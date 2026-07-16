# ADR-2607170800: yabai — Cloudflare zone HTTP-scanner ingest（kotoba estate honeypot）

## Status

Accepted, implemented, verified end-to-end（2026-07-16）。実装は `orgs/etzhayyim/com-etzhayyim-yabai`（新規 `methods/cf_sweep.cljc`、`methods/ingest.cljc` に portable bridge 追加、`methods/test_cf_scanners.cljc`、`data/passive-dns.merged.kotoba.edn` 再生成）。live sweep・offline rebuild・analyze/autorun heartbeat の全経路を実データで確認済み。

## Context

オーナー指示（2026-07-16、時系列）:
1. 「今の club-shinshi の itonami は?」→ BMC/Lean Loop 稼働確認。訪問数の funnel が bot/scanner で水増しされていることが判明。
2. 「これは実際の人間のアクセス?」→ shinshi.club の traffic の大半は WordPress/PHP 脆弱性スキャナ（人間ではない）と判定。
3. 「こういった攻撃をしてきている ip を特定して yabai で登録して公開してほしい」→ shinshi.club 単体で 8 IP を IOC 化（`:indicator/category :scanner` 新設）、yabai main + IPNS + west pin へ公開。
4. 「yabai などに, データを公開してください. block は不要. kotoba 全体が honneypot としても機能させたいので.」→ 観測面を **14 の operator-owned Cloudflare zone 全体**へ拡大し、221 IP を IOC 化して公開。block はしない（catch-all 200 が scanner を引きつけ続ける honeypot として機能する）。
5. 「next」→ 本 ADR。この収集を **ad-hoc python でなく yabai の .cljc パイプラインに配線**し、日次自走できるようにする。

**実装時に発覚した重大なギャップ**: 手順3・4で公開した IOC ファイル（scanner 群）も、既存の `email-phishing-gftd-202607`・`sms-smishing-jp-2026h1` も、`data/*.kotoba.edn` に存在するだけで **CTI 分析パイプラインが読む `passive-dns.merged.kotoba.edn` には畳み込まれていなかった**（merged は indicator 3 件のみ）。`autorun.cljc`（heartbeat）と `analyze.cljc` は merged グラフ（か seed）しか読まないため、公開済み IOC は**孤立して分析に反映されていなかった**。手3・4 の python 収集も yabai のコードを通っておらず、再現性・自走性が無かった。

**前提の棚卸し（コード直読で確認）**:
- `ingest.cljc` は portable な bridge 群（`bridge-pdns`・`parse-crtsh`）+ `merge-rows`（seed+bridged の 2引数 dedup）を持つ。JSON reader（`parse-json`）も inline 実装済み。
- `autorun.cljc run-cycle` は `graph-path*`（merged が在れば merged、無ければ seed）を読み、`classify → analyze → derived-datoms → append-only kotoba Datom log`。G6/G10（`:access/*` 平文なら hard-stop）を毎 cycle で強制。deterministic・no-live-I/O。
- `analyze.cljc` は indicator を **カテゴリ非依存**で集計（`:cat-load` = category 分布）→ `:cti/ioc-category-load <cat>` derived datom を出す。つまり `:scanner` は新カテゴリだが analyze 側の変更は不要で、merged に入りさえすれば自動集計される。
- yabai CLAUDE.md は WIT export `cf-metrics-ingest@1.0.0` と "CF Traffic Analysis — Logpush + GraphQL" を既に設計面に持つ。Cloudflare メトリクス ingest は設計意図に合致。
- 全 methods は **bb（babashka）** ランタイム上で `--classpath` 実行。live network I/O は `#?(:clj …)` に隔離する house style（`kotoba.cljc` の inline `java.security`、autorun の `#?(:clj)` file I/O が先例）。

## Decision

### 1. 検出ロジックを `ingest.cljc` の portable bridge に一本化（single source of truth）

`methods/ingest.cljc` に純関数として追加（bb/JVM/cljs で同一挙動、test でピン留め）:

- `scanner-probe-re` / `probe-path?` — kotoba zone（SvelteKit/cljs）が提供しない路（WordPress/Joomla surface、`.env`/`.git` 秘密、`/actuator`・`/telescope` framework console、phpunit eval-stdin RCE 等）への request を「敵対的偵察」と判定する述語。**honeypot 前提**: これらの路は正規に存在しないので、operator-owned zone への当該アクセスは曖昧さなく hostile。
- `scanner-tier` — 信頼度階層。`>=4 zones or >=500 probe-req → 900/confirmed`、`>=2 zones or >=50 → 820/confirmed`、それ以外 `700/candidate`。
- `bridge-cf-scanners` — 正規化観測 `[{ip cc path count zone day}]`（`bridge-pdns` と同じ string-keyed JSON 形）を source IP 単位で集約し `:indicator/* :scanner` IOC 行に変換。deterministic（zones desc・probe desc・ip asc でソート、1 IP 1 行）。フロア無し（probe パスに 1 回でも触れた全 IP を捕捉）= honeypot の網羅方針。
- `merge-many` — 多数の row-seq を id で dedup（first-seen wins、seed を先頭に）。`merge-rows`（2引数）の一般化で、**data/ の全 IOC ファイルを merged グラフに畳み込む**ための核。

### 2. live collector + offline rebuild driver（`methods/cf_sweep.cljc`、G7-gated）

bb-only の `#?(:clj)` driver（portable とは主張しない。detection は §1 を再利用）:

- `sweep-live! [from to]` — **GATE-G7**（`--live` + `YABAI_OPERATOR_GATE`）。14 zone × 各 UTC 日を Cloudflare GraphQL `httpRequestsAdaptiveGroups`（clientIP × path × country、1日/クエリ上限を日ループで回避）で取得 → `groups->obs`（Cloudflare 共有 egress `2a06:98c0:3600::103` を除外＝shared-infra discipline）→ `bridge-cf-scanners` → 日付き `data/http-probe-scanners-kotoba-<period>.kotoba.edn` を書き出し → `rebuild-merged!`。GraphQL POST は **curl サブプロセス経由**（bb/SCI が `HttpURLConnection.getOutputStream` を禁ずるため。keychain 読取と同じ ProcessBuilder パターン、依存追加なし）。CF token は env `CF_API_TOKEN` → macOS Keychain `gftd.cf`。
- `rebuild-merged!`（offline 既定）— seed + `data/*.kotoba.edn` の全 curated IOC/graph ファイルを `merge-many` で畳み込み `passive-dns.merged.kotoba.edn` を再生成。**§Context の孤立ギャップを閉じる**。生成物（手編集禁止ヘッダ付き）。

### 3. データの統合（手 python 成果物を tool 出力に置換）

手3・4 で hand-python 生成した `http-probe-scanners-kotoba-202607`（221 IP）・`http-probe-scanners-shinshi-202607`（8 IP）を **削除**し、`sweep-live!` が同一 window（2026-07-13..16）で再生成した `http-probe-scanners-kotoba-20260713-20260716.kotoba.edn`（**488 IP**、23 high / 61 mid / 404 candidate）に一本化。488 は手製 221∪8 の厳密な上位集合（フロア除去で単発 probe IP も捕捉）。以後この収集は再現可能・機械所有。

### 4. 自走（fleet cron）

`rebuild-merged!` は既存 heartbeat（`yabai_cti_ingest`/`weave`/`persist`、murakumo fleet）が読む merged を更新する offline 経路。**live sweep は G7 gated なので heartbeat 本体には入れず**、別の operator-gated cell（例: `yabai_cf_sweep`、日次 `bb --classpath <cp> methods/cf_sweep.cljc --live --from <D-3> --to <D>`）として `50-infra/murakumo/fleet.toml`（murakumo repo、本 superproject 未 checkout）に追加する。本 ADR ではその cron セル定義を指定するに留め、fleet.toml の実編集は murakumo repo 側の follow-up とする（見えないファイルを憶測編集しない）。

## Consequences

**Good**:
- 公開済み IOC（scanner 488 + phishing 40 + scam/benign/tor-exit）が **CTI heartbeat に到達**。実測: merged 3→531 indicator、`run-cycle` が `[:db/add "cti-cat-scanner" :cti/ioc-category-load :scanner … :cti/indicators 488]` を append-only ログに永続化、G6/G10 保持（access_encrypted 1 / plaintext_violations 0）。
- 検出ロジックが 1 箇所（`ingest.cljc`）に集約。live sweep も offline replay も同じ bridge を通る。`test_cf_scanners.cljc`（7 tests / 29 assertions）で probe 判定・tier・集約・順序・shared-egress 除外・merge dedup をピン留め。
- 収集が再現可能・自走可能に（ad-hoc python 廃棄）。block しない honeypot 方針を明文化（catch-all 200 維持）。

**Bad / 負債**:
- hand-python 版の per-IP RDAP 所有者・behaviour ナラティブ（TECHOFF SRV LIMITED/DMZHOST の防弾ホスティング、Azure/DO 乗っ取り VM 群、94.154.43.x の `.env` 連番散布）は bridge 出力には載らない（schema フィールドのみ）。その intel は本 ADR に保存。RDAP 補足を自動経路に入れるかは follow-up（per-IP live lookup は heavy）。
- `cf_sweep.cljc` の live 経路は G7 手動 gate のまま（fleet cron 化は murakumo repo 側 follow-up）。
- yabai の `run_tests.sh` は repo 再構成（`20-actors/yabai/methods/` → `methods/`）で classpath が壊れたまま（本変更前から）。test は `yabai/methods` レイアウトを classpath 上に再現して実行した。harness 修復は別 follow-up。
- clj-kondo は `#?(:clj)` 内 require の未使用や `clojure.java.io` 未 require を warn するが、これは既存 baseline（ingest/autorun）と同型の reader-conditional false-positive。runtime は検証済み。

## Alternatives considered

1. **live sweep を autorun heartbeat に直接組み込む** — 却下。heartbeat は deterministic・no-live-I/O（G6/G10 の不変条件を `test_autorun` が守る）。live network はその契約を破る。ingest 側（G7 gated）に置き、heartbeat は offline merged を読むだけ、が正しい分離。
2. **nbb（.cljs）で collector を書く** — オーナーの「新規スクリプトは nbb」方針に沿うが、yabai の methods 群・test harness は全て bb 上で、bridge（.cljc）を single source of truth として再利用するには同一ランタイムが最も一貫。CLAUDE.md も「リポジトリ運用ツールは現状 bb が正本」と明記。よって bb/.cljc を採用。
3. **手製ファイルを残し 488 と併存** — 却下。stale な 221 と重複し merged が冗長化。tool 出力に一本化して再現性を担保。
4. **scanner IP を block（WAF ルール）** — 却下（オーナー明示「block は不要」）。honeypot として catch-all 200 を維持し、observe & score に徹する。

## References

- ADR-2605301400 §T3（yabai CTI/DNS/IP-history の kotoba-native 化、autorun heartbeat・G6/G10）
- `orgs/etzhayyim/com-etzhayyim-yabai`: `methods/ingest.cljc`（`probe-path?`/`scanner-tier`/`bridge-cf-scanners`/`merge-many`）, `methods/cf_sweep.cljc`（`sweep-live!`/`rebuild-merged!`/`groups->obs`）, `methods/test_cf_scanners.cljc`, `data/http-probe-scanners-kotoba-20260713-20260716.kotoba.edn`, `data/passive-dns.merged.kotoba.edn`(生成物)
- yabai CLAUDE.md: WIT `cf-metrics-ingest@1.0.0`、"CF Traffic Analysis — Logpush + GraphQL"
- 先行 IOC: `data/email-phishing-gftd-202607.kotoba.edn`（gftd collections-scam）, `data/sms-smishing-jp-2026h1.kotoba.edn`
- Cloudflare GraphQL: `httpRequestsAdaptiveGroups`（1日幅上限 → 日ループ）

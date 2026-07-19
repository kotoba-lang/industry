# cloud-itonami 成熟度 /loop — session iteration log

**Charter**: ADR-2607189200
**Canonical maturity tracker**: `canvas-ledger.edn` (actor-driven, append-only, 手編集禁止).
  このファイルは並列 tracker ではなく、この Claude Code `/loop` session が
  *何を計測し何をしたか* を残す session log のみ。score 数値は `maturity-scores.edn`
  (生成物) を正とし、本ファイルには書かない。

**North star**: cloud-itonami portfolio 成熟度は first external paid conversion まで
  据え置き (捏造ゼロ)。本 loop の owned lever = audit blocker #3
  (free UI から未リンクの pricing/upgrade CTA) の実装 → 効果計測。

---

## Iteration 1 — 2026-07-18

**Measured (baseline, maturity-scores.edn as-of 2026-07-15):**
- BMC 78 / YC 56.7; 不足軸 revenue=1, validation=1.5, distribution=2
- 3 軸とも gate `:hyp/itonami-smb-pay` (externalPaid>=1) で blocking、externalPaid=0
- hourly routine `itonami-react-growth-hourly` は 02:08〜10時間以上 stuck:
  advisor が同一文字列の funnel-metrics + nudge を毎 tick propose → governor が
  毎回 "duplicate item" reject (seq 3865→3903)。accept される観測は実数値埋め込みのみ。

**Did:**
- basline + bottleneck を確定。revenue 自体は safety-floor ② (資金) で owner 起因、
  自律閉环できる唯一の lever は audit blocker #3 (pricing CTA) と判定。
- charter ADR-2607189200 を起票 (EDN, north-star + implementation-arc + invariants)。
- `cloud-itonami.github.io` を fetch → catalog 静的ページ (sitemap/robots/index) で
  あり product surface ではないことを確認。pricing/free-claim は main worker
  `orgs/cloud-itonami/cloud-itonami` (未 checkout) にあることを特定。

**Next (iter 2):**
- `west update --fetch smart cloud-itonami` で main worker を取得
- `/isco-1212` free-claim UI と `/api/billing` 周辺を特定し、pricing CTA
  (Managed Starter ¥80k/月 Payment Link への upgrade リンク/バナー) の挿入点を決める
- worktree (superproject 外) で実装に入る前の設計のみに留め、盲撃ち実装しない

**Did NOT (honest):**
- score 数値は一切動かしていない (externalPaid=0 のまま)
- live 5820 CRM API は触っていない (監査台帳汚染リケット回避)
- cockpit worker repo の実コードには未着手 (次 iter)

---

## Iteration 2 — 2026-07-18 (14:16Z)

**⚠ CHARTER INVALIDATION**: iter1 の前提 (blocker #3 = pricing CTA 不在 → 実装が lever)
は **誤り**。main worker を fetch して読んだら blocker #3 は全部 build 済みだった。
charter ADR-2607189200 は revision 済み (本 iter の finding に pivot)。

**Did (locate + diagnose — 実装せず):**
- `west update --fetch smart cloud-itonami` → 実 path は `orgs/gftdcojp/cloud-itonami`
  (iter1 charter の `orgs/cloud-itonami/cloud-itonami` は誤り、訂正済)。
- conversion infra が **既に全部 live** であることを確認:
  - /isco-1212 tenant page に "Subscribe via Stripe Checkout" button 既存 (isco_1212.cljc:151)
  - trial->paid nudge 完全 wired: daily GHA cron nudge-scan.yml → POST /api/nudge/scan
    → nudge/decide governor → Resend email (ADR-0025)。threshold=20回, cooldown=30日
  - Payment Link live (ADR-2607161745)
- live /api/fleet/metrics 取得 (read-only, 2026-07-18T14:16:31Z):
  externalTotal=4, **externalPaid=0**, stripe.activeSubscriptions=0, customerBindings=0,
  agentRuns7d=34357, bottleneck="run Stripe checkout via /isco-1212/",
  nextOwnerActions=[first-paid-checkout]。**システム自身が残る唯一の bottleneck は
  owner の checkout 実行と報告。**

**Finding (honest):**
- portfolio score (revenue/validation/distribution) は externalPaid>=1 で hard-gate。
  externalPaid は実 ¥80k/月 subscription = owner/market action で safety-floor ②。
  → **agent 主導で portfolio score を上げる code-actionable lever は存在しない** と確定。
- 監査 doc (同日) は code review 無しに書かれたため stale だった。loop が盲目的に
  CTA を実装していたら、既存 button の隣に重複 CTA を置く無駄作業になっていた。

**Next (iter3):**
- loop を within-product vertical 成熟度に pivot。flagship (6399/6310/7810/5820) の
  Impl-product / storefront を code で 1 段上げる、または 146 の "actor without
  storefront" から 1 つ storefront 化する — これが本物の code-actionable 成熟度。
- 候補選定: vertical-maturity cohort 表で product-score=1-2 かつ Design>=4 (設計は
  mature だが product face が thin) の vertical を 1 つ選ぶ。

**Did NOT (honest):**
- portfolio score は据え置き (externalPaid=0)。nudge scan の prod 実行成否 (GHA run
  history が empty で不明) の深掘りは iter3 以降。実 checkout は owner。

---

## Iteration 3 — 2026-07-18 (loop converged on portfolio diagnosis)

**Diagnosis refined (nudge scan trigger + preflight):**
- nudge-scan.yml 自身のヘッダが明記: **GHA が repo level で disabled** 確認済み
  (`gh api repos/gftdcojp/cloud-itonami/actions/permissions` -> `{enabled:false}`)。
  よって daily nudge scan は **prod で 1 度も fire していない**。4 tenant への
  upgrade email は (code が正しいにも関わらず) 届いていない。これが iter2 の
  empty `gh run list` の理由。
- ただし **armed しても現 4 tenant には moot**: nudge governor は hr/* endpoint
  20回使用で初めて :send。agentRuns7d 34k は内部/ops 起因 (audit) で、外部 4 tenant
  は閾値未満の可能性が高い → 仮に scan を動かしても hold のまま。binding constraint
  は market (lead quality / free tier で十分) = code 非対象。
- checkout preflight は **paid-tenant (externalPaid=0) 1件以外すべて GREEN**:
  free path 6340/6340, Stripe ready (live), funnel URL 全 200。owner は
  /isco-1212/ から即 checkout 可能。

**Loop convergence (3-angle verification, 同一結論):**
- iter1: audit → blocker #3 (pricing CTA)
- iter2: blocker #3 は既 build 済 + live metrics で externalPaid=0
- iter3: nudge scan は dead-but-moot + preflight GREEN
- 結論: **portfolio 成熟度を上げる唯一のものは owner の first paid checkout
  (preflight green) または real outbound。agent 側 code lever は存在しない。**
  本 loop は portfolio 診断において完全に収束。以後の 30min 反復を portfolio
  診断に使うのは theater。

**Decision point (owner):** loop を (a) stop / (b) within-product vertical
  成熟度 worker に再利用 (5820 dogfood "Ready to execute" 等) / (c) externalPaid
  変化のみ watch する軽量 monitor — のいずれにするかは owner 判断で ask 済。

**Did NOT (honest):** portfolio score 据え置き。nudge scan の re-arm は (実 tenant
  への実 email = 外部不可逆 action + 現 tenant には moot) なので agent 単独で
  armed にせず owner 判断に回した。

**Owner decision (iter3 ask → 回答):** within-product vertical 成熟度を code で
  上げ続ける (推奨) を選択。portfolio 診断は収束済。以後の loop は各 iter で
  bounded code で cloud-itonami-vertical-maturity.edn の Impl-product/storefront 軸を
  1 つ上げる。superproject orgs/ の共有 checkout は直接触らず sibling-path worktree で隔離。

---

## Iteration 4 — 2026-07-19 (first within-product iteration)

**Target:** 5820 dogfood (Phase 1.2/1.3) — cloud-itonami 自身の 4-vertical funnel
  を 5820 CRM pipeline に map する seed。今日 doc "Ready to execute"、priority #2
  (flagship product->business) に合致。5820 は既に product-score 4 (operator-quickstart/
  api/DESIGN あり) だが dogfood は未開始。

**Did:**
- 5820 src を読み store shape を特定 (:account/id+name+subscription-tier+active,
  :rep/id+name, :opportunity/id+stage)。crm/http.clj:184-234 を読み、:escalate
  (human-in-the-loop) を resume する HTTP route が **未実装** (graph 本体は
  sim.cljc:36 の {:thread-id .. :resume? true} で resume 可能) ことを確認。
- `90-docs/business/cloud-itonami-5820-dogfood-seed.edn` 作成 (tx-data doc-wrapper,
  verify clean): 5820 store shape に合わせた Phase-1 seed = 1 rep + 4 account
  (6399/6310/7810 free, 5820 self-ref) + 5 opportunity (4 external tenant = trial/lead
  paid=0, 1 self-ref agreed=intended)。捏造ゼロ: Closed Won=0, externalPaid=0 明記。

**Finding (code gap = next iter target):**
- 5820 CRM の HTTP 層に **escalation resume endpoint が無い** (api.md "Honest scope"
  が明記)。これが dogfood を HTTP 経由で端から端まで駆動するのを block している。
- 次 iter (iter5) の code target を precise に特定済み: **crm/http.clj に
  POST /approve (thread resume) endpoint 追加** — thread-id + :approve|:reject を受け
  g/run* を {:thread-id .. :resume? true} で再開。これが閉じれば dogfood seed が
  /propose + /approve で live 適用可能になる。

**Next (iter5):** 5820 repo (orgs/cloud-itonami/cloud-itonami-isic-5820) で
  POST /approve endpoint を worktree-isolated で実装 + test。pin 鮮度確認
  (local cfd1542 vs origin/main b0ab372 — 要 compare) → sibling worktree →
  commit/push → server-merge。build/test は resource-guard 経由。

**Did NOT (honest):** 5820 Impl-product score の再計算は未実施 (seed 適用 + endpoint
  実装が land してから)。実コード (endpoint) は iter5。portfolio score 据え置き。

---

## Iterations 5–7 — backfill note (2026-07-19)

**⚠ この session log ファイル自体が、共有 superproject checkout に対する激しい並行
  更新の影響で iteration 5/6/7 の本文を一度失っていた** (iter8 セッションが確認: ローカル
  disk 上で 232 行/6 iteration まで見えていた版が、後続の並行 fast-forward 更新で
  156 行/4 iteration の commit 済み版に置き換わっており、その間の working-tree 差分
  (未 commit) が回収不能になっていた — 中身のコード成果自体は各子リポの git 履歴に
  実在するため実害はコードになく、この session log の narrative のみが欠落)。以下は
  git 履歴 (`gh api` で直接検証済み) から再構成した要約 — 詳細な逐語ログは失われたため
  簡潔に記す:

- **iter5 (2026-07-19)**: `cloud-itonami-isic-5820` の `crm/http.clj` に
  `approve-decision` fn + `POST /approve` route を実装 (escalate → approve/reject で
  `g/run*` を `{:thread-id .. :resume? true}` 再開)。sibling worktree で実装し、
  test 5件追加・15/15 pass 確認後、commit `6755118` → server-side merge →
  isic-5820 main = `8de59cb9`。
- **iter6 (2026-07-19)**: superproject `manifest/west.yml` の isic-5820 pin を
  `cfd1542`→`8de59cb9` に前進 (GitHub API single-entry commit、pin 検証 clean ff
  確認済み)。superproject main commit `d1a841eb`。
- **iter7 (2026-07-19, 別セッション/並行実行と推測)**: isic-5820 の `docs/api.md`
  の stale な "no approval endpoint" 記述を修正 (commit `f8b7fc3d` → `bdf54dd0`)。
  superproject 側 pin もこれに追従して `bdf54dd0` まで前進済み (現在の west.yml で確認)。
  本セッションはこの iter7 を**実行していない** — 気づいた時には他プロセスが既に
  landed 済みだった。

**教訓 (iter8 が明示的に記録)**: この charter の作業は、この 1 session だけでなく
  複数の並行 Claude Code セッション/自動化 (`Wave5` 系の flagship-checklist-scan
  iteration も同時並行で iteration 15 まで進行中を iter8 で確認) が同時に触っている。
  以後この log に書く内容は「このセッションが観測した時点の事実」に限定し、
  「次にやるべきこと」の断定は避ける (他セッションが既にやっている可能性が高いため)。

---

## Iteration 8 — 2026-07-19 (diagnosis: "deploy 5820 live" is not a valid next step)

**Target:** iter6 の "Next (iter7 候補)" (a) 「5820 endpoint を live itonami.cloud/
  isic-5820/ に deploy」を検証しようとした。

**Did (diagnosis only, isolated worktree, no live mutation):**
- `orgs/cloud-itonami/cloud-itonami-isic-5820/README.md` (~L147) を読み、この repo が
  **self-host-only の OSS business-in-a-box** であり、自身では deploy しない設計
  であることを確認 ("no docker push/registry step and no cloud-deploy automation ...
  out of scope here" と明記)。
- fresh agent (isolation: worktree) に、cockpit repo (`gftdcojp/cloud-itonami`,
  west 名 `cloud-itonami`, pin `d907064...`) を fetch させ、`/isic-5820/` がどう
  ライブになっているか調査させた。結果:
  - cockpit の `deps.edn` に isic-5820 への依存 (`:local/root` 等) は **ゼロ**。
  - cockpit の `public/_redirects` / `functions/api/open-business/[isic].js` の
    静的レジストリにも 5820 は **登録されていない**（登録済みは 3512/3600/3830/
    6310/7810/6399/6810/8569/8691/8810/3011/2811/2410 + UNSPSC 5件のみ）。
  - `cloud-itonami-vertical-maturity.edn` が言う "live face /isic-5820/" の正体は
    `public/marketplace.json` の単なる **リンクアウト**（`demo:
    https://cloud-itonami.github.io/cloud-itonami-isic-5820/`）で、これは
    **isic-5820 repo 自身の GitHub Pages**（`gh api .../pages` で `status: built`
    確認済み、Jekyll 静的ビルド）— cockpit の build/deploy パイプラインとは無関係。
  - GitHub Pages は静的ファイルのみ配信するため、`crm/http.clj` の Clojure サーバ
    (= 今回の `POST /approve`) は **構造的に絶対にそこでは動かない**。
  - cockpit 自身の deploy pipeline も確認したところ **死んでいる**
    (`gh api repos/gftdcojp/cloud-itonami/actions/permissions` → `enabled:false`、
    実 deploy は `lefthook` の `post-merge` hook 経由 `wrangler pages deploy` で、
    誰かのローカル checkout が `origin/main` を pull した時にしか発火しない)。

**Finding (honest — corrects iter6's own assumption):**
- **「5820 pin を bump して cockpit を redeploy すれば live になる」は誤った前提
  だった** — bump すべき pin が cockpit 側に存在しない。redeploy しても
  isic-5820 の GitHub Pages 静的内容は一切変わらない。
- `POST /approve` を真に reachable にするには、(a) isic-5820 は self-host 専用と
  割り切り "live face" は静的デモ止まりと認める、または (b) cockpit に新規 proxy
  route を足す/isic-5820 自身に実 Cloudflare Workers deploy 経路を作る、という
  設計判断が要る — これは 1 iteration の bounded bump では済まない scope で、
  owner 判断が必要 (本 iteration では設計しない)。

**Did NOT (honest):** cockpit repo への変更・deploy は一切行っていない
  (diagnosis-only)。cockpit の test suite はフル実行できず (25+ sibling repo
  依存で classpath 解決不可、ci.yml 自身が `continue-on-error: true` にしている
  既知の制約を再現しただけ)。portfolio score・5820 score とも据え置き。

**Next:** cockpit 統合の設計判断は owner に投げる。within-product 側の code-actionable
  な次の一手としては、iter6 の wave5 系 (`cloud-itonami-flagship-generator-template.edn`)
  が既に証明済みの「no-demo repo にテンプレートを適用」パターンを別 repo に展開する方が
  安全 — ただし前述の通り Wave5 系の並行 iteration が既にこれを高頻度で進めている
  (iter8 時点で iteration 15 まで確認) ため、本セッションが同じ対象を選ぶと衝突する
  リスクが高い。次に本セッションが継続する場合は、Wave5 の最新状態を必ず再確認してから
  重複しない対象を選ぶこと。

---

## Iteration 9 — 2026-07-19 (first ISCO-side demo: cloud-itonami-isco-1211)

**Target:** iter8 の教訓通り、再開時に Wave5 の最新状態を確認したところ iteration 17
  まで進行 (56 業種、ISIC 側 ~290-repo cluster を対象)。ただし
  `cloud-itonami-flagship-rollout-ledger.edn` を grep した結果、**`isco-` (ISCO 職業
  repo, 216件) は Wave5 が一度も閉じていない**(唯一の "isco-1212" 言及はフラグシップ
  テナントページで無関係)ことを確認。`cloud-itonami-flagship-checklist-scan.edn` では
  ISCO 216件中 **126件が `:item2/classification "unknown-no-demo"`**（デモ皆無）—
  Wave5 が触っていない、衝突しない独立領域と判断。

**Did (fresh agent, isolation:worktree, background):**
- ISCO repo の実際の src 形状を先に自分でサンプル確認: `isco-1111`/`isco-2111`/
  `isco-1211`/`isco-1311`/`isco-0110` はすべて `src/<domain>/{governor,actor,
  advisor,store}.cljc` の**4ファイル構成**で完全一致 — ISIC 側テンプレートの
  7ファイル構成 (`facts/phase/sim/governor/operation/advisor/store`) とは異なる、
  ISCO 固有の一貫した形状と確認。
- fresh agent に、ISIC 側の実証済み template 仕様 (`cloud-itonami-flagship-
  generator-template.edn`) を読ませた上で、ISCO 固有形状に合わせた bespoke
  render 実装を1 repo で実証させた（"batch 6件" の Wave5 段階ではなく "1件で
  実証" の isic-851/6820 段階に相当）。
- agent が **`cloud-itonami-isco-0210`/`0310` を stub と正しく screen-out**
  (`run-request!` が `{:stub true ...}` を返すだけで実 StateGraph 呼び出しが
  存在しないことを確認、ISIC 側の isic-2100/isic-1101 screen-out と同型の判断)。
  **`cloud-itonami-isco-1211`**（ISCO-08 1211 Finance Managers、`finmgmt` ドメイン）
  を選定 — 実 StateGraph (intake→advise→govern→decide→commit/hold) + governor
  7-rule + 既存 test (`fresh-store` fixture, 14 tests/36 assertions) を確認。
- seed データは既存 `finmgmt.actor-test` の `fresh-store` fixture (client-1
  "Kobo Works" + budget-line L-ops 100000 + 80000→30000 支出シーケンス) をそのまま
  流用 — 捏造ゼロ。2人目クライアント (client-2) は実 API (`register-client!` 等)
  経由で追加したことを docstring に明記 (既存 fixture への上乗せと正直に開示)。
- `src/finmgmt/render_html.clj` (11-request シナリオ、governor 7理由中6理由を
  実際にトリガ、7つ目 `:no-actuation` は実 mock-advisor 経由では到達不能と正直に
  記載) + `.github/workflows/regenerate.yml` + `docs/samples/operator-console.html`
  を実装。
- 検証 (ISIC 側と同じ基準): (a) ローカル2回連続実行が同一 sha256、(b) `.cpcache`
  無しの from-scratch clone での再実行が dev run と byte-diff ゼロ、(c) 実
  `workflow_dispatch` run `29676202519` が `conclusion=success`、CI 自身の
  regenerate ステップも "console unchanged" (=byte一致)、(d) live GitHub Pages
  (`cloud-itonami.github.io/cloud-itonami-isco-1211/samples/operator-console.html`)
  が HTTP 200 + 同一 sha256 で配信されていることを `curl` で実測確認。既存
  test suite も前後とも 14/36 green。
- commit `e6d500de9579` (feat) → server-side merge `f72607011590` → isco-1211
  main に landed。feature branch は merge 後に削除。superproject `orgs/` 共有
  checkout は未変更 (read-only のまま)。

**Finding:** ISIC 側で証明されたパターン（build-time 生成デモ + regenerate.yml +
  4段階検証）は、形状の異なる ISCO 側にも**手法として移植可能**（コード形状は
  移植不可、都度実データを読んで適応が必要）ことを実証。ISCO 126件の no-demo
  backlog に対して "isic-851/isic-6820" 相当の一番目の実証が完了 — 今後 Wave5
  同様の batch 展開ができる可能性がある (ただし batch 化はこの iteration の
  スコープ外、次の判断)。

**Did NOT (honest):** superproject `manifest/west.yml` は未変更（isco-1211 の
  pin 前進は別途必要、本 iteration のスコープ外と明示）。`cloud-itonami-
  flagship-checklist-scan.edn` / `-rollout-ledger.edn` への追記もしていない
  (Wave5 自身の生成物であり、並行実行中の別プロセスの管轄と判断、衝突回避)。
  portfolio score・ISIC 側スコアとも据え置き。

**Next:** (a) 誰かが isco-1211 の pin を superproject west.yml に反映すれば
  fleet 全体の可視性が上がる、(b) ISCO 側 no-demo 126件への batch 展開
  (Wave5 の ISIC batch 化と同型) は次の判断だが、対象が multiple agent の並行
  作業と衝突しやすいので、次回起動時も必ず最新状態を再確認してから 1 repo ずつ
  進めること (→ iter10 で isco-1211 の pin 前進を実施、続けて2件目の実証も実施)。

---

## Iteration 10 — 2026-07-19 (local cron loop へ移行 + ISCO 2件目実証: isco-1111)

**運用変更:** ユーザー指示で dynamic ScheduleWakeup 自己ペースから、
  **local cron job（`*/15 * * * *`, session-only, 7日で自動失効）**に切替。
  以後の iteration はこの cron 発火で起動する。

**Did (superproject 側、直接実施):**
- iter9 の "Did NOT" だった **isco-1211 の west.yml pin 前進**を実施
  (`aa96640c`→`f7260701fbde452772d1c587c4db44356e65`、GitHub API 単一 entry
  commit、ff clean 検証済み、superproject commit `86f2755cac36`)。

**Did (fresh agent, isolation:worktree, background — ISCO 2件目実証):**
- 起動前に Wave5 rollout ledger を再確認 (`grep isco- ledger.edn` = 依然
  `isco-1212` のみ) — 衝突なしを確認済み。
- agent が isco-1111/1112/1213/2111/2112/1312/1322/2131 の8候補を screening
  (stub でない実 StateGraph 実装であることを個別確認)。
  **`cloud-itonami-isco-1111`**（ISCO-08 1111 Legislators, `legislature` ドメイン）
  を選定。
- seed データは既存 `test/legislature/{actor,governor}_test.clj` の
  `fresh-store` fixture (`constituent-1` "Alice Voter" + `bill-2024-042`
  "Education Reform Act") をそのまま流用。2人目 (`constituent-2` "Jordan Reyes")
  は実 API (`register-constituent!`) 経由で追加したことを docstring に明記
  (捏造ゼロ、iter9 と同じ開示水準)。
- governor 7 rule 中 5 rule を実 mock-advisor 経由で実際にトリガ (8-request
  シナリオ)。残り2つ (`:no-actuation`, `escalates-on-low-confidence`) は
  実 advisor の confidence floor (0.7) が governor 閾値 (0.6) を下回らない
  ため構造的に到達不能と正直に docstring に記載。
- `src/legislature/render_html.clj` + `.github/workflows/regenerate.yml` +
  `docs/samples/operator-console.html` を実装。GitHub Pages は未設定だった
  ため API 経由で有効化 (`main`/`/docs`, legacy Jekyll, iter9 と同型)。
- 検証 (iter9 と同一4基準): (a) ローカル2回連続実行 sha256 一致
  (`d24310b7aadf...`)、(b) `.cpcache` 無し clean clone 再実行が byte 一致、
  (c) 実 `workflow_dispatch` run `29676896454` が `conclusion=success`、CI 自身の
  regenerate ステップも "console unchanged"、(d) live Pages
  (`cloud-itonami.github.io/cloud-itonami-isco-1111/samples/operator-console.html`)
  — **本セッションが `curl` で直接再検証**: HTTP 200、sha256
  `d24310b7aadfd29406819aeef3b177e78e125ca7f83b3823697c061d8abc2111` が
  agent 報告値と完全一致。既存 test suite 12 tests/31 assertions 前後とも green。
- commit `b9a9351aac28` (feat) → server-side merge `cbbf9b78ff32` → isco-1111
  main に landed。feature branch は merge 後に削除。superproject `orgs/`
  共有 checkout は未変更 (read-only のまま、agent 自身も確認済み)。
- 続けて本セッションが isco-1111 の west.yml pin も前進
  (`43cea993`→`cbbf9b78ff327b25d491cc3b6fd0fd42930fa657`、ff clean 検証済み、
  superproject commit `68c44e5117`)。

**Finding:** ISCO 側の実証が2件目 (isic 側の isic-851→isic-6820 と同型の
  "2件実証してから batch 化検討" 段階に到達)。2件とも: stub でない real
  actor を screening で見分ける、既存 test fixture から seed データを流用する
  (捏造ゼロ)、advisor の到達不能ルールを正直に開示する、という同じ規律で
  再現できることを確認 — 手法が repeatable であることの追加証拠。

**Did NOT (honest):** ISCO 側 batch 展開 (Wave5 の ISIC 6-at-a-time 相当) は
  まだ着手していない — 2件では batch 化を正当化するにはまだ早いと判断
  (ISIC 側も2件確認後すぐには batch 化していない)。
  `cloud-itonami-flagship-checklist-scan.edn`/`-rollout-ledger.edn` への
  追記はしていない (Wave5 自身の管轄、衝突回避のため引き続き非関与)。
  portfolio score・ISIC 側スコアとも据え置き。

**Next:** 次回 cron 発火時、まず Wave5 の最新状態と本ログを再確認。ISCO
  側3件目の単発実証を続けるか、この2件を元に batch 展開 (テンプレート化)
  を検討するかを、その時点の状況で判断する。cron は 15分毎で発火し続ける
  ため、前 iteration の background agent がまだ動いている場合は新規agent
  を重複起動せず待機すること。

---

## Iteration 11 — 2026-07-19 (first ISCO-side batch: 3 repos in one pass)

**Target:** iter9/10 で2件実証済み (isic 側の isic-851→isic-6820 と同型の
  節目) に到達したため、ISIC 側 Wave5 が batch 化に移行したのと同じ判断で、
  ISCO 側でも初の batch (3件、Wave5 の 6件よりまず小さく) を試行。

**運用ノート (cron との整合):** cron (15分毎) が iteration 11 のバッチ実行中
  にも発火し、background agent がまだ完了通知を返していなかったため、
  その回は新規 agent を起動せず待機のみで応答 (重複起動回避、正しく機能した)。

**Did (fresh agent, isolation:worktree, background, ~20分):**
- 起動前に Wave5 rollout ledger を再確認 (`isco-1211`/`isco-1212` のみ、
  Wave5 は iter9 の isco-1211 を「別セッションの進行中作業」として認識・
  非干渉と明記) — 衝突なしを再確認。
- 24候補中5件で実 JVM test suite を走らせて screening。
  **`cloud-itonami-isco-2111`(physics) は3/14 test が既存の実バグ
  (advisor が `:finalized?` を伝播しない) で fail することを発見** —
  このバッチでは無理に含めず正直に対象外とした (低品質を避けるため
  "3件必達" より品質を優先、指示通り)。
  代わりに **isco-2113(chemistry) / isco-1213(policyplan) /
  isco-1112(administration)** の3件、全て test green を選定。
- 3件とも: 既存 test fixture から実 seed データを流用 + 追加エンティティは
  実 API 経由で追加したことを docstring に明記（iter9/10 と同じ開示水準）。
  isco-1112 では governor の docstring と実装コードの不一致
  ("registered AND verified" と書いてあるが実装は存在確認のみ) も発見・
  正直に開示。
- 3件それぞれに render-html + regenerate.yml + operator-console.html を実装、
  GitHub Pages を有効化 (未設定だったため)。
- 検証 (iter9/10 と同一4基準、3件それぞれ独立に実施): 全件 idempotent、
  clean-checkout byte-match、live workflow_dispatch success + "console
  unchanged"、live Pages 配信確認。**本セッションが3件とも `curl` で
  再検証** — sha256 が全て agent 報告値と完全一致
  (`767b1658...`/`63d5ec31...`/`0ae2d67c...`)。
- commit `d4cc4e7`→merge `9c2bda2fe3ff` (isco-2113)、`c82accf`→merge
  `ca9ca834f535` (isco-1213)、`8f642b0`→merge `b8f73c3d3246` (isco-1112)。
  feature branch は全て merge 後に削除。
- 続けて本セッションが3件分の west.yml pin を**1コミットにまとめて**前進
  (各 diff 1行のみ、ff clean 個別検証済み、superproject commit `0dd51139e7dc`)。

**Finding:** ISCO 側で batch (3件同時) が単発実証と同じ品質規律
  (screening・実データ・正直な開示・4段階検証) を保ったまま機能することを
  確認。実バグ発見時に無理に batch 数を埋めず対象外にする判断も機能した
  (isic 側の isic-2100/isic-1101 screen-out と同型の規律)。ISCO 側の
  no-demo backlog: 126 → 121 (このセッション累計5件: isco-1211/1111/2113/
  1213/1112)。

**Did NOT (honest):** `cloud-itonami-flagship-checklist-scan.edn`/
  `-rollout-ledger.edn` への追記はしていない (Wave5 自身の管轄と判断)。
  isco-2111 の実バグ (advisor が `:finalized?` 未伝播) は発見のみで未修正
  (このバッチのスコープ外、将来 isco-2111 に取り組む際の前提条件として
  残す)。portfolio score・ISIC 側スコアとも据え置き。

**Next:** 次回 cron 発火時、まず Wave5 の最新状態と本ログを再確認。ISCO
  batch をさらに大きくする (Wave5 の6件相当に近づける) か、isco-2111 の
  実バグ修正に取り組むか、別の切り口 (cockpit 統合設計など) に移るかを、
  その時点の状況で判断すること。

---

## Iteration 12 — 2026-07-19 (ISCO batch を6件に拡大 + isco-2111 バグ修正を並行着手)

**Target:** iter11 の3件 batch が正常に機能したため、Wave5 の cadence (6件)
  に合わせて拡大。同時に、cron 発火の待機時間を無駄にしないため、iter11 で
  見つけた isco-2111 の実バグ修正を**別リポジトリ対象の非衝突タスク**として
  並行起動。

**Did (fresh agent#1, isolation:worktree, background, ~29分 — ISCO 6件batch):**
- 40候補を2並列 read-only agent で screening (test suite 実行)。stub 2件を
  新規発見: **isco-1322**(mining_managers)、**isco-2146**(mining_engineers、
  コード中に "Simplified stub" と明記された偽実装、`langgraph.graph` 未
  require)。残り38件は実 StateGraph、全 green。
- 38件中もっとも豊かな fixture を持つ6件を選定:
  **isco-2112**(meteorology)/**isco-2131**(biosciences)/**isco-1346**
  (branch_manager)/**isco-2133**(envpro)/**isco-2423**(careers)/**isco-2424**
  (training)。全て既存 test fixture から実 seed データを流用 + 追加分は
  実 API 経由と docstring に開示 (iter9-11 と同水準)。到達不能な governor
  rule も正直に開示 (isco-2133 の context 未読み込み、isco-2424 の
  `:hours-mismatch` 到達不能、isco-2423 の fixture 間 skill-set 不一致)。
- 6件それぞれ render-html + regenerate.yml + operator-console.html を実装、
  4段階検証を独立実施。**本セッションが6件とも `curl` で再検証** — 全て
  HTTP 200、sha256 prefix が agent 報告値と完全一致
  (`59f06097`/`97ea3184`/`4209f000`/`28f8ce57`/`1a59158f`/`7d2dc4ae`)。
- commit→merge: isco-2112(`088118c1`→`09d98977`)、isco-2131(`13708832`→
  `35cdb2a6`)、isco-1346(`50b01d14`→`0cfd5ab1`)、isco-2133(`e0c5da0f`→
  `c6591a3a`)、isco-2423(`048ae0f4`→`96e49e5d`)、isco-2424(`a159a5fb`→
  `55478bc3`)。feature branch は全て merge 後に削除確認済み。
- 続けて本セッションが6件分の west.yml pin を1コミットにまとめて前進
  (各 diff 1行、ff clean 個別検証済み、superproject commit `ebaba8f0e2f2`)。
- **正直な未解決事項 (agent報告)**: `isolation:"worktree"` の入れ子 agent
  worktree 2件 (`.claude/worktrees/agent-a6f97cac6a30acc2d/.claude/
  worktrees/{agent-a693cec6b1d088112,agent-a6aff8a9b7b45e8a8}`) が
  非標準の `.git` シンボリックリンク構造のため `git worktree remove` に
  失敗、無理な force削除はせず正直に報告 (harness 管理領域の cleanup は
  本セッションでも同じ症状に度々遭遇 — 無理に触らず harness に委ねる方針
  を継続)。

**Did (fresh agent#2, isolation:worktree, background — 並行、isco-2111 のみ対象、
  上記batchと非衝突 — 完了はまだ通知されておらず、次 iteration で処理予定):**
- isco-2111 の実バグ (advisor が `:finalized?` 未伝播) の根本原因確認・
  修正・再検証を委託。対象は isco-2111 単体のみで、上記6件 batch とも
  超過候補4件 (isco-1113/2114/2412/1114) とも repo が重複しないため
  衝突なしと判断して同時実行。

**Finding:** ISCO 側 batch は6件規模でも同じ品質規律 (screening・実データ・
  正直な開示・4段階検証) を保って機能。stub 検出も2件目3件目のパターンを
  発見 (isic 側と同様、"ファイル名は揃っているが実装が偽" のケースが
  一定確率で混在する)。ISCO 側 no-demo backlog: 121 → 115
  (このセッション累計11件: isco-1211/1111/2113/1213/1112/2112/2131/1346/
  2133/2423/2424)。

**Did NOT (honest):** `cloud-itonami-flagship-checklist-scan.edn`/
  `-rollout-ledger.edn` への追記はしていない。isco-2111 のバグ修正結果は
  並行 agent がまだ完了通知を返しておらず、本 iteration ではまだ処理して
  いない (次 iteration で検証・pin前進・ログ化を行う)。portfolio score・
  ISIC 側スコアとも据え置き。

**Next:** 次回 cron 発火時 (または isco-2111 修正 agent の完了通知が先に
  届いた場合): (1) isco-2111 修正結果を検証・着地処理、(2) ISCO 側 batch
  をさらに継続するか、cockpit 統合設計等の別の切り口に移るかを判断。

---

## Iteration 13 — 2026-07-19 (isco-2111 バグ修正 着地処理)

**Target:** iter12 で並行起動した isco-2111 バグ修正 agent の完了通知を処理。

**Did:**
- agent が根本原因を精査: 実際は **2つの複合欠陥**だった。(1)
  `physics.advisor/infer`（mock-advisor の決定的推論fn）が `:op`/`:stake`
  以外のリクエストキーを全て握りつぶしており、`governor/check` が見る
  `:finalized?`/`:novel?` フラグが伝播していなかった（報告通りの根本原因）。
  (2) 失敗していたテスト自身にも配線バグがあり、`:finalized? true` が
  実際には未使用のローカル変数 `proposal` にしか存在せず、実際の
  `request` map には渡っていなかった（advisor を直しただけではこの
  test は救えなかった、test 側も要修正だった）。
- 修正はテストの削除/緩和ではなく根本修正: `advisor.cljc` の `infer` に
  `cond->` で `:finalized?`/`:novel?` をリクエストから proposal へ伝播する
  処理を追加、テスト自身の配線バグも修正、さらに `:novel?` の伝播を
  証明する e2e テストを新規追加。
- test 結果: 修正前 14 tests/48 assertions・3 failures → 修正後
  **15 tests/53 assertions・0 failures**（本人の worktree + 独立の
  clean clone 両方で確認済み）。
- ついでに (低い追加コストと判断、バグ修正で全ソース読了済みのため)
  build-time demo generator も同時実装: 既存 fixture (`proj-1`/`ds-1`/
  `inst-1`) を流用 + `proj-2` を実 API 経由で追加 (docstring 開示)。
  修正により初めて `:no-finalized-claims` hard rule も実 advisor 経由で
  到達可能になったことを確認・デモに反映。
- commit `7e5aff9bbefe` (fix+demo) → server-side merge `8d96aea8e53b`
  → isco-2111 main に landed。
- 検証: (a) idempotent (sha256 `19c717fe...`)、(b) clean-checkout byte一致、
  (c) live workflow_dispatch run `29678940878` success + unchanged、
  (d) live Pages — **本セッションが `curl` で再検証**、HTTP 200、sha256
  `19c717fe59f3bc80544357ec945ca06f919818419f6f9acb54330001829dfd51` が
  agent 報告値と完全一致。
- west.yml pin 前進 (`9d6088ab`→`8d96aea8e53b44fb354b037752b7386f9a85ffcd`、
  ff clean 検証済み、superproject commit `cf29c573ea3f`)。

**Finding:** 実バグを screening で発見 → 対象外化せず正面から修正 → 修正の
  副産物として demo generator も追加、という流れが機能した。ISCO 側
  no-demo backlog: 115 → 114 (isco-2111 追加、累計12件)。「stub/バグを
  見つけたら黙って回避せず、可能なら根本修正する」という規律の実例が
  ISCO 側にも1件でき、isic 側の同種の規律 (screen-out はするが、
  直せるバグは直す) と揃った。

**Did NOT (honest):** `cloud-itonami-flagship-checklist-scan.edn`/
  `-rollout-ledger.edn` への追記はしていない。isco-2111 の
  `physics.governor/check` が未使用の `context` param を持つ点は
  agent が発見したが未修正 (スコープ外、次の課題として残す)。portfolio
  score・ISIC 側スコアとも据え置き。

**Next:** 次回 cron 発火時、Wave5 の最新状態と本ログを再確認した上で、
  ISCO batch を継続するか (次候補プールから選定)、cockpit-isic-5820
  統合の設計スコーピング (iter8 で保留した課題) に切り替えるかを判断。

---

## Iteration 14 — 2026-07-19 (ISCO batch を8件に拡大)

**Target:** iter12/13 の6件batch + バグ修正1件が順調だったため、batch を
  さらに8件に拡大して継続。

**Did (fresh agent, isolation:worktree, background, ~30分 — ISCO 8件batch):**
- 起動前に Wave5 rollout ledger を再確認 (`isco-1211`/`isco-1212` のみ) —
  衝突なし確認。
- 50候補を3並列 read-only agent で screening (test suite 実行)。
  **今回は stub もバグも0件** — 全50件が実装済み・test green の real actor
  だった。選定は governor-rule/fixture の豊かさと domain 多様性のみで判断
  (1311/1312/2132 の類似トリオ、3112/3113/3114 の薄いトリオを意図的に回避)。
- 選定8件: **isco-1323**(construction)/**isco-1222**(advertpr)/
  **isco-2153**(telecomeng)/**isco-2412**(finadvisory)/**isco-2514**
  (appsdev)/**isco-3111**(chemistry)/**isco-2522**(sysadmin)/**isco-2141**
  (indprod)。全て既存 test fixture から実 seed データ流用 + 追加分は実 API
  経由と docstring に開示 (iter9-13 と同水準)。全件 test green 前後維持
  (14-15 tests / 27-47 assertions、regression なし)。
- 8件それぞれ render-html + regenerate.yml + operator-console.html を実装、
  4段階検証を独立実施。**本セッションが8件とも `curl` で再検証** — 全て
  HTTP 200、sha256 prefix が agent 報告値と完全一致。
- commit→merge: isco-1323(`f2b94015`)、isco-1222(`30cb1fd2`)、
  isco-2153(`56ee05d6`)、isco-2412(`122f0308`)、isco-2514(`c6b66cba`)、
  isco-3111(`9f577c13`)、isco-2522(`71d7e304`)、isco-2141(`868c54db`)。
  feature branch は全て merge 後に削除確認済み。
- 続けて本セッションが8件分の west.yml pin を1コミットにまとめて前進
  (各 diff 1行、ff clean 個別検証済み、superproject commit `dc3612e8dee9`)。
- agent が正直に報告: 自身の isolation worktree 内に無関係な未コミット差分
  (`cloud-itonami-iso3166-usa-*` の pin 2件) を発見したが、自分/sub-agent
  が触っていないことを確認し、スコープ外として不介入 (触らない判断は
  正しい — このセッションの並行churn観測パターンと整合)。

**Finding:** ISCO 側 batch が8件規模でも同じ品質規律を保って機能 (今回は
  stub/バグ0件で選定が純粋に多様性判断になった点が新しい)。ISCO 側
  no-demo backlog: 114 → 106 (このセッション累計20件)。

**Did NOT (honest):** `cloud-itonami-flagship-checklist-scan.edn`/
  `-rollout-ledger.edn` への追記はしていない。portfolio score・ISIC 側
  スコアとも据え置き。

**Next:** 次回 cron 発火時、Wave5 の最新状態と本ログを再確認した上で、
  ISCO batch をさらに継続する (残り106件) か、cockpit-isic-5820 統合の
  設計スコーピングに切り替えるかを判断。

---

## Iteration 15 — 2026-07-19 (ISCO batch を10件に拡大 + domain多様性を意識した選定 + バグ2件修正)

**Target:** iter14 まで一貫して batch 規模を拡大 (3→6→8) してきたのを継続。
  今回は選定時に ISCO の major-group prefix (1xxx 管理職〜9xxx 単純作業まで)
  を意図的に分散させ、同系統への偏りを避けた。

**Did (fresh agent, isolation:worktree, background, ~32分 — ISCO 10件batch):**
- 起動前に Wave5 ledger 再確認 (`isco-1211`/`isco-1212` のみ) — 衝突なし。
- 17候補を screening。**新規 stub 発見: isco-3121**(mining_supervisors、
  isco-2146 と同型の "Simplified stub" コメント)。**新規 shape 不一致発見:
  isco-3134**(refinery、旧5ファイル構成 `advisor/governor/phase/sim/store`
  で `actor.cljc` が無く `langgraph.graph` 参照ゼロ) — 4ファイル template
  に無理に当てはめず対象外化。
- major-group 0/1/2/3(×2)/4/5/8/9(×2) にまたがる10件を選定: **isco-0110**
  (officer_admin)/**isco-1345**(eduman)/**isco-2434**(ictsales)/
  **isco-3151**(marine)/**isco-3331**(customsclearing)/**isco-4214**
  (debtcollection)/**isco-5419**(protective_services)/**isco-8343**
  (craneoperations、バグ修正込み)/**isco-9321**(packingfulfillment)/
  **isco-9329**(manufacturing_labour、バグ修正込み)。
- **実バグ2件を根本修正 (isic-2111/iter13 と同型の規律)**: isco-8343 と
  isco-9329 で `commit-node`/`approve!` が `store` を受け取りながら
  `store/add-record!` を一度も呼んでおらず、docstring が謳う append-only
  audit ledger が実際には一切記録されていなかった。isco-9321/isco-5419
  の正しい実装パターンを参考に修正 + `store/records` を直接検証する
  regression test を追加。**agent 自身が修正後の2リポを再度独立 clone
  して test suite を再実行し確認済み** (35/100, 33/82、共に0 failures)。
- 全10件それぞれ render-html + regenerate.yml + operator-console.html を
  実装、4段階検証を独立実施。**本セッションが10件とも `curl` で再検証** —
  全て HTTP 200、sha256 prefix が agent 報告値と完全一致。
- commit→merge (最終SHA): isco-0110(`1598035e`)、isco-1345(`bae88f89`)、
  isco-2434(`2c6239bf`)、isco-3151(`198249a2`)、isco-3331(`86926752`)、
  isco-4214(`a79c0ae2`)、isco-5419(`27eb1770`)、isco-8343(`02ef416c`)、
  isco-9321(`a043f5be`)、isco-9329(`f8b91af3`)。feature branch は全て
  merge 後に削除確認済み。
- 続けて本セッションが10件分の west.yml pin を1コミットにまとめて前進
  (各 diff 1行、ff clean 個別検証済み — isco-8343/9329 のみ ahead_by 3
  (バグ修正+demo の2commit分)、他は ahead_by 2。superproject commit
  `7a64c69cd38`)。

**Finding:** ISCO 側 batch が10件規模でも同じ品質規律 (stub/shape不一致の
  screen-out、実バグの根本修正、4段階検証) を保って機能。domain 多様性を
  意図的に broaden した選定も機能した。ISCO 側 no-demo backlog: 106 → 96
  (このセッション累計30件)。実バグ根本修正の累計は isco-2111(iter13)含め
  3件に到達。

**Did NOT (honest):** `cloud-itonami-flagship-checklist-scan.edn`/
  `-rollout-ledger.edn` への追記はしていない。portfolio score・ISIC 側
  スコアとも据え置き。

**Next:** 次回 cron 発火時、Wave5 の最新状態と本ログを再確認した上で、
  ISCO batch をさらに継続する (残り96件) か、cockpit-isic-5820 統合の
  設計スコーピングに切り替えるかを判断。

---

## Iteration 16 — 2026-07-19 (ISCO batch継続、10件狙いが内部分割で5件着地)

**Target:** iter15 の10件batchを踏襲し規模を維持 (これ以上の拡大はせず)。

**運用ノート (agent内部分割):** 今回の agent は自身を "Group A"/"Group B" に
  内部分割して並行screeningした模様。Group B (65候補中10件を実test suite
  screening、5件を選定・着地) からは完全な最終レポートが届いたが、
  Group A 側の結果は本セッションには届かなかった (agent自身が
  "Could not reach that peer directly via SendMessage" と報告 — 内部
  orchestrationの不具合と判断)。**正直な判断**: 届いた確実な証拠 (Group B
  の5件) のみを本iterationの成果として処理し、Group A 分は「不明」として
  扱う (捏造しない — 存在するかもしれない追加成果を憶測で報告しない)。

**Did (Group B の5件、全て独立検証済み):**
- 65候補を grep で stub/langgraph 参照チェック (全件 stub なし)、うち10件
  (major-group 4分野にまたがる) で実 test suite 実行 — 全10件 green。
- 選定5件 (domain多様性重視): **isco-2413**(finanalyst, 2xxx)/**isco-2611**
  (legalpractice, 2xxx)/**isco-3511**(ictops, 3xxx)/**isco-4311**
  (bookkeeping, 4xxx、7/9 governor条件到達で今batch最豊富)/**isco-9313**
  (construction labour, 9xxx)。全て既存 test fixture から実 seed データ
  流用 + 追加分は実 API 経由と docstring 開示 (従来と同水準)。バグは
  今回0件 (5件とも commit-node が正しく store 記録済みを事前確認)。
- 4段階検証を独立実施、**本セッションが5件とも `curl` で再検証** — 全て
  HTTP 200、sha256 が agent 報告値と完全一致。isco-9313 は Pages ビルドが
  一時不安定 (agent が再トリガして解決、最終的に一致) だったことが正直に
  報告された。
- commit→merge: isco-2413(`5bab5b4d`)、isco-2611(`7ae56eab`)、
  isco-3511(`58bd2c9b`)、isco-4311(`84c293b0`)、isco-9313(`06cec974`)。
  feature branch は全て merge 後に削除確認済み。
- west.yml pin 前進: 初回 PUT が 409 (別セッションとの race — blob sha
  が読み取り後に他プロセスにより変わっていた) で失敗、fresh に west.yml
  を再取得 → 自分の対象5件が無事なことを確認 → 再試行で成功
  (superproject commit `ebdbc645b5a9`)。

**保留 (未実装の予備候補、Group B が明記)**: isco-2521(dbadmin)/
  isco-3112(civeng、domainは薄いと判明)/isco-3311(brokerage)/
  isco-4416(personnelclerk)/isco-9334(merchandising) — test green 確認済みだが
  未実装。65候補中55件は今回一切未 screening (stub/バグ有無とも不明)。

**Finding:** ISCO 側 no-demo backlog: 96 → 91 (このセッション累計35件)。
  agent の自己並列化 (Group A/B分割) は今回一部通信不調があったため、
  次回 iteration では単一 agent 構成に戻すか、分割時は各グループの
  レポートを個別に追跡する前提で臨むこと。

**Did NOT (honest):** Group A の作業内容は不明 (届いていない、存在した
  かどうかも確定できない)。`cloud-itonami-flagship-checklist-scan.edn`/
  `-rollout-ledger.edn` への追記はしていない。portfolio score・ISIC 側
  スコアとも据え置き。

**Next:** 次回 cron 発火時、Wave5 の最新状態と本ログを再確認。ISCO batch
  を単一agent構成で継続する (残り91件、うち5件は screen済み予備あり) か、
  cockpit-isic-5820 統合の設計スコーピングに切り替えるかを判断。

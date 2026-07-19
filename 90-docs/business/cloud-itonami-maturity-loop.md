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
  進めること。

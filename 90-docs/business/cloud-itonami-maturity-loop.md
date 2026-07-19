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

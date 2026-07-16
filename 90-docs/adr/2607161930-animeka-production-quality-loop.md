# ADR-2607161930: animeka — 作品作成・品質更新ループ（production-loop）の設計実装

## Status

Accepted, implemented & merged（2026-07-16、オーナー指示「作品の作成、品質更新 loop を
設計実装」）。実装は `gftdcojp/ai-gftd-animeka` main `f2ebc840`
（`scripts/production-loop.cljs` + viewer 拡張）。初回実運転で 鋼刃学園 ep1 が
カット 0 → 6/6 レンダリング済み公開に到達（https://animeka.gftd.ai/ep-ep-hagane-01）。

## Context

BMC lean-loop（ADR-2607161800）の Iteration 1〜5 で判明した事実: animeka の成熟度の
律速は「作品データはあるが工程が進まない」（8 作品中 7 が episode タイトルのみ、
フラッグシップも原画 1 工程まで）と「品質判定が graph 層に常設されたが production
データに対して回っていない」の 2 点。手動 iteration で縦切り（render→R2→D1→viewer）
は実証済み — これを**繰り返し可能な 1 コマンド**に落とすのが本 ADR。

## Decision

`ai-gftd-animeka/scripts/production-loop.cljs`（**nbb** operator script — 新規
スクリプトは sh/mjs 禁止・nbb の repo 規則に従う）。**1 run = 1 有界増分**、5 phase:

1. **discover** — D1 実クエリで未着手を発見: cuts==0 の episode（breakdown 対象、
   oldest-first、`--episode` で指定可）/ `image_url IS NULL` の cut（render 対象、
   `--max-renders` 既定 3）。
2. **create** — 対象 episode の work `props.arc`（起承転結、ADR-2607161800 で永続化済み）
   と ADR-2605222000 の cinematic vocabulary から**決定論的に** 6 カット割を生成し
   INSERT（LLM 不要 = 捏造リスクなし。beat-plan: 起=ELS establishing + MS intro、
   承=MLS development、転=CU turning + TWO confrontation、結=LS resolution）。
3. **render + QA** — active `renderRecipe`（co-scientist ループの勝者、
   ADR-2607161730）で条件付けて fleet bridge（comfy-openai-bridge → animagine-xl-4.0）
   で render → sha256/CIDv1 → R2 `blobs/anonymous/{sha256}`（**round-trip サイズ検証
   必須**）→ D1 更新。vision judge は `OLLAMA_HOST` 可用時のみ 0-100 採点、
   不可用時は **`:judge :deferred` と正直に記録**（後続 run で採点可能な形で残す）。
4. **publish** — D1 → `viewer/data.json` export → kotoba-ui SSR（generate.cljs、
   データ駆動化済み — cuts を持つ全 episode のページを emit）→ resource-guard
   `deploy` scope 経由で `wrangler deploy`。`--no-deploy` でスキップ可。
5. **ledger** — `docs/production-loop-ledger.edn` に 1 行 EDN append（append-only、
   手編集禁止）。BMC lean-loop log とは役割分担: ledger = 機械可読 run 記録、
   log = iteration の判断と学び。

併せて viewer に **validation 計測面**: first-party hit counter（D1 `viewer_hits`、
`/blob` と `/api/stats` 自身は除外）+ `GET /api/stats`。

## Consequences

- (+) 「成熟度を向上」の各 iteration が `nbb scripts/production-loop.cljs` 1 コマンドに
  なり、10 分周期ループが安定運転に乗る。初回実運転: 鋼刃学園 ep1 breakdown 6 cuts →
  6/6 render+publish（judge 全件 deferred — LLM head down のため）。
- (+) 品質更新は graph 層ハーネス（ADR-2607161730）と同じ資源（renderRecipe / vision
  rubric）を使い、loop 側は operator、graph 側は runtime と役割が分離。
- (−) judge が deferred のままだと品質ゲートは実質素通し — LLM head 復帰後に
  deferred cuts の一括採点 + retake を回すのが前提（follow-up）。
- (−) breakdown は決定論テンプレート（6 beat 固定）— 脚本 AI による可変カット割は
  スコープ外（graph 層 `generateScript`/`breakdownScene` の本配線が正規経路）。
- (−) 初回運転でバグ 2 件検出・同日修正: publish の deploy cwd 誤り（1 run 目の
  ledger 欠落、renders 自体は D1 反映済み）、ep ページ命名変更に伴う旧ページ残留。

## Alternatives Considered

1. **graph 層（coscientistQuality/autopilot）を production D1 に直結して回す** —
   本命だが store の kotoba backend 配線と runtime のデプロイが必要で、10 分
   iteration の増分に収まらない。operator loop を先に立て、graph 直結は後続。
2. **cloud routine（RemoteTrigger）化** — セッション非依存になるが、オーナーの
   明示なしに常駐 routine を増やさない方針（ADR-2607161800）を維持。
3. **Workflow（multi-agent）化** — 現状の律速は並列度でなく fleet render の
   スループット。単線 loop で十分。

## References

- 実装: `orgs/gftdcojp/ai-gftd-animeka` main `f2ebc840` — `scripts/production-loop.cljs`
  / `viewer/generate.cljs`（データ駆動）/ `viewer/worker.js`（hit counter + /api/stats）
  / `docs/production-loop-ledger.edn` / `docs/bmc-lean-loop-log.md` Iteration 6
- 関連 ADR: 2607161800（standalone BMC lean-loop）、2607161730（co-scientist ReAct
  ハーネス）、2605222000（cinematic vocabulary）、2607122200（kotoba-ui paved road）
- live: https://animeka.gftd.ai/ep-ep-hagane-01（6 cuts）、/api/stats（validation 計測）

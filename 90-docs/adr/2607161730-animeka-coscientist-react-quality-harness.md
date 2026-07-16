# ADR-2607161730: animeka — co-scientist ReAct 品質ハーネスの langgraph 昇格（coscientistQuality 実ループ + autopilot 品質ゲート）

## Status

Accepted, implemented & merged（2026-07-16）。実装は `gftdcojp/ai-gftd-animeka` main
`491a7895f7cfe45f0f223274631a30d978c50866`（feature commit `07c3f8d`、7 tests / 60
assertions / 0 failures）。west pin は `6b18398` → `491a789` へ前進済み
（com-junkawasaki/root main `3f8e6bd75ed`。途中に west.yml 空ファイル事故と復旧あり —
Consequences 参照）。

## Context

オーナーからの問い「今の animeka での生成 pipeline として高品質に anime を生成する
react agent loop のハーネスは設計されている?」に対する調査（2026-07-16、本セッション）で、
状態は **PARTIAL** と判明した:

- **動く co-scientist ループは既に存在した** — `ai-gftd-animeka/lg/scripts/
  coscientist-murakumo.cljc`（babashka スクリプト）。Google AI Co-Scientist を写した
  generate → render(fleet ComfyUI, animagine-xl-4.0) → judge(gemma4 vision, 6軸
  RUBRIC 0-100) → tournament(top-2 pairwise debate + Elo swap) → evolve →
  meta-review のループで、勝者 recipe を animeka D1（`ai.gftd.apps.animeka.renderRecipe`,
  status=active）に永続化し、Python 時代の `autopilot._latest_render_recipe()` が
  それを読んで全 keyframe render を条件付けする「複利」設計。
- **しかし canonical な CLJC graph 層には届いていなかった**: `clj/src/animeka/graphs/
  generation.cljc` の `make-coscientist-quality`（NSID
  `ai.gftd.apps.animeka.coscientistQuality`）は `:score 0.82`/`0.5` 固定・`:issues []`
  のハードコードスタブ、`make-autopilot` は create→episode→scene→cut→cut_runner の
  線形パスで judge→retry の反復なし。CLJC ポート（ADR-2607011900）で Python runtime を
  prune した際、recipe 読み出し配線も落ちていた。
- **この設計を定めた ADR が無かった**。汎用の react loop ハーネス（persona visual
  user-test react loop ADR-2607141550、design-quality 3-judge panel ADR-2607132300、
  原典 ADR-2606141500 keiei-arbor co-scientist engine）は itad / business 側に適用済みで
  animeka には未配線。

Goal 指示「adr にして gap を埋めて」を受け、本 ADR がその設計の正本となり、実装で
ギャップを埋めた。

## Decision

**standalone スクリプトの co-scientist ループを canonical CLJC graph 層に昇格し、
autopilot に有界の ReAct 品質ゲートを組み込む。** 実装はすべて
`ai-gftd-animeka/clj/`（portable `.cljc`、runtime 優先順位ルールに整合 — JVM は互換層、
graph 契約が正本）。

### 1. `coscientistQuality` — 実ループ（`generation.cljc`）

`make-coscientist-quality` を generate → render → judge → evolve(meta-review) →
persist の実ループに置換:

- **generate**: `animeka.llm/complete-json`（`recipe-sys`）で recipe 候補
  `{:recipes [{style_tags, extra_prompt, cfg, steps, sampler}]}` を `:candidates` 件
  提案（`parse-structured` が map しか返さないため raw JSON array ではなく
  `{"recipes": [...]}` 契約にした）。2世代目以降は meta-review rules + top recipes を
  渡して EVOLVE（mutate winners, fix weaknesses）。
- **render**: 各 recipe を `animeka.comfy/render-keyframe`（既存 facade →
  `comfyui.gateway`）で実レンダリング。`blobs` があれば content-addressed store に
  offload（`generate-keyframe` と同じ `offload` 経路）。
- **judge**: `animeka.llm/vision-json`（新設 — `langchain.jvm/vision-json` の
  passthrough）で 6軸 RUBRIC（line_quality / color_lighting / cel_shading /
  character_anatomy / composition / mood_match、overall 0-100 → 0-1 正規化）。
- **meta-review**: HIGH vs LOW の recipe 差分から concrete rules を抽出し次世代へ。
- **persist**: 勝者を store の `renderRecipe` entity として `activate!`（既存 active を
  supersede、常に active は 1 件 — スクリプト版の D1 UPDATE→INSERT と同型を
  store 抽象（mem / kotoba 両 backend）の上で行う）。`:persist false` で judge-only。
- envelope はスタブの `:score`(0-1)/`:issues`/`:recommendations` を後方互換で維持しつつ
  `:judge`/`:best_recipe`/`:recipe_id`/`:recipe_uri`/`:trajectory` を追加。

### 2. `autopilot` — 有界 ReAct 品質ゲート（同ファイル）

線形 scaffold パスの後に: coscientistQuality で judge → `:quality-threshold`
（default 0.75）未満かつ `:max-retakes`（default 1、上限 3）残があれば、ループの
recommendations を retake ヒントとして keyframe を再生成（このとき新 active recipe が
プロンプトに乗る）→ 再 judge。`:quality`/`:quality_attempts`/`:quality_trajectory`/
`:quality_passed` を返す。

### 3. `generate-keyframe` の recipe 条件付け

`styled-prompt` で active `renderRecipe` の style-tags / extra-prompt をプロンプトに
連結 — Python `autopilot._latest_render_recipe()` 読みの CLJC ポート。これで
coscientistQuality の成果が以後の全 keyframe render に複利で効く（スクリプト版と同じ
閉ループが graph 層でも閉じた）。

### 4. offline degradation 契約（テスト可能性）

外部呼び出しはすべて既存の劣化規約に従う: LLM 提案/judge は offline で mock echo →
parse nil → **決定論 fallback**（recipe は固定 style-tags 3種の巡回、judge は
「stage 進捗あり 0.82 / なし 0.5 + recipe-hash jitter < 0.07（上限 0.95）」の
heuristic — 旧スタブのスコア意味論を保存）。ComfyUI は既存 placeholder。これにより
suite は GPU/LLM なしで決定論的に通る（threshold 0.99 を渡すと必ず max-retakes まで
回る、を利用して反復経路もテスト）。

### 5. standalone スクリプトの位置づけ

`lg/scripts/coscientist-murakumo.cljc` は**廃止しない** — tailnet 上で fleet
ComfyUI + Ollama native vision + D1 直書きで回す operator ツールとして残す。canonical
なのは graph 層のループ（appview runtime / NSID 経由）。両者の persist 先の相違
（D1 `vertex_animeka` vs store 抽象）の統一は follow-up（store の kotoba backend が
本番 D1 相当に書くため、実運用では同じ場所に収束する）。

## Consequences

- (+) 調査で PARTIAL だった 3 ギャップ（graph node スタブ / autopilot 線形 / ADR 不在）が
  すべて閉じた。`coscientistQuality` は NSID 経由で reactive pipeline（derive rule）からも
  呼べる実ループになった。
- (+) 品質ループの成果（active renderRecipe）が autopilot / generateKeyframe に自動で
  乗る複利構造が、スクリプト実行に依存せず runtime 本体に常設された。
- (−) **vision judge の live 経路は未検証**: `langchain.jvm/vision-json` の
  multimodal content shape が murakumo OpenAI-compatible endpoint（現行 serving は
  `qwen-agentworld-35b-a3b`、vision 非対応の可能性）で通るかは実測していない。通らない
  場合も heuristic に劣化するだけで壊れない設計だが、「LLM vision で採点されている」と
  みなす前に fleet で 1 回実測すること（follow-up）。
- (−) judge は単一 LLM 1 パス — ADR-2607132300 が実測した「LLM panel の合意が具体的
  ギャップを見逃す」問題への手当（3-judge panel / 決定論 fitness の追加）は本 ADR の
  スコープ外の follow-up。スクリプト版にあった top-2 pairwise tournament も graph 版では
  初版から落としてある（必要になったら移植）。
- (−) **west pin 前進中の事故（記録）**: single-entry API 編集で `base64 -o`（BSD 構文）が
  GNU base64 に拒否され空の content を PUT、com-junkawasaki/root main の
  `manifest/west.yml` が 1 commit（`831639bba16`）の間 **空ファイル**になった。直後の
  `3f8e6bd75ed` で「事故直前 main + animeka 1 行前進」の全文を復旧（pre-incident との
  正味 diff は +1/−1 の当該 entry のみ、窓中の割り込み commit なしを API で確認済み）。
  教訓: content PUT の前に **payload が非空か・base64 が成功したかを検証する**
  （`base64 -w0 < file` を使う。west-pin-verify hook は pin 値の検証であり空ファイルは
  弾かない）。
- 既存テストは無変更で通過（autopilot の従来アサーション含む）。追加テストで
  ループ envelope・active recipe の supersede・keyframe 条件付け・judge-only・
  反復経路を固定。

## Alternatives Considered

1. **design-quality（`90-docs/design-quality/coscientist.cljc` + 3-judge panel）を
   そのまま animeka に配線** — 対象が UI ライブラリ採点で、render→vision 採点→recipe
   進化という animeka の形と入出力が合わない。パターン（Generate→Reflect→Rank→
   Evolve→Meta）だけ共有し、実装は animeka の既存 facade（comfy/llm/store）上に置いた。
2. **standalone スクリプトのまま運用し graph はスタブ維持** — 現状維持案。ループが
   tailnet 手動実行に依存し、appview/derive-rule から品質ゲートを呼べないままになる
   ため棄却。
3. **LangGraph の multi-node graph（generate/render/judge/evolve を別 node）で表現** —
   animeka の graph 層は全 NSID が `util/single-node` 契約で統一されており、初版から
   逸脱する利益が薄い。ループ本体は純関数群に分割してあるので、後で multi-node 化する
   移行コストは低い。

## References

- 実装: `orgs/gftdcojp/ai-gftd-animeka` main `491a7895` —
  `clj/src/animeka/graphs/generation.cljc`（coscientist ループ + autopilot ゲート +
  styled-prompt）/ `clj/src/animeka/store.cljc`（renderRecipe）/
  `clj/src/animeka/llm.cljc`（vision-json）/ `clj/src/animeka/domain.cljc`（schema）/
  `clj/test/animeka/core_test.cljc`
- 原型: `orgs/gftdcojp/ai-gftd-animeka/lg/scripts/coscientist-murakumo.cljc`
- west pin 前進: com-junkawasaki/root main `831639bba16`（事故）→ `3f8e6bd75ed`（復旧+前進）
- 関連 ADR: 2607011900（mangaka/animeka commons・CLJC port）、2606141500
  （keiei-arbor co-scientist engine、パターン原典）、2607132300（design-quality
  3-judge panel と LLM-judge の限界の実測）、2607141550（persona visual user-test
  react loop）、2605222000（animeka v3 USD+ComfyUI cinematic pipeline）
- インフラ実測: `ai-gftd-animeka/CLAUDE.md`（murakumo fleet LLM `100.82.98.110:8090` /
  ComfyUI bridge `:8189` 確認済み 2026-07-10）

## Addendum 1 — live vision judge 検証完了（2026-07-16）

Consequences に残していた「vision judge の live 経路は未検証」を解消。fleet の
Ollama（`benjamin` ノード、brew 0.30.6 の llama-server 欠落を 0.32.0 upgrade で修理
— 経緯は ai-gftd-animeka `docs/bmc-lean-loop-log.md` Iteration 8）復帰後、canonical
graph node を実資源で通した: `GFTD_LLM_URL=<ollama>/v1`（gemma4:e4b-it-qat）+
fleet bridge、mem store、1 generation × 1 candidate、133s。結果 `:judge "vision"` /
score 0.87 — `langchain.jvm/vision-json` の multimodal content が Ollama の OpenAI
互換面で実際に通ることを確認（heuristic 劣化ではない）。meta-review も実質的な
recipe rules を返し、勝者が renderRecipe（backend "llm-vision"）として activate
された。残 follow-up は 3-judge panel / pairwise tournament 移植のみ。
運用面では ADR-2607161930 の production-loop が同じ judge を全 45 公開カットに適用済み
（avg 87.7 / min 82 / max 95、閾値 60 未満ゼロ）。

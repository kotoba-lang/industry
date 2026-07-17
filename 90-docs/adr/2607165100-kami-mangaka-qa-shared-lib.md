
## Addendum 5 (2026-07-17) — 同定戦略の co-scientist（キャラ grounding 改善）

v11 p02 が同点だった原因（復元キャラが unknown に落ち facePresence 軸が
効かない）を **co-scientist アプローチ**で解いた。同定戦略の仮説群を
Generate → p02（手読み GT と照合）/ p03（汎化）で fitness 実測 Reflect →
Elo Rank → Evolve → Meta。

**仮説と実測（fleet gemma4、2回 run）**:

| 戦略 | p02 精度 | grounding | Elo |
|---|---|---|---|
| cast 名のみ（baseline） | 0.0–0.2 | **0→5** | 1169 |
| **外見記述を prompt に添付（勝者）** | **0.2–0.42** | **4–5** | **1246** |
| 参照顔画像 few-shot | 0.1–0.25 | 4 | 1215 |
| 記述+画像 hybrid（Evolve） | 0.2 | 5 | 1169 |

**Meta-review の結論**:
1. **cast 名のみは grounding ほぼ 0** — v11 p02 が unknown だらけになった
   原因を実証。
2. **外見記述の添付が全 run で勝者**（`page-script-prompt` 3-arity、
   `recover_storyboard` が store の `:character/prompt` を流す）。
3. **参照画像 few-shot は期待外れ**、hybrid は合成で改善せず — 量子化
   gemma4 は複数画像がノイズになる一貫した知見。
4. **同定の正確さ自体は backend 限界**（Ren を Akira と誤る、精度 ~0.4）。
   prompt 工学では超えられない — 上位モデル（Anthropic fallback / 大きい
   gemma）が要る。これは正直な天井。

**end-to-end 実証**（p02、全自前ホスト）: 外見ガイドで復元 focal grounding
0→4 → 品質ループの `:facePresence` 軸が **nil→1.0 ACTIVE**（score 74）。
co-scientist が特定した勝者がキャラ存在圧として生成ループまで届いた。

lib 9 tests / 60 assertions、mangaka 125 tests / 771 assertions green。
pin: kami-mangaka-qa → 61c8858、ai-gftd-mangaka → d2a33c3。全 45p の v11
バッチは、同定精度の backend 天井（上位モデル導入）とセットで判断する。

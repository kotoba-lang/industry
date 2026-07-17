
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

## Addendum 6 (2026-07-17) — VLM backend 比較（gemma4 vs Qwen3.6-35B-A3B）

同定精度の backend 天井を破れる OSS VLM を探す。gad に vision で serve
できる候補を、addendum 5 の勝者戦略（外見記述）で実測（各3回平均）:

| モデル | p02 同定精度 | p02 grounding | p03 grounding(汎化) |
|---|---|---|---|
| gemma-4-26b-a4b (現行) | 0.367 | 6.0 | 0.0 |
| qwen3.6-35b-a3b | 0.283 | 6.0 | 4.7 |

- **決定打なし**: 同定精度は両者 ~0.3（Q4 量子化の共通天井）。
- **gemma** は複数キャラを混ぜて部分点を稼ぐ（`{Yuto Nei}`）が Ren を
  認識できず、別ページ p03 では grounding を完全に諦める（0）。
- **qwen3.6** は各コマを単一キャラに正しく分離し、p03 でも grounding
  し続ける（汎化が明確に上）が、Nei↔Ren/Ren↔Akira を混同する。
- Qwen3.6-35B-A3B(3B active) は gemma-26b より遅く VRAM も食う
  (23GB vs 20GB)。weights+mmproj は gad resident、serve recipe は
  murakumo `infer.edn` の `qwen3.6-35b-a3b`。比較後 VRAM 解放のため停止。

**結論**: 「murakumo で動く OSS で gemma4 より明確に上」は今の Q4 構成では
存在しない。qwen3.6 は 45p バッチ的な汎化用途で有利だが精度天井は同じ。
根本的に上げるには Q8 量子化(VRAM 次第)か上位 API(ANTHROPIC_API_KEY で
env 切替可)。mangaka.llm は `MURAKUMO_LLM_URL` の差し替えで任意 VLM に
できるので、recover バッチだけ qwen を使う運用は追加実装なしで可能。
pin: ai-gftd-mangaka → e64a193。

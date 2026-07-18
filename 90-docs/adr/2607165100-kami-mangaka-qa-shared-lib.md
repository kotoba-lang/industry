
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

## Addendum 7 (2026-07-17) — OSS VLM 3種の実測 + Datomic query 可能な公開

addendum 6 の「murakumo で動く OSS で gemma4 より明確に上は存在しない」を
覆す3候補を実測した（gad, 同一 GT・p02・各3回、勝者戦略=外見記述）:

| モデル | 精度 | 安定性 | サイズ |
|---|---|---|---|
| **qwen3-vl-30b-a3b** | **0.833** | 3/3 分散ゼロ | 18GB |
| **internvl3.5-30b-a3b** | **0.833** | 3/3 分散ゼロ | 18GB |
| minicpm-v-4.5 | 0.750 | 3/3 分散ゼロ | 5GB |
| gemma-4-26b（旧本番） | 0.367 | 不安定 | — |
| qwen3.6-35b-a3b | 0.283 | 不安定 | — |

**addendum 6 の「天井」判断は誤りだった** — 天井ではなくモデル選択の問題。
**VL ネイティブ設計が決定打**: 同じ 30B-A3B でも VL 専用の qwen3-vl (0.833)
は text-tower+後付け mmproj の qwen3.6 (0.283) の約3倍。OSS が frontier
(claude-sonnet-4-5 も 0.833) に並んだ。

実測中の運用知見: MiniCPM は `--reasoning off` 必須（無いと答えが
reasoning_content に入り content が空）。Qwen3-VL は縮退ループで JSON が
max_tokens 切断されるため救済 parser が要る。InternVL3.5 は出力が最もクリーン。

llm.cljc の耐障害性バグも発見・修正: `vision-json`/`complete-json` は
「nil on failure」を謳っていたが transient API error は素通しで throw して
いた（murakumo の一時エラーが復元バッチをパネル4/6で殺した実測） →
transport/API 例外も nil に degrade するよう修正（ai-gftd-mangaka）。

**Datomic query 可能な形で知識化**: `cloud-murakumo/resources/
vlm-catalog.datoms.edn`（`:vlm/*` / `:measurement/*` / `:constraint/*` /
`:source/*` の4種 entity、DataScript で datalog query 実証済み）。

pin: 3種実測 + Datomic 公開部分は kotoba-lang/kami-mangaka-qa 非変更。
llm.cljc 耐障害性修正は ai-gftd-mangaka commit 7624c1c
("fix(llm): transient API errors degrade to nil instead of crashing
 loops")。cloud-murakumo main commit 865be77（catalog 初版公開）。

## Addendum 8 (2026-07-18) — xavier を qwen3-vl-30b-a3b に配置（owner directive）

オーナー指示「xavier を vl に置き換える」。xavier
(`orgs/kotoba-lang/murakumo` fleet.edn の second standalone inference head,
NVIDIA Jetson AGX Xavier, CUDA 11.4/sm72) の `murakumo-standalone-cuda.
service` (:8090) を qwen3.6-35b-a3b (このベンチで精度 0.283、fleet-OSS
最下位) から qwen3-vl-30b-a3b (0.833) に置換。

手順: xavier 実機で DL (18.5GB GGUF Q4_K_M + 1.1GB mmproj F16) → 手動
llama-server で vision smoke test (2314 prompt/74 completion tokens,
66.4s) → SSH port-forward 経由で識別ベンチ3回、**gad と bit-for-bit 一致
(0.833, 完全安定) を確認してから** systemd 差し替え (`-c 8192`、旧 unit の
`-c 262144` は qwen3.6 の 262144-token 前提で今のモデルには過大。
`--reasoning off` 必須)。旧 qwen3.6 weights は /mnt/nvme にロールバック用
温存 (387GB 空き、gad の gemma4 温存方針と同じ)。

速度は正式ベンチ未実施 (単発サンプルのみ実測、gad比の系統比較はしていない
と ADR/fleet.edn に明記— 旧 qwen3.6 の実測比較値をそのまま qwen3-vl に
転用しない)。

副次的に発覚した運用上の落とし穴 (fleet.edn に記録済み): xavier は
`~/.ssh/config` に host エントリが無く `ssh xavier` が直接は解決できない、
`tailscale ssh xavier` も MagicDNS resolver が一部クライアント Mac で
unreachable になる既知の不具合があった。回避策
`ssh -o "ProxyCommand=tailscale nc %h %p" xavier@xavier.tail110d8b.ts.net`
を確立し記録。またこの過程で `nbb scripts/gen-west-manifest.cljs`
（引数なし dry-run）が murakumo 以外の pin (kagami/security 等) まで
巻き込む wholesale 差分を生成しかけた — CLAUDE.md の警告通りの事故パターン
だったため即座に破棄し、`--entry` 相当の安全な単一 entry API PUT で
pin を前進させた (west.yml の正経路を実地で再確認)。

着地: kotoba-lang/murakumo main aefc0c5 (fleet.edn + infer.edn)、
cloud-murakumo main 7fe3716 (xavier 実測 entity 追加)、west pin
murakumo → aefc0c5 (commit 1b3be77)。worktree/branch 掃除済み。

このタスクをもってセッションの vision-model 系作業を close する。

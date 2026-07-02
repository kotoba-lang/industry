# MIGRATION: RW/D1 → kotoba sovereign 台帳

kotoba Datomic を app の SSoT（sovereign）と宣言する前に通すゲートの台帳。
kotoba には**本番ロールバックの先例**がある（shinshi / yukkuri が
kotoba→RW/D1 に戻した）ため、sovereign 宣言は「動いているように見える」では
なく、**CLJ runtime 側の durable store verification graph の WARM pod 実測**
を通過してからにする（ai-gftd-syosetsuka ADR-2606071600 §7 が初出の規約）。

## Gate 定義（§7 verification graph）

app の CLJ runtime に検証 graph を実装し、kotoba storage substrate に対して
実測する。**k8s の lg-* pod は prune 済み** — 実測先は kotobase.net の tenant
Datom plane（ADR-2607022300: kotoba=storage / murakumo=compute /
aozora=publish。認証は actor 鍵の CACAO 自己発行、書込は
`kotobase/db/<actor-did>/<db-name>` の自 tenant）:

| check | 内容 |
|---|---|
| `:transact` | probe entity 群（作者/作品/話 相当）の tx が受理される |
| `:pull-roundtrip` | transact した attr が pull で無傷で戻る |
| `:tag-fidelity-pull` | **multi-value attr（例 `:nv/tag`）が cardinality を失わず戻る**（過去ロールバックの主因） |
| `:tag-fidelity-q` | 同じ集合が query 経路でも一致する |
| `:body-as-blob` | 長文は datom に入らず blob key のみ（`:ep/bodyBlobKey` 型不変条件） |

規約:

- 既定はネットワークを触らない決定的 store（`{:store "mem"}`）で、CI では
  gate 自体の regression を検知する。**mem 実行は sovereign 判定に使えない**
  （`:sovereign_ready` は常に false）。
- sovereign 判定は `{:store "kotoba", :probe_slug <fresh>}` を kotobase.net に
  対して実行し `:sovereign_ready true` を得ることのみ。live 実行は production
  への書込を伴うため owner が行う（auto-mode agent は permission gate で停止）。
- probe entity は run ごとに fresh な slug を使う（durable store 上での再実行
  衝突を避ける）。

## 台帳

| app | 状態 | gate | 備考 |
|---|---|---|---|
| shinshi | **rolled back** (kotoba→RW) | 未実装 | ロールバック先例。再挑戦時はこの gate を先に実装する |
| yukkuri | **rolled back** (kotoba→D1) | 未実装 | 同上 |
| ai-gftd-syosetsuka | **gate 実装済み / kotobase.net 実測待ち** | `ai.gftd.apps.syosetsuka.verifyStore`（v2 merge `2f895e0`、2026-07-02） | probe は kotoba-lang/shousetsu 語彙。CACAO 自己発行（`syosetsuka.cacao`、caip122 方言、canonical-graph CID は既知 KG CID とバイト一致検証済み）+ `syosetsuka.store.kotoba`（langchain.kotoba-db、write=db_name+CACAO / read=graph CID）。fake edge で wire 契約テスト済み。live 実測コマンドは repo CLAUDE.md §7（owner 実行） |

## 追記の仕方

app に検証 graph を実装 → 本台帳に行を追加（gate NSID / merge SHA / 実測状態）
→ kotobase.net で `{:store "kotoba"}` を実行し `:sovereign_ready true` の evidence
（run 出力）を備考にリンク → その app の CLAUDE.md で sovereign 宣言。

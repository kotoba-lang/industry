# MIGRATION: RW/D1 → kotoba sovereign 台帳

kotoba Datomic を app の SSoT（sovereign）と宣言する前に通すゲートの台帳。
kotoba には**本番ロールバックの先例**がある（shinshi / yukkuri が
kotoba→RW/D1 に戻した）ため、sovereign 宣言は「動いているように見える」では
なく、**CLJ runtime 側の durable store verification graph の WARM pod 実測**
を通過してからにする（ai-gftd-syosetsuka ADR-2606071600 §7 が初出の規約）。

## Gate 定義（§7 verification graph）

app の CLJ runtime に検証 graph を実装し、WARM pod に対して以下を実測する:

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
- sovereign 判定は `{:store "kotoba", :probe_slug <fresh>}` を WARM pod に対して
  実行し `:sovereign_ready true` を得ることのみ。endpoint 未設定での kotoba
  指定は fail-closed（silent pass 禁止）。
- probe entity は run ごとに fresh な slug を使う（durable store 上での再実行
  衝突を避ける）。

## 台帳

| app | 状態 | gate | 備考 |
|---|---|---|---|
| shinshi | **rolled back** (kotoba→RW) | 未実装 | ロールバック先例。再挑戦時はこの gate を先に実装する |
| yukkuri | **rolled back** (kotoba→D1) | 未実装 | 同上 |
| ai-gftd-syosetsuka | **gate 実装済み / WARM 実測待ち** | `ai.gftd.apps.syosetsuka.verifyStore`（`syosetsuka.graphs.verify-store`、merge `9e020ac`、2026-07-02） | probe は kotoba-lang/shousetsu 語彙。client は `syosetsuka.store.kotoba`（XRPC transact/q/pull、transport 注入可）。sovereign 宣言は lg-syosetsuka pod deploy 後に operator が `{:store "kotoba"}` 実測で判定 |

## 追記の仕方

app に検証 graph を実装 → 本台帳に行を追加（gate NSID / merge SHA / 実測状態）
→ WARM pod で `{:store "kotoba"}` を実行し `:sovereign_ready true` の evidence
（run 出力）を備考にリンク → その app の CLAUDE.md で sovereign 宣言。

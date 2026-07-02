# ADR-2607022100: BMC を schedule で循環させる gate 評価器 + LLM advisor 配線

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2607021800 で毎朝の運転 routine を作ったが、ReAct loop は tick 1 で実測
signal を貼ると 2 日目以降は dedup で dry になり、canvas が前進しなかった。
各 BMC が schedule で**循環して前進する**ために、共通で不足していたのは 2 つ:

1. **gate 評価器が無い** — validation が全 product 0 のまま。collect した metric
   を hypothesis の gate と比較して仮説を進める仕組みが無かった。
2. **LLM advisor が未配線** — `mock-advisor` の 3 dedup ルールのみで、think が
   新しい提案を出せず dry になる。

さらに各 product の gate はそれぞれ固有の計器（YouTube 統計・checkout・fork
テレメトリ等）を要し、その「不足の可視化」も無かった。

## Decision

`70-tools/bmc` に以下を実装（全て .cljc、io は cli/collect のみ）:

1. **`gftd.gate`** — riskiest hypothesis ごとに **gate-spec** を定義:
   - **機械測定**（`{:metric [path] :op :>= :threshold N}` / `:all` 連言）—
     product の metrics edn（collect.bb が集める Cloudflare/Stripe/health/
     product 供給値）に対して評価。満たせば `hyp/status :validated` を evidence
     つきで提案（**自動昇格**）、測定可・未到達なら「gate 距離」観測を Key Metrics に。
   - **計器不足**（`{:needs [...]}`）— まだ測れないので、不足計器を **「準備:」項目**
     として Solution ブロックに提案（実行 to-do 化）+ gate を `:blocked` 記録。
2. **`react/gate-aware-advisor`**（新デフォルト）= `mock-advisor` + gate 提案。
   これで schedule は「毎朝 gate を測り、通れば仮説を validated に昇格、通らなければ
   不足計器を実行項目として surface」する本物の kaizen になった。
3. **`react/llm-advisor`** — LLM 差し替え seam。`(fn [prompt] -> string)`（例:
   langchain.model / murakumo text）を注入すると観測から proposal EDN を生成。
   malformed は no-op（governor が backstop）。gate-aware と compose して novelty +
   gate 進行を両立。
4. **CLI `gate` コマンド** — 全 product / 単一 product の gate 状態を一覧表示。
5. 全書込は従来どおり **governor 検閲 → ledger**。準備項目は dedup され、2 回目
   以降は governor が duplicate 拒否 → 収束（gate が動くか新計器が入るまで静か）。

## 各 product の gate 現況（`gftd gate` 実測、as-of 2026-07-02）

| product | 状態 | 内容 |
|---|---|---|
| ai-gftd-yukkuri | **measuring** | 登録者 3 / 総再生 10.2h（YPP gate 1,000 / 4,000h 未到達）— 機械測定可 |
| net-kotobase | blocked | signup→checkout 配線（kotobase price 既存）/ tenant 従量計測。Stripe 収集で active-subscriptions が入れば first-tenant gate が機械測定に変わる |
| cloud-murakumo | blocked | run ledger の原価/tok export / 社内3アプリ fleet 移管 |
| ai-gftd-apex | blocked | tier 価格定義 / Stripe product / Free→Plus 転換テレメトリ |
| cloud-itonami | blocked | 初期 vertical 絞り込み / 外部オンボーディング / per-seat billing |
| app-aozora | blocked | engagement テレメトリ（DAU / post engagement） |
| app-aozora-yoro | blocked | yoro child repo 分離 / MAU テレメトリ |
| cloud-manimani | blocked | OSS install テレメトリ / cloud signup funnel / 価格設計 |
| etzhayyim | blocked | itonami 契約の RAD 参照フック / 資金チャネル |
| network-isekai | blocked | fork イベントテレメトリ（週次 fork / fork 由来比率） |
| club-shinshi | blocked | PSP/crypto rail 解禁 / creator billing / GMV 計測 |

各 product の loop 運転でこれらが Solution ブロックに「準備:」to-do として着地
（yukkuri は Key Metrics に gate 距離）。tests 6→9（22→31 assertions）green。

## Consequences

- (+) schedule が dry ではなく毎朝「gate 測定 → 昇格 or 不足計器の surface」を回す
  真の kaizen サイクルになった。各 BMC の**前進に必要な準備が canvas 上の実行
  項目**として台帳化された。
- (+) gate が機械測定可能になった product（yukkuri / kotobase の Stripe）は、実測が
  gate を越えた朝に **routine が自動で hyp を validated に昇格**（facts 昇格の承認は
  人、というガードレールは維持 — validation は canvas 側の仮説状態で、facts の
  launched/users/revenue とは別レイヤー）。
- (+) LLM advisor の注入口が確定。murakumo text / langchain.model を complete-fn
  として渡せば mock から LLM へ差し替わる。
- (−) 各 product 固有の計器（YouTube OAuth 復活・kotobase checkout・fork
  テレメトリ・engagement 計測 等）は product repo 側の実装で、本 ADR は「何が
  不足か」を機械可読・毎朝 surface する所まで。実装は個別 follow-up。
- (−) LLM advisor の complete-fn 実配線（murakumo エンドポイント）は cloud
  routine 環境の鍵配布が前提 — 未配線（seam のみ）。

## References

- ADR-2607021600（ReAct loop / advisor ⊣ governor）
- ADR-2607021700 / 2607021800（スコア / collect）
- ADR-2607021900 / 2607022000（product 追加時の gate 診断の出所）
- `70-tools/bmc/src/gftd/gate.cljc` / `react.cljc`（gate-aware-advisor / llm-advisor）

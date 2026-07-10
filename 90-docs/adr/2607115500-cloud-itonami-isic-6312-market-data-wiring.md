# ADR-2607115500: cloud-itonami-isic-6312 が cloud-itonami-isic-6311 の市場データを governed listing source として消費する配線を追加

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

`cloud-itonami-isic-6311`(市場データ actor)と `cloud-itonami-isic-6312`
(Webポータル actor)は同一セッションで新設された兄弟 actor で、6311 の
README は「市場データ層」、6312 は「サードパーティコンテンツを集約する
ポータル層」という補完関係にある。オーナーの「既存 actor の相互接続を進める」
指示のもと、6312 が 6311 の governed `:disclosure/query` を実際に消費する
配線を追加した(ドキュメント上の言及のみではなく、実コード上の依存として)。

## Decision

`cloud-itonami-isic-6312` の `src/portal/marketdata_bridge.cljc`(新規、
`portal.*` core からの compile-time 依存ではない — optional/injectable)
が、`cloud-itonami-isic-6311` の `OperationActor` へ `:disclosure/query`
を発行し、結果を portal の `:listing/publish` request へ整形する。

### 単一不変条件の合成(bypass ではなく compose)

> **市場データ由来の listing も、PortalGovernor が拒否する publish を
> 決して bypass しない。**

6311 側の `:disclosure/query` が MarketDataGovernor(tolerance-gate・
source-provenance-gate・licensed-disclosure・halted-instrument gate)を
通過して **commit** した場合のみ、bridge は
`{:class :cloud-itonami-market-data-feed :ref "cloud-itonami-isic-6311:<id>"}`
という実在する出典citationを構成する。6311 側の query が commit しなかった
場合(hold/escalate)、bridge は **`:source nil`** を返す — これは通常の
「出典なきリスティング」と全く同じ扱いで、6312 自身の
`source-provenance-gate` が独立に HARD reject する。市場データ由来の
listing だけを特別扱い(governor bypass)することは一切ない — 2層の
governor(6311 のMarketDataGovernor + 6312 のPortalGovernor)を**合成**
しているだけである。

### 新規 R0 出典クラス `:cloud-itonami-market-data-feed`(portal.facts、5件目)

既存4クラス(public-domain / cc-attribution / fair-use-excerpt /
licensed-syndication)に加え、「governed sibling actor の disclosure
出力」という5件目の実在クラスを追加した。`:licensed-syndication` と異なり
別途 `content-license` レコードの検証は不要 — grounding は6311側で既に
完了しているため二重検証しない。

### deps.edn: optional `:market-data` alias

`io.github.cloud-itonami/cloud-itonami-isic-6311 {:local/root "../../cloud-itonami/cloud-itonami-isic-6311"}`
を base `:deps` ではなく `:market-data` alias に隔離。デフォルトの
`clojure -M:dev:test` は 6311 の checkout 無しでも green のまま(35 tests
/ 141 assertions)。bridge のテストは別ディレクトリ `test-market-data/`
に置き、`clojure -M:dev:test:market-data`(**`:market-data` を最後に
置く必要がある** — Clojure CLI は複数 alias の `:main-opts` をマージせず
最後に選択された alias のものが勝つ)で追加実行(37 tests / 148
assertions)。isic-6311→securities の配線(ADR-2607112200)と同じ
optional-injectable の作法。

## Consequences

- (+) 6311/6312 という2つの兄弟 actor が実コード上で相互接続され、
  「サードパーティコンテンツポータルが governed 市場データウィジェットを
  掲載する」という現実の業態(金融ニュースポータルの相場ティッカー等)を
  デモできるようになった。
- (+) `clojure -M:lint`: エラー0・警告0。デフォルトテストスイートは無変更
  (35/141、0 failures)。market-data alias 込みで 37/148、0 failures
  (bridge の commit シナリオ・hold-and-no-special-casing シナリオ双方を
  カバー)。
- (-) 6311 側の governed query が hold/escalate した場合、bridge は
  listing 自体を作らない(空の掲載枠になる) — UX上のフォールバック
  (「市場データ準備中」表示等)は operator の責任範囲として本 ADR の
  スコープ外。
- superproject への反映: 本 ADR のみ(両 repo とも standalone、plain-git、
  west 非登録のため `manifest/west.yml`/`repos.edn` への変更は無い)。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-6312/README.md`「Consuming
  cloud-itonami-isic-6311 for market-data content」節
- `orgs/cloud-itonami/cloud-itonami-isic-6312/src/portal/marketdata_bridge.cljc`
- `orgs/cloud-itonami/cloud-itonami-isic-6312/test-market-data/portal/marketdata_bridge_test.clj`
- `90-docs/adr/2607111500-cloud-itonami-isic-6311-market-data-actor.md`
- `90-docs/adr/2607112200-cloud-itonami-isic-6311-feed-connectors-and-securities-wiring.md`
  (同型の optional-injectable 配線パターンの直接の手本)

# ADR-2607021900: ポートフォリオに network-isekai（Roblox 型）と club-shinshi（PornHub/OnlyFans/FANZA 型）を追加

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2607021500 の 8 プロダクト 7 レイヤー体系に、オーナー指示（2026-07-02）で
2 プロダクトを追加する:

- **network-isekai** を **Roblox 型 UGC ゲーム/クリエイタープラットフォーム**として
- **club-shinshi** を **PornHub / OnlyFans / FANZA 型アダルトクリエイタープラット
  フォーム**として

いずれも business model / BMC / lean canvas を設計し、既存の datoms 正本・CLI・
スコアリングに統合する。両者とも isekai.network / shinshi.club として本番稼働
しており、実測トラフィックがある。

## Decision — レイヤー追加

| Layer | Product | 参照モデル | 一言 |
|---|---|---|---|
| **L6 UGC game / creator platform** | network-isekai | Roblox | play/fork/share の AI-native UGC ゲーム/資産プラットフォーム |
| **L4 adult creator platform** | club-shinshi | PornHub / OnlyFans / FANZA | 3 型ハイブリッドのアダルトクリエイター経済圏 |

### network-isekai — Roblox 型

「ゲームが**データ**（EDN scene + CLJ logic）」で、ブラウザで遊び URL で**即 fork**
できる（CodePen for games）。VRM dance stage、AI 生成 asset hub（photo→3D/image/
TTS/music を Modal GPU で生成 → ワンクリック publish）まで同じ play/fork/share loop。
Roblox 経済圏を content-addressed・可搬（「逃げられる Roblox」）で実装する:
platform は UGC をホストし creator に revenue share（DevEx 型 take rate）、
GPU 原価は murakumo/Modal、marketplace 手数料と AI 生成従量が収益。
**riskiest**: 「作品=データで fork できる」が creator/player 増殖ループを起こすか
（gate = fork 由来の新規作品比率と週次 fork 数、viral 係数）。

### club-shinshi — PornHub / OnlyFans / FANZA 型

shinshi.club（AT Protocol AppView、自前 age gate 18+、LangGraph AI 生成）。
ai-gftd-shinshi の ad-supported BMC（`docs/260613-bmc-lean.datoms.edn`）を
**platform 版**に拡張し、3 型をハイブリッド化: **PornHub 型**無料 tube（ExoClick
ad）+ **OnlyFans 型** creator 課金（サブスク/PPV/チップ、低手数料）+ **FANZA 型**
marketplace（単品/セット販売手数料）。ExoClick ad は現行 **gftd 唯一の :live 収益**
（ADR-2606130000）。creator 課金は PSP 制約（ADR-2605220000）で凍結履歴があり、
crypto USDC + fiat dual-rail / etzhayyim settlement 後に解禁。
**riskiest**: 低手数料 creator 課金が ad 収益を超える収益軸になるか
（gate = rail 解禁後、creator 課金 GMV が ExoClick ad 収益を上回る）。

各 canvas 9 block 全文は base datoms
（`90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn`）と生成 md
（`90-docs/business/<product>-business-model.md`）が正。

## Decision — 統合

1. **base datoms** に 2 プロダクト × 9 block + riskiest 仮説を追記
   （`:canvas/layer :ugc-game-platform` / `:adult-creator-platform`）。
2. **CLI registry**（`gftd.cli`）に `isekai`（→network-isekai）と
   `club`（→club-shinshi）を追加。`gftd` umbrella は全 10 product を扱う。
3. **layer-labels**（`gftd.canvas`）に L6 / L4-adult を追加。
4. **maturity-facts.edn** に実測根拠つき facts を追加、`metrics/*.edn` に
   実測 zone トラフィックを記録。
5. ReAct loop を両 product で運転（実測 signal を canvas へ fold、governor 検閲、
   両 loop 収束）。canvas md --all / score md を再生成。tests 6/22 green。

## スコア（as-of 2026-07-02、実測反映）

| product | BMC | YC bench | 特記 |
|---|---|---|---|
| **club-shinshi** | 68 | **68**（全 product トップ） | gftd 唯一の実収益（ExoClick ad、revenue=3）+ traffic 上位 |
| **network-isekai** | 60 | 50 | isekai.network 実稼働（12,186 req/7d・PV 7,819）、creator 経済圏は未収益化 |

club-shinshi は「実収益がある」ことで YC bench 軸で初めて 60 台後半に乗り、
ポートフォリオ全体のトップになった（他は revenue 0–1 が律速のまま）。

## Consequences

- (+) 10 プロダクト・8 レイヤーの整合体系に拡張。Roblox 型 UGC とアダルト
  creator 経済圏という 2 つの高 traffic 領域が canvas 化された。
- (+) club-shinshi の実収益が YC bench の revenue 律速を初めて突破する実例に。
- (−) network-isekai の creator 経済圏（DevEx/marketplace の take rate）と
  club-shinshi の creator 課金 rail は未確定 — 別 ADR で価格・rail を定める。
- (−) ai-gftd-shinshi（60-apps モノレポ側の ad BMC）と club-shinshi（分離 repo・
  platform BMC）の二重管理 — canvas 正本は本 ADR 側に集約し、ai-gftd-shinshi の
  260613 datoms は歴史的 ad-supported 版として残す。

## References

- ADR-2607021500（7 レイヤー正本、本 ADR で L6 / L4-adult を追加）
- ADR-2607021600 / 2607021700 / 2607021800（CLI / スコア / collect）
- ADR-2606130000（portfolio revenue、shinshi :live）/ ADR-2605220000（PSP 凍結）
- `orgs/gftdcojp/network-isekai/README.md` / `orgs/gftdcojp/club-shinshi/README.md`
- `orgs/gftdcojp/ai-gftd-shinshi/docs/260613-bmc-lean.datoms.edn`（ad-supported 先行版）

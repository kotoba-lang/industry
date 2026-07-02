# ADR-2607022000: ポートフォリオに ai-gftd-yukkuri（YouTube 収益型）を追加

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2607021500 / 2607021900 の 10 プロダクト体系に、オーナー指示（2026-07-02）で
**ai-gftd-yukkuri** を **YouTube に動画を投稿して広告収益を得る business model**
として追加する。business model / BMC / lean canvas を設計し、既存の datoms 正本・
CLI・スコアリングに統合する。

## Decision — レイヤー追加

| Layer | Product | 参照モデル | 一言 |
|---|---|---|---|
| **L7 AI video content channel** | ai-gftd-yukkuri | YouTube 広告収益 / MCN 型 | ゆっくり実況を全自動生成し YouTube 投稿→広告収益 |

### ai-gftd-yukkuri — YouTube 収益型

yukkuri.gftd.ai は 1 トピック / 1 台本から動画を**全自動生成**する（10 actor DID:
scriptwriter → voiceLeft/Right（VOICEVOX）→ character → illustrator → sfx →
composer（ongakuka BGM）→ editor → renderer（kami-engine headless）→ critic、
最終 mux は dougaka ffmpeg）。生成物を YouTube Data API で投稿し、**YouTube 広告
収益（YPP、視聴時間 × RPM）**を得る。

business model の核は「**1 本の限界コストが ~0**」＝生成原価がほぼ固定費なので、
量産 A/B（尺分割・サムネ複数案・タイトル複数案・ショート切り出し）と横展開
（ジャンル別チャンネル増殖、将来は MCN/OEM でパイプライン外販）が構造的に可能。
収益は traffic × RPM が律速で、集客は organic のみ（CAC ~0）。

**riskiest**: 全自動量産チャンネルが YPP 要件を越え、RPM × 視聴時間が生成原価を
上回る収益になるか（gate = YPP 加入＝登録者 1,000 + 総再生 4,000h 到達、その後
RPM 実測が原価を上回る）。

各 canvas 9 block 全文は base datoms
（`90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn`）と生成 md
（`90-docs/business/ai-gftd-yukkuri-business-model.md`）が正。

## Decision — 統合

1. **base datoms** に 9 block + riskiest 仮説を追記（`:canvas/layer :video-content-channel`）。
2. **CLI registry**（`gftd.cli`）に `yukkuri`（→ai-gftd-yukkuri）を追加。
3. **layer-labels**（`gftd.canvas`）に L7 を追加。
4. **maturity-facts.edn** に実測根拠つき facts、`metrics/ai-gftd-yukkuri.edn` に
   実測チャンネル数値を記録。
5. ReAct loop 運転（実測 signal を canvas へ fold、governor 検閲、loop 収束）。
   canvas md --all / score md 再生成。tests 6/22 green。11 product・9 レイヤーに拡張。

## スコア（as-of 2026-07-02、実測反映）

| product | BMC | YC bench | 特記 |
|---|---|---|---|
| **ai-gftd-yukkuri** | 60 | 45 | ゆっくりサイバーch 実稼働（公開 2026/06/06、登録者 3・総再生 10.2h）だが **YPP 未達**（登録者 1,000 + 4,000h が gate）、収益ゼロ |

実測は 260607 チャンネル分析（`UCTisE2aPQp3i8i6JUVIUoiw`）に基づく。立ち上げ
直後で母数が小さく、YouTube 広告収益は YPP 加入が前提のため revenue=0 が正直値。

## Consequences

- (+) 11 プロダクト・9 レイヤー体系に拡張。YouTube 広告収益という gftd 内で
  club-shinshi（ExoClick ad）と並ぶ「実 traffic → 広告」型の 2 本目が canvas 化。
- (+) 全自動生成 → 量産 → YPP 到達という明確な gate が仮説として台帳化された。
- (−) YouTube Data API の OAuth 復活が metadata 自動最適化・投稿自動化の前提
  （docs/youtube-upload-setup / youtube-metadata-optimization）— 別作業。
- (−) mp4 render は `compose_scene` の合成フレーム未生成で一部未完
  （content-pipeline 別件、CLAUDE.md 記載）。canvas は business model 記述で、
  実装完了度は grounding=4 に反映済み。

## References

- ADR-2607021500（レイヤー正本、本 ADR で L7 を追加）
- ADR-2607021900（isekai/club 追加、本 ADR は同型の product 追加）
- ADR-2607021600 / 2607021700 / 2607021800（CLI / スコア / collect）
- `orgs/gftdcojp/ai-gftd-yukkuri/`（CLAUDE.md / 260607-youtube-channel-analysis /
  260614-youtube-metadata-optimization / docs/youtube-upload-setup）

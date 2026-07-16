# ADR-2607116400: cloud-itonami-isic-4510 — 自動車販売(Sale of Motor Vehicles)を VehicleSale-LLM ⊣ VehicleSaleGovernor で実装する actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

オーナーの「成熟度を高めて」という指示のもと、`kotoba-lang/industry` registry
の未着手 `:spec` スロットから新規 actor を追加した。ISIC Rev.4 4510
「Sale of motor vehicles」が対象。同セッションで既に実装した
`cloud-itonami-isic-4774`(中古品全般のリセールマーケットプレイス)とは
業態が本質的に異なる — 自動車販売は権原(タイトル)・リーエン・走行距離
開示という**車両固有の実定法上の制約**(49 U.S.C. Chapter 327 連邦走行距離
開示法等)を持つため、独立した業態として実装した。死んだ
`gftdcojp/cloud-itonami-G4510` プレースホルダー URL のまま `:spec` で
放置されていたスロットである。

## Decision

新規 actor `cloud-itonami-isic-4510` を `cloud-itonami` org 直下に
public/AGPL-3.0-or-later で新設した(namespace `vehiclesale`)。新車/
中古車のディーラー/マーケットプレイス販売プラットフォーム。actor は
出品/開示/成約の**決定のみ**を扱い、決済・エスクロー・実車のカストディは
一切扱わない(No :robotics)。

### VehicleSale-LLM ⊣ VehicleSaleGovernor(単一不変条件)

> **VehicleSale-LLM は、VehicleSaleGovernor が拒否する出品確定・成約確定・
> 紛争解決を決して行わない。**

8チェック(5 HARD: rbac・**lien-clearance-gate**・
**odometer-disclosure-gate**・source-provenance-gate・licensed-disclosure、
3 SOFT: 確信度フロア・salvage-title-gate・dispute-request 無条件)。

`lien-clearance-gate` と `odometer-disclosure-gate` は他の cloud-itonami
actor に存在しない domain-unique HARD チェック:

- **lien-clearance-gate**: アクティブなリーエンが未解消のまま(`:lien-
  cleared?` の申告なしに)成約するのを無条件拒否する。isic-6311 の
  tolerance-gate と同型の「桁間違い/未検証状態をそのまま通さない」構造的
  防御。
- **odometer-disclosure-gate**: 走行距離のロールバック(直近記録値を下回る
  申告)、または連邦法(49 U.S.C. Chapter 327 / 49 CFR Part 580)上の
  開示証明が非適用除外車両(モデル年20年未満)で欠如している成約を無条件
  拒否する。適用除外(モデル年20年以上)の判定ロジックも実装し、その境界を
  誤って全車両に適用しない。

`salvage-title-gate` はサルベージ/水没/再建権原車両の成約を常に人間承認へ
回す SOFT チェック(isic-6311 の halted-instrument gate の写像)。
`default-phase` はセッション開始時点から保守的な `1` を採用(isic-6311/
isic-7820 で見つかった fail-open バグの事前適用、初期実装時点から正しい
設計)。

### R0 の正直なスコープ

出典カタログ(`src/vehiclesale/facts.cljc`)は実在する2つの無料公式ソース
(NMVTIS 米国DOJ公式ワンレポートゲートウェイ、NHTSA recalls)+ 1つの
構造的クラス `:operator-licensed-dmv-feed`(州ごとの権原/リーエンデータは
全米統一の無料ソースが存在しないため、operator が自前の州DMVアクセス権を
`dmv-license` レコードとして登録して初めて取込可能)。

### Robotics premise: false

出品・成約決定のみのデジタルサービスであり、実車の引渡し・決済・カストディ
は actor の境界外。

## Consequences

- (+) `kotoba-lang/industry` registry の 4510 スロットが `:spec`(死んだ
  `gftdcojp/cloud-itonami-G4510` URL)から実装へ昇格。
- (+) `clojure -M:dev:test`: 34 tests / 113 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。`clojure -M:dev:run` デモも
  10シナリオ全て正しく発火(出典あり出品→commit、出典なし→hold、
  リーエン未解消成約→hold、リーエン解消済み成約→commit、過剰開示×2→hold、
  サルベージ権原成約→人間承認→commit、紛争申立て→人間承認→commit、
  走行距離ロールバック→hold、適用除外車両の成約(開示証明なし)→commit)。
- (-) R0 の自由公式ソースは2種のみ。州ごとの権原/リーエンは operator の
  dmv-license 登録が必須。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`manifest/west.yml` の `industry` entry、`--entry industry` の最小
  diff、pin検証 OK)。`cloud-itonami-isic-4510` は既存の
  `cloud-itonami-{ISIC}` blueprint 群と同じ慣例により
  `manifest/repos.edn` には登録しない(standalone、plain-git 子リポ)。
- `kotoba-lang/industry` へのレジストリ登録・test count 更新は、共有
  checkout が他の並行フォーク(isic-3822/isic-6209)の WIP と競合したため、
  GitHub API 単一エントリ編集(blob SHA 一致 PUT、`repos.edn
  :manifest-workflow` の正経路)で着地した(commit `9240224`/`f2c7f51`)。

## 代替案と不採用理由

- **`cloud-itonami-isic-4774` に統合**: 4774 は中古品全般のリセール
  マーケットプレイスで、権原/リーエン/走行距離開示という車両固有の実定法
  制約を持たない。同一スロットへの統合は業態の法的責任構造の違いを隠蔽
  する。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-4510/README.md` + `docs/adr/0001-architecture.md`
- `90-docs/adr/2607115200-cloud-itonami-isic-4774-resale-marketplace-actor.md`(業態対比の対象)
- `90-docs/adr/2607111500-cloud-itonami-isic-6311-market-data-actor.md`(フリート標準パターンの手本)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`(id "4510" エントリ)

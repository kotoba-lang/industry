# ADR-2607113700: cloud-itonami-isic-6312 — Webポータル・コンテンツ集約を PortalCurator-LLM ⊣ PortalGovernor で実装する actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

オーナーから「ISIC fleet の成熟度を高めて(:spec → :implemented の昇格を
進めて)」との指示があり、`kotoba-lang/industry` registry の未着手 `:spec`
スロットから対象を選定した。ISIC Rev.4 6312「Web portals」は、姉妹スロット
`cloud-itonami-isic-6311`(market data、ADR-2607111500)と同じ3桁親コード
配下に位置し、既存の実装済み隣接コードとの重複が無いことを確認した上で
選定した。死んだ `gftdcojp/cloud-itonami-J6312` プレースホルダー URL の
まま `:spec` で放置されていたスロットである。

third-party ソースの要約・featured 配置案・広告主向け開示列の提案には LLM
が有効だが、**LLM に掲載・配置・開示・削除確定を直接行わせるのは危険**で
ある(出典なきリスティングの断定=汚染コンテンツ伝播、fair-use 範囲を超えた
全文転載=著作権侵害、開示ラベル無しスポンサード配置=FTC native-advertising
違反、告発対象記事の自動配置=名誉毀損リスク)。

## Decision

新規 actor `cloud-itonami-isic-6312`(ISIC Rev.4 6312)を `cloud-itonami`
org 直下に public/AGPL-3.0-or-later で新設する。`cloud-itonami-isic-6311`
(収集・保持・契約者限定開示パターン)を直接の手本としつつ、コンテンツ集約
業固有のリスク面(著作権スコープ超過、ネイティブ広告開示義務)に対応する
新規 HARD チェックを2つ追加した。

### 1. PortalCurator-LLM ⊣ PortalGovernor(単一不変条件)

> **PortalCurator-LLM は、PortalGovernor が拒否するリスティングの掲載・
> 配置・開示・削除確定を決して行わない。**

8チェック(5 HARD + 3 SOFT):

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1 | rbac | HARD | actor-role が operation の権限を持つか |
| 2 | source-provenance-gate | HARD | 出典クラスが許可リストに無ければ拒否。`:licensed-syndication` はアクティブな `content-license` を要求 |
| 3 | **license-scope-gate**(新規) | HARD | `:fair-use-excerpt` 出典のリスティングは抜粋長が保守的上限(400文字)を超えたら拒否。17 U.S.C. §107 の excerpt/commentary 法理に根拠を置く(ブライトラインテストではないことを明記) |
| 4 | **disclosure-gate**(新規) | HARD | スポンサード配置に開示ラベルが無ければ拒否(FTC native-advertising、16 CFR Part 255) |
| 5 | licensed-disclosure | HARD | 有効な契約(tenant×tier)が無い、または開示列が tier を超えたら拒否 |
| 6 | 確信度フロア | SOFT | `:confidence < 0.6` → escalate |
| 7 | sensitive-subject gate | SOFT | 告発対象のリスティング配置 → 必ず人間承認 |
| 8 | takedown-request | SOFT(無条件) | 削除/訂正申立ては確信度に関わらず常に人間レビュー |

**意図的に無い項目**: 与信/決済関連チェックは存在しない — この actor は
コンテンツの集約・配置・開示のみを行い、注文執行・決済処理を一切含まない。

### 2. Phase 0→3、default-phase = 1(実装当初から保守的)

`cloud-itonami-isic-7820` で確立された規律に倣い、`default-phase` は実装
当初から `1`(assisted、auto-commit 無し)。`:phase` を省略した呼び出し元が
最大自律性を得る fail-open を、最初から回避した。`:takedown/request` は
どの phase の `:auto` にも入らない構造的恒久ゲート。

### 3. R0 の正直なスコープ(捏造禁止)

出典カタログ(`src/portal/facts.cljc`)は実在する3つの自由・公式法的根拠
(US federal public domain: 17 U.S.C. §105、CC BY 4.0、fair-use excerpt:
17 U.S.C. §107)+ 1つの構造的クラス `:licensed-syndication`(operator が
自前のライセンス契約を `content-license` レコードとして登録して初めて
取込可能)。`facts/coverage` が常に正直に現状を報告する。

### 4. Robotics premise: false

物理的な配送・実物資産の移動を伴わない、コンテンツの集約・配置・開示のみの
デジタルサービスであり、actor の境界の外に物理的な作動は存在しない。

## Consequences

- (+) `kotoba-lang/industry` registry の 6312 スロットが `:spec`(死んだ
  `gftdcojp/cloud-itonami-J6312` URL)から実装へ昇格。
- (+) license-scope-gate・disclosure-gate という、他の cloud-itonami
  actor に存在しないコンテンツ集約業固有の HARD チェックを新設した。
- (+) `clojure -M:dev:test`: 35 tests / 137 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。`clojure -M:dev:run` デモも
  end-to-end で確認済み(8シナリオ全て正しく発火)。
- (-) R0 の自由法的根拠は3種のみ。ほとんどの商用シンジケーション契約は
  operator の content-license 登録が必須で、この actor 単体では取込でき
  ない。
- (-) Datomic/kotoba-server backend は次のシーム(未接続)。
- **(!) west.yml 生成ツールが本 ADR 作成時点で不調だった**:
  `scripts/gen-west-manifest.bb`(babashka)が同時並行のセッションにより
  `scripts/gen-west-manifest.cljs`(nbb)へ移行されていたが、移行が未完了
  (`scripts/nbb_compat/` への classpath 設定が無く、
  `Could not find namespace: clojure.java.shell` で失敗)。本 ADR の
  `kotoba-lang/industry` pin 前進は、別セッション(isic-5590 registration)
  が同じ不調を発見し確立した回避策(GitHub API 直接 PUT による west.yml
  entry 編集、`repos.edn :manifest-workflow` の正経路である blob SHA
  一致 PUT)により、既に前進済みの pin(`8befc866f4be`)に本 actor の
  registry commit が祖先として含まれることを確認した上で追加のpin編集は
  不要と判断した(GitHub API `compare` で ahead_by=5, behind_by=0,
  merge_base=本 actor の登録 commit を確認済み)。`gen-west-manifest.cljs`
  の nbb 移行自体の修復は本 ADR のスコープ外(別途フォローアップが必要)。

## 代替案と不採用理由

- **6611(取引所運営)/6612(証券・コモディティ仲介業)等、既存実装済み
  スロットの流用**: 該当なし(6312 は独立した web-portal 業態)。
- **LLM に掲載・配置権限を直接付与(エージェント自律)**: 速いが、出典なき
  断定・著作権範囲超過・開示義務違反・名誉毀損リスクを構造的に防げない。
  単一不変条件(決定1)に反する。
- **license-scope-gate を SOFT にとどめる**: fair-use の範囲逸脱は確信度
  と無関係に起きるため、SOFT では低確信フィルタをすり抜ける高確信の全文
  転載を止められない。HARD が必須と判断した。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-6312/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対)
- `90-docs/adr/2607111500-cloud-itonami-isic-6311-market-data-actor.md`
  (直接の手本、フリート標準パターン)
- `90-docs/adr/2607112900-cloud-itonami-isic-7820-temp-staffing-actor.md`
  (default-phase 保守的設計の踏襲元)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "6312" エントリ)

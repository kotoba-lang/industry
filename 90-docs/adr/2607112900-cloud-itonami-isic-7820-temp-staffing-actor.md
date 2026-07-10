# ADR-2607112900: cloud-itonami-isic-7820 — 労働者派遣事業(Temporary Employment Agency)を TempStaffing-LLM ⊣ StaffingGovernor で実装する actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

`cloud-itonami-isic-6311`(market-data actor、ADR-2607111500)に続き、
`kotoba-lang/industry` registry の未着手 `:spec` スロットから次の対象を
選定した。ISIC Rev.4 7820「Temporary employment agency activities」
(Randstad/Adecco/ManpowerGroup 級の労働者派遣業)は、既に実装済みの隣接
コード 7810「Activities of employment placement agencies」(一時金/紹介料
モデル、あっせん業者は雇用主にならない)とは業態が本質的に異なる:
**7810 はエージェント型**(仲介のみ、紹介後は雇用関係に関与しない)である
のに対し、**7820 は派遣元自身が雇用主(employer of record)になり、労働者を
クライアント企業へ派遣する**。`gh repo list cloud-itonami` で未着手を確認
した上で選定した。

## Decision

新規 actor `cloud-itonami-isic-7820`(ISIC Rev.4 7820)を `cloud-itonami`
org 直下に public/AGPL-3.0-or-later で新設する。`cloud-itonami-isic-6311`
/`cloud-itonami-isic-8291` と同型の「封じ込め+独立governor+不変台帳」actor
パターンを踏襲しつつ、労働者派遣業固有の実定法上の制約(派遣期間上限・
賃金コンプライアンス)に対応する新規 HARD チェックを2つ追加した。

### 1. TempStaffing-LLM ⊣ StaffingGovernor(単一不変条件)

> **TempStaffing-LLM は、StaffingGovernor が拒否する派遣配置の確定・
> 賃金支払・紛争解決を決して行わない。**

8チェック(5 HARD + 3 SOFT):

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1 | rbac | HARD | actor-role が operation の権限を持つか |
| 2 | eligibility-gate | HARD | 労働者の就労資格・適性証明が未確認なら拒否 |
| 3 | **tenure-limit-gate**(新規、労働者派遣業固有) | HARD | 同一労働者の同一派遣先への継続派遣期間が法定上限を超えたら拒否。日本(労働者派遣法: 原則3年)、ドイツ(AÜG: 18ヶ月)、英国(Agency Workers Regulations 2010: 12週で同等待遇義務発生)の実定法上限を根拠とする。米国は該当する連邦上限が無いため意図的に未収録 |
| 4 | **wage-compliance-gate**(新規、労働者派遣業固有) | HARD | 実効賃金が operator 管理下の賃金フロア(法定基準)を下回ったら拒否。ハードコードされた金額は持たず、常に法的根拠(法域)に紐づく |
| 5 | licensed-disclosure | HARD | 有効な契約(tenant×tier)が無い、または開示列が tier を超えたら拒否 |
| 6 | 確信度フロア | SOFT | `:confidence < 0.6` → escalate |
| 7 | hazardous-duty gate | SOFT | 危険業務への配置は必ず人間承認 |
| 8 | dispute-request | SOFT(無条件) | 労働紛争は確信度に関わらず常に人間レビュー、どの phase でも auto 化しない |

**意図的に無い項目**: 7810(あっせん業)のような単発紹介料モデルの
チェックは存在しない — この actor は継続的な雇用主責任(派遣元が雇用主)
を負うモデルであり、業態の構造的差異を反映している。

### 2. Phase 0→3 + 恒久人間ゲート

`default-phase` はセッション開始時点から保守的な `1`(assisted、auto-commit
無し)に設定 — `cloud-itonami-isic-6311` の兄弟テンプレート
(`talent.phase`/`gftd-talent-actor` 系列)で見つかった「`:phase` を省略した
呼び出し元が黙って最大自律性を得る」fail-open バグの修正を、新規 actor の
初期実装時点から適用した(過去に遡って直す必要のない、最初から正しい設計)。
`dispute-request` はどの phase の `:auto` 集合にも入らない構造的恒久ゲート。

### 3. Robotics premise: false

労働者派遣は書面/システム上のマッチング・契約管理であり、actor の境界の
外に物理的な作動(実際の労働自体)は存在しない。

## Consequences

- (+) `kotoba-lang/industry` registry の 7820 スロットが実装へ昇格
  (`M6910`・`isic-8291`・`isic-4690`・`isic-4610`・`isic-6311` に続く
  6件目)。
- (+) tenure-limit-gate・wage-compliance-gate という、他の cloud-itonami
  actor に存在しない労働者派遣業固有の HARD チェックを新設し、7810(あっせん
  業)との構造的差異(雇用主責任の有無)を反映した。
- (+) `clojure -M:dev:test`: 41 tests / 166 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。`clojure -M:dev:run` デモも
  end-to-end で確認済み(7シナリオ全て正しく発火)。
- (+) `cloud-itonami-isic-6311` で発見・修正された `com-junkawasaki/
  langgraph-clj`→`kotoba-lang/langgraph` の deps.edn パス問題を、本 actor
  では最初から正しいパスで実装した(再発防止)。
- (-) 米国の派遣期間上限は連邦法レベルでは存在しないため、tenure-limit-gate
  の対象法域は日独英の3法域のみ。
- (-) Datomic/kotoba-server backend は次のシーム(未接続)。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ
  (`manifest/west.yml` の `industry` entry、他セッションにより既に
  `8f2fda42ca8b`まで前進済み — GitHub API で isic-7820 の登録commit
  `15b21f2` から8コミット先の子孫であることを確認済み)。
  `cloud-itonami-isic-7820` は既存の `cloud-itonami-{ISIC}` blueprint 群と
  同じ慣例により `manifest/repos.edn` には登録しない(standalone、
  plain-git 子リポ)。

## 代替案と不採用理由

- **7810(あっせん業)のスロットを流用/拡張**: 7810 は一時金モデルで
  あっせん後の雇用関係に関与しない業態であり、雇用主責任を負う7820とは
  法的責任構造が根本的に異なる。同一スロットへの統合は業態の違いを
  隠蔽する。
- **tenure-limit-gate/wage-compliance-gate を SOFT にとどめる**: 派遣期間
  上限超過・最低賃金割れは実定法違反であり、人間承認で事後的に許容できる
  性質のものではない。HARD が必須と判断した。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-7820/README.md` + `docs/business-
  model.md` + `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対)
- `90-docs/adr/2607111500-cloud-itonami-isic-6311-market-data-actor.md`
  (直接の手本、フリート標準パターン)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "7820" エントリ)

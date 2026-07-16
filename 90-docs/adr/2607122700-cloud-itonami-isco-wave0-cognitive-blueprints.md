# ADR-2607122700: ISCO wave-0 認知職 blueprint batch — 4311/2513/3511/2519（:blueprint 19→23）

**Status**: accepted
**Date**: 2026-07-13
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isco-{4311,2513,3511,2519}`（新規）,
`orgs/kotoba-lang/occupation`, `manifest/west.yml`

## Context

30 分 loop「成熟度, coverage を向上」tick 5。procurement lane は parked だが
main 未着地のため ADR-2607122500 の実装ゲートは未成立 — 未踏の ISCO 職業軸へ。
実態: fleet が occupation を 70 `:implemented` / 19 `:blueprint` / 347 `:spec`
まで前進済み（07-12 baseline 23/61/353 から加速）。ISCO **Wave 0（認知基盤、
ADR-2607121000）は LLM 第一波で robotics gate 無し** — 労働解放 SD モデル
（ADR-2607122100）の `:wave2.isco-cognitive`（labor-share 0.08）の実体。

## Decision

Wave 0 の `:spec` 大票田 4 unit groups に blueprint 衛星を発行し registry 昇格:

| ISCO | 職業 | 衛星 | governor |
|---|---|---|---|
| 4311 | 簿記・会計事務員 | cloud-itonami-isco-4311 Community Bookkeeping Service | `:bookkeeping-clerks-governor` |
| 2513 | Web・マルチメディア開発者 | isco-2513 Community Web Studio | `:web-multimedia-governor` |
| 3511 | ICT 運用技術者 | isco-3511 Community ICT Operations | `:ict-operations-governor` |
| 2519 | SW 開発・分析 NEC（QA 含む） | isco-2519 Community Software QA & Analysis | `:applications-nec-governor` |

- 衛星は isco-2411 の慣例に従う tx-data 形 blueprint.edn（`:isco-08` キー）+
  honest README（**wave 0 = 今すぐ actor 実装可能、gate 無し** — blueprint は
  その前段で actor 未実装を明記）。
- registry: 4 entry `:spec → :blueprint`（`:repo`/`:business-id` 追加、
  stringified vector 内を精密置換）。maturity-summary `:blueprint` 19 → 23、
  `:spec` 347 → 343。テスト 14 tests / 890 assertions green、clj-kondo clean。
  main `631625d6`。
- pin 前進: occupation `900cc645 → 631625d6`（API single-entry `7f8dde8f`、
  compare 前進検証済み）。

## Consequences

- (+) 労働解放の第一波（純認知職）の器が 4 本増えた。7810 労働市場 lane で
  市場化される agent 化対象（P2、ADR-2607121000）の在庫。
- (+) ISCO 版 `:blueprint` ladder が batch 運用に乗った（ISIC 側 batch #1/#2 と同型）。
- (−) actor 実装は別作業（wave 0 なので gate は無い — 実装しない理由は帯域のみ）。

## Artifacts

- https://github.com/cloud-itonami/cloud-itonami-isco-4311 /-2513 /-3511 /-2519
- `kotoba-lang/occupation` main `631625d6` / superproject west.yml `7f8dde8f`
- 本 ADR とペアの `.edn`

## References

- ADR-2607121000（ISCO wave 定義）/ ADR-2607012000（curated blueprints 初版）
- ADR-2607122100（isco-cognitive ノード）/ ADR-2607122200・2607122600（ISIC batch 同型）

## Addendum (2026-07-13, batch #2 — loop tick 8)

同型の第 2 batch として **ICT 運用クラスタ 4 本**を追加した:
2514 応用プログラマ（Community Applications Programming ⊣
`:applications-programming-governor`）/ 2521 DB 設計・管理（Community
Database Practice ⊣ `:database-administration-governor`）/ 2522
SysAdmin（Community Systems Administration ⊣
`:systems-administration-governor`）/ 3513 ネットワーク・システム技術者
（Community Network Operations ⊣ `:network-systems-governor`）。
maturity-summary `:blueprint` 23 → **27**、`:spec` 343 → 339。テスト
14/890 green、clj-kondo clean。occupation main `a6dc7092`、pin 前進
`967f4cff`。wave-0 認知職の blueprint 在庫は計 8 本（batch #1+#2）。

## Addendum 2 (2026-07-13, batch #3 — loop tick 10)

**フロントオフィス認知クラスタ 4 本**: 3514 Web 技術者（Community Web
Maintenance ⊣ `:web-technicians-governor`）/ 4226 受付（Community
Reception Desk ⊣ `:reception-governor`）/ 4225 照会係（Community
Inquiry Desk ⊣ `:inquiry-clerks-governor`）/ 4227 調査・市場調査
インタビュアー（Community Survey Practice ⊣
`:survey-interviewing-governor`）。maturity-summary `:blueprint`
27 → **31**、`:spec` 339 → 335。occupation main `fa86a54e`、pin 前進
`7282893d`。wave-0 認知職の blueprint 在庫は計 **12 本**（batch #1-#3）。

## Addendum 3 (2026-07-13, batch #4 — loop tick 12)

2511 システム分析（Community Systems Analysis）/ 2523 ネットワーク専門職
（Community Network Architecture）/ 2432 広報（Community Public
Relations）/ 2434 ICT 営業（Community ICT Sales Practice）。**251x/252x
ICT 専門職 sub-major は全て ≥ :blueprint に到達**。広報・営業の 2 本は
outbound 重心のため README に「external-send は常に human-gated」を明記。
`:blueprint` 31 → **35**、occupation main `8b26173d`、pin `96c74b9b`。
在庫は計 **16 本**（batch #1-#4）。同 tick で 8291 の最終枠に UN 安保理
統合制裁リストを収載（catalog 20/20 満杯 — 以降の追加は facts-test の
guard 再交渉が必要。:un は国に attach しない honest 設計のため
market-entry の regen/deploy は不要）。

## Addendum 4 (2026-07-13, batch #5 — loop tick 14 + pin 退行の検出/修復)

**batch #5（分析 + バックオフィス金融クラスタ）**: 2421 経営分析 /
2413 財務分析（analysis only — 投資助言・執行は明示的にスコープ外）/
4312 統計・金融・保険事務 / 4313 給与計算（実装時は kotoba-lang/labor
の賃金 primitives を消費、再発明しない）。`:blueprint` 35 → **39**、
occupation main `2d17c9c8`。

**pin 退行インシデント**: superproject main の `5c10bbe2`（別セッション
の org-iso-h264 pin 前進、19:59）が stale な west.yml 全文を書き戻し、
**occupation を `631625d6` へ・cloud-itonami を `9ef582e8` へ静かに退行**
させていた（repos.edn :manifest-workflow が警告する wholesale/stale-
snapshot ハザードの実発生）。検出経路: batch #5 の pin 前進時に旧 pin が
想定 (`8b26173d`) でなく `631625d6` だったことから発覚 → 全 4 entry を
監査 → occupation は `9925bb40`、cloud-itonami は `0d9609d0` で
forward-fix、最終検証で **4 entry すべて pin == child main tip** を確認。
教訓: 単一 entry 前進でも、書き戻す全文は必ず**直前に取得した tip
snapshot** から作ること（古い snapshot の再利用は他 entry を巻き戻す）。

## Addendum 5 (2026-07-13, batch #6 — loop tick 15)

新規 4 衛星: 2433 技術・医療営業（Community Technical Sales Practice）/
4419 事務 NEC（Community Clerical Support）/ 4223 電話交換
（Community Switchboard & Call Handling）/ 4416 人事記録
（Community Personnel Records、privacy-first 明記）。加えて **3521
放送・AV 技術者は registry-sync**: blueprint repo は kawaraban
（ADR-2607110200）で既に実在していたが registry が `:spec` のままだった
乖離を修正し実 repo を指すようにした。`:blueprint` 39 → **44**、
occupation main `1f29f9be`、pin `c0ec69b7`（fresh tip snapshot 手順で
実施 — Addendum 4 の教訓を適用）。wave-0 認知職在庫は計 **25 本**
（batch #1-#6、3521 含む）。tick 冒頭の退行再発チェックは両 pin OK。

## Addendum 6 (2026-07-13, batch #7 + wave-0 棚卸し完了 — loop tick 16)

**wave-0 全域棚卸し**: 58 unit groups = implemented 17 / blueprint 25 /
spec 16。安全な 6 件を batch #7 として昇格: 2423 人事・キャリア
（Community Careers Practice — 7810 lane への供給元）/ 2424 研修・能力
開発 / 2529 DB・ネットワーク NEC / 3522 通信技術者（**cognitive scope
のみ** — 物理プラント作業は対象外と README 明記）/ 4221 旅行事務 /
4323 運輸事務。`:blueprint` 44 → **50**、occupation main `c73236e0`、
pin `b1be5058`。

**残り 10 件は全て意図的**（テストコメントにも固定）:
- **2612 裁判官 — 恒久除外相当**（国家司法作用の agent 化 blueprint は
  作らない）。
- **sensitive/規制クラスタ 6 件 — オーナー判断待ち**: 2611/2619 法務
  （2411 会計士の先例はあるが判断が重い）、2412 投資助言（免許制。
  2413 で analysis のみ収載済み）、4212 賭博、4213 貸金、4214 取立て
  （mamori 文脈とも衝突しうる）。
- **小規模 tail 3 件**: 4411 図書館事務 / 4412 郵便配達（物理配達成分
  あり）/ 4414 代書。

これで **wave-0 の安全に進められる在庫は全て ≥ :blueprint** に到達 —
ISCO 軸の loop 作業は当面完了。次の前進は :blueprint → :implemented
（actor 実装）か、sensitive クラスタのオーナー判断。

## Addendum 7 (2026-07-13, 垂直第 1 号 — loop tick 18)

**loop 初の :blueprint → :implemented 昇格**: 4311 簿記事務に
**BookkeepingActor**（Advisor ⊣ BookkeepingClerksGovernor、langgraph
StateGraph、isco-2411 会計 actor の同型）を実装した。fleet 標準の
HARD 不変条件（client 実在・:propose 限定）に加え簿記固有の 2 条件:
**①原始証憑基盤**（登録済み source-doc を引用しない仕訳 draft =
取引の捏造として HARD hold。他 client の証憑流用も HARD）、
**②複式簿記の貸借一致**（不均衡仕訳は人間承認でも通せない —
算術の誤りは approve の対象外）。escalation は :issue-invoice
（external-send）/ :close-period / 低 confidence。
14 tests / 36 assertions green（初回実行で green）。satellite main
`3c6dfedb`、registry `:blueprint` 50 → 49 / `:implemented` 70 → **71**
（occupation main `b2a43b84`、pin `6f61cfd5`）。
procurement lane は tick 冒頭チェックで依然静止・未着地（gate 維持）。

## Addendum 8 (2026-07-13, 垂直第 2 号 = PayrollActor — loop tick 19)

**4313 給与計算を `:implemented` に昇格**。PayrollActor（Advisor ⊣
PayrollGovernor、isco-4311 同型）は **kotoba-lang/labor を消費**する
capability-library-wrapping 型（isic-9700 先例。labor 自身の deps が
sibling path のため git dep 不可 — workspace-sibling 参照の慣例に従い
deps.edn に理由を明記）。給与固有の HARD 不変条件:
**governor が `kotoba.labor/wages-for` で登録済み契約 + timesheet から
賃金を決定的に再計算し、gross/net が一致しない proposal は confidence
0.99 でも HARD hold**（公正な賃金は算術であって意見ではない — LLM の
暗算を信用しない）。雇用の捏造（契約未引用/未登録/他 employer/不正契約）
も HARD。escalation は :disburse-wages（実資金移動 — 常に人間）と低
confidence。monthly 契約は timesheet 無視という labor lib の意味論も
テストで固定。14 tests / 36 assertions green。satellite main
`466d814a`、registry `:blueprint` 49 → 48 / `:implemented` 71 → **72**
（occupation main `252216ac`、pin `fa1a128c`）。

## Addendum 9 (2026-07-13, 垂直第 3 号 = ReceptionActor — loop tick 20)

**4226 受付を `:implemented` に昇格**。ReceptionActor（isco-4311 同型）
の受付固有 HARD 不変条件: **二重予約禁止** — governor が提案 slot を
**確定済みカレンダーに対して決定的に照合**し（隣接境界 end==start は
重複でない、重なりは confidence 0.99 でも hold）、advisor の「空いて
います」を信用しない。start ≥ end の時刻算術エラーも承認不可。
escalation は :send-confirmation（external-send）と低 confidence。
12 tests / 25 assertions green。satellite main `81f1c169`、registry
`:blueprint` 48 → 47 / `:implemented` 72 → **73**（occupation main
`1bfdeb93`、pin `63e86040`）。loop 垂直 3 本の governor 不変条件は
一貫して「**決定的に再計算/照合できる事実は LLM に委ねない**」:
簿記 = 貸借一致、給与 = 賃金再計算、受付 = カレンダー照合。

## Addendum 10 (2026-07-13, 垂直第 4 号 = InquiryDeskActor — loop tick 21)

**4225 照会係を `:implemented` に昇格**。照会固有の HARD 不変条件:
**回答は登録済み KB entry の引用必須**（引用なし = 回答の捏造）+
**鮮度の決定的照合**（`:valid-until < today` の期限切れ知識は
confidence 0.99 でも案内不可 — 直すのは KB であって承認ではない）。
他 client の KB 流用も HARD。escalation は :publish-faq と低
confidence。12 tests / 26 assertions green。satellite main `87e725f7`、
registry `:blueprint` 47 → 46 / `:implemented` 73 → **74**
（occupation main `1e11236b`、pin `61b45895`）。
tick 冒頭の pin チェックで cloud-itonami の通常 staleness を検出し
純前進で追従（`ee8af6b4` — 退行ではない）。決定的 governor 原則の
第 4 例 = **KB 引用実在 + 期限比較**。

## Addendum 11 (2026-07-13, 垂直第 5 号 = TrainingActor — loop tick 22)

**2424 研修・能力開発を `:implemented` に昇格**。研修固有の HARD
不変条件は全て決定的なカリキュラム事実: **①モジュール実在**（未登録
モジュール引用 = カリキュラムの捏造）、**②前提順序 DAG**（前提より
先に上級モジュールを置く計画・前提が計画から欠落している計画は
confidence 0.99 でも hold — グラフ事実は意見ではない）、**③時間合計
一致**（total-hours ≠ モジュール合計 = 水増し禁止）。escalation は
:send-invitations と低 confidence。14 tests / 31 assertions green。
satellite main `c98c6844`、registry `:blueprint` 46 → 45 /
`:implemented` 74 → **75**（occupation main `61812d00`、pin
`afda5d0e`）。決定的 governor 原則 第 5 例 = **DAG 順序 + 合計算術**。

## Addendum 12 (2026-07-13, 垂直第 6 号 = CareersActor — loop tick 23、rate-limit 跨ぎ)

**2423 人事・キャリアを `:implemented` に昇格** — 7810 労働市場 lane
（R4 伝達ループ、ADR-2607122100）に直結する戦略 actor。careers 固有の
HARD 不変条件: **①同意** — 本人の紹介同意が記録されていない candidate
は confidence 0.99 でも紹介不可（「同意の欠如は承認で越えられない —
本人に聞くこと」。尊厳は hard line）、**②実在する人・実在する職**
（未登録 candidate/vacancy = 捏造）、**③スキル集合包含** — vacancy の
required-skills ⊆ 登録 skills を決定的照合（是正は証拠によるスキル
記録更新か研修 isco-2424 — 承認の強行ではない）。escalation は
:send-referral と低 confidence。13 tests / 28 assertions green。

運用上の特記: 実装中に GitHub API rate limit（core 0）に遭遇。tick を
ローカル完結（コード + テスト + local commit + registry 昇格の事前準備）
に切替え、quota watcher で回復を監視して回復後に一括着地した — loop
が外部制約に遭った際の標準対応として記録。satellite main `00f661a9`、
registry `:blueprint` 45 → 44 / `:implemented` 75 → **76**
（occupation main `73babc9e`、pin `932f1321`）。回復後の pin 全数
スイープは 4 entry すべて OK。決定的 governor 原則 第 6 例 =
**同意記録 + 集合包含**。

## Addendum 13 (2026-07-13, 垂直第 7 号 = DatabaseAdministrationActor — loop tick 24)

**2521 DB 設計・管理を `:implemented` に昇格**。DBA 固有の HARD
不変条件: **backup-before-apply の版等値** — migration 適用は「この
database の、現行 schema-version と等しい版の登録済みバックアップ」の
引用が必須。別版のバックアップは別状態のバックアップであり、
「どこかにバックアップはある」は confidence 0.99 でも通らない。
escalation: :apply-migration は有効バックアップがあっても常に人間
sign-off（実システム変更）、**破壊的 step（:drop-table/:drop-column/
:truncate）は step リストの走査で検出**（advisor の自己申告 stake を
信用しない）、低 confidence。13 tests / 28 assertions green。
satellite main `a7f075b8`、registry `:blueprint` 44 → 43 /
`:implemented` 76 → **77**（occupation main `cef8f1aa`、pin
`f848146b`）。決定的 governor 原則 第 7 例 = **版等値 + step 走査**。

## Addendum 14 (2026-07-13, 垂直第 8 号 = SystemsAdministrationActor — loop tick 25)

**2522 SysAdmin を `:implemented` に昇格**。sysadmin 固有の HARD
不変条件は二重の区間算術: **①窓の包含** — 変更区間は承認済み
メンテナンス窓の**内側に完全に収まる**こと（半分入りは外。緊急性は
窓を作らない — 窓を取ること）、**②凍結の非重複** — 登録済み変更凍結
と重ならないこと（凍結を破る緊急事態は、まず凍結記録の変更を記録に
残して行う）。escalation: :apply-change は窓内でも常に人間 sign-off、
:rotate-credentials（機微）、低 confidence。14 tests / 30 assertions
green。satellite main `1eae9840`、registry `:blueprint` 43 → 42 /
`:implemented` 77 → **78**（occupation main `a5bd1787`、pin
`6b9435e3`）。決定的 governor 原則 第 8 例 = **区間包含 + 凍結非重複**。

## Addendum 15 (2026-07-13, 垂直第 9 号 = NetworkSystemsActor + スイープ + wave-1 棚卸し — tick 26)

**3513 ネットワーク技術者を `:implemented` に昇格 — ICT 運用クラスタ
（2521 DB / 2522 SysAdmin / 3513 Network）完結**。ネットワーク固有の
HARD 不変条件: **接続性保存** — link 削除の適用前に governor が登録
トポロジ上の到達性を純 BFS で再計算（計画の追加 link も込みで判定 —
「橋を外しつつ代替路を足す」計画は通る）。切断を生む変更は confidence
0.99 でも hold。「その link は冗長です」という advisor の主張は信用
しない — 冗長路を先に足すこと。12 tests / 25 assertions green。
satellite main `c640cfd4`、registry `:blueprint` 42 → 41 /
`:implemented` 78 → **79**（occupation main `2288d551`、pin
`76891c2d`）。決定的 governor 原則 第 9 例 = **グラフ到達性**。

**定期スイープ**: pin 4/4 OK、ISCO/ISIC drift 0 件（repos リスト再取得
の上で確認）。

**wave-1 棚卸し**（79 unit groups = impl 13 / spec 66、blueprint 0）:
- 除外相当: 軍 01-03（3 件）、1111 立法者 / 1112 政府高官 / 1113
  伝統的首長（国家統治職 — 2612 裁判官と同じ扱い）。1114 利益団体
  役員は保留。
- **安全な補充源は約 60 件**: 12xx 機能管理職（財務 1211・販売 1221・
  広告広報 1222 等）、13xx 生産管理職（農林 1311・建設 1323 等 —
  管理は認知業務、現場は wave-3）、21xx 科学工学専門職 22 件、31xx
  準専門職 23 件。wave-0 の blueprint 在庫（41 本）が細ったら
  ここから batch 補充する。

## Addendum 16 (2026-07-13, 垂直第 10 号 = WebMultimediaActor — tick 27)

**2513 Web・マルチメディア開発者を `:implemented` に昇格**。web-studio
固有の HARD 不変条件: **①ライセンス出所** — 引用素材は全て「ライセンス
記録付きで登録済み」であること（spec-basis 規律の創作物版。ライセンス
不明素材は confidence 0.99 でも使用不可 — 是正はライセンスの確認と
記録）、**②内部リンク集合整合** — draft 内の内部リンクは同一 draft の
ページ集合の要素を指すこと（「見た目は完成している」を信用しない）。
escalation は :publish-site（外部公開）と低 confidence。12 tests /
26 assertions green。satellite main `34594831`、registry `:blueprint`
41 → 40 / `:implemented` 79 → **80**（occupation main `1ea2787b`、
pin `c62bc294`）。決定的 governor 原則 第 10 例 = **ライセンス出所 +
リンク集合**。

## Addendum 17 (2026-07-13, 垂直第 11 号 = ApplicationsProgrammingActor — tick 28)

**2514 応用プログラマを `:implemented` に昇格 — 開発系（2513 Web /
2514 Apps）完結**。プログラミング固有の HARD 不変条件: **①要件基盤**
（登録済みチケット引用必須 — 要件の捏造禁止）、**②commit スコープの
テスト実証** — 変更提出は「この client の、:green で、かつ提出 commit
と同一 commit の」test run 引用が必須。red run・run 欠落・**別 commit
の green** はいずれも confidence 0.99 でも hold — 「昨日は green
だった」（別 commit）は何も証明しない。**証拠の鮮度は同一性であって
新しさではない**。escalation は :deploy-release（実デプロイ）と低
confidence。14 tests / 29 assertions green。satellite main `f183bc93`、
registry `:blueprint` 40 → 39 / `:implemented` 80 → **81**
（occupation main `81e4a918`、pin `e525ed1d`）。決定的 governor 原則
第 11 例 = **commit 同一性による証拠照合**。

## Addendum 18 (2026-07-13, wave-1 初 blueprint batch — tick 29)

**初の wave-1（設計・統制）blueprint batch**: 1211 財務管理（Community
Finance Management）/ 1213 政策・計画管理（Community Policy &
Planning）/ 1221 販売・マーケ管理（Community Sales & Marketing
Management）/ 1222 広告・広報管理（Community Advertising & PR
Management）。管理業務は認知業務で robotics gate 無し — rollout 優先度
として wave-0 基盤の後に続く位置づけを README に明記。財務・対外効果を
持つ管理判断は常に escalated と明記。目的は**垂直ラインの blueprint
在庫補充**（39 → 43。垂直 11 本消化で在庫が細っていた）。
occupation main `4b807205`、pin `211aad8a`。wave-1 の `:blueprint`
はこれが初（従来 impl 13 / spec 66 / bp 0 → bp 4）。

## Addendum 19 (2026-07-13, 垂直第 12 号 = FinanceManagementActor、wave-1 初実装 — tick 30)

**1211 財務管理を `:implemented` に昇格 — wave-1 初の実装 actor**
（blueprint 化から同日中の垂直化）。財務管理固有の HARD 不変条件:
**予算上限は台帳の合計** — governor は毎回、line の残高を「予算額 −
登録済み支出の合計」で再計算し、超過は confidence 0.99 でも hold
（「残高は算術であって記憶ではない。confidence も役職も算術は
越えられない」）。是正は再配分提案を記録に残すこと。actor の commit
node は承認済み支出を spend として登録するため、**承認のたびに上限が
締まる** — 2 連続支出テストで「1 本目の承認後、残高を超える 2 本目が
hold される」ことを実証（state であって memory ではない）。
escalation: :approve-expenditure は予算内でも常に人間（金銭効果）、
:reallocate-budget、低 confidence。14 tests / 36 assertions green。
satellite main `aa96640c`、registry `:blueprint` 43 → 42 /
`:implemented` 81 → **82**（occupation main `10be9cc6`、pin
`2f9328cf`）。決定的 governor 原則 第 12 例 = **台帳合計による上限**。

## Addendum 20 (2026-07-13, 垂直第 13 号 = SalesMarketingManagementActor — tick 31)

**1221 販売・マーケ管理を `:implemented` に昇格**（wave-1 第 2 号）。
HARD 不変条件は登録済み数表の決定的照合: **①値引率の権限上限**
（「権限表は交渉材料ではない。『お客様が大事』は算術を動かさない」）、
**②価格フロア**（「マージン保護は算術であって情ではない」）。
escalation は :publish-campaign と低 confidence。13 tests / 27
assertions green。satellite main `5b928dbe`、registry `:blueprint`
42 → 41 / `:implemented` 82 → **83**（occupation main `d210372b`、
pin `e73547f2`）。tick 冒頭スイープで cloud-itonami の通常 staleness
を純前進（`d819b6da`）。決定的 governor 原則 第 13 例 =
**権限数表照合**。

## Addendum 21 (2026-07-13, 垂直第 14 号 = PolicyPlanningManagementActor — tick 32)

**1213 方針・企画管理を `:implemented` に昇格**（wave-1 第 3 号）。
HARD 不変条件は登録済み集合の決定的照合: **①管轄集合包含**
（proposal の scope は登録済み mandate 集合の要素でなければならない —
「管轄は集合であって気分ではない」）、**②統制点競合検出**（同一統制点
上の現行 policy と異なる directive の採択は矛盾 — 「矛盾は集合照合で
機械的に検出できる。判断ではない」。同一 directive の再確認は矛盾に
しない）。escalation は :repeal-policy（現行方針の撤回）と低
confidence。13 tests / 27 assertions green。satellite main
`01dbdd01`（blueprint 素体に actor 一式を追加）、registry
`:blueprint` 41 → 40 / `:implemented` 83 → **84**（occupation main
`62603e32`、pin `f3b3f4ef`）。決定的 governor 原則 第 14 例 =
**管轄集合包含 + 統制点競合**。

## Addendum 22 (2026-07-13, 垂直第 15 号 = AdvertisingPRManagementActor — tick 33)

**1222 広告・広報管理を `:implemented` に昇格**（wave-1 第 4 号 —
これで wave-1 初回バッチの管理職 4 職 1211/1213/1221/1222 が全て
:implemented）。HARD 不変条件は登録済み集合の決定的照合 2 本:
**①主張根拠の部分集合判定**（広告 copy の全主張は当該 client の登録
済み substantiation 集合の要素でなければならない — 「広告は登録済み
根拠の引用であって創作ではない」。他 client の根拠は流用不可）、
**②禁止表との交差判定**（主張集合と登録済み禁止表の交差は空集合 —
「ブラックリストは算術であってトーンではない」。根拠があっても禁止
なら hold = 規制は証拠に優越する）。escalation は :publish-ad /
:issue-press-release（対外公開）と低 confidence。13 tests / 27
assertions green。satellite main `ff1fd920`、registry `:blueprint`
40 → 39 / `:implemented` 84 → **85**（occupation main `b1a7ec8c`、
pin `d2666bdc`）。決定的 governor 原則 第 15 例 =
**根拠集合包含 + 禁止表交差**。

## Addendum 23 (2026-07-13, tick 34 — ①blueprint 補充 ②2511 垂直 ③スイープの三点同時)

**③スイープ**: main の west.yml に対する pin 鮮度 4/4 ALL OK（kotoba
`1ab3c9d8` / industry `c3999a72` は fleet が前進済みを確認）。drift
スキャンは **ゼロ**（spec-with-repo なし / blueprint-without-repo
なし。org repo 一覧 124 と registry 436 entry の突合）。

**②垂直第 16 号 = SystemsAnalysisActor（2511、wave-0 ICT クラスタ）**:
HARD 不変条件は**被覆関係の全域性** — 登録済み全要件が空でない登録済み
component 集合に写像されなければならない（「未被覆要件は算術的欠落で
あって未解決論点ではない」）。要件の捏造・他 client component の流用
は基底違反。escalation は :approve-cutover と低 confidence。14 tests /
29 assertions green。satellite main `f268ee4e`。決定的 governor 原則
第 16 例 = **被覆全域性**。

**①wave-1 第 2 バッチ（21xx 設計職 5 本）**: 2141 生産技術 / 2142
土木 / 2151 電気 / 2152 電子 / 2153 通信の各 engineer を public AGPL
blueprint satellite として公開し `:spec → :blueprint`。設計・解析は
認知作業、物理実行は robotics ゲートで actor スコープ外と README に
明記。registry は 1 commit で一括: `:blueprint` 39 → **43** / `:spec`
312 → **307** / `:implemented` 85 → **86**（occupation main
`f2da3596`、pin `9d93cd14` — 初回 PUT は並行セッションの west.yml
前進と衝突し 409 → fresh tip 再取得でリトライ成功。楽観ロックが
設計どおり機能）。

## Addendum 24 (2026-07-13, tick 35 — 垂直第 17・18 号 = ICT クラスタ 2 本同時)

**垂直第 17 号 = NetworkProfessionalsActor（2523）**: HARD 不変条件は
**影付け（shadow）検出** — 先行するアクティブ rule の src/dst zone
集合が提案 rule の集合を包含していれば、その提案は死んだ設定
（action が同じなら冗長、異なれば矛盾 — いずれの場合も先行 rule が
常に勝つ）。「dead config は集合包含であって意見ではない」。14
tests / 29 assertions green。satellite main `fb360f22`。

**垂直第 18 号 = SoftwareDevNECActor（2519）**: HARD 不変条件は
**API surface 集合差分に基づく semver 算術** — 登録済み surface から
消えた symbol（差集合）は MAJOR bump 必須、追加のみなら MINOR 以上
必須。「semver は差分から計算される契約であって、リリース時の気分
ではない」。version は登録済みより厳密に前進していなければならない
（再リリース・ロールバックは算術違反）。15 tests / 34 assertions
green。satellite main `880191bf`。

両者とも escalation は破壊的操作（:apply-to-production /
:publish-release）と低 confidence。registry は 1 commit で一括昇格:
`:blueprint` 43 → **41** / `:implemented` 86 → **88**（occupation
main `caaec05a`、pin `4bcec0a4`）。決定的 governor 原則 第 17 例 =
**shadow-rule 包含検出**、第 18 例 = **API 差分 semver 算術**。

作業事故ノート: 本 tick の着地作業中、shell の複合コマンドで `cd` が
非空白引数の誤展開により失敗し、`&&` で連結していない後続行が
（`cd` 失敗後もカレントディレクトリのまま）実行され、superproject
直下で `git add -A` が走って `orgs/cloud-itonami/` 配下の複数
satellite を embedded git repository として警告した。**commit までは
到達しておらず**（HEAD 不変・index に gitlink エントリなし・reflog
に該当なし、を個別に確認済み）実害は無かったが、以後 for ループ内の
`cd`+`git` は `&&` で明示的に連結するか、各行の前に cwd 確認を挟む。

## Addendum 25 (2026-07-13, 垂直第 19 号 = DatabaseNetworkProfessionalsActor — tick 36、wave-0 ICT クラスタ完結)

**2529 データベース・ネットワーク専門職（NEC）を `:implemented` に
昇格。これで wave-0 ICT クラスタ 3 本（2511 Systems Analysts / 2523
Network Professionals / 2529 Database & Network NEC）が全て実装完了**。
HARD 不変条件は **3NF を登録済み候補キーと提案 FD の間の関係として
判定**: 非自明な関数従属性 (FD) は、determinant が superkey（登録済み
候補キーの上位集合）であるか、dependent の全属性が何らかの候補キーの
prime attribute であるかのいずれかでなければ許容されない — 満たさな
ければ推移的従属性として機械検出（「集合帰属であって設計の好みでは
ない」）。12 tests / 24 assertions green。satellite main `bc39fdb6`
（`&&` 連結で着地、事故再発なし）。registry `:blueprint` 41 → 40 /
`:implemented` 88 → **89**（occupation main `4901755`、pin
`9b997d4b`）。決定的 governor 原則 第 19 例 = **3NF 推移的従属性検出**。

## Addendum 26 (2026-07-13, 垂直第 20 号 = StatFinanceInsuranceClerksActor — tick 37)

**tick 冒頭スイープ**: kotoba のみ fleet 前進による staleness を検出
（純前進 ahead 2 / behind 0 確認済み、pin `49f9ac27`）。industry /
occupation / cloud-itonami は OK。

**4312 統計・財務・保険事務員を `:implemented` に昇格**。HARD 不変
条件は **集計恒等式**: 登録済み batch の明細合計が header 合計と
厳密に一致しなければならない（明細が ground truth で、一致しない
header は誤りであって丸めの意見問題ではない）。明細ゼロの batch は
照合不能として HARD。escalation は :post-adjustment（財務効果を伴う
訂正・償却）と低 confidence。13 tests / 28 assertions green。
satellite main `173e6ed5`（`&&` 連結で着地）。registry `:blueprint`
40 → 39 / `:implemented` 89 → **90**（occupation main `46b387bb`、
pin `2a6a6ee2`）。決定的 governor 原則 第 20 例 = **集計恒等式**。

## Addendum 27 (2026-07-13, tick 38 — 6910 国別カバレッジ拡張、垂直バッチではなく coverage 側)

ISCO 垂直生産が続いたため久しく手つかずだった **cloud-itonami-isic-6910
（法人設立要件カタログ）の国別カバレッジを 95 → 101 法域に拡張**。
追加: RUS（連邦税務庁 / EGRUL）、TWN（経済部商業司 / GCIS）、VGB
（BVI 金融サービス委員会 / VIRRGIN）、CYM（ケイマン諸島 General
Registry）、BMU（バミューダ Registrar of Companies）、LIE
（リヒテンシュタイン Amt für Justiz Handelsregister）。各エントリは
`formation.facts` の誠実性規律どおり実在の公式一次情報源を引用（捏造
禁止）。69 tests / 311 assertions green。satellite main `289f49cb`。

8291（コンプライアンス情報源カタログ）は既に `<=20` guard が FULL
（2026-07-13 の最終スロットで UN Security Council Consolidated List
を追加済み、`facts-test` の合意なしに増枠不可）と確認、今 tick は
対象外。6910/8291 とも west-registered ではない standalone satellite
のため pin 前進は不要（west.yml に非掲載を確認済み）。

## Addendum 28 (2026-07-13, 垂直第 21 号 = IndustrialProductionEngineersActor — tick 39)

**tick 冒頭スイープ + drift スキャン**: 4 pin 全て OK、drift ゼロ
（org repo 129 件 vs registry 436 entry を突合）。

**2141 生産技術エンジニアを `:implemented` に昇格**（wave-1 第 5 号）。
HARD 不変条件は製造 QA 領域で新規: **①許容差区間包含**（測定値が
登録済み [LSL, USL] 帯域内か否かの二値判定 — 「部品は帯域の中か外
かのどちらかである」）、**②積み上げ上限**（累積公差の算術和が登録
済み許容スタックを超えないか — 「積み上げ算術は超過か否かの二値」）。
escalation は :approve-deviation（規格外容認）と低 confidence。15
tests / 31 assertions green。satellite main `2b8f88d0`。registry
`:blueprint` 39 → 38 / `:implemented` 90 → **91**（occupation main
`d2c65775`、pin `4bdec1cf`）。決定的 governor 原則 第 21 例 =
**区間包含 + 積み上げ算術**。

## Addendum 29 (2026-07-13, 垂直第 22 号 = CivilEngineersActor — tick 40)

**2142 土木エンジニアを `:implemented` に昇格**（wave-1 第 6 号）。
HARD 不変条件は構造領域で新規: **①荷重・容量マージン**（利用率 =
load / 登録済み allowable-capacity が 1.0 を超えないこと — 算術除算
であって工学的裁量ではない）、**②材料等級membership**（提案等級が
登録済み approved-grades 集合の要素であること — 捏造・未承認材料
禁止）。escalation は :approve-occupancy（使用可否証明の発行）と
低 confidence。13 tests / 27 assertions green。satellite main
`e47b0120`。registry `:blueprint` 38 → 37 / `:implemented` 91 →
**92**（occupation main `49f1befd`、pin `c2f2d286`）。決定的
governor 原則 第 22 例 = **荷重利用率 + 材料等級membership**。

## Addendum 30 (2026-07-13, 垂直第 23 号 = ElectricalEngineersActor — tick 41)

**2151 電気エンジニアを `:implemented` に昇格**（wave-1 第 7 号）。
HARD 不変条件は回路領域で新規: **①電流容量マージン**（提案負荷電流
が登録済み ampacity 定格を超えないこと — 「電流算術は現場での交渉
対象ではない」）、**②電圧クラス整合性**（提案機器の電圧クラスが
登録済み circuit クラスと等値であること — 不一致は代替不可）。
escalation は :energize-circuit（活線投入）と低 confidence。13
tests / 27 assertions green。satellite main `622d4228`。registry
`:blueprint` 37 → 36 / `:implemented` 92 → **93**（occupation main
`e388ec7f`、pin `a7e7726e`）。決定的 governor 原則 第 23 例 =
**電流容量マージン + 電圧クラス整合性**。

## Addendum 31 (2026-07-13, 垂直第 24 号 = ElectronicsEngineersActor — tick 42)

**2152 電子エンジニアを `:implemented` に昇格**（wave-1 第 8 号）。
HARD 不変条件は BOM 領域で新規: **①電力予算算術**（提案 BOM の消費
電力合計が登録済み board の power-budget を超えないこと — 算術和
判定）、**②承認ベンダーmembership**（各部品のベンダーが登録済み
approved-vendors 集合の要素であること — 「サプライチェーン追跡は
集合membershipであって購買の好みではない」）。escalation は
:approve-production（量産リリース）と低 confidence。13 tests / 27
assertions green。satellite main `f2c28053`。registry `:blueprint`
36 → 35 / `:implemented` 93 → **94**（occupation main `4047412e`、
pin `7d96ca97`）。決定的 governor 原則 第 24 例 = **電力予算算術 +
承認ベンダーmembership**。wave-1 バッチ2（21xx 設計職 5 本）は残り
2153 通信のみで完結見込み。

## Addendum 32 (2026-07-13, 垂直第 25 号 = TelecommunicationsEngineersActor — tick 43、wave-1 バッチ2 完結)

**2153 通信エンジニアを `:implemented` に昇格。これで wave-1 バッチ2
（21xx 設計職 5 本: 2141/2142/2151/2152/2153）が全て実装完了**。HARD
不変条件は RF リンク領域で新規: **①リンクマージン下限**（提案リンク
マージン(dB) が登録済み最低要求以上であること — 算術比較であって
見積もりではない）、**②スペクトラム区間包含**（提案搬送波周波数が
登録済み割当帯域内であること — 「スペクトラム割当は免許境界であって
提案ではない」）。escalation は :activate-link（RF送信活性化）と低
confidence。14 tests / 30 assertions green。satellite main
`2660f6c1`。registry `:blueprint` 35 → 34 / `:implemented` 94 →
**95**（occupation main `05cbcaea`、pin `de5866f4`）。決定的
governor 原則 第 25 例 = **リンクマージン下限 + スペクトラム区間
包含**。

## Addendum 33 (2026-07-13, tick 44 — スイープ+drift(異常なし) + wave-1 バッチ3 blueprint 補充)

**スイープ/drift**: 4 pin 全て OK、drift ゼロ（org repo 129件 vs
registry 436件を突合）。

**wave-1 第 3 バッチ（管理職・専門職 5 本）**: 1323 建設管理 / 1324
供給・流通管理 / 1342 医療サービス管理 / 1345 教育管理 / 2120 数学者・
保険数理士・統計学者 を public AGPL blueprint satellite として公開し
`:spec → :blueprint`。registry は 1 commit で一括: `:blueprint` 34 →
**39** / `:spec` 307 → **302**（occupation main `8c92eba6`、pin
`7733e740`）。垂直化は次 tick 以降。

## Addendum 34 (2026-07-13, 垂直第 26 号 = ConstructionManagersActor — tick 45)

**1323 建設管理を `:implemented` に昇格**（wave-1 バッチ3 第1号）。
HARD 不変条件は建設現場領域で新規: **①許可窓区間包含**（提案 as-of
day が登録済み permit-issued/expiry 窓の内側であること）、**②検査
完了集合の網羅性**（提案 passed-inspections 集合が登録済み
required-inspections 集合の上位集合であること — 「検査完了は集合
包含であって部分点はない」）。escalation は
:issue-occupancy-certificate（最終使用許可サインオフ）と低
confidence。15 tests / 31 assertions green。satellite main
`18f13d96`。registry `:blueprint` 39 → 38 / `:implemented` 95 →
**96**（occupation main `a3c7c6e2`、pin `1984d1bf`）。決定的
governor 原則 第 26 例 = **許可窓区間包含 + 検査完了網羅性**。

## Addendum 35 (2026-07-13, 垂直第 27 号 = SupplyDistributionManagersActor — tick 46)

**1324 供給・流通管理を `:implemented` に昇格**（wave-1 バッチ3
第2号）。HARD 不変条件は物流領域で新規: **①在庫算術**（提案引当数量
が登録済み on-hand 在庫を超えないこと — 「存在しない在庫は引当でき
ない」）、**②キャリアmembership**（提案キャリアが登録済み
approved-carriers 集合の要素であること — 「キャリア承認は追跡性で
あって配送の好みではない」）。escalation は
:approve-cross-border-shipment（税関・規制曝露）と低 confidence。13
tests / 27 assertions green。satellite main `23a706f8`。registry
`:blueprint` 38 → 37 / `:implemented` 96 → **97**（occupation main
`51da4eee`、pin `97c92c5b`）。決定的 governor 原則 第 27 例 =
**在庫算術 + キャリアmembership**。

## Addendum 36 (2026-07-13, 垂直第 28 号 = HealthServicesManagersActor — tick 47)

**1342 医療サービス管理を `:implemented` に昇格**（wave-1 バッチ3
第3号）。HARD 不変条件は医療現場領域で新規: **①人員配置比率上限**
（patient-count / staff-count が登録済み max-patients-per-staff
上限を超えないこと — 「患者安全算術は判断ではない」）、**②免許有効
期間区間包含**（提案 as-of day が登録済み運営免許窓の内側であること
— 「免許有効性は交渉不可」）。escalation は
:approve-emergency-surge（一時的比率緩和要求）と低 confidence。14
tests / 30 assertions green。satellite main `eebc5bef`。registry
`:blueprint` 37 → 36 / `:implemented` 97 → **98**（occupation main
`1217e3e4`、pin `939431ad`）。決定的 governor 原則 第 28 例 =
**人員配置比率上限 + 免許有効期間区間包含**。

## Addendum 37 (2026-07-13, 垂直第 29 号 = EducationManagersActor — tick 48、wave-1 バッチ3 完結)

**1345 教育管理を `:implemented` に昇格。これで wave-1 バッチ3
（管理職 4 本: 1323/1324/1342/1345）が全て実装完了**（2120 数学者・
統計学者は blueprint のまま残る）。HARD 不変条件は学務領域で新規:
**①単位数算術**（提案 completed-credits が登録済み required-credits
以上であること — 部分点はない）、**②認定有効期間区間包含**
（提案 as-of day が登録済み accreditation window の内側であること
— 「失効した認定証は無効」）。escalation は
:approve-credit-waiver（通常算術外の単位付与）と低 confidence。14
tests / 30 assertions green。satellite main `8bab71d3`。registry
`:blueprint` 36 → 35 / `:implemented` 98 → **99**（occupation main
`16cba699`、pin `a5522bfb`）。決定的 governor 原則 第 29 例 =
**単位数算術 + 認定有効期間区間包含**。

## Addendum 38 (2026-07-13, 垂直第 30 号 = QuantitativeProfessionalsActor — tick 49、wave-1 バッチ3 全 5 本完結)

**2120 数学者・保険数理士・統計学者を `:implemented` に昇格。これで
wave-1 バッチ3（1323/1324/1342/1345/2120）が全 5 本実装完了**。HARD
不変条件は統計解析領域で新規: **①有意性算術**（:significant と主張
する finding は p値が登録済み有意水準（alpha）以下でなければならない
— 算術比較であって判断ではない）、**②手法membership**（提案手法が
登録済み approved-methods 集合の要素であること — 事後の手法差替は
事前開示を要する。governor は内容から p-hacking を検出できないが、
未登録手法は拒否することで事前開示を強制する）。escalation は
:publish-finding（対外公開）と低 confidence。14 tests / 28
assertions green。satellite main `52f99b8e`。registry `:blueprint`
35 → 34 / `:implemented` 99 → **100**（occupation main `13993c10`、
pin `2765cdc7`）。決定的 governor 原則 第 30 例 = **有意性算術 +
手法membership**。累計 impl **100** 到達。

## Addendum 39 (2026-07-13, tick 50 — スイープ(kotoba純前進) + drift(異常なし) + 垂直第 31 号 = FinancialAnalystsActor)

**スイープ**: kotoba pin が fleet 前進で staleness（純前進 ahead
3/behind 0 確認済み、pin `b4ae8642`）。他 3 リポは OK。drift スキャン
はゼロ（org repo 134 件 vs registry 436 件を突合）。

**垂直第 31 号（wave-0 専門職クラスタ）= FinancialAnalystsActor
（2413）**: HARD 不変条件は証券アナリスト領域で新規: **①レーティング
妥当性**（提案レーティングが登録済み valid-ratings スケールの要素
であること — レーティングの捏造禁止）、**②利益相反開示網羅性**
（提案 disclosed-conflicts 集合が登録済み known-conflicts 集合の
上位集合であること — 「未開示利益相反は集合差で機械検出できる」）。
escalation は :publish-rating（対外公開）と低 confidence。13 tests /
27 assertions green。satellite main `35aea1c4`。registry
`:blueprint` 34 → 33 / `:implemented` 100 → **101**（occupation main
`22dafccd`、pin `c853e2a2`）。決定的 governor 原則 第 31 例 =
**レーティング妥当性 + 利益相反開示網羅性**。

## Addendum 40 (2026-07-13, 垂直第 32 号 = ManagementOrganizationAnalystsActor — tick 51)

**垂直第 32 号（wave-0 専門職クラスタ）= ManagementOrganizationAnalystsActor
（2421）**: HARD 不変条件はコンサルティング領域で新規: **①引用指標
membership**（提言が引用する全指標が登録済み baseline-metrics 集合
の要素であること — 「証拠の捏造禁止」）、**②削減率主張上限**
（提案 claimed-savings-pct が登録済み上限を超えないこと — 「削減率
算術は営業判断ではない」）。escalation は :publish-recommendation
（対外提言配信）と低 confidence。13 tests / 27 assertions green。
satellite main `d4134bdf`。registry `:blueprint` 33 → 32 /
`:implemented` 101 → **102**（occupation main `3dfba81a`、pin
`2ce540f4`）。決定的 governor 原則 第 32 例 = **引用指標membership +
削減率主張上限**。

## Addendum 41 (2026-07-13, 垂直第 33 号 = PublicRelationsProfessionalsActor — tick 52)

**垂直第 33 号（wave-0 専門職クラスタ）= PublicRelationsProfessionalsActor
（2432）**: HARD 不変条件は広報リリース領域で新規: **①エンバーゴ
解禁日下限**（提案 as-of day が登録済み embargo-lift-day 以上である
こと — 「エンバーゴは登録済み日付であって提案ではない」）、**②発言者
引用membership**（引用する全発言者が登録済み approved-spokespersons
集合の要素であること — 「引用は追跡性であって物語上の裁量ではない」）。
escalation は :publish-release（対外公開）と低 confidence。13 tests /
27 assertions green。satellite main `bc7da5bc`。registry
`:blueprint` 32 → 31 / `:implemented` 102 → **103**（occupation main
`8b2cc2f8`、pin `90725383`）。決定的 governor 原則 第 33 例 =
**エンバーゴ解禁日下限 + 発言者引用membership**。

## Addendum 42 (2026-07-13, 垂直第 34 号 = TechnicalMedicalSalesActor — tick 53)

**垂直第 34 号（wave-0 専門職クラスタ）= TechnicalMedicalSalesActor
（2433）**: HARD 不変条件は医療機器・医薬品販売領域で新規: **①適応症
部分集合検証**（提案 claimed-indications 集合が登録済み
approved-indications 集合の部分集合であること — 「オフラベル宣伝は
部分集合違反であって販売技術ではない」）、**②制限品目の免許保有者
membership**（product が registered restricted の場合のみ、buyer が
登録済み licensed-buyers 集合の要素であることを要求 — 非制限品目には
このゲートは適用されない条件付き検証）。escalation は
:approve-bulk-order（大量注文・横流しリスク上昇）と低 confidence。13
tests / 27 assertions green。satellite main `4c6d0843`。registry
`:blueprint` 31 → 30 / `:implemented` 103 → **104**（occupation main
`e0fce324`、pin `b94778ae`）。決定的 governor 原則 第 34 例 =
**適応症部分集合検証 + 制限品目免許保有者membership**。

## Addendum 43 (2026-07-13, 垂直第 35 号 = ICTSalesActor — tick 54、wave-0 専門販売クラスタ完結)

**垂直第 35 号 = ICTSalesActor（2434）。これで wave-0 専門販売クラスタ
（2432 広報 / 2433 技術医療販売 / 2434 ICT販売）が全て実装完了**。HARD
不変条件はライセンス見積領域で新規: **①席数上限算術**（提案席数が
登録済み bundle の max-seats を超えないこと — 「席数算術は交渉材料
ではない」）、**②コンポーネント互換性membership**（提案コンポーネント
全てが登録済み compatible-components 集合の要素であること —
「互換性はマトリクス照合であって販売トークではない」）。escalation は
:approve-enterprise-quote（大口案件確約）と低 confidence。13 tests /
27 assertions green。satellite main `f47b2a27`（テストヘルパー関数名
に Clojure 特殊形式 `quote` を誤って使用し `Wrong number of args`
エラーが発生 → `mkquote` にリネームして解決。以後テストヘルパー命名
で予約語衝突に留意）。registry `:blueprint` 30 → 29 / `:implemented`
104 → **105**（occupation main `c8e2712b`、pin `4463eb75`）。決定的
governor 原則 第 35 例 = **席数上限算術 + コンポーネント互換性
membership**。

## Addendum 44 (2026-07-13, tick 55 — スイープ+drift(異常なし) + 垂直第 36 号 = ICTOperationsTechniciansActor)

**スイープ/drift**: 4 pin 全て OK、drift ゼロ（org repo 134件 vs
registry 436件を突合）。

**垂直第 36 号（wave-0 ICT運用クラスタ）= ICTOperationsTechniciansActor
（3511）**: HARD 不変条件はインシデント対応領域で新規: **①SLA算術**
（提案対応時間が登録済み SLA 上限を超えないこと — 「SLA は算術で
あってベストエフォートではない」）、**②資格網羅性**（対応技術者の
保有資格集合が登録済み required-certifications 集合の上位集合である
こと — 部分充足での対応は許可されない）。escalation は
:approve-emergency-override（通常変更管理のバイパス）と低
confidence。13 tests / 27 assertions green。satellite main
`805ffdf1`。registry `:blueprint` 29 → 28 / `:implemented` 105 →
**106**（occupation main `d8684370`、pin `4f178126`）。決定的
governor 原則 第 36 例 = **SLA算術 + 資格網羅性**。

## Addendum 45 (2026-07-13, 垂直第 37 号 = WebTechniciansActor — tick 56)

**垂直第 37 号（wave-0 ICT クラスタ）= WebTechniciansActor（3514）**:
HARD 不変条件はデプロイ領域で新規: **①適合レベル下限（順序尺度）**
（提案 achieved-conformance-level が登録済み下限に対し順序上 A < AA
< AAA の関係で以上であること — 「適合は順序尺度であって願望では
ない」）、**②ドメインmembership**（デプロイ先ドメインが登録済み
approved-domains 集合の要素であること — 未承認デプロイ先の禁止）。
escalation は :approve-production-cutover（DNS/本番トラフィック切替）
と低 confidence。13 tests / 27 assertions green。satellite main
`b39d2719`。registry `:blueprint` 28 → 27 / `:implemented` 106 →
**107**（occupation main `e8985343`、pin `5cb4d34f`）。決定的
governor 原則 第 37 例 = **適合レベル順序下限 + ドメインmembership**。

## Addendum 46 (2026-07-13, 垂直第 38 号 = TelecomEngineeringTechniciansActor — tick 57)

**方向転換の記録**: 当初 3521 放送・AV技術者を対象にしたが、既存
README/blueprint.edn に kawaraban 連携・Media Advisor/Governor・
`:media-pipeline` capability を含む詳細な独自設計が既に存在しており、
汎用 sales 型テンプレートを当てはめると設計意図と衝突すると判断、
着手前に撤回（未コミットの scaffold ファイルを削除、branch は
main と同一コミットのため削除のみで実害なし）。3522 通信エンジニア
リング技術者（汎用 blueprint テンプレートのみで独自設計無しを確認
済み）に切り替えた。

**垂直第 38 号（wave-0 ICT クラスタ）= TelecomEngineeringTechniciansActor
（3522）**: HARD 不変条件は回線試験領域で新規: **①減衰量上限算術**
（測定減衰量が登録済み上限を超えないこと — 「減衰量は測定であって
目視ではない」）、**②校正有効期間**（試験の as-of day が登録済み
校正有効期限を超えないこと — 「失効校正機器の測定結果は証拠ではなく
推測」）。escalation は :approve-service-restoral（復旧後の本番回線
復帰）と低 confidence。13 tests / 28 assertions green。satellite
main `c9257dea`。registry `:blueprint` 27 → 26 / `:implemented` 107
→ **108**（occupation main `fc0fab3c`、pin `aa129544`）。決定的
governor 原則 第 38 例 = **減衰量上限算術 + 校正有効期間**。

## Addendum 47 (2026-07-13, 垂直第 39 号 = TravelConsultantsActor — tick 58)

**垂直第 39 号（wave-0 事務クラスタ）= TravelConsultantsActor（4221）**:
HARD 不変条件は旅行予約領域で新規: **①在庫算術**（提案予約数量が
登録済み available-units を超えないこと — 「存在しない在庫は予約
できない」）、**②返金資格下限算術**（提案 days-before-departure が
登録済み refund-cutoff-days 以上であること — 「返金資格は日数の算術
であって温情の電話ではない」）。escalation は
:approve-group-booking（大口団体予約確約）と低 confidence。14
tests / 28 assertions green。satellite main `44df5347`。registry
`:blueprint` 26 → 25 / `:implemented` 108 → **109**（occupation main
`93b86f76`、pin `76c6e4de`）。決定的 governor 原則 第 39 例 =
**在庫算術 + 返金資格下限算術**。

## Addendum 48 (2026-07-13, 垂直第 40 号 = TelephoneSwitchboardOperatorsActor — tick 59)

**垂直第 40 号（wave-0 事務クラスタ）= TelephoneSwitchboardOperatorsActor
（4223）**: HARD 不変条件は電話交換領域で新規: **①内線宛先
membership**（着信ルーティングの宛先内線が登録済み extensions 集合の
要素であること — 誤配線禁止）、**②着信拒否リスト除外**（発信番号が
登録済み do-not-call 集合の要素であってはならない — 「DNC 除外は
オペレータ裁量ではない」）。escalation は
:approve-emergency-override（緊急時の通常ルーティングバイパス）と
低 confidence。13 tests / 27 assertions green。satellite main
`c7bb59c2`。registry `:blueprint` 25 → 24 / `:implemented` 109 →
**110**（occupation main `8f1295ef`、pin `4d5c06a1`）。決定的
governor 原則 第 40 例 = **内線宛先membership + 着信拒否リスト除外**。
累計 impl **110** 到達。

## Addendum 49 (2026-07-13, tick 60 — スイープ(kotoba純前進) + 垂直第 41 号 = MarketResearchInterviewersActor)

**スイープ**: kotoba pin が fleet 前進による staleness（純前進 ahead
2/behind 0 確認済み、pin `a90cc751`）。他 3 リポは OK。drift スキャン
はゼロ（org repo 134件 vs registry 436件を突合）。残 blueprint 在庫
24本を確認。

**垂直第 41 号（wave-0 事務/フィールドワーククラスタ）=
MarketResearchInterviewersActor（4227）**: HARD 不変条件は調査領域で
新規: **①セグメント基底 + クォータ上限**（回答は登録済みセグメント
を引用し、当該セグメントの累積回答数が登録済みクォータを超えない
こと — 「クォータは数値であって目安ではない」）、**②同意ゲート**
（study が同意必須を登録している場合、同意未取得の回答記録は拒否 —
「同意なき記録はデータではなく違反」）。escalation は
:approve-quota-reopening（閉鎖済みクォータの再開）と低 confidence。
15 tests / 30 assertions green。satellite main `da73cb69`。registry
`:blueprint` 24 → 23 / `:implemented` 110 → **111**（occupation main
`353a458b`、pin `47cf4075`）。決定的 governor 原則 第 41 例 =
**セグメント基底+クォータ上限 + 同意ゲート**。

## Addendum 50 (2026-07-13, 垂直第 42 号 = TransportClerksActor — tick 61)

**垂直第 42 号（wave-0 事務クラスタ）= TransportClerksActor（4323）**:
HARD 不変条件は運送マニフェスト領域で新規: **①積載重量上限算術**
（提案マニフェストの総重量が登録済み最大積載量を超えないこと —
「積載量は物理であって楽観ではない」）、**②危険物分類部分集合検証**
（提案する全危険物分類が登録済み approved-hazmat-classes 集合の
要素であること — この車両への未承認危険物分類は許可されない）。
escalation は :approve-overweight-permit（特別許可申請）と低
confidence。13 tests / 27 assertions green。satellite main
`efeeaae9`。registry `:blueprint` 23 → 22 / `:implemented` 111 →
**112**（occupation main `77a63904`、pin `9d7f2f01`）。決定的
governor 原則 第 42 例 = **積載重量上限算術 + 危険物分類部分集合**。

## Addendum 51 (2026-07-13, 垂直第 43 号 = PersonnelClerksActor — tick 62)

**垂直第 43 号（wave-0 事務/HRクラスタ）= PersonnelClerksActor（4416）**:
HARD 不変条件は入社処理領域で新規: **①人事記録完全性**（提案
submitted-fields 集合が登録済み required-fields 集合の上位集合で
あること — 「不完全な人事ファイルは入社処理できない」）、**②身元
調査クリアランスゲート**（position が身元調査必須を登録している
場合、クリア未確認の入社は拒否 — 「クリアランス無しの入社は事務
処理の速さではなく方針違反」）。escalation は
:approve-provisional-start（全チェック未完了下での例外的着任）と
低 confidence。14 tests / 28 assertions green。satellite main
`7f612b1e`。registry `:blueprint` 22 → 21 / `:implemented` 112 →
**113**（occupation main `b680eb64`、pin `28502d64`）。決定的
governor 原則 第 43 例 = **人事記録完全性 + 身元調査クリアランス
ゲート**。

## Addendum 52 (2026-07-13, 垂直第 44 号 = ClericalSupportWorkersActor — tick 63、wave-0 事務クラスタ連走完結)

**垂直第 44 号 = ClericalSupportWorkersActor（4419）。これで wave-0
事務クラスタの連走（4221/4223/4227/4323/4416/4419）が完結**。HARD
不変条件は記録管理領域で新規: **①保存期限下限（区間）**（提案廃棄
の as-of day が登録済み retention-expiry-day 以上であること —
「記録は時計が許すまで存続する。都合ではない」）、**②クリアランス
順序尺度**（提案アクセスの要求者クリアランスが登録済み要求水準に
対し順序上 :public < :internal < :confidential の関係で以上である
こと — 「アクセス制御は順序尺度であって裁量ではない」）。escalation
は :approve-early-destruction（法的保持のオーバーライド要求）と低
confidence。15 tests / 29 assertions green。satellite main
`7c54a5d8`。registry `:blueprint` 21 → 20 / `:implemented` 113 →
**114**（occupation main `dc5cd66b`、pin `94197bc5`）。決定的
governor 原則 第 44 例 = **保存期限下限 + クリアランス順序尺度**。

## Addendum 53 (2026-07-14, 垂直第 45 号 = PoultryProductionActor — tick 64、初の robotics-premise 垂直実装)

**方針転換の発見**: tick 63 完結時点で残る blueprint 在庫 20 本を
点検したところ、全て 3521 と同型 — README/business-model.md に
「ロボットが物理作業を実行し、governor がそれをゲートする」詳細な
独自契約（Advisor → Governor の固有命名込み）が既に記述済みと判明
（6122/6123/6221/6222/71xx-93xx 全て "Robotics premise" +
"Advisor -> Governor" セクションを保有）。3521 のときは撤回した
（汎用テンプレートでは設計と衝突するため）が、今回はその記述済み
契約を忠実に実装する方針に転換 — これらは空の blueprint ではなく
「まだ実装されていない具体的契約」である。

**垂直第 45 号 = PoultryProductionActor（6122、README 記載名を
そのまま採用）**: HARD 不変条件は「提案給餌量 / 登録済み羽数」が
登録済み 1羽あたり上限を超えないこと（「過給餌は算術であって
動物福祉の判断ではない」）。README の Trust Controls どおり
`:administer-medication`／`:approve-outbreak-containment` は
**confidence に関わらず常時 escalate**（governor はハードウェアを
一切直接起動しない）。13 tests / 27 assertions green。satellite
main `3450ccfa`。registry `:blueprint` 20 → 19 / `:implemented`
114 → **115**（occupation main `d9e5ba53`、pin `c5b3a995`）。
決定的 governor 原則 第 45 例 = **1羽あたり給餌上限算術 + 常時
escalate（医療・アウトブレイク）**。

## Addendum 54 (2026-07-14, 垂直第 46 号 = ApiaryOperationsActor — tick 65)

**垂直第 46 号 = ApiaryOperationsActor（6123、README 記載名採用）**:
HARD 不変条件は採蜜領域で新規: **持続可能採蜜量上限算術**（提案採蜜量
が登録済み持続可能上限を超えないこと — 「コロニーの持続可能量超過は
算術であって判断ではない」）。README の Trust Controls どおり
`:administer-hive-treatment`／`:approve-defensive-colony-operation` は
confidence に関わらず**常時 escalate**。13 tests / 27 assertions
green。satellite main `8edf14fa`。registry `:blueprint` 19 → 18 /
`:implemented` 115 → **116**（occupation main `ae49f4bc`、pin
`a3bcf6b9`）。決定的 governor 原則 第 46 例 = **持続可能採蜜量上限
算術 + 常時escalate（治療・防衛コロニー）**。

## Addendum 55 (2026-07-14, 垂直第 47 号 = AquacultureOperationsActor — tick 66)

**垂直第 47 号 = AquacultureOperationsActor（6221、README 記載名
採用）**: HARD 不変条件は給餌領域で新規2本: **①1尾あたり給餌上限
算術**（提案給餌量/登録済み尾数が上限を超えないこと）、**②溶存酸素
下限**（測定溶存酸素が登録済み下限以上でなければ給餌不可 — 「酸素
欠乏水域への給餌はへい死を加速させる。水質化学であって判断ではない」）。
README の Trust Controls どおり `:approve-chemical-treatment`／
`:approve-deep-water-operation` は confidence に関わらず常時
escalate。14 tests / 29 assertions green。satellite main
`d814cc97`。registry `:blueprint` 18 → 17 / `:implemented` 116 →
**117**（occupation main `4f7c6ee0`、pin `f47f9cca`）。決定的
governor 原則 第 47 例 = **1尾あたり給餌上限算術 + 溶存酸素下限 +
常時escalate（化学処理・深水域）**。

## Addendum 56 (2026-07-14, 垂直第 48 号 = FisheryOperationsActor — tick 67、robotics-premise 農林水産クラスタ完結)

**垂直第 48 号 = FisheryOperationsActor（6222、README 記載名
採用）。これで robotics-premise 農林水産クラスタ（6122/6123/6221/
6222）が完結**。HARD 不変条件は漁獲領域で新規2本: **①クォータ
上限算術**（提案漁獲量が登録済み残余クォータを超えないこと —
「クォータは法的割当であって目標接近値ではない」）、**②保護種区域
除外**（提案漁獲海域が登録済み protected-species-zones 集合の要素
であってはならない）。README の Trust Controls どおり
`:approve-vessel-operation`／`:report-bycatch-incident` は
confidence に関わらず常時 escalate（混獲インシデントは決して隠蔽
できない）。14 tests / 29 assertions green。satellite main
`9b9fb11b`。registry `:blueprint` 17 → 16 / `:implemented` 117 →
**118**（occupation main `217f1e1d`、pin `b1538f35`）。決定的
governor 原則 第 48 例 = **クォータ上限算術 + 保護種区域除外 +
常時escalate（船舶操作・混獲）**。

## Addendum 57 (2026-07-14, 垂直第 49 号 = CarpentryPracticeActor — tick 68、robotics-premise 製造・建設クラスタ開始)

**垂直第 49 号 = CarpentryPracticeActor（7115、README 記載名採用）**:
HARD 不変条件は材料切断領域で新規2本: **①材料基底**（提案材料が
job の登録済み material-stock マップのキーであること — 材料の捏造
禁止）、**②在庫上限算術**（提案数量が当該材料の登録済み在庫を超え
ないこと — 「存在しない材料は切断できない」）。README の Trust
Controls どおり `:approve-powered-saw-operation`／
`:approve-height-work` は confidence に関わらず常時 escalate。14
tests / 29 assertions green。satellite main `574ad868`。registry
`:blueprint` 16 → 15 / `:implemented` 118 → **119**（occupation
main `ab5da54d`、pin `def5d365`）。決定的 governor 原則 第 49 例 =
**材料基底 + 在庫上限算術 + 常時escalate（動力鋸・高所作業）**。

## Addendum 58 (2026-07-14, 垂直第 50 号 = WeldingPracticeActor — tick 69、累計50本目マイルストーン)

**垂直第 50 号 = WeldingPracticeActor（7212、README 記載名採用）。
このループが生産した垂直 actor が累計 50 本に到達**。HARD 不変条件
は溶接検査領域で新規2本: **①欠陥長受入上限**（測定欠陥長が登録済み
受入上限を超えないこと — 「溶接欠陥の受入は規格照合であって目視では
ない」）、**②接合部ずれ上限**（測定接合部ずれが登録済み上限を超え
ないこと）。README の Trust Controls どおり
`:approve-arc-welding-operation`／`:approve-flammable-proximity` は
confidence に関わらず常時 escalate。14 tests / 29 assertions
green。satellite main `eab53f3d`。registry `:blueprint` 15 → 14 /
`:implemented` 119 → **120**（occupation main `e7e03114`、pin
`cb222db8`）。決定的 governor 原則 第 50 例 = **欠陥長受入上限 +
接合部ずれ上限 + 常時escalate（アーク溶接・可燃物近接）**。

累計サマリ（tick 69 時点）: ISCO `:implemented` **120**（開始時
70）、`:blueprint` 14（開始時 19）、垂直 actor 50 本（ループ産、開始時
0）、incorporation 101/194 か国 (52.1%)、compliance 17か国/20ソース
（満杯）。決定的 governor 原則は **50 種類**確立。

## Addendum 59 (2026-07-14, 垂直第 51 号 = MachineryRepairPracticeActor — tick 70)

**垂直第 51 号 = MachineryRepairPracticeActor（7233、README 記載名
採用）**: HARD 不変条件は修理領域で新規2本: **①承認部品membership**
（提案交換部品が登録済み approved-parts 集合の要素であること —
模造・未承認部品の使用禁止）、**②試験偏差上限**（提案の修理後試験
偏差が登録済み上限を超えないこと）。README の Trust Controls どおり
`:approve-lift-operation`／`:approve-hydraulic-pressure-work` は
confidence に関わらず常時 escalate。14 tests / 29 assertions
green。satellite main `5f186c9e`。registry `:blueprint` 14 → 13 /
`:implemented` 120 → **121**（occupation main `a7f043d8`、pin
`bcf005ac`）。決定的 governor 原則 第 51 例 = **承認部品membership +
試験偏差上限 + 常時escalate（リフト操作・油圧加圧作業）**。

## Addendum 60 (2026-07-14, 垂直第 52 号 = HandicraftPracticeActor — tick 71)

**垂直第 52 号 = HandicraftPracticeActor（7318、README 記載名
採用）**: HARD 不変条件は工芸・納品領域で新規2本: **①材料基底 + 在庫
上限算術**（工程材料が登録済み在庫キーであり、数量が在庫を超えない
こと — carpentry と同型だが独立ドメイン適用）、**②仕様網羅性**
（納品の delivered-items 集合が登録済み required-spec-items 集合の
上位集合であること — 「部分納品は納品ではない」）。README の Trust
Controls どおり `:approve-sharp-equipment-operation`／
`:approve-chemical-treatment` は confidence に関わらず常時
escalate。17 tests / 33 assertions green。satellite main
`18a32d07`。registry `:blueprint` 13 → 12 / `:implemented` 121 →
**122**（occupation main `d96fd720`、pin `0ef57874`）。決定的
governor 原則 第 52 例 = **材料基底+在庫上限 + 仕様網羅性 +
常時escalate（鋭利機材・化学処理）**。

## Addendum 61 (2026-07-14, 垂直第 53 号 = WoodworkingMachineActor — tick 72)

**垂直第 53 号 = WoodworkingMachineActor（7523、README 記載名
採用）**: HARD 不変条件は切断・生産領域で新規2本: **①寸法公差区間
包含**（提案切断の測定寸法が登録済み [target - tolerance, target +
tolerance] 帯域の内側であること — 「寸法公差は帯域であって提案では
ない」）、**②生産数量上限算術**（提案の生産数量が登録済み受注数量
を超えないこと）。README の Trust Controls どおり
`:approve-unguarded-blade-operation`／
`:clear-quality-inspection-failure` は confidence に関わらず常時
escalate。14 tests / 30 assertions green。satellite main
`f2de4610`。registry `:blueprint` 12 → 11 / `:implemented` 122 →
**123**（occupation main `33ca72fe`、pin `33b34176`）。決定的
governor 原則 第 53 例 = **寸法公差区間包含 + 生産数量上限算術 +
常時escalate（無防護刃・品質クリアランス）**。

## Addendum 62 (2026-07-14, 垂直第 54 号 = SewingOperationsActor — tick 73)

**垂直第 54 号 = SewingOperationsActor（8153、README 記載名
採用）**: HARD 不変条件は縫製領域で新規2本: **①ステッチ密度帯域**
（測定ステッチ密度が登録済み [min, max] 帯域の内側であること —
「ステッチ密度は仕様帯域であってオペレータの感覚ではない」）、
**②縫い目偏差上限**（測定縫い目偏差が登録済み上限を超えないこと）。
README の Trust Controls どおり
`:approve-needle-mechanism-operation`／
`:clear-quality-inspection-failure` は confidence に関わらず常時
escalate。14 tests / 30 assertions green。satellite main
`a85549c4`。registry `:blueprint` 11 → 10 / `:implemented` 123 →
**124**（occupation main `2c8ab6b0`、pin `ff26663a`）。決定的
governor 原則 第 54 例 = **ステッチ密度帯域 + 縫い目偏差上限 +
常時escalate（針機構・品質クリアランス）**。

## Addendum 63 (2026-07-14, 垂直第 55 号 = FabricProcessingActor — tick 74)

**垂直第 55 号 = FabricProcessingActor（8154、README 記載名採用）**:
HARD 不変条件は薬品処理・加工温度領域で新規2本: **①薬品濃度上限
算術**（測定薬品濃度が登録済み上限を超えないこと — 「濃度は測定で
あって匂いの判断ではない」）、**②処理温度安全域**（測定処理温度が
登録済み安全域帯域の内側であること）。README の Trust Controls
どおり `:approve-concentrated-chemical-handling`／
`:approve-pressurized-equipment-operation` は confidence に関わらず
常時 escalate。14 tests / 30 assertions green。satellite main
`8f0c554d`。registry `:blueprint` 10 → 9 / `:implemented` 124 →
**125**（occupation main `c4a5d374`、pin `01894433`）。決定的
governor 原則 第 55 例 = **薬品濃度上限算術 + 処理温度安全域 +
常時escalate（濃縮薬品・加圧機器）**。

## Addendum 64 (2026-07-14, 垂直第 56 号 = WoodProcessingActor — tick 75)

**垂直第 56 号 = WoodProcessingActor（8172、README 記載名採用）**:
HARD 不変条件は稼働安全・処理能力領域で新規2本: **①粉じん濃度上限
算術**（測定粉じん濃度が登録済み職業安全上限を超えないこと —
「粉じん曝露は職業安全限度に対する測定であって視認判断ではない」）、
**②処理量定格上限**（提案処理量が登録済み定格処理能力を超えないこと
— 「定格超過は機械的リスクであって効率ではない」）。README の Trust
Controls どおり `:approve-blade-proximity-operation`／
`:approve-blade-change-maintenance` は confidence に関わらず常時
escalate。14 tests / 29 assertions green。satellite main
`7aea7b0c`。registry `:blueprint` 9 → 8 / `:implemented` 125 →
**126**（occupation main `d06daee6`、pin `db5e3bee`）。決定的
governor 原則 第 56 例 = **粉じん濃度上限算術 + 処理量定格上限 +
常時escalate（刃近接作業・刃交換保守）**。

## Addendum 65 (2026-07-14, 垂直第 57 号 = StationaryPlantActor — tick 76)

**垂直第 57 号 = StationaryPlantActor（8189、README 記載名採用）**:
HARD 不変条件は圧力・保守領域で新規2本: **①圧力安全域区間包含**
（測定圧力が登録済み安全域帯域の内側であること）、**②保守期限上限
算術**（保守後稼働時間が登録済み保守期限を超えないこと — 「期限
超過稼働は機械的リスクであってスケジュールの都合ではない」）。
README の Trust Controls どおり
`:approve-pressurized-system-proximity`／
`:approve-startup-shutdown-sequence` は confidence に関わらず常時
escalate。14 tests / 30 assertions green。satellite main
`233a65b0`。registry `:blueprint` 8 → 7 / `:implemented` 126 →
**127**（occupation main `15149d44`、pin `fc800654`）。決定的
governor 原則 第 57 例 = **圧力安全域区間包含 + 保守期限上限算術 +
常時escalate（加圧系統近接・起動停止シーケンス）**。

## Addendum 66 (2026-07-14, 垂直第 58 号 = CarVanDrivingActor — tick 77)

**垂直第 58 号 = CarVanDrivingActor（8322、README 記載名採用）**:
HARD 不変条件は配車領域で新規2本: **①勤務時間上限算術**（当日既
勤務時間 + 提案トリップ時間が登録済み日次上限を超えないこと —
「勤務時間は累積算術であって運転手の裁量ではない」）、**②点検有効
期間**（点検後経過日数が登録済み有効期間を超えないこと — 「点検
切れ車両での配車は書類の整った車両ではない」）。README の Trust
Controls どおり `:approve-duty-hours-exception`／
`:approve-high-risk-trip` は confidence に関わらず常時
escalate。14 tests / 30 assertions green。satellite main
`7be9d3de`。registry `:blueprint` 7 → 6 / `:implemented` 127 →
**128**（occupation main `5b50c217`、pin `3afbd560`）。決定的
governor 原則 第 58 例 = **勤務時間上限算術 + 点検有効期間 +
常時escalate（勤務時間超過・高リスクトリップ）**。

## Addendum 67 (2026-07-14, 垂直第 59 号 = EarthmovingActor — tick 78)

**垂直第 59 号 = EarthmovingActor（8342、README 記載名採用）**:
HARD 不変条件は掘削領域で新規2本: **①クリア深度上限算術**（提案
掘削深度が登録済み地下埋設物クリア上限を超えないこと — 「クリア
深度超過はストライクリスクであってスケジュールの都合ではない」）、
**②測量済み区画membership**（提案掘削区画が登録済み cleared-zones
集合の要素であること — 「未測量地は対象外」）。README の Trust
Controls どおり `:approve-unlocated-utility-excavation`／
`:approve-occupied-zone-entry` は confidence に関わらず常時
escalate。14 tests / 29 assertions green。satellite main
`20a6dce3`。registry `:blueprint` 6 → 5 / `:implemented` 128 →
**129**（occupation main `c32d52ce`、pin `caa18349`）。決定的
governor 原則 第 59 例 = **クリア深度上限算術 + 測量済み区画
membership + 常時escalate（未測量地掘削・占有区画進入）**。

## Addendum 68 (2026-07-14, 垂直第 60 号 = CropFarmLabourActor — tick 79、垂直60本累計マイルストーン)

**垂直第 60 号 = CropFarmLabourActor（9211、README 記載名採用）。
このループが生産した垂直 actor が累計 60 本に到達**。HARD 不変条件
は農作業安全領域で新規2本: **①運搬重量上限算術**（提案運搬重量が
登録済み安全上限を超えないこと — 「登録済み安全運搬重量超過は労災
リスクであって根性の話ではない」）、**②休憩義務発生時間上限算術**
（連続作業時間が登録済み休憩義務発生時間を超えないこと — 「休憩
義務は算術であってクルーリーダーの裁量ではない」）。README の
Trust Controls どおり `:approve-heavy-equipment-proximity`／
`:approve-water-source-treatment` は confidence に関わらず常時
escalate。14 tests / 29 assertions green。satellite main
`86dba0f1`。registry `:blueprint` 5 → 4 / `:implemented` 129 →
**130**（occupation main `10dc2fd5`、pin `b4ee42d7`）。決定的
governor 原則 第 60 例 = **運搬重量上限算術 + 休憩義務発生時間上限
算術 + 常時escalate（重機近接・水源処理）**。

累計サマリ（tick 79 時点）: ISCO `:implemented` **130**（開始時
70）、`:blueprint` 4（開始時 19）、垂直 actor 60 本（ループ産、開始時
0）、決定的 governor 原則 **60 種類**確立。robotics-premise クラスタ
（README に Advisor→Governor 契約が既に記述されている blueprint）
15 本を6122以降忠実に実装済み。

## Addendum 69 (2026-07-14, 垂直第 61 号 = FreightHandlingActor — tick 80)

**垂直第 61 号 = FreightHandlingActor（9333、README 記載名採用）**:
HARD 不変条件は貨物荷役領域で新規2本: **①重量突合帯域**（測定重量
が登録済み [マニフェスト重量 − 許容差, マニフェスト重量 + 許容差]
帯域の内側であること — 「重量差異は書類上の不備であって荷役判断
ではない」）、**②ドック割当membership**（提案ドックが登録済み
assigned-docks 集合の要素であること — 「未割当ドックでの荷役は
許可されない」）。README の Trust Controls どおり
`:approve-loading-dock-proximity`／
`:approve-overweight-load-handling` は confidence に関わらず常時
escalate。14 tests / 30 assertions green。satellite main
`4fd00030`。registry `:blueprint` 4 → 3 / `:implemented` 130 →
**131**（occupation main `f838289f`、pin `cdcacc39`）。決定的
governor 原則 第 61 例 = **重量突合帯域 + ドック割当membership +
常時escalate（ドック近接・過積載荷役）**。

## Addendum 70 (2026-07-14, 垂直第 62 号 = KitchenSupportPracticeActor — tick 81)

**垂直第 62 号 = KitchenSupportPracticeActor（9412、README 記載名
採用 — Kitchen Advisor -> Kitchen Support Governor）**: HARD 不変
条件は厨房衛生・在庫領域で新規2本: **①殺菌温度帯域**（提案殺菌
水温が登録済み [`:min-sanitize-temp-c`, `:max-sanitize-temp-c`]
帯域の内側であること — 「殺菌水温は食品安全仕様であって感覚テスト
ではない」）、**②補充数量上限算術**（提案補充数量が登録済み
`:max-restock-quantity` を超えないこと — 「登録済み保管容量を超える
過剰発注は在庫リスクであって倹約ではない」）。README/business-model.md
の Trust Controls どおり `:approve-hot-surface-proximity`（火気・
高温面近接はガバナー gate なしのロボット動作不可）／
`:approve-sharp-tool-zone-entry`（刃物ゾーンは人間の sign-off 必須）
は confidence に関わらず常時 escalate。14 tests / 30 assertions
green。satellite main `e8a3cb60`。registry `:blueprint` 3 → 2 /
`:implemented` 131 → **132**（occupation main `1be49942`、pin
`45fa3aee`）。決定的 governor 原則 第 62 例 = **殺菌温度帯域 +
補充数量上限算術 + 常時escalate（高温面近接・刃物ゾーン進入）**。

残る `:blueprint` は 2 件のみ: `3521`（Broadcasting and Audiovisual
Technicians — kawaraban news-mirror 連携を含む bespoke 設計のため
Addendum 46 で意図的に保留中）と `9622`（Odd-job Persons — 平易な
generic blueprint、次点候補）。

## Addendum 71 (2026-07-14, 垂直第 63 号 = HandymanPracticeActor — tick 82、blueprint 在庫が 3521 のみに到達)

**垂直第 63 号 = HandymanPracticeActor（9622、README 記載名採用 —
Handyman Advisor -> Handyman Governor）**: HARD 不変条件は取扱重量・
現場アクセス領域で新規2本: **①取扱重量上限算術**（提案取扱重量が
登録済み `:max-handling-weight-kg` を超えないこと — 「登録済み容量
超過は挫傷/負傷リスクであって根性ではない」）、**②作業時間上限
算術**（提案作業時間が登録済み `:max-task-duration-hours` を超えない
こと — 「現場アクセス許諾は時間で区切られており無制限ではない」）。
README/business-model.md の Trust Controls どおり
`:approve-electrical-plumbing-access`（電気・配管系統アクセスは
ガバナー gate なしのロボット動作不可）／`:approve-working-at-height`
（高所作業は人間の sign-off 必須）は confidence に関わらず常時
escalate。14 tests / 29 assertions green。satellite main
`f97472a2`。registry `:blueprint` 2 → 1 / `:implemented` 132 →
**133**（occupation main `8583a382`、pin `e95f9ad3`）。決定的
governor 原則 第 63 例 = **取扱重量上限算術 + 作業時間上限算術 +
常時escalate（電気配管アクセス・高所作業）**。

累計サマリ（tick 82 時点）: ISCO `:implemented` **133**（開始時
70）、`:blueprint` **1**（開始時 19、残るは `3521` のみ）、垂直
actor 63 本（ループ産、開始時 0）、決定的 governor 原則 **63 種類**
確立。robotics-premise クラスタ（README に Advisor→Governor 契約が
既に記述されている blueprint）17 本を 6122 以降忠実に実装済み。
これで「素朴な blueprint 在庫」は事実上枯渇し、次ティック以降は
①`3521` の bespoke 実装（kawaraban 連携の設計を Addendum 46 の
判断どおり正式に着手）、②pin鮮度/drift 定期スイープ、③registry
自体の次波（`:spec` 302 件からの新規 blueprint 起票）のいずれかに
切り替える必要がある。

## Addendum 72 (2026-07-14, 垂直第 64 号 = MediaBroadcastActor — tick 83、blueprint 在庫 完全枯渇マイルストーン)

**垂直第 64 号 = MediaBroadcastActor（3521、blueprint.edn 記載名採用
— Media Advisor -> Media Broadcast Governor）。Addendum 46（tick 57）
で意図的に保留していた bespoke 設計を、Addendum 53 の方針転換
（robotics-premise クラスタは README/business-model.md の Trust
Controls を忠実にマッピングして実装する）に沿って正式着手・完了**。

3521 は他の robotics-premise 頂点と異なり、**kawaraban（瓦版、
`etzhayyim/com-etzhayyim-kawaraban`）という別の非商用ニュースミラー
actor の下流**という設計になっている: kawaraban は世界の報道機関を
`headline + canonical link + bounded fair-use excerpt` のみの
append-only Datom ログにミラーし、全文保存も広告販売も
engagement-ranking も真偽判定も一切しない。この practice はその
既に charter で境界づけられた出力から商用派生物（動画ニュースダイジェ
スト、ポッドキャスト、シンジケーション API、white-label editorial
curation）を生産する。

HARD 不変条件は kawaraban 下流固有の帰属・引用境界領域で新規2本:
**①引用文字数上限算術**（提案引用文字数が登録済み kawaraban article
の `:max-excerpt-chars`（fair-use 上限）を超えないこと — 「上限超過
の引用は全文転載でありフェアユース抜粋ではない」）、**②
attribution/link-out 完全一致**（提案する派生物の attribution と
link-out が登録済み article の attribution/canonical-link と一致
すること — 「誤帰属・なりすましは syndication ではない」）。README
の Trust Controls どおり `:approve-publish-derivative-product`
（派生放送製品の公開）／`:approve-live-on-air-switching`（ライブ
オンエア切替）は confidence に関わらず常時 escalate。15 tests / 31
assertions green。satellite main `7cb3557`。registry `:blueprint`
1 → **0** / `:implemented` 133 → **134**（occupation main
`757e3719`、pin `398d2aa7`）。決定的 governor 原則 第 64 例 =
**引用文字数上限算術 + attribution/link-out完全一致 +
常時escalate（派生製品公開・ライブオンエア切替）**。

**マイルストーン: `:blueprint` 在庫が完全に 0 件に到達**（開始時 19
件、tick 20 から tick 83 まで 64 ティックかけて全件を `:implemented`
に昇格）。累計サマリ（tick 83 時点）: ISCO `:implemented` **134**
（開始時 70）、`:blueprint` **0**（開始時 19）、垂直 actor 64 本
（ループ産、開始時 0）、決定的 governor 原則 **64 種類**確立。
robotics-premise クラスタ 18 本を 6122 以降忠実に実装済み。

blueprint 在庫が枯渇したため、以後のティックは以下いずれかに
切り替える: ①pin鮮度/drift 定期スイープ（既存パターン、tick 34/37/
55/60 実績あり）、②registry `:spec` 302 件から新規 blueprint を
起票し次波を作る（scaffold → GitHub repo 作成 → registry 登録の
`new-project-scaffold` skill 経路）、③既存 64 verticals の
coverage/robustness 向上（edge-case テスト追加・ドキュメント整備・
capability layer 検証）。

## Addendum 73 (2026-07-14, tick 84、pin鮮度/drift スイープ + repo hygiene)

blueprint 在庫が枯渇したため（Addendum 72）、このティックは①の
pin鮮度/drift 定期スイープを実施。新規 vertical はなし。発見・修正
した内容:

1. **`.gitignore` 欠落（coverage gap）**: `cloud-itonami-isco-*` 64
   本中 51 本に `.gitignore` が無く、`clojure -M:test` 実行後に
   `.cpcache/` が untracked のまま残り得た（テンプレート元の
   `cloud-itonami-isco-1221` にはあったが、各ティックで `deps.edn`
   のみコピーし `.gitignore` はコピーしていなかったための系統的
   欠落）。51 本すべてに `.cpcache/` の `.gitignore` を追加し、各
   ローカル checkout が origin/main と ahead=0/behind=0 であることを
   確認した上で直接 main へ commit/push（trivial・非論争的な hygiene
   fix のため branch+PR は経由せず）。
2. **共有 `orgs/kotoba-lang/occupation` checkout の detached HEAD /
   停滞**: 直近3件（9412/9622/3521）の昇格はすべて使い捨て worktree
   経由で行ったため、共有 checkout 自体は `f838289`（131件時点）で
   detached HEAD のまま3コミット遅れていた。`git checkout main` +
   `git merge --ff-only kotoba-lang/main` で `757e371`（west.yml pin
   と一致）まで前進させ修復。
3. **`kotoba-lang/occupation` の放置 remote branch 69本**: このループ
   の全履歴（本セッション以前を含む）にわたり `feat/XXXX-implemented`
   / `feat/isco-wave*-batch*` 系のブランチが merge 後も削除されずに
   蓄積していた（「着地後の後片付けまでがタスクの完了条件」からの
   逸脱）。`git-cleanup-conflict` skill の content-containment 手法
   （リテラル diff でなく、各ブランチが対象とした ISCO code が現在
   の main で実際に `:implemented` になっているかを個別検証）で69本
   全てが完全着地済みであることを確認してから削除。
4. **`nbb scripts/gen-west-manifest.cljs --check`** は west.yml
   全体としては STALE と報告（このループが触れていない他の west
   project 群の既存 drift によるもの）。CLAUDE.md の「wholesale 再生成
   commit は禁止」原則により、このスイープでは追わない（自分が
   触った `occupation` entry 自体は fresh を個別確認済み）。今後
   の pin鮮度スイープでの調査対象として記録するに留める。

次ティック以降は②（registry `:spec` からの新規 blueprint 起票）か
③（既存 verticals の coverage 深化）へ切り替える。

## Addendum 74 (2026-07-14, tick 85、wave-0 cognitive batch #8 — wave 0 の :spec 完全消化)

Addendum 73 の②（registry `:spec` からの新規 blueprint 起票）を実施。
`kotoba.occupation.wave`（ADR-2607121000、逆トポロジカル rollout）で
wave 0 = **cognitive-substrate-root**（他の全 wave の自動化が依存する
認知基盤の根 — ICT/財務/法務/事務）は 55 件中 45 件が既に
`:implemented`、残る `:spec` はちょうど 10 件のみだった:
`2412`（Financial and Investment Advisers）、`2611`（Lawyers）、
`2612`（Judges）、`2619`（Legal Professionals NEC）、`4212`
（Bookmakers, Croupiers and Related Gaming Workers）、`4213`
（Pawnbrokers and Money-lenders）、`4214`（Debt Collectors and
Related Workers）、`4411`（Library Clerks）、`4412`（Mail Carriers
and Sorting Clerks）、`4414`（Scribes and Related Workers）。

`new-project-scaffold` skill の標準フロー（ADR起票 → 子リポscaffold
→ GitHub repo作成 → registry登録、恒久承認）で10本を一括処理:
各 `cloud-itonami-isco-{code}` を `cloud-itonami` org に新規 public
repo として作成（AGPL-3.0-or-later）、既存 robotics-premise テンプレート
（README の Robotics premise 節 + Core Contract + `docs/business-model.md`
+ `docs/operator-guide.md` + `blueprint.edn` + CODE_OF_CONDUCT/
CONTRIBUTING/GOVERNANCE/SECURITY + `.gitignore`）を各職種の Advisor/
Governor 名・Trust Controls・narrow physical-edge task で個別に埋めて
scaffold（`2612` Judges は公職ではなく private arbitration/mediation
practice として、`2619` Legal NEC は notarization/compliance practice
として、それぞれ実在するビジネス形態にマッピング）。src/test はまだ
無く、実装（`:blueprint` → `:implemented`）は将来ティックの仕事。

registry `:spec` 302 → **292** / `:blueprint` 0 → **10**
（occupation main `3b3760cb`、pin `cf8c5d8c`）。これで **wave 0 の
`:spec` プールが完全にゼロ**（55/55 が blueprint-or-implemented）。
14 tests / 902 assertions green（`kotoba-lang/occupation`）。

累計サマリ（tick 85 時点）: ISCO `:implemented` 134（不変）、
`:blueprint` **10**（新規 blueprint 在庫の補充）、`:spec` **292**。
wave 別内訳: wave0 `spec 0 / bp 10 / impl 45`、wave1
`spec 52 / bp 0 / impl 27`、wave2 `spec 43 / bp 0 / impl 9`、wave3
`spec 101 / bp 0 / impl 26`、wave4 `spec 96 / bp 0 / impl 27`。次
ティック以降はこの10本の `:blueprint` → `:implemented` 実装（vertical
本数65本目以降）を再開できる。

## Addendum 75 (2026-07-14, 垂直第 65 号 = FinancialAdvisoryActor — tick 86)

**垂直第 65 号 = FinancialAdvisoryActor（2412、README 記載名採用 —
Advisory Advisor -> Financial Advisory Governor）。Addendum 74 で
補充した wave-0 batch #8 の10本から最初の実装**。HARD 不変条件は
助言適合性領域で新規2本: **①配分比率上限算術**（提案配分比率が
登録済み account の `:max-allocation-pct`（適合性上限）を超えない
こと — 「登録済みリスク許容度を超える推奨は不適合助言であって積極
戦略ではない」）、**②リスク開示添付の存在チェック**（推奨は
`:risk-disclosure-attached?` が true でなければ commit 不可 —
「リスク開示が添付されていない推奨は未開示助言であって効率的サービス
ではない」）。README/business-model.md の Trust Controls どおり
`:approve-trade-execution`／`:approve-fund-transfer`（取引執行・資金
移動はガバナー gate なしに実行不可）は confidence に関わらず常時
escalate。14 tests / 29 assertions green。satellite main `9036f916`。
registry `:blueprint` 10 → 9 / `:implemented` 134 → **135**
（occupation main `5c655c1c`、pin `b98c8e5e`）。決定的 governor
原則 第 65 例 = **配分比率上限算術 + リスク開示添付存在チェック +
常時escalate（取引執行・資金移動）**。

残る wave-0 batch #8 の `:blueprint` は9本: `2611`/`2612`/`2619`
（法務クラスタ）、`4212`/`4213`/`4214`（金融隣接信託サービス）、
`4411`/`4412`/`4414`（事務・サービスクラスタ）。次ティック以降も
この在庫から選んで実装を継続できる。

## Addendum 76 (2026-07-14, 垂直第 66 号 = LegalPracticeActor — tick 87)

**垂直第 66 号 = LegalPracticeActor（2611、README 記載名採用 —
Legal Advisor -> Legal Practice Governor）**。HARD 不変条件は受任
範囲・職業倫理領域で新規2本: **①請求時間上限算術**（提案請求時間
が登録済み matter の `:max-billable-hours`（受任範囲上限）を超えない
こと — 「登録済み範囲を超える請求はスコープクリープであって注意義務
ではない」）、**②利益相反チェック完了の存在チェック**（matter は
`:conflict-check-cleared?` が true でなければ作業準備不可 —
「利益相反チェックが完了していない matter への作業準備は倫理違反で
あって効率的サービスではない」）。README/business-model.md の
Trust Controls どおり `:approve-court-filing`（裁判所・登記所への
提出はガバナー gate なしに実行不可）／`:approve-new-representation`
（新規受任の承諾は常に人間の sign-off が必要）は confidence に
関わらず常時 escalate。14 tests / 29 assertions green。satellite
main `9427177`。registry `:blueprint` 9 → 8 / `:implemented` 135 →
**136**（occupation main `f3d7ca75`、pin `8243fe2f`）。決定的
governor 原則 第 66 例 = **請求時間上限算術 + 利益相反チェック存在
チェック + 常時escalate（法廷提出・新規受任）**。

残る wave-0 batch #8 の `:blueprint` は8本: `2612`/`2619`（法務
クラスタ）、`4212`/`4213`/`4214`（金融隣接信託サービス）、
`4411`/`4412`/`4414`（事務・サービスクラスタ）。

## Addendum 77 (2026-07-14, 垂直第 67 号 = ArbitrationActor — tick 88)

**垂直第 67 号 = ArbitrationActor（2612「Judges」、README 記載どおり
公職ではなく私的仲裁・調停実務として実装 — Arbitration Advisor ->
Arbitration Governor）**。HARD 不変条件は管轄・利益相反領域で新規
2本: **①裁定額管轄上限算術**（提案裁定額が登録済み case の
`:max-award-amount`（管轄/合意上限）を超えないこと — 「登録済み
上限を超える裁定は越権行為であって寛大な裁定ではない」）、**②忌避
チェック完了の存在チェック**（case は `:recusal-check-cleared?` が
true でなければ finding 起案不可 — 「忌避/利益相反チェックが完了
していない case への finding 起案は利益相反違反であって効率的
サービスではない」）。README/business-model.md の Trust Controls
どおり `:approve-binding-award-issuance`（拘束力ある裁定の発行は
ガバナー gate なしに実行不可）／`:approve-case-acceptance`（新規
案件の受任は常に人間の sign-off が必要）は confidence に関わらず
常時 escalate。14 tests / 29 assertions green。satellite main
`54783e7b`。registry `:blueprint` 8 → 7 / `:implemented` 136 →
**137**（occupation main `c2e722ef`、pin `de6922fb`）。決定的
governor 原則 第 67 例 = **裁定額管轄上限算術 + 忌避チェック存在
チェック + 常時escalate（拘束力ある裁定発行・案件受任）**。

残る wave-0 batch #8 の `:blueprint` は7本: `2619`（法務クラスタ）、
`4212`/`4213`/`4214`（金融隣接信託サービス）、`4411`/`4412`/`4414`
（事務・サービスクラスタ）。

## Addendum 78 (2026-07-14, 垂直第 68 号 = LegalComplianceActor — tick 89、法務クラスタ完結)

**垂直第 68 号 = LegalComplianceActor（2619「Legal Professionals NEC」
— Compliance Advisor -> Legal Compliance Governor）**。HARD 不変条件
は公証・本人確認領域で新規2本: **①認証謄本部数上限算術**（提案
認証謄本部数が登録済み document の `:max-certified-copies`（認可
上限）を超えないこと — 「認可を超える謄本発行は無許可複製であって
効率的サービスではない」）、**②本人確認完了の存在チェック**
（document は `:identity-verified?` が true でなければ認証不可 —
「本人確認未完了の document への認証は公証詐欺リスクであって効率的
サービスではない」）。README/business-model.md の Trust Controls
どおり `:approve-certification-issuance`（公証・認証の発行はガバナー
gate なしに実行不可）／`:approve-regulatory-filing`（規制当局への
提出代行は常に人間の sign-off が必要）は confidence に関わらず常時
escalate。14 tests / 29 assertions green。satellite main `0a0fc9f3`。
registry `:blueprint` 7 → 6 / `:implemented` 137 → **138**
（occupation main `313220dd`、pin `929c7a3b`）。決定的 governor
原則 第 68 例 = **認証謄本部数上限算術 + 本人確認存在チェック +
常時escalate（公証認証発行・規制提出代行）**。

**wave-0 batch #8 の法務クラスタ（2611/2612/2619）が完結**（2412
金融は tick 86 で別クラスタとして先行済み）。残る `:blueprint` は
6本: `4212`/`4213`/`4214`（金融隣接信託サービス）、`4411`/`4412`/
`4414`（事務・サービスクラスタ）。

## Addendum 79 (2026-07-14, 垂直第 69 号 = GamingOperationsActor — tick 90)

**垂直第 69 号 = GamingOperationsActor（4212、README 記載名採用 —
Gaming Advisor -> Gaming Operations Governor）**。HARD 不変条件は
テーブル運営・年齢確認領域で新規2本: **①配当額上限算術**（提案
配当額が登録済み table の `:max-payout`（テーブル上限）を超えない
こと — 「登録済み上限を超える配当は無許可支払いであって幸運な夜
ではない」）、**②本人確認完了の存在チェック**（ウェイジャー精算
提案は `:patron-verified?` が true でなければ精算不可 — 「年齢/
本人確認未完了のウェイジャー精算は未成年/詐欺リスクであって効率的
サービスではない」）。README/business-model.md の Trust Controls
どおり `:approve-over-limit-payout`（登録上限超過の配当はガバナー
gate なしに実行不可）／`:approve-unverified-wager`（本人確認未完了
のウェイジャー受付は常に人間の sign-off が必要）は confidence に
関わらず常時 escalate。14 tests / 29 assertions green。satellite
main `c692d489`。registry `:blueprint` 6 → 5 / `:implemented` 138 →
**139**（occupation main `7cc77845`、pin `de65826b`）。決定的
governor 原則 第 69 例 = **配当額上限算術 + 本人確認存在チェック +
常時escalate（超過配当・未確認ウェイジャー）**。

残る wave-0 batch #8 の `:blueprint` は5本: `4213`/`4214`（金融
隣接信託サービス）、`4411`/`4412`/`4414`（事務・サービスクラスタ）。

## Addendum 80 (2026-07-14, 垂直第 70 号 = PawnbrokingActor — tick 91)

**垂直第 70 号 = PawnbrokingActor（4213、README 記載名採用 —
Pawnbroking Advisor -> Pawnbroking Governor）**。HARD 不変条件は
鑑定・担保領域で新規2本: **①鑑定額上限算術**（提案貸付額が登録済み
item の `:appraised-value`（鑑定上限）を超えないこと — 「登録済み
鑑定額を超える貸付は無担保前貸であって質貸ではない」）、**②鑑定
完了の存在チェック**（item は `:appraisal-completed?` が true で
なければ貸付提案不可 — 「鑑定未完了の担保への貸付提案は無担保の
当て推量であって質鑑定ではない」）。README/business-model.md の
Trust Controls どおり `:approve-over-appraisal-disbursement`（登録
鑑定額超過の貸付実行はガバナー gate なしに実行不可）／
`:approve-unappraised-loan-offer`（未鑑定担保への貸付提案は常に
人間の sign-off が必要）は confidence に関わらず常時 escalate。
14 tests / 29 assertions green。satellite main `0d6da998`。registry
`:blueprint` 5 → 4 / `:implemented` 139 → **140**（occupation main
`4fdbc0dd`、pin `06d01684`）。決定的 governor 原則 第 70 例 =
**鑑定額上限算術 + 鑑定完了存在チェック + 常時escalate（超過鑑定額
貸付・未鑑定貸付提案）**。

（運用メモ: 使い捨て worktree での `clojure -M:test` が
`Local lib io.github.kotoba-lang/technology not found` で失敗する
現象を検出——`kotoba-lang/occupation` の `deps.edn` が
`:local/root "../technology"` という相対 sibling 依存を持つため、
worktree の親ディレクトリ（scratchpad 直下）に `technology` が
存在しないと解決できない。`scratchpad/technology ->
orgs/kotoba-lang/technology` のシンボリックリンクを1回作成して
恒久対処——以後のティックでも再利用される。）

残る wave-0 batch #8 の `:blueprint` は4本: `4214`（金融隣接信託
サービス）、`4411`/`4412`/`4414`（事務・サービスクラスタ）。

## Addendum 81 (2026-07-14, 垂直第 71 号 = DebtCollectionActor — tick 92、金融隣接信託サービスクラスタ完結)

**垂直第 71 号 = DebtCollectionActor（4214、README 記載名採用 —
Collection Advisor -> Debt Collection Governor）**。HARD 不変条件は
接触許可・ハラスメント防止領域で新規2本: **①接触許可時間帯**
（提案接触時刻が登録済み account の `[:contact-start-hour,
:contact-end-hour]`（許可時間帯）の内側であること — 「登録許可時間
外の接触はハラスメントリスクであって注意義務ではない」）、**②
ハラスメントフラグ不許可**（提案は `:harassment-flagged?` が true
であってはならない — 「威嚇/ハラスメント文言はコンストラクトとして
拒否される（単なる非推奨ではない）」）。README/business-model.md の
Trust Controls どおり `:approve-off-hours-contact`（登録許可時間外
の接触はガバナー gate なしに実行不可）／`:approve-settlement-offer`
（債務者への和解提案は常に人間の sign-off が必要）は confidence に
関わらず常時 escalate。14 tests / 30 assertions green。satellite
main `683ef8ac`。registry `:blueprint` 4 → 3 / `:implemented` 140 →
**141**（occupation main `3095d4c5`、pin `e5e68fb5`）。決定的
governor 原則 第 71 例 = **接触許可時間帯 + ハラスメントフラグ
不許可 + 常時escalate（時間外接触・和解提案）**。

**wave-0 batch #8 の金融隣接信託サービスクラスタ（4212/4213/4214）
が完結**。残る `:blueprint` は3本: `4411`/`4412`/`4414`（事務・
サービスクラスタのみ）。

## Addendum 82 (2026-07-14, tick 93、並行セッション検出 + wave-0 batch #8 完全完結の同期)

tick 93 で `4411`（Library Clerks）の実装に着手し、`cloud-itonami-isco-4411`
を fresh clone した直後、**既に `libraryclerk` 名前空間で完全実装済み
（`0ee19f5` merge、README/registry 未反映）**であることを検出——この
loop の別の並行セッション（同一 30分 cron `75c9c8e6` の前ティックが
未完了のうちに次ティックが発火し、複数セッションが同時に走っている
と推定。co-author tag は本セッションと同じ `Claude Fable 5`/
`Claude Sonnet 5`）が既に着手・完了していた。**自分の重複実装
（`src/library/`・`test/library/`、未commit・未push）は破棄して
撤退**——CLAUDE.md「Claude Code の Agent 委譲」節と同型の衝突検知・
撤退パターン（tick 57 の 3521 near-miss と同じ手順）。

続けて `4412`・`4414` も同様に確認したところ、**両方とも既に別
セッションによって実装・registry 昇格まで完了済み**と判明
（`4414` は確認した数秒後に registry 昇格が入るのを目撃——
raced な同時実行の実測証拠）。この時点で **wave-0 batch #8 の
10本全てが `:implemented` に到達**（registry `:blueprint` 0 /
`:implemented` **144** / `:spec` 292。wave0 は 55/55 完全に
`:implemented`）。

自分の寄与として残っていたのは west.yml の pin 同期のみ:
occupation main の pin が自分の tick 92 時点（`3095d4c5`）で止まって
いたのを、他セッションの3件の昇格を含む最新 tip（`ee3f6a99`）まで
前進（single-entry API PUT、blob-sha ロックで無競合に成功、commit
`0bd43505`）。4411/4412/4414 の HARD 不変条件・governor 原則の詳細は
他セッションの成果であり本 ADR では未確認のため個別記載しない
（`kotoba-lang/occupation` の各実装コミット参照）。

**教訓**: この loop は複数の並行セッションが同一 blueprint 在庫を
奪い合う形で実行され得る。今後は新規 vertical に着手する前に、
①対象 satellite repo の `git log main` / tree を fresh に確認して
既存実装の有無を検出、②registry の対象 entry の `:maturity` を
fresh に再確認、の2点を必須の事前チェックとする。

## Addendum 83 (2026-07-14, tick 94、wave-2 (coordination-logistics) batch #1 — オーナー指示によるレーン分割)

オーナーより明示指示: 「wave 2 を進めて、wave 1 は別に進めている
ので」。Addendum 82 で判明した並行セッション衝突リスクへの対応として、
オーナーが wave 単位でレーンを分割——**本セッションは以後 wave 2
（coordination-logistics）に専念し、wave 1（design-governance）は
別セッションが担当**。この分割により同一 blueprint 在庫の奪い合いを
構造的に回避する。

wave 2 の thesis（ADR-2607121000）: 「Drivers, warehouse/material-
recording work, labourers and protective services move everything the
production and consumer waves need; agent orchestration plus early
robotics」。tick 94 時点の wave 2 状態: 52 件中 `:spec` 43 / `:blueprint`
0 / `:implemented` 9。sub-major 33（協調・仲介系 associate
professionals）/ 54（protective services）/ 83（drivers）/ 93
（labourers）の中から、政府職員・法執行系（Police Officers, Prison
Guards, Government *Officials 等）を今回のバッチでは避け、実在する
独立事業として素直にマッピングできる10件を選定し **wave-0 batch #8
と同型の scaffold フロー**（`new-project-scaffold` skill の標準手順）
で一括処理:

`3311`（Securities and Finance Dealers and Brokers → Independent
Securities Brokerage Practice）、`3312`（Credit and Loans Officers →
Independent Loan Origination & Underwriting Practice）、`3321`
（Insurance Representatives → Independent Insurance Brokerage
Practice）、`3323`（Buyers → Independent Procurement & Sourcing
Practice）、`3334`（Real Estate Agents and Property Managers →
Independent Real Estate & Property Management Practice）、`3332`
（Conference and Event Planners → Independent Event Planning
Practice）、`5414`（Security Guards → Independent Security Guard
Practice）、`8331`（Bus and Tram Drivers → Independent Passenger
Transport Practice）、`9313`（Building Construction Labourers →
Independent Construction Labour Practice）、`9334`（Shelf Fillers →
Independent Retail Merchandising & Restocking Practice）。

各 repo を `cloud-itonami` org に新規 public repo として作成
（AGPL-3.0-or-later）、robotics-premise テンプレート（README +
`docs/business-model.md` + `docs/operator-guide.md` + `blueprint.edn`
+ CODE_OF_CONDUCT/CONTRIBUTING/GOVERNANCE/SECURITY + `.gitignore`）を
職種ごとの Advisor/Governor 名・Trust Controls・narrow physical-edge
task で個別記述。src/test はまだ無く、実装（`:blueprint` →
`:implemented`）は将来ティックの仕事。

registry `:spec` 292 → **282** / `:blueprint` 0 → **10**（occupation
main `a77c2fc5`、pin `b3cc8ac2`）。wave 2 内訳: `spec 33 / bp 10 /
impl 9`。14 tests / 915 assertions green（`kotoba-lang/occupation`）。

次ティック以降はこの10本の `:blueprint` → `:implemented` 実装を
wave 2 レーン内で継続する。

## Addendum 84 (2026-07-14, 垂直第 72 号 = SecuritiesBrokerageActor — tick 95、wave 2 レーン初実装)

**垂直第 72 号 = SecuritiesBrokerageActor（3311、README 記載名採用 —
Brokerage Advisor -> Securities Brokerage Governor）。wave-2
（coordination-logistics）レーンの初実装**。着手前に対象 repo の
tree と registry entry の `:maturity` を fresh に確認（Addendum 82
の教訓の実践）、他セッションによる先行実装が無いことを確認してから
着手。HARD 不変条件は執行適合性領域で新規2本: **①注文数量上限算術**
（提案注文数量が登録済み account の `:max-order-size`（登録上限）を
超えないこと — 「登録上限を超える執行は無許可取引であってアクティブ
運用ではない」）、**②適合性レビュー完了の存在チェック**（account
は `:suitability-reviewed?` が true でなければ注文受付不可 —
「適合性レビュー未完了の account への注文受付は不適合執行であって
効率的サービスではない」）。README/business-model.md の Trust
Controls どおり `:approve-over-limit-trade`（登録上限超過の取引執行
はガバナー gate なしに実行不可）／`:approve-margin-call-liquidation`
（証拠金不足による強制清算は常に人間の sign-off が必要）は
confidence に関わらず常時 escalate。14 tests / 29 assertions green。
satellite main `8082ca82`。registry `:blueprint` 10 → 9 /
`:implemented` 144 → **145**（occupation main `8ad26a65`、pin
`4806881e`）。決定的 governor 原則 第 72 例 = **注文数量上限算術 +
適合性レビュー存在チェック + 常時escalate（超過取引執行・強制清算）**。

残る wave-2 batch #1 の `:blueprint` は9本: `3312`/`3321`/`3323`/
`3334`/`3332`（金融・仲介系 associate professionals）、`5414`
（protective services）、`8331`（drivers）、`9313`/`9334`
（労働者）。

## Addendum 85 (2026-07-14, 垂直第 73 号 = LoanUnderwritingActor — tick 96、並行セッションのテスト固定値破損を検出・修復)

**垂直第 73 号 = LoanUnderwritingActor（3312、README 記載名採用 —
Underwriting Advisor -> Loan Underwriting Governor）**。HARD 不変
条件は与信・融資実行領域で新規2本: **①承認額上限算術**（提案実行額
が登録済み application の `:approved-amount`（承認額）を超えない
こと — 「承認額を超える実行は無許可融資であって柔軟な対応ではない」）、
**②信用調査完了の存在チェック**（application は
`:credit-assessment-completed?` が true でなければ実行不可 —
「信用調査未完了の application への融資実行は情報不足の与信判断で
あって効率的サービスではない」）。README/business-model.md の
Trust Controls どおり `:approve-over-approved-disbursement`／
`:approve-loan-approval-override` は confidence に関わらず常時
escalate。14 tests / 29 assertions green。satellite main
`d678ee6f`。

**着地時に並行 wave-1 セッションとの衝突を検出**: registry 昇格前の
fresh 確認は問題なかったが、テスト着地直前に再実行した
`clojure -M:test` で失敗6件を検出——`kotoba.occupation.wave` 側の
固定 fixture（`maturity-tier`「a registry-only unit group entry is
:spec」と `maturity-roadmap`「a spec entry's next step is
blueprint」が参照していた `1112`）を、並行 wave-1 セッションが自分の
ティックで `:implemented` に昇格させ、かつ count-pin の期待値
（spec/implemented）を実データと不整合な値のまま commit していた
ため。**当て推量のデルタ計算をやめ、live query（`o/maturity-summary`）
で実測してから修復**: `1112` 参照を wave-4（現在誰も触っていない
安定圏）の `1411`（Hotel Managers、`:spec`）に差し替え、count-pin を
実測値（spec 278 / blueprint 9→8 / implemented 149→150）に合わせて
再検証・green 化してから着地。registry `:blueprint` 9 → 8 /
`:implemented` 149 → **150**（occupation main `2b6c9a2d`、pin
`4c5d50cb`）。決定的 governor 原則 第 73 例 = **承認額上限算術 +
信用調査完了存在チェック + 常時escalate（承認超過実行・与信判断
オーバーライド）**。

**運用メモ（Addendum 82 の教訓の追補）**: 着手前 fresh チェックだけ
では不十分——**着地直前にもう一度 fresh チェック**（ここでは
`clojure -M:test` の再実行）が必要。並行セッションは自分の作業中にも
割り込んでくる。テストの固定 fixture 値（特定 ISCO code を「これは
ずっと :spec のはず」と決め打ちする値）は、他レーンの並行昇格で
いつでも壊れうるため、崩れているのを見つけたら**その場で fresh
query に基づき最小修復してから着地**する（無視して素通りしない）。

残る wave-2 batch #1 の `:blueprint` は8本: `3321`/`3323`/`3334`/
`3332`（金融・仲介系 associate professionals）、`5414`
（protective services）、`8331`（drivers）、`9313`/`9334`
（労働者）。

## Addendum 86 (2026-07-14, 垂直第 74 号 = InsuranceBrokerageActor — tick 97、実コードバグの検出・修正 + pin 楽観ロックの実証)

**垂直第 74 号 = InsuranceBrokerageActor（3321、README 記載名採用 —
Insurance Advisor -> Insurance Brokerage Governor）**。実装直後の
`clojure -M:test` で **4件のテスト失敗（実コードバグ）を検出**:
`insurance.store` の docstring では `:risk-disclosure-attached?` を
application エンティティのフィールドと設計していたが、
`insurance.governor` の実装がそれを誤って proposal から
destructure しており、常に「リスク開示なし」扱いになっていた
（並行セッション由来ではなく、この tick 自身のコーディングミス）。
**その場で修正**: governor を `(:risk-disclosure-attached? a)`
（application レコード由来）に訂正し、advisor/test の対応する
proposal-level フィールドを除去して設計を一貫させてから green 化。
HARD 不変条件は補償上限・リスク開示領域で新規2本: **①補償上限
算術**（提案補償額が登録済み application の `:max-coverage-limit`
を超えないこと — 「登録上限を超える引受は無許可引受であって寛大な
補償ではない」）、**②リスク開示添付の存在チェック（application
レコード側）**（application は `:risk-disclosure-attached?` が
true でなければ引受不可 — 「リスク開示が添付されていない引受は
未開示補償であって効率的サービスではない」）。README/business-model.md
の Trust Controls どおり `:approve-over-limit-binding`／
`:approve-claims-settlement` は confidence に関わらず常時 escalate。
14 tests / 29 assertions green（修正後）。satellite main `ef4e50c0`。

registry `:blueprint` 8 → 7 / `:implemented` 150 → **151**
（occupation main `945f47bb`）。**west.yml pin PUT が1回目 409
（`manifest/west.yml does not match ...`）で弾かれた**——並行セッション
（おそらく wave-1）が同じファイルの別 project entry を同時に前進
させていたため。blob-sha 楽観ロックの設計どおり、fresh 再取得
（occupation entry 自体は変化なしを確認）→ 再構築 → リトライで
無競合に成功（pin `a4a84e6e`）。決定的 governor 原則 第 74 例 =
**補償上限算術 + リスク開示添付存在チェック（application レコード）
+ 常時escalate（超過引受・保険金支払）**。

**運用メモ（Addendum 82/85 の教訓の追補、3件目）**: 並行セッションが
関わるのは registry.edn だけでなく `manifest/west.yml` 自体も
（複数 project の pin を同じファイル内に持つため）。409 は設計どおりの
安全動作——慌てず fresh 再取得 → 再構築 → リトライする。今回は
occupation entry 自体は無傷だったので単純リトライで済んだが、万一
occupation entry 自体が他セッションに更新されていた場合は、その
新しい pin 値を起点に自分の差分を再計算する。

残る wave-2 batch #1 の `:blueprint` は7本: `3323`/`3334`/`3332`
（金融・仲介系 associate professionals）、`5414`（protective
services）、`8331`（drivers）、`9313`/`9334`（労働者）。

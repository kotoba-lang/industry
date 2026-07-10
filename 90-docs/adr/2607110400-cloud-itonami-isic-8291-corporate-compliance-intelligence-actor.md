# ADR-2607110400: cloud-itonami-isic-8291 — 全世界の企業・役職者(職務上)・関係性を Dossier-LLM ⊣ DisclosureGovernor で保持し契約者限定開示する corporate/compliance intelligence actor を新設

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

「全世界の企業・公人・政府・組織図・連絡先・人間関係を分析する actor」の要否を
問われ、既存 actor を横断調査した結果、該当するものは存在しないと判明した
(近傍だが別目的: `cloud-itonami-M6910`=法人設立の**執行**、`cloud-itonami-6310`
(旧 `gftd-talent-actor`)=**自社**従業員のみ、`ai-gftd-arms` の
`global-satellite-intelligence-actors.edn`=衛星OSINT・軍事施設/船舶検出限定
で個人の標的化は `:safety-boundary` により明示的にスコープ外、`kakure`=個人
データ露出の**削除**で逆方向)。

オーナーから「Palantir のように内部データとして保持し、cloud-itonami の産業
SaaS ごとに情報を保持して契約した user のみに開示する」という業態方針が示され、
これは実世界の **credit bureau / business-information-services**
(Dun & Bradstreet, Moody's Orbis(BvD), Refinitiv World-Check, LexisNexis Risk
Solutions 等)と同型の SaaS であることを確認した。`kotoba-lang/industry` の
registry (`resources/kotoba/industry/registry.edn`) を調べたところ、**ISIC
Rev.4 8291「Activities of collection agencies and credit bureaus」が
`:maturity :spec`(registry のみ、repo 未着手)で既に登録済み**であり、
`cloud-itonami-M6910`(6910 spec→実装)と同型の「未着手スロットを実装に
昇格させる」前例に倣うのが最も筋が良いと判断した。

情報スコープについてはオーナーに確認を取り、**「職務上の公開情報のみ」**
(法人登記・役員/取締役/UBO(公開分のみ)・政府機関の職位/役職者名・M&A/JV等の
公表済み取引関係・法人としての連絡先)を採用し、**私生活・家族関係・思想信条・
所在地追跡・SNS由来の推論プロファイル等は対象外**と決定した。この境界は
D&B/Moody's Orbis/World-Check という実在業態の適法性の根拠そのものであり、
`ai-gftd-arms` の `:safety-boundary`(標的選定・攻撃計画は対象外)や `kakure`
の PII ルール(私生活データを持たない)と同じ設計思想を踏襲する。

## Decision

新規 actor `cloud-itonami-isic-8291`(ISIC Rev.4 8291)を、`cloud-itonami-6310`
/ `cloud-itonami-M6910` / `robotaxi-actor` と同型の **「封じ込め + 独立
governor + 不変台帳」actor パターン**で実装し、`cloud-itonami` org 直下に
public/AGPL-3.0-or-later の open business blueprint として新設する(ADR-2607012100
の「新規 repo は直接 `cloud-itonami` org 直下に作成してよい」慣行に従う。
命名は ADR-2607051300 follow-up で確定した `cloud-itonami-isic-{code}` 表記を
最初から採用し、旧 `cloud-itonami-{code}` 表記は経由しない)。

### 1. Dossier-LLM ⊣ DisclosureGovernor(単一不変条件)

> **Dossier-LLM は、DisclosureGovernor が拒否する事実の書き込み・開示・
> 訂正確定を決して行わない。**

Dossier-LLM は以下 4 種の *proposal のみ* を返す助言者: エンティティ名寄せ
(entity-resolution)、関係性ドラフト(relationship-draft、出典引用必須)、
契約 tier 別開示列セット提案、訂正申立てへの解決案ドラフト。実書き込み・
実開示は独立した DisclosureGovernor の6チェックを通ってから台帳に commit
する(`cloud-itonami-6310` の PolicyGovernor、`cloud-itonami-M6910` の
RegistrarGovernor と同型)。

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1 | scope-gate | HARD | proposal が私生活/家族/思想信条/健康/性的指向/リアルタイム所在地 等のスキーマ外フィールドを含まないか(スキーマ自体にこれらのフィールドが存在しない = 構造的除外) |
| 2 | source-basis | HARD | 全ての事実/関係edgeが許可された出典クラス(公式法人登記・裁判記録・規制当局提出書類・ライセンス済みデータフィード・当事者自身のプレスリリース)を引用しているか。無出典・推論のみは拒否 |
| 3 | licensed-disclosure | HARD | `:disclosure/query` は有効な契約(tenant×tier×purpose)を持つ呼び出し元にのみ結果を返す。匿名/公開の問い合わせ経路は operation セットに一切存在しない |
| 4 | confidence-floor | SOFT→escalate | 名寄せ/関係性推論の確信度が閾値未満 → 人間レビューへ |
| 5 | high-stakes gate | SOFT→escalate | 対象が政府職員(職務上)・制裁/PEPフラグ付きの場合は常に人間承認へ(名誉毀損・政治的リスクの非対称性) |
| 6 | correction-request | HARD(常時 escalate) | データ主体(法人または職務上の役職者)からの訂正申立ては、どのフェーズでも自動解決を許さない(FCRA型のdispute権を構造で保証) |

### 2. Phase 0→3 + 恒久人間ゲート

`cloud-itonami-6310`/`M6910` と同じ段階導入。ただし **`:correction/request`
と、政府職員/制裁フラグ関連の `:disclosure/query`・`:relationship/draft` は
どの phase の `:auto` 集合にも入らない**構造的恒久ゲート(`M6910` の
`:filing/submit` 恒久人間専用と同型の実装不変条件)。

### 3. データモデル(職務上公開情報のみ、構造的スコープ)

`:entity/company`(法人登記事実)・`:entity/official`(氏名・役職・所属法人or
政府機関・capacity=officer|director|ubo|government-official、**職務上のみ**)・
`:entity/government-agency`・`:relationship/edge`(ownership|directorship|
employment|joint-venture|regulatory-oversight|business-contact、出典・時点
必須)・`:contact/business`(登記住所・IR・広報等の法人としての連絡先のみ)。
私生活フィールドはスキーマに存在しない。

### 4. R0 の正直なスコープ

`M6910` の「10法域のみ spec-basis」に倣い、R0 の出典カタログは実在する
無料/公開の一次情報源のみを載せる: 日本(法人番号公表サイト)、UK(Companies
House、officers/PSC 無料API)、Germany(Unternehmensregister)、Estonia
(e-Business Register)、USA(SEC EDGAR、上場企業のみ)、EU consolidated
financial sanctions list(制裁/PEPフラグの出典 — これ自体が政府公表データ
であり private surveillance ではない)。Store は `MemStore`/`DatomicStore`
(`langchain.db` 経由、`cloud-itonami-6310` と同じ swap 可能設計)。

### 5. cloud-itonami 産業SaaS群への卸し(オーナーの業態方針の実装)

`cloud-itonami-isic-8291` 自体を直接契約者へ販売するのに加え、他の
`cloud-itonami-{ISIC}` blueprint(175+ fleet)は本 actor を `:required-
technologies`/`:optional-technologies` の新規 capability keyword
`:corporate-intelligence` として宣言的に消費できる(`:identity`/`:audit-
ledger` が universal required-technology として全blueprintから参照される
のと同じ、ADR-2607051300 の vertical-capability-lib パターン)。各業種
blueprintが自前でデータを持たず、同じ DisclosureGovernor ゲート越しに
licensed 契約を通じて問い合わせる — 「産業SaaSごとに情報を保持し契約者限定
開示」というオーナー方針をそのまま構造化する。本ADRでは capability keyword
の宣言のみ行い、175 blueprintへの一斉配線は別ADRのフォローアップとする。

## Consequences

- (+) `kotoba-lang/industry` registry の 8291 スロットが `:spec` から実装へ
  昇格し、`M6910` に続く2件目の「昇格実例」になる。
- (+) 「保持は自由・開示は契約者限定」という Palantir 型の業態が、
  `cloud-itonami-6310`/`M6910` と同じ「封じ込め+独立governor+不変台帳」actor
  パターンの中に無理なく収まることを示した(新しいパターンの発明は不要だった)。
  disclosure=最大のリスク面という認識のもと、governor 名を Policy→Disclosure
  に、単一不変条件の対象を「書き込み」から「書き込み・開示・訂正確定」の3つに
  拡張した。
- (+) 私生活/家族/思想信条等の除外はスキーマレベル(存在しないフィールド)
  であり、実行時チェックだけに頼らない — `cloud-itonami-6310` の
  `protected-attrs` runtime チェックより一段厳格。
- (-) R0 の出典カバレッジは6ソースのみ(全世界のごく一部)。`M6910` と同じ
  正直な報告方針を採り、`facts/catalog` への追記でのみ拡大する(捏造禁止)。
- (-) Datomic/kotoba-server backend は次のシーム(未接続)。実運用の与信/
  制裁データベンダー統合・実法人登記APIとの実結線は operator の責任範囲。
- (-) 175 blueprintへの `:corporate-intelligence` capability 配線は本ADRの
  スコープ外(フォローアップ)。
- superproject への反映: 本 ADR のみ。`cloud-itonami-isic-8291` は既存の
  `cloud-itonami-{ISIC}` blueprint 群と同じ慣例により `manifest/repos.edn` /
  `manifest/west.yml` には登録しない(standalone)。`kotoba-lang/industry`
  registry.edn の 8291 エントリのみ実 repo URL へ更新する。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-8291/README.md` + `docs/DESIGN.md` +
  `docs/adr/0001-architecture.md`(実装側 ADR、本 ADR と対)
- `90-docs/adr/2607031500-cloud-itonami-m6910-global-incorporation-actor.md`
  (spec→実装昇格・R0正直スコープの先例)
- `cloud-itonami-6310`(旧 `gftd-talent-actor`)ADR-0001(封じ込め+governor+
  台帳パターンの原型)
- `90-docs/adr/2607051300-cross-org-capability-map.edn`(vertical-capability-lib
  パターン、`:required-technology-index`)
- `90-docs/adr/2606081510-arms-public-defense-industrial-and-satellite-osint-data.md`
  (`:safety-boundary` パターンの参照元)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`(id "8291"
  エントリ)

## Addendum 1 (2026-07-10): `:corporate-intelligence` 10-repo パイロット配線

§5 で「本ADRのスコープ外」としたフォローアップのうち、まず小規模パイロットを
実施した(オーナー選択: 288 repo 一斉ではなく 5〜10 repo で先に検証)。

**選定基準**: KYC/デューデリジェンス/カウンターパーティ確認が実際に業務ロジック
上意味を持つ、既に `:implemented`(scaffold のみでなく実actor)な金融・不動産・
保険系 vertical のみを選んだ(投機的に全 vertical へばら撒かない)。

| repo | 用途 |
|---|---|
| `cloud-itonami-isic-6810`(不動産仲介) | 買主/賃貸人の法人・受益所有者確認 |
| `cloud-itonami-isic-6910`(法人設立代行、M6910) | 申請者のKYC/制裁スクリーニング強化 |
| `cloud-itonami-isic-6499`(VCファンド) | 投資先/カウンターパーティのデューデリジェンス |
| `cloud-itonami-isic-6430`(信託/ファンド器) | カウンターパーティ/投資家確認 |
| `cloud-itonami-isic-6630`(ファンド運用) | 投資家/カウンターパーティ確認 |
| `cloud-itonami-isic-6512`(損害保険) | 商業契約者の引受デューデリジェンス |
| `cloud-itonami-isic-6621`(損害査定) | 請求者/カウンターパーティの法人確認 |
| `cloud-itonami-isic-6622`(保険仲介) | 商業顧客オンボーディング確認 |
| `cloud-itonami-isic-6420`(持株会社) | 子会社/資本構成の確認 |
| `cloud-itonami-isic-6419`(銀行) | 商業口座のKYC/AMLスクリーニング |

**実施内容**: 各 repo 自身の `blueprint.edn` の
`:itonami.blueprint/optional-technologies` に `:corporate-intelligence` を追記
し、当該 repo の `main` へ直接 commit+push(288 repo 一斉スイープではなく
10 repo の個別最小差分)。`kotoba-lang/industry` の registry.edn 側 10
エントリも同じ内容で追随させ(worktree+サーバ側マージ)、`industry_test.clj`
7 tests / 193 assertions・lint clean を確認済み。

**あくまで宣言のみ**: `:optional-technologies` への追記は「この capability を
使い得る」という宣言であり、各blueprint側のコード実装(実際に
`cloud-itonami-isic-8291` の XRPC/契約を呼ぶ統合コード)は行っていない —
それは各blueprintの実装フェーズでの独立作業。

**残り約278 repo への展開判断**: このADRでは判断しない。10 repoパイロットの
運用実績(実際にこのcapabilityが使われるか、宣言だけで終わるか)を見てから、
必要な業種だけ個別に追加するか、改めて一斉展開のADRを起票するかを判断する。

## Addendum 2 (2026-07-10): cloud-itonami-isic-6910 が最初の実統合(宣言のみでない)consumer になった

Addendum 1 の10 repoは `:optional-technologies` への**宣言のみ**だったが、
そのうち `cloud-itonami-isic-6910`(法人設立代行)について、実際に
`cloud-itonami-isic-8291` を呼ぶ統合コードを実装した。

### 統合内容

6910 の `formation.registrarllm/screen-kyc`(officer の KYC/制裁スクリーニング
draft)は、従来ローカルの自己申告フラグ `:sanctions-hit?` のみを見ていた
(実質デモ用のダミーフィールドで、どこからも裏取りされていなかった)。これに
新設の `formation.corporate-intel.cljc` を通じて 8291 の
`:disclosure/screen-name` を追加照会するよう配線した:

- 呼び出しは 8291 の**同じ** DisclosureGovernor ゲートを通る(6910側からの
  バイパスは無い)。8291 が実際にヒットを検出した場合、8291 **自身**の
  high-stakes gate が常に escalate する(8291 の人間レビュアーが確認するまで
  確定しない)ため、6910 側は 8291 の未承認の Dossier-LLM 生draftを覗き見せず
  `:pending-human-review?` をそのまま「不確定」信号として扱う。
- 結果として実際に得られる保証は「**サイレントに :clear にはならない**」で
  あり、「即座に hard-hold になる」ではない(8291自身が確定的な :hit を返す
  唯一のケースは、8291側の人間が既に承認済みの場合のみ)。この設計上の帰結は
  実装中に統合テストで発見され、それに合わせて8291の `propose-name-screen`
  自体も1点修正した(下記)。
- `formation.registrarllm/mock-advisor` は新オプション
  `:corporate-intel-screen`(officer名 → 8291照会結果の関数)を受け取る。
  既定値は no-op(`{:found? false :hit? false}` 固定)なので、**明示的に
  opt-in しない限り既存の全呼び出し元の挙動は一切変わらない**。

### 8291側の修正(統合テストで発見)

`propose-name-screen` の「未収載(not found)」結果が当初 confidence 0.5
(低確信・escalate)だったが、これは実世界の与信/制裁スクリーニング製品の
挙動と整合しない: 「自社データベースに一致なし」は確信度の低い推論ではなく、
**確定的で処理可能な陰性結果**である。低確信のままだと、R0の狭いカタログ
(officialが3件のみ)の外にいる、ごく普通の(何のリスクも無い)人物を
スクリーニングするたび毎回 escalate してしまい、自動化可能なスクリーニング
opの意味が無くなる。confidence を 0.85 に修正し、カバレッジの狭さは
`dossier.facts/coverage` 側の特性として別途正直に報告する(個々のクエリの
確信度を下げる理由にはしない)方針にした。8291 の既存テストも
この設計変更に合わせて更新(37 tests / 164 assertions、lint clean)。

### 実測結果

`cloud-itonami-isic-6910` の demo officer `o-4`("Jane Smith (demo)"、8291側
のsanctions-flagged demo officialと同名)は、ローカルのフィールドは全てクリーン
(`:sanctions-hit? false`、id-doc あり)——統合前は `:clear` として通っていた。
統合後は 8291 照会が pending-human-review を返し、6910 側も `:incomplete` に
留まる(`:clear` には決してならない)。69 tests / 311 assertions(6件新規)、
lint clean、demo (`clojure -M:dev:run`) で実際にこのフローを再現可能。

### 残り9 repo・約278 repo への展開判断

引き続き判断しない。まず6910の実運用(実際にこの照会が意味のある区別を
生むか)を見てから、他の9 repo(不動産・VC/信託/ファンド運用の三点セット・
損保三点セット・持株会社・銀行)への統合を個別に検討する。

## Addendum 3 (2026-07-10): パイロット10 repo中5 repoが実統合完了、残り5 repoは機械的横展開が不適と判明

オーナーの指示で「残り9 repoにも同様の実統合を順次展開」を実行した。
Addendum 2 の 6910 に続き、既存の `screen-kyc` 相当コードを詳細に読んだ
結果、9 repo は均質ではなく**2群に分かれる**ことが判明した:

### A群(4 repo): 6910と同型、機械的に横展開 — 並列4エージェントで実施

| repo | namespace | party概念 | 結果 |
|---|---|---|---|
| `cloud-itonami-isic-6810`(不動産仲介) | `realty` | party(buyer/seller) | commit `9ba0967`、30 tests/118 assertions |
| `cloud-itonami-isic-6499`(VCファンド) | `vcfund` | party(founder) | commit `8055f7e`、176 tests/661 assertions |
| `cloud-itonami-isic-6512`(損害保険) | `casualty` | party(policyholder) | commit `1158aa9`、40 tests/187 assertions |
| `cloud-itonami-isic-6419`(銀行) | `banking` | account(holder-name) | commit `12665c0`、44 tests/191 assertions |

いずれも `<ns>/corporate_intel.cljc` 新設 + `screen-kyc`/`screen-sanctions`
相当関数の `:else` 分岐にのみ `screen-fn` を追加注入(既存 `mock-advisor` の
無引数呼び出しは全て挙動不変を確認済み)、6910 と同じ「サイレントに clear/
resolved にはならない」保証。全4 repo で 0 failures/0 errors、`clojure -M:lint`
clean、`clojure -M:dev:run` 正常終了を確認。

`cloud-itonami-isic-6419` のみ語彙が異なる(`:unresolved`/`:resolved` のみで
`:incomplete` 相当が無い): governor の `sanctions-violations` が
`:verdict :unresolved` を**無条件・即時 HARD hold**として扱うため、8291側の
pending-human-review/held は(他3 repoのような soft escalate ではなく)
そのまま即時 hold に収束する — 銀行/AML ドメインとしてより保守的な、正当な
設計判断(意図的な仕様、バグではない)。

**副産物の不整合(低リスク、要調整)**: 4エージェントがそれぞれ独立に
「対象repoにCI workflowが無い場合どうするか」を判断した結果が割れた
(6512/6499=作成せず・欠落を明記、6810/6419=6910相当のci.ymlを新規作成)。
実害は無いが一貫性の観点で後日どちらかに統一する余地がある。

### B群(5 repo): 6910型パターンが構造的に不適合 — 今回は見送り

調査の結果、以下5 repoは「officer/party の名前を制裁/PEPリストと照合する」
という8291の `:disclosure/screen-name` の形と、そもそも噛み合わないことが
判明した:

- **`cloud-itonami-isic-6430`(信託/ファンド器)・`cloud-itonami-isic-6630`
  (ファンド運用)**: バックオフィス系actor(capital call・NAV・fee・carry の
  再計算・突合)で、そもそも人物名をスクリーニングする概念自体が無い
  (`sanctions`/`kyc`/`screen` のいずれも governor.cljc に一切出現しない)。
  統合すべき箇所が存在しない。
- **`cloud-itonami-isic-6420`(持株会社)**: `screen-beneficial-ownership` が
  あるが、これは子会社ポジションの「実質的支配者(UBO)情報が確認済みか」
  という**真偽フラグの検証状態**をスクリーニングするもので、人物名の
  制裁/PEP照合ではない。8291の `:relationship/edge`(ownership)データは
  概念的に近いが、`:disclosure/screen-name` とは別の新規クエリ形状
  (UBOチェーンの照会)が要る。
- **`cloud-itonami-isic-6621`(損害査定)・`cloud-itonami-isic-6622`
  (保険仲介)**: `conflict-of-interest` チェックがあるが、これは
  **査定人/仲介人自身**(claimant/insurerとの利益相反)をスクリーニングする
  もので、外部カウンターパーティのPEP/制裁照合ではない。8291の関係性グラフ
  (`:relationship/edge`)を使えば「この査定人はこの保険会社/請求者と
  役員/株主等の関係を持つか」を問えそうだが、これも `:disclosure/screen-
  name` とは別の新規クエリ(二者間関係の照会)の設計が要る。

この3 repo(6420/6621/6622)への統合は、8291側に**新しいクエリ機能
(UBOチェーン確認・二者間関係照会)を設計してから**でないと、
`:disclosure/screen-name` を無理に当てはめる誤った統合になる — 今回は
実施しない。6430/6630 は統合すべき箇所が無いため対象外。

### 現状まとめ

パイロット10 repo中: **5 repo が実統合済み**(6910・6810・6499・6512・6419)、
**2 repo は対象外**(6430・6630、統合箇所が無い)、**3 repo は新規8291
capability設計待ち**(6420・6621・6622、UBOチェーン/二者間関係照会)。
残り約278 repo・上記3 repoへの新capability設計は、いずれも本ADRでは
判断しない — 次のADRまたはフォローアップに委ねる。

## Addendum 4 (2026-07-10): 新規2 op を設計・実装し、残り3 repo も実統合完了 — パイロット10 repo中8 repoが実統合済み

オーナーの指示で、Addendum 3 が「8291側に新capability設計待ち」とした
`6420`(UBOチェーン)・`6621`/`6622`(二者間関係)を実際に設計・実装し、
パイロット10 repoの残り作業を完了させた。

### 8291側: 2つの新規 governed read op

`src/dossier/llm.cljc`/`policy.cljc`/`phase.cljc`/`store.cljc` に追加(いずれも
`:tier/graph` 必須、DisclosureGovernorの同じ licensed-disclosure ゲートを通る):

- **`:disclosure/ownership-chain`**(`{:company-id|:company-name ..}` →
  `{:owners [{:owner-id :pct :source :as-of} ..] :has-sourced-ownership-data?
  bool}`)— 対象法人へ向かう `:ownership` edge を1 hop 辿る。出典データが無い
  ことは「所有者がいない」ではなく「未収載」——清潔判定として扱わない。
- **`:disclosure/relationship-check`**(`{:person-name .. :company-id|
  :company-name|:target-person-name|:target-name ..}` → `{:related? bool
  :kind kw|nil}`)— 名前一致した official の `:org` 一致、または関係edge
  (1 hop)で判定。設計途中で判明した重要な一般化: `:target-name` は
  company-by-name→official-by-name の順で両方試すため、呼び出し側は
  カウンターパーティが法人か個人か知らなくてよい(`cloud-itonami-isic-
  6621`/`6622` の `party` レコードは両方を同じ形で保持するため必須だった)。

新規 `dossier.store/company-by-name`(`official-by-name` と対称)+ demo
relationship edge を2本追加(co-200→co-300 所有60%、of-1→co-200 役員兼務、
of-2→of-1 business-contact)。8291自体: 55 tests / 213 assertions、lint
clean、demo op7/op8 追加。commit `c91691c` → `2152c07` → `448151b`。

### 残り3 repoの実統合

| repo | 統合先 | 検証結果 |
|---|---|---|
| `cloud-itonami-isic-6420`(持株会社) | `:disclosure/ownership-chain`(subsidiary-name で照会) | co-300 の実所有者(制裁フラグ付きco-200、60%)を検出 → `:beneficial-ownership-verified? false` → **即時 HARD hold**(このrepoの検証語彙は真偽2値のみで中間状態が無く、governor が無条件hardなため。40 tests/195 assertions、commit `4109e99`) |
| `cloud-itonami-isic-6621`(損害査定) | `:disclosure/relationship-check`(`:matter-id` 任意追加、adjuster名×counterparty名) | エージェントが実行して判明: 山田一郎↔Jane Smithの直接edgeは`related?=true`を返すが、どちらも個人としては制裁フラグを持たないため8291は即時commit(escalateしない)→ 6621自身のunconditional hard-hold(`:verdict :hit`)へ着地。pending-review/heldの2経路はスタブで別途決定的に検証。30 tests/123 assertions、commit `4c628f9` |
| `cloud-itonami-isic-6622`(保険仲介) | `:disclosure/relationship-check`(`:placement-id` 任意追加、broker名×customer名) | エージェントが6621と異なる、より的確なペアリングを選択: 山田一郎(co-200役員)×Northwind社(co-200、制裁フラグ付き法人そのもの)→ **実際に8291側でescalateが発火**(`:reason :high-stakes`)→ 6622側も`:incomplete`でescalate。40 tests/193 assertions、commit `b61854d` |

**2つのエージェントが独立に、ブリーフ執筆時の私の想定(どのデモペアリングが
8291のescalateを発火させるか)が誤りだったことを実行して発見し、正しく
補正した**(6621は代替のスタブ検証で、6622はより的確な法人ターゲットへの
差し替えで)——スクリプト通りに進めるのではなく、実行結果で検証する姿勢が
機能した実例。

`:matter-id`/`:placement-id` はどちらも既存リクエスト形状への**後方互換な
オプション追加**(省略時は完全に元の挙動)。

### 現状まとめ(更新)

パイロット10 repo中: **8 repo が実統合済み**(6910・6810・6499・6512・6419・
6420・6621・6622)、**2 repo が対象外のまま**(6430・6630、統合すべき箇所が
構造的に存在しない)。残り約278 repoへの展開判断は引き続きこのADRでは
行わない。

## Addendum 5 (2026-07-10): 実データソース(UK Companies House)

オーナーの選択で、実 API を最初に接続する法域として UK Companies House を
採用した(無料・APIキー登録のみで利用可能)。APIキーは1Password/Keychainを
検索したが本ワークスペースには存在しなかった(`manifest/repos.edn` の秘密情報
マップにも記載無し)。オーナーの指示通り、キー未取得のまま実装のみ進めた。

### 実装

- **`dossier/companies_house.clj`**(新規、平文 `.clj` — `.cljc` ではない):
  http-kit + jsonista(`kotoba-lang/langchain` の `langchain.jvm/jvm-http-fn`
  と同じ host-fn 注入型パターン)。当初 `.cljc` + `#?(:clj ...)` で書いたが、
  require句が全て `:clj` 限定のためclj-kondoの `:cljs` 側解析でrequire句が
  空になり lint error("Invalid require: no libs specified")が発生 — 平文
  `.clj` に変更して解決(`talent.facts.clj` と同じ判断: フェッチ/ネットワーク
  関心事はポータブルなactor coreではなくdev/ops seamという扱い)。
  - 認証: `COMPANIES_HOUSE_API_KEY` 環境変数のみ(ハードコード禁止)。
  - fetch関数は注入可能(`{:path :query} -> parsed-json|nil`)— テストは
    偽のfetch関数で完全オフライン実行、実キー・実通信は一切不要。
  - スコープは正直に限定: `company-by-name`(検索の完全一致)+ 既知法人IDへの
    `officials-of`(officers一覧)のみ。`official-by-name`(人物名グローバル
    検索)は**未実装**(Companies Houseの`/search/officers`はcompany文脈を
    返さず、候補ごとに`/officers/{id}/appointments`への追加呼び出しが要る
    ため、中途半端に作らず明示的に見送った)。つまり `:disclosure/screen-
    name`(パイロット5 repoが実際に呼ぶop)はまだライブデータの恩恵を受けない。
- **`dossier/live_store.cljc`**(新規): `Store` プロトコルのデコレータ。
  local(Mem/Datomic)Store を包み、`company`/`company-by-name`/
  `officials-of` の3メソッドのみ「localに無ければlive APIを試す」に拡張。
  **localの回答は常に優先**(追加のみ、既存の回答を変更しない)。fetch関数が
  `nil`(キー未設定)の場合は undecorated な local store と完全に同じ挙動。
- `dossier.facts/coverage` に `:live-capable-jurisdictions`(現状 `#{:gbr}`
  のみ)を追加 — 静的な「実装済みコードの有無」であって「今キーが設定されて
  いるか」の実行時チェックではない、と明記。

### 検証状況(正直な限界)

68 tests / 253 assertions(13件新規)、lint clean、demo正常動作。**ただし
実 Companies House API に対しては未検証**(構築時点で実キーが無かったため)。
operatorは本番投入前に自分のキーで疎通確認する必要がある(`docs/operator-
guide.md` に明記)。commit `343fadc`。

## Addendum 6 (2026-07-10): フリート横断サーベイで4 repoを追加発見

オーナーの指示「2,3」(フリート展開判断 + 実データソース)のうち、フリート
展開判断側を実施した。

### 方法論(重要な発見)

当初、`kotoba-lang/industry` registryの既存コメント(例:
`cloud-itonami-isic-6419`のコメントが「`sanctions-violations`... 6511/
6512/6621/6622/.../9603/9602/.../8569 と同型」のように多数の一見無関係な
vertical名を列挙)から、これらすべてが corporate-intelligence の候補に
見えた。しかし実際に `cloud-itonami-isic-9603`(葬儀)の governor.cljc を
確認したところ、対応する概念は `authorization-unverified-violations`
(遺族の代理権限の確認)であり、**"sanctions-violations" という名前も
制裁/PEPスクリーニングという概念も一切登場しなかった** — 上記コメントが
指していたのは「未条件hard-hold」という**構造的パターンの再利用**であって、
KYC/制裁スクリーニングという**意味的概念の再利用ではない**と判明した。

この誤った早期仮説をそのまま採用していれば、無関係な数十repoへ誤った
統合を宣言するところだった。教訓を踏まえ、実装済み(`:maturity :implemented`)
111 blueprint全件のgovernor.cljcを個別に確認し、実際に「外部の当事者/
カウンターパーティをsanctions/PEP/利益相反の観点でスクリーニングする」
という8291のデータモデルと**意味的に一致する**check実装を持つものだけを
抽出した。

### 発見された4 repo(宣言のみ、実統合コードは未実装)

| repo | check | 一致理由 |
|---|---|---|
| `cloud-itonami-isic-6411`(中央銀行) | `correspondent-banking-due-diligence-unresolved-violations` | コルレス銀行のカウンターパーティ(実質的支配者/制裁リスク)デューデリジェンス、実在するAML/CFT概念 |
| `cloud-itonami-isic-6511`(生命保険) | `sanctions-violations`(`:insured`/`:beneficiaries`) | `cloud-itonami-isic-6512`と文字通り同一構造 |
| `cloud-itonami-isic-6612`(証券仲介) | `conflict-of-interest-violations` | ブローカー自身の利益相反、`6621`/`6622`と同型 |
| `cloud-itonami-isic-6920`(会計/監査) | `independence-violation-violations` | 監査人独立性(実在するSEC/PCAOB型規制概念)、`6612`/`6621`/`6622`と同型 |

いずれも `blueprint.edn` + `kotoba-lang/industry` registry へ
`:corporate-intelligence` を宣言(実統合コードは今回実装せず、パイロット
Addendum 3と同じ「宣言のみ」段階)。4 repo個別push + industry側1バッチ
commit、7 tests/217 assertions、lint clean。

### 除外した候補とその理由(誤判定を防ぐため明記)

調査した他9 candidateは意味的に不一致と判断し除外:
`6491`(リース、`adverse-credit-flag` = 与信スコアであり8291の制裁/PEP/
所有権データとは別概念)・`6492`(与信審査、承認/返済能力チェックでKYCでは
ない)・`6520`(再保険、財務照合中心)・`6530`(年金、`proof-of-life`は
KYCと無関係)・`6611`(市場監視フラグ、取引パターン異常でPEPと無関係)・
`6619`(カード、`fraud-flag`は取引詐欺でPEPと無関係)・`6629`(保険補助、
配分照合中心)・`6820`(不動産手数料、契約管理中心)・`9200`(賭博、
`patron-flag`が自己排除リストかAMLかコード上判別不能なため保留)。

### 残された判断

上記9候補・その他の非implemented(scaffold段階)blueprint・残り約278 repo
全体への展開は、引き続きこのADRでは判断しない。4 repoの実統合コード配線
(パイロットと同じパターンが機械的に適用できる可能性が高い)も未着手。

## Addendum 7 (2026-07-10): サーベイで見つけた4 repoも実統合完了

「next」の指示で、Addendum 6 で宣言のみだった4 repo(`6411`・`6511`・
`6612`・`6920`)を実統合した。

### 8291側の追加拡張: `:disclosure/query` の `:company-name` 対応

配線着手前に、`6920`(会計/監査、クライアント法人名で照会)・`6411`
(中央銀行、コルレス銀行名で照会)の2 repoには8291のid не持たない「名前
だけ知っている」ケースが必要と判明。既存の `:disclosure/screen-name`/
`:disclosure/ownership-chain`/`:disclosure/relationship-check` が既に
持つ「`*-by-name` 解決」パターンを `:disclosure/query`(企業プロファイル
照会)にも拡張: `:company-id` に加え `:company-name`(`company-by-name`
経由)を受理し、返り値に `:value {:company-id :flags}` を追加(従来
`:disclosure/query` の proposal には `:value` が無かった — `report/
render-profile` の別経路の列描画は無変更のまま追加)。70 tests/257
assertions、commit `b8adf8c`。

### 4 repoの実統合結果

| repo | 統合先op | 照会対象 | 語彙 | 実測結果 |
|---|---|---|---|---|
| `cloud-itonami-isic-6511`(生命保険) | `:disclosure/screen-name` | party.name | 3値(hit/incomplete/clear) | `6512`と同型、そのまま複製。30 tests/118 assertions、commit `233dcfd` |
| `cloud-itonami-isic-6612`(証券仲介) | `:disclosure/screen-name` | account.client(個人名) | 3値 | 実測: Jane Smith(デモ、co-200役員)への照会が実際にescalate(`:high-stakes`)→ `:incomplete` へ着地(サイレントに`:clear`にはならない)。42 tests/180 assertions、commit `586abfa` |
| `cloud-itonami-isic-6920`(会計/監査) | `:disclosure/query`(company-name) | engagement.client(法人名) | 3値 | 実測で判明した重要な階層区別: **8291自身**の監査理由は `:high-stakes`(sanctions-flagを検出したため)だが、**6920自身**のgovernorはその提案の confidence 0.5 のみを見て独立に `:low-confidence` と判定 — 2層は別々の根拠で escalate する、意図した設計どおり。44 tests/189 assertions、commit `5e8d1e1` |
| `cloud-itonami-isic-6411`(中央銀行) | `:disclosure/query`(company-name) | member.member-name(コルレス銀行名) | 2値(true/false のみ、中間状態なし) | 実測: 8291自身はescalateするが、6411自身のgovernorは無条件hard-checkのため**即時hold(interrupt無し)** — `6419`/`6420`と同じ collapse。47 tests/205 assertions、commit `8a2339b` |

### 現状タリー(更新)

サーベイで特定した14 repo(パイロット元10 + Addendum 6の4)のうち、
**12 repoが実統合済み**(6910・6810・6499・6512・6419・6420・6621・6622・
6511・6612・6920・6411)。残る2 repo(`6430`・`6630`)は統合すべき箇所が
構造的に存在しないため対象外のまま(Addendum 3の判断を維持)。

Addendum 6で除外した9候補・その他の非implemented blueprint・残り約278
repo全体への展開は、引き続きこのADRでは判断しない。

# ADR-0018: MoneyForward 置換会計エンジンを Datomic / Clojure / ClojureScript で設計する

- **Status**: Accepted(境界=オプション1「併存」で確定。基盤スキーマ `:gftd.acct/*`/`:gftd.bank/*`/`:gftd.fin/*` 実装・ロード検証済み。仕訳エンジン本体は未実装)
- **Date**: 2026-06-17
- **Deciders**: 河崎純真
- **Context tags**: accounting, moneyforward, datomic, kotoba-datomic, clojure, clojurescript, double-entry, ledger, edn, content-addressed, m365-fact-layer
- **Related**: ADR-0010(EDN 事実層)、ADR-0012(M365 EDN 事実層)、ADR-0013(kotoba-datomic 埋め込みクエリ層)、ADR-0017(gftd-keiei-sim)、**etzhayyim ADR-0076(moneyforward-actor-replacement / RisingWave+Python+BPMN)**

## Context

MoneyForward(会計・請求・支払・口座・経費・人事)を自前システムで置換する取り組みは、
既に **etzhayyim ADR-0076「moneyforward-actor-replacement」** として設計・実装が大半完了している。
ただしそのスタックは:

| 層 | ADR-0076 の採用技術 |
|---|---|
| 永続化 | **RisingWave**(Postgres 互換ストリーミング DB, `RW_DSN`) |
| プロセス | **SpiffWorkflow / Zeebe BPMN**(kaikei/seikyu/keiyaku/kousuu/keihi/jinji の 19 プロセス) |
| ハンドラ | **Python**(`pymagatama.ingest.{kaikei,kouza,seikyu,...}`) |
| エージェント | Clojure(kotoba)— 一部 |
| UI | ClojureScript + Svelte |

一方、当ドメインの中核資産は **Datomic 系**である:

- **m365 EDN 事実層**(ADR-0012): `facts/invoice-terms.edn`(請求 3,075 件)、`contract-terms.edn`、
  `docs.edn`、`crm.edn`、本 ADR と同時に追加した **`finance-summary.edn`**(取引先 669・月次 105、
  AR ¥454M / AP ¥520M / net −¥65M)。全行が CID(git-annex key)で内容アドレス化済み。
- **kotoba-datomic**(ADR-0013): Rust 製・内容アドレス型 Datalog。原子は 5-tuple Datom
  `(E,A,V,T,Added)`、`as_of`/`since`/`history`/EAVT、トランザクション基底時刻 = `KotobaCid`。
  Tauri/WASM アプリに**クレート埋め込み**。
- **gftd-keiei-sim**(ADR-0017): ClojureScript UI + `agents/finance.clj`(ReAct + kotoba-datomic 連動)。

要望は「MoneyForward に変わるシステムを **Datomic / Clojure / ClojureScript** で」。本 ADR は、
ADR-0076(RisingWave/Python)に対する **Datomic ネイティブな会計エンジンの設計図**を定義する。

### なぜ会計は Datomic に向くか

複式簿記は本質的に **追記専用の不変仕訳(immutable journal)+ 任意時点の残高照会**である。
これは Datomic のデータモデルとそのまま一致する:

- **不変 datom = 監査証跡**。仕訳は訂正も削除もしない(反対仕訳=赤伝で打ち消す)。Datomic は
  retraction も履歴に残る追記専用で、会計の「訂正可能だが消去不能」要件に合致。
- **`as-of` / `history` = 任意時点の試算表**。月末・期末・任意日の B/S・P/L を**再計算なしに**
  時間遡及クエリで得る。MoneyForward の「過去帳簿の凍結」を DB 機能として持つ。
- **reified transaction = 仕訳ヘッダ**。各トランザクションエンティティに伝票番号・起票者・
  証憑 CID・承認状態を属性として付与でき、明細(datom)と一体で来歴管理できる。
- **CID 基底時刻**。証憑(請求書 PDF / メール .eml)は既に CID。仕訳 → 証憑 CID → B2 実体まで
  内容アドレスで解決でき、ADR-0010/0012 の事実層と同一同一性で接続。

## Decision

**Datomic 互換の kotoba-datomic を台帳エンジン、Clojure を記帳・計算ドメイン、ClojureScript を
UI とする 3 層の会計システムを設計する。** 既存 m365 事実層を種データとして transact する。

```
┌─────────────────────────────────────────────────────────────┐
│ UI 層   ClojureScript (re-frame) + kotoba/Datalog 直 query    │
│   会計ダッシュボード / 仕訳入力 / 試算表 / AP・AR エイジング │
│   口座残高 / 請求書発行 / MF パリティビュー                  │
├─────────────────────────────────────────────────────────────┤
│ ドメイン層  Clojure                                          │
│   posting-rules(取引→複式仕訳)/ 消費税・源泉・インボイス  │
│   AP/AR 消込 / 銀行口座照合(reconcile)/ 期間締め / 法定帳票 │
├─────────────────────────────────────────────────────────────┤
│ 台帳層  kotoba-datomic(内容アドレス型 Datalog, 追記専用)    │
│   datom (E,A,V,T=CID,Added) / as-of / history / pull         │
├─────────────────────────────────────────────────────────────┤
│ 種データ  m365 EDN 事実層(ADR-0012)                         │
│   invoice-terms / finance-summary / contract-terms / accounts │
└─────────────────────────────────────────────────────────────┘
```

### スキーマ(`:gftd.acct/*`)— 設計

複式簿記コア + MoneyForward 6 アクター対応。既存 `:gftd.invoice/*` `:gftd.contract/*` を再利用。

```clojure
;; 勘定科目(chart of accounts)
{:gftd.acct/code        "現金/普通預金/売掛金/買掛金/売上高/外注費 等のコード"  :unique :identity}
{:gftd.acct/name        :string}
{:gftd.acct/type        :enum #{:asset :liability :equity :revenue :expense}}
{:gftd.acct/parent      :ref}                 ; 階層勘定

;; 仕訳(journal) — reified tx がヘッダ、entry が明細
{:gftd.journal/no       :string :unique :identity}  ; 伝票番号
{:gftd.journal/date     :instant}
{:gftd.journal/memo     :string}
{:gftd.journal/voucher  :ref}                 ; 証憑 → :gftd.doc/blob → CID(B2 実体)
{:gftd.journal/status   :enum #{:draft :posted :reversed}}
{:gftd.journal/by       :ref}                 ; 起票者 → :person/email
{:gftd.entry/journal    :ref}
{:gftd.entry/account    :ref}                 ; → :gftd.acct/code
{:gftd.entry/debit      :bigdec}              ; 借方(消費税込/抜は税属性で)
{:gftd.entry/credit     :bigdec}              ; 貸方(借方+貸方の総和=0 を不変条件に)
{:gftd.entry/tax        :enum #{:taxable10 :taxable8 :exempt :withholding}}
{:gftd.entry/counterparty :ref}               ; → org/person(AP/AR 紐付け)

;; 口座(kouza)— 自社/取引先の銀行口座(原本請求書から抽出 → accounts.edn)
{:gftd.bank/id          :string :unique :identity}
{:gftd.bank/holder      :string}              ; 名義
{:gftd.bank/bank        :string}              ; 銀行名
{:gftd.bank/branch      :string}              ; 支店
{:gftd.bank/type        :enum #{:futsu :touza}}
{:gftd.bank/number      :string}
{:gftd.bank/owner       :ref}                 ; 自社 or 取引先 org
{:gftd.bank.txn/...}                          ; 入出金明細(照合用)

;; 請求/支払(seikyu/keihi)— 既存 :gftd.invoice/* を AP/AR 区分付きで継承
{:gftd.invoice/direction :enum #{:ar :ap}}    ; finance-summary の分類を昇格
{:gftd.invoice/paid     :ref}                 ; 消込先 journal
{:gftd.invoice/due      :instant}

;; 期間 / 法定 / MF パリティ
{:gftd.period/yyyymm    :string :unique :identity}
{:gftd.period/closed    :boolean}             ; 締め(以降の datom 投入を rule で拒否)
{:gftd.acct.parity/mf-export :ref}            ; MF エクスポート CID と突合(二重実行検証)
```

### 種データ投入(seed)パイプライン

m365 事実層 → Datomic transact(Clojure ローダ、`datomic/src/m365/load.clj` を拡張):

1. `finance-summary.edn` → 取引先(org)+ 月次集計の検算用基準値。
2. `invoice-terms.edn` → `:gftd.invoice/*`(direction は issuer の自社判定で AR/AP)。
3. `accounts.edn`(原本請求書 PDF から haiku 抽出予定)→ `:gftd.bank/*`。
4. `contract-terms.edn` → `:gftd.contract/*`(継続課金・自動更新 → recurring 仕訳の予定生成)。
5. 証憑は全て CID 参照(原本は B2 / annex)。

### クエリビュー(L3, `datomic/queries/views.edn` 拡張)

- **試算表(as-of)**: 任意日時点の勘定別借貸残高。`(d/as-of db inst)` → 勘定で集計。
- **AP/AR エイジング**: 未消込請求を due からの経過日でバケット化。
- **総勘定元帳**: 勘定コード起点に仕訳明細を時系列 pull。
- **口座残高**: `:gftd.bank.txn` 累計 + 未達調整。
- **MF パリティ**: 同期間の MF エクスポート(CID)と内部試算表の差分(ADR-0076 の
  `validateMoneyForwardParity` に相当する Datalog 版)。

## ADR-0076 との関係(coexistence / 代替)— **決定: オプション1「併存」**

二者択一ではなく**役割分担**とする。検討した 3 オプション:

1. **併存【採用】**: ADR-0076(RisingWave/Python/BPMN)を**取引処理・対外連携(Peppol/
   インボイス送付/銀行 API/勤怠・給与)**の実行系として残し、本 Datomic 台帳を
   **SSoT 元帳・監査・分析・経営シミュレーション(keiei-sim)連携**として置く。
   両者は CID で証憑を共有し、`validateMoneyForwardParity` と Datalog パリティで相互検算する。
2. 段階置換: kaikei(GL/試算表/法定帳票)から Datomic に寄せ、seikyu/keihi/jinji は当面 0076。
3. 全面 Datomic: BPMN を Clojure posting-rules + 状態機械(ADR-0015)で再実装。

**採用理由(オプション1)**: 対外プロトコル(電子インボイス送付・銀行 API・給与/年末調整)は
0076 側に実装・検証が既にあり、Datomic で作り直す価値が薄い。一方、監査・時間遡及・証憑トレース・
経営分析は Datomic の独擅場。よって**「Datomic = 真実の台帳/分析」「0076 = 取引実行/対外」**の
境界が最小コスト・最大価値。境界は将来オプション2へ段階移行しうる(再評価条件: 0076 の
RisingWave 運用コスト or パリティ乖離が閾値超過時)。

## Consequences

**Positive**
- 監査・時間遡及・証憑トレースが DB ネイティブ(会計の本質要件に一致)。
- 既存 Datomic 資産(kotoba-datomic / m365 facts / keiei-sim)と同一同一性で接続、CID で証憑解決。
- アプリ内蔵(Tauri/WASM)で別 DB サーバ不要、オフライン・ローカルファースト。
- EDN-lines 事実層からの seed が容易、round-trip 検証可能。

**Negative / リスク**
- ADR-0076 と機能重複 → 役割境界(オプション 1〜3)を先に確定しないと二重保守。
- 対外プロトコル(Peppol/電子インボイス/銀行 API/給与・年末調整)の実装は Datomic では薄く、
  結局 0076 側 or 新規ハンドラが要る。
- `bigdec` 金額・消費税端数・源泉の丸めは Clojure 側で厳密実装が必要(MF 互換性試験必須)。
- kotoba-datomic の集計クエリ性能(全期間試算表)は要ベンチ。

## 未決事項(Open Questions)

1. ~~ADR-0076 との境界~~ → **オプション1「併存」で確定(本 ADR)**。
2. 法定帳票・電子帳簿保存法(タイムスタンプ/検索要件)対応をどちらのスタックで満たすか。
3. 金額精度・税計算の MF 互換テストデータ(MF エクスポート CID)の準備。
4. 仕訳生成 posting-rules(請求/支払 → 複式仕訳)の自動化範囲と承認フロー。

## 進捗 / 次アクション

実施済み(2026-06-17):
- [x] **口座抽出** `facts/accounts.edn`(87 口座: 自社受取 7 + 取引先 80。`bin/extract-accounts.py`
      = 原本 PDF / `bin/refresh-current-billing.py` = recent メール添付)。
- [x] **請求集約** `facts/finance-summary.edn`(取引先 669・月次 105、AR ¥454M / AP ¥520M)。
- [x] **基盤スキーマ** `:gftd.acct/*` `:gftd.bank/*` `:gftd.fin/*` + `:gftd.invoice/direction` を
      `datomic/schema-m365.edn` に追加。ローダ(`load.clj` の `bank-tx`/`fin-tx`)で投入検証
      (accounts 87 / fin-cparty 669 ロード成功)。
- [x] Datalog ビュー `views.edn`: `:accounts/self-receiving`(今の口座)、`:accounts/vendor-payee`、
      `:finance/counterparty-rollup`、`:invoices/payables` を追加・稼働確認。
- [x] ADR-0076 境界をオプション1で確定 → 本 ADR Accepted。

残:
- [ ] 勘定科目マスタ(`:gftd.acct/code`)シードと posting-rules で請求 → 仕訳の自動起票。
- [ ] 試算表(as-of)・AP/AR エイジングの Datalog ビュー(仕訳投入後)。
- [ ] ClojureScript 会計ダッシュボード(keiei-sim の views を拡張)。
- [ ] MF パリティ照合(0076 エクスポート CID vs Datomic 試算表)。

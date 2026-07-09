# cloud-murakumo(Sora)— CN / US / EU sales & marketing plan

**As-of**: 2026-07-05
**Author**: Jun Kawasaki (drafted with Claude)
**Status**: draft — first cut, meant to be gated against real evidence, not a forecast
**Builds on**: ADR-2607030030(推論経済 GTM, 三面市場・フェーズ順序)、
ADR-2607052200(国別 storefront + Stripe Checkout 実装)、
`90-docs/business/cloud-murakumo-business-model.md`(生成 BMC canvas)、
`90-docs/business/maturity-scores.md`(現状 distribution=weakest dimension)

## 0. 前提として直視する現実(捏造ゼロ)

- **現在のトラフィックはほぼゼロ**: `90-docs/business/metrics/cloud-murakumo.edn` 実測
  11,201 req/7d・278 uniques(日次和)。これは全ソース合算(内部アプリの API 呼び出しも
  含む)であって、外部からの認知経由の流入ではない。ブランド認知は事実上ゼロから始まる。
- **`distribution` が全指標で最弱**(`maturity-scores.md`: YC bench distribution 軸が
  低スコア、revenue=0.0, validation=0.0)。この計画は「その穴を埋める」ためのもので、
  存在しない実績を前提にしない。
- **チームは実質 1 人(創業者)+ Claude Code セッション**。広告予算・マーケティングチームを
  前提にした計画は書かない。低コスト/オーガニック中心、時間コストが主な投資。
- **raw $/token では勝てない**(ADR-2607030030 の既存の誠実な前提)。売るのは FLOPS では
  なく (1) 遊休ハードの限界費用ゼロ活用 (2) 主権・監査可能推論 (3) GPU/PC
  オーナーシップの節税角度。この 3 本の訴求を国ごとに使い分ける。

## 1. 国別の優先順位(結論から)

**US → EU → CN の順**で立ち上げる。理由:

- US は Stripe 決済がそのまま機能し(USD, card。分割払いは 2026-07-06 時点で
  非対応と決定 — 下記参照)、Bonus Depreciation という即効性のある訴求材料が
  Q4(12/31 taxable-year-end)に向けて時限性を持つ。
- EU は決済は US と同型(EUR, card)だが、訴求(AI Act/GDPR 監査台帳)は
  エンタープライズ営業サイクルが長く、成果が出るまでの時間が US より長い。
- **CN は今回のスコープでは "watch" に留める(能動的な広告出稿はしない)。**
  storefront の決済は Alipay/WeChat Pay 経由でも実際は USD 建て決済(Stripe が CNY
  決済通貨を持たないため、ADR-2607052200)であり、中国本土の一般的な B2B 決済慣行
  (対公帳戸・银联)とは別物。加えて中国本土向けにサービスを本格提供するには
  ICP 備案・データ越境規制など Stripe 決済導線だけでは解決しない論点がある。
  「中国語 LP を出す」こと自体は無コストで済むが、**実弾(広告費・現地パートナー契約)を
  投じる前に、この決済/規制ギャップを潰すのが先**、というのが正直な評価。

## 2. US — 最優先

### ターゲット
- indie AI/ML 開発者・スタジオ(個人事業主〜数名の LLC/S-corp)
- 生成AI/クリエイティブスタジオ(GPU 常時稼働の需要がある小規模チーム)
- 節税を探している IT 資産購入担当(CFO/controller クラスの兼務者)

### 訴求(フック)
「年末までに GPU/サーバーを買えば、Bonus Depreciation で今期の課税所得から
即時控除できる。しかも買った実機は cloud-murakumo のフリートに入って稼働収益を生む
— 資産が寝ない」。**税務助言ではない旨は storefront 既存の disclaimer で担保済み。**

### チャネル(低コスト優先)
1. **技術ブログ + Hacker News / X(Twitter)** — 「idle Apple Silicon を OpenAI 互換
   推論クラウドに変える」という技術記事(exo/petals が手薄な領域という ADR-2607030030
   の主張をそのままコンテンツ化)。実装が実在する(murakumo.cloud playground が
   その場で動く)ことが差別化 — デモリンクを常に併記する。
2. **Q4 tax-season タイミングのニュースレター/告知** — 会計士・スタートアップ CFO
   コミュニティ(Indie Hackers, r/smallbusiness, r/accounting 等)への「Bonus
   Depreciation × GPU」記事の直接投稿(広告費ゼロ、コミュニティガイドライン厳守)。
3. **既存 design partner(gftd 自社フリート)の実データを事例化** — ADR-2607030030 の
   GTM 方針どおり、自社利用の実測(tok/s・稼働率)をケーススタディとして公開する。
   これは追加コスト無しで「実際に動いている」証拠になる。
4. ~~OSS CLI → cloud upsell(`curl murakumo.cloud/join | sh`)~~ — 2026-07-06
   実測: `murakumo.cloud/join` は 404(実装なし)。ADR-2607030030 の一般ユーザー
   参加導線は Phase 2/3 のビジョンであって、この Sora ドメインには未実装。
   誤って公開ブログ記事に載せていたため削除・修正済み(`try before you buy` =
   `#console` playground を無料の試用導線として使う、に置換)。この Channel 項目
   は「実装されたら」復活させる。

### 90日プラン(具体)
| 週 | アクション |
|---|---|
| 1–2 | Bonus Depreciation 訴求のブログ記事 1 本執筆・公開、`#store/us` への内部リンク |
| 3–4 | HN/X 投稿(記事 + playground デモリンク)、反応(コメント/流入)を実測 |
| 5–8 | 自社フリート実測ケーススタディ公開、会計士/スタートアップコミュニティへの直接投稿 |
| 9–12 | Q4 tax-season 直前の再告知、`#store/us` の checkout 開始率/完了率を実測開始 |

### 成功指標(捏造しない — 計測できるものだけ)
- `#store/us` へのユニーク訪問数(Cloudflare zone 実測、既存の `collect.bb` 収集経路)
- Stripe Checkout Session 作成数 → 完了数(コンバージョン率、実データが貯まってから)
- HN/X 投稿への実エンゲージメント(vanity metric として過信しない — 流入経路の1つに過ぎない)

## 3. EU — 2番目

### ターゲット
- GDPR/AI Act のコンプライアンス要件を抱える中堅企業の IT/データ部門
- 独・仏中心(独は 1 年償却という具体的な訴求材料がある)

### 訴求(フック)
「推論トークンが自組織の overlay から出ない。全推論が actor 署名の append-only 台帳に
残り、どのノードが何 token を計算したかを検証可能に示せる — AI Act のトレーサビリティ
要件に効く監査可能な推論」(ADR-2607030030 の UVP をそのまま使う、コモディティ化して
いない差別化点)。ハードウェア面では独の 1 年償却を補助訴求として使う。

### チャネル
1. **コンプライアンス/データ主権を扱う技術者コミュニティへの記事投稿**(英語中心、
   EU 圏の開発者は英語コンテンツで十分リーチできる — 多言語 LP を用意する前に
   まず英語で反応を見る)。
2. **直接 outreach**(コールドメール/LinkedIn)— 対象は「GDPR/AI Act 対応」を
   公言している中堅 SaaS/AI スタートアップの CTO/データ保護責任者。件数は少なくてよい
   (最初の 10–20 社に絞った質重視の outreach)。
3. ~~`#store/eu` の Klarna 分割払い~~ — 2026-07-06、分割払い(Affirm/Klarna/
   Alipay/WeChat Pay)は非対応と決定(Stripe アカウント側で未有効化、
   `docs/stripe-go-live-checklist.md` #5)。ハードウェア購入のハードル低減は
   別の訴求(節税角度・リース型オーナーシップ)のみで行う。

### 90日プラン
| 週 | アクション |
|---|---|
| 1–4 | AI Act/監査台帳の英語技術記事 1 本、EU開発者コミュニティへ投稿 |
| 5–8 | ターゲット企業 10–20 社リストアップ、コールドメール送付(質重視) |
| 9–12 | 反応があった企業とのカスタムデモ(playground を使った実演)、`#store/eu` 流入計測 |

### 成功指標
- outreach 返信率(実数。ゼロなら正直にゼロと記録する)
- `#store/eu` 流入・checkout 開始率
- 具体的な商談発生数(0 が現状の誠実な出発点)

## 4. CN — watch のみ(能動投資は保留)

### 現状の制約(正直な評価)
- Stripe は CNY を決済通貨として持たない → `#store/cn` の実決済は USD 建て
  (Alipay/WeChat Pay 経由、ADR-2607052200)。中国本土の一般的な企業間決済慣行とは
  異なり、法人購買の実務に馴染むかは未検証。
- 中国本土向けにサービスを本格提供するには ICP 備案・データ越境規制など、
  storefront の決済導線だけでは解決しない論点がある(本 ADR/計画の範囲外、法務検討が
  必要)。
- 節税角度(固定资产一次性税前扣除)自体は本物の制度紹介として `#store/cn` に既に
  掲載済みだが、上記の decisão に照らすと「広告費を投じて流入を増やす」フェーズでは
  ない。

### この計画での扱い
- `#store/cn` LP はそのまま公開しておく(コストゼロ)。
- **能動的な広告出稿・現地パートナー契約はこの計画のスコープ外**とし、次のいずれかが
  整うまで保留する: (a) 現地決済(银联/対公帳戸)に対応できるペイメントパートナーの
  目処、または (b) ICP備案要否を含む法務レビューの完了。
- 保留の判断自体をここに明記することで、「中国を無視した」のではなく「決済/規制ギャップ
  を先に潰すべきと判断した」ことを記録する。

## 5. 横断的な注意点

- **税制訴求は全て「一般的な制度紹介であり税務・法務助言ではない」の disclaimer 付き**
  (storefront に実装済み、ADR-2607052200)。マーケティング文言でこの一線を越えない
  (「節税できます」ではなく「〜の対象になり得ます、顧問にご確認ください」)。
- **crypto 決済は US/EU/CN のどのマーケティングでも「coming soon」以上の約束をしない**
  (未実装、ADR-2607052200)。
- **フェーズ規律を守る**(ADR-2607030030): 公開マーケット/ブラウザ貢献は Phase 3。
  この計画は Phase 1(企業 fleet)〜Phase 2(storefront 経由の外部収益)の立ち上げに
  相当し、「バズらせる」ことを目標にしない。

## 6. この計画の追跡方法

`70-tools/bmc` の canvas ledger(`90-docs/business/canvas-ledger.edn`)に、この計画の
Channel 項目を `murakumo canvas add :cloud-murakumo.channels "…"` で追記済み
(governor 経由、重複は自動拒否)。進捗確認は:

```bash
70-tools/bmc/bin/murakumo canvas show
70-tools/bmc/bin/gftd canvas md --all   # 生成 md(cloud-murakumo-business-model.md)を再生成
```

新しい仮説(例: 「US Bonus Depreciation 訴求がハードウェアオーナーシップ枠の実購入に
転換する」)を追加するには、正本の `90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn`
側の更新が必要(本計画のスコープ外、次の follow-up)。

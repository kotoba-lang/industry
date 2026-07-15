# ADR-2607995000: 縁(EN)・労(credits)・準(junbi/USDC) 三圏経済 — kotoba 経済圏の統合設計

**Status**: accepted(統合設計。個別の実装是正は follow-up として列挙)
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki

## Context

オーナー指示(2026-07-15): 「kotoba-lang/murakumo, cloud-murakumo も確認して、
実際に理念理想的に適した経済圏を設計して」。

実装調査(本セッション)の結果、経済の**理念は各ADRに明確に存在する**が、
複数の通貨圏を1つの経済圏として貫く統合設計が無く、以下の7つの緊張点が
実装とADRの間に放置されていることが判明した:

1. **「treasury」が3つの別物**: murakumo credits の `:treasury`(台帳上の
   口座名、5%が積もるが保有者・支出手続・出口が未定義)/ cloud-murakumo の
   x402/USDC treasury(gftdcojp entity、Worker env var)/ etzhayyim の
   junbi/HAKARI reserve(統治された Safe)。kekkai `:treasury/release` は
   「記録を統治するが資金は動かさない」— スタック全体で誰も動かさない。
2. **EN の非投機理念 vs USDC建て witness 報酬**: ADR-2607994000 は
   「EN transfer 1件あたり外部資産建て手数料」を witness 報酬にしたが、
   EN の移動に USDC の値札が付くなら EN は事実上の外部価値を持つ —
   憲章(投機的金融の禁止)が封じたはずの投機面がここから再侵入する。
3. **credits は換金可能なのか曖昧**: ADR-2607030030 は一方向
   (fiat→credits→推論でburn)を設計したが、`credits.cljc` の `spend`
   docstring には「(or fiat payout)」という、他のどこにも存在しない
   償還経路が書かれている。credits が換金可能な請求権なら、隔離設計が
   避けたはずの規制上の重みを帯びる。加えて storefront の「ノード
   所有シェア+compute収益シェア」SKU は証券類似で、ADR上の扱いが無い。
4. **actor-signed feed は教義であって機構ではない**: 全レイヤの docstring
   が「actor署名済み append-only feed」を謳うが、`/infer/runs`/`/infer/spend`
   は無署名 body の `:who` 自己申告を信頼し、receipt はハッシュ連鎖のみで
   無署名、`ledger/witness` の quorum は未結線。ENGI 側は同種のギャップを
   大声で文書化しているのに対し、credits の earn 側は静かに信頼している。
5. **同じ計算資源に2つの需要レール**: credits(`credits-per-usd 100`、
   registry 価格)と x402($0.01/req USDC 直払い)。x402 経由の支払いは
   memory×time 経済に一切触れず、貢献者の earn と x402 収益がどの fold
   でも出会わない。
6. **engi/L1 の除外条項が将来の再利用命令と正面衝突**: ADR-2607993000 は
   「murakumo の経済台帳をこの L1 に巻き込まない」と「将来 BFT 順序付けが
   要る別ドメインは新 L1 でなく engi/L1 を再利用せよ」を同時に規定した。
   credits 台帳が Phase 3(公開マーケット)でグローバル順序を必要とした
   瞬間、両者は衝突する。
7. **witness 人口が分裂**: engi/L1 の順序付け witness(USDC bond、QUIC、
   Mac mini fleet)と cloud-murakumo の proof-of-compute witness(GPU、
   plain HTTP)— `verify/witness_rpc.clj` 自身が「互いに WRONG node
   population」と書いている。staking 市場が2つに割れたまま。

## 理念(既存ADRから抽出した、この経済圏のアイデンティティ)

以下は本ADRが新設するものではなく、**既に決定済みの理念の再確認**である:

- **労働裏付け発行のみ・pre-mine 無し**(ADR-2607030030)
- **EN は net-zero・非発行・非価格**の相互信用 — 貯め込む対象ではなく
  関係性の会計(ADR-2607101100、HoloFuel モデル)
- **売るのは FLOPS ではなく主権と監査**(ADR-2607030030)
- **希少資産の決済は境界の外側だけ**(etzhayyim の USDC on Base L2、
  ADR-2607101100 §4)
- **独自トークンを作らない・投機的金融をしない**(etzhayyim 憲章、
  HAKARI は unit of account であって token ではない — ADR-2607021800 J1)
- **chain primitives は etzhayyim 専属、fiat/Stripe は vendor 専属**
  (dual-axis doctrine、ADR-2607071000)
- **kekkai は統治するが資金を動かさない**(ADR-2607110300 Phase 3)
- **正直なラベリング** — Phase 4 到達まで「分散型」を名乗らない
  (ADR-2607110300)

## Decision — 三圏構造と一方向の膜

経済圏を**3つの圏(sphere)と、圏の間の膜(membrane)の通過規則**として
定義する。各圏は既存実装にそのまま対応し、新しい通貨・トークンは作らない。

```
┌─────────────────────────────────────────────────────────────┐
│  準圏 (junbi) — 外部決済: USDC on Base L2 / fiat(Stripe)     │
│  custody: etzhayyim junbi Safe(chain) / gftdcojp(fiat)       │
│     │ mint(一方向・5% treasury)         │ x402(即時・使用権) │
│     ▼                                    ▼                   │
│  労圏 (credits) — murakumo memory×time credits                │
│  労働(計算・witness務め)だけが発行。推論でburn。換金不可。      │
│                                                               │
│  縁圏 (EN) — mutual credit。net-zero・非発行・非価格。         │
│  他のどの圏とも交換不可。engi/L1 が順序のみを確定する。         │
└─────────────────────────────────────────────────────────────┘
```

### 1. 膜の通過規則(全列挙 — ここに無い流れは禁止)

| 流れ | 可否 | 機構 |
|---|---|---|
| fiat/USDC → credits | **可**(一方向 mint) | 既存 `itonami.cljc` topup(5% treasury)。Stripe checkout → topup の webhook 結線は follow-up |
| credits → fiat/USDC | **禁止** | credits は前払い使用権(prepaid usage claim)であって預り金でも請求権でもない。`credits.cljc` の「(or fiat payout)」docstring は削除する(実装是正 #1) |
| x402 USDC → 推論 | **可**、ただし **credits fold に合流させる** | x402 収益の USDC は treasury へ、リクエストは内部で credits 建てに換算されて同じ `settle` を通り、貢献ノードは credits を earn する(実装是正 #2)。「支払いのフロントエンドが2つ、経済は1つ」 |
| credits ↔ EN | **双方向とも禁止** | 労働の計量と関係性の会計は混ぜない |
| EN ↔ USDC/fiat | **双方向とも禁止** | EN は永久に非価格。witness bond は担保であって交換ではない |
| chain gateway(ADR-2607030030 Phase 3) | **inbound のみ**(USDC→credits mint) | burn 側(credits→チェーン上の資産)は credits 換金禁止に抵触するため**作らない**。ADR-2607030030 の「mint/burn gateway」は「mint-only gateway」に読み替える(部分supersede) |

**この構造自体が非投機性の証明になる**: 内部単位(credits・EN)はどちらも
外部に償還できないため、投資対象として成立しない。外部価値は入ることは
できるが、内部の会計単位が請求権として出ていくことはない。

### 2. treasury の一本化 — 「1つの treasury、3つの役割、権力分立」

3つの「treasury」を、**フロー別に custodian を名指しした単一の設計**に
統合する:

- **記録(ledger)**: credits 圏の `:treasury` 口座(5% cut の積立先)は
  台帳上の記録であり続ける。これは「支出予算の残高」であって資産ではない。
- **保管(custody)**: 実際の外部資産は dual-axis doctrine どおり2箇所のみ —
  fiat(Stripe 売上)は **gftdcojp**(vendor 軸)、chain(x402/USDC 収入・
  witness bond escrow)は **etzhayyim junbi Safe**(chain 軸)。
  cloud-murakumo の `MURAKUMO_TREASURY_ADDR` は junbi Safe のアドレスを
  指すものと定義する(独立した第3の custody を作らない)。
- **統治(governance)**: 支出(witness 報酬・インフラ費・slashing 執行)は
  kekkai `:treasury/release` の allow/deny 記録を必須の前提とし、custodian
  はその記録なしに動かさない。**kekkai は動かさず、custodian は判断せず、
  台帳は記録する** — 三権分立。

### 3. EN の安全予算 — witness 報酬の再設計(ADR-2607994000 Decision #7 を部分supersede)

「EN transfer 1件あたりの外部資産建て手数料」を**廃止**する。理由は
緊張点2のとおり — per-transfer 課金は EN の移動に外部価格を貼り、
非価格理念を裏口から破壊する。代わりに:

- **witness の務め(block 検証・recompute 検証)は労働である。労働は
  credits を mint する** — ADR-2607030030 の「settled run だけが credits
  を生む」を「settled run **および settled witness duty** が credits を
  生む」に拡張する(発行が労働裏付けであるという原則は不変のまま、
  労働の種類が増えるだけ)。報酬は **finalize された block 数に対する
  定額**(EN の移動量に比例させない — 比例させた瞬間 EN に値札が付く)。
- engi/L1 の運用は経済圏全体の**公共財**(縁圏の秩序は労圏・準圏の
  信頼性の土台)であり、その費用を労圏の発行で賄うのは自然である。
- 実在の第三者 witness が「credits では足りない、外部資産で払え」と
  なった場合は、on-ramp 収益(gftdcojp/junbi に実在する USDC/fiat)から
  kekkai release 記録を経て**サービス対価として**支払う道を governance
  投票(stake加重 2/3、ADR-2607994000 Decision #8 の再利用)で開けられる。
  これは EN の値付けではなく、検証労働の市場価格である。

### 4. slashing 原則の統一 — 「客観的に証明可能な不正のみ」

ADR-2607994000 の equivocation-only 原則を、経済圏全体の slashing 原則に
一般化する: **slash してよいのは暗号学的・決定論的に議論の余地なく証明
できる不正だけ**。

- 縁圏(engi/L1): equivocation(同一 height での二重署名)— 既存どおり。
- 労圏(proof-of-compute): **決定論的等価性の recompute 不一致**
  (`verify/compute.cljc` の reject verdict)— これも主観の余地が無い
  客観的証拠であり、同じ原則の適用例である。
- どちらも異議申立て機構を必要としない(証拠が事実であるため)。
  liveness 障害はどちらの圏でも無罰則(active set からの除外のみ)。

### 5. witness 市場の統一 — 「1つの bond 市場、2つの役割」

witness 人口の分裂(緊張点7)は、**bond 市場を1つにし、役割をタグで
分ける**ことで解消する: `engi.stake` の `{did → bonded-amount}` を
`{did → {:amount N :roles #{:ordering :recompute}}}` に拡張し、

- `:ordering`(engi/L1 の block 順序付け)と `:recompute`
  (proof-of-compute のサンプル再計算)は要求ハードウェアが違うため、
  witness は自分が果たせる役割だけを申告する。
- bond・unbond delay・slashing(§4 の統一原則)・kekkai 統治は共通。
- 報酬(§3)も共通の treasury 経路から。
- ノード集団が物理的に別であること(Mac mini vs GPU)は役割タグの
  self-selection で表現され、市場・ルールの二重化はしない。

### 6. engi/L1 と credits 台帳 — 衝突条項の解消(ADR-2607993000 を部分supersede)

ADR-2607993000 の「murakumo の経済台帳をこの L1 に巻き込まない」は、
**「Phase 3(公開マーケット)が credits 台帳にグローバル順序を要求する
までは巻き込まない」に再スコープする**。その時点が来たら、同ADR自身の
「新 L1 を増やさず engi/L1 を再利用せよ」に従い、credits 台帳は engi/L1
の**2番目のドメイン**として乗る — これは奇しくも ADR-2607101200 が
DHT 基盤復活の着手条件に挙げた「複数の消費者が同じ基盤を要求する」の
充足でもある。単一テナント運用(Phase 1-2)の間は現行の
Cloudflare KV + witness seam のままでよい。

### 7. 署名の義務化 — 教義を機構にする gate(緊張点4)

**外部参加者(非オペレータ)が1人でも経済圏に入る前に**、以下を必須 gate
とする(それまでの間は「単一オペレータ信頼」と正直にラベルする):

- `/infer/runs` / `/infer/spend` は CACAO 署名済み body のみ受理
  (`:who` 自己申告の廃止)。
- receipt は hash 連鎖に加えて actor 署名を必須化(`credits.cljc` は
  既に「ready for the actor's kotoba key to sign」と書いてある —
  その ready を実装する)。
- `ledger/witness` の quorum-fn に実際の witness-quorum を結線。

### 8. スコープ外として明示的に切り出すもの

- **ノード所有シェア+収益シェア SKU**(storefront.cljc): 証券類似の
  性質を持ち、本経済圏の「換金不可の内部単位」設計と本質的に異なる。
  経済圏の外の資本取引として**別ADRで扱う**(それまで販売を拡大しない)。
- **HAKARI basket による EN credit-limit の denominate**
  (ADR-2607021800 D8): 既存決定のまま。HAKARI は unit of account で
  あり続け、EN の mint/burn 面を持たない(J12 不変)。

## 実装是正(follow-up、優先順)

1. `murakumo/infer/credits.cljc` — `spend` docstring の「(or fiat payout)」
   を削除(膜規則の明文化)。
2. `murakumo/infer/relay_server.clj` — `post-run!` のハードコード 95/5
   split を `credits.cljc` の `settle`(treasury 5% + head 10%)に統一
   (分裂した第2の split 定数の解消)。
3. cloud-murakumo x402 route — 収益を credits fold に合流(x402 リクエスト
   の credits 建て換算 + 貢献ノードへの settle)。
4. Stripe checkout → `/itonami/topup` の webhook 結線(on-ramp の自動化)。
5. `engi.stake` の bond map に `:roles` タグ拡張(§5)。
6. 署名 gate(§7)— 外部参加者受け入れの前提条件。

## Consequences

- (+) 3つの通貨圏が「膜の通過規則の全列挙」で閉じ、**構造的に非投機**
  (内部単位はどちらも償還不能)であることが設計から証明できる。
- (+) treasury の三重定義が「記録/保管/統治」の分立として一本化され、
  どのフローでも custodian を名指しできる。
- (+) EN の非価格理念が witness 報酬設計と整合する(per-transfer USDC
  手数料の廃止、労働としての credits mint への置換)。
- (+) slashing 原則・witness 市場・L1 基盤が圏を跨いで統一され、
  二重化(2つの staking 市場、2つ目の L1)を防ぐ。
- (−) witness 報酬が credits 建てになるため、外部の第三者にとっての
  参加誘因は当面弱い(credits は換金不能)。正直に言えば、外部 witness の
  実獲得には on-ramp 収益からのサービス対価支払い(§3 の governance 経路)
  が現実には必要になる見込み — その原資は実際の売上であり、売上ゼロの
  現状(Stripe 有効サブスク0)では誘因を提供できない。**経済圏の分散化は
  経済圏の売上に律速される**という依存関係を、本ADRは隠さず明記する。
- (−) 実装是正 1-6 は本ADRでは実行していない(設計のみ)。
- (−) x402 の credits fold 合流(是正3)は x402 の「credits を経由しない
  手軽さ」という現在の利点を内部的には失わせる(外部 API は不変)。

## Alternatives

- **EN と credits を統合して1つの内部通貨にする**: 却下。労働の計量
  (発行あり・burn あり)と関係性の会計(net-zero・非発行)は数学的に
  異なる不変条件を持ち、統合すると両方の理念が壊れる。
- **credits の fiat 償還を認める(双方向化)**: 却下。credits が預り金/
  請求権になり、ADR-2607030030 が意図的に避けた規制・投機リスクをコアに
  持ち込む。
- **witness 報酬を per-EN-transfer の USDC 手数料のまま維持**
  (ADR-2607994000 Decision #7): 却下。EN の移動に外部価格を貼ることに
  なり、非価格理念と矛盾する(緊張点2)。
- **witness 市場を圏ごとに2つ持つ**: 却下。bond・slashing・統治の
  ルールが二重化し、どちらの市場も薄くなる。役割タグで足りる。
- **経済圏専用の新しい governance 機構を作る**: 却下。stake 加重 2/3
  投票(ADR-2607994000)と kekkai `:treasury/release` が既にあり、
  新設は Actor パターン(既存 Governor の拡張)に反する。

## References

- ADR-2607030030(murakumo-inference-economy-gtm)— 労働裏付け発行・
  treasury/head 取り分・fiat on-ramp。**部分supersede**: chain gateway を
  mint-only に読み替え、credits の fiat payout の可能性を明示的に否定。
- ADR-2607101100(engi-mutual-credit-kotobase-native-design)§4 — 三圏の
  境界線の原文。本ADRはこれを全列挙の膜規則に形式化した。
- ADR-2607993000(engi-l1-byzantine-consensus-en-currency)— **部分
  supersede**: 「murakumo 経済台帳を巻き込まない」を Phase 3 までの
  時限条項に再スコープ。
- ADR-2607994000(engi-l1-permissionless-staking-equivocation-slashing)—
  **部分supersede**: Decision #7(per-transfer 外部手数料)を廃止し、
  witness 労働の credits mint + governance 経由のサービス対価に置換。
  Decision #5(equivocation-only)は §4 で経済圏全体の原則に昇格。
- ADR-2607021800(junbi reserve treasury + HAKARI basket)— custody の
  chain 軸。J1/J6/J10/J11/J12 すべて不変。
- ADR-2607071000(dual-axis doctrine)— custody の二軸(etzhayyim=chain、
  vendor=fiat)。本ADR §2 はこの doctrine の適用。
- ADR-2607093100(x402)— 労圏への USDC 入口。是正3 で credits fold に合流。
- ADR-2607110300(decentralization roadmap)— 正直なラベリング原則と
  kekkai value governance。本ADRの前提。

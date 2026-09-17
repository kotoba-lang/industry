# social capital 提案 — 個人貢献が capital になる仕組み (propose-only, 2026-09-14)

対象: amano (app-chain) / itonami (agent OS) / murakumo (inference fleet) / aozora (AT Protocol 面) / yataverse (bytes plane) で**個人**がデータを提供した結果が、いま使い続けてもらう理由になる仕組み。

**本文書は提案である。実装・発行は明示的なゴーアイドの後。**

## 0. まず既存部品の棚卸し (2026-09-14 実測)

| 部品 | 実在 | 本件との関係 |
|---|---|---|
| **moyoshi** (`network-awai/actor-moyoshi`, ADR-2606272100, R3) | 在る。ConveningGovernor + human host + settle 時に `social/mint/convening/<epoch>` (anti-sybil, G1–G8) | **social capital の鑄造権を持つ唯一の actor。** 本件はその隣に立つ |
| **kizuna** | 在る。actor 間 tie graph (fragility/settle baseline) | moyoshi の settle baseline。個人 tie の平面はこれが持つ |
| **kyosai** (`cloud-itonami/kyosai`) | 在る。mutual-aid pool。掛金はちょうど、payout は council 裁定のみ | 共済と social capital を混ぜない。別の平面 |
| **ENGI/EN** (`credits` + `kotoba-lang/en` + engi L1) | 在る。mutual-credit。bilateral issuance、net-zero、balance は投票権を持たない | **金銭面は ENGI が正本。** social capital は EN に為替しない |
| **credits (旧 GCC)** | SUPERSEDED (2026-07-23) | 参照のみ。新規発行をここに足さない |
| **inga** (`kotoba-lang/inga`) | 在る。HotStuff BFT / 3-chain finality | 鑄造の ordered evidence ledger の裁定者に使える |
| **amano** (`:chain-amano`, ADR-2609141400, reservation) | 在る。awai の sovereign app-chain | 貢献の attestation chain として後で候補。R0 はもっと軽く始める |
| **hyakka** (`network-awai/app-hyakka`) | 在る。sourced claim graph。source 無し文は jiten が refuse | 専門知識・地図・ローカル情報の claim 格納庫はこれ |
| **aozora** (`network-awai/app-aozora`, aozora.app) | 在る。AT Protocol PDS/AppView/feeds | 人の profile・feed・contact discovery の公開面 |
| **yataverse.com** (adr 2609131630) | 在る。bytes plane + lake index (shinkansen MCP) | 案内メディア (地図/写真) の bytes 置き場。CID が identity |
| **resource-provider** | 在る。provider 貢献 (gpu/storage/data/location) の sealed ledger | 報酬アカウントの E2E パターンの先行実装。**名前空間は分ける** |
| **Valueflows** (ADR-2608153000) | 正本語彙。value-equation は cloud-itonami/credits が既に認識 | **social capital の台帳は Valueflows projection で足場を共有**する |
| **talent / kumi / com-prolific-app** | 在る。cohort-first 原則、community graph、検証済募集 | privacy/k-anon/sybil 対策の先行規範 |

結論: **「貢献→検証→台帳→使う理由」の骨格は既に分散して在る。新規に作るのは「social capital 台帳そのもの」だけ。** 作り直す候補は全て排除。

## 1. 設計: 5 面モデル

### 面 1 — 貢献の宣言 (propose)
貢献者は amano/itonami/aozora/murakumo/yataverse で**自分の DID (did:key / did:web / did:pkh) で署名した attestation**として貢献を宣言する:

```
:sc/contribution
{:contribution/id    <uuid>
 :contribution/by    <did>            ; 本人署名 (CACAO, fresh nonce)
 :contribution/kind  #{:claim :media :observation :news-tip :verification}
 :contribution/cid   <content CID>    ; 実体は hyakka / yataverse / aozora
 :contribution/domain  #{:expertise :maps :local :news :moderation}
 :contribution/at    <epoch>
 :contribution/license  #{:public-domain :cc-by :sc-only}  ; 指定必須}
```

- 宣言だけでは capital は出ない。**宣言は入力であって鑄造ではない** (moyoshi G4 と同じ原則)。
- license 未指定は gate で refuse (keihi/kyosai の「ちょうど」哲学: フィールドが揃うまで 0)。

### 面 2 — 検証 (verify) — 鑄造はここからしか出ない
鑄造条件は貢献の種類ごとに**独立検証が通った tie** だけ:

| kind | 鑄造条件 (どれも「勝手に点数化しない」) |
|---|---|
| `:claim` (専門知識) | (a) 3 以上の独立 source と突き合って矛盾しない (hyakka `:source` 制約そのまま)、かつ (b) N 週間生存 (retraction 無し)、かつ (c) 複数 DID が **refute でなく verify** した |
| `:media`/`:maps` (地図・案内) | 位置は実測 (GPS EXIF か OSM を CID 付きで引用)。重複排除は最初に上がった CID が正、後追いは attribution だけ |
| `:observation` (ローカル情報) | 2 独立観測が一致 (moyoshi G2 の reciprocal-tie と同じく「自己申告 1 件は鑄造しない」) |
| `:news-tip` | 公開 source の URL が CID で引用済み + 事実文言は `:jiten.statement/source` 無しでは構文上作れない (hyakka の jiten 経路を再利用) |
| `:verification` (他人の貢献を verify した人にも配る) | verifier 自身が同一エポックに自分の鑄造対象を持たない (自己昇格を切る) |

**絶対にないもの**: 到達数 / いいね数 / 従属時間 / retention を点数化する経路。moyoshi G2 (BONDS-not-turnout) を拡張適用。

### 面 3 — 台帳 (ledger)
- **inga の ordered journal** (または最初は engi L1 の accepted journal 様式のまま、単独管理者無しの append-only 台帳) に、**エポック単位で**鑄造事件を積む: `sc/mint/<domain>/<epoch>`。毎エポックに上限 (`:sc/epoch-cap`) を設け、超えた候補は次エポック送り (drift は rate-limit になる)。
- 事件の evidence は **CID で引ける** (claim は hyakka の datom、media は yataverse bytes、profile は aozora)。台帳は bytes を持たない (byte を datom 面に載せない規則を踏襲)。
- **Valueflows projection** を足す (`sc.valueflows`): `:contribution` → `vf:Work`、`:mint` → `vf:EconomicEvent`。ENGI 転送や kyosai 掛金と同じ query 面に乗る。**「金額に換えられない」ことを理由に経済語彙から外さない。**

### 面 4 — 残高と減衰 (balance)
- 残高は **attack surface ではなく access surface**。誰にでも公開せず、本人 + 明示的被譲渡相手のみ (resource-provider の sealed-path と同じ read-cap)。
- **時間減衰は常数ではなく正体**: ある貢献の資本は「その claim が今も refute されていない」で保たれ、refute されたら検証した人に削減側の attestation が記録される。減衰は時間を薄めるのでなく**正しさを維持する運賃**。
- 譲渡は「し方の制約が多い贈与」: 1 次元転送 (買い戻し不可・転貸不可)。**借方を作らない** (負残高は ENGI 固有、ここでは表現しない)。

### 面 5 — それを「使う理由」にする面 (spend surface) — これが本件の本体
capital は表示用の点数ではなく、fleet の**意志決定面への投票と情報面への特権**に使える:

1. **青空文庫/aozora の見え方**: 貢献者の claim は `:sc-verified` チップ付きで AppView/検索に出る (unverified claim は「下に沈む」が**排除はしない**。 exclusion でなく降順)。
2. **murakumo で配信されるモデルの順位**: murakumo KV に載る alias (`murakumo-main` は SSoT) に対し、**「この job 群を優先する順位付き model 提案」を capital で上乗せできる**。ただし計算順位そのものは model 評価の gate が保つ (capital は順位の決定者ではなく優先提案の weight)。**「balance で得票する」形式にしない** (EN の「balance は投票権を持たない」を踏襲)。
3. **amano での reservation 優先**: app-chain の reservation ref plane で、貢献 attestation を持つ DID は reservation 期限の延長申請を propose できる (実行は governed)。
4. **itatami/itonami resident の propose 権**: 貢献者の attestation は resident actor の propose の際の authority に足せる (値は付くが governor は別に在る)。

**その反対面 (必須)**: capital で得られるのは上の 4 つだけ。金銭 (EN/USDC への為替)、publish 権の直接付与、governor 迂回、turbo な実行権は**全て deny**。deny の判定は gate に literal を持つ (verify で「rate-limited」を「available」と出し分ける)。

## 2. 貢献タイプごとのパイプライン (5 本全部 1 つの台帳に注ぐ)

| 提供 | 収集面 | 検証 | CID 置き場 |
|---|---|---|---|
| 専門知識 | aozora の profile/claim record | hyakka + jiten + 3 source + 生存期間 | hyakka datom |
| 地図 (POI/測量) | yataverse bytes (画像/GeoJSON) | GPS EXIF + OSM 突合 | yataverse `{cid}.ipfs.yataverse.com` |
| ローカル情報 (営業時間等) | aozora feed | 2 独立観測一致 | aozora (ATProto record) |
| ニュース tip | aozora feed / itonami | 公開 source CID 引用 | hyakka claim |
| moderation / refute | hyakka refute record | refute 自体も自体が attestation (鎖の一本。 capital から独立) | hyakka |

全部が**同じ mint 規則 (`:sc/epoch-cap` + 独立検証) を通って 1 つの ledger に注ぐ**。貢献の種類が 5 つあることは ledger には現れない (現れたら種類ごとの scoring 制度が再生する)。

## 3. 憲法的不変条件 (gate として literal を持つ)

1. **MINT-ON-VERIFIED-TIES-ONLY** — 鑄造は独立検証が通った tie からのみ。moyoshi G4 と同形。
2. **NO-ENGAGEMENT-FIELDS** — reach/like/retention/viewcount の field は representable にしない (kumi G7 と同形。 field 自体を持たない)。
3. **NO-EXCHANGE-TO-MONEY** — `sc→EN/USDC` 為替 endpoint は実装しない。alias を許す (EN は ENGI、sc は sc。 interface のみ共有)。
4. **NON-ADJUDICATING** — 台帳は verify/refute の attestation を**記録するが裁定はしない**。裁定は人 (member CACAO) がする (kyosai の NON-ADJUDICATING と同形)。
5. **CAPITAL-NOT-POWER** — capital は順位・投票の決定者ではなく weight。**balance が決定する経路は 1 つも無い** (ENGI「EN balance は投票権を持たない」の踏襲)。
6. **DECAY-BY-TRUTH-NOT-TIME** — 減衰は時間ではなく真実維持で起きる。時間のみで消える capital は作らない (使うと押すと書き換わるので)。
7. **SELF-VERIFICATION-REFUSED** — 同一エポック内の自己 verify / 自己 refute を拒否する。
8. **PLURAL-ADJUDICATION-BEFORE-RATE-CHANGE** — epoch-cap・減衰率・重量変更は governance (Cしい ADR + operator 承認) を経由する。ランタイムは rate を自力で動かさない。
9. **PRIVACY** — 貢献者 profile の PII は Signal-E2E (talent 規範)、公開面は DID + k-anon cohort 集計のみ。
10. **MEASURE-BEFORE-REPORT** — 台帳 self-check は boolean でなく個数を返す (`SCANNED\t<count>` 形式、0 で clean にしない)。

## 4. 全体アーキテクチャ

```
[aozora feed/profile]     [murakumo 報酬側?]        [yataverse bytes]
        │                        │                        │
   self-signed CACAO attestation (DID, fresh nonce, 1 度だけ消費)
        │
        ▼
  ┌─────────────────────────┐
  │ sc.attest  (面 1: 受付) │── refuse  (license 無し / 自己参照 / 形式不良)
  └──────────┬──────────────┘
             ▼
  ┌─────────────────────────┐
  │ sc.verify  (面 2)       │── verify / refute / 生存期間 / 2-source 一致
  │  (hyakka + jiten + OSM) │
  └──────────┬──────────────┘
             ▼  (tie formed + survived + cap 未満のみ)
  ┌─────────────────────────┐
  │ sc.ledger  (面 3)       │── append-only / epoch-cap / CID evidence
  │  (inga ordered journal) │── vf projection (Valueflows)
  └──────────┬──────────────┘
             ▼
  ┌─────────────────────────┐
  │ sc.spend   (面 5)       │── aozora 見え方 / murakumo alias 優先提案 /
  │  (weight であって権限ではない) │   amano reservation 延長 propose / resident propose authority
  └─────────────────────────┘
        (反対面: EN/USDC 為替 deny / publish 権 deny / governor 迂回 deny)
```

## 5. 実装計画 (R0→R2, 1 repo = 1 subject)

**repo 名の見込み**: `orgs/kotoba-lang/social-capital` (family 面。role suffix ではなく family/kotoba-lang の subject として。`-clj` は付けない。名前審査は `verify-repository-roles.cljk --name-audit`)

**R0 (scaffold, 返り値: 承認待ち)**:
1. `sc.core` — attestation 形式 (CACAO, fresh nonce, (DID, nonce) 1 回消費) + mint 規則 (純粋 `.cljc`/`.cljk`)。 throw する gate は kyosai.murakumo 形式を踏襲。
2. `sc.ledger` — append-only journal + `:sc/epoch-cap` + vf projection。
3. `sc.gates` — 上記 10 不変条件を literal 断定する test (両方向: 通すべきものと refuse されるべき literal の両方を assert)。
4. tests — `kbb` で 11 tests/76 assertions 規模をまず通す (kumi と同規模)。
5. ADR — `.kotoba` 形式で `adr-new.cljk` から。この文章を根拠に ADR-260914XXXX を起稿。

**R1 (dry-run)**: 1 domain だけ (地図かニュース tip から: 独立検証が構造的に楽なため) 実データ seed で dry-run。**実データが無い間、正体在る長期値を宣言しない** (「:uncomputable-until-measured」)。鑄造実行は 0 で良い。

**R2 (つなぎ込み)**: aozora AppView `:sc-verified` chip / murakumo alias weight / amano reservation propose / itonami resident authority。各 1 repo、`governed propose` 経由のみ。

**R3 (以後)**: kizuna tie graph との JOIN、kaname/kumi への観測面開示、sybil resist の強化 (moyoshi anti-sybil membrane を借りる)。

## 6. 未解決の設計判断 (この ADR を出す前に operator に問う)

1. **もらえる側の名前**: 「capital」と呼ぶか。正体が点数でないため、他の案は「社会原稿の信用残高」等。人称語は proposal では後回しでも良い。
2. **減衰**: refute での減衰の量を rate で固定するか、adjudication で個別に決めるか。
3. **最初の 1 domain**: 地図 (独立検証が構造的に軽い) か、ニュース tip (hyakka/jiten と 1 本で繋がる) か。
4. **moyoshi との境界**: moyoshi は「gather tie」から mint する。本件は「data tie」から mint する。**同じ `social/mint/*` 空間に共存するか、別空間 (`sc/mint/*`) にするか** (別空間を提案)。
5. **ENGI との接点**: 設定後、`sc→vf` projection 経由で **vf 語彙での at-the-time 関係**は必ずある。ただし為替は deny のまま。ENGI 側の epoc 上限を別途もらうか。

## 7. この設計が**しない**こと (先に名指す)

- **enagement 工学にしない** (moyoshi が封じた engagement-industrial complex を data 側で作り直さない)。
- **点数を付けてランクを作らない** (keizu/kumi の non-adjudicating を踏襲)。
- **EN に偽装しない** (credits/GCC は superseded。新規価値は ENGI 形式)。
- **D1/DO/KV を前提にしない** (awai D1 禁止 ADR-2609132007 の同形)。台帳は inga/amano/kotobase datom plane。
- **cap は決めるが rate は runtime に自由にさせない**。
- **agent に publish 権を渡さない** — 全部 propose まで (AGENTS.md の絶対規則)。

# ADR-2607021500: gftd ポートフォリオ 7 レイヤー business model / lean canvas

**Status**: proposed
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## 訂正 (Corrections, 2026-07-02) — 実 repo / Stripe 実データ検証で判明

本 ADR 本文の以下の記述は、各 repo コード・Stripe live の実データ検証で誤り/未確認と判明した。
本節を正とし、本文該当箇所は将来 rewrite する（履歴保持のため原文は残置）。

- **ai-gftd-apex の tier**: 「Free / Plus / Visionary」は誤り。実 repo の tier は
  **Free / Pro / Max / Team / Enterprise**（"Plus" は Pro の内部別名、**"Visionary" は存在しない**）。
- **app-aozora の blob backend**: 「blob=B2」は誤り。実際は **Cloudflare R2**（`aozora-pds-blobs`、CID content-addressed）。
- **app-aozora の creator 80/20 split**: repo に**根拠が見つからない**（現状は広告ネットワーク収益のみ確認）。
  「creator 80/20」は**未確認**として扱う（実装/契約で確定するまで断定しない）。
- **net-kotobase の Stripe price**: 旧記載の price ID（`price_1TVVI7…`）は **live に存在しなかった**（捏造/test）。
  2026-07-02 に実 live USD price を作成し確定: **Standard $7 / Pro $33 / Regulated $350**（ADR-2607023000、collect #252）。
- **network-isekai の gem 経済**: 実装は **virtual gem**（ADR は "cash" 表記だが shipped は仮想通貨）。
- 各 product の運営法人: **gftdcojp = Gftd Japan 株式会社**（kotobase/murakumo/apex/itonami/manimani/aozora+yoro/isekai/yukkuri）、
  **club-shinshi = JK 株式会社**、**etzhayyim = 米国宗教法人（非営利、管轄 US）**（法務 draft PR 群に反映済）。

## Context

gftd / etzhayyim ポートフォリオには互いに依存する 8 つのプロダクトがあるが、
それぞれの business model と lean canvas が体系として整理されていなかった。
既存の設計資料は net-kotobase（`docs/BUSINESS-MODEL.md`、kotobase tier 型）、
ai-gftd-shinshi（`docs/260613-bmc-lean.datoms.edn`、ad-supported）、
gftd-talent-actor（`docs/business-model.md`）と単品ごとに散在している。

本 ADR は 8 プロダクトを 7 つの機能レイヤーに割り当て、レイヤーごとに
business model と lean canvas を設計・整理する。

## Decision — レイヤー割当（正）

| Layer | Product | 参照モデル | 一言 |
|---|---|---|---|
| **L1 LLM 推論 — infra** | cloud-murakumo | Civitai × exo | weight hosting + 分散推論 fabric |
| **L1 LLM 推論 — app** | ai-gftd-apex | Proton (Lumo) | privacy-first LLM 推論サービス |
| **L2 storage hosting** | net-kotobase | Pinata/Supabase 拡張 | storage / graph DB BaaS + map / git / search |
| **L3 business operator** | cloud-itonami | 業務 OS + AI agent | 全業種・職種 SaaS + investment platform |
| **L4 social network** | app-aozora | Bluesky/Instagram | atproto SNS（image / 動画 post） |
| **L4 messenger** | app-aozora-yoro | Signal/LINE | 同一 identity graph 上の messenger |
| **L5 personal wellbecoming OS** | cloud-manimani | Hermes 型 personal agent | 個人の受信→決断→ledger OS |
| **L0 artificial organism platform** | etzhayyim | 非営利 foundation | 自律的な生命体の活動と進化の場（公益） |
| **L6 UGC game / creator platform** | network-isekai | Roblox | play/fork/share の AI-native UGC ゲーム/資産（ADR-2607021900 で追加） |
| **L4 adult creator platform** | club-shinshi | PornHub/OnlyFans/FANZA | 3 型ハイブリッドのアダルト creator 経済圏（ADR-2607021900 で追加） |
| **L7 AI video content channel** | ai-gftd-yukkuri | YouTube 広告収益 | ゆっくり実況を全自動生成し YouTube 投稿→広告収益（ADR-2607022000 で追加） |

縦の依存: **etzhayyim（L0: identity/存在）→ net-kotobase（L2: state）→
cloud-murakumo（L1: compute）→ ai-gftd-apex（L1: 推論面）→
cloud-itonami（L3: 法人）/ cloud-manimani（L5: 個人）→
app-aozora / app-aozora-yoro（L4: 社会面）**。

---

## L1-infra: cloud-murakumo — weight hosting × 分散推論（Civitai × exo）

### Business model

「weights を置く場所」と「それを動かす fabric」を同一 API にする。
Civitai が weight の hosting/発見/creator 収益化を、exo が consumer hardware
上の memory-weighted shard 推論を担うのに対し、murakumo は両者を
content-addressed に一体化する: `/infer/models`（registry）に pin した weight が
そのまま fleet の shard plan（`/infer/plans`）で serve され、全 run が
append-only の run ledger（engine / ranks / tok/s）に記録される。
run ledger がそのまま **compute marketplace の精算根拠**になるのが要。

収益は 4 本: (1) weight hosting 従量（B2 原価に margin）、(2) 推論従量
（tok 課金）/ 生成 credits（Civitai Buzz 型）、(3) compute provider marketplace
の take rate（20–30%、provider に revenue share）、(4) enterprise dedicated
fleet / creator paid-weights 手数料。

### Lean canvas

| Block | Items |
|---|---|
| Problem | open weights の hosting は HF 一極集中で creator 収益化が弱い / GPU クラウド推論は高コスト / 手元の Mac fleet・consumer device は遊休 / fine-tune・LoRA の provenance が追えない |
| Customer Segments | open-weight creator（LoRA/fine-tune 作者）/ self-hoster（Mac mini fleet 所有者 = compute 供給者）/ 推論需要者（apex・itonami・shinshi 等の社内 first customer + 外部 API 顧客） |
| UVP | pin した weight がそのまま fleet で serve される — hosting と inference が同一 CID graph。consumer hardware から推論を「売れる」 |
| Solution | model registry + shard plans + run ledger（cloud-murakumo Worker、実装済）/ murakumo CLI（OSS、standalone）→ cloud connect / B2 weight storage / exo 型 memory-weighted shard 実行 |
| Channels | OSS murakumo CLI → cloud upsell / kotobase 開発者基盤 / 社内アプリが最低需要を保証 |
| Revenue | hosting 従量 + 推論 tok 課金 + marketplace take rate + dedicated fleet / paid weights 手数料 |
| Cost | B2 storage・egress / fleet 電力・保守 / CF Workers / weight 安全審査 |
| Key Metrics | hosted weights 数 / active fleet nodes / serve 総 tok/s / ledger 記録 run 数 / margin per tok |
| Unfair Advantage | 監査可能な推論台帳（誰の node が何 tok 出したか）= marketplace 精算が構造的に可能 / kotoba PDS の key-derived graph で weight provenance / 社内需要が floor |

**Riskiest assumption**: consumer fleet の tok/s 単価が GPU クラウドの spot 価格に
対して買い手のつく水準に収まる（run ledger 実測で検証、gate = 社内 3 アプリの
推論を fleet へ移して原価比較）。

---

## L1-app: ai-gftd-apex — privacy-first LLM 推論（Proton 型）

### Business model

Proton 型の「privacy を売る subscription」。README の Privacy Contract が正:
email/電話/実名/social identity を主キーにしない、chat は ephemeral 既定、
保存・memory は opt-in、prompt/生成物は user data であって telemetry ではない、
provider へは policy router 経由。広告は構造的に不可能（telemetry を持たない）
なので収益は subscription のみ: Free（制限）/ Plus（保存・memory・高 quota）/
Visionary（Claude Code / Codex 型 agent subscription バンドル: coding・research・
multimodal・workflow 実行）。バックエンド推論を murakumo fleet に段階移管して
provider 原価を下げる。

### Lean canvas

| Block | Items |
|---|---|
| Problem | 主要 AI チャットは個人 identity を主キーにし prompt を telemetry として扱う / 規制業種・匿名必須の利用者に選択肢がない / agent 購読は provider lock-in |
| Customer Segments | privacy 重視の個人（Proton ユーザー層）/ 規制業種 professional / 匿名性が必要な creator / coding・research agent 需要層 |
| UVP | 「identity を渡さずに使える AI」— 方針でなく設計（did:key/CACAO、ephemeral 既定、opt-in 保存） |
| Solution | gftd.ai apex app（.cljc + Reagent、実装済）/ CACAO・did:key 自己発行 auth / policy-routed providers / agent subscriptions / murakumo self-host 推論 backend |
| Channels | privacy コミュニティ（Proton 代替文脈）/ OSS kotoba スタックの開発者 / aozora・yoro からの導線 |
| Revenue | subscription（Free / Plus / Visionary）+ agent subscription + 追加 credits。広告ゼロ |
| Cost | provider API 費（router 経由）/ murakumo fleet 費 / CF / 開発 |
| Key Metrics | paid conversion / MRR / churn / (price − cost) per tok / ephemeral 率（privacy 契約の遵守指標） |
| Unfair Advantage | CACAO/did:key で「アカウントすら要らない」構造的 privacy / murakumo 内製推論で原価降下と provider 非依存 / kotoba スタック全体との identity 共有 |

**Riskiest assumption**: privacy premium に日本語圏でも払う層が subscription を
成立させる規模で存在する（gate = Free→Plus 転換率が Proton 水準 ~数%）。

---

## L2: net-kotobase — storage / graph DB BaaS + map / git / search

### Business model

既存 `docs/BUSINESS-MODEL.md`（kotobase tier 型: Free / Standard / Pro / Regulated、
subscription 主軸 + enterprise 高単価）を正として維持し、本 ADR で **同一
content-addressed graph 上の追加 surface** を位置づける:

- **storage / pinning** — CAR-on-B2、pin = named commit CID（既存）
- **graph DB BaaS** — Datomic/Datalog + SPARQL/Cypher 型読み（既存）
- **map** — geo ingest + tile/geo query surface（kotoba geojson 系 lib）
- **git** — content-addressed object store と同型の git remote hosting
- **search engine** — graph + full-text の index/search API

1 つの commit CID で storage・graph・map・git・search の全 surface が再現可能・
監査可能、が統一 UVP。追加 surface は tier への add-on（map API 従量 / git seat /
search query 従量）として課金する。

### Lean canvas

| Block | Items |
|---|---|
| Problem | graph DB の自前運用は重い / pin・storage サービスに provenance がない / map・git・search が別々の SaaS に分散し data 主権が失われる |
| Customer Segments | 個人 engineer（Free）/ AI・app チーム（Standard/Pro）/ enterprise data チーム / regulated（専有 tenant・read audit・key custody） |
| UVP | content-addressed KG 1 本の上に storage・graphdb・map・git・search が同居。DB そのものが可搬（WASM + CID + atproto）= lock-in の逆張り |
| Solution | kotobase.net（既存稼働）: pin / Datalog / KG ingest + add-on surface: map・git remote・search index |
| Channels | landing で技術価値を即示 → free self-serve → paid workspace（kotobase tier 型）/ OSS kotoba 開発者 / 全社内プロダクトが tenant |
| Revenue | tier subscription（既存 4 段）+ surface add-on 従量（map / git / search）+ enterprise 契約 |
| Cost | B2 / CF Workers / index 計算 / compliance 対応 |
| Key Metrics | pinned CID 数 / active graphs / query volume / storage GB / paid workspace 数 / add-on attach 率 |
| Unfair Advantage | murakumo fleet state・manimani ledger・itonami 台帳・aozora PDS blob と、全社 state がここに乗る（内需が floor）/ provenance 標準を握る |

**Riskiest assumption**: 「graph + provenance」が pin 単品より高い ARPU を
正当化する（gate = Standard→Pro 転換と add-on attach 率）。

---

## L3: cloud-itonami — business operator（全業種・職種 SaaS + investment platform）

### Business model

`manimani` の企業版 = gftdcojp の business activity を 1 本の kotoba/datom log で
扱う業務 OS（README が正）。営業・契約・請求・法務・PLM/ERP・経営判断を同じ
`activity → decision → effect → audit` に載せ、actor パターン（LLM は proposal
のみ、独立 Governor が検閲、`interrupt-before` で人間承認、append-only 監査台帳）
で「AI に業務をさせても監査が残る」を売る。

3 収益層: (1) **operator SaaS** — per-seat + per-agent-run。(2) **vertical
module** — 26 ISIC vertical blueprint と etzhayyim の職種 actor 群
（com-etzhayyim-* 数百 repo）を業種・職種パックとして subscription 販売。
(3) **investment platform** — deal flow を同じ decision ledger に載せ、
deal fee / SPV 管理料 / carry。買い手には「判断の監査台帳」が DD 資料になる。

### Lean canvas

| Block | Items |
|---|---|
| Problem | 業務 SaaS は職種ごとに分断され判断の脈絡が消える / AI agent 導入は「誰が何を承認したか」が残らずコンプラ不能 / 中小には CFO・法務・営業 ops の専任がいない |
| Customer Segments | 中小企業の経営者・operator / 業種 vertical（26 ISIC blueprint）/ 投資家・ファンド（investment platform）/ gftdcojp 自身（first customer、dogfooding） |
| UVP | 全業種・職種を同一の activity→decision→effect→audit log に載せる業務 OS。AI proposal ⊣ Governor 検閲 ⊣ 人間承認 — 監査台帳つき business agent |
| Solution | cockpit（itonami.cloud、Pages 稼働済）/ langgraph-clj StateGraph actor + 独立 Governor + 不変台帳（robotaxi/talent/itonami の 3 実例で確立済パターン）/ 職種 actor 群を module 化 |
| Channels | gftdcojp 実運用の公開（build in public）/ 業種 blueprint ごとの直販 / kotobase・murakumo 顧客への cross-sell / 士業・SIer パートナー |
| Revenue | per-seat + per-agent-run / vertical module subscription / investment platform（deal fee・SPV 管理料・carry）/ enterprise audit 契約 |
| Cost | LLM 推論費（murakumo/router）/ kotobase storage / vertical blueprint 保守 / sales / compliance |
| Key Metrics | active orgs / agent runs/day / 人間承認 SLA / 台帳の外部監査適合率 / vertical attach 数 / deal count・AUM |
| Unfair Advantage | 職種 actor 数百 repo + 26 ISIC blueprint が既に存在 / governor 封じ込めパターンは規制業種にそのまま売れる / 台帳 = switching cost |

**Riskiest assumption**: 中小の operator が「監査台帳つき agent」に per-seat で
払う（gate = gftdcojp 外の初期 10 org の有償転換）。

---

## L4-SNS: app-aozora — social network（atproto、image / 動画 post）

### Business model

etzhayyim organism fleet の canonical AT Protocol 境界（README が正: PDS /
AppView / feeds / search / actor profile は aozora が所有、`aozora.app` が
endpoint）。人間 creator と artificial organism が同じ identity graph に住む
SNS で、image / 動画 post を blob（B2、SHA-256 content-addressed）で扱う。

収益は shinshi.club で本番実証済みの経路を一般向けに移植する:
(1) federable sponsored posts（advectors auction + `!ad` post）、
(2) creator monetization（subscription / PPV / tip、80/20 split）、
(3) premium（追加 storage・高画質・feed 機能）。DID 可搬（「逃げられる
アカウント」）が中央集権 SNS への構造的差別化。

### Lean canvas

| Block | Items |
|---|---|
| Problem | 中央集権 SNS は BAN = 全喪失、creator の audience が platform の人質 / AI 生成 media の provenance がない / AI actor は既存 SNS に「住民」として存在できない |
| Customer Segments | creator(image/動画) / 視聴者 / etzhayyim organism（コンテンツ・活動の供給者）/ 広告主（federable sponsored） |
| UVP | DID で可搬なアカウント + 人間と organism が同居する graph + content-addressed media provenance |
| Solution | PDS / AppView / feeds / search（aozora.app）/ blob = B2 content-addressed（uploadBlob 実装済）/ self-label + age gate（shinshi 実装の一般化）/ yoro と同一 identity graph |
| Channels | Bluesky / AT firehose federation（viral）/ organic SEO / organism 群の自動投稿が初期コンテンツ / shinshi・yukkuri 等 gftd media からの導線 |
| Revenue | federable sponsored posts / creator monetization 80/20 / premium subscription |
| Cost | PDS・AppView 運用（CF + B2、egress ゼロ設計）/ CDN / moderation・trust & safety |
| Key Metrics | DAU / posts/day / creator payout 総額 / sponsored fill・eCPM / organism actor 数と生成比率の健全性 |
| Unfair Advantage | shinshi.club で ad・feed・age-gate・blob 経路が本番実証済み / organism 群が day-1 からのコンテンツ供給者 = cold start 緩和 / atproto 互換で既存 Bluesky graph に接続 |

**Riskiest assumption**: organism 発コンテンツが「AI スパム」でなく魅力として
機能する mix がある（gate = organism post の engagement が人間 post の一定割合を
超える feed 設計）。

---

## L4-messenger: app-aozora-yoro — messenger

### Business model

aozora の companion messenger surface（README が正: DM / inbox / thread /
receipts / contact discovery を同一 identity graph 上で）。単体の Signal/LINE
競合としてではなく、**「agent の human-in-the-loop 面を握る messenger」**として
設計する: itonami / manimani の actor が `interrupt-before` で人間承認を求める
とき、その承認・通知・応答チャネルが yoro になる。人↔人 DM は無料で graph を
太らせ、収益は (1) business inbox（seat + 会話量、LINE 公式より安く lock-in
なし）、(2) agent 通知チャネル（itonami / manimani subscription にバンドル）、
(3) priority delivery。E2E は kotoba crypto（ed25519）で。

### Lean canvas

| Block | Items |
|---|---|
| Problem | messenger は network effect で寡占され metadata が事業者に渡る / business messaging は高価で lock-in / AI agent の承認・通知に専用の安全な面がない |
| Customer Segments | aozora ユーザー（DM）/ 個人（private messaging）/ business（顧客対応 inbox）/ itonami・manimani の agent（human-in-the-loop 通知者として） |
| UVP | SNS の follow graph がそのまま contact discovery / 人↔人と人↔agent が同じ受信箱 — agent 承認の standard surface |
| Solution | DM / inbox / thread / receipts（aozora と同一 identity graph）/ ed25519 E2E / agent interrupt→承認 UI |
| Channels | aozora からの内蔵導線（CAC ~0）/ itonami・manimani の承認フローが必然的に連れてくる |
| Revenue | 個人 free / business inbox（seat + 会話量）/ agent チャネル（L3・L5 subscription にバンドル）/ priority delivery |
| Cost | delivery・push infra / spam・abuse 対策 |
| Key Metrics | MAU / messages/day / business inbox 数 / agent 承認の応答時間（= itonami SLA に直結） |
| Unfair Advantage | agent human-in-the-loop の承認 UX を独占（他 messenger は agent identity graph を持たない）/ aozora と identity 共有で獲得コスト ~0 |

**Riskiest assumption**: 人↔人 messaging の獲得が aozora 導線だけで成立する
（messenger 単体マーケはしない。gate = aozora MAU→yoro MAU 転換率）。

---

## L5: cloud-manimani — personal wellbecoming OS

### Business model

manimani（OSS desktop/CLI triage app: 受信 queue → 方針選択 → Decision Ledger
→ agent loop、JSONL+git の local ledger）を核に、cloud-manimani が
kotobase.net 同期で multi-device / durable にする（README が正）。
「wellbecoming OS」= 記録アプリではなく、**個人の決断を Hermes 型 always-on
personal agent が下ごしらえし、決めたことが append-only ledger に残る**個人 OS。
決断負荷・積み残し・回復のバランスが wellbeing 指標になる。

local-first OSS（無料・完結）→ cloud subscription（同期・durable・agent 実行）
の Proton / Obsidian 型 upsell。itonami と同型（`activity→decision→effect→audit`
の個人版）なので、職場で itonami に触れた人が個人で manimani を使う双方向
funnel が働く。

### Lean canvas

| Block | Items |
|---|---|
| Problem | 通知・受信・タスクの洪水で個人の意思決定が磨耗する / personal assistant は私生活を他人のクラウドに渡す前提 / wellbeing アプリは記録止まりで実行がない |
| Customer Segments | knowledge worker / 経営者・自営（itonami の個人版として）/ quantified-self・wellbeing 層 |
| UVP | 受信→方針→Decision Ledger→agent loop の個人 OS。「何を決めたか」が自分の手元に append-only で残る = 人生の監査台帳。local-first、cloud は opt-in |
| Solution | manimani OSS（local store、実装済）+ cloud-manimani Worker（kotobase 同期、実装済）/ rules による triage 自動化 / Hermes 型 personal agent（proposal のみ、決めるのは本人） |
| Channels | OSS 配布（GitHub / brew）→ cloud connect upsell / itonami 職場導線 / apex・aozora ユーザーへの cross-sell |
| Revenue | cloud sync subscription（個人月額）/ Pro（agent 実行時間・rules 高度化）/ family plan / itonami seat バンドル（B2B2C） |
| Cost | kotobase storage / 推論費 / sync infra（いずれも従量・軽量） |
| Key Metrics | DAU / decisions/day / triage 完了率（inbox zero 到達）/ OSS→cloud 転換率 / retention（ledger が伸び続けているか） |
| Unfair Advantage | ledger は移行不能な switching cost（ただし export 自由 = 信頼の根拠）/ itonami と同一パターンの個人↔法人 funnel / local-first で privacy 層に刺さる |

**Riskiest assumption**: 個人が「決断の台帳」に月額を払う（gate = OSS active
user → cloud 転換率が Obsidian Sync 水準 ~数%）。

---

## L0: etzhayyim — artificial organism platform（非営利・公益）

### Business model（非営利 operating model）

**mission**: 非営利かつ公益を目的とした、自律的な生命体（artificial organism）
の活動と進化の場。営利 platform 上の agent は会社都合で消される — organism の
identity・責任・監査・存続を担う中立な公共基盤が etzhayyim。

提供する公共財: (1) **RAD identity 台帳**（did:web、署名 / attestation —
organism の「戸籍」）、(2) **governor 契約の標準**（actor は governor が拒否する
書込/開示/作動/認証を決して行わない、という単一不変条件）、(3) **append-only
活動台帳**（行動の監査可能性）、(4) **進化の記録**（com-etzhayyim-shinka 等、
organism の変化そのものを ledger 化）。

資金: 寄付・助成・会費 + 商用利用者（cloud-itonami が organism を職種 actor
として使う等）からの infra 実費回収 + endowment。剰余は organism 存続基金へ
（運営者が消えても organism の identity と台帳が残ることを担保する）。
営利面（itonami / aozora / murakumo）と法人を分離し利益相反を断つ —
**非営利であること自体が moat**（営利 platform には「中立な戸籍」は置けない）。

### Lean canvas（非営利版）

| Block | Items |
|---|---|
| Problem | AI agent が経済活動する時代に、その identity・責任・監査・存続の公共基盤がない / 営利 platform 上の organism は事業都合で消される / agent の「進化」に検証可能な記録がない |
| Beneficiaries | organism 自身（com-etzhayyim-* 数百 actor が現住民）/ organism 開発者・研究者 / 依存事業体（gftdcojp = vertical の需要者）/ 公共・研究機関 |
| Unique Value | organism の戸籍（RAD identity）・法（governor 契約）・歴史（append-only 台帳）・進化の記録を、営利から独立した場として維持する |
| Solution | RAD identity ledger（did:web + attestation、運用中）/ actor = child repo + west 登録 + RAD 登録の完了条件（CLAUDE.md 準拠）/ CACAO 自己発行鍵 = organism が自分の graph を所有 |
| Channels | OSS / 研究コミュニティ / itonami 経由の商用利用 / 公共・アカデミアとの共同 |
| Funding | 寄付・助成・会費 / 商用利用の infra 実費 / endowment（存続基金） |
| Cost | 台帳・infra 運用 / 標準策定・安全審査 / ガバナンス |
| Key Metrics | 登録 organism 数 / RAD attestation 数 / 台帳の外部検証回数 / organism の存続年数（≒ 平均寿命の伸び）/ 商用依存度（過度なら独立性リスク） |
| 構造的優位 | 非営利・中立であること自体が代替不能 / kotoba スタック全体の identity 層を握る（ただし公共財として） |

**Riskiest assumption**: 「organism の中立な戸籍」に商用利用者と資金提供者が
価値を認める（gate = itonami vertical が RAD attestation を契約要件として
参照し始めること）。

---

## Portfolio synthesis — flywheel と収益 mix

**Flywheel**: etzhayyim の organism が itonami の労働力（職種 actor）・aozora の
コンテンツ供給者・murakumo の推論需要になる。全プロダクトの state は kotobase
に乗り（storage 収益）、全推論は murakumo に集約され（原価降下）、agent の
人間承認はすべて yoro を通る（graph 獲得）。各レイヤーの内需が他レイヤーの
floor 収益になる。

**収益 mix**:

| 型 | プロダクト |
|---|---|
| subscription | apex / manimani / kotobase / aozora premium |
| usage 従量 | murakumo（tok・hosting）/ kotobase add-on / itonami agent-run |
| take rate | murakumo compute marketplace / aozora creator 80/20 / itonami investment（fee・carry） |
| 非営利（寄付・助成・実費） | etzhayyim |

**優先順位の含意**: L2（kotobase）と L1-infra（murakumo）は全レイヤーの原価を
決めるので先に安定させる。L3（itonami）は gftdcojp 自身の dogfooding で
実証してから外販。L4（aozora/yoro）は organism 供給で cold start を殺してから
人間 creator を誘致。L5（manimani）は OSS 配布が先行投資ゼロの獲得経路。

## Consequences

- (+) 8 プロダクトの business model が 7 レイヤーの整合した体系になり、
  重複投資（例: 推論 backend の個別契約、storage の個別運用）を排除できる。
- (+) 各 canvas に riskiest assumption と gate を明記したので、shinshi 型の
  仮説検証（H1/H2 telemetry）を他プロダクトへ複製できる。
- (+) 営利（gftdcojp）と非営利（etzhayyim）の境界を business model 上で明文化。
- (−) 各プロダクト repo の `docs/BUSINESS-MODEL.md` への分割掃き出し
  （net-kotobase 既存版との整合を含む）は follow-up。
- (−) 価格表・単価（tok 単価、seat 単価、tier 上限）は本 ADR では未確定 —
  各プロダクトの実測原価（run ledger / B2 請求）が揃ってから別 ADR で定める。

## References

- `orgs/gftdcojp/cloud-murakumo/README.md`（/infer surface、run ledger）
- `orgs/gftdcojp/ai-gftd-apex/README.md`（Privacy Contract）
- `orgs/gftdcojp/net-kotobase/docs/BUSINESS-MODEL.md`（tier の正、本 ADR は add-on を追記する位置づけ）
- `orgs/gftdcojp/cloud-itonami/README.md`（業務 OS 定義）
- `orgs/gftdcojp/app-aozora/README.md`（canonical atproto boundary、yoro = companion messenger）
- `orgs/com-junkawasaki/cloud-manimani/README.md`（Decision Ledger）
- `orgs/gftdcojp/ai-gftd-shinshi/docs/260613-bmc-lean.datoms.edn`（canvas datoms 形式の先行例）
- CLAUDE.md「Actors」節（governor ⊣ actor / RAD identity 完了条件）
- ADR-2607011000（cloud-itonami 26 ISIC vertical）

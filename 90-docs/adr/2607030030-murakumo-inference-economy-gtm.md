# ADR-2607030030: murakumo 推論経済の事業設計 — 一般ユーザー / 企業 / blockchain ユーザーの三面市場

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

murakumo は「fleet を 1 つのモデルホストにする」技術面が揃った:
exo 型 planner + engine (ADR-2607022000)、memory×time credits、kotoba-native
shard 契約 (ADR-2607022300)、そして本 ADR と同日に **償還 (`/infer/spend`,
`credits/charge`) と検証可能領収書 (`credits/receipt`)** が入り、経済が
「発生 → 台帳 → 口座 → 償還」まで一周した。問いは「これはグローバルな
需要として成り立つか」。

## 正直な前提（ポジショニングの土台）

**raw $/token では hyperscaler に勝てない。** 実測が根拠: M4 mini 3 台の
Gemma-26B は 13.9 tok/s。H100 ファームの同クラス serving 単価に対して
電力効率でも密度でも劣る。したがって**売るのは FLOPS ではない**。売るのは:

1. **既保有ハードの限界費用ゼロ利用** — 世界の企業に眠る Apple Silicon
   （開発機・デザイン機・教室の Mac、夜間 16h idle）を、追加投資ゼロで
   OpenAI 互換の私設推論クラウドに変える。exo (OSS 停滞) と petals
   (公開 swarm 研究) が取り残した未対応市場。
2. **主権と監査** — トークンが自組織の overlay から出ない
   (murakumo.cloud、default-deny policy)。全推論が actor 署名の
   append-only 台帳に落ち、**どのノードがどの shard を保持し何 token を
   計算したか**が byte 検証つきで残る (shard 検証 + receipt)。
   EU AI Act / 国内ガイドラインのトレーサビリティ要件に対する
   「監査可能な推論」はコモディティ化していない。
3. **貢献の計量単位** — memory×time credits。配置(plan)と精算が同一の
   数式で貫通する唯一性が、内部チャージバックにも外部マーケットにも
   同じ台帳で効く。

## 三面の需要定義

### 企業（最初に取る。収益の主柱）
- **需要**: (a) Mac fleet の遊休活用 (b) データ主権推論 (c) 監査台帳
  (d) 部門別チャージバック — credits の memory×time 台帳が**そのまま
  社内原価配賦**になる (e) treasury = ハード更新予算の需要シグナル。
- **プロダクト**: murakumo Enterprise = control plane 課金
  (ノード数 subscription + support)。推論自体は顧客ハードで走る =
  当方の限界費用ゼロ。Tailscale/wasmCloud と同じ OSS-core →
  enterprise-control-plane 型。kekkai admission gate が teilnehmer 管理面。
- **GTM**: 自 fleet (gftd) が design partner / 実証済みリファレンス。
  Apple-heavy な開発会社・制作会社・教育機関から。

### 一般ユーザー（ネットワークの厚み）
- **需要**: 「自分の Mac が夜稼いだ credits で、昼に 1 台では動かない
  クラスのモデルを使う」。80B を 6 台で回した実測がその証明。
  参加は `curl murakumo.cloud/join | sh`（将来: **ブラウザタブ = 参加**、
  kotodama.inference の WebGPU 契約が browser-first なのはこのため）。
- **課金**: 貢献者は無料 (credits 自給)。非貢献者は fiat → credits
  (Stripe)。**fiat 購入がネットワークの外部収益**、take = treasury 5% +
  スプレッド。
- **順序の規律**: 公開マーケットは Phase 3。先に企業 fleet 連邦
  (Phase 2: 既知組織間の credits 相互運用) で品質と Sybil 前提を固める。

### blockchain ユーザー（相互運用の縁）
- **需要**: (a) on-chain agent が消費できる**検証可能な推論証明** —
  receipt (settle + shard byte 検証 + hash chain + CACAO 署名) が
  attestation そのもの (b) compute へのトークン化アクセス。
- **設計**: **murakumo 推論経済専用の新 L1 は作らない**。運用台帳は off-chain
  の actor 署名 feed のまま、チェーンとの接続は **mint/burn gateway のみ**
  （receipt を根拠に定期精算）。グローバル合意が必要になるまでコンセンサスを
  持ち込まない、という ADR-2607022000 の立場を維持。**2026-07-15 追記**:
  この「新L1は作らない」は ADR-2607993000 により ENGI/EN スコープに限定して
  撤回され、`kotoba-lang/engi` が chained HotStuff 型 BFT の L1 になった
  （詳細は同ADR参照）。ただし murakumo 推論経済の chain gateway が
  mint/burn 限定である本節の決定自体は不変 — engi/L1 は ENGI/EN 専用であり、
  murakumo の gpu-seconds 経済台帳をそこに巻き込まない。
  **2026-07-15 追記2(ADR-2607995000 三圏経済)**: 上記 gateway は
  **mint-only に再スコープ**された — burn 側(credits→チェーン上の資産)は
  「credits は換金不可の前払い使用権」という膜規則に抵触するため作らない。
  また「経済台帳を engi/L1 に巻き込まない」は Phase 3 が credits 台帳に
  グローバル順序を要求するまでの時限条項に再スコープされた(その時点で
  新 L1 ではなく engi/L1 の2番目のドメインとして乗る)。credits の
  fiat 償還(payout)経路は存在しない・作らないことも同ADRで明文化。
- **前提条件（未実装、Phase 3 ゲート）**: proof-of-compute。
  proof-of-storage は shard 検証で実装済み。計算の証明は
  **決定論的等価性**（shard_test が示した double 完全一致）を使った
  サンプリング再計算 + 不一致で credits 没収 (slashing) が設計案。

## 経済ルール（確定分）

- **発行は労働裏付けのみ**: settle された run だけが credits を生む。
  pre-mine 無し。treasury 5% + head 10% は取り分として明文化済み。
- **償還**: `/infer/spend` — credits が推論を買う唯一の非 fiat 手段。
  残高不足は 402。earn と burn は同一 feed・同一 fold（実装済み）。
- **価格**: v1 は registry 固定 (:credit/per-token per model)。動的価格は
  lattice free-gas auction の系譜で Phase 2+。
- **フライホイール**: 貢献者↑ → 載るモデルクラス↑（11 台で 80B の実証）
  → 需要↑ → credit 流速↑ → treasury↑ → ハード増強 → 貢献価値↑。

## フェーズと完了条件

| Phase | 内容 | ゲート |
|---|---|---|
| 1 (今) | 単一テナント企業 fleet + 社内チャージバック | 実運用 1 fleet (済: gftd)、control plane 課金設計 |
| 2 | fleet 連邦 + credits 相互運用 + murakumo.cloud 公開 | ノード自己署名 feed、overlay の QUIC/WebRTC 結線 |
| 3 | 公開マーケット + ブラウザ貢献 + chain gateway | proof-of-compute、kotoba-native full forward (browser WebGPU) |

## Consequences

- 「分散推論プロジェクト」ではなく「**遊休ハードの主権推論 + 監査可能な
  計量経済**」として売る。性能比較の土俵 (vs H100) を意図的に降りる。
- credits は社内チャージバック (企業) と マーケット (一般/chain) の
  両方に同じ実装で効く — 実装の一本化が事業の梃子。
- blockchain 面は receipt gateway に限定することで、規制・投機リスクを
  コアから隔離する。

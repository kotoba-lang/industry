# ADR-2607093100: x402 (Cloudflare Monetization Gateway) を pay レールの標準ワイヤプロトコルに統合

- **Status**: accepted（2026-07-09 オーナー指示: 「blog.cloudflare.com/monetization-gateway
  を設計統合してみて」）。`pay.x402` コーデックは実装・landed、Worker ミドルウェア配線は
  reviewed follow-up。
- **Related**: ADR-2607092700（決済主体=JK株式会社 / `@etzhayyim/sdk`→`kotoba-lang/pay`）、
  ADR-2607071900（club-shinshi 受信専用 USDC レール `/pay/quote|claim|status`）、
  ADR-2607051621（`kotoba-lang/treasury` USDC on-chain 検証）
- **参照**: [blog.cloudflare.com/monetization-gateway](https://blog.cloudflare.com/monetization-gateway/)

## Context

Cloudflare が Monetization Gateway を発表した。中核は **x402**（HTTP `402 Payment Required`
を使う帯域内マイクロペイメント）: ゲート対象リソースが価格・受入資産・支払先を **402 応答本体**
で返し、買い手（人間ウォレット **または自律エージェント**）がステーブルコインで払い `X-PAYMENT`
ヘッダに proof を載せて再送、facilitator が検証してリソースを配信（`X-PAYMENT-RESPONSE`
レシート付き、リダイレクトも checkout ページも seller onboarding も無し）。

これは我々が既に持つものと構造が一致する:

- `kotoba-lang/pay`（`entitle` = verify-before-honor、`PayRail` seam、USDC micros）
- `kotoba-lang/treasury`（USDC の on-chain 検証: 宛先違い/不足額/confirmation 不足を弾く）
- club-shinshi の `/pay/quote|claim|status`（ADR-2607071900）は、まさに x402 を **帯域外・非標準**
  でやっている版（quote≈402 応答、claim≈`X-PAYMENT` proof、verifier≈facilitator）

つまり x402 は我々の自前レールが収束すべき **標準ワイヤ形式**であり、かつ Cloudflare の製品は
closed waitlist の facilitator-as-a-service だが、**我々は treasury（on-chain verify）+ pay
（entitle）で自前 facilitator になれる** — ベンダーロックも待機列も不要で、既に制御下にある
Base L2 / USDC で決済する。

戦略的な意味: x402 は「per-request 従量課金（`$0.01 for every GET to /api/premium/*`）」を
エージェント相手に成立させる。これは逆トポソートの **L3（cloud-murakumo 推論 / net-kotobase
storage）を USDC で収益化する機構**そのもので、循環を閉じる鍵になる（アダルト葉だけでなく
インフラ層の monetization パスが開く）。

## Decision

1. **x402 を `kotoba-lang/pay` の標準ワイヤプロトコルとして採用**し、pure `.cljc` コーデック
   `pay.x402` を実装（landed、14 tests/85 assertions、clj-kondo clean）。ライブラリ不変条件
   （zero-dep / zero network I/O / zero key custody）を維持し、protocol 層のみを担う:
   - `payment-requirements` / `challenge` — 402 応答本体を構築（価格は `parse-usdc` で micros）
   - `encode-header` / `decode-header` — `X-PAYMENT` / `X-PAYMENT-RESPONSE` の UTF-8 base64
     エンベロープ（JSON 直列化は host 側に残し zero-dep を保つ）
   - `payload-errors` / `acceptable?` — 復号済み payload の純検証（scheme/network/宛先/金額/
     期限）。`exact`（EIP-3009 `transferWithAuthorization`、gasless、facilitator が submit）と
     `transaction`（tx-hash、既存 treasury/verify に 1:1 対応）の 2 スキーム
   - `authorize` — verify-before-serve 判定（x402 版の `entitle`）: host が on-chain 決済確定を
     報告した場合のみリソース配信、それ以外は 402 で hold

2. **自前 facilitator**。x402 の検証・決済は closed ベンダーに委ねず、`treasury/verify-payment`
   （`transaction` スキーム）または host が submit する EIP-3009 authorization（`exact` スキーム）
   で行う。`pay.core/verification<-treasury` が treasury 結果を `authorize` の入力に橋渡しする。
   鍵は一切保持しない（買い手が自ウォレットから支払う / エージェントが自 wallet で署名）。

3. **Worker ミドルウェアは follow-up**（reviewed、未デプロイ）。cljs Worker（shinshi /
   将来の kotobase / murakumo edge）に「`X-PAYMENT` 無ければ 402 challenge を返す / 有れば
   decode→`authorize`→配信」の薄いミドルウェアを1つ置く。JSON.stringify/parse は Worker 側。
   club-shinshi の既存 `/pay/*` は当面併存（人間フロー）、x402 はエージェント・per-request 用の
   帯域内経路として追加する（置換ではなく superset）。

4. **段階導入**（葉→根、逆トポソート順）:
   - **L0**: club-shinshi の PPV シーン解放を x402 でゲート（エージェント購入 + 既存 /support
     人間フローが同一 treasury で共存）
   - **L3**: net-kotobase storage GET / cloud-murakumo 推論エンドポイントを x402 従量課金化
     — これが「usage-based pricing for everything」の本命で、DePIN 層の収益化パス
   - Web Bot Auth（Cloudflare の verified agent identity）は将来オプション（エージェント認証を
     強めたい場合。x402 自体は無くても動く）

## Consequences

- **`pay.x402` は landed / 完結**（コーデックとしては使用可能）。残るは Worker ミドルウェアの
   実装とデプロイで、これはオーナー所有の Cloudflare ターゲット（shinshi worker）へのデプロイを
   伴うため、並行 subagent との競合回避ルール（本セッションで確立）に従って別タスク化する。
- **EIP-3009 の submit/confirm は host 責務**。`exact` スキームで facilitator が authorization を
   on-chain に流す部分（gasless transfer の relay）は pay/treasury の外（Worker + base-l2/wallet）。
   `pay.x402` は shape/経済検証と decision までを純粋に担う。
- **既存レールとの関係**: ADR-2607071900 の `/pay/*` は帯域外の人間向けとして残し、x402 は
   帯域内のエージェント/per-request 向け。両者とも treasury で検証し、同一 JK株式会社 treasury
   に着金する（tx-hash 単位で attribution）。
- **Cloudflare 製品との関係**: 我々は Monetization Gateway の顧客になるのではなく、その標準
   （x402）を採用しつつ自前 facilitator を運用する。将来 CF の facilitator を使いたくなっても
   ワイヤは同一なので移行コストは小さい（ロックインしない設計）。
- 数値・スキーム詳細は x402 公開仕様と Cloudflare blog の記載に基づく概算で、監査済み実装契約
   ではない。EIP-3009 relay の本番化前にガス/nonce/リプレイ保護の実装レビューが要る。

## Alternatives Considered

1. **Cloudflare Monetization Gateway（closed waitlist）をそのまま採用**。却下 — waitlist 依存 +
   facilitator がベンダー固定になり、我々が既に持つ treasury/pay の自前決済スタックを捨てることに
   なる。x402 という**標準だけ採る**方が主権的で、ロックインしない。
2. **独自の帯域外レール（現行 `/pay/*`）だけで続ける**。却下 — エージェント経済（記事の中核）に
   対応できず、per-request 課金（L3 収益化）も帯域外フローでは自然に表現できない。x402 の帯域内
   402 が agent-native の標準。
3. **x402 の検証まで `pay.x402` に入れる（on-chain I/O を持たせる）**。却下 — ライブラリの
   zero-I/O / zero-dep 不変条件を破る。protocol 層（純粋）と settle 層（host 注入）を分離し、
   `PayRail`/`entitle` と同じ seam 設計に揃える。

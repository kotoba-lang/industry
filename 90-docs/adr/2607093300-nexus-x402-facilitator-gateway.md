# ADR-2607093300: gftdcojp/nexus-x402 — 自前 x402 決済 facilitator/gateway、ゲートウェイ核は kotoba-lang/pay

- **Status**: accepted（2026-07-09 オーナー指示:「gftdcojp/nexus-x402 を設計して、x402 の
  決済 gateway を kotoba-lang で設計」）。`pay.facilitator` 実装・landed、`gftdcojp/nexus-x402`
  scaffold・GitHub repo 作成・push 済み、manifest 登録・Worker デプロイは本 ADR で確定/follow-up。
- **Related**: ADR-2607093100（x402 を pay の標準ワイヤに採用、`pay.x402`）、ADR-2607092700
  （決済主体=JK株式会社、`@etzhayyim/sdk`→`kotoba-lang/pay`）、ADR-2607051621（treasury）
- **参照**: [blog.cloudflare.com/monetization-gateway](https://blog.cloudflare.com/monetization-gateway/)

## Context

ADR-2607093100 で x402 を pay レールの標準ワイヤに採用し、shinshi（L0）/ murakumo・kotobase
（L3）に個別のゲート middleware を配線した。しかし現状は**各 seller worker が pay.x402 +
treasury.core を vendor し、Basescan fetch・verify・challenge/authorize 配線を各自で重複実装**
している。Cloudflare の Monetization Gateway は対照的に**中央 facilitator**（seller は価格
ルールを登録するだけ、CF がエッジで検証）である。

オーナーから「nexus-x402 を設計し、x402 決済 gateway を kotoba-lang で設計せよ」との指示を受け、
**自前の中央 facilitator サービス**（`gftdcojp/nexus-x402`）と、その**ゲートウェイ純ロジックを
kotoba-lang ライブラリ**（`pay.facilitator`）として設計・実装する。

## Decision

1. **ゲートウェイ核は `kotoba-lang/pay` の `pay.facilitator`**（pure `.cljc`、landed、
   19 tests/112 assertions、clj-kondo clean）。ライブラリ不変条件（zero-dep/zero-I/O/
   zero-custody）を維持し、以下を担う:
   - **rules engine / seller registry**: `{:seller :method :path-prefix :usd :pay-to :chain
     :scheme}` の順序付きルール（first structural match）。多数の seller（shinshi/murakumo/
     kotobase、各自 own treasury）が**鍵を持たない単一 facilitator を共有**。`match-rule` /
     `rule->requirements`
   - **`verify`**: x402 `/verify` 判定（`{:isValid :invalidReason :payer}`）— 純粋な
     shape/経済検証 + host の on-chain verdict
   - **`settle`**: x402 `/settle` 判定（`authorize` 再利用）
   - **`gate`**: transport 非依存の end-to-end 判定（`:pass`/`:challenge`/`:serve`/`:hold`）
   - **`discovery`**: `/.well-known/x402` facilitator discovery 文書

2. **`gftdcojp/nexus-x402`** = デプロイ可能な facilitator Worker（cljs `:esm`、private repo、
   scaffold・push 済み）。`pay.facilitator` + `pay.x402` + `treasury.core` を vendor し、
   HTTP エンベロープと on-chain I/O（Basescan verify）のみ追加。2 つの統合モード:
   - **Facilitator API**（薄い委譲）: `POST /verify` / `POST /settle` / `GET /.well-known/x402`
     — seller は自前ゲートを保持しつつ検証を nexus に委譲
   - **Gateway proxy**（前段配置）: `ANY /gateway/<seller>/<path>` — X-PAYMENT 無しなら
     402 challenge、有れば on-chain 検証 → seller origin へプロキシ + X-PAYMENT-RESPONSE
   - seller ルール + origin は `SELLERS_JSON` config（**公開 treasury アドレスのみ、鍵なし**）。
     `BASESCAN_API_KEY` は `wrangler secret`。honest default: 未知 seller → 404、未検証 → 402

3. **鍵ゼロ・マルチ seller・自前 facilitator**。各 seller の own treasury に着金し、nexus は
   検証のみ（closed ベンダー facilitator に依存しない）。x402 標準ワイヤなので、将来 CF の
   facilitator を併用したくなっても移行コストは小さい（ロックインしない）。

4. **既存の個別ゲートとの関係**: shinshi の `/x402/premium/*`（稼働中）と murakumo/kotobase の
   個別ゲート（PR）は**当面併存**。nexus は「seller が vendor 重複をやめて委譲する」中央化の
   選択肢を提供する — 段階的に個別ゲートを nexus 委譲へ移行できる（強制ではない）。

## Consequences

- **`pay.facilitator` は landed / 完結**（gateway 核として使用可能）。`nexus-x402` は scaffold +
  repo 作成 + push 済みで、Worker ビルドは `:esm` 0 warning、smoke test 2/7 green。
- **follow-up（デプロイ）**: `nexus-x402` を Cloudflare へデプロイ（custom domain 例
  `nexus.gftd.ai`）、`SELLERS_JSON` に各 seller のルール + origin + 公開 treasury を投入、
  `BASESCAN_API_KEY` を secret 設定。これは gftdcojp の Cloudflare リソースに触れるため、
  並行 subagent との競合回避ルール（本セッション確立）に従い調整の上で行う。
- **follow-up（移行）**: shinshi/murakumo/kotobase の個別ゲートを nexus 委譲（Facilitator API）
  へ寄せるか、gateway proxy 前段に置くかは seller 単位で選択。vendor した pay.* の drift は
  各 repo で `nbb`/diff 監視。
- **EIP-3009 `exact` スキーム**: `discovery` は exact を宣言するが、authorization の on-chain
  submit（gasless relay）は base-l2/wallet 側の未実装 follow-up。現状の実効経路は
  `transaction`スキーム（買い手が先に tx broadcast → tx-hash proof）。
- manifest 登録: `orgs/gftdcojp/nexus-x402` を repos.edn に追加、`--entry nexus-x402` の最小
  diff で west.yml 生成、pin == repo HEAD をサーバ側検証。

## Alternatives Considered

1. **各 seller が個別ゲートを vendor し続ける（現状維持）**。却下 — Basescan fetch・verify・
   配線の重複、treasury 検証ロジックの分散、cross-seller のルール/discovery 不在。中央
   facilitator が Cloudflare Monetization Gateway の設計思想であり、重複を排する。
2. **Cloudflare Monetization Gateway（closed waitlist）を facilitator として採用**。却下 —
   ADR-2607093100 と同じ理由: waitlist 依存 + ベンダー固定。標準（x402）だけ採り自前 facilitator。
3. **gateway 核を nexus-x402 内に直接書く（kotoba-lang ライブラリにしない）**。却下 — seller
   worker も同じ核を再利用（委譲でなく埋め込みたい seller もいる）ため、pure `.cljc` の
   kotoba-lang ライブラリに置くのが正しい層。オーナー指示「kotoba-lang で設計」とも整合。
4. **facilitator に鍵を持たせて EIP-3009 を submit させる**。却下（現時点） — facilitator の
   no-key-custody 不変条件を破る。gasless relay は payer デバイス署名 + base-l2 bundler 経由の
   follow-up として分離し、facilitator は検証のみに留める。

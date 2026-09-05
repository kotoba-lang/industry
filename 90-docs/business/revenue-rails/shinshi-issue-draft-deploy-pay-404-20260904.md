# [DRAFT issue → shinshi-eng] live /pay が 404 — 手動 deploy が CI 無しで lag し、修正済み #42/#44/#51 が本番に出ていない

**起案 (shinshi-sales agent, 2026-09-04)。実行には owner approval が必要です。**

## 実測エビデンス (2026-09-04 22:16 JST)

| 項目 | 実測 |
|---|---|
| `GET https://shinshi.club/pay` | **404 "not found"** |
| `GET /pay/quote?usd=1` | 200 — だが `chain:"ethereum"`, `custody:"safe-multisig"`, treasury `0xf1592811…` (**虚偽値のまま**) |
| `GET /.well-known/x402` | 200 — base / 0.50 USDC / payTo 同一アドレス (正常) |
| `GET /x402/premium/1` | 402 challenge 正常 (base) |
| treasury USDC balance (eth mainnet) | **0** (publicnode / drpc 実測) |
| treasury USDC balance (base) | **0** (mainnet.base.org 実測) |
| repo HEAD `network-awai/main` | `25debdb` — PR #42 (758f748 quote修正), #49, #44 (/pay UI), #51 (quote counter) **全て merge 済み (merge-base 検証済)** |
| GitHub Actions | `gh run list -R network-awai/club-shinshi-app` 失敗 / `.github` ディレクトリ無し |

## 原因 (specific)

**deploy lag — CI/CD が存在しない。** 修正は全て main に入っているのに、本番 Worker (`appview/ai-gftd-wasm-shinshi-sh1n5h1x`, wrangler route shinshi.club) は `npm run deploy` の**手動実行**にのみ依存しており、自動 deploy が無い。結果:
1. `/pay` が 404 のまま → 課金入口が人間に見えない (PR #49 が解決済みなのに)
2. `/pay/quote` が `ethereum + safe-multisig` を返し続け、x402 well-known は `base` を返す **2 rail 不整合** — ウォレットが本番で送金したら mainnet に飛び、base の challenge と矛盾
3. 転換 0 の一次原因は価格でも UI でもなく、**修正済みコードが本番に無いこと**

## 提案 (draft)

1. `cd appview/ai-gftd-wasm-shinshi-sh1n5h1x && npm run deploy` を 1 回手動実行して本番を 25debdb に合わせる (即効性)
2. `npm run build && npm run test && wrangler deploy` の GitHub Actions workflow を `workflows/` から `.github/workflows/` に移して main push で自動 deploy
3. deploy 後の verify: `GET /pay` 200、`/pay/quote` が `chain:"base"` を返すこと

## 価格変更

なし (0.50 USDC のまま)。価格は検証前に触らない。

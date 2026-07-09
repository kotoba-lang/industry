# ADR-2607021800: bmc collect — Cloudflare/Stripe 実測を business loop に接続

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

オーナー指示（2026-07-02）: 各プロダクトの成熟度を向上し、facts を集め、
Cloudflare・Stripe などから実データを収集して、それぞれの business を回す。
ADR-2607021700 の facts 初回値は自己評価で、実測の裏付けがなかった。

## Decision

1. **`70-tools/bmc/collect.bb`** を追加。Cloudflare GraphQL（zone
   `httpRequests1dGroups` 7 日 + `workersInvocationsAdaptive` 7 日）、Stripe
   （active subscriptions / charges）、本番 health を収集し、
   `90-docs/business/metrics/<product>.edn` に集計数値のみ書く（実測の SSoT）。
   認証は env → Keychain `gftd.cf` / 1Password `STRIPE_SECRET_KEY` の順で解決し、
   **秘密は一切ファイルに書かない**。ReAct loop は各 metrics の `:signal` を
   観測として canvas に取り込む（advisor 提案 → governor 検閲 → ledger）。

2. **operating cadence（business を回す最小形）**: `collect.bb` → 全 product
   `react loop` → `canvas md --all` → `score md` → commit。この列が 1 運転。

3. **実測に基づく facts 改訂**（maturity-facts.edn、証拠は各 note に記載）:

   | 実測 (7d, as-of 2026-07-02) | facts 改訂 |
   |---|---|
   | gftd.ai 422,889 req・uniques 日次和 4,644 | apex launched 2→3, users 1→2, grounding 3→4 |
   | ai-gftd-murakumo worker 158,768 inv | murakumo launched 2→3, grounding 4→5 |
   | etzhayyim.com 136,214 req + did-web/xrpc 85,862 inv | etzhayyim launched 3→4 |
   | aozora.app 14,313 req・PV 6,960 + workers 3,521 inv | aozora launched 2→3, users 1→2, grounding 3→4 |
   | aozora-yoro-appview/pds/spa 1,673 inv（デプロイ済と判明） | **yoro「設計のみ」を訂正**: launched 0→2, grounding 1→3, users 0→1 |
   | manimani.cloud 443 req（uniques 1） | manimani launched 2→3（users 1 据置） |
   | kotobase.net 3,483 req・uniques 272、**Stripe active subs 0** | kotobase 据置（revenue 1 = billing wired が上限の正直値） |
   | itonami.cloud 6 req・uniques 3 | itonami 据置（外部に未発見が実測で確定） |

4. **スコア改訂**（BMC / YC bench）: yoro 44/30→**52/40**、murakumo 60/48→**64/52**、
   aozora 60/40→**64/47**、apex 56/38→**60/45**、etzhayyim 64/57→**64/60**（YC 軸トップ、
   非営利につき参考値）、manimani 56/40→56/43、kotobase 76/58 据置（総合トップ）、
   itonami 56/43 据置。**validation は依然全 product 0、revenue 0–1** — 次の
   1 手はスコア表でなく現実側（kotobase 初 paid tenant / murakumo 原価実測 /
   itonami vertical 絞込）。

## Consequences

- (+) facts が実測根拠つきになり、canvas の Problem block に実測観測が
  ledger 経由で積まれた（8 product、全 loop 収束）。
- (+) 収集は再実行可能（collect.bb、集計数値のみ・秘密なし）。
- (−) Keychain `StripeCLI` の鍵は期限切れだった — 1Password
  `Stripe Live API Keys` が有効。Stripe telemetry の常設接続は kotobase
  billing worker 側の webhook/metrics 化が本筋（follow-up）。
- (−) uniques の 7d 値は日次 uniques の単純和（重複含む）— 傾向指標として扱う。
- (−) 収集の定期運転（cron / `/loop`）は未設定 — オーナー判断待ち。

## References

- ADR-2607021500 / 2607021600 / 2607021700
- `90-docs/business/metrics/*.edn`（実測 SSoT）/ `maturity-facts.edn`（証拠つき facts）

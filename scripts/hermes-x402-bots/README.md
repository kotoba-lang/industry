# hermes-x402-bots — x402.nexus autonomous ops fleet

自律的に x402.nexus を運営・拡大する Hermes bot profile 群の正本スクリプト。
`~/.hermes/profiles/<profile>/scripts/` へコピーして使う（.hermes.md の cron bot ルールに従う）。

## 現状の正（2026-09-03 計測）

- `GET https://x402.nexus/health` → live (nexus-x402)
- catalog: 31 SKU / 8 sellers (murakumo 8, colombia-trm 9, hanmoto 6, kotobase 4,
  kekkai / gleif / hyakka / shinshi 各1)
- settlements (aggregate): 7 settled / $0.083 USDC / agent 5, human 0, unknown 2
  / repeat-rate 0.33 (direct), direct-agent repeat-rate 1.0
- facilitator は鍵を持たず seller treasury 直行 → プラットフォーム自身の収益レイヤーは未設計。
  収益化は「実績が出てから ADR で fee 導入」が今回の意思決定。
  候補: (a) facilitator fee (settle の 2-5%), (b) x402.nexus 自身の seller SKU。
  ADR-2607093300 / -2607093100 が設計正本。

## Profiles

| profile | 役割 | cron | 正本 |
|---|---|---|---|
| x402-pnl | /stats + catalog 計測、seller 別 GMV、前回差分、fee 導入提案 | 30m | `x402_pnl_tick.sh` |
| x402-ops | health / catalog / llms.txt / apply 死活 + 計測値の整合 | 30m | `x402_ops_tick.sh` |
| x402-growth | catalog 差分から新 seller リード検知、オンボーディング cone（/apply pitch） | 2h | `x402_growth_tick.sh` |
| x402-mktg | llms.txt・ディレクトリの agent-discoverability 検証（agent 購買 5/7 を伸ばす） | 6h | `x402_mktg_tick.sh` |

## 使い方

```
# 正本 -> profile へ
for p in x402-pnl x402-ops x402-growth x402-mktg; do
  mkdir -p ~/.hermes/profiles/$p/scripts
  cp x402_*.sh ~/.hermes/profiles/$p/scripts/
done
chmod +x ~/.hermes/profiles/x402-*/scripts/*.sh

# cron は CLI から HERMES_HOME=~/.hermes/profiles/<name> 付きで作成
```

## 状態

- 各 tick は `~/.hermes/profiles/<profile>/state/` に EDN/JSON で前回値を残し、
  差分ベースで報告する（静かな時は静か）。
- pnl は 3 値 (`up` / `flat` / `unmeasured`) を守る — itonami-actor-pnl.cljs の
  訓えに従い、zero margin を「儲かっていない」と誤読しない。

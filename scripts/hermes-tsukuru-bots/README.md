# hermes-tsukuru-bots — tsukuru 運営 fleet の正本

tsukuru (B2B factory-direct 商品設計・物理 sim 面, itonami.cloud/sites/app/tsukuru/)
を運営する Hermes bot profile 群。cron jobs の正本はここに versioned で置き、
`~/.hermes/profiles/<name>/` へは同期したコピーを使う (他 hermes-* と同じ規律)。

## Profiles

| profile | 役割 | cron | ツール |
|---|---|---|---|
| tsukuru-sim | sim 実行: repo を pull → 全 product を tsukuru.sim に通す → findings を status/sim-ledger.edn に追記。findings が増えたら名指しで報告 | 30 分毎 (tick) | terminal/file/web |
| tsukuru-design-review | 設計審査: 未審査 product 宣言 (products/*.edn) を 1 件読み、BOM 完全性 (envelope/mounts-on/insert-axis 必須) と fulfillment-mode の意味妥当性を検査。合格/不合格を審査台帳に追記。コード修正はしない | 30 分毎 (位相をずらす) | terminal/file/web |
| tsukuru-rfq | RFQ 進行: 審査合格 + sim passed の product を production_order (cto/mto/bto) 申立て候補として status/rfq-queue.edn に整理。人の承認待ちで止まる (自動発注しない) | 2 時間毎 | terminal/file/web |

## 正本 → 同期

```
scripts/hermes-tsukuru-bots/<prompt>.md   ← 正本
~/.hermes/profiles/<name>/SOUL.md          ← 同期先 (同じ内容)
```

## 検証

初回 tick は手動で `hermes cron run` を打って出力を確認してから enable。

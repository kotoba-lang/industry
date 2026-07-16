---
name: uriage
description: Monero マイニングフリート(10台 Apple M4)の現在の売上・収益を確認して報告する。SupportXMR プールの未払い残高(amtDue)・支払い済み・プール側ハッシュレート・シェアを取得し XMR と USD で表示。引数 "nodes"/"full" で各ノードの稼働状況(tailnet 経由)も確認する。「売上」「収益」「マイニング いくら」「/uriage」で発火。
---

# /uriage — マイニング収益チェック

Monero マイニングフリートの「売上(収益)」を確認して日本語で報告する。

## 固定情報
- 受取アドレス(公開・表示可): `45qeqPbz1MCbWPXL6NxPhWBkdnDHWvzUYRuP8TU3mGBuZpwmQDSaee2Sr9mYiKwUzzMQTtUpnn63d6wphoFF4gDMKw7RuxE`
- プール: SupportXMR
- 単位: `amtDue` / `amtPaid` は **piconero**(1 XMR = 1e12 piconero)
- フリート10台(tailnet 名:IP):
  `asher:100.96.122.69 benjamin:100.75.169.8 dan:100.98.142.59 issachar:100.89.204.30 joseph:100.82.123.35 judah:100.113.200.45 levi:100.102.78.81 naphtali:100.101.27.85 simeon:100.81.66.86 zebulun:100.66.28.79`

## 手順

1. **プール収益を取得(必須)** — WebFetch で次を取得し、JSON の `hash` / `totalHashes` / `validShares` / `invalidShares` / `amtDue` / `amtPaid` / `txnCount` を抜く:
   `https://supportxmr.com/api/miner/45qeqPbz1MCbWPXL6NxPhWBkdnDHWvzUYRuP8TU3mGBuZpwmQDSaee2Sr9mYiKwUzzMQTtUpnn63d6wphoFF4gDMKw7RuxE/stats`
   (ローカル `curl` はサンドボックスで外部到達不可のことがあるので **WebFetch を優先**。失敗時のみ Bash の `curl` をフォールバック。)

2. **XMR 価格を取得** — WebFetch `https://api.coingecko.com/api/v3/simple/price?ids=monero&vs_currencies=usd`(失敗時は WebSearch "Monero XMR price USD")。

3. **換算** — `amtDue_XMR = amtDue / 1e12`、`amtPaid_XMR = amtPaid / 1e12`。USD = XMR × 価格。

4. **報告(表・日本語)** — 次を出す:
   - **未払い残高 amtDue**: X XMR (≈ $Y) ← これが「今の収益(未払い分)」
   - 支払い済み amtPaid: X XMR(txnCount 回)
   - プール側ハッシュレート `hash`: N H/s(= N/1000 kH/s)
   - 有効 / 無効シェア(reject 率)
   - 日次見込み(概算): `hash / 網hashrate × 720 × 0.6 XMR × 価格`。網 hashrate(~5.8 GH/s 前後)は必要なら WebSearch。
   - 末尾に確認先: `https://supportxmr.com/#/dashboard`(アドレスを貼る)+ API URL。

5. **引数による分岐**($ARGUMENTS):
   - **なし** → 上記プール収益サマリのみ(軽量・速い)。
   - **`nodes`** または **`full`** → 追加で各10ノードの XMRig 稼働を tailnet 経由で確認:
     各ノード `ssh -o BatchMode=yes -o ConnectTimeout=8 <name>@<ip> 'curl -s -H "Authorization: Bearer fleet" http://127.0.0.1:8080/1/summary | tr -d " \n" | grep -oE "\"total\":\[[0-9.]+" | head -1 | grep -oE "[0-9.]+$"'`
     で 10s ハッシュレートを取り、**稼働台数 / 合計 H/s** の表を作る。到達不能ノードは `down/offline` と明記。

## ガードレール
- **秘密情報(復元シード / ウォレットPW / fleet ログインパスワード)は絶対に表示・記録・出力しない。** 受取アドレスは公開情報なので表示可。
- 数値は丸めすぎない(XMR は小数6桁程度まで)。
- pkill でマイナーを止めない(launchd KeepAlive が再起動する)。停止は `sudo launchctl bootout system/com.mining.xmrig`。

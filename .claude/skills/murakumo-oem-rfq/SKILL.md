---
name: murakumo-oem-rfq
description: Mouse / Sycom への murakumo OEM RFQ の業者返信を 1 通だけ読んで対応する。ローカル Claude loop（com.gftd.murakumo-oem-rfq）が 4 時間ごとに tick し、未処理の業者返信がある周だけこれを呼ぶ。手で `/murakumo-oem-rfq` と打ってもよい。「OEM 返信」「Mouse から返事」「Sycom 対応」で発火。
---

# murakumo OEM RFQ — 業者返信を 1 通だけ扱う

**会話履歴を持たない fresh context から読める**こと。前の周が何をしたかは
`~/.itonami/murakumo-oem-rfq/scans.edn` と `loop.ledger.edn` と run 0027 / 0028 から読む。

送信済み RFQ（2026-08-13、inquiry only、PO ではない）:

| Vendor | To | Resend id |
|---|---|---|
| マウスコンピューター コーポレート営業部 | `houjin@mouse-jp.co.jp` | `02568a2b-6c8d-4062-ba66-fe947a4630b9` |
| 株式会社サイコム | `pc-order@sycom.co.jp` | `cae67591-83c8-4be8-9d40-e1eff0e78d34` |

- From: `Gftd Japan 株式会社 <hello@mail.murakumo.cloud>`
- Reply-To: `hello@gftd.co.jp`（返信は Gmail 側に落ちやすい。Resend inbound は From へ返したときだけ）
- 10 営業日 timebox。成功条件は白ラベル可否 / MOQ / リードタイムの書面。

## 1 反復 = 業者返信 1 通

2 通まとめない。無い周は **何も送らない**。

## 手順

### 0. 測る（推測しない）

```bash
nbb ~/.itonami/murakumo-oem-rfq/tick.cljs --json
# または repo 側
nbb scripts/murakumo-oem-rfq-tick.cljs --json
```

- `UNANSWERED=true` / `reason=resend-key-missing` → **返信 0 と書かない。** 鍵が無い。Cursor なら Resend MCP `list-received-emails` と Gmail `search_threads` で測る。launchd は `~/.itonami/resend-api-key`（mode 600）か keychain `gftd.resend`/`API_KEY` が要る。
- `SCANNED=n MATCHES=0` で unanswered でない → 測った空。それが証拠。スコアは上げない。
- 候補の先頭 1 件だけ取る。ledger に同じ `:id` の `:woke` があれば次へ行かず終わる。

Gmail が使えるセッションでは追加で:

```
after:2026/08/13 (from:mouse-jp.co.jp OR from:sycom.co.jp OR from:houjin@mouse-jp.co.jp OR from:pc-order@sycom.co.jp)
```

接続している Gmail が `hello@gftd.co.jp` でないなら、Gmail 0 件は「Reply-To が空」の証明にしない。

除外: `probe@mail.murakumo.cloud`、Amazon Associates、自ドメイン発信。

### 1. 本文から 4 つ抜く

白ラベル可否 / MOQ / リード日数 / GPU を誰が買うか。書いてない項目は `unknown`。捏造しない。

### 2. 返してよいこと / 禁ずること

**返してよい:** RFQ に既に書いた事実の再掲（法人名・法人番号・住所・代表、SKU A/B/C、数量は outlook であって発注ではない、Linux 希望、dual-GPU しない、中古部品しない）。受領確認。不足 4 項目の再質問。プロト見積の依頼（まだ PO ではない）。

**禁ずる:**

- 発注書、代金、カード、送金、trade
- Node 24 / MK-1 が販売中であると言う
- 未計測 llama.cpp tok/s を仕様として書く
- 特商法の空欄を埋める
- 同じ内容の 2 通目（誤字訂正を含む。オーナーが送れと言わない限り）
- CAPTCHA 回避、Mouse ウェブフォームへの投稿
- 契約書・NDA・電話番号が不明な電話約束を単独で結ぶ → owner にescalate

送信は Resend、From `hello@mail.murakumo.cloud`、Reply-To `hello@gftd.co.jp`。idempotency key を付ける（`oem-rfq-reply/<vendor>-<date>-<id>`）。

### 3. 記録する

`90-docs/business/revenue-agent-loop/runs/` に **新しい run** を 1 本（0027 の過去結果は書き換えない）。funnel は qualified lead が増えたときだけ動かす。delivery や空スキャンではスコアを上げない。共有 checkout は触らず worktree で着地。

## ハードゲート

Inquiry / 見積依頼まで。PO と入金は owner。消費者向け Node 24 / MK-1 販売は赤のまま。

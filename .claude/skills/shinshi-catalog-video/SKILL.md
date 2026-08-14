---
name: shinshi-catalog-video
description: club-shinshi の original-series キャラを 1 体だけ i2v し、決定論的品質を測って pass のときだけ shinshi.club に載せる。ローカル Claude loop（com.gftd.shinshi-catalog-video）が 30 分ごとに tick し、yellow / 想定外 fail の周だけこれを呼ぶ。手で `/shinshi-catalog-video` と打ってもよい。「catalog 動画」「character ごとに動画」「shinshi video loop」で発火。
---

# shinshi catalog video — 1 体生成し、測ってから載せる

**会話履歴を持たない fresh context から読める**こと。前の周は
`~/.gftd/shinshi-catalog-video/scans.edn` と `loop.ledger.edn` と run 0029 から読む。

対象: `shinshi.club` original-series 12 名（Cafe Corner ほか）。IP（Genshin / Fate / …）は
`--allow-ip` 無しでは候補にしない。

生成経路は **gad ComfyUI 8188 + Wan2.2 ti2v 5B**。`generation.murakumo.cloud` は
使わない（402 / 課金）。自己購入・dogfood clip は外部売上に数えない。

## 1 反復 = original-series キャラ 1 体

まとめない。無い周は **生成しない**。

## 手順

### 0. 測る（推測しない）

```bash
nbb ~/.gftd/shinshi-catalog-video/tick.cljs --json
# または repo 側
nbb scripts/shinshi-catalog-video-tick.cljs --json
```

- `UNANSWERED=true`（D1 / ssh / Comfy 不通）→ **埋めるものが 0 と書かない。**
- `SCANNED=n MATCHES=0` で unanswered でない → 測った空。catalog がこの LIMIT 内で埋まっている。
- `GPU busy` → 生成しない。H3（8191）が載っていると VRAM が足りないことがある。
- 候補の先頭 1 件。ledger に同じ slug の直近 6h `:failed` があれば次へ。

品質（モデル無し）:

```bash
nbb scripts/shinshi-catalog-video-quality.cljs <file.mp4>
nbb scripts/shinshi-catalog-video-quality.cljs --self-test
```

| 検査 | pass | fail | yellow |
|---|---|---|---|
| luma | ≥ 8 | < 8 または未測定 | 8–20 で暗い |
| frames | 49 | 他 | |
| size | ≥ 80000 | < 80000（全黒 hazard ~50939） | |
| WxH | 512×768 | 他 | |
| duration | 1.5–3.5s | 他 | |
| 16×16 MAE | ≥ 1 | | 静止画に近い |

空ファイルは fail。既知良クリップ `/tmp/aoi-amano-cafe-0.mp4` は pass。両方見ないと gate は劇場。

### 1. 生成してよいこと / 禁ずること

**してよい:** gad で 1 clip。H3 を止めて VRAM を空ける。pass なら R2 upload + RECORDLOG INSERT。終わったら H3 を戻す。

**禁ずる:**

- murakumo generation token / Stripe クレジット購入
- 黒・未測定・tiny clip の公開
- IP シリーズの自動公開
- H3 を止めたまま終わる
- D1 不通を「もう埋まっている」と書く
- 2 体まとめて生成
- cash score を dogfood clip で上げる

公開 URL は `https://shinshi.club/video/actress/<slug>` と `https://shinshi.club/b/<sha256>`。

### 2. この skill が起きるとき

launchd `com.gftd.shinshi-catalog-video` は **pass ならモデルを呼ばない**。呼ばれるのは:

- quality unanswered
- quality fail のうち black 以外
- yellow（暗い / ほぼ静止）

呼ばれたら 1 体の証跡を確認し、壊れた clip を公開しない。直すなら gad 側の 1 手（VRAM / 参照画像 / 再生成 1 回）だけ。run を 1 本残す。共有 checkout は触らない。

### 3. 記録する

`90-docs/business/revenue-agent-loop/runs/` に新しい run（0029 の過去結果は書き換えない）。
funnel は外部入金が増えたときだけ動かす。catalog 埋めは dogfood。

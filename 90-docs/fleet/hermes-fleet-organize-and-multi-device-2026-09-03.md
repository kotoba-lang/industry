# Hermes fleet 整理 + 複数端末共有設計 (2026-09-03)

## 実施した整理

1. **UNPINNED 15 job を pin** (`z-ai/glm-5.3-flash` / `openrouter`):
   aiueos-handoff / aiueos-maint / amu-{bench,falsify,maint,rank} /
   giemon-sim-{bench,falsify,maint,rank} / jvm-dep-migrator / kotoba-cloud-maint /
   kotoba-maint / kotobalang-maint / kotobalang-org-maint。
   unpinned のままだと model drift 時に `drift_skip:silent` で cron が黙って止まる。
2. **stagger 整理** (同時起動の衝突回避):
   giemon-sim-bench `4-59/15` / falsify `9-59/15` / rank `14-59/15` /
   giemon-sim-maint `23,53`。amu 系は元から 0/2/7 分オフセットで維持。

## 現在の fleet 構成 (62 profiles)

| グループ | 件数 | cron |
|---|---|---|
| amu co-scientist loop | 5 | 4 (amu はコア, cron なし) |
| giemon sim loop | 5 | 4 (同上) |
| maintainer bots | 6 | 全部 cron 済 |
| kotobase.net roles | 7 | stab のみ + eng (cloud-itonami 側 360m) |
| itonami roles | 8 | maint のみ + cloud-itonami 側 cron 5 本 |
| murakumo roles | 6 | なし (chat/workforce 用) |
| kotobase-eng/mktg/qa/sales/support 相当の murakumo 版 | — | なし |
| media bots (dougaka/animeka/mangaka/gameka/adsk/adska) | 6 | なし (手動起動) |
| mail/inbox support | 6 | なし (default profile の mail_poll cron 15m が一括ポーリング) |
| other (codinator/compiler/hyakka/otent/syntax/research/review/writing/graphpr/bridgetest×2/aiueos-handoff/jvm-dep-migrator) | 14 | 一部 |

**cron のない 39 profiles の扱い**: 削除はしない。役割は chat/on-call 用として
有効 (cloud-itonami Bots UI にも import 済)。棚卸しは「その profile が直近 30 日に
session を持ったか」(`sqlite3 ~/.hermes/profiles/<p>/state.db "select count(*) from sessions"`)
で判断し、0 のものを archive 候補にする。bridgetestclone/fresh は名前からして
テスト用で第一候補。

## 複数端末で bot profile を共有するには

### 前提の理解

- 1 profile = `~/.hermes/profiles/<name>/` 以下の **自己完結ディレクトリ**
  (config.yaml / SOUL.md / cron/ / skills/ / state.db / .env)。
  「共有」= このディレクトリをどう同期するかの問題。
- 同期して良いもの / 悪いもの:
  - **同期する**: config.yaml, SOUL.md, skills/, cron/jobs.json, cron/scripts/
  - **同期しない**: `.env` (API key は端末ごとに発行), `state.db*` / `sessions/`
    (実行状態は端末ローカル), `logs/`, `cache/`, `models_dev_cache.json` (再生成可),
    `auth.json` (OAuth は端末ローカル), `cron/executions.db`, `cron/output/`

### 方式 A: 1 台を gateway ホストに集約 (推奨)

bot が cron で自律動作するなら、**実行主体は 1 台に置き、他端末はクライアント**にする。

- 常時起動の mac (現行機) が gateway + cron multiplex を担う (現状この形)。
- 他端末からは:
  1. **メッセージング経由** — Telegram/Discord/Slack を gateway に繋ぎ、端末を問わず
     同じ bot と会話。最も簡単で堅い。
  2. **`hermes dashboard`** — gateway ホストで起動すれば Web UI から全 profile 操作。
     OAuth gate 越えは Tailscale / SSH port forward で loopback を露出させる。
  3. **remote gateway login** — desktop app は per-profile remote-gateway login
     に対応。他端末の Hermes desktop から自家 gateway にログインする形。
- cron の二重実行だけは絶対に避ける: 同一 profile ディレクトリを 2 台で同時実行すると
  jobs.json の実行が競合し、git 同系の merge 地獄になる。

### 方式 B: git で profile 定義を同期 (宣言的 shares)

実行は各端末の担当 profile だけ、という分業なら git 管理が効く。

```
hermes-fleet/            # private repo
  profiles/
    <name>/
      config.yaml        # OK
      SOUL.md            # OK
      cron/jobs.json     # OK
      cron/scripts/      # OK
      skills/            # OK
  .gitignore:  .env  state.db*  sessions/  logs/  cache/  auth.json
              cron/executions.db  cron/output/  *_cache.json  plans/
```

運用: 変更は repo で commit → 各端末 `git pull` して自分の担当 profile だけ
活性化。`.env` は端末ごとに手動配置 (1Password/Keychain から)。
**crontab 的な jobs.json 同期は「同時に動かさない」ことが前提** — profile 単位で
担当端末を決め、ゲートウェイ multiplex の tick 対象を端末ごとに分ける。

### 方式 C: Syncthing / iCloud で実ディレクトリ同期 — 非推奨

state.db (SQLite) と jobs.json が常時書き換わるため、双方向同期だと破損と
コンフリクトが頻発する。A か B で。

### 判断基準

- bot は 1 箇所で動かすべき (cron の単一実行主体)。端末を増やすのは
  **実行の分散**ではなく**アクセスの分散** (Telegram/Dashboard/Desktop) で解決する。
- 実行を本当に分散したい (例: 別拠点の常時起動機で負荷分散) 場合は方式 B で
  profile を端末ごとに割り当てる。現行 62 profiles / load 36 前後なら
  この mac 1 台で足りている。

# shinshi-cast-bots — club-shinshi の actor ごとの itonami bots

club-shinshi のオリジナルキャラクター（actor）を、それぞれ自分の名義で動く
bot にする。cast bot は 12 original series の看板キャラ 1 人ずつ（roster は
毎回 D1 から導出 — ハードコードしない）、producer bot は公式工場
（char-factory）から新しい actor を迎え入れてカタログを育てる。

ADR: `90-docs/adr/2608291800-shinshi-cast-producer-bots.edn`。
workforce 側の役割定義（`:club-shinshi/cast-member` / `:club-shinshi/producer`）
は `orgs/network-awai/loop-yakuwari`。会話（1:1 チャット）は appview の
companion 面が既に持っており、ここが持つのは定期の情報発信・生成・カタログ成長。

## できること

| script | 何をするか |
|---|---|
| `roster.cljs` | cast roster を D1 から導出して表示（測定面） |
| `register.cljs` | 各 cast + producer に aozora.app アカウント（did:key + CACAO、seed は Keychain custody） |
| `post.cljs` | 各 cast が in-character 投稿を作文（murakumo-main、落ちたら profile 由来の決定論 fallback）→ shinshi.club に書いて **読み返して検証** → aozora.app にも試行 |
| `produce.cljs` | producer bot: char-factory の決定論列から未登録キャラを N 人 mint → actress/profile/intro を D1 に着地 → 行数で検証 |
| `generate.cljs` | `image <slug> [--apply]` fleet 画像 API で portrait 生成・scene 登録 / `video <slug> [--publish]` gad ComfyUI (Wan2.2) で i2v → 品質 gate → publish-once 委譲 |

## 実行

```bash
R=$PWD K=$PWD/orgs/kotoba-lang
CP="$R/scripts/shinshi-cast-bots:$K/org-chainagnostic-cacao/src:$K/authority/src:$K/org-ietf-ed25519/src:$K/org-ietf-cbor/src"

nbb --classpath "$R/scripts/shinshi-cast-bots" scripts/shinshi-cast-bots/roster.cljs
nbb --classpath "$CP" scripts/shinshi-cast-bots/register.cljs           # 全員
nbb --classpath "$CP" scripts/shinshi-cast-bots/post.cljs [--slug s] [--dry-run] [--skip-aozora] [--skip-llm]
nbb --classpath "$R/scripts/shinshi-cast-bots" scripts/shinshi-cast-bots/produce.cljs [--count N] [--dry-run]
nbb --classpath "$R/scripts/shinshi-cast-bots" scripts/shinshi-cast-bots/generate.cljs image <slug> --apply
```

D1 は appview（`orgs/network-awai/club-shinshi-app/appview/ai-gftd-wasm-shinshi-sh1n5h1x`）
を cwd に `wrangler d1 execute --remote` で読む/書く。`GFTD_ROOT` /
`SHINSHI_APPVIEW_DIR` で場所を上書きできる。

## 現在の状態（正直に）

- **shinshi.club への投稿・produce は今日動く**（vertex_repo_record / actress
  への D1 書き込み + 読み返し検証）。
- **aozora.app は全面 BLOCKED**（kotobase Biscuit-required cutover、
  ADR-2608291500）。register/post は seed を鋳造した上で試行し、BLOCKED banner
  に生のエラーを載せて exit 0。aozora PDS が Biscuit に移行した時点で、ここは
  無変更のまま成功に転じる。
- **画像 API は mk1 token（`MURAKUMO_API_KEY`）が要る**。無ければ UPSTREAM
  受領で止まり、投稿は既存 scene 画像を embed する。動画は
  `generation.murakumo.cloud` を使わない（402/billing — skill
  shinshi-catalog-video）。gad の ComfyUI 直で、カタログ動画は従来どおり
  catalog-video loop / shinshi-video-actor が回す。

## cron（hermes）

`shinshi_cast_tick.py` は hermes `--script` shim（判断を持たない）。

```bash
cp scripts/shinshi-cast-bots/shinshi_cast_tick.py ~/.hermes/scripts/
H=~/.hermes/hermes-agent/venv/bin/hermes
$H cron create "20 10 * * *" --name shinshi-cast-post --script shinshi_cast_tick.py --no-agent --deliver local
$H cron create "40 11 * * 1" --name shinshi-producer --script shinshi_producer_tick.py --no-agent --deliver local
```

hermes cron に載ることで、hermes-bots-aozora の register/pulse がこの 2 job
にも aozora アカウントを与える（roster は jobs.json から導出される、が設計）。

## 罠（実測）

- `tools/char-factory.cljs` / `char-posts.cljs` は 2026-08-29 まで **nbb で
  parse 不能**だった（bb→nbb 変換が残した `')` が require を閉じさせず
  ファイル全体を飲む + `Integer/parseInt`/`format`/`spit` の JVM 残骸）。
  club-shinshi-app `2de95b0` で修理済み。pin がそれより手前なら produce は
  REFUSED を返す。
- roster 導出で slug サフィックスだけ見ると IP キャラを拾う
  （`tifa-final-fantasy` が `%-fantasy` に一致）。profile の series 実測で
  弾いている。tiebreak を slug 昇順だけにすると 12 枠中 11 が同一キャラに
  潰れる（akari-hoshino-\*）。first-name の greedy 分散で回避。
- aozora の `createdAt` は**秒単位 ISO** でないと server 側が落ちる
  （yukkuri aozora.cljc の実測）。ここでは全書き込みが whole-second。

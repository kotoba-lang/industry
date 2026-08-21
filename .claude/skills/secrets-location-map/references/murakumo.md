# Murakumo — 各面のトークンと署名鍵

> **同名で別物が多い面。** どの Worker のどの gate かを必ず確認すること。

## api.murakumo.cloud mk1 capability-token 署名鍵 (ADR-2608011400、2026-08-01)

- **`MURAKUMO_API_TOKEN_SECRET`（kagi vault、compartment `network-awai`）** —
  `api.murakumo.cloud`（Worker `local-murakumo`）が `/v1/messages` `/v1/embeddings`
  で検証する **mk1 capability token の署名鍵**。同じ値が Worker secret
  `MURAKUMO_TOKEN_SECRET` に入っている（wrangler で投入済み、redeploy を跨いで永続）。
  取得: `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get MURAKUMO_API_TOKEN_SECRET`。
- ⚠ **2026-08-21 実測: この item も kagi vault に存在しない**（`no such item`）。
  本ファイルは既に 2 箇所（`LOCAL_MURAKUMO_SERVICE_TOKEN` / `MURAKUMO_GENERATION_TOKEN_SECRET`
  系）で同じ形の欠落を記録しており、これで **3 例目**。
  この日の作業では **mk1 トークンを発行できず**、`POST https://api.murakumo.cloud/v1/messages`
  は 401（`authentication_error`）のまま測定不能として記録した。
  **gate 1 本のために Worker の署名鍵をローテートしない** —— 発行済みトークンが全部無効になる。
  復旧はオーナー操作（現行値を documented 名で kagi に入れる、または再発行して両側を揃える）。
- **新規発行**（2026-08-01）。この Worker には従来 mk1 の署名鍵が存在せず、
  共有の静的 `ANTHROPIC_PROXY_TOKEN` / `_2` しか無かった（本マップの
  「Murakumo chat gate secondary secret」節が『api.murakumo.cloud は別の secret』と
  注記しているとおり）。mk1 受理を足すにあたって新設したので、**rotation ではなく追加**
  —— 既存の共有トークン経路は一切変わらない。
- **鍵の mint**（CLI / MCP のどちらも同じ実装 `murakumo.apikey`）:
  ```
  cd orgs/kotoba-lang/murakumo
  MURAKUMO_TOKEN_SECRET=$(kagi get MURAKUMO_API_TOKEN_SECRET) \
    nbb --classpath src scripts/run-task.cljs token issue --sub <who> --scope chat --ttl 604800
  ```
  MCP: `claude mcp add murakumo -- nbb --classpath "src:../org-anthropic-mcp/src" scripts/mcp-server.cljs`
  → tool `murakumo.issue_api_key`（同じ env が要る）。
- **境界**: scope は `chat|image|all` のみ、TTL は 90 日上限。**失効リストは無い**
  （ステートレス検証なので期限が失効そのもの）—— 長命鍵を作らず再発行する。
- live 検証済み（2026-08-01、CLI 発行・MCP 発行のどちらも本番 `/v1/messages` で 200、
  scope 不足は 401）。

## api.murakumo.cloud ノード面 service token (ADR-2608031000、2026-08-03)

- ⚠ **2026-08-15 実測: この item は kagi に存在しない。**
  `KAGI_HOME=$HOME/.kagi … kagi get LOCAL_MURAKUMO_SERVICE_TOKEN` は
  **`no such item: LOCAL_MURAKUMO_SERVICE_TOKEN`** を返す（この索引冒頭が警告して
  いる「記述は実在の証明ではない」の 5 例目）。旧記述は復旧時の参照として下に残す。
  - **stdout は空、メッセージは stderr に出る。** `$(kagi get …)` で捕まえると
    空文字が入るだけなので、**「取れた」と読める**。実際 2026-08-15 にそれで
    空 Bearer を送り、401 の原因を token 側でなく gate 側だと誤読しかけた。
    取得は必ず `2>&1 1>/dev/null` でエラー本文を読んでから使う。
  - **読み戻せないので再発行しかない。ただし単独で rotate してはいけない** ——
    Worker 側の env `MURAKUMO_SERVICE_TOKEN` は `operator-authorized?`（`/infer/models/:id`
    `/infer/plans/:id` の PUT）**と** `write-authorized!`（`/infer/runs` `/infer/spend`
    `/v1/slow/*`）が**共有**している（`worker.cljs` の `operator-authorized?` と
    `write-authorized!` が同じ `gobj/get env "MURAKUMO_SERVICE_TOKEN"` を読む）。
    rotate すると稼働中のノード caller が全部 401 になる。再発行するなら
    Worker secret 更新とノード側の配布を同時にやる。
  - （旧記述、2026-08-03 時点）**`LOCAL_MURAKUMO_SERVICE_TOKEN`（kagi vault、
    compartment `network-awai`、`KAGI_HOME=$HOME/.kagi`）** — Worker `local-murakumo`
    （= `api.murakumo.cloud`）の **write gate** `MURAKUMO_SERVICE_TOKEN` と同値。
    `/infer/runs` `/infer/spend` `/infer/queue*` と低速ティア ノード面
    `/v1/slow/work*` `/v1/slow/workers/heartbeat` の Bearer。
  - **2026-08-03 に新規発行（rotation ではない）。** 発行前この Worker には
    `MURAKUMO_SERVICE_TOKEN` が未設定で、`write-gate/service-authorized?` は
    **未設定を「全部許可」に倒さない**（fail-closed）ため、該当ルートは常に 401 を
    返していた＝既存 caller はいない。
  - ⚠ **`murakumo.cloud`（Worker `murakumo-cloud`、cloud-murakumo）の
    `MURAKUMO_SERVICE_TOKEN` とは別物。** 同名だが別 Worker・別値で、下の
    「murakumo x402 ingest service token」節のものは `/x402/ingest` 用。
    どちらかを rotate しても他方には効かない。
  - ⚠ **mk1 の署名鍵（`MURAKUMO_API_TOKEN_SECRET`、このファイル冒頭の節）とも別物。** あちらは
    顧客面 `/v1/messages` `/v1/embeddings` `/v1/slow/messages` の capability token 検証、
    こちらはノード/フリート側の write gate。

## Murakumo generation caller gate (ADR-2607161750、2026-07-16 / 2026-07-29 再発行)

- ⚠ **2026-07-29 実測: 下記 2 item は `~/.kagi`（live vault）にも
  `orgs/kotoba-lang/kagi/.kagi`（旧 vault）にも存在しなかった** —
  `MURAKUMO_GENERATION_TOKEN_SECRET` / `MURAKUMO_CHAT_TOKEN_SECRET_2` とも
  `no such item`。ADR-2607161750 / ADR-2607171800 addendum 2 の「kagi 保管を正とする」
  という記述は**実態と乖離していた**（保管されなかったか、後に失われた）。
  オーナー承認（2026-07-29「kagi は再発行して ok」）のもとで再発行した。詳細は
  ADR-2607299960。
- **再発行後の現在地（すべて `KAGI_HOME=$HOME/.kagi` で引く）**:
  - `MURAKUMO_GENERATION_TOKEN_SECRET`（compartment `gftdcojp`）— 新規生成。
    **Worker `murakumo-generation-proxy` の `MURAKUMO_TOKEN_SECRET_2`（optional な
    第2検証スロット、未設定だった）へ投入**したので、**primary は無傷** =
    `net-babiniku` / `network-isekai` の既存 caller は壊れていない（rotation ではない）。
  - `MURAKUMO_DOUGAKA_GENERATION_TOKEN`（compartment `gftdcojp`）— 上記 secret で
    mint した subject=`dougaka` / scope=`generation` / ttl 90 日の実 token。
    dougaka pipeline が `MURAKUMO_GENERATION_TOKEN` として読む。
  - **`murakumo-generation-proxy` は 2026-07-15 版が動いていて `_2` を読まなかった**
    ため、main から rebuild して再 deploy 済み（version `9a183830`）。
- **どのホストを叩くか**: 実 API は **`generation.murakumo.cloud/api/v1/generation`**。
  同じ token は `murakumo.cloud` では 401（別 worker・別 secret）。job status と
  artifact も同じホストで引く（API が返す URL は `murakumo.cloud` を指すので
  ホストを差し替える必要がある）。
- 旧記述（参考、上記のとおり item は実在しなかった）:
  **`MURAKUMO_GENERATION_TOKEN_SECRET`（kagi vault、compartment `gftdcojp`）** —
  `generation.murakumo.cloud`（Worker `murakumo-generation-proxy`）の caller gate
  `MURAKUMO_TOKEN_SECRET` と、その caller（`net-babiniku` / `network-isekai` 両
  Pages の `MURAKUMO_CALLER_SECRET`）が共有する HMAC signing secret の正本。
  2026-07-16 に rotation 済み（旧値は babiniku の provision script が生成後即破棄
  していてどこにも保管されていなかった — この轍を踏まないため kagi 保管を正とする）。
  新しい caller を追加するときは rotation せず `bin/kagi get
  MURAKUMO_GENERATION_TOKEN_SECRET` の値を該当 Pages/Worker secret に設定する。
  generation-scope の長命 token を mint するには `cloud-murakumo` で
  `MURAKUMO_TOKEN_SECRET=$(kagi get …) clojure -M:token issue <sub> generation <ttl>`。
  ⚠ `murakumo.cloud` site Worker（chat/inference gate）と `api.murakumo.cloud`
  （local-murakumo、`MURAKUMO_PROXY_TOKEN` 系）は**別の secret** — この item では
  ローテーションも検証もできない。

## Murakumo chat gate secondary secret (ADR-2607171800 addendum 2、2026-07-16)

- ⚠ **2026-08-03 実測: `MURAKUMO_CHAT_TOKEN_SECRET_2` は live vault
  （`KAGI_HOME=$HOME/.kagi`）に存在しない** — `no such item`。下記の記述は実態と乖離して
  いる（2026-07-29 に `MURAKUMO_GENERATION_TOKEN_SECRET` / この item で起きた乖離のうち、
  generation 側だけが再発行され、chat 側は残ったまま）。**この item を前提にしたスクリプトは
  動かない。** `murakumo.cloud/api/v1/chat/completions` は 401 のまま。
  - **今日動く chat 経路は `api.murakumo.cloud/v1/chat/completions`**（OpenAI 互換、実測 200）。
    token はこのファイル冒頭「api.murakumo.cloud mk1 capability-token 署名鍵」の
    `MURAKUMO_API_TOKEN_SECRET`（compartment `network-awai`、実在確認済み）から mint する:
    `cd orgs/kotoba-lang/murakumo && MURAKUMO_TOKEN_SECRET=$(kagi get MURAKUMO_API_TOKEN_SECRET)
     nbb --classpath src scripts/run-task.cljs token issue --sub <who> --scope chat --ttl 604800`
  - 実害: ADR-2608031500 の storyboard 生成でこれを踏んだ。`murakumo.cloud` gate を
    復旧したい場合は secret の再発行が要る（オーナー承認が要る操作）。
  - 以下は復旧時の参照用に残す。**現状は上記のとおり存在しない。**


- **`MURAKUMO_CHAT_TOKEN_SECRET_2`（kagi vault、compartment `gftdcojp`）** —
  `murakumo.cloud` site Worker（chat/inference gate）の**第2検証 secret**
  `MURAKUMO_TOKEN_SECRET_2` の正本。primary の `MURAKUMO_TOKEN_SECRET` は
  write-only Wrangler secret でどこにも保管が無く再取得不能（上記注記のとおり）
  だったため、gate 側を「どちらかの secret で verify が通れば受理」に拡張し
  （cloud-murakumo GitHub main `5bba489`）、agent が mint できる第2系統として
  この kagi item を追加した。既存 primary 署名 token は無効化されない（rotation
  ではなく追加）。chat-scope token の mint:
  `cd orgs/network-awai/cloud-murakumo && MURAKUMO_TOKEN_SECRET=$(kagi get
  MURAKUMO_CHAT_TOKEN_SECRET_2) clojure -M:token issue <sub> chat <ttl>`。
  Worker secret `MURAKUMO_TOKEN_SECRET_2` は wrangler で投入済み（worker
  `murakumo-cloud`、redeploy を跨いで永続）。
  ✅ **2026-07-16 更新: production 反映済み**。当初は共有 checkout が GitHub main と
  乖離した de-facto production ライン（local 109 commits 先行 / main 5 遅れ）で、
  この gate 拡張コードがそこに無かったため実運用は primary のみ受理していた。
  オーナー指示でその 109 commits を main に着地・一本化し（cloud-murakumo main
  `a1423b2`、west pin も前進 `3bbdc32`）、統一 main から rebuild した dist を
  murakumo.cloud にデプロイ（worker version `a6d00f52`）。以後、実運用の chat gate は
  `MURAKUMO_CHAT_TOKEN_SECRET_2` で mint した token を受理する（live 200 確認済み・
  upstream gemma4-26b 応答）。**注意**: cloud-murakumo は複数の worktree/deploy 経路が
  あり（共有 checkout・`cloud-murakumo-production-release` worktree・他セッション）、
  いずれも今は unified main 上にあるが、古い dist からの再デプロイが混ざると一時的に
  401 に戻りうる — その場合は unified main から rebuild して再デプロイする。

## Murakumo playtest critic token (ADR-2607162100、2026-07-16)

- **`MURAKUMO_CRITIC_TOKEN`（kagi vault、compartment `gftdcojp`）** —
  `api.murakumo.cloud/v1/messages`（local-murakumo）の vision critic 用トークン。
  local-murakumo Worker の **secondary** 受理スロット `ANTHROPIC_PROXY_TOKEN_2`
  に設定済み（primary の `ANTHROPIC_PROXY_TOKEN`＝1Password `gftd.murakumo/
  ANTHROPIC_PROXY_TOKEN` はローテーションせず据え置き。primary は op interactive
  auth timeout で非対話取得不可、こちらは kagi から取得可能なのが要点）。
  playtest co-scientist の standing runner（`~/.gftd/run-playtest-coscientist.cljs`
  ＋ LaunchAgent `com.gftd.playtest-coscientist`）が `MURAKUMO_CLAUDE_TOKEN` として
  これを読む。新しい `/v1/messages` consumer も同じ secondary スロットで rotation
  なしにオンボードできる。

## murakumo x402 ingest service token (ADR-2608010000、2026-08-01)

- **`MURAKUMO_SERVICE_TOKEN`（kagi vault、compartment `gftdcojp`、
  `KAGI_HOME=$HOME/.kagi`）** — `murakumo.cloud` の `POST /x402/ingest`（フリートが
  検証済み USDC-transfer view を push する経路）の Bearer。Worker `murakumo-cloud`
  の同名 secret と同値。取得:
  `KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi get MURAKUMO_SERVICE_TOKEN`。
  - **2026-08-01 に新規発行した（ローテーションではない）。** 発行前は kagi にも
    Worker secret list にも存在せず、`/x402/ingest` は設計どおり 503 を返していた。
    消費者ゼロを `wrangler secret list` で確認した上で作成しているので、既存の
    caller を壊していない。
  - ⚠ **`cloud-murakumo` の `post-to-ledger!` も同名の env を読む**（`/infer/runs`
    への bearer）。未設定時は単にヘッダを付けない実装なので今回の追加で挙動は
    変わらないが、**将来この値を rotate するときは ingest と ledger POST の両方に
    効く**ことを忘れないこと。
  - **launchd 経路は kagi ではなくファイル**: `~/.gftd/murakumo-service-token`
    （mode 600）。**launchd 下では kagi が Keychain unlock prompt を出せずに
    timeout する**ため（fleet-ci が先に踏んだ壁、ADR-2607178000 §3 と同じ答え）。
    LaunchAgent plist は world-readable なので plist 本体には絶対に書かない。
    常駐: `com.gftd.murakumo-x402-ingest`（60秒間隔）。
  - 消費側: `orgs/network-awai/cloud-murakumo/scripts/x402-ingest.cljs`
    （env → `MURAKUMO_SERVICE_TOKEN_FILE` の順で解決。argv には載せない）。

- **`murakumo-base-rpc-url`（kagi、compartment `personal`）— まだ存在しない。**
  keyed Base RPC エンドポイント（`https://base-mainnet.g.alchemy.com/v2/<key>` 等）を
  置く先として `scripts/provision-rpc-key.cljs` が読む item 名。**アカウント登録と
  鍵発行はオーナー作業**（安全床①）。設定すると Worker secret `MURAKUMO_BASE_RPC`
  として最優先の RPC になる。未設定でも公開エンドポイントへフォールバックする。

---
name: svelte-cljs-wave
description: Svelte を cljs + reagent + re-frame + hiccup + jp-go-dds に移す波を 1 本進める。1 反復 = 1 波（4 repo 並列）+ west pin 前進 + 再測定。ローカル Claude loop（com.gftd.svelte-cljs-wave）が毎周これを呼ぶが、手で `/svelte-cljs-wave` と打ってもよい。「svelte 移行」「cljs に移す」「svelte を消す」「migration wave」で発火。
---

# svelte-cljs-wave — Svelte を cljs に移す波を 1 本

正本は **ADR-2608260900**（オーナー指示 2026-08-26「svelte, react は全て cljs,
reframe などに refactor」「jp-go-dds をデフォルトの デザインシステムに」）。

**1 反復 = 1 波。** 4 repo を並列で移し、pin を進め、測り直して終わる。
欲張らない —— 並列度を上げても同時に build できるのは 1 本（下記）。

## 0. 前提を測る（推測しない）

```bash
cd "$COM_JUNKAWASAKI_ROOT"
git fetch origin -q && git merge --ff-only origin/main
nbb --classpath ".:scripts/nbb_compat" scripts/svelte-cljs-wave-tick.cljs --limit 4
```

tick が **exit 2** なら測れていない。**その周は何もしない。**
候補 0 本なら終わり（プール枯渇か、全部 in-flight）。

tick は各候補の `:repo` `:org` `:name` `:svelte-dir` を出す。**それをそのまま使う** ——
パスを自分で組み立てない。

## 1. 4 本の subagent に投げる

**fresh agent（`subagent_type` に `fork` 以外）** で、**model は `sonnet`**。
fork は会話コンテキストを継承して指示範囲を超えるので実行系に使わない
（CLAUDE.md「fork は調査専用」）。

1 agent = 1 repo。プロンプトは self-contained に書く（fresh agent は何も継承しない）。
下の「agent プロンプトに必ず入れるもの」を全部入れる。

`isolation: "worktree"` は**付けない** —— それは superproject の worktree を作るが、
`orgs/` の子リポは superproject に tracked されていないので中身が無い。隔離は
agent 自身に子リポの中で `git worktree add` させる。

## 2. 波が終わったら pin を進める（agent にはやらせない）

agent は `manifest/west.yml` を触らない。**中央でまとめて 1 commit にする。**

```bash
# merge した repo ごとに: <west-entry-name>\t<40-hex sha>\t<org/repo>
# ⚠ west entry 名は repo 名と違うことがある（例 gftdcojp/omise は `gftdcojp-omise`)。
#   path から引く:
node -e '
const fs=require("fs");let n=null;
for(const l of fs.readFileSync("manifest/west.yml","utf8").split("\n")){
  let m;
  if(m=l.match(/^\s*- name:\s*(\S+)/)) n=m[1];
  else if(m=l.match(/^\s*path:\s*(\S+)/)) if(process.argv.slice(1).includes(m[1])) console.log(m[1],n);
}' orgs/<org>/<name> ...

PINS=pins.tsv DRY=1 nbb --classpath ".:scripts/nbb_compat" scripts/west-pin-put-batch.cljs
PINS=pins.tsv     nbb --classpath ".:scripts/nbb_compat" scripts/west-pin-put-batch.cljs
```

SHA は **GitHub API から採る**（`gh api repos/<org>/<repo>/commits/main --jq .sha`）。
agent の報告をそのまま pin にしない。

## 3. checkout を新しい pin に合わせる

```bash
git fetch origin -q && git merge --ff-only origin/main
printf '%s\n' <name1> <name2> ... | xargs west update --fetch smart
```

⚠ **`xargs` は必須。** zsh は単語分割しないので `west update $NAMES` は 1 個の
project 名になり、`printf ... | west update` は**引数ゼロ = 4,200 project 全部**を歩く。
⚠ **west.yml が新しいことを先に確かめる。** 古い west.yml に対して update すると
checkout が**古い pin に戻る**（実測 2026-08-26 に 1 度やった）。
⚠ **非対話 shell では `west` が PATH に無い**（実測 2026-09-07、第 8 波）。`xargs west`
は `xargs: west: No such file or directory`（exit 127）で**何も更新せずに終わる**。
実体は `~/.local/bin/west`（pipx）。`W=$HOME/.local/bin/west` にしてから `xargs $W update`。
exit 127 は「pin に合わせた」と同じ静けさで返るので、update の後は必ず
`git -C orgs/<org>/<repo> rev-parse HEAD` で新しい pin に居ることを見る。

## 4. 測り直して baseline を締める

```bash
nbb --classpath ".:scripts/nbb_compat" scripts/verify-frontend-stack-retirement.cljs
nbb --classpath ".:scripts/nbb_compat" scripts/verify-frontend-stack-retirement.cljs --write-baseline
```

**baseline の締め直しは省かない。** 締めないと、移行済み repo が `.svelte` を
取り戻しても検出できない（entry が 1 のままだと 0→1 は「増えた」にならない）。
commit して main に載せる。

**載せ方（実測 2026-09-07、第 8 波）。** superproject 本体は閲覧専用なので、commit は
sparse な root worktree から出す:

```bash
nbb scripts/root-worktree.cljs create svelte-wave-baseline-<date> --profile minimal --include manifest --path /tmp/root-svelte-wave-baseline
# --write-baseline は cwd（FLEET_ROOT）の orgs/ を走査して cwd の manifest/ に書く。
# worktree には orgs/ が無いので、本体で書いて worktree へ copy し、本体側は checkout で戻す
cp manifest/frontend-stack-baseline.edn /tmp/root-svelte-wave-baseline/manifest/
git checkout -- manifest/frontend-stack-baseline.edn
```

⚠ **push / `gh api …/merges` を含む Bash 呼び出しは、その中で先に `git merge --ff-only`
しても通らない。** PreToolUse hook `west-pin-verify-guard` は**コマンド文字列**に push /
merges を見つけた時点で、worktree に**今ある** west.yml を main と比べる。worktree を切って
から main の pin が 1 つでも動いていれば（第 8 波では `amu` が動いた）、この branch が
west.yml を触っていなくても「pin 退行」として**呼び出し全体が拒否され、ff も commit も
走らない**。**ff を別の Bash 呼び出しで先に済ませ、west.yml が main と一致してから**
commit + push + merges を呼ぶ。`WEST_PIN_VERIFY_SKIP=1` は使わない（退行ではないが、
skip は本物の退行も通す）。

## agent プロンプトに必ず入れるもの（全部、実測で必要と分かったもの）

| 入れるもの | なぜ（実測） |
|---|---|
| **remote 名は org 名**（`origin` ではない） | west checkout の慣習。`git push origin` は通らない |
| **detached HEAD である** | west は SHA で pin するので branch 名が無い。`<org>/main` を明示させる |
| **`reagent`/`re-frame` は `:cljs` alias に置く** | PreToolUse hook `jvm-new-surface-guard`（ADR-2608201300）が top-level の新規 JVM 依存を**拒否**する。第 1 波の 6 agent 全員が踏んだ |
| **`package.json` に `react` と `react-dom` を入れる** | **reagent は npm の `react` / `react-dom` を解決する。** shadow-cljs だけ宣言すると app build が `The required namespace "react-dom" is not available, it was required by "reagent/dom.cljs"` で落ちる。第 2 波で `app-har` がこれで落ち、agent は**正直に失敗として報告した**（merge しなかった）。`devDependencies` に `^18.2.0` で足りる |
| **`package.json` に `"type": "module"` を書かない** | shadow-cljs の `:node-test` 出力は CommonJS（`__dirname`）。ESM 指定で `ReferenceError` になる。3 agent が踏んだ |
| **build の sentinel / log を worktree の外に置かない** | 第 2 波で 3 agent が揃って `/private/tmp/claude-501/build-app.exit` という**共有パス**を使った。1 つの exit code を別の agent が自分の結果として読みうる。scratch は worktree の下か session 固有パスに置く |
| **`:asset-path` は相対** | これらのページは path prefix の下に出る。絶対だと mount 先で壊れる |
| **build は前景の retry loop で回す。background 監視に入らせない。かつ Bash 呼び出しに `timeout: 600000` を渡させる** | 下記。第 1・2 波で計 6 agent、第 7 波でさらに 1 agent がこれで停止した。timeout を落とすと harness が**勝手に** background へ移す |
| **backend の `.ts` を書き換えない** | `src/app.ts` / `src/engine.ts` は Cloudflare Worker の本番ロジック。第 1 波で 2 agent が正しく拒否した。**svelte/ ディレクトリだけ**が対象 |
| **README / operator-quickstart / `kotodama.jsonld` の `staticDir` も直す** | 消した svelte build を説明したまま残すと、文書が能動的に嘘になる |
| **`wrangler.jsonc` / `wrangler.toml` が消したパスを指していたら直す**（下記の 1 通りに揃える） | 第 6 波で 3 agent が同じ問題に**3 通り**の答えを出した。scope を「frontend だけ」と書いた私の穴 |
| **build が通らなければ merge しない** | 壊れた移行は未移行より悪い |
| **rebase 禁止・force-push 禁止** | CLAUDE.md |
| **`manifest/west.yml` を触らない** | pin は中央で 1 commit にまとめる |
| **`wrangler.jsonc` のコメントを Bash heredoc で書かない（Write / Edit ツールで書く）** | PreToolUse hook `wrangler-deploy-main-sync-guard` は**コマンド文字列**を見る。「deploy / dev は走らせていない」と説明する UNVERIFIED コメントに `wrangler deploy` の字面が入るので、heredoc 経由だと**実行していない deploy として拒否される**。第 8 波（2026-09-07）で 1 agent が踏み、Write ツールに切り替えて通った |
| **`+server.ts` を別 dir へ `git mv` してから `git rm -r svelte/`** | 第 8 波の 4 repo は全部 `svelte/src/routes/xrpc/[...path]/+server.ts` を持っていた（上記「バックエンドである」節）。4 agent とも `src/xrpc-proxy.ts` に marker 付きで保存し、`src/app.ts` には触っていない（`gh api compare` で確認） |

## ⚠ `svelte/src/routes/**/+server.ts` は**バックエンド**である（実測 230 repo）

**SvelteKit の `+server.ts` は frontend ではない。HTTP エンドポイントの実装。**
route ツリーの中に在るので、`svelte/` を丸ごと消すと**本番のハンドラごと消える。**

実測 2026-08-26、`cloud-itonami/app-warehouse` の agent が
`svelte/src/routes/xrpc/[...path]/+server.ts` を見つけた —— その repo の README が
**「実際にデプロイされるのはこのファイル」**と書いていた XRPC dispatcher で、
wrangler の `main` が指していた SvelteKit build の実体がこれだった。agent は
`src/xrpc-dispatcher.ts` へ**移して**から消した。同じ波の `app-scheduler` と
`app-wvme` も同じ形に当たった。

**これは例外ではない。`orgs/` 全体で `svelte/` 配下の `+server.ts` は 230 件在る。**

**規則:**

1. 消す前に `find <svelte-dir> -name '+server.ts'` を必ず引く。
2. 在ったら**移す**（`<appview>/src/<意味のある名前>.ts`）。中身は byte 同一のまま、
   provenance を書いたヘッダコメントだけ足す。
3. **動かせるとは限らない。** SvelteKit 専用の import を持っていて、SvelteKit build が
   消えた後は**そのままでは動かない**。ヘッダにそう書く —— 復活させるかは
   未決の product 判断であって、移行の agent が決めることではない。
4. `+page.server.ts` / `hooks.server.ts` / `svelte/src/routes/**/*.ts` のうち
   `+page.svelte` でないものも同じ扱い。**`.svelte` でなければ frontend とは限らない。**
5. **ヘッダの 1 行目を必ずこの文字列にする** —— 置き場所は agent ごとに割れてよいが、
   **後から全部を見つけられること**だけは揃える:

   ```
   // SVELTEKIT-BACKEND-PRESERVED: moved out of svelte/ during the cljs migration; not wired.
   ```

   実測 2026-08-26、7 件が**5 通り**の場所に置かれた。どれも妥当な判断で、
   route 構造を保つ形は復活させやすい。**場所を 1 つに強制するより、
   `grep -r SVELTEKIT-BACKEND-PRESERVED orgs/` で全件挙がる方が価値がある。**

   ⚠ **この marker 規則より前に landed した 7 件には marker が入っていない。**
   つまり **`grep` の結果は 2026-08-26 のこの規則以降の分しか答えない。**
   grep が 0 件でも「保存された backend は無い」ではない —— 下の 7 件は別に在る:

   ```
   cloud-itonami/app-scheduler/appview/scheduler-mcp-component/src/xrpc-agentgateway-proxy.ts
   cloud-itonami/app-warehouse/src/xrpc-dispatcher.ts
   cloud-itonami/arbitrage/worker/src/xrpc-proxy.ts
   cloud-itonami/car-sim/appview/etzhayyim-wasm-car-sim-c4r51m00/src/xrpc-proxy.ts
   cloud-itonami/collector/appview/etzhayyim-wasm-collector-c0ll3ct1/src/xrpc-mcp-router-proxy.ts
   cloud-itonami/compintel/appview/compintel-cpti0001/salvage/svelte/src/routes/xrpc/[...path]/+server.ts
   cloud-itonami/contentengine/appview/contentengine-cten0001/backend-frozen/routes/xrpc/[...path]/+server.ts
   ```

   **marker を後から 7 repo に注入する PR は出していない** —— 7 件の PR を
   体裁のために起こすより、ここに名前を書いて grep の被覆を明示する方が安い。
   ただし**「grep が全件を答える」と誤読させないことが条件**で、そのために
   この注意書きが在る。1 件でも本文を触る用事ができたら、そのついでに
   marker を足すこと。

既に移行を終えた 26 repo は全部確認済みで、**pre-migration tree に `+server.ts` を
持っていたものは 0 件**（失ったものは無い）。危険は**これから**の、より大きい
scaffold の側に在る。

## `svelte/` を消す前に、Svelte でないものが入っていないか見る

**`svelte/static/` に、Svelte とは無関係の実物が置かれていることがある。**
実測 2026-08-26、`cloud-itonami/cad` の agent が `svelte/static/` に
**動作する prebuilt WASM の CAD viewer（約 266KB、`kami_app_cad_bg.wasm` 一式）**を
見つけた。上流 `kotoba-lang/kami-app-cad` の成果物で、Svelte の source ではない。
しかもその repo 自身の ADR-0001 が「このリポジトリで唯一動く viewer 面」と書いていた。

私の指示は「`svelte/` ディレクトリを丸ごと消せ」だったので、**字面どおりなら
壊していた。** agent は `appview/<name>/static/` へ**移してから**消した。正しい。

**判定は拡張子ではなく「それは何か」で行う。** 実測 2026-08-26、`svelte/` の中から
**3 種類**の別物が出た:

| 種類 | 実例 | どうした |
|---|---|---|
| ビルド成果物 | `cad` の prebuilt WASM CAD viewer（266KB、上流 `kami-app-cad` の成果物） | `static/` へ移した |
| **バックエンド** | `app-warehouse` / `app-scheduler` / `app-wvme` / `arbitrage` の `+server.ts` | `src/` へ移した（前節） |
| **ドメインのデータ模块** | `hc` の `svelte/src/lib/legal/contracts.ts`（17.9KB）—— **その repo の CLAUDE.md が「§Contracts の正本」と名指ししていた** | `legal/contracts.ts` へ移した |

⚠ **`.ts` だから消してよい、は成り立たない。** `hc` のそれは Svelte 構文を一切
含まないただの TS データ模块で、どちらの frontend からも import されていなかった
が、**repo の正本文書が権威として参照していた**。

**列挙して、1 件ずつ「これは Svelte のビルドに必要か」を問う。** 必要なのは
`*.svelte` / `main.ts` / `svelte.d.ts` / `app.html` / `vite.config.*` /
`svelte.config.*` / `tsconfig.json` / `tailwind.config.*` / `postcss.config.*` /
`package.json` / lockfile くらい。**それ以外は移す候補**で、消す前に
「これを参照している文書やコードは無いか」を repo 内で grep する。

⚠ **tick の `.svelte` 件数と、対象ツリーの件数は違うことがある。** `hc` は
tick が 2 件と出したが対象 appview には 1 件で、もう 1 件は**別の appview
（KYC）**のものだった。agent はそれに気づいて対象ツリーだけを扱った。
**プロンプトに書いた件数と実物が食い違ったら、実物を信じる。**

## `wrangler` の deploy 設定は 1 通りに揃える

`svelte/` を消すと、`wrangler.jsonc` の `main` と `assets.directory` が**存在しない
パス**を指したまま残ることがある（SvelteKit の `_worker.js` と `client/`）。
その状態の config は書かれたとおりには deploy できない。

**規則（迷わないために 1 通りに固定する）:**

1. `main` が消した SvelteKit の出力を指していたら **`main` を削る**。
2. `assets.directory` を **`./cljs/public`** に向ける。
3. `vars.APP_FRAMEWORK` が `sveltekit-*` なら `cljs-reagent-re-frame-jp-go-dds` に。
4. **`main` を `src/app.ts` に付け替えない** —— ただし *その worker が
   `env.ASSETS.fetch()` を呼んでいることを読んで確かめた場合だけ*は可。
   呼んでいない worker を `main` に据えると、asset の前に worker が立って
   **誰も静的ファイルを返さなくなる**。
5. **`wrangler deploy` も `wrangler dev` も実行しない。** commit には
   **UNVERIFIED** と書く。deploy の検証は別の仕事。
6. **`cljs/public` に置く document は 1 枚だけ**（＋ 静的ホストなら `404.html`）。
   SvelteKit は route ごとに document を出すので、**その形のまま cljs へ移すと
   移行が終わった瞬間に single-page 規則を破った app ができる**。画面の移動は
   state の変更であって location の変更ではない（ADR-2608080100）。
   view は表（data）で持ち、nav をそこから生成し、addressability は fragment
   で与える。着地後の確認:

   ```bash
   nbb scripts/verify-single-page-app.cljs --root . --findings
   ```

   ⚠ **exit 0 を合格条件にしない。この検出器は fleet 全体を見る**ので、
   波と無関係な repo の finding で恒常的に exit 1 を返す（実測 2026-09-07:
   `multi-document=11 no-document=21`、どれも今回の 4 repo と無関係）。
   **exit 0 を要求すると、どの波も永久に着地できない。** 見るのは
   **その波が触った repo が finding に出ていないこと**:

   ```bash
   grep -e <repo1> -e <repo2> ... /tmp/spa.log || echo "wave clean"
   ```

   波が新しい finding を*足していない*ことが条件であって、fleet が
   clean であることではない（後者は別の仕事）。


⚠ **なぜ 1 通りに固定するか。** 第 6 波で 3 agent が同じ状況に別々に答えた ——
`app-docs` は `main` を `src/app.ts` に付け替え（その worker は実際に
`env.ASSETS.fetch` を持っていたので正しい）、`okaimono` は `main` を削って
assets を向け直し（正しい・unverified と明記）、`app-society6` は
**触らずに flag だけした**（結果、消えたパスを指す config が main に残った）。
3 つとも判断としては筋が通っていて、**私が scope に書かなかったのが原因**。

対象 host が NXDOMAIN のことが多い（`app-society6` の 2 host とも）。
**live を壊す話ではないが、内部整合が壊れた config を残す理由にもならない。**

## build は前景の retry loop で回す（agent を止めないための最重要事項）

第 1 波・第 2 波で **6 agent すべてが同じ止まり方をした**: `resource-guard` の
`exit 2` を受けて background の retry loop を起こし、その完了通知を待って idle し、
**通知は来なかった**（background process は先に終了していた）。1 agent あたり
180k〜250k token を使って何も着地しなかった。

**background 監視・Monitor・sentinel ファイルを使わせない。** 次の 1 行が実測で
毎回通った形（私が第 2 波の 3 repo を全部これで着地させた）:

⚠ **agent には `timeout: 600000` を Bash 呼び出しに渡させる。これを落とすと、
自分から background にしなくても harness が background に**する**。** 下の loop は
`sleep 45` × 9 回で既定の 2 分を超えるので、timeout を指定しなければ harness が
勝手に background へ移し、agent は来ない通知を待って idle する —— **「自分から
background 監視に入るな」だけでは防げない**。実測 2026-09-07（第 7 波、app-tia）:
この 1 点で 1 agent が 212k token 使って停止し、resume が要った。作業自体は
無傷で、build も落ちていなかった（lock が兄弟 agent に握られていただけ）。

```bash
for i in $(seq 1 9); do
  node "$COM_JUNKAWASAKI_ROOT/scripts/resource-guard.mjs" run build -- npx shadow-cljs compile app > /tmp/x.log 2>&1
  rc=$?; [ $rc -ne 2 ] && break
  echo "attempt $i: lock held, waiting 45s"; sleep 45
done
echo "EXIT=$rc"; tail -5 /tmp/x.log
```

- **`exit 2` は「拒否された」であって失敗ではない。** lock が空くまで待って再試行。
- **迂回させない。** 迂回すると飽和した機械に 7 本目の build が乗る。
- **`$?` は最後のコマンドの終了値。** `... | tail` すると tail の 0 を読む。
  **先にファイルへ落として exit を採り、それから読む**（CLAUDE.md の 6 問）。
- **scratch ファイルは worktree の下か session 固有パスに置く。** 第 2 波で 3 agent が
  揃って `/private/tmp/claude-501/build-app.exit` を使い、互いの exit code を読みうる
  状態だった。
- **lock は断続的に競合する。** 「今空いている」は次の瞬間の保証にならない ——
  5 サンプル 20 秒すべて idle・shadow-cljs プロセス 0 を確認した直後に、無関係な
  build に取られて exit 2 になった実測がある（2026-08-26）。

着地は **feature branch → `gh api repos/<org>/<repo>/merges`**（サーバ側マージ）。
PR を開きっぱなしにしない。worktree と branch は agent 自身に片付けさせる。

## 検証は自分でやる（agent の報告を信じない）

```bash
gh api "repos/<org>/<repo>/git/trees/main?recursive=1" \
  --jq '[.tree[].path|select(test("\\.(svelte|jsx|tsx)$"))]|length'   # 0 であること
```

第 1 波では 6/6 が本当に 0 だったが、**確かめたから言える**のであって
報告がそう言ったからではない。

## 上流で着地済みの repo も候補から外れる（tick が自動で除外する、2026-09-07）

**tick が走査しているのは `orgs/` の作業ツリーであって、repo の `main` ではない。**
west は pin で checkout を止めるので、**上流で移行が着地しても、pin が手前にある
限り `.svelte` はディスクに残る。**

実測 2026-09-07、候補 4 件のうち **3 件が既に main で移行済み**だった
（`open-jpn-gov` / `outreach` / `port`。どれも 2026-09-05 の PR #1 で着地）。
投げた 3 agent はそれぞれ「もう終わっている」と気づくところから始めることになり、
1 体あたり 200k token を再監査に使った。**checkout・west pin・repo の main は
3 つの別物**という ADR-2608136800 の形そのもので、tick は 3 番目を一度も
見ていなかった。

`landed-on-github` が候補に渡す直前に GitHub の default branch を直接引き、
0 件なら `ALREADY-LANDED-SKIPPED` に名前付きで落とす。**同じ周に `robot` も
これで捕まった** —— 次の波で配られるはずだった 4 件目。

⚠ **trees API の `truncated: true` を 0 件に畳まない。** 巨大な tree では
`.tree` が途中までしか入らず、件数だけ見ると「着地済み」と同じ顔になる。
truncated なら `:unmeasured` を返す（測れなかった検査が、測って問題が
無かった検査と同じ値を返してはいけない）。

⚠ **これは「pin を進めれば済む」話でもある** —— 3 件とも west pin が
古いままだった。波の最後の pin 前進はこの再発を減らすが、pin は他の理由でも
遅れるので、tick 側が main を訊く必要は消えない。

## archived / 未登録の repo も候補から外れる（tick が自動で除外する、2026-09-07）

**tick は `ARCHIVED-SKIPPED` と `UNREGISTERED-SKIPPED` を名前付きで出す。**
どちらも「まだ着地していない」ではなく **「着地できない / させても意味がない」**。

- **archived** —— push が `This repository was archived so it is read-only.`
  で拒否される。実測 2026-09-07、`open-banking` に投げた agent が**完璧な移行を
  終えてから** push で弾かれた（11 分 / 194k token）。west.yml は最初から
  `groups: [archived]` と書いていて、tick が訊いていなかっただけ。
  eligible pool に同じものが 7 件在った。
- **未登録** —— west.yml にも fleet-db にも無い path。ほぼ**改名前の残骸**で、
  実測 2026-09-07 の `orgs/cloud-itonami/open-cofog` は GitHub 側が
  `org-un-cofog` に改名済みだった（`gh api repos/cloud-itonami/open-cofog` が
  改名後の名前を返す）。**残骸は誰も更新しないので `svelte/` が消えず、毎周
  候補に出続ける。** 同型が 10 件（`orgs/gftdcojp/app-6ir` 等、どれも
  cloud-itonami へ redirect）。

⚠ **agent が「push できない」と報告してきたら、それは失敗ではなく正しい停止。**
その worktree を消さない —— 着地先が無いだけで、中身は完成している。

⚠ **改名された repo に投げると、push は redirect 先に着地する。** つまり
**west entry 名は候補の path と違う**（`open-cofog` の成果は `org-un-cofog` の
main に載った）。pin を進めるときは *GitHub が返した full_name* から entry を
引き直す —— 候補の path から引くと entry が見つからない。

## custody 契約を持つ repo は候補から外れる（tick が自動で除外する）

実測 2026-08-26、投げた agent が **4 回正しく拒否した**。それらの repo は
`migration.edn` で「N ファイル / M バイトを出所から verbatim に持ってきた」と宣言し、
検査器がそれを **sha256 で pin して今日 PASS している**。`svelte/` を消すと
12/14 や 9/19 の pin が外れ、**直しようのない FAIL** になる。文書の陳腐化ではなく
**契約違反**で、移行するなら `migration.edn` と検査器を書き換える統治判断が要る。

⚠ **ファイル名で判定しない。** 私が最初 `docs/verify-custody.cljs` だけを見て
skill に書いたら、**3 つ取りこぼした**。実際の名前と場所は少なくとも 4 通り:

    docs/verify-custody.cljs             app-global, app-maps, app-roukisho, …
    docs/verify-docs-claims.cljs         app-sos
    docs/check-migration-identity.cljs   gol-d-roger
    scripts/verify-docs-claims.cljs      app-public-kafun-bokumetsu

**中身で判定する。** `docs/` か `scripts/` の `.cljs` が `migration.edn` か
`svelte/` に言及していれば custody 契約とみなす。tick はこの規則で除外し、
既知の陽性 9 件・陰性 9 件（実際に移行が通った repo）で **9/9・9/9** を確認済み。
`CUSTODY-SKIPPED` 行に**名前を出す**（黙って除くと移行が進んだように見える）。

**agent への指示でもファイル名を固定しない。** 3 agent が「その名前は無いが、
同じ目的の gate がこの名前で在る」と自分で気づいて止まった —— *指示の字面より
その場で測った証拠を優先した*のが正しい。指示にはその判断を許す言葉を入れる。

**これらを移行したくなったら、まず custody 契約をどうするかを人が決める。**
波に混ぜない。

## やらないこと

- **`svelte-design-system`（55 component）をこの波で流さない。** あれは
  jp-go-dds への上流拡張という別の判断（DADS は 20 component、BottomSheet /
  Carousel / DatePicker / Dialog / Drawer / Toast / Fab に対応が無い）。
- **Next.js app（7 本）をこの波で流さない。** `hrse` は Go バックエンド・Clerk
  認証・Atlas migration・BDD を含む。個別見積もりが要る。
- **`react` / `react-dom` を package.json から剥がさない。** reagent/re-frame は
  React を描画バックエンドに使う。退役するのは**著述面**であって依存ではない。

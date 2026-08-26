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

## 4. 測り直して baseline を締める

```bash
nbb --classpath ".:scripts/nbb_compat" scripts/verify-frontend-stack-retirement.cljs
nbb --classpath ".:scripts/nbb_compat" scripts/verify-frontend-stack-retirement.cljs --write-baseline
```

**baseline の締め直しは省かない。** 締めないと、移行済み repo が `.svelte` を
取り戻しても検出できない（entry が 1 のままだと 0→1 は「増えた」にならない）。
commit して main に載せる。

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
| **build は `resource-guard.mjs run build --` 経由、exit 2 は retry** | lock は二本目を**拒否**する。`exit 2` は失敗ではない。**迂回させない**（機械が飽和する） |
| **backend の `.ts` を書き換えない** | `src/app.ts` / `src/engine.ts` は Cloudflare Worker の本番ロジック。第 1 波で 2 agent が正しく拒否した。**svelte/ ディレクトリだけ**が対象 |
| **README / operator-quickstart / `kotodama.jsonld` の `staticDir` も直す** | 消した svelte build を説明したまま残すと、文書が能動的に嘘になる |
| **build が通らなければ merge しない** | 壊れた移行は未移行より悪い |
| **rebase 禁止・force-push 禁止** | CLAUDE.md |
| **`manifest/west.yml` を触らない** | pin は中央で 1 commit にまとめる |

着地は **feature branch → `gh api repos/<org>/<repo>/merges`**（サーバ側マージ）。
PR を開きっぱなしにしない。worktree と branch は agent 自身に片付けさせる。

## 検証は自分でやる（agent の報告を信じない）

```bash
gh api "repos/<org>/<repo>/git/trees/main?recursive=1" \
  --jq '[.tree[].path|select(test("\\.(svelte|jsx|tsx)$"))]|length'   # 0 であること
```

第 1 波では 6/6 が本当に 0 だったが、**確かめたから言える**のであって
報告がそう言ったからではない。

## custody 契約を持つ repo は候補から外れる（tick が自動で除外する）

実測 2026-08-26、`cloud-itonami/app-global` に投げた agent が**正しく拒否した**。
あの repo の `docs/verify-custody.cljs` は「保管ファイル 24 / 87,245 バイト /
出所 tree hash」を検査して**今日 PASS する**。`svelte/` を消すと再構成 hash が
記録と恒久的に食い違い、**直しようのない FAIL** になる。文書の陳腐化ではなく
**契約違反**で、移行するなら `migration.edn` と検査器を書き換える統治判断が要る。

同じ形が 6 件（`app-global` `app-maps` `app-roukisho` `app-saiban`
`app-shomeisyashin` `app-sre`）。tick が `docs/verify-custody.cljs` の有無で除外し、
`CUSTODY-SKIPPED` 行に**名前を出す**（黙って除くと移行が進んだように見える）。

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

Base directory for this skill: /Users/junkawasaki/github/com-junkawasaki/.claude/skills/fleet-refactor-wave

# fleet-refactor-wave — cloud-itonami fleet 向け refactor 波を 1 本

正本は **ADR-2608290100**（オーナー指示 2026-08-29「orgs/cloud-itonami/ 配下 100+
repo に対する常駐 refactor loop actor を立てる」）。構造は姉妹 skill
`svelte-cljs-wave` の模写——同じ罠を再学習しない。判定基準そのものはここで
再定義しない（Mission A: ADR-2608039000、Mission B: ADR-2608261100 +
`kotoba-clj-to-kotoba` skill、それぞれが正本）。

**1 反復 = 1 波。** 少数 repo を並列で処理し、pin を進め、測り直して終わる。
`orgs/cloud-itonami/` の repo は west 管理で、この skill の実行環境に checkout
されているとは限らない——tick は checked-out repo だけを見る（「無い」と「checked
out されていない」は別物、CLAUDE.md「無いと結論する前に検索する」節）。

## 0. 前提を測る（推測しない）

```bash
cd "$COM_JUNKAWASAKI_ROOT"
git fetch origin -q && git merge --ff-only origin/main
kbb --backend sci --classpath ".:scripts/nbb_compat:orgs/cloud-itonami/loop-fleet-refactor-wave/src" \
  scripts/fleet-refactor-wave-tick.cljk --limit 4
```

tick が **exit 2** なら測れていない。**その周は何もしない。** 候補 0 本なら
終わり（両 mission とも枯渇 or 全部 in-flight — Mission A は 2026-08-29 実測で
`orgs/cloud-itonami/` スコープにおいて候補 0 件だった。これは tick の欠陥では
なく、ADR-2608039000 の規則違反が今のところ無いという legitimate な測定結果。
0 本が続くこと自体を異常として扱わない）。

`--mission a` / `--mission b` で片方だけに絞れる（既定は `both`）。

> ⚠ **Mission B は 2026-08-30 以降 authority が禁じている。手で再開しない。**
> tick が `MISSION-B-BLOCKED` を出すのは、機械正本
> `kotoba-lang/kotoba-lang` の `lang/q9-migration.edn`（origin/main）が
> 2 つの独立した理由で禁じているから:
>
> 1. `:scope :decision-only-extraction-forbidden true` —— この tick の Mission B
>    が探すのは decision core（ADR-2608290100 Decision §3 が自分でそう書いている）で、
>    authority が名指しで禁じた形そのもの。`:legacy-decision-cores` は
>    `:status :historical-evidence-only` / `:expansion false`。
> 2. `:current-decision :authorized-waves #{:wave-0 :wave-1}` —— `orgs/cloud-itonami/`
>    は `:wave-4`（`:status :not-authorized`）。authorized な 2 tranche は
>    `orgs/kotoba-lang/*` のみで、cloud-itonami の言及は 0 件。
>
> 解除は tick を書き換えることではなく、**authority 側が変わること**。authority が
> 許可に転じれば gate は自動で通る（両方向を実測済み）。Mission B を再開したい場合の
> 正しい経路は、Q9 の `:whole-component-build-contract`（`kotoba check` /
> `amu check --jvm-free` の両建て + `:component-closure` 全 public surface）を
> 満たす移行単位に tick のヒューリスティクスを作り直すことで、この gate を外すことではない。

**したがって当面、この loop が実際に進められるのは Mission A だけ**であり、その
候補は 0 本が続いている（ADR-2608039000 の規則違反が今のところ無いという legitimate な
測定結果）。両方が 0 本の周は、何もしないのが正しい出力。

tick は各候補の `:repo` `:org` `:name` `:mission`（`:a`/`:b`）、Mission B なら
さらに `:file`（対象 `.clj`/`.cljc` の相対パス）`:lines` を出す。**それをそのまま
使う** — パスを自分で組み立てない。

## 1. 候補を N 本の fresh agent に投げる

**fresh agent（`subagent_type` に `fork` 以外）**。fork は会話コンテキストを
継承し指示範囲を超えるので実行系に使わない（CLAUDE.md「fork は調査専用」）。
1 agent = 1 候補（1 repo・1 mission・1 slice）。プロンプトは self-contained に
書く。

**`isolation: "worktree"` は付けない。** それは superproject の worktree しか
作らず、`orgs/` 配下の子リポの中身は含まれない（svelte-cljs-wave が実測で
学んだ罠、そのままここでも起きる）。隔離は agent 自身に子リポの中で
`git worktree add` させる:

```bash
# orgs/cloud-itonami/<repo> が checked out 済みの場合（west 管理の共有 checkout）
cd orgs/cloud-itonami/<repo>
git fetch origin -q && git merge --ff-only origin/main -q   # remote 名は実測（下記）
git worktree add -b agent/<branch-suffix> /tmp/<scratch>/<repo> origin/main
cd /tmp/<scratch>/<repo>
# ... 編集 / test / commit ...
git push origin agent/<branch-suffix>
gh api repos/cloud-itonami/<repo>/merges -f base=main -f head=agent/<branch-suffix> \
  -f commit_message="..."
git worktree remove /tmp/<scratch>/<repo>

# orgs/cloud-itonami/<repo> が checked out されていない場合（west 未取得）
git clone git@github.com:cloud-itonami/<repo>.git /tmp/<scratch>/<repo>
cd /tmp/<scratch>/<repo> && git checkout -b agent/<branch-suffix>
# ... 以下同じ ...
```

**Mission A の branch 名は `agent/d1-premise-fix`、Mission B は
`agent/kotoba-migration`** — tick の in-flight 判定がこの名前で remote branch を
見る（`scripts/fleet-refactor-wave-tick.cljk` の `in-flight` 呼び出し）。違う
名前を使うと次周の tick が同じ repo を二重に選び得る。

### agent プロンプトに必ず入れるもの

| 入れるもの | なぜ |
|---|---|
| **remote 名は実測する**（`git -C <path> remote` — org 名のことが多いが決め打ち
  しない。svelte-cljs-wave の実測知見: `origin` の checkout もある） |
| **同期済み base の commit SHA を書いて渡す**（CLAUDE.md「委譲する agent の
  プロンプトに、同期済み base の commit SHA を書いて渡す」——fresh agent は自分の
  base が古いかを自力で検証できるようにする） |
| **rebase 禁止・force-push 禁止**（CLAUDE.md） |
| **`manifest/west.yml` を触らない**（pin は中央で 1 commit にまとめる） |
| **build/test が通らなければ merge しない**（壊れた refactor は未着手より悪い） |
| **Mission A: ADR-2608039000 を全文読ませ、判定基準（delete-and-rebuild test）を
  自分の言葉で再確認させてから着手させる**（tick の候補判定は heuristic であって
  最終判断ではない——agent が実コードを読んで確認する） |
| **Mission B: skill `kotoba-clj-to-kotoba` を Skill ツールで呼ばせてから着手
  させる**（手順・4 分類・`amu compile` の起動形はそこが正本） |
| **1 agent = 1 slice。2 mission を混ぜない**（タスクの指示どおり——同じ repo が
  両方に該当しても、1 回の投入では片方だけ） |

## 2. 波が終わったら pin を進める（agent にはやらせない）

agent は `manifest/west.yml` を触らない。中央でまとめて 1 commit にする
（`svelte-cljs-wave` と同じ手順）。

```bash
# merge した repo ごとに 1 件: kbb --backend sci scripts/west-pin-put.cljk <entry-name> HEAD
# entry 名は west entry 名（通常 repo 名と同じだが、west.yml で確認する）
kbb --backend sci --classpath ".:scripts/nbb_compat" scripts/west-pin-put.cljk <name> HEAD
# 複数まとめる場合は scripts/west-pin-put-batch.cljk（1 commit に束ねる）
```

SHA は **GitHub API から採る**（`gh api repos/cloud-itonami/<repo>/commits/main
--jq .sha`）。agent の報告をそのまま pin にしない。

## 3. checkout を新しい pin に合わせる

```bash
git fetch origin -q && git merge --ff-only origin/main
printf '%s\n' <name1> <name2> ... | xargs west update --fetch smart
```

⚠ **`xargs` は必須。** zsh は単語分割しないので `west update $NAMES` は 1 個の
project 名になり、`printf ... | west update` は**引数ゼロ = 全 project 更新**に
なる（CLAUDE.md 罠 2）。

## 4. 測り直す

```bash
kbb --backend sci --classpath ".:scripts/nbb_compat:orgs/cloud-itonami/loop-fleet-refactor-wave/src" \
  scripts/fleet-refactor-wave-tick.cljk --limit 4
```

pool（対象 repo 数）は変わらないが、mission-a-raw / mission-b-raw が減っている
はずなら着地を確認できる。**tick の再実行を省かない** — 省くと、着地した repo が
何らかの理由で候補として再浮上しても検出できない。

## Mission A 固有の注意（ADR-2608039000 を読んでから判断する）

- **判定は「消して再構築できるか」の 1 本だけ**（ADR-2608039000 §2）。D1 の
  存在だけで候補にしない——decentralization self-claim + D1-as-arbiter の
  両方が揃って初めて候補（`loop-fleet-refactor-wave.mission-a/mission-a-candidate?`
  が heuristic として両方を要求する）。
- **fix は「D1 を消す」ではなく「ref/CAS 役を inga に、block 役を
  content-addressed object store に移す」**（`(storage/compose {:blocks <store>
  :refs inga})`、`kotoba-lang/kotobase-storage` の `storage/compose`。着手前に
  必ず現在の API 形状を実際のソースで確認する——ADR の記述を鵜呑みにしない）。
- **D1 が「補助的なだけ」という自己申告を信じない。** ADR-2608039000 自身が
  「D1 は補助的に使っています」という自己申告は検査不能だと明記している。
  agent は実コードで「今この D1 を消したら何が壊れるか」を実際に確認する。

## Mission B 固有の注意（skill `kotoba-clj-to-kotoba` を読んでから判断する）

- **移行単位は 1 vertical slice**（state → effect → event → governor → UI →
  checkpoint）であって、repo 全体でも、判断核だけの抽出でもない
  （ADR-2608261100）。tick が拾う候補ファイルは「小さく自己完結した decision
  core」の heuristic であって、そのファイル全体を機械的に `.kotoba` にすれば
  終わりという意味ではない——agent は 4 分類（portable pure / portable
  effectful app / host mechanism / operational script）を実際に行う。
- **`kotoba -M compile` の起動形を間違えると 4 通りの違う顔で落ちる**
  （`-M` 無し・target 名・`--output` でなく `-o`・相対パス）。skill
  `kotoba-clj-to-kotoba` の手順 5 をそのまま使う。
- **custody-gate を tick が既に除外している**が、agent 側でも
  `docs/`・`scripts/` の `.cljs` に `migration.edn` / `svelte/` への言及が
  無いか、対象 repo で改めて確認する（tick の除外漏れが svelte-cljs-wave で
  3 回起きた実測がある——同じ罠がここでも起きうる）。
- **oracle パターンが既にある repo は模倣する。** 実測 2026-08-29:
  `cloud-itonami/cloud-itonami-app` は既に 15 本の `_core.kotoba` +
  `kotoba-oracle` bridge namespace を持つ。同じ repo の別ファイルを移行する
  ときは、その repo の既存パターン（例: `fleet_core.kotoba` /
  `fleet_core.cljc` の oracle 呼び出し形）を新規発明せず真似る。

## 検証は自分でやる（agent の報告を信じない）

```bash
gh api "repos/cloud-itonami/<repo>/git/trees/main?recursive=1" \
  --jq '[.tree[].path|select(test("\\.kotoba$"))]'   # Mission B: 新しい .kotoba が実在するか
gh api "repos/cloud-itonami/<repo>/compare/main...agent/d1-premise-fix" \
  --jq '.files[].filename'                            # Mission A: 何を変えたか
```

## やらないこと

- **1 回の agent 投入で 2 mission を混ぜない。** 両方に該当する repo は
  ADR の body に注記するが、diff は片方だけに留める。
- **Mission A candidate が無いことを埋め合わせるために基準を緩めない。** 0 件は
  正直に報告する（タスクの指示・ADR-2608290100 の実測記録どおり）。

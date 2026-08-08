# Cleanup / Merge Conflict Workflow

Machine-readable workflow: [`manifest/cleanup-workflow.edn`](cleanup-workflow.edn).

Codex skill: `$git-cleanup-conflict`

Use this when cleaning unmerged branches/PRs, reconciling worktrees and stashes, or resolving
merge conflicts in this superproject and its `orgs/` child repos.

## Guardrails

- Do not discard owner WIP.
- Do not force-push shared branches.
- Do not rebase. If a stale branch cannot fast-forward, create a clean branch/worktree from current `origin/main` and replay only the needed commits or patch.
- Prefer a new branch from current `origin/main` when an old branch is stale.
- Treat `manifest/west.yml` as generated output. Resolve source files and regenerate it; do not hand-edit conflict markers.
- Keep failed `stash pop` entries. Git keeps the stash on failed pop; inspect it before applying manually.
- **GitHub push alone is not registration.** Repos under `orgs/` that consumers resolve via `:local/root` (or that are intentional fleet members) must also appear in west (`repos.edn` `:extra-projects` + `gen-west-manifest.cljs --entry`). See skill `new-project-scaffold`.

## west update first — inventory より前に必ず回す

```bash
git fetch origin && git merge --ff-only origin/main   # superproject を先に同期
west update --fetch smart                             # 子リポを pin に合わせる（dirty は skip、exit 1）
```

理由は2つあり、どちらも実測（2026-08-04、オーナー指示でこの節を追加）に基づく。

**1. 判定の前提が古いと結論が全部ずれる。** cleanup の中心的判定である content-containment は
「branch の追加行が現 `origin/main` に存在するか」を見る。子リポの `origin/main` が fetch されて
いなければこの判定は成立しない。実測: 6 リポの ahead を **pin 基準**で見ると abi +11 / bitcoin-node
+52 / kotoba +19 / kotobase +3 / kagitaba +1 / shell +5 に見えたが、各リポを fetch して
**origin/main 基準**で測り直すと bitcoin-node は 0 ahead（pin が遅れていただけ）、残りも大半が
既に着地済みで、本当に未着地だったのは kotoba の 2 ファイルだけだった。

> ⚠️ **訂正（2026-08-08）: この鮮度は `west update` では得られない。**
> `west update --fetch smart` は **remote-tracking ref を一切更新しない**。`--fetch smart` は
> pin の SHA に到達するのに必要な分しか fetch せず、pin 自体が upstream より遅れているので、
> west が保証するのは「checkout が pin に一致すること」だけで「upstream が最新であること」
> ではない。対照実験（`kotoba-lang/css`）:
>
> | | `refs/remotes/kotoba-lang/main` |
> |---|---|
> | before | `6eda5ee` |
> | GitHub の `main` | `82aa184` |
> | `west update --fetch smart css` 直後 | `6eda5ee` ← **動かない** |
> | `git fetch kotoba-lang` 直後 | `82aa184` ← 正しい |
>
> したがって鮮度は下の手順 **(d) で別途取る**のが唯一の方法であり、(d) は補助ではなく
> 本体である。west update を先に回す価値は理由 **(2)**（skip 集合 = ローカルにしか無い
> 作業の母集団）にある。

**2. west update の skip 一覧そのものが cleanup の入力である。** west は dirty な project を
破壊せず skip して exit 1 を返すので、その skip 集合が「ローカルにしか無い作業を持つ repo」の
正確な母集団になる。実測（4,022 project、full history、約4時間）: **87 project が skip**、内訳は
untracked 衝突 83 / tracked のローカル変更 17（13 は両方）。うち **69 project・70 ファイル**は
incoming と byte-identical な掃き出しファイル（大半が `kotoba-lang/com-*` の
`schema/<name>.kotoba-schema`）で、`shasum` 一致を確認して削除し再 update すれば解消した。
残る **18 project** が本物のローカル作業だった。

手順:

1. superproject を `git fetch origin && git merge --ff-only origin/main`
2. `west update --fetch smart`
3. skip された project を untracked / localchg に分類。untracked は `git hash-object` と pin 側
   blob hash の**一致を確認したものだけ**削除して再 update。localchg は触らず温存
4. 個別リポを触る前に、**そのリポでも**明示的に fetch して `<remote>/<default>` を最新化して
   から判定する（上の訂正のとおり、**ここが鮮度を得る唯一の手段**）。
   **`git fetch origin` と書いてはならない** — 下記のとおり 72% の repo に `origin` は無い。

   ```bash
   REM=$(git -C "$R" remote | grep -qx origin && echo origin || git -C "$R" remote | head -1)
   git -C "$R" fetch "$REM" --quiet
   ```

**罠:**

- **remote は `origin` とは限らない。むしろ少数派。** west は remote を manifest の remote 名
  (`kotoba-lang` / `cloud-itonami` / …) で作る。実測 2026-08-08、`orgs/` 配下 273 repo の
  サンプルで **197 (72%) に `origin` が無い**。`origin/` 決め打ちは ref が解決せず、
  `merge-base --is-ancestor` が fatal になって **判定が静かに UNLANDED 側へ倒れる**。
  `scripts/cleanup.cljs` は実際にこれで 5 箇所誤答しており（`repo-slug` が nil に落ちて PR
  照会が飛ぶ / 全 branch が未着地に見える / 既に upstream にある untracked が最上位に来る）、
  `primary-remote` を port して修正した。観測した verdict の反転は**全て「偽の未着地 → 着地済み」**
  の向きだった。

- **`error: could not read IPC response` は fetch の失敗ではない。** `core.fsmonitor` の IPC
  である（`~/.gitconfig` で `true`、実測 983 個の `fsmonitor--daemon` が常駐）。素の
  `git status` でも出て、`-c core.fsmonitor=false` を付けると消える。west のログでは直後に
  `HEAD is now at …` が続くので「fetch が落ちている」と読み違えやすい（2026-08-08 に実際に
  誤診した）。west update の出力からこの行を根拠に fetch 失敗を結論しないこと。

- west checkout は fetch refspec が `refs/west/*` のため `origin/<branch>` の remote-tracking ref
  が無いことがある。branch の push 済み判定をローカル ref だけで行わず
  `gh api repos/<slug>/branches/<branch>` で確認する（実測: kotobase の
  `agent/persist-execution-identities` はローカルに ref が無く未 push に見えたが実際は push 済み）。
- 巨大リポは長時間かかる。実測 `gftdcojp/apps-gftdcojp` 単体で約1時間（pack 5.4 GiB 受信後に
  `git index-pack` が CPU 70% で delta 解決）。親の `git fetch` が 0% CPU に見えるので
  **ハングと誤認しない**こと。

## UNLANDED inventory — which child repos still hold work that hasn't landed

```bash
nbb scripts/cleanup.cljs --unlanded   # only repos with un-landed work
nbb scripts/cleanup.cljs              # full survey (also lists quiet repos)
nbb scripts/cleanup.cljs --subrepos   # superproject only (fast)
```

Per child repo, a **landing ladder** — left is more dangerous because git protects it less:

| Marker | Meaning | Hazard |
|---|---|---|
| `untracked=N` | not committed at all | On no branch, on no remote. One `git checkout` in the shared west checkout destroys it. |
| `dirty=N` | tracked, uncommitted | Survives branch switches only by accident. |
| `unpushed=B:N` | branch `B` is N ahead of `origin/B` (or `no-remote`) | Exists only on this machine. |
| `nopr=B` | pushed, unreachable from default branch, no open PR | Not on any review path; rots silently. |
| `nopr=?(N branches…)` | 予算切れで PR 照会を打切り | 報告する。黙って落とさない。 |
| `nopr=!(N branches…)` | gh 照会が失敗（再試行3回後） | **「PR が無い」ではなく「見られなかった」。** 混同すると偽陽性になる。 |

### PR 照会は repo あたり 1 往復（2026-08-06）

`gh pr list --repo <slug> --state open --json number,headRefName` は open PR を
**まとめて**返すので、branch ごとに `--head` で引く必要はない。取得した map と
手元の branch を突き合わせれば同じ答えが 1 往復で出る。

同一 fleet での実測:

| | 往復 | 解決した branch | 打切り |
|---|---|---|---|
| 旧（branch ごと） | 199/200 | 約 181 | **18** |
| 新（repo ごと） | **149/200** | **242** | **0** |

branch farm（webgpu ≈80、slides ≈90）が実質無料になり、20 branch の cap は不要に
なったので撤去した。

**gh の失敗を `nopr` にしない。** 旧実装は gh 失敗時に nil を返し、呼び手の `remove`
がそれを「PR が見つからなかった」と読んでいた——つまり**ネットワークの瞬断が黙って
偽の「push 済みだが PR 無し」を作っていた**。`net/http: TLS handshake timeout` は
実測で無負荷時 1/40、survey が数千の git を spawn している最中は 137/137 で起きた。
3 回・2s/4s バックオフで再試行し、それでも駄目なら `nopr=!` として**別に**数える。

### `unpushed` は実測する（ローカル ref から推測しない）

west checkout の fetch refspec は `refs/west/*` なので `refs/remotes/origin/<branch>`
が無いことがあり、**push 済みの branch が「このマシンにしか無い」と誤報される**。
ladder の rank 3 は本来「消えたら戻らない」を意味するので、偽陽性は本物を埋もれさせる。

実測 2026-08-06: `:no-remote` は 57 repo / 96 branch あり、**28 が偽**だった。例:

```
旧: orgs/cloud-itonami/cloud-itonami-isco-0110  UNLANDED; unpushed=uiux/banner:no-remote
新: orgs/cloud-itonami/cloud-itonami-isco-0110  UNLANDED; nopr=uiux/banner
remote: b4141de64ae94ededac13cd87407ae914734daa9  refs/heads/uiux/banner
```

`git ls-remote` は **git protocol なので REST の rate limit を消費しない**（同一 repo・
同一の問い: `gh api .../branches/main` 2173ms・1 branch・課金、`git ls-remote` 1627ms・
全 ref・無課金）。`:no-remote` を持つ repo だけに当てるので fleet 全体で 29 往復。

`clojure.java.shell/sh` に timeout は無いので、退役した remote で認証プロンプトに
ぶつかると survey ごと止まる。`GIT_TERMINAL_PROMPT=0` と ssh の
`BatchMode=yes`/`ConnectTimeout=10` で塞ぐ（実測: 死んだ remote が 800ms で exit 128）。

集計は row ではなく atom に持つ——覆した repo はしばしば UNLANDED でなくなって
行ごと filter から落ち、row に持たせた集計値も一緒に消える（初回は実際に
recovered=0 と表示された）。

Incident reference (2026-07-25): `orgs/gftdcojp/cloud-itonami` held the entire Workspace
suite (Directory/Mail/Drive/backup/domain-proof/projection-outbox, ~4,000 lines with tests
and its own ADR) as **untracked files** in the shared west checkout, on a
`rescue/wip-20260718` branch 1381 commits behind `origin/main` — on no branch, on no
remote, not deployed. The pre-fix survey printed only `dirty=57`, indistinguishable from a
one-line edit elsewhere. Landed as `gftdcojp/cloud-itonami` PR #488.

Two gaps this closed:

1. `git status` alone under-reports danger — split untracked from dirty and rank them.
2. PRs must be queried **per child repo slug**. The old script ran `gh pr list` only
   against `com-junkawasaki/root`, so a child repo with pushed-but-un-PR'd branches
   looked clean.

**Landed ≠ deployed.** cloud-itonami's live Pages Function was serving a build that
predated the merged source; `git log` cannot see that. Probe the live surface after
landing.

### Landing it — `scripts/cleanup-land.cljs`

```bash
nbb scripts/cleanup-land.cljs                      # dry-run plan (default)
nbb scripts/cleanup-land.cljs --apply              # execute
nbb scripts/cleanup-land.cljs --apply --names a,b  # limit to named repos
nbb scripts/cleanup-land.cljs --apply --max 20     # cap; the rest is reported, not hidden
```

Never merge all UNLANDED work as one class. Split by *whether it can break `main`*:

| Class | What | Action |
|---|---|---|
| `:additive` | untracked files only | commit → PR → **merge** (no such path exists on the default branch, so nothing is rewritten) |
| `:review` | changes to tracked files | commit → PR, **never auto-merge** (a stale base silently rolls `main` back) |
| `:branches` | existing local branches | push if unpushed; PR if pushed with none; **never auto-merge** |

Write path is the GitHub git API (blob → tree with `base_tree` → commit → ref), not local
worktrees — same server-side single-commit shape `CLAUDE.md` mandates for `west.yml`.

Two silent traps: **renamed repos** return HTTP 307 to `--input` POSTs and `gh` does not
follow (reads work, writes fail — resolve slugs through `gh api repos/<slug> --jq
.full_name`; measured: `kotoba-git` → `bonsai`), and **`--jq` bare scalars are not JSON**
(parsing `6dc20b…` as JSON yields nil and every commit fails quietly).

`:branches` is opt-in (`--branches`, because it pushes) and capped at 20 live branches per
repo, reported not hidden (webgpu has 67 local branches, slides over 90).

Skipped and always reported: credential-looking paths, build junk, files over 2 MB,
git-annex/DataLad datasets. Executable bits preserved. Nothing is ever deleted — archive
to `.git/stash-archive-<date>/` first, then add.

**Deletions are never applied** — a ` D ` entry comes from a working tree that may be far
behind, and replaying it can delete work someone else added. **Re-runs must be idempotent**
since landing does not remove the local copy: compare local blob shas against the base tree
and drop what already matches.

### DO-NOT-MERGE PR は「駐車場」であって判定ではない

`:review` / `:branches` として開かれた `DO-NOT-MERGE` PR は、開いた時点で**誰も中身を
見ていない**。ラベルは事故を防ぐためのもので、判断の代わりではない。1 件ずつ
disposition を付けて閉じるまでが cleanup。

実測 2026-08-04: fleet に 98 件が滞留していた。同じラベルなのに中身は 3 つの全く違う
ものだった — 一括処理してはならない理由そのもの:

| クラスタ | 件数 | 中身 | disposition |
|---|---|---|---|
| `fix/khm-repo-name-typo` | 20 | テンプレ複製で他リポ名が残った `CONTRIBUTING.md`/`GOVERNANCE.md` の 2 行 typo。各リポが自分の名前に直すだけ | `:merge` |
| `wasm-compile-*` | 13 | `.kotoba` source + コンパイル済み `.wasm` + テスト + `deps.edn` 変更。実質的な機能追加 | `:needs-review` |
| `preserve uncommitted tracked changes` | 60 | 共有 checkout の未コミット編集の退避。base の鮮度次第で revert 装置になる | 測定して決める |

3 つ目が危険な理由は PR body 自身が記録している: **2026-07-26/27 に同型 17 件が自動
マージされ、`main` から約 913 行が削除された。**

disposition は 5 つ。ラベルではなく**測定**で決める:

| disposition | 条件 |
|---|---|
| `:merge` | diff が自明に正しく、base が現行で、`main` 側が同じ file を動かしていない |
| `:needs-review` | 実質的な追加がある。放置せず「何を確認すれば決まるか」を PR に comment で残す |
| `:close-superseded` | 追加行が全て default branch に既に存在する（content containment）。archive → close |
| `:close-stale-revert-risk` | base が古く、PR が触る file を `main` が更新済み。merge すると新しい内容を巻き戻す。archive → close（内容は archive に残る） |
| `:close-repo-retired` | 対象 repo 自体が退役済み（下記 archived 例外に注意） |

**archived repo の PR は close すらできない**（実測 2026-08-04）。read-only なので
`closePullRequest` / `update-branch` / `merge` が全て 403。さらに GitHub は**1ヶ月以上前の
workflow run の再実行を拒否**するので、必須チェックが古い失敗のまま固まっていても branch
更新で CI を回し直す逃げ道が無い。

実測: open 108 件のうち **31 件が archived repo**（`gftdcojp/241001-lifescience-web` 29 +
`kotoba-lang/kotoba-v2025` 2）。必須チェックは 2026-02 の失敗のまま。owner が unarchive →
close → re-archive するしかないので**報告して終わりにするのが正しい**。inventory では live
repo と分けて数える — 混ぜると backlog が永久に減らないように見える。

判定は安い順に:

```bash
# 1) main が同じ file を動かしたか（非空なら stale = revert risk）
gh api repos/<repo>/compare/<pr.base.sha>...<default> --jq '[.files[].filename]'
#    ↑ と PR の files の積集合を取る
# 2) 空でも追加行が既に default branch にあるなら :close-superseded
# 3) 残ったものだけを人が読む
```

**積集合が空 ≠ merge して安全**（この節の初版が間違えた点、2026-08-04 修正）。overlap と
「PR 自身が削除している行」は**独立した軸**。overlap が空ということは main の該当 file が
base のままということなので、PR の `-N` 行は**いま main にある行をそのまま消す**。

実測: `io-multiformats#8` は overlap 0 だが `defn-` を 1 つ丸ごと削除、`lab#8` はパース処理を
削除、`bonsai#14` は doc を削除 — いずれも**未完成のリファクタの途中状態**だった。

したがって preserve 系の既定判定は:

| 削除行数 | disposition |
|---|---|
| 0 | `:merge`（何も書き換えない） |
| 1 行でも有 | `:needs-review`（他人の未完成の編集を main に適用することになる） |

### `close` は、ローカル WIP が残っている限り終端ではない

`:close-superseded` / `:close-stale-revert-risk` として PR を閉じても、**その PR の元になった
共有 checkout の未コミット変更は消えない**（cleanup-land が「ローカルの WIP は一切削除しない」
のは非交渉の安全床なので、これは正しい挙動）。したがって次に `cleanup-land --apply` を回すと、
**同じ内容の preservation PR が作り直される。**

実測 2026-08-08: `etzhayyim/com-etzhayyim-kawaraban#28` を `:close-stale-revert-risk` として
archive → close した約 1 時間後、同じ 54 ファイルが `:review` として再び plan に載った
（この時は `--names` から外して回避した）。

disposition が `:close-*` の repo は、次のどれかまでやって初めて片付く:

| | 対処 |
|---|---|
| (a) | その working tree の変更を owner が commit するか捨てる |
| (b) | 意図的に残すなら `.gitignore` / `.git/info/exclude` に入れて cleanup-land の視界から外す |
| (c) | 現 `main` から切り直した正しい PR を landed させ、working tree を `main` と一致させる |

どれもできないなら、**「close したが再生成される」ことを報告に明記する**。黙って閉じると、
次の周で復活したものを別の agent が新規 backlog として数え直す。

なお **PR の重複そのもの**（日付違いの同一内容）は tool 側のバグで、com-junkawasaki/root#1765 で
修正済み。修正後は既存の open な preservation PR があればその branch を再利用するので、
repo あたり常に 1 本になる。

**Never**: `DO-NOT-MERGE` を理由に中身を見ずに放置する（この節ができた理由）/ draft の
まま放置して「GitHub が merge を防ぐから安全」で終わらせる（防いでいるのは事故だけで、
判断は誰もしていない）/ archive せずに close する（`:retirement :archive` と同じ
非交渉ルール）/ 同じラベルのものを 1 クラスタとして一括処理する。

Three kinds of "no remote", needing different handling: has commits (create + push);
**no commits at all** (create empty repo, land via a parentless root commit through the API,
never touching the local checkout); **exists upstream but local lost `origin`** (reattach,
do not create — measured: `kotoba-lang/org-threejs` had 10 of 12 files already landed).

## West orphan inventory

`orgs/<org>/<repo>` can exist locally without being in west, or exist on GitHub without local/west. Mixing path-override leftovers with true orphans causes false registrations.

```bash
nbb scripts/west-orphan-audit.cljs
nbb scripts/west-orphan-audit.cljs --blocking
nbb scripts/west-orphan-audit.cljs --all
```

| Class | Action |
|---|---|
| `:local-root-broken` | **Blocking** — register the missing project or retarget the dep. |
| `:true-orphan-git` | Register or retire; report, never silent-delete. |
| `:path-override-leftover` | Do not re-register old path (new path is already in west). |
| `:worktree-scratch` | Session debris; remove only after unpushed-WIP check. |
| `:personal` | Out of west scope. |
| `:true-orphan-nongit` | Report; register only if it becomes a real repo. |

Incident reference (2026-07-12→17): `kotoba-lang/crm` pushed to GitHub; consumers
`cloud-itonami-isic-5820` / `-6201` / `-6202` use `{:local/root "../../kotoba-lang/crm"}`;
crm missing from west and often from the local tree → fresh checkout breaks.

**Repair / keep current (three planes):** see
[`manifest/west-triple-sync-workflow.md`](west-triple-sync-workflow.md) and
`nbb scripts/west-triple-sync.cljs` (ADR-2607173200).

## Standard Cleanup

1. Inventory each relevant repo (**west update を先に回してから** — 上の「west update first」節):

   ```bash
   git fetch origin && git merge --ff-only origin/main
   west update --fetch smart
   git worktree list --porcelain
   git branch --show-current
   git stash list
   git status --short --branch
   gh pr list --state open --json number,title,headRefName,baseRefName,url,mergeable,statusCheckRollup
   nbb scripts/west-orphan-audit.cljs
   ```

2. Classify what remains:

   - Unmerged branch with useful commits: replay onto current `origin/main`.
   - Stash only: inspect, then apply only if it will not overwrite dirty files.
   - Placeholder repo with no commits or missing remote: report as blocked, do not invent a PR.
   - Generated-file change: regenerate from source of truth before committing.
   - West orphan / `:local/root` broken edge: classify per table above; fix blocking edges before claiming cleanup done.

3. For stale branches, prefer a clean branch:

   ```bash
   git fetch origin main
   git switch -c cleanup-<date> origin/main
   git cherry-pick <needed-commit>
   ```

   If a cherry-pick turns into add/add conflicts, abort and apply the small diff manually.
   Do not switch to rebase to solve the conflict.

4. Commit cleanup work with:

   ```bash
   git commit -m "cleanup"   # Co-Authored-By trailer は実行中のハーネス既定の規約に従う
   ```

5. Create, inspect, and merge the PR:

   ```bash
   git push origin <branch>
   gh pr create --base main --head <branch> --title cleanup --body cleanup
   gh pr view <number> --json mergeable,statusCheckRollup,url
   ```

   If `gh pr merge` fails only because local `main` is checked out in another worktree, merge via GitHub API.

## Stash / Branch Retirement

Use this to drain an accumulated `git stash list` / local branch list without losing work.
Prevention lives in `CLAUDE.md` § 並行エージェント運用 (worktree-per-agent, no stash
accumulation); this section is the recovery path. Verified in practice 2026-07-02:
20 stashes + 8 branches drained, 2 genuinely unlanded items rescued.

1. **Snapshot by SHA first.** Concurrent sessions push/pop stashes, so indices shift.
   Record `git stash list --format='%H %gs'` once, and before every drop re-resolve the
   SHA to its current index.

2. **Classify each stash by content containment, not by patch-id or ancestry.**
   Exclude generated files (`manifest/west.yml`) from the check — their stashed content
   is disposable by definition. For the remaining files, test whether each added line of
   the stash diff exists in current `main`'s version of that file:

   - all lines present → landed; safe to retire.
   - only `;;` comment/wording lines missing → landed with rewording; safe to retire.
   - substantive lines missing (repo registrations, code) → unlanded; rescue.
   - same-file-set stashes in a numbered series ("round N") where the latest round is
     fully landed → earlier rounds are superseded intermediates; safe to retire.

3. **Archive everything before dropping.** Export each stash as a patch (plus its
   untracked-file list from `stash^3` when present) into
   `.git/stash-archive-<date>/` with an `index.txt` of `SHA | message`. Dropping is then
   fully reversible without relying on reflog/gc timing.

4. **Rescue unlanded content to a branch, not back into a stash.** Build the branch in a
   sparse worktree outside the superproject (full checkout is slow):

   ```bash
   git worktree add --no-checkout -b stash-rescue-<date> /tmp/root-stash-rescue origin/main
   cd /tmp/root-stash-rescue && git sparse-checkout set --no-cone <paths> && git checkout
   git apply -3 --include='<path>' <archive>/<sha>.patch   # 3-way, per rescued file
   git commit && git push origin stash-rescue-<date>        # push; merge is owner's call
   ```

5. **Retire branches with the same discipline.** Per branch: check
   `git merge-base --is-ancestor <branch> main` (full history is the default — ADR-2607211600 —
   so this resolves directly; no shallow deepening needed). Non-ancestors get the added-line containment check (step 2), and
   unrelated-history branches (no merge base) get a remote-preservation check
   (`gh api repos/<org>/<repo>/commits/<tip>` — if the tip exists in the successor repo,
   the branch is preserved remotely). Archive `git diff main...<branch>` + a commit log to
   `.git/stash-archive-<date>/branches/`, then `git branch -D`. Never touch `git-annex`
   (annex metadata) or branches another session is actively using.

## Conflict Resolution

Inspect first:

```bash
git diff --name-only --diff-filter=U
rg -n '<<<<<<<|=======|>>>>>>>' <paths>
git diff --stat -- <paths>
```

Resolve by file class:

- `manifest/west.yml`: resolve `manifest/repos.edn`, generator code, and child repo checkouts first; then run `nbb scripts/gen-west-manifest.cljs`.
- EDN files: keep both logically distinct additions and validate by running the relevant babashka/Clojure reader or generator.
- Markdown policy files: preserve current `main` policy and add only the missing procedure/reference text.
- Stash conflicts: do not drop the stash; inspect `git stash show --stat` and `git show 'stash@{0}' -- <paths>`.

### `--check` reports STALE almost always — do not "fix" it by regenerating

`nbb scripts/gen-west-manifest.cljs --check` compares `west.yml` against what a
**wholesale** regeneration would produce, and the generator pins from each child
repo's *local working HEAD*. In a shared checkout that is drifted by definition.

Measured 2026-07-29 on a clean `main`: of **3,299** locally-present west
projects, **287** had a local HEAD different from their pin. Sampling 25 of
those for direction:

| | |
|---|---|
| local **ahead** of pin — regeneration would advance | 3 |
| local **behind** pin — regeneration would **regress** | 5 |
| diverged | 3 |
| **pin not present in the local clone at all** | 14 |

Extrapolated, a wholesale regeneration would have rolled back on the order of
57 pins and mangled ~160 more whose pin the local clone cannot even see. That
is the `90852b86` incident (44 broken pins pushed to `main`) waiting to happen
again.

So: **STALE is the normal reading, not a defect.** The `:west-conflict`
resolution below lists "ensure child repos are checked out at the intended
pins" as a precondition, and nothing enforces it. Treat `--check` as
informational unless you have just run `--entry` for your own change, and
verify *that entry's* diff instead:

```bash
git diff manifest/west.yml     # must show only the entries you intended
```

After resolving:

```bash
rg -n '<<<<<<<|=======|>>>>>>>' <changed-files> || true
nbb scripts/gen-west-manifest.cljs --check   # informational — see above
```

Run any domain-specific verification touched by the change, for example:

```bash
nbb scripts/kotoba-boundary-audit.cljs
```

## Stash Pop Failures

If `git stash pop` says local changes would be overwritten, that is a safe stop. The stash entry is kept.

Do this next:

```bash
git diff --stat -- <paths>
git diff -- <paths>
git stash show --stat stash@{0}
git show 'stash@{0}' -- <paths>
```

Then choose one:

- Apply the stash change manually if it is independent.
- Commit or stash the current local work first.
- Leave the stash and report the conflict if applying it would regress a generated pin.

## Final Report

End every cleanup with:

```bash
gh pr list --state open --json number,title,headRefName,baseRefName,url
git worktree list --porcelain
git branch --show-current
git stash list
git status --short -- <relevant-paths>
nbb scripts/west-orphan-audit.cljs --blocking
```

Report merged PRs, closed/superseded PRs, deleted remote branches, preserved stashes,
untracked placeholder repos, and **west-orphan summary** (blocking count +
true-orphan-git count, with any intentional deferrals named).

## Session closing

`closing` is a trigger for Standard Cleanup plus an authoritative handoff:

1. Update the relevant ADR/gap ledger with landed-vs-evidence status and a concrete resume point.
2. Compare every evidence branch with the current remote default branch before opening or merging
   a PR. A branch that deletes newer default-branch work is superseded evidence, not merge input.
3. Create and merge a fresh-default-branch PR for the closing record and any safely ported delta.
   Comment and close conflicting predecessor PRs with a link to the record or successor.
4. After merge confirmation, remove only the closing session's worktrees and branches.
5. Finish with merged PR/default SHA, remaining gaps in priority order, exact validation results,
   preserved owner WIP, open PRs, worktrees, stashes, and west-orphan counts.

The closing report must be sufficient for a new agent with no conversation history to resume.

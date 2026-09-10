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

## inventory の前に fleet 全体の west update を回さない（2026-09-09 改訂）

```bash
git fetch origin && git merge --ff-only origin/main   # superproject を先に同期
nbb scripts/checkout-staleness.cljs                   # dirty / behind / untracked の母集団（全 fleet 94 秒、network なし）
# 判定する repo だけ、その repo で明示的に fetch する（鮮度はここでしか得られない）
```

この節は 2026-08-04 のオーナー指示で「必ず回せ」と定め、理由を 2 つ挙げていた。
**両方とも、この repo 自身の測定で崩れている。** 以下は両方の記録を残す — 何が
真だったかと、いつ何によって偽になったかが読めないと、同じ結論に戻るため。

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
> したがって鮮度は「判定する repo だけを明示的に fetch する」で取るのが唯一の方法であり、
> それは補助ではなく本体である。
>
> この訂正が書かれた時点では「west update を先に回す価値は理由 **(2)** にある」と結ばれて
> いた。その (2) も 2026-09-09 に倒れている（下）。**残った価値は『checkout を pin に揃える』
> ことだけで、それは cleanup の前提ではない。**

**2. west update の skip 一覧そのものが cleanup の入力である。**

> ⚠️ **訂正（2026-09-09）: skip 集合は母集団を取りこぼす。**
> west が skip するのは *更新がローカル変更と衝突するとき* だけで、衝突しなければ
> dirty な木の上でも checkout を進める。実測: 中断した run が到達した 1,073 project の
> うち dirty は 2 件（`cloud-itonami/akashi` の untracked `config/`、
> `cloud-itonami/business-manager` の `.cpcache/`）で、**どちらも skip されていない** ——
> akashi は `Previous HEAD position was 1c9ef16` → `HEAD is now at 16ecd85` と HEAD を
> 動かしている。
>
> **そして同じ答えは約 200 倍速くローカルで出る。** 同一の 1,073 project に対して:
>
> | 方法 | 時間 | 1 project あたり | 全 4,343 換算 |
> |---|---|---|---|
> | `west update --fetch smart` | 1,825 秒 | 1.70 s | **約 2.0 時間** |
> | `git status --porcelain` 12 並列 | 7.7 秒 | 0.0072 s | 約 32 秒 |
> | `scripts/checkout-staleness.cljs`（実際に使う道具） | — | — | **94 秒**（4,555 checkout、load 51.6） |
>
> 時間は network に消えている（到達した 545 のうち 352 = 65% が実際に fetch を要した）。
> fleet 全体をローカルで測る道具は既にある: `scripts/checkout-staleness.cljs` は
> docstring 自身が「**fetch はしない。** 4,415 checkout を fetch するのは論外だし、hook から
> 呼べなくなる」と書いている。`scripts/cleanup.cljs` も 2026-07-26 に同じ理由で 2 フェーズ化
> され、phase 1 はネットワーク往復ゼロで全 repo を走査する。**3 つのスクリプトが既にこれを
> 知っていて、この節だけが取り残されていた。**

**dirty な repo は実在する — 母集団の取り方が変わっただけである。**
実測（4,022 project、full history、約4時間）: **87 project が skip**、内訳は
untracked 衝突 83 / tracked のローカル変更 17（13 は両方）。うち **69 project・70 ファイル**は
incoming と byte-identical な掃き出しファイル（大半が `kotoba-lang/com-*` の
`schema/<name>.kotoba-schema`）で、`shasum` 一致を確認して削除し再 update すれば解消した。
残る **18 project** が本物のローカル作業だった。

手順:

1. superproject を `git fetch origin && git merge --ff-only origin/main`
2. `nbb scripts/checkout-staleness.cljs` で dirty / behind / untracked の母集団をローカルに取る
   （`dirty=N (Xt/Yu)` と tracked / untracked を分けて出す。全 fleet 94 秒、network round trip ゼロ）
3. untracked は `git hash-object` と pin 側 blob hash の**一致を確認したものだけ**削除。
   localchg は触らず温存。checkout を pin に揃えたいときだけ
   `west update --fetch smart <name> …` を**対象を名指しして**回す（引数なしの全体走査は
   初回 clone と pin が大量に動いた後だけ — CLAUDE.md）
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
git-annex/DataLad datasets, **rename residue (below)**, and **nested git repositories
(`:skip-nested-repo`, below)**. Executable bits preserved. Nothing
is ever deleted — archive to `.git/stash-archive-<date>/` first, then add.

### An untracked directory holding another git repo never expands

`plan-repo` turns `?? foo/` into individual files with
`git ls-files --others --exclude-standard -- foo/`. **`ls-files -o` stops at a nested repo
boundary and returns `foo/` unchanged**, so the expansion is a no-op; no regex matches a
bare directory, `statSync` succeeds, and the *directory path* lands in `:additive`.

Measured 2026-08-18:

| repo | untracked entry | what it actually was |
|---|---|---|
| `kotoba-lang/amu` | `.claude/worktrees/agent-a62da554fc36aeff3/` + 1 more | **live registered worktrees of other sessions** — 1,623 files, branches `agent/log-v1-aot-surface` / `agent/storage-v1-aot-surface` |
| `kotoba-lang/kotoba-lang` | `netsync/` | **duplicate clone of the west-registered repo `kotoba-lang/netsync`**, sitting at the pinned commit `c7ca033`, 0 unpushed |

Both were planned as `:additive → PR → merge`. Merging them would have committed another
session's worktrees into a repo, and absorbed an independent repo into its parent. **Both
satisfy `:additive` perfectly** — no path of that name exists on `main`. That is the same
false argument the residue gate exists to refute: *absent from `main`* is not *new work*.

`classify-file` now returns `:skip-nested-repo` for any candidate that is still a directory
after expansion. Proven both ways: with only the nested dirs, both repos report
着地対象なし; with one ordinary untracked file added, `amu` still plans `:additive 1 files`.

**Deletions are never applied** — a ` D ` entry comes from a working tree that may be far
behind, and replaying it can delete work someone else added. **Re-runs must be idempotent**
since landing does not remove the local copy: compare local blob shas against the base tree
and drop what already matches.

### A test file is additive by path and still breaks the build

SSoT: `:unresolved-refs-gate` in the edn. Gate: `unresolved-refs-gate!` in
`scripts/cleanup-land.cljs`.

`:additive` merges on one argument: no path of this name exists on the default
branch, so no existing line is rewritten. That is true of **lines** and silent about
**the build**.

Measured on `kotoba-lang/kotoba-kir`, found 2026-08-19. `b0472c3`
`cleanup: land untracked WIP (1 files)` (2026-08-14) landed
`test/kotoba/kir_value_runtime_test.clj`, which reads `kir/value-runtime-operations`
— a var defined in **no ref of the repo and nowhere in the fleet**. All four of its
deftests drive `kir/execute` with `value-intern` / `value-hydrate` / `value-resolve`
/ `value-cid-of` / `value-release`; `src/kotoba/kir.cljc` has 0 occurrences of each.

The suite therefore **did not compile for five days**:

```
Syntax error compiling at (kotoba/kir_value_runtime_test.clj:65:10).
No such var: kir/value-runtime-operations
```

Not a red suite — zero tests ran, so nothing in that repo was checked at all, which
is harder to notice than a failure. Removed in `kotoba-kir#54`; the suite went from
`0 tests run` to `Ran 136 tests containing 581 assertions. 0 failures`.

The commit's own message reads *"Purely additive: none of these paths exist on main,
so no existing line is rewritten."* Correct, and beside the point.

**What the gate does.** For each landed `.clj/.cljc/.cljs` file it reads the ns form's
`:as` aliases, and for every alias whose namespace has source **in this repo**, checks
that the symbols used through it are actually defined there. External dependencies are
out of scope, not "clean". When a namespace's source can be read from neither the
working tree nor the base ref it declines to answer, and it always prints `scanned N`
so "found nothing" is distinguishable from "looked at nothing".

**It demotes to `:review`; it never drops.** The scan is regex lexing, and regex lexing
of Clojure is approximate. Measured false-positive rate over 99 files on `main` across
5 repos whose suites run: **1 in 99** — `kotoba.kir.value` genuinely aliases
`kotoba.kir.cljs-i64 :as i64` and genuinely contains `i64/f64`, on line 1437, inside a
docstring, meaning "i64 or f64". Blanking strings and comments before the scan removed
the other one; this survives because a file containing regex literals can mispair
quotes. One human glance per hundred files is the right price for catching a repo whose
suite silently stopped compiling — one silently discarded file would not be.

Proven both ways, in the real pipeline rather than in a unit test:

| probe (same shape, same repo) | unpatched | with the gate |
|---|---|---|
| references a var that does not exist | `:additive` → PR → **merge** | **`:review`**, named, with the symbol |
| references only vars that exist | `:additive` → merge | `:additive` → merge |

### Every filter guarded `:additive` only; `:review` was committed unchecked

SSoT: `:tracked-safety-gate` in the edn. Gate: `tracked-safety-gate!` in
`scripts/cleanup-land.cljs`. Proof: `nbb scripts/cleanup-land.cljs --selftest-tracked-gate`.

`plan-repo` classifies with `grouped (group-by #(classify-file dir %) untracked)` — the
untracked set, and nothing else. `:tracked` reached `server-commit!` having passed through
no filter at all. So the credential pattern, the secret-content scan, the 2 MB ceiling and
the build-output exclusion were **all `:additive`-only**.

What failed was the *route*, not the pattern: `junk-re` has carried `.cpcache` and
`.shadow-cljs` since the day it was written. Measured 2026-08-21, both reached a PR through
`:review`:

| PR | what it was |
|---|---|
| `kotoba-lang/kotobase-worker-shell#3` | 103 files, all `.shadow-cljs/builds/test/dev/` compiler output, **+29,071 lines** |
| `cloud-itonami/ai-gftd-dougaka#6` | `clj/.cpcache/*`, whose contents are one machine's absolute paths (`/Users/junkawasaki/.m2/…`) |

**The same hole is in the credential side**: edit a tracked key file locally and it is
committed and pushed. That makes this a safety fix, not a noise fix.

The drop rule differs by class:

- **credential / secret content** — dropped unconditionally. No exception to the floor.
- **over 2 MB** — dropped.
- **junk path** — dropped **only when absent from base**. If base has it, the repo tracks
  that artifact deliberately (such repos exist), so discarding it silently is the more
  dangerous move; it stays and a human reads it.

When `base-map` cannot be fetched the junk question cannot be asked, so only credential and
size apply and the gate prints `:applied :partial` — "could not measure" must not look like
"measured and clean" (ADR-2608136000). Local working trees are never touched; what is
dropped is already in that repo's `.git/stash-archive-<date>/tracked-modifications.patch`.

Proven three ways, re-runnable by anyone:

| case | kept | dropped |
|---|---|---|
| junk absent from base | 1 | `{:skip-junk-new 2, :skip-credential 1}`, `applied=true` |
| junk present in base | 3 | `{:skip-credential 1}` |
| base tree unavailable | 3 | `{:skip-credential 1}`, `applied=:partial` |

Credential drops in all three.

Never: treat `:review` as safe because it is not merged (it is still committed and pushed);
drop junk without asking whether base tracks it; pass off a question you could not ask as a
clean answer.

### A renamed-away path is absent from `main` for exactly the reason a new path is

SSoT: `:residue-gate` in the edn. Gate: `scripts/rename_residue.cljs`, entry point
`residue-gate!` in `cleanup-land.cljs`, proof `nbb scripts/rename-residue-test.cljs`
(offline; `--repo <dir> --base <ref>` runs it against a real checkout).

`:additive` merges on one argument: *no path of this name exists on the default branch, so
no existing line is rewritten*. A directory rename destroys that argument precisely. The
moment `worker/` becomes `clj-edge/`, every old path stops existing on `main` — that is
what a rename **means**. Any stale copy left in the shared checkout at the old address
therefore satisfies `:additive` perfectly and gets committed, PR'd and merged as new work.

Measured on `net-kotobase/control-plane` (found 2026-08-12):

| commit | date | effect |
|---|---|---|
| `04e1514` | 08-03 | `worker/` → `kotobase-edge/`, 100 %-similarity renames, 48 → 0 tracked |
| `5a6a55b` | 08-04 | `clj-edge/` → `kotobase-api-gateway-cljs/`, same, 136 → 0 |
| `8f7fbaf` | 08-05 | **`cleanup: land untracked WIP (1 files)`** — `worker/` 0 → 1 |
| `d96f18b` | 08-06 | **`cleanup: land untracked WIP (16 files)`** — `clj-edge/` 0 → 14 |

Two cleanup passes put 17 files back under the old names, one and two days after the moves
were made deliberately. `clj-edge/src/kotobase/site_skin.cljc` was byte-identical to the
revision immediately *before* the commit that superseded its design — the old doctrine
restored a week after it was argued out. `worker/src/edge-app.generated.mjs` is a build
artifact: `.gitignore` can only name the live path
(`kotobase-api-gateway/src/edge-app.generated.mjs`), so the copy at the pre-rename address
was never excluded. Deleted again in `3c99bd5` (merge `0ba825a`). **The files were the
symptom; the cleanup pass is the bug.**

**What separates residue from WIP is history, not the disk.** On disk they are identical —
untracked files with real content — and `git status` does not consult history. Three
signals, cheapest first:

1. the path itself is recorded as a **rename source** reachable from base;
2. the content is **already in the object database** at that same path;
3. an **ancestor directory was moved away wholesale** and has no live path on base.

Signal 3 closes the `.gitignore` hole: a generated file is in nobody's history, so 1 and 2
miss it, but it has a dead parent. Follow the rename chain forward (`worker/src/` →
`kotobase-edge/src/` → `kotobase-api-gateway/src/`, two hops measured) and run
`git check-ignore` on the corresponding live path. A file the repo declares it ignores at
its live address is not WIP at its dead one.

**Three verdicts. No silent-drop path** — a cleanup pass that quietly discarded files would
be worse than the bug being fixed:

| verdict | meaning | action |
|---|---|---|
| `:residue` | the bytes are already in this repo's object database, or the repo declares it ignores them | dropped from `:additive`, reported by name with the live path. Nothing is deleted from the working tree. |
| `:suspect` | the path is dead but the content is **not** in history — possibly a real edit against a stale copy | demoted to `:review` (draft PR, human decides) |
| `:wip` | neither | stays `:additive` |

Under that split the incident is fully prevented: 17/17 classified `:residue` (16 by rename
source, 1 by the ignore probe), nothing reaches `main`.

**Which ref judges it.** Live paths come from the `base-blobs` tree cleanup-land already
fetched from the GitHub API. The commit graph is read locally: `<remote>/<base>` when it
resolves, otherwise `HEAD`. `HEAD` suffices because an untracked leftover at a pre-rename
path can only exist in a checkout at or **after** the rename — before it, those paths are
tracked, not untracked. The fallback is load-bearing, not decoration: measured 2026-08-12,
only **43 of 121** `orgs/kotoba-lang/*` checkouts resolve `<remote>/main` (west fetches
into `refs/west/*`), so a gate reading liveness from local refs alone would sit silent in
two thirds of the fleet. If neither resolves, the gate **warns and skips** — never reports
"no residue" for a question it could not ask.

Cost: 3.8 s for the incident's 17 candidates (`renames-seen=496`; renames via
`-M --diff-filter=R`, historical blobs via one `--raw` process instead of one
`rev-parse` per commit). False positives measured by scattering 40 genuinely new files
through live directories of that same rename-heavy repo: residue 0, suspect 0, wip 40.

Never: treat "not on `main`" as proof a file is new; pass a skipped check off as a clean
one; auto-exclude `:suspect` alongside `:residue`; delete anything from the working tree.

### PR の題に指示を書かない。題は中身を述べ、状態は state が担う（2026-08-24、オーナー指示）

**`DO-NOT-MERGE` / `DO NOT MERGE` / `DO-NOT-AUTO-MERGE` を題に書いた PR を作らない。**
これは 2026-08-24 のオーナー指示（「do not merge が pr に存在するのが不適切なので、
削除なら削除、merge なら merge で cleanup して、また今後こういった do not merge のような
分かりずらい status のものを作成しないように」）であり、`scripts/cleanup-land.cljs` の
2 箇所の題を書き換えて実装済み（`grep -c DO-NOT-MERGE scripts/cleanup-land.cljs` = 0）。

**題に書いた指示は強制力を持たない。** 実測 2026-08-24、`cloud-itonami/cloud-itonami-app`
の姉妹 PR 2 本は同じ題「DO NOT MERGE — rescued the stranded …」で開かれ、**`#123` は
2026-08-23 に merge された**。題は merge を止めず、履歴に嘘だけを残した。止めていたのは
draft state の方である。したがって:

- **題**は中身を述べる（`cleanup: rescue 3 uncommitted tracked change(s) from the shared checkout`）
- **merge を止める**のは draft state（GitHub が機構として拒否する。読まれる必要がない）
- **body** は「何を測れば決まるか」を書く（削除行数 / base の遅れ / main との overlap）

**そして disposition は必ず付ける。** 開けたままにするのは第 3 の選択肢ではない。
1 件ずつ merge か close まで持っていくのが cleanup。

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

**Never**: 題に指示（`DO-NOT-MERGE` 等）を書いて判断の代わりにする / draft のまま放置して
「GitHub が merge を防ぐから安全」で終わらせる（防いでいるのは事故だけで、判断は誰もして
いない）/ archive せずに close する（`:retirement :archive` と同じ非交渉ルール）/ 同じ
形のものを 1 クラスタとして一括処理する。

Three kinds of "no remote", needing different handling: has commits (create + push);
**no commits at all** (create empty repo, land via a parentless root commit through the API,
never touching the local checkout); **exists upstream but local lost `origin`** (reattach,
do not create — measured: `kotoba-lang/org-threejs` had 10 of 12 files already landed).

## ignore 規則を書く前に、base tree に当てる（2026-09-10）

着地候補を黙らせるために `.gitignore` へ規則を足すときは、その規則が **base tree**
（`git ls-tree -r <remote>/main`）の何に当たるかを測る。**`git ls-files` に訊かない。**

これは `stale-ignore-gate` と同じ論拠の逆向きである。あの gate が base の `.gitignore` を
GitHub から取るのは west checkout が数十 commit 遅れるからで、同じ理由で checkout の
*tracked 集合* も遅れている。`git ls-files` は「この checkout が知っている tracked」で
あって「この repo が track している」ではない。

実測 2026-09-10、`kotoba-lang/giemon`: checkout に `sim-loop/` が 1 ファイルも無く 351 件が
全部 untracked に見えたので `/sim-loop/` を「完全に未追跡の bot scratch」として landed した。
**main はそこに 194 ファイルを track している**（`bench-001..067.md` / `falsify-001..023.md` /
`probe_*`）。revert 済み。

同じ日、同じ形が逆に働いた例が 2 つある:

- `kotoba-lang/torihiki` は `evidence/*.out` を 232、`*.err` を 121、`*.md` を 156 track して
  いる。`evidence/*.out` は junk ではなくこの repo の成果物で、`tracked-safety-gate` の
  「junk パスは base に無いときだけ落とす」と同じ判断がここでも要る。ignore したのは base が
  1 件も track していない `*.py` / `*.sh` / `*.log` だけ。
- `net-kotobase/docs` は tracked 238 件が untracked 5,919 件と同じ `_b<N>_*` 命名・同じ拡張子
  で、同じ corpus の一部だけが commit されている。パターンでは決まらないので規則を書かず、
  未決として報告した。**決められないことを、決めたふりで黙らせない。**

手順（両方向を出すまで規則を書かない）:

```bash
d=orgs/<org>/<repo>
# remote は URL に <org>/<repo> を含むものを選ぶ（origin とは限らない。
# 実測: net-kotobase/docs の remote 名は bench_fetch）
rem=$(git -C "$d" remote -v | awk -v p="<org>/<repo>" '$3=="(fetch)" && index($2,p)>0 {print $1; exit}')
git -C "$d" fetch -q "$rem" main
git -C "$d" ls-tree -r --name-only "$rem/main" > /tmp/base.files

printf '/sim-loop/\n' > /tmp/rule.excl          # 候補の規則だけ
git -C "$d" -c core.excludesFile=/tmp/rule.excl check-ignore --no-index --stdin < /tmp/base.files
#   ^ 1 件でも当たったらその規則は書かない
git -C "$d" -c core.excludesFile=/tmp/rule.excl check-ignore --no-index --stdin < /tmp/keep.files
#   ^ 着地させると決めたファイル。ここも 0 件でなければ書かない
```

仕組み自体にも control を通す — 当たるはずの path が当たり、当たらないはずの path が当たら
ないことを 1 回ずつ見る（`core.excludesFile` が先頭 `/` 付きの規則を repo root 起点で解釈する
ことを確かめずに、0 件を「安全」と読まない）。

数えるときは **repo 自身の `.gitignore` と `.git/info/exclude` も `check-ignore` が読む**。
合算を自分の規則の成績として報告しない（実測: `network-awai/app-hyakka` の 18 件は既存の
`data/`、`kotoba-lang/amu` の 1 件は `info/exclude` の `.npmrc`）。

**この gate は `cleanup-land.cljs` には無い。** あの script は着地の側を
`drop-already-landed` の 3-way（base に無い / 在って同内容 / 在って内容違い）で正しく守って
おり、giemon を通しても事故は起きなかった — 守られている経路の *外* で手で規則を書いたのが
原因である。ignore 規則を書く行為は cleanup の write path でありながら道具を通らないので、
当面はこの手順を人が回す。

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
| `:true-orphan-git` | Register or retire; report, never silent-delete. **Verified only** since 2026-08-23: GitHub was asked and named no successor. Bulk path: `west-triple-sync plan --scope orphans`. |
| `:renamed-upstream` | Rename residue — the remote redirects to a repo west already carries. **Do not re-register.** |
| `:renamed-unverified` | **Neither an orphan nor a decision.** GitHub could not be asked (`:ask-failed`) or there is no remote to ask (`:no-remote`). Never register, never retire. Re-run when `gh` can answer. |
| `:path-override-leftover` | Do not re-register old path (new path is already in west). |
| `:worktree-scratch` | Session debris; remove only after unpushed-WIP check. |
| `:personal` | Out of west scope. |
| `:true-orphan-nongit` | Report; register only if it becomes a real repo. |

**訊けなかったことを「無い」と読まない（2026-08-23、ADR-2608230300）。** audit は
`:true-orphan-git` の候補 1 件につき `gh api repos/<slug>` を 1 往復して改名先を引く。
2026-08-23 の実測ではその候補 **43 件が 43 件とも改名残骸**で、そう分かったのは gh が
答えたからである。gh が答えられない日（rate limit / 認証切れ / 通信）に、以前の実装は
同じ 43 件を **`true-orphan-git`（register or retire）として印字**した —— 登録すれば規約
違反、退役させれば実害。今は `:renamed-unverified` に分かれ、**exit 2**（答えられなかった）
になる。`--blocking` の exit 0 を完了 gate に使う側は、2 を 0 と混ぜないこと。

**登録漏れは 6 時間ごとに測られている。** `manifest/orgs-detectors.edn` の
`:verify-west-registration-gap` が `west-orphan-audit --findings` を回し、壊れた
consumer 辺を FINDING として出す（セッション開始時に表示される）。この detector は
**gh を呼ばない**ので、`:true-orphan-git` は finding にせず CENSUS 行で件数だけ言う。
以前この検査は **人が「cleanup」と打った時にしか走らなかった**。

Incident reference (2026-07-12→17): `kotoba-lang/crm` pushed to GitHub; consumers
`cloud-itonami-isic-5820` / `-6201` / `-6202` use `{:local/root "../../kotoba-lang/crm"}`;
crm missing from west and often from the local tree → fresh checkout breaks.

**Repair / keep current (three planes):** see
[`manifest/west-triple-sync-workflow.md`](west-triple-sync-workflow.md) and
`nbb scripts/west-triple-sync.cljs` (ADR-2607173200).

```bash
nbb scripts/west-triple-sync.cljs plan  --scope blocking   # 壊れた :local/root 辺（既定）
nbb scripts/west-triple-sync.cljs plan  --scope orphans    # 確かめた上で未登録の repo（2026-08-23 追加）
nbb scripts/west-triple-sync.cljs apply --scope orphans    # repos.edn :extra-projects + --entry
```

`--scope orphans` が入るまで、**誰もまだ依存していない未登録 repo を一括で登録する経路は
無かった**（`--names <path>` を 1 件ずつ手で渡すしかなかった）。plan が既定であることは
変わらない。plan は判定していない候補があれば `renamed-UNVERIFIED=N` と警告する ——
scope の沈黙を「未登録は無い」と読ませないため。

**GitHub に push しただけでは終わっていない。** `scripts/cleanup-land.cljs` は GitHub 着地
までしか行わず、west 登録は別の道具が持つ。そのため cleanup-land は最後に、**触った repo の
うち west.yml に path を持たないもの**を名指しし、次に打つ `west-triple-sync` のコマンドを
印字する（west.yml を読めなかった run は「0 件」ではなく「未測定」と言う）。

## Standard Cleanup

1. Inventory each relevant repo (**ローカル走査を先に** — 上の「inventory の前に fleet 全体の west update を回さない」節):

   ```bash
   git fetch origin && git merge --ff-only origin/main
   nbb scripts/checkout-staleness.cljs   # 母集団をローカルに（94s・network なし）。fleet 全体の west update は回さない
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

0. **This whole flow is blind to gitignored content, and so is every survey that feeds
   it.** Step 2 judges a *diff*, step 3 archives `git diff` patches plus the `stash^3`
   untracked list, and `scripts/cleanup.cljs` ranks a checkout by dirty/untracked/stash/
   branch — all of it from `git status`, whose whole job is to hide ignored paths.
   Nothing below can see, judge or archive a gitignored file. Measured 2026-08-13
   (ADR-2608138400): of 249 shadow checkouts, 4 that `git status` called safe-to-retire
   held gitignored content present in no commit and in no twin —
   `com-etzhayyim-kawaraban/.kawaraban/` carries an actor Ed25519 identity in 157 bytes.
   **When the thing being retired is a CHECKOUT rather than a stash, run
   `scripts/shadow-ignored-content-survey.cljs` first and archive what it names by
   copying the files — `git diff` cannot express them.** The warning cleanup.cljs already
   prints (untracked work vanishes the moment `git checkout` runs in a shared tree)
   applies verbatim to ignored work, which it never prints.

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

   **Append-only ledgers are judged on a semantic key, not on the line.** If the diff
   touches `90-docs/business/canvas-ledger.edn`, `90-docs/design-quality/design-quality-ledger.edn`
   or `manifest/fleet-db.ledger.edn`, read `:append-only-ledger-gate` in the edn before
   deciding — a missing ledger line is un-landed *data*, not a stale generated artifact.
   But strip `:event/at` before comparing: `kagami reconcile` re-stamps it on every run,
   so two agents reconciling the same drift seconds apart produce lines identical in
   `:event/type`, `:repo/name`, `:pin/old`, `:pin/new` and `:event/seq` that differ only
   in their timestamp. Measured 2026-08-15: a line-exact test called **37 of 37** events
   missing from `main`; on the semantic key **35 were identical**, 33 seconds apart. A
   false `:unlanded` disables the gate — it parks retirable branches forever and invites
   the append this section forbids.

3. **Archive everything before dropping.** Export each stash as a patch (plus its
   untracked-file list from `stash^3` when present) into
   `.git/stash-archive-<date>/` with an `index.txt` of `SHA | message`. Dropping is then
   fully reversible without relying on reflog/gc timing.

4. **Rescue unlanded content to a branch, not back into a stash.** Build the branch in a
   sparse worktree outside the superproject (full checkout is slow):

   ```bash
   nbb scripts/root-worktree.cljs create stash-rescue-<date> --include <directory>
   cd <helper-printed-exact-path>
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

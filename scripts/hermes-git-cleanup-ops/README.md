# hermes-git-cleanup-ops — ローカル側 cleanup の定期出口

Owner 指示 2026-09-16「git cleanup conflict を定期的に行う bot profile, cron も立てて」。

## 役割分担 (duplication なし)

| bot | 面 | job |
|---|---|---|
| `pr-drain` | fleet の open PR disposition | fe323bd36793 (*/3h) |
| `branch-drain` | PR を持たない **remote** branch | 86e1107fb102 (*/2h) |
| **`git-cleanup-ops`** | **ローカル側**: superproject 同期・stash/branch retirement・子リポ unlanded WIP の :additive 着地・west-orphan blocking 検査 | (下記) |

正本手順は `manifest/cleanup-workflow.md` / `manifest/cleanup-workflow.edn`
(`:workflow` `:retirement` `:west-orphan` `:west-update`) と skill `git-cleanup-conflict`。
本 dir の script はそれらの**読み取り専用ラッパー**であり、規則の写しは SOUL に置いた
(cron job は CLAUDE.md を 32,000 字で切るため bot 規則は SOUL に置く — remote-drain 節の罠)。

## 2 つの script

### `git_cleanup_ops_evidence.py` — 計測のみ (cron script: に注入)

runbook の local-first 手順 (2026-09-09 改訂) をそのまま回す。network は superproject fetch
と gh 照会のみ。**fleet 全体の west update は絶対に回さない** (:west-update 参照)。

1. superproject `git fetch` + `merge --ff-only` (拒否されたら blocker を報告 — stash で押しのけない。
   ledger append line なら `scripts/ledger-land.cljk`)
2. `kbb --backend sci scripts/checkout-staleness.cljk` (~150s・network 0)
3. `kbb --backend sci scripts/cleanup.cljk --unlanded` (実測 402s・ladder 順で WORK 行に整形)
4. `kbb --backend sci scripts/west-orphan-audit.cljk --blocking` (exit 0/1/2 を区別して印字)
5. superproject の stash list (SHA キー) / branch list (origin/HEAD 祖先性 + ahead 数)

失敗 phase は `REFUSED <phase>` として続け、全部失敗なら `REFUSED-ALL`。exit は常に 0
(banner を prompt に残すため)。判定は一切しない。

### `git_cleanup_retire.py` — archive-first 強制の実行器 (bot が terminal から呼ぶ)

headless cron では `git stash drop` / `git branch -D` / `rm` が承認 gate で拒否される
(fleet skill の pitfall 記載)。そこで削除系はこの whitelist script に寄せる。
script 自体が順序を強制する: **`drop-stash`/`drop-branch` は該当 SHA の archive
index entry + patch/diff ファイルの実在を `<gitdir>/stash-archive-YYYYMMDD/` に確認できなければ
REFUSED**。gitdir は `rev-parse --absolute-git-dir` で解像 (`.git` がファイル/linked
worktree の盲点、2026-08-22 項目)。`routine/*` `git-annex` `main` `master` は名前拒否。
worktree 使用中の branch は拒否。判定 (landed/superseded/unlanded→rescue) は agent が
content-containment で行い、script に渡すのは判定済み SHA/name のみ。

Subcommands: `resolve` `archive-stash` `drop-stash` `archive-branch` `drop-branch` `list`。
両方向の gate は 2026-09-16 一時 repo で実証 (drop-before-archive→REFUSED、archive→drop 成功、
unarchived branch→REFUSED、使用中 branch→worktree 検出して REFUSED)。

## 自走 run の流れ (SOUL と同じ)

INVENTORY の WORK 行から最大 3 retirement or 1 land batch。着地は
`kbb --backend sci scripts/cleanup-land.cljk --apply --names <repo[,repo…]>` (≤3 repo)。
remote branch / PR の disposition は触らない (branch-drain / pr-drain の職務)。

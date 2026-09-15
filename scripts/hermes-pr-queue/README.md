# hermes-pr-queue — PR が積み上がらないようにするための 2 本の計器

Owner 指示 2026-09-05「github で pr が積もってしまっているので, hermes agent bot で
pr が積み上がらないように整えて」。

## 何が起きていたか（2026-09-05 実測）

fleet の open PR は **265 件**（うち com-junkawasaki 146 / dependabot 99 / その他 20）。
自分たちの 146 件のうち **87 件が CONFLICTING** で、最古は 522 日前。詰まりは 2 箇所:

| 面 | 実測 |
|---|---|
| **出口が止まっていた** | `pr-cleanup/pr-queue-review` は 11:32→13:00 の 88 分走って `Interrupted by shutdown` で殺され、2 回連続失敗。毎 run の大半を「queue を探すこと」に使っていた |
| **出口が queue の一部しか見ていなかった** | `kotoba-merger` は `head:svelte-to-cljs` / `head:bot` で検索しており、**`agent/*` の PR を 1 件も見ていなかった** |
| **入口が止まらない** | otent は 6 本の毎時 job が同じファイルを触る PR を作り続け、誰も流さないので後から作られた PR が全部 conflict（open 22 / うち 21 が CONFLICTING）|

## 2 本の計器

### `pr_queue_scan.cljs`（+ `pr_queue_scan.py` launcher）— 出口の入力

fleet 全 org の open PR を 1 回で測り、**処理順に並べた bounded な worklist** を出す。
drain bot（`pr-cleanup/pr-queue-review`、`kotoba-merger/kotoba-merger-tick`）の
cron `script:` に付いており、その stdout が agent prompt に注入される。agent は
queue を探さない — 測られたものを処理する。

```bash
kbb --backend sci scripts/hermes-pr-queue/pr_queue_scan.cljk            # 既定: 7 org, cap 5, work 25
kbb --backend sci scripts/hermes-pr-queue/pr_queue_scan.cljk --author any --orgs kotoba-lang --work 10
python3 scripts/hermes-pr-queue/pr_queue_scan.py          # cron runner が使う経路（.py 経由で nbb を起動）
```

出力（TSV、40 行程度）: `SCANNED` / `TOTAL` / `OVERCAP|REPO` / `WORK <n> <action> …`。
action は `CLOSE-SUPERSEDED?`（touch した全 path の blob が base と同一）/
`VERIFY-AND-MERGE` / `RESOLVE-THEN-MERGE` / `DRAFT-DECIDE` / `INVESTIGATE-RED`。

**evidence floor**: query が 1 本でも失敗したら `REFUSED` を出して **exit 2**（0 でも 1 でもない
= 「答えられなかった」）。**測れなかった queue を空の queue として報告しない。**

### `backlog_gate.py` — 入口の栓

producer bot の evidence script から呼ぶ。その repo の自分の open PR を数え、cap 以上なら
**この run は PR を作らず 1 件 drain しろ**と言う。

```python
from backlog_gate import print_gate
print_gate("cloud-itonami/otent")      # True を返したら「この run は PR を作らない」
```

```bash
python3 scripts/hermes-pr-queue/backlog_gate.py cloud-itonami/otent   # exit 1 = over/unknown
```

3 状態（`under` / `over` / `unknown`）を出し、**unknown は over と同じく塞ぐ**。

**数えるのは open ではなく「誰も流していない」PR**（既定 6h より古いもの。`PR_BACKLOG_STALE_HOURS`）。
burst は backlog ではない —— app-hyakka は一瞬 13 件 open を抱えつつ 24h で 100 件以上 merge して
おり、otent の 22 件は日単位で止まっていた。open 数だけで数えると、**健全な loop と詰まった
loop に同じ栓をする**。

⚠ **`gh pr list --repo <存在しない repo> --author X --json …` は exit 0 で `[]` を返す**
（`--author` を付けると search 経路に入り、到達できない repo を「open PR なし」と答える。
実測 2026-09-05）。だから gate は数える前に `gh api repos/<repo>` で repo を解決する。
この 1 手が無いと、rename / 削除 / token 到達不能が**短い queue と同じ顔**になり、栓が開く。

## 配線（2026-09-05 時点）

| profile / job | 変更 |
|---|---|
| `pr-cleanup/pr-queue-review` | `script: scripts/pr_queue_scan.py`、周期 360m → **120m**、予算を merge 3 / resolve 2 / close 3・**25 分で切り上げ**に固定 |
| `pr-cleanup/pr-queue-pulse` | **paused**（差分観測は scan の prelude に吸収。`paused_reason` に記録済み）|
| `kotoba-merger/kotoba-merger-tick` | head 接頭辞の検索をやめ scan の worklist を入力に。job 名が prompt 断片になっていたのを修正 |
| `otent`（6 job） | evidence script に backlog gate、prompt に「over / unknown なら作らず drain」|

profile 側の copy は端末ローカル。**正本はこの directory** で、変更したら profile へ
re-copy し、`python3 scripts/hermes-cron-jobs/export_cron.py` で cron 台帳を更新する。

## まだ塞いでいない穴

- **dependabot 99 件**は別問題。GitHub Actions は fleet 全体で無効（ADR-2607300900）なので
  これらの PR は **checks を一度も持てない** — 「緑だから merge」が構造的に成立しない。
  merge するか、PR 生成を止めて alert だけ残すかは owner 判断。ADR-2609051400 に記録。
- backlog gate が入っているのは otent の 6 job だけ。**次に入れる先は scan の `OVERCAP` 行が
  名指しする repo**（ここに repo 名を焼かない。over かどうかは測るたびに変わる）。

## 2026-09-15 — 出口を 2 profile に分け、PR 無し branch も測る

Owner 指示 2026-09-15「remote はちゃんと review and merge」「残りも進めて、効率的に進められるように
hermes profile bots を立てて」。手で 1 日かけて測った結果と、それを引き継ぐ 2 本の profile。

### 実測（手動 pass、2026-09-15）

| 面 | 実測 |
|---|---|
| remote branch（87 repo） | 3,746 本 = 着地済み 1,556 / 未着地 2,190。未着地のうち **PR を持つのは 134 本だけ** |
| 未着地の内訳 | superseded（厳密 containment）270 / clean・小・追加のみ 85 / clean・削除あり 90 / clean・大 84 / **conflict 1,616**（40 に PR、1,133 は 08-15 以前） |
| open PR（8 org） | 183 = mergeable 111 / conflicting 44 / unknown 28。dependabot 58、archived repo 31（close 不可）、当日の loop 出力 12 |
| その日に着地 | kotoba-lang/kotoba-lang 台帳 tick 60–71（bot が push だけしていた 17 branch の superset）、isic-6810 ADR-0002、cloud-murakumo env template ×2 + docs/actions.md、app-aozora-engine #20 |
| その日に削除 | remote branch **1,896 本**（着地済み 1,552 + close 判定 344。全件 live `ls-remote` で tip 一致・祖先性・archive 存在を再検証してから `git push --delete`）。archive: superproject `.git/stash-archive-2026-09-15/remote-{branches,review}/` |

読んで**誤りと分かって close** したものがある（kotoba-wasm の ADR 0044 `Extends` 書換 — 本文が ADR 0046 の
続きで main が正、`bot: re-apply PR #N additions on main tip` 19 本 — ファイル末尾に断片を追記して EDN を壊す
09-05 の bad-bot）。**diff を読まずに merge する出口は、この 2 件を通す。**

### code branch を merge できない理由（道具の穴、bot の責任ではない）

`.cljk` rename（2026-09-11）以降、cognitect test-runner は `*_test.clj` しか拾わず **`Ran 0 tests` で緑**、
`kbb -M:test` は `clojure.java.io` で落ちる（実測 kotoba-lang/ekyc、main と merged tree の両方）。
実行 test 数 0 は緑ではない（CLAUDE.md 8 問の 7）。両 profile の SOUL.md は **N>0 の緑だけを merge の
根拠にする**と書いてあり、それまで code branch は draft PR + 判定条件 comment で止まる。この穴が
塞がれば、同じ worklist がそのまま流れる。

### `branch_queue_scan.cljk`（+ `branch_queue_scan.py`）— PR 無し branch の入力

`pr_queue_scan` の姉妹。roster（`branch-drain-roster.edn`、2026-09-15 の 86 repo）を cursor で
6 repo ずつ回り、remote の全 heads を fetch して測る。決めない。

```bash
kbb --backend sci scripts/hermes-pr-queue/branch_queue_scan.cljk --repos kotoba-lang/org-iso-16739 --work 10
kbb --backend sci scripts/hermes-pr-queue/branch_queue_scan.cljk --roster scripts/hermes-pr-queue/branch-drain-roster.edn --cursor-file ~/.hermes/profiles/branch-drain/cursor.edn
```

出力: `SCANNED repos= measured= absent= branches=` / `ABSENT <slug> <why>`（**測っていない、clean ではない**）/
`REPO <slug> unmerged= landed= open_pr_heads=` / `WORK <n> <action> <slug> <branch> <tip> age= ahead= files= +a/-d contained= [twin= conflicts=]`。
action: `DELETE-LANDED`（main の祖先）/ `CLOSE-SUPERSEDED`（追加行が全て main に在り、削除行が main に残っていない）/
`VERIFY-AND-MERGE`（無衝突・≤10 file・削除 0）/ `DRAFT-DECIDE` / `REPLAY-CONFLICT`（`twin=rename-only` は
衝突が `.clj→.cljk` 改名だけ）。open PR の head は除く（pr-drain の領分）。全 repo が測れなければ `REFUSED` で exit 2。

両方向: 存在しない repo → `ABSENT` + `REFUSED` exit 2（実測）。kotoba-lang/kotoba-lang 171 branch を 18 秒。
実測 2026-09-15、kotoba-lang/bim `agent/bim-editor-domain` は `twin=rename-only`（衝突 2 path とも main では `.cljk`）。

### profile

| profile | cron | script | やること |
|---|---|---|---|
| `branch-drain` | every 2h（job 86e1107fb102） | `branch_queue_scan.py` | worklist から最大 5 件。DELETE-LANDED は祖先性を再確認して削除、CLOSE-SUPERSEDED は archive してから削除、VERIFY-AND-MERGE は **diff を読み** docs/data は merge・code は test N>0 緑だけ merge、REPLAY-CONFLICT は rename-only だけ replay |
| `pr-drain` | every 3h（job fe323bd36793） | `pr_drain_scan.py`（`--author any --work 15` 固定） | `:preservation-pr-disposition` の表 + dependabot 方針（dev-dep patch/minor で `npm ci` が通れば merge、major/runtime は comment して close）+ conflicting は 60 日超かつ main が同 file 更新済みなら close-stale、security fix は close しない |

両 SOUL.md の禁止: force-push / marker 手編集 / archive 無し削除 / comment 無し close / 1 run 6 件目 / superproject checkout での commit /
`.github/workflows` を足す branch の merge / 当日の loop 出力（`bot/` `scout/` `mktg/`）への介入。
予算: hermes-budget-policy の名簿に無いので tier 3（先に止まる）。台帳: `scripts/hermes-cron-jobs/`。

⚠ この節を書く途中で `.claude/hooks/west-pin-verify-guard.cljk` が command 文字列中の「git push」literal に反応し、
verifier を stock nbb で起動して `scripts.nbb-compat` を解決できず deny した（cutover 以降 `.cljk` を解決できるのは
kbb engine だけ、ADR-2609111700）。hook 側の壊れであり、pin 退行ではない。

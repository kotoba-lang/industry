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
nbb scripts/hermes-pr-queue/pr_queue_scan.cljs            # 既定: 7 org, cap 5, work 25
nbb scripts/hermes-pr-queue/pr_queue_scan.cljs --author any --orgs kotoba-lang --work 10
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

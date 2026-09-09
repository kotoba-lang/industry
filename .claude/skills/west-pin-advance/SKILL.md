---
name: west-pin-advance
description: west manifest（manifest/west.yml）の pin を前進させる・repo を登録/改名する・local checkout を pin に合わせる・GitHub と local と west.yml の三点ずれを直すときの手順。生成物である west.yml を安全に変える唯一の経路（GitHub API single-entry commit）と、pin 退行・全 project 更新・topdir 誤認・`--entry` の所要時間・`--check` が常に STALE・`repos.edn` の 1 行 conflict という 6 つの実測済みの罠を含む。repo 新規登録の repos.edn 側の編集（`:extra-projects` への conj）もここ。「pin を進める」「west に登録」「west update」「pin がずれている」「manifest を再生成」で発火。west を動かす worktree の作り方もここ。
---

# west の pin を動かす

**正本は CLAUDE.md の「リポジトリ構成」節**（何が正で何が生成物か）。この skill は
その**操作面**で、実測で踏んだ罠つきの手順書。

前提を 2 つだけ再掲する（これを外すと以下全部が無意味になる）:

- `manifest/repos.edn` が**ポリシーの正本**、`manifest/west.yml` は
  `scripts/gen-west-manifest.cljs` の**生成物（手書き禁止）**。
- `manifest/fleet-db.edn` が **原本 (genpon)** — west.yml の上流の正本
  （Phase 1.5 dual-write 吸収期。ファイル名は legacy、ADR-2608147300）。
  west.yml はその写し。

## やりたいことから引く

| やりたいこと | 使うもの |
|---|---|
| **local を pin に合わせる（差分だけ）** | `kagami sync --db manifest/fleet-db.edn`（kagami）。pin と一致する repo は **`:noop` で git を起動しない**、dirty は skip、pin SHA を名指しで fetch、`--jobs` で並列。⚠ **先に `kagami reconcile` を通すこと**（下記） |
| **どの pin が remote より遅れているか（1 件）** | `west update` は**答えない**（pin に合わせるだけ）。`gh api repos/<org>/<repo>/compare/<pin>...main` の `ahead_by` |
| **どの pin が remote より遅れているか（fleet 全体）** | `nbb --classpath ".:scripts/nbb_compat" scripts/pin-tip-lag.cljs > pins.tsv`（GraphQL batch。stdout はそのまま `PINS=` に渡せる TSV）。**測れなかった batch は exit 2** で clean と区別する。向きは分類しないので、決めるのは `west-pin-put-batch.cljs` 側 |
| **pin を前進させる** | `nbb scripts/advance-pins.cljs <org> <list-file> --execute`（entry の revision 行だけ書換）→ `nbb scripts/verify-west-pins.cljs` |
| **GitHub / local / west.yml の三点ずれ** | `nbb scripts/west-triple-sync.cljs plan --scope managed`（既定 dry-run。`--scope blocking` は fresh-checkout を壊している分だけ） |
| **ずれの定期検出** | `nbb scripts/fleet-sync-tick.cljs check`（検出のみ。書かない） |

## セッション前の同期 —— checkout を pin に合わせるのは hook の仕事

**`.claude/hooks/session-start-toolchain-pin-sync.cljs`** が毎セッション冒頭で、
`manifest/session-sync.edn` に載った toolchain repo の checkout を **west pin に
合わせる**（clean なものだけ。tracked 変更・未 push commit・pin が手元に無い、の
3 つは触らず理由を出す）。手で回すなら:

```bash
nbb .claude/hooks/session-start-toolchain-pin-sync.cljs "$PWD" --dry-run  # 測るだけ
nbb .claude/hooks/session-start-toolchain-pin-sync.cljs "$PWD"            # 合わせる
```

**なぜ hook なのか（2026-09-09 の実測、ADR-2609092500）。** 共有 `amu` checkout が
**自分の west pin より 200 commit 遅れ**ており、その checkout が pin する
kotoba-sema は main より 71 遅れで、pure S-expression core は数日間「無い」ものとして
拒否されていた。**pin は正しく、tree だけが腐っていた** —— そして
`session-start-checkout-staleness` は checkout を *自分の remote の default branch* と
*読み手の多い順*で比べるので、誰も `:local/root` しない toolchain repo は 1 行も出ない。
警告を足しても直らない（CLAUDE.md 自身が「警告を読むことと同期することは別の動作」と
書いている）ので、**この hook は報告ではなく同期する**。

pin 自体が最後に fetch した `origin/main` より遅れていれば、行数と
`nbb scripts/west-pin-put.cljs <name> HEAD` を出す。**pin の前進は自動でやらない** ——
到達性検証を伴う書き込みで、共有 checkout からは行わない（下記の正経路を使う）。

## 罠 1 — `west update` は「pin に合わせる」だけで、GitHub の新しい commit を見ない

**pin と remote の鮮度は別の問い**である。`west update` は west.yml に**既に書かれた**
pin へ checkout を合わせるだけで、GitHub 側の新しい commit を pin に反映するコマンド
ではない（pin 自体の前進は別操作。「`west update` すれば GitHub 最新に追従する」と
誤解しないこと）。

実測（2026-07-03）: `nbb scripts/gen-west-manifest.cljs`（引数なし dry-run）で
kotoba-lang org 配下の character / comfyui / kami-engine / kotoba / kotobase /
murakumo 等 多数の project で、ローカル checkout が **既存 pin より遅れている**
状態を検出した。**気付かず push すると stale checkout と古い pin が他 clone と
CI に伝播する。**

対象 project を触る `git push` / `git pull` / `west update` の前に:

```bash
# 1) 対象 project の pin 鮮度を GitHub API で確認（ahead_by > 0 なら upstream が先行）
gh api "repos/<org>/<repo>/compare/<pinned-sha>...<default-branch>" \
  --jq '{ahead_by, behind_by}'
# 2) 先行していたら該当 project の checkout を最新化
cd orgs/<org>/<repo> && git fetch origin && git merge --ff-only origin/<default-branch>
# 3) manifest の pin を前進（当該 entry のみ最小 diff。wholesale 再生成は禁止）
nbb scripts/gen-west-manifest.cljs --entry <repo-name>   # ⚠ 数分〜1 時間。下記「罠 4」
# 4) 自分の変更だけを見る。--check は使わない（下記「罠 5」）
git diff origin/main -- manifest/west.yml
nbb scripts/verify-west-pins.cljs
```

これを終えてから本来の操作を実行する。

## 罠 2 — 引数なしの `west update` は 4,124 project を歩く

**`west update` は 4,124 project を歩き、既に pin と一致している checkout でも
git を起動する。** 全体を回すのは初回 clone と、pin が大量に動いた後だけ。
`manifest/west-triple-sync-workflow.edn`（ADR-2607173200）は `:never` に
**「clone all west projects by default」**を挙げており、これはその文の運用面。

```bash
# 例: 遅れている pin だけを見つけて、その分だけ同期する
gh api repos/kotoba-lang/<repo>/compare/<pin>...main --jq '.ahead_by'   # 0 なら触らない
printf '%s\n' <name> <name> | xargs west update --fetch smart           # ← xargs 必須
```

**`xargs` は必須。zsh は変数もパイプも単語分割しないので、`west update $NAMES` は
全体を 1 個の project 名として渡し `unknown project name` になり、
`printf ... | west update` は**引数ゼロ = 全 project 更新**になる（実測 2026-08-06、
10 分でタイムアウトするまで気付かなかった）。

## 罠 3 — `kagami sync` の前に `kagami reconcile`

**`kagami sync` を使う前に `kagami reconcile` を通す。** 原本 (genpon、
`manifest/fleet-db.edn`) は west.yml の**上流の正本**だが、west.yml 側の pin
書き込みを原本へ運ぶのは reconcile だけで、
それを回していた CI は 2026-07-30 に撤去された（ADR-2607300900）。**遅れた原本に
対して `kagami sync` すると checkout が pin より「後ろ」へ動く。** 実測 2026-08-07:
reconcile が未実行のまま 24 pin ぶん遅れており、dry-run が既に west pin と一致している
repo に `:advance` を出した。

```bash
# 吸収前に必ず: 入力 west.yml は origin/main のもの、変更される pin は全て fast-forward か
git show origin/main:manifest/west.yml > /tmp/west-main.yml
nbb --classpath orgs/kotoba-lang/kagami/src orgs/kotoba-lang/kagami/bin/kagami.cljs \
  reconcile --db manifest/fleet-db.edn --west /tmp/west-main.yml
```

**reconcile / sync のような manifest 書き込みは共有 checkout でやらない。** superproject
本体は「統合・閲覧専用」（CLAUDE.md「並行エージェント運用」）で、実測 2026-08-07 には
共有 checkout で走らせた reconcile の出力を、別セッションが 2 分後に巻き戻した
（同時刻に別セッションが `manifest/repos.edn` へ新規 project を登録中だった）。
worktree で走らせて branch で着地させる。

## 罠 4 — `--entry` は one-liner ではない。数分〜1 時間かかり、進捗を 1 行も出さない

**`gen-west-manifest.cljs` は `--entry` を付けても `render` を全 project 分計算する。**
`render` は path ごとに `project-entry` を呼び、その中の `working-head` が
**`git -C <path> rev-parse --show-toplevel` と `git -C <path> rev-parse HEAD` の 2 回、
別プロセスの git を起動する**。4,200 project 分である。

**所要時間は「その checkout に子リポが何本実在するか」で決まる**ので、定数で持たない
（測り方だけ持つ）。実測 2026-08-22 の 2 点:

| 走らせた場所 | `orgs/*/*/.git` の数 | 所要 |
|---|---|---|
| 共有 checkout（子リポが全部在る） | 4,474 | 約 55 分 |
| `origin/main` から切った空の worktree | 0 | 4 分 27 秒 |

**その間、標準出力は完全に無音**である。読み手は hang したと判断して kill する
（実測でそうなった）。長いと知った上で流し、**`timeout … | tail; echo EXIT=$?` で
包まない** —— `$?` は pipe の最後（= `tail`）の値なので、`timeout` に殺されても
`EXIT=0` が出る。`> file` に落としてから exit を採る（CLAUDE.md「検査を書く前・
緑を信じる前の 5 問」）。

**`--check` も同じコストを払う。** `render` は `(if check? …)` の**前**で評価される
ので、「軽い検査」ではない。

## 罠 5 — `--check` は無改変の `main` でも STALE。あなたの変更について何も答えない

**`nbb scripts/gen-west-manifest.cljs --check` を「自分の変更が canonical か」の
確認に使わない。** 対照実験（実測 2026-08-22、`origin/main` を checkout しただけの
worktree、`git status --porcelain` が空）:

```
$ nbb scripts/gen-west-manifest.cljs --check
west.yml is STALE. run: nbb scripts/gen-west-manifest.cljs
CHECK_EXIT=1
```

**一時的な drift ではなく構造的に一致しない。** 理由は 2 つあり、どちらも
「誰かが手で直せば消える」種類ではない:

1. **順序が違う。** `render` は path を必ず `distinct sort` で出すが、west.yml の
   実ファイルは sort 順に並んでいない（実測 2026-08-22: `path: orgs/` 行 4,223 本の
   うち `diff` が 864 行を出す）。`--check` は
   `(= (slurp out-file) rendered)` の**バイト完全一致**なので、順序が違えば
   中身が同じでも STALE。
2. **集合が違う。** `render` の path は
   「west.yml の既存 path ∪ `repos.edn` の `:extra-projects` ∪ `kotoba-workspace` の
   components」を union する。実測 2026-08-22 で render 側 **4,500** に対し
   west.yml 側 **4,223** —— **282 path は登録口の側にだけ在って west.yml に entry が無く**、
   5 path は `:path-overrides` で移動する。

そして **`--check` の出力は 1 行の `STALE` だけで diff を出さない**ので、
仮に一致していない理由が 2 つ以上あっても、**あなたの entry 由来の差分と、
以前から在る drift とを分離できない**。

**代わりに使う検査（どちらも自分の変更だけを見る）:**

```bash
git diff origin/main -- manifest/west.yml   # 自分が動かした entry だけが出る
nbb scripts/verify-west-pins.cljs           # pin の存在・default branch 到達性・前進
```

`verify-west-pins` は生成器の中でも走る（下記「pin 検証」）ので、**pin の正しさは
`--check` に依存していない**。`--check` が担っていたのは「生成器と west.yml が
バイト一致か」だけで、それは今日 **どのみち一致しない**。

## repo の新規登録 —— `repos.edn` 側にはスクリプトが無い

**登録は 2 面ある。west.yml 側は `--entry`、`repos.edn` 側は自分で書く。**
`scripts/` の似た名前の 2 本は**どちらもこの用途ではない**:

- `register-archived-west-project.cljs` —— `:extra-projects` に conj するが、
  **同時に `:manifest.repos/archived` に `{:group "archived" :note …}` を刻む。**
  live な repo に使うと `archived` group へ隔離され、既定の group-filter `-archived`
  で `west update` の対象から外れる。
- `relocate-west-project.cljs` —— **既に在る** project の path を移す。新規登録ではない。

**実際の編集は `:manifest.repos/extra-projects` ベクタへの `conj` 1 つ**である。
`manifest/repos.edn` は **1 行・約 490 KB** の datomize 済み EDN（entity 1 個）だが、
**reader → writer で byte 完全に round-trip する**（実測 2026-08-22:
490,695 → 490,695、`byte-identical = true`）。だから reader/writer で編集してよく、
そのほうが構造的に安全である。**文字列置換や `sed` でやらない。**

```bash
nbb -e '
(require (quote [cljs.reader :as reader]) (quote ["fs" :as fs]))
(let [p "manifest/repos.edn"
      tx (reader/read-string (.readFileSync fs p "utf8"))
      e  (first tx)
      xs (vec (:manifest.repos/extra-projects e))
      new-path "orgs/<org>/<repo>"]
  (when-not (some #{new-path} xs)
    (.writeFileSync fs p
      (str (pr-str [(assoc e :manifest.repos/extra-projects (conj xs new-path))]) "\n"))))'
git diff --stat -- manifest/repos.edn   # 1 行ファイルなので "1 insertion, 1 deletion" が正常
```

⚠ **`git diff` は 1 行ファイルの差分を全文で出す。** `--stat` で見て、中身は
上の reader で読み直して確かめる（`(count (:manifest.repos/extra-projects e))` が
1 増えていること）。

## 罠 6 — 「conflict が構造的に発生しない」のは west.yml だけ。`repos.edn` は衝突する

下記の single-entry commit 経路が conflict を消すのは **west.yml に対してだけ**である。
**`repos.edn` は 1 行なので、行指向の 3-way merge が原理的に効かない。**
branch を切って `gh api repos/<org>/root/merges` で着地させる経路を取ると、
**その間に別セッションが 1 本でも repo を登録していれば必ず `409 Merge conflict`** になる。

実測 2026-08-22（`org-ietf-tls` の登録）: 11:29 に自分の登録 commit、11:33 に
別セッションが `repos.edn` を触る commit を main に入れ、merge は 409 で弾かれ、
11:45 の `Merge origin/main into agent/register-org-ietf-tls`（`# Conflicts: manifest/repos.edn`）
で解いた。**登録は 4 分あれば衝突する。**

**安全な解き方は 1 つだけ:**

1. `git checkout origin/main -- manifest/repos.edn` で**丸ごと origin/main 側を採る**
2. 上の reader/writer で自分の 1 件を**append し直す**
3. commit して再度 merge

**1 行に付いた conflict marker を手で編集しない。** 490 KB の 1 行の中の
`<<<<<<<` を人間が正しく解けることは無く、失敗しても reader は（marker が
文字列の中に落ちれば）**黙って読めてしまう**ことがある。

## west.yml を変える唯一の正経路 — GitHub API single-entry commit

**`manifest/west.yml` への変更（登録 / rename / pin 前進）は GitHub API の
サーバ側 single-entry commit を「唯一の正経路」にする。** west.yml は生成物
（`repos.edn` ＋ 各子repo HEAD → `gen-west-manifest.cljs`、手書き禁止）
なので、行指向 pin を textual 3-way merge するのはアンチパターンで、conflict
marker の手編集は **pin を静かに壊す**。代わりに: tip の west.yml と blob SHA を
取得（dir listing から SHA を採ると巨大 base64 を避けられる）→ **当該 entry の
行だけ**編集 → blob SHA 一致で PUT（`branch=` `sha=`）。**tip がずれれば 409**
で弾かれる（取得し直してリトライ）ので **west.yml については conflict が構造的に
発生しない**（`repos.edn` には効かない —— 上記「罠 6」）。
commit 前に **pin == 子repo HEAD を検証**。API 手編集は生成器を通らないが、
**その確認に `--check` を使わない**（無改変の main でも STALE を返す。罠 5）——
`git diff origin/main -- manifest/west.yml` と `nbb scripts/verify-west-pins.cljs`
で見る。

やむを得ずローカル merge する場合のみ、west.yml の衝突は **marker 手編集でなく
再生成で解決**: superset 側採用 → `west update` で子を目的 pin に揃える
（⚠ 再生成はローカル working HEAD で pin するので、子が遅れていると黙って
ロールバックする＝pin 退行の罠）→ `gen-west-manifest.cljs` →
`git diff origin/main -- manifest/west.yml`（`--check` ではない。罠 5）。子repo
自体は普通の git（branch/PR/push）。詳細は ADR-2606272237 / `repos.edn`
`:manifest-workflow`。実例: PR #61/#62/#86、kenchi-actor→kenchi-clj rename
（`34988dd`、diff は当該 entry のみ）。

### pin 検証（`scripts/verify-west-pins.cljs`、ADR-2607022900）

pin に許されるのは「上流 repo の default branch から到達可能な commit」だけ:

1. **存在**（= push 済み。未 push のローカル HEAD の pin 化は禁止）
2. **default branch 到達性**（rewrite されうる未 merge branch 上の commit は不可）
3. **旧 pin からの前進**（behind = 静かな pin 退行 / diverged を弾く）

判定はすべて GitHub API（サーバ側 full 履歴）で行い、**ローカルの ancestry 判定
だけに頼らない**。`gen-west-manifest.cljs` は生成時に自動でこの検証を行い、
失敗したら west.yml を書かない（緊急スキップ: `--no-verify-remote` /
`WEST_PIN_VERIFY_SKIP=1`。使ったら理由を commit message に残す）。

**登録・rename・pin 前進は `--entry <name>` で当該 entry のみの最小 diff を生成する
— wholesale 再生成 commit は禁止。** 1 件の登録のつもりが未 push HEAD 由来の壊れた
pin を 44 件 main に流した実事故（`90852b86`）の再発防止。

強制するのは PreToolUse hook（`.claude/hooks/west-pin-verify-guard.cljs`。`git push`
と `gh api PUT` の両経路）と、murakumo fleet の `root-west-pin-policy` gate（policy 層）
+ tick.cljs の CD 前 `verify-west-pins`（server-side 到達性）。**GitHub Actions の
`west-pin-verify.yml` は撤去済み**（2026-07-30、ADR-2607300900 — 16 workflow
すべてが job 起動せず赤のままだった）。

## west を動かす worktree の作り方（topdir 固定）

**agent ごとの git worktree で `west update` を動かすときは、worktree を
superproject ルートの *外*（sibling path）に作り、worktree 内で `west init -l manifest`
をやり直して topdir をその worktree に固定せよ。** これをしないと west は
子リポジトリを agent の worktree でなく **superproject 本体の `orgs/` に展開**してしまい、
他 agent の WIP と衝突する・`west update` が dirty で skip される。

理由: west は cwd から上方向に `.west/` を探して topdir を決める。`git worktree add`
を superproject *配下*（既定の `.claude/worktrees/<name>` 等）で作ると、その path は
superproject のサブディレクトリなので上方向の探索が superproject 本体の `.west/`
に当たり、topdir = superproject 本体と誤認される。`WEST_TOPDIR` 環境変数での上書きも
効かない（`.west/` 発見が優先される）。superproject ルートの *外* に worktree を作れば
`.west/` に当たらず、`west init -l manifest` でその worktree 専用の `.west/` が生成され
topdir が固定される。実測検証: ADR-2607011300。

```bash
# ✅ 正: superproject の外に worktree を作り、topdir を固定
git worktree add -b <agent-branch> /tmp/root-<agent-name> origin/main
cd /tmp/root-<agent-name>
west init -l manifest                       # ← この worktree 専用の .west/ を生成
west update --fetch smart <必要な repo>     # ← worktree 内 orgs/ に独立 checkout
# ❌ 誤: .claude/worktrees/<name> 配下の worktree で west を動かす
#        （superproject 本体を topdir と誤認し、本体の orgs/ を書き換える）
```

注意:
- これでも防げないのは **上流の force-push 系**（`origin/main` の force-rewrite /
  子 repo remote の force-rewrite による pin 退行）。worktree 分離は作業 tree の
  WIP 衝突しか防ぐ。force-push は上流の運用で撲滅するしかない。
- 大容量 repo は worktree ごとに重複取得される（full history 既定のため軽減策は
  無い。恒久対応は DataLad/B2 経路への移行、`nbb manifest/west_annex.cljs
  annex-get`）。
- **後片付けで `git worktree remove` が
  `'<path>/.git' is not a .git file` で拒否することがある**（2026-08-08 に
  `/tmp` 配下の worktree 2 つで再現）。`git worktree add` は `.git` を
  **gitdir を書いた 1 行のファイル**として作るが、何かがそれを worktree
  メタデータ dir への **symlink** に置き換えており、`remove` の検証がそこで落ちる。
  作業内容とは無関係なので、ファイルに戻してから remove すればよい:

  ```bash
  # 消す前に: 未コミットが無いこと、HEAD が origin/main に含まれることを確認
  cd <path> && git status --porcelain && git merge-base --is-ancestor HEAD origin/main
  rm <path>/.git
  printf 'gitdir: <superproject>/.git/worktrees/<name>\n' > <path>/.git
  git worktree remove <path>
  ```

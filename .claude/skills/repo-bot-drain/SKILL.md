---
name: repo-bot-drain
description: repo ごとの常駐 bot（scripts/repo-bots/）が見つけた床割れを 1 件だけ直して着地させる。1 反復 = 1 finding。ローカル Claude loop（cloud.itonami.bot.repo-bot-drain）が呼ぶが、手で `/repo-bot-drain` と打ってもよい。「repo bot の指摘を直す」「未着地の作業を拾う」「床を塞ぐ」で発火。
---

# repo bot が見つけた床割れを 1 件塞ぐ

**正本は superproject ADR-2608271500。** この skill はその 1 反復ぶんの手順書で、
**会話履歴を持たない fresh context から読める**ように書いてある。前の反復が何をしたかは
会話ではなく **tick の state と ledger** から読む。

## この反復の仕事はちょうど 1 つ

```bash
nbb scripts/repo-bots/tick.cljs --next     # 直す 1 件が EDN で出る
```

**無人の loop（`cloud.itonami.bot.repo-bot-drain`）は `--next-unattended` を使い、`:landed` を
渡さない。** 他人の未 commit の作業は、失われうる唯一の床であると同時に、**誰も見て
いないときに触ってよい対象ではない**（このマシンは並行 agent が走っている）。
あなたが手で `/repo-bot-drain` を打ったなら人が見ているので、`--next` の答え
（`:landed` を含む）で正しい。無人側は `:held-for-a-human` として件数を返す。

`{:outcome :candidate :bot "<org>/<name>" :floor <床> :since <iso> :detail "…"}`

- `:outcome :not-measured` — **候補 0 件ではない。** まだ誰も測っていない。
  `nbb scripts/repo-bots/tick.cljs --wave 200` を先に回して、この周は終わる。
- `:outcome :no-candidates` — 本当に床が塞がっている。**無い仕事を作らない。**

2 件まとめない。「ついでに」他の repo を直さない —— この loop の価値は
**1 周 1 件が確実に着地すること**であって、件数ではない。

## 床ごとの直し方

### `:landed`（未 commit / 未 push の作業がある）

**最優先。他の床は放置しても情報が減らないが、未着地の作業は失われうる。**
**ただし人が見ているときだけ。** 無人の周回はこの床を飛ばす（上記）。

まず何が在るかを見る（`git -C orgs/<org>/<name> status` と `git log @{u}..HEAD`）。
**判断せずに消さない。** 手順の正本は skill `git-cleanup-conflict`（`.claude/skills/`）で、
`manifest/cleanup-workflow.edn` の `:retirement` を**節だけでなく edn 全体**読む。

- 着地判定は content-containment（追加行が現 main に含まれるか）。生成物
  `manifest/west.yml` は判定から除外する
- **drop / 削除の前に必ず** `.git/stash-archive-<date>/` へ退避する。
  「もう landed だと確信している」は archive を省略する理由にならない
- 着地させるなら feature branch → push → `gh api repos/<org>/<repo>/merges` の
  サーバ側マージ。rebase も force-push もしない

### `:pinned`（local HEAD が west pin と違う）

**どちらが正しいかを先に決める。** 2 方向あり、逆をやると退行する。

```bash
git -C orgs/<org>/<name> log --oneline -3
gh api repos/<org>/<name>/compare/<pin>...<head> --jq '{status, ahead_by, behind_by}'
```

- HEAD が pin より**先**で、その commit が upstream の default branch から到達可能
  → **pin を進める**。`scripts/west-pin-put.cljs <entry> HEAD`（skill `west-pin-advance`）
- HEAD が pin より**後ろ**、または到達不能（未 push / 別 branch）
  → checkout を pin に合わせる。`west update --fetch smart <name>`
  （**引数なしで走らせない** —— 4,200 project を歩く）
- **未 merge branch 上の commit を pin にしない**（CLAUDE.md、実測事例あり）

### `:readme`（README.md が無い / 200 byte 未満）

**まず、murakumo が書いた草稿が在るか見る。**

```bash
ls ~/.itonami/repo-bots/proposals/<org>__<name>.md          # 受理された草稿
cat ~/.itonami/repo-bots/proposals/<org>__<name>.receipt.edn # 何が書いたか・token 数
```

草稿は `scripts/repo-bots/propose.cljs` が **決定論的に集めた証拠だけ**を渡して
書かせ、**決定論的な gate**（実在しないパスを挙げていないか / 証拠に無いホストの
URL が無いか / 雛形の痕跡が無いか / 床を越えているか / repo 名を名乗っているか）を
通ったものだけが `.md` として残っている。却下された草稿は `.rejected.md` に在る。

**草稿は下書きであって正解ではない。** 中身を読んで直す。直したら gate に通し直す:

```bash
nbb scripts/repo-bots/propose.cljs --bot <org>/<name> --check-draft <file>
```

草稿が無い場合（`INSUFFICIENT-EVIDENCE` で模型を呼ばなかった場合を含む）は自分で書く。

**中身を読んでから書く。** 名前と ISIC 番号から推測した README は、この workspace が
一番嫌う種類の嘘になる（ADR-2608039980 の「索引に無いことは不在の証拠にならない」の
裏返しで、**中身を見ずに書いた索引は誤った答えを出す索引**になる）。

- `src/` と `deps.edn` / `package.json` と test を実際に開く
- 名前が機能を示さない repo は **冒頭で名乗る**（CLAUDE.md の `kuro`/`kobo` 規則）。
  併せて `manifest/concept-vocabulary.edn` への登録が要るか確かめる
- 最近接 repo との境界を 1 文書く（同一面・同一主題の重複を作らないため）
- **空の repo に README だけ書かない。** 中身が無いなら、この finding は
  `:readme` ではなく「その repo が空である」ことの報告。ADR に書いて次へ。
  tick の `EMPTY-REPO` 行がこれを数えている（実測 2026-08-27: `:readme` 411 件の
  うち **154 件は README もコードも無い**）。propose 側も証拠が 400 byte 未満なら
  模型を呼ばず `INSUFFICIENT-EVIDENCE` を返す

### `:test-signal`（コードが在るのに test が無い）

**本物の仕事なので、1 反復で無理に終わらせない。** 最小の実テストを 1 本書いて
着地させるか、無理なら「なぜ書けないか」を repo の ADR に残して次へ。
**空の test dir を作って床を塞がない** —— それはこの loop 自身を劇場にする。

## 着地のさせ方（共有 checkout を触らない）

このマシンは並行 agent が走っている。**west 管理の `orgs/<org>/<name>` を直接
編集しない。**

```bash
git -C orgs/<org>/<name> fetch origin
git -C orgs/<org>/<name> worktree add -b agent/repo-bot-<floor> \
  /tmp/rb-<name> origin/main          # 分岐元を明示する
# … /tmp/rb-<name> で編集・commit・test …
git -C /tmp/rb-<name> push origin agent/repo-bot-<floor>
gh api repos/<org>/<name>/merges -f base=main -f head=agent/repo-bot-<floor> \
  -f commit_message="…"
git -C orgs/<org>/<name> worktree remove /tmp/rb-<name>
```

worktree は superproject ルートの**外**に作る。west を worktree 内で使うなら
その中で `west init -l manifest` をやり直す（topdir 誤認の実測事例あり）。

## 終わり方

1. 直したら、その bot だけ測り直して床が塞がったことを**見る**:
   `nbb scripts/repo-bots/tick.cljs --only <org>/<name>`
   → `RESOLVED（塞がった床）` に出れば着地。出ないなら**直っていない**。
2. 出なかったら、直したつもりのものと報告されたものが食い違っている。
   ここで「たぶん直った」と書かない（ADR-2608136000）。
3. 1 行で報告する: どの bot の / どの床を / 何をして塞いだか / 証拠（PR URL か commit）。

## この loop 自身が劇場になっていないかの確認

床は 5 つとも、**その床だけを壊した fixture で赤くなり、無改変で緑になる**ことを
2026-08-27 に実測してある（`scripts/repo-bots/README.md`）。塞いだ床が
`RESOLVED` に出ないなら、疑うのは実装ではなく**自分がやった変更**の方。

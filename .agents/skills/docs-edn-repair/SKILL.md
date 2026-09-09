---
name: docs-edn-repair
description: 90-docs の中で reader を通らない EDN 文書を 1 件だけ復元して着地させる。1 反復 = 1 文書。ローカル Codex loop（cloud.itonami.bot.docs-edn-repair）が毎周これを呼ぶが、手で `/docs-edn-repair` と打ってもよい。「読めない edn を直す」「docs 修復 loop」「parse error の文書」で発火。
---

# 読めない 90-docs 文書を 1 件だけ復元する

**この skill は会話履歴を一切持たない fresh context から読める**ように書いてある。
前の反復が何をしたかは会話ではなく **tick の出力と ledger** から読む。

## 何をする反復か

`90-docs/` の文書は datom 面（`manifest/edn-query.cljs`）の入力である。reader を
通らない文書は **存在するのに、どの query にも出てこない** —— 消えたのではなく、
見えない。この反復の仕事はちょうど 1 つ:

> **読めない文書を 1 件選び、内容を保ったまま読めるようにし、main に着地させる。**

1 反復 = 1 文書。2 件まとめない。**再構成は取り違えると文書の意味を静かに変える**
ので、どれが正しくてどれが取り違えかを後から分離できる形にしておく。

## この仕事が機械化されていない理由（先に読む）

2026-08-08 に 2 通りの機械修復を試して、7 件とも同じ所で止まった。
`manifest/docs-edn-only.cljs` の `known-parse-errors` 注記によれば、**それ以前にも
別のセッションが同じ 2 つの手当てを試して同じ所で止まっている**。

壊れ方は 2 つ重なっている:

1. **サブマップの閉じ括弧が無い**（機械的に直る。`scripts/diagnose-unreadable-edn.cljs`
   の `--write` がやる）
2. **「map の並び」が vector で包まれていない** —— 値の位置に map が 2 つ裸で並ぶ。
   括弧を閉じると今度は `Map literal contains duplicate keys: :time :output …`
   （並んだ map が 1 つに潰れる）や `map literal contains 15 form(s)` になる。

②は「どう包むのが元の意図か」を読まないと決まらないので、モデルの仕事である。

## 手順

### 0. 現在地を測る（推測しない）

```bash
cd ~/github/com-junkawasaki
git fetch origin
nbb --classpath ".:scripts/nbb_compat" scripts/docs-edn-repair-tick.cljs
```

`:outcome :insufficient-scan` が出たら **何もせず終わる**。sparse checkout から
「もう無い」を言うと嘘になる（この罠は 2026-08-08 に 2 回刺さっている）。

候補の先頭 1 件を取る。`:status` が

- `:closers-only` → 機械的に直る。手順 2 の `--write` で終わり
- `:needs-reconstruction` → 本番。手順 3
- `:key-set-changed` → **書かない**。tick の出力に `:missing` / `:extra` が出る
  ので、それを報告して終わる（機械修復が内容を移している証拠）

### 1. worktree を切る（共有 checkout を触らない）

**superproject の外**に切る。分岐元は `origin/main` を明示する（AGENTS.md）。

```bash
W=/tmp/root-docs-repair-$(date +%s)
git worktree add --no-checkout -b agent/docs-repair-<短い名前> "$W" origin/main
cd "$W"
git sparse-checkout set --no-cone /90-docs /scripts /manifest /nbb.edn /package.json
git checkout -q
ln -sfn ~/github/com-junkawasaki/node_modules "$W/node_modules"
echo node_modules >> .git/info/exclude
```

### 2. 機械で直る分は機械に任せる

```bash
nbb scripts/diagnose-unreadable-edn.cljs <対象ファイル>          # dry-run
nbb scripts/diagnose-unreadable-edn.cljs <対象ファイル> --write  # 条件を満たせば書く
```

`--write` は **修復後の entity キー集合が元の外側キー集合と一致しないと書かない**。
その安全弁を外さない。

### 3. 再構成する（ここがモデルの仕事）

**原則: 構造ブラケットだけを足す。文字は 1 つも足さず、消さない。**

よくある形（2026-08-08 実測）:

```clojure
;; 壊れている: 値の位置に map が 2 つ裸で並ぶ
:restore-vs-retry-decision
{:restore-scenario "..." :decision "..."}
{:abort-scenario "..."   :decision "..."}

;; 直した形: vector で包む（唯一の情報保存的な読み）
:restore-vs-retry-decision
[{:restore-scenario "..." :decision "..."}
 {:abort-scenario "..."   :decision "..."}]
```

**包む以外の読み方は情報を失う** —— 片方を捨てるか、キー衝突させて混ぜるかしか
なく、どちらも元の内容を保たない。だから vector 化は選択ではなく、その位置で
唯一情報を保つ操作である。

エスケープされていない `"` が文字列を切っている形もある（JSON 片
`{"orders":[]}` や英語引用 `は "Creating" と報告`）。その場合は `\"` に直す。
**diff がバックスラッシュだけになる**のが正しい。

### 4. 検証する（「parse が通った」は「正しく直った」ではない）

3 つとも通ること。1 つでも落ちたら**着地させない**。

```bash
# (a) 読める。かつ entity のキーが全部キーワード（切れた文字列が残っていない）
nbb -e '(def fs (js/require "node:fs"))
        (def tx (cljs.reader/read-string (.readFileSync fs "<対象>" "utf8")))
        (def e (first (filter map? tx)))
        (println "keys" (count (keys e)) "非キーワード" (count (remove keyword? (keys e))))'

# (b) 文字の内容が保たれている。diff が構造ブラケットとバックスラッシュだけであること
git diff --stat
git diff -U0 | grep -E "^[+-][^+-]" | head -20

# (c) 面に載る。docs-edn-only の parse-errors が 1 件減ること
nbb --classpath ".:scripts/nbb_compat" manifest/docs-edn-only.cljs verify | head -1
```

**(c) を通したら `manifest/docs-edn-only.cljs` の `known-parse-errors` から
その 1 行を消す。**同じ commit で消す —— 直したのに baseline に残すと、次の run が
`BASELINE IS STALE` で落ちる（これは仕様であって不具合ではない）。

### 5. 着地させる

`git push` は west pin guard に弾かれることがある（fleet が main を数分ごとに
進めるので、branch が古くなるたび pin 退行と判定される）。`manifest/west.yml` に
触れていないなら **Git Data API で 1 commit にまとめて main に載せる**のが確実:

```bash
BASE=$(gh api repos/com-junkawasaki/root/git/ref/heads/main --jq .object.sha)
TREE=$(gh api repos/com-junkawasaki/root/git/commits/$BASE --jq .tree.sha)
B=$(gh api repos/com-junkawasaki/root/git/blobs -f content="$(base64 -i <file> | tr -d '\n')" -f encoding=base64 --jq .sha)
# ... tree → commit → PATCH refs/heads/main（実例は ADR-2608080500 の verification 節）
```

着地後、**API から取り直して byte 一致を確認する**。

### 6. 後片付け

worktree を撤去し、branch を消す。`git worktree remove` が
`'.git' is not a .git file` で断る場合は、`.git` が symlink になっているので
`readlink -f .git` の指す先を `gitdir: <path>` の 1 行ファイルに戻してから撤去する。

## やらないこと

- **2 件以上まとめて直さない。**
- **内容を「整える」ことをしない。** 誤字・表現・古い日付はそのまま。この反復の
  仕事は読めるようにすることだけで、編集ではない。
- **`known-parse-errors` に足さない。** 減らす方向だけ。
- **判断がつかなければ直さない。** 元の意図が読み取れない箇所に遭遇したら、
  その 1 件を飛ばして次の候補に移るのではなく、**何が読み取れなかったかを報告して
  終わる**。次周の tick が同じ候補を出すので仕事は失われない。

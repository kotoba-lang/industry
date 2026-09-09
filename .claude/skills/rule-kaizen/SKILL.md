---
name: rule-kaizen
description: standing な ADR / md / rule が、ある日の実装天井を『こう書くもの』として残していないかを 1 件だけ疑って着地させる。1 反復 = 1 finding。ローカル Claude loop（com.gftd.rule-kaizen）が毎日これを呼ぶが、手で `/rule-kaizen` と打ってもよい。「規則を疑う」「スナップショットが言語になっている」「rule kaizen」で発火。
---

# 実装スナップショットを言語にしない（1 finding）

**正本は superproject ADR-2608261200。** この skill はその 1 反復ぶんの手順書で、
**会話履歴を一切持たない fresh context から読める**ように書いてある。前の反復が
何をしたかは会話ではなく **tick の出力と ledger** から読む。

adr-inventory は status と pointer を棚卸しする。**決定の中身が今も正しいかは
あちらは見ない。** この反復がそれを 1 件見る。

## 何をする反復か

> **tick が先頭に出した finding 1 件を、standing な言い方から外して着地させる。**

1 反復 = 1 finding。2 件まとめない。needles に無い言い方をこの周で新しく
狩りに行かない。新しいクラスを見つけたら、直す前に
`manifest/rule-snapshot-needles.edn` へ 1 本足す（語彙は手書き。corpus から
導かない）。

## やらないこと

- **ファイルを削除しない。** 不適切でも残す。
- **決定本文を黙って消さない。** 当時の切り方だと読める 1〜2 文と、現行の
  後継 ADR。経緯の抹消が目的ではない（ADR-2607257000）。
- **parse を直さない。** それは `docs-edn-repair`。
- **status / pointer だけを直さない。** それは `adr-inventory`。
- **共有 checkout を書き換えない。** worktree。rebase / force-push しない。
- **判断がつかなければ直さない。** 何が読み取れなかったかを報告して終わる。

## 手順

### 0. 現在地を測る（推測しない）

```bash
cd ~/github/com-junkawasaki
git fetch origin
nbb --classpath ".:scripts/nbb_compat" scripts/rule-kaizen-tick.cljs
```

`:outcome :insufficient-scan` が出たら **何もせず終わる**。sparse checkout から
「もう無い」を言うと嘘になる。

先頭 1 件 (`next`) を取る。`~/.itonami/rule-kaizen-tick.ledger.edn` の末尾も読む。
対象ファイルと、`:superseded-by` が指す後継 ADR を **両方読む**。

### 1. worktree を切る（共有 checkout を触らない）

**superproject の外**に切る。分岐元は `origin/main` を明示する。

```bash
W=/tmp/root-rule-kz-$(date +%s)
git worktree add --no-checkout -b agent/rule-kz-<短い名前> "$W" origin/main
cd "$W"
git sparse-checkout set --no-cone /90-docs /scripts /manifest /nbb.edn /CLAUDE.md /AGENTS.md /.claude/skills /.agents/skills /.cursor/rules
git checkout -q
ln -sfn ~/github/com-junkawasaki/node_modules "$W/node_modules"
```

### 2. standing な文を 1 件だけ直す

tick の `:kind` と `:fix` を読む。対象の文が **現行方針**として読めるときだけ直す。

- 当時の測定・切り方なら、過去形と後継 ADR を同じ段落に置く。
- demurrer（当時 / 降格 / 現行は / oracle であって / 捨てる / 不適切）が
  既に窓にあれば、この finding は既に終わっている。次の候補に行かず、
  classifier の誤検知として報告して終わる。
- CLAUDE.md / AGENTS.md / always-rule が現行として読めるなら、後継の言い方に
  差し替え、古い言い方を『不適切』と名指しする。

EDN は Write / StrReplace で書く。shell heredoc の `\"` で書かない。

### 3. 検証して着地

```bash
COM_JUNKAWASAKI_ROOT="$PWD" nbb --classpath ".:scripts/nbb_compat" scripts/rule-kaizen.cljs --self-test
COM_JUNKAWASAKI_ROOT="$PWD" nbb --classpath ".:scripts/nbb_compat" scripts/rule-kaizen.cljs --edn
```

直したファイルが `:next` に再掲されないこと。self-test が緑であること。
新しい ADR を書いたら `cljs.reader/read-string` が通ること。

feature branch を push し `gh api repos/com-junkawasaki/root/merges` で
サーバ側マージ。rebase / force-push しない。`.cursor/rules` は gitignore なので
`git add -f`。

### 4. 後始末

自分が切った worktree と branch だけ消す。共有 checkout の dirty は触らない。

## 完了条件

- tick の先頭 1 件が、次周で同じ (path, kind) として出ない。
- 古い決定が『当時』として残り、現行が 1 回で読める。
- ledger にこの周の finding を残さない（tick が測る）。報告に path と kind と
  着地 commit を書く。

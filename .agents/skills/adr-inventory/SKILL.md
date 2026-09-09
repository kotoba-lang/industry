---
name: adr-inventory
description: 90-docs/adr の古い・不適切な ADR を 1 件だけ棚卸しして着地させる。1 反復 = 1 finding。ローカル Codex loop（com.gftd.adr-inventory）が毎日これを呼ぶが、手で `/adr-inventory` と打ってもよい。「古い adr」「adr 棚卸し」「supersede 漏れ」で発火。
---

# 古い / 不適切な ADR を 1 件棚卸しする

**正本は superproject ADR-2608161700。** この skill はその 1 反復ぶんの手順書で、
**会話履歴を一切持たない fresh context から読める**ように書いてある。前の反復が
何をしたかは会話ではなく **tick の出力と ledger** から読む。

## 何をする反復か

ADR は最新状態のみを表す（ADR-2607257000）。古い決定はファイルを消さず、
status を `superseded` にするか、本文をその場で直す。この反復の仕事はちょうど 1 つ:

> **tick が先頭に出した finding 1 件を、種類に応じた最小の訂正で着地させる。**

1 反復 = 1 finding。2 件まとめない。**部分無効を全置換と取り違えると、生きている
決定が静かに死ぬ**（実測対象: ADR-2607173000 の nbb-only は、2607257000 が
ledger 半分だけを無効化したあとも生きている）。

## やらないこと

- **ファイルを削除しない。** 不適切でも残す（ADR-2607202500 の先例）。
- **決定本文を黙って消さない。** status 遷移か、いつ・なぜの 1〜2 文。
- **stamp が衝突している参照を推測して結び付けない。** tick が
  `:unresolved-supersede-ref` と出したものを、番号だけで宛先にしない。
- **keyword-status をこの loop で直さない。** schema 癖であって棚卸しではない。
  `--hygiene` の別仕事。
- **読めない EDN を直さない。** それは `docs-edn-repair`。
- **判断がつかなければ直さない。** 飛ばして次の候補に移るのではなく、何が
  読み取れなかったかを報告して終わる。次周の tick が同じ候補を出す。

## 手順

### 0. 現在地を測る（推測しない）

```bash
cd ~/github/com-junkawasaki
git fetch origin
nbb --classpath ".:scripts/nbb_compat" scripts/adr-inventory-tick.cljs
```

`:outcome :insufficient-scan` が出たら **何もせず終わる**。sparse checkout から
「もう無い」を言うと嘘になる。

先頭 1 件 (`next`) を取る。`~/.itonami/adr-inventory-tick.ledger.edn` の末尾も読む。

### 1. worktree を切る（共有 checkout を触らない）

**superproject の外**に切る。分岐元は `origin/main` を明示する。

```bash
W=/tmp/root-adr-inv-$(date +%s)
git worktree add --no-checkout -b agent/adr-inv-<短い名前> "$W" origin/main
cd "$W"
git sparse-checkout set --no-cone /90-docs /scripts /manifest /nbb.edn /CLAUDE.md /AGENTS.md
git checkout -q
ln -sfn ~/github/com-junkawasaki/node_modules "$W/node_modules"
```

### 2. kind に応じて 1 件だけ直す

対象ファイルと、tick が出した successor / ref を **両方読む**。片方だけ読んで
決めない。

#### `:successor-unmarked`

後続 ADR が `:adr/supersedes` にこの文書を書いている。**後続が 2 件以上なら
全部読む。** 1 件だけ読んで pointer を埋めない（実測: ADR-2606241600 の後続は
2607211500（heavy 例外つき）と 2607211600（例外ごと撤回）の 2 件）。

1. 各後続の本文を読む。次のどれかがあれば **部分無効**:
   - 「半分」「のみ無効」「ledger 部分」「decision item N のみ」
   - 後続が自分で「残りの決定は生きている」と書いている
2. **部分無効**なら、旧 ADR の status は触らない。本文先頭に 1〜2 文と
   `:adr/partially-superseded-by "<後続の adr/id>"` を足す。後続が複数で
   部分の範囲が食い違うなら、直さず報告して終わる。classifier は、その
   pointer が後続を覆っていれば `:successor-unmarked` を再掲しない。
3. **全置換**なら、`:adr/status` を `"superseded"` にし、
   `:adr/superseded-by` に **最終の後継 1 件**（後から出た、決定全体を
   置き換えた方）を書き、本文の Status 行を合わせ、「いつ・どの ADR が
   置き換えたか」を 1〜2 文残す。古い決定の記述は消さない。途中の後続
   （例: 2607211500）は、この反復では触らない。
4. 後続同士が食い違って最終の後継が決まらないなら、直さず報告して終わる。

#### `:superseded-without-pointer`

status は既に superseded。pointer が空で、後続がグラフから 1 件に決まっている。
その 1 件を `:adr/superseded-by` に書く。別の後続が見えたら **書かない**。

#### `:superseded-ambiguous` / `:unresolved-supersede-ref` / `:orphan-successor`

推測して埋めない。後続側の `:adr/supersedes` を **slug または path の exact 参照**
に直せるときだけ直す（stamp 裸参照を一意なファイル名に）。直せる自信が無ければ
報告して終わる。

#### `:id-collision`

同じ `:adr/id` 文字列を 2 ファイルが名乗っている。削除しない。中身が違うなら
片方の id にファイル名 slug を付ける。中身が同じなら、残す方を報告し、
この反復では id だけずらす。

#### `:status-body-mismatch`

`:adr/status` が query 面。本文に後からの supersede 記録があるなら **属性を本文に
合わせる**。本文の Status 行が古いだけなら **本文を属性に合わせる**。両方とも
古い可能性があれば報告して終わる。

#### `:ledger-ops-instruction`

「以後は adr-ledger に append」を現行手順として残している。その文を
ADR-2607257000（その場で書き換え、履歴は git）へのポインタに差し替える。
経緯の記述は残す。

#### `:cited-superseded`

CLAUDE.md / AGENTS.md。引用が **現行方針**として読めるときだけ、後継 ADR に
付け替える。『X が Y を reverse した』という経緯の引用は触らない。

### 3. 検証する

3 つとも通ること。1 つでも落ちたら着地させない。

```bash
# (a) 対象ファイルが読める
nbb -e '(println (count (keys (first (filter map? (cljs.reader/read-string (.readFileSync (js/require "node:fs") "<対象>" "utf8")))))))'

# (b) diff が当該 1 ファイル・当該 finding の範囲だけ
git diff --stat
git diff -U0 | head -80

# (c) 同じ finding が先頭から消える（残件数は減らなくてよい。別 kind が残る）
COM_JUNKAWASAKI_ROOT="$PWD" nbb --classpath ".:scripts/nbb_compat" scripts/adr-inventory.cljs
```

(c) で **同じ path の同じ kind がまだ先頭**なら、直っていない。着地させない。

### 4. 着地させる

`manifest/west.yml` に触れていない。session branch に commit して push し、
サーバ側 merge（`gh api repos/com-junkawasaki/root/merges`）で main に載せる。
rebase / force-push はしない。

着地後、共有 checkout は触らない。

### 5. 後片付け

worktree を撤去し、merge 済み branch を消す。

## 成否

この反復が自分で green と言ってはいけない。次周の tick が測る。
`~/.itonami/adr-inventory-tick.ledger.edn` の `remaining` が減っていること、
かつ同じ `:path` + `:kind` が `next` に出ていないこと。

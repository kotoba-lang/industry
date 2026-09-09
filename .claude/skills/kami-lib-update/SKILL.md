---
name: kami-lib-update
description: kami-engine family + core render/game lib（webgpu / webgl / host / scene2d / sprite2d / render / game / dance / bonsai）の west pin を upstream default branch tip へ前進させ、遅れた checkout を同期する。1 反復 = tick が見つけた遅れ pin の前進（batch 可）+ 床割れ修正は最大 1 件。ローカル Claude loop（com.gftd.kami-lib-update）が遅れのある周だけこれを呼ぶが、手で `/kami-lib-update` と打ってもよい。「kami の pin を進める」「lib 更新 bot」「kami lib update」「ライブラリ更新 loop」で発火。
---

# kami family の pin を tip へ進める

**この skill は会話履歴を一切持たない fresh context から読める**ように書いてある。
前の反復が何をしたかは会話ではなく **tick の出力・`~/.itonami/kami-lib-update/ledger.edn`・
cursor（`~/.itonami/kami-lib-update/cursor.edn`）** から読む。

根拠: CLAUDE.md 2026-08-20 オーナー規則「**pin の既定状態は upstream default branch の
tip**」。pin が遅れているのは平常ではなく是正対象なので、**batch で前進させてよい**
（1 反復 1 件の制約はここには当たらない — pin 前進は workspace の通常の鮮度義務）。

## この反復の仕事

### 1. tick の答えを読む

```bash
nbb scripts/kami-lib-update-tick.cljs     # 最終行に EDN
```

- `:outcome :candidate` — `:lagging` の repo 列（`:entry` / `:org` / `:ahead-by`）が対象。
- `:outcome :no-candidates` — 何もしない。**`:not-measured` 列が非 0 なら、それは
  fresh ではなく未測定**（tick がそう数えている）。次周の cursor が続きを見る。
- `:outcome :not-measured` — 候補 0 件ではない。rate limit 等。この周は終わる。

### 2. pin を前進させる（唯一の正経路）

**`manifest/west.yml` を手で編集しない。経路は 2 つだけ:**

```bash
# 1 件ずつ（サーバ側検証つき: default branch 到達性 / forward-only / blob precondition）
nbb --classpath ".:scripts/nbb_compat" scripts/west-pin-put.cljs <entry> HEAD

# 多件を 1 commit に束ねる（TSV: <entry> <tip-sha> <slug>）
PINS=<tsv> nbb --classpath ".:scripts/nbb_compat" scripts/west-pin-put-batch.cljs
```

`HEAD` は「上流 default branch の先端」の意味で、script がサーバ側で解決する。

- **REJECTED をねじ込まない。** サーバ側検証（到達不能 / 退行 / diverged / 409）に
  弾かれた entry は、**理由を ledger（`~/.itonami/kami-lib-update/ledger.edn` に追記）に
  書いて置いていく**。force しない、検証を飛ばす別経路を作らない。
  未 merge branch 上の commit を pin にしない（CLAUDE.md、実測事例あり）。
- 終わったら検証: `nbb scripts/verify-west-pins.cljs`

### 3. local checkout を pin に合わせる

```bash
# 引数なしの west update は 4,100+ project を歩くので絶対にしない。
# zsh は単語分割しないので $NAMES 直渡しも不可 — xargs 必須（CLAUDE.md）。
printf '%s\n' <entry1> <entry2> ... | xargs west update --fetch smart
```

### 4.（第二の任務・最大 1 件）kami family の床割れを 1 つ塞ぐ

pin が全部進んだ後に余力があれば、`~/.itonami/repo-bots/state.edn` を読み、
kami family の repo で `:readme` または `:test-signal` が `:broken` のものを
**1 件だけ**直す。直し方・着地のさせ方は skill `repo-bot-drain` の該当節と同じ:

- 共有 checkout を触らない。子リポに worktree
  （`git -C orgs/kotoba-lang/<name> worktree add -b agent/kami-lib-<floor>
  /tmp/klu-<name> origin/main`）を切って作業する
- 着地は push → `gh api repos/kotoba-lang/<name>/merges` のサーバ側マージ
- README は**中身を読んでから**書く。空 repo に README だけ書かない
- test-signal は最小の実テスト 1 本。空の test dir で床を塞がない
- 着地したらその子リポの pin も進める（修正 → pin 前進 → verify までが 1 組）

state.edn が無い / 読めないなら、この任務は **:not-measured** としてスキップする
（「床割れ 0 件」とは書かない）。

## 絶対にやらないこと

- **無人での履歴書き換え。** rebase / force-push / 他者ブランチへの push は一切しない
- west.yml の手編集・wholesale 再生成 commit（ADR に実事故 `90852b86` あり）
- REJECTED な pin の強行、検証を省いた pin 書き込み
- 引数なしの `west update`
- 測れなかった repo（rate limit / API error）を「fresh」「遅れ無し」と報告すること

## 終わり方

1 行で報告する: 何 entry の pin をどこからどこへ進めたか / REJECTED は何件・なぜ /
checkout 同期した名前 / （やったなら）どの床を塞いだか。**成否は次周の tick が測る**
— cursor が一周して同じ entry が再び lagging に出ないことが着地の証拠。

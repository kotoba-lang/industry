---
name: spotwork-match
description: スキマバイト（単発シフト）の求人を 1 件だけ governed に審査して提案台帳へ着地させる。規則側に欠陥が見つかった周はそれを 1 件直す。1 反復 = 1 件。ローカル Claude loop（cloud.itonami.bot.spotwork-match）が候補のある周だけこれを呼ぶが、手で `/spotwork-match` と打ってもよい。「スキマバイト」「単発シフト」「タイミー」「求人を審査」「spotwork」で発火。
---

# 単発シフトを 1 件だけ審査して着地させる

**この skill は会話履歴を一切持たない fresh context から読める**ように書いてある。
前の反復が何をしたかは会話ではなく **tick の出力・`80-data/spotwork/proposals.ledger.edn`・
`~/.itonami/spotwork-match/ledger.edn`・git log** から読む。

- 実装: `70-tools/spotwork/`（pure `.cljc`）
- policy: `manifest/spotwork.edn`
- 設計の正本: `90-docs/adr/2608292100-cloud-itonami-spotwork-matching-governed-bots.edn`

## この反復の仕事はちょうど 1 つ

```bash
nbb scripts/spotwork-match-tick.cljs     # 最終行に EDN で候補が出る
```

- `:outcome :candidate :kind :catalog-defect` — 規則カタログが壊れている
  （根拠の無い block 規則、宣言と検査の 1 対 1 が崩れた）。**それを直す。**
  提案は作らない。
- `:outcome :candidate :kind :unreviewed-offer` — 台帳に 1 行も無い求人。
  下記の手順で提案を 1 行積む。**1 件だけ**（`:all-unreviewed` に他が並んでいても）。
- `:outcome :candidate :kind :stale-proposal` — 求人の内容が最後の提案から
  変わっている（賃金・時間が書き換わった）。同じ手順で**再審査**して 1 行積む。
- `:outcome :no-candidates` — 何もしない。無い仕事を作らない。
- `:outcome :not-measured` — **候補 0 件ではない。** 測れなかった理由
  （`:input` が何だったか）を報告してこの周は終わる。入力を推測で埋めない。

## 提案は手で書かない

**提案 1 行を自分で組み立てないこと。** 生成は決定論の emitter が持つ:

```bash
nbb scripts/spotwork-match-tick.cljs --emit <offer-id>   # 1 行の EDN が出る
```

この行をそのまま `80-data/spotwork/proposals.ledger.edn` の末尾に**追記**する
（append-only。既存行を書き換えない・削除しない）。

モデルの仕事は行を書くことではなく、**出てきた verdict を読んで次の 1 手を決める**こと:

| verdict | 読み方 | この周の 1 手 |
|---|---|---|
| `:pass` | 全規則を通った | 追記して終わり |
| `:block` | 法令に反する求人 | 追記し、**どの条文で止めたか**を報告に書く。求人を書き換えて通さない |
| `:hold` | 測れていない申告がある | 追記し、**何の申告が欠けているか**を報告に書く |

**`:block` を消すために fixture を書き換えない。** 止まっていることが正しい
求人（`of-fixture-0003-night-fee` は 4 つの違反を同時に持つ否定対照）を通すと、
governor が discriminate していることを示す唯一の証拠が消える。

## 規則の欠陥だと判断したときだけ、規則を直す

`:block` / `:hold` を見て「これは求人の問題ではなく規則の問題だ」と判断した場合のみ、
`70-tools/spotwork/src/spotwork/` を直す。そのとき守ること:

- **条文を引けない規則を足さない。** `spotwork.facts` の `:rule/basis` には
  法令名・公布番号・条番号を入れる。**URL を記憶から書かない**
  （`:basis/url-status :not-recorded` のままにして、オペレータが
  `with-basis-urls` で入れる）。
- **閾値は条文に書かれた数値だけ。** 告示で毎年動く数値（地域別最低賃金額）は
  `spotwork.facts` に置かない —— オペレータ維持データとして外から受ける。
- **規則を足したら検査も足す。** `spotwork.governor/checks` に対応する行が
  無いと `assert-coverage` が落ちる（落ちるのが正しい）。
- **負テストは理由の literal を pin する。** 「拒否された」だけを assert しない。
  1 箇所だけ壊した fixture を作り、報告された `:rule/id` が壊した箇所と
  一致することを見る。

## 床（floor）は直そうとしない

tick の `:floors` には bot が直せないものが並ぶ:

- `:operator-data-absent` — 今年度の地域別最低賃金の告示額は**運用者が一次資料から
  転記する**もの。**推測で `operator.edn` を作らない。** これが無い限り、どの提案も
  `:pass` にはならず `:hold` で止まる。それが正しい挙動であって、直すべきバグではない。
- `:demand-is-fixtures-only` — 単発シフトの公開 feed は存在しない。並んでいる
  求人を実需要として報告しない。

床を「直した」ことにするために値を捏造しない。報告に残して次へ行く。

## 検証

```bash
nbb 70-tools/spotwork/run-tests.cljs   # exit 0 / 1 / 2（2 = 本数が床を割った）
nbb scripts/spotwork-match-tick.cljs   # exit 0 / 2
```

規則を触った周は、**壊して落ちることを実際に見る**。1 箇所壊した状態で
`run-tests.cljs` を回し、**壊した箇所に対応するテストが**落ちることを確認してから
戻す（別のテストが落ちたなら、それは実演になっていない）。

## 記録

- 判断を伴ったら ADR を 1 本、`.edn` tx-data で書く（**heredoc で書かない** ——
  `\"` がファイル上で壊れる。Write tool を使う）。`:adr/id` は
  `adr-<番号>-<slug>` の形。
- **捏造しない。** 測っていない値を書かない。`:hold` を `:pass` と報告しない。
- 共有 checkout を直接触らない運用のときは worktree を切る（CLAUDE.md の
  並行エージェント節）。この skill が触るのは root repo だけなので、
  `orgs/` 配下には触れない。

## 着地

1. `nbb 70-tools/spotwork/run-tests.cljs` が exit 0
2. `nbb scripts/spotwork-match-tick.cljs` が exit 0（次周の候補が 1 つ減っている）
3. commit → push → PR（root の既定 branch 運用に従う）

---
name: industry-stack-wave
description: cloud-itonami flagship item2（REAL actor build-time demo）を missing-render isic に対して最大 24 本並列で進める。1 反復 = 1 wave。ローカル Claude loop（com.gftd.industry-stack-wave）が毎周これを呼ぶが、手で `/industry-stack-wave` と打ってもよい。「flagship wave」「industry stack wave」「render_html を並列で」で発火。
---

# industry-stack wave — flagship demos を並列で進める

**正本は superproject ADR-2608090800。** この skill は 1 wave ぶんの手順書で、
**会話履歴を一切持たない fresh context から読める**ように書いてある。前 wave が
何をしたかは会話ではなく **tick ledger と industry-stack-ledger** から読む。

## 何をする反復か

> **tick が名指しした最大 24 本の isic に REAL actor build-time `render_html` を付け、
> main に merge し、west pin を進め、ledger に 1 行書く。**

1 反復 = 1 wave（最大 24 repo 並列）。1 repo に 1 agent。mock HTML 禁止。

## 手順

### 0. 測る（推測しない）

```bash
cd ~/github/com-junkawasaki
git fetch origin
# 共有 checkout は書き換えない。分岐していないかだけ見る
git merge-base --is-ancestor HEAD origin/main
nbb --classpath ".:scripts/nbb_compat" scripts/industry-stack-wave-tick.cljs --limit 24
```

tick の ledger 最終行 `~/.gftd/industry-stack-wave-tick.ledger.edn` の
`:candidates` が対象。候補 0 なら**何もせず終える**。

正本 ledger: `90-docs/business/industry-stack-ledger.edn` の末尾も読む
（Wave N の resume point）。

### 1. 候補ごとに 1 agent（並列）

各 agent の仕事:

1. isolated worktree / branch `agent/flagship-item2-<code>` from **origin/main**
2. `src/<domain>/render_html.clj` が **無い、または手書き stub** なら REAL 実装
   - 参照: `cloud-itonami-isic-9522` の `applianceshop/render_html.clj`
   - 実 `operation` → `governor` → `store`（langgraph があれば `g/run*`）
   - シナリオに **HARD hold ≥1**
   - `docs/samples/operator-console.html` 生成
   - `:render-html` alias + 必要なら jp-go-dds
3. `main` に既に REAL があれば **検証のみ**で `:outcome :skipped-already-real`
4. **push only**（merge / force-push / 新規 Actions 禁止）

### 2. orchestrator が着地（直列）

```bash
# 各 repo: server-side merge
gh api repos/cloud-itonami/<repo>/merges \
  -f base=main -f head=agent/flagship-item2-<code> \
  -f commit_message="flagship item2 demo (Wave N)"

# west pins（entry 群だけ）
# list-file に repo name を並べて:
nbb scripts/advance-pins.cljs cloud-itonami /tmp/waveN-pins.txt --execute
nbb scripts/verify-west-pins.cljs --only <comma-names>
```

superproject で pin + ledger を branch に載せ、server-side merge で main へ。

### 3. done 集合と ledger

merge した repo 名を `~/.gftd/industry-stack-wave-done.edn` に conj（次周 tick が再ピックしない）:

```bash
# 例: 既存 set に追加して書き戻す
nbb -e '(require (quote [clojure.edn :as edn])) ...'
```

または EDN set を手で更新。**忘れても skill が origin/main で skip するが、slot が無駄になる。**

`~/.gftd/industry-stack-wave.ledger.edn` と
`90-docs/business/industry-stack-ledger.edn` に 1 行:

```clojure
{:event/type :industry-stack/wave :event/wave N :event/parallel 24
 :event/success N :event/fail 0 :event/merged M
 :event/skipped-already-main K :event/not-done "..."}
```

**自己申告 green を信じない。** 次周 tick の `:pool` 減少で測る。

## ガードレール

- 1 agent = 1 repo。10 本まとめない
- mock HTML 禁止（ADR-2607122300 §1）
- 新規 `.github/workflows` 禁止（fleet-ci）
- rebase / force-push 禁止
- 共有 `orgs/` 直編集禁止（worktree）
- 失敗率 >20% なら次 wave の fan-out を半減
- 候補 0 なら終了（作らない）

## やらないこと

- Stripe item7 一括
- 既に REAL render がある repo の書き直し（検証だけ）
- OS 接続（別 skill `itonami-os-connect`）
- craft 新規（wave の主務は item2。craft は明示候補があるときだけ）

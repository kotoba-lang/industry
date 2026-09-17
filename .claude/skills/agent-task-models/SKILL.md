---
name: agent-task-models
description: Hermes 常駐 profile / Claude loop の task 分類ごとに、候補 model（nex-n2.5-mini / qwen3.8-27b / qwen3.8-flash-next 等、manifest/agent-task-models.edn の名簿）を同一 task 集合で測った結果と今の routing のずれを 1 件だけ着地させる。1 反復 = 1 finding。ローカル Claude loop（cloud.itonami.bot.agent-task-models）が 6 時間ごとに測り、finding がある周だけこれを呼ぶが、手で `/agent-task-models` と打ってもよい。「どの model がどの task に向くか」「hermes の model を最適化」「model routing の監視」「nex と qwen の比較」で発火。
---

# task 分類 × 候補 model の routing を 1 finding ぶん着地させる

**正本は `manifest/agent-task-models.edn` と ADR-2609172000。** この skill はその 1 反復ぶんの
手順書で、**会話履歴を一切持たない fresh context から読める**ように書いてある。前の反復が
何をしたかは会話ではなく **tick の出力と ledger** から読む。

## 何をする反復か

> **tick が先頭に出した finding 1 件（`:routing-drift` か `:model-unavailable`）を、
> manifest の `:apply` の値に従って着地させる。**

1 反復 = 1 finding。2 件まとめない。**測っていない分類について何も言わない。**

- `:routing-drift` — ある分類で、床（`:selection`）を満たした勝者が今の routing と違う。
- `:model-unavailable` — ある候補が 2 周以上、全 task で `:error`（答えていない）。順位以前。

## やらないこと

- **model id を呼び出し側のコードにハードコードしない**（ADR-2607173100）。書き換えてよいのは
  manifest の `:recommendations`、hermes profile の `model: default:`（`:apply/hermes :auto` のとき
  だけ）、ADR の gap。`ao_organism.js` の表と Claude loop の起動文字列は触らない。
- **1 周の数字で決めない。** `:min-ticks-per-cell` 未満は「まだ測っていない」。
- **`:truncated` / `:error` を `:fail` と混ぜない。** 前者は答えていない。reasoning-first の model
  （qwen3.8-27b）は `max_tokens` が小さいと content が空で返る —— それは model の欠陥ではなく
  cap（ADR-2608313500 の実測）。cap を上げて測り直すのは manifest の `:budget` を直す仕事。
- **窓超えの `:error` を model の欠陥と読まない。** qwen3.8-27b の 8192 は K16 host の設定。
- **共有 checkout を書き換えない。** worktree。rebase / force-push しない。
- **判断がつかなければ直さない。** 何が読み取れなかったかを報告して終わる。

## 手順

### 0. 現在地を測る（推測しない）

```bash
cd ~/github/com-junkawasaki
git fetch origin
kbb --backend sci --classpath scripts/model-eval scripts/agent-task-models-tick.cljk --report-only
```

`--report-only` は測らず、7 日分の ledger の集計（cells）と今の routing（hermes 273 profile の
`default`、Claude loop の `--model` の有無）を出す。最後の `TICK<TAB>` 行の `:next` が今回の finding。
`~/.itonami/agent-task-models-tick.ledger.edn` の該当 cell の row（`:err` 本文まで）を読む。

`:outcome :insufficient-scan` なら **何もせず終わる**。

### 1. worktree を切る（共有 checkout を触らない）

**superproject の外**に切る。分岐元は `origin/main` を明示する。

```bash
W=/tmp/root-atm-$(date +%s)
git worktree add --no-checkout -b agent/atm-<短い名前> "$W" origin/main
cd "$W"
git sparse-checkout set --no-cone /90-docs/adr /scripts /manifest /nbb.edn /CLAUDE.md /AGENTS.md /.claude/skills
git checkout -q
ln -sfn ~/github/com-junkawasaki/node_modules "$W/node_modules"
```

### 2. finding を 1 件だけ着地させる

**`:routing-drift`**
1. `manifest/agent-task-models.edn` の `:recommendations` に分類 → `{:model :evidence :at}` を書く。
   `:evidence` は tick の cell（`:ticks` `:answered` `:pass-rate` `:wall-median`）をそのまま写す。
2. `:apply/hermes` が `:propose`（既定）なら、ADR-2609172000 の `:adr/gaps` に
   「分類 X: 現行 A → 提案 B、根拠 cell」を 1 行足して終わり。**profile は書き換えない。**
3. `:apply/hermes` が `:auto` のときだけ、その分類に当たる profile（`:profile-classes` の
   先頭一致）の `~/.hermes/profiles/<p>/config.yaml` の `model:` 節 `default:` を勝者に書き換える。
   書き換える前に `cp config.yaml config.yaml.bak-atm-<date>`。書き換えた profile 名と数を ADR に書く。
   `:agent-general`（当たらない profile）は root の `default` に従うので触らない。

**`:model-unavailable`**
1. `:err` の literal を読む。`origin-not-allowed` / 401 は token の話、`context_length_exceeded` は
   窓の話、curl の timeout は host の話 —— 分類ごとに直し先が違う（token: `secrets-location-map`、
   窓: manifest の `:window`、host: ADR-2609161500 / 2609151900 の gap）。
2. この skill が直すのは manifest 側だけ（`:note` に測った事実と日付、必要なら `:window`）。
   host / token は ADR の gap に書いて owner に渡す。

EDN は Write / StrReplace で書く。shell heredoc の `\"` で書かない。**書いたら reader で読み直す**:

```bash
kbb --backend sci -e '(let [fs (js/require "node:fs") m (clojure.edn/read-string (str (.readFileSync fs "manifest/agent-task-models.edn" "utf8")))] (println (count (:models m)) (keys (:recommendations m))))'
```

### 3. 検証して着地

```bash
COM_JUNKAWASAKI_ROOT="$PWD" kbb --backend sci --classpath scripts/model-eval scripts/agent-task-models-tick.cljk --report-only
kbb --backend sci scripts/verify-adr-identity.cljk 2>&1 | tail -3
kbb --backend sci scripts/gen-agents-md.cljk --check
```

`git add` → commit（message に finding の種類・分類・現行→提案・根拠 cell）→ `git push -u origin` →
`gh pr create` → `gh api repos/junkawasaki/root/merges` でサーバ側マージ → worktree を retire
（`kbb --backend sci scripts/worktree-retire.cljk`）。

### 4. 報告

着地したもの（PR 番号・分類・現行→提案・cell）と、着地しなかったもの（理由の literal）を分けて書く。
「測れなかった」を「差が無かった」と書かない。

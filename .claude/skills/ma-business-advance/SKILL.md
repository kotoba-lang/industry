---
name: ma-business-advance
description: M&A マッチング事業（cloud-itonami/ma + 構成 5 repo）の割れた床を 1 つだけ塞いで着地させる。1 反復 = 床 1 つ。ローカル Claude loop（com.gftd.ma-business-advance）が毎日これを呼ぶが、手で `/ma-business-advance` と打ってもよい。「M&A 事業を進める」「マッチング事業」「ma business」「M&A クラウドのような」で発火。
---

# M&A マッチング事業を 1 床ぶん進める

**正本は superproject ADR-2800005400。** この skill はその 1 反復ぶんの手順書で、
**会話履歴を一切持たない fresh context から読める**ように書いてある。前の反復が何を
したかは会話ではなく **tick の出力と ledger** から読む。

## 何をする反復か

M&A マッチングは 6 repo にまたがるので、repo 単位の bot（`repo-bots` /
`itonami-os-connect`）の視野に「事業」として入らない。この反復の仕事はちょうど 1 つ:

> **`manifest/ma-business.edn` が定義する 5 つの床のうち、tick が名指しした
> 最初の 1 つを塞ぎ、main に着地させる。**

1 反復 = 床 1 つ。2 つまとめない（ADR-2607189300 のガードレール: 大きいバッチと
自己申告の green が 61% 欠陥の fan-out を起こした）。

## 手順

### 0. 測る（推測しない）

```bash
cd ~/github/com-junkawasaki
git fetch origin && git merge --ff-only origin/main
nbb --classpath ".:scripts/nbb_compat" scripts/ma-business-tick.cljs
```

- **exit 2 が返ったら何もしない。** 構成 repo の checkout が 0 本という意味なので、
  次の 1 手は `west update` であって事業を進めることではない。tick が名指しする。
- `NEXT` 行の床が今回の対象。**それ以外の床に手を出さない。**
- `~/.itonami/ma-business-tick.ledger.edn` の末尾数行も読む（前周との差分が現在地）。
- **`:unmeasured` は対象にしない。** 測れていないものを直しに行くと、直したつもりで
  別のものを壊す。測れないこと自体が報告対象で、作業対象ではない。

### 1. 床ごとの塞ぎ方

| 床 | どこを直すか | 着地先 |
|---|---|---|
| `stage-owner` | `manifest/ma-business.edn` の `:stage/owner nil` | **root**（子 repo 不要） |
| `checkout` | `west update --fetch smart <name>` | 着地物なし |
| `matching-runtime` | Matching stage の owner repo に標準形 `src/` を書く | 子 repo |
| `standard-form` | 名指しされた repo に `phase.cljc` / `operation.cljc` を足す | 子 repo |
| `os-declared` | `network-awai/cloud-itonami` の `os.edn` と `os/adapters/*` | 子 repo |

#### `stage-owner` — owner の無い stage を埋める

**実在する repo を、README と `src/` を実際に読んでから**割り当てる。
`cloud-itonami/ma` の README の actor 表を根拠にしない —— あの表は ISIC 6619 を
「M&A execution support」と書いているが、実在する `cloud-itonami-isic-6619` は
カード決済処理である。**名前の一致は主題の一致ではない。**

その営みを持つ repo が fleet に無いなら、選べるのは 2 つだけ:

- `nbb scripts/repo-search.cljs <語>` と `nbb scripts/concept-lookup.cljs <語>` を
  引いてから、実在する近接 repo を割り当てる
- その stage をこの事業のスコープ外と決め、`:business/stages` から外す。**その場合は
  外した理由を構成表のコメントに書く**（黙って消すと、次の反復が同じ stage を
  足し直す）

**新しい vertical repo をこの反復で起こさない。** それは `new-project-scaffold` と
`itonami-os-connect` の仕事で、1 反復 1 床の範囲を超える。

#### `matching-runtime` — ここがこの事業の本体

M&A クラウドが売っているのは「買い手と売り手の突き合わせ」そのものであって、
周辺の助言ではない。この床が赤い限り、この事業は設計スケッチのままである。

実装は skill `build-actor` の型に揃える（Advisor ⊣ 独立 Governor、
langgraph-clj StateGraph、append-only 監査台帳、注入境界）。標準形は
`scripts/itonami-os-maturity-tick.cljs` の `conformance` が定義する:

```
src/<単一 ns>/phase.cljc      (def read-ops #{...}) (def write-ops #{...}) (def default-phase ...)
src/<単一 ns>/operation.cljc  (defn build ...) + langgraph.graph
src/<単一 ns>/store.cljc      (defn seed-db ...)
src/<単一 ns>/governor.cljc   在ること
                              render_html.clj 以外の .clj を置かない（JVM 専用は不可）
```

HARD 不変条件（governor が常に持ち、上書き不可）を最低 1 つは書く。マッチングなら
たとえば **提案は `:propose` のみで actuation しない**、**未検証の相手方を
shortlist に入れない**、**売り手の非公開情報を買い手側の提案に混ぜない**。

#### `standard-form` — 実装済み actor を OS から回せるようにする

対象は 2 ファイル形（`actor.cljc` + `advisor.cljc`）で止まっている repo。
**既存の `actor.cljc` を消さない。** `phase.cljc` と `operation.cljc` を足して、
`operation/build` が既存 actor を呼ぶ形にする。`store.cljc` に `seed-db` が
無ければ足す。

#### `os-declared` — 営み OS に宣言する

手順の正本は skill `itonami-os-connect`。**宣言は `origin/main` の `os.edn` を
基準に読む**（working tree は共有 checkout なので他セッションの WIP で汚れている）。

`ma` は `itonami-os-maturity-tick` の候補プールに構造的に入らない
（`candidate-family-re` が `^cloud-itonami-(isic|isco|cofog|jsic|nace|naics|unspsc)-`）。
**これは違反ではない。改名で直さない**（ADR-2608040100 / 2608040170）。os.edn に
手で宣言するのが正しい経路である。

### 2. 着地させる（作業場は共有 checkout の外）

```bash
# root だけで済む床（stage-owner）
git worktree add -b agent/ma-business-<床> /tmp/ma-business-<床> origin/main

# 子 repo を触る床
git worktree add -b agent/ma-<repo> /tmp/ma-<repo>/<repo> origin/main   # 子 repo 側で
```

push → `gh api repos/<org>/<repo>/merges` でサーバ側マージ → west pin 前進
（`scripts/west-pin-put.cljs <entry> HEAD`）→ worktree 撤去。
rebase も force-push もしない。手順は skill `git-cleanup-conflict` /
`west-pin-advance`。

### 3. 確かめる（着地の前に）

**その床だけが動いたことを見る。**

```bash
nbb --classpath ".:scripts/nbb_compat" scripts/ma-business-tick.cljs --no-ledger   # 直す前
# ... 直す ...
nbb --classpath ".:scripts/nbb_compat" scripts/ma-business-tick.cljs --no-ledger   # 直した後
```

対象の床が `broken` → `ok` に変わり、**他の床が動いていない**ことを確認する。
他の床まで動いていたら、1 反復 1 床を破っているか、tick が床を分離できていない。
どちらも着地前に直す。

`matching-runtime` / `standard-form` を塞いだときは、tick が緑になるだけでは足りない
—— **その repo が nbb で実際に load できること**まで見る（OS の候補プールは
`(require '[<ns>.operation])` を実際に走らせる）:

```bash
nbb --classpath "orgs/cloud-itonami/<repo>/src:<兄弟の src...>" -e "(require '[<ns>.operation])"
```

## やらないこと（ガードレール）

- **2 つの床をまとめて塞がない。** tick が 2 つ赤くても、この反復で触るのは 1 つ。
- **`cloud-itonami/ma` を改名しない。** 候補プールに入らないのは受け入れた設計。
- **actor の実装を superproject に staging しない。** `ma` repo の ADR 0001 が記録した
  欠陥は「1 つの repo が 2 つの設計を持っていた」ことで、root を 3 つ目の家にすれば
  同じ誤りを増やす。実装は必ずその repo の main に着地させる。
- **2 本目の loop / tick を作らない。** 測るのは `ma-business-tick.cljs` 1 本。
- **`ma` の README の actor 表を根拠にしない**（6619 の誤配置）。
- **push 権限が無いセッションでは着地しない。** 実測 2026-08-29: remote session から
  `cloud-itonami/*` への push は `add_repo: cross-tier adds are not supported in v1` /
  `Access denied: repository "cloud-itonami/ma" is not configured for this session` で
  拒否される。その場合は **何も書かずに、どの床が対象でなぜ着地できなかったかを
  報告して終える**。root だけで済む床（`stage-owner`）ならその session でも完結する。
- **共有 checkout（`orgs/<org>/<repo>`）で直接 commit しない。** worktree を切る。

## 完了条件

1. 対象の床が `broken` → `ok` に変わった（tick で前後を実測した）
2. 他の床が動いていない
3. main に着地した（PR merge 済み。branch 上で通ったことと main に在ることを混同しない）
4. 子 repo を触ったなら west pin を前進させた
5. worktree と local branch を撤去した

**「直した」と報告する前に 1 と 3 を実際に見る。** 成否は次周の tick が測るので、
自己申告は台帳に残らない。

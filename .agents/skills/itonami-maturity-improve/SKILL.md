---
name: itonami-maturity-improve
description: cloud-itonami fleet の成熟度スコア（ADR-2608052000 の 7 軸）を、leverage の高い repo から 1 反復 1 軸ずつ実際に上げる。水増しを構造的に禁じる gate つき。ローカル Codex loop（cloud.itonami.bot.itonami-maturity-improve）が毎周呼ぶが、手で `/itonami-maturity-improve` と打ってもよい。「成熟度を上げる」「maturity loop」「leverage の高い repo を伸ばす」で発火。
---

# 成熟度を 1 段だけ上げる

**正本は superproject ADR-2608080000**（測り方と順位は ADR-2608052000）。
この skill は 1 反復ぶんの手順書で、**会話履歴を一切持たない fresh context から
読める**ように書いてある。

## この反復の仕事はちょうど 1 つ

> **tick が名指しした repo の、tick が名指しした軸を、1 つだけ本当に上げる。**

1 反復 = 1 repo × 1 軸。まとめない。

## 最初に読むこと —— これは点数稼ぎではない

7 軸はすべて**観測量**（バイト数・ファイルの有無・経過日数）である。だから
「この軸を上げろ」は、素直にやると次のことを意味してしまう:

| 軸 | 素直にやると | それは |
|---|---|---|
| substrate | `src/**` にバイトを足す | **禁止**（tick が『狙わない軸』として除外済み） |
| fresh | 意味の無い commit を打つ | **禁止**（同上） |
| test | テストファイルを膨らませる | 水増し。**壊して赤くなる**ことを確かめて初めて仕事 |
| docs | README を長くする | 水増し。**踏める手順**にして初めて仕事 |
| ingest | それらしい URL を並べる | 捏造。**実際に取得できる**ことを確かめる |
| surface | 手書きのモックアップを置く | ADR-2607122300 §1 が名指しで禁じた形 |
| governed | 7 部品のファイルを置く | 門が閉まることを**実演**して初めて仕事 |

**スコアは仕事の影であって、仕事ではない。** 影を直接動かしたらそれは嘘に
なる。tick は `:targetable?` が false の軸を最初から候補から外しているので、
`:axis-substrate` / `:axis-fresh` を目標にしてはならない（それらは良い仕事の
副産物として上がる）。

## 手順

### 0. 測る（推測しない）

```bash
cd ~/github/com-junkawasaki
git fetch origin && git merge --ff-only origin/main
nbb --classpath ".:scripts/nbb_compat" scripts/itonami-maturity-improve-tick.cljs
```

tick が出すもの: `lane`（substrate / breadth）・対象 repo・**目標にしてよい軸**と
その伸びしろ（bp）・計測値の鮮度。`~/.itonami/itonami-maturity-improve.ledger.edn`
の末尾も読む（前周が何を狙い、`:own-before` がいくつだったか）。

**`:datoms-stale?` が true なら、この反復の仕事は「測り直し」**であって軸上げ
ではない。§5 へ飛ぶ。

### 1. 対象と軸を確定する

原則は tick の 1 位。**外してよいのは、外す理由を ledger に書けるときだけ**:

- その repo が dirty（他セッションの WIP）→ **触らない**。次の候補へ。
- `:ranking-is-flat?` が true（cohort lane でよくある）→ 順位は弱い信号なので、
  順位より「弱い軸を 1 つ確実に埋める」を優先してよい。

### 2. 作業場は superproject の外

```bash
git worktree add -b agent/maturity-<repo> /tmp/maturity-<repo> origin/main   # 対象 repo の中で
```

**superproject 本体の `orgs/` を直接編集しない**（並行セッションの WIP を壊す）。

### 3. 軸ごとの「本当に上げる」形と gate

| 軸 | やること | **必ず通す gate** |
|---|---|---|
| `axis-test` | その repo の不変条件を固定するテストを足す | `nbb scripts/maturity-loop/run.cljs --only <repo>` —— **実装を壊したときに赤くなること**を確かめる。落ちない gate は劇場 |
| `axis-ingest` | facts/catalog に**実 URL** の引用を足す | 足した URL を実際に取得して 2xx を確認。取れないものは足さない |
| `axis-docs` | `README` の名乗り / `docs/operator-quickstart.md` | quickstart の手順を**実際に踏む**。踏めない手順は書かない |
| `axis-surface` | demo 生成器 / `docs/business-model.md` | 生成器を**実際に回す**。生成物は実 actor 由来であること（手打ち禁止） |
| `axis-governed` | 不足している actor 部品 | **拒否を実演する**。拒否 0 件の生成は生成器自身が exit 1 にする |

**gate を通せない軸は、その反復では上げない。** 上げたことにしない。

`scripts/maturity-loop/` は「テストのテスト」（mutation testing）で、
`mutations.edn` に壊し方を書いて赤くなることを確かめる道具。`axis-test` を
狙う反復では**これを通すまでが仕事**。

### 4. テスト

対象 repo のテストを、**変更前に一度**通す（変更前から赤い repo は触らない ——
自分の変更の可否を判定できない）。変更後にもう一度通す。失敗集合を比べる。

### 5. 測り直しの反復（`:datoms-stale?` が true のとき）

```bash
nbb --classpath ".:scripts/nbb_compat" scripts/itonami-maturity-scan.cljs
nbb --classpath ".:scripts/nbb_compat:orgs/kotoba-lang/dynamics/src:orgs/kotoba-lang/org-oasis-open-xmile/src" \
  scripts/itonami-maturity-dynamics.cljs \
  --evidence manifest/itonami-maturity-evidence.edn \
  --taxonomy manifest/repo-taxonomy.edn \
  --out 90-docs/system-dynamics/itonami-maturity.datoms.edn
```

**パリティゲートも通す**（スコア算術の正本は Kotoba カーネル、ADR-2608052000）:

```bash
nbb --classpath ".:scripts/nbb_compat" scripts/itonami-maturity-kernel-parity.cljs
```

生成物を着地させて終わり。この周は lane を消費しない（軸を上げていないので）。

### 6. 着地（rebase も force-push もしない）

```bash
git push origin agent/maturity-<repo>
gh api repos/<org>/<repo>/merges -f base=main -f head=agent/maturity-<repo> -f commit_message="..."
```

superproject 側で pin を進めるなら **当該 entry だけ**
（`nbb scripts/gen-west-manifest.cljs --entry <name>`）。wholesale 再生成は禁止。

後片付けまでが完了条件: worktree 削除 → local branch 削除 → remote branch 削除。

### 7. ledger に 1 行足す

`~/.itonami/itonami-maturity-improve.ledger.edn`（追記のみ、1 行 1 EDN）:

```clojure
{:at "..." :outcome :landed :lane :substrate :target "orgs/kotoba-lang/langgraph"
 :axis :axis-docs :own-before 0.544 :merged "<commit>"
 :gate "quickstart を実際に踏んだ / mutation 3 件が赤くなった"
 :not-done "..." :chose-why "tick 1 位でない場合の理由"}
```

**`:outcome :landed` は、gate を通して main に入ったときだけ。** 途中で止めたら
`:partial` にして何が残ったかを書く。**上がったかどうかは自分で判定しない** ——
次周の tick が測り直して `:own-before` と突き合わせる。

## やらないこと

- **`:axis-substrate` / `:axis-fresh` を目標にしない。** tick が除外している。
- **gate を通さずに「上げた」と書かない。**
- **dirty な repo を触らない**（他セッターの未コミット WIP）。
- **変更前から赤い repo を触らない**（自分の変更の可否を判定できない）。
- 目標にできる軸が無ければ**何もせず終える**。無いのに作らない。

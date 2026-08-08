---
name: observatory-collect
description: 領域別 observatory（産業・領域ごとの観測 actor）を 1 件だけ実際に走らせて測り、manifest/observatories.edn に登録して着地させる。1 反復 = 1 actor。ルーチン（observatory-collect-hourly）が毎時これを呼ぶが、手で `/observatory-collect` と打ってもよい。「observatory を測る」「収集の続き」「未測定の actor を登録」「observatory loop」で発火。
---

# 領域別 observatory を 1 件だけ測って登録する

**この skill は会話履歴を一切持たない fresh context から読める**ように書いてある。
前の反復が何をしたかは会話ではなく **登録簿と台帳**から読む。

正本の設計は **ADR-2608081200**、登録簿は **`manifest/observatories.edn`**（手書き）、
台帳は **`90-docs/observatory/observatory.datoms.edn`**（生成物）。

## 何をする反復か

領域別の observatory は「動くと書いてあるが、誰も走らせていない」状態になりやすい。
2026-08-08 の初回実測では **22 本のうち 8 本が起動しなかった** —— 壊れていたのでは
なく、**一度も走らせていなかった**。README も MATURITY.md も manifest.edn も、
それらが動くと書いてあった。

この反復の仕事はちょうど 1 つ:

> **登録簿の `:next` の先頭 1 件を実際に走らせて、観測した値だけを登録簿に書き、
> main に着地させる。**

1 反復 = 1 actor。**2 件まとめない。** 失敗の原因は毎回別物で、まとめると
「どれがどう直ったか」が分離できなくなる。

## この仕事が機械化されていない理由（先に読む）

起動しない 8 本の原因は全部違った —— `*file*` 相対のパス解決が null になる NPE、
seed が repo に無い、bb 退役の巻き添えで deps.edn に依存が宣言されていない、
west 移行前のモノレポのパスが残っている、そもそも TypeScript でランナーが無い。
**どれも実行して初めて分かった。** 静的検査で分かるものは 1 件も無かった。

だから gate（`scripts/fleet-ci/gates/observatory-registry-check.cljs`）が検査
できるのは「登録簿が現実と整合しているか」までで、**実際に動くかは走らせないと
分からない**。その境界を曖昧にしないこと —— **gate が green でも actor が動く
証拠にはならない。**

## 手順

### 0. 現在地を読む（推測しない）

```bash
cd <superproject>
git fetch origin && git merge --ff-only origin/main
nbb --classpath ".:scripts/nbb_compat" scripts/observatory-run.cljs --check
```

`--check` は走らせずに「west.yml に登録が在るか / checkout が在るか」だけ見る。

次に登録簿の `:next` を読む。先頭 1 件がこの反復の対象。

```bash
nbb -e '(def fs (js/require "node:fs"))
        (def r (cljs.reader/read-string (.readFileSync fs "manifest/observatories.edn" "utf8")))
        (doseq [n (:next r)] (println (:target n) "|" (:needs n) "|" (:fix n)))'
```

`:needs` を見て、**この反復で着地できるものを選ぶ**:

| `:needs` | この反復でできるか |
|---|---|
| `:investigation` | **できる。** 走らせて測り、登録簿を直す |
| `:push-to-<org>` | **この superproject のセッションからは着地できない。** 対象 org への push 権が要る。判断だけ足して次へ |
| `:owner-decision` | **触らない。** owner の判断を要する（個人データの source 登録など） |

`:next` が空、または着地できる行が 1 件も無ければ、**何もせず「今周は着地なし」と
報告して終わる。** 仕事を作らない。

### 1. 対象と、その sibling 依存を clone する

**west はこのコンテナに無い**（`pip install west` は docopt のビルドで落ちる）。
`west update` を待たずに `git clone` してよい —— ただし **置き場所は
`orgs/<org>/<name>`**。`observatory-run` の `${REPO}` 展開も `repo-dir` もそこを
見るので、別の場所に置くと動かない。

```bash
git clone --quiet https://github.com/<org>/<name>.git orgs/<org>/<name>
```

`deps.edn` に `:local/root` があれば **sibling も先に clone する**。無いと
`clojure` は依存解決の段階で落ち、それを「actor が壊れている」と誤診する:

```bash
grep -o ':local/root "[^"]*"' orgs/<org>/<name>/deps.edn
```

実測済みの sibling: `kotoba-lang/langgraph` `kotoba-lang/langchain`
`kotoba-lang/ie-flow` `kotoba-lang/social-publication`
`etzhayyim/com-etzhayyim-kotoba-datom`。

### 2. 実際に走らせる（出力を読む。exit code だけを見ない）

まず素の起動で何が起きるかを見る。**落ちたら stderr をそのまま残す** ——
要約しない。次の反復が同じ所を踏み直さないための唯一の記録になる。

```bash
cd orgs/<org>/<name> && clojure -M -m <ns>.methods.autorun
```

落ちた場合、**引数で回避できるかを必ず確かめる。** 実測 4 件がこれで動いた:

- `-main` が seed / log path を位置引数や `--out` で受けているなら、**絶対パスを
  明示すれば repo に一切触らずに動く**（shionome / mitooshi / busshi / masago）
- argv がその値を受け付けていないなら回避できない —— repo 側の修正が要る
  （hakoniwa / chie）。**この差は `-main` を読まないと分からない**

`clojure -M -m` で落ちる時は、`deps.edn` の有無だけで結論しない。masago は
deps.edn を持たないが外部依存がゼロなので素の `clojure -M -m` で通る。
**deps.edn の不在は「起動経路が無い」を意味しない。**

### 3. 2 周目を走らせる（ここを飛ばすと登録を間違える）

**1 周目だけ見て `:produces-datoms` と登録しない。** append-only の台帳を持つ
actor には 2 種類ある:

- **毎周伸びる**（watari は +498 datoms/cycle）→ `:produces-datoms`
- **変化した時だけ伸びる**（inochi / rasen / busshi は 2 周目で
  `appended=false (no-change)`）→ `:produces-datoms-idempotent`

冪等なものを `:produces-datoms` として登録すると、**正常な 2 周目が毎回下振れに
なり、gate が常時赤くなる** —— 常時赤い gate は無視されるので、gate が無いのと
同じになる。逆に毎周伸びるものを idempotent にすると、伸びなくなっても気付けない。

### 4. 登録簿に書く（観測した値だけ）

`manifest/observatories.edn` の該当エントリを **その場で書き換える**（append
しない。CLAUDE.md の「文書は最新状態のみ」）。

```clojure
{:name "..." :org "..."
 :domain "この actor が何を観測しているか"
 :runtime :clojure :main "..." :args ["${REPO}/..." "--out" "${REPO}/out"]
 :produces "data/persisted/....edn"   ; 実測していないなら nil。当て推量を書かない
 :expect :produces-datoms-idempotent
 :evidence "2026-MM-DD 実測: <数字>。<何を確かめたか>"}
```

守ること:

- **`:args` のパスは `${REPO}` 起点。** 相対パスの既定値を避けるための登録簿で
  相対パスを書いたら意味が無い（gate が落とす）
- **`:known-broken` / `:runs-empty` には `:blocked-by` を必ず書く。**
  理由を書かない登録は記録ではなく黙認
- **`:evidence` には数字を入れる。** 「動いた」ではなく「380 datoms、chain
  {:ok true}、2 周目 no-change」
- **測っていないものを nil のままにする。** 「測ったが 0」と「測っていない」を
  混ぜない
- 着地したら `:next` からその行を消す。新しく分かった手当ては `:next` に足す

### 5. 台帳を再生成する

```bash
nbb --classpath ".:scripts/nbb_compat" scripts/observatory-run.cljs
```

**書き込みは常にマージで、実際に走った actor の行だけが差し替わる**（2026-08-08
に構造を変えた。ADR-2608082600）。以前は全体 run が台帳をまるごと書き換えたので、
checkout の無い環境で回すと 22 行の実測が `:absent` に化けて消えた。今は消えない
—— 各行が自分の `:observatory/as-of` を持つので、いつの観測かは行ごとに読める。

`--only <name>` は今までどおり**一切書かない**（1 件の結果を fleet 全体の観測に
化けさせないため）。確認だけならこちら。

### 5b. 頻度を触るとき（ADR-2608082600）

観測の頻度は actor ごとに **T\* = sqrt(2·cost / (importance·λ))** で計算する。
入力は登録簿の `:change-rate`（λ [1/day]）と `:importance` [sec·day]、cost は
台帳の実測。計算は:

```bash
nbb --classpath "90-docs/system-dynamics/nbb-shim:orgs/kotoba-lang/org-oasis-open-xmile/src:orgs/kotoba-lang/dynamics/src" \
  90-docs/system-dynamics/observatory-cadence.cljs
```

- **λ の 12/13 は宣言した prior であって測定値ではない。** `:change-rate-basis` を
  見ずに間隔を引用しない。実測に置き換える材料は
  `90-docs/observatory/observatory-runs.ledger.edn`（append-only、1 行 1 run）の
  `:run/changed` 列。**この列を読んで λ を prior から実測へ動かすのは、この skill の
  仕事に含まれる**（数十 run 貯まってから）。
- **新しい actor を登録したら `:change-rate` / `:change-rate-basis` /
  `:change-rate-source` / `:importance` も付ける。** gate が欠落を落とす。
  測れないものは `:uncomputable-until-measured` と書く —— **0 を入れない**
  （0 は「毎周走らせる」になり、測っていない actor ほど頻繁に叩かれる）。
- importance を細かく詰めない。**T\* ∝ 1/sqrt(w) なので 16 倍間違えても 4 倍しか
  ずれない。** 桁で足りる（現在 5 / 10 / 20 の 3 段）。

### 6. gate を通す

```bash
nbb scripts/fleet-ci/gates/observatory-registry-check.cljs .
```

`OK` になること。落ちたら**登録簿を直す** —— gate を緩めない。

### 7. 着地させる

`origin/main` から branch を切り（分岐元を明示）、変更した**ファイルだけ**を
明示 stage する。**`git add .` をしない** —— `orgs/` 配下の clone を巻き込む。

```bash
git add manifest/observatories.edn 90-docs/observatory/observatory.datoms.edn
git commit -m "observatory: <name> を実測して登録"
git push -u origin <branch>
```

PR を作り、mergeable を確認して merge する。

## やらないこと

- **走らせずに登録しない。** README / MATURITY.md / manifest.edn の主張を
  `:evidence` に書かない。この登録簿が在る理由がそれ
- **exit 0 を成功の証拠にしない。** パイプ越しの `$?` を見ない（`java … | tail`
  の `$?` は tail のもの）
- **緑にするために `:expect` を下げない。** 下げてよいのは、下げた値が実測に
  一致する時だけ
- **2 件以上まとめない。**
- **`:unmeasured` を空にしただけで「全部見た」と書かない。** 22 件は 4,148
  project から手で拾った候補であって、網羅の証明ではない（`:inventory-note`）。
  新しい候補は `nbb scripts/repo-search.cljs 観測 observatory ingest 収集` と
  `nbb scripts/concept-lookup.cljs observatory` を引いてから足す
- **`:live-alias` を持つ actor を勝手に live で走らせない。** kouhou と
  kawaraban は実 fetch / 実 publish の経路を別 alias に分けてある。無人の毎時
  run に外向きの副作用を混ぜない（`--live <name>` は人が明示した時だけ）
- **repo 側の push 権が要る修正を、権限が無いのに「やった」と報告しない。**
  `:needs :push-to-<org>` はこの superproject のセッションからは着地できない。
  分かったことを `:next` の `:fix` に具体化して次へ渡す

---
name: industry-stack-wave
description: cloud-itonami flagship item2（REAL actor build-time demo）を missing-render isic に対して最大 20 本並列で進める。1 反復 = 1 wave。ローカル Claude loop（cloud.itonami.bot.industry-stack-wave）が毎周これを呼ぶが、手で `/industry-stack-wave` と打ってもよい。「flagship wave」「industry stack wave」「render_html を並列で」で発火。
---

# industry-stack wave — flagship demos を並列で進める

**正本は superproject ADR-2608090800。** この skill は 1 wave ぶんの手順書で、
**会話履歴を一切持たない fresh context から読める**ように書いてある。前 wave が
何をしたかは会話ではなく **tick ledger と industry-stack-ledger** から読む。

## 何をする反復か

> **tick が名指しした最大 20 本の isic に REAL actor build-time `render_html` を付け、
> main に merge し、west pin を進め、ledger に 1 行書く。**

1 反復 = 1 wave（最大 20 repo 並列 = harness の同時 subagent 上限）。
1 repo に 1 agent。mock HTML 禁止。

## 手順

### 0. 測る（推測しない）

```bash
cd ~/github/com-junkawasaki
git fetch origin
# 共有 checkout は書き換えない。分岐していないかだけ見る
git merge-base --is-ancestor HEAD origin/main
nbb --classpath ".:scripts/nbb_compat" scripts/industry-stack-wave-tick.cljs --limit 20
```

tick の ledger 最終行 `~/.itonami/industry-stack-wave-tick.ledger.edn` の
`:candidates` が対象。候補 0 なら**何もせず終える**。

**`--limit` は 20 にする。harness の同時 subagent 上限が 20 で、21 本目以降は
`Concurrent subagent limit reached` で拒否される**（実測 2026-08-12 Wave 6:
24 本投げて 20 本が起動、4 本が即座に拒否された）。24 で tick を回すと、
候補として名指しされたのに誰も担当しない repo が毎周 4 本出る。上限を上げたい
場合は `CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS` をオーナーに上げてもらう。
拒否された候補は次周の tick が再び拾うので失われはしないが、**その周の
`:parallel` を 24 と記録すると台帳が嘘になる。**

正本 ledger: `90-docs/business/industry-stack-ledger.edn` の末尾も読む
（Wave N の resume point）。

### 1. 候補ごとに 1 agent（並列）

各 agent の仕事:

1. isolated worktree / branch `agent/flagship-item2-<code>` from **origin/main**
   - **worktree は `orgs/cloud-itonami/_wt<N>-<code>` のように org ディレクトリの
     兄弟に置く。`/tmp` に置かない** —— 子リポの deps.edn は
     `:local/root "../../kotoba-lang/langgraph"` という相対パスで langgraph /
     langchain を解決するので、`/tmp` 配下では依存が引けず build が落ちる。
   - **`git -C <repo> worktree add` に相対パスを渡さない。`-C` は相対パスを
     *repo ディレクトリから* 解決するので、`../cloud-itonami/_wt<N>-<code>` は
     `orgs/cloud-itonami/cloud-itonami/_wt<N>-<code>` という 1 段深い場所に落ち、
     そこでは `../../kotoba-lang/langgraph` が解決できない。**絶対パスを渡す:

     ```bash
     git -C <repo> worktree add -b agent/flagship-item2-<code> \
       "$PWD/orgs/cloud-itonami/_wt<N>-<code>" origin/main
     ```

     実測 2026-08-14（Wave 16）: 相対パス版を 20 agent 全員に配り、**20 人全員が
     踏んで 20 人全員が自力で直した**。落ちるのは build なので気付けるが、
     agent 1 人につき 1 手ぶん無駄になる。
   - **使い終わったら `git worktree remove` する。** Wave 5 までの残骸が 14 本
     `orgs/cloud-itonami/` に居座っている（tick は linked worktree を構造的に
     skip するので害は無いが、他 agent の WIP かもしれないので勝手に消さない）。
2. `src/<domain>/render_html.clj` が **無い、または手書き stub** なら REAL 実装
   - 参照: `cloud-itonami-isic-9522` の `applianceshop/render_html.clj`
   - 実 `operation` → `governor` → `store`（langgraph があれば `g/run*`）
   - シナリオに **HARD hold ≥1**
   - `docs/samples/operator-console.html` 生成
   - `:render-html` alias + 必要なら jp-go-dds
   - **`-main` は `:governor-hold` が 0 件なら書かずに throw する**
     （HARD hold 要件を規約でなく build-time の不変条件にする。isic-2513 が先例）
   - **さらに「宣言した rule のどれかが黙ったら throw」まで上げる**（Wave 16 で
     isic-9521 と isic-9499 が独立に発明し、両方とも部分カバレッジを人工的に
     起こして「欠けている rule 名がちょうど出る」ことまで確かめた）。
     `≥1 hold` だけだと、**governor が育つにつれて他の rule のカバレッジを
     静かに失っても console は緑のまま**になる。
   - **join が測れなかったときに「所見」を書かない。** 実測 Wave 16、isic-9321:
     `g/run*` は `{:state :events :status :frontier}` を返し **`:audit` は
     `:state` の下**にあるので、top level を読んだ join が nil を返し、
     ページに `auto-committed (no approver)` ——「所見に見えるが実は
     *測れていない*」という文——が出た。record 自身から approver を読む行は
     正しく出ていたので**気付けない**。ADR-2608136000 の class そのもの。
     対策は **evidence floor**: そのシナリオが人手で N 件承認しているなら
     `:approval-granted` が 0 件で build を落とす。isic-9523 / isic-9492 も
     同じ根に独立に当たった（approver 節が 0 行になった）。
3. `main` に既に REAL があれば **検証のみ**で `:outcome :skipped-already-real`
4. **push only**（merge / force-push / 新規 Actions 禁止）

**build が console を吐いた瞬間に commit + push し、検証はその後に続ける。**
検証してから commit する順序にしない。実測 2026-08-12（Wave 7）: **直前の 2 wave
が 20 本すべてで render_html.clj を書き、1 つも commit せずにセッションを終えた**
（0 commit・untracked・remote に branch 無し）。しかも tick ledger は 5 周連続で
`:outcome :ran :exit 0` を記録し続け、`:pool` は 177 のまま動かなかった——
**成功したように見える失敗が 6.5 時間続いた。** isic-3091 では `:render-html`
alias が孤児 `deps.edn.tmp.43917.8a46d980c405` にしか存在しなかった（temp 書き込みと
rename の間でプロセスが死んだ）。push されていない完璧な実装の価値はゼロ。

**agent の push 先 remote が `origin` とは限らない。** 実測: `isic-4771` の唯一の
remote は `cloud-itonami` という名前だった。`git remote` を見てから push する
（`origin` 決め打ちのスクリプトはそこで黙って落ちる）。

**決定性チェックの scratch は `mktemp -d` を使う。`/tmp/run1.html` のような
固定パスを使わない。** wave は 20 本並列で走るので、固定パスは兄弟 agent と
衝突する。実測 Wave 5: 20 本中 4 本が「決定性が無い」と誤検出した——中身は
別 isic の console だった（isic-2022 が isic-5012 を、isic-4520 が isic-5120 を
上書き）。全数 `mktemp -d` で再検証して byte 一致だったので成果物は無事だが、
**偽陽性は「再現性が無い」という最も調べにくい形で出る。**

### console の良し悪しを byte 数で測らない（2026-08-12 訂正）

**旧版のこの skill は「peers are ~80-100KB」を品質の目安として agent に渡していた。
これは誤りで、撤回する。** 実測（isic-5912 が測り、isic-2593 / isic-5223 が追認）:
参照実装 isic-9522 の console は 76,561 byte のうち **71,085 byte（93%）が vendored
jp-go-dds CSS** で、包んでいる中身は 3 section / 25 row しかない。一方 isic-5912 の
26,387 byte の console は CSS 1,799 byte で **10 section / 59 row**——行数 2.4 倍・
section 数 3.3 倍。**byte 数の差は CSS の同梱方針であって中身の深さではない。**
この目安を渡すと、agent は CSS を盛って数字を合わせに行く。

見るべきは **HARD hold の数と規則の種類 / section・row 数 / 全 entity が seed data に
辿れるか**。

### 「小さい console」は薄いのではなく偽物のことがある

Wave 7 で小さい 2 本を調べたら、どちらも**生成器の出力ではなく手書きの偽 console が
commit されたまま**だった（生成器は一度も走っていない）:

| repo | 偽装の中身 |
|---|---|
| isic-4649（7.9KB） | 初回 commit の手書きページ。`ho-8` を "portable space heater" と説明——seed data は `:product-category :small-appliance` としか言っていない |
| isic-4920（2.0KB） | freight 風の捏造 1 行 `SH1 / Tokyo → Osaka / Yamato / 1Z999AA101234`。実 seed は `shipment-1..6` / `Tokyo DC → Osaka Store` / `Local Freight Co` / `1Z999AA10123456784` |

**既知の robotics stub（`robot-1` / "deliver parcel"）と違って domain 名は正しい**ので、
domain 一致チェックでは捕まらない。**生成器を実際に走らせて上書きさせるしか検出手段が
ない。** 両方とも再生成して 90,983 / 87,576 byte になった。

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

merge した repo 名を `~/.itonami/industry-stack-wave-done.edn` に conj（次周 tick が再ピックしない）:

```bash
# 例: 既存 set に追加して書き戻す
nbb -e '(require (quote [clojure.edn :as edn])) ...'
```

または EDN set を手で更新。**忘れても skill が origin/main で skip するが、slot が無駄になる。**

`~/.itonami/industry-stack-wave.ledger.edn` と
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

## scaffold の欠陥は「fleet 共通」と決めてかからず、その repo で実測させる

Wave 6 の ledger は approver 帰属の欠陥を「6 agent が独立に確認した fleet-wide
scaffold defect」と記録したが、**Wave 7 が 20 repo で測ったら store の挙動は 5 通り
あった。** agent に「この欠陥がある」と教えると、無い repo でも在ることにして書く。
**渡すのは「この欠陥が在るかもしれない、走らせて確かめろ」まで。**

| 型 | 挙動 | 例 |
|---|---|---|
| A 欠陥あり | `commit-record!` が `:value` を分解し `:payload` を読まない | 2029 2733 2593 5223 2815 3091 3290 2660 |
| B 無効化済み | `MemStore/commit-record!` が record 全体を conj するので `:payload` が SSoT に残る | 5819 5912 4540 4771 |
| C 部分的 | 一部の effect だけ `:payload` を読む（2394 は検証は帰属するが出荷/証明は帰属しない） | 2394 4649 4920 |
| D 承認経路が無い | `operation` に承認が無く store に持てる欄も無い | 0128 |
| E 別のキー | 承認者が `[:value :approved-by]` に付くが store がそこを読み出さない | 3315 4329 |

**⚠ この表は閉じた分類ではない。列挙を渡すと、agent は在らない型を報告する。**
Wave 16 が 20 repo を測ったら、上の 5 型にきれいに入らない形が少なくとも 4 つ出た:

- **F 効果ごとに混在（Wave 16 の多数派、14/20）** — `commit-record!` は
  `:value` と `:payload` の**両方**を分解し、screening 系（`:assessment/set` 等）は
  `payload` を読んで approver を**保持する**が、actuation 系
  （`:*/mark-published` 等）は `<domain>.registry` から record を組み直すので
  どちらも読まない。**帰属が失われるのは、人間の承認が必須な唯一の op だけ**。
- **G `commit-record!` が呼ばれない** — 実装は非破壊なのに pipeline が
  `append-ledger!` しか呼ばない（isic-949: 17 proposal → record 0 件）。
- **H hold が保存されない** — approver 以前に、governor の HARD hold が
  ledger に一切残らない（isic-855: 14 op → 7 record、hold 6 件全部が消え、
  `governor/reject-proposal` は dead code）。
- **I 承認という出来事が起こり得ない** — checkpointer も interrupt も resume も
  無い（isic-879）。「store が捨てた」ではない。

ほぼ全 repo で共通して見つかったのは、**`:approval-granted` が store ledger に
到達せず、run の `:audit` channel にしか居ない**こと。

**したがって渡すのは方法（走らせて見る）であって、表ではない。**

**最良の書き方は isic-3315**——開示セクションを **render 時に導出**する（4 つの
register を走査して approver キーの有無を実際に見る）。欠陥が直れば page も自動で
直る。ハードコードした「欠陥があります」は、直った後に嘘になる。

**「黙って省略する」は honest ではない**（isic-3290 の判断）。approver を出さないだけ
だと、読み手は「誰も承認していない」のか「store が保持していない」のか区別できない。
audit fact から join して「(audit のみ・record には無い)」と明示する。

## gate を壊して確かめるとき、失敗も 2 方向ある

skill は既に「壊し方を間違えた赤は *成功した実演* に見える」と書いているが、
**逆向きも起きる**。実測 Wave 16、isic-9524: 不変条件を確かめるための break が
隣接行の regex ミスで **hold 生成を 1 つ残してしまい**、gate は正しく黙った。
これは **「gate が壊れている」ように読める**が、壊れていたのは break の方である。

**「自分の gate が効かない」と結論した agent は、効いている gate を弱めに行く。**
壊したものと報告されたものが一致することを、両方向で確かめる。

## tick が答えない問い —— 「committed な console は本物か」

`--limit` の候補選定は **`render_html.clj` が在るか**しか見ない。**在る console が
本物かは一度も問わない。** 実測 2026-08-14（Wave 16）: fleet 全体 549 個の
committed console のうち **94 個が byte 同一の 2,063 byte の偽物**
（`<title>cloud-itonami · robotics</title>`、`robot-1` / `domain task` の
placeholder 行）で、**94 個とも `render_html.clj` を 1 つも持っていない**。
出所は手編集ではなく **scaffold** —— 2026-07-01 の
`feat: cloud-itonami-isco-XXXX open occupation blueprint` が撒いている。

内訳は **isco-* が 90 / isic-* が 3 / jsic-* が 1**。この tick は ISIC 駆動なので
**91 個は候補に一度も上がらない** —— pool が 0 になって「もう仕事は無い」と
報告した後も、91 repo は robotics safety を名乗る console を publish し続ける。

```bash
# 数え直す（worktree は除く）
cd orgs/cloud-itonami && for d in cloud-itonami-*/; do
  f="$d/docs/samples/operator-console.html"
  [ -f "$f" ] && grep -q 'cloud-itonami · robotics' "$f" && echo "${d%/}"
done | wc -l
```

**pool の数字を「残りの仕事」と読まない。** 本当の条件は
「console が在り、かつ生成器が無い ⇒ 欠陥」であって、tick はまだそれを問うていない。

## git flake — `core.fsmonitor`

子リポで `git status` が `error: could not read IPC response` を吐いたら
`git -C <repo> config core.fsmonitor false`。superproject は `false` だが子リポは
`true` で daemon が死んでいる。Wave 7 で候補 20 本だけ潰した——**fleet 全体は未掃討。**

## やらないこと

- Stripe item7 一括
- 既に REAL render がある repo の書き直し（検証だけ）
- OS 接続（別 skill `itonami-os-connect`）
- craft 新規（wave の主務は item2。craft は明示候補があるときだけ）

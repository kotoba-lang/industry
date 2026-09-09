---
name: itonami-maturity-improve
description: cloud-itonami fleet の成熟度スコア（ADR-2608052000 の 7 軸）を、leverage の高い repo から 1 反復 1 軸ずつ実際に上げる。水増しを構造的に禁じる gate つき。ローカル Claude loop（cloud.itonami.bot.itonami-maturity-improve）が毎周呼ぶが、手で `/itonami-maturity-improve` と打ってもよい。「成熟度を上げる」「maturity loop」「leverage の高い repo を伸ばす」で発火。
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

**tick が `⚠ archived の掃き出しが読めない` と言ったら、先にそれを作り直す。**

```bash
nbb --classpath ".:scripts/nbb_compat" scripts/gen-archived-repos.cljs   # 約 2 分
nbb --classpath ".:scripts/nbb_compat" scripts/gen-archived-repos.cljs --check   # 差分だけ見る
```

archived（GitHub で read-only）な repo は **この順位の常連**になる —— archived =
開発が止まっている = 全軸が低い = 「伸びしろが最大」と読まれるのに、push は
できない。実測 2026-08-11: fleet 最下位 3 本が全部 archived で、loop は 3 周
連続で先頭 3 手を捨てた（ledger に 2 回 `:not-done` として報告されている）。
tick は `manifest/archived-repos.edn` を読んで候補から落とすが、**掃き出しが
無ければ落とせない**。`--check` が exit 1 なら作り直す（新しく archive された
repo が候補に戻っている）。

**これはスコアを変えない。** archived な repo は従来どおり測られ、datoms にも
fleet 平均にも入る。変わるのは行き先だけ。

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

**scan は `orgs/` の実 checkout を読む。** worktree の `orgs/` は west の checkout では
ないので（gitignore されている）、**`--data-root` で本体を指す**。指し忘れると 1,729 repo
すべてを「未 checkout」と測る。同じ理由で dynamics の classpath も本体の絶対パスにする。

```bash
R=$HOME/github/com-junkawasaki                      # 本体（orgs/ が populate されている）
nbb --classpath ".:scripts/nbb_compat" scripts/itonami-maturity-scan.cljs \
  --data-root "$R" --out manifest/itonami-maturity-evidence.edn
nbb --classpath ".:scripts/nbb_compat:$R/orgs/kotoba-lang/dynamics/src:$R/orgs/kotoba-lang/org-oasis-open-xmile/src" \
  scripts/itonami-maturity-dynamics.cljs \
  --evidence manifest/itonami-maturity-evidence.edn \
  --taxonomy manifest/repo-taxonomy.edn \
  --out 90-docs/system-dynamics/itonami-maturity.datoms.edn
```

**scan の前に checkout と pin のずれを見る。** pin より**遅れた** checkout は、
既に着地している仕事を「無い」と測る（実測: 過去周が 6 件でこれを踏んだ）。
**ahead は直さない** —— 自動 commit を打ち続ける actor（`yabai-actor` の ct-watch 等）は
pin より前に居るのが正常で、HEAD こそ実態である。

**パリティゲートも通す**（スコア算術の正本は Kotoba カーネル、ADR-2608052000）。
**`.:scripts/nbb_compat` だけでは起動しない** —— compiler の依存閉包が要る。
**閉包を手で書いた列にしない** —— compiler は名前空間を別 repo へ出し続けており、
**列を書いた瞬間からそれは腐り始める**。実際 2 回とも同じ形で壊れた:

| 起動しなかった理由 | 移動先 | 実測 |
|---|---|---|
| `Could not find namespace: kotoba.artifact.core` | 閉包を 1 つも書いていなかった | 2026-08-08 |
| `Could not find namespace: kotoba.compiler.frontend` | compiler → **`kotoba-sema`**（#545 "Consume semantic analysis from kotoba-sema"。ns 名は不変で repo だけ動いた） | 2026-08-09 |
| `Could not find namespace: sha2.core` | **閉包の引き方が 1 段しか辿っていなかった**。`sha2.core` を持つ `org-nist-sha2` は amu の *直接* 依存ではなく、`security` 等を経由した先に居る | 2026-08-31 |

どれも**落ちたのではなく走らなかった**ので、「スコア算術はカーネルと一致している」が
誰にも検査されないまま計測が landed し続けた（落ちるより悪い。検査されていないことが
緑と区別できない）。

⚠ **3 件目は「列を書くな」を守っていても起きる。** 閉包を毎回 `amu/deps.edn` から
引いていても、**1 段だけ引けば 1 段目の外は見えない**。依存は推移的なので、
**閉包の計算も推移的でなければならない**。だから閉包は shell に書かず、
**`scripts/itonami-maturity-parity-classpath.cljs` に訊く**:

```bash
CP=$(nbb --classpath ".:scripts/nbb_compat" \
      scripts/itonami-maturity-parity-classpath.cljs --root "$HOME/github/com-junkawasaki") || true
nbb --classpath "$CP" scripts/itonami-maturity-kernel-parity.cljs \
  --evidence manifest/itonami-maturity-evidence.edn \
  --datoms 90-docs/system-dynamics/itonami-maturity.datoms.edn
```

deriver の exit は 3 値（`0` 全部 checkout 済み / `1` classpath は出たが未 checkout の
repo が在る（stderr に repo 名と `west update` の行）/ `2` **REFUSED** —— 起点の
`amu/deps.edn` が読めず、stdout に**何も出さない**）。`|| true` が要るのは `1` が
advisory だから。**`2` のときに CP が空文字のまま gate を回さない。**

⚠ **この recipe を shell に戻さない。** 同じ日に shell 版が 2 回別の理由で壊れた ——
1 段しか辿らない閉包と、**zsh が `for r in $CLOSURE` を単語分割しないので classpath が
2 エントリに潰れる**（CLAUDE.md の `west update $NAMES` と同じ罠）。どちらも
**classpath が静かに間違うだけで、症状は「gate が起動しない」= exit 1** だった。

⚠ **起動しなかったときの exit も 1 である。** 本物の `FAIL` と同じ値なので、
**exit だけを見て「不一致があった」と読まない**。`Could not find namespace:` が
出ていないことを毎回確かめる（下の「緑を採用する前に落とす」と対で効く ——
落とす側が名前解決で落ちていたら、それは discriminate の実演になっていない）。

**`MISSING checkout:` が出たら先にそれを取る。** 閉包の repo は west に登録されていても
checkout されていないことがある（実測 2026-08-09: `kotoba-sema` が未 checkout で、
`orgs/` を見ただけでは「そんな repo は無い」と読めた）。**手元に無いことは
存在しないことではない** —— west.yml を引く。

**緑を採用する前に落とす。** datoms のコピーで 1 repo の `:maturity/own-bp` を
**1bp だけ**ずらし、`FAIL` + exit 1 になることを見る（無改変で exit 0）。
起動しない gate と全一致する gate は、出力が同じ「異常なし」なので区別できない。

生成物を着地させて終わり。この周は lane を消費しない（軸を上げていないので）。
ledger の行は `:outcome :remeasured` / **`:axis :none-remeasure`** にする ——
`axis-` で始まる軸を書くと freshness 判定がそれを「軸上げ」と数え、
loop は測り直しから二度と出られない（`scripts/itonami_maturity_freshness.cljs`）。

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

**`:at` は必ず `date -u` の実 UTC を書く。** この端末は JST なので、`date` の
出力をそのまま写して `Z` を付けると **9 時間先の時刻**になる。実測（2026-08-09）:
`m365-ingest` の行が `"2026-08-09T00:30:00Z"`（実際の merge は `15:14:18Z`）と
なっており、**測り直しを main に着地させた直後の tick がまだ STALE と答えた** ——
未来の着地はどんな計測より新しいので、loop は測り直しから出られなくなる。

```bash
date -u +%Y-%m-%dT%H:%M:%SZ    # ← これを :at に貼る
```

tick 側は `:merged` commit の実時刻を優先して読むので 1 行の誤りでは詰まらないが、
**`:merged` が解決できない行はこの `:at` しか手が無い**（tick は未来の行を
`⚠ ledger の時刻が未来` として報告し、判定には使わない）。

**`:outcome :landed` は、gate を通して main に入ったときだけ。** 途中で止めたら
`:partial` にして何が残ったかを書く。**上がったかどうかは自分で判定しない** ——
次周の tick が測り直して `:own-before` と突き合わせる。

## やらないこと

- **`:axis-substrate` / `:axis-fresh` を目標にしない。** tick が除外している。
- **gate を通さずに「上げた」と書かない。**
- **dirty な repo を触らない**（他セッターの未コミット WIP）。
- **変更前から赤い repo を触らない**（自分の変更の可否を判定できない）。
- 目標にできる軸が無ければ**何もせず終える**。無いのに作らない。

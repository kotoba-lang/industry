---
name: verification-discipline
description: 検査を書く前・緑を信じる前・「無い」と結論する前・規則を制約として持ち出す前・「遅い」を説明する前に読む正本。8 問（入力が無いとき何を返すか／実行できないとき／エラー本文／skip と pass の区別／両方向と境界／名乗った理由で拒否したか／仕事をしたから緑か／生成物を実行したか）、shell の `$?` と zsh の単語分割の罠、一覧 1 本の不在を「無い」と読まない規則、索引（repo-search / concept-lookup / 4 つの datom 索引）と sparse cone の確かめ方、Kubo に手を伸ばさない、compliance 2 索引、規則が「性質」か「実装状態」かの判定手順、基盤ライブラリの定数倍は呼び出し側の profile に現れない話。「test を書く」「gate を足す」「緑になった」「X は無い」「作る必要がある」「この規則があるからできない」「遅い」「profile」で発火。CLAUDE.md の 4 節（8 問／「無い」と結論する前に／性質か実装状態か／定数倍）から 2026-09-11 に切り出した正本（ADR-2609112300）。
---

# verification-discipline — 測ってから言う

**CLAUDE.md の次の 4 節はここへ委譲している。** CLAUDE.md 側には skill を読まなくても
効く問いと禁止だけが残っており、実測・実例・コマンド・罠はこの文書が正本。

1. 検査を書く前・緑を信じる前の 8 問
2. 「無い」と結論する前に、索引を引き、検索する
3. 規則を制約として持ち出す前に、それが性質か実装状態かを判定する
4. 基盤ライブラリの定数倍は、呼び出し側の profile に現れない

---

# CLAUDE.md に 2026-09-11 まで残っていた本文（逐語、ADR-2609112300）

以下は CLAUDE.md から**逐語で**移した本文である（2026-09-11、ADR-2609112300。AGENTS.md の
読み込み上限 31,457 字に合わせて CLAUDE.md を不変条件だけに絞った）。CLAUDE.md 側には
skill を読まなくても効く規則だけが残っている。ここが理由・実測・罠の正本。

## 検査を書く前・緑を信じる前の 8 問（repo-wide mandatory、2026-08-13 / 6 問目 2026-08-22 / 7 問目・8 問目 2026-09-06、ADR-2608136000）

**測れなかった検査が、測って問題が無かった検査と同じ値を返す** —— この 1 つの形が
2026-08-13 の 1 日で **14 箇所**見つかった（gate・PreToolUse hook 4 本・launchd job 2 本・
生成 runner 41 repo 分・検証器・alias のコメント）。個別には別のバグに見えるが同型で、
**沈黙が緑として蓄積する**。

1. **入力が無いとき何を返すか。** pass ならそれが欠陥（`root-permit-index` は
   入力不在を「射影がズレている」と 297 回報告した）。
2. **そもそも実行できないとき何を返すか。** pass と同じ値なら欠陥
   （deploy guard は `origin/main` が解決できないと `allow!` していた —— west の
   checkout は remote を org 名で持つので **4,406 中 2,824（64%）が無検査**だった）。
3. **受け取ったエラー本文を捨てていないか。** status だけ記録する経路は、原因が
   応答の中に書いてあっても読まない（HTTP 400 を 20 回、本文を捨てて status だけ記録）。
4. **「飛ばした」と「合格した」が出力で区別できるか。**
5. **その検査は両方向を出したことがあるか。**
   ⚠ **両方向を出しても、境界が無ければ演算子は見えない。** 実測 2026-09-06、
   発注額の上限比較を `>` から `>=` に反転しても自己検査は**緑のまま**だった ——
   通る例も落ちる例も在ったが、「発注額 == 決議予算」の**線上のケースが無かった**。
   その 1 件（規則が「超過」なので**可決されるべき**）を足すと反転で赤くなる。
   **比較を持つ検査には、必ず境界ちょうどの入力を 1 つ置く。**
6. **その検査は、自分が名乗っている理由で拒否したことがあるか。** 結果だけを
   assert する負テストは、**別の原因で落ちた実行を「discriminate した」として数える**。
   2026-08-22 の 1 日で、別々の repo の 4 つの agent がこの形を踏んだ:
   ① `tls.cert` の署名検証は provider の答えを truthy で判定していた —— 契約に
   `[:ok false]` は無く、拒否は `[:error :signature/bad-signature]` という**空でない
   ベクタ**なので、**却下された署名がすべて成功として通っていた**（1 マージ入り、次で
   修正）。② `kotoba-lang/http` の wrong-pin control は、握手がもっと手前で失敗して
   いたため「拒否された」は真のまま**空振りで通っていた**。③ aiueos の cross-host
   テストは注入した故障とは別の理由で赤くなった（受け入れず、テスト側を直した）。
   ④ 同じ変更の 5xx 分離テストは、verdict を socket から得ずに自分で構築していたため
   **revert しても緑のまま**だった —— ADR-0073 が「docstring から書かれたテスト」として
   記録した欠陥が、その ADR の後に書かれたコードで再発した。
   **理由の literal を pin する。** upstream が理由名を変えたとき失敗になるのは
   欠点ではなく、それがこの assertion の効き目そのもの（実測: `:spki-pin-mismatch`
   → `:peer-not-pinned` の rename を、この形の control だけが捕まえた）。

7. **その緑は、仕事をしたから緑なのか、仕事を飛ばしたから緑なのか。** この節は
   「壊し方を間違えた赤」を既に警告しているが、**鏡が抜けている** —— 直したあとの緑も
   同じだけ疑う必要がある。**正しく動いている skip/cache/gate は、仕事をした実行と
   同じ成功値を返す。** 変更した経路が実際に実行されたことを確かめるまで、その緑は
   修正の証拠ではない。
   確かめ方は 1 つ: **変更した経路を必ず通す条件で 1 回走らせる**（gate の前回状態を
   消す / cache key を変える / skip の入力を変える）。それができないなら、「直った」
   ではなく「変更後に緑だったが、その経路が走ったかは未測定」と書く。
   ⚠ **skip した実行が理由を印字していても足りない。** 実測 2026-09-06、出力は
   `no_change (agent run suppressed)` と正しく言っていたのに、`Ran now: succeeded.`
   の側を読んで「接続の修正が効いた」と結論しかけた —— **道具は正直で、読み手が
   誤った。** 直後に gate を外して走らせ直すと、同じ失敗がそのまま出た。

8. **その検査は生成物を実行したか、ビルドできたことで満足したか。** 7 問目は
   「変更した経路が走ったか」を問うが、**その手前に「作ったものを動かしたか」がある**。
   コンパイラ・生成器・トランスパイラを相手にすると、**受理して誤った答えを出す**
   backend が在りうる —— 拒否する backend より悪い。拒否は設計判断を 1 つ生むが、
   誤答は何も生まない。
   実測 2026-09-06（kotoba-lang/amu#835）: あるモジュールは `aarch64-macos` に
   **コンパイルでき、実行でき、答えが違った** —— その backend では keyword の `=` が
   常に false で、同じビルドで i64 の `=` は正しい。全分岐が keyword で回るので
   全部が誤った枝へ行った。**気づけた理由は、モジュールが自分で self-check を持ち、
   失敗の「個数」を返していたことだけ**である（boolean なら「何かが失敗した」しか
   言えず、1 件の退行と壊れたビルドを区別できない）。
   したがって: **成果物を出す検査は、その成果物を実行して値を確かめるまで
   pass にしない。** `:ok true` は「ビルドできた」であって「正しい」ではない。

直し方で効いたもの: **evidence floor**（`SCANNED<TAB>n`、n=0 を clean にしない）/
**実行本数の床** / **「答えられなかった」専用の exit code**（0 でも 1 でもない値）/
**答えを拒否する**（`git archive` に `.git` が無いと分かった gate は
`Refusing to report a pass` と言って終わる —— 恒久的に赤い gate を landing させるより良い）/
**signal を落とさない** / **測ったときの load を値の隣に書く** /
**self-check は個数を返す**（boolean は 1 件の退行と壊れたビルドを区別できない）/
**禁じたい経路は「無い」ではなく「拒否して記録する」**（PATH の先頭に stub を置き、
呼ばれたら log に追記して非ゼロで終わる。そして**その log が空でないことを 1 度は
見せる** —— 実測 2026-09-06、JVM-free 経路の検証で `amu test` だけが
JVM 経路（当時の `clojure` CLI の `-M:run`）に落ちて trace を踏み、それが「trace が何かを検出できる」ことの
証拠になった。踏まれたことのない trace は、常に空な trace と区別できない）。

⚠ **この class を最も安く作れるのは shell である。`$?` は pipe の「最後の」
コマンドの終了値**なので、次の 1 行は**検査の結果を一度も見ていない**:

```bash
timeout 110 kbb --backend sci scripts/audit.cljk | tail -12; echo EXIT=$?   # ← tail の 0
timeout 110 kbb --backend sci scripts/audit.cljk > /tmp/a.log; echo EXIT=$? # ← 検査の値
```

実測 2026-08-22: 上の形が `EXIT=0` を出したが、**監査自体は `timeout` に殺されて
いた**（124）。「実行できなかった検査が、実行して問題が無かった検査と同じ値を
返す」の最短形。長い検査は**先にファイルへ落として exit を採り、それから読む**
（`${PIPESTATUS[0]}` / `set -o pipefail` でもよいが、`>` が一番間違えにくい）。

⚠ **同じ形が「一括操作」でも出る。zsh は引用符なしの変数展開を単語分割しない**ので、
`for x in $LIST` は**リスト全体を 1 個の値として 1 回だけ**回る。エラーは出ず、
ループは成功して見える。この CLAUDE.md は既に `west update` について同じことを
書いているが、**罠は shell の側にあって west の側には無い** —— `aws` の削除ループでも
`xargs` を使わない限り同じことが起きる（実測 2026-09-06）。

```bash
for k in $KEYS; do ...; done                 # ← 1 回しか回らない
printf '%s\n' "$KEYS" | xargs -I{} ...      # ← 1 行ずつ回る
```

**一括操作は「エラーが出なかったこと」で成功と判定しない。終わったあとに件数を数える。**
そして **0 件が返ったときは、それが「空」なのか「読めなかった」のかを control で分ける**
（消えたはずの 1 件が 404 になり、消していない 1 件が 200 で返ることを両方見る）。
store によっては `length(...)` が空を返し、**エラーと区別が付かない**。

**この規則はコードに書かれた検査だけを縛らない**（2026-08-21、ADR-2608211000）。
金額・契約状態・支払い状況を報告するときも、会計一覧・督促・検索結果など**一覧 1 本の
不在を「無い」と読まない**。その一覧が対象を載せる義務を持つかを先に確かめ、最低 2 つの
出所（請求書と入出金明細、契約書と請求実績など）を 1 件ずつ突き合わせる。
逆向きも同じで、説明できない差異を、突き合わせずに危険として報告しない。
報告には**何と何を突き合わせたか**を書く。突き合わせていないなら、その不在は
`無い` でも `危険` でもなく **未測定** と書く。

⚠ **この class を直すとき、壊し方を間違えた赤は「成功した実演」に見える。**
当日 4 回起きた —— gate の*別の*検査を壊した / reader を throw させた（EDN が壊れて
いることの証明であって、静かな切断の証明ではない）/ コメントの中の key を置換した /
検索対象の部分文字列を含む名前に改名した。**壊したものと報告されたものが一致することを
確かめる。**

**直したら pin も前進させる。** 子リポの main を直しても、west pin が手前にあると
gate は古い tip を見続ける（実測: `amu` / `cloud-itonami` とも修正 commit の手前で
pin が止まっていた）。修正 → `advance-pins.cljs` → `verify-west-pins.cljs` までが 1 組。


## 基盤ライブラリの定数倍は、呼び出し側の profile に現れない（repo-wide mandatory、2026-09-05、ADR-2609051700）

**「遅い」と分かった場所と、遅い理由が在る場所は、たいてい 2 層以上離れている。**
呼び出し側のコードは正しく、そこにある profile もその層のことしか言わない。しかも
基盤 codec は正しさが最優先なので、**正しく書かれた遅い実装はテストを全部通り、
review でも通る。**

実測 2026-09-05: Cloudflare account の Worker CPU の **98.5%**（週 170 万 CPU 秒、
2.8 コア相当）を `api.murakumo.cloud` の 1 本が使っており、その 89% は
`GET /infer/queue` —— **2 バイトの空配列を返すのに 760 ms**。原因は 2 層下の
`multiformats/base32.cljc` が 1 バイトを 8 要素の lazy seq に展開していたことで、
DAG-CBOR のリンクは全部 CID なので `ipld/decode` が canonical 再エンコードで
リンク 1 本につき 1 回それを払っていた（643 リンクのブロックで 124 ms 中 97 ms）。

- **プロファイルする層を、症状が出た層で止めない。** 症状の層で説明が付いたように
  見えても、その説明が「このライブラリを呼んでいるから」で終わっているなら、
  まだ測っていない。
- **コードを読んで得た確信を測定の代わりにしない。** この 1 件で私は 3 回、
  コードから原因を推定して 3 回とも外した（legacy catalog / shard フェッチ /
  read そのもの）。当たったのは R2 の実バイトを引いて段階ごとに測ったときだけ。
- **検出は呼び出し側ではなく codec 側で、形に対して行う。** 検査は
  `kbb --backend sci --classpath ".:scripts/nbb_compat" scripts/verify-codec-seq-expansion.cljk --findings orgs`
  （`manifest/orgs-detectors.edn` の `:verify-codec-seq-expansion`）。捕まえるのは
  ①`mapcat` して `partition` で組み直す形 ②バイト列の等価判定のために両辺を
  persistent vector に materialise する形。**報告するのは形であって計測値ではない**
  —— finding は「ここを測れ」であって「ここが遅い」ではない。
- **基盤ライブラリの pin は、fix が main に在っても届かない。** io-multiformats /
  io-ipld はどの deps.edn からも直接は引かれておらず、他 repo の `:git/sha` 経由で
  しか入らない。tools.deps は**見せられた中で一番新しい sha**を選ぶので、誰かが
  新しい sha を名指すまで fix は届かない。deploy する repo は自分の deps.edn に
  **明示的な床**として pin し、理由を隣に書く（west pin には `verify-west-pins` が
  あるが、`deps.edn` の pin には gate が無い）。

⚠ **ここに測定値を書き足さない。** 上の数字は「何が起きたか」の記録であって、
今日の値ではない。今日の値は上のコマンドとその repo の bench が持つ。


## 「無い」と結論する前に、索引を引き、検索する（repo-wide mandatory、2026-08-03 / 2026-08-04）

**「この workspace には X が無い」「X を作る必要がある」と結論する前、および新しく何かを
作り始める前に、必ず索引と検索を引く。** west.yml は 4,000 を超える repo を管理しており
（正確な数は数える）、**checkout されていない repo は `ls` にも `find` にも `grep -r` にも
映らない**。手元に無いことは存在しないことではない。

```bash
kbb --backend sci scripts/repo-search.cljk bitswap libp2p   # 名前 + checkout 済み README 冒頭
kbb --backend sci scripts/concept-lookup.cljk terminal      # 概念 → repo（順位付き・有界）
kbb --backend sci scripts/concept-lookup.cljk 端末           # 日本語でも引ける
kbb --backend sci scripts/concept-lookup.cljk               # 語彙一覧
```

**`repo-search` は名前と、checkout 済み repo の README 冒頭の両方に当たる** ——
能力名が repo 名に出ないことがあるため（multistream と Yamux は
`io-libp2p-specs-transport` にあり、どちらの語も名前に無い）。接頭辞を持たない
library（`noise`、`codebase`、`identify`、`mesh`、`p2p` 等）はこれが拾う。

**grep で代替しない** —— 全 repo に対する全文検索は必ず数百行を出し、必ず切られ、
**切られたことに気付く手段が無い**（2026-08-03、`kuro`/`kobo` は grep に当たっていたのに
`head -40` で切って当の行を見ていなかった）。

| 索引 | 何を答えるか | 生成 |
|---|---|---|
| `90-docs/concept/concept.datoms.edn` | **どの repo がどの概念を実装しているか** | `kbb --backend sci scripts/gen-concept-index.cljk` |
| `90-docs/surface/surface.datoms.edn` | **どのホストがどのパスを提供しているか** | `kbb --backend sci scripts/gen-surface-index.cljk` |
| `90-docs/compliance/scope.datoms.edn` | **どのワーカがどのデータストアに触り、誰に預けているか** | `kbb --backend sci scripts/gen-compliance-scope.cljk` |
| `90-docs/compliance/dependencies.datoms.edn` | **どの repo が何に依存し、それは本番に載るか** | `kbb --backend sci scripts/gen-dependency-inventory.cljk` |

4 つとも生成物（手で編集しない）。語彙 `manifest/concept-vocabulary.edn` だけが手書き。
いずれも `manifest/edn-query.cljk` の datom 面に載っており、`:concept/repo` /
`:surface/repo` / `:scope/repo` / `:dependency/repo` は `repo-taxonomy` の `:repo/path` と
join できる。**セッション開始時に外部仕様ミラー repo の一覧**（`io-`/`org-`/`tech-`/`dev-`/
`capability-` 接頭辞）が SessionStart hook `session-start-spec-inventory.cljs` で自動提示
される —— この接頭辞群は命名規則上「どの外部仕様が実装済みか」の答えそのもの。

**実測（2026-08-04、この規則が生まれたセッション）: agent が 1 セッションで「無い」と
3 回結論し、3 回とも間違っていた**（semantic-code / DHT announce / transport の 3 件は
`codebase`・`io-libp2p-specs-kad-dht`・`io-libp2p-specs-transport` に既に在り、3 回とも
1 コマンドで見つかった）。失敗したのは検索能力ではなく**「結論する前に検索する」という
手順**で、prose の指示（CLAUDE.md には既に「既存を確認せよ」が複数ある）だけでは
守られなかった。しかも 3 回目は 2 回目の訂正を受けた直後に起きている ——
**一度直した種類の誤りが、次の話題で再発する。**

**既存を見つけたら使う。** 「見つけたが自分で書き直す」は、既存が accepted ADR で
否定されている場合を除き、選択肢に入らない。

### 索引が当たったことは、動くものが在ることの証拠ではない（2026-09-06）

**見つけた機構の上に何かを載せる前に、それを *読む側* が実在するかを確かめる。**

| 見つかったもの | 確かめること |
|---|---|
| **accepted な ADR が機構を定義している** | その形を**読むコードが在るか**。`grep` して 0 hit なら、書いても誰も読まない |
| **その名前のコードが在る** | **同じ名前の別物ではないか**（面も schema も別、ということが起きる） |

実測 2026-09-06、索引が 3 つ返して**2 つが行き止まりだった**（1 つは accepted だが読む
実装が無く、1 つは同名の検査器が別 schema を見ていたので**実装済みに見えた**）。
確かめ方: 読む側を `grep` する / 使っている**実例が 1 つ以上在るか**を見る / それでも
決まらないなら**最小の 1 個を置いて、拾われるかを観測する**。拾われないものを
「登録した」と報告しない。

**索引に無いことも、存在しないことの証拠にならない**（concept 索引は README のある repo
だけを見る。未索引数は `:concept/coverage` が申告する）。「索引を引いたが無かった」を
不在の証明に使わない。

### 同じ誤りは repo の *中* でも起きる —— sparse cone

**この superproject は cone-mode sparse checkout である。** cone の外のファイルは `ls` にも
`find` にも映らず、`git ls-files -v` では **`S`（skip-worktree）** が付く。
**`origin/main` には在る。手元に無いだけである。**

**手元に無いファイルについて何かを結論する前に、`git ls-files -v` と
`git cat-file -e origin/main:<path>` を引く。** cone 外・stale checkout・未 checkout の
west project —— **3 つとも「`ls` に映らない」で同じ顔をする。**

実測 2026-08-13、同じバグが**両方向に 1 回ずつ**出た: `docs-edn-only.cljs` の baseline が
sparse な worktree から生成されて cone 外の 6 件を「新規」として 1 週間報告し、その 1 週間後
私はその 6 件を「もう存在しないから削れ」と指示した（**6 件は `origin/main` に無傷で在り、
指示どおり削っていれば ratchet から本物の 6 エントリが消えていた**）。1 回目の対策は
docstring への注意書きで、**効かなかった —— 誤った答えを出す実行は docstring を読まない。**
現在は `git ls-files --cached --others --exclude-standard` で git に訊き、読めない分が
在れば `edn=<scanned>/<listed>` を出して **exit 2**（0 でも 1 でもない = 「答えられなかった」）
で終わる。

⚠ **その `<rev>:<path>` を shell 変数で組み立てない。zsh が食う。** 実測 2026-08-19
（zsh 5.9）、`$r:$path` の `:` 以降は history modifier として解釈される ——
**この workspace で最も多い 2 つの top-level dir がどちらも当たる**:

```
$r:scripts/x.cljs   → pr/547           # :s = 置換。以降を静かに飲み込む
$r:tools/x.c        → 547ools/x.c      # :t = tail。静かに別物になる
${r}:scripts/x.cljs → pr/547:scripts/x.cljs   # ← 常にこう書く
```

**壊れ方が path 依存なので、動く例を見て安心できない。** しかも `2>/dev/null` を付けると
`fatal: Not a valid object name` が消え、**存在するファイルが「MISSING」として報告される**
—— 「無い」と結論しないための道具が、「無い」と嘘をつく。確実な形は `${r}:...` と波括弧で
閉じるか、`git ls-tree -r --name-only <rev> -- <path>`（`--` の後は expansion されず、
件数で答えが出る）。

### IPFS / content-addressed storage で Kubo に安易に手を伸ばさない（repo-wide mandatory、2026-08-28）

**IPFS の block 取得・bitswap 相当の P2P 配布が要る時、Kubo（go-ipfs）のような外部ネイティブ
バイナリ daemon を既定の選択肢にしない。** プラットフォーム別バイナリ配布と別プロセス
daemon は、「新規に外部ネイティブバイナリへ依存する」パターンそのもの。

**`kotoba-lang/io-libp2p`（実体 repo 名 `kotoba-net`）に libp2p 実装が既にある** ——
TCP + multistream + Noise XX + Yamux + Kademlia DHT + GossipSub + IPNS が揃い、
**2026-08-04 に実 public IPFS ピアと相互接続検証済み**。関連: `kotoba-lang/p2p` が
同じ基盤の上に GraphSync を構築している。

⚠ **ただし「pure `.cljc` だからどこでも動く」ではない。ここは 2026-09-09 に訂正した。**
それまでこの節は「pure `.cljc` の完全な実装」「nbb/JVM 上で in-process に動く」と書き、
**`src/kotoba/net/bitswap.cljc` に実際の bitswap がある**と名指していた。3 点とも測ると違う:

| 主張 | 実測 2026-09-09 |
|---|---|
| `net/bitswap.cljc` が bitswap | **違う。** その docstring 自身が `No block transfer over any wire` と書いている（want-list の集合演算だけ、protobuf の require すら無い）。wire は **`net/libp2p/bitswap.cljc`** —— `/ipfs/bitswap/1.2.0` を go-bitswap の `message.proto` の field 番号で持つ |
| pure `.cljc` | **違う。** `libp2p/` は 22 ファイル中 **14 が `.clj`**。`.cljc` なのは schema 側（bitswap / handshake / identify / gossipsub / connection …）で、**dial・socket・transport・mux・keys・node は全部 `.clj`** |
| nbb でも動く | **動かない。** 上のとおり接続経路が JVM 専用。もう 1 本の transport `net/transport/tcp.cljs` は `node:net` なので **workerd では動かない** |

**つまり protobuf は在り、足りないのは transport である。** bitswap の wire schema は
書けているので、Worker から使いたいなら要るのは protobuf ではなく **workerd の
`cloudflare:sockets` 上の transport**（と、request-scoped isolate ではなく session を
持てる Durable Object）。**「bitswap が在る」と「その runtime から届く」を混同しない。**

この誤りが実害を持つ形: この節は「Kubo に手を伸ばすな、io-libp2p を使え」と指示している。
Worker 上の agent がそれに従って名指しの path を開くと、**集合演算だけのファイルに当たる**。
そこで「無い」と結論しても「全部在る」と結論しても、どちらも誤る。

実測 2026-08-28: 複数の agent が「Kubo を fleet ノードへ curl 取得」「npm の Helia」へ
いきなり向かい、**この既存実装を見落とした**（`kbb --backend sci scripts/repo-search.cljk bitswap libp2p`
で一発で見つかる）。既に Kubo が動いている環境との相互運用として残すのはよいが、
**新規設計の第一候補は `io-libp2p` の native 実装。**

### compliance の 2 索引が答えるもの（2026-08-23）

surface 索引は「どのホストがどのパスを出すか」までで、**そのワーカがどのデータストアに
触るかを持っていなかった**。監査（SOC 2 CC3.2/CC6.1、ISO/IEC 27001 A.5.9）で問われるのは
そこ。**どちらも fleet gate にできない**（west 管理の `orgs/` を読む）ので
`manifest/orgs-detectors.edn` に `:compliance-scope-boundary` / `:dependency-vulnerabilities`
として登録してある。

⚠ **実測値をこの節に書かない。** 数は ADR と索引の中に在るので、必要なら引く
（この CLAUDE.md 自身が fleet-ci の節でそう警告している形）。この 2 つを引かずに次の 3 つを
結論しないこと:

- **「この面は他と切り離せる」** —— 1 ワーカが複数の登録可能ドメインに応答している例が
  実在する。**境界はドメインではなく共有された制御環境の単位でしか切れない。**
- **「脆弱性は無い」** —— version が範囲（`^1.2.3`）の依存を advisory DB に投げると
  「該当なし」が返り、それは「脆弱性が無い」と同じ顔をする。範囲のままの依存は
  **未測定であって clean ではない。**
- **「この脆弱性は緊急だ」** —— `:dependency/dev?` を見ずに数えない（2026-08-23 に
  `undici` の勧告を「本番」と誤報告した実例。deploy された Worker は workerd で走り
  undici を載せない）。⚠ **`:dev?` は 3 値**（pnpm の lockfile は dev/prod を言わないので
  `nil` = 判らなかった）。`not` で畳むと、判らなかったものが本番として並ぶ。

**統制の写像と SBOM の生成器**: SOC 2 TSC / ISO 27001 Annex A ↔ 手元の証拠の写像は
`kotoba-lang/security` の `policy/control-crosswalk.edn` +
`src/kotoba/security/crosswalk.cljc`（現在地は
`kbb --backend sci --classpath src scripts/check-crosswalk.cljs`。**設計の証拠は運用の証拠に
ならない**という不変条件を計算器が持つ ——「写像を埋めても Type II を主張できない」）。SBOM の生成器は
`cloud-itonami/cloud-itonami-isic-7120-cyberassurance` の `cyberassurance.sbom` ——
⚠ **新しく作らない**（domain は `app-sbom`、リリース成果物の仕様は `security` の
`docs/sbom-slsa.md`、署名への束ね方は `amu` が既に持っている）。**認証が取れるかへの答えは
評価からは出ない** —— 発行するのは CPA firm / 認定審査機関 / 登録監査機関で、
**評価の完全性は発行権限ではない**（ADR-2608231800）。

**名前が機能を示さない repo を作ったら、README の冒頭で名乗る。** 短い名前を選ぶのは
正しいが、説明可能性は別の場所で補う必要がある（`manifest/concept-vocabulary.edn` 登録も要る）。


## 規則を制約として持ち出す前に、それが性質か実装状態かを判定する（repo-wide mandatory、2026-09-04、ADR-2809041200）

**規則・ADR・docstring を「だからこうはできない」の根拠に使う瞬間に、それが
性質を述べているのか、その日の実装状態を述べているのかを判定する。**
実装状態なら、従う前に測る。

上の「実装スナップショットを言語にしない」は**書く側**の規則で、`rule-kaizen` は
**定期棚卸し**（1 反復 = 1 finding）である。この節が足すのは**読む側** —— 規則を
持ち出したその場で確かめる、という手順。棚卸しは何千の規則に対して 1 日 1 件しか
進まないので、**あなたが今まさに引用している 1 件**には間に合わない。

### 判定

| その規則が言っているのは | 例 | 扱い |
|---|---|---|
| **性質** — 定義・不変条件・数学的事実から出る | 「union は SET なので重複は 1 度しか現れない」「HMAC で blind した key は順序を保存しないので range scan ができない」 | そのまま従う |
| **実装状態** — 今のコードがそうである、という事実 | 「`open` は `:ref-name` を 1 つしか取らない」「native backend にこの型は無い」「stdlib にこの関数は無い」 | **測ってから従う** |

見分け方は**理由が規則の中で閉じているか**。性質なら「なぜそうなるか」がそこに
書いてある。実装状態は「今はそうなっている」で止まり、**いつからそうなのか・
誰がどう変えられるのかが書かれていない**。

⚠ **「これは実装の都合ではなく X そのものである」と書いてある規則ほど疑う。**
その一文は、書き手が実装状態を性質に**昇格させた**瞬間の痕跡である。本当に性質なら
導出が書けるので、わざわざそう宣言する必要が無い。

### 実例（2026-09-04、この規則が生まれた経緯）

ADR-260726（**superseded** —— ADR-2809040800 が実測で反転させた）は「kotobase の
Datalog join の到達範囲はちょうど ref 1 本で、別 ref に分けたものは**二度と join
できない**。**これは実装の都合ではなく、kotobase のデータモデルそのものである**」と
書いていた。私はこれを制約として引用し、IPLD 越しの
query 設計をこの前提の上に組み立てた。

**測ると偽だった。** `datom-source` の `merged` に、答えがどちらの partition 単独にも
存在しない 2 ホップ join を通すと届く（A 単独 `#{}`、B 単独 `#{}`、merged
`#{"alice"}`）。旧文が書かれた時点では正しく、その後 `IPatternSource` seam が入って
天井が動いていた。**規則だけが動かなかった。**

代償は「間違った設計を書きかけた」ことではない。**その一文が「corpus を分けたら
終わり」という誤った設計圧を、分けてよくなった後も何ヶ月もかけ続けていた**ことである。
規則は破られると音がするが、**古い規則に従っている間は何の音もしない。**

### 手順

1. 規則を引用して設計を縛ろうとしたら、**その規則が名指ししているコードを開く**。
2. **1 コマンドで反証できるなら、まず反証を試す。** 上の例は `merged` に join を
   1 本通すだけで済んだ。規則を信じて設計をやり直すより安い。
3. 反証できたら、**その場で規則を直す** —— `:adr/status` を `superseded`、後継を
   `:adr/superseded-by`、**`CLAUDE.md`（agent 指示の正本）の該当節も同じ commit で**。
   次に読む人は ADR ではなく agent 指示を見るので、片方だけ直すと誤りが残る。
   **`AGENTS.md` は `CLAUDE.md` からの生成物なので直接編集しない** —— `CLAUDE.md` を
   直して `kbb --backend sci scripts/gen-agents-md.cljk` を回す（下記「agent 指示は 1 本の正本から
   生成する」節）。
4. 反証できなかったら、**確かめた事実を規則の隣に足す**（「2026-09-04 に測って
   まだ真」）。次の人が同じ検証を繰り返さずに済む。
5. どちらの場合も、**測った内容は数値ではなく再現手順として残す**（この CLAUDE.md が
   fleet-ci 節で繰り返し警告しているとおり、日付付きで書いた値は日付を落として
   引用される）。

**規則を疑うことと、規則を無視することは別である。** 測らずに従うのも、測らずに
破るのも、同じ 1 つの誤り —— 根拠を確かめていない。だから 2 の反証が失敗したときは、
その規則は**前より強くなる**（測られたから）。


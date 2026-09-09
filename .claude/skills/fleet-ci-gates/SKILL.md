---
name: fleet-ci-gates
description: murakumo mac-mini fleet の CI/CD（`scripts/fleet-ci/`）を触るときの正本 — gate の書き方と種別（jvm-test / nbb-test / nbb-script）、`:include-ext` で配られる tree が手元と違う話、ノードの外向き HTTPS を定数で持たない規則、署名鍵の在り処、placement authority が murakumo.task.plan にあること、job 配分の LPT、生成物を sha256 で検査する gate、赤い gate を直す前に確かめる 3 点（receipt の sha / 引数順 / ローカルと fleet の差）、gate の入力が west 管理の `orgs/` にあって配られない問題、GitHub Actions の無効化状態を GitHub 側に訊く方法と課金の測り方。「fleet gate を足す」「gate が赤い」「gates.edn」「fleet-ci」「GitHub Actions を止める」「Actions の課金」「murakumo で CI」で発火。CLAUDE.md の CI/CD 節から切り出した正本。
---

# fleet gate（詳細）

**CLAUDE.md の「CI/CD は murakumo fleet」節はここへ委譲している。** CLAUDE.md 側には
skill を読まなくても効く規則（GitHub Actions を使わない・新しい workflow を書かない・
検査は `gates.edn` に足す・**検査を書く前/緑を信じる前の 8 問**）だけが残っており、
手順・実測・罠はこの文書が正本。

以下は CLAUDE.md から**逐語で**移した本文である（2026-09-08、ADR-2609081000）。

## CI/CD は murakumo fleet。GitHub Actions を使わない（repo-wide mandatory、2026-08-05、ADR-2607300900）

**オーナー指示（2026-08-05）「github は使わない、murakumo.cloud の cdci, workflow を使う」。**
このワークスペースの CI/CD の正本は **`scripts/fleet-ci/`（murakumo mac-mini fleet）**であり、
GitHub Actions ではない。

- **新しい `.github/workflows/*.yml` を書かない。** 検査を足したいなら
  `scripts/fleet-ci/gates.edn` に 1 行足す（gate 本体は `scripts/fleet-ci/gates/*.cljs`）。
  「Actions が今は動いているから」は理由にならない — **止まったのは org 単位**で、
  動いている org も同じ理由で止まりうる。
- **Actions は repo 単位で無効化「した」——それは掃除の記録であって、今の状態ではない**
  （`scripts/github-actions-disable-sweep.cljs`）。無効化された repo では workflow ファイル
  自体は残るが inert。
  ⚠ **2026-09-06 訂正: 旧文は現在形で「無効化してある」と書いており、それが偽になる repo が
  ある。** 実測: `kotoba-lang/amu` は `GET /repos/kotoba-lang/amu/actions/permissions` が
  `{"enabled":true}` を返し、PR に 13 job のマトリクスが実走し（`test` × 3 platform /
  `browser-matrix` × 2 / `safari` / `windows-arm64` / `android-ndk` /
  `provider-qualification` × 3 / `server-kind` / `downstream-murakumo`）、**main は保護ブランチで
  PR + 2 status check を要求する**。サーバ側 `gh api .../merges` は 409 で拒否され、PR 経路が
  唯一の着地経路だった。
  **したがって「この workspace では Actions は動いていない」を前提に手順を選ばない** ——
  下記の節が自分で書いているとおり、状態は GitHub に訊くまで未測定である。訊いてから決める。**ファイルの削除には GitHub の `workflow` OAuth scope が
  要り、このワークスペースの token は持っていない**（push も Contents API も通らず、後者は
  403 でなく **404** を返すので「repo が無い」と誤読しやすい）。一方 **Actions の無効化は
  `repo` scope で通る** — 詰まっているのは「workflow ファイルを編集する」経路だけ。
  ⚠ **ただしこの制約は remote の protocol 次第で、repo ごとに違う**（実測 2026-08-19）。
  OAuth scope が効くのは **HTTPS remote への push** だけで、**SSH remote には効かない**。
  同じ日に `kotoba-lang/amu`（remote が `git@github.com:`）へは workflow 変更が普通に
  push でき、`kotoba-lang/kotoba`（`https://github.com/`）は
  `refusing to allow an OAuth App to create or update workflow ... without workflow scope`
  で弾かれた。後者は push 先に SSH URL を明示すれば通る（共有 checkout の remote 設定は
  書き換えないこと）。**「この workspace では workflow を触れない」と一般化しない** ——
  触れるかどうかは対象 repo の remote を見て決まる。
  org 単位の一括無効化（`PUT /orgs/{org}/actions/permissions`）は `admin:org` が要り、
  これも持っていない（実測 2026-08-05）。
- **なぜ「動いていない CI」より「無い CI」の方がよいか。** 2026-07-30、com-junkawasaki と
  gftdcojp の Actions は課金停止で **job が起動しなくなった**。落ちるのではなく走らないので、
  **repo は green に見えたまま何も検査されていなかった**。無効化すればチェックマーク自体が
  出ないので、誤読しようがない。

### 「Actions を止めた」は、GitHub 側に訊くまで未測定（2026-08-22、ADR-2608221300）

**workflow ファイルの有無から Actions の状態を推測しない。** `.github/` を 1 ファイルも
持たない repo が registered workflow を持つことがある（Dependabot の `dynamic/*` と、
削除済みファイルの stale entry）。それらは `ls` にも `git ls-files` にも映らないので、
**ファイル走査は見えないものを「無い」と報告する。**

- 状態を訊くのは `GET /repos/{o}/{r}/actions/permissions`。**読めなかった応答を
  「無効」と読まない** —— 403 も 404 も network error も、無効と同じ形で返ってくる。
  読めなかったなら `UNVERIFIED` であって `disabled` ではない（実測 2026-08-22、
  掃除機がまさにこれを `:already-disabled` として state に書き込んでいた）。
- **workflow が 0 本であることは無効化を省く理由にならない。** 有効なまま放置された
  repo は、workflow ファイルが 1 つ載った瞬間に走り出す。
- **課金を言うときは `/actions/runs/{id}/timing` の `billable` を引く。**
  壁時計は課金ではない（実測: 30〜45 分回る run の `billable.total_ms` が 0）。
  引いていないなら「未測定」と書く。
- 道具の分担: 現在地を測るのは `scripts/github-actions-billable-audit.cljs`
  （対象は `--repo` / `--owner` で明示。引数なしで全アカウントを歩かない）、
  止めるのは `scripts/github-actions-disable-sweep.cljs`。tree の側は fleet gate
  `root-no-github-workflows` が保つ —— ただし**その緑が言うのは「この tree は
  GitHub に workflow を渡していない」だけ**で、GitHub 側の設定は credential を
  要するのでノードでは引けない。

### fleet gate の書き方（実測した制約つき）

| gate | 要件 | 落とし穴 |
|---|---|---|
| `:jvm-test` | `deps.edn` に `:test` alias、出力に `Ran N tests` | 依存はノード側で解決する |
| `:nbb-test` | nbb のテストエントリ | 同上 |
| `:nbb-script` | `gates/*.cljs` を配って実行 | 1 ファイルで完結させる（nbb に `load-file` は無い） |

- **ノードの外向き HTTPS の有無は「実測して」使う。定数で持たない。**
  fleet-ci の README と `tick.cljs` のコメントは「ノードは tailnet だけに繋がっていて
  外向きの HTTPS が無い」と書いているが、これは **2026-07-26 に zebulun 1 台で測った値**で、
  全ノードの恒久的な性質ではない。この誤った前提のせいで、gate 種別の判断を誤り
  （maven 依存があるから `:jvm-test` は無理、と結論した）、workflow 実行では 167 本を
  不当に拒否していた。
  必要なら `curl -sS -o /dev/null -w '%{http_code}' https://repo1.maven.org/maven2/` を
  その場で叩く（`gates/github_workflow_run.cljs` の `egress?` が実例）。

  **⚠ この節自身が定数を持ってしまっていた。** 2026-08-05 の実測「到達可能な 10 ノード
  全部で 200」をここに書いた結果、それが新しい定数として引用され続けた（今日だけで
  複数の agent 指示に転記した）。**2026-08-13 に測り直すと `registry.npmjs.org` は
  8 ノードが 200、zebulun が 000。** egress は**一様ではない**。

  zebulun が今日それで問題を起こしていないのは、`:caps #{}` を持っていて cap filter に
  弾かれているからで、**設計ではなく偶然**である。cap を 1 つ足した瞬間に、egress を
  前提にした gate がそのノードで落ちる。

  **「実測して定数で持つな」と書いた節に実測値を書けば、それは定数になる。**
  日付付きで書いても同じ —— 引用する側は日付を落とす。ここに残してよいのは
  *測り方*であって、*測った値*ではない。
- **`ship-git-deps!` が運ぶのは git 依存だけ**（maven/npm は運ばない）。egress があれば
  ノードが自力で取りに行けるので普通は問題にならないが、**egress を切った運用に戻すなら
  そこが効いてくる**。
- **`:include-ext` で送る tree を絞る**（`max-ship-mb` 200）。`:min-files` は絞り込みが壊れて
  空 tree を「違反 0 件 = 合格」にしないための床。
- **署名鍵**は kagi の `fleet-agent-murakumo-ci-tip-25mbair`（compartment `personal`）と
  `~/.itonami/fleet-ci-signer-tip.pem` の両方にある（2026-08-05 に kagi 側を PEM から復元）。
  手動実行で `no such item` が出たら `FLEET_CI_SIGNER_PEM=$HOME/.itonami/fleet-ci-signer-tip.pem`
  を付ける。常駐 plist は env を渡している。
- **gate は「落ちること」を確かめてから landed とする。** 対象を 1 箇所壊したコピーで
  exit 1 になり、無改変で exit 0 になることを実際に見る。落ちない gate は劇場。

### placement を決めるのは murakumo。fleet-ci は「何を検査するか」だけを持つ（2026-08-11、ADR-2608111721）

**オーナー判断（2026-08-11）: placement authority は `murakumo.task.plan` に1本化する。**
新しい配置ロジック・ノード在庫・入場判定を `scripts/fleet-ci/` に書き足さない。

| 誰が | 何を所有するか |
|---|---|
| **murakumo**（`murakumo.task.plan` / `murakumo.fleet.inventory`、`:task-plan` / `:fleet-inventory` KIR 裏付け） | placement・在庫・入場（`admit`）・不能タスクの説明（`why-unschedulable`）・常駐の枠 |
| **scripts/fleet-ci** | `gates.edn`（何を検査するか）・gate script・署名 receipt・commit status・west pin 前進 |

fleet-ci が持ち続けるものは**全部 credential を要する operator 側の仕事**なので、
不変条件3（ノードに credential を置かない）のとおりノードへ移さない。移るのは
placement だけ。ADR-2607300900 の「CI/CD の正本は murakumo fleet であって GitHub
Actions ではない」は変わらない —— 変わるのは**どう配るかを誰が決めるか**。

**常駐スロットは 2 種で、混ぜない。**

- `:slot/anonymous` — 鍵を持たない。10 ノードどこでも置く（gate・推論・ffmpeg・WASM guest）
- `:slot/attested` — 書き込み鍵を持つ。**常時稼働の1台に固定し、台数を増やさない。
  そのホストは `probe.cljs` の `operator-hosts` で gate rotation から外す** ——
  さもないと repo から送られてきた gate コードを実行するマシンが publish 鍵を持つ

鍵を発行するのは cloud-itonami（actor DID / CACAO / scope を絞った鍵）、**枠を割り当てて
生存を見るのは murakumo**。常駐の機構は murakumo、常駐する権利は cloud-itonami、
保管は kotobase —— 判定は 3 問（今夜この機械が眠って何が止まるか / 書き込み鍵を持つか /
誰の名前で世に出るか）。

**移行期の現在地（2026-08-11）**: 切り替えは未実施。両実装に同じ batch を通して
assignments が一致することを実測してから切り替える。それまで下記の LPT が正本として
動き続ける。cost EMA は捨てず、**placement の決定器から入力の順序付け器へ降りる**
（`plan/assign` は与えられた順に greedy least-filled で置くので、LPT は「tasks を
cost 降順に並べ替える」という host 側の 1 手に還元でき、Kotoba object を足す必要が無い）。

### job の配分は自動計算する（round-robin に戻さない）

**⚠ この節は移行期の暫定実装を記述している。新しい配置ロジックの置き場は上記のとおり
murakumo 側であって、ここではない。**

`tick.cljs` の `assign` は **LPT（重い順に、投入後の完了時刻が最小の slot へ）**で、
ノードの速度を `cores` / `free-gb` / **live の load1**（`sysctl -n vm.loadavg` を実測）から、
gate の重さを過去実測の EMA（`~/.itonami/fleet-ci-cost.edn`）から出す。

以前は `(mod i (count slots))` の round-robin で、**空きも重さも見ていなかった**。
fleet のノードは CI 専用ではなく推論やマイニングと同居しているので、張り付いている
ノードに暇なノードと同じ本数が飛び、batch は全 slot の完走を待つため遅い 1 台が
全体の完了時刻を決めていた。**新しいノードを足したり用途を変えたときに手で配分を
書き換える必要は無い** — 測った値から毎 tick 計算し直す。

記録するコストは **batch 単位の上界**（batch 内の gate は並列に走るので、所要時間は
最も遅い 1 本で決まる）。LPT は相対的な重さしか使わないのでこれで足りるが、
**絶対値として引用しない**こと。

### 生成物を検査する gate は sha256 を見る（Actions では構造的に不可能だった）

committed な生成物（投影・シャード・索引）を持つ repo では、**manifest に記録された
sha256 と実ファイルを突き合わせる**。Actions 経路は committed 済みの値を読むだけで
再生成が走らないので `git diff --exit-code` が無反応になり、**内部整合を保ったまま
手編集されたファイル**を検出できなかった。fleet gate ならこれを捕まえられる。

### 赤い gate を直す前に 3 つ確かめる（repo-wide mandatory、2026-08-10、ADR-2608102000）

**`manifest/fleet-ci.edn` の fail を見て、いきなり直しにいかない。** 実測 2026-08-10、
赤い 8 種のうち **2 種は既に上流で直っており**、**2 種は私の手元の環境が原因**で、
本当に直す必要があったのは残りだけだった。順に:

1. **receipt の sha を現 tip と比べる。** gate 名は
   `test-<gate>-<sha7>-murakumo-<node>` で、**その sha 時点の判定**でしかない。
   実測: `test-amu-jdk-free-2644eb9` は赤だったが、現 tip `3b45ae11` では
   `LOCK-FRESH`。`net-kotobase` も現 tip では gate 全体が OK
   （別セッションが `93d176d` で直していた）。**古い赤を『いま壊れている』と読まない。**
2. **ローカルで gate を回すときは `<dir>` を引数の**先頭**に置く。** 多くの gate が
   `(first (remove #(str/starts-with? % "--") argv))` で tree を決めるので、
   `gate.cljs --min 10 .` と書くと **`"10"` が tree のパスになる**。
   fleet は `<dir>` を先に渡すので production では起きない。実測 2026-08-10、
   この順序ミスで 3 つの gate を「壊れている」と誤診しかけた。
3. **`npx --yes <pkg> --flag` はこのマシンでは壊れているが、ノードでは動く。**
   実測 2026-08-10: 手元 npm 11.12.1 では npx が `--classpath` を自分のフラグと
   誤解してヘルプを吐く。judah（npm 11.17.0）と simeon（10.9.8）では正常。
   **「ローカルで赤」は「fleet で赤」ではない。** 切り分けは `nbb` を直接呼ぶか、
   `ssh <node> 'npx --yes nbb …'` で実ノードに当てる。
4. **逆向きも起きる —— 「ローカルで緑」は「fleet で緑」ではない。** fleet が配るのは
   repo の tree そのままではなく、**`:include-ext` で拡張子を絞った tree** である。
   実測 2026-08-13: `gh-workflow-assoc-gapki` は手元の完全な tree で緑、fleet で赤。
   `:include-ext` が `.yml .edn .clj .cljc` だったのに対し、その repo の production
   source は `src/association_facts.kotoba` **1 本きり**で、ノードに配られた 11 ファイル
   に `src/` が無かった（`clojure -M:test` が `association_facts.kotoba (No such file or
   directory)`）。**再現するのは tree ではなく、絞り込みの結果である。**
   ローカルで gate を回すときは `:include-ext` を当ててから回す。

**gate は「fleet で 1 度緑になる」まで landed としない。** 実測 2026-08-13、赤い
8 gate のうち**両方向を見せたことがあるのは 2 つだけ**で、残る 6 つは landing 以来
一度も緑になっていない（`root-permit-index` 300 回、`root-itonami-org-id` 286 回）。
landing 前の break/unbreak は手元か stub に対して行われており、**手元で discriminate
することと、ノードの配られた tree で discriminate することは別の主張**である。
「落ちない gate は劇場」の対偶も同じく成り立つ —— **一度も緑にならない gate も、
誰も行動できないという意味で同じだけ無内容**。


### gate が要求する入力が repo に無いことがある

**その gate が読む正本が、配られる tree に入っているかを確かめる。** fleet が配るのは
**その repo の tree だけ**で、`orgs/` 配下の子リポは入らない。実測 2026-08-10:
`root-permit-index` の生成器 `gen-permit-index.cljs` は `<root>/orgs/cloud-itonami` を
読むが、`git ls-files orgs/cloud-itonami` は **0 件**（west 管理で repo 外）。
つまりこの gate は **root repo をどう直しても fleet 上では緑にならない**。
射影を検査したいなら `manifest/projection-verify.cljs` の contract（入力 hash を
固定する）に寄せるか、`orgs/` が実在する場所で回す。**入力が無い gate は、
落ちているのではなく問いを立てられていない。**


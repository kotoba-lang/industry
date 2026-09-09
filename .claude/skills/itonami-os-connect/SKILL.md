---
name: itonami-os-connect
description: 営み OS（network-awai/cloud-itonami）に、まだ繋がっていない産業の governed actor を 1 本だけ接続して着地させる。1 反復 = 1 vertical。ローカル Claude loop（cloud.itonami.bot.itonami-os-connect）が毎周これを呼ぶが、手で `/itonami-os-connect` と打ってもよい。「営み OS に産業を繋ぐ」「次の vertical を接続」「itonami os loop」で発火。
---

# 営み OS に産業を 1 本繋ぐ

**正本は superproject ADR-2608070000。** この skill はその 1 反復ぶんの手順書で、
**会話履歴を一切持たない fresh context から読める**ように書いてある。前の反復が
何をしたかは会話ではなく **tick の出力と ledger** から読む。

## 何をする反復か

営み OS は、独立した governor を持つ vertical actor を、`os.edn` の宣言と
`os/adapters/*` の shim で 1 つの面から回す。この反復の仕事はちょうど 1 つ:

> **まだ宣言されていない産業を 1 本選び、標準形 shim で接続し、
> 実際に回して拒否まで実演し、main に着地させる。**

1 反復 = 1 vertical。2 本まとめない（ADR-2607189300 のガードレール:
大きいバッチと自己申告の green が 61% 欠陥の fan-out を起こした）。

## 手順

### 0. 現在地を測る（推測しない）

```bash
cd ~/github/com-junkawasaki
git fetch origin && git merge --ff-only origin/main
nbb --classpath ".:scripts/nbb_compat" scripts/itonami-os-maturity-tick.cljs
```

tick が出す `:candidates` が**次の 1 本の候補**（M_own 降順、標準形適合のみ）。

tick は宣言を **origin/main から**読む（2026-08-08 以降）。出力 1 行目が
`宣言: origin/main の os.edn` であることを確認すること —— `⚠ working tree の
os.edn` と出ていたら **その候補は信用できない**（共有 checkout は west の pin に
留まり他セッターの WIP で dirty なので、main で既に接続済みの vertical を
「未宣言」と判定する。実測 2026-08-07 / 08 の 2 回、1 位の 4630 が両方とも既に
接続済みで、loop 自身が『次の反復も同じ罠を踏む』と ledger に書いて終わった）。
`~/.itonami/itonami-os-maturity-tick.ledger.edn` の末尾数行も読む。

**tick が `:ops-drift` を出していたら、接続より先にそれを直す。** 宣言と actor の
op がズレている面は嘘をついているので、その上に 1 本足しても嘘が増えるだけ。

### 1. 1 本選ぶ

原則は tick の順（M_own 降順）。**外してよいのは、外す理由を ledger に書けるとき
だけ**:

- 既に宣言済みの産業と**同じ営み**なら、順位より**流れが繋がる方**を採る
  （モノの営み 4711→4659→4920 はこれで選んだ）。
- 主体が人（患者・生徒・利用者）なら `:boundaries` に PII 境界を宣言し、
  連携は `:carry` しか書けない。それでよいか先に決める。

### 2. 繋ぐ（作業場は superproject の外）

```bash
git worktree add -b agent/itonami-os-<repo> /tmp/itonami-os-<repo>/orgs/network-awai/cloud-itonami origin/main
# 兄弟 repo は symlink で見せる（:local/root と classpath が相対のため）
for o in cloud-itonami gftdcojp kotoba-lang; do ln -sfn ~/github/com-junkawasaki/orgs/$o /tmp/itonami-os-<repo>/orgs/$o; done
cd /tmp/itonami-os-<repo>/orgs/network-awai/cloud-itonami && npm install
```

触るのはこの 7 箇所だけ。**`os/adapters/standard.cljc` は触らない**（147 本以上が
共有する形なので、1 本のために変えたらそこが最初のズレになる）。

| ファイル | 足すもの |
|---|---|
| `src/cloud_itonami/os/adapters/<ns>.cljc` | shim（`ops` と `vertical` の数行だけ。判断を書かない） |
| `os.edn` | vertical 宣言（`:binding :native`）。連携があれば `:wiring` |
| `src/cloud_itonami/os/hydrate.cljc` | `hydrate` の case に 1 節（edge が使う） |
| `src/cloud_itonami/edge/os_endpoints.cljc` | `native-adapter` の case に 1 節 |
| **`scripts/generate-os-site.cljs`** | **`natives` map に 1 行 + require + `<x>-repo` / `<x>-operator` + シナリオ本体** |
| `deps.edn` / `sites.edn` | `:local/root` と classpath・site entry |
| `test/cloud_itonami/os_test.cljc` | 宣言一致・coverage の数・その産業固有の性質。**`ctx` の natives map にも足す**（2 箇所） |

生成器の `natives` map は 2026-08-06 まで表から漏れていた。落ちるので気付けるが
（`os runtime: 宣言と adapter が食い違う`）、**表を信じて 6 箇所で終えると必ず踏む**。

宣言を変えたら**必ず**射影を作り直す。**2 つある** —— 片方だけだと面が 404 になる:

```bash
# ① OS registry（宣言 → kernel が読む射影）
nbb scripts/generate-os-registry.cljs && nbb scripts/generate-os-registry.cljs --check
# ② sites registry（生成した面 → edge の routing 表）
nbb scripts/generate-sites-registry.cljs && nbb scripts/run-task.cljs sites-registry-check
```

**②を忘れると、面の HTML は commit されているのに誰もそこへ行けない**（実測
2026-08-06: isic-5210 を接続して site を生成・merge したが sites registry を
再生成せず、`/{repo}/` が 404 のまま一晩残った）。さらに悪いことに、sites
registry が STALE だと `test-sites` は**検査を始める前に中断する** —— つまり
STALE は「404 になる」だけでなく「検査が走らなくなる」。実際この中断が既存の
4 失敗を隠していた。`sites-registry-check` が OK を返すまでを接続作業に含めること。

**`sites.edn` の classpath は 1 ブロックではなく、生成対象の全ブロックに足す。**
`:generator "scripts/generate-os-site.cljs"` を持つ site 宣言は 27 個あり、その
どれもが**繋いだ vertical 全部**を解決できないといけない（生成器は 1 回の実行で
全面を描くため）。`declared-classpath-can-actually-run-the-generator` がこれを
検査する。

⚠ **並行して別の反復が着地すると、両方向に穴が空く。** 実測 2026-08-08:
7320 と 3600 が同じ日に着地し、**7320 のブロックに 3600 が無く、3600 のブロックに
7320 が無い**状態になった（互いの patch が相手の追加より前に作られていたため）。
片方向だけ直しても落ち続ける。両方向を揃えること:

```bash
grep -c 'isic-<自分>/src' sites.edn   # 27 になるまで
grep -c 'isic-<相手>/src' sites.edn   # こちらも 27
```

1 本繋ぐたびに 27 ブロックへ波及するのは構造上の負債で、**恒久対応は宣言を
導出にすること**（この skill の範囲では直さない。踏んだら ledger に残す）。

### 3. 実際に回す（ここを飛ばさない）

```bash
OS_SITE_AT="<固定時刻>" nbb --classpath "<sites.edn の classpath>" scripts/generate-os-site.cljs
```

生成器は**実物の actor を回す**。次を自分の目で見る:

- 新しい vertical の op が `commit` / `escalate`（承認待ち）で出ている
- **拒否が 1 件以上ある**（生成器は拒否 0 件なら自分で exit 1 する）
- 台帳の hash 連鎖が `{:ok? true}`

`hold` しか出ないときは、たいてい**前提の op を飛ばしている**（多くの actor は
`:jurisdiction/assess` や `:contract/verify` を先に要求する）。governor を疑う前に
その repo の `phase.cljc` / `governor.cljc` を読む。

### 4. テスト

```bash
clojure -M:test        # 全体。os-test は test-runner に登録済み
```

**失敗集合をベースラインと比べる。** 既知の失敗（doctor / kotoba-xrpc / ops-keys 系）
は本件と無関係だが、**それを理由に新しい失敗を見逃さない**:

```bash
clojure -M:test 2>&1 | grep -E "^(FAIL|ERROR) in" | sort > /tmp/after.txt
diff /tmp/base.txt /tmp/after.txt   # base は origin/main の worktree で同じものを取る
```

### 4b. **`clojure -M:test` は edge Worker 側を compile しない**（2026-08-08 実測）

`src/cloud_itonami/edge/os_endpoints.cljc` は cljs ビルド（`:os-api`）でしか
compile されないので、**そこに書いた require の抜けはテストが green のまま通る。**

実測 2026-08-08: 4520-carwash を接続したとき `native-adapter` の case 節だけ足して
`require` を忘れた。`clojure -M:test` は 1,532 tests すべて green で、
**欠陥は main に merge された**。`cloud-itonami.edge.os-endpoints` を load すると
`Unable to resolve symbol: carwashops/vertical` で落ちる。

**だから接続のたびに、テストとは別にこれを回す:**

```bash
# 本命: os-api の cljs ビルド（resource-guard 経由が repo-wide の規約）
node ~/github/com-junkawasaki/scripts/resource-guard.mjs run build -- \
  npx shadow-cljs release os-api

# guard が他セッションで埋まっているとき（exit 2）の代替。これでも require 抜けは捕まる
nbb --classpath "<sites.edn のこの vertical の classpath>" \
  -e '(require (quote [cloud-itonami.edge.os-endpoints])) (println "LOAD OK")'
```

`shadow-cljs release worker` ではない —— **`:worker` という build id は存在しない**
（`shadow-cljs.edn` の build は `:os-api` `:edge-api` `:sites-api` 等）。存在しない
id を指定すると `no build with id` で落ち、それを「ビルドが壊れている」と誤読しやすい。

**この検査を飛ばしてよい反復は無い。** 接続作業は必ず `os_endpoints.cljc` を触るので、
毎回 require が 1 本増える。

### 5. 着地（rebase も force-push もしない）

```bash
git add -A && git commit -m "os: <産業> を接続（ADR-2608070000）"
git push origin agent/itonami-os-<repo>
gh api repos/cloud-itonami-app/... # ← 実際は network-awai/cloud-itonami
gh api repos/network-awai/cloud-itonami/merges -f base=main -f head=agent/itonami-os-<repo> -f commit_message="..."
```

superproject 側は `manifest/west.yml` の pin を **当該 entry だけ**前進させる
（`nbb scripts/gen-west-manifest.cljs --entry cloud-itonami`。wholesale な再生成は禁止）。

⚠ **`--entry` でも生成器が他の差分を巻き込むことがある。** `repos.edn` に別セッションが
足した未登録 repo があると、その entry も一緒に書かれる（実測 2026-08-06:
`cloud-itonami-kekkai` が混入し diff が 7 行になった）。**diff を必ず目で見て**、当該
1 行だけでないなら GitHub API の single-entry commit で該当行だけを書く。

### 5b. デプロイまでが完了条件（面は git に入っただけでは 404）

```bash
# checkout が origin/main を含むこと（deploy guard が要求する）
git fetch origin && git merge --ff-only origin/main
npx wrangler pages deploy public --project-name=cloud-itonami --branch=main --commit-dirty=true
curl -o /dev/null -w '%{http_code}\n' https://cloud-itonami.itonami.cloud/<repo>/   # 200 を確認
```

**`:local/root` 依存が west pin とズレていると deploy guard が止める。** その依存が
**clean かつ pin の後ろ**なら `west update --fetch smart <name>` で足りるが、**dirty か
diverged なら触らない** —— pin の clean な worktree を作り、兄弟 org を symlink した
隔離レイアウトからデプロイする（実測 2026-08-07: `kyoninka` が diverged だったので
この経路を使った）。

後片付けまでが完了条件: worktree 削除 → ローカル branch 削除 → remote branch 削除。

### 6. ledger に 1 行足す

`~/.itonami/itonami-os-connect.ledger.edn`（追記のみ、1 行 1 EDN）:

```clojure
{:at "..." :repo "cloud-itonami-isic-NNNN" :ns "..." :ops N
 :wiring [...] :merged "<commit>" :verified "生成器 N op / 拒否 N 件 / 台帳 ok"
 :not-done "..." :chose-why "tick 1 位でない場合の理由"}
```

**`:not-done` を空にしない。** 何を残したかが次の反復の入力になる。

## やらないこと

- **拒否を実演せずに「接続した」と書かない。** 拒否が本物であることは、拒否を
  実演しないと示せない（ADR-2607122300 §1 系の規律）。
- **`:unbound` を黙って `:native` にしない。** 中核に JVM 専用ファイルが残って
  いるなら、それを `.cljc` に割る作業が先で、それは 1 反復に収まらないことが多い。
  収まらないなら**接続しない**で、tick の `:candidates` から次を採る。
- **境界を跨ぐ連携に `:invoke` を書かない。** 書くと `wiring/compile-rules` が
  load 時に throw する（ADR-2607131000 の機械化）。落ちたら宣言が間違っている。
- **境界が空でも `:invoke` を書けるとは限らない。** `compile-rules` を通ることと
  実際に動くことは別。受け手の governor が『対象が**自分の台帳で**独立に登録・
  検証済みであること』を要求するなら、`:mint-from-rule` が作る新しい主体は
  受け手の store に存在しないので**必ず hold する** —— 面には「繋がっているのに
  何も通らない」という一番わかりにくい形で出る。実測 2026-08-07: 5210→5229 を
  `:invoke` で書いたら、mint された `custody-transfer->forwarding-record:tank-1`
  が 5229 の台帳に無く `:shipment-unverified` で落ち続けた。**受け手が intake 系の
  op（自分で record を作る op）を持っていなければ `:carry`** にする。判断材料は
  「受け手はこの主体を自分で作れるか」の 1 点。
- **繋ぐ前に、その actor が `:subject` を使っているか確かめる。** `standard/->request`
  が作るのは `{:op op :subject subject}` で、接続済みの vertical はすべてこの形。
  `:target-id` など別の名前で主体を受け取る actor をそのまま繋ぐと、キーが nil の
  まま governor の第 1 検査に入り、**登録・検証済みの対象でも全 op が hold する**
  （実測 2026-08-07: isic-5229）。`grep ':keys \[op ' <repo>/src/*/governor.clj*`
  で 1 秒で分かる。翻訳を shim に書くのは可（判断は書かない）だが、**不適合として
  ledger と shim の docstring に必ず残す** —— 恒久的には actor 側が寄せる番。
- **`:invoke` を書くなら `:wiring/subject :mint-from-rule` を必ず添える。**
  無いと load 時に落ちる（実行時に必ず `:malformed-request` で拒否されるものを
  宣言できてしまわないため）。
- 候補が 1 本も無ければ**何もせず終える**。無いのに作らない。

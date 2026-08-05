---
name: itonami-os-connect
description: 営み OS（network-awai/cloud-itonami）に、まだ繋がっていない産業の governed actor を 1 本だけ接続して着地させる。1 反復 = 1 vertical。ローカル Claude loop（com.gftd.itonami-os-connect）が毎周これを呼ぶが、手で `/itonami-os-connect` と打ってもよい。「営み OS に産業を繋ぐ」「次の vertical を接続」「itonami os loop」で発火。
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
`~/.gftd/itonami-os-maturity-tick.ledger.edn` の末尾数行も読む。

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

触るのはこの 6 箇所だけ。**`os/adapters/standard.cljc` は触らない**（147 本以上が
共有する形なので、1 本のために変えたらそこが最初のズレになる）。

| ファイル | 足すもの |
|---|---|
| `src/cloud_itonami/os/adapters/<ns>.cljc` | shim（`ops` と `vertical` の数行だけ。判断を書かない） |
| `os.edn` | vertical 宣言（`:binding :native`）。連携があれば `:wiring` |
| `src/cloud_itonami/os/hydrate.cljc` | `hydrate` の case に 1 節（edge が使う） |
| `src/cloud_itonami/edge/os_endpoints.cljc` | `native-adapter` の case に 1 節 |
| `deps.edn` / `sites.edn` | `:local/root` と classpath・site entry |
| `test/cloud_itonami/os_test.cljc` | 宣言一致・coverage の数・その産業固有の性質 |

宣言を変えたら**必ず**射影を作り直す:

```bash
nbb scripts/generate-os-registry.cljs && nbb scripts/generate-os-registry.cljs --check
```

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

### 5. 着地（rebase も force-push もしない）

```bash
git add -A && git commit -m "os: <産業> を接続（ADR-2608070000）"
git push origin agent/itonami-os-<repo>
gh api repos/cloud-itonami-app/... # ← 実際は network-awai/cloud-itonami
gh api repos/network-awai/cloud-itonami/merges -f base=main -f head=agent/itonami-os-<repo> -f commit_message="..."
```

superproject 側は `manifest/west.yml` の pin を **当該 entry だけ**前進させる
（`nbb scripts/gen-west-manifest.cljs --entry cloud-itonami`。wholesale な再生成は禁止）。

後片付けまでが完了条件: worktree 削除 → ローカル branch 削除 → remote branch 削除。

### 6. ledger に 1 行足す

`~/.gftd/itonami-os-connect.ledger.edn`（追記のみ、1 行 1 EDN）:

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
- **`:invoke` を書くなら `:wiring/subject :mint-from-rule` を必ず添える。**
  無いと load 時に落ちる（実行時に必ず `:malformed-request` で拒否されるものを
  宣言できてしまわないため）。
- 候補が 1 本も無ければ**何もせず終える**。無いのに作らない。

---
name: kaiyu-kaizen
description: 回遊（アクセス解析）の計測から出た「答えるべき問い」を 1 件だけ、cloud-itonami の承認キューに提案として着地させる。ローカル Codex loop（cloud.itonami.bot.kaiyu-kaizen）が候補のある周だけこれを呼ぶが、手で `/kaiyu-kaizen` と打ってもよい。「回遊から kaizen」「アクセス解析の issue」「kaiyu kaizen loop」で発火。
---

# kaiyu-kaizen — 測ったことから、答えるべき問いを 1 件

## この skill が引き受ける範囲

`scripts/kaiyu-kaizen-tick.cljs` が既に測り、`kaiyu.diagnose` が既に判定した
**候補 1 件**を受け取り、(a) 数字が本物か確認し、(b) 提案として着地させ、
(c) 証跡を残す。**探索と判定はやり直さない** —— それは機械の仕事で、周ごとに
基準が揺れるのを防ぐためにそちらに置いてある。

引数は tick が出した候補 JSON（`{site, tenant, top, issue}`）。引数無しで呼ばれた
ときは自分で 1 周測る:

```bash
nbb --classpath ".:scripts/nbb_compat:orgs/kotoba-lang/kaiyu/src" \
    scripts/kaiyu-kaizen-tick.cljs --json
```

## 1 反復 = 1 issue

まとめて出さない。issue は「答えるべき問い」であって作業指示ではなく、5 件積むと
どれも読まれない。**queue を読まれなくすることが、計測から得られるものを全部
無駄にする唯一の方法**である。

## 手順

1. **候補の数字を、根拠に当たって確認する。** `top.evidence` の数（samples /
   inbound / under-10s など）が、そのサイトの read face の生の応答と一致するか。
   一致しなければ **提案しない** —— tick の正規化がずれている可能性の方が高く、
   その場合の正しい成果物は issue ではなく tick の修正。

2. **`:blocked`（計測が疑わしい）だったら、issue の中身は「計測を直す」になる。**
   サイトの所見は出さない（diagnose が short-circuit している）。この場合、
   本当に直すべきものが自分の手元にあることが多い（beacon・read face・migration）
   ので、**提案の前にまず現物を確認する**。

3. **提案を投げる。**

   ```bash
   KEY=$(security find-generic-password -a cloud-itonami \
         -s "cloud-itonami KAIZEN_INGRESS_KEY" -w)
   curl -sS -X POST -H 'content-type: application/json' \
        -H "authorization: Bearer $KEY" \
        -d @issue.json \
        https://itonami.cloud/api/<org>/<repo>/kaizen
   ```

   - `202 proposed` … 新しく提案された
   - `200 already-open` … **正常**。同じ window の同じ問いは 1 件だけ。
     これが返ったら、その周はそこで終わり（別の候補に乗り換えない —— それは
     「出せるものを出す」であって「いちばん答えるべき問い」ではない）

4. **証跡を 1 行残す。** `90-docs/kaizen/kaiyu-kaizen.ledger.edn` に追記
   （loop が起こした場合は loop 側が書く。手で回したときはここで書く）。

## やらないこと

- **原因を書かない。** 回遊の計測は counts と dates だけで訪問者を識別しない。
  どこで注意が止まり、どこに届いていないかは分かるが**なぜ**は分からない。
  `kaiyu.diagnose/->issue` は本文にその限界を明記する —— それを削らない
- **修正を提案しない。** issue は問いで終わる。答えは人が決める
- **承認しない。** 提案は `:proposed` のまま人の承認を待つ。approve は cockpit の
  仕事で、この skill は鍵を持っていない（narrow ingress key は提案しかできない）
- **候補が無い周に何かを出さない。** 健全な周は健全と記録して終わる

## 不変条件

- 提案の id は **tick が出したものをそのまま使う**（`kaizen:<site>:<finding>:<window>`）。
  作り直すと重複排除が壊れる
- secret は Keychain から**1 件だけ狙い撃ちで**読む（総当たり禁止、AGENTS.md 安全床⑦）
- 読めなかったサイトを「問題が無い」とも「問題がある」とも書かない

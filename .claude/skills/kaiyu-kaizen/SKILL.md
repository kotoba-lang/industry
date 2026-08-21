---
name: kaiyu-kaizen
description: 回遊（アクセス解析）の計測から出た「答えるべき問い」を 1 件だけ、cloud-itonami の承認キューに提案として着地させる。ローカル Claude loop（com.gftd.kaiyu-kaizen）が候補のある周だけこれを呼ぶが、手で `/kaiyu-kaizen` と打ってもよい。「回遊から kaizen」「アクセス解析の issue」「kaiyu kaizen loop」で発火。
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

3. **投げる前に、同じ問いが既に提案済みでないか ledger で確かめる。**

   **ingress は window をまたぐ重複を止めない。** dedup の鍵は id で、id は
   `kaizen:<site>:<finding>:<window>` —— window を含む。だから同じ問いが翌日
   新しい id で再発火し、**`200 already-open` ではなく `202 proposed` が返る**。
   止まったように見えないまま queue に 2 通目が積まれる。

   ```bash
   nbb --classpath ".:scripts/nbb_compat" -e '
   (ns g (:require [clojure.edn :as edn] ["fs" :as fs]))
   (def site "<site>")
   ;; ファイル全体を 1 度に読む。**1 行 1 form で読まない** —— 台帳には複数行に
   ;; pretty-print された entry が混在し、行単位の reader はそれを黙って落とす。
   (def es (edn/read-string (str "[" (str (fs/readFileSync "90-docs/kaizen/kaiyu-kaizen.ledger.edn" "utf8")) "]")))
   (println "ENTRIES" (count es))            ; ← 件数を必ず出す。evidence floor
   (doseq [e es :when (and (= site (:site e)) (= :proposed (:status e)))]
     (println "OPEN" (:at e) (:issue-id e)))'
   ```

   **grep で引かない。** documented guard は 2026-08-21 まで
   `grep -n "kaizen:<site>:<finding>:"` で引いて「当たった entry の `:status` を
   見る」と書いていたが、**複数行 entry では `:status` が開き括弧の行に、
   `:issue-id` が別の行に載る**ので、grep が返す行に `:status` は無い。実測: その日
   open だった 2 通はどちらも複数行 entry で、`grep -c '<id>.*:status'` = 0 ——
   **いちばん答えてほしい entry（open な提案）についてだけ答えられない guard**
   だった。1 行 1 form で reader に通す形も同じく壊れる（542 行 → 506 entry と
   報告し、複数行 36 件を落として `:proposed` を **0 件**と答える。真値は 2 件）。
   **読めなかったことが、読んで問題が無かったことと同じ値になる。**

   出力を prefix（window を除いた 3 節）と **site 全体**の両方で見る:

   - prefix に `:status :proposed` が 1 件でもある → **提案しない。** 問いの文言が
     同じなら、人が答えるべき内容も同じで、2 通目は queue の深さを増やすだけ。
     `:status :not-proposed-duplicate-question` で記録してその周は終わり
   - prefix は 0 件でも、**site 全体に同じ section について open な問いが在る**なら
     やはり提案しない —— **finding 節そのものが回る**（`-stopped-while-sibling-collects`
     → `-empty-while-measured` は、最後の行が窓から出ただけで section も問いも
     変わっていない）。prefix guard はこの回転を止められない
   - どちらも無い → 次へ

   **新しい行は 1 行 EDN で書く**（`pr-str` した map を 1 行）。複数行に
   pretty-print すると、上記のとおり後続の周の guard を壊す。

   **これは重複を数える guard であって、dedup の意味論の決定ではない。**
   後者（open な間は (site, finding) で dedup して evidence を更新するか、
   ingress 側が already-open を返すか）は人に渡してある設計判断で、
   ここで勝手に決めない。

4. **提案を投げる。**

   ```bash
   KEY=$(security find-generic-password -a cloud-itonami \
         -s "cloud-itonami KAIZEN_INGRESS_KEY" -w)
   curl -sS -X POST -H 'content-type: application/json' \
        -H "authorization: Bearer $KEY" \
        -d @issue.json \
        https://itonami.cloud/api/<org>/<repo>/kaizen
   ```

   - `202 proposed` … 新しく提案された。**重複していないことの証拠ではない**
     （上記のとおり id が違えば重複でも 202 が返る）
   - `200 already-open` … 同じ **id** が既に open。これが返ったら、その周は
     そこで終わり（別の候補に乗り換えない —— それは「出せるものを出す」であって
     「いちばん答えるべき問い」ではない）

   **POST は取り消せない。** ingress の `allowedMethods` は `["POST","OPTIONS"]`
   だけで、narrow key に withdraw も close も無い（queue の読み書きは cockpit の
   CACAO 認証が要る）。誤って積んだ 1 通は、人が cockpit で閉じるまで残る ——
   だから step 3 は POST の後ではなく前にやる。

5. **証跡を 1 行残す。** `90-docs/kaizen/kaiyu-kaizen.ledger.edn` に追記
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
- **ledger を読むのは POST の前**（step 3）。証跡を書く段（step 5）で初めて読むと、
  重複だと分かるのが投げた後になる。2026-08-13 に実際にそうなった —— 直前の
  3 周が手で守っていた「同じ問いは 2 度出さない」は ledger の散文にしか無く、
  手順に書かれていなかったので、読む順番が変わった 1 周で破れた
- secret は Keychain から**1 件だけ狙い撃ちで**読む（総当たり禁止、CLAUDE.md 安全床⑦）
- 読めなかったサイトを「問題が無い」とも「問題がある」とも書かない

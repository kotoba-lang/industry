(ns scripts.itonami-maturity-freshness
  "成熟度向上 loop の『計測値はまだ信用できるか』の判定（純関数のみ）。

  ## なぜ日数の閾値では足りなかったか

  もとの tick は「datoms が commit された日から `stale-after-days`(7) 日
  経ったか」だけを見ていた。これは二つのことを取り違えている:

    1. **粒度** —— 日単位に丸めるので、同じ日に着地した仕事は必ず `0 日` に
       なり『新鮮』と読まれる。
    2. **基準点** —— 経過時間は「計測がどれだけ古いか」しか答えない。loop が
       知りたいのは **「計測は自分が直前にやった仕事を見ているか」** で、
       これは時計ではなく ledger にしか書いていない。

  実測（2026-08-09、この ns が生まれた周）: marine-insurance の axis-docs は
  02:14 JST に main へ着地したが、計測は 00:56 JST のものだった。ずれは 78 分
  なので `datoms-age-days` は 0、`:datoms-stale?` は false。tick は着地済みの
  仕事を『README も ADR も quickstart も無い（0bp）』と読み、**同じ repo の
  同じ軸をもう一度上げろ**と言った —— 実ファイルは README 7,192 B /
  operator-quickstart 6,472 B / ADR 1 件が既に在る。そのまま従えば水増しになる。

  これは一度きりの取りこぼしではない。ledger 上、直近の周は 3 回続けてこの穴に
  落ちており、そのたびに人が手で気付いて別の repo を選び直していた
  （前周の `:not-done` が『tick 側の鮮度判定を ledger の最終着地時刻と
  突き合わせる形に変える案は未着手』と名指ししている）。

  ## 判定

  計測値が blind なのは、次のどちらか:

    :blind-to-own-work  計測の**後**に、この loop 自身が軸上げを main へ
                        着地させている。順位は自分の直前の仕事を見ていない。
    :too-old            誰も着地させていなくても、他セッションや外部の変化で
                        古くなる。従来の日数の床（これは残す）。

  片方だけでは足りない —— 前者は速いが loop 自身の仕事しか見えず、後者は
  何でも捉えるが遅い。"
  (:require [clojure.string :as str]))

(defn parse-instant
  "ISO-8601 → epoch ms。読めなければ **nil**。

  0 に丸めない —— 0 は 1970 年であり『とても古い計測』として通ってしまう。
  読めないことは、読めないこととして上へ返す。"
  [s]
  (when (string? s)
    (let [t (js/Date.parse s)]
      (when-not (js/isNaN t) t))))

(defn axis-raise?
  "この ledger 行は『軸を 1 つ上げて main に着地させた周』か。

  **測り直しの周を必ず外す。** ledger は datoms を commit した *後* に書くので、
  測り直しの行の `:at` は必ずその計測値より新しくなる。外さないと、測り直した
  直後の tick が『計測が自分の仕事を見ていない』と言い、loop は測り直しから
  二度と出られない（測る → stale と言われる → また測る）。測り直しの周は
  `:axis :none-remeasure` を書くので、`axis-` で始まる軸だけを数える。

  `:outcome` では判定しない。知りたいのは『main に載ったか』であって周の
  自己申告ではないので、`:merged` commit の有無で見る —— 途中で止めて
  `:partial` にした周でも、軸上げが main に載っているなら計測はそれを
  見ていない。"
  [{:keys [merged axis]}]
  (boolean (and merged
                (keyword? axis)
                (str/starts-with? (name axis) "axis-"))))

(defn unseen-landings
  "計測値が commit された後に着地した軸上げを、古い順に返す。

  `generated-at` が数でなければ比較の基準が無いので空 —— 『無かった』ではなく
  『判定できなかった』であり、それは `freshness` が別の理由として扱う。"
  [entries generated-at]
  (if-not (number? generated-at)
    []
    (->> entries
         (filter axis-raise?)
         (keep (fn [e]
                 (when-let [t (parse-instant (:at e))]
                   (when (> t generated-at)
                     (assoc e :at-ms t)))))
         (sort-by :at-ms)
         vec)))

(defn freshness
  "計測値を信用してよいか。

  入力はすべて呼び出し側が測って渡す（この ns は時計も git も ledger も
  読まない）。返すのは:

    {:stale? bool
     :reason :fresh | :blind-to-own-work | :too-old | :unknown-generation
     :age-days num-or-nil
     :unseen [ledger 行 …]}

  `:unknown-generation`（git log が答えなかった等）は **stale とも fresh とも
  言わない**。stale にすると計測を直しても抜けられない永久ループになり、fresh に
  すると今回直した嘘をもう一度つくことになる。判定できなかったことを、そのまま
  呼び出し側へ返して表示させる。"
  [{:keys [generated-at now entries stale-after-days]}]
  (let [age (when (and (number? generated-at) (number? now))
              (/ (- now generated-at) 86400000.0))
        unseen (unseen-landings entries generated-at)]
    (cond
      (not (number? generated-at))
      {:stale? false :reason :unknown-generation :age-days nil :unseen []}

      ;; 日数の床より先に見る。こちらの方が具体的で、次の 1 手も強い
      ;; （『何日か経った』ではなく『この commit を見ていない』と言える）。
      (seq unseen)
      {:stale? true :reason :blind-to-own-work :age-days age :unseen unseen}

      (and (number? stale-after-days) (number? age) (> age stale-after-days))
      {:stale? true :reason :too-old :age-days age :unseen []}

      :else
      {:stale? false :reason :fresh :age-days age :unseen []})))

(defn explain
  "`freshness` の結果を、tick の 1 行表示にする。"
  [{:keys [reason unseen age-days]}]
  (case reason
    :fresh (str "計測値の鮮度: " (js/Math.round age-days) " 日")
    :too-old (str "計測値の鮮度: " (js/Math.round age-days) " 日（STALE: 床 "
                  "を超えた）")
    :blind-to-own-work
    (str "計測値の鮮度: " (js/Math.round age-days) " 日（STALE: 計測の後に "
         "この loop 自身が " (count unseen) " 周ぶん着地させている）")
    :unknown-generation
    "計測値の鮮度: **不明**（datoms の commit 時刻が読めない）— 順位を信用する前に確かめる"
    (str "計測値の鮮度: " (pr-str reason))))

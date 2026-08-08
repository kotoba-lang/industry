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

(defn landing-instant
  "この行が main に着地した瞬間（epoch ms）と、その出所。

  **`:at` は最後の手段。** `:at` は周が自分で書く文字列であって観測ではない。
  実測（2026-08-09）: `m365-ingest` の行は `\"2026-08-09T00:30:00Z\"` と
  書いてあったが、その merge commit `c70a7114` の実時刻は
  `2026-08-08T15:14:18Z` —— JST の壁時計に `Z` を付けた 9 時間先の値だった。
  9 時間先の『着地』はどんな計測よりも新しいので、**測り直しても永久に
  『計測が自分の仕事を見ていない』と言われ続ける**（実際にこの周、測り直しを
  main に着地させた直後の tick がまだ STALE と答えた）。

  merge commit の時刻は git が持つ事実なので、呼び出し側が解決できたなら
  （`:landed-at-ms`）そちらを使う。これは `generated-at` が file の mtime を
  拒んで `git log -1 --format=%ct` を使うのと **同じ原則を着地側に当てた**
  だけで、新しい判断ではない。"
  [{:keys [landed-at-ms at]}]
  (if (number? landed-at-ms)
    {:ms landed-at-ms :source :commit}
    (when-let [t (parse-instant at)]
      {:ms t :source :at})))

(defn classify-landings
  "軸上げの行を『計測が見ていない』と『時刻が使えない』に分ける。

  **未来の時刻は見落としの証拠にならない** —— 着地は未来には起きない。commit を
  解決できずに `:at` へ落ちた行が壊れた時刻を持っていると、それだけで loop は
  測り直しから出られなくなるので、`now` より後の行は `:unseen` に数えない。

  ただし **黙って捨てない**。`:suspect` として返し、tick に出させる —— 隠すと、
  次に同じ壊れ方をしたとき誰も気づかない（この欠陥が 1 周まるごと溶かしたのは、
  ledger の時刻が静かに嘘をついていたからである）。

  `now` が数でなければ未来判定はできないので、その篩は掛けない（`nil` を
  『未来ではない』とみなすのではなく、判定しない）。"
  [entries generated-at now]
  (if-not (number? generated-at)
    {:unseen [] :suspect []}
    (let [rows (->> entries
                    (filter axis-raise?)
                    (keep (fn [e]
                            (when-let [{:keys [ms source]} (landing-instant e)]
                              (assoc e :at-ms ms :at-source source))))
                    vec)
          future? (fn [e] (and (number? now) (> (:at-ms e) now)))]
      {:unseen (->> rows
                    (remove future?)
                    (filter #(> (:at-ms %) generated-at))
                    (sort-by :at-ms)
                    vec)
       :suspect (->> rows (filter future?) (sort-by :at-ms) vec)})))

(defn unseen-landings
  "計測値が commit された後に着地した軸上げを、古い順に返す。

  `generated-at` が数でなければ比較の基準が無いので空 —— 『無かった』ではなく
  『判定できなかった』であり、それは `freshness` が別の理由として扱う。

  `now` を渡さない 2 引数版は未来判定をしない（`classify-landings` を見よ）。"
  ([entries generated-at] (unseen-landings entries generated-at nil))
  ([entries generated-at now]
   (:unseen (classify-landings entries generated-at now))))

(defn freshness
  "計測値を信用してよいか。

  入力はすべて呼び出し側が測って渡す（この ns は時計も git も ledger も
  読まない）。返すのは:

    {:stale? bool
     :reason :fresh | :blind-to-own-work | :too-old | :unknown-generation
     :age-days num-or-nil
     :unseen  [ledger 行 …]
     :suspect [ledger 行 …]}   ; 時刻が未来で使えなかった行

  `:suspect` は判定には使わないが **必ず返す**。呼び出し側に出させることで、
  ledger の時刻が壊れていることが黙って埋もれないようにする。

  `:unknown-generation`（git log が答えなかった等）は **stale とも fresh とも
  言わない**。stale にすると計測を直しても抜けられない永久ループになり、fresh に
  すると今回直した嘘をもう一度つくことになる。判定できなかったことを、そのまま
  呼び出し側へ返して表示させる。"
  [{:keys [generated-at now entries stale-after-days]}]
  (let [age (when (and (number? generated-at) (number? now))
              (/ (- now generated-at) 86400000.0))
        {:keys [unseen suspect]} (classify-landings entries generated-at now)]
    (cond
      (not (number? generated-at))
      {:stale? false :reason :unknown-generation :age-days nil :unseen [] :suspect suspect}

      ;; 日数の床より先に見る。こちらの方が具体的で、次の 1 手も強い
      ;; （『何日か経った』ではなく『この commit を見ていない』と言える）。
      (seq unseen)
      {:stale? true :reason :blind-to-own-work :age-days age :unseen unseen :suspect suspect}

      (and (number? stale-after-days) (number? age) (> age stale-after-days))
      {:stale? true :reason :too-old :age-days age :unseen [] :suspect suspect}

      :else
      {:stale? false :reason :fresh :age-days age :unseen [] :suspect suspect})))

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

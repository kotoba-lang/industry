(ns spotwork.time
  "単発シフトの時刻計算。**wall-clock だけを扱い、タイムゾーン計算を一切しない。**

  スキマバイトの求人が実際に持っている形（`:offer/date` \"2026-08-30\" +
  `:offer/start` \"10:00\" + `:offer/end` \"19:00\" + `:offer/tz` \"Asia/Tokyo\"）を
  そのまま入力にする。ISO offset 付き instant を受けて自前で解析すると、
  夏時間・閏秒・offset 表記ゆれのぶんだけ**間違える経路が増える**うえ、
  労基法の判定（8 時間 / 休憩 / 深夜業）はどれも**現地の壁時計**で定義されている
  ので、instant に直すこと自体が要らない変換になる。

  ## 返り値の規約（この名前空間の全関数）

  読めなかった入力に対して**それらしい数値を返さない**。`nil` を返す。
  呼び手（governor）はそれを `:not-measured` に写し、`:pass` に畳まない
  （ADR-2608136000「測れなかった検査が、測って問題が無かった検査と同じ値を
  返す」）。0 を返してはならない —— 0 分は「深夜業なし」と同じ顔をする。"
  (:require [clojure.string :as str]))

(def ^:const day-minutes 1440)

(defn parse-hhmm
  "\"HH:MM\" → 0..1439 の分。形が違えば nil（0 ではない）。

  `js/parseInt` は \"10abc\" を 10 と読むので、**先に形を固定してから**数に直す。"
  [s]
  (when (string? s)
    (when-let [[_ h m] (re-matches #"([01]?\d|2[0-3]):([0-5]\d)" (str/trim s))]
      (let [hh #?(:clj (Integer/parseInt h) :cljs (js/parseInt h 10))
            mm #?(:clj (Integer/parseInt m) :cljs (js/parseInt m 10))]
        (+ (* 60 hh) mm)))))

(defn segments
  "start/end（\"HH:MM\"）を [[from to] ...] の分区間に開く。終了が開始より前なら
  日跨ぎとして 2 区間に割る。

  - 読めない → nil
  - start == end → nil。**0 分とも 24 時間とも決められない**ので、どちらかに
    決めてしまわない（`:not-measured` として上へ返す）。"
  [start end]
  (let [s (parse-hhmm start)
        e (parse-hhmm end)]
    (cond
      (or (nil? s) (nil? e)) nil
      (= s e) nil
      (< s e) [[s e]]
      :else [[s day-minutes] [0 e]])))

(defn span-minutes
  "拘束時間（休憩込み）の分。読めなければ nil。"
  [start end]
  (when-let [segs (segments start end)]
    (reduce + 0 (map (fn [[a b]] (- b a)) segs))))

(defn- overlap-1 [[a b] [c d]]
  (max 0 (- (min b d) (max a c))))

(defn overlap-minutes
  "2 つの区間列の重なりの分。片方でも nil なら nil。"
  [segs-a segs-b]
  (when (and (seq segs-a) (seq segs-b))
    (reduce + 0 (for [x segs-a y segs-b] (overlap-1 x y)))))

(defn working-minutes
  "労働時間 = 拘束時間 − 休憩。休憩が数でない / 負 / 拘束を超えるなら nil。

  「休憩が書かれていない」を 0 と読まない —— 未申告の求人を
  「休憩ゼロで適法」とも「違法」とも判定できないため、上へ nil を返して
  `:not-measured` にする。"
  [start end break-minutes]
  (when-let [span (span-minutes start end)]
    (when (and (number? break-minutes)
               (not (neg? break-minutes))
               (<= break-minutes span))
      (- span break-minutes))))

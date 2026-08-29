(ns spotwork.fingerprint
  "求人 1 件の内容指紋。**再審査が要るかどうかを決めるためだけ**に使う。

  暗号学的な保証は無い（FNV-1a 32bit）。ここで欲しいのは「この求人は前に
  審査した時と同じ内容か」への決定論的な答えであって、改竄検知ではない。
  改竄検知が要る面では kotobase の CID を使う —— **その用途にこれを流用しない。**

  依存を足さずに JVM と cljs で**同じ値**を出すために、乗算は 32bit に畳む
  （`Math.imul` / `unchecked-int`）。畳まないと JS 側が倍精度に落ちて
  静かに別の値になる。"
  (:require [clojure.string :as str]))

(defn canonical-str
  "key 順に依らない文字列表現。map は key の文字列表現で整列し、set も整列する。
  これをしないと同じ内容の求人が入力順で別の指紋になる。"
  [x]
  (cond
    (map? x) (str "{" (str/join "," (for [[k v] (sort-by (comp pr-str key) x)]
                                      (str (pr-str k) " " (canonical-str v)))) "}")
    (set? x) (str "#{" (str/join "," (map canonical-str (sort-by pr-str x))) "}")
    (sequential? x) (str "[" (str/join "," (map canonical-str x)) "]")
    :else (pr-str x)))

(defn- mul32 [a b]
  #?(:clj (unchecked-int (* (unchecked-int a) (unchecked-int b)))
     :cljs (js/Math.imul a b)))

(defn- hex32 [h]
  #?(:clj (let [u (bit-and (long h) 0xFFFFFFFF)] (Long/toHexString u))
     :cljs (.toString (unsigned-bit-shift-right h 0) 16)))

(defn- code-unit
  "i 文字目の UTF-16 コードユニット（0..65535）。JVM の `.charAt` も JS の
  `.charCodeAt` も UTF-16 単位を返すので、両者は同じ値になる。"
  [s i]
  #?(:clj (int (.charAt ^String s i))
     :cljs (.charCodeAt s i)))

(defn of
  "任意の値 → `\"fnv1a32-xxxxxxxx\"`。

  ⚠ **コードユニットを 1 バイトに切り詰めない。** 以前はここで `0xFF` を取って
  いたが、それだと `あ`（U+3042）と `B`（U+0042）が同じ値になり、**日本語を
  含む求人（`:offer/employer-label` など）が書き換わったのに指紋が動かない**
  経路ができる。指紋が動かないことは tick にとって『再審査は要らない』と
  同義なので、これは静かに検査を飛ばすバグだった。上位バイトと下位バイトを
  順に混ぜる（UTF-16 単位なので JVM と cljs で同じ値になる）。"
  [x]
  (let [s (canonical-str x)
        n (count s)]
    (loop [i 0 h (unchecked-int 2166136261)]
      (if (>= i n)
        (str "fnv1a32-" (hex32 h))
        (let [cu (code-unit s i)
              h (mul32 (bit-xor h (bit-and cu 0xFF)) 16777619)
              h (mul32 (bit-xor h (bit-and (unsigned-bit-shift-right cu 8) 0xFF))
                       16777619)]
          (recur (inc i) h))))))

(defn of-offer
  "求人の**内容**の指紋。`:offer/id` は内容ではないので外す —— id が同じまま
  賃金や時間が書き換わったことを検出したい。"
  [offer]
  (of (dissoc offer :offer/id)))

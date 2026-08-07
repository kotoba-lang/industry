#!/usr/bin/env nbb
;; block-sizing-pad-alignment-check.cljs — ADR-2608070400 D6 の機械検査。
;;
;; 展開済みの `kotoba-lang/kotobase-peer` tree を受け取り、
;; `src/kotobase_peer/block_sizing.cljc` の `size-classes` が、client 側封緘の
;; padding bucket 階段に載るかを検証する。
;;
;; ## なぜこの gate が要るか
;;
;; ADR-2608070400 D6 は「長さ秘匿(padding)が実コストの主項で、AEAD の 0.23% に対し
;; index block +34% / definition block +150%。block target を pad bucket 直下に
;; 揃えれば padding 比はほぼ消える。pad bucket の上限は 65,536 B で、それを超える
;; block は ciphertextBlob 送りになり inline padding の経路を外れる」と決めた。
;;
;; **これは prose では守れない。** block size class は性能都合(`kotobase-peer` の
;; Merkle run controller)で決まり、pad bucket は暗号都合(`kotoba-lang/pqh` の
;; ISO/IEC 7816-4 スキーム)で決まる。両者は別 repo・別 owner で、片方を動かした
;; ときにもう片方との整合を検査するものが無かった。実測 2026-08-07: **4 つの
;; size class すべてが病的**だった(下記)。
;;
;; ## 検査する不変条件
;;
;; 封緘は `plaintext_len + 1(pad marker) + 16(AEAD tag)` を収める最小 bucket を
;; 選ぶ(`pqh` の `pick-bucket`)。したがって size class `c` について:
;;
;;   1. `c + 17 <= max(buckets)`        — 超えると inline padding 経路を外れる
;;   2. `(bucket(c) - c) / c <= --max-overhead` — bucket 直下に載っているか
;;
;; class が bucket の**ちょうど上**に乗ると最悪になる: 16,384 は 16,401 を要求して
;; 16,384 bucket を 17 バイト超え、65,536 へ飛ぶ(+300%)。
;;
;; ## bucket 階段はなぜ引数か
;;
;; 正本は `kotoba-lang/pqh` の `crypto.cljc` `PAD-BUCKETS` だが、この gate は
;; kotobase-peer の tree に対して走るので別 repo の定数を tree から読めない。
;; **script に埋めず gates.edn の :script-args に置く** —— 値が review 対象に
;; 残り、pqh 側が動いたときに diff に出る。script に埋めると誰も気づかない。
;;
;; ネットワーク: 不要。
;;
;; 実行: `npx nbb block-sizing-pad-alignment-check.cljs <dir> [--buckets 1024,4096,16384,65536] [--max-overhead 25]`

(ns fleet-ci.gates.block-sizing-pad-alignment-check
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))

(defn- flag [name default]
  (let [i (.indexOf args name)]
    (if (neg? i) default (nth args (inc i)))))

(def buckets-arg (flag "--buckets" "1024,4096,16384,65536"))
(def max-overhead (js/parseFloat (flag "--max-overhead" "25")))
(def tag-bytes (js/parseInt (flag "--tag-bytes" "16") 10))

(def root (or (first (remove #(str/starts-with? % "--")
                             (remove #{buckets-arg (str max-overhead) (str tag-bytes)} args)))
              "."))

(def buckets (->> (str/split buckets-arg #",")
                  (map str/trim)
                  (remove str/blank?)
                  (map #(js/parseInt % 10))
                  (sort)
                  vec))

(when (or (empty? buckets) (some js/isNaN buckets))
  (println "FAIL --buckets is not a comma-separated list of integers:" buckets-arg)
  (js/process.exit 1))

(def src (path/join root "src" "kotobase_peer" "block_sizing.cljc"))

(when-not (fs/existsSync src)
  (println "FAIL block sizing source not in the shipped tree:" src)
  (println "  (:include-ext must carry .cljc, or this gate cannot read the authority)")
  (js/process.exit 1))

(def text (fs/readFileSync src "utf8"))

;; `(def size-classes [16384 32768 65536 131072])`
(def classes
  (when-let [m (re-find #"\(def\s+size-classes\s*\[([^\]]*)\]" text)]
    (->> (str/split (second m) #"\s+")
         (map str/trim)
         (remove str/blank?)
         (map #(js/parseInt % 10))
         vec)))

(when (or (nil? classes) (empty? classes) (some js/isNaN classes))
  (println "FAIL could not read `size-classes` from" src)
  (println "  この gate は定数の形に依存する。形が変わったなら gate を直すこと —")
  (println "  読めなかったことを pass にしない(それが劇場の入口)。")
  (js/process.exit 1))

(defn pick-bucket [c]
  (let [need (+ c 1 tag-bytes)]
    (first (filter #(<= need %) buckets))))

(def rows
  (for [c classes]
    (let [need (+ c 1 tag-bytes)
          b (pick-bucket c)
          overhead (when b (* 100.0 (/ (double (- b c)) c)))]
      {:class c :need need :bucket b :overhead overhead
       :violations (cond-> []
                     (nil? b) (conj :exceeds-largest-bucket)
                     (and b (> overhead max-overhead)) (conj :overhead-above-limit))})))

(println (str "block size classes: " (str/join ", " classes)))
(println (str "pad buckets:        " (str/join ", " buckets)
              "   (AEAD tag " tag-bytes " B + 1 pad marker)"))
(println (str "max overhead:       " max-overhead "%"))
(println)
(println (str (.padEnd "class" 10) (.padEnd "need" 10) (.padEnd "bucket" 22) "overhead"))
(doseq [r rows]
  (println (str (.padEnd (str (:class r)) 10)
                (.padEnd (str (:need r)) 10)
                (.padEnd (if (:bucket r) (str (:bucket r)) "NONE (ciphertextBlob)") 22)
                (if (:bucket r)
                  (str "+" (.toFixed (:overhead r) 1) "%")
                  "inline padding 経路外"))))

(def bad (filter #(seq (:violations %)) rows))

(println)
(if (empty? bad)
  (do (println (str "PASS " (count classes) " 個の size class すべてが pad bucket 直下に載り、"
                    "overhead <= " max-overhead "%"))
      (js/process.exit 0))
  (do (println (str "FAIL " (count bad) "/" (count classes) " 個の size class が ADR-2608070400 D6 に違反"))
      (doseq [r bad]
        (println (str "  " (:class r) ": " (str/join ", " (map name (:violations r)))
                      (when (:bucket r) (str " (+" (.toFixed (:overhead r) 1) "%)")))))
      (println)
      (println "修正は 2 択で、blast radius が違う:")
      (println "  A) kotobase-peer の size-classes を bucket 直下へ (block レイアウトが変わる)")
      (println "  B) pqh の PAD-BUCKETS 階段を伸ばす (padding スキームが変わる=暗号文長が変わる)")
      (js/process.exit 1))))

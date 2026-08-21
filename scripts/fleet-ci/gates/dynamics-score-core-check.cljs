#!/usr/bin/env nbb
;; dynamics-score-core-check.cljs — system dynamics のスコア算術の**2 実装が
;; 同じ数を出すか**を見る（root ADR-2608189500）。score-core-check.cljs と同じ形。
;;
;; ## 何を突き合わせるか
;;
;;   正本    kotoba/dynamics_score_core.kotoba   （kotoba/pure、capability 0）
;;   参照    src/dynamics/core.cljc              （consumer が require する load path）
;;
;; **食い違ったら kernel が正しい。** `.cljc` は「JVM と JS の両方が require できる
;; もの」であるという理由で load path に残っているだけで、数の正本ではない。
;;
;; ノードに kotoba ツールチェーンは無い（fleet が配るのは **この repo の tree だけ**で、
;; `orgs/` の amu は入らない）。だから score-core / fabric-actor-core gate と同じく、
;; **コンパイル済みの `.mjs` を配って import する**。gate 自身はコンパイルしない。
;; **JVM はこの経路のどこにも居ない。**
;;
;; ## 5 つの検査
;;
;;   1. artifact <-> source —— `.mjs` の `sourceDigest` が `.kotoba` の sha256 と一致。
;;      `.kotoba` を直して再コンパイルを忘れると、**古い artifact に対して緑が出る**。
;;   2. purity —— `requiredCapabilities` が空。欄が **読めなかったとき空と読まない**
;;      （re-find が外れると nil で、素朴に書くと「capability 無し」で緑になる）。
;;   3. parity(exact) —— IEEE 演算と選択だけの measure。**ε は無い。**
;;      1 ulp の差は丸めではなく欠陥。`loop-structural-strength` は
;;      `(* a b c d)` を左に畳む —— 組み替えは代数的に同一で IEEE では同一でない。
;;   4. parity(tolerant) —— `pow` を経由する measure。kernel は host の
;;      transcendental を import しないので **bit 一致は入手不能**。閾値 1e-12、
;;      実測最悪値を毎回印字する。
;;   5. evidence floor —— 実際に走った case 数が `--min` 未満なら落とす。表が空に
;;      なった gate は exit 0 で何も主張しない（ADR-2608136000）。
;;
;; **なぜ ε がある半分を残すか。** score_core は整数 basis point にして ε を消したが、
;; ここが扱うのは成長率と複利で、`cagr` は実数指数の冪根そのものである。整数化は
;; 「どこまで丸めてよいか」を別の場所で決めるだけで、問題を消さない。だから
;; **2 つの半分を混ぜず、tolerant 側だけに閾値を置き、実測値を毎回見せる。**
;;
;; ## 使い方
;;
;;   nbb dynamics-score-core-check.cljs <repo-dir> [--min N]
;;
;; `<dir>` は **引数の先頭**に置く（fleet はそう渡す。CLAUDE.md の実測済みの罠）。

(ns dynamics-score-core-check
  (:require [clojure.string :as str]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def args (vec *command-line-args*))

;; `--min 10 .` のように書かれても `"10"` を tree のパスと誤読しないよう、
;; 値を取るフラグを飛ばして探す（CLAUDE.md 実測済みの罠）。
(def ^:private value-flags #{"--min"})

(def root
  (loop [[a & more] args prev nil]
    (cond (nil? a) "."
          (str/starts-with? a "--") (recur more a)
          (contains? value-flags prev) (recur more nil)
          :else a)))

(defn- opt [flag default]
  (let [i (.indexOf args flag)]
    (if (neg? i) default (get args (inc i)))))

(def min-cases (js/parseInt (opt "--min" "1200")))

(def tolerance 1e-12)

(def failures (atom []))
(def ran (atom 0))

(defn- fail! [& parts]
  (let [m (str/join " " (map str parts))]
    (swap! failures conj m)
    (println "FAIL" m)))

(defn- sha256 [buf] (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

;; ---------------------------------------------------------------------------
;; 参照側 —— `.cljc` の算術をこの gate が再実装している。
;;
;; **これは「2 実装」の 3 つ目ではない。** nbb は `src/dynamics/core.cljc` を
;; そのまま require できる（依存ゼロの純粋 namespace）が、fleet が配る tree で
;; classpath を組むより、**参照式をここに 1 行ずつ書き写す方が読み手に対して
;; 正直**である —— 何と比べているかが gate を読むだけで分かる。
;;
;; 書き写しは `.cljc` の式と 1 対 1 で、**結合順まで含めて**同じにする。
;; ここがずれたら gate は「gate 自身の再実装」と kernel を比べることになる。

(defn- ref-band-weight [b]
  ({"E" 1.0 "D" 3.0 "C" 5.0 "B" 7.0 "A" 10.0} b))

;; (* (band-weight band) tractability)
(defn- ref-leverage-base [b t]
  (when-let [w (ref-band-weight b)] (* w t)))

;; (* cycles-per-year (+ 0.1 sf) (+ 0.1 ic) (- 1.1 friction)) — clojure.core/* folds left
(defn- ref-loop-strength [cycle sf ic fr]
  (* (* (* (/ 365.0 cycle) (+ 0.1 sf)) (+ 0.1 ic)) (- 1.1 fr)))

;; (- (/ (+ 1.0 nominal) (+ 1.0 inflation)) 1.0), nil when 1+inflation <= 0
(defn- ref-real-growth [nom infl]
  (when (> (+ 1.0 infl) 0.0) (- (/ (+ 1.0 nom) (+ 1.0 infl)) 1.0)))

;; (- (pow (/ end start) (/ 1.0 years)) 1.0), nil unless start/end/years all positive
(defn- ref-cagr [start end years]
  (when (and (> start 0.0) (> years 0.0) (> end 0.0))
    (- (js/Math.pow (/ end start) (/ 1.0 years)) 1.0)))

;; (- 1 (pow (- 1 confidence) (/ 1 n))), refuses unless n > 0 and 0 < confidence < 1
(defn- ref-upper-bound [n c]
  (when (and (> n 0) (< 0 c) (< c 1))
    (- 1 (js/Math.pow (- 1 c) (/ 1.0 n)))))

;; ---------------------------------------------------------------------------
;; case 表。**1 行足すことは「両実装がこの入力を同じに扱う」と主張すること。**

(def bands ["E" "D" "C" "B" "A"])
;; **綴りとして妥当だが band ではない**もの。`""` や `"A "` のような不正な
;; keyword は guest の境界が `invalid-keyword` で trap する —— それは refusal
;; ではなく、`.cljc` には対応する概念が無い。**trap と refusal を混ぜない。**
(def unknown-bands ["Z" "X" "unknown" "a" "band" "" "a/b" "\u65e5\u672c"])

;; guest が trap すべき綴り。ここは parity ではなく**境界の主張**で、
;; 「拒否した」と「読めなかった」を分けるために別枠にしてある。
(def malformed-bands ["A " " " "A\nB" "A;B"])

(def tractabilities [0.0 0.05 0.2 0.37 0.5 0.618 0.75 0.9 0.99 1.0])

(def loop-shapes
  (vec (for [cycle [0.25 1.0 3.5 7.0 14.0 30.0 90.0 365.0 1000.0]
             sf    [0.0 0.15 0.5 0.83 1.0]
             ic    [0.0 0.07 0.5 0.95 1.0]
             fr    [0.0 0.05 0.4 0.9 1.0]]
         [cycle sf ic fr])))

(def yield-cases
  (vec (for [pool [1.0 17.0 1250.0 3.3e6 8.1e9] rate [0.0 1.0e-5 0.003 0.12 0.5 1.0]]
         [pool rate])))

;; -0.99 と 230.0 は両端（docstring が「2% では平気、200% では大間違い」と書く場合）
(def growth-pairs
  (vec (for [nom [-0.5 -0.02 0.0 0.021 0.07 0.35 2.0 12.0]
             infl [-0.99 -0.3 -0.021 0.0 0.02 0.19 2.0 230.0]]
         [nom infl])))

(def cagr-cases
  (vec (for [start [0.5 1.0 97.0 1000.0 8.25e6]
             end   [0.25 1.0 113.0 4200.0 9.9e7]
             years [0.5 1.0 3.0 7.0 25.0]]
         [start end years])))

(def trial-counts [1 2 3 7 30 100 1000 12345 1000000])
(def confidences [0.5 0.9 0.95 0.99 0.999])

;; 拒否が両側で一致することを見る欄。**片側だけが数を返すのが最悪の壊れ方**で、
;; 一致テストは「両方 nil」を無言で通してしまうのでここを分けている。
(def refusal-cases
  {:cagr [[0.0 100.0 5.0] [-3.0 100.0 5.0] [100.0 0.0 5.0]
          [100.0 -1.0 5.0] [100.0 200.0 0.0] [100.0 200.0 -2.0]]
   :real-growth [-1.0 -1.5 -100.0]
   :upper-bound-n [0 -5]
   :upper-bound-c [0.0 1.0 -0.5 1.5]
   :tractability [-0.01 1.01 -5.0 42.0]})

;; 種別ごとの ratchet。総数の床だけでは「1 種別だけ骨抜き」を捕まえられない
;; （score-core-check が 2026-08-15 に実測した形）。
(def case-floors
  {:bands 5 :unknown-bands 8 :malformed-bands 4 :tractabilities 10 :loop-shapes 1125
   :yield-cases 30 :growth-pairs 64 :cagr-cases 125
   :trial-counts 9 :confidences 5})

(defn- check-tables! []
  (doseq [[k floor] case-floors
          :let [n (count (case k
                           :bands bands :unknown-bands unknown-bands :malformed-bands malformed-bands
                           :tractabilities tractabilities :loop-shapes loop-shapes
                           :yield-cases yield-cases :growth-pairs growth-pairs
                           :cagr-cases cagr-cases :trial-counts trial-counts
                           :confidences confidences))]]
    (when (< n floor)
      (fail! "case table" (name k) "shrank to" n "— floor is" floor))))

;; ---------------------------------------------------------------------------
;; guest 呼び出し。**戻りの BigInt を Number へ戻し忘れると throw せずに静かに
;; 別の値になる**（ADR-2608122000）。i64 引数は BigInt で渡す必要があり、素の
;; number を渡すと artifact が `invalid-i64` で拒否する（黙って丸めない）。

(defn- result->clj
  "`[:result :f64 :string]` は [ok? payload] で返る。refusal は nil に畳む
  （`.cljc` の nil と直接比べるため）。ok/err を読めなかった場合は :unreadable
  を返す —— **読めなかったことを「拒否した」と読まない**。"
  [v]
  (cond (= v :trap) :trap
        (and (array? v) (= 2 (.-length v))) (if (aget v 0) (aget v 1) nil)
        :else :unreadable))

(defn- guard
  "guest 呼び出しを包む。**trap を握り潰さない** —— trap は refusal ではないので
  `:trap` として返し、比較側が失敗として報告する。包まないと 1 件の trap が
  gate 全体を落とし、残りの case が走らないまま exit 0 になる（実測 2026-08-18）。"
  [f]
  (try (f) (catch :default _ :trap)))

(defn- exact! [label got want]
  (swap! ran inc)
  (cond
    (= got :trap) (fail! label "— the kernel trapped where a value was expected")
    (= got :unreadable) (fail! label "— could not read the artifact's result shape")
    (and (nil? want) (some? got)) (fail! label "— cljc refuses but kernel returned" got)
    (and (some? want) (nil? got)) (fail! label "— kernel refuses but cljc returned" want)
    (and (some? want) (not= want got)) (fail! label "— kernel" got "cljc" want)))

(def worst (atom {}))

(defn- tolerant! [group label got want]
  (swap! ran inc)
  (cond
    (= got :trap) (fail! label "— the kernel trapped where a value was expected")
    (= got :unreadable) (fail! label "— could not read the artifact's result shape")
    (and (nil? want) (some? got)) (fail! label "— cljc refuses but kernel returned" got)
    (and (some? want) (nil? got)) (fail! label "— kernel refuses but cljc returned" want)
    (and (some? want) (some? got))
    (let [e (if (zero? want) (js/Math.abs got) (js/Math.abs (/ (- got want) want)))]
      (swap! worst update group (fnil max 0) e)
      (when-not (< e tolerance)
        (fail! label "— relative error" e "exceeds" tolerance)))))

(defn- check-parity! [m]
  (let [;; **fuel は instance の生涯で尽きる** —— 実測 512 呼び出しで
        ;; `fuel-exhausted`。1 instance を使い回すと corpus の途中から
        ;; **全部 trap になり、それが「不一致」として報告される**（実測
        ;; 2026-08-18、1535 件中 1316 件が偽の赤になった）。
        ;; したがって 1 呼び出し 1 instance にする。
        call (fn [f & a] (guard #(apply (aget ((aget m "instantiateKotoba") #js {}) f) a)))]

    ;; --- exact half ---
    (doseq [b bands]
      (exact! (str "band-weight :band/" b)
              (result->clj (call "band-weight" (str ":band/" b)))
              (ref-band-weight b)))
    (doseq [b unknown-bands]
      (exact! (str "band-weight unknown " (pr-str b))
              (result->clj (call "band-weight" (str ":band/" b)))
              (ref-band-weight b)))

    ;; 境界の主張: 綴りが keyword として不正なら guest は **trap する**。
    ;; refusal（`[false msg]`）ではない。両者を同じ欄で数えると、境界が壊れて
    ;; 何でも受け取るようになっても「拒否した」と読めてしまう。
    (doseq [b malformed-bands]
      (swap! ran inc)
      (let [got (call "band-weight" (str ":band/" b))]
        (when-not (= :trap got)
          (fail! (str "band-weight " (pr-str b))
                 "— a malformed keyword must trap at the guest boundary, got" got))))

    (doseq [b bands t tractabilities]
      (exact! (str "leverage-base " b " " t)
              (result->clj (call "leverage-base" (str ":band/" b) t))
              (ref-leverage-base b t)))

    (doseq [[pool rate] yield-cases]
      (exact! (str "expected-yield " pool " " rate)
              (call "expected-yield" pool rate)
              (* pool rate)))

    (doseq [[cycle sf ic fr] loop-shapes]
      (exact! (str "loop-structural-strength " [cycle sf ic fr])
              (call "loop-structural-strength" cycle sf ic fr)
              (ref-loop-strength cycle sf ic fr)))

    (doseq [[nom infl] growth-pairs]
      (exact! (str "real-growth " nom " " infl)
              (result->clj (call "real-growth" nom infl))
              (ref-real-growth nom infl)))

    ;; --- tolerant half ---
    (doseq [[start end years] cagr-cases]
      (tolerant! :cagr (str "cagr " [start end years])
                 (result->clj (call "cagr" start end years))
                 (ref-cagr start end years)))

    (doseq [n trial-counts c confidences]
      (tolerant! :upper-bound (str "upper-bound " n " " c)
                 (result->clj (call "upper-bound-rate-from-zero-events" (js/BigInt n) c))
                 (ref-upper-bound n c)))

    (doseq [base [0.05 0.5 0.999 1.0 1.7 3.0 97.0 1e6]
            e    [-3.0 -0.5 0.0 0.01 0.5 1.0 2.0 7.0]]
      (tolerant! :pow (str "pow " base " " e)
                 (call "pow" base e)
                 (js/Math.pow base e)))

    ;; --- refusal parity ---
    (doseq [[s e y] (:cagr refusal-cases)]
      (exact! (str "cagr refuses " [s e y]) (result->clj (call "cagr" s e y)) nil))
    (doseq [infl (:real-growth refusal-cases)]
      (exact! (str "real-growth refuses " infl) (result->clj (call "real-growth" 0.05 infl)) nil))
    (doseq [n (:upper-bound-n refusal-cases)]
      (exact! (str "upper-bound refuses n=" n)
              (result->clj (call "upper-bound-rate-from-zero-events" (js/BigInt n) 0.95)) nil))
    (doseq [c (:upper-bound-c refusal-cases)]
      (exact! (str "upper-bound refuses c=" c)
              (result->clj (call "upper-bound-rate-from-zero-events" (js/BigInt 100) c)) nil))
    (doseq [t (:tractability refusal-cases)]
      (exact! (str "leverage-base refuses t=" t)
              (result->clj (call "leverage-base" ":band/A" t)) nil))))

;; ---------------------------------------------------------------------------

(defn- main! []
  (let [src      (path/join root "kotoba" "dynamics_score_core.kotoba")
        artifact (path/join root "kotoba" "dynamics_score_core.mjs")
        cljc     (path/join root "src" "dynamics" "core.cljc")]
    (cond
      (not (fs/existsSync src))      (do (fail! "missing" src) (set! (.-exitCode js/process) 1))
      (not (fs/existsSync artifact)) (do (fail! "missing" artifact) (set! (.-exitCode js/process) 1))
      (not (fs/existsSync cljc))     (do (fail! "missing" cljc) (set! (.-exitCode js/process) 1))
      :else
      (let [want (sha256 (fs/readFileSync src))
            js   (fs/readFileSync artifact "utf8")
            got  (second (re-find #"sourceDigest:\"([0-9a-f]+)\"" js))
            caps (second (re-find #"requiredCapabilities:Object\.freeze\(\[([^\]]*)\]\)" js))]
        (when-not (= want got)
          (fail! "artifact does not belong to the committed source:"
                 "dynamics_score_core.kotoba sha256" want
                 "but dynamics_score_core.mjs carries" (str got)
                 "— recompile with `amu compile … --target js`"))
        (cond
          (nil? caps)
          (fail! "could not read requiredCapabilities from the artifact"
                 "— the purity check cannot run, and absence is not a pass")
          (not= "" (str/trim caps))
          (fail! "the score core is no longer pure — requiredCapabilities:" caps))
        (println "artifact<->source:" (if (= want got) "bound" "BROKEN")
                 "· capabilities:" (cond (nil? caps) "UNREADABLE"
                                         (= "" (str/trim caps)) "none"
                                         :else caps))
        (check-tables!)
        (-> (js/import (str "file://" (path/resolve artifact)))
            (.catch (fn [e] (fail! "could not import the compiled artifact:" (.-message e)) nil))
            (.then (fn [m]
                     (when m
                       (check-parity! m))
                     (doseq [[g e] (sort-by key @worst)]
                       (println (str "  " (name g) " worst relative error " e)))
                     (println (str "ran " @ran " cases, " (count @failures) " failing"))
                     ;; 絞り込みが壊れて 0 件を「合格」にしない床。上限ではない。
                     ;; **`when (seq …)` の中に入れない** —— 0 件こそが床の存在理由。
                     (when (< @ran min-cases)
                       (println "FAIL only" @ran "cases ran, floor is" min-cases
                                "— a pass here would assert nothing")
                       (swap! failures conj "evidence floor"))
                     (when (seq @failures)
                       (println "")
                       (println "root ADR-2608189500: system dynamics のスコア算術の正本は")
                       (println "kotoba/dynamics_score_core.kotoba。src/dynamics/core.cljc は")
                       (println "consumer が require する load path であって数の正本ではない。")
                       (println "食い違ったら **kernel が正しい**。")
                       (println ".kotoba を直したら .mjs を必ず再生成する:")
                       (println "  amu compile <abs>/kotoba/dynamics_score_core.kotoba --target js \\")
                       (println "    --output <abs>/kotoba/dynamics_score_core.mjs")
                       (set! (.-exitCode js/process) 1)))))))))

(main!)

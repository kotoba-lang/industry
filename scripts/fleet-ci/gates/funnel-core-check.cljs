#!/usr/bin/env nbb
;; funnel-core-check.cljs — BMC funnel の**転換率とボトルネック判定**を、
;; 2 実装が同じに答えるかで見る（ADR-2608160500）。
;;
;; ## 何を突き合わせるか
;;
;;   正本    70-tools/bmc/kotoba/funnel_core.kotoba   （kotoba/pure、capability 0）
;;   参照    70-tools/bmc/src/gftd/funnel.cljc        （日次 routine が実際に読む）
;;
;; 参照は **`gftd.funnel` そのもの**であって、gate のためだけの 2 つ目の
;; 名前空間ではない（mirror を作らない）。
;;
;; ノードに kotoba ツールチェーンは無い（fleet が配るのは **この repo の tree だけ**で、
;; `orgs/` の amu は入らない）。だから fabric-actor-core gate と同じく、
;; **コンパイル済みの `.mjs` を配って import する**。gate 自身はコンパイルしない。
;;
;; ## 4 つの検査
;;
;;   1. artifact <-> source —— `.mjs` の `sourceDigest` が `.kotoba` の sha256 と一致。
;;      `.kotoba` を直して再コンパイルを忘れると、**古い artifact に対して緑が出る**。
;;   2. purity —— `requiredCapabilities` が空。欄が **読めなかったとき空と読まない**
;;      （re-find が外れると nil で、素朴に書くと「capability 無し」で緑になる）。
;;   3. parity —— 下の case 表の全件で kernel == cljc。ε は無い（全部 i64 と bool）。
;;   4. evidence floor —— 実際に走った case 数が `--min` 未満なら落とす。表が空に
;;      なった gate は exit 0 で何も主張しない（ADR-2608136000）。
;;
;; さらに参照側の入力そのものも見る: `funnel-specs` が縮んでいないか、宣言された
;; benchmark（0.03 のような小数）が **誤差なく整数 bp になるか**。後者が崩れると、
;; kernel と cljc は一致したまま **両方が spec の意図からずれる**。
;;
;; ## 使い方
;;
;;   nbb funnel-core-check.cljs <repo-dir> [--min N] [--nbb CMD]
;;
;; `<dir>` は **引数の先頭**に置く（fleet はそう渡す。CLAUDE.md の実測済みの罠）。

(ns funnel-core-check
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def args (vec *command-line-args*))

;; `<dir>` は先頭に来るのが fleet の渡し方だが、`--min 10 .` のように書かれても
;; `"10"` を tree のパスと誤読しないよう、値を取るフラグを飛ばして探す
;; （CLAUDE.md 実測済みの罠。ローカルで gate を回すときにだけ踏む）。
(def ^:private value-flags #{"--min" "--nbb"})

(def root
  (loop [[a & more] args prev nil]
    (cond (nil? a) "."
          (str/starts-with? a "--") (recur more a)
          (contains? value-flags prev) (recur more nil)
          :else a)))

(defn- opt [flag default]
  (let [i (.indexOf args flag)]
    (if (neg? i) default (get args (inc i)))))

;; 既定の床は 2026-08-15 時点の実測 1,740 件の少し下。**上限ではなく床**で、
;; 表が絞り込みで縮んだときに 0 件や数十件の「合格」を出さないためにある。
(def min-cases (js/parseInt (opt "--min" "1700")))

;; `--nbb CMD` は **手でこの gate を検証するためだけ**にある。`npx --yes <pkg> <args>`
;; は作者の laptop（npm 11.12.1）でスクリプトパスをパッケージ名と誤読して落ちるが、
;; fleet のノード（10.9.8 / 11.17.0）では正しく走る（CLAUDE.md の実測）。override を
;; 用意しないと、この gate は **落ちることを一度も見せられないまま landing する**。
(def nbb-cmd (opt "--nbb" nil))

(def failures (atom []))

(defn- fail! [& parts]
  (let [m (str/join " " (map str parts))]
    (swap! failures conj m)
    (println "FAIL" m)))

(defn- sha256 [buf] (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

;; ---------------------------------------------------------------------------
;; case 表。**1 行足すことは「両実装がこの入力を同じに扱う」と主張すること。**
;;
;; ここで名指しで踏む分岐（どれも「測れなかった」と「測って悪かった」が同じ顔を
;; しうる場所）:
;;
;;   分母 0（前段 0 件）    …  0% 転換ではなく「率を出せない」
;;   分子 0                …  こちらは測って 0%。分母 0 と別の値でなければならない
;;   両端が等しい          …  10000 bp ちょうど
;;   率が 1.0 超           …  後段の emitter が前段より多く数える。頭打ちにしない
;;   benchmark 無し(-1)    …  gap は 0 ではなく「無い」
;;   benchmark ちょうど    …  gap = 0。未達ではない
;;   gap 同点              …  どちらの段を採るか（後ろが勝つ）

(def counts
  ;; -1 = host が読めなかった。負はすべて欠測なので -2 も入れて境界を踏む。
  [-2 -1 0 1 2 3 7 12 30 50 100 200 999 1000 1001 100000])

(def benchmarks
  ;; bp。-1 = 段が benchmark を宣言していない。10001 は clamp の境界。
  [-2 -1 0 1 100 200 300 400 500 1000 2000 2500 3000 4000 5000 10000 10001 99999])

(def clamp-inputs
  [-100000 -1 0 1 9999 10000 10001 33333 100000])

(def pct-inputs
  ;; .5 の境界を跨がせる。負（不在）も入れる。
  [-100000 -1 0 49 50 51 149 150 250 299 300 301 1249 1250 4999 5000 10000 12000 333333])

;; (from to) の組。counts × counts の全交差に加えて、上の分岐を名前で踏む。
(def step-pairs
  (vec (distinct (concat (for [f counts t counts] [f t])
                         [[0 100]      ;; 分母 0・分子あり
                          [1000 0]     ;; 分子 0
                          [0 0]        ;; 両方 0
                          [50 50]      ;; 等量 = 10000 bp
                          [10 30]      ;; 率 1.0 超
                          [-1 100] [100 -1] [-1 -1]  ;; 欠測
                          [1000 30] [30 12] [3 1]]))))

;; gap の畳み込みに与える (candidate, incumbent)。同点・不在・正の gap を踏む。
(def gap-values
  [-100000 -10000 -2500 -1000 -500 -200 -1 0 1 500 1000 29500 333333])

(def bottleneck-pairs
  (vec (distinct (concat (for [c gap-values b gap-values] [c b])
                         [[-1000 -1000]      ;; 同点 → 候補（＝後ろの段）が勝つ
                          [-100000 -100000]  ;; どちらも不在
                          [0 -100000]]))))   ;; ちょうど一致は候補にならない

;; gap 系は counts × counts × benchmarks の全交差だと 4,600 件を超えるので、
;; 分岐を代表する組に絞る（絞ったこと自体は下の evidence floor が見張る）。
(def gap-triples
  (vec (distinct (for [[f t] [[1000 30] [1000 50] [1000 0] [0 100] [0 0] [50 50]
                              [10 30] [-1 100] [100 -1] [-1 -1] [3 1] [30 12] [100 10]]
                       b benchmarks]
                   [f t b]))))

;; ---------------------------------------------------------------------------
;; cljc 側: 参照実装を子プロセスで起動して答えだけ受け取る。**規則を写さない。**
;;
;; classpath は `70-tools/bmc/src` と repo root —— `gftd.canvas` 経由で
;; `scripts.nbb-compat` を読むため。bmc の runner が読むのと同じ名前空間
;; （`gftd.funnel`）で読む。

(defn- ref-answers []
  (let [expr (str "(require '[gftd.funnel :as f])"
                  "(prn {:absent (f/absent) :gap-absent (f/gap-absent)"
                  " :clamp-bp (mapv (fn [x] [x (f/clamp-bp x)]) " (pr-str clamp-inputs) ")"
                  " :measured (mapv (fn [n] [n (f/measured? n)]) " (pr-str counts) ")"
                  " :denominator (mapv (fn [n] [n (f/denominator? n)]) " (pr-str counts) ")"
                  " :benchmark-known (mapv (fn [b] [b (f/benchmark-known? b)]) " (pr-str benchmarks) ")"
                  " :benchmark-bp (mapv (fn [b] [b (f/benchmark-bp b)]) " (pr-str benchmarks) ")"
                  " :pct-bp (mapv (fn [x] [x (f/pct-bp x)]) " (pr-str pct-inputs) ")"
                  " :step-measured (mapv (fn [[a b]] [[a b] (f/step-measured? a b)]) " (pr-str step-pairs) ")"
                  " :rate-known (mapv (fn [[a b]] [[a b] (f/rate-known? a b)]) " (pr-str step-pairs) ")"
                  " :conversion-bp (mapv (fn [[a b]] [[a b] (f/conversion-bp a b)]) " (pr-str step-pairs) ")"
                  " :gap-known (mapv (fn [[a b c]] [[a b c] (f/gap-known? a b c)]) " (pr-str gap-triples) ")"
                  " :gap-bp (mapv (fn [[a b c]] [[a b c] (f/gap-bp a b c)]) " (pr-str gap-triples) ")"
                  " :below-benchmark (mapv (fn [[a b c]] [[a b c] (f/below-benchmark? a b c)]) " (pr-str gap-triples) ")"
                  " :bottleneck-wins (mapv (fn [[a b]] [[a b] (f/bottleneck-wins? a b)]) " (pr-str bottleneck-pairs) ")"
                  ;; 参照側の入力そのもの。kernel には無いが、両実装が一致したまま
                  ;; spec の意図からずれるのを止める。
                  " :product-count (count f/funnel-specs)"
                  " :benchmarks (vec (for [[p spec] f/funnel-specs s spec"
                  "                        :let [b (:benchmark s)] :when (some? b)]"
                  "                    [p (:key s) b (f/benchmark->bp b)]))"
                  ;; `pr-str` を鍵にするので、印字が衝突する形（1 と 1.0）は入れない。
                  " :count-inputs (into {} (for [x [nil 0 1 1000 -1 -0.5 12.9 \"30\" \"abc\" :kw true]]"
                  "                          [(pr-str x) (f/count->bp-input x)]))})")
        [prog pre] (if nbb-cmd
                     (do (println "NOTE: using --nbb" nbb-cmd "instead of the pinned npx nbb")
                         [nbb-cmd []])
                     ["npx" ["--yes" "nbb"]])
        r (cp/spawnSync prog (clj->js (concat pre ["--classpath" "70-tools/bmc/src:." "-e" expr]))
                        #js {:cwd root :encoding "utf8" :maxBuffer (* 64 1024 1024)})
        out (str (aget r "stdout"))
        err (str (aget r "stderr"))]
    (if (or (not= 0 (aget r "status")) (str/blank? out))
      (do (fail! "could not run 70-tools/bmc/src/gftd/funnel.cljc —"
                 (str/join " " (take-last 4 (str/split-lines (str err out)))))
          nil)
      (try (edn/read-string out)
           (catch :default e
             (fail! "unreadable output from the cljc reference:" (.-message e))
             nil)))))

;; ---------------------------------------------------------------------------
;; kotoba 側。**trap を失敗として捕まえる** —— 外へ投げると gate ごと落ちて、
;; 診断が「artifact を import できない」になり原因を取り違える。
;;
;; fuel は instance ごとに尽きる。1 呼び出し 1 instance。

;; **`:i64` の seam は BigInt。** 数をそのまま渡すと `invalid-i64` で trap する。
;; 戻りの BigInt も Number へ戻す —— 戻り側は throw すらしないので、放置すると
;; 「一致しない」ではなく「静かに別の値」になる（ADR-2608122000）。
;; **bool を素の `js/Number` に通さない** —— `(js/Number true)` は 1 で、
;; `true` と `1` を混ぜると「答えが違う」が「型が違う」に化ける。
(defn- ->guest [v] (if (number? v) (js/BigInt v) v))
(defn- <-guest [v] (if (boolean? v) v (js/Number v)))

(defn- kernel-caller [m]
  (let [instantiate (aget m "instantiateKotoba")]
    (fn [fname argv]
      (try {:ok (<-guest (apply (aget (instantiate #js {}) fname) (map ->guest argv)))}
           (catch :default e {:trap (.-message e) :fn fname :args argv})))))

(def ran (atom 0))

(defn- expect! [k fname argv want]
  (swap! ran inc)
  (let [got (k fname argv)]
    (cond
      (:trap got)
      (fail! fname (pr-str argv) "TRAPPED —" (:trap got))
      (not= (str (:ok got)) (str want))
      (fail! fname (pr-str argv) "— kernel says" (pr-str (str (:ok got)))
             "but cljc says" (pr-str (str want))))))

(defn- check-parity! [m ref]
  (let [k (kernel-caller m)]
    (expect! k "absent" [] (:absent ref))
    (expect! k "gap-absent" [] (:gap-absent ref))
    (doseq [[x want] (:clamp-bp ref)]        (expect! k "clamp-bp" [x] want))
    (doseq [[n want] (:measured ref)]        (expect! k "measured?" [n] want))
    (doseq [[n want] (:denominator ref)]     (expect! k "denominator?" [n] want))
    (doseq [[b want] (:benchmark-known ref)] (expect! k "benchmark-known?" [b] want))
    (doseq [[b want] (:benchmark-bp ref)]    (expect! k "benchmark-bp" [b] want))
    (doseq [[x want] (:pct-bp ref)]          (expect! k "pct-bp" [x] want))
    (doseq [[[a b] want] (:step-measured ref)] (expect! k "step-measured?" [a b] want))
    (doseq [[[a b] want] (:rate-known ref)]    (expect! k "rate-known?" [a b] want))
    (doseq [[[a b] want] (:conversion-bp ref)] (expect! k "conversion-bp" [a b] want))
    (doseq [[[a b c] want] (:gap-known ref)]       (expect! k "gap-known?" [a b c] want))
    (doseq [[[a b c] want] (:gap-bp ref)]          (expect! k "gap-bp" [a b c] want))
    (doseq [[[a b c] want] (:below-benchmark ref)] (expect! k "below-benchmark?" [a b c] want))
    (doseq [[[a b] want] (:bottleneck-wins ref)]   (expect! k "bottleneck-wins?" [a b] want))))

;; ---------------------------------------------------------------------------
;; 参照側の入力の健全性。kernel と一致していても spec がおかしければ意味が無い。

(defn- check-reference-inputs! [ref]
  ;; product が静かに消えると、case 表は縮まないのに **誰も出荷していない
  ;; funnel について両実装が一致している**ことを確かめ続ける。
  (when (< (:product-count ref) 6)
    (fail! "gftd.funnel/funnel-specs lists only" (:product-count ref)
           "products — the case table would go on agreeing about funnels nobody runs"))
  ;; 宣言された benchmark が誤差なく整数 bp になること。ここが崩れると kernel と
  ;; cljc は一致したまま、両方が spec の意図から等しくずれる。
  (doseq [[p k b bp] (:benchmarks ref)]
    (when-not (== (double b) (/ bp 10000.0))
      (fail! "benchmark" (pr-str b) "of" (str (name p) "/" (name k))
             "does not survive the trip to basis points:" bp "bp is" (/ bp 10000.0)
             "— pick a benchmark with at most four decimal places")))
  (when (< (count (:benchmarks ref)) 13)
    (fail! "only" (count (:benchmarks ref)) "benchmarks found in funnel-specs"
           "— the six shipped funnels declare 13"))
  ;; metrics edn から来る形が kernel の入力にどう落ちるか。**「読めなかった」と
  ;; 「読めて 0」を、この境界で取り違えないこと**が funnel の欠測の綴りの前提。
  ;; 期待値をここに書く（kernel には無い host だけの責務なので parity では見えない）。
  (let [want {"nil" -1, "0" 0, "1" 1, "1000" 1000, "-1" -1, "-0.5" -1,
              "12.9" 12, "\"30\"" 30, "\"abc\"" -1, ":kw" -1, "true" -1}]
    (doseq [[shown expected] want]
      (let [got (get (:count-inputs ref) shown ::missing)]
        (when-not (= got expected)
          (fail! "count->bp-input" shown "=>" (pr-str got) "but this gate expects" expected
                 "— a count that cannot be read must land on the absent sentinel,"
                 "and a count that can must not")))))
  (println "reference inputs:" (:product-count ref) "products ·"
           (count (:benchmarks ref)) "benchmarks · absent=" (:absent ref)
           "gap-absent=" (:gap-absent ref)))

;; ---------------------------------------------------------------------------

(defn- main! []
  (let [src      (path/join root "70-tools" "bmc" "kotoba" "funnel_core.kotoba")
        artifact (path/join root "70-tools" "bmc" "kotoba" "funnel_core.mjs")]
    (cond
      (not (fs/existsSync src))      (do (fail! "missing" src) (set! (.-exitCode js/process) 1))
      (not (fs/existsSync artifact)) (do (fail! "missing" artifact) (set! (.-exitCode js/process) 1))
      :else
      (let [want (sha256 (fs/readFileSync src))
            js   (fs/readFileSync artifact "utf8")
            got  (second (re-find #"sourceDigest:\"([0-9a-f]+)\"" js))
            caps (second (re-find #"requiredCapabilities:Object\.freeze\(\[([^\]]*)\]\)" js))]
        (when-not (= want got)
          (fail! "artifact does not belong to the committed source:"
                 "funnel_core.kotoba sha256" want
                 "but funnel_core.mjs carries" (str got)
                 "— recompile with `kotoba -M compile … --target js`"))
        (cond
          (nil? caps)
          (fail! "could not read requiredCapabilities from the artifact"
                 "— the purity check cannot run, and absence is not a pass")
          (not= "" (str/trim caps))
          (fail! "the funnel core is no longer pure — requiredCapabilities:" caps))
        (println "artifact<->source:" (if (= want got) "bound" "BROKEN")
                 "· capabilities:" (cond (nil? caps) "UNREADABLE"
                                         (= "" (str/trim caps)) "none"
                                         :else caps))
        (if-let [ref (ref-answers)]
          (do
            (check-reference-inputs! ref)
            (-> (js/import (str "file://" (path/resolve artifact)))
                (.catch (fn [e] (fail! "could not import the compiled artifact:" (.-message e)) nil))
                (.then (fn [m]
                         (when m (check-parity! m ref))
                         (println (str "ran " @ran " cases, " (count @failures) " failing"))
                         ;; 絞り込みが壊れて 0 件を「合格」にしない床。上限ではない。
                         ;; **`when (seq …)` の中に入れない** —— 0 件こそが床の存在理由。
                         (when (< @ran min-cases)
                           (println "FAIL only" @ran "cases ran, floor is" min-cases
                                    "— a pass here would assert nothing")
                           (swap! failures conj "evidence floor"))
                         (when (seq @failures)
                           (println "")
                           (println "ADR-2608160500: 転換率・benchmark との差・ボトルネック判定の")
                           (println "正本は 70-tools/bmc/kotoba/funnel_core.kotoba。参照実装は")
                           (println "70-tools/bmc/src/gftd/funnel.cljc の 1 箇所だけで、")
                           (println "日次 routine bmc-business-operate-daily はそこを読む。")
                           (println "食い違ったら **kernel が正しい**。")
                           (set! (.-exitCode js/process) 1))))))
          (set! (.-exitCode js/process) 1))))))

(main!)

#!/usr/bin/env nbb
;; score-core-check.cljs — BMC / YC 成熟度スコアの**算術の 2 実装が同じ数を出すか**
;; を見る（ADR-2608158000）。
;;
;; ## 何を突き合わせるか
;;
;;   正本    70-tools/bmc/kotoba/score_core.kotoba   （kotoba/pure、capability 0）
;;   参照    70-tools/bmc/src/gftd/score.cljc        （CLI・render・投影・allocate が読む）
;;
;; ノードに kotoba ツールチェーンは無い（fleet が配るのは **この repo の tree だけ**で、
;; `orgs/` の amu は入らない）。だから fabric-actor-core / org-id gate と同じく、
;; **コンパイル済みの `.mjs` を配って import する**。gate 自身はコンパイルしない。
;;
;; ## 4 つの検査
;;
;;   1. artifact <-> source —— `.mjs` の `sourceDigest` が `.kotoba` の sha256 と一致。
;;      `.kotoba` を直して再コンパイルを忘れると、**古い artifact に対して緑が出る**。
;;   2. purity —— `requiredCapabilities` が空。欄が **読めなかったとき空と読まない**
;;      （re-find が外れると nil で、素朴に書くと「capability 無し」で緑になる）。
;;   3. parity —— 下の case 表の全件で kernel == cljc。ε は無い（全部 i64）。
;;      **これが ε 無しで書けるのが整数 basis point にした理由**であって、
;;      浮動小数のままなら「どこまでズレてよいか」を誰かが決めることになる。
;;   4. evidence floor —— 実際に走った case 数が `--min` 未満なら落とす。表が空に
;;      なった gate は exit 0 で何も主張しない（ADR-2608136000）。
;;
;; ## 複合スコアも突き合わせる
;;
;; BMC / YC の複合は「次元 bp の加重平均」ちょうどなので、kernel 側は
;; `acc-num` / `acc-den` / `acc-mean` の 3 本しか持たない（合成専用の関数を置くと
;; 同じ判断の 2 実装になる）。gate は **kernel 側だけを自分で畳み**、cljc 側は
;; `score/fold-mean-bp`（`score-product` が実際に使う畳み込み）を呼ぶ。両側とも
;; gate が畳むと、検査しているのは gate の畳み込み 2 本の一致になってしまう。
;;
;; ## 使い方
;;
;;   nbb score-core-check.cljs <repo-dir> [--min N] [--nbb CMD]
;;
;; `<dir>` は **引数の先頭**に置く（fleet はそう渡す。CLAUDE.md の実測済みの罠）。

(ns score-core-check
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def args (vec *command-line-args*))

;; `--min 10 .` のように書かれても `"10"` を tree のパスと誤読しないよう、
;; 値を取るフラグを飛ばして探す（CLAUDE.md 実測済みの罠）。
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

(def min-cases (js/parseInt (opt "--min" "300")))

;; `--nbb CMD` は **手でこの gate を検証するためだけ**にある。`npx --yes <pkg> <args>`
;; は作者の laptop（npm 11.12.1）でスクリプトパスをパッケージ名と誤読して落ちるが、
;; fleet のノードでは正しく走る。override が無いと、この gate は
;; **落ちることを一度も見せられないまま landing する**。
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
;; 境界（0・負・上限超え・分母 0・知らない綴り）を名指しで踏む。切り捨ての向きが
;; 効くのは端数のある入力だけなので、割り切れない比も入れる。

(def clamp-inputs [-1000000 -10000 -2000 -1 0 1 1333 4999 5000 9999 10000 10001 999999])

(def ratio-inputs
  (vec (for [num [0 1 2 3 4 5 7 9 13 100] den [0 1 2 3 5 9 12 25 30]] [num den])))

;; rubric は 1/10 単位。0.0–5.0 の全刻みと、範囲外（負・上限超え）。
(def rubric-inputs (vec (concat (range 0 51) [-1 -10 51 60 1000])))

;; blocks 0..9（9 block が満点）× items 0..20。平均 2 の境界をまたぐ。
(def completeness-inputs
  (vec (for [b (range 0 10) i [0 1 2 3 5 8 17 18 19 20 40]] [b i])))

(def hypothesis-inputs
  (vec (for [g [0 1 2 5] t [0 1 2 3 9]] [g t])))

;; 実在する綴り + 知らない綴り + 空 + 大文字（fold しないことの確認）。
(def status-inputs
  ["untested" "measuring" "blocked" "moot" "refuted" "validated"
   "" " " "Validated" "VALIDATED" "unknown" "pass" "日本語"])

(def acc-num-inputs
  (vec (for [n [0 1 10000 52000] v [0 3000 10000] w [1 2 3]] [n v w])))

(def acc-den-inputs (vec (for [d [0 1 6 12] w [0 1 2]] [d w])))

(def acc-mean-inputs
  (vec (for [n [0 1 4333 52000 120001] d [0 1 5 12 25]] [n d])))

;; 複合スコアの畳み込み。[[bp weight] ...]。
;; ①BMC の 5 次元（重み 1）②YC の 9 次元（design 1 / traction 2）
;; ③空（分母 0 = 仮説ゼロの validation）④端数の出る組
(def fold-inputs
  (let [d (fn [& vs] (mapv #(vector (* % 2000) 1) vs))
        yc (fn [ds ts] (into (mapv #(vector (* % 2000) 1) ds)
                             (mapv #(vector (* % 2000) 2) ts)))]
    (vec (concat
          [[]                                   ;; 仮説ゼロ
           [[3000 1]]                           ;; measuring 1 件
           [[3000 1] [0 1] [10000 1]]           ;; 端数（13000/3）
           [[10000 1] [4000 1] [0 1]]]
          [(d 0 0 0 0 0) (d 5 5 5 5 5) (d 1 2 3 4 5)
           (d 0.6665 5 0 2 3)]                  ;; completeness 由来の端数を含む形
          [(yc [4 2 4 5 1 4] [2 1 0])           ;; bmc_test の fixture（4333 bp）
           (yc [0 0 0 0 0 0] [0 0 0])
           (yc [5 5 5 5 5 5] [5 5 5])
           (yc [3 3 3 3 3 3] [1 1 1])
           (yc [1 2 3 4 5 1] [2 3 4])]))))

;; ---------------------------------------------------------------------------
;; 表そのものが縮んでいないかを見る。
;;
;; **総数の床だけでは縮小を捕まえられない。** 実測 2026-08-15: `status-inputs` を
;; 13 件から 1 件に削っても 378 件が残り、床 300 を上回って **exit 0（緑）**になった。
;; 「絞り込みが壊れて 0 件」は床が捕まえるが、「1 種別だけ骨抜き」は捕まえない ——
;; 総数は他の種別が支えてしまう。だから種別ごとに見る。
;;
;;   `case-floors`     —— 種別ごとの ratchet。減らすなら理由と一緒に減らす。
;;   `required-statuses` —— rubric が名指しする綴りは**全部踏む**。これは件数では
;;                        なく「kernel が答えを持つ入力の集合」そのもので、
;;                        1 つでも踏まれていなければその分岐は誰も見ていない。

(def required-statuses
  #{"untested" "measuring" "blocked" "moot" "refuted" "validated"})

(def case-floors
  {:clamp 10 :ratio 60 :rubric 50 :completeness 80 :hypothesis 15
   :status 10 :acc-num 30 :acc-den 10 :acc-mean 20 :fold 12})

(def case-tables
  {:clamp clamp-inputs :ratio ratio-inputs :rubric rubric-inputs
   :completeness completeness-inputs :hypothesis hypothesis-inputs
   :status status-inputs :acc-num acc-num-inputs :acc-den acc-den-inputs
   :acc-mean acc-mean-inputs :fold fold-inputs})

(defn- check-tables! []
  (doseq [[kw floor] case-floors]
    (let [n (count (get case-tables kw))]
      (when (< n floor)
        (fail! "the" (name kw) "case table has shrunk to" n "inputs, floor is" floor
               "— the total-case floor cannot see this, other tables carry it"))))
  (let [missing (remove (set status-inputs) required-statuses)]
    (when (seq missing)
      (fail! "the status case table no longer exercises" (pr-str (vec (sort missing)))
             "— those branches of the rubric would go unchecked")))
  (when-not (some #(not (contains? required-statuses %)) status-inputs)
    (fail! "the status case table has no unknown spelling in it"
           "— the fallback branch (unknown -> 0) would go unchecked")))

;; ---------------------------------------------------------------------------
;; cljc 側: 参照実装を子プロセスで起動して答えだけ受け取る。**規則を写さない。**
;;
;; classpath は `70-tools/bmc/src` に加えて `.` と `scripts/nbb_compat` ——
;; `gftd.canvas` が cljs 側で `scripts.nbb-compat` を require するため
;; （`scripts/nbb_compat.cljs` が repo ルート直下にある）。CLI も同じ経路で読む。

(defn- ref-answers []
  (let [expr (str "(require '[gftd.score :as s])"
                  "(prn {:clamp (into {} (for [x " (pr-str clamp-inputs)
                  "] [x (s/clamp-bp x)]))"
                  " :ratio (into {} (for [[n d] " (pr-str ratio-inputs)
                  "] [[n d] (s/ratio-bp n d)]))"
                  " :rubric (into {} (for [t " (pr-str rubric-inputs)
                  "] [t (s/rubric-bp t)]))"
                  " :completeness (into {} (for [[b i] " (pr-str completeness-inputs)
                  "] [[b i] (s/completeness-bp b i)]))"
                  " :hypothesis (into {} (for [[g t] " (pr-str hypothesis-inputs)
                  "] [[g t] (s/hypothesis-bp g t)]))"
                  " :status (into {} (for [x " (pr-str status-inputs)
                  "] [x (s/status-bp x)]))"
                  " :acc-num (into {} (for [[n v w] " (pr-str acc-num-inputs)
                  "] [[n v w] (s/acc-num n v w)]))"
                  " :acc-den (into {} (for [[d w] " (pr-str acc-den-inputs)
                  "] [[d w] (s/acc-den d w)]))"
                  " :acc-mean (into {} (for [[n d] " (pr-str acc-mean-inputs)
                  "] [[n d] (s/acc-mean n d)]))"
                  " :fold (mapv (fn [ws] (s/fold-mean-bp ws)) " (pr-str fold-inputs) ")"
                  " :weights [(s/design-weight) (s/traction-weight)]"
                  " :bp-one s/bp-one})")
        [prog pre] (if nbb-cmd
                     (do (println "NOTE: using --nbb" nbb-cmd "instead of the pinned npx nbb")
                         [nbb-cmd []])
                     ["npx" ["--yes" "nbb"]])
        r (cp/spawnSync prog
                        (clj->js (concat pre ["--classpath" "70-tools/bmc/src:.:scripts/nbb_compat"
                                              "-e" expr]))
                        #js {:cwd root :encoding "utf8" :maxBuffer (* 32 1024 1024)})
        out (str (aget r "stdout"))
        err (str (aget r "stderr"))]
    (if (or (not= 0 (aget r "status")) (str/blank? out))
      (do (fail! "could not run 70-tools/bmc/src/gftd/score.cljc —"
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
;; **`:i64` の seam は BigInt。** 数をそのまま渡すと `invalid-i64` で trap し、
;; 戻りの BigInt を Number へ戻し忘れると **throw せずに静かに別の値**になる
;; （ADR-2608122000）。この kernel の戻りは全部 `:i64`。
(defn- ->guest [v] (if (number? v) (js/BigInt v) v))
(defn- <-guest [v] (if (string? v) v (js/Number v)))

;; fuel は instance ごとに尽きる。1 呼び出し 1 instance が基本だが、畳み込みだけは
;; 1 instance を使い回す（呼び出しごとに instantiate すると 20 case で数百回になる）。
;; **pair ハンドルは渡していない** —— kernel のアキュムレータは i64 2 本に
;; 平坦化してある（pair は artifact の境界を越えられない。score_core.kotoba 参照）。
(defn- kernel-caller [m]
  (fn [fname argv]
    (try {:ok (<-guest (apply (aget ((aget m "instantiateKotoba") #js {}) fname)
                              (map ->guest argv)))}
         (catch :default e {:trap (.-message e) :fn fname :args argv}))))

(defn- fold-with [m ws]
  ;; kernel 側だけを gate が畳む。cljc 側は score-product が使う fold を呼ぶ。
  (try
    (let [i ((aget m "instantiateKotoba") #js {})
          an (aget i "acc-num") ad (aget i "acc-den") am (aget i "acc-mean")
          b js/BigInt]
      (loop [num (b 0) den (b 0) xs (seq ws)]
        (if-let [[v w] (first xs)]
          (recur (an num (b v) (b w)) (ad den (b w)) (next xs))
          {:ok (js/Number (am num den))})))
    (catch :default e {:trap (.-message e)})))

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
    (doseq [[x want] (:clamp ref)]         (expect! k "clamp-bp" [x] want))
    (doseq [[[n d] want] (:ratio ref)]     (expect! k "ratio-bp" [n d] want))
    (doseq [[t want] (:rubric ref)]        (expect! k "rubric-bp" [t] want))
    (doseq [[[b i] want] (:completeness ref)] (expect! k "completeness-bp" [b i] want))
    (doseq [[[g t] want] (:hypothesis ref)]   (expect! k "hypothesis-bp" [g t] want))
    (doseq [[s want] (:status ref)]        (expect! k "status-bp" [s] want))
    (doseq [[[n v w] want] (:acc-num ref)] (expect! k "acc-num" [n v w] want))
    (doseq [[[d w] want] (:acc-den ref)]   (expect! k "acc-den" [d w] want))
    (doseq [[[n d] want] (:acc-mean ref)]  (expect! k "acc-mean" [n d] want))
    (let [[dw tw] (:weights ref)]
      (expect! k "design-weight" [] dw)
      (expect! k "traction-weight" [] tw))
    ;; 複合スコア（BMC = 5 次元の平均 / YC = design 1・traction 2 の加重平均）
    (doseq [[ws want] (map vector fold-inputs (:fold ref))]
      (swap! ran inc)
      (let [got (fold-with m ws)]
        (cond
          (:trap got) (fail! "fold" (pr-str ws) "TRAPPED —" (:trap got))
          (not= (:ok got) want)
          (fail! "fold" (pr-str ws) "— kernel says" (:ok got) "but cljc says" want))))))

;; ---------------------------------------------------------------------------

(defn- main! []
  (let [src      (path/join root "70-tools" "bmc" "kotoba" "score_core.kotoba")
        artifact (path/join root "70-tools" "bmc" "kotoba" "score_core.mjs")]
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
                 "score_core.kotoba sha256" want
                 "but score_core.mjs carries" (str got)
                 "— recompile with `kotoba -M compile … --target js`"))
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
        (if-let [ref (ref-answers)]
          (do
            ;; スケールが両側で同じであることを先に見る。ここがずれていたら
            ;; 以降の一致は「同じ間違いを 2 回している」ことの確認にしかならない。
            (when-not (= 10000 (:bp-one ref))
              (fail! "the cljc reference no longer uses 10000 bp = 1.0 —"
                     "bp-one is" (:bp-one ref)
                     "· the kernel's scale is fixed at 10000 and cannot follow"))
            ;; 表そのものが縮んでいないか（種別ごと・status は綴りの集合ごと）。
            (check-tables!)
            ;; **cljc 側が答えを返さなかった欄**を名指しする（nil を「一致した」と
            ;; 読まない）。踏まれなかった case は下の `ran` にも入らないので、
            ;; 床だけでは「答えが無い」と「答えが合っている」が区別できない。
            (doseq [[kw table] case-tables]
              (when-not (= (count table) (count (get ref kw)))
                (fail! "the cljc reference answered" (count (get ref kw)) "of" (count table)
                       "cases for" (name kw) "— a missing answer is not a match")))
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
                           (println "ADR-2608158000: スコア算術の正本は")
                           (println "70-tools/bmc/kotoba/score_core.kotoba。参照実装は")
                           (println "70-tools/bmc/src/gftd/score.cljc の 1 箇所だけで、")
                           (println "CLI・render・投影・allocate はそこを読む。")
                           (println "食い違ったら **kernel が正しい**。")
                           (println ".kotoba を直したら .mjs を必ず再生成する:")
                           (println "  kotoba -M compile <abs>/score_core.kotoba --target js \\")
                           (println "    --output <abs>/score_core.mjs")
                           (set! (.-exitCode js/process) 1))))))
          (set! (.-exitCode js/process) 1))))))

(main!)

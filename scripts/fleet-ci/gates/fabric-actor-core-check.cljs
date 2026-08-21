#!/usr/bin/env nbb
;; fabric-actor-core-check.cljs — fabric actor の名乗り・pulse 文・終了状態の
;; **2 実装が同じことを言うか**を見る（ADR-2608155000）。
;;
;; ## 何を突き合わせるか
;;
;;   正本    scripts/kotoba/fabric_actor_core.kotoba   （kotoba/pure、capability 0）
;;   参照    scripts/gftd/fabric_actor_core.cljc       （JVM の 2 script が実際に読む）
;;
;; ノードに kotoba ツールチェーンは無い（fleet が配るのは **この repo の tree だけ**で、
;; `orgs/` の amu は入らない）。だから org-id gate と同じく、**コンパイル済みの
;; `.mjs` を配って import する**。gate 自身はコンパイルしない。
;;
;; ## 4 つの検査
;;
;;   1. artifact <-> source —— `.mjs` の `sourceDigest` が `.kotoba` の sha256 と一致。
;;      `.kotoba` を直して再コンパイルを忘れると、**古い artifact に対して緑が出る**。
;;   2. purity —— `requiredCapabilities` が空。欄が **読めなかったとき空と読まない**
;;      （re-find が外れると nil で、素朴に書くと「capability 無し」で緑になる。
;;      org-id gate が同じ穴を踏んで直した）。
;;   3. parity —— 下の case 表の全件で kernel == cljc。ε は無い（全部 string と i64）。
;;   4. evidence floor —— 実際に走った case 数が `--min` 未満なら落とす。表が空に
;;      なった gate は exit 0 で何も主張しない（ADR-2608136000）。
;;
;; ## 使い方
;;
;;   nbb fabric-actor-core-check.cljs <repo-dir> [--min N]
;;
;; `<dir>` は **引数の先頭**に置く（fleet はそう渡す。CLAUDE.md の実測済みの罠）。

(ns fabric-actor-core-check
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

(def min-cases (js/parseInt (opt "--min" "120")))

;; `--nbb CMD` は **手でこの gate を検証するためだけ**にある。`npx --yes <pkg> <args>`
;; は作者の laptop（npm 11.12.1）でスクリプトパスをパッケージ名と誤読して落ちるが、
;; fleet のノード（10.9.8 / 11.17.0）では正しく走る（CLAUDE.md の実測）。override を
;; 用意しないと、この gate は **落ちることを一度も見せられないまま landing する**。
;; 使ったことを印字するのは、pin されていない interpreter が別の測定だから。
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
;; 表が product 9 件だけだと、綴りの分岐（欠測 / 0 / 空文字）が 1 つも踏まれない。
;; 実測 2026-08-15: 移行前の pulse 文は欠測を空文字にしていて、`runtime=/` が
;; 「0 台健全」とも「測れなかった」とも読めた。その分岐をここで名指しで踏む。

(def products
  ["itonami" "manimani" "isekai" "shinshi" "yukkuri" "dougaka" "animeka" "mangaka" "babiniku"])

;; product 名として来うる、あるいは来てはいけない形。1 引数関数の入力に使う。
(def adversarial-products
  [""                    ;; 空 —— handle が "-organism.aozora.app" になる形
   "a"                   ;; 1 文字
   "with-hyphen"         ;; ハイフンを含む
   "UPPER"               ;; 大小混在（どちらも fold しないことの確認）
   "日本語"               ;; 非 ASCII（連結だけなので通るはず）
   "x.y"])               ;; ドットを含む —— handle が多段になる形

(def one-arg-fns ["handle" "display-name" "description" "registration-text"])

(def text-inputs ["" "green" "degraded" "unknown" "0" " " "日本語"])

(def count-inputs [-1 0 1 2 9 10 42 1000])

(def exit-inputs [[0 0] [0 1] [1 0] [1 1] [9 0] [9 1] [9 9] [-1 0]])

;; ---------------------------------------------------------------------------
;; cljc 側: 参照実装を子プロセスで起動して答えだけ受け取る。**規則を写さない。**
;;
;; classpath は `scripts` —— この repo の JVM script が `:paths ["scripts"]` で
;; 読むのと同じ名前空間（`gftd.fabric-actor-core`）で読むため。ここだけ `.` に
;; すると、gate は script が実際に読むのとは別の名前で読むことになる。

(defn- ref-answers []
  (let [expr (str "(require '[gftd.fabric-actor-core :as c])"
                  "(prn {:one-arg (into {} (for [f " (pr-str one-arg-fns)
                  "        p " (pr-str (vec (concat products adversarial-products)))
                  "] [[f p] ((case f \"handle\" c/handle \"display-name\" c/display-name"
                  "            \"description\" c/description c/registration-text) p)]))"
                  " :seed-service (into {} (for [p " (pr-str (vec (concat products adversarial-products)))
                  "] [p (c/seed-service (c/handle p))]))"
                  " :text-or-unknown (into {} (for [s " (pr-str text-inputs)
                  "] [s (c/text-or-unknown s)]))"
                  " :count-text (into {} (for [n " (pr-str count-inputs)
                  "] [n (c/count-text n)]))"
                  " :runtime-text (into {} (for [h " (pr-str count-inputs)
                  " t " (pr-str count-inputs) "] [[h t] (c/runtime-text h t)]))"
                  " :pulse-text (into {} (for [p " (pr-str (vec (take 3 products)))
                  " s " (pr-str (vec (take 3 text-inputs)))
                  " d " (pr-str (vec (take 4 count-inputs)))
                  "] [[p s d] (c/pulse-text p s (c/runtime-text 7 10) \"passing\" d)]))"
                  " :exit-code (into {} (for [[a f] " (pr-str exit-inputs)
                  "] [[a f] (c/exit-code a f)]))"
                  " :products c/products})")
        [prog pre] (if nbb-cmd
                     (do (println "NOTE: using --nbb" nbb-cmd "instead of the pinned npx nbb")
                         [nbb-cmd []])
                     ["npx" ["--yes" "nbb"]])
        r (cp/spawnSync prog (clj->js (concat pre ["--classpath" "scripts" "-e" expr]))
                        #js {:cwd root :encoding "utf8" :maxBuffer (* 32 1024 1024)})
        out (str (aget r "stdout"))
        err (str (aget r "stderr"))]
    (if (or (not= 0 (aget r "status")) (str/blank? out))
      (do (fail! "could not run scripts/gftd/fabric_actor_core.cljc —"
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
;; fuel は instance ごとに尽きる（org-id gate の実測）。1 呼び出し 1 instance。

;; **`:i64` の seam は BigInt。** 数をそのまま渡すと `invalid-i64` で trap する
;; （2026-08-15 実測。ADR-2608122000 が数える host<->guest 非対称の 2 番目そのもの
;; で、JVM では `(long n)` と `n` が同じ値なので緑の JVM suite からは見えない）。
;; 戻りの BigInt も Number へ戻す —— 戻り側は throw すらしないので、放置すると
;; 「一致しない」ではなく「静かに別の値」になる。
(defn- ->guest [v] (if (number? v) (js/BigInt v) v))
;; この kernel が返すのは `:string` か `:i64` の 2 つだけなので、文字列でなければ
;; i64（= BigInt）である。`typeof` を書きたいところだが SCI には `js*` が無い。
(defn- <-guest [v] (if (string? v) v (js/Number v)))

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
    ;; 1 引数の名乗り
    (doseq [[[fname p] want] (:one-arg ref)]
      (expect! k fname [p] want))
    (doseq [[p want] (:seed-service ref)]
      (expect! k "seed-service" [(str p "-organism.aozora.app")] want))
    ;; 綴りの分岐
    (doseq [[s want] (:text-or-unknown ref)] (expect! k "text-or-unknown" [s] want))
    (doseq [[n want] (:count-text ref)] (expect! k "count-text" [n] want))
    (doseq [[[h t] want] (:runtime-text ref)] (expect! k "runtime-text" [h t] want))
    ;; pulse 文（runtime は kernel 側でも 7/10 を作ってから渡す）
    (let [rt (:ok (k "runtime-text" [7 10]))]
      (doseq [[[p s d] want] (:pulse-text ref)]
        (expect! k "pulse-text" [p s rt "passing" d] want)))
    ;; 終了状態
    (doseq [[[a f] want] (:exit-code ref)]
      (expect! k "exit-code" [a f] (js/Number want)))))

;; ---------------------------------------------------------------------------

(defn- main! []
  (let [src      (path/join root "scripts" "kotoba" "fabric_actor_core.kotoba")
        artifact (path/join root "scripts" "kotoba" "fabric_actor_core.mjs")]
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
                 "fabric_actor_core.kotoba sha256" want
                 "but fabric_actor_core.mjs carries" (str got)
                 "— recompile with `kotoba -M compile … --target js`"))
        (cond
          (nil? caps)
          (fail! "could not read requiredCapabilities from the artifact"
                 "— the purity check cannot run, and absence is not a pass")
          (not= "" (str/trim caps))
          (fail! "the fabric actor core is no longer pure — requiredCapabilities:" caps))
        (println "artifact<->source:" (if (= want got) "bound" "BROKEN")
                 "· capabilities:" (cond (nil? caps) "UNREADABLE"
                                         (= "" (str/trim caps)) "none"
                                         :else caps))
        (if-let [ref (ref-answers)]
          (do
            ;; product 一覧が静かに縮むと、下の case 表は縮まないのに **実在しない
            ;; product について両実装が一致している**ことを確かめ続ける。床と、
            ;; 一致そのものを見る（この gate の一覧は参照実装の一覧の写しであって、
            ;; 別に維持してよい 3 つ目の正本ではない）。
            (when (< (count (:products ref)) 9)
              (fail! "the cljc reference lists only" (count (:products ref))
                     "products — the case table would go on checking names nobody ships"))
            (when-not (= (set products) (set (:products ref)))
              (fail! "the probe list and gftd.fabric-actor-core/products have diverged:"
                     "gate has" (pr-str (sort products))
                     "· reference has" (pr-str (sort (:products ref)))))
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
                           (println "ADR-2608155000: 名乗り・pulse 文・終了状態の正本は")
                           (println "scripts/kotoba/fabric_actor_core.kotoba。cljc 側は")
                           (println "scripts/gftd/fabric_actor_core.cljc の 1 箇所だけで、")
                           (println "register_fabric_actors.clj と publish_fabric_actor_status.clj は")
                           (println "そこを読む。食い違ったら **kernel が正しい**。")
                           (set! (.-exitCode js/process) 1))))))
          (set! (.-exitCode js/process) 1))))))

(main!)

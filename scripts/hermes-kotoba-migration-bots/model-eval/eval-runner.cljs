#!/usr/bin/env nbb
(ns eval-runner
  "clj/cljc -> .kotoba migration eval: cost per ACCEPTED migration, per model.

  The oracle is NOT the model's word and NOT `compiles`. For every task the
  harness owns the input set, computes ground truth by running the ORIGINAL
  .cljc under nbb, then runs the model's COMPILED artifact on the same inputs
  and compares. The model never supplies an expected value.

  Verdicts are distinct on purpose (CLAUDE.md q4: skipped and passed must be
  distinguishable in the output):
    :pass                 compiled AND every input agreed with the Clojure
    :parity-fail          compiled, ran, and at least one value disagreed
    :compile-fail         amu rejected it
    :no-code              the model returned no extractable kotoba module
    :parity-not-measured  compiled but this task's return shape has no
                          cross-runtime comparison defined -- NOT a pass
    :api-error            the model endpoint did not answer

  exit 0 report produced   exit 2 REFUSED (could not measure at all)"
  (:require [clojure.string :as str]
            [cljs.reader :as edn]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]))

(def argv (vec (or *command-line-args* [])))
(defn arg [f d] (let [i (.indexOf argv f)] (if (neg? i) d (get argv (inc i) d))))

(def root      (arg "--root" "/Users/junkawasaki/github/com-junkawasaki"))
(def tasks-in  (arg "--tasks" nil))
(def models    (str/split (arg "--models" "murakumo-main") #","))
(def out-path  (arg "--out" "eval-results.edn"))
(def reps      (js/parseInt (arg "--reps" "1") 10))
(def api       (arg "--api" "https://api.murakumo.cloud/v1/chat/completions"))
(def max-tok   (js/parseInt (arg "--max-tokens" "2000") 10))
(def workdir   (arg "--workdir" "/tmp/kotoba-eval"))
(def repair    (js/parseInt (arg "--repair" "0") 10))
(def amu-bin   (path/join root "orgs" "kotoba-lang" "amu" "bin" "amu"))

(defn refuse! [why] (println "REFUSED —" why) (.exit js/process 2))

(defn sh [cmd args opts]
  (let [r (cp/spawnSync cmd (clj->js args)
                        (clj->js (merge {:encoding "utf8" :maxBuffer (* 32 1024 1024)} opts)))]
    {:status (if (nil? (.-status r)) 1 (.-status r))
     :stdout (or (.-stdout r) "") :stderr (or (.-stderr r) "")}))

;; ---------------------------------------------------------------- tasks

(when-not tasks-in (refuse! "--tasks <file.edn> is required"))
(when-not (.existsSync fs tasks-in) (refuse! (str tasks-in " does not exist")))
(def tasks (edn/read-string (.readFileSync fs tasks-in "utf8")))
(when (empty? tasks) (refuse! "task file is empty — nothing to measure"))

;; ------------------------------------------------- ground truth (Clojure)

(defn ground-truth
  "Run the ORIGINAL .cljc under nbb. The model has no influence here."
  [{:keys [src-path ns-name fn-name inputs]}]
  (let [dir  (path/join root (path/dirname (str/replace src-path #"/src/.*$" "/src")))
        cpdir (let [i (str/index-of src-path "/src/")]
                (path/join root (subs src-path 0 (+ i 4))))
        expr (str "(require '[" ns-name " :as T]) "
                  "(doseq [args " (pr-str (vec inputs)) "] "
                  "(println (str \"GT|\" (pr-str (apply T/" fn-name " args)))))")
        r (sh "nbb" ["--classpath" cpdir "-e" expr] {:cwd root :timeout 120000})]
    (if (zero? (:status r))
      {:ok true :values (->> (str/split-lines (:stdout r))
                             (filter #(str/starts-with? % "GT|"))
                             (mapv #(subs % 3)))}
      {:ok false :why (str "nbb exit " (:status r) ": "
                           (str/trim (subs (:stderr r) 0 (min 300 (count (:stderr r))))))})))

;; ---------------------------------------------------------------- prompt

(def kotoba-rules
"Migrate one small pure Clojure function to Kotoba (.kotoba).

Kotoba is a TYPED s-expression language. It is NOT Clojure. Every fact below
was verified by compiling and RUNNING it against this repo's amu compiler.

MODULE SHAPE (both parts required):

  (ns <module-name>
    (:export [<fn-name> ...]))

  (defn <fn-name> [<arg> :<type> ...] :<return-type>
    <body>)

Every parameter AND the return type must be annotated.
Types: :string :i64 :bool :keyword :document

STRINGS -- Clojure's string functions DO NOT EXIST. Use these:
  (string-length s)              NOT (count s)      -- count rejects :string
  (string-substring s start end) NOT (subs s a b)   -- subs is not a builtin
  (string=? a b)                 NOT (= a b)        -- = rejects strings
  (string-concat a b)            (str ...) does not concatenate i64
  (string-from-i64 n)            integer -> string. (str n) does NOT do this.
  also: string-contains, string-index-of, string-join, string-prefix,
        string-replace-all, string-split-count

WORKS: let cond if when and or not = < > <= >= + - * (on :i64 and :bool)

DOES NOT EXIST: apply, repeat, format, map, reduce, interop, throw,
  variadic user functions, defmacro, eval, atoms, host calls.
Build repetition with explicit cond branches or recursion.

A REFERENCE MODULE that compiles and runs correctly:

```kotoba
(ns probe
  (:export [starts? pad2]))

(defn starts? [s :string i :i64 lit :string] :bool
  (string=? lit (string-substring s i (+ i (string-length lit)))))

(defn pad2 [n :i64] :string
  (let [s (string-from-i64 n)]
    (cond (< (string-length s) 2) (string-concat \"0\" s)
          :else s)))
```

Return ONLY the .kotoba module source in one ```kotoba fenced block.
No prose before or after.")

(defn build-prompt [{:keys [source fn-name]}]
  (str kotoba-rules
       "\n\nMigrate this Clojure source. Preserve the behaviour of `" fn-name
       "`, and export it under exactly that name.\n\n```clojure\n" source "\n```\n"))

;; ---------------------------------------------------------------- model

(defn call-model [model messages]
  (let [body (js/JSON.stringify
              (clj->js {:model model :max_tokens max-tok :temperature 0.2
                        :messages messages}))
        t0 (js/Date.now)
        r (sh "curl" ["-sS" "--max-time" "600" "-X" "POST" api
                      "-H" "Content-Type: application/json" "-d" body] {})
        ms (- (js/Date.now) t0)]
    (if-not (zero? (:status r))
      {:ok false :why (str "curl exit " (:status r)) :ms ms}
      (try
        (let [j (js/JSON.parse (:stdout r))]
          (if (.-error j)
            {:ok false :why (str "api error: " (js/JSON.stringify (.-error j))) :ms ms}
            {:ok true :ms ms
             :text (or (some-> j .-choices (aget 0) .-message .-content) "")
             :usage {:prompt (or (some-> j .-usage .-prompt_tokens) 0)
                     :completion (or (some-> j .-usage .-completion_tokens) 0)}
             :served (or (.-model j) model)}))
        (catch :default e
          {:ok false :ms ms
           :why (str "unparseable response: "
                     (subs (:stdout r) 0 (min 200 (count (:stdout r)))))})))))

(defn extract-kotoba [text]
  (let [m (or (re-find #"(?s)```(?:kotoba|clojure)?\s*\n(.*?)```" text)
              [nil nil])
        code (or (second m) "")]
    (when (str/includes? code "(ns ") (str/trim code))))

;; ---------------------------------------------------------------- run one

(defn run-artifact
  "Run the COMPILED module on the harness's inputs, print one line per input."
  [mjs {:keys [fn-name inputs]}]
  (let [calls (str/join ";"
               (map (fn [args]
                      (str "try{out.push('GT|'+fmt(m['" fn-name "']("
                           (str/join "," (map (fn [a] (if (string? a) (pr-str a)
                                                        (str a "n"))) args))
                           ")))}catch(e){out.push('ERR|'+e.message)}"))
                    inputs))
        js (str "import {instantiateKotoba} from " (pr-str mjs) ";"
                "const m=instantiateKotoba({});const out=[];"
                "const fmt=v=>String(v);"
                calls ";console.log(out.join('\\n'));")
        r (sh "node" ["--input-type=module" "-e" js] {:timeout 120000})]
    {:status (:status r) :lines (str/split-lines (str/trim (:stdout r)))
     :stderr (:stderr r)}))

(defn normalise
  "Clojure pr-str and JS both render a string with surrounding quotes and an
   integer without. That is the only shape this comparison claims to cover."
  [s] (-> s str/trim (str/replace #"^GT\|" "") (str/replace #"^\"|\"$" "")))

(defn judge
  "Compile, and only if it compiles, RUN it and compare to the Clojure.
   Returns [verdict why] -- :compile-fail carries the diagnostic so a repair
   round can be fed the compiler's own words."
  [task dir code]
  (let [kp (path/join dir (str (:id task) ".kotoba"))
        mp (path/join dir (str (:id task) ".mjs"))]
    (.writeFileSync fs kp code)
    (let [c (sh amu-bin ["-M" "compile" kp "--target" "js-browser"
                         "--output" mp] {:cwd root :timeout 300000})]
      (if-not (zero? (:status c))
        [:compile-fail (str/trim (subs (:stdout c) 0 (min 400 (count (:stdout c)))))]
        (if-not (:comparable task)
          [:parity-not-measured nil]
          (let [a (run-artifact mp task)]
            (if-not (zero? (:status a))
              [:parity-fail (str "artifact did not run: "
                                 (str/trim (subs (:stderr a) 0 (min 200 (count (:stderr a))))))]
              (let [got (mapv normalise (:lines a))
                    exp (mapv normalise (:ground-truth task))]
                (if (= got exp)
                  [:pass nil]
                  [:parity-fail (str "expected " (pr-str exp) " got " (pr-str got))])))))))))

(defn run-one
  "One (model, task, rep). With --repair N, a :compile-fail is fed the
   COMPILER DIAGNOSTIC ONLY and retried, up to N extra rounds.

   The expected values are never sent to the model. A parity-fail is terminal:
   showing the model what the Clojure returned would stop measuring whether it
   can migrate and start measuring whether it can copy an answer."
  [model task rep]
  (let [dir (path/join workdir (str model "-" (:id task) "-" rep))]
    (.mkdirSync fs dir #js {:recursive true})
    (loop [msgs  [{:role "user" :content (build-prompt task)}]
           round 0
           ms    0
           ptok  0
           ctok  0]
      (let [res  (call-model model msgs)
            ms   (+ ms (:ms res))
            ptok (+ ptok (get-in res [:usage :prompt] 0))
            ctok (+ ctok (get-in res [:usage :completion] 0))
            base {:ms ms :usage {:prompt ptok :completion ctok}
                  :served (:served res) :rounds round}]
        (cond
          (not (:ok res))
          (assoc base :verdict :api-error :why (:why res))

          :else
          (let [code (extract-kotoba (:text res))]
            (if-not code
              (assoc base :verdict :no-code)
              (let [[verdict why] (judge task dir code)]
                (if (and (= verdict :compile-fail) (< round repair))
                  (recur (conj msgs
                               {:role "assistant" :content (:text res)}
                               {:role "user"
                                :content (str "That module was REJECTED by the Kotoba compiler.\n\n"
                                              "Compiler diagnostic:\n" why "\n\n"
                                              "Fix the module so it compiles. Return ONLY the corrected "
                                              "```kotoba fenced module, no prose.")})
                         (inc round) ms ptok ctok)
                  (assoc base :verdict verdict :why why))))))))))

;; ---------------------------------------------------------------- main

(println (str "SCANNED\t" (count tasks) " tasks x " (count models)
              " models x " reps " reps = " (* (count tasks) (count models) reps) " runs"))
(println)

;; ground truth first — a task whose Clojure we cannot run is not a task
(def prepared
  (vec (for [t tasks]
         (let [g (ground-truth t)]
           (println (str "GROUND-TRUTH\t" (:id t) "\t"
                         (if (:ok g) (str "ok " (pr-str (:values g)))
                             (str "UNAVAILABLE — " (:why g)))))
           (assoc t :ground-truth (:values g)
                    :comparable (boolean (and (:ok g) (seq (:values g)) (:compare t))))))))
(println)

(def results
  (vec (for [m models t prepared r (range reps)]
         (let [v (run-one m t r)]
           (println (str "RUN\t" m "\t" (:id t) "\t#" r "\t" (name (:verdict v))
                         "\tr" (:rounds v 0) "\t" (:ms v) "ms"
                         "\ttok=" (get-in v [:usage :prompt] 0) "/" (get-in v [:usage :completion] 0)
                         (if (:why v) (str "\t" (:why v)) "")))
           (assoc v :model m :task (:id t) :rep r)))))

(println)
(doseq [m models]
  (let [rs (filter #(= m (:model %)) results)
        n  (count rs)
        pass (count (filter #(= :pass (:verdict %)) rs))
        comp-ok (count (remove #(#{:compile-fail :no-code :api-error} (:verdict %)) rs))
        ctok (reduce + (map #(get-in % [:usage :completion] 0) rs))
        ptok (reduce + (map #(get-in % [:usage :prompt] 0) rs))
        ms  (reduce + (map #(or (:ms %) 0) rs))]
    (println (str "MODEL\t" m))
    (println (str "  runs=" n " pass=" pass " compiled=" comp-ok
                  "  pass-rate=" (if (pos? n) (.toFixed (* 100 (/ pass n)) 1) "n/a") "%"))
    (println (str "  tokens in/out=" ptok "/" ctok "  wall=" (.toFixed (/ ms 1000) 1) "s"))
    (println (str "  tokens-per-accepted=" (if (pos? pass) (js/Math.round (/ (+ ptok ctok) pass)) "INFINITE — nothing accepted")))
    (println (str "  seconds-per-accepted=" (if (pos? pass) (.toFixed (/ (/ ms 1000) pass) 1) "INFINITE")))
    (println (str "  repair-rounds-used=" (reduce + (map #(:rounds % 0) rs))
                  "  passes-needing-repair=" (count (filter #(and (= :pass (:verdict %)) (pos? (:rounds % 0))) rs))))
    (doseq [[v c] (sort-by (comp - val) (frequencies (map :verdict rs)))]
      (println (str "    " (name v) "\t" c)))
    (println)))

(.writeFileSync fs out-path (pr-str {:tasks (mapv #(dissoc % :source) prepared) :results results}))
(println (str "WROTE\t" out-path))

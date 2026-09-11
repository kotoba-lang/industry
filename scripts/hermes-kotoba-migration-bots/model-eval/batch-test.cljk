#!/usr/bin/env nbb
(ns batch-test
  "Does migrating N functions in ONE call beat N calls?

  89.8% of the baseline's tokens were the rules block, re-sent once per task.
  Batching sends it once for N tasks. What this measures is whether quality
  survives -- a cheaper call that stops passing is not cheaper.

  Graded exactly like the per-task runner: compile, RUN the artifact, compare
  against values the ORIGINAL Clojure produced. Expected values are never sent
  to the model."
  (:require [clojure.string :as str] [cljs.reader :as edn]
            ["node:fs" :as fs] ["node:path" :as path] ["node:child_process" :as cp]))

(def root "/Users/junkawasaki/github/com-junkawasaki")
(def amu  (path/join root "orgs/kotoba-lang/amu/bin/amu"))
(def wd   "/tmp/kotoba-batch")
(def S    "/tmp/claude-501/-Users-junkawasaki-github-com-junkawasaki/e1aa89a3-5859-4582-b41a-303df0dd797e/scratchpad")
(def reps (js/parseInt (or (first *command-line-args*) "3") 10))

(defn sh [c a o]
  (let [r (cp/spawnSync c (clj->js a) (clj->js (merge {:encoding "utf8" :maxBuffer 33554432} o)))]
    {:status (if (nil? (.-status r)) 1 (.-status r))
     :stdout (or (.-stdout r) "") :stderr (or (.-stderr r) "")}))

;; the SAME rules text the per-task runner used -- read from it, never retyped
(def rules
  (let [s (.readFileSync fs (path/join S "eval-runner.cljs") "utf8")
        i (str/index-of s "\"Migrate one small pure Clojure function")
        j (str/index-of s "Return ONLY the .kotoba module source in one")]
    (subs s (inc i) j)))

;; three tasks the harness can actually grade, with ground truth measured
;; earlier by running the original .cljc under nbb
(def tasks
  [{:id "time-pad4"  :fn "pad4"
    :src "(defn pad4 [n] (let [s (str n)] (str (apply str (repeat (- 4 (count s)) \\0)) s)))"
    :inputs [[7] [1234] [0] [12345]] :gt ["0007" "1234" "0000" "12345"]}
   {:id "time-pad2"  :fn "pad2"
    :src "(defn pad2 [n] (let [s (str n)] (if (< (count s) 2) (str \"0\" s) s)))"
    :inputs [[7] [12] [0] [123]] :gt ["07" "12" "00" "123"]}
   {:id "json-starts" :fn "starts?"
    :src "(defn starts? [s i lit] (= lit (subs s i (min (count s) (+ i (count lit))))))"
    :inputs [["hello" 0 "he"] ["hello" 1 "he"] ["hello" 3 "lo"]] :gt ["true" "false" "true"]}])


;; filler: the other nine candidates from the same ranked set. They are NOT
;; graded -- they exist only to make the batch bigger, so batch SIZE is the
;; single variable between this run and batch-of-3.
(def filler
  [{:fn "notification" :src "(defn notification [method params] {:jsonrpc \"2.0\" :method method :params (or params {})})"}
   {:fn "pad-left"  :src "(defn pad-left [s width ch] (let [pad (max 0 (- width (count s)))] (str (join (repeat pad ch)) s)))"}
   {:fn "ws?"       :src "(defn ws? [ch] (contains? #{\\space \\tab \\newline \\return} ch))"}
   {:fn "response"  :src "(defn response [id result] {:jsonrpc \"2.0\" :id id :result result})"}
   {:fn "len"       :src "(defn len [buf] (count @buf))"}
   {:fn "closed?"   :src "(defn closed? [ch] (true? (:closed? ch)))"}
   {:fn "instant-millis" :src "(defn instant-millis [i] (:time/instant i))"}
   {:fn "pad-right" :src "(defn pad-right [s width ch] (let [pad (max 0 (- width (count s)))] (str s (join (repeat pad ch))))) "}
   {:fn "surface->cap" :src "(defn surface->cap [s] (str \"net:\" (name s)))"}])

(def n-filler (js/parseInt (or (second *command-line-args*) "0") 10))
(def all-items (concat tasks (take n-filler filler)))

(defn call [messages]
  (let [body (js/JSON.stringify (clj->js {:model "murakumo-main" :max_tokens 3000
                                          :temperature 0.2 :messages messages}))
        t0 (js/Date.now)
        r (sh "curl" ["-sS" "--max-time" "600" "-X" "POST"
                      "https://api.murakumo.cloud/v1/chat/completions"
                      "-H" "Content-Type: application/json" "-d" body] {})
        ms (- (js/Date.now) t0)
        _ (when (or (str/blank? (:stdout r)) (not (str/includes? (:stdout r) "choices")))
            (println (str "  DEBUG curl status=" (:status r)
                          " stdout[0:300]=" (subs (:stdout r) 0 (min 300 (count (:stdout r))))
                          " stderr[0:200]=" (subs (:stderr r) 0 (min 200 (count (:stderr r)))))))
        j (js/JSON.parse (if (str/blank? (:stdout r)) "{}" (:stdout r)))]
    {:ms ms :text (or (some-> j .-choices (aget 0) .-message .-content) "")
     :ptok (or (some-> j .-usage .-prompt_tokens) 0)
     :ctok (or (some-> j .-usage .-completion_tokens) 0)}))

(defn blocks [text]
  (mapv #(str/trim (second %))
        (re-seq #"(?s)```(?:kotoba|clojure)?\s*\n(.*?)```" text)))

(defn grade [dir tag code task]
  (let [kp (path/join dir (str tag ".kotoba")) mp (path/join dir (str tag ".mjs"))]
    (.writeFileSync fs kp code)
    (let [c (sh amu ["-M" "compile" kp "--target" "js-browser" "--output" mp]
                {:cwd root :timeout 300000})]
      (if-not (zero? (:status c))
        :compile-fail
        (let [calls (str/join ";" (map (fn [args]
                       (str "try{o.push(String(m['" (:fn task) "']("
                            (str/join "," (map #(if (string? %) (pr-str %) (str % "n")) args))
                            ")))}catch(e){o.push('ERR')}")) (:inputs task)))
              js (str "import {instantiateKotoba} from " (pr-str mp) ";"
                      "const m=instantiateKotoba({});const o=[];" calls
                      ";console.log(o.join('\\n'));")
              a (sh "node" ["--input-type=module" "-e" js] {:timeout 120000})]
          (if-not (zero? (:status a)) :parity-fail
            (if (= (str/split-lines (str/trim (:stdout a))) (:gt task)) :pass :parity-fail)))))))

(.mkdirSync fs wd #js {:recursive true})
(println (str "MODE\tbatch-of-" (count all-items) "\tgraded=" (count tasks) "\treps=" reps))
(println)

(def out
  (vec (for [r (range reps)]
         (let [dir (path/join wd (str "rep" r))
               _   (.mkdirSync fs dir #js {:recursive true})
               prompt (str rules
                           "Return ONE ```kotoba fenced block PER FUNCTION, in the same order as given,"
                           " " (count all-items) " blocks total. No prose.\n\n"
                           (str/join "\n\n" (map-indexed
                             (fn [i t] (str "### " (inc i) ". migrate `" (:fn t)
                                            "`, export it under exactly that name\n```clojure\n"
                                            (:src t) "\n```")) all-items)))
               res (call [{:role "user" :content prompt}])
               bs  (blocks (:text res))
               vs  (vec (map-indexed
                          (fn [i t] (if (< i (count bs))
                                      (grade dir (str (:id t) "-" r) (nth bs i) t)
                                      :no-code)) tasks))]
           (println (str "REP\t" r "\tblocks=" (count bs) "\t"
                         (str/join " " (map #(str (:id %1) "=" (name %2)) tasks vs))
                         "\tptok=" (:ptok res) " ctok=" (:ctok res) " ms=" (:ms res)))
           {:verdicts vs :ptok (:ptok res) :ctok (:ctok res) :ms (:ms res)}))))

(println)
(def pass (reduce + (map #(count (filter #{:pass} (:verdicts %))) out)))
(def ptok (reduce + (map :ptok out)))
(def ctok (reduce + (map :ctok out)))
(def ms   (reduce + (map :ms out)))
(def n    (* reps (count tasks)))
(println (str "BATCH  runs=" n " pass=" pass
              "  pass-rate=" (.toFixed (* 100.0 (/ pass n)) 1) "%"))
(println (str "       tokens in/out=" ptok "/" ctok "  total=" (+ ptok ctok)
              "  wall=" (.toFixed (/ ms 1000) 1) "s"))
(println (str "       TOKENS-PER-ACCEPTED=" (if (pos? pass) (js/Math.round (/ (+ ptok ctok) pass)) "INF")
              "  SECONDS-PER-ACCEPTED=" (if (pos? pass) (.toFixed (/ (/ ms 1000.0) pass) 1) "INF")))

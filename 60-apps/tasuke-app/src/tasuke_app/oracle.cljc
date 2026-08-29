(ns tasuke-app.oracle
  "The seam to the guest. It resolves the artifact, executes an export, and
  decides nothing.

  `kotoba/triage_core.kotoba` holds the decisions; `resources/tasuke_app/oracle/
  triage-core.kir.edn` is what was compiled from it and what ships. Both runtimes
  execute THAT — the JVM reads the resource, the browser gets it inlined by
  `kir-embed/embedded-kir` at compile time. There is no ClojureScript
  reimplementation of any rule in this app, which is the point: jp-go-dds's oracle
  states plainly that on ClojureScript its rules remain a second implementation
  because crossing the seam would charge 170 consumers for it. This app has one
  consumer, so it crosses.

  ## Measured ABI notes (2026-08-29, kir 6d08e3c / amu 1e21a1f)

  - A top-level `:i64` argument is coerced from a host integer on both runtimes.
    An `:i64` INSIDE a record is not (ClojureScript demands a js/BigInt), which is
    why the guest declares no records at all.
  - An `:i64` RESULT comes back as a host integer on the JVM and as a js/BigInt on
    ClojureScript; `->long` normalizes it. Anything that reaches the UI as a number
    goes through there.
  - Multi-value answers are newline-joined strings, split here. Empty means empty,
    not missing — `lines` returns `[]` for `\"\"` rather than `[\"\"]`."
  (:require [clojure.string :as str]
            [kotoba.kir :as kir]
            #?(:clj [clojure.edn :as edn])
            #?(:clj [clojure.java.io :as io]))
  #?(:cljs (:require-macros [tasuke-app.kir-embed :as embed])))

(def fuel
  "Execution fuel per call. One value, so a call that runs under a test runs on
  the page."
  262144)

(def triage-core
  #?(:clj (let [path "tasuke_app/oracle/triage-core.kir.edn"]
            (if-let [url (io/resource path)]
              (edn/read-string (slurp url))
              (throw (ex-info "shipped decision core is missing — run `clojure -M:gen`"
                              {:path path}))))
     :cljs (embed/embedded-kir)))

(defn call
  "Execute an export of the shipped core. Args and result are guest ABI values."
  [export args]
  (kir/execute triage-core (symbol (name export)) (vec args) {:fuel fuel}))

(defn- ->long [v]
  #?(:clj (long v)
     :cljs (js/Number v)))

(defn- lines [s]
  (if (str/blank? s) [] (str/split-lines s)))

;; --- the exports, named once -----------------------------------------------

(defn classify
  "The scam KIND for routing — never a verdict (G4). An explicit kind from the
  member wins over the narrative scan."
  ([narrative] (classify narrative ""))
  ([narrative explicit] (call :classify [(str narrative) (str explicit)])))

(defn severity [kind loss-jpy ongoing?]
  (call :severity [(str kind) (->long (or loss-jpy 0)) (boolean ongoing?)]))

(defn windows   [kind] (lines (call :windows [(str kind)])))
(defn actions   [kind] (lines (call :actions [(str kind)])))
(defn deadlines [kind] (lines (call :deadlines [(str kind)])))
(defn ja-kind   [kind] (call :ja-kind [(str kind)]))

(defn documents-for-kind [kind loss-jpy]
  (lines (call :documents-for-kind [(str kind) (->long (or loss-jpy 0))])))

(defn support-cost-jpy
  "G1 — 助 is free, and the guest is where that is true: there is no parameter
  that could make it anything else."
  []
  (->long (call :support-cost-jpy [])))

(defn recovery-plan
  "アカウント復旧手順書 — the member executes it; 助 never logs in for them."
  [service]
  (call :recovery-plan [(str service)]))

(defn platform-request
  "プラットフォーム凍結・復旧依頼 — the member sends it; 助 never submits."
  [{:keys [platform account-id occurred kind]}]
  (call :platform-request [(str platform) (str account-id) (str occurred) (str kind)]))

(defn triage
  "One call site for the whole answer, so a view never assembles it by hand."
  [{:keys [narrative explicit loss-jpy ongoing?]}]
  (let [kind (classify narrative explicit)]
    {:kind      kind
     :ja-kind   (ja-kind kind)
     :severity  (severity kind loss-jpy ongoing?)
     :windows   (windows kind)
     :actions   (actions kind)
     :deadlines (deadlines kind)
     :documents (documents-for-kind kind loss-jpy)
     :cost-jpy  (support-cost-jpy)}))

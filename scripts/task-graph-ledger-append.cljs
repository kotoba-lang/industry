#!/usr/bin/env nbb
;; scripts/task-graph-ledger-append.cljs — append-only progress log for
;; 90-docs/task-graphs/task-graph.datoms.edn (workflow/task topology).
;;
;; Why this exists: the base catalog declares topology only (:task/id,
;; :task/requires) and is never hand-rewritten (see header of
;; task-graph.datoms.edn and ADR-2607202800) — same reasoning as
;; 90-docs/adr-ledger/adr-ledger.edn: DataScript has no as-of/history API, so
;; progress must live in a separate append-only event stream that a projector
;; (scripts/task-graph-query.cljs `ready-tasks`) folds against the base.
;;
;; This repo runs many concurrent agents/sessions (CLAUDE.md 「並行エージェント
;; 運用」節) — append takes an exclusive filesystem lock
;; (90-docs/task-graphs/.append.lock, O_EXCL create) around the
;; read-last-seq -> write critical section, with a stale-lock takeover, same
;; pattern as scripts/adr-ledger-append.cljs.
;;
;; Usage:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/task-graph-ledger-append.cljs \
;;     --task A-gitattributes-alias --type completed \
;;     --summary "merged kotoba-lang/kotoba#<pr>" \
;;     --actor "Jun Kawasaki"
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/task-graph-ledger-append.cljs --verify
;;     — checks :event/seq is unique+monotonic and every :task/id referenced
;;       by an event actually exists under 90-docs/task-graphs/*.datoms.edn.
;;       exit 1 on failure.
;;
;; :event/type is one of: dispatched | started | completed | blocked | skipped

(require '[scripts.nbb-compat :refer [slurp spit-append file file-seq exit format sleep!]]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))

(def ledger-dir "90-docs/task-graphs")
(def ledger-path (str ledger-dir "/task-graph-ledger.edn"))
(def lock-path (str ledger-dir "/.append.lock"))
(def lock-stale-ms 30000)
(def lock-retry-ms 200)
(def lock-max-wait-ms 15000)

(def header
  (str
   ";; 90-docs/task-graphs/task-graph-ledger.edn — append-only progress events\n"
   ";; for 90-docs/task-graphs/task-graph.datoms.edn (one EDN map per line,\n"
   ";; DataScript/Datomic-transactable). The base catalog is topology-only and\n"
   ";; not hand-rewritten; progress is recorded here instead and folded by\n"
   ";; scripts/task-graph-query.cljs. Do NOT hand-edit existing lines; only\n"
   ";; append new lines, and only via scripts/task-graph-ledger-append.cljs so\n"
   ";; :event/seq stays monotonic. See ADR-2607202800.\n"))

(defn parse-args [argv]
  (loop [args (seq argv) out {}]
    (if (empty? args)
      out
      (let [[k v & more] args]
        (cond
          (= k "--verify") (recur (rest args) (assoc out :verify true))
          (and (string? k) (str/starts-with? k "--"))
          (recur more (assoc out (keyword (subs k 2)) v))
          :else (recur (rest args) out))))))

;; ---------- locking ----------

(defn- lock-status []
  (try
    {:exists? true
     :age-ms (- (.getTime (js/Date.)) (.getTime (.-mtime (.statSync fs lock-path))))}
    (catch :default _ {:exists? false})))

(defn- try-acquire-lock! []
  (try
    (.closeSync fs (.openSync fs lock-path "wx"))
    (.writeFileSync fs lock-path (str "pid=" (.-pid js/process)
                                       " at=" (.toISOString (js/Date.)) "\n"))
    true
    (catch :default e
      (if (= (.-code e) "EEXIST") false (throw e)))))

(defn acquire-lock! []
  (.mkdirSync fs ledger-dir #js {:recursive true})
  (loop [waited 0]
    (cond
      (try-acquire-lock!) :acquired

      :else
      (let [{:keys [exists? age-ms]} (lock-status)]
        (cond
          (and exists? (> age-ms lock-stale-ms))
          (do (println (str "[task-graph-ledger-append] taking over stale lock (>" lock-stale-ms "ms old): " lock-path))
              (try (.unlinkSync fs lock-path) (catch :default _ nil))
              (recur waited))

          (>= waited lock-max-wait-ms)
          (throw (ex-info (str "timed out waiting for " lock-path
                                " after " lock-max-wait-ms "ms — another append in progress?")
                           {}))

          :else
          (do (sleep! lock-retry-ms) (recur (+ waited lock-retry-ms))))))))

(defn release-lock! []
  (try (.unlinkSync fs lock-path) (catch :default _ nil)))

(defn with-lock* [thunk]
  (acquire-lock!)
  (try (thunk) (finally (release-lock!))))

;; ---------- ledger read ----------

(defn existing-lines []
  (let [f (file ledger-path)]
    (if (.isFile f)
      (->> (str/split-lines (slurp ledger-path))
           (remove str/blank?)
           (remove #(str/starts-with? (str/trim %) ";")))
      [])))

(defn existing-events []
  (keep (fn [line] (try (edn/read-string line) (catch :default _ nil)))
        (existing-lines)))

(defn next-seq []
  (let [seqs (keep :event/seq (existing-events))]
    (inc (if (seq seqs) (apply max seqs) 0))))

;; ---------- append ----------

(defn append-event! [{:keys [task type summary actor agent]}]
  (with-lock*
    (fn []
      (let [f (file ledger-path)
            seq-n (next-seq)
            event {:event/seq seq-n
                   :event/type (keyword type)
                   :event/at (.toISOString (js/Date.))
                   :task/id task
                   :event/actor (or actor "Jun Kawasaki")
                   :event/agent (or agent "claude-code")
                   :event/summary summary}]
        (when-not (.isFile f)
          (spit-append ledger-path header))
        (spit-append ledger-path (str (pr-str event) "\n"))
        (println (format "appended :event/seq %s for task/id %s (%s) -> %s" seq-n task type ledger-path))))))

;; ---------- verify ----------

(defn task-ids-on-disk []
  (->> (file-seq (file "90-docs" "task-graphs"))
       (filter #(str/ends-with? (str %) ".datoms.edn"))
       (mapcat (fn [f]
                 (try
                   (let [content (edn/read-string {:default (fn [_tag v] v)} (slurp (str f)))]
                     (keep :task/id content))
                   (catch :default _ nil))))
       (remove nil?)
       set))

(defn verify! []
  (let [events (existing-events)
        seqs (map :event/seq events)
        dup-seqs (->> (frequencies seqs) (filter (fn [[_ n]] (> n 1))) (map first))
        missing-seq (filter (comp nil? :event/seq) events)
        known-tasks (task-ids-on-disk)
        orphan-events (remove (fn [e] (contains? known-tasks (:task/id e))) events)
        sorted-seqs (sort (remove nil? seqs))
        non-monotonic? (not= sorted-seqs (range 1 (inc (count sorted-seqs))))
        problems (cond-> []
                   (seq dup-seqs) (conj (str "duplicate :event/seq values: " (pr-str dup-seqs)))
                   (seq missing-seq) (conj (str (count missing-seq) " event(s) missing :event/seq"))
                   non-monotonic? (conj (str "seqs not a contiguous 1.." (count sorted-seqs)
                                              " run (race condition or hand-edit?): " (pr-str sorted-seqs)))
                   (seq orphan-events)
                   (conj (str (count orphan-events) " event(s) reference a task/id with no matching "
                              "90-docs/task-graphs/*.datoms.edn: "
                              (pr-str (distinct (map :task/id orphan-events))))))]
    (println (format "task-graph-ledger verify: events=%s known-tasks=%s problems=%s"
                      (count events) (count known-tasks) (count problems)))
    (doseq [p problems] (println "  -" p))
    (if (seq problems)
      (do (println "task-graph-ledger verify: FAIL") (exit 1))
      (println "task-graph-ledger verify: OK"))))

;; ---------- entry ----------

(defn -main [& argv]
  (let [{:keys [verify task type summary] :as opts} (parse-args argv)]
    (cond
      verify (verify!)

      (or (str/blank? task) (str/blank? type) (str/blank? summary))
      (do (println "usage: nbb scripts/task-graph-ledger-append.cljs --task <task/id> --type <dispatched|started|completed|blocked|skipped> --summary \"...\" [--actor \"...\"] [--agent \"...\"]")
          (println "       nbb scripts/task-graph-ledger-append.cljs --verify")
          (exit 1))

      :else (append-event! opts))))

(apply -main *command-line-args*)

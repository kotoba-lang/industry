#!/usr/bin/env nbb
;; scripts/adr-ledger-append.cljs — append-only amendment log for accepted
;; 90-docs/adr/*.edn entries.
;;
;; Why this exists: 90-docs/adr/*.edn (ADR-2607171600, EDN-only) are single
;; tx-data snapshots (`[{:db/id -1 :adr/id ... :adr/body ...}]`). This repo's
;; own convention (ADR-2607173000 decision item 6) is not to mass-rewrite an
;; accepted ADR's historical prose in place — but DataScript (the query
;; engine `manifest/edn-query.cljs` uses) has no Datomic-style
;; `d/as-of`/`d/history` transaction-time API, and each query run re-parses
;; the *current* file content fresh, so an in-place edit leaves nothing for
;; any future query to time-travel across. The fix already used elsewhere in
;; this repo (`90-docs/business/canvas-ledger.edn`,
;; `90-docs/design-quality/design-quality-ledger.edn`) is a real append-only
;; event log: one EDN map per line, base file never rewritten, a projector
;; (here: DataScript query joining on `adr/id`) folds it against the base
;; ADR. This script is that append point for ADR amendments — do NOT
;; hand-edit 90-docs/adr-ledger/adr-ledger.edn directly once it has content;
;; go through this script so :event/seq stays monotonic. See CLAUDE.md
;; "docs / ADR は EDN only + DataScript query" for the standing rule.
;;
;; This repo runs many concurrent agents/sessions (CLAUDE.md 「並行エージェント
;; 運用」節). Two agents appending at the same moment would otherwise both
;; read the same last :event/seq and write duplicate seqs, so append takes an
;; exclusive filesystem lock (90-docs/adr-ledger/.append.lock, O_EXCL create)
;; around the read-last-seq -> write critical section, with a stale-lock
;; takeover so a crashed process can't wedge future appends forever.
;;
;; Usage:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/adr-ledger-append.cljs \
;;     --adr 2607173000 --type amend \
;;     --summary "short one-line summary" \
;;     --body "longer free-text note (optional)" \
;;     --related 2607181900,2607100100 \
;;     --actor "Jun Kawasaki"
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/adr-ledger-append.cljs --verify
;;     — checks :event/seq is unique+monotonic and every :adr/id referenced
;;       by an event actually exists under 90-docs/adr/*.edn. exit 1 on
;;       failure.
;;
;; :event/type is free-form but by convention one of:
;;   amend | status-change | supersede | note

(require '[scripts.nbb-compat :refer [slurp spit-append file file-seq exit format sleep!]]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))

(def ledger-dir "90-docs/adr-ledger")
(def ledger-path (str ledger-dir "/adr-ledger.edn"))
(def lock-path (str ledger-dir "/.append.lock"))
(def lock-stale-ms 30000) ;; crashed-holder takeover threshold
(def lock-retry-ms 200)
(def lock-max-wait-ms 15000)

(def header
  (str
   ";; 90-docs/adr-ledger/adr-ledger.edn — append-only amendment events for\n"
   ";; already-accepted 90-docs/adr/*.edn entries (one EDN map per line,\n"
   ";; DataScript/Datomic-transactable). Base ADR files are not hand-rewritten\n"
   ";; after acceptance (ADR-2607173000 decision item 6); substantive changes\n"
   ";; are recorded here instead and joined on `adr/id` by\n"
   ";; manifest/edn-query.cljs. Do NOT hand-edit existing lines; only append\n"
   ";; new lines, and only via scripts/adr-ledger-append.cljs so :event/seq\n"
   ";; stays monotonic.\n"))

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

(defn- lock-status
  "Distinguishes \"no lock file\" from \"lock file exists and is N ms old\" —
   collapsing the two (e.g. treating a stat ENOENT as infinitely stale) makes
   the ordinary TOCTOU window between a holder's unlink and our stat look
   like a crashed-process takeover and logs a false alarm."
  []
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

(defn acquire-lock!
  "Blocks (busy-wait with backoff) until the exclusive lock file is created by
   this process, taking over a stale lock (older than lock-stale-ms — almost
   certainly a crashed/killed prior invocation, not a live holder) if one is
   found. Throws after lock-max-wait-ms so a genuinely wedged lock fails loud
   instead of hanging a caller forever."
  []
  (.mkdirSync fs ledger-dir #js {:recursive true})
  (loop [waited 0]
    (cond
      (try-acquire-lock!) :acquired

      :else
      (let [{:keys [exists? age-ms]} (lock-status)]
        (cond
          (and exists? (> age-ms lock-stale-ms))
          (do (println (str "[adr-ledger-append] taking over stale lock (>" lock-stale-ms "ms old): " lock-path))
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

(defn append-event! [{:keys [adr type summary body related actor agent]}]
  (with-lock*
    (fn []
      (let [f (file ledger-path)
            seq-n (next-seq)
            event (cond-> {:event/seq seq-n
                           :event/type (keyword type)
                           :event/at (.toISOString (js/Date.))
                           :adr/id (str adr)
                           :event/actor (or actor "Jun Kawasaki")
                           :event/agent (or agent "claude-code")
                           :event/summary summary}
                    (not (str/blank? body)) (assoc :event/body body)
                    (not (str/blank? related))
                    (assoc :adr/related (vec (remove str/blank? (str/split related #",")))))]
        (when-not (.isFile f)
          (spit-append ledger-path header))
        (spit-append ledger-path (str (pr-str event) "\n"))
        (println (format "appended :event/seq %s for adr/id %s -> %s" seq-n adr ledger-path))))))

;; ---------- verify ----------

(defn adr-ids-on-disk
  "All :adr/id values found under 90-docs/adr/*.edn (single or multi-entity
   tx-data). Best-effort: files that fail to parse are skipped here — that's
   manifest/docs-edn-only.cljs verify's job to report, not this script's."
  []
  (->> (file-seq (file "90-docs" "adr"))
       (filter #(str/ends-with? (str %) ".edn"))
       (mapcat (fn [f]
                 (try
                   (let [content (edn/read-string {:default (fn [_tag v] v)} (slurp (str f)))]
                     (cond
                       (and (vector? content) (every? map? content)) (keep :adr/id content)
                       (map? content) [(:adr/id content)]
                       :else nil))
                   (catch :default _ nil))))
       (remove nil?)
       set))

(defn verify! []
  (let [events (existing-events)
        seqs (map :event/seq events)
        dup-seqs (->> (frequencies seqs) (filter (fn [[_ n]] (> n 1))) (map first))
        missing-seq (filter (comp nil? :event/seq) events)
        known-adrs (adr-ids-on-disk)
        orphan-events (remove (fn [e] (contains? known-adrs (:adr/id e))) events)
        sorted-seqs (sort (remove nil? seqs))
        non-monotonic? (not= sorted-seqs (range 1 (inc (count sorted-seqs))))
        problems (cond-> []
                   (seq dup-seqs) (conj (str "duplicate :event/seq values: " (pr-str dup-seqs)))
                   (seq missing-seq) (conj (str (count missing-seq) " event(s) missing :event/seq"))
                   non-monotonic? (conj (str "seqs not a contiguous 1.." (count sorted-seqs)
                                              " run (race condition or hand-edit?): " (pr-str sorted-seqs)))
                   (seq orphan-events)
                   (conj (str (count orphan-events) " event(s) reference an adr/id with no matching "
                              "90-docs/adr/*.edn: "
                              (pr-str (distinct (map :adr/id orphan-events))))))]
    (println (format "adr-ledger verify: events=%s known-adrs=%s problems=%s"
                      (count events) (count known-adrs) (count problems)))
    (doseq [p problems] (println "  -" p))
    (if (seq problems)
      (do (println "adr-ledger verify: FAIL") (exit 1))
      (println "adr-ledger verify: OK"))))

;; ---------- entry ----------

(defn -main [& argv]
  (let [{:keys [verify adr type summary] :as opts} (parse-args argv)]
    (cond
      verify (verify!)

      (or (str/blank? adr) (str/blank? type) (str/blank? summary))
      (do (println "usage: nbb scripts/adr-ledger-append.cljs --adr <id> --type <amend|status-change|supersede|note> --summary \"...\" [--body \"...\"] [--related id1,id2] [--actor \"...\"]")
          (println "       nbb scripts/adr-ledger-append.cljs --verify")
          (exit 1))

      :else (append-event! opts))))

(apply -main *command-line-args*)

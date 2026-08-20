#!/usr/bin/env nbb
;; verify-ledger-seq-collision — two different events must not share one
;; :event/seq, because seq is the identity the kotobase projection upserts on.
;;
;;   nbb scripts/verify-ledger-seq-collision.cljs             # ratchet
;;   nbb scripts/verify-ledger-seq-collision.cljs --list      # + name them
;;   nbb scripts/verify-ledger-seq-collision.cljs --write-baseline
;;   nbb scripts/verify-ledger-seq-collision.cljs --self-test
;;
;; ## Why this exists
;;
;; gftd.ledger/append! stamps a new event with (inc (max seq-of-the-file)),
;; read from the LOCAL file, with nothing holding the file between the read and
;; the write. Two loops running at once on this machine read the same max and
;; write the same seq; two checkouts appending independently produce two
;; disjoint runs of the same numbers and git merges them without complaint,
;; because they are different lines at the end of a file.
;;
;; That is not a git conflict once it lands -- it is a silent duplicate. On
;; 2026-08-20 the committed canvas-ledger held 9,624 events on 9,271 distinct
;; seq values: 333 numbers carried more than one event, and every one of those
;; pairs was genuinely different content, not an idempotent re-append.
;;
;; It matters because gftd.kotobase/event->entity builds :db/id as
;; "bmc.event/<seq>" and says so: "Stable :db/id = event/seq so re-asserts are
;; cardinality-one upserts." Cardinality-one is exactly the problem when the
;; key is not unique -- the second event does not land beside the first, it
;; REPLACES it. 353 events currently have no id of their own.
;;
;; Nothing is losing data this minute, and the honest reason is not that the
;; design is sound: bmc-dual-write is scheduled and has been failing since at
;; least 2026-08-19 with "Could not find namespace: kotobase.client". The
;; collapse is armed, not disarmed. Repair the projection and it fires.
;;
;; ADR-2607254000 already recorded this class for a different ledger and drew
;; the right conclusion -- "an exclusive lock only protects concurrent appends
;; on the same filesystem, so you have to sync from origin BEFORE appending".
;; canvas-ledger's writer does neither.
;;
;; ## What this proves, and what it does not
;;
;; It proves how many seq values carry more than one distinct event in the
;; files it actually read. It does NOT fix the writer, and a clean run does not
;; mean two loops cannot still collide five minutes from now -- it means they
;; had not yet when this ran. The fix is in gftd.ledger/append!; this only
;; stops the number growing unnoticed, which is how it reached 333.
;;
;; Exit 0 = not worse than the baseline. 1 = new collisions. 2 = could not
;; measure (no ledger, unreadable, or zero events) -- never reported as clean.

(ns verify-ledger-seq-collision
  (:require ["fs" :as fs]
            [clojure.string :as str]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(def list?          (some #{"--list"} argv))
(def write-baseline? (some #{"--write-baseline"} argv))
(def self-test?     (some #{"--self-test"} argv))

(def baseline-path "manifest/ledger-seq-baseline.edn")

;; The ledgers that carry :event/seq. Add a path here, not a new script.
(def ledgers
  ["90-docs/business/canvas-ledger.edn"
   "90-docs/design-quality/design-quality-ledger.edn"])

;; Both EDN spellings appear in these files: a namespaced map prints the key as
;; `:seq` inside `#:event{...}`, while a plain map prints `:event/seq`. Matching
;; only one of them silently halves the count -- measured, it read 225 of 333.
(def seq-re #"(?::event/seq|(?<![\w/-]):seq)\s+(\d+)")

(defn- seq-of [line]
  (when-let [m (re-find seq-re line)] (js/parseInt (second m) 10)))

(defn- collisions
  "{seq #{distinct-event-lines}} for every seq carrying >1 DISTINCT line.
   Byte-identical repeats are an idempotent re-append and are not a collision."
  [lines]
  (let [by (reduce (fn [acc l]
                     (if-let [s (seq-of l)]
                       (update acc s (fnil conj #{}) (str/trim l))
                       acc))
                   {} lines)]
    (into {} (filter (fn [[_ v]] (> (count v) 1)) by))))

(defn- read-lines [p]
  (try (->> (str (fs/readFileSync p "utf8"))
            str/split-lines
            (remove str/blank?)
            vec)
       (catch :default _ nil)))

;; ── self-test: the check has to be able to fail ──────────────────────────
(when self-test?
  (let [a "#:event{:type :x, :seq 7, :at \"t\"}"
        b "#:event{:type :y, :seq 7, :at \"t\"}"
        c "{:canvas/id :p, :event/seq 8}"
        d "{:canvas/id :q, :event/seq 8}"
        clean (collisions [a c])
        dirty-ns (collisions [a b])
        dirty-plain (collisions [c d])
        repeat-same (collisions [a a])
        both (collisions [a b c d])]
    (println "self-test  clean input          ->" (count clean) "(expect 0)")
    (println "self-test  #:event{:seq} dup     ->" (count dirty-ns) "(expect 1)")
    (println "self-test  :event/seq dup        ->" (count dirty-plain) "(expect 1)")
    (println "self-test  byte-identical repeat ->" (count repeat-same) "(expect 0)")
    (println "self-test  both spellings        ->" (count both) "(expect 2)")
    (if (and (zero? (count clean)) (= 1 (count dirty-ns))
             (= 1 (count dirty-plain)) (zero? (count repeat-same))
             (= 2 (count both)))
      (do (println "self-test OK — it separates a genuine collision from a repeat,")
          (println "               and it sees BOTH spellings, which is the bug that")
          (println "               made an earlier count read 225 of 333.")
          (js/process.exit 0))
      (do (println "self-test FAILED") (js/process.exit 1)))))

;; ── measure ──────────────────────────────────────────────────────────────
(def baseline
  (try (let [t (str (fs/readFileSync baseline-path "utf8"))]
         (into {} (for [[_ p n] (re-seq #"\"([^\"]+)\"\s+(\d+)" t)]
                    [p (js/parseInt n 10)])))
       (catch :default _ {})))

(let [results (for [p ledgers
                    :let [lines (read-lines p)]]
                {:path p
                 :readable? (some? lines)
                 :events (count (or lines []))
                 :cols (if lines (collisions lines) {})})
      readable (filter :readable? results)
      total-events (reduce + 0 (map :events readable))]

  (println (str "SCANNED\t" (count readable) "\tledger(s) of " (count ledgers)
                ", " total-events " event line(s)"))

  (doseq [{:keys [path readable? events cols]} results]
    (if-not readable?
      (println (str "  " path " — NOT READABLE (not scanned)"))
      (let [dups (count cols)
            lost (reduce + 0 (map (fn [[_ v]] (dec (count v))) cols))
            base (get baseline path)]
        (println (str "  " path
                      "  events=" events
                      "  colliding-seq=" dups
                      "  events-without-their-own-id=" lost
                      (when base (str "  baseline=" base))))
        (when (and list? (pos? dups))
          (doseq [[s v] (take 5 (sort-by key cols))]
            (println (str "      seq " s " carries " (count v) " distinct events:"))
            (doseq [l (take 2 (sort v))]
              (println (str "        " (subs l 0 (min 110 (count l)))))))
          (when (> dups 5) (println (str "      … and " (- dups 5) " more colliding seq values")))))))

  (when write-baseline?
    (let [body (str ";; Known :event/seq collisions, recorded so the number cannot grow\n"
                    ";; unnoticed. Lowering an entry is progress; raising one needs a reason.\n"
                    ";; Generated by scripts/verify-ledger-seq-collision.cljs --write-baseline\n"
                    "{\n"
                    (str/join "\n" (for [{:keys [path readable? cols]} results
                                         :when readable?]
                                     (str " \"" path "\" " (count cols))))
                    "\n}\n")]
      (fs/writeFileSync baseline-path body)
      (println (str "wrote " baseline-path))
      (js/process.exit 0)))

  ;; evidence floor: a run that read nothing is not a clean run
  (when (or (empty? readable) (zero? total-events))
    (println "no ledger was readable, or every one was empty — this run measured nothing")
    (js/process.exit 2))

  (let [grown (for [{:keys [path readable? cols]} results
                    :when readable?
                    :let [base (get baseline path)]
                    :when (and base (> (count cols) base))]
                [path base (count cols)])]
    (doseq [[p base now] grown]
      (println (str "  NEW COLLISIONS in " p ": " base " -> " now
                    " (gftd.ledger/append! read a stale max and reused it)")))
    (js/process.exit (if (seq grown) 1 0))))

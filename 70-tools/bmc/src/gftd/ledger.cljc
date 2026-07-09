(ns gftd.ledger
  "Append-only canvas ledger — 1 EDN event map per line.
   Never rewritten, never reordered; the fold (gftd.canvas) is the only reader
   that gives events meaning. Event shape:
   {:event/seq 1 :event/at \"2026-07-02T…\" :event/actor \"cli:itonami\"
    :event/type :canvas/add-item|:canvas/retract-item|:canvas/note|:hyp/status
                |:react/observation|:react/thought|:governor/rejected
    :canvas/id … :hyp/id … :event/value … :event/evidence … :event/reason … :event/tick …}"
  (:require [clojure.string :as str]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(defn parse-events
  "Parse ledger file content (string) → vector of event maps."
  [s]
  (into []
        (comp (remove str/blank?)
              (remove #(str/starts-with? (str/trim %) ";"))
              (map edn/read-string))
        (str/split-lines (or s ""))))

(defn next-seq [events] (inc (reduce max 0 (keep :event/seq events))))

(defn stamp
  "Assign :event/seq (continuing from existing) and :event/at to new events."
  [existing at new-events]
  (let [n0 (next-seq existing)]
    (vec (map-indexed (fn [i e] (assoc e :event/seq (+ n0 i) :event/at at)) new-events))))

#?(:clj
   (do
     (defn read-events [path]
       (let [f (java.io.File. ^String path)]
         (if (.exists f) (parse-events (slurp f)) [])))

     (defn append!
       "Stamp and append events to the ledger file. Returns the stamped events."
       [path events]
       (let [existing (read-events path)
             at (str (java.time.Instant/now))
             stamped (stamp existing at events)
             f (java.io.File. ^String path)]
         (when-let [p (.getParentFile f)] (.mkdirs p))
         (spit f (apply str (map #(str (pr-str %) "\n") stamped)) :append true)
         stamped))))

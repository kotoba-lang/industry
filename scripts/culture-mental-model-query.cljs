#!/usr/bin/env nbb
;; culture-mental-model-query.cljs — Cross-cultural research generalization lookup (DataScript query tool)
;;
;; WARNING: This tool surfaces statistical cultural patterns, not universal rules.
;; Individual variation is large. Use for awareness & empathy, not stereotyping.
;; See 90-docs/culture/culture-mental-model-ledger.edn for caveats on each entry.
;;
;; Usage:
;;   nbb scripts/culture-mental-model-query.cljs count
;;   nbb scripts/culture-mental-model-query.cljs region DEU
;;   nbb scripts/culture-mental-model-query.cljs kind :communication-style
;;   nbb scripts/culture-mental-model-query.cljs family :ok-sign
;;   nbb scripts/culture-mental-model-query.cljs q '[:find ?id ?title :where [?e :trait/region ?r] [?r :region/id "JPN"]]'

(require '[scripts.nbb-compat :refer [slurp exit]]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.shell :as shell]
         '["datascript" :as ds-mod]
         '["fs" :as fs])

(def ds (.-default ds-mod))

(def root (str/trim (:out (shell/sh "git" "rev-parse" "--show-toplevel"))))

;; Data sources
(def SOURCES
  [{:label "culture-mental-model (base)"
    :schema "90-docs/culture/culture-mental-model.datoms.edn"
    :data "90-docs/culture/culture-mental-model-ledger.edn"}])

(defn load-source [{:keys [label schema data]}]
  (let [schema-path (str root "/" schema)
        data-path (str root "/" data)
        schema-exists (.existsSync fs schema-path)
        data-exists (.existsSync fs data-path)]
    (if (and schema-exists data-exists)
      (do
        (println (str "✓ Loading " label))
        {:schema (edn/read-string (slurp schema-path))
         :data (edn/read-string (str "[" (slurp data-path) "]"))})
      (do
        (println (str "✗ SKIP (missing schema or data): " label))
        nil))))

(defn kw->attr [kw]
  (if (keyword? kw)
    (str/replace (str kw) #"^:" "")
    kw))

(defn ->>ds-value [v]
  (cond
    (keyword? v) (str v)
    (boolean? v) v
    (number? v) v
    (string? v) v
    (vector? v) (into-array (mapv ->>ds-value v))
    :else nil))

(defn entity->js [entity]
  (let [result (js-obj)]
    (doseq [[k v] entity]
      (let [attr (if (= k :db/id) ":db/id" (kw->attr k))
            value (->>ds-value v)]
        (when value
          (aset result attr value))))
    result))

(defn schema->js [schema-vec]
  (into-array (mapv entity->js (filter map? schema-vec))))

(defn build-conn [schema-js]
  (.create_conn ds schema-js))

(defn load-and-transact []
  (let [sources (keep load-source SOURCES)]
    (if (empty? sources)
      (do (println "Error: No valid data sources found!")
          (exit 1)))
    (let [merged-schema (reduce merge {} (mapcat :schema sources))
          merged-schema (assoc merged-schema "culture/source" {})
          schema-js (schema->js (vals merged-schema))
          conn (build-conn schema-js)]
      (doseq [source sources]
        (let [entities (:data source)
              entities-js (into-array (mapv entity->js entities))]
          (.transact ds conn entities-js)))
      {:conn conn :db (.db ds conn)})))

(defn print-count []
  (let [{:keys [db]} (load-and-transact)
        traits (count (.q ds "[:find ?e :where [?e \"trait/id\"]]" db))
        gestures (count (.q ds "[:find ?e :where [?e \"gesture/id\"]]" db))
        regions (count (.q ds "[:find ?e :where [?e \"region/id\"]]" db))
        generations (count (.q ds "[:find ?e :where [?e \"generation/id\"]]" db))]
    (println (str "traits=" traits " gestures=" gestures " regions=" regions " generations=" generations " TOTAL=" (+ traits gestures regions generations)))))

(defn print-region [iso]
  (let [{:keys [db]} (load-and-transact)
        result (.q ds
                   (str "[:find ?id ?title ?kind ?caveat "
                        ":where [?e \"trait/region\" ?r] [?r \"region/id\" \"" iso "\"] "
                        "[?e \"trait/id\" ?id] [?e \"trait/title\" ?title] [?e \"trait/kind\" ?kind] [?e \"trait/caveat\" ?caveat]]")
                   db)]
    (if (empty? result)
      (println (str "No traits found for region " iso))
      (do (println (str "\nTraits for region " iso ":"))
          (doseq [[id title kind caveat] result]
            (println (str "  " id " (" kind "): " title))
            (println (str "    Caveat: " caveat)))))))

(defn print-kind [kind]
  (let [{:keys [db]} (load-and-transact)
        result (.q ds
                   (str "[:find ?id ?title ?region ?caveat "
                        ":where [?e \"trait/kind\" \"" kind "\"] "
                        "[?e \"trait/id\" ?id] [?e \"trait/title\" ?title] [?e \"trait/region\" ?r] [?r \"region/id\" ?region] [?e \"trait/caveat\" ?caveat]]")
                   db)]
    (if (empty? result)
      (println (str "No traits found for kind " kind))
      (do (println (str "\nTraits of kind " kind ":"))
          (doseq [[id title region caveat] result]
            (println (str "  " id " (" region "): " title))
            (println (str "    Caveat: " caveat)))))))

(defn print-family [family]
  (let [{:keys [db]} (load-and-transact)
        result (.q ds
                   (str "[:find ?id ?region ?meaning ?valence ?caution "
                        ":where [?e \"gesture/family\" \"" family "\"] "
                        "[?e \"gesture/id\" ?id] [?e \"gesture/region\" ?r] [?r \"region/id\" ?region] "
                        "[?e \"gesture/meaning\" ?meaning] [?e \"gesture/valence\" ?valence] [?e \"gesture/caution\" ?caution]]")
                   db)]
    (if (empty? result)
      (println (str "No gestures found for family " family))
      (do (println (str "\nGesture family " family " by region:"))
          (doseq [[id region meaning valence caution] result]
            (println (str "  " id " (" region "): " meaning " [" valence "]"))
            (println (str "    Caution: " caution)))))))

(defn q [query-str]
  (let [{:keys [db]} (load-and-transact)]
    (.q ds query-str db)))

(defn -main [& args]
  (let [[mode arg] args]
    (case mode
      "count" (print-count)
      "region" (print-region arg)
      "kind" (print-kind arg)
      "family" (print-family arg)
      "q" (println (pr-str (q arg)))
      (do (println "usage: nbb scripts/culture-mental-model-query.cljs [count | region <ISO> | kind <keyword> | family <keyword> | q '<datalog-query>']")
          (exit 1)))))

(apply -main *command-line-args*)

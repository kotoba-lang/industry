#!/usr/bin/env nbb
;; scripts/compliance-fact-query.cljs — cross-repo compliance-fact federation
;; query (ADR-2607141700, "cross-repo query 層" section: interim thin
;; federation, NOT net-kotobase which is still L0/L1-only).
;;
;; Loads each source repo's schema/*.edn + data/datascript-tx.edn, merges the
;; schemas, unions the tx-data into ONE DataScript db, and lets you `d/q`
;; across all of them at once even though each repo's git history/ownership/
;; visibility stays separate. Same npm `datascript` JS-interop convention as
;; scripts/labor-liberation-sd.cljs and manifest/edn-query.cljs (attrs are
;; bare strings, not keywords, in the JS-facing API).
;;
;; SOURCES grows one entry per Wave (ADR-2607141700 Wave 0: JPN statute.facts +
;; etzhayyim/global-legislation-datoms legal-source + Tokyo ordinance.facts, so
;; far). Add a source here the moment its repo gets a data/datascript-tx.edn --
;; never invent facts inside THIS script, it only unions what the source repos
;; already committed.
;;
;; 使い方:
;;   nbb scripts/compliance-fact-query.cljs count
;;   nbb scripts/compliance-fact-query.cljs jurisdiction JPN
;;   nbb scripts/compliance-fact-query.cljs municipality tokyo
;;   nbb scripts/compliance-fact-query.cljs q '[:find ?url :where [?e "statute/id" "jpn.appi"] [?e "statute/url" ?url]]'

(require '[scripts.nbb-compat :refer [slurp exit]]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.shell :as shell]
         '["datascript" :as ds-mod])

(def ds (.-default ds-mod))

(def root (str/trim (:out (shell/sh "git" "rev-parse" "--show-toplevel"))))

(def SOURCES
  "Wave 0 (ADR-2607141700). Each entry: repo-relative schema + data path,
  plus a human label used only in `count` output."
  [{:label "cloud-itonami-iso3166-jpn statute.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-iso3166-jpn/schema/statute.edn"
    :data "orgs/cloud-itonami/cloud-itonami-iso3166-jpn/data/datascript-tx.edn"}
   {:label "etzhayyim/global-legislation-datoms legal-source"
    :schema "orgs/etzhayyim/global-legislation-datoms/schema/legislation.edn"
    :data "orgs/etzhayyim/global-legislation-datoms/data/datascript-tx.edn"}
   {:label "cloud-itonami-municipality-jpn-tokyo ordinance.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-municipality-jpn-tokyo/schema/ordinance.edn"
    :data "orgs/cloud-itonami/cloud-itonami-municipality-jpn-tokyo/data/datascript-tx.edn"}
   {:label "cloud-itonami-assoc-6419-jpn-zenginkyo association.facts"
    :schema "orgs/cloud-itonami/cloud-itonami-assoc-6419-jpn-zenginkyo/schema/association-rule.edn"
    :data "orgs/cloud-itonami/cloud-itonami-assoc-6419-jpn-zenginkyo/data/datascript-tx.edn"}])

;; ---------- keyword → 裸文字列変換（labor-liberation-sd.cljs と同一方針） ----------

(defn kw->attr [k]
  (if (keyword? k)
    (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (str k)))

(defn ->ds-scalar [v] (if (keyword? v) (kw->attr v) v))

(defn ->ds-value [v]
  (cond
    (map? v) (pr-str v)
    (or (vector? v) (seq? v) (set? v)) (into-array (map ->ds-scalar v))
    :else (->ds-scalar v)))

(defn entity->js [source-label m]
  (let [obj (js-obj)]
    (doseq [[k v] m]
      (aset obj (kw->attr k) (->ds-value v)))
    (aset obj "compliance-fact/source" source-label)
    obj))

;; npm datascript's JS wrapper needs schema CONFIG values that are themselves
;; keywords (:db.cardinality/many, :db.unique/identity, ...) written as
;; colon-prefixed strings (":db.cardinality/many") -- unlike entity attrs and
;; schema attr-names, which stay bare (no colon). Empirically verified
;; (2026-07-14): a bare "db.cardinality/many" or "many" string is silently
;; ignored (falls back to cardinality-one, array values become one opaque
;; datom instead of exploding into N datoms); only the colon-prefixed form
;; actually engages cardinality-many.
(defn ->ds-schema-value [v] (if (keyword? v) (str ":" (kw->attr v)) v))

(defn schema-entry->js [config]
  (let [obj (js-obj)]
    (doseq [[k v] config] (aset obj (kw->attr k) (->ds-schema-value v)))
    obj))

(defn schema->js [schema-map]
  (let [obj (js-obj)]
    (doseq [[attr config] schema-map] (aset obj (kw->attr attr) (schema-entry->js config)))
    obj))

;; ---------- load + union ----------

(defn load-source [{:keys [label schema data]}]
  (let [schema-path (str root "/" schema)
        data-path (str root "/" data)]
    (if (and (.existsSync (js/require "node:fs") schema-path)
             (.existsSync (js/require "node:fs") data-path))
      {:label label
       :schema (edn/read-string (slurp schema-path))
       :entities (edn/read-string (slurp data-path))}
      (do (println "SKIP (missing schema or data):" label) nil))))

(def loaded (into [] (keep load-source) SOURCES))

(def merged-schema
  (-> (reduce merge {} (map :schema loaded))
      (assoc "compliance-fact/source" {})))

(defn build-conn []
  (let [conn (.create_conn ds (schema->js merged-schema))
        tx (mapcat (fn [{:keys [label entities]}]
                      (map #(entity->js label %) entities))
                    loaded)]
    (.transact ds conn (into-array tx))
    conn))

(def conn (build-conn))
(def db (.db ds conn))

(defn q [query-str & args]
  (js->clj (.apply (.-q ds) ds (into-array (concat [query-str db] args)))))

;; ---------- commands ----------

(defn print-count []
  (doseq [{:keys [label entities]} loaded]
    (println (str (count entities) "\t" label)))
  (println (str (reduce + (map (comp count :entities) loaded)) "\tTOTAL")))

(defn print-jurisdiction [iso3]
  (println (str "== " iso3 " (statute.facts) =="))
  (let [rows (q (str "[:find ?id ?title ?url :in $ ?j :where
                       [?e \"statute/jurisdiction\" ?j]
                       [?e \"statute/id\" ?id] [?e \"statute/title\" ?title]
                       [?e \"statute/url\" ?url]]")
                iso3)]
    (doseq [[id title url] rows] (println (str "  " id "  " title "  <" url ">"))))
  (println (str "== " iso3 " (legal-source) =="))
  (let [rows (q (str "[:find ?name ?url :in $ ?j :where
                       [?e \"legal-source/jurisdiction\" ?j]
                       [?e \"legal-source/name\" ?name] [?e \"legal-source/url\" ?url]]")
                iso3)]
    (doseq [[name url] rows] (println (str "  " name "  <" url ">")))))

(defn print-municipality [muni]
  (println (str "== " muni " (ordinance.facts) =="))
  (let [rows (q (str "[:find ?id ?title ?url :in $ ?m :where
                       [?e \"ordinance/municipality\" ?m]
                       [?e \"ordinance/id\" ?id] [?e \"ordinance/title\" ?title]
                       [?e \"ordinance/url\" ?url]]")
                muni)]
    (doseq [[id title url] rows] (println (str "  " id "  " title "  <" url ">")))))

(defn print-association [assoc-slug]
  (println (str "== " assoc-slug " (association.facts) =="))
  (let [rows (q (str "[:find ?id ?title ?url :in $ ?a :where
                       [?e \"association-rule/association\" ?a]
                       [?e \"association-rule/id\" ?id] [?e \"association-rule/title\" ?title]
                       [?e \"association-rule/url\" ?url]]")
                assoc-slug)]
    (doseq [[id title url] rows] (println (str "  " id "  " title "  <" url ">")))))

(defn -main [& args]
  (let [[mode arg] args]
    (case mode
      "count" (print-count)
      "jurisdiction" (print-jurisdiction arg)
      "municipality" (print-municipality arg)
      "association" (print-association arg)
      "q" (println (pr-str (q arg)))
      (do (println "usage: nbb scripts/compliance-fact-query.cljs [count|jurisdiction <ISO3>|municipality <slug>|association <slug>|q '<datalog>']")
          (exit 1)))))

(apply -main *command-line-args*)

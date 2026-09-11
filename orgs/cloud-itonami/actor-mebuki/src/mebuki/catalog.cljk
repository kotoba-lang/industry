(ns mebuki.catalog
  "The recovery-operations catalog: what a flooded community needs, as data.

  The catalog itself is `resources/mebuki/catalog.edn` and carries no numbers
  -- see that file's header for why. This namespace loads it, answers
  ordering and dependency questions about it, and checks it against the
  simulation model, so that a catalog item claiming a link to a model
  variable is a claim the test suite can falsify.

  Loading is host-injected: `parse` takes EDN text, and the JVM convenience
  `load-catalog` reads it off the classpath. Nothing here reaches for a
  filesystem in ClojureScript."
  (:require [clojure.edn :as edn]
            [mebuki.model :as model]
            #?(:clj [clojure.java.io :as io])))

(defn parse [edn-text] (edn/read-string edn-text))

#?(:clj
   (defn- resource-edn [path]
     (if-let [r (io/resource path)]
       (edn/read-string (slurp r))
       (throw (ex-info (str "mebuki.catalog: resource not on classpath: " path)
                       {:path path})))))

#?(:clj (defn load-catalog [] (resource-edn "mebuki/catalog.edn")))
#?(:clj (defn load-profile [id] (resource-edn (str "mebuki/jurisdiction/" (name id) ".edn"))))
#?(:clj (defn load-scenario [id] (resource-edn (str "mebuki/scenario/" (name id) ".edn"))))

(defn items [catalog] (:catalog/items catalog))

(defn by-id [catalog] (into {} (map (juxt :id identity)) (items catalog)))

(defn of-kind
  "kind is :resource, :task or :operation."
  [catalog kind]
  (filter #(= kind (:kind %)) (items catalog)))

(defn phase-order
  "phase keyword -> its :order, for sorting."
  [catalog]
  (into {} (map (juxt :phase/id :order)) (:catalog/phases catalog)))

(defn in-order
  "Every item sorted into the sequence a community meets it in: by phase, then
  by the start of its ordering window. This is an ORDERING, not a schedule --
  :order-window is not a measured duration and this fn does not treat it as one."
  [catalog]
  (let [po (phase-order catalog)]
    (sort-by (juxt #(get po (:phase %) 99)
                   #(first (:order-window % [0 0]))
                   #(str (:id %)))
             (items catalog))))

(defn requirements
  "Transitive closure of :needs from `item-ids` -- everything that has to exist
  for those items to be doable. Cycle-safe."
  [catalog item-ids]
  (let [idx (by-id catalog)]
    (loop [seen #{} frontier (set item-ids)]
      (if (empty? frontier)
        seen
        (let [nxt (into #{} (mapcat #(:needs (get idx %) #{})) frontier)]
          (recur (into seen frontier) (remove seen nxt)))))))

(defn dangling-references
  "Every :needs / :gated-by target that nothing defines. A non-empty result is
  a broken catalog, not a style problem: the requirement closure would silently
  stop at the missing node."
  [catalog instrument-role-ids]
  (let [ids (set (map :id (items catalog)))
        roles (set instrument-role-ids)]
    {:needs (vec (sort (distinct (remove ids (mapcat #(seq (:needs % #{})) (items catalog))))))
     :gated-by (vec (sort (distinct (remove roles (mapcat #(seq (:gated-by % #{})) (items catalog))))))}))

(defn unknown-model-vars
  "The :model-var values that name nothing in the built model. `built` is a
  model from `mebuki.model/build`."
  [catalog built]
  (let [known (into #{} (map name) (keys (:xmile/variables built)))]
    (vec (sort (remove known (keep :model-var (items catalog)))))))

(defn unlinked-model-vars
  "Model variables no catalog item claims. Reported, not failed on: the model
  legitimately contains internal bookkeeping (gates, accumulators, capacity
  auxes) that is nobody's procurement decision. It is worth printing because a
  variable nothing in the catalog points at is a variable no one can act on."
  [catalog built]
  (let [claimed (set (keep :model-var (items catalog)))]
    (vec (sort (remove claimed (map name (keys (:xmile/variables built))))))))

(defn coverage
  "A count per phase and per kind, so a caller can see what the catalog covers
  before trusting an answer drawn from it. `:items` is the denominator every
  other number here should be read against."
  [catalog]
  {:items (count (items catalog))
   :by-kind (frequencies (map :kind (items catalog)))
   :by-phase (frequencies (map :phase (items catalog)))
   :with-model-var (count (keep :model-var (items catalog)))
   :model-parameters (count model/required-parameter-names)})

(ns identity.merge
  (:require [clojure.set :as set]))

(defn same-subject? [a b]
  (or (= (:identity.subject/id a) (:identity.subject/id b))
      (and (:identity.subject/did a)
           (= (:identity.subject/did a) (:identity.subject/did b)))
      (boolean (some (set (:identity.subject/aliases a))
                     (conj (set (:identity.subject/aliases b))
                           (:identity.subject/id b))))))

(defn merge-subjects
  ([subjects] (merge-subjects subjects {}))
  ([subjects opts]
   (let [conflict? (boolean (seq (remove #{(:identity.subject/type (first subjects))}
                                         (map :identity.subject/type subjects))))
         ids (map :identity.subject/id subjects)
         labels (apply set/union (map #(or (:identity.subject/labels %) #{}) subjects))
         sources (vec (distinct (keep :identity.subject/source subjects)))
         canonical (or (:canonical-id opts)
                       (:identity.subject/id (first subjects)))]
     (-> (first subjects)
         (assoc :identity.subject/id canonical
                :identity.subject/labels labels
                :identity.subject/source (first sources)
                :identity.subject/aliases (vec (distinct (remove #{canonical} ids)))
                :identity.subject/conflict? conflict?)))))

(defn merge-by-policy
  ([subjects] (merge-by-policy subjects {}))
  ([subjects opts]
   (loop [remaining subjects
          groups []]
     (if-let [s (first remaining)]
       (let [{matches true rest false} (group-by #(same-subject? s %) (rest remaining))]
         (recur rest (conj groups (merge-subjects (cons s matches) opts))))
       groups))))

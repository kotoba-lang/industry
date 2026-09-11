;; Condense the definition graph by strongly-connected component, then order it.
;;
;; kotoba.lang.edn has (declare read-form) and a read-form <-> read-sequence
;; cycle: a recursive-descent reader. Two definitions that call each other
;; cannot be two repos with an acyclic dependency, so the unit is the CYCLE.
;; Unison groups mutually recursive definitions the same way and hashes them
;; together; this is the same fact arriving from the same place.
(require '[clojure.string :as s])
(def fs (js/require "node:fs"))
(def lines (remove s/blank? (s/split-lines (str (.readFileSync fs (first *command-line-args*) "utf8")))))
(def deps (into {} (for [l lines :let [[t n _ d] (s/split l #"\t" 4)] :when (= "DEF" t)]
                     [n (if (s/blank? d) #{} (set (s/split d #",")))])))
(def nodes (vec (sort (keys deps))))

;; Kosaraju: order by finish time on G, then label components on the reverse.
(defn dfs1 [g]
  (let [seen (volatile! #{}) order (volatile! [])]
    (letfn [(go [u] (when-not (@seen u)
                      (vswap! seen conj u)
                      (doseq [v (get g u)] (when (contains? deps v) (go v)))
                      (vswap! order conj u)))]
      (doseq [u nodes] (go u)))
    @order))
(def rev (reduce (fn [m [u vs]] (reduce (fn [m2 v] (update m2 v (fnil conj #{}) u)) m vs)) {} deps))
(def order (reverse (dfs1 deps)))
(def comp-of
  (let [seen (volatile! #{}) out (volatile! {})]
    (letfn [(go [u root] (when-not (@seen u)
                           (vswap! seen conj u)
                           (vswap! out assoc u root)
                           (doseq [v (get rev u)] (go v root))))]
      (doseq [u order] (when-not (@seen u) (go u u))))
    @out))
(def groups (group-by comp-of nodes))
;; name each component after the member with the most edges coming from OUTSIDE
;; it; ties broken alphabetically, so the name is stable.
(def leader
  (into {} (for [[root ms] groups]
             [root (first (sort-by (fn [m] [(- (count (for [[u vs] deps
                                                            :when (and (not= (comp-of u) root) (contains? vs m))]
                                                        u))) m])
                                   ms))])))
(println (str "COMPONENTS\t" (count groups) "\tof " (count nodes) " definitions"))
(doseq [[root ms] (sort-by (comp leader key) groups) :when (> (count ms) 1)]
  (println (str "CYCLE\t" (leader root) "\t" (s/join "," (sort ms)))))
;; condensed edges, then a topological order over components
(def cdeps
  (into {} (for [[root ms] groups]
             [root (disj (set (for [m ms, d (get deps m) :when (contains? deps d)] (comp-of d))) root)])))
(loop [done [] seen #{} guard 0]
  (if (or (= (count done) (count cdeps)) (> guard 400))
    (do (println (str "LAYERS-OK\t" (count done) "/" (count cdeps)))
        (doseq [r done] (println (str "TOPO\t" (leader r) "\t" (s/join "," (sort (get groups r)))))))
    (let [ready (sort-by leader (for [[r ds] cdeps :when (and (not (seen r)) (every? seen ds))] r))]
      (if (empty? ready)
        (do (println "STILL CYCLIC: " (pr-str (map leader (remove seen (keys cdeps))))) (js/process.exit 2))
        (recur (into done ready) (into seen ready) (inc guard))))))

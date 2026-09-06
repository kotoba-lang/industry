#!/usr/bin/env nbb
;; Score web architectures for fit with the Kotoba language surface.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/architecture-fit-score.cljs [--findings] [--csv]
;;
;; The dataset holds FACTS in a closed vocabulary; the rubric maps each fact to
;; a number. This script is the only place a total exists. Nothing here reads a
;; score out of a file, so a hand-edited total is not a thing that can happen.
;;
;; Exit codes are three, not two:
;;   0  every entry validated and the table was produced
;;   1  the dataset disagrees with the rubric (unknown level, missing axis, ...)
;;   2  REFUSED -- the check could not be performed, which is not the same as a
;;      pass. A detector that returns 0 when it could not run is the failure
;;      this workspace has found fourteen times (ADR-2608136000).
(ns architecture-fit-score
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["fs" :as fs]))

(def rubric-path "manifest/architecture-fit-rubric.edn")
(def dataset-path "90-docs/architecture-fit/architecture-fit.datoms.edn")

(def argv (vec (drop 2 (js->clj js/process.argv))))
(def findings? (some #{"--findings"} argv))
(def csv? (some #{"--csv"} argv))

(defn refuse! [msg]
  (println (str "REFUSED: " msg))
  (println "A check that could not run must not report a pass.")
  (js/process.exit 2))

(defn slurp-edn [path]
  (when-not (fs/existsSync path) (refuse! (str "not found: " path)))
  (try (edn/read-string (fs/readFileSync path "utf8"))
       (catch :default e (refuse! (str "unreadable EDN: " path " -- " (.-message e))))))

(def rubric (slurp-edn rubric-path))
(def dataset (slurp-edn dataset-path))

(def axes (:axes rubric))
(def axis-keys (mapv :axis axes))
(def by-axis (into {} (map (juxt :axis identity)) axes))
(def scale (:scale rubric))

(when (empty? axes) (refuse! "the rubric declares no axes"))
(when-not (vector? dataset) (refuse! "the dataset is not a vector of entities"))

;; ---------------------------------------------------------------- partition
(def coverage (first (filter :arch/coverage dataset)))
(def entries (vec (remove :arch/coverage dataset)))

(when (zero? (count entries))
  (refuse! "the dataset carries zero architecture entries -- an empty scan is not a clean scan"))

;; ------------------------------------------------------------------ validate
(defn axis-fact-key [axis] (keyword "arch" (name axis)))

(defn problems-for [e]
  (let [id (:arch/id e)]
    (concat
     (when (str/blank? (str id)) [{:entry "(no :arch/id)" :problem :missing-id}])
     (for [axis axis-keys
           :let [k (axis-fact-key axis)
                 v (get e k)
                 levels (:levels (by-axis axis))]
           :when (or (nil? v) (not (contains? levels v)))]
       {:entry id :problem (if (nil? v) :missing-axis :unknown-level)
        :axis axis :value v
        :admitted (when (some? v) (vec (sort (map name (keys levels)))))})
     (when (str/blank? (str (:arch/evidence e)))
       [{:entry id :problem :no-evidence}])
     (when-not (str/starts-with? (str (:arch/evidence e)) "http")
       [{:entry id :problem :evidence-is-not-a-url :value (:arch/evidence e)}])
     (when (str/blank? (str (:arch/note e)))
       [{:entry id :problem :no-note}])
     (when (nil? (:arch/family e)) [{:entry id :problem :no-family}]))))

(def dup-ids
  (->> entries (map :arch/id) frequencies (filter #(> (val %) 1)) (map key) sort vec))

(def structural
  (concat
   (when (nil? coverage) [{:entry "(coverage)" :problem :no-coverage-entity}])
   (for [d dup-ids] {:entry d :problem :duplicate-id})
   (when (and coverage (not= (:arch/entries coverage) (count entries)))
     [{:entry "(coverage)" :problem :entry-count-disagrees
       :value {:declared (:arch/entries coverage) :actual (count entries)}}])
   (when (not= (count axes) (:axes scale))
     [{:entry "(rubric)" :problem :axis-count-disagrees
       :value {:declared (:axes scale) :actual (count axes)}}])
   (when (not= (* (count axes) (:max scale)) (:total-max scale))
     [{:entry "(rubric)" :problem :total-max-disagrees}])
   (for [a axes
         [lvl spec] (:levels a)
         :when (not (<= (:min scale) (:score spec) (:max scale)))]
     {:entry "(rubric)" :problem :level-score-out-of-range :axis (:axis a) :value lvl})))

(def problems (vec (concat structural (mapcat problems-for entries))))

;; --------------------------------------------------------------------- score
(defn score-of [e axis]
  (get-in (by-axis axis) [:levels (get e (axis-fact-key axis)) :score]))

(def scored
  (when (empty? problems)
    (->> entries
         (map (fn [e]
                (let [s (into {} (map (fn [a] [a (score-of e a)])) axis-keys)]
                  (assoc e :scores s :total (reduce + (vals s))))))
         (sort-by (juxt (comp - :total) :arch/id))
         vec)))

;; --------------------------------------------------------------------- print
(defn pad [s n] (let [s (str s)] (if (>= (count s) n) (subs s 0 n) (str s (apply str (repeat (- n (count s)) " "))))))
(defn lpad [s n] (let [s (str s)] (str (apply str (repeat (max 0 (- n (count s))) " ")) s)))

(defn mean [xs] (if (empty? xs) 0 (/ (reduce + xs) (count xs))))
(defn r1 [x] (/ (js/Math.round (* 10 x)) 10))
(defn stdev [xs]
  (if (< (count xs) 2) 0
      (let [m (mean xs)] (js/Math.sqrt (mean (map #(* (- % m) (- % m)) xs))))))

(defn print-table []
  (println)
  (println (str "Kotoba web architecture fit -- " (count scored) " entries, "
                (count axes) " axes, max " (:total-max scale)))
  (println (str "rubric " rubric-path " as-of " (:kotoba.architecture-fit.rubric/as-of rubric)
                "; measured against kotoba-lang "
                (get-in rubric [:measured-against :kotoba-lang])
                " / amu " (get-in rubric [:measured-against :amu])))
  (println)
  (println (str (pad "#" 4) (pad "architecture" 46)
                (apply str (map #(lpad (subs (name %) 0 (min 4 (count (name %)))) 6) axis-keys))
                (lpad "TOTAL" 7) "  " "binding axis"))
  (println (apply str (repeat 118 "-")))
  (doseq [[i e] (map-indexed vector scored)]
    (let [binding-axis (key (apply min-key val (:scores e)))]
      (println (str (pad (str (inc i)) 4)
                    (pad (:arch/name e) 46)
                    (apply str (map #(lpad (get-in e [:scores %]) 6) axis-keys))
                    (lpad (:total e) 7)
                    "  " (name binding-axis)
                    " " (str "(" (get-in e [:scores binding-axis]) ")"))))))

(defn print-analysis []
  (println)
  (println "== axis analysis -- which constraint actually separates the field")
  (println (str (pad "axis" 14) (lpad "mean" 7) (lpad "sd" 7) (lpad "min" 5) (lpad "max" 5)
                "  " (pad "binds-most-often" 18) "invariant"))
  (println (apply str (repeat 108 "-")))
  (let [binding-counts (frequencies (map (fn [e] (key (apply min-key val (:scores e)))) scored))]
    (doseq [a (sort-by (fn [a] (- (stdev (map #(get-in % [:scores a]) scored)))) axis-keys)]
      (let [xs (map #(get-in % [:scores a]) scored)]
        (println (str (pad (name a) 14)
                      (lpad (r1 (mean xs)) 7) (lpad (r1 (stdev xs)) 7)
                      (lpad (apply min xs) 5) (lpad (apply max xs) 5)
                      "  " (pad (str (get binding-counts a 0) " / " (count scored)) 18)
                      (name (:invariant (by-axis a)))))))
    (println)
    (println (str "The axis with the largest spread is the one worth arguing about; the axis that "
                  "binds most often is the one that decides ports."))
    (println (str "Lowest mean axis: "
                  (name (apply min-key (fn [a] (mean (map #(get-in % [:scores a]) scored))) axis-keys))
                  " -- the field as a whole is furthest from the language here.")))
  (println)
  (println "== by family (mean total)")
  (doseq [[fam es] (->> scored (group-by :arch/family) (sort-by (fn [[_ es]] (- (mean (map :total es))))))]
    (println (str (pad (name fam) 22) (lpad (r1 (mean (map :total es))) 7)
                  "   n=" (count es)
                  "   " (str/join ", " (map :arch/id (take 3 (sort-by (comp - :total) es)))))))
  (println)
  (println "== by kind (mean total)")
  (doseq [[k es] (->> scored (group-by :arch/kind) (sort-by (fn [[_ es]] (- (mean (map :total es))))))]
    (println (str (pad (name k) 22) (lpad (r1 (mean (map :total es))) 7) "   n=" (count es))))
  (println)
  (let [tot (map :total scored)
        m (mean tot)]
    (println (str "total: mean " (r1 m) "  sd " (r1 (stdev tot))
                  "  range " (apply min tot) ".." (apply max tot)
                  "  median " (nth (sort tot) (quot (count tot) 2))))))

(defn print-csv []
  (println (str/join "," (concat ["rank" "id" "name" "kind" "family" "language"]
                                 (map name axis-keys) ["total" "binding_axis"])))
  (doseq [[i e] (map-indexed vector scored)]
    (println (str/join "," (concat [(inc i) (:arch/id e) (str "\"" (:arch/name e) "\"")
                                    (name (:arch/kind e)) (name (:arch/family e))
                                    (str "\"" (:arch/language e) "\"")]
                                   (map #(get-in e [:scores %]) axis-keys)
                                   [(:total e) (name (key (apply min-key val (:scores e))))])))))

;; ----------------------------------------------------------------------- run
(println (str "SCANNED\t" (count entries) "\tentries"))
(println (str "AXES\t" (count axes)))

(if (seq problems)
  (do (println)
      (println (str "FINDINGS\t" (count problems)))
      (doseq [p problems]
        (println (str "  " (pad (:entry p) 34) (name (:problem p))
                      (when (:axis p) (str " axis=" (name (:axis p))))
                      (when (contains? p :value) (str " value=" (pr-str (:value p))))
                      (when (:admitted p) (str "\n      admitted: " (str/join " " (:admitted p)))))))
      (println)
      (println "The table is NOT printed: a total computed from an unvalidated dataset would")
      (println "be a number nobody can check. Fix the dataset or the rubric and rerun.")
      (js/process.exit 1))
  (do (when-not findings? (if csv? (print-csv) (do (print-table) (print-analysis))))
      (when findings? (println "clean"))
      (js/process.exit 0)))

;; scripts/kotoba-admitted-vocabulary.cljs — what a `.kotoba` guest may actually
;; write, read out of the grammar rather than remembered.
;;
;; ## Why this exists
;;
;; Measured 2026-09-09. `orgs/kotoba-lang/com-aadhaar`'s whole-component
;; migration is recorded `:blocked`, and its gap table says these are rejected:
;;
;;   string literals anywhere; (str a b); (count v); (reduce f 0 xs);
;;   (mapv f xs); (filterv f xs); (keys m); (contains? m :k); (min a b)
;;
;; The grammar admits `count`, `reduce`, `keys`, `vals`, `contains?`, `filter`,
;; `map`, `assoc`, `get`, `take`, `nth`, `some->` as sugar, `min` as a builtin,
;; `string-concat` / `string-substring` / `string=` / `string-length` /
;; `string-index-of` / `string-contains?` as predicates -- and
;; `examples/todo-app.kotoba` opens with
;;
;;   (defn alphabet [] :string "abcdefghijklmnopqrstuvwxyz")
;;
;; The table was not measuring the language. It was measuring whether
;; CLOJURE'S NAMES resolve: `str` is not the head, `string-concat` is;
;; `mapv`/`filterv` are not heads, `map`/`filter` are; `[:vector T]` is not the
;; collection type, `[:list T]` is -- and it answers `:hetero-vector-type`,
;; which reads like a language limit and is a spelling error.
;;
;; A `:blocked` disposition derived that way keeps a component out of the
;; migration for a reason that was never true. So: print the vocabulary from
;; the authority, and probe with THESE names.
;;
;; ## What it does not tell you
;;
;; That a head is admitted is not that your USE of it compiles. Arity, type
;; annotations and the backend's own qualification are separate gates -- run
;; `amu check --jvm-free` on a minimal probe before recording any verdict.
;; This tool removes one failure mode (probing the wrong name); it does not
;; remove the need to measure.
;;
;; usage:
;;   nbb scripts/kotoba-admitted-vocabulary.cljs [--grep <substring>] [--edn]
(require '[clojure.edn :as edn] '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def argv (vec (drop 2 js/process.argv)))
(defn opt [f] (second (drop-while #(not= f %) argv)))
(def needle (opt "--grep"))
(def edn? (some #{"--edn"} argv))
(def root (.cwd js/process))

(def grammar-path
  "kotoba-sema owns the authority copy; amu reads it across its pin."
  (.join path root "orgs/kotoba-lang/kotoba-sema/resources/kotoba/lang/guest-grammar.edn"))

(when-not (.existsSync fs grammar-path)
  (println "REFUSED\tno guest-grammar.edn at" grammar-path)
  (println "  the authority is kotoba-lang/kotoba-sema; check that project out first")
  (.exit js/process 2))

(def grammar (edn/read-string (.readFileSync fs grammar-path "utf8")))

(def sections
  "Every grammar key that names something writable, with what it is. `:sugar`
  is where `count`/`reduce`/`keys`/`filter` live -- the section a reader
  looking for `admitted-builtins` alone will miss, and the one the aadhaar
  table missed."
  [[:core-special-forms "special forms"]
   [:sugar              "sugar (desugars to core; THIS is where count/reduce/keys/filter/get/assoc live)"]
   [:predicates         "predicates and string operations"]
   [:comparisons        "comparisons"]
   [:arithmetic         "arithmetic"]
   [:admitted-builtins  "builtins (mostly kernel/slice/f32; the low-level surface)"]
   [:string-head-host-ops "host ops reached by a declared capability import"]
   [:data-head-host-ops   "graph host ops, likewise capability-gated"]])

(defn names [k]
  (let [x (get grammar k)]
    (sort (map str (cond (map? x) (keys x) (coll? x) x :else nil)))))

(def forbidden (sort (map str (:forbidden-heads grammar))))

(when edn?
  (println (pr-str {:grammar-version (:kotoba.lang.guest-grammar/version grammar)
                    :sections (into {} (map (fn [[k _]] [k (vec (names k))])) sections)
                    :forbidden (vec forbidden)}))
  (.exit js/process 0))

(println (str "kotoba admitted vocabulary — guest-grammar version "
              (:kotoba.lang.guest-grammar/version grammar)))
(println (str "  " grammar-path))
(when needle (println (str "  filtered by: " needle)))
(println)

(def total (atom 0))
(doseq [[k label] sections]
  (let [all (names k)
        shown (if needle (filter #(str/includes? % needle) all) all)]
    (swap! total + (count all))
    (when (seq shown)
      (println (str (name k) " — " label
                    (if needle (str "  [" (count shown) " of " (count all) "]")
                        (str "  [" (count all) "]"))))
      (doseq [g (partition-all 8 shown)]
        (println (str "    " (str/join "  " g))))
      (println))))

(println (str "forbidden-heads [" (count forbidden) "]"))
(doseq [g (partition-all 8 (if needle (filter #(str/includes? % needle) forbidden) forbidden))]
  (println (str "    " (str/join "  " g))))
(println)
(println (str "SCANNED\t" @total "\tadmitted names across " (count sections) " grammar sections"))
(println)
(println "Names that are NOT heads here, and the ones that are:")
(println "    str      -> string-concat        subs   -> string-substring")
(println "    =(str)   -> string=              mapv   -> map")
(println "    filterv  -> filter               [:vector T] -> [:list T]")

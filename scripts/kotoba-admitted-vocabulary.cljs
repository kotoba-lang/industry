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
;; A `:blocked` disposition derived that way keeps a component out of the
;; migration for a reason that was never true. So: print the vocabulary from
;; the authority, and probe with THESE names.
;;
;; ## The correction this file needed itself (2026-09-09, same day)
;;
;; The paragraph that used to stand here said the gap table was "measuring
;; whether CLOJURE'S NAMES resolve: `str` is not the head, `string-concat` is;
;; `mapv`/`filterv` are not heads". Half of that is false, and it was written
;; from the grammar rather than measured. Against amu `6ffc1d71`:
;;
;;   (str a b)            amu check 0   -- and its definition CID is BYTE-
;;                                        IDENTICAL to (string-concat a b)
;;   (mapv f xs)          amu check 0
;;   (filterv f xs)       amu check 0
;;   (subs a 1 3)         refused: unknown operation
;;   (= a b) on strings   refused: "use string=? for string equality"
;;                                        -- and the name is `string=?`, not
;;                                        `string=` as that paragraph said
;;
;; None of `str`, `mapv`, `filterv`, `subs` appears anywhere in
;; `guest-grammar.edn` -- checked in the grammar amu resolves from its own lock
;; (`kotoba-sema 96fd4e19`) as well as in the checked-out copy, which are
;; different trees and agree here.
;;
;; So there are TWO questions and this tool answers one of them:
;;
;;   what the AUTHORITY admits      guest-grammar.edn -- what this file prints
;;   what the FRONTEND accepts      amu check -- what `--against` measures
;;
;; CLAUDE.md already names the gap ("`compiler/frontend.cljc` が受理することと
;; authority が認めることは別物"). The consequence is the opposite of a
;; convenience: `mapv` COMPILES and is NOT admitted, so a module written with
;; it is outside the surface the authority defines, and nothing in a green
;; `amu check` says so. That set -- accepted and not admitted -- is what
;; `--against` exists to print.
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
;;   nbb scripts/kotoba-admitted-vocabulary.cljs --against <amu checkout dir>
;;
;; `--against` writes one minimal probe per name below into a temp dir, runs
;; `bin/amu check --jvm-free` on each, and prints the four-way classification.
;; It is slow (one compiler start per probe) and it is the only half of this
;; tool that measures anything.
(require '[clojure.edn :as edn] '[clojure.string :as str])
(def child (js/require "node:child_process"))
(def os (js/require "node:os"))

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

;; ---------------------------------------------------------------------------
;; `--against <amu dir>`: what the FRONTEND accepts, next to what the authority
;; admits. The disagreements are the point.
;;
;; The probe bodies are written by hand because arity and type annotations
;; differ per name and a generated probe would fail for reasons that have
;; nothing to do with the name. Each is minimal, each USES its own result --
;; an unused definition is not type-checked, which is exactly how a gap table
;; recorded a refused construct as PASS -- and each is left on disk so the
;; next reader re-runs rather than re-reads.

(def probes
  "name -> a whole module that exercises it and consumes the result."
  {"str"             "(ns p (:export [q]))\n(defn j [a :string b :string] :string (str a b))\n(defn q [] :i64 (string-byte-length (j \"ab\" \"cd\")))"
   "string-concat"   "(ns p (:export [q]))\n(defn j [a :string b :string] :string (string-concat a b))\n(defn q [] :i64 (string-byte-length (j \"ab\" \"cd\")))"
   "subs"            "(ns p (:export [q]))\n(defn j [a :string] :string (subs a 1 3))\n(defn q [] :i64 (string-byte-length (j \"abcd\")))"
   "string-substring" "(ns p (:export [q]))\n(defn j [a :string] :string (string-substring a 1 3))\n(defn q [] :i64 (string-byte-length (j \"abcd\")))"
   "="               "(ns p (:export [q]))\n(defn j [a :string b :string] :bool (= a b))\n(defn q [] :i64 (if (j \"ab\" \"ab\") 1 0))"
   "string=?"        "(ns p (:export [q]))\n(defn j [a :string b :string] :bool (string=? a b))\n(defn q [] :i64 (if (j \"ab\" \"ab\") 1 0))"
   "mapv"            "(ns p (:export [q]))\n(defn q [] :i64 (count (mapv (fn [x] (+ x 1)) [1 2 3])))"
   "map"             "(ns p (:export [q]))\n(defn q [] :i64 (count (map (fn [x] (+ x 1)) [1 2 3])))"
   "filterv"         "(ns p (:export [q]))\n(defn q [] :i64 (count (filterv (fn [x] (> x 1)) [1 2 3])))"
   "filter"          "(ns p (:export [q]))\n(defn q [] :i64 (count (filter (fn [x] (> x 1)) [1 2 3])))"
   "remove"          "(ns p (:export [q]))\n(defn q [] :i64 (count (remove (fn [x] (> x 1)) [1 2 3])))"
   "reduce"          "(ns p (:export [q]))\n(defn q [] :i64 (reduce (fn [a x] (+ a x)) 0 [1 2 3]))"
   "count"           "(ns p (:export [q]))\n(defn q [] :i64 (count [1 2 3]))"
   "min"             "(ns p (:export [q]))\n(defn q [] :i64 (min 3 4))"
   "keys"            "(ns p (:export [q]))\n(defn q [] :i64 (count (keys {1 10 2 20})))"
   "vals"            "(ns p (:export [q]))\n(defn q [] :i64 (count (vals {1 10 2 20})))"
   ;; A CANONICAL typed map, not `{:a 1}`. A keyword-keyed literal is the
   ;; legacy bounded map, which answers to `get` and `assoc` and not to these
   ;; three, so probing them with one reports an admitted head as absent --
   ;; the probe's mistake wearing a language gap's clothes.
   "contains?"       "(ns p (:export [q]))\n(defn q [] :i64 (if (contains? {1 10} 1) 1 0))"
   "seq"             "(ns p (:export [q]))\n(defn q [] :i64 (count (seq [1 2 3])))"
   "parse-long"      "(ns p (:export [q]))\n(defn q [] :i64 (parse-long \"12\"))"
   "trim"            "(ns p (:export [q]))\n(defn j [a :string] :string (trim a))\n(defn q [] :i64 (string-byte-length (j \" a \")))"
   "lower-case"      "(ns p (:export [q]))\n(defn j [a :string] :string (lower-case a))\n(defn q [] :i64 (string-byte-length (j \"AB\")))"
   "long"            "(ns p (:export [q]))\n(defn q [] :i64 (long 3))"})

(when-let [amu-dir (opt "--against")]
  (let [tmp (.mkdtempSync fs (.join path (.tmpdir os) "kotoba-vocab-"))
        ;; The sugar section stores its names as keywords, so they arrive as
        ;; ":count" while the frontend is asked about "count". Comparing the
        ;; two forms without normalising put five admitted heads in the
        ;; ACCEPTED-BUT-NOT-ADMITTED bucket -- the bucket whose whole purpose
        ;; is to be alarming.
        admitted (into #{} (map #(str/replace % #"^:" ""))
                       (mapcat names (map first sections)))
        run (fn [[nm src]]
              (let [f (.join path tmp (str (str/replace nm #"[^a-zA-Z0-9]" "_") ".kotoba"))]
                (.writeFileSync fs f src "utf8")
                ;; A probe that did not survive being written is not a
                ;; measurement of anything. Measured while adding this: three
                ;; probe strings carried a doubled escape, so the file on disk
                ;; held a literal backslash-n, every form landed on one line,
                ;; and `amu` answered "only ns, def, defn ... at top level" --
                ;; which the classifier below read as a FRONTEND GAP for
                ;; `keys`, `vals` and `contains?`. All three are admitted and
                ;; all three pass when the file has real newlines.
                (let [back (.readFileSync fs f "utf8")]
                  (when-not (and (= back src) (str/starts-with? back "(ns ")
                                 (str/includes? back "\n"))
                    (println (str "Refusing to classify " nm
                                  ": the probe file is not the probe -- " f))
                    (.exit js/process 2)))
                (let [r (.spawnSync child (.join path amu-dir "bin" "amu")
                                    (clj->js ["check" f "--jvm-free"])
                                    #js {:encoding "utf8" :cwd amu-dir})]
                  [nm (contains? admitted nm)
                   (str/includes? (or (.-stdout r) "") "{:ok true")])))
        rows (mapv run (sort-by first probes))
        bucket (fn [a f] (mapv first (filter (fn [[_ x y]] (and (= a x) (= f y))) rows)))]
    (println)
    (println (str "MEASURED\t" (count rows) "\tprobe(s) against " amu-dir
                  ", written to " tmp))
    (doseq [[a f label]
            [[true  true  "admitted AND accepted -- write these"]
             [false true  "ACCEPTED BUT NOT ADMITTED -- compiles, outside the authority"]
             ;; NOT "a frontend gap". The probe carries one arity and one
             ;; argument type, and the grammar entry may be wider than that:
             ;; `=` lands here because this probe compares two STRINGS and the
             ;; refusal says `use string=? for string equality`, which is a
             ;; deliberate narrowing rather than a missing head. Read the
             ;; message in the probe directory before calling anything a gap.
             [true  false "admitted, and refused for THIS probe -- read the message"]
             [false false "neither -- a spelling error, not a language limit"]]]
      (let [b (bucket a f)]
        (println (str "  " label "  [" (count b) "]"))
        (when (seq b) (println (str "    " (str/join "  " b))))))
    ;; Evidence floor: a run where every probe failed to start looks exactly
    ;; like a run where the frontend refuses everything.
    (when (empty? (filter (fn [[_ _ f]] f) rows))
      (println "Refusing to report: not one probe was accepted, which is what a")
      (println "broken `bin/amu` invocation also looks like.")
      (.exit js/process 2))))

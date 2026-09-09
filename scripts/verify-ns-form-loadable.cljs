#!/usr/bin/env nbb
;; scripts/verify-ns-form-loadable.cljs — `ns` forms that cannot macroexpand,
;; in files no test loads.
;;
;; ADR-2609101200. Found by accident while swapping a JSON library: every rewire
;; was checked with one line per edited namespace, `clojure -M -e '(require
;; (quote <ns>))'`, and five repos were loaded that way. Five failed, none
;; because of the change. Two of the five failed at the `ns` form itself:
;;
;;   (ns tashikame.aozora
;;     (:require [kotoba.net.jvm-host :as jvm-host]
;;               [tashikame.publisher :as publisher])
;;                [java.time Instant]      ; <- the (:import ...) keyword is gone
;;              [java.util UUID]))         ; <- so these dangle as ns references
;;
;; `ns` takes references that are LISTS beginning with a keyword (:require,
;; :import, :use, :refer-clojure, :load, :gen-class). A bare vector sitting
;; directly inside the `ns` form is not one, and `clojure.core/ns` throws while
;; macroexpanding. The file never loads. Nothing says so, because no test
;; requires it — the suites in these repos are green and substantial and simply
;; never touch the file.
;;
;; WHAT THIS DETECTS AND WHAT IT DOES NOT. This is a shape check on the `ns`
;; form, so it catches exactly the defect above and its relatives. It does NOT
;; run anything, so it cannot see the other two failure modes ADR-2609101200
;; records: a required namespace no coordinate provides, and a Java class used
;; with no `:import`. Those need a classpath. A clean run here means "no ns form
;; is malformed in the way we can see without one", which is a smaller claim
;; than "these namespaces load".
;;
;;   nbb scripts/verify-ns-form-loadable.cljs [--findings] <dir>...
;;
;; exit 0 clean / 1 findings / 2 REFUSED (could not answer)

(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))

;; nbb's argv is [node nbb <this script> args...], so drop three, not two.
;; Dropping two leaves the script's own path in `roots`: harmless while walking
;; (readdirSync on a file throws and is caught) but it makes REFUSED name a
;; "directory" the caller never passed, which is the wrong thing to hand someone
;; who is trying to work out why a scan came back empty.
(def argv (vec (drop 3 (js->clj js/process.argv))))
(def findings-mode? (some #{"--findings"} argv))
(def roots (vec (remove #(str/starts-with? % "--") argv)))

(defn refuse! [msg]
  (println (str "REFUSED\t" msg))
  (println "Refusing to report a pass: this run could not answer the question.")
  (js/process.exit 2))

;; ---------------------------------------------------------------- reading

(defn clj-source? [p]
  (some #(str/ends-with? p %) [".clj" ".cljc"]))

(defn walk
  "Every .clj/.cljc under dir. Skips the usual generated/vendored trees, and
  skips `.git` — a west checkout keeps one per project."
  [dir]
  (let [out (atom [])]
    (letfn [(go [d depth]
              (when (< depth 12)
                (let [ents (try (js->clj (.readdirSync fs d #js {:withFileTypes true}))
                                (catch :default _ nil))]
                  (doseq [e (or ents [])]
                    (let [nm (.-name e)
                          full (.join path d nm)]
                      (cond
                        (.isDirectory e)
                        (when-not (contains? #{".git" "node_modules" "target" ".cpcache"
                                               ".shadow-cljs" "out" "dist" ".clj-kondo"}
                                             nm)
                          (go full (inc depth)))

                        (and (.isFile e) (clj-source? nm))
                        (swap! out conj full)))))))]
      (go dir 0))
    @out))

;; ---------------------------------------------------------------- the shape

(defn strip-noise
  "Blank out string literals, character literals and line comments so their
  contents cannot be mistaken for structure. Length is preserved so offsets
  still line up with the original.

  Escape state is carried as a FLAG, not inferred by looking back one or two
  characters. Whether a quote closes its string depends on the parity of the
  backslash run before it, and no fixed-width lookback can decide that.
  Measured 2026-09-10: a two-character lookback read

      \\\\\\\"maks_hind\\\\\\\"

  -- three backslashes then a quote, i.e. one literal backslash followed by an
  ESCAPED quote -- as an unescaped quote, ended the docstring 80 lines early,
  and every paren of prose after it was counted as structure. The detector then
  reported a well-formed file as malformed. A checker that mis-parses is worse
  than no checker: it sends someone to a file that is fine."
  [s]
  (let [n (count s)
        sb (js/Array. n)]
    (loop [i 0, in-str? false, in-cmt? false, escaped? false]
      (if (>= i n)
        (.join sb "")
        (let [c (nth s i)]
          (cond
            in-cmt?
            (do (aset sb i (if (= c "\n") "\n" " "))
                (recur (inc i) false (not= c "\n") false))

            ;; inside a string: the previous char was a backslash that was not
            ;; itself escaped, so this character is literal whatever it is
            (and in-str? escaped?)
            (do (aset sb i (if (= c "\n") "\n" " "))
                (recur (inc i) true false false))

            in-str?
            (do (aset sb i (if (= c "\n") "\n" " "))
                (cond
                  (= c "\\") (recur (inc i) true false true)
                  (= c "\"") (recur (inc i) false false false)
                  :else      (recur (inc i) true false false)))

            (= c "\"")
            (do (aset sb i " ") (recur (inc i) true false false))

            (= c ";")
            (do (aset sb i " ") (recur (inc i) false true false))

            ;; \( \[ \" etc — a character literal, blank the char it names
            (and (= c "\\") (< (inc i) n))
            (do (aset sb i " ") (aset sb (inc i) " ")
                (recur (+ i 2) false false false))

            :else
            (do (aset sb i c) (recur (inc i) false false false))))))))

(defn ns-form-span
  "[start end] of the first top-level `(ns ` form in blanked source, or nil."
  [blank]
  (when-let [m (.exec (js/RegExp. "\\(ns\\s" "g") blank)]
    (let [start (.-index m)]
      (loop [i start, depth 0]
        (cond
          (>= i (count blank)) nil
          :else
          (let [c (nth blank i)]
            (cond
              (contains? #{"(" "[" "{"} c) (recur (inc i) (inc depth))
              (contains? #{")" "]" "}"} c) (if (= 1 depth)
                                             [start (inc i)]
                                             (recur (inc i) (dec depth)))
              :else (recur (inc i) depth))))))))

(defn dangling-vectors
  "Offsets of `[` that sit DIRECTLY inside the ns form (depth 1). At depth 1 the
  only legal elements are lists `(:require ...)`, a docstring, an attr-map, and
  the namespace symbol — never a vector. Returns a vector of offsets."
  [blank start end]
  (let [out (atom [])]
    (loop [i start, depth 0]
      (when (< i end)
        (let [c (nth blank i)]
          (cond
            (= c "(") (do (recur (inc i) (inc depth)))
            (= c "{") (do (recur (inc i) (inc depth)))
            (= c "[") (do (when (= depth 1) (swap! out conj i))
                          (recur (inc i) (inc depth)))
            (contains? #{")" "]" "}"} c) (recur (inc i) (dec depth))
            :else (recur (inc i) depth)))))
    @out))

(defn line-of [s off]
  (inc (count (re-seq #"\n" (subs s 0 off)))))

(defn ns-name-at [s start]
  (when-let [m (re-find #"\(ns\s+([A-Za-z0-9_.$*+!?<>=/-]+)" (subs s start (min (count s) (+ start 200))))]
    (second m)))

(defn examine
  "nil, or a finding map."
  [file]
  (let [src (try (str (.readFileSync fs file "utf8")) (catch :default _ ::unreadable))]
    (if (= src ::unreadable)
      {:file file :kind :unreadable}
      (let [blank (strip-noise src)]
        (when-let [[start end] (ns-form-span blank)]
          (let [offs (dangling-vectors blank start end)]
            (when (seq offs)
              {:file file
               :ns (ns-name-at src start)
               :lines (mapv #(line-of src %) offs)
               :count (count offs)})))))))

;; ---------------------------------------------------------------- control
;;
;; Every run first proves the check can still tell a malformed ns form from a
;; well-formed one. A detector that has never discriminated is indistinguishable
;; from one that always passes.

(def bad-fixture
"(ns fixture.bad
  \"a docstring with a ] and a ( in it\"
  (:require [a.b :as b]
            [c.d :as d])
             [java.time Instant]
           [java.util UUID]))
(defn f [] 1)")

(def good-fixture
"(ns fixture.good
  \"a docstring with a ] and a ( in it\"
  (:require [a.b :as b]
            [c.d :as d])
  (:import [java.time Instant]
           [java.util UUID]))
(defn f [] 1)")

(defn check-fixture [src]
  (let [blank (strip-noise src)]
    (when-let [[s e] (ns-form-span blank)]
      (count (dangling-vectors blank s e)))))

(defn self-check!
  "Returns the number of control failures. A count, not a boolean: a boolean
  cannot tell one regression from a wholly broken scanner."
  []
  (let [fails (atom [])]
    (let [b (check-fixture bad-fixture)]
      (when-not (and (number? b) (pos? b))
        (swap! fails conj (str "malformed fixture was not flagged (got " (pr-str b) ")"))))
    (let [g (check-fixture good-fixture)]
      (when-not (= 0 g)
        (swap! fails conj (str "well-formed fixture WAS flagged (got " (pr-str g) ")"))))
    ;; a docstring containing brackets must not be read as structure
    (let [d (check-fixture "(ns x \"[java.time Instant] in prose\" (:require [a :as a]))")]
      (when-not (= 0 d)
        (swap! fails conj (str "a bracket inside a docstring was read as structure (got " (pr-str d) ")"))))
    ;; ODD-LENGTH BACKSLASH RUN. The real defect this detector shipped with once:
    ;; a docstring containing \\\" (one literal backslash then an escaped quote)
    ;; was read as ending the string, and the prose after it became structure.
    ;; The fixture below is that shape, plus an unbalanced "(" in the prose so a
    ;; regression cannot pass by accident.
    (let [d (check-fixture
             (str "(ns x\n  \"the portal says \\\\\\\"maks_hind\\\\\\\" and ( is unbalanced prose\n"
                  "   [java.time Instant] is also prose\"\n  (:require [a :as a]))"))]
      (when-not (= 0 d)
        (swap! fails conj (str "an odd-length backslash run ended the docstring early (got "
                               (pr-str d) ")"))))
    @fails))

;; ---------------------------------------------------------------- main

(let [control (self-check!)]
  (when (seq control)
    (refuse! (str "self-check failed " (count control) " way(s): " (str/join "; " control)))))

(when (empty? roots)
  (refuse! "no directory given"))

(doseq [r roots]
  (when-not (try (.existsSync fs r) (catch :default _ false))
    (refuse! (str "not readable: " r))))

(def files (vec (mapcat walk roots)))

(when (zero? (count files))
  (refuse! (str "scanned 0 Clojure sources under " (str/join " " roots)
                " -- an empty scan is not a clean scan")))

(def results (keep examine files))
(def unreadable (filter #(= :unreadable (:kind %)) results))
(def findings (remove #(= :unreadable (:kind %)) results))

(when (seq unreadable)
  (refuse! (str (count unreadable) " file(s) could not be read; a partial scan"
                " cannot report a pass")))

(println (str "SCANNED\t" (count files) "\tClojure source file(s)"))
(println (str "NOTE\tcheckouts only -- orgs/ is west-managed and an unchecked-out"
              " project is invisible to the filesystem, so SCANNED is not a"
              " workspace total."))
(println (str "NOTE\tshape check on the `ns` form only. It does not run anything,"
              " so it cannot see a required namespace no coordinate provides, or a"
              " Java class used with no :import (ADR-2609101200 records both)."))

(when findings-mode?
  (doseq [f (sort-by :file findings)]
    (println (str "FINDING\terror\tns-form-cannot-macroexpand\t"
                  (:file f) "\t" (or (:ns f) "?")
                  "\tvector(s) directly inside the ns form at line(s) "
                  (str/join ", " (:lines f))
                  " -- `ns` references must be lists starting with a keyword"
                  " (:require/:import/...), so this file throws while"
                  " macroexpanding and never loads"))))

(println (str "FINDINGS\t" (count findings)))
(js/process.exit (if (seq findings) 1 0))

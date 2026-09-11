;; Split one namespace into its definitions and the edges between them.
;;
;; Tokenising has to skip strings, character literals and `;` comments, or the
;; graph is nonsense: every docstring in this file names other functions, and a
;; first attempt that ignored that reported `split` -- which is implemented on
;; top of match-spans -- as a leaf.
(require '[clojure.string :as s])
(def fs (js/require "node:fs"))
(def path (first *command-line-args*))
(def src (str (.readFileSync fs path "utf8")))

(defn code-mask
  "A boolean per character: true where the character is code, false inside a
  string, a character literal, or a line comment."
  [t]
  (let [n (count t)]
    (loop [i 0, in-str? false, esc? false, in-cmt? false, out (transient [])]
      (if (>= i n)
        (persistent! out)
        (let [c (nth t i)]
          (cond
            in-cmt? (recur (inc i) false false (not= c \newline) (conj! out false))
            esc?    (recur (inc i) in-str? false false (conj! out false))
            in-str? (cond (= c \\) (recur (inc i) true true false (conj! out false))
                          (= c \") (recur (inc i) false false false (conj! out false))
                          :else    (recur (inc i) true false false (conj! out false)))
            (= c \") (recur (inc i) true false false (conj! out false))
            (= c \;) (recur (inc i) false false true (conj! out false))
            ;; \c character literal: skip the next char
            (and (= c \\) (< (inc i) n))
            (recur (+ i 2) false false false (conj! (conj! out false) false))
            :else (recur (inc i) false false false (conj! out true))))))))

(def mask (code-mask src))
(defn code-at? [i] (get mask i false))

;; top-level forms start at column 0 with `(`
(def tops
  ;; A top-level reader conditional is a top-level form. kotoba.lang.process
  ;; wraps read-bounded in #?(:clj ...); accepting only `(` at column zero meant
  ;; the form was never a candidate and the definition inside it vanished.
  (vec (for [i (range (count src))
             :when (and (code-at? i)
                        (or (zero? i) (= \newline (nth src (dec i))))
                        (or (= \( (nth src i))
                            (and (= \# (nth src i))
                                 (= \? (get src (inc i)))
                                 (= \( (get src (+ i 2))))))]
         i)))

(defn form-end [start]
  (loop [i (inc (if (= \# (nth src start)) (+ start 2) start)), depth 1]
    (cond (>= i (count src)) (count src)
          (not (code-at? i)) (recur (inc i) depth)
          (= \( (nth src i)) (recur (inc i) (inc depth))
          (= \) (nth src i)) (if (= 1 depth) (inc i) (recur (inc i) (dec depth)))
          :else (recur (inc i) depth))))

(defn- protocol-methods
  "The method names a defprotocol form introduces.

  A protocol defines its own name AND one var per method signature, and a
  dependent calls the METHODS, not the protocol. Missing that would make every
  caller of read-text look like it depends on nothing."
  [body]
  (vec (distinct (map second (re-seq #"(?m)^\s{2,}\(([a-zA-Z][a-zA-Z0-9*+!?<>=_-]*)\s" body)))))

(def forms
  (vec (for [st tops
             :let [en (form-end st)
                   head (subs src st (min (count src) (+ st 400)))
                   ;; a top-level reader conditional is named after the first
                   ;; definition inside it, and the whole conditional is the unit
                   m (or (re-find #"^\((def|defn|defn-|def-|defonce|defmacro|defprotocol|defrecord)\s+(?:\^\S+\s+)*([a-zA-Z0-9*+!?<>=_.-]+)" head)
                         (re-find #"^#\?@?\([\s\S]*?\((def|defn|defn-|def-|defonce|defmacro|defprotocol|defrecord)\s+(?:\^\S+\s+)*([a-zA-Z0-9*+!?<>=_.-]+)" head))]
             :when m]
         (let [kind (or (nth m 1) (nth m 3)) nm (or (nth m 2) (nth m 4))]
           {:kind kind :name nm :start st :end en
            :provides (cond
                        (= "defprotocol" kind)
                        (into [nm] (protocol-methods (subs src st en)))

                        ;; A defrecord introduces the type AND two constructor
                        ;; functions. A dependent calls ->RNG, not RNG, and
                        ;; missing that would make every caller look like it
                        ;; depends on nothing -- the same mistake the protocol
                        ;; case above was written to avoid.
                        (= "defrecord" kind)
                        [nm (str "->" nm) (str "map->" nm)]

                        :else
                        [nm])}))))

(def owner-of (into {} (for [f forms, p (:provides f)] [p (:name f)])))
(def names (set (keys owner-of)))

;; EVIDENCE FLOOR: every top-level definition form must be accounted for.
;;
;; The first version recognised def / defn / defn- / def- / defmacro and nothing
;; else, and when it was pointed at kotoba.lang.fs it silently dropped two
;; defprotocol forms -- 16 forms in the file, 14 in the output, and no
;; complaint. A splitter that cannot see a definition will publish a library
;; without it. So the count is checked against the file, and anything unseen is
;; named rather than skipped.
(def all-def-heads
  ;; EVERY definition head in a code position, at any indentation.
  ;;
  ;; Counting only column-zero forms missed kotoba.lang.process's
  ;; #?(:clj (defn- read-bounded ...)) twice over: the enclosing form starts
  ;; with `#`, so it was never a top-level candidate, and the definition inside
  ;; it is indented. The split shipped a repo whose body called a symbol that
  ;; did not exist anywhere, and only the suite said so.
  ;; ...but only where a definition can actually BE. `def[a-zA-Z-]*` also
  ;; matches a CALL to a function whose name starts with def: kotoba.lang.lint-kotoba
  ;; has a helper named def-name and calls it twice from inside a cond, and the
  ;; floor refused the whole namespace over two function calls. So a candidate
  ;; counts only if it is a top-level form itself, or sits inside a top-level
  ;; reader conditional -- which is the case the floor was widened for
  ;; (kotoba.lang.process's #?(:clj (defn- read-bounded ...))).
  (vec (for [i (range (count src))
             :let [owner (last (filter #(<= % i) tops))
                   in-conditional? (and owner (= \# (nth src owner)))]
             :when (and (= \( (nth src i)) (code-at? i)
                        (or (= i owner) in-conditional?))
             :let [m (re-find #"^\((def[a-zA-Z-]*|extend-[a-z-]+)\s+(?:\^\S+\s+)*([^\s()]+)" (subs src i (min (count src) (+ i 200))))]
             ;; A name that begins with ~ or ~@ is UNQUOTED: it is computed when
             ;; the enclosing macro expands, so the form is a template, not a
             ;; definition this file makes. kotoba.lang.test's deftest emits
             ;; (def ~test-name test-fn#) and the floor counted it as a
             ;; definition the extractor had failed to understand -- a refusal
             ;; with nothing behind it. The floor still counts every real head
             ;; at any indentation; this drops only the ones whose NAME does not
             ;; exist until expansion.
             :when (and m (not (s/starts-with? (nth m 2) "~")))]
         [(nth m 1) (nth m 2)])))
(def unseen (remove (fn [[_ n]] (contains? names n)) all-def-heads))
(when (seq unseen)
  (println (str "REFUSED: " (count unseen) " top-level definition form(s) this extractor"
                " does not understand, out of " (count all-def-heads) ". Splitting would"
                " publish a library without them."))
  (doseq [[k n] unseen] (println (str "UNSEEN\t" k "\t" n)))
  (js/process.exit 2))

(println (str "FORMS\t" (count forms) "\tof " (count all-def-heads) " top-level definition forms"))

(defn tokens-in
  "Identifiers that appear at least once in a CODE position between st and en.

  Checking only the first occurrence was wrong: a docstring names the function
  the body then calls, so `trim` -- whose body uses java-whitespace? -- came out
  a leaf because the first `java-whitespace?` in its form is inside prose."
  [st en]
  (let [acc (volatile! #{})]
    (loop [i st]
      (when (< i en)
        ;; A token may START with any character legal in a Clojure symbol, not
        ;; just a letter. Requiring a letter made every definition whose name
        ;; begins with punctuation invisible: ->ymd was never emitted as a
        ;; token, so ->iso8601 came out with no edge to it and its repo did not
        ;; compile. Names like -main and *warn-on-reflection* have the same
        ;; shape. Runs with no letter at all (numbers, ->, =) are dropped below,
        ;; where they cannot match a definition name anyway.
        ;; A keyword is never a reference to a var, and scanning through one
        ;; invents edges. Measured 2026-09-09 on kotoba.lang.log:
        ;; (def levels [:trace :debug :info :warn :error :fatal]) gave `levels`
        ;; an edge to each of the six functions of the same name, and with
        ;; trace -> log -> emit -> level-enabled? -> level-rank -> levels
        ;; already real, twelve separate definitions condensed into one false
        ;; cycle -- one repo where there should have been twelve.
        (if (and (code-at? i) (= \: (nth src i)))
          (recur (loop [k (inc i)]
                   (if (and (< k en)
                            (re-find #"[a-zA-Z0-9*+!?<>=_.:/-]" (str (nth src k))))
                     (recur (inc k)) k)))
        (if (and (code-at? i)
                 (re-find #"[a-zA-Z0-9*+!?<>=_.-]" (str (nth src i)))
                 (or (= i 0) (not (re-find #"[a-zA-Z0-9*+!?<>=_.-]" (str (nth src (dec i)))))))
          (let [j (loop [k i] (if (and (< k en) (re-find #"[a-zA-Z0-9*+!?<>=_.-]" (str (nth src k)))) (recur (inc k)) k))]
            (let [tok (subs src i j)]
              (when (re-find #"[a-zA-Z]" tok) (vswap! acc conj tok)))
            (recur j))
          (recur (inc i))))))
    @acc))


;; Every definition name must be something the tokeniser can actually PRODUCE.
;; A name the scan cannot emit is a name that can never appear as a dependency,
;; and the failure is silent: the definition looks like a leaf, its dependents
;; look independent of it, and the generated repo does not compile. Measured
;; 2026-09-09 on kotoba.lang.time -- ->ymd was invisible and ->iso8601's repo
;; failed with "Unable to resolve symbol: ->ymd".
;;
;; The test is the token GRAMMAR, not presence in this file. An earlier version
;; asked whether the name appears in the source, which conflated "the scan
;; cannot see this" with "nothing here uses it": defrecord RNG provides
;; map->RNG, nothing in kotoba.lang.test calls it, and the extractor refused a
;; namespace it understood perfectly well. A definition nobody references is
;; legitimate; a definition nobody CAN reference is the defect.
(defn tokenisable?
  [n]
  (and (re-find #"[a-zA-Z]" n)                       ; runs with no letter are dropped
       (not (s/starts-with? n ":"))                  ; keywords are skipped entirely
       (re-matches #"[a-zA-Z0-9*+!?<>=_.-]+" n)))    ; every char legal in a token run

(let [blind (sort (remove tokenisable? names))
      whole (tokens-in 0 (count src))
      unref (sort (remove whole names))]
  (println (str "TOKENISABLE\t" (- (count names) (count blind)) "\tof " (count names) " definition names"))
  (doseq [n unref] (println (str "UNREFERENCED\t" n)))
  (when (seq blind)
    (doseq [n blind] (println (str "BLIND\t" n)))
    (println "REFUSING: the reference scan cannot produce the names above, so any edge to them would be dropped silently.")
    (js/process.exit 2)))

(doseq [{:keys [kind name start end provides]} forms]
  ;; the head (name itself) must not count as a self-edge, and a reference to a
  ;; protocol METHOD is an edge to the protocol that declares it
  (let [body-start (+ start (count (str "(" kind " " name)))
        deps (->> (tokens-in body-start end)
                  (filter names)
                  (map owner-of)
                  (remove #(= % name))
                  set)]
    (println (str "DEF\t" name "\t" kind "\t" (s/join "," (sort deps))))
    (when (> (count provides) 1)
      (println (str "PROVIDES\t" name "\t" (s/join "," (sort provides)))))))

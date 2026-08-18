#!/usr/bin/env nbb
;; What does this .cljc call that safe Kotoba does not admit?
;;
;; Peeling one rejection at a time answers "what fails first", which is not the
;; same question and costs a 40-second compile per answer. This computes the
;; whole set in one pass: every call head in the source, minus the admitted
;; vocabulary read out of the compiler's own frontend and the language
;; authority.
;;
;;   nbb scripts/kotoba-surface-gap.cljs <file-or-dir>... \
;;     --frontend <kotoba-sema>/src/kotoba/compiler/frontend.cljc \
;;     --grammar  <kotoba-lang>/lang/guest-grammar.edn \
;;     [--stdlib  <kotoba-lang>/lang/stdlib/core.kotoba]
;;
;; Exit 0 = no gaps, 1 = gaps found, 2 = COULD NOT ANSWER. The third value
;; exists because the failure this tool is most likely to have is reading
;; nothing and reporting a clean bill: no files matched, an unreadable
;; frontend, an empty vocabulary. Those are not passes and must not share an
;; exit code with one.
;;
;; The scan is lexical, not a reader. It strips strings, comments and character
;; literals, then takes the symbol after each `(`. It cannot see through macros
;; and it counts a head inside quoted data as a call. Both directions of that
;; error are visible in the output -- every finding carries file:line -- which
;; is the property that matters, since an unreviewable number would be worse
;; than a slightly noisy list.

(ns kotoba-surface-gap
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            [cljs.reader :as reader]))

(defn die! [code msg]
  (.error js/console msg)
  (.exit js/process code))

;; ── reading the source ────────────────────────────────────────────────────

(defn strip-noise
  "Blank out strings, line comments and character literals, preserving offsets
  so line numbers stay true."
  [line in-string?]
  (let [n (count line)]
    (loop [i 0, out [], in-str? in-string?]
      (if (>= i n)
        [(str/join out) in-str?]
        (let [c (nth line i)]
          (cond
            in-str? (cond
                      (= c "\\") (recur (+ i 2) (conj out " " " ") true)
                      (= c "\"") (recur (inc i) (conj out " ") false)
                      :else (recur (inc i) (conj out " ") true))
            (= c "\"") (recur (inc i) (conj out " ") true)
            (= c ";") [(str (str/join out) (str/join (repeat (- n i) " "))) false]
            ;; \( \; \" -- a character literal, never a call head
            (= c "\\") (recur (+ i 2) (conj out " " " ") false)
            :else (recur (inc i) (conj out c) in-str?)))))))

(def head-re #"([#'`]?)\(([^\s()\[\]{}'\"`,;&]+)")

(defn call-heads
  "[{:head :line}] for every `(sym` in the file, minus quoted lists."
  [text]
  (loop [lines (str/split-lines text), ln 1, in-str? false, acc []]
    (if (empty? lines)
      acc
      (let [[clean in-str?'] (strip-noise (first lines) in-str?)
            found (map (fn [m]
                         {:head (nth m 2) :line ln
                          ;; `'(...)` is data and `#(...)` is a lambda; neither
                          ;; has a call head at that position.
                          :skip? (contains? #{"'" "#"} (nth m 1))})
                       (re-seq head-re clean))]
        (recur (rest lines) (inc ln) in-str?'
               (into acc (remove :skip? found)))))))

(defn defined-names
  "Names this corpus itself defines -- callable without being in the vocabulary."
  [text]
  ;; Deliberately only `def*`. A `let`-bound lambda -- `(let [clear (fn [r] ..)]
  ;; (clear x))` -- is callable and is NOT recognised here, so it shows up as a
  ;; false positive. The obvious fix, treating any `sym (fn` as a binding, was
  ;; tried and reverted: it also matches `(remove (fn [e] ..) xs)`, and so
  ;; silently absorbed `remove`, `mapv` and `mapcat` -- three real gaps -- into
  ;; the "locally defined" set. A false positive is visible in the output and
  ;; costs a reader ten seconds. A false negative is a gap this tool exists to
  ;; find and reports as absent.
  (set (map second (re-seq #"\(def(?:n|record|protocol|interface|type)?-?\s+(?:\^\S+\s+)?([a-zA-Z0-9\-\?\!\*\+<>=]+)" text))))

(defn require-aliases
  "alias -> namespace, from the ns form's :require clause."
  [text]
  (into {} (map (fn [m] [(nth m 2) (nth m 1)])
                (re-seq #"\[([a-zA-Z0-9._-]+)\s+:as\s+([a-zA-Z0-9._-]+)\]" text))))

;; ── reading the admitted vocabulary ───────────────────────────────────────

(defn balanced-form
  "The substring starting at `start` (a `(`) through its matching close."
  [s start]
  (loop [i start, d 0]
    (if (>= i (count s))
      (subs s start)
      (let [c (nth s i)]
        (cond (= c "(") (recur (inc i) (inc d))
              (= c ")") (if (= 1 d) (subs s start (inc i)) (recur (inc i) (dec d)))
              :else (recur (inc i) d))))))

(defn- balanced-braces
  "The `{...}` starting at or just after `start`, through its matching close."
  [s start]
  (let [open (str/index-of s "{" start)]
    (loop [i open, d 0]
      (if (or (nil? open) (>= i (count s)))
        ""
        (let [c (nth s i)]
          (cond (= c "{") (recur (inc i) (inc d))
                (= c "}") (if (= 1 d) (subs s open (inc i)) (recur (inc i) (dec d)))
                :else (recur (inc i) d)))))))

(defn- all-indexes [s needle]
  (loop [from 0, acc []]
    (if-let [i (str/index-of s needle from)]
      (recur (inc i) (conj acc i))
      acc)))

(defn frontend-vocabulary
  "Every symbol named inside a quoted set or map in the compiler frontend --
  both the top-level `(def x '#{...})` tables and the inline `(contains?
  '#{inc dec} f)` membership tests, which admit operations without appearing
  in any table. Reading only the tables reports `inc` as unadmitted, which is
  how this was found."
  [text]
  (let [forms (concat
               (keep (fn [i] (when (str/starts-with? (subs text i) "(def ")
                               (let [f (balanced-form text i)]
                                 (when (re-find #"'[#]?\{" f) f))))
                     (all-indexes text "(def "))
               (map #(balanced-braces text %) (all-indexes text "'#{"))
               (map #(balanced-braces text %) (all-indexes text "'{")))]
    (reduce (fn [acc form]
              (into acc (remove #(or (re-matches #"[0-9]+" %)
                                     (str/starts-with? % ":"))
                                (map second (re-seq #"[\s{]([^\s{}()\[\]#'\"]+)" form)))))
            #{} forms)))

(defn grammar-vocabulary [text]
  (let [g (reader/read-string text)]
    (into (set (map str (:core-special-forms g)))
          (map name (keys (:sugar g))))))

(defn stdlib-vocabulary [text]
  (set (map second (re-seq #"\(defn\s+([a-zA-Z0-9\-\?\!\*\+<>=]+)" text))))

;; ── driver ────────────────────────────────────────────────────────────────

(defn opt [args flag]
  (second (drop-while #(not= % flag) args)))

(defn sources [target]
  (let [st (try (fs/statSync target)
                (catch :default e
                  ;; A path that does not exist must not reach the file loop and
                  ;; come back as "no files matched" -- that is the same shape as
                  ;; a clean scan of an empty tree, and only one of the two is an
                  ;; answer.
                  (die! 2 (str "COULD-NOT-ANSWER\tunreadable target: " target
                               "\n" (.-message e)))))]
    (if (.isDirectory st)
      (mapcat #(sources (path/join target %)) (fs/readdirSync target))
      (when (re-find #"\.(cljc|cljk|kotoba)$" target) [target]))))

(defn -main [& args]
  (let [flags (set (filter #(str/starts-with? % "--") args))
        targets (loop [a args, acc []]
                  (cond (empty? a) acc
                        (str/starts-with? (first a) "--") (recur (drop 2 a) acc)
                        :else (recur (rest a) (conj acc (first a)))))
        frontend (opt args "--frontend")
        grammar (opt args "--grammar")
        stdlib (opt args "--stdlib")
        read! (fn [p what]
                (try (str (fs/readFileSync p "utf8"))
                     (catch :default e
                       (die! 2 (str "COULD-NOT-ANSWER\tunreadable " what ": " p
                                    "\n" (.-message e))))))]
    (when-not frontend (die! 2 "COULD-NOT-ANSWER\t--frontend is required"))
    (when-not grammar (die! 2 "COULD-NOT-ANSWER\t--grammar is required"))
    (let [vocab (cond-> (into (frontend-vocabulary (read! frontend "frontend"))
                              (grammar-vocabulary (read! grammar "grammar")))
                  stdlib (into (stdlib-vocabulary (read! stdlib "stdlib"))))
          files (vec (mapcat sources targets))]
      (when (< (count vocab) 100)
        (die! 2 (str "COULD-NOT-ANSWER\tvocabulary is implausibly small ("
                     (count vocab) " names) -- the tables were not found")))
      (when (zero? (count files))
        (die! 2 "COULD-NOT-ANSWER\tno .cljc/.cljk/.kotoba files matched"))
      (let [corpus (map (fn [f] [f (str (fs/readFileSync f "utf8"))]) files)
            local (reduce into #{} (map (comp defined-names second) corpus))
            findings
            (for [[f text] corpus
                  :let [aliases (require-aliases text)]
                  {:keys [head line]} (call-heads text)
                  :let [q (when (str/includes? head "/") (first (str/split head #"/")))]
                  :when (not (contains? local head))
                  :when (not (contains? vocab head))
                  :when (not (str/starts-with? head ":"))
                  :when (not (and q (contains? aliases q)))]
              {:file f :line line :head (if (= head "#") "#{...} in call position" head)
               :kind (if q :interop :unadmitted)})]
        (println (str "SCANNED\t" (count files) " file(s)"))
        (println (str "VOCAB\t" (count vocab) " admitted name(s)"))
        (doseq [[kind label] [[:unadmitted "unqualified heads outside the admitted vocabulary"]
                              [:interop "qualified heads that are not project modules (interop)"]]]
          (let [group (filter #(= kind (:kind %)) findings)]
            (println (str "\n-- " label " -- " (count (set (map :head group))) " distinct"))
            (doseq [[head hits] (sort-by (comp - count val) (group-by :head group))]
              (println (str head "\t" (count hits) "\t"
                            (str/join " " (map #(str (path/basename (:file %)) ":" (:line %))
                                               (take 4 hits))))))))
        (.exit js/process (if (seq findings) 1 0))))))

(apply -main (drop 3 (js->clj js/process.argv)))

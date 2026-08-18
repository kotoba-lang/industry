;; Repair 90-docs EDN documents whose section maps were never closed.
;;
;; Several `90-docs/deployment` and `90-docs/gates` documents share one
;; generation defect: the closing `}` before each next top-level key was omitted,
;; so nesting depth climbs by one at every section and the file ends deep instead
;; of at zero. Because a top-level entity key cannot legitimately live inside the
;; previous section's sub-map, the place the brace belongs is unambiguous — right
;; before the next indent-2 key — which makes this mechanical rather than a
;; judgement call.
;;
;; Refuses to write unless the result parses AND every indent-2 key in the source
;; is present as a key on the repaired entity, so a file cannot be "fixed" into a
;; different shape. Anything it cannot prove, it leaves alone.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/docs-edn-close-sections.cljs [--write] <file>...
(ns docs-edn-close-sections
  (:require ["fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- scan [line depth in-string?]
  (loop [i 0, d depth, in? in-string?]
    (if (>= i (count line))
      [d in?]
      (let [c (nth line i)]
        (cond
          in? (cond (= c \\) (recur (+ i 2) d true)
                    (= c \") (recur (inc i) d false)
                    :else (recur (inc i) d true))
          (= c \") (recur (inc i) d true)
          (= c \\) (recur (+ i 2) d false)
          (= c \;) [d in?]
          (#{\[ \{ \(} c) (recur (inc i) (inc d) false)
          (#{\] \} \)} c) (recur (inc i) (dec d) false)
          :else (recur (inc i) d false))))))

(def top-key-re #"^  :([a-zA-Z][a-zA-Z0-9._/-]*)")

(defn- source-top-keys [lines]
  (loop [idx 0, in? false, acc []]
    (if (>= idx (count lines))
      acc
      (let [line (nth lines idx)
            hit (when-not in? (re-find top-key-re line))
            [_ in2] (scan line 0 in?)]
        (recur (inc idx) in2 (if hit (conj acc (second hit)) acc))))))

(defn- repair
  "Insert the missing `}` before each top-level key that sits too deep.
  Returns repaired lines, or nil when nothing needed changing."
  [lines]
  (let [out (transient (vec lines))
        changed (atom 0)]
    (loop [idx 0, depth 0, in? false, last-content nil]
      (when (< idx (count lines))
        (let [line (nth lines idx)
              key? (and (not in?) (re-find top-key-re line))]
          (when (and key? (> depth 2) last-content)
            (let [pad (str/join (repeat (- depth 2) "}"))]
              (assoc! out last-content (str (nth lines last-content) pad))
              (swap! changed inc)))
          (let [depth (if (and key? (> depth 2)) 2 depth)
                [d in2] (scan line depth in?)]
            (recur (inc idx) d in2
                   (if (str/blank? (str/trim line)) last-content idx))))))
    (when (pos? @changed) [(persistent! out) @changed])))

(defn- entity-keys
  "Qualified key names, matching what source-top-keys captures from the text.
  `name` alone would drop the namespace and make every comparison a false
  mismatch."
  [text]
  (let [content (edn/read-string {:default (fn [_ v] v)} text)
        e (cond (map? content) content
                (and (sequential? content) (map? (first content))) (first content))]
    (when e (set (map #(subs (str %) 1) (filter keyword? (keys e)))))))

(defn process [file write?]
  (let [text (fs/readFileSync file "utf8")
        lines (str/split-lines text)
        expected (set (source-top-keys lines))]
    (println (str "== " file))
    (if-let [[fixed n] (repair lines)]
      ;; split-lines drops a trailing newline; restore it so the repair is a
      ;; pure delimiter insertion and shows up as such in review.
      (let [candidate (str (str/join "\n" fixed)
                           (when (str/ends-with? text "\n") "\n"))
            got (try (entity-keys candidate) (catch :default e (str "PARSE: " (ex-message e))))]
        (cond
          (string? got)
          (println (str "   refused — still does not parse (" got ")"))

          (nil? got)
          (println "   refused — repaired text is not a single-entity document")

          (seq (remove got expected))
          (println (str "   refused — these source keys would be lost: "
                        (pr-str (sort (remove got expected)))))

          :else
          (do (println (str "   " (if write? "wrote" "would insert") " " n
                            " closing brace(s); entity keys " (count got)
                            "/" (count expected) " preserved"))
              (when write? (fs/writeFileSync file candidate)))))
      (println "   nothing to close"))))

(let [args *command-line-args*
      write? (boolean (some #{"--write"} args))]
  (doseq [f (remove #{"--write"} args)] (process f write?)))

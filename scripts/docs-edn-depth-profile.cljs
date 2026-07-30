;; Depth profile for a 90-docs EDN document.
;;
;; In `[{...}]` tx-data every top-level entity key sits at nesting depth 2 (the
;; outer vector, then the entity map). Printing the depth at each indent-2 key
;; turns "one delimiter is wrong somewhere in 500 lines" into "the drift starts
;; at this key", and shows whether a file has one fault or several compounding
;; ones — which decides whether it can be repaired mechanically at all.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/docs-edn-depth-profile.cljs <file>...
(ns docs-edn-depth-profile
  (:require ["fs" :as fs]
            [clojure.string :as str]))

(defn- scan
  "Advance [depth in-string?] across one line, honouring escapes, char literals
  and comments."
  [line depth in-string?]
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

(defn profile [file]
  (let [lines (str/split-lines (fs/readFileSync file "utf8"))]
    (println (str "== " file))
    (loop [idx 0, depth 0, in? false, expected nil, reported 0]
      (if (>= idx (count lines))
        (println (str "   end depth " depth (when (not= depth 0) "  <-- should be 0")))
        (let [line (nth lines idx)
              key? (and (not in?) (re-find #"^  :[a-zA-Z]" line))
              expected (or expected (when key? depth))
              drift? (and key? expected (not= depth expected) (< reported 6))
              [d in2] (scan line depth in?)]
          (when drift?
            (println (str "   line " (inc idx) " depth " depth " (expected " expected ") "
                          (str/trim (subs line 0 (min 70 (count line)))))))
          (recur (inc idx) d in2 expected (if drift? (inc reported) reported)))))))

(doseq [f *command-line-args*] (profile f))

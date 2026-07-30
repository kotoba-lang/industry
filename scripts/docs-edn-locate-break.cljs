;; Locate where an EDN document actually breaks.
;;
;; A reader error names where the wreckage surfaced, not where it started. One
;; unescaped quote inside a string swallows everything after it and prose is then
;; read as structure; one surplus `}` closes a collection early and nothing
;; complains until EOF. Both routinely report a line hundreds away from the bug.
;;
;; Two complementary passes, no heuristics — so `pr-str` string blobs (the
;; documented ADR body shape, full of legitimately escaped quotes) never produce
;; false positives:
;;
;;   balance  a single scan tracking the delimiter stack outside strings. Finds
;;            surplus and mismatched closers, and unclosed openers, by line.
;;   prefix   parse growing prefixes. A prefix that is merely truncated fails
;;            EOF-shaped and is skipped; the first prefix failing for any other
;;            reason carries the defect on its last line.
;;
;; Note `edn/read-string` reads only the first form and ignores trailing text, so
;; a genuinely surplus delimiter at the very end of a file is harmless and is
;; reported as such rather than as an error.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/docs-edn-locate-break.cljs <file>...
(ns docs-edn-locate-break
  (:require ["fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def closer->opener {")" "(" "]" "[" "}" "{"})

(defn balance
  "Scan `text` outside strings and comments. Returns the first delimiter problem
  as {:line :kind :detail}, or nil."
  [text]
  (let [n (count text)]
    (loop [i 0, line 1, stack '(), in-string? false]
      (if (>= i n)
        (cond
          in-string? {:line line :kind :unterminated-string
                      :detail "string runs to end of file"}
          (seq stack) {:line (:line (first stack)) :kind :unclosed
                       :detail (str "`" (:ch (first stack)) "` opened here is never closed")}
          :else nil)
        (let [c (subs text i (inc i))
              nl? (= c "\n")
              line' (if nl? (inc line) line)]
          (cond
            in-string?
            (cond
              (= c "\\") (recur (+ i 2) line' stack true)
              (= c "\"") (recur (inc i) line' stack false)
              :else (recur (inc i) line' stack true))

            (= c "\"") (recur (inc i) line' stack true)
            ;; char literal, e.g. \" or \n — consume the escaped char
            (= c "\\") (recur (+ i 2) line' stack false)
            (= c ";") (let [nlpos (str/index-of text "\n" i)]
                        (if nlpos (recur nlpos line stack false) (recur n line stack false)))

            (contains? #{"(" "[" "{"} c)
            (recur (inc i) line' (conj stack {:ch c :line line}) false)

            (contains? closer->opener c)
            (cond
              (empty? stack)
              {:line line :kind :surplus
               :detail (str "`" c "` closes nothing — a collection was already closed earlier")}
              (not= (:ch (first stack)) (closer->opener c))
              {:line line :kind :mismatch
               :detail (str "`" c "` does not match `" (:ch (first stack))
                            "` opened on line " (:line (first stack)))}
              :else (recur (inc i) line' (rest stack) false))

            :else (recur (inc i) line' stack false)))))))

(defn- incomplete? [message]
  (let [m (str/lower-case (or message ""))]
    (or (str/includes? m "eof") (str/includes? m "unexpected end"))))

(defn- parse-error [text]
  (try (edn/read-string {:default (fn [_ v] v)} text) nil
       (catch :default e (ex-message e))))

(defn- show [lines n]
  (doseq [i (range (max 0 (- n 3)) (min (count lines) n))]
    (let [l (nth lines i)]
      (println (str "     " (inc i) (if (= (inc i) n) " > " "   ")
                    (str/trim (subs l 0 (min 120 (count l)))))))))

(defn locate [file]
  (let [text (fs/readFileSync file "utf8")
        lines (str/split-lines text)]
    (println (str "== " file " (" (count lines) " lines)"))
    (if-not (parse-error text)
      (println "   parses cleanly")
      ;; Balance is a single pass and answers every delimiter defect, so only
      ;; fall through to the quadratic prefix pass when it finds nothing — that
      ;; is the malformed-string / bad-token case.
      (if-let [{:keys [line kind detail]} (balance text)]
        (do (println (str "   balance: " (name kind) " at line " line " — " detail))
            (show lines line))
        (loop [n 1]
          (if (> n (count lines))
            (println "   delimiters consistent and no prefix failure — inspect the reader message directly")
            (let [m (parse-error (str/join "\n" (take n lines)))]
              (if (and m (not (incomplete? m)))
                (do (println (str "   token:   first non-EOF failure at line " n " — " m))
                    (show lines n))
                (recur (inc n))))))))))

(doseq [f *command-line-args*] (locate f))

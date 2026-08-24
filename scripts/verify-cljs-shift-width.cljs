#!/usr/bin/env nbb
;; verify-cljs-shift-width.cljs -- bit shifts of 32 or more in code that
;; ClojureScript executes, where the shift count is silently taken mod 32.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-cljs-shift-width.cljs \
;;     [--findings] [--root <dir>]
;;
;; ## What it is about
;;
;; JavaScript's shift operators take the shift count modulo 32, and cljs
;; `bit-shift-left` / `bit-shift-right` / `unsigned-bit-shift-right` compile
;; straight to them. So on ClojureScript:
;;
;;   (bit-shift-left 1 31)  => -2147483648 (not 2147483648 -- the SIGN BIT)
;;   (bit-shift-left 1 32)  => 1           (not 4294967296)
;;   (bit-shift-left 1 56)  => 16777216    (not 2^56)
;;   (unsigned-bit-shift-right x 32) => x >>> 0, the low word
;;
;; Note the first line. The boundary for `bit-shift-left` is 31, not 32: the
;; result is an int32, so a shift that lands a set bit in position 31 makes the
;; number NEGATIVE rather than wrapping. Measured 2026-08-25 while fixing
;; `av1.bitreader/uvlc`, whose `2^leading_zeros - 1` term went negative at 31
;; leading zeros -- a case the first version of this detector, which only
;; looked for 32 and above, did not report.
;;
;; It does not throw. It returns a plausible number of the right type, which is
;; why this survives: on the JVM the same expression is right, so a `.cljc`
;; file is correct in the suite that runs and wrong in the runtime nobody asked.
;;
;; Found by hand three times in two days before this detector existed:
;;   kotoba.kir.cljs-i64/ashr          divisor 2^shift, wrong for every shift>=32
;;   kotoba.compiler.packaging.pe32plus/read-le
;;                                     ELF 64-bit fields, high 4 bytes folded
;;                                     into the low bits; an admission check
;;                                     that failed OPEN on the second runtime
;;   kotoba.object.pe32plus/little-endian
;;                                     PE image-base at width 8, bytes 4..7
;;                                     repeating bytes 0..3
;; Two of those three sat in files whose own comments already explained this
;; exact hazard, in a different function.
;;
;; ## What counts as "code ClojureScript executes"
;;
;; `.cljs` entirely, and the part of a `.cljc` that survives the cljs reader.
;; `#?(:clj ...)` branches are BLANKED IN PLACE -- replaced with spaces, not
;; deleted -- so that every reported line number is the one a reader opening the
;; file will see. Deleting them instead moved the numbers; measured 2026-08-25,
;; a spot check landed five lines from the finding and read as a false positive.
;;
;; `.clj` files are not scanned at all. There the shift is correct.
;;
;; ## The count this does NOT answer
;;
;; A shift whose count is an expression -- `(* 8 index)`, `(- 63 n)` -- cannot
;; be classified by reading. Two of the three defects above were exactly that
;; shape. They are counted and printed as UNMEASURED, never as clean; this
;; detector reports only the counts it can read.
;;
;; Not a fleet gate: it reads west-managed checkouts under orgs/.
;;
;; ## exit codes
;;
;;   0  no readable shift count of 32 or more in cljs-reachable code
;;   1  at least one
;;   2  COULD NOT ANSWER -- nothing was scanned

(ns verify-cljs-shift-width
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

(def ^:private argv (vec (drop 2 (js->clj (.-argv js/process)))))
(def ^:private findings-mode? (some #{"--findings"} argv))

(defn- arg-after [flag]
  (let [i (.indexOf argv flag)] (when (>= i 0) (get argv (inc i)))))

(def ^:private root (or (arg-after "--root") (.cwd js/process)))

(def ^:private skip-dirs
  #{"node_modules" "target" "out" ".git" ".shadow-cljs" ".cpcache"
    ".cljs_node_repl" ".calva" "dist" "build"})

(defn- walk [dir depth]
  (if (neg? depth)
    []
    (let [entries (try (js->clj (fs/readdirSync dir #js {:withFileTypes true}))
                       (catch :default _ nil))]
      (if (nil? entries)
        []
        (mapcat (fn [e]
                  (let [nm (.-name e) p (path/join dir nm)]
                    (cond
                      (skip-dirs nm) []
                      (.isDirectory e) (walk p (dec depth))
                      (and (.isFile e)
                           (or (str/ends-with? nm ".cljc") (str/ends-with? nm ".cljs"))) [p]
                      :else [])))
                entries)))))

;; ---------------------------------------------------------------------------
;; blanking: strings, comments, and :clj branches -- all replaced by spaces so
;; every character keeps its offset and every newline stays a newline
;; ---------------------------------------------------------------------------

(defn- blank-run [^string s start end]
  (apply str (map (fn [i] (let [c (.charAt s i)] (if (= c "\n") "\n" " ")))
                  (range start end))))

(defn- blank-strings-and-comments [src]
  (let [n (count src)
        out (js/Array. n)]
    (loop [i 0 in-string? false]
      (if (>= i n)
        (.join out "")
        (let [c (.charAt src i)]
          (cond
            in-string?
            (cond
              (= c "\\") (do (aset out i " ")
                             (when (< (inc i) n) (aset out (inc i) " "))
                             (recur (+ i 2) true))
              (= c "\"") (do (aset out i " ") (recur (inc i) false))
              :else (do (aset out i (if (= c "\n") "\n" " ")) (recur (inc i) true)))

            (= c "\"") (do (aset out i " ") (recur (inc i) true))

            ;; character literal: \" \; \( must not start a string or comment
            (= c "\\") (do (aset out i " ")
                           (when (< (inc i) n) (aset out (inc i) " "))
                           (recur (+ i 2) false))

            (= c ";") (let [nl (loop [j i] (if (or (>= j n) (= (.charAt src j) "\n")) j (recur (inc j))))]
                        (dotimes [k (- nl i)] (aset out (+ i k) " "))
                        (recur nl false))

            :else (do (aset out i c) (recur (inc i) false))))))))

(defn- matching-close
  "Index of the delimiter closing the one at `start`, or nil.
  `src` must already have strings blanked."
  [src start]
  (let [n (count src)
        openers #{"(" "[" "{"} closers #{")" "]" "}"}]
    (loop [i start d 0]
      (if (>= i n)
        nil
        (let [c (.charAt src i)]
          (cond
            (openers c) (recur (inc i) (inc d))
            (closers c) (if (= 1 d) i (recur (inc i) (dec d)))
            :else (recur (inc i) d)))))))

(defn- read-form
  "[text next-index] for the form starting at or after `i`."
  [src i]
  (let [n (count src)
        i (loop [j i] (if (and (< j n) (re-find #"\s" (.charAt src j))) (recur (inc j)) j))]
    (cond
      (>= i n) [nil i]
      (#{"(" "[" "{"} (.charAt src i))
      (if-let [close (matching-close src i)]
        [(subs src i (inc close)) (inc close)]
        [(subs src i) n])
      :else
      (let [end (loop [j i]
                  (if (or (>= j n)
                          (re-find #"\s" (.charAt src j))
                          (#{"(" ")" "[" "]" "{" "}"} (.charAt src j)))
                    j (recur (inc j))))]
        [(subs src i end) end]))))

(defn- blank-clj-branches
  "Replace the body of every `#?(:clj ...)` / `#?@(:clj ...)` branch with
  spaces. The `:cljs` and `:default` bodies are left alone."
  [src]
  (loop [s src from 0]
    (let [idx (.indexOf s "#?" from)]
      (if (neg? idx)
        s
        (let [open (loop [j (+ idx 2)] (if (and (< j (count s)) (= "@" (.charAt s j))) (recur (inc j)) j))]
          (if (not= "(" (.charAt s open))
            (recur s (+ idx 2))
            (if-let [close (matching-close s open)]
              (let [s' (loop [acc s i (inc open)]
                         (if (>= i close)
                           acc
                           (let [[k after-k] (read-form acc i)]
                             (if (nil? k)
                               acc
                               (let [[v after-v] (read-form acc after-k)]
                                 (if (nil? v)
                                   acc
                                   (if (= ":clj" k)
                                     (recur (str (subs acc 0 (- after-v (count v)))
                                                 (blank-run acc (- after-v (count v)) after-v)
                                                 (subs acc after-v))
                                            after-v)
                                     (recur acc after-v))))))))]
                (recur s' (inc open)))
              (recur s (+ idx 2)))))))))

;; ---------------------------------------------------------------------------

(def ^:private shift-pattern #"\((unsigned-)?bit-shift-(left|right)\s")

(defn- literal-value [token]
  (cond
    (re-matches #"-?\d+" token) (js/parseInt token 10)
    (re-matches #"0[xX][0-9a-fA-F]+" token) (js/parseInt (subs token 2) 16)
    :else nil))

(defn- line-of [src idx]
  (inc (count (re-seq #"\n" (subs src 0 idx)))))

(defn- scan-file [file]
  (when-let [raw (try (fs/readFileSync file "utf8") (catch :default _ nil))]
    (let [blanked (cond-> raw
                    (str/ends-with? file ".cljc") blank-clj-branches
                    true blank-strings-and-comments)
          hits (atom [])
          sign-bit (atom [])
          computed (atom 0)
          readable (atom 0)]
      (loop [from 0]
        (let [rest-src (subs blanked from)
              m (re-find shift-pattern rest-src)]
          (when m
            (let [at (+ from (.indexOf rest-src (first m)))
                  after-head (+ at (count (first m)))
                  [_ after-value] (read-form blanked after-head)
                  [cnt _] (read-form blanked after-value)]
              (when (some? cnt)
                (if-let [v (literal-value cnt)]
                  (do (swap! readable inc)
                      ;; `bit-shift-left` is wrong from 31 up: at 31 the result
                      ;; is int32-negative rather than 2^31. The right shifts
                      ;; are only wrong from 32, where the count wraps.
                      (let [left? (str/includes? (first m) "bit-shift-left")]
                        (cond
                          (>= v 32)
                          (swap! hits conj {:line (line-of blanked at) :shift v
                                            :op (if left? "bit-shift-left" "shift-right")
                                            :why "count taken mod 32"})
                          ;; A left shift by exactly 31 puts a set bit in the
                          ;; sign position, so on ClojureScript the result is
                          ;; negative where the JVM's is 2^31. That IS a
                          ;; divergence, but it is often deliberate: code that
                          ;; builds a 32-bit word and then masks it, writes it
                          ;; into a Uint32Array, or runs it through a `u32`
                          ;; helper is correct as written. Measured 2026-08-25,
                          ;; three sites: one real (opus.celt's overflow guard
                          ;; compared against a negative number, so it never
                          ;; fired) and two deliberate (xz.crc64 has its own
                          ;; `u32`; a car-sim test writes into a Uint32Array).
                          ;; One in three is not a finding rate. Reported and
                          ;; counted, never failing the run.
                          (and left? (= v 31))
                          (swap! sign-bit conj
                                 {:line (line-of blanked at)
                                  :file file}))))
                  (swap! computed inc)))
              (recur (inc at))))))
      {:file file :hits @hits :sign-bit @sign-bit
       :computed @computed :readable @readable})))

(defn -main []
  (let [orgs (path/join root "orgs")
        repos (try (vec (mapcat (fn [org]
                                  (let [od (path/join orgs org)]
                                    (when (try (.isDirectory (fs/statSync od)) (catch :default _ false))
                                      (keep (fn [r]
                                              (let [rd (path/join od r)]
                                                (when (try (.isDirectory (fs/statSync rd)) (catch :default _ false))
                                                  rd)))
                                            (js->clj (fs/readdirSync od))))))
                                (js->clj (fs/readdirSync orgs))))
                   (catch :default _ []))]
      (when (zero? (count repos))
        (println "REFUSING: no orgs/<org>/<repo> checkout was readable under" root)
        (println "This detector cannot answer, which is not the same as clean.")
        (.exit js/process 2))
      (let [files (mapcat #(walk % 8) repos)
            results (keep scan-file files)
            findings (mapcat (fn [{:keys [file hits]}]
                               (map (fn [{:keys [line shift op why]}]
                                      {:id (str "shift-" shift ":"
                                                (str/replace file (str root "/") "") ":" line)
                                       :file (str/replace file (str root "/") "")
                                       :line line :shift shift :op op :why why})
                                    hits))
                             results)
            readable (reduce + (map :readable results))
            computed (reduce + (map :computed results))
            sign-bit (mapcat :sign-bit results)]
        (when (zero? (count results))
          (println "REFUSING: scanned 0 files. Refusing to report a pass.")
          (.exit js/process 2))
        (println (str "SCANNED\t" (count results)))
        (println (str "SHIFTS-READ\t" readable
                      "\t(a literal count this could classify)"))
        (println (str "SHIFTS-COMPUTED\t" computed
                      "\t(the count is an expression -- UNMEASURED here, not clean;"
                      " two of the three defects this detector was written for"
                      " were exactly this shape)"))
        (println (str "SHIFT-LEFT-31\t" (count sign-bit)
                      "\t(negative on ClojureScript, 2^31 on the JVM --"
                      " often deliberate, so reported and never failing;"
                      " measured 1 of 3 real)"))
        (doseq [{:keys [file line]} (sort-by (juxt :file :line) sign-bit)]
          (println (str "  SIGN-BIT  "
                        (str/replace file (str root "/") "") ":" line)))
        (println (str "FINDINGS\t" (count findings)))
        (doseq [{:keys [id file line shift op why]} (sort-by :id findings)]
          (if findings-mode?
            (println (str "FINDING\t" id "\t" op " by " shift
                          " on ClojureScript: " why))
            (println (str "  " file ":" line "\tshift=" shift "\t" why))))
        (.exit js/process (if (seq findings) 1 0)))))

(-main)

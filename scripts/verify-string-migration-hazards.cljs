#!/usr/bin/env nbb
(ns verify-string-migration-hazards
  "Call sites where swapping clojure.string for kotoba.lang.text would CHANGE
  THE ANSWER, not just the name.

  ## Why this exists

  kotoba.lang.text stopped delegating to clojure.string on 2026-09-08 and had
  to decide three things clojure.string does not decide -- because its two host
  implementations disagree, and a delegating wrapper inherited the
  disagreement invisibly. kotoba.lang.text adopts the JVM's answer in all
  three and pins it, so a `.clj` call site is unaffected and a `.cljs` one is
  not.

  The workspace has roughly 3,900 repos and ~30,000 clojure.string call sites.
  Almost all of them are safe to rewrite mechanically. This finds the ones that
  are NOT, so a bulk migration has a list to skip rather than a hope.

  Measured 2026-09-08 over orgs/ + scripts/ + 70-tools/, and the answer is much
  smaller than the fear: 3,002 `split` calls with a regex separator and 1,918
  `replace` calls with a regex match and a string replacement, of which

    1 split  has a capturing group -- and it is .clj, so nothing changes
    8 replace use `$&`
    6 replace use a backslash before `$N`

  Fourteen files, all .cljs. Nothing in the .cljc corpus is affected.

  ## The three divergences, and which are visible in source

    split with a capturing group   JVM drops the captures; JS String.split
                                   interleaves them into the result. VISIBLE.

    replace with `$&`              the whole match in JS; ordinary text on the
                                   JVM and in kotoba.lang.text (write `$0`).
                                   VISIBLE.

    replace with `\\$`             an escaped literal dollar on the JVM; a
                                   backslash followed by a group reference in
                                   JS. VISIBLE.

    trim's whitespace class        the JVM excludes U+00A0/2007/202F and
                                   includes U+001C-001F; goog.string is the
                                   reverse. NOT VISIBLE -- it depends on the
                                   DATA, not the source. 15,395 .cljc/.cljs
                                   call sites could differ, and no scan can
                                   say which, so this detector does not
                                   pretend to. It reports the population as
                                   :info so the number is not mistaken for
                                   zero.

  Exit: 0 clean, 1 findings, 2 REFUSED (could not measure).

    nbb --classpath \".:scripts/nbb_compat\" scripts/verify-string-migration-hazards.cljs [--findings] [--root DIR]"
  (:require [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))

(def argv (vec (drop 2 (js->clj js/process.argv))))
(def findings-mode? (some #{"--findings"} argv))
(def root (or (second (drop-while #(not= "--root" %) argv)) "."))

(def scan-dirs ["orgs" "scripts" "70-tools"])

(defn- rg [pattern]
  (try
    (let [r (.spawnSync cp "rg"
                        (clj->js (concat ["-n" "--no-heading" "--no-messages"
                                          "-g" "*.clj" "-g" "*.cljc" "-g" "*.cljs"
                                          "--glob" "!node_modules" "--glob" "!target"
                                          "--glob" "!out" "--glob" "!dist"
                                          "-e" pattern]
                                         scan-dirs))
                        #js {:cwd root :encoding "utf8" :maxBuffer 268435456})]
      ;; rg exits 1 for "no matches", which is a real answer; only a crash is not
      (when (<= (.-status r) 1) (or (.-stdout r) "")))
    (catch :default _ nil)))

(defn- ext [p] (last (str/split p #"\.")))

;; A Clojure source string literal, and the characters it actually denotes.
(defn- unescape [s]
  (str/replace s #"\\(.)"
               (fn [m] (let [c (second m)]
                         (case c "n" "\n" "t" "\t" "r" "\r" "\"" "\"" "\\" "\\" c)))))

(def ^:private split-call
  ;; the regex has to be the SECOND argument of split, not merely on the line
  #"(?:str|string)/split\s+(?:[^\s()]+|\([^()]{0,80}\))\s+#\"((?:[^\"\\]|\\.)*)\"")

(def ^:private replace-call
  #"(?:str|string)/replace(?:-first)?\s+(?:[^\s()]+|\([^()]{0,80}\))\s+#\"(?:[^\"\\]|\\.)*\"\s+\"((?:[^\"\\]|\\.)*)\"")

(defn- capturing-group?
  "An unescaped `(` that opens a capture -- not `(?:`, and not one inside a
  character class, where a paren is literal. Both exclusions were measured:
  without the second, `#\"\\s+[-|(]\\s*\" was reported as a capture."
  [pattern]
  (loop [i 0, in-class? false]
    (if (>= i (count pattern))
      false
      (let [c (nth pattern i)]
        (cond
          (= c \\) (recur (+ i 2) in-class?)
          (= c \[) (recur (inc i) true)
          (= c \]) (recur (inc i) false)
          (and (= c \() (not in-class?)
               (not= \? (get pattern (inc i)))) true
          :else (recur (inc i) in-class?))))))

(defn -main []
  (let [split-out   (rg "str/split|string/split")
        replace-out (rg "str/replace|string/replace")
        trim-out    (rg "str/trim|str/blank\\?|string/trim|string/blank\\?")]
    (when (or (nil? split-out) (nil? replace-out) (nil? trim-out))
      (println "REFUSED: ripgrep did not run to completion; nothing was measured.")
      (js/process.exit 2))
    (let [lines      (fn [s] (remove str/blank? (str/split-lines s)))
          split-ls   (lines split-out)
          replace-ls (lines replace-out)
          trim-ls    (lines trim-out)
          path-of    (fn [l] (first (str/split l #":")))
          hazards
          (concat
           (for [l split-ls
                 m (re-seq split-call l)
                 :when (capturing-group? (second m))]
             {:kind :split/capturing-group :file (path-of l)
              :detail (str "#\"" (second m) "\" -- the JVM drops the captures, JS String.split interleaves them")})
           (for [l replace-ls
                 m (re-seq replace-call l)
                 :let [raw (second m) r (unescape raw)]
                 :when (str/includes? r "$&")]
             {:kind :replace/dollar-amp :file (path-of l)
              :detail (str "\"" raw "\" -- $& is the whole match in JS and ordinary text on the JVM; write $0")})
           (for [l replace-ls
                 m (re-seq replace-call l)
                 :let [raw (second m) r (unescape raw)]
                 :when (and (not (str/includes? r "$&"))
                            (re-find #"\\\$" r))]
             {:kind :replace/backslash-dollar :file (path-of l)
              :detail (str "\"" raw "\" -- the JVM reads \\$ as a literal dollar; JS reads a backslash then a group")}))
          hazards (vec hazards)
          by-ext  (frequencies (map (comp ext :file) hazards))
          trim-by (frequencies (map (comp ext path-of) trim-ls))]
      ;; Evidence floor: what was actually walked. A run that matched nothing
      ;; because ripgrep found no files must not read as a clean run.
      (println (str "SCANNED\t" (+ (count split-ls) (count replace-ls))
                    "\tsplit/replace call-site lines across " (str/join " " scan-dirs)))
      (if findings-mode?
        (do
          (doseq [{:keys [kind file detail]} hazards]
            (println (str "FINDING\twarn\t" file ":" (name kind) "\t" file " " detail)))
          (println (str "FINDING\tinfo\ttrim-population\t"
                        (get trim-by "cljc" 0) " .cljc and " (get trim-by "cljs" 0)
                        " .cljs trim/blank? lines could answer differently on"
                        " U+00A0/2007/202F and U+001C-001F -- data-dependent, not"
                        " decidable from source, and reported so the number is not"
                        " mistaken for zero")))
        (do
          (println)
          (if (seq hazards)
            (doseq [{:keys [kind file detail]} (sort-by :file hazards)]
              (println (str "  " (name kind) "  " file "\n      " detail)))
            (println "  no call site would change its answer under kotoba.lang.text"))
          (println)
          (println (str "  by extension: " by-ext
                        "   (.clj is unaffected -- kotoba.lang.text adopts the JVM's answer)"))
          (println (str "  trim/blank? population that is data-dependent: "
                        (get trim-by "cljc" 0) " .cljc, " (get trim-by "cljs" 0) " .cljs lines"))))
      (js/process.exit (if (seq hazards) 1 0)))))

(-main)

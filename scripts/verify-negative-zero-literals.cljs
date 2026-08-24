#!/usr/bin/env nbb
;; verify-negative-zero-literals.cljs -- `-0.0` written as a literal in
;; ClojureScript-reachable source.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-negative-zero-literals.cljs \
;;     [--findings] [--root <dir>] [--repo orgs/<org>/<name>]
;;
;; ## What it is about
;;
;; Measured 2026-08-24 across three nbb versions, the same expression each time:
;;
;;     ((fn [] (/ 1.0 -0.0)))    1.4.208 ##-Inf   1.4.210 ##-Inf   1.5.212 ##Inf
;;
;; A `-0.0` LITERAL inside a function body reads back as +0.0 under SCI on
;; nbb 1.5.212. The same literal at the top level, behind a top-level `def`,
;; or COMPUTED with `(- 0.0)` or `(* -1.0 0.0)` keeps its sign on every
;; version tested. It is a reader/analyzer regression between 1.4.210 and
;; 1.5.212 -- not a permanent property of the runtime, and not something the
;; JVM has ever got wrong.
;;
;; It had two live victims the day it was found, in two repositories that had
;; no idea they shared a bug:
;;
;;   kotoba-lang/io-ipld                  test/ipld/value_test.cljc
;;   kotoba-lang/kotobase-engine-contract test/kotobase/engine/canonical_test.cljc
;;
;; Both wrote `#(f -0.0)` to assert a codec REJECTS negative zero. On current
;; nbb the thunk passed +0.0, so the assertion tested nothing it meant to.
;; Neither codec was wrong. Both suites were green on the version their gate
;; pins, and red on the version a person gets from `npm i nbb`.
;;
;; ## Why every code-position occurrence, not only the ones in function bodies
;;
;; A top-level `(def negative-zero -0.0)` reads correctly today, and stops
;; doing so the moment someone inlines it into a function -- an edit that
;; looks like a refactor, not a value change. The portable form `(- 0.0)`
;; costs nothing, so there is no reason to carve an exception people have to
;; remember. Findings say which case they are, so a reader can tell a live
;; defect from a latent one without opening the file.
;;
;; This is the same shape as `(int c)` over a string (root ADR-2608730000):
;; a portable-looking literal that means something else on the runtime nobody
;; ran.
;;
;; ## What it does NOT see
;;
;; `.cljc` and `.cljs` only -- a `.clj` file is JVM-only, where the literal is
;; correct. And it does not see the OTHER 1.4.210->1.5.212 regression found the
;; same day (amu's `ir/lower` overflowing the SCI stack on a 243-byte fixture),
;; which is not a source shape and cannot be grepped for. This closes one
;; class; it does not answer the version-divergence question.
;;
;; A path containing a newline is not scanned and is counted as unreadable --
;; `git ls-files` is read line by line here. None exist in this workspace
;; today, and one appearing would show up in the UNREADABLE count rather than
;; being silently dropped.
;;
;; ## exit codes
;;
;;   0  scanned, and no `-0.0` literal sits in a code position
;;   1  at least one does
;;   2  COULD NOT ANSWER -- the self-check failed, or zero files were scanned.
;;      Distinct from 0 on purpose: a run that could not look must not report
;;      the same value as a run that looked and found nothing.

(require '["node:fs" :as fs]
         '["node:path" :as path]
         '["node:child_process" :as cp]
         '[clojure.string :as str])

(def argv (vec (drop 2 js/process.argv)))
(defn- flag? [f] (some #{f} argv))
(defn- opt [f] (second (drop-while #(not= f %) argv)))
(def findings? (flag? "--findings"))
(def root (or (opt "--root") (.cwd js/process)))
(def one-repo (opt "--repo"))

;; ---------------------------------------------------------------------------
;; Reader state, not a regex.
;;
;; `-0.0` appears in docstrings and `;;` comments all over this workspace --
;; io-ipld's own `value.cljc` explains the rule in four of them -- and a regex
;; counts every one. The scanner also has to know that a backslash-semicolon
;; is a character literal and not the start of a comment, and that an escaped
;; quote inside a string does not end it.
;; ---------------------------------------------------------------------------

(def ^:private boundary
  "What may sit either side of the literal. Anything else means it is part of
   a longer token -- `-0.05`, `x-0.0`, `:a-0.0` -- and is not this."
  #{" " "\t" "\n" "\r" "(" ")" "[" "]" "{" "}" "'" "`" "," ";" "@" "^" nil})

(defn- at [s i] (when (and (>= i 0) (< i (count s))) (subs s i (inc i))))

(defn- negative-zero-at?
  "True when a `-0.0`-valued numeric literal starts at `i`: minus, zero,
   point, then only zeros, then a token boundary. `-0.` and `-0.000` are the
   same value and the same defect."
  [s i]
  (and (= "-" (at s i))
       (= "0" (at s (+ i 1)))
       (= "." (at s (+ i 2)))
       (boundary (at s (dec i)))
       (loop [j (+ i 3)]
         (let [c (at s j)]
           (cond (= c "0") (recur (inc j))
                 (boundary c) true
                 :else false)))))

(defn- scan
  "Every code-position `-0.0` in `text`, as `{:line n :depth d}`.

   `:depth` is the paren nesting at that point. Depth is not the same question
   as `inside a function body`, but it separates the two cases that matter in
   practice: a `-0.0` at depth 1 is a top-level form's argument -- usually a
   `def` -- and one deeper than that is inside something that will be called."
  [text]
  (let [n (count text)]
    (loop [i 0 line 1 depth 0 in-string? false in-comment? false hits []]
      (if (>= i n)
        hits
        (let [c (at text i)]
          (cond
            (= c "\n") (recur (inc i) (inc line) depth in-string? false hits)

            in-comment? (recur (inc i) line depth in-string? true hits)

            in-string?
            (if (= c "\\")
              (recur (+ i 2) line depth true false hits)
              (recur (inc i) line depth (not= c "\"") false hits))

            ;; A character literal. Skip the backslash and what it names, or a
            ;; backslash-semicolon starts a comment and the rest of the line
            ;; vanishes from the scan.
            (= c "\\") (recur (+ i 2) line depth false false hits)

            (= c "\"") (recur (inc i) line depth true false hits)
            (= c ";") (recur (inc i) line depth false true hits)
            (#{"(" "[" "{"} c) (recur (inc i) line (inc depth) false false hits)
            (#{")" "]" "}"} c) (recur (inc i) line (max 0 (dec depth)) false false hits)

            (negative-zero-at? text i)
            (recur (inc i) line depth false false
                   (conj hits {:line line :depth depth}))

            :else (recur (inc i) line depth false false hits)))))))

;; ---------------------------------------------------------------------------
;; Self-check. The first scanner written here called `subs` with one index,
;; which throws -- and a detector that dies before printing SCANNED looks
;; exactly like one that found nothing, to a caller reading only the exit
;; code. So it proves it can say yes AND no before it says anything about the
;; workspace.
;; ---------------------------------------------------------------------------

(def ^:private self-check-cases
  [{:name "bare literal in a call"      :text "(defn f [] (g -0.0))"       :expect 1}
   {:name "top-level def"               :text "(def z -0.0)"               :expect 1}
   {:name "inside a line comment"       :text ";; -0.0 rejected\n(f 1)"    :expect 0}
   {:name "inside a docstring"          :text "(defn f \"see -0.0\" [] 1)" :expect 0}
   {:name "escaped quote in a string"   :text "(def s \"a \\\" -0.0 b\")"  :expect 0}
   {:name "character-literal semicolon" :text (str "(def c " (char 92) "; ) (f -0.0)") :expect 1}
   {:name "longer token -0.05"          :text "(f -0.05)"                  :expect 0}
   {:name "identifier ending in -0.0"   :text "(f x-0.0)"                  :expect 0}
   {:name "computed, the correct form"  :text "(f (- 0.0))"                :expect 0}
   {:name "-0. and -0.000 are it too"   :text "(f -0.) (g -0.000)"         :expect 2}])

(defn- self-check! []
  (let [bad (keep (fn [{:keys [name text expect]}]
                    (let [got (count (scan text))]
                      (when (not= got expect)
                        (str name ": expected " expect ", got " got))))
                  self-check-cases)]
    (when (seq bad)
      (println "SELF-CHECK FAILED -- refusing to report on the workspace:")
      (doseq [b bad] (println "  " b))
      (js/process.exit 2))
    (count self-check-cases)))

;; ---------------------------------------------------------------------------

(defn- sh [cmd cwd]
  (try (str (cp/execSync cmd #js {:cwd cwd :encoding "utf8"
                                  :stdio #js ["ignore" "pipe" "ignore"]
                                  :maxBuffer (* 64 1024 1024)}))
       (catch :default _ nil)))

(defn- dir? [p] (try (.isDirectory (fs/statSync p)) (catch :default _ false)))
(defn- git-repo? [p]
  (let [g (path/join p ".git")]
    (or (dir? g) (try (.isFile (fs/statSync g)) (catch :default _ false)))))

(defn- checkouts []
  (if one-repo
    [(path/join root one-repo)]
    (let [orgs (path/join root "orgs")]
      (->> (try (vec (fs/readdirSync orgs)) (catch :default _ []))
           (mapcat (fn [org]
                     (let [d (path/join orgs org)]
                       (->> (try (vec (fs/readdirSync d)) (catch :default _ []))
                            (map #(path/join d %))))))
           (filter git-repo?)
           vec))))

(defn- rel [p] (str/replace (str p) (str root "/") ""))

(defn- finding! [severity k detail]
  (println (str "FINDING\t" severity "\t" k "\t" detail)))

(let [checked (self-check!)
      dirs (checkouts)
      _ (when (zero? (count dirs))
          (println "SCANNED\t0\tno checkouts under orgs/")
          (println "Refusing to report a pass: found no repository to look at.")
          (js/process.exit 2))
      results
      (vec (for [d dirs
                 :let [listing (sh "git ls-files '*.cljc' '*.cljs'" d)]
                 :when listing
                 f (remove str/blank? (str/split-lines listing))
                 :let [p (path/join d f)
                       text (try (str (fs/readFileSync p "utf8"))
                                 (catch :default _ nil))]]
             (if (nil? text)
               {:file p :unreadable true}
               {:file p :hits (scan text)})))
      unreadable (filterv :unreadable results)
      scanned (- (count results) (count unreadable))
      hits (vec (for [{:keys [file hits]} results, h hits] (assoc h :file file)))]
  (println (str "SCANNED\t" scanned "\ttracked .cljc/.cljs across "
                (count dirs) " checkout(s); self-check " checked "/"
                (count self-check-cases) " cases"))
  (when (pos? (count unreadable))
    (println (str "UNREADABLE\t" (count unreadable)
                  "\tfiles git listed but could not be read")))
  (when (zero? scanned)
    (println "Refusing to report a pass: scanned 0 files.")
    (js/process.exit 2))
  (if (empty? hits)
    (do (println "OK -- no `-0.0` literal sits in a code position.")
        (js/process.exit 0))
    (do
      (when findings?
        (doseq [{:keys [file line depth]} (sort-by (juxt :file :line) hits)]
          (finding!
           "fail"
           (str "negative-zero-literal:" (rel file) ":" line)
           (str "`-0.0` literal at depth " depth
                (if (<= depth 1)
                  " (top level -- correct today, and silently wrong the moment it is inlined into a function)"
                  " (inside a called form -- reads as +0.0 on nbb 1.5.212)")
                ". Write `(- 0.0)`."))))
      (println (str (count hits) " `-0.0` literal(s) in code position across "
                    (count (distinct (map :file hits))) " file(s)."))
      (js/process.exit 1))))

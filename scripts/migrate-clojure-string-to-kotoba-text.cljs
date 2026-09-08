#!/usr/bin/env nbb
(ns migrate-clojure-string-to-kotoba-text
  "Rewrite one repo's `.clj`/`.cljc` from clojure.string to kotoba.lang.text.

  ## What it will and will not touch

  `.cljs` IS LEFT ALONE. kotoba.lang.text adopts the JVM's answer for the three
  places clojure.string's two host implementations disagree, so a `.clj` file
  cannot change behaviour and a `.cljc` file's two halves stop disagreeing --
  but a `.cljs` file's answer would move from goog.string's whitespace class to
  Java's, and that is a decision about DATA, not about source. It is deferred.

  A file is REFUSED, and the whole repo with it, when it contains one of the
  three forms that would change its answer:

    split with a capturing group   the JVM drops the captures; JS interleaves
    replace with `$&`              the whole match in JS; text on the JVM
    replace with `\\$`             an escaped dollar on the JVM; not in JS

  scripts/verify-string-migration-hazards.cljs is the workspace-wide census of
  those (15 files, measured 2026-09-08); this is the same test applied per
  repo, so a repo that gains one later is refused rather than half-migrated.

  ## The rewrite

    [clojure.string :as X]  ->  [kotoba.lang.text :as X]
    clojure.string/f        ->  X/f  when an alias exists, else refused
    X/lower-case            ->  X/lower
    X/upper-case            ->  X/upper

  and `io.github.kotoba-lang/text` is added to `:deps` as an explicit floor,
  with the reason beside it -- tools.deps takes the newest sha it is shown, so
  a repo without a floor rides whatever a sibling happens to name.

  Nothing else. `escape`, `index-of`, `last-index-of`, `blank?`, `join`,
  `split`, `trim`, `starts-with?` and the rest keep their names.

  ## What it does NOT claim

  It re-reads every file it wrote and proves, by applying the inverse
  substitution and comparing, that NOTHING BUT THE SUBSTITUTIONS MOVED. That is
  what stands in for running 3,245 test suites: it does not show the code is
  correct, it shows this tool did not touch anything else, which is the failure
  a bulk rewrite actually has.

  It does not run the repo's tests. `--apply` prints REWROTE, never PASSED.
  Running the suite is the caller's job and the caller must say so separately.

    nbb --classpath \".:scripts/nbb_compat\" scripts/migrate-clojure-string-to-kotoba-text.cljs \\
        --repo <dir> --text-sha <40-hex> [--apply]"
  (:require [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def path-mod (js/require "node:path"))

(def argv (vec (drop 2 (js->clj js/process.argv))))
(defn- opt [flag] (second (drop-while #(not= flag %) argv)))
(def repo (opt "--repo"))
(def text-sha (opt "--text-sha"))
(def apply? (some #{"--apply"} argv))

(defn- refuse! [msg]
  (println (str "REFUSED: " msg))
  (js/process.exit 2))

(defn- walk-files [dir]
  (letfn [(go [d acc]
            (reduce (fn [acc e]
                      (let [p (.join path-mod d (.-name e))]
                        (cond
                          (.isDirectory e)
                          (if (#{"node_modules" ".git" "target" "out" "dist" ".cpcache"} (.-name e))
                            acc (go p acc))
                          (re-find #"\.cljc?$" (.-name e)) (conj acc p)
                          :else acc)))
                    acc
                    (try (.readdirSync fs d #js {:withFileTypes true}) (catch :default _ []))))]
    (go dir [])))

;; ---------------------------------------------------------------------------
;; hazards -- the same three forms the workspace census counts
;; ---------------------------------------------------------------------------

(defn- capturing-group? [pattern]
  (loop [i 0, in-class? false]
    (if (>= i (count pattern))
      false
      (let [c (nth pattern i)]
        (cond
          (= c \\) (recur (+ i 2) in-class?)
          (= c \[) (recur (inc i) true)
          (= c \]) (recur (inc i) false)
          (and (= c \() (not in-class?) (not= \? (get pattern (inc i)))) true
          :else (recur (inc i) in-class?))))))

(defn- unescape [s]
  (str/replace s #"\\(.)" (fn [m] (let [c (second m)]
                                    (case c "n" "\n" "t" "\t" "r" "\r" "\"" "\"" "\\" "\\" c)))))

(defn- hazards-in [alias src]
  (let [a (js/RegExp. (str "(?:" alias "|clojure\\.string)"))
        split-call   (re-pattern (str "(?:" alias "|clojure\\.string)/split\\s+(?:[^\\s()]+|\\([^()]{0,80}\\))\\s+#\"((?:[^\"\\\\]|\\\\.)*)\""))
        replace-call (re-pattern (str "(?:" alias "|clojure\\.string)/replace(?:-first)?\\s+(?:[^\\s()]+|\\([^()]{0,80}\\))\\s+#\"(?:[^\"\\\\]|\\\\.)*\"\\s+\"((?:[^\"\\\\]|\\\\.)*)\""))]
    (concat
     (for [m (re-seq split-call src) :when (capturing-group? (second m))]
       (str "split with a capturing group: #\"" (second m) "\""))
     (for [m (re-seq replace-call src)
           :let [r (unescape (second m))]
           :when (or (str/includes? r "$&") (re-find #"\\\$" r))]
       (str "replace with a host-divergent template: \"" (second m) "\"")))))

;; ---------------------------------------------------------------------------

(defn- alias-of [src]
  (second (re-find #"\[clojure\.string :as ([a-zA-Z0-9*+!?<>=_-]+)\]" src)))

(defn- rewrite [alias src]
  (-> src
      (str/replace (re-pattern (str "\\[clojure\\.string :as " alias "\\]"))
                   (str "[kotoba.lang.text :as " alias "]"))
      (str/replace (re-pattern (str "\\b" alias "/lower-case")) (str alias "/lower"))
      (str/replace (re-pattern (str "\\b" alias "/upper-case")) (str alias "/upper"))
      (str/replace #"\bclojure\.string/lower-case" (str alias "/lower"))
      (str/replace #"\bclojure\.string/upper-case" (str alias "/upper"))
      (str/replace #"\bclojure\.string/" (str alias "/"))))

(defn- unrewrite
  "The inverse of `rewrite`, as far as the substitution set goes."
  [alias src]
  (-> src
      (str/replace (re-pattern (str "\\[kotoba\\.lang\\.text :as " alias "\\]"))
                   (str "[clojure.string :as " alias "]"))
      (str/replace (re-pattern (str "\\b" alias "/lower\\b")) (str alias "/lower-case"))
      (str/replace (re-pattern (str "\\b" alias "/upper\\b")) (str alias "/upper-case"))))

(defn- substitution-only?
  "Proof that the rewrite moved NOTHING but the substitutions.

  Applying the inverse to the result has to give back the original, modulo the
  fully-qualified `clojure.string/f` -> `alias/f` routing, which has no inverse
  (both spellings map to one). So the comparison is made after collapsing that
  form on the ORIGINAL side too.

  This is what stands in for running 3,245 test suites. It does not prove the
  code is correct -- it proves this tool did not touch anything else, which is
  the failure a bulk rewrite actually has. A regex that matched one character
  too many somewhere in 13,000 files would show up here and nowhere else until
  something broke in production."
  [alias before after]
  (let [collapse (fn [s] (-> s
                             (str/replace #"\bclojure\.string/lower-case" (str alias "/lower-case"))
                             (str/replace #"\bclojure\.string/upper-case" (str alias "/upper-case"))
                             (str/replace #"\bclojure\.string/" (str alias "/"))))]
    (= (collapse before) (unrewrite alias after))))

(def dep-note
  ["        ;; kotoba.lang.text, not clojure.string. Pinned as an explicit floor:"
   "        ;; tools.deps takes the NEWEST sha it is shown, so without a floor here"
   "        ;; this repo silently rides whatever a sibling happens to name."])

(defn- add-dep [deps-path]
  (let [s (.readFileSync fs deps-path "utf8")]
    (cond
      (str/includes? s "io.github.kotoba-lang/text ")
      [:already s]

      ;; No :deps key at all -- common in these repos, which declare only
      ;; :paths and :aliases. Insert one rather than rewriting the source and
      ;; leaving the namespace unresolvable, which is what the first version
      ;; did: it reported `no-deps-key` and carried on.
      (not (str/includes? s ":deps"))
      (let [m (re-find #"\{:paths \[[^\]]*\]" s)]
        (if-not m
          [:refused s]
          (let [i (+ (.indexOf s m) (count m))
                entry (str "\n :deps {\n" (str/join "\n" dep-note)
                           "\n        io.github.kotoba-lang/text {:git/sha \"" text-sha "\"}}")]
            [:inserted (str (subs s 0 i) entry (subs s i))])))

      :else
      (let [i (+ (.indexOf s ":deps {") (count ":deps {"))
            entry (str "\n" (str/join "\n" dep-note)
                       "\n        io.github.kotoba-lang/text {:git/sha \"" text-sha "\"}\n       ")]
        [:added (str (subs s 0 i) entry (str/triml (subs s i)))]))))

(defn -main []
  (when-not repo (refuse! "--repo is required"))
  (when-not (and text-sha (re-matches #"[0-9a-f]{40}" text-sha))
    (refuse! "--text-sha must be a 40-hex sha"))
  (when-not (try (.isDirectory (.statSync fs repo)) (catch :default _ false))
    (refuse! (str "not a directory: " repo)))

  (let [files    (walk-files (.join path-mod repo "src"))
        files    (into files (walk-files (.join path-mod repo "test")))
        _        (when (empty? files)
                   (refuse! (str "no .clj/.cljc under " repo "/src or /test; nothing was measured")))
        loaded   (for [p files] [p (.readFileSync fs p "utf8")])
        targets  (filterv (fn [[_ s]] (str/includes? s "clojure.string")) loaded)
        haz      (for [[p s] targets
                       :let [a (alias-of s)]
                       :when a
                       h (hazards-in a s)]
                   [p h])
        no-alias (filterv (fn [[_ s]] (and (str/includes? s "clojure.string")
                                           (nil? (alias-of s)))) targets)]
    (println (str "SCANNED\t" (count files) "\t.clj/.cljc files under " repo))
    (println (str "TARGETS\t" (count targets) "\tfiles mention clojure.string"))
    (cond
      (empty? targets)
      (do (println "nothing to do") (js/process.exit 0))

      (seq haz)
      (do (doseq [[p h] haz] (println (str "HAZARD\t" p "\t" h)))
          (refuse! (str (count haz) " hazardous call site(s); this repo is not"
                        " safe to rewrite mechanically -- fix the template or"
                        " the pattern first")))

      (seq no-alias)
      (do (doseq [[p _] no-alias] (println (str "NO-ALIAS\t" p)))
          (refuse! (str (count no-alias) " file(s) name clojure.string without an"
                        " `:as` alias; the rewrite has no name to route through")))

      :else
      (let [changed (for [[p s] targets
                          :let [a (alias-of s), s' (rewrite a s)]
                          :when (not= s s')]
                      [p s'])]
        (if-not apply?
          (do (doseq [[p _] changed] (println (str "WOULD-REWRITE\t" p)))
              (println (str "PLAN\t" (count changed) "\tfiles (dry run; pass --apply)"))
              (js/process.exit 0))
          (do
            (doseq [[p s'] changed] (.writeFileSync fs p s' "utf8"))
            ;; read back what was written; a rewrite that cannot be re-read is
            ;; the failure mode this whole workspace keeps rediscovering
            (let [orig (into {} loaded)
                  bad (for [[p _] changed
                            :let [back  (.readFileSync fs p "utf8")
                                  a     (alias-of (get orig p))
                                  fail  (cond
                                          (str/blank? back) "empty after write"
                                          (str/includes? back "clojure.string") "still names clojure.string"
                                          (not (substitution-only? a (get orig p) back))
                                          "changed something other than the substitutions"
                                          :else nil)]
                            :when fail]
                        [p fail])]
              (when (seq bad)
                (doseq [[p why] bad] (println (str "READBACK-FAILED\t" p "\t" why)))
                (refuse! "a rewritten file did not survive the read-back invariant")))
            (let [dp (.join path-mod repo "deps.edn")]
              (when (try (.isFile (.statSync fs dp)) (catch :default _ false))
                (let [[status s'] (add-dep dp)]
                  (when (#{:added :inserted} status) (.writeFileSync fs dp s' "utf8"))
                  (println (str "DEPS\t" (name status)))
                  (when (= :refused status)
                    (refuse! (str dp " has neither a :deps key nor a :paths vector to"
                                  " insert one after; the rewritten source would not"
                                  " resolve kotoba.lang.text"))))))
            (doseq [[p _] changed] (println (str "REWROTE\t" p)))
            (println (str "REWROTE\t" (count changed) "\tfiles -- NOT verified;"
                          " run this repo's tests and say so separately"))
            (js/process.exit 0)))))))

(-main)

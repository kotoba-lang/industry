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
(def include-cljs?
  "Whether to rewrite `.cljs` too.

  Off by default, and it stayed off for the whole .clj/.cljc migration, because
  kotoba.lang.text adopts the JVM's whitespace class for `trim` and a .cljs
  file's answer would move from goog.string's. That is a decision about DATA --
  whether the strings being trimmed contain U+00A0, U+2007, U+202F (which JS
  trims and Java does not) or U+001C-001F (the reverse) -- and it is not
  decidable from source.

  What settles it is that the .cljc migration ALREADY made that change on the
  ClojureScript half of 10,115 lines. Leaving .cljs behind does not avoid the
  decision; it applies it inconsistently. The remaining 4,882 lines get the
  same answer the rest of the workspace already has."
  (some #{"--include-cljs"} argv))

(defn- refuse! [msg]
  (println (str "REFUSED: " msg))
  (js/process.exit 2))

(defn- walk-files [dir]
  (letfn [(go [d acc]
            (reduce (fn [acc e]
                      (let [p (.join path-mod d (.-name e))]
                        (cond
                          (.isDirectory e)
                          (if (#{"node_modules" ".git" "target" "out" "dist" ".cpcache"
                                 ".shadow-cljs" ".clj-kondo" "vendor" ".calva"} (.-name e))
                            acc (go p acc))
                          (if include-cljs?
                            (re-find #"\.cljs?c?$" (.-name e))
                            (re-find #"\.cljc?$" (.-name e)))
                          (conj acc p)
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

(defn- requires-clojure-string?
  "True when the file's ns form actually REQUIRES clojure.string, in any of the
  three shapes that appear here: `[clojure.string :as x]`, `[clojure.string]`,
  or a bare `clojure.string` symbol inside `(:require ...)`.

  A file that calls `clojure.string/f` WITHOUT requiring it -- 337 of the 485
  no-alias files, measured 2026-09-08 -- works only because something else on
  the classpath loaded the namespace first. Renaming its calls would break it,
  so those are still refused: they need a require inserted, which is ns-form
  surgery and a different edit from a rename."
  [src]
  (boolean (or (re-find #"\[clojure\.string[\s\]]" src)
               (re-find #"(?s)\(:require[^)]*[\s\[]clojure\.string[\s\)\]]" src))))

(def ^:private inserted-libspec
  "What is inserted into an ns form that CALLS clojure.string without requiring
  it. Written as one exact string so the inverse can remove it by equality
  rather than by pattern -- the substitution-only invariant has to be able to
  undo an insertion as precisely as it undoes a rename."
  "(:require [kotoba.lang.text]")

(defn- ns-close-index
  "Index of the `)` that closes the `(ns ...)` form, or nil.

  Balanced-paren scan, skipping parens inside strings, character literals and
  `;` comments -- a docstring containing a paren is the ordinary case here, not
  an exotic one, and counting it would close the ns form in the wrong place."
  [src]
  (let [start (.indexOf src "(ns ")]
    (when (>= start 0)
      (let [n (count src)]
        (loop [i (inc start), depth 1, in-str? false, esc? false, in-cmt? false]
          (cond
            (>= i n) nil
            :else
            (let [c (nth src i)]
              (cond
                in-cmt?  (recur (inc i) depth false false (not= c \newline))
                esc?     (recur (inc i) depth in-str? false false)
                (and in-str? (= c \\)) (recur (inc i) depth true true false)
                in-str?  (recur (inc i) depth (not= c \") false false)
                (= c \") (recur (inc i) depth true false false)
                (= c \;) (recur (inc i) depth false false true)
                ;; a character literal: the next char is data, whatever it is
                (= c \\) (recur (+ i 2) depth false false false)
                (= c \() (recur (inc i) (inc depth) false false false)
                (= c \)) (if (= depth 1) i (recur (inc i) (dec depth) false false false))
                :else (recur (inc i) depth false false false)))))))))

(def ^:private created-require
  "What is added to an ns form that has no `:require` at all. One exact
  literal, for the same reason `inserted-libspec` is: the inverse removes it by
  equality."
  "\n  (:require [kotoba.lang.text])")

(defn- ns-require-index
  "Index of the ns form's own `(:require`, or nil.

  Anchored on the `(ns ` form, not on the first `(:require` in the file: a
  nested `(require ...)` in a comment or a body would otherwise be treated as
  the ns's, and the libspec would land somewhere that is not a require at all."
  [src]
  (let [ns-at (.indexOf src "(ns ")]
    (when (>= ns-at 0)
      (let [at (.indexOf src "(:require" ns-at)]
        (when (>= at 0) at)))))

(def ^:private inserted-toplevel
  "What is inserted into a file that has NO `(ns ...)` form but does open with a
  top-level `(require ...)`. Nine files in the workspace are shaped that way --
  scripts and a `clojure -i` test file -- and the tool refused all three of
  their repos wholesale because it looked only inside an ns form and concluded
  there was nowhere to put a require. There was: the same place the file
  already keeps its requires."
  "(require '[kotoba.lang.text]")

(defn- toplevel-require-index
  "Index of a top-level `(require ...)`, or nil.

  Anchored on column zero. A top-level form in these files begins there, and
  requiring that is what keeps a `(require` inside a string, a comment or a
  function body from being mistaken for the file's own require list -- the same
  reason `ns-require-index` anchors on the `(ns ` form rather than on the first
  `(:require` it can find."
  [src]
  (let [m (re-find #"(?m)^\(require\b" src)]
    (when m
      (let [i (.indexOf src m)]
        (when (>= i 0) i)))))

(defn- rewrite
  "With an alias, the namespace in the require is renamed and the alias is kept.
  Without one, the NAMESPACE TOKEN itself is renamed everywhere -- which is the
  same edit, spelled without a nickname, and covers `[clojure.string]` and a
  bare `clojure.string` symbol in the require."
  [alias src]
  (if-not alias
    (let [renamed (-> src
                      (str/replace #"\bclojure\.string/lower-case" "kotoba.lang.text/lower")
                      (str/replace #"\bclojure\.string/upper-case" "kotoba.lang.text/upper")
                      (str/replace #"\bclojure\.string\b" "kotoba.lang.text"))]
      ;; If the file only CALLED clojure.string and never required it, renaming
      ;; the calls is not enough: it worked because something else on the
      ;; classpath had loaded the namespace, and kotoba.lang.text may not be
      ;; loaded by anything. Insert the libspec.
      (if (or (str/includes? src "[clojure.string")
              (re-find #"(?s)\(:require[^)]*[\s\[]clojure\.string[\s\)\]]" src))
        renamed
        (if-let [i (ns-require-index renamed)]
          (str (subs renamed 0 i) inserted-libspec
               (subs renamed (+ i (count "(:require"))))
          ;; No `:require` in the ns form at all -- create one just inside its
          ;; closing paren. 41 files, measured 2026-09-09.
          (if-let [close (ns-close-index renamed)]
            (str (subs renamed 0 close) created-require (subs renamed close))
            ;; No ns form either. If the file keeps its requires in a top-level
            ;; `(require ...)`, that is its require list and the libspec goes
            ;; there. 9 files, measured 2026-09-09.
            (if-let [t (toplevel-require-index renamed)]
              (str (subs renamed 0 t) inserted-toplevel
                   (subs renamed (+ t (count "(require"))))
              renamed)))))
    (-> src
      (str/replace (re-pattern (str "\\[clojure\\.string :as " alias "\\]"))
                   (str "[kotoba.lang.text :as " alias "]"))
      (str/replace (re-pattern (str "\\b" alias "/lower-case")) (str alias "/lower"))
      (str/replace (re-pattern (str "\\b" alias "/upper-case")) (str alias "/upper"))
      (str/replace #"\bclojure\.string/lower-case" (str alias "/lower"))
      (str/replace #"\bclojure\.string/upper-case" (str alias "/upper"))
      (str/replace #"\bclojure\.string/" (str alias "/")))))

(defn- unrewrite
  "The inverse of `rewrite`, as far as the substitution set goes.

  `inserted?` matters. `inserted-libspec` is the literal
  `(:require [kotoba.lang.text]` -- which is ALSO exactly what renaming a file
  whose ns already said `(:require [clojure.string]` produces. Stripping it
  unconditionally turned that legitimate rename back into a bare `(:require`,
  the round trip did not match, and the read-back refused a correct rewrite:
  control-plane, 146 files, all of them held back by one. So the inverse only
  undoes an insertion when there was one."
  [alias inserted? src]
  (if-not alias
    (-> (if inserted?
          (-> src
              (str/replace created-require "")
              (str/replace inserted-libspec "(:require")
              (str/replace inserted-toplevel "(require"))
          src)
        (str/replace #"\bkotoba\.lang\.text/lower\b" "clojure.string/lower-case")
        (str/replace #"\bkotoba\.lang\.text/upper\b" "clojure.string/upper-case")
        (str/replace #"\bkotoba\.lang\.text\b" "clojure.string"))
    (-> src
      (str/replace (re-pattern (str "\\[kotoba\\.lang\\.text :as " alias "\\]"))
                   (str "[clojure.string :as " alias "]"))
      (str/replace (re-pattern (str "\\b" alias "/lower\\b")) (str alias "/lower-case"))
      (str/replace (re-pattern (str "\\b" alias "/upper\\b")) (str alias "/upper-case")))))

(defn- insertion-landed-inside-ns?
  "Where an insertion went, which `substitution-only?` cannot see.

  The inverse removes `created-require`/`inserted-libspec` by equality wherever
  they are, so a libspec inserted into the WRONG place -- past the end of the
  ns form, say, because a docstring paren was miscounted -- undoes cleanly and
  the substitution invariant passes. It would still be broken code. So the
  marker's position is checked against the ns form's own closing paren in the
  WRITTEN text."
  [after]
  (let [marker (cond (str/includes? after created-require)  created-require
                     (str/includes? after inserted-libspec) inserted-libspec
                     (str/includes? after inserted-toplevel) inserted-toplevel
                     :else nil)]
    (if (= marker inserted-toplevel)
      ;; A top-level insertion has no ns form to be inside of, so what has to
      ;; hold is different: there must be no ns form at all (otherwise the
      ;; libspec belonged in it), and the marker must sit exactly where the
      ;; file's own top-level require began.
      (boolean (and (neg? (.indexOf after "(ns "))
                    (= (.indexOf after inserted-toplevel)
                       (toplevel-require-index after))))
    (if-not marker
      true
      ;; BOTH ends. An earlier version checked only `at < close`, and an
      ;; insertion at index 0 -- before the ns form entirely -- passed it: the
      ;; marker was indeed before the closing paren, just not inside anything.
      (let [start (.indexOf after "(ns ")
            at    (.indexOf after marker)
            close (ns-close-index after)]
        (boolean (and (>= start 0) close (< start at) (< at close))))))))

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
  (if-not alias
    ;; an insertion happened exactly when the original did not require it
    (let [inserted? (not (or (str/includes? before "[clojure.string")
                             (re-find #"(?s)\(:require[^)]*[\s]clojure\.string[\s\)\]]" before)))]
      (= before (unrewrite alias inserted? after)))
    (let [collapse (fn [s] (-> s
                             (str/replace #"\bclojure\.string/lower-case" (str alias "/lower-case"))
                             (str/replace #"\bclojure\.string/upper-case" (str alias "/upper-case"))
                             (str/replace #"\bclojure\.string/" (str alias "/"))))]
      (= (collapse before) (unrewrite alias false after)))))

(def dep-note
  ["        ;; kotoba.lang.text, not clojure.string. Pinned as an explicit floor:"
   "        ;; tools.deps takes the NEWEST sha it is shown, so without a floor here"
   "        ;; this repo silently rides whatever a sibling happens to name."])

(defn- top-level-key-open
  "Index just past the `{` of the OUTER map's `:deps`, or nil.

  Not `(.indexOf s \":deps {\")`. That finds the first such text anywhere,
  which in a deps.edn whose top-level `:deps` is written as

      :deps
      {org.clojure/clojure ...}

  is the one inside `:aliases {:build {:deps {...}}}`. Measured 2026-09-09 on
  cloud-itonami-app: the text dependency landed in the BUILD alias, the
  namespace was unresolvable at runtime, and the only symptom was a
  FileNotFoundException for kotoba/lang/text much later. 83 repos write
  `:deps` on its own line.

  So: scan with a depth counter and take the `:deps` that sits at depth 1,
  then the `{` that follows it."
  ([s] (top-level-key-open s ":deps" \{))
  ([s kee open]
  (let [n (count s)]
    (loop [i 0, depth 0, in-str? false, esc? false, in-cmt? false]
      (cond
        (>= i n) nil
        in-cmt? (recur (inc i) depth false false (not= (nth s i) \newline))
        esc?    (recur (inc i) depth in-str? false false)
        :else
        (let [c (nth s i)]
          (cond
            (and in-str? (= c \\)) (recur (inc i) depth true true false)
            in-str? (recur (inc i) depth (not= c \") false false)
            (= c \") (recur (inc i) depth true false false)
            (= c \;) (recur (inc i) depth false false true)
            (or (= c \{) (= c \[) (= c \()) (recur (inc i) (inc depth) false false false)
            (or (= c \}) (= c \]) (= c \))) (recur (inc i) (dec depth) false false false)
            (and (= depth 1) (= c \:) (= kee (subs s i (min n (+ i (count kee))))))
            ;; found it -- now the opening delimiter of its value
            (loop [j (+ i (count kee))]
              (cond (>= j n) nil
                    (= (nth s j) open) (inc j)
                    (or (= (nth s j) \space) (= (nth s j) \newline)
                        (= (nth s j) \tab) (= (nth s j) \return)) (recur (inc j))
                    :else nil))
            :else (recur (inc i) depth false false false))))))))

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
      ;; Find `:paths` the same way, by depth. The old `#"\{:paths \["` required
      ;; it to be the very first key with no comment before it -- and a bb.edn
      ;; that opens with a two-line comment then `:paths` was reported as having
      ;; neither key, which held 218 files in etzhayyim/root.
      (if-let [po (top-level-key-open s ":paths" \[)]
        (let [close (loop [j po] (cond (>= j (count s)) nil
                                       (= (nth s j) \]) (inc j)
                                       :else (recur (inc j))))
              entry (str "\n :deps {\n" (str/join "\n" dep-note)
                         "\n        io.github.kotoba-lang/text {:git/sha \"" text-sha "\"}}")]
          (if close [:inserted (str (subs s 0 close) entry (subs s close))] [:refused s]))
        [:refused s])

      :else
      (if-let [i (top-level-key-open s)]
        (let [entry (str "\n" (str/join "\n" dep-note)
                         "\n        io.github.kotoba-lang/text {:git/sha \"" text-sha "\"}\n       ")]
          [:added (str (subs s 0 i) entry (str/triml (subs s i)))])
        [:refused s]))))

(defn -main []
  (when-not repo (refuse! "--repo is required"))
  (when-not (and text-sha (re-matches #"[0-9a-f]{40}" text-sha))
    (refuse! "--text-sha must be a 40-hex sha"))
  (when-not (try (.isDirectory (.statSync fs repo)) (catch :default _ false))
    (refuse! (str "not a directory: " repo)))

  ;; The whole repo, not just src/ and test/. Measured 2026-09-08: app-animeka
  ;; keeps its .cljc under lg/, and an earlier version that walked only src and
  ;; test reported "nothing was measured" for it -- a repo with code the tool
  ;; could not see reads exactly like a repo with nothing to do.
  (let [files    (walk-files repo)
        _        (when (empty? files)
                   (refuse! (str "no .clj/.cljc anywhere under " repo "; nothing was measured")))
        loaded   (for [p files] [p (.readFileSync fs p "utf8")])
        targets  (filterv (fn [[_ s]] (str/includes? s "clojure.string")) loaded)
        haz      (for [[p s] targets
                       :let [a (alias-of s)]
                       h (hazards-in (or a "kotoba\\.lang\\.text") s)]
                   [p h])
        no-alias (filterv (fn [[_ s]] (and (str/includes? s "clojure.string")
                                           (nil? (alias-of s))
                                           (not (requires-clojure-string? s))
                                           (re-find #"\bclojure\.string/" s)
                                           (nil? (ns-require-index s))
                                           (nil? (ns-close-index s))
                                           (nil? (toplevel-require-index s))))
                          targets)]
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
          (refuse! (str (count no-alias) " file(s) CALL clojure.string without"
                        " requiring it, and have nowhere to declare it: no"
                        " `(:require` in their ns form, no ns form to add one"
                        " to, and no top-level `(require ...)` either --"
                        " measured 2026-09-09, all scripts. Where a file DOES"
                        " keep a top-level `(require ...)`, the libspec now goes"
                        " there; these are the ones that keep no require list at"
                        " all, so a new one would have to be placed relative to"
                        " first use -- a judgement this tool does not make")))

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
                                          ;; A MENTION is not a dependency. kagi/b64.cljc
                                          ;; explains in a comment why the require sits
                                          ;; inside a reader conditional, and the word
                                          ;; `clojure.string` in that sentence made the
                                          ;; readback refuse a correct rewrite -- 9 repos
                                          ;; in one wave. Only a libspec or a qualified
                                          ;; call counts.
                                          (or (str/includes? back "[clojure.string")
                                              (str/includes? back "clojure.string/")
                                              (re-find #"(?s)\(:require[^)]*[\s]clojure\.string[\s\)]" back))
                                          "still requires or calls clojure.string"
                                          (not (substitution-only? a (get orig p) back))
                                          "changed something other than the substitutions"
                                          (not (insertion-landed-inside-ns? back))
                                          "the inserted require landed outside the ns form"
                                          :else nil)]
                            :when fail]
                        [p fail])]
              (when (seq bad)
                (doseq [[p why] bad] (println (str "READBACK-FAILED\t" p "\t" why)))
                (refuse! "a rewritten file did not survive the read-back invariant")))
            ;; The deps.edn to update is the NEAREST ANCESTOR of each rewritten
            ;; file, not `<repo>/deps.edn`. net-kotobase/control-plane is a
            ;; monorepo with a deps.edn per sub-project and none at the root:
            ;; 146 files were rewritten there and nothing declared the
            ;; dependency, because the only file the tool looked for did not
            ;; exist and its absence was indistinguishable from "already done".
            ;; `bb.edn` counts. Babashka's project file carries `:deps` with the
            ;; same shape as deps.edn, and two of the repos that had "no deps.edn
            ;; to declare in" have only a bb.edn -- refusing them was the tool
            ;; being narrow, not the repo being unusual.
            (let [project-file (fn [d]
                                 (some (fn [n]
                                         (let [c (.join path-mod d n)]
                                           (when (try (.isFile (.statSync fs c))
                                                      (catch :default _ false)) c)))
                                       ["deps.edn" "bb.edn"]))
                  nearest (fn [file]
                            (loop [d (.dirname path-mod file)]
                              (or (project-file d)
                                  (when-not (or (= d repo) (= d (.dirname path-mod d)))
                                    (recur (.dirname path-mod d))))))
                  targets-deps (distinct (keep (fn [[f _]] (nearest f)) changed))
                  targets-deps (if (seq targets-deps)
                                 targets-deps
                                 (when-let [dp (project-file repo)] [dp]))]
              (when (empty? targets-deps)
                (println "DEPS\tnone-found")
                (refuse! (str "rewrote " (count changed) " file(s) but found no deps.edn"
                              " or bb.edn above any of them to declare kotoba.lang.text"
                              " in -- the namespace would not resolve")))
              (doseq [dp targets-deps]
                (let [[status s'] (add-dep dp)]
                  (when (#{:added :inserted} status) (.writeFileSync fs dp s' "utf8"))
                  (println (str "DEPS\t" (name status) "\t" dp))
                  (when (= :refused status)
                    (refuse! (str dp " has neither a locatable top-level :deps nor a"
                                  " :paths vector to insert one after; the rewritten"
                                  " source would not resolve kotoba.lang.text"))))))
            (doseq [[p _] changed] (println (str "REWROTE\t" p)))
            (println (str "REWROTE\t" (count changed) "\tfiles -- NOT verified;"
                          " run this repo's tests and say so separately"))
            (js/process.exit 0)))))))

(-main)

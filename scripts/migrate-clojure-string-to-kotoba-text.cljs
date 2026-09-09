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
  (:require [clojure.string :as str]
            [cljs.reader :as reader]))

(def fs (js/require "node:fs"))
(def path-mod (js/require "node:path"))

(def argv (vec (drop 2 (js->clj js/process.argv))))
(defn- opt [flag] (second (drop-while #(not= flag %) argv)))
(def repo (opt "--repo"))
(def text-sha (opt "--text-sha"))
;; `.cljs` is run by nbb, and nbb reads nbb.edn -- NOT deps.edn and not bb.edn.
;; Without this flag a repo whose only project file is a deps.edn gets the
;; coordinate written somewhere its own .cljs can never see it. Measured
;; 2026-09-09 on kotoba-lang/grammar: deps.edn named the dependency, the tool
;; reported DEPS added, and `nbb tools/gen-tmlanguage.cljs` answered "Could not
;; find namespace: kotoba.lang.text". Creating the file is a change to how the
;; repo is built, so it is opt-in rather than silent.
(def create-nbb-edn? (some #{"--create-nbb-edn"} argv))
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

(defn- project-file
  "The deps.edn, bb.edn or nbb.edn directly in this directory, or nil.

  bb.edn takes the same shape as deps.edn, and two of the repos reported as
  having \"no deps.edn to declare in\" have only a bb.edn -- refusing them was
  the tool being narrow, not the repo being unusual.

  nbb.edn is the same story, found when .cljs joined the migration: nbb reads
  :deps from it in the tools.deps shape, git coordinates included. Measured
  2026-09-09 rather than assumed -- an nbb.edn naming kotoba-lang/text by
  :git/url and :git/sha resolves, and `nbb --classpath src -e \"(require ...)\"`
  against it answers from kotoba.lang.text. Twenty-four of the repositories
  refused in the .cljs pass run their tests as `nbb --classpath src:test
  run_tests.cljs`, which is where nbb.edn belongs."
  [d]
  (some (fn [n]
          (let [c (.join path-mod d n)]
            (when (try (.isFile (.statSync fs c)) (catch :default _ false)) c)))
        ["deps.edn" "bb.edn" "nbb.edn"]))

(defn- declaration-landed?
  "Whether the ONLY semantic change to the project file is one added dependency.

  Added 2026-09-09, after thirty deps.edn files were found split in half by an
  earlier version of the insertion arithmetic. Every one passed the read-back
  invariant, because that invariant asks whether the SOURCE files round-trip and
  never looked at the project file this tool also writes.

  The first version of this check only asked whether the result still PARSED,
  and that was not enough -- discriminated by reintroducing the bug at a
  different offset, which produced

      {:paths [\"src\"] :deps io.github.kotoba-lang/text {...} {com.example/a ...}}

  an even number of forms, a clean parse, and `:deps` bound to a symbol. A check
  that a corrupted file can satisfy is not a check. So the question asked here
  is the whole statement instead: read both sides, and require that everything
  outside `:deps` is untouched and that `:deps` differs by exactly the one entry
  this tool meant to add.

  Comments are dropped by the reader, which is right for this: what is being
  compared is what tools.deps will see, not the bytes.

  cljs.reader is stricter than Clojure's about multi-segment keywords, so an
  unreadable BEFORE is reported as the repo's own defect rather than as damage
  from this edit -- see the two refusals at the call site."
  [before after]
  (let [b (try (reader/read-string before) (catch :default _ nil))
        a (try (reader/read-string after) (catch :default _ nil))]
    (boolean
      (and (map? b) (map? a)
           (= (dissoc b :deps) (dissoc a :deps))
           (map? (:deps a))
           (contains? (:deps a) 'io.github.kotoba-lang/text)
           (= (dissoc (:deps a) 'io.github.kotoba-lang/text)
              (or (:deps b) {}))))))

(defn- reads-back?
  "Whether this text reads back as an EDN map at all. Used only to tell a file
  that was ALREADY broken from one this tool broke."
  [text]
  (try (map? (reader/read-string text)) (catch :default _ false)))

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
        ;; A FILE THAT ONLY MENTIONS clojure.string IN PROSE IS NOT A TARGET.
        ;; The filter used to be a bare substring test, so a file that had
        ;; ALREADY been migrated -- requiring kotoba.lang.text, calling nothing
        ;; from clojure.string -- was picked up because a comment above its ns
        ;; form said "dependency-free (only clojure.string)". The no-alias path
        ;; then found no `[clojure.string :as x]` to rename, concluded the file
        ;; called the namespace without requiring it, and inserted a second
        ;; libspec: `(:require [kotoba.lang.text] [kotoba.lang.text :as str])`.
        ;;
        ;; The read-back invariant caught it and refused the whole repo, which
        ;; is the invariant working -- but the repo was club-shinshi-app and the
        ;; cost was 61 other files. So the target set is narrowed to what it
        ;; always meant: a file that REQUIRES clojure.string, or CALLS it.
        ;; A COPY OF THE TARGET NAMESPACE IS NOT A CONSUMER OF IT.
        ;; kuro vendors kotoba.lang.text under test/cljs-shims/, and
        ;; kotoba-lang/text is the namespace itself plus three test files that
        ;; declare it. Rewriting those turns (:require [clojure.string ...])
        ;; into a namespace requiring ITSELF. The read-back invariant caught it
        ;; and refused both repositories, which is the invariant working -- but
        ;; the fix is not to loosen the invariant, it is to stop calling the
        ;; definition a call site. Measured 2026-09-09: five such files exist in
        ;; the workspace, four in kotoba-lang/text and one in kuro.
        self?    (fn [s] (re-find #"(?m)^\(ns\s+kotoba\.lang\.text\b" s))
        targets  (filterv (fn [[_ s]] (and (not (self? s))
                                           (or (requires-clojure-string? s)
                                               (re-find #"\bclojure\.string/" s))))
                          loaded)
        ;; A HAZARD IS A HOST DISAGREEMENT, AND A .clj FILE HAS ONE HOST.
        ;; All three hazards -- split with a capturing group, `$&`, and `\$` --
        ;; are places where clojure.string's two implementations answer
        ;; differently. kotoba.lang.text adopts the JVM's answer in all three,
        ;; so in a file that only ever runs on the JVM the rewrite cannot change
        ;; the answer. The hazard detector says exactly this in its own output
        ;; ("`.clj` is unaffected") and this tool refused anyway, which held
        ;; cloud-itonami-isco-4313 and its 54 files on one line of a test file.
        ;;
        ;; Measured on the JVM before this exemption was written, using that
        ;; file's own pattern: 180 cases across four patterns, nine inputs and
        ;; five limits -- including #"/(ipfs|refs)/" -- gave zero disagreements
        ;; between clojure.string/split and kotoba.lang.text/split. The harness
        ;; was controlled: it prints a difference when given two calls that do
        ;; differ, so agreement is a result and not a silent comparator.
        ;;
        ;; .cljc keeps the check. Its two halves are exactly the disagreement.
        haz      (for [[p s] targets
                       :when (not (str/ends-with? p ".clj"))
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
      (let [project-above (fn [file]
                            (loop [d (.dirname path-mod file)]
                              (or (project-file d)
                                  (when-not (or (= d repo) (= d (.dirname path-mod d)))
                                    (recur (.dirname path-mod d))))))
            nbb-above (fn [file]
                        (loop [d (.dirname path-mod file)]
                          (let [c (.join path-mod d "nbb.edn")]
                            (cond
                              (try (.isFile (.statSync fs c)) (catch :default _ false)) c
                              (or (= d repo) (= d (.dirname path-mod d))) nil
                              :else (recur (.dirname path-mod d))))))
            all-changed (for [[p s] targets
                              :let [a (alias-of s), s' (rewrite a s)]
                              :when (not= s s')]
                          [p s'])
            ;; A FILE WITH NO PROJECT FILE ABOVE IT IS NOT REWRITTEN.
            ;; The refusal for "nowhere to declare kotoba.lang.text" used to be
            ;; global: it fired only when NO changed file had a project file
            ;; above it. So in a repo where most files do and a few do not, the
            ;; few were rewritten into a namespace nothing would resolve, and
            ;; nothing said so. Measured on club-shinshi-app: 59 files under a
            ;; deps.edn and three standalone `#!/usr/bin/env nbb` scripts under
            ;; scripts/, which have no project file anywhere above them and
            ;; would have stopped working.
            ;;
            ;; They are skipped and named, rather than the whole repo refused --
            ;; a file that can be migrated correctly should be, and a file that
            ;; cannot should be reported, not quietly broken.
            orphan  (filterv (fn [[p _]] (nil? (project-above p))) all-changed)
            changed (filterv (fn [[p _]] (some? (project-above p))) all-changed)]
        (doseq [[p _] orphan]
          (println (str "SKIPPED-NO-DECLARATION\t" p)))
        (when (seq orphan)
          (println (str "SKIPPED\t" (count orphan) "\tfile(s) have no deps.edn or"
                        " bb.edn above them; rewriting them would leave"
                        " kotoba.lang.text unresolvable")))
        (when (empty? changed)
          (refuse! (str "every one of the " (count all-changed) " file(s) to rewrite"
                        " has no project file above it; nothing was written")))
        ;; nbb.edn IS THE DECLARATION SITE FOR .cljs, AND ONLY nbb.edn IS.
        ;; deps.edn and bb.edn are both invisible to nbb, so a repo whose .cljs
        ;; were rewritten and whose coordinate went to deps.edn is not migrated
        ;; -- it is broken, and it reports success. Measured 2026-09-09 on three
        ;; repos in one wave (grammar, cybersecurity, kotoba-fleet): each printed
        ;; DEPS added, and each then answered "Could not find namespace:
        ;; kotoba.lang.text" when its own entry point was run.
        ;;
        ;; This is checked HERE, before any source is written, so the refusal can
        ;; truthfully say nothing was written. The first version of it sat in the
        ;; declaration phase and said so while 1 rewritten file was already on
        ;; disk.
        (let [without (filterv (fn [[p _]] (and (str/ends-with? p ".cljs")
                                                (nil? (nbb-above p))))
                               changed)]
          (when (seq without)
            (doseq [[p _] without] (println (str "NO-NBB-EDN\t" p)))
            (when-not create-nbb-edn?
              (refuse! (str (count without) " rewritten .cljs file(s) would have no"
                            " nbb.edn above them. nbb does not read deps.edn or"
                            " bb.edn, so the declaration this tool writes there is one"
                            " their own runtime cannot see: they would fail at load"
                            " with \"Could not find namespace: kotoba.lang.text\"."
                            " Nothing was written. Pass --create-nbb-edn to have one"
                            " written beside the project file, mirroring its :paths.")))))
        (if-not apply?
          (do (doseq [[p _] changed] (println (str "WOULD-REWRITE\t" p)))
              (println (str "PLAN\t" (count changed) "\tfiles (dry run; pass --apply)"))
              (js/process.exit 0))
          (do
            ;; --create-nbb-edn: one nbb.edn beside each project file that has
            ;; .cljs under it with no nbb.edn of their own. :paths is COPIED
            ;; from that project file -- the tool does not choose a classpath.
            (doseq [dp (distinct (keep (fn [[f _]] (when (and (str/ends-with? f ".cljs")
                                                              (nil? (nbb-above f)))
                                                     (project-above f)))
                                       changed))]
              (let [np   (.join path-mod (.dirname path-mod dp) "nbb.edn")
                    base (try (reader/read-string (.readFileSync fs dp "utf8"))
                              (catch :default _ nil))
                    ps   (:paths base)]
                (when-not (vector? ps)
                  (refuse! (str dp " has no :paths vector to mirror, so"
                                " --create-nbb-edn would have to invent one for " np
                                " -- and a classpath this tool guessed is exactly the"
                                " kind of declaration that looks right and resolves"
                                " nothing. Nothing was written.")))
                (.writeFileSync
                  fs np
                  (str ";; nbb reads this file and does NOT read deps.edn or bb.edn.\n"
                       ";; The .cljs here are run by nbb, so a coordinate declared only\n"
                       ";; in " (.basename path-mod dp) " is invisible to them.\n"
                       ";;\n"
                       ";; :paths is COPIED from " (.basename path-mod dp)
                       ", not chosen here.\n"
                       "{:paths " (pr-str ps) "\n"
                       " :deps {io.github.kotoba-lang/text {:git/sha \"" text-sha "\"}}}\n")
                  "utf8")
                (println (str "DEPS\tcreated\t" np))))
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
            (let [targets-deps (distinct (concat
                                           (keep (fn [[f _]] (project-above f)) changed)
                                           (keep (fn [[f _]]
                                                   (when (str/ends-with? f ".cljs")
                                                     (let [d (.dirname path-mod f)]
                                                       (loop [d d]
                                                         (let [c (.join path-mod d "nbb.edn")]
                                                           (cond
                                                             (try (.isFile (.statSync fs c))
                                                                  (catch :default _ false)) c
                                                             (or (= d repo) (= d (.dirname path-mod d))) nil
                                                             :else (recur (.dirname path-mod d))))))))
                                                 changed)))
                  targets-deps (if (seq targets-deps)
                                 targets-deps
                                 (when-let [dp (project-file repo)] [dp]))]
              (when (empty? targets-deps)
                (println "DEPS\tnone-found")
                (refuse! (str "rewrote " (count changed) " file(s) but found no deps.edn"
                              " or bb.edn above any of them to declare kotoba.lang.text"
                              " in -- the namespace would not resolve")))
              (doseq [dp targets-deps]
                (let [before (.readFileSync fs dp "utf8")
                      [status s'] (add-dep dp)]
                  ;; Parse BEFORE writing. A project file this tool cannot read
                  ;; back is one it must not edit -- and if it could not read it
                  ;; beforehand either, that is the repo's defect to report, not
                  ;; this tool's to paper over.
                  (when (#{:added :inserted} status)
                    (cond
                      (not (reads-back? before))
                      (refuse! (str dp " does not read back as an EDN map BEFORE this"
                                    " tool touched it -- broken already, and not by"
                                    " this edit. The declaration was NOT written; the"
                                    " source rewrites are on disk, so discard this"
                                    " working tree. The project file needs a human."))
                      (not (declaration-landed? before s'))
                      (refuse! (str "inserting the kotoba.lang.text declaration into "
                                    dp " did not leave the rest of that file alone:"
                                    " read back, it is not the same map plus exactly"
                                    " one dependency. The insertion arithmetic is"
                                    " wrong for this file's shape. The project file"
                                    " was NOT written; the source rewrites are on"
                                    " disk, so discard this working tree."))
                      :else (.writeFileSync fs dp s' "utf8")))
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

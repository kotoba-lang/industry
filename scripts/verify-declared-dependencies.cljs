#!/usr/bin/env nbb
(ns verify-declared-dependencies
  "A repository's `src/` requires namespaces its own `deps.edn` does not
  account for.

  ## The failure this exists for

  A require is resolved by the classpath, and in this workspace the classpath
  is assembled four different ways -- `clojure -M:test`, a hand-written
  `--classpath` in `bin/run_tests.cljs`, shadow-cljs reading `:deps`, and a
  sibling checkout that happens to sit next door under `orgs/`. Three of those
  four put a dependency on the classpath whether or not `deps.edn` names it.
  So a require to an undeclared library resolves for everyone who already has
  it and for nobody else -- and the first person it does not resolve for is
  always a new consumer, halfway through its first run.

  Measured twice on 2026-09-06, in one session:

  1. `kotoba-lang/kotobase-protocol-core`'s deps.edn said `src has no
     dependencies at all -- this is the bottom of the stack ... verified by
     grep at the 2026-08-05 split`. The grep was true when it was run.
     `src/kotobase/protocols/blocks.cljc` arrived AFTER that split and
     requires `kotobase.store`. Three consumer repos needed the whole
     `kotobase` repository on the classpath with nothing declaring it; the
     fourth found out as `Could not find namespace: kotobase.store`.

  2. `net-kotobase/ipfs` pinned `io-ipld` twelve commits behind the namespace
     a new file needed. The same shape one layer down: the coordinate was
     declared, and the revision it named did not carry the namespace.

  Neither is carelessness. A comment recording a grep is only as true as the
  day it was run, and nothing in this workspace re-ran it.

  ## The two directions are different instructions, so they are reported apart

  - **used but undeclared** -- `src` requires it, `deps.edn` cannot reach it.
    The instruction is `add a coordinate`. Severity `fail`.
  - **declared but unused** -- `:deps` names it, no namespace under `src`
    reaches for it. The instruction is `remove it, or say why it is there`.
    Severity `warn`, never `fail`: a coordinate can be a deliberate version
    FLOOR for something a direct dependency pulls transitively, and that case
    is reported as such rather than as dead weight.

  Fusing the two into one `dependency mismatch` count is the exact failure
  this detector is about -- a number nobody can act on.

  ## Three questions, not one

  For each repository in scope:

  1. **reachability** -- is every namespace `src` requires provided by the
     transitive closure of what `:deps` declares, read from the WORKING TREES
     under `orgs/`?
  2. **pin** -- for the DIRECT git dependencies, does the revision the pin
     names actually carry the namespace? That is question 1 asked of the bytes
     tools.deps would fetch rather than of the checkout that happens to be
     here, and it is the only one that sees defect 2 above.
  3. **direction** -- which declared coordinates does `src` never reach for?

  Question 2 is deliberately bounded to DIRECT dependencies and to namespaces
  the scoped repository requires ITSELF. Following pins transitively means
  reimplementing tools.deps' newest-wins resolution, and a wrong answer there
  would be worse than a stated gap.

  ## What it reads, and what that costs

  Working trees, as `verify-dep-coordinate-identity` does and for the same
  reason: `origin/main` would report on code nobody here can run and would
  need a fetch per checkout. Checkout, west pin and the repository's main are
  three different things (ADR-2608136800) -- before acting on a finding, check
  the dependency's main.

  Requires are read from `ns` declarations only. A run-time `(require ...)` is
  not seen, and is not claimed to be. `(:import ...)` is not read either: a
  Java class is a different question with a different answer.

  ## Refusing to answer

  Exit is three-valued:

    0  every repository in scope was examined and agreed
    1  every repository was examined, at least one disagreed
    2  something could NOT be examined -- REFUSING to report an agreement it
       did not verify.

  A dependency whose checkout is absent from `orgs/` is exit 2, never exit 0:
  `I could not read what that provides` and `that provides everything you
  need` are otherwise the same silence. So are an unparseable `deps.edn`, an
  unreadable source file, a source file with no `ns` form, a `--repo` that
  does not exist, and a scan that examined zero requires.

  ## Usage

    nbb --classpath \".:scripts/nbb_compat\" scripts/verify-declared-dependencies.cljs \\
        [--scan-root <dir>] [--repo <path>]... [--only <path>]... [--findings]
    nbb --classpath \".:scripts/nbb_compat\" scripts/verify-declared-dependencies.cljs --selftest

  `--scan-root` is where `orgs/` lives, so the detector can run from a
  worktree that does not have it (the shape `gen-concept-index.cljs` uses).
  `--repo` widens the default scope; `--only` replaces it. Both take paths
  relative to `--scan-root` and both are ASSERTIONS -- a path named on the
  command line that is not a directory is exit 2."
  (:require ["fs" :as fs]
            ["path" :as p]
            ["os" :as os]
            ["child_process" :as cp]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ── scope ───────────────────────────────────────────────────────────────────

(def default-scope
  "PATTERNS, not assertions. A trailing `*` globs one directory level.

  A pattern that matches nothing contributes nothing and is not a finding --
  the scan may legitimately be pointed at a tree holding only some of these.
  A path named with `--repo` or `--only` IS an assertion and must exist,
  because the caller said it does."
  ["orgs/kotoba-lang/kotobase-protocol-*"
   "orgs/kotoba-lang/io-ipld*"
   "orgs/kotoba-lang/io-ipni-specs"
   "orgs/kotoba-lang/ayatori"
   "orgs/kotoba-lang/arrangement"
   "orgs/net-kotobase/ipfs"])

(def stdlib-namespaces
  "The standard library, named one namespace at a time.

  Deliberately NOT the prefix `clojure.`. `clojure.data.json`,
  `clojure.core.async` and `clojure.tools.logging` all live under it and all
  need a coordinate, so a prefix rule would silence exactly the requires this
  detector exists to find -- and silence them invisibly, which is worse than
  not checking. Measured 2026-09-06 in the default scope: `clojure.string`
  (27), `clojure.edn` (2), `clojure.set` (1) and nothing else, so the list
  costs no noise here and closes the hole for whoever widens the scope."
  #{"clojure.core" "clojure.core.protocols" "clojure.core.reducers"
    "clojure.string" "clojure.set" "clojure.walk" "clojure.edn" "clojure.zip"
    "clojure.data" "clojure.datafy" "clojure.pprint" "clojure.test"
    "clojure.math" "clojure.repl" "clojure.main" "clojure.template"
    "clojure.instant" "clojure.uuid" "clojure.stacktrace" "clojure.reflect"
    "clojure.java.io" "clojure.java.shell" "clojure.java.browse"
    "clojure.spec.alpha" "clojure.spec.gen.alpha" "clojure.spec.test.alpha"
    "cljs.core" "cljs.reader" "cljs.pprint" "cljs.test" "cljs.js" "cljs.env"
    "cljs.analyzer" "cljs.spec.alpha" "cljs.spec.test.alpha" "goog"})

(def runtime-prefixes
  "Families the HOST supplies whole, where naming members individually would
  be a list nobody could keep true.

  `goog.` ships inside ClojureScript; `js.` is interop; `nbb.` and `promesa.`
  are compiled into the nbb binary; `shadow.` comes from shadow-cljs, which
  every consumer of it already has on the classpath as a build tool."
  ["goog." "js." "nbb." "promesa." "shadow."])

(def language-coordinates
  "Coordinates whose contents are exactly `stdlib-namespaces`.

  Without this they are maven artifacts, and a maven artifact is `:opaque` --
  which would make every repository that declares Clojure itself refuse to
  report agreement, forever, for a reason that has nothing to do with what is
  being measured."
  #{"org.clojure/clojure" "org.clojure/clojurescript"
    "org.clojure/spec.alpha" "org.clojure/core.specs.alpha"})

;; ── a reader for `ns` forms ─────────────────────────────────────────────────
;;
;; Not `clojure.edn/read-string`: an `ns` form carries reader conditionals,
;; metadata and `#_` discards, none of which are EDN, and a `try` around a
;; reader that dies on the FIRST `#?` would report a `.cljc` file as requiring
;; nothing -- an unreadable file counted as a clean one, which is the class
;; this workspace's rule set names first.
;;
;; So: a tokeniser that knows just enough syntax to keep its shape and drops
;; the rest. A node is {:t :sym|:kw|:str|:other|:coll}.

(defn- delimiter? [c]
  (contains? #{\space \tab \newline \return \, \( \) \[ \] \{ \} \" \; \'} c))

(defn- token-end [s i]
  (let [n (count s)]
    (loop [j i] (if (or (>= j n) (delimiter? (nth s j))) j (recur (inc j))))))

(defn- string-end
  "Index just past the closing quote of the string starting at `i`."
  [s i]
  (let [n (count s)]
    (loop [j (inc i)]
      (cond (>= j n) n
            (= \\ (nth s j)) (recur (+ j 2))
            (= \" (nth s j)) (inc j)
            :else (recur (inc j))))))

(defn- push-node
  "Attach `node` to the frame on top of `stack`, honouring a pending `#_`."
  [stack node]
  (let [[children discard?] (peek stack)]
    (conj (pop stack) [(if discard? children (conj children node)) false])))

(defn tokenize
  "Nested nodes for the code in `s`.

  Strings, regexes, character literals, comments and `#_`-discarded forms are
  removed as SYNTAX rather than as text, so a `;` inside a docstring and a
  quote inside a comment do not move the parens."
  [s]
  (let [n (count s)]
    (loop [i 0 stack [[[] false]]]
      (if (>= i n)
        (first (peek stack))
        (let [c (nth s i)]
          (cond
            (contains? #{\space \tab \newline \return \,} c) (recur (inc i) stack)

            (= \; c) (recur (let [j (str/index-of s "\n" i)] (if j (inc j) n)) stack)

            ;; metadata, quote, syntax-quote, unquote, deref: syntax only
            (contains? #{\^ \' \` \~ \@} c) (recur (inc i) stack)

            (= \" c) (recur (string-end s i) (push-node stack {:t :str}))

            ;; \x and \newline. The name form is read as a token so a `\(foo`
            ;; does not swallow `foo`, and a bare `\(` stops after one char.
            (= \\ c)
            (let [nx (when (< (inc i) n) (nth s (inc i)))]
              (recur (if (and nx (re-find #"[A-Za-z0-9]" (str nx)))
                       (token-end s (inc i))
                       (+ i 2))
                     (push-node stack {:t :other})))

            (= \# c)
            (let [nx (when (< (inc i) n) (nth s (inc i)))]
              (cond
                (= \_ nx) (let [[children _] (peek stack)]
                            (recur (+ i 2) (conj (pop stack) [children true])))
                (= \" nx) (recur (string-end s (inc i)) (push-node stack {:t :other}))
                ;; #? #?@ #{ #( and every tagged literal: drop the dispatch
                ;; chars, keep the form. A reader conditional therefore reads
                ;; as an ordinary list of feature/spec pairs, which is what
                ;; `libspec-nss` below is written against.
                (= \? nx) (recur (if (= \@ (when (< (+ i 2) n) (nth s (+ i 2)))) (+ i 3) (+ i 2))
                                 stack)
                (contains? #{\{ \(} nx) (recur (inc i) stack)
                :else (recur (token-end s (inc i)) stack)))

            (contains? #{\( \[ \{} c) (recur (inc i) (conj stack [[] false]))

            (contains? #{\) \] \}} c)
            (if (= 1 (count stack))
              (recur (inc i) stack)                 ; unbalanced close: keep reading
              (let [[children _] (peek stack)]
                (recur (inc i) (push-node (pop stack) {:t :coll :children children}))))

            :else
            (let [j (max (inc i) (token-end s i))
                  tok (subs s i j)]
              (recur j (push-node stack (if (str/starts-with? tok ":")
                                          {:t :kw :v tok}
                                          {:t :sym :v tok}))))))))))

(defn- coll-head [node] (first (:children node)))

(defn libspec-nss
  "The namespaces one require spec names.

  Four shapes, all of which occur in this workspace:

    foo.bar                       a bare symbol
    [foo.bar :as x :refer [a b]]  a libspec, whose option VALUES are skipped
                                  -- `[a b]` after `:refer` is not a namespace
    (:clj [a] :cljs [b])          a reader conditional, already stripped of
                                  its `#?` by the tokeniser
    [clojure [string :as s]]      a prefix list; the head is a prefix, and is
                                  not itself a namespace

  A string spec is an npm package and yields nothing."
  [node]
  (case (:t node)
    :sym [(:v node)]
    :coll
    (let [ch (:children node)
          head (first ch)]
      (cond
        (nil? head) []

        ;; a reader conditional, or a `#?@` splice whose children are specs
        (contains? #{:kw :coll} (:t head))
        (into [] (mapcat libspec-nss) (remove #(= :kw (:t %)) ch))

        (= :sym (:t head))
        (let [prefix-children
              (loop [xs (rest ch) acc []]
                (cond (empty? xs) acc
                      (= :kw (:t (first xs))) (recur (drop 2 xs) acc)   ; option + its value
                      (= :coll (:t (first xs))) (recur (rest xs) (conj acc (first xs)))
                      :else (recur (rest xs) acc)))]
          (if (seq prefix-children)
            (into [] (mapcat (fn [c] (map #(str (:v head) "." %) (libspec-nss c)))) prefix-children)
            [(:v head)]))

        :else []))
    []))

(defn ns-requires
  "Namespaces the `ns` form in `src` requires, or nil when there is no `ns`
  form at all.

  nil is not the empty set. A source file with no `ns` form is a file this
  reader did not understand, and the caller counts it rather than treating it
  as a file that requires nothing."
  [src]
  (let [top (tokenize src)
        nsf (first (filter #(and (= :coll (:t %))
                                 (= {:t :sym :v "ns"} (coll-head %)))
                           top))]
    (when nsf
      (->> (:children nsf)
           (filter #(and (= :coll (:t %))
                         (contains? #{":require" ":require-macros" ":use"}
                                    (:v (coll-head %)))))
           (mapcat #(mapcat libspec-nss (rest (:children %))))
           (remove nil?)
           set))))

;; ── state ───────────────────────────────────────────────────────────────────

(def unmeasured (atom []))        ; [{:key :detail}] -- drives exit 2
(def findings   (atom []))        ; [{:sev :key :detail}]
(def findings?  (atom false))
(def repo-cache (atom {}))

(defn- unmeasured! [k detail] (swap! unmeasured conj {:key (str k) :detail detail}))
(defn- finding! [sev k detail] (swap! findings conj {:sev sev :key (str k) :detail detail}))

(def scan-root* (atom "."))

(defn rel-to
  "A path relative to the scan root, for use in a finding KEY.

  Findings are keyed forever, and an absolute path puts this machine's home
  directory inside the key -- so the same defect, seen from a worktree or from
  another checkout, would be a different finding with no history."
  [abs]
  (let [root (str @scan-root*)
        abs (str abs)]
    (cond
      (= abs root) "."
      (str/starts-with? abs (str root "/")) (subs abs (inc (count root)))
      :else abs)))

(defn distinct-by-key
  "One row per key. The same coordinate is reachable through several paths
  through one closure -- `org.clojure/clojure` arrived twice for every
  repository that has it -- and a key is what the registry counts findings
  by, so two rows under one key are one finding printed twice."
  [rows]
  (->> rows (reduce (fn [[seen out] r]
                      (if (seen (:key r)) [seen out]
                          [(conj seen (:key r)) (conj out r)]))
                    [#{} []])
       second))

;; ── the tree ────────────────────────────────────────────────────────────────

(defn- dir? [x] (try (.isDirectory (fs/statSync x)) (catch :default _ false)))

(defn- walk-files
  "Every file under `dir`. A directory that cannot be read is pushed onto
  `errs` rather than contributing zero files, because a subtree read as empty
  is a subtree whose requires were silently dropped."
  [dir errs]
  (if-not (dir? dir)
    []
    (try
      (into [] (mapcat (fn [e]
                         (let [f (p/join dir e)]
                           (if (dir? f) (walk-files f errs) [f]))))
            (fs/readdirSync dir))
      (catch :default e
        (swap! errs conj {:dir dir :err (.-message e)})
        []))))

(def source-ext #{".clj" ".cljc" ".cljs"})
(defn- source? [f] (contains? source-ext (p/extname f)))

(defn path->ns
  "The namespace a classpath root resolves this file to. Path-derived on
  purpose: that IS how a require is satisfied, so a file whose `ns` form
  disagrees with its path does not provide what its `ns` form claims."
  [rel]
  (-> rel
      (str/replace #"\.(clj|cljc|cljs)$" "")
      (str/replace "_" "-")
      (str/replace "/" ".")))

(defn read-repo
  "Everything one checkout says about itself: its `:paths`, the namespaces
  those paths provide, the namespaces its sources require, and its `:deps`.

  `:readable? false` is why this cache exists at all -- a checkout that is
  absent, or whose deps.edn does not parse, must reach the caller as a
  measurable state and not as an empty map that reads like a library with no
  dependencies."
  [dir]
  (or (@repo-cache dir)
      (let [r (if-not (dir? dir)
                {:dir dir :readable? false :reason :no-checkout}
                (let [df (p/join dir "deps.edn")
                      parsed (when (fs/existsSync df)
                               (try {:ok (edn/read-string (str (fs/readFileSync df "utf8")))}
                                    (catch :default e {:err (.-message e)})))]
                  (if (:err parsed)
                    {:dir dir :readable? false :reason :unparseable-deps :detail (:err parsed)}
                    (let [deps-edn (:ok parsed)
                          declared (filter string? (:paths deps-edn))
                          roots (filterv #(dir? (p/join dir %))
                                         (if (seq declared) declared ["src"]))
                          errs (atom [])
                          files (into [] (for [rt roots
                                               f (walk-files (p/join dir rt) errs)
                                               :when (source? f)]
                                           {:rel (subs f (inc (count (p/join dir rt)))) :abs f}))
                          read (mapv (fn [{:keys [abs rel]}]
                                       (try {:ns (path->ns rel)
                                             :rel rel
                                             :req (ns-requires (str (fs/readFileSync abs "utf8")))}
                                            (catch :default e
                                              {:ns (path->ns rel) :rel rel :err (.-message e)})))
                                     files)]
                      {:dir dir :readable? true
                       :deps (or (:deps deps-edn) {})
                       :roots roots
                       :n-files (count files)
                       :dir-errors @errs
                       :unreadable (filterv :err read)
                       :no-ns-form (filterv #(and (not (:err %)) (nil? (:req %))) read)
                       :provides (set (map :ns read))
                       :requires (reduce into #{} (keep :req read))}))))]
        (swap! repo-cache assoc dir r)
        r)))

;; ── coordinates ─────────────────────────────────────────────────────────────

(def in-house
  #{"kotoba-lang" "cloud-itonami" "com-junkawasaki" "etzhayyim"
    "gftdcojp" "net-kotobase" "network-awai" "jk-luxury"})

(defn coord->repo
  "`org/name` for a dependency entry, by the rule tools.deps derives a URL
  with: an explicit `:git/url` when there is one, otherwise the coordinate
  minus its `io.github.`/`com.github.` prefix."
  [lib coord]
  (let [url (:git/url coord)]
    (if (seq (str url))
      (-> (str url) (str/replace #"^https?://github\.com/" "") (str/replace #"\.git$" ""))
      (str/replace (str lib) #"^(io|com)\.github\." ""))))

(defn provider
  "Where a dependency entry's code is, if this machine can read it at all.

  `:kind` answers `can I enumerate what this provides`:
    :checkout  a directory under orgs/ (or a `:local/root` path) -- readable
    :opaque    a jar, or a third-party GitHub repo with no checkout here.
               NOT a pass; recorded, and the exit code refuses because of it."
  [scan-root from-dir lib coord]
  (cond
    (language-coordinates (str lib))
    {:coord (str lib) :kind :language}

    (:local/root coord)
    {:coord (str lib) :kind :checkout
     :dir (p/normalize (p/join from-dir (str (:local/root coord))))}

    (:mvn/version coord)
    {:coord (str lib) :kind :opaque :why (str "maven artifact " (:mvn/version coord))}

    (or (:git/sha coord) (:git/url coord) (:git/tag coord))
    (let [r (coord->repo lib coord)
          org (first (str/split r #"/"))]
      (if (in-house org)
        {:coord (str lib) :kind :checkout :repo r :sha (:git/sha coord)
         :dir (p/join scan-root "orgs" r)}
        {:coord (str lib) :kind :opaque :repo r
         :why (str "third-party repository " r ", not checked out under orgs/")}))

    :else {:coord (str lib) :kind :opaque :why "coordinate names no git or maven revision"}))

(defn direct-providers [scan-root dir deps]
  (mapv (fn [[lib coord]] (provider scan-root dir lib coord))
        (when (map? deps) deps)))

(defn closure
  "Every provider reachable from `dir`'s `:deps`, transitively.

  Returns {:checkouts #{dir} :opaque [...] :unreadable [...]}. Cycles exist in
  this workspace and are simply visited once."
  [scan-root dir]
  ;; The queue carries [dir declared-by] pairs, not bare dirs. `dag-cbor is
  ;; not readable` is a fact nobody can act on; `kotoba-lang/kotoba declares
  ;; dag-cbor, which is not readable` names the file to edit. Measured
  ;; 2026-09-06: that coordinate is a repository renamed to `org-ietf-cbor`,
  ;; still reachable through GitHub's redirect and so still resolving for
  ;; tools.deps -- which is why nothing had noticed.
  (loop [queue (list [dir nil]) seen #{} checkouts #{} opaque [] unreadable []]
    (if (empty? queue)
      {:checkouts (disj checkouts dir) :opaque opaque :unreadable unreadable}
      (let [[d from] (first queue) queue (rest queue)]
        (if (seen d)
          (recur queue seen checkouts opaque unreadable)
          (let [r (read-repo d)]
            (if-not (:readable? r)
              (recur queue (conj seen d) checkouts opaque
                     (conj unreadable {:dir d :from from :reason (:reason r)
                                       :detail (:detail r)}))
              (let [ps (direct-providers scan-root d (:deps r))
                    next (keep #(when (= :checkout (:kind %)) [(:dir %) d]) ps)]
                (recur (into queue next)
                       (conj seen d)
                       (into (conj checkouts d) (map first next))
                       (into opaque (map #(assoc % :from d)
                                         (filter #(= :opaque (:kind %)) ps)))
                       unreadable)))))))))

;; ── the pin question ────────────────────────────────────────────────────────

(defn tree-namespaces
  "The namespaces a git revision carries, or nil when the revision cannot be
  read from this checkout's object store.

  nil is :unmeasured. A sha nobody has fetched is not a pin that is wrong.
  The source roots are the WORKING TREE's, since reading the pinned deps.edn
  to get the pinned `:paths` would answer a question nobody asked -- stated
  because a repository that moved its sources between the pin and now would
  be measured against the wrong roots."
  [dir sha roots]
  (try
    (let [out (str (cp/execSync (str "git -C " (pr-str dir) " ls-tree -r --name-only " (pr-str sha))
                                #js {:encoding "utf8"
                                     :stdio #js ["pipe" "pipe" "pipe"]
                                     :maxBuffer (* 64 1024 1024)}))]
      (set (for [rt roots
                 f (str/split-lines out)
                 :when (and (str/starts-with? f (str rt "/")) (source? f))]
             (path->ns (subs f (inc (count rt)))))))
    (catch :default _ nil)))

(defn- short-sha [s] (let [s (str s)] (subs s 0 (min 12 (count s)))))

;; ── the check ───────────────────────────────────────────────────────────────

(defn language-provided?
  "Is this namespace the language's rather than a library's?"
  [n]
  (boolean (or (stdlib-namespaces n)
               (some #(str/starts-with? n %) runtime-prefixes))))

(def pending-unaccounted
  "Requires with no provider, held until every repository has been read.

  Resolved at the END rather than where they are found, because the sentence
  `provided by X` is looked up in the cache of checkouts read so far -- and
  the first repository in the scope has an empty cache. Measured: the same
  namespace was reported as `provided by no checkout examined in this run`
  for `kotobase-protocol-atproto` and as `provided by .../kotobase-protocol-core`
  for `kotobase-protocol-ipfs`, one alphabetical position later. Same defect,
  two different instructions, decided by sort order."
  (atom []))

(defn check-repo!
  "The three questions, asked of one repository. Returns a small census map."
  [scan-root rel dir]
  (let [r (read-repo dir)]
    (if-not (:readable? r)
      (do (unmeasured! (str "unreadable-repo:" rel)
                       (str dir " could not be examined: " (name (:reason r))
                            (when (:detail r) (str " — " (:detail r)))))
          {:edges 0 :files 0 :closure 0})
      (let [self (:provides r)
            external (vec (sort (remove language-provided? (remove self (:requires r)))))
            {:keys [checkouts opaque unreadable]} (closure scan-root dir)
            reachable (reduce into #{} (map #(:provides (read-repo %)) checkouts))
            ;; a closure that could not be fully read makes every NEGATIVE
            ;; answer below provisional, and it says so instead of asserting
            blind? (or (seq opaque) (seq unreadable))
            unaccounted (remove reachable external)
            direct (direct-providers scan-root dir (:deps r))]

        ;; inputs that could not be read come first: they are why an answer
        ;; may not be given at all
        (doseq [e (:dir-errors r)]
          (unmeasured! (str "unreadable-dir:" rel ":" (:dir e)) (:err e)))
        (doseq [f (:unreadable r)]
          (unmeasured! (str "unreadable-source:" rel ":" (:rel f))
                       (str (p/join dir (:rel f)) " — " (:err f))))
        (doseq [f (:no-ns-form r)]
          (unmeasured! (str "no-ns-form:" rel ":" (:rel f))
                       (str (p/join dir (:rel f))
                            " has no `ns` form, so what it requires was not read")))
        (doseq [u unreadable]
          (let [k (str "unmeasured-provider:" rel ":" (rel-to (:dir u)))
                detail (str (rel-to (:dir u)) " is on " rel "'s dependency closure and could "
                            "not be read (" (name (:reason u))
                            (when (:detail u) (str ": " (:detail u))) "); first reached from "
                            (rel-to (or (:from u) dir)) "/deps.edn, which declares it — what "
                            "it provides was NOT measured")]
            (unmeasured! k detail)
            (finding! "warn" k detail)))
        (doseq [o opaque]
          (let [k (str "opaque-provider:" rel ":" (:coord o))
                detail (str (:coord o) ", first reached from " (rel-to (or (:from o) dir))
                            "/deps.edn, cannot be enumerated from orgs/ (" (:why o)
                            ") — namespaces it might provide were NOT measured")]
            (unmeasured! k detail)
            (finding! "warn" k detail)))

        ;; ── 1. used but undeclared ──────────────────────────────────────────
        (doseq [n unaccounted]
          (swap! pending-unaccounted conj {:rel rel :ns n :blind? (boolean blind?)}))

        ;; ── 2. the pin, for DIRECT git dependencies only ────────────────────
        (doseq [d direct
                :when (and (= :checkout (:kind d)) (:sha d) (dir? (:dir d)))]
          (let [pr (read-repo (:dir d))
                used-here (filterv (:provides pr) external)]
            (when (seq used-here)
              (if-let [at-pin (tree-namespaces (:dir d) (:sha d) (:roots pr))]
                (doseq [n used-here :when (not (at-pin n))]
                  (finding! "fail" (str "pin-behind:" rel ":" (:coord d) ":" n)
                            (str "src requires " n ", which the CHECKOUT of " (:coord d)
                                 " provides and the pinned revision " (short-sha (:sha d))
                                 " does not — it resolves here and for nobody who resolves "
                                 "the pin")))
                (unmeasured! (str "unmeasured-pin:" rel ":" (:coord d))
                             (str "revision " (short-sha (:sha d)) " of " (:coord d)
                                  " is not in " (:dir d) "'s object store; what it carries "
                                  "was not measured"))))))

        ;; ── 3. declared but unused — the opposite instruction, kept apart ───
        (doseq [d direct :when (= :checkout (:kind d))]
          (let [pr (read-repo (:dir d))]
            (when (:readable? pr)
              (let [provided (:provides pr)]
                (when-not (some provided external)
                  ;; a version FLOOR -- something another reachable provider
                  ;; requires -- is deliberate, not dead weight
                  (let [wanted-by (first (sort (for [o (disj checkouts (:dir d))
                                                     :let [orr (read-repo o)]
                                                     :when (and (:readable? orr)
                                                                (some provided (:requires orr)))]
                                                 o)))]
                    (finding! "warn" (str "declared-unused:" rel ":" (:coord d))
                              (if wanted-by
                                (str (:coord d) " is declared and no namespace under src "
                                     "requires it; " (rel-to wanted-by) " does, so it reads as a "
                                     "deliberate version floor — say so beside the pin")
                                (str (:coord d) " is declared and nothing in the closure "
                                     "requires any namespace it provides")))))))))

        {:edges (count external) :files (:n-files r) :closure (count checkouts)}))))

(defn emit-unaccounted!
  "Turn the held requires into findings, now that every checkout has been read
  and `provided by X` can be answered the same way for all of them."
  []
  (doseq [{:keys [rel ns blind?]} @pending-unaccounted]
    (let [owner (first (sort (for [[d rr] @repo-cache
                                   :when (and (:readable? rr) ((:provides rr) ns))]
                               d)))
          where (if owner (str "provided by " (rel-to owner))
                    "provided by no checkout read in this run")]
      (if blind?
        (finding! "warn" (str "unresolved-require:" rel ":" ns)
                  (str "src requires " ns ", which nothing in the READABLE part of the "
                       "declared closure provides (" where "); part of that closure could "
                       "not be read, so this is not asserted"))
        (finding! "fail" (str "undeclared-require:" rel ":" ns)
                  (str "src requires " ns " and deps.edn declares nothing that provides it ("
                       where ")"))))))

;; ── scope resolution ────────────────────────────────────────────────────────

(defn expand-pattern
  "One directory level of `*`, and nothing more. Globbing is not the point of
  this script, and a general matcher would be a second thing to be wrong."
  [scan-root pat]
  (if-not (str/includes? pat "*")
    (when (dir? (p/join scan-root pat)) [pat])
    (let [i (or (str/last-index-of pat "/") -1)
          parent (if (neg? i) "." (subs pat 0 i))
          leaf (subs pat (inc i))
          pfx (subs leaf 0 (str/index-of leaf "*"))
          pd (p/join scan-root parent)]
      (when (dir? pd)
        (->> (fs/readdirSync pd)
             sort
             (filter #(str/starts-with? % pfx))
             (filter #(dir? (p/join pd %)))
             (mapv #(if (neg? i) % (str parent "/" %))))))))

(defn flag-values [argv f]
  (->> (map vector argv (rest argv))
       (keep (fn [[a b]] (when (= f a) b)))
       vec))

;; ── selftest ────────────────────────────────────────────────────────────────

(def selftest-fails (atom 0))

(defn- t! [nm ok? detail]
  (if ok?
    (println (str "  ok   " (name nm) "  —  " detail))
    (do (swap! selftest-fails inc)
        (println (str "  FAIL " (name nm) "  —  " detail)))))

(defn- write! [f content]
  (fs/mkdirSync (p/dirname f) #js {:recursive true})
  (fs/writeFileSync f content))

(defn- git!
  "A fixture repository needs real revisions: the pin question is answered by
  `git ls-tree`, and a fake sha would only prove that a missing revision is
  unmeasured -- which is a different claim from the one being tested."
  [dir & argv]
  (str/trim
   (str (cp/execSync (str "git -c user.email=selftest@example.invalid "
                          "-c user.name=selftest -c commit.gpgsign=false -C "
                          (pr-str dir) " " (str/join " " argv))
                     #js {:encoding "utf8" :stdio #js ["pipe" "pipe" "pipe"]}))))

(defn- fixture-run
  "Run the check over a synthetic tree and return [findings unmeasured]."
  [root scope]
  (reset! scan-root* root)
  (reset! repo-cache {})
  (reset! findings [])
  (reset! unmeasured [])
  (reset! pending-unaccounted [])
  (doseq [rel scope] (check-repo! root rel (p/join root rel)))
  (emit-unaccounted!)
  [(distinct-by-key @findings) (distinct-by-key @unmeasured)])

(defn selftest
  "The derivation must report the defect on a tree that has it and clear on
  the same tree once fixed. A check nobody has watched change colour has not
  been shown to depend on what it claims to measure."
  []
  (println "selftest — the derivation in both directions, on trees built here\n")

  ;; the reader, on the shapes that occur in this workspace
  (t! :reads-a-plain-libspec
      (= #{"kotobase.store"} (ns-requires "(ns a.b (:require [kotobase.store :as st]))"))
      "[kotobase.store :as st]")
  (t! :skips-option-values
      (= #{"a.b"} (ns-requires "(ns x (:require [a.b :as q :refer [c d] :refer-macros [e]]))"))
      "the vector after :refer is not a namespace")
  (t! :reads-both-arms-of-a-reader-conditional
      (= #{"a.one" "a.two"} (ns-requires "(ns x (:require #?(:clj [a.one] :cljs [a.two])))"))
      "#?(:clj [a.one] :cljs [a.two])")
  (t! :reads-a-prefix-list
      (= #{"clojure.string" "clojure.set"}
         (ns-requires "(ns x (:require [clojure [string :as s] [set :as t]]))"))
      "[clojure [string :as s] [set :as t]] is two namespaces, not `clojure`")
  (t! :drops-an-npm-require
      (= #{"clojure.string"}
         (ns-requires "(ns x (:require [\"@noble/hashes/sha2.js\" :refer [sha256]] [clojure.string :as s]))"))
      "a string spec is an npm package, not a namespace")
  (t! :reads-require-macros-and-use
      (= #{"a.m" "a.u"} (ns-requires "(ns x (:require-macros [a.m]) (:use [a.u]))"))
      ":require-macros and :use name namespaces too")
  (t! :ignores-import
      (= #{"a.b"} (ns-requires "(ns x (:require [a.b]) (:import [java.util Date UUID]))"))
      "a Java class is a different question")
  (t! :honours-discard
      (= #{"a.keep"} (ns-requires "(ns x (:require #_[a.gone] [a.keep]))"))
      "#_[a.gone] is not required")
  (t! :parens-in-a-docstring-do-not-move-the-form
      (= #{"a.b"} (ns-requires "(ns x \"doc with ( and ; and \\\" inside\" (:require [a.b]))"))
      "strings and comments are syntax, not text")
  (t! :no-ns-form-is-nil-not-empty
      (nil? (ns-requires "(defn f [] 1)"))
      "a file this reader did not understand is counted, not called clean")

  ;; the whole check, on a tree built for the purpose
  (let [root (p/join (os/tmpdir) (str "verify-declared-deps-selftest-" (js/Date.now)))
        lib (p/join root "orgs/kotoba-lang/libx")
        app (p/join root "orgs/kotoba-lang/appy")]
    (write! (p/join lib "deps.edn") "{:paths [\"src\"] :deps {}}")
    (write! (p/join lib "src/libx/store.cljc") "(ns libx.store)")
    (write! (p/join app "src/appy/core.cljc")
            "(ns appy.core (:require [libx.store :as s] [clojure.string :as str]))")

    ;; `:local/root` here rather than `:git/sha`, so these six tests measure
    ;; reachability and nothing else. A fake sha would make every one of them
    ;; ALSO report an unmeasured pin, and a test that fails for two reasons
    ;; discriminates neither. The pin question gets its own fixture below,
    ;; with real revisions.

    ;; 1. undeclared -> reported
    (write! (p/join app "deps.edn") "{:paths [\"src\"] :deps {}}")
    (let [[f u] (fixture-run root ["orgs/kotoba-lang/appy"])]
      (t! :finds-the-undeclared-require
          (and (empty? u) (= 1 (count f))
               (= "undeclared-require:orgs/kotoba-lang/appy:libx.store" (:key (first f)))
               (= "fail" (:sev (first f))))
          (str "`:deps {}` with src requiring libx.store → "
               (str/join ", " (map #(str (:sev %) " " (:key %)) f))
               (when (seq u) (str "; " (count u) " unmeasured")))))

    ;; 2. declared -> clean, on the SAME tree
    (write! (p/join app "deps.edn")
            "{:paths [\"src\"] :deps {io.github.kotoba-lang/libx {:local/root \"../libx\"}}}")
    (let [[f u] (fixture-run root ["orgs/kotoba-lang/appy"])]
      (t! :goes-clean-once-declared
          (and (empty? f) (empty? u))
          (str "the same tree with the coordinate declared → "
               (if (and (empty? f) (empty? u))
                 "no findings, nothing unmeasured"
                 (str (count f) " finding(s): " (str/join ", " (map :key f))
                      "; " (count u) " unmeasured")))))

    ;; 3. the opposite direction, and it does not borrow the other's key
    (write! (p/join root "orgs/kotoba-lang/liby/deps.edn") "{:paths [\"src\"] :deps {}}")
    (write! (p/join root "orgs/kotoba-lang/liby/src/liby/unused.cljc") "(ns liby.unused)")
    (write! (p/join app "deps.edn")
            (str "{:paths [\"src\"] :deps {io.github.kotoba-lang/libx {:local/root \"../libx\"}"
                 " io.github.kotoba-lang/liby {:local/root \"../liby\"}}}"))
    (let [[f u] (fixture-run root ["orgs/kotoba-lang/appy"])]
      (t! :finds-the-unused-declaration
          (and (empty? u) (= 1 (count f))
               (= "declared-unused:orgs/kotoba-lang/appy:io.github.kotoba-lang/liby"
                  (:key (first f)))
               (= "warn" (:sev (first f))))
          (str "an extra coordinate nothing requires → "
               (str/join ", " (map #(str (:sev %) " " (:key %)) f))))
      (t! :the-two-directions-do-not-share-a-key
          (not-any? #(str/starts-with? (:key %) "undeclared-require:") f)
          "`declared but unused` is never reported as `used but undeclared`"))

    ;; 4. a dependency with no checkout is unmeasured, not a pass
    (write! (p/join app "deps.edn")
            (str "{:paths [\"src\"] :deps {io.github.kotoba-lang/libx {:local/root \"../libx\"}"
                 " io.github.kotoba-lang/absent {:git/sha \"deadbeef\"}}}"))
    (let [[_ u] (fixture-run root ["orgs/kotoba-lang/appy"])]
      (t! :a-missing-checkout-is-unmeasured
          (and (= 1 (count u)) (str/starts-with? (:key (first u)) "unmeasured-provider:"))
          (str "a declared coordinate with no checkout → "
               (str/join ", " (map :key u)))))

    ;; 5. reachability really is transitive
    (write! (p/join lib "deps.edn")
            "{:paths [\"src\"] :deps {io.github.kotoba-lang/libz {:local/root \"../libz\"}}}")
    (write! (p/join root "orgs/kotoba-lang/libz/deps.edn") "{:paths [\"src\"] :deps {}}")
    (write! (p/join root "orgs/kotoba-lang/libz/src/libz/deep.cljc") "(ns libz.deep)")
    (write! (p/join app "src/appy/core.cljc")
            "(ns appy.core (:require [libx.store :as s] [libz.deep :as d]))")
    (write! (p/join app "deps.edn")
            "{:paths [\"src\"] :deps {io.github.kotoba-lang/libx {:local/root \"../libx\"}}}")
    (let [[f u] (fixture-run root ["orgs/kotoba-lang/appy"])]
      (t! :reachability-is-transitive
          (and (empty? u) (empty? f))
          (str "libz.deep, reached only through libx's own deps.edn → "
               (if (empty? f) "clean" (str/join ", " (map :key f))))))

    ;; 6. an unparseable deps.edn is unmeasured, never `no dependencies`
    (write! (p/join app "deps.edn") "{:paths [\"src\"] :deps {")
    (let [[_ u] (fixture-run root ["orgs/kotoba-lang/appy"])]
      (t! :an-unparseable-deps-edn-is-unmeasured
          (and (= 1 (count u)) (str/starts-with? (:key (first u)) "unreadable-repo:"))
          (str "a truncated deps.edn → " (str/join ", " (map :key u)))))

    ;; 7. an underscore in a path is a hyphen in a namespace
    (t! :path-to-namespace-follows-the-classpath
        (= "kotobase.protocols.ipfs-pinning"
           (path->ns "kotobase/protocols/ipfs_pinning.cljc"))
        "kotobase/protocols/ipfs_pinning.cljc → kotobase.protocols.ipfs-pinning")

    ;; ── 8. the pin question, on real revisions ─────────────────────────────
    ;; `net-kotobase/ipfs` in miniature: the coordinate is declared, the
    ;; checkout provides the namespace, and the revision named does not. Both
    ;; directions on one tree, with only the sha changing between them -- so
    ;; the colour cannot come from anything else.
    (let [glib (p/join root "orgs/kotoba-lang/gitlib")
          gapp (p/join root "orgs/kotoba-lang/gitapp")]
      (write! (p/join glib "deps.edn") "{:paths [\"src\"] :deps {}}")
      (write! (p/join glib "src/gitlib/one.cljc") "(ns gitlib.one)")
      (git! glib "init -q -b main")
      (git! glib "add -A")
      (git! glib "commit -q -m one")
      (let [sha1 (git! glib "rev-parse HEAD")]
        (write! (p/join glib "src/gitlib/two.cljc") "(ns gitlib.two)")
        (git! glib "add -A")
        (git! glib "commit -q -m two")
        (let [sha2 (git! glib "rev-parse HEAD")]
          (write! (p/join gapp "src/gitapp/core.cljc")
                  "(ns gitapp.core (:require [gitlib.one :as a] [gitlib.two :as b]))")

          (write! (p/join gapp "deps.edn")
                  (str "{:paths [\"src\"] :deps {io.github.kotoba-lang/gitlib "
                       "{:git/sha \"" sha1 "\"}}}"))
          (let [[f u] (fixture-run root ["orgs/kotoba-lang/gitapp"])]
            (t! :finds-the-namespace-the-pinned-revision-does-not-carry
                (and (empty? u) (= 1 (count f))
                     (= (str "pin-behind:orgs/kotoba-lang/gitapp:"
                             "io.github.kotoba-lang/gitlib:gitlib.two")
                        (:key (first f)))
                     (= "fail" (:sev (first f))))
                (str "pinned at the revision before gitlib.two landed → "
                     (str/join ", " (map #(str (:sev %) " " (:key %)) f))
                     (when (seq u) (str "; " (count u) " unmeasured"))))
            (t! :and-not-the-one-it-does-carry
                (not-any? #(str/ends-with? (:key %) ":gitlib.one") f)
                "gitlib.one is present at that revision and is not reported"))

          (write! (p/join gapp "deps.edn")
                  (str "{:paths [\"src\"] :deps {io.github.kotoba-lang/gitlib "
                       "{:git/sha \"" sha2 "\"}}}"))
          (let [[f u] (fixture-run root ["orgs/kotoba-lang/gitapp"])]
            (t! :clears-when-the-pin-moves-forward
                (and (empty? f) (empty? u))
                (str "the same tree, pin advanced one commit → "
                     (if (and (empty? f) (empty? u)) "clean"
                         (str (str/join ", " (map :key f)) "; "
                              (count u) " unmeasured")))))

          (write! (p/join gapp "deps.edn")
                  "{:paths [\"src\"] :deps {io.github.kotoba-lang/gitlib
                                             {:git/sha \"0000000000000000000000000000000000000000\"}}}")
          (let [[_ u] (fixture-run root ["orgs/kotoba-lang/gitapp"])]
            (t! :an-unfetched-revision-is-unmeasured-not-behind
                (and (= 1 (count u)) (str/starts-with? (:key (first u)) "unmeasured-pin:"))
                (str "a sha no object store holds → " (str/join ", " (map :key u))
                     " (a revision nobody fetched is not a pin that is wrong)"))))))

    (fs/rmSync root #js {:recursive true :force true}))

  (println)
  (if (pos? @selftest-fails)
    (do (println (str "  " @selftest-fails " selftest failure(s)")) 1)
    (do (println "  selftest clean — the derivation reports the defect and clears when fixed") 0)))

;; ── main ────────────────────────────────────────────────────────────────────

(defn -main [& args]
  (let [argv (vec args)]
    (reset! findings? (boolean (some #{"--findings"} argv)))
    (if (some #{"--selftest"} argv)
      (selftest)
      (let [scan-root (or (first (flag-values argv "--scan-root")) ".")
            _ (reset! scan-root* scan-root)
            only (flag-values argv "--only")
            extra (flag-values argv "--repo")
            patterns (if (seq only) only (into default-scope extra))
            asserted (set (if (seq only) only extra))
            scope (vec (distinct
                        (mapcat (fn [pat]
                                  (let [m (expand-pattern scan-root pat)]
                                    (when (and (empty? m) (asserted pat))
                                      (unmeasured! (str "no-such-repo:" pat)
                                                   (str (p/join scan-root pat)
                                                        " was named on the command line and is "
                                                        "not a directory")))
                                    m))
                                patterns)))
            results (mapv (fn [rel]
                            (assoc (check-repo! scan-root rel (p/join scan-root rel)) :rel rel))
                          scope)
            _ (emit-unaccounted!)
            edges (reduce + 0 (map :edges results))
            files (reduce + 0 (map :files results))]
        (println (str "declared dependencies vs what src actually requires  (scan-root "
                      scan-root ")\n"))
        ;; Evidence floor. The registry matches SCANNED\t[1-9][0-9]* before it
        ;; will believe an exit code, and zero requires examined is a detector
        ;; pointed at the wrong tree -- never a workspace with nothing wrong.
        (println (str "SCANNED\t" edges
                      "\texternal namespace requires from " files " source file(s) across "
                      (count scope) " repo(s) in scope; " (count @repo-cache)
                      " checkout(s) read in total (scope + dependency closures)"))
        (doseq [{:keys [rel edges closure]} results]
          (println (str "  " rel "\t" edges " external require(s), closure of "
                        (or closure 0) " checkout(s)")))
        (when (zero? edges)
          (unmeasured! :nothing-scanned
                       (str "0 external requires across " (count scope)
                            " repo(s) — a scan-root with no source in it, not a workspace "
                            "that agrees")))
        (println)
        (let [fs* (sort-by :key (distinct-by-key @findings))
              um (sort-by :key (distinct-by-key @unmeasured))]
          (doseq [{:keys [sev key detail]} fs*]
            (println (str "  " (if (= "fail" sev) "FAIL" "warn") " " key "\n        " detail))
            (when @findings?
              (println (str "FINDING\t" sev "\t" key "\t" detail))))
          (when (seq um) (println))
          (doseq [{:keys [key detail]} um]
            (println (str "  ?    " key "\n        " detail)))
          (println)
          (cond
            (seq um)
            (do (println (str "  REFUSING to report agreement: " (count um)
                              " thing(s) could not be examined"))
                2)
            (seq fs*)
            (do (println (str "  " (count (filter #(= "fail" (:sev %)) fs*))
                              " used-but-undeclared / pin-behind finding(s), "
                              (count (filter #(= "warn" (:sev %)) fs*))
                              " declared-but-unused / unmeasured-provider warning(s)"))
                1)
            :else
            (do (println (str "  clean — every require in scope is reachable from a declared "
                              "coordinate at its pinned revision, and every declared "
                              "coordinate is used"))
                0)))))))

(let [code (apply -main *command-line-args*)]
  (set! (.-exitCode js/process) code))

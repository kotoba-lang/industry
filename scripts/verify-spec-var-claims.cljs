#!/usr/bin/env nbb
;; verify-spec-var-claims — spec files that name a var, checked against the
;; branch other people actually get.
;;
;; ## What it answers
;;
;; A spec EDN in this workspace names implementations by qualified symbol —
;; `:operation "kotoba.value.codec/value-cid"`, `:entry
;; "kotoba.compiler.cli/-main"`, `:requires-wrapper ...` — and sits next to a
;; `:status :implemented`. Nothing resolved those names.
;;
;; The interesting failure is NOT "the name is wrong". It is that resolving it
;; **in the working tree** and resolving it **on the default branch** give
;; different answers, and no output distinguishes them. Measured 2026-08-20 on
;; `kotoba-lang/kotoba-lang/lang/*.edn`: 14 claims, of which the working tree
;; resolved 12 and the default branches resolved 10. The two that differ:
;;
;;   kotoba.value.codec/value-cid         only in the working tree
;;   kotoba.value.codec/verify-value-cid  only in the working tree
;;
;; Both exist solely as an uncommitted 27-line addition in one shared checkout
;; — another session's in-flight work — while `lang/value-codec.edn` declares
;; them `:status :implemented` under `:kotoba.lang.value-codec/logical-address`.
;; A person checking locally sees them resolve. A fresh clone does not have
;; them. That gap is what this detector is for, and it is why every check here
;; reads `git show <default-ref>:<path>` rather than the file on disk.
;;
;; A third class the same run found: `kotoba.release-evidence/v2`, whose
;; namespace file exists in a checkout that is 197 commits behind and is not on
;; that repo's main at all. Stale checkouts and dirty checkouts produce the same
;; symptom — a name that resolves for you and for nobody else.
;;
;; ## Why namespace-to-path is not a heuristic here
;;
;; Both Clojure and nbb resolve `a.b-c/f` by loading `a/b_c.clj[c|s]` from the
;; classpath. A namespace that is not at its path cannot be required, so
;; checking that path is the same rule the runtime uses, not an approximation
;; of it.
;;
;; ## Do not reach for shell grep here
;;
;; `grep` on this machine is ugrep, which reads `(` as a group opener, so
;; `grep "^(ns foo.bar"` matches nothing and reports every namespace absent.
;; That is how the first draft of this measurement concluded all four claims
;; were missing. Every pattern below is a JS regex, evaluated in-process.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-spec-var-claims.cljs [--findings]
;;
;; Exit: 0 clean, 1 findings, 2 could not answer (no claims parsed, or no
;; default ref resolvable — never reported as clean).

(require '["fs" :as fs]
         '["path" :as path]
         '["child_process" :as cp]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def argv (vec (drop 2 (js->clj js/process.argv))))
(def findings-mode? (some #{"--findings"} argv))

(def spec-dirs
  "Where spec EDN lives. A directory that is not checked out is skipped and
  counted, never silently treated as empty."
  ["orgs/kotoba-lang/kotoba-lang/lang"])

(defn sh [& args]
  (try (str/trim (.toString (cp/execSync (str/join " " args)
                                         #js {:stdio #js ["pipe" "pipe" "pipe"]})))
       (catch :default _ nil)))

(defn finding! [severity key detail]
  (when findings-mode?
    (println (str "FINDING\t" severity "\t" key "\t" detail))))

;; ── claims ───────────────────────────────────────────────────────────────────

(def qualified-symbol-re
  ;; `ns/var` where ns has at least one dot. Deliberately narrow: a bare
  ;; `foo/bar` is far more often a repo path than a var, and a detector that
  ;; guesses produces findings nobody can act on.
  #"^[a-z][a-z0-9.*+!_?-]*\.[a-z0-9.*+!_?-]+/[a-zA-Z0-9*+!_?<>=-]+$")

(def dependency-coordinate-re
  ;; A git/Maven coordinate has the SHAPE of a qualified symbol and is not one.
  ;; `io.github.kotoba-lang/io-ipld` reached the first version of this detector
  ;; as an NS-MISSING finding: correct that no namespace has that path, useless
  ;; as a report. Every git dep in this workspace is `io.github.<org>/<repo>`,
  ;; so the prefix decides it without guessing.
  #"^(io|com|org|net)\.github\.")

(def coordinate-keys
  "Spec keys whose value is a dependency, not an implementation."
  #{:lib :dep :deps :coordinate :artifact})

(defn collect-claims
  "Every qualified-symbol-shaped string in `form`, with the key that carried it."
  [form file out]
  (cond
    (map? form)
    (doseq [[k v] form]
      (when (and (string? v)
                 (re-find qualified-symbol-re v)
                 (not (re-find dependency-coordinate-re v))
                 (not (contains? coordinate-keys k)))
        (swap! out conj {:file file :key k :claim v}))
      (collect-claims v file out))
    (coll? form) (doseq [x form] (collect-claims x file out))
    :else nil))

;; ── resolution ───────────────────────────────────────────────────────────────

(defn ns->path [ns] (-> ns (str/replace "." "/") (str/replace "-" "_")))

(defn def-re [v]
  (re-pattern (str "(?m)^\\((?:defn-?|def|defmacro|defrecord|deftype|defprotocol|definterface)\\s+"
                   "(?:\\^\\S+\\s+)?"
                   (str/replace v #"([.*+?^${}()|\[\]\\-])" "\\$1")
                   "[\\s)]")))

(def repos
  (delay
    (vec (for [org (try (fs/readdirSync "orgs") (catch :default _ []))
               r   (try (fs/readdirSync (path/join "orgs" org)) (catch :default _ []))
               :when (fs/existsSync (path/join "orgs" org r "src"))]
           (path/join "orgs" org r)))))

(defn default-ref
  "The remote-tracking default branch of `repo`, or nil.

  A west checkout names its remote by ORG, not `origin` — measured on
  `io-ipld`, whose only remote is `kotoba-lang`. Assuming `origin` is how a
  guard elsewhere in this workspace silently skipped 64% of checkouts, so the
  remotes are read rather than guessed, and nil is returned rather than a
  fallback to the working tree."
  [repo]
  (or (some-> (sh "git -C" repo "symbolic-ref -q refs/remotes/origin/HEAD")
              (str/replace "refs/remotes/" ""))
      (some (fn [remote]
              (let [r (str remote "/main")]
                (when (sh "git -C" repo "rev-parse --verify -q" r) r)))
            (some-> (sh "git -C" repo "remote") (str/split #"\n")))))

(def ref-cache (atom {}))
(defn ref-for [repo]
  (if (contains? @ref-cache repo)
    (get @ref-cache repo)
    (let [r (default-ref repo)] (swap! ref-cache assoc repo r) r)))

(defn locate
  "Checkouts carrying `ns`'s path.

  Located by the WORKING TREE, then verified against the default ref. The
  asymmetry is deliberate and is this detector's one blind spot: a namespace
  that exists on a repo's main but is missing from a stale checkout is
  reported as `ns-missing`, indistinguishable from one that exists nowhere.
  Locating by ref instead would mean an `ls-tree` per repository -- measured
  at roughly 4,400 of them -- on every run. The blind spot is stated in the
  finding text rather than hidden, and it under-reports rather than passing
  something bad."
  [ns]
  (let [p (ns->path ns)]
    (vec (for [repo @repos
               ext ["cljc" "clj" "cljs"]
               :let [rel (str "src/" p "." ext)]
               :when (fs/existsSync (path/join repo rel))]
           {:repo repo :rel rel :ref (ref-for repo) :in-tree? true}))))

(defn defined-in-tree? [{:keys [repo rel]} v]
  (try (boolean (re-find (def-re v) (fs/readFileSync (path/join repo rel) "utf8")))
       (catch :default _ false)))

(defn defined-on-ref? [{:keys [repo rel ref]} v]
  (when ref
    (some-> (sh "git -C" repo "show" (str ref ":" rel))
            (->> (re-find (def-re v))) boolean)))

;; ── run ──────────────────────────────────────────────────────────────────────

(def claims (atom []))
(def missing-dirs (atom 0))

(doseq [d spec-dirs]
  (if-not (fs/existsSync d)
    (swap! missing-dirs inc)
    (doseq [f (fs/readdirSync d) :when (str/ends-with? f ".edn")]
      (let [fp (path/join d f)]
        (try (collect-claims (edn/read-string (fs/readFileSync fp "utf8")) fp claims)
             (catch :default e
               (finding! "high" (str "unreadable:" fp)
                         (str "spec file does not parse, so its claims were not checked: "
                              (.-message e)))))))))

(def all (distinct @claims))

(when (or (empty? all) (pos? @missing-dirs))
  (println (str "SCANNED\t0\tclaim(s)"))
  (println (str "verify-spec-var-claims: refusing to report a pass -- "
                (if (pos? @missing-dirs)
                  (str @missing-dirs " spec dir(s) not checked out")
                  "no claims parsed")
                ". Absence of claims is not absence of drift."))
  (js/process.exit 2))

(def results
  (vec (for [{:keys [file key claim]} all]
         (let [[ns v] (str/split claim #"/" 2)
               sites  (locate ns)
               on-ref (filter #(defined-on-ref? % v) sites)
               in-tree (filter #(defined-in-tree? % v) sites)]
           {:file file :key key :claim claim
            :sites sites :on-ref on-ref :in-tree in-tree}))))

(def no-ref (remove #(some :ref (:sites %)) (filter #(seq (:sites %)) results)))

(doseq [{:keys [file key claim on-ref in-tree sites]} results]
  (cond
    (seq on-ref)
    nil                                  ; resolved where everyone can see it

    (seq in-tree)
    (finding! "high" (str "working-tree-only:" claim)
              (str claim " is named by " (path/basename file) " " key
                   " but exists only in a working tree ("
                   (str/join ", " (map :repo in-tree))
                   "), not on its default branch -- it is uncommitted or the"
                   " checkout is ahead. A fresh clone does not have it."))

    (seq sites)
    (finding! "high" (str "var-missing:" claim)
              (str claim " is named by " (path/basename file) " " key
                   "; the namespace file exists ("
                   (str/join ", " (map :repo sites))
                   ") but nothing defines that var, in the tree or on the ref."))

    :else
    (finding! "medium" (str "ns-missing:" claim)
              (str claim " is named by " (path/basename file) " " key
                   " and no checked-out repo has src/" (ns->path (first (str/split claim #"/")))
                   ".clj[c|s]. Either it moved, or the checkout carrying it is"
                   " not present -- this detector cannot tell those apart."))))

(doseq [{:keys [claim sites]} no-ref]
  (finding! "medium" (str "no-default-ref:" claim)
            (str "no remote-tracking default branch resolved for "
                 (str/join ", " (map :repo sites))
                 ", so this claim was checked against the working tree only.")))

(def unresolved (remove #(seq (:on-ref %)) results))

(println (str "SCANNED\t" (count all) "\tclaim(s) in " (count spec-dirs) " spec dir(s)"))
(println (str "verify-spec-var-claims: " (- (count all) (count unresolved)) "/" (count all)
              " resolve on a default branch"
              (when (seq unresolved)
                (str "; " (count (filter #(seq (:in-tree %)) unresolved))
                     " resolve ONLY in a working tree"))))

(when-not findings-mode?
  (doseq [{:keys [claim in-tree sites]} unresolved]
    (println (str "  " (if (seq in-tree) "WORKING-TREE-ONLY" (if (seq sites) "VAR-MISSING     " "NS-MISSING      "))
                  "  " claim))))

(js/process.exit (if (seq unresolved) 1 0))

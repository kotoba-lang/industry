#!/usr/bin/env nbb
;; jvm-new-surface-policy — what may NEWLY appear in scaffold / deps edits.
;;
;; ADR-2608170200 / cutover `:new-jvm-runtime-dependencies :forbidden` already
;; forbid growing the JVM surface. Detector `verify-jvm-dependency-surface`
;; reports standing debt; this module answers the admission question for a
;; single Write / Edit / commit: "does THIS change add a new third-party JVM
;; runtime dep or a new production `.clj`?"
;;
;; Existing debt is out of scope. Editing a file that already exists at HEAD
;; is allowed. Only NEW production `.clj` paths and NEW top-level leaf
;; coordinates (maven / external-git, excluding clojure itself and
;; lint/test/build tool coords) are refused.
;;
;; CLI:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/jvm_new_surface_policy.cljs \
;;     check-deps --before <old.edn> --after <new.edn>
;;   nbb … scripts/jvm_new_surface_policy.cljs check-clj --path <rel> [--new]
;;   nbb … scripts/jvm_new_surface_policy.cljs self-test
(ns jvm-new-surface-policy
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as node-path]))

(def lint-coords #{"clj-kondo/clj-kondo"})
(def test-coords #{"io.github.cognitect-labs/test-runner" "lambdaisland/kaocha"
                   "cloverage/cloverage" "org.clojure/test.check"})
(def build-coords #{"thheller/shadow-cljs" "org.clojure/clojurescript"
                    "io.github.clojure/tools.build" "com.thheller/shadow-css"})

(def workspace-orgs
  #{"kotoba-lang" "cloud-itonami" "com-junkawasaki" "etzhayyim" "gftdcojp"
    "network-awai" "net-kotobase" "jk-luxury" "kawasakijun" "personal"})

(defn test-path?
  "Same predicate as verify-jvm-dependency-surface (test_ prefix included)."
  [p]
  (let [p (str/replace (str p) #"\\" "/")]
    (or (re-find #"(^|/)(test|tests)/" p)
        (re-find #"_test\.[a-z]+$" p)
        (re-find #"(^|/)test_[a-z0-9_]*\.[a-z]+$" p)
        (re-find #"(^|/)dev/" p))))

(defn production-clj-path?
  "A path that would count as :jvm-source if newly created."
  [p]
  (let [p (str/replace (str p) #"\\" "/")]
    (and (str/ends-with? p ".clj")
         (not (test-path? p))
         ;; hooks / scripts / tools under the superproject root are nbb hosts,
         ;; not JVM production libraries — still prefer .cljs, but do not block
         ;; legacy script names that agents already touch.
         (not (re-find #"(^|/)(\.claude|scripts|tools|bin)/" p)))))

(defn- unquote-arg
  "Strip one layer of matching shell quotes. `\"/a b\"` -> `/a b`."
  [s]
  (let [s (str/trim (str s))
        n (count s)]
    (if (and (>= n 2)
             (or (and (str/starts-with? s "\"") (str/ends-with? s "\""))
                 (and (str/starts-with? s "'") (str/ends-with? s "'"))))
      (subs s 1 (dec n))
      s)))

(defn- literal-path?
  "False when the shell would produce something other than these bytes:
  an unexpanded variable, command substitution, a glob, or `~`."
  [p]
  (not (re-find #"[$`*?~]|\$\{" (str p))))

(defn target-dir
  "Which git repository does `cmd` act on?

  `git -C <dir>` wins. Otherwise the LAST `cd` that runs before the git
  command, separated by `&&`, `;` or a newline, with the path bare,
  \"double\" or 'single' quoted.

  Returns `\".\"` when the command never changes directory, and **nil when it
  does but the destination is not literal** (`$VAR`, a glob, `~`). nil means
  `I could not tell`, and callers must not read it as `\".\"`: this guard
  answers a question about one repository, and answering it about a different
  one is wrong in both directions — a clean tree hides a real violation, and
  a busy tree denies a commit that adds nothing.

  Measured 2026-08-30: the previous regex accepted only `^cd <bare> &&`, so
  `cd /a/b` on its own line, and `cd \"$SP/x\" &&`, both silently fell back to
  `\".\"` — the west superproject — and denied commits in child repos for
  dependencies belonging to neither."
  [cmd]
  (let [cmd (str cmd)]
    (if-let [c (second (re-find #"\bgit\s+-C\s+(\"[^\"]*\"|'[^']*'|\S+)" cmd))]
      (unquote-arg c)
      (let [cds (->> (re-seq #"(?:^|&&|;|\n)\s*cd\s+(\"[^\"]*\"|'[^']*'|[^\s;&|]+)" cmd)
                     (map second))]
        (if (empty? cds)
          "."
          (let [dir (unquote-arg (last cds))]
            (when (literal-path? dir) dir)))))))

(defn commit-includes-unstaged?
  "`git commit -a` also commits tracked files that were never staged."
  [cmd]
  (boolean (re-find #"\bcommit\b[^\n]*?(?:\s-[a-zA-Z]*a|\s--all\b)" (str cmd))))

(defn- coord-org [sym v]
  (or (second (re-find #"github\.com[:/]([^/]+)/" (str (:git/url v ""))))
      (second (re-find #"^(?:io|com|net)\.github\.([^/]+)/" (str sym)))))

(defn- coord-kind [sym v]
  (cond
    (not (map? v)) :unknown
    (contains? v :mvn/version) :maven
    (contains? v :local/root) :local
    (or (contains? v :git/url) (contains? v :git/sha) (contains? v :git/tag))
    (if (workspace-orgs (coord-org sym v)) :workspace-git :external-git)
    :else :unknown))

(defn top-level-leaves
  "Coordinate strings in the top-level `:deps` map that are JVM leaves
   (maven or external-git)."
  [form]
  (when (map? form)
    (into (sorted-set)
          (for [[sym v] (:deps form)
                :when (symbol? sym)
                :let [k (coord-kind (str sym) v)]
                :when (contains? #{:maven :external-git} k)]
            (str sym)))))

(defn runtime-third-party
  "Leaves that grow `:jvm-runtime-deps` (third-party), not clojure/tool pins."
  [leaves]
  (into (sorted-set)
        (remove #(or (lint-coords %)
                     (test-coords %)
                     (build-coords %)
                     (= "org.clojure/clojure" %))
                leaves)))

(defn parse-deps
  "Read deps.edn text. Returns map or ::unparsed."
  [text]
  (try
    (let [form (edn/read-string {:default (fn [_ v] v)} (or text ""))]
      (if (map? form) form ::unparsed))
    (catch :default _ ::unparsed)))

(defn new-runtime-third-party
  "Coords present in AFTER top-level :deps as third-party leaves but not in BEFORE."
  [before-text after-text]
  (let [b (parse-deps before-text)
        a (parse-deps after-text)]
    (cond
      (= ::unparsed a) {:error :after-unparsed}
      (= ::unparsed b)
      ;; New deps.edn file: every third-party leaf is new.
      {:added (runtime-third-party (top-level-leaves a))}
      :else
      (let [before-tp (runtime-third-party (top-level-leaves b))
            after-tp (runtime-third-party (top-level-leaves a))]
        {:added (into (sorted-set) (remove before-tp after-tp))}))))

(defn deny-new-production-clj?
  "True when PATH is a production .clj that is being created (not an edit)."
  [path new-file?]
  (and new-file? (production-clj-path? path)))

(defn deny-reason-deps [added]
  (str "New top-level JVM runtime dependency forbidden "
       "(cutover :new-jvm-runtime-dependencies :forbidden / ADR-2608201300):\n  "
       (str/join "\n  " added)
       "\nUse a workspace git dep, portable .cljc/.cljs/.kotoba, or put a tool "
       "coord only under an alias — not top-level :deps."))

(defn deny-reason-clj [path]
  (str "New production .clj forbidden (ADR-2608201300 / runtime priority): "
       path
       "\nWrite .cljc / .cljs / .kotoba instead. Existing .clj may still be edited; "
       "test/ and *_test.clj remain allowed."))

(defn- assert! [label pred]
  (when-not pred
    (binding [*out* *err*]
      (println "FAIL" label))
    (.exit js/process 1))
  (println "ok" label))

(defn- self-test! []
  (assert! "test-path test/" (test-path? "src/foo/bar_test.clj"))
  (assert! "test-path test_ prefix" (test-path? "src/foo/test_runner.clj"))
  (assert! "prod clj" (production-clj-path? "src/foo/core.clj"))
  (assert! "not prod test" (not (production-clj-path? "test/foo/core.clj")))
  (assert! "deny new prod" (deny-new-production-clj? "src/a/b.clj" true))
  (assert! "allow edit prod" (not (deny-new-production-clj? "src/a/b.clj" false)))
  (let [before "{:deps {org.clojure/clojure {:mvn/version \"1.12.0\"}}}"
        after  "{:deps {org.clojure/clojure {:mvn/version \"1.12.0\"}
                        cheshire/cheshire {:mvn/version \"5.13.0\"}}}"
        {:keys [added]} (new-runtime-third-party before after)]
    (assert! "detects cheshire" (= #{"cheshire/cheshire"} added)))
  (let [before "{:deps {org.clojure/clojure {:mvn/version \"1.12.0\"}}}"
        after  "{:deps {org.clojure/clojure {:mvn/version \"1.12.0\"}
                        io.github.kotoba-lang/fs {:git/url \"https://github.com/kotoba-lang/fs.git\"
                                                 :git/sha \"abc\"}}}"
        {:keys [added]} (new-runtime-third-party before after)]
    (assert! "workspace git ok" (empty? added)))
  (let [after "{:deps {org.clojure/clojure {:mvn/version \"1.12.0\"}}
               :aliases {:lint {:replace-deps {clj-kondo/clj-kondo {:mvn/version \"2024.01.01\"}}}}}"
        {:keys [added]} (new-runtime-third-party nil after)]
    (assert! "alias lint not top-level" (empty? added)))
  ;; target-dir — the parsing that decides WHICH repo is measured.
  (assert! "no cd -> ." (= "." (target-dir "git commit -m x")))
  (assert! "cd && bare" (= "/a/b" (target-dir "cd /a/b && git commit -m x")))
  (assert! "cd on its own line"
           (= "/a/b" (target-dir "cd /a/b\ngit add -A\ngit commit -m x")))
  (assert! "cd double-quoted" (= "/a b" (target-dir "cd \"/a b\" && git commit")))
  (assert! "cd single-quoted" (= "/a b" (target-dir "cd '/a b' && git commit")))
  (assert! "semicolon separated" (= "/a/b" (target-dir "cd /a/b ; git commit")))
  (assert! "last cd wins" (= "/second" (target-dir "cd /first && cd /second && git commit")))
  (assert! "git -C beats cd" (= "/c" (target-dir "cd /a && git -C /c commit")))
  (assert! "git -C quoted" (= "/c d" (target-dir "git -C \"/c d\" commit")))
  (assert! "unexpanded var -> nil" (nil? (target-dir "cd \"$SP/x\" && git commit")))
  (assert! "braced var -> nil" (nil? (target-dir "cd ${SP}/x && git commit")))
  (assert! "tilde -> nil" (nil? (target-dir "cd ~/x && git commit")))
  (assert! "glob -> nil" (nil? (target-dir "cd /a/*/b && git commit")))
  (assert! "commit -a" (commit-includes-unstaged? "git commit -am x"))
  (assert! "commit --all" (commit-includes-unstaged? "git commit --all -m x"))
  (assert! "plain commit" (not (commit-includes-unstaged? "git commit -m x")))
  (assert! "-m is not -a" (not (commit-includes-unstaged? "git commit -m 'add all files'")))
  (println "self-test passed"))

(defn -main [& args]
  (let [[cmd & rest] args]
    (case cmd
      "self-test" (self-test!)
      "check-clj"
      (let [opts (apply hash-map rest)
            path (get opts "--path")
            new? (contains? (set rest) "--new")]
        (when (deny-new-production-clj? path new?)
          (binding [*out* *err*] (println (deny-reason-clj path)))
          (.exit js/process 1))
        (println "ok"))
      "check-deps"
      (let [opts (apply hash-map rest)
            before (when-let [p (get opts "--before")]
                     (when (.existsSync fs p) (.readFileSync fs p "utf8")))
            after (.readFileSync fs (get opts "--after") "utf8")
            {:keys [added error]} (new-runtime-third-party before after)]
        (when error
          (binding [*out* *err*] (println "unparsed deps.edn"))
          (.exit js/process 2))
        (when (seq added)
          (binding [*out* *err*] (println (deny-reason-deps added)))
          (.exit js/process 1))
        (println "ok"))
      (do (println "usage: self-test | check-clj --path <p> [--new] | check-deps --before <f> --after <f>")
          (.exit js/process 2)))))

(let [argv (js->clj js/process.argv)
      idx (first (keep-indexed
                  (fn [i a]
                    (when (str/ends-with? (str a) "jvm_new_surface_policy.cljs") i))
                  argv))]
  (when idx
    (apply -main (drop (inc idx) argv))))

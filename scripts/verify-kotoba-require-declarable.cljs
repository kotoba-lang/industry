#!/usr/bin/env nbb
(ns verify-kotoba-require-declarable
  "Files that require a kotoba.lang.* namespace with nowhere to declare it.

  ## Why this exists

  clojure.string resolves for free: it ships with Clojure, with ClojureScript
  and with babashka. kotoba.lang.text does not. So the two are NOT
  interchangeable in one specific way that no source-level diff shows -- a file
  with no project file above it runs fine on clojure.string and cannot run at
  all on kotoba.lang.text.

  Measured 2026-09-09, and the reason this detector exists: the clojure.string
  migration rewrote 91 files across 14 repos that were in exactly that state.
  37 were `#!/usr/bin/env bb` scripts and 5 were nbb scripts, invoked directly,
  in repos with no deps.edn, bb.edn, package.json, shadow-cljs.edn or nbb.edn
  anywhere above them. One of them, m365-archive's bin/gpg-unlock.cljc, is what
  lets git-annex push that archive non-interactively. All 91 have been put
  back, and the migration tool now asks the question per FILE rather than per
  REPO -- but nothing in the tree was checking the property itself, and the
  property outlives that one tool.

  ## What is reported

    :warn   a file requires kotoba.lang.<x> and has NO deps.edn or bb.edn
            anywhere above it. It cannot resolve, whatever else is true.

    :info   a file requires kotoba.lang.<x>, has a project file above it, and
            that file does not name io.github.kotoba-lang/<x>. Often fine --
            the dependency can arrive transitively, or through :local/root on
            a sibling -- so this is a number to watch, not a defect.

  A file INSIDE the library it requires is exempt: kotoba-lang/text's own tests
  require kotoba.lang.text and need no declaration to do it.

  Exit: 0 clean, 1 findings, 2 REFUSED (could not measure).

    nbb --classpath \".:scripts/nbb_compat\" scripts/verify-kotoba-require-declarable.cljs [--findings] [--root DIR]"
  (:require [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def path-mod (js/require "node:path"))
(def cp (js/require "node:child_process"))

(def argv (vec (drop 2 (js->clj js/process.argv))))
(def findings-mode? (some #{"--findings"} argv))
(def root (or (second (drop-while #(not= "--root" %) argv)) "."))

(defn- rg [& args]
  (let [r (.spawnSync cp "rg" (clj->js args)
                      #js {:cwd root :encoding "utf8" :maxBuffer 268435456})]
    (when (<= (.-status r) 1) (or (.-stdout r) ""))))

(defn- project-file [d]
  (some (fn [n] (let [c (.join path-mod d n)]
                  (when (try (.isFile (.statSync fs c)) (catch :default _ false)) c)))
        ["deps.edn" "bb.edn"]))

(defn- nearest-project
  "The deps.edn or bb.edn nearest above this file, WITHOUT leaving its repo.

  The first version walked to the filesystem root, found this superproject's own
  deps.edn above every file in orgs/, and reported the whole workspace clean --
  a check that answered a different question than the one it was named for. It
  was caught by planting a file that was known to be unresolvable and watching
  the detector not report it."
  [file stop-at]
  (let [stop (.resolve path-mod (.join path-mod root stop-at))]
    (loop [d (.dirname path-mod (.resolve path-mod (.join path-mod root file)))]
      (or (project-file d)
          (when (and (not= d stop) (not= d (.dirname path-mod d)))
            (recur (.dirname path-mod d)))))))

;; orgs/<org>/<repo>/...
(defn- repo-of [file]
  (let [ps (str/split file #"/")]
    (when (and (= "orgs" (first ps)) (>= (count ps) 3))
      [(nth ps 1) (nth ps 2)])))

(defn- sh [args]
  (let [r (.spawnSync cp (first args) (clj->js (rest args))
                      #js {:encoding "utf8" :maxBuffer 67108864})]
    {:status (.-status r) :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))

(defn- repo-ref
  "The repo's last-fetched default branch, or nil.

  A west checkout names its remote after the ORG, not origin -- CLAUDE.md
  records that this left 64% of a deploy guard's population unchecked -- so the
  remote is matched against the repo's own path rather than assumed."
  [org repo]
  (let [p (.join path-mod root "orgs" org repo)
        rem (->> (str/split-lines (:out (sh ["git" "-C" p "remote" "-v"])))
                 (filter #(str/includes? % "(fetch)"))
                 (filter #(re-find (re-pattern (str "github\\.com[:/]" org "/" repo "(\\.git)?\\s")) %))
                 (map #(first (str/split % #"\s"))) first)]
    (when rem
      (first (for [b ["main" "master"]
                   :let [r (str "refs/remotes/" rem "/" b)]
                   :when (zero? (:status (sh ["git" "-C" p "rev-parse" "--verify" "-q" r])))]
               [p (str rem "/" b)])))))

(defn- still-orphan-at-main?
  "Whether the finding survives at the repo's main.

  The working tree is where this detector reads, and this workspace's checkouts
  are routinely behind: measured 2026-09-09, a sweep that repaired 50 repos on
  main left every one of them still reporting here, because the local checkout
  had not moved. So a local verdict is a suspicion and the repo's own main
  decides -- the same two-stage shape verify-git-dep-pin-reachability needed for
  the same reason. A repo whose ref cannot be resolved is :unverified, which is
  neither clean nor a finding."
  [org repo rel lib]
  (if-let [[p ref] (repo-ref org repo)]
    (let [g (sh ["git" "-C" p "grep" "-l" "-E" (str "kotoba\\.lang\\." lib) ref "--" rel])]
      (cond
        (not (zero? (:status g))) :gone            ; the file no longer has it at main
        :else
        (let [decl (->> (str/split-lines (:out (sh ["git" "-C" p "ls-tree" "-r" "--name-only" ref])))
                        (filter #(re-find #"(^|/)(deps|bb)\.edn$" %))
                        (map #(let [d (.dirname path-mod %)] (if (= d "") "." d)))
                        set)]
          (loop [d (.dirname path-mod rel)]
            (cond
              (contains? decl d) :declared
              (= d ".")          :orphan
              :else              (recur (.dirname path-mod d)))))))
    :unverified))

(defn -main []
  (let [out (rg "-n" "--no-heading" "--no-messages"
                "-g" "*.clj" "-g" "*.cljc" "-g" "*.cljs"
                "--glob" "!node_modules" "--glob" "!target" "--glob" "!out" "--glob" "!dist"
                "-e" "kotoba\\.lang\\.[a-z-]+" "orgs")]
    (when (nil? out)
      (println "REFUSED: ripgrep did not run to completion; nothing was measured.")
      (js/process.exit 2))
    (let [lines (remove str/blank? (str/split-lines out))
          ;; one row per (file, library), from a REQUIRE or a qualified call --
          ;; a prose mention is not a dependency, which is the same distinction
          ;; the migration tool had to learn.
          rows (distinct
                (for [l lines
                      :let [[f & rest'] (str/split l #":")
                            body (str/join ":" (drop 1 rest'))]
                      ;; A KEYWORD IS NOT A REQUIRE. `:kotoba.lang.surface-status/as-of`
                      ;; is a map key, and matching it reported plugin-hermes as
                      ;; unable to resolve a namespace it never asks for. The
                      ;; libspec form is unambiguous; the qualified-call form has
                      ;; to exclude a preceding colon.
                      m (re-seq #"\[kotoba\.lang\.([a-z][a-z0-9-]*)[\s\]]|(?:^|[^:a-zA-Z0-9._-])kotoba\.lang\.([a-z][a-z0-9-]*)/" body)
                      :let [lib (or (nth m 1) (nth m 2))]
                      :when lib]
                  [f lib]))
          rows (vec rows)]
      (when (< (count rows) 50)
        (println (str "REFUSED: only " (count rows) " kotoba.lang.* uses found under orgs/;"
                      " this workspace has thousands, so the walk did not run."))
        (js/process.exit 2))
      (let [judged (for [[f lib] rows
                         :let [[org repo] (repo-of f)]
                         ;; a file inside the library it requires needs nothing
                         :when (not (and (= "kotoba-lang" org) (= lib repo)))
                         :let [dp (nearest-project f (str "orgs/" org "/" repo))]]
                     (cond
                       (nil? dp) [:unresolvable f lib nil]
                       :else
                       (let [txt (try (str (.readFileSync fs dp "utf8")) (catch :default _ ""))]
                         (if (or (str/includes? txt (str "kotoba-lang/" lib " "))
                                 (str/includes? txt (str "kotoba-lang/" lib "\n"))
                                 (str/includes? txt (str "kotoba-lang/" lib "{"))
                                 (str/includes? txt (str "/" lib "\"")))
                           [:declared f lib dp]
                           [:undeclared f lib dp]))))
            judged (vec judged)
            by (group-by first judged)
            suspect (vec (:unresolvable by))
            checked (for [[_ f lib _] suspect
                          :let [[org repo] (repo-of f)
                                rel (str/join "/" (drop 3 (str/split f #"/")))]]
                      [(still-orphan-at-main? org repo rel lib) f lib])
            bad  (vec (filter #(= :orphan (first %)) checked))
            unv  (vec (filter #(= :unverified (first %)) checked))
            gone (vec (filter #(#{:gone :declared} (first %)) checked))]
        (println (str "SCANNED\t" (count rows) "\tfile/library uses of kotoba.lang.* under orgs/; "
                      (count suspect) " looked unresolvable in the checkout and were"
                      " re-asked of each repo's main"))
        (if findings-mode?
          (do
            (doseq [[_ f lib] bad]
              (println (str "FINDING\twarn\t" f ":" lib "\t" f " requires kotoba.lang." lib
                            " and has no deps.edn or bb.edn anywhere above it;"
                            " clojure.string would have resolved for free, this cannot")))
            (when (seq unv)
              (println (str "FINDING\tinfo\tunverified\t" (count unv)
                            " suspect(s) could not be checked against their repo's main"
                            " -- UNVERIFIED, which is neither clean nor a finding")))
            (when (seq gone)
              (println (str "FINDING\tinfo\tstale-checkout\t" (count gone)
                            " suspect(s) looked unresolvable only because the local"
                            " checkout is behind that repo's main")))
            (println (str "FINDING\tinfo\tundeclared\t" (count (:undeclared by))
                          " use(s) have a project file above them that does not name the"
                          " library -- often fine (transitive, or :local/root on a sibling),"
                          " so a number to watch rather than a defect")))
          (do
            (println)
            (if (seq bad)
              (doseq [[_ f lib] (sort-by second bad)]
                (println (str "  UNRESOLVABLE  " f "\n      requires kotoba.lang." lib
                              " with no project file above it")))
              (println "  every kotoba.lang.* require has a project file above it"))
            (println)
            (println (str "  cleared by asking main (stale checkout): " (count gone)
                          "   unverified: " (count unv)))
            (println (str "  declared: " (count (:declared by))
                          "   undeclared-but-has-a-project-file: " (count (:undeclared by))))))
        (js/process.exit (if (seq bad) 1 0))))))

(-main)

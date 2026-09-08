#!/usr/bin/env nbb
(ns verify-kotoba-stdlib-self-hosted
  "Which kotoba stdlib repos still reach for a clojure.* namespace.

  ## What this answers, and why it is not a grep

  Between 2026-09-06 and 2026-09-08 the kotoba stdlib stopped delegating:
  clojure.string, clojure.set, clojure.walk and clojure.edn were replaced by
  kotoba.lang.text / .coll / .edn, and the last of those replaced a READER
  rather than a wrapper. The value of that work is not that the names changed.
  It is that `clojure.string` and `clojure.edn` DO NOT MEAN THE SAME THING ON
  THEIR TWO HOSTS -- measured divergences in `trim`'s whitespace class, in
  `split` with a capturing group, in `replace`'s `$0`, in `1N`/`1.5M`/`1/2`,
  and in `:eof` on the empty string -- and a delegating wrapper inherits every
  one of them silently.

  A single reintroduced require puts a repo back on that footing without
  anything saying so, which is what this detector exists to notice.

  ## Three things it does deliberately

  1. THE POPULATION IS DERIVED FROM THE TREE, not from a list. A repo is in
     scope when its own `src` declares a `kotoba.lang.*` namespace. A
     hand-maintained list would go stale the first time a repo was added, and
     would go stale silently, which is the failure mode this workspace keeps
     rediscovering.

  2. IT READS THE DEFAULT-BRANCH REMOTE REF, NOT THE WORKING TREE, and it
     RESOLVES that ref rather than assuming `origin/main` -- a west checkout
     names its remote after the org. A west checkout behind its
     pin reports requires that were removed days ago -- measured 2026-09-08,
     where six repos looked unmigrated purely because their checkouts were
     stale. When `origin/main` cannot be resolved for a repo the repo is
     counted as UNRESOLVED and reported, never as clean.

  3. `clojure.java.io` IS NOT A FINDING WHEN IT IS JVM-ONLY. Classpath lookup
     has no portable meaning -- there is no classpath on ClojureScript -- so
     `io/resource` is a host mechanism, not a migration target, and
     ADR-2609040930's rule is to record such a dependency rather than invent a
     destination for it. It is reported at :info so the count stays visible.

  Exit: 0 clean, 1 findings, 2 REFUSED (could not measure).

    nbb --classpath \".:scripts/nbb_compat\" scripts/verify-kotoba-stdlib-self-hosted.cljs [--findings] [--root DIR]"
  (:require [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def path-mod (js/require "node:path"))
(def cp (js/require "node:child_process"))

(def argv (vec (drop 2 (js->clj js/process.argv))))
(def findings-mode? (some #{"--findings"} argv))
(def root (or (second (drop-while #(not= "--root" %) argv)) "."))

(defn- sh [dir & args]
  (try
    (let [r (.spawnSync cp (first args) (clj->js (vec (rest args)))
                        #js {:cwd dir :encoding "utf8" :maxBuffer 33554432})]
      (when (zero? (.-status r)) (.-stdout r)))
    (catch :default _ nil)))

(defn- dirs [p]
  (try (->> (.readdirSync fs p #js {:withFileTypes true})
            (filter #(or (.isDirectory %) (.isSymbolicLink %)))
            (mapv #(.-name %)))
       (catch :default _ [])))

(defn- kotoba-lang-ns?
  "True when the repo's checked-out src declares a kotoba.lang.* namespace.
  The checkout is only used to decide MEMBERSHIP -- every measurement below
  reads origin/main."
  [repo-dir]
  (some? (sh repo-dir "git" "grep" "-lE" "^\\(ns[[:space:]]+kotoba\\.lang\\." "--" "src")))

(def ^:private host-mechanism
  "Namespaces that are host mechanisms rather than migration targets. Reported,
  never counted as a defect. See ADR-2609040930: an unassigned dependency is
  recorded and the migration stops; it does not get a destination invented for
  it. `clojure.java.io` is here because classpath lookup does not exist on
  ClojureScript at all, so there is nothing portable for it to become."
  #{"clojure.java.io"})

(defn- main-ref
  "The remote-tracking ref for this repo's default branch, or nil.

  NOT hard-coded to `origin/main`. A west checkout names its remote after the
  ORG, not `origin` -- measured 2026-09-08, six of the repos here have a
  `kotoba-lang` remote and no `origin` at all, and an earlier version of this
  detector reported all six as unmeasurable for that reason alone. CLAUDE.md
  records the same assumption breaking a deploy guard across 2,824 of 4,406
  checkouts, where it silently ALLOWED instead of refusing. Here it only ever
  produced an honest UNRESOLVED, but the honest answer was still the wrong
  one."
  [repo-dir]
  (some (fn [r]
          (when (sh repo-dir "git" "rev-parse" "--verify" "-q" (str "refs/remotes/" r))
            r))
        (concat ["origin/main" "origin/master"]
                (for [remote (some-> (sh repo-dir "git" "remote")
                                     str/split-lines
                                     ((partial remove str/blank?)))
                      branch ["main" "master" "HEAD"]]
                  (str remote "/" branch)))))

(defn- requires-of [repo-dir ref]
  (when-let [out (sh repo-dir "git" "grep" "-hoE"
                     "\\[[[:space:]]*(clojure|cljs)\\.[a-z0-9.-]+" ref "--" "src")]
    (->> (str/split-lines out)
         (map #(str/replace % #"^\[\s*" ""))
         (remove #{"clojure.core" "cljs.core"})
         (remove str/blank?)
         set
         sort
         vec)))

(defn -main []
  (let [orgs-dir (.join path-mod root "orgs" "kotoba-lang")
        repos    (->> (dirs orgs-dir)
                      (map (fn [n] [n (.join path-mod orgs-dir n)]))
                      (filter (fn [[_ d]] (kotoba-lang-ns? d))))
        _ (when (empty? repos)
            (println "REFUSED: no repo under orgs/kotoba-lang declares a kotoba.lang.* namespace;")
            (println "         the population could not be derived, so nothing was measured.")
            (js/process.exit 2))
        results (for [[name dir] repos]
                  (if-let [ref (main-ref dir)]
                    {:repo name :ref ref :requires (requires-of dir ref)}
                    {:repo name :unresolved? true}))
        results (vec results)
        unresolved (filterv :unresolved? results)
        measured   (filterv (complement :unresolved?) results)
        defects    (for [{:keys [repo requires]} measured
                         r requires
                         :when (not (host-mechanism r))]
                     {:repo repo :ns r})
        infos      (for [{:keys [repo requires]} measured
                         r requires
                         :when (host-mechanism r)]
                     {:repo repo :ns r})]
    ;; Evidence floor: the count of repos actually measured, so a run that
    ;; walked nothing cannot read as a clean run.
    (println (str "SCANNED\t" (count measured) "\tkotoba.lang.* repos read at their default-branch remote ref"))
    (when (seq unresolved)
      (println (str "UNRESOLVED\t" (count unresolved)
                    "\trepos with no resolvable default-branch remote ref -- NOT clean, unmeasured: "
                    (str/join " " (map :repo unresolved)))))
    (if findings-mode?
      (do
        (doseq [{:keys [repo ns]} defects]
          (println (str "FINDING\twarn\t" repo ":" ns
                        "\t" repo " requires " ns
                        " -- the kotoba stdlib replaced it because it does not mean the"
                        " same thing on both hosts (ADR-2609040930)")))
        (doseq [{:keys [repo ns]} infos]
          (println (str "FINDING\tinfo\t" repo ":" ns
                        "\t" repo " requires " ns
                        " -- host mechanism, no portable destination; recorded, not migrated")))
        (doseq [{:keys [repo]} unresolved]
          (println (str "FINDING\twarn\t" repo ":unresolved"
                        "\t" repo " has no resolvable default-branch remote ref, so it was not measured"))))
      (do
        (println)
        (if (seq defects)
          (doseq [{:keys [repo ns]} defects] (println (str "  DEFECT " repo " -> " ns)))
          (println "  no repo requires a clojure.* namespace that has a kotoba replacement"))
        (when (seq infos)
          (println)
          (doseq [{:keys [repo ns]} infos] (println (str "  host-mechanism " repo " -> " ns))))))
    (js/process.exit (if (or (seq defects) (seq unresolved)) 1 0))))

(-main)

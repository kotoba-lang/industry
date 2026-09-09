#!/usr/bin/env nbb
;; scripts/itonami-growth-evidence.cljs — the measurement the itonami growth
;; bots are handed. It decides nothing.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/itonami-growth-evidence.cljs
;;   ... --root <superproject>   ... --candidates 5   ... --offline
;;
;; Three exit codes, on purpose:
;;   0  a report was produced
;;   2  it could not answer            -- NOT a pass, and NOT an empty report
;;
;; There is no exit 1 here. This file finds things; it judges nothing, so it has
;; no way to be "wrong". The gate (scripts/itonami-verify-proposal.cljs) owns 1.
;;
;; WHY 2 IS REACHED WITH process.exit
;; Setting exitCode and throwing reports 1 under nbb, and 1 is the code this
;; family uses for "measured, and found something". A refusal that reports 1 is
;; indistinguishable from a finding. Same reason as app-hyakka's collector
;; (ADR-2608271450 decision 3).
;;
;; WHY AN EMPTY REPORT IS THE DANGEROUS OUTPUT
;; Hermes injects this stdout into the bot's prompt. An empty report reaches the
;; model as "nothing is missing" -- the one answer that must never be produced by
;; failure. So every section either prints rows or prints why it has none, and
;; the run refuses outright rather than printing a short report.
;;
;; ── WHAT THIS ADDS THAT THE TICK DOES NOT HAVE
;;
;; scripts/itonami-maturity-improve-tick.cljs ranks repos by leverage and names
;; the axis to raise. It reads the west pin and the maturity datoms, and neither
;; of those can see a branch that was pushed and never merged.
;;
;; Measured 2026-08-27: the tick named cloud-itonami-iso3166-jpn-meti and its
;; axis-ingest. The register it was asking for had been written the day before,
;; pushed as agent/maturity-meti-ingest, 3 commits, 0 behind main -- and left
;; there for nineteen hours. A worker who trusted the tick would have written a
;; second copy of the same 29 sources.
;;
;; So this collector asks GitHub, per candidate, whether a branch is sitting
;; ahead of main. "Not done" and "done and not landed" are different jobs, and
;; the more expensive mistake is doing the first when it is the second.
;;
;; ── AND WHY COVERAGE IS IN THE SAME REPORT
;;
;; Maturity says how good the repos that exist are. Coverage says which classes
;; have no repo at all. Raising one while the other is unmeasured is how a fleet
;; gets very good at the industries it already picked.
;;
;; The coverage signal is deliberately shaped like app-hyakka's ONTOLOGY SIGNAL:
;; it is a list the bot may only choose FROM. A class appears because the UN
;; table declares it and west has no project for it -- never because a model
;; thought of it. An industry nobody standardised cannot be added by asking.

(ns itonami-growth-evidence
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "fs"))
(def nodepath (js/require "path"))
(def cp (js/require "child_process"))

(def argv (vec (drop 2 (js->clj js/process.argv))))
(defn- flag [n d]
  (let [i (.indexOf (into-array argv) n)]
    (if (and (>= i 0) (< (inc i) (count argv))) (nth argv (inc i)) d)))
(def offline? (boolean (some #{"--offline"} argv)))
(def root (or (flag "--root" nil)
              (.-COM_JUNKAWASAKI_ROOT js/process.env)
              (str (.-HOME js/process.env) "/github/com-junkawasaki")))
(def n-candidates (js/parseInt (flag "--candidates" "5") 10))

(defn- die-refused! [& msg]
  (println "REFUSED — no evidence was gathered this run.")
  (doseq [m msg] (println m))
  (js/process.exit 2))

(defn- p [& xs] (nodepath.join root (str/join "/" xs)))
(defn- slurp* [path] (when (fs.existsSync path) (fs.readFileSync path "utf8")))

(defn- sh
  "Run a command, never throw. Returns {:code :out :err}."
  [cmd args & [opts]]
  (try
    (let [out (cp.execFileSync cmd (clj->js args)
                               (clj->js (merge {:encoding "utf8"
                                                :maxBuffer (* 64 1024 1024)
                                                :stdio ["ignore" "pipe" "pipe"]}
                                               opts)))]
      {:code 0 :out (str out) :err ""})
    (catch :default e
      {:code (or (.-status e) -1)
       :out (str (or (.-stdout e) ""))
       :err (str (or (.-stderr e) (.-message e) ""))})))

;; ---------------------------------------------------------------------------
;; 1. maturity — run the tick, then read the entry it appended.
;;
;; The tick is not re-implemented here. It writes a structured `:ranked` vector
;; to its own ledger on every run, so the reuse is: run it, read the line it
;; just wrote. A second ranking implementation would drift from the first and
;; the drift would be invisible -- both would print a repo name.
;; ---------------------------------------------------------------------------

(def ledger-path (str (.-HOME js/process.env) "/.itonami/itonami-maturity-improve.ledger.edn"))

(defn- ledger-entries []
  (let [t (slurp* ledger-path)]
    (if-not t
      []
      (->> (str/split-lines t)
           (remove str/blank?)
           (keep #(try (edn/read-string %) (catch :default _ nil)))
           vec))))

(defn- run-tick! []
  (let [before (count (ledger-entries))
        {:keys [code out err]} (sh "nbb" ["--classpath" (str root ":" (p "scripts/nbb_compat"))
                                          (p "scripts/itonami-maturity-improve-tick.cljs")]
                                   {:cwd root})
        after (ledger-entries)]
    (when (not= 0 code)
      (die-refused! (str "the maturity tick exited " code)
                    (subs (str (or (not-empty (str/trim err)) (str/trim out))) 0
                          (min 800 (count (str (or (not-empty (str/trim err)) (str/trim out))))))))
    (when (<= (count after) before)
      (die-refused! "the maturity tick wrote no ledger entry, so its ranking cannot be read"
                    "(a tick that ran and measured always appends one)"))
    (let [e (last after)]
      (when-not (= :measured (:outcome e))
        (die-refused! (str "the last ledger entry is :outcome " (pr-str (:outcome e))
                           ", not :measured — the tick did not rank anything this run")))
      {:entry e :stdout out})))

;; ---------------------------------------------------------------------------
;; 2. unlanded work — what the tick structurally cannot see.
;; ---------------------------------------------------------------------------

(defn- repo-slug
  "orgs/<org>/<name> -> <org>/<name>, using the org directory as the GitHub owner.
  Returns nil for a path that is not shaped that way rather than guessing."
  [path]
  (let [seg (str/split (str path) #"/")]
    (when (and (= 3 (count seg)) (= "orgs" (first seg)))
      (str (nth seg 1) "/" (nth seg 2)))))

(defn- branches-ahead
  "Branches on the remote that are ahead of the default branch.

  Reports :unknown rather than [] when GitHub could not be asked. An empty list
  and an unanswered question are the same shape here and must not be."
  [slug]
  (if offline?
    :unknown
    (let [{:keys [code out]} (sh "gh" ["api" (str "repos/" slug "/branches")
                                       "--jq" ".[].name"])]
      (if (not= 0 code)
        :unknown
        (let [names (->> (str/split-lines out) (remove str/blank?) vec)
              default (if (some #{"main"} names) "main"
                          (if (some #{"master"} names) "master" nil))]
          (if-not default
            :unknown
            (->> (remove #{default} names)
                 (keep (fn [b]
                         (let [{:keys [code out]}
                               (sh "gh" ["api" (str "repos/" slug "/compare/" default "..." b)
                                         "--jq" "[.status,.ahead_by,.behind_by]|@tsv"])]
                           (when (= 0 code)
                             (let [[st a bh] (str/split (str/trim out) #"\t")]
                               (when (and (= "ahead" st) (= "0" bh))
                                 {:branch b :ahead (js/parseInt a 10)}))))))
                 vec)))))))

;; ---------------------------------------------------------------------------
;; 3. coverage — classes the official table declares and west has no project for.
;;
;; Two tables, read from the mirrors in this workspace, never from memory:
;;   orgs/cloud-itonami/org-un-isic/data/classes/*.json   ISIC (industry)
;;   orgs/cloud-itonami/org-un-cofog/worker/src/taxonomy.ts  COFOG (government)
;;
;; A missing mirror is a refusal, not an empty gap list: "the table is not here"
;; and "nothing is uncovered" must not print the same way.
;; ---------------------------------------------------------------------------

(def west-path (p "manifest/west.yml"))

(defn- west-project-names []
  (let [t (slurp* west-path)]
    (when-not t (die-refused! (str "manifest/west.yml not found under " root)))
    (->> (re-seq #"(?m)^\s*-\s+name:\s+(\S+)\s*$" t) (map second) set)))

(defn- isic-classes
  "code -> English name, from the per-class JSON the mirror publishes."
  []
  (let [dir (p "orgs/cloud-itonami/org-un-isic/data/classes")]
    (when-not (fs.existsSync dir)
      (die-refused! (str "the ISIC mirror is not checked out: " dir)
                    "west update --fetch smart org-un-isic"))
    (into {}
          (keep (fn [f]
                  (when (str/ends-with? f ".json")
                    (let [j (js->clj (js/JSON.parse (slurp* (nodepath.join dir f))))]
                      [(get j "code") (or (get j "nameEn") (get j "name") "")])))
                (js->clj (fs.readdirSync dir))))))

(defn- cofog-groups
  "3-digit group code -> [name division-name], parsed from the mirror's taxonomy."
  []
  (let [f (p "orgs/cloud-itonami/org-un-cofog/worker/src/taxonomy.ts")
        t (slurp* f)]
    (when-not t
      (die-refused! (str "the COFOG mirror is not checked out: " f)
                    "west update --fetch smart org-un-cofog"))
    (let [divs (into {} (map (fn [[_ c n]] [c n])
                             (re-seq #"\{ code: \"(\d{2})\", nameEn: \"([^\"]+)\" \}" t)))
          groups (map (fn [[_ c n d]] {:code c :name n :division d :division-name (get divs d)})
                      (re-seq #"\{ code: \"(\d{3})\", nameEn: \"([^\"]+)\", division: \"(\d{2})\" \}" t))]
      (when (empty? groups)
        (die-refused! "the COFOG taxonomy parsed to zero groups — the mirror's shape changed"))
      (vec groups))))

(defn- isic-gaps [names]
  (let [four (into #{} (keep #(second (re-find #"^cloud-itonami-isic-(\d{4})" %)) names))
        three (into #{} (keep #(second (re-find #"^cloud-itonami-isic-(\d{3})(?:$|-)" %)) names))
        table (isic-classes)]
    {:table-size (count table)
     :gaps (->> table
                (remove (fn [[code _]]
                          (or (four code) (three (subs code 0 3)))))
                (sort-by first)
                vec)}))

(defn- cofog-gaps [names]
  (let [have (into #{} (keep #(let [[_ d g] (re-find #"^cloud-itonami-cofog-(\d{2})\.(\d)" %)]
                                (when d (str d g)))
                             names))
        table (cofog-groups)]
    {:table-size (count table)
     :implemented (sort (vec have))
     :gaps (->> table (remove #(have (:code %))) vec)}))

;; ---------------------------------------------------------------------------
;; report
;; ---------------------------------------------------------------------------

(defn -main []
  ;; --probe-branches exercises the unlanded-work probe on one repo, by name.
  ;;
  ;; It exists because that probe is the one check here that can only be shown to
  ;; fire against a repository that actually has a stranded branch, and stranded
  ;; branches are transient by nature -- the one this collector was built for was
  ;; merged the same day it was found. A check nobody can re-run on demand is a
  ;; check that quietly stops working. This calls the real function, not a copy.
  (when-let [slug (flag "--probe-branches" nil)]
    (println (str slug "\t" (pr-str (branches-ahead slug))))
    (js/process.exit 0))

  (when-not (fs.existsSync root)
    (die-refused! (str "the superproject root does not exist: " root)
                  "point it with --root or COM_JUNKAWASAKI_ROOT"))

  (let [{:keys [entry]} (run-tick! )
        ranked (vec (take n-candidates (:ranked entry)))
        names (west-project-names)
        isic (isic-gaps names)
        cofog (cofog-gaps names)]

    (when (empty? ranked)
      (die-refused! "the tick ranked no candidate this run"
                    (str ":datoms-stale? " (pr-str (:datoms-stale? entry))
                         "  :lane " (pr-str (:lane entry)))
                    "a run with no candidate is a measurement the bots cannot act on;"
                    "it is reported as a refusal so it is not read as 'the fleet is done'."))

    (println (str "root\t" root))
    (println (str "measured-at\t" (:at entry)))
    (println (str "datoms-age-days\t" (:datoms-age-days entry)
                  "\tstale? " (pr-str (:datoms-stale? entry))))
    (println (str "lane\t" (:lane entry)
                  "\tranking-is-flat? " (pr-str (:ranking-is-flat? entry))))
    (println)

    (println "MATURITY SIGNAL — repos the tick ranked, and the axis it named")
    (println "  (axis-substrate and axis-fresh are excluded by the tick and may not be targeted:")
    (println "   they are what good work moves, not what work aims at)")
    (doseq [r ranked]
      (let [slug (repo-slug (:repo r))
            targetable (filter :targetable? (:weakest r))]
        (println (str "  " (:repo r)
                      "\tkind=" (pr-str (:kind r))
                      "\town=" (:own r)
                      "\tgain=" (:fleet-gain r)
                      "\tband=" (pr-str (:band r))))
        (doseq [a targetable]
          (println (str "      axis " (name (:axis a))
                        "  now " (:value a) "bp"
                        "  headroom +" (:headroom-bp a) "bp")))
        (when (empty? targetable)
          (println "      (no targetable axis — this repo cannot be raised by this loop)"))
        (let [b (branches-ahead slug)]
          (cond
            (= :unknown b)
            (println (str "      unlanded work: UNKNOWN — GitHub was not asked"
                          (when offline? " (--offline)")
                          ". Treat as unmeasured, not as none."))
            (seq b)
            (doseq [{:keys [branch ahead]} b]
              (println (str "      ALREADY PUSHED, NOT MERGED: " branch
                            " (" ahead " commit(s) ahead of the default branch, 0 behind)"
                            " — read it before writing anything")))
            :else
            (println "      unlanded work: none on the remote")))))
    (println)

    (println (str "COVERAGE SIGNAL — government functions the UN table declares "
                  "and west has no project for"))
    (println (str "  COFOG groups: " (count (:gaps cofog)) " uncovered of " (:table-size cofog)
                  "   implemented: " (str/join ", " (:implemented cofog))))
    (doseq [g (:gaps cofog)]
      (println (str "  " (subs (:code g) 0 2) "." (subs (:code g) 2)
                    "\t" (:name g)
                    "\t[division " (:division g) " " (:division-name g) "]")))
    (println)

    (println (str "COVERAGE SIGNAL — ISIC classes the table declares "
                  "and west has no project for"))
    (println (str "  ISIC classes: " (count (:gaps isic)) " uncovered of " (:table-size isic)))
    (if (empty? (:gaps isic))
      (println "  none — every class in the mirror has a project at class or group level")
      (doseq [[code nm] (:gaps isic)]
        (println (str "  " code "\t" nm))))
    (println)
    (println (str "  NOTE the mirror's own PROVENANCE.edn records that its Rev.4 half is "
                  "unpinned and disputed on 33 of 414 titles, and that no Rev.4<->Rev.5 "
                  "correspondence table is published. A code that appears here may be a "
                  "Rev.5 code sitting in a table labelled Rev.4. Check the code against "
                  "data/classes/<code>.json before treating it as a gap."))
    (println)

    ;; The evidence floor. A report that reaches the model without this line is
    ;; a truncated run, and the shim refuses on its absence.
    (println (str "SCANNED\t" (count ranked) "\tranked candidate(s)"
                  "\t" (count (:gaps cofog)) "\tCOFOG gap(s)"
                  "\t" (count (:gaps isic)) "\tISIC gap(s)"))
    (js/process.exit 0)))

(-main)

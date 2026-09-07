#!/usr/bin/env nbb
(ns candidates
  "Rank one clojure.* -> kotoba.* stdlib-rewire candidate across kotoba-lang.

  Companion to scripts/hermes-kotoba-migration-bots/candidates.cljs, but a
  DIFFERENT program: that one moves product semantics from .clj/.cljc to
  .kotoba/.cljk (ADR-2607279200/2608261100). This one rewires an EXISTING
  .clj/.cljc file's (:require ...) from a org.clojure/clojure library-shaped
  namespace to its kotoba-lang.* replacement, per
  adr-2809061500-clojure-namespace-to-kotoba-stdlib. Same shape
  (decision-free scanner ranks, LLM classifies and does the work, an
  independent gate decides), different domain, different candidate set, and
  a DIFFERENT seen-ledger — the two bots must never claim the same unit of
  work, so they don't share state.

  Decision-free: this only measures. It does not decide whether a candidate
  is a good rewire — the LLM still has to open the CURRENT GitHub tip of the
  target kotoba-lang library and re-check its API actually covers what the
  candidate file uses, every run. This script's own information about which
  clojure.* namespace maps to which kotoba-lang repo (below) is a fixed fact
  (the ADR's decision), not a claim about current API coverage — that part
  goes stale. Measured THIS session: the local checkout of kotoba-lang/test,
  kotoba-lang/coll and kotoba-lang/process were all several commits behind
  their own GitHub tips (missing deftest/is/testing/are/run-tests; missing
  subset?/superset?/select/project/join/rename/index and unbounded
  walk/prewalk/postwalk; missing a real `exec` spawn transport, respectively)
  — trusting the local checkout instead of re-reading the target repo's
  actual tip would have proposed rewires against an API that doesn't exist
  yet in what the candidate's own west pin resolves to. Never trust this
  script's table as a coverage claim; it only tells you WHERE to look.

  Namespace -> target map (adr-2809061500 decision; NOT a coverage claim):

    clojure.string      -> kotoba.string          (kotoba-lang/string)
    clojure.test         -> kotoba.lang.test       (kotoba-lang/test)
    clojure.edn          -> kotoba.lang.edn        (kotoba-lang/edn)
    clojure.set          -> kotoba.lang.coll       (kotoba-lang/coll)
    clojure.walk         -> kotoba.lang.coll       (kotoba-lang/coll)
    clojure.pprint       -> kotoba.lang.fmt        (kotoba-lang/fmt)
    clojure.java.shell   -> kotoba.lang.process    (kotoba-lang/process)

  clojure.java.io is DELIBERATELY NOT in this list. Its migration is only a
  PROPOSED design (adr-2809070100-clojure-java-io-capability-boundary-proposal,
  status \"proposed\", not accepted) — an ambient-OS-authority namespace being
  redesigned into a capability-injected one, the most invasive of the eight
  and explicitly scheduled last. This scanner cannot choose it because it is
  never in the scanned set — not filtered out after the fact, never looked
  for. Do not add it here without the ADR itself changing status to
  accepted.

  Known documented exclusions (adr-2809061500 :adr/consequences, dated
  2026-09-07 — re-check that field before trusting these, it is the log of
  what has already happened, not a permanent list):
    - sahai's one clojure.walk site: uses keywordize-keys, which
      kotoba-lang/coll genuinely does not implement. Do not force it.
    - kotoba-lang/fleet (clojure.pprint): a stale fossil superseded by the
      actively-maintained kotoba-lang/sahai. Not worth rewiring.
    - kotoba-lang/inference's scripts/kotodama/oracle_gen.cljs
      (clojure.pprint): deliberately relies on cljs-pprint's own BigInt/
      whitespace quirks as a test oracle. Rewiring it would break the thing
      it tests.
    - kotobase, browser-use, torch, murakumo, kami-app-character-creator,
      io-stripe-issuing: :local/root sibling deps — also caught structurally
      below, listed here because they were already checked once.
    - kotoba-server: retired archive; its own README says nothing should be
      built there.

  Exit codes (the itonami-growth-evidence.cljs family):
    0  a report was produced (including \"no candidate this run\")
    2  REFUSED — could not measure"
  (:require [cljs.reader :as edn]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            ["node:os" :as os]))

(def argv (vec (or *command-line-args* [])))
(defn arg [flag default]
  (let [i (.indexOf argv flag)]
    (if (neg? i) default (get argv (inc i) default))))

(def root (path/resolve (arg "--root" ".")))
(def top-n (js/parseInt (arg "--top" "1") 10))
(def seen-path (arg "--seen" (path/join (os/homedir) ".gftd" "hermes-clojure-stdlib-migration-bot" "seen.edn")))
(def seen-ttl-days (js/parseInt (arg "--seen-ttl-days" "14") 10))
(def pool-size (js/parseInt (arg "--pool" "60") 10))

(defn refuse! [why]
  (println "REFUSED — no candidate was measured this run.")
  (println why)
  (println)
  (println "Do not propose anything. A rewire picked from an unread tree is a")
  (println "proposal built on nothing. Report this refusal and stop.")
  (.exit js/process 2))

(def kotoba-lang-dir (path/join root "orgs" "kotoba-lang"))

(when-not (.existsSync fs kotoba-lang-dir)
  (refuse! (str kotoba-lang-dir " does not exist — this checkout has no populated "
                "orgs/kotoba-lang/. Candidates cannot be found in a tree that isn't there.")))

;; ---------------------------------------------------------- excluded repos

(def meta-repos
  "The frozen bootstrap quartet plus the stdlib target's own home repo. A
  rewire wave inside the compiler/runtime bootstrap itself is an
  architectural tranche, not an autonomous per-repo swap — same reasoning
  scripts/hermes-kotoba-migration-bots/README.md gives for excluding these
  four from THAT bot."
  #{"kotoba" "kotoba-lang" "amu" "kototama" "aiueos"})

(def deliberately-skip
  "{repo ns-key} pairs the ADR's own :adr/consequences already names as
  checked-and-not-viable (not merely unattempted). See docstring above for
  the reasons. Keyed on the same ns-key as target-map below."
  #{["sahai" "clojure.walk"]
    ["fleet" "clojure.pprint"]
    ["inference" "clojure.pprint"]})

;; ------------------------------------------------------------ ns -> target

(def target-map
  "clojure.* namespace -> {:target-ns :target-repo}. adr-2809061500 decision,
  not a coverage claim — see docstring."
  {"clojure.string"       {:target-ns "kotoba.string"        :target-repo "string"}
   "clojure.test"          {:target-ns "kotoba.lang.test"     :target-repo "test"}
   "clojure.edn"           {:target-ns "kotoba.lang.edn"      :target-repo "edn"}
   "clojure.set"           {:target-ns "kotoba.lang.coll"     :target-repo "coll"}
   "clojure.walk"          {:target-ns "kotoba.lang.coll"     :target-repo "coll"}
   "clojure.pprint"        {:target-ns "kotoba.lang.fmt"      :target-repo "fmt"}
   "clojure.java.shell"    {:target-ns "kotoba.lang.process"  :target-repo "process"}})

;; regex per namespace, careful about substring collisions (clojure.test vs
;; clojure.test.check; clojure.java.shell vs clojure.java.io share no prefix
;; so no collision there)
(def ns-pattern
  {"clojure.string"    #"clojure\.string\b"
   "clojure.test"       #"clojure\.test(?!\.check)\b"
   "clojure.edn"        #"clojure\.edn\b"
   "clojure.set"        #"clojure\.set\b"
   "clojure.walk"       #"clojure\.walk\b"
   "clojure.pprint"     #"clojure\.pprint\b"
   "clojure.java.shell" #"clojure\.java\.shell\b"})

;; --------------------------------------------------------------- find files

(defn sh [cmd args]
  (let [r (cp/spawnSync cmd (clj->js args) #js {:encoding "utf8" :maxBuffer (* 64 1024 1024)})]
    {:status (or (.-status r) 1)
     :stdout (or (.-stdout r) "")
     :stderr (or (.-stderr r) "")}))

;; NOTE, unlike scripts/hermes-kotoba-migration-bots/candidates.cljs: this
;; does NOT exclude test/ paths. clojure.test lives almost entirely in test
;; files — excluding them would make that namespace structurally unpickable.
(def find-result
  (sh "find" ["orgs/kotoba-lang" "-type" "f"
              "(" "-name" "*.clj" "-o" "-name" "*.cljc" ")"
              "-not" "-path" "*/.git/*"
              "-not" "-path" "*/node_modules/*"
              "-not" "-path" "*/target/*"
              "-not" "-path" "*/.cpcache/*"
              "-not" "-path" "*/out/*"]))

(when (pos? (:status find-result))
  (refuse! (str "find over orgs/kotoba-lang exited " (:status find-result) ": " (:stderr find-result))))

(def all-files
  (->> (str/split-lines (:stdout find-result))
       (remove str/blank?)
       vec))

(when (empty? all-files)
  (refuse! (str "find found zero .clj/.cljc source files under " kotoba-lang-dir
                " — either the checkout is empty or the exclude filters are wrong.")))

(defn repo-of [rel-path] (nth (str/split rel-path #"/") 2 nil))

(def all-repos (distinct (map repo-of all-files)))

;; --------------------------------------------------------- :local/root scan

(defn repo-has-local-root? [repo]
  "A fresh clone of `repo` cannot resolve a :local/root sibling dependency —
  the sibling path only exists inside THIS west checkout. Excluded wholesale,
  every namespace, not just the matched one."
  (let [deps-path (path/join root "orgs" "kotoba-lang" repo "deps.edn")]
    (and (.existsSync fs deps-path)
         (try (str/includes? (.toString (fs/readFileSync deps-path)) ":local/root")
              (catch :default _ false)))))

(def local-root-repos (set (filter repo-has-local-root? all-repos)))

;; --------------------------------------------------------------- score each

(defn read-file [abs-path]
  (try (.toString (fs/readFileSync abs-path))
       (catch :default _ nil)))

(defn matches-for-file [rel-path content]
  "-> seq of {:ns :hits} for every target namespace this file mentions."
  (keep (fn [[ns-key pat]]
          (let [hits (count (re-seq pat content))]
            (when (pos? hits) {:ns ns-key :hits hits})))
        ns-pattern))

(def file+matches
  (->> all-files
       (remove #(contains? local-root-repos (repo-of %)))
       (remove #(contains? meta-repos (repo-of %)))
       (keep (fn [rel-path]
               (when-let [content (read-file (path/join root rel-path))]
                 (let [ms (matches-for-file rel-path content)]
                   (when (seq ms)
                     {:path rel-path :repo (repo-of rel-path)
                      :lines (count (str/split-lines content)) :matches ms})))))))

;; group by (repo, ns) — the natural unit of one rewire run: one repo, one
;; clojure.* namespace, all the files in that repo which use it.
(def candidate-pool
  (->> (for [{:keys [path repo lines matches]} file+matches
             {:keys [ns hits]} matches]
         {:repo repo :ns ns :path path :lines lines :hits hits})
       (group-by (fn [c] [(:repo c) (:ns c)]))
       (remove (fn [[k _]] (contains? deliberately-skip k)))
       (map (fn [[[repo ns] rows]]
              {:repo repo
               :ns ns
               :target-ns (get-in target-map [ns :target-ns])
               :target-repo (get-in target-map [ns :target-repo])
               :files (mapv :path rows)
               :file-count (count rows)
               :total-hits (reduce + (map :hits rows))
               ;; lower is better: few files, few total hits — small,
               ;; self-contained, few call sites to re-derive by hand.
               :score (+ (reduce + (map :lines rows))
                         (* 6 (count rows))
                         (* 2 (reduce + (map :hits rows))))}))
       (sort-by :score)))

;; ------------------------------------------------------------------- seen

(defn load-seen []
  (try (edn/read-string (fs/readFileSync seen-path "utf8"))
       (catch :default _ {})))

(def seen (load-seen))
(def now-ms (.getTime (js/Date.)))
(def ttl-ms (* seen-ttl-days 24 60 60 1000))

(defn seen-key [c] (str (:repo c) "|" (:ns c)))

(defn recently-seen? [c]
  (when-let [t (get seen (seen-key c))]
    (< (- now-ms t) ttl-ms)))

;; --------------------------------------------------------- GitHub in-flight

(defn gh-open-prs [repo]
  (let [r (sh "gh" ["pr" "list" "--repo" (str "kotoba-lang/" repo)
                    "--state" "open" "--json" "headRefName,title,url"
                    "--limit" "50"])]
    (when (zero? (:status r))
      (try (js->clj (js/JSON.parse (:stdout r)) :keywordize-keys true)
           (catch :default _ nil)))))

(defn in-flight?
  [c]
  (let [token (last (str/split (:ns c) #"\."))
        prs (gh-open-prs (:repo c))]
    (cond
      (nil? prs) {:status :unknown}
      (some #(or (str/includes? (str/lower-case (:headRefName %)) token)
                 (str/includes? (str/lower-case (:title %)) token)
                 (str/includes? (str/lower-case (:headRefName %)) "clojure-stdlib")
                 (str/includes? (str/lower-case (:title %)) "clojure-stdlib"))
            prs)
      {:status :in-flight
       :pr (first (filter #(or (str/includes? (str/lower-case (:headRefName %)) token)
                                (str/includes? (str/lower-case (:title %)) token)
                                (str/includes? (str/lower-case (:headRefName %)) "clojure-stdlib")
                                (str/includes? (str/lower-case (:title %)) "clojure-stdlib"))
                           prs))}
      :else {:status :clear})))

;; ------------------------------------------------------------------- pick

(defn pick []
  (loop [pool (take pool-size candidate-pool)
         tried []]
    (if (empty? pool)
      {:chosen nil :tried tried}
      (let [c (first pool)]
        (if (recently-seen? c)
          (recur (rest pool) (conj tried (assoc c :skip "recently proposed")))
          (let [flight (in-flight? c)]
            (case (:status flight)
              :in-flight (recur (rest pool) (conj tried (assoc c :skip (str "ALREADY PUSHED, NOT MERGED — "
                                                                              (get-in flight [:pr :url])))))
              :clear {:chosen c :tried tried}
              :unknown {:chosen c :tried tried :unknown-flight (:repo c)})))))))

(def result (pick))

;; ---------------------------------------------------------------- report

(println (str "SCANNED\t" (count all-files) " .clj/.cljc files across "
              (count all-repos) " kotoba-lang repos "
              "(" (count meta-repos) " meta-repos excluded, "
              (count local-root-repos) " :local/root-excluded, "
              (count candidate-pool) " repo×namespace candidates, "
              (count deliberately-skip) " deliberately-skipped pairs; "
              "clojure.java.io is never scanned — see docstring)"))
(println)

(if-let [c (:chosen result)]
  (do
    (println (str "CHOSEN\t" (:repo c) "\t" (:ns c) "\t->\t" (:target-ns c)
                   "\t(kotoba-lang/" (:target-repo c) ")"))
    (println (str "  files=" (:file-count c) " total-hits=" (:total-hits c) " score=" (:score c)))
    (doseq [f (:files c)] (println (str "    " f)))
    (when (:unknown-flight result)
      (println (str "  ⚠ gh could not confirm whether this is already proposed for "
                     (:unknown-flight result) " — check `gh pr list --repo kotoba-lang/"
                     (:unknown-flight result) "` yourself before writing anything.")))
    (println)
    (println (str "TARGET REPO (re-verify its CURRENT tip before trusting it covers this): "
                   "https://github.com/kotoba-lang/" (:target-repo c)
                   " -> require as [" (:target-ns c) "]"))
    (println)
    (println "RUNNERS-UP (for context, not to be worked on this run):")
    (doseq [t (take 5 (:tried result))]
      (println (str "  " (:repo t) "\t" (:ns t) "\t" (or (:skip t) (str "lower score=" (:score t))))))
    (fs/mkdirSync (path/dirname seen-path) #js {:recursive true})
    (fs/writeFileSync seen-path (pr-str (assoc seen (seen-key c) now-ms)))
    (.exit js/process 0))
  (do
    (println (str "No candidate this run. " (count (:tried result))
                   " were ranked and all were either recently proposed or "
                   "already have an open PR."))
    (println "This is a legitimate empty report — do not invent a candidate.")
    (.exit js/process 0)))

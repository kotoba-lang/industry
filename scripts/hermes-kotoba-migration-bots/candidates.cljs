#!/usr/bin/env nbb
(ns candidates
  "Rank one clj/cljc → .kotoba/.cljk migration candidate across kotoba-lang.

  Decision-free, like itonami-growth-evidence.cljs and wiki_growth_evidence.cljs:
  this only measures. Nothing here decides whether a candidate is a good
  migration — that judgment (the ADR-2607279200 four-way classification,
  portable-vs-host) is the LLM's, per run, reading the actual file. This
  script's job is narrower: don't hand the model a file that is already
  migrated, already proposed and sitting in an open PR, or too big/import-heavy
  to be a defensible single slice.

  hyakka-lang has 2,164 checked-out repos under orgs/kotoba-lang/ in THIS
  superproject checkout (measured 2026-08-28) — unusually complete; most west
  checkouts are sparse. This scans the local filesystem, not GitHub code
  search, so it sees exactly what this checkout has. If that ever stops being
  (nearly) everything, this under-reports rather than lies: the SCANNED line
  says how many repos and files it actually looked at.

  Ranking is a size/interop heuristic, not a verdict. It orders candidates so
  the LLM sees tractable ones first; the LLM still classifies the winner
  itself before touching it, and can reject this script's pick and report why.

  Exit codes (the itonami-growth-evidence.cljs family):
    0  a report was produced (including \"no candidate this run\" — a
       legitimate empty report, not a failure)
    2  REFUSED — could not measure (orgs/kotoba-lang missing, no files
       readable, or the seen-ledger is corrupt in a way that can't be
       skipped safely)"
  (:require [cljs.reader :as edn]
            [clojure.string :as str]
            [promesa.core :as p]
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
(def seen-path (arg "--seen" (path/join (os/homedir) ".itonami" "hermes-kotoba-migration-bot" "seen.edn")))
(def seen-ttl-days (js/parseInt (arg "--seen-ttl-days" "14") 10))
(def pool-size (js/parseInt (arg "--pool" "40") 10)) ; how many top-ranked candidates to consider before giving up

(defn refuse! [why]
  (println "REFUSED — no candidate was measured this run.")
  (println why)
  (println)
  (println "Do not propose anything. A migration slice picked from an unread tree")
  (println "is a proposal built on nothing. Report this refusal and stop.")
  (.exit js/process 2))

(def kotoba-lang-dir (path/join root "orgs" "kotoba-lang"))

(when-not (.existsSync fs kotoba-lang-dir)
  (refuse! (str kotoba-lang-dir " does not exist — this checkout has no populated "
                "orgs/kotoba-lang/. Candidates cannot be found in a tree that isn't there.")))

;; --------------------------------------------------------------- find files

(defn sh [cmd args]
  (let [r (cp/spawnSync cmd (clj->js args) #js {:encoding "utf8" :maxBuffer (* 64 1024 1024)})]
    {:status (or (.-status r) 1)
     :stdout (or (.-stdout r) "")
     :stderr (or (.-stderr r) "")}))

(def find-result
  (sh "find" ["orgs/kotoba-lang" "-type" "f"
              "(" "-name" "*.clj" "-o" "-name" "*.cljc" ")"
              "-not" "-path" "*/.git/*"
              "-not" "-path" "*/node_modules/*"
              "-not" "-path" "*/target/*"
              "-not" "-path" "*/.cpcache/*"
              "-not" "-path" "*/out/*"
              "-not" "-path" "*/test/*"
              "-not" "-path" "*/tests/*"
              "-not" "-path" "*resources*"]))

(when (pos? (:status find-result))
  (refuse! (str "find over orgs/kotoba-lang exited " (:status find-result) ": " (:stderr find-result))))

(def all-files
  (->> (str/split-lines (:stdout find-result))
       (remove str/blank?)
       vec))

(when (empty? all-files)
  (refuse! (str "find found zero .clj/.cljc source files under " kotoba-lang-dir
                " — either the checkout is empty or the exclude filters are wrong. "
                "Neither is a candidate list.")))

;; ---------------------------------------------------- repo / sibling helpers

(defn repo-of
  "orgs/kotoba-lang/<repo>/... -> <repo>"
  [rel-path]
  (nth (str/split rel-path #"/") 2 nil))

(defn already-migrated?
  "A sibling <basename>.kotoba or <basename>.cljk next to this file — same
  logical unit already has a Kotoba/cljk counterpart, so this is not a
  candidate, it's done."
  [rel-path]
  (let [dir (path/dirname rel-path)
        base (-> (path/basename rel-path)
                 (str/replace #"\.clj[c]?$" ""))]
    (or (.existsSync fs (path/join root dir (str base ".kotoba")))
        (.existsSync fs (path/join root dir (str base ".cljk"))))))

(def host-path-markers
  ;; Soft exclude: these path segments usually mean the file's job is a
  ;; mechanism (sockets, native codegen, JVM/JS interop shim), not product
  ;; semantics. Not authoritative — the LLM still classifies the winner
  ;; itself per ADR-2607279200 before touching it.
  ["/native/" "/wasm/" "/interop/" "/jvm/" "/chicory/" "/host/" "/host_provider/"
   "/bin/" "/cli/" "/tools/" "/scripts/"])

(defn host-path? [rel-path]
  (some #(str/includes? rel-path %) host-path-markers))

;; --------------------------------------------------------------- score each

(defn read-file [abs-path]
  (try (.toString (fs/readFileSync abs-path))
       (catch :default _ nil)))

(defn score-file [rel-path]
  (let [abs (path/join root rel-path)
        content (read-file abs)]
    (when content
      (let [lines (str/split-lines content)
            line-count (count lines)
            defn-count (count (re-seq #"\(defn[- ]" content))
            interop-count (+ (count (re-seq #"\(:import" content))
                              (count (re-seq #"#js\b" content))
                              (count (re-seq #"\bjs/" content))
                              (count (re-seq #"\bSystem/" content)))
            throw-count (count (re-seq #"\(throw\b" content))]
        {:path rel-path
         :repo (repo-of rel-path)
         :lines line-count
         :defns defn-count
         :interop interop-count
         :throws throw-count
         ;; lower is better: small, few interop calls, few defns to re-derive
         :score (+ line-count (* 8 interop-count) (* 3 throw-count))}))))

(def candidate-pool
  (->> all-files
       (remove already-migrated?)
       (remove host-path?)
       (keep score-file)
       (filter #(and (>= (:lines %) 10) (pos? (:defns %))))
       (sort-by :score)))

(def already-migrated-count
  (count (filter already-migrated? all-files)))
(def host-excluded-count
  (count (filter #(and (not (already-migrated? %)) (host-path? %)) all-files)))

;; ------------------------------------------------------------------- seen

(defn load-seen []
  (try (edn/read-string (fs/readFileSync seen-path "utf8"))
       (catch :default _ {})))

(def seen (load-seen))
(def now-ms (.getTime (js/Date.)))
(def ttl-ms (* seen-ttl-days 24 60 60 1000))

(defn recently-seen? [rel-path]
  (when-let [t (get seen rel-path)]
    (< (- now-ms t) ttl-ms)))

;; --------------------------------------------------------- GitHub in-flight

(defn gh-open-prs [repo]
  "Open PR head branches + titles for kotoba-lang/<repo>. nil on any failure
  (rate limit, no gh, network) — the caller degrades to UNKNOWN, not to
  'nothing in flight'."
  (let [r (sh "gh" ["pr" "list" "--repo" (str "kotoba-lang/" repo)
                    "--state" "open" "--json" "headRefName,title,url"
                    "--limit" "50"])]
    (when (zero? (:status r))
      (try (js->clj (js/JSON.parse (:stdout r)) :keywordize-keys true)
           (catch :default _ nil)))))

(defn in-flight?
  "Best-effort: does an open PR's branch name or title look like it already
  targets this file's basename? Not exact — a false negative here just means
  a duplicate proposal, which the human reviewing two PRs will notice; a false
  positive would silently skip a real candidate, so this only skips on an
  actual textual match, never on 'gh failed'."
  [repo rel-path]
  (let [base (-> (path/basename rel-path) (str/replace #"\.clj[c]?$" ""))
        prs (gh-open-prs repo)]
    (cond
      (nil? prs) {:status :unknown}
      (some #(or (str/includes? (:headRefName %) base)
                 (str/includes? (:title %) base))
            prs)
      {:status :in-flight
       :pr (first (filter #(or (str/includes? (:headRefName %) base)
                                (str/includes? (:title %) base)) prs))}
      :else {:status :clear})))

;; ------------------------------------------------------------------- pick

(defn pick []
  (loop [pool (take pool-size candidate-pool)
         tried []]
    (if (empty? pool)
      {:chosen nil :tried tried}
      (let [c (first pool)]
        (if (recently-seen? (:path c))
          (recur (rest pool) (conj tried (assoc c :skip "recently proposed")))
          (let [flight (in-flight? (:repo c) (:path c))]
            (case (:status flight)
              :in-flight (recur (rest pool) (conj tried (assoc c :skip (str "ALREADY PUSHED, NOT MERGED — "
                                                                              (get-in flight [:pr :url])))))
              :clear {:chosen c :tried tried}
              :unknown {:chosen c :tried tried :unknown-flight (:repo c)})))))))

(def result (pick))

;; ---------------------------------------------------------------- report

(println (str "SCANNED\t" (count all-files) " .clj/.cljc files across "
              (count (distinct (map repo-of all-files))) " kotoba-lang repos "
              "(" already-migrated-count " already migrated, "
              host-excluded-count " host/mechanism-path excluded, "
              (count candidate-pool) " candidates)"))
(println)

(if-let [c (:chosen result)]
  (do
    (println (str "CHOSEN\t" (:repo c) "\t" (:path c)))
    (println (str "  lines=" (:lines c) " defns=" (:defns c)
                   " interop-markers=" (:interop c) " throw-forms=" (:throws c)
                   " score=" (:score c)))
    (when (:unknown-flight result)
      (println (str "  ⚠ gh could not confirm whether this is already proposed for "
                     (:unknown-flight result) " — check `gh pr list --repo kotoba-lang/"
                     (:unknown-flight result) "` yourself before writing anything.")))
    (println)
    (println "RUNNERS-UP (for context, not to be worked on this run):")
    (doseq [t (take 5 (:tried result))]
      (println (str "  " (:repo t) "\t" (:path t) "\t" (or (:skip t) "lower score"))))
    ;; record the pick so the next run doesn't propose it again for
    ;; seen-ttl-days, whether this run lands it, opens a PR, or gives up.
    (fs/mkdirSync (path/dirname seen-path) #js {:recursive true})
    (fs/writeFileSync seen-path
                       (pr-str (assoc seen (:path c) now-ms)))
    (.exit js/process 0))
  (do
    (println (str "No candidate this run. " (count (:tried result))
                   " were ranked and all were either recently proposed or "
                   "already have an open PR."))
    (println "This is a legitimate empty report — do not invent a candidate.")
    (.exit js/process 0)))

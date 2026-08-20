(ns ipni-maturity.coscientist
  "Co-Scientist loop over the IPNI pipeline — Generate -> Reflect -> Rank
  (Elo) -> Evolve -> Meta, the shape ported into this repo from
  isekai.ux.coscientist (ADR-0007) and used by design-quality
  (ADR-2607132300).

  **The Elo engine is reused, not copied.** `rank` and `evolve` come from
  `design-quality.coscientist`; only Generate, Reflect and the write-up are
  domain-specific, because only those depend on what IPNI is. Copying the
  tournament would have been the third instance this week of the failure
  this workspace keeps paying for — arrangement/datalog drifting 20 commits
  apart, and eleven byte-identical kotobase-protocol files with s3.cljc
  already 165 lines apart.

  **The judge is `ipni-maturity.audit` over `ipni-maturity.probe`** —
  measured HTTP statuses and repo facts, never an LLM debate. design-quality
  learned this the expensive way: a 3-judge LLM panel scored those libraries
  4.0–5.0/5 on every axis while missing four concrete defects a regex found.
  An unmeasured metric is theater.

  Run:  nbb --classpath 90-docs 90-docs/ipni-maturity/coscientist.cljs /tmp/ipni-probe.edn"
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            ["fs" :as fs]
            [ipni-maturity.audit :as audit]
            [kaizen.elo :as elo]))

;; --- Generate ---------------------------------------------------------------
;; One grounded hypothesis per measured finding. `:blocked-by` is a first-class
;; field: a hypothesis that cannot be executed yet must say what it waits on,
;; otherwise the roadmap ranks it as shippable and the batch quietly contains
;; work nobody can start.

(defn- hypothesis [{:keys [axis headroom worst-score finding title] :as f} idx]
  (let [{:keys [change effort blocked-by shippable? observable?]}
        (case axis
          :discoverable-as-provider
          {:observable? true
           :change "advertise one CID end-to-end and confirm cid.contact returns the kotobase multiaddr, then IsRm and confirm it disappears — the ADR's own success criterion, both halves"
           :effort :L :shippable? false
           :blocked-by "publisher identity (a peer ID) and the advertisement signature"}

          :chain-published
          {:observable? true
           :change "build the first advertisement, PUT it to ipld/{ad-cid} in the block plane, write the CID to ipni/head, and announce it"
           :effort :L :shippable? false
           :blocked-by "publisher identity (a peer ID) and the advertisement signature"}

          :advertise-wired
          {:observable? true
           :change "drain the pin outbox: read the queued events, build an advertisement per event via ipni.advertise, clear on accept, leave queued on reject"
           :effort :M :shippable? false
           :blocked-by "an advertisement needs a signed Provider, so the drain cannot be finished before the identity exists"}

          :dependency-declared
          {:observable? false
           :change "declare io-ipni-specs in the protocols-worker deps/upstream-lock so the library is reachable from deployed code — a prerequisite of the drain, and independently verifiable"
           :effort :S :shippable? true :blocked-by nil}

          :entries-hamt
          {:observable? false
           :change "implement ipni.hamt/as-set (HAMT-as-set per ipni/specs) so one advertisement is not capped at a single EntryChunk"
           :effort :M :shippable? true :blocked-by nil}

          :publisher-anonymous
          {:change "serve the publisher from a host that answers without a credential"
           :effort :S :shippable? true :blocked-by nil}

          :ad-bytes-servable
          {:change "serve GET /ipni/v1/ad/{cid} from the block plane"
           :effort :S :shippable? true :blocked-by nil}

          :publisher-narrow
          {:change "404 everything on the publisher host that is not /ipni/v1/*"
           :effort :S :shippable? true :blocked-by nil}

          :read-path
          {:change "add https://cid.contact/routing/v1 to kad.routing/default-routers"
           :effort :S :shippable? true :blocked-by nil}

          :retrieval-witness
          {:change "make the retrieval gateway serve the advertised CID — an advertisement pointing at bytes that are not there is worse than none"
           :effort :M :shippable? true :blocked-by nil}

          :publisher-reachable
          {:change "bring the publisher host up"
           :effort :S :shippable? true :blocked-by nil}

          {:change (str "address: " finding) :effort :M :shippable? false
           :blocked-by "no hypothesis registered for this axis"})]
    {:id (str "ipni-h" (inc idx))
     :axis axis :title title :finding finding
     :change change :effort effort
     :predicted-gain headroom :worst-score worst-score
     ;; `rank`'s tie-break reads :consumer-fixable? — here that means
     ;; "can be shipped without waiting on the identity decision".
     :shippable? (boolean shippable?)
     :blocked-by blocked-by
     :observable? (boolean observable?)}))

(defn generate [findings]
  (vec (map-indexed (fn [i f] (hypothesis f i)) findings)))

;; --- Reflect ----------------------------------------------------------------

(defn reflect [hyps]
  (mapv (fn [h]
          (assoc h :reflection
                 {:risk (cond
                          (:blocked-by h) :blocked
                          (= :S (:effort h)) :low
                          :else :medium)
                  :note (or (some->> (:blocked-by h) (str "cannot start: "))
                            "no external dependency; verifiable in this repo")}))
        hyps))

;; --- Evolve: only the genuinely startable work becomes a batch --------------

(defn evolve [ranked]
  (let [batch (filterv #(= :low (get-in % [:reflection :risk])) ranked)]
    {:batch-id "ipni-maturity-kaizen-1"
     :members (mapv :id batch)
     :hypotheses batch
     :deferred (mapv (fn [h] {:id (:id h) :blocked-by (:blocked-by h)})
                     (filterv #(= :blocked (get-in % [:reflection :risk])) ranked))}))

;; --- Meta -------------------------------------------------------------------

(defn- pct [x] (.toFixed (double x) 2))

(defn gain-points [headroom measured-weight]
  (if (zero? measured-weight) 0.0 (/ (* 100.0 headroom) measured-weight)))

(defn meta-review [{:keys [overall measured-weight]} ranked evolved]
  (let [gp #(assoc % :gain-points (gain-points (:predicted-gain %) measured-weight))
        ranked (mapv gp ranked)
        evolved (update evolved :hypotheses #(mapv gp %))
        reachable (+ overall (reduce + (map :gain-points (:hypotheses evolved))))]
    {:top-pick (first ranked)
     :overall overall
     :reachable-without-unblocking (min 100.0 reachable)
     :roadmap ranked
     :batch evolved
     ;; The stage that stops this loop from congratulating itself. A batch
     ;; whose every member is unobservable raises the score without changing
     ;; anything a stranger can see -- the same defect as an unmeasured
     ;; metric, wearing the opposite mask. Say so in the output rather than
     ;; reporting a gain.
     :batch-observable? (boolean (some :observable? (:hypotheses evolved)))
     :unobservable-gain (reduce + (map :gain-points
                                       (remove :observable? (:hypotheses evolved))))}))

(defn run [probe]
  (let [before (audit/audit probe)
        hyps (-> (:findings before) generate reflect)
        ranked (elo/rank hyps)
        evolved (evolve ranked)]
    {:probe probe :before before :meta (meta-review before ranked evolved)}))

(defn iteration-md
  [n {:keys [overall findings incomplete measured-weight]} meta probe]
  (let [{:keys [roadmap batch reachable-without-unblocking
                batch-observable? unobservable-gain]} meta]
    (str
     "# IPNI maturity — Co-Scientist iteration " (if (< n 10) (str "0" n) n) "\n\n"
     "> Judge: `ipni-maturity.audit` over `ipni-maturity.probe` — measured HTTP\n"
     "> statuses and manifest facts, no LLM. Ranking: `kaizen.elo`, round-robin,\n"
     "> the measured gain is the judge.\n>\n"
     "> Probed " (:measured-at probe) ". "
     (pct (* 100.0 (/ measured-weight audit/total-weight))) "% of the rubric was measurable"
     (if (seq incomplete)
       (str "; UNMEASURED (excluded from the score, not zeroed): "
            (str/join ", " (map name incomplete)))
       "; nothing was unmeasurable, so nothing is excluded")
     ".\n\n"
     "## Score\n\n**" (pct overall) " / 100**\n\n"
     "## Findings (heaviest recoverable headroom first)\n\n"
     "| axis | weight | finding |\n|---|---|---|\n"
     (str/join "\n" (map (fn [f] (str "| `" (name (:axis f)) "` | " (:weight f) " | "
                                       (:finding f) " |")) findings))
     "\n\n## Roadmap (Elo)\n\n"
     "| Elo | id | axis | startable? | observable? | +gain |\n|---|---|---|---|---|---|\n"
     (str/join "\n" (map (fn [h] (str "| " (:elo h) " | `" (:id h) "` | `" (name (:axis h))
                                       "` | " (if (:blocked-by h) (str "no — " (:blocked-by h)) "yes")
                                       " | " (if (:observable? h) "yes" "no")
                                       " | +" (pct (:gain-points h)) " |")) roadmap))
     "\n\n## Meta\n\n"
     (if batch-observable?
       (str "Batch `" (:batch-id batch) "` = " (str/join ", " (:members batch))
            ". Reachable without unblocking: **" (pct reachable-without-unblocking) " / 100**.\n")
       (str "**Every member of the startable batch is unobservable from outside.**\n\n"
            "Shipping all of it moves the score **+" (pct unobservable-gain)
            "** (to " (pct reachable-without-unblocking) " / 100) without changing anything a\n"
            "stranger can see. That is the same defect as an unmeasured metric wearing the\n"
            "opposite mask, so this iteration does not bank the gain: it reports the blocked\n"
            "work instead.\n\n"
            "The three highest-Elo hypotheses share one blocker:\n\n"
            (str/join "\n" (distinct (map (fn [h] (str "- " (:blocked-by h)))
                                           (filter :blocked-by roadmap))))
            "\n\nThat is a decision, not a task. Until it is made, IPNI maturity is capped at "
            (pct reachable-without-unblocking) " / 100 and every point of the remainder is behind it.\n"))
     "\n## Method note\n\n"
     "Three earlier versions of the probe swept `orgs/` directly for call sites; on 4,400\n"
     "checkouts none finished, and each reported the two heaviest axes as `:unknown`. The\n"
     "working version derives them instead: a namespace cannot be required without its\n"
     "dependency on the classpath, so zero declarations across "
     (str (:repos-listed (:write/dep-scan probe))) " repos ("
     (str (:manifests-read (:write/dep-scan probe))) " manifests read) implies zero call\n"
     "sites. The evidence floor is reported so 0 hits cannot be read as 0 looked at.\n")))

(defn -main [& args]
  (let [argv (vec (or (seq args) (seq *command-line-args*) []))
        probe-file (first (remove #(str/starts-with? % "--") argv))
        _ (when-not (and probe-file (fs/existsSync probe-file))
            (println "usage: coscientist.cljs <probe.edn>")
            (js/process.exit 2))
        probe (edn/read-string (str (fs/readFileSync probe-file "utf8")))
        md-out (loop [a argv] (cond (empty? a) nil
                                    (= "--md" (first a)) (second a)
                                    :else (recur (rest a))))
        {:keys [before meta]} (run probe)
        _ (when md-out
            ;; EDN, not .md: 90-docs's source of truth is EDN documents
            ;; (ADR-2607171600), and design-quality's own iterations are
            ;; stored the same way -- a :doc/body string inside tx-data.
            (fs/writeFileSync
             md-out
             (pr-str [{:db/id -1
                       :doc/id (str "doc-ipni-maturity-iteration-01")
                       :doc/title "IPNI maturity — Co-Scientist iteration 01"
                       :doc/path md-out
                       :source/dataset "ipni-maturity"
                       :doc/measured-at (:measured-at probe)
                       :doc/score (:overall before)
                       :doc/body (iteration-md 1 before meta probe)}]))
            (println "wrote" md-out))]
    (println "IPNI maturity:" (pct (:overall before)) "/ 100"
             (str "(" (pct (* 100.0 (/ (:measured-weight before) audit/total-weight)))
                  "% of the rubric was measurable)"))
    (when (seq (:incomplete before))
      (println "UNMEASURED axes (excluded from the score, not zeroed):"
               (str/join ", " (map name (:incomplete before)))))
    (println)
    (println "findings, heaviest headroom first:")
    (doseq [f (:findings before)]
      (println (str "  " (name (:axis f)) "  w=" (:weight f) "  " (:finding f))))
    (println)
    (println "Elo roadmap:")
    (doseq [h (:roadmap meta)]
      (println (str "  " (:elo h) "  " (:id h) "  " (name (:axis h))
                    (if (:blocked-by h) (str "  [BLOCKED: " (:blocked-by h) "]") "  [startable]"))))
    (println)
    (println "batch" (:batch-id (:batch meta)) "=" (str/join ", " (:members (:batch meta))))
    (println "reachable without unblocking:" (pct (:reachable-without-unblocking meta)) "/ 100")
    (when-not (:batch-observable? meta)
      (println)
      (println "META: every member of this batch is UNOBSERVABLE from outside.")
      (println (str "  Shipping the whole batch moves the score +"
                    (pct (:unobservable-gain meta))
                    " without changing anything a stranger can see."))
      (println "  The score would rise; discoverability would not. Report the")
      (println "  blocked work instead of banking the gain."))))

(apply -main *command-line-args*)

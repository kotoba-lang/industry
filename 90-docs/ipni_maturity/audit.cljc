(ns ipni-maturity.audit
  "Deterministic judge for the IPNI publishing pipeline (ADR-2608160300).

  Same contract as `design-quality.audit`: weighted axes, each returning
  `{:score 0..1 :finding str?}`, and `audit` returning
  `{:overall :axes :findings :incomplete}` with findings sorted by recoverable
  headroom. That shape is what `design-quality.coscientist/rank` consumes, so
  the Elo engine is reused rather than copied -- see this repo's own lesson
  about second copies (arrangement/datalog, and the eleven duplicated
  kotobase-protocol files found on 2026-08-20).

  ## What makes this a judge and not an opinion

  Every input is a measurement from `ipni-maturity.probe`: an HTTP status
  from the live surface, or a count from the repositories. Nothing here reads
  a document to decide whether something works. The ADR said the publisher
  was serving and the outbox was drained; the first was true, the second was
  not, and only a probe could tell them apart.

  ## :unknown is not zero

  An axis whose input could not be measured scores **nil**, not 0.0, and is
  reported in `:incomplete`. `overall` is the mean over MEASURED axes only.
  A failed probe and a failed system must not produce the same number --
  that is the failure this workspace names most often, and a maturity score
  is exactly the place it would hide."
  (:require [clojure.string :as str]))

(defn- unknown? [v] (or (nil? v) (= :unknown v)))

(defn- status-is
  "1.0 when the measured status is in `ok`, else 0.0, else nil if unmeasured."
  [v ok finding]
  (cond
    (unknown? v) {:score nil :finding (str finding " (UNMEASURED — probe could not reach it)")}
    (contains? ok v) {:score 1.0}
    :else {:score 0.0 :finding finding}))

(defn- count-at-least
  [v n finding]
  (cond
    (unknown? v) {:score nil :finding (str finding " (UNMEASURED)")}
    (>= v n) {:score 1.0}
    :else {:score 0.0 :finding finding}))

(def axes
  "Weights say what a maturity point is worth, and they are not uniform on
  purpose. `:discoverable-as-provider` and `:chain-published` carry the most
  because they are the only two axes a stranger can observe: everything else
  can be green while the network still cannot find us, which is precisely
  the state measured on 2026-08-20."
  [{:id :discoverable-as-provider :title "cid.contact lists kotobase as a provider" :weight 0.20
    :check (fn [p] (count-at-least (:indexer/lists-kotobase p) 1
                                   "a CID kotobase serves resolves to third-party providers only — kotobase is not among them"))}

   {:id :chain-published :title "An advertisement chain exists (GET /ipni/v1/head)" :weight 0.18
    :check (fn [p] (status-is (:publisher/head p) #{200}
                              "no advertisement chain has ever been published — /ipni/v1/head is 404"))}

   {:id :advertise-wired :title "Something in production calls the advertise path" :weight 0.15
    :check (fn [p] (count-at-least (:write/call-sites p) 1
                                   "zero call sites for ipni.advertise/announce/ad/head anywhere in the workspace"))}

   {:id :publisher-anonymous :title "The publisher is readable without a credential" :weight 0.12
    :check (fn [p]
             (let [v (:publisher/head p)]
               (cond
                 (unknown? v) {:score nil :finding "publisher head UNMEASURED"}
                 (contains? #{401 403} v)
                 {:score 0.0 :finding "the publisher answers 401/403 — an indexer crawls anonymously and can never read the chain"}
                 :else {:score 1.0})))}

   {:id :ad-bytes-servable :title "Advertisement bytes are retrievable by CID" :weight 0.12
    :check (fn [p] (status-is (:publisher/ad-bytes p) #{200}
                              "GET /ipni/v1/ad/{cid} does not return bytes — an announced chain could not be crawled"))}

   {:id :dependency-declared :title "A consumer declares io-ipni-specs" :weight 0.08
    :check (fn [p] (count-at-least (:write/declared-dependency p) 1
                                   "io-ipni-specs is not a declared dependency of anything — the library is unreachable from any deployed code"))}

   {:id :retrieval-witness :title "The gateway actually serves the advertised content" :weight 0.06
    :check (fn [p] (status-is (:gateway/serves p) #{200}
                              "the retrieval gateway does not serve the witness CID — an advertisement would point at nothing"))}

   {:id :publisher-narrow :title "The publisher serves only the publisher surface" :weight 0.05
    :check (fn [p] (status-is (:publisher/narrow-surface p) #{404}
                              "the publisher host also serves the gateway — wider surface than the role needs"))}

   {:id :read-path :title "IPNI answers reach our reads" :weight 0.05
    :check (fn [p] (count-at-least (:read/cid-contact-router p) 1
                                   "cid.contact is not among the default routers — IPNI-backed answers never reach find-providers"))}

   {:id :publisher-reachable :title "Publisher host is up" :weight 0.04
    :check (fn [p] (status-is (:publisher/health p) #{200} "publisher /health is not 200"))}

   {:id :entries-hamt :title "HAMT entry encoding" :weight 0.03
    :check (fn [p] (count-at-least (:entries/hamt p) 1
                                   "ipni.hamt returns :not-yet-implemented — honest, but caps an advertisement at one EntryChunk"))}])

(def total-weight (reduce + (map :weight axes)))

(defn audit
  "Score one probe map. `overall` is 0..100 over MEASURED axes only;
  unmeasured axes are listed in `:incomplete` and excluded from both the
  score and the findings, so a probe failure can never look like a pass or
  like a regression."
  [probe]
  (let [scored (mapv (fn [{:keys [id title weight check]}]
                       (let [{:keys [score finding]} (check probe)]
                         {:axis id :title title :weight weight
                          :score score :finding finding
                          :unknown? (nil? score)}))
                     axes)
        measured (filterv (complement :unknown?) scored)
        mw (reduce + (map :weight measured))
        overall (if (zero? mw) 0.0
                    (* 100.0 (/ (reduce + (map (fn [a] (* (:weight a) (:score a))) measured)) mw)))
        findings (->> measured
                      (filter :finding)
                      (mapv (fn [a] {:axis (:axis a) :weight (:weight a)
                                     :title (:title a)
                                     :pages ["ipni-pipeline"]
                                     :finding (:finding a)
                                     :worst-score (:score a)
                                     :headroom (* (:weight a) (- 1.0 (:score a)))}))
                      (sort-by :headroom >)
                      vec)]
    {:overall overall
     :axes scored
     :findings findings
     :measured-weight mw
     :incomplete (mapv :axis (filterv :unknown? scored))}))

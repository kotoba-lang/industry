(ns auth-plane-integration.audit
  "Score one probe of the six apexes against the five integration invariants.

  ## Boundary with the nearest instrument

  `90-docs/kotobase_auth_maturity` measures ONE apex in depth: how mature
  kotobase.net's own authentication and authorization planes are. This
  measures the OTHER question -- whether the apexes agree with each other.
  An apex can score 100 there and 0 here (it has a fine auth plane of its
  own that nothing else shares), and the reverse is also possible.

  ## :unknown is not a zero

  An axis that could not be measured is `:unknown`, and an apex holding one
  gets no score at all rather than a low one. `run` then refuses to report a
  complete number. This is the failure class this workspace names most often:
  a check that could not run returning the value of a check that ran and
  found nothing wrong."
  (:require [clojure.string :as str]))

;; Weights sum to 1.00 per apex. The ordering is the causal one: you cannot
;; read a capability you cannot mint, and you cannot mint one without a
;; ceremony that actually completes.
(def axes
  [{:axis :controller-live          :weight 0.25
    :claim "a passkey ceremony is obtainable at this apex"}
   {:axis :one-authority            :weight 0.20
    :claim "that ceremony is the shared authn contract, not a second implementation"}
   {:axis :capability-issuance      :weight 0.20
    :claim "the apex can mint a Biscuit, and guards the mint against cross-origin"}
   {:axis :refusal-distinguishes-credential :weight 0.20
    :claim "a refused credential is distinguishable from none presented"}
   {:axis :refusal-names-credential :weight 0.10
    :claim "a refusal names the credential class it wanted"}
   {:axis :auth-failure-status      :weight 0.05
    :claim "an authentication failure carries 401/403, not 200"}
   ;; Added 2026-08-31. The design's central claim is that Biscuit is what
   ;; authorizes, and NO axis measured anything about it at the accepting end:
   ;; x402.nexus began verifying Biscuits with a public root and its score did
   ;; not move. A scoreboard that cannot see the thing it exists to measure is
   ;; worse than a low number.
   ;;
   ;; NAMED for what it can see. Without a credential this cannot observe
   ;; acceptance -- only whether the surface SAYS a capability is accepted. The
   ;; previous axis in this file was renamed for exactly this mistake, so:
   ;; advertises, not accepts.
   ;;
   ;; ⚠ Adding an axis changes the denominator. Scores before and after are on
   ;; different scales and must not be compared as a trend.
   {:axis :refusal-advertises-capability :weight 0.10
    :claim "the refusal tells a stranger that a capability credential is accepted here"}])

(def total-weight (reduce + (map :weight axes)))

(defn- axis-value
  "Read one axis out of one apex's probe map. Returns true, false or :unknown."
  [a axis]
  (get a axis :unknown))

(defn score-apex
  "{:apex s :score double-or-nil :findings [...] :incomplete [axis ...]}

  `:score` is nil -- not 0.0 -- when any axis is :unknown."
  [a]
  (let [vs (for [{:keys [axis weight claim]} axes]
             (assoc {:axis axis :weight weight :claim claim}
                    :value (axis-value a axis)))
        incomplete (mapv :axis (filter #(= :unknown (:value %)) vs))
        earned (reduce + (map #(if (true? (:value %)) (:weight %) 0.0) vs))]
    {:apex (:apex a)
     :score (when (empty? incomplete) (* 100.0 (/ earned total-weight)))
     :incomplete incomplete
     :findings (vec (for [v vs :when (false? (:value v))]
                      {:apex (:apex a)
                       :axis (:axis v)
                       :headroom (:weight v)
                       :finding (str (:apex a) ": " (:claim v) " -- no")}))}))

(defn audit
  "Score every apex in the probe. `:overall` is the mean of the apex scores
  and is nil whenever any apex is incomplete."
  [p]
  (let [rs (mapv score-apex (:apexes p))
        incomplete (vec (mapcat (fn [r] (map #(vector (:apex r) %) (:incomplete r))) rs))
        scores (keep :score rs)]
    {:by-apex (vec (sort-by (fn [r] (or (:score r) -1)) rs))
     :incomplete incomplete
     :overall (when (empty? incomplete)
                (/ (reduce + scores) (double (max 1 (count scores)))))
     :findings (vec (sort-by (comp - :headroom) (mapcat :findings rs)))}))

(defn format-report [p a]
  (str/join
   "\n"
   (concat
    [(str "auth plane integration — " (:probe/at p))
     (str "  overall        "
          (if (:overall a) (str (.toFixed (:overall a) 2) " / 100") "UNMEASURED")
          "   (" (count (:by-apex a)) " apexes)")
     ""]
    (for [r (:by-apex a)]
      (str "  " (str/join (repeat (max 0 (- 16 (count (:apex r)))) " "))
           (:apex r) "  "
           (if (:score r) (str (.toFixed (:score r) 2)) "UNMEASURED")))
    [""]
    (for [f (:findings a)]
      (str "  [" (.toFixed (:headroom f) 3) "] " (name (:axis f))
           "\n        " (:finding f)))
    (when (seq (:incomplete a))
      [""
       (str "  UNMEASURED: "
            (str/join ", " (map (fn [[apex axis]] (str apex "/" (name axis)))
                                (:incomplete a))))
       "  REFUSING to report a complete score."]))))

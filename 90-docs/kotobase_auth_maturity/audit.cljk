(ns kotobase-auth-maturity.audit
  "Deterministic judge for the kotobase.net auth planes (ADR-2608291700).

  Same contract as `ipni-maturity.audit`: weighted axes, each returning
  `{:score 0..1 :finding str?}`, and `audit` returning
  `{:overall :axes :findings :incomplete}`. Reused rather than copied where
  the shape is identical; the axes are this plane's own.

  ## Two planes, deliberately not averaged into one word

  The owner's instruction of 2026-08-29 separates them: authentication is
  passkey plus smart contract, authorization is Biscuit. A single number
  hides the case this plane is actually in -- authentication landed, and
  authorization stops one hop short of the surfaces that serve reads. So
  `audit` reports `:by-plane` alongside `:overall`, and the weights are
  chosen so that no amount of authentication maturity can conceal an
  authorization surface that does not read the header.

  ## An unmeasured axis scores nil

  Not 0.0. `overall` is the weighted mean over MEASURED axes only, and the
  unmeasured ones are named in `:incomplete`. A probe that could not reach
  a host and a host that is genuinely broken must not produce the same
  number."
  (:require [clojure.string :as str]))

(defn- unknown? [v] (or (nil? v) (= :unknown v)))

(defn- is-true
  "1.0 when the measured boolean is true, 0.0 when it is false, nil when the
  probe could not read it."
  [v finding]
  (cond
    (unknown? v) {:score nil :finding (str finding " (UNMEASURED)")}
    (true? v) {:score 1.0}
    :else {:score 0.0 :finding finding}))

(defn- status-is [v ok finding]
  (cond
    (unknown? v) {:score nil :finding (str finding " (UNMEASURED)")}
    (contains? ok v) {:score 1.0}
    :else {:score 0.0 :finding (str finding " — measured " v)}))

(defn- fraction-of
  "Partial credit, because 2 of 4 surfaces accepting a token is a real and
  reportable midpoint, and rounding it to 0 or 1 would erase the only
  signal that a rollout is in progress."
  [n total finding]
  (cond
    (or (unknown? n) (unknown? total) (zero? total)) {:score nil :finding (str finding " (UNMEASURED)")}
    (= n total) {:score 1.0}
    :else {:score (double (/ n total))
           :finding (str finding " — " n "/" total)}))

(def axes
  [;; ---- authentication: passkey ----
   {:id :passkey-login-live :plane :authentication :weight 0.12
    :title "Passkey login issues a real WebAuthn challenge"
    :check (fn [p]
             (cond
               (unknown? (:authn/passkey-login-options p))
               {:score nil :finding "passkey login options UNMEASURED"}
               (not= 200 (:authn/passkey-login-options p))
               {:score 0.0 :finding (str "POST /v1/passkey/login/options is not 200 — measured "
                                         (:authn/passkey-login-options p))}
               (not (and (true? (:authn/passkey-challenge-issued p))
                         (true? (:authn/passkey-rp-id p))))
               {:score 0.0 :finding "login options answered 200 but carried no challenge/rpId — a 200 that is not a credential ceremony"}
               :else {:score 1.0}))}

   {:id :passkey-registration-live :plane :authentication :weight 0.06
    :title "Passkey registration exists and states its precondition"
    :check (fn [p]
             (if (false? (:authn/passkey-register-present p))
               {:score 0.0 :finding "POST /v1/passkey/register/options is absent — passkeys can be used but never enrolled"}
               (is-true (:authn/passkey-register-reason-named p)
                        "registration refuses without naming what it needs — a caller cannot act on it")))}

   ;; ---- authentication: smart contract ----
   {:id :smart-contract-signin-live :plane :authentication :weight 0.10
    :title "SIWE (smart-contract / wallet) sign-in is served"
    :check (fn [p]
             (cond
               (or (unknown? (:authn/siwe-options-present p))
                   (unknown? (:authn/siwe-verify-present p)))
               {:score nil :finding "SIWE endpoints UNMEASURED"}
               (and (:authn/siwe-options-present p) (:authn/siwe-verify-present p))
               {:score 1.0}
               :else {:score 0.0 :finding "SIWE options/verify not both present — wallet sign-in is not a complete path"}))}

   {:id :signin-offers-both :plane :authentication :weight 0.06
    :title "The sign-in page ships both credential modules"
    :check (fn [p]
             (cond
               (and (:authn/signin-ships-passkey p) (:authn/signin-ships-wallet p)) {:score 1.0}
               (or (:authn/signin-ships-passkey p) (:authn/signin-ships-wallet p))
               {:score 0.5 :finding "the sign-in page ships only one of passkey.js / wallet.js — the other basis is reachable by API but not offered to a person"}
               :else {:score 0.0 :finding "the sign-in page offers neither passkey nor wallet — the declared basis is not the served basis"}))}

   ;; ---- authorization: biscuit ----
   {:id :biscuit-issuance-live :plane :authorization :weight 0.10
    :title "Biscuit tokens can be issued"
    :check (fn [p] (is-true (:biscuit/issuance-present p)
                            "POST /v1/biscuit/token is absent — nothing can obtain the credential the plane is built on"))}

   {:id :biscuit-issuance-guarded :plane :authorization :weight 0.04
    :title "Issuance refuses cross-origin callers"
    :check (fn [p] (is-true (:biscuit/issuance-same-origin-guarded p)
                            "issuance does not enforce same-origin — a session cookie on this host mints tokens for any page that asks"))}

   {:id :datom-plane-requires-biscuit :plane :authorization :weight 0.10
    :title "The datom plane refuses non-Biscuit credentials"
    :check (fn [p] (status-is (:biscuit/datom-plane-status p) #{401 403}
                              "datomic.q does not refuse an unauthenticated read"))}

   {:id :datom-plane-names-its-reason :plane :authorization :weight 0.06
    :title "That refusal names Biscuit"
    :check (fn [p] (is-true (:biscuit/datom-plane-names-biscuit p)
                            "the datom plane answers a bare \"Unauthorized\" — a CACAO speaker locked out by the cutover cannot learn why from the response"))}

   {:id :surfaces-accept-biscuit :plane :authorization :weight 0.20
    :title "The query surfaces accept Biscuit"
    :check (fn [p] (fraction-of (:biscuit/surfaces-naming-biscuit p)
                                (:biscuit/surfaces-total p)
                                "query surfaces still enumerate only Bearer / SigV4 / operator CACAO"))}

   {:id :surface-reads-the-header :plane :authorization :weight 0.10
    :title "Presenting a Biscuit changes the surface's answer"
    :check (fn [p] (is-true (:biscuit/surface-header-changes-answer p)
                            "the refusal is byte-identical with and without an Authorization: Biscuit header — the surface does not read it at all"))}

   {:id :credentials-discoverable :plane :authorization :weight 0.06
    :title "The capability document says which credential to present"
    :check (fn [p] (is-true (:discovery/capabilities-names-credentials p)
                            "/.well-known/kotobase-capabilities describes transports and limits but never names an accepted credential — discovery stops before the door"))}])

(def total-weight (reduce + (map :weight axes)))

(defn audit
  "Score one probe map. 0..100 over MEASURED axes only, plus a per-plane
  breakdown so that a mature authentication plane cannot mask an
  authorization plane that is one hop short."
  [probe]
  (let [scored (mapv (fn [{:keys [id title weight check plane]}]
                       (let [{:keys [score finding]} (check probe)]
                         {:axis id :title title :weight weight :plane plane
                          :score score :finding finding :unknown? (nil? score)}))
                     axes)
        measured (filterv (complement :unknown?) scored)
        mean (fn [as]
               (let [w (reduce + (map :weight as))]
                 (if (zero? w) nil
                     (* 100.0 (/ (reduce + (map #(* (:weight %) (:score %)) as)) w)))))]
    {:overall (or (mean measured) 0.0)
     :by-plane (into {} (for [pl [:authentication :authorization]]
                          [pl (mean (filterv #(= pl (:plane %)) measured))]))
     :axes scored
     :measured-weight (reduce + (map :weight measured))
     :findings (->> measured
                    (filter :finding)
                    (mapv (fn [a] {:axis (:axis a) :plane (:plane a) :weight (:weight a)
                                   :title (:title a) :finding (:finding a)
                                   :score (:score a)
                                   :headroom (* (:weight a) (- 1.0 (:score a)))}))
                    (sort-by :headroom >)
                    vec)
     :incomplete (mapv :axis (filterv :unknown? scored))}))

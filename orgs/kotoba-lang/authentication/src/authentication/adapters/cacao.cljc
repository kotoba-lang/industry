(ns authentication.adapters.cacao
  (:require [authentication.model :as m]
            [authentication.ports :as p]
            [cacao.core :as cacao]))

(defprotocol ICacaoVerifier
  (verify-cacao! [verifier payload opts]))

(defn cacao-factor-verifier
  ([verifier] (cacao-factor-verifier verifier {}))
  ([verifier opts]
   (reify p/IFactorVerifier
     (verify-factor! [_ factor-request response]
       (let [claims (verify-cacao! verifier {:request factor-request
                                             :response response}
                                   opts)]
         (m/factor (:authn.factor-request/id factor-request)
                   :cacao
                   (true? (:ok? claims))
                   {:subject (or (:subject claims)
                                 (:authn.factor-request/subject factor-request))
                    :evidence-ref (:evidence-ref claims)
                    :assurance (:assurance claims)
                    :at (:at claims)}))))))

(defn static-cacao-verifier [claims]
  (reify ICacaoVerifier
    (verify-cacao! [_ _payload _opts] claims)))

;; ── production: real did:key CACAO verification (ADR-2607050400) ────────────
;; Bridges to kotoba-lang/cacao's cacao.core/verify + verify-chain -- the same
;; self-issued, no-owner-hand-off CACAO kekkai/kagi actors mint. Pure/offline:
;; did:key CACAO verification needs no injected host port, unlike every other
;; production adapter in this repo (webauthn/faceid/touchid all wrap a real
;; verifier port because their crypto lives on a device/OS the code can't
;; reach directly).

(defn- single-cacao-result [cacao-b64]
  (let [{:keys [valid? iss payload]} (cacao/verify cacao-b64)]
    {:ok? valid? :subject iss :evidence-ref iss
     :assurance (when valid? :single-factor) :at (:exp payload)}))

(defn- chain-cacao-result [chain]
  (let [{:chain/keys [valid? root-iss holder expires]} (cacao/verify-chain chain)]
    {:ok? valid? :subject holder :evidence-ref root-iss
     :assurance (when valid? :single-factor) :at expires}))

(defn production-cacao-verifier
  "Real ICacaoVerifier via cacao.core. `payload`'s `:response` must carry
   either `:cacao/cacao-b64` (a single self-issued CACAO) or `:cacao/chain`
   (an ordered vector of cacao-b64 strings, a delegation chain -- verified via
   `cacao.core/verify-chain`). Never `:phishing-resistant` on its own (see
   ADR-2607050400: SIWE/EIP-4361 signing has no browser/OS-enforced origin
   binding the way WebAuthn does, so a CACAO factor's assurance tops out at
   `:single-factor` here regardless of the signature's own validity)."
  []
  (reify ICacaoVerifier
    (verify-cacao! [_ payload _opts]
      (let [response (:response payload)]
        (if-let [chain (:cacao/chain response)]
          (chain-cacao-result chain)
          (single-cacao-result (:cacao/cacao-b64 response)))))))

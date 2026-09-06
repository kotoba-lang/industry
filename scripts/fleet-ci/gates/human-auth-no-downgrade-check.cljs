#!/usr/bin/env nbb
;; The gate validates declared human-auth authorities. It intentionally does not
;; scan protocol mirrors or libraries for words such as OAuth, Email, or SAML.

(require '[cljs.reader :as reader]
         '[clojure.set :as set]
         '[clojure.string :as str]
         '["node:fs" :as fs]
         '["node:path" :as path])

(def required-files
  ["SECURITY.md"
   "AGENTS.md"
   "manifest/west.yml"
   "manifest/human-authentication-policy.edn"
   "90-docs/adr/2609070400-human-authentication-is-web3-first.edn"])

(def required-prohibited
  #{:email-link :email-otp :password :sms-otp :voice-otp :oauth :oidc :saml
    :social-sso :enterprise-sso :support-judgment :operator-reset
    :administrator-override})

(def required-security-phrases
  ["Human Authentication Invariants"
   "Web3 first"
   "ERC-191"
   "ERC-1271"
   "Recovery must replace a credential"
   "server-enforced delay"
   "Closed legacy routes must return 404 or 410"
   "Unverified means unverified, not conformant"])

(defn read-text [root rel]
  (.readFileSync fs (.join path root rel) "utf8"))

(defn fail! [errors]
  (println (str "human-auth-no-downgrade: " (count errors) " violation(s)"))
  (doseq [error errors] (println (str "- " error)))
  (js/process.exit 1))

(defn surface-errors [surface statuses approved prohibited west]
  (let [{:keys [project path active-methods status security-policy]} surface
        registered? (str/includes? west (str "    - name: " project "\n"))]
    (vec
     (concat
      (when-not (and project path security-policy)
        [(str "surface is missing project/path/security-policy: " (pr-str project))])
      (when-not (contains? statuses status)
        [(str project " has invalid status " (pr-str status))])
      (when-not (set/subset? active-methods approved)
        [(str project " declares non-approved active methods "
              (pr-str (set/difference active-methods approved)))])
      (when (seq (set/intersection active-methods prohibited))
        [(str project " declares prohibited active authority")])
      (when-not registered?
        [(str project " is not registered in manifest/west.yml")])))))

(let [argv (vec *command-line-args*)
      root (or (first (remove #(.startsWith % "--") argv)) ".")
      missing (filterv #(not (.existsSync fs (.join path root %))) required-files)]
  (when (seq missing)
    (println "human-auth-no-downgrade: required input was not shipped")
    (doseq [rel missing] (println (str "- missing " rel)))
    (js/process.exit 2))

  (let [security (read-text root "SECURITY.md")
        agents (read-text root "AGENTS.md")
        west (read-text root "manifest/west.yml")
        policy (reader/read-string (read-text root "manifest/human-authentication-policy.edn"))
        adr (reader/read-string
             (read-text root "90-docs/adr/2609070400-human-authentication-is-web3-first.edn"))
        surfaces (:inventory/surfaces policy)
        statuses (:inventory/allowed-statuses policy)
        approved (:human-auth/approved-active-methods policy)
        prohibited (:human-auth/prohibited-authority policy)
        claim? (:inventory/workspace-conformance-claim? policy)
        all-conformant? (and (seq surfaces) (every? #(= :conformant (:status %)) surfaces))
        projects (map :project surfaces)
        errors
        (vec
         (concat
          (for [phrase required-security-phrases :when (not (str/includes? security phrase))]
            (str "SECURITY.md is missing invariant: " phrase))
          (when-not (str/includes? agents "ADR-2609070400")
            ["AGENTS.md does not declare ADR-2609070400 mandatory"])
          (when-not (= "human-authentication-web3-first/v2" (:policy/id policy))
            ["unexpected policy id"])
          (when-not (= "2609070400" (:policy/adr policy))
            ["machine policy does not point at ADR-2609070400"])
          (when-not (= "2609070400" (:adr/id (first adr)))
            ["ADR file does not contain ADR-2609070400"])
          (when-not (= #{:webauthn-passkey :siwe-erc191 :siwe-erc1271} approved)
            [(str "approved active methods must be exactly #{:webauthn-passkey :siwe-erc191 :siwe-erc1271}, got " (pr-str approved))])
          (when-not (= :web3-wallet (:human-auth/preferred-family policy))
            ["Web3 wallet must be the preferred authentication family"])
          (for [k [:wallet/server-issued-single-use-nonce?
                   :wallet/exact-domain-origin-uri-binding?
                   :wallet/admitted-chain-binding? :wallet/expiry-required?
                   :wallet/server-side-signature-verification?
                   :wallet/atomic-nonce-consumption?
                   :wallet/contract-current-authorization-required?
                   :wallet/verification-fails-closed?]
                :when (not (true? (get policy k)))]
            (str "wallet safeguard must be true: " k))
          (for [k [:wallet/connection-is-authentication?
                   :wallet/login-authorizes-transactions?
                   :identity/implicit-principal-linking?]
                :when (not (false? (get policy k)))]
            (str "wallet authority boundary must be false: " k))
          (when-not (boolean? claim?)
            ["workspace conformance claim must be explicitly boolean"])
          (when-not (set/subset? required-prohibited prohibited)
            [(str "prohibited authority set is missing "
                  (pr-str (set/difference required-prohibited prohibited)))])
          (when (seq (set/intersection approved prohibited))
            ["approved and prohibited authority sets overlap"])
          (when-not (= #{:conformant :migration-gap :unverified} statuses)
            ["allowed inventory statuses changed without a policy version change"])
          (when (and claim? (not all-conformant?))
            [(str "workspace conformance claim is inconsistent: claim=" claim?
                  " all-conformant=" all-conformant?)])
          (when-not (:recovery/replaces-credential-not-session? policy)
            ["recovery must replace a credential, not create a session"])
          (when-not (:recovery/offline-one-time-secret? policy)
            ["recovery must require a one-time offline secret"])
          (when-not (:recovery/store-verifier-only? policy)
            ["recovery must store only a verifier"])
          (when (< (or (:recovery/server-enforced-delay-hours-min policy) 0) 48)
            ["recovery delay is shorter than 48 hours"])
          (when-not (false? (:recovery/operator-bypass? policy))
            ["operator recovery bypass must be explicitly false"])
          (when-not (:legacy/runtime-reactivation-prohibited? policy)
            ["legacy runtime reactivation must be prohibited"])
          (when-not (:legacy/source-built-live-negative-tests-required? policy)
            ["source, built, and live negative tests must be required"])
          (when-not (:legacy/plausible-secret-fixture-required? policy)
            ["negative tests must require plausible legacy secrets"])
          (when-not (= (count projects) (count (distinct projects)))
            ["inventory has duplicate project names"])
          (mapcat #(surface-errors % statuses approved prohibited west) surfaces)))]
    (if (seq errors)
      (fail! errors)
      (do
        (println (str "human-auth-no-downgrade: PASS — " (count surfaces)
                      " declared surfaces; workspace claim=" claim?))
        (println "Scope: declared first-party human-authentication authorities only; protocol mirrors and libraries excluded.")
        (js/process.exit 0)))))

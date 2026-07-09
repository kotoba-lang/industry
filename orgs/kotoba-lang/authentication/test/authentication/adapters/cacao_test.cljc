(ns authentication.adapters.cacao-test
  (:require [clojure.test :refer [deftest is testing]]
            [authentication.adapters.cacao :as cacao]
            [authentication.core :as c]
            [authentication.model :as m]
            [cacao.core :as real-cacao]))

(deftest verifies-cacao-as-authentication-factor
  (let [verifier (cacao/cacao-factor-verifier
                  (cacao/static-cacao-verifier
                   {:ok? true :subject "did:web:example.com:alice"
                    :evidence-ref "cacao:1" :assurance :phishing-resistant}))
        fr (m/factor-request "fr-1" :cacao {:subject "did:web:example.com:alice"})]
    (is (= {:authn.factor/type :cacao
            :authn.factor/ok? true
            :authn.factor/evidence-ref "cacao:1"}
           (select-keys (c/verify-factor {:cacao verifier} fr {:signature "sig"})
                        [:authn.factor/type :authn.factor/ok? :authn.factor/evidence-ref])))))

;; ── production: real cacao.core verification (ADR-2607050400) ──────────────

(def ^:private seed (byte-array (range 32)))

(defn- mint-test-cacao []
  (real-cacao/mint {:seed seed :aud "did:web:kotobase.net" :nonce "n1"
                    :iat "2026-07-04T00:00:00Z" :exp "2026-08-04T00:00:00Z"
                    :resources ["kotoba://can/kotobase:pin"]}))

(deftest production-verifier-accepts-a-real-cacao
  (let [{:keys [cacao-b64 iss]} (mint-test-cacao)
        verifier (cacao/cacao-factor-verifier (cacao/production-cacao-verifier))
        fr (m/factor-request "fr-1" :cacao {:subject iss})
        result (c/verify-factor {:cacao verifier} fr {:cacao/cacao-b64 cacao-b64})]
    (testing "the real Ed25519 signature verifies and the subject is the signer's did:key"
      (is (:authn.factor/ok? result))
      (is (= iss (:authn.factor/subject result)))
      (is (= iss (:authn.factor/evidence-ref result))))
    (testing "a CACAO factor alone never reaches :phishing-resistant (ADR-2607050400)"
      (is (= :single-factor (:authn.factor/assurance result))))))

(deftest production-verifier-rejects-a-tampered-cacao
  (let [{:keys [cacao-b64]} (mint-test-cacao)
        tampered (str (subs cacao-b64 0 (dec (count cacao-b64))) "x")
        verifier (cacao/cacao-factor-verifier (cacao/production-cacao-verifier))
        fr (m/factor-request "fr-1" :cacao {})]
    (is (not (:authn.factor/ok? (c/verify-factor {:cacao verifier} fr {:cacao/cacao-b64 tampered}))))))

(deftest production-verifier-checks-a-delegation-chain
  (let [root (mint-test-cacao)
        verifier (cacao/cacao-factor-verifier (cacao/production-cacao-verifier))
        fr (m/factor-request "fr-1" :cacao {})
        result (c/verify-factor {:cacao verifier} fr {:cacao/chain [(:cacao-b64 root)]})]
    (testing "a single-link chain is just that root CACAO, holder == its own aud"
      (is (:authn.factor/ok? result))
      (is (= "did:web:kotobase.net" (:authn.factor/subject result)))
      (is (= (:iss root) (:authn.factor/evidence-ref result))))))

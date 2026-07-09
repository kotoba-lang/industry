(ns webauthn.adapters.hmac-ceremony
  (:require [webauthn.adapters.ceremony :as ceremony])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(defn- b64url [bytes]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))

(defn signature [secret challenge credential-id]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. (.getBytes secret StandardCharsets/UTF_8) "HmacSHA256")))
        data (str challenge ":" credential-id)]
    (b64url (.doFinal mac (.getBytes data StandardCharsets/UTF_8)))))

(defn verifier [secret opts]
  (reify ceremony/ICeremonyVerifier
    (verify-registration! [_ payload call-opts]
      (let [data (:data payload)]
        {:credential-id (:credential-id data)
         :public-key-ref (or (:public-key-ref data)
                             (str "hmac://webauthn/" (:credential-id data)))
         :aaguid (:aaguid data)
         :transports (:transports data)
         :discoverable? (:discoverable? data)
         :verified-at (:verified-at call-opts)}))
    (verify-authentication! [_ payload call-opts]
      (let [data (:data payload)
            credential-id (:credential-id data)
            expected (signature secret (:challenge payload) credential-id)]
        (if (= expected (:signature data))
          {:credential-id credential-id
           :sign-count (:sign-count data)
           :user-present? true
           :user-verified? true
           :evidence-ref (or (:evidence-ref data)
                             (str "hmac://webauthn/assertion/" (:challenge-id payload)))
           :verified-at (:verified-at call-opts)}
          {:error :invalid-signature
           :credential-id credential-id
           :user-present? false
           :user-verified? false})))
    (derive-prf-envelope! [_ payload _]
      (assoc payload :derived-by :hmac-ceremony))))

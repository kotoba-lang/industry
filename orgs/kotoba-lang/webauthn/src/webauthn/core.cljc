(ns webauthn.core
  (:require [webauthn.model :as m]
            [webauthn.ports :as p]))

(defn problems [record]
  (cond-> []
    (and (:webauthn.challenge/ceremony record)
         (not (contains? m/ceremonies (:webauthn.challenge/ceremony record))))
    (conj {:webauthn.problem/code :unknown-ceremony})
    (seq (remove m/transports (:webauthn.credential/transports record)))
    (conj {:webauthn.problem/code :unknown-transport})

    (and (contains? record :webauthn.assertion/ok?)
         (:webauthn.assertion/ok? record)
         (not (:webauthn.assertion/user-present? record)))
    (conj {:webauthn.problem/code :assertion/user-not-present})

    (and (contains? record :webauthn.assertion/ok?)
         (:webauthn.assertion/ok? record)
         (not (:webauthn.assertion/user-verified? record)))
    (conj {:webauthn.problem/code :assertion/user-not-verified})))

(defn valid! [record]
  (when-let [ps (seq (problems record))]
    (throw (ex-info "invalid WebAuthn record" {:webauthn/problems ps})))
  record)

(defn register [port challenge attestation]
  (valid! challenge)
  (p/register! port challenge attestation))

(defn authenticate [port challenge assertion]
  (valid! challenge)
  (valid! (p/authenticate! port challenge assertion)))

(defn authenticate-once [port challenge-store challenge assertion]
  (when-not (p/consume-challenge! challenge-store (:webauthn.challenge/id challenge))
    (throw (ex-info "WebAuthn challenge replay" {:webauthn.challenge/id (:webauthn.challenge/id challenge)})))
  (authenticate port challenge assertion))

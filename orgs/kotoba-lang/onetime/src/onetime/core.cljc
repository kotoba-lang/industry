(ns onetime.core
  (:require [onetime.model :as m]
            [onetime.ports :as p]))

(defn problems [record]
  (cond-> []
    (not (contains? m/kinds (:onetime/kind record)))
    (conj {:onetime.problem/code :unknown-kind})))

(defn verify [port challenge response]
  (when-let [ps (seq (problems challenge))]
    (throw (ex-info "invalid one-time challenge" {:onetime/problems ps})))
  (p/verify! port challenge response))

(defn verify-once [port attempt-store challenge response]
  (let [out (verify port challenge response)
        n (p/record-attempt! attempt-store (:onetime/id challenge) (:onetime/ok? out))]
    (when (:onetime/ok? out)
      (when-not (p/consume-challenge! attempt-store (:onetime/id challenge))
        (throw (ex-info "one-time challenge replay" {:onetime/id (:onetime/id challenge)}))))
    (assoc out :onetime/attempts n)))

(defn rate-limit-problems [attempt-store challenge opts]
  (when-not (satisfies? p/IAttemptLookup attempt-store)
    (throw (ex-info "attempt lookup not available" {:onetime/id (:onetime/id challenge)})))
  (let [max-attempts (:max-attempts opts)
        attempts (p/attempt-count attempt-store (:onetime/id challenge))]
    (cond-> []
      (and max-attempts (>= attempts max-attempts))
      (conj {:onetime.problem/code :rate-limit/exceeded
             :onetime.attempts/count attempts
             :onetime.attempts/max max-attempts}))))

(defn verify-once-limited [port attempt-store challenge response opts]
  (when-let [ps (seq (rate-limit-problems attempt-store challenge opts))]
    (throw (ex-info "one-time challenge rate limit exceeded"
                    {:onetime/problems ps
                     :onetime/id (:onetime/id challenge)})))
  (verify-once port attempt-store challenge response))

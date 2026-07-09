(ns authentication.datom)

(defn factor-datoms [factor]
  [{:db/id (:authn.factor/id factor)
    :authn.factor/type (:authn.factor/type factor)
    :authn.factor/ok? (:authn.factor/ok? factor)
    :authn.factor/subject (:authn.factor/subject factor)
    :authn.factor/evidence-ref (:authn.factor/evidence-ref factor)
    :authn.factor/assurance (:authn.factor/assurance factor)
    :authn.factor/at (:authn.factor/at factor)}])

(defn factor-request-datoms [factor-request]
  [{:db/id (:authn.factor-request/id factor-request)
    :authn.factor-request/type (:authn.factor-request/type factor-request)
    :authn.factor-request/subject (:authn.factor-request/subject factor-request)
    :authn.factor-request/challenge-ref (:authn.factor-request/challenge-ref factor-request)
    :authn.factor-request/purpose (:authn.factor-request/purpose factor-request)
    :authn.factor-request/created-at (:authn.factor-request/created-at factor-request)}])

(defn decision-datoms [decision]
  [{:db/id (:authn.decision/request-id decision)
    :authn.decision/subject (:authn.decision/subject decision)
    :authn.decision/decision (:authn.decision/decision decision)
    :authn.decision/level (:authn.decision/level decision)
    :authn.decision/factor-ids (mapv :authn.factor/id (:authn.decision/factors decision))
    :authn.decision/issued-at (:authn.decision/issued-at decision)}])

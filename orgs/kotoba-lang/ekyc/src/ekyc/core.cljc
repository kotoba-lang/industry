(ns ekyc.core
  (:require [ekyc.model :as m]
            [ekyc.ports :as p]))

(defn problems [record]
  (let [required (:ekyc/required-checks record)
        one (:ekyc/check record)]
    (cond-> []
      (and (:ekyc/status record) (not (contains? m/statuses (:ekyc/status record))))
      (conj {:ekyc.problem/code :unknown-status})
      (seq (remove m/checks (if one #{one} required)))
      (conj {:ekyc.problem/code :unknown-check}))))

(defn completion [session evidence]
  (let [required (set (:ekyc/required-checks session))
        verified (set (keep #(when (= :verified (:ekyc/status %)) (:ekyc/check %)) evidence))
        missing (set (remove verified required))]
    {:ekyc/id (:ekyc/id session)
     :ekyc/complete? (empty? missing)
     :ekyc/status (if (empty? missing) :verified :review)
     :ekyc/required-checks required
     :ekyc/verified-checks verified
     :ekyc/missing-checks missing
     :ekyc/non-adjudicating true}))

(defn- valid! [x]
  (when-let [ps (seq (problems x))]
    (throw (ex-info "invalid eKYC record" {:ekyc/problems ps})))
  x)

(defn start [port session]
  (valid! (merge session (p/start-session! port session))))

(defn submit [port session evidence]
  (valid! (merge evidence (p/submit-evidence! port session evidence))))

(defn result [port session]
  (valid! (merge session (p/fetch-result port session))))

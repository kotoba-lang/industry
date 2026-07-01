(ns ekyc.core-test
  (:require [clojure.test :refer [deftest is]]
            [ekyc.core :as c]
            [ekyc.datom :as d]
            [ekyc.model :as m]
            [ekyc.ports :as p]))

(deftest session-flow
  (let [s (m/session "e1" "did:web:example.com:alice" {:required-checks #{:liveness}})
        port (reify p/IEkyc
               (start-session! [_ session] (assoc session :ekyc/status :submitted))
               (submit-evidence! [_ _ evidence] (assoc evidence :ekyc/status :verified))
               (fetch-result [_ session] (assoc session :ekyc/status :verified)))]
    (is (= :submitted (:ekyc/status (c/start port s))))
    (is (= :verified (:ekyc/status (c/result port s))))))

(deftest completion-requires-all-required-checks
  (let [s (m/session "e1" "did:web:example.com:alice"
                     {:required-checks #{:liveness :document-authenticity}})
        live (m/evidence s :liveness :verified {})
        doc (m/evidence s :document-authenticity :review {})]
    (is (= {:ekyc/id "e1"
            :ekyc/complete? false
            :ekyc/status :review
            :ekyc/required-checks #{:liveness :document-authenticity}
            :ekyc/verified-checks #{:liveness}
            :ekyc/missing-checks #{:document-authenticity}
            :ekyc/non-adjudicating true}
           (c/completion s [live doc])))))

(deftest emits-ekyc-datoms
  (let [s (m/session "e1" "did:web:example.com:alice" {:required-checks #{:liveness}})
        live (m/evidence s :liveness :verified {:evidence-ref "blob://live"})
        done (c/completion s [live])]
    (is (= "e1" (:db/id (first (d/session-datoms s)))))
    (is (= "e1:liveness" (:db/id (first (d/evidence-datoms live)))))
    (is (true? (:ekyc/complete? (first (d/decision-datoms done)))))))

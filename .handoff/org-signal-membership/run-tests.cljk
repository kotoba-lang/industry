(require '[clojure.test :as t] '[kotoba.signal.membership-test])
(let [r (t/run-tests 'kotoba.signal.membership-test)]
  (when (pos? (+ (:fail r) (:error r))) (js/process.exit 1)))

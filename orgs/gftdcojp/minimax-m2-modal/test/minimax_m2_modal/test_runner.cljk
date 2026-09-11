(ns minimax-m2-modal.test-runner
  (:require [clojure.test :as test]
            [minimax-m2-modal.openai-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'minimax-m2-modal.openai-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))

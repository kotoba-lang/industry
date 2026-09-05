(ns hikobae.test-runner
  "テストランナー。**実行本数の床**を持つ。

   0 本走って 0 失敗を『合格』として通さない（root CLAUDE.md の evidence floor）。
   名前空間の require が黙って失敗しても、それは緑にならない。"
  (:require [clojure.test :as t]
            [hikobae.hikobae-test]))

(def ^:private minimum-tests 22)

#?(:clj
   (defn -main [& _]
     (let [r (t/run-tests 'hikobae.hikobae-test)]
       (println)
       (println (format "Ran %d tests, %d assertions, %d failures, %d errors"
                        (:test r) (:pass r) (:fail r) (:error r)))
       (when (< (:test r) minimum-tests)
         (println (format "REFUSING TO REPORT A PASS: ran %d tests, floor is %d"
                          (:test r) minimum-tests))
         (System/exit 3))
       (System/exit (if (and (zero? (:fail r)) (zero? (:error r))) 0 1)))))

(ns run-tests
  "nbb でテストを回す。`npx --yes nbb run-tests.cljs`

  実行本数の床を持つ: 1 本も走らなかった実行を成功として終わらせない
  （ADR-2608136000「測れなかった検査が、測って問題が無かった検査と同じ値を返す」）。"
  (:require [clojure.test :as test]
            [gpr.detect-test]
            [gpr.dsp-test]
            [gpr.fft-test]
            [gpr.trace-test]
            [gpr.velocity-test]))

(def ^:private minimum-assertions 25)

(defmethod test/report [:cljs.test/default :end-run-tests] [m]
  (let [ran (+ (:pass m) (:fail m) (:error m))]
    (println (str "ASSERTIONS\t" ran))
    (cond
      (< ran minimum-assertions)
      (do (println (str "REFUSING to report a pass: only " ran
                        " assertions ran, floor is " minimum-assertions))
          (js/process.exit 2))

      (or (pos? (:fail m)) (pos? (:error m)))
      (js/process.exit 1)

      :else (println "OK"))))

(test/run-tests 'gpr.fft-test 'gpr.velocity-test 'gpr.trace-test
                'gpr.dsp-test 'gpr.detect-test)

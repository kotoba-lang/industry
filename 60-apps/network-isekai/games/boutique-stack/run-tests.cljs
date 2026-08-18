#!/usr/bin/env nbb
;; Test entry point for the murakumo fleet `:nbb-test` gate.
;;
;;     npx nbb --classpath src:test:../common/src run-tests.cljs
(ns run-tests
  (:require [clojure.test :as t]
            [isekai.games.boutique-stack.economy-test]
            [isekai.games.boutique-stack.manager-test]
            [isekai.games.boutique-stack.render-ir-test]
            [isekai.games.boutique-stack.sim-test]
            [isekai.games.boutique-stack.world-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (throw (ex-info "test failures" {:fail (:fail m) :error (:error m)}))))

(t/run-tests 'isekai.games.boutique-stack.economy-test
             'isekai.games.boutique-stack.manager-test
             'isekai.games.boutique-stack.render-ir-test
             'isekai.games.boutique-stack.sim-test
             'isekai.games.boutique-stack.world-test)

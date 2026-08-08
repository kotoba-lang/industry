#!/usr/bin/env nbb
;; Test entry point for the murakumo fleet `:nbb-test` gate.
;;
;;     npx nbb --classpath src:test run-tests.cljs
;;
;; nbb only, per the workspace script-host rule — no bb, no bare .mjs, no .sh.
(ns run-tests
  (:require [clojure.test :as t]
            [isekai.games.kingdom-cascade.blast-test]
            [isekai.games.kingdom-cascade.clear-test]
            [isekai.games.kingdom-cascade.core-test]
            [isekai.games.kingdom-cascade.gravity-test]
            [isekai.games.kingdom-cascade.input-test]
            [isekai.games.kingdom-cascade.level-test]
            [isekai.games.kingdom-cascade.matcher-test]
            [isekai.games.kingdom-cascade.render-ir-test]
            [isekai.games.kingdom-cascade.solver-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (throw (ex-info "test failures" {:fail (:fail m) :error (:error m)}))))

(t/run-tests 'isekai.games.kingdom-cascade.blast-test
             'isekai.games.kingdom-cascade.clear-test
             'isekai.games.kingdom-cascade.core-test
             'isekai.games.kingdom-cascade.gravity-test
             'isekai.games.kingdom-cascade.input-test
             'isekai.games.kingdom-cascade.level-test
             'isekai.games.kingdom-cascade.matcher-test
             'isekai.games.kingdom-cascade.render-ir-test
             'isekai.games.kingdom-cascade.solver-test)

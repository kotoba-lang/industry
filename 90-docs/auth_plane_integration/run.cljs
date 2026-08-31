#!/usr/bin/env nbb
;; Entry point for the cross-apex auth integration measurement.
;;
;;   nbb --classpath 90-docs 90-docs/auth_plane_integration/run.cljs [--out FILE]
;;   nbb --classpath 90-docs 90-docs/auth_plane_integration/run.cljs --probe stored.edn
;;
;; --probe scores a stored measurement without touching the network. That is
;; what makes the judge testable: the same axes can be shown to go red and
;; green against two hand-built probe maps, which is the only evidence that
;; the scoring discriminates at all.
;;
;; Exit codes are three-valued on purpose:
;;   0  every axis measured, no findings
;;   1  every axis measured, findings present
;;   2  at least one axis could not be measured -- REFUSING to report a score
;;      as if it were complete.
(ns auth-plane-integration.run
  (:require [auth-plane-integration.probe :as probe]
            [auth-plane-integration.audit :as audit]
            [clojure.edn :as edn]
            [cljs.pprint :as pp]
            ["fs" :as fs]))

(defn- arg [args flag]
  (second (drop-while #(not= flag %) args)))

(defn -main [& args]
  (let [stored (arg args "--probe")
        p (if stored
            (edn/read-string (str (fs/readFileSync stored "utf8")))
            (probe/probe))
        p (or (:probe p) p)
        a (audit/audit p)
        out (or (arg args "--out")
                (when-not stored
                  (str "90-docs/auth_plane_integration/probe-"
                       (subs (:probe/at p) 0 10) ".edn")))]
    ;; The coverage block prints on EVERY run, before the scores. A number
    ;; assembled from an incomplete set of axes is worth less than the list of
    ;; what the axes do and do not reach, and putting it after the scores would
    ;; make it the thing nobody reads.
    (println (audit/format-coverage))
    (println)
    (println (audit/format-report p a))
    (when out
      (fs/writeFileSync out (with-out-str (pp/pprint {:probe p :audit a})))
      (println (str "\n  wrote " out)))
    (cond
      ;; An invariant the design asserts and nothing measures is not a low
      ;; score, it is an unmeasured claim, and it refuses the same way an
      ;; unmeasured axis does.
      (seq (:silent (audit/coverage)))
      (do (println "\n  REFUSING: an invariant has no axis and no declared reason.") 2)

      (seq (audit/orphan-axes))
      (do (println "\n  REFUSING: an axis serves no declared invariant.") 2)

      (seq (:incomplete a)) 2
      (seq (:findings a)) 1
      :else 0)))

(let [code (apply -main *command-line-args*)]
  (set! (.-exitCode js/process) code))

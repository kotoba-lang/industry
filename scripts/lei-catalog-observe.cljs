#!/usr/bin/env nbb
;; lei-catalog-observe.cljs — print the cloud-itonami-lei catalog's live metrics
;; as EDN, read from git (the blueprints), not from any SQL projection.
;;
;; This exists so `kotoba-lang/loop-lei-catalog` can observe without embedding a
;; reader of its own. The loop's stated design is that every action shells out
;; to the tested superproject scripts rather than reimplementing them —
;; observation is held to the same rule, for the same reason: two readers of the
;; same catalog drift, and the drift shows up as a maturity score that moves
;; without the catalog changing.
;;
;; Run (from the superproject root):
;;   nbb --classpath scripts scripts/lei-catalog-observe.cljs

(ns lei-catalog-observe
  (:require [lei-catalog :as cat]))

;; `:fresh? true` -- the Contents API, not the raw CDN. This is the loop's
;; after-measurement: it exists to see the write the loop's own action just
;; made, and the CDN serves a stale copy for minutes afterwards. Measured
;; 2026-08-04: a cycle reported `found: 1` and recorded an identical
;; maturity before and after. Costs one authenticated request per repo.
(-> (cat/fetch-catalog {:docs? true :concurrency 12 :fresh? true})
    (.then (fn [c]
             (let [obs (cat/observe c)]
               ;; The unreadable repos go to stderr as well as into the map:
               ;; a caller that only prints the score should still see that the
               ;; score was computed over an incomplete read.
               (when (pos? (:unreadable obs))
                 (binding [*print-fn* *print-err-fn*]
                   (println "lei-catalog-observe: WARNING" (:unreadable obs)
                            "repo(s) unreadable —— この観測は不完全です:"
                            (pr-str (mapv :repo (:unreadable c))))))
               (println (pr-str obs)))))
    (.catch (fn [e]
              (binding [*print-fn* *print-err-fn*] (println "FATAL:" (.-message e)))
              (set! (.-exitCode js/process) 1))))

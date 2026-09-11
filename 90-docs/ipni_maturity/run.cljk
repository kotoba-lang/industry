#!/usr/bin/env nbb
;; Entry point for the IPNI maturity Co-Scientist loop. Kept separate from
;; `coscientist.cljc` so that requiring the loop does not run it.
;;
;;   nbb --classpath 90-docs 90-docs/ipni_maturity/run.cljs <probe.edn> [--md OUT.edn]
(ns ipni-maturity.run
  (:require [ipni-maturity.coscientist :as cs]))

(apply cs/-main *command-line-args*)

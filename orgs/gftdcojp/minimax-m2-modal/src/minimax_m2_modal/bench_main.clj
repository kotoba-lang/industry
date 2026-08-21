(ns minimax-m2-modal.bench-main
  (:require [minimax-m2-modal.bench :as bench]
            [minimax-m2-modal.host-config :as host-config]))

(defn -main [& _]
  (bench/run (host-config/from-environment)))

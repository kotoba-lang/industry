(ns minimax-m2-modal.client-main
  (:require [minimax-m2-modal.client :as client]
            [minimax-m2-modal.host-config :as host-config]))

(defn -main [& [mode]]
  (client/run (host-config/from-environment) mode))

(ns babashka.process (:require [scripts.nbb-compat :as compat]))

(defn- process-options [opts]
  (cond-> {}
    (:dir opts) (assoc :cwd (:dir opts))
    (:env opts) (assoc :env (:env opts))))

(defn process [command opts]
  (delay (apply compat/sh (into (vec command) [(process-options opts)]))))

(defn shell [opts & command]
  (let [opts (if (map? opts) opts {})
        command (if (map? opts) command (cons opts command))]
    (apply compat/sh (concat command [(process-options opts)]))))

(defn sh [command]
  (apply compat/sh command))

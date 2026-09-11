;; verify the ADR parses as valid EDN tx-data
(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def text (str (.readFileSync fs "90-docs/adr/2609022000-yui-five-domain-participation-empowerment-bot.edn")))
(def tx (edn/read-string text))
(assert (vector? tx) "tx-data must be a vector")
(assert (= 1 (count tx)) "one entity")
(def e (first tx))
(println :adr/id (:adr/id e))
(println :adr/status (:adr/status e))
(println :title-len (count (:adr/title e)))
(println :body-len (count (:adr/body e)))
(println :has-measured (boolean (:adr/measured e)))
(println "ADR-EDN-OK")

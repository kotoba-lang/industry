(ns kyber-plm.export
  "Print the released PLM graph as a kotobase `kg.ingest_batch` JSON payload.

     clojure -M:export > live/plm-batch.json
     KOTOBA_INTERNAL_TRUST=… node live/kg_batch_load.mjs   # ingest → commit → read-back

   Builds the same small product as the demo, releases it (so only :released
   items project), and emits {\"entities\": [ <kg.ingest entity> … ]}."
  (:require [kyber-plm.db :as db]
            [kyber-plm.plm :as plm]
            [kyber-plm.erp :as erp]
            [kyber-plm.thread :as thread]
            [kyber-plm.kotobase :as kb]
            [cheshire.core :as json]))

(defn sample-graph! [conn]
  (db/tx! conn erp/chart)
  (db/tx! conn
    [(plm/item {:part-no "PN-2000" :name "Resistor 10k"   :make-buy :buy :std-unit-cost 100 :category :electronic})
     (plm/item {:part-no "PN-2001" :name "Capacitor 1uF"  :make-buy :buy :std-unit-cost 50  :category :electronic})
     (plm/item {:part-no "PN-1000" :name "Controller PCBA" :make-buy :make :category :assembly})])
  (db/tx! conn
    [(plm/bom-edge {:parent "PN-1000@A" :child "PN-2000@A" :qty 4 :find-no 1})
     (plm/bom-edge {:parent "PN-1000@A" :child "PN-2001@A" :qty 2 :find-no 2})])
  (doseq [iid ["PN-2000@A" "PN-2001@A" "PN-1000@A"]] (thread/release-item! conn iid)))

(defn -main [& _]
  (let [conn (db/fresh-conn)]
    (sample-graph! conn)
    (println (json/generate-string {:entities (kb/graph->kg-entities (db/db conn))}))
    (shutdown-agents)))

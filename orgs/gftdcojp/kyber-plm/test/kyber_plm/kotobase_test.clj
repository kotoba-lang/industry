(ns kyber-plm.kotobase-test
  (:require [clojure.test :refer [deftest testing is]]
            [kyber-plm.db :as db]
            [kyber-plm.plm :as plm]
            [kyber-plm.erp :as erp]
            [kyber-plm.thread :as thread]
            [kyber-plm.kotobase :as kb]))

(defn- world []
  (let [conn (db/fresh-conn (str "kb-" (System/nanoTime)))]
    (db/tx! conn erp/chart)
    (db/tx! conn
      [(plm/item {:part-no "PN-2000" :name "Resistor" :make-buy :buy :std-unit-cost 100 :category :electronic})
       (plm/item {:part-no "PN-1000" :name "Controller PCBA" :make-buy :make})])
    (db/tx! conn [(plm/bom-edge {:parent "PN-1000@A" :child "PN-2000@A" :qty 4 :find-no 1})])
    (doseq [iid ["PN-2000@A" "PN-1000@A"]] (thread/release-item! conn iid))
    conn))

(deftest item-projects-to-kg-entity
  (let [conn (world)
        e (kb/item->kg-entity (db/db conn) "PN-1000@A")
        claims (into {} (map (juxt :pred :value)) (:claims e))]
    (testing "entity shell (camelCase labelEn per pod struct)"
      (is (= "PN-1000@A" (:id e)))
      (is (= "plm.item" (:type e)))
      (is (= "Controller PCBA" (:labelEn e))))
    (testing "claims use {:pred :value} with stringified keyword/decimal values"
      (is (= "PN-1000" (claims "plm.item/part-no")))
      (is (= "make"    (claims "plm.item/make-buy")))   ; keyword → name
      (is (= "released" (claims "plm.item/lifecycle")))
      (is (= "4" (claims "plm.bom/qty/PN-2000@A"))))     ; BOM qty as companion claim
    (testing "MBOM child becomes a relation {:pred :dstId} (no qty slot)"
      (is (= [{:pred "plm.bom/child" :dstId "PN-2000@A"}] (:relations e))))
    (testing "buy item carries its std-unit-cost claim"
      (let [b (into {} (map (juxt :pred :value)) (:claims (kb/item->kg-entity (db/db conn) "PN-2000@A")))]
        (is (= "100" (b "plm.item/std-unit-cost")))
        (is (= "buy"  (b "plm.item/make-buy")))))))

(deftest batch-projects-only-released
  (let [conn (world)]
    (db/tx! conn [(plm/item {:part-no "PN-DRAFT" :make-buy :buy :std-unit-cost 9})]) ; stays :draft
    (let [ids (set (map :id (kb/graph->kg-entities (db/db conn))))]
      (is (contains? ids "PN-1000@A"))
      (is (contains? ids "PN-2000@A"))
      (is (not (contains? ids "PN-DRAFT@A")) "draft items are not projected"))))

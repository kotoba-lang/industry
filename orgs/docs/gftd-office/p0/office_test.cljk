(ns office-test
  "P0 round-trip tests: document / org(+DID) / grant <-> datoms, plus an access-DAG check.
   Run:  nbb scripts/run-task.cljs test   (see scripts/tasks.edn; bb was retired by
         ADR-2607173000 and this file's bb.edn went with it in ADR-2608135000)"
  (:require [office :as o]
            [clojure.test :refer [deftest is run-tests]]))

;; --- sample org tree: gftd.ai(root) -> Acme(team) -> Alice(account = org of one) ---
(def sample-orgs
  [{:id "org-root" :did "did:key:zRoot" :kind :org.kind/root
    :display-name "gftd.ai" :data-graph "g-root" :created-at 1719000000000
    :vms [{:vid "did:key:zRoot#k1" :type :Ed25519 :pk "zRoot"
           :rels #{:authentication :capabilityDelegation}}]}
   {:id "org-acme" :did "did:key:zAcme" :kind :org.kind/team
    :display-name "Acme" :parent "org-root" :data-graph "g-acme" :created-at 1719000001000
    :vms [{:vid "did:key:zAcme#k1" :type :Ed25519 :pk "zAcme"
           :rels #{:authentication :capabilityDelegation :capabilityInvocation}}]}
   {:id "org-alice" :did "did:key:zAlice" :kind :org.kind/account
    :display-name "Alice" :parent "org-acme" :data-graph "g-alice" :created-at 1719000002000
    :vms [{:vid "did:key:zAlice#k1" :type :Ed25519 :pk "zAlice"
           :rels #{:authentication :capabilityInvocation :keyAgreement}}]}])

;; --- access DAG: Alice is a MEMBER of Acme AND a GUEST of Beta (two access parents) ---
(def sample-grants
  [{:id "grant-alice-acme" :org "org-acme" :subject "did:key:zAlice"
    :cap :cap/transact :scope "g-acme" :role :role/member :issued-at 1719000003000}
   {:id "grant-alice-beta" :org "org-beta" :subject "did:key:zAlice"
    :cap :cap/read :scope "g-beta" :role :role/guest
    :issued-at 1719000004000 :expires-at 1726000000000}])

;; --- nested block document owned by Alice ---
(def sample-doc
  {:id "doc1" :kind :doc/document :title "Q3 戦略メモ"
   :owner-org "org-alice" :created-at 1719000005000
   :blocks [{:id "b0" :kind :block/heading :text "概要" :order "a0"}
            {:id "b1" :kind :block/paragraph :text "原材料費が上昇し…" :order "a1"
             :children [{:id "b1a" :kind :block/paragraph :text "詳細" :order "a0"}]}]})

(deftest doc-round-trip
  (is (= sample-doc (o/datoms->doc (o/doc->datoms sample-doc)))))

(deftest org-round-trip
  (doseq [org sample-orgs]
    (is (= org (o/datoms->org (o/org->datoms org) (:id org)))
        (str "org round-trip: " (:id org)))))

(deftest grant-round-trip
  (doseq [g sample-grants]
    (is (= g (o/datoms->grant (o/grant->datoms g) (:id g)))
        (str "grant round-trip: " (:id g)))))

(deftest access-is-a-dag
  ;; same subject DID reachable via two distinct grants/orgs => DAG, not a tree
  (let [datoms (mapcat o/grant->datoms sample-grants)
        alice-grants (->> datoms
                          (filter (fn [[_ a v]] (and (= a :grant/subject) (= v "did:key:zAlice"))))
                          (map first) set)]
    (is (= 2 (count alice-grants)) "Alice holds grants from 2 orgs (multi-membership)")))

(deftest ownership-is-a-tree
  ;; every non-root org has exactly one :org/parent (single ownership spine)
  (let [datoms (mapcat o/org->datoms sample-orgs)
        parents-of (fn [oid] (o/v-many datoms oid :org/parent))]
    (is (empty? (parents-of "org-root")))
    (is (= 1 (count (parents-of "org-acme"))))
    (is (= 1 (count (parents-of "org-alice"))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'office-test)]
    (if (zero? (+ fail error)) 0 1)))

(ns gftd.bmc-test
  (:require [clojure.test :refer [deftest is testing]]
            [gftd.canvas :as canvas]
            [gftd.ledger :as ledger]
            [gftd.react :as react]
            [gftd.score :as score]))

(def base
  [{:canvas/kind :lean :canvas/product :cloud-itonami :canvas/layer :business-operator
    :canvas/id :cloud-itonami.problem :canvas/block :lean/problem :canvas/label "Problem"
    :canvas/items ["p1"]}
   {:canvas/kind :lean :canvas/product :cloud-itonami :canvas/layer :business-operator
    :canvas/id :cloud-itonami.uvp :canvas/block :lean/uvp :canvas/label "UVP"
    :canvas/items ["u1"]}
   {:canvas/kind :lean :canvas/product :cloud-itonami :canvas/layer :business-operator
    :canvas/id :cloud-itonami.metrics :canvas/block :lean/key-metrics :canvas/label "Key Metrics"
    :canvas/items ["m1"]}
   {:canvas/kind :lean :canvas/product :etzhayyim :canvas/layer :artificial-organism-platform
    :canvas/id :etzhayyim.revenue :canvas/block :lean/revenue-streams :canvas/label "Funding"
    :canvas/items ["寄付・助成"]}
   {:hyp/id :hyp/t1 :hyp/product :cloud-itonami :hyp/risk :riskiest :hyp/status :untested
    :hyp/claim "claim-1" :hyp/gate "gate-1"}])

(deftest fold-events
  (let [idx (canvas/index base)
        idx' (canvas/fold idx [{:event/type :canvas/add-item :canvas/id :cloud-itonami.problem :event/value "p2"}
                               {:event/type :canvas/retract-item :canvas/id :cloud-itonami.problem :event/value "p1"}
                               {:event/type :hyp/status :hyp/id :hyp/t1 :event/value :validated :event/evidence "e"}])]
    (is (= ["p2"] (get-in idx' [:blocks :cloud-itonami.problem :canvas/items])))
    (is (= :validated (get-in idx' [:hyps :hyp/t1 :hyp/status])))
    (is (= "e" (get-in idx' [:hyps :hyp/t1 :hyp/evidence])))))

(deftest ledger-roundtrip
  (let [evs (ledger/parse-events ";; comment\n{:event/seq 1 :event/type :canvas/note}\n\n{:event/seq 2 :event/type :canvas/note}\n")]
    (is (= 2 (count evs)))
    (is (= 3 (ledger/next-seq evs)))
    (is (= [5 6] (map :event/seq (ledger/stamp [{:event/seq 4}] "t" [{} {}]))))))

(deftest governor-invariants
  (let [idx (canvas/index base)
        {:keys [approved rejected]}
        (react/governor idx
                        [{:proposal/action :canvas/add-item :canvas/id :cloud-itonami.problem :event/value "new"}
                         {:proposal/action :canvas/add-item :canvas/id :cloud-itonami.problem :event/value "p1"} ; dup
                         {:proposal/action :canvas/retract-item :canvas/id :cloud-itonami.uvp :event/value "u1"} ; last item
                         {:proposal/action :hyp/status :hyp/id :hyp/t1 :event/value :validated} ; no evidence
                         {:proposal/action :canvas/add-item :canvas/id :etzhayyim.revenue :event/value "take rate 20%"} ; 営利
                         {:proposal/action :canvas/add-item :canvas/id :nope.problem :event/value "x"}])] ; unknown id
    (is (= 1 (count approved)))
    (is (= 5 (count rejected)))
    (is (= "non-profit invariant: etzhayyim funding must stay 非営利"
           (some #(when (= "take rate 20%" (get-in % [:proposal :event/value])) (:reason %)) rejected)))))

(deftest react-loop-converges
  (testing "untested riskiest hyp → gate item proposed once, then loop goes dry"
    (let [idx (canvas/index base)
          run (react/run-ticks {:idx idx :product :cloud-itonami :max-ticks 5})]
      (is (:dry? run))
      (is (= 2 (count (:ticks run))))  ; tick1 proposes, tick2 dry
      (let [items (get-in (:idx run) [:blocks :cloud-itonami.metrics :canvas/items])]
        (is (some #(re-find #"次の検証" %) items)))))
  (testing "refuted hyp → pivot note on uvp"
    (let [idx (-> (canvas/index base)
                  (canvas/fold [{:event/type :hyp/status :hyp/id :hyp/t1 :event/value :refuted :event/evidence "e"}]))
          {:keys [approved]} (react/tick {:idx idx :product :cloud-itonami})]
      (is (some #(= :cloud-itonami.uvp (:canvas/id %)) approved)))))

(deftest scoring
  (let [idx (canvas/index base)
        facts {:as-of "t" :products {:cloud-itonami {:pricing 2 :grounding 3
                                                     :acute-problem 4 :wedge 2 :tenx 4 :founder-fit 5
                                                     :distribution 1 :defensibility 4
                                                     :launched 2 :users 1 :revenue 0}}}
        s (score/score-product idx :cloud-itonami facts)]
    (testing "auto dims"
      ;; base fixture has 3 of 9 blocks, avg 1 item → completeness (5*3/9)-1
      (is (< 0.6 (get-in s [:bmc :dims :completeness]) 0.7))
      (is (= 5.0 (get-in s [:bmc :dims :hypothesis])))
      (is (= 0.0 (get-in s [:bmc :dims :validation]))))
    (testing "validation moves when hyp is validated via ledger"
      (let [idx' (canvas/fold idx [{:event/type :hyp/status :hyp/id :hyp/t1
                                    :event/value :validated :event/evidence "e"}])
            s' (score/score-product idx' :cloud-itonami facts)]
        (is (= 5.0 (get-in s' [:bmc :dims :validation])))
        (is (> (get-in s' [:bmc :score]) (get-in s [:bmc :score])))))
    (testing "yc = design 50% + traction 50%"
      (is (= (+ (* 50.0 (/ 20.0 30.0)) (* 50.0 (/ 3.0 15.0)))
             (get-in s [:yc :score]))))
    (testing "render"
      (is (re-find #"cloud-itonami" (score/render-table {:cloud-itonami s}))))))

(deftest render-md-smoke
  (let [idx (canvas/index base)
        md (canvas/render-md idx :cloud-itonami {:as-of "2026-07-02"})]
    (is (re-find #"cloud-itonami — business model / lean canvas" md))
    (is (re-find #"\| `:hyp/t1` \| riskiest \| untested \|" md))))

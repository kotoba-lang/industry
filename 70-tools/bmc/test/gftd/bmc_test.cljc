(ns gftd.bmc-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [gftd.canvas :as canvas]
            [gftd.cli :as cli]
            [gftd.ledger :as ledger]
            [gftd.react :as react]
            [gftd.gate :as gate]
            [gftd.funnel :as funnel]
            [gftd.score :as score]
            [gftd.murakumo :as murakumo]
            [gftd.kotobase :as kbase]
            [gftd.traffic :as traffic]))

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

(deftest explicit-root-configuration
  (testing "portable CLI path resolution prefers the closed value supplied by its host"
    (is (= "/explicit/repository" (cli/find-root "/explicit/repository")))
    (is (= "/explicit/repository/90-docs/business/canvas-ledger.edn"
           (:ledger (cli/paths "/explicit/repository"))))))

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

(def base+channels
  (conj base
        {:canvas/kind :lean :canvas/product :cloud-itonami :canvas/layer :business-operator
         :canvas/id :cloud-itonami.channels :canvas/block :lean/channels :canvas/label "Channels"
         :canvas/items ["c1"]}))

(deftest top-paths-observation-lands-in-channels
  (testing ":top-paths metric (実 page アクセス内訳) → channels block へ観測、dedup で収束"
    (let [idx (canvas/index base+channels)
          metrics {:top-paths "上位 path (24h): / 1200 · /pricing 88 · /docs 41"}
          run (react/run-ticks {:idx idx :product :cloud-itonami :metrics metrics :max-ticks 5})]
      (is (:dry? run))
      (let [items (get-in (:idx run) [:blocks :cloud-itonami.channels :canvas/items])]
        (is (= 1 (count (filter #(re-find #"観測 \(paths\)" %) items))))
        (is (some #(re-find #"/pricing 88" %) items))))))

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
      ;; design 4+2+4+5+1+4 = 20 / traction 2+1+0 = 3。浮動小数版はここで
      ;; 43.33333333333333 を返していた。ADR-2608158000 で算術を整数 basis point
      ;; （kotoba/score_core.kotoba）へ移してからは (40000 + 2*6000)/12 = 4333 bp
      ;; = 43.33 で、**切り捨てのぶんだけ下に動く**。動いた値はこの 1 件だけ。
      (is (= 43.33 (get-in s [:yc :score])))
      (is (= 4333 (get-in s [:yc :score-bp]))))
    (testing "render"
      (is (re-find #"cloud-itonami" (score/render-table {:cloud-itonami s}))))))

(deftest gate-evaluation
  (testing "machine-measurable gate: met → validated"
    (is (= :validated (:status (gate/evaluate-hyp {:n 5} {:metric [:n] :op :>= :threshold 3})))))
  (testing "machine-measurable gate: not met → measuring"
    (is (= :measuring (:status (gate/evaluate-hyp {:n 1} {:metric [:n] :op :>= :threshold 3})))))
  (testing "unmeasurable metric → blocked with fallback needs"
    (is (= :blocked (:status (gate/evaluate-hyp {} {:metric [:n] :op :>= :threshold 3
                                                    :needs-when-unmeasurable ["x"]})))))
  (testing ":all conjunction"
    (is (= :validated (:status (gate/evaluate-hyp {:a 2000 :b 5000}
                                                  {:all [{:metric [:a] :op :>= :threshold 1000}
                                                         {:metric [:b] :op :>= :threshold 4000}]}))))
    (is (= :measuring (:status (gate/evaluate-hyp {:a 3 :b 10}
                                                  {:all [{:metric [:a] :op :>= :threshold 1000}
                                                         {:metric [:b] :op :>= :threshold 4000}]})))))
  (testing "instrument-blocked spec"
    (is (= :blocked (:status (gate/evaluate-hyp {} {:needs ["計器A"]})))))
  (testing ":compare form (lhs op rhs)"
    (let [spec {:compare {:lhs [:a] :op :> :rhs [:b]} :needs-when-unmeasurable ["x"]}]
      (is (= :validated (:status (gate/evaluate-hyp {:a 100 :b 10} spec))))
      (is (= :measuring (:status (gate/evaluate-hyp {:a 5 :b 10} spec))))
      (is (= :blocked (:status (gate/evaluate-hyp {:a 5} spec))))))
  (testing "real gate-specs: isekai viral-coefficient measurable"
    (is (= :validated (:status (gate/evaluate-hyp {:fork {:viral-coefficient 1.5}}
                                                  (get gate/gate-specs :hyp/isekai-fork-viral)))))
    (is (= :blocked (:status (gate/evaluate-hyp {} (get gate/gate-specs :hyp/isekai-fork-viral))))))
  (testing "real gate-specs: club-shinshi creator-gmv vs ad compare"
    (is (= :validated (:status (gate/evaluate-hyp {:revenue {:creator-gmv-jpy 500000 :ad-revenue-jpy 100000}}
                                                  (get gate/gate-specs :hyp/club-shinshi-creator-take)))))))

;; ADR-2608159300 — 判定を .kotoba の決定核へ移したときに変わった振る舞い。
;; 「挙動保存 + 名指しできる欠陥の修正だけ」という条件の、その名指しの部分。
(deftest gate-decision-core-migration
  (testing "欠測は 0 ではない —— `:> 0` の gate に欠測を渡しても validated にしない。
            測定値を basis point へスケールする経路で、数にできないものを 0 に
            落とすと『測って落第』でも『測って合格』でもある値になる（ADR-2608136000）"
    (let [spec (get gate/gate-specs :hyp/nexus-x402-agent-demand)]
      (is (= :blocked   (:status (gate/evaluate-hyp {} spec))))
      (is (= :measuring (:status (gate/evaluate-hyp {:catalog {:settlements {:agent-hint {:agent 0}}}} spec))))
      (is (= :validated (:status (gate/evaluate-hyp {:catalog {:settlements {:agent-hint {:agent 1}}}} spec))))
      (is (= gate/absent-bp (gate/->bp nil)))
      (is (= gate/absent-bp (gate/->bp "n/a")))
      (is (= 0 (gate/->bp 0)))))
  (testing "節が 0 本の `:all` は blocked。移行前は `(every? :met [])` が true で
            :validated を返しており、節を書き忘れた spec が『機械測定で gate 到達』
            として昇格 event を積む形だった（今日の gate-specs には無い）"
    (is (= :blocked (:status (gate/evaluate-hyp {:a 1} {:all [] :needs-when-unmeasurable ["x"]})))))
  (testing "閾値が数でない spec は落ちる。移行前の cljs は `(>= 5 nil)` が JS の
            null 強制で true になり、閾値の書き損じが静かに validated になった"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (gate/evaluate-hyp {:a 5} {:metric [:a] :op :>= :threshold nil}))))
  (testing "未知の演算子は既定に落ちず落ちる（kernel が -2 = 答えられなかった を返し、
            host の verdict 対応表が既定を持たない。ADR-2608122000）"
    (is (= gate/op-unknown (gate/op-code :≒)))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (gate/evaluate-hyp {:a 5} {:metric [:a] :op :≒ :threshold 3}))))
  (testing "0.3 のような 10 進閾値が bp で壊れない（floor だと 2999 になる）"
    (is (= 3000 (gate/->bp 0.3)))
    (is (= 200 (gate/->bp 0.02)))
    (is (= 10000 (gate/->bp 1.0))))
  (testing "再提案規則は順位の単調性 1 本（validated と measuring の 2 条件を畳んだもの）"
    (is (= 1 (gate/should-propose (gate/status-rank nil) gate/status-validated)))
    (is (= 0 (gate/should-propose (gate/status-rank :validated) gate/status-validated)))
    (is (= 0 (gate/should-propose (gate/status-rank :validated) gate/status-measuring)))
    (is (= 0 (gate/should-propose (gate/status-rank :measuring) gate/status-measuring)))
    (is (= 1 (gate/should-propose (gate/status-rank :measuring) gate/status-validated)))))

(deftest gate-proposals-cycle
  (testing "blocked gate → 準備 proposal into solution block"
    (let [idx (canvas/index (conj base
                                  {:canvas/kind :lean :canvas/product :cloud-itonami
                                   :canvas/id :cloud-itonami.solution :canvas/block :lean/solution
                                   :canvas/label "Solution" :canvas/items ["s1"]}))
          ;; reuse :hyp/t1 by pointing a spec at it via with-redefs
          props (with-redefs [gate/gate-specs {:hyp/t1 {:needs ["計器X"]}}]
                  (gate/proposals idx :cloud-itonami {}))]
      (is (some #(and (= :canvas/add-item (:proposal/action %))
                      (= :cloud-itonami.solution (:canvas/id %))
                      (re-find #"準備" (:event/value %))) props))))
  (testing "measurable+met gate → hyp validated proposal with evidence"
    (let [idx (canvas/index base)
          props (with-redefs [gate/gate-specs {:hyp/t1 {:metric [:subs] :op :>= :threshold 1
                                                        :evidence-label "subs"}}]
                  (gate/proposals idx :cloud-itonami {:subs 3}))]
      (is (some #(and (= :hyp/status (:proposal/action %)) (= :validated (:event/value %))
                      (:event/evidence %)) props))))
  (testing "already-validated hyp is NOT re-proposed (loop converges/goes dry)"
    (let [idx (canvas/index (mapv #(if (= :hyp/t1 (:hyp/id %))
                                     (assoc % :hyp/status :validated) %) base))
          props (with-redefs [gate/gate-specs {:hyp/t1 {:metric [:subs] :op :>= :threshold 1
                                                        :evidence-label "subs"}}]
                  (gate/proposals idx :cloud-itonami {:subs 3}))]
      (is (empty? (filter #(= :hyp/status (:proposal/action %)) props))))))

(deftest gate-aware-advisor-advances
  (testing "gate-aware advisor adds gate proposals on top of mock; validated hyp folds"
    (let [idx (canvas/index base)
          r (with-redefs [gate/gate-specs {:hyp/t1 {:metric [:subs] :op :>= :threshold 1
                                                    :evidence-label "subs"}}]
              (react/tick {:idx idx :product :cloud-itonami :metrics {:subs 9}
                           :advisor react/gate-aware-advisor}))]
      (is (= :validated (get-in (:idx r) [:hyps :hyp/t1 :hyp/status]))))))

(deftest funnel-evaluation
  (let [spec [{:key :awareness :label "訪問" :metric [:v]}
              {:key :acquisition :label "signup" :metric [:s] :benchmark 0.05}
              {:key :revenue :label "paid" :metric [:p] :benchmark 0.30}]]
    ;; ADR-2608160500: 率と gap は整数 basis point（10000 = 1.0）で、鍵も
    ;; `:rate-bp` / `:gap-bp` に変わった。同じ鍵のまま小数から bp へ型を変えると
    ;; 読み手に気づかせずに 100 倍ずれるので、名前を変えて壊れて見えるようにした。
    (testing "stage counts + step conversion rates (basis point)"
      (let [r (funnel/evaluate-funnel {:v 1000 :s 30 :p 12} spec)]
        (is (= [1000 30 12] (map :count (:stages r))))
        (is (= 2 (count (:steps r))))
        ;; 30/1000 = 300 bp < 500 bp benchmark (below); 12/30 = 4000 bp >= 3000 (ok)
        (is (= 300 (:rate-bp (first (:steps r)))))
        (is (= -200 (:gap-bp (first (:steps r)))))
        ;; 旧版はここが `(not (neg? (:gap …)))` で、cljs の `(neg? nil)` が false を
        ;; 返すため **gap が nil でも通っていた**（ADR-2608136000 の 1 問目:
        ;; 測れなかったものが、測って問題が無かったものと同じ値を返す）。実値を見る。
        (is (= 4000 (:rate-bp (second (:steps r)))))
        (is (= 1000 (:gap-bp (second (:steps r)))))))
    (testing "bottleneck = measured step furthest below benchmark"
      (let [r (funnel/evaluate-funnel {:v 1000 :s 30 :p 12} spec)]
        (is (= :awareness (:from (:bottleneck r))))
        (is (= :acquisition (:to (:bottleneck r))))))
    (testing "unmeasured stage → :missing, no false bottleneck"
      (let [r (funnel/evaluate-funnel {:v 1000} spec)]
        (is (= [:acquisition :revenue] (:missing r)))
        (is (nil? (:bottleneck r)))))
    (testing "all above benchmark → no bottleneck"
      (let [r (funnel/evaluate-funnel {:v 1000 :s 200 :p 100} spec)]
        (is (nil? (:bottleneck r)))))
    ;; --- ADR-2608160500 で直した/固定した振る舞い -----------------------------
    (testing "前段 0 件は『0% 転換』ではなく『分母が無い』"
      (let [r (funnel/evaluate-funnel {:v 0 :s 5 :p 1} spec)
            st (first (:steps r))]
        (is (true? (:measurable st)) "両端とも読めている")
        (is (false? (:rate-known st)) "しかし率は出せない")
        (is (nil? (:rate-bp st)))
        (is (nil? (:gap-bp st)) "gap も無い — 0 ではない")
        (is (not= :awareness (:from (:bottleneck r))) "未測定段をボトルネックにしない")
        ;; 出力でも『未計測』と区別する（以前はどちらも "(未計測)"）
        (is (re-find #"分母なし" (with-redefs [funnel/funnel-specs {:x spec}]
                                  (funnel/render-text :x {:v 0 :s 5 :p 1}))))))
    (testing "分子 0 は測った 0% であって欠測ではない"
      (let [r (funnel/evaluate-funnel {:v 1000 :s 0 :p 0} spec)
            st (first (:steps r))]
        (is (= 0 (:rate-bp st)))
        (is (= -500 (:gap-bp st)))
        (is (= :acquisition (:to (:bottleneck r))))))
    (testing "benchmark を持たない段には gap が無い（0 に既定しない）"
      (let [nob [{:key :awareness :label "訪問" :metric [:v]}
                 {:key :acquisition :label "signup" :metric [:s]}]
            st (first (:steps (funnel/evaluate-funnel {:v 1000 :s 0} nob)))]
        (is (= 0 (:rate-bp st)) "率は 0 bp と測れている")
        (is (nil? (:benchmark-bp st)))
        (is (nil? (:gap-bp st)) "gap は無い — 0 ではない")))
    (testing "benchmark ちょうどは未達ではない"
      (let [r (funnel/evaluate-funnel {:v 1000 :s 50 :p 15} spec)]
        (is (= 500 (:rate-bp (first (:steps r)))))
        (is (= 0 (:gap-bp (first (:steps r)))))
        (is (nil? (:bottleneck r)))))
    (testing "率が 1.0 を超えても頭打ちにしない（emitter の食い違いの信号）"
      (let [r (funnel/evaluate-funnel {:v 10 :s 30 :p 3} spec)]
        (is (= 30000 (:rate-bp (first (:steps r)))))
        (is (= 29500 (:gap-bp (first (:steps r)))))))
    (testing "負の件数は数ではなく『読めなかった』"
      ;; 旧版は -1 を素の数として算術に入れ、その段をボトルネックに仕立てていた。
      (let [r (funnel/evaluate-funnel {:v 1000 :s -1 :p 1} spec)
            st (first (:steps r))]
        (is (false? (:measurable st)))
        (is (nil? (:rate-bp st)))
        (is (nil? (:bottleneck r)))))
    (testing "同点のボトルネックは後ろの段が勝つ（旧 min-key と同じ）"
      (let [tie [{:key :awareness :label "a" :metric [:v]}
                 {:key :acquisition :label "b" :metric [:s] :benchmark 0.20}
                 {:key :revenue :label "c" :metric [:p] :benchmark 0.20}]
            ;; 100/1000 = 1000 bp と 10/100 = 1000 bp、目標はどちらも 2000 bp
            r (funnel/evaluate-funnel {:v 1000 :s 100 :p 10} tie)]
        (is (= [-1000 -1000] (map :gap-bp (:steps r))))
        (is (= :revenue (:to (:bottleneck r))))))))

(deftest funnel-proposals-cycle
  (testing "bottleneck → GTM proposal into channels block; snapshot into metrics; missing → 計器 into solution"
    (let [props (with-redefs [funnel/funnel-specs
                              {:cloud-itonami [{:key :awareness :label "訪問" :metric [:v]}
                                               {:key :acquisition :label "signup" :metric [:s] :benchmark 0.10}
                                               {:key :revenue :label "paid" :metric [:p] :benchmark 0.30}]}]
                  (funnel/proposals (canvas/index base) :cloud-itonami {:v 1000 :s 20}))]  ; s/v=2% < 10%, p missing
      (is (some #(and (= :cloud-itonami.channels (:canvas/id %))
                      (re-find #"GTM" (:event/value %))) props))
      (is (some #(and (= :cloud-itonami.metrics (:canvas/id %))
                      (re-find #"funnel" (:event/value %))) props))
      (is (some #(and (= :cloud-itonami.solution (:canvas/id %))
                      (re-find #"計器" (:event/value %))) props))))
  (testing "no spec → no proposals"
    (is (empty? (funnel/proposals (canvas/index base) :no-such-product {})))))

(deftest funnel-proposals-dedup
  (testing "bottleneck が不変なら GTM/snapshot/計器 は advisor 側で dedup され、2度目は空になる（theater 停止）"
    (let [spec {:cloud-itonami [{:key :awareness :label "訪問" :metric [:v]}
                                {:key :acquisition :label "signup" :metric [:s] :benchmark 0.10}
                                {:key :revenue :label "paid" :metric [:p] :benchmark 0.30}]}
          metrics {:v 1000 :s 20}  ; s/v=2% < 10%, p missing → GTM + snapshot + 計器
          idx0 (canvas/index (conj base
                                   {:canvas/kind :lean :canvas/product :cloud-itonami
                                    :canvas/id :cloud-itonami.channels :canvas/block :lean/channels
                                    :canvas/label "Channels" :canvas/items []}
                                   {:canvas/kind :lean :canvas/product :cloud-itonami
                                    :canvas/id :cloud-itonami.solution :canvas/block :lean/solution
                                    :canvas/label "Solution" :canvas/items ["s1"]}))
          first-props (with-redefs [funnel/funnel-specs spec]
                        (funnel/proposals idx0 :cloud-itonami metrics))
          {:keys [approved]} (react/governor idx0 first-props)
          folded (canvas/fold idx0 (mapv #(react/proposal->event 0 "advisor:funnel" %) approved))
          second-props (with-redefs [funnel/funnel-specs spec]
                         (funnel/proposals folded :cloud-itonami metrics))]
      (is (= 3 (count first-props)))   ; GTM + snapshot + 計器
      (is (= 3 (count approved)))      ; block あり・非重複で全可決
      (is (empty? second-props)))))    ; advisor-side dedup で2度目は空 = theater 停止

(deftest render-md-smoke
  (let [idx (canvas/index base)
        md (canvas/render-md idx :cloud-itonami {:as-of "2026-07-02"})]
    (is (re-find #"cloud-itonami — business model / lean canvas" md))
    (is (re-find #"\| `:hyp/t1` \| riskiest \| untested \|" md))))

(deftest render-datoms-projects-the-fold-not-the-base
  (let [idx (canvas/fold (canvas/index base)
                         [{:event/type :canvas/add-item
                           :canvas/id :cloud-itonami.problem :event/value "p2"}
                          {:event/type :canvas/note
                           :canvas/id :cloud-itonami.uvp :event/value "n1"}])
        tx (canvas/render-datoms idx :cloud-itonami {:as-of "2026-07-30"})
        header (first tx)
        blocks (filter :canvas/block tx)
        hyps (filter :hyp/id tx)]
    (testing "the header counts what it actually emitted, so a short projection
              cannot look complete"
      (is (= "canvas-cloud-itonami" (:projection/id header)))
      (is (= "canvas-projection" (:source/dataset header)))
      (is (= (count blocks) (:projection/blocks header)))
      (is (= (count hyps) (:projection/hypotheses header))))
    (testing "items are the FOLDED value, not the base — this is the whole reason
              the projection exists"
      (is (= ["p1" "p2"]
             (:canvas/items (first (filter #(= :lean/problem (:canvas/block %)) blocks)))))
      (is (= "n1" (:canvas/note (first (filter #(= :lean/uvp (:canvas/block %)) blocks))))))
    (testing "only this product's entities, and every one is tagged so a query
              can tell projected state from the pre-fold base"
      (is (= #{:cloud-itonami} (set (keep :canvas/product tx))))
      (is (every? #(= "canvas-projection" (:source/dataset %)) tx)))
    (testing "block order travels as data rather than being re-derived downstream"
      (is (= [:lean/problem :lean/uvp :lean/key-metrics] (map :canvas/block blocks)))
      (is (= [0 1 2] (map :canvas/order blocks))))
    (testing "db/ids are distinct, or a transact would collapse the entities"
      (is (= (count tx) (count (set (map :db/id tx))))))
    (testing "no gates were supplied, so no hypothesis claims a gate state —
              an unmeasured gate is not a failed one"
      (is (every? #(nil? (:gate/status %)) hyps)))))

(deftest render-datoms-carries-a-supplied-gate-verdict
  (let [idx (canvas/index base)
        verdict (gate/evaluate-hyp {} (get gate/gate-specs :hyp/t1))
        tx (canvas/render-datoms idx :cloud-itonami
                                 {:gates {:hyp/t1 (merge verdict {:status :measuring
                                                                  :distance "あと 3"})}})
        h (first (filter :hyp/id tx))]
    (is (= :measuring (:gate/status h)))
    (is (= "あと 3" (:gate/distance h)))
    (testing "the hypothesis keeps its own status too: :untested is what the
              ledger says, :measuring is what the metrics say, and collapsing
              them would lose which one moved"
      (is (= :untested (:hyp/status h))))))

(deftest render-datoms-carries-what-the-markdown-cannot
  (let [idx (canvas/index base)
        facts {:as-of "2026-07-30"
               :products {:cloud-itonami {:pricing 3 :grounding 4 :acute-problem 4
                                          :wedge 3 :tenx 3 :founder-fit 5
                                          :distribution 2 :defensibility 2
                                          :launched 3 :users 1 :revenue 0}}}
        scores (score/score-all idx facts [:cloud-itonami])
        tx (score/render-datoms scores facts)
        header (first tx)
        product (first (filter :score/product tx))
        dims (filter :dim/name tx)
        by-name (into {} (map (juxt :dim/name identity)) dims)]
    (testing "the header counts what it emitted, so a short projection cannot
              look complete"
      (is (= "maturity-scores" (:projection/id header)))
      (is (= "maturity-scores" (:source/dataset header)))
      (is (= 1 (:projection/products header)))
      (is (= "2026-07-30" (:projection/as-of header))))
    (testing "the composite scores survive as numbers rather than as table cells"
      (is (number? (:score/bmc product)))
      (is (number? (:score/yc product)))
      (is (= (get-in scores [:cloud-itonami :bmc :score]) (:score/bmc product))))
    (testing "fourteen dimensions — 5 BMC + 9 YC — each queryable on its own"
      (is (= 14 (count dims)))
      (is (= :cloud-itonami.pricing (:dim/id (by-name :pricing)))))
    (testing "a computed dimension and a recorded judgement are different kinds of
              claim, and the markdown table flattens them into one row"
      (is (= :auto (:dim/source (by-name :completeness))))
      (is (= :facts (:dim/source (by-name :pricing))))
      (is (true? (:dim/recorded? (by-name :completeness))))
      (is (true? (:dim/recorded? (by-name :pricing)))))
    (testing "db/ids are distinct, or a transact would collapse the entities"
      (is (= (count tx) (count (set (map :db/id tx))))))))

(deftest an-unrecorded-judgement-is-marked-not-silently-scored-zero
  (let [idx (canvas/index base)
        ;; :defensibility and :revenue never recorded. score-product reads facts
        ;; with (get f k 0), so they score as the WORST value rather than as
        ;; absent — the projection carries that distinction even though the
        ;; arithmetic does not.
        facts {:as-of "2026-07-30"
               :products {:cloud-itonami {:pricing 3 :grounding 4 :acute-problem 4
                                          :wedge 3 :tenx 3 :founder-fit 5
                                          :distribution 2 :launched 3 :users 1}}}
        tx (score/render-datoms (score/score-all idx facts [:cloud-itonami]) facts)
        product (first (filter :score/product tx))
        by-name (into {} (map (juxt :dim/name identity)) (filter :dim/name tx))]
    (is (= 2 (:score/unrecorded-dims product)))
    (is (= #{"defensibility" "revenue"} (set (:score/unrecorded product))))
    (testing "the value is still 0 — this projection reports the arithmetic, it
              does not change it — but 0-because-absent is now distinguishable
              from 0-because-assessed"
      (is (= 0.0 (:dim/value (by-name :defensibility))))
      (is (false? (:dim/recorded? (by-name :defensibility))))
      (is (true? (:dim/recorded? (by-name :pricing)))))
    (testing "an :auto dimension is never counted as unrecorded — nobody enters it"
      (is (true? (:dim/recorded? (by-name :validation)))))))

(deftest render-datoms-keeps-products-apart
  (let [idx (canvas/index base)
        facts {:as-of "2026-07-30" :products {}}
        tx (score/render-datoms (score/score-all idx facts [:cloud-itonami :etzhayyim])
                                facts)]
    (is (= 2 (count (filter :score/product tx))))
    (is (= 28 (count (filter :dim/name tx))))
    (is (= (count tx) (count (set (map :db/id tx)))))
    (testing "with no facts at all, every :facts dimension is unrecorded — and
              says so rather than reading as a portfolio scored at zero"
      ;; 14 dimensions less the 3 :auto ones this namespace computes itself.
      (is (every? #(= 11 (:score/unrecorded-dims %)) (filter :score/product tx))))))

(deftest cli-help-text
  (testing "global help (nil topic) lists cli desc + every command's usage"
    (let [txt (cli/help-text :itonami nil)]
      (is (str/starts-with? txt "itonami cli — business operator (L3)"))
      (is (str/includes? txt "commands:"))
      (is (str/includes? txt "canvas show [--product P]"))
      (is (str/includes? txt "hyp list [--product P]"))
      (is (str/includes? txt "ledger show [--tail N]"))
      (is (str/includes? txt "run `itonami <command> --help`"))))
  (testing "per-command topic scopes usage to just that command"
    (let [txt (cli/help-text :manimani "canvas")]
      (is (str/starts-with? txt "manimani canvas — usage:"))
      (is (str/includes? txt "canvas add|retract <canvas-id> <text>"))
      (is (not (str/includes? txt "hyp list")))
      (is (not (str/includes? txt "ledger show")))))
  (testing "unknown topic falls back to global help rather than erroring"
    (let [txt (cli/help-text :itonami "no-such-command")]
      (is (str/includes? txt "commands:"))))
  (testing "every registry cli-key renders without throwing, using its own desc"
    (doseq [[cli-key {:keys [desc]}] cli/registry]
      (is (str/includes? (cli/help-text cli-key nil) desc)))))

(deftest murakumo-complete-from-http
  (testing "extracts OpenAI content; empty content falls back to reasoning"
    (is (= "[{:x 1}]"
           (murakumo/extract-content
            {"choices" [{"message" {"content" "[{:x 1}]"}}]})))
    (is (= "fallback"
           (murakumo/extract-content
            {"choices" [{"message" {"content" "" "reasoning_content" "fallback"}}]}))))
  (testing "make-complete is fail-soft on HTTP errors"
    (let [complete (murakumo/make-complete (fn [_] {:status 500 :body "nope"}) {})]
      (is (nil? (complete "hi")))))
  (testing "make-complete returns content on 200"
    (let [body "{\"choices\":[{\"message\":{\"content\":\"[{:ok true}]\"}}]}"
          complete (murakumo/make-complete (fn [_] {:status 200 :body body}) {})]
      (is (= "[{:ok true}]" (complete "hi")))))
  (testing "```edn フェンスを剥がすと read-string 可能になる (react/llm-advisor は read-string するため)"
    (is (= "[{:a 1}]" (murakumo/strip-fences "```edn\n[{:a 1}]\n```")))
    (is (= "[{:a 1}]" (murakumo/strip-fences "```\n[{:a 1}]\n```")))
    (is (= "[{:a 1}]" (murakumo/strip-fences "[{:a 1}]"))))
  (testing "extract-content strips a fence around the message content"
    (is (= "[{:x 1}]"
           (murakumo/extract-content
            {"choices" [{"message" {"content" "```edn\n[{:x 1}]\n```"}}]})))))

(deftest kotobase-event-projection
  (let [evs [{:event/seq 42 :event/type :canvas/add-item :event/actor "advisor:auto"
              :event/at "t" :canvas/id :cloud-itonami.problem :event/value "v"
              :event/reason "r"}]
        tx (kbase/events->tx-data evs)]
    (is (= 1 (count tx)))
    (is (= "bmc.event/42" (:db/id (first tx))))
    (is (= ":cloud-itonami.problem" (:bmc.event/canvas-id (first tx))))
    (is (str/includes? (kbase/events->tx-edn evs) "bmc.event/42")))
  (testing "enabled? respects env and flags"
    (is (true? (kbase/enabled? (constantly nil) {})))
    (is (false? (kbase/enabled? (constantly "0") {})))
    (is (false? (kbase/enabled? (constantly nil) {:no-kotobase true})))))

(deftest compose-advisors-concat
  (let [a (fn [_] [{:proposal/action :canvas/add-item :event/value "a"}])
        b (fn [_] [{:proposal/action :canvas/add-item :event/value "b"}])
        c (react/compose-advisors a b)]
    (is (= ["a" "b"] (mapv :event/value (c {}))))))

(deftest advisor-mode-resolution
  (is (= "auto" (cli/advisor-mode {} (constantly nil))))
  (is (= "gate" (cli/advisor-mode {:advisor "gate"} (constantly "auto"))))
  (is (= "murakumo" (cli/advisor-mode {} (constantly "murakumo")))))

(deftest cli-command-help-covers-every-documented-command
  ;; command-help は ns docstring の一覧と対応させる運用なので、両者がズレたら
  ;; help がサイレントに古びる。docstring 側に出てくる各コマンド語がここにも
  ;; 出てくることをスモークチェックする。
  (doseq [cmd ["products" "canvas" "hyp" "react" "gate" "funnel" "score" "allocate" "ledger"]]
    (is (some? (cli/find-command-help cmd)) (str cmd " missing from command-help"))))

(deftest rolling-observation-retention
  (let [idx (canvas/index base)
        signal #(str "観測 (signal): 実測 " % " req/7d")
        events (concat
                (for [i (range 5)]
                  {:event/type :canvas/add-item :canvas/id :cloud-itonami.problem
                   :event/value (signal i)})
                [{:event/type :canvas/add-item :canvas/id :cloud-itonami.problem
                  :event/value "観測 (2026-07-06 QA): 実質的な発見 — 残す"}
                 {:event/type :canvas/add-item :canvas/id :cloud-itonami.problem
                  :event/value "観測 (paths): 上位 page A"}
                 {:event/type :canvas/add-item :canvas/id :cloud-itonami.problem
                  :event/value "観測 (paths): 上位 page B"}])
        items (get-in (canvas/fold idx events)
                      [:blocks :cloud-itonami.problem :canvas/items])]
    (testing "signal 観測は window (3) 件だけ残り、最新が生き残る"
      (is (= [(signal 2) (signal 3) (signal 4)]
             (filterv #(clojure.string/starts-with? % "観測 (signal):") items))))
    (testing "paths 観測は別カウント (window 未満なら全部残る)"
      (is (= 2 (count (filterv #(clojure.string/starts-with? % "観測 (paths):") items)))))
    (testing "実質的観測と既存 item は無期限に残る"
      (is (some #{"観測 (2026-07-06 QA): 実質的な発見 — 残す"} items))
      (is (some #{"p1"} items)))))

;; ---- LLM advisor 配線 (ADR-2607172700) ------------------------------------------

(deftest llm-advisor-parses-and-fails-safe
  (let [idx (canvas/index base)
        obs {:idx idx :product :cloud-itonami :blocks {} :hyps [] :metrics {}}
        good (react/llm-advisor
              (fn [_] "[{:proposal/action :canvas/add-item :canvas/id :cloud-itonami.uvp :event/value \"LLM 提案\" :proposal/reason \"test\"}]"))
        garbage (react/llm-advisor (fn [_] "すみません、EDN では返せません。"))
        thrower (react/llm-advisor (fn [_] (throw (ex-info "network down" {}))))]
    (testing "整形済み EDN vector は proposal 列になる"
      (is (= [:canvas/add-item] (mapv :proposal/action (good obs)))))
    (testing "非 EDN 出力は空 (fail-safe)"
      (is (= [] (vec (garbage obs)))))
    (testing "complete の例外も空 (fail-safe)"
      (is (= [] (vec (thrower obs)))))))

(deftest llm-advisor-prompt-includes-dedup-constraint
  (testing "llm-advisor の prompt に既存 item の重複禁止指示が含まれる (LLM theater 停止)"
    (let [captured (atom nil)
          advisor (react/llm-advisor (fn [prompt] (reset! captured prompt) "[]")) ; fail-safe・提案ゼロ
          obs {:product :cloud-itonami :layer :business-operator
               :blocks {:cloud-itonami.channels ["GTM existing bottleneck action"]}
               :hyps [] :metrics {}}]
      (advisor obs)
      (testing "prompt に dedup 指示（governor duplicate 拒否）が含まれる"
        (is (str/includes? @captured "duplicate")))
      (testing "prompt に既存 item が渡っている（LLM が重複を認識できる）"
        (is (str/includes? @captured "GTM existing bottleneck action"))))))

;; strip-fences moved to gftd.murakumo (transport-layer concern for the
;; murakumo-based advisor); see `murakumo-complete-from-http` above.
;; sse-collect had no replacement: it existed only to reassemble the old
;; GFTD_LLM_URL curl/SSE transport (ADR-2607172800), which this PR's
;; murakumo.cljc-based `make-complete` (plain HTTP, injected http-post!)
;; replaces outright — there is no streaming path to test here anymore.

#?(:cljs
   (deftest normalize-llm-proposal-keywordizes-string-ids
     (testing "string の canvas/id・hyp/id・action を keyword 化 (qwen3.6 実測の揺れ)"
       (is (= {:proposal/action :canvas/add-item :canvas/id :cloud-itonami.metrics :event/value "v"}
              (cli/normalize-llm-proposal
               {:proposal/action "canvas/add-item" :canvas/id "cloud-itonami.metrics" :event/value "v"})))
       (is (= :hyp/itonami-smb-pay
              (:hyp/id (cli/normalize-llm-proposal {:hyp/id ":hyp/itonami-smb-pay"}))))
       (is (= :cloud-itonami.problem
              (:canvas/id (cli/normalize-llm-proposal {:canvas/id (symbol "cloud-itonami.problem")})))))
     (testing "keyword はそのまま素通し"
       (is (= {:canvas/id :a.b} (cli/normalize-llm-proposal {:canvas/id :a.b}))))))

;; ---- traffic classification (ADR-2607231800) ------------------------------------
;; classify-path が実測 canvas-ledger.edn の bot-probe path を正しく分類すること
;; (:network-isekai.channels 2026-07-09..22 の実観測、90-docs/business/
;; canvas-ledger.edn より — WordPress/PHP スキャナ signature 群と、
;; //abcd.php のような二重スラッシュ + probe-path-re 非対象の real route)。

(deftest classify-path-known-bot-probes-observed-in-canvas-ledger
  (testing "実測スキャナ signature (canvas-ledger.edn 2026-07-18..22) は :probe に分類される"
    (doseq [path ["/mailer.php" "/wp.php" "/a1.php" "/1996.php" "/biufile.php"
                  "/w3lls.php" "/inputs.php" "/wp-lvminl.php" "/1c.php" "/ops.php"
                  "/min.php" "/m.php" "/cxc.php" "/sbhu.php" "/ws.php"
                  "/wp-admin/css/colors/index.php" "/.git" "/.env"
                  "/config/config.php" "/config/nexmo.php" "/Dockerfile"
                  "/package-updates/yum.cgi" "//abcd.php" "//avcqlevnbk.php"]]
      (is (= :probe (traffic/classify-path :network-isekai path))
          (str path " should classify as :probe"))))
  (testing "既知の実 route は :channel に分類される"
    (doseq [path ["/" "/robots.txt" "/favicon.ico" "/sitemap.xml"
                  "/feed/fork-stats.edn" "/play" "/studio" "/api/events"]]
      (is (= :channel (traffic/classify-path :network-isekai path))
          (str path " should classify as :channel"))))
  (testing "allowlist にも probe-path-re にも一致しない path は :unclassified
            (捏造ゼロ — 落とさず可視化する。allowlist 未登録の real route かもしれない)"
    (doseq [path ["/some-new-page" "/team/roster"]]
      (is (contains? #{:channel :unclassified} (traffic/classify-path :network-isekai path))))
    (is (= :unclassified (traffic/classify-path :network-isekai "/totally-unknown-route"))))
  (testing "channel-allowlist 未登録 product は :probe-path-re のみ効く (allowlist が nil なので :channel には絶対ならない) —
            zone-top-paths 側は classify-path 自体を呼ばない (opt-in)、既存 status-only 挙動を壊さない のはそちらで保証。
            cloud-itonami も意図的に未登録 (status-only split で既に十分なため — traffic.cljc の channel-allowlist
            docstring 参照): probe-path-re 自体は product-agnostic なので :probe 判定は効くが、:channel には
            絶対にならない。"
    (is (= :probe (traffic/classify-path :net-kotobase "/mailer.php")))
    (is (= :unclassified (traffic/classify-path :net-kotobase "/")))
    (is (= :probe (traffic/classify-path :cloud-itonami "/wp-login.php")))
    (is (= :unclassified (traffic/classify-path :cloud-itonami "/isco-4712")))))

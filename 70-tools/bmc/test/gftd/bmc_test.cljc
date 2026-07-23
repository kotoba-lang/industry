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
      (is (= (+ (* 50.0 (/ 20.0 30.0)) (* 50.0 (/ 3.0 15.0)))
             (get-in s [:yc :score]))))
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
    (testing "stage counts + step conversion rates"
      (let [r (funnel/evaluate-funnel {:v 1000 :s 30 :p 12} spec)]
        (is (= [1000 30 12] (map :count (:stages r))))
        (is (= 2 (count (:steps r))))
        ;; 30/1000 = 0.03 < 0.05 benchmark (below); 12/30 = 0.4 >= 0.30 (ok)
        (is (< (Math/abs (- 0.03 (:rate (first (:steps r))))) 1e-9))
        (is (neg? (:gap (first (:steps r)))))
        (is (not (neg? (:gap (second (:steps r))))))))
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
        (is (nil? (:bottleneck r)))))))

(deftest funnel-proposals-cycle
  (testing "bottleneck → GTM proposal into channels block; snapshot into metrics; missing → 計器 into solution"
    (let [props (with-redefs [funnel/funnel-specs
                              {:cloud-itonami [{:key :awareness :label "訪問" :metric [:v]}
                                               {:key :acquisition :label "signup" :metric [:s] :benchmark 0.10}
                                               {:key :revenue :label "paid" :metric [:p] :benchmark 0.30}]}]
                  (funnel/proposals :cloud-itonami {:v 1000 :s 20}))]  ; s/v=2% < 10%, p missing
      (is (some #(and (= :cloud-itonami.channels (:canvas/id %))
                      (re-find #"GTM" (:event/value %))) props))
      (is (some #(and (= :cloud-itonami.metrics (:canvas/id %))
                      (re-find #"funnel" (:event/value %))) props))
      (is (some #(and (= :cloud-itonami.solution (:canvas/id %))
                      (re-find #"計器" (:event/value %))) props))))
  (testing "no spec → no proposals"
    (is (empty? (funnel/proposals :no-such-product {})))))

(deftest render-md-smoke
  (let [idx (canvas/index base)
        md (canvas/render-md idx :cloud-itonami {:as-of "2026-07-02"})]
    (is (re-find #"cloud-itonami — business model / lean canvas" md))
    (is (re-find #"\| `:hyp/t1` \| riskiest \| untested \|" md))))

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

#!/usr/bin/env nbb
;; kotobase-live-latency-probe.cljs — kotobase.net の実サーフェスを外から測る。
;;
;;   npx nbb scripts/kotobase-live-latency-probe.cljs [samples] [out.edn]
;;
;; ## なぜ要るか
;;
;; ADR-2607310900 訂正3 は本番 p50 を 2,491ms（うち hydration 2,282ms = 92%）と
;; 記録した。ADR-2608021000 の novelty segment 化は kotobase-peer で 11 倍の
;; 改善を出したが（90-docs/kotobase-performance/2026-08-07-novelty-segmentation-dag-shape.edn）、
;; **それが deployment に届いたかを確かめる手段が無かった** —— 配信元
;; `network-awai/net-kotobase` は private で読めない。
;;
;; コードが読めなくても**外から測ることはできる**。これはその経路。
;;
;; ## 測り方の前提（ここを外すと嘘になる）
;;
;; - **cold start を分離する。** Cloudflare Worker は isolate が落ちていると
;;   最初の 1 発が桁違いに遅い。1 サンプルだけ取って「遅い」と言うのは計測ではない。
;;   第 1 サンプルを `:cold` として別に出し、p50/p95 は残りから取る。
;; - **read only。** GET しか投げない。書き込み経路には触れない。
;; - **同一ホスト内で control を取る。** 生の数字には network・TLS・Worker 起動・
;;   ルーティングが全部入る。同じホストの存在しないパス（`:control`）は
;;   そこまで同じ経路を通ってハンドラの仕事だけをしないので、差 = ハンドラ実費。
;;   別ホストとの比較では「遅いのは回線では」に答えられない。
;; - **単一ホストからの測定**。ADR-2607250100 軸 6 が自分で書いているとおり、
;;   これは独立した status surface ではない。
;;
;; ## これを fleet gate にしない理由
;;
;; 他の検査（`scripts/fleet-ci/gates/`）と違い、これは **live の本番に負荷をかける**。
;; CI が回るたびに 6 サーフェス × N サンプル投げるのは、測っている対象を測定行為で
;; 汚す。しかも遅延は他テナント・colo・isolate の warm 具合で動くので、**閾値を置くと
;; 必ず flaky になる**——赤が「退行」なのか「たまたま」なのか区別できない gate は、
;; やがて誰も見なくなる。
;;
;; これは**問いに答えるための計測**であって回帰検査ではない。回帰検査が要るなら、
;; 対象は wall-clock ではなく `kotobase-shard-index` の GET 予算のように**回数**にする。

(ns kotobase-live-latency-probe)

(def fs (js/require "node:fs"))

(def endpoints
  "`:handler-work?` はその surface が本体の仕事（protocols-worker では chain の
  hydrate）を通るか。

  **`:control` が要点。** 同じホストの存在しないパスは、DNS・TLS・Worker 起動・
  ルーティングまでは同じ経路を通り、ハンドラの仕事だけをしない。だから
  `/health` と `:control` の差は**そのホストの中で**測れる —— 『遅いのは
  ネットワークか cold start では』という一番ありがちな誤帰属を、別ホストとの
  比較ではなく同一ホスト内の差で潰す。"
  [{:url "https://kotobase.net/health" :label :apex :handler-work? false}
   {:url "https://kotobase-storage-d1.aozora.app/health" :label :d1-backend :handler-work? false}
   {:url "https://sparql.kotobase.net/health" :label :sparql :handler-work? true
    :control "https://sparql.kotobase.net/__probe_no_such_route__"}
   {:url "https://cypher.kotobase.net/health" :label :cypher :handler-work? true
    :control "https://cypher.kotobase.net/__probe_no_such_route__"}
   {:url "https://gremlin.kotobase.net/health" :label :gremlin :handler-work? true
    :control "https://gremlin.kotobase.net/__probe_no_such_route__"}
   {:url "https://graphql.kotobase.net/health" :label :graphql :handler-work? true
    :control "https://graphql.kotobase.net/__probe_no_such_route__"}])

(defn- now-ms [] (.now js/Date))

(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn- timed-get
  "One GET. Returns {:ms :status :error}. Never throws — a probe that dies on
  the first transient failure measures nothing."
  [url]
  (let [t0 (now-ms)]
    (-> (js/fetch url #js {:method "GET"})
        (.then (fn [r]
                 (-> (.text r)
                     (.then (fn [_] {:ms (- (now-ms) t0) :status (.-status r)})))))
        (.catch (fn [e] {:ms (- (now-ms) t0) :status nil
                         :error (or (.-message e) (str e))})))))

(defn- percentile [sorted p]
  (when (seq sorted)
    (nth sorted (min (dec (count sorted))
                     (int (js/Math.floor (* p (count sorted))))))))

(defn- stats [samples ok?]
  (let [ok (filterv ok? samples)
        ms (vec (sort (map :ms ok)))]
    {:n (count samples) :ok (count ok)
     :p50-ms (percentile ms 0.5) :p95-ms (percentile ms 0.95)
     :min-ms (first ms) :max-ms (last ms)
     :statuses (frequencies (map :status samples))}))

(defn- summarise [{:keys [label url handler-work? control]} samples control-samples]
  (let [cold (first samples)
        warm (vec (rest samples))
        s (stats warm #(= 200 (:status %)))
        c (when (seq control-samples)
            (stats (vec (rest control-samples)) #(some? (:status %))))]
    (cond-> {:label label
             :url url
             :handler-work? handler-work?
             :cold-ms (:ms cold)
             :warm (dissoc s :statuses)
             :statuses (:statuses s)
             :errors (vec (distinct (keep :error samples)))}
      c (assoc :control {:url control :warm (dissoc c :statuses) :statuses (:statuses c)}
               ;; the number that survives the obvious objection
               :handler-cost-ms (when (and (:p50-ms s) (:p50-ms c))
                                  (- (:p50-ms s) (:p50-ms c)))))))

(defn- sample-n
  "`n` sequential GETs of `url`, 250ms apart. Spaced so the probe is not itself
  load on a live production service."
  [url n]
  (letfn [(step [i acc]
            (if (or (nil? url) (>= i n))
              (js/Promise.resolve acc)
              (-> (timed-get url)
                  (.then (fn [r] (-> (sleep 250)
                                     (.then (fn [_] (step (inc i) (conj acc r))))))))))]
    (step 0 [])))

(defn- probe-one [{:keys [url control] :as ep} n]
  (-> (sample-n url n)
      (.then (fn [main]
               (-> (sample-n control (if control n 0))
                   (.then (fn [ctl] (summarise ep main ctl))))))))

(defn -main [& args]
  (let [n (js/parseInt (or (first args) "12"))
        out (or (second args)
                "90-docs/kotobase-performance/2026-08-08-live-latency-probe.edn")]
    (letfn [(step [eps acc]
              (if (empty? eps)
                (js/Promise.resolve acc)
                (let [ep (first eps)]
                  (println "probing" (:url ep) (str "(" n " samples)"))
                  (-> (probe-one ep n)
                      (.then (fn [r]
                               (println (str "  cold=" (:cold-ms r) "ms  warm p50="
                                             (get-in r [:warm :p50-ms]) "ms  p95="
                                             (get-in r [:warm :p95-ms]) "ms  ok="
                                             (get-in r [:warm :ok]) "/" (get-in r [:warm :n])
                                             (when (:control r)
                                               (str "  | control p50="
                                                    (get-in r [:control :warm :p50-ms])
                                                    "ms  handler-cost="
                                                    (:handler-cost-ms r) "ms"))))
                               (step (rest eps) (conj acc r))))))))]
      (-> (step endpoints [])
          (.then
           (fn [results]
             (let [hyd (filter :handler-work? results)
                   non (filter (complement :handler-work?) results)
                   med (fn [xs] (let [v (vec (sort (keep #(get-in % [:warm :p50-ms]) xs)))]
                                  (percentile v 0.5)))
                   med-handler (let [v (vec (sort (keep :handler-cost-ms hyd)))]
                                 (percentile v 0.5))
                   receipt
                   {:receipt :kotobase/live-latency
                    :date "2026-08-08"
                    :samples-per-endpoint n
                    :method "GET /health, sequential, 250ms apart, first sample reported
                             separately as :cold-ms and excluded from p50/p95"
                    :vantage "single host (Claude Code remote container), through an
                              egress proxy. NOT an independent status surface."
                    :results results
                    :summary
                    {:handler-work-p50-median-ms (med hyd)
                     :no-handler-work-p50-median-ms (med non)
                     :handler-cost-p50-median-ms med-handler
                     :baseline-2026-07-31
                     {:source "ADR-2607310900 訂正3"
                      :hydrating-health-ms 2386
                      :non-hydrating-diag-health-ms 104
                      :hydration-share-of-query 0.92}}
                    :caveats
                    ["End-to-end: network, TLS, Worker start, routing and handler are all
                      inside the raw numbers. :handler-cost-ms subtracts a control request
                      to a NON-EXISTENT path on the SAME host, which travels the same
                      network and worker-start path and does no handler work -- so that
                      difference is attributable to the handler, and the usual objection
                      ('it is just cold start / the network') does not survive it."
                     "The control returns 401, not 404: these surfaces authenticate before
                      routing. That is fine for the purpose -- what matters is that it does
                      no handler work -- but it means the control also excludes auth cost
                      from the difference."
                     "Repeated GETs over one connection keep hitting a warm isolate. A
                      fresh connection costs several times more (measured the same day:
                      ~8s versus a ~2.4s warm p50 on sparql). ADR-2607310900 訂正3 recorded
                      the same effect -- 12 identical queries landed on only 4 isolates.
                      Real clients are spread across colos, so warm p50 is the FLOOR, not
                      the typical experience."
                     "One vantage point, one moment. ADR-2607250100 軸 6 records that the
                      workspace has no independent status surface; this does not become one."
                     "/health is not a query. A real query adds parse + plan + scan on top."]}]
               (.mkdirSync fs "90-docs/kotobase-performance" #js {:recursive true})
               (.writeFileSync fs out (pr-str receipt) "utf8")
               (println "\n== summary ==")
               (println (pr-str (:summary receipt)))
               (println "receipt ->" out))))))))

(apply -main (drop 3 (vec (js->clj js/process.argv))))

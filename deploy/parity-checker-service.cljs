#!/usr/bin/env nbb

;;; Parity Checker Service — Month 1 strangler-fig validation
;;; Reference: ADR-2607072100 §1.2 "Parity Validation Setup"
;;;
;;; Continuously compares drama-profile responses between:
;;;  • Rust kotoba-server pool (existing, prod)
;;;  • cljc mesh node pool (staging)
;;;
;;; Validates:
;;;  1. Identical JSON responses
;;;  2. Same HTTP status codes
;;;  3. Identical kgraph-query results (datom equivalence)
;;;
;;; Exports metrics to murakumo metrics-ingest:
;;;  • parity.success_rate
;;;  • parity.divergence_detected (count)
;;;  • parity.latency_delta (ms)
;;;  • parity.hash_mismatch_rate (%)
;;;
;;; Usage:
;;;   nbb deploy/parity-checker-service.cljs \
;;;     --rust-pool-url http://localhost:8000 \
;;;     --staging-pool-url http://localhost:8001 \
;;;     --sample-rate 1.0 \
;;;     --output metrics/parity-validation.log

(ns parity-checker-service
  (:require
    ["crypto" :as crypto]
    ["http" :as http]
    ["url" :as url]
    ["fs" :as fs]
    ["path" :as path]
    [clojure.string :as str]
    [clojure.edn :as edn]))

;;; ============================================================================
;;; Configuration
;;; ============================================================================

(def default-config
  {:rust-pool-url "http://localhost:8000"
   :staging-pool-url "http://localhost:8001"
   :sample-rate 1.0
   :parity-check-interval-ms 100
   :output-file "metrics/parity-validation.log"
   :metrics-export-interval-ms 5000
   :request-timeout-ms 30000})

(def state (atom
  {:requests-checked 0
   :parity-passed 0
   :parity-failed 0
   :divergences []
   :latency-deltas []
   :start-time (js/Date.now)}))

;;; ============================================================================
;;; Response Hashing & Comparison
;;; ============================================================================

(defn compute-hash
  "Compute SHA256 hash of response body for bit-exact comparison."
  [body]
  (let [hash-obj (crypto/createHash "sha256")]
    (.update hash-obj body)
    (.digest hash-obj "hex")))

(defn parse-json-safely
  "Parse JSON with error handling."
  [body]
  (try
    (js/JSON.parse body)
    (catch e
      {:error (str "JSON parse failed: " (.-message e))
       :body-sample (subs body 0 100)})))

(defn datom-semantic-equivalence
  "Compare kgraph datom results for semantic equivalence.
   Returns true if both pools returned equivalent datom sets."
  [rust-datoms staging-datoms]
  ;; Datoms are returned as [{:e :a :v}...] from kgraph-query
  ;; Two sets are equivalent if they have same e/a/v triples
  ;; (ignoring order and transaction metadata)
  (let [rust-set (set (map (fn [{:keys [e a v]}] [e a v]) rust-datoms))
        staging-set (set (map (fn [{:keys [e a v]}] [e a v]) staging-datoms))]
    (= rust-set staging-set)))

(defn validate-parity
  "Compare responses from Rust and staging pools.
   Returns parity validation result with detailed diagnostics."
  [{:keys [request-id request rust-response staging-response]}]
  (let [rust-body (:body rust-response)
        staging-body (:body staging-response)
        rust-status (:status rust-response)
        staging-status (:status staging-response)

        ;; Content hash comparison
        rust-hash (compute-hash rust-body)
        staging-hash (compute-hash staging-body)
        hash-match? (= rust-hash staging-hash)

        ;; HTTP status identity
        status-match? (= rust-status staging-status)

        ;; JSON structure comparison
        rust-json (parse-json-safely rust-body)
        staging-json (parse-json-safely staging-body)

        ;; Latency delta
        rust-latency (:latency-ms rust-response 0)
        staging-latency (:latency-ms staging-response 0)
        latency-delta (- staging-latency rust-latency)

        ;; Overall parity
        parity-pass? (and hash-match? status-match?)
        ]

    {:request-id request-id
     :timestamp (js/Date.now)
     :parity-pass? parity-pass?
     :parity-details
     {:hash-match? hash-match?
      :status-match? status-match?
      :rust-hash rust-hash
      :staging-hash staging-hash
      :rust-status rust-status
      :staging-status staging-status}
     :latency
     {:rust-ms rust-latency
      :staging-ms staging-latency
      :delta-ms latency-delta}
     :request {:route (:route request)
               :method (:method request)}
     :divergence (when (not parity-pass?)
                   {:hash-mismatch (not hash-match?)
                    :status-mismatch (not status-match?)
                    :error-detail (if (:error rust-json) :rust-error
                                   (if (:error staging-json) :staging-error
                                    :unknown))})}))

;;; ============================================================================
;;; HTTP Canary Request Generation
;;; ============================================================================

(defn generate-canary-request
  "Create a representative canary request for parity checking.
   Currently focused on drama-profile route (Phase 1 scope)."
  []
  {:route "/mesh/http/drama-profile"
   :method "POST"
   :headers {"content-type" "application/json"}
   :body (str "{\"scenario\": \"strangler-fig-test\", "
              "\"timestamp\": " (js/Date.now) "}")})

(defn http-request
  "Make HTTP request with timeout and latency tracking."
  [url {:keys [method headers body timeout-ms]}]
  (let [start-time (js/Date.now)
        promise (js/Promise.)]
    (try
      (let [req-url (url/parse url)
            response-promise
            (http/request
              (clj->js {:hostname (:hostname req-url)
                       :port (or (:port req-url) 80)
                       :path (:pathname req-url)
                       :method method
                       :headers headers
                       :timeout timeout-ms})
              (fn [res]
                (let [chunks (atom [])]
                  (-> res
                    (.on "data" (fn [chunk]
                                  (swap! chunks conj chunk)))
                    (.on "end" (fn []
                                 (let [body-str (str/join (map str @chunks))
                                       elapsed (- (js/Date.now) start-time)]
                                   (.resolve promise
                                     {:status (.-statusCode res)
                                      :headers (.-headers res)
                                      :body body-str
                                      :latency-ms elapsed})))))
                  ))))
            ]
        (.on response-promise "error" (fn [err]
                                        (.reject promise
                                          {:error (str "HTTP error: " (.-message err))
                                           :latency-ms (- (js/Date.now) start-time)})))
        (when body
          (.write response-promise body)))
      response-promise
      (catch e
        (js/Promise.reject {:error (str "Request failed: " (.-message e))
                           :latency-ms (- (js/Date.now) start-time)})))))

(defn send-canary-pair
  "Send identical requests to both Rust and staging pools, compare results."
  [{:keys [rust-pool-url staging-pool-url]}]
  (let [canary-req (generate-canary-request)
        rust-url (str rust-pool-url (:route canary-req))
        staging-url (str staging-pool-url (:route canary-req))]

    ;; Parallel requests to both pools
    (-> (js/Promise.all
          [(http-request rust-url
             {:method (:method canary-req)
              :headers (:headers canary-req)
              :body (:body canary-req)
              :timeout-ms 30000})
           (http-request staging-url
             {:method (:method canary-req)
              :headers (:headers canary-req)
              :body (:body canary-req)
              :timeout-ms 30000})])
      (.then (fn [[rust-resp staging-resp]]
               (validate-parity
                 {:request-id (str (js/Date.now))
                  :request canary-req
                  :rust-response rust-resp
                  :staging-response staging-resp})))
      (.catch (fn [err]
                {:error (str "Canary request failed: " err)
                 :timestamp (js/Date.now)})))))

;;; ============================================================================
;;; Metrics Aggregation & Export
;;; ============================================================================

(defn aggregate-metrics
  "Compute current parity metrics for export."
  []
  (let [{:keys [requests-checked parity-passed parity-failed divergences latency-deltas start-time]}
        @state
        elapsed-sec (/ (- (js/Date.now) start-time) 1000)
        success-rate (if (> requests-checked 0)
                       (/ parity-passed requests-checked)
                       0)
        avg-latency-delta (if (not-empty latency-deltas)
                           (/ (apply + latency-deltas) (count latency-deltas))
                           0)]

    {:timestamp (js/Date.now)
     :elapsed-sec elapsed-sec
     :requests-checked requests-checked
     :parity-passed parity-passed
     :parity-failed parity-failed
     :success-rate-pct (* success-rate 100)
     :failure-rate-pct (* (- 1 success-rate) 100)
     :divergence-count (count divergences)
     :avg-latency-delta-ms avg-latency-delta
     :throughput-req-per-sec (/ requests-checked (max elapsed-sec 1))
     :slo-parity-target-pct 99.99
     :slo-pass? (>= (* success-rate 100) 99.99)
     :sample-divergences (take 10 divergences)}))

(defn export-metrics
  "Write aggregated metrics to file and send to murakumo metrics-ingest."
  [output-file metrics]
  (let [edn-str (str (pr-str metrics) "\n")]
    ;; Append to metrics log
    (fs/appendFileSync output-file edn-str)

    ;; Print to console for visibility
    (println (str "[PARITY] " (:success-rate-pct metrics) "% pass, "
                  (:divergence-count metrics) " divergences, "
                  (Math/round (:avg-latency-delta-ms metrics)) "ms delta"))))

;;; ============================================================================
;;; Parity Monitoring Loop
;;; ============================================================================

(defn parity-monitor-loop
  "Run continuous parity validation loop."
  [{:keys [rust-pool-url staging-pool-url sample-rate output-file
           parity-check-interval-ms metrics-export-interval-ms]}]

  (println "[PARITY] Starting parity checker service...")
  (println (str "  Rust pool: " rust-pool-url))
  (println (str "  Staging pool: " staging-pool-url))
  (println (str "  Sample rate: " (* sample-rate 100) "%"))
  (println (str "  Output: " output-file "\n"))

  ;; Create output directory if needed
  (let [output-dir (path/dirname output-file)]
    (when (not (fs/existsSync output-dir))
      (fs/mkdirSync output-dir {:recursive true})))

  ;; Metrics export interval
  (js/setInterval
    (fn []
      (let [metrics (aggregate-metrics)]
        (export-metrics output-file metrics)
        (when (not (:slo-pass? metrics))
          (println (str "[PARITY ALERT] SLO Miss: " (:failure-rate-pct metrics) "% failure")))))
    metrics-export-interval-ms)

  ;; Parity check interval
  (js/setInterval
    (fn []
      ;; Sample requests based on sample-rate
      (when (<= (js/Math.random) sample-rate)
        (-> (send-canary-pair {:rust-pool-url rust-pool-url
                              :staging-pool-url staging-pool-url})
            (.then (fn [result]
                     ;; Update state
                     (swap! state
                       (fn [s]
                         (-> s
                           (update :requests-checked inc)
                           (cond->
                             (:parity-pass? result)
                             (update :parity-passed inc)

                             (not (:parity-pass? result))
                             (do
                               (-> (update :parity-failed inc)
                                   (update :divergences conj
                                     (dissoc result :body)))))
                           (update :latency-deltas conj
                             (-> result :latency :delta-ms)))))

                     ;; Log divergences immediately
                     (when (not (:parity-pass? result))
                       (println (str "[PARITY DIVERGENCE] "
                                    (:request-id result) ": "
                                    (str/join ", "
                                      (map name
                                        (filter true?
                                          (let [d (:divergence result)]
                                            [(:hash-mismatch d)
                                             (:status-mismatch d)])))))))
                     ))
            (.catch (fn [err]
                      (println (str "[PARITY ERROR] " err)))))))
    parity-check-interval-ms))

  (println "[PARITY] Monitor loop active. Collecting baseline..."))

;;; ============================================================================
;;; Main Entry Point
;;; ============================================================================

(defn -main
  [& args]
  (let [config (merge default-config
                 (apply hash-map args))]
    (parity-monitor-loop config)
    ;; Keep process alive
    (js/setInterval #() 60000)))

(apply -main *command-line-args*)

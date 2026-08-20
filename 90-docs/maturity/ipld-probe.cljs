(ns ipld-probe
  "What a block fetch costs on the IPLD premise, and whether this harness can
  be believed.

  The previous run of this file printed PASS for a PUT that returned 401. The
  `!ok` branch threw inside a `.then` whose promise was never returned, so the
  caller carried on as if the write had landed — the same swallowed failure
  this session spent a day finding in other people's code, sitting in the
  instrument measuring it. `put-block!` below returns every branch, and
  `harness-propagates-failure?` proves it against a request that must be
  refused, because an instrument's honesty is the one thing it cannot assert
  about itself afterwards.

  The measurement needs no credential: `GET /ipfs/*` is unauthenticated, and a
  404 costs the same round trip a hit does. That round trip IS the number that
  matters — a prolly tree lookup at depth D costs D of them, so per-block
  latency sets the floor under every query on this plane."
  (:require [clojure.string :as str]))

(def base "https://kotobase.net")
(def token (.. js/process -env -KOTOBASE_ARCHIVE_TOKEN))

(defn- ms [] (.now js/Date))

(defn- report [label ok? detail]
  (println (str (if ok? "PASS " "FAIL ") label (when detail (str "  " detail)))))

(defn put-block!
  "PUT a block, REJECTING on a non-2xx. Every branch returns its promise."
  [cid bytes]
  (-> (js/fetch (str base "/ipfs/" cid)
                #js {:method "PUT"
                     :headers #js {"authorization" (str "Bearer " token)
                                   "content-type" "application/octet-stream"}
                     :body bytes})
      (.then (fn [r]
               (if (.-ok r)
                 (js/Promise.resolve cid)
                 (-> (.text r)
                     (.then (fn [body]
                              (throw (js/Error.
                                      (str "PUT " (.-status r) " "
                                           (subs body 0 (min 120 (count body))))))))))))))

(defn- timed-get
  "Fetch a CID and return {:status :ms}. A 404 is a measurement, not an error:
  the round trip is what is being timed."
  [cid]
  (let [t0 (ms)]
    (-> (js/fetch (str base "/ipfs/" cid))
        (.then (fn [r] {:status (.-status r) :ms (- (ms) t0)})))))

(defn- absent-cid [i]
  ;; well-formed CIDv1 shape, deterministic, certain to be absent
  (str "bafkreie" (.padStart (str i) 3 "0")
       "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))

(defn- percentile [sorted p]
  (nth sorted (min (dec (count sorted)) (int (* p (count sorted))))))

(defn- harness-propagates-failure?
  "The instrument checking itself: a PUT that the server refuses must reject,
  not resolve. Without a valid token every PUT is a 401, which makes this a
  cheap and unambiguous test of the exact bug that invalidated the last run."
  []
  (-> (put-block! (absent-cid 999) (js/Uint8Array. #js [1 2 3]))
      (.then (fn [_] false))
      (.catch (fn [_] true))))

(defn- measure-fetch-cost []
  (-> (js/Promise.all (clj->js (map #(timed-get (absent-cid %)) (range 20))))
      (.then (fn [rs]
               (let [rs (js->clj rs :keywordize-keys true)
                     times (vec (sort (map :ms rs)))
                     statuses (frequencies (map :status rs))
                     p50 (percentile times 0.5)]
                 (println (str "20 block GETs  statuses=" (pr-str statuses)))
                 (println (str "  p50 " p50 "ms  p95 " (percentile times 0.95)
                               "ms  min " (first times) "ms  max " (peek times) "ms"))
                 (report "absent block is a clean 404, not a hang or a 5xx"
                         (= #{404} (set (keys statuses))) nil)
                 (println)
                 (println "implied query floor, one block per level:")
                 (doseq [d [2 3 4]]
                   (println (str "  depth " d " lookup >= " (* d p50) "ms")))
                 {:p50 p50 :p95 (percentile times 0.95) :statuses statuses})))))

(defn ^:export main [& _]
  (println (str "block-fetch cost on " base "  (GET /ipfs/* is unauthenticated)"))
  (-> (harness-propagates-failure?)
      (.then (fn [honest?]
               (report "harness propagates a failed write instead of returning success"
                       honest? (if token "token present" "no token, so the PUT must reject"))
               honest?))
      (.then (fn [honest?]
               (if honest?
                 (measure-fetch-cost)
                 (do (println)
                     (println "harness is not trustworthy; recording no numbers")
                     (set! (.-exitCode js/process) 1)
                     nil))))
      (.then (fn [r] (when r (println) (println "result" (pr-str r)))))
      (.catch (fn [e]
                (println "PROBE ERROR:" (or (.-message e) (str e)))
                (set! (.-exitCode js/process) 1)))))

(main)

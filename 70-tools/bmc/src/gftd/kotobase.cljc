(ns gftd.kotobase
  "net-kotobase dual-write for BMC canvas ledger events (ADR-2607180400).

   Git `canvas-ledger.edn` remains SSoT for fold/score. Every append is also
   projected as entity maps suitable for ai.gftd.apps.kotobase.datomic.transact
   (tx_edn entity-map shape, NOT :db/add triples — see kotobase-server
   handler.cljc tx-edn->quads).

   I/O is injected: dual-write! takes an `http-post!` or a platform write-fn.
   Fail-open: callers never block the local ledger path on remote failure."
  (:require [clojure.string :as str]))

;; `backend.kotobase.net` is deprecated and no longer resolves in DNS -- a
;; dual-write against it failed in 1.4ms, before TCP. The live datom plane is
;; kotobase.net itself, which is also what kotobase-client documents
;; (client.cljs) and what its live E2E suite actually runs against
;; (live_e2e.cljs). Verified 2026-08-19 by overriding BMC_KOTOBASE_ENDPOINT:
;;   {:ok true, :db "portfolio-bmc-ledger", :events 1, :datom_count 7}
;; The XRPC path was never wrong -- only the host.
(def default-endpoint "https://kotobase.net")
(def default-operator-did "did:web:kotobase.net")
(def default-db-name "portfolio-bmc-ledger")
(def datomic-ns "ai.gftd.apps.kotobase.datomic")

(defn canonical
  "A printed form that depends on a value's CONTENT, never on how it was built.

  pr-str cannot be used directly: map and set iteration order is unspecified, so
  {:a 1 :b 2} and {:b 2 :a 1} print differently and would digest differently --
  the same event would get two ids, which is the exact failure this is meant to
  prevent. Its own test caught that. Maps and sets are sorted here; vectors and
  lists keep their order, because there the order IS the content."
  [v]
  (cond
    (map? v) (str "{" (str/join "," (map (fn [[k val]] (str (canonical k) " " (canonical val)))
                                         (sort-by (comp pr-str first) (seq v)))) "}")
    (set? v) (str "#{" (str/join "," (sort (map canonical v))) "}")
    (sequential? v) (str "[" (str/join "," (map canonical v)) "]")
    :else (pr-str v)))

(defn digest
  "A stable non-cryptographic digest of any value.

  djb2 over `canonical`, kept below 2^53 at every step (h < 2^32, so h*33 <
  1.5e11) so a JS double and a JVM long compute the SAME number. `hash` cannot
  be used here: it is runtime-specific, and an id that changes between clj and
  cljs is not an id. Not a security primitive -- it only has to be
  deterministic."
  [v]
  (let [s (canonical v)]
    (loop [i 0 h 5381]
      (if (>= i (count s))
        h
        (recur (inc i)
               (mod (+ (* h 33)
                       #?(:clj  (int (.charAt ^String s i))
                          :cljs (.charCodeAt s i)))
                    4294967296))))))

(defn- seq-counts
  "{seq → how many events carry it}, so a colliding seq can be told from a
  unique one without re-reading the file."
  [events]
  (reduce (fn [m e] (if-let [s (:event/seq e)] (update m s (fnil inc 0)) m)) {} events))

(defn event->entity
  "One stamped ledger event → one entity map for kotobase tx_edn.

  :db/id is \"bmc.event/<seq>\" while that seq belongs to ONE event, so
  re-asserting the same ledger is still a cardinality-one upsert and the id is
  the readable thing it always was.

  When a seq carries more than one event the id becomes
  \"bmc.event/<seq>-<digest>\". It has to: gftd.ledger/append! stamps
  (inc (max seq-of-the-local-file)) with nothing held between the read and the
  write, so two loops -- or two checkouts whose appends git later merges --
  hand the same number to different events. Measured 2026-08-20 on the
  committed canvas-ledger: 333 seq values carried more than one event, and 353
  events had no id of their own. Under cardinality-one the second does not land
  beside the first, it REPLACES it.

  This does not stop new collisions; that is the writer's bug, watched by
  scripts/verify-ledger-seq-collision.cljs. It stops a collision from silently
  eating an event when the projection runs."
  ([e] (event->entity e nil))
  ([e counts]
   (let [seq-n (:event/seq e)
         collides? (and seq-n (> (get counts seq-n 1) 1))
         eid (str "bmc.event/"
                  (cond
                    (nil? seq-n) (digest e)
                    collides? (str seq-n "-" (digest e))
                    :else seq-n))]
    (cond-> {:db/id eid
             :bmc.event/type (str (or (:event/type e) :unknown))
             :bmc.event/actor (str (or (:event/actor e) "unknown"))
             :bmc.event/at (str (or (:event/at e) ""))
             :bmc.event/source "portfolio-bmc"}
      seq-n (assoc :bmc.event/seq seq-n)
      (:event/tick e) (assoc :bmc.event/tick (:event/tick e))
      (:canvas/id e) (assoc :bmc.event/canvas-id (str (:canvas/id e)))
      (:hyp/id e) (assoc :bmc.event/hyp-id (str (:hyp/id e)))
      (contains? e :event/value)
      (assoc :bmc.event/value
             (let [v (:event/value e)]
               (if (string? v) v (pr-str v))))
      (:event/reason e) (assoc :bmc.event/reason (str (:event/reason e)))
      (:event/evidence e) (assoc :bmc.event/evidence (str (:event/evidence e)))))))

(defn events->tx-data
  "Vector of stamped events → tx_edn entity-map vector.

  Counts seq values across the WHOLE batch first: whether an id is ambiguous is
  a property of the corpus, not of one event, so event->entity cannot see it
  alone."
  [events]
  (let [counts (seq-counts events)]
    (mapv #(event->entity % counts) events)))

(defn events->tx-edn [events]
  (pr-str (events->tx-data events)))

(defn transact-url
  ([endpoint] (transact-url endpoint "transact"))
  ([endpoint method]
   (str (str/replace (or endpoint default-endpoint) #"/+$" "")
        "/xrpc/" datomic-ns "." method)))

(defn build-transact-body
  "JSON-ready body map for datomic.transact. Auth headers are separate."
  [db-name tx-edn]
  {:db_name (or db-name default-db-name)
   :tx_edn tx-edn})

(defn dual-write-result
  "Normalize remote outcome → {:ok? bool :status :detail}."
  [{:keys [status body ok? error]}]
  (cond
    error {:ok? false :status :error :detail (str error)}
    (and status (>= status 200) (< status 300)
         (or (nil? ok?) (not (false? ok?))))
    {:ok? true :status :ok :detail (str "HTTP " status)}
    :else {:ok? false :status (or status :failed)
           :detail (str (or body ""))}))

(defn- json-body
  "Encode fixed-shape {:db_name :tx_edn} without a heavy JSON dep on JVM."
  [m]
  #?(:clj  (str "{\"db_name\":"
                (pr-str (str (:db_name m)))
                ",\"tx_edn\":"
                (pr-str (str (:tx_edn m)))
                "}")
     :cljs (js/JSON.stringify (clj->js m))))

(defn dual-write-via-http!
  "POST tx_edn with optional Bearer/CACAO. `http-post!` → {:status :body}.
   opts: :endpoint :db-name :token :cacao :did
   Returns dual-write-result. Never throws."
  [http-post! events {:keys [endpoint db-name token cacao did]}]
  (try
    (let [tx-edn (events->tx-edn events)
          url (transact-url (or endpoint default-endpoint))
          body (json-body (build-transact-body db-name tx-edn))
          headers (cond-> {"Content-Type" "application/json"
                           "User-Agent" "gftd-bmc/0.1 (kotobase dual-write)"}
                    token (assoc "Authorization" (str "Bearer " token))
                    cacao (assoc "Authorization" (str "CACAO " cacao)
                                 "x-kotoba-did" (or did "")))
          res (http-post! {:url url :headers headers :body body})
          parsed (try
                   #?(:clj  nil
                      :cljs (when (:body res)
                              (js->clj (js/JSON.parse (str (:body res)))
                                       :keywordize-keys true)))
                   (catch #?(:clj Exception :cljs :default) _ nil))]
      (dual-write-result
       (merge res
              {:ok? (if (map? parsed) (get parsed :ok (get parsed "ok")) nil)
               :body (or (when (map? parsed) (pr-str parsed)) (:body res))})))
    (catch #?(:clj Exception :cljs :default) e
      (dual-write-result {:error #?(:clj (.getMessage e) :cljs (or (.-message e) (str e)))}))))

(defn enabled?
  "Dual-write on unless BMC_KOTOBASE_DUAL_WRITE is 0/false/off, or flag disables."
  [env-get flags]
  (let [env (when env-get (env-get "BMC_KOTOBASE_DUAL_WRITE"))
        flag-off (or (:no-kotobase flags) (false? (:kotobase flags)))]
    (and (not flag-off)
         (not (contains? #{"0" "false" "off" "no"} (some-> env str/lower-case))))))

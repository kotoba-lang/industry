#!/usr/bin/env nbb
;; kotobase-dual-write.cljs — push stamped BMC ledger events to kotobase.net
;; as entity maps (ADR-2607180400). Invoked by gftd.cli after local ledger
;; append (spawnSync, fail-open).
;;
;; Usage (from superproject root):
;;   NODE_PATH=orgs/kotoba-lang/kotobase-client/node_modules \
;;   nbb --classpath "orgs/kotoba-lang/kotobase-client/src:70-tools/bmc/src:." \
;;     70-tools/bmc/bin/kotobase-dual-write.cljs /tmp/events.edn
;;
;; Identity: 70-tools/bmc/.bmc-kotobase-identity.hex (gitignored). First run
;; mints a new Ed25519 seed; losing it orphans the portfolio-bmc-ledger graph.
(require '["node:crypto" :as node-crypto]
         '["node:fs" :as fs]
         '["node:path" :as path]
         '[cljs.reader :as edn]
         '[clojure.string :as str]
         '[gftd.kotobase :as kbase]
         '[kotobase.client :as client])

(def argv (vec (js->clj (.-argv js/process))))

(defn find-script-path []
  (or (some #(when (and (string? %)
                        (str/includes? % "kotobase-dual-write"))
               %)
            argv)
      (path/join (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
          ;; legacy; `gftd` is retired (manifest/gftd-retirement.edn)
          (aget (.-env js/process) "GFTD_ROOT") ".")
                 "70-tools/bmc/bin/kotobase-dual-write.cljs")))

(def script-path (find-script-path))
(def script-dir (path/dirname script-path))
(def identity-path (path/join script-dir ".." ".bmc-kotobase-identity.hex"))
(def db-name (or (aget (.-env js/process) "BMC_KOTOBASE_DB")
                 kbase/default-db-name))
(def endpoint (or (aget (.-env js/process) "BMC_KOTOBASE_ENDPOINT")
                  kbase/default-endpoint))

(defn load-or-create-identity! []
  (if (fs/existsSync identity-path)
    (js/Uint8Array.from (js/Buffer.from (str/trim (fs/readFileSync identity-path "utf8")) "hex"))
    (let [sk (js/Uint8Array. (.randomBytes node-crypto 32))]
      ;; nbb cannot parse 0o600 — decimal 384 = rw-------
      (fs/writeFileSync identity-path (.toString (js/Buffer.from sk) "hex") #js {:mode 384})
      (js/console.error "Minted NEW BMC kotobase identity at" identity-path
                        "— back this up; losing it orphans graph" db-name)
      sk)))

(defn events-arg
  "Last path-like argv entry that is not the dual-write script / nbb binary."
  []
  (or (last (filter (fn [a]
                      (and (string? a)
                           (not (str/starts-with? a "-"))
                           (not (str/includes? a "kotobase-dual-write"))
                           (not (re-find #"/(nbb|node)(\.js)?$" a))
                           (not (str/includes? a "node_modules/nbb"))))
                    argv))
      (throw (js/Error. "usage: nbb ... kotobase-dual-write.cljs <events.edn|->"))))

(defn read-payload
  "-> {:events [...] :counts {seq → n}}

  The payload carries seq counts taken over the WHOLE ledger, because the events
  in it are a slice: whether a seq is ambiguous cannot be decided from the slice
  (gftd.kotobase/events->tx-data says why). `nil` counts means the caller did not
  send any, and the projection falls back to counting the slice — the old
  behaviour, kept so an older CLI writing a bare vector still works."
  [arg]
  (let [raw (if (= arg "-")
              (fs/readFileSync 0 "utf8")
              (fs/readFileSync arg "utf8"))
        v (edn/read-string raw)]
    (cond
      (vector? v) {:events v :counts nil}
      (and (map? v) (contains? v :bmc/events))
      {:events (:bmc/events v) :counts (:bmc/seq-counts v)}
      (map? v) {:events [v] :counts nil}
      :else (throw (js/Error. (str "events file must be EDN vector/map, got "
                                   (type v)))))))

(defn -main []
  (let [arg (events-arg)
        {:keys [events counts]} (read-payload arg)
        sk (load-or-create-identity!)
        c (client/make-client {:endpoint endpoint
                               :operator-did kbase/default-operator-did
                               :secret-key sk})
        tx-edn (if counts
                 (kbase/events->tx-edn events counts)
                 (kbase/events->tx-edn events))]
    (js/console.error "kotobase dual-write: did=" (:did c)
                      "db=" db-name
                      "events=" (count events)
                      "from=" arg)
    (-> (client/transact c db-name tx-edn {:retry? true})
        (.then (fn [^js res]
                 (println (pr-str {:ok true
                                   :did (:did c)
                                   :db db-name
                                   :events (count events)
                                   :datom_count (.-datom_count res)}))
                 (js/process.exit 0)))
        (.catch (fn [e]
                  (js/console.error "kotobase dual-write FAILED:" (.-message e))
                  (println (pr-str {:ok false :error (.-message e)}))
                  (js/process.exit 1))))))

(-main)

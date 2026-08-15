#!/usr/bin/env nbb
(ns verify-kotobase-persistence-policy
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(defn flag [n default]
  (let [i (.indexOf args n)]
    (if (neg? i) default (nth args (inc i) default))))
(def root (path/resolve (flag "--root" ".")))
(def failures (atom []))
(defn fail! [s] (swap! failures conj s))
(defn read-text [p]
  (let [f (path/join root p)]
    (if (fs/existsSync f) (str (fs/readFileSync f "utf8"))
        (do (fail! (str "missing " p)) ""))))
(defn read-edn [p]
  (try (reader/read-string (read-text p))
       (catch :default e (fail! (str "invalid EDN " p ": " (ex-message e))) nil)))

(let [rules (read-edn "manifest/repository-rules.edn")
      adr-path "90-docs/adr/2608159100-kotobase-net-default-durable-boundary.edn"
      adr (first (read-edn adr-path))
      agents (read-text "AGENTS.md")
      p (get-in rules [:workspace-policies :live-service-durable-data])
      exact {:policy/authority adr-path
             :policy/scope :runtime-durable-plane
             :policy/authority-origin "https://kotobase.net"
             :policy/default-api-origin "https://kotobase.net"
             :policy/immutable-block-path "/ipld/:cid"
             :policy/datom-path-prefix "/api/"
             :policy/large-object-entry-origin "https://kotobase.net"}
      required #{:cid-verified-read :cid-verified-write :fresh-cacao-nonce
                 :recoverable-without-coordination-store}
      forbidden #{:application-direct-r2-binding :provider-origin-as-production-premise
                  :deprecated-origin-alias-in-new-config
                  :query-implementation-name-as-capability
                  :durable-object-as-data-authority :silent-production-fallback}]
  (when-not (map? p) (fail! "missing :workspace-policies :live-service-durable-data"))
  (doseq [[k expected] exact]
    (when-not (= expected (get p k))
      (fail! (str k " expected " (pr-str expected) ", got " (pr-str (get p k))))))
  (when-not (= required (:policy/requires p))
    (fail! (str ":policy/requires drift: " (pr-str (:policy/requires p)))))
  (when-not (= forbidden (:policy/forbids p))
    (fail! (str ":policy/forbids drift: " (pr-str (:policy/forbids p)))))
  (when-not (= {:datomic "https://datomic.kotobase.net"
                :sparql "https://sparql.kotobase.net"
                :cypher "https://cypher.kotobase.net"
                :gremlin "https://gremlin.kotobase.net"
                :graphql "https://graphql.kotobase.net"
                :s3 "https://s3.kotobase.net"
                :git "https://git.kotobase.net"
                :atproto "https://atproto.kotobase.net"
                :pinning "https://pinning.kotobase.net"}
               (:policy/capability-origins p))
    (fail! ":policy/capability-origins drift"))
  (when-not (= {:datoms "https://datoms.kotobase.net"}
               (:policy/internal-capability-origins p))
    (fail! ":policy/internal-capability-origins drift"))
  (when-not (= #{:datalog :sql :cypher :sparql :graphql :gremlin}
               (:policy/query-dialects p))
    (fail! ":policy/query-dialects drift"))
  (when-not (= #{"https://graph-database.kotobase.net"
                 "https://backend.kotobase.net"
                 "https://graphdb.kotobase.net"}
               (:policy/deprecated-origin-aliases p))
    (fail! ":policy/deprecated-origin-aliases drift"))
  (when-not (= #{:durable-object :d1 :kv} (:policy/coordination-only p))
    (fail! ":policy/coordination-only must be exactly durable-object/d1/kv"))
  (when-not (= "ADR-2608159100" (:adr/id adr)) (fail! "ADR id mismatch"))
  (when-not (= "accepted" (:adr/status adr)) (fail! "ADR is not accepted"))
  (doseq [needle ["live service の永続化境界は `kotobase.net`"
                  "PUT/GET https://kotobase.net/ipld/:cid"
                  "sparql.kotobase.net/repositories/default"
                  "SQL は独立 origin ではなく query dialect"
                  ":workspace-policies :live-service-durable-data"
                  "root-kotobase-persistence-policy"]]
    (when-not (str/includes? agents needle)
      (fail! (str "AGENTS.md missing policy marker " (pr-str needle)))))
  (when-not (str/includes? (:adr/body adr) "production live gate")
    (fail! "ADR does not bind the decision to production live verification")))

(if (seq @failures)
  (do (println "kotobase-persistence-policy: FAIL")
      (doseq [f @failures] (println " -" f))
      (js/process.exit 1))
  (println "kotobase-persistence-policy: OK — apex authority, capability origins, IPLD blocks, datom metadata, coordination-only stores"))

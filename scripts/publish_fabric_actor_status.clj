;; pulse 文・handle・Keychain service 名・終了状態は **この script が持たない** ——
;; 正本は `scripts/kotoba/fabric_actor_core.kotoba`、参照実装は
;; `gftd.fabric-actor-core`（ADR-2608155000）。ここに残るのは HTTP・Keychain 読み出し・
;; CACAO 鋳造・receipt の JSON 書き出しという effect だけ。
(ns publish-fabric-actor-status
  (:require [cacao.core :as cacao]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [gftd.fabric-actor-core :as core])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Instant]
           [java.util Base64 UUID]))

(def pds core/pds)
(def products core/products)
(def client (HttpClient/newHttpClient))

;; superproject root は **環境変数で受ける**（既定 "."）。cwd 決め打ちにできない ——
;; この repo の root にある `deps.edn` は Clojure の deps マニフェストではなく
;; workspace メタデータ（datom の vector）で、`clojure` を root で起動すると
;; tools.deps がそれを読もうとして classpath 構築ごと落ちる。実測: launchd の
;; `com.junkawasaki.fabric-actor-cadence` は 2026-08-10 以降この
;; NullPointerException で毎週落ちていた（/tmp/fabric-actor-cadence.err）。
;; 起動側（`scripts/publish-fabric-actor-status`）は repo の外から `clojure` を
;; 呼び、ここに root を渡す。`90-docs/pricing-intelligence/query.clj` と
;; `scripts/mk1_cfd.clj` が先に同じ回避をしている。
(def root (or (System/getenv "FABRIC_ROOT") "."))
(def receipt-path (str root "/80-data/system/artificial-organism-actor-cadence.json"))

(defn post! [nsid body token]
  (let [b (doto (HttpRequest/newBuilder (URI/create (str pds "/xrpc/" nsid)))
            (.header "content-type" "application/json"))
        _ (when token (.header b "authorization" (str "Bearer " token)))
        response (.send client (-> b (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body))) .build)
                        (HttpResponse$BodyHandlers/ofString))
        value (json/read-str (.body response) :key-fn keyword)]
    (when (or (>= (.statusCode response) 400) (:error value))
      (throw (ex-info (str nsid " rejected") {:status (.statusCode response)
                                               :error (:error value) :message (:message value)})))
    value))

(defn actor-seed [handle]
  (let [account (or (System/getenv "USER") "fabric-operator")
        result (shell/sh "security" "find-generic-password" "-a" account
                         "-s" (core/seed-service handle) "-w")]
    (when-not (zero? (:exit result))
      (throw (ex-info "actor seed absent from Keychain" {:handle handle})))
    (.decode (Base64/getDecoder) (.trim (:out result)))))

(defn session-cacao [seed]
  (:cacao-b64
   (cacao/mint {:seed seed :aud "did:web:pds.aozora.app"
                :iat (str (Instant/now)) :exp (str (.plusSeconds (Instant/now) 300))
                :nonce (str (UUID/randomUUID)) :domain "pds.aozora.app"
                :resources ["atproto://account/session"]})))

(defn publish! [product health]
  (let [handle (core/handle product)
        session (post! "com.atproto.server.createSession" {:cacao (session-cacao (actor-seed handle))} nil)
        text (core/pulse-for product health)
        result (post! "com.atproto.repo.createRecord"
                      {:repo (:did session) :collection "app.bsky.feed.post"
                       :record {:$type "app.bsky.feed.post" :text text :createdAt (str (Instant/now))}}
                      (:accessJwt session))]
    {:product product :handle handle :did (:did session) :uri (:uri result) :cid (:cid result)}))

(defn -main [& _]
  (let [health (json/read-str (slurp "https://murakumo.cloud/health/fabric.json") :key-fn keyword)
        results (mapv (fn [p]
                        (try (publish! p health)
                             (catch Exception e {:product p :error (or (:error (ex-data e)) (.getMessage e))
                                                 :message (:message (ex-data e))})))
                      products)
        receipt {:schema 1 :publishedAt (str (Instant/now)) :results results}
        ;; 0 件 = exit 2。product 一覧が空になった週に「全部成功」で緑を出さない。
        code (core/exit-code (count results) (count (filter :error results)))]
    (spit receipt-path
          (str (json/write-str receipt :escape-slash false :indent true) "\n"))
    (println (json/write-str receipt :escape-slash false))
    (when (pos? code) (System/exit code))))

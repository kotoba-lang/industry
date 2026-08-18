;; 名乗り（handle / displayName / description / 投稿文）と Keychain の service 名、
;; および終了状態は **この script が持たない** —— 正本は
;; `scripts/kotoba/fabric_actor_core.kotoba`、参照実装は
;; `gftd.fabric-actor-core`（ADR-2608155000）。ここに残るのは HTTP・CACAO 鋳造・
;; Keychain 書き込み・receipt の JSON 書き出しという effect だけ。
(ns register-fabric-actors
  (:require [cacao.core :as cacao]
            [clojure.data.json :as json]
            [gftd.fabric-actor-core :as core])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.security SecureRandom]
           [java.time Instant]
           [java.util Base64 UUID]))

(def pds core/pds)
(def products core/products)
(def client (HttpClient/newHttpClient))

;; superproject root は環境変数で受ける（既定 "."）。理由は
;; `publish_fabric_actor_status.clj` の同じ def に書いてある —— root の `deps.edn`
;; は Clojure の deps マニフェストではないので、`clojure` を root で起動できない。
(def root (or (System/getenv "FABRIC_ROOT") "."))
(def receipt-path (str root "/80-data/system/artificial-organism-actors.json"))

(defn post! [nsid body token]
  (let [builder (doto (HttpRequest/newBuilder (URI/create (str pds "/xrpc/" nsid)))
                  (.header "content-type" "application/json"))
        _ (when token (.header builder "authorization" (str "Bearer " token)))
        req (-> builder (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body))) .build)
        response (.send client req (HttpResponse$BodyHandlers/ofString))
        value (json/read-str (.body response) :key-fn keyword)]
    (when (or (>= (.statusCode response) 400) (:error value))
      (throw (ex-info (str nsid " rejected") {:status (.statusCode response)
                                               :error (:error value) :message (:message value)})))
    value))

(defn seed []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    bytes))
(defn b64 [bytes] (.encodeToString (Base64/getEncoder) bytes))
(defn cacao-for [seed]
  (:cacao-b64 (cacao/mint {:seed seed :aud "did:web:pds.aozora.app"
                           :iat (str (Instant/now))
                           :exp (str (.plusSeconds (Instant/now) 300))
                           :nonce (str (UUID/randomUUID))
                           :resources ["atproto://account/session"]
                           :domain "pds.aozora.app"})))

(defn keychain! [handle seed]
  (let [account (or (System/getenv "USER") "fabric-operator")]
    (.. (ProcessBuilder. ["security" "add-generic-password" "-U" "-a" account
                          "-s" (core/seed-service handle) "-w" (b64 seed)])
        inheritIO start waitFor)))

(defn register! [product]
  (let [handle (core/handle product)
        seed (seed)
        cacao (cacao-for seed)
        session (post! "com.atproto.server.createAccount" {:handle handle :cacao cacao} nil)
        did (:did session)
        token (:accessJwt session)
        now (str (Instant/now))]
    (keychain! handle seed)
    (post! "com.atproto.repo.putRecord"
           {:repo did :collection "app.bsky.actor.profile" :rkey "self"
            :record {:$type "app.bsky.actor.profile" :displayName (core/display-name product)
                     :description (core/description product)}}
           token)
    (let [post (post! "com.atproto.repo.createRecord"
                      {:repo did :collection "app.bsky.feed.post"
                       :record {:$type "app.bsky.feed.post"
                                :text (core/registration-text product)
                                :createdAt now}}
                      token)]
      {:product product :handle handle :did did :state "registered"
       :postUri (:uri post) :postCid (:cid post)})))

(defn -main [& args]
  (let [selected (if (seq args) (vec args) products)
        fresh (mapv (fn [product]
                        (try (register! product)
                             (catch Exception e
                               {:product product :state "failed"
                                :error (or (:error (ex-data e)) (.getMessage e))
                                :message (:message (ex-data e))})))
                      selected)
        old (try (:results (json/read-str (slurp receipt-path) :key-fn keyword))
                 (catch Exception _ []))
        replaced (set selected)
        results (vec (concat (remove #(contains? replaced (:product %)) old) fresh))
        receipt {:schema 1 :generatedAt (str (Instant/now)) :pds pds :results results}
        ;; 終了状態は fresh（今回試した分）で決める。receipt に残っている
        ;; 過去の結果まで見ると、直っていない別 product のせいで今回の run が
        ;; 永遠に赤くなる。0 件 = exit 2（試していない ≠ 全部成功）。
        code (core/exit-code (count fresh) (count (filter #(= "failed" (:state %)) fresh)))]
    (spit receipt-path (str (json/write-str receipt :escape-slash false :indent true) "\n"))
    (println (json/write-str receipt :escape-slash false))
    (when (pos? code) (System/exit code))))

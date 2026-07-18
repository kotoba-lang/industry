(ns register-fabric-actors
  (:require [cacao.core :as cacao]
            [clojure.data.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.security SecureRandom]
           [java.time Instant]
           [java.util Base64 UUID]))

(def pds "https://pds.aozora.app")
(def products ["itonami" "manimani" "isekai" "shinshi" "yukkuri" "dougaka" "animeka" "mangaka" "babiniku"])
(def client (HttpClient/newHttpClient))

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
                          "-s" (str "aozora.app/actor-seed/" handle) "-w" (b64 seed)])
        inheritIO start waitFor)))

(defn register! [product]
  (let [handle (str product "-organism.aozora.app")
        seed (seed)
        cacao (cacao-for seed)
        session (post! "com.atproto.server.createAccount" {:handle handle :cacao cacao} nil)
        did (:did session)
        token (:accessJwt session)
        now (str (Instant/now))]
    (keychain! handle seed)
    (post! "com.atproto.repo.putRecord"
           {:repo did :collection "app.bsky.actor.profile" :rkey "self"
            :record {:$type "app.bsky.actor.profile" :displayName (str product " organism")
                     :description (str "Autonomous " product " actor. Kotoba-policy governed, capability-bound and auditable.")}}
           token)
    (let [post (post! "com.atproto.repo.createRecord"
                      {:repo did :collection "app.bsky.feed.post"
                       :record {:$type "app.bsky.feed.post"
                                :text (str product " organism registered. Kotoba → Kototama WASM → Murakumo → Kotobase evidence. Autonomous activity remains bounded and auditable.")
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
        old (try (:results (json/read-str (slurp "80-data/system/artificial-organism-actors.json") :key-fn keyword))
                 (catch Exception _ []))
        replaced (set selected)
        results (vec (concat (remove #(contains? replaced (:product %)) old) fresh))
        receipt {:schema 1 :generatedAt (str (Instant/now)) :pds pds :results results}]
    (spit "80-data/system/artificial-organism-actors.json" (str (json/write-str receipt :escape-slash false :indent true) "\n"))
    (println (json/write-str receipt :escape-slash false))
    (when (some #(= "failed" (:state %)) results) (System/exit 1))))

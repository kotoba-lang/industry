(ns publish-fabric-actor-status
  (:require [cacao.core :as cacao]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Instant]
           [java.util Base64 UUID]))

(def pds "https://pds.aozora.app")
(def products ["itonami" "manimani" "isekai" "shinshi" "yukkuri" "dougaka" "animeka" "mangaka" "babiniku"])
(def client (HttpClient/newHttpClient))

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
                         "-s" (str "aozora.app/actor-seed/" handle) "-w")]
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
  (let [handle (str product "-organism.aozora.app")
        session (post! "com.atproto.server.createSession" {:cacao (session-cacao (actor-seed handle))} nil)
        summary (:summary health)
        activity (:activity health)
        text (str product " organism weekly pulse — fabric=" (:status health)
                  ", runtime=" (get-in activity [:runtime :healthy]) "/" (get-in activity [:runtime :total])
                  ", CI=" (get-in activity [:ci :status])
                  ", open checkout WIP=" (:dirty summary)
                  ". Evidence: https://murakumo.cloud/health/fabric.json")
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
        receipt {:schema 1 :publishedAt (str (Instant/now)) :results results}]
    (spit "80-data/system/artificial-organism-actor-cadence.json"
          (str (json/write-str receipt :escape-slash false :indent true) "\n"))
    (println (json/write-str receipt :escape-slash false))
    (when (some :error results) (System/exit 1))))

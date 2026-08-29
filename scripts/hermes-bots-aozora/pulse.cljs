(ns pulse
  "Daily run receipt: each Hermes cron bot posts one status line to its own
   aozora.app account. Deterministic — no model anywhere in this path; the
   text is built from jobs.json fields the scheduler itself wrote.

   Run via aozora_pulse.py (the Hermes shim builds the classpath), or by hand
   with the classpath one-liner in README.md.

   A bot with no Keychain seed is reported as UNREGISTERED (run register.cljs),
   not silently skipped. An upstream PDS rejection prints BLOCKED and exits 0 —
   the job must run and be told it is blind, not be an incident every day
   while the platform blocker (ADR-2608291500) stands."
  (:require [aozora-core :as core]
            [cacao.core :as cacao]
            [clojure.string :as str]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def jobs-path (path/join (os/homedir) ".hermes" "cron" "jobs.json"))
(def user (or (aget (.-env js/process) "USER") "fabric-operator"))

(defn jobs []
  (try (let [parsed (js->clj (js/JSON.parse (fs/readFileSync jobs-path "utf8")))]
         (if (map? parsed) (get parsed "jobs") parsed))
       (catch :default e
         (println "REFUSED: cannot read" jobs-path "-" (.-message e))
         nil)))

(defn seed [handle]
  (let [r (cp/spawnSync "security"
                        (clj->js ["find-generic-password" "-a" user
                                  "-s" (core/seed-service handle) "-w"])
                        #js {:encoding "utf8"})]
    (when (zero? (.-status r))
      (js/Uint8Array.from (js/Buffer.from (str/trim (.-stdout r)) "base64")))))

(defn mint [s]
  (let [now (js/Date.)]
    (:cacao-b64 (cacao/mint {:seed s :aud core/pds-aud :domain core/pds-domain
                             :iat (.toISOString now)
                             :exp (.toISOString (js/Date. (+ (.getTime now) 300000)))
                             :nonce (str (random-uuid))
                             :resources ["atproto://account/session"]}))))

(defn xrpc [nsid body token]
  (-> (js/fetch (str core/pds "/xrpc/" nsid)
                (clj->js {:method "POST"
                          :headers (cond-> {"content-type" "application/json"}
                                     token (assoc "authorization" (str "Bearer " token)))
                          :body (js/JSON.stringify (clj->js body))}))
      (.then (fn [resp]
               (.then (.text resp)
                      (fn [text]
                        (let [v (try (js->clj (js/JSON.parse text)) (catch :default _ {}))]
                          {:status (.-status resp) :body v
                           :ok (and (< (.-status resp) 400) (not (get v "error")))})))))))

(defn pulse-bot! [job]
  (let [job-name (get job "name")
        handle (core/handle job-name)]
    (if-let [s (seed handle)]
      (-> (xrpc "com.atproto.server.createSession" {:cacao (mint s)} nil)
          (.then (fn [{:keys [ok body status]}]
                   (if-not ok
                     {:name job-name :state "BLOCKED"
                      :error (str "createSession " status " " (get body "error"))}
                     (-> (xrpc "com.atproto.repo.createRecord"
                               {:repo (get body "did") :collection "app.bsky.feed.post"
                                :record {:$type "app.bsky.feed.post"
                                         :text (core/pulse-text job-name job)
                                         :createdAt (.toISOString (js/Date.))}}
                               (get body "accessJwt"))
                         (.then (fn [{:keys [ok body status]}]
                                  (if ok
                                    {:name job-name :state "posted" :uri (get body "uri")}
                                    {:name job-name :state "BLOCKED"
                                     :error (str "createRecord " status " " (get body "error")
                                                 ": " (get body "message"))})))))))
          (.catch (fn [e] {:name job-name :state "LOCAL-FAILURE" :error (.-message e)})))
      (js/Promise.resolve {:name job-name :state "UNREGISTERED"
                           :error "no Keychain seed — run register.cljs"}))))

(defn -main []
  (let [all (jobs)]
    (if (nil? all)
      (.exit js/process 2)
      (let [selected (->> all
                          (filter #(get % "enabled" true))
                          (remove #(core/opted-out? (get % "name")))
                          vec)]
        (-> (reduce (fn [p j] (.then p (fn [acc] (.then (pulse-bot! j) #(conj acc %)))))
                    (js/Promise.resolve []) selected)
            (.then (fn [results]
                     (doseq [{:keys [name state uri error]} results]
                       (println (str state "\t" name (when uri (str "\t" uri))
                                     (when error (str "\t" error)))))
                     (let [blocked (count (filter #(= "BLOCKED" (:state %)) results))]
                       (when (pos? blocked)
                         (println (str "BLOCKED " blocked "/" (count results)
                                       " — PDS data plane rejects writes; see ADR-2608291500."))))
                     (.exit js/process
                            (core/exit-code (count results)
                                            (count (filter #(contains? #{"LOCAL-FAILURE" "UNREGISTERED"} (:state %))
                                                           results)))))))))))

(-main)

(ns register
  "Give every Hermes cron bot an aozora.app account (did:key + CACAO).

   Order per bot (yukkuri's account-bootstrap-order, learned the hard way):
     1. ensure an Ed25519 seed in the Keychain (generate BEFORE any network
        call — an account whose seed was never persisted is unrecoverable);
     2. createSession first; only when that fails, createAccount
        (re-running createAccount for a registered DID corrupted accounts,
        orgs/cloud-itonami/yukkuri clj/src/yukkuri/aozora.cljc:58);
     3. on a fresh account, putRecord the profile + one registration post.

   Run from the superproject root (classpath: this dir + the four sibling
   .cljc libraries cacao.core needs — see README.md for the one-liner):
     nbb --classpath <cp> scripts/hermes-bots-aozora/register.cljs [job-name ...]
   (no args = every job in ~/.hermes/cron/jobs.json, minus HERMES_AOZORA_OPT_OUT)

   Exit: see aozora-core — upstream rejection prints BLOCKED and
   does not count as a local failure; the receipt line carries the raw error."
  (:require [aozora-core :as core]
            [cacao.core :as cacao]
            [clojure.string :as str]
            ["node:crypto" :as crypto]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def jobs-path (path/join (os/homedir) ".hermes" "cron" "jobs.json"))

(defn roster []
  (try
    (let [parsed (js->clj (js/JSON.parse (fs/readFileSync jobs-path "utf8")))
          ;; the file is {"jobs": [...], ...}; an old flat-array shape is
          ;; accepted too so a hermes upgrade in either direction stays read.
          jobs (if (map? parsed) (get parsed "jobs") parsed)]
      (->> jobs (map #(get % "name")) (remove str/blank?) vec))
    (catch :default e
      (println "REFUSED: cannot read" jobs-path "-" (.-message e))
      nil)))

(defn- sh [cmd args]
  (let [r (cp/spawnSync cmd (clj->js args) #js {:encoding "utf8"})]
    {:status (.-status r) :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))

(def user (or (aget (.-env js/process) "USER") "fabric-operator"))

(defn ensure-seed!
  "→ Uint8Array seed. Reads the Keychain item, else generates and stores one
   first. Throwing here is a LOCAL failure."
  [handle]
  (let [svc (core/seed-service handle)
        got (sh "security" ["find-generic-password" "-a" user "-s" svc "-w"])]
    (if (zero? (:status got))
      (js/Uint8Array.from (js/Buffer.from (str/trim (:out got)) "base64"))
      (let [seed (js/Uint8Array.from (.randomBytes crypto 32))
            b64 (.toString (js/Buffer.from seed) "base64")
            put (sh "security" ["add-generic-password" "-U" "-a" user "-s" svc "-w" b64])]
        (when-not (zero? (:status put))
          (throw (ex-info (str "keychain add failed for " svc) {:err (:err put)})))
        seed))))

(defn mint [seed]
  (let [now (js/Date.)]
    (:cacao-b64 (cacao/mint {:seed seed :aud core/pds-aud
                             :domain core/pds-domain
                             :iat (.toISOString now)
                             :exp (.toISOString (js/Date. (+ (.getTime now) 300000)))
                             :nonce (str (random-uuid))
                             :resources ["atproto://account/session"]}))))

(defn xrpc [nsid body token]
  (-> (js/fetch (str core/pds "/xrpc/" nsid)
                (clj->js (cond-> {:method "POST"
                                  :headers (cond-> {"content-type" "application/json"}
                                             token (assoc "authorization" (str "Bearer " token)))
                                  :body (js/JSON.stringify (clj->js body))})))
      (.then (fn [resp]
               (.then (.text resp)
                      (fn [text]
                        (let [v (try (js->clj (js/JSON.parse text)) (catch :default _ {}))]
                          {:status (.-status resp) :body v
                           :ok (and (< (.-status resp) 400) (not (get v "error")))})))))))

(defn resolve-handle
  "→ Promise<:bound | :unbound | :unverifiable>. On this PDS a createSession
   for ANY validly-signed did:key succeeds (the DID is the account), so a good
   session proves nothing about the handle registry — only resolveHandle does,
   and it is exactly the surface the current upstream outage takes down."
  [handle]
  (-> (js/fetch (str core/pds "/xrpc/com.atproto.identity.resolveHandle?handle="
                     (js/encodeURIComponent handle)))
      (.then (fn [resp]
               (.then (.text resp)
                      (fn [text]
                        (cond (= 200 (.-status resp)) :bound
                              (str/includes? text "AccountNotFound") :unbound
                              :else :unverifiable)))))
      (.catch (fn [_] :unverifiable))))

(defn register-bot! [job-name]
  (let [handle (core/handle job-name)
        seed (ensure-seed! handle)]
    (-> (xrpc "com.atproto.server.createSession" {:cacao (mint seed)} nil)
        (.then
         (fn [{:keys [ok body] :as sess}]
           (if ok
             (-> (resolve-handle handle)
                 (.then (fn [hstate]
                          {:name job-name :handle handle :did (get body "did")
                           :state (case hstate
                                    :bound "registered"
                                    :unbound "session-ok-handle-unbound"
                                    "session-ok-handle-unverifiable")})))
             (-> (xrpc "com.atproto.server.createAccount" {:handle handle :cacao (mint seed)} nil)
                 (.then
                  (fn [{:keys [ok body status]}]
                    (if-not ok
                      {:name job-name :handle handle :state "BLOCKED"
                       :error (str "createAccount " status " " (get body "error") ": " (get body "message")
                                   " (session: " (:status sess) " " (get-in sess [:body "error"]) ")")}
                      (let [did (get body "did") token (get body "accessJwt")
                            now (.toISOString (js/Date.))]
                        (-> (xrpc "com.atproto.repo.putRecord"
                                  {:repo did :collection "app.bsky.actor.profile" :rkey "self"
                                   :record {:$type "app.bsky.actor.profile"
                                            :displayName (core/display-name job-name)
                                            :description (core/description job-name)}} token)
                            (.then (fn [_]
                                     (xrpc "com.atproto.repo.createRecord"
                                           {:repo did :collection "app.bsky.feed.post"
                                            :record {:$type "app.bsky.feed.post"
                                                     :text (core/registration-text job-name)
                                                     :createdAt now}} token)))
                            (.then (fn [{:keys [ok body status]}]
                                     (cond-> {:name job-name :handle handle :did did :state "registered"}
                                       ok (assoc :post-uri (get body "uri"))
                                       (not ok) (assoc :state "registered-post-failed"
                                                       :error (str status " " (get body "error")))))))))))))))
        (.catch (fn [e] {:name job-name :handle handle :state "LOCAL-FAILURE"
                         :error (.-message e)})))))

(defn -main []
  (let [args (vec *command-line-args*)
        all (roster)]
    (if (nil? all)
      (.exit js/process 2)
      (let [selected (->> (if (seq args) args all)
                          (remove core/opted-out?) vec)]
        (-> (reduce (fn [p n]
                      (.then p (fn [acc]
                                 (.then (register-bot! n) (fn [r] (conj acc r))))))
                    (js/Promise.resolve []) selected)
            (.then (fn [results]
                     (doseq [{:keys [name handle did state error post-uri]} results]
                       (println (str state "\t" name "\t" handle
                                     (when did (str "\t" did))
                                     (when post-uri (str "\t" post-uri))
                                     (when error (str "\t" error)))))
                     (when (some #(= "BLOCKED" (:state %)) results)
                       (println "BLOCKED: the PDS rejected registration upstream — see"
                                "ADR-2608291500 (kotobase Biscuit-required cutover)."))
                     (.exit js/process
                            (core/exit-code (count results)
                                            (count (filter #(= "LOCAL-FAILURE" (:state %)) results)))))))))))

(-main)

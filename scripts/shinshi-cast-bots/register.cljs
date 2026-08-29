(ns register
  "Give every club-shinshi cast bot (and the producer bot) an aozora.app
   account (did:key + CACAO). Adapted from scripts/hermes-bots-aozora/
   register.cljs — same bootstrap order, same Keychain custody, same
   BLOCKED semantics (ADR-2608291500: the kotobase Biscuit-required cutover
   401s createAccount/createRecord today; seeds are still minted so the
   accounts appear the moment the aozora PDS migrates).

   Order per bot (yukkuri's account-bootstrap-order):
     1. ensure an Ed25519 seed in the Keychain BEFORE any network call;
     2. createSession first; only when that fails, createAccount;
     3. on a fresh account, putRecord the profile + one registration post.

   Run from the superproject root:
     R=$PWD K=$PWD/orgs/kotoba-lang
     CP=\"$R/scripts/shinshi-cast-bots:$K/org-chainagnostic-cacao/src:$K/authority/src:$K/org-ietf-ed25519/src:$K/org-ietf-cbor/src\"
     nbb --classpath \"$CP\" scripts/shinshi-cast-bots/register.cljs [slug ...]

   Exit: see cast-core — upstream rejection prints BLOCKED and does not
   count as a local failure."
  (:require [cast-core :as core]
            [cast-d1 :as d1]
            [cacao.core :as cacao]
            [clojure.string :as str]
            ["node:crypto" :as crypto]
            ["node:child_process" :as cp]))

(defn- sh [cmd args]
  (let [r (cp/spawnSync cmd (clj->js args) #js {:encoding "utf8"})]
    {:status (.-status r) :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))

(def user (or (aget (.-env js/process) "USER") "fabric-operator"))

(defn ensure-seed! [handle]
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

(defn resolve-handle [handle]
  (-> (js/fetch (str core/pds "/xrpc/com.atproto.identity.resolveHandle?handle="
                     (js/encodeURIComponent handle)))
      (.then (fn [resp]
               (.then (.text resp)
                      (fn [text]
                        (cond (= 200 (.-status resp)) :bound
                              (str/includes? text "AccountNotFound") :unbound
                              :else :unverifiable)))))
      (.catch (fn [_] :unverifiable))))

(defn register-bot!
  "bot: {:name <slug-or-producer> :display <name> :description <text> :post <text>}"
  [{:keys [name display description post]}]
  (let [handle (core/handle name)
        seed (ensure-seed! handle)]
    (-> (xrpc "com.atproto.server.createSession" {:cacao (mint seed)} nil)
        (.then
         (fn [{:keys [ok body] :as sess}]
           (if ok
             (-> (resolve-handle handle)
                 (.then (fn [hstate]
                          {:name name :handle handle :did (get body "did")
                           :state (case hstate
                                    :bound "registered"
                                    :unbound "session-ok-handle-unbound"
                                    "session-ok-handle-unverifiable")})))
             (-> (xrpc "com.atproto.server.createAccount" {:handle handle :cacao (mint seed)} nil)
                 (.then
                  (fn [{:keys [ok body status]}]
                    (if-not ok
                      {:name name :handle handle :state "BLOCKED"
                       :error (str "createAccount " status " " (get body "error") ": " (get body "message")
                                   " (session: " (:status sess) " " (get-in sess [:body "error"]) ")")}
                      (let [did (get body "did") token (get body "accessJwt")
                            now (core/whole-second-iso)]
                        (-> (xrpc "com.atproto.repo.putRecord"
                                  {:repo did :collection "app.bsky.actor.profile" :rkey "self"
                                   :record {:$type "app.bsky.actor.profile"
                                            :displayName display
                                            :description description}} token)
                            (.then (fn [_]
                                     (xrpc "com.atproto.repo.createRecord"
                                           {:repo did :collection "app.bsky.feed.post"
                                            :record {:$type "app.bsky.feed.post"
                                                     :text post
                                                     :createdAt now}} token)))
                            (.then (fn [{:keys [ok body status]}]
                                     (cond-> {:name name :handle handle :did did :state "registered"}
                                       ok (assoc :post-uri (get body "uri"))
                                       (not ok) (assoc :state "registered-post-failed"
                                                       :error (str status " " (get body "error")))))))))))))))
        (.catch (fn [e] {:name name :handle handle :state "LOCAL-FAILURE"
                         :error (.-message e)})))))

(defn bots []
  (let [members (d1/cast-members)]
    (when members
      (conj
       (mapv (fn [{:keys [slug series profile]}]
               (let [nm (or (:charName profile) slug)]
                 {:name slug
                  :display (core/cast-display-name nm series)
                  :description (core/cast-description nm series slug)
                  :post (core/cast-registration-text nm slug)}))
             members)
       {:name core/producer-name
        :display "club-shinshi producer (bot)"
        :description (core/producer-description)
        :post (str core/producer-name " bot registered on aozora.app. "
                   "Posts here are run receipts of catalog growth, not prose.")}))))

(defn -main []
  (let [args (vec *command-line-args*)
        all (bots)]
    (if (nil? all)
      (do (println "REFUSED: catalog unanswerable (D1)") (.exit js/process 2))
      (let [selected (->> (if (seq args)
                            (filterv #(contains? (set args) (:name %)) all)
                            all)
                          (remove #(core/opted-out? (:name %))) vec)]
        (-> (reduce (fn [p b]
                      (.then p (fn [acc]
                                 (.then (register-bot! b) (fn [r] (conj acc r))))))
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

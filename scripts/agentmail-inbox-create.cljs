#!/usr/bin/env nbb
;; Create an additional agentmail.to inbox (Telnyx-hosted Agent Inbox).
;;
;;   nbb scripts/agentmail-inbox-create.cljs challenge            ; step 1+2, prints a problem
;;   nbb scripts/agentmail-inbox-create.cljs create ACCEPT '<answer>' [label]
;;   nbb scripts/agentmail-inbox-create.cljs list
;;   nbb scripts/agentmail-inbox-create.cljs messages <account_id>
;;   nbb scripts/agentmail-inbox-create.cljs read <account_id> '<message-id>'
;;
;; WHY THIS EXISTS RATHER THAN `telnyx-agent-signup.cljs inbox-create`.
;; That script stores the account_key with `security add-generic-password -U`
;; under the single service `telnyx-agent-inbox-key`, and overwrites
;; ~/.telnyx-signup-state.json's :inbox. Running it a second time therefore
;; DESTROYS access to the inbox already in use
;; (bot-telnyx-432cf07c…@agentmail.to), whose account_key is returned once and
;; cannot be recovered — and which is the password-reset path for the AWAI
;; Network Telnyx account. This script never touches either location: each
;; inbox gets its own Keychain service `agentmail-inbox-<n>` and a row appended
;; to ~/.agentmail-inboxes.json. Nothing is ever removed.
;;
;; THE SIGNUP FLOW CHANGED between 2026-08-17 and 2026-08-21. It is now three
;; calls, and the server documents itself — the PoW challenge response carries
;; `bot_challenge_required: true` plus a `bot_challenge` block naming the next
;; endpoint. Read that block rather than trusting this comment:
;;
;;   1. POST /v2/agent_inboxes/challenges      {purpose}
;;        -> {challenge_id, algorithm:"sha256-v1", challenge, difficulty_bits,
;;            expires_at, bot_challenge_required, bot_challenge{…}}
;;   2. POST /v2/agent_inboxes/bot_challenges  {purpose, pow{challenge_id,nonce}}
;;        -> {type:"bot_challenge", nonce, problem, challenge_type}
;;   3. POST /v2/agent_inboxes                 {purpose, bot_challenge{nonce,answer}}
;;        -> {email, account_id, account_key, …}   ← the PoW is NOT resent here
;;
;; Measured 2026-08-21: PoW construction sha256(challenge + ":" + nonce) with
;; `difficulty_bits` leading zero bits; the ordered fallback below is kept
;; because nothing published pins it. The bot challenge is single-use — a wrong
;; answer or a reused nonce comes back 410 "expired or not found", and the
;; earlier `POST https://api.telnyx.com/v2/bot_challenge` (a DIFFERENT host,
;; used by telnyx-agent-signup.cljs for account signup) issues nonces this host
;; will not accept. That mistake also reads as 410.
;;
;; ⚠ NEVER hand the account_key to `security ... -w` through a single write.
;; With no value on argv, `security` reads the secret TWICE from stdin (entry +
;; confirmation). Piping it once stores an EMPTY password, silently — and the
;; key is returned only once, so the inbox is then unrecoverable. That happened
;; on 2026-08-21 and cost one inbox. `keychain-put!` below writes it twice; use
;; it rather than an ad-hoc pipe, and verify the stored length before deleting
;; any plaintext copy.
;;
;; ⚠ RECEIVE-ONLY, for us. On the Telnyx agent-inbox host only two endpoints
;; exist (GET messages, GET messages/<id>); POST /messages and /threads are
;; 404. AgentMail's own API can send, but this inbox is created through Telnyx
;; and we hold no AgentMail credentials, so there is no send path from here.

(require '[promesa.core :as p]
         '[clojure.string :as str]
         '["node:process" :as proc]
         '["node:child_process" :as cp]
         '["node:crypto" :as crypto]
         '["node:fs" :as fs]
         '["node:os" :as os])

(def base "https://agent-inbox.telnyx.com/v2/agent_inboxes")
(def keychain-account "agentmail")
(def state-file (str (.homedir os) "/.agentmail-inboxes.json"))

(defn load-state []
  (if (.existsSync fs state-file)
    (js->clj (js/JSON.parse (.readFileSync fs state-file "utf8")) :keywordize-keys true)
    {:inboxes []}))

(defn save-state! [m]
  (.writeFileSync fs state-file (js/JSON.stringify (clj->js m) nil 2) #js {:mode 0600}))

(defn die! [msg] (println "\n✗" msg) (js/process.exit 1))

(defn req [url {:keys [method body headers]}]
  (p/let [res (js/fetch url
                        (clj->js (merge {:method (or method "GET")
                                         :redirect "follow"
                                         :headers (merge {}
                                                         (when body {"Content-Type" "application/json"})
                                                         headers)}
                                        (when body {:body (js/JSON.stringify (clj->js body))}))))
          text (.text res)]
    {:ok (.-ok res) :status (.-status res) :text text
     :json (try (js->clj (js/JSON.parse text) :keywordize-keys true)
                (catch :default _ nil))}))

(defn keychain-put!
  "`security -w` with no value reads the secret TWICE from stdin. Nothing on argv."
  [service secret]
  (p/create
   (fn [resolve reject]
     (let [child (.spawn cp "security"
                         #js ["add-generic-password" "-a" keychain-account
                              "-s" service "-U" "-w"]
                         #js {:stdio #js ["pipe" "ignore" "pipe"]})
           errbuf (atom "")]
       (.on (.-stderr child) "data" #(swap! errbuf str (.toString %)))
       (.on child "close" #(if (zero? %) (resolve true)
                               (reject (js/Error. (str "security exited " % ": " @errbuf)))))
       (.write (.-stdin child) (str secret "\n" secret "\n"))
       (.end (.-stdin child))))))

(defn keychain-get [service]
  (try (str/trim (.toString (.execFileSync cp "security"
                                           #js ["find-generic-password" "-s" service "-w"]
                                           #js {:stdio #js ["ignore" "pipe" "ignore"]})))
       (catch :default _ nil)))

(defn keychain-exists? [service]
  (some? (try (.execFileSync cp "security"
                             #js ["find-generic-password" "-s" service]
                             #js {:stdio #js ["ignore" "ignore" "ignore"]})
              (catch :default _ nil))))

;; ------------------------------------------------------------ proof of work

(defn meets? [buf bits]
  (loop [i 0 remaining bits]
    (cond
      (<= remaining 0) true
      (>= i (.-length buf)) false
      :else (let [b (aget buf i)]
              (if (>= remaining 8)
                (if (zero? b) (recur (inc i) (- remaining 8)) false)
                (zero? (bit-shift-right b (- 8 remaining))))))))

(defn sha256 [s]
  (let [h (.createHash crypto "sha256")] (.update h s "utf8") (.digest h)))

(def pow-variants
  [{:name "sha256(challenge + ':' + nonce)" :f (fn [c _ n] (str c ":" n))}
   {:name "sha256(challenge + nonce)"       :f (fn [c _ n] (str c n))}
   {:name "sha256(challenge_id + nonce)"    :f (fn [_ id n] (str id n))}
   {:name "sha256(nonce + challenge)"       :f (fn [c _ n] (str n c))}])

(defn solve-pow [variant challenge challenge-id bits cap]
  (let [f (:f variant)]
    (loop [n 0]
      (cond
        (> n cap) nil
        (meets? (sha256 (f challenge challenge-id n)) bits) (str n)
        :else (recur (inc n))))))

(defn next-service []
  (loop [n 1]
    (let [s (str "agentmail-inbox-" n)]
      (if (keychain-exists? s) (recur (inc n)) s))))

;; -------------------------------------------------- step 1+2: get a problem

(defn cmd-challenge []
  (p/let [_ (println "→ [1/3] proof-of-work challenge…")
          c0 (req (str base "/challenges") {:method "POST" :body {:purpose "bot_signup"}})
          _ (when-not (:ok c0)
              (die! (str "pow challenge failed (" (:status c0) "): " (:text c0))))
          d0 (get-in c0 [:json :data])
          bits (:difficulty_bits d0)
          _ (println "    difficulty:" bits "bits  expires:" (:expires_at d0))
          _ (when-let [bc (:bot_challenge d0)]
              (println "    server says next:" (:method bc) (:url bc)))
          solved
          (p/loop [vs pow-variants]
            (if (empty? vs)
              nil
              (let [v (first vs)
                    t0 (.now js/Date)
                    nonce (solve-pow v (:challenge d0) (:challenge_id d0) bits 20000000)]
                (if-not nonce
                  (p/recur (rest vs))
                  (p/let [_ (println "    solved" (:name v) "-> nonce" nonce
                                     (str "(" (.toFixed (/ (- (.now js/Date) t0) 1000.0) 1) "s)"))
                          r (req (str base "/bot_challenges")
                                 {:method "POST"
                                  :body {:purpose "bot_signup"
                                         :pow {:challenge_id (:challenge_id d0)
                                               :nonce nonce}}})]
                    (if (:ok r) r
                        (do (println "    rejected (" (:status r) "):" (:text r))
                            (p/recur (rest vs)))))))))]
    (if (nil? solved)
      (die! "every proof-of-work construction was rejected — the scheme may have changed.")
      (let [d (get-in solved [:json :data])]
        (save-state! (assoc (load-state) :challenge (assoc d :issued_at (.now js/Date))))
        (println "\n→ [2/3] bot challenge issued")
        (println "─────────────── PROBLEM ───────────────")
        (println "nonce: " (:nonce d))
        (println "type:  " (:challenge_type d))
        (println (:problem d))
        (println "───────────────────────────────────────")
        (println "\nSingle-use. Answer it and run immediately:")
        (println "  nbb scripts/agentmail-inbox-create.cljs create ACCEPT '<answer>' [label]")))))

;; ------------------------------------------------------------- step 3: create

(defn cmd-create [accept answer label]
  (if (not= accept "ACCEPT")
    (die! (str "refusing to create.\n"
               "  This creates a Telnyx-hosted agentmail.to inbox. Its account_key is\n"
               "  returned once and cannot be recovered if lost.\n"
               "  Re-run with the literal token ACCEPT."))
    (let [ch (:challenge (load-state))]
      (cond
        (nil? ch) (die! "no stored bot challenge — run `challenge` first")
        (str/blank? answer) (die! "missing <answer>")
        :else
        (p/let [service (next-service)
                _ (println "→ [3/3] creating inbox — answer" (pr-str answer))
                r (req base {:method "POST"
                             :body {:purpose "bot_signup"
                                    :bot_challenge {:nonce (:nonce ch) :answer answer}}})]
          (if-not (:ok r)
            (die! (str "rejected (" (:status r) "): " (:text r)
                       "\n  410 = wrong answer, or the nonce was already used."
                       "\n        Re-run `challenge` and answer the new problem."))
            (let [data (or (get-in r [:json :data]) (:json r))
                  {:keys [email account_id account_key]} data]
              (if-not (and email account_id account_key)
                (die! (str "created but the response lacked expected fields: " (:text r)))
                (p/let [_ (keychain-put! service account_key)
                        stored (keychain-get service)]
                  ;; Verify BEFORE the key leaves memory. An empty or truncated
                  ;; store here means the inbox is already unrecoverable, and
                  ;; saying so now is the only useful thing left.
                  (if (not= stored account_key)
                    (die! (str "KEYCHAIN WRITE DID NOT ROUND-TRIP for " service
                               " (stored " (count (or stored "")) " chars, expected "
                               (count account_key) ").\n"
                               "  This inbox is UNRECOVERABLE: " email "\n"
                               "  Create another one; do not retry this key."))
                    (p/let [st (load-state)
                            _ (save-state! (-> st
                                               (update :inboxes (fnil conj [])
                                                       {:email email
                                                        :account_id account_id
                                                        :keychain_service service
                                                        :label (or label "")
                                                        :created_at (:created_at data)})
                                               (dissoc :challenge)))]
                      (println "\n✓ agentmail.to inbox created and verified.")
                      (println "  email:      " email)
                      (println "  account_id: " account_id)
                      (println "  account_key: Keychain service" service
                               (str "(account " keychain-account ", "
                                    (count account_key) " chars, not printed)"))
                      (println "  recorded in" state-file))))))))))))

;; ------------------------------------------------------------------- reading

(defn cmd-list []
  (let [st (load-state)]
    (if (empty? (:inboxes st))
      (println "no inboxes recorded in" state-file)
      (doseq [i (:inboxes st)]
        (println (:email i))
        (println "  account_id:" (:account_id i)
                 " keychain:" (:keychain_service i)
                 (if (str/blank? (:label i)) "" (str " label: " (:label i))))))))

(defn service-for [aid]
  (->> (:inboxes (load-state)) (filter #(= aid (:account_id %))) first :keychain_service))

(defn fetch-as [aid path]
  (let [svc (service-for aid)
        key (some-> svc keychain-get)]
    (cond
      (nil? svc) (die! (str "unknown account_id " aid " — run `list`"))
      (str/blank? key) (die! (str "account_key missing or empty in Keychain (" svc ")"))
      :else (p/let [r (req (str base "/" aid path)
                           {:headers {"Authorization" (str "Bearer " key)}})]
              (println "HTTP" (:status r))
              (println (:text r))))))

(defn cmd-messages [aid] (fetch-as aid "/messages"))
(defn cmd-read [aid mid] (fetch-as aid (str "/messages/" mid)))

;; ---------------------------------------------------------------------- main

(let [[cmd a b c] (drop 3 (js->clj (.-argv proc)))]
  (case cmd
    "challenge" (cmd-challenge)
    "create"    (cmd-create a b c)
    "list"      (cmd-list)
    "messages"  (cmd-messages a)
    "read"      (cmd-read a b)
    (do (println "usage:")
        (println "  nbb scripts/agentmail-inbox-create.cljs challenge")
        (println "  nbb scripts/agentmail-inbox-create.cljs create ACCEPT '<answer>' [label]")
        (println "  nbb scripts/agentmail-inbox-create.cljs list")
        (println "  nbb scripts/agentmail-inbox-create.cljs messages <account_id>")
        (println "  nbb scripts/agentmail-inbox-create.cljs read <account_id> '<message-id>'"))))

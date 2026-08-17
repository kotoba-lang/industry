#!/usr/bin/env nbb
;; Telnyx agent signup — AWAI Network, L.L.C.
;; Follows https://telnyx.com/agent-signup.md
;;
;; Subcommands, so this runs without a TTY (Claude Code's `!`, ssh, CI):
;;
;;   nbb scripts/telnyx-agent-signup.cljs inbox-create ACCEPT   # Telnyx Agent Inbox
;;   nbb scripts/telnyx-agent-signup.cljs challenge             # bot challenge
;;   nbb scripts/telnyx-agent-signup.cljs signup '<answer>' ACCEPT
;;   nbb scripts/telnyx-agent-signup.cljs inbox-wait            # poll -> key
;;   echo '<link>' | nbb scripts/telnyx-agent-signup.cljs redeem   # manual fallback
;;   nbb scripts/telnyx-agent-signup.cljs resend
;;   nbb scripts/telnyx-agent-signup.cljs status
;;
;; The literal token ACCEPT is required on the two steps that create something
;; at Telnyx, so each is a deliberate act by the owner.
;;
;; Secrets never touch argv (`ps` shows it) and are never printed: the inbox
;; `account_key` and the final API key go straight into the macOS Keychain.
;; The first Telnyx key this workspace issued was pasted into a chat log and is
;; still flagged for rotation — this avoids repeating that.
;;
;; UNDOCUMENTED API SHAPES, established by probing the live endpoints on
;; 2026-08-17 (agent-signup.md documents neither):
;;   POST /v2/agent_inboxes/challenges  {"purpose":"bot_signup"}
;;     -> {data:{challenge_id, algorithm:"sha256-v1", challenge,
;;               difficulty_bits, expires_at}}
;;   POST /v2/agent_inboxes  {"purpose":"bot_signup",
;;                            "pow":{"challenge_id":…,"nonce":…}}
;; The proof-of-work construction is NOT published. `solve-pow` tries the
;; plausible ones in order; a wrong construction is rejected with
;; "Invalid proof of work" and costs nothing, so trying is safe.
;;
;; MEASURED 2026-08-17: the accepted construction is the SECOND candidate,
;;   sha256(challenge + ":" + nonce)   with difficulty_bits leading zero bits
;; (plain `challenge + nonce` is rejected). The ordered fallback is kept rather
;; than hardcoded, because nothing published pins this and it can change.

(require '[promesa.core :as p]
         '[clojure.string :as str]
         '["node:process" :as proc]
         '["node:child_process" :as cp]
         '["node:crypto" :as crypto]
         '["node:fs" :as fs]
         '["node:os" :as os])

(def fallback-email "j@awai.network")
(def api-key-service "telnyx-api-key-awai")
(def inbox-key-service "telnyx-agent-inbox-key")
(def keychain-account "telnyx")

(def state-file (str (.homedir os) "/.telnyx-signup-state.json"))

(defn load-state []
  (if (.existsSync fs state-file)
    (js->clj (js/JSON.parse (.readFileSync fs state-file "utf8")) :keywordize-keys true)
    {}))

(defn save-state! [m]
  (.writeFileSync fs state-file (js/JSON.stringify (clj->js m) nil 2) #js {:mode 0600}))

(defn update-state! [f & args]
  (let [m (apply f (load-state) args)] (save-state! m) m))

(defn die! [msg] (println "\n✗" msg) (js/process.exit 1))

(defn req [url {:keys [method body headers]}]
  (p/let [res (js/fetch url
                        (clj->js (merge {:method (or method "GET")
                                         :redirect "follow"
                                         ;; must be at least {} — a nil here makes the
                                         ;; Headers constructor throw before the request
                                         ;; is ever sent (bit us on a bare GET)
                                         :headers (merge {}
                                                         (when body {"Content-Type" "application/json"})
                                                         headers)}
                                        (when body {:body (js/JSON.stringify (clj->js body))}))))
          text (.text res)]
    {:ok (.-ok res) :status (.-status res) :text text
     :json (try (js->clj (js/JSON.parse text) :keywordize-keys true)
                (catch :default _ nil))}))

(defn read-stdin []
  (p/create (fn [resolve _]
              (let [buf (atom "")]
                (.setEncoding proc/stdin "utf8")
                (.on proc/stdin "data" #(swap! buf str %))
                (.on proc/stdin "end" #(resolve (str/trim @buf)))))))

(defn keychain-put!
  "`security -w` with no value reads the secret twice from stdin. Nothing on argv."
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

;; ------------------------------------------------------------ proof of work

(defn meets?
  "Does the digest start with `bits` zero bits?"
  [buf bits]
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
  [{:name "sha256(challenge + nonce)"       :f (fn [c _ n] (str c n))}
   {:name "sha256(challenge + ':' + nonce)" :f (fn [c _ n] (str c ":" n))}
   {:name "sha256(challenge_id + nonce)"    :f (fn [_ id n] (str id n))}
   {:name "sha256(nonce + challenge)"       :f (fn [c _ n] (str n c))}])

(defn solve-pow
  "Find a nonce for one construction. Returns nil if none within `cap`."
  [variant challenge challenge-id bits cap]
  (let [f (:f variant)]
    (loop [n 0]
      (cond
        (> n cap) nil
        (meets? (sha256 (f challenge challenge-id n)) bits) (str n)
        :else (recur (inc n))))))

;; ------------------------------------------------------------ inbox-create

(defn cmd-inbox-create [accept]
  (if (not= accept "ACCEPT")
    (die! (str "refusing to send.\n"
               "  This creates a Telnyx-hosted Agent Inbox. Its account_key is\n"
               "  returned once and cannot be recovered if lost.\n"
               "  Re-run with the literal token ACCEPT."))
    (p/let [_ (println "→ requesting proof-of-work challenge…")
            c (req "https://agent-inbox.telnyx.com/v2/agent_inboxes/challenges"
                   {:method "POST" :body {:purpose "bot_signup"}})
            _ (when-not (:ok c)
                (die! (str "challenge failed (" (:status c) "): " (:text c))))
            d (get-in c [:json :data])
            bits (:difficulty_bits d)
            _ (println "  algorithm:" (:algorithm d) " difficulty:" bits "bits"
                       " expires:" (:expires_at d))

            ;; Try each construction: solve, submit, stop on success. A wrong
            ;; construction comes back "Invalid proof of work" and creates nothing.
            result
            (p/loop [vs pow-variants]
              (if (empty? vs)
                :exhausted
                (let [v (first vs)]
                  (println "\n→ solving" (:name v) "…")
                  (let [t0 (.now js/Date)
                        nonce (solve-pow v (:challenge d) (:challenge_id d) bits 20000000)]
                    (if-not nonce
                      (do (println "  no nonce found within cap") (p/recur (rest vs)))
                      (p/let [_ (println "  nonce" nonce "in"
                                         (.toFixed (/ (- (.now js/Date) t0) 1000.0) 1) "s")
                              r (req "https://agent-inbox.telnyx.com/v2/agent_inboxes"
                                     {:method "POST"
                                      :body {:purpose "bot_signup"
                                             :pow {:challenge_id (:challenge_id d)
                                                   :nonce nonce}}})]
                        (if (:ok r)
                          (assoc r :variant (:name v))
                          (do (println "  rejected (" (:status r) "):" (:text r))
                              (p/recur (rest vs))))))))))]

      (cond
        (= result :exhausted)
        (die! (str "every proof-of-work construction was rejected.\n"
                   "  The scheme is undocumented; `sha256-v1` may hash raw bytes\n"
                   "  or include a field this script does not send."))

        :else
        (let [data (or (get-in result [:json :data]) (:json result))
              {:keys [email account_id account_key]} data]
          (if-not (and email account_id account_key)
            (die! (str "inbox created but the response lacked expected fields: "
                       (:text result)))
            (p/let [_ (println "\n✓ accepted with" (:variant result))
                    _ (keychain-put! inbox-key-service account_key)
                    _ (update-state! assoc :inbox {:email email :account_id account_id})]
              (println "✓ Agent Inbox created.")
              (println "  email:      " email)
              (println "  account_id: " account_id)
              (println "  account_key: stored in Keychain as" inbox-key-service
                       (str "(" (count account_key) " chars, not printed)"))
              (println "\nNext: get a bot challenge, have Claude answer it, then sign up.")
              (println "  nbb scripts/telnyx-agent-signup.cljs challenge"))))))))

;; -------------------------------------------------------------- challenge

(defn cmd-challenge []
  (p/let [c (req "https://api.telnyx.com/v2/bot_challenge" {:method "POST" :body {}})]
    (if-not (:ok c)
      (die! (str "bot_challenge failed (" (:status c) "): " (:text c)))
      (let [d (get-in c [:json :data])]
        (update-state! assoc :challenge (assoc d :issued_at (.now js/Date)))
        (println "\n─────────── CHALLENGE ───────────")
        (println "nonce:     " (:nonce d))
        (println "type:      " (:challenge_type d))
        (println "precision: " (:precision d))
        (println "problem:   " (:problem d))
        (println "─────────────────────────────────")
        (println "\nPaste the problem to Claude, then:")
        (println "  nbb scripts/telnyx-agent-signup.cljs signup '<answer>' ACCEPT")))))

;; ----------------------------------------------------------------- signup

(defn cmd-signup [answer accept]
  (let [st (load-state)
        ch (:challenge st)
        email (get-in st [:inbox :email] fallback-email)
        age (when ch (/ (- (.now js/Date) (:issued_at ch)) 1000.0))]
    (cond
      (nil? ch)           (die! "no stored challenge — run `challenge` first")
      (str/blank? answer) (die! "missing <answer>")
      (not= accept "ACCEPT")
      (die! (str "refusing to send.\n"
                 "  This CREATES A TELNYX ACCOUNT for AWAI Network, L.L.C. and\n"
                 "  accepts Telnyx's terms of service and privacy policy:\n"
                 "    " (:terms_and_conditions_url ch) "\n"
                 "    " (:privacy_policy_url ch) "\n"
                 "  email: " email "\n"
                 "  Re-run with the literal token ACCEPT as the last argument."))
      :else
      (p/let [_ (println "→ submitting signup…")
              _ (println "  email: " email)
              _ (println "  nonce: " (:nonce ch) (str "(issued " (.toFixed age 0) "s ago)"))
              _ (println "  answer:" (pr-str answer))
              s (req "https://api.telnyx.com/v2/bot_signup"
                     {:method "POST"
                      :body {:bot_challenge_nonce (:nonce ch)
                             :bot_challenge_answer answer
                             :terms_and_conditions_url (:terms_and_conditions_url ch)
                             :privacy_policy_url (:privacy_policy_url ch)
                             :email email
                             :terms_of_service true}})]
        (if-not (:ok s)
          (die! (str "bot_signup failed (" (:status s) "): " (:text s)
                     "\n  Wrong answer or stale nonce -> re-run `challenge`."))
          (do
            (update-state! assoc :signup {:email email :at (.now js/Date)})
            (println "✓ signup accepted —" (:text s))
            (if (get-in st [:inbox :account_id])
              (println "\nNow collect the link from the Agent Inbox:\n"
                       "  nbb scripts/telnyx-agent-signup.cljs inbox-wait")
              (do (println "\nVerification mail sent to" email)
                  (println "⚠ Do NOT open the link in a browser — the first request")
                  (println "  to it consumes it, even one that then errors.")
                  (println "  echo '<link>' | nbb scripts/telnyx-agent-signup.cljs redeem")))))))))

;; ------------------------------------------------------ redeem / inbox-wait

(defn finish-with-link [link]
  (p/let [_ (println "→ redeeming link…")
          r (req link {})
          tok (get-in (:json r) [:data :api_v2_token])]
    (if-not tok
      (die! (str "no data.api_v2_token (" (:status r) "): " (:text r)))
      (p/let [_ (println "✓ session token obtained")
              _ (println "→ creating API key…")
              k (req "https://api.telnyx.com/v2/api_keys"
                     {:method "POST" :body {}
                      :headers {"Authorization" (str "Bearer " tok)}})]
        (if-not (:ok k)
          (die! (str "api_keys failed (" (:status k) "): " (:text k)))
          (let [api-key (get-in (:json k) [:data :api_key])]
            (if-not api-key
              (die! (str "no data.api_key: " (:text k)))
              (p/let [_ (keychain-put! api-key-service api-key)]
                (println "\n✓ API key stored in the Keychain.")
                (println "  service:" api-key-service "  account:" keychain-account)
                (println "  length: " (count api-key) "chars (value not printed)")
                (println "\nCopy it into kagi — kagi is the record of custody,")
                (println "the Keychain is only the cache .mcp.json reads.")
                (println "(the subcommand is `add`, reading stdin; there is no `put`)")
                (println "\n  security find-generic-password -s" api-key-service "-w \\")
                (println "    | KAGI_HOME=$HOME/.kagi orgs/kotoba-lang/kagi/bin/kagi add"
                         api-key-service "-c personal")
                (println "\n⚠ `security -w` appends a trailing newline; `kagi get`")
                (println "  does not (measured: 59 vs 58 bytes for a 58-char key).")
                (println "  `kagi add` strips it, and $(…) drops it — but a raw")
                (println "  byte-for-byte compare of the two will differ.")))))))))

(defn extract-link
  "Pull the single-use portal link out of a message body, decoding HTML
   entities first — agent-signup.md warns the raw &amp; form fails."
  [body]
  (let [decoded (-> (str body)
                    (str/replace "&amp;" "&") (str/replace "&#38;" "&")
                    (str/replace "&quot;" "\"") (str/replace "&#39;" "'"))]
    ;; The mail also carries a marketing footer link to
    ;; portal.telnyx.com/#/login/sign-in. A loose "sign-in" match picks that one,
    ;; and GETting it just returns the portal SPA's HTML — which looks like a
    ;; failure of the token, not of the matcher. Match the real endpoint by name.
    (or (re-find #"https://api\.telnyx\.com/v2/bot_sessions\?[^\s\"'<>\\]+" decoded)
        (re-find #"https://[^\s\"'<>\\]*portal_redirect_token=[^\s\"'<>\\]+" decoded))))

(defn scan-messages
  "Fetch each message once and return [message-id link] for the first that
   carries a sign-in link, or nil."
  [aid key msgs]
  (p/loop [ms msgs]
    (if (empty? ms)
      nil
      (p/let [m (first ms)
              mid (or (:id m) (:message_id m) (:uuid m))
              full (req (str "https://agent-inbox.telnyx.com/v2/agent_inboxes/"
                             aid "/messages/" mid)
                        {:headers {"Authorization" (str "Bearer " key)}})
              link (extract-link (:text full))]
        (if link [mid link] (p/recur (rest ms)))))))

(defn cmd-inbox-wait []
  (let [st (load-state)
        aid (get-in st [:inbox :account_id])
        key (keychain-get inbox-key-service)]
    (cond
      (nil? aid) (die! "no Agent Inbox in state — run `inbox-create ACCEPT` first")
      (nil? key) (die! (str "account_key not in Keychain (" inbox-key-service ")"))
      :else
      (p/loop [attempt 1]
        (p/let [_ (println (str "→ polling inbox (attempt " attempt "/20)…"))
                r (req (str "https://agent-inbox.telnyx.com/v2/agent_inboxes/"
                            aid "/messages")
                       {:headers {"Authorization" (str "Bearer " key)}})]
          (if-not (:ok r)
            (die! (str "listing messages failed (" (:status r) "): " (:text r)))
            (p/let [raw (or (get-in r [:json :data]) (:json r))
                    msgs (if (sequential? raw) raw [])
                    _ (println "  " (count msgs) "message(s)")
                    hit (if (seq msgs) (scan-messages aid key msgs) nil)]
              (cond
                hit (do (println "✓ sign-in link found in message" (first hit))
                        (finish-with-link (second hit)))
                (>= attempt 20)
                (die! (str "no sign-in link after 20 attempts.\n"
                           "  If mail arrived but no link was matched, dump it:\n"
                           "    security find-generic-password -s " inbox-key-service " -w \\\n"
                           "      | xargs -I{} curl -s -H 'Authorization: Bearer {}' \\\n"
                           "        https://agent-inbox.telnyx.com/v2/agent_inboxes/" aid "/messages"))
                :else (p/let [_ (p/delay 6000)] (p/recur (inc attempt)))))))))))

;; ------------------------------------------------------- reading the inbox
;;
;; The Telnyx Agent Inbox API is READ-ONLY. Measured 2026-08-17: only
;;   GET /v2/agent_inboxes/<account_id>/messages
;;   GET /v2/agent_inboxes/<account_id>/messages/<message_id>
;; answer 200. Inbox metadata, threads, and POST to /messages all return 404 —
;; there is no way to send or reply from this address.

(defn inbox-auth []
  (let [aid (get-in (load-state) [:inbox :account_id])
        key (keychain-get inbox-key-service)]
    (cond
      (nil? aid) (die! "no Agent Inbox in state")
      (nil? key) (die! (str "account_key not in Keychain (" inbox-key-service ")"))
      :else [aid key])))

(defn re
  "CLJS regex literals compile to JS RegExp, which does NOT accept inline
   (?is) flags — a literal #\"(?is)…\" silently fails to match, which is how
   a whole <style> block of CSS ended up in the rendered output. Build the
   RegExp with explicit flags instead."
  [pattern flags]
  (js/RegExp. pattern flags))

(defn html->text [h]
  (-> (str h)
      (str/replace (re "<(script|style)[^>]*>[\\s\\S]*?</\\1>" "gi") "")
      (str/replace (re "<!--[\\s\\S]*?-->" "g") "")
      (str/replace (re "<br\\s*/?>" "gi") "\n")
      (str/replace (re "</(p|div|tr|h[1-6]|li)>" "gi") "\n")
      (str/replace (re "<[^>]+>" "g") "")
      (str/replace "&nbsp;" " ") (str/replace "&amp;" "&")
      (str/replace "&lt;" "<") (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"") (str/replace "&#39;" "'")
      (str/replace (re "[ \\t]+" "g") " ")
      (str/replace (re "\\n[ \\t]+" "g") "\n")
      (str/replace (re "\\n{3,}" "g") "\n\n")
      str/trim))

(defn cmd-inbox-list []
  (let [[aid key] (inbox-auth)]
    (p/let [r (req (str "https://agent-inbox.telnyx.com/v2/agent_inboxes/" aid "/messages")
                   {:headers {"Authorization" (str "Bearer " key)}})]
      (if-not (:ok r)
        (die! (str "list failed (" (:status r) "): " (:text r)))
        (let [msgs (or (get-in r [:json :data]) [])]
          (println "inbox:" (get-in (load-state) [:inbox :email]))
          (println (count msgs) "message(s)\n")
          (doseq [m msgs]
            (println "  id:      " (:id m))
            (println "  from:    " (:from m))
            (println "  subject: " (:subject m))
            (println "  at:      " (:received_at m) "\n")))))))

(defn cmd-inbox-read [mid]
  (if (str/blank? mid)
    (die! "usage: inbox-read '<message-id>'  (ids come from `inbox-list`, angle brackets included)")
    (let [[aid key] (inbox-auth)]
      (p/let [r (req (str "https://agent-inbox.telnyx.com/v2/agent_inboxes/" aid
                          "/messages/" (js/encodeURIComponent mid))
                     {:headers {"Authorization" (str "Bearer " key)}})]
        (if-not (:ok r)
          (die! (str "read failed (" (:status r) "): " (:text r)))
          (let [m (get-in r [:json :data])]
            (println "from:   " (:from m))
            (println "subject:" (:subject m))
            (println "at:     " (:received_at m))
            (println (apply str (repeat 60 "─")))
            (println (html->text (:html m)))))))))

(defn cmd-redeem [argv-link]
  (p/let [link (if (str/blank? argv-link) (read-stdin) argv-link)]
    (if (str/blank? link) (die! "no magic link on stdin") (finish-with-link link))))

;; ----------------------------------------------------------------- misc

(defn cmd-resend []
  (let [email (get-in (load-state) [:inbox :email] fallback-email)]
    (p/let [r (req "https://api.telnyx.com/v2/bot_signup/resend_magic_link"
                   {:method "POST" :body {:email email}})]
      (if (:ok r)
        (println "✓ resent to" email "—" (:text r) "\n(max 3 resends, 60s cooldown)")
        (die! (str "resend failed (" (:status r) "): " (:text r)))))))

(defn cmd-status []
  (let [st (load-state)]
    (println "state file:" state-file)
    (println "inbox:     " (or (:inbox st) "—"))
    (println "signup:    " (or (:signup st) "—"))
    (println "challenge: " (if-let [c (:challenge st)]
                             (str (:nonce c) " (" (:challenge_type c) ")") "—"))
    (println "keychain:")
    (println "  " inbox-key-service ":" (if (keychain-get inbox-key-service) "present" "absent"))
    (println "  " api-key-service   ":" (if (keychain-get api-key-service) "present" "absent"))))

;; ------------------------------------------------------------------ main

(let [[cmd a b] (drop 3 (js->clj (.-argv proc)))]
  (case cmd
    "inbox-create" (cmd-inbox-create a)
    "challenge"    (cmd-challenge)
    "signup"       (cmd-signup a b)
    "inbox-wait"   (cmd-inbox-wait)
    "inbox-list"   (cmd-inbox-list)
    "inbox-read"   (cmd-inbox-read a)
    "redeem"       (cmd-redeem a)
    "resend"       (cmd-resend)
    "status"       (cmd-status)
    (do (println "usage:")
        (println "  nbb scripts/telnyx-agent-signup.cljs inbox-create ACCEPT")
        (println "  nbb scripts/telnyx-agent-signup.cljs challenge")
        (println "  nbb scripts/telnyx-agent-signup.cljs signup '<answer>' ACCEPT")
        (println "  nbb scripts/telnyx-agent-signup.cljs inbox-wait")
        (println "  nbb scripts/telnyx-agent-signup.cljs inbox-list")
        (println "  nbb scripts/telnyx-agent-signup.cljs inbox-read '<message-id>'")
        (println "  echo '<link>' | nbb scripts/telnyx-agent-signup.cljs redeem")
        (println "  nbb scripts/telnyx-agent-signup.cljs resend")
        (println "  nbb scripts/telnyx-agent-signup.cljs status")
        (js/process.exit 2))))

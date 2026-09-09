#!/usr/bin/env nbb
;; x402_bot_pay.cljs — how an itonami CLI bot pays for inference and storage.
;;
;; ## What this closes
;;
;; The seller half of x402 has been live for a year: x402.nexus is our own
;; facilitator, murakumo prices inference and kotobase prices reads, and a 402
;; challenge comes back with everything a payer needs. The BUYER half was a
;; human: both existing tools say so in their own first lines — the nexus
;; example ("Private keys never enter this script. Transfer the required USDC
;; to payTo on Base. Retry with TX_HASH") and cloud-murakumo's dogfood tool
;; ("This tool NEVER holds a key, signs, or moves funds"). A bot could be told
;; a price and could not answer one.
;;
;; ## Credits, not USDC — and that is not a downgrade
;;
;; The USDC rail advertises the `transaction` scheme: broadcast a transfer
;; yourself, then present its hash. That means one on-chain transaction per
;; request, with gas, for a $0.001 read — and it means an unattended process
;; holding money. The credits rail settles in murakumo's append-only ledger:
;; labour-issued units, transferable between holders, non-redeemable for fiat
;; by design (ADR-2607995000 §1). A bot paying in credits is spending an
;; allowance INSIDE this economy. It is not moving funds, which is why this is
;; the rail a bot may drive without a human in the loop.
;;
;; ## Three refusals that must stay distinguishable
;;
;;   :allowance/unlisted          — this bot was never granted anything
;;   :allowance/daily-exhausted   — it was, and it has spent it
;;   insufficient-credits (ledger) — it is allowed and has no balance
;;
;; The third is the seller's answer, not ours, and it is the one that proves
;; the rail is reachable. Collapsing any of the three into "payment failed"
;; is how an unfunded bot and a forbidden one become the same incident.
;;
;; ## The order is reserve, then mint, then send
;;
;; The spend is written to the local ledger BEFORE a CACAO exists, for the same
;; reason the facilitator appends to the credits ledger before it proxies: a
;; record written after the effect is a record that is missing exactly when the
;; process dies at the wrong moment. If the ledger cannot be written, this
;; refuses to pay rather than paying unrecorded — a spend nobody can count is
;; not a cheaper spend.
;;
;; Usage (classpath is long because the pure layers live in their own repos):
;;
;;   CP="orgs/kotoba-lang/pay/src:orgs/kotoba-lang/org-chainagnostic-cacao/src:\
;; orgs/kotoba-lang/org-ietf-ed25519/src:orgs/kotoba-lang/org-ietf-cbor/src:\
;; orgs/kotoba-lang/authority/src:orgs/network-awai/local-murakumo/src"
;;   nbb --classpath "$CP" scripts/x402_bot_pay.cljs account <bot-id>
;;   nbb --classpath "$CP" scripts/x402_bot_pay.cljs policy  <bot-id>
;;   nbb --classpath "$CP" scripts/x402_bot_pay.cljs balance <bot-id>
;;   nbb --classpath "$CP" scripts/x402_bot_pay.cljs spent   <bot-id>
;;   nbb --classpath "$CP" scripts/x402_bot_pay.cljs probe   <bot-id> <url> [--body '<json>']
;;   nbb --classpath "$CP" scripts/x402_bot_pay.cljs pay     <bot-id> <url> [--body '<json>']
;;
;; `probe` reads the challenge and prints what WOULD be paid. It mints nothing.

(ns x402-bot-pay
  (:require [clojure.edn :as edn]
            [nbb.core :as nbb]
            [biscuit.wire :as wire]
            [biscuit.kotoba :as bk]
            [clojure.string :as str]
            [cacao.core :as cacao]
            [ed25519.core :as ed]
            [local-murakumo.write-gate :as wg]
            [pay.x402 :as x402]
            [pay.x402-buyer :as buyer]
            ["crypto" :as crypto]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]))

;; ── where things live ────────────────────────────────────────────────────

(def allowances-file
  "The ISSUANCE policy — what the operator has decided to grant. It is no
  longer what the payer trusts: that is the signed pass
  (`scripts/x402_bot_pass.cljs`), read by `read-pass` and folded by
  `biscuit.kotoba/->delegated`. This file is read here only to mint one and to
  answer `spent`/`policy` questions about what WOULD be granted."
  "manifest/bot-allowances.edn")

(defn gftd-dir [] (path/join (os/homedir) ".itonami"))
(defn seed-file [] (path/join (gftd-dir) "itonami-bot-payer.seed"))
(defn spend-ledger-file [] (path/join (gftd-dir) "x402-bot-spend.ledger.edn"))

;; ── identity: one secret, every bot's account derived from it ────────────
;;
;; Same shape as ADR-2608200400's workforce dids: SHA-256(seed || 0x00 || id)
;; into Ed25519, so re-provisioning a bot reproduces its account rather than
;; stranding its balance under a name nothing answers to any more. A separate
;; seed FILE from the workforce one on purpose — these are a different
;; population on a different host, and a compromise of one should not be a
;; compromise of the other.

(def ^:private seed-bytes 32)

(defn fleet-seed
  "The one secret every bot's account derives from. Created 0600 on first use
  and never regenerated silently: regenerating renames every bot at once, and
  a renamed bot is a bot whose credits it can no longer reach."
  []
  (let [f (seed-file)]
    (if (fs/existsSync f)
      (let [b (fs/readFileSync f)]
        (when (= seed-bytes (.-length b)) b))
      (let [b (crypto/randomBytes seed-bytes)]
        (fs/mkdirSync (gftd-dir) #js {:recursive true})
        (fs/writeFileSync f b #js {:mode 384}) ; 384 = 0600; nbb has no octal literal
        b))))

(defn derive-seed [fleet bot-id]
  (-> (crypto/createHash "sha256")
      (.update fleet)
      (.update (js/Buffer.from #js [0]))
      (.update bot-id "utf8")
      (.digest)))

(defn account
  "This bot's credits account: its own `did:key`. Nil when there is no seed —
  a bot without an identity has no account, and inventing one would name a
  balance nobody can sign for."
  [bot-id]
  (when-let [fleet (fleet-seed)]
    (ed/did-key-from-seed (derive-seed fleet bot-id))))

;; ── allowance: pure, so the question 'may this bot spend this' is a table ──

(defn read-allowances
  "The registry, or nil when it cannot be read. Nil is NOT an empty registry:
  every caller below treats it as `unreadable` and refuses, because a policy
  file that failed to load must not answer like one that permits nothing —
  those two are the same verdict and different bugs."
  ([] (read-allowances allowances-file))
  ([f] (try (edn/read-string (fs/readFileSync f "utf8")) (catch :default _ nil))))

(def ^:private seller-prefix "kotoba://credits/")

(defn resource->seller
  "The seller a granted resource names, or nil. A resource of another shape
  confers no seller rather than being trimmed into one — `kotoba://graph/x`
  is not a credits account and must not become one by string surgery."
  [r]
  (when (and (string? r) (str/starts-with? r seller-prefix))
    (let [s (subs r (count seller-prefix))]
      (when (re-matches #"[a-z0-9][a-z0-9._-]{0,62}" s) s))))

(defn spend-limits
  "The `limit` facts of a verified token, folded MINIMUM-wins.

  `biscuit.kotoba/->delegated` reads `cap` and `before` and nothing else, so
  these confer no authority on their own — which is exactly why they are safe
  to carry. What they need is a fold, and the fold has to be one-directional
  for the same reason block folding is: a later block may LOWER a cap and must
  never raise one. Attenuation that can widen is not attenuation.

  A limit nobody set is nil, not infinity — the caller decides what an absent
  cap means, and here it means the offer is refused rather than uncapped."
  [token-model]
  (reduce (fn [acc [p n v]]
            (if (and (= 'limit p) (string? n) (number? v))
              (update acc (keyword n) (fn [prev] (if prev (min prev v) v)))
              acc))
          {}
          (mapcat :block/facts (:biscuit/blocks token-model))))

(defn pass-policy
  "A VERIFIED pass -> the `pay.x402-buyer` policy it confers, or nil.

  This is where the allowance stopped being a file. `manifest/bot-allowances.edn`
  is now the ISSUANCE policy — what the operator decided to grant — and what
  the payer trusts is the grant inside a signed token, folded by
  `biscuit.kotoba/->delegated` and never by this namespace. There is one
  covering decision in this workspace and it is `kotoba-lang/authority`'s;
  writing a second one here is what that library exists to prevent.

  nil when the token confers no `credits-spend` grant. `buyer/plan` refuses a
  nil policy with `:buyer/no-policy`, so the deny-by-default floor is the same
  one it was when the file was the authority — it is now merely a floor
  somebody can verify.

  It refuses an UNVERIFIED pass here rather than only at the call site. The
  function that turns a credential into authority is the one that must not do
  it for a credential nobody checked — a guard that lives only in the caller is
  one refactor away from a second caller without it, and the test for it passed
  against this function for the wrong reason until the guard moved here.

  ⚠ This binds a WELL-BEHAVED buyer. The pass is read here, by the payer, and
  no seller checks it yet; a bot that skipped this function is stopped by its
  balance and nothing else. What the token buys today is that the allowance
  cannot be widened by editing a checkout, can be narrowed offline by a holder
  with none of our keys, and can be verified by anyone holding the root public
  key. Seller-side enforcement is murakumo's decision, not this file's."
  [{:keys [grants limits] :pass/keys [verified?]} rails]
  (let [grants (when verified? grants)          ; an unverified credential confers nothing
        resources (into #{} (mapcat :grant/resources)
                        (filter #(= :credits-spend (:grant/kind %)) grants))
        sellers (into #{} (keep resource->seller) resources)
        per-call (:per-call limits)]
    (when (and (seq sellers) per-call)
      {:schemes #{"credits"}
       :networks (set (map first rails))
       :assets (set (map second rails))
       :pay-tos sellers
       :caps (into {} (map (fn [r] [r per-call])) rails)
       :prefer (vec rails)})))



(defn root-public-hex
  "The spend authority's PUBLIC key, hex, or nil.

  Only the public half, and from its own file: a verifier that could read the
  signing seed would be able to mint what it is checking. `x402_bot_pass.cljs`
  writes this beside the seed; nil here means this host was never given the
  authority to check against, which is `:pass/unverifiable` — neither a grant
  nor a denial."
  []
  (let [f (path/join (gftd-dir) "bot-spend-authority.pub")]
    (when (fs/existsSync f)
      (some-> (fs/readFileSync f "utf8") str/trim not-empty))))

(defn read-pass
  "The bot's spend pass, decoded, verified, and folded.

  -> `{:pass/verified? bool :pass/reason kw :grants [...] :limits {...}
       :holder did}`

  Decode and verify are separate calls in `biscuit.wire`, and its docstring
  warns that converting without verifying decodes an attacker's facts. They
  are joined here, once, so there is no route by which an unverified model
  reaches `->delegated`.

  A missing pass, an unreadable one and a host with no root public key are
  three different answers. Only one of them means the bot was refused."
  [bot-id]
  (let [f (path/join (gftd-dir) "x402-bot-pass" (str bot-id ".token"))]
    (cond
      (not (fs/existsSync f)) {:pass/verified? false :pass/reason :pass/absent
                               :detail (str "no pass at " f)}
      :else
      (let [b64 (str/trim (fs/readFileSync f "utf8"))
            pub (root-public-hex)]
        (cond
          (nil? pub) {:pass/verified? false :pass/reason :pass/unverifiable
                      :detail "no bot-spend-authority.pub on this host"}
          :else
          (try
            (let [padded (-> b64 (str/replace "-" "+") (str/replace "_" "/"))
                  bytes (vec (js/Buffer.from padded "base64"))
                  token (wire/decode-token bytes)
                  pub-bytes (vec (js/Buffer.from pub "hex"))
                  v (wire/verify token pub-bytes
                                 (fn [key payload sig]
                                   (try (ed/verify (js/Uint8Array.from (clj->js key))
                                                   (js/Uint8Array.from (clj->js payload))
                                                   (js/Uint8Array.from (clj->js sig)))
                                        (catch :default _ false))))
                  model (wire/token->model token)]
              (if-not (:ok? v)
                {:pass/verified? false :pass/reason :pass/bad-signature :detail (:reason v)}
                (let [d (bk/->delegated model #{:credits-spend})]
                  {:pass/verified? true
                   :grants (:grants d)
                   :rejected (:grant/rejected d)
                   :holder (:grant/holder d)
                   :limits (spend-limits model)})))
            (catch :default e
              {:pass/verified? false :pass/reason :pass/malformed
               :detail (str e)})))))))

(defn- day-of [iso] (some-> iso (subs 0 10)))

(defn spent-on
  "Credits this bot has already committed on `day` (UTC), from ledger rows.

  Counts RESERVED rows, not settled ones. A reservation whose outcome is
  unknown — the process died between minting and the seller's answer — may
  well have been charged, and counting only the confirmed ones would let every
  crash raise the day's limit."
  [rows bot-id day]
  (->> rows
       (filter #(and (= bot-id (:bot %)) (= day (day-of (:at %)))
                     (= :reserved (:phase %))))
       (map #(or (:amount %) 0))
       (reduce + 0)))

(defn allowance-verdict
  "May this bot commit `amount` more credits today? Pure.

  `limits` comes from the SIGNED pass (`spend-limits`), not from a file. Both
  halves of an allowance now live in the same credential: WHICH sellers, in the
  `cap` facts the library folds, and HOW MUCH, in the `limit` facts this
  namespace folds minimum-wins. Half a policy in a token and half in a
  checkout would be an allowance nobody could state.

  An ABSENT limit refuses, which is the opposite of the usual convention and
  is deliberate: a pass that says nothing about how much authorises nothing,
  and reading silence as `no cap` is how a token with a dropped fact becomes
  unlimited spending.

  The refusals stay distinct — a caller that cannot tell an unpassed bot from
  an exhausted one cannot tell a broken deployment from a working one
  enforcing its limits."
  [limits rows bot-id day amount]
  (let [per-call (:per-call limits)
        per-day (:per-day limits)
        already (spent-on rows bot-id day)]
    (cond
      (nil? limits) {:ok? false :reason :allowance/no-pass}
      (not (and (number? amount) (pos? amount))) {:ok? false :reason :allowance/bad-amount}
      (nil? per-call) {:ok? false :reason :allowance/no-per-call-limit}
      (> amount per-call) {:ok? false :reason :allowance/over-per-call
                           :limit per-call :amount amount}
      (nil? per-day) {:ok? false :reason :allowance/no-per-day-limit}
      (> (+ already amount) per-day)
      {:ok? false :reason :allowance/daily-exhausted
       :limit per-day :spent already :amount amount}
      :else {:ok? true :spent already :remaining (- per-day already amount)})))

;; ── the local spend ledger ───────────────────────────────────────────────

(defn read-ledger
  "Append-only rows, one EDN map per line. A line that does not read is
  skipped and REPORTED — a corrupt row silently dropped would lower today's
  spend, which is the direction that spends more."
  []
  (let [f (spend-ledger-file)]
    (if-not (fs/existsSync f)
      []
      (->> (str/split-lines (fs/readFileSync f "utf8"))
           (remove str/blank?)
           (keep (fn [l]
                   (try (edn/read-string l)
                        (catch :default _
                          (binding [*print-fn* *print-err-fn*]
                            (println "WARNING: unreadable spend-ledger row skipped:"
                                     (subs l 0 (min 80 (count l)))))
                          nil))))
           vec))))

(defn append-ledger!
  "Append one row. Throws rather than returning a value on failure: the caller
  must not treat an unrecorded spend as a recorded one."
  [row]
  (fs/mkdirSync (gftd-dir) #js {:recursive true})
  (fs/appendFileSync (spend-ledger-file) (str (pr-str row) "\n")))

;; ── the network half ─────────────────────────────────────────────────────

(defn json-encode
  "The host's JSON serializer. `pay` does not serialize JSON — `encode-header`
  takes an already-serialized string — and passing it the map produced base64
  of a print form, which the facilitator could not parse and which it reported
  as no payment at all (measured 2026-08-31, fixed on both sides)."
  [x] (js/JSON.stringify (clj->js x)))

(defn- utc-second
  "`YYYY-MM-DDTHH:MM:SSZ` — the ONLY shape `cacao.edge.verify` parses, and the
  verifier a Cloudflare Worker runs.

  `.toISOString` emits milliseconds. `cacao.core/verify` compares instants as
  strings and tolerates them, so such a CACAO looks fine to a JVM/Node test and
  is refused at the edge with `invalid CACAO iat` — a message about the
  timestamp, on a token whose signature and scope are both correct. Measured
  2026-08-31: every CACAO this payer minted carried one, so every payment it
  made would have been refused. `cacao.core/mint` now normalizes too
  (kotoba-lang/org-chainagnostic-cacao 2327183e); this stays because the value
  is also what the local ledger row records, and two places that disagree about
  when a spend happened is its own defect."
  [^js d]
  (str (.replace (.toISOString d) #"\.\d{3}Z$" "Z")))

(defn- now-iso [] (utc-second (js/Date.)))

(defn- plus-seconds [secs]
  (utc-second (js/Date. (+ (.getTime (js/Date.)) (* 1000 secs)))))

(defn- fetch-json
  "-> {:status n :body <parsed or string> :headers h}. Never throws on a
  non-2xx: a 402 is the protocol working."
  [url {:keys [method body headers]}]
  (-> (js/fetch url (clj->js (cond-> {:method (or method "GET")
                                      :headers (merge {"accept" "application/json"} headers)}
                               body (assoc :body body)
                               body (update :headers assoc "content-type" "application/json"))))
      (.then (fn [^js r]
               (-> (.text r)
                   (.then (fn [t]
                            {:status (.-status r)
                             :body (try (js->clj (js/JSON.parse t) :keywordize-keys true)
                                        (catch :default _ t))})))))))

(defn balance!
  "-> `{:reachable? true :credits n :known? bool}` or `{:reachable? false}`.

  Three answers, not two. An unreachable ledger, an account the ledger has
  never seen, and an account that has spent everything all reduce to `0` if
  you let them, and only one of those is a bot that may not spend. `:known?`
  is false for an account with no entry: its balance is 0 and the reason is
  that nothing has ever been credited to it, which is what an unfunded bot
  looks like on its first day."
  [ledger acct]
  (-> (fetch-json (str ledger "/infer/credits") {})
      (.then (fn [{:keys [status body]}]
               (if-not (and (= 200 status) (map? body))
                 {:reachable? false :status status}
                 (let [v (get body (keyword acct) (get body acct))]
                   {:reachable? true :known? (some? v) :credits (or v 0)}))))
      (.catch (fn [e] {:reachable? false :error (str e)}))))

(defn transfer-cacao
  "A CACAO authorising exactly ONE transfer of `amount` credits to `to`.

  Scoped and single-use by construction: `write-gate/transfer-resource` puts
  the (recipient, amount) pair in the SIWE resources the signer reads, and the
  nonce is recorded as consumed by the ledger. What the facilitator receives
  authorises this payment and cannot be re-aimed at another recipient, raised
  to another amount, or replayed."
  [bot-id {:keys [to amount aud ttl-seconds]
           :or {aud "did:web:api.murakumo.cloud" ttl-seconds 300}}]
  (when-let [fleet (fleet-seed)]
    (let [seed (derive-seed fleet bot-id)]
      (cacao/mint {:seed seed
                   :aud aud
                   :iat (now-iso)
                   :exp (plus-seconds ttl-seconds)
                   :nonce (.toString (crypto/randomBytes 16) "hex")
                   :statement (str "Pay " amount " credits to " to " for one x402 request.")
                   :resources [(wg/transfer-resource to amount)]}))))

(defn- challenge-of
  "The decoded 402 body, or nil. x402 answers 402 with the challenge as the
  body; anything else is not a payment problem and must not be treated as one."
  [{:keys [status body]}]
  (when (and (= 402 status) (map? body)) body))

(defn plan-for
  "-> {:challenge c :plan p} for a request that came back 402, or a refusal.

  The policy comes from the VERIFIED pass. An unverified one yields no policy
  at all rather than a narrow one, so `buyer/plan` refuses with
  `:buyer/no-policy` — the same floor as before, now standing on something
  somebody can check."
  [pass registry bot-id response]
  (if-let [ch (challenge-of response)]
    {:challenge ch
     :plan (buyer/plan ch (pass-policy pass (:allowance/rails registry)))}
    {:refuse :buyer/not-a-challenge :status (:status response)}))

(defn pay!
  "Do the whole thing: request, read the challenge, decide, reserve, mint, retry.

  Returns a map that always says which of the stages it reached. A caller that
  cannot tell 'never asked' from 'asked and was refused' from 'paid and the
  seller still said no' cannot act on any of them."
  [{:keys [bot-id url method body registry dry-run?]}]
  (let [registry (or registry (read-allowances))
        ledger (:allowance/ledger registry)
        acct (account bot-id)
        pass (read-pass bot-id)]
    (-> (fetch-json url {:method method :body body})
        (.then
         (fn [first-response]
           (let [{:keys [challenge plan refuse]} (plan-for pass registry bot-id first-response)]
             (cond
               refuse (js/Promise.resolve {:stage :no-challenge :refuse refuse
                                           :status (:status first-response)
                                           :body (:body first-response)})
               (:refuse plan) (js/Promise.resolve {:stage :planned :refuse (:refuse plan)
                                                   :rejected (:rejected plan)
                                                   :pass (select-keys pass [:pass/verified? :pass/reason])
                                                   :challenge challenge})
               :else
               (let [chosen (:pay plan)
                     amount (:amount chosen)
                     day (day-of (now-iso))
                     verdict (allowance-verdict (when (:pass/verified? pass) (:limits pass))
                                                (read-ledger) bot-id day amount)]
                 (cond
                   (not (:ok? verdict))
                   (js/Promise.resolve {:stage :allowance :refuse (:reason verdict)
                                        :detail verdict :pay chosen})

                   dry-run?
                   (js/Promise.resolve {:stage :dry-run :pay chosen :account acct
                                        :allowance verdict})

                   :else
                   (let [row {:bot bot-id :at (now-iso) :phase :reserved
                              :rail (buyer/rail chosen) :amount amount
                              :to (:pay-to chosen) :url url :account acct}]
                     ;; Reserve BEFORE minting. A throw here stops the payment,
                     ;; which is the intended direction: a spend nobody can
                     ;; count is not a cheaper spend.
                     (append-ledger! row)
                     (let [minted (transfer-cacao bot-id {:to (:pay-to chosen) :amount amount})
                           payment (buyer/credits-payment chosen {:payer acct
                                                                  :cacao (:cacao-b64 minted)})
                           header (buyer/payment-header payment json-encode)]
                       (if (nil? header)
                         (do (append-ledger! (assoc row :phase :abandoned
                                                    :at (now-iso)
                                                    :reason (:refuse payment)))
                             (js/Promise.resolve {:stage :envelope :refuse (:refuse payment)}))
                         (-> (fetch-json url {:method method :body body
                                              :headers {x402/v1-payment-header header}})
                             (.then (fn [paid]
                                      (append-ledger!
                                       (assoc row :phase (if (< (:status paid) 300)
                                                           :settled :rejected)
                                              :at (now-iso) :status (:status paid)))
                                      {:stage :paid :status (:status paid)
                                       :body (:body paid) :pay chosen
                                       :account acct :ledger ledger}))))))))))))
        (.catch (fn [e] {:stage :error :error (str e)})))))

;; ── CLI ──────────────────────────────────────────────────────────────────

(defn- flag [argv k]
  (let [i (.indexOf (to-array argv) k)]
    (when (>= i 0) (nth argv (inc i) nil))))

(defn- out [x] (println (pr-str x)))

(defn -main [& argv]
  (let [[cmd bot-id url] argv
        registry (read-allowances)
        body (flag argv "--body")
        method (or (flag argv "--method") (if body "POST" "GET"))]
    (case cmd
      "account" (out {:bot bot-id :account (account bot-id)})
      "policy"  (let [pass (read-pass bot-id)]
                  (out {:bot bot-id
                        :pass (select-keys pass [:pass/verified? :pass/reason :holder :limits])
                        :policy (when (:pass/verified? pass)
                                  (pass-policy pass (:allowance/rails registry)))}))
      "pass"    (out (read-pass bot-id))
      "spent"   (out {:bot bot-id :day (day-of (now-iso))
                      :spent (spent-on (read-ledger) bot-id (day-of (now-iso)))})
      "balance" (-> (balance! (:allowance/ledger registry) (account bot-id))
                    (.then (fn [b] (out (merge {:bot bot-id :account (account bot-id)} b)))))
      "probe"   (-> (pay! {:bot-id bot-id :url url :method method :body body
                           :registry registry :dry-run? true})
                    (.then out))
      "pay"     (-> (pay! {:bot-id bot-id :url url :method method :body body
                           :registry registry})
                    (.then out))
      (do (println "usage: x402_bot_pay.cljs <account|policy|pass|spent|balance|probe|pay> <bot-id> [url] [--body json]")
          (js/process.exit 2)))))

;; Only when this file is the one that was invoked. Requiring it — which the
;; test does, and which any bot loop wanting `pay!` as a function will do —
;; must not run the CLI: a namespace that executes its command line on load
;; answers `usage` and exits 2 in the middle of whatever required it.
(when (= nbb/*file* (nbb/invoked-file))
  (apply -main *command-line-args*))

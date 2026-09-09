#!/usr/bin/env nbb
;; x402_bot_pass.cljs — a bot's spend allowance as a Biscuit, not as a file.
;;
;;   nbb --classpath "<see below>" scripts/x402_bot_pass.cljs root
;;   nbb … scripts/x402_bot_pass.cljs issue   <bot-id> [--ttl-seconds 3600]
;;   nbb … scripts/x402_bot_pass.cljs inspect <bot-id>
;;
;; ## Why this exists
;;
;; `manifest/bot-allowances.edn` decided whether a bot could spend and how
;; much, and it is an unsigned EDN file that only the payer reads. Under this
;; workspace's own division that is a DELEGATION, and delegation is a Biscuit
;; decided by `kotoba-lang/authority` — ADR-2608291700 ("CACAO は session の
;; 起点として残り delegation からは降りている") and ADR-2608312900-hyakka
;; ("支払いは authorization ではない。x402 が決済し、biscuit が『その支払いが
;; 何を買ったか』を運び、authority が決める").
;;
;; So the file stops being the authority and becomes the ISSUANCE POLICY: what
;; the operator has decided to grant. What the bot carries is this pass, and
;; what the payer trusts is the verified grant inside it.
;;
;; ## What this changes, and what it does not
;;
;; Changes: the allowance is signed, so it cannot be widened by editing a file
;; in a checkout; it is attenuable offline by a holder who has none of our keys
;; (`biscuit.kotoba/->delegated` folds later blocks as narrowing only); and it
;; is verifiable by anyone holding the root PUBLIC key, which is the property a
;; file has never had.
;;
;; Does NOT change: who enforces it. Today the payer is still the only party
;; that reads the pass, so the cap binds a well-behaved buyer. Seller-side
;; enforcement needs the ledger to accept the pass, which is a separate
;; decision on murakumo's side. Saying the cap is "enforced" now would be the
;; overclaim this file exists to avoid.
;;
;; ## Expiry is the bound
;;
;; Biscuit has no revocation and this does not invent one (ADR-2608180200
;; measured that and decided against a list nobody would publish). A pass is
;; short-lived and a leaked one expires rather than being recalled.

(ns x402-bot-pass
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [biscuit.wire :as wire]
            [biscuit.kotoba :as bk]
            [ed25519.core :as ed]
            [nbb.core :as nbb]
            [x402-bot-pay :as pay]
            ["crypto" :as crypto]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]))

(def kind
  "The one kind this authority issues. A token carrying anything else lands in
  `:grant/rejected` — it does not fail open and it does not throw."
  :credits-spend)

(def kinds #{kind})

(def seller-prefix "kotoba://credits/")

(defn seller->resource [seller] (str seller-prefix seller))

(defn resource->seller
  "The seller name a granted resource names, or nil. A resource this authority
  did not mint the shape of confers no seller rather than being trimmed into
  one — `kotoba://graph/murakumo` is not a credits account."
  [r]
  (when (and (string? r) (str/starts-with? r seller-prefix))
    (let [s (subs r (count seller-prefix))]
      (when (re-matches #"[a-z0-9][a-z0-9._-]{0,62}" s) s))))

;; ── the root key ─────────────────────────────────────────────────────────

(defn gftd-dir [] (path/join (os/homedir) ".itonami"))
(defn root-seed-file [] (path/join (gftd-dir) "bot-spend-authority.seed"))
(defn pass-dir [] (path/join (gftd-dir) "x402-bot-pass"))
(defn pass-file [bot-id] (path/join (pass-dir) (str bot-id ".token")))

(defn root-seed
  "The fleet's spend authority. Created 0600 on first use and never
  regenerated: rotating it invalidates every pass already issued.

  A SEPARATE secret from the payer's identity seed. One names bots, this one
  says what a bot may spend, and a compromise of either should not be a
  compromise of both."
  []
  (let [f (root-seed-file)]
    (if (fs/existsSync f)
      (let [b (fs/readFileSync f)] (when (= 32 (.-length b)) b))
      (let [b (crypto/randomBytes 32)]
        (fs/mkdirSync (gftd-dir) #js {:recursive true})
        (fs/writeFileSync f b #js {:mode 384})   ; 384 = 0600
        b))))

(defn root-public-file [] (path/join (gftd-dir) "bot-spend-authority.pub"))

(defn root-public!
  "The public half, hex, WRITTEN beside the seed so a verifier never has to
  open the seed to check a pass.

  `ed/hexify` takes the typed array, not a vector of its bytes — a vector
  hexifies to the empty string, which would publish a root key of \"\" and make
  every pass unverifiable while looking like a key was published."
  []
  (when-let [seed (root-seed)]
    (let [hex (ed/hexify (ed/pubkey-from-seed seed))
          f (root-public-file)]
      (fs/mkdirSync (gftd-dir) #js {:recursive true})
      (fs/writeFileSync f hex #js {:mode 420})   ; 420 = 0644, it is public
      hex)))

;; ── crypto seams (injected, per biscuit.wire's contract) ─────────────────

(defn- ->u8 [xs] (js/Uint8Array.from (clj->js (vec xs))))

(defn sign-bytes [seed payload]
  (vec (ed/sign seed (->u8 payload))))

(defn verify-bytes [key payload sig]
  (try (ed/verify (->u8 key) (->u8 payload) (->u8 sig))
       (catch :default _ false)))

;; ── issuance policy (the file the operator edits) ────────────────────────

(defn issuance-policy
  "What the operator has decided to grant this bot, or nil.

  nil is NOT an empty grant: `issue` refuses rather than minting a pass that
  confers nothing, because a pass that grants nothing and a bot nobody granted
  anything are different situations and only one of them is worth a token."
  [registry bot-id]
  (when-let [a (get-in registry [:allowance/bots bot-id])]
    {:sellers (vec (sort (:allowance/sellers registry)))
     :per-call (:credits/per-call a)
     :per-day (:credits/per-day a)}))

(defn pass-facts
  "The authority block of a freshly issued pass.

  `cap` and `before` are the two predicates `biscuit.kotoba/->delegated`
  reads. `holder` is provenance. `limit` facts confer nothing to the library —
  an unread fact cannot widen a grant — and are folded by `spend-limits` on
  the way out, minimum-wins, so a later block can lower a cap and never raise
  one."
  [{:keys [sellers expires holder per-call per-day]}]
  (cond-> (vec (for [s sellers] ['cap (name kind) (seller->resource s)]))
    expires  (conj ['before expires])
    holder   (conj ['holder holder])
    per-call (conj ['limit "per-call" per-call])
    per-day  (conj ['limit "per-day" per-day])))

(defn- utc-second [^js d]
  (str (.replace (.toISOString d) #"\.\d{3}Z$" "Z")))

(defn mint
  "-> {:token <url-safe base64> :facts […]} or {:refuse reason}."
  [{:keys [bot-id sellers per-call per-day ttl-seconds]
    :or {ttl-seconds 3600}}]
  (if-let [sk (root-seed)]
    (let [_ (root-public!)                 ; a pass nobody can verify is not a pass
          holder (pay/account bot-id)
          expires (utc-second (js/Date. (+ (.getTime (js/Date.)) (* 1000 ttl-seconds))))
          facts (pass-facts {:sellers sellers :expires expires :holder holder
                             :per-call per-call :per-day per-day})
          next-secret (vec (crypto/randomBytes 32))
          next-public (vec (ed/pubkey-from-seed (->u8 next-secret)))
          token (wire/encode-authority-token
                 {:facts facts :root-private-key sk
                  :next-secret next-secret :next-public-key next-public
                  :sign-fn sign-bytes})]
      {:token (-> (.toString (js/Buffer.from (->u8 token)) "base64")
                  (str/replace "+" "-") (str/replace "/" "_") (str/replace #"=+$" ""))
       :facts facts :holder holder :expires expires})
    {:refuse :pass/no-root-key}))

(defn -main [& argv]
  (let [[cmd bot-id] argv
        ttl (some-> (second (drop-while #(not= "--ttl-seconds" %) argv)) js/parseInt)
        registry (pay/read-allowances)]
    (case cmd
      "root" (println (pr-str {:root-public (root-public!)
                               :seed-file (root-seed-file)
                               :public-file (root-public-file)}))

      "issue"
      (if-let [p (issuance-policy registry bot-id)]
        (let [r (mint (merge {:bot-id bot-id :ttl-seconds (or ttl 3600)} p))]
          (if (:refuse r)
            (do (println (pr-str r)) (js/process.exit 1))
            (do (fs/mkdirSync (pass-dir) #js {:recursive true})
                (fs/writeFileSync (pass-file bot-id) (:token r) #js {:mode 384})
                (println (pr-str {:bot bot-id :holder (:holder r)
                                  :expires (:expires r)
                                  :sellers (:sellers p)
                                  :per-call (:per-call p) :per-day (:per-day p)
                                  :written (pass-file bot-id)})))))
        (do (println (pr-str {:refuse :allowance/unlisted :bot bot-id
                              :detail "manifest/bot-allowances.edn grants this bot nothing"}))
            (js/process.exit 1)))

      "inspect"
      (let [r (pay/read-pass bot-id)]
        (println (pr-str r))
        (when-not (:pass/verified? r) (js/process.exit 1)))

      (do (println "usage: x402_bot_pass.cljs <root|issue|inspect> [bot-id] [--ttl-seconds n]")
          (js/process.exit 2)))))

(when (= nbb/*file* (nbb/invoked-file))
  (apply -main *command-line-args*))

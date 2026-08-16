#!/usr/bin/env nbb
;; verify-kip-quorum.cljs — does each :final KIP actually carry a quorum?
;;
;;   nbb --classpath "orgs/kotoba-lang/kip/src:orgs/kotoba-lang/kagami/src" \
;;       scripts/verify-kip-quorum.cljs [--kip <id>] [--all]
;;
;; Without this, `:kip/status :final` is a string somebody typed. This is what
;; makes it a claim about keys.
;;
;; ## Why it lives in the superproject
;;
;; It needs three things that are never in one repo: the registry
;; (orgs/kotoba-lang/kip), the did:key codec (orgs/kotoba-lang/kagami), and the
;; allow-list — manifest/fleet-keys.edn :canonical, the same policy
;; `kagami govern` reads for canonical pin advances. The policy lives here, so
;; the verifier does too. kotoba-lang/kip stays dependency-free and injectable;
;; `kotoba.kip.quorum` holds the judgement and this file holds the wiring.
;;
;; ## Exit codes
;;
;;   0  every :final KIP verifies (or there are none, said explicitly)
;;   1  a :final KIP does not carry a valid quorum
;;   2  COULD NOT ANSWER — a sibling checkout, the policy, or the registry is missing

(require '["node:crypto" :as crypto]
         '["node:fs" :as fs]
         '["node:path" :as path]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def argv (vec *command-line-args*))
(defn- flag? [f] (some #{f} argv))
(defn- flag-value [f] (some (fn [[a b]] (when (= a f) b)) (partition 2 1 argv)))

(defn- sh-out [cmd args]
  (let [{:keys [status stdout]}
        (js->clj (.spawnSync (js/require "node:child_process") cmd (clj->js args)
                             #js {:encoding "utf8"})
                 :keywordize-keys true)]
    (when (zero? (or status 1)) (str/trim (str stdout)))))

(def root (or (sh-out "git" ["rev-parse" "--show-toplevel"]) "."))

(defn- say [& xs] (println (str/join "\t" xs)))
(defn- bail! [code & msg]
  (say "RESULT" (case code 0 "pass" 1 "fail" "could-not-answer"))
  (doseq [m msg] (println (str "  " m)))
  (js/process.exit code))

;; ----------------------------------------------------------- the three inputs
(def kip-root (path/join root "orgs" "kotoba-lang" "kip"))
(def kagami-src (path/join root "orgs" "kotoba-lang" "kagami" "src"))

;; --policy-file / --kips point the verifier at a scratch policy and registry.
;; They exist so this check can be shown FAILING without signing a real KIP with
;; a real governance key: a throwaway keypair, a throwaway allow-list, and a
;; tampered document. Verifying that a verifier can reject is not something to
;; take on faith, and doing it with the live keys would turn a demonstration
;; into a governance act.
(def keys-file (or (flag-value "--policy-file") (path/join root "manifest" "fleet-keys.edn")))

(doseq [[p what fix]
        [[kip-root "the KIP registry" "west update --fetch smart kip"]
         [kagami-src "kagami's did:key codec" "west update --fetch smart kagami"]
         [keys-file "the governance allow-list" "manifest/fleet-keys.edn is missing from this checkout"]]]
  (when-not (fs/existsSync p)
    (bail! 2 (str what " is not here: " p) (str "  " fix))))

(when (or (flag-value "--policy-file") (flag-value "--kips"))
  (say "MODE" "scratch"
       (str "policy=" (or (flag-value "--policy-file") "real")
            " kips=" (or (flag-value "--kips") "real")
            " — NOT a statement about the real registry")))

;; Required late and guarded, so a missing sibling produces the message above
;; rather than a classpath stack trace. If this throws, the --classpath is wrong
;; and the message says which namespace could not be found.
(require '[kagami.did :as did]
         '[kotoba.kip.quorum :as quorum])

(def policy
  (let [k (edn/read-string (fs/readFileSync keys-file "utf8"))]
    (:canonical k)))

(when (or (empty? (:allow policy)) (nil? (:threshold policy)))
  (bail! 2 "manifest/fleet-keys.edn has no usable :canonical {:allow .. :threshold ..}"))

;; ------------------------------------------------------------------- crypto
;; Same construction kagami uses (bin/kagami.cljs node-verify): raw ed25519
;; pubkey wrapped in the DER SPKI header, verified with node's one-shot API.
(def ^:private spki-prefix "302a300506032b6570032100")

(defn- pubkey-object [hex]
  (crypto/createPublicKey
   #js {:key (js/Buffer.from (str spki-prefix hex) "hex") :format "der" :type "spki"}))

(defn node-verify [pubkey-hex payload sig-hex]
  (try (crypto/verify nil (js/Buffer.from payload "utf8")
                      (pubkey-object pubkey-hex)
                      (js/Buffer.from sig-hex "hex"))
       (catch :default _ false)))

(defn node-sha256 [s]
  (-> (crypto/createHash "sha256") (.update s "utf8") (.digest "hex")))

;; A self-check on the primitive itself. If node's ed25519 were unavailable or
;; wired wrong, every signature would come back false and the run would look
;; like a fleet of bad signatures rather than a broken verifier — the same value
;; for two very different facts. One ephemeral keypair, signed and verified, and
;; a deliberately corrupted signature that must NOT verify.
(let [{:keys [publicKey privateKey]}
      (js->clj (crypto/generateKeyPairSync "ed25519") :keywordize-keys true)
      msg "kip-quorum self-check"
      sig (-> (crypto/sign nil (js/Buffer.from msg "utf8") privateKey) (.toString "hex"))
      der (.export publicKey #js {:format "der" :type "spki"})
      hex (-> der (.subarray (- (.-length der) 32)) (.toString "hex"))]
  (when-not (node-verify hex msg sig)
    (bail! 2 "ed25519 self-check failed to verify a signature it just made — the verifier is broken, not the KIPs"))
  (when (node-verify hex msg (str "00" (subs sig 2)))
    (bail! 2 "ed25519 self-check accepted a corrupted signature — refusing to verify anything"))
  (say "SELF-CHECK" "ok" "(ed25519 sign/verify round-trip, and a corrupted signature rejected)"))

(def ctx {:policy policy :verify-fn node-verify
          :did->pubkey-hex did/did->pubkey-hex :hash-fn node-sha256})

(say "POLICY" (quorum/policy-summary policy))

;; ----------------------------------------------------------------- registry
(def blob-fields #{:kip/surfaces :kip/quorum :kip/requires :kip/evidence :kip/discussion})

(defn- unblob [v]
  (if (string? v)
    (let [p (try (edn/read-string v) (catch :default _ nil))] (if (coll? p) p v))
    v))

(def kips
  (let [d (or (flag-value "--kips") (path/join kip-root "kips"))]
    (if-not (fs/existsSync d)
      (bail! 2 (str d " is missing"))
      (->> (fs/readdirSync d)
           (filter #(str/ends-with? % ".edn"))
           sort
           (keep (fn [f]
                   (let [tx (try (edn/read-string (fs/readFileSync (path/join d f) "utf8"))
                                 (catch :default _ nil))]
                     (when (and (vector? tx) (map? (first tx)))
                       [f (reduce-kv (fn [m k v] (assoc m k (if (blob-fields k) (unblob v) v)))
                                     {} (dissoc (first tx) :db/id))]))))
           vec))))

(say "SCANNED" (count kips))
(when (zero? (count kips))
  (bail! 2 "no readable KIPs — refusing to report on an empty registry"))

(def targets
  (cond->> kips
    (flag-value "--kip") (filter #(= (flag-value "--kip") (:kip/id (second %))))
    (not (flag? "--all")) (filter #(= :final (:kip/status (second %))))))

(say "FINAL-KIPS" (count targets))

(when (zero? (count targets))
  (bail! 0 "no :final KIPs to verify."
         "That is the current state of the registry, not a verification of anything:"
         "nothing has been admitted, so nothing needed a quorum. Pass --all to check"
         "signatures on KIPs that are not yet Final."))

;; ------------------------------------------------------------------ verdict
(def results
  (for [[f kip] targets]
    (let [r (quorum/admit kip (:kip/quorum kip) ctx)]
      (say (if (= :admit (:verdict r)) "ADMIT" "REJECT")
           (:kip/id kip) f
           (str (count (:valid-signers r)) "/" (:threshold policy) " valid"
                " of " (:claimed r) " claimed")
           (if (seq (:reasons r)) (pr-str (:reasons r)) ""))
      (doseq [line (quorum/explain kip (:kip/quorum kip) ctx)]
        (println (str "    " line)))
      (assoc r :id (:kip/id kip) :file f))))

(def rejected (filterv #(= :reject (:verdict %)) results))

(say "SUMMARY" (str (- (count results) (count rejected)) " admitted, "
                    (count rejected) " rejected"))

(if (seq rejected)
  (apply bail! 1 (str (count rejected) " :final KIP(s) do not carry a valid quorum:")
         (map #(str (:id %) "  " (pr-str (:reasons %))
                    (when (seq (:invalid-from-allowed %))
                      (str "  invalid-from " (pr-str (:invalid-from-allowed %)))))
              rejected))
  (bail! 0))

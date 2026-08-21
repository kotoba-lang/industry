#!/usr/bin/env nbb
;; kip-sign.cljs — sign a KIP for admission to :final.
;;
;;   nbb --classpath "orgs/kotoba-lang/kip/src:orgs/kotoba-lang/kagami/src" \
;;       scripts/kip-sign.cljs --kip kip-0001 (--kagi <name> | --key <pem>) [--write]
;;
;; Prints the signature entry. With --write it splices it into the KIP's
;; :kip/quorum and leaves :kip/status alone — signing is not admitting. Run
;; scripts/verify-kip-quorum.cljs afterwards; only when that says ADMIT should
;; anyone set :kip/status :final.
;;
;; ## Two signatures, two people
;;
;; The policy is 2-of-3 over manifest/fleet-keys.edn :canonical. All three keys
;; are reachable from one kagi vault, so nothing here can enforce that two
;; different humans signed — this tool checks that two different KEYS did. That
;; is the honest limit of a single-owner keyring and it should not be papered
;; over: a quorum satisfied by one party holding every key is a record of what
;; was signed, not an independent review.
;;
;;   --kagi fleet-gov1        governance key 1
;;   --kagi fleet-gov2        governance key 2
;;   --kagi fleet-owner-root  owner root
;;
;; Names only, one at a time. Never enumerate the vault.

(require '["node:child_process" :as cp]
         '["node:crypto" :as crypto]
         '["node:fs" :as fs]
         '["node:path" :as path]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def argv (vec *command-line-args*))
(defn- flag? [f] (some #{f} argv))
(defn- flag-value [f] (some (fn [[a b]] (when (= a f) b)) (partition 2 1 argv)))

(defn- die! [code & msg]
  (doseq [m msg] (println (str "kip-sign: " m)))
  (js/process.exit code))

(def root
  (str/trim (str (cp/execSync "git rev-parse --show-toplevel" #js {:encoding "utf8"}))))

(def kip-id (or (flag-value "--kip") (die! 2 "--kip <id> is required (e.g. --kip kip-0001)")))
(def kips-dir (or (flag-value "--kips") (path/join root "orgs" "kotoba-lang" "kip" "kips")))

(when-not (fs/existsSync kips-dir) (die! 2 (str kips-dir " does not exist")))
(require '[kagami.did :as did] '[kotoba.kip.quorum :as quorum])

;; ------------------------------------------------------------------- the key
(def pem
  (cond
    (flag-value "--key") (fs/readFileSync (flag-value "--key") "utf8")
    (flag-value "--kagi")
    (let [bin (path/join root "orgs" "kotoba-lang" "kagi" "bin" "kagi")]
      (when-not (fs/existsSync bin) (die! 2 (str "kagi binary not found at " bin)))
      (try
        (str/trim-newline
         (str (cp/execFileSync bin #js ["get" (flag-value "--kagi") "--compartment" "personal"]
                               #js {:encoding "utf8" :timeout 120000})))
        (catch :default e
          ;; One named item, by identifier. If it is not there, say so and stop —
          ;; do not go looking through the vault for something that might be it.
          (die! 2 (str "kagi get " (flag-value "--kagi") " failed: " (.-message e))
                "Retrieve one key by name. Do not enumerate the vault."))))
    :else (die! 2 "one of --kagi <name> or --key <pem-file> is required")))

(defn- node-sign [payload]
  (-> (crypto/sign nil (js/Buffer.from payload "utf8") (crypto/createPrivateKey pem))
      (.toString "hex")))

(defn- pubkey-hex []
  (let [pub (crypto/createPublicKey (crypto/createPrivateKey pem))
        der (.export pub #js {:format "der" :type "spki"})]
    (-> der (.subarray (- (.-length der) 32)) (.toString "hex"))))

(defn- node-sha256 [s]
  (-> (crypto/createHash "sha256") (.update s "utf8") (.digest "hex")))

(def signer (did/pubkey-hex->did (pubkey-hex)))

;; ------------------------------------------------------------------ the KIP
(def blob-fields #{:kip/surfaces :kip/quorum :kip/requires :kip/evidence :kip/discussion})
(defn- unblob [v]
  (if (string? v)
    (let [p (try (edn/read-string v) (catch :default _ nil))] (if (coll? p) p v))
    v))

(def kip-file
  (or (->> (fs/readdirSync kips-dir)
           (filter #(str/ends-with? % ".edn"))
           (map #(path/join kips-dir %))
           (filter (fn [f]
                     (let [tx (try (edn/read-string (fs/readFileSync f "utf8"))
                                   (catch :default _ nil))]
                       (= kip-id (:kip/id (first tx))))))
           first)
      (die! 2 (str "no KIP with :kip/id " kip-id " in " kips-dir))))

(def raw (edn/read-string (fs/readFileSync kip-file "utf8")))
(def kip (reduce-kv (fn [m k v] (assoc m k (if (blob-fields k) (unblob v) v)))
                    {} (dissoc (first raw) :db/id)))

(when-not (= :standards (:kip/track kip))
  (die! 2 (str kip-id " is on the " (:kip/track kip) " track, which does not take a quorum."
               " Only :standards KIPs are admitted by signature.")))

;; --------------------------------------------------------------- the payload
(def entry
  (quorum/sign node-sha256 node-sign signer
               (.toISOString (js/Date.)) kip))

(println "kip      :" kip-id (str "(" (path/basename kip-file) ")"))
(println "signer   :" signer)
(println "in allow :"
         (let [allow (:allow (:canonical (edn/read-string
                                          (fs/readFileSync (path/join root "manifest" "fleet-keys.edn") "utf8"))))]
           (if (contains? allow signer) "yes" "NO — this signature will not count")))
(println "digest   :" (quorum/content-digest node-sha256 kip))
(println)
(println (pr-str entry))

;; The digest covers the whole document except the signatures, so a signature
;; made now is void the moment anyone edits the prose. That is the point, and it
;; is worth saying at the moment of signing rather than at the moment of
;; verifying.
(println)
(println ";; This signature covers the document as it is right now. Any edit to")
(println ";; any field except :kip/quorum voids it and every other signature.")

(when (flag? "--write")
  (let [existing (vec (remove #(= signer (:signer %)) (:kip/quorum kip)))
        updated (conj existing entry)
        text (fs/readFileSync kip-file "utf8")
        ;; Replace only the :kip/quorum blob, leaving the rest of the file
        ;; byte-identical — this file is hand-written prose and round-tripping
        ;; it through the reader would reflow all of it.
        ;; The blob is an EDN string containing escaped quotes, so the naive
        ;; "[^\"]*" stops at the first \" inside it. Measured 2026-08-16: with
        ;; that pattern the FIRST signature wrote fine and the SECOND appended
        ;; past the truncated match, corrupting the file — a bug only the
        ;; two-signature path shows, which is the path that matters.
        pattern (js/RegExp. ":kip/quorum\\s+\"(?:\\\\.|[^\"\\\\])*\"" "")
        replaced (.replace text pattern (str ":kip/quorum " (pr-str (pr-str updated))))]
    (when (= replaced text)
      (die! 2 "could not find a :kip/quorum \"...\" blob to update — edit the file by hand"))
    ;; Never hand back a file the reader cannot parse, even if that means
    ;; refusing to write: a corrupted KIP is worse than an unsigned one.
    (let [check (try (edn/read-string replaced) (catch :default e {::err (.-message e)}))]
      (when (or (::err check) (not (vector? check)) (not= 1 (count check)))
        (die! 2 (str "refusing to write: the result does not re-read as one-entity tx-data"
                     (when (::err check) (str " (" (::err check) ")")))))
      (let [round (unblob (:kip/quorum (first check)))]
        (when-not (= (count round) (count updated))
          (die! 2 (str "refusing to write: re-read gives " (count round)
                       " signature(s), expected " (count updated))))))
    (fs/writeFileSync kip-file replaced)
    (println)
    (println (str "wrote " (path/relative root kip-file) " — " (count updated) " signature(s)"))
    (println "Now run: nbb --classpath \"orgs/kotoba-lang/kip/src:orgs/kotoba-lang/kagami/src\" \\")
    (println "           scripts/verify-kip-quorum.cljs --all --kip" kip-id)
    (println "Only set :kip/status :final once that says ADMIT.")))

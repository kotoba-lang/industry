#!/usr/bin/env nbb
;; Does manifest/ipni-publisher.edn describe the publisher that is actually
;; publishing?
;;
;;   nbb scripts/verify-ipni-publisher-record.cljs [--selftest]
;;
;; Measured 2026-08-31 (ADR-2608311800): it did not. The file named the
;; 2026-08-27 key while the live head had been signed by another since
;; 2026-08-30, a fourth identity had never been written down at all, and the
;; two retrieval candidates had swapped -- the one marked :ready 404'd the
;; objects the live chain announces while the one marked :broken served them.
;;
;; None of that was anyone's carelessness. There was simply nothing that
;; compared the record to the wire, so the record could only ever be as fresh
;; as the last person who happened to look.
;;
;; Exit is three-valued:
;;   0  every check ran and agreed
;;   1  every check ran, at least one disagreed
;;   2  a check could not run -- REFUSING to report agreement it did not verify.
;;      A network this cannot reach is not a publisher that is correct.
(ns verify-ipni-publisher-record
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["fs" :as fs]
            ["child_process" :as cp]
            ["crypto" :as crypto]))

(def manifest-path "manifest/ipni-publisher.edn")
(def head-url "https://ipni.kotobase.net/ipni/v1/head")
(def providers-url "https://cid.contact/providers")
(def publisher-host "ipni.kotobase.net")

;; ── encodings ───────────────────────────────────────────────────────────────

(def b58-alphabet "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")
(def b36-alphabet "0123456789abcdefghijklmnopqrstuvwxyz")
(def b32-alphabet "abcdefghijklmnopqrstuvwxyz234567")

(defn- base-n
  "Byte vector -> base-`alphabet` string, by repeated division on the digits.

  Deliberately not BigInt: nbb has no `js-mod`, and the arithmetic here is a
  handful of bytes. Leading zero bytes become leading zero DIGITS, which is
  what makes `1` the prefix of every Ed25519 peer id."
  [bytes alphabet]
  (let [radix (count alphabet)
        leading (count (take-while zero? bytes))
        digits (reduce (fn [acc b]
                         (let [carried (reduce (fn [{:keys [out carry]} d]
                                                 (let [v (+ (* d 256) carry)]
                                                   {:out (conj out (rem v radix))
                                                    :carry (quot v radix)}))
                                               {:out [] :carry b}
                                               acc)]
                           (loop [out (:out carried) c (:carry carried)]
                             (if (zero? c) out (recur (conj out (rem c radix)) (quot c radix))))))
                       []
                       bytes)]
    (str (str/join (repeat leading (first alphabet)))
         (str/join (map #(nth alphabet %) (reverse digits))))))

(defn- base32 [bytes]
  ;; RFC 4648 lower-case, no padding -- the multibase 'b' body.
  (loop [bs (seq bytes) acc 0 bits 0 out ""]
    (if (and (empty? bs) (zero? bits))
      out
      (if (>= bits 5)
        (recur bs (bit-and acc (dec (bit-shift-left 1 (- bits 5)))) (- bits 5)
               (str out (nth b32-alphabet (bit-and (bit-shift-right acc (- bits 5)) 31))))
        (if (empty? bs)
          (str out (nth b32-alphabet (bit-and (bit-shift-left acc (- 5 bits)) 31)))
          (recur (rest bs) (+ (* acc 256) (first bs)) (+ bits 8) out))))))

(defn identity-multihash
  "0x00 <len> <protobuf pubkey> — the identity multihash a libp2p peer id is."
  [pubkey-bytes]
  (into [0x00 (count pubkey-bytes)] pubkey-bytes))

(defn peer-id [pubkey-bytes] (base-n (identity-multihash pubkey-bytes) b58-alphabet))

(defn ipns-name [pubkey-bytes]
  (str "k" (base-n (into [0x01 0x72] (identity-multihash pubkey-bytes)) b36-alphabet)))

(defn cid-of-dag-cbor [^js buf]
  (let [digest (vec (.digest (.update (crypto/createHash "sha256") buf)))]
    (str "b" (base32 (into [0x01 0x71 0x12 0x20] digest)))))

;; ── the wire ────────────────────────────────────────────────────────────────

(defn- curl
  "Bytes, or nil when the request could not be made. nil is :unmeasured, never
  a disagreement: a network this cannot reach is not a publisher that is wrong."
  [url & {:keys [binary?]}]
  (try
    (let [out (cp/execSync (str "curl -sSL --max-time 25 " (pr-str url))
                           #js {:encoding (if binary? "buffer" "utf8")
                                :stdio #js ["pipe" "pipe" "pipe"]
                                :maxBuffer (* 32 1024 1024)})]
      (if binary? out (str out)))
    (catch :default _ nil)))

(defn head-pubkey
  "The Ed25519 protobuf public key the live head is signed with, or nil."
  []
  (when-let [body (curl head-url)]
    (try
      (let [d (js/JSON.parse body)
            b64 (some-> d (aget "pubkey") (aget "/") (aget "bytes"))]
        (when b64 (vec (js/Buffer.from b64 "base64"))))
      (catch :default _ nil))))

(defn indexer-publishers
  "Publisher ids the indexer has for this publisher host, or nil."
  []
  (when-let [body (curl providers-url)]
    (try
      (->> (js->clj (js/JSON.parse body))
           (keep (fn [p]
                   (let [pub (get p "Publisher")
                         addrs (map str (get pub "Addrs"))]
                     (when (some #(str/includes? % publisher-host) addrs)
                       {:peer-id (get pub "ID")
                        :provider (get-in p ["AddrInfo" "ID"])
                        :last (get p "LastAdvertisementTime")}))))
           vec)
      (catch :default _ nil))))

(defn linked-cids
  "CIDv1 dag-cbor/raw links embedded in a block — enough to find something the
  advertised host is supposed to be able to serve."
  [^js buf]
  (let [b (vec buf)]
    (->> (range (- (count b) 36))
         (keep (fn [i]
                 (when (and (= 0x01 (nth b i))
                            (contains? #{0x71 0x55} (nth b (inc i)))
                            (= 0x12 (nth b (+ i 2)))
                            (= 0x20 (nth b (+ i 3))))
                   (str "b" (base32 (subvec b i (+ i 36)))))))
         distinct vec)))

(defn serves-verifiably?
  "Does `host` return bytes that re-hash to `cid`? :unmeasured when no answer."
  [host cid]
  (if-let [buf (curl (str "https://" host "/ipfs/" cid "?format=raw") :binary? true)]
    (= cid (cid-of-dag-cbor buf))
    :unmeasured))

;; ── checks ──────────────────────────────────────────────────────────────────

(def findings (atom []))
(def unmeasured (atom []))

(def ^:private findings? (atom false))

(defn- check! [id ok? detail]
  (cond
    (= :unmeasured ok?) (do (swap! unmeasured conj id)
                            (println (str "  ?    " (name id) "  —  " detail)))
    ok? (println (str "  ok   " (name id) "  —  " detail))
    :else (do (swap! findings conj {:check id :detail detail})
              (println (str "  FAIL " (name id) "  —  " detail))
              ;; manifest/orgs-detectors.edn's :findings :protocol. Keyed by the
              ;; check id so a reworded message does not become a new finding.
              (when @findings?
                (println (str "FINDING\thigh\t" (name id) "\t" detail))))))

(defn selftest
  "The derivation must reproduce a pair the file already records. A derivation
  that cannot do that may not be used to judge a new one."
  []
  (let [known-peer "12D3KooWGmq2x23J59LmqyBT5ik8pK9gEz7N3Y4vdJidezf7Usz3"
        known-ipns "k51qzi5uqu5dirbsrgpb1iyrwbzlqgjoq006b7hda5nr2ah4cxez8atsxwc00c"
        ;; decode the known peer id back to its multihash, drop 0x00 <len>
        n (reduce (fn [acc c] (+ (* acc (js/BigInt 58))
                                 (js/BigInt (str/index-of b58-alphabet c))))
                  (js/BigInt 0) known-peer)
        hex (.toString n 16)
        hex (if (odd? (count hex)) (str "0" hex) hex)
        mh (into [0x00] (vec (js/Buffer.from hex "hex")))
        pk (vec (drop 2 mh))]
    (println "selftest — the derivation against a pair the file already holds\n")
    (check! :peer-id-round-trips (= known-peer (peer-id pk))
            (str "derived " (subs (peer-id pk) 0 20) "…"))
    (check! :ipns-name-round-trips (= known-ipns (ipns-name pk))
            (str "derived " (subs (ipns-name pk) 0 20) "…"))
    (check! :cid-of-known-bytes
            (= "bafyreibmvkn2wghwulquddzzwi2qysbleoz5eaqyr3ghjcszb47vhep2ve"
               (cid-of-dag-cbor (js/Buffer.from "kotobase" "utf8")))
            (str "CIDv1 dag-cbor of the 8 bytes 'kotobase', computed independently in python: " (subs (cid-of-dag-cbor (js/Buffer.from "kotobase" "utf8")) 0 20) "…"))))

(defn -main [& args]
  (reset! findings? (boolean (some #{"--findings"} args)))
  (if (some #{"--selftest"} args)
    (do (selftest)
        (if (seq @findings) 1 0))
    (let [m (edn/read-string (str (fs/readFileSync manifest-path "utf8")))
          declared (:ipni.publisher/peer-id m)
          history (:ipni.publisher/identity-history m)
          candidates (:ipni.publisher/retrieval-candidates m)
          pk (head-pubkey)
          pubs (indexer-publishers)]
      (println "ipni publisher record vs the wire\n")
      ;; Evidence floor: the registry matches SCANNED\t[1-9][0-9]* before it will
      ;; believe an exit code. A record with no history entries and no candidates
      ;; has nothing to disagree with and must not read as agreement.
      (println (str "SCANNED\t" (+ (count history) (count candidates))
                    "\t(" (count history) " history entries, "
                    (count candidates) " retrieval candidates)\n"))
      (when (zero? (+ (count history) (count candidates)))
        (swap! unmeasured conj :nothing-to-check))

      (check! :record-names-the-live-signer
              (if (nil? pk) :unmeasured (= declared (peer-id pk)))
              (if (nil? pk)
                "the live head could not be read"
                (str "head is signed by " (subs (peer-id pk) 0 20) "…, record says "
                     (subs (str declared) 0 20) "…")))

      (check! :ipns-name-matches-the-peer-id
              (if (nil? pk) :unmeasured (= (:ipni.publisher/ipns-name m) (ipns-name pk)))
              (if (nil? pk) "the live head could not be read"
                  "the recorded pair derives from one key"))

      (check! :history-accounts-for-every-indexed-identity
              (if (nil? pubs) :unmeasured
                  (empty? (remove (set (map :peer-id history)) (map :peer-id pubs))))
              (if (nil? pubs) "the indexer could not be read"
                  (let [missing (remove (set (map :peer-id history)) (map :peer-id pubs))]
                    (if (empty? missing)
                      (str (count pubs) " indexed identities, all recorded")
                      (str "not in :identity-history: " (str/join ", " missing))))))

      (check! :one-entry-is-marked-current
              (= 1 (count (filter :current? history)))
              (str (count (filter :current? history)) " entries marked :current?"))

      (check! :the-current-entry-is-the-declared-one
              (= declared (:peer-id (first (filter :current? history))))
              "the :current? entry and :ipni.publisher/peer-id agree")

      ;; A :ready candidate must actually serve something the live chain names.
      ;; The head advertisement is the one object every publisher must have.
      (let [ad (curl head-url)
            head-cid (try (-> (js/JSON.parse ad) (aget "head") (aget "/"))
                          (catch :default _ nil))]
        (doseq [c (filter #(= :ready (:status %)) candidates)]
          (let [r (if head-cid (serves-verifiably? (:host c) head-cid) :unmeasured)]
            (check! (keyword (str "ready-candidate-serves-" (:host c)))
                    r
                    (if (= :unmeasured r)
                      (str (:host c) " could not be measured")
                      (str (:host c) (if (true? r)
                                       " returned bytes that re-hash to "
                                       " did NOT return bytes matching ")
                           head-cid))))))

      (println)
      (cond
        (seq @unmeasured)
        (do (println (str "  REFUSING to report agreement: unmeasured — "
                          (str/join ", " (map name @unmeasured))))
            2)
        (seq @findings)
        (do (println (str "  " (count @findings) " finding(s)")) 1)
        :else (do (println "  clean") 0)))))

(let [code (apply -main *command-line-args*)]
  (set! (.-exitCode js/process) code))

#!/usr/bin/env nbb
;; Does manifest/ipni-publisher.edn describe the publisher that is actually
;; publishing?
;;
;;   nbb --classpath orgs/kotoba-lang/io-ipni-specs/src \
;;       scripts/verify-ipni-publisher-record.cljs [--selftest]
;;
;; The classpath is for `ipni.metadata`, which reads the advertisement's
;; Metadata field. Required rather than reimplemented here on purpose: this
;; repository has already paid for two implementations of that one wire field.
;; `ipni-drain` hardcodes the gateway metadata as three bytes, the library
;; emits two, IPNI.md says two, and the wire carries three -- which is exactly
;; what the last two checks below exist to see.
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

(def ^:private ipni-metadata-src
  "The one file the Metadata checks need, so their absence can be measured.

  `require` in nbb cannot be caught -- a missing namespace kills the process
  before a `try` sees it -- so the gate is the file, not the exception. Without
  it two checks are :unmeasured and the other twelve still run."
  "orgs/kotoba-lang/io-ipni-specs/src/ipni/metadata.cljc")

;; Its own top-level form, not folded into the `def` below. nbb's `require`
;; resolves asynchronously and is only awaited between top-level forms, so a
;; `require` inside the `def` returns before the namespace is loaded and every
;; `resolve` after it is nil -- which is indistinguishable from a missing
;; checkout. Measured: with the checkout present and correct, the checks still
;; reported :unmeasured.
(when (fs/existsSync ipni-metadata-src)
  (require '[ipni.metadata]))

(def ipni-metadata
  "The two fns, or nil when either is absent.

  `resolve` returns nil for a symbol a loaded namespace does not define, so a
  checkout that is merely OLD looks exactly like one that is present -- and
  then calling nil crashes the whole script. Measured: the west pin was two
  commits behind `decode-sequence` and the run died mid-way with `Cannot read
  properties of null`, which is a worse answer than either :unmeasured or a
  finding. Both symbols are required together; a partial answer is no answer."
  (let [read-ipq (resolve 'ipni.metadata/read-ipq)
        decode-sequence (resolve 'ipni.metadata/decode-sequence)]
    (when (and read-ipq decode-sequence)
      {:read-ipq read-ipq :decode-sequence decode-sequence})))

(def manifest-path
  ;; Overridable so the negative direction can be shown on a doctored copy
  ;; rather than asserted. A check nobody has watched fail is a check nobody
  ;; knows the failure mode of.
  (or (aget (.-env js/process) "IPNI_MANIFEST_PATH")
      "manifest/ipni-publisher.edn"))
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

(defn- base32-decode
  "The multibase 'b' body back to bytes. Encoding without decoding is how a
  CID becomes something only a human can read: the indexer indexes
  MULTIHASHES, and getting from one to the other is this direction."
  [s]
  (loop [cs (seq s) acc 0 bits 0 out []]
    (if (empty? cs)
      out                                 ; trailing <8 bits are padding, dropped
      (let [i (str/index-of b32-alphabet (first cs))
            acc (+ (* acc 32) i) bits (+ bits 5)]
        (if (>= bits 8)
          (recur (rest cs) (bit-and acc (dec (bit-shift-left 1 (- bits 8)))) (- bits 8)
                 (conj out (bit-and (bit-shift-right acc (- bits 8)) 255)))
          (recur (rest cs) acc bits out))))))

(defn cid->multihash-b58
  "The base58btc multihash inside a base32 CIDv1 -- the key cid.contact's
  uncached lookup surface is addressed by.

  Skips the version byte and the codec varint: entries carry multihashes,
  not CIDs, so `raw` and `dag-cbor` CIDs of the same bytes share one key."
  [cid]
  (when (str/starts-with? cid "b")
    (let [b (base32-decode (subs cid 1))]
      (when (= 0x01 (first b))
        (loop [i 1]
          (if (>= (nth b i) 0x80)
            (recur (inc i))
            (base-n (vec (drop (inc i) b)) b58-alphabet)))))))

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

(defn multihash-providers
  "Who cid.contact says is providing `b58`, on the surface that is NOT cached.

  /cid/{cid} and /routing/v1/providers/{cid} both sit behind CloudFront, and
  a cached miss and a real absence are the same bytes -- which is how a
  removal was once read as still-present for three minutes. /multihash/
  carries no age header and reflected a new advertisement in 15s.

  nil means the question could not be asked. [] means it was asked and the
  index holds nothing for that multihash -- a different fact from a removal,
  and the caller must not conflate them."
  [b58]
  ;; 404 is an ANSWER -- "indexed nothing for this multihash" -- and must not
  ;; arrive looking like an unreachable network, or the floor check below can
  ;; never fire for the reason it names. So the status is read, not inferred
  ;; from whether a body parsed.
  (let [url (str "https://cid.contact/multihash/" b58)
        out (try (str (cp/execSync
                       (str "curl -sSL --max-time 25 -w '\\n%{http_code}' " (pr-str url))
                       #js {:encoding "utf8" :stdio #js ["pipe" "pipe" "pipe"]}))
                 (catch :default _ nil))
        [body status] (when out
                        (let [i (str/last-index-of out "\n")]
                          [(subs out 0 i) (str/trim (subs out (inc i)))]))]
    (cond
      (nil? out) nil                      ; could not ask
      (= "404" status) []                 ; asked; the index holds nothing
      (not= "200" status) nil
      :else
      (try
        (->> (get (js->clj (js/JSON.parse body)) "MultihashResults")
           (mapcat #(get % "ProviderResults"))
           (map (fn [p]
                  {:id (get-in p ["Provider" "ID"])
                   :addrs (vec (get-in p ["Provider" "Addrs"]))
                   :context (try (str (js/Buffer.from (get p "ContextID") "base64"))
                                 (catch :default _ nil))}))
             vec)
        (catch :default _ nil)))))

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

(defn advertisement-metadata
  "The `Metadata` byte string out of a DAG-CBOR advertisement block, or nil.

  A targeted read rather than a CBOR decoder: find the map key -- CBOR text of
  length 8, `0x68` then \"Metadata\" -- and take the byte string after it. Only
  the immediate (`0x40`-`0x57`) and one-byte-length (`0x58`) forms are read,
  because a Metadata field longer than 255 bytes would mean something this
  reader has not been shown, and guessing would be worse than saying so.

  nil is :unmeasured all the way up. It is never \"no metadata\", which IPNI.md
  gives its own meaning: an advertisement with no Metadata is an address update."
  [^js buf]
  (let [b (vec buf)
        k (into [0x68] (map #(.charCodeAt % 0)) "Metadata")
        kn (count k)
        n (count b)]
    (loop [i 0]
      (cond
        (> (+ i kn 1) n) nil
        (= k (subvec b i (+ i kn)))
        (let [h (nth b (+ i kn))]
          (cond
            (<= 0x40 h 0x57) (let [len (- h 0x40) st (+ i kn 1)]
                               (when (<= (+ st len) n) (subvec b st (+ st len))))
            (= h 0x58) (let [len (nth b (+ i kn 1)) st (+ i kn 2)]
                         (when (<= (+ st len) n) (subvec b st (+ st len))))
            :else nil))
        :else (recur (inc i))))))

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

(defn check-advertised-protocols!
  "What the live advertisement says this provider speaks.

  IPNI.md, Metadata: the field is a uvarint protocol identifier and
  protocol-specific bytes, `repeated for additional supported protocols`, in
  increasing order. So announcing a second transport is ONE advertisement with
  a longer Metadata -- not a second advertisement and not a second context id.

  These two checks were written before the publisher was changed, and were red
  when written. That is the point: adding the IPQ metadata and forgetting to
  add it produce the same successful publish, the same indexer 200 and the same
  provider lookup, so nothing outside the bytes can tell them apart."
  [head-cid retrieval-host]
  ;; `retrieval-host`, not `publisher-host`. Measured while writing this:
  ;; ipni.kotobase.net is classified as the publisher plane and answers every
  ;; path with its surface descriptor, so `/ipfs/<cid>` there returns JSON and
  ;; the extractor finds no Metadata field -- which is indistinguishable from an
  ;; advertisement that has none. The host to read the block from is the one the
  ;; check above has just proven serves it.
  (let [ad-bytes (when (and head-cid retrieval-host)
                   (curl (str "https://" retrieval-host "/ipfs/" head-cid "?format=raw")
                         :binary? true))
        md (some-> ad-bytes advertisement-metadata)
        decoded (when (and md ipni-metadata) ((:decode-sequence ipni-metadata) md))
        ipq (when (and md ipni-metadata) ((:read-ipq ipni-metadata) md))]
    (check! :advertisement-metadata-is-well-formed
            (cond (nil? ipni-metadata) :unmeasured
                  (nil? retrieval-host) :unmeasured
                  (nil? md) :unmeasured
                  :else (boolean (:ok? decoded)))
            (cond
              (nil? ipni-metadata)
              (str ipni-metadata-src " is missing, too old to carry decode-sequence, "
                   "or not on the classpath (--classpath orgs/kotoba-lang/io-ipni-specs/src)")
              (nil? retrieval-host) "no :ready retrieval candidate to read the block from"
              (nil? md) "the advertisement's Metadata field could not be read"
              (:ok? decoded)
              (str (count (:entries decoded)) " protocol(s): "
                   (str/join ", " (map #(or (:name %) (str (:protocol %)))
                                       (:entries decoded))))
              :else (str "Metadata does not parse as a protocol sequence: "
                         (name (:reason decoded))
                         (if (:protocol decoded)
                           (str " at protocol " (:protocol decoded))
                           ""))))
    (check! :advertisement-announces-ipq
            (cond (nil? ipni-metadata) :unmeasured
                  (nil? retrieval-host) :unmeasured
                  (nil? md) :unmeasured
                  :else (boolean (:ok? ipq)))
            (cond
              (nil? ipni-metadata) "ipni.metadata is not on the classpath"
              (nil? retrieval-host) "no :ready retrieval candidate"
              (nil? md) "the advertisement's Metadata field could not be read"
              (:ok? ipq) (str "IPQ/" (:profile ipq) " is announced")
              :else (str "not announced: " (name (:reason ipq))
                         (if (:protocols ipq)
                           (str " (found " (str/join ", " (:protocols ipq)) ")")
                           ""))))))

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
            (str "CIDv1 dag-cbor of the 8 bytes 'kotobase', computed independently in python: " (subs (cid-of-dag-cbor (js/Buffer.from "kotobase" "utf8")) 0 20) "…"))
    ;; The lookup key is derived, not copied out of the manifest -- a
    ;; derivation checked against the file it is about proves only that two
    ;; copies agree. Anchor it on bytes instead: sha256 of the three bytes
    ;; "Any" is the digest inside the witness CID, and `shasum -a 256` says
    ;; 2b505597daa736f13c2910c260e8deb1af3b20ffe375eb5e01a003e92f541db9.
    (let [digest (.toString (.digest (.update (crypto/createHash "sha256")
                                              (js/Buffer.from "Any" "utf8")))
                            "hex")
          witness "bafkreiblkbkzpwvhg3ytykiqyjqorxvrv45sb77doxvv4anaapus6va5xe"]
      (check! :witness-digest-is-sha256-of-its-content
              (= "2b505597daa736f13c2910c260e8deb1af3b20ffe375eb5e01a003e92f541db9" digest)
              "sha256 of the 3 bytes 'Any'")
      (check! :multihash-key-round-trips
              (= "QmRFjHhG3SkR1RnjwXZ7zz1zS5NpDsv6JCn5V4EkEzP8dv"
                 (cid->multihash-b58 witness))
              (str "base32 CIDv1 → base58btc multihash: "
                   (str (cid->multihash-b58 witness)))))

    ;; ── the metadata checks, in BOTH directions ──────────────────────────
    ;; Against the wire they can only be red today, because nothing has
    ;; announced IPQ yet. A check that has only ever been red says as little as
    ;; one that has only ever been green: neither has been shown to depend on
    ;; what it claims to measure. These are real DAG-CBOR advertisement blocks
    ;; built with io-ipld and pinned as bytes, differing in exactly one field --
    ;; nine Metadata bytes against three.
    (let [with-ipq (js/Buffer.from
                    (str "a5644973526df4684d6574616461746149a01200c092c001010168"
                     "50726f7669646572782c313244334b6f6f57466978747572655072"
                     "6f7669646572506565724964466f7253656c66746573744f6e6c79"
                     "694164647265737365738178252f646e73342f697066732e6b6f74"
                     "6f626173652e6e65742f7463702f3434332f687474707369436f6e"
                     "7465787449444401020304")
                    "hex")
          gateway-only (js/Buffer.from
                        (str "a5644973526df4684d6574616461746143a012006850726f766964"
                         "6572782c313244334b6f6f574669787475726550726f7669646572"
                         "506565724964466f7253656c66746573744f6e6c79694164647265"
                         "737365738178252f646e73342f697066732e6b6f746f626173652e"
                         "6e65742f7463702f3434332f687474707369436f6e746578744944"
                         "4401020304")
                        "hex")]
      (check! :fixture-with-ipq-reads-as-announced
              (if (nil? ipni-metadata) :unmeasured
                  (boolean (:ok? ((:read-ipq ipni-metadata)
                                  (advertisement-metadata with-ipq)))))
              (if (nil? ipni-metadata)
                "ipni.metadata is not on the classpath"
                "an advertisement whose Metadata carries IPQ reads as announced"))
      (check! :fixture-without-ipq-reads-as-absent
              (if (nil? ipni-metadata) :unmeasured
                  (= :not-ipq (:reason ((:read-ipq ipni-metadata)
                                        (advertisement-metadata gateway-only)))))
              (if (nil? ipni-metadata)
                "ipni.metadata is not on the classpath"
                "and one whose Metadata does not is :not-ipq, not :undecodable")))))

(defn -main [& args]
  (reset! findings? (boolean (some #{"--findings"} args)))
  (if (some #{"--selftest"} args)
    (do (selftest)
        (if (seq @findings) 1 0))
    (let [m (edn/read-string (str (fs/readFileSync manifest-path "utf8")))
          declared (:ipni.publisher/peer-id m)
          history (:ipni.publisher/identity-history m)
          candidates (:ipni.publisher/retrieval-candidates m)
          life (:ipni.publisher/lifecycle-proof m)
          disc (:ipni.publisher/discovery-witness m)
          known-ids (set (map :peer-id history))
          pk (head-pubkey)
          pubs (indexer-publishers)]
      (println "ipni publisher record vs the wire\n")
      ;; Evidence floor: the registry matches SCANNED\t[1-9][0-9]* before it will
      ;; believe an exit code. A record with no history entries and no candidates
      ;; has nothing to disagree with and must not read as agreement.
      (println (str "SCANNED\t" (+ (count history) (count candidates)
                                  (if life 2 0) (if disc 1 0))
                    "\t(" (count history) " history entries, "
                    (count candidates) " retrieval candidates, "
                    (if life "1 lifecycle proof over 2 multihashes" "no lifecycle proof")
                    (if disc ", 1 discovery witness" "")
                    ")\n"))
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
                           head-cid)))))
        (check-advertised-protocols!
         head-cid
         (:host (first (filter #(= :ready (:status %)) candidates)))))

      ;; ── the lifecycle proof ────────────────────────────────────────────
      ;; ADR-2608160300's success criterion ends with the advertisement going
      ;; away again. Two controls first: an absence read off an instrument
      ;; that cannot see presences is not evidence of anything.
      (when life
        (let [control (multihash-providers (get-in life [:control :multihash]))
              witness (multihash-providers (get-in life [:witness :multihash]))
              ours (fn [rs] (filter #(known-ids (:id %)) rs))
              control-ok (cond (nil? control) :unmeasured
                               :else (boolean (seq (ours control))))
              floor-ok (cond (nil? witness) :unmeasured
                             :else (boolean (seq witness)))]

          (check! :lifecycle-control-still-advertised control-ok
                  (cond (= :unmeasured control-ok) "cid.contact could not be read"
                        control-ok (str "this publisher is still returned for "
                                        (get-in life [:control :multihash]) " — the matcher can see us")
                        :else (str "no recorded identity is returned for the control multihash; "
                                   "an absence at the witness proves nothing while this is false")))

          (check! :lifecycle-witness-still-indexed floor-ok
                  (cond (= :unmeasured floor-ok) "cid.contact could not be read"
                        floor-ok (str (count witness) " provider(s) hold the witness multihash — "
                                      "the index has not forgotten it")
                        :else "the index returns nobody at all for the witness; a forgotten multihash is not a removal"))

          ;; Only meaningful once BOTH controls hold, so it inherits their
          ;; verdict rather than reporting a pass they did not license.
          (check! :lifecycle-removal-took-effect
                  (cond (or (= :unmeasured control-ok) (= :unmeasured floor-ok)) :unmeasured
                        (not (and control-ok floor-ok)) :unmeasured
                        :else (empty? (ours witness)))
                  (cond (or (= :unmeasured control-ok) (= :unmeasured floor-ok))
                        "not asked: a control was unmeasured"
                        (not (and control-ok floor-ok))
                        "not asked: a control failed, so absence here is uninterpretable"
                        (empty? (ours witness))
                        (str "gone from " (get-in life [:witness :multihash])
                             "; the remaining " (count witness) " are third parties")
                        :else (str "still listed: "
                                   (str/join ", " (map :id (ours witness)))
                                   " — :lifecycle-proof says :ok and the wire disagrees")))))

      ;; ── the discovery witness ──────────────────────────────────────────
      ;; 90-docs/ipni_maturity/probe.cljs scores :discoverable-as-provider
      ;; (weight 0.20, the heaviest axis) on the CID named here. A probe that
      ;; carried its own sample is a probe whose sample nobody re-measured;
      ;; that is exactly how the axis came to be scored on the CID the
      ;; lifecycle proof RETRACTS, and how it then reported 0 for eighteen
      ;; hours while ipni-h1 was green and passing the checks above.
      ;;
      ;; So the sample is recorded, and measured here for its own reason.
      ;; Both directions matter: a sample that stopped resolving to us, and a
      ;; sample that is the retracted witness again.
      ;; Recorded at all? Deleting the key would make the heaviest axis
      ;; :unknown, and the audit scores over MEASURED axes only -- so dropping
      ;; a red axis RAISES the mean. That must not be a silent move.
      (check! :discovery-witness-is-recorded
              (boolean (:multihash disc))
              (if (:multihash disc)
                (str "the probe's sample is named in the record: " (:multihash disc))
                (str "no :ipni.publisher/discovery-witness — probe.cljs scores its "
                     "heaviest axis :unknown, which drops it from the mean instead of "
                     "counting it")))

      (when disc
        (let [answered (multihash-providers (:multihash disc))
              ours (when answered (filter #(known-ids (:id %)) answered))]

          (check! :discovery-witness-is-not-the-lifecycle-witness
                  (if-not life :unmeasured
                          (not= (:multihash disc) (get-in life [:witness :multihash])))
                  (cond (not life) "no lifecycle proof recorded to compare against"
                        (= (:multihash disc) (get-in life [:witness :multihash]))
                        (str "the discovery sample IS the retracted witness ("
                             (:multihash disc) ") — the heaviest axis can never be green")
                        :else "the two samples are different multihashes, as they must be"))

          (check! :discovery-witness-still-advertised
                  (cond (nil? answered) :unmeasured
                        :else (boolean (seq ours)))
                  (cond (nil? answered) "cid.contact could not be read"
                        (seq ours)
                        (str (count ours) " of this publisher's recorded identities answer for "
                             (:multihash disc) " — the probe's sample is real")
                        (empty? answered)
                        (str "the index holds nothing at all for " (:multihash disc)
                             " — the probe is scoring its heaviest axis on a multihash nobody indexes")
                        :else
                        (str (count answered) " provider(s) answer for " (:multihash disc)
                             " and none is ours — the probe's sample no longer belongs to us")))))

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

#!/usr/bin/env nbb
;; Publish one IPNI advertisement for content this operator holds, and prove
;; the indexer can reach it.
;;
;;   NODE_PATH=orgs/kotoba-lang/io-ipld/node_modules \
;;   nbb --classpath "orgs/kotoba-lang/io-ipld/src:orgs/kotoba-lang/io-multiformats/src:\
;;   orgs/kotoba-lang/io-ipni-specs/src:orgs/kotoba-lang/org-ietf-ed25519/src" \
;;     scripts/ipni-advertise.cljs <content-cid>... [--execute]
;;       [--ephemeral-detached]
;;
;; Dry run by default: it builds the blocks, signs, prints the CIDs, and
;; verifies its own signature -- but writes nothing and announces nothing.
;; `--execute` writes the two blocks and the head pointer to R2 and PUTs the
;; announce to cid.contact. `--ephemeral-detached` is for a reproducible live
;; benchmark when the canonical signing key is unavailable: it creates a
;; one-shot provider identity, writes/announces its ad, and deliberately leaves
;; the canonical head untouched. Such an ad cannot be extended after the
;; process exits, because its seed is never printed or retained.
;;
;; ⚠ An advertisement naming an address that cannot serve a verifiable block
;; is worse than no advertisement: the indexer answers, the fetch fails, and
;; the failure is attributed to IPFS rather than to us. So `--execute` re-reads
;; every block back over HTTPS before announcing, and refuses to announce if
;; what comes back is not byte-identical.

(ns ipni-advertise
  (:require ["child_process" :as cp]
            ["node:crypto" :as crypto]
            ["fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [ed25519.core :as ed]
            [ipld.dag-json :as dj]
            [ipld.link :as link]
            [ipni.metadata :as metadata]
            [ipni.sign :as sign]
            [multiformats.base32 :as b32]
            [multiformats.core :as mf]
            [multiformats.multiaddr :as ma]))

(def manifest-path
  (or (aget (.-env js/process) "IPNI_MANIFEST_PATH")
      "manifest/ipni-publisher.edn"))
(def kagi-bin
  (or (aget (.-env js/process) "IPNI_KAGI_BIN")
      "orgs/kotoba-lang/kagi/bin/kagi"))
(def ipfs-cwd
  (or (aget (.-env js/process) "IPNI_IPFS_CWD")
      "orgs/net-kotobase/ipfs"))
(def bucket "kotobase-graph-database-production")
(def context-id
  (or (aget (.-env js/process) "IPNI_CONTEXT_ID") "kotobase-appviews-v1"))
(def indexer "https://cid.contact")

(defn- die! [& msg]
  (apply println "REFUSING —" msg)
  (set! (.-exitCode js/process) 1)
  (throw (js/Error. "refused")))

(defn- octets [x]
  (cond (nil? x) []
        (vector? x) x
        (string? x) (vec (.from js/Array (js/Buffer.from x "utf8")))
        :else (vec (.from js/Array x))))

(defn ->buf [octs] (js/Buffer.from (into-array octs)))

(defn cid->bytes
  "base32 'b' multibase → the binary CID."
  [cid]
  (when-not (str/starts-with? cid "b") (die! "only base32 CIDs:" cid))
  (vec (b32/decode (subs cid 1))))

(defn cid->multihash
  "The multihash inside a CIDv1: skip the version byte and the codec varint.
  Entries carry MULTIHASHES, not CIDs -- a chunk built from CIDs indexes
  nothing, and the indexer will not say so."
  [cid]
  (let [b (cid->bytes cid)]
    (when-not (= 0x01 (first b)) (die! "not a CIDv1:" cid))
    (loop [i 1]
      (if (>= (nth b i) 0x80)
        (recur (inc i))
        (vec (drop (inc i) b))))))

;; ── the two blocks ──────────────────────────────────────────────────────────

(defn entry-chunk-node [content-cids]
  {"Entries" (mapv #(->buf (cid->multihash %)) content-cids)})

(defn advertisement-node
  [{:keys [peer addrs entries-cid metadata-bytes previous-id signature]}]
  (cond-> {"Provider" peer
           "Addresses" (vec addrs)
           "Entries" (link/link entries-cid)
           "ContextID" (->buf (octets context-id))
           "Metadata" (->buf metadata-bytes)
           "IsRm" false
           "Signature" (->buf signature)}
    previous-id (assoc "PreviousID" (link/link previous-id))))

(defn sign-advertisement
  [{:keys [peer addrs entries-cid metadata-bytes previous-id seed]}]
  (let [payload-bytes (sign/signature-payload
                       {:previous-id previous-id
                        :entries entries-cid
                        :provider peer
                        :addresses addrs
                        :metadata metadata-bytes
                        :is-rm false}
                       {:cid-bytes-fn cid->bytes})]
    (when (:error payload-bytes) (die! "signature payload:" (pr-str payload-bytes)))
    (let [payload (vec (mf/multihash-sha256 (->buf payload-bytes)))
          pubkey (vec (ed/pubkey-from-seed seed))
          env (sign/envelope {:payload-type sign/ad-codec
                              :payload payload
                              :pubkey pubkey
                              :sign-fn (fn [record] (vec (ed/sign seed (->buf record))))})]
      (when (:error env) (die! "envelope:" (pr-str env)))
      ;; Verify our own signature before anything leaves this process. A
      ;; signature that does not verify locally will not verify at cid.contact
      ;; either, and there it comes back as a bare 400.
      (let [check (sign/verify env payload
                               {:verify-fn (fn [pub msg sig]
                                             (ed/verify (->buf pub) (->buf msg) (->buf sig)))})]
        (when-not (true? (:valid? check))
          (die! "our own signature does not verify:" (pr-str check))))
      env)))

;; ── effects ─────────────────────────────────────────────────────────────────

(defn r2-get-head []
  (let [r (.spawnSync cp "npx" (into-array ["wrangler" "r2" "object" "get"
                                            (str bucket "/ipni/head") "--pipe" "--remote"])
                      #js {:cwd ipfs-cwd :encoding "utf8"})]
    (when (zero? (.-status r))
      (let [v (str/trim (str (.-stdout r)))]
        (when (str/starts-with? v "bagu") v)))))

(defn r2-put! [key buf]
  (let [tmp (str "/tmp/ipni-" (str/replace key "/" "_"))]
    (.writeFileSync fs tmp buf)
    (let [r (.spawnSync cp "npx" (into-array ["wrangler" "r2" "object" "put"
                                              (str bucket "/" key)
                                              "--file" tmp "--remote"])
                        #js {:cwd ipfs-cwd :encoding "utf8"})]
      (.unlinkSync fs tmp)
      (when-not (zero? (.-status r))
        (die! "r2 put" key (str (.-stdout r)) (str (.-stderr r))))
      key)))

(defn http-get-bytes [url]
  (let [r (.spawnSync cp "curl"
                      (into-array ["-sS" "-m" "45" "-H" "Accept: application/vnd.ipld.raw" url])
                      #js {:encoding "buffer" :maxBuffer (* 32 1024 1024)})]
    (when-not (zero? (.-status r)) (die! "GET failed" url))
    (.-stdout r)))

(defn announce! [ad-cid publisher-addr]
  (let [addr-b64 (.toString (->buf (ma/->octets publisher-addr)) "base64")
        body (js/JSON.stringify (clj->js {"Cid" {"/" ad-cid} "Addrs" [addr-b64]}))
        r (.spawnSync cp "curl"
                      (into-array ["-sS" "-m" "60" "-X" "PUT"
                                   "-H" "Content-Type: application/json"
                                   "-d" body "-w" "\n%{http_code}"
                                   (str indexer "/ingest/announce")])
                      #js {:encoding "utf8"})]
    {:body (str (.-stdout r)) :err (str (.-stderr r))}))

;; ── main ────────────────────────────────────────────────────────────────────

(let [argv (vec *command-line-args*)
      execute? (some #{"--execute"} argv)
      detached? (some #{"--ephemeral-detached"} argv)
      content-cids (vec (remove #(str/starts-with? % "--") argv))
      m (edn/read-string (.readFileSync fs manifest-path "utf8"))
      ready (first (filter #(= :ready (:status %)) (:ipni.publisher/retrieval-candidates m)))
      publisher-origin (:ipni.publisher/publisher-origin m)]
  (when (empty? content-cids)
    (die! "no content CID given"))
  (when-not ready
    (die! "no retrieval candidate is :ready — advertising an address that cannot serve is worse than not advertising"))
  (let [addrs [(:multiaddr ready)]
        seed-hex (if detached?
                   (.toString (crypto/randomBytes 32) "hex")
                   (let [r (.spawnSync cp kagi-bin
                                     (into-array ["get" (:ipni.publisher/seed-kagi-item m)
                                                  "-c" (:ipni.publisher/seed-kagi-compartment m)])
                                     #js {:encoding "utf8"
                                          :env (doto (js/Object.assign #js {} (.-env js/process))
                                                 (aset "KAGI_HOME" (str (aget (.-env js/process) "HOME") "/.kagi"))
                                                 (aset "FLEET_ROOT" (.cwd js/process)))})]
                   (when-not (zero? (.-status r))
                     (die! "kagi:" (str/trim (str (.-stderr r)))))
                   (str/trim (str (.-stdout r)))))
        seed (js/Buffer.from seed-hex "hex")
        _ (when-not (= 32 (.-length seed)) (die! "seed is not 32 bytes"))
        derived (mf/base58btc (->buf (concat [0x00 36 0x08 0x01 0x12 0x20]
                                             (vec (ed/pubkey-from-seed seed)))))
        peer (if detached? derived (:ipni.publisher/peer-id m))
        publisher-addr (str "/dns4/" (str/replace publisher-origin #"^https://" "")
                            "/tcp/443/https/p2p/" peer)
        _ (when-not (= derived peer)
            (die! "the vault key does not derive the peer id in the manifest:" derived "vs" peer))
        metadata-bytes (vec (metadata/gateway-http-bytes))
        ;; An advertisement chain is a chain. An indexer that has already
        ;; ingested a head walks BACK from the new one, so a second
        ;; advertisement with no PreviousID orphans everything before it.
        previous-id (when-not detached? (r2-get-head))
        chunk (entry-chunk-node content-cids)
        chunk-block (dj/node->block chunk)
        signature (sign-advertisement {:peer peer :addrs addrs
                                       :entries-cid (:cid chunk-block)
                                       :metadata-bytes metadata-bytes
                                       :previous-id previous-id
                                       :seed seed})
        ad (advertisement-node {:peer peer :addrs addrs
                                :entries-cid (:cid chunk-block)
                                :metadata-bytes metadata-bytes
                                :previous-id previous-id
                                :signature signature})
        ad-block (dj/node->block ad)]
    (println "provider       " peer)
    (println "publication    " (if detached? "ephemeral detached (head unchanged)" "canonical chain"))
    (println "context        " context-id)
    (println "retrieval      " (first addrs))
    (println "publisher      " publisher-addr)
    (println "content CIDs   " (count content-cids))
    (println "previous head  " (or previous-id "none — this is the first advertisement"))
    (println "entry chunk    " (:cid chunk-block) (str "(" (.-length (:bytes chunk-block)) " bytes)"))
    (println "advertisement  " (:cid ad-block) (str "(" (.-length (:bytes ad-block)) " bytes)"))
    (println "signature      " (count signature) "bytes, verified locally")
    (if-not execute?
      (do
        ;; Print what would be written. A CID is not reviewable; the bytes are.
        (println "\n--- entry chunk ---")
        (println (.toString (js/Buffer.from (:bytes chunk-block)) "utf8"))
        (println "\n--- advertisement ---")
        (println (.toString (js/Buffer.from (:bytes ad-block)) "utf8"))
        (println "\ndry run — nothing written, nothing announced. Pass --execute."))
      (do
        (println "\nwriting blocks…")
        (r2-put! (str "ipld/" (:cid chunk-block)) (:bytes chunk-block))
        (r2-put! (str "ipld/" (:cid ad-block)) (:bytes ad-block))
        (when-not detached?
          (r2-put! "ipni/head" (js/Buffer.from (:cid ad-block) "utf8")))
        (println "verifying over HTTPS before announcing…")
        (doseq [[label cid expected]
                [["entry chunk" (:cid chunk-block) (:bytes chunk-block)]
                 ["advertisement" (:cid ad-block) (:bytes ad-block)]]]
          (let [got (http-get-bytes (str publisher-origin "/ipni/v1/ad/" cid))]
            (when-not (.equals (js/Buffer.from got) (js/Buffer.from expected))
              (die! label "does not read back byte-identical from" publisher-origin))
            (println " " label "reads back identical," (.-length got) "bytes")))
        (when-not detached?
          (let [head (http-get-bytes (str publisher-origin "/ipni/v1/head"))]
            (when-not (.equals (js/Buffer.from head) (js/Buffer.from (:bytes ad-block)))
              (die! "head does not serve the advertisement we just wrote"))
            (println "  head serves the advertisement")))
        (println "\nannouncing to" indexer "…")
        (let [{:keys [body]} (announce! (:cid ad-block) publisher-addr)]
          (println body))))))

;; Pack every block the IPNI head can reach into one CARv2, for the pack-price
;; measurement (ADR-2609109900 Q3d).
;;
;; The blocks are not fetched one by one: the IPQ surface already answers the
;; `every link` selector with a CARv1 holding all of them, root first, and
;; that CAR is the only input this script trusts -- every block is re-hashed
;; by `car/decode`'s caller before it is packed, so a byte that did not hash to
;; its CID on the wire cannot end up in the pack either.
;;
;; Writes `<out>/<root>.car` (CARv2, MultihashIndexSorted) and prints the
;; entries so the offsets are on the record. Upload is a separate step, and
;; is documented in the ADR rather than done here: this script never touches
;; the store.
;;
;;   nbb --classpath "orgs/kotoba-lang/io-ipld-car/src:orgs/kotoba-lang/io-ipld/src:orgs/kotoba-lang/org-ietf-cbor/src:orgs/kotoba-lang/io-multiformats/src:orgs/kotoba-lang/org-nist-sha2/src:orgs/kotoba-lang/text/src" 90-docs/query-plane/pack_reachable.cljs <out-dir>

(ns pack-reachable
  (:require [ipld.car :as car]
            [ipld.car.v2 :as v2]
            [ipld.core :as ipld]
            [ipld.selector :as sel]
            ["fs" :as fs]
            ["path" :as path]))

(def origin "https://ipfs.kotobase.net")
(def indexer "https://ipni.kotobase.net")

(defn- b64url [bytes]
  (-> (.toString (js/Buffer.from (js/Uint8Array. (clj->js (vec bytes)))) "base64")
      (.replace (js/RegExp. "\\+" "g") "-")
      (.replace (js/RegExp. "/" "g") "_")
      (.replace (js/RegExp. "=+$") "")))

(def everything
  {:selector :explore-recursive :limit {:mode :depth :depth 32}
   :sequence {:selector :explore-all :next {:selector :explore-recursive-edge}}})

(defn- verified-blocks
  "Every block in the CAR, each re-hashed under its own CID. A CAR from the
  network is a storage boundary and is read the way one is read."
  [car-bytes]
  (let [{:keys [roots blocks]} (car/decode car-bytes)]
    {:roots roots
     :blocks (mapv (fn [[cid bytes]]
                     (let [store {cid bytes}]
                       ;; get-verified-block throws :ipld/cid-mismatch on a
                       ;; block that does not hash to its CID.
                       {:cid cid :bytes (ipld/get-verified-block store cid)}))
                   blocks)}))

(defn- write-pack! [out-dir root buf]
  (let [car-bytes (js/Uint8Array. buf)
        {:keys [roots blocks]} (verified-blocks car-bytes)
        ;; root first, as the CAR delivered it
        by-cid (into {} (map (juxt :cid identity) blocks))
        ordered (into [(by-cid root)] (remove #(= root (:cid %)) blocks))
        packed (v2/pack {:roots [root] :blocks ordered})
        out (path/join out-dir (str root ".car"))]
    (fs/mkdirSync out-dir (js-obj "recursive" true))
    (fs/writeFileSync out (js/Buffer.from (:bytes packed)))
    (println "car v1 in    " (.-byteLength buf) "bytes, roots" (pr-str roots))
    (println "blocks       " (count ordered) "-- every one re-hashed under its CID")
    (println "car v2 out   " (.-length (:bytes packed)) "bytes ->" out)
    (println "index-offset " (:index-offset packed))
    (println "entries      " (count (:entries packed)))
    (doseq [e (take 3 (:entries packed))]
      (println "  " (subs (:cid e) 0 20) "… offset" (:file-offset e) "len" (:frame-length e)))
    (println "  …")
    (let [back (v2/read-all (:bytes packed))]
      (println "read-all     " (count (:blocks back)) "blocks,"
               (count (v2/read-index (:bytes packed))) "index records"))))

(defn -main [out-dir]
  (-> (js/fetch (str indexer "/ipni/v1/head"))
      (.then #(.json %))
      (.then (fn [j]
               (let [root (get-in (js->clj j) ["head" "/"])]
                 (println "root" root)
                 (-> (js/fetch (str origin "/ipq/v1/selection/" root
                                    "?selector=" (b64url (sel/encode everything))))
                     (.then (fn [r]
                              (println "ipq" (.-status r)
                                       "blocks" (.get (.-headers r) "x-ipq-blocks")
                                       "fetches" (.get (.-headers r) "x-ipq-fetches"))
                              (.arrayBuffer r)))
                     (.then (fn [buf] (write-pack! out-dir root buf)))))))
      (.catch (fn [e] (println "FAILED" (str e)) (.exit js/process 1)))))

(-main (or (first *command-line-args*) "/tmp/packs"))

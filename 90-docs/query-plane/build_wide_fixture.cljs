;; A DAG exactly as wide as the profile's advertised ceiling, for measuring
;; whether that ceiling is deliverable (ADR-2609109900 Q3e).
;;
;; `GET /ipq/v1` advertises maxBlocks 256. At the measured ~109 ms per block
;; that is ~28 s of sequential R2 reads, which three ADR updates have carried
;; as an extrapolation. Nothing on the origin reaches 256 blocks, so this
;; builds a graph that does: one dag-cbor root linking N leaves, each leaf a
;; distinct small dag-cbor node. `explore-all` from the root touches N+1.
;;
;; It writes every block to `<out>/blocks/<cid>` and the CARv2 pack to
;; `<out>/packs/<root>.car`, and prints the root. Upload is a separate,
;; documented step. Synthetic on purpose and content-addressed like any
;; other block: a fixture that lives at its own hash is not litter, it is a
;; block nobody links to.
;;
;;   nbb --classpath "orgs/kotoba-lang/io-ipld-car/src:orgs/kotoba-lang/io-ipld/src:orgs/kotoba-lang/org-ietf-cbor/src:orgs/kotoba-lang/io-multiformats/src:orgs/kotoba-lang/org-nist-sha2/src:orgs/kotoba-lang/text/src" 90-docs/query-plane/build_wide_fixture.cljs <out-dir> [leaves=255]

(ns build-wide-fixture
  (:require [ipld.car.v2 :as v2]
            [ipld.core :as ipld]
            ["fs" :as fs]
            ["path" :as path]))

(defn -main [out-dir leaves pad]
  (let [n (js/parseInt (or leaves "255") 10)
        pad (js/parseInt (or pad "0") 10)
        padding (apply str (repeat pad "x"))
        store (atom {})
        put! (fn [cid bytes] (swap! store assoc cid bytes) cid)
        leaf-cids (mapv (fn [i]
                          (ipld/put-node! put! (cond-> {"i" i "kind" "wide-fixture-leaf"
                                                        "note" "ADR-2609109900 Q3e: maxBlocks 256 measurement"}
                                                 (pos? pad) (assoc "pad" padding))))
                        (range n))
        root (ipld/put-node! put! {"kind" "wide-fixture-root"
                                   "leaves" (mapv ipld/link leaf-cids)})
        blocks-dir (path/join out-dir "blocks")
        packs-dir (path/join out-dir "packs")]
    (fs/mkdirSync blocks-dir (js-obj "recursive" true))
    (fs/mkdirSync packs-dir (js-obj "recursive" true))
    (doseq [[cid bytes] @store]
      (fs/writeFileSync (path/join blocks-dir cid) (js/Buffer.from bytes)))
    (let [ordered (into [{:cid root :bytes (get @store root)}]
                        (map (fn [c] {:cid c :bytes (get @store c)}) leaf-cids))
          packed (v2/pack {:roots [root] :blocks ordered})
          out (path/join packs-dir (str root ".car"))]
      (fs/writeFileSync out (js/Buffer.from (:bytes packed)))
      (println "root        " root)
      (println "blocks      " (count @store) "(1 root +" n "leaves)")
      (println "root bytes  " (.-length (get @store root)))
      (println "pack bytes  " (.-length (:bytes packed)) "->" out)
      (println "index bytes " (- (.-length (:bytes packed)) (:index-offset packed)))
      (println "read-all    " (count (:blocks (v2/read-all (:bytes packed)))) "blocks"))))

(-main (or (first *command-line-args*) "/tmp/wide") (second *command-line-args*) (nth *command-line-args* 2 nil))

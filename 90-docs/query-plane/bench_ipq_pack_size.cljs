;; How does reading a pack WHOLE scale with the pack's size -- and therefore
;; where would a range read start to pay?
;;
;; Q3d and Q3e read packs of 39 KB and 51 KB whole, on the trampoline's first
;; miss, in about one R2 round trip. That cannot stay free as packs grow: a
;; one-block matcher on a 3 MB pack downloads 3 MB to answer 562 bytes. The
;; CARv2 index exists so a reader can fetch one frame by offset instead; this
;; Worker does not do that yet, and whether it should is a question of WHERE
;; the whole read stops being cheap. This measures that curve rather than
;; implementing a range reader on the assumption it is needed.
;;
;; Four 256-block packs of the same shape, different leaf payloads:
;;   ~51 KB (pad 0)  ~314 KB (pad 1 KB)  ~1.1 MB (pad 4 KB)  ~3.2 MB (pad 12 KB)
;; The last is under the traversal byte budget (4 MiB); a larger pack is
;; refused by `get-pack-blocks` and falls back, which is a different row.
;;
;; Two selectors per pack: the one-block matcher (the pack's worst case --
;; everything read, one block used) and the full 256-block walk (its best
;; case). x-ipq-io-ms is the awaited R2 time, so transfer and decode separate.
;;
;;   nbb --classpath "orgs/kotoba-lang/io-ipld/src:orgs/kotoba-lang/org-ietf-cbor/src:orgs/kotoba-lang/io-multiformats/src:orgs/kotoba-lang/org-nist-sha2/src:orgs/kotoba-lang/text/src" 90-docs/query-plane/bench_ipq_pack_size.cljs

(ns bench-ipq-pack-size
  (:require [ipld.selector :as sel]
            ["os" :as os]))

(def origin "https://ipfs.kotobase.net")
(def reps 5)

(def packs
  [{:label "51 KB"   :bytes 50717   :root "bafyreibk63jgcejweghwmcqxdc73ve4bcd6eowi4geqbwvhbbcxaftt5fq"}
   {:label "314 KB"  :bytes 313877  :root "bafyreibbaahj6boodhdjemhfimlhqs4mac3sfhtuagzdsqvc7ansuoegkq"}
   {:label "1.1 MB"  :bytes 1097237 :root "bafyreigqpnq7mqeuzlu5ykvc3ggg7vxm4hrnjz5ggb2q3j74yuzw4tcka4"}
   {:label "3.2 MB"  :bytes 3186197 :root "bafyreidha3w7th66v2xhfrbioxyj5sasgiiwmyju6bhq2vacy4oxns5y74"}])

(defn- b64url [bytes]
  (-> (.toString (js/Buffer.from (js/Uint8Array. (clj->js (vec bytes)))) "base64")
      (.replace (js/RegExp. "\\+" "g") "-")
      (.replace (js/RegExp. "/" "g") "_")
      (.replace (js/RegExp. "=+$") "")))

(def matcher {:selector :matcher})
(def all-leaves {:selector :explore-fields
                 :fields {"leaves" {:selector :explore-all :next {:selector :matcher}}}})

(defn- percentile [xs p]
  (let [v (vec (sort xs))]
    (nth v (min (dec (count v)) (js/Math.floor (* p (count v)))))))

(defn- one [url method]
  (let [t0 (js/performance.now)]
    (-> (js/fetch url (js-obj "method" method))
        (.then (fn [r]
                 (-> (.arrayBuffer r)
                     (.then (fn [buf]
                              (let [h (fn [k] (.get (.-headers r) k))]
                                {:ms (- (js/performance.now) t0)
                                 :status (.-status r)
                                 :bytes (.-byteLength buf)
                                 :blocks (js/parseInt (or (h "x-ipq-blocks") "0"))
                                 :fetches (js/parseInt (or (h "x-ipq-fetches") "0"))
                                 :io-ms (js/parseInt (or (h "x-ipq-io-ms") "0"))
                                 :pack (or (h "x-ipq-pack") "-")})))))))))

(defn- serial [url method n acc]
  (if (zero? n) (js/Promise.resolve acc)
      (-> (one url method) (.then (fn [r] (serial url method (dec n) (conj acc r)))))))

(defn- fmt [x] (.toFixed x 0))

(defn- row [{:keys [label bytes root]} sel-label selector method]
  (let [url (str origin "/ipq/v1/selection/" root "?selector=" (b64url (sel/encode selector)))]
    (-> (serial url method reps [])
        (.then (fn [rs]
                 (let [f (first rs) ok (filter #(= 200 (:status %)) rs)
                       p50 (percentile (map :ms ok) 0.5) io (percentile (map :io-ms ok) 0.5)]
                   (println (str (.padEnd label 8) (.padStart (str bytes) 9)
                                 (.padEnd (str "  " sel-label " " method) 20)
                                 (.padStart (str (:status f)) 5)
                                 (.padStart (str (:blocks f)) 7)
                                 (.padStart (str (:fetches f)) 8)
                                 (.padStart (:pack f) 6)
                                 (.padStart (fmt p50) 8)
                                 (.padStart (fmt (percentile (map :ms ok) 0.9)) 8)
                                 (.padStart (str io) 7)
                                 (.padStart (str (fmt (* 100 (/ io p50))) "%") 7)
                                 (.padStart (str (count ok) "/" (count rs)) 5)))))))))

(defn -main []
  (println (str "load " (pr-str (mapv #(.toFixed % 2) (os/loadavg))) "  reps " reps))
  (println)
  (println "pack        bytes  selector            st blocks fetches  pack     p50     p90  io-ms io/wall ok")
  (reduce (fn [chain p]
            (-> chain
                ;; HEAD does the same traversal, hashing and CAR encoding and
                ;; sends no body, so GET minus HEAD is the client's download
                ;; and HEAD minus io-ms is the Worker's CPU.
                (.then (fn [_] (row p "matcher" matcher "GET")))
                (.then (fn [_] (row p "all 256" all-leaves "HEAD")))
                (.then (fn [_] (row p "all 256" all-leaves "GET")))))
          (js/Promise.resolve nil)
          packs))

(-main)

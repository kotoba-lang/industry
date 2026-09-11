;; A pack far larger than the traversal budget, read in windows.
;;
;; Q3f measured whole reads up to 3.2 MB and found them free; the owner's
;; correction was that 4 MiB bounds what a traversal READS and not how big a
;; pack IS, and that compaction takes packs to data-lake sizes where a whole
;; read is not slow but impossible. This is that regime: one root, 1,000
;; leaves of ~100 KB, a ~100 MB CARv2 pack, and traversals of 1 to 32 blocks
;; -- all under both ceilings, so the comparison is about the read path and
;; not the budget.
;;
;; The Worker's reader (kotobase-ipfs.pack) asks for one 4 MiB window first,
;; learns the object's size from that answer, reads the index from the tail,
;; and every later miss locates its frame and reads one window forward.
;; x-ipq-requests counts R2 requests, x-ipq-pack says hit-large.
;;
;; Run twice: with the pack present (hit-large), and with the pack deleted
;; and the same 32 blocks present as individual objects (miss, per-block).
;;
;;   nbb --classpath "..." 90-docs/query-plane/bench_ipq_large_pack.cljs <root> <label> [reps=3]
(ns bench-ipq-large-pack
  (:require [ipld.selector :as sel]
            ["os" :as os]))

(def origin "https://ipfs.kotobase.net")
(def widths [1 7 15 31])                ; leaves; blocks = leaves + 1 -- all under maxBlocksWithoutPack

(defn- b64url [bytes]
  (-> (.toString (js/Buffer.from (js/Uint8Array. (clj->js (vec bytes)))) "base64")
      (.replace (js/RegExp. "\\+" "g") "-")
      (.replace (js/RegExp. "/" "g") "_")
      (.replace (js/RegExp. "=+$") "")))

(defn- first-k-leaves [k]
  {:selector :explore-fields
   :fields {"leaves" {:selector :explore-range :start 0 :end k
                      :next {:selector :matcher}}}})

(defn- percentile [xs p]
  (let [v (vec (sort xs))]
    (nth v (min (dec (count v)) (js/Math.floor (* p (count v)))))))

(defn- one [url]
  (let [t0 (js/performance.now)
        ctl (js/AbortController.)
        timer (js/setTimeout #(.abort ctl) 90000)]
    (-> (js/fetch url (js-obj "signal" (.-signal ctl)))
        (.then (fn [r]
                 (-> (.arrayBuffer r)
                     (.then (fn [buf]
                              (js/clearTimeout timer)
                              (let [h (fn [k] (.get (.-headers r) k))]
                                {:ms (- (js/performance.now) t0)
                                 :status (.-status r)
                                 :bytes (.-byteLength buf)
                                 :blocks (js/parseInt (or (h "x-ipq-blocks") "0"))
                                 :fetches (js/parseInt (or (h "x-ipq-fetches") "0"))
                                 :requests (js/parseInt (or (h "x-ipq-requests") "0"))
                                 :io-ms (js/parseInt (or (h "x-ipq-io-ms") "0"))
                                 :pack (or (h "x-ipq-pack") "-")}))))))
        (.catch (fn [e]
                  (js/clearTimeout timer)
                  {:ms (- (js/performance.now) t0) :status (str "ERR " (.-name e))
                   :bytes 0 :blocks 0 :fetches 0 :requests 0 :io-ms 0 :pack "-"})))))

(defn- serial [url n acc]
  (if (zero? n)
    (js/Promise.resolve acc)
    (-> (one url) (.then (fn [r] (serial url (dec n) (conj acc r)))))))

(defn- fmt [x] (.toFixed x 0))

(defn- row [root reps k]
  (let [url (str origin "/ipq/v1/selection/" root "?selector=" (b64url (sel/encode (first-k-leaves k))))]
    (-> (serial url reps [])
        (.then (fn [rs]
                 (let [f (first rs)
                       ok (filter #(= 200 (:status %)) rs)
                       p50 (when (seq ok) (percentile (map :ms ok) 0.5))
                       io (when (seq ok) (percentile (map :io-ms ok) 0.5))]
                   (println (str (.padStart (str (inc k)) 7)
                                 (.padStart (str (:status f)) 10)
                                 (.padStart (str (:blocks f)) 8)
                                 (.padStart (str (:fetches f)) 9)
                                 (.padStart (str (:requests f)) 9)
                                 (.padStart (:pack f) 10)
                                 (.padStart (if p50 (fmt p50) "-") 9)
                                 (.padStart (if (seq ok) (fmt (percentile (map :ms ok) 0.9)) "-") 9)
                                 (.padStart (if io (str io) "-") 8)
                                 (.padStart (str (count ok) "/" (count rs)) 6)
                                 (when (and p50 (pos? (:blocks f)))
                                   (.padStart (str (fmt (/ p50 (:blocks f))) " ms/blk") 12))))
                   {:blocks (inc k) :p50 p50 :ok (count ok)}))))))

(defn -main [root label reps]
  (let [reps (js/parseInt (or reps "3") 10)]
    (println (str "load " (pr-str (mapv #(.toFixed % 2) (os/loadavg)))
                  "  reps " reps "  " label))
    (println (str "root " root))
    (println)
    (println "asked    status  blocks  fetches requests  pack          p50      p90   io-ms  ok/n")
    (reduce (fn [chain k] (.then chain (fn [_] (row root reps k))))
            (js/Promise.resolve nil)
            widths)))

(let [[root label reps] *command-line-args*]
  (if root
    (-main root (or label "") reps)
    (do (println "usage: <root> <label> [reps]") (.exit js/process 2))))

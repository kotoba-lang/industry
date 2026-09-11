;; Would packing pay on the IPQ path? The slope decides, so measure the slope.
;;
;; ADR-2609109800 P7 says CARv2 packing (ADR-2608160100) is worth considering
;; here because IPQ issued one fetch per block -- and then refuses to derive a
;; per-block cost from two points, which was right. This bench gets the points.
;;
;; The IPNI advertisement chain is a linked list of DAG-CBOR blocks on the same
;; origin, so an ExploreRecursive over `PreviousID` walks a real DAG of chosen
;; depth. That is a graph the origin already serves, not one this bench had to
;; publish, and it means the numbers are about the deployed read path.
;;
;; ## What a slope can and cannot say
;;
;; A positive slope with `fetches == blocks` on every row says the origin pays
;; per block. It does NOT say a pack would remove that cost -- a pack replaces
;; N object reads with one ranged read of a larger object, and whether that is
;; cheaper depends on the store, not on this curve. What the slope gives is the
;; SIZE OF THE PRIZE: N x slope is the most a pack could remove here.
;;
;;   nbb --classpath "orgs/kotoba-lang/io-ipld/src:orgs/kotoba-lang/org-ietf-cbor/src:orgs/kotoba-lang/io-multiformats/src:orgs/kotoba-lang/org-nist-sha2/src:orgs/kotoba-lang/text/src" 90-docs/query-plane/bench_ipq_pack_opportunity.cljs

(ns bench-ipq-pack-opportunity
  (:require [ipld.selector :as sel]
            ["os" :as os]))

(def origin "https://ipfs.kotobase.net")
(def indexer "https://ipni.kotobase.net")
(def reps 7)

;; 2026-09-10: the chain refused at depth 14, where its codec switches to
;; dag-json, so the fit stopped at 13 blocks. 2026-09-11: io-ipld decodes
;; dag-json, the surface crosses into it, and the chain turns out to END at
;; 16 advertisements -- the ceiling was hiding the last three. Following the
;; Entries link as well reaches every block the head can, 32 of them.
(def series
  [{:label "PreviousID chain" :depths [0 1 2 4 8 12 14 15] :selector :chain}
   {:label "every link"       :depths [0 1 2 4 8 12 16 24 31] :selector :everything}])

(defn- chain-selector
  "Walk `PreviousID` to `depth`. Depth 0 is the head advertisement alone."
  [depth]
  {:selector :explore-recursive
   :limit {:mode :depth :depth depth}
   :sequence {:selector :explore-fields
              :fields {"PreviousID" {:selector :explore-recursive-edge}}}})

(defn- everything-selector
  "Every link from every node, to `depth`: advertisements AND their entry
  chunks. This is the whole graph the head can reach."
  [depth]
  {:selector :explore-recursive
   :limit {:mode :depth :depth depth}
   :sequence {:selector :explore-all :next {:selector :explore-recursive-edge}}})

(defn- selector-for [kind depth]
  (case kind :chain (chain-selector depth) :everything (everything-selector depth)))

(defn- b64url [bytes]
  (-> (.toString (js/Buffer.from (js/Uint8Array. (clj->js (vec bytes)))) "base64")
      (.replace (js/RegExp. "\\+" "g") "-")
      (.replace (js/RegExp. "/" "g") "_")
      (.replace (js/RegExp. "=+$") "")))

(defn- percentile [xs p]
  (let [v (vec (sort xs))]
    (nth v (min (dec (count v)) (js/Math.floor (* p (count v)))))))

(defn- one [url]
  (let [t0 (js/performance.now)]
    (-> (js/fetch url)
        (.then (fn [r]
                 (-> (.arrayBuffer r)
                     (.then (fn [buf]
                              {:ms (- (js/performance.now) t0)
                               :status (.-status r)
                               :bytes (.-byteLength buf)
                               :blocks (js/parseInt (or (.get (.-headers r) "x-ipq-blocks") "0"))
                               :fetches (js/parseInt (or (.get (.-headers r) "x-ipq-fetches") "0"))})))))))) 

(defn- serial [url n acc]
  (if (zero? n)
    (js/Promise.resolve acc)
    (-> (one url) (.then (fn [r] (serial url (dec n) (conj acc r)))))))

(defn- fmt [x] (.toFixed x 1))

(defn- regress
  "Least squares over [blocks p50]. Returns [intercept slope r2]."
  [points]
  (let [n (count points)
        sx (reduce + (map first points))
        sy (reduce + (map second points))
        sxx (reduce + (map #(* (first %) (first %)) points))
        sxy (reduce + (map #(* (first %) (second %)) points))
        denom (- (* n sxx) (* sx sx))
        slope (/ (- (* n sxy) (* sx sy)) denom)
        intercept (/ (- sy (* slope sx)) n)
        mean (/ sy n)
        ss-tot (reduce + (map #(let [d (- (second %) mean)] (* d d)) points))
        ss-res (reduce + (map #(let [d (- (second %) (+ intercept (* slope (first %))))]
                                 (* d d))
                              points))]
    [intercept slope (- 1 (/ ss-res ss-tot))]))

(defn- measure-depth [root kind depth]
  (let [url (str origin "/ipq/v1/selection/" root
                 "?selector=" (b64url (sel/encode (selector-for kind depth))))]
    (-> (serial url reps [])
        (.then (fn [rs]
                 (let [f (first rs) ms (map :ms rs)]
                   {:depth depth :status (:status f)
                    :blocks (:blocks f) :fetches (:fetches f) :bytes (:bytes f)
                    :p50 (percentile ms 0.5) :p90 (percentile ms 0.9)
                    :min (apply min ms)}))))))

(defn- print-row [r]
  (println (str (.padStart (str (:depth r)) 5)
                (.padStart (str (:status r)) 8)
                (.padStart (str (:blocks r)) 8)
                (.padStart (str (:fetches r)) 8)
                (.padStart (str (= (:blocks r) (:fetches r))) 7)
                (.padStart (str (:bytes r)) 13)
                (.padStart (fmt (:p50 r)) 9)
                (.padStart (fmt (:p90 r)) 9)
                (.padStart (fmt (:min r)) 9)
                (.padStart (if (pos? (:blocks r)) (fmt (/ (:p50 r) (:blocks r))) "-") 11))))

(defn- walk [root kind ds acc]
  (if (empty? ds)
    (js/Promise.resolve acc)
    (-> (measure-depth root kind (first ds))
        (.then (fn [r] (print-row r) (walk root kind (rest ds) (conj acc r)))))))

(defn- summarize [label rows]
  (let [ok (filterv #(and (= 200 (:status %)) (pos? (:blocks %))) rows)
        broken (filterv #(not= 200 (:status %)) rows)
        [intercept slope r2] (regress (mapv (fn [r] [(:blocks r) (:p50 r)]) ok))
        max-blocks (apply max (map :blocks ok))]
    (println)
    (when (seq broken)
      (println (str "EXCLUDED from the fit: " (count broken) " row(s) that did not answer 200 -- "
                    (pr-str (mapv (juxt :depth :status) broken))))
      (println "  A non-200 is not a slow 200. Fitting a line through it would make")
      (println "  a broken request look like an expensive one.")
      (println))
    (println (str "least squares over " (count ok) " points:"))
    (println (str "  fixed cost   " (fmt intercept) " ms   (TLS, routing, first byte)"))
    (println (str "  per block    " (fmt slope) " ms"))
    (println (str "  r-squared    " (.toFixed r2 4)))
    (println (str "  at " max-blocks " blocks that is " (fmt (* slope max-blocks))
                  " ms of per-block cost -- the size of the prize"))
    (println)
    (println (str "fetches == blocks on every row: "
                  (every? (fn [r] (= (:blocks r) (:fetches r))) ok)))
    (println "One fetch per block is the mechanism. A pack would replace those")
    (println "reads with one ranged read; whether that is cheaper is a property")
    (println "of the store and is NOT measured here. This bounds the saving.")
    (println "The intercept is the floor a pack cannot remove.")))

(defn- run-series [root {:keys [label depths selector]}]
  (println)
  (println (str "== " label))
  (println (str "depth  status  blocks fetches   f=b        bytes"
                "      p50      p90      min   ms/block"))
  (-> (walk root selector depths [])
      (.then (fn [rows] (summarize label rows) rows))))

(defn -main []
  (println (str "load " (pr-str (mapv #(.toFixed % 2) (os/loadavg)))
                "  reps " reps "  origin " origin))
  (println)
  (-> (js/fetch (str indexer "/ipni/v1/head"))
      (.then (fn [r] (.json r)))
      (.then (fn [j]
               (let [root (get-in (js->clj j) ["head" "/"])]
                 (println (str "root " root
                               " (live IPNI signed head, not a pinned constant)"))
                 (reduce (fn [chain s] (.then chain (fn [_] (run-series root s))))
                         (js/Promise.resolve nil)
                         series))))
      (.catch (fn [e] (println "PROBE FAILED" (str e))))))

(-main)

#!/usr/bin/env nbb
;; The deterministic judge for the aiuefs-v4 co-scientist loop.
;;
;; It answers ONE question the workspace has never measured: the storage plane
;; encrypts every leaf (arrangement.core: blinded key + AEAD value), and
;; ADR-2608160100 measured compression AFTER that encryption, found ratio
;; 1.003 -- bytes went UP -- and concluded "do not compress the pack". That
;; conclusion is correct for its subject and says nothing about the other
;; order. Nobody has measured compress-BEFORE-encrypt, per leaf.
;;
;; So this measures both orders, on real bytes this workspace actually stores,
;; across the leaf sizes the physical plane actually uses, and prices the
;; security tax that compress-before-encrypt owes (length leakage -> padding).
;;
;;   nbb scripts/aiuefs-compression-bench.cljs --root . --out <file.edn>
;;
;; Evidence floor (CLAUDE.md "6 questions"): it prints SCANNED<TAB>n for every
;; corpus, refuses to report a result when a corpus scanned zero files, and
;; exits 2 -- not 0, not 1 -- when it could not answer. A codec that is
;; unavailable is reported as :unavailable, never silently omitted.

(require '[clojure.string :as str]
         '[clojure.edn :as edn])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def zlib (js/require "node:zlib"))
(def crypto (js/require "node:crypto"))

(def EXIT-COULD-NOT-ANSWER 2)

(defn- die [code & msg]
  (binding [*out* *err*] (apply println (cons "aiuefs-bench:" msg)))
  (.exit js/process code))

(def args (vec *command-line-args*))
(defn- arg [flag default]
  (let [i (.indexOf (to-array args) flag)]
    (if (>= i 0) (nth args (inc i) default) default)))

(def root (arg "--root" "."))
(def out-path (arg "--out" nil))

;; --- codecs -----------------------------------------------------------------
;; Availability is PROBED, never assumed: node gained zstd only in 22.15, and a
;; missing codec must read as :unavailable rather than as a zero-gain result.

(defn- probe [f]
  (try (let [r (f (js/Buffer.from "aiuefs codec probe, long enough to compress ......"))]
         (when (pos? (.-length r)) true))
       (catch :default _ false)))

(def codecs
  (->> [{:id :deflate-raw
         :compress #(.deflateRawSync zlib % #js {:level 9})}
        {:id :brotli
         :compress (let [params (js-obj)]
                     (aset params (.. zlib -constants -BROTLI_PARAM_QUALITY) 9)
                     (aset params (.. zlib -constants -BROTLI_PARAM_LGWIN) 22)
                     #(.brotliCompressSync zlib % (js-obj "params" params)))}
        {:id :zstd
         :compress (when (fn? (.-zstdCompressSync zlib))
                     #(.zstdCompressSync zlib %))}]
       (mapv (fn [{:keys [id compress] :as c}]
               (assoc c :available? (boolean (and compress (probe compress))))))))

;; --- AEAD -------------------------------------------------------------------
;; AES-256-GCM, the shape arrangement's encrypt-fn already carries on both
;; platforms (javax.crypto / crypto.subtle). Overhead is what actually lands on
;; disk: 12-byte nonce + 16-byte tag per sealed leaf.

(def nonce-bytes 12)
(def tag-bytes 16)
(def aead-key (.randomBytes crypto 32))

(defn- seal [^js buf]
  (let [iv (.randomBytes crypto nonce-bytes)
        c (.createCipheriv crypto "aes-256-gcm" aead-key iv)
        body (js/Buffer.concat #js [(.update c buf) (.final c)])]
    (js/Buffer.concat #js [iv body (.getAuthTag c)])))

;; --- padding ----------------------------------------------------------------
;; Compress-before-encrypt makes ciphertext LENGTH a function of plaintext
;; content. Padme (Nikitin et al. 2019) bounds the resulting leak while capping
;; overhead near 12%; :p2 is the blunt alternative; :none prices the leak at
;; zero and is here to show what it buys.

(defn- ilog2 [n] (loop [n n i -1] (if (zero? n) i (recur (bit-shift-right n 1) (inc i)))))

(defn- padme [L]
  (if (< L 2)
    L
    (let [e (ilog2 L)
          s (inc (ilog2 (max e 1)))
          z (- e s)]
      (if (<= z 0)
        L
        (let [m (bit-shift-left 1 z)]
          (* m (quot (+ L m -1) m)))))))

(defn- p2 [L] (if (< L 2) L (bit-shift-left 1 (ilog2 (dec (* 2 L))))))

(def paddings
  [{:id :none :f identity}
   {:id :padme :f padme}
   {:id :p2 :f p2}])

;; --- corpus -----------------------------------------------------------------
;; Real bytes, named by which plane of this stack stores them. Selection is
;; deterministic (sorted, capped) so a rerun on the same tree gives the same
;; numbers.

(defn- walk [dir pred cap]
  (let [acc (atom [])]
    (letfn [(go [d]
              (when (< (count @acc) cap)
                (doseq [name (sort (js->clj (.readdirSync fs d)))]
                  (when (< (count @acc) cap)
                    (let [p (.join path d name)
                          st (try (.statSync fs p) (catch :default _ nil))]
                      (cond
                        (nil? st) nil
                        (.isDirectory st) (when-not (contains? #{".git" "node_modules"} name) (go p))
                        (and (.isFile st) (pred p st)) (swap! acc conj p)))))))]
      (try (go dir) (catch :default _ nil)))
    @acc))

(defn- corpora []
  (let [adr (.join path root "90-docs" "adr")
        orgs (.join path root "orgs" "kotoba-lang")]
    [{:id :adr-documents
      :plane "document plane: an ADR as kotobase stores it"
      :files (walk adr (fn [p st] (and (str/ends-with? p ".edn")
                                       (not (str/includes? p ".datoms."))
                                       (> (.-size st) 512))) 400)}
     {:id :datom-catalogs
      :plane "datom plane: multi-entity catalogs the query face loads"
      :files (walk (.join path root "90-docs")
                   (fn [p st] (and (str/ends-with? p ".datoms.edn") (> (.-size st) 512))) 200)}
     {:id :source-cljc
      :plane "code plane: the .cljc this fleet ships"
      :files (walk orgs (fn [p st] (and (or (str/ends-with? p ".cljc") (str/ends-with? p ".cljs"))
                                        (> (.-size st) 512))) 400)}
     {:id :kernel-objects
      :plane "app plane: compiled artefacts (aiuefs-v3 app extents)"
      :files (walk orgs (fn [p st] (and (or (str/ends-with? p ".wasm") (str/ends-with? p ".o"))
                                        (> (.-size st) 512))) 200)}]))

;; --- the measurement --------------------------------------------------------

(def leaf-sizes [1024 4096 16384 65536 131072])

(defn- chunks [^js buf n]
  (loop [off 0 out []]
    (if (>= off (.-length buf))
      out
      (recur (+ off n) (conj out (.subarray buf off (min (.-length buf) (+ off n))))))))

(defn- measure-corpus
  "For one corpus and one leaf size, the bytes that would actually land on disk
   under each order. Sealed size is what the block store pays, so nonce+tag are
   counted for every leaf in every arm -- including the baseline, which is the
   plane as it exists today."
  [bufs leaf]
  (let [cs (mapcat #(chunks % leaf) bufs)
        n (count cs)
        plain (reduce + 0 (map #(.-length %) cs))
        overhead (* n (+ nonce-bytes tag-bytes))]
    (when (pos? n)
      {:leaf-bytes leaf
       :leaves n
       :plaintext-bytes plain
       ;; today: seal the leaf as-is.
       :baseline-sealed-bytes (+ plain overhead)
       :arms
       (into {}
             (for [{:keys [id compress available?]} codecs]
               [id
                (if-not available?
                  {:state :unavailable}
                  (let [comp (mapv #(compress %) cs)
                        comp-len (mapv #(.-length %) comp)
                        ;; encrypt-then-compress: what ADR-2608160100 measured.
                        etc (reduce + 0 (map #(.-length (compress (seal %))) cs))]
                    {:state :measured
                     :encrypt-then-compress-bytes etc
                     :compress-then-encrypt
                     (into {}
                           (for [{pid :id pf :f} paddings]
                             [pid (+ (reduce + 0 (map pf comp-len)) overhead)]))}))]))})))

(defn- ratio
  "A ratio, or a refusal. Never a number when an input was missing: nil
   arithmetic in cljs yields 0, and a 0 here reads as `compression made the
   bytes vanish` -- the exact shape this file exists to stop."
  [a b]
  (cond (not (number? a)) :UNMEASURED
        (not (number? b)) :UNMEASURED
        (zero? b) :UNMEASURED
        :else (/ (js/Math.round (* 10000 (/ a b))) 10000.0)))

(defn -main []
  (let [cs (corpora)
        scanned (mapv (fn [{:keys [id files]}] [id (count files)]) cs)]
    (doseq [[id n] scanned] (println (str "SCANNED\t" (name id) "\t" n)))
    (when (some (fn [[_ n]] (zero? n)) scanned)
      (die EXIT-COULD-NOT-ANSWER
           "a corpus scanned zero files; refusing to report a compression result."
           "Run from a full checkout with orgs/kotoba-lang populated."))
    (when-not (some :available? codecs)
      (die EXIT-COULD-NOT-ANSWER "no compression codec is available in this node build."))
    (let [result
          {:measurement :aiuefs-v4/compression-order
           :measured-at (.toISOString (js/Date.))
           :node (.-version js/process)
           :aead "aes-256-gcm"
           :aead-overhead-bytes-per-leaf (+ nonce-bytes tag-bytes)
           :codecs (mapv #(select-keys % [:id :available?]) codecs)
           :corpora
           (vec (for [{:keys [id plane files]} cs
                      :let [bufs (keep (fn [f] (try (.readFileSync fs f) (catch :default _ nil))) files)]]
                  {:id id
                   :plane plane
                   :files-scanned (count files)
                   :files-read (count bufs)
                   :bytes (reduce + 0 (map #(.-length %) bufs))
                   :by-leaf-size (vec (keep #(measure-corpus bufs %) leaf-sizes))}))}]
      (when out-path
        (.mkdirSync fs (.dirname path out-path) #js {:recursive true})
        (.writeFileSync fs out-path (with-out-str (pr result))))
      ;; A short human table; the EDN is the record.
      (println)
      (println (str/join "\t" ["corpus" "leaf" "codec" "c-then-e/none" "c-then-e/padme" "e-then-c"]))
      (doseq [{:keys [id by-leaf-size]} (:corpora result)
              {:keys [leaf-bytes baseline-sealed-bytes arms]} by-leaf-size
              [codec arm] arms
              :when (= :measured (:state arm))]
        (println (str/join "\t"
                           [(name id) leaf-bytes (name codec)
                            (ratio (get-in arm [:compress-then-encrypt :none]) baseline-sealed-bytes)
                            (ratio (get-in arm [:compress-then-encrypt :padme]) baseline-sealed-bytes)
                            (ratio (:encrypt-then-compress-bytes arm) baseline-sealed-bytes)])))
      (println)
      (println "ratio < 1.0 means fewer bytes on disk than the plane stores today."))))

(-main)

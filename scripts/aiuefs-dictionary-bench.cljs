#!/usr/bin/env nbb
;; Third judge for the aiuefs-v4 co-scientist loop: does a SHARED DICTIONARY
;; pay, and by how much, on held-out leaves?
;;
;; The codec judge found brotli and zstd losing ground as leaves get small.
;; That is structural, not a codec defect: an IPLD leaf must be independently
;; addressable and independently verifiable, so it cannot be compressed against
;; its neighbours. Every leaf starts from an empty history. A shared dictionary
;; is the standard answer -- and in a content-addressed plane the dictionary is
;; just another block, named by its own CID, so "which dictionary" has an
;; answer that verifies like everything else.
;;
;;   nbb scripts/aiuefs-dictionary-bench.cljs --root . --out <file.edn>
;;
;; HOLD-OUT IS THE POINT. Leaves are split by index: every `--holdout-every`th
;; leaf is measured, the rest may train. A dictionary measured on its own
;; training set reports a gain that does not survive new data, and that number
;; looks exactly like a real one.

(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def zlib (js/require "node:zlib"))
(def crypto (js/require "node:crypto"))
(def os (js/require "node:os"))

(def EXIT-COULD-NOT-ANSWER 2)
(def args (vec *command-line-args*))
(defn- arg [flag default]
  (let [i (.indexOf (to-array args) flag)] (if (>= i 0) (nth args (inc i) default) default)))
(def root (arg "--root" "."))
(def out-path (arg "--out" nil))
(def leaf-bytes (js/parseInt (arg "--leaf" "16384")))
(def holdout-every (js/parseInt (arg "--holdout-every" "3")))
(def dict-bytes (js/parseInt (arg "--dict-bytes" "32768")))

(defn- die [code & msg]
  (binding [*out* *err*] (apply println (cons "aiuefs-dict:" msg)))
  (.exit js/process code))

(defn- files-under [dir suffixes min-size]
  (let [acc (atom [])]
    (letfn [(go [d]
              (doseq [name (sort (js->clj (.readdirSync fs d)))]
                (let [p (.join path d name)
                      st (try (.statSync fs p) (catch :default _ nil))]
                  (cond (nil? st) nil
                        (.isDirectory st) (when-not (contains? #{".git" "node_modules"} name) (go p))
                        (and (.isFile st)
                             (some #(str/ends-with? p %) suffixes)
                             (> (.-size st) min-size))
                        (swap! acc conj p)))))]
      (try (go dir) (catch :default _ nil)))
    @acc))

(defn- leaves-of [files]
  (vec (mapcat (fn [f]
                 (let [b (try (.readFileSync fs f) (catch :default _ nil))]
                   (when b
                     (loop [off 0 out []]
                       (if (>= off (.-length b))
                         out
                         (recur (+ off leaf-bytes)
                                (conj out (.subarray b off (min (.-length b) (+ off leaf-bytes))))))))))
               files)))

(def dictionary-sources 64)

(defn- build-dictionary
  "Take an equal slice from each of `dictionary-sources` evenly sampled training
   leaves, so the fixed `dict-bytes` budget spans the SAME NUMBER OF SOURCES at
   every leaf size.

   The first version concatenated a sample and kept the final `dict-bytes`.
   That silently changed what the dictionary WAS as the sweep moved: at 1 KiB
   leaves the 32 KiB tail covered 32 whole leaves, at 64 KiB it covered half of
   ONE. The resulting `gain collapses with leaf size` curve therefore measured
   leaf size and dictionary coverage at once, and could not say which moved."
  [train]
  (if (empty? train)
    nil
    (let [n (min dictionary-sources (count train))
          step (max 1 (quot (count train) n))
          per (max 1 (quot dict-bytes n))
          slices (->> (take-nth step train)
                      (take n)
                      (mapv #(.subarray % 0 (min per (.-length %)))))
          joined (js/Buffer.concat (clj->js slices))]
      (if (<= (.-length joined) dict-bytes)
        joined
        (.subarray joined 0 dict-bytes)))))

(defn- sha256-hex [^js b] (-> (.createHash crypto "sha256") (.update b) (.digest "hex")))

(defn -main []
  (let [corpora
        [{:id :datom-catalogs :dir (.join path root "90-docs") :suffixes [".datoms.edn"]}
         {:id :adr-documents :dir (.join path root "90-docs" "adr") :suffixes [".edn"]}
         {:id :source-cljc :dir (.join path root "orgs" "kotoba-lang") :suffixes [".cljc" ".cljs"]}]
        results
        (vec
         (for [{:keys [id dir suffixes]} corpora]
           ;; The split is by FILE. Splitting by leaf index over the
           ;; concatenated corpus leaves every held-out leaf byte-adjacent to
           ;; training leaves from the same document, which is not a hold-out
           ;; and reports a gain that will not survive an unseen file.
           (let [files (files-under dir suffixes 2048)
                 indexed-files (map-indexed vector files)
                 held-files (mapv second (filter (fn [[i _]] (zero? (mod i holdout-every))) indexed-files))
                 train-files (mapv second (remove (fn [[i _]] (zero? (mod i holdout-every))) indexed-files))
                 ls (leaves-of files)
                 held (leaves-of held-files)
                 train (leaves-of train-files)
                 dict (build-dictionary train)
                 held-bytes (reduce + 0 (map #(.-length %) held))]
             (println (str "SCANNED\t" (name id) "\tfiles\t" (count files)
                           "\theld-out-files\t" (count held-files)
                           "\ttrain-files\t" (count train-files)
                           "\theld-out-leaves\t" (count held) "\ttrain-leaves\t" (count train)))
             (if (or (empty? held) (nil? dict))
               {:corpus id :state :UNMEASURED
                :reason "no held-out leaves or no dictionary could be built"}
               (let [plain (fn [b] (.deflateRawSync zlib b (js-obj "level" 6)))
                     withd (fn [b] (.deflateRawSync zlib b (js-obj "level" 6 "dictionary" dict)))
                     no-dict (reduce + 0 (map #(.-length (plain %)) held))
                     with-dict (reduce + 0 (map #(.-length (withd %)) held))
                     ;; A dictionary that cannot decode is not a saving.
                     roundtrip?
                     (every? true?
                             (map (fn [b]
                                    (try (.equals (.inflateRawSync zlib (withd b)
                                                                   (js-obj "dictionary" dict))
                                                  b)
                                         (catch :default _ false)))
                                  (take 64 held)))]
                 {:corpus id
                  :state :measured
                  :files (count files)
                  :split :by-file
                  :held-out-files (count held-files)
                  :train-files (count train-files)
                  :leaves-total (count ls)
                  :leaves-held-out (count held)
                  :leaves-trained-on (count train)
                  :dictionary-sources dictionary-sources
                  :held-out-plaintext-bytes held-bytes
                  :dictionary-bytes (.-length dict)
                  :dictionary-sha256 (sha256-hex dict)
                  :roundtrip-with-dictionary? roundtrip?
                  :deflate6-no-dictionary-bytes no-dict
                  :deflate6-with-dictionary-bytes with-dict
                  :ratio-no-dictionary (/ (js/Math.round (* 10000 (/ no-dict held-bytes))) 10000.0)
                  :ratio-with-dictionary (/ (js/Math.round (* 10000 (/ with-dict held-bytes))) 10000.0)
                  :dictionary-gain-pct
                  (/ (js/Math.round (* 100 (* 100 (- 1 (/ with-dict no-dict))))) 100.0)})))))]
    (when (every? #(not= :measured (:state %)) results)
      (die EXIT-COULD-NOT-ANSWER "no corpus produced a held-out dictionary measurement."))
    (when-let [bad (seq (filter #(and (= :measured (:state %))
                                      (not (:roundtrip-with-dictionary? %))) results))]
      (die EXIT-COULD-NOT-ANSWER
           "dictionary decode failed for:" (str/join "," (map (comp name :corpus) bad))))
    (let [payload {:measurement :aiuefs-v4/shared-dictionary
                   :measured-at (.toISOString (js/Date.))
                   :node (.-version js/process)
                   :leaf-bytes leaf-bytes
                   :holdout-every holdout-every
                   :split :by-file
                   :dictionary-construction "equal slice from each of 64 evenly sampled training leaves, so source count is constant across the leaf-size sweep"
                   :dictionary-budget-bytes dict-bytes
                   :codec "deflate-raw level 6, zlib preset dictionary"
                   :load1-at-measurement (first (js->clj (.loadavg os)))
                   :corpora results}]
      (when out-path
        (.mkdirSync fs (.dirname path out-path) #js {:recursive true})
        (.writeFileSync fs out-path (with-out-str (pr payload))))
      (println)
      (println (str/join "\t" ["corpus" "no-dict" "with-dict" "gain%" "dict bytes"]))
      (doseq [r results]
        (if (= :measured (:state r))
          (println (str/join "\t" [(name (:corpus r)) (:ratio-no-dictionary r)
                                   (:ratio-with-dictionary r) (:dictionary-gain-pct r)
                                   (:dictionary-bytes r)]))
          (println (str/join "\t" [(name (:corpus r)) "UNMEASURED" "" "" ""]))))
      (println)
      (println "gain% is measured on leaves the dictionary never saw."))))

(-main)

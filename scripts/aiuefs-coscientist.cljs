#!/usr/bin/env nbb
;; The aiuefs-v4 Co-Scientist loop: Generate -> Reflect -> Rank -> Evolve -> Meta.
;;
;; Same discipline as design-quality.coscientist (ADR-2607132300, ported from
;; isekai ADR-0007): the JUDGE IS DETERMINISTIC. Ranking reads the three bench
;; EDN files under 90-docs/design/aiuefs-v4/bench/results/ and computes an
;; order. It is never a debate between model outputs, and it is re-runnable --
;; anyone can delete the iteration doc and regenerate it from the measurements.
;;
;;   nbb scripts/aiuefs-coscientist.cljs --root . --out <iteration.edn>
;;
;; A hypothesis may only cite a number this loop actually measured. Anything
;; else is carried as :UNMEASURED and, per the ranking rule below, cannot win.

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))

(def EXIT-COULD-NOT-ANSWER 2)
(def args (vec *command-line-args*))
(defn- arg [flag default]
  (let [i (.indexOf (to-array args) flag)] (if (>= i 0) (nth args (inc i) default) default)))
(def root (arg "--root" "."))
(def out-path (arg "--out" nil))
(def bench-dir (.join path root "90-docs" "design" "aiuefs-v4" "bench" "results"))

(defn- die [code & msg]
  (binding [*out* *err*] (apply println (cons "aiuefs-coscientist:" msg)))
  (.exit js/process code))

(defn- read-edn [p]
  (try (edn/read-string (.readFileSync fs p "utf8")) (catch :default _ nil)))

(defn- measured?
  "NaN is a number to `number?`, and nil/nil in cljs IS NaN. Without this the
   `unmeasured cannot win` rule below is bypassed by exactly the hypotheses it
   exists to exclude."
  [x] (and (number? x) (not (js/Number.isNaN x))))

(defn- safe-div [a b]
  (if (and (measured? a) (measured? b) (not (zero? b))) (/ a b) :UNMEASURED))

;; --- the evidence the judge is allowed to use -------------------------------

(defn- evidence []
  (let [order (read-edn (.join path bench-dir "2026-08-29-compression-order.edn"))
        codec (read-edn (.join path bench-dir "2026-08-29-codec-choice-16k.edn"))
        ;; Named by exact leaf size. The first sweep wrote "-16k" and the
        ;; re-measured one wrote "-16384"; both files then sat side by side and
        ;; this judge kept reading the stale one, reporting corrected numbers
        ;; everywhere except in its own evidence.
        dict16 (read-edn (.join path bench-dir "2026-08-29-shared-dictionary-16384.edn"))
        dict1 (read-edn (.join path bench-dir "2026-08-29-shared-dictionary-1024.edn"))
        leak (read-edn (.join path bench-dir "2026-08-29-length-leak-16k.edn"))]
    (when (some nil? [order codec dict16 dict1 leak])
      (die EXIT-COULD-NOT-ANSWER
           "one or more bench results are missing or unreadable in" bench-dir
           "-- run the three bench scripts first. Refusing to rank without them."))
    (let [datom (first (filter #(= :datom-catalogs (:id %)) (:corpora order)))
          at16 (first (filter #(= 16384 (:leaf-bytes %)) (:by-leaf-size datom)))
          base (:baseline-sealed-bytes at16)
          arm (get-in at16 [:arms :deflate-raw])
          g (fn [d id] (:dictionary-gain-pct
                        (first (filter #(= id (:corpus %)) (:corpora d)))))
          ;; The leak axis is MEASURED, not proxied. Iteration 1 first scored it
          ;; by counting plaintext header fields and the unpadded design won on
          ;; that count -- it carries the fewest fields and the most leak. The
          ;; quantity that matters is the entropy of the sealed-length
          ;; distribution, and it is reported per plane because the two planes
          ;; do not behave the same way.
          leak-bits (fn [corpus scheme]
                      (get-in (first (filter #(= corpus (:corpus %)) (:corpora leak)))
                              [:by-scheme scheme :length-entropy-bits]))]
      {:datom-16k-baseline-sealed-bytes base
       ;; the two orders, as a fraction of what the plane stores today
       :compress-then-encrypt-padme (safe-div (get-in arm [:compress-then-encrypt :padme]) base)
       :compress-then-encrypt-none (safe-div (get-in arm [:compress-then-encrypt :none]) base)
       :encrypt-then-compress (safe-div (:encrypt-then-compress-bytes arm) base)
       :identity 1.0
       :codec-rows (:rows codec)
       :dictionary-gain-pct-datom-16k (g dict16 :datom-catalogs)
       :dictionary-gain-pct-datom-1k (g dict1 :datom-catalogs)
       :dictionary-gain-pct-adr-16k (g dict16 :adr-documents)
       ;; bits per leaf a passive length observer learns, per scheme
       :leak-bits-datom {:today (leak-bits :datom-catalogs :today)
                         :unpadded (leak-bits :datom-catalogs :compressed-unpadded)
                         :padme (leak-bits :datom-catalogs :compressed-padme)
                         :p2 (leak-bits :datom-catalogs :compressed-p2)}
       :leak-bits-adr {:today (leak-bits :adr-documents :today)
                       :unpadded (leak-bits :adr-documents :compressed-unpadded)
                       :padme (leak-bits :adr-documents :compressed-padme)
                       :p2 (leak-bits :adr-documents :compressed-p2)}})))

;; --- Generate ---------------------------------------------------------------
;; Competing designs, not one design with variants. Each names the axis it is
;; betting on and the evidence key the judge must look up for it.

(defn generate [ev]
  [{:id :h1-v3-plus-compressed-extent
    :title "Keep aiuefs-v3; compress the app extent only"
    :bet "smallest change: the catalog and its RSA signatures stay as they are"
    :bytes-key :identity        ; app extents are not the datom plane; no measured gain there
    :bytes-note "the datom plane is untouched, so the measured corpus shows no change"
    :leak-scheme :today
    :new-kernel-primitives [:inflate]
    :backends #{:jvm}
    :blocked "aiuefs-v3 has no CID, so a compressed extent is still unreachable by ayatori, IPNI or the pack; the query plane cannot see it at all"}

   {:id :h2-compress-the-pack
    :title "Compress the CARv2 pack as a whole"
    :bet "one codec call per pack, no per-leaf format change"
    :bytes-key :encrypt-then-compress
    :bytes-note "the pack holds sealed leaves; this is the order ADR-2608160100 measured"
    :leak-scheme :today
    :new-kernel-primitives [:inflate]
    :backends #{:jvm :kotoba-cljs}
    :blocked "measured to make the bytes grow, and it destroys range reads: a compressed pack cannot be served by HTTP Range, which is what :packed-blocks requires"}

   {:id :h3-compress-before-seal-bare
    :title "Compress each leaf before the AEAD; no padding, no dictionary"
    :bet "take the whole measured win and pay nothing for it"
    :bytes-key :compress-then-encrypt-none
    :bytes-note "the full measured saving"
    :leak-scheme :unpadded
    :new-kernel-primitives [:inflate]
    :backends #{:jvm :kotoba-cljs :kotoba-wasm}
    :risk "ciphertext length becomes a function of plaintext content; in a blinded index an adversary who can influence values learns from lengths (CRIME/BREACH class)"}

   {:id :h4-sealed-leaf-envelope
    :title "kotoba.leaf.v1: compress inside the seal, padme the length, name the dictionary by CID"
    :bet "keep the measured win, price the leak, and make the dictionary a reachable edge of the DAG"
    :bytes-key :compress-then-encrypt-padme
    :bytes-note "the saving after paying for length padding"
    :leak-scheme :padme
    :new-kernel-primitives [:inflate]
    :backends #{:jvm :kotoba-cljs :kotoba-wasm}}

   {:id :h5-conventional-fs-under-car
    :title "Put ext4/erofs/squashfs inside the image and keep the DAG above it"
    :bet "reuse a mature filesystem instead of writing one"
    :bytes-key :UNMEASURED
    :bytes-note "not measured here"
    :leak-scheme :UNMEASURED
    :new-kernel-primitives [:ext4-or-erofs-driver :inflate]
    :backends #{:jvm}
    :blocked "a second naming plane: inodes and paths that no CID names, so the same file has two identities and only one of them verifies"}

   {:id :h6-unixfs
    :title "Use UnixFS as the file format"
    :bet "an existing IPLD file layout with tooling"
    :bytes-key :UNMEASURED
    :bytes-note "not measured here"
    :leak-scheme :UNMEASURED
    :new-kernel-primitives [:dag-pb :inflate]
    :backends #{:jvm :kotoba-cljs}
    :note "root ADR-2608161500 already decided UnixFS is a layout adapter, not the public surface; adopting it as the format would reopen that"}

   {:id :h7-no-compression-more-replicas
    :title "Spend the bytes on replicas instead of on a codec"
    :bet "availability is worth more than space"
    :bytes-key :identity
    :bytes-note "unchanged by construction"
    :leak-scheme :today
    :new-kernel-primitives []
    :backends #{:jvm :kotoba-cljs :kotoba-wasm :native}}])

;; --- Reflect ----------------------------------------------------------------

(defn reflect [hyps]
  (mapv (fn [h]
          (assoc h :reflection
                 {:risk (cond (:blocked h) :blocked
                              (:risk h) :medium
                              (seq (:new-kernel-primitives h)) :low
                              :else :none)
                  :falsifier
                  (case (:id h)
                    :h1-v3-plus-compressed-extent "show a v3 extent being fetched by CID through ayatori"
                    :h2-compress-the-pack "show a compressed pack served by HTTP Range for one block"
                    :h3-compress-before-seal-bare "show that leaf lengths do not separate two chosen plaintexts"
                    :h4-sealed-leaf-envelope "show a rewritten header field decoding instead of failing the tag"
                    :h5-conventional-fs-under-car "show one identity for a file that both the inode layer and the DAG agree on"
                    :h6-unixfs "show UnixFS carrying the blinded-key/AEAD leaf without a second encoding"
                    :h7-no-compression-more-replicas "show replica cost per byte below the measured codec saving")}))
        hyps))

;; --- Rank -------------------------------------------------------------------
;; Deterministic and total. Two rules do the work, and both are stated rather
;; than tuned: a blocked hypothesis cannot win no matter how it scores, and an
;; :UNMEASURED byte figure cannot win either -- otherwise a design with no
;; evidence outranks one with evidence, which is the failure this workspace
;; keeps writing down.

(def weights {:bytes 0.55 :leak 0.20 :kernel 0.10 :reach 0.15})

(defn rank [ev hyps]
  (let [scored
        (mapv (fn [h]
                (let [bk (:bytes-key h)
                      bytes-frac (get ev bk :UNMEASURED)
                      has-bytes? (measured? bytes-frac)
                      lb (:leak-bits-datom ev)
                      today (:today lb)
                      bits (get lb (:leak-scheme h) :UNMEASURED)
                      ;; bits ABOVE what the plane already leaks. A scheme that
                      ;; leaks less than today scores above 1.0 on this axis
                      ;; before clamping -- that case is real and measured on
                      ;; the document plane, so the axis must be able to say it.
                      added (if (and (measured? bits) (measured? today)) (- bits today) :UNMEASURED)
                      leak-score (if (measured? added) (max 0.0 (- 1.0 (/ (max added 0.0) 12.0))) 0.0)
                      kernel-score (- 1.0 (/ (min (count (:new-kernel-primitives h)) 4) 4.0))
                      reach-score (/ (count (:backends h)) 4.0)
                      ;; lower bytes is better, so invert into a gain
                      bytes-score (if has-bytes? (max 0.0 (- 1.0 bytes-frac)) 0.0)
                      total (+ (* (:bytes weights) bytes-score)
                               (* (:leak weights) leak-score)
                               (* (:kernel weights) kernel-score)
                               (* (:reach weights) reach-score))]
                  (assoc h
                         :score {:bytes-fraction (if has-bytes?
                                                   (/ (js/Math.round (* 10000 bytes-frac)) 10000.0)
                                                   :UNMEASURED)
                                 :leak-bits-per-leaf bits
                                 :leak-bits-above-today (if (measured? added)
                                                          (/ (js/Math.round (* 1000 added)) 1000.0)
                                                          :UNMEASURED)
                                 :bytes bytes-score :leak leak-score
                                 :kernel kernel-score :reach reach-score
                                 :total (/ (js/Math.round (* 10000 total)) 10000.0)}
                         :admissible? (and has-bytes? (measured? added) (nil? (:blocked h))))))
              hyps)]
    (vec (sort-by (juxt (fn [h] (if (:admissible? h) 0 1))
                        (fn [h] (- (get-in h [:score :total]))))
                  scored))))

;; --- Evolve -----------------------------------------------------------------

(defn evolve [ev ranked]
  (let [winner (first (filter :admissible? ranked))
        d1k (:dictionary-gain-pct-datom-1k ev)
        d16k (:dictionary-gain-pct-datom-16k ev)
        dadr (:dictionary-gain-pct-adr-16k ev)
        best-decompress (->> (:codec-rows ev)
                             (filter #(measured? (:decompress-mbps %)))
                             (sort-by :decompress-mbps >) first)]
    (when-not best-decompress
      (die EXIT-COULD-NOT-ANSWER
           "no codec row carries a measured decompress throughput; refusing to"
           "name a kernel read codec. Re-run aiuefs-codec-bench.cljs."))
    {:winner (:id winner)
     :mutations
     [{:from :measurement/length-leak
       :change (str "the padding scheme is a PER-PLANE policy the format records, not a "
                    "constant. Measured bits per leaf a length observer learns: datom "
                    "plane today " (get-in ev [:leak-bits-datom :today])
                    ", unpadded " (get-in ev [:leak-bits-datom :unpadded])
                    ", padme " (get-in ev [:leak-bits-datom :padme])
                    ", power-of-two " (get-in ev [:leak-bits-datom :p2])
                    "; document plane today " (get-in ev [:leak-bits-adr :today])
                    ", unpadded " (get-in ev [:leak-bits-adr :unpadded])
                    ", padme " (get-in ev [:leak-bits-adr :padme])
                    ", power-of-two " (get-in ev [:leak-bits-adr :p2])
                    ". On the document plane padme compression leaks LESS than the "
                    "uncompressed plane does today while also being smaller: there is no "
                    "trade to make there, only a strictly better point. On the datom "
                    "plane there is a real trade, and the format's job is to say which "
                    "point was taken rather than to pick one for every plane.")}
      {:from :measurement/codec-choice
       :change (str "the kernel's read path implements exactly one codec: "
                    (name (:codec best-decompress))
                    " (fastest measured decompress at "
                    (:decompress-mbps best-decompress)
                    " MB/s). Producers may use others; the format carries the codec id "
                    "inside the seal so a reader that meets one it cannot decode refuses "
                    "by name instead of guessing.")}
      {:from :measurement/shared-dictionary
       :change (str "the dictionary link stays OPTIONAL and defaults to null. Held-out gain "
                    "on the datom plane collapses from " d1k "% at 1 KiB leaves to "
                    d16k "% at 16 KiB -- a large datom leaf already contains its own "
                    "dictionary. The document plane keeps " dadr
                    "% at 16 KiB because ADRs share vocabulary no single ADR contains. "
                    "So the dictionary is a property of the PLANE, not of the format, and "
                    "the format's job is only to name which one was used.")}
      {:from :hypothesis/h3
       :change (str "h3 (unpadded) is kept admissible and beaten on the measurement, not "
                    "on assertion. It buys "
                    (- (get-in ev [:leak-bits-datom :unpadded]) (get-in ev [:leak-bits-datom :padme]))
                    " extra bits of length leak per datom leaf for a byte saving of a "
                    "fraction of a percent. Iteration 1's FIRST ranking chose h3, because "
                    "the leak axis then counted plaintext header fields -- and h3 carries "
                    "the fewest fields while leaking the most. The axis was replaced with "
                    "the measured quantity rather than the weights being tuned until the "
                    "expected answer appeared.")}
      {:from :hypothesis/h7
       :change "kept as the null hypothesis in every future iteration. It is not absurd; it is what the measurement has to beat, and it does."}]}))

;; --- Meta -------------------------------------------------------------------

(defn meta-review []
  {:recurring-error-class
   "A measurement that answered a narrower question than the sentence it produced. ADR-2608160100 measured compression AFTER the AEAD, found 1.003, and wrote down `do not compress the pack` -- correct. It was then available to be read as `compression does not pay here`, which is false by a factor of ten, and nothing in the record distinguished the two readings."
   :countermeasure
   "Every bench result written by this loop names its subject in the :measurement key and reports the arm it did NOT take beside the one it did. The compression-order bench reports both orders in the same row for exactly this reason."
   :self-inflicted-instances
   ["The first run of this loop's own judge printed 0 for the encrypt-then-compress column because it read a key that does not exist and nil arithmetic in cljs yields 0. A ratio of 0 reads as `compression removed every byte`. `ratio` now returns :UNMEASURED for a non-numeric input."
    "The first ranking scored the leak axis by counting plaintext header fields -- a proxy that inverts the quantity it stands for, since the unpadded design carries the fewest fields and leaks the most. It ranked h3 first. The fix was to measure the length-distribution entropy and re-rank, not to adjust the weights; a weight chosen to produce an expected winner is not a judge."]
   :next-iteration-seed
   "Round-trip count, not bytes. This iteration measured space; ADR-2608160100 names round trips as the success metric for the pack layer, and nothing here measured one. The experiment is: build a real arrangement snapshot, commit it as one pack per commit, and count block GETs for the same query with and without co-location -- against the recorded ayatori baseline of 57 blocks / 916,304 bytes for a full score scan."})

(defn -main []
  (let [ev (evidence)
        hyps (-> (generate ev) reflect)
        ranked (rank ev hyps)
        ev-out (evolve ev ranked)
        payload {:loop :aiuefs-v4/coscientist
                 :iteration 1
                 :generated-at (.toISOString (js/Date.))
                 :judge :deterministic
                 :judge-note "ranking reads the bench EDN; no model output participates"
                 :weights weights
                 :evidence (dissoc ev :codec-rows)
                 :hypotheses ranked
                 :evolve ev-out
                 :meta (meta-review)}]
    (when out-path
      (.mkdirSync fs (.dirname path out-path) #js {:recursive true})
      (.writeFileSync fs out-path (with-out-str (pr payload))))
    (println (str/join "\t" ["rank" "hypothesis" "bytes-frac" "leak+bits" "total" "admissible"]))
    (doseq [[i h] (map-indexed vector ranked)]
      (println (str/join "\t" [(inc i) (name (:id h))
                               (get-in h [:score :bytes-fraction])
                               (get-in h [:score :leak-bits-above-today])
                               (get-in h [:score :total])
                               (if (:admissible? h) "yes" (str "no: " (or (:blocked h) "unmeasured")))])))
    (println)
    (println (str "WINNER\t" (name (:winner ev-out))))
    (when out-path (println (str "-> " out-path)))))

(-main)

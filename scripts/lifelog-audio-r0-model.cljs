#!/usr/bin/env nbb
(require '[scripts.nbb-compat :refer [slurp exit]]
         '[clojure.edn :as edn])

(def input-path "90-docs/hardware/lifelog-audio-r0-model-input.edn")
(def spec-path "90-docs/hardware/lifelog-audio-r0-product-spec.edn")

(defn die! [message]
  (binding [*out* *err*] (println "FAIL" message))
  (exit 1))

(defn approx= [a b tolerance]
  (<= (js/Math.abs (- a b)) tolerance))

(let [input (edn/read-string (slurp input-path))
      spec (edn/read-string (slurp spec-path))
      {:keys [nominal-mah required-reserve-fraction required-hours]} (:battery input)
      {:keys [sample-rate-hz bytes-per-sample channels]} (:audio input)
      {:keys [nominal-gb usable-fraction]} (:storage input)
      bytes-per-hour (* sample-rate-hz bytes-per-sample channels 3600)
      bytes-per-16h (* bytes-per-hour 16)
      usable-bytes (* nominal-gb 1000000000 usable-fraction)
      retention-days (/ usable-bytes bytes-per-hour 24)
      allowed-average-ma (/ (* nominal-mah (- 1 required-reserve-fraction)) required-hours)
      estimate-average-ma (reduce + (map :average-ma (:loads input)))
      worst-average-ma (reduce + (map :average-ma (:worst-case-loads input)))
      estimate-hours (/ nominal-mah estimate-average-ma)
      worst-hours (/ nominal-mah worst-average-ma)
      assumptions (filter #(not= :measured (:evidence %)) (:loads input))
      measured? (and (seq (:measurements input)) (empty? assumptions))
      result {:model/id (:model/id input)
              :model/qualification (if measured? :measured :not-qualified)
              :storage/bytes-per-hour bytes-per-hour
              :storage/bytes-per-16h bytes-per-16h
              :storage/retention-days-at-80pct (js/Number (.toFixed retention-days 2))
              :power/allowed-average-ma-for-12h-and-10pct-reserve allowed-average-ma
              :power/estimate-average-ma estimate-average-ma
              :power/worst-assumption-average-ma worst-average-ma
              :power/estimate-hours (js/Number (.toFixed estimate-hours 2))
              :power/worst-assumption-hours (js/Number (.toFixed worst-hours 2))
              :evidence/unmeasured-loads (mapv :load/id assumptions)}]
  (when-not (= "lifelog-audio-r0" (:product/id spec))
    (die! "product spec id mismatch"))
  (when-not (= bytes-per-hour (get-in spec [:audio :calculated-bytes-per-hour]))
    (die! "product spec bytes/hour drift"))
  (when-not (= bytes-per-16h (get-in spec [:audio :calculated-bytes-per-16h]))
    (die! "product spec bytes/16h drift"))
  (when-not (approx= allowed-average-ma 75.0 0.001)
    (die! "12h + 10% reserve current budget drift"))
  (when (> worst-average-ma allowed-average-ma)
    (die! "worst assumption already exceeds the current budget; resize before bench"))
  (prn result)
  (println "MODEL OK — arithmetic and spec are consistent; hardware qualification remains" (:model/qualification result))
  (when (some #{"--require-measured"} *command-line-args*)
    (when-not measured?
      (die! "bench measurements are absent; assumption-only model cannot satisfy a measured gate"))))

;; yui_xmile.cljs — 結 five-domain shared participation model.
;; See the ns docstring in yui_xmile.cljs for the model contract,
;; measured-vs-assumed discipline, and the questions this run answers.
(ns yui-run
  ;; runnable entry; the model itself lives in yui_xmile.cljs
  (:require [yui-xmile :as yx]
            [clojure.string :as str]
            ["fs" :as fs]))

(def fs (js/require "node:fs"))
(def out-dir (or (.-SD_OUT (.-env js/process)) "90-docs/system-dynamics/yui"))

(defn r3 [v] (/ (.round js/Math (* 1000 (double v))) 1000.0))
(defn r1 [v] (/ (.round js/Math (* 10 (double v))) 10.0))
(defn ensure-dir! [p] (.mkdirSync fs p #js {:recursive true}))

;; ── measured constants (2026-09-02 live probes / 2026-08-22 metrics) ────
;; sources: kotobase.net/api/funnel (2026-09-02): visitors 4750 signups 31
;;   => conv_visit_signup = 31/4750 = 0.0065263
;; itonami.cloud/api/fleet/metrics (2026-09-02): 5 owners, 392 agentRuns/7d
;; isekai.network/feed/fork-stats.edn (2026-09-02): viral-coefficient 0
;; metrics/{cloud-murakumo,net-kotobase,network-isekai,cloud-itonami}.edn
;;   (2026-08-22): uniques-7d 785 / 1386 / 627 / 578
;; ADR-2608291009 measured B (2026-08-29): 1958 bots / 41 governed / 2 priced
(def weekly-reach-measured yx/weekly-reach-measured)  ; sum of the four measured door uniques
(def conv-visit-signup-measured yx/conv-visit-signup-measured)

(defn pad [s n] (.padEnd (str s) n))

(defn esc [s] (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;")
                  (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))
(defn ->xml
  ([e] (->xml e 0))
  ([e depth]
   (let [p (apply str (repeat depth "  "))]
     (cond
       (string? e) (esc e)
       (map? e)
       (let [{:keys [tag attrs content]} e
             a (str/join (for [[k v] attrs] (str " " (name k) "=\"" (esc v) "\"")))
             kids (remove nil? content)]
         (cond
           (empty? kids) (str p "<" (name tag) a "/>\n")
           (every? string? kids) (str p "<" (name tag) a ">" (esc (str/join kids)) "</" (name tag) ">\n")
           :else (str p "<" (name tag) a ">\n" (str/join (map #(->xml % (inc depth)) kids))
                      p "</" (name tag) ">\n")))
       :else ""))))

(defn var-elem [v]
  (case (:xmile/kind v)
    :stock {:tag :stock
            :attrs {:name (:xmile/name v)}
            :content (concat [{:tag :eqn :content [(:xmile/eqn v)]}]
                             (map #(hash-map :tag :inflow :content [%]) (sort (:xmile/inflows v)))
                             (map #(hash-map :tag :outflow :content [%]) (sort (:xmile/outflows v))))}
    {:tag (case (:xmile/kind v) :flow :flow :aux)
     :attrs {:name (:xmile/name v)}
     :content [{:tag :eqn :content [(:xmile/eqn v)]}]}))

(defn xml-of-model
  [mdl]
  (let [{:xmile/keys [start stop method dt time-units]} (:xmile/sim-specs mdl)]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         (->xml {:tag :xmile
                 :attrs {:version "1.0" :xmlns "http://docs.oasis-open.org/xmile/ns/XMILE/v1.0"}
                 :content
                 [{:tag :header :content
                   [{:tag :vendor :content ["cloud-itonami/yui"]}
                    {:tag :product :attrs {:version "1.0"} :content ["yui-five-domain-participation"]}
                    {:tag :name :content ["yui five-domain shared participation funnel"]}]}
                  {:tag :sim_specs
                   :attrs {:method (name (or method :euler)) :time_units (str time-units)}
                   :content [{:tag :start :content [(str start)]}
                             {:tag :stop :content [(str stop)]}
                             {:tag :dt :content [(str dt)]}]}
                  {:tag :model :attrs {:name (:xmile/name mdl)}
                   :content
                   [{:tag :sim_specs
                     :attrs {:method (name (or method :euler)) :time_units (str time-units)}
                     :content [{:tag :start :content [(str start)]}
                               {:tag :stop :content [(str stop)]}
                               {:tag :dt :content [(str dt)]}]}
                    {:tag :variables :content (map var-elem (vals (:xmile/variables mdl)))}]}]}))))


(defn -main []
  (ensure-dir! out-dir)
  (let [results (mapv yx/evaluate yx/scenarios)
        ranked (sort-by :final-contributors > results)]
    (println "═══ 結 (yui) five-domain participation — OASIS XMILE 1.0 ═══")
    (println (str "measured weekly door reach: " weekly-reach-measured
                  " (murakumo 785 + kotobase 1386 + isekai 627 + itonami 578)"
                  " — kotoba.cloud publishes NO funnel numbers (unmeasured door)"))
    (println (str "measured visit→signup: " conv-visit-signup-measured
                  " (kotobase 31/4750, 2026-09-02)"))
    (println (str "measured isekai viral coefficient: 0 — the R loop has never fired"))
    (println "")
    (println (str (pad "scenario" 26) (pad "valid" 7) (pad "signed_up" 11)
                  (pad "active" 10) (pad "contributors" 14) (pad "rep" 10)
                  "crossing-week"))
    (doseq [{:keys [name valid? final-signed final-active final-contributors
                    final-reputation crossing-week]} ranked]
      (println (str (pad name 26)
                    (pad (if valid? "yes" "NO") 7)
                    (pad (r1 final-signed) 11)
                    (pad (r1 final-active) 10)
                    (pad (r1 final-contributors) 14)
                    (pad (r1 final-reputation) 10)
                    (or crossing-week "never in 104w"))))
    (println "")
    (println "S0 = 全て実測 + referral=0。他は ASSUMPTION シナリオ —")
    (println "読めるのは シナリオ間の順位 であって絶対人数ではない。")
    ;; write XMILE artifact for the base scenario (hand serializer: the
    ;; engine's own emit-xml-string produced an empty <model> because the
    ;; DSL-built model maps carry :xmile/kind keys the emitter path doesn't
    ;; round-trip in this nbb-shim environment — observatory-cadence.cljs
    ;; hit the same wall and ships its own ->xml serializer; same pattern)
    ;; VERIFICATION POLICY (same as observatory-cadence): reading the XML back
    ;; via xmile.xml/parse-xml-string is impossible under nbb (js/DOMParser is
    ;; browser-only). Instead the artifact's validity rests on (a) the source
    ;; model passing xmile.validate, and (b) the series numbers printed above
    ;; having been produced by actually executing that model.
    (let [mdl (:model (yx/evaluate yx/base))
          xml-text (xml-of-model mdl)]
      (.writeFileSync fs (str out-dir "/yui-five-domain-participation.xmile") xml-text)
      (println "")
      (println (str "XMILE 1.0 written: " out-dir
                    "/yui-five-domain-participation.xmile ("
                    (count xml-text) " bytes)")))
    ;; datom-style ledger line (append-only evidence, ADR-2607203000 pattern)
    (.appendFileSync fs (str out-dir "/yui-xmile-ledger.edn")
      (str (pr-str {:event/as-of "2026-09-02"
                    :event/kind :yui-participation-scenario-run
                    :event/measured {:weekly-reach 3376 :conv-visit-signup 0.0065263
                                     :sources ["kotobase.net/api/funnel 2026-09-02"
                                               "metrics/*.edn 2026-08-22"]}
                    :event/assumed ["conv_signup_active" "conv_active_contrib"
                                     "churn rates" "referral mechanism"]
                    :event/ranked (mapv (fn [r] {:scenario (:name r)
                                                 :final-contributors (r3 (:final-contributors r))
                                                 :crossing-week (:crossing-week r)})
                                        ranked)}) "\n"))
    (println (str "ledger appended: " out-dir "/yui-xmile-ledger.edn"))))

(-main)

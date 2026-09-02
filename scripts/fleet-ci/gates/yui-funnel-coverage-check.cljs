;; yui/funnels.cljs — per-door machine-readable funnel surface coverage.
;; Evidence for ADR-2609022000's yui-h4 (publish-funnel-per-domain).
;;
;; The lesson this encodes (endpoint-health.edn spirit): a 200 is NOT health.
;; isekai.network and murakumo.cloud / kotoba.cloud return 200 with
;; text/html — the SPA fallback, not a funnel. Reading the status code alone
;; would have reported "3/5 doors publish a funnel"; reading the CONTENT TYPE
;; and body says 2/5 (kotobase.json, itonami.json), 1/5 EDN feed
;; (isekai fork-stats), 2/5 SPA-fallback + 0 published (murakumo, kotoba).
;;
;; Run (superproject root):
;;   nbb --classpath "scripts:scripts/nbb_compat" scripts/fleet-ci/gates/yui-funnel-coverage-check.cljs --min 2
;;
;; Exit: 0 = machine-readable funnel surface count >= --min, 1 = below, 2 = probe failed.
(require '[clojure.string :as str]
         '[scripts.nbb-compat :refer [sh]])

(def args (js->clj (.slice (.-argv js/process) 2)))
(def min-doors
  (let [i (.indexOf args "--min")]
    (js/parseInt (if (and (>= i 0) (< (inc i) (count args)))
                   (nth args (inc i) "2")
                   "2") 10)))

(def probes
  [{:domain "kotobase.net"   :url "https://kotobase.net/api/funnel"}
   {:domain "murakumo.cloud" :url "https://murakumo.cloud/api/funnel"}
   {:domain "isekai.network" :url "https://isekai.network/feed/fork-stats.edn"}
   {:domain "kotoba.cloud"   :url "https://kotoba.cloud/api/funnel"}
   {:domain "itonami.cloud"  :url "https://itonami.cloud/api/funnel"}])

(defn- probe-one [{:keys [domain url]}]
  (let [{:keys [exit out]} (sh "curl" "-sS" "-m" "10" "-A" "yui/1 (funnel-coverage probe)"
                               "-o" "/dev/null" "-w" "%{http_code} %{content_type}" url)]
    (if (zero? exit)
      (let [[code ct] (str/split (str/trim out) #" " 2)
            status (js/parseInt code 10)
            ct (str/lower-case (str ct))]
        {:domain domain :url url :status status :content-type ct
         :kind (cond
                 (= 404 status) :no-endpoint
                 (str/includes? ct "html") :spa-fallback
                 (= 200 status) :machine-readable
                 :else (keyword (str "http-" status)))})
      {:domain domain :url url :kind :probe-failed})))

(def results (mapv probe-one probes))
(doseq [r results]
  (println (str (:domain r) "  " (name (:kind r))
                (when (:status r) (str " (" (:status r) " " (:content-type r) ")"))
                "  " (:url r))))

(let [readable (count (filter #(= :machine-readable (:kind %)) results))
      spa (count (filter #(= :spa-fallback (:kind %)) results))
      none (count (filter #(= :no-endpoint (:kind %)) results))]
  (println "")
  (println (str "machine-readable funnel doors: " readable "/5"
                " (spa-fallback 200-but-not-data: " spa ", no-endpoint 404: " none ")"))
  (println (str "yui-h4 work list: the doors that need a real funnel surface = "
                (str/join ", " (map :domain (filter #(contains? #{:spa-fallback :no-endpoint} (:kind %)) results)))))
  (if (>= readable min-doors)
    (do (println (str "yui-funnel-coverage: OK — " readable " >= --min " min-doors))
        (.exit js/process 0))
    (do (println (str "yui-funnel-coverage: FAIL — " readable " < --min " min-doors))
        (.exit js/process 1))))

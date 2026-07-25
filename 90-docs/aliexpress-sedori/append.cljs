#!/usr/bin/env nbb
;; 90-docs/aliexpress-sedori/append.cljs — append-only writer for
;; observations-ledger.edn (one EDN map per line, :event/seq monotonic).
;; Mirrors scripts/adr-ledger-append.cljs's lock + verify discipline: do NOT
;; hand-edit the ledger file, only append through this script.
;;
;; Usage:
;;   nbb --classpath ".:scripts/nbb_compat" 90-docs/aliexpress-sedori/append.cljs \
;;     --batch /path/to/batch.edn
;;   (batch.edn is a vector of event maps, no :event/seq — assigned here)
;;
;;   nbb --classpath ".:scripts/nbb_compat" 90-docs/aliexpress-sedori/append.cljs --verify
;;
;; How batches actually get produced (read before scheduling anything): there
;; is no unattended scraper feeding this. Measured 2026-07-25
;; (90-docs/adr/2607251500-aliexpress-sedori-observation-dataset.edn): a plain
;; HTTP fetch against aliexpress.com is redirected straight to a CAPTCHA/JS
;; challenge page, so listing data is collected via the WebFetch tool (JS
;; capable, sanctioned) at low human-paced frequency, and seller/store name
;; only via an interactive Claude-in-Chrome session (product detail pages are
;; client-rendered — no JS-executing session, no seller data). Both are
;; agent-driven collection passes that produce a batch.edn by hand, not a
;; cron job. If a future WebFetch/browse run hits the challenge page, record
;; zero results for that pass and say so — do not try to get past it
;; (header/fingerprint spoofing, proxy rotation, solving the challenge, or
;; retrying with varied requests to find one that slips through are all
;; bot-detection evasion and are out of bounds here).

(require '[scripts.nbb-compat :refer [slurp spit-append file exit format sleep!]]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))

(def ledger-dir "90-docs/aliexpress-sedori")
(def ledger-path (str ledger-dir "/observations-ledger.edn"))
(def lock-path (str ledger-dir "/.append.lock"))
(def lock-stale-ms 30000)
(def lock-retry-ms 200)
(def lock-max-wait-ms 15000)

(def header
  (str
   ";; 90-docs/aliexpress-sedori/observations-ledger.edn — append-only\n"
   ";; AliExpress product/seller/price observation events (one EDN map per\n"
   ";; line, DataScript-transactable). Never hand-edited after being written;\n"
   ";; only append through 90-docs/aliexpress-sedori/append.cljs so\n"
   ";; :event/seq stays monotonic. Query with\n"
   ";; 90-docs/aliexpress-sedori/query.cljs. See README.md in this directory\n"
   ";; for schema and how collection actually works (WebFetch listing scan +\n"
   ";; interactive Claude-in-Chrome detail enrichment — no unattended scraper).\n"))

(defn parse-args [argv]
  (loop [args (seq argv) out {}]
    (if (empty? args)
      out
      (let [[k v & more] args]
        (cond
          (= k "--verify") (recur (rest args) (assoc out :verify true))
          (and (string? k) (str/starts-with? k "--"))
          (recur more (assoc out (keyword (subs k 2)) v))
          :else (recur (rest args) out))))))

;; ---------- locking (same pattern as scripts/adr-ledger-append.cljs) ----------

(defn- lock-status []
  (try
    {:exists? true
     :age-ms (- (.getTime (js/Date.)) (.getTime (.-mtime (.statSync fs lock-path))))}
    (catch :default _ {:exists? false})))

(defn- try-acquire-lock! []
  (try
    (.closeSync fs (.openSync fs lock-path "wx"))
    (.writeFileSync fs lock-path (str "pid=" (.-pid js/process)
                                       " at=" (.toISOString (js/Date.)) "\n"))
    true
    (catch :default e
      (if (= (.-code e) "EEXIST") false (throw e)))))

(defn acquire-lock! []
  (.mkdirSync fs ledger-dir #js {:recursive true})
  (loop [waited 0]
    (cond
      (try-acquire-lock!) :acquired

      :else
      (let [{:keys [exists? age-ms]} (lock-status)]
        (cond
          (and exists? (> age-ms lock-stale-ms))
          (do (println (str "[aliexpress-sedori/append] taking over stale lock (>" lock-stale-ms "ms old): " lock-path))
              (try (.unlinkSync fs lock-path) (catch :default _ nil))
              (recur waited))

          (>= waited lock-max-wait-ms)
          (throw (ex-info (str "timed out waiting for " lock-path
                                " after " lock-max-wait-ms "ms — another append in progress?")
                           {}))

          :else
          (do (sleep! lock-retry-ms) (recur (+ waited lock-retry-ms))))))))

(defn release-lock! []
  (try (.unlinkSync fs lock-path) (catch :default _ nil)))

(defn with-lock* [thunk]
  (acquire-lock!)
  (try (thunk) (finally (release-lock!))))

;; ---------- ledger read ----------

(defn existing-lines []
  (let [f (file ledger-path)]
    (if (.isFile f)
      (->> (str/split-lines (slurp ledger-path))
           (remove str/blank?)
           (remove #(str/starts-with? (str/trim %) ";")))
      [])))

(defn existing-events []
  (keep (fn [line] (try (edn/read-string line) (catch :default _ nil)))
        (existing-lines)))

(defn next-seq []
  (let [seqs (keep :event/seq (existing-events))]
    (inc (if (seq seqs) (apply max seqs) 0))))

;; ---------- append ----------

(def valid-types #{:product-observed :seller-observed :resale-price-observed})

(defn- validate-event! [{:keys [event/type product/id] :as ev}]
  (when-not (contains? valid-types type)
    (throw (ex-info (str "unknown :event/type " (pr-str type) " in " (pr-str ev)) {:event ev})))
  (when (str/blank? (str id))
    (throw (ex-info (str ":product/id required on every event, got " (pr-str ev)) {:event ev}))))

(defn append-batch! [batch-path]
  (let [batch (edn/read-string (slurp batch-path))]
    (when-not (vector? batch)
      (throw (ex-info "batch file must contain a single EDN vector of event maps" {:path batch-path})))
    (doseq [ev batch] (validate-event! ev))
    (with-lock*
      (fn []
        (let [f (file ledger-path)
              start-seq (next-seq)]
          (when-not (.isFile f)
            (spit-append ledger-path header))
          (doseq [[i ev] (map-indexed vector batch)]
            (let [event (assoc ev
                                :event/seq (+ start-seq i)
                                :event/at (or (:event/at ev) (.toISOString (js/Date.))))]
              (spit-append ledger-path (str (pr-str event) "\n"))))
          (println (format "appended %s event(s), seq %s..%s -> %s"
                            (count batch) start-seq (+ start-seq (dec (count batch))) ledger-path)))))))

;; ---------- verify ----------

(defn verify! []
  (let [events (existing-events)
        seqs (map :event/seq events)
        dup-seqs (->> (frequencies seqs) (filter (fn [[_ n]] (> n 1))) (map first))
        missing-seq (filter (comp nil? :event/seq) events)
        sorted-seqs (sort (remove nil? seqs))
        non-monotonic? (not= sorted-seqs (range 1 (inc (count sorted-seqs))))
        product-ids (set (keep :product/id (filter #(= :product-observed (:event/type %)) events)))
        ;; every non-product event hangs off a :product/id; an event referencing
        ;; an id we never observed as a product is a typo or a lost batch, not
        ;; valid data — same orphan check the ADR ledger does against adr/id.
        orphans (filter (fn [e] (and (not= :product-observed (:event/type e))
                                      (not (contains? product-ids (:product/id e)))))
                         events)
        problems (cond-> []
                   (seq dup-seqs) (conj (str "duplicate :event/seq values: " (pr-str dup-seqs)))
                   (seq missing-seq) (conj (str (count missing-seq) " event(s) missing :event/seq"))
                   non-monotonic? (conj (str "seqs not a contiguous 1.." (count sorted-seqs)
                                              " run (race condition or hand-edit?): " (pr-str sorted-seqs)))
                   (seq orphans)
                   (conj (str (count orphans) " non-product event(s) reference a :product/id "
                              "with no matching :product-observed event: "
                              (pr-str (distinct (map :product/id orphans))))))]
    (println (format "aliexpress-sedori verify: events=%s products=%s problems=%s"
                      (count events) (count product-ids) (count problems)))
    (doseq [p problems] (println "  -" p))
    (if (seq problems)
      (do (println "aliexpress-sedori verify: FAIL") (exit 1))
      (println "aliexpress-sedori verify: OK"))))

;; ---------- entry ----------

(defn -main [& argv]
  (let [{:keys [verify batch]} (parse-args argv)]
    (cond
      verify (verify!)
      (not (str/blank? batch)) (append-batch! batch)
      :else
      (do (println "usage: nbb 90-docs/aliexpress-sedori/append.cljs --batch <path-to-edn-vector-of-events>")
          (println "       nbb 90-docs/aliexpress-sedori/append.cljs --verify")
          (exit 1)))))

(apply -main *command-line-args*)

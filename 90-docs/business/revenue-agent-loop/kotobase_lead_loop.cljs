#!/usr/bin/env nbb
;; Evidence-floor helpers for kotobase.net lead automations.
;;
;;   nbb 90-docs/business/revenue-agent-loop/kotobase_lead_loop.cljs funnel-pulse
;;   nbb 90-docs/business/revenue-agent-loop/kotobase_lead_loop.cljs next-form
;;   nbb 90-docs/business/revenue-agent-loop/kotobase_lead_loop.cljs reply-scan --unanswered gmail-unauthenticated
;;   nbb 90-docs/business/revenue-agent-loop/kotobase_lead_loop.cljs reply-scan --scanned N [--matches-edn EDN]
;;
;; Never sends forms, never opens a browser, never raises a score, and never
;; treats scanned=0 / fetch-fail / missing files as CLEAN.
;; Exit 0 = measured and recorded (including UNCHANGED). Exit 2 = unanswered.
;; Exit 1 = bad invocation.

(ns kotobase-lead-loop
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def process (js/require "node:process"))

(def funnel-url "https://kotobase.net/api/funnel")
(def skip-ids #{"helpfeel"})
(def internal-domains
  #{"gftd.co.jp" "gftd.ai" "junkawasaki.com" "itonami.cloud"
    "kotobase.net" "murakumo.cloud"})

(defn- die [code & msg]
  (binding [*print-fn* *print-err-fn*]
    (println (str/join " " msg)))
  (.exit process code))

(defn- now-iso []
  (let [d (js/Date.)
        tz-min (- (.getTimezoneOffset d))
        sign (if (neg? tz-min) "-" "+")
        abs (js/Math.abs tz-min)
        hh (.padStart (str (quot abs 60)) 2 "0")
        mm (.padStart (str (mod abs 60)) 2 "0")]
    (str (.getFullYear d) "-"
         (.padStart (str (inc (.getMonth d))) 2 "0") "-"
         (.padStart (str (.getDate d)) 2 "0") "T"
         (.padStart (str (.getHours d)) 2 "0") ":"
         (.padStart (str (.getMinutes d)) 2 "0") ":"
         (.padStart (str (.getSeconds d)) 2 "0")
         sign hh ":" mm)))

(defn- find-root []
  (loop [dir (.cwd process)]
    (cond
      (.existsSync fs (.join path dir "90-docs" "business")) dir
      (= dir (.dirname path dir)) nil
      :else (recur (.dirname path dir)))))

(defn- slurp-utf8 [p]
  (.readFileSync fs p "utf8"))

(defn- spit-utf8 [p s]
  (.mkdirSync fs (.dirname path p) #js {:recursive true})
  (.writeFileSync fs p s "utf8"))

(defn- read-edn [p]
  (edn/read-string (slurp-utf8 p)))

(defn- pr-edn [x]
  (binding [*print-namespace-maps* false]
    (pr-str x)))

(defn- append-edn-line [p m]
  (let [line (str (pr-edn m) "\n")
        prev (when (.existsSync fs p) (slurp-utf8 p))]
    (spit-utf8 p (str prev line))))

(defn- last-edn-line [p]
  (when (.existsSync fs p)
    (let [lines (->> (str/split-lines (slurp-utf8 p))
                     (remove str/blank?)
                     (remove #(str/starts-with? % ";")))]
      (when (seq lines)
        (edn/read-string (last lines))))))

(defn- print-kv [k v]
  (println (str k "\t" v)))

(defn- fetch-json [url]
  (-> (js/fetch url #js {:method "GET"
                         :headers #js {"accept" "application/json"
                                       "user-agent" "kotobase-lead-loop/1.0"}
                         :signal (js/AbortSignal.timeout 20000)})
      (.then (fn [r]
               (-> (.text r)
                   (.then (fn [t]
                            {:status (.-status r)
                             :ok (.-ok r)
                             :body t})))))
      (.catch (fn [e]
                {:status nil
                 :ok false
                 :error (or (.-message e) (str e))}))))

(defn- parse-funnel [body]
  (let [j (js->clj (js/JSON.parse body) :keywordize-keys true)
        f (:funnel j)]
    {:visitors (or (:visitors f) 0)
     :signups (or (:signups f) 0)
     :checkouts (or (:checkouts f) 0)
     :by-source (:by-source j)
     :x402 (:x402 j)}))

(defn- sent? [acct]
  "True only after a thank-you / success / sent-at. :account/send? means
  selected as the WIP send target, not that it was submitted."
  (let [st (:account/status acct)]
    (or (= st :sent)
        (= st :thank-you)
        (some? (:account/sent-at acct))
        (some? (:account/thank-you-at acct)))))

(defn- beekle-blocked? [acct]
  (and (= (:account/id acct) "beekle")
       (or (= (:account/status acct) :blocked)
           (str/includes? (str (:account/blocked-reason acct)) "Not sent")
           (str/includes? (str (:account/blocked-reason acct)) "Turnstile"))))

(defn- load-accounts [root]
  (let [p (.join path root "90-docs" "business" "net-kotobase-named-accounts.edn")]
    (if-not (.existsSync fs p)
      {:path p :present? false :accounts []}
      {:path p
       :present? true
       :accounts (filterv :account/id (read-edn p))})))

(defn- cmd-funnel-pulse [root]
  (-> (fetch-json funnel-url)
      (.then
       (fn [res]
         (let [pulse-path (.join path root "90-docs" "business"
                                 "metrics" "net-kotobase-funnel-pulses.edn")
               as-of (now-iso)]
           (cond
             (or (not (:ok res)) (nil? (:status res)))
             (do
               (append-edn-line pulse-path
                                {:as-of as-of
                                 :kind :funnel-pulse
                                 :scanned 0
                                 :ok false
                                 :unanswered true
                                 :url funnel-url
                                 :status (:status res)
                                 :error (or (:error res) (:body res))
                                 :score-raised? false
                                 :note "SCANNED=0 is not clean. Fetch failed. Do not treat as pass or demand."})
               (print-kv "SCANNED" 0)
               (print-kv "UNANSWERED" (or (:error res) (str "http-" (:status res))))
               (print-kv "SCORE" "unchanged")
               (print-kv "RESULT" "unanswered")
               (.exit process 2))

             :else
             (let [parsed (try (parse-funnel (:body res))
                               (catch js/Error e
                                 {:parse-error (.-message e)}))]
               (if (:parse-error parsed)
                 (do
                   (append-edn-line pulse-path
                                    {:as-of as-of
                                     :kind :funnel-pulse
                                     :scanned 0
                                     :ok false
                                     :unanswered true
                                     :url funnel-url
                                     :status (:status res)
                                     :error (:parse-error parsed)
                                     :score-raised? false
                                     :note "Body present but not parseable. Not a pass."})
                   (print-kv "SCANNED" 0)
                   (print-kv "UNANSWERED" (:parse-error parsed))
                   (print-kv "SCORE" "unchanged")
                   (print-kv "RESULT" "unanswered")
                   (.exit process 2))
                 (let [metrics-path (.join path root "90-docs" "business"
                                          "metrics" "net-kotobase.edn")
                       prev (last-edn-line pulse-path)
                       snapshot (when (.existsSync fs metrics-path)
                                  (:funnel (read-edn metrics-path)))
                       prev-f (or (:funnel prev) snapshot)
                       delta {:visitors (- (:visitors parsed) (or (:visitors prev-f) (:visitors parsed)))
                              :signups (- (:signups parsed) (or (:signups prev-f) (:signups parsed)))
                              :checkouts (- (:checkouts parsed) (or (:checkouts prev-f) (:checkouts parsed)))}
                       unchanged? (and (zero? (:visitors delta))
                                       (zero? (:signups delta))
                                       (zero? (:checkouts delta)))
                       external-change? (pos? (:checkouts delta))
                       rec {:as-of as-of
                            :kind :funnel-pulse
                            :scanned 1
                            :ok true
                            :unanswered false
                            :url funnel-url
                            :status (:status res)
                            :funnel (select-keys parsed [:visitors :signups :checkouts])
                            :by-source (:by-source parsed)
                            :x402 (:x402 parsed)
                            :delta delta
                            :unchanged? unchanged?
                            :external-funnel-change? external-change?
                            :score-raised? false
                            :note (cond
                                    unchanged?
                                    "Silence / zero change is not a pass or demand."
                                    (not external-change?)
                                    "Visitor/signup movement without checkout is not demand. Do not raise score."
                                    :else
                                    "Checkout count moved. Record only; do not count self-purchase / internal / test payment.")}]
                   (append-edn-line pulse-path rec)
                   (print-kv "SCANNED" 1)
                   (print-kv "VISITORS" (:visitors parsed))
                   (print-kv "SIGNUPS" (:signups parsed))
                   (print-kv "CHECKOUTS" (:checkouts parsed))
                   (print-kv "DELTA" (pr-edn delta))
                   (print-kv "UNCHANGED" unchanged?)
                   (print-kv "EXTERNAL-FUNNEL-CHANGE" external-change?)
                   (print-kv "SCORE" "unchanged")
                   (print-kv "RESULT" (if unchanged? "unchanged" "recorded"))
                   (.exit process 0))))))))))

(defn- cmd-next-form [root]
  (let [loaded (load-accounts root)
        as-of (now-iso)]
    (when-not (:present? loaded)
      (print-kv "SCANNED" 0)
      (print-kv "UNANSWERED" "named-accounts-file-missing")
      (print-kv "SCORE" "unchanged")
      (print-kv "RESULT" "unanswered")
      (print-kv "NOTE" "Missing list is not zero outreach. Do not invent the next form.")
      (.exit process 2))
    (let [accts (:accounts loaded)
          beekle (first (filter #(= "beekle" (:account/id %)) accts))
          helpfeel (first (filter #(= "helpfeel" (:account/id %)) accts))
          elyza (first (filter #(= "elyza" (:account/id %)) accts))
          iasys (first (filter #(= "iasys" (:account/id %)) accts))
          beekle-sent? (and beekle (sent? beekle) (not (beekle-blocked? beekle)))
          wip (if beekle-sent?
                (->> accts
                     (remove #(contains? skip-ids (:account/id %)))
                     (remove sent?)
                     (remove #(= "beekle" (:account/id %)))
                     first)
                beekle)
          out {:as-of as-of
               :kind :next-form
               :scanned (count accts)
               :wip-id (or (:account/id wip) :none)
               :wip-org (:account/org wip)
               :wip-url (:account/contact-path wip)
               :beekle-sent? beekle-sent?
               :beekle-status (:account/status beekle)
               :skip-ids (vec skip-ids)
               :helpfeel-skip-reason (or (:account/skip-reason helpfeel)
                                         "competitor-adjacent; do not contact")
               :elyza {:id "elyza"
                       :url-confirmed? (boolean (:account/url-confirmed-at elyza))
                       :url (:account/contact-path elyza)
                       :may-suggest? false}
               :iasys {:id "iasys"
                       :url-confirmed? (boolean (:account/url-confirmed-at iasys))
                       :url (:account/contact-path iasys)
                       :may-suggest? false}
               :do-not-send true
               :do-not-bypass-captcha true
               :do-not-solicit-paid true
               :score-raised? false
               :instruction (if beekle-sent?
                              "Beekle has send confirmation. Surface only the next official form. Re-confirm ELYZA/iASYS URLs with a live GET before naming them. Skip Helpfeel. Do not send."
                              "WIP=1 remains Beekle until a thank-you/success exists. Do not surface Laboro.AI, Enison, ELYZA, iASYS, or anyone else. Skip Helpfeel. Do not send. Do not bypass Turnstile.")}]
      (print-kv "SCANNED" (count accts))
      (print-kv "WIP" (:wip-id out))
      (print-kv "WIP-URL" (or (:wip-url out) ""))
      (print-kv "BEEKLE-SENT" beekle-sent?)
      (print-kv "SKIP" "helpfeel")
      (print-kv "MAY-SUGGEST-ELYZA" false)
      (print-kv "MAY-SUGGEST-IASYS" false)
      (print-kv "DO-NOT-SEND" true)
      (print-kv "SCORE" "unchanged")
      (print-kv "RESULT" (if beekle-sent? "next-after-beekle" "beekle-unsent"))
      (println (pr-edn out))
      (.exit process 0))))

(defn- parse-args [argv]
  (loop [xs argv acc {}]
    (if-not (seq xs)
      acc
      (let [[a & more] xs]
        (cond
          (= a "--unanswered")
          (recur (rest more) (assoc acc :unanswered (first more)))
          (= a "--scanned")
          (recur (rest more) (assoc acc :scanned (js/parseInt (first more) 10)))
          (= a "--matches-edn")
          (recur (rest more) (assoc acc :matches (edn/read-string (first more))))
          (str/starts-with? a "--")
          (die 1 "unknown flag" a)
          :else
          (die 1 "unexpected arg" a))))))

(defn- cmd-reply-scan [root opts]
  (let [as-of (now-iso)
        pulse-path (.join path root "90-docs" "business"
                          "metrics" "net-kotobase-reply-scans.edn")
        unanswered (:unanswered opts)
        scanned (:scanned opts)]
    (cond
      (some? unanswered)
      (do
        (append-edn-line pulse-path
                         {:as-of as-of
                          :kind :reply-scan
                          :scanned 0
                          :ok false
                          :unanswered true
                          :reason unanswered
                          :internal-domains (vec internal-domains)
                          :score-raised? false
                          :note "Mail was not scanned. SCANNED=0 is not zero replies and not a pass. Do not dump keychains."})
        (print-kv "SCANNED" 0)
        (print-kv "UNANSWERED" unanswered)
        (print-kv "SCORE" "unchanged")
        (print-kv "RESULT" "unanswered")
        (.exit process 2))

      (nil? scanned)
      (die 1 "reply-scan requires --unanswered REASON or --scanned N")

      (js/Number.isNaN scanned)
      (die 1 "--scanned must be an integer")

      :else
      (let [matches (or (:matches opts) [])
            rec {:as-of as-of
                 :kind :reply-scan
                 :scanned scanned
                 :ok true
                 :unanswered false
                 :match-count (count matches)
                 :matches matches
                 :score-raised? false
                 :note (if (zero? scanned)
                         "Scanned with 0 messages in scope is a measurement, not demand. Do not raise score."
                         "Inbound matches are discovery evidence only. Do not solicit paid plans. Do not send forms.")}]
        (append-edn-line pulse-path rec)
        (print-kv "SCANNED" scanned)
        (print-kv "MATCHES" (count matches))
        (print-kv "SCORE" "unchanged")
        (print-kv "RESULT" "recorded")
        (.exit process 0)))))

(defn- usage []
  (die 1 "usage: nbb kotobase_lead_loop.cljs funnel-pulse|next-form|reply-scan [flags]"))

(defn -main [& argv]
  (let [root (find-root)
        [cmd & more] argv]
    (when-not root
      (die 1 "cannot find workspace root containing 90-docs/business"))
    (case cmd
      "funnel-pulse" (cmd-funnel-pulse root)
      "next-form" (cmd-next-form root)
      "reply-scan" (cmd-reply-scan root (parse-args more))
      (usage))))

(apply -main (drop 3 (vec (js->clj js/process.argv))))

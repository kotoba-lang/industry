#!/usr/bin/env nbb
;; scripts/murakumo-oem-rfq-tick.cljs — Mouse / Sycom OEM RFQ の受信を測る。
;; モデルは居ない。候補の有無だけを JSON で返す。
;;
;; 不変条件:
;;   - 鍵が無い / HTTP が失敗したら candidates=[] を「返信 0」と書かない。
;;     :unanswered を立てて終わる（SCANNED=0 は pass ではない）。
;;   - probe@ / 自ドメイン発信は候補にしない。
;;   - スコアは上げない。delivered も 0 matches も需要ではない。
;;
;; usage:
;;   nbb scripts/murakumo-oem-rfq-tick.cljs
;;   nbb scripts/murakumo-oem-rfq-tick.cljs --json
;;
;; Resend key（どれか 1 つ、総当たりしない）:
;;   env RESEND_API_KEY
;;   file $HOME/.itonami/resend-api-key  (mode 600, launchd 用)
;;   keychain service=gftd.resend account=API_KEY

(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def os (js/require "node:os"))
(def https (js/require "node:https"))
(def cp (js/require "node:child_process"))
(def process (js/require "node:process"))

(def vendor-hosts #{"mouse-jp.co.jp" "sycom.co.jp"})
(def vendor-addrs #{"houjin@mouse-jp.co.jp" "pc-order@sycom.co.jp"})
(def self-hosts #{"mail.murakumo.cloud" "murakumo.cloud" "email.gftd.co.jp"
                  "gftd.co.jp" "gftd.ai" "itonami.cloud"})
(def skip-from #{"probe@mail.murakumo.cloud"})

(def home (.homedir os))
(def ledger-dir (or (.-OEM_RFQ_HOME js/process.env)
                    (.join path home ".itonami" "murakumo-oem-rfq")))
(def scan-path (.join path ledger-dir "scans.edn"))
(def json? (some #{"--json"} (js->clj (.-argv process))))

(defn- now-iso [] (.toISOString (js/Date.)))

(defn- addr-str [from]
  (cond
    (nil? from) ""
    (string? from) from
    (map? from) (or (:email from) (get from "email") (str from))
    (sequential? from) (addr-str (first from))
    :else (str from)))

(defn- email-of [addr]
  (let [s (str/trim (addr-str addr))]
    (str/lower-case (or (second (re-find #"<([^>]+)>" s)) s))))

(defn- host [addr]
  (let [e (email-of addr)]
    (second (str/split e #"@" 2))))

(defn- vendor-from? [from]
  (let [e (email-of from)
        h (host from)]
    (or (contains? vendor-addrs e)
        (contains? vendor-hosts h))))

(defn- skip-from? [from]
  (let [e (email-of from)
        h (host from)]
    (or (contains? skip-from e)
        (contains? self-hosts h))))

(defn- read-key []
  (or (let [e (.-RESEND_API_KEY js/process.env)]
        (when (and (string? e) (pos? (count e))) e))
      (let [p (.join path home ".itonami" "resend-api-key")]
        (when (.existsSync fs p)
          (str/trim (.readFileSync fs p "utf8"))))
      (try
        (let [out (.execFileSync cp "security"
                                 #js ["find-generic-password" "-s" "gftd.resend"
                                      "-a" "API_KEY" "-w"]
                                 #js {:encoding "utf8" :stdio #js ["ignore" "pipe" "ignore"]})
              k (str/trim (str out))]
          (when (and (pos? (count k)) (not (str/includes? k "could not")))
            k))
        (catch :default _ nil))))

(defn- get-json [url token]
  (js/Promise.
   (fn [resolve]
     (let [u (js/URL. url)
           req (.request https
                         #js {:hostname (.-hostname u)
                              :path (str (.-pathname u) (.-search u))
                              :method "GET"
                              :headers #js {"authorization" (str "Bearer " token)
                                            "accept" "application/json"}
                              :timeout 20000}
                         (fn [res]
                           (let [chunks #js []]
                             (.on res "data" (fn [c] (.push chunks c)))
                             (.on res "end"
                                  (fn []
                                    (let [body (.toString (.concat js/Buffer chunks) "utf8")]
                                      (resolve {:status (.-statusCode res)
                                                :body body})))))))]
       (.on req "error" (fn [e] (resolve {:status nil :error (str e)})))
       (.on req "timeout" (fn [] (.destroy req) (resolve {:status nil :error "timeout"})))
       (.end req)))))

(defn- parse-received [body]
  (try
    (let [j (js->clj (js/JSON.parse body) :keywordize-keys true)
          items (or (:data j) (when (vector? j) j) [])]
      {:ok true :items items})
    (catch :default e {:ok false :error (str e)})))

(defn- candidate [src item]
  (let [from (or (:from item) (get item "from"))
        to (or (:to item) (get item "to"))
        subj (or (:subject item) (get item "subject"))
        id (or (:id item) (get item "id"))]
    (when (and id (vendor-from? from) (not (skip-from? from)))
      {:id (str id)
       :source src
       :from (str from)
       :to (str to)
       :subject (str subj)})))

(defn- append-scan! [m]
  (.mkdirSync fs (.dirname path scan-path) #js {:recursive true})
  (.appendFileSync fs scan-path (str (pr-str m) "\n") "utf8"))

(defn- emit [out]
  (when-not json?
    (println (str "SCANNED\t" (:scanned out)))
    (println (str "MATCHES\t" (count (:candidates out))))
    (println (str "UNANSWERED\t" (boolean (:unanswered out))))
    (when (:reason out) (println (str "REASON\t" (:reason out))))
    (println (str "RESULT\t" (if (:unanswered out) "unanswered" "recorded"))))
  (println (js/JSON.stringify (clj->js out)))
  (.exit process 0))

(defn- done [m]
  (append-scan! (assoc m :as-of (now-iso) :kind :oem-rfq-scan :score-raised? false))
  (emit m))

(let [token (read-key)]
  (if-not token
    (done {:unanswered true
           :reason "resend-key-missing"
           :scanned 0
           :candidates []
           :note "Mail was not scanned. SCANNED=0 is not zero replies."})
    (-> (get-json "https://api.resend.com/emails/receiving?limit=50" token)
        (.then
         (fn [{:keys [status body error]}]
           (cond
             error
             (done {:unanswered true :reason (str "resend-fetch:" error)
                    :scanned 0 :candidates []})

             (not= status 200)
             (done {:unanswered true
                    :reason (str "resend-http-" status)
                    :scanned 0
                    :candidates []
                    :body-head (subs (or body "") 0 (min 200 (count (or body ""))))})

             :else
             (let [{:keys [ok items error]} (parse-received body)]
               (if-not ok
                 (done {:unanswered true :reason (str "resend-parse:" error)
                        :scanned 0 :candidates []})
                 (let [cands (vec (keep #(candidate :resend %) items))]
                   (done {:unanswered false
                          :scanned (count items)
                          :candidates cands
                          :note "Inbound vendor matches are discovery evidence only. Do not PO. Do not raise cash score."}))))))))))

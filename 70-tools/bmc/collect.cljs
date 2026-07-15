#!/usr/bin/env nbb
;; collect.cljs — Cloudflare / Stripe / 本番 health から実測 metrics を収集し
;; 90-docs/business/metrics/<product>.edn に書く (ADR-2607021800)。
;;
;; creds (秘密は一切ファイルに書かない):
;;   Cloudflare: env CF_API_TOKEN → Keychain service "gftd.cf"
;;   Stripe:     env STRIPE_SECRET_KEY → 1Password item (env STRIPE_OP_ITEM,
;;               既定 "Stripe Live API Keys" の item id) の STRIPE_SECRET_KEY
;; 出力は集計数値のみ。捏造ゼロ: 取れなかった source は :sources に入れない。
(require '[scripts.nbb-compat :refer [slurp spit file-seq format]]
         '[babashka.curl :as curl]
         '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.edn]
         '[clojure.java.shell :refer [sh]]
         '[clojure.pprint :as pprint]
         '[clojure.string :as str])

(def root (or (scripts.nbb-compat/getenv "GFTD_ROOT") "."))
(def out-dir (str root "/90-docs/business/metrics"))
(def account "4da88288dc30d9ee257f319d3c33ecf0") ; ai-gftd-cloud

(def products
  {:ai-gftd-apex    {:zone "63132931facb26812993527da9f85186" :zone-name "gftd.ai"
                     :workers #{"ai-gftd-chat-shell" "magatama-sh1n5h1x"}
                     :health "https://gftd.ai/"}
   :app-aozora      {:zone "cf83e7590ebb6ff47a184866e9eddbe6" :zone-name "aozora.app"
                     :workers #{"app-aozora-appview" "app-aozora-pds" "app-aozora-spa" "aozora-app"}
                     :health "https://aozora.app/"}
   :app-aozora-yoro {:workers #{"aozora-yoro-appview" "aozora-yoro-pds" "aozora-yoro-spa"}}
   :cloud-murakumo  {:zone "9795417e38ef69e173fe37371f937f3e" :zone-name "murakumo.cloud"
                     :workers #{"ai-gftd-murakumo-2603241700"}
                     :health "https://murakumo.cloud/"}
   :cloud-manimani  {:zone "f28dd3b902729a343d2b9d09a2c548e8" :zone-name "manimani.cloud"
                     :health "https://manimani.cloud/"}
   :net-kotobase    {:zone "5ad3da692f2367905c257cfcb0c1057c" :zone-name "kotobase.net"
                     :workers #{"net-kotobase" "kotobase-cf-wasm-staging"}
                     :health "https://kotobase.net/health" :stripe true}
   :cloud-itonami   {:zone "4ea304cb1d465cb9ba7ea89d8a0d6750" :zone-name "itonami.cloud"
                     :health "https://itonami.cloud/health.json"}
   :etzhayyim       {:zone "54dece4ac787807d4c3410243916a1e6" :zone-name "etzhayyim.com"
                     :workers #{"etzhayyim-did-web" "etzhayyim-xrpc-proxy"}
                     :health "https://etzhayyim.com/"}
   ;; 2026-07-09 追加: どちらも zone 実在 (API 確認済) だが products 未登録で
   ;; collect が回らず、metrics が 07-02 の手動 snapshot のまま stale だった。
   ;; network-isekai は gate-emitters の fork-stats (live 稼働確認済) もこれで
   ;; 初めて毎 tick fetch される。
   :network-isekai  {:zone "3bb094bac1fffdb9c61a60093eb267c5" :zone-name "isekai.network"
                     :health "https://isekai.network/"}
   :club-shinshi    {:zone "1527fa6d84bd5c216029c20b4f4810e1" :zone-name "shinshi.club"
                     :health "https://shinshi.club/"}
   ;; 2026-07-10 追加 (ADR-2607105200): 横断決済 facilitator。ページ無し API-only
   ;; Worker のため zone 無し (app-aozora-yoro と同型) — workers invocation +
   ;; health のみ。seller/agent-demand 実測は gate-emitters (:catalog) 経由。
   :nexus-x402      {:workers #{"nexus-x402"}
                      :health "https://x402.nexus/health"}})

(defn keychain [service]
  (let [{:keys [exit out]} (sh "security" "find-generic-password" "-s" service "-w")]
    (when (zero? exit) (str/trim out))))

(defn cf-token [] (or (scripts.nbb-compat/getenv "CF_API_TOKEN") (keychain "gftd.cf")))
(defn stripe-key []
  (or (scripts.nbb-compat/getenv "STRIPE_SECRET_KEY")
      ;; Keychain ミラー (service gftd.stripe) を op より先に見る。op は並行
      ;; セッションと session を取り合うと "authorization timeout" で分単位に
      ;; 落ちる (2026-07-09 実測) — b2/cf と同じ Keychain ミラー方式に揃えた。
      ;; sk_ 検証必須: 2026-07-09 に op の field 解決が publishable key
      ;; (pk_live_) を返しミラーが汚染された実事故 — secret key 以外は捨てる。
      ;; ミラー更新: op が生きている時に sk_ を確認してから
      ;;   security add-generic-password -U -s gftd.stripe -a stripe -w "$KEY"
      (when-let [k (keychain "gftd.stripe")]
        (when (str/starts-with? k "sk_") k))
      (let [item (or (scripts.nbb-compat/getenv "STRIPE_OP_ITEM") "b2g2dkwnlfyl3sik6naptrmjyq")]
        (some (fn [attempt]
                (let [{:keys [exit out]} (sh "op" "item" "get" item "--fields" "STRIPE_SECRET_KEY" "--reveal")]
                  (if (zero? exit)
                    (str/trim out)
                    (do (when (< attempt 3) (scripts.nbb-compat/sleep! 2000))
                        nil))))
              [1 2 3]))))

(defn iso-now [] (.toISOString (js/Date.)))
(defn date-days-ago
  "The bb original used java.time.LocalDate/now (system-local calendar date).
   UTC Date methods here would silently shift the date for ~9h after local
   midnight in JST, feeding a wrong day into the CF date_gt filters and the
   :as-of metrics stamp — use local-time getters/setters instead."
  [n]
  (let [d (js/Date.)]
    (.setDate d (- (.getDate d) n))
    (str (.getFullYear d) "-"
         (.padStart (str (inc (.getMonth d))) 2 "0") "-"
         (.padStart (str (.getDate d)) 2 "0"))))

(defn cf-graphql [token query]
  (-> (curl/post "https://api.cloudflare.com/client/v4/graphql"
                 {:headers {"Authorization" (str "Bearer " token)
                            "Content-Type" "application/json"}
                  :body (json/generate-string {:query query})
                  :throw false})
      :body (json/parse-string true)))

(defn zone-traffic
  "→ {zoneTag {:requests-7d n :pageviews-7d n :uniques-7d-sum n}}"
  [token]
  (let [tags (keep :zone (vals products))
        q (str "{ viewer { zones(filter: {zoneTag_in: [" (str/join "," (map #(str "\"" % "\"") tags)) "]})"
               " { zoneTag httpRequests1dGroups(limit: 10, filter: {date_gt: \"" (date-days-ago 7) "\"})"
               " { sum { requests pageViews } uniq { uniques } } } } }")
        r (cf-graphql token q)]
    (into {}
          (for [z (get-in r [:data :viewer :zones])]
            [(:zoneTag z)
             (let [gs (:httpRequests1dGroups z)]
               {:requests-7d (reduce + (map #(get-in % [:sum :requests] 0) gs))
                :pageviews-7d (reduce + (map #(get-in % [:sum :pageViews] 0) gs))
                :uniques-7d-sum (reduce + (map #(get-in % [:uniq :uniques] 0) gs))})]))))

(defn worker-invocations
  "→ {scriptName requests-7d}"
  [token]
  (let [q (str "{ viewer { accounts(filter: {accountTag: \"" account "\"})"
               " { workersInvocationsAdaptive(limit: 500, filter: {date_gt: \"" (date-days-ago 7) "\"})"
               " { sum { requests errors } dimensions { scriptName } } } } }")
        r (cf-graphql token q)]
    (reduce (fn [m g] (update m (get-in g [:dimensions :scriptName]) (fnil + 0)
                              (get-in g [:sum :requests] 0)))
            {} (get-in r [:data :viewer :accounts 0 :workersInvocationsAdaptive]))))

(defn zone-top-paths
  "実際に user がどのpageにアクセスしているか (直近24h)。edgeResponseStatus で
   「実際に配信された page (2xx/3xx)」とスキャナ probe (4xx) を分離する —
   kotobase.net の初回実測で上位 path の大半が /.env.backup 等の bot probe
   だったため、status 混在のままだと channels 観測が導線として読めない。
   httpRequestsAdaptiveGroups は free plan だと概ね1日分しか引けないので
   window は 24h 固定。→ {:ok [{:path :requests}…8] :status-mix {:ok n :client-error n :server-error n}}
   取れなければ nil (捏造ゼロ: 出力に入れない)。"
  [token zone-tag]
  (try
    (let [now (iso-now)
          q (str "{ viewer { zones(filter: {zoneTag: \"" zone-tag "\"})"
                 " { httpRequestsAdaptiveGroups(limit: 500, filter: {datetime_geq: \""
                 (let [d (js/Date.)] (.setUTCSeconds d (- (.getUTCSeconds d) 86400)) (.toISOString d)) "\", datetime_leq: \"" now "\"})"
                 " { count dimensions { clientRequestPath edgeResponseStatus } } } } }")
          r (cf-graphql token q)
          groups (get-in r [:data :viewer :zones 0 :httpRequestsAdaptiveGroups])]
      (when (seq groups)
        (let [status-of (fn [g] (let [s (get-in g [:dimensions :edgeResponseStatus] 0)]
                                  (cond (< s 400) :ok
                                        (< s 500) :client-error
                                        :else :server-error)))
              mix (reduce (fn [m g] (update m (status-of g) (fnil + 0) (:count g 0)))
                          {} groups)
              ok-paths (->> groups
                            (filter #(= :ok (status-of %)))
                            (reduce (fn [m g] (update m (get-in g [:dimensions :clientRequestPath])
                                                      (fnil + 0) (:count g 0)))
                                    {})
                            (sort-by val >)
                            (take 8)
                            (mapv (fn [[path n]] {:path path :requests n})))]
          {:ok ok-paths :status-mix mix})))
    (catch :default _ nil)))

(defn top-paths-summary
  "2xx/3xx (実配信 page) の上位のみを導線観測として要約し、probe (4xx) 率を
   併記する。governor の 300 chars 上限に収まるよう上位5件・path は40字で切る。"
  [{:keys [ok status-mix]}]
  (when (seq ok)
    (let [total (reduce + (vals status-mix))
          probe (get status-mix :client-error 0)
          err (get status-mix :server-error 0)]
      (str "上位 page (24h, 2xx/3xx): "
           (str/join " · "
                     (map (fn [{:keys [path requests]}]
                            (str (if (> (count path) 40) (str (subs path 0 40) "…") path)
                                 " " requests))
                          (take 5 ok)))
           (when (pos? total)
             (str " | 4xx(probe) " (Math/round (* 100.0 (/ probe total))) "%"
                  (when (pos? err)
                    (str " · 5xx " (Math/round (* 100.0 (/ err total))) "%"))))))))

;; kotobase 課金 product の price_id (ADR-2607022200)。gate kotobase-graph-arpu は
;; 「kotobase の初 paid tenant」を測るので、アカウント全体の active-subscriptions を
;; 数えると 2017 年レガシーの無関係サブスク (price=group_monthly, ¥0) を誤カウントし
;; gate を false-validate する。kotobase price に紐づく active sub だけを数える。
(def kotobase-price-ids
  ;; live USD prices (2026-07-02 作成・livemode:true 確認済、ADR-2607023000)。
  ;; 旧 price_1TVVI7… は live に存在せず (test/誤り) 破棄。
  #{"price_1TohewBcblPoapUJX098Knc3"    ; Standard $7/mo    (prod_UoKJjsdqxDvmLz)
    "price_1ToheyBcblPoapUJwJS2FCLT"    ; Pro $33/mo        (prod_UoKJ7ooZynkf8E)
    "price_1TohezBcblPoapUJeLF7J8Qn"})  ; Regulated $350/mo (prod_UoKJ83riUJKfTn)

(defn- sub-price-ids [sub]
  (set (keep #(get-in % [:price :id]) (get-in sub [:items :data]))))

(defn stripe-summary
  "「:active-subscriptions は PAID のみ」(2026-07-09 修正)。status=active でも
  latest_invoice が未払い (open/amount_paid 0) の subscription は収益ではない —
  実測で 4 件の社内テスト sub (hello@gftd.co.jp / jun@gftd.group、2026-07-02
  作成、全 invoice 未払い) が gate kotobase-graph-arpu を誤 validate しかけた。
  paid 判定は latest_invoice の paid フラグを個別 GET で確認する
  (subscription 一覧の latest_invoice は id 文字列のみ)。"
  [sk]
  (let [get* (fn [ep] (-> (curl/get (str "https://api.stripe.com/v1/" ep)
                                    {:basic-auth [sk ""] :throw false})
                          :body (json/parse-string true)))
        subs (get* "subscriptions?status=active&limit=100")
        charges (get* "charges?limit=100")]
    (when-not (:error subs)
      (let [all-active (:data subs)
            kotobase-active (filter #(seq (clojure.set/intersection
                                            (sub-price-ids %) kotobase-price-ids))
                                    all-active)
            invoice-paid? (fn [sub]
                            (let [inv-id (:latest_invoice sub)]
                              (and inv-id
                                   (true? (:paid (get* (str "invoices/" inv-id)))))))
            kotobase-paid (filter invoice-paid? kotobase-active)]
        {:active-subscriptions (count kotobase-paid)     ; ← kotobase price かつ PAID のみ (gate)
         :active-subscriptions-unpaid (- (count kotobase-active) (count kotobase-paid)) ; 参考: 未払い (テスト/滞納)
         :active-subscriptions-account-wide (count all-active) ; 参考: 全体 (レガシー含む)
         :charges-total (count (:data charges))
         :last-charge-epoch (some-> (first (:data charges)) :created)}))))

(defn http-status [url]
  (when url
    (try (:status (curl/get url {:throw false :raw-args ["--max-time" "8"]}))
         (catch :default _ nil))))

;; ---- gate-metric emitter fetch (ADR-2607022200/2607022100) -------------------
;; 各 product repo が deploy した emitter endpoint から gate 用 metric を取得し
;; metrics/<product>.edn へ merge する。emitter が未 deploy / 到達不可なら no-op
;; (defensive) — その product の gate は引き続き needs-fallback を surface する。
;; CLI/file 型 emitter (murakumo cost / apex subscription / etzhayyim rad /
;; yukkuri channelStats) は各 repo の CI/実行で自 metrics に出す設計なので、この
;; generic HTTP collector の対象外 (URL を持たない product は fetch されない)。
(def gate-emitters
  {:network-isekai {:url "https://isekai.network/feed/fork-stats.edn" :fmt :edn :key :fork}
   :cloud-manimani {:url "https://manimani.cloud/metrics"            :fmt :json :merge true}
   :cloud-itonami  {:url "https://itonami.cloud/api/metrics"          :fmt :json :merge true}
   ;; 外部獲得 funnel テレメトリ (net-kotobase #150): {"funnel":{visitors/signups/checkouts}}
   ;; を top-level merge → funnel spec (ADR-2607022600) の [:funnel …] が実データ化。
   :net-kotobase   {:url "https://kotobase.net/api/funnel"            :fmt :json :merge true}
   ;; club-shinshi companion engagement (2026-07-10): {"funnel":{visitors/
   ;; chatters/scenes/paying}} from shinshi D1 (pv_daily + chat_event_daily).
   ;; chatters = 1:1 companion chat messages = the validation signal the live
   ;; chat (murakumo fleet) now produces.
   :club-shinshi   {:url "https://shinshi.club/api/funnel"            :fmt :json :merge true}
   ;; cloud-murakumo cost 計器 (ADR-2607022200 の「CLI/file 型」を HTTP emitter 化、
   ;; 2026-07-15): local-murakumo Worker が /v1/messages の実 run(llama.cpp timings)
   ;; を KV ring に記録し、GET /infer/cost が ¥/Mtok 集計を返す。:key :cost →
   ;; gftd.gate の :hyp/murakumo-tok-price compare ([:cost :fleet-yen-per-mtok] <=
   ;; [:cost :spot-yen-per-mtok]) が機械測定可能になる。
   :cloud-murakumo {:url "https://api.murakumo.cloud/infer/cost"      :fmt :json :key :cost}
   ;; nexus-x402 (ADR-2607105200): public /stats is a superset of /catalog (no
   ;; admin auth needed) — {:count N :items [...] :settlements {:count :usd-total
   ;; :agent-hint {:agent :human :unknown}}}. :catalog :count feeds the
   ;; adoption/external-seller gate-specs; :catalog :settlements :agent-hint :agent
   ;; feeds :hyp/nexus-x402-agent-demand (added when nexus-x402 shipped /stats).
   :nexus-x402     {:url "https://x402.nexus/stats"                   :fmt :json :key :catalog}})

(defn fetch-emitter
  "→ parsed emitter map, or nil if unreachable/unparseable (no-op)."
  [{:keys [url fmt]}]
  (try
    (let [r (curl/get url {:throw false :raw-args ["--max-time" "8"]})]
      (when (= 200 (:status r))
        (case fmt
          :edn  (clojure.edn/read-string (:body r))
          :json (json/parse-string (:body r) true))))
    (catch :default _ nil)))

(defn merge-emitter
  "Merge a product's live gate-metric emitter output into its metrics map m.
   :key → nest under that key; :merge → shallow-merge top-level."
  [m product]
  (if-let [{:keys [key merge] :as cfg} (get gate-emitters product)]
    (if-let [em (fetch-emitter cfg)]
      (cond-> (if merge (clojure.core/merge m em) (assoc m key em))
        true (update :sources (fnil conj []) :emitter))
      m)
    m))

(defn traffic-quality
  "24h の status-mix から probe(4xx)/5xx 率を % で。zone の uniques/requests を
   「見込み顧客」と読ませないための補正係数 (実測比、推定ではない)。"
  [{:keys [status-mix]}]
  (let [total (reduce + (vals (or status-mix {})))]
    (when (pos? total)
      {:window "24h"
       :probe-4xx-pct (Math/round (* 100.0 (/ (get status-mix :client-error 0) total)))
       :error-5xx-pct (Math/round (* 100.0 (/ (get status-mix :server-error 0) total)))})))

(defn signal [p m]
  (let [z (:zone m) w (:workers-invocations-7d m) tq (:traffic-quality m)]
    (str/join "、"
              (remove nil?
                      [(when z (str (:zone-name m) " 実測 " (:requests-7d z) " req/7d・"
                                    (:uniques-7d-sum z) " uniques(日次和)"
                                    (when tq (str "・うち4xx probe " (:probe-4xx-pct tq) "%(24h)"))))
                       (when (seq w) (str "workers " (reduce + (vals w)) " inv/7d"))
                       (when-let [s (:stripe m)]
                         (str "Stripe active subs " (:active-subscriptions s)))
                       (when (nil? z) (case p :app-aozora-yoro "zone 無し (aozora.app 配下)" nil))]))))

(defn -main []
  (let [tok (or (cf-token) (throw (ex-info "no CF_API_TOKEN / Keychain gftd.cf" {})))
        sk (stripe-key)
        zt (zone-traffic tok)
        wi (worker-invocations tok)
        stripe (when sk (stripe-summary sk))
        as-of (date-days-ago 0)]
    (fs/create-dirs out-dir)
    (doseq [[p {:keys [zone zone-name workers health] :as cfg}] products]
      (let [m (cond-> {:as-of as-of
                       :sources (vec (remove nil? [:cloudflare (when (and (:stripe cfg) stripe) :stripe)
                                                   (when health :health)]))}
                zone (assoc :zone (assoc (get zt zone) :zone zone-name) :zone-name zone-name)
                zone (as-> m' (let [tp (zone-top-paths tok zone)]
                                (cond-> m'
                                  tp (assoc :paths (assoc tp :window "24h")
                                            :top-paths (top-paths-summary tp))
                                  (traffic-quality tp) (assoc :traffic-quality (traffic-quality tp)))))
                (seq workers) (assoc :workers-invocations-7d
                                     (into {} (filter (fn [[k _]] (contains? workers k)) wi)))
                (and (:stripe cfg) stripe) (assoc :stripe stripe)
                health (assoc :health-status (http-status health)))
            m (merge-emitter m p)
            m (assoc m :signal (signal p m))]
        (spit (str out-dir "/" (name p) ".edn") (with-out-str (pprint/pprint m)))
        (println "wrote" (name p) "-" (:signal m))))))

(-main)

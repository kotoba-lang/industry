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
         '[clojure.string :as str]
         '[gftd.traffic :as traffic])

(def root (or (scripts.nbb-compat/getenv "GFTD_ROOT") "."))
(def out-dir (str root "/90-docs/business/metrics"))
(def account "4da88288dc30d9ee257f319d3c33ecf0") ; ai-gftd-cloud

(def products
  ;; ai-gftd-apex は :stripe true (2026-07-15): gate :hyp/apex-privacy-premium の
  ;; 分子 (apex-active-subscriptions) を stripe-summary から得る。:conversion は
  ;; -main 内の per-product 導出 (ai-gftd-apex PR #2 semantics) で付く。
  {:ai-gftd-apex    {:zone "63132931facb26812993527da9f85186" :zone-name "gftd.ai"
                     :stripe true
                     :workers #{"ai-gftd-chat-shell" "magatama-sh1n5h1x"}
                     :health "https://gftd.ai/"}
   :app-aozora      {:zone "cf83e7590ebb6ff47a184866e9eddbe6" :zone-name "aozora.app"
                     :workers #{"app-aozora-appview" "app-aozora-pds" "app-aozora-spa" "aozora-app"}
                     :health "https://aozora.app/"}
   :app-aozora-yoro {:workers #{"aozora-yoro-appview" "aozora-yoro-pds" "aozora-yoro-spa"}}
   ;; :stripe true (2026-07-15): revenue 計器 — #store/* checkout の charge に
   ;; metadata.product=cloud-murakumo が付く (cloud-murakumo fcd317d)。
   ;; stripe-summary の :murakumo-paid-charges を funnel revenue 段が読む。
   :cloud-murakumo  {:zone "9795417e38ef69e173fe37371f937f3e" :zone-name "murakumo.cloud"
                     :workers #{"ai-gftd-murakumo-2603241700"}
                     :health "https://murakumo.cloud/" :stripe true}
   :cloud-manimani  {:zone "f28dd3b902729a343d2b9d09a2c548e8" :zone-name "manimani.cloud"
                     :health "https://manimani.cloud/"}
   :net-kotobase    {:zone "5ad3da692f2367905c257cfcb0c1057c" :zone-name "kotobase.net"
                     :workers #{"net-kotobase" "kotobase-cf-wasm-staging"}
                     :health "https://kotobase.net/health" :stripe true}
   :cloud-itonami   {:zone "4ea304cb1d465cb9ba7ea89d8a0d6750" :zone-name "itonami.cloud"
                     ;; Live freePath snapshot (KV tenants/agentRuns); static
                     ;; /health.json remains for version/route map only.
                     :health "https://itonami.cloud/api/health"}
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
                      :health "https://x402.nexus/health"}
   ;; 2026-08-07 追加: 一次計測（dwell / route transition, ADR-2608060900）が
   ;; 入ったのに products に居らず、metrics ファイル自体が存在しなかった。
   ;; zone は付けない — babiniku.net の zone id をこの作業機から検証できず
   ;; （CF_API_TOKEN も Keychain "gftd.cf" も無い）、確かめていない id を
   ;; 書くくらいなら無い方がよい。zone を足すときは API で実在を確認してから。
   ;; 実データは :emitter（/api/telemetry-report）が運ぶ。
   :net-babiniku    {:health "https://babiniku.net/"}})

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

(defn cf-classify
  "Parsed Cloudflare GraphQL body -> the body, or {::failed reason}.

  Pure, so the distinction that matters can be tested without a token."
  [body]
  (cond
    (seq (:errors body)) {::failed (str/join "; " (keep :message (:errors body)))}
    (nil? (:data body))  {::failed "response carried neither data nor errors"}
    :else body))

(defn cf-graphql
  "Cloudflare's GraphQL, with the failure kept instead of dropped.

  `:throw false` means an expired or revoked token comes back as a 200-shaped
  body carrying `errors` and no `data`. Every caller then read
  `(get-in r [:data …])`, got nil, and folded it into an empty collection --
  which is the same value a genuinely idle worker produces. Measured
  2026-08-31: all twelve product metrics regenerated with every Cloudflare
  number gone (net-kotobase 261870 req/7d -> absent, cloud-murakumo 859821 ->
  absent, six workers' invocation counts -> absent) while each file still
  declared `:sources [:cloudflare …]`. The collection failed and the failure
  was written as a measurement."
  [token query]
  (cf-classify
   (-> (curl/post "https://api.cloudflare.com/client/v4/graphql"
                  {:headers {"Authorization" (str "Bearer " token)
                             "Content-Type" "application/json"}
                   :body (json/generate-string {:query query})
                   :throw false})
       :body (json/parse-string true))))

(defn cf-failed
  "The reason a Cloudflare call could not be measured, or nil."
  [x]
  (when (map? x) (::failed x)))

(defn zone-traffic
  "→ {zoneTag {:requests-7d n :pageviews-7d n :uniques-7d-sum n}}"
  [token]
  (let [tags (keep :zone (vals products))
        q (str "{ viewer { zones(filter: {zoneTag_in: [" (str/join "," (map #(str "\"" % "\"") tags)) "]})"
               " { zoneTag httpRequests1dGroups(limit: 10, filter: {date_gt: \"" (date-days-ago 7) "\"})"
               " { sum { requests pageViews } uniq { uniques } } } } }")
        r (cf-graphql token q)]
    (if-let [why (cf-failed r)]
      {::failed why}
      (into {}
            (for [z (get-in r [:data :viewer :zones])]
              [(:zoneTag z)
               (let [gs (:httpRequests1dGroups z)]
                 {:requests-7d (reduce + (map #(get-in % [:sum :requests] 0) gs))
                  :pageviews-7d (reduce + (map #(get-in % [:sum :pageViews] 0) gs))
                  :uniques-7d-sum (reduce + (map #(get-in % [:uniq :uniques] 0) gs))})])))))

(defn worker-invocations
  "→ {scriptName requests-7d}"
  [token]
  (let [q (str "{ viewer { accounts(filter: {accountTag: \"" account "\"})"
               " { workersInvocationsAdaptive(limit: 500, filter: {date_gt: \"" (date-days-ago 7) "\"})"
               " { sum { requests errors } dimensions { scriptName } } } } }")
        r (cf-graphql token q)]
    (if-let [why (cf-failed r)]
      {::failed why}
      (reduce (fn [m g] (update m (get-in g [:dimensions :scriptName]) (fnil + 0)
                                (get-in g [:sum :requests] 0)))
              {} (get-in r [:data :viewer :accounts 0 :workersInvocationsAdaptive])))))

(defn scanner-403-count
  "groups (httpRequestsAdaptiveGroups) のうち、secret-probe path (.env/.git 等
   gftd.traffic/probe-path-re 一致) への 403 レスポンスのリクエスト数。
   pure — self-test で token 無しに検証できる (#47 triage 2026-09-03: shinshi.club
   の 4xx の 62% がこれで、broken UI ではなく正常ブロック)。
   403 以外 (404/499) は probe path でも外さない — 実害候補として残す。"
  [groups]
  (reduce + 0 (keep (fn [{:keys [count dimensions]}]
                      (when (and (= 403 (get dimensions :edgeResponseStatus 0))
                                 (re-find traffic/probe-path-re (get dimensions :clientRequestPath "")))
                        count))
                    groups)))

(defn zone-top-paths
  "実際に user がどのpageにアクセスしているか (直近24h)。edgeResponseStatus で
   「実際に配信された page (2xx/3xx)」とスキャナ probe (4xx/5xx) を分離する —
   kotobase.net の初回実測で上位 path の大半が /.env.backup 等の bot probe
   だったため、status 混在のままだと channels 観測が導線として読めない。
   ただし status-code だけでは不十分な product がある: network-isekai は
   Cloudflare Pages が unmatched path にも 2xx/3xx を返す (custom 404 未設定)
   ため、status filter を通過した \"ok\" bucket 自体がスキャナ probe path で
   汚染される (evidence: canvas-ledger.edn 参照、gftd.traffic/classify-path
   docstring)。product が gftd.traffic/channel-allowlist に登録されていれば、
   status filter 通過後の path 集合をさらに classify-path で
   :channel/:probe/:unclassified に分け、:channel のみを top-paths として返し、
   他2バケットの比率を :channel-mix に載せる (未登録 product は従来どおり
   status のみで top8)。
   httpRequestsAdaptiveGroups は free plan だと概ね1日分しか引けないので
   window は 24h 固定。→ {:ok [{:path :requests}…8] :status-mix {:ok n :client-error n :server-error n}
   :channel-mix (opt) {:channel n :probe n :unclassified n :ok-total n}}
   取れなければ nil (捏造ゼロ: 出力に入れない)。"
  [token zone-tag product]
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
              ;; #47 (2026-09-03): 4xx の内訳で scanner secret-probe への 403
              ;; (正常ブロック) を分離した「real 4xx」系列。probe-4xx-pct は
              ;; 従来定義のまま据え置き (正本の改変はしない)、real-4xx を追加で
              ;; 載せる。499 (client disconnect) は probe path であっても外さない。
              scanner-403 (scanner-403-count groups)
              path-counts (->> groups
                               (filter #(= :ok (status-of %)))
                               (reduce (fn [m g] (update m (get-in g [:dimensions :clientRequestPath])
                                                         (fnil + 0) (:count g 0)))
                                       {}))]
          (if (contains? traffic/channel-allowlist product)
            (let [by-class (group-by (fn [entry] (traffic/classify-path product (key entry))) path-counts)
                  sum-class (fn [k] (reduce + (map val (get by-class k))))
                  channel-paths (->> (get by-class :channel)
                                     (sort-by val >)
                                     (take 8)
                                     (mapv (fn [entry] {:path (key entry) :requests (val entry)})))]
              {:ok channel-paths
               :status-mix mix
               :real-4xx (- (get mix :client-error 0) scanner-403)
               :channel-mix {:channel (sum-class :channel)
                             :probe (sum-class :probe)
                             :unclassified (sum-class :unclassified)
                             :ok-total (reduce + (vals path-counts))}})
            (let [ok-paths (->> path-counts
                                (sort-by val >)
                                (take 8)
                                (mapv (fn [[path n]] {:path path :requests n})))]
              {:ok ok-paths
               :status-mix mix
               :real-4xx (- (get mix :client-error 0) scanner-403)})))))
    (catch :default _ nil)))

(defn top-paths-summary
  "2xx/3xx (実配信 page) の上位のみを導線観測として要約し、probe (4xx/5xx) 率を
   併記する。channel-allowlist 対象 product (:channel-mix あり) は、その
   2xx/3xx bucket 自体に混入した bot/vuln-scanner probe path
   (CF Pages の unmatched-path 200-fallback 由来。gftd.traffic/classify-path
   参照) の probe-path-pct と、allowlist 未登録の real route かもしれない
   unclassified-pct を追記する (捏造ゼロ: 未分類は落とさず可視化)。
   governor の 300 chars 上限に収まるよう上位5件・path は40字で切る。"
  [{:keys [ok status-mix real-4xx channel-mix]}]
  (when (seq ok)
    (let [total (reduce + (vals status-mix))
          probe (get status-mix :client-error 0)
          err (get status-mix :server-error 0)
          ok-total (:ok-total channel-mix 0)
          probe-path-pct (when (and channel-mix (pos? ok-total))
                           (Math/round (* 100.0 (/ (:probe channel-mix 0) ok-total))))
          unclassified-pct (when (and channel-mix (pos? ok-total))
                             (Math/round (* 100.0 (/ (:unclassified channel-mix 0) ok-total))))]
      (str "上位 page (24h, 2xx/3xx" (when channel-mix "・channel-allowlist済") "): "
           (str/join " · "
                     (map (fn [{:keys [path requests]}]
                            (str (if (> (count path) 40) (str (subs path 0 40) "…") path)
                                 " " requests))
                          (take 5 ok)))
           (when (pos? total)
             (str " | 4xx(probe) " (Math/round (* 100.0 (/ probe total))) "%"
                  (when (some? real-4xx)
                    (str " · 4xx(real) " (Math/round (* 100.0 (/ real-4xx total))) "%"))
                  (when (pos? err)
                    (str " · 5xx " (Math/round (* 100.0 (/ err total))) "%"))))
           (when (and probe-path-pct (pos? probe-path-pct))
             (str " · probe(200-fallback) " probe-path-pct "%"))
           (when (and unclassified-pct (pos? unclassified-pct))
             (str " · unclassified " unclassified-pct "%"))))))

;; kotobase 課金 product の price_id (ADR-2607022200)。gate kotobase-graph-arpu は
;; 「kotobase の初 paid tenant」を測るので、アカウント全体の active-subscriptions を
;; 数えると 2017 年レガシーの無関係サブスク (price=group_monthly, ¥0) を誤カウントし
;; gate を false-validate する。kotobase price に紐づく active sub だけを数える。
(def apex-price-ids
  ;; ai-gftd-apex「Gftd AI Pro」(prod_SdVVTqTHT1Z206, $20/mo)。非機密識別子 —
  ;; ai-gftd-apex PR #2 (subscription.cljc) の定数と同一。gate
  ;; :hyp/apex-privacy-premium の分子 (paid Plus) を account-level Stripe から
  ;; 実カウントする (kotobase-price-ids と同じ手口)。
  #{"price_1RiEU5BcblPoapUJY3PfDspR"})

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
            kotobase-paid (filter invoice-paid? kotobase-active)
            ;; cloud-murakumo: 単発 credits 購入 (subscription でない)。checkout が
            ;; charge へ伝播させる metadata.product で機械判定 (2026-07-15,
            ;; cloud-murakumo fcd317d)。paid かつ非 refund のみ数える。
            apex-active (filter #(seq (clojure.set/intersection
                                        (sub-price-ids %) apex-price-ids))
                                all-active)
            apex-paid (filter invoice-paid? apex-active)
            murakumo-paid (filter #(and (true? (:paid %))
                                        (not (:refunded %))
                                        (= "cloud-murakumo" (get-in % [:metadata :product])))
                                  (:data charges))]
        {:active-subscriptions (count kotobase-paid)     ; ← kotobase price かつ PAID のみ (gate)
         :active-subscriptions-unpaid (- (count kotobase-active) (count kotobase-paid)) ; 参考: 未払い (テスト/滞納)
         :active-subscriptions-account-wide (count all-active) ; 参考: 全体 (レガシー含む)
         :apex-active-subscriptions (count apex-paid)   ; ← ai-gftd-apex Free→Plus 分子 (PAID のみ)
         :murakumo-paid-charges (count murakumo-paid)   ; ← cloud-murakumo funnel revenue 段
         :murakumo-paid-amount-minor (reduce + 0 (map :amount murakumo-paid)) ; 参考: 額 (通貨 minor 単位混在に注意)
         :charges-total (count (:data charges))
         :last-charge-epoch (some-> (first (:data charges)) :created)}))))

(def collect-ua
  "gftd-bmc-collect/1.0 (+https://itonami.cloud/llms.txt; maturity loop)")

(defn http-status [url]
  (when url
    (try (:status (curl/get url {:throw false
                                 :raw-args ["--max-time" "8"
                                            "-A" collect-ua]}))
         (catch :default _ nil))))

(defn http-get-json
  "GET url → parsed JSON map, or nil. Sends a non-empty User-Agent:
  Cloudflare may return 403 error 1010 for signatures like Python-urllib/*."
  [url]
  (when url
    (try
      (let [r (curl/get url {:throw false
                             :raw-args ["--max-time" "8" "-A" collect-ua]})]
        (when (= 200 (:status r))
          (json/parse-string (:body r) true)))
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
   ;; 2026-08-07: 1 product に複数 emitter を許す形にした（値が map なら 1 本、
   ;; vector なら順に merge）。shinshi は funnel(公開) と audience(secret-gated)
   ;; の 2 本を持つ。
   :club-shinshi   [{:url "https://shinshi.club/api/funnel"            :fmt :json :merge true}
                    ;; 滞在時間バケット + 遷移エッジ（ADR-2608060900、0016）。
                    ;; business data なので x-internal-trust ゲート付き。
                    ;; `collected-since` を必ず一緒に綴じ込むこと — 行が無いのは
                    ;; 「誰も滞在していない」ではなく NOT MEASURED。
                    {:url "https://shinshi.club/_metrics/audience?window_days=7"
                     :fmt :json :key :audience :timeout 15
                     :secret-service "club-shinshi BMC_COLLECTOR_READONLY_SECRET"
                     :auth-header :internal-trust}]
   ;; babiniku の一次計測（ADR-2608060900、0020）。shinshi と schema を揃えて
   ;; あるので、2 プロダクトの回遊を同じ形で比較できる。
   :net-babiniku   {:url "https://babiniku.net/api/telemetry-report?window_days=7"
                    :fmt :json :key :audience :timeout 15
                    :secret-service "net-babiniku TELEMETRY_REPORT_SECRET"
                    :auth-header :bearer}
   ;; app-aozora organism-engagement (2026-07-16, ADR-2607151900): appview.aozora.app
   ;; /api/engagement が {:engagement {:organism-engagement-ratio r|nil ...集計}} を返す。
   ;; :merge true で top-level に載せ gate :hyp/aozora-organism-content が読む。
   ;; live :eavt scan で ~14s のため :timeout 25 (既定 8s は他 product 保護、据置)。
   :app-aozora     {:url "https://appview.aozora.app/api/engagement"    :fmt :json :merge true :timeout 25}
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

(defn- auth-args
  "curl args for an emitter that needs a credential. `:secret-service` names a
  macOS Keychain item (ONE targeted lookup — never an enumeration), and
  `:auth-header` says how to present it. Returns nil when the secret is absent,
  and the caller then skips the fetch entirely rather than sending an empty
  credential: an unauthenticated request to a gated endpoint is a 403 that
  reads, in the metrics file, exactly like 'no data'."
  [{:keys [secret-service auth-header]}]
  (when secret-service
    (when-let [secret (keychain secret-service)]
      (case auth-header
        :bearer         ["-H" (str "authorization: Bearer " secret)]
        :internal-trust ["-H" (str "x-internal-trust: " secret)]
        nil))))

(defn fetch-emitter
  "→ parsed emitter map, or nil if unreachable/unparseable (no-op).

  A gated emitter whose secret is not on this machine is skipped, not fetched
  anonymously — see auth-args."
  [{:keys [url fmt timeout secret-service] :as cfg}]
  (try
    (let [auth (auth-args cfg)]
      (when-not (and secret-service (nil? auth))
        (let [r (curl/get url {:throw false
                               :raw-args (into ["--max-time" (str (or timeout 8)) "-A" collect-ua]
                                               (or auth []))})]
          (when (= 200 (:status r))
            (case fmt
              :edn  (clojure.edn/read-string (:body r))
              :json (json/parse-string (:body r) true))))))
    (catch :default _ nil)))

(defn- merge-one-emitter
  [m {:keys [key merge] :as cfg}]
  (if-let [em (fetch-emitter cfg)]
    (cond-> (if merge (clojure.core/merge m em) (assoc m key em))
      true (update :sources (fnil conj []) :emitter))
    m))

(defn merge-emitter
  "Merge a product's live gate-metric emitter output into its metrics map m.
   :key → nest under that key; :merge → shallow-merge top-level.

   A product may declare either one emitter (map) or several (vector), applied
   in order. Several because a product can have both a public funnel and a
   gated audience surface, and folding them into one endpoint would mean
   publishing the gated half."
  [m product]
  (let [cfg (get gate-emitters product)]
    (reduce merge-one-emitter m (cond (map? cfg) [cfg] (sequential? cfg) cfg :else []))))

(defn traffic-quality
  "24h の status-mix から probe(4xx)/5xx 率を % で。zone の uniques/requests を
   「見込み顧客」と読ませないための補正係数 (実測比、推定ではない)。"
  [{:keys [status-mix real-4xx]}]
  (let [total (reduce + (vals (or status-mix {})))]
    (when (pos? total)
      (cond-> {:window "24h"
               :probe-4xx-pct (Math/round (* 100.0 (/ (get status-mix :client-error 0) total)))
               :error-5xx-pct (Math/round (* 100.0 (/ (get status-mix :server-error 0) total)))}
        ;; #47 (2026-09-03): scanner secret-probe 403 を除いた実害系列。従来の
        ;; probe-4xx-pct は据え置き — 正本の定義改変はせず追加のみ。
        (some? real-4xx) (assoc :real-4xx-pct (Math/round (* 100.0 (/ real-4xx total))))))))

(defn signal [p m]
  (let [z (:zone m) w (:workers-invocations-7d m) tq (:traffic-quality m)]
    (str/join "、"
              (remove nil?
                      [(when z (str (:zone-name m) " 実測 " (:requests-7d z) " req/7d・"
                                    (:uniques-7d-sum z) " uniques(日次和)"
                                    (when tq (str "・うち4xx probe " (:probe-4xx-pct tq) "%(24h)"
                                                  (when-let [r (:real-4xx-pct tq)]
                                                    (str " (real " r "%)"))))))
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
        ;; A failed Cloudflare call must not be written as an empty measurement:
        ;; :sources may only claim :cloudflare for the numbers it actually got,
        ;; and the reason it got none has to survive into the file.
        cf-why (or (cf-failed zt) (cf-failed wi))
        as-of (date-days-ago 0)]
    (when cf-why
      (println "WARNING cloudflare unmeasured:" cf-why
               "— :sources will not claim :cloudflare for these products"))
    (fs/create-dirs out-dir)
    (doseq [[p {:keys [zone zone-name workers health] :as cfg}] products]
      (let [m (cond-> {:as-of as-of
                       :sources (vec (remove nil? [(when-not cf-why :cloudflare)
                                                   (when (and (:stripe cfg) stripe) :stripe)
                                                   (when health :health)]))}
                cf-why (assoc :cloudflare-unmeasured cf-why)
                (and zone (not cf-why))
                (assoc :zone (assoc (get zt zone) :zone zone-name) :zone-name zone-name)
                (and zone (not cf-why))
                (as-> m' (let [tp (zone-top-paths tok zone p)]
                           (cond-> m'
                             tp (assoc :paths (assoc tp :window "24h")
                                       :top-paths (top-paths-summary tp))
                             (traffic-quality tp) (assoc :traffic-quality (traffic-quality tp)))))
                (and (seq workers) (not cf-why))
                (assoc :workers-invocations-7d
                       (into {} (filter (fn [[k _]] (contains? workers k)) wi)))
                (and (:stripe cfg) stripe) (assoc :stripe stripe)
                health (assoc :health-status (http-status health)))
            m (merge-emitter m p)
            ;; cloud-itonami free path: fold /api/health freePath into metrics so
            ;; portfolio ticks see tenants/selfReg/agentRuns (not just HTTP status).
            m (if (= p :cloud-itonami)
                (if-let [h (http-get-json "https://itonami.cloud/api/health")]
                  (cond-> m
                    true (assoc :health-live h)
                    (:freePath h) (assoc :free-path (:freePath h))
                    true (update :sources (fnil conj []) :itonami-free-path))
                  m)
                m)
            ;; ai-gftd-apex: Free→Plus conversion (ai-gftd-apex PR #2
            ;; subscription.cljc の定義に一致: pct = plus/(free+plus) 4dp、
            ;; base 空は 0.0)。plus = Stripe 実カウント (apex price, PAID のみ)。
            ;; free = 0 が現時点の真実 — apex は Privacy Contract により
            ;; アカウント主キーを持たず「登録済み Free ユーザー」という母集団が
            ;; まだ存在しない (checkout + actor-binding が載ったら records 経由で
            ;; 実数化する)。
            m (if (= p :ai-gftd-apex)
                (let [plus (get-in m [:stripe :apex-active-subscriptions] 0)
                      free 0
                      base (+ free plus)
                      pct (if (pos? base)
                            (/ (js/Math.round (* 10000 (/ plus base))) 10000.0)
                            0.0)]
                  (assoc m
                         :subscription {:free free :plus plus :conversion-pct pct}
                         :conversion {:pct pct}))
                m)
            m (assoc m :signal (signal p m))]
        (spit (str out-dir "/" (name p) ".edn") (with-out-str (pprint/pprint m)))
        (println "wrote" (name p) "-" (:signal m))))))

;; `nbb 70-tools/bmc/collect.cljs --self-test` — no token, no network.
;; Guards the one distinction this collector kept getting wrong: a Cloudflare
;; call that FAILED must not look like a Cloudflare call that returned zero.
(if (some #{"--self-test"} (vec *command-line-args*))
  (let [ok? (fn [label pred] (println (if pred "PASS" "FAIL") label) pred)
        errs   (cf-classify {:errors [{:message "Authentication error"}] :data nil})
        blank  (cf-classify {:foo 1})
        good   (cf-classify {:data {:viewer {:zones []}}})
        rs [(ok? "a body carrying errors is a failure, not empty data"
                 (= "Authentication error" (cf-failed errs)))
            (ok? "a body with neither data nor errors is also a failure"
                 (some? (cf-failed blank)))
            (ok? "a real body passes through untouched"
                 (and (nil? (cf-failed good)) (= good {:data {:viewer {:zones []}}})))
            (ok? "an empty-but-successful result stays empty, not failed"
                 (nil? (cf-failed (cf-classify {:data {:viewer {:accounts []}}}))))
            ;; the consequence the files showed: :sources claimed :cloudflare
            ;; while carrying no Cloudflare number
            (ok? ":sources omits :cloudflare when the call failed"
                 (= [:health]
                    (vec (remove nil? [(when-not (cf-failed errs) :cloudflare) nil :health]))))
            (ok? "and still claims it when the call worked"
                 (= [:cloudflare :health]
                    (vec (remove nil? [(when-not (cf-failed good) :cloudflare) nil :health]))))
            ;; #47 (2026-09-03): scanner secret-probe 403 と real 4xx の分離
            (ok? "scanner-403-count: /.env 403 だけを数える"
                 (= 8 (scanner-403-count
                       [{:count 5 :dimensions {:clientRequestPath "/mongodb/.env" :edgeResponseStatus 403}}
                        {:count 3 :dimensions {:clientRequestPath "/.git/config" :edgeResponseStatus 403}}
                        {:count 42 :dimensions {:clientRequestPath "/xrpc/ai.gftd.apps.shinshi.listAuthorFeed" :edgeResponseStatus 499}}
                        {:count 10 :dimensions {:clientRequestPath "/firebase-key.json" :edgeResponseStatus 404}}
                        {:count 2 :dimensions {:clientRequestPath "/wp-admin/install.php" :edgeResponseStatus 200}}])))
            (ok? "scanner-403-count: 403 でも probe path でなければ外さない"
                 (= 0 (scanner-403-count
                       [{:count 7 :dimensions {:clientRequestPath "/en/actresses" :edgeResponseStatus 403}}])))
            (ok? "traffic-quality: probe-4xx-pct は据え置き、real-4xx-pct が追加で付く"
                 (= {:window "24h" :probe-4xx-pct 20 :error-5xx-pct 0 :real-4xx-pct 8}
                    (traffic-quality {:status-mix {:ok 571 :client-error 143 :server-error 0}
                                      :real-4xx 55})))
            (ok? "traffic-quality: real-4xx 無しの旧データには新キーを足さない"
                 (= {:window "24h" :probe-4xx-pct 20 :error-5xx-pct 0}
                    (traffic-quality {:status-mix {:ok 571 :client-error 143}})))]]
    (js/process.exit (if (every? true? rs) 0 1)))
  (-main))

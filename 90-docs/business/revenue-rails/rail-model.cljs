;; rail-model.cljs — 収益レール比較モデル（marketing cost 込み）
;;
;; 問い: kotobase.net / murakumo.cloud / shinshi.club / itonami.cloud で
;;       収益を生むために、どのレールへ marketing cost を積むのが合理的か。
;;
;; 方針（ADR-2608095000）:
;;   1. 転換率を推測しない。実測ゼロ実績から dynamics/upper-bound-rate-from-zero-events
;;      で 95% 上界を出し、「上界ですら赤字なら赤字」という形でのみ結論する。
;;   2. gate（実入金に必要な前提条件）を先に評価する。gate が閉じているレールへの
;;      marketing 支出は転換率と無関係に 100% 損失なので、上界計算より先に効く。
;;   3. 域外の pool に未計測の転換率を掛けて期待値を捏造しない
;;      （dynamics/leverage-score の :uncomputable-until-measured 規律）。
;;
;; 実行:
;;   nbb --classpath "orgs/kotoba-lang/dynamics/src" \
;;     90-docs/business/revenue-rails/rail-model.cljs
;;
;; 出力: 90-docs/business/revenue-rails/rail-model-results.edn

(ns rail-model
  (:require [dynamics.core :as dyn]
            [clojure.edn :as edn]
            [cljs.pprint :as pp]
            ["fs" :as fs]))

(def as-of "2026-08-08")

;; ---------------------------------------------------------------------------
;; 実測入力 — SSoT は 90-docs/business/metrics/<product>.edn
;; ---------------------------------------------------------------------------

(defn read-metrics [product]
  (let [p (str "90-docs/business/metrics/" product ".edn")]
    (when (fs/existsSync p)
      (edn/read-string (fs/readFileSync p "utf8")))))

(defn observed []
  (let [k (read-metrics "net-kotobase")
        s (read-metrics "club-shinshi")
        i (read-metrics "cloud-itonami")
        m (read-metrics "cloud-murakumo")]
    {:kotobase {:visitors (get-in k [:funnel :visitors])
                :signups  (get-in k [:funnel :signups])
                :paid     (get-in k [:funnel :checkouts])
                :x402-submissions (get-in k [:x402 :submissions])
                :x402-settlements (get-in k [:x402 :settlements])
                :as-of (:as-of k)}
     :shinshi  {:visitors (get-in s [:funnel :visitors])
                :paid     (get-in s [:funnel :paying])
                :as-of (:as-of s)}
     :itonami  {:tenants  (get-in i [:funnel :externalTenants])
                :paid     (get-in i [:funnel :paid])
                :as-of (:as-of i)}
     :murakumo {:demand-apps (get-in m [:cost :federation :active-demand-apps])
                :as-of (:as-of m)}}))

;; ---------------------------------------------------------------------------
;; Gate — 実入金に必要な前提条件。閉じていれば marketing は全損
;; ---------------------------------------------------------------------------

(def gates
  "COMMERCIAL-GO-NO-GO.md（2026-08-08）の Portfolio gate から、
   各レールが「1円受け取る」ために通す必要のある条件だけを抜いたもの。
   :open? は agent 判断ではなく、その文書の status が green かどうか。"
  {:web-subscription
   {:needs #{:contracting-entity :psp-account :jp-tax :terms-privacy :refund-policy}
    :blocking ["Stripe は Gftd Japan、operator は AWAI Network、収納代行契約が未署名 draft"
               "日本の登記・税務に専門家結論なし"
               "Terms/Privacy が DRAFT"]
    :open? false}

   :ad-network-thirdparty
   {:needs #{:contracting-entity :payout-account :jp-tax :traffic}
    :blocking ["ExoClick / AdSense 等は法人の payout 口座と税務書類へ fiat で支払う — web-subscription と同じ gate"
               "実測: ExoClick は 7d 再構成 USD 0 / imp 0（2026-07-09）"
               "fill 率・payout ポリシー・支払サイクルが他社に従属する"]
    :open? false}

   :ad-network-firstparty
   {:needs #{:wallet :publisher-inventory :advertiser}
    :blocking ["USDC で入札する広告主が 0"
               "adnet が publisher（shinshi / isekai / media-gamers 等）へ未配線"]
    :note "kotoba-lang/adnet は広告主が USDC で入札し x402 rail で決済する設計。
           したがって fiat payout の gate を通さない — usdc-x402 と同じ開いたレール。
           2026-08-08 訂正: 当初 ad-network を一括で CLOSED としたのは
           third-party と first-party の混同だった。"
    :open? true}

   :mobile-iap
   {:needs #{:contracting-entity :payout-account :jp-tax :store-account :store-review}
    :blocking ["Apple/Google の developer account は法人・税務書類・銀行口座を要求する"
               "IAP take rate 15–30% が上乗せされる"
               "審査サイクルが加わる"
               "この workspace に iOS/Android の app 実体は無い（com-ios-sdk / com-android-aosp は外部仕様ミラー）"]
    :open? false}

   :usdc-x402
   {:needs #{:wallet :seller-registration}
    :blocking []
    :note "PSP 関係を必要としない唯一のレール。nexus-x402 facilitator は live、
           seller 3 件登録済み、kotobase の x402 challenge も発火している。"
    :open? true}})

;; ---------------------------------------------------------------------------
;; 転換率の上界 — ゼロ実績を「測っていない」ではなく「≤X と測った」に変える
;; ---------------------------------------------------------------------------

(defn bound
  "successes=0 の時だけ上界を返す。1件でも成功があれば点推定が可能なので
   このモデルの担当外（:has-observed-success を返して呼び出し側に投げる）。"
  [{:keys [trials successes]}]
  (cond
    (or (nil? trials) (zero? trials)) :no-trials
    (and successes (pos? successes)) :has-observed-success
    :else (dyn/upper-bound-rate-from-zero-events trials)))

;; ---------------------------------------------------------------------------
;; marketing cost モデル
;; ---------------------------------------------------------------------------

(def unit-economics
  {:web-subscription
   {:price-jpy-month 2980          ; owner 確定 2026-08-08
    :gross-margin 0.88             ; 原価モデル（execution plan）
    :retention-months 12}})        ; 仮定。実測ではない

(defn owned-inventory-pv-7d
  "自社 zone の実測 pageviews/7d を metrics ディレクトリから読む。
   ad rail の inventory はこれ。RPM は未計測なので掛けて収益を作らない。

   **値をここに焼かない。** SSoT は 90-docs/business/metrics/<product>.edn であり、
   焼くと advisor loop が metrics を更新するたびに黙って古くなる — 本モデルが
   まさに扱っている『記録が計測から乖離する』欠陥をモデル自身が持つことになる。"
  []
  (into {}
        (for [f (.readdirSync fs "90-docs/business/metrics")
              :let [d (edn/read-string
                        (fs/readFileSync (str "90-docs/business/metrics/" f) "utf8"))
                    pv (get-in d [:zone :pageviews-7d])]
              :when pv]
          [(keyword (subs f 0 (- (count f) 4))) pv])))

(defn ad-rail-inventory
  "収益は出さない。出すのは『月間 PV』と『目標収益に必要な RPM』だけ。
   RPM を仮定して収益を作るのは捏造なので、逆に解いて必要 RPM を返す。"
  [target-monthly-jpy]
  (let [inv (owned-inventory-pv-7d)
        pv-7d (reduce + (vals inv))
        pv-month (* pv-7d (/ 30.0 7.0))]
    {:owned-inventory inv
     :owned-pv-7d pv-7d
     :owned-pv-month (int pv-month)
     :target-monthly-jpy target-monthly-jpy
     :required-rpm-jpy (/ target-monthly-jpy (/ pv-month 1000.0))
     :note (str "RPM は未計測。measured な唯一の第三者 ad 実績は ExoClick の "
                "USD 0 / imp 0 で、これは RPM ではなく fill の失敗。"
                "first-party adnet の RPM は広告主が 1 件も居ないため未定義。")}))

(defn breakeven-cpc
  "転換率の上界 r、LTV から、marketing の 1 訪問あたり許容単価を出す。
   これは『上界』なので、実際に払ってよい額ではなく
   『これを超えたら上界ですら赤字』という天井。"
  [{:keys [price-jpy-month gross-margin retention-months]} r]
  (when (number? r)
    (let [ltv (* price-jpy-month gross-margin retention-months)]
      {:ltv-jpy ltv
       :conversion-upper-bound r
       :breakeven-cpc-jpy-upper-bound (* ltv r)})))

;; ---------------------------------------------------------------------------
;; leverage 評価 — dynamics に委譲（自前で採点しない）
;; ---------------------------------------------------------------------------

(def interventions
  [{:id :close-x402-settlement
    :label "x402 の settlement を 0 → 1 にする（非 owner の実決済を 1 件観測）"
    :band :band/C                  ; feedback loop gain — 既存ループの gain を 0 から立ち上げる
    :tractability 0.8}             ; rail は live、seller 登録済み、残るは実決済

   {:id :open-psp-gate
    :label "収納主体と PSP を一致させ、登記・税務の結論を得る"
    :band :band/B                  ; rules / information-flow structure
    :tractability 0.2}             ; owner + counsel 依存、agent が動かせない

   {:id :buy-traffic-web
    :label "web subscription funnel へ marketing 支出"
    :band :band/E                  ; constants / parameters — buffer を増やすだけ
    :tractability 0.9
    :pool-size 101750}             ; execution plan の「30 Standard に要る訪問数」

   {:id :launch-mobile-apps
    :label "iOS / Android app を公開して IAP 収益"
    :band :band/D                  ; stock-flow structure — 新しい経路を足す
    :tractability 0.1}             ; entity + store account + 審査 + 実装ゼロから

   {:id :expand-ad-network-thirdparty
    :label "ExoClick 等の第三者 ad network へ展開"
    :band :band/E
    :tractability 0.4}

   {:id :wire-adnet-to-publishers
    :label "adnet を自社 publisher へ配線し、USDC 建て在庫を立てる"
    :band :band/D                  ; stock-flow structure — 既存在庫に収益経路を接続する
    :tractability 0.7}             ; adnet は実装済み、publisher も実在、配線が残っている

   {:id :grow-owned-inventory
    :label "media-gamers/kouryaku・gameya 等で自社 PV 在庫を増やす（SEO/コンテンツ）"
    :band :band/D
    :tractability 0.6}             ; pipeline 実装済み、corpus 収集済み、公開と継続運用が残る

   {:id :land-first-usdc-advertiser
    :label "USDC で入札する広告主を 1 件獲得する"
    :band :band/C                  ; feedback loop gain — 広告ループの gain を 0 から立ち上げる
    :tractability 0.4}])

;; ---------------------------------------------------------------------------

(defn -main []
  (let [obs (observed)
        k (:kotobase obs)
        bounds
        {:kotobase-visitor->paid
         (bound {:trials (:visitors k) :successes (:paid k)})
         :kotobase-signup->paid
         (bound {:trials (:signups k) :successes (:paid k)})
         :kotobase-x402-submission->settlement
         (bound {:trials (:x402-submissions k) :successes (:x402-settlements k)})
         :shinshi-visitor->paid
         (bound {:trials (get-in obs [:shinshi :visitors]) :successes (get-in obs [:shinshi :paid])})
         :itonami-tenant->paid
         (bound {:trials (get-in obs [:itonami :tenants]) :successes (get-in obs [:itonami :paid])})}

        econ (breakeven-cpc (:web-subscription unit-economics)
                            (:kotobase-visitor->paid bounds))

        ranked (dyn/rank-interventions interventions)

        results
        {:as-of as-of
         :observed obs
         :conversion-upper-bounds bounds
         :note-bounds
         (str "successes=0 の Bernoulli 上界（95%）。点推定ではない。"
              "trials が小さいほど上界は緩む — itonami の tenant→paid は 5 trials しかないので"
              "上界が大きく出るが、それは有望さではなく情報が無いことを意味する。")
         :web-subscription-economics econ
         :ad-rail (ad-rail-inventory 300000)
         :gates gates
         :ranked-interventions ranked
         :note-pool-tap
         (str "buy-traffic-web は :pool-size を持つが conversion-rate を渡していないため "
              ":expected-yield は :uncomputable-until-measured になる。"
              "大きな pool に未計測の転換率を掛けて期待値を作らないための規律。")}]

    (fs/writeFileSync "90-docs/business/revenue-rails/rail-model-results.edn"
                      (with-out-str (pp/pprint results)))

    (println "=== conversion upper bounds (95%, zero observed successes) ===")
    (doseq [[k' v] bounds]
      (println (str "  " (name k') ": "
                    (if (number? v) (str "<= " (.toFixed (* 100 v) 3) " %") v))))
    (println)
    (println "=== web subscription marketing ceiling ===")
    (if econ
      (do (println (str "  LTV (¥2,980 x 88% x 12mo) = ¥" (int (:ltv-jpy econ))))
          (println (str "  breakeven CPC upper bound = ¥"
                        (.toFixed (:breakeven-cpc-jpy-upper-bound econ) 2)
                        "  <- 上界ですらこれ以上払うと赤字")))
      (println "  uncomputable"))
    (println)
    (println "=== ad rail (owned inventory) ===")
    (let [a (ad-rail-inventory 300000)]
      (println (str "  owned PV/7d = " (:owned-pv-7d a)
                    "  -> PV/month = " (:owned-pv-month a)))
      (println (str "  月次 ¥" (int (:target-monthly-jpy a)) " を出すのに必要な RPM = ¥"
                    (.toFixed (:required-rpm-jpy a) 0) " / 1000PV")))
    (println)
    (println "=== gates ===")
    (doseq [[rail g] gates]
      (println (str "  " (name rail) ": " (if (:open? g) "OPEN" "CLOSED"))))
    (println)
    (println "=== ranked interventions (dynamics/leverage-score) ===")
    (doseq [r ranked]
      (println (str "  " (.toFixed (:base-score r) 1) "  [" (name (:kind r)) "]  "
                    (:label r)
                    (when (:expected-yield r) (str "  expected-yield=" (:expected-yield r))))))
    (println)
    (println "wrote 90-docs/business/revenue-rails/rail-model-results.edn")))

(-main)

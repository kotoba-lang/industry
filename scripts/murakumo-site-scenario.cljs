#!/usr/bin/env nbb
;; murakumo-site-scenario.cljs — murakumo.cloud を **面の集合**として測る。
;;
;; `lp-copy-score.cljs` が 1 面のコピーを測るのに対し、ここは
;; **サイト全体が何をする形になっているか**を測る。
;;
;; ## なぜ「合計点」にしないか
;;
;; **面は役割が違うので、同じ物差しで足すと意味が消える。** 法定表示は説明的で
;; あるべきで、そこに CTA が無いのは欠陥ではない。逆に販売面に CTA が無ければ
;; 欠陥である。だから各面は**自分の役割に対する適合度**で測り、
;; 役割の重みを掛けてからサイト値にする。
;;
;;   :entry    玄関。**server-render された内容**で、販売面へ送る
;;   :sell     売る。CTA と購入経路があり、Interest が Doubt を上回る
;;   :acquire  集める。申込の入口があり、否定が薄い
;;   :explain  説明する。否定・留保は自由。CTA は要らない
;;   :legal    法定。到達できることだけが要件
;;
;; ## 測れなかった面は平均から外し、**外したことを出す**
;;
;; `lp-copy-score` が `UNMEASURABLE` を返した面を 0 点として混ぜると、
;; **測れなかったことが低品質と同じ顔になる。** 分母から外し、件数で申告する。
;;
;; ## 使い方
;;
;;   nbb scripts/murakumo-site-scenario.cljs            # 本番を測る
;;   nbb scripts/murakumo-site-scenario.cljs --json
;;
;; **この値は売上ではない。** 面の形しか見ていない。購入は Stripe の checkout
;; 件数、集客は実問い合わせ件数でしか測れない。

(ns murakumo-site-scenario
  (:require ["node:child_process" :as cp]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(defn- flag? [n] (some #(= n %) args))

(def base "https://murakumo.cloud")

(def surfaces
  "面 → 役割。**手書き（判断）。** どの面が何をするつもりかは測定では決まらない。
   SKU と法人は `storefront.cljc` と特商法表示に合わせてある —— 混ぜると
   『どちらの事業の面か』が読めなくなる。"
  [{:path "/"                    :role :entry   :sku :a   :entity "Gftd/AWAI"}
   {:path "/onprem"              :role :sell    :sku :a   :entity "Gftd Japan"}
   {:path "/onprem/3d"           :role :sell    :sku :a   :entity "Gftd Japan"}
   {:path "/onprem/3d/en"        :role :sell    :sku :a   :entity "Gftd Japan"}
   {:path "/go"                  :role :acquire :sku :b   :entity "AWAI Network"}
   {:path "/advertise"           :role :acquire :sku :b   :entity "AWAI Network"}
   {:path "/models"              :role :explain :sku :svc :entity "AWAI Network"}
   {:path "/docs"                :role :explain :sku :svc :entity "AWAI Network"}
   {:path "/gpu"                 :role :explain :sku nil  :entity nil}
   {:path "/gpu-market"          :role :explain :sku nil  :entity nil}
   {:path "/blog/"               :role :explain :sku nil  :entity nil}
   {:path "/legal/tokushoho.md"  :role :legal   :sku :a   :entity "Gftd Japan"}])

(def role-weight
  "役割の重み。**売る面が一番重い。** 説明面が完璧でも売れない。"
  {:entry 3 :sell 4 :acquire 2 :explain 1 :legal 1})

(defn- lp-val
  "`lp-copy-score` が UNMEASURABLE を返した面では `:lp` / `:sales` が無い。
   **既定 0 を入れて `:fail` にしてはいけない** —— 測れなかった検査が、測って
   落ちた検査と同じ顔になる。実測 2026-08-23、この既定のせいで `/` が
   『販売面への導線が無い』と報告された（server HTML には購入 CTA が在る）。"
  [r ks]
  (get-in r ks))

(def checks
  "役割ごとの検査。`[:id 説明 述語]`。述語は 1 面ぶんのスコアを受け取り、
   **測れなければ nil を返す**（`:unmeasurable` になり分母から外れる）。"
  {:entry
   [[:server-rendered "server-render された内容がある（クローラと初回表示が読める）"
     (fn [r] (when (:render r) (not (get-in r [:render :js-dependent?]))))]
    [:routes-to-sell "販売面への導線がある"
     (fn [r] (some-> (lp-val r [:lp :cta-words]) pos?))]]
   :sell
   [[:has-cta "CTA がある" (fn [r] (some-> (lp-val r [:lp :cta-words]) pos?))]
    [:has-buy-path "購入経路がある" (fn [r] (some-> (lp-val r [:lp :buy-links]) pos?))]
    [:interest-over-doubt "Interest が Doubt を上回る"
     (fn [r] (let [a (get-in r [:register :addressed])]
               (when a (> (get a :Interest 0) (get a :Doubt 0)))))]
    [:negation-under-25 "否定文が 25% 未満"
     (fn [r] (some->> (lp-val r [:sales :negation-ratio-pct]) (> 25)))]
    [:has-concrete "具体数がある" (fn [r] (some-> (lp-val r [:sales :concrete-numbers]) pos?))]]
   :acquire
   [[:has-cta "申込の入口がある" (fn [r] (some-> (lp-val r [:lp :cta-words]) pos?))]
    [:negation-under-15 "否定文が 15% 未満"
     (fn [r] (some->> (lp-val r [:sales :negation-ratio-pct]) (> 15)))]]
   :explain
   [[:has-substance "本文が 1,500 字以上" (fn [r] (>= (:chars r 0) 1500))]]
   :legal
   [[:reachable "到達できる" (fn [r] (>= (:chars r 0) 300))]]})

(defn- score-surfaces []
  (let [urls (map #(str base (:path %)) surfaces)
        out (str (cp/execSync
                  (str "nbb scripts/lp-copy-score.cljs --json "
                       (str/join " " (map pr-str urls)))
                  #js {:encoding "utf8" :maxBuffer 40000000}))
        parsed (js->clj (js/JSON.parse out) :keywordize-keys true)]
    (map (fn [s r] (merge s r)) surfaces parsed)))

(defn evaluate [r]
  (let [cs (get checks (:role r))
        results (mapv (fn [[id label f]]
                        (let [v (try (f r) (catch :default _ nil))]
                          {:id id :label label
                           :verdict (cond (nil? v) :unmeasurable v :pass :else :fail)}))
                      cs)
        answered (remove #(= :unmeasurable (:verdict %)) results)
        passed (filter #(= :pass (:verdict %)) results)]
    (assoc r :checks results
             :answered (count answered)
             :passed (count passed)
             :fitness (when (seq answered)
                        (js/Number (.toFixed (* 100.0 (/ (count passed) (count answered))) 1))))))

(defn -main []
  (let [rs (mapv evaluate (score-surfaces))
        scored (filter :fitness rs)
        unmeasured (remove :fitness rs)
        wsum (reduce + (map #(role-weight (:role %)) scored))
        site (when (pos? wsum)
               (js/Number (.toFixed (/ (reduce + (map #(* (role-weight (:role %)) (:fitness %)) scored))
                                       wsum) 1)))]
    (if (flag? "--json")
      (println (js/JSON.stringify (clj->js {:surfaces rs :site-fitness site
                                            :unmeasured (mapv :path unmeasured)}) nil 2))
      (do
        (println "murakumo.cloud — 面の適合度（役割ごとの検査。決定論的）\n")
        (println (str (.padEnd "surface" 22) (.padEnd "role" 9) (.padEnd "SKU" 5)
                      (.padEnd "適合" 8) "落ちた検査"))
        (doseq [r rs]
          (println (str (.padEnd (:path r) 22)
                        (.padEnd (name (:role r)) 9)
                        (.padEnd (str (some-> (:sku r) name)) 5)
                        (.padEnd (if (:fitness r)
                                   (str (:fitness r) "% " (:passed r) "/" (:answered r))
                                   "測定不能") 8)
                        (str/join ", " (map :label (filter #(= :fail (:verdict %)) (:checks r))))
                        (let [u (filter #(= :unmeasurable (:verdict %)) (:checks r))]
                          (when (seq u) (str "  ⟨測れず: " (str/join ", " (map :label u)) "⟩"))))))
        (println)
        (println (str "サイト適合度（役割で重み付け）: " site "%"))
        (println (str "  重み: " (pr-str role-weight)))
        (when (seq unmeasured)
          (println (str "  ⚠ 分母から外した面: " (str/join ", " (map :path unmeasured))
                        " —— **0 点ではなく未測定**")))
        (println "\n**この値は売上ではない。** 面の形しか見ていない。")))))

(-main)

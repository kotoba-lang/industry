(ns cp2 (:require [valueflows.algorithms.critical-path :as cp]))
(defn res [r] {:resource-conforms-to r :action :consume :quantity {:has-numerical-value 1 :has-unit :each}})
(defn out [r] {:resource-conforms-to r :action :produce :quantity {:has-numerical-value 1 :has-unit :each}})
;; 2026-08-01 までの実測を反映した改訂版。
;; - justco-decide / justco-execute を削除（2026-07-31 に JustCo 側が即時解除済み）
;; - 本店移転登記を追加（解除された住所が登記上の本店）
;; - MF 未納によるサービス停止予告があるため mf-export を最優先の枝に
(def recovery
  {:recipe/processes
   [{:id :refresh-archive      :duration 1  :inputs []                       :outputs [(out :current-facts)]}
    {:id :mf-pay-arrears       :duration 1  :inputs [(res :current-facts)]   :outputs [(out :mf-alive)]}
    {:id :mf-export-payroll    :duration 1  :inputs [(res :mf-alive)]        :outputs [(out :wage-figure)]}
    {:id :mf-export-ledger     :duration 1  :inputs [(res :mf-alive)]        :outputs [(out :bs-detail)]}
    {:id :check-ar-settlement  :duration 1  :inputs [(res :bs-detail)]       :outputs [(out :ar-status)]}
    {:id :check-roudou-case    :duration 1  :inputs [(res :current-facts)]   :outputs [(out :case-status)]}
    {:id :collect-ar           :duration 20 :inputs [(res :ar-status)]       :outputs [(out :cash)]}
    {:id :tax-refund-claim     :duration 10 :inputs [(res :bs-detail)]       :outputs [(out :refund-cash)]}
    {:id :pay-wages            :duration 1  :inputs [(res :wage-figure) (res :cash) (res :refund-cash) (res :case-status)] :outputs [(out :wage-cleared)]}
    {:id :hq-relocation-filing :duration 14 :inputs [(res :current-facts)]   :outputs [(out :hq-registered)]}
    {:id :saas-inventory       :duration 2  :inputs [(res :bs-detail)]       :outputs [(out :saas-list)]}
    {:id :cancel-saas          :duration 3  :inputs [(res :saas-list)]       :outputs [(out :opex-cut)]}
    {:id :card-topup           :duration 1  :inputs [(res :cash) (res :opex-cut)] :outputs [(out :credit-restored)]}
    {:id :reopen-sales         :duration 10 :inputs [(res :credit-restored) (res :wage-cleared) (res :hq-registered)] :outputs [(out :invoice-2026)]}]})
(let [s (cp/schedule recovery {})]
  (println "全体:" (:project-duration s) "日")
  (println "クリティカルパス:" (pr-str (:critical-path s)))
  (println "ボトルネック:" (pr-str (:bottlenecks s)))
  (doseq [[id n] (sort-by (comp :es val) (:nodes s))]
    (println (str "  " (.padEnd (str id) 22) " 開始" (.padStart (str (:es n)) 4)
                  " 終了" (.padStart (str (:ef n)) 4) " 余裕" (.padStart (str (:slack n)) 4)
                  (if (:critical? n) "  ← critical" "")))))

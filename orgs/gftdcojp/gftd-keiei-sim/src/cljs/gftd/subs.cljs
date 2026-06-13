(ns gftd.subs
  "re-frame subscriptions。:data から各パネル向けに派生させる。"
  (:require [re-frame.core :as rf]))

(rf/reg-sub :data     (fn [db _] (:data db)))
(rf/reg-sub :thinking (fn [db _] (:thinking db)))

(rf/reg-sub :kpis      :<- [:data] (fn [d _] (:kpis d)))
(rf/reg-sub :proposals :<- [:data] (fn [d _] (:proposals d)))
(rf/reg-sub :intel     :<- [:data] (fn [d _] (:intel d)))
(rf/reg-sub :ledger    :<- [:data] (fn [d _] (:ledger d)))
(rf/reg-sub :llm-live  :<- [:data] (fn [d _] (:llm_live d)))
(rf/reg-sub :status    :<- [:kpis] (fn [k _] (:status k)))
(rf/reg-sub :turn      :<- [:kpis] (fn [k _] (:turn k)))

(rf/reg-sub :turn-history :<- [:data] (fn [d _] (:turn_history d)))
(rf/reg-sub :discussion   :<- [:data] (fn [d _] (:discussion d)))
(rf/reg-sub :live-m365    :<- [:data] (fn [d _] (:live_m365 d)))
(rf/reg-sub :modal        (fn [db _] (:modal db)))

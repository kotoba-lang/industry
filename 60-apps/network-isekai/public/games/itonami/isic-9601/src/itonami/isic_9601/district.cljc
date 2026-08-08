(ns itonami.isic-9601.district
  "A shop board's rules, per district — so the game is eight businesses rather than one.

  Until now `logic.cljc` carried the laundry's tables as module-level `def`s, which made
  every other district on the map a picture of a shop you could not walk into.

  ## What is derived and what is authored

  The line matters, because the game's whole claim rests on one side of it.

  **Derived, from `world.cljc`** (which was read out of the eight repos' own `phase.cljc`
  and `governor.cljc` — see `world/district-evidence`):

    the operations, in order          `:ops`
    which of them never automates     `:never-auto`  → `:hard-human?`
    what phase 3 may run unattended   `:auto-at-3`   → the phase table's `:auto`

  **Authored here**: the Japanese labels, what each business cleans, and the process
  vocabulary its advisor proposes from. Presentation, not invariant.

  **The game's own staging**: which operations open at phases 1 and 2. The repos each
  stage their rollout slightly differently, and only 9601's was transcribed exactly; the
  rest use the shape 'phase 1 opens the first operation, phase 2 opens everything that is
  not the never-auto one, phase 3 opens all'. That is a *plausible* reading of their
  ladders, not a transcription, and it is marked as such rather than quietly presented as
  read from the source. `:auto` is never guessed — it is always `:auto-at-3`.

  Same subset discipline as the rest of the game: pure data, pure functions, no interop,
  no keyword-as-function."
  (:require [itonami.isic-9601.world :as world]))

;; --------------------------------------------------------------------------
;; presentation — the part that is authored
;; --------------------------------------------------------------------------

(def presentation
  "Per-district labels and vocabulary. Keyed by district id, then by op keyword for the
  station labels. Nothing here changes what may or may not automate."
  {"isic-9601"
   {:subject "衣類" :cert "溶剤取扱資格" :conflict "洗濯表示"
    :method "処理方法" :method-verb "洗う"
    :labels {:garment/intake "受付" :careplan/verify "取扱方法"
             :certification/screen "資格照合"
             :actuation/apply-cleaning-process "洗浄"
             :actuation/return-garment "返却"}
    :processes ["dry-clean" "wet-clean" "tumble-dry" "bleach" "press"]
    :kinds [{:desc "ウールのジャケット" :forbidden ["bleach" "tumble-dry"]}
            {:desc "シルクのブラウス"   :forbidden ["bleach" "tumble-dry"]}
            {:desc "綿のシャツ"         :forbidden []}
            {:desc "ダウンコート"       :forbidden ["dry-clean"]}
            {:desc "カシミヤのセーター" :forbidden ["tumble-dry" "wet-clean"]}
            {:desc "リネンのワンピース" :forbidden ["bleach"]}]}

   "isic-4520"
   {:subject "自動車" :cert "整備士資格" :conflict "整備要領"
    :method "作業内容" :method-verb "洗う"
    :labels {:log-service-record "作業記録" :schedule-service-operation "作業計画"
             :coordinate-parts-order "部品手配" :flag-safety-concern "安全指摘"}
    :processes ["high-pressure-wash" "engine-degrease" "undercarriage" "wax" "interior"]
    :kinds [{:desc "軽トラック"       :forbidden ["engine-degrease"]}
            {:desc "電気自動車"       :forbidden ["high-pressure-wash" "engine-degrease"]}
            {:desc "旧車のセダン"     :forbidden ["high-pressure-wash"]}
            {:desc "商用バン"         :forbidden []}
            {:desc "オープンカー"     :forbidden ["high-pressure-wash" "interior"]}
            {:desc "四輪駆動車"       :forbidden ["wax"]}]}

   "isic-9609"
   {:subject "動物" :cert "動物取扱責任者" :conflict "健康記録"
    :method "施術内容" :method-verb "洗う"
    :labels {:client/intake "受付" :serviceplan/verify "施術計画"
             :background-check/screen "資格照合"
             :actuation/finalize-referral "紹介確定"}
    :processes ["full-groom" "bath-only" "clip" "de-shed" "medicated-bath"]
    :kinds [{:desc "長毛の猫"     :forbidden ["clip"]}
            {:desc "子犬"         :forbidden ["medicated-bath" "clip"]}
            {:desc "老犬"         :forbidden ["de-shed" "medicated-bath"]}
            {:desc "短毛の犬"     :forbidden []}
            {:desc "皮膚炎の犬"   :forbidden ["full-groom" "de-shed"]}
            {:desc "うさぎ"       :forbidden ["bath-only" "medicated-bath"]}]}

   "isic-8121"
   {:subject "建物" :cert "作業主任者" :conflict "建材仕様"
    :method "清掃方法" :method-verb "清掃する"
    :labels {:log-service-record "作業記録" :schedule-cleaning-operation "清掃計画"
             :coordinate-supply-order "資材手配" :flag-safety-concern "安全指摘"}
    :processes ["strip-and-wax" "steam" "acid-wash" "dry-buff" "pressure-wash"]
    :kinds [{:desc "大理石の床"       :forbidden ["acid-wash"]}
            {:desc "木の床"           :forbidden ["steam" "pressure-wash"]}
            {:desc "コンクリート土間" :forbidden []}
            {:desc "カーペット階段"   :forbidden ["strip-and-wax" "acid-wash"]}
            {:desc "ガラス外壁"       :forbidden ["acid-wash" "dry-buff"]}
            {:desc "ビニル床"         :forbidden ["acid-wash"]}]}

   "isic-8129"
   {:subject "プラント" :cert "特別教育修了" :conflict "設備仕様"
    :method "洗浄方法" :method-verb "洗浄する"
    :labels {:log-service-record "作業記録" :schedule-service-operation "作業計画"
             :coordinate-supply-order "資材手配" :flag-safety-concern "安全指摘"}
    :processes ["chemical-clean" "hydroblast" "dry-ice" "vacuum" "solvent-flush"]
    :kinds [{:desc "熱交換器"       :forbidden ["dry-ice"]}
            {:desc "貯蔵タンク"     :forbidden ["hydroblast"]}
            {:desc "配管ライン"     :forbidden ["dry-ice" "vacuum"]}
            {:desc "反応槽"         :forbidden ["solvent-flush"]}
            {:desc "集塵ダクト"     :forbidden ["chemical-clean"]}
            {:desc "冷却塔"         :forbidden []}]}

   "isic-3700"
   {:subject "排水" :cert "下水道管理技士" :conflict "管路台帳"
    :method "作業方法" :method-verb "浚渫する"
    :labels {:log-system-record "系統記録" :schedule-maintenance "保守計画"
             :order-supplies "資材手配" :flag-safety-concern "安全指摘"}
    :processes ["jetting" "rodding" "vacuum" "cctv-survey" "chemical-dose"]
    :kinds [{:desc "老朽陶管"     :forbidden ["jetting" "rodding"]}
            {:desc "合流管渠"     :forbidden ["chemical-dose"]}
            {:desc "圧送管"       :forbidden ["rodding"]}
            {:desc "マンホール"   :forbidden []}
            {:desc "雨水吐き"     :forbidden ["chemical-dose" "vacuum"]}
            {:desc "本管"         :forbidden []}]}

   "isic-3811"
   {:subject "ごみ" :cert "収集運搬業許可" :conflict "分別区分"
    :method "収集方法" :method-verb "収集する"
    :labels {:pickup/schedule "収集計画" :manifest/record "マニフェスト"
             :dispute/request "異議申立"}
    :processes ["compactor" "roll-off" "manual-load" "bulk-lift" "transfer"]
    :kinds [{:desc "事業系一般廃棄物" :forbidden []}
            {:desc "がれき類"         :forbidden ["compactor" "manual-load"]}
            {:desc "廃プラスチック"   :forbidden ["bulk-lift"]}
            {:desc "剪定枝"           :forbidden ["compactor"]}
            {:desc "厨芥"             :forbidden ["roll-off" "bulk-lift"]}
            {:desc "紙くず"           :forbidden []}]}

   "isic-3900"
   {:subject "土壌" :cert "作業環境測定士" :conflict "汚染調査"
    :method "浄化方法" :method-verb "浄化する"
    :labels {:log-remediation-record "調査記録" :schedule-remediation-operation "浄化計画"
             :coordinate-disposal "処分手配" :flag-contamination-concern "汚染指摘"}
    :processes ["excavate" "soil-wash" "bioremediate" "thermal" "containment"]
    :kinds [{:desc "油汚染土"     :forbidden ["containment"]}
            {:desc "重金属汚染土" :forbidden ["bioremediate" "thermal"]}
            {:desc "揮発性有機物" :forbidden ["excavate"]}
            {:desc "農薬残留土"   :forbidden ["thermal"]}
            {:desc "埋立跡地"     :forbidden []}
            {:desc "地下水汚染"   :forbidden ["excavate" "soil-wash"]}]}})

;; --------------------------------------------------------------------------
;; derivation
;; --------------------------------------------------------------------------

(def op->station-key
  "Op → the short station key a board uses. A literal table rather than a derivation:
  `keyword` does not exist under squint (the browser preview compiles through it), and
  every op in the fleet is known anyway, so a table is both portable and explicit about
  what each operation is called on a shop floor.

  An op missing from here has no station, which `spec` reports by refusing to build the
  board rather than by producing one with a hole in it."
  {;; 9601 washing and dry-cleaning
   :garment/intake :intake
   :careplan/verify :verify
   :certification/screen :screen
   :actuation/apply-cleaning-process :clean
   :actuation/return-garment :return
   ;; 9609 pet care
   :client/intake :intake
   :serviceplan/verify :verify
   :background-check/screen :screen
   :actuation/finalize-referral :finalize
   ;; 3811 waste collection
   :pickup/schedule :intake
   :manifest/record :verify
   :dispute/request :flag
   ;; 4520 / 8121 / 8129 / 3700 / 3900 — the log/schedule/supply/flag shape
   :log-service-record :log
   :log-system-record :log
   :log-remediation-record :log
   :schedule-service-operation :schedule
   :schedule-cleaning-operation :schedule
   :schedule-remediation-operation :schedule
   :schedule-maintenance :schedule
   :coordinate-parts-order :supply
   :coordinate-supply-order :supply
   :coordinate-disposal :supply
   :order-supplies :supply
   :flag-safety-concern :flag
   :flag-contamination-concern :flag})

(def base-payout
  "What a committed act at position `i` of `n` pays. Front-loaded like a real deposit at
  drop-off, with the last station settling the balance."
  [5 4 4 7 14])

(def base-upgrade
  "First-upgrade cost by station position."
  [40 60 90 140 110])

(defn spec
  "The board for `district-id`. nil when there is no such district, or no presentation for
  it — a district with real op tables but no labels is not playable yet, and saying so is
  better than inventing Japanese for it."
  [district-id]
  (let [d (world/district district-id)
        p (get presentation district-id)]
    (when (and d p (every? (fn [o] (contains? op->station-key o)) (:ops d)))
      (let [ops (vec (:ops d))
            n (count ops)
            never (set (map (fn [o] (str o)) (:never-auto d)))
            auto3 (set (map (fn [o] (str o)) (:auto-at-3 d)))
            stations (mapv (fn [i]
                             (let [op (nth ops i)]
                               {:key (get op->station-key op)
                                :op op
                                :label (get (:labels p) op (str op))
                                :hard-human? (contains? never (str op))}))
                           (range n))
            keys' (mapv (fn [s] (:key s)) stations)
            auto-keys (set (keep (fn [s] (when (contains? auto3 (str (:op s))) (:key s)))
                                 stations))
            open-at-2 (set (keep (fn [s] (when-not (:hard-human? s) (:key s))) stations))]
        {:id district-id
         :label (:label d)
         :repo (:repo d)
         :stations stations
         :station-keys keys'
         :stage-after (into {} (map (fn [i] [(nth keys' i)
                                             (if (< (inc i) n) (nth keys' (inc i)) :done)])
                                    (range n)))
         ;; phase 0 read-only; 1 opens the first station; 2 opens everything that is not
         ;; the never-auto one; 3 opens all. `:auto` is `:auto-at-3` — never guessed.
         :phase-table {0 {:label "read-only" :writes #{} :auto #{}}
                       1 {:label "assisted-intake" :writes #{(first keys')} :auto #{}}
                       2 {:label "assisted-verify" :writes open-at-2 :auto #{}}
                       3 {:label "supervised-auto" :writes (set keys') :auto auto-keys}}
         :phase-source (if (= district-id "isic-9601")
                         :transcribed
                         :game-staging)
         :subject (:subject p)
         :cert-label (:cert p)
         :conflict-label (:conflict p)
         :method-label (:method p)
         :processes (:processes p)
         :kinds (:kinds p)
         ;; the last station is the one that settles; the one before it is the act
         :evidence-required (mapv (fn [s] (:label s)) stations)
         :payout (into {} (map (fn [i] [(nth keys' i)
                                        (nth base-payout (min i (dec (count base-payout))))])
                               (range n)))
         :upgrade (assoc (into {} (map (fn [i] [(nth keys' i)
                                                (nth base-upgrade (min i (dec (count base-upgrade))))])
                                       (range n)))
                         :approver 220)}))))

(def playable
  "District ids with a board. The rest are on the map with their real op tables and no
  labels yet."
  (vec (filter (fn [id] (some? (spec id))) (map (fn [d] (:id d)) world/districts))))

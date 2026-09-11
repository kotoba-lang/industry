(ns mebuki.model
  "The post-flood recovery system as an OASIS XMILE 1.0 stock-and-flow model.

  The simulator, the equation language and the XML serialisation are all
  `kotoba-lang/org-oasis-open-xmile`; nothing here re-implements them. What
  this namespace owns is the STRUCTURE -- which stocks exist, which flows
  connect them, and which of those flows are limited by which resource --
  and nothing else. Every number arrives from outside as a parameter.

  Read the structure as five production lines that share one workforce and
  one road network:

    A  buildings   inundated -> mud-laden -> drying -> awaiting repair -> restored
                                        \\-> mold-damaged -> demolition queue
    B  entitlement applications -> assessment backlog -> certificates issued
    C  waste       kerbside -> temporary storage site -> processed
    D  households  shelters -> temporary housing -> home
    E  livelihood  businesses closed -> reopened; farmland inundated -> restored

  Three couplings are the point of building this as a simulation rather than
  a checklist, because each one is a delay that a list cannot show:

    1  DRYING IS A RACE, NOT A STEP. A wet building leaves the `drying`
       stock by one of two doors -- dried, or mold-damaged -- and the split
       is set by `dehumidifier_coverage`. Equipment does not make drying
       faster so much as it makes the mold door narrower. Buildings lost
       through that door come back as demolition and as waste tonnage.

    2  PAPER GATES CONCRETE. `certificate_gate` throttles both repair
       funding and publicly-funded demolition. An under-staffed assessment
       line therefore shows up, months later, as idle contractors -- and
       `reassessment` feeds appeals back into the same queue, so the delay
       compounds rather than merely persisting.

    3  WASTE BLOCKS ITS OWN REMOVAL. When the temporary storage site is
       full, hauling stops; waste then accumulates at the kerb, and
       `access_factor` degrades the roads that the volunteers, the trucks
       and the contractors all travel on. This is the loop that turns a
       site-capacity shortfall into a whole-recovery slowdown.

  Parameters are declared, not defaulted. `build` throws on a missing one
  rather than substituting a plausible value: a model that silently invents
  its own inputs produces numbers that look measured and are not."
  (:require [clojure.string :as str]
            [xmile.model :as x]))

(def parameters
  "Every free parameter of the model: name -> {:unit :ja :en}. A jurisdiction
  profile or a labelled scenario must supply all of them (see mebuki.needs)."
  {"total_damaged_buildings"        {:unit "building"          :ja "被害を受けた建物の総数"           :en "Total damaged buildings"}
   "initial_displaced_households"   {:unit "household"         :ja "発災直後の避難世帯数"             :en "Households displaced at t=0"}
   "households_per_building"        {:unit "household/building" :ja "1棟あたり世帯数"                 :en "Households per building"}
   "persons_per_household"          {:unit "person/household"  :ja "1世帯あたり人数"                  :en "Persons per household"}
   "application_window_days"        {:unit "day"               :ja "罹災証明の申請が出そろうまでの日数" :en "Days over which certificate applications arrive"}

   "pump_capacity_bpd"              {:unit "building/day"      :ja "排水能力"                         :en "Dewatering capacity"}
   "dewatering_time_days"           {:unit "day"               :ja "1棟あたり排水日数"                :en "Dewatering time per building"}
   "volunteer_crews"                {:unit "crew"              :ja "稼働できるボランティア班数"        :en "Volunteer crews available"}
   "volunteer_centre_capacity"      {:unit "crew/day"          :ja "災害VCがマッチングできる班数/日"   :en "Crews the volunteer centre can match per day"}
   "buildings_per_crew_day"         {:unit "building/crew/day" :ja "1班1日あたり泥出し棟数"            :en "Buildings mucked out per crew-day"}
   "dehumidifier_units"             {:unit "machine"           :ja "送風機・除湿機の台数"              :en "Blower and dehumidifier units"}
   "ambient_dry_rate"               {:unit "1/day"             :ja "自然乾燥の速度（気候依存）"        :en "Ambient drying rate (climate dependent)"}
   "assisted_dry_rate"              {:unit "1/day"             :ja "機械乾燥の速度"                    :en "Machine-assisted drying rate"}
   "mold_hazard_rate"               {:unit "1/day"             :ja "未乾燥時のカビ発生率"              :en "Mold onset rate while undried"}
   "remediation_rate"               {:unit "1/day"             :ja "カビ被害からの復旧率"              :en "Rate at which mold damage is remediated"}
   "write_off_fraction"             {:unit "1/day"             :ja "カビ被害から解体に回る率"          :en "Rate at which mold damage becomes demolition"}

   "assessor_crews"                 {:unit "crew"              :ja "被害認定調査班数"                  :en "Damage assessment crews"}
   "assessments_per_crew_day"       {:unit "building/crew/day" :ja "1班1日あたり調査棟数"              :en "Assessments per crew-day"}
   "reassessment_fraction"          {:unit "1"                 :ja "再調査に回る割合"                  :en "Fraction sent back for re-assessment"}

   "contractor_capacity_bpd"        {:unit "building/day"      :ja "施工業者の修理能力"                :en "Contractor repair capacity"}
   "demolition_capacity_bpd"        {:unit "building/day"      :ja "解体能力"                          :en "Demolition capacity"}

   "debris_per_cleanout_t"          {:unit "tonne/building"    :ja "1棟の片付けで出る廃棄物量"         :en "Waste per building cleaned out"}
   "debris_per_demolition_t"        {:unit "tonne/building"    :ja "1棟の解体で出る廃棄物量"           :en "Waste per building demolished"}
   "truck_capacity_tpd"             {:unit "tonne/day"         :ja "収集運搬能力"                      :en "Haulage capacity"}
   "temp_site_capacity"             {:unit "tonne"             :ja "仮置場の容量"                      :en "Temporary storage site capacity"}
   "processing_capacity_tpd"        {:unit "tonne/day"         :ja "破砕選別・処分の受入能力"          :en "Processing and disposal capacity"}
   "kerbside_blocking_tonnes"       {:unit "tonne"             :ja "道路が塞がる路上堆積量"            :en "Kerbside tonnage at which roads block"}
   "min_access_factor"              {:unit "1"                 :ja "最悪時でも確保される通行の割合"     :en "Floor on road access even when fully blocked"}

   "temp_housing_order_rate"        {:unit "household/day"     :ja "仮設住宅の発注ペース"              :en "Rate at which temporary housing is ordered"}
   "temp_housing_lead_days"         {:unit "day"               :ja "仮設住宅の引き渡しリードタイム"    :en "Temporary housing lead time"}

   "outreach_crews"                 {:unit "crew"              :ja "保健師等の訪問班数"                :en "Health outreach crews"}
   "households_per_outreach_crew_day" {:unit "household/crew/day" :ja "1班1日あたり訪問世帯数"        :en "Households visited per crew-day"}

   "business_grant_open_day"        {:unit "day"               :ja "事業再建補助が使えるようになる日"  :en "Day the business recovery grant opens"}
   "initial_businesses_closed"      {:unit "business"          :ja "被災により休業した事業所数"        :en "Businesses closed by the flood"}
   "business_repair_capacity_bpd"   {:unit "business/day"      :ja "事業所の復旧能力"                  :en "Business restoration capacity"}
   "initial_farmland_ha"            {:unit "hectare"           :ja "冠水した農地面積"                  :en "Farmland inundated"}
   "farm_crew_capacity_hapd"        {:unit "hectare/day"       :ja "農地復旧能力"                      :en "Farmland restoration capacity"}})

(def required-parameter-names (set (keys parameters)))

(defn missing-parameters
  "The declared parameters `params` does not supply, as a sorted vector.
  Empty means the model can be built; anything else means it cannot, and
  mebuki.needs reports that as :unmeasured rather than guessing."
  [params]
  (vec (sort (remove #(number? (get params %)) required-parameter-names))))

(defn- num-str [v]
  (let [d (double v)
        f #?(:clj (Math/floor d) :cljs (js/Math.floor d))]
    (if (== d f) (str (long d)) (str d))))

(defn- params->auxes [params]
  (for [nm (sort required-parameter-names)]
    (x/aux nm (num-str (get params nm))
           {:xmile/units (:unit (get parameters nm))})))

(def ^:private structure
  "[kind name equation opts] for every non-parameter variable. Kept as data so
  the catalog cross-check and the XMILE emitter read the same source."
  (concat
   ;; ---- A. buildings ------------------------------------------------------
   [[:stock "inundated_buildings"      "total_damaged_buildings" {:xmile/outflows #{"dewatering"}}]
    [:stock "mud_laden_buildings"      "0" {:xmile/inflows #{"dewatering"} :xmile/outflows #{"mud_removal"}}]
    [:stock "drying_buildings"         "0" {:xmile/inflows #{"mud_removal"} :xmile/outflows #{"drying_completion" "mold_onset"}}]
    [:stock "mold_damaged_buildings"   "0" {:xmile/inflows #{"mold_onset"} :xmile/outflows #{"remediation" "write_off"}}]
    [:stock "awaiting_repair_buildings" "0" {:xmile/inflows #{"drying_completion" "remediation"} :xmile/outflows #{"repair_completion"}}]
    [:stock "restored_buildings"       "0" {:xmile/inflows #{"repair_completion"}}]
    [:stock "demolition_queue"         "0" {:xmile/inflows #{"write_off"} :xmile/outflows #{"demolition"}}]
    [:stock "demolished_buildings"     "0" {:xmile/inflows #{"demolition"}}]

    [:aux  "matched_crews"          "MIN(volunteer_crews, volunteer_centre_capacity)" {}]
    [:aux  "muckout_capacity_bpd"   "matched_crews * buildings_per_crew_day * access_factor" {}]
    [:aux  "dehumidifier_coverage"  "MIN(1, dehumidifier_units / MAX(drying_buildings, 1))" {}]
    [:aux  "dry_rate"               "ambient_dry_rate + dehumidifier_coverage * (assisted_dry_rate - ambient_dry_rate)" {}]
    [:aux  "mold_rate"              "mold_hazard_rate * (1 - dehumidifier_coverage)" {}]
    [:aux  "repair_capacity"        "contractor_capacity_bpd * funding_gate" {}]
    [:aux  "demolition_capacity"    "demolition_capacity_bpd * certificate_gate" {}]

    [:flow "dewatering"        "MIN(inundated_buildings / dewatering_time_days, pump_capacity_bpd)" {}]
    [:flow "mud_removal"       "MIN(mud_laden_buildings, muckout_capacity_bpd)" {}]
    [:flow "drying_completion" "drying_buildings * dry_rate" {}]
    [:flow "mold_onset"        "drying_buildings * mold_rate" {}]
    [:flow "remediation"       "mold_damaged_buildings * remediation_rate" {}]
    [:flow "write_off"         "mold_damaged_buildings * write_off_fraction" {}]
    [:flow "repair_completion" "MIN(awaiting_repair_buildings, repair_capacity)" {}]
    [:flow "demolition"        "MIN(demolition_queue, demolition_capacity)" {}]]

   ;; ---- B. entitlement ----------------------------------------------------
   [[:stock "assessment_backlog"  "0" {:xmile/inflows #{"applications" "reassessment"} :xmile/outflows #{"assessment_rate"}}]
    [:stock "certificates_issued" "0" {:xmile/inflows #{"certificate_issue"}}]

    [:aux  "certificate_gate" "MIN(1, certificates_issued / MAX(total_damaged_buildings, 1))" {}]
    [:aux  "funding_gate"     "certificate_gate" {}]

    [:flow "applications"      "IF TIME < application_window_days THEN total_damaged_buildings / application_window_days ELSE 0" {}]
    [:flow "assessment_rate"   "MIN(assessment_backlog, assessor_crews * assessments_per_crew_day)" {}]
    [:flow "reassessment"      "assessment_rate * reassessment_fraction" {}]
    [:flow "certificate_issue" "assessment_rate * (1 - reassessment_fraction)" {}]]

   ;; ---- C. waste ----------------------------------------------------------
   [[:stock "kerbside_debris"  "0" {:xmile/inflows #{"debris_generation"} :xmile/outflows #{"hauling"}}]
    [:stock "temp_site_stock"  "0" {:xmile/inflows #{"hauling"} :xmile/outflows #{"processing"}}]
    [:stock "processed_debris" "0" {:xmile/inflows #{"processing"}}]

    [:aux  "site_headroom" "MAX(0, temp_site_capacity - temp_site_stock)" {}]
    ;; The floor is not a fudge factor, it is the difference between a model and
    ;; a trap. Without it, `hauling` is multiplied by an access term that the
    ;; hauling itself is the only cure for, so a run that once reaches zero
    ;; access can never clear the kerb, never restore access, and never leave --
    ;; an absorbing state, reported downstream as a very bad recovery rather
    ;; than as an unphysical one. In the world, blocked roads are exactly where
    ;; clearance goes first. `min_access_factor` is the fraction of throughput
    ;; that priority keeps alive, and it is a parameter because it is a
    ;; measurable property of a road network and a fleet, not a constant.
    [:aux  "access_factor" "MAX(min_access_factor, 1 - MIN(1, kerbside_debris / MAX(kerbside_blocking_tonnes, 1)))" {}]

    [:flow "debris_generation" "mud_removal * debris_per_cleanout_t + demolition * debris_per_demolition_t" {}]
    ;; Hauling is NOT throttled by access_factor, and that is deliberate.
    ;; Clearing the kerb is the work that restores the road; throttling it by
    ;; the blockage it removes makes the blockage self-sustaining and the model
    ;; unfalsifiable. What actually limits hauling is somewhere to put the load,
    ;; which is why `site_headroom` is the third term -- and why a full
    ;; temporary storage site, not a blocked road, is the true origin of the
    ;; loop this model is trying to show.
    [:flow "hauling"           "MIN(MIN(kerbside_debris, truck_capacity_tpd), site_headroom)" {}]
    [:flow "processing"        "MIN(temp_site_stock, processing_capacity_tpd)" {}]]

   ;; ---- D. households -----------------------------------------------------
   [[:stock "households_in_shelters"         "initial_displaced_households" {:xmile/outflows #{"shelter_to_temp" "return_from_shelter"}}]
    [:stock "households_in_temporary_housing" "0" {:xmile/inflows #{"shelter_to_temp"} :xmile/outflows #{"return_from_temp"}}]
    [:stock "households_returned"            "0" {:xmile/inflows #{"return_from_temp" "return_from_shelter"}}]
    [:stock "displacement_person_days"       "0" {:xmile/inflows #{"displacement_accumulation"}}]
    [:stock "households_not_yet_visited"     "initial_displaced_households" {:xmile/outflows #{"outreach_rate"}}]
    [:stock "temp_housing_pipeline"          "0" {:xmile/inflows #{"temp_housing_ordering"} :xmile/outflows #{"temp_housing_delivery"}}]

    ;; A first-order delay written out as a pipeline stock rather than as
    ;; DELAY1(...). Two reasons, one practical and one about what a model is
    ;; for. Practically, the engine's DELAY1 desugaring gives its hidden stock
    ;; an initial equation that references the delayed input, and initial
    ;; equations cannot see parameters (see structure-vars). But the better
    ;; reason is that ordering temporary housing IS a visible pipeline with a
    ;; visible backlog, and hiding it inside a built-in would hide the one
    ;; thing a reader of this model most wants to see: that units ordered
    ;; today house nobody for months.
    [:aux  "temp_housing_supply" "temp_housing_pipeline / temp_housing_lead_days" {}]
    [:aux  "households_rehoused" "repair_completion * households_per_building" {}]

    [:flow "temp_housing_ordering" "temp_housing_order_rate" {}]
    [:flow "temp_housing_delivery" "temp_housing_supply" {}]
    [:flow "shelter_to_temp"      "MIN(households_in_shelters, temp_housing_supply)" {}]
    [:flow "return_from_temp"     "MIN(households_in_temporary_housing, households_rehoused)" {}]
    [:flow "return_from_shelter"  "MIN(households_in_shelters, MAX(0, households_rehoused - return_from_temp))" {}]
    [:flow "displacement_accumulation" "(households_in_shelters + households_in_temporary_housing) * persons_per_household" {}]
    [:flow "outreach_rate"        "MIN(households_not_yet_visited, outreach_crews * households_per_outreach_crew_day)" {}]]

   ;; ---- E. livelihood -----------------------------------------------------
   [[:stock "businesses_closed"    "initial_businesses_closed" {:xmile/outflows #{"business_reopening"}}]
    [:stock "businesses_reopened"  "0" {:xmile/inflows #{"business_reopening"}}]
    [:stock "farmland_inundated_ha" "initial_farmland_ha" {:xmile/outflows #{"farmland_restoration"}}]
    [:stock "farmland_restored_ha" "0" {:xmile/inflows #{"farmland_restoration"}}]

    [:aux  "business_funding_gate" "STEP(1, business_grant_open_day)" {}]

    [:flow "business_reopening"   "MIN(businesses_closed, business_repair_capacity_bpd * business_funding_gate)" {}]
    [:flow "farmland_restoration" "MIN(farmland_inundated_ha, farm_crew_capacity_hapd)" {}]]))

(defn- structure-vars
  "The non-parameter variables, with one substitution: a stock whose initial
  value is a parameter gets the parameter's LITERAL, not its name.

  Measured 2026-08-23 against org-oasis-open-xmile: `xmile.execute/initial-stocks`
  evaluates each stock's initial equation in an environment containing only the
  other stocks, so a reference to a parameter aux raises `unknown identifier` --
  even though that fn's own docstring says an initial equation may reference a
  constant. Substituting here keeps `structure` readable as
  `total_damaged_buildings` while emitting an equation the engine can evaluate.
  Remove the substitution if the engine's environment is widened to include the
  constant env it already computes."
  [params]
  (for [[kind nm eqn opts] structure]
    (case kind
      :stock (x/stock nm (if (contains? required-parameter-names eqn)
                           (num-str (get params eqn))
                           eqn)
                      opts)
      :flow  (x/flow nm eqn opts)
      :aux   (x/aux nm eqn opts))))

(def default-sim
  "Days. dt is small because several flows are MIN-clipped and therefore not
  smooth; Euler with a small step is honest about that where RK4 would only
  make the discontinuity look differentiable."
  {:start 0.0 :stop 540.0 :dt 0.125 :method :euler})

(defn build
  "The XMILE model for one parameter set. Throws on a missing parameter.
  `opts` may override :start/:stop/:dt/:method and :name."
  ([params] (build params nil))
  ([params opts]
   (let [missing (missing-parameters params)]
     (when (seq missing)
       (throw (ex-info (str "mebuki.model/build: refusing to build with "
                            (count missing) " unset parameter(s): "
                            (str/join ", " missing))
                       {:mebuki/missing-parameters missing}))))
   (let [{:keys [start stop dt method name]} (merge default-sim {:name "flood_recovery"} opts)]
     (reduce x/add-variable
             (x/model name {:xmile/sim-specs (x/sim-specs start stop
                                                          {:xmile/dt dt
                                                           :xmile/method method
                                                           :xmile/time-units "days"})})
             (concat (params->auxes params) (structure-vars params))))))

(defn document
  "A complete XMILE 1.0 document (header + one model) ready for
  `xmile.xml/emit-string`."
  ([params] (document params nil))
  ([params opts]
   {:xmile/header {:xmile/vendor "cloud-itonami"
                   :xmile/product {:xmile/name "mebuki" :xmile/version "1"}
                   :xmile/name (or (:title opts) "Post-flood recovery")}
    :xmile/models [(build params opts)]}))

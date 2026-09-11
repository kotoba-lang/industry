(ns mebuki.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [mebuki.catalog :as cat]
            [mebuki.export :as export]
            [mebuki.model :as model]
            [mebuki.needs :as needs]
            [clojure.string :as str]
            [xmile.execute :as ex]
            [xmile.validate :as v]))

(def scenario (cat/load-scenario :illustrative-medium-town))
(def chiba (cat/load-profile :jpn-chiba))
(def params (:profile/parameters scenario))

(deftest scenario-supplies-every-declared-parameter
  (is (= [] (model/missing-parameters params))
      "the illustrative scenario must be complete, or nothing below tests the model"))

(deftest build-refuses-an-incomplete-parameter-set
  ;; The evidence floor. A model that quietly defaults a missing input returns
  ;; the same shape for 'we measured this' and 'we made this up'.
  (let [incomplete (dissoc params "assessor_crews" "dehumidifier_units")]
    (is (= ["assessor_crews" "dehumidifier_units"] (model/missing-parameters incomplete)))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                          #"refusing to build"
                          (model/build incomplete)))))

(deftest model-is-valid-xmile
  (let [built (model/build params)
        problems (v/validate built)]
    (is (= [] (v/errors problems)) (pr-str (v/errors problems)))
    (testing "every flow a stock names is a real flow, and every flow is attached"
      (let [flows (set (map :xmile/name (filter #(= :flow (:xmile/kind %))
                                                 (vals (:xmile/variables built)))))
            referenced (into #{} (mapcat #(concat (:xmile/inflows % #{}) (:xmile/outflows % #{})))
                             (filter #(= :stock (:xmile/kind %)) (vals (:xmile/variables built))))]
        (is (= [] (vec (sort (remove flows referenced)))) "stock names a flow that does not exist")
        (is (= [] (vec (sort (remove referenced flows)))) "flow attached to no stock")))))

(deftest simulation-conserves-buildings
  ;; Buildings only move between the eight stocks of chain A; none are created
  ;; or destroyed. If this drifts, an inflow or outflow wiring is wrong, and
  ;; every downstream number is wrong with it.
  (let [built (model/build params)
        r (ex/run built)
        chain ["inundated_buildings" "mud_laden_buildings" "drying_buildings"
               "mold_damaged_buildings" "awaiting_repair_buildings"
               "restored_buildings" "demolition_queue" "demolished_buildings"]
        total-at (fn [i] (reduce + (map #(nth (get-in r [:xmile/series %]) i) chain)))
        n (dec (count (:xmile/times r)))
        start (total-at 0)
        end (total-at n)]
    (is (< (abs (- start (get params "total_damaged_buildings"))) 1e-6))
    (is (< (abs (- end start)) (* 1e-3 start))
        (str "buildings not conserved: " start " -> " end))))

(deftest drying-equipment-moves-buildings-off-the-mold-branch
  ;; The first of the three couplings the model exists to show. This is a
  ;; behavioural claim about the structure, and it must be able to fail: if the
  ;; dehumidifier_coverage wiring were cut, both runs would agree.
  (let [few  (needs/estimate (assoc scenario :profile/parameters (assoc params "dehumidifier_units" 10)))
        many (needs/estimate (assoc scenario :profile/parameters (assoc params "dehumidifier_units" 3000)))]
    (is (= :ok (:status few)))
    (is (= :ok (:status many)))
    (is (> (get-in few [:summary :mold-damaged-peak])
           (* 2 (get-in many [:summary :mold-damaged-peak])))
        "starving the drying equipment must push buildings through the mold door")
    (is (> (get-in few [:summary :buildings-written-off])
           (get-in many [:summary :buildings-written-off]))
        "and mold must end in demolition, not merely in a different stock name")))

(deftest the-certificate-line-gates-physical-repair
  ;; The second coupling: paper throughput limits concrete. Starving the
  ;; assessment crews must delay restoration even though no builder was removed.
  (let [slow (needs/estimate (assoc scenario :profile/parameters (assoc params "assessor_crews" 1)))
        fast (needs/estimate (assoc scenario :profile/parameters (assoc params "assessor_crews" 60)))]
    (is (< (get-in fast [:summary :day-90-percent-certificates])
           (get-in slow [:summary :day-90-percent-certificates]))
        "the certificate line itself must be the thing that moved")
    (is (< (+ 100 (get-in fast [:summary :day-half-restored]))
           (get-in slow [:summary :day-half-restored]))
        "and starving it must delay restoration by months, with no builder removed")
    (testing "a line that cannot finish inside the horizon reports nil, not the horizon"
      ;; 'not within 540 days' and 'on day 540' are different answers, and a
      ;; summariser that returns the horizon for both is the failure this
      ;; workspace keeps finding: the unmeasurable rendered as the measured.
      (let [never (needs/estimate (assoc scenario :profile/parameters
                                          (assoc params "assessor_crews" 0.05)))]
        (is (= :ok (:status never)))
        (is (nil? (get-in never [:summary :day-90-percent-certificates])))
        (is (some? (get-in never [:summary :buildings-restored])))))))

(deftest the-certificate-line-sizes-the-waste-line
  ;; This test exists because an earlier version of the model produced a
  ;; spectacular finding -- "processing paperwork faster destroys more houses"
  ;; -- that turned out to be an absorbing state in the access term, not a
  ;; property of recoveries. The bug is fixed (see mebuki.model's note on
  ;; `hauling`) and the finding evaporated with it. What survives is smaller
  ;; and true: authorising demolition faster does not make the waste smaller,
  ;; it makes it arrive sooner and all at once, and the waste system is where
  ;; that lands.
  (let [moldy (assoc params "dehumidifier_units" 0 "mold_hazard_rate" 0.2)
        slow (needs/estimate (assoc scenario :profile/parameters (assoc moldy "assessor_crews" 1)))
        fast (needs/estimate (assoc scenario :profile/parameters (assoc moldy "assessor_crews" 60)))]
    (is (> (get-in fast [:summary :kerbside-peak-tonnes])
           (* 10 (get-in slow [:summary :kerbside-peak-tonnes])))
        "the same demolitions, authorised sooner, must peak far higher on the kerb")
    (is (< (get-in fast [:summary :worst-access-factor])
           (get-in slow [:summary :worst-access-factor]))
        "and that peak must degrade road access")
    (testing "and -- the part worth stating so nobody over-reads the above --"
      ;; Restoration is NOT harmed here, because the muck-out finished before
      ;; the demolition wave arrived. Sizing the certificate line without
      ;; sizing the waste line moves the failure; it does not create one out
      ;; of nothing. Asserting the non-effect keeps the next reader from
      ;; quoting this test as evidence that fast paperwork is dangerous.
      (is (< (abs (- (get-in fast [:summary :buildings-restored])
                     (get-in slow [:summary :buildings-restored])))
             (* 0.02 (get-in slow [:summary :buildings-restored])))))))

(deftest the-model-has-no-absorbing-gridlock
  ;; A structural invariant, added after the model was found to have exactly
  ;; this defect: a state it could enter and never leave, reported downstream
  ;; as a very bad recovery rather than as an unphysical one. Given enough time
  ;; and no change in parameters, the kerb must clear.
  (let [moldy (assoc params "dehumidifier_units" 0 "mold_hazard_rate" 0.2 "assessor_crews" 60)
        e (needs/estimate (assoc scenario :profile/parameters moldy) {:stop 3000.0})
        series (get-in e [:result :xmile/series "kerbside_debris"])
        peak (apply max series)
        final (last series)]
    (is (= :ok (:status e)))
    (is (pos? peak) "the run must actually reach the congested regime it is testing")
    (is (< final (* 0.01 peak))
        (str "kerbside waste must drain given time; peaked at " peak " and ended at " final))))

(deftest waste-blocks-its-own-removal
  ;; The third coupling: a temporary storage site too small stops hauling, the
  ;; waste piles at the kerb, and access -- which the volunteers and the trucks
  ;; both depend on -- degrades.
  (let [roomy (needs/estimate (assoc scenario :profile/parameters (assoc params "temp_site_capacity" 200000)))
        tight (needs/estimate (assoc scenario :profile/parameters (assoc params "temp_site_capacity" 500)))]
    (is (> (get-in tight [:summary :kerbside-peak-tonnes])
           (get-in roomy [:summary :kerbside-peak-tonnes])))
    (is (< (get-in tight [:summary :worst-access-factor])
           (get-in roomy [:summary :worst-access-factor]))
        "a full site must show up as degraded road access, not just as a fuller stock")))

(deftest an-unmeasured-jurisdiction-refuses-to-produce-numbers
  ;; jpn-chiba declares every parameter unmeasured. Asking it for an estimate
  ;; must return the refusal, WITH the list of what is missing -- not an empty
  ;; result, and certainly not a trajectory.
  (let [e (needs/estimate chiba)]
    (is (= :unmeasured (:status e)))
    (is (= :unmeasured (:provenance e)))
    (is (= (count model/required-parameter-names) (count (:missing e))))
    (is (nil? (:summary e)) "no summary may accompany a refusal")))

(deftest every-produced-number-carries-its-provenance
  (let [e (needs/estimate scenario)]
    (is (= :ok (:status e)))
    (is (= :illustrative (:provenance e))
        "a scenario-derived trajectory must never be labelled measured")))

(deftest sensitivity-ranks-levers-without-asserting-a-ranking
  (let [s (needs/sensitivity scenario "dehumidifier_units" [0.1 1.0 10.0])]
    (is (= :ok (:status s)))
    (is (= 3 (count (:points s))))
    (is (apply >= (map :buildings-written-off (:points s)))
        "more drying equipment must not increase write-offs")))

(deftest emits-a-valid-xmile-document
  (let [s (export/xmile-string params)]
    (is (str/starts-with? s "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
    (is (str/includes? s "<xmile"))
    (is (str/includes? s "version=\"1.0\""))
    (is (str/includes? s "certificate_gate"))
    (testing "and it round-trips back through the parser to the same variables"
      (let [back (#?(:clj requiring-resolve :cljs identity) 'xmile.xml/parse-string)
            doc (back s)
            built (model/build params)]
        (is (= (set (keys (:xmile/variables built)))
               (set (keys (:xmile/variables (first (:xmile/models doc)))))))))))

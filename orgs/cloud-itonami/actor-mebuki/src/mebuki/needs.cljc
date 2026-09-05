(ns mebuki.needs
  "The answer layer: given a place and a parameter set, what does the recovery
  need, when, and what is it standing on.

  The whole point of this namespace is the refusal in `estimate`. A recovery
  model is asked for numbers by people who will act on them, and the easiest
  way to produce a number is to assume one. So there are exactly two ways to
  get a trajectory out of mebuki, and both of them are labelled:

    :measured     every parameter came from a jurisdiction profile that cites
                  where it was observed. No such profile exists in this
                  repository yet -- jpn-chiba declares all of its parameters
                  unmeasured on purpose.
    :illustrative every parameter came from a scenario file, which says in its
                  own header that it is not a measurement of anything.

  Anything else returns {:status :unmeasured} with the list of what is
  missing. It does not fall back to a default, because a default here would be
  a fabricated number wearing the shape of a result -- and it would be
  indistinguishable, in the output, from one that had been measured."
  (:require [mebuki.model :as model]
            [xmile.execute :as ex]
            [xmile.validate :as v]))

(defn parameters-of
  "The plain {name -> number} map a profile or scenario supplies. A
  jurisdiction profile that has not been measured supplies none."
  [profile]
  (let [p (:profile/parameters profile)]
    (if (and (map? p) (not (:mebuki/all-unmeasured p)))
      (into {} (filter (comp number? val)) p)
      {})))

(defn provenance
  "How any number produced from `profile` must be labelled."
  [profile]
  (case (:profile/kind profile)
    :scenario     :illustrative
    :jurisdiction (if (= :unmeasured (:profile/measurement-status profile))
                    :unmeasured
                    :measured)
    :unknown))

(defn readiness
  "Can this profile drive a simulation, and if not, what is missing. Callers
  that only want to know whether they have enough to model with should ask
  this rather than catching an exception out of `mebuki.model/build`."
  [profile]
  (let [params (parameters-of profile)
        missing (model/missing-parameters params)]
    {:profile (:profile/id profile)
     :provenance (provenance profile)
     :ready? (empty? missing)
     :supplied (count params)
     :required (count model/required-parameter-names)
     :missing missing}))

(defn- series-at [result nm t]
  (let [times (:xmile/times result)
        idx (->> (map-indexed vector times)
                 (filter (fn [[_ tv]] (>= tv (- t 1e-9))))
                 ffirst)]
    (when idx (nth (get-in result [:xmile/series nm]) idx nil))))

(defn- day-reaching
  "First simulated day on which `nm` reaches `target`, or nil if it never does
  within the horizon. nil means 'not within the horizon', which is a real
  answer and must not be rendered as the horizon."
  [result nm target]
  (->> (map vector (:xmile/times result) (get-in result [:xmile/series nm]))
       (filter (fn [[_ v]] (and (number? v) (>= v target))))
       ffirst))

(defn estimate
  "Run the model for `profile` and summarise it.

  Returns either
    {:status :unmeasured :missing [...] ...}                  -- nothing simulated
  or
    {:status :ok :provenance :illustrative|:measured ...}     -- with :summary
  and never a number without one of those two labels attached."
  ([profile] (estimate profile nil))
  ([profile opts]
   (let [r (readiness profile)]
     (if-not (:ready? r)
       (assoc r :status :unmeasured
              :note "No trajectory produced. Supply the missing parameters from a source, or run a labelled scenario.")
       (let [params (parameters-of profile)
             built (model/build params opts)
             problems (v/validate built)]
         (if (seq (v/errors problems))
           {:status :invalid-model :problems (v/errors problems)}
           (let [result (ex/run built)
                 total (get params "total_damaged_buildings")
                 horizon (:xmile/stop (:xmile/sim-specs built))]
             {:status :ok
              :profile (:profile/id profile)
              :provenance (:provenance r)
              :horizon-days horizon
              :warnings (v/warnings problems)
              :summary
              {:day-90-percent-certificates (day-reaching result "certificates_issued" (* 0.9 total))
               :day-half-restored           (day-reaching result "restored_buildings" (* 0.5 total))
               :day-shelters-empty          (day-reaching result "households_returned" 1e-9)
               :buildings-written-off       (series-at result "demolished_buildings" horizon)
               :buildings-restored          (series-at result "restored_buildings" horizon)
               :mold-damaged-peak           (apply max (get-in result [:xmile/series "mold_damaged_buildings"]))
               :kerbside-peak-tonnes        (apply max (get-in result [:xmile/series "kerbside_debris"]))
               :worst-access-factor         (apply min (get-in result [:xmile/series "access_factor"]))
               :people-displaced-person-days (series-at result "displacement_person_days" horizon)
               :households-still-displaced  (+ (or (series-at result "households_in_shelters" horizon) 0)
                                               (or (series-at result "households_in_temporary_housing" horizon) 0))
               :businesses-reopened         (series-at result "businesses_reopened" horizon)
               :farmland-restored-ha        (series-at result "farmland_restored_ha" horizon)}
              :result result})))))))

(defn sensitivity
  "Re-run with one parameter multiplied by each of `factors`, and report the
  chosen outcome. This is how the catalog's resources get ranked by leverage
  without anyone asserting a ranking: move the procurement decision, measure
  what the system does.

  Only meaningful for a profile that is already :ready?; returns the same
  :unmeasured shape otherwise."
  ([profile param factors] (sensitivity profile param factors :buildings-written-off))
  ([profile param factors outcome]
   (let [r (readiness profile)]
     (if-not (:ready? r)
       (assoc r :status :unmeasured)
       (let [base (parameters-of profile)]
         {:status :ok
          :provenance (:provenance r)
          :parameter param
          :outcome outcome
          :points (vec (for [f factors]
                         (let [p (update base param * f)
                               e (estimate (assoc profile :profile/parameters p))]
                           {:factor f
                            :value (get p param)
                            outcome (get-in e [:summary outcome])})))})))))

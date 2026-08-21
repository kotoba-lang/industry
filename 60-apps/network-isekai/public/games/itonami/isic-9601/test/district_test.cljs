(ns district-test
  "Every district on the street is playable, and every one keeps its own operation
  un-automatable.

  The single-shop game could only assert that boundary; running eight boards through the
  same reducer is what turns it into a demonstration. If a district's spec were derived
  wrongly — its never-auto op mapped to the wrong station, its phase-3 `:auto` widened —
  the run below would still LOOK fine. These assertions are the part that would not."
  (:require [itonami.isic-9601.logic :as l]
            [itonami.isic-9601.district :as d]
            [itonami.isic-9601.world :as world]))

(def failures (atom 0))
(def checks (atom 0))
(defn is! [ok? label]
  (swap! checks inc)
  (when-not ok? (swap! failures inc) (println "  FAIL:" label)))

(println "== every district has a board ==")
(is! (= 8 (count d/playable)) (str "8 playable districts, got " (count d/playable)))
(doseq [id d/playable]
  (let [s (d/spec id)]
    (is! (seq (:stations s)) (str id " has stations"))
    (is! (every? (fn [st] (string? (:label st))) (:stations s)) (str id " labels every station"))
    (is! (string? (:subject s)) (str id " says what it cleans"))))

(println "== the never-auto op is the hard-human station, in every district ==")
(doseq [id d/playable]
  (let [s (d/spec id)
        wd (world/district id)
        hard (set (map (fn [st] (str (:op st))) (filter (fn [st] (:hard-human? st)) (:stations s))))
        never (set (map str (:never-auto wd)))]
    (is! (= hard never)
         (str id " hard-human stations " (pr-str hard) " must equal :never-auto " (pr-str never)))))

(println "== no district automates its never-auto op, at any phase ==")
(doseq [id d/playable]
  (let [s (d/spec id)
        hard-keys (set (map (fn [st] (:key st)) (filter (fn [st] (:hard-human? st)) (:stations s))))]
    (doseq [p (range 0 (inc l/max-phase))]
      (doseq [k hard-keys]
        (is! (not (contains? (l/auto-ops s p) k))
             (str id " phase " p " must not auto-commit " k))))))

(println "== a hard-human station escalates even when nothing is wrong ==")
(doseq [id d/playable]
  (let [s (d/spec id)
        ks (:station-keys s)
        n (count ks)
        hard (first (filter (fn [st] (:hard-human? st)) (:stations s)))
        st (assoc (l/init 1 id) :phase 3)
        g {:id "g" :desc "x" :forbidden [] :proposed-process "none"
           :confidence 0.99 :cited? true :evidence (dec n)
           :stage (:key hard) :work 99
           :cleaning-applied? false :garment-returned? false}]
    (is! (= :escalate (l/disposition st (:key hard) g))
         (str id " " (:key hard) " escalates at phase 3 with a clean job"))))

(println "== each district plays, and a careful run wins ==")
(defn- risky? [sm k]
  (let [stn (first (filter (fn [x] (= (:key x) k)) (:stations sm)))
        g (first (filter (fn [g] (:ready? g)) (:garments stn)))]
    (boolean (and g (or (:risk g) (:label-conflict? g) (not (:cited? g)))))))

(doseq [id d/playable]
  (let [spec (d/spec id)
        ks (:station-keys spec)
        second-k (second ks)
        final (reduce
               (fn [st _]
                 (let [st (-> st (l/reduce-event [:tick]) (l/reduce-event [:take-in]))
                       sm (l/summary st)
                       st (l/reduce-event st (if (risky? sm second-k)
                                               [:reject second-k] [:tap second-k]))
                       st (reduce (fn [a k] (l/reduce-event a [:tap k])) st (drop 2 ks))]
                   (-> st (l/reduce-event [:renew]) (l/reduce-event [:phase]))))
               (l/init 3 id) (range 1500))
        sm (l/summary final)]
    (println (str "   " id " " (:district-label sm)
                  "  t=" (:t sm) " phase=" (:phase sm) " ¥" (:cash sm)
                  " 完了 " (:returned sm) "/" (:target sm)
                  " 信頼 " (:lives sm) " " (name (:flow sm))))
    (is! (pos? (:returned sm)) (str id " completed at least one job"))
    (is! (= :victory (:flow sm)) (str id " a careful run closes the audit"))
    (is! (= 3 (:lives sm)) (str id " a careful run loses no trust"))))

(println "== an unknown district falls back rather than opening an empty shop ==")
(let [st (l/init 1 "isic-0000")]
  (is! (= "isic-9601" (:district st)) "unknown district falls back to the laundry")
  (is! (seq (:stations (l/summary st))) "and the fallback board has stations"))

(println "== payout and upgrade follow station role, not chain position ==")
;; Positional tables of length 5 used to hand a 4-station settle the act seat's
;; numbers (payout 7 / upgrade 140). Settle must keep 14 / 110 in every district.
(let [laundry (d/spec "isic-9601")]
  (is! (= (:payout laundry) l/station-payout)
       "laundry payout still matches the reference map")
  (is! (= (:upgrade laundry) l/upgrade-base)
       "laundry upgrade still matches the reference map"))
(doseq [id d/playable]
  (let [s (d/spec id)
        settle (l/settle-station s)
        act (l/act-station s)
        verify (l/verify-station s)
        entry (l/first-station s)]
    (is! (= (get (:payout s) settle) (get d/role-payout :settle))
         (str id " settle " settle " pays " (get d/role-payout :settle)
              ", got " (get (:payout s) settle)))
    (is! (= (get (:upgrade s) settle) (get d/role-upgrade :settle))
         (str id " settle " settle " upgrades for " (get d/role-upgrade :settle)
              ", got " (get (:upgrade s) settle)))
    (is! (= (get (:payout s) act) (get d/role-payout :act))
         (str id " act " act " pays " (get d/role-payout :act)
              ", got " (get (:payout s) act)))
    (is! (= (get (:upgrade s) act) (get d/role-upgrade :act))
         (str id " act " act " upgrades for " (get d/role-upgrade :act)
              ", got " (get (:upgrade s) act)))
    (when (not= (str verify) (str act))
      (is! (= (get (:payout s) verify) (get d/role-payout :verify))
           (str id " verify " verify " pays " (get d/role-payout :verify))))
    (is! (= (get (:payout s) entry) (get d/role-payout :entry))
         (str id " entry " entry " pays " (get d/role-payout :entry)))))

(println)
(println (str "checks: " @checks "  failures: " @failures))
(when (pos? @failures) (js/process.exit 1))
(println "ALL PASS")

(ns logic-test
  "nbb test suite for the ISIC 9601 idle tycoon.

  Run: npx nbb --classpath 60-apps/network-isekai/public/games/itonami/isic-9601 60-apps/network-isekai/public/games/itonami/isic-9601/test/logic_test.cljs

  The first three tests are the ones that matter: they are the game's copy of
  `cloud-itonami-isic-9601`'s own invariants, and they fail if a future edit
  quietly makes the shop automatable in a way the real actor is not."
  (:require [itonami.isic-9601.logic :as l]))

(def failures (atom 0))
(def checks (atom 0))

(defn is! [ok? label]
  (swap! checks inc)
  (when-not ok?
    (swap! failures inc)
    (println "  FAIL:" label)))

(defn testing! [label f]
  (println "==" label)
  (f))

;; --------------------------------------------------------------------------

(testing! "actuation-never-auto-at-any-phase"
  (fn []
    ;; `laundry.phase`: :actuation/apply-cleaning-process and
    ;; :actuation/return-garment are absent from EVERY phase's :auto set.
    (doseq [p (range 0 (inc l/max-phase))]
      (is! (not (contains? (l/auto-ops p) :clean))
           (str "phase " p " must not auto-commit :clean"))
      (is! (not (contains? (l/auto-ops p) :return))
           (str "phase " p " must not auto-commit :return")))
    ;; and the only auto op that ever exists is :intake, at phase 3
    (is! (= (l/auto-ops 3) #{:intake}) "phase 3 :auto is exactly #{:intake}")
    (is! (= (l/auto-ops 0) #{}) "phase 0 automates nothing")))

(testing! "clean-and-return-escalate-even-when-perfectly-clean"
  (fn []
    ;; a garment with full evidence, a cited spec-basis, high confidence, a
    ;; permitted process and a current certification still may not commit
    ;; without a human -- the governor's high-stakes gate.
    (let [g {:id "g" :desc "綿のシャツ" :forbidden [] :proposed-process "dry-clean"
             :confidence 0.99 :cited? true :evidence 4 :stage :clean :work 9
             :cleaning-applied? false :garment-returned? false :held nil}
          st (assoc (l/init 1) :phase 3 :garments [g])]
      (is! (= (l/disposition st :clean g) :escalate) ":clean escalates at phase 3")
      (is! (= (l/disposition st :return (assoc g :stage :return)) :escalate)
           ":return escalates at phase 3")
      ;; a tick with no human present must NOT move it
      (let [after (l/tick st)]
        (is! (= (:stage (first (:garments after))) :clean)
             "tick alone never advances :clean"))
      ;; the player's tap does
      (let [after (l/tap st :clean)]
        (is! (= (:stage (first (:garments after))) :return)
             "tap advances :clean -> :return")))))

(testing! "hired-approver-never-clears-actuation"
  (fn []
    ;; buying every level of :approver clears :verify/:screen escalations but
    ;; leaves :clean/:return exactly where they were.
    (let [g {:id "g" :desc "綿のシャツ" :forbidden [] :proposed-process "dry-clean"
             :confidence 0.99 :cited? true :evidence 4 :stage :clean :work 9
             :cleaning-applied? false :garment-returned? false :held nil}
          st (-> (l/init 1) (assoc :phase 3 :cash 999999 :garments [g])
                 (l/buy :approver) (l/buy :approver) (l/buy :approver))]
      (is! (>= (get-in st [:levels :approver]) 3) "approvers were hired")
      (let [after (reduce (fn [s _] (l/tick s)) st (range 60))]
        (is! (some (fn [x] (= (:id x) "g")) (:garments after))
             "the garment is still on the floor after 60 ticks")
        (is! (= (:stage (first (filter (fn [x] (= (:id x) "g")) (:garments after)))) :clean)
             "approvers never advance :clean")))))

;; --------------------------------------------------------------------------
;; the six HARD checks
;; --------------------------------------------------------------------------

(def base-garment
  {:id "g" :desc "ウールのジャケット" :forbidden ["bleach"] :proposed-process "dry-clean"
   :confidence 0.9 :cited? true :evidence 4 :stage :clean :work 9
   :cleaning-applied? false :garment-returned? false :held nil})

(testing! "governor-hard-checks"
  (fn []
    (let [st (assoc (l/init 1) :phase 3)]
      (is! (= (:rule (l/hard-violation st :verify (assoc base-garment :cited? false :stage :verify)))
              :no-spec-basis)
           "check 1 no-spec-basis")
      (is! (= (:rule (l/hard-violation st :clean (assoc base-garment :evidence 2)))
              :evidence-incomplete)
           "check 2 evidence-incomplete")
      (is! (= (:rule (l/hard-violation st :clean (assoc base-garment :proposed-process "bleach")))
              :cleaning-process-forbidden-by-care-label)
           "check 3 care-label forbids the proposed process")
      (is! (= (:rule (l/hard-violation (assoc st :cert-current? false) :clean base-garment))
              :certification-not-current)
           "check 4 certification-not-current")
      (is! (= (:rule (l/hard-violation st :clean (assoc base-garment :cleaning-applied? true)))
              :already-cleaned)
           "check 5 already-cleaned")
      (is! (= (:rule (l/hard-violation st :return (assoc base-garment :stage :return :garment-returned? true)))
              :already-returned)
           "check 6 already-returned")
      (is! (nil? (l/hard-violation st :clean base-garment))
           "a clean garment trips nothing"))))

(testing! "hard-holds-cannot-be-approved-away"
  (fn []
    ;; the player tapping a garment whose care label forbids its process must
    ;; NOT clean it -- it docks a life and freezes the garment instead.
    (let [g (assoc base-garment :proposed-process "bleach")
          st (assoc (l/init 1) :phase 3 :garments [g])
          after (l/tap st :clean)]
      (is! (= (:lives after) 2) "a hard hold docks a life")
      (is! (empty? (:garments after)) "the garment leaves the shop unprocessed")
      (is! (= (:returned after) 0) "nothing was returned")
      (is! (= (:basis (last (:ledger after))) :cleaning-process-forbidden-by-care-label)
           "the ledger names the rule")
      (is! (= (:disposition (last (:ledger after))) :hold) "the ledger records a HOLD"))))

(testing! "forbidden-by-care-label-is-ground-truth"
  (fn []
    ;; `laundry.registry/cleaning-process-forbidden-by-care-label?` -- pure
    ;; set membership against the garment's own recorded fields.
    (is! (true? (l/forbidden-by-care-label? {:proposed-process "bleach" :forbidden ["bleach" "tumble-dry"]})) "hit")
    (is! (false? (l/forbidden-by-care-label? {:proposed-process "press" :forbidden ["bleach"]})) "miss")
    (is! (false? (l/forbidden-by-care-label? {:proposed-process "press" :forbidden []})) "empty label")))

(testing! "lapsed-certification-holds-every-station"
  (fn []
    (let [st (assoc (l/init 1) :phase 3 :cert-current? false)]
      (doseq [k l/station-keys]
        (is! (= (l/disposition st k (assoc base-garment :stage k)) :hold)
             (str "lapsed certification holds " (name k)))))))

;; --------------------------------------------------------------------------
;; economy / progression
;; --------------------------------------------------------------------------

(testing! "phase-0-writes-nothing"
  (fn []
    (let [st (assoc (l/init 1) :phase 0)]
      (doseq [k l/station-keys]
        (is! (= (l/disposition st k (assoc base-garment :stage k)) :hold)
             (str "phase 0 holds " (name k)))))))

(testing! "phase-gate-requires-throughput-and-cash"
  (fn []
    (let [st (assoc (l/init 1) :phase 1 :cash 10 :commits 99)]
      (is! (= (:phase (l/advance-phase st)) 1) "no cash, no tier"))
    (let [st (assoc (l/init 1) :phase 1 :cash 5000 :commits 0)]
      (is! (= (:phase (l/advance-phase st)) 1) "no throughput, no tier"))
    (let [st (assoc (l/init 1) :phase 1 :cash 5000 :commits 99)]
      (is! (= (:phase (l/advance-phase st)) 2) "cash + throughput advances the tier"))
    (let [st (assoc (l/init 1) :phase 3 :cash 99999 :commits 9999)]
      (is! (= (:phase (l/advance-phase st)) 3) "phase 3 is the top of the ladder"))))

(testing! "upgrade-costs-rise"
  (fn []
    (let [st (l/init 1)
          c1 (l/upgrade-cost st :intake)
          st2 (l/buy (assoc st :cash 99999) :intake)
          c2 (l/upgrade-cost st2 :intake)]
      (is! (> c2 c1) "the second level costs more than the first")
      (is! (= (:cash (l/buy (l/init 1) :intake)) 0) "you cannot buy what you cannot afford"))))

(testing! "run-is-deterministic-from-seed"
  (fn []
    ;; run far enough that the seed-dependent part of the shop -- which
    ;; garments arrive, and whether their care labels forbid the process the
    ;; advisor proposed -- has actually had a chance to matter. Below phase 3
    ;; nothing depends on a garment's contents, so a short run is identical
    ;; across seeds for reasons that have nothing to do with the RNG.
    (let [play (fn [seed]
                 (reduce (fn [s i]
                           (let [s (l/tick s)
                                 s (l/take-in s true)
                                 s (l/tap s :verify)
                                 s (l/tap s :screen)
                                 s (l/tap s :clean)
                                 s (l/tap s :return)
                                 s (if (zero? (mod i 25)) (l/advance-phase s) s)
                                 s (if (not (:cert-current? s)) (l/renew-certification s) s)]
                             s))
                         (l/init seed) (range 900)))
          a (play 7) b (play 7) c (play 1234)]
      (is! (= (:cash a) (:cash b)) "same seed, same cash")
      (is! (= (:returned a) (:returned b)) "same seed, same throughput")
      (is! (= (:t a) (:t b)) "same seed, run ends at the same tick")
      (is! (= (:ledger a) (:ledger b)) "same seed, same audit ledger")
      (is! (not= [(:cash a) (:returned a) (:t a)] [(:cash c) (:returned c) (:t c)])
           "a different seed plays differently"))))

(testing! "a-played-run-actually-earns-and-terminates"
  (fn []
    ;; drive the shop the way a player would: tap the two actuation stations
    ;; every few ticks, buy what you can afford.
    (let [risky? (fn [s k]
                   (some (fn [g] (and (:ready? g) (or (:risk g) (:label-conflict? g) (not (:cited? g)))))
                         (:garments (first (filter (fn [x] (= (:key x) k))
                                                   (:stations (l/summary s)))))))
          final (reduce (fn [s i]
                          (let [s (l/tick s)
                                s (l/take-in s true)
                                ;; read the care label before approving the plan
                                s (if (risky? s :verify) (l/reject s :verify) (l/tap s :verify))
                                s (l/tap s :screen)
                                s (l/tap s :clean)
                                s (l/tap s :return)
                                s (if (zero? (mod i 50)) (l/advance-phase s) s)
                                s (if (not (:cert-current? s)) (l/renew-certification s) s)]
                            s))
                        (l/init 3) (range 1500))]
      (println "   final:" (pr-str (select-keys (l/summary final) [:t :phase :cash :lives :returned :flow])))
      (is! (> (:returned final) 0) "garments were returned")
      (is! (= (:phase final) l/max-phase) "the tier ladder was climbed")
      (is! (= (:flow final) :victory) "reading the care label wins the run")
      (is! (= (:lives final) 3) "a careful run loses no customer trust"))))

(testing! "careless-play-loses-to-the-care-label"
  (fn []
    ;; the same script, except the plan is approved without reading the care
    ;; label. `laundry.registry/cleaning-process-forbidden-by-care-label?` is
    ;; ground truth, so the governor catches every one of these at :clean --
    ;; the shop is never allowed to ruin a garment, but it does lose the run.
    (let [final (reduce (fn [s i]
                          (let [s (l/tick s)
                                s (l/take-in s true)
                                s (l/tap s :verify)     ; approved blind
                                s (l/tap s :screen)
                                s (l/tap s :clean)
                                s (l/tap s :return)
                                s (if (zero? (mod i 25)) (l/advance-phase s) s)
                                s (if (not (:cert-current? s)) (l/renew-certification s) s)]
                            s))
                        (l/init 3) (range 1500))
          holds (filter (fn [e] (= (:basis e) :cleaning-process-forbidden-by-care-label))
                        (:ledger final))]
      (println "   careless:" (pr-str (select-keys (l/summary final) [:t :lives :returned :flow])))
      (is! (= (:flow final) :gameover) "approving blind loses the run")
      (is! (pos? (count holds)) "the ledger shows care-label holds")
      ;; and the crucial one: not a single forbidden process was ever applied
      (is! (zero? (count (filter (fn [e] (and (= (:disposition e) :commit)
                                              (= (:op e) :actuation/apply-cleaning-process)
                                              (:forbidden e)))
                                 (:ledger final))))
           "no forbidden process was ever applied, even while losing"))))

(testing! "summary-is-flat-and-complete"
  (fn []
    (let [s (l/summary (l/init 1))]
      (is! (= (count (:stations s)) 5) "five stations")
      (is! (= (mapv :label (:stations s)) ["受付" "取扱方法" "資格照合" "洗浄" "返却"])
           "stations are in operating order")
      (is! (every? (fn [x] (contains? x :hard-human?)) (:stations s))
           "every station declares whether a human is mandatory"))))

;; --------------------------------------------------------------------------

(println)
(println (str "checks: " @checks "  failures: " @failures))
(when (pos? @failures)
  (js/process.exit 1))
(println "ALL PASS")

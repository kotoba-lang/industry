(ns state-test
  "Acceptance for ADR-2608108000 / #1749 — game state as an EDN envelope.

  Run: npx nbb --classpath src test/state_test.cljs

  Pins:
    1. dump → load → dump is byte-identical
    2. replaying {seed, district, script} equals the dump's :shop
    3. unknown :version errors loudly
    4. a victory dump advances :world :cleared (render --state unlock signal)
    5. soft-lock surfaces as :flow :stalled (#1761 minimum)"
  (:require [clojure.string :as str]
            [itonami.isic-9601.logic :as l]
            [itonami.isic-9601.world :as world]
            [itonami.isic-9601.state :as state]
            [itonami.isic-9601.district :as district]))

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

(defn- apply-script [st district-id script]
  (let [spec (district/spec district-id)
        ks (:station-keys spec)
        nm (fn [k] (let [t (str k)] (if (= ":" (subs t 0 1)) (subs t 1) t)))
        vocab (merge (into {} (mapcat (fn [k] [[(nm k) [:tap k]]
                                               [(str "buy-" (nm k)) [:buy k]]])
                                      ks))
                     {"tick" [:tick] "intake" [:take-in]
                      "reject" [:reject (l/verify-station spec)]
                      "renew" [:renew] "phase" [:phase]
                      "buy-approver" [:buy :approver]})
        expand (fn [token]
                 (let [[c n] (str/split token #"\*")]
                   (repeat (if n (js/parseInt n 10) 1) c)))]
    (reduce (fn [s token]
              (l/reduce-event s (get vocab token)))
            st
            (mapcat expand (str/split (str/trim script) #"\s+")))))

;; --------------------------------------------------------------------------

(testing! "envelope-shape-matches-ADR"
  (fn []
    (let [shop (l/init 20260808 "isic-9601")
          env (state/wrap shop (world/init) 20260808)]
      (is! (= :itonami-game/state (:kind env)) ":kind is :itonami-game/state")
      (is! (= 1 (:version env)) ":version is 1")
      (is! (= "isic-9601" (:district env)) ":district")
      (is! (= 20260808 (:seed env)) "top-level :seed is the starting seed")
      (is! (map? (:world env)) ":world present")
      (is! (map? (:shop env)) ":shop present")
      (is! (= 20260808 (:seed (:shop env))) "fresh shop still holds the start seed"))))

(testing! "dump-load-dump-identical"
  (fn []
    (let [seed 20260808
          district "isic-9601"
          script "tick*5 intake tick*3"
          shop (apply-script (l/init seed district) district script)
          world (-> (world/init) (assoc :in district))
          env (state/wrap shop world seed district)
          text1 (state/encode env)
          env2 (state/parse text1)
          text2 (state/encode env2)]
      (is! (= text1 text2) "dump→load→dump is byte-identical")
      (is! (= (:shop env) (:shop env2)) ":shop round-trips by value")
      (is! (= (:world env) (:world env2)) ":world round-trips by value"))))

(testing! "replay-seed-district-script-equals-dump"
  (fn []
    (let [seed 42
          district "isic-9601"
          script "tick*20 intake renew phase"
          shop (apply-script (l/init seed district) district script)
          env (state/wrap shop (assoc (world/init) :in district) seed district)
          ;; second independent replay — the dump must equal this, not merely itself
          shop2 (apply-script (l/init seed district) district script)]
      (is! (= shop shop2) "two replays of the same input column agree")
      (is! (= shop (:shop env)) "dump :shop equals replay of {seed,district,script}")
      (is! (= seed (:seed env)) "envelope :seed stays the starting seed")
      (is! (not= seed (:seed shop)) "shop LCG cursor has advanced (seed is not identity)"))))

(testing! "unknown-version-errors-loudly"
  (fn []
    (let [bad {:kind :itonami-game/state :version 99
               :district "isic-9601" :seed 1
               :world (world/init) :shop nil}
          threw (atom nil)]
      (try
        (state/validate! bad)
        (catch :default e (reset! threw e)))
      (is! (some? @threw) "validate! throws on :version 99")
      (is! (= :itonami-game/state-version-unsupported
              (:error (ex-data @threw)))
           "error key is :itonami-game/state-version-unsupported")
      (is! (re-find #"unsupported|version" (str (.-message @threw)))
           "message names the version problem"))
    (let [wrong-kind {:kind :other/state :version 1
                      :district "isic-9601" :seed 1
                      :world (world/init) :shop nil}
          threw (atom nil)]
      (try
        (state/validate! wrong-kind)
        (catch :default e (reset! threw e)))
      (is! (some? @threw) "validate! throws on wrong :kind"))))

(testing! "victory-dump-advances-world-cleared"
  (fn []
    ;; Synthesise a closed audit rather than playing 40 returns — the unlock
    ;; signal for render --state is :world :cleared, not the play length.
    (let [shop (assoc (l/init 1 "isic-9601") :flow :victory :returned 40)
          world0 (assoc (world/init) :in "isic-9601")
          world (world/clear-district world0 "isic-9601" 40)
          env (state/wrap shop world 1 "isic-9601")
          status (world/status (:world env))]
      (is! (= 1 (:cleared (:world env))) "cleared advances on victory")
      (is! (= 2 (count (filter :unlocked? (:districts status))))
           "second district unlocks — what render --state must show"))))

(testing! "stalled-flow-when-cert-lapsed-and-broke"
  (fn []
    (let [st (assoc (l/init 1) :cert-current? false :cert-ticks 0 :cash 40)
          sm (l/summary st)
          after-tick (l/tick (assoc (l/init 1)
                                    :cert-current? false :cert-ticks 1 :cash 10))]
      (is! (= :stalled (:flow sm)) "summary names :stalled while still :playing internally")
      (is! (l/stalled? st) "stalled? predicate")
      (is! (= :stalled (:flow after-tick)) "tick persists :flow :stalled"))))

;; --------------------------------------------------------------------------

(println)
(println (str "  " @checks " checks, " @failures " failures"))
(js/process.exit (if (pos? @failures) 1 0))

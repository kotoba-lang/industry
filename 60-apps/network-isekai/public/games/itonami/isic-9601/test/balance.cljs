(ns balance
  "Balance probe -- plays the shop the way a competent player would and reports
  what actually killed the run. Not a test; a tuning instrument.

  Run: npx nbb --classpath 60-apps/network-isekai/public/games/itonami/isic-9601/src 60-apps/network-isekai/public/games/itonami/isic-9601/test/balance.cljs"
  (:require [itonami.isic-9601.logic :as l]))

(defn- station-of [s k]
  (first (filter (fn [x] (= (:key x) k)) (:stations (l/summary s)))))

(defn- risky? [s k]
  (let [g (first (filter (fn [g] (:ready? g)) (:garments (station-of s k))))]
    ;; the garment `tap`/`reject` will actually act on — asking whether ANY ready
    ;; garment is risky rejects the wrong one when two are waiting
    (boolean (and g (or (:risk g) (:label-conflict? g) (not (:cited? g)))))))

(defn play [seed ticks]
  (reduce
   (fn [s i]
     (let [s (l/tick s)
           s (l/take-in s true)
           s (if (risky? s :verify) (l/reject s :verify) (l/tap s :verify))
           s (l/tap s :screen)
           s (l/tap s :clean)
           s (l/tap s :return)
           s (if (not (:cert-current? s)) (l/renew-certification s) s)
           s (if (zero? (mod i 25)) (l/advance-phase s) s)
           s (if (zero? (mod i 30)) (l/buy s :intake) s)
           s (if (zero? (mod i 37)) (l/buy s :clean) s)
           s (if (zero? (mod i 41)) (l/buy s :return) s)
           s (if (zero? (mod i 53)) (l/buy s :approver) s)]
       s))
   (l/init seed) (range ticks)))

(doseq [seed [3 7 11 99]]
  (let [f (play seed 4000)
        sm (l/summary f)
        holds (frequencies (map :basis (filter (fn [e] (= (:disposition e) :hold)) (:ledger f))))]
    (println (str "seed " seed
                  " | t=" (:t sm) " phase=" (:phase sm) " cash=" (:cash sm)
                  " lives=" (:lives sm) " returned=" (:returned sm) "/" (:target sm)
                  " flow=" (name (:flow sm))))
    (println (str "   levels " (pr-str (:levels sm))))
    (println (str "   recent holds " (pr-str holds)))))

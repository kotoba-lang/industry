(ns tasuke-app.state
  "re-frame db, events and subscriptions.

  ## Nothing here is persisted, and that is a decision

  What a victim types into this page is the 要配慮 material tasuke's charter (G6)
  says must never sit in the clear. `localStorage` is the clear — readable by any
  script on the origin, surviving the browser close, on a machine that may be
  shared or already compromised, which for an account takeover is the LIKELY case.
  So this app keeps everything in memory and says so on the page. Closing the tab
  is the erase.

  Evidence is the same rule taken one step further: the file's bytes are hashed in
  the browser and then dropped. What the db holds is a label, a kind and a sha256
  — never the content."
  (:require [re-frame.core :as rf]
            [tasuke-app.oracle :as oracle]
            [tasuke-app.route :as route]))

(def initial
  {:route     route/default-view
   :narrative ""
   :explicit  ""
   :loss      ""
   :ongoing?  false
   :service   ""
   :account   ""
   :occurred  ""
   :evidence  []
   :done      #{}})

(rf/reg-event-db :app/init (fn [_ _] initial))
(rf/reg-event-db :route/set (fn [db [_ id]] (assoc db :route id)))

(rf/reg-event-db
 :field/set
 (fn [db [_ k v]] (assoc db k v)))

(rf/reg-event-db
 :action/toggle
 (fn [db [_ step]]
   (update db :done #(if (contains? % step) (disj % step) (conj % step)))))

(rf/reg-event-db
 :evidence/add
 (fn [db [_ item]] (update db :evidence conj item)))

(rf/reg-event-db
 :evidence/drop
 (fn [db [_ i]] (update db :evidence #(vec (concat (subvec % 0 i) (subvec % (inc i)))))))

;; --- derived: everything below is the guest's answer, not this app's ---------

(defn- parse-yen
  "「48万」「480,000円」「なし」→ yen. Kept on the host because it is text
  normalization, not a decision — the same boundary the guest draws for case
  folding it does NOT draw here."
  [s]
  (let [t (-> (str s) (clojure.string/replace "," "") (clojure.string/replace "円" "")
              (clojure.string/trim))]
    (cond
      (clojure.string/blank? t) 0
      (contains? #{"なし" "無し" "ない"} t) 0
      :else (if-let [m (re-matches #"(\d+(?:\.\d+)?)万(\d+)?" t)]
              (long (+ (* (js/parseFloat (nth m 1)) 10000)
                       (if (nth m 2) (js/parseInt (nth m 2)) 0)))
              (let [n (js/parseInt (clojure.string/replace t #"[^0-9]" ""))]
                (if (js/isNaN n) 0 n))))))

(rf/reg-sub :route (fn [db _] (:route db)))
(rf/reg-sub :field (fn [db [_ k]] (get db k)))
(rf/reg-sub :evidence (fn [db _] (:evidence db)))
(rf/reg-sub :done (fn [db _] (:done db)))
(rf/reg-sub :loss-jpy (fn [db _] (parse-yen (:loss db))))

(rf/reg-sub
 :triage
 (fn [db _]
   (oracle/triage {:narrative (:narrative db)
                   :explicit  (:explicit db)
                   :loss-jpy  (parse-yen (:loss db))
                   :ongoing?  (:ongoing? db)})))

(rf/reg-sub
 :recovery-plan
 (fn [db _] (oracle/recovery-plan (:service db))))

(rf/reg-sub
 :platform-request
 (fn [db _]
   (oracle/platform-request
    {:platform  (:service db)
     :account-id (:account db)
     :occurred  (:occurred db)
     :kind      (oracle/classify (:narrative db) (:explicit db))})))

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
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [tasuke-app.input :as input]
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
   :subject   ""
   :station   ""
   :bank      ""
   :counterparty ""
   :timeline  ""
   :discovery ""
   :current   ""
   :evidence  []
   :done      {}})

(rf/reg-event-db :app/init (fn [_ _] initial))
(rf/reg-event-db :route/set (fn [db [_ id]] (assoc db :route id)))

(rf/reg-event-db
 :field/set
 (fn [db [_ k v]] (assoc db k v)))

(rf/reg-event-db
 :action/toggle
 ;; Keyed BY KIND. The checklist is positional, and the positions mean different
 ;; steps for different kinds — a set of bare indices carried across a
 ;; reclassification renders a different checklist with unperformed steps struck
 ;; through, which is the one thing a first-response list must never do.
 (fn [db [_ kind step]]
   (update-in db [:done kind]
              #(let [s (or % #{})]
                 (if (contains? s step) (disj s step) (conj s step))))))

(rf/reg-event-db
 :evidence/add
 (fn [db [_ item]] (update db :evidence conj item)))

(rf/reg-event-db
 :evidence/drop
 (fn [db [_ i]] (update db :evidence #(vec (concat (subvec % 0 i) (subvec % (inc i)))))))

;; --- derived: everything below is the guest's answer, not this app's ---------

(rf/reg-sub :route (fn [db _] (:route db)))
(rf/reg-sub :field (fn [db [_ k]] (get db k)))
(rf/reg-sub :evidence (fn [db _] (:evidence db)))
(rf/reg-sub :done (fn [db [_ kind]] (get (:done db) kind #{})))
(rf/reg-sub :loss (fn [db _] (input/parse-yen (:loss db))))
(rf/reg-sub :loss-jpy (fn [db _] (:jpy (input/parse-yen (:loss db)))))

(rf/reg-sub
 :triage
 (fn [db _]
   (oracle/triage {:narrative (:narrative db)
                   :explicit  (:explicit db)
                   :loss-jpy  (:jpy (input/parse-yen (:loss db)))
                   :ongoing?  (:ongoing? db)})))

(rf/reg-sub
 :recovery-plan
 (fn [db _] (oracle/recovery-plan (:service db))))

(defn- evidence-rows
  "The 証拠目録's rows. The member's own items are TABULATED here and the document
  around them is the guest's — that is the seam, stated so it is not mistaken for
  the rules living in two places."
  [items]
  (->> items
       (map-indexed (fn [i it]
                      (str "  " (inc i) ". [" (:kind it) "] sha256="
                           (subs (:sha256 it) 0 16) "… ref=" (:label it))))
       (str/join "\n")))

(rf/reg-sub
 :filings
 (fn [db _]
   (let [loss (:jpy (input/parse-yen (:loss db)))
         subject (:subject db)
         kind (oracle/classify (:narrative db) (:explicit db))]
     {"damage-report"
      (oracle/damage-report {:subject subject :station (:station db) :kind kind
                             :occurred (:occurred db) :narrative (:narrative db)
                             :loss-jpy loss})
      "incident-statement"
      (oracle/incident-statement {:subject subject :timeline (:timeline db)
                                  :discovery (:discovery db) :current (:current db)})
      "evidence-index"
      (oracle/evidence-index {:subject subject
                              :rows (evidence-rows (:evidence db))
                              :n (count (:evidence db))})
      "damage-calculation"
      (oracle/damage-calculation {:subject subject
                                  :lines (if (pos? loss)
                                           (str "  ・被害額: 金 " (oracle/yen loss) " 円")
                                           "")
                                  :total loss})
      "bank-freeze-request"
      (oracle/bank-freeze-request {:subject subject :bank (:bank db)
                                   :occurred (:occurred db)
                                   :counterparty (:counterparty db) :loss-jpy loss})
      "recovery-plan" (oracle/recovery-plan (:service db))
      "platform-request" (oracle/platform-request
                          {:platform (:service db) :account-id (:account db)
                           :occurred (:occurred db) :kind kind})})))

(rf/reg-sub
 :platform-request
 (fn [db _]
   (oracle/platform-request
    {:platform  (:service db)
     :account-id (:account db)
     :occurred  (:occurred db)
     :kind      (oracle/classify (:narrative db) (:explicit db))})))

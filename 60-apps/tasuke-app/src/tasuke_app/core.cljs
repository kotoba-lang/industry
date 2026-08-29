(ns tasuke-app.core
  "Mount. One document, one mount (ADR-2608080100)."
  (:require [re-frame.core :as rf]
            [reagent.dom.client :as rdc]
            [tasuke-app.route :as route]
            [tasuke-app.state]
            [tasuke-app.views :as views]))

(defonce root (atom nil))

(defn ^:export main []
  (rf/dispatch-sync [:app/init])
  (route/start!)
  (let [el (.getElementById js/document "app")]
    (when-not @root (reset! root (rdc/create-root el)))
    (rdc/render @root [views/app])
    ;; A value the browser test can look for after crossing a view: if the page
    ;; ever NAVIGATES instead of routing, this is gone. That difference is
    ;; invisible in the source — the nav is real `href`s either way.
    (set! (.-tasukeMountedAt js/window) (.now js/Date))))

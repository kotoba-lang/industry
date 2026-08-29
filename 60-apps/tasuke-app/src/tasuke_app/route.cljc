(ns tasuke-app.route
  "Views as data, and the fragment that addresses them.

  One document, one bundle, one mount (ADR-2608080100). Moving between screens
  changes state, not location, so nothing here navigates — `views` is the table,
  the nav is GENERATED from it, and a view added to the table cannot be forgotten
  in the nav. A view added to a dispatch and missing from a hand-written nav is
  dead code that looks live.

  Addressing is the FRAGMENT, not a path. This page is served as a static file
  (an artifact, Pages, the itonami.cloud sites plane), where `/plan` works until
  someone reloads it and then 404s. `#plan` is correct under every mount point.

  `.cljc` on purpose: routing is testable without a browser, and only the
  listener is behind a reader conditional."
  ;; `clojure.string` was used fully qualified and never required. On
  ;; ClojureScript the build pulled it in anyway (another namespace requires it),
  ;; so it worked — and a JVM `require` of this namespace, which the docstring
  ;; above promises, would have failed. Measured 2026-08-29.
  (:require [clojure.string :as str]
            #?@(:cljs [[re-frame.core :as rf]])))

(def views
  "Order is the nav order. `:id` is the fragment."
  [{:id :soudan   :label "相談"     :hint "何が起きたかを書く"}
   {:id :plan     :label "初動"     :hint "いま順にやること"}
   {:id :shorui   :label "書面"     :hint "そのまま送れる下書き"}
   {:id :shoko    :label "証拠"     :hint "ハッシュで固める"}
   {:id :madoguchi :label "窓口"    :hint "無料の公的窓口"}])

(def default-view (:id (first views)))

(def known (into #{} (map :id) views))

(defn fragment->view
  "`\"#plan\"` → `:plan`. An unknown or empty fragment is the first view, never a
  blank screen — a victim who lands on a bad link still gets the intake form."
  [fragment]
  (let [s (-> (str fragment) (str/replace #"^#" ""))
        k (keyword s)]
    (if (contains? known k) k default-view)))

(defn view->fragment [id] (str "#" (name id)))

#?(:cljs
   (defn start!
     "Read the fragment now, and on every change. `hashchange` is what makes the
      generated nav's real `href`s work without a router that intercepts clicks."
     []
     (let [sync! #(rf/dispatch [:route/set (fragment->view (.. js/window -location -hash))])]
       (.addEventListener js/window "hashchange" sync!)
       (sync!))))

;; Generates 3 comparable sample UI screens (same content, different platform
;; binding) as static self-contained HTML via kotoba-ui.core/->page + shitsuke's
;; SSR hiccup renderer -- same dual-render contract liquid-glass.demo (JVM)
;; already uses, but authored as an nbb script matching this org's .cljc
;; runtime priority (kotoba wasm > clojurewasm > cljs > nbb > jvm/bb) and the
;; multi-dir --classpath pattern already established by
;; orgs/kotoba-lang/kototama/web/generate.cljs.
;;
;; Run (from the superproject root):
;;   nbb --classpath "orgs/kotoba-lang/shitsuke/src:orgs/kotoba-lang/css/src:orgs/kotoba-lang/liquid-glass-ui/src:orgs/kotoba-lang/kotoba-ui/src:orgs/kotoba-lang/uikit/src:orgs/kotoba-lang/appkit/src" \
;;     90-docs/design-quality/samples/generate-samples.cljs
;;
;; Each sample renders the identical "Team Dashboard" screen content through
;; a different platform binding's panel/list-view defaults, so the only
;; visual difference between samples is what that binding actually changes --
;; a fair basis for score comparison.

(require '[kotoba-ui.core :as ui]
         '[uikit.core :as uikit]
         '[appkit.core :as appkit]
         '["fs" :as fs])

(def theme {:accent "#5b6cff"})

;; Co-Scientist kaizen batch `design-quality-kaizen-1` (90-docs/design-quality/coscientist/
;; iteration-01.md) — 4 low-risk, additive consumer-level fixes for gaps that
;; design-quality.audit found and design-quality-ledger.edn's 3-judge LLM panel (scoring
;; 4.0-5.0/5 on every HIG axis) completely missed. All unlayered app CSS, so it wins over
;; the library's @layer kotoba.hig / kotoba.glass rules by design -- no library edit.
;; dq-h4 (viewport-fit=cover) and dq-h6 (liquid-glass-ui showcase responsive) are deferred
;; upstream-only findings, NOT applied here -- see the iteration doc.
(def kaizen-head
  (list
   [:meta {:name "theme-color" :content "#ffffff" :media "(prefers-color-scheme: light)"}]
   [:meta {:name "theme-color" :content "#0b0b10" :media "(prefers-color-scheme: dark)"}]
   [:style [:hiccup/raw
            (str
             ;; dq-h1 tap-targets: liquid-glass.style sizes buttons by padding+line-height only
             ".liquid-glass__button,.liquid-glass__icon-button{min-height:44px}"
             ;; dq-h2 dynamic-viewport: kotoba-ui.shell's .kotoba-shell__app has no dvh fallback
             ".kotoba-shell__app{min-height:100dvh}"
             ;; dq-h3 safe-area: only the bottom edge was padded; cover the top edge too
             ".liquid-glass__nav-bar{padding-top:env(safe-area-inset-top,0px)}")]]))

(defn nav []
  (ui/nav-bar "Team Dashboard" {:trailing (ui/icon-button "⚙" {:title "Settings"})}))

(defn cards [panel-fn]
  (ui/grid {:min "220px" :gap :4}
    (panel-fn [:div [:h3 "Open tasks"] [:p "12 tasks across 3 projects need review before Friday."]])
    (panel-fn [:div [:h3 "Team activity"] [:p "5 teammates pushed changes in the last 24 hours."]])
    (panel-fn [:div [:h3 "Upcoming"] [:p "Design review at 3pm, deploy window opens at 6pm."]])))

(defn activity-rows []
  [(ui/list-row "Aiko merged \"fix: sidebar collapse\"" {:trailing (ui/badge "2m")})
   (ui/list-row "Ren opened a design review request" {:trailing (ui/badge "18m")})
   (ui/list-row "Deploy to staging succeeded" {:trailing (ui/badge "1h")})
   (ui/list-row "Mio commented on the roadmap doc" {:trailing (ui/badge "3h")})])

(defn screen [{:keys [panel-fn list-fn sidebar?]}]
  (ui/app-shell
   (cond-> {:nav (nav)}
     sidebar? (assoc :sidebar (ui/stack {:gap :2}
                                         (panel-fn [:strong "Projects"])
                                         (list-fn (activity-rows)))))
   (ui/hero {:title "Good afternoon, Jun"
             :tagline "Here's what's happening across your team today."
             :actions [(ui/button "New task") (ui/button "Invite teammate")]})
   (ui/section {:title "Overview"} (cards panel-fn))
   (when-not sidebar?
     (ui/section {:title "Recent activity"} (list-fn (activity-rows))))))

(defn write-page! [path title screen-opts]
  (let [html (ui/->page {:title title :lang "en" :theme theme :head kaizen-head} (screen screen-opts))]
    (fs/writeFileSync path html)
    (println "wrote" path (count html) "bytes")))

(write-page! "90-docs/design-quality/samples/uikit-mobile-sample.html"
             "uikit mobile sample — Team Dashboard"
             {:panel-fn uikit/panel :list-fn uikit/list-view :sidebar? false})

(write-page! "90-docs/design-quality/samples/appkit-desktop-sample.html"
             "appkit desktop sample — Team Dashboard"
             {:panel-fn appkit/panel :list-fn appkit/list-view :sidebar? true})

(write-page! "90-docs/design-quality/samples/kotoba-ui-bare-sample.html"
             "kotoba-ui bare sample — Team Dashboard (no platform binding)"
             {:panel-fn ui/panel :list-fn ui/list-view :sidebar? false})

#!/usr/bin/env nbb
;; Copy the DADS stylesheet out of the west checkout into public/.
;;
;; Not committed: a copy of `dds.css` inside this game is a fork of the
;; component CSS, and the DADS button exposes `:attrs` precisely so consumers
;; do not have to make one. This is a build step, the way jp-go-dds itself
;; vendors from upstream.
;;
;;     npx nbb vendor.cljs        # before `npx shadow-cljs release app`
(ns vendor
  (:require ["fs" :as fs]
            ["path" :as path]))

(def src
  (path/resolve "../../../../../orgs/kotoba-lang/jp-go-digital-design-system/resources/jp_go_dds/dds.css"))
(def dst (path/resolve "public/dds.css"))

(if (fs/existsSync src)
  (do (fs/copyFileSync src dst)
      (println (str "vendored " (.-size (fs/statSync dst)) " bytes -> public/dds.css")))
  (do (println (str "missing " src
                    "\n  the west checkout is not there — run `west update jp-go-digital-design-system`"))
      (js/process.exit 1)))

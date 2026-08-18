#!/usr/bin/env nbb
(ns endpoint-health-pull
  "Bring the resident's statement back to where a Bot can read it.

  ## Why a pull and not a push

  The resident on gad holds no write key and should not get one: it is a
  `:slot/anonymous` residency, and a host that can write to repositories is a
  different, heavier thing (ADR-2608111721). So the side that already has keys
  does the moving.

  Nothing is lost by that. Bots run on the workspace machine -- `bots.clj` says
  so in as many words ('A Bot's computer is therefore this machine') and
  `fire-due-workforce!` needs a live session -- so a mirror refreshed while that
  machine is live is refreshed exactly when a Bot could read it.

  What IS lost is freshness when the workspace is down, and the receipt says so
  itself: it carries `:pulled-at` and `:measured-at` separately, so a reader can
  see a stale mirror instead of mistaking it for a quiet fleet. That distinction
  is the whole subject of ADR-2608180100.

  usage:
    nbb scripts/endpoint-health-pull.cljs [--host gad@100.82.98.110]
                                          [--remote-home /var/lib/endpoint-health]
                                          [--out <path>]"
  (:require ["child_process" :as cp]
            ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def argv (vec *command-line-args*))
(defn opt [k d] (if-let [i (first (keep-indexed #(when (= %2 k) %1) argv))]
                  (get argv (inc i) d) d))
(def host (opt "--host" "gad@100.82.98.110"))
(def remote-home (opt "--remote-home" "/var/lib/endpoint-health"))
(def out (opt "--out" "orgs/network-awai/cloud-itonami/docs/endpoint-health-receipt.edn"))

(defn -main []
  (let [raw (try (.execSync cp (str "ssh -o ConnectTimeout=10 -o BatchMode=yes " host
                                    " cat " remote-home "/statement.edn")
                            #js {:encoding "utf8" :stdio "pipe" :timeout 30000})
                 (catch :default e
                   ;; Refuse rather than write an empty or partial receipt. A
                   ;; receipt that exists but says nothing is exactly the shape
                   ;; this whole exercise is about.
                   (println (str "REFUSING to write a receipt: cannot read the resident -- " e))
                   (js/process.exit 2)))
        stmt (try (edn/read-string (str raw))
                  (catch :default e
                    (println (str "REFUSING to write a receipt: resident output unreadable -- " e))
                    (js/process.exit 2)))
        stmts (:statements stmt)]
    (when (empty? stmts)
      (println "REFUSING to write a receipt: the resident has no statements yet.")
      (js/process.exit 2))
    (let [now (.now js/Date)
          age-min (int (/ (- now (:at stmt)) 60000))
          bad (filterv #(#{:unavailable :degraded} (:availability/status %)) stmts)
          receipt {:schema "cloud.itonami.endpoint-health-receipt.v1"
                   :source {:host host :path (str remote-home "/statement.edn")
                            :residency :slot/anonymous}
                   :measured-at (:at stmt)
                   :pulled-at now
                   :mirror-age-minutes age-min
                   :window-hours (:window-hours stmt)
                   :not-available (mapv :availability/target bad)
                   :statements stmts}]
      (.mkdirSync fs (.dirname path out) #js {:recursive true})
      (.writeFileSync fs out (str ";; 生成物。手で編集しない —— nbb scripts/endpoint-health-pull.cljs\n"
                                  ";; :measured-at と :pulled-at は別物。差が開いていたら\n"
                                  ";; それは『静かなフリート』ではなく『古い鏡』である。\n"
                                  (pr-str receipt) "\n"))
      (println (str "wrote " out))
      (println (str "  measured " age-min " minute(s) ago, " (count stmts) " target(s), "
                    (count bad) " not available")))))

(-main)

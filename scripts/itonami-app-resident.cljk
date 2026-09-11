#!/usr/bin/env nbb
;; itonami-app-resident — the launchd job `cloud.itonami.app.local` runs THIS,
;; and this runs the JVM server. The plist's WorkingDirectory is the app
;; checkout, so `clojure -M:server` and the default data dir (`./data`) both
;; resolve from there.
;;
;; ## Why it exists again, and why it is HERE
;;
;; Rewritten 2026-09-09, and put under version control the same day. The
;; original lived only in the home directory, so its loss was invisible until
;; the next restart — a single point of failure with no history and no
;; reviewer. This file is the only copy; `~/.gftd/bin/` is gone with the rest of
;; file; the plist beside it (`scripts/cloud.itonami.app.local.plist`) points
;; here directly, and is the copy to reinstall from.
;;
;; What made the rewrite necessary: the original lived at `~/.gftd/bin/` (the name
;; is retired -- manifest/gftd-retirement.edn -- but this sentence records where it
;; actually was) and was
;; removed by that day's home-directory migration, while the process it had
;; started stayed alive holding a deleted file. The plist still pointed at the missing
;; path and `KeepAlive` is true, so the app was one crash away from a respawn
;; loop with nothing to respawn — and nothing said so, because a server that is
;; already running looks exactly like a server that can be restarted.
;;
;; ## The two secrets, and why they are read HERE rather than declared in the plist
;;
;; A launchd plist is world-readable; a value written into `EnvironmentVariables`
;; is a credential in a file anyone can cat. These are fetched at start instead:
;;
;;   MURAKUMO_API_KEY    ~/.itonami/itonami-murakumo-api-key (0600). A file rather
;;                       than kagi because launchd cannot unlock kagi.
;;   OPENROUTER_API_KEY  login Keychain, service `gftd.openrouter`, by exact
;;                       name. One known item, never an enumeration.
;;
;; Both matter beyond authentication: `policy/provider-allowed?` derives
;; `authenticated` from the variable ACTUALLY being set, so an egress provider
;; whose variable is empty is denied before a turn starts. That is why a missing
;; one is reported in full rather than passed over — but it is NOT fatal here.
;; Exiting would hand `KeepAlive` a crash loop, and a server running with one
;; provider denied is still a server; the line below is what says which.

(ns itonami-app-resident
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]))

(def workspace-root "/Users/junkawasaki/github/com-junkawasaki")
(def murakumo-key-file
  (str (or (aget js/process.env "HOME") "") "/.itonami/itonami-murakumo-api-key"))
(def openrouter-keychain-service "gftd.openrouter")

(defn- log [& parts]
  (println (apply str "itonami-app-resident: " parts)))

(defn- from-file [path]
  (try (let [value (.trim (fs/readFileSync path "utf8"))]
         (when (seq value) value))
       (catch :default _ nil)))

(defn- from-keychain [service]
  (try (let [{:keys [status stdout]}
             (js->clj (cp/spawnSync "/usr/bin/security"
                                    #js ["find-generic-password" "-s" service "-w"]
                                    #js {:encoding "utf8"})
                      :keywordize-keys true)]
         (when (zero? status)
           (let [value (.trim (str stdout))]
             (when (seq value) value))))
       (catch :default _ nil)))

(defn -main []
  (let [murakumo (from-file murakumo-key-file)
        openrouter (from-keychain openrouter-keychain-service)
        present (cond-> ["CLOUD_ITONAMI_WORKSPACE_ROOT"]
                  murakumo (conj "MURAKUMO_API_KEY")
                  openrouter (conj "OPENROUTER_API_KEY"))
        absent (cond-> []
                 (nil? murakumo) (conj (str "MURAKUMO_API_KEY (" murakumo-key-file ")"))
                 (nil? openrouter) (conj (str "OPENROUTER_API_KEY (Keychain "
                                              openrouter-keychain-service ")")))]
    ;; Names only. A launcher that prints a value writes it into
    ;; target-server-live.log, which is neither 0600 nor rotated.
    (log "exporting " (.join (clj->js present) ", ") " into the server process")
    (when (seq absent)
      (log "WARNING not found, so its provider will fail `authenticated` and be"
           " denied before any turn: " (.join (clj->js absent) ", ")))
    ;; `js/process.env` is a host object, not a map: `js->clj` hands it back
    ;; unchanged and `assoc` then throws. Copy it and set properties.
    (let [env (js/Object.assign #js {} js/process.env)
          _ (aset env "CLOUD_ITONAMI_WORKSPACE_ROOT" workspace-root)
          _ (when murakumo (aset env "MURAKUMO_API_KEY" murakumo))
          _ (when openrouter (aset env "OPENROUTER_API_KEY" openrouter))
          child (cp/spawn "clojure" #js ["-M:server"]
                          #js {:stdio "inherit" :env env})]
      ;; launchd stops a job with SIGTERM. Forward it, so the server gets the
      ;; same signal it would have got directly and this wrapper is not the
      ;; reason a shutdown becomes a kill.
      (doseq [signal ["SIGTERM" "SIGINT" "SIGHUP"]]
        (.on js/process signal (fn [] (.kill child signal))))
      (.on child "exit"
           (fn [code signal]
             (log "server exited code=" code " signal=" signal)
             (set! (.-exitCode js/process) (or code 1)))))))

(-main)

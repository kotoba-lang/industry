#!/usr/bin/env nbb
;; scripts/adr-inventory-tick.cljs — 90-docs/adr の棚卸し候補を測る決定論的 tick。
;; ここにモデルは居ない。分類の正本は `scripts/adr-inventory.cljs`。
;;
;; 正本: ADR-2608161700。姉妹は docs-edn-repair-tick（読めない文書）。
;;
;; 不変条件:
;;   - 走査対象が床を割ったら候補 0 を報告しない（:insufficient-scan）。
;;   - ledger は追記のみ。捏造ゼロ。
;;   - この tick は何も書かない・git を触らない。測って言うだけ。
;;   - exit 0 常に（監視 tick であって gate ではない）。
;;
;; usage:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/adr-inventory-tick.cljs

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def os (js/require "node:os"))
(def cp (js/require "node:child_process"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT") (.cwd js/process)))
(def ledger-file (str home "/.gftd/adr-inventory-tick.ledger.edn"))

(defn- sh [cmd args]
  (try
    (let [r (.spawnSync cp cmd (clj->js args)
                        #js {:encoding "utf8" :cwd root
                             :maxBuffer (* 64 1024 1024)})]
      {:code (aget r "status") :out (str (aget r "stdout")) :err (str (aget r "stderr"))})
    (catch :default e {:code nil :out "" :err (str e)})))

(defn -main []
  (let [started (.toISOString (js/Date.))
        {:keys [code out err]}
        (sh "nbb" ["--classpath" ".:scripts/nbb_compat"
                   "scripts/adr-inventory.cljs" "--edn"])
        parsed (try (edn/read-string (str/trim out))
                    (catch :default _ nil))
        result
        (cond
          (not= 0 code)
          {:at started :outcome :insufficient-scan :reason :classifier-failed
           :exit code :err (str/trim (str err out))}

          (nil? parsed)
          {:at started :outcome :insufficient-scan :reason :classifier-unreadable
           :err (str/trim (str err (subs (str out) 0 (min 400 (count (str out))))))}

          :else
          (merge parsed {:at started}))]
    (println (str "adr-inventory-tick: listed " (or (:listed result) "?")
                  " / readable " (or (:readable result) "?")
                  " / findings " (or (:finding-count result) 0)
                  " / outcome " (name (:outcome result))))
    (when-let [n (:note result)] (println (str "  " n)))
    (when-let [bk (:by-kind result)]
      (doseq [[k c] (sort-by (comp name key) bk)]
        (println (str "  " (name k) "  " c))))
    (when-let [n (:next result)]
      (println (str "  next  " (name (:kind n)) "  " (:path n))))
    (let [slim (-> result
                   (dissoc :candidates :unreadable-paths :err)
                   (update :next #(when % (dissoc % :body :snippet))))]
      (try (.appendFileSync fs ledger-file (str (pr-str slim) "\n"))
           (catch :default e (println "ledger 追記に失敗:" (str e)))))
    (js/process.exit 0)))

(-main)

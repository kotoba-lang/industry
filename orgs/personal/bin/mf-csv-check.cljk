#!/usr/bin/env nbb
;; --- nbb shims (auto, ADR-2607173000) ---------------------------------
(def ^:private __fs (js/require "node:fs"))
(def ^:private __path (js/require "node:path"))
(def ^:private __cp (js/require "node:child_process"))
(def ^:private __os (js/require "node:os"))
(def ^:private __crypto (js/require "node:crypto"))
(defn- __sh [& args]
  (let [opts (when (map? (last args)) (last args))
        cmd (if opts (butlast args) args)
        r (.spawnSync __cp (first cmd) (to-array (rest cmd))
                      (clj->js (merge {:encoding "utf8"} (when opts {:cwd (:dir opts)}))))]
    {:exit (or (.-status r) 1) :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))
(defn- __shell [& args]
  (let [opts (when (map? (first args)) (first args))
        cmd (if opts (rest args) args)
        r (.spawnSync __cp (first cmd) (to-array (rest cmd))
                      (clj->js (merge {:stdio "inherit" :encoding "utf8"}
                                      (when opts {:cwd (:dir opts)}))))]
    (when-not (zero? (or (.-status r) 1))
      (throw (js/Error. (str "shell failed: " (pr-str cmd)))))
    {:exit (or (.-status r) 0) :out "" :err ""}))
;; -----------------------------------------------------------------------
(defn- __json-parse [s & _] (js->clj (js/JSON.parse s) :keywordize-keys true))
(defn- __json-gen [x & _] (js/JSON.stringify (clj->js x)))
;; mf-csv-check.nbb — ob/mf-csv-export の自動消化 (pl/mf-csv-ingest の機械手番側)。
;; launchd (com.junkawasaki.mf-csv-check) が毎日18:00に起動:
;;   inbox/mf-cashflow.csv あり → ingest.mf → warehouse.load → obligation done
;;     → bank-sources :dark→:ingested → commit → 通知 → ジョブ自己解除
;;   なし & 期限(2026-06-19)以降 → macOS 通知でリマインド (obligation は open のまま)
(require '
         ']
         '[clojure.string :as str])

(def base "/Users/junkawasaki/github/com-junkawasaki/orgs/personal")
(def csv-file (str base "/inbox/mf-cashflow.csv"))
(def ob-file (str base "/facts/obligations.jsonl"))
(def banks-file (str base "/facts/bank-sources.edn"))
(def due "2026-06-19")
(def label "com.junkawasaki.mf-csv-check")
(def plist (str (.-homedir __os) "/Library/LaunchAgents/" label ".plist"))

(defn notify [msg]
  (__sh "osascript" "-e"
      (format "display notification \"%s\" with title \"MF CSV取込 (ob/mf-csv-export)\"" msg)))

(defn uninstall! []
  (__sh "rm" "-f" plist)
  (let [uid (str/trim (:out (__sh "id" "-u")))]
    (__sh "launchctl" "bootout" (str "gui/" uid "/" label))))  ; 自プロセスも終了する → 最後に呼ぶ

(defn obligation-done? []
  (some #(let [r (__json-parse % true)]
           (and (= "ob/mf-csv-export" (:id r)) (= "done" (:status r))))
        (str/split-lines (slurp ob-file))))

(defn mark-obligation-done! []
  (let [lines (str/split-lines (slurp ob-file))
        today (.slice (.toISOString (js/Date.)) 0 10)]
    (spit ob-file
          (str (str/join "\n"
                 (map (fn [l]
                        (let [r (__json-parse l true)]
                          (if (= "ob/mf-csv-export" (:id r))
                            (__json-gen
                             (assoc r :status "done"
                                      :note (str (:note r) " | 自動取込完了 " today " (mf-csv-check.bb)")))
                            l)))
                      lines))
               "\n"))))

(defn covered-banks []
  (->> (str/split-lines (slurp (str base "/mail/mf-txns.jsonl")))
       (keep #(:bank (__json-parse % true)))
       set))

(defn mark-banks-ingested! [banks]
  (let [s (slurp banks-file)
        s' (reduce (fn [s bid]
                     (str/replace s
                                  (re-pattern (str "(:bank/id \"" bid "\"[^}]*?):bank/status :dark"))
                                  "$1:bank/status :ingested"))
                   s banks)]
    (spit banks-file s')))

(defn ingest! []
  (__shell {:dir (str base "/bin/datomic")} "clojure" "-M" "-m" "ingest.mf")
  (__shell {:dir (str base "/bin/datomic")} "clojure" "-M" "-m" "warehouse.load")
  (let [n (count (str/split-lines (slurp (str base "/mail/mf-txns.jsonl"))))]
    (when (zero? n) (throw (ex-info "mf-txns.jsonl が空 — CSV形式を確認" {})))
    (mark-obligation-done!)
    (mark-banks-ingested! (covered-banks))
    (__shell {:dir base} "git" "add" "inbox/mf-cashflow.csv" "mail/mf-txns.jsonl"
           "facts/obligations.jsonl" "facts/bank-sources.edn")
    (__shell {:dir base} "git" "commit"
           "inbox/mf-cashflow.csv" "mail/mf-txns.jsonl" "facts/obligations.jsonl" "facts/bank-sources.edn"
           "-m" (str "ingest: MF CSV 自動取込 " n " txns (ob/mf-csv-export done, mf-csv-check.bb)\n\n"
                     "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"))
    n))

(cond
  (obligation-done?)
  (uninstall!)  ; 手動消化済みでもジョブを畳む

  (.exists (__path.resolve csv-file))
  (try (let [n (ingest!)]
         (notify (format "取込完了: %d txns → Datomic。dark口座も解消。" n))
         (uninstall!))
       (catch Exception e
         (notify (str "取込失敗: " (ex-message e) " — 手動確認を"))))

  (>= (compare (.slice (.toISOString (js/Date.)) 0 10) due) 0)
  (notify "MoneyForward 入出金CSVが未エクスポートです → orgs/personal/inbox/mf-cashflow.csv (期限 6/19)")

  :else nil)  ; 期限前で未着 — 静かに待つ

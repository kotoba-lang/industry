#!/usr/bin/env bb
;; mf-csv-check.bb — ob/mf-csv-export の自動消化 (pl/mf-csv-ingest の機械手番側)。
;; launchd (com.junkawasaki.mf-csv-check) が毎日18:00に起動:
;;   inbox/mf-cashflow.csv あり → ingest.mf → warehouse.load → obligation done
;;     → bank-sources :dark→:ingested → commit → 通知 → ジョブ自己解除
;;   なし & 期限(2026-06-19)以降 → macOS 通知でリマインド (obligation は open のまま)
(require '[cheshire.core :as json]
         '[babashka.process :refer [shell sh]]
         '[clojure.string :as str])

(def base "/Users/junkawasaki/github/com-junkawasaki/orgs/personal")
(def csv-file (str base "/inbox/mf-cashflow.csv"))
(def ob-file (str base "/facts/obligations.jsonl"))
(def banks-file (str base "/facts/bank-sources.edn"))
(def due "2026-06-19")
(def label "com.junkawasaki.mf-csv-check")
(def plist (str (System/getProperty "user.home") "/Library/LaunchAgents/" label ".plist"))

(defn notify [msg]
  (sh "osascript" "-e"
      (format "display notification \"%s\" with title \"MF CSV取込 (ob/mf-csv-export)\"" msg)))

(defn uninstall! []
  (sh "rm" "-f" plist)
  (let [uid (str/trim (:out (sh "id" "-u")))]
    (sh "launchctl" "bootout" (str "gui/" uid "/" label))))  ; 自プロセスも終了する → 最後に呼ぶ

(defn obligation-done? []
  (some #(let [r (json/parse-string % true)]
           (and (= "ob/mf-csv-export" (:id r)) (= "done" (:status r))))
        (str/split-lines (slurp ob-file))))

(defn mark-obligation-done! []
  (let [lines (str/split-lines (slurp ob-file))
        today (str (java.time.LocalDate/now))]
    (spit ob-file
          (str (str/join "\n"
                 (map (fn [l]
                        (let [r (json/parse-string l true)]
                          (if (= "ob/mf-csv-export" (:id r))
                            (json/generate-string
                             (assoc r :status "done"
                                      :note (str (:note r) " | 自動取込完了 " today " (mf-csv-check.bb)")))
                            l)))
                      lines))
               "\n"))))

(defn covered-banks []
  (->> (str/split-lines (slurp (str base "/mail/mf-txns.jsonl")))
       (keep #(:bank (json/parse-string % true)))
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
  (shell {:dir (str base "/bin/datomic")} "clojure" "-M" "-m" "ingest.mf")
  (shell {:dir (str base "/bin/datomic")} "clojure" "-M" "-m" "warehouse.load")
  (let [n (count (str/split-lines (slurp (str base "/mail/mf-txns.jsonl"))))]
    (when (zero? n) (throw (ex-info "mf-txns.jsonl が空 — CSV形式を確認" {})))
    (mark-obligation-done!)
    (mark-banks-ingested! (covered-banks))
    (shell {:dir base} "git" "add" "inbox/mf-cashflow.csv" "mail/mf-txns.jsonl"
           "facts/obligations.jsonl" "facts/bank-sources.edn")
    (shell {:dir base} "git" "commit"
           "inbox/mf-cashflow.csv" "mail/mf-txns.jsonl" "facts/obligations.jsonl" "facts/bank-sources.edn"
           "-m" (str "ingest: MF CSV 自動取込 " n " txns (ob/mf-csv-export done, mf-csv-check.bb)\n\n"
                     "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"))
    n))

(cond
  (obligation-done?)
  (uninstall!)  ; 手動消化済みでもジョブを畳む

  (.exists (java.io.File. csv-file))
  (try (let [n (ingest!)]
         (notify (format "取込完了: %d txns → Datomic。dark口座も解消。" n))
         (uninstall!))
       (catch Exception e
         (notify (str "取込失敗: " (ex-message e) " — 手動確認を"))))

  (>= (compare (str (java.time.LocalDate/now)) due) 0)
  (notify "MoneyForward 入出金CSVが未エクスポートです → orgs/personal/inbox/mf-cashflow.csv (期限 6/19)")

  :else nil)  ; 期限前で未着 — 静かに待つ

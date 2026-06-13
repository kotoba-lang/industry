#!/usr/bin/env bb
;; m365-live.bb — 実 Microsoft 365 (Outlook) のライブ情報を取得 (Clojure / babashka)。
;;
;; m365 CLI (ログイン済みセッション) から Graph アクセストークンを取り、
;; 受信トレイ直近・今後の予定・未読件数を取得して JSON で stdout に出力する。
;; 読み取りのみ・メタデータ(件名/差出人/日時)のみ取得し、本文は取らない。
;;
;;   bb analysis/m365-live.bb   => {"unread":N,"inbox":[...],"events":[...],"fetched_at":"..."}

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def GRAPH "https://graph.microsoft.com/v1.0")

(defn token []
  (-> (p/shell {:out :string :err :string}
               "m365" "util" "accesstoken" "get" "--resource" "https://graph.microsoft.com")
      :out str/trim (str/replace "\"" "")))

(defn graph-get [tok url & hdrs]
  (let [args (concat ["curl" "-s" "--max-time" "25" "-H" (str "Authorization: Bearer " tok)]
                     (mapcat (fn [[k v]] ["-H" (str k ": " v)]) (partition 2 hdrs))
                     [url])]
    (try (json/parse-string (:out (apply p/shell {:out :string} args)) true)
         (catch Exception _ {}))))

(defn now-iso [] (str (java.time.Instant/now)))
(defn plus-days-iso [n] (str (.plus (java.time.Instant/now) (java.time.Duration/ofDays n))))

(defn -main []
  (let [tok (token)
        inbox (-> (graph-get tok
                   (str GRAPH "/me/mailFolders/inbox/messages?$top=12&$select=subject,from,receivedDateTime,isRead&$orderby=receivedDateTime%20desc"))
                  :value)
        events (-> (graph-get tok
                    (str GRAPH "/me/calendarView?startDateTime=" (now-iso) "&endDateTime=" (plus-days-iso 14)
                         "&$select=subject,start,organizer&$orderby=start/dateTime&$top=12")
                    "Prefer" "outlook.timezone=\"Tokyo Standard Time\"")
                   :value)
        unread (-> (graph-get tok (str GRAPH "/me/mailFolders/inbox?$select=unreadItemCount"))
                   :unreadItemCount)]
    (println
     (json/generate-string
      {:fetched_at (now-iso)
       :unread (or unread 0)
       :inbox (mapv (fn [m]
                      {:subject (:subject m)
                       :from (get-in m [:from :emailAddress :address])
                       :received (:receivedDateTime m)
                       :unread (not (:isRead m))})
                    (take 12 inbox))
       :events (mapv (fn [e]
                       {:subject (:subject e)
                        :start (get-in e [:start :dateTime])
                        :organizer (get-in e [:organizer :emailAddress :name])})
                     (take 12 events))}))))

(-main)

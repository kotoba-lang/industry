#!/usr/bin/env nbb
;; ingest-raw-eml.cljs — content-addressed ingest of TRUE RFC822 .eml files.
;;
;; Why this exists alongside bin/ingest-eml.py and bin/ingest-gmail-batch.py:
;;   - ingest-eml.py  consumes MCP get_thread JSON and *synthesizes* an .eml.
;;                    The MCP surface returns no attachment bytes, so attachments
;;                    are LOST. Fine for text-only threads, wrong for contracts.
;;   - ingest-gmail-batch.py fetches format=RAW over the Gmail API — correct, but
;;                    needs a live OAuth refresh token (bin/google-auth.py).
;;   - this script    takes .eml bytes you already have (Gmail UI
;;                    "メッセージをダウンロード", mbox split, mail client export)
;;                    and files them under the same convention. Use it when the
;;                    API token is expired/revoked but the message must be kept
;;                    byte-exact, attachments included.
;;
;; Writes, matching bin/ingest-gmail-batch.py exactly:
;;   mail/messages/<cid>.eml     (cid = sha256 hex of the raw bytes)
;;   mail/messages/index.jsonl   (one JSON record per message, appended)
;; Idempotent by cid: a file whose cid is already indexed is skipped.
;;
;; Usage:
;;   nbb bin/ingest-raw-eml.cljs --account jun784 [--thread-id T] \
;;       [--message-id M]... [--labels L,L,...]... <file.eml>...
;;   --message-id and --labels may repeat; each is applied to the .eml files
;;   positionally (file 1 gets the 1st, file 2 the 2nd, ...).
;;
;; Why --labels is not optional in practice: a raw .eml carries NO Gmail label
;; information (the Gmail UI download emits no X-Gmail-Labels header; only the
;; API's format=RAW response has a sibling labelIds field). Records written
;; without it get "labels":[] while every API-ingested record carries e.g.
;; ["IMPORTANT","STARRED","INBOX"] — so label-scoped queries over the warehouse
;; silently skip them. Pass the labels from whatever surface you read the thread
;; on (MCP get_thread returns labelIds) unless you truly have none.

(ns ingest-raw-eml
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.string :as str]))

(def base (path/dirname (path/dirname (js/require.resolve "./ingest-raw-eml.cljs"))))
(def msgdir (path/join base "mail" "messages"))
(def index-path (path/join msgdir "index.jsonl"))

;; ---------------------------------------------------------------- RFC822 parse

(defn split-header-block
  "Return the header block of a raw message as a string (CRLF or LF tolerant)."
  [^js buf]
  (let [s (.toString buf "binary")
        i (.indexOf s "\r\n\r\n")
        j (.indexOf s "\n\n")
        end (cond (pos? i) i (pos? j) j :else (count s))]
    (subs s 0 end)))

(defn unfold
  "Join RFC822 folded continuation lines (leading SP/TAB) onto the previous line."
  [header-str]
  (->> (str/split header-str #"\r?\n")
       (reduce (fn [acc line]
                 (if (and (seq acc) (re-find #"^[ \t]" line))
                   (conj (pop acc) (str (peek acc) " " (str/trim line)))
                   (conj acc line)))
               [])))

(defn decode-word
  "Decode a single RFC2047 encoded-word body."
  [charset enc text]
  (let [cs (if (re-matches #"(?i)utf-?8" charset) "utf8" "latin1")]
    (case (str/lower-case enc)
      "b" (.toString (js/Buffer.from text "base64") cs)
      "q" (-> text
              (str/replace "_" " ")
              (str/replace #"=([0-9A-Fa-f]{2})"
                           (fn [m] (js/String.fromCharCode (js/parseInt (second m) 16))))
              (js/Buffer.from "binary")
              (.toString cs))
      text)))

(defn decode-rfc2047
  "Decode all =?charset?enc?text?= words in a header value.
  Per RFC 2047 §6.2, linear whitespace *between two adjacent encoded-words* is a
  fold artifact and must be dropped — otherwise a subject folded mid-word comes
  back with a spurious space (e.g. 「ご署名お 願い」)."
  [s]
  (-> s
      (str/replace #"\?=[ \t]+=\?" "?==?")
      (str/replace #"=\?([^?]+)\?([BbQq])\?([^?]*)\?="
                   (fn [m] (decode-word (nth m 1) (nth m 2) (nth m 3))))
      ;; headers with no encoded-words are still raw bytes read as binary
      (as-> v (if (= v s) (.toString (js/Buffer.from s "binary") "utf8") v))))

(defn headers
  "Parse a raw message buffer into {lower-case-name [decoded-value ...]}."
  [^js buf]
  (reduce (fn [acc line]
            (if-let [[_ k v] (re-find #"^([A-Za-z0-9-]+):\s?(.*)$" line)]
              (update acc (str/lower-case k) (fnil conj []) (decode-rfc2047 v))
              acc))
          {}
          (unfold (split-header-block buf))))

(defn addr-list [v]
  (if (str/blank? (or v "")) [] (mapv str/trim (str/split v #",\s*"))))

;; ---------------------------------------------------------------------- index

(defn known-cids []
  (if (fs/existsSync index-path)
    (->> (str/split-lines (fs/readFileSync index-path "utf8"))
         (remove str/blank?)
         (keep #(try (.-cid (js/JSON.parse %)) (catch :default _ nil)))
         set)
    #{}))

(defn sha256 [^js buf]
  (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

;; ----------------------------------------------------------------------- main

(defn parse-args [argv]
  (loop [[a & more] argv opts {:message-ids [] :labels []} files []]
    (cond
      (nil? a) [opts files]
      (= a "--account")    (recur (rest more) (assoc opts :account (first more)) files)
      (= a "--thread-id")  (recur (rest more) (assoc opts :thread-id (first more)) files)
      (= a "--message-id") (recur (rest more) (update opts :message-ids conj (first more)) files)
      (= a "--labels")     (recur (rest more)
                                  (update opts :labels conj
                                          (->> (str/split (or (first more) "") #",")
                                               (map str/trim)
                                               (remove str/blank?)
                                               vec))
                                  files)
      :else (recur more opts (conj files a)))))

(defn -main [& argv]
  (let [[opts files] (parse-args argv)]
    (when (empty? files)
      (println "usage: nbb bin/ingest-raw-eml.cljs --account SLUG [--thread-id T] [--message-id M]... [--labels L,L]... <file.eml>...")
      (js/process.exit 2))
    (fs/mkdirSync msgdir #js {:recursive true})
    (let [seen (atom (known-cids))
          lines (atom [])
          added (atom 0)]
      (doseq [[i f] (map-indexed vector files)]
        (let [buf (fs/readFileSync f)
              cid (sha256 buf)
              h (headers buf)
              g #(first (get h % []))]
          (if (@seen cid)
            (println (str "skip (already indexed): " (path/basename f)))
            (do
              (fs/writeFileSync (path/join msgdir (str cid ".eml")) buf)
              (swap! lines conj
                     (js/JSON.stringify
                      (clj->js (cond-> {:cid cid
                                        :sha256 cid
                                        :size (.-length buf)
                                        :rfc822_message_id (g "message-id")
                                        :date (g "date")
                                        :from (g "from")
                                        :to (addr-list (g "to"))
                                        :cc (addr-list (g "cc"))
                                        :subject (g "subject")
                                        :labels (get (:labels opts) i [])
                                        :eml_path (str "mail/messages/" cid ".eml")
                                        :source "gmail-ui/download"}
                                 (:account opts)   (assoc :account (:account opts))
                                 (:thread-id opts) (assoc :thread_id (:thread-id opts))
                                 (get (:message-ids opts) i)
                                 (assoc :message_id (get (:message-ids opts) i))))))
              (swap! seen conj cid)
              (swap! added inc)
              (println (str "+ " cid "  " (g "subject")))))))
      (when (seq @lines)
        (fs/appendFileSync index-path (str (str/join "\n" @lines) "\n") "utf8"))
      (println (str "ingested " @added " new message(s); index: " index-path)))))

(apply -main (drop 3 (js->clj js/process.argv)))

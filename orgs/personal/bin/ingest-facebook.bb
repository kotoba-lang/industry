#!/usr/bin/env bb
;; ingest-facebook.bb — parse a Facebook "Download Your Information" (DYI) HTML export
;; into normalized JSONL indexes + a summary facts EDN, mirroring the takeout/imessage
;; ingest pattern (ADR-0010: files=正本, Datomic=再構築可能な索引).
;;
;; The FB DYI HTML format is regular: every data item is a `_a6-g` block with
;;   name/sender  -> class "_2ph_ _a6-h _a6-i"
;;   content      -> class "_2ph_ _a6-p"   (text in nested <div>, media in <a href>)
;;   timestamp    -> class "_a72d"          (e.g. "1月 19, 2026 6:46:46 PM")
;;
;; USAGE:
;;   orgs/personal/bin/ingest-facebook.bb <export-dir> <out-dir> [account] [export-date]
;; e.g.
;;   orgs/personal/bin/ingest-facebook.bb \
;;     orgs/personal/social/facebook/jun784/2026-06-15/export \
;;     orgs/personal/social/facebook/jun784/2026-06-15 jun784 2026-06-15
;;
;; Writes:
;;   <out-dir>/index/{profile,friends,threads,messages,posts,activity}.jsonl
;;   orgs/personal/facts/facebook.edn   (summary consumed by warehouse.load :datasrc/facebook)
(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[cheshire.core :as json]
         '[babashka.fs :as fs])

;; ---------------------------------------------------------------- html helpers
(def ^:private entities
  {"&amp;" "&" "&lt;" "<" "&gt;" ">" "&quot;" "\"" "&#039;" "'" "&#39;" "'" "&nbsp;" " "})

(defn unescape [s]
  (when s
    (-> (reduce (fn [a [k v]] (str/replace a k v)) s entities)
        (str/replace #"&#(\d+);" (fn [[_ n]] (str (char (parse-long n))))))))

(defn strip
  "Tag-strip to single-line text (block tags -> space, collapse whitespace)."
  [s]
  (when s
    (-> s
        (str/replace #"(?is)<(br|/p|/div|/li|/h[1-6])[^>]*>" "\n")
        (str/replace #"(?s)<[^>]+>" " ")
        unescape
        (str/replace #"[ \t]+" " ")
        (str/replace #"\s*\n\s*" "\n")
        (str/replace #"\n{2,}" "\n")
        str/trim)))

(defn first-group [re s] (some-> (re-find re s) second str/trim))

(defn hrefs [s] (->> (re-seq #"href=\"([^\"]+)\"" (or s "")) (map second) distinct vec))

;; FB Japanese timestamp "1月 19, 2026 6:46:46 PM" -> "2026-01-19 18:46:46"
(def ^:private ts-re #"(\d{1,2})月\s+(\d{1,2}),\s+(\d{4})\s+(\d{1,2}):(\d{2}):(\d{2})\s+(AM|PM)")
(defn norm-ts [raw]
  (when raw
    (when-let [[_ mo d y h mi s ap] (re-find ts-re raw)]
      (let [h (parse-long h)
            h (cond (and (= ap "PM") (< h 12)) (+ h 12)
                    (and (= ap "AM") (= h 12)) 0
                    :else h)]
        (format "%s-%02d-%02d %02d:%s:%s"
                y (parse-long mo) (parse-long d) h mi s)))))

;; split an html document into top-level `_a6-g` sibling blocks
(defn blocks [html]
  (let [parts (str/split html #"class=\"_a6-g\"")]
    (rest parts)))                                 ; drop preamble before first block

(defn block->item
  "Generic FB block -> {:name :ts-raw :ts :text :links}. Returns nil for empty blocks."
  [b]
  (let [name   (first-group #"_a6-h _a6-i\"[^>]*>([^<]*)<" b)
        ts-raw (first-group #"_a72d\"?[^>]*>([^<]*)<" b)
        ;; content region: after the _a6-p div's opening tag, up to footer/timestamp
        cstart (when-let [i (str/index-of b "_a6-p")]
                 (some-> (str/index-of b ">" i) inc))
        cregion (when cstart
                  (let [tail (subs b cstart)
                        cut  (or (str/index-of tail "<footer")
                                 (str/index-of tail "_a72d")
                                 (count tail))]
                    (subs tail 0 cut)))
        text  (some-> cregion strip not-empty)
        links (vec (remove #(str/starts-with? % "#") (hrefs cregion)))]
    (when (or name ts-raw text (seq links))
      (cond-> {}
        name        (assoc :name (unescape name))
        ts-raw      (assoc :ts-raw ts-raw)
        (norm-ts ts-raw) (assoc :ts (norm-ts ts-raw))
        text        (assoc :text text)
        (seq links) (assoc :links links)))))

;; ----------------------------------------------------------------- collectors
(def out-records (atom {}))                         ; category -> vector of maps
(defn emit! [cat m] (swap! out-records update cat (fnil conj []) m))

(defn read-html [f] (slurp f))

;; ---- profile -------------------------------------------------------------
(defn parse-profile [export]
  ;; profile_information.html renders as <table><tr><th>label</th><td>value</td></tr>
  (let [f (io/file export "personal_information/profile_information/profile_information.html")]
    (when (.exists f)
      (doseq [[_ th td] (re-seq #"(?s)<tr>\s*<th>(.*?)</th>\s*<td>(.*?)</td>\s*</tr>" (read-html f))
              :let [field (strip th) value (strip td)]
              :when (and (not-empty field) (not-empty value))]
        (emit! :profile {:field field :value value})))))

;; ---- friends / followers / requests -------------------------------------
(def friend-files
  {"connections/friends/your_friends.html"            "friend"
   "connections/friends/received_friend_requests.html" "request-received"
   "connections/friends/rejected_friend_requests.html" "request-rejected"
   "connections/friends/your_post_audiences.html"      "post-audience"
   "connections/followers/people_who_followed_you.html" "follower"
   "connections/followers/who_you've_followed.html"     "following"})

(defn parse-friends [export]
  (doseq [[rel-path rel] friend-files
          :let [f (io/file export rel-path)]
          :when (.exists f)]
    (doseq [b (blocks (read-html f))
            :let [it (block->item b)]
            :when (and it (:name it) (:ts-raw it))]      ; real rows carry name+ts
      (emit! :friends (assoc (select-keys it [:name :ts-raw :ts]) :rel rel)))))

;; ---- messages ------------------------------------------------------------
(defn thread-id [dir] (.getName dir))

(defn parse-thread [dir box]
  (let [htmls (->> (file-seq dir) (filter #(re-matches #"message_\d+\.html" (.getName %))) sort)
        tid   (thread-id dir)
        raw   (apply str (map read-html htmls))
        ;; title + participants live in the preamble of the first html
        first-html (read-html (first htmls))
        title (some-> (first-group #"<title>([^<]*)</title>" first-html) unescape str/trim)
        participants (some-> (first-group #"参加者[:：]\s*([^<]+)<" first-html)
                             unescape str/trim
                             (str/split #"[、,]") (->> (map str/trim) (remove str/blank?) vec))
        msgs  (->> (blocks raw)
                   (keep block->item)
                   (filter :ts-raw)                       ; real messages carry _a72d
                   (filter #(or (:name %) (:text %) (:links %)))
                   (mapv (fn [m]
                           (cond-> (assoc (select-keys m [:ts-raw :ts :text]) :thread tid)
                             (:name m)  (assoc :sender (:name m))
                             (:links m) (assoc :media (count (:links m)))))))]
    (doseq [m (map #(assoc % :box box) msgs)] (emit! :messages m))
    (let [ts (->> msgs (keep :ts) sort)]
      (emit! :threads
             (cond-> {:thread tid :box box :messages (count msgs)
                      :path (str (.getPath dir))}
               title         (assoc :title title)
               (seq participants) (assoc :participants participants)
               (seq ts)      (assoc :from (first ts) :to (last ts)))))))

;; message folders that hold thread dirs (inbox + E2EE-cutover history + any others)
(def message-boxes ["inbox" "e2ee_cutover" "archived_threads" "filtered_threads" "message_requests"])

(defn parse-messages [export]
  (doseq [box message-boxes
          :let [root (io/file export "your_facebook_activity/messages" box)]
          :when (.isDirectory root)
          d (->> (.listFiles root) (filter #(.isDirectory %)) sort)
          :when (some #(re-matches #"message_\d+\.html" (.getName %)) (.listFiles d))]
    (parse-thread d box)))

;; ---- messages (JSON: E2EE secure-storage export) -------------------------
;; The Messenger "secure storage / E2EE chats" download is JSON (not the DYI HTML).
;; Schema (classic Meta): {:participants [{:name}] :title :messages [{:sender_name
;; :timestamp_ms :content :photos [..] :share {..}}] :thread_path}. This path is
;; structure-agnostic: it finds every *.json that looks like a thread, so it works
;; regardless of the ZIP's top-level layout. NOTE: verify against the real file on
;; first arrival (E2EE export schema may differ slightly).
(import '[java.time Instant ZoneId] '[java.time.format DateTimeFormatter])
(def ^:private jst (ZoneId/of "Asia/Tokyo"))
(def ^:private ts-fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))
(defn ms->iso [ms] (when (number? ms) (.format (.atZone (Instant/ofEpochMilli (long ms)) jst) ts-fmt)))

(defn fix-mojibake
  "Meta JSON encodes UTF-8 bytes as Latin-1 escapes; re-decode if it looks garbled."
  [s]
  (if (and (string? s) (re-find #"[-ÿ]" s))
    (try (String. (.getBytes ^String s "ISO-8859-1") "UTF-8") (catch Exception _ s))
    s))

(defn thread-json? [m]
  (and (map? m) (sequential? (:messages m)) (contains? m :participants)))

(defn parse-thread-json [f box]
  (let [j (json/parse-string (slurp f) true)
        tid   (.getName (.getParentFile f))
        title (some-> (:title j) fix-mojibake not-empty)
        parts (->> (:participants j) (keep #(fix-mojibake (:name %))) (remove str/blank?) vec)
        msgs  (->> (:messages j)
                   (mapv (fn [m]
                           (cond-> {:thread tid :box box}
                             (:sender_name m)  (assoc :sender (fix-mojibake (:sender_name m)))
                             (fix-mojibake (:content m)) (assoc :text (fix-mojibake (:content m)))
                             (:timestamp_ms m) (assoc :ts (ms->iso (:timestamp_ms m))
                                                       :ts-raw (str (:timestamp_ms m)))
                             (seq (:photos m)) (assoc :media (count (:photos m))))))
                   (sort-by #(or (:ts %) "")) vec)]
    (doseq [m msgs] (emit! :messages m))
    (let [ts (->> msgs (keep :ts) sort)]
      (emit! :threads (cond-> {:thread tid :box box :messages (count msgs) :path (str (.getPath f))}
                        title (assoc :title title)
                        (seq parts) (assoc :participants parts)
                        (seq ts) (assoc :from (first ts) :to (last ts)))))))

(defn parse-messages-json
  "Recursively ingest every thread-shaped *.json under root (E2EE secure-storage)."
  [root]
  (doseq [f (->> (file-seq (io/file root))
                 (filter #(str/ends-with? (.getName %) ".json"))
                 (filter #(re-find #"(?i)message" (.getName %))) sort)
          :let [j (try (json/parse-string (slurp f) true) (catch Exception _ nil))]
          :when (thread-json? j)]
    ;; box = the messages-subfolder name if present, else "e2ee"
    (let [p (.getPath f) box (or (second (re-find #"messages/([^/]+)/" p)) "e2ee")]
      (parse-thread-json f box))))

;; ---- posts ---------------------------------------------------------------
(defn parse-posts [export]
  (doseq [f (->> (file-seq (io/file export "your_facebook_activity/posts"))
                 (filter #(str/ends-with? (.getName %) ".html")))]
    (doseq [b (blocks (read-html f))
            :let [it (block->item b)]
            :when (and it (:ts-raw it) (or (:text it) (:links it) (:name it)))]
      (emit! :posts (assoc (select-keys it [:name :ts-raw :ts :text :links])
                           :file (.getName f))))))

;; ---- generic activity catch-all (groups/events/pages/comments/...) -------
(def activity-cats
  ["groups" "events" "pages" "comments_and_reactions" "reviews" "polls"
   "fundraisers" "facebook_marketplace" "facebook_payments" "saved_items_and_collections"
   "activity_you're_tagged_in" "other_activity"])

(defn parse-activity [export]
  (doseq [cat activity-cats
          :let [root (io/file export "your_facebook_activity" cat)]
          :when (.exists root)
          f (->> (file-seq root) (filter #(str/ends-with? (.getName %) ".html")))]
    (doseq [b (blocks (read-html f))
            :let [it (block->item b)]
            :when (and it (:ts-raw it) (or (:text it) (:links it) (:name it)))]
      (emit! :activity (assoc (select-keys it [:name :ts-raw :ts :text :links])
                              :category cat :file (.getName f))))))

;; ------------------------------------------------------------------- output
(defn write-jsonl [dir cat recs]
  (let [f (io/file dir (str (name cat) ".jsonl"))]
    (with-open [w (io/writer f)]
      (doseq [r recs] (.write w (str (json/generate-string r) "\n"))))
    [(name cat) (count recs)]))

(defn date-range [recs]
  (let [ts (->> recs (keep :ts) sort)]
    (when (seq ts) {:from (first ts) :to (last ts)})))

(defn top-contacts [msgs]
  (->> msgs (keep :sender) frequencies (sort-by val >) (take 20)
       (mapv (fn [[k v]] [k v]))))

(defn -main [& [export out account export-date]]
  (when-not (and export out)
    (binding [*out* *err*]
      (println "USAGE: ingest-facebook.bb <export-dir> <out-dir> [account] [export-date]"))
    (System/exit 2))
  (let [export (str export) out (str out)
        idx (io/file out "index")]
    (.mkdirs idx)
    (println (str "[facebook] parsing " export " ..."))
    (parse-profile  export)
    (parse-friends  export)
    (parse-messages export)
    (when (empty? (:messages @out-records))   ; HTML 形式でなければ JSON(E2EE secure-storage)を試す
      (println "[facebook] no HTML threads — trying JSON (E2EE secure-storage) ...")
      (parse-messages-json export))
    (parse-posts    export)
    (parse-activity export)
    (let [recs @out-records
          counts (into {} (for [[cat rs] recs] (write-jsonl idx cat rs)))
          msgs (:messages recs)
          facts {:facebook/account (or account "jun784")
                 :facebook/export-date (or export-date "unknown")
                 :facebook/source-path (str/replace export #".*/(orgs/personal/.*)" "$1")
                 :facebook/index-path (str/replace (.getPath idx) #".*/(orgs/personal/.*)" "$1")
                 :facebook/counts
                 {:profile  (count (:profile recs []))
                  :friends  (count (:friends recs []))
                  :threads  (count (:threads recs []))
                  :messages (count (:messages recs []))
                  :posts    (count (:posts recs []))
                  :activity (count (:activity recs []))}
                 :facebook/messages-date-range (date-range msgs)
                 :facebook/posts-date-range    (date-range (:posts recs))
                 :facebook/top-message-contacts (top-contacts msgs)
                 :facebook/friends-by-rel
                 (->> (:friends recs []) (map :rel) frequencies)
                 :facebook/activity-by-category
                 (->> (:activity recs []) (map :category) frequencies)}
          facts-file (io/file (str (fs/parent (fs/parent (fs/absolutize *file*)))) "facts" "facebook.edn")]
      (spit facts-file (with-out-str (clojure.pprint/pprint facts)))
      (println "[facebook] index/*.jsonl:")
      (doseq [[c n] (sort counts)] (println (format "    %-10s %6d" c n)))
      (println (str "[facebook] facts -> " (.getPath facts-file)))
      (println (str "[facebook] threads=" (:threads (:facebook/counts facts))
                    " messages=" (:messages (:facebook/counts facts))
                    " friends=" (:friends (:facebook/counts facts))
                    " posts=" (:posts (:facebook/counts facts)))))))

(apply -main *command-line-args*)

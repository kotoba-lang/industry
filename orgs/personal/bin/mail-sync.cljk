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
;; mail-sync.nbb — personal warehouse の定期メール ingest（全段を babashka 1 本に統合）。
;;
;; sh 版 mail-sync.sh + ingest-gmail-batch.py を置換する Clojure 実装（ADR-0009）。
;;   registry(mail.sync=true) 走査 → per-account access token(Keychain) →
;;   Gmail REST(format=raw) で newer_than:<window> 差分取得 →
;;   mail/messages/<cid>.eml (cid=sha256(RFC822)) + index.jsonl(完全互換) →
;;   git annex add → copy --to b2(hybrid暗号) → pointer commit。
;;   cid と message_id で冪等。窓重複は無害。token 無しアカウントは警告 skip。
;;   index.jsonl は annex 管理(PII)なので get→unlock→追記→annex add で re-lock。
;;
;; usage: mail-sync.nbb            ; registry の全 mail.sync アカウントを同期
;;        mail-sync.nbb <slug>     ; 1 アカウントのみ
;; launchd: com.junkawasaki.mail-sync（15分間隔）が呼ぶ。

(require ']
         '[clojure.string :as str]
         '         ')
(import '[java.security MessageDigest]
        '[java.util Base64]
        '[java.time LocalDate])

(def repo "/Users/junkawasaki/github/com-junkawasaki")
(def base (str repo "/orgs/personal"))
(def bin  (str base "/bin"))
(def msgs (str base "/mail/messages"))
(def index (str msgs "/index.jsonl"))
(def msgs-rel "orgs/personal/mail/messages")
(def api "https://gmail.googleapis.com/gmail/v1/users/me")
(def log-file (str (.-homedir __os) "/.mail-sync/sync.log"))

(def kg-primary "07C2382AE713776AE86663BAD0CB4AA0C4843A5D")
(def kg-sub     "9B9892A6C39ADD70A20C76E38B75235614E0D091")
(def gpg-preset "/opt/homebrew/Cellar/gnupg/2.4.8/libexec/gpg-preset-passphrase")

(defn log [& xs]
  (let [line (str "[" (str/replace (str (java.time.LocalDateTime/now)) "T" " ") "] "
                  (str/join " " xs))]
    (io/make-parents log-file)
    (spit log-file (str line "\n") :append true)
    (println line)))

(defn git [& args]
  (apply __shell {:dir repo :out :string :err :string :continue true} "git" args))

;; ---- Keychain / GPG ----------------------------------------------------------
(defn keychain [service]
  (let [{:keys [exit out]} (__sh "security" "find-generic-password" "-s" service "-w")]
    (when (zero? exit) (str/trim out))))

(defn preset-pass []
  (when-let [p (keychain "gpg:personal-data")]
    (doseq [k [kg-primary kg-sub]]
      (__sh {:in p} gpg-preset "--preset" k))))

(defn token [slug]
  (let [{:keys [exit out]} (__sh (str bin "/google-auth.py") "token" slug)]
    (when (and (zero? exit) (seq (str/trim out))) (str/trim out))))

;; ---- registry ----------------------------------------------------------------
(defn mail-accounts []
  ;; TSV: slug<TAB>provider<TAB>window
  (->> (:out (__sh (str bin "/registry") "mail-accounts"))
       str/split-lines
       (remove str/blank?)
       (map #(zipmap [:slug :provider :window] (str/split % #"\t")))))

;; ---- Gmail API ---------------------------------------------------------------
(defn curl-get [url tok]
  (let [{:keys [out]} (__sh "curl" "-s" "-w" "\n%{http_code}" "-H" (str "Authorization: Bearer " tok) url)
        lines (str/split-lines out)
        status (parse-long (last lines))
        body (str/join "\n" (butlast lines))]
    {:status status :body body}))

(defn api-get [path tok params]
  (let [qs (when (seq params)
             (str "?" (str/join "&" (map (fn [[k v]]
                                           (str (name k) "="
                                                (java.net.URLEncoder/encode (str v) "UTF-8")))
                                         params))))
        url (str api path qs)
        {:keys [status body]} (curl-get url tok)]
    (if (= 200 status)
      (__json-parse body true)
      (throw (ex-info (str "gmail api " status) {:status status :body (subs body 0 (min 300 (count body)))})))))

(defn list-ids [query tok cap]
  (loop [ids [] page nil]
    (if (>= (count ids) cap)
      (vec (take cap ids))
      (let [params (cond-> {:q query :maxResults (min 500 (- cap (count ids)))}
                     page (assoc :pageToken page))
            resp (api-get "/messages" tok params)
            ids' (into ids (map :id (:messages resp)))
            page' (:nextPageToken resp)]
        (if page' (recur ids' page') (vec (take cap ids')))))))

;; ---- index（既存 .jsonl と完全互換のレコード） --------------------------------
(defn known []
  (if (.exists (__path.resolve index))
    (reduce (fn [acc line]
              (if (str/blank? line) acc
                  (let [r (try (__json-parse line true) (catch Exception _ nil))]
                    (cond-> acc
                      (:cid r) (update :cids conj (:cid r))
                      (:message_id r) (update :mids conj (:message_id r))))))
            {:cids #{} :mids #{}}
            (line-seq (js/undefined ; io/reader-removed index)))
    {:cids #{} :mids #{}}))

(defn sha256-hex [^bytes b]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") b)]
    (apply str (map #(format "%02x" %) d))))

(defn header [^String raw ^String name]
  ;; RFC822 ヘッダの素朴抽出（unfold 対応）。索引メタ用途。
  (let [head (first (str/split raw #"\r?\n\r?\n" 2))
        lines (str/split-lines head)
        pat (str/lower-case (str name ":"))]
    (loop [ls lines, acc nil]
      (cond
        (empty? ls) (some-> acc str/trim)
        acc (if (re-find #"^\s" (first ls))
              (recur (rest ls) (str acc " " (str/trim (first ls))))
              (some-> acc str/trim))
        (str/starts-with? (str/lower-case (first ls)) pat)
        (recur (rest ls) (subs (first ls) (count (str name ":"))))
        :else (recur (rest ls) acc)))))

(defn addr-list [^String v]
  (->> (str/split (or v "") #",") (map str/trim) (remove str/blank?) vec))

(defn ingest-account! [{:keys [slug provider window]} state]
  (cond
    (not= provider "google")
    (do (log "SKIP" slug ": provider" provider "未実装(bb)") state)

    (nil? (token slug))
    (do (log "SKIP" slug ": no token (bootstrap: google-auth.py login" (str slug ")")) state)

    :else
    (let [tok (token slug)
          query (str "newer_than:" window)
          _ (log "sync" slug (str "(" query ")"))
          ids (list-ids query tok 2000)
          _ (log " " slug "matched" (count ids) "message(s)")]
      (io/make-parents (str msgs "/x"))
      (with-open [idx (io/writer index :append true)]
        (reduce
         (fn [st mid]
           (if (contains? (:mids st) mid)
             st
             (let [msg (api-get (str "/messages/" mid) tok {:format "raw"})
                   raw-bytes (.decode (Base64/getUrlDecoder) ^String (:raw msg))
                   raw (String. raw-bytes "UTF-8")
                   cid (sha256-hex raw-bytes)]
               ;; cid名の .eml が既存(= 同一RFC822が別アカ/別sourceで保存済み、
               ;; あるいは index と FS の不整合)なら上書きしない。locked annex
               ;; symlink への書込は Permission denied になるため存在チェックで回避。
               (let [eml (__path.resolve msgs (str cid ".eml"))]
                 (when (and (not (contains? (:cids st) cid))
                            (not (.exists eml)))
                   (io/copy raw-bytes eml)))
               (let [rec {:cid cid :sha256 cid :size (count raw-bytes)
                          :account slug
                          :message_id mid
                          :rfc822_message_id (header raw "Message-ID")
                          :thread_id (:threadId msg)
                          :date (header raw "Date")
                          :from (header raw "From")
                          :to (addr-list (header raw "To"))
                          :cc (addr-list (header raw "Cc"))
                          :subject (header raw "Subject")
                          :labels (:labelIds msg)
                          :eml_path (str "mail/messages/" cid ".eml")
                          :source "gmail-api/raw"}]
                 (.write idx (str (__json-gen rec) "\n"))
                 (-> st (update :cids conj cid) (update :mids conj mid)
                     (update :added inc))))))
         (assoc state :synced (inc (:synced state)))
         ids)))))

;; ---- seal: annex → b2 → commit ----------------------------------------------
(defn seal! [synced]
  (git "annex" "add" msgs-rel)
  (git "annex" "copy" "--to" "b2" "-J4" msgs-rel)
  (let [staged (git "diff" "--cached" "--quiet" "--" msgs-rel)]
    (when-not (zero? (:exit staged))
      (git "commit" "-m"
           (str "personal(mail): periodic sync " (LocalDate/now) " (" synced " account(s), bb)")
           "--" msgs-rel)
      (log "committed new mail pointers"))))

;; ---- main --------------------------------------------------------------------
(defn -main [& args]
  (log "=== mail-sync start (bb) ===")
  (preset-pass)
  (git "annex" "get" (str msgs-rel "/index.jsonl"))
  (git "annex" "unlock" (str msgs-rel "/index.jsonl"))
  ;; unlock が効いて index が書込可能(=symlinkでない)であることを保証。
  ;; 効いていないと io/writer の append が無に帰し silently 0件になる(過去バグ)。
  (when (java.nio.file.Files/isSymbolicLink (.toPath (__path.resolve index)))
    (git "annex" "get" (str msgs-rel "/index.jsonl"))
    (git "annex" "unlock" (str msgs-rel "/index.jsonl"))
    (when (java.nio.file.Files/isSymbolicLink (.toPath (__path.resolve index)))
      (throw (ex-info "index.jsonl が unlock できず書込不可（annex lock状態）" {:path index}))))
  (let [only (first args)
        accts (cond->> (mail-accounts) only (filter #(= (:slug %) only)))
        init (merge (known) {:synced 0 :added 0})
        final (reduce (fn [st a] (try (ingest-account! a st)
                                      (catch Exception e
                                        (log "FAIL" (:slug a) (.getMessage e)) st)))
                      init accts)]
    (seal! (:synced final))
    (log "=== mail-sync done (" (:synced final) "account(s),"
         (:added final) "new .eml, bb) ===")))

(apply -main *command-line-args*)

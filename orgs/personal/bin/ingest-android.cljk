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
;; ingest-android.nbb — 接続中の Android (adb) を orgs/personal/device/android/ へ snapshot ingest。
;;
;; sh 版 ingest-android.sh + 埋込み python を置換する Clojure 実装（ADR-0009 系譜）。
;;   adb getprop/dumpsys → system.txt、pm list → packages*.txt、
;;   content query (contacts/sms/call_log) → *-raw.txt →
;;   "Row: N k=v, k=v" パース → contacts.json / sms.jsonl / call_log.jsonl。
;; PII (sms/call_log/contacts) は git-annex 管理 (encryption=hybrid → B2暗号文のみ)。
;; 点in時間の snapshot であり継続同期ではない。annex add/copy/commit は呼び出し側で行う。
;; 前回 snapshot が annex lock 済 (dangling symlink) でも、書込前に除去するので再実行可。
;;
;; usage: ingest-android.nbb [adb-serial]   (default: 最初の authorized device)

(require ']
         '[clojure.string :as str]
         '         ')
(import '[java.time Instant]
        '[java.time.format DateTimeFormatter]
        '[java.time.temporal ChronoUnit])

(def repo "/Users/junkawasaki/github/com-junkawasaki")
(def out  (str repo "/orgs/personal/device/android"))

(def ts (.format DateTimeFormatter/ISO_INSTANT (.truncatedTo (Instant/now) ChronoUnit/SECONDS)))

(defn adb-devices []
  (->> (str/split-lines (:out (__sh "adb" "devices")))
       (drop 1)
       (keep #(let [[serial state] (str/split % #"\s+")]
                (when (= state "device") serial)))))

(def serial
  (or (first *command-line-args*)
      (first (adb-devices))
      (do (binding [*out* *err*] (println "no authorized adb device"))
          (.exit js/process 1))))

(defn adb [& args]
  (let [{:keys [exit out]} (apply __sh "adb" "-s" serial args)]
    (if (zero? exit) (str/replace out "\r" "") "")))

(defn getprop [k] (str/trim (adb "shell" "getprop" k)))

(defn write! [fname ^String content]
  ;; 旧 snapshot の annex symlink (lock済=dangling) が残っていると追記/上書きが
  ;; ENOENT/EACCES になるため、書込前に必ず除去 (履歴と B2 に旧キーは残る)。
  (let [f (__path.resolve out fname)]
    (java.nio.file.Files/deleteIfExists (.toPath f))
    (spit f content)))

;; ---- device identity / state ---------------------------------------------------
(defn system-txt []
  (str "ingested_at: " ts "\n"
       "serial: " serial "\n"
       (str/join (for [k ["ro.product.model" "ro.product.manufacturer" "ro.product.name"
                          "ro.build.version.release" "ro.build.version.security_patch"
                          "ro.build.fingerprint"]]
                   (str k ": " (getprop k) "\n")))
       "--- battery ---\n"
       (str/join "\n" (map str/triml (str/split-lines (adb "shell" "dumpsys" "battery")))) "\n"
       "--- storage ---\n"
       (adb "shell" "df" "-h" "/data")))

;; ---- packages -------------------------------------------------------------------
(defn packages [& flags]
  ;; ASCII 順 sort (__sh 版はロケール依存 `sort` だった → 決定的な順序に変更)
  (->> (str/split-lines (apply adb "shell" "pm" "list" "packages" flags))
       (keep #(second (re-matches #"package:(.*)" %)))
       sort
       (map #(str % "\n"))
       str/join))

;; ---- content query "Row: N k=v, k=v" → maps ------------------------------------
(defn content-query [uri projection]
  (adb "shell" "content" "query" "--uri" uri "--projection" projection))

(defn parse-rows [raw]
  (for [ln (str/split-lines raw)
        :let [m (re-matches #"Row:\s*\d+\s*(.*)" (str/trim ln))]
        :when m]
    (into {}
          (for [p (str/split (second m) #", (?=[A-Za-z_]+=)")
                :let [[_ k v] (re-matches #"(?s)([^=]+)=(.*)" p)]
                :when k]
            [(str/trim k) (when (not= v "NULL") v)]))))

(defn jsonl [recs] (str/join (map #(str (__json-gen %) "\n") recs)))

;; ---- main -----------------------------------------------------------------------
(io/make-parents (str out "/x"))
(println (str "android ingest: " serial " @ " ts))

(write! "getprop.txt" (adb "shell" "getprop"))
(write! "system.txt" (system-txt))
(write! "packages.txt" (packages))
(write! "packages-3rdparty.txt" (packages "-3"))

(let [contacts-raw (content-query "content://contacts/phones/" "display_name:number")
      sms-raw      (content-query "content://sms/" "_id:address:date:type:body")
      call-raw     (content-query "content://call_log/calls" "_id:number:date:duration:type")
      contacts (for [d (parse-rows contacts-raw)]
                 {:name (d "display_name") :number (d "number")})
      sms      (for [d (parse-rows sms-raw)]            ; type 1=received 2=sent
                 {:id (d "_id") :address (d "address") :date (d "date")
                  :type (d "type") :body (d "body")})
      calls    (for [d (parse-rows call-raw)]           ; type 1=in 2=out 3=missed
                 {:id (d "_id") :number (d "number") :date (d "date")
                  :duration (d "duration") :type (d "type")})]
  (write! "contacts-raw.txt" contacts-raw)
  (write! "sms-raw.txt" sms-raw)
  (write! "call_log-raw.txt" call-raw)
  (write! "contacts.json" (__json-gen contacts {:pretty true}))
  (write! "sms.jsonl" (jsonl sms))
  (write! "call_log.jsonl" (jsonl calls))
  (println (str "contacts=" (count contacts)
                " sms=" (count sms)
                " call_log=" (count calls)))
  (println (str "android ingest complete -> " out "/")))

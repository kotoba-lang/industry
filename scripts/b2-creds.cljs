#!/usr/bin/env nbb
;; b2-creds.cljs — B2 (S3 互換) 認証情報を解決して出力する。
;; 解決順は manifest/repos.edn の :b2 :credentials :order（既定 env→1password→keychain）。
;;   :env        … 指定名の環境変数
;;   :1password  … op read "op://vault/item/field"（op に signin 済みが前提）
;;   :keychain   … security find-generic-password -s <service> -a <account> -w（macOS）
;; 参照先（op:// パス / keychain service）は秘密ではないので repos.edn に置く。
;; 実値（key/secret）は出力時のみ取得し、リポジトリには一切置かない。
;;
;; 出力:
;;   nbb scripts/b2-creds.cljs            ; shell 用 export 行（eval して使う）
;;   nbb scripts/b2-creds.cljs --json     ; {"B2_KEY_ID":...,...}（プログラム用）
;;   eval "$(nbb scripts/b2-creds.cljs)"  ; 環境に流し込む
(require '[scripts.nbb-compat :refer [slurp spit file-seq format]]
         '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.java.shell :refer [sh]]
         '[cheshire.core :as json]
         '[clojure.java.io :as io])

(def root (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))

;; repos.edn は manifest/edn-datomize.cljs により tx-data 形式に変換済み。
;; 元のトップレベル map(:b2 等)を復元する。
(defn- unblob [v]
  (if (string? v)
    (try (let [parsed (edn/read-string v)] (if (coll? parsed) parsed v))
         (catch :default _ v))
    v))

(defn- reconstitute-entity [tx-data]
  (into {} (map (fn [[k v]] [(keyword (name k)) (unblob v)]))
        (dissoc (first tx-data) :db/id)))

(def cfg  (reconstitute-entity (-> (slurp (io/file root "manifest" "repos.edn")) edn/read-string)))
(def cred (get-in cfg [:b2 :credentials]))
(def order (or (:order cred) [:env :1password :keychain]))
(def fields [:key-id :app-key :bucket])
(def env-out {:key-id "B2_KEY_ID" :app-key "B2_APP_KEY" :bucket "B2_BUCKET"})

(defn from-env [field]
  (some-> (get-in cred [:env field]) scripts.nbb-compat/getenv (#(when-not (str/blank? %) %))))

(defn from-1password [field]
  (when-let [ref (get-in cred [:1password field])]
    (let [{:keys [exit out]} (sh "op" "read" ref)]
      (when (zero? exit) (str/trim out)))))

(defn from-keychain [field]
  (let [kc  (:keychain cred)
        svc (:service kc)]
    (when svc
      (if (:combined kc)
        ;; 単一アイテム方式: service=b2:<bucket> の generic-password 1 件に
        ;;   account = key-id, password = app-key を入れ、bucket は service 名の
        ;;   "b2:" 以降(または :bucket 明示)から導く。
        ;;   実例(macOS): security add-generic-password -s b2:<bucket> -a <KEY_ID> -w <APP_KEY>
        (case field
          :app-key (let [{:keys [exit out]} (sh "security" "find-generic-password" "-s" svc "-w")]
                     (when (zero? exit) (str/trim out)))
          :key-id  (let [{:keys [exit out]} (sh "security" "find-generic-password" "-s" svc "-g")]
                     (when (zero? exit)
                       (some-> (re-find #"\"acct\"<blob>=\"([^\"]*)\"" out) second)))
          :bucket  (or (:bucket kc)
                       (some-> (re-find #"^b2:(.+)$" svc) second))
          nil)
        ;; 従来方式: service 下に field ごとの account(値は各 item の password)。
        (let [acct (get kc (keyword (str (name field) "-account")))]
          (when acct
            (let [{:keys [exit out]} (sh "security" "find-generic-password" "-s" svc "-a" acct "-w")]
              (when (zero? exit) (str/trim out)))))))))

(defn resolve-field [field]
  (some (fn [src]
          (try
            (case src
              :env       (from-env field)
              :1password (from-1password field)
              :keychain  (from-keychain field)
              nil)
            (catch :default _ nil)))
        order))

(let [vals (into {} (for [f fields] [f (resolve-field f)]))
      missing (filter #(str/blank? (vals %)) fields)
      json?  (some #{"--json"} *command-line-args*)]
  (when (seq missing)
    (binding [*out* *err*]
      (println (str "b2-creds: 解決できない項目: " (str/join ", " (map name missing))
                    " (order=" (str/join "→" (map name order)) ")")))
    (scripts.nbb-compat/exit 1))
  (let [out {"B2_KEY_ID" (:key-id vals)
             "B2_APP_KEY" (:app-key vals)
             "B2_BUCKET" (:bucket vals)
             ;; git-annex の S3 special remote 用
             "AWS_ACCESS_KEY_ID" (:key-id vals)
             "AWS_SECRET_ACCESS_KEY" (:app-key vals)}]
    (if json?
      (println (json/generate-string out))
      ;; shell-safe な単一引用符クォート('→'\'' に置換)。$ や ` を含む secret でも安全。
      (doseq [[k v] out]
        (println (str "export " k "='" (str/replace v "'" "'\\''") "'"))))))

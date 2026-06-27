#!/usr/bin/env bb
;; b2-creds.bb — B2 (S3 互換) 認証情報を解決して出力する。
;; 解決順は manifest/repos.edn の :b2 :credentials :order（既定 env→1password→keychain）。
;;   :env        … 指定名の環境変数
;;   :1password  … op read "op://vault/item/field"（op に signin 済みが前提）
;;   :keychain   … security find-generic-password -s <service> -a <account> -w（macOS）
;; 参照先（op:// パス / keychain service）は秘密ではないので repos.edn に置く。
;; 実値（key/secret）は出力時のみ取得し、リポジトリには一切置かない。
;;
;; 出力:
;;   bb scripts/b2-creds.bb            ; shell 用 export 行（eval して使う）
;;   bb scripts/b2-creds.bb --json     ; {"B2_KEY_ID":...,...}（プログラム用）
;;   eval "$(bb scripts/b2-creds.bb)"  ; 環境に流し込む
(require '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.java.shell :refer [sh]]
         '[cheshire.core :as json]
         '[clojure.java.io :as io])

(def root (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))
(def cfg  (-> (slurp (io/file root "manifest" "repos.edn")) edn/read-string))
(def cred (get-in cfg [:b2 :credentials]))
(def order (or (:order cred) [:env :1password :keychain]))
(def fields [:key-id :app-key :bucket])
(def env-out {:key-id "B2_KEY_ID" :app-key "B2_APP_KEY" :bucket "B2_BUCKET"})

(defn from-env [field]
  (some-> (get-in cred [:env field]) System/getenv (#(when-not (str/blank? %) %))))

(defn from-1password [field]
  (when-let [ref (get-in cred [:1password field])]
    (let [{:keys [exit out]} (sh "op" "read" ref)]
      (when (zero? exit) (str/trim out)))))

(defn from-keychain [field]
  (let [svc (get-in cred [:keychain :service])
        acct (get-in cred [:keychain (keyword (str (name field) "-account"))])]
    (when (and svc acct)
      (let [{:keys [exit out]} (sh "security" "find-generic-password" "-s" svc "-a" acct "-w")]
        (when (zero? exit) (str/trim out))))))

(defn resolve-field [field]
  (some (fn [src]
          (try
            (case src
              :env       (from-env field)
              :1password (from-1password field)
              :keychain  (from-keychain field)
              nil)
            (catch Exception _ nil)))
        order))

(let [vals (into {} (for [f fields] [f (resolve-field f)]))
      missing (filter #(str/blank? (vals %)) fields)
      json?  (some #{"--json"} *command-line-args*)]
  (when (seq missing)
    (binding [*out* *err*]
      (println (str "b2-creds: 解決できない項目: " (str/join ", " (map name missing))
                    " (order=" (str/join "→" (map name order)) ")")))
    (System/exit 1))
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

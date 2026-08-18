#!/usr/bin/env nbb
;; b2-creds.cljs — B2 (S3 互換) 認証情報を解決して出力する。
;; 解決ロジックは kotoba-lang/secret-resolve（orgs/kotoba-lang/secret-resolve）
;; に委譲する（ADR-2607161000）。以前はこのファイル自身が env→1Password→
;; Keychain の解決を持っていたが、`com-backblaze-secure` にも同じロジックが
;; 独立に複製された上、両方に同一のバグ（`security -g` が平文パスワードを
;; stderr に出す／`op read` がハングする）があった — secret-resolve に一本化。
;;
;; 解決順は manifest/repos.edn の :b2 :credentials :order（既定 env→1password→keychain）。
;; 参照先（op:// パス / keychain service）は秘密ではないので repos.edn に置く。
;; 実値（key/secret）は出力時のみ取得し、リポジトリには一切置かない。
;;
;; 実行には secret-resolve の src を classpath に追加する必要がある:
;;   nbb --classpath ".:orgs/kotoba-lang/secret-resolve/src" scripts/b2-creds.cljs
;;   nbb --classpath ".:orgs/kotoba-lang/secret-resolve/src" scripts/b2-creds.cljs --json
;;   eval "$(nbb --classpath \".:orgs/kotoba-lang/secret-resolve/src\" scripts/b2-creds.cljs)"
(require '[scripts.nbb-compat :as compat :refer [slurp]]
         '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[secret-resolve.resolver :as resolver]
         '[secret-resolve.sources :as sources])

(def root (-> (compat/sh "git" "rev-parse" "--show-toplevel") :out str/trim))

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
(def fields [:key-id :app-key :bucket])

(defn- keychain-ref
  "repos.edn の :keychain 形状を secret-resolve.sources/keychain の ref 形状へ
  変換する。:combined true(単一アイテムに account=key-id, password=app-key)
  では field ごとに読み方が変わる — :bucket は subprocess を伴わない
  service 名パースなので、ここでは意図的に nil を返し resolve-field 側で
  フォールバック処理する。"
  [field]
  (let [kc (:keychain cred)]
    (when (:service kc)
      (if (:combined kc)
        (case field
          :app-key {:service (:service kc)}
          :key-id  {:service (:service kc) :field :account}
          :bucket  nil)
        ;; 従来方式: service 下に field ごとの account(値は各 item の password)。
        (when-let [acct (get kc (keyword (str (name field) "-account")))]
          {:service (:service kc) :account acct})))))

(defn- bucket-from-service-name []
  (let [kc (:keychain cred)]
    (or (:bucket kc)
        (some-> (re-find #"^b2:(.+)$" (:service kc)) second))))

(defn- resolve-field [field]
  (or (resolver/resolve1 sources/default-sources
                         {:order (:order cred [:env :1password :keychain])
                          :env (get-in cred [:env field])
                          :1password (get-in cred [:1password field])
                          :keychain (keychain-ref field)})
      (when (= field :bucket) (bucket-from-service-name))))

(let [vals (into {} (for [f fields] [f (resolve-field f)]))
      missing (filter #(str/blank? (vals %)) fields)
      json?  (some #{"--json"} *command-line-args*)]
  (when (seq missing)
    ;; js/console.error であって (binding [*out* *err*] (println …)) ではない。
    ;; nbb は *out* の束縛を無視して stdout に書く（ADR-2608130600、実測:
    ;; `nbb -e '(binding [*out* *err*] (println "x"))' 2>/dev/null` が x を出す）。
    ;; このスクリプトの stdout は機械可読で、消費側は 3 本とも exit で分岐する:
    ;;   manifest/west_annex.cljs   … JSON.parse、失敗時は err を表示
    ;;   scripts/hirameki-mirror.cljs … JSON.parse、失敗時は err を receipt に書く
    ;;   scripts/newsfeed-ingest.cljs … `export K='V'` 行を正規表現で拾う
    ;; 束縛のままだと前 2 者が表示する err が**空文字**になり、B2 鍵が解決できない
    ;; 理由が operator にもレシートにも残らない（実測 2026-08-13）。
    (js/console.error (str "b2-creds: 解決できない項目: " (str/join ", " (map name missing))
                           " (order=" (str/join "→" (map name (:order cred [:env :1password :keychain]))) ")"))
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

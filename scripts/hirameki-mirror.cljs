#!/usr/bin/env nbb
;; hirameki-mirror.cljs — 特許 corpus の**第 2 の耐久コピー**を B2 に置き、
;; 置いた直後に**復元して確かめる**。ADR-2608100400 の custody 穴。
;;
;; ## なぜ annex ではなく bundle なのか
;;
;; corpus は 780 KB の EDN で、git が最も得意な領域である。annex 化すれば正本に
;; 対する `git diff` / `git blame` を失って何も得ない（dataset.edn に記録済み）。
;; 足りないのは content-addressed store ではなく **2 人目の custodian** なので、
;; **git bundle**（履歴を丸ごと含む 1 ファイル）を object store に置く。
;;
;; bundle は annex より強い性質を 1 つ持つ: **GitHub が消えても、bundle 1 つから
;; repo 全体が復元できる。** annex は実体を持つが履歴を持たない。
;;
;; ## 「置いた」を成功と呼ばない
;;
;; 毎回、置いた直後に **B2 から取り直して clone し、HEAD が一致することを確かめる**。
;; dataset.edn は 2026-08-10 時点で `:recovery-drill :never-run` と書いていた。
;; 復元したことのないバックアップはバックアップではなく、バックアップの主張である。
;;
;; ## 使い方
;;
;;   nbb --classpath ".:scripts/nbb_compat:orgs/kotoba-lang/secret-resolve/src" \
;;     scripts/hirameki-mirror.cljs
;;   ... --dry-run     鍵の解決と bundle 作成まで（アップロードしない）
;;   ... --keep 10     B2 に残す世代数（既定 8）
;;
;; 秘密情報は **argv に載せない**（`ps` 露出）。env で子プロセスに渡す。

(require '[scripts.nbb-compat :as compat :refer [slurp spit sh]]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

(def fs (js/require "node:fs"))
(def os (js/require "node:os"))
(def path-mod (js/require "node:path"))
(def cp (js/require "node:child_process"))

(def root (str/trim (:out (sh "git" "rev-parse" "--show-toplevel"))))
(defn- abs [& parts] (apply (.-join path-mod) (clj->js (cons root parts))))
(defn- exists? [p] (.existsSync fs p))

(def argv (vec *command-line-args*))
(defn- flag? [f] (some #{f} argv))
(defn- opt [f] (let [i (.indexOf (clj->js argv) f)] (when (>= i 0) (nth argv (inc i) nil))))

(def dry-run? (flag? "--dry-run"))
(def keep-n (js/parseInt (or (opt "--keep") "8") 10))
(def corpus-dir (abs "orgs" "cloud-itonami" "hirameki-patents"))
(def ledger-path (abs "90-docs" "observatory" "hirameki-mirror.ledger.edn"))
(def prefix "hirameki-patents/")

(defn- run [cmd args {:keys [cwd env timeout]}]
  (let [r (.spawnSync cp cmd (clj->js args)
                      (clj->js (cond-> {:encoding "utf8" :maxBuffer (* 64 1024 1024)
                                        :timeout (or timeout 600000)}
                                 cwd (assoc :cwd cwd)
                                 env (assoc :env env))))]
    {:exit (if (nil? (.-status r)) 124 (.-status r))
     :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))

(defn- git [dir & args] (run "git" args {:cwd dir}))

(defn- append-ledger! [m]
  (when-not (exists? ledger-path)
    (spit ledger-path
          (str ";; hirameki-mirror.ledger.edn — append-only。1 行 1 回のミラー。\n"
               ";; :mirror/restored が false の行は「置いたが復元できていない」——\n"
               ";; それはバックアップではなく、バックアップの主張である。\n")))
  (.appendFileSync fs ledger-path (str (pr-str (assoc m :mirror/at (.toISOString (js/Date.)))) "\n")))

(defn- die! [m]
  (println (str "  ✗ " (:mirror/reason m)))
  (when-not dry-run? (append-ledger! (assoc m :mirror/ok false)))
  (js/process.exit 1))

(println (str "hirameki-mirror " (.toISOString (js/Date.)) (when dry-run? "  [--dry-run]")))

(when-not (exists? ((.-join path-mod) corpus-dir ".git"))
  (die! {:mirror/reason (str "corpus checkout が無い: " corpus-dir) :mirror/stage :precondition}))

;; ── 鍵（値は一切表示しない）───────────────────────────────────────────────
(def creds
  (let [r (run "nbb" ["--classpath" ".:scripts/nbb_compat:orgs/kotoba-lang/secret-resolve/src"
                      "scripts/b2-creds.cljs" "--json"]
               {:cwd root :timeout 120000})]
    (when-not (zero? (:exit r))
      (die! {:mirror/reason (str "B2 鍵を解決できない: " (str/trim (:err r)))
             :mirror/stage :credentials}))
    (js->clj (js/JSON.parse (:out r)))))

(def bucket (get creds "B2_BUCKET"))
(def endpoint "https://s3.us-west-004.backblazeb2.com")
(println (str "  · bucket " bucket " / " endpoint))

(def aws-env
  (clj->js (merge (compat/getenv-all)
                  {"AWS_ACCESS_KEY_ID" (get creds "AWS_ACCESS_KEY_ID")
                   "AWS_SECRET_ACCESS_KEY" (get creds "AWS_SECRET_ACCESS_KEY")
                   "AWS_DEFAULT_REGION" "us-west-004"
                   "AWS_EC2_METADATA_DISABLED" "true"})))

(defn- aws [& args]
  (run "aws" (into ["--endpoint-url" endpoint] args) {:env aws-env :timeout 600000}))

;; ── bundle を作る ────────────────────────────────────────────────────────
(def head (str/trim (:out (git corpus-dir "rev-parse" "HEAD"))))
(def tmp (.mkdtempSync fs ((.-join path-mod) (.tmpdir os) "hir-mirror-")))
(def stamp (subs (.toISOString (js/Date.)) 0 10))
(def bundle-name (str "hirameki-patents-" stamp "-" (subs head 0 7) ".bundle"))
(def bundle-path ((.-join path-mod) tmp bundle-name))

(let [r (git corpus-dir "bundle" "create" bundle-path "--all")]
  (when-not (zero? (:exit r))
    (die! {:mirror/reason (str "git bundle が失敗: " (str/trim (:err r)))
           :mirror/stage :bundle})))

;; bundle 自身の健全性は git が判定できる。壊れた bundle を上げない。
(let [r (git corpus-dir "bundle" "verify" bundle-path)]
  (when-not (zero? (:exit r))
    (die! {:mirror/reason (str "git bundle verify が失敗: " (str/trim (:err r)))
           :mirror/stage :bundle-verify})))

(def bundle-bytes (.-size (.statSync fs bundle-path)))
(def bundle-sha
  (str/trim (first (str/split (:out (run "shasum" ["-a" "256" bundle-path] {})) #"\s+"))))
(println (str "  · bundle " bundle-name " " bundle-bytes " bytes  sha256 " (subs bundle-sha 0 16) "…"))

(when dry-run?
  (println "  · --dry-run: ここまで。アップロードしない。")
  (js/process.exit 0))

;; ── 置く ────────────────────────────────────────────────────────────────
(let [r (aws "s3" "cp" bundle-path (str "s3://" bucket "/" prefix bundle-name))]
  (when-not (zero? (:exit r))
    (die! {:mirror/reason (str "アップロードが失敗: " (str/trim (:err r)))
           :mirror/stage :upload :mirror/bundle bundle-name})))

;; latest.json は「今どれが最新か」を 1 箇所で答える。bundle 名からは
;; 世代は分かるが、どれが健全に検証されたかは分からない。
(def latest
  {:bundle bundle-name :head head :bytes bundle-bytes :sha256 bundle-sha
   :at (.toISOString (js/Date.)) :bucket bucket :prefix prefix})
(let [p ((.-join path-mod) tmp "latest.edn")]
  (spit p (str (pr-str latest) "\n"))
  (let [r (aws "s3" "cp" p (str "s3://" bucket "/" prefix "latest.edn"))]
    (when-not (zero? (:exit r))
      (die! {:mirror/reason "latest.edn のアップロードが失敗" :mirror/stage :upload}))))

;; ── 復元して確かめる（ここを飛ばしたら「置いた」だけ）─────────────────────
(def restore-dir ((.-join path-mod) tmp "restore"))
(def redown ((.-join path-mod) tmp "redown.bundle"))

(let [r (aws "s3" "cp" (str "s3://" bucket "/" prefix bundle-name) redown)]
  (when-not (zero? (:exit r))
    (die! {:mirror/reason "取り直しに失敗（置いたが読めない）"
           :mirror/stage :restore :mirror/bundle bundle-name})))

(def redown-sha
  (str/trim (first (str/split (:out (run "shasum" ["-a" "256" redown] {})) #"\s+"))))
(when-not (= bundle-sha redown-sha)
  (die! {:mirror/reason (str "取り直した bundle の sha256 が一致しない: "
                             bundle-sha " vs " redown-sha)
         :mirror/stage :restore :mirror/bundle bundle-name}))

(let [r (run "git" ["clone" "--quiet" redown restore-dir] {:timeout 600000})]
  (when-not (zero? (:exit r))
    (die! {:mirror/reason (str "bundle から clone できない: " (str/trim (:err r)))
           :mirror/stage :restore :mirror/bundle bundle-name})))

(def restored-head (str/trim (:out (git restore-dir "rev-parse" "HEAD"))))
(when-not (= head restored-head)
  (die! {:mirror/reason (str "復元した HEAD が一致しない: " head " vs " restored-head)
         :mirror/stage :restore}))

;; HEAD が合っていても中身が壊れていることはある。corpus 自身の検証器を回す。
(def verified?
  (let [r (run "clojure" ["-M" "verify.clj"] {:cwd restore-dir :timeout 600000})]
    (when-not (zero? (:exit r))
      (println (:out r))
      (die! {:mirror/reason "復元した corpus が verify.clj を通らない"
             :mirror/stage :restore :mirror/bundle bundle-name}))
    true))

(println (str "  · 復元検証 OK — B2 から取り直し → clone → HEAD 一致 → verify.clj 通過"))

;; ── 古い世代を刈る ──────────────────────────────────────────────────────
(def pruned
  (let [r (aws "s3" "ls" (str "s3://" bucket "/" prefix))
        names (->> (str/split-lines (:out r))
                   (keep #(second (re-find #"\s(hirameki-patents-\S+\.bundle)$" %)))
                   sort vec)
        old (drop-last keep-n names)]
    (doseq [n old] (aws "s3" "rm" (str "s3://" bucket "/" prefix n)))
    (count old)))

(run "rm" ["-rf" tmp] {})

(append-ledger!
 {:mirror/ok true :mirror/stage :done :mirror/bundle bundle-name
  :mirror/head head :mirror/bytes bundle-bytes :mirror/sha256 bundle-sha
  :mirror/restored true :mirror/verified verified?
  :mirror/bucket bucket :mirror/pruned pruned})

(println (str "\n  hirameki-mirror: " bundle-name " → s3://" bucket "/" prefix
              "（復元検証済み）· 古い世代 " pruned " 件を削除"))

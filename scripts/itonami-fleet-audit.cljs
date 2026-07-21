#!/usr/bin/env nbb
;; scripts/itonami-fleet-audit.cljs — cloud-itonami actor fleet の健全性/活動性を
;; 一括評価する CLI。
;;
;; orgs/cloud-itonami/cloud-itonami-* の各リポジトリについて、blueprint.edn の
;; 宣言的メタデータ(maturity/domain/governor)と、実測シグナル(直近コミット日時・
;; dirty 状態・未push コミット数・src/test ファイル数・facts.cljc の法域カバレッジ数・
;; governed-actor 構成要素の有無)を集め、
;;   1. 人間可読なサマリ+「要対応」表を標準出力に表示
;;   2. manifest/edn-datomize.cljs の tx-entities モード経由で、そのまま
;;      Datomic/Datascript に transact できる tx-data を生成し、
;;      manifest/schema.edn に :itonami.fleet-audit/* 属性を登録
;; の両方を行う。実際のアクターリポジトリは読むだけで一切書き換えない
;; (blueprint.edn 自体を tx-data 化したい場合は
;; `nbb manifest/edn-datomize.cljs wrap-map-glob orgs/cloud-itonami blueprint.edn
;; itonami.blueprint` を別途、意図して実行すること — 380個の別 git repo を書き換える
;; 操作なので、このスクリプトからは絶対に自動実行しない)。
;;
;; 使い方:
;;   nbb scripts/itonami-fleet-audit.cljs           — 人間可読なサマリ+「要対応」表
;;   nbb scripts/itonami-fleet-audit.cljs --all      — 全リポジトリの表を表示
;;   nbb scripts/itonami-fleet-audit.cljs --edn       — tx-data 全件を EDN で標準出力へ
;;                                                       (リダイレクトで保存: ... > report.edn)

(require '[scripts.nbb-compat :refer [slurp spit file-seq format relative-path]]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str])

(def os (js/require "node:os"))
(def node-fs (js/require "node:fs"))

(def root (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))
(def fleet-dir (io/file root "orgs" "cloud-itonami"))

(defn actor-dirs []
  (->> (.listFiles fleet-dir)
       (filter #(.isDirectory %))
       (filter #(str/starts-with? (.getName %) "cloud-itonami-"))
       (sort-by #(.getName %))))

(defn read-blueprint
  "blueprint.edn を読む。生の map / wrap-map! 済み tx-data([{...}])のどちらでも読める。"
  [dir]
  (let [f (io/file dir "blueprint.edn")]
    (when (.exists f)
      (try
        (let [content (edn/read-string (slurp f))]
          (cond
            (and (vector? content) (map? (first content))) (dissoc (first content) :db/id)
            (map? content) content
            :else nil))
        (catch :default _ nil)))))

(defn git-out [dir & args]
  (let [{:keys [exit out]} (apply sh "git" "-C" (.getPath dir) args)]
    (when (zero? exit) (not-empty (str/trim out)))))

(defn last-commit-at [dir] (git-out dir "log" "-1" "--format=%cI"))
(defn dirty? [dir] (boolean (git-out dir "status" "--porcelain")))
(defn repo-url [dir] (git-out dir "remote" "get-url" "origin"))
(defn unpushed-count [dir]
  (when-let [s (git-out dir "rev-list" "--count" "@{u}..HEAD")]
    (let [n (js/parseInt s 10)]
      (when-not (js/isNaN n) n))))

(defn days-since [iso-date-str]
  (when iso-date-str
    (let [then (.getTime (js/Date. iso-date-str))
          now (js/Date.now)]
      (.round js/Math (/ (- now then) 86400000)))))

(defn count-files [dir sub]
  (let [d (io/file dir sub)]
    (if (.exists d)
      (count (filter #(.isFile %) (file-seq d)))
      0)))

(defn find-facts-file [dir]
  (let [src (io/file dir "src")]
    (when (.exists src)
      (first (filter #(and (.isFile %) (= "facts.cljc" (.getName %))) (file-seq src))))))

(def jurisdiction-re #"\"[A-Z]{3}\"\s*\{")

(defn jurisdiction-coverage [dir]
  (when-let [f (find-facts-file dir)]
    (count (re-seq jurisdiction-re (slurp f)))))

(def component-patterns
  {:governor #"governor\.cljc$"
   :store #"store\.cljc$"
   :phase #"phase\.cljc$"
   :sim #"sim\.cljc$"
   :registry #"registry\.cljc$"
   :operation #"operation\.cljc$"
   :facts #"facts\.cljc$"
   :advisor #"(llm|advisor)\.cljc$"})

(defn components [dir]
  (let [src (io/file dir "src")]
    (if (.exists src)
      (let [names (map #(.getName %) (filter #(.isFile %) (file-seq src)))]
        (into #{}
              (keep (fn [[k re]] (when (some #(re-find re %) names) k)))
              component-patterns))
      #{})))

(defn archive-file-count
  "Real, non-empty file count under `80-data/` -- the read-only-archive repo
   pattern (cloud-itonami-lei-*, ADR-2607110300/2607199960: 'archives
   publicly published legal/policy documents ... does not act, propose, or
   execute anything ... not a governed Advisor/Governor actor'). These repos
   are BY DESIGN not shaped like the src/-based actor repos `components`/
   `find-facts-file` above check for -- a src-file-count of 0 there does not
   mean empty, it means 'this is a different, real, non-actor repo kind.'
   2026-07-21 correction: 143/143 cloud-itonami-lei-* repos were previously
   flagged :stub by this script; a sample (cloud-itonami-lei-
   ilul7b6z54mrycf6h308) has a real 248-line 80-data/public/tos.journal.edn.
   `(pos? (.length %))` excludes zero-byte placeholder files from counting
   as real content."
  [dir]
  (let [d (io/file dir "80-data")]
    (if (.exists d)
      (count (filter #(and (.isFile %) (pos? (.length %))) (file-seq d)))
      0)))

(defn actor-status [{:keys [src-file-count archive-file-count last-commit-at days-since-commit]}]
  (cond
    (and (zero? src-file-count) (pos? archive-file-count)) :archive
    (zero? src-file-count) :stub
    (nil? last-commit-at) :no-commits
    (> days-since-commit 180) :stale
    (> days-since-commit 30) :dormant
    :else :active))

(defn actor-facts [dir]
  (let [name (.getName dir)
        bp (read-blueprint dir)
        commit-at (last-commit-at dir)
        days (or (days-since commit-at) -1)
        src-count (count-files dir "src")
        archive-count (archive-file-count dir)
        comps (components dir)
        maturity (cond
                   (:itonami.blueprint/maturity bp) (:itonami.blueprint/maturity bp)
                   (nil? bp) :no-blueprint
                   :else :maturity-unset)
        base {:itonami.fleet-audit/repo name
              :itonami.fleet-audit/isic (or (:itonami.blueprint/isic-rev5 bp) "")
              :itonami.fleet-audit/domain (or (:itonami.blueprint/domain bp) :unknown)
              :itonami.fleet-audit/governor (or (:itonami.blueprint/governor bp) :none)
              :itonami.fleet-audit/maturity maturity
              :itonami.fleet-audit/repo-url (or (repo-url dir) "")
              :itonami.fleet-audit/last-commit-at (or commit-at "")
              :itonami.fleet-audit/days-since-commit days
              :itonami.fleet-audit/dirty? (dirty? dir)
              :itonami.fleet-audit/unpushed-commit-count (or (unpushed-count dir) 0)
              :itonami.fleet-audit/src-file-count src-count
              :itonami.fleet-audit/archive-file-count archive-count
              :itonami.fleet-audit/test-file-count (count-files dir "test")
              :itonami.fleet-audit/jurisdiction-coverage-count (or (jurisdiction-coverage dir) 0)
              :itonami.fleet-audit/component-count (count comps)
              :itonami.fleet-audit/components (vec (sort comps))}]
    (assoc base :itonami.fleet-audit/status (actor-status {:src-file-count src-count
                                                            :archive-file-count archive-count
                                                            :last-commit-at commit-at
                                                            :days-since-commit days}))))

(defn build-entities []
  (mapv actor-facts (actor-dirs)))

(defn to-tx-data!
  "entities を manifest/edn-datomize.cljs の tx-entities モード経由で Datomic/
   Datascript tx-data に変換し、manifest/schema.edn へスキーマを登録する
   (OS tmpdir を経由し、一時ファイルは処理後に削除する)。"
  [entities]
  (let [tmp-in (io/file (.tmpdir os) "itonami-fleet-audit-staging.edn")
        tmp-out (io/file (.tmpdir os) "itonami-fleet-audit-txdata.edn")
        _ (spit tmp-in (pr-str entities))
        {:keys [exit err]} (sh "nbb" (str root "/manifest/edn-datomize.cljs") "tx-entities"
                                (.getPath tmp-in) (.getPath tmp-out))]
    (when-not (zero? exit) (println "WARN: tx-entities failed:" err))
    (let [tx (when (.exists tmp-out) (edn/read-string (slurp tmp-out)))]
      (try (.unlinkSync node-fs (.getPath tmp-in)) (catch :default _ nil))
      (try (.unlinkSync node-fs (.getPath tmp-out)) (catch :default _ nil))
      tx)))

(defn summary [entities]
  {:total (count entities)
   :by-maturity (into (sorted-map) (frequencies (map :itonami.fleet-audit/maturity entities)))
   :by-status (into (sorted-map) (frequencies (map :itonami.fleet-audit/status entities)))
   :dirty (count (filter :itonami.fleet-audit/dirty? entities))
   :with-unpushed-commits (count (filter #(pos? (:itonami.fleet-audit/unpushed-commit-count %)) entities))
   :with-jurisdiction-coverage (count (filter #(pos? (:itonami.fleet-audit/jurisdiction-coverage-count %)) entities))
   :total-jurisdiction-coverage (reduce + (map :itonami.fleet-audit/jurisdiction-coverage-count entities))})

(defn needs-attention [entities]
  (filter (fn [e] (or (= :stale (:itonami.fleet-audit/status e))
                      (:itonami.fleet-audit/dirty? e)
                      (pos? (:itonami.fleet-audit/unpushed-commit-count e))))
          entities))

(defn print-row [{:itonami.fleet-audit/keys [repo maturity status days-since-commit
                                              src-file-count test-file-count
                                              jurisdiction-coverage-count
                                              dirty? unpushed-commit-count]}]
  (println (format "%-38s %-12s %-11s %5s %4s %4s %4s %-5s %s"
                    repo (name maturity) (name status)
                    (if (neg? days-since-commit) "n/a" (str days-since-commit "d"))
                    src-file-count test-file-count jurisdiction-coverage-count
                    (if dirty? "dirty" "-")
                    (if (pos? unpushed-commit-count) (str unpushed-commit-count " unpushed") ""))))

(defn print-header []
  (println (format "%-38s %-12s %-11s %5s %4s %4s %4s %-5s %s"
                    "repo" "maturity" "status" "days" "src" "test" "juri" "" ""))
  (println (str/join (repeat 110 "-"))))

(let [entities (build-entities)
      args (set *command-line-args*)
      edn? (contains? args "--edn")
      all? (contains? args "--all")]
  (if edn?
    (prn (to-tx-data! entities))
    (do
      ;; schema 登録は毎回の副作用として行う(--edn の有無に関わらず、
      ;; kotoba-boundary-audit.cljs 同様「保存するかは呼び出し側が redirect で選ぶ」
      ;; 流儀を保ちつつ、schema.edn への蓄積だけは常時行う)。
      (to-tx-data! entities)
      (println "cloud-itonami fleet audit --" (count entities) "repos under" (relative-path root fleet-dir))
      (println "summary:" (pr-str (summary entities)))
      (println)
      (if all?
        (do (print-header) (doseq [e entities] (print-row e)))
        (let [attn (vec (needs-attention entities))]
          (println (count attn) "repos need attention (stale / dirty / unpushed commits):")
          (println)
          (print-header)
          (doseq [e (take 200 attn)] (print-row e))
          (when (> (count attn) 200)
            (println (format "... %d more. Use --all for the full fleet, --edn for full tx-data."
                              (- (count attn) 200))))
          (when (zero? (count attn))
            (println "(none — use --all to see the full fleet table.)")))))))

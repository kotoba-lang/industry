#!/usr/bin/env nbb
;; scripts/itonami-fleet-audit.cljs — cloud-itonami actor fleet の健全性/活動性を
;; 一括評価する CLI。
;;
;; orgs/cloud-itonami/cloud-itonami-* の各リポジトリについて、blueprint.edn の
;; 宣言的メタデータ(maturity/domain/governor)と、実測シグナル(直近コミット日時・
;; dirty 状態・未push コミット数・src/test ファイル数・facts.cljc の法域カバレッジ数・
;; governed-actor 構成要素の有無)を集め、そこから2つの派生判定を計算する:
;;   :itonami.fleet-audit/prod-ready?            status が :active(実装済+直近
;;                                                 メンテ) か :archive(アーカイブ
;;                                                 として実コンテンツを保持)である
;;                                                 場合のみ true。:stub/:no-commits/
;;                                                 :stale/:dormant はまだ prod に
;;                                                 達していない。
;;   :itonami.fleet-audit/real-world-ingest-signal どの実世界データ経路で実測した
;;                                                 か — :facts-citations
;;                                                 (facts.cljc 内の実 URL 引用数。
;;                                                 isic/isco の国別カタログだけで
;;                                                 なく municipality の条例別
;;                                                 カタログ等、キー形が違っても
;;                                                 拾える汎化版) / :archive-content
;;                                                 (80-data/ 配下の実ファイル) /
;;                                                 :none-measured(どちらの経路も
;;                                                 存在しない)。
;;   :itonami.fleet-audit/real-world-ingest-gap?  対応する count が 0、または
;;                                                 経路が :none-measured なら true
;;                                                 (未測定は「対象外」ではなく
;;                                                 「未達」— ADR-2607203000 の
;;                                                 「今日数値を持たない entity は
;;                                                 対象外でなくカバレッジ未達」原則
;;                                                 に合わせる)。
;; 集めた結果を:
;;   1. 人間可読なサマリ+「要対応」表を標準出力に表示
;;   2. manifest/edn-datomize.cljs の tx-entities モード経由で、そのまま
;;      Datomic/Datascript に transact できる tx-data を生成し、
;;      manifest/schema.edn に :itonami.fleet-audit/* 属性を登録
;;   3. manifest/itonami-fleet-audit.edn へ(:db/id 無しの flat map ベクタ、
;;      manifest/repo-maturity.edn と同じ規約で)毎回上書き保存
;; の3つを行う。実際のアクターリポジトリは読むだけで一切書き換えない
;; (blueprint.edn 自体を tx-data 化したい場合は
;; `nbb manifest/edn-datomize.cljs wrap-map-glob orgs/cloud-itonami blueprint.edn
;; itonami.blueprint` を別途、意図して実行すること — 380個の別 git repo を書き換える
;; 操作なので、このスクリプトからは絶対に自動実行しない)。
;;
;; 使い方:
;;   nbb scripts/itonami-fleet-audit.cljs             — 人間可読なサマリ+「要対応」表
;;   nbb scripts/itonami-fleet-audit.cljs --all        — 全リポジトリの表を表示
;;   nbb scripts/itonami-fleet-audit.cljs --not-prod    — prod-ready? false の repo だけ表示
;;   nbb scripts/itonami-fleet-audit.cljs --ingest-gap  — real-world-ingest-gap? true の repo だけ表示
;;   nbb scripts/itonami-fleet-audit.cljs --edn         — tx-data 全件を EDN で標準出力へ
;;                                                        (リダイレクトで保存: ... > report.edn)
;;   毎回の実行で manifest/itonami-fleet-audit.edn を上書き生成する(下記 DataScript 例参照)。

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

(defn- read-industry-maturity-registry
  "kotoba-lang/industry's own registry.edn -- a plain (not pr-str-blob-
   encoded) top-level map, :industries a real EDN vector of {:id
   :maturity ...} per ISIC code, authoritative per ADR-2607121000.
   2026-07-21 finding: EVERY sampled maturity-unset isic repo (62/62) has a
   real entry here -- the gap this fn closes is a cross-reference gap, not
   a missing-data gap."
  []
  (let [f (io/file root "orgs" "kotoba-lang" "industry" "resources" "kotoba" "industry" "registry.edn")]
    (if (.exists f)
      (try
        (into {} (map (juxt :id :maturity)) (:industries (edn/read-string (slurp f))))
        (catch :default _ {}))
      {})))

(defn- read-occupation-maturity-registry
  "kotoba-lang/occupation's own registry.edn -- unlike industry's, the
   top-level is a vector-of-one-map (tx-data shape) whose
   :kotoba.occupation/occupations value is itself a pr-str-encoded EDN
   STRING (double-parse required; same nested-string-blob convention
   documented in this workspace's own docs/ADR edn tooling). 2026-07-21
   finding: 216/216 sampled maturity-unset isco repos have a real entry
   here too."
  []
  (let [f (io/file root "orgs" "kotoba-lang" "occupation" "resources" "kotoba" "occupation" "registry.edn")]
    (if (.exists f)
      (try
        (let [outer (edn/read-string (slurp f))
              occs-str (:kotoba.occupation/occupations (first outer))
              occs (edn/read-string occs-str)]
          (into {} (map (juxt :id :maturity)) occs))
        (catch :default _ {}))
      {})))

(def industry-maturity-registry (read-industry-maturity-registry))
(def occupation-maturity-registry (read-occupation-maturity-registry))

(defn canonical-maturity
  "Cross-references the canonical kotoba-lang/industry or
   kotoba-lang/occupation registry by numeric code, parsed from the repo's
   own real name -- nil (not a guess) when the repo isn't an isic/isco
   code or the code isn't in either registry."
  [repo-name]
  (or (when-let [code (second (re-find #"^cloud-itonami-isic-(\d+)" repo-name))]
        (get industry-maturity-registry code))
      (when-let [code (second (re-find #"^cloud-itonami-isco-(\d+)" repo-name))]
        (get occupation-maturity-registry code))))

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

(def url-re #"https?://")

(defn facts-citation-count
  "Real-world source citations inside facts.cljc specifically(not the whole
   src/ tree). Generalizes jurisdiction-coverage beyond the isic/isco
   per-country `\"XXX\" {` catalog shape -- e.g. cloud-itonami-municipality-*
   keys its catalog by municipality slug, not country code, so
   jurisdiction-coverage reads 0 there even though every ordinance entry
   cites a real :ordinance/url (verified 2026-07-21 against
   cloud-itonami-municipality-jpn-tokyo, which has 2 official
   reiki.metro.tokyo.lg.jp citations and 0 jurisdiction-re matches).
   Deliberately scoped to facts.cljc, NOT all of src/ -- src/registry.cljc
   commonly embeds a `https://www.w3.org/ns/credentials/v2` JSON-LD
   `@context` boilerplate (verifiable-credential issuance scaffolding, not
   domain data), which would contaminate a whole-src/ URL count into a false
   non-gap for repos with zero real facts."
  [dir]
  (when-let [f (find-facts-file dir)]
    (count (re-seq url-re (slurp f)))))

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
   Checks real file size via node:fs `statSync` (bound to `node-fs` below),
   NOT `.length` -- `scripts.nbb-compat`'s `io/file` shim object has no
   `.length` method (only `isFile`/`isDirectory`/`getPath`/`getName`/etc,
   see scripts/nbb_compat.cljs's own `file` fn), unlike real java.io.File.
   Excludes zero-byte placeholder files from counting as real content."
  [dir]
  (let [d (io/file dir "80-data")]
    (if (.exists d)
      (count (filter #(and (.isFile %) (pos? (.-size (.statSync node-fs (.getPath %)))))
                     (file-seq d)))
      0)))

(defn actor-status [{:keys [src-file-count archive-file-count last-commit-at days-since-commit]}]
  (cond
    (and (zero? src-file-count) (pos? archive-file-count)) :archive
    (zero? src-file-count) :stub
    (nil? last-commit-at) :no-commits
    (> days-since-commit 180) :stale
    (> days-since-commit 30) :dormant
    :else :active))

(def known-families
  "cloud-itonami-<family>-<rest> の <family> 語彙(実測: isic 429 / isco 216 /
   lei 143 / assoc 78 / municipality 49 / unspsc 5 / cofog 5 / gtin 3 /
   regulatory 1, 2026-07-21 時点)。未知語は fallback で repo 名の残り全体を
   family として使う(cloud-itonami-partners のような単発 repo 用)。"
  #{"isic" "isco" "lei" "assoc" "municipality" "iso3166" "unspsc" "cofog" "gtin" "regulatory"})

(defn family-of [repo-name]
  (let [rest-name (subs repo-name (count "cloud-itonami-"))
        seg (first (str/split rest-name #"-"))]
    (keyword (if (contains? known-families seg) seg rest-name))))

(defn prod-ready? [status]
  (contains? #{:active :archive} status))

(defn real-world-ingest [{:keys [has-facts-file? citation-count archive-count]}]
  (cond
    has-facts-file? {:signal :facts-citations :count citation-count}
    (pos? archive-count) {:signal :archive-content :count archive-count}
    :else {:signal :none-measured :count 0}))

(defn actor-facts [dir]
  (let [name (.getName dir)
        bp (read-blueprint dir)
        commit-at (last-commit-at dir)
        days (or (days-since commit-at) -1)
        src-count (count-files dir "src")
        archive-count (archive-file-count dir)
        comps (components dir)
        has-facts? (boolean (find-facts-file dir))
        juri-count (or (jurisdiction-coverage dir) 0)
        citation-count (or (facts-citation-count dir) 0)
        own-maturity (:itonami.blueprint/maturity bp)
        registry-maturity (when-not own-maturity (canonical-maturity name))
        maturity (cond
                   own-maturity own-maturity
                   registry-maturity registry-maturity
                   (nil? bp) :no-blueprint
                   :else :maturity-unset)
        maturity-source (cond
                          own-maturity :own-blueprint
                          registry-maturity :canonical-registry
                          :else :unknown)
        status (actor-status {:src-file-count src-count
                              :archive-file-count archive-count
                              :last-commit-at commit-at
                              :days-since-commit days})
        ingest (real-world-ingest {:has-facts-file? has-facts?
                                   :citation-count citation-count
                                   :archive-count archive-count})
        ingest-signal (:signal ingest)
        ingest-count (:count ingest)]
    {:itonami.fleet-audit/repo name
     :itonami.fleet-audit/family (family-of name)
     :itonami.fleet-audit/isic (or (:itonami.blueprint/isic-rev5 bp) "")
     :itonami.fleet-audit/domain (or (:itonami.blueprint/domain bp) :unknown)
     :itonami.fleet-audit/governor (or (:itonami.blueprint/governor bp) :none)
     :itonami.fleet-audit/maturity maturity
     :itonami.fleet-audit/maturity-source maturity-source
     :itonami.fleet-audit/repo-url (or (repo-url dir) "")
     :itonami.fleet-audit/last-commit-at (or commit-at "")
     :itonami.fleet-audit/days-since-commit days
     :itonami.fleet-audit/dirty? (dirty? dir)
     :itonami.fleet-audit/unpushed-commit-count (or (unpushed-count dir) 0)
     :itonami.fleet-audit/src-file-count src-count
     :itonami.fleet-audit/archive-file-count archive-count
     :itonami.fleet-audit/test-file-count (count-files dir "test")
     :itonami.fleet-audit/jurisdiction-coverage-count juri-count
     :itonami.fleet-audit/component-count (count comps)
     :itonami.fleet-audit/components (vec (sort comps))
     :itonami.fleet-audit/status status
     :itonami.fleet-audit/prod-ready? (prod-ready? status)
     :itonami.fleet-audit/real-world-ingest-signal ingest-signal
     :itonami.fleet-audit/real-world-ingest-count ingest-count
     :itonami.fleet-audit/real-world-ingest-gap? (or (= ingest-signal :none-measured) (zero? ingest-count))}))

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
   :by-family (into (sorted-map) (frequencies (map :itonami.fleet-audit/family entities)))
   :by-maturity (into (sorted-map) (frequencies (map :itonami.fleet-audit/maturity entities)))
   :by-maturity-source (into (sorted-map) (frequencies (map :itonami.fleet-audit/maturity-source entities)))
   :by-status (into (sorted-map) (frequencies (map :itonami.fleet-audit/status entities)))
   :dirty (count (filter :itonami.fleet-audit/dirty? entities))
   :with-unpushed-commits (count (filter #(pos? (:itonami.fleet-audit/unpushed-commit-count %)) entities))
   :with-jurisdiction-coverage (count (filter #(pos? (:itonami.fleet-audit/jurisdiction-coverage-count %)) entities))
   :total-jurisdiction-coverage (reduce + (map :itonami.fleet-audit/jurisdiction-coverage-count entities))
   :not-prod-ready (count (remove :itonami.fleet-audit/prod-ready? entities))
   :real-world-ingest-gap (count (filter :itonami.fleet-audit/real-world-ingest-gap? entities))
   :by-ingest-signal (into (sorted-map) (frequencies (map :itonami.fleet-audit/real-world-ingest-signal entities)))})

(defn needs-attention [entities]
  (filter (fn [e] (or (= :stale (:itonami.fleet-audit/status e))
                      (:itonami.fleet-audit/dirty? e)
                      (pos? (:itonami.fleet-audit/unpushed-commit-count e))))
          entities))

(defn not-prod-ready [entities]
  (remove :itonami.fleet-audit/prod-ready? entities))

(defn ingest-gap [entities]
  (filter :itonami.fleet-audit/real-world-ingest-gap? entities))

(defn print-row [{:itonami.fleet-audit/keys [repo family maturity status days-since-commit
                                              src-file-count test-file-count
                                              jurisdiction-coverage-count
                                              prod-ready? real-world-ingest-gap?
                                              dirty? unpushed-commit-count]}]
  (println (format "%-38s %-13s %-12s %-11s %5s %4s %4s %4s %-5s %-5s %-5s %s"
                    repo (name family) (name maturity) (name status)
                    (if (neg? days-since-commit) "n/a" (str days-since-commit "d"))
                    src-file-count test-file-count jurisdiction-coverage-count
                    (if prod-ready? "-" "NOT-PROD")
                    (if real-world-ingest-gap? "GAP" "-")
                    (if dirty? "dirty" "-")
                    (if (pos? unpushed-commit-count) (str unpushed-commit-count " unpushed") ""))))

(defn print-header []
  (println (format "%-38s %-13s %-12s %-11s %5s %4s %4s %4s %-5s %-5s %-5s %s"
                    "repo" "family" "maturity" "status" "days" "src" "test" "juri"
                    "prod" "ingest" "" ""))
  (println (str/join (repeat 135 "-"))))

(def edn-header
  (str ";; manifest/itonami-fleet-audit.edn — generated by scripts/itonami-fleet-audit.cljs.\n"
       ";; DO NOT EDIT BY HAND. Regenerate: nbb scripts/itonami-fleet-audit.cljs\n"
       ";;\n"
       ";; A vector of DataScript/Datomic-transactable entity-maps, one per\n"
       ";; orgs/cloud-itonami/cloud-itonami-* repo. Each map is directly usable as a\n"
       ";; tx-data entry (no :db/id — DataScript assigns a tempid per map on transact):\n"
       ";;\n"
       ";;   (require '[datascript.core :as d])\n"
       ";;   (def conn (d/create-conn {}))\n"
       ";;   (d/transact! conn (clojure.edn/read-string (slurp \"manifest/itonami-fleet-audit.edn\")))\n"
       ";;\n"
       ";;   ;; repos that have not reached prod maturity yet:\n"
       ";;   (d/q '[:find ?repo ?status :where\n"
       ";;          [?e :itonami.fleet-audit/repo ?repo]\n"
       ";;          [?e :itonami.fleet-audit/status ?status]\n"
       ";;          [?e :itonami.fleet-audit/prod-ready? false]] @conn)\n"
       ";;\n"
       ";;   ;; repos where real-world information ingestion is incomplete:\n"
       ";;   (d/q '[:find ?repo ?signal ?count :where\n"
       ";;          [?e :itonami.fleet-audit/repo ?repo]\n"
       ";;          [?e :itonami.fleet-audit/real-world-ingest-signal ?signal]\n"
       ";;          [?e :itonami.fleet-audit/real-world-ingest-count ?count]\n"
       ";;          [?e :itonami.fleet-audit/real-world-ingest-gap? true]] @conn)\n"
       ";;\n"
       ";; Fields:\n"
       ";;   :itonami.fleet-audit/family              parsed from repo name (isic/isco/lei/\n"
       ";;                                             assoc/municipality/iso3166/unspsc/cofog/\n"
       ";;                                             gtin/regulatory/other).\n"
       ";;   :itonami.fleet-audit/status               :stub / :no-commits / :stale (>180d) /\n"
       ";;                                             :dormant (30-180d) / :active / :archive\n"
       ";;                                             (read-only 80-data/ archive, e.g. lei ToS).\n"
       ";;   :itonami.fleet-audit/prod-ready?          true only for :active / :archive status —\n"
       ";;                                             the fleet's only two \"doing its job\" states.\n"
       ";;   :itonami.fleet-audit/real-world-ingest-signal  which real-world data channel was\n"
       ";;                                             measured: :facts-citations (real https:// URL\n"
       ";;                                             citations inside facts.cljc — generalizes past\n"
       ";;                                             the isic/isco per-country catalog shape to\n"
       ";;                                             municipality's per-ordinance shape etc.) /\n"
       ";;                                             :archive-content (80-data/ real files) /\n"
       ";;                                             :none-measured (no channel present at all).\n"
       ";;   :itonami.fleet-audit/real-world-ingest-gap?  true when the measured count is 0 OR the\n"
       ";;                                             signal is :none-measured — an entity with no\n"
       ";;                                             measurement is a coverage gap, not \"n/a\"\n"
       ";;                                             (ADR-2607203000 principle).\n"
       ";;\n"
       ";; Generated: " (.toISOString (js/Date.)) "\n\n"))

(defn write-edn! [entities out-path]
  (spit (io/file root out-path)
        (str edn-header "[\n" (str/join "\n" (map #(str " " (pr-str %)) entities)) "\n]\n")))

(let [entities (build-entities)
      args (set *command-line-args*)
      edn? (contains? args "--edn")
      all? (contains? args "--all")
      not-prod? (contains? args "--not-prod")
      ingest-gap? (contains? args "--ingest-gap")]
  ;; schema 登録 + manifest/itonami-fleet-audit.edn への保存は毎回の副作用として
  ;; 常に行う(--edn の有無に関わらず。kotoba-boundary-audit.cljs 同様「標準出力に
  ;; 出すかは呼び出し側が選ぶ」流儀を保ちつつ、永続化とschema蓄積だけは常時行う)。
  (to-tx-data! entities)
  (write-edn! entities "manifest/itonami-fleet-audit.edn")
  (if edn?
    (prn entities)
    (do
      (println "cloud-itonami fleet audit --" (count entities) "repos under" (relative-path root fleet-dir))
      (println "summary:" (pr-str (summary entities)))
      (println "wrote manifest/itonami-fleet-audit.edn")
      (println)
      (cond
        all? (do (print-header) (doseq [e entities] (print-row e)))

        not-prod? (let [rows (vec (not-prod-ready entities))]
                    (println (count rows) "repos not yet prod-ready:")
                    (println)
                    (print-header)
                    (doseq [e rows] (print-row e)))

        ingest-gap? (let [rows (vec (ingest-gap entities))]
                      (println (count rows) "repos with a real-world-ingest gap:")
                      (println)
                      (print-header)
                      (doseq [e rows] (print-row e)))

        :else
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

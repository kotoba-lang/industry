#!/usr/bin/env nbb
;; watchlist-datalake-export.cljs — kotoba-lang/watchlist-screen の
;; 制裁リスト snapshot を datalake に載せられる 4 枚の表に落とす。
;;
;; ## 何を読むか
;;
;; 読むのは **正本の EDN**（`orgs/kotoba-lang/watchlist-screen/resources/
;; watchlist/lists/*.entities.edn` と `*.manifest.edn`、あちらの
;; `scripts/refresh_lists.cljs` が書く）。この script は事実を 1 つも取りに
;; 行かない —— 取得と検証は正本の仕事で、ここは形を変えるだけ。
;;
;; ## なぜ 4 枚か
;;
;;   watchlist_entity     広い形。1 行 = 1 entity。分析の入口。
;;   watchlist_name       長い形。1 行 = 1 つの名前（primary か alias か）。
;;                        **screening が実際に join する面**で、正規化名も持つ。
;;   watchlist_attribute  長い形。1 行 = 国籍 1 つ、または program 1 つ。
;;   watchlist_manifest   3 行。出所・取得時刻・件数・sha256・git commit。
;;
;; 広い方だけにすると alias が落ちる —— OFAC の 1 entity は 20 個の別名を持つ
;; ことがあり、`;` で 1 セルに畳めば「区切り文字を含む別名」で静かに壊れる。
;; 長い方だけにすると素の集計に毎回 pivot が要る。**どちらか一方は必ず嘘か
;; 不便になる**ので両方出す（lei-datalake-export.cljs と同じ判断）。
;;
;; manifest を表にするのは、この面に対する query が
;; 「その答えはどのくらい古いデータに対するものか」を **join で** 答えられる
;; ようにするため。件数だけ載せて取得時刻を落とすと、stale な答えと fresh な
;; 答えが区別できなくなる（正本側の `:watchlist/stale?` が守っている不変条件と
;; 同じもの）。
;;
;; ## normalized_name をここで計算する理由
;;
;; `watchlist.match/normalize`（半角カナ・全角 ASCII・ひらがな畳み込み）を
;; 通した文字列を列として持つ。持たないと、この表を読む側が全員それを書き
;; 直すことになり、**書き直した瞬間に screening の答えと datalake の答えが
;; 分岐する**。同じ関数の出力であることが要点なので、ここで呼ぶ。
;;
;; ## なぜ 2 プロセスに分かれているか
;;
;; EDN を読むのは nbb（この workspace の script host）、Iceberg の commit は
;; pyiceberg（nbb に Avro manifest / metadata.json の writer は無い）。
;; 境界はこの JSON と、隣に書く spec。
;;
;; ## 測れなかったことを clean と書かない
;;
;; entities が 1 つも読めなければ exit 2。読めなかった source は skipped として
;; **数えて報告する**。
;;
;; Run:
;;   nbb -cp "orgs/kotoba-lang/watchlist-screen/src" \
;;     scripts/watchlist-datalake-export.cljs [--repo <path>] [--out-dir <dir>]

(ns watchlist-datalake-export
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as edn]
            [clojure.string :as str]
            [watchlist.match :as match]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(defn- flag [n] (let [i (.indexOf (clj->js argv) n)]
                  (when (and (>= i 0) (< (inc i) (count argv))) (nth argv (inc i)))))

(def repo (or (flag "--repo") "orgs/kotoba-lang/watchlist-screen"))
(def out-dir (or (flag "--out-dir") "/tmp"))
(def lists-dir (path/join repo "resources" "watchlist" "lists"))

(defn- source-commit
  "The commit the snapshot was read at. Provenance, not decoration: this is
   the one column that lets a reader of the Iceberg table get back to the
   bytes it was built from. Unknown is reported as \"unknown\", never as an
   empty string that sorts next to a real sha."
  []
  (try
    (str/trim (str (cp/execSync (str "git -C " repo " rev-parse HEAD")
                                #js {:encoding "utf8" :stdio #js ["ignore" "pipe" "ignore"]})))
    (catch :default _ "unknown")))

(defn- iso [ms] (when ms (.toISOString (js/Date. ms))))

(defn- read-edn [p]
  (when (fs/existsSync p) (edn/read-string (fs/readFileSync p "utf8"))))

(defn- sources-in [dir]
  (->> (fs/readdirSync dir)
       (keep #(second (re-matches #"(.+)\.entities\.edn" %)))
       sort vec))

(defn- entity-row [e]
  {"entity_id" (:entity/id e)
   "source" (name (:entity/source e))
   "entity_type" (name (:entity/type e))
   "primary_name" (:entity/primary-name e)
   "normalized_name" (match/normalize (:entity/primary-name e))
   "dob" (:entity/dob e)
   "source_updated_at" (:entity/source-updated-at e)
   "alias_count" (count (:entity/aliases e))
   "nationality_count" (count (:entity/nationality e))
   "program_count" (count (:entity/programs e))})

(defn- name-rows [e]
  (into [{"entity_id" (:entity/id e)
          "source" (name (:entity/source e))
          "name_kind" "primary"
          "name_index" 0
          "name" (:entity/primary-name e)
          "normalized_name" (match/normalize (:entity/primary-name e))}]
        (map-indexed (fn [i a]
                       {"entity_id" (:entity/id e)
                        "source" (name (:entity/source e))
                        "name_kind" "alias"
                        "name_index" i
                        "name" a
                        "normalized_name" (match/normalize a)}))
        (:entity/aliases e)))

(defn- attribute-rows [e]
  (into (vec (map-indexed (fn [i v] {"entity_id" (:entity/id e)
                                     "source" (name (:entity/source e))
                                     "attribute" "nationality"
                                     "value_index" i
                                     "value" v})
                          (:entity/nationality e)))
        (map-indexed (fn [i v] {"entity_id" (:entity/id e)
                                "source" (name (:entity/source e))
                                "attribute" "program"
                                "value_index" i
                                "value" v}))
        (:entity/programs e)))

(defn- write-json! [f rows]
  (let [p (path/join out-dir f)]
    (fs/writeFileSync p (js/JSON.stringify (clj->js rows)))
    (println (str "WROTE\t" (count rows) "\t" p))))

(defn -main []
  (when-not (fs/existsSync lists-dir)
    (println "no such directory:" lists-dir) (js/process.exit 2))
  (let [commit (source-commit)
        srcs (sources-in lists-dir)
        loaded (keep (fn [s]
                       (let [ents (read-edn (path/join lists-dir (str s ".entities.edn")))
                             man (read-edn (path/join lists-dir (str s ".manifest.edn")))]
                         (if (and (seq ents) man)
                           {:source s :entities ents :manifest man}
                           (do (println "SKIP" s "-- entities or manifest unreadable/empty") nil))))
                     srcs)
        skipped (- (count srcs) (count loaded))]
    (println (str "SCANNED\t" (count srcs) "\tsources\t" skipped "\tskipped"))
    ;; Evidence floor: nothing readable is not a clean export of nothing.
    (when (empty? loaded)
      (println "no source produced entities -- refusing to report a pass")
      (js/process.exit 2))
    (let [all (mapcat :entities loaded)]
      (write-json! "watchlist_entity.json" (mapv entity-row all))
      (write-json! "watchlist_name.json" (vec (mapcat name-rows all)))
      (write-json! "watchlist_attribute.json" (vec (mapcat attribute-rows all)))
      (write-json! "watchlist_manifest.json"
                   (mapv (fn [{:keys [manifest]}]
                           {"source" (name (:manifest/source manifest))
                            "fetched_at_iso" (iso (:manifest/fetched-at manifest))
                            "source_published_at" (:manifest/source-published-at manifest)
                            "entity_count" (:manifest/entity-count manifest)
                            "source_sha256" (:manifest/sha256 manifest)
                            "source_repo" "kotoba-lang/watchlist-screen"
                            "source_commit" commit})
                         loaded))
      ;; The spec the generic Iceberg loader reads. Emitted next to the data
      ;; rather than hardcoded in the loader, so adding a table is one change
      ;; here and none there.
      (fs/writeFileSync
       (path/join out-dir "watchlist.spec.json")
       (js/JSON.stringify
        (clj->js {"namespace" "cloud_itonami"
                  "tables" [{"file" "watchlist_entity.json" "table" "watchlist_entity"
                             "int_columns" ["alias_count" "nationality_count" "program_count"]}
                            {"file" "watchlist_name.json" "table" "watchlist_name"
                             "int_columns" ["name_index"]}
                            {"file" "watchlist_attribute.json" "table" "watchlist_attribute"
                             "int_columns" ["value_index"]}
                            {"file" "watchlist_manifest.json" "table" "watchlist_manifest"
                             "int_columns" ["entity_count"]}]})
        nil 2))
      (println (str "EXPORTED\t" (count all) "\tentities from " (count loaded)
                    " sources at " commit)))))

(-main)

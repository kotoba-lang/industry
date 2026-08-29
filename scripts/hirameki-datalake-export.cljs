#!/usr/bin/env nbb
;; hirameki-datalake-export.cljs — 公開特許 corpus を datalake に載せられる
;; 4 枚の表に落とす。
;;
;; ## 何を読むか
;;
;; 読むのは **正本の EDN**（`cloud-itonami/hirameki-patents` の
;; `corpus/*.kotoba.edn`、`com.gftd.hirameki-harvest` が 4h ごとに追記する
;; corpus）。この script は特許を 1 件も取りに行かない —— 取得は harvest の
;; 仕事で、ここは形を変えるだけ。CLAUDE.md の「消して再構築できるか」に対する
;; 答えは yes: 表を全部消してもこの script で作り直せるので、lake 側は
;; projection であって premise ではない。
;;
;; ## なぜ 4 枚か
;;
;;   hirameki_patent    1 行 = 1 特許。管轄・出願年・登録年・権利者・open-license
;;   hirameki_citation  1 行 = 1 引用エッジ（citing → cited）。corpus に居ない
;;                      被引用側も行として残す（`cited_in_corpus` で区別）——
;;                      それが引用フロンティアそのもので、捨てると
;;                      「まだ取得していない」が「引用が無い」と同じ顔になる
;;   hirameki_assignee  1 行 = (特許, 権利者)。`:assignees` は複数持てるので、
;;                      1 列に畳むと「この権利者の特許」を join で引けなくなる
;;   hirameki_manifest  1 行。corpus commit・生成時刻・各表の行数
;;
;; manifest を表にするのは hyakka / watchlist と同じ理由 —— この面への query が
;; 「その答えはどのくらい古い corpus に対するものか」を **join で** 答えられる
;; ようにするため。
;;
;; ## 行数は 2 つの出所で突き合わせる
;;
;; corpus の shard を数えた値と、`publish-manifest.edn` が申告する
;; `:artifacts :corpus :rows` を両方 manifest 表に書く。**一致しないときは
;; 落とさず両方載せる** —— harvest の途中で読めば食い違うのが正常で、
;; そこで REFUSE すると 4h ごとに 1 回は必ず赤くなる gate ができる。
;; 食い違いは `rows_agree` 列で query から見える。
;;
;; ## なぜ 2 プロセスに分かれているか
;;
;; EDN を読むのは nbb（この workspace の script host）、Iceberg の commit は
;; pyiceberg（nbb に Avro manifest / metadata.json の writer は無い）。
;; 境界はこの JSON と、隣に書く spec。
;;
;;     nbb scripts/hirameki-datalake-export.cljs [--repo <path>] [--out-dir <dir>]
;;     python3 scripts/datalake-sync.py --spec /tmp/hirameki.spec.json --in-dir /tmp
;;
;; exit: 0 載せられる JSON を書いた / 2 REFUSED（読めなかった。空ではない）

(ns hirameki-datalake-export
  (:require [cljs.reader :as edn]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(defn- flag [n] (let [i (.indexOf (clj->js argv) n)]
                  (when (and (>= i 0) (< (inc i) (count argv))) (nth argv (inc i)))))

(def repo (or (flag "--repo")
              "/Users/junkawasaki/github/com-junkawasaki/orgs/cloud-itonami/hirameki-patents"))
(def out-dir (or (flag "--out-dir") "/tmp"))
(def corpus-dir (path/join repo "corpus"))

(defn die! [code msg]
  (js/console.error msg)
  (.exit js/process code))

(defn shard-files [dir]
  (when-not (fs/existsSync dir)
    (die! 2 (str "REFUSED: no corpus at " dir
                 "\nThe projection cannot be rebuilt from a tree that is not there,"
                 "\nand an empty export would look exactly like a corpus with no patents.")))
  (->> (fs/readdirSync dir)
       (filter #(str/ends-with? % ".kotoba.edn"))
       sort
       (mapv #(path/join dir %))))

(defn s [v]
  (cond (nil? v) nil
        (string? v) v
        (keyword? v) (name v)
        :else (pr-str v)))

(defn i [v] (when (number? v) v))

(defn patent-row [p]
  {"patent_id" (s (:id p))
   "title" (s (:title p))
   "jurisdiction" (s (:jurisdiction p))
   "field" (s (:field p))
   "status" (s (:status p))
   ;; `:authoritative` = the record came from the issuing office's own page;
   ;; `:representative` = a curated stand-in. Collapsing these would let seven
   ;; hand-written rows read as harvested evidence.
   "sourcing" (s (:sourcing p))
   "assignee" (s (:assignee p))
   "assignee_count" (count (:assignees p))
   "filing_year" (i (:filing-year p))
   "grant_year" (i (:grant-year p))
   "term_years" (i (:term-years p))
   "open_license" (s (:open-license p))
   "essentiality" (s (:essentiality p))
   "cites_count" (count (:cites p))
   "source_url" (s (:source p))})

(defn citation-rows [p in-corpus?]
  (for [c (:cites p)]
    {"citing_patent_id" (s (:id p))
     "cited_patent_id" (s c)
     "citing_jurisdiction" (s (:jurisdiction p))
     ;; The frontier: a cited patent this corpus does not hold yet. Dropping
     ;; these rows would make "not fetched yet" indistinguishable from
     ;; "cites nothing".
     "cited_in_corpus" (if (in-corpus? (s c)) "true" "false")}))

(defn assignee-rows [p]
  (for [a (:assignees p)]
    {"patent_id" (s (:id p))
     "assignee" (s a)
     "jurisdiction" (s (:jurisdiction p))
     "filing_year" (i (:filing-year p))}))

(defn write-json! [name rows]
  (fs/mkdirSync out-dir #js {:recursive true})
  (let [p (path/join out-dir name)]
    (fs/writeFileSync p (js/JSON.stringify (clj->js rows)))
    (println (str "WROTE\t" (count rows) "\t" p))
    (count rows)))

(defn git [& args]
  (let [r (cp/spawnSync "git" (clj->js (concat ["-C" repo] args))
                        #js {:encoding "utf8" :timeout 60000})]
    (str/trim (str (or (.-stdout r) "")))))

(defn manifest-rows
  "What `publish-manifest.edn` says the corpus holds. nil when unreadable —
  UNMEASURED, not zero."
  []
  (try
    (-> (edn/read-string (fs/readFileSync (path/join repo "publish-manifest.edn") "utf8"))
        :artifacts :corpus :rows)
    (catch :default _ nil)))

(defn -main []
  (let [files (shard-files corpus-dir)]
    (when (empty? files)
      (die! 2 (str "REFUSED: " corpus-dir " holds no .kotoba.edn shard."
                   "\nZero shards is not zero patents; it is an unread tree.")))
    (println (str "SCANNED\t" (count files) "\tcorpus shards"))
    (let [patents (vec (mapcat #(edn/read-string (fs/readFileSync % "utf8")) files))]
      (when (empty? patents)
        (die! 2 "REFUSED: read the corpus and found no patents. That is not a corpus with nothing in it."))
      (let [;; Last write wins on id: the harvester appends, and a re-harvest of
            ;; the same patent restates it rather than duplicating the row.
            by-id (reduce #(assoc %1 (s (:id %2)) %2) {} patents)
            uniq (vec (vals by-id))
            in-corpus? (set (keys by-id))
            cites (vec (mapcat #(citation-rows % in-corpus?) uniq))
            assignees (vec (mapcat assignee-rows uniq))
            declared (manifest-rows)]
        (write-json! "hirameki_patent.json" (mapv patent-row uniq))
        (write-json! "hirameki_citation.json" cites)
        (write-json! "hirameki_assignee.json" assignees)
        (write-json! "hirameki_manifest.json"
                     [{"corpus_commit" (git "rev-parse" "HEAD")
                       "corpus_shards" (count files)
                       "patent_rows" (count uniq)
                       "patent_rows_read" (count patents)
                       ;; What the corpus itself declares, so a reader can see
                       ;; the two numbers rather than trust one of them.
                       "declared_rows" (or declared -1)
                       "rows_agree" (if (nil? declared)
                                      "unmeasured"
                                      (str (= declared (count patents))))
                       "citation_rows" (count cites)
                       "assignee_rows" (count assignees)
                       "frontier_rows" (count (remove #(= "true" (get % "cited_in_corpus")) cites))
                       "generated_at" (.toISOString (js/Date.))
                       "repo" repo}])
        ;; The spec the generic Iceberg loader reads. Emitted next to the data so
        ;; an exporter and its spec cannot drift when one writes the other.
        (fs/writeFileSync
         (path/join out-dir "hirameki.spec.json")
         (js/JSON.stringify
          (clj->js {"namespace" "cloud_itonami"
                    "tables" [{"file" "hirameki_patent.json" "table" "hirameki_patent"
                               "int_columns" ["assignee_count" "filing_year" "grant_year"
                                              "term_years" "cites_count"]
                               "partition_by" ["jurisdiction"]}
                              {"file" "hirameki_citation.json" "table" "hirameki_citation"
                               "int_columns" []}
                              {"file" "hirameki_assignee.json" "table" "hirameki_assignee"
                               "int_columns" ["filing_year"]}
                              {"file" "hirameki_manifest.json" "table" "hirameki_manifest"
                               "int_columns" ["corpus_shards" "patent_rows" "patent_rows_read"
                                              "declared_rows" "citation_rows" "assignee_rows"
                                              "frontier_rows"]}]})
          nil 2))
        (println (str "SPEC\t" (path/join out-dir "hirameki.spec.json")))))))

(-main)

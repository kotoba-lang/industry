#!/usr/bin/env nbb
;; hyakka-datalake-export.cljs — wiki.kotobase.net の claim graph を
;; datalake に載せられる 5 枚の表に落とす。
;;
;; ## 何を読むか
;;
;; 読むのは **正本の EDN**（`network-awai/app-hyakka` の
;; `knowledge/ledger/**/*.datoms.edn`、あちらの resident が書く append-only な
;; 台帳）。この script は事実を 1 つも取りに行かない —— 取得・抽出・admission は
;; 正本の仕事で、ここは形を変えるだけ。CLAUDE.md の「消して再構築できるか」に
;; 対する答えは yes: 表を全部消してもこの script で作り直せる。
;;
;; ## なぜ 5 枚か
;;
;;   hyakka_claim     1 行 = 1 claim。subject/property/value と出所。この面の中心
;;   hyakka_item      1 行 = 1 item。label と class
;;   hyakka_prop      1 行 = 1 property。50 行しかないが、property 名だけで
;;                    join したいクエリが毎回 label を引き直さずに済む
;;   hyakka_source    1 行 = 1 source。url・publisher・retrieved-at・archived-cid
;;   hyakka_manifest  1 行。ledger commit・生成時刻・各表の行数
;;
;; manifest を表にするのは watchlist-datalake-export.cljs と同じ理由 ——
;; この面への query が「その答えはどのくらい古いデータに対するものか」を
;; **join で** 答えられるようにするため。件数だけ載せて出所の commit を落とすと、
;; stale な答えと fresh な答えが区別できなくなる。
;;
;; ## qualifiers を列に開く理由
;;
;; 台帳の `:claim/qualifiers` は EDN map を pr-str した **文字列**で、
;; `{:evidence … :extractor … :model … :connector … :source-sha256 …}` が入って
;; いる。文字列のまま載せると、この表を読む側が全員それを parse し直すことに
;; なり、**parse を書き直した瞬間に答えが分岐する**。特に `extractor` は
;; 「決定論的コネクタが書いた claim」と「LLM が抽出した claim」を分ける列で、
;; ADR-2608271450 が記録した事故（抽出が死んでいるのに wiki は育っている）を
;; **クエリ 1 本で見分けられるようにする**のがこの列の存在理由。
;;
;; 開かなかった残りは `qualifiers_raw` にそのまま置く。捨てない。
;;
;; ## id で dedup する（後勝ち）
;;
;; 台帳は append-only で、同じ claim / item / prop が複数のファイルに現れる。
;; dedup しないと行数が wiki の公表値を超え、**投影が正本と静かに食い違う**。
;; ファイルは時刻順に読み、後から来たものを採る。
;;
;; ## なぜ 2 プロセスに分かれているか
;;
;; EDN を読むのは nbb（この workspace の script host）、Iceberg の commit は
;; pyiceberg（nbb に Avro manifest / metadata.json の writer は無い）。
;; 境界はこの JSON と、隣に書く spec。
;;
;;     scripts/hyakka-datalake-export.cljs [--worktree <path>] [--out-dir <dir>]
;;     python3 scripts/datalake-sync.py --spec /tmp/hyakka.spec.json --in-dir /tmp

(ns hyakka-datalake-export
  (:require [cljs.reader :as edn]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            ["node:child_process" :as cp]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(defn- flag [n] (let [i (.indexOf (clj->js argv) n)]
                  (when (and (>= i 0) (< (inc i) (count argv))) (nth argv (inc i)))))

(def worktree (or (flag "--worktree")
                  (str (.homedir os) "/.itonami/worktrees/app-hyakka-resident")))
(def out-dir (or (flag "--out-dir") "/tmp"))
(def ledger-dir (path/join worktree "knowledge" "ledger"))

(defn die! [code msg]
  (js/console.error msg)
  (.exit js/process code))

(defn ledger-files [dir]
  (if-not (fs/existsSync dir)
    (die! 2 (str "REFUSED: no ledger at " dir
                 "\nThe projection cannot be rebuilt from a tree that is not there,"
                 "\nand an empty export would look exactly like an empty wiki."))
    (sort (mapcat (fn walk [p]
                    (let [st (fs/statSync p)]
                      (if (.isDirectory st)
                        (mapcat walk (sort (map #(path/join p %) (fs/readdirSync p))))
                        (when (str/ends-with? p ".datoms.edn") [p]))))
                  [dir]))))

(defn kind-of [e]
  (->> (keys e) (keep namespace) (remove #{"db"}) first))

(defn parse-qualifiers [s]
  (try (let [v (edn/read-string (str s))] (when (map? v) v))
       (catch :default _ nil)))

(defn s [v] (cond (nil? v) nil (string? v) v :else (pr-str v)))

(defn claim-row [e]
  (let [q (parse-qualifiers (:claim/qualifiers e))]
    {"claim_id" (s (:claim/id e))
     "subject" (s (:claim/subject e))
     "property" (s (:claim/property e))
     "value" (s (:claim/value e))
     "value_item" (s (:claim/value-item e))
     "corpus" (s (:claim/corpus e))
     "source_id" (s (:claim/source e))
     "asserted_at" (s (:claim/asserted-at e))
     "asserted_by" (s (:claim/asserted-by e))
     "confidence" (s (:claim/confidence e))
     "rank" (s (:claim/rank e))
     ;; The column ADR-2608271450 exists for: deterministic connector output and
     ;; LLM extraction are indistinguishable in the wiki's own totals.
     "extractor" (s (:extractor q))
     "model" (s (:model q))
     "connector" (s (:connector q))
     "source_sha256" (s (:source-sha256 q))
     "code_revision" (s (:code-revision q))
     "evidence" (s (:evidence q))
     "qualifiers_raw" (s (:claim/qualifiers e))}))

(defn item-row [e]
  {"item_id" (s (:item/id e))
   "label" (s (:item/label e))
   "label_ja" (s (:item/label-ja e))
   "class" (s (:item/class e))
   "corpus" (s (:item/corpus e))
   "aliases" (s (:item/aliases e))
   "created_at" (s (:item/created-at e))})

(defn prop-row [e]
  {"prop_id" (s (:prop/id e))
   "label" (s (:prop/label e))
   "label_ja" (s (:prop/label-ja e))
   "datatype" (s (:prop/datatype e))})

(defn source-row [e]
  {"source_id" (s (:source/id e))
   "url" (s (:source/url e))
   "title" (s (:source/title e))
   "publisher" (s (:source/publisher e))
   "access" (s (:source/access e))
   "retrieved_at" (s (:source/retrieved-at e))
   "archived_cid" (s (:source/archived-cid e))})

(defn write-json! [name rows]
  (fs/mkdirSync out-dir #js {:recursive true})
  (let [p (path/join out-dir name)]
    (fs/writeFileSync p (js/JSON.stringify (clj->js rows)))
    (println (str "WROTE\t" (count rows) "\t" p))
    (count rows)))

(defn git [& args]
  (let [r (cp/spawnSync "git" (clj->js (concat ["-C" worktree] args))
                        #js {:encoding "utf8" :timeout 60000})]
    (str/trim (str (or (.-stdout r) "")))))

(defn -main []
  (let [files (ledger-files ledger-dir)]
    (when (empty? files)
      (die! 2 (str "REFUSED: " ledger-dir " holds no .datoms.edn."
                   "\nZero ledger files is not zero claims; it is an unread tree.")))
    (println (str "SCANNED\t" (count files) "\tledger files"))
    (let [;; Last write wins: the ledger is append-only and files are read in
          ;; chronological name order, so a later restatement supersedes.
          acc (reduce
               (fn [m f]
                 (reduce (fn [m e]
                           (case (kind-of e)
                             "claim" (assoc-in m [:claim (:claim/id e)] (claim-row e))
                             "item" (assoc-in m [:item (:item/id e)] (item-row e))
                             "prop" (assoc-in m [:prop (:prop/id e)] (prop-row e))
                             "source" (assoc-in m [:source (:source/id e)] (source-row e))
                             m))
                         m
                         (edn/read-string (fs/readFileSync f "utf8"))))
               {} files)
          claims (vec (vals (:claim acc)))
          items (vec (vals (:item acc)))
          props (vec (vals (:prop acc)))
          sources (vec (vals (:source acc)))]
      (when (empty? claims)
        (die! 2 "REFUSED: read the ledger and found no claims. That is not a wiki with nothing in it."))
      (write-json! "hyakka_claim.json" claims)
      (write-json! "hyakka_item.json" items)
      (write-json! "hyakka_prop.json" props)
      (write-json! "hyakka_source.json" sources)
      (write-json! "hyakka_manifest.json"
                   [{"ledger_commit" (git "rev-parse" "HEAD")
                     "ledger_files" (count files)
                     "claim_rows" (count claims)
                     "item_rows" (count items)
                     "prop_rows" (count props)
                     "source_rows" (count sources)
                     "generated_at" (.toISOString (js/Date.))
                     "worktree" worktree}])
      ;; The spec the generic Iceberg loader reads. Emitted next to the data so
      ;; an exporter and its spec cannot drift when one writes the other.
      (fs/writeFileSync
       (path/join out-dir "hyakka.spec.json")
       (js/JSON.stringify
        (clj->js {"namespace" "cloud_itonami"
                  "tables" [{"file" "hyakka_claim.json" "table" "hyakka_claim" "int_columns" []}
                            {"file" "hyakka_item.json" "table" "hyakka_item" "int_columns" []}
                            {"file" "hyakka_prop.json" "table" "hyakka_prop" "int_columns" []}
                            {"file" "hyakka_source.json" "table" "hyakka_source" "int_columns" []}
                            {"file" "hyakka_manifest.json" "table" "hyakka_manifest"
                             "int_columns" ["ledger_files" "claim_rows" "item_rows"
                                            "prop_rows" "source_rows"]}]})
        nil 2))
      (println (str "SPEC\t" (path/join out-dir "hyakka.spec.json"))))))

(-main)

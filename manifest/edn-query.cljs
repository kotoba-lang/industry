#!/usr/bin/env nbb
;; manifest/edn-query.cljs — ADR ledger + RAD identity journal を
;; kotoba-lang/datalog にロードして query するツール（ADR-2608260200）。
;;
;; 対象:
;;   - 90-docs/adr/*.edn（manifest/edn-datomize.cljs で tx-data 化済み。
;;     [{:db/id -1 ...}] 形式の1エンティティ）
;;   - orgs/etzhayyim/root/80-data/kotoba-rad/*.identity.journal.edn
;;     （生 datom タプル [e a v tx :add|:retract] の羅列。DataScript には
;;     Datomic のような transaction history API が無いため、tx 順に replay して
;;     「現在値」の entity map に圧縮してから読み込む — 履歴クエリは対象外、
;;     現在状態のみ）。orgs/etzhayyim/root が未 checkout の場合は黙ってスキップする。
;;
;; 属性の型変換について: このローダは属性を keyword ではなく
;; **裸の文字列**（例 "adr/id"、コロン無し）として扱う。:db/id だけ特別扱いで
;; 整数 entity id。元の edn の keyword 値もこのローダでは
;; 文字列化する（"was keyword" という型情報は失われる — MVP の既知の制約。
;; 必要になったら別属性に :value/type "keyword" のようなマーカーを足す）。
;;
;; 使い方（classpath は下の `classpath` var と同じ 4 項目。`.` と
;; `scripts/nbb_compat` だけでは `datalog.core` が見つからない）:
;;
;;   CP=".:scripts/nbb_compat:orgs/kotoba-lang/datalog/src:orgs/kotoba-lang/datom-source/src"
;;   NODE_OPTIONS=--max-old-space-size=8192 \
;;     nbb --classpath "$CP" manifest/edn-query.cljs refresh   # 面を組んで shard に書く
;;
;; **面を丸ごと materialise する操作は heap を上げる。** 組むときだけの話ではない
;; —— 実測 2026-09-06、既定 heap では build も cache からのロードも OOM した
;; （前者 4.07GB/86 秒、後者 79 秒）。面そのものが既定の old-space に入らないので、
;; どう保存しても変わらない。対象は `refresh` と `count` と、entity 変数が
;; dataset に縛られていない `q`（= `shardable` が :full と言う query）。
;;
;; 逆に **dataset を名指した query は既定 heap のまま数秒で返る** —— shard を
;; 名指した分だけ読むので面を組まない（実測: market-intel 1 枚で 4.6 秒）。
;; 常用するなら、この形に寄せるのが一番効く。
;;   nbb --classpath "$CP" manifest/edn-query.cljs count
;;   nbb --classpath "$CP" manifest/edn-query.cljs q '[:find ?id ?status :where
;;                                   [?e "adr/id" ?id] [?e "adr/status" ?status]]'
;;   nbb --classpath "$CP" manifest/edn-query.cljs q* '<q1>' '<q2>' ...
;;                                       # 面を 1 回だけ組んで N 本流す。結果は
;;                                       # 同じ順のベクタ 1 行。所要時間はロードが
;;                                       # 支配するので、N 本を別プロセスで叩くと
;;                                       # そのまま N 倍かかる
;;   nbb --classpath "$CP" manifest/edn-query.cljs mcp   # 常駐 MCP server（stdio, JSON-RPC）
;;
;; MCP client の設定（この面を datalog を知らなくても聞けるようにする）:
;;
;;   {"mcpServers": {"kotoba-query-plane": {
;;      "command": "nbb",
;;      "args": ["--classpath",
;;               ".:scripts/nbb_compat:orgs/kotoba-lang/datalog/src:orgs/kotoba-lang/datom-source/src",
;;               "manifest/edn-query.cljs", "mcp"],
;;      "cwd": "<superproject root>"}}}
;;
;; tools: plane_query（生 datalog）/ company_profile（LEI 結合済みの企業像）/
;; dataset_counts。ロードは初回 query 時に 1 回だけ（約 46 秒）、以後の query は
;; 十数 ms（実測 2026-07-30）。cwd が superproject root である必要があるのは、
;; 入力パスを git rev-parse --show-toplevel から解決しているため。

(require '[scripts.nbb-compat :as nbb-compat :refer [file-seq format]]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str]
         '[manifest.edn-query-datalog :as dlq]
         '["fs" :as node-fs]
         '["node:crypto" :as node-crypto])

;; ---------- 何を読んだかを記録する（キャッシュの鮮度判定の材料） ----------
;;
;; キャッシュの鍵を「宣言した root を walk して hash」にはできない。この面が
;; 読むのは `orgs/cloud-itonami` の下の 185 個の repo だが、その親には 1,852 個の
;; repo が居て、walk すると数百万ファイルを stat することになる。
;;
;; だから **ビルドが実際に読んだファイル** を記録する。読む口は `slurp` 1 つに
;; 集約されているので、ここを通れば漏れない —— 新しい loader を足しても、
;; その loader が slurp を使う限り自動的に記録される。
;;
;; ファイルだけでは「ディレクトリに新しいファイルが増えた」を検出できないので、
;; 読んだファイルの親と祖父ディレクトリの **エントリ名の digest** も併せて記録する
;; （新しい lei repo が生えた場合は祖父の `orgs/cloud-itonami` 側で捕まる）。

(def ^:private reads-seen (atom #{}))

(defn slurp
  "`scripts.nbb-compat/slurp` に読んだパスの記録を足したもの。名前を保つのは、
   この面の 30 近い呼び出し側を書き換えないため。"
  [path]
  (swap! reads-seen conj (str path))
  (nbb-compat/slurp path))


(def root (str/trim (:out (shell/sh "git" "rev-parse" "--show-toplevel"))))

(declare adl-decode)

(defn- adl-entry [e]
  (when-not (and (seq? e) (= 2 (count e)))
    (throw (ex-info (str "map entry must be a (k v) form, got " (pr-str e)) {})))
  [(adl-decode (first e)) (adl-decode (second e))])

(defn adl-decode
  "Kotoba ADL notation -> the value it denotes (ADR-2609101300).

   A .kotoba document is S-expressions, so the EDN reader parses it and hands
   back the NOTATION -- nested lists -- not the value. Every loader below walks
   maps, so an undecoded document contributes no entities and `count` simply
   gets smaller: the dataset leaves the query face without any loader reporting
   an error. Decoding here, in the one shared read helper, keeps that from
   being something each loader has to remember."
  [x]
  (cond
    (vector? x) (mapv adl-decode x)
    (not (seq? x)) x
    :else
    (let [h (first x) more (rest x)
          t (when (symbol? h) (name h))]
      (case t
        "map" (let [pairs (mapv adl-entry more)
                    ks (map first pairs)]
                (when-not (= (count ks) (count (set ks)))
                  (throw (ex-info "duplicate key in (map ...)" {})))
                (into {} pairs))
        "vector" (mapv adl-decode more)
        "set" (set (map adl-decode more))
        "list" (apply list (map adl-decode more))
        ("uuid" "timestamp" "bytes" "link") (str (first more))
        (doall (map adl-decode x))))))

(defn data-file?
  "Either notation. One extension no longer tells you what a file holds
   (ADR-2609101300), so enumerations ask this rather than testing for .edn."
  [p]
  (let [s (str p)]
    (or (str/ends-with? s ".edn") (str/ends-with? s ".kotoba"))))

(defn slurp-edn
  "未知の reader タグ（例 #md \"...\"）はタグを捨てて値だけ返すフォールバックを
   常に有効にする（さもないと clojure.edn/read-string がタグ未知で例外を投げ、
   呼び出し側の try/catch で丸ごとスキップされてしまう — 各カテゴリで
   個別にタグを解釈するより、ここで一箇所吸収する方が安全）。"
  [path]
  (let [parsed (edn/read-string {:default (fn [_tag v] v)} (slurp path))]
    (if (str/ends-with? (str path) ".kotoba") (adl-decode parsed) parsed)))

(defn slurp-edn-lines
  "1行1トップレベルEDNフォームの疑似JSONL形式を読む（RAD identity journal・
   一部の *.datoms.kotoba.edn がこの形。レコードが逐次1行ずつ追記される想定で、
   単一の妥当な edn ベクタとしては書かれていない）。空行・`;`で始まるコメント行・
   parse できない行は黙ってスキップする（先頭のファイルヘッダコメント等）。"
  [path]
  (->> (str/split-lines (slurp path))
       (remove str/blank?)
       (remove #(str/starts-with? (str/trim %) ";"))
       (keep (fn [line]
               (try (edn/read-string {:default (fn [_tag v] v)} line)
                    (catch :default _ nil))))
       vec))

;; ---------- attr/value 変換（cljs keyword <-> datascript.js の裸文字列） ----------

(defn kw->attr [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (and (string? k) (str/starts-with? k ":") (> (count k) 1)) (subs k 1)
    (string? k) k
    :else (str k)))

(defn ->ds-scalar [v]
  (cond
    (keyword? v) (kw->attr v)
    ;; 一部の .kotoba.edn は keyword を \":foo\" のように文字列としてシリアライズ
    ;; している（datom タプルの attr/value 位置どちらも）。他ソースの真の
    ;; keyword 値（コロン無しに正規化）と揃えるため同様に扱う。
    (and (string? v) (str/starts-with? v ":") (> (count v) 1)) (subs v 1)
    (nil? v) ""
    :else v))

(defn ->ds-scalar? [v]
  (or (string? v) (number? v) (boolean? v) (keyword? v) (nil? v)))

(defn ->ds-value
  "スカラーの vector/list はそのまま JS array に、それ以外の非scalar
   （入れ子 map / map を含む vector 等）は pr-str して blob 文字列にする
   （manifest/edn-datomize.cljs の classify/attr-value と同じ方針）。"
  [v]
  (cond
    (map? v) (pr-str v)
    ;; 集合はここに来るまで素通しだった。map / vector と違い pr-str されないので
    ;; CLJS の集合オブジェクトのまま JS 側へ渡り、datascript の serializable が
    ;; `#object[Object #{...}]` と書いて **読み戻せない db** を作っていた
    ;; （実測 2026-08-25: 約 100 万 datom 中 45 件がこれで、面のキャッシュ化を
    ;; 阻んでいた唯一の原因）。map と同じく文字列にする。
    (set? v) (pr-str v)
    (and (or (vector? v) (seq? v) (list? v)) (every? ->ds-scalar? v))
    (into-array (map ->ds-scalar v))
    (or (vector? v) (seq? v) (list? v)) (pr-str v)
    :else (->ds-scalar v)))

(defn entity->js
  "cljのentity map（{:db/id -N, :ns/attr val, ...}）を datascript.js 向け
   JS object に変換。:db/id はコロン付き文字列キー \":db/id\"、他の属性は
   裸文字列キー（namespace/name、コロン無し）にする。"
  [m]
  (let [obj (js-obj)]
    (doseq [[k v] m]
      (if (= k :db/id)
        (aset obj ":db/id" v)
        (aset obj (kw->attr k) (->ds-value v))))
    obj))

;; ---------- 汎用シェイプリーダー（複数カテゴリで共有） ----------

(defn wrapped-entity
  "既に tx-data 化済み（[{:db/id -1 ...}]）な edn ファイルを読み、中身の1
   entity map を返す（manifest/edn-datomize.cljs の wrap-map!/adr-dir! が
   生成する形。manifest/repos.edn・kotoba-boundaries.edn・cleanup-workflow.edn・
   この repo 自身の 90-docs/adr/*.edn が該当）。shape 不一致や parse エラーは
   nil でスキップする。"
  [f]
  (try
    (let [content (slurp-edn f)]
      (when (and (vector? content) (seq content) (map? (first content)))
        (first content)))
    (catch :default _ nil)))

(defn single-map-entity
  "トップレベルが単一の flat(ish) map な edn ファイル（tx-data 化されていない、
   :db/id も無い）を読み、bare key に ns-name の名前空間を付けて entity map化
   する。既に名前空間付きのキー（例 :kj/foo）はそのまま使う。90-docs/business/
   metrics/*.edn・orgs/kawasakijun/*.edn 向け。"
  [f ns-name]
  (try
    (let [content (slurp-edn f)]
      (when (map? content)
        (into {}
              (map (fn [[k v]]
                     [(if (and (keyword? k) (namespace k)) k (keyword ns-name (name k))) v]))
              content)))
    (catch :default _ nil)))

(defn vector-of-maps-entities
  "トップレベルが [{...} {...} ...]（既に flat map のベクタ）な edn を読み、
   各 map をそのまま entity として返す（kanjo/kabuto の merged.kotoba.edn・
   actor の procedure-registry.edn 等）。"
  [f]
  (try
    (let [content (slurp-edn f)]
      (when (vector? content)
        (filter map? content)))
    (catch :default _ nil)))

(defn dict-of-entities
  "トップレベルが {\"code\" {...attrs...} ...} な edn（キーが entity 識別子、
   値が flat map）を読み、各 value map に :key-attr 属性で元のキーを足して
   entity 化する（unspsc-taxonomy.edn 向け）。"
  [f key-attr]
  (try
    (let [content (slurp-edn f)]
      (when (map? content)
        (for [[k v] content :when (map? v)]
          (assoc v (keyword key-attr) (str k)))))
    (catch :default _ nil)))

(defn seed-vector-entities
  "トップレベルが {:seed [{...} ...] ...} のようなラッパー map な edn を読み、
   wrapper-key の中身（flat map のベクタ）だけ entity として取り出す
   （cleanroom-actors-seed.kotoba.edn 向け）。"
  [f wrapper-key]
  (try
    (let [content (slurp-edn f)]
      (when (map? content)
        (filter map? (get content wrapper-key))))
    (catch :default _ nil)))

(defn ->op-add?
  "op マーカーが real keyword :db/add か、文字列 \":db/add\" かの両方あり得る
   （.kotoba.edn 生成元の実装差）。"
  [x] (or (= x :db/add) (= x ":db/add")))

(defn add-datoms-entities
  "[:db/add e a v] 4要素タプルの列を読み、entity-id ごとに {e {a v ...}} へ
   グルーピングして entity map 化する（junkan-governance/hirameki-patents の
   *.datoms.kotoba.edn 向け）。wrapper-key が nil なら1行1タプルの疑似JSONL
   （governance-asymmetry・hirameki-patents はこの形。ファイル先頭にコメント行
   もある）として読み、wrapper-key が指定されていれば単一の妥当な edn map
   として読んでその key の中身（vector）を使う（findings-ledger.kotoba.edn
   の :tx/datoms 向け）。"
  [f wrapper-key id-attr]
  (try
    (let [datoms (if wrapper-key (get (slurp-edn f) wrapper-key) (slurp-edn-lines f))]
      (when (vector? datoms)
        (let [grouped (reduce
                       (fn [acc tuple]
                         (if (and (vector? tuple) (= 4 (count tuple)) (->op-add? (first tuple)))
                           (let [[_add e a v] tuple] (assoc-in acc [e a] v))
                           acc))
                       {}
                       datoms)]
          (for [[eid attrs] grouped]
            (assoc attrs (keyword id-attr) (str eid))))))
    (catch :default _ nil)))

(defn seed-vector-entities-any
  "seed-vector-entities を候補 wrapper-key 群で順に試し、最初に非空の結果を
   返したものを使う（jinushi-land の各 .kotoba.edn はファイルごとに :records /
   :buildings 等キー名が違うため）。"
  [f wrapper-keys]
  (some (fn [k] (let [r (seed-vector-entities f k)] (when (seq r) r))) wrapper-keys))

(defn line-map-entities
  "1行1トップレベル flat map の疑似JSONL edn（すでに :ns/attr 形式のキーを
   持つ map。canvas-ledger.edn・hirameki-patents corpus 向け）を読み、
   各行をそのまま entity として返す（tuple replay は不要 — 各行が
   既に完成した1レコード）。"
  [f]
  (try
    (->> (slurp-edn-lines f) (filter map?))
    (catch :default _ nil)))

;; ---------- ADR ledger (90-docs/adr/*.edn) ----------
;;
;; 正本は EDN only（ADR-2607171600）。.md は置かない。
;; 各ファイルは:
;;   - 1 entity の tx-data `[{:db/id -1 :adr/* ...}]`（通常 ADR）
;;   - 複数 entity の catalog（`*.datoms.edn` 等）— 全 entity をロード
;;   - まれに bare map — in-memory で :adr/* 化

(defn adr-files []
  (->> (file-seq (io/file root "90-docs" "adr"))
       (filter #(data-file? %))
       (sort-by str)))

(defn adr-entities-from-file
  "1 ADR ファイルから 0+ entity map を返す（disk は書き換えない）。"
  [f]
  (try
    (let [content (slurp-edn f)]
      (cond
        (and (vector? content) (seq content) (every? map? content))
        (map-indexed (fn [i e] (assoc e :db/id (- (inc i))
                                      :source/file (str f)))
                     content)

        (map? content)
        (let [fm (:frontmatter content)
              base (dissoc content :frontmatter :body-file)
              entries (concat (when (map? fm) (seq fm)) (seq base))
              e (into {:db/id -1 :source/file (str f)}
                      (map (fn [[k v]]
                             [(if (and (keyword? k) (namespace k)) k (keyword "adr" (name k))) v]))
                      entries)]
          [e])

        :else nil))
    (catch :default _ nil)))

(defn adr-entity
  "後方互換: ファイルの先頭 entity だけ（count 用の薄い wrapper）。"
  [f]
  (first (adr-entities-from-file f)))

;; ---------- 90-docs 配下の非-adr ドキュメント（:doc/*） ----------

(defn docs-edn-files []
  (->> (file-seq (io/file root "90-docs"))
       (filter #(data-file? %))
       (remove #(str/includes? (str %) "/adr/"))
       (remove #(str/includes? (str %) "/metrics/")) ; metrics は business-metrics で別ロード
       (remove #(str/ends-with? (str %) "canvas-ledger.edn")) ; canvas-ledger で別ロード
       (remove #(str/ends-with? (str %) "design-quality-ledger.edn"))
       ;; training-corpus は index-sources 側でロードする（下の「索引データ」節）。
       ;; ここで拾うと **同じ entity が 2 回**入る。実測 2026-08-05: 除外前は
       ;; `[?e "source/dataset" "training-corpus"]` の tier 別集計が
       ;; gold 2230 / silver 1532 と、正しい値のちょうど 2 倍になった
       ;; （この面は entity 自身が `:source/dataset` を持つので、docs 経由の
       ;; コピーも同じ dataset を名乗ってしまう）。
       ;;
       ;; ⚠ surface / concept / engine-parity / gtm-icp / itonami-maturity は
       ;; **今も両方の経路でロードされている**。あちらは entity 側が
       ;; `:source/dataset` を持たず index ローダが後から付けるので dataset 別の
       ;; 集計は二重にならないが、entity 自体は重複している。既存の集計値を
       ;; 動かさないためここでは触っていない（別途 ADR で扱う）。
       (remove #(str/ends-with? (str %) "corpus/corpus.datoms.edn"))
       ;; arday-cofnas-affair も index-sources 側でロードする。ここで拾うと
       ;; **同じ entity が 2 回**入り、entity 自身が `:source/dataset` を持つので
       ;; training-corpus と同じく dataset 別の集計がちょうど 2 倍になる。
       (remove #(str/ends-with? (str %) "knowledge/arday-cofnas-affair.datoms.edn"))
       (sort-by str)))

(defn doc-entities-from-file [f]
  (try
    (let [content (slurp-edn f)]
      (cond
        (and (vector? content) (seq content) (every? map? content))
        (map-indexed (fn [i e] (assoc e :db/id (- (inc i)) :source/file (str f))) content)

        (map? content)
        [(assoc content :db/id -1 :source/file (str f))]

        :else nil))
    (catch :default _ nil)))

;; ---------- manifest/*.edn（既に tx-data 化済み。category C） ----------

(defn manifest-edn-files []
  (->> ["repos.edn" "kotoba-boundaries.edn" "cleanup-workflow.edn"]
       (map #(io/file root "manifest" %))
       (filter #(.exists %))))
;; schema.edn は意図的に対象外（属性メタスキーマそのもので、既に ds-schema
;; が構造的に消費している。instance data として二重にロードすると混乱する）。

;; ---------- 他リポの ADR ledger（未変換。category B） ----------

(defn foreign-adr-sources []
  [{:dir (io/file root "orgs" "gftdcojp" "ai-gftd-apps-gftdcojp" "90-docs" "adr")
    :repo "gftdcojp/ai-gftd-apps-gftdcojp"}
   {:dir (io/file root "orgs" "gftdcojp" "club-shinshi" "90-docs" "adr")
    :repo "gftdcojp/club-shinshi"}
   {:dir (io/file root "orgs" "etzhayyim" "root" "90-docs" "adr")
    :repo "etzhayyim/root"}])

(defn foreign-adr-files [{:keys [dir]}]
  (if (.exists dir)
    (->> (file-seq dir)
         (filter #(data-file? %))
         (remove #(re-find #"(?i)(index|registry)\.edn$" (str %)))
         (sort-by str))
    []))

(defn foreign-adr-entity
  "他リポの ADR edn を読み、:adr/* 名前空間の entity map を返す（in-memory
   変換のみ・ディスクへの書き戻しはしない）。既に tx-data 化済み
   （[{:db/id ...}]）・生の {:frontmatter {...} :body \"...\"} 形式・
   フラットな {:id ... :status ...} 形式（etzhayyim/root）のいずれも扱う。
   :body-file（外部 .md への参照。本文は追わない）は落とす。"
  [f source-repo]
  (try
    (let [content (slurp-edn f)]
      (cond
        (and (vector? content) (seq content) (map? (first content)))
        (assoc (first content) :source/repo source-repo)

        (map? content)
        (let [fm (:frontmatter content)
              base (dissoc content :frontmatter :body-file)
              entries (concat (when (map? fm) (seq fm)) (seq base))]
          (when (seq entries)
            (into {:db/id -1 :source/repo source-repo}
                  (map (fn [[k v]] [(if (and (keyword? k) (namespace k)) k (keyword "adr" (name k))) v]))
                  entries)))

        :else nil))
    (catch :default _ nil)))

(defn foreign-adr-entities [next-tempid!]
  (mapcat
   (fn [{:keys [repo] :as src}]
     (keep (fn [f]
             (when-let [e (foreign-adr-entity f repo)]
               (assoc e :db/id (next-tempid!) :source/file (str f))))
           (foreign-adr-files src)))
   (foreign-adr-sources)))

;; ---------- business metrics (90-docs/business/*.edn。category D) ----------

(defn business-metrics-files []
  (let [dir (io/file root "90-docs" "business" "metrics")]
    (if (.exists dir)
      (->> (file-seq dir) (filter #(data-file? %)) (sort-by str))
      [])))

(defn business-metrics-entities [next-tempid!]
  (keep (fn [f]
          (when-let [e (single-map-entity f "biz-metrics")]
            (assoc e :db/id (next-tempid!) :source/file (str f))))
        (business-metrics-files)))

(defn canvas-ledger-file [] (io/file root "90-docs" "business" "canvas-ledger.edn"))

(defn canvas-ledger-entities [next-tempid!]
  (let [f (canvas-ledger-file)]
    (if (.exists f)
      (map (fn [e] (assoc e :db/id (next-tempid!) :source/file (str f)))
           (line-map-entities f))
      [])))

;; ---------- (retired) ADR amendment ledger ----------
;;
;; 90-docs/adr-ledger/adr-ledger.edn was an append-only :event/* stream that
;; amended already-accepted ADRs without rewriting their :adr/body
;; (ADR-2607173000 decision item 6 / ADR-2607181900). Owner decision
;; 2026-07-25 (ADR-2607257000) retired it: an ADR document now records only
;; its latest state and `git log` / `git blame` carry the history. The 76
;; existing events were folded into their base ADRs by
;; scripts/fold-adr-ledger.cljs, so no join is needed — query the ADR entity
;; directly:
;;   [:find ?title :where [?adr "adr/id" "2607173000"] [?adr "adr/title" ?title]]

;; ---------- orgs/kawasakijun/*.edn（個人ライフトラッキング。category H） ----------

(defn kawasakijun-files []
  (let [dir (io/file root "orgs" "kawasakijun")]
    (if (.exists dir)
      (->> (file-seq dir) (filter #(data-file? %)) (sort-by str))
      [])))

(defn kawasakijun-entities [next-tempid!]
  (keep (fn [f]
          (when-let [e (single-map-entity f "kj")]
            (assoc e :db/id (next-tempid!) :source/file (str f))))
        (kawasakijun-files)))

;; ---------- RAD identity journal ----------

(defn rad-dir [] (io/file root "orgs" "etzhayyim" "root" "80-data" "kotoba-rad"))

(defn rad-files []
  (let [dir (rad-dir)]
    (if (.exists dir)
      (->> (file-seq dir)
           (filter #(str/ends-with? (str %) ".identity.journal.edn"))
           (sort-by str))
      [])))

(defn replay-journal
  "[e a v tx op] タプル列を tx 順に replay し {e {a v ...}} に圧縮する
   （:add は上書き、:retract は attr 削除。RAD の属性は実データ上すべて
   単一値の概念 — :rad/type :rad/name :rad/did-web 等が tx を跨いで
   書き換わる形 — なので cardinality-one 前提の「最新値」replay で
   current state を再構成できる）。"
  [tuples]
  (reduce
   (fn [acc [e a v _tx op]]
     (case op
       :add (assoc-in acc [e a] v)
       :retract (update acc e dissoc a)
       acc))
   {}
   (sort-by #(nth % 3) tuples)))

(defn rad-entities [next-tempid!]
  (mapcat
   (fn [f]
     (let [compacted (replay-journal (slurp-edn-lines f))]
       (for [[cid attrs] compacted]
         (assoc attrs
                :db/id (next-tempid!)
                :rad/cid cid
                :source/file (str f)))))
   (rad-files)))

;; ---------- etzhayyim/root 80-data/* 兄弟ドメイン（category A） ----------

(defn etzhayyim-80-data [& parts]
  (apply io/file root "orgs" "etzhayyim" "root" "80-data" parts))

;; journal-tuple 形式（kotoba-rad と同じ [e a v tx op]、1行1フォーム）
(def journal-tuple-sources
  [["vitals" "journal.edn"]
   ["organism" "narration.journal.edn"]
   ["organism" "social.journal.edn"]
   ["organism" "trajectory.journal.edn"]
   ["organism" "pulse.journal.edn"]
   ["organism" "joucho.journal.edn"]
   ["manimani" "intake.journal.edn"]])

(defn journal-tuple-entities [next-tempid!]
  (mapcat
   (fn [[domain filename]]
     (let [f (etzhayyim-80-data domain filename)]
       (if (.exists f)
         (try
           (let [compacted (replay-journal (slurp-edn-lines f))]
             (for [[eid attrs] compacted]
               (assoc attrs
                      :db/id (next-tempid!)
                      :journal/entity-id (str eid)
                      :journal/domain domain
                      :source/file (str f))))
           (catch :default _ []))
         [])))
   journal-tuple-sources))

(defn vector-tuple-journal-entities
  "genome-datoms.kotoba.edn 向け: ファイル名は .datoms.kotoba.edn だが中身は
   RAD journal と同じ [e a v tx op] 5要素タプル。ただし1行1フォームでなく
   単一の妥当な edn vector として書かれている（slurp-edn で一括parseできる）。"
  [f]
  (try
    (let [content (slurp-edn f)]
      (when (vector? content) (replay-journal content)))
    (catch :default _ nil)))

;; genome-datoms.kotoba.edn: 同じ5要素タプルだが単一vectorとして書かれている
(defn genome-datoms-entities [next-tempid!]
  (let [f (etzhayyim-80-data "genome" "genome-datoms.kotoba.edn")]
    (if (.exists f)
      (for [[eid attrs] (or (vector-tuple-journal-entities f) {})]
        (assoc attrs
               :db/id (next-tempid!)
               :journal/entity-id (str eid)
               :journal/domain "genome"
               :source/file (str f)))
      [])))

;; [:db/add e a v] 4要素タプル形式（junkan-governance / hirameki-patents）
(def datoms-kotoba-sources
  [["junkan-governance" "governance-asymmetry.datoms.kotoba.edn" nil]
   ["junkan-governance" "findings-ledger.kotoba.edn" :tx/datoms]
   ["hirameki-patents" "hirameki-patents.datoms.kotoba.edn" nil]])

(defn datoms-kotoba-entities [next-tempid!]
  (mapcat
   (fn [[domain filename wrapper-key]]
     (let [f (etzhayyim-80-data domain filename)]
       (if (.exists f)
         (map (fn [e] (assoc e :db/id (next-tempid!) :journal/domain domain :source/file (str f)))
              (or (add-datoms-entities f wrapper-key :kotoba/entity-id) []))
         [])))
   datoms-kotoba-sources))

;; hirameki-patents corpus（1行1レコード、既に flat map）
(defn hirameki-corpus-entities [next-tempid!]
  (let [f (etzhayyim-80-data "hirameki-patents" "hirameki-patents.corpus.kotoba.edn")]
    (if (.exists f)
      (map (fn [e] (assoc e :db/id (next-tempid!) :source/file (str f)))
           (or (line-map-entities f) []))
      [])))

;; jinushi-land: 参照データセット（{:records [...]} 等、トランザクション性なし）
(defn jinushi-land-entities [next-tempid!]
  (let [dir (etzhayyim-80-data "jinushi-land")]
    (if (.exists dir)
      (mapcat
       (fn [f]
         (map (fn [e] (assoc e :db/id (next-tempid!)
                              :jinushi/source (.getName f)
                              :source/file (str f)))
              (or (seed-vector-entities-any f [:records :buildings :units :parcels]) [])))
       (->> (file-seq dir) (filter #(str/ends-with? (str %) ".kotoba.edn")) (sort-by str)))
      [])))

;; ---------- actor procedure registries（category E） ----------

(def procedure-registry-sources
  [["tate" (io/file root "orgs" "etzhayyim" "root" "20-actors" "tate" "data" "procedure-registry.edn")]
   ["saisei" (io/file root "orgs" "etzhayyim" "root" "20-actors" "saisei" "data" "procedure-registry.edn")]])

(defn procedure-registry-entities [next-tempid!]
  (mapcat
   (fn [[actor f]]
     (if (.exists f)
       (map (fn [e] (assoc e :db/id (next-tempid!) :registry/actor actor :source/file (str f)))
            (or (vector-of-maps-entities f) []))
       []))
   procedure-registry-sources))

;; ---------- 大型統合 kotoba データセット（category F。67MB の public-rir は
;; 実用上のメモリ/パース時間の懸念から意図的に対象外。必要なら別途 opt-in で。） ----------

(defn kanjo-kabuto-unspsc-cleanroom-entities [next-tempid!]
  (let [kanjo (io/file root "orgs" "etzhayyim" "root" "20-actors" "kanjo" "data" "facts.merged.kotoba.edn")
        kabuto (io/file root "orgs" "etzhayyim" "root" "20-actors" "kabuto" "data" "companies.merged.kotoba.edn")
        unspsc (io/file root "orgs" "com-junkawasaki" "kototama" "clj" "resources" "unspsc-taxonomy.edn")
        cleanroom (io/file root "orgs" "etzhayyim" "root" "00-contracts" "schemas" "cleanroom-actors-seed.kotoba.edn")]
    (concat
     (when (.exists kanjo)
       (map (fn [e] (assoc e :db/id (next-tempid!) :source/file (str kanjo)))
            (or (vector-of-maps-entities kanjo) [])))
     (when (.exists kabuto)
       (map (fn [e] (assoc e :db/id (next-tempid!) :source/file (str kabuto)))
            (or (vector-of-maps-entities kabuto) [])))
     (when (.exists unspsc)
       (map (fn [e] (assoc e :db/id (next-tempid!) :source/file (str unspsc)))
            (or (dict-of-entities unspsc :unspsc/code) [])))
     (when (.exists cleanroom)
       (map (fn [e] (assoc e :db/id (next-tempid!) :source/file (str cleanroom)))
            (or (seed-vector-entities cleanroom :seed) []))))))

;; ---------- gftdcojp _working/ 運用ledger（category G。実データ・機微内容含む。
;; ロード方針はオーナー確認済み — 2026-07-12） ----------

(defn working-doc-files []
  (let [dir (io/file root "orgs" "gftdcojp" "ai-gftd-apps-gftdcojp" "_working")]
    (if (.exists dir)
      (->> (file-seq dir) (filter #(str/ends-with? (str %) ".md.edn")) (sort-by str))
      [])))

(defn working-doc-entity
  "{:frontmatter {...} :body \"...\"} または {:body \"...\"} のみ（frontmatter 無し）
   のどちらもある（ADR と同型のばらつき）。:working/* 名前空間にする。"
  [f]
  (try
    (let [content (slurp-edn f)]
      (when (map? content)
        (let [fm (:frontmatter content)
              base (dissoc content :frontmatter)
              entries (concat (when (map? fm) (seq fm)) (seq base))]
          (when (seq entries)
            (into {:db/id -1}
                  (map (fn [[k v]] [(if (and (keyword? k) (namespace k)) k (keyword "working" (name k))) v]))
                  entries)))))
    (catch :default _ nil)))

(defn working-doc-entities [next-tempid!]
  (keep (fn [f]
          (when-let [e (working-doc-entity f)]
            (assoc e :db/id (next-tempid!) :source/file (str f))))
        (working-doc-files)))

;; ---------- 選択的ナラティブ/コンテンツデータ（category I。構造的に query
;; 価値のあるものだけ選んで対象にする — 未構造の物語散文（origins.edn 等）や
;; 線形のシーン列（shotlist）は Datalog より全文検索/線形再生の方が向くため
;; 対象外にする） ----------

(defn spirit-in-physics-entities [next-tempid!]
  (let [base (io/file root "orgs" "com-junkawasaki" "org-spirit-in-physics-comics")
        chars-f (io/file base "resources" "characters.edn")
        manga-f (io/file base "manga.edn")]
    (concat
     (when (.exists chars-f)
       (map (fn [e] (assoc e :db/id (next-tempid!) :source/file (str chars-f)))
            (or (seed-vector-entities chars-f :gh/characters) [])))
     (when (.exists manga-f)
       (when-let [e (single-map-entity manga-f "manga")]
         [(assoc e :db/id (next-tempid!) :source/file (str manga-f))])))))

(defn ghosthacker-manga-log-entities [next-tempid!]
  (let [f (io/file root "orgs" "gftdcojp" "app-aozora" "60-apps" "appview" "cljs" "public" "kotoba"
                   "ghosthacker-manga-log.edn")]
    (if (.exists f)
      (map (fn [e] (assoc e :db/id (next-tempid!) :source/file (str f)))
           (or (add-datoms-entities f :tx/datoms :ghosthacker/entity-id) []))
      [])))

;; ---------- cross-repo 企業データ（category J。:company/lei が結合キー） ----------
;; 2026-07-25 追加。以下の 3 corpus は同じ `:company/lei` を持ちながら別々の
;; ファイル・別々のリポジトリに座っており、この統合面にもどこにも載っていなかった
;; （実測: この 3 者を突合すると 69 社が :company/lei で結合可能なのに、結合する
;; ローダが存在しなかった）:
;;
;;   - orgs/gftdcojp/cloud-murakumo-market-intel/data/company-facts.edn
;;     SEC EDGAR 由来の財務ファンダメンタルズ 5,200 社（うち :company/lei 保有
;;     2,640 社）。市場サイジング用の別ストア（market-intel.store の
;;     langchain.db / DataScript バックエンド）でだけ query 可能だった。
;;   - orgs/cloud-itonami/cloud-itonami-lei-<lei>/blueprint.edn
;;     法人実体 155 社（legal-name / jurisdiction / website / ticker）。
;;   - 同 80-data/public/tos.journal.edn
;;     各社の ToS アーカイブ（[e a v tx op] journal、1 ファイル 1 entity）。
;;
;; 同一面に載せることで「財務 × 法人実体 × ToS」を 1 クエリで結合できる。
;; `:company/*` の属性名は 3 者で共有されるので、出自の区別は `:source/dataset`
;; で行う（属性名を書き換えて出自を埋め込むと結合キーが壊れるため、そうしない）。

(defn west-project-path
  "west.yml が宣言する project の `path:`（例 \"orgs/network-awai/foo\"）。

   org をパスに焼き込まないためにこれを通す。この関数が無かった間、
   market-intel は `orgs/gftdcojp/...` を直接指しており、west が repo を
   `orgs/network-awai/...` へ移した時点で存在しないディレクトリを見に行き、
   **黙って 0 entity を返していた**。結果 CLAUDE.md が主力機能として挙げる
   「財務 × 法人実体 × ToS を 1 クエリ」が `[]` を返し続けた（実測 2026-07-30:
   `company/revenue-usd` は 0 entity、dataset 一覧に market-intel が不在）。
   west が正である以上、パスは west から引く。"
  [project-name]
  (let [f (io/file root "manifest" "west.yml")]
    (when (.exists f)
      (loop [lines (str/split-lines (slurp f)) cur nil]
        (when-let [l (first lines)]
          (let [t (str/trim l)]
            (cond
              (str/starts-with? t "- name:")
              (recur (rest lines) (str/trim (subs t 7)))

              (and (= cur project-name) (str/starts-with? t "path:"))
              (str/trim (subs t 5))

              :else (recur (rest lines) cur))))))))

(defn market-intel-company-facts-file
  "SEC EDGAR 財務ファンダメンタルズの実ファイル。west が宣言する path 配下を見る。"
  []
  (when-let [p (west-project-path "cloud-murakumo-market-intel")]
    (apply io/file root (concat (str/split p #"/") ["data" "company-facts.edn"]))))

(defn company-facts-entities [next-tempid!]
  (let [f (market-intel-company-facts-file)]
    (cond
      (nil? f)
      (do (js/console.error
           (str "edn-query: WARNING market-intel: west.yml に "
                "cloud-murakumo-market-intel の path が無い — 財務データは "
                "load されない（:company/revenue-usd は 0 件になる）"))
          [])

      (not (.exists f))
      ;; 黙って [] を返すのがこの面を壊した経路そのもの。存在しないなら言う。
      (do (js/console.error
           (str "edn-query: WARNING market-intel: " f
                " が無い — 財務データは load されない。west update が未実行か、"
                "repo が移動している"))
          [])

      :else
      (for [e (or (vector-of-maps-entities f) [])]
        (assoc e
               :db/id (next-tempid!)
               :source/dataset "market-intel"
               :source/file (str f))))))

(defn lei-repo-dirs
  "orgs/cloud-itonami/cloud-itonami-lei-<lei> ディレクトリの一覧。親を
   `.listFiles` で 1 段だけ見る（cloud-itonami 配下は 1,200+ repo あるので
   file-seq で全走査しない）。"
  []
  (let [parent (io/file root "orgs" "cloud-itonami")]
    (if (.exists parent)
      (->> (seq (.listFiles parent))
           (filter #(str/includes? (str %) "cloud-itonami-lei-"))
           (filter #(.isDirectory %))
           (sort-by str))
      [])))

(defn lei-from-dir
  "ディレクトリ名 `cloud-itonami-lei-<lei>` から LEI を復元する。ディレクトリ名は
   小文字、GLEIF/blueprint の `:company/lei` は大文字なので大文字化して揃える
   （揃えないと同じ企業が別キーになり結合できない）。"
  [d]
  (let [n (last (str/split (str d) #"/"))]
    (when-let [m (second (re-matches #"cloud-itonami-lei-(.+)" n))]
      (str/upper-case m))))

(defn warn-skipped!
  "存在するのに 0 entity しか生まなかったソースを stderr に報告する。
   このローダ群は shape 不一致を nil で握り潰す設計（1 ファイルの破損で面
   全体が落ちないため）だが、握り潰したまま黙っていると count が「全部載って
   いる」ように読めてしまう。落としたものは必ず言う。

   **警告は `js/console.error` で書く。`(binding [*out* *err*] (println …))`
   を使わないこと** —— nbb はこの binding を尊重せず、**stdout に出る**（実測
   2026-08-13: `nbb -e '(binding [*out* *err*] (println \"x\"))' 2>/dev/null` が
   x を表示する）。JVM Clojure の正しいイディオムがこのランタイムでは何もしない
   ので、意図は正しいまま出力先だけが間違う。ここでそれをやると `q` / `q*` /
   `count` の**機械可読な stdout に日本語の警告文が混ざり**、消費側の
   `edn/read-string` が `Invalid symbol: edn-query:.` で落ちる。実際に
   verify-tsukuru-factory-plane がこれで落ち、それは正しい挙動だった —— 旧版は
   同じ状況で nil を返して 3 検査すべてを skip し PASS していた。"
  [label dirs]
  (when (seq dirs)
    (js/console.error
     (str "edn-query: WARNING " label ": " (count dirs)
          " source(s) existed but yielded no entity — NOT queryable: "
          (str/join ", " (take 5 dirs))
          (when (> (count dirs) 5) (str " ... +" (- (count dirs) 5) " more"))))))

(defn- normalize-name
  "Normalize a company/applicant name for fuzzy string join: uppercase,
  strip punctuation, collapse whitespace. Lets 'Broad Institute Inc'
  join with 'Broad Institute, Inc.' on the unified query plane."
  [s]
  (when (and s (string? s) (not (str/blank? s)))
    (-> s str/upper-case
        (str/replace #"[,.\"'`(){}\[\];]" "")
        (str/replace #"\s+" " ")
        str/trim)))

(defn lei-blueprint-entities [next-tempid!]
  (let [skipped (atom [])
        out (doall
             (keep (fn [d]
                     (let [f (io/file d "blueprint.edn")]
                       (when (.exists f)
                         (if-let [e (single-map-entity f "company")]
                           (assoc e
                                  :db/id (next-tempid!)
                                  ;; blueprint は通常自分で :company/lei を持つ。
                                  ;; 持たない場合だけディレクトリ名から補う
                                  ;; （捏造ではなく、その repo の識別子そのもの）。
                                  :company/lei (or (:company/lei e) (lei-from-dir d))
                                  :company/legal-name-norm (normalize-name (:company/legal-name e))
                                  :source/dataset "cloud-itonami-lei"
                                  :source/file (str f))
                           (do (swap! skipped conj (lei-from-dir d)) nil)))))
                   (lei-repo-dirs)))]
    (warn-skipped! "cloud-itonami-lei blueprint.edn" @skipped)
    out))

(defn flat-attrlist-journal-entities
  "tos.journal.edn の **第2の形**を読む。実測 2026-07-25、155 件中 9 件がこちら:

     [[\"berkshire-hathaway-legal-1\"
       :tos/full-text \"...\" :tos/source-url \"...\" ... :tx 1 :op :add]]

   1 datom = 1 タプル（`[e a v tx op]`、`replay-journal` が読む形）ではなく、
   **1 entity = 1 タプル**で attr/value が平坦に並び、末尾に `:tx` / `:op` が
   付く。壊れているのではなく別のシリアライズ規約なので、捨てずに読む。

   末尾の tx/op には**さらに 2 通りの書き方**がある（実測、両方とも実在する）:

     keyed      [... :tos/doc-type :legal-disclaimer :tx 1 :op :add]   (1 件)
     positional [... :tos/doc-type :terms-of-service 1 :add]           (6 件)

   `{e {a v ...}}` を返す（`replay-journal` と同じ戻り値の形）。tx/op はメタなので
   entity 属性には含めない。`op` が `:add` 以外なら nil を返す（retract 相当を
   この形で書いた例は実データに無いため、黙って add 扱いにしない）。"
  [f]
  (try
    (let [content (slurp-edn f)]
      (when (and (vector? content) (= 1 (count content)) (vector? (first content)))
        (let [t (vec (first content))
              n (count t)
              [body op]
              (cond
                ;; keyed: 末尾 4 要素が :tx N :op OP
                (and (>= n 5) (= :op (nth t (- n 2))))
                [(subvec t 1 (- n 4)) (nth t (dec n))]
                ;; positional: 末尾 2 要素が N OP
                (and (>= n 3) (keyword? (nth t (dec n))) (number? (nth t (- n 2))))
                [(subvec t 1 (- n 2)) (nth t (dec n))]
                :else [nil nil])]
          (when (and body (= :add op) (even? (count body)) (seq body))
            {(first t) (into {} (map vec) (partition 2 body))}))))
    (catch :default _ nil)))

(defn lei-tos-entities [next-tempid!]
  (let [skipped (atom [])
        out (doall
             (mapcat
              (fn [d]
                (let [f (io/file d "80-data" "public" "tos.journal.edn")]
                  (if (.exists f)
                    ;; 2 つのシリアライズ規約が混在している。datom-tuple 形を
                    ;; 先に試し、0 件なら flat-attrlist 形として読み直す。
                    (let [ents (or (not-empty (or (vector-tuple-journal-entities f) {}))
                                   (flat-attrlist-journal-entities f)
                                   {})]
                      ;; 実測 2026-07-25 の内訳（当初「9 件すべて破損」と誤診し、
                      ;; 個別に検証して訂正した）: 146 件が datom-tuple 形、
                      ;; 8 件が flat-attrlist 形（壊れていない、別規約）、
                      ;; 1 件（Berkshire Hathaway）だけが本物の破損で、本文中の
                      ;; `provided "as is" without` の引用符が未エスケープのまま
                      ;; 書かれ文字列が途中終端していた。記録されていた
                      ;; :tos/sha256 と復元テキストのハッシュが一致したことで
                      ;; 復元の正しさを証明した上で source を修復済み。
                      ;; 以後ここで skip が出たら「また別の規約か本物の破損」なので
                      ;; 黙って落とさず報告する。
                      (when (empty? ents) (swap! skipped conj (lei-from-dir d)))
                      ;; journal の entity-id は "wabtec-tos-1" のような repo
                      ;; ローカル名であって LEI ではない。ディレクトリ由来の LEI を
                      ;; 足して初めて blueprint / market-intel と結合できる。
                      (for [[eid attrs] ents]
                        (assoc attrs
                               :db/id (next-tempid!)
                               :tos/entity-id (str eid)
                               :company/lei (lei-from-dir d)
                               :source/dataset "cloud-itonami-lei-tos"
                               :source/file (str f))))
                    [])))
              (lei-repo-dirs)))]
    (warn-skipped! "cloud-itonami-lei tos.journal.edn" @skipped)
    out))

(defn lei-facts-entities
  "cloud-itonami-lei-<LEI>/facts.edn を読む。`scripts/lei-verify-facts.cljs` が
   書く tx-data（entity map のベクタ）で、GLEIF の登記事実・ISIN・親子関係・
   reporting exception が 1 entity ずつ、それぞれ :source/url と
   :source/retrieved-at つきで入っている。

   **この loader が無かった間、その事実は面から引けなかった。** facts.edn の
   ヘッダは自分でそう申告している（「NOT on the shared query plane yet.
   manifest/edn-query.cljs has loaders for blueprint.edn and tos.journal.edn
   and none for this file」）。2026-08-25 に 183/185 repo が facts.edn を持った
   ので、載せる価値がその申告を上回った。

   :fact/id は **repo ローカル**で、`gleif-lei-record` は全 repo で同じ文字列。
   LEI を足して初めて blueprint / tos / market-intel / property と結合できる
   （tos loader が :tos/entity-id でやっているのと同じ理由）。repo を跨いで
   1 件を名指しできるように :fact/qualified-id も足す。"
  [next-tempid!]
  (let [skipped (atom [])
        out (doall
             (mapcat
              (fn [d]
                (let [f (io/file d "facts.edn")]
                  (if (.exists f)
                    (let [ents (vector-of-maps-entities f)
                          lei (lei-from-dir d)]
                      ;; 読めたのに 0 件なら、別の規約か本物の破損。黙って落とさず報告する
                      ;; （ファイルが無いのは skip ではない —— まだ生成していないだけ）。
                      (when (empty? ents) (swap! skipped conj lei))
                      (for [e ents]
                        (assoc e
                               :db/id (next-tempid!)
                               :company/lei (or (:company/lei e) lei)
                               :fact/qualified-id (str lei "/" (:fact/id e))
                               :source/dataset (or (:source/dataset e) "cloud-itonami-lei-facts")
                               :source/file (str f))))
                    [])))
              (lei-repo-dirs)))]
    (warn-skipped! "cloud-itonami-lei facts.edn" @skipped)
    out))

;; ---------- GLEIF LEI universe + 不動産 ownership（category J、ADR-2608012000） ----------
;;
;; kotoba-lang/property の `data/*.datoms.edn`（committed projection）を読む。
;; corpus 本体（GLEIF Golden Copy 全 3.4M 社、~1.2 GB）は **この面には載らない**。
;; 載せない理由と、それが何を犠牲にするかを明示しておく（CLAUDE.md の
;; 「分けるときは何が join できなくなるかを名指しで書く」）:
;;
;;   実測: この DataScript ローダは 20 万 entity で 85 秒 / 614 MB、60 万 entity で
;;   290 秒 / 1.2 GB。3.4M 社は約 30 分 / 6 GB 常駐になり、CLI の 1 query ごとに
;;   払える値段ではない（この面の既存 116k entity 全体で 46 秒）。
;;
;;   **犠牲**: projection に入っていない LEI は Datalog で join できない。
;;   market-intel の財務・cloud-itonami-lei の ToS・property の ownership と
;;   突合したい会社は、先に projection に含める必要がある
;;   （`property/scripts/project_gleif_corpus.cljs --lei-file` / `--jurisdiction`）。
;;   corpus 全体に対する集計は join なしの streaming scan
;;   （`property/scripts/query_gleif_corpus.cljs`）でのみ答えられる。
;;   join 面を広げる正しい操作は「projection を広げる」であって
;;   「別のストアに分ける」ではない。

(defn corpus-line-entities
  "manifest 行付き edn-lines corpus を読む。1 行目が `{:corpus/manifest true ...}`
   ならその `:source/*` を各レコードへ配る（provenance を 1 ファイル 1 回だけ
   書く形式。全レコードに複製すると corpus が数百 MB 太る）。

   **manifest 行そのものも 1 entity として載せる。** 実測 2026-08-19、
   `gbizinfo-zenken-government-summary` に `:projection/folded-rows`（2,358 件の
   集計が 125,144 行の fold であること）を足したのに、面から引けなかった ——
   1 行目は provenance の供給元として消費され、entity にならなかったため。
   **分母が artifact にしか無く面から引けないなら、面の上では分母が無いのと同じ**で、
   projection/queried・matched-rows・ambiguous-count・folded-rows が全部そうなっていた。

   ⚠ **したがって `[?e \"source/dataset\" X]` の件数はレコード数ではない。**
   manifest 行の分だけ多い。レコードだけ数えるなら
   `(not [?e \"corpus/manifest\" true])` を足す。この形は以前から
   gbizinfo（section ごとに manifest 5 本のうち 4 本が載っていた）で成立していた ——
   今は全 dataset で一貫している。"
  [f next-tempid!]
  (try
    (let [lines (slurp-edn-lines f)
          head (first lines)
          manifest (when (and (map? head)
                              (or (:corpus/manifest head) (:corpus/projection head)))
                     head)
          provenance (into {} (filter (fn [[k _]] (= "source" (namespace k)))) manifest)
          records (if manifest (rest lines) lines)]
      (concat
       (when manifest [(merge manifest {:db/id (next-tempid!) :source/file (str f)})])
       (for [r records :when (map? r)]
         (merge provenance r {:db/id (next-tempid!) :source/file (str f)}))))
    (catch :default _ nil)))

(defn property-data-files
  "kotoba-lang/property の committed projection 群。west が宣言する path 配下を
   見る（org をパスに焼かない — market-intel が repo 移動で黙って 0 件になった
   のと同じ轍を踏まないため）。"
  [glob-prefix]
  (when-let [p (west-project-path "property")]
    (let [dir (apply io/file root (concat (str/split p #"/") ["data"]))]
      (when (.exists dir)
        (->> (seq (.listFiles dir))
             (filter #(let [n (last (str/split (str %) #"/"))]
                        (and (str/starts-with? n glob-prefix)
                             (str/ends-with? n ".datoms.edn"))))
             (sort-by str))))))

;; ---------- GLEIF の tier（ADR-2608071000） ----------
;;
;; **projection のサイズは、誰が撃つどのクエリにも一律にかかる税**である。
;; この面は毎回まるごと DataScript に load されるので、大きい tier を既定に
;; すると「LEI を 1 件も見ないクエリ」まで同じ代金を払う。
;;
;; 実測（M4 mac mini、2026-08-07）:
;;   200,000 entity → 3.88M datom / 2.4 GB / 27 秒
;;   500,000 entity → 9.77M datom / 3.6 GB / 72 秒
;; 1,000,000 entity → 19.66M datom / 5.6 GB / 156 秒
;; ≒ 1M あたり 5.6 GB・156 秒。node の既定 old-space は 4.2 GB なので、
;; 既定のままなら 70 万 entity 付近で落ちる。
;;
;; したがって tier で分ける（既定 = joined のみ）:
;;   joined     plane が既に参照している LEI とそれに触れる edge（約 42k）
;;   closure    joined から所有 edge を辿って到達できる全社（約 276k）
;;   universe   3,396,479 社。**約 22 GB。CLI の 1 クエリでは載らない**ので
;;              git にも置かない（node の ~/.cache/gleif にある）
;;
;; **これは「別ストアに分ける」ではない。** 同じ 1 つの面に、同じ属性で、
;; 追加ロードするだけなので join 到達性は失われない —— 失うのは「黙って
;; 全部載っている」という思い込みだけで、そのために count が tier を表示する。
(def gleif-default-tiers #{"joined"})

(defn gleif-tiers
  "load する tier。`--tier closure` / `--tier closure,universe`、または
   `EDN_QUERY_GLEIF_TIERS=closure`。joined は常に入る（面の既定の姿）。"
  []
  (let [argv (vec (js->clj (or (.-argv js/process) #js [])))
        flag (second (drop-while #(not= "--tier" %) argv))
        raw (or flag (.. js/process -env -EDN_QUERY_GLEIF_TIERS))]
    (into gleif-default-tiers
          (remove str/blank?)
          (map str/trim (str/split (or raw "") #",")))))

(defn- file-tier
  "`gleif-lei-closure-2.datoms.edn` → \"closure\"。prefix の直後から次の `-` か
   `.` まで。shard 番号は tier ではない。"
  [f prefix]
  (let [n (last (str/split (str f) #"/"))]
    (first (str/split (subs n (count prefix)) #"[-.]"))))

(defn gleif-projection-files
  "GLEIF projection の正本は **`com-junkawasaki/org-gleif-projections` だけ**
   （murakumo fleet ノードの gleif-cell が毎日ここへ push する）。

   `kotoba-lang/property` の data/ を**併読しない**のは、同じ LEI が二重に
   entity 化されるため —— 両方読んだ実測で `company` が 18,930 でなく 37,860 に
   なり、しかも数字としては正しく見える。property 側の gleif-* は本 ADR で
   削除した（property が持ち続けるのは collector/projector とその test、および
   別 dataset の property-ownership）。"
  [prefix]
  (->> ["org-gleif-projections"]
       (keep west-project-path)
       (mapcat (fn [p]
                 (let [dir (apply io/file root (concat (str/split p #"/") ["data"]))]
                   (when (.exists dir)
                     (->> (seq (.listFiles dir))
                          (filter #(let [n (last (str/split (str %) #"/"))]
                                     (and (str/starts-with? n prefix)
                                          (str/ends-with? n ".datoms.edn")))))))))
       (filter #(contains? (gleif-tiers) (file-tier % prefix)))
       (sort-by str)))

(defn gleif-lei-entities
  "GLEIF LEI projection（`data/gleif-lei-<tier>*.datoms.edn`）。複数ファイルを
   読むので、jurisdiction 別 projection を足すのは data/ にファイルを 1 つ置く
   だけで済む。"
  [next-tempid!]
  (let [files (gleif-projection-files "gleif-lei-")]
    (if (empty? files)
      (do (js/console.error
           (str "edn-query: WARNING gleif-lei: kotoba-lang/property の "
                "data/gleif-lei-*.datoms.edn が無い — LEI universe は load されない"
                "（west update 未実行か、projection 未生成）"))
          [])
      (mapcat (fn [f] (or (corpus-line-entities f next-tempid!) [])) files))))

(defn gleif-relationship-entities
  "GLEIF Level 2 corporate relationship projection
   （`data/gleif-relationship-*.datoms.edn`、ADR-2608031900）。

   `gleif-lei-*` と別 prefix・別 dataset にしてあるのは、edge を join 対象に
   しない query が 23,530 本の edge を load しなくて済むようにするため。
   `:corporate-relation/child-lei` / `-parent-lei` はどちらも `:company/lei` と
   join できるので、「この会社の最終親会社は誰か」「この会社は何を保有して
   いるか」を財務・ToS・不動産と同じ 1 クエリで聞ける。

   `:corporate-relation/validation` は GLEIF 自身の証拠階層。
   `ENTITY_SUPPLIED_ONLY` は「その会社が自己申告し、誰も裏を取っていない」
   （20260803 publish で 483,263 本中 139,111 本）。**自己申告の edge を検証済み
   として提示しない** — legalsupport の `:legal/verification` 降格と同じ規律。"
  [next-tempid!]
  (let [files (gleif-projection-files "gleif-relationship-")]
    (if (empty? files)
      (do (js/console.error
           (str "edn-query: WARNING gleif-relationship: kotoba-lang/property の "
                "data/gleif-relationship-*.datoms.edn が無い — 資本関係は load "
                "されない（west update 未実行か、projection 未生成）"))
          [])
      (mapcat (fn [f] (or (corpus-line-entities f next-tempid!) [])) files))))

(def houjin-bangou-default-tiers
  ;; `closures`（登記記録の閉鎖）を既定に入れる: **面の番号集合に絞った 52 件**しか
  ;; 無く、答える問いは「この会社はまだ生きているか」という、ほぼ全ての引き手が
  ;; 最初に要るもの。tier の仕組みは大きい tier を既定から外すためにあるので、
  ;; 小さいものを外す理由は無い。
  ;;
  ;; ⚠ closures projection は**番号集合で絞った結果**であって全件ではない
  ;; （全件は 773,796 件 = 登記の 13%）。絞りを外して commit されたらこの既定は
  ;; 高くつくので、loader が record-count を見て名指しで警告する。
  #{"joined" "closures"})

(defn houjin-bangou-tiers
  "load する tier。`--hb-tier government`、または
   `EDN_QUERY_HOUJIN_BANGOU_TIERS=public-bodies`。joined は常に入る。

   GLEIF の `--tier` と別のフラグにしてあるのは、片方の tier を広げたつもりが
   もう片方も広がって heap を割る、という混線を避けるため。"
  []
  (let [argv (vec (js->clj (or (.-argv js/process) #js [])))
        flag (second (drop-while #(not= "--hb-tier" %) argv))
        raw (or flag (.. js/process -env -EDN_QUERY_HOUJIN_BANGOU_TIERS))]
    (into houjin-bangou-default-tiers
          (remove str/blank?)
          (map str/trim (str/split (or raw "") #",")))))

(defn- nta-projection-files
  "`jp-go-nta-houjin-bangou` の data/ から、prefix と tier で選んだ projection。

   1 repo に 2 dataset（法人番号と invoice registry）が入っているのは、法人の
   invoice 登録番号が `T` + 法人番号 で、**同じ entity の属性だから**である。
   population が別なら repo も別にする。"
  [prefix requested]
  (->> ["jp-go-nta-houjin-bangou"]
       (keep west-project-path)
       (mapcat (fn [p]
                 (let [dir (apply io/file root (concat (str/split p #"/") ["data"]))]
                   (when (.exists dir)
                     (->> (seq (.listFiles dir))
                          (filter #(let [n (last (str/split (str %) #"/"))]
                                     (and (str/starts-with? n prefix)
                                          (str/ends-with? n ".datoms.edn")))))))))
       (sort-by str)
       ((fn [all]
          (let [available (into #{} (map #(file-tier % prefix)) all)]
            ;; 要求された tier に 1 つもファイルが無いなら、それは「その tier が
            ;; 空」ではなく「その名前の tier が無い」。黙って 0 件を返すと、flag が
            ;; 効いたのか効かなかったのかを出力から区別できない。
            ;;
            ;; ⚠ **既定 tier については言わない。** 2 つの dataset（法人番号と
            ;; invoice）が同じ tier 集合を共有しているので、片方にだけ在る tier を
            ;; 既定に足すと、もう片方が毎回「その tier が無い」と言い続ける ——
            ;; 実測 2026-08-19、`closures` を既定に入れた直後に invoice 側が鳴いた。
            ;; **毎回出る警告は、警告を読まない習慣を作る。**
            (doseq [t (sort (remove #(or (contains? available %)
                                         (contains? houjin-bangou-default-tiers %))
                                    requested))]
              (js/console.error
               (str "edn-query: WARNING " prefix ": tier " (pr-str t)
                    " に該当する data/" prefix t "*.datoms.edn が無い（在るのは "
                    (str/join ", " (sort available)) "）")))
            (filter #(contains? requested (file-tier % prefix)) all))))))

(defn houjin-bangou-entities
  "国税庁 法人番号 projection（`jp-go-nta-houjin-bangou` の
   `data/houjin-bangou-<tier>*.datoms.edn`、ADR-2608181000）。

   corpus は毎月の 全件データ 5,816,535 件で、**git にも、この面にも載らない**
   （ノードの `~/.cache/houjin-bangou`）。載るのは tier を切った projection:

     joined      面が既に参照している法人（GLEIF の JP registration-no と
                 business 文書からの名前解決の結果）
     government  法人種別 101/201 = 国の機関 + 地方公共団体（登記が生きているもの）

   `:company/registration-no` に法人番号と同じ値を入れてあるので、GLEIF の
   JP レコード（`Entity.RegistrationAuthority.RegistrationAuthorityEntityID`）と
   **翻訳層なしで join できる**。`:company/houjin-bangou` は衝突を避けたい
   query 用の曖昧さのない別名。

   **projection に無い法人は Datalog で join できない。** corpus に対して
   答えられるのは join を伴わない集計だけで、それは
   `property/scripts/query_houjin_bangou_corpus.cljs` の仕事。

   ⚠ **tier 名にハイフンを使わない。** `file-tier` は prefix の直後から次の
   `-` か `.` までを tier と読む（`gleif-lei-closure-2` の `-2` は shard 番号で
   tier ではない）ので、`houjin-bangou-public-bodies.datoms.edn` は tier
   `\"public\"` として読まれ、`--hb-tier public-bodies` は**受理された上で 0 件を
   load した**（実測 2026-08-18）。"
  [next-tempid!]
  (let [files (nta-projection-files "houjin-bangou-" (houjin-bangou-tiers))]
    ;; 既定 tier に大きい projection が入ると、**entity を 1 件聞くだけの query も**
    ;; それを毎回 load する。黙って遅くならないよう名指しで言う。
    (doseq [f files
            :let [head (first (or (try (slurp-edn-lines f) (catch :default _ nil)) []))]
            :when (and (map? head)
                       (number? (:corpus/record-count head))
                       (> (:corpus/record-count head) 50000))]
      (js/console.error
       (str "edn-query: WARNING houjin-bangou: " (.getName f) " は "
            (:corpus/record-count head) " 件 —— 既定 tier に置くには大きい"
            "（number-set で絞られていない可能性）")))
    (if (empty? files)
      (do (js/console.error
           (str "edn-query: WARNING houjin-bangou: com-junkawasaki/jp-go-nta-houjin-bangou の "
                "data/houjin-bangou-*.datoms.edn が無い — 日本の法人は load されない"
                "（west update 未実行か、projection 未生成）"))
          [])
      (mapcat (fn [f] (or (corpus-line-entities f next-tempid!) [])) files))))

(defn invoice-entities
  "国税庁 適格請求書発行事業者 projection（同じ repo の
   `data/invoice-<tier>*.datoms.edn`、ADR-2608181000）。

   法人番号 が「この法人は在るか」を答えるのに対し、こちらは
   **「この取引先は適格請求書を出せるか、いつから、今も有効か」**を答える。
   `:invoice/active?` は *取消でない* かつ *失効でない* の連言（authority は
   2 つの列で持つ）。

   **個人事業主の登録は projection に入らない。** 登録番号は法人番号ではないので
   この面の何とも join できず、しかも authority の照会サイトを通せば自然人に
   解決する識別子である。ノードの corpus には在るので lookup はできる
   （実測 2026-08-18: 5,069,446 件のうち 2,491,986 件が個人）。"
  [next-tempid!]
  (let [files (nta-projection-files "invoice-" (houjin-bangou-tiers))]
    (if (empty? files)
      (do (js/console.error
           (str "edn-query: WARNING invoice: com-junkawasaki/jp-go-nta-houjin-bangou の "
                "data/invoice-*.datoms.edn が無い — 適格請求書発行事業者は load されない"))
          [])
      (mapcat (fn [f] (or (corpus-line-entities f next-tempid!) [])) files))))

(defn jgrants-entities
  "jGrants（デジタル庁）の補助金**公募**カタログ
   （`jp-go-digital-jgrants` の `data/jgrants-catalog.datoms.edn`、ADR-2608181000）。

   **これは「どんな補助金が在るか」であって「誰が受けたか」ではない。**
   `:company/houjin-bangou` も `:company/lei` も 1 件も無いので、
   法人側と join できない —— 隣に載っているだけで join できると読まれないよう、
   ここにも書いておく。交付実績は各省庁の採択者一覧にあり、未取得。

   カバレッジは keyword 和集合（API に列挙モードが無い）。使った keyword は
   manifest 行の `:corpus/keywords` に入っている。"
  [next-tempid!]
  (let [files (->> ["jp-go-digital-jgrants"]
                   (keep west-project-path)
                   (mapcat (fn [p]
                             (let [dir (apply io/file root (concat (str/split p #"/") ["data"]))]
                               (when (.exists dir)
                                 (->> (seq (.listFiles dir))
                                      (filter #(str/ends-with? (str %) ".datoms.edn"))))))))]
    (if (empty? files)
      (do (js/console.error
           (str "edn-query: WARNING jgrants: com-junkawasaki/jp-go-digital-jgrants の "
                "data/*.datoms.edn が無い — 補助金の公募は load されない"))
          [])
      (mapcat (fn [f] (or (corpus-line-entities f next-tempid!) [])) files))))

(defn gbizinfo-entities
  "gBizINFO（経済産業省）の法人活動情報
   （`jp-go-gbiz-info` の `data/*.datoms.edn`、ADR-2608181000）。

   法人番号 registry が「この法人は在るか」、invoice registry が「適格請求書を
   出せるか」を答えるのに対し、こちらは**国がこの法人に何を渡し、何を買い、何を
   記録したか** —— `:grant/kind :subsidy` / `:procurement` と、
   `:company/fiscal-year-end-month`（決算期）・`:company/net-sales-yen`。

   全部 `:company/houjin-bangou` を持つので、この面の他の全部と join できる。
   jGrants（公募）とは別物である: あちらは「どんな補助金が在るか」、
   こちらは「誰が受けたか」。

   **2026-08-19: 全件経路が入った。** `data/gbizinfo-zenken-*.datoms.edn` は
   データダウンロード（5 ファイル・1,033,446 行）を面の法人番号 9,142 件で
   絞ったもので、`gbizinfo-joined.datoms.edn`（REST API 経路、特許・職場情報・
   資本金など bulk に無いもの）と**並存する**。名前がどちらの経路かを言う。

   一致した 125,820 行のうち **125,144 行 (99.5%) は自治体**（交付金の受け手）
   だったので、government tier は 1 団体 1 entity の集計に畳んである
   （`:grant/summary? true`）。列挙すると 45 MB になり、面は**クエリのたびに**
   それを load する。行は捨てていない —— corpus に在る。

   したがって count が小さい aspect は「まだ引いていない」ではなく
   「その aspect の実績がその会社に無い」を意味するようになった。"
  [next-tempid!]
  (let [files (->> ["jp-go-gbiz-info"]
                   (keep west-project-path)
                   (mapcat (fn [p]
                             (let [dir (apply io/file root (concat (str/split p #"/") ["data"]))]
                               (when (.exists dir)
                                 (->> (seq (.listFiles dir))
                                      (filter #(str/ends-with? (str %) ".datoms.edn"))))))))]
    (if (empty? files)
      (do (js/console.error
           (str "edn-query: WARNING gbizinfo: com-junkawasaki/jp-go-gbiz-info の "
                "data/*.datoms.edn が無い — 補助金交付・調達・決算期は load されない"))
          [])
      (mapcat (fn [f] (or (corpus-line-entities f next-tempid!) [])) files))))

(defn gyousei-review-entities
  "行政事業レビューシートの**支出先**（`jp-go-gyoukaku-review` の
   `data/gyousei-review.datoms.edn`、ADR-2608181000 22 節）。

   gBizINFO（`:source/dataset \"gbizinfo\"`）が経産省の集約を通した交付実績なのに対し、
   こちらは**各府省が自分の事業について出す一次資料**。同じ `:company/houjin-bangou`
   を持つので 1 クエリで突き合わせられる —— どちらが欠けているかを言えるようになる。

   ⚠ **金額の単位が違う。** ここは `:review/total-million-jpy`（百万円・申告値）、
   gBizINFO は `:grant/amount-yen`。**足し合わせない** —— 名前が違うのは、静かに
   桁を間違えるより気付かせるためである。

   ⚠ **これは会社 × 府省に畳んだ側**（実測 3,463）。畳む前は面の番号に一致した
   17,808 行、全体では 80,319 行で、その分母は manifest の
   `:projection/folded-from` / `:projection/recipients-seen` に載る。
   **3,463 を「国の支出先はこれで全部」と読まない。**"
  [next-tempid!]
  (let [files (->> ["jp-go-gyoukaku-review"]
                   (keep west-project-path)
                   (mapcat (fn [p]
                             (let [dir (apply io/file root (concat (str/split p #"/") ["data"]))]
                               (when (.exists dir)
                                 (->> (seq (.listFiles dir))
                                      (filter #(str/ends-with? (str %) ".datoms.edn"))))))))]
    (if (empty? files)
      (do (js/console.error
           (str "edn-query: WARNING gyousei-review: com-junkawasaki/jp-go-gyoukaku-review の "
                "data/*.datoms.edn が無い — 各府省の原典から見た交付先は load されない"))
          [])
      (mapcat (fn [f] (or (corpus-line-entities f next-tempid!) [])) files))))

(defn web-presence-entities
  "法人番号に紐づく**公開面**（`company-web-presence` の
   `data/web-presence.datoms.edn`、ADR-2608181000）。

   `:web/url` は gBizINFO 由来（その会社が国に登録した URL）、`:press/feed-url`
   ほか `:press/*` は**こちらが取得して数えた事実**。混ぜないのは、前者が
   「こう名乗っている」で後者が「この日こう応えた」だから。

   ⚠ **数える前に分母を見る。** manifest 行の `:projection/queried`（1,040）に対して
   URL は 194、feed は 41 —— 日本の企業サイトは RSS を出さない方が普通で、
   **ここに無いのは「発信していない」ではなく「自社サイトに feed が無い」**。

   記事そのものはここに載らない。`kotoba-lang/newsfeed` が取得と台帳を持ち、
   catalog の各 entry が `:company/houjin-bangou` を持つので記事を会社に戻せる。

   同じディレクトリの `domain-facts.datoms.edn`（`:source/dataset` は
   `domain-facts`）もここから載る。**3 つの出所を混ぜないこと**: `:registry/*` はレジストリの登録内容
   （JPRS WHOIS / RDAP）、`:dns/*` は**こちらが引いて返ってきた**値、`:web/url` は
   会社の自己申告。`:registry/created-on` は会社の設立日ではなく**ドメインを取った日**。

   `:registry/name-agrees?` は、レジストリの `[組織名]` と法人番号側の商号が
   同じ会社を指しているか。**false は誤りではない** —— グループ親会社がドメインを
   持っている形が実在する（実測: 積水化学工業 / 積水武蔵化工、オリックス /
   オリックス・レンテック）。`:registry/registrant-withheld?` は「レジストリが
   答えなかった」ではなく「**個人名だったので載せなかった**」の印。"
  [next-tempid!]
  (let [files (->> ["company-web-presence"]
                   (keep west-project-path)
                   (mapcat (fn [p]
                             (let [dir (apply io/file root (concat (str/split p #"/") ["data"]))]
                               (when (.exists dir)
                                 (->> (seq (.listFiles dir))
                                      (filter #(str/ends-with? (str %) ".datoms.edn"))))))))]
    (if (empty? files)
      (do (js/console.error
           (str "edn-query: WARNING web-presence: com-junkawasaki/company-web-presence の "
                "data/*.datoms.edn が無い — 会社の URL とフィードは load されない"))
          [])
      (mapcat (fn [f] (or (corpus-line-entities f next-tempid!) [])) files))))

(defn kanpou-kessan-entities
  "官報の会社決算公告から抽出した**非上場企業の決算期**
   （`jp-go-npb-kanpou` の `data/kanpou-kessan.datoms.edn`、ADR-2608181000）。

   `:company/fiscal-year-end-month` は gBizINFO が上場会社に対して埋めるのと
   **同じ属性**で、こちらが非上場側を埋める。両方 `:company/houjin-bangou` を
   持つので、どちらから来たかを気にせず 1 クエリで聞ける（出所を区別したい時は
   `:source/dataset` を見る）。

   ⚠ **数えるときに 3 つ思い出す**: ①官報は直近 90 日しか無料で読めない
   ②決算公告の履行率が低く、**不在は「決算期が無い」ではない**
   ③名寄せで同名 2 社は解決しないので、`:company/houjin-bangou` を持たない
   レコードが残る（`:corpus/ambiguous-count`）。

   同じディレクトリから **`kanpou-chotatsu`（落札公示）と `kanpou-kaisan`
   （解散公告）**も載る。解散公告は「この会社はもう営業していない」という、
   リード・与信で最も強い 1 事実で、`:kaisan/resolved-on`（解散決議日）と
   `:kaisan/published-at`（掲載日）を分けて持つ。**`:kaisan/resolved-on` の不在は
   「決議していない」ではない** —— 決議日を本文に持たない公告と、縦書きの列境界で
   日付が割れたものがあり、そこは掲載日で埋めずに空けてある。"
  [next-tempid!]
  (let [files (->> ["jp-go-npb-kanpou"]
                   (keep west-project-path)
                   (mapcat (fn [p]
                             (let [dir (apply io/file root (concat (str/split p #"/") ["data"]))]
                               (when (.exists dir)
                                 (->> (seq (.listFiles dir))
                                      (filter #(str/ends-with? (str %) ".datoms.edn"))))))))]
    (if (empty? files)
      (do (js/console.error
           (str "edn-query: WARNING kanpou-kessan: com-junkawasaki/jp-go-npb-kanpou の "
                "data/*.datoms.edn が無い — 非上場の決算期は load されない"))
          [])
      (mapcat (fn [f] (or (corpus-line-entities f next-tempid!) [])) files))))

(defn property-ownership-entities
  "公開不動産 ownership claim（`data/property-ownership.datoms.edn`）。
   `:ownership/*` は kotoba.property.ownership の可搬コントラクトそのままなので、
   property repo が同梱する Datalog クエリがこの面でも動く。"
  [next-tempid!]
  (let [files (property-data-files "property-ownership")]
    (if (empty? files)
      (do (js/console.error
           (str "edn-query: WARNING property-ownership: kotoba-lang/property の "
                "data/property-ownership.datoms.edn が無い — 不動産 ownership は "
                "load されない"))
          [])
      (mapcat (fn [f] (or (corpus-line-entities f next-tempid!) [])) files))))

;; ---------- 公開アカウント・ディレクトリ（category J — global-accounts-datoms、ADR-2608059100） ----------
;;
;; **account 行（174,592 件）はこの面に載せない。** 載せるのは 3 種類だけ:
;;   catalog  `data/datascript-tx.edn`         — どの protocol にどんな公開
;;              ディレクトリが在るか（20 件、8 protocol）。`:directory/kind :none`
;;              の 6 件（did:web / WebFinger / Matrix / NIP-05 / OIDC discovery）が
;;              「そもそも列挙できるものが無い」を述べる —— corpus の欠落は
;;              この行の隣でしか読めない
;;   coverage `data/corpus/coverage.edn`       — 分母（2 件）
;;   services `data/corpus/*/services.edn`     — サーバ（790 件）。
;;              `:service/domain` が yabai-passive-dns / tadori-threat-intel の
;;              ドメイン文字列と join できる（DNS 面との唯一の接点）
;;
;; account を除いたのは容量の話ではなく到達性の話。account 行が join できる先は
;; `:account/service-host` → `:service/domain` だけで、その join はこの面に
;; services さえ在れば成立する。**個人識別子 174,592 件を全 query の作業集合に
;; 常駐させる理由が無い** —— account を引きたい query は
;; global-accounts-datoms 側の adapters/read_only.clj（公開 query のみ）を通す。
;; ADR-2608059100 / 同 repo の connections/actors.edn が正本。

(defn accounts-datoms-files
  "etzhayyim/global-accounts-datoms の committed projection のうち、この面に
   載せてよい 3 種類。west が宣言する path 配下を見る（org をパスに焼かない）。"
  []
  (when-let [p (west-project-path "global-accounts-datoms")]
    (let [base (apply io/file root (str/split p #"/"))]
      (->> [(io/file base "data" "datascript-tx.edn")
            (io/file base "data" "corpus" "coverage.edn")
            (io/file base "data" "corpus" "directory.plc" "services.edn")
            (io/file base "data" "corpus" "nodeinfo" "services.edn")]
           (filter #(.exists %))))))

(defn internet-accounts-entities
  "catalog + coverage + services を 1 dataset として load する。

   ⚠ `:service/source` を見ずに service を数えない。`:self-reported` は
   NodeInfo（サーバが自分について公開した文書）、`:observed` は PDS を
   アカウント側から数えたもので、**同じ列に見えて出所が違う**。
   `:service/users-total` は前者だけが持ち、しかも自己申告なので、
   1.7% 標本の合計を「fediverse の人口」として引用しない。"
  [next-tempid!]
  (let [files (accounts-datoms-files)]
    (if (empty? files)
      (do (js/console.error
           (str "edn-query: WARNING internet-accounts: etzhayyim/global-accounts-datoms の "
                "data/ projection が無い — 公開アカウント・ディレクトリは load されない"
                "（west update 未実行か、projection 未生成）"))
          [])
      (mapcat (fn [f]
                (for [e (or (vector-of-maps-entities f) [])]
                  (assoc e
                         :db/id (next-tempid!)
                         :source/dataset "internet-accounts"
                         :source/file (str f))))
              files))))

;; ---------- patent bibliographic（category J — hirameki-patents、ADR-2608100100） ----------
;; cloud-itonami/hirameki-patents の 80-data/public/*.journal.edn（quads
;; [entity attr value tx op] — toshokan と同じ ADR-2607072300 形）。lei-tos と同型。
;; :patent/applicant-lei が埋まっていれば :company/lei と join（財務×法人×特許）。
;;
;; ⚠ 2026-08-10 に corpus が kotoba-lang/toshokan-patents から移った
;; （ADR-2608100100: kotoba-lang は lib だけを持つ）。**この loader は移動の翌日まで
;; 消えた dir を読んでいて、面の特許は 0 件になっていた** —— しかも旧実装は dir が
;; 無いと黙って [] を返すので、count は「読み込めなかった」ではなく「特許が無い」に
;; 見えた。CLAUDE.md「握り潰した分は必ず stderr で報告する」に従い、
;; **corpus repo が checkout されていないことを警告する**。

;; ---------- 国別の注目（category K — hayari、ADR-2608103000） ----------
;; cloud-itonami/hayari の data/hayari-summary.edn。**raw の観測は repo 側で
;; .gitignore されている**（1 日 900 行超で、日々伸びる）ので、面に載るのは
;; commit されている要約 —— (国, 日) ごとに 1 entity と、1900 年からの
;; 年別カバレッジ 1 entity。
;;
;; :hayari.summary/country-iso2 は ISO 3166-1 alpha-2 で、LEI 面の
;; :company/jurisdiction の先頭 2 文字と join できる（あちらは US-DE のような
;; 下位区分付きの値も持つ）。
;;
;; ⚠ この loader が無かった間、hayari は全 datom に :source/dataset "hayari" を
;; 付けて**この面への所属を主張しながら、面は 1 件も読んでいなかった**。
;; hirameki と同じく、checkout が無い時は黙って [] を返さず stderr で警告する
;; —— count 0 は「観測が無い」ではなく「読めていない」。

(defn hayari-top-entities
  "The works each hayari country-day looked at most, as Wikidata records.

  Without this the plane can see :hayari.summary/top-qid but cannot say what
  the QID is. CC0-1.0, so a consumer inherits no share-alike obligation — the
  CC BY-SA article extracts are deliberately NOT in the tracked slice."
  [next-tempid!]
  (let [f (io/file root "orgs" "cloud-itonami" "hayari" "data" "hayari-top-entities.edn")]
    (if-not (.exists f)
      []
      (let [ents (try (slurp-edn (.getPath f))
                      (catch :default e
                        (js/console.error
                         (str "edn-query: WARNING hayari-top-entities: 読めない — " e))
                        nil))]
        (if-not (sequential? ents)
          []
          (mapv (fn [m] (assoc m :db/id (next-tempid!) :source/file (str f))) ents))))))

(defn hayari-entities [next-tempid!]
  (let [f (io/file root "orgs" "cloud-itonami" "hayari" "data" "hayari-summary.edn")]
    (if-not (.exists f)
      (do (js/console.error
           (str "edn-query: WARNING hayari: "
                "orgs/cloud-itonami/hayari/data/hayari-summary.edn が無い — "
                "国別の注目は load されない（west update 未実行か、collect 未実行）。"
                "count 0 は「観測が無い」ではなく「読めていない」。"))
          [])
      (let [ents (try (slurp-edn (.getPath f))
                      (catch :default e
                        (js/console.error
                         (str "edn-query: WARNING hayari: 要約が読めない — " e))
                        nil))]
        (if-not (sequential? ents)
          []
          (mapv (fn [m] (assoc m :db/id (next-tempid!) :source/file (str f))) ents))))))


(defn hirameki-patents-entities [next-tempid!]
  (let [dir (io/file root "orgs" "cloud-itonami" "hirameki-patents" "80-data" "public")
        legacy (io/file root "orgs" "kotoba-lang" "toshokan-patents" "80-data" "public")]
    (when (.exists legacy)
      (js/console.error
       (str "edn-query: WARNING hirameki-patents: 旧パス "
            "orgs/kotoba-lang/toshokan-patents/80-data/public が残っている。"
            "corpus は cloud-itonami/hirameki-patents へ移った（ADR-2608100100）。"
            "旧パスは読まない。")))
    (if-not (.exists dir)
      (do (js/console.error
           (str "edn-query: WARNING hirameki-patents: "
                "orgs/cloud-itonami/hirameki-patents/80-data/public が無い — "
                "特許は load されない（west update 未実行）。"
                "count 0 は「特許が無い」ではなく「読めていない」。"))
          [])
      (let [files (->> (.listFiles dir) (filter #(.endsWith (.getName %) ".journal.edn")) (sort-by #(.getName %)))
            skipped (atom [])
            out (mapcat
                 (fn [f]
                   (let [ents (or (not-empty (or (vector-tuple-journal-entities f) {}))
                                  (flat-attrlist-journal-entities f)
                                  {})]
                     (when (empty? ents) (swap! skipped conj (.getName f)))
                     (for [[eid attrs] ents]
                       (let [a (:patent/applicant attrs)
                             applicants (if (sequential? a) a (when a [a]))]
                         (assoc attrs
                                :db/id (next-tempid!)
                                :patent/entity-id (str eid)
                                :patent/applicant-norm (vec (keep normalize-name applicants))
                                :source/dataset "hirameki-patents"
                                :source/file (str f))))))
                 files)]
        (warn-skipped! "hirameki-patents journal" @skipped)
        out))))

;; ---------- fleet 状態データ（category K） ----------
;; どちらも「A vector of DataScript/Datomic-transactable entity-maps」と自ら
;; 宣言しているのに、この面の manifest corpus（repos.edn /
;; kotoba-boundaries.edn / cleanup-workflow.edn の 3 件のみ）に入っておらず
;; query できなかった。

;; repo-taxonomy は fleet「状態」ではなく repo の分類だが、形は同じ
;; （manifest/ 配下の vector-of-maps 生成物）なのでこのローダを共有する。
;; :repo/path で repo-maturity と、:company/lei で market-intel /
;; cloud-itonami-lei と join できる（ADR-2607289600 D5: 3 面は同じ ref に置く）。
(defn fleet-state-sources []
  [["itonami-fleet-audit" (io/file root "manifest" "itonami-fleet-audit.edn")]
   ["repo-maturity"       (io/file root "manifest" "repo-maturity.edn")]
   ["repo-taxonomy"       (io/file root "manifest" "repo-taxonomy.edn")]])

(defn fleet-state-entities [next-tempid!]
  (let [skipped (atom [])
        out (doall
             (mapcat
              (fn [[dataset f]]
                (if (.exists f)
                  (let [es (or (vector-of-maps-entities f) [])]
                    (when (empty? es) (swap! skipped conj dataset))
                    (for [e es]
                      (assoc e
                             :db/id (next-tempid!)
                             :source/dataset dataset
                             :source/file (str f))))
                  []))
              (fleet-state-sources)))]
    (warn-skipped! "fleet state" @skipped)
    out))

;; ---------- 索引データ（category L） ----------
;; 「作る前に、既に在るものを見る」ための 2 つの索引。どちらも生成物で、
;; `90-docs/**/…datoms.edn` の vector-of-maps なので同じローダを共有する。
;;
;;   surface  — どのホストがどのパスを提供しているか（HTTP の面）
;;              scripts/gen-surface-index.cljs、`:surface/repo`
;;   concept  — どの repo がどの概念を実装しているか（名前が機能を示さない
;;              repo を見つける経路）。scripts/gen-concept-index.cljs、
;;              `:concept/repo`
;;
;; どちらも `:concept/repo` / `:surface/repo` が repo-taxonomy の `:repo/path`
;; と同形なので join できる。concept は `:concept/coverage` entity を 1 件持ち、
;; 索引できなかった repo 数を申告する —— 索引を引いて出なかったことが
;; 「存在しない」の証拠に使われるため、カバレッジを query 側から読めることが要る。

(defn index-sources []
  [["surface" (io/file root "90-docs" "surface" "surface.datoms.edn")]
   ["concept" (io/file root "90-docs" "concept" "concept.datoms.edn")]
   ;; engine-parity — kami-engine 家 vs Unity / network-isekai vs Roblox・Fortnite・Steam
   ;; の parity 台帳（手書きの「現在値」文書。ADR-2608040300）。`:parity/subject` は
   ;; repo-taxonomy の `:repo/path` と同形ではなく org/repo 表記なので join には注意。
   ;; `:parity/coverage` entity を 1 件持ち、測定できていない面を申告する。
   ["engine-parity" (io/file root "90-docs" "maturity" "engine-parity.datoms.edn")]
   ;; threed-parity — CAD / CAM / DCC / render / CAE の能力軸を、**tree から測った**
   ;; 生成物（ADR-2608200100、`scripts/threed-maturity-audit.cljs`、`--check` あり）。
   ;; engine-parity（手書きの Unity/Roblox 比較）とは別の dataset で、こちらは
   ;; 判断が入るのは語彙（`90-docs/maturity/threed-capability-axes.edn`）だけ。
   ;;
   ;; ⚠ **`:parity/status` を 2 値として読まないこと。** `:working` は behavior probe
   ;; が通った軸、`:declared` は marker だけ通って **まだ測っていない**軸で、後者を
   ;; 実装済みに数えると測っていないものが緑になる。`:hollow` は marker が在って
   ;; probe が落ちた軸 —— grep でも行数でも LLM 採点でも緑に見えるので、これが最悪。
   ;; `:coverage/kind "threed-parity-coverage"` の 1 entity が、probe を書いた軸数と
   ;; 走査ファイル数を申告する。
   ;;
   ;; join: `:parity/subject` は org/repo 表記（repo-taxonomy の `:repo/path` とは同形
   ;;       ではない）。gtm-icp の `:icp/proof-axes` が `:parity/axis` を名指しする。
   ["threed-parity" (io/file root "90-docs" "maturity" "threed-parity.datoms.edn")]
   ;; gtm-icp — 誰に売るかの機械可読な定義（手書きの「現在値」文書。ADR-2608042000）。
   ;; `:icp/product` は BMC の product id と同形。`:icp/coverage*` を持つ entity を
   ;; 1 件持ち、ICP の述語のうちデータ面が評価**できない**ものを申告する —— target
   ;; list を「ICP 適合企業」と読み違えさせないため、その申告を query 側から読めることが要る。
   ["gtm-icp" (io/file root "90-docs" "business" "gtm-icp.datoms.edn")]
   ;; itonami-maturity — cloud-itonami 全 repo の成熟度スコア + 依存伝播 + leverage
   ;; 順位 + XMILE 戦略シミュレーション（生成物。ADR-2608052000）。`:repo/path` が
   ;; repo-taxonomy の `:repo/path` と同形なので join できる。3 種類の entity が
   ;; 混在する: repo 1 件ごとの行（`:maturity/own-bp` を持つ）/ `:summary/kind
   ;; "fleet-summary"` 1 件 / `:sim/strategy`・`:sweep/w-substrate` の模型出力。
   ;;
   ;; **測定値と模型値を混ぜて読まないこと。** `:maturity/axis-*` は観測から直接、
   ;; `:maturity/own-bp` は観測 × 重み（重みは判断で、`:model/weights` に載っている）、
   ;; `:maturity/effective`・`:leverage/*` は依存伝播モデルの出力（`:model/alpha`
   ;; 依存）、`:sim/*` は XMILE RK4 の軌道（`:model/work-rate` は scenario）。
   ;; fleet-summary は未 checkout / tombstone repo 数も申告する —— スコアが付いて
   ;; いないことが「悪い」ではなく「測っていない/対象外」であるため。
   ["itonami-maturity" (io/file root "90-docs" "system-dynamics" "itonami-maturity.datoms.edn")]
   ;; itonami-industry-scorecard — 上の repo 単位スコアを **産業（ISIC）軸**に畳んだ
   ;; 生成物（`scripts/gen-itonami-industry-scorecard.cljs`、`--check` あり）。
   ;; itonami.cloud の /industries/ が読んでいるのと同じ数。3 種類の entity:
   ;;   `:scorecard/kind "summary"` 1 件（出典・caveat・fleet 全体の数）
   ;;   `:scorecard/kind "section"` 21 件（A–U。coverage と 7 軸の平均）
   ;;   `:scorecard/kind "industry"` 651 件（registry の 1 行 = ISIC コード 1 つ）
   ;;
   ;; join: `:industry/repo-name` → itonami-maturity の `:repo/name`（同形）。
   ;;       `:industry/id` → permits の ISIC コード、marketplace.json の :id。
   ;;
   ;; ⚠ **coverage の分母は UN ISIC Rev.4 の 428 class**（org-un-isic）。registry 側は
   ;; 改訂番号を宣言していないので、突き合わない id は `:summary/off-classification`
   ;; に件数で出し、分子からも除いてある。`:section/unmeasured` は「スコア 0」では
   ;; なく「maturity scan にその repo が無い」——平均の分母から外してある。
   ["itonami-industry-scorecard"
    (io/file root "90-docs" "system-dynamics" "itonami-industry-scorecard.edn")]
   ;; permits — cloud-itonami の governed actor が各自の `facts.cljc` に持っている
   ;; **法規制カタログ**の射影（生成物。`scripts/gen-permit-index.cljs`、ADR-2608080000）。
   ;; 「採掘許可が要る法域はどこか」「配電の所管庁は誰か」を横断で引けるようにする。
   ;; **正本は各 actor の facts.cljc のまま** —— ここは射影で、規制の正本ではない。
   ;;
   ;; join: `:permit/repo` → repo-taxonomy / itonami-maturity の `:repo/path`（同形）。
   ;;       `:permit/jurisdiction` → `cloud-itonami-iso3166-<iso3>` repo。
   ;;       `:permit/kyoninka-procedure` → kotoba-lang/kyoninka の `:procedure/id`。
   ;;
   ;; **読み違えを防ぐために data 側が申告しているもの**（`:join/*` entity と
   ;; `:permit/coverage` entity を必ず見ること）:
   ;;   1. `:permit/legal-basis` と `:permit/authority` は**結合キーにできない**。
   ;;      同じ法律・同じ当局が repo ごとに違う表記で書かれており、手続き側との
   ;;      交差は実測ゼロ（`:join/status :unusable` として載っている）。
   ;;   2. `:permit/source-shape` が `:jurisdictions-map` の行は**法的引用を持たない**
   ;;      （法令名が `:name` の散文に入っているだけ）。引用付きだけを数えるなら絞る。
   ;;   3. **法域別の行数は制度の数ではない。** iso3166 の marketentry catalog が
   ;;      各国 repo に USA/DEU/GBR の比較行を再掲するため（`:coverage/caveat`）。
   ;;   4. `:permit/jurisdiction` は**観測済みの ISO3 のときだけ**出る。第 1 階層の
   ;;      キーが自治体 slug・団体 slug・制度 keyword のことがあり、[A-Z]{3} を国と
   ;;      決めると偽の国コードを鋳造する（"EUR" "IEC" は 3 文字だが国ではない）。
   ["permits" (io/file root "90-docs" "regulatory" "permits.datoms.edn")]
   ;; training-corpus — **どの文書が学習素材として何点か**（生成物。ADR-2608056000。
   ;; `scripts/gen-training-corpus.cljs`、policy は `manifest/corpus-policy.edn`）。
   ;; 3 種類の entity が混在する:
   ;;   1 文書 1 件（`:corpus/id` = repo 相対パス、6 軸のスコア + `:corpus/score`
   ;;     = 重み付き幾何平均 + `:corpus/tier` + `:corpus/hazard`）
   ;;   shard `:shard/id`（tier ごとに畳んだ bytes の annex key。`:shard/custody`
   ;;     が `:unverified` の間は「宛先を宣言しただけ」であって保管していない）
   ;;   `:corpus/coverage true` 1 件（走査数・skip 数・tier 別件数・policy の sha256）
   ;;
   ;; join: `:corpus/adr-id` → ADR entity の `"adr/id"`（スコアと決定内容を突き合わせ
   ;; られる）。`:corpus/repo` は org/repo 表記なので repo-taxonomy の `:repo/path`
   ;; とは同形ではない。
   ;;
   ;; ⚠ `:corpus/tier :excluded` は hazard 検出による除外で、**hazard 検査は既知の形
   ;; だけを弾く構造検査**。合格は秘密の不在を証明しない（公開先を広げる判断の
   ;; 根拠にしない）。
   ["training-corpus" (io/file root "90-docs" "corpus" "corpus.datoms.edn")]
   ;; observatory — 領域別 observatory を**実際に起動した**結果（生成物。
   ;; ADR-2608081200。`scripts/observatory-run.cljs`、登録簿は
   ;; `manifest/observatories.edn`）。2 種類の entity:
   ;;   actor 1 件ごとの行（`:observatory/name`、`:observatory/expect` と
   ;;     `:observatory/observed`、exit code、出力 bytes の差分、chain 検証）
   ;;   `:observatory/coverage true` 1 件（登録数・実行数・下振れ数・未測定数）
   ;;
   ;; join: `:observatory/repo` → repo-taxonomy / itonami-maturity の `:repo/path`（同形）。
   ;;
   ;; **他の dataset と性質が違う。** ほかは actor が産んだ *データ* の射影だが、
   ;; ここは actor が *動いたかどうか* の観測。「収集層が生きているか」を
   ;; query 面から引けるようにするために在る。
   ;;
   ;; ⚠ `:observatory/observed :runs-empty` は **exit 0 だが 0 件**。成功ではない
   ;;    （`:observatory/blocked-by` に理由がある）。exit code だけで数えないこと。
   ;; ⚠ `:observatory/output-gitignored true` の actor は、出力が各 repo の
   ;;    .gitignore に入っている charter 上のローカル台帳で、**走らせたマシンにしか
   ;;    存在しない**。ここに載っているのは観測サマリであって中身ではない。
   ;; ⚠ `:coverage/unmeasured` は「対象外」ではなく「まだ測っていない」。
   ["observatory" (io/file root "90-docs" "observatory" "observatory.datoms.edn")]
   ;; lending-alignment — 貸付を整合 / 直交 / 逆行の 3 体制に分類した実在組織カタログ
   ;; （手書きの「現在値」文書。ADR-2608082200。ADR-2608082100 の一般モデルを貸付に絞ったもの）。
   ;; 4 種類の entity が混在する:
   ;;   `:regime/id` 3 件（体制の定義と判定規則）
   ;;   `:test/id` 1 件（3 問 + 下振れ問の判定手続き）
   ;;   `:lending/id` 行（組織 × 商品 1 つごと）
   ;;   `:remedy/id` 2 件 / `:lending/coverage true` 1 件
   ;;
   ;; ⚠ **分類の単位は組織ではなく「組織 × 商品」。** 同じ銀行が事業融資（直交）と
   ;;    遅延損害金・リボ（逆行）とイスラム窓口（名目整合）を同時に持つ。ある行が
   ;;    逆行であることは、その組織の全事業が逆行だという主張ではない。
   ;; ⚠ **`:lending/confidence` を見ずに集計しない。** `:structural` は商品説明と契約
   ;;    形式からの読みで、**契約文を観測していない**（ADR-2608082100 が約款でやった
   ;;    定型句の実測に相当する作業は未実施）。`:documented` だけが一次資料に基づく。
   ;; ⚠ **`:lending/regime-nominal` を持つ行は名目と実質が食い違う。** イスラム金融の
   ;;    大半（名目整合・実質直交）と BloomTech（名目整合・実質逆行）がこれ。
   ;;    形式から体制を導出しないこと。
   ;; ⚠ **逆行の件数を時系列比較しない。** 行は執行記録から選ばれており、規制が弱い
   ;;    法域と非公式金融は構造的に欠落する。CFPB は 2025-04-29 に Credit Acceptance
   ;;    訴訟から離脱しており、検出可能性そのものが年によって動く。
   ;; ⚠ **LEI を 1 件も持たないので market-intel / cloud-itonami-lei と join できない。**
   ;;    推測した LEI が join 面を静かに壊すのを避けたため（`:coverage/no-lei-join`）。
   ["lending-alignment" (io/file root "90-docs" "finance" "lending-alignment.datoms.edn")]
   ;; arday-cofnas-affair — 2026-08 の Arday / Cofnas 事件の人物・発言カタログ
   ;; （手書きの観測記録）。他の索引と違い**生成物ではない**ので `--check` に相当する
   ;; 再生成検査が無い —— 更新は人（または agent）が直接書く。
   ;;
   ;; ⚠ **`:evidence/tier` を見ずに entity を数えないこと。** `:primary` は発言者自身の
   ;;    公表物か、それを引いた報道を直接取得したもの、`:secondary` は報道 1 本、
   ;;    `:tertiary` は Wikipedia の要約のみで**役職の記載に疑義があるものを含む**
   ;;    （`:evidence/caveat` が付く）。tier を潰して数えると、裏取りしていない
   ;;    肩書きが裏取り済みと同じ重みで面に載る。
   ;; ⚠ `:coverage/known-gaps` を持つ entity が 1 件あり、取得できなかった経路
   ;;    （原投稿・署名者名簿・403 の媒体）を申告する —— このカタログを引いて
   ;;    出なかったことを「その発言が無かった」の証拠に使わせないため。
   ;;
   ;; join: 現状どの dataset とも join key を共有しない（人名は文字列で、LEI も
   ;;       repo path も持たない）。孤島であることを承知の上で置いている。
   ["arday-cofnas-affair"
    (io/file root "90-docs" "knowledge" "arday-cofnas-affair.datoms.edn")]])

(defn index-entities [next-tempid!]
  (let [skipped (atom [])
        out (doall
             (mapcat
              (fn [[dataset f]]
                (if (.exists f)
                  (let [es (or (vector-of-maps-entities f) [])]
                    (when (empty? es) (swap! skipped conj dataset))
                    (for [e es]
                      (assoc e
                             :db/id (next-tempid!)
                             :source/dataset dataset
                             :source/file (str f))))
                  []))
              (index-sources)))]
    (warn-skipped! "index" @skipped)
    out))

;; fleet-db.edn は west.yml の **上流の正本**（ADR-2607160005、west.yml はその
;; projection）。トップレベルは単一 map で、query したい実体はその中の
;; `:fleet/repos`（実測 2,996 件、`:repo/name` `:repo/remote` `:repo/revision`
;; `:repo/path` `:repo/groups`）と `:fleet/remotes`（6 件）。`:fleet/header` /
;; `:fleet/footer` は west.yml を書き出すためのテキストなので entity にしない。
;;
;; fleet-ci.edn は署名付き CI receipt が 1 件（`:receipt` `:cid` `:signature`
;; `:signer`）。`:receipt` は入れ子 map なので `->ds-value` が blob 文字列にする。
;;
;; どちらも append-only / 署名付きの正本なので、このローダは **読むだけ**。

(defn fleet-db-entities [next-tempid!]
  (let [f (io/file root "manifest" "fleet-db.edn")]
    (if (.exists f)
      (let [repos   (or (seed-vector-entities f :fleet/repos) [])
            remotes (or (seed-vector-entities f :fleet/remotes) [])]
        (when (empty? repos)
          (warn-skipped! "fleet-db.edn :fleet/repos" ["(no repo entries parsed)"]))
        (concat
         (for [e repos]
           (assoc e :db/id (next-tempid!) :source/dataset "fleet-db" :source/file (str f)))
         (for [e remotes]
           (assoc e :db/id (next-tempid!) :source/dataset "fleet-db-remote" :source/file (str f)))))
      [])))

(defn fleet-ci-entities [next-tempid!]
  (let [f (io/file root "manifest" "fleet-ci.edn")]
    (if (.exists f)
      (if-let [e (single-map-entity f "fleet-ci")]
        [(assoc e :db/id (next-tempid!) :source/dataset "fleet-ci" :source/file (str f))]
        (do (warn-skipped! "fleet-ci.edn" ["(shape not a map)"]) []))
      [])))

;; ---------- cross-actor CTI graph（category M。yabai/tadori の DNS/IP/CTI） ----------
;; yabai (com-etzhayyim-yabai) は passive-DNS / TLS-CT / IOC / IP-hosting-history の
;; リスク評価グラフ、tadori (com-etzhayyim-tadori) は case-anchored evidence。
;; どちらも SecurityTrails 的な DNS/IP/CTI テレメトリを kotoba EAVT の EDN として持つ
;; （schema: 00-contracts/schemas/passive-dns-cti-ontology.kotoba.edn / tadori kotoba/schema.edn）。
;; :db.type/ref は datascript.js 上で解決されず文字列値として join する
;; （:pdns/domain "domain.x" が :domain/id "domain.x" と文字列一致 — lei-tos と同型）。
;; yabai の merged file は生成物（cf_sweep/rebuild-merged!）。query 面は point-in-time snapshot。
;; :access/* は envelope CID のみ（PII は暗号化済み・ADR-2605181100）。tadori は現状 sample のみ。

(defn- cti-file
  "yabai / tadori のデータファイルを **west に登録されている今のパス**から引く。

   ⚠ 両 repo は `orgs/etzhayyim/com-etzhayyim-*` から `orgs/cloud-itonami/*` へ
   移っている。loader は旧パスを見続けており、**ファイルが無いと黙って `[]` を
   返していた** —— 面の上では「passive DNS は 1 件も無い」と、
   「passive DNS を見に行けていない」が同じ顔をしていた（実測 2026-08-19、
   1,072 domain / 1,005 pDNS record が載っていないことに誰も気付けなかった）。

   だから今は**候補パスを全部見て、どれにも無ければ WARNING を出す**。"
  [candidates label]
  (let [fs (keep (fn [parts] (let [f (apply io/file root parts)] (when (.exists f) f)))
                 candidates)]
    (when (empty? fs)
      (js/console.error
       (str "edn-query: WARNING " label ": どの候補パスにもファイルが無い ("
            (str/join " | " (map #(str/join "/" %) candidates))
            ") — この dataset は load されない")))
    (first fs)))

(defn yabai-passive-dns-entities [next-tempid!]
  (if-let [f (cti-file [["orgs" "cloud-itonami" "yabai-actor" "data" "passive-dns.merged.kotoba.edn"]
                        ["orgs" "cloud-itonami" "yabai" "data" "passive-dns.merged.kotoba.edn"]
                        ["orgs" "etzhayyim" "com-etzhayyim-yabai" "data" "passive-dns.merged.kotoba.edn"]]
                       "yabai passive-dns")]
    (let [es (or (vector-of-maps-entities f) [])]
      (when (empty? es) (warn-skipped! "yabai passive-dns.merged.kotoba.edn" [f]))
      (for [e es]
        (assoc e :db/id (next-tempid!)
               :source/dataset "yabai-passive-dns"
               :source/file (str f))))
    []))

(defn tadori-threat-intel-entities [next-tempid!]
  (if-let [f (cti-file [["orgs" "cloud-itonami" "tadori" "persisted" "tadori-threat-intel.tx.kotoba.edn"]
                        ["orgs" "cloud-itonami" "tadori" "data" "persisted" "tadori-threat-intel.tx.kotoba.edn"]
                        ["orgs" "etzhayyim" "com-etzhayyim-tadori" "data" "persisted" "tadori-threat-intel.tx.kotoba.edn"]]
                       "tadori threat-intel")]
    (let [es (or (add-datoms-entities f nil :tadori/entity-id) [])]
      (when (empty? es) (warn-skipped! "tadori threat-intel.tx.kotoba.edn" [f]))
      (for [e es]
        (assoc e :db/id (next-tempid!)
               :source/dataset "tadori-threat-intel"
               :source/file (str f))))
    []))

;; kakekomi (cloud-itonami/kakekomi) — 国外で犯罪被害に遭った渡航者の初動 corpus。
;; data/*.edn はいずれもトップレベルが entity map の vector なので vector-of-maps
;; でそのまま読める。面をまたぐ結合キーは :jurisdiction/iso3166-alpha3 で、
;; cloud-itonami-iso3166-<alpha3> 群の法域 entity と文字列一致で join する
;; （lei-tos / yabai と同型。datascript.js は :db.type/ref を解決しない）。
;;
;; **この corpus は網羅していない**（2026-08-03 時点で 42/249 法域）。載って
;; いない法域は :not-yet-collected であって「該当なし」ではない。data/coverage.edn
;; の entity がその差を持っているので、集計するときは必ず一緒に読むこと——
;; jurisdiction を数えて「世界の N 割」と読むと誤る。

(defn kakekomi-entities [next-tempid!]
  (let [dir (io/file root "orgs" "cloud-itonami" "kakekomi" "data")]
    (if (.exists dir)
      (let [skipped (atom [])
            es (->> (file-seq dir)
                    (filter #(data-file? %))
                    (sort-by str)
                    (mapcat (fn [f]
                              (let [rows (vector-of-maps-entities f)]
                                (when-not (seq rows) (swap! skipped conj (str f)))
                                (map #(assoc % :source/file (str f)) (or rows []))))))]
        (warn-skipped! "kakekomi data/*.edn" @skipped)
        (when (empty? es) (warn-skipped! "kakekomi" [dir]))
        (for [e es]
          (assoc e :db/id (next-tempid!) :source/dataset "kakekomi")))
      [])))

;; okugai-inventory (kotoba-lang/loop-okugai-survey) — 屋外広告物（physical / OOH）の
;; 在庫候補台帳。電柱・ビルボード・広告板・屋外ビジョン・ポスターボックス等を
;; 同じ面に載せる（電柱だけの denchu-inventory を 2026-08-04 に一般化して置き換えた）。
;; survey が area ごとに `okugai-inventory-<area>.datoms.edn` を、媒体・規制・電柱
;; 代理店の静的カタログを `okugai-catalog.datoms.edn` を書く（どちらも生成物）。
;;
;; 面をまたぐ結合キー:
;;   :site/medium (文字列 "billboard")     ↔ :medium/id
;;   :site/operator                        ↔ 媒体 repo のカタログ（:agency/sells-poles-of 等）
;;   :medium/regulatory-triggers の各要素  ↔ :regulation/id
;; datascript.js は :db.type/ref を解決しないので値一致で join する
;; （lei-tos / yabai / kakekomi と同型）。
;;
;; **この台帳は網羅していない。** survey した area の中で、要求した媒体しか見て
;; いない。さらに **屋上看板・SA/PA 内広告・高速道路沿道の後付け分類・シェルター
;; 広告は観測タグを持たないので survey には決して出ない**（`:medium/observable false`）。
;; `:okugai.coverage/*` entity を必ず一緒に読むこと —— 地点を数えて「日本の N%」と
;; 読むと誤る。`:site/ad-eligible` は全件 "unknown"、`:site/height-known` は既定 false
;; （高さが分からないので建築基準法88条の要否は判定できない）。

(defn okugai-inventory-entities [next-tempid!]
  (let [dir (io/file root "orgs" "kotoba-lang" "loop-okugai-survey" "data")]
    (if (.exists dir)
      (let [skipped (atom [])
            es (->> (file-seq dir)
                    (filter #(str/ends-with? (str %) ".datoms.edn"))
                    (sort-by str)
                    (mapcat (fn [f]
                              (let [rows (vector-of-maps-entities f)]
                                (when-not (seq rows) (swap! skipped conj (str f)))
                                (map #(assoc % :source/file (str f)) (or rows []))))))]
        (warn-skipped! "okugai-inventory data/*.datoms.edn" @skipped)
        (when (empty? es) (warn-skipped! "okugai-inventory" [dir]))
        (for [e es]
          (assoc e :db/id (next-tempid!) :source/dataset "okugai-inventory")))
      [])))

;; ---------- tsukuru factory registry（ADR-2800003200 Phase 1） ----------
;; cloud-itonami/tsukuru-actor の kotoba/*.edn。製造委託先（工場）を面に載せる。
;;
;; **3 つの dataset に分ける。1 つに畳んではならない。** この 3 ファイルは
;; 「どれだけ本物か」ではなく **その企業がこのプラットフォームに何を同意したか**
;; で違う。畳むとその区別がクエリ側から消える:
;;
;;   tsukuru-candidates    公開ディレクトリ由来の research 参照（2,119 社 / 106 か国）。
;;                         **同意も onboarding も無い。** :factory/did は
;;                         "candidate:manufacturer-directory/..." で、did:web ではない。
;;                         :factory/labor-provenance は全件 :unknown。
;;   tsukuru-registry-seed 実在企業の例示 seed（99 社）。:sourcing :representative。
;;                         **これも登録済み関係ではない。**
;;   tsukuru-seed          R0 の worked example（3 社 + production-order / progress /
;;                         sbt entity）。デモであって実在の取引ではない。
;;
;; `wire/catalogs/manufacturer-catalog.v1.json` は **読まない** —— 約 90% が合成
;; プレースホルダで、別 consumer 向けの read-only データ。ここに混ぜると
;; 「実在企業 2,119 社」という数字が嘘になる。
;;
;; **:company/lei を合成しない。** candidates.edn は LEI を持たない。持たせれば
;; 捏造になるので、market-intel / cloud-itonami-lei との join は
;; :factory/display-name × :company/legal-name の**人間が確認する候補提示**に留める
;; （自動同定は Phase 1 の scope 外 —— ADR-2800003200 の「Phase 1 は join を約束しない」）。
;;
;; 面をまたぐ結合キー:
;;   :factory/did          ↔ :production-order/factory-did（同一 dataset 内）
;;   :factory/isic         ↔ cloud-itonami-* の ISIC blueprint repo 名
;;   :factory/display-name ↔ :company/legal-name（**値一致の候補提示のみ**）

(def ^:private tsukuru-factory-sources
  ;; [ファイル名 dataset 名] —— 追加するときは dataset 名も必ず新設する。
  ;; 既存 dataset に相乗りさせると、同意の段階が違うものが同じタグになる。
  [["candidates.edn"                 "tsukuru-candidates"]
   ["manufacturer-registry-seed.edn" "tsukuru-registry-seed"]
   ["seed.edn"                       "tsukuru-seed"]])

(defn tsukuru-factory-entities [next-tempid!]
  (let [dir (io/file root "orgs" "cloud-itonami" "tsukuru-actor" "kotoba")]
    (if (.exists dir)
      (let [skipped (atom [])
            es (->> tsukuru-factory-sources
                    (mapcat (fn [[fname dataset]]
                              (let [f (io/file dir fname)]
                                (if-not (.exists f)
                                  (do (swap! skipped conj (str f)) [])
                                  (let [rows (vector-of-maps-entities f)]
                                    (when-not (seq rows) (swap! skipped conj (str f)))
                                    (map #(assoc % :source/file (str f)
                                                   :source/dataset dataset)
                                         (or rows []))))))))]
        (warn-skipped! "tsukuru-actor kotoba/*.edn" @skipped)
        (for [e es]
          (assoc e :db/id (next-tempid!))))
      [])))

;; ---------- 因縁 dependency record（category N。ADR-2607258500） ----------
;; kotoba-lang/loop-innen の corpus/*.edn（+ resources/*-corpus.edn）。
;; entity 間の**依存エッジ**を持つ唯一の corpus — この面には従来「entity の台帳」
;; （company/lei 系・municipality）と「entity 内部の stock/flow」（loop-system-dynamics）
;; はあったが、entity → entity の依存関係（supply / ownership / funding /
;; legal-authority / causation / participation / infrastructure …）が無かった。
;;
;; 形は `{:innen/nodes [...] :innen/edges [...]}` の単一 map。node/edge は
;; どちらも DataScript-transactable な entity map だが、`:innen.node/existed` /
;; `:innen.edge/valid` だけは入れ子 map なので、ここで **scalar に平坦化**する
;; （`->ds-value` が blob 文字列にしてしまうと `?k < 18000000` のような期間
;; 比較ができなくなる）。平坦化は innen.tx/->flat-tx と同じ規則:
;;   :innen.edge/valid {:from "1602-03-20" :to "1799-12-31"}
;;     -> :innen.edge/valid-from / -to（文字列）
;;      + :innen.edge/valid-from-key / -to-key（整数。BCE を含めて単調）
;; edge の endpoint は `:innen.edge/from-id` / `-to-id`（keyword id）で、
;; datascript.js に lookup ref は無いので `:innen.node/id` と**値で** join する
;; （lei-tos / yabai と同型）。
;;
;; :company/lei は innen 側でも同じ属性名なので、この面に載せるだけで
;; 「依存グラフ × SEC 財務 × 法人実体 × ToS」が 1 クエリで結合可能になる。

(defn innen-corpus-files []
  (let [repo (io/file root "orgs" "kotoba-lang" "loop-innen")]
    (when (.exists repo)
      (concat (let [d (io/file repo "resources")]
                (when (.exists d)
                  (->> (.listFiles d)
                       (filter #(.endsWith (.getName %) "-corpus.edn"))
                       (sort-by #(.getName %)))))
              (let [d (io/file repo "corpus")]
                (when (.exists d)
                  (->> (.listFiles d)
                       (filter #(.endsWith (.getName %) ".edn"))
                       (sort-by #(.getName %)))))))))

(defn- innen-date-key
  "innen.time の key encoding（year*10000 + month*100 + day）。BCE も含めて単調に
   なるので Datalog の範囲比較に使える。ISO 文字列の辞書順ではこれができない
   （\"-0221\" は全ての CE 日付より前に並ぶ）。coarse な日付は from 側を下限、
   to 側を上限に寄せる（innen.time/lower-key・upper-key と同じ規則）。"
  [s upper?]
  (when (and (string? s) (seq s))
    (let [bce? (str/starts-with? s "-")
          body (if bce? (subs s 1) s)
          [y m d] (map #(js/parseInt % 10) (str/split body #"-"))]
      (when (and y (not (js/isNaN y)))
        (let [y (if bce? (- y) y)
              m (if (and m (not (js/isNaN m))) m (if upper? 12 1))
              d (if (and d (not (js/isNaN d))) d (if upper? 31 1))]
          (+ (* y 10000) (* m 100) d))))))

(defn- innen-flatten-interval
  "入れ子 interval map を scalar 属性に展開する。attr-prefix 例: \"innen.edge/valid\"。"
  [e k attr-prefix]
  (if-let [iv (get e k)]
    (let [{:keys [from to]} iv]
      (cond-> (dissoc e k)
        from (assoc (keyword (namespace (keyword attr-prefix)) (str (name (keyword attr-prefix)) "-from")) from)
        to (assoc (keyword (namespace (keyword attr-prefix)) (str (name (keyword attr-prefix)) "-to")) to)
        (innen-date-key from false) (assoc (keyword (namespace (keyword attr-prefix)) (str (name (keyword attr-prefix)) "-from-key")) (innen-date-key from false))
        (innen-date-key to true) (assoc (keyword (namespace (keyword attr-prefix)) (str (name (keyword attr-prefix)) "-to-key")) (innen-date-key to true))
        ;; 元の map も pr-str で残す（この面の入れ子値の慣習。無損失にする）
        true (assoc (keyword (namespace (keyword attr-prefix)) (str (name (keyword attr-prefix)) "-edn")) (pr-str iv))))
    e))

(defn innen-entities [next-tempid!]
  (let [files (innen-corpus-files)
        skipped (atom [])
        out (doall
             (mapcat
              (fn [f]
                (let [c (try (slurp-edn f) (catch :default _ nil))
                      dataset (or (:innen/dataset c) "innen")
                      nodes (when (map? c) (:innen/nodes c))
                      edges (when (map? c) (:innen/edges c))]
                  (if-not (seq nodes)
                    (do (swap! skipped conj (.getName f)) [])
                    (concat
                     (for [n nodes]
                       (-> n
                           (innen-flatten-interval :innen.node/existed "innen.node/existed")
                           (assoc :db/id (next-tempid!)
                                  :source/dataset dataset
                                  :source/file (str f))))
                     (for [e (or edges [])]
                       (-> e
                           (innen-flatten-interval :innen.edge/valid "innen.edge/valid")
                           (assoc :db/id (next-tempid!)
                                  ;; from-id/to-id を明示的に持たせる（corpus が
                                  ;; from/to だけを持つ形でも値 join できるように）
                                  :innen.edge/from-id (:innen.edge/from e)
                                  :innen.edge/to-id (:innen.edge/to e)
                                  :source/dataset dataset
                                  :source/file (str f))))))))
              files))]
    (warn-skipped! "innen corpus" @skipped)
    out))

;; ---------- awai-yakuwari（network-awai の6ビジネスの運営役割。ADR-2607300800） ----------
;;
;; 正本は orgs/network-awai/loop-yakuwari:
;;   fleet.edn                 fleet 方針（global WIP / weights / capability ceiling）
;;   businesses.edn            6 ビジネスと役割集合、および「意図的に無い役割」
;;   yakuwari/<business>.edn   役割そのもの（1 ファイル = 1 ビジネス、複数 entity）
;;
;; **authored file は :db/id を持たない素の map。** ここで後付けするのは fleet-db /
;; innen / market-intel と同じ慣習で、pre-wrapped tx-data なのは 90-docs/adr/*.edn
;; だけ。こうしておくと同じファイルを yakuwari.spec/validate がそのまま読める。
;;
;; **入れ子 map は平坦化する。** `:yakuwari/scale {:min 0 :desired 1 :max 2}` を
;; そのまま渡すと `->ds-value` が pr-str の blob 文字列にするので `:desired` で
;; query できない。innen-flatten-interval と同じ手で scalar 属性に展開し、無損失の
;; ため `-edn` blob も残す。capabilities は decision ごとの cardinality-many 属性に
;; 展開する（「承認待ちを抱えた役割をビジネス横断で数える」を 1 query にするため）。
;;
;; join 可能な先: `:repo/path` → repo-taxonomy / repo-maturity。
;; `:company/lei` での market-intel / cloud-itonami-lei との join は **まだできない**
;; — fleet.edn は Delaware file no. しか持っておらず LEI を持たないので、ここで
;; LEI を捏造しない（記録されたら 1 行足すだけで繋がる）。

(defn- awai-dir [] (io/file root "orgs" "network-awai" "loop-yakuwari"))

(defn- awai-flatten-scale
  "`:yakuwari/scale` の {:min :desired :max} を scalar 属性へ。元 map は
   `:yakuwari.scale/edn` に pr-str して残す（この面の入れ子値の慣習）。"
  [e]
  (if-let [s (:yakuwari/scale e)]
    (cond-> (dissoc e :yakuwari/scale)
      true (assoc :yakuwari.scale/edn (pr-str s))
      (:min s) (assoc :yakuwari.scale/min (:min s))
      (contains? s :min) (assoc :yakuwari.scale/min (:min s))
      (contains? s :desired) (assoc :yakuwari.scale/desired (:desired s))
      (contains? s :max) (assoc :yakuwari.scale/max (:max s)))
    e))

(defn- awai-flatten-capabilities
  "`:yakuwari/capabilities` の [{:capability :decision :note}] を
   decision ごとの cardinality-many 属性へ:

     :yakuwari.policy/autonomous        #{cap …}
     :yakuwari.policy/voice-required    #{cap …}
     :yakuwari.policy/approval-required #{cap …}
     :yakuwari.policy/blocked           #{cap …}
     :yakuwari/capability               #{全 cap}

   decision 名は yakuwari.policy の語彙をそのまま属性名に使う（別名を作ると
   2 語彙が並立する）。未知の decision は落とさず :yakuwari.policy/unknown に
   入れる — 黙って消すと typo が『その capability は無い』ように読める。"
  [e]
  (let [caps (:yakuwari/capabilities e)]
    (if-not (sequential? caps)
      e
      (let [known #{:autonomous :voice-required :approval-required :blocked}
            grouped (reduce (fn [acc {:keys [capability decision]}]
                              (if (nil? capability)
                                acc
                                (let [d (if (contains? known decision) decision :unknown)]
                                  (update acc d (fnil conj []) (kw->attr capability)))))
                            {} caps)]
        (cond-> (-> e
                    (dissoc :yakuwari/capabilities)
                    (assoc :yakuwari.capabilities/edn (pr-str caps)
                           :yakuwari.capabilities/count (count caps)
                           :yakuwari/capability
                           (vec (keep #(some-> (:capability %) kw->attr) caps))))
          (:autonomous grouped) (assoc :yakuwari.policy/autonomous (:autonomous grouped))
          (:voice-required grouped) (assoc :yakuwari.policy/voice-required (:voice-required grouped))
          (:approval-required grouped) (assoc :yakuwari.policy/approval-required (:approval-required grouped))
          (:blocked grouped) (assoc :yakuwari.policy/blocked (:blocked grouped))
          (:unknown grouped) (assoc :yakuwari.policy/unknown (:unknown grouped)))))))

(defn- awai-role-entity [f e]
  (-> e
      awai-flatten-scale
      awai-flatten-capabilities
      ;; :yakuwari/project is "<org>/<repo>"; repo-taxonomy and repo-maturity
      ;; key on "orgs/<org>/<repo>", so emit the joinable form explicitly
      ;; rather than making every query rebuild it.
      (as-> m (if-let [p (:yakuwari/project m)]
                (assoc m :repo/path (str "orgs/" p))
                m))
      ;; The role's kind (:sales) apart from its full id
      ;; (:net-kotobase/sales), so "every supporter across the fleet" is one
      ;; clause instead of a string match.
      (as-> m (if-let [id (:yakuwari/id m)]
                (assoc m :yakuwari/kind (name id))
                m))
      (assoc :source/dataset "awai-yakuwari" :source/file (str f))
      (dissoc :yakuwari/mandate)
      (as-> m (if-let [md (:yakuwari/mandate e)]
                (assoc m :yakuwari.mandate/edn (pr-str md))
                m))))

(defn awai-yakuwari-entities [next-tempid!]
  (let [dir (awai-dir)
        roles-dir (io/file dir "yakuwari")
        skipped (atom [])]
    (if-not (.exists dir)
      ;; loop-yakuwari が未 checkout のときは黙ってスキップする（他の子リポ
      ;; ローダと同じ。ただし「0 件」と「未 checkout」は区別できるよう、
      ;; checkout があるのに 0 件のときだけ WARNING を出す）。
      []
      (let [role-files (when (.exists roles-dir)
                         (->> (file-seq roles-dir)
                              (filter #(data-file? %))
                              ;; sort-by str, not sort: File objects are not
                              ;; comparable, and plain `sort` throws.
                              (sort-by str)))
            roles (vec (mapcat (fn [f]
                                 (let [es (try (slurp-edn f) (catch :default _ nil))]
                                   (if (and (vector? es) (seq es) (every? map? es))
                                     (map #(awai-role-entity f %) es)
                                     (do (swap! skipped conj (str f)) []))))
                               role-files))
            bf (io/file dir "businesses.edn")
            businesses (when (.exists bf)
                         (let [c (try (slurp-edn bf) (catch :default _ nil))]
                           (if-let [bs (:awai.businesses/businesses c)]
                             (for [b bs]
                               (-> b
                                   ;; :roles / :roles-absent are a vector of
                                   ;; keywords and a vector of maps; the first
                                   ;; survives as an array, the second must be
                                   ;; a blob, so name them apart.
                                   (dissoc :business/roles-absent)
                                   (assoc :business/role-count (count (:business/roles b))
                                          :business/roles-absent-edn
                                          (pr-str (vec (:business/roles-absent b)))
                                          :business/roles-absent-count
                                          (count (:business/roles-absent b))
                                          :repo/path (str "orgs/" (:business/repo b))
                                          :source/dataset "awai-yakuwari"
                                          :source/file (str bf))))
                             (do (swap! skipped conj (str bf)) nil))))
            ff (io/file dir "fleet.edn")
            fleet (when (.exists ff)
                    (let [c (try (slurp-edn ff) (catch :default _ nil))]
                      (when (map? c)
                        [(-> c
                             (dissoc :awai.fleet/capability-ceiling
                                     :awai.fleet/principal :awai.fleet/resources
                                     :awai.fleet/weights :awai.fleet/authority
                                     :awai.fleet/journal :awai.fleet/residency
                                     :awai.fleet/runners)
                             (assoc :awai.fleet/capability-ceiling-edn
                                    (pr-str (:awai.fleet/capability-ceiling c))
                                    :awai.fleet/global-wip
                                    (:global-wip (:awai.fleet/resources c))
                                    :awai.fleet/principal-name
                                    (:principal/name (:awai.fleet/principal c))
                                    :awai.fleet/principal-registration
                                    (:principal/registration (:awai.fleet/principal c))
                                    :source/dataset "awai-yakuwari"
                                    :source/file (str ff)))])))]
        (when (and (.exists roles-dir) (empty? roles))
          (warn-skipped! "loop-yakuwari yakuwari/*.edn" ["(checkout present but no roles parsed)"]))
        (warn-skipped! "loop-yakuwari" @skipped)
        (concat
         (for [e roles] (assoc e :db/id (next-tempid!)))
         (for [e (or businesses [])] (assoc e :db/id (next-tempid!)))
         (for [e (or fleet [])] (assoc e :db/id (next-tempid!))))))))

;; ---------- schema (manifest/schema.edn -> datascript createConn schema) ----------

(defn schema-path [] (io/file root "manifest" "schema.edn"))

(defn card-many-attrs-set
  "DataScript schema の cardinality-many 宣言を set に落とす（datalog ロード用）。"
  []
  (let [from-schema (if (.exists (schema-path))
                      (into #{} (keep #(when (= (:db/cardinality %) :db.cardinality/many)
                                          (kw->attr (:db/ident %)))
                                    (slurp-edn (schema-path))))
                      #{})]
    (into dlq/*card-many-attrs*
          (concat from-schema
                  ["patent/applicant-norm"
                   "yakuwari.policy/autonomous" "yakuwari.policy/voice-required"
                   "yakuwari.policy/approval-required" "yakuwari.policy/blocked"
                   "yakuwari.policy/unknown" "yakuwari/capability"
                   "yakuwari/runners" "business/roles"
                   "factory/capabilities" "factory/fulfillment-modes"]))))

(defn ds-schema []
  (card-many-attrs-set))

;; ---------- build + query ----------

(defn build-conn []
  (binding [dlq/*card-many-attrs* (card-many-attrs-set)]
    (let [tempid (atom 0)
          next-tempid! (fn [] (swap! tempid dec))
        ;; ADR: multi-entity catalog も含め全 entity をロード
        adr-tx (mapcat (fn [f]
                         (map (fn [e] (assoc e :db/id (next-tempid!)))
                              (or (adr-entities-from-file f) [])))
                       (adr-files))
        docs-tx (mapcat (fn [f]
                          (map (fn [e] (assoc e :db/id (next-tempid!)))
                               (or (doc-entities-from-file f) [])))
                        (docs-edn-files))
        manifest-tx (keep (fn [f]
                             (when-let [e (wrapped-entity f)]
                               (assoc e :db/id (next-tempid!) :source/file (str f))))
                           (manifest-edn-files))
        foreign-adr-tx (foreign-adr-entities next-tempid!)
        biz-tx (business-metrics-entities next-tempid!)
        canvas-tx (canvas-ledger-entities next-tempid!)
        kj-tx (kawasakijun-entities next-tempid!)
        rad-tx (rad-entities next-tempid!)
        journal-tx (journal-tuple-entities next-tempid!)
        genome-tx (genome-datoms-entities next-tempid!)
        datoms-tx (datoms-kotoba-entities next-tempid!)
        hirameki-corpus-tx (hirameki-corpus-entities next-tempid!)
        jinushi-tx (jinushi-land-entities next-tempid!)
        proc-registry-tx (procedure-registry-entities next-tempid!)
        merged-kotoba-tx (kanjo-kabuto-unspsc-cleanroom-entities next-tempid!)
        working-doc-tx (working-doc-entities next-tempid!)
        narrative-tx (concat (spirit-in-physics-entities next-tempid!)
                              (ghosthacker-manga-log-entities next-tempid!))
        company-tx (concat (company-facts-entities next-tempid!)
                            (lei-blueprint-entities next-tempid!)
                            (lei-tos-entities next-tempid!)
                            (lei-facts-entities next-tempid!)
                            (gleif-lei-entities next-tempid!)
                            (houjin-bangou-entities next-tempid!)
                            (invoice-entities next-tempid!))
        property-tx (property-ownership-entities next-tempid!)
        ;; 補助金は company とは別 dataset にする（法人側と join できないので、
        ;; company を数える query に 3,751 件を混ぜない）。
        subsidy-tx (jgrants-entities next-tempid!)
        ;; gBizINFO は company-tx に入れる（`:company/houjin-bangou` を持ち、
        ;; 法人側と実際に join するため）。jgrants と違うのはそこ。
        gbizinfo-tx (gbizinfo-entities next-tempid!)
        kanpou-tx (kanpou-kessan-entities next-tempid!)
        web-presence-tx (web-presence-entities next-tempid!)
        gyousei-review-tx (gyousei-review-entities next-tempid!)
        ;; company-tx と別にするのは、entity を聞くだけの query に 23,530 本の
        ;; edge を load させないため（ADR-2608031900）。
        relationship-tx (gleif-relationship-entities next-tempid!)
        fleet-tx (concat (fleet-state-entities next-tempid!)
                          (fleet-db-entities next-tempid!)
                          (fleet-ci-entities next-tempid!))
        index-tx (index-entities next-tempid!)
        yabai-tx (yabai-passive-dns-entities next-tempid!)
        tadori-tx (tadori-threat-intel-entities next-tempid!)
        kakekomi-tx (kakekomi-entities next-tempid!)
        patent-tx (hirameki-patents-entities next-tempid!)
        accounts-tx (internet-accounts-entities next-tempid!)
        innen-tx (innen-entities next-tempid!)
        awai-tx (awai-yakuwari-entities next-tempid!)
        okugai-tx (okugai-inventory-entities next-tempid!)
        factory-tx (tsukuru-factory-entities next-tempid!)
        hayari-tx (hayari-entities next-tempid!)
        hayari-ent-tx (hayari-top-entities next-tempid!)
        all-tx (vec (concat adr-tx docs-tx manifest-tx foreign-adr-tx
                            biz-tx canvas-tx kj-tx rad-tx
                            journal-tx genome-tx datoms-tx
                            hirameki-corpus-tx jinushi-tx
                            proc-registry-tx merged-kotoba-tx
                            working-doc-tx narrative-tx
                            company-tx gbizinfo-tx kanpou-tx web-presence-tx
                            gyousei-review-tx
                            property-tx subsidy-tx relationship-tx fleet-tx
                            yabai-tx tadori-tx patent-tx accounts-tx innen-tx
                            awai-tx kakekomi-tx okugai-tx factory-tx
                            hayari-tx hayari-ent-tx
                            index-tx))
        db (dlq/build-db all-tx)]
      {:db db
     :gleif-tiers (sort (gleif-tiers))
     :houjin-bangou-tiers (sort (houjin-bangou-tiers))
     :adr-count (count adr-tx)
     :docs-count (count docs-tx)
     :manifest-count (count manifest-tx)
     :foreign-adr-count (count foreign-adr-tx)
     :biz-count (+ (count biz-tx) (count canvas-tx))
     :kj-count (count kj-tx)
     :rad-count (count rad-tx)
     :etzhayyim-80-data-count (+ (count journal-tx) (count genome-tx) (count datoms-tx)
                                  (count hirameki-corpus-tx) (count jinushi-tx))
     :proc-registry-count (count proc-registry-tx)
     :merged-kotoba-count (count merged-kotoba-tx)
     :working-doc-count (count working-doc-tx)
     :narrative-count (count narrative-tx)
     :company-count (count company-tx)
     :property-count (count property-tx)
     :fleet-count (count fleet-tx)
     :yabai-count (count yabai-tx)
     :tadori-count (count tadori-tx)
     :patent-count (count patent-tx)
     ;; catalog / coverage / service を 1 つの数字にしない。20 の directory と
     ;; 790 の service が溶けると「810 件のアカウント情報」と読める。
     :accounts-directory-count (count (filter :directory/id accounts-tx))
     :accounts-coverage-count (count (filter :coverage/id accounts-tx))
     :accounts-service-count (count (filter :service/id accounts-tx))
     :innen-count (count innen-tx)
     :awai-yakuwari-count (count awai-tx)
     :okugai-inventory-count (count okugai-tx)
     ;; 3 dataset を合算した 1 個の factory-count にしない —— 同意していない
     ;; 2,119 社と 3 社のデモが 1 つの数字に溶けると、それを読んだ人が
     ;; 「登録済み工場 2,221 社」と誤読する（ADR-2800003200）。
     :tsukuru-candidates-count (count (filter #(= "tsukuru-candidates" (:source/dataset %)) factory-tx))
     :tsukuru-registry-seed-count (count (filter #(= "tsukuru-registry-seed" (:source/dataset %)) factory-tx))
     :tsukuru-seed-count (count (filter #(= "tsukuru-seed" (:source/dataset %)) factory-tx))
     :index-count (count index-tx)})))

;; ---------- MCP mode（常駐して JSON-RPC で答える） ----------
;;
;; 実測 2026-07-30: この面を 1 query するのに 37 秒かかっていた。内訳を測ると
;; **ロードが全部**で、ロード済みの db に対する 3-way join は 14〜33 ms だった。
;; つまり遅さの原因は query ではなくプロセスの寿命で、CLI が毎回 3,405 ファイルを
;; 読み直して 116,392 entity を transact していたことに尽きる。
;;
;; 最初は db をファイルにキャッシュしようとして捨てた: DataScript の
;; serializable/from-serializable はこの db を往復できない（属性値に JS object が
;; 入っており from_serializable が EDN reader で "No reader function for tag
;; object." を投げる）。キャッシュを repo 直下に置くと root の mtime が変わって
;; 指紋が自分を無効化する、という別の穴も踏んだ。正解はファイルではなく常駐だった。
;;
;; なので: 1 回ロードして持ち続け、以後は ms で答える。`initialize` と
;; `tools/list` は面をロードせずに即答し、最初に query 系 tool が呼ばれた時点で
;; 初めてロードする（client が握手で 50 秒待たされない）。
;;
;; kernel（kotoba-lang/org-anthropic-mcp）を使っていないのは意図的で、
;; cloud-itonami-app の MCP server では使っている。あちらは server が独立した
;; 名前空間なので素直に require できるが、この CLI に持ち込むと (a) client の
;; 設定に --classpath を 1 本増やし、(b) SCI は defn の評価時に alias を解決する
;; ため条件付き require が効かず、MCP 実装全体を `when` の中に入れ子にする必要が
;; ある。30 行を節約するために CLI 全体の起動要件を変えるのは釣り合わない。

(def ^:private mcp-tools
  [{:name "plane_query"
    :description
    (str "Run a Datalog query against the unified EDN plane: "
         "116k entities across 26 datasets — ADRs, business metrics, fleet state, "
         "SEC EDGAR financials, legal entities, ToS archives, patents, passive DNS, "
         "manufacturer/factory registries. "
         "Attributes are BARE STRINGS, not keywords (\"company/lei\", not "
         ":company/lei). Datasets are distinguished by \"source/dataset\" — and for "
         "the tsukuru factory registries that tag is load-bearing: "
         "\"tsukuru-candidates\" companies have NOT consented to or been onboarded "
         "onto the platform (public-directory research only), while "
         "\"tsukuru-registry-seed\" and \"tsukuru-seed\" are illustrative seeds. "
         "Never report them as registered suppliers. "
         "Example: [:find ?id :where [?e \"adr/id\" ?id] [?e \"adr/status\" \"accepted\"]]")
    :input-schema {:type "object"
                   :properties {"query" {:type "string"
                                         :description "Datalog query as EDN text."}}
                   :required ["query"]}}
   {:name "company_profile"
    :description
    (str "Everything the plane knows about a company, joined on :company/lei — "
         "SEC EDGAR revenue, legal name, jurisdiction, and Terms-of-Service URL. "
         "Takes an LEI or part of a legal name. This is the join no single dataset "
         "can answer: financials, legal identity and ToS sit in three different "
         "repositories.")
    :input-schema {:type "object"
                   :properties {"company" {:type "string"
                                           :description "LEI, or a substring of the legal name (case-insensitive)."}}
                   :required ["company"]}}
   {:name "dataset_counts"
    :description "Entity count per dataset in the plane, and the total."
    :input-schema {:type "object" :properties {}}}])

;; ---------- 面のキャッシュと 30 秒予算 ----------
;;
;; 実測 2026-08-25、load 18〜23 のとき:
;;
;;   面の構築            298,795 ms   ← 所要時間のほぼ全部
;;   join 1 本               267 ms
;;   稀な属性を数える          7 ms
;;
;; join は全体の 0.09% で、遅かったのは「1 クエリ 1 プロセスで毎回 166,311
;; entity を読み直していた」こと。キャッシュを挟むと最初の答えまで 7.6 秒
;; (read 1.4 + JSON.parse 3.4 + from_serializable 2.3 + query 0.15)。
;;
;; **冷たい面に対しては答えを出さずに拒否する。** 5 分待たせると、待たされた側は
;; 「重いクエリを書いてしまった」と誤解する —— 実際にはクエリは 1 秒もかかって
;; いない。拒否すれば原因が名指しで出る。

(def ^:private budget-ms 30000)

;; この面をロードするのに要る classpath。**`.` と `scripts/nbb_compat` だけでは
;; 足りない** —— datalog backend（`datalog.core`）は west 管理の
;; `orgs/kotoba-lang/datalog` に在り、それ自身が `datom.source`
;; （`orgs/kotoba-lang/datom-source`）を require する。
;;
;; 1 箇所に置いているのは、**このツールが印字する指示は実行できなければならない**
;; から。実測 2026-09-06: 下の refuse-cold! は 2 項目だけの classpath を印字して
;; いて、言われたとおり貼り付けると `Could not find namespace: datalog.core` で
;; exit 1 —— 元のエラーが別のエラーに変わるだけで、そこから先の道が無かった。
;; 印字する側とロードする側が同じ値を読んでいれば、この形の食い違いは起きない。
(def ^:private classpath
  ".:scripts/nbb_compat:orgs/kotoba-lang/datalog/src:orgs/kotoba-lang/datom-source/src")

;; refresh を実際に通す 1 行。**heap の指定はこのコマンドの一部であって、回避策では
;; ない。** 面を組む途中の working set が node の既定 old-space を超えるからで、
;; 実測 2026-09-06、既定 heap の refresh は 86 秒・4.07 GB で OOM した —— shard を
;; 1 枚も書く前、まだ build-conn の中である（.projection-cache は空のままだった）。
;;
;; これは shard 化が直した欠陥とは別のものである。両方を測って区別してある:
;; heap を上げても一枚岩は書けず（`Invalid string length` に変わるだけ）、
;; 一枚岩をやめても面は既定 heap に収まらない。片方だけ直しても refresh は通らない。
;;
;; 8192 は「面が組めた」ことを実測した値であって、面の大きさの上界ではない。
;; いつか足りなくなるが、そのとき出るのは上の OOM なので、読めば分かる。
(def ^:private refresh-command
  (str "NODE_OPTIONS=--max-old-space-size=8192 nbb --classpath \"" classpath
       "\" manifest/edn-query.cljs refresh"))

(def ^:private cache-dir (io/file root ".projection-cache"))
(def ^:private cache-edn (io/file cache-dir "edn-query-plane.edn"))
(def ^:private cache-meta (io/file cache-dir "edn-query-plane.meta.edn"))
(def ^:private views-file (io/file cache-dir "edn-query-views.edn"))
(def ^:private views-spec (io/file root "manifest" "plane-views.edn"))

(defn- sha256-hex [x]
  (-> (node-crypto/createHash "sha256") (.update x) (.digest "hex")))

(defn- stat-of [path]
  (try (let [st (node-fs/statSync path)]
         [(str path) (.-size st) (js/Math.round (.-mtimeMs st))])
       (catch :default _ [(str path) -1 -1])))

(defn- dir-digest [dir]
  (try (sha256-hex (str/join " " (sort (js->clj (node-fs/readdirSync dir)))))
       (catch :default _ "missing")))

(defn- inputs-snapshot
  "ビルドが読んだファイル (slurp が記録) と、その親・祖父ディレクトリのエントリ名
   digest。ファイルだけでは『ディレクトリに新しいファイルが増えた』が見えない。

   宣言した root を walk する方式は採れない —— この面が読むのは
   orgs/cloud-itonami の下の 185 repo だが、その親には 1,852 repo が居るので
   walk すると数百万ファイルを stat することになる。読んだものだけを記録する。"
  []
  (let [files (sort @reads-seen)
        dirs (->> files
                  (mapcat (fn [f]
                            (let [parent (.getParent (io/file f))]
                              [parent (some-> parent io/file .getParent)])))
                  (remove nil?)
                  distinct sort vec)]
    {:files (mapv stat-of files)
     :dirs (mapv (fn [d] [d (dir-digest d)]) dirs)}))

(defn- snapshot-digest [{:keys [files dirs]}]
  (sha256-hex (pr-str [(vec files) (vec dirs)])))

(defn- current-digest
  "meta が記録した *同じ* 集合を測り直す。ビルドせずに鮮度を判定できるのは
   この記録があるからで、記録に無いものが増えた場合は dirs 側で捕まる。"
  [{:keys [files dirs]}]
  (snapshot-digest {:files (mapv (fn [[f _ _]] (stat-of f)) files)
                    :dirs (mapv (fn [[d _]] [d (dir-digest d)]) dirs)}))

(defn- read-meta []
  (try (when (.exists cache-meta) (slurp-edn cache-meta))
       (catch :default _ nil)))

(def ^:private shards-dir (io/file cache-dir "shards"))
(def ^:private shards-index (io/file cache-dir "edn-query-shards.edn"))

(def ^:private no-dataset-shard
  "`source/dataset` を持たない entity を入れる shard の名前。

   dataset で切るだけでは面の一部しか書けない。ADR entity が持つのは
   `:adr/*` と `:source/file` だけで、`:source/dataset` は付かない
   （`adr-entities-from-file` を見ればわかる）—— docs / manifest / foreign-adr も
   同じ。**`entity->dataset` が nil を返すこれらを keep が黙って落としていた**
   ので、shard は今日まで面の写しではなく面の一部だった。

   `_` で始めているのは `source/dataset` の実値と衝突しないため。`shardable` は
   query に literal で書かれた dataset 名しか要求しないので、この shard が
   dataset 指定の速い経路に混ざることはない。"
  "_no-dataset")

(defn- cache-state
  "[:fresh|:stale|:absent meta]。**読めなかったことを fresh と区別する** ——
   meta が壊れていれば :absent であって :fresh ではない。

   面の実体は shard 群なので、在るかどうかを訊く先は shard の索引。"
  []
  (let [m (read-meta)]
    (cond
      (or (nil? m) (not (.exists shards-index))) [:absent nil]
      (= (:digest m) (current-digest m)) [:fresh m]
      :else [:stale m])))

(defn- serialize-shard
  "shard 1 枚を文字列にする。V8 の 1 文字列の上限に当たったら、**どの shard で
   当たったか**を名指して落ちる。

   一枚岩をやめてもこの上限は消えていない。消えたのは「今の面が 1 枚に収まらない」
   という事実だけで、上限そのものは据え置きである。いつか 1 つの dataset が単独で
   届く日が来るので、そのとき欲しいのは次に割る単位を名指した 1 行であって、
   どこで何が起きたか書いていない RangeError のスタックではない。"
  [dsname sdb]
  (try
    (dlq/serialize-db sdb)
    (catch :default e
      (throw (ex-info
              (str "edn-query: shard \"" dsname "\" does not fit in one JavaScript "
                   "string (" (.-message e) "). Split this dataset into parts -- "
                   "raising the heap will not help, the limit is per string, "
                   "not per process.")
              {:shard dsname})))))

(defn- write-shards!
  "面を dataset ごとの shard に分けて書く。**これがキャッシュの本体**である。

   一枚岩をやめた理由は速さではなく、**書けなかったから**である。実測 2026-09-06、
   `write-cache!` の `pr-str` が `RangeError: Invalid string length` で落ちた。
   V8 の 1 文字列の上限（64bit で約 5.4 億文字）は heap の設定ではないので、
   `--max-old-space-size` を上げても**落ち方が変わるだけ**だった。同じ日に測った
   最大の shard は `_no-dataset` の 204,562,760 バイトで、上限の 4 割弱に収まる。

   これは heap が足りない話とは**別**である（`refresh-command` の注記を見よ）。
   混ぜると、heap を上げて直ったつもりになるか、割ったから heap が要らなくなった
   つもりになる —— どちらでも refresh は通らない。

   割り方が dataset なのは、`shardable` / `load-shards!` が既にこの単位で
   読んでいるから —— 新しい機構を足さずに、既にある機構を全体にも使う。"
  [db]
  (when-not (.exists shards-dir)
    (node-fs/mkdirSync (.getPath shards-dir) (clj->js {:recursive true})))
  ;; どの entity がどの shard かは 1 度だけ決める。dataset ごとに面を走査し直すと
  ;; 102 shard × 480,253 entity になり、shard を書く時間が読む時間から離れていく。
  (let [by-shard (reduce (fn [m eid]
                           (let [k (or (dlq/entity->dataset db eid) no-dataset-shard)]
                             (assoc m k (conj (get m k []) eid))))
                         {}
                         (keys (:eavt db)))
        idx (doall
             (for [dsname (sort (keys by-shard))
                   :let [sdb (dlq/subset-db-eids db (get by-shard dsname))
                         content (serialize-shard dsname sdb)
                         f (io/file shards-dir
                                    (str (str/replace (str dsname) #"[^A-Za-z0-9_.-]" "_")
                                         ".edn"))]]
               (do
                 (node-fs/writeFileSync (.getPath f) content)
                 [(str dsname) {:file (.getName f)
                                :datoms (dlq/datom-count sdb)
                                :bytes (count content)}])))]
    ;; ファイル名は dataset 名の英数字以外を `_` に潰して作るので、`a/b` と `a_b` は
    ;; 同じファイルになる。索引は 2 エントリのまま同じファイルを指し、後に書いた方が
    ;; 前を上書きするので、**片方の dataset は索引に載ったまま中身だけ消える** ——
    ;; 数え直しても shard の枚数は合う。数が合う消え方なので、ここで名指しして止める。
    (when-not (= (count idx) (count (into #{} (map (fn [[_ v]] (:file v)) idx))))
      (throw (ex-info (str "edn-query: two datasets sanitise to the same shard file "
                           "-- refusing to write a cache that would silently drop one.")
                      {:files (map (fn [[k v]] [k (:file v)]) idx)})))
    ;; 組んだ datom が全部どれかの shard に入ったことを、書いた直後に数えて確かめる。
    ;;
    ;; **この 1 行が無かったせいで、shard は面の一部のまま気づかれずに居た。**
    ;; `source/dataset` を持たない entity（ADR 全部）を `keep` が落としていて、
    ;; それでも shard は書けて索引も揃い、枚数も dataset の数と一致していた ——
    ;; 足りないことを言うものが、どこにも無かった。数が合う欠け方だったので、
    ;; 出力を読んでも分からない。数える側を置く以外に見つけようが無い。
    ;;
    ;; 実測 2026-09-06、落とす側の実装にこの検査を当てると
    ;; `built 6930504 datoms but wrote 6086157 across 101 shards` で止まる ——
    ;; 面の 12.2% が、索引の揃った「完成した」キャッシュから消えていた。
    (let [built (dlq/datom-count db)
          written (reduce + 0 (map (fn [[_ v]] (:datoms v)) idx))]
      (when-not (= built written)
        (throw (ex-info (str "edn-query: built " built " datoms but wrote " written
                             " across " (count idx) " shards -- refusing to publish a "
                             "cache that is not the plane.")
                        {:built built :written written}))))
    (node-fs/writeFileSync (.getPath shards-index)
                           (pr-str {:built-at (.toISOString (js/Date.))
                                    :shards (into {} idx)}))
    {:shards (count idx)
     :datoms (reduce + 0 (map (fn [[_ v]] (:datoms v)) idx))
     :bytes (reduce + 0 (map (fn [[_ v]] (:bytes v)) idx))}))

(defn- shardable
  "クエリが触りうる dataset を静的に決められるか。

   決められるのは **すべての entity 変数が `source/dataset` のリテラル節で
   縛られているとき**だけ。そのときに限り、名指された shard だけを読んだ答えは
   全体を読んだ答えと一致する（entity は必ずどれかの dataset に属するので、
   名指されていない dataset の entity は解に現れ得ない）。

   1 つでも縛られていない entity 変数があれば全体を読む —— **速さのために
   答えを変えない**。:in / rules がある query も全体に落とす（束縛が実行時に
   決まるので静的に言えない）。

   戻り値: [:shards #{...}] か [:full <理由>]。"
  [qs]
  (try
    (let [q (edn/read-string qs)]
      (if-not (vector? q)
        [:full "query is not a vector"]
        (let [has-in? (some #{:in} q)
              where (->> q (drop-while (complement #{:where})) rest
                         (take-while (complement keyword?)))
              clauses (filter vector? where)
              evar? (fn [x] (and (symbol? x) (str/starts-with? (str x) "?")))
              evars (set (keep (fn [c] (let [x (first c)] (when (evar? x) x))) clauses))
              bound (into {} (keep (fn [c]
                                     (when (and (>= (count c) 3)
                                                (= "source/dataset" (nth c 1))
                                                (string? (nth c 2))
                                                (evar? (first c)))
                                       [(first c) (nth c 2)]))
                                   clauses))
              unbound (remove bound evars)]
          (cond
            has-in? [:full ":in present — bindings are decided at run time"]
            (empty? evars) [:full "no entity clause"]
            (seq unbound) [:full (str "entity var(s) not pinned to a dataset: "
                                      (str/join " " unbound))]
            :else [:shards (set (vals bound))]))))
    (catch :default e [:full (str "unparseable: " e)])))

(defn- load-shards!
  [datasets]
  (let [idx (:shards (slurp-edn shards-index))
        files (map (fn [d] [d (get idx d)]) datasets)]
    (when-not (some (fn [[_ v]] (nil? v)) files)
      (dlq/merge-dbs
       (mapv (fn [[_ {:keys [file]}]]
               (dlq/deserialize-db
                (node-fs/readFileSync (.getPath (io/file shards-dir file)) "utf8")))
             files)))))

(defn- write-cache!
  "鮮度の指紋と counts を書く。**面そのものはもうここには無い** —— shard が
   持っている（write-shards!）ので、ここが書くのは meta だけ。

   meta を最後に書くのは、これが「面が揃った」ことの印だからである。shard の
   途中で落ちた refresh が meta を残すと、cache-state は揃っていない面を fresh と
   答える —— 半分の面に対する query は、少ない行数を正しい答えの顔で返す。"
  [counts sharded]
  (when-not (.exists cache-dir)
    (node-fs/mkdirSync (.getPath cache-dir) (clj->js {:recursive true})))
  ;; 旧レイアウトの一枚岩が残っていたら捨てる。もう誰も読まないので、置いたままに
  ;; すると数百 MB の死んだファイルが黙って残り続けるだけになる。
  (when (.exists cache-edn)
    (node-fs/unlinkSync (.getPath cache-edn)))
  (let [snap (inputs-snapshot)]
    (node-fs/writeFileSync (.getPath cache-meta)
                           (pr-str (assoc snap
                                          :digest (snapshot-digest snap)
                                          :built-at (.toISOString (js/Date.))
                                          :counts counts
                                          :shards (:shards sharded)
                                          :bytes (:bytes sharded))))
    {:bytes (:bytes sharded) :files (count (:files snap)) :dirs (count (:dirs snap))}))

(defn- load-cached-db
  "面全体を shard から組み直す。

   1 つずつ読んで足すのは、`(mapv deserialize ...)` だと全 shard の db が同時に
   生きたまま merge 先も作ることになり、面を 2 つ分抱えるから。ここでは生きて
   いるのは累積器と読んでいる 1 枚だけで、読み終えた shard はその場でゴミになる。

   3 索引のうち載せ直す側が読むのは `:eavt` だけ（`dlq/db-quads`）。残る
   `:aevt` / `:avet` は同じ datom の並べ替えなので、merge が assert し直して作る。"
  []
  (let [idx (:shards (slurp-edn shards-index))]
    (dlq/merge-finish
     (reduce (fn [acc [_ {:keys [file]}]]
               (dlq/merge-add!
                acc
                (dlq/deserialize-db
                 (node-fs/readFileSync (.getPath (io/file shards-dir file)) "utf8"))))
             (dlq/merge-start)
             (sort-by key idx)))))

(defn- allow-cold? []
  (= "1" (.-EDN_QUERY_ALLOW_COLD (.-env js/process))))

(defn- refuse-cold! [state]
  (js/console.error
   (str "edn-query: the plane is " (name state) " -- refusing to answer.\n"
        "  Building it inline takes ~5 minutes, 10x this tool's 30s budget, and the\n"
        "  query is not what is slow (a join measures ~0.3s once the plane is warm).\n"
        "  Refresh the cache first (the heap flag is part of the command --\n"
        "  building the plane does not fit in node's default old-space):\n"
        "    " refresh-command "\n"
        ;; ここも「貼れば動く」でなければならない。inline build も同じ面を組むので
        ;; 同じ heap が要る —— 書き忘れると、拒否を回避した人が今度は OOM に当たる。
        "  Or set EDN_QUERY_ALLOW_COLD=1 (with the same heap flag) to build inline."))
  (scripts.nbb-compat/exit 4))

(defn- plane-db! []
  (let [[state m] (cache-state)]
    (if (= :fresh state)
      (let [t (js/Date.now)
            db (load-cached-db)]
        (js/console.error (str "edn-query: plane from cache in " (- (js/Date.now) t)
                               "ms (built " (:built-at m) ")"))
        {:db db :counts (:counts m) :source :cache})
      (if (allow-cold?)
        (let [t (js/Date.now)
              built (build-conn)]
          (js/console.error (str "edn-query: plane built in " (- (js/Date.now) t) "ms (cold)"))
          {:db (:db built) :counts (dissoc built :db) :source :build})
        (refuse-cold! state)))))

(defn- timed-q
  [db qs]
  (let [t (js/Date.now)
        r (dlq/q db qs)
        ms (- (js/Date.now) t)]
    (js/console.error (str "edn-query: query " ms "ms"))
    (when (> ms budget-ms)
      (js/console.error (str "edn-query: that query took " ms "ms, over the " budget-ms
                             "ms budget -- reporting it rather than passing it off as fine."))
      (scripts.nbb-compat/exit 4))
    r))

(defn- run-refresh! []
  (let [t0 (js/Date.now)
        built (build-conn)
        db (:db built)
        t-build (- (js/Date.now) t0)
        counts (dissoc built :db)
        ;; shard を先に、meta を最後に。順が逆だと、shard の途中で落ちた refresh が
        ;; 「揃っている」と名乗る meta を残す（write-cache! の docstring）。
        sharded (write-shards! db)
        written (write-cache! counts sharded)
        views (when (.exists views-spec)
                (into {} (map (fn [[k qs]]
                                (let [t (js/Date.now)
                                      rows (timed-q db qs)]
                                  (js/console.error (str "  view " k " " (- (js/Date.now) t)
                                                         "ms rows=" (count rows)))
                                  [k {:query qs :rows rows}]))
                              (slurp-edn views-spec))))]
    (when views
      (node-fs/writeFileSync (.getPath views-file)
                             (pr-str {:built-at (.toISOString (js/Date.)) :views views})))
    (println (str "REFRESHED\tbuild_ms=" t-build
                  " inputs_files=" (:files written)
                  " inputs_dirs=" (:dirs written)
                  " shards=" (:shards sharded)
                  " datoms=" (:datoms sharded)
                  " shard_bytes=" (:bytes sharded)
                  " views=" (count (or views {}))))))

(defn- run-view! [nm]
  (if-not (.exists views-file)
    (do (js/console.error "edn-query: no materialised views -- run refresh first")
        (scripts.nbb-compat/exit 4))
    (let [{:keys [views built-at]} (slurp-edn views-file)
          v (or (get views (keyword nm)) (get views nm))]
      (if v
        (do (js/console.error (str "edn-query: view from " built-at))
            (println (pr-str (:rows v))))
        (do (js/console.error (str "edn-query: no such view: " nm
                                   " (have: "
                                   (str/join ", " (map #(if (keyword? %) (name %) (str %))
                                                       (keys views)))
                                   ")"))
            (scripts.nbb-compat/exit 4))))))

(def ^:private plane (atom nil))

(defn- plane!
  "面を 1 回だけ組む。ロードは stderr に報告する（client 側で最初の呼び出しが
   数十秒かかる理由が見えるように）。

   キャッシュが新しければそれを使う —— mcp の最初の呼び出しが 5 分から 8 秒に
   なる。古い/無い場合はここでは拒否せずに組む: mcp サーバは長命で、client は
   既に接続してしまっているので、そこで exit 4 すると『答えられない server』に
   なる。拒否が正しいのは 1 発で終わる CLI の側。"
  []
  (or @plane
      (let [t0 (js/Date.now)
            [state m] (cache-state)]
        (if (= :fresh state)
          (let [built (assoc (:counts m) :db (load-cached-db))]
            (js/console.error (str "edn-query/mcp: plane from cache in "
                                   (- (js/Date.now) t0) "ms (built " (:built-at m) ")"))
            (reset! plane built))
          (do (js/console.error
               (str "edn-query/mcp: cache is " (name state)
                    " — building the plane (first query only, minutes). "
                    "To make this seconds, run: " refresh-command))
              (let [built (build-conn)]
                (js/console.error (str "edn-query/mcp: plane ready in "
                                       (quot (- (js/Date.now) t0) 1000) "s"))
                (reset! plane built)))))))

(defn- q* [query]
  (timed-q (:db (plane!)) query))

(defn- pairs->index
  "[[k v] …] -> {k v}。同じ k が複数あれば最初を採る。"
  [rows]
  (reduce (fn [m [k v]] (if (contains? m k) m (assoc m k v))) {} rows))

(defn- company-profile [needle]
  (let [needle (str/lower-case (str needle))
        names (q* "[:find ?lei ?legal :where [?b \"company/lei\" ?lei] [?b \"company/legal-name\" ?legal]]")
        revenue (pairs->index
                 (q* (str "[:find ?lei ?rev :where [?a \"company/lei\" ?lei] "
                          "[?a \"source/dataset\" \"market-intel\"] [?a \"company/revenue-usd\" ?rev]]")))
        juris (pairs->index
               (q* "[:find ?lei ?j :where [?b \"company/lei\" ?lei] [?b \"company/jurisdiction\" ?j]]"))
        tos (pairs->index
             (q* "[:find ?lei ?u :where [?c \"company/lei\" ?lei] [?c \"tos/source-url\" ?u]]"))
        hits (->> names
                  (filter (fn [[lei legal]]
                            (or (= (str/lower-case (str lei)) needle)
                                (str/includes? (str/lower-case (str legal)) needle))))
                  (sort-by second))]
    {:matched (count hits)
     :companies (mapv (fn [[lei legal]]
                        (cond-> {:lei lei :legal-name legal}
                          (get juris lei) (assoc :jurisdiction (get juris lei))
                          (get revenue lei) (assoc :revenue-usd (get revenue lei))
                          (get tos lei) (assoc :tos-url (get tos lei))))
                      hits)}))

(defn- counts-summary []
  (let [p (plane!)]
    (-> (dissoc p :db)
        (assoc :total (reduce + (vals (dissoc p :db)))))))

(defn- mcp-invoke [tool-name args]
  (try
    (case tool-name
      "plane_query"
      (let [query (get args "query")]
        (if (str/blank? (str query))
          {:error "query is required"}
          {:rows (q* query)}))

      "company_profile"
      (let [c (get args "company")]
        (if (str/blank? (str c))
          {:error "company is required"}
          (company-profile c)))

      "dataset_counts" (counts-summary)

      {:error (str "unknown tool: " tool-name)})
    (catch :default e
      ;; tool の失敗で常駐プロセスを落とさない。落ちると以後の全 query が死ぬ。
      {:error (str (.-message e))})))

(defn- mcp-response [id result]
  (clj->js {"jsonrpc" "2.0" "id" id "result" result}))

(defn- mcp-handle [req]
  (let [method (get req "method")
        id (get req "id")
        params (get req "params" {})]
    (case method
      "initialize"
      (mcp-response id {"serverInfo" {"name" "kotoba-query-plane" "version" "1"}
                        "capabilities" {"tools" {}}})

      "tools/list"
      (mcp-response
       id {"tools" (mapv (fn [t]
                           {"name" (:name t)
                            "description" (:description t)
                            "inputSchema" (:input-schema t)})
                         mcp-tools)})

      "tools/call"
      (let [tool-name (get params "name")
            args (get params "arguments" {})
            known (some #(when (= tool-name (:name %)) %) mcp-tools)]
        (if-not known
          (clj->js {"jsonrpc" "2.0" "id" id
                    "error" {"code" -32601 "message" (str "tool not found: " tool-name)}})
          (let [required (get-in known [:input-schema :required] [])
                missing (remove #(contains? args %) required)]
            (if (seq missing)
              (clj->js {"jsonrpc" "2.0" "id" id
                        "error" {"code" -32602
                                 "message" (str "missing required param: " (first missing))}})
              (let [result (mcp-invoke tool-name args)]
                (mcp-response
                 id {"content" [{"type" "text" "text" (pr-str result)}]
                     "isError" (contains? result :error)
                     "structuredContent" (clj->js result)}))))))

      (clj->js {"jsonrpc" "2.0" "id" id
                "error" {"code" -32601 "message" (str "Method not found: " method)}}))))

(defn- mcp-serve!
  "newline-delimited JSON-RPC on stdin/stdout。id の無い notification には
   返さない（spec 上禁止で、client は握手直後に notifications/initialized を
   送ってくる）。"
  []
  (let [buf (atom "")
        emit! (fn [v] (.write js/process.stdout (str (js/JSON.stringify v) "\n")))]
    (.setEncoding js/process.stdin "utf8")
    (.on js/process.stdin "data"
         (fn [chunk]
           (swap! buf str chunk)
           (loop []
             (let [s @buf
                   i (.indexOf s "\n")]
               (when (>= i 0)
                 (let [line (str/trim (subs s 0 i))]
                   (reset! buf (subs s (inc i)))
                   (when-not (str/blank? line)
                     (let [req (try (js->clj (js/JSON.parse line) :keywordize-keys false)
                                    (catch :default _ nil))]
                       (cond
                         (nil? req)
                         (emit! (clj->js {"jsonrpc" "2.0" "id" nil
                                          "error" {"code" -32700 "message" "parse error"}}))
                         (contains? req "id") (emit! (mcp-handle req))
                         :else nil)))
                   (recur)))))))
    (.on js/process.stdin "end" (fn [] (js/process.exit 0)))))


(defn- counts-line [c]
  (let [g (fn [k] (get c k 0))
        total (reduce + 0 (map g [:adr-count :docs-count :manifest-count :foreign-adr-count
                                  :biz-count :kj-count :rad-count :etzhayyim-80-data-count
                                  :proc-registry-count :merged-kotoba-count :working-doc-count
                                  :narrative-count :company-count :fleet-count :yabai-count
                                  :tadori-count :patent-count :innen-count :awai-yakuwari-count
                                  :tsukuru-candidates-count :tsukuru-registry-seed-count
                                  :tsukuru-seed-count]))]
    (str "adr=" (g :adr-count) " docs=" (g :docs-count) " manifest=" (g :manifest-count)
         " foreign-adr=" (g :foreign-adr-count) " biz=" (g :biz-count) " kj=" (g :kj-count)
         " rad=" (g :rad-count) " etzhayyim-80-data=" (g :etzhayyim-80-data-count)
         " proc-registry=" (g :proc-registry-count) " merged-kotoba=" (g :merged-kotoba-count)
         " working-doc=" (g :working-doc-count) " narrative=" (g :narrative-count)
         " company=" (g :company-count) " fleet=" (g :fleet-count) " yabai=" (g :yabai-count)
         " tadori=" (g :tadori-count) " patent=" (g :patent-count) " innen=" (g :innen-count)
         " awai-yakuwari=" (g :awai-yakuwari-count)
         " tsukuru-candidates=" (g :tsukuru-candidates-count)
         " tsukuru-registry-seed=" (g :tsukuru-registry-seed-count)
         " tsukuru-seed=" (g :tsukuru-seed-count)
         " total=" total)))

(defn- run-cli [mode queries]
  (case mode
    "refresh" (run-refresh!)
    "view" (run-view! (first queries))
    ("count" "q" "q*")
    (let [{:keys [db counts]} (if (and (= mode "q")
                                       (.exists shards-index)
                                       ;; baseline を測るときに shard 経路へ落ちないようにする
                                       ;; スイッチ。これが無いと「全体ロードを測った」と
                                       ;; 言いながら shard を測ることになる
                                       (not= "1" (.-EDN_QUERY_NO_SHARD (.-env js/process))))
                                (let [[kind arg] (shardable (first queries))]
                                  (if (= kind :shards)
                                    (let [[state m] (cache-state)]
                                      (if (= :fresh state)
                                        (let [t (js/Date.now)
                                              sdb (load-shards! arg)]
                                          (if sdb
                                            (do (js/console.error
                                                 (str "edn-query: " (count arg) " shard(s) in "
                                                      (- (js/Date.now) t) "ms — "
                                                      (str/join " " (sort arg))))
                                                {:db sdb :counts (:counts m)})
                                            (plane-db!)))
                                        (plane-db!)))
                                    (do (js/console.error (str "edn-query: full plane — " arg))
                                        (plane-db!))))
                                (plane-db!))]
      (case mode
        "count" (println (counts-line counts))
        "q" (println (pr-str (timed-q db (first queries))))
        "q*" (println (pr-str (mapv (fn [x] (timed-q db x)) queries)))))

    (do (println (str "usage: nbb --classpath \"" classpath "\" manifest/edn-query.cljs "
                      "[refresh | count | q '<datalog-query>' | q* '<q1>' '<q2>' ... "
                      "| view <name> | mcp]"))
        (scripts.nbb-compat/exit 1))))

(defn -main [& args]
  (let [[mode & queries] args]
    ;; mcp モードは面をここでは組まない。握手には面が要らないので、client を
    ;; 数十秒待たせずに initialize / tools/list を返し、最初の query 系 tool 呼び出しで
    ;; 初めてロードする（plane! が 1 回だけ組む）。
    (if (= "mcp" mode)
      (mcp-serve!)
      (run-cli mode (vec queries)))))

(apply -main *command-line-args*)

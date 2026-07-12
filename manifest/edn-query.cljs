#!/usr/bin/env nbb
;; manifest/edn-query.cljs — ADR ledger + RAD identity journal を実 DataScript
;; (npm `datascript` パッケージ) にロードして query するツール。
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
;; 属性の型変換について: npm `datascript` パッケージは datascript.js 名前空間
;; （JS 向けインターフェース）をそのまま公開しており、属性は keyword ではなく
;; **裸の文字列**（例 "adr/id"、コロン無し）として扱う。:db/id だけ特別扱いで
;; コロン付き文字列キー ":db/id"。このため元の edn の keyword 値もこのローダでは
;; 文字列化する（"was keyword" という型情報は失われる — MVP の既知の制約。
;; 必要になったら別属性に :value/type "keyword" のようなマーカーを足す）。
;;
;; 使い方:
;;   nbb manifest/edn-query.cljs count
;;   nbb manifest/edn-query.cljs q '[:find ?id ?status :where
;;                                   [?e "adr/id" ?id] [?e "adr/status" ?status]]'

(require '[scripts.nbb-compat :refer [slurp file-seq format]]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str]
         '["datascript" :as ds-mod])

(def ds (.-default ds-mod))

(def root (str/trim (:out (shell/sh "git" "rev-parse" "--show-toplevel"))))

(defn slurp-edn
  "未知の reader タグ（例 #md \"...\"）はタグを捨てて値だけ返すフォールバックを
   常に有効にする（さもないと clojure.edn/read-string がタグ未知で例外を投げ、
   呼び出し側の try/catch で丸ごとスキップされてしまう — 各カテゴリで
   個別にタグを解釈するより、ここで一箇所吸収する方が安全）。"
  [path]
  (edn/read-string {:default (fn [_tag v] v)} (slurp path)))

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

(defn adr-files []
  (->> (file-seq (io/file root "90-docs" "adr"))
       (filter #(str/ends-with? (str %) ".edn"))
       (sort-by str)))

(def adr-entity wrapped-entity)

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
         (filter #(str/ends-with? (str %) ".edn"))
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
      (->> (file-seq dir) (filter #(str/ends-with? (str %) ".edn")) (sort-by str))
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

;; ---------- orgs/kawasakijun/*.edn（個人ライフトラッキング。category H） ----------

(defn kawasakijun-files []
  (let [dir (io/file root "orgs" "kawasakijun")]
    (if (.exists dir)
      (->> (file-seq dir) (filter #(str/ends-with? (str %) ".edn")) (sort-by str))
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

;; ---------- schema (manifest/schema.edn -> datascript createConn schema) ----------

(defn schema-path [] (io/file root "manifest" "schema.edn"))

(defn ds-schema []
  (let [attrs (if (.exists (schema-path)) (slurp-edn (schema-path)) [])
        obj (js-obj)]
    (doseq [attr attrs]
      (when (= (:db/cardinality attr) :db.cardinality/many)
        (aset obj (kw->attr (:db/ident attr)) (js-obj ":db/cardinality" ":db.cardinality/many"))))
    (aset obj "rad/cid" (js-obj ":db/unique" ":db.unique/identity"))
    obj))

;; ---------- build + query ----------

(defn build-conn []
  (let [conn (.create_conn ds (ds-schema))
        tempid (atom 0)
        next-tempid! (fn [] (swap! tempid dec))
        adr-tx (keep (fn [f]
                        (when-let [e (adr-entity f)]
                          (assoc e :db/id (next-tempid!) :source/file (str f))))
                      (adr-files))
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
        all-tx (into-array (map entity->js (concat adr-tx manifest-tx foreign-adr-tx
                                                     biz-tx canvas-tx kj-tx rad-tx
                                                     journal-tx genome-tx datoms-tx
                                                     hirameki-corpus-tx jinushi-tx
                                                     proc-registry-tx merged-kotoba-tx
                                                     working-doc-tx narrative-tx)))]
    (.transact ds conn all-tx)
    {:conn conn
     :adr-count (count adr-tx)
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
     :narrative-count (count narrative-tx)}))

(defn -main [& args]
  (let [[mode query-str] args
        {:keys [conn adr-count manifest-count foreign-adr-count biz-count kj-count rad-count
                etzhayyim-80-data-count proc-registry-count merged-kotoba-count working-doc-count
                narrative-count]}
        (build-conn)
        total (+ adr-count manifest-count foreign-adr-count biz-count kj-count rad-count
                 etzhayyim-80-data-count proc-registry-count merged-kotoba-count working-doc-count
                 narrative-count)]
    (case mode
      "count"
      (println (format (str "adr=%s manifest=%s foreign-adr=%s biz=%s kj=%s rad=%s "
                             "etzhayyim-80-data=%s proc-registry=%s merged-kotoba=%s working-doc=%s "
                             "narrative=%s total=%s")
                        adr-count manifest-count foreign-adr-count biz-count kj-count rad-count
                        etzhayyim-80-data-count proc-registry-count merged-kotoba-count working-doc-count
                        narrative-count total))

      "q"
      (println (pr-str (js->clj (.q ds query-str (.db ds conn)))))

      (do (println "usage: nbb manifest/edn-query.cljs [count | q '<datalog-query>']")
          (scripts.nbb-compat/exit 1)))))

(apply -main *command-line-args*)

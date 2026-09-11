#!/usr/bin/env nbb
;; legislation-corpus-check.cljs — etzhayyim/global-legislation-datoms の gate。
;;
;; ノード側で `npx nbb legislation-corpus-check.cljs <repo-dir>` として走る
;; （tick.cljs が heredoc で配って呼ぶ）。**JVM を要求しない。**
;;
;; なぜ JVM gate ではないか（実測 2026-08-05、推測ではない）:
;; この repo の contract test は Datascript を要求し、Datascript は maven 依存。
;; fleet のノードは tailnet だけに繋がっていて外向き HTTPS が無く、
;; `~/.m2/repository/datascript/` は zebulun にも asher にも存在しない。
;; したがって :jvm-test は `clojure -M:test` の依存解決で必ず落ちる。
;; ここは Datalog エンジン抜きで成立する不変条件だけを検査する。
;;
;; 検査するもの:
;;   1. 宣言されたシャードが全部実在し、**sha256 とバイト数が manifest 通り**
;;   2. シャードの entity 件数が manifest 通り
;;   3. quality-report の閾値（corpus 件数の床、text-bytes-held-here = 0）
;;   4. JP シャードの意味検査（identity 一意・text address の形・辺の向き）
;;   5. corpus の :law/source-id が catalog の :legal-source/id に join でき、
;;      その行が :status/ingested + :legal-source/dataset を持つこと
;;
;; (1) がこの gate の主眼。GitHub Actions 版では**構造的に不可能だった**検査で、
;; 「内部整合を保ったまま手編集されたシャード」をここで捕まえられる。
;;
;; 大きいシャード（EU/UK）は全 parse しない。646,468 辺を edamame で読むと
;; 数分かかる一方、改竄検出は sha256 で十分で、意味検査は JP（9,536 法令 +
;; 7,793 辺）で代表させる。**この線引きは意図的で、下に何が検査されないかを
;; 明示する。**
(ns fleet-ci.gates.legislation-corpus-check
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:crypto" :as crypto]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

(def failures (atom []))
(defn fail! [& xs] (swap! failures conj (str/join " " (map str xs))))
(defn slurp* [p] (str (fs/readFileSync p "utf8")))
(defn read-edn [p] (reader/read-string (slurp* p)))
(defn exists? [p] (fs/existsSync p))
(defn sha256 [p]
  (-> (.createHash crypto "sha256") (.update (fs/readFileSync p)) (.digest "hex")))

(defn- die-early [msg code]
  (println (str "FLEET-CI: " msg))
  (js/process.exit code))

;; ---------------------------------------------------------------------------
;; 0. 展開の健全性。tarball の展開ミスで空ツリーを検査し「違反 0 件 = 合格」と
;;    報告する事故（ADR-2607178000 addendum）を構造的に防ぐ。
(def manifest-path (path/join root "data/corpus/manifest.edn"))
(def quality-path (path/join root "data/quality-report.edn"))
(def catalog-path (path/join root "data/datascript-tx.edn"))
(def schema-path (path/join root "schema/legislation.edn"))

(doseq [p [manifest-path quality-path catalog-path schema-path]]
  (when-not (exists? p)
    (die-early (str "missing " (path/relative root p)
                    " — extraction or :include-ext is wrong, refusing to report a pass")
               90)))

(def manifest (read-edn manifest-path))
(def quality (read-edn quality-path))
(def catalog (read-edn catalog-path))
(def schema (read-edn schema-path))
(def shards (:corpus/shards manifest))
(def sources (:corpus/sources manifest))

(when (< (count shards) 10)
  (die-early (str "only " (count shards) " shards declared — manifest looks truncated") 90))

;; ---------------------------------------------------------------------------
;; 1+2. 全シャードの実在・sha256・バイト数・entity 件数。
;;
;; entity 件数は full parse せず identity 属性の出現数で数える。これらのファイルは
;; bin/build-corpus.cljs が 1 entity 1 個の :law/key（または :law.rel/id）で書く
;; 生成物なので、出現数 == entity 数が成り立つ。壊れれば sha256 側で捕まる。
;;
;; 注意: pr-str は「全キーが同じ名前空間」の map を **namespaced map 構文**で書く。
;; relation entity は全部 :law.rel/* なので `#:law.rel{:id …}` になり、
;; リテラル `:law.rel/id` は現れない（実測: この形を見落として全 relation shard が
;; 0 件と数えられた）。law entity も**本文アドレスを持つかどうかで形が変わる**:
;; 持てば :law/* と :law.text/* が混ざるので素の map、持たなければ全キーが :law/* で
;; `#:law{…}` になる（実測: EU は 64,237 件中 1,114 件しか本文が無いので、素の map
;; だけ数えると 20,000 件のシャードが 180 件に見えた）。4 つの形すべてを数える。
;; `#:law{` は `#:law.rel{` に一致しない（リテラルの `{` が違いを作る）。
(def key-re (js/RegExp. ":law/key " "g"))
(def law-nsmap-re (js/RegExp. "#:law\\{" "g"))
(def rel-plain-re (js/RegExp. ":law\\.rel/id " "g"))
(def rel-nsmap-re (js/RegExp. "#:law\\.rel\\{" "g"))

(defn- count-re [s ^js re] (count (js/Array.from (.matchAll s re))))

(defn count-entities [s]
  (+ (count-re s key-re)
     (count-re s law-nsmap-re)
     (count-re s rel-plain-re)
     (count-re s rel-nsmap-re)))

(def shard-report
  (doall
   (for [{:keys [path sha256 bytes entities dataset]} shards
         :let [p (str root "/" path)]]
     (if-not (exists? p)
       (do (fail! "declared shard missing:" path) {:path path :ok false})
       (let [actual-sha (fleet-ci.gates.legislation-corpus-check/sha256 p)
             actual-bytes (.-size (fs/statSync p))
             body (slurp* p)
             actual-entities (count-entities body)]
         (when-not (= sha256 actual-sha)
           (fail! "shard sha256 mismatch:" path "declared" sha256 "actual" actual-sha
                  "— the file was changed after it was generated"))
         (when-not (= bytes actual-bytes)
           (fail! "shard byte count mismatch:" path "declared" bytes "actual" actual-bytes))
         (when-not (= entities actual-entities)
           (fail! "shard entity count mismatch:" path "declared" entities
                  "actual" actual-entities))
         {:path path :dataset dataset :ok true :entities actual-entities})))))

;; ---------------------------------------------------------------------------
;; 3. quality-report の床。gate が「静かに空になる」のを防ぐ。
(let [c (:quality/corpus quality)
      t (:quality/thresholds quality)]
  (when-not (:corpus/present? c)
    (fail! "quality-report says the corpus is absent"))
  (when-not (zero? (:quality/text-bytes-held-here quality))
    (fail! "scope violation: :quality/text-bytes-held-here is"
           (:quality/text-bytes-held-here quality)
           "— this repo projects a corpus, it must never store one"))
  (when (< (:corpus/sources c 0) (:minimum-corpus-sources t 4))
    (fail! "corpus sources" (:corpus/sources c) "<" (:minimum-corpus-sources t)))
  (when-not (= (:corpus/sources c) (:corpus/sources-declared c))
    (fail! "a declared corpus source produced no index — coverage silently thinned"))
  (when (< (:corpus/laws c 0) (:minimum-corpus-laws t 90000))
    (fail! "corpus laws" (:corpus/laws c) "<" (:minimum-corpus-laws t)))
  (when (< (:corpus/relations c 0) (:minimum-corpus-relations t 600000))
    (fail! "corpus relations" (:corpus/relations c) "<" (:minimum-corpus-relations t)))
  (when-not (pos? (:corpus/text-bytes-addressed c 0))
    (fail! "corpus addresses zero bytes of text — the whole point is the text")))

;; manifest の合計 == quality-report の合計（2 つの生成物が食い違わない）
(let [c (:quality/corpus quality)]
  (when-not (= (:corpus/laws c) (reduce + 0 (map :laws sources)))
    (fail! "quality-report corpus/laws disagrees with manifest sources"))
  (when-not (= (:corpus/relations c) (reduce + 0 (map :relations sources)))
    (fail! "quality-report corpus/relations disagrees with manifest sources")))

;; ---------------------------------------------------------------------------
;; 4. JP シャードの意味検査。全 corpus を parse する代わりに、text 層が
;;    complete-as-a-class な JP を代表に取る。
(def jp-laws-path (path/join root "data/corpus/jp.go.e-gov.elaws/laws-000.edn"))
(def jp-rels-path (path/join root "data/corpus/jp.go.e-gov.elaws/relations-000.edn"))

(when (and (exists? jp-laws-path) (exists? jp-rels-path))
  (let [laws (read-edn jp-laws-path)
        rels (read-edn jp-rels-path)
        keys' (map :law/key laws)
        hex64 #(and (string? %) (= 64 (count %)) (re-matches #"[0-9a-f]{64}" %))]
    ;; identity は一意でなければ Datascript に載せた瞬間に上書きが起きる
    (when-not (= (count keys') (count (distinct keys')))
      (fail! "duplicate :law/key in the JP shard —" (- (count keys') (count (distinct keys')))
             "collisions would silently overwrite entities"))
    (let [ids (map :law.rel/id rels)]
      (when-not (= (count ids) (count (distinct ids)))
        (fail! "duplicate :law.rel/id in the JP relation shard")))
    ;; text address は「不正な sha256」より「無い」方がまだ良い。壊れた
    ;; ポインタは検証不能な参照になる。
    (doseq [l laws
            :when (:law.text/sha256 l)]
      (when-not (hex64 (:law.text/sha256 l))
        (fail! "malformed text sha256 on" (:law/key l) ":" (:law.text/sha256 l)))
      (when-not (pos? (:law.text/bytes l 0))
        (fail! "non-positive text bytes on" (:law/key l)))
      (when (str/blank? (str (:law.text/dataset l)))
        (fail! "text address with no dataset on" (:law/key l))))
    ;; JP の text 層は「上流が 404 を返す 13 件を除いて全件」。上限ではなく
    ;; 等号で見る: 減れば上流が公開した、増えれば取得が退行した、どちらも人が見る。
    (let [missing (count (remove :law.text/sha256 laws))]
      (when-not (= 13 missing)
        (fail! "JP laws without a text address:" missing
               "(expected exactly 13 — the ids e-Gov itself 404s)")))
    ;; 辺の向き。日本国憲法は一度も改正されていないので、ここに amends が
    ;; 入っていたら from/to が反転している。件数では絶対に見つからない不具合。
    (let [const-key "jp-elaws:321CONSTITUTION"
          incoming (filter #(and (= const-key (:law.rel/to %))
                                 (= :law.rel/amends (:law.rel/kind %)))
                           rels)]
      (when-not (empty? incoming)
        (fail! "the Constitution of Japan is recorded as amended by"
               (str/join "," (map :law.rel/from (take 5 incoming)))
               "— amends edge direction is inverted")))
    ;; ...ただし辺が空でないことも要る。でないと上の検査が「空集合だから通る」
    (let [resolved (count (filter :law.rel/resolved? rels))]
      (when (< resolved 1000)
        (fail! "only" resolved "resolved amendment edges in JP — the graph is not connected")))
    (println "jp shard:" (count laws) "laws," (count rels) "relations")))

;; ---------------------------------------------------------------------------
;; 5. corpus → catalog の join。壊れると「この本文はどのライセンスで使えるか」が
;;    黙って答えられなくなる。ここ以外にそれを検出するものが無い。
(let [legal-sources (filter :legal-source/id catalog)
      by-id (into {} (map (juxt :legal-source/id identity)) legal-sources)
      corpus-source-ids (into #{} (map :source-id) sources)]
  (doseq [sid corpus-source-ids]
    (if-let [row (get by-id sid)]
      (do
        (when-not (= :status/ingested (:legal-source/status row))
          (fail! "corpus source" sid "is not marked :status/ingested in the catalog"))
        (when (str/blank? (str (:legal-source/dataset row)))
          (fail! "corpus source" sid "claims :status/ingested but names no dataset")))
      (fail! "corpus :law/source-id" sid "has no :legal-source row —"
             "the licence join is broken"))))

;; ---------------------------------------------------------------------------
;; 6. schema が corpus 層の属性を宣言していること。
(doseq [a [:law/key :law/jurisdiction :law/source-id :law/status :law.text/sha256
           :law.text/dataset :law.rel/id :law.rel/from :law.rel/to :law.rel/kind
           :law.rel/resolved?]]
  (when-not (contains? schema a)
    (fail! "schema/legislation.edn does not declare" a)))

;; ---------------------------------------------------------------------------
(println "shards:" (count shards) "verified,"
         (reduce + 0 (keep :entities shard-report)) "entities,"
         "sources:" (count sources))
(println "NOT checked here (stated, not hidden): the EU/UK/US shards are verified by"
         "sha256 + entity count only, not parsed for semantics; and nothing re-derives"
         "data/corpus/** from the locked source datasets.")

(if (seq @failures)
  (do (println "FAIL —" (count @failures) "violation(s):")
      (doseq [f (take 30 @failures)] (println "  -" f))
      (js/process.exit 1))
  (println "OK — corpus projection is intact"))

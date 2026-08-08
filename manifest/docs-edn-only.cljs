#!/usr/bin/env nbb
;; manifest/docs-edn-only.cljs — 90-docs を EDN-only + DataScript/datom query 可能にする移行。
;;
;; 正本方針（このスクリプトが強制する）:
;;   - 90-docs/adr/*.md は廃止。正本は 90-docs/adr/*.edn（tx-data: [{:db/id -1 :adr/* ...}]）
;;   - 90-docs 配下の他 md（business 生成物・protocols・design-quality coscientist 等）も
;;     同型の :doc/* entity に落とし、md を消す
;;   - 生成物だった business-model.md / maturity-scores.md は EDN 投影へ切替（正本は
;;     従来どおり portfolio BMC datoms + canvas-ledger）
;;
;; 依存: manifest/edn-datomize.cljs と同じ nbb_compat classpath
;;
;; 使い方:
;;   nbb --classpath ".:scripts/nbb_compat" manifest/docs-edn-only.cljs migrate
;;       — md→edn 変換 + 既存 edn に body 注入 + bare map を tx-data 化 + md 削除
;;   nbb --classpath ".:scripts/nbb_compat" manifest/docs-edn-only.cljs migrate --dry-run
;;   nbb --classpath ".:scripts/nbb_compat" manifest/docs-edn-only.cljs datomize-adr
;;       — 90-docs/adr の bare-map / frontmatter 形だけ tx-data 化（md は触らない）
;;   nbb --classpath ".:scripts/nbb_compat" manifest/docs-edn-only.cljs status
;;
;; 冪等: 既に tx-data で :adr/body がある edn はスキップ。md が無い場合は no-op。

(require '[scripts.nbb-compat :refer [slurp spit file-seq format relative-path] :as nc]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str])

(def root (str/trim (:out (shell/sh "git" "rev-parse" "--show-toplevel"))))

(defn schema-path [] (io/file root "manifest" "schema.edn"))

(defn slurp-edn [path]
  (edn/read-string {:default (fn [_tag v] v)} (slurp path)))

(defn already-tx-data?
  [content]
  (and (vector? content) (seq content) (every? map? content)
       (every? #(contains? % :db/id) content)))

(defn multi-entity-tx?
  "schema 行や catalog 多数 entity の datoms ファイル（単一 ADR ではない）。
   migrate では触らず query 側が全 entity を読む。"
  [content]
  (and (vector? content) (> (count content) 1) (every? map? content)))

(defn classify
  [v]
  (cond
    (string? v)  {:type :db.type/string  :card :db.cardinality/one}
    (boolean? v) {:type :db.type/boolean :card :db.cardinality/one}
    (integer? v) {:type :db.type/long    :card :db.cardinality/one}
    (double? v)  {:type :db.type/double  :card :db.cardinality/one}
    (keyword? v) {:type :db.type/keyword :card :db.cardinality/one}
    (nil? v)     {:type :db.type/string  :card :db.cardinality/one}
    (and (coll? v) (empty? v))
    {:type :db.type/string :card :db.cardinality/many}
    (and (coll? v) (every? string? v))  {:type :db.type/string  :card :db.cardinality/many}
    (and (coll? v) (every? keyword? v)) {:type :db.type/keyword :card :db.cardinality/many}
    (and (coll? v) (every? integer? v)) {:type :db.type/long    :card :db.cardinality/many}
    :else {:type :db.type/string :card :db.cardinality/one :blob true}))

(defn attr-value [v]
  (let [{:keys [blob]} (classify v)]
    (if blob (pr-str v) v)))

(defn load-schema []
  (let [f (schema-path)]
    (if (.exists f) (slurp-edn f) [])))

(defn merge-schema! [new-attrs]
  (let [existing (load-schema)
        by-ident (into {} (map (juxt :db/ident identity)) existing)
        merged-by-ident (reduce (fn [acc {:keys [db/ident] :as attr}]
                                   (if (contains? acc ident) acc (assoc acc ident attr)))
                                 by-ident
                                 new-attrs)
        merged (vec (sort-by (comp str :db/ident) (vals merged-by-ident)))]
    (spit (schema-path)
          (str ";; manifest/schema.edn — Datomic/Datascript 互換スキーマ定義（自動生成 by manifest/edn-datomize.cljs / docs-edn-only.cljs）\n"
               ";; :db/ident 属性定義のリスト。Datomic 固有キー(:db.install/_attribute 等)は使わない。\n"
               ";; 手編集禁止 — 再生成すると上書きされる。\n"
               ";;\n"
               ";; 既知の制約: 90-docs/adr/*.edn は歴史的に型がばらつく。最初に遭遇した型を登録。\n\n"
               (pr-str merged)
               "\n"))
    merged))

(defn safe-name
  "EDN keyword name は先頭数字不可。不正文字は - に潰す。"
  [s]
  (let [n (-> (str s)
              (str/replace #"[^A-Za-z0-9*+!\-_\'.?]" "-")
              (str/replace #"^-+" ""))]
    (if (re-matches #"[0-9].*" n) (str "n-" n) n)))

(defn schema-attrs-for [entity]
  (for [[k v] (dissoc entity :db/id)]
    (let [{:keys [type card]} (classify v)
          ident (if (and (keyword? k) (namespace k))
                  (keyword (namespace k) (safe-name (name k)))
                  k)]
      {:db/ident ident :db/valueType type :db/cardinality card})))

(defn ns-key [ns-name k]
  (if (and (keyword? k) (namespace k))
    (keyword (namespace k) (safe-name (name k)))
    (keyword ns-name (safe-name (name k)))))

;; ---------- YAML-ish frontmatter (ADR subset) ----------

(defn unquote-str [s]
  (let [s (str/trim s)]
    (cond
      (and (>= (count s) 2) (= (first s) \") (= (last s) \"))
      (str/replace (subs s 1 (dec (count s))) #"\\\"" "\"")
      (and (>= (count s) 2) (= (first s) \') (= (last s) \'))
      (subs s 1 (dec (count s)))
      (= s "true") true
      (= s "false") false
      (= s "[]") []
      (re-matches #"-?\d+" s) (js/parseInt s 10)
      (re-matches #"-?\d+\.\d+" s) (js/parseFloat s)
      :else s)))

(defn parse-frontmatter-yaml
  "ADR で使っている YAML frontmatter のサブセットを map にする。
   対応: scalar、`- item` リスト、ネストしない key: のみ。失敗時は {}。"
  [yaml-text]
  (try
    (let [lines (str/split-lines (or yaml-text ""))
          state (atom {:m {} :key nil :list? false :buf []})
          flush-list! (fn []
                        (when (and (:key @state) (:list? @state))
                          (swap! state (fn [st]
                                         (-> st
                                             (assoc-in [:m (:key st)] (vec (:buf st)))
                                             (assoc :key nil :list? false :buf []))))))
          flush-scalar! (fn [k v]
                          (swap! state assoc-in [:m k] (unquote-str v)))]
      (doseq [raw lines]
        (let [line (str/replace raw #"\t" "  ")
              trimmed (str/trim line)]
          (cond
            (str/blank? trimmed) nil
            (str/starts-with? trimmed "#") nil
            ;; list item under current key
            (and (:list? @state) (re-matches #"^\s+-\s+.*" line))
            (let [item (str/replace trimmed #"^-\s+" "")]
              (swap! state update :buf conj (unquote-str item)))
            ;; new key: value  OR  key:  (list follows)
            (re-matches #"^[A-Za-z0-9_./-]+:\s*.*$" trimmed)
            (let [[_ k rest] (re-matches #"^([A-Za-z0-9_./-]+):\s*(.*)$" trimmed)
                  rest (str/trim (or rest ""))]
              (flush-list!)
              (if (str/blank? rest)
                (swap! state assoc :key k :list? true :buf [])
                (do (flush-list!)
                    (flush-scalar! k rest)
                    (swap! state assoc :key nil :list? false :buf []))))
            :else
            ;; continuation line for previous scalar — append
            (when (and (:key @state) (not (:list? @state)))
              (swap! state update-in [:m (:key @state)]
                     (fn [prev] (str prev " " (unquote-str trimmed))))))))
      (flush-list!)
      (:m @state))
    (catch :default _ {})))

(defn split-md
  "Returns {:frontmatter map :body string}. Handles --- yaml --- body and no-fm."
  [text]
  (let [text (str/replace text #"\r\n" "\n")]
    (if (str/starts-with? text "---")
      (let [rest (subs text 3)
            end (str/index-of rest "\n---")
            yaml (if end (subs rest 0 end) "")
            body (if end
                   (let [after (subs rest (+ end 4))]
                     (if (str/starts-with? after "\n") (subs after 1) after))
                   rest)]
        {:frontmatter (parse-frontmatter-yaml yaml)
         :body (str/trimr body)})
      {:frontmatter {}
       :body (str/trimr text)})))

(defn guess-meta-from-body
  "frontmatter が無い md から title/status/id を粗く拾う。"
  [body filename]
  (let [title (when-let [m (re-find #"(?m)^#\s+(.+)$" body)] (second m))
        status (or (when-let [m (re-find #"(?i)\*\*Status\*\*:\s*([^\n*]+)" body)]
                     (str/trim (second m)))
                   (when-let [m (re-find #"(?i)^-\s+\*\*Status\*\*:\s*(.+)$" body)]
                     (str/trim (second m)))
                   (when-let [m (re-find #"(?i)^##\s+Status\s*\n+([^\n#]+)" body)]
                     (str/trim (second m))))
        date (when-let [m (re-find #"(?i)\*\*Date\*\*:\s*([0-9-]+)" body)]
               (str/trim (second m)))
        stem (str/replace filename #"\.md$" "")
        id (or (when-let [m (re-find #"(?i)ADR-(\d{10})" (or title stem))]
                 (str "adr-" (second m) "-" (str/replace stem #"^\d{10}-?" "")))
               (str "adr-" stem))]
    (cond-> {:id id :doc_type "adr"}
      title (assoc :title title)
      status (assoc :status (str/lower-case (first (str/split status #"[\s—\-–(]"))))
      date (assoc :date date :last_verified date))))

(defn fm->adr-attrs
  "YAML frontmatter map (string keys) → :adr/* keyword map。値は attr-value 済みにしない
   （後段 entity 組み立てで一括処理）。"
  [fm]
  (into {}
        (keep (fn [[k v]]
                (when (some? v)
                  (let [kk (if (string? k) (keyword "adr" (-> k (str/replace #"_" "-") (str/replace #" " "-")))
                               (ns-key "adr" k))]
                    ;; keep snake_case keys as-is under :adr/ for historical parity
                    [kk v]))))
        fm))

(defn entity-from-md
  [md-path ns-name]
  (let [text (slurp md-path)
        filename (.getName (io/file md-path))
        {:keys [frontmatter body]} (split-md text)
        guessed (when (empty? frontmatter) (guess-meta-from-body body filename))
        fm (merge guessed frontmatter)
        base (fm->adr-attrs fm)
        ;; normalize common keys to :adr/* stable names
        id (or (:adr/id base)
               (get base (keyword "adr" "id"))
               (str "adr-" (str/replace filename #"\.md$" "")))
        title (or (:adr/title base) (get base (keyword "adr" "title")) filename)
        status (or (:adr/status base) (get base (keyword "adr" "status")) "active")
        entity (merge
                {:db/id -1
                 :adr/id (str id)
                 :adr/title (str title)
                 :adr/status (if (keyword? status) (name status) (str status))
                 :adr/doc_type (str (or (:adr/doc_type base) (get base (keyword "adr" "doc_type"))
                                       (if (= ns-name "adr") "adr" "doc")))
                 :adr/body (or body "")
                 :adr/source-format "md-migrated"}
                (dissoc base :adr/id :adr/title :adr/status :adr/doc_type :adr/body))]
    ;; re-namespace any stray bare keys under ns-name for non-adr docs
    (if (= ns-name "adr")
      (into {} (map (fn [[k v]] [k (attr-value v)])) entity)
      (into {:db/id -1}
            (map (fn [[k v]]
                   (let [k' (if (and (keyword? k) (= (namespace k) "adr"))
                              (keyword "doc" (name k))
                              (ns-key "doc" k))]
                     [k' (attr-value v)])))
            (dissoc entity :db/id)))))

(defn transform-existing-edn
  "既存 edn を 1 entity map（:db/id 付き）に正規化。multi-entity / 非 map は nil。"
  [content]
  (cond
    (multi-entity-tx? content) nil
    (already-tx-data? content) (first content)
    (and (vector? content) (seq content) (map? (first content)))
    (assoc (first content) :db/id (or (:db/id (first content)) -1))
    (map? content)
    (let [fm (:frontmatter content)
          base (dissoc content :frontmatter :body-file)
          entries (concat (when (map? fm) (seq fm)) (seq base))
          m (into {:db/id -1}
                  (map (fn [[k v]]
                         [(if (and (keyword? k) (namespace k)) k (keyword "adr" (name k)))
                          (attr-value v)]))
                  entries)]
      m)
    :else nil))

(defn merge-md-into-entity
  "edn entity に md から得た body / 欠けている frontmatter を補完。既存値は上書きしない。"
  [entity md-path]
  (let [from-md (entity-from-md md-path "adr")
        body (:adr/body from-md)
        needs-body? (let [b (or (:adr/body entity) (:body entity))]
                      (or (nil? b) (and (string? b) (str/blank? b))))]
    (cond-> entity
      needs-body? (assoc :adr/body body)
      (nil? (:adr/id entity)) (assoc :adr/id (:adr/id from-md))
      (nil? (:adr/title entity)) (assoc :adr/title (:adr/title from-md))
      (nil? (:adr/status entity)) (assoc :adr/status (:adr/status from-md))
      (nil? (:adr/doc_type entity)) (assoc :adr/doc_type (or (:adr/doc_type from-md) "adr"))
      true (assoc :adr/source-format (or (:adr/source-format entity) "edn+md-merged"))
      true (assoc :db/id (or (:db/id entity) -1)))))

(defn write-entity! [edn-path entity dry-run?]
  (let [tx [(into {} (map (fn [[k v]] [k (if (= k :db/id) v (attr-value v))])) entity)]]
    (when-not dry-run?
      (spit edn-path (pr-str tx))
      (merge-schema! (schema-attrs-for (first tx))))
    tx))

(defn delete-file! [path dry-run?]
  (when-not dry-run?
    (try
      (.unlinkSync (js/require "node:fs") (str path))
      (catch :default e
        (println "WARN delete failed" path (ex-message e))))))

(defn adr-dir [] (io/file root "90-docs" "adr"))

(defn list-files [dir ext]
  (->> (file-seq dir)
       (filter #(.isFile %))
       (filter #(str/ends-with? (str %) ext))
       (sort-by str)))

(defn migrate-adr-file!
  "1 ADR: md があれば edn に取り込み、edn を tx-data 化し、md を削除。"
  [md-or-edn dry-run? report]
  (let [path (str md-or-edn)
        is-md? (str/ends-with? path ".md")
        stem (str/replace path #"\.(md|edn)$" "")
        md-path (str stem ".md")
        edn-path (str stem ".edn")
        md-exists? (.exists (io/file md-path))
        edn-exists? (.exists (io/file edn-path))]
    (try
      (cond
        ;; skip multi-entity catalogs
        (and edn-exists?
             (let [c (slurp-edn edn-path)]
               (multi-entity-tx? c)))
        (do (swap! report update :skipped-multi conj edn-path)
            (when (and md-exists? (not dry-run?))
              ;; multi entity の並列 md は正本ではない — 削除してよい
              (delete-file! md-path dry-run?)
              (swap! report update :md-deleted conj md-path)))

        :else
        (let [base-entity (when edn-exists?
                            (transform-existing-edn (slurp-edn edn-path)))
              entity (cond
                       (and base-entity md-exists?)
                       (merge-md-into-entity base-entity md-path)

                       base-entity
                       (assoc base-entity :db/id (or (:db/id base-entity) -1))

                       md-exists?
                       (entity-from-md md-path "adr")

                       :else nil)]
          (if entity
            (do
              (write-entity! edn-path entity dry-run?)
              (swap! report update :edn-written conj edn-path)
              (when md-exists?
                (delete-file! md-path dry-run?)
                (swap! report update :md-deleted conj md-path)))
            (swap! report update :skipped conj path))))
      (catch :default e
        (swap! report update :errors conj [path (ex-message e)])
        (println "ERROR" path "->" (ex-message e))))))

(defn migrate-adr-dir! [dry-run?]
  (let [dir (adr-dir)
        mds (list-files dir ".md")
        edns (list-files dir ".edn")
        ;; unique stems
        stems (into #{} (map #(str/replace (str %) #"\.(md|edn)$" "")
                             (concat mds edns)))
        report (atom {:edn-written [] :md-deleted [] :skipped [] :skipped-multi [] :errors []})]
    (println (format "migrate adr: %s unique stems (md=%s edn=%s) dry-run=%s"
                     (count stems) (count mds) (count edns) dry-run?))
    (doseq [stem (sort stems)]
      (migrate-adr-file! (str stem ".edn") dry-run? report))
    (println (format "  written=%s md-deleted=%s skipped=%s multi=%s errors=%s"
                     (count (:edn-written @report))
                     (count (:md-deleted @report))
                     (count (:skipped @report))
                     (count (:skipped-multi @report))
                     (count (:errors @report))))
    (when (seq (:errors @report))
      (println "=== ERRORS ===")
      (doseq [[f m] (:errors @report)] (println " " f "->" m)))
    @report))

(defn migrate-generic-md!
  "90-docs 配下の非-adr md を :doc/* tx-data に変換して削除。"
  [md-path dry-run? report]
  (try
    (let [md-str (nc/file-path md-path)
          rel (relative-path root md-str)
          edn-path (str/replace md-str #"\.md$" ".edn")
          text (slurp md-str)
          {:keys [frontmatter body]} (split-md text)
          title (or (get frontmatter "title")
                    (when-let [m (re-find #"(?m)^#\s+(.+)$" body)] (second m))
                    (.getName (io/file md-path)))
          id (or (get frontmatter "id")
                 (str "doc-" (str/replace rel #"[^A-Za-z0-9._-]+" "-")))
          entity (into {:db/id -1
                        :doc/id id
                        :doc/title title
                        :doc/path rel
                        :doc/body (or body "")
                        :doc/source-format "md-migrated"
                        :doc/doc_type (or (get frontmatter "doc_type") "doc")}
                       (map (fn [[k v]]
                              [(keyword "doc" (str/replace (str k) #"_" "-")) (attr-value v)])
                            (dissoc frontmatter "id" "title" "doc_type")))]
      (when-not dry-run?
        (spit edn-path (pr-str [entity]))
        (merge-schema! (schema-attrs-for entity))
        (delete-file! md-str false))
      (swap! report update :edn-written conj edn-path)
      (swap! report update :md-deleted conj md-str))
    (catch :default e
      (swap! report update :errors conj [(str (nc/file-path md-path)) (ex-message e)]))))

(defn migrate-rest-of-90-docs! [dry-run?]
  (let [docs-root (io/file root "90-docs")
        mds (->> (list-files docs-root ".md")
                 (remove #(str/includes? (str %) "/adr/"))
                 ;; leave sample HTML alone; only md
                 vec)
        report (atom {:edn-written [] :md-deleted [] :errors []})]
    (println (format "migrate other 90-docs md: %s files dry-run=%s" (count mds) dry-run?))
    (doseq [f mds] (migrate-generic-md! f dry-run? report))
    (println (format "  written=%s md-deleted=%s errors=%s"
                     (count (:edn-written @report))
                     (count (:md-deleted @report))
                     (count (:errors @report))))
    @report))

(defn datomize-adr-only! [dry-run?]
  (let [edns (list-files (adr-dir) ".edn")
        report (atom {:ok [] :skipped [] :errors []})]
    (doseq [f edns]
      (try
        (let [content (slurp-edn f)]
          (cond
            (multi-entity-tx? content)
            (swap! report update :skipped conj (str f))

            (already-tx-data? content)
            (swap! report update :skipped conj (str f))

            :else
            (when-let [e (transform-existing-edn content)]
              (when-not dry-run?
                (write-entity! (str f) e false))
              (swap! report update :ok conj (str f)))))
        (catch :default e
          (swap! report update :errors conj [(str f) (ex-message e)]))))
    (println (format "datomize-adr: ok=%s skipped=%s errors=%s"
                     (count (:ok @report)) (count (:skipped @report)) (count (:errors @report))))
    @report))

(defn status! []
  (let [dir (adr-dir)
        mds (list-files dir ".md")
        edns (list-files dir ".edn")
        multi (atom 0)
        tx (atom 0)
        bare (atom 0)
        bad (atom 0)]
    (doseq [f edns]
      (try
        (let [c (slurp-edn f)]
          (cond
            (multi-entity-tx? c) (swap! multi inc)
            (already-tx-data? c) (swap! tx inc)
            (or (map? c) (vector? c)) (swap! bare inc)
            :else (swap! bad inc)))
        (catch :default _ (swap! bad inc))))
    (let [other-md (->> (list-files (io/file root "90-docs") ".md")
                        (remove #(str/includes? (str %) "/adr/"))
                        count)]
      (println (format "adr md=%s edn=%s (tx-data=%s multi=%s bare/other=%s bad=%s) other-90-docs-md=%s"
                       (count mds) (count edns) @tx @multi @bare @bad other-md)))))

(defn split-string-keys
  "Keys that are not keywords in an ADR entity — the fingerprint of a string
  literal that ended early.

  An unescaped `\"` inside `:adr/body` terminates the string, and the rest of
  the document becomes stray map keys and values. If the quote count happens to
  stay even the file still PARSES, so `parse-errors` says nothing: measured
  2026-07-30, ADR-2607299300 had a `:adr/body` truncated at 9,684 of 22,649
  characters (probe output containing \"mainnet\" and [\"1413\"]), and four
  addenda were invisible to every query over \"adr/body\" for a whole session.

  Scoped to entities carrying `:adr/id`, which are always keyword-keyed, so a
  legitimate string- or number-keyed table elsewhere in 90-docs is not flagged."
  [content]
  (let [entities (cond (map? content) [content]
                       (sequential? content) (filter map? content)
                       :else [])]
    (->> entities
         (filter #(contains? % :adr/id))
         (mapcat #(remove keyword? (keys %)))
         (map pr-str)
         distinct
         vec)))

(def known-md-files
  "The .md files still under 90-docs, accepted as a BASELINE.

  ADR-2607171600 makes EDN the only source of truth here, and seventy files have not been
  migrated: gates 20, business/revenue-agent-loop/runs 20, deployment 10, business 10,
  and a handful at the root. That is a real, unfinished migration -- listing them is not
  approval of it.

  It is listed because the alternative was worse. While every run failed on these
  seventy, nothing could report that a SEVENTY-FIRST had appeared, which is the only
  question a gate can usefully answer about a migration in progress. Same reasoning as
  known-parse-errors, and asserted in the same two directions: a file here that no longer
  exists must be removed from the list.

  **Regenerate this list only from a FULL checkout.** Six files were missing from the
  original capture and reported as NEW for a week (2026-07-31 → 2026-08-08), which is
  the one thing this list exists to prevent. All six were already committed when the
  baseline was written -- verified with `git cat-file -e <baseline-commit>:<path>` --
  so they were not new; they were never seen. The likely cause is a sparse worktree,
  and the same trap was hit again on 2026-08-08 while investigating this: the very
  same command reported md=34 from a sparse worktree and md=76 from the full checkout.
  A partial view does not report that it is partial, so a baseline taken from one
  silently converts pre-existing files into permanent false alarms."
  [
   "90-docs/business/cloud-itonami-5820-crm-go-to-market.md"
   "90-docs/kura/README.md"
   "90-docs/religious-community/README.md"
   "90-docs/religious-community/queries/guide.md"
   "90-docs/security-gates/evidence/EVIDENCE-SUMMARY.md"
   "90-docs/security-gates/evidence/INDEX.md"
   "90-docs/security/kagi-external-crypto-review-package-20260718.md"
   "90-docs/business/cloud-itonami-5820-dogfood-execution.md"
   "90-docs/business/cloud-itonami-5820-validation-sprint-order-form.md"
   "90-docs/business/cloud-itonami-6399-6310-acquisition-audit.md"
   "90-docs/business/cloud-itonami-commercial-closure.md"
   "90-docs/business/cloud-itonami-e2e-checkout-verification.md"
   "90-docs/business/cloud-itonami-energy-systemdynamics-estimate.md"
   "90-docs/business/cloud-itonami-maturity-loop.md"
   "90-docs/business/net-kotobase-graphdb-baas-execution-plan-2026-07-24.md"
   "90-docs/business/portfolio-priority-2026-07-24.md"
   "90-docs/business/revenue-agent-loop/COMMERCIAL-GO-NO-GO.md"
   "90-docs/business/revenue-agent-loop/README.md"
   "90-docs/business/revenue-agent-loop/RUN-TEMPLATE.md"
   "90-docs/business/revenue-agent-loop/runs/0001-club-shinshi-first-external-payment.md"
   "90-docs/business/revenue-agent-loop/runs/0002-cloud-itonami-existing-tenant-paid-pilot.md"
   "90-docs/business/revenue-agent-loop/runs/0003-cloud-itonami-7810-public-prospects.md"
   "90-docs/business/revenue-agent-loop/runs/0004-net-babiniku-payment-preflight.md"
   "90-docs/business/revenue-agent-loop/runs/0005-commercial-readiness-gate.md"
   "90-docs/business/revenue-agent-loop/runs/0006-net-kotobase-standard-closure.md"
   "90-docs/business/revenue-agent-loop/runs/0007-net-kotobase-privacy-inventory.md"
   "90-docs/business/revenue-agent-loop/runs/0008-net-kotobase-account-deletion.md"
   "90-docs/business/revenue-agent-loop/runs/0009-net-kotobase-legacy-index-and-processors.md"
   "90-docs/business/revenue-agent-loop/runs/0010-provider-evidence-and-counsel-packet.md"
   "90-docs/business/revenue-agent-loop/runs/0011-net-kotobase-stripe-boundary-e2e.md"
   "90-docs/business/revenue-agent-loop/runs/0012-cloud-itonami-5820-offer-integrity.md"
   "90-docs/business/revenue-agent-loop/runs/0013-cloud-itonami-operator-selection.md"
   "90-docs/business/revenue-agent-loop/runs/0014-cloud-itonami-collection-agreement-execution-copy.md"
   "90-docs/business/revenue-agent-loop/runs/0015-cloud-itonami-collection-adr.md"
   "90-docs/business/revenue-agent-loop/runs/0016-cloud-itonami-stripe-test-credential-audit.md"
   "90-docs/business/revenue-agent-loop/runs/0017-cloud-itonami-5820-order-form.md"
   "90-docs/business/revenue-agent-loop/runs/0018-cloud-itonami-legal-owner-approval.md"
   "90-docs/business/revenue-agent-loop/runs/0019-cloud-itonami-legal-site.md"
   "90-docs/business/revenue-agent-loop/runs/0020-capital-allocation-governor.md"
   "90-docs/business/revenue-agent-loop/SCORECARD.md"
   "90-docs/deployment/MONTH-3-CANARY-DEPLOYMENT-PLAN.md"
   "90-docs/deployment/MONTH-3-DEPLOYMENT-READINESS.md"
   "90-docs/deployment/MONTH-3-FINAL-CHECKLIST-2026-09-08.md"
   "90-docs/deployment/MONTH-3-INDEX.md"
   "90-docs/deployment/MONTH-3-PRE-FLIGHT-README.md"
   "90-docs/deployment/MONTH-3-ROLLBACK-PROCEDURE.md"
   "90-docs/deployment/MONTH-3-STRANGLER-FIG-EXECUTION-PLAN.md"
   "90-docs/deployment/MONTH-3-STRANGLER-FIG-SUMMARY.md"
   "90-docs/deployment/MONTH-3-TIMELINE-INTEGRATION.md"
   "90-docs/deployment/README.md"
   "90-docs/fleet-migration-month-3-monitoring-guide.md"
   "90-docs/fleet-migration-rust-fleet-decommissioning.md"
   "90-docs/gates/ACTIVATION-STATUS-WAVE5.md"
   "90-docs/gates/calendar-setup-guide.md"
   "90-docs/gates/decision-rule-and-escalation-summary.md"
   "90-docs/gates/DEPLOYMENT-ARTIFACTS-CHECKLIST.md"
   "90-docs/gates/DEPLOYMENT-QUICK-REFERENCE.md"
   "90-docs/gates/DEPLOYMENT-SUMMARY.md"
   "90-docs/gates/DISTRIBUTION-ACTIVATION-SUMMARY-2026-07-21.md"
   "90-docs/gates/EXECUTION-KICKOFF-REPORT-WAVE5-M5M6.md"
   "90-docs/gates/EXECUTION-LEAD-QUICK-REFERENCE-2026-07-21.md"
   "90-docs/gates/EXECUTION-READINESS-FINAL-CHECKLIST-2026-08-21.md"
   "90-docs/gates/FINAL-DISTRIBUTION-READINESS-CHECK-2026-07-21.md"
   "90-docs/gates/KICKOFF-EXECUTION-COMPLETE-2026-07-21.md"
   "90-docs/gates/kickoff-meeting-slides.md"
   "90-docs/gates/KICKOFF-PREPARATION-SUMMARY-2026-07-21.md"
   "90-docs/gates/metrics-deployment-procedure.md"
   "90-docs/gates/PRODUCTION-READINESS-GO-NO-GO-GATE-2026-07-21.md"
   "90-docs/gates/README.md"
   "90-docs/gates/team-notifications-email-templates.md"
   "90-docs/gates/team-onboarding-checklist.md"
   "90-docs/gates/week-11-checkpoint-procedures.md"
   "90-docs/MONTH-3-EXECUTION-SUMMARY.md"
   "90-docs/MONTH-3-PREFLIGHT-CHECKLIST.md"
   "90-docs/WAVE-2-EXECUTION-SUMMARY-2026-07-21.md"
   "90-docs/wave-2-task-board.md"])

(def known-parse-errors
  "Files under 90-docs that have not parsed as EDN for some time, accepted as a
  BASELINE so that a new breakage is visible.

  This is not approval. Each is a document whose content is unreachable to every query
  over the 90-docs plane -- `edn-query.cljs` skips them, and its loader reports what it
  skipped for exactly this reason. They are listed so the verifier can answer the only
  question it could not answer while it failed unconditionally: did something break
  TODAY?

  Measured 2026-07-31. Sixteen of the eighteen are outside 90-docs/adr (deployment,
  gates, migration), and most report `Unmatched delimiter` or an odd number of map
  forms -- the signature of a literal that ended early, which is the same failure an
  unescaped quote produces.

  That last sentence is a hypothesis, and for the seven still listed below it is
  WRONG. Measured 2026-08-04 by scanning every string literal in each file: the
  longest is ~400 characters and none runs away, so no quote is swallowing
  structure. The actual defect is collections that are opened and never closed
  (in MONTH-3-GRACEFUL-RUST-DRAIN.edn a `{` at line 217 and another at 253), plus
  maps appearing in key position because a sibling map follows a keyed value with
  no key of its own.

  They are not mechanically repairable, and the reason is worth stating so the
  next reader does not rediscover it: once the delimiters are gone, indentation is
  the only remaining signal for where a map ends, and in these files it is not
  consistent -- `:next-step` sits at three spaces immediately after a `}` that
  closed at four, so it reads as either the inner map's last key or the outer
  map's next one. Choosing a parent for each key is authoring, not repair.

  Two repairs that ARE unambiguous were tried and then reverted, so they are not
  retried: wrapping a run of unkeyed sibling maps in a vector, and closing a map
  where the following line's indentation marks the boundary. Both applied cleanly
  to the first two sites in MONTH-3-GRACEFUL-RUST-DRAIN.edn and then stalled, and
  a file that is still unparseable after two edits is worse than an untouched one
  -- it adds noise to the owning agent's history for no gain. What would actually
  fix these is the agent that owns these runbooks regenerating them, or an owner
  deciding they are historical planning documents and retiring them."
  ;; Two ADRs left this list on 2026-07-31: both were a pr-str'd map inside a string
  ;; where some quotes were escaped and the rest were not, and both are repaired.
  ;;
  ;; Six more left it on 2026-08-01 -- they had been repaired at some earlier point
  ;; but never removed from here, so the verifier reported "BASELINE IS STALE" and
  ;; exited 1 on every run. A baseline that stays stale fails exactly as
  ;; uninformatively as the unconditional FAIL it replaced, which is why `fixed`
  ;; is a hard failure rather than a note: the list has to be pruned when a file
  ;; is repaired, in the same commit.
  ["90-docs/deployment/MONTH-3-GRACEFUL-RUST-DRAIN.edn"
   "90-docs/deployment/MONTH-3-MANUAL-ROLLBACK-DECISION-TREE.edn"
   "90-docs/deployment/MONTH-3-TEAM-ASSIGNMENTS.edn"
   "90-docs/gates/weekly-checkpoint-structure.edn"
   "90-docs/migration/M5-M6-checkpoint-procedures.edn"])

(defn verify!
  "EDN が 90-docs の唯一の正本であることを機械検証する。
   - 90-docs 配下に .md が無い
   - ADR edn は parse 可能で multi 以外は tx-data（:db/id 付き）
   - 文字列に 90-docs/**/*.md パス参照が残っていない
   - source-format が md-migrated / edn+md-merged ではない
   失敗時 exit 1。"
  []
  (let [docs-root (io/file root "90-docs")
        md-left (list-files docs-root ".md")
        edns (list-files docs-root ".edn")
        path-md-re #"90-docs/[A-Za-z0-9_./+-]+\.md\b"
        ;; 値としての source-format だけを弾く（ADR 本文で歴史語彙として触れるのは可）
        bad-sf-re #":(?:adr|doc)/source-format\s+\"(?:md-migrated|edn\+md-merged)\""
        parse-errors (atom [])
        not-tx (atom [])
        split-strings (atom [])
        path-md-hits (atom [])
        sf-hits (atom [])]
    (doseq [f edns]
      ;; The text-level checks must not sit behind the parse: when they did, a
      ;; parse error masked them for that file, so every parse fix surfaced
      ;; "new" md-path and source-format hits that had been there all along and
      ;; the totals understated the real work.
      (let [raw (slurp f)]
        (when (re-find path-md-re raw)
          (swap! path-md-hits conj (str f)))
        (when (re-find bad-sf-re raw)
          (swap! sf-hits conj (str f)))
        (try
          (let [content (edn/read-string {:default (fn [_tag v] v)} raw)]
            (when (and (str/includes? (str f) "/adr/")
                       (not (multi-entity-tx? content))
                       (not (already-tx-data? content)))
              (swap! not-tx conj (str f)))
            (when-let [ks (seq (split-string-keys content))]
              (swap! split-strings conj [(str f) ks])))
          (catch :default e
            (swap! parse-errors conj [(str f) (ex-message e)])))))
    (println (format "verify: md=%s edn=%s parse-errors=%s not-tx=%s split-strings=%s path-md-refs=%s source-format-residue=%s"
                     (count md-left) (count edns)
                     (count @parse-errors) (count @not-tx)
                     (count @split-strings)
                     (count @path-md-hits) (count @sf-hits)))
    (when (seq md-left)
      (println (str "=== .md UNDER 90-docs (" (count md-left) ") ==="))
      (println "  The EDN-only policy (ADR-2607171600) is not finished. This is not a")
      (println "  handful of stragglers: measured 2026-07-31, they cluster in gates (20),")
      (println "  business/revenue-agent-loop/runs (20), deployment (10) and business (10).")
      (println "  Until they are migrated or the policy is scoped to exclude them, this")
      (println "  command cannot pass -- so read the checks below, not the exit code.")
      (doseq [f (take 10 md-left)] (println " " (str f)))
      (when (< 10 (count md-left))
        (println (str "  … and " (- (count md-left) 10) " more"))))
    (when (seq @parse-errors)
      (println "=== PARSE ERRORS ===")
      (doseq [[f m] (take 20 @parse-errors)] (println " " f "->" m)))
    (when (seq @not-tx)
      (println (str "=== NOT TX-DATA — reported, not fatal (" (count @not-tx) ") ==="))
      (println "  These lack :db/id, which is the house shape. They ARE readable:")
      (println "  edn-query.cljs assigns its own tempid to every entity it loads.")
      (doseq [f (take 20 @not-tx)] (println " " f)))
    (when (seq @split-strings)
      (println "=== SPLIT STRINGS (parses, but a literal ended early) ===")
      (doseq [[f ks] (take 20 @split-strings)]
        (println " " f "-> stray keys:" (str/join " " (take 6 ks)))))
    (when (seq @path-md-hits)
      (println "=== 90-docs/*.md PATH REFS ===")
      (doseq [f (take 20 @path-md-hits)] (println " " f)))
    (when (seq @sf-hits)
      (println "=== source-format residue ===")
      (doseq [f (take 20 @sf-hits)] (println " " f)))
    ;; RATCHET. Eighteen files under 90-docs have not parsed for some time, so this
    ;; command exited FAIL every run -- and a FAIL that is always FAIL cannot tell
    ;; anyone that something NEW broke. Measured the hard way on 2026-07-31: an
    ;; :adr/body was corrupted by an unescaped quote, the `split-strings` check that
    ;; exists precisely for that would have named it, and nobody would have noticed
    ;; among the standing failures.
    ;;
    ;; So the baseline is accepted and anything beyond it fails. The baseline is also
    ;; asserted in the other direction: a file listed here that now parses must be
    ;; removed from the list, or the list silently grows stale and stops ratcheting.
    (let [known (set known-parse-errors)
          ;; @parse-errors carries absolute paths; the baseline is repo-relative, which
          ;; is the only form that survives being read on another machine.
          relative (fn [p] (let [i (str/index-of p "90-docs/")]
                             (if i (subs p i) p)))
          seen (set (map (comp relative first) @parse-errors))
          new-errors (sort (remove known seen))
          fixed (sort (remove seen known))
          ;; not-tx is REPORTED but does not hard-fail, and the distinction is
          ;; measured rather than assumed. Those seven ADRs lack :db/id, which is the
          ;; house tx-data shape -- but `edn-query.cljs` REASSIGNS :db/id from its own
          ;; tempid counter for every entity it loads, so a missing one changes nothing
          ;; about whether the document is readable. All seven were queried by
          ;; :adr/id on 2026-07-31 and all seven answered.
          ;;
          ;; Keeping them as a hard failure put a style deviation in the same bucket as
          ;; a body truncated by an unescaped quote, and that conflation is part of why
          ;; this command was permanently red and the real corruption was invisible in
          ;; it. Unreadable fails; unconventional is reported.
          known-md (set known-md-files)
          seen-md (set (map (comp relative str) md-left))
          new-md (sort (remove known-md seen-md))
          gone-md (sort (remove seen-md known-md))
          ;; path-md-refs follows md-left: while seventy .md files legitimately exist,
          ;; a document that references one is not carrying a dangling link. Measured --
          ;; the two ADRs flagged point at gates/*.md files that are right there. The
          ;; check earns its keep once the migration finishes; until then it is
          ;; reporting the same unfinished work twice.
          hard-fail? (or (seq @split-strings) (seq @sf-hits)
                         (seq new-md) (seq gone-md))]
      (when (seq new-errors)
        (println "=== NEW PARSE ERRORS (not in the accepted baseline) ===")
        (doseq [f new-errors] (println " " f)))
      (when (seq fixed)
        (println "=== BASELINE IS STALE — these parse now, remove them from known-parse-errors ===")
        (doseq [f fixed] (println " " f)))
      (when (seq new-md)
        (println "=== NEW .md UNDER 90-docs (not in the accepted baseline) ===")
        (println "  EDN is the source of truth here; a new .md moves the migration backwards.")
        (doseq [f new-md] (println " " f)))
      (when (seq gone-md)
        (println "=== BASELINE IS STALE — these .md files are gone, remove them from known-md-files ===")
        (doseq [f gone-md] (println " " f)))
      (if (and (not hard-fail?) (empty? new-errors) (empty? fixed))
        (do (println (str "verify: OK — no NEW breakage ("
                          (count known) " known-unparseable and "
                          (count known-md) " un-migrated .md accepted as baselines; "
                          "nothing else is wrong)"))
            (println "  Neither baseline is approval: ADR-2607171600's EDN-only migration")
            (println "  is unfinished, and those files are unreadable to every query over")
            (println "  this plane. What OK means is that nothing got worse.")
            0)
        (do (println "verify: FAIL")
            (nc/exit 1)
            1)))))

(defn write-policy-adr! [dry-run?]
  (let [path (io/file root "90-docs" "adr" "2607171600-docs-adr-edn-only-datascript.edn")
        entity {:db/id -1
                :adr/id "adr-2607171600-docs-adr-edn-only-datascript"
                :adr/title "ADR-2607171600: docs/ADR は EDN のみ — DataScript/datom query が正経路"
                :adr/status "accepted"
                :adr/doc_type "adr"
                :adr/topic "docs-edn-only"
                :adr/date "2026-07-17"
                :adr/last_verified "2026-07-17"
                :adr/authoritative true
                :adr/deciders ["Jun Kawasaki"]
                :adr/authoritative_for
                ["90-docs/adr の正本フォーマット（.edn tx-data、.md 禁止）"
                 "90-docs 配下ドキュメントの EDN-only 方針"
                 "DataScript query 経路（manifest/edn-query.cljs）"
                 "schema 登録（manifest/schema.edn、自動生成）"]
                :adr/related
                ["manifest/edn-datomize.cljs"
                 "manifest/edn-query.cljs"
                 "manifest/docs-edn-only.cljs"
                 "manifest/schema.edn"
                 "90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn"
                 "90-docs/design-quality/design-quality.datoms.edn"]
                :adr/supersedes []
                :adr/superseded_by []
                :adr/problem
                "docs/ADR が .md と .edn の二重管理になり、DataScript で横断 query できない。md-only 86 件、対になる md が本文を抱え edn に無いケース、bare map 未 tx-data が残っていた。"
                :adr/decision
                "90-docs（特に adr）の正本は EDN のみ。各 ADR は (d/transact conn (edn/read-string (slurp f))) 可能な [{:db/id -1 :adr/* ...}] 1 entity。入れ子は pr-str blob。query は nbb --classpath \".:scripts/nbb_compat\" manifest/edn-query.cljs。新規 ADR は .md を書かない。BMC 等の生成 md も EDN 投影へ寄せ、手編集 md を置かない。"
                :adr/consequences
                ["agent/人間は ADR を .edn で読み書きする"
                 "横断検索は datalog（edn-query.cljs q）"
                 "歴史的 md は migrate で :adr/body に取り込み後削除"
                 "multi-entity *.datoms.edn（BMC/design-quality）は catalog として残し query が全 entity をロード"]
                :adr/body
                (str "# ADR-2607171600: docs/ADR は EDN のみ — DataScript/datom query が正経路\n\n"
                     "## Status\naccepted — 2026-07-17\n\n"
                     "## Decision\n"
                     "- Source of truth for `90-docs/adr` is `*.edn` tx-data only. No `*.md`.\n"
                     "- Each ADR file: `[{:db/id -1 :adr/id ... :adr/title ... :adr/status ... :adr/body ...}]`.\n"
                     "- Nested maps/vectors are stored as `pr-str` string blobs (same as edn-datomize).\n"
                     "- Query: `nbb --classpath \".:scripts/nbb_compat\" manifest/edn-query.cljs count|q`.\n"
                     "- Schema attrs auto-merged into `manifest/schema.edn`.\n"
                     "- Migration tool: `manifest/docs-edn-only.cljs migrate`.\n"
                     "- Multi-entity catalogs (`*.datoms.edn`) remain multi-entity vectors; not single ADR docs.\n")}]
    (when-not dry-run?
      (write-entity! (str path) entity false))
    (println (if dry-run? "would write" "wrote") (str path))
    entity))

(defn -main [& args]
  (let [mode (or (first args) "status")
        dry-run? (some #{"--dry-run"} args)]
    (case mode
      "status" (status!)
      "verify" (verify!)
      "datomize-adr" (datomize-adr-only! dry-run?)
      "policy" (write-policy-adr! dry-run?)
      "migrate"
      (do (write-policy-adr! dry-run?)
          (migrate-adr-dir! dry-run?)
          (migrate-rest-of-90-docs! dry-run?)
          (status!)
          (when-not dry-run? (verify!)))
      (do (println "usage: nbb --classpath \".:scripts/nbb_compat\" manifest/docs-edn-only.cljs [status|verify|migrate|datomize-adr|policy] [--dry-run]")
          (nc/exit 1)))))

(apply -main *command-line-args*)

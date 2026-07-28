#!/usr/bin/env nbb
;; scripts/repo-taxonomy-select.cljs
;;
;; ADR-2607289600 D9 の selector。`manifest/repo-taxonomy.edn` の 3 面分類で
;; west project を絞り込み、**プレーンなリストを stdout に出す**だけの道具。
;; `west update <names>` / `fleet sync --names` / `xargs` にそのまま渡せる。
;;
;; なぜ west groups への `kind-*` 投影ではなくこの形なのか (ADR-2607289600 D9、実測):
;;
;;   1. `nbb scripts/gen-west-manifest.cljs --check` は main 上で既に STALE。
;;      groups を足すために全体再生成すると、pin が **ローカル working HEAD** から
;;      書き直される — CLAUDE.md が記録している「1件の登録のつもりが壊れた pin を
;;      44 件 main に流した」事故 (90852b86) と同じ経路になる。
;;   2. `west list` に `--group-filter` は無い (west v1.5.0 実測: unexpected arguments)。
;;      group による絞り込みは `west config manifest.group-filter` の**永続的な書き換え**を
;;      要求するので、「今この slice だけ触る」用途に使えない。
;;   3. west の group 意味論は「どれか1つでも有効なら active」。org group が既定で
;;      有効なので、`kind-actor` を足しても org group を全部無効にしない限り絞れない。
;;
;;   対して `west update <project names...>` と `fleet sync --names a,b` は
;;   明示リストを取る。selector はそこに直接繋がり、**west.yml を 1 バイトも変えない**。
;;
;; 使い方:
;;   nbb scripts/repo-taxonomy-select.cljs --kind actor --org cloud-itonami
;;   nbb scripts/repo-taxonomy-select.cljs --kind actor,organism --present
;;   nbb scripts/repo-taxonomy-select.cljs --role person --paths
;;   nbb scripts/repo-taxonomy-select.cljs --trait deploy-target --format comma
;;
;; west / fleet に渡すとき (実測した罠が 2 つあるので必ずこの形で):
;;
;;   # ✅ 正: 既定の lines 出力を xargs -n で刻んで west に渡す
;;   nbb scripts/repo-taxonomy-select.cljs --kind organism --present \
;;     | xargs -n 500 west update --fetch smart
;;
;;   # ✅ 正: fleet sync は --names がカンマ区切りを取る
;;   nbb scripts/repo-taxonomy-select.cljs --kind actor --org cloud-itonami --format comma \
;;     | xargs -I{} nbb --classpath orgs/kotoba-lang/kotoba-fleet-vcs/src \
;;         orgs/kotoba-lang/kotoba-fleet-vcs/bin/fleet.cljs sync \
;;         --db manifest/fleet-db.edn --workspace . --names {} --jobs 8
;;
;;   # ❌ 誤: zsh は変数を単語分割しないので、全名前が 1 引数になり west が
;;   #       "unknown project name/path: a b c" で落ちる (CLAUDE.md 既知の罠)
;;   NAMES=$(nbb scripts/repo-taxonomy-select.cljs --kind organism --format space)
;;   west update $NAMES
;;
;;   # ❌ 誤: xargs の既定バッチは ARG_MAX 基準で巨大になり、1,107 名を一括で
;;   #       渡すと west 側が落ちる。`-n 400`〜`-n 1000` は実測で通る
;;   nbb scripts/repo-taxonomy-select.cljs --kind actor --present | xargs west list
;;
;; フィルタ (すべて AND、値はカンマ区切りで OR):
;;   --kind   <k,...>   :repo/kind         actor|organism|service|app|lib|corpus|
;;                                          organisation-record|tombstone|docs|
;;                                          blueprint-only|unclassified|absent
;;   --org    <o,...>   :repo/org
;;   --role   <r,...>   :role/for-kind     person|organisation
;;   --trait  <t,...>   :repo/traits 内のキーワード (governor / deploy-target / heartbeat …)
;;   --present          ローカル checkout がある repo だけ (:repo/present true)
;;   --paths            name でなく repo path を出す
;;   --format <f>       lines (既定) | comma | space
;;   --count            件数だけを stdout に出す
(require '[scripts.nbb-compat :as io :refer [slurp]]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

(defn- parse-args [args]
  (loop [args args opts {:format "lines" :in "manifest/repo-taxonomy.edn"}]
    (if-let [[k & more] (seq args)]
      (case k
        "--kind"    (recur (rest more) (assoc opts :kind (first more)))
        "--org"     (recur (rest more) (assoc opts :org (first more)))
        "--role"    (recur (rest more) (assoc opts :role (first more)))
        "--trait"   (recur (rest more) (assoc opts :trait (first more)))
        "--format"  (recur (rest more) (assoc opts :format (first more)))
        "--in"      (recur (rest more) (assoc opts :in (first more)))
        "--present" (recur more (assoc opts :present true))
        "--paths"   (recur more (assoc opts :paths true))
        "--count"   (recur more (assoc opts :count true))
        (recur more opts))
      opts)))

(def opts (parse-args *command-line-args*))

(defn- csv->set [s] (when s (set (map str/trim (str/split s #",")))))

(def entities (edn/read-string (slurp (:in opts))))

;; role リンクは別 entity なので、先に repo path -> #{for-kind} の索引を作る。
(def roles-by-repo
  (reduce (fn [m e]
            (if-let [p (:role/repo e)]
              (update m p (fnil conj #{}) (:role/for-kind e))
              m))
          {} entities))

(defn- traits-of [e]
  ;; :repo/traits は pr-str された set 文字列 ("#{:governor :tests}")。
  ;; 読み戻してキーワード名の集合にする。
  (try (set (map name (edn/read-string (or (:repo/traits e) "#{}"))))
       (catch :default _ #{})))

(def kinds  (csv->set (:kind opts)))
(def orgs   (csv->set (:org opts)))
(def roles  (csv->set (:role opts)))
(def traits (csv->set (:trait opts)))

(def selected
  (->> entities
       (filter :repo/path)
       (filter #(or (nil? kinds) (contains? kinds (:repo/kind %))))
       (filter #(or (nil? orgs)  (contains? orgs (:repo/org %))))
       (filter #(or (nil? roles) (seq (clojure.set/intersection roles (get roles-by-repo (:repo/path %) #{})))))
       (filter #(or (nil? traits) (seq (clojure.set/intersection traits (traits-of %)))))
       (filter #(or (not (:present opts)) (:repo/present %)))
       (sort-by :repo/path)))

(def out (map (if (:paths opts) :repo/path :repo/name) selected))

;; 進捗・件数は stderr。stdout はパイプ可能なリストのみに保つ。
(binding [*print-fn* *print-err-fn*]
  (println (str "repo-taxonomy-select: " (count selected) " / " (count (filter :repo/path entities))
                " projects"
                (when kinds  (str "  kind=" (str/join "," kinds)))
                (when orgs   (str "  org=" (str/join "," orgs)))
                (when roles  (str "  role=" (str/join "," roles)))
                (when traits (str "  trait=" (str/join "," traits)))
                (when (:present opts) "  present-only"))))

(cond
  (:count opts)         (println (count selected))
  (= "comma" (:format opts)) (println (str/join "," out))
  (= "space" (:format opts)) (println (str/join " " out))
  :else                 (doseq [x out] (println x)))

;; scripts/query-dialect-bench/plane.cljs — ベンチが使う **subset plane**。
;;
;; ⚠ これは manifest/edn-query.cljs の代わりではない。あちらは 20 以上の dataset を
;; 組む本番の面で、**実測 load average 246 の環境で 1 回のロードに数十分**かかった。
;; 40 分かかるベンチは二度と回らない（CLAUDE.md「落ちない gate は劇場」の裏返し ——
;; **回せない gate も同じだけ無内容**）。
;;
;; ここが読むのは本番の面と**同じ実ファイル 2 本**で、変換規則も同じ:
;;   market-intel   <market-intel repo>/data/company-facts.edn   (SEC EDGAR 財務)
;;   repo-taxonomy  manifest/repo-taxonomy.edn                    (repo 3 面分類)
;;
;; したがって「実データである」ことは失われていない。失われているのは**他の
;; dataset との join 可能性**だけで、この 20 問はその 2 つしか参照しない。
;;
;; **subset であることを黙らせない**: 起動時に stderr へ何を載せたかを必ず出す。
;;
;; usage: nbb --classpath ".:scripts/nbb_compat" scripts/query-dialect-bench/plane.cljs 'q*' '<q1>' ...
;;        nbb ... plane.cljs count

(require '[clojure.string :as str]
         '[clojure.edn :as edn]
         '["datascript" :as ds-mod]
         '[scripts.nbb-compat :as compat])

(def ds (or (.-default ds-mod) ds-mod))
(def fs (js/require "fs"))
(def path (js/require "path"))
(def ROOT (or (.-FLEET_ROOT js/process.env) (.cwd js/process)))

(defn kw->attr [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (and (string? k) (str/starts-with? k ":") (> (count k) 1)) (subs k 1)
    (string? k) k
    :else (str k)))

(defn ->ds-scalar [v]
  (cond
    (keyword? v) (kw->attr v)
    (and (string? v) (str/starts-with? v ":") (> (count v) 1)) (subs v 1)
    (nil? v) ""
    :else v))

(defn ->ds-scalar? [v] (or (string? v) (number? v) (boolean? v) (keyword? v) (nil? v)))

(defn ->ds-value [v]
  (cond
    (map? v) (pr-str v)
    (and (coll? v) (not (map? v)) (every? ->ds-scalar? v)) (into-array (map ->ds-scalar v))
    (coll? v) (pr-str v)
    :else (->ds-scalar v)))

(defn entity->js [m]
  (let [obj (js-obj)]
    (doseq [[k v] m]
      (if (= k :db/id) (aset obj ":db/id" v) (aset obj (kw->attr k) (->ds-value v))))
    obj))

(defn west-path
  "west.yml から project の path を引く。生成物を手で持たない。"
  [name']
  (let [y (.readFileSync fs (.join path ROOT "manifest" "west.yml") "utf8")
        lines (str/split-lines y)]
    (loop [ls lines hit false]
      (cond
        (empty? ls) nil
        (and hit (str/includes? (first ls) "path:"))
        (str/trim (second (str/split (first ls) #"path:" 2)))
        :else (recur (rest ls)
                     (or (and hit (not (str/includes? (first ls) "name:")))
                         (str/includes? (first ls) (str "name: " name'))))))))

(defn read-edn [f] (edn/read-string (.readFileSync fs f "utf8")))

(defn load-entities []
  (let [tid (atom 0)
        next-id! #(swap! tid dec)
        mi-rel (west-path "cloud-murakumo-market-intel")
        mi (when mi-rel (.join path ROOT mi-rel "data" "company-facts.edn"))
        tx (.join path ROOT "manifest" "repo-taxonomy.edn")]
    ;; 5 問の 2: 入力が無いのに動いたふりをしない
    (when (or (nil? mi) (not (.existsSync fs mi)))
      (.error js/console (str "plane: REFUSING — market-intel の実ファイルが無い: " mi))
      (compat/exit 2))
    (when-not (.existsSync fs tx)
      (.error js/console (str "plane: REFUSING — repo-taxonomy が無い: " tx))
      (compat/exit 2))
    (let [mi-ents (->> (read-edn mi)
                       (filter map?)
                       (map #(assoc % :db/id (next-id!) :source/dataset "market-intel")))
          tx-ents (->> (read-edn tx)
                       (filter map?)
                       (map #(assoc % :db/id (next-id!))))]
      (.error js/console
              (str "plane: SUBSET — market-intel=" (count mi-ents)
                   " repo-taxonomy=" (count tx-ents)
                   "  (本番の面 manifest/edn-query.cljs は他に 20+ dataset を持つ。"
                   "この面はこの 2 つだけ)"))
      (concat mi-ents tx-ents))))

(defn build-db []
  (let [conn (.create_conn ds)
        ents (load-entities)]
    (when (empty? ents)
      (.error js/console "plane: REFUSING — entity が 0 件。0 件を『結果無し』として返さない")
      (compat/exit 2))
    (.transact ds conn (into-array (map entity->js ents)))
    (.db ds conn)))

(defn -main [& args]
  (let [[mode & queries] args
        db (build-db)]
    (case mode
      "count"
      (println (str "market-intel+repo-taxonomy datoms-entities loaded; "
                    "datasets=" (pr-str (js->clj (.q ds "[:find ?ds (count ?e) :where [?e \"source/dataset\" ?ds]]" db)))))
      "q"  (println (pr-str (js->clj (.q ds (first queries) db))))
      "q*" (println (pr-str (mapv #(js->clj (.q ds % db)) queries)))
      (do (println "usage: plane.cljs [count | q '<q>' | q* '<q1>' '<q2>' ...]")
          (compat/exit 1)))))

(apply -main *command-line-args*)

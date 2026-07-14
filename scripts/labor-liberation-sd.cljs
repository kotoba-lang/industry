#!/usr/bin/env nbb
;; scripts/labor-liberation-sd.cljs — 労働解放 system dynamics モデル
;; (90-docs/business/labor-liberation-sd-model.edn) を実 DataScript (npm datascript)
;; にロードし、逆トポロジーソート・解放レバレッジ・SD ループ解析を query で導出する。
;;
;; ADR-2607122100 の導出器。属性変換（keyword → 裸文字列）は manifest/edn-query.cljs
;; と同じ制約・同じ方針（npm datascript の JS インターフェースは attr を裸文字列で扱う）。
;;
;; 使い方:
;;   nbb scripts/labor-liberation-sd.cljs verify   ; 参照整合 + DAG 非循環の検証
;;   nbb scripts/labor-liberation-sd.cljs layers   ; 逆トポソート層（Kahn）
;;   nbb scripts/labor-liberation-sd.cljs rank     ; score 降順の全ノードランキング
;;   nbb scripts/labor-liberation-sd.cljs loops    ; SD ループ（polarity / status / 断点）
;;   nbb scripts/labor-liberation-sd.cljs plan     ; 再計算した実行順序（3 トラック）
;;   nbb scripts/labor-liberation-sd.cljs q '[:find ?id :where [?e "node/gate" "gate.robotics"] [?e "node/id" ?id]]'

(require '[scripts.nbb-compat :refer [slurp exit]]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.shell :as shell]
         '["datascript" :as ds-mod])

(def ds (.-default ds-mod))

(def root (str/trim (:out (shell/sh "git" "rev-parse" "--show-toplevel"))))
(def model-path (str root "/90-docs/business/labor-liberation-sd-model.edn"))

;; ---------- keyword → 裸文字列変換（manifest/edn-query.cljs と同一方針） ----------

(defn kw->attr [k]
  (if (keyword? k)
    (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (str k)))

(defn ->ds-scalar [v] (if (keyword? v) (kw->attr v) v))

(defn ->ds-value [v]
  (cond
    (map? v) (pr-str v)
    (or (vector? v) (seq? v)) (into-array (map ->ds-scalar v))
    :else (->ds-scalar v)))

(defn entity->js [m]
  (let [obj (js-obj)]
    (doseq [[k v] m]
      (if (= k :db/id)
        (aset obj ":db/id" v)
        (aset obj (kw->attr k) (->ds-value v))))
    obj))

;; ---------- load ----------

(def model (edn/read-string (slurp model-path)))

(defn build-conn []
  (let [conn (.create_conn ds (js-obj))
        tempid (atom 0)
        ent (fn [kind m] (assoc m :db/id (swap! tempid dec) :entity/kind kind))
        tx (concat (map #(ent :node %) (:nodes model))
                   (map #(ent :edge %) (:edges model))
                   (map #(ent :loop %) (:loops model))
                   (map #(ent :gate %) (:gates model))
                   (map #(ent :adr %) (:adrs model)))]
    (.transact ds conn (into-array (map entity->js tx)))
    conn))

(def conn (build-conn))
(def db (.db ds conn))

;; ---------- DataScript query 層（導出はここから引く） ----------

(defn q [query-str] (js->clj (.q ds query-str db)))

(defn pull-all
  "entity/kind でエンティティ群を pull し、\"db/id\" 抜きの clj map（文字列キー）を返す。"
  [kind]
  (->> (q (str "[:find ?e :where [?e \"entity/kind\" \"" (name kind) "\"]]"))
       (map first)
       (map #(js->clj (.pull ds db "[*]" %)))
       (map #(dissoc % ":db/id"))))

(def nodes (pull-all :node))
(def gates (pull-all :gate))
(def loops (pull-all :loop))
(def adrs (pull-all :adr))

(def node-by-id (into {} (map (fn [n] [(get n "node/id") n])) nodes))
(def gate-by-id (into {} (map (fn [g] [(get g "gate/id") g])) gates))

;; 辺は素の datalog query で引く（from/to/kind は必須属性）
(def edges
  (q "[:find ?from ?to ?kind :where
       [?e \"edge/from\" ?from] [?e \"edge/to\" ?to] [?e \"edge/kind\" ?kind]]"))

(def precedence-edges
  "トポソート対象の辺（:requires + :enables。:flows は SD ストック流入なので除外）"
  (into [] (comp (filter (fn [[_ _ k]] (contains? #{"requires" "enables"} k)))
                 (map (fn [[f t _]] [f t])))
        edges))

;; ---------- 導出 1: 逆トポロジーソート（Kahn 層化） ----------

(defn kahn-layers
  "被依存の根（in-degree 0）から層化。cycle があれば nil を返す。"
  [ids edge-pairs]
  (loop [layers [] remaining (set ids)]
    (if (empty? remaining)
      layers
      (let [blocked (into #{} (comp (filter (fn [[f t]] (and (remaining f) (remaining t))))
                                    (map second))
                          edge-pairs)
            layer (sort (remove blocked remaining))]
        (if (empty? layer)
          nil
          (recur (conj layers (vec layer)) (reduce disj remaining layer)))))))

(def sortable-ids
  (->> nodes (remove #(= "stock" (get % "node/kind"))) (map #(get % "node/id"))))

(def layers (kahn-layers sortable-ids precedence-edges))

(def layer-of (into {} (mapcat (fn [[i ids]] (map #(vector % i) ids))
                               (map-indexed vector (or layers [])))))

;; ---------- 導出 2: 解放レバレッジ unlocked-W と score ----------

(def adjacency
  (reduce (fn [m [f t]] (update m f (fnil conj #{}) t)) {} precedence-edges))

(defn transitive-dependents [id]
  (loop [seen #{} frontier (get adjacency id #{})]
    (if (empty? frontier)
      seen
      (let [nxt (first frontier)]
        (if (seen nxt)
          (recur seen (disj frontier nxt))
          (recur (conj seen nxt) (into (disj frontier nxt) (get adjacency nxt #{}))))))))

(defn labor-of [id] (or (get (node-by-id id) "node/labor-share") 0))

(defn unlocked-W [id]
  (reduce + (labor-of id) (map labor-of (transitive-dependents id))))

(defn loop-boost
  "reinforcing loop への参加で優先度を押し上げる。断点そのもの=1.0（そこを塞ぐのが
   最高レバレッジ）/ active=0.5 / broken=0.4 / inactive=0.2。balancing は加点しない。"
  [id]
  (reduce
   (fn [acc l]
     (if (and (= "reinforcing" (get l "loop/polarity"))
              (some #(= id %) (get l "loop/nodes")))
       (+ acc (cond (= id (get l "loop/broken-at")) 1.0
                    (= "active" (get l "loop/status")) 0.5
                    (= "broken" (get l "loop/status")) 0.4
                    :else 0.2))
       acc))
   1.0
   loops))

(defn gate-factor [id]
  (let [g (get (node-by-id id) "node/gate")]
    (if (and g (false? (get (gate-by-id g) "gate/open"))) 0.15 1.0)))

(defn score [id]
  (let [n (node-by-id id)]
    (* (unlocked-W id)
       (or (get n "node/automatability-now") 0)
       (+ 0.5 (* 0.5 (or (get n "node/asset-wedge") 0)))
       (loop-boost id)
       (gate-factor id))))

;; ---------- 表示 ----------

(defn fmt [x] (.toFixed x 3))

(defn pad [s n]
  (let [s (str s)] (if (< (count s) n) (str s (apply str (repeat (- n (count s)) " "))) s)))

(defn node-line [id]
  (let [n (node-by-id id)]
    (str (pad id 32) " L" (get layer-of id "?")
         "  score=" (fmt (score id))
         "  W=" (fmt (unlocked-W id))
         "  boost=" (fmt (loop-boost id))
         (if-let [g (get n "node/gate")] (str "  ⛔" g) "")
         "  [" (get n "node/status") "] " (get n "node/label"))))

(defn print-verify []
  (let [ids (set (map #(get % "node/id") nodes))
        dangling (remove (fn [[f t _]] (and (ids f) (ids t))) edges)
        bad-gate-refs (remove ids (mapcat #(get % "gate/blocks") gates))
        bad-loop-refs (remove ids (mapcat #(get % "loop/nodes") loops))
        acyclic? (some? layers)
        gated-missing (remove (fn [n] (or (nil? (get n "node/gate"))
                                          (gate-by-id (get n "node/gate"))))
                              nodes)
        ok? (and acyclic? (empty? dangling) (empty? bad-gate-refs)
                 (empty? bad-loop-refs) (empty? gated-missing))]
    (println "nodes:" (count nodes) " edges:" (count edges)
             " loops:" (count loops) " gates:" (count gates) " adrs:" (count adrs))
    (println "DAG acyclic (requires+enables):" acyclic?)
    (when (seq dangling) (println "DANGLING edges:" (pr-str dangling)))
    (when (seq bad-gate-refs) (println "BAD gate blocks:" (pr-str bad-gate-refs)))
    (when (seq bad-loop-refs) (println "BAD loop node refs:" (pr-str bad-loop-refs)))
    (when (seq gated-missing) (println "NODES with unknown gate:" (pr-str (map #(get % "node/id") gated-missing))))
    (println (if ok? "VERIFY: OK" "VERIFY: FAIL"))
    (when-not ok? (exit 1))))

(defn print-layers []
  (println "逆トポロジーソート層（被依存の根 = L0 から。:requires + :enables）")
  (doseq [[i layer] (map-indexed vector layers)]
    (println (str "\n== Layer " i " =="))
    (doseq [id layer] (println " " (node-line id)))))

(defn print-rank []
  (println "score = unlocked-W × automatability-now × (0.5+0.5×wedge) × loop-boost × gate-factor")
  (doseq [id (sort-by (comp - score) sortable-ids)]
    (println " " (node-line id))))

(defn print-loops []
  (doseq [l (sort-by #(get % "loop/id") loops)]
    (println (str (pad (get l "loop/id") 28)
                  " [" (get l "loop/polarity") "/" (get l "loop/status") "]"
                  (if-let [b (get l "loop/broken-at")] (str " 断点=" b) "")
                  " τ=" (get l "loop/cycle-time-months") "mo"))
    (println "   " (get l "loop/label"))
    (println "   " (str/join " → " (get l "loop/nodes")))))

(defn work-remaining? [id]
  (not (contains? #{"live" "shipping" "accumulating" "done"}
                  (get (node-by-id id) "node/status"))))

(defn print-plan []
  (let [broken (filter #(= "broken" (get % "loop/status")) loops)
        gated? (fn [id] (< (gate-factor id) 1.0))
        main (->> sortable-ids
                  (filter work-remaining?)
                  (remove gated?)
                  (remove #(>= (or (get (node-by-id %) "node/lead-time-months") 0) 6))
                  (sort-by (fn [id] [(layer-of id 99) (- (score id))])))
        enablers (->> sortable-ids
                      (filter work-remaining?)
                      (filter #(>= (or (get (node-by-id %) "node/lead-time-months") 0) 6))
                      (sort-by #(- (or (get (node-by-id %) "node/lead-time-months") 0))))
        gated (->> sortable-ids (filter gated?) (sort-by (comp - score)))]
    (println "== SD 診断 ==")
    (doseq [l broken]
      (println (str "  断裂中の reinforcing loop: " (get l "loop/id")
                    " — 断点 " (get l "loop/broken-at") " を塞ぐのが最優先")))
    (println "\n== TRACK A: 主戦線（gate 無し。層 → score 順に直列） ==")
    (doseq [id main] (println " " (node-line id)))
    (println "\n== TRACK B: 長リード enabler（lead ≥ 6mo。今日着手・並行） ==")
    (doseq [id enablers]
      (println (str "  " (pad id 32) " lead=" (get (node-by-id id) "node/lead-time-months")
                    "mo  score=" (fmt (score id))
                    "  [" (get (node-by-id id) "node/status") "] "
                    (get (node-by-id id) "node/label"))))
    (println "\n== TRACK C: gate 待ち（gate が開いたら score 順） ==")
    (doseq [[g members] (group-by #(get (node-by-id %) "node/gate") gated)]
      (println (str "  -- " g " opens-when: " (get (gate-by-id g) "gate/opens-when")))
      (doseq [id members] (println "   " (node-line id))))))

(defn -main [& args]
  (let [[mode query-str] args]
    (case mode
      "verify" (print-verify)
      "layers" (print-layers)
      "rank"   (print-rank)
      "loops"  (print-loops)
      "plan"   (print-plan)
      "q"      (println (pr-str (q query-str)))
      (do (println "usage: nbb scripts/labor-liberation-sd.cljs [verify|layers|rank|loops|plan|q '<datalog>']")
          (exit 1)))))

(apply -main *command-line-args*)

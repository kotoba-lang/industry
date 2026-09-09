#!/usr/bin/env nbb
;; placement.cljs — fleet-ci の配置。**移行期の実装**。
;;
;; ## この file が在る理由
;;
;; ADR-2608111721（オーナー判断 2026-08-11、選択肢 A）: **placement authority は
;; `murakumo.task.plan` に1本化する。** fleet-ci は gate 定義（何を検査するか）と
;; operator 側の credential 仕事だけを持つ。
;;
;; その切り替えは「両実装に同じ batch を通して assignments が一致することを実測して
;; から」行う、と ADR が決めている。**tick.cljs は load 時に `(-main)` を呼ぶので
;; library として require できず、比較しようがなかった** —— だから配置だけをここへ
;; 出した。これは切り替えの前提であって、それ自体が目的ではない。
;;
;; 比較: `nbb scripts/fleet-ci/placement-parity.cljs`
;;
;; ## LPT は murakumo に移っても失われない
;;
;; `murakumo.task.plan/assign` は tasks を**与えられた順に** fold し、各 task を
;; 「最も空いている eligible node」へ置く greedy である。LPT とは『重い順に並べてから
;; greedy』なので、**tasks を cost 降順に並べ替えるという host 側の1手**に還元できる。
;; したがって切り替えても Kotoba object を1つも足す必要が無く、EMA cost は
;; *placement の決定器* から *入力の順序付け器* へ降りるだけ。
;;
;; ## 注入するもの
;;
;; `live-load` は ssh を撃つので、この ns は自分で子プロセスを起動しない。
;; `assign` に `:load-fn`（host → load1 か nil）と `:log-fn` を渡す。渡さなければ
;; 負荷を未知として扱い（静的な cores/free-gb だけ）、何も print しない ——
;; **テストが ssh に依存しない**のはそのため。

(ns placement
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; slot（cap を満たすノードの席）

(defn slots
  "cap を満たすノードから (host 反復) の slot 列を作る。JVM 可能ノードは JVM gate の
  ために温存したいので、:node gate は非 JVM ノードを優先して埋める。"
  [nodes cap]
  (let [ns (filter #(and (:reachable? %) (contains? (set (:caps %)) cap)) nodes)
        ns (if (= cap :node)
             (sort-by #(if (contains? (set (:caps %)) :jvm) 1 0) ns)
             ns)]
    (vec (mapcat (fn [n] (repeat (:max-parallel n 1) n)) ns))))

;; ---------------------------------------------------------------------------
;; gate の重さ（EMA）

(def cost-path (path/join (os/homedir) ".itonami" "fleet-ci-cost.edn"))

(def default-cost-s
  ;; 実測が無い gate の初期値。桁が合っていればよい（LPT は順序しか使わない）。
  {:jvm-test 600 :shadow-test 600 :nbb-test 180 :nbb-script 120})

(defn read-costs []
  (try (reader/read-string (str (.readFileSync fs cost-path "utf8")))
       (catch :default _ {})))

(defn record-cost!
  "batch の実測秒を、その batch の各 gate に EMA で反映する。

  **これは上界であって gate 単体の時間ではない** — batch 内の gate は並列に走り、
  batch の所要時間は最も遅い 1 本で決まる。LPT は相対的な重さしか使わないので
  上界で足りる。絶対値として引用しないこと。"
  [gate-ids elapsed-s]
  (let [costs (read-costs)
        upd (reduce (fn [m id]
                      (let [{:keys [ema-s n] :or {ema-s elapsed-s n 0}} (get m id)]
                        (assoc m id {:ema-s (js/Math.round (+ (* 0.7 ema-s) (* 0.3 elapsed-s)))
                                     :n (inc n)
                                     :bound :batch-upper})))
                    costs gate-ids)]
    (try (.mkdirSync fs (path/join (os/homedir) ".itonami") #js {:recursive true})
         (.writeFileSync fs cost-path (str (pr-str upd) "\n"))
         (catch :default _ nil))))

(defn cost-of [costs w]
  (or (:ema-s (get costs (keyword (or (:id w) (:name w)))))
      (get default-cost-s (:gate w))
      180))

;; ---------------------------------------------------------------------------
;; ノードの速度

(defn node-speed
  "ノードの相対処理能力。大きいほど速い。

  load1 を引くのは、fleet のノードが CI 専用ではなく推論やマイニングと同居して
  いるため（probe.cljs の :max-parallel のコメントが同じ前提を書いている）。
  静的な cores だけで配ると、張り付いているノードに同じ本数が飛ぶ。"
  [n load]
  (let [cores (max 1 (:cores n 4))
        busy (min (double cores) (or load 0.0))
        free-cores (max 0.25 (- cores busy))
        disk-ok? (>= (:free-gb n 0) 5)]
    (* free-cores (if disk-ok? 1.0 0.25))))

;; ---------------------------------------------------------------------------
;; 配置

(defn required-cap [w]
  (or (:cap w)
      ;; The requirement is a property of the GATE KIND, not of each entry.
      ;; Measured 2026-08-19: `:shadow-test` was added without appearing here,
      ;; defaulted to :node, and was placed on a node with no JVM. The gate
      ;; refused to report a pass (exit 93, no test summary) rather than going
      ;; green on a machine that could not compile it -- the floor held, and
      ;; the placement was still wrong.
      (if (contains? #{:jvm-test :shadow-test} (:gate w)) :jvm :node)))

(defn assign-lpt
  "**参照実装。production では使わない。**

  fleet-ci が 2026-08-11 まで placement authority だったときの実装。ADR-2608111721
  の決定 1 で authority は `murakumo.task.plan` に移ったので、これは
  `placement-parity.cljs` が「murakumo の配置が退行していないか」を測るための
  oracle として残してある —— **production 経路は `assign`（murakumo へ委譲）。**

  2 実装が並んでいるように見えるが、authority は 1 つである。ここが production に
  戻ることは無い（戻すなら ADR を書き換えること）。

  round-robin ではなく **LPT**: 重い gate から順に、投入後の完了時刻が最小になる
  slot へ置く。slot の完了時刻は (積まれたコストの合計 / そのノードの速度) で、
  速度は cores・free-gb・live load1 から出す。

  `opts`:
    :load-fn  (fn [host] → load1 | nil)  既定 nil = 負荷を未知として扱う
    :log-fn   (fn [& xs])                既定 no-op
    :costs    EMA map                    既定 `(read-costs)`"
  ([work nodes] (assign-lpt work nodes nil))
  ([work nodes {:keys [load-fn log-fn costs]}]
   (let [costs (or costs (read-costs))
         log-fn (or log-fn (fn [& _] nil))
         by-cap (group-by required-cap work)
         hosts (into #{} (map :host) (filter #(and (:reachable? %) (seq (:caps %))) nodes))
         loads (if load-fn (into {} (map (fn [h] [h (load-fn h)])) hosts) {})
         chunk
         (fn [items slot-nodes]
           (if (empty? slot-nodes)
             (mapv (fn [w] (assoc w :unassigned true)) items)
             (let [slots' (vec (map-indexed
                                (fn [i n] {:idx i :node n
                                           :speed (node-speed n (get loads (:host n)))
                                           :load 0.0 :queue 0})
                                slot-nodes))
                   ;; 重い順（LPT）。同コストは名前順で決定的にする。
                   ordered (sort-by (juxt (comp - #(cost-of costs %)) :name) items)]
               (first
                (reduce
                 (fn [[acc st] w]
                   (let [c (cost-of costs w)
                         ;; 投入後の完了時刻が最小の slot
                         best (apply min-key
                                     (fn [s] (/ (+ (:load s) c) (max 0.25 (:speed s))))
                                     st)
                         best (update best :load + c)
                         best (update best :queue inc)]
                     [(conj acc (assoc w :node (:node best)
                                       :batch (dec (:queue best))
                                       :est-cost-s c))
                      ;; 選んだ slot を差し替えて戻す。`assoc … (count st)` は
                      ;; remove 後の長さを超えるので範囲外になる（conj が正しい）。
                      (conj (vec (remove #(= (:idx %) (:idx best)) st)) best)]))
                 [[] slots']
                 ordered)))))
         assigned (vec (mapcat (fn [cap]
                                 (chunk (get by-cap cap) (slots nodes cap)))
                               (sort (keys by-cap))))]
     (when (seq loads)
       (log-fn "placement: " (str/join
                              " " (map (fn [[h l]] (str h "=" (or l "?"))) (sort loads)))))
     (->> assigned (group-by :batch) (sort-by key) (map second)))))

;; ---------------------------------------------------------------------------
;; production 経路: murakumo.task.plan へ委譲（ADR-2608111721 決定 1）
;;
;; fleet-ci は murakumo の依存グラフを知らない。placement だけを別プロセスで解き、
;; EDN を stdin/stdout でやり取りする —— gate をノードで走らせるのと同じ形。

(def ^:private murakumo-rel "orgs/kotoba-lang/murakumo")

(def ^:private cp-cache-path
  (path/join (os/homedir) ".itonami" "murakumo-classpath.edn"))

(defn absolutize-classpath
  "`clojure -Spath` が返す相対 entry を、その command を実行した checkout 基準の
  絶対 path にする。placement shim は superproject root を cwd にして起動するため、
  `src` のような相対 entry をそのまま渡すと murakumo namespace を読めない。"
  [base cp]
  (->> (str/split cp (js/RegExp. (if (= path/delimiter ";") ";" ":")))
       (map (fn [entry]
              (if (path/isAbsolute entry)
                entry
                (path/resolve base entry))))
       (str/join path/delimiter)))

(defn classpath-cache-key
  "同じ deps.edn でも checkout が違えば相対 classpath の解決先が違うため、root も
  cache identity に含める。"
  [root st]
  (when st
    ;; v2: subprocess wrapper が :cwd を伝播する。v1 cache は root の deps を
    ;; murakumo のものとして保存し得たため、同じ checkout でも再利用しない。
    [:v2 (path/resolve root) (.-mtimeMs st) (.-size st)]))

(defn murakumo-classpath
  "murakumo の解決済み classpath。`clojure -Spath` は実測で約 10 秒かかるので、
  **checkout root + deps.edn の mtime+size をキーに cache する** —— tick は 5 分
  ごとに走るので毎回解決させない。root または deps.edn が変われば作り直す。

  `spawn-fn` は (fn [cmd args opts] → {:exit :out})。注入するのは、この ns が
  子プロセスの起動方法を持たないため（テストが clojure CLI に依存しない）。"
  [root spawn-fn]
  (let [murakumo-root (path/join root murakumo-rel)
        deps (path/join murakumo-root "deps.edn")
        st (try (.statSync fs deps) (catch :default _ nil))
        key* (classpath-cache-key murakumo-root st)
        cached (try (reader/read-string (str (.readFileSync fs cp-cache-path "utf8")))
                    (catch :default _ nil))]
    (if (and key* cached (= key* (:key cached)) (string? (:cp cached)))
      (:cp cached)
      (let [{:keys [exit out]} (spawn-fn "clojure" ["-Spath"]
                                         {:cwd murakumo-root})
            ;; -Spath は checkout 中の進捗を stderr に出すので最後の行だけ取る
            cp (when (zero? (or exit 1))
                 (last (remove str/blank? (str/split-lines (str out)))))]
        (when cp
          (let [abs (absolutize-classpath murakumo-root cp)]
            (try (.mkdirSync fs (path/join (os/homedir) ".itonami") #js {:recursive true})
                 (.writeFileSync fs cp-cache-path (pr-str {:key key* :cp abs}))
                 (catch :default _ nil))
            abs))))))

(defn- ->mk-node
  "fleet-ci の nodes.edn entry → murakumo の node。cap は :roles に写す
  （murakumo の `eligible?` が roles ⊆ node roles を検査する）。"
  [n]
  {:name (:host n) :host (:host n)
   :cores (:cores n 4)
   :roles (vec (map name (:caps n)))
   :mem-bytes (* (long (or (:free-gb n) 0)) 1024 1024 1024)
   :slots (:max-parallel n 1)
   :online? (boolean (:reachable? n))})

(defn assign
  "**production の placement。** work を murakumo.task.plan に配置させ、
  fleet-ci の batch 形（[[work…] [work…]]）に戻す。

  tasks は **cost 降順**で渡す。`murakumo.task.plan/assign` は与えられた順に fold
  して最も空いている eligible node へ置く greedy なので、これで LPT と同じ順序付けに
  なる（実測: `placement-parity.cljs`。101 gate × 8 node で placed/cap/unschedulable
  一致、makespan は fleet-ci 509s に対し murakumo 389s）。

  `opts`:
    :root      superproject root（既定 \".\"）
    :spawn-fn  (fn [cmd args opts] → {:exit :out})  **必須**
    :log-fn    (fn [& xs])
    :costs     EMA map"
  [work nodes {:keys [root spawn-fn log-fn costs]}]
  (let [root (or root ".")
        log-fn (or log-fn (fn [& _] nil))
        costs (or costs (read-costs))
        cp (murakumo-classpath root spawn-fn)
        _ (when-not cp
            (throw (ex-info "murakumo の classpath を解決できない — placement authority に届かない"
                            {:root root :hint "clojure -Spath が orgs/kotoba-lang/murakumo で通るか"})))
        by-id (into {} (map (fn [w] [(str (or (:id w) (:name w))) w])) work)
        tasks (vec (sort-by (juxt (comp - :cost) :id)
                            (for [w work]
                              {:id (str (or (:id w) (:name w)))
                               :placement {:roles [(name (required-cap w))]}
                               :cost (cost-of costs w)})))
        payload (pr-str {:nodes (mapv ->mk-node nodes) :tasks tasks :opts {}})
        {:keys [exit out]} (spawn-fn "nbb"
                                     ["--classpath" cp
                                      (path/join root "scripts" "fleet-ci" "placement-shim.cljs")
                                      "--resources" (path/join root murakumo-rel "resources")]
                                     {:cwd root :input payload})
        edn (when (zero? (or exit 1))
              (try (reader/read-string
                    (last (remove str/blank? (str/split-lines (str out)))))
                   (catch :default _ nil)))]
    (when-not edn
      (throw (ex-info "placement shim が結果を返さない" {:exit exit :tail (apply str (take-last 400 (str out)))})))
    (let [{:keys [assignments unschedulable]} edn
          placed (for [a assignments
                       :let [w (get by-id (:task-id a))]
                       :when w]
                   (assoc w :node (first (filter #(= (:host %) (:node a)) nodes))
                          :batch (:wave a)
                          :est-cost-s (cost-of costs w)))
          unplaced (for [u unschedulable
                         :let [w (get by-id (:task-id u))]
                         :when w]
                     (assoc w :unassigned true :unschedulable-reason (:detail u)))]
      (when (seq unschedulable)
        (log-fn "placement: unschedulable" (count unschedulable)
                (str/join " " (map :task-id unschedulable))))
      (->> (concat placed unplaced)
           (group-by :batch)
           (sort-by (fn [[k _]] (or k 0)))
           (map second)))))

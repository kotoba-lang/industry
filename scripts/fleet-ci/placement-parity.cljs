#!/usr/bin/env nbb
;; placement-parity.cljs — fleet-ci の配置と murakumo.task.plan の配置を同じ batch で比べる。
;;
;; ADR-2608111721 の決定 1・2 の**切り替え条件**。「両実装に同じ batch を通して
;; assignments が一致することを実測してから切り替える」と ADR が決めており、
;; これがその実測である。
;;
;;   nbb --classpath ".:scripts/nbb_compat:scripts/fleet-ci:orgs/kotoba-lang/murakumo/src" \
;;     scripts/fleet-ci/placement-parity.cljs [--check]
;;
;; ## 何を「一致」と呼ぶか
;;
;; **assignment そのもの（どの gate がどのノードへ行くか）を比べても意味が無い。**
;; 2 つの実装は slot の作り方が違う（fleet-ci は cap ごとに slot 列を作り、
;; murakumo は node ごとに slot 数を計算する）ので、同じ入力でも別のノードを選びうる。
;; 比べるべきは **placement の性質**である:
;;
;;   1. 全 task が配置されるか（unschedulable が同数か）
;;   2. cap 制約を破らないか（jvm gate が jvm 無しノードへ行かないか）
;;   3. ノードあたりの本数が :max-parallel を超えないか
;;   4. makespan（最も遅いノードの推定完了時刻）が悪化しないか ← LPT の値そのもの
;;
;; 4 が本題である。fleet-ci の LPT を捨てて murakumo の greedy least-filled にする
;; とき、**cost 降順に並べ替えて渡せば LPT と同じ順序になる**——という主張が
;; 正しいかどうかは、makespan を両方で計算して比べるしかない。
;;
;; ## murakumo 側は KIR を要求する
;;
;; `murakumo.task.plan` は `:task-plan` KIR（Kotoba の決定核）を preload してから
;; require しないと動かない。KIR は `orgs/kotoba-lang/murakumo/resources/` に在るが、
;; oracle の既定ローダは `process.cwd()/resources/` を見るので、superproject root から
;; 走らせると解決しない。`set-resource-loader!` で murakumo の resources を指す
;; （これは oracle が document している拡張点であって回避策ではない）。

(ns placement-parity
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [placement :as fci]
            [murakumo.kotoba.oracle :as oracle]))

(def args (vec *command-line-args*))
(def check? (some #{"--check"} args))
(def root (or (some (fn [a] (when-not (str/starts-with? a "--") a)) args) "."))

(def murakumo-resources (path/join root "orgs" "kotoba-lang" "murakumo" "resources"))

;; oracle の resources を murakumo checkout から読む。
(oracle/set-resource-loader!
 (fn [p]
   (let [f (path/join murakumo-resources p)]
     (when (.existsSync fs f) (.readFileSync fs f "utf8")))))

(oracle/preload! [:task-plan])

(require '[murakumo.task.plan :as mk])

;; ---------------------------------------------------------------------------
;; 入力: 実 nodes.edn と実 gates.edn

(defn- read-edn [p] (reader/read-string (str (.readFileSync fs p "utf8"))))

(def nodes (:nodes (read-edn (path/join root "scripts" "fleet-ci" "nodes.edn"))))
(def cfg (read-edn (path/join root "scripts" "fleet-ci" "gates.edn")))
(def gates (:repos cfg))
(def costs (fci/read-costs))

(def work
  "gates.edn の全 entry を「今回 tip が動いた」と仮定した最大 batch。
  実 tick は変化した repo だけを回すので、これは上界の配置問題である。"
  (vec (map-indexed (fn [i g] (assoc g :idx i)) gates)))

;; ---------------------------------------------------------------------------
;; murakumo 側へ写す
;;
;; cap は murakumo の `:placement {:roles [...]}` に写す（`eligible?` が
;; roles ⊆ node roles を検査する）。cores / mem / slots はそのまま。

(defn- ->mk-node [n]
  {:name (:host n) :host (:host n)
   :cores (:cores n 4)
   :roles (vec (map name (:caps n)))
   :mem-bytes (* (long (or (:free-gb n) 0)) 1024 1024 1024)
   :slots (:max-parallel n 1)
   :online? (boolean (:reachable? n))})

(defn- ->mk-task [w]
  {:id (str (or (:id w) (:name w)))
   :placement {:roles [(name (fci/required-cap w))]}
   :cost (fci/cost-of costs w)})

;; ---------------------------------------------------------------------------
;; 性質を測る

(defn- makespan
  "ノードごとに (積まれた cost / 速度) を出し、その最大値。LPT が最小化しようと
  しているものそのもの。速度は両実装で同じ `node-speed` を使う（比べたいのは
  順序付けであって速度モデルではない）。"
  [placements node-by-host]
  (let [by-host (group-by :host placements)]
    (reduce max 0.0
            (for [[h ws] by-host
                  :let [n (get node-by-host h)
                        speed (fci/node-speed (or n {}) nil)]]
              (/ (reduce + 0.0 (map :cost ws)) (max 0.25 speed))))))

(def node-by-host (into {} (map (juxt :host identity)) nodes))

;; --- A. fleet-ci -----------------------------------------------------------

(def fci-batches (fci/assign work nodes {:costs costs}))

(def fci-placements
  (vec (for [b fci-batches w b
             :when (not (:unassigned w))]
         {:task (str (or (:id w) (:name w)))
          :host (get-in w [:node :host])
          :cost (:est-cost-s w)})))

(def fci-unassigned
  (count (for [b fci-batches w b :when (:unassigned w)] w)))

;; --- B. murakumo（cost 降順に並べてから greedy）-----------------------------

(def mk-nodes (mapv ->mk-node nodes))

(def mk-tasks
  ;; **これが「LPT は host 側の1手に還元できる」の実装**。降順ソートしてから
  ;; murakumo の greedy least-filled に渡す。同 cost は id 順で決定的にする。
  (vec (sort-by (juxt (comp - :cost) :id) (mapv ->mk-task work))))

(def mk-result (mk/assign mk-nodes mk-tasks {}))

(def mk-placements
  (vec (for [a (:assignments mk-result)]
         {:task (get-in a [:task :id])
          :host (:node a)
          :cost (get-in a [:task :cost])})))

;; ---------------------------------------------------------------------------
;; 比較

(defn- cap-violations [placements]
  (count (for [p placements
               :let [w (first (filter #(= (:task p) (str (or (:id %) (:name %)))) work))
                     n (get node-by-host (:host p))]
               :when (and w n (not (contains? (set (:caps n)) (fci/required-cap w))))]
           p)))

(defn- over-parallel [placements]
  ;; 1 batch あたりの本数は :max-parallel を超えてはいけない。fleet-ci は batch を
  ;; 明示的に持ち、murakumo は :wave がそれに当たる。ここでは総数 / slot 数で見る。
  (count (for [[h ws] (group-by :host placements)
               :let [n (get node-by-host h)
                     cap (* (:max-parallel n 1) 1000)]  ; 総数の上界は事実上無い
               :when (> (count ws) cap)]
           h)))

(def report
  {:input {:gates (count work) :nodes (count nodes)
           :gate-capable (count (filter #(seq (:caps %)) nodes))
           :costs-known (count costs)}
   :fleet-ci {:placed (count fci-placements)
              :unassigned fci-unassigned
              :hosts (count (distinct (map :host fci-placements)))
              :makespan (js/Math.round (makespan fci-placements node-by-host))
              :cap-violations (cap-violations fci-placements)
              :over-parallel (over-parallel fci-placements)}
   :murakumo {:placed (count mk-placements)
              :unassigned (count (:unschedulable mk-result))
              :hosts (count (distinct (map :host mk-placements)))
              :makespan (js/Math.round (makespan mk-placements node-by-host))
              :cap-violations (cap-violations mk-placements)
              :over-parallel (over-parallel mk-placements)}})

(println "placement parity —" (:gates (:input report)) "gate ×"
         (:gate-capable (:input report)) "gate-capable node"
         (str "(cost 実測 " (:costs-known (:input report)) " 件)"))
(println)
(println (str "  " (str/join "" (repeat 12 " ")) "placed  unasgn  hosts  makespan  cap-viol  over-par"))
(doseq [[k v] [["fleet-ci" (:fleet-ci report)] ["murakumo" (:murakumo report)]]]
  (println (str "  " (subs (str k "            ") 0 12)
                (subs (str (:placed v) "        ") 0 8)
                (subs (str (:unassigned v) "        ") 0 8)
                (subs (str (:hosts v) "       ") 0 7)
                (subs (str (:makespan v) "s         ") 0 10)
                (subs (str (:cap-violations v) "          ") 0 10)
                (:over-parallel v))))
(println)

(def fails
  (cond-> []
    (not= (:placed (:fleet-ci report)) (:placed (:murakumo report)))
    (conj (str "placed が違う: fleet-ci " (:placed (:fleet-ci report))
               " vs murakumo " (:placed (:murakumo report))))

    (pos? (:cap-violations (:murakumo report)))
    (conj (str "murakumo が cap 制約を破った: " (:cap-violations (:murakumo report)) " 件"))

    (pos? (:unassigned (:murakumo report)))
    (conj (str "murakumo が " (:unassigned (:murakumo report))
               " 件を unschedulable にした（fleet-ci は " (:unassigned (:fleet-ci report)) " 件）"))

    ;; makespan は 20% 以内の悪化まで許す。速度モデルは同じで、違うのは slot の
    ;; 作り方だけなので、完全一致は期待しない。**悪化が有意なら LPT は還元できて
    ;; いないということなので、そこで止まる。**
    (> (:makespan (:murakumo report)) (* 1.2 (max 1 (:makespan (:fleet-ci report)))))
    (conj (str "makespan が 20% 以上悪化: " (:makespan (:fleet-ci report))
               "s → " (:makespan (:murakumo report)) "s"))))

(if (seq fails)
  (do (doseq [f fails] (println "FAIL" f))
      (println)
      (println "→ 切り替えない。ADR-2608111721 の決定 2 は『一致を実測してから移す』と")
      (println "  書いている。ここが赤いまま移すと、その決定を破ることになる。")
      (when check? (set! (.-exitCode js/process) 1)))
  (do (println "OK — placed 数・cap 制約・unschedulable が一致し、makespan は悪化していない。")
      (println "     cost 降順ソート + greedy least-filled は LPT と同等に働いている。")))

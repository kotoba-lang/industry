#!/usr/bin/env bb
;; pregel_planner.clj — LangGraph Pregel ベースの人生経路プランナー
;; (pregel_planner.py から babashka へ移植, ADR-0013 clj-agent-stack。元の jsonld は edn へ移行 ADR-0010)
;;
;; 設計:
;;   - vertex     = 活動領域 (physics / fiction / research / infra / health / relations / spirit / social / did)
;;   - super-step = τ ∈ {day, week, month, year, decade, generation, cosmos}
;;   - message    = {:I :R :O} (領域間の影響)
;;   - state      = 各 τ・各 vertex の W(τ) と推奨アクション
;;
;; W(τ) = α·I + β·R + γ·O。重み α/β/γ は profile.edn の :kj/valueFunction :weights。
;; 元 .py は langgraph.StateGraph で BSP を回していたが、実体は「終了条件まで super-step を進める」
;; 逐次ループ。各 super-step 内では DOMAINS 順に vertex を更新し、隣接 vertex の inbox に送信する
;; (同一 super-step 内で後続 vertex が先行 vertex のメッセージを受け取る = .py と同一セマンティクス)。
(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[babashka.fs :as fs])

(def ^:private here (fs/parent (fs/absolutize *file*)))
(defn ^:private load-edn [f] (edn/read-string (slurp (str (fs/file here f)))))

(def profile    (load-edn "profile.edn"))
(def activities (load-edn "activities.edn"))
(def roadmap    (load-edn "roadmap.edn"))

(def tau-order ["day" "week" "month" "year" "decade" "generation" "cosmos"])
(def domains ["physics" "fiction" "research" "infra"
              "health" "relations" "spirit" "social" "did"])

(def influence
  {"physics"   ["fiction" "research" "spirit"]
   "fiction"   ["relations" "spirit" "infra"]
   "research"  ["physics" "infra" "health"]
   "infra"     ["research" "fiction" "did"]
   "health"    ["research" "relations" "spirit"]
   "relations" ["fiction" "health" "spirit" "social"]
   "spirit"    ["physics" "fiction" "relations" "social"]
   "social"    ["relations" "spirit" "did" "infra"]
   "did"       ["infra" "social" "research"]})

(def weights (get-in profile [:kj/valueFunction :weights])) ; {:alpha :beta :gamma}

(defn ^:private clamp [x] (max -1.0 (min 1.0 (double x))))
(defn ^:private W [vs]
  (+ (* (:alpha weights) (:I vs)) (* (:beta weights) (:R vs)) (* (:gamma weights) (:O vs))))

(defn ^:private init-vertices []
  ;; activities.edn からの impact を集約して初期値に。
  ;; 注: 元 .py は item["impact"] を参照していたが activities のキーは :kj/impact のため
  ;;     実際には常に空 (no-op) だった。挙動を厳密に保つため :impact を参照する (= 常に nil)。
  ;;     impact 集約を有効化したい場合は :kj/impact に変更する。
  (let [base (reduce (fn [m item]
                       (let [d (:domain item)
                             imp (:impact item)]
                         (if (and d (contains? m d) imp)
                           (-> m
                               (update-in [d :I] + (:I imp 0.0))
                               (update-in [d :R] + (:R imp 0.0))
                               (update-in [d :O] + (:O imp 0.0)))
                           m)))
                     (into {} (map (fn [d] [d {:domain d :I 0.0 :R 0.0 :O 0.0
                                               :actions [] :inbox []}]) domains))
                     (:itemListElement activities))]
    (reduce-kv (fn [m d vs]
                 (assoc m d (-> vs
                                (update :I #(Math/tanh %))
                                (update :R #(Math/tanh %))
                                (update :O #(Math/tanh %)))))
               {} base)))

(defn ^:private goals-for-tau [tau]
  (or (first (filter #(= (:tau %) tau) (:itemListElement roadmap)))
      {:goals [] :drivers {}}))

(defn ^:private step-vertex!
  "1 super-step での 1 vertex 更新 (Pregel の compute() 相当)。vertices は atom。"
  [vertices tau domain]
  (let [vs (get @vertices domain)
        ;; 受信メッセージを統合
        i+ (reduce + 0.0 (map #(* 0.10 (:I % 0.0)) (:inbox vs)))
        r+ (reduce + 0.0 (map #(* 0.15 (:R % 0.0)) (:inbox vs)))
        o+ (reduce + 0.0 (map #(* 0.10 (:O % 0.0)) (:inbox vs)))
        ;; τ レイヤーの目標から駆動
        goals (:goals (goals-for-tau tau))
        relevant (filter (fn [g]
                           (or (str/includes? (str/lower-case g) domain)
                               (and (= domain "spirit") (str/includes? g "霊"))))
                         goals)
         I (clamp (+ (:I vs) i+))
        R (clamp (+ (:R vs) r+))
        O (clamp (+ (:O vs) o+ (* 0.05 (count relevant))))
        actions (into (:actions vs) relevant)]
    (swap! vertices assoc domain
           (assoc vs :I I :R R :O O :actions actions :inbox []))
    ;; 隣接 vertex にメッセージ送信
    (let [out {:I (* I 0.2) :R (* R 0.2) :O (* O 0.2)}]
      (doseq [nb (influence domain)]
        (swap! vertices update-in [nb :inbox] conj out)))))

(defn run
  "全 τ を horizon 分だけ進める。tau-start から開始。"
  [tau-start horizon]
  (let [vertices (atom (init-vertices))
        st (atom {:tau tau-start
                  :tau-idx (.indexOf tau-order tau-start)
                  :super-step 0
                  :w-history []})]
    (loop []
      ;; --- 1 super-step (全 vertex を 1 τ 分進める) ---
      (let [tau (:tau @st)]
        (doseq [d domains] (step-vertex! vertices tau d))
        (let [snap (into {} (map (fn [d] [d (W (get @vertices d))]) domains))
              total (/ (reduce + (vals snap)) (count domains))
              snap (assoc snap "_total" total "_tau" tau)]
          (swap! st update :w-history conj snap)))
      (swap! st update :super-step inc)
      ;; τ を昇順に進める (3 super-step ごと)
      (when (and (zero? (mod (:super-step @st) 3))
                 (< (:tau-idx @st) (dec (count tau-order))))
        (swap! st update :tau-idx inc)
        (swap! st assoc :tau (nth tau-order (:tau-idx @st))))
      ;; should_continue
      (if (or (>= (:super-step @st) horizon)
              (and (= (:tau @st) "cosmos") (>= (:super-step @st) (dec horizon))))
        {:vertices @vertices :state @st}
        (recur)))))

(defn report [{:keys [vertices state]}]
  (let [out (StringBuilder.)
        add (fn [s] (.append out s) (.append out "\n"))]
    (add "# Pregel 計画レポート\n")
    (add (format "super-steps: %d  final τ: %s\n" (:super-step state) (:tau state)))
    (add "## W(τ) 推移 (平均)\n")
    (doseq [snap (:w-history state)]
      (add (str (format "- τ=%s  W̄=%+.3f  " (get snap "_tau") (double (get snap "_total")))
                (str/join "  " (map #(format "%s=%+.2f" % (double (get snap %))) domains)))))
    (add "\n## 各 domain の推奨アクション\n")
    (doseq [d domains]
      (let [vs (get vertices d)]
        (when (seq (:actions vs))
          (add (str "### " d))
          (doseq [a (distinct (:actions vs))]
            (add (str "- " a))))))
    (str out)))

;; ── CLI ──────────────────────────────────────────────────────────────────
(defn ^:private parse-args [args]
  (loop [a args, m {:tau "day" :horizon 12 :out nil}]
    (cond
      (empty? a) m
      (= (first a) "--tau")     (recur (drop 2 a) (assoc m :tau (second a)))
      (= (first a) "--horizon") (recur (drop 2 a) (assoc m :horizon (parse-long (second a))))
      (= (first a) "--out")     (recur (drop 2 a) (assoc m :out (second a)))
      :else (recur (rest a) m))))

(let [{:keys [tau horizon out]} (parse-args *command-line-args*)
      text (report (run tau horizon))]
  (if out
    (do (spit out text) (println (str "wrote " out)))
    (println text)))

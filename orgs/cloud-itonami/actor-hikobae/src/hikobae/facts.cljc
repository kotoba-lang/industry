(ns hikobae.facts
  "観測事実の読み込みと、**出所の検査**。

   この名前空間の存在理由は読み込みではなく検査のほうにある。
   出所の無い数がモデルに入るのを構造的に止める。root CLAUDE.md:
   『測れなかった検査が、測って問題が無かった検査と同じ値を返す』の
   データ側の対応物 —— 出所の宣言が無いパラメータは、正しい値かもしれないが
   **検査していない値**であり、両者は出力から区別できなければならない。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]))

(defn- load-edn [resource-name]
  (with-open [r (io/reader (or (io/resource resource-name)
                               (throw (ex-info "hikobae.facts: resource not found"
                                               {:resource resource-name}))))]
    (edn/read (java.io.PushbackReader. r))))

(defn kumamoto-2026 [] (load-edn "kumamoto-2026.facts.edn"))
(defn operations []    (load-edn "recovery-operations.edn"))
(defn world []         (load-edn "world-regimes.edn"))

;; ---------------------------------------------------------------------------
;; 観測の取り出し
;; ---------------------------------------------------------------------------

(defn observation
  "スカラ観測を id で引く。無ければ nil（0 を返さない）。"
  [facts id]
  (first (filter #(= id (:id %)) (:event/observations facts))))

(defn observed-value
  "観測値。**数値でないもの（:under-investigation 等）はそのまま返す。**
   数値へ強制しない —— 未確定を 0 に潰すのがこのモデルで最も避けたい誤り。"
  [facts id]
  (:value (observation facts id)))

(defn observed-number
  "数値の観測値。数値でなければ nil。呼び出し側に「測れていない」を伝える。"
  [facts id]
  (let [v (observed-value facts id)]
    (when (number? v) v)))

(defn trajectory
  "日付つき系列を [[day value] ...] で返す。"
  [facts id]
  (->> (get-in facts [:event/trajectories id :points])
       (map (juxt :day :value))
       (sort-by first)
       vec))

(defn unmeasured-ids
  "この event で明示的に『測れていない』と宣言されている量。"
  [facts]
  (set (map :id (:event/unmeasured facts))))

;; ---------------------------------------------------------------------------
;; 出所の検査 —— gate 本体
;; ---------------------------------------------------------------------------

(defn check-provenance
  "パラメータ集合の全キーが `provenance` に宣言されているかを検査する。

   返り値 {:undeclared #{..} :assumed-without-basis #{..} :ok? bool}。

   ⚠ :sim/* は模擬の設定であって領域の量ではないので対象外。それ以外の
   キーは、宣言が無ければ **通さない**。『たぶん妥当な値』と
   『出所を確認した値』の区別を出力に残すのがこの関数の仕事。"
  [params provenance]
  (let [domain-keys (set (remove #(= "sim" (namespace %)) (keys params)))
        declared (set (keys provenance))
        undeclared (set/difference domain-keys declared)
        assumed-without-basis
        (set (for [[k v] provenance
                   :when (and (= :assumed (:kind v))
                              (contains? domain-keys k)
                              (not (:basis v)))]
               k))]
    {:undeclared undeclared
     :assumed-without-basis assumed-without-basis
     :ok? (and (empty? undeclared) (empty? assumed-without-basis))
     :counts (frequencies (keep #(:kind (get provenance %)) domain-keys))}))

(defn assert-provenance!
  "検査に落ちたら投げる。黙って通さない。"
  [params provenance]
  (let [r (check-provenance params provenance)]
    (when-not (:ok? r)
      (throw (ex-info "hikobae.facts: parameters without declared provenance" r)))
    r))

;; ---------------------------------------------------------------------------
;; 業務の依存グラフ
;; ---------------------------------------------------------------------------

(defn operation [ops id]
  (first (filter #(= id (:id %)) (:taxonomy/operations ops))))

(defn downstream-of
  "`id` に（推移的に）ゲートされている業務の集合。
   『この待ち行列を1本止めると、何が止まるか』への答え。"
  [ops id]
  (let [all (:taxonomy/operations ops)
        direct (fn [x] (set (map :id (filter #(contains? (set (:gated-by %)) x) all))))]
    (loop [frontier (direct id) seen #{}]
      (if (empty? frontier)
        seen
        (let [nxt (set/difference (into #{} (mapcat direct frontier)) seen)]
          (recur nxt (set/union seen frontier)))))))

(defn binding-resources
  "各業務の律速資源 → その資源が律速している業務の集合。"
  [ops]
  (->> (:taxonomy/operations ops)
       (filter :binding-resource)
       (group-by :binding-resource)
       (into {} (map (fn [[k vs]] [k (set (map :id vs))])))))

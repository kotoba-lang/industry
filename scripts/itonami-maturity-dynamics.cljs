#!/usr/bin/env nbb
;; scripts/itonami-maturity-dynamics.cljs — cloud-itonami 成熟度スコア + 依存伝播 +
;; leverage 順位 + XMILE stock-flow シミュレーション。ADR-2608052000。
;;
;;   nbb --classpath ".:scripts/nbb_compat:<dynamics/src>:<org-oasis-open-xmile/src>" \
;;     scripts/itonami-maturity-dynamics.cljs \
;;     --evidence manifest/itonami-maturity-evidence.edn \
;;     --taxonomy manifest/repo-taxonomy.edn \
;;     --out 90-docs/system-dynamics/itonami-maturity.datoms.edn
;;
;; 3 層に分かれている。混ぜないこと:
;;
;;   (1) 観測  — itonami-maturity-scan.cljs が測った生の証拠。ここでは読むだけ。
;;   (2) スコア — 観測を [0,1] に写す。**写像の重みは判断であって測定ではない**ので
;;                 :weights テーブル 1 箇所に集め、出力にもそのまま載せる。
;;   (3) 力学  — 依存伝播（不動点）と leverage（摂動）と XMILE 軌道。
;;                 モデルであってデータではない。パラメタは出力に明記する。
;;
;; 測っていないもの（未 checkout repo、taxonomy に無い repo）は nil を書く。
;; 0 で埋めると「証拠が無い」が「悪い」に化ける（ADR-2607203000）。

(ns itonami-maturity-dynamics
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [dynamics.core :as dyn]
            [xmile.model :as xm]
            [xmile.execute :as xex]
            ["fs" :as fs]
            ["path" :as npath]))

;; ---------------------------------------------------------------- args

(defn- parse-args [args]
  (loop [a args m {:evidence "manifest/itonami-maturity-evidence.edn"
                   :taxonomy "manifest/repo-taxonomy.edn"
                   :out "90-docs/system-dynamics/itonami-maturity.datoms.edn"
                   :report "90-docs/system-dynamics/itonami-maturity-report.md"
                   :top 40}]
    (if (empty? a) m
        (let [[k v & more] a]
          (case k
            "--evidence" (recur more (assoc m :evidence v))
            "--taxonomy" (recur more (assoc m :taxonomy v))
            "--out"      (recur more (assoc m :out v))
            "--report"   (recur more (assoc m :report v))
            "--top"      (recur more (assoc m :top (js/parseInt v 10)))
            "--explain"  (recur more (update m :explain (fnil conj []) v))
            (recur (rest a) m))))))

(def opts (parse-args (vec (.slice (.-argv js/process) 2))))

(defn- slurp* [p] (try (.toString (.readFileSync fs p) "utf8") (catch :default _ nil)))
(defn- resolve* [p] ((.-resolve npath) (.cwd js/process) p))

;; ---------------------------------------------------------------- load

(defn- read-edn-file [p what]
  (let [t (or (slurp* p) (throw (ex-info (str what " not found") {:path p})))]
    (edn/read-string t)))

(def evidence (read-edn-file (resolve* (:evidence opts)) "evidence"))
(def taxonomy (read-edn-file (resolve* (:taxonomy opts)) "taxonomy"))

(def kind-by-path
  (into {} (keep (fn [m] (when-let [p (:repo/path m)] [p (:repo/kind m)])) taxonomy)))

(def by-path (into {} (map (juxt :repo/path identity) evidence)))

;; ---------------------------------------------------------------- (2) score
;;
;; 半飽和定数 k: sat(x,k) = x/(x+k) は x=k でちょうど 0.5 になる。k を発明せず
;; **fleet 自身の分布の中央値（正の値のみ）** から取る。「fleet の中央値の repo が
;; その軸で 0.5」という意味になり、外部から持ち込んだ閾値ではなくなる。
;; 中央値が 0（＝過半数が 0）の軸は正値の中央値を使う。

(defn- median [xs]
  (let [v (vec (sort xs)) n (count v)]
    (cond (zero? n) nil
          (odd? n) (nth v (quot n 2))
          :else (/ (+ (nth v (dec (quot n 2))) (nth v (quot n 2))) 2))))

(defn- positive-median [xs]
  ;; 整数で持つ（basis point 演算に float の中央値を混ぜない）。
  (when-let [m (median (filter pos? xs))] (js/Math.floor m)))

;; --- 算術は Kotoba カーネルの写し ------------------------------------------
;; 正本は 90-docs/system-dynamics/kotoba/itonami_maturity_kernel.kotoba。
;; ここはその **参照実装** であって定義ではない。
;; scripts/itonami-maturity-kernel-parity.cljs が全 repo で 1 の位まで
;; 一致することを検査する。食い違ったらカーネルが正しい。
;;
;; 単位は basis point（10000 = 1.0）。quot 相当は Math.trunc（i64 の quot は
;; 0 方向切り捨て。ここは全て非負なので floor と同じだが trunc で揃える）。

(def BP 10000)
(defn- q [a b] (js/Math.trunc (/ a b)))
(defn- clamp-bp [x] (if (< x 0) 0 (if (< BP x) BP x)))
(defn- sat-bp [x k]
  (if (or (nil? k) (< k 1) (nil? x) (< x 1)) 0 (q (* BP x) (+ x k))))
(defn- decay-bp [half-life elapsed]
  ;; sat-bp の流用ではない。第 2 引数は経過量で、0 は「今さっき = 満点」。
  ;; カーネルの decay-bp と同じ（実測バグの再発防止はカーネル側 docstring 参照）。
  (if (or (nil? half-life) (< half-life 1) (nil? elapsed) (< elapsed 0))
    0
    (q (* BP half-life) (+ half-life elapsed))))
(defn- ratio-bp [num den] (if (or (nil? den) (< den 1)) 0 (clamp-bp (q (* BP num) den))))
(defn- bool-bp [b] (if b BP 0))
(defn- axis3-bp [a b c] (q (+ a b c) 3))
(defn- axis4-bp [a b c d] (q (+ a b c d) 4))

(defn- tombstone?
  "tombstone 判定は **repo-taxonomy を正本にする**（manifest/repo-taxonomy.edn の
   :repo/kind \"tombstone\"）。scan 側のファイル名ヒューリスティックは補助にとどめる —
   実測: scan の `NOT-MIGRATED` 完全一致は cloud-itonami/ads の
   `NOT-MIGRATED-VENDOR-REGULATORY.md.edn` を取り逃がし、taxonomy が拾っていた
   2 件を 0 件と報告していた。分類は分類の正本に任せる。"
  [e]
  (or (= "tombstone" (kind-by-path (:repo/path e)))
      (:repo/tombstone? e)))

(def scored-evidence
  "スコア対象。未 checkout（:repo/present? false）と tombstone は除外し、別枠で報告する。"
  (vec (remove (fn [e] (or (not (:repo/present? e)) (tombstone? e))) evidence)))

(def k-consts
  {:src     (positive-median (map #(:src/bytes % 0) scored-evidence))
   :test    (positive-median (map #(:test/bytes % 0) scored-evidence))
   :cite    (positive-median (map #(:ingest/citation-count % 0) scored-evidence))
   :readme  (positive-median (map #(:doc/readme-bytes % 0) scored-evidence))
   :adr     (positive-median (map #(:doc/adr-count % 0) scored-evidence))})

(def component-total 7) ; component-patterns の数（operation/governor/store/phase/sim/facts/ledger）

;; :m/fresh の半減期に 180 日を使う。これは発明ではなく、この workspace が
;; 既に :stale の閾値として使っている値（scripts/itonami-fleet-audit.cljs の
;; :status :stale = >180d）。指数減衰にして崖を作らない。
(def stale-days 180)

(def now-ms (.now js/Date))

(defn- days-since [iso]
  (when (and iso (string? iso))
    (let [t (.parse js/Date iso)]
      (when-not (js/isNaN t) (/ (- now-ms t) 86400000.0)))))

(defn- axes-of
  "軸ごとの basis point。未測定は nil（0 ではない）。"
  [e]
  (let [d (days-since (:git/last-commit e))]
    {:m/substrate (sat-bp (:src/bytes e 0) (:src k-consts))
     :m/test      (sat-bp (:test/bytes e 0) (:test k-consts))
     :m/governed  (ratio-bp (:component/count e 0) component-total)
     :m/ingest    (sat-bp (:ingest/citation-count e 0) (:cite k-consts))
     :m/docs      (axis3-bp (sat-bp (:doc/readme-bytes e 0) (:readme k-consts))
                            (ratio-bp (:doc/adr-count e 0) (:adr k-consts))
                            (bool-bp (:doc/has-operator-quickstart? e)))
     :m/surface   (axis4-bp (bool-bp (pos? (:surface/demo-file-count e 0)))
                            (bool-bp (:surface/has-cron-workflow? e))
                            (bool-bp (:doc/has-business-model? e))
                            (bool-bp (:doc/has-pricing? e)))
     ;; 双曲減衰 10000*180/(180+d)。exp は kotoba の admitted builtins に無いので
     ;; カーネル側で書けない — 式を言語に合わせた。180 日で 0.5 は同じ、裾は厚い。
     ;; git log が取れなかった repo は nil（0 ではない）。
     :m/fresh     (when d (decay-bp stale-days (js/Math.floor d)))}))

;; --- 重み: これは **判断であって測定ではない** ---------------------------------
;; repo の kind によって「成熟」の意味が違う。library に governor が無いのは
;; 欠陥ではなく category mismatch（itonami-fleet-audit が既に同じ区別をしている）。
;; 重みを 1 テーブルに集めて出力にも載せるのは、後から異論を出せるようにするため。
(def weights
  {"actor"    {:m/substrate 2000 :m/test 1500 :m/governed 2500 :m/ingest 1500 :m/docs 1000 :m/surface 1000 :m/fresh  500}
   "organism" {:m/substrate 2000 :m/test 1500 :m/governed 2500 :m/ingest 1500 :m/docs 1000 :m/surface 1000 :m/fresh  500}
   "service"  {:m/substrate 2500 :m/test 2000 :m/governed  500 :m/ingest  500 :m/docs 1500 :m/surface 2500 :m/fresh  500}
   "app"      {:m/substrate 2500 :m/test 2000 :m/governed    0 :m/ingest  500 :m/docs 1500 :m/surface 3000 :m/fresh  500}
   "lib"      {:m/substrate 3000 :m/test 3000 :m/governed    0 :m/ingest  500 :m/docs 2500 :m/surface    0 :m/fresh 1000}
   "corpus"   {:m/substrate 1000 :m/test 1000 :m/governed    0 :m/ingest 4500 :m/docs 2500 :m/surface    0 :m/fresh 1000}
   "blueprint-only"      {:m/substrate 1000 :m/test  500 :m/governed  500 :m/ingest 2000 :m/docs 5000 :m/surface  500 :m/fresh 500}
   "organisation-record" {:m/substrate 1000 :m/test  500 :m/governed    0 :m/ingest 4500 :m/docs 3500 :m/surface    0 :m/fresh 500}
   "docs"     {:m/substrate  500 :m/test    0 :m/governed    0 :m/ingest 2000 :m/docs 6500 :m/surface  500 :m/fresh  500}
   :default   {:m/substrate 2500 :m/test 2000 :m/governed  500 :m/ingest 1500 :m/docs 2500 :m/surface  500 :m/fresh  500}})

;; 各行は 10000 bp に足りていること（重みの取り違えを黙って通さない）。
(doseq [[k w] weights]
  (let [t (reduce + 0 (vals w))]
    (when-not (= t 10000)
      (throw (ex-info "weight row must sum to 10000 bp" {:kind k :sum t})))))

(defn- weights-for [kind] (get weights kind (:default weights)))

(defn- weighted-mean-bp
  "nil の軸（未測定）は分子・分母の両方から外す。0 として混ぜない。
   カーネルの acc-init/acc-step/acc-mean と同じ整数畳み込み。"
  [ax w]
  (let [pairs (keep (fn [[k wt]] (when-let [v (get ax k)] [v wt])) w)
        num (reduce + 0 (map (fn [[v wt]] (* v wt)) pairs))
        den (reduce + 0 (map second pairs))]
    (when (pos? den) (clamp-bp (q num den)))))

(def own-score-bp
  "path -> M_own（basis point、整数）。スコア対象外（absent / tombstone）は入らない。
   これがスコアの正本表現。以後の力学モデルだけが /10000 して実数で扱う。"
  (into {} (keep (fn [e]
                   (let [ax (axes-of e)
                         w  (weights-for (kind-by-path (:repo/path e)))]
                     (when-let [s (weighted-mean-bp ax w)]
                       [(:repo/path e) s])))
                 scored-evidence)))

(def own-score
  "path -> M_own ∈ [0,1]。力学モデル（不動点・摂動・XMILE）用の実数表現。"
  (into {} (map (fn [[p bp]] [p (/ bp 10000.0)]) own-score-bp)))

;; ---------------------------------------------------------------- (3) 依存伝播
;;
;; モデル: M_eff(r) = M_own(r) * C(r),  C(r) = min over d∈deps(r) of (α + (1-α)·M_eff(d))
;;
;; 「未成熟な substrate の上に production repo は立たない」を最弱リンクで表す。
;; α は「substrate が maturity 0 でも自分の maturity の α 倍は残る」という床。
;; α は測定値ではない scenario parameter なので出力に明記する。

(def alpha 0.5)

(def nodes (vec (sort (keys own-score))))
(def idx (into {} (map-indexed (fn [i p] [p i]) nodes)))

(def dropped-dep-edges (atom 0))

(def dep-idx
  "i -> スコア対象内に解決できた依存先の index vec。対象外（未 checkout / tombstone /
   west 未登録）への依存は落とし、件数を dropped-dep-edges に残して報告する
   — 黙って落とすと「依存が無い repo」に見えてしまう。"
  (mapv (fn [p]
          (let [ds (:deps/local (by-path p) [])]
            (vec (keep (fn [d] (or (idx d) (do (swap! dropped-dep-edges inc) nil))) ds))))
        nodes))

(def unscored-dep-edges @dropped-dep-edges)

(defn- topo-order
  "依存先が先に来る順。閉路があれば nil（呼び出し側が反復解法に落ちる）。"
  []
  (let [n (count nodes)
        color (js/Array. n)
        order (atom (transient []))
        cyclic? (atom false)]
    (dotimes [i n] (aset color i 0))
    (letfn [(visit [i]
              (when-not @cyclic?
                (case (aget color i)
                  2 nil
                  1 (reset! cyclic? true)
                  (do (aset color i 1)
                      (doseq [d (nth dep-idx i)] (visit d))
                      (aset color i 2)
                      (swap! order conj! i)))))]
      (dotimes [i n] (visit i)))
    (when-not @cyclic? (persistent! @order))))

(def order (topo-order))

(defn- eff-scores
  "own(index->double) から M_eff の配列を返す。topo 順が取れていれば 1 pass。
   閉路があるときだけ反復（単調減少で収束する）。"
  [own-arr]
  (let [n (count nodes)
        eff (js/Float64Array. n)]
    (if order
      (do (doseq [i order]
            (let [ds (nth dep-idx i)
                  c (if (empty? ds) 1.0
                        (reduce (fn [acc d] (min acc (+ alpha (* (- 1.0 alpha) (aget eff d)))))
                                1.0 ds))]
              (aset eff i (* (aget own-arr i) c))))
          eff)
      (do (dotimes [i n] (aset eff i (aget own-arr i)))
          (dotimes [_ 50]
            (dotimes [i n]
              (let [ds (nth dep-idx i)
                    c (if (empty? ds) 1.0
                          (reduce (fn [acc d] (min acc (+ alpha (* (- 1.0 alpha) (aget eff d)))))
                                  1.0 ds))]
                (aset eff i (* (aget own-arr i) c)))))
          eff))))

(def own-arr
  (let [a (js/Float64Array. (count nodes))]
    (dotimes [i (count nodes)] (aset a i (own-score (nth nodes i))))
    a))

(def base-eff (eff-scores own-arr))
(defn- total [arr] (areduce arr i acc 0.0 (+ acc (aget arr i))))
(def base-total (total base-eff))

;; --- dependents（誰が自分に依存しているか）: 直接 + 推移閉包 -------------------

(def dependents-direct
  (reduce (fn [m i]
            (reduce (fn [m2 d] (update m2 d (fnil conj []) i)) m (nth dep-idx i)))
          {}
          (range (count nodes))))

(def ^:private tdep-cache (atom {}))

(defn- transitive-dependents [i]
  (or (@tdep-cache i)
      (let [r (loop [stack [i] seen #{}]
                (if (empty? stack)
                  (disj seen i)
                  (let [x (peek stack)
                        nxt (remove seen (get dependents-direct x []))]
                    (recur (into (pop stack) nxt) (into (conj seen x) nxt)))))]
        (swap! tdep-cache assoc i r)
        r)))

;; --- leverage: 摂動で厳密に測る（近似でなく実際に不動点を解き直す） -----------

(defn- leverage-of [i]
  (let [saved (aget own-arr i)]
    (if (>= saved 0.999)
      {:gain 0.0 :effort 0.0}
      (do (aset own-arr i 1.0)
          (let [t (total (eff-scores own-arr))]
            (aset own-arr i saved)
            {:gain (- t base-total) :effort (- 1.0 saved)})))))

(def leverage
  (let [t0 (.now js/Date)
        v (mapv leverage-of (range (count nodes)))]
    (println (str "leverage: " (count v) " perturbations in " (- (.now js/Date) t0) "ms"))
    v))

;; ---------------------------------------------------------------- Meadows band
;;
;; band は「その repo を直す介入が Meadows のどの階層に当たるか」であって
;; repo の属性ではない。dynamics.core/meadows-bands をそのまま使う（再発明しない）。
;;
;;   band/B (rules / information-flow structure) — 多数の repo が依存する共有
;;     substrate。ここを直すと下流全部の「表現できること」が変わる。
;;   band/D (stock-flow structure) — governed-actor の部品欠落。repo 自身の
;;     構造を変える。
;;   band/E (parameters) — 単体 repo の軸（test / citation / docs）の上げ下げ。

(def hub-share
  "hub の定義: **fleet の 1% 以上を推移的に gate している** repo。
   パーセンタイルで切ると分布が極端に歪んでいる（実測: 推移的 dependents の
   95 パーセンタイルが 1）ため、『2 個依存されていれば hub』という無意味な閾値になる。
   『fleet の何割の表現可能性を握っているか』で切ると閾値が意味を持つ。"
  0.01)

(def hub-threshold (max 2 (int (js/Math.ceil (* hub-share (count nodes))))))

(defn- band-of [i]
  (let [tdeps (count (transitive-dependents i))
        e (by-path (nth nodes i))
        kind (kind-by-path (nth nodes i))]
    (cond
      (>= tdeps hub-threshold) :band/B
      (and (#{"actor" "organism"} kind) (< (:component/count e 0) component-total)) :band/D
      :else :band/E)))

(defn- tractability-of [i]
  ;; 「直しやすさ」の測定できる代理: 既に実コードがある repo は直しやすく、
  ;; 空 stub はゼロから書くことになる。0.3 の下駄は「どの repo も着手自体は可能」。
  ;; これは代理指標であって計測された tractability ではない。
  (let [e (by-path (nth nodes i))
        sub (/ (sat-bp (:src/bytes e 0) (:src k-consts)) 10000.0)]
    (max 0.0 (min 1.0 (+ 0.3 (* 0.7 sub))))))

;; ---------------------------------------------------------------- flagship

(def flagship-paths
  "flagship は ADR-2607122300 / ADR-2607189300 / ADR-2608102000 が名指ししている repo。
   スコアから導かず、決定文書から引く（flagship は測定結果ではなく指定）。

   2026-08-10（ADR-2608102000）に 9 → 34 本。増えた 25 本は 7 項目チェックリストの
   1〜6 を既に満たしており、欠けていたのは item 7（Managed Starter の有償 tier）
   だけだった —— そしてその item 7 の実体は「Payment Link を貼る」ことではなく
   **vertical ごとに価格を決める**ことだった。24 本は実競合ベンチマークから
   価格を導出し（公開/非公開の別と出典 URL を各 business-model.md に記録）、
   Stripe Payment Link を Gftd Japan 口座に作成した。

   ⚠ **この集合に repo を足すのは指定であって測定ではない。** 6/7 を満たす repo は
   2026-08-10 時点で他に 49 本あるが、item 7 の価格策定が済んでいないので入れて
   いない。スコアが高いことを理由にここへ足さないこと。"
  #{;; ADR-2607122300 / ADR-2607189300（2026-07-12〜07-18）
    "orgs/cloud-itonami/cloud-itonami-isic-6399"
    "orgs/cloud-itonami/cloud-itonami-isic-6310"
    "orgs/cloud-itonami/cloud-itonami-isic-7810"
    "orgs/cloud-itonami/cloud-itonami-isic-5820"
    "orgs/cloud-itonami/cloud-itonami-isic-851"
    "orgs/cloud-itonami/cloud-itonami-isic-852"
    "orgs/cloud-itonami/cloud-itonami-isic-853"
    "orgs/cloud-itonami/cloud-itonami-isic-854"
    "orgs/cloud-itonami/cloud-itonami-isic-4921"
    ;; ADR-2608102000（2026-08-10）
    "orgs/cloud-itonami/cloud-itonami-isic-0610"
    "orgs/cloud-itonami/cloud-itonami-isic-2620"
    "orgs/cloud-itonami/cloud-itonami-isic-2910"
    "orgs/cloud-itonami/cloud-itonami-isic-3600"
    "orgs/cloud-itonami/cloud-itonami-isic-4210"
    "orgs/cloud-itonami/cloud-itonami-isic-4630"
    "orgs/cloud-itonami/cloud-itonami-isic-5210"
    "orgs/cloud-itonami/cloud-itonami-isic-5320"
    "orgs/cloud-itonami/cloud-itonami-isic-6201"
    "orgs/cloud-itonami/cloud-itonami-isic-6202"
    "orgs/cloud-itonami/cloud-itonami-isic-6311"
    "orgs/cloud-itonami/cloud-itonami-isic-6312"
    "orgs/cloud-itonami/cloud-itonami-isic-6420"
    "orgs/cloud-itonami/cloud-itonami-isic-6512"
    "orgs/cloud-itonami/cloud-itonami-isic-6612"
    "orgs/cloud-itonami/cloud-itonami-isic-6622"
    "orgs/cloud-itonami/cloud-itonami-isic-6810"
    "orgs/cloud-itonami/cloud-itonami-isic-6910"
    "orgs/cloud-itonami/cloud-itonami-isic-6920"
    "orgs/cloud-itonami/cloud-itonami-isic-7310"
    "orgs/cloud-itonami/cloud-itonami-isic-7820"
    "orgs/cloud-itonami/cloud-itonami-isic-8299"
    "orgs/cloud-itonami/cloud-itonami-isic-9200"
    "orgs/cloud-itonami/cloud-itonami-isic-9411"
    "orgs/cloud-itonami/cloud-itonami-isic-9522"})

(defn- layer-of [i]
  (let [p (nth nodes i)]
    (cond
      (>= (count (transitive-dependents i)) hub-threshold) :substrate
      (flagship-paths p) :flagship
      :else :cohort)))

(def layers (mapv layer-of (range (count nodes))))

(defn- layer-idxs [lyr] (filterv #(= lyr (nth layers %)) (range (count nodes))))

(defn- layer-mean [lyr]
  (let [is (layer-idxs lyr)]
    (when (seq is) (/ (reduce + 0.0 (map #(aget own-arr %) is)) (count is)))))

(defn- substrate-mean-weighted
  "substrate 層の集約は単純平均でなく **gate している repo 数で重み付け**する。
   717 repo を gate する langgraph と 2 repo を gate する lib を同じ 1 票にすると、
   『substrate の成熟度』が実際の gate 力とずれる。"
  []
  (let [is (layer-idxs :substrate)
        ws (map #(double (max 1 (count (transitive-dependents %)))) is)
        tot (reduce + 0.0 ws)]
    (when (pos? tot)
      (/ (reduce + 0.0 (map (fn [i w] (* w (aget own-arr i))) is ws)) tot))))

(def layer-counts (frequencies layers))

;; ---------------------------------------------------------------- XMILE
;;
;; fleet の成熟度を 3 stock の実 XMILE model にして、org-oasis-open-xmile の
;; 実 RK4 で回す（自前シミュレータを書かない — ADR-2607072350 / dynamics.xmile の
;; 「もう実在するエンジンを使え」の指示に従う）。
;;
;;   Substrate' = w_sub  * Work * (1 - Substrate)
;;   Flagship'  = w_flag * Work * (1 - Flagship) * gate(Substrate)
;;   Cohort'    = w_coh  * Work * (1 - Cohort)   * gate(Substrate) * tmpl(Flagship)
;;
;;   gate(S) = α + (1-α)·S       ← 依存伝播モデルと同じ α（同じ主張を 2 度書かない）
;;   tmpl(F) = β + (1-β)·F       ← flagship から cohort への template 伝播
;;
;; β は測定値から取る: flagship-checklist-scan が数えた「生成された demo」と
;; 「手書き demo」の比が、flagship の型が cohort にどれだけ伝播しているかの実測。
;; Work（単位時間あたりの投入量）は測っていない — scenario parameter。

(def work-rate 0.05) ; scenario: fleet 全体に均等投入したとき 1 期間で残ギャップの 5% を詰める量

;; Spread_* が層サイズの逆数で大きくなる（substrate 層は約 100 倍）ので、
;; 明示的な積分ステップを細かくして overshoot を出さない。dt は結果に効く
;; 数値パラメタなので出力に載せる。収束は dt 半分での再実行で確認している
;; （:sim/dt-halved-max-delta）。
(def sim-dt 0.05)

;; 目的関数は **effective** 成熟度の fleet 平均にする。ここを raw 平均にすると
;; 静的 leverage 計算（M_eff の不動点で測っている）と別のものを最適化することになり、
;; 2 つの分析が食い違う。実際 1 度そう書いて、静的側が langgraph に gain 22 を出す一方
;; 動的側が breadth-first を勝たせるという矛盾が出た。同じ構造は 1 回だけ書く。
;;
;;   Fleet_Eff = (n_sub·Substrate + n_flag·Flagship·Gate + n_coh·Cohort·Gate) / N
;;
;; substrate 自身は依存を持たない（gate されない）。flagship / cohort は substrate に
;; gate される — 静的モデルの C(r) = α + (1-α)·M_eff(dep) と同じ形。

(defn- strategy-model
  "w-* が定数なら固定配分。:switch-at が与えられたら、その時刻までは
   pre 配分、以後 post 配分に切り替える（XMILE の IF/THEN/ELSE + 予約語 TIME）。"
  [nm {:keys [w-sub w-flag w-coh switch-at post]} s0 f0 c0 beta sim-t n-sub n-flag n-coh
   {:keys [flag-gated-share coh-gated-share]} dt]
  (let [alloc (fn [var-nm pre-v post-v]
                (xm/aux var-nm
                        (if switch-at
                          (str "IF TIME < " (double switch-at)
                               " THEN " (double pre-v) " ELSE " (double post-v))
                          (str (double pre-v)))))
        n (double (+ n-sub n-flag n-coh))
        ;; 依存を 1 つも持たない repo は substrate に gate されない。fleet 全体を
        ;; gate すると substrate の効きを過大評価する。実測した被 gate 率で薄める。
        eff-gate (fn [share] (str "(" (double share) " * Gate + " (double (- 1.0 share)) ")"))
        ;; **層のサイズで work を割る。** これを忘れると 18 repo の substrate 層と
        ;; 1,770 repo の cohort 層を同じ速度で埋められることになり、「土台を先に」の
        ;; コストがタダになる。1 期間の総投入 Work を層のシェアで分け、さらに
        ;; その層の repo 数で割る = repo 1 個あたりの投入。層平均の gap 閉じ速度は
        ;;   w_L * Work * (N / n_L) * (1 - Stock_L)
        spread (fn [n-layer] (/ n (double (max 1 n-layer))))]
    (-> (xm/model nm {:xmile/sim-specs (xm/sim-specs 0.0 (double sim-t)
                                                     {:xmile/dt dt :xmile/method :rk4})})
        (xm/add-variable (xm/aux "Work" (str work-rate)))
        (xm/add-variable (xm/aux "Alpha" (str alpha)))
        (xm/add-variable (xm/aux "Beta" (str beta)))
        (xm/add-variable (alloc "W_Sub"  w-sub  (:w-sub post w-sub)))
        (xm/add-variable (alloc "W_Flag" w-flag (:w-flag post w-flag)))
        (xm/add-variable (alloc "W_Coh"  w-coh  (:w-coh post w-coh)))
        (xm/add-variable (xm/aux "Gate" "Alpha + (1 - Alpha) * Substrate"))
        (xm/add-variable (xm/aux "Tmpl" "Beta + (1 - Beta) * Flagship"))
        (xm/add-variable (xm/aux "Flag_Gate" (eff-gate flag-gated-share)))
        (xm/add-variable (xm/aux "Coh_Gate"  (eff-gate coh-gated-share)))
        (xm/add-variable (xm/aux "Spread_Sub"  (str (spread n-sub))))
        (xm/add-variable (xm/aux "Spread_Flag" (str (spread n-flag))))
        (xm/add-variable (xm/aux "Spread_Coh"  (str (spread n-coh))))
        (xm/add-variable (xm/flow "Sub_Gain"  "W_Sub * Work * Spread_Sub * (1 - Substrate)"))
        (xm/add-variable (xm/flow "Flag_Gain" "W_Flag * Work * Spread_Flag * (1 - Flagship) * Flag_Gate"))
        (xm/add-variable (xm/flow "Coh_Gain"  "W_Coh * Work * Spread_Coh * (1 - Cohort) * Coh_Gate * Tmpl"))
        (xm/add-variable (xm/stock "Substrate" (str (double s0)) {:xmile/inflows #{"Sub_Gain"}}))
        (xm/add-variable (xm/stock "Flagship"  (str (double f0)) {:xmile/inflows #{"Flag_Gain"}}))
        (xm/add-variable (xm/stock "Cohort"    (str (double c0)) {:xmile/inflows #{"Coh_Gain"}}))
        ;; 目的関数を model の中に aux として置く。外で後計算すると
        ;; 「何を最適化したか」がモデルの外に漏れる。
        (xm/add-variable (xm/aux "Fleet_Eff"
                                 (str "(" (double n-sub) " * Substrate + "
                                      (double n-flag) " * Flagship * Flag_Gate + "
                                      (double n-coh) " * Cohort * Coh_Gate) / " n)))
        (xm/add-variable (xm/aux "Fleet_Raw"
                                 (str "(" (double n-sub) " * Substrate + "
                                      (double n-flag) " * Flagship + "
                                      (double n-coh) " * Cohort) / " n))))))

(defn- round4 [x] (/ (js/Math.round (* 1e4 x)) 1e4))

;; ---------------------------------------------------------------- render

(defn- render-val [v]
  (cond (nil? v) "nil"
        (string? v) (pr-str v)
        (boolean? v) (str v)
        (number? v) (str (if (integer? v) v (round4 v)))
        (keyword? v) (str v)
        (vector? v) (str "[" (str/join " " (map render-val v)) "]")
        :else (pr-str v)))

(defn- render-entity [m]
  (str "{" (str/join ", " (map (fn [[k v]] (str k " " (render-val v))) m)) "}"))

;; ---------------------------------------------------------------- main

(defn- explain! [p]
  (let [e (by-path p)]
    (if-not e
      (println (str "  " p " — not in evidence"))
      (let [ax (axes-of e) kind (kind-by-path p) w (weights-for kind)]
        (println (str "\n  " p "  kind=" kind "  M_own=" (own-score-bp p) "bp"
                      "  M_eff=" (round4 (aget base-eff (idx p 0)))))
        (println (str "    src=" (:src/bytes e) "B/" (:src/file-count e) "f"
                      "  test=" (:test/bytes e) "B/" (:test/file-count e) "f"
                      "  components=" (:component/present e)
                      "  citations=" (:ingest/citation-count e)
                      "  readme=" (:doc/readme-bytes e) "B"
                      "  adr=" (:doc/adr-count e)
                      "  demo=" (:surface/demo-file-count e)
                      "  cron=" (:surface/has-cron-workflow? e)
                      "  deps=" (:deps/local e)
                      "  last=" (:git/last-commit e)))
        (doseq [[k v] (sort-by key ax)]
          (println (str "    " k " = " (if v (str v "bp") "nil") "   (weight " (get w k) "bp)")))))))

(defn -main []
  (let [n (count nodes)
        _ (println (str "scored " n " repos; " unscored-dep-edges " dep edges pointed outside the scored set"))
        _ (println (str "topo order: " (if order "acyclic (1-pass)" "CYCLIC (iterative)")))
        s0 (substrate-mean-weighted) f0 (layer-mean :flagship) c0 (layer-mean :cohort)
        n-sub (get layer-counts :substrate 0)
        n-flag (get layer-counts :flagship 0)
        n-coh (get layer-counts :cohort 0)
        ;; 実測: その層のうち「実際に依存を 1 つ以上持つ」repo の割合。
        gated-share (fn [lyr]
                      (let [is (layer-idxs lyr)]
                        (if (empty? is) 0.0
                            (/ (double (count (filter #(seq (nth dep-idx %)) is))) (count is)))))
        flag-gated (gated-share :flagship)
        coh-gated (gated-share :cohort)
        gating {:flag-gated-share flag-gated :coh-gated-share coh-gated}

        ;; β: flagship の型が cohort へ伝播している実測比。生成 demo / (生成 + 手書き)。
        ;; これは flagship-checklist-scan の分類ではなく、この scan が数えた
        ;; 「cron workflow で再生成される demo を持つ cohort repo の割合」。
        coh-idxs (filterv #(= :cohort (nth layers %)) (range n))
        coh-with-demo (count (filter #(pos? (:surface/demo-file-count (by-path (nth nodes %)) 0)) coh-idxs))
        coh-with-regen (count (filter #(:surface/has-cron-workflow? (by-path (nth nodes %))) coh-idxs))
        beta (if (pos? coh-with-demo)
               (max 0.05 (min 0.95 (/ (double coh-with-regen) coh-with-demo)))
               0.05)

        strategies {"substrate-first" {:w-sub 0.70 :w-flag 0.20 :w-coh 0.10}
                    "flagship-first"  {:w-sub 0.10 :w-flag 0.70 :w-coh 0.20}
                    "breadth-first"   {:w-sub 0.10 :w-flag 0.10 :w-coh 0.80}
                    "balanced"        {:w-sub 0.34 :w-flag 0.33 :w-coh 0.33}
                    ;; 「まず土台、次に横展開」— 順序が効くかを見るための逐次戦略。
                    ;; 切替時刻 8 は scenario（測定値ではない）。
                    "substrate-then-breadth" {:w-sub 0.70 :w-flag 0.20 :w-coh 0.10
                                              :switch-at 8
                                              :post {:w-sub 0.10 :w-flag 0.10 :w-coh 0.80}}
                    "substrate-then-flagship-then-breadth"
                    {:w-sub 0.70 :w-flag 0.20 :w-coh 0.10
                     :switch-at 6
                     :post {:w-sub 0.15 :w-flag 0.35 :w-coh 0.50}}
                    ;; substrate に 1 も割かない対照。substrate drag を分離するために要る
                    ;; — 他の戦略は breadth-first でさえ 10% を substrate に回しており、
                    ;; 18 repo しかない層はそれで飽和してしまうので「substrate を無視した」
                    ;; ことにならない。
                    "cohort-only" {:w-sub 0.0 :w-flag 0.0 :w-coh 1.0}}
        sim-t 60
        at (fn [series t] (nth series (min (dec (count series)) (int (js/Math.round (/ t sim-dt))))))
        sims (into {}
                   (map (fn [[nm w]]
                          (let [mdl (strategy-model nm w s0 f0 c0 beta sim-t n-sub n-flag n-coh gating sim-dt)
                                r (xex/run mdl)
                                fs* (get-in r [:xmile/series "Fleet_Eff"])
                                raw (get-in r [:xmile/series "Fleet_Raw"])]
                            [nm {:series fs*
                                 :times (:xmile/times r)
                                 :final (last fs*)
                                 :at-12 (at fs* 12) :at-24 (at fs* 24)
                                 :raw-final (last raw)
                                 :substrate-final (last (get-in r [:xmile/series "Substrate"]))
                                 :flagship-final (last (get-in r [:xmile/series "Flagship"]))
                                 :cohort-final (last (get-in r [:xmile/series "Cohort"]))
                                 :weights w}]))
                        strategies))

        ;; substrate 配分の knee 探索。「土台に最低どれだけ回せば drag が消えるか」は
        ;; 順序の議論より実務的で、しかも計算できる。残りは全部 cohort に回す。
        sweep (mapv (fn [ws]
                      (let [w {:w-sub ws :w-flag 0.0 :w-coh (- 1.0 ws)}
                            r (xex/run (strategy-model (str "sweep-" ws) w s0 f0 c0 beta sim-t
                                                       n-sub n-flag n-coh gating sim-dt))
                            fe (get-in r [:xmile/series "Fleet_Eff"])]
                        {:w-sub ws
                         :at-12 (at fe 12) :at-24 (at fe 24) :final (last fe)
                         :substrate-at-24 (at (get-in r [:xmile/series "Substrate"]) 24)}))
                    [0.0 0.005 0.01 0.02 0.05 0.10 0.20])

        ;; dt 収束検査。Spread_* が層サイズぶん大きいので、dt が粗いと RK4 でも
        ;; 数値が動く。dt を半分にして最終値がどれだけ動くかを測り、出力に残す
        ;; （「RK4 だから正しい」で済ませない）。
        dt-check (let [half (into {} (map (fn [[nm w]]
                                            [nm (last (get-in (xex/run (strategy-model
                                                                        nm w s0 f0 c0 beta sim-t
                                                                        n-sub n-flag n-coh gating
                                                                        (/ sim-dt 2.0)))
                                                              [:xmile/series "Fleet_Eff"]))])
                                          strategies))]
                   (apply max 0.0 (map (fn [[nm s]] (js/Math.abs (- (:final s) (get half nm 0.0))))
                                       sims)))

        ;; --- 順位づけ ---
        rows (mapv (fn [i]
                     (let [p (nth nodes i)
                           e (by-path p)
                           ax (axes-of e)
                           {:keys [gain effort]} (nth leverage i)
                           tdeps (count (transitive-dependents i))
                           band (band-of i)
                           tract (tractability-of i)]
                       {:repo/path p
                        :repo/name (:repo/name e)
                        :repo/kind (kind-by-path p)
                        :maturity/layer (nth layers i)
                        :maturity/own-bp (own-score-bp p)
                        :maturity/own (aget own-arr i)
                        :maturity/effective (aget base-eff i)
                        :maturity/effective-bp (js/Math.round (* 10000 (aget base-eff i)))
                        :maturity/axis-substrate (:m/substrate ax)
                        :maturity/axis-test (:m/test ax)
                        :maturity/axis-governed (:m/governed ax)
                        :maturity/axis-ingest (:m/ingest ax)
                        :maturity/axis-docs (:m/docs ax)
                        :maturity/axis-surface (:m/surface ax)
                        :maturity/axis-fresh (:m/fresh ax)
                        ;; ── 見えていない内容を datoms まで運ぶ（scan が測る報告専用
                        ;; フィールド。ADR-2608052000）。**スコアには一切入らない** ——
                        ;; 上の :maturity/* はどれもこれを読まない。運ぶ理由は、順位を
                        ;; 出す tick が datoms しか読まないので、evidence に在るだけでは
                        ;; 「この repo の axis-test が 0 なのは test が無いからではない」
                        ;; と言えないこと。
                        :uncounted/src-file-count (:uncounted/src-file-count e 0)
                        :uncounted/src-bytes (:uncounted/src-bytes e 0)
                        :uncounted/test-file-count (:uncounted/test-file-count e 0)
                        :uncounted/test-bytes (:uncounted/test-bytes e 0)
                        :uncounted/readme-file-count (:uncounted/readme-file-count e 0)
                        :uncounted/url-count (:uncounted/url-count e 0)
                        :dep/direct-count (count (nth dep-idx i))
                        :dep/transitive-dependents tdeps
                        :leverage/fleet-gain gain
                        :leverage/effort effort
                        :leverage/ratio (if (pos? effort) (/ gain effort) 0.0)
                        :leverage/band band
                        :leverage/band-weight (dyn/band-weight band)
                        :leverage/tractability tract
                        :leverage/meadows-score (:base-score (dyn/leverage-score
                                                              {:band band :tractability tract}))
                        :source/dataset "itonami-maturity"}))
                   (range n))
        ranked (vec (sort-by :leverage/ratio > rows))

        ;; 未 checkout / tombstone は「悪い」ではなく「測っていない/対象外」として別枠。
        absent (filterv #(not (:repo/present? %)) evidence)
        tombs  (filterv #(and (:repo/present? %) (tombstone? %)) evidence)

        summary {:db/id -1
                 :source/dataset "itonami-maturity"
                 :summary/kind "fleet-summary"
                 :summary/scored-repos n
                 :summary/absent-repos (count absent)
                 :summary/tombstone-repos (count tombs)
                 :summary/evidence-entities (count evidence)
                 :summary/dep-edges (reduce + 0 (map count dep-idx))
                 :summary/dep-edges-outside-scored unscored-dep-edges
                 :summary/graph-acyclic? (boolean order)
                 :summary/hub-threshold hub-threshold
                 :summary/hub-share hub-share
                 :summary/sim-dt sim-dt
                 :summary/sim-dt-convergence-max-delta dt-check
                 :summary/flagship-gated-share flag-gated
                 :summary/cohort-gated-share coh-gated
                 :summary/layer-substrate n-sub
                 :summary/layer-flagship n-flag
                 :summary/layer-cohort n-coh
                 :summary/mean-own (/ (reduce + 0.0 (map #(aget own-arr %) (range n))) n)
                 :summary/mean-effective (/ base-total n)
                 :summary/substrate-drag (- (/ (reduce + 0.0 (map #(aget own-arr %) (range n))) n)
                                            (/ base-total n))
                 :summary/init-substrate s0
                 :summary/init-flagship f0
                 :summary/init-cohort c0
                 :model/alpha alpha
                 :model/beta beta
                 :model/beta-source "measured: cohort repos with a cron-regenerated demo / cohort repos with any demo"
                 :model/work-rate work-rate
                 :model/work-rate-source "scenario parameter — throughput per period is not measured"
                 :model/sim-horizon sim-t
                 :model/integration "rk4 (xmile.execute, org-oasis-open-xmile)"
                 :model/k-src (:src k-consts)
                 :model/k-test (:test k-consts)
                 :model/k-cite (:cite k-consts)
                 :model/k-readme (:readme k-consts)
                 :model/k-adr (:adr k-consts)
                 :model/k-source "fleet's own positive-value medians (not external thresholds)"
                 :model/stale-days stale-days
                 :model/weights (pr-str weights)
                 ;; スコアの評価時刻。freshness は「この瞬間からの経過日数」なので、
                 ;; パリティゲートは再計算せずこの値を使う（日跨ぎで落ちないため）。
                 :scan/at (.toISOString (js/Date. now-ms))}

        sweep-entities (mapv (fn [m]
                               {:source/dataset "itonami-maturity"
                                :sweep/w-substrate (:w-sub m)
                                :sweep/w-cohort (- 1.0 (:w-sub m))
                                :sweep/fleet-eff-at-12 (:at-12 m)
                                :sweep/fleet-eff-at-24 (:at-24 m)
                                :sweep/fleet-eff-final (:final m)
                                :sweep/substrate-at-24 (:substrate-at-24 m)
                                :sweep/note "substrate 配分の knee 探索（残りは全て cohort）"})
                             sweep)

        sim-entities (mapv (fn [[nm s]]
                             {:source/dataset "itonami-maturity"
                              :sim/strategy nm
                              :sim/w-substrate (:w-sub (:weights s))
                              :sim/w-flagship (:w-flag (:weights s))
                              :sim/w-cohort (:w-coh (:weights s))
                              :sim/switch-at (:switch-at (:weights s))
                              :sim/fleet-eff-at-0 (first (:series s))
                              :sim/fleet-eff-at-12 (:at-12 s)
                              :sim/fleet-eff-at-24 (:at-24 s)
                              :sim/fleet-eff-final (:final s)
                              :sim/fleet-raw-final (:raw-final s)
                              :sim/substrate-final (:substrate-final s)
                              :sim/flagship-final (:flagship-final s)
                              :sim/cohort-final (:cohort-final s)
                              :sim/horizon sim-t
                              :sim/objective "Fleet_Eff = (n_sub*Substrate + n_flag*Flagship*Gate + n_coh*Cohort*Gate)/N — 静的 M_eff と同じ構造"
                              :sim/engine "org-oasis-open-xmile xmile.execute/run rk4 dt=0.25"})
                           sims)

        out-path (resolve* (:out opts))
        header (str ";; 90-docs/system-dynamics/itonami-maturity.datoms.edn — GENERATED, DO NOT HAND-EDIT.\n"
                    ";; Regenerate: see ADR-2608052000 / scripts/itonami-maturity-dynamics.cljs header.\n"
                    ";;\n"
                    ";; :source/dataset \"itonami-maturity\"。:repo/path で repo-taxonomy と join できる。\n"
                    ";; 1 entity/repo + fleet-summary 1 + strategy simulation " (count sims) "。\n"
                    ";;\n"
                    ";; 測定値と模型値を混ぜて読まないこと:\n"
                    ";;   :maturity/axis-*  観測から直接（半飽和定数は fleet 自身の中央値）\n"
                    ";;   :maturity/own     観測 × :model/weights（重みは判断）\n"
                    ";;   :maturity/effective / :leverage/*  依存伝播モデルの出力（α に依存）\n"
                    ";;   :sim/*            XMILE RK4 の軌道（work-rate は scenario）\n\n")]
    (.writeFileSync fs out-path
                    (str header "[\n "
                         (str/join "\n " (map render-entity (into [summary] (into (into sim-entities sweep-entities) rows))))
                         "\n]\n"))
    (when (seq (:explain opts))
      (println "\n=== explain ===")
      (doseq [p (:explain opts)] (explain! p)))
    (println (str "wrote " out-path))
    (println (str "  mean own=" (round4 (:summary/mean-own summary))
                  " mean eff=" (round4 (:summary/mean-effective summary))
                  " drag=" (round4 (:summary/substrate-drag summary))))
    (println (str "  layers: substrate=" n-sub " flagship=" n-flag " cohort=" n-coh
                  " (hub = gates >= " hub-threshold " repos = " (* 100 hub-share) "% of fleet)"))
    (println (str "  init: substrate(dep-weighted)=" (round4 s0)
                  " flagship=" (round4 f0) " cohort=" (round4 c0)))
    (println (str "  gated share (measured): flagship=" (round4 flag-gated)
                  " cohort=" (round4 coh-gated)))
    (println (str "  beta(measured)=" (round4 beta) " alpha=" alpha " work-rate(scenario)=" work-rate))
    (println (str "  dt=" sim-dt " convergence check (dt/2 max |Δfinal|)=" (round4 dt-check)))
    (println "\n=== strategy comparison — same total work, objective = Fleet_Eff ===")
    (doseq [[nm s] (sort-by (fn [[_ s]] (- (:final s))) sims)]
      (println (str "  " (subs (str nm (apply str (repeat 40 " "))) 0 40)
                    " t=12 " (round4 (:at-12 s))
                    "  t=24 " (round4 (:at-24 s))
                    "  t=" sim-t " " (round4 (:final s))
                    "  (raw " (round4 (:raw-final s)) ")")))
    (println "\n=== substrate allocation sweep (rest to cohort) ===")
    (doseq [m sweep]
      (println (str "  w_sub=" (:w-sub m)
                    "  Fleet_Eff t=12 " (round4 (:at-12 m))
                    "  t=24 " (round4 (:at-24 m))
                    "  t=60 " (round4 (:final m))
                    "  | Substrate@24 " (round4 (:substrate-at-24 m)))))
    (println (str "\n=== top " (:top opts) " by leverage ratio (fleet gain / unit effort) ==="))
    (doseq [r (take (:top opts) ranked)]
      (println (str "  " (str/join "" (take 46 (str (:repo/path r) (apply str (repeat 46 " ")))))
                    " own=" (round4 (:maturity/own r))
                    " eff=" (round4 (:maturity/effective r))
                    " deps<-" (:dep/transitive-dependents r)
                    " gain=" (round4 (:leverage/fleet-gain r))
                    " ratio=" (round4 (:leverage/ratio r))
                    " " (:leverage/band r))))))

(-main)

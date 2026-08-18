(ns gftd.score
  "BMC 成熟度 / YC bench 成熟度 scoring (ADR-2607021700).

   2 軸のスコア (0–100):
   - :bmc — business model canvas としての成熟度。canvas 構造から機械判定できる
     3 次元 (completeness / hypothesis / validation) + facts 2 次元 (pricing /
     grounding)。validation は ledger の hyp status から算出されるので、
     `hyp pass|fail` で検証が進むとスコアが動く。
   - :yc — YC bench としての成熟度。net-kotobase GO-TO-MARKET-LEAN-YCBENCH.md の
     基準に整合する design 6 次元 (acute-problem / wedge / tenx / founder-fit /
     distribution / defensibility) 50% + traction 3 次元 (launched / users /
     revenue、重み 2 倍) 50%。

   主観入力 (facts) は 90-docs/business/maturity-facts.edn に日付つきで分離。
   捏造ゼロ: 実測のない次元は低く付ける。全次元 0–5。

   ## 算術の正本は `70-tools/bmc/kotoba/score_core.kotoba`（ADR-2608158000）

   スコアの**算術**は Kotoba 決定核に移した。この名前空間はその**参照実装**で、
   同名の関数（`clamp-bp` / `ratio-bp` / `rubric-bp` / `acc-num` / `acc-den` /
   `acc-mean` / `completeness-bp` / `hypothesis-bp` / `status-bp` /
   `design-weight` / `traction-weight`）を 1 対 1 で持つ。両者の完全一致は fleet
   gate `root-score-core` が毎回見る。**食い違ったら kernel が正しく、ここを直す。**

   canvas の索引・仮説の列挙・render・投影・allocate はここに残る（決定核は
   スカラで表せる判断に限る）。**反復の順序は host が持ち、算術は kernel が持つ。**

   ## 数はすべて整数 basis point（10000 bp = 1.0）

   0–5 の次元は**その最大値に対する bp**（5.0 → 10000、1.5 → 3000）、複合スコア
   （0–100 点）は **1.0 に対する bp**（100 点 → 10000）で持つ。**bp が正本で、
   小数は表示のための派生値**（`bp->dim` / `bp->points`）—— 浮動小数の計算を
   並置しない。並置した瞬間、ε 無しのパリティが書けなくなる。

   除算は `quot`（切り捨て）なので、**移行前の浮動小数版から公開値が
   1/100 点の桁で動きうる**。実際に動いた値は ADR-2608158000 に列挙してある。"
  (:require [clojure.string :as str]
            [gftd.canvas :as canvas]))

;; ---- bp 算術 — kotoba/score_core.kotoba と 1 対 1 -------------------------------
;; 名前も引数の順序も kernel と同じにしてある。gate は名前で突き合わせるので、
;; 片方だけ改名すると落ちる。

(def bp-one "1.0 を表す basis point。" 10000)

(defn clamp-bp [x] (if (< x 0) 0 (if (< bp-one x) bp-one x)))

(defn ratio-bp
  "比率を bp に。den<1 なら 0（未測定でなく「分母が無い」）。10000 で頭打ち。"
  [num den]
  (if (< den 1) 0 (clamp-bp (quot (* bp-one num) den))))

(defn rubric-bp
  "0–5 の rubric 値を bp に。**引数は 1/10 単位**（5.0 → 50、1.5 → 15）。"
  [tenths]
  (clamp-bp (* tenths 200)))

;; 加重平均のアキュムレータ。kernel 側は `pair` ではなく (分子, 分母) の 2 本の
;; i64 —— コンパイル済み artifact の境界を pair ハンドルが越えられないため
;; （2026-08-15 実測、score_core.kotoba の冒頭に記録）。ここも同じ形にしてある。
(defn acc-num [num v w] (+ num (* v w)))
(defn acc-den [den w] (+ den w))
(defn acc-mean [num den] (if (< den 1) 0 (clamp-bp (quot num den))))

;; YC bench の traction は design の 2 倍の重み（次元数 6 対 3 で 50/50 にする）。
(defn design-weight [] 1)
(defn traction-weight [] 2)

(defn completeness-bp
  "9 block が揃い、平均 item 数が薄くないか。5*(blocks/9)、平均<2 items で -1、0 で床。

   平均の判定を割り算でやらない —— `items/blocks < 2` は `items < 2*blocks` と
   同値で、後者なら切り捨ての向きを考えなくてよい。"
  [blocks items]
  (if (< items (* 2 blocks))
    (clamp-bp (- (ratio-bp blocks 9) 2000))
    (ratio-bp blocks 9)))

(defn hypothesis-bp
  "riskiest 仮説が gate つきで定義されているか。gate 無し=2.0、無定義=0。"
  [gated total]
  (if (< 0 gated) bp-one (if (< 0 total) 4000 0)))

(defn status-bp
  "検証状態 (ledger fold 済の hyp status) を bp に。untested=0 / measuring=1.5
   (計器稼働・未到達) / blocked=0 / moot=0 / refuted=2.0 (学習) / validated=5.0。
   知らない綴りは 0。

   measuring は 2026-07-17 追加: gate が機械測定可能で距離を読み続けている
   状態は untested より前進（計器なしと同じ 0 にするのは不誠実）。validated
   に届かない限り 5 には上げない。"
  [status]
  (case (str status)
    "untested" 0
    "measuring" 3000
    "blocked" 0
    "moot" 0
    "refuted" 4000
    "validated" bp-one
    0))

;; ---- bp と表示値の橋渡し（派生であって第 2 の計算ではない） ---------------------

(defn bp->dim "次元 bp → 0–5 の表示値。" [bp] (/ bp 2000.0))
(defn bp->points "複合 bp → 0–100 点の表示値。" [bp] (/ bp 100.0))

(defn- rubric-tenths
  "facts に記録された rubric 値（0–5、通常は整数）を 1/10 単位の整数へ。
   小数が記録されていたら最も近い 1/10 に丸める —— kernel は i64 しか受けず、
   丸めの判断を kernel の外へ出さないためにここで 1 度だけ行う。"
  [v]
  (if (number? v) (int (+ (* 10 v) 0.5)) 0))

(defn- fact-bp [f k] (rubric-bp (rubric-tenths (get f k 0))))

(defn fold-mean-bp
  "[[bp weight] ...] の加重平均を kernel と同じ順序で畳む。**踏んだ次元だけが
   分母に入る**（測っていない次元を 0 として混ぜない、ADR-2607203000）。

   private にしないのは gate がここを直接叩くため —— gate 側が独自に畳むと、
   検査しているのは『gate の畳み込み 2 本が一致すること』になり、
   `score-product` が実際に使う畳み込みは誰も見なくなる。"
  [weighted]
  (loop [num 0 den 0 xs (seq weighted)]
    (if-let [[v w] (first xs)]
      (recur (acc-num num v w) (acc-den den w) (next xs))
      (acc-mean num den))))

;; ---- auto dims (canvas 構造から機械判定) -----------------------------------------
;; どれも bp を返す。canvas を読むのがここの仕事で、点にするのは kernel の仕事。

(defn completeness [idx product]
  (let [blocks (canvas/product-blocks idx product)]
    (completeness-bp (count blocks) (count (mapcat :canvas/items blocks)))))

(defn hypothesis [idx product]
  (let [hyps (canvas/product-hyps idx product)]
    (hypothesis-bp (count (filter #(and (= :riskiest (:hyp/risk %))
                                        (not (str/blank? (str (:hyp/gate %)))))
                                  hyps))
                   (count hyps))))

(defn validation [idx product]
  (fold-mean-bp (for [h (canvas/product-hyps idx product)]
                  [(status-bp (some-> (:hyp/status h) name)) 1])))

;; ---- rubric ------------------------------------------------------------------------

(def bmc-dims
  [[:completeness "9 block 完備・item 濃度" :auto]
   [:hypothesis   "riskiest 仮説 + gate 定義" :auto]
   [:validation   "仮説の検証状態 (ledger)" :auto]
   [:pricing      "価格・単価の確定度" :facts]
   [:grounding    "solution の実装接地度" :facts]])

(def yc-design-dims
  [[:acute-problem "acute problem (切実さ)"]
   [:wedge         "narrow wedge (楔の細さ)"]
   [:tenx          "10x insight"]
   [:founder-fit   "founder-market fit (内需/dogfood)"]
   [:distribution  "distribution (実証された獲得経路)"]
   [:defensibility "defensibility (moat)"]])

(def yc-traction-dims
  [[:launched "launch 段階 (0 idea → 5 scaled)"]
   [:users    "users/traction (external)"]
   [:revenue  "revenue (0 none / 3 first / 5 repeatable)"]])

(defn score-product
  "→ {:bmc {:dims-bp {...} :dims {...} :score-bp N :score 0-100} :yc {...}}

   **`:dims-bp` と `:score-bp` が正本**（i64 basis point）。`:dims` / `:score` は
   その表示用の派生値で、既存の呼び出し側（render / allocate / 投影）が読む。

   複合スコアは 2 つとも「次元 bp の加重平均」ちょうどである:

     BMC 100*(Σ/25)          = Σ_bp/5           = 5 次元の単純平均
     YC  50*(Σd/30)+50*(Σt/15) = (Σd_bp+2Σt_bp)/12 = design 重み 1・traction 重み 2 の加重平均

   だから合成専用の関数を kernel に置いていない —— 置くと同じ判断の 2 実装になる。"
  [idx product facts]
  (let [f (get-in facts [:products product] {})
        bmc-bp {:completeness (completeness idx product)
                :hypothesis   (hypothesis idx product)
                :validation   (validation idx product)
                :pricing      (fact-bp f :pricing)
                :grounding    (fact-bp f :grounding)}
        bmc (fold-mean-bp (for [[k] bmc-dims] [(get bmc-bp k) 1]))
        design-bp (into {} (for [[k] yc-design-dims] [k (fact-bp f k)]))
        traction-bp (into {} (for [[k] yc-traction-dims] [k (fact-bp f k)]))
        yc (fold-mean-bp
            (concat (for [[k] yc-design-dims] [(get design-bp k) (design-weight)])
                    (for [[k] yc-traction-dims] [(get traction-bp k) (traction-weight)])))
        ->dims (fn [m] (into {} (for [[k v] m] [k (bp->dim v)])))]
    {:bmc {:dims-bp bmc-bp :dims (->dims bmc-bp)
           :score-bp bmc :score (bp->points bmc)}
     :yc  {:dims-bp (merge design-bp traction-bp)
           :dims (->dims (merge design-bp traction-bp))
           :score-bp yc :score (bp->points yc)}
     :note (:note f)}))

(defn score-all [idx facts products]
  (into (sorted-map) (for [p products] [p (score-product idx p facts)])))

;; ---- render ------------------------------------------------------------------------

(defn- fmt [x] (let [r (/ (Math/round (* 10.0 x)) 10.0)] (str r)))

(defn render-table
  "terminal / md 共用の markdown table。"
  [scores]
  (str "| product | BMC 成熟度 | YC bench 成熟度 | validation | 主な不足 |\n"
       "|---|---|---|---|---|\n"
       (apply str
              (for [[p {:keys [bmc yc]}] (sort-by (fn [[_ s]] (- (get-in s [:yc :score]))) scores)]
                (let [weak (->> (merge (:dims bmc) (:dims yc))
                                (sort-by val)
                                (take 2)
                                (map (fn [[k v]] (str (name k) "=" (fmt v)))))]
                  (str "| " (name p) " | " (fmt (:score bmc)) " | " (fmt (:score yc))
                       " | " (fmt (get-in bmc [:dims :validation]))
                       " | " (str/join ", " weak) " |\n"))))))

(def dim-source
  "Where each dimension's number comes from.

  `:auto` is computed from the canvas and the ledger by this namespace.
  `:facts` is a judgement somebody recorded in `maturity-facts.edn`. They are
  different kinds of claim and `render-md` flattens them into one row of
  decimals, so the projection carries the distinction as data."
  (into {}
        (concat (for [[k _ src] bmc-dims] [k (or src :facts)])
                (for [[k] (concat yc-design-dims yc-traction-dims)] [k :facts]))))

(defn render-datoms
  "DataScript-transactable projection of the portfolio's maturity scores.

  `render-md` already exists and is a different thing: it writes a markdown
  table into `:doc/body`, which reads well and cannot be queried. Nothing could
  ask 「この product の validation 次元は何点か」 without parsing prose, and no
  consumer outside this repository could read a score at all — which is why
  `cloud-itonami-app`'s business plane recorded it as an open gap.

  Two facts travel per dimension that the markdown cannot carry:

  `:dim/source` — `:auto` (computed here from the canvas and ledger) versus
  `:facts` (a judgement recorded in `maturity-facts.edn`). A reader comparing two
  products should know which of the eleven numbers were derived and which were
  entered.

  `:dim/recorded?` — whether the fact was actually present. `score-product` reads
  facts with `(get f k 0)`, so an unrecorded judgement scores as the WORST
  possible value rather than as absent, and the composite silently absorbs it.
  Measured on 2026-07-30 this is latent and not firing: all 12 products carry all
  11 fact dimensions. It is carried anyway, because the day a thirteenth product
  is added without facts it would otherwise appear as a product assessed and
  found lacking on everything."
  [scores facts]
  (into
   [{:db/id -1
     :projection/id "maturity-scores"
     :projection/doc_type "maturity-score-projection"
     :projection/as-of (:as-of facts)
     :projection/products (count scores)
     :projection/source (str "gftd score datoms (ADR-2607021700); "
                             "SSoT = portfolio BMC datoms + canvas-ledger + maturity-facts.edn")
     :source/dataset "maturity-scores"}]
   (mapcat
    (fn [[i [p {:keys [bmc yc note]}]]]
      (let [recorded (get-in facts [:products p] {})
            dims (concat (for [[k] bmc-dims] [k (get (:dims bmc) k)])
                         (for [[k] (concat yc-design-dims yc-traction-dims)]
                           [k (get (:dims yc) k)]))
            missing (->> dims
                         (remove (fn [[k]] (or (= :auto (dim-source k))
                                               (contains? recorded k))))
                         (mapv first))]
        (into
         [(cond-> {:db/id (- (+ (* i 100) 2))
                   :score/product p
                   :score/bmc (:score bmc)
                   :score/yc (:score yc)
                   ;; Named rather than left to a reader counting nils: a
                   ;; composite built partly from absent inputs is a weaker claim
                   ;; than one built from recorded ones.
                   :score/unrecorded-dims (count missing)
                   :source/dataset "maturity-scores"}
            note (assoc :score/note note)
            (seq missing) (assoc :score/unrecorded (mapv name missing)))]
         (map-indexed
          (fn [j [k v]]
            (let [src (dim-source k)]
              {:db/id (- (+ (* i 100) 3 j))
               :dim/product p
               :dim/id (keyword (str (name p) "." (name k)))
               :dim/name k
               :dim/value v
               :dim/source src
               :dim/recorded? (or (= :auto src) (contains? recorded k))
               :source/dataset "maturity-scores"}))
          dims))))
    (map-indexed vector scores))))

(defn render-md
  [scores facts]
  (str "# portfolio maturity scores — BMC 成熟度 / YC bench 成熟度\n\n"
       "<!-- generated by gftd cli `score md` (70-tools/bmc, ADR-2607021700).\n"
       "     入力 = ADR-2607021500 datoms + canvas ledger + maturity-facts.edn。手編集禁止。 -->\n\n"
       "**As-of**: " (:as-of facts) "  \n"
       "**Rubric**: BMC = completeness/hypothesis/validation(自動) + pricing/grounding(facts)、"
       "各 0–5 → 100 点換算。YC bench = design 6 次元（YCBench 基準: acute-problem/wedge/10x/"
       "founder-fit/distribution/defensibility)50% + traction 3 次元 (launched/users/revenue)50%。\n"
       "**捏造ゼロ**: validation は ledger の hyp status 機械判定（現状ほぼ全滅=0 が正直な値）。\n\n"
       (render-table scores)
       "\n## 次元別詳細\n\n"
       (apply str
              (for [[p {:keys [bmc yc note]}] scores]
                (str "### " (name p) "\n\n"
                     "- BMC: "
                     (str/join ", " (for [[k] bmc-dims] (str (name k) "=" (fmt (get (:dims bmc) k)))))
                     "\n- YC: "
                     (str/join ", " (for [[k] (concat yc-design-dims yc-traction-dims)]
                                      (str (name k) "=" (fmt (get (:dims yc) k)))))
                     (when note (str "\n- note: " note))
                     "\n\n")))))

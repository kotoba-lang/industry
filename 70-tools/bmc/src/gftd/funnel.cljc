(ns gftd.funnel
  "Sales/marketing funnel process (ADR-2607022600): the layer above the gate
   evaluator. A gate measures the single riskiest *revenue hypothesis*; the funnel
   measures the whole acquisition→revenue path (AARRR-style stages) so the daily
   cycle can drive *growth* (a sales/marketing motion), not just validate one
   hypothesis.

   Per product a funnel-spec is an ordered vector of stages
   [{:key :label :metric [path] :benchmark r} ...]. Consecutive stages form
   conversion steps; `:benchmark` on a stage is the expected conversion FROM the
   previous stage TO this one. `evaluate-funnel` computes each stage count, each
   step's conversion rate, the gap vs benchmark, and the bottleneck step (measured,
   furthest below benchmark). `proposals` turns the bottleneck into a governor-ready
   GTM action into the product's channels block, records the funnel snapshot into
   the metrics block, and surfaces any unmeasured stage as an instrument to-do.

   Grounded in collectable metrics (same emitters as gate/collect — funnel counts
   come from the product's metrics edn). Pure .cljc; io stays in cli/collect.

   ## 算術の正本は `70-tools/bmc/kotoba/funnel_core.kotoba`（ADR-2608160500）

   転換率・benchmark との差・ボトルネック判定は **Kotoba の決定核**が持つ。
   この名前空間はその **参照実装**（reference implementation）であり、
   `absent` … `pct-bp` の関数群は kernel と名前も引数の順序も 1 対 1 に対応する。
   fleet gate `root-funnel-core` が両者の完全一致を毎回見る。**食い違ったら
   kernel が正しく、ここを直す。**

   別名前空間の mirror は作らない（CLAUDE.md「同じ判断を 2 実装が別々に持ち
   片方だけ直る状態にしない」）。ここが唯一の参照実装で、
   `evaluate-funnel` / `render-text` / `proposals` はこの関数群を呼ぶ。

   ## 単位は整数 basis point（10000 = 1.0）

   浮動小数を判断から追い出した（理由は kernel の冒頭と
   `90-docs/system-dynamics/kotoba/itonami_maturity_kernel.kotoba`）。
   `evaluate-funnel` が返す step は `:rate` / `:gap` ではなく
   **`:rate-bp` / `:gap-bp` / `:benchmark-bp`** を持つ —— 同じ鍵の下で
   小数から bp へ型を変えると、読み手に気づかせずに 100 倍ずれる。
   `funnel-specs` の `:benchmark` は従来どおり 0.03 のような小数のまま
   （業界水準の読みやすさを優先）で、bp への変換は `benchmark->bp` 1 箇所。"
  (:require [clojure.string :as str]
            [gftd.canvas :as canvas]))

;; ---- per-product funnel specs -----------------------------------------------
;; Stages are collectable via each product's funnel emitter (ADR-2607022200 と同じ
;; 経路)。benchmark は業界水準の下限目安（超えていれば健全、下回れば bottleneck）。
(def funnel-specs
  {:net-kotobase
   [{:key :awareness   :label "landing 訪問"       :metric [:funnel :visitors]}
    {:key :acquisition :label "signup"            :metric [:funnel :signups]   :benchmark 0.03}
    {:key :activation  :label "checkout 開始"      :metric [:funnel :checkouts] :benchmark 0.25}
    {:key :revenue     :label "paid(active sub)"   :metric [:stripe :active-subscriptions] :benchmark 0.50}]

   :ai-gftd-apex
   [{:key :awareness   :label "訪問"               :metric [:funnel :visitors]}
    {:key :acquisition :label "signup(Free)"       :metric [:funnel :signups]   :benchmark 0.04}
    {:key :revenue     :label "Plus 転換"          :metric [:conversion :plus]  :benchmark 0.02}]

   :cloud-manimani
   [{:key :awareness   :label "OSS install"        :metric [:oss :installs]}
    {:key :acquisition :label "cloud signup"       :metric [:cloud :signups]    :benchmark 0.10}
    {:key :revenue     :label "cloud paid"         :metric [:conversion :cloud-paid] :benchmark 0.04}]

   :cloud-itonami
   [{:key :awareness   :label "trial org"          :metric [:funnel :trials]}
    {:key :acquisition :label "onboarded org"      :metric [:tenants :active]   :benchmark 0.40}
    {:key :revenue     :label "外部有償 org"        :metric [:tenants :external-paid] :benchmark 0.20}]

   :club-shinshi
   [{:key :awareness   :label "訪問"               :metric [:funnel :visitors]}
    {:key :acquisition :label "登録"               :metric [:funnel :signups]   :benchmark 0.02}
    {:key :revenue     :label "課金/creator GMV"    :metric [:funnel :paying]    :benchmark 0.03}]

   ;; cloud-murakumo (2026-07-15): awareness = zone uniques (collect 済み)、
   ;; activation = /infer/cost emitter が数える実推論 run(ring 上限 200 —
   ;; 絶対数でなく「API が実際に使われているか」の生存信号)、revenue = Stripe。
   ;; Stripe は未配線(canvas 2026-07-06 観測: STRIPE_SECRET_KEY 未設定で
   ;; checkout 503)なので revenue 段が bottleneck として surface されるのが正。
   :cloud-murakumo
   [{:key :awareness   :label "murakumo.cloud 訪問" :metric [:zone :uniques-7d-sum]}
    {:key :activation  :label "実推論 run (記録済)"  :metric [:cost :runs-count]  :benchmark 0.02}
    {:key :revenue     :label "paid (credits 購入)"  :metric [:stripe :murakumo-paid-charges] :benchmark 0.01}]})

;; ---- 決定核の参照実装 --------------------------------------------------------
;; `70-tools/bmc/kotoba/funnel_core.kotoba` と 1 対 1。名前・引数順・返り値を
;; 変えるときは **両方** を変える（gate は名前で突き合わせる）。

(defn absent
  "件数 / benchmark の『host がそれを読めなかった』番兵。件数にも benchmark bp
   にも負は無いので実在の値と衝突しない。"
  [] -1)

(defn gap-absent
  "gap の『無い』番兵。実在の gap は [-10000, ∞) に収まるので到達不能。
   **0 は使えない** —— 0 は『benchmark ちょうど』という実在の gap。"
  [] -100000)

(defn clamp-bp [x] (if (< x 0) 0 (if (< 10000 x) 10000 x)))

(defn measured? [n] (not (neg? n)))

(defn denominator?
  "0 件は分母にならない。『0 件から 0% 転換した』ではなく『率を出す材料が無い』。"
  [from] (not (< from 1)))

(defn step-measured? [from to] (and (measured? from) (measured? to)))

(defn rate-known? [from to] (and (step-measured? from to) (denominator? from)))

(defn conversion-bp
  "転換率（bp）。**clamp しない** —— 10000 bp 超は後段の emitter が前段より
   多く数えているという信号で、頭打ちにすると『ちょうど 100%、健全』に見える。"
  [from to]
  (if (rate-known? from to) (quot (* 10000 to) from) (absent)))

(defn benchmark-known? [b] (not (neg? b)))

(defn benchmark-bp
  "不在判定を先に済ませてから clamp する。逆順だと -1（不在）が 0（目標 0%）に
   化ける。上限を掛けることで gap の下限が -10000 に固定される。"
  [b]
  (if (benchmark-known? b) (clamp-bp b) (absent)))

(defn gap-known? [from to bench]
  (and (rate-known? from to) (benchmark-known? bench)))

(defn gap-bp [from to bench]
  (if (gap-known? from to bench)
    (- (conversion-bp from to) (benchmark-bp bench))
    (gap-absent)))

(defn below-benchmark?
  "ちょうど一致（gap = 0）は未達ではない。"
  [from to bench]
  (and (gap-known? from to bench) (neg? (gap-bp from to bench))))

(defn bottleneck-wins?
  "候補 `cand` の gap が現職 `best` を置き換えるか。**同点は候補が勝つ = 後ろの
   段が勝つ** —— 移行前に使っていた Clojure の `min-key` は等しいキーのとき
   最後の要素を返す（2 引数版 `(if (< (k x) (k y)) x y)`、3 引数以上はループ内が
   `<=`。2026-08-15 に nbb で実測）。暗黙に反転させないよう明示する。"
  [cand best]
  (cond (not (< (gap-absent) cand)) false
        (not (neg? cand))           false
        (not (< (gap-absent) best)) true
        :else                       (not (< best cand))))

(defn pct-bp
  "bp → パーセント（half-up）。不在は不在のまま（`0%` と綴らない）。"
  [bp]
  (if (neg? bp) (absent) (quot (+ bp 50) 100)))

;; ---- host 側の橋渡し（kernel には無い。metrics edn の形を知っているのは host）--

(defn- num [x] (cond (number? x) x (string? x) (parse-double x) :else nil))

(defn count->bp-input
  "metrics から読んだ段の件数を kernel が受け取れる i64 にする。

   数でないもの（nil / 読めない文字列）と **負値** は `(absent)`。負を弾くのは、
   emitter が -1 を『不明』の意味で出すことがあり、以前はそれが素の数として
   算術に入って **その段をボトルネックに仕立てていた**ため（ADR-2608136000:
   測れなかったものが、測って悪かったものと同じ顔をする）。
   小数は切り捨てる（bp の丸めと同じく、端数は常に下側）。"
  [x]
  (let [n (num x)]
    (if (and (number? n) (== n n) (>= n 0))
      (long (Math/floor n))
      (absent))))

(defn benchmark->bp
  "spec の `:benchmark`（0.03 のような小数）を bp に。**この 1 箇所だけが
   浮動小数に触る。** 現行 6 product の 13 個の benchmark はすべて誤差なく
   整数 bp になる（0.03→300 … 0.50→5000、2026-08-15 実測。gate が毎回
   往復して確かめる）。
   宣言が無い / 数でない / 負なら `(absent)`。"
  [b]
  (if (and (number? b) (>= b 0))
    (long (Math/round (* 10000.0 (double b))))
    (absent)))

(defn- nil-when-absent [v sentinel] (when-not (= v sentinel) v))

;; ---- evaluation -------------------------------------------------------------

(defn evaluate-funnel
  "→ {:stages [{:key :label :count n|nil}]
      :steps  [{:from :to :from-count :to-count
                :rate-bp r|nil        ; 転換率（basis point、10000 = 1.0）
                :benchmark b|nil      ; spec が書いた小数のまま（未加工）
                :benchmark-bp bb|nil
                :gap-bp g|nil         ; rate-bp − benchmark-bp
                :measurable bool      ; 両端の件数が読めたか
                :rate-known bool}]    ; **かつ分母がある**か（別の条件）
      :bottleneck step|nil  ; measured step furthest below its benchmark
      :missing [stage-key ...]}  ; stages with no measurable count

   `:measurable` と `:rate-known` は別物である。前段が 0 件の step は
   **測れている**が率を出せない（分母が無い）—— 以前はこの 2 つがどちらも
   `:rate nil` に潰れ、出力でも『(未計測)』と同じに綴られていた。"
  [metrics spec]
  (let [stages (mapv (fn [s] (assoc s :count (num (get-in metrics (:metric s))))) spec)
        steps (mapv (fn [[a b]]
                      (let [fc (:count a) tc (:count b)
                            f (count->bp-input fc) t (count->bp-input tc)
                            bench (:benchmark b)
                            bbp (benchmark->bp bench)]
                        {:from (:key a) :to (:key b) :from-label (:label a) :to-label (:label b)
                         :from-count fc :to-count tc
                         :rate-bp (nil-when-absent (conversion-bp f t) (absent))
                         :benchmark bench
                         :benchmark-bp (nil-when-absent bbp (absent))
                         :gap-bp (nil-when-absent (gap-bp f t bbp) (gap-absent))
                         :measurable (step-measured? f t)
                         :rate-known (rate-known? f t)}))
                    (partition 2 1 stages))
        ;; ボトルネックは畳み込みで決める。判定は kernel の `bottleneck-wins?` が
        ;; 持ち、host が持つのは反復の順序だけ（同点は後ろの段が勝つ）。
        bottleneck (reduce (fn [best st]
                             (if (bottleneck-wins? (or (:gap-bp st) (gap-absent))
                                                   (or (:gap-bp best) (gap-absent)))
                               st best))
                           nil steps)
        missing (mapv :key (filter #(nil? (:count %)) stages))]
    {:stages stages :steps steps :bottleneck bottleneck :missing missing}))

;; ---- GTM playbook (bottleneck → 具体アクション) -----------------------------
(def ^:private gtm-playbook
  {:acquisition "landing の価値提案/CTA と価格ページの A/B、SEO・技術コンテンツ、既存導線からの招待"
   :activation  "onboarding の摩擦削減（signup→checkout 最短化）・価格/tier の明確化・空状態の初期価値提示"
   :revenue     "trial→paid の nudge（使用量到達通知）・価格 tier 見直し・年額/上位 tier の提示"})

(defn- pct
  "bp → \"12%\"。丸めの規則は kernel の `pct-bp` が持つ（切り捨てた bp から出す
   丸めと元の小数から出す丸めが別の答えを出しうるので、1 箇所に置く）。"
  [bp]
  (when (and bp (not (neg? bp))) (str (pct-bp bp) "%")))

(defn- block-id [product suffix] (keyword (str (name product) "." suffix)))

(defn- block-items-set
  "Current canvas items for a block as a set (advisor-side dedup).
   gate.cljc と同一 shape — fresh idx / 未定義 block は #{}。"
  [idx block-id]
  (let [block (get-in idx [:blocks block-id])]
    (if block (set (:canvas/items block)) #{})))

(defn proposals
  "Governor-ready proposals that advance the sales/marketing motion:
   - bottleneck step below benchmark → GTM action into the channels block (dedup'd)
   - funnel snapshot → observation into the metrics block (dedup'd)
   - unmeasured stage → 計器 to-do into the solution block (dedup'd)"
  [idx product metrics]
  (let [spec (get funnel-specs product)]
    (if-not spec
      []
      (let [{:keys [stages steps bottleneck missing]} (evaluate-funnel metrics spec)
            metrics-items (block-items-set idx (block-id product "metrics"))
            channels-items (block-items-set idx (block-id product "channels"))
            solution-items (block-items-set idx (block-id product "solution"))
            snapshot (str "funnel (" (name product) "): "
                          (str/join " → " (map (fn [s] (str (:label s) "=" (or (:count s) "?"))) stages))
                          (when (seq steps)
                            (str " | 転換 "
                                 (str/join " / " (keep (fn [st] (when (:rate-bp st)
                                                                  (str (:from-label st) "→" (:to-label st) " " (pct (:rate-bp st)))))
                                                       steps)))))]
        (concat
         ;; funnel snapshot (when measurable) — dedup'd against metrics block
         (when (and (some :count stages) (not (contains? metrics-items snapshot)))
           [{:proposal/action :canvas/add-item
             :canvas/id (block-id product "metrics")
             :event/value snapshot
             :proposal/reason "sales/marketing funnel スナップショット — 運用指標に反映"}])
         ;; bottleneck → GTM action — dedup'd against channels block
         (let [gtm-txt (when bottleneck
                         (str "GTM (" (name (:from bottleneck)) "→" (name (:to bottleneck)) "): "
                              (:from-label bottleneck) "→" (:to-label bottleneck) " 転換 "
                              (pct (:rate-bp bottleneck)) " < 目標 " (pct (:benchmark-bp bottleneck))
                              " — " (get gtm-playbook (:to bottleneck) "獲得施策を検討")))]
           (when (and bottleneck (not (contains? channels-items gtm-txt)))
             [{:proposal/action :canvas/add-item
               :canvas/id (block-id product "channels")
               :event/value gtm-txt
               :proposal/reason "funnel bottleneck（benchmark 未達の転換段）に GTM アクションを提案"}]))
         ;; unmeasured stages → instrument to-do — dedup'd against solution block
         (for [k missing
               :let [st (first (filter #(= (:key %) k) stages))
                     kiage-txt (str "計器 (funnel): " (:label st) " の計測（funnel emitter で " (str/join "/" (map name (:metric st))) " を出力）")]
               :when (not (contains? solution-items kiage-txt))]
           {:proposal/action :canvas/add-item
            :canvas/id (block-id product "solution")
            :event/value kiage-txt
            :proposal/reason "funnel 段の計測が未整備 — 計器を準備項目として提案"}))))))

(defn- step-rate-text
  "率の綴り。**『測れていない』と『分母が無い』を別々に綴る** —— 以前はどちらも
   `(未計測)` になり、instrument の穴と 0 件の前段が読み手に区別できなかった
   （ADR-2608136000 の 4 問目: 飛ばしたのか合格したのかが出力で分かるか）。"
  [st]
  (cond (:rate-bp st)          (pct (:rate-bp st))
        (not (:measurable st)) "(未計測)"
        :else                  "(分母なし)"))

(defn render-text
  "Human-readable funnel report for `funnel show`."
  [product metrics]
  (let [spec (get funnel-specs product)]
    (if-not spec
      (str (name product) ": funnel-spec 未定義")
      (let [{:keys [stages steps bottleneck missing]} (evaluate-funnel metrics spec)]
        (str/join "\n"
          (concat
           [(str "── " (name product) " sales/marketing funnel ──")]
           (map (fn [s] (str "  " (:label s) ": " (or (:count s) "(未計測)"))) stages)
           ["  ─ 転換 ─"]
           (map (fn [st]
                  (str "  " (:from-label st) " → " (:to-label st) ": "
                       (step-rate-text st)
                       (when (:benchmark-bp st) (str " (目標 " (pct (:benchmark-bp st)) ")"))
                       (when (and (:gap-bp st) (neg? (:gap-bp st))) " ⚠ bottleneck 候補")))
                steps)
           (when bottleneck
             [(str "  ▸ bottleneck: " (:from-label bottleneck) "→" (:to-label bottleneck)
                   " " (pct (:rate-bp bottleneck)) " < 目標 " (pct (:benchmark-bp bottleneck)))])
           (when (seq missing)
             [(str "  ▸ 未計測段: " (str/join ", " (map name missing)))])))))))

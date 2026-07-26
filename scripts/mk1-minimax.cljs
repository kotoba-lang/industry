(ns mk1-minimax
  "叢雲 MK-1 — 原資 JPY 3,000,000 の配分を minimax 系の判断基準で決める。
   nbb only (ADR-2607173000)。

   理論の適用範囲を正確にしておく:

   ・von Neumann の minimax 定理(1928)は **2人ゼロ和有限ゲーム** の定理で、
     max_x min_y x'Ay = min_y max_x x'Ay、最適戦略は混合戦略になり得る、
     そしてゲームには一意の『値』がある、という主張。
   ・**自然(市場・技術的不確実性)は我々の損失から利得を得ないので、厳密には
     ゼロ和ではない。**したがって『自然を相手にした minimax』は von Neumann の
     定理そのものではなく、Wald の maximin(1945)と Savage の minimax regret
     (1951)である。本モデルは両方を計算する。
   ・**von Neumann が文字通り当てはまる相手は1つある: DRAM/GDDR6 の希少供給を
     めぐる AI データセンターとの争奪。**これは近似的にゼロ和で、我々は極小の
     プレイヤーなので、このゲームの値は我々にとって負である。値が負のゲームに
     対する処方は『参加しない』こと。部材 pass-through はまさにそれ(§1)。
   ・混合戦略は本件では『無作為化』ではなく『分散投資』の意味。無作為化が価値を
     持つのは相手が我々の純戦略を読んで突いてくる場合で、自然は突いてこない。
     一回限りの資本配分をコイン投げで決めるのは誤用。**混合するのは分散を
     縮めるためであって、読まれないためではない。**")

(def fx 163.8)
(def budget 3000000)

(defn r0 [x] (str (Math/round (double x))))
(defn m [x] (str (/ (Math/round (/ (double x) 100000.0)) 10.0) "M"))   ; JPY -> "xx.xM"
(defn rp [s w] (let [s (str s)] (str s (apply str (repeat (max 0 (- w (count s))) " ")))))
(defn lp [s w] (let [s (str s)] (str (apply str (repeat (max 0 (- w (count s))) " ")) s)))

;; ════════════════════════════════════════════════════════════════════════════
;; §1  部材ゲーム — ここだけは本物の鞍点があり、既に解けている
;; ════════════════════════════════════════════════════════════════════════════
(def sagieki-usd 1899.0)                       ; 固定円建て差益 (ADR-2607268000)
(def parts-usd 2265.0)
(def conv-ship-plat (+ 214.5 202.0 409.0))     ; 転換費 + 出荷 + platform 手数料

(println "════ §1 部材ゲーム — 唯一の本物の鞍点 ════")
(println "自然の手: BOM インフレ。我々の手: 固定価格 か 部材 pass-through。")
(println (str (rp "戦略" 24) (lp "+0%" 9) (lp "+20%" 9) (lp "+50%" 9) (lp "+100%" 9)
              (lp "min" 10)))
(let [infl [0.0 0.20 0.50 1.00]
      fixed (map #(- 4990.0 (* parts-usd (+ 1.0 %)) conv-ship-plat) infl)
      pass  (repeat 4 sagieki-usd)]
  (println (str (rp "固定価格" 24)
                (apply str (map #(lp (r0 %) 9) fixed))
                (lp (r0 (apply min fixed)) 10)))
  (println (str (rp "部材 pass-through" 24)
                (apply str (map #(lp (r0 %) 9) pass))
                (lp (r0 (apply min pass)) 10)))
  (println (str "\nmaximin = pass-through ($" (r0 sagieki-usd) ")。"))
  (println "この行は **定数** なので min = max。つまりゲームの値は $1,899 で、")
  (println "自然にはこれを動かす手が無い — これが鞍点。我々は部材ゲームを")
  (println "『参加しない』ことで解いている。予測して当てているのではない。"))

;; ════════════════════════════════════════════════════════════════════════════
;; §2  資本配分ゲーム — 原資 JPY 3,000,000 をどう置くか
;; ════════════════════════════════════════════════════════════════════════════
;; コスト(JPY)。すべて 2026-07 の実勢か明示 assumption。
(def c-probe-b70   400000)   ; MK-1 一式を street で組む($2,437)。gate 判定に落ちても fleet node として残る
(def c-probe-strix 430000)   ; Ryzen AI MAX+ 395 128GB 級 = 実測済み gad の複製($2,600 想定)
(def c-jp-cert    1027687)   ; 日本のコンプライアンス固定費($6,274)
(def c-campaign    500000)   ; campaign 制作(murakumo 自前スタックで現金支出のみ)
(def c-aib-dep     500000)   ; AIB 割当の PO デポジット(assumption、財貨に充当)

(def contrib-unit (* 1899.0 fx))              ; 1台あたり貢献利益 = JPY 311,109
(def strix-benefit-18mo 1440000)              ; 1ノードの18か月 avoided spot($2.99/h x 8h x 250d x1.5 - 電力)
(def strix-thin-frac 0.25)                    ; 自社推論需要が薄い場合の割引
(def b70-residual 280000)                     ; gate 落ち時の残存効用(遅いが動くノード)

;; 自然の状態。**gate 落ちだけでなく『自社の推論需要が本当にあるか』も
;; 状態に入れる** — Strix 案の利得は avoided cost なので、需要が無ければ
;; 消える。ここを状態に入れないと D の行が不当に安全に見える。
(def states
  [{:k :θ1 :n "gate PASS · 需要200台 · 自社需要 実在"}
   {:k :θ2 :n "gate PASS · 需要50台 · 自社需要 実在"}
   {:k :θ3 :n "gate FAIL · 自社需要 実在"}
   {:k :θ4 :n "gate FAIL · 自社需要 薄い"}])

(defn strix [n state] (* n (if (= state :θ4) (* strix-benefit-18mo strix-thin-frac)
                              strix-benefit-18mo)))
(defn units [state] (case state :θ1 200 :θ2 50 0))
(defn pass? [state] (or (= state :θ1) (= state :θ2)))

(def strategies
  [{:k :A :n "即 launch(実測なし)"
    :spend (+ c-campaign c-jp-cert c-aib-dep)
    :f (fn [s] (if (pass? s)
                 (- (* (units s) contrib-unit) (+ c-campaign c-jp-cert c-aib-dep))
                 ;; 未実測の性能で募った後に落ちる = 返金義務 + 支出済み現金。
                 ;; 原資全額を上限として計上する。**評判・法的損害は行列に入って
                 ;; いない** ので、この -3.0M は下限ではなく過小評価。
                 (- budget)))}
   {:k :B :n "実測 → launch"
    :spend (+ c-probe-b70 c-campaign c-jp-cert c-aib-dep)
    :f (fn [s] (if (pass? s)
                 (- (* (units s) contrib-unit) (+ c-probe-b70 c-campaign c-jp-cert c-aib-dep))
                 (- (- c-probe-b70 b70-residual))))}
   {:k :C :n "二重probe → launch"
    :spend (+ c-probe-b70 c-probe-strix c-campaign c-jp-cert c-aib-dep)
    :f (fn [s] (if (pass? s)
                 (+ (- (* (units s) contrib-unit)
                       (+ c-probe-b70 c-probe-strix c-campaign c-jp-cert c-aib-dep))
                    (strix 1 s))
                 (+ (- (- c-probe-b70 b70-residual)) (- c-probe-strix) (strix 1 s))))}
   {:k :D :n "製品を作らず Strix Halo を6台"
    :spend (* 6 c-probe-strix)
    :f (fn [s] (- (strix 6 s) (* 6 c-probe-strix)))}
   {:k :E :n "現金保持(何もしない)"
    :spend 0
    :f (fn [_] 0)}
   {:k :F :n "混合: probe(B70+Strix) + Strix追加1 + 残りを留保"
    ;; 今すぐ出すのは probe 2台 + Strix 1台 = 1,260,000。残り 1,740,000 は
    ;; gate 通過後の JP cert + campaign(1,527,687)に充てる。AIB デポジットは
    ;; pledge 入金から出す。
    :spend (+ c-probe-b70 (* 2 c-probe-strix))
    :f (fn [s] (let [now (+ c-probe-b70 (* 2 c-probe-strix))]
                 (if (pass? s)
                   (+ (- (* (units s) contrib-unit) now (+ c-jp-cert c-campaign))
                      (strix 2 s))
                   (+ (- (- c-probe-b70 b70-residual)) (- (* 2 c-probe-strix)) (strix 2 s)))))}])

(println "\n\n════ §2 資本配分ゲーム — 原資 JPY 3,000,000 ════")
(println "自然の状態:")
(doseq [{:keys [k n]} states] (println (str "  " (name k) "  " n)))
(println (str "\n1台あたり貢献利益 = JPY " (r0 contrib-unit) " (差益 $1,899、固定)"))
(println (str "Strix 1ノードの18か月便益 = JPY " (r0 strix-benefit-18mo)
              " (自社需要が薄い場合は " (r0 (* strix-benefit-18mo strix-thin-frac)) ")"))

(println (str "\n利得行列 (JPY, 18か月)\n"
              (rp "戦略" 40) (lp "即金支出" 10)
              (apply str (map #(lp (name (:k %)) 10) states)) (lp "min" 10)))
(def payoffs
  (into {} (for [{:keys [k n f spend]} strategies]
             [k {:n n :spend spend :row (mapv #(f (:k %)) states)}])))
(doseq [{:keys [k n]} strategies]
  (let [{:keys [row spend]} (payoffs k)]
    (println (str (rp (str (name k) " " n) 40) (lp (m spend) 10)
                  (apply str (map #(lp (m %) 10) row))
                  (lp (m (apply min row)) 10)))))

;; ── maximin (Wald) ─────────────────────────────────────────────────────────
(def mins (into {} (for [[k {:keys [row]}] payoffs] [k (apply min row)])))
(def maximin-k (key (apply max-key val mins)))
(println (str "\n■ maximin (Wald) = " (name maximin-k)
              "  最悪ケース JPY " (r0 (mins maximin-k))))
(println "  ※ 純 maximin は確率を一切使わないので『何もしない』行に引きつけられる。")
(println "     採るべきかどうかは、次の regret と併せて読む。")

;; ── minimax regret (Savage) ────────────────────────────────────────────────
(def col-best (mapv (fn [i] (apply max (map #(nth (:row (val %)) i) payoffs)))
                    (range (count states))))
(def regrets (into {} (for [[k {:keys [row]}] payoffs]
                        [k (apply max (map-indexed (fn [i v] (- (nth col-best i) v)) row))])))
(def minimax-regret-k (key (apply min-key val regrets)))
(println (str "\n各状態の最良利得: " (apply str (map #(str (m %) "  ") col-best))))
(println (str (rp "戦略" 40) (lp "最大 regret" 14)))
(doseq [{:keys [k]} strategies]
  (println (str (rp (str (name k) " " (:n (payoffs k))) 40) (lp (m (regrets k)) 14))))
(println (str "\n■ minimax regret (Savage) = " (name minimax-regret-k)
              "  最大 regret JPY " (r0 (regrets minimax-regret-k))))

;; ── 情報の価格 ─────────────────────────────────────────────────────────────
(println "\n■ 情報の価格 — gate を測るのに払う保険料")
(let [a-min (mins :A) b-min (mins :B) e-min (mins :E)]
  (println (str "  A(実測なし)の最悪 = JPY " (r0 a-min)))
  (println (str "  B(実測あり)の最悪 = JPY " (r0 b-min)))
  (println (str "  → 実測を買うことで最悪ケースが JPY " (r0 (- b-min a-min)) " 改善する。"))
  (println (str "  B の最悪は『何もしない』(" (r0 e-min) ")との差が JPY "
                (r0 (- e-min b-min)) " しかない = 原資の "
                (/ (Math/round (* 1000.0 (/ (- e-min b-min) budget))) 10.0) "%。"))
  (println "  **上振れ(+60M級)を全部保持したまま、下振れをこの額で買い取れる。**"))

;; ── 予算充足の確認 ─────────────────────────────────────────────────────────
(println "\n■ 予算充足の確認 (原資 JPY 3,000,000)")
(doseq [{:keys [k n]} strategies]
  (let [sp (:spend (payoffs k))]
    (println (str "  " (rp (str (name k) " " n) 40) (lp (m sp) 9)
                  (if (<= sp budget) "  ○ 収まる" "  × 超過")))))
(println (str "\n  F の内訳: 即金 " (m (+ c-probe-b70 (* 2 c-probe-strix)))
              " + 留保 " (m (- budget c-probe-b70 (* 2 c-probe-strix)))))
(println (str "  gate 通過後に必要な JP launch 費用 = "
              (m (+ c-jp-cert c-campaign)) " → 留保で足りる"
              (if (<= (+ c-jp-cert c-campaign) (- budget c-probe-b70 (* 2 c-probe-strix)))
                "(○)" "(×)")))
(println "  AIB 割当デポジットは pledge 入金から出す(原資を使わない)。")
(println (str "\n■ 原資が届かない範囲: US は +$12,500 = JPY "
              (r0 (* 12500 fx)) "、EU は +$28,292 = JPY " (r0 (* 28292 fx)) "。"))
(println "  **JPY 3,000,000 は『日本だけの launch』を丁度賄う額であって、")
(println "  4地域展開の原資ではない。US/EU は campaign 収入から出す。**")

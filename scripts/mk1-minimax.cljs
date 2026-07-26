(ns mk1-minimax
  "叢雲 MK-1 — 原資 JPY 3,000,000 の配分を minimax 系の判断基準で決める。
   nbb only (ADR-2607173000)。

   理論の位置づけ(2026-07-26 訂正 — 初版は境界を引きすぎていた):

   ・**von Neumann の minimax は『単にゼロ和』の話ではなく『最悪を想定して手を
     打つ』話である。**Borel と von Neumann はどちらも security level /
     safety level(最悪の中の最良)に到達し、それをゲームの値とした。1928年の
     定理の意義は、**最悪を想定することが単に慎重なのではなく数学的に最適だと
     示した点** にある。
   ・**ゼロ和は別の話題ではなく、『最悪を想定する』と『合理的な相手を想定する』が
     一致する条件である。**ゼロ和では相手の個人合理性が『こちらを潰しに来る』と
     一致する。ゼロ和を外れるとこの2つは分岐するが、最悪ケースという**決定規則**
     はそのまま使える。
   ・**Wald は von Neumann の代替物を作ったのではなく、von Neumann を自然に
     適用した。**Wald の統計的決定理論は決定問題を『悪意ある Nature との2人ゼロ和
     ゲーム』として解釈する(von Neumann-Morgenstern に明示的に依拠)。
     初版が『定理は適用しない、正しい対応物は Wald』と書いたのは誤り。
   ・**架空の敵は厳密な裏付けを持つ: Bayes-minimax 双対性。**minimax 規則は
     least favorable prior に対する Bayes 規則であり、自然の最適混合戦略が
     まさにその prior である。したがって『自然を敵と見なす』のは便宜ではなく
     均衡的に特徴づけられた操作。§3 でこれを実務的な診断に使う。
   ・**定理自体はゲームの話ですらない。**Sion(1958)は準凸/準凹関数と凸集合の
     条件下に一般化した — min と max を交換できる条件についての命題であり、
     現代の robust optimization / DRO / 敵対的学習はここから来ている。
   ・残る限界は2つだけ: ①minimax 系は確率を使わないので保守的(§3-3 で
     『どの信念なら正当化されるか』に変換する) ②maximin と minimax regret の
     どちらを採るかは『最悪』の定義の選択であり、定理が決めることではない。")

(def fx 163.8)
(def budget 3000000)

(defn joins [sep xs] (apply str (interpose sep xs)))
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

;; ════════════════════════════════════════════════════════════════════════════
;; §3  支配関係 と Bayes-minimax 双対性
;;
;;     オーナー指摘(2026-07-26)を受けた修正: von Neumann の minimax は
;;     『単にゼロ和』の話ではなく『最悪を想定して手を打つ』話である、が正しい。
;;     Borel と von Neumann はどちらも security level / safety level(最悪の中の
;;     最良)に到達し、それをゲームの値とした。定理の意義は **最悪を想定すること
;;     が単に慎重なのではなく数学的に最適だと示した点** にある。
;;     ゼロ和は別の話題ではなく、『最悪を想定する』と『合理的な相手を想定する』
;;     が一致する条件である。
;;
;;     そして Wald は von Neumann の代替物を作ったのではなく、**von Neumann を
;;     自然に適用した**(決定問題を悪意ある Nature との2人ゼロ和ゲームとして
;;     解釈した)。さらに Bayes-minimax 双対性により、minimax 規則は
;;     **least favorable prior に対する Bayes 規則** であり、自然の最適混合戦略が
;;     まさにその prior である。つまり『架空の敵』は厳密な均衡的裏付けを持つ。
;;
;;     この双対性は実務的な道具になる: 『minimax は確率を使わない』という批判を
;;     裏返して、**F を最適にする暗黙の prior を逆算し、それを本当に信じるかを
;;     オーナーに問う** ことができる。以下はその計算。
;; ════════════════════════════════════════════════════════════════════════════
(println "\n\n════ §3 支配関係 と Bayes-minimax 双対性 ════")

;; ── 3-1 支配関係(確率を一切使わずに言えること) ─────────────────────────────
(println "\n■ 3-1 支配関係 — 確率の見立てを一切使わずに言えること")
(def sk (mapv :k strategies))
(defn row-of [k] (:row (payoffs k)))
(defn dominates? [a b]
  (let [ra (row-of a) rb (row-of b)]
    (and (every? true? (map >= ra rb)) (some true? (map > ra rb)))))
(def dominated
  (for [b sk :let [ds (filter #(dominates? % b) (remove #{b} sk))] :when (seq ds)]
    [b ds]))
(if (seq dominated)
  (doseq [[b ds] dominated]
    (println (str "  " (name b) " は " (joins ", " (map name ds))
                  " に **支配される** → 確率をどう置いても選ぶ理由が無い(inadmissible)")))
  (println "  支配される戦略は無い"))
(println (str "  可容(admissible)な戦略: "
              (joins ", " (map name (remove (set (map first dominated)) sk)))))

;; ── 3-2 期待値を確率の関数として見る ───────────────────────────────────────
;;   p = P(gate PASS) / q = P(200台 | PASS) / r = P(自社需要 実在 | FAIL)
(defn ev [k p q r]
  (let [[v1 v2 v3 v4] (row-of k)]
    (+ (* p q v1) (* p (- 1 q) v2) (* (- 1 p) r v3) (* (- 1 p) (- 1 r) v4))))

(def q-ref 0.30)   ; PASS したとき 200台に届く確率(assumption)
(def r-ref 0.70)   ; FAIL でも自社推論需要は実在する確率(assumption)

(println (str "\n■ 3-2 期待値 (q=P(200台|PASS)=" q-ref ", r=P(自社需要実在|FAIL)=" r-ref ")"))
(println (str (rp "戦略" 40) (apply str (map #(lp (str "p=" %) 10) [0.0 0.1 0.3 0.5 0.8]))))
(doseq [{:keys [k n]} strategies]
  (println (str (rp (str (name k) " " n) 40)
                (apply str (map #(lp (m (ev k % q-ref r-ref)) 10) [0.0 0.1 0.3 0.5 0.8])))))

;; ── 3-3 F が他を上回るのに必要な P(gate PASS) ──────────────────────────────
(defn breakeven-p
  "F の期待値が k の期待値と等しくなる p を二分法で求める。
   全域で F が上なら :F-always、全域で下なら :F-never。"
  [k]
  (let [f (fn [p] (- (ev :F p q-ref r-ref) (ev k p q-ref r-ref)))]
    (cond (and (pos? (f 0.0)) (pos? (f 1.0))) :F-always
          (and (neg? (f 0.0)) (neg? (f 1.0))) :F-never
          :else (loop [lo 0.0 hi 1.0 i 0]
                  (if (> i 60) (/ (+ lo hi) 2.0)
                      (let [mid (/ (+ lo hi) 2.0)]
                        (if (= (pos? (f mid)) (pos? (f hi)))
                          (recur lo mid (inc i)) (recur mid hi (inc i)))))))))
(println "\n■ 3-3 F を採るのに必要な P(gate PASS) — これが暗黙の prior の正体")
(doseq [{:keys [k n]} strategies :when (not= k :F)]
  (let [b (breakeven-p k)]
    (println (str "  F vs " (rp (str (name k) " " n) 38)
                  (cond (= b :F-always) "→ p を問わず F が上"
                        (= b :F-never)  "→ p を問わず F が下"
                        :else (str "→ p > " (/ (Math/round (* 1000.0 b)) 10.0) "% なら F が上"))))))
(println "\n  これが Bayes-minimax 双対性の実務版: minimax が確率を使わないという批判を")
(println "  裏返して『F を正当化する最小の信念』を明示できる。**この閾値を本当に")
(println "  超えていると信じられるかがオーナーの判断事項** で、行列が決めることではない。")

;; ── 3-4 portfolio と lottery は別物 ────────────────────────────────────────
(println "\n■ 3-4 なぜ F は『混合』でも無作為化ではないのか(前版の理由づけを訂正)")
(println "  前版は『自然は突いてこないから無作為化は不要』と書いたが、これは理由として")
(println "  不正確だった — 統計的決定理論では損失が凸でない場合、自然相手でも")
(println "  無作為化規則が minimax risk を真に下げることがある。")
(println "  正しい理由は **portfolio と lottery が別の対象** だから:")
(let [f-row (row-of :F)]
  (println (str "    portfolio F(B70 1台 + Strix 2台を実際に買う) = 1つの確定的行動。"))
  (println (str "    利得ベクトルは " (joins " / " (map m f-row)) " で確定する。"))
  (println "    一方 lottery(『B70 3台』と『Strix 3台』をコイン投げで選ぶ)は")
  (println "    期待値が同じでも一回限りでは分散が残る。**破滅に敏感な一回限りの")
  (println "    決定では portfolio が lottery を厳密に支配する。**")
  (println "    F は6行の凸結合ではなく、予算制約下で実行可能な新しい第7の行動である。"))

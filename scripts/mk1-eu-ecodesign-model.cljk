(ns mk1-eu-ecodesign-model
  "MK-1 / MK-4 は EU EcoDesign 617/2013 で売れるか。

   問い(オーナー、2026-08-18): ADR-2608180500 が『EcoDesign 617/2013 の ETEC 制限に
   MK-4 Ring が収まるか未評価。棚札ではなく販売可否の問題』と残した項目を閉じる。

   ■ 結論の形について
   **測っていない量(Pidle)に対して『合格する』と主張しない。**代わりに 2 つを出す:
   (a) 規制が**適用されなくなる**条件（仕様で決まる。測定不要）
   (b) 適用される場合に**何 W を超えると落ちるか**という閾値（測る対象を名指しする）

   ■ 実測（2026-08-18 確認、一次資料 + 委員会ガイドライン April 2014）
   - Regulation (EU) 617/2013 は**現在も有効**。Directive 2009/125/EC は ESPR
     (Reg 2024/1781) が 2024-07-18 に廃止したが、その下の実施規則は置き換わるまで有効。
     617/2013 は 2024-04 から改正レビュー中(未成立)。
   - ETEC(desktop) = (8760/1000) x (0.55 x Poff + 0.05 x Psleep + 0.40 x Pidle)
   - Tier 2 上限(2016-01-01 以降): A 94.00 / B 112.00 / C 134.00 / D 150.00 kWh/年
   - カテゴリ定義: A = B/C/D のいずれにも当たらないもの / B = 2 物理コア + 2GB 以上 /
     C = 3 物理コア以上 + (2GB 以上 または dGfx) / D = 4 物理コア以上 + (4GB 以上
     または FB_BW > 128 GB/s の dGfx)
   - dGfx 分類: G1 FB_BW<=16 / G2 <=32 / G3 <=64 / G4 <=96 / G5 <=128 /
     G6 >128 かつ FB Data Width < 192-bit / G7 >128 かつ >= 192-bit
   - 加算(desktop): 1台目 dGfx G1 18 / G2 30 / G3 38 / G4 54 / G5 72 / G6 90 / **G7 122**、
     2台目以降 G1 11 / G2 17 / G3 22 / G4 32 / G5 42 / G6 53 / **G7 72**。
     メモリは 4GB(カテゴリD)超のGBあたり 1、追加内蔵ストレージ 25、TV チューナ 15、
     オーディオカード 15。
   - **カテゴリD の免除**: 次の 4 つを**全て**満たす desktop は Tier1/Tier2 の
     ETEC 上限から**免除**される — (a) CPU 物理コア 6 以上 (b) dGfx の合計 FB_BW が
     **320 GB/s 超** (c) システムメモリ 16GB 以上 (d) PSU 定格 **1000W 以上**
   - **workstation には ETEC 上限が無い**(Art.2 の定義: MTBF 15,000時間以上 **かつ
     ECC もしくは buffered メモリ** かつ 5 項目中 3 項目)。
   - 免除されても**残る**要件: sleep <= 5.00W / off <= 1.00W / 最小電力状態 <= 0.50W /
     内蔵PSU 効率(50%負荷で85%、20%と100%で82%) / 電源管理 / 情報提供義務。"
  (:require [clojure.string :as str]))

(defn r1 [x] (/ (js/Math.round (* 10 x)) 10))
(defn pad [s n] (.padEnd (str s) n))
(defn lp [s n] (.padStart (str s) n))

;; ── 規制の定数（MEASURED、上記一次資料）───────────────────────────────────
(def etec-cap {:A 94.0 :B 112.0 :C 134.0 :D 150.0})
(def dgfx-first {:G1 18 :G2 30 :G3 38 :G4 54 :G5 72 :G6 90 :G7 122})
(def dgfx-more  {:G1 11 :G2 17 :G3 22 :G4 32 :G5 42 :G6 53 :G7 72})
(def storage-allow 25)
(def cat-d-mem-base 4)
(def sleep-cap 5.00) (def off-cap 1.00)

(defn dgfx-class [fb-bw data-width]
  (cond (<= fb-bw 16) :G1 (<= fb-bw 32) :G2 (<= fb-bw 64) :G3
        (<= fb-bw 96) :G4 (<= fb-bw 128) :G5
        (< data-width 192) :G6 :else :G7))

(defn etec [{:keys [p-off p-sleep p-idle]}]
  (* (/ 8760 1000) (+ (* 0.55 p-off) (* 0.05 p-sleep) (* 0.40 p-idle))))

(defn p-idle-at [budget {:keys [p-off p-sleep]}]
  ;; budget = 8.76*(0.55*Poff + 0.05*Psleep + 0.40*Pidle) を Pidle について解く
  (/ (- (/ budget (/ 8760 1000)) (* 0.55 p-off) (* 0.05 p-sleep)) 0.40))

;; ── SKU（構成は ADR-2607267000 / 2608153400 から。ASSUMPTION は明示）───────
(def skus
  [{:name "MK-1 Lite"  :gpu "Arc B580"    :n-gpu 1 :fb-bw 456 :dw 192
    :fb-src "ASSUMPTION 192-bit GDDR6 19Gbps からの導出。一次確認が要る"
    :cores 6 :sys-mem 32 :psu 650 :storage 1
    :cfg-src "ASSUMPTION 構成未確定。ADR-2608153400 は B580 の調達価格 $300 しか固定していない"}
   {:name "MK-1 Solo"  :gpu "Arc Pro B70" :n-gpu 1 :fb-bw 608 :dw 256
    :fb-src "MEASURED ADR-2607267000: 608 GB/s / 256-bit"
    :cores 6 :sys-mem 64 :psu 750 :storage 1
    :cfg-src "ASSUMPTION PSU 750W は推定。ここが免除の可否を分ける"}
   {:name "MK-4 Ring"  :gpu "Arc Pro B70 x4" :n-gpu 4 :fb-bw 608 :dw 256
    :fb-src "MEASURED ADR-2607267000 の 608 GB/s x 4"
    :cores 16 :sys-mem 128 :psu 1600 :storage 2
    :cfg-src "ASSUMPTION 4 GPU 構成なので PSU 1000W 超は構造的に確実"}])

;; 未測定の電力（ASSUMPTION）。**この模型の出力は『閾値』であって『合否』ではない。**
(def power-guess {:p-off 0.5 :p-sleep 3.0})

(println "=== MK-1 / MK-4 と EU EcoDesign 617/2013 ===\n")
(println "⚠ Pidle は測っていない。したがって『合格する』とは言わない。")
(println "  出すのは (a) 規制が適用されなくなる条件 と (b) 何 W で落ちるかの閾値。\n")

(println "--- 1. カテゴリと dGfx 分類 ---")
(println (str (pad "SKU" 12) (lp "GPU" 16) (lp "台" 4) (lp "FB_BW/枚" 10)
              (lp "合計FB_BW" 11) (lp "幅bit" 7) (lp "class" 7) (lp "カテゴリ" 9)))
(def rows
  (for [{:keys [name gpu n-gpu fb-bw dw cores sys-mem psu storage] :as s} skus]
    (let [total-fb (* n-gpu fb-bw)
          cls (dgfx-class fb-bw dw)
          cat (cond (and (>= cores 4) (or (>= sys-mem 4) (> fb-bw 128))) :D
                    (and (>= cores 3) (or (>= sys-mem 2) true)) :C
                    :else :A)
          exempt? (and (>= cores 6) (> total-fb 320) (>= sys-mem 16) (>= psu 1000))
          budget (+ (get etec-cap cat)
                    (max 0 (- sys-mem cat-d-mem-base))
                    (* storage-allow (max 0 (dec storage)))
                    (get dgfx-first cls)
                    (* (dec n-gpu) (get dgfx-more cls)))]
      (assoc s :total-fb total-fb :cls cls :cat cat :exempt? exempt? :budget budget))))
(doseq [{:keys [name gpu n-gpu fb-bw total-fb dw cls cat]} rows]
  (println (str (pad name 12) (lp gpu 16) (lp n-gpu 4) (lp fb-bw 10)
                (lp total-fb 11) (lp dw 7) (lp (clojure.core/name cls) 7)
                (lp (clojure.core/name cat) 9))))

(println "\n--- 2. カテゴリD 免除の 4 条件（全て満たせば ETEC 上限が適用されない）---")
(println (str (pad "SKU" 12) (lp "6コア+" 8) (lp "FB>320" 9) (lp "16GB+" 8)
              (lp "PSU1000W+" 11) (lp "→免除" 8)))
(doseq [{:keys [name cores total-fb sys-mem psu exempt?]} rows]
  (println (str (pad name 12)
                (lp (str (if (>= cores 6) "○ " "✗ ") cores) 8)
                (lp (str (if (> total-fb 320) "○ " "✗ ") total-fb) 9)
                (lp (str (if (>= sys-mem 16) "○ " "✗ ") sys-mem) 8)
                (lp (str (if (>= psu 1000) "○ " "✗ ") psu "W") 11)
                (lp (if exempt? "**免除**" "適用") 8))))

(println "\n--- 3. ETEC 予算と、落ちる Pidle の閾値 ---")
(println "  予算 = カテゴリ上限 + メモリ加算 + ストレージ加算 + dGfx 加算")
(println (str (pad "SKU" 12) (lp "上限" 6) (lp "メモリ" 7) (lp "storage" 8)
              (lp "dGfx" 7) (lp "予算kWh" 9) (lp "落ちるPidle" 12)))
(doseq [{:keys [name cat sys-mem storage cls n-gpu budget exempt?]} rows]
  (let [mem (max 0 (- sys-mem cat-d-mem-base))
        st (* storage-allow (max 0 (dec storage)))
        gfx (+ (get dgfx-first cls) (* (dec n-gpu) (get dgfx-more cls)))]
    (println (str (pad name 12) (lp (get etec-cap cat) 6) (lp (str "+" mem) 7)
                  (lp (str "+" st) 8) (lp (str "+" gfx) 7) (lp (r1 budget) 9)
                  (lp (if exempt? "— (免除)" (str (r1 (p-idle-at budget power-guess)) " W")) 12)))))
(println (str "  ※ Poff " (:p-off power-guess) "W / Psleep " (:p-sleep power-guess)
              "W と仮定。どちらも ETEC の 0.6% しか動かさないので閾値はほぼ Pidle で決まる。"))

(println "\n--- 4. 免除されても残る要件（全 SKU 共通。ここは逃げ道が無い）---")
(doseq [[k v] [["sleep モード" (str "<= " sleep-cap " W")]
               ["off モード" (str "<= " off-cap " W")]
               ["最小電力状態" "<= 0.50 W"]
               ["内蔵PSU 効率" "50%負荷で 85% / 20%・100%負荷で 82%"]
               ["電源管理" "既定でスリープへ移行する設定、ユーザ説明"]
               ["情報提供" "Annex II 7.1.1: ETEC値・各モード電力・PSU効率・騒音 等"]]]
  (println (str "  " (pad k 14) v)))
(println "  → **免除は ETEC からの免除であって、CE 全体からの免除ではない。**")

(println "\n--- 5. もう一本の逃げ道: workstation 分類 ---")
(println "  Art.2: MTBF 15,000時間以上 かつ **ECC または buffered メモリ** かつ 5 項目中 3 項目")
(println "  → workstation には ETEC 上限が無い。ただし **ECC は CPU/チップセットを選ぶ** ので")
(println "     BOM とプラットフォームが動く。カテゴリD 免除(PSU だけ)より高くつく。")

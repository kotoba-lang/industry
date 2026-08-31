#!/usr/bin/env nbb
;; yata-cost-model.cljs — YATA（保管の計量単位）の原価と掲示価格を計算する。
;;
;; ADR-2608312900 の計算器。**この数字は再計算できなければならない** ——
;; ADR-2607321200 の known-gap「採点が scratchpad にしか無かった」と同じ轍を
;; 踏まないために、掲示価格を主張する側がここに在る。
;;
;;   nbb scripts/yata-cost-model.cljs
;;
;; 入力はすべて `inputs` に出所つきで置く。導出値は 1 つも手で持たない。
;; レートを測り直したら `inputs` だけを直す —— 表は全部そこから出る。
(require '[clojure.string :as str])

;; ── 入力（すべて 2026-08-31 に取得・実測） ────────────────────────────────
(def inputs
  {:b2/storage-usd-per-tb-month  {:v 6.95   :src "backblaze.com/cloud-storage/pricing 実取得 2026-08-31。ADR-2607299200 が独自に訂正した $6.95 と一致"}
   :b2/egress-usd-per-gb         {:v 0.01   :src "同上。ただし保存量の 3 倍までは無料"}
   :r2/storage-usd-per-gb-month  {:v 0.015  :src "developers.cloudflare.com/r2/pricing 実取得 2026-08-31（Standard）"}
   :r2/egress-usd-per-gb         {:v 0.0    :src "同上。R2 は egress 無料"}
   :r2/class-a-usd-per-million   {:v 4.50   :src "同上"}
   :r2/class-b-usd-per-million   {:v 0.36   :src "同上"}
   :kura/expansion               {:v 1.625  :src "ADR-2607299200 実測 4 domains x 7 = 28 >= 26。倍率 2.0 -> 1.625"}
   :peg/credits-per-usd          {:v 100.0  :src "ADR-2607030030 / 2608026500。$1 = 100 credits。再ペグしない"}
   :margin/multiple              {:v 2.0    :src "ADR-2608026200 掲示 = 原価 x 2.0（保管は弾力供給なので適用可）"}
   :block/min-kb                 {:v 16.0   :src "kotobase-peer.block-sizing。block-cache / tia の 4 箇所で一致"}
   :block/max-kb                 {:v 128.0  :src "同上"}})

(defn v [k] (:v (get inputs k)))

;; ── 原価（$/GB-month）──────────────────────────────────────────────────
(def b2-gb-month (/ (v :b2/storage-usd-per-tb-month) 1000.0))
(def r2-gb-month (v :r2/storage-usd-per-gb-month))
(def kura-gb-month (* b2-gb-month (v :kura/expansion)))   ; shard host が B2 のとき

(def backends
  [{:name "B2 直"                    :cost b2-gb-month   :egress (v :b2/egress-usd-per-gb)}
   {:name "kura (B2 backed, 1.625x)" :cost kura-gb-month :egress (v :b2/egress-usd-per-gb)}
   {:name "R2 直 (Standard)"          :cost r2-gb-month   :egress (v :r2/egress-usd-per-gb)}])

(def worst-storage (apply max (map :cost backends)))
(def worst-egress  (apply max (map :egress backends)))

(defn usd->credits [usd] (* usd (v :peg/credits-per-usd)))
(def posted-storage-usd (* worst-storage (v :margin/multiple)))
(def posted-egress-usd  (* worst-egress  (v :margin/multiple)))
(defn r2f [x] (/ (js/Math.round (* x 10000)) 10000.0))

(println "── 原価（$/GB-month、保管のみ）─────────────────────────────")
(doseq [{:keys [name cost egress]} backends]
  (println (str "  " (.padEnd name 30)
                "$" (r2f cost) "/GB月    egress $" (r2f egress) "/GB")))
(println (str "  最も高い backend  = $" (r2f worst-storage) "/GB月"))
(println)
(println "── 掲示価格（原価 x 2.0、最も高い backend を基準）─────────")
(println (str "  保管  $" (r2f posted-storage-usd) "/GB月  = "
              (r2f (usd->credits posted-storage-usd)) " credits/GB月"))
(println (str "  egress $" (r2f posted-egress-usd) "/GB   = "
              (r2f (usd->credits posted-egress-usd)) " credits/GB"))
(println)
(println "── D6 step 2: eligible な全 backend が掲示価格で黒字か ────")
(doseq [{:keys [name cost]} backends]
  (println (str "  " (.padEnd name 30) "margin x" (r2f (/ posted-storage-usd cost))
                (if (>= (/ posted-storage-usd cost) 1.0) "  OK" "  赤字"))))
(println)
(println "── D6 step 3: 整数 credits で表せるか ──────────────────────")
(let [s (usd->credits posted-storage-usd) e (usd->credits posted-egress-usd)]
  (println (str "  保管 " (r2f s) " -> 整数か: " (== s (js/Math.round s))))
  (println (str "  egress " (r2f e) " -> 整数か: " (== e (js/Math.round e)))))
(println)
(println "── 掲示価格に入っていないもの: write operation ─────────────")
(doseq [kb [(v :block/min-kb) (v :block/max-kb)]]
  (let [blocks-per-gb (/ (* 1024.0 1024.0) kb)
        usd (* (/ blocks-per-gb 1000000.0) (v :r2/class-a-usd-per-million))]
    (println (str "  block " (int kb) " KB -> " (int blocks-per-gb) " class-A/GB書込 = $"
                  (r2f usd) "/GB  = 保管 " (r2f (/ usd r2-gb-month)) " か月分"))))

(println)
(println "══ 掲示価格の候補は eligible set の取り方で変わる ══════════")
(defn scenario [label rows]
  (let [worst (apply max (map :cost rows))
        posted (* worst (v :margin/multiple))
        cr (usd->credits posted)
        cr-int (js/Math.round cr)
        posted-int (/ cr-int (v :peg/credits-per-usd))]
    (println (str "\n【" label "】 eligible = " (str/join " / " (map :name rows))))
    (println (str "  最も高い原価 $" (r2f worst) "/GB月 -> 掲示 $" (r2f posted)
                  " = " (r2f cr) " credits"))
    (println (str "  整数に丸めると " cr-int " credits = $" (r2f posted-int) "/GB月 = $"
                  (r2f (* posted-int 1000)) "/TB月"))
    (doseq [{:keys [name cost]} rows]
      (let [m (/ posted-int cost)]
        (println (str "    " (.padEnd name 30) "margin x" (r2f m)
                      (if (>= m (v :margin/multiple)) "  OK" "  ← 2.0x 未満")))))
    posted-int))

(def a (scenario "案A 全 backend" backends))
(def b (scenario "案B 耐久面は B2/kura のみ（R2 は edge/cache 扱い）"
                 (remove #(= "R2 直 (Standard)" (:name %)) backends)))
(println)
(println "── 市場との比較（$/TB月）──────────────────────────────────")
(doseq [[n p] [["案A 掲示" (* a 1000)] ["案B 掲示" (* b 1000)]
               ["B2 実勢" 6.95] ["Storj（ADR-2607299200 引用）" 7.00]
               ["kura 自身の導出顧客価格" 3.75]]]
  (println (str "  " (.padEnd n 34) "$" (r2f p))))

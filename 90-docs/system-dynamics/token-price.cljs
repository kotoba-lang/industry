(ns token-price
  "murakumo の LLM トークン単価と credits 建ての最適値を計算する。

   実測:
   - fleet 原価 ¥20.04/Mtok（:hyp/murakumo-tok-price validated 2026-07-23）
   - head 容量 40M tok/日（maturity note 2026-07-29）
   - 現行 default-per-token = 1 credit/token（i64）、mint $1 = 100cr
   公開情報（2026-08-02）:
   - Qwen3 30B A3B（murakumo が serve する qwen3.6-35b-a3b の最も近い公開比較対象）
     = $0.12/Mtok 入力・$0.50/Mtok 出力（OpenRouter / DeepInfra、5 provider 中最安）")

(def fx 157.0)
(def fleet-yen-mtok 20.04)
(def fleet-usd-mtok (/ fleet-yen-mtok fx))
(def head-tok-day 40e6)
(def rev-side 0.044)          ; Stripe 2.9% + Tax 0.5% + CB 引当 1.0%
(def floor 0.30)              ; ADR-2608026200 §5-5 の寄与率 floor

(def market {:in 0.12 :out 0.50})   ; Qwen3 30B A3B の実勢（OpenRouter / DeepInfra）
;; prefill 287.5 tok/s vs generation 70.5 tok/s（実測）→ 入力は約 1/4 のコスト
(def prefill-speedup (/ 287.5 70.5))

(defn r [n x] (let [m (js/Math.pow 10 n)] (/ (js/Math.round (* m x)) m)))
(defn pct [x] (str (r 1 (* 100 x)) "%"))

(println "=== 0. 現行価格が壊れている理由 ===")
(let [cur-per-tok 0.01                     ; 1 credit/token × $0.01/credit
      cur-mtok (* cur-per-tok 1e6)]
  (println (str "  default-per-token = 1 credit/token、mint $1 = 100cr"))
  (println (str "  → $" cur-per-tok "/token = $" (r 0 cur-mtok) " / Mtok"))
  (println (str "  → 実勢 $" (:out market) "/Mtok の " (r 0 (/ cur-mtok (:out market))) " 倍"))
  (println "  原因: default-per-token は kotoba oracle の **i64**（整数）で、")
  (println "        正の最小整数が 1。誰かが $0.01/token を選んだのではなく、")
  (println "        **表現可能な最小値がそのまま価格になっていた**。"))

(println "\n=== 1. 原価（実測）===")
(println (str "  出力トークン: ¥" fleet-yen-mtok "/Mtok = $" (r 4 fleet-usd-mtok) "/Mtok"))
(let [in-cost (/ fleet-usd-mtok prefill-speedup)]
  (println (str "  入力トークン: prefill が " (r 2 prefill-speedup) "x 速い → 約 $" (r 4 in-cost) "/Mtok"))
  (def fleet-in-usd-mtok in-cost))

(println "\n=== 2. 実勢との位置関係 ===")
(doseq [[k cost mk] [[:out fleet-usd-mtok (:out market)] [:in fleet-in-usd-mtok (:in market)]]]
  (println (str "  " (name k) ": 原価 $" (r 4 cost) " vs 実勢 $" mk
                " → 実勢で売れば粗利 " (pct (- 1 (/ cost mk)))
                "、原価に対する余裕 " (r 2 (/ mk cost)) "x")))

(println "\n=== 3. 価格候補と寄与率 ===")
(println "  価格($/Mtok)\t寄与率(out)\t寄与率(in)\t実勢比\t40M tok/日 完売時の月商")
(doseq [[in out label] [[0.12 0.50 "実勢に一致"]
                        [0.10 0.40 "実勢を 17-20% 下回る"]
                        [0.08 0.32 "実勢を 33-36% 下回る"]
                        [0.06 0.26 "原価×2.0（ADR-2608026200 の規則）"]
                        [0.03 0.13 "実勢の 1/4"]]]
  (let [c-out (- 1 (/ fleet-usd-mtok (* out (- 1 rev-side))))
        c-in  (- 1 (/ fleet-in-usd-mtok (* in (- 1 rev-side))))
        month (* 30 (/ head-tok-day 1e6) out)]
    (println (str "  $" in " / $" out "\t" (pct c-out) "\t\t" (pct c-in)
                  "\t\t" (r 2 (/ out (:out market))) "x\t$" (r 0 month) "/月"
                  (when (or (< c-out floor) (< c-in floor)) "  ← floor 割れ")
                  "  " label))))

(println "\n=== 4. 容量制約下では原価×2.0 の規則が効かない ===")
(println (str "  head 容量 " (r 0 (/ head-tok-day 1e6)) "M tok/日 は固定。"))
(println "  同じモデル（Qwen3 30B A3B 級）が 5 provider から買える = **完全な代替財**。")
(println "  → murakumo に価格支配力は無く price-taker。かつ容量が上限なので、")
(println "     実勢を大きく下回る値付けは『どうせ売り切る容量』の粗利を捨てるだけ。")
(println "  media（fal が無限に供給する）で原価×2.0 が正しかったのとは逆の構造。")

(println "\n=== 5. credits 建て ===")
(def peg 100.0)   ; $1 = 100 credits（ADR-2607030030、据置）
(doseq [[label usd] [["入力" 0.10] ["出力" 0.40]]]
  (println (str "  " label " $" usd "/Mtok × " peg "cr/$ = **" (r 0 (* usd peg)) " cr / Mtok**"
                "（= " (r 8 (/ (* usd peg) 1e6)) " cr/token）")))
(println "  → **課金単位を :tokens から :mtokens（100万トークン）へ変える。**")
(println "     業界の見積もり単位（$/1M tok）と一致し、整数で表現でき、")
(println "     oracle の i64 がそのまま使える。ペグは変えないので既存残高の意味も変わらない。")

(println "\n=== 6. x402 の $0.01/req も同じ理由で壊れている ===")
(let [tok-per-req 270
      new-out 0.40
      cost-per-req (* (/ tok-per-req 1e6) new-out)
      tokens-for-1cent (/ 0.01 (/ new-out 1e6))]
  (println (str "  1 req ≒ " tok-per-req " tok（実測）→ 新価格での相当額 $" (r 6 cost-per-req)))
  (println (str "  $0.01 を 1 req に課すのは " (r 0 (/ 0.01 cost-per-req)) " 倍の過大請求"))
  (println (str "  → $0.01 は **" (r 0 tokens-for-1cent) " 出力トークン分**のバンドルにする")))

(println "\n=== 7. 結論 ===")
(println "  掲示: **入力 10 cr/Mtok（$0.10）/ 出力 40 cr/Mtok（$0.40）**")
(println (str "  寄与率 out " (pct (- 1 (/ fleet-usd-mtok (* 0.40 (- 1 rev-side)))))
              " / in " (pct (- 1 (/ fleet-in-usd-mtok (* 0.10 (- 1 rev-side)))))
              " —— floor 30% を大きく超える"))
(println (str "  容量完売時の月商 $" (r 0 (* 30 (/ head-tok-day 1e6) 0.40)) " —— "
              "これは事業の規模ではなく、内需の原価優位を測る物差し"))

(ns mk1-jp-taxwindow-schedule
  "日本で実績を作るときの逆算。中小企業経営強化税制 B類型は『設備取得前』に
   確認書が要るので、決算期末から逆に引くと**今日が動くべき日かどうか**が出る。

   オーナー判断(2026-08-18): ひとまず日本にいるので日本で実績を作る。

   ■ 実測（2026-08-18 確認）
   - 経営力向上計画の**標準処理期間は概ね 30 日**（中小企業庁）。取得日から 60 日以内の
     申請受理で特例的に取得後の認定も可。
   - **B類型の経済産業大臣の確認書は『設備取得前』に申請・取得する**（A類型は初代機に
     比較対象の旧モデルが無く使えない。ADR-2608153400 決定3）。
   - 確認書は 公認会計士/税理士の事前確認 → 経済産業局 の 2 段。
   - 適用期限は **令和10年3月末 = 2028-03-31**（ADR-2607268000）。
   - **3月決算が最多**（ADR-2608153400 決定9）。"
  (:require [clojure.string :as str]))

(defn d [s] (js/Date. s))
(defn minus [date days] (js/Date. (- (.getTime date) (* days 86400000))))
(defn fmt [date] (.slice (.toISOString date) 0 10))
(defn days-from-today [date]
  (js/Math.round (/ (- (.getTime date) (.getTime (js/Date. "2026-08-18"))) 86400000)))
(defn pad [s n] (.padEnd (str s) n))
(defn lp [s n] (.padStart (str s) n))

;; 各工程の所要日数（後ろから前へ積む）
(def steps
  [["期末までの据付余裕"          30 "供用開始が期末ぎりぎりだと事故る"]
   ["納品 → 供用開始"              14 "据付・検収"]
   ["組立・検査"                   14 "ADR-2607268000 の転換費に対応"]
   ["部材確保"                     30 "DDR5/NAND 逼迫。ADR-2607267000 は 14 日を楽観と注記"]
   ["**確認書の取得完了**"          0 "★ここより後ろで設備を取得する（B類型の要件）"]
   ["経済産業局の確認書処理"        30 "標準処理期間"]
   ["公認会計士/税理士の事前確認"    21 "投資計画の妥当性確認"]
   ["投資計画の作成"               21 "★実測 tok/s が入力。murakumo が算定資料を出す工程"]
   ["**性能 gate の実測**"          0 "★ここが全ての起点。39.2 tok/s + Poff/Psleep/Pidle"]])

(def windows
  [{:label "2026年12月期末（12月決算）" :fy-end (d "2026-12-31") :note "母数は小さい"}
   {:label "2027年3月期末（3月決算・最多）" :fy-end (d "2027-03-31") :note "本命"}
   {:label "2028年3月期末（適用期限の最終回）" :fy-end (d "2028-03-31") :note "制度の最後の年度"}])

(println "=== 日本で実績を作る — 決算期末からの逆算 ===")
(println "基準日 2026-08-18\n")

(doseq [{:keys [label fy-end note]} windows]
  (println (str "--- " label "  (" note ") ---"))
  (loop [date fy-end ss steps]
    (when (seq ss)
      (let [[nm days why] (first ss)
            date' (minus date days)
            dd (days-from-today date')]
        (println (str "  " (pad nm 26) (lp (fmt date') 12)
                      (lp (if (neg? dd) (str "**" dd " 日（過ぎている）**") (str "T+" dd " 日")) 22)
                      "  " why))
        (recur date' (rest ss)))))
  (println))

(println "--- 読み方 ---")
(println "  『**性能 gate の実測**』の行が、その決算期を取りに行くために")
(println "  **今日から何日後までに測り終えていなければならないか**である。")
(println "  T が負なら、その窓はもう取れない。")
(println "\n  gate が全ての起点なのは 2 つの理由による:")
(println "   ① B類型の投資計画の根拠そのものが実測 tok/s である（ADR-2607268000 決定10）")
(println "   ② 同じ測定で Poff/Psleep/Pidle が取れ、EU の EcoDesign も同時に解ける（ADR-2608180500 決定12）")

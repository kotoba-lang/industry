(ns mk1-jp-dm-mailing-list
  "DM の宛名 CSV をセグメント別に出す。入力は mk1-jp-leads-enriched.edn。

   ■ 除外する行を黙って落とさない
   郵便番号が無い / 登記 status が通常でない / 従業員 1,000 人超 は **除外理由つきで
   別ファイルに出す**。落としたことが出力から読めないと、1,014 が 1,008 になった
   ことに気付けない。

   ■ 並び順は郵便番号昇順
   郵便区分の作業と、差出時の区分郵便割引を考えると番号順が扱いやすい。

   ■ 宛名
   代表者名は実測 55/1014 しか埋まらない。**印刷物の体裁を揃えるため既定は
   『代表者様』**とし、代表者名は別カラムで渡す（使うかは印刷側の判断）。"
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            ["fs" :as fs]))

(def in-path "90-docs/business/mk1-jp-leads-enriched.edn")
(def out-dir "/private/tmp/claude-501/-Users-junkawasaki-github-com-junkawasaki/ffd60345-20a5-4956-86bd-5b31b0dfaf84/scratchpad/")

(defn die2 [m] (println (str "CANNOT ANSWER — " m)) (js/process.exit 2))

(defn zip [s] (if (and s (= 7 (count s))) (str (subs s 0 3) "-" (subs s 3)) (or s "")))
(defn q [s] (str "\"" (str/replace (str (or s "")) "\"" "\"\"") "\""))
(defn row [cs] (str/join "," (map q cs)))

(def seg-name {"A" "A データ機密型" "B" "B 製造業" "C" "C 建設業"})

(defn -main []
  (when-not (fs/existsSync in-path) (die2 (str in-path " が無い。enrich を先に走らせる")))
  (let [d (reader/read-string (str (fs/readFileSync in-path)))
        _ (when (zero? (count d)) (die2 "入力が 0 件"))
        reason (fn [r]
                 (cond (not (:company/postal r)) "郵便番号なし"
                       (and (:company/status r) (not (contains? #{"-" "" nil} (:company/status r)))) 
                       (str "登記 status=" (:company/status r))
                       (and (:company/employees r) (> (js/Number (:company/employees r)) 1000))
                       "従業員1,000人超（中小企業者等から外れうる）"
                       :else nil))
        tagged (map (fn [r] (assoc r ::reason (reason r))) d)
        good (remove ::reason tagged)
        bad  (filter ::reason tagged)
        hdr ["整理番号" "セグメント" "郵便番号" "住所" "会社名" "宛名"
             "代表者名(参考)" "法人番号" "事業分野" "認定日" "従業員数(参考)"]]
    (doseq [[code xs] (group-by :pool/segment good)]
      (let [sorted (sort-by #(or (:company/postal %) "9999999") xs)
            lines (map-indexed
                   (fn [i r]
                     (row [(str code "-" (.padStart (str (inc i)) 4 "0"))
                           (get seg-name code code)
                           (zip (:company/postal r))
                           (or (:company/location r) (str (:company/pref r) (:company/address r)))
                           (:company/name r)
                           "代表者様"
                           (or (:company/rep r) "")
                           (:company/houjin-bangou r)
                           (:company/field r)
                           (:cert/date r)
                           (or (:company/employees r) "")]))
                   sorted)
            path (str out-dir "mk1-dm-" code ".csv")]
        (fs/writeFileSync path (str "﻿" (row hdr) "\n" (str/join "\n" lines) "\n"))
        (println (str "SEG " code "\t送付可=" (count sorted) "\t→ " path))))
    (let [path (str out-dir "mk1-dm-EXCLUDED.csv")]
      (fs/writeFileSync path
        (str "﻿" (row (conj hdr "除外理由")) "\n"
             (str/join "\n" (map #(row [(str (:pool/segment %) "-除外") (get seg-name (:pool/segment %))
                                        (zip (:company/postal %))
                                        (or (:company/location %) (str (:company/pref %) (:company/address %)))
                                        (:company/name %) "代表者様" (or (:company/rep %) "")
                                        (:company/houjin-bangou %) (:company/field %) (:cert/date %)
                                        (or (:company/employees %) "") (::reason %)]) bad)) "\n"))
      (println (str "除外\t" (count bad) " 件\t→ " path))
      (doseq [b bad] (println (str "   - " (:company/name b) " : " (::reason b)))))
    (println (str "\n合計 " (count d) " = 送付可 " (count good) " + 除外 " (count bad)))
    (when (not= (count d) (+ (count good) (count bad))) (die2 "件数が合わない"))))

(-main)

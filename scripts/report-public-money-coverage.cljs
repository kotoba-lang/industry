#!/usr/bin/env nbb
;; report-public-money-coverage.cljs — 「国から金を受けた会社」を答える 3 経路が、
;; **同じ番号集合に対してどれだけ違う会社を見ているか**を毎回測り直す。
;;
;; 経路は独立している:
;;
;;   gbizinfo         経産省が集約したもの（補助金・調達・表彰・認定・財務）
;;   gyousei-review   各府省が自分の事業について出す一次資料（支出先上位 10 者）
;;   kanpou-chotatsu  官報の落札公示（政府調達の公示そのもの）
;;
;; **どれか 1 つを「交付先の一覧」として読むと外れる。** 実測 2026-08-19 の時点では
;; 補助金は集約が厚く、調達は集約がほぼ空で官報と原典が担っていた —— しかしこれは
;; 測った日の値であって定数ではない（gBizINFO の調達は 308,661 行のうち 186 行しか
;; 我々の番号に当たっていない、という当てはまり方の問題）。だから script にしてある。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/report-public-money-coverage.cljs

(require '[clojure.string :as str])
(def cp (js/require "node:child_process"))

(def datasets ["gbizinfo" "gyousei-review" "kanpou-chotatsu"])

(defn- run-all
  "**面の load は 1 回にする。** 問い 1 つにつき 1 プロセスにすると、9 問で
   9 回 load して 18 分かかる（実測 2026-08-19、10 分の上限で切れた）。
   `q*` は 1 回の load で複数の問いに答える。"
  [queries]
  (let [r (.spawnSync cp "nbb"
                      (clj->js (concat ["--classpath" ".:scripts/nbb_compat"
                                        "manifest/edn-query.cljs" "q*"] queries))
                      #js {:encoding "utf8" :maxBuffer (* 64 1024 1024)})]
    (when (pos? (or (.-status r) 1))
      (println (str (.-stderr r)))
      (println "report-public-money-coverage: the query plane refused — cannot answer")
      (js/process.exit 2))
    (let [out (str/trim (last (remove str/blank? (str/split-lines (str (.-stdout r))))))
          ;; ⚠ **`nil` を数として読む。** 重なりが 0 のとき datascript のスカラ集約は
          ;; `nil` を返す（実測 2026-08-19、官報 × gbizinfo がそれで、数字だけを拾う
          ;; parser は「答えが 1 つ足りない」と言って止まった）。**0 と「引けなかった」は
          ;; 別**なので、nil は 0 に写しつつ、答えの個数が合わなければ止める。
          nums (mapv (fn [tok] (if (= "nil" tok) 0 (js/parseInt tok 10)))
                     (re-seq #"\d+|nil" (str/replace out #"[\[\]]" " ")))]
      (when (not= (count nums) (count queries))
        (println (str "report-public-money-coverage: expected " (count queries)
                      " answers, parsed " (count nums) " from: " out))
        (js/process.exit 2))
      nums)))

(def pairs [["gyousei-review" "gbizinfo"]
            ["kanpou-chotatsu" "gbizinfo"]
            ["kanpou-chotatsu" "gyousei-review"]])

(defn- count-q [ds]
  (str "[:find (count-distinct ?hb) . :where [?e \"source/dataset\" \"" ds "\"] [?e \"company/houjin-bangou\" ?hb]]"))

(defn- shared-q [a b]
  (str "[:find (count-distinct ?hb) . :where"
       " [?x \"source/dataset\" \"" a "\"] [?x \"company/houjin-bangou\" ?hb]"
       " [?y \"source/dataset\" \"" b "\"] [?y \"company/houjin-bangou\" ?hb]]"))

(defn- only-q [a b]
  (str "[:find (count-distinct ?hb) . :where"
       " [?x \"source/dataset\" \"" a "\"] [?x \"company/houjin-bangou\" ?hb]"
       " (not-join [?hb] [?y \"source/dataset\" \"" b "\"] [?y \"company/houjin-bangou\" ?hb])]"))

(def repos
  "経路 -> committed projection を持つ checkout（west path の末尾）。"
  {"gbizinfo" "orgs/com-junkawasaki/jp-go-gbiz-info"
   "gyousei-review" "orgs/com-junkawasaki/jp-go-gyoukaku-review"
   "kanpou-chotatsu" "orgs/com-junkawasaki/jp-go-npb-kanpou"})

(defn declared
  "committed ファイルの manifest が申告する**このファイルの件数**の合計。

   ⚠ `:corpus/record-count` の意味は 1 つに固定されている必要がある。実測
   2026-08-20、gbizinfo だけ「読んだ元ファイルの行数」を入れており、合計すると
   1,035,804（実体 3,034）になった —— **key が 2 つの意味を持つ間、この照合は
   成り立たない**。生成器と検査器を直してから、この検査を足した。

   dataset 名で絞るのは、1 つの repo に複数 dataset が入るため
   （jp-go-npb-kanpou は kessan / chotatsu / kaisan の 3 つ）。"
  [ds]
  (let [fsmod (js/require "node:fs")
        dir (str (get repos ds) "/data")]
    (when (.existsSync fsmod dir)
      (reduce
       (fn [acc f]
         (let [lines (str/split-lines (.readFileSync fsmod (str dir "/" f) "utf8"))
               head (first lines)]
           (if (and (str/includes? (str head) ":corpus/manifest true")
                    (str/includes? (str head) (str "\"" ds "\"")))
             (+ acc (reduce + 0 (map #(js/parseInt % 10)
                                     (map second (re-seq #":corpus/record-count (\d+)"
                                                         (str/join "\n" (filter #(str/includes? % ":corpus/manifest true") lines)))))))
             acc)))
       0
       (filter #(str/ends-with? % ".datoms.edn") (js->clj (.readdirSync fsmod dir)))))))

(let [queries (concat (map count-q datasets)
                      (mapcat (fn [[a b]] [(shared-q a b) (only-q a b)]) pairs))
      answers (run-all queries)
      [c1 c2 c3 & rest*] answers
      counts (map vector datasets [c1 c2 c3])]
  ;; ---- 人が読む表 ----
  (println "companies with a 法人番号, by route:")
  (doseq [[d n] counts] (println (str "  " d "\t" n)))
  (println "\npairwise:")
  (doseq [[[a b] [sh only]] (map vector pairs (partition 2 rest*))]
    (println (str "  " a " × " b "\tshared=" sh "\tonly-in-" a "=" only)))

  ;; ---- detector protocol ----
  ;;
  ;; **見つけるのは「経路が答えなくなったこと」であって、重なりの少なさではない。**
  ;; 3 経路が互いにほとんど重ならないのは測った事実（ADR-2608181000 25 節）で、
  ;; 欠陥ではない。欠陥は**答えが 0 になること** —— projection が空になる、loader が
  ;; 移動したパスを見続ける（実測 2026-08-19: yabai の loader が旧パスを見ており、
  ;; 3,288 entity が「1 件も無い」と同じ顔で消えていた）。
  (doseq [[d n] counts]
    (when (zero? n)
      (println (str "FINDING\thigh\troute-silent:" d
                    "\tthe plane answers 0 companies for this route —— projection emptied,"
                    " or the loader is pointing at a path that moved"))))
  ;; ---- ファイルが申告する件数と、面が実際に載せた件数 ----
  ;;
  ;; **経路が痩せても 0 にならなければ上の検査は黙る。** 実測で 2 回踏んだ形:
  ;; tier の既定から外れて載らない（closures）、loader が移動したパスを見続ける
  ;; （yabai）。committed の申告と突き合わせれば、どちらも「載っていない」と言える。
  (let [loaded (into {} (map (fn [[d n]] [d n])) counts)]
    (doseq [[d _] counts
            :let [dec* (declared d)
                  ;; 面の側は entity 数（manifest 行を含む）ではなく**レコード数**で
                  ;; 比べる必要があるが、この report が持つのは会社数（distinct）。
                  ;; したがって「申告 > 0 なのに会社 0」だけを見る —— 会社数と
                  ;; レコード数は別物なので、差の大小は言わない。
                  ]
            :when (and dec* (pos? dec*) (zero? (get loaded d 0)))]
      (println (str "FINDING\thigh\tdeclared-but-not-loaded:" d
                    "\tcommitted files declare " dec* " record(s) but the plane answers 0 company —— "
                    "the loader, the tier default, or the west path is wrong"))))

  ;; **走った証拠**。0 経路を走査して「異常なし」と言わせない。
  (println (str "SCANNED\t" (count datasets) "\troutes\t"
                (str/join " " (map (fn [[d n]] (str d "=" n "/declared=" (declared d))) counts))))
  (println "\n(0 means measured-and-disjoint; a query that could not run exits 2)")
  (js/process.exit (if (some (fn [[_ n]] (zero? n)) counts) 1 0)))

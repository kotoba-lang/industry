;; j_scan.cljs — ISIC J（情報通信）の営利企業に、同じ検出器をかける。
;; 入力は curl で取得した公開 ToS の本文（tos-j/*.txt）。
(require '[clojure.string :as str])
(def fs (js/require "fs"))
(def dir "/private/tmp/claude-501/-Users-junkawasaki-github-com-junkawasaki/4601d432-1579-42ab-8bc2-cd06733e1d56/scratchpad/tos-j")

(def SIGNALS
  [{:mech :lockin :dir :neg :ja "退出コスト"
    :pats [#"(?i)automatic(ally)? renew" #"(?i)auto-renew" #"(?i)minimum (term|commitment|period)"
           #"(?i)early termination (fee|charge)" #"(?i)cancellation fee" #"(?i)non-?cancellable"
           #"(?i)initial term of" #"(?i)liquidated damages"]}
   {:mech :unilateral :dir :neg :ja "一方的変更"
    :pats [#"(?i)may (modify|change|amend|revise) these terms at any time"
           #"(?i)at (our|its) sole discretion" #"(?i)without (prior )?notice(?! period)"
           #"(?i)continued use .{0,60}constitutes (your )?acceptance"
           #"(?i)reserve the right to (modify|change|amend)"]}
   {:mech :mismatch :dir :neg :ja "第三者収益"
    :pats [#"(?i)targeted advertis" #"(?i)interest-based advertis" #"(?i)behaviou?ral advertis"
           #"(?i)sell (your |personal )?(information|data)" #"(?i)advertising partners"
           #"(?i)share .{0,40}with third parties for .{0,30}(marketing|advertis)"]}
   {:mech :nonconsent :dir :neg :ja "非同意・追跡"
    :pats [#"(?i)tracking technolog" #"(?i)web beacon" #"(?i)pixel tag" #"(?i)profiling"
           #"(?i)automated decision-?making"]}
   {:mech :asym-power :dir :neg :ja "紛争手段の非対称"
    :pats [#"(?i)binding arbitration" #"(?i)class action waiver" #"(?i)waive .{0,30}class action"
           #"(?i)waiver of .{0,20}jury trial" #"(?i)waive .{0,20}right to a jury"]}
   {:mech :portability :dir :pos :ja "データ可搬性"
    :pats [#"(?i)data portability" #"(?i)export your data" #"(?i)download (a copy of )?your data"
           #"(?i)return .{0,30}your data"]}
   {:mech :exit-right :dir :pos :ja "退出の明示的保証"
    :pats [#"(?i)cancel at any time" #"(?i)terminate .{0,30}at any time (without|with no) (penalty|charge|fee)"
           #"(?i)no (long-?term )?commitment" #"(?i)month-to-month"]}])

(def EXCLUDE #"(?i)(update the information|update any of those|disclaims any duty|become out-of-date|makes no commitment to update)")
(defn hits [text s]
  (->> (:pats s)
       (keep (fn [p] (when-let [m (re-find p text)]
                       (let [ph (if (string? m) m (first m))
                             i (str/index-of (str/lower-case text) (str/lower-case ph))]
                         (when (and i (not (re-find EXCLUDE (subs text (max 0 (- i 120))
                                                                 (min (count text) (+ i (count ph) 120))))))
                           ph)))))
       distinct vec))

(def files (filter #(str/ends-with? % ".txt") (.readdirSync fs dir)))
(def scored
  (for [f files
        :let [t (.readFileSync fs (str dir "/" f) "utf8")
              h (into {} (for [s SIGNALS :let [x (hits t s)] :when (seq x)] [(:mech s) x]))
              neg (count (filter (fn [[m _]] (= :neg (:dir (first (filter #(= m (:mech %)) SIGNALS))))) h))
              pos (- (count h) neg)]]
    {:nm (str/replace (str/replace f ".txt" "") "_" " ") :hits h :neg neg :pos pos
     :net (- pos neg) :bytes (count t)}))

(defn pad [s n] (.padEnd (str s) n))
(println "ISIC J 情報通信 —— 追加取得" (count scored) "社\n")
(println (str "  " (pad "社" 22) (pad "net" 5) (pad "bytes" 8) "検出された機序"))
(doseq [e (sort-by :net scored)]
  (println (str "  " (pad (:nm e) 22) (pad (:net e) 5) (pad (:bytes e) 8)
                (str/join " " (for [s SIGNALS :when (get (:hits e) (:mech s))]
                                (str (if (= :neg (:dir s)) "−" "＋") (:ja s)))))))
(println "\n═══ 機序ごとの検出率 ═══")
(doseq [s SIGNALS]
  (let [n (count (filter #(get (:hits %) (:mech s)) scored))]
    (println (str "  " (if (= :neg (:dir s)) "−" "＋") " " (pad (:ja s) 22) n "/" (count scored)
                  "  " (.toFixed (* 100.0 (/ n (count scored))) 1) "%"))))
(println "\n  平均 net:" (.toFixed (/ (reduce + (map :net scored)) (count scored)) 2))
(println "  3 点セット（退出コスト＋一方的変更＋紛争非対称）を持つ社:"
         (count (filter #(and (get (:hits %) :lockin) (get (:hits %) :unilateral)
                              (get (:hits %) :asym-power)) scored)) "/" (count scored))
(println "\n  ⚠ 10KB 未満は ToS 本文でなく目次ページの可能性:"
         (str/join ", " (map :nm (filter #(< (:bytes %) 10000) scored))))

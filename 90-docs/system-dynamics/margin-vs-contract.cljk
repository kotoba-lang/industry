;; margin-vs-contract.cljs — 実財務 × 実契約。ADR-2608090800。
;; 純利益率は SEC EDGAR（market-intel）の実測、契約条項は公開 ToS の実測。
(require '[clojure.string :as str])
(def fs (js/require "fs"))
(def dir "/private/tmp/claude-501/-Users-junkawasaki-github-com-junkawasaki/4601d432-1579-42ab-8bc2-cd06733e1d56/scratchpad/tos-big")
;; 純利益率はすべて market-intel で検証済み（推測値は使わない）
(def MARGIN {"NVIDIA" 0.5560 "Microsoft" 0.3615 "Alphabet" 0.3281 "Apple" 0.2692
             "Netflix" 0.2430 "Airbnb" 0.2051 "PayPal" 0.1578 "Amazon" 0.1083})
(def SIGNALS
  [{:m :lockin :d :neg :ja "退出コスト"
    :p [#"(?i)automatic(ally)? renew" #"(?i)auto-renew" #"(?i)minimum (term|commitment|period)"
        #"(?i)early termination (fee|charge)" #"(?i)cancellation fee" #"(?i)non-?cancellable"]}
   {:m :unilateral :d :neg :ja "一方的変更"
    :p [#"(?i)may (modify|change|amend|revise) these terms at any time" #"(?i)at (our|its) sole discretion"
        #"(?i)without (prior )?notice(?! period)" #"(?i)reserve the right to (modify|change|amend)"]}
   {:m :mismatch :d :neg :ja "第三者収益"
    :p [#"(?i)targeted advertis" #"(?i)interest-based advertis" #"(?i)advertising partners"
        #"(?i)sell (your |personal )?(information|data)"]}
   {:m :nonconsent :d :neg :ja "非同意・追跡"
    :p [#"(?i)tracking technolog" #"(?i)web beacon" #"(?i)pixel tag" #"(?i)profiling"]}
   {:m :asym :d :neg :ja "紛争手段の非対称"
    :p [#"(?i)binding arbitration" #"(?i)class action waiver" #"(?i)waive .{0,30}class action"
        #"(?i)waiver of .{0,20}jury trial"]}
   {:m :port :d :pos :ja "データ可搬性"
    :p [#"(?i)data portability" #"(?i)export your data" #"(?i)download (a copy of )?your data"]}
   {:m :exit :d :pos :ja "退出の明示的保証"
    :p [#"(?i)cancel at any time" #"(?i)no (long-?term )?commitment" #"(?i)month-to-month"]}])
(def EX #"(?i)(update the information|disclaims any duty|makes no commitment to update)")
(defn hit? [t s]
  (some (fn [p] (when-let [m (re-find p t)]
                  (let [ph (if (string? m) m (first m))
                        i (str/index-of (str/lower-case t) (str/lower-case ph))]
                    (and i (not (re-find EX (subs t (max 0 (- i 120)) (min (count t) (+ i 120)))))))))
        (:p s)))
(def rows
  (->> (keys MARGIN)
       (keep (fn [nm]
               (let [f (str dir "/" nm ".txt")]
                 (when (.existsSync fs f)
                   (let [t (.readFileSync fs f "utf8")
                         h (into #{} (for [s SIGNALS :when (hit? t s)] (:m s)))
                         neg (count (filter #(= :neg (:d %)) (filter #(h (:m %)) SIGNALS)))
                         pos (- (count h) neg)]
                     {:nm nm :margin (MARGIN nm) :hits h :neg neg :pos pos :net (- pos neg)
                      :bytes (count t)})))))
       vec))
(defn pad [s n] (.padEnd (str s) n))
(defn f3 [x] (.toFixed (js/Number x) 3))
(println "実財務 × 実契約 —— " (count rows) "社\n")
(println (str "  " (pad "社" 12) (pad "純利益率" 11) (pad "net" 6) (pad "負" 5) "検出された機序"))
(doseq [r (reverse (sort-by :margin rows))]
  (println (str "  " (pad (:nm r) 12) (pad (f3 (:margin r)) 11) (pad (:net r) 6) (pad (:neg r) 5)
                (str/join " " (for [s SIGNALS :when ((:hits r) (:m s))]
                                (str (if (= :neg (:d s)) "−" "＋") (:ja s)))))))
;; ── 相関 ──────────────────────────────────────────────────────────────
(defn rank [xs] (let [s (vec (sort xs))] (mapv (fn [x] (inc (.indexOf s x))) xs)))
(defn pearson [xs ys]
  (let [n (count xs) mx (/ (reduce + xs) n) my (/ (reduce + ys) n)
        num (reduce + (map (fn [a b] (* (- a mx) (- b my))) xs ys))
        dx (Math/sqrt (reduce + (map #(* (- % mx) (- % mx)) xs)))
        dy (Math/sqrt (reduce + (map #(* (- % my) (- % my)) ys)))]
    (if (or (zero? dx) (zero? dy)) 0 (/ num (* dx dy)))))
(let [ms (mapv :margin rows) ns (mapv :net rows) negs (mapv :neg rows)]
  (println "\n═══ 相関 ═══")
  (println (str "  純利益率 × net スコア      Pearson r = " (f3 (pearson ms ns))))
  (println (str "  純利益率 × 負の機序数      Pearson r = " (f3 (pearson ms negs))))
  (println (str "  順位相関（Spearman）       r = " (f3 (pearson (mapv double (rank ms)) (mapv double (rank ns))))))
  (println (str "\n  n=" (count rows) " —— この標本数では相関係数の信頼区間は非常に広い。")))
(println "\n═══ 機序ごとの検出（高マージン順）═══")
(doseq [s SIGNALS]
  (let [with (filter #((:hits %) (:m s)) rows)
        wo (remove #((:hits %) (:m s)) rows)
        am (if (seq with) (/ (reduce + (map :margin with)) (count with)) nil)
        ao (if (seq wo) (/ (reduce + (map :margin wo)) (count wo)) nil)]
    (println (str "  " (if (= :neg (:d s)) "−" "＋") " " (pad (:ja s) 20)
                  "検出 " (pad (str (count with) "/" (count rows)) 8)
                  "検出社の平均マージン " (pad (if am (f3 am) "—") 9)
                  "非検出 " (if ao (f3 ao) "—")))))

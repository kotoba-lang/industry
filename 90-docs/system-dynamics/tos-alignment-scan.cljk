;; tos_scan.cljs — 実在企業の契約文から「整合度」を観測する。
;;
;; モデルは金くささを Revenue × max(0,-Alignment) と定義した。Alignment は
;; 8 機序の重み付き和だが、そのうち **契約文に書いてあるもの** は外から検証できる。
;; ここでは cloud-itonami-lei-tos の archived ToS（full-text + source-url +
;; retrieved-at + sha256）から、機序ごとに **一致した語句そのもの** を証拠として
;; 抜き出す。スコアではなく引用可能な事実を出す。
;;
;; ⚠ 妥当性の限界（隠さず書く）: これは企業サイトの Terms of Use であって、
;;   その企業の主力事業の顧客契約ではない。Phillips 66 の web ToU は製油所の
;;   引取契約について何も言わない。したがってこの検査は
;;   **関係が click-through 契約で媒介される事業ほど有効**で、B2B 重工業では弱い。
;;   それ自体が結果である（下の「検出可能性」列）。

(require '[clojure.string :as str]
         '[clojure.edn :as edn])

(def fs (js/require "fs"))
(def path (js/require "path"))

;; ── 機序 → 検出する語句（すべて公開 ToS に現れる定型句）────────────────
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
   {:mech :mismatch :dir :neg :ja "第三者収益（広告・データ販売）"
    :pats [#"(?i)targeted advertis" #"(?i)interest-based advertis" #"(?i)behavioou?ral advertis"
           #"(?i)sell (your |personal )?(information|data)" #"(?i)advertising partners"
           #"(?i)share .{0,40}with third parties for .{0,30}(marketing|advertis)"]}
   {:mech :nonconsent :dir :neg :ja "非同意・追跡"
    :pats [#"(?i)tracking technolog" #"(?i)web beacon" #"(?i)pixel tag"
           #"(?i)profiling" #"(?i)automated decision-?making"]}
   {:mech :asym-power :dir :neg :ja "紛争手段の非対称"
    :pats [#"(?i)binding arbitration" #"(?i)class action waiver" #"(?i)waive .{0,30}class action"
           #"(?i)waiver of .{0,20}jury trial" #"(?i)waive .{0,20}right to a jury"]}
   {:mech :portability :dir :pos :ja "データ可搬性・返還"
    :pats [#"(?i)data portability" #"(?i)export your data" #"(?i)download (a copy of )?your data"
           #"(?i)return .{0,30}your data" #"(?i)delete your (personal )?(data|information) (upon|on) (termination|request)"]}
   {:mech :exit-right :dir :pos :ja "退出の明示的保証"
    :pats [#"(?i)cancel at any time" #"(?i)terminate .{0,30}at any time (without|with no) (penalty|charge|fee)"
           #"(?i)no (long-?term )?commitment" #"(?i)month-to-month"]}])

;; 前後 120 字に除外語があれば偽陽性として捨てる。
;; 実測: 「no commitment」の 9 件中 8 件は「情報を更新する義務を負わない」という
;; 免責文であって退出保証ではなかった（Berkshire / Aon / Schwab / Enbridge 等）。
(def EXCLUDE #"(?i)(update the information|update any of those|disclaims any duty|become out-of-date|makes no commitment to update|update those documents)")

(defn- window [text i n] (subs text (max 0 (- i 120)) (min (count text) (+ i n 120))))

(defn find-hits [text {:keys [pats]}]
  (->> pats
       (keep (fn [p]
               (when-let [m (re-find p text)]
                 (let [phrase (if (string? m) m (first m))
                       i (str/index-of (str/lower-case text) (str/lower-case phrase))]
                   (when (and i (not (re-find EXCLUDE (window text i (count phrase)))))
                     phrase)))))
       distinct vec))

(defn ctx [text phrase]
  (let [i (str/index-of (str/lower-case text) (str/lower-case phrase))]
    (when i (-> (subs text (max 0 (- i 60)) (min (count text) (+ i (count phrase) 60)))
                (str/replace #"\s+" " ") str/trim))))

;; ── ToS journal を全部読む ──────────────────────────────────────────────
(def root "/Users/junkawasaki/github/com-junkawasaki/orgs/cloud-itonami")
(defn journals []
  (->> (.readdirSync fs root)
       (filter #(str/starts-with? % "cloud-itonami-lei-"))
       (map #(.join path root % "80-data" "public" "tos.journal.edn"))
       (filter #(.existsSync fs %))))

(def bad (atom []))

(defn read-journal
  "journal は Datomic 形式の 5-tuple 列 [[eid attr value tx op] ...]。
   entity-id で束ねて attr→value の map にする。壊れたファイルは握り潰さず記録する。"
  [p]
  (try
    (let [v (edn/read-string (.readFileSync fs p "utf8"))]
      (if (vector? v)
        (->> v
             (filter #(and (vector? %) (>= (count %) 3)))
             (group-by first)
             (map (fn [[eid ds]] (into {:eid eid} (map (fn [[_ a val & _]] [a val]) ds))))
             vec)
        (do (swap! bad conj p) nil)))
    (catch :default _ (swap! bad conj p) nil)))

(def LEI->NAME
  (let [f "/Users/junkawasaki/github/com-junkawasaki/90-docs/system-dynamics/tos-lei-names.edn"]
    (if (.existsSync fs f) (edn/read-string (.readFileSync fs f "utf8")) {})))

(def entries
  (->> (journals)
       (keep (fn [p]
               (when-let [recs (read-journal p)]
                 (keep (fn [r]
                         (let [txt (:tos/full-text r)]
                           (when (and (string? txt) (> (count txt) 400))
                             {:lei (or (:company/lei r)
                                       (-> p (str/split #"/") (nth 7) (str/replace "cloud-itonami-lei-" "")
                                           str/upper-case))
                              :url (:tos/source-url r) :at (:tos/retrieved-at r) :text txt})))
                       recs))))
       (apply concat) vec))

(println "読めた ToS:" (count entries) "件 / 壊れて読めなかった journal:" (count @bad) "件")

(def scored
  (for [e entries]
    (let [hits (into {} (for [s SIGNALS]
                          (let [h (find-hits (:text e) s)] (when (seq h) [(:mech s) h]))))
          neg (count (filter (fn [[m _]] (= :neg (:dir (first (filter #(= m (:mech %)) SIGNALS))))) hits))
          pos (count (filter (fn [[m _]] (= :pos (:dir (first (filter #(= m (:mech %)) SIGNALS))))) hits))]
      (assoc e :hits hits :neg neg :pos pos :net (- pos neg) :chars (count (:text e))))))

(println "\n═══ 機序ごとの検出率（187 件中）═══")
(doseq [s SIGNALS]
  (let [n (count (filter #(get (:hits %) (:mech s)) scored))]
    (println (str "  " (if (= :neg (:dir s)) "−" "＋") " "
                  (.padEnd (:ja s) 28) n " 件 ("
                  (.toFixed (* 100.0 (/ n (max 1 (count scored)))) 1) "%)"))))

(defn nm [e] (or (get LEI->NAME (:lei e)) (:lei e)))
(defn ev [e]
  (str/join " / " (for [s SIGNALS :let [h (get (:hits e) (:mech s))] :when h]
                    (str (if (= :neg (:dir s)) "−" "＋") (:ja s) "「" (first h) "」"))))

(println "\n═══ 逆行シグナルが最も濃い 10 社（証拠語句つき）═══")
(doseq [e (take 10 (sort-by :net scored))]
  (println (str "\n  " (nm e) "  [net " (:net e) "]"))
  (println (str "    " (ev e)))
  (println (str "    " (:url e) "  取得 " (:at e))))

(println "\n═══ 整合シグナルを持つ社（＋が１つ以上）═══")
(doseq [e (reverse (sort-by :net (filter #(pos? (:pos %)) scored)))]
  (println (str "  [net " (.padStart (str (:net e)) 2) "] " (.padEnd (nm e) 44) (ev e))))

(println "\n═══ 何も検出されなかった社（検出可能性の限界）═══")
(let [none (filter #(empty? (:hits %)) scored)]
  (println "  " (count none) "件。企業サイトの ToU は主力事業の顧客契約ではない。例:")
  (doseq [e (take 8 none)] (println (str "    " (nm e)))))

(.writeFileSync fs "/private/tmp/claude-501/-Users-junkawasaki-github-com-junkawasaki/4601d432-1579-42ab-8bc2-cd06733e1d56/scratchpad/tos-scored.edn"
                (pr-str (mapv #(dissoc % :text) scored)))
(println "\n書き出し: tos-scored.edn")

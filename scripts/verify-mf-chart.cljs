#!/usr/bin/env nbb
;; 写した chart を、shohyo に**実際に食わせて**検査する。
;;
;;   nbb --classpath "orgs/kotoba-lang/shohyo/src:.:scripts/nbb_compat" \
;;       scripts/verify-mf-chart.cljs
;;
;; ## なぜテキスト走査でないのか
;;
;; 隣の `verify-moneyforward-parity.cljs` は shohyo のソースを**テキストとして**
;; 読む（superproject から走るので shohyo の deps が classpath に無い、という
;; 理由が docstring に書いてある）。shohyo は依存ゼロなので、nbb の
;; `--classpath` に src を足せば本物が読み込める —— そして本物は
;; `:section-type-conflict` を返す。区分名が定義されているかどうかだけを見る
;; テキスト走査には、その検査ができない。
;;
;; ここが捕まえるのは主にこれ:
;;   売上原価を :revenue と宣言しても、区分名は正しいので名前の照合は通る。
;;   会社計算規則 第八十九条の側では逆符号になり、売上総利益は**正しい形をして
;;   間違った値**になる。
;;
;; ## 振り分けは財務諸表の種類ではなく、区分がどちらの権威の下にあるかで決める
;;
;; 最初の実装は `financial_statement_type` で振り分け、**6 件を誤って
;; :unknown-section と報告した** —— 売上原価の 3 科目（期首商品棚卸高 /
;; 仕入高 / 期末商品棚卸高）は損益計算書の側に居るが、名前を持っているのは
;; 財務諸表等規則 第七十五条第一項 の `genka/cogs-items` であって
;; 会社計算規則の `jp` ではない。**科目がどの表に印字されるかと、どの条文が
;; その区分を名付けているかは別**である。
;;
;; したがって: 区分が jp の知る名前なら jp が検査し（型の矛盾まで見る）、
;; genka の知る名前なら genka の側で名前だけを確かめ、どちらも知らなければ
;; それが本物の欠落。
;;
;; ## 答えられなかったときは 0 でも 1 でもない
;;
;; chart が無い / 読めない / shohyo の checkout が無い は **exit 3**。
(ns verify-mf-chart
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [kotoba.shohyo :as shohyo]
            [kotoba.shohyo.jp :as jp]
            [kotoba.shohyo.genka :as genka]
            ["fs" :as fs]
            ["path" :as path]))

(def root (or (some-> js/process.env .-FLEET_ROOT) (.cwd js/process)))
(def argv (vec (drop 2 js/process.argv)))
(defn opt [f d] (let [i (.indexOf argv f)] (if (neg? i) d (nth argv (inc i) d))))
(def chart-file (opt "--chart" (path/join root "90-docs" "accounting" "gftd-japan-chart.edn")))
(def dataset (opt "--dataset" (path/join root "90-docs" "accounting" "moneyforward-parity.datoms.edn")))
;; `--findings` は manifest/orgs-detectors.edn の tick 用。FINDING 行は key で
;; 追跡されるので、**key に件数や順序を入れない** —— 1 件増えただけで既存の
;; finding が全部 NEW として再報告される。
(def findings? (some #{"--findings"} argv))

(defn refuse! [why]
  (println (str "REFUSING to report a coverage figure: " why))
  (println "exit 3 — could not answer. This is not a pass and not a gap.")
  (.exit js/process 3))

(defn slurp' [f] (when (fs/existsSync f) (fs/readFileSync f "utf8")))

(def genka-sections
  "genka が名前を持つ区分すべて —— 明細書の 8 つと 売上原価の 3 科目。"
  (into (set (keys genka/cost-report-sections)) (keys genka/cogs-items)))

(defn -main []
  (let [text  (or (slurp' chart-file) (refuse! (str "chart が無い: " chart-file
                                                    " —— scripts/gen-mf-chart.cljs で作る")))
        chart (try (edn/read-string text)
                   (catch :default e (refuse! (str "chart が読めない: " (.-message e)))))
        _     (when-not (and (map? chart) (seq chart)) (refuse! "chart が空、または map ではない"))
        snap  (some->> (slurp' dataset) edn/read-string (filter :mf.snapshot/id) first)
        _     (when-not snap (refuse! (str "parity dataset に snapshot が無い: " dataset)))

        ;; 読み込めたのが本物の shohyo であることを、値の側から確かめる。
        ;; classpath がずれて空の名前空間を掴んでいたら、下の検査は
        ;; **全部きれいに通る**（誰も何も知らないので誰も文句を言わない）。
        _ (when (or (< (count jp/bs-sections) 10)
                    (< (count jp/pl-sections) 5)
                    (not (contains? jp/bs-sections :current-assets))
                    (not (contains? genka/cost-report-sections :labor-costs)))
            (refuse! (str "shohyo が読み込めていない（bs=" (count jp/bs-sections)
                          " pl=" (count jp/pl-sections)
                          "）—— --classpath に orgs/kotoba-lang/shohyo/src を足す")))

        stmt  (into {} (filter (fn [[_ v]] (jp/known-section? (:section v))) chart))
        cost  (into {} (filter (fn [[_ v]] (and (not (jp/known-section? (:section v)))
                                                (contains? genka-sections (:section v))))
                               chart))
        lost  (into {} (remove (fn [[k _]] (or (contains? stmt k) (contains? cost k))) chart))

        ;; 1. chart として使えるか（type が 5 つのどれか、concept が語彙にあるか）
        cprobs (shohyo/chart-problems chart)
        ;; 2. 会社計算規則 が名付けた区分は jp が検査する —— 型の矛盾まで見る
        sprobs (jp/section-problems stmt)
        ;; 3. どちらの権威も知らない区分。これが本物の欠落
        gprobs (vec (map (fn [[k v]] {:account k :problem :unknown-section
                                      :section (:section v)})
                         lost))
        accounts (count (remove (fn [[_ v]] (:mf/sub-account v)) chart))
        subs     (- (count chart) accounts)]

    ;; 数の床。写しが縮んでいたら、残った分がきれいに通って緑になる。
    (when-not (= accounts (:mf.snapshot/accounts snap))
      (refuse! (str "chart は " accounts " 勘定科目、snapshot は "
                    (:mf.snapshot/accounts snap) " —— 写しが欠けている")))
    (when-not (= subs (:mf.snapshot/sub-accounts snap))
      (refuse! (str "chart は " subs " 補助科目、snapshot は "
                    (:mf.snapshot/sub-accounts snap))))

    (println (str "SCANNED\t" (count chart) " chart entries ("
                  accounts " accounts + " subs " sub-accounts) against "
                  (count jp/bs-sections) "+" (count jp/pl-sections)
                  " 会社計算規則 sections and " (count genka-sections)
                  " 財務諸表等規則/原価計算基準 sections"))
    (println (str "SNAPSHOT\t" (:mf.snapshot/office-name snap)
                  " measured " (:mf.snapshot/measured-at snap)))
    (println (str "USABLE\t" (if (shohyo/chart-usable? chart) "yes" "NO")
                  " (" (count cprobs) " chart problems)"))
    (println (str "会社計算規則\t" (- (count stmt) (count (distinct (map :account sprobs))))
                  "/" (count stmt) " entries classified without conflict"))
    (println (str "財務諸表等規則\t" (count cost) " entries under genka"
                  " (売上原価 第七十五条第一項 + 製造原価明細書、内訳は :source :observed)"))
    (println (str "権威なし\t" (count lost) " entries whose section neither authority names"))
    (println (str "TAX-RATES\t"
                  (count (remove (fn [[_ v]] (nil? (:mf/tax-rate v))) chart))
                  "/" (count chart) " entries carry a declared 消費税率 "
                  (pr-str (vec (sort (distinct (keep (fn [[_ v]] (:mf/tax-rate v)) chart)))))))

    (let [all (concat cprobs sprobs gprobs)]
      (when findings?
        (doseq [{:keys [account problem section detail]} (sort-by (juxt :problem :account) all)]
          (println (str "FINDING\tmedium\t" (name (or problem :unknown)) ":" account
                        "\t" (name (or problem :unknown))
                        (when section (str " -> " section))
                        (when detail (str " — " detail))))))
      (if (seq all)
        (do (doseq [[problem rows] (sort-by key (group-by :problem all))]
              (println (str "\n" (name problem) " (" (count rows) "):"))
              (doseq [r (take 12 (sort-by :account rows))]
                (println (str "  " (:account r)
                              (when (:section r) (str "\t-> " (:section r)))
                              (when (:detail r) (str "\t" (:detail r))))))
              (when (> (count rows) 12)
                (println (str "  … 他 " (- (count rows) 12) " 件"))))
            (println (str "\n" (count all) " problems. `statements` はこの chart で"
                          " :incomplete を返すか、区分を取り違える。"))
            (.exit js/process 1))
        (println "\nEvery entry is usable, and every section is one the regulation names.")))))

(-main)

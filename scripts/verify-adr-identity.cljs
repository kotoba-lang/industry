#!/usr/bin/env nbb
;; verify-adr-identity — `:adr/id` が 1 つの文書を指すことを検査する。
;;
;; ## 実測した実害（2026-08-07）
;;
;; `:adr/id "2608080000"` を datom 面に問い合わせると **2 つの別々の ADR が返る**:
;;
;;   nbb --classpath ".:scripts/nbb_compat" manifest/edn-query.cljs q \
;;     '[:find ?id ?t :where [?e "adr/id" ?id] [?e "adr/title" ?t] [(= ?id "2608080000")]]'
;;   → [["2608080000" "成熟度を上げる loop —— …"]
;;      ["2608080000" "各国の法規制（許認可の要件）を datom 面に載せて …"]]
;;
;; 21 個の id が 44 ファイルに分かれており（2 つは 3 ファイル）、さらに 11 個は
;; 他 ADR の `:adr/related` から**構造化された参照**を受けている。参照は
;; `["2608039950" "2608052000" "2608060000"]` のような裸の番号なので、どちらの
;; ADR を指しているかが**構造的に決まらない**。
;;
;; `manifest/schema.edn` の `:adr/id` は unique 宣言を持たない（自動生成で、
;; unique を持つ属性は 1979 個中 0 個）ので DataScript は黙って 2 entity のまま
;; 保持する。一方 `manifest/projection-schemas/agent-source-query-adr.edn` は
;; `:unique :db.unique/identity` を宣言しており、**ADR 全体を projection contract に
;; 載せた瞬間に `project-edn` の identity 一意性検査で落ちる。**
;;
;; ## なぜ既存 21 件を改名しないか
;;
;; 改名すると 26 本の `:adr/related` 参照が dangling になる。どちらを指していたかは
;; 各参照元 ADR を読まないと決まらず、**取り違えると文書の意味を静かに書き換える**。
;; これは機械的な rewrite ではなく判断であり、agent が単独でやってよい種類ではない
;; （ADR-2608062200 で JPY 3,000,000 の衝突を記録だけして解消しなかったのと同じ）。
;;
;; そこでワークスペース自身の先例に合わせる —— `verify-repository-roles` は origin 面の
;; 77 件の誤配置を一括改名せず `:gaps` に日付付きで記録し、**規則は新規登録だけを縛る**。
;; ここも同じ: 既知の 21 件は `known-collisions` に据え、**新しい衝突だけを fail** する。
;;
;; ## 検査
;;
;;   1. :adr/id の重複（known-collisions に無いもの）        → fail
;;   2. :adr/related / :adr/supersedes / :adr/superseded_by が
;;      複数の ADR に解決する曖昧参照（新規のみ）             → fail
;;   3. どの ADR も宣言していない id への dangling 参照        → 報告のみ
;;   4. known-collisions に載っているが既に解消したもの        → fail（債務表の腐敗）
;;
;; 3 を fail にしないのは、`:adr/related` が ADR 以外（ファイルパス・外部文書）も
;; 指す慣習があるため —— 実測で `"90-docs/business/budget-supply.edn"` のような値が
;; 入っている。ADR id の形をしたものだけを dangling と呼ぶのは、その形を定義しないと
;; 決まらず、**bare 数字と slug 形の 2 系統が併存している**現状では定義できない。
;;
;; usage: nbb scripts/verify-adr-identity.cljs [--dir 90-docs/adr] [--min 500]

(require '[clojure.string :as str])

(def fs (js/require "node:fs"))

;; 2026-08-07 実測。**この表を増やしてはいけない** —— 新しい衝突は fail させる。
;; 減らすのは正しい（曖昧参照を解消して改名したら、ここから外す）。
(def known-collisions
  #{"2607091400" "2607091900" "2607121900" "2607122400" "2607142200"
    "2607142400" "2607142500" "2607152900" "2607153800" "2607176000"
    "2607242300" "2607252200" "2608026000" "2608026100" "2608026200"
    "2608026300" "2608026400" "2608026500" "2608052000" "2608060000"
    "2608080000" "2660000000" "ADR-2607276000"})

;; `:adr/id` が文字列でない ADR。`:adr/related` の参照はすべて文字列なので、
;; 数値 id の ADR は**参照グラフから永久に到達できない**。2026-08-07 実測で 171 件
;; あり、全部が cloud-itonami の生成された coverage 2 族に閉じている。
;;
;; 既存分を一括修正しないのは衝突と同じ理由（生成物でない文書の一括改変を避ける）
;; だが、**族の件数を pin して増加も fail にする** —— 族を許すだけだと生成器が
;; 悪い id を出し続けても gate が黙ることになる。
(def known-non-string-id-families
  {"cloud-itonami-isco" 111
   "cloud-itonami-isic" 60})

(defn- id-family [file]
  (some (fn [fam] (when (str/includes? file fam) fam))
        (keys known-non-string-id-families)))

(def ref-attrs [:adr/related :adr/supersedes :adr/superseded_by :adr/superseded-by])

(defn- read-adr [dir f]
  (let [p (str dir "/" f)]
    (try
      (let [tx (cljs.reader/read-string (.readFileSync fs p "utf8"))
            e (first (filter :adr/id tx))]
        ;; `:adr/id` を **string に寄せて** 比較する。manifest/schema.edn 自身が
        ;; 「:adr/id は string または long」と注記しており、実測で 2 件が数値宣言
        ;; だった。数値 id は `:adr/related` の文字列参照と**永久に一致しない**ので、
        ;; 生の値も :raw-id に残して別途報告する。
        (when e {:file f :id (str (:adr/id e)) :raw-id (:adr/id e) :entity e}))
      (catch :default _ nil))))

(defn -main [& args]
  (let [flags (apply hash-map (map str args))
        dir (or (get flags "--dir") "90-docs/adr")
        ;; 債務表（known-collisions / known-non-string-id-families）は 90-docs/adr を
        ;; 記述している。別ディレクトリを見ているときはその表を適用しない。
        corpus? (str/ends-with? (str/replace dir #"/+$" "") "90-docs/adr")
        min-adrs (js/parseInt (or (get flags "--min") "500"))
        files (sort (filter #(str/ends-with? % ".edn") (js->clj (.readdirSync fs dir))))
        adrs (keep #(read-adr dir %) files)]
    (when (< (count adrs) min-adrs)
      (println (str "FAIL ADR が " (count adrs) " 件しか読めない (--min " min-adrs ")。"
                    "\n  --dir が違うか、reader が落ちている。**走査対象ゼロを合格にしない。**"))
      (js/process.exit 1))

    (let [by-id (group-by :id adrs)
          declared (set (keys by-id))
          collisions (into {} (filter (fn [[_ v]] (> (count v) 1)) by-id))
          new-collisions (remove #(contains? known-collisions (key %)) collisions)
          stale-debt (remove #(contains? collisions %) known-collisions)
          ;; 参照 → 解決先の数
          refs (for [a adrs
                     attr ref-attrs
                     r (get (:entity a) attr)
                     :when (string? r)]
                 {:from (:file a) :attr attr :ref r
                  :targets (count (get by-id r []))})
          ambiguous (filter #(> (:targets %) 1) refs)
          new-ambiguous (remove #(contains? known-collisions (:ref %)) ambiguous)
          dangling (filter #(zero? (:targets %)) refs)]

      (println (str "verify-adr-identity: ADR " (count adrs)
                    " / 一意な id " (count declared)
                    " / 参照 " (count refs)))

      (when (seq collisions)
        (println (str "\n既知の衝突（debt、fail させない）: " (count collisions) " id / "
                      (reduce + (map (comp count val) collisions)) " ファイル"))
        (println "  改名すると :adr/related の参照が dangling になり、どちらを指していたかは")
        (println "  各参照元を読まないと決まらない。解消は判断を要する（本検査の対象外）。"))

      (when (seq dangling)
        (let [uniq (distinct (map :ref dangling))
              shape (fn [r] (cond (str/blank? r) :blank
                                  (str/includes? r "/") :path-like
                                  (re-matches #"adr-[0-9]+-.*" r) :slug-form
                                  (re-matches #"[0-9]+" r) :bare-number
                                  :else :prose))
              ;; 番号で一意に解決できるか。id 慣習が 2 系統（bare 番号 / slug 形）
              ;; 併存しているので、参照側が「覚えていた方」で書くと解決しない ——
              ;; その多くは番号を取り出せば一意に当たる。
              ;; `:adr/id` は string とは限らない —— manifest/schema.edn 自身が
              ;; 「:adr/id は string または long」と注記している。str で寄せる。
              num-of (fn [v] (let [s (str v)]
                               (second (or (re-matches #"adr-([0-9]+)-.*" s)
                                           (re-matches #"([0-9]+)" s)
                                           (re-matches #"ADR-([0-9]+).*" s)))))
              by-num (group-by #(num-of (:id %)) adrs)
              resolvable (filter #(= 1 (count (get by-num (num-of %) []))) uniq)]
          (println (str "\n報告のみ — どの ADR の :adr/id にも解決しない参照: "
                        (count uniq) " 種 / 延べ " (count dangling)))
          (println "  :adr/related は ADR 以外（ファイルパス・外部文書・散文）も指す慣習があるので fail させない。")
          (println "  形の内訳:")
          (doseq [[k v] (sort-by (comp - val) (frequencies (map shape uniq)))]
            (println (str "    " (name k) " " v)))
          (println (str "\n  うち **番号で一意に解決できる** ものが " (count resolvable) " 種。"))
          (println "  これは id 慣習が 2 系統併存していることの直接の帰結で、参照側が")
          (println "  `adr-<番号>-<slug>` と `<番号>` のどちらで書いたかだけの違い。機械的に")
          (println "  正規化できるが、**本検査は書き換えない**（生成物でない文書を一括改変")
          (println "  しないため。やるなら別の変更として、差分を見せて着地させる）。")))

      (let [non-string (filter #(not (string? (:raw-id %))) adrs)
            fam-counts (frequencies (keep #(id-family (:file %)) non-string))
            outside (remove #(id-family (:file %)) non-string)]
        (when (seq non-string)
          (println (str "\n:adr/id が文字列でない ADR: " (count non-string) " 件"))
          (println "  :adr/related の参照はすべて文字列なので、これらは参照グラフから到達できない。")
          (doseq [[fam n] (sort fam-counts)
                  :let [pinned (get known-non-string-id-families fam)]]
            (println (str "    " fam " " n (when pinned (str " (pin " pinned ")"))))))

      (let [fails (concat
                   (for [a (sort-by :file outside)]
                     {:kind :non-string-id
                      :detail (str (:file a) " の :adr/id が " (pr-str (:raw-id a))
                                   " —— 文字列でない。:adr/related の参照はすべて文字列なので、"
                                   "この ADR は**参照グラフから永久に到達できない**")})
                   (for [[fam n] (sort fam-counts)
                         :let [pinned (get known-non-string-id-families fam 0)]
                         :when (> n pinned)]
                     {:kind :non-string-id-family-grew
                      :detail (str fam " の非文字列 id が " n " 件（pin は " pinned "）。"
                                   "生成器が悪い id を出し続けている —— 既存分を許すことと、"
                                   "増え続けるのを許すことは別")})
                   (for [[id v] (sort-by key new-collisions)]
                     {:kind :new-collision
                      :detail (str ":adr/id \"" id "\" を " (count v) " ファイルが宣言している: "
                                   (str/join ", " (sort (map :file v))))})
                   (for [r (sort-by :ref new-ambiguous)]
                     {:kind :new-ambiguous-reference
                      :detail (str (:from r) " の " (:attr r) " が \"" (:ref r)
                                   "\" を指すが、その id を " (:targets r) " 個の ADR が宣言している")})
                   ;; 債務表は **90-docs/adr を記述したもの**なので、その corpus を
                   ;; 見ているときだけ stale 判定する。fixture ディレクトリに対して
                   ;; 走らせると 23 件全部が「もう衝突していない」に見えてしまい、
                   ;; 健全な入力で fail する gate になる（実測でそうなった）。
                   (when corpus?
                     (for [id (sort stale-debt)]
                       {:kind :stale-debt
                        :detail (str "known-collisions に \"" id "\" が載っているが、もう衝突していない"
                                     " —— 解消済みなら表から外す（債務表が腐ると検査が嘘をつく）")})))]
        (if (empty? fails)
          (println (str "\nOK — 新しい id 衝突も、新しい曖昧参照も無い"
                        "（既知 debt " (count known-collisions) " 件は据え置き）"))
          (do
            (println (str "\nFAIL " (count fails) " 件:"))
            (doseq [{:keys [kind detail]} fails]
              (println (str "  ✗ " (name kind) "\n      " detail)))
            (println (str "\n直し方: 新しい ADR の :adr/id は既存と衝突しない値にする。"
                          "\n慣習は 2 系統あり、slug 形 `adr-<番号>-<slug>` が多数派（実測 1,362 / 353 / 65）。"
                          "\n**bare 番号は衝突しやすい** —— 同じ日時 prefix の ADR が別 org で独立に起きるため。"))
            (js/process.exit 1))))))))

(apply -main (drop 3 (js->clj js/process.argv)))

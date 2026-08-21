#!/usr/bin/env nbb
;; MF の勘定科目表 → kotoba.shohyo の chart（射影、手で書かない）
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/gen-mf-chart.cljs
;;   nbb --classpath ".:scripts/nbb_compat" scripts/gen-mf-chart.cljs --check
;;
;; ## なぜ生成物なのか
;;
;; 勘定科目 200 + 補助科目 512 を手で写せば、写し間違いは**正しい形の chart**
;; として着地する。`kotoba.shohyo` は「chart は主張である」という設計なので、
;; 間違った主張も受理して、貸借は合ったまま区分だけが狂う。だから写さない ——
;; MF の生の応答を repo に置き、そこから毎回作り直す。
;;
;; ## 入力はすべて root repo の中にある
;;
;; `--check` は credential も west checkout も要らない。したがって
;; **fleet gate にできる**（`root-mf-chart`）。shohyo を実際に読み込んで
;; 区分を検査する `verify-mf-chart.cljs` の方は `orgs/kotoba-lang/shohyo` を
;; 要求するので gate にできない —— CLAUDE.md の「gate が要求する入力が repo に
;; 無いことがある」に当たる。2 本に分けてあるのはそのため。
;;
;; ## 答えられなかったときは 0 でも 1 でもない
;;
;; 入力欠落・snapshot 不一致・未知の account_group・未知の tax_id・キー衝突は
;; すべて **exit 3**。既定値で埋めない —— 0% で埋めた税率も、:unknown で埋めた
;; 区分も、正しく写せた場合と同じ形をして着地する。
(ns gen-mf-chart
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]))

(def root (or (some-> js/process.env .-FLEET_ROOT) (.cwd js/process)))
(def argv (vec (drop 2 js/process.argv)))
(defn flag? [f] (some #{f} argv))
(defn opt [f d] (let [i (.indexOf argv f)] (if (neg? i) d (nth argv (inc i) d))))

(def acct-json (opt "--accounts" (path/join root "90-docs" "accounting" "raw" "mf-accounts-2026-08-20.json")))
(def dataset   (opt "--dataset"  (path/join root "90-docs" "accounting" "moneyforward-parity.datoms.edn")))
(def tax-edn   (opt "--taxes"    (path/join root "90-docs" "accounting" "mf-tax-categories.edn")))
(def ovr-edn   (opt "--overrides" (path/join root "90-docs" "accounting" "mf-account-sections.edn")))
(def out-file  (opt "--out"      (path/join root "90-docs" "accounting" "gftd-japan-chart.edn")))

(defn refuse! [why]
  (println (str "REFUSING to write a chart: " why))
  (println "exit 3 — could not answer. This is not a pass and not a gap.")
  (.exit js/process 3))

(defn slurp' [f] (when (fs/existsSync f) (fs/readFileSync f "utf8")))

(defn sha256 [s]
  (-> (crypto/createHash "sha256") (.update s "utf8") (.digest "hex")))

;; MF の account_group は 5 つ。shohyo の 5 type と 1:1 で対応するが、
;; **CAPITAL → :equity だけ名前が違う**ので表にする。表に無い値が来たら
;; 拒否する —— :expense に倒す既定値は、新しい group を静かに費用にする。
(def group->type
  {"ASSET" :asset "LIABILITY" :liability "CAPITAL" :equity
   "REVENUE" :revenue "EXPENSE" :expense})

(defn -main []
  (let [raw   (or (slurp' acct-json) (refuse! (str "MF の生応答が無い: " acct-json)))
        js    (try (js->clj (js/JSON.parse raw) :keywordize-keys true)
                   (catch :default e (refuse! (str "生応答が JSON として読めない: " (.-message e)))))
        accts (:accounts js)
        _     (when-not (seq accts) (refuse! "生応答に accounts が無い"))
        dtext (or (slurp' dataset) (refuse! (str "parity dataset が無い: " dataset)))
        ents  (try (edn/read-string dtext)
                   (catch :default e (refuse! (str "dataset が読めない: " (.-message e)))))
        snap  (or (first (filter :mf.snapshot/id ents)) (refuse! "dataset に snapshot が無い"))
        cats  (into {} (map (juxt (juxt :mf.category/statement :mf.category/id)
                                  :mf.category/shohyo-section))
                    (filter :mf.category/id ents))
        taxes (try (edn/read-string (or (slurp' tax-edn) (refuse! (str "税区分表が無い: " tax-edn))))
                   (catch :default e (refuse! (str "税区分表が読めない: " (.-message e)))))
        overs (try (edn/read-string (or (slurp' ovr-edn) (refuse! (str "区分の上書き表が無い: " ovr-edn))))
                   (catch :default e (refuse! (str "上書き表が読めない: " (.-message e)))))
        mf-subs (mapcat :sub_accounts accts)]  ;; ⚠ `subs` と名付けない —— clojure.core/subs を覆い、
                                               ;;    FRESH の枝だけが実行時に落ちる（実測 2026-08-20）

    ;; 上書きが実在の科目を指しているか。改名・廃止された科目に対する
    ;; 上書きは、黙って効かなくなる —— 効かないことを誰も見ない。
    (let [names (set (map :name accts))
          stale (vec (sort (remove names (keys overs))))]
      (when (seq stale)
        (refuse! (str "上書き表が MF に実在しない科目を指している: "
                      (str/join ", " stale)
                      " —— 改名か廃止。表を測り直す"))))

    ;; 生応答が snapshot と同じ測定であることを、数で確かめる。違えば
    ;; どちらか片方だけが更新されている。
    (when-not (= (count accts) (:mf.snapshot/accounts snap))
      (refuse! (str "生応答は " (count accts) " 科目、snapshot は "
                    (:mf.snapshot/accounts snap) " —— 片方だけが更新されている")))
    (when-not (= (count mf-subs) (:mf.snapshot/sub-accounts snap))
      (refuse! (str "生応答は " (count mf-subs) " 補助科目、snapshot は "
                    (:mf.snapshot/sub-accounts snap))))

    (let [tax-of (fn [id where]
                   (or (get taxes id)
                       (refuse! (str where " が参照する税区分 " id
                                     " が " tax-edn " に無い。"
                                     "0% で埋めない —— 税率の異なるごとの区分が壊れる"))))
          entry  (fn [{:keys [name account_group financial_statement_type category tax_id]}]
                   (let [t   (or (group->type account_group)
                                 (refuse! (str name " の account_group " (pr-str account_group)
                                               " は既知の 5 つのどれでもない")))
                         sec (if (contains? cats [financial_statement_type category])
                               (get cats [financial_statement_type category])
                               (refuse! (str name " の区分 " financial_statement_type "/" category
                                             " が parity dataset に無い —— dataset を測り直す")))
                         ovr (get overs name)
                         sec (if ovr (:section ovr) sec)
                         tx  (tax-of tax_id name)]
                     (when (nil? sec)
                       (refuse! (str name " の区分 " financial_statement_type "/" category
                                     " は dataset にあるが :mf.category/shohyo-section が nil")))
                     [name (cond-> {:type t
                                    :section sec
                                    :mf/section-article (:article ovr)
                                    :mf/statement financial_statement_type
                                    :mf/category category
                                    :mf/tax-name (:name tx)
                                    :mf/tax-rate (:rate tx)})]))
          top    (mapv entry accts)
          ;; 補助科目は親の type / section / 区分を継ぐ。**税区分は継がない** ——
          ;; 補助科目は自分の tax_id を持ち、親と違うことがある。
          subrows (vec (mapcat
                        (fn [{:keys [name] :as a}]
                          (let [[_ parent] (entry a)]
                            (mapv (fn [s]
                                    (let [tx (tax-of (:tax_id s) (str name "/" (:name s)))]
                                      [(str name "/" (:name s))
                                       (assoc parent
                                              :mf/account name
                                              :mf/sub-account (:name s)
                                              :mf/tax-name (:name tx)
                                              :mf/tax-rate (:rate tx))]))
                                  (:sub_accounts a))))
                        accts))
          rows   (concat top subrows)
          dups   (->> rows (map first) frequencies (filter #(> (val %) 1)) (map key) sort vec)]

      (when (seq dups)
        (refuse! (str "chart のキーが衝突した: " (str/join ", " (take 5 dups))
                      " —— 「勘定科目/補助科目」という綴りは一意でなければ"
                      " 片方の区分がもう片方を黙って上書きする")))

      (let [sorted (sort-by first rows)
            body   (str/join "\n"
                     (map (fn [[k v]]
                            (str " " (pr-str k) "\n"
                                 "  {:type " (pr-str (:type v))
                                 " :section " (pr-str (:section v)) "\n"
                                 "   :mf/statement " (pr-str (:mf/statement v))
                                 " :mf/category " (pr-str (:mf/category v)) "\n"
                                 (when (:mf/account v)
                                   (str "   :mf/account " (pr-str (:mf/account v))
                                        " :mf/sub-account " (pr-str (:mf/sub-account v)) "\n"))
                                 (when (:mf/section-article v)
                                   (str "   :mf/section-article " (pr-str (:mf/section-article v)) "\n"))
                                 "   :mf/tax-name " (pr-str (:mf/tax-name v))
                                 " :mf/tax-rate " (pr-str (:mf/tax-rate v)) "}"))
                          sorted))
            text (str
";; Gftd Japan株式会社 の勘定科目表を kotoba.shohyo の chart に写したもの。\n"
";;\n"
";; **生成物。手で編集しない。**\n"
";;   作り直す: nbb --classpath \".:scripts/nbb_compat\" scripts/gen-mf-chart.cljs\n"
";;   検査する: nbb --classpath \".:scripts/nbb_compat\" scripts/gen-mf-chart.cljs --check\n"
";;   区分の検査: nbb --classpath \".:scripts/nbb_compat\" scripts/verify-mf-chart.cljs\n"
";;\n"
";; 入力:\n"
";;   90-docs/accounting/raw/mf-accounts-2026-08-20.json  (MF 生応答)\n"
";;   90-docs/accounting/moneyforward-parity.datoms.edn   (区分 → shohyo section)\n"
";;   90-docs/accounting/mf-tax-categories.edn            (税区分 12 件)\n"
";;   90-docs/accounting/mf-account-sections.edn          (条文が細分を要求する区分の上書き)\n"
";;\n"
";; ## キーの綴り\n"
";;\n"
";; 勘定科目は科目名そのもの、補助科目は `\"勘定科目/補助科目\"`。**これはこの\n"
";; 射影の取り決めであって MF の綴りではない。** 試算表を作る側（bookkeeping /\n"
";; kakeibo / banking）が同じ綴りを使わなければ、`kotoba.shohyo/statements` は\n"
";; その科目を `:shohyo/unclassified` に落とす —— 落とすのが正しい振る舞いで、\n"
";; 黙って別の科目に混ぜるよりよい。綴りを変えたいときは :mf/account と\n"
";; :mf/sub-account から作り直せる（MF に取りに行き直さなくてよい）。\n"
";;\n"
";; ⚠ **区切りの `/` でキーを割ってはいけない。** 補助科目の名前自身が `/` を\n"
";;   含むことがある（例: \"DE-SCHOOL売上/DE-SCHOOL/その他売上\"）。分解が要る\n"
";;   ときは :mf/account と :mf/sub-account を読む —— 一意なのは 712 キー全体の\n"
";;   集合であって、綴りの構造ではない。\n"
";;\n"
";; ## この chart が言っていないこと\n"
";;\n"
";; - **:concept を持たない。** kanjō の 16 語彙への対応付けは、MF の区分からは\n"
";;   導出できない。`kotoba.shohyo` は「科目が何であるかを推測しない」ので、\n"
";;   ここでも推測しない（`concept-ok?` は nil を返す = 誰も検査していない）。\n"
";; - **部門を持たない。** MF は 20 部門を 2 階層で持ち仕訳が部門を持てるが、\n"
";;   bookkeeping の journal entry / posting に部門の次元が無い（ADR-2608209000）。\n"
";; - **残高を持たない。** これは科目表であって試算表ではない。\n"
";;\n"
                 ";; measured-at " (:mf.snapshot/measured-at snap) "\n"
                 ";; office       " (:mf.snapshot/office-name snap)
                                    " (" (:mf.snapshot/office-code snap) ")\n"
                 ";; accounts     " (count top) "\n"
                 ";; sub-accounts " (count subrows) "\n"
                 ";; source-sha256 " (sha256 raw) "\n"
                 "\n{"
                 (str/replace body #"^ " "")
                 "}\n")]

        (if (flag? "--check")
          (let [cur (slurp' out-file)]
            (cond
              (nil? cur) (do (println (str "MISSING\t" out-file " —— 生成されていない"))
                             (.exit js/process 1))
              (= cur text) (println (str "FRESH\t" (count rows) " entries ("
                                         (count top) " accounts + " (count subrows)
                                         " sub-accounts), sha256 " (subs (sha256 raw) 0 12)))
              :else (do (println (str "STALE\t" out-file
                                      " は入力から作り直したものと一致しない"))
                        (.exit js/process 1))))
          (do (fs/writeFileSync out-file text)
              (println (str "WROTE\t" out-file "\t" (count rows) " entries ("
                            (count top) " accounts + " (count subrows) " sub-accounts)"))))))))

(-main)

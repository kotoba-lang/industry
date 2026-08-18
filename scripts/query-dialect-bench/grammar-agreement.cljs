;; scripts/query-dialect-bench/grammar-agreement.cljs
;;
;; **`agent/validate` が拒否する文法と、エンジンが実際に拒否する文法は、別々に
;; 書かれた 2 つの実装である。** validate は「DataScript が何を throw するか」を
;; 手で写したものでしかなく、ずれても誰も気づかない —— 実測 2026-08-18、
;; `:empty-find` も `:predicate-not-wrapped` も **production の測定が先に見つけた**。
;; validator を通り抜けて実行段で落ち、batch ごと 90 分の推論を捨てた。
;;
;; ⚠ **一致は目標ではない。** validator は engine より*厳しくてよい* —— 未知属性は
;; engine が黙って受けて 0 件を返すもので、それを実行前に捕まえることがこの入口の
;; 存在理由そのものである（実測: bare 条件の失敗 5/5 が属性の捏造だった）。
;; したがって不変条件は方向つきになる:
;;
;;   **必須** engine が拒否 ⟹ validator も拒否
;;            破ると推論を捨てる。実測でこれが起きた（batch ごと 90 分）
;;   **許容** validator が拒否 / engine は通す —— ただし宣言済みの理由に限る
;;            それ以外は偽の refusal。18/20 を差し戻した事故がこれ
;;
;; 宣言済みの意図的な厳しさは `intentional-strictness` に列挙する。**列挙に無い
;; 厳しさは失敗として扱う** —— そうしないと「validator が厳しいのは仕様です」で
;; 何でも通ってしまう。
;;
;;   nbb --classpath ".:scripts/nbb_compat:orgs/kotoba-lang/kotobase-query/src" \
;;     scripts/query-dialect-bench/grammar-agreement.cljs
;;
;; exit 0 = 一致 / 1 = ずれあり / 2 = 検査できなかった（0 でも 1 でもない）

(require '[clojure.string :as str]
         '["datascript" :as ds-mod]
         '[scripts.nbb-compat :as compat]
         '[kotobase.query.agent :as agent])

(def ds (or (.-default ds-mod) ds-mod))

(def intentional-strictness
  "engine が通すのに validator が弾いてよい、宣言済みの理由。
   `:unknown-attributes` —— engine は未知属性を黙って受けて 0 件を返す。捏造された
   属性名を実行前に捕まえることが、この入口が存在する理由。"
  #{:unknown-attributes})

(def schema
  {:datasets ["market-intel"]
   :attributes [{:attr "source/dataset"} {:attr "company/lei"} {:attr "company/ticker"}
                {:attr "company/name"} {:attr "company/revenue-usd"} {:attr "repo/kind"}]})

(defn tiny-db []
  (let [conn (.create_conn ds)]
    (.transact ds conn (clj->js [{"db/id" -1 "source/dataset" "market-intel" "company/lei" "L1"
                                  "company/ticker" "AAA" "company/name" "Alpha"
                                  "company/revenue-usd" 2e11}
                                 {"db/id" -2 "source/dataset" "market-intel" "company/lei" "L2"
                                  "company/ticker" "BBB" "company/name" "Beta"
                                  "company/revenue-usd" 5}
                                 {"db/id" -3 "source/dataset" "repo-taxonomy" "repo/kind" "actor"}]))
    (.db ds conn)))

;; 入力表。**エンジンが拒否すべきものと、通すべきものを両方置く。**
;; 片側だけの表は片側のずれしか見つけない。
(def cases
  '[;; --- 通るべき
    [:ok "単一パターン"        [:find ?t :where [?e "company/ticker" ?t]]]
    [:ok "値の文字列"          [:find ?t :where [?e "source/dataset" "market-intel"]
                                                [?e "company/ticker" ?t]]]
    [:ok "包んだ述語"          [:find ?t :where [?e "company/revenue-usd" ?r] [(>= ?r 1e11)]
                                                [?e "company/ticker" ?t]]]
    [:ok "join"               [:find ?l :where [?a "company/lei" ?l] [?b "company/lei" ?l]]]
    [:ok "集約"                [:find (count ?e) :where [?e "company/ticker" _]]]
    [:ok "属性が変数"          [:find ?k (count ?e) :where [?e "repo/kind" ?k]]]
    [:ok "2 変数 find"         [:find ?t ?n :where [?e "company/ticker" ?t] [?e "company/name" ?n]]]
    [:ok "not 節"              [:find ?t :where [?e "company/ticker" ?t]
                                                (not [?e "company/name" "Alpha"])]]
    ;; --- 拒否されるべき
    [:reject "空の :find"      [:find :where [?e "company/ticker" ?t]]]
    [:reject "演算子が entity 位置" [:find ?t :where [?e "company/revenue-usd" ?r]
                                                     [>= ?r 1e11] [?e "company/ticker" ?t]]]
    [:reject "中置述語"        [:find ?n :where [?e "company/revenue-usd" ?r] [?r < 0]
                                                [?e "company/name" ?n]]]
    [:reject ":where が無い"   [:find ?e]]
    [:reject ":find で始まらない" [:where [?e "company/ticker" ?t]]]])

(defn engine-verdict
  "エンジンに実際に投げて、拒否するかを見る。**結果の中身は見ない** ——
   ここで問うているのは文法であって答えではない。"
  [db q]
  (try (.q ds (pr-str q) db) :accepts
       (catch :default e {:rejects (or (.-message e) (str e))})))

(defn validator-verdict [q]
  (if-let [r (agent/validate q schema)] {:rejects (:error r)} :accepts))


;; ---------------------------------------------------------------- 変異
;; **手で選んだ表は、思いついた形しか調べない。** 上の 13 件が一致したことは
;; 「私が想像した範囲では一致する」でしかなく、ずれが在るとすれば当然その外に在る。
;; ここは妥当なクエリに機械的な変異を当てて範囲を広げる。**乱数は使わない** ——
;; (base × 節 × 変異) を網羅するので、同じ入力からは同じ差異が毎回出る。

(defn- mutate-clause [c kind]
  (let [n (count c)]
    (case kind
      :attr->keyword  (when (and (>= n 2) (string? (nth c 1)))
                        (assoc (vec c) 1 (keyword (nth c 1))))
      :attr->unknown  (when (and (>= n 2) (string? (nth c 1)))
                        (assoc (vec c) 1 "no/such-attr"))
      :attr->blank    (when (>= n 2) (assoc (vec c) 1 '_))
      :swap-e-a       (when (>= n 2)
                        (assoc (vec c) 0 (nth c 1) 1 (nth c 0)))
      :unwrap-pred    (when (and (= n 1) (seq? (first c)))
                        (vec (first c)))
      :infix-pred     (when (and (= n 1) (seq? (first c)) (= 3 (count (first c))))
                        (let [[op a b] (first c)] [a op b]))
      :extra-element  (vec (conj (vec c) 'extra))
      :drop-value     (when (>= n 3) (vec (butlast c)))
      :e->literal     (when (>= n 1) (assoc (vec c) 0 "literal"))
      nil)))

(def mutation-kinds
  [:attr->keyword :attr->unknown :attr->blank :swap-e-a
   :unwrap-pred :infix-pred :extra-element :drop-value :e->literal])

(defn- where-index [q]
  (first (keep-indexed (fn [n x] (when (= x :where) n)) q)))

(defn variants
  "base クエリの :where 節を 1 つずつ、1 種類ずつ変異させたもの。"
  [q]
  (when-let [wi (where-index q)]
    (let [v (vec q)]
      (for [i (range (inc wi) (count v))
            k mutation-kinds
            :let [c (nth v i)
                  m (when (vector? c) (mutate-clause c k))]
            :when m]
        {:label (str "mut/" (name k) "@" i) :query (assoc v i m)}))))

(defn- run-fuzz [db bases]
  (let [vs (mapcat variants bases)
        rows (for [{:keys [label query]} vs]
               (let [ev (engine-verdict db query)
                     vv (validator-verdict query)]
                 {:label label :query query
                  :engine (if (= :accepts ev) :accepts :rejects)
                  :validator (if (= :accepts vv) :accepts (:rejects vv))}))]
    (vec rows)))

(defn -main []
  (let [db (tiny-db)
        rows (for [[expected label q] cases]
               (let [ev (engine-verdict db q)
                     vv (validator-verdict q)
                     e-acc (= :accepts ev)
                     v-acc (= :accepts vv)]
                 {:label label :expected expected
                  :engine (if e-acc :accepts (:rejects ev))
                  :validator (if v-acc :accepts (:rejects vv))
                  :agree (= e-acc v-acc)
                  ;; 表の期待と実際のエンジンがずれていたら、**表の方が間違っている**
                  :table-ok (= e-acc (= :ok expected))}))
        rows (vec rows)]

    ;; 5 問の 1/2: 表が空、あるいは 1 件も実行できていないなら pass にしない
    (when (empty? rows)
      (println "REFUSING: 入力表が空。0 件の一致を『一致』として報告しない。")
      (compat/exit 2))

    (doseq [r rows]
      (println (str (cond (not (:table-ok r)) "TABLE"
                          (:agree r) "ok   "
                          :else "DIFFER")
                    "  " (:label r)
                    "  engine=" (if (= :accepts (:engine r)) "accepts"
                                    (str "rejects(" (subs (str (:engine r)) 0
                                                          (min 48 (count (str (:engine r))))) ")"))
                    "  validator=" (if (= :accepts (:validator r)) "accepts"
                                       (str "rejects(" (name (:validator r)) ")")))))

    (let [differ (remove :agree rows)
          table-bad (remove :table-ok rows)
          ;; 片側だけの表は片側のずれしか見つけない —— 床を置く
          n-ok (count (filter #(= :ok (:expected %)) rows))
          n-rej (count (filter #(= :reject (:expected %)) rows))]
      (println)
      (when (or (zero? n-ok) (zero? n-rej))
        (println (str "REFUSING: 表が片側しか持っていない（通るべき " n-ok
                      " 件 / 拒否すべき " n-rej " 件）。片側の表は片側のずれしか見つけない。"))
        (compat/exit 2))
      (println (str "checked=" (count rows) " (通るべき " n-ok " / 拒否すべき " n-rej ")"
                    "  差異=" (count differ) "  表の誤り=" (count table-bad)))
      (when (seq table-bad)
        (println "\n⚠ 表の期待とエンジンの実挙動がずれている（validator ではなく表を直す）:")
        (doseq [r table-bad] (println (str "   " (:label r) " — expected " (name (:expected r))))))
      (when (seq differ)
        (println "\n⚠ validator とエンジンがずれている:")
        (doseq [r differ]
          (println (str "   " (:label r)
                        (if (= :accepts (:validator r))
                          "  validator が通し、エンジンが拒否 → 推論を捨てる"
                          "  validator が弾き、エンジンは通す → 偽の refusal")))))
      ;; --- 変異
      (let [bases (keep (fn [[e _ q]] (when (= :ok e) q)) cases)
            fz (run-fuzz db bases)
                ;; 必須方向の違反 —— engine が拒否するのに validator が通した
            fz-missed (filter #(and (not= :accepts (:engine %)) (= :accepts (:validator %))) fz)
            ;; 許容方向のうち、宣言に無いもの
            fz-false (filter #(and (= :accepts (:engine %))
                                   (not= :accepts (:validator %))
                                   (not (intentional-strictness (:validator %)))) fz)
            fz-intended (filter #(and (= :accepts (:engine %))
                                      (intentional-strictness (:validator %))) fz)]
        (when (empty? fz)
          (println "REFUSING: 変異が 1 件も作られなかった。0 件を『ずれ無し』にしない。")
          (compat/exit 2))
        (println (str "変異 checked=" (count fz)
                      " (engine accepts " (count (filter #(= :accepts (:engine %)) fz))
                      " / rejects " (count (filter #(not= :accepts (:engine %)) fz)) ")"))
        (println (str "  必須方向の違反（engine 拒否 / validator 通す）= " (count fz-missed)))
        (println (str "  宣言に無い厳しさ（engine 通す / validator 拒否）= " (count fz-false)))
        (println (str "  宣言済みの意図的な厳しさ = " (count fz-intended)
                      " " (pr-str (frequencies (map :validator fz-intended)))))
        (doseq [[title rs] [["engine が拒否するのに validator が通した（推論を捨てる）" fz-missed]
                            ["宣言に無い厳しさ（偽の refusal）" fz-false]]]
          (when (seq rs)
            (println (str "\n⚠ " title ":"))
            (doseq [r (take 20 rs)]
              (println (str "   " (:label r) "  validator="
                            (if (= :accepts (:validator r)) "accepts" (name (:validator r)))
                            "\n     " (pr-str (:query r)))))))
        (compat/exit (if (or (seq differ) (seq table-bad) (seq fz-missed) (seq fz-false)) 1 0))))))

(-main)

;; scripts/query-dialect-bench/bench.cljs — LLM が kotobase 方言の query を書けるかを
;; **実データ・実 LLM・実行結果**で測る。ADR-2608189300 の「次の一手は測定」の実装。
;;
;; 測るもの（ADR-2608189300 D5 の主張そのもの）:
;;   方言側の不利は schema 注入 + few-shot + validator + repair loop で埋まる、が本当か。
;;
;;   pass@1-bare     schema 無し（方言名だけ伝える）
;;   pass@1-schema   schema + few-shot あり
;;   pass@repair     validator の構造化エラーを返して最大 :max-repair 回
;;
;; ## 「検査を書く前の 5 問」（CLAUDE.md / ADR-2608136000）への回答
;;
;; 1. 入力が無いとき   — questions.edn が空なら exit 2。pass にしない。
;; 2. 実行できないとき — LLM が落ちている / 面が組めない場合は exit 2（0 でも 1 でもない）。
;;                       「答えられなかった」を「合格」と同じ値で返さない。
;; 3. エラー本文       — LLM の HTTP エラーは status だけでなく body も記録する。
;; 4. 飛ばした/合格    — 出力は per-question に :outcome を持ち、:skipped と :pass を区別する。
;; 5. 両方向           — 既知正解の reference query 自身を同じ比較器に通す（self-check）。
;;                       reference が pass しない = 比較器が壊れている → exit 2。
;;
;; evidence floor: executed < asked なら結果を出さずに exit 2。
;;
;; 使い方:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/query-dialect-bench/bench.cljs \
;;       --questions scripts/query-dialect-bench/questions.edn \
;;       --out scripts/query-dialect-bench/result.edn [--limit N] [--offline]
;;
;;   --offline は LLM を呼ばず reference の self-check だけ行う（比較器と面の健全性検査）。

(require '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[scripts.nbb-compat :as compat])

(def fs (js/require "fs"))
(def child (js/require "child_process"))

(def ROOT (or (.-FLEET_ROOT js/process.env) (.cwd js/process)))

;; ---------------------------------------------------------------- args

(defn parse-args [argv]
  (loop [a (vec argv) m {:max-repair 2}]
    (if (empty? a)
      m
      (let [[k & r] a]
        (case k
          "--questions" (recur (vec (rest r)) (assoc m :questions (first r)))
          "--out"       (recur (vec (rest r)) (assoc m :out (first r)))
          "--limit"     (recur (vec (rest r)) (assoc m :limit (js/parseInt (first r))))
          "--max-repair" (recur (vec (rest r)) (assoc m :max-repair (js/parseInt (first r))))
          "--offline"   (recur (vec r) (assoc m :offline true))
          "--self-test" (recur (vec r) (assoc m :self-test true))
          "--plane-script" (recur (vec (rest r)) (assoc m :plane-script (first r)))
          (recur (vec r) m))))))

;; ---------------------------------------------------------------- plane

(def ^:dynamic *plane-script* "manifest/edn-query.cljs")

(defn run-plane
  "1 プロセスで N 本のクエリを流す。面のロードが所要時間の全てなので必ず batch する。
   戻り値は {:ok true :rows [...]} か {:ok false :why ... :detail ...}。
   **空出力を「結果 0 件」と読まない** —— 本数が合わなければ :ok false。"
  [queries]
  (if (empty? queries)
    {:ok true :rows []}
    (let [args (into ["--classpath" ".:scripts/nbb_compat" *plane-script* "q*"] queries)
          res (.spawnSync child "nbb" (clj->js args)
                          #js {:cwd ROOT :encoding "utf8"
                               :maxBuffer (* 64 1024 1024)
                               :timeout (* 20 60 1000)})
          out (or (.-stdout res) "")
          err (or (.-stderr res) "")]
      (cond
        (not (zero? (.-status res)))
        {:ok false :why :plane-nonzero-exit :detail (str "exit=" (.-status res) " stderr=" (str/trim err))}

        (str/blank? (str/trim out))
        {:ok false :why :plane-empty-output :detail (str/trim err)}

        :else
        (let [parsed (try (edn/read-string (str/trim (last (remove str/blank? (str/split-lines out)))))
                          (catch :default e {::parse-error (.-message e)}))]
          (cond
            (and (map? parsed) (::parse-error parsed))
            {:ok false :why :plane-unparseable :detail (::parse-error parsed)}

            (not= (count parsed) (count queries))
            {:ok false :why :plane-arity-mismatch
             :detail (str "asked " (count queries) " got " (count parsed))}

            :else {:ok true :rows (vec parsed)}))))))

(defn norm
  "結果を順序非依存の集合にする。datalog の :find は順序を約束しない。"
  [rows]
  (set (map vec (or rows []))))

;; ---------------------------------------------------------------- validator
;; query が EDN 値であることの実利がここ。文字列 surface はこの段を持てない。

(defn type->str [x]
  (cond (map? x) "map" (list? x) "list" (string? x) "string" (nil? x) "nil" :else "other"))

(defn where-clauses
  "query の :where 以降の節だけを返す。:find / :in の中身は data pattern ではない。"
  [q]
  ;; ⚠ `(.indexOf (to-array q) :where)` は **-1 を返す** —— JS の indexOf は
  ;; boxed な cljs keyword を strict equality で比べるので一致しない。実測
  ;; 2026-08-18、その実装は :where 節を **0 件**走査したうえで「妥当」を返して
  ;; いた。**測れなかった検査が、測って問題が無かった検査と同じ値を返す**
  ;; （ADR-2608136000 の 5 問の 2）。cljs の = で数える。
  (let [i (first (keep-indexed (fn [n x] (when (= x :where) n)) q))]
    (if (nil? i) [] (filter vector? (subvec (vec q) (inc i))))))

(defn clause-attribute
  "data pattern `[?e <attr> ?v]` の attr 位置だけを返す。
   ⚠ **ここを『query 中の全文字列』にすると値も属性として弾く。**
   実測 2026-08-18: 最初の実装がそれで、`[?e \"source/dataset\" \"market-intel\"]` の
   値 \"market-intel\" を未知属性として報告し、**正しいクエリを 20 問中 18 問で
   拒否した**。repair loop はその偽のエラーをモデルに返していたので、その回の
   測定は丸ごと無効だった。壊れた検査は『指摘』の顔をして出てくる。"
  [c]
  (when (and (vector? c) (>= (count c) 2)
             ;; `[(>= ?r 1e11)]` のような述語節は data pattern ではない
             (not (seq? (first c)))
             (not (list? (first c))))
    (second c)))

(defn validate
  "構造検証 + 属性 allowlist。戻り値 nil = 妥当、それ以外は **LLM に返せる構造化エラー**。"
  [q attr-allow]
  (cond
    (not (vector? q))
    {:error :not-a-vector :got (type->str q)
     :hint "query は EDN のベクタで始まる: [:find ?x :where [?e \"attr\" ?x]]"}

    (not= :find (first q))
    {:error :missing-find :got (str (first q))
     :hint "先頭は :find でなければならない"}

    (not (some #{:where} q))
    {:error :missing-where
     :hint ":where 節が無い"}

    ;; :find と :where の間に 1 つも束縛が無いと DataScript が
    ;; `Cannot parse :find` で throw する。**実行前に捕まえる**（実測 2608189300 の測定で、
    ;; validator を通ったこの形が batch 全体を落とした）
    (empty? (remove #{:find :in :where}
                    (take-while #(not= :where %) (rest q))))
    {:error :empty-find
     :hint ":find と :where の間に返す変数か集約が 1 つも無い。例: [:find ?x :where …]"}

    :else
    (let [attrs (keep clause-attribute (where-clauses q))
          kw-attrs (sort (map str (filter keyword? attrs)))
          bad (sort (remove attr-allow (filter string? attrs)))]
      (cond
        ;; 最も起きやすい誤り。専用のエラーにして直し方を名指しする
        (seq kw-attrs)
        {:error :keyword-attributes :got (vec kw-attrs)
         :hint (str "属性はキーワードではなく**裸文字列**で書く。"
                    (first kw-attrs) " ではなく "
                    (pr-str (subs (first kw-attrs) 1)) " とする。")}

        (seq bad)
        {:error :unknown-attributes :got (vec bad)
         :hint (str "この面に存在しない属性: " (str/join ", " bad)
                    "。属性は与えた一覧の中からのみ選ぶ。")}))))

;; ---------------------------------------------------------------- llm

(defn resolve-model
  "CLAUDE.md: concrete model id を焼かない。①env override ②murakumo-main alias
   ③endpoint のみの fallback（endpoint 先の serving モデルに従う）。"
  []
  (if-let [ep (.-BENCH_LLM_ENDPOINT js/process.env)]
    {:endpoint ep :model (or (.-BENCH_LLM_MODEL js/process.env) "murakumo-main") :via :env}
    (let [res (.spawnSync child "curl"
                          #js ["-sS" "--max-time" "20"
                               "https://api.murakumo.cloud/infer/models/murakumo-main"]
                          #js {:encoding "utf8"})
          body (or (.-stdout res) "")]
      (if (and (zero? (.-status res)) (not (str/blank? body)))
        (let [j (try (js->clj (js/JSON.parse body) :keywordize-keys true) (catch :default _ nil))]
          (if (:endpoint j)
            {:endpoint (:endpoint j) :model "murakumo-main" :via :alias :alias-for (:alias-for j)}
            {:endpoint nil :why :alias-shape :detail body}))
        {:endpoint nil :why :alias-unreachable
         :detail (str "exit=" (.-status res) " " (str/trim (or (.-stderr res) "")))}))))

(defn ask
  "1 回の chat completion。**エラー本文を捨てない**（5 問の 3）。"
  [{:keys [endpoint model]} messages]
  (let [payload (js/JSON.stringify
                 (clj->js {:model model :messages messages
                           :temperature 0 :max_tokens 2500 :stream false}))
        res (.spawnSync child "curl"
                        #js ["-sS" "--max-time" "180" "-X" "POST" endpoint
                             "-H" "content-type: application/json" "-d" payload]
                        #js {:encoding "utf8" :maxBuffer (* 16 1024 1024)})
        out (or (.-stdout res) "")]
    (if (not (zero? (.-status res)))
      {:ok false :why :curl-failed :detail (str/trim (or (.-stderr res) ""))}
      (let [j (try (js->clj (js/JSON.parse out) :keywordize-keys true) (catch :default _ nil))]
        (cond
          (nil? j) {:ok false :why :unparseable-response :detail (subs out 0 (min 500 (count out)))}
          (:error j) {:ok false :why :api-error :detail (pr-str (:error j))}
          :else
          ;; ⚠ このエンドポイントは推論モデル（Qwen3.8）で、思考は reasoning_content に
          ;; 入り content とは別枠。**max_tokens で切られると content が空文字で返る** ——
          ;; これを「答えなかった」と同じ値で数えると、予算不足がモデルの失敗に化ける
          ;; （5 問の 4: 飛ばしたと落ちたを区別する）。finish_reason を見て別立てにする。
          (let [c (get-in j [:choices 0 :message :content])
                fin (get-in j [:choices 0 :finish_reason])]
            (cond
              (and (string? c) (not (str/blank? c))) {:ok true :content c}
              (= "length" fin) {:ok false :why :truncated
                                :detail (str "finish_reason=length, thinking-tokens="
                                             (get-in j [:usage :completion_tokens]))}
              :else {:ok false :why :no-content :detail (subs out 0 (min 400 (count out)))})))))))

(defn extract-query
  "モデル出力から EDN query を取り出す。```fence と散文を剥がす。"
  [s]
  (let [s (str/replace (or s "") #"(?s)<think>.*?</think>" "")
        fenced (re-find #"(?s)```(?:clojure|edn|clj)?\s*(.+?)```" s)
        body (str/trim (or (second fenced) s))
        i (str/index-of body "[:find")]
    (when i
      (let [sub (subs body i)]
        ;; 括弧の釣り合いで終端を見つける（後続の散文を落とす）
        (loop [n 0 depth 0 seen false]
          (if (>= n (count sub))
            (when seen (str/trim sub))
            (let [ch (nth sub n)
                  d (cond (#{\[ \( \{} ch) (inc depth)
                          (#{\] \) \}} ch) (dec depth)
                          :else depth)]
              (if (and (zero? d) (pos? depth))
                (subs sub 0 (inc n))
                (recur (inc n) d (or seen (pos? d)))))))))))

;; ---------------------------------------------------------------- prompts

(defn schema-block [attrs datasets]
  (str "この面（DataScript / kotobase 方言）で使える属性は次で全部。**この一覧に無い属性を書かない。**\n"
       (str/join "\n" (map #(str "  " (pr-str (first %)) "  — " (second %)) attrs))
       "\n\nsource/dataset の値: " (str/join ", " (map pr-str datasets)) "\n"))

(def few-shot
  (str "記法の例（kotobase 方言 = Datomic-shaped EDN Datalog、属性は**裸文字列**でコロンを付けない）:\n\n"
       "  問: market-intel の会社を LEI 付きで列挙\n"
       "  [:find ?lei :where [?e \"source/dataset\" \"market-intel\"] [?e \"company/lei\" ?lei]]\n\n"
       "  問: 2 つの dataset を LEI で join して法人名を出す\n"
       "  [:find ?lei ?name :where [?a \"source/dataset\" \"market-intel\"] [?a \"company/lei\" ?lei]"
       " [?b \"company/lei\" ?lei] [?b \"company/legal-name\" ?name]]\n\n"
       "  問: 件数を数える\n"
       "  [:find (count ?e) :where [?e \"source/dataset\" \"repo-taxonomy\"]]\n"))

(def rules
  (str "規則:\n"
       "- 出力は EDN のクエリ 1 本だけ。説明・前置き・後置きを書かない。\n"
       "- 属性は裸文字列（\"company/lei\"）。キーワード（:company/lei）にしない。\n"
       "- 変数は ?name の形。\n"
       "- :find と :where は必須。\n"))

(defn user-msg [nl] (str "問い: " nl "\n\nこの問いに答えるクエリを 1 本書く。"))


;; ---------------------------------------------------------------- self-test
;; validator と抽出器の**両方向**（通すべきものを通し、弾くべきものを弾く）。
;; この harness が一度これを持たずに走り、正しいクエリを 18/20 で拒否したまま
;; repair loop を回した。検査そのものを検査する場所がここ。

(def ^:private allow-fixture
  #{"source/dataset" "company/lei" "company/ticker" "company/revenue-usd" "repo/kind" "repo/path"})

(def ^:private validator-cases
  [;; [label query 期待する :error（nil = 通るべき）]
   ["値の文字列を属性と誤認しない"
    '[:find ?t :where [?e "source/dataset" "market-intel"] [?e "company/ticker" ?t]] nil]
   ["述語節を属性節と誤認しない"
    '[:find ?t :where [?e "company/revenue-usd" ?r] [(>= ?r 1e11)] [?e "company/ticker" ?t]] nil]
   ["複数 dataset の join も通る"
    '[:find ?l :where [?a "source/dataset" "market-intel"] [?a "company/lei" ?l]
                      [?b "source/dataset" "repo-taxonomy"] [?b "company/lei" ?l]] nil]
   ["集約も通る" '[:find (count ?e) :where [?e "repo/kind" "actor"]] nil]
   ["キーワード属性は弾く"
    '[:find ?t :where [?e :company/ticker ?t]] :keyword-attributes]
   ["存在しない属性は弾く"
    '[:find ?x :where [?e "company/nonexistent" ?x]] :unknown-attributes]
   ["ベクタでないものは弾く" '{:find "x"} :not-a-vector]
   [":find で始まらないものは弾く" '[:where [?e "repo/kind" ?k]] :missing-find]
   [":where が無いものは弾く" '[:find ?e] :missing-where]
   ["空の :find を弾く（DataScript が throw する形）" '[:find :where [?e "repo/kind" ?k]] :empty-find]])

(defn self-test! []
  (let [vres (for [[label q expected] validator-cases]
               (let [got (:error (validate q allow-fixture))]
                 {:label label :expected expected :got got :ok (= expected got)}))
        eres (for [[label s expected] [["素" "[:find ?t :where [?e \"a/b\" ?t]]" true]
                                       ["fence" "```clojure\n[:find ?t :where [?e \"a/b\" ?t]]\n```" true]
                                       ["散文つき" "はい:\n[:find ?t :where [?e \"a/b\" ?t]]\n以上。" true]
                                       ["think タグ" "<think>x</think>[:find ?t :where [?e \"a/b\" ?t]]" true]
                                       ["クエリ無し" "分かりません。" false]]]
                (let [got (some? (extract-query s))]
                  {:label (str "extract/" label) :expected expected :got got :ok (= expected got)}))
        all (concat vres eres)
        bad (remove :ok all)]
    (doseq [r all]
      (println (str (if (:ok r) "ok   " "FAIL ") (:label r)
                    "  expected=" (pr-str (:expected r)) " got=" (pr-str (:got r)))))
    (println (str "\nself-test: " (- (count all) (count bad)) "/" (count all) " passed"))
    (compat/exit (if (seq bad) 1 0))))

;; ---------------------------------------------------------------- main

(defn slurp' [p] (.readFileSync fs p "utf8"))

(defn -main [& argv]
  (let [{:keys [questions out limit offline max-repair plane-script self-test]} (parse-args argv)]
    (when plane-script (set! *plane-script* plane-script))
    (when self-test (self-test!))
    (when-not questions
      (println "usage: bench.cljs --questions <file.edn> --out <file.edn> [--limit N] [--offline]")
      (compat/exit 2))
    (let [spec (edn/read-string (slurp' questions))
          attrs (:attributes spec)
          datasets (:datasets spec)
          qs (cond->> (:questions spec) limit (take limit))
          attr-allow (set (map first attrs))]

      ;; 5 問の 1: 入力が無いとき pass にしない
      (when (empty? qs)
        (println "REFUSING: questions.edn に問いが 0 件。合格と区別できない値を返さない。")
        (compat/exit 2))

      (println (str "asked=" (count qs) " attrs=" (count attrs) " repair-budget=" max-repair
                    " plane=" *plane-script*))

      ;; ---- self-check: reference query 自身を比較器に通す（5 問の 5、両方向）
      (println "[1/4] reference query を実行して期待値を作る…（面のロードに数分）")
      (let [refs (mapv :reference qs)
            r1 (run-plane (mapv pr-str refs))]
        (when-not (:ok r1)
          (println (str "REFUSING: 面が組めなかった —— " (name (:why r1)) " / " (:detail r1)))
          (compat/exit 2))
        (let [expected (mapv norm (:rows r1))
              empty-refs (keep-indexed (fn [i e] (when (empty? e) (:id (nth qs i)))) expected)]
          ;; reference が 0 件しか返さない問いは、正解と不正解を区別できない
          (when (seq empty-refs)
            (println (str "REFUSING: reference が 0 行の問いがある —— " (str/join ", " empty-refs)
                          "\n  0 行は『間違ったクエリ』とも一致するので、この問いでは pass を判定できない。"))
            (compat/exit 2))
          (println (str "  reference OK: " (count expected) " 件、行数 "
                        (str/join "/" (map count expected))))

          (if offline
            (do (println "offline: LLM を呼ばずに終了（比較器と面の健全性のみ検査した）")
                (.writeFileSync fs (or out "bench-offline.edn")
                                (pr-str {:mode :offline :asked (count qs)
                                         :reference-rows (mapv count expected)}))
                (compat/exit 0))

            ;; ---- LLM
            (let [llm (resolve-model)]
              (when-not (:endpoint llm)
                (println (str "REFUSING: LLM に到達できない —— " (name (:why llm)) " / " (:detail llm)))
                (compat/exit 2))
              (println (str "[2/4] LLM: " (:endpoint llm) " model=" (:model llm)
                            " (alias-for " (:alias-for llm) ")"))

              (let [sys-bare (str "あなたは kotobase 方言（Datomic-shaped EDN Datalog）でクエリを書く。\n" rules)
                    sys-full (str sys-bare "\n" (schema-block attrs datasets) "\n" few-shot)
                    ;; bare 条件と schema 条件を両方走らせる
                    run-cond
                    (fn [label sys]
                      (println (str "  [" label "] " (count qs) " 問…"))
                      (mapv (fn [q]
                              (let [r (ask llm [{:role "system" :content sys}
                                                {:role "user" :content (user-msg (:nl q))}])]
                                (if-not (:ok r)
                                  {:id (:id q) :outcome :llm-error :why (:why r) :detail (:detail r)}
                                  (let [txt (extract-query (:content r))]
                                    (if-not txt
                                      {:id (:id q) :outcome :no-query :raw (subs (:content r) 0 (min 300 (count (:content r))))}
                                      (let [parsed (try (edn/read-string txt) (catch :default e {::pe (.-message e)}))]
                                        (if (and (map? parsed) (::pe parsed))
                                          {:id (:id q) :outcome :unparseable :text txt :detail (::pe parsed)}
                                          {:id (:id q) :outcome :parsed :query parsed :text txt
                                           :invalid (validate parsed attr-allow)})))))))
                            qs))]

                (let [bare (run-cond "bare" sys-bare)
                      schema (run-cond "schema+few-shot" sys-full)]

                  ;; ---- repair loop（schema 条件に対してのみ。validator の構造化エラーを返す）
                  (println (str "[3/4] repair loop（最大 " max-repair " 回、validator のエラーを返す）"))
                  (let [repaired
                        (loop [cur schema round 0]
                          (let [need (filter #(or (#{:unparseable :no-query} (:outcome %)) (:invalid %)) cur)]
                            (if (or (zero? (count need)) (>= round max-repair))
                              cur
                              (do
                                (println (str "  round " (inc round) ": " (count need) " 件を差し戻す"))
                                (recur
                                 (mapv (fn [item]
                                         (if-not (or (#{:unparseable :no-query} (:outcome item)) (:invalid item))
                                           item
                                           (let [q (first (filter #(= (:id %) (:id item)) qs))
                                                 err (or (:invalid item)
                                                         {:error (:outcome item) :hint "EDN として読めなかった。クエリ 1 本だけを出す。"})
                                                 r (ask llm [{:role "system" :content sys-full}
                                                             {:role "user" :content (user-msg (:nl q))}
                                                             {:role "assistant" :content (or (:text item) (:raw item) "")}
                                                             {:role "user"
                                                              :content (str "そのクエリは実行前の構造検査で弾かれた。\n"
                                                                            (pr-str err)
                                                                            "\n直したクエリを 1 本だけ出す。")}])]
                                             (if-not (:ok r)
                                               (assoc item :outcome :llm-error :why (:why r) :detail (:detail r))
                                               (let [txt (extract-query (:content r))
                                                     parsed (when txt (try (edn/read-string txt) (catch :default _ nil)))]
                                                 (if (vector? parsed)
                                                   {:id (:id item) :outcome :parsed :query parsed :text txt
                                                    :invalid (validate parsed attr-allow) :repaired (inc round)}
                                                   (assoc item :outcome :unparseable :repaired (inc round))))))))
                                       cur)
                                 (inc round))))))]

                    ;; ---- 実行して照合
                    (println "[4/4] 生成クエリを実行して reference と照合（面をもう一度組む）")
                    ;; ⚠ **実行の前に LLM 出力を書き出す。** 実測 2026-08-18、実行段の
                    ;; 例外で 90 分ぶんの推論結果が捨てられた。推論は高く、実行は安い。
                    (let [dump (str (or out "bench-result.edn") ".generated.edn")]
                      (.writeFileSync fs dump
                                      (pr-str {:bare (mapv #(dissoc % :raw) bare)
                                               :repaired (mapv #(dissoc % :raw) repaired)}))
                      (println (str "  生成クエリを保存: " dump)))

                    ;; ⚠ 面のロードが所要時間の全て（実測で分単位）。2 条件を別々に流すと
                    ;; その分だけ倍になるので、**両条件の runnable を 1 回の q* に束ねる**。
                    (let [runnable-of (fn [items]
                                        (keep-indexed (fn [i it]
                                                        (when (and (= :parsed (:outcome it)) (nil? (:invalid it)))
                                                          [i (:query it)]))
                                                      items))
                          rb-run (vec (runnable-of bare))
                          rs-run (vec (runnable-of repaired))
                          all-q (mapv (comp pr-str second) (concat rb-run rs-run))
                          res (run-plane all-q)
                          _ (when-not (:ok res)
                              (println (str "REFUSING: 生成クエリを実行できなかった —— "
                                            (name (:why res)) " / " (:detail res)))
                              (compat/exit 2))
                          rows (:rows res)
                          rb-rows (subvec rows 0 (count rb-run))
                          rs-rows (subvec rows (count rb-run))
                          grade
                          (fn [label items runnable rrows]
                            (let [raw-by-idx (into {} (map (fn [[i _] r] [i r]) runnable rrows))
                                  by-idx (into {} (map (fn [[i _] r] [i (if (map? r) #{} (norm r))]) runnable rrows))
                                  graded (map-indexed
                                          (fn [i it]
                                            (cond
                                              (= :llm-error (:outcome it))
                                              (assoc it :grade (if (= :truncated (:why it))
                                                                 :skipped-truncated
                                                                 :skipped-llm-error))
                                              (:invalid it) (assoc it :grade :invalid)
                                              (not= :parsed (:outcome it)) (assoc it :grade :malformed)
                                              ;; エンジンが拒否したものは「違う答え」ではない
                                              (map? (get raw-by-idx i))
                                              (assoc it :grade :query-error
                                                     :detail (:query-error (get raw-by-idx i)))
                                              :else (assoc it :grade
                                                           (if (= (get by-idx i) (nth expected i)) :pass :wrong-answer)
                                                           :got-rows (count (get by-idx i))
                                                           :want-rows (count (nth expected i)))))
                                          items)]
                              {:label label
                               :executed (count runnable)
                               :asked (count items)
                               :tally (frequencies (map :grade graded))
                               :items (vec graded)}))
                          rb (grade "bare" bare rb-run rb-rows)
                          rs (grade "schema+repair" repaired rs-run rs-rows)]

                      (let [report {:date (subs (.toISOString (js/Date.)) 0 10)
                                    :plane *plane-script*
                                    :model (:model llm) :alias-for (:alias-for llm)
                                    :endpoint (:endpoint llm)
                                    :asked (count qs)
                                    :repair-budget max-repair
                                    :conditions [rb rs]}]
                        (.writeFileSync fs (or out "bench-result.edn") (pr-str report))
                        (println)
                        (doseq [c [rb rs]]
                          (println (str "== " (:label c)
                                        "  asked=" (:asked c) " executed=" (:executed c)))
                          (doseq [[k v] (sort-by (comp str key) (:tally c))]
                            (println (str "   " (name k) ": " v)))
                          (println (str "   pass-rate = "
                                        (.toFixed (* 100 (/ (or (get (:tally c) :pass) 0) (:asked c))) 1) "%")))
                        (println (str "\nwrote " (or out "bench-result.edn")))
                        (compat/exit 0)))))))))))))

(apply -main *command-line-args*)

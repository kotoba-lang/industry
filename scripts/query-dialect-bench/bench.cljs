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
         '[scripts.nbb-compat :as compat]
         ;; ⚠ **prompt も validator も extractor もここには無い。** 全部
         ;; kotoba-lang/kotobase-query の `kotobase.query.agent`（pure、依存ゼロ）に在る。
         ;; 測定と production が別々のコピーを持てば、直るのは片方だけになる。
         ;; classpath に `orgs/kotoba-lang/kotobase-query/src` が要る（README 参照）。
         '[kotobase.query.agent :as agent])

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

(defn gateway-error?
  "504/524 のような **transport の失敗**。モデルが答えなかったのとは別物。
   実測 2026-08-18: 40 call 中 11 件が Cloudflare の 524 で落ち、これを分母に
   入れると bare の pass 率が 45.5% ではなく 25.0% に見えた。"
  [{:keys [why detail]}]
  (and (= :unparseable-response why)
       (some #(str/includes? (str detail) %) ["error code: 524" "error code: 504" "error code: 502"])))

(defn ask-once
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

(defn ask
  "1 回の chat completion。**エラー本文を捨てない**（5 問の 3）。
   transport の失敗は最大 2 回まで再試行する —— モデルの能力とは無関係な
   ノイズを測定値に混ぜない。"
  [llm messages]
  (loop [n 0]
    (let [r (ask-once llm messages)]
      (if (and (not (:ok r)) (gateway-error? r) (< n 2))
        (recur (inc n))
        (if (and (not (:ok r)) (gateway-error? r))
          (assoc r :why :gateway-timeout)
          r)))))

;; ---------------------------------------------------------------- prompts

(defn ->schema
  "questions.edn の `[[attr doc] ...]` を `kotobase.query.agent` の schema 値にする。"
  [spec]
  {:datasets (:datasets spec)
   :attributes (mapv (fn [[a d]] (cond-> {:attr a} d (assoc :doc d))) (:attributes spec))})


;; ---------------------------------------------------------------- self-test
;; ⚠ **これは validator の fixture ではない。** 通すべき/弾くべきの本体は
;; `kotoba-lang/kotobase-query` の `test/kotobase/query/agent_test.cljc`
;; （12 test / 32 assertion、`run-tests-pure.cljs` で依存ゼロで回る）に在る。
;; ここが確かめるのは**配線だけ** —— classpath が通っていて、questions.edn の
;; schema 変換が agent の期待する形になっていること。それが壊れると validator が
;; 「何も検査せず妥当を返す」形に戻り、それは実際に一度起きた。

(def ^:private wiring-cases
  [["属性一覧が prompt に載る"
    (fn [sc] (str/includes? (agent/system-prompt sc) "company/lei"))]
   ["値の文字列を属性と誤認しない"
    (fn [sc] (nil? (agent/validate '[:find ?t :where [?e "source/dataset" "market-intel"]
                                                     [?e "company/ticker" ?t]] sc)))]
   ["存在しない属性は弾く"
    (fn [sc] (= :unknown-attributes
                (:error (agent/validate '[:find ?x :where [?e "company/nope" ?x]] sc))))]
   ["述語のデータパターン化を弾く"
    (fn [sc] (= :predicate-not-wrapped
                (:error (agent/validate '[:find ?t :where [?e "company/ticker" ?t]
                                                          [>= ?t 1]] sc))))]
   ["クエリの無い応答は nil"
    (fn [_] (nil? (agent/extract-query "分かりません。")))]])

(defn self-test! [questions-path]
  (let [sc (->schema (edn/read-string (.readFileSync fs questions-path "utf8")))
        rs (for [[label f] wiring-cases]
             (let [ok (try (boolean (f sc)) (catch :default e (println "  例外:" (.-message e)) false))]
               {:label label :ok ok}))
        bad (remove :ok rs)]
    (doseq [r rs] (println (str (if (:ok r) "ok   " "FAIL ") (:label r))))
    (println (str "\nwiring self-test: " (- (count rs) (count bad)) "/" (count rs) " passed"
                  "\n（validator 本体の fixture は kotobase-query の run-tests-pure.cljs）"))
    (compat/exit (if (seq bad) 1 0))))

;; ---------------------------------------------------------------- main

(defn slurp' [p] (.readFileSync fs p "utf8"))

(defn -main [& argv]
  (let [{:keys [questions out limit offline max-repair plane-script self-test]} (parse-args argv)]
    (when plane-script (set! *plane-script* plane-script))
    (when self-test (self-test! (or questions "scripts/query-dialect-bench/questions.edn")))
    (when-not questions
      (println "usage: bench.cljs --questions <file.edn> --out <file.edn> [--limit N] [--offline]")
      (compat/exit 2))
    (let [spec (edn/read-string (slurp' questions))
          attrs (:attributes spec)
          datasets (:datasets spec)
          qs (cond->> (:questions spec) limit (take limit))
          sc (->schema spec)]

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

              (let [sys-bare (agent/system-prompt sc {:schema? false :examples? false})
                    sys-full (agent/system-prompt sc)
                    ;; bare 条件と schema 条件を両方走らせる
                    run-cond
                    (fn [label sys]
                      (println (str "  [" label "] " (count qs) " 問…"))
                      (mapv (fn [q]
                              (let [r (ask llm [{:role "system" :content sys}
                                                {:role "user" :content (agent/user-turn (:nl q))}])]
                                (if-not (:ok r)
                                  {:id (:id q) :outcome :llm-error :why (:why r) :detail (:detail r)}
                                  (let [txt (agent/extract-query (:content r))]
                                    (if-not txt
                                      {:id (:id q) :outcome :no-query :raw (subs (:content r) 0 (min 300 (count (:content r))))}
                                      (let [parsed (try (edn/read-string txt) (catch :default e {::pe (.-message e)}))]
                                        (if (and (map? parsed) (::pe parsed))
                                          {:id (:id q) :outcome :unparseable :text txt :detail (::pe parsed)}
                                          {:id (:id q) :outcome :parsed :query parsed :text txt
                                           :invalid (agent/validate parsed sc)})))))))
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
                                                             {:role "user" :content (agent/user-turn (:nl q))}
                                                             {:role "assistant" :content (or (:text item) (:raw item) "")}
                                                             {:role "user"
                                                              :content (agent/repair-turn err)}])]
                                             (if-not (:ok r)
                                               (assoc item :outcome :llm-error :why (:why r) :detail (:detail r))
                                               (let [txt (agent/extract-query (:content r))
                                                     parsed (when txt (try (edn/read-string txt) (catch :default _ nil)))]
                                                 (if (vector? parsed)
                                                   {:id (:id item) :outcome :parsed :query parsed :text txt
                                                    :invalid (agent/validate parsed sc) :repaired (inc round)}
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
                          (let [t (:tally c)
                                pass (or (:pass t) 0)
                                ;; ⚠ **測れなかったものを分母に入れない。** transport の
                                ;; 失敗（524 等）はモデルが間違えたことの証拠ではない。
                                ;; この harness が防ぐために書かれた誤りを、harness 自身の
                                ;; 集計層でやっていた（実測 2026-08-18）。
                                unmeasured (+ (or (:skipped-llm-error t) 0)
                                              (or (:skipped-truncated t) 0)
                                              (or (:skipped-gateway t) 0))
                                answered (- (:asked c) unmeasured)]
                            (println (str "== " (:label c)
                                          "  asked=" (:asked c)
                                          " answered=" answered
                                          " (測れなかった " unmeasured " 件は分母から外す)"
                                          " executed=" (:executed c)))
                            (doseq [[k v] (sort-by (comp str key) t)]
                              (println (str "   " (name k) ": " v)))
                            (println (str "   pass / answered = " pass "/" answered " = "
                                          (if (pos? answered)
                                            (str (.toFixed (* 100 (/ pass answered)) 1) "%")
                                            "算出不能")))
                            (println (str "   pass / asked    = " pass "/" (:asked c)
                                          "  ← 転送障害を失敗として数えた場合。主指標にしない"))))
                        (println (str "\nwrote " (or out "bench-result.edn")))
                        (compat/exit 0)))))))))))))

(apply -main *command-line-args*)

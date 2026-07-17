(ns gftd.cli
  "Shared CLI core for the 8 portfolio CLI binaries (ADR-2607021600, nexus bin added
   ADR-2607105200): itonami / manimani / murakumo / kotoba / aozora / e7m / nexus / gftd.
   (isekai/club/yukkuri are registry-only entries, reachable via `gftd`, without a
   dedicated bin/ wrapper — ADR-2607021900 / ADR-2607022000.)

   Each CLI is the same .cljc engine bound to its product(s); `gftd` is the
   umbrella over all products. すべての書込（人手の canvas add/retract/note・
   hyp pass/fail も含む）は gftd.react/governor を通ってから ledger に積まれる。

   Commands:
     products                              — 扱える product 一覧
     canvas show [--product P]             — 端末表示（fold 済）
     canvas md|edn [--product P|--all] [--out-dir D]  — EDN 投影生成（正本は datoms+ledger; md 別名は互換）
     canvas add|retract <canvas-id> <text> — item 追加/撤回
     canvas note <canvas-id> <text>        — note 差替
     hyp list [--product P]
     hyp pass|fail <hyp-id> --evidence \"…\"
     react tick [--product P] [--metrics k=v…]    — ReAct 1 tick（有界）
     react loop [--product P] [--max-ticks N]     — dry まで反復（budget 有界）
     gate [--product P|--all]                     — 仮説 gate の現況（測定/距離/需）
     funnel show [--product P]                    — 獲得→収益ファネルの現況
     funnel analyze [--product P|--all]           — bottleneck に GTM 提案（governor 経由 ledger）
     ledger show [--tail N]"
  (:require [clojure.string :as str]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            #?(:cljs [scripts.nbb-compat :as nc])
            [gftd.canvas :as canvas]
            [gftd.ledger :as ledger]
            [gftd.react :as react]
            [gftd.gate :as gate]
            [gftd.funnel :as funnel]
            [gftd.score :as score]))

(def registry
  {:itonami  {:products [:cloud-itonami]                :desc "business operator (L3)"}
   :manimani {:products [:cloud-manimani]               :desc "personal wellbecoming OS (L5)"}
   :murakumo {:products [:cloud-murakumo]               :desc "LLM 推論 infra (L1)"}
   :kotoba   {:products [:net-kotobase]                 :desc "storage hosting / graph BaaS (L2)"}
   :aozora   {:products [:app-aozora :app-aozora-yoro]  :desc "social network + messenger (L4)"}
   :isekai   {:products [:network-isekai]               :desc "UGC game / creator platform (L6, Roblox 型)"}
   :club     {:products [:club-shinshi]                 :desc "adult creator platform (L4, PornHub/OnlyFans/FANZA 型)"}
   :yukkuri  {:products [:ai-gftd-yukkuri]              :desc "AI video content channel (L7, YouTube 収益型)"}
   :e7m      {:products [:etzhayyim]                    :desc "artificial organism platform (L0, 非営利)"}
   :nexus    {:products [:nexus-x402]                   :desc "payment facilitator/gateway (Lx cross-cutting, 鍵ゼロ x402 facilitator)"}
   :gftd     {:products :all                            :desc "umbrella — 全 product + ai-gftd-apex"}})

(def base-rel   "90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn")
(def ledger-rel "90-docs/business/canvas-ledger.edn")
(def md-out-rel "90-docs/business")
(def metrics-rel "90-docs/business/metrics")
(def facts-rel  "90-docs/business/maturity-facts.edn")

;; ---- arg parsing ---------------------------------------------------------------

(defn parse-args
  "args → [positional-vec flags-map]; --k v / --k (boolean)."
  [args]
  (loop [[a & r] args pos [] flags {}]
    (cond
      (nil? a) [pos flags]
      (str/starts-with? a "--")
      (let [k (keyword (subs a 2))]
        (if (or (nil? (first r)) (str/starts-with? (str (first r)) "--"))
          (recur r pos (assoc flags k true))
          (recur (rest r) pos (assoc flags k (first r)))))
      :else (recur r (conj pos a) flags))))

(defn ->kw [s] (if (keyword? s) s (keyword (str/replace (str s) #"^:" ""))))

;; ---- help --------------------------------------------------------------------
;; コマンドごとの usage 行。ns docstring の一覧と対応させて保守する。

(def command-help
  [{:cmd "products" :usage ["products                              — 扱える product 一覧"]}
   {:cmd "canvas"   :usage ["canvas show [--product P]             — 端末表示（fold 済）"
                            "canvas md|edn [--product P|--all] [--out-dir D]  — EDN 投影（正本は datoms+ledger）"
                            "canvas add|retract <canvas-id> <text> — item 追加/撤回"
                            "canvas note <canvas-id> <text>        — note 差替"]}
   {:cmd "hyp"      :usage ["hyp list [--product P]"
                            "hyp pass|fail <hyp-id> --evidence \"…\""]}
   {:cmd "react"    :usage ["react tick [--product P] [--metrics k=v…]    — ReAct 1 tick（有界）"
                            "react loop [--product P] [--max-ticks N]     — dry まで反復（budget 有界）"]}
   {:cmd "gate"     :usage ["gate [--product P|--all]                     — 仮説 gate の現況（測定/距離/需）"]}
   {:cmd "funnel"   :usage ["funnel show [--product P]                    — 獲得→収益ファネルの現況"
                            "funnel analyze [--product P|--all]           — bottleneck に GTM 提案（governor 経由 ledger）"]}
   {:cmd "score"    :usage ["score [md]                                   — 成熟度スコア表示 / md 出力"]}
   {:cmd "ledger"   :usage ["ledger show [--tail N]"]}])

(defn find-command-help [cmd]
  (some #(when (= (:cmd %) cmd) %) command-help))

(defn help-text
  "副作用なしで help 文字列を返す（テスト容易）。topic が既知コマンド名なら
   そのコマンドの usage のみ、nil/未知なら全体 help。"
  [cli-key topic]
  (if-let [{:keys [usage]} (find-command-help topic)]
    (str/join "\n" (cons (str (name cli-key) " " topic " — usage:")
                         (map #(str "  " %) usage)))
    (str/join "\n" (concat [(str (name cli-key) " cli — " (get-in registry [cli-key :desc]))
                            "commands:"]
                           (map #(str "  " %) (mapcat :usage command-help))
                           [(str "flags: --product P --all --out-dir D --evidence \"…\" --metrics k=v,… --max-ticks N --tail N")
                            (str "run `" (name cli-key) " <command> --help` for command-specific usage.")]))))

#?(:clj
   (do
     ;; ---- paths ---------------------------------------------------------------
     (defn find-root
       "GFTD_ROOT env か、cwd から上方向に base datoms を探して repo root を決める。"
       []
       (or (System/getenv "GFTD_ROOT")
           (loop [d (.getCanonicalFile (java.io.File. ".")) n 0]
             (cond
               (.exists (java.io.File. d ^String base-rel)) (.getPath d)
               (or (nil? (.getParentFile d)) (>= n 8))
               (throw (ex-info (str "repo root not found (looked for " base-rel
                                    "). run from repo root or set GFTD_ROOT.") {}))
               :else (recur (.getParentFile d) (inc n))))))

     (defn paths []
       (let [root (find-root)
             j (fn [rel] (str root "/" rel))]
         {:root root :base (j base-rel) :ledger (j ledger-rel)
          :md-out (j md-out-rel) :metrics (j metrics-rel) :facts (j facts-rel)}))

     (defn load-idx [{:keys [base ledger]}]
       (canvas/load-index base (ledger/read-events ledger)))

     ;; ---- product resolution ----------------------------------------------------
     (defn cli-products [cli-key idx]
       (let [ps (get-in registry [cli-key :products])]
         (if (= :all ps) (vec (:products idx)) ps)))

     (defn resolve-product
       "単一 product が必要なコマンド用。CLI が 1 product ならそれ、複数なら --product 必須。"
       [cli-key idx flags]
       (let [ps (cli-products cli-key idx)]
         (cond
           (:product flags) (let [p (->kw (:product flags))]
                              (if (some #{p} ps)
                                p
                                (throw (ex-info (str p " is not managed by this cli (allowed: " ps ")") {}))))
           (= 1 (count ps)) (first ps)
           :else (throw (ex-info (str "--product required (one of " (str/join ", " (map name ps)) ")") {})))))

     ;; ---- governed writes ---------------------------------------------------------
     (defn governed-append!
       "proposals → governor → 可決分+拒否記録を ledger へ。拒否があれば表示して exit 1。"
       [cli-key {:keys [ledger] :as ps} idx proposals]
       (let [{:keys [approved rejected]} (react/governor idx proposals)
             actor (str "cli:" (name cli-key))
             events (concat (map #(react/proposal->event 0 actor %) approved)
                            (for [{:keys [proposal reason]} rejected]
                              {:event/type :governor/rejected :event/actor "governor"
                               :event/value (select-keys proposal [:proposal/action :canvas/id :hyp/id :event/value])
                               :event/reason reason}))]
         (ledger/append! ledger events)
         (doseq [p approved]
           (println "ok:" (name (:proposal/action p)) (or (:canvas/id p) (:hyp/id p)) (pr-str (:event/value p))))
         (doseq [{:keys [proposal reason]} rejected]
           (println "REJECTED by governor:" reason "--" (pr-str (:event/value proposal))))
         (when (seq rejected) (System/exit 1))))

     ;; ---- commands ---------------------------------------------------------------
     (defn cmd-canvas-md [cli-key ps idx [_ _] flags]
       (let [products (if (:all flags) (cli-products cli-key idx) [(resolve-product cli-key idx flags)])
             out-dir (or (:out-dir flags) (:md-out ps))
             as-of (str (java.time.LocalDate/now))]
         (doseq [p products]
           (let [f (java.io.File. (str out-dir "/" (name p) "-business-model.edn"))]
             (.mkdirs (.getParentFile f))
             (spit f (pr-str (canvas/render-edn idx p {:as-of as-of})))
             (println "wrote" (.getPath f))))))

     (defn read-metrics [ps product flags]
       (let [f (java.io.File. (str (:metrics ps) "/" (name product) ".edn"))
             file-m (when (.exists f) (edn/read-string (slurp f)))
             flag-m (when-let [m (:metrics flags)]
                      (into {} (for [kv (str/split m #",")
                                     :let [[k v] (str/split kv #"=" 2)]]
                                 [(keyword k) v])))]
         (merge file-m flag-m)))

     (defn cmd-react [cli-key ps idx sub flags]
       (let [product (resolve-product cli-key idx flags)
             metrics (read-metrics ps product flags)
             actor "advisor:gate"
             run (case sub
                   "tick" (let [r (react/tick {:idx idx :product product :metrics metrics :actor actor})]
                            {:ticks [r]})
                   "loop" (react/run-ticks {:idx idx :product product :metrics metrics :actor actor
                                            :max-ticks (parse-long (str (or (:max-ticks flags) "5")))}))]
         (doseq [[i r] (map-indexed vector (:ticks run))]
           (ledger/append! (:ledger ps) (:events r))
           (println (str "tick " (inc i) ": " (count (:proposals r)) " proposal(s), "
                         (count (:approved r)) " approved, " (count (:rejected r)) " rejected"))
           (doseq [p (:approved r)] (println "  +" (or (:canvas/id p) (:hyp/id p)) (pr-str (:event/value p))))
           (doseq [{:keys [proposal reason]} (:rejected r)]
             (println "  x governor:" reason "--" (pr-str (:event/value proposal)))))
         (when (:dry? run) (println "loop went dry (no more proposals) — 進化は収束"))))

     (defn cmd-gate [cli-key ps idx flags]
       (let [products (if (or (:all flags) (not (:product flags)))
                        (cli-products cli-key idx)
                        [(resolve-product cli-key idx flags)])]
         (doseq [p products]
           (let [metrics (read-metrics ps p flags)]
             (doseq [h (canvas/product-hyps idx p)
                     :let [r (gate/evaluate-hyp metrics (get gate/gate-specs (:hyp/id h)))]]
               (println (name p) (:hyp/id h)
                        (str "[" (name (:status r)) "]")
                        (or (:evidence r) (:distance r)
                            (when (:needs r) (str "需: " (str/join " / " (:needs r)))))))))))

     (defn cmd-funnel [cli-key ps idx sub flags]
       (let [products (if (or (:all flags) (not (:product flags)))
                        (cli-products cli-key idx)
                        [(resolve-product cli-key idx flags)])]
         (case sub
           "show"
           (doseq [p products]
             (let [metrics (read-metrics ps p flags)]
               (println (funnel/render-text p metrics))))
           "analyze"
           ;; sales/marketing motion: bottleneck→GTM 提案を governor に通して ledger へ。
           ;; schedule で反復するので dedup 拒否は正常 — react と同じく exit しない。
           (doseq [p products
                   :let [metrics (read-metrics ps p flags)
                         props (funnel/proposals p metrics)]
                   :when (seq props)]
             (let [{:keys [approved rejected]} (react/governor idx props)
                   actor (str "advisor:funnel")
                   events (concat (map #(react/proposal->event 0 actor %) approved)
                                  (for [{:keys [proposal reason]} rejected]
                                    {:event/type :governor/rejected :event/actor "governor"
                                     :event/value (select-keys proposal [:proposal/action :canvas/id :hyp/id :event/value])
                                     :event/reason reason}))]
               (ledger/append! (:ledger ps) events)
               (println (name p) "funnel:" (count approved) "approved," (count rejected) "rejected")
               (doseq [a approved] (println "  +" (:canvas/id a) (pr-str (:event/value a))))
               (doseq [{:keys [proposal reason]} rejected]
                 (println "  x governor:" reason "--" (pr-str (:event/value proposal)))))))))

     (defn -main-for
       "Entry point shared by the 7 wrappers. `--help` / `help` (グローバルまたは
        `<cmd> --help` / `help <cmd>`) は repo root 解決や datoms 読込より前に
        処理し、help-text を印字するだけで終える。"
       [cli-key args]
       (let [[pos flags] (parse-args args)
             [c1 c2 c3 c4] pos
             help? (or (:help flags) (= c1 "help") (= c1 "--help"))
             help-topic (cond (and (= c1 "help") c2) c2
                              (= c1 "help") nil
                              help? c1
                              :else nil)]
        (if help?
          (println (help-text cli-key help-topic))
          (let [ps (paths)
                idx (load-idx ps)]
           (try
           (case [c1 c2]
             ["products" nil]
             (doseq [p (cli-products cli-key idx)]
               (println (name p) "\t" (get canvas/layer-labels (canvas/product-layer idx p))))

             ["canvas" "show"]
             (println (canvas/render-text idx (resolve-product cli-key idx flags)))

             ["canvas" "md"] (cmd-canvas-md cli-key ps idx pos flags)

             ["canvas" "add"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :canvas/add-item :canvas/id (->kw c3)
                                 :event/value c4 :proposal/reason "manual edit"}])
             ["canvas" "retract"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :canvas/retract-item :canvas/id (->kw c3)
                                 :event/value c4 :proposal/reason "manual edit"}])
             ["canvas" "note"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :canvas/note :canvas/id (->kw c3)
                                 :event/value c4 :proposal/reason "manual edit"}])

             ["hyp" "list"]
             (let [p (resolve-product cli-key idx flags)]
               (doseq [{:keys [hyp/id hyp/status hyp/claim]} (canvas/product-hyps idx p)]
                 (println id (str "[" (name status) "]") claim)))
             ["hyp" "pass"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :hyp/status :hyp/id (->kw c3) :event/value :validated
                                 :event/evidence (:evidence flags) :proposal/reason "gate passed"}])
             ["hyp" "fail"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :hyp/status :hyp/id (->kw c3) :event/value :refuted
                                 :event/evidence (:evidence flags) :proposal/reason "gate failed"}])

             ["react" "tick"] (cmd-react cli-key ps idx "tick" flags)
             ["react" "loop"] (cmd-react cli-key ps idx "loop" flags)

             ["gate" nil] (cmd-gate cli-key ps idx flags)

             ["funnel" "show"] (cmd-funnel cli-key ps idx "show" flags)
             ["funnel" "analyze"] (cmd-funnel cli-key ps idx "analyze" flags)

             ["score" nil]
             (let [facts (edn/read-string (slurp (:facts ps)))
                   products (if (or (:all flags) (not (:product flags)))
                              (cli-products cli-key idx)
                              [(resolve-product cli-key idx flags)])]
               (print (score/render-table (score/score-all idx facts products))))
             ["score" "md"]
             (let [facts (edn/read-string (slurp (:facts ps)))
                   scores (score/score-all idx facts (cli-products :gftd idx))
                   f (java.io.File. (str (:md-out ps) "/maturity-scores.edn"))
                   body (score/render-md scores facts)
                   tx [{:db/id -1
                        :doc/id "maturity-scores"
                        :doc/doc_type "maturity-scores-projection"
                        :doc/title "Portfolio maturity scores"
                        :doc/path "90-docs/business/maturity-scores.edn"
                        :doc/body body
                        :doc/source "gftd score md (ADR-2607021700); SSoT = maturity-facts.edn"}]]
               (spit f (pr-str tx))
               (println "wrote" (.getPath f)))

             ["ledger" "show"]
             (let [es (ledger/read-events (:ledger ps))
                   n (parse-long (str (or (:tail flags) "20")))]
               (doseq [e (take-last n es)] (println (pr-str e))))

             ;; default: help
             (println (help-text cli-key nil)))
           (catch clojure.lang.ExceptionInfo e
             (println "error:" (ex-message e))
             (System/exit 1))))))))

   :cljs
   (do
     ;; ---- paths ---------------------------------------------------------------
     (defn find-root
       "GFTD_ROOT env か、cwd から上方向に base datoms を探して repo root を決める。"
       []
       (or (nc/getenv "GFTD_ROOT")
           (loop [d (.getCanonicalFile (nc/file ".")) n 0]
             (cond
               (.exists (nc/file d base-rel)) (.getPath d)
               (or (nil? (.getParentFile d)) (>= n 8))
               (throw (ex-info (str "repo root not found (looked for " base-rel
                                    "). run from repo root or set GFTD_ROOT.") {}))
               :else (recur (.getParentFile d) (inc n))))))

     (defn paths []
       (let [root (find-root)
             j (fn [rel] (str root "/" rel))]
         {:root root :base (j base-rel) :ledger (j ledger-rel)
          :md-out (j md-out-rel) :metrics (j metrics-rel) :facts (j facts-rel)}))

     (defn load-idx [{:keys [base ledger]}]
       (canvas/load-index base (ledger/read-events ledger)))

     ;; ---- product resolution ----------------------------------------------------
     (defn cli-products [cli-key idx]
       (let [ps (get-in registry [cli-key :products])]
         (if (= :all ps) (vec (:products idx)) ps)))

     (defn resolve-product
       "単一 product が必要なコマンド用。CLI が 1 product ならそれ、複数なら --product 必須。"
       [cli-key idx flags]
       (let [ps (cli-products cli-key idx)]
         (cond
           (:product flags) (let [p (->kw (:product flags))]
                              (if (some #{p} ps)
                                p
                                (throw (ex-info (str p " is not managed by this cli (allowed: " ps ")") {}))))
           (= 1 (count ps)) (first ps)
           :else (throw (ex-info (str "--product required (one of " (str/join ", " (map name ps)) ")") {})))))

     ;; ---- governed writes ---------------------------------------------------------
     (defn governed-append!
       "proposals → governor → 可決分+拒否記録を ledger へ。拒否があれば表示して exit 1。"
       [cli-key {:keys [ledger] :as ps} idx proposals]
       (let [{:keys [approved rejected]} (react/governor idx proposals)
             actor (str "cli:" (name cli-key))
             events (concat (map #(react/proposal->event 0 actor %) approved)
                            (for [{:keys [proposal reason]} rejected]
                              {:event/type :governor/rejected :event/actor "governor"
                               :event/value (select-keys proposal [:proposal/action :canvas/id :hyp/id :event/value])
                               :event/reason reason}))]
         (ledger/append! ledger events)
         (doseq [p approved]
           (println "ok:" (name (:proposal/action p)) (or (:canvas/id p) (:hyp/id p)) (pr-str (:event/value p))))
         (doseq [{:keys [proposal reason]} rejected]
           (println "REJECTED by governor:" reason "--" (pr-str (:event/value proposal))))
         (when (seq rejected) (nc/exit 1))))

     ;; ---- commands ---------------------------------------------------------------
     (defn cmd-canvas-md [cli-key ps idx [_ _] flags]
       (let [products (if (:all flags) (cli-products cli-key idx) [(resolve-product cli-key idx flags)])
             out-dir (or (:out-dir flags) (:md-out ps))
             ;; local-time calendar date, matching the :clj branch's
             ;; java.time.LocalDate/now (system-local) — UTC would silently
             ;; stamp a different day near local midnight in non-UTC zones.
             now (js/Date.)
             as-of (str (.getFullYear now) "-"
                        (.padStart (str (inc (.getMonth now))) 2 "0") "-"
                        (.padStart (str (.getDate now)) 2 "0"))]
         (doseq [p products]
           (let [path (str out-dir "/" (name p) "-business-model.edn")]
             (nc/spit path (pr-str (canvas/render-edn idx p {:as-of as-of})))
             (println "wrote" path)))))

     (defn read-metrics [ps product flags]
       (let [f (nc/file (str (:metrics ps) "/" (name product) ".edn"))
             file-m (when (.exists f) (edn/read-string (nc/slurp f)))
             flag-m (when-let [m (:metrics flags)]
                      (into {} (for [kv (str/split m #",")
                                     :let [[k v] (str/split kv #"=" 2)]]
                                 [(keyword k) v])))]
         (merge file-m flag-m)))

     (defn strip-fences
       "LLM 出力の ```edn … ``` markdown フェンスを剥がす。react/llm-advisor は
        出力を read-string するので、フェンスは transport 側(ここ)で除去する。"
       [s]
       (let [s (str/trim (str s))]
         (if (str/starts-with? s "```")
           (-> s
               (str/replace #"^```[a-zA-Z0-9]*\s*" "")
               (str/replace #"\s*```\s*$" ""))
           s)))

     (defn sse-collect
       "SSE stream body → 最終 text (thinking/reasoning delta は捨てる)。
        OpenAI delta (choices[0].delta.content) と Anthropic
        content_block_delta (:delta :text) の両形を受ける。SSE に見えない
        body には nil を返す(非 streaming fallback 用)。ADR-2607172800。"
       [out]
       (when (str/includes? (str out) "data:")
         (let [texts (for [line (str/split-lines (str out))
                           :let [line (str/trim line)]
                           :when (str/starts-with? line "data:")
                           :let [payload (str/trim (subs line 5))]
                           :when (and (seq payload) (not= payload "[DONE]")
                                      (str/starts-with? payload "{"))
                           :let [d (try (js->clj (js/JSON.parse payload) :keywordize-keys true)
                                        (catch :default _ nil))]
                           :when d]
                       (or (get-in d [:choices 0 :delta :content])
                           (when (= "content_block_delta" (:type d))
                             (get-in d [:delta :text]))))]
           (apply str (remove nil? texts)))))

     (defn- llm-complete-fn
       "GFTD_LLM_URL への同期 chat completion (fn [prompt] -> string)。
        react/tick は同期パイプラインなので js/fetch(async)ではなく
        curl execFileSync で待つ(nbb=Node)。OpenAI 互換 /v1/chat/completions と
        Anthropic 互換 /v1/messages の両応答形を受ける。ADR-2607172700。
        既定で stream:true を送る(ADR-2607172800): Cloudflare は非 streaming
        応答を TTFB 100 秒で切る(524)ため、qwen3.6 の長い thinking 生成が
        api.murakumo.cloud / qwen-gad.gftd.ai 経由で死ぬ。streaming なら
        最初の delta が即座に流れ 524 に当たらない。SSE でない応答が返る
        endpoint には従来の JSON parse に fallback する。
        env: GFTD_LLM_MODEL (default qwen3.6-35b-a3b、murakumo fleet の既定) /
        GFTD_LLM_TOKEN (optional Bearer) / GFTD_LLM_MAX_TOKENS (default 3000 —
        qwen3.6 は thinking モデルで reasoning にも token 予算を使う) /
        GFTD_LLM_NO_STREAM=1 (streaming を無効化)。"
       [url]
       (let [model (or (nc/getenv "GFTD_LLM_MODEL") "qwen3.6-35b-a3b")
             token (nc/getenv "GFTD_LLM_TOKEN")
             max-tokens (or (some-> (nc/getenv "GFTD_LLM_MAX_TOKENS") js/parseInt) 3000)
             stream? (not (nc/getenv "GFTD_LLM_NO_STREAM"))
             cp (js/require "child_process")]
         (fn [prompt]
           (let [payload (cond-> {:model model :max_tokens max-tokens
                                  :messages [{:role "user" :content prompt}]}
                           stream? (assoc :stream true))
                 args (cond-> ["-sS" "-N" "-m" "600" "-X" "POST" url
                               "-H" "content-type: application/json"
                               "--data-binary" "@-"]
                        token (into ["-H" (str "authorization: Bearer " token)]))
                 out (.execFileSync cp "curl" (clj->js args)
                                    #js {:input (js/JSON.stringify (clj->js payload))
                                         :encoding "utf8"
                                         :maxBuffer (* 32 1024 1024)})]
             (if-let [streamed (sse-collect out)]
               (strip-fences streamed)
               (let [d (js->clj (js/JSON.parse out) :keywordize-keys true)]
                 (when-let [err (:error d)]
                   (throw (ex-info (str "LLM endpoint error: " (or (:message err) (pr-str err))) {:url url})))
                 (strip-fences
                  (or (get-in d [:choices 0 :message :content])          ; OpenAI 形
                      (some :text (:content d))))))))))                   ; Anthropic 形

     (defn normalize-llm-proposal
       "LLM が :canvas/id / :hyp/id / :proposal/action を string で出す揺れを
        keyword に正規化する (transport 正規化 — 実測: qwen3.6 は
        \"cloud-itonami.metrics\" と string で出し、gemma4 は keyword で出す)。
        妥当性の判定は従来どおり governor に委ね、ここでは形だけ揃える。"
       [p]
       (let [kw #(cond (keyword? %) %
                       (string? %) (keyword (str/replace % #"^:" ""))
                       (symbol? %) (keyword (str %))   ; qwen3.6 実測: quote 無し
                       :else %)]
         (cond-> p
           (contains? p :canvas/id) (update :canvas/id kw)
           (contains? p :hyp/id) (update :hyp/id kw)
           (contains? p :proposal/action) (update :proposal/action kw)
           (contains? p :event/type) (update :event/type kw))))

     (defn cmd-react [cli-key ps idx sub flags]
       (let [product (resolve-product cli-key idx flags)
             metrics (read-metrics ps product flags)
             llm-url (nc/getenv "GFTD_LLM_URL")
             advisor (if llm-url
                       (let [llm (react/llm-advisor (llm-complete-fn llm-url))]
                         (fn [obs] (concat (react/gate-aware-advisor obs)
                                           (map normalize-llm-proposal (llm obs)))))
                       react/gate-aware-advisor)
             actor (if llm-url "advisor:llm+gate" "advisor:gate")
             run (case sub
                   "tick" (let [r (react/tick {:idx idx :product product :metrics metrics :actor actor :advisor advisor})]
                            {:ticks [r]})
                   "loop" (react/run-ticks {:idx idx :product product :metrics metrics :actor actor :advisor advisor
                                            :max-ticks (parse-long (str (or (:max-ticks flags) "5")))}))]
         (doseq [[i r] (map-indexed vector (:ticks run))]
           (ledger/append! (:ledger ps) (:events r))
           (println (str "tick " (inc i) ": " (count (:proposals r)) " proposal(s), "
                         (count (:approved r)) " approved, " (count (:rejected r)) " rejected"))
           (doseq [p (:approved r)] (println "  +" (or (:canvas/id p) (:hyp/id p)) (pr-str (:event/value p))))
           (doseq [{:keys [proposal reason]} (:rejected r)]
             (println "  x governor:" reason "--" (pr-str (:event/value proposal)))))
         (when (:dry? run) (println "loop went dry (no more proposals) — 進化は収束"))))

     (defn cmd-gate [cli-key ps idx flags]
       (let [products (if (or (:all flags) (not (:product flags)))
                        (cli-products cli-key idx)
                        [(resolve-product cli-key idx flags)])]
         (doseq [p products]
           (let [metrics (read-metrics ps p flags)]
             (doseq [h (canvas/product-hyps idx p)
                     :let [r (gate/evaluate-hyp metrics (get gate/gate-specs (:hyp/id h)))]]
               (println (name p) (:hyp/id h)
                        (str "[" (name (:status r)) "]")
                        (or (:evidence r) (:distance r)
                            (when (:needs r) (str "需: " (str/join " / " (:needs r)))))))))))

     (defn cmd-funnel [cli-key ps idx sub flags]
       (let [products (if (or (:all flags) (not (:product flags)))
                        (cli-products cli-key idx)
                        [(resolve-product cli-key idx flags)])]
         (case sub
           "show"
           (doseq [p products]
             (let [metrics (read-metrics ps p flags)]
               (println (funnel/render-text p metrics))))
           "analyze"
           ;; sales/marketing motion: bottleneck→GTM 提案を governor に通して ledger へ。
           ;; schedule で反復するので dedup 拒否は正常 — react と同じく exit しない。
           (doseq [p products
                   :let [metrics (read-metrics ps p flags)
                         props (funnel/proposals p metrics)]
                   :when (seq props)]
             (let [{:keys [approved rejected]} (react/governor idx props)
                   actor (str "advisor:funnel")
                   events (concat (map #(react/proposal->event 0 actor %) approved)
                                  (for [{:keys [proposal reason]} rejected]
                                    {:event/type :governor/rejected :event/actor "governor"
                                     :event/value (select-keys proposal [:proposal/action :canvas/id :hyp/id :event/value])
                                     :event/reason reason}))]
               (ledger/append! (:ledger ps) events)
               (println (name p) "funnel:" (count approved) "approved," (count rejected) "rejected")
               (doseq [a approved] (println "  +" (:canvas/id a) (pr-str (:event/value a))))
               (doseq [{:keys [proposal reason]} rejected]
                 (println "  x governor:" reason "--" (pr-str (:event/value proposal)))))))))

     (defn -main-for
       "Entry point shared by the 7 wrappers. `--help` / `help` (グローバルまたは
        `<cmd> --help` / `help <cmd>`) は repo root 解決や datoms 読込より前に
        処理し、help-text を印字するだけで終える。"
       [cli-key args]
       (let [[pos flags] (parse-args args)
             [c1 c2 c3 c4] pos
             help? (or (:help flags) (= c1 "help") (= c1 "--help"))
             help-topic (cond (and (= c1 "help") c2) c2
                              (= c1 "help") nil
                              help? c1
                              :else nil)]
        (if help?
          (println (help-text cli-key help-topic))
          (let [ps (paths)
                idx (load-idx ps)]
           (try
           (case [c1 c2]
             ["products" nil]
             (doseq [p (cli-products cli-key idx)]
               (println (name p) "\t" (get canvas/layer-labels (canvas/product-layer idx p))))

             ["canvas" "show"]
             (println (canvas/render-text idx (resolve-product cli-key idx flags)))

             ["canvas" "md"] (cmd-canvas-md cli-key ps idx pos flags)

             ["canvas" "add"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :canvas/add-item :canvas/id (->kw c3)
                                 :event/value c4 :proposal/reason "manual edit"}])
             ["canvas" "retract"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :canvas/retract-item :canvas/id (->kw c3)
                                 :event/value c4 :proposal/reason "manual edit"}])
             ["canvas" "note"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :canvas/note :canvas/id (->kw c3)
                                 :event/value c4 :proposal/reason "manual edit"}])

             ["hyp" "list"]
             (let [p (resolve-product cli-key idx flags)]
               (doseq [{:keys [hyp/id hyp/status hyp/claim]} (canvas/product-hyps idx p)]
                 (println id (str "[" (name status) "]") claim)))
             ["hyp" "pass"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :hyp/status :hyp/id (->kw c3) :event/value :validated
                                 :event/evidence (:evidence flags) :proposal/reason "gate passed"}])
             ["hyp" "fail"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :hyp/status :hyp/id (->kw c3) :event/value :refuted
                                 :event/evidence (:evidence flags) :proposal/reason "gate failed"}])

             ["react" "tick"] (cmd-react cli-key ps idx "tick" flags)
             ["react" "loop"] (cmd-react cli-key ps idx "loop" flags)

             ["gate" nil] (cmd-gate cli-key ps idx flags)

             ["funnel" "show"] (cmd-funnel cli-key ps idx "show" flags)
             ["funnel" "analyze"] (cmd-funnel cli-key ps idx "analyze" flags)

             ["score" nil]
             (let [facts (edn/read-string (nc/slurp (:facts ps)))
                   products (if (or (:all flags) (not (:product flags)))
                              (cli-products cli-key idx)
                              [(resolve-product cli-key idx flags)])]
               (print (score/render-table (score/score-all idx facts products))))
             ["score" "md"]
             (let [facts (edn/read-string (nc/slurp (:facts ps)))
                   scores (score/score-all idx facts (cli-products :gftd idx))
                   f (str (:md-out ps) "/maturity-scores.edn")
                   body (score/render-md scores facts)
                   tx [{:db/id -1
                        :doc/id "maturity-scores"
                        :doc/doc_type "maturity-scores-projection"
                        :doc/title "Portfolio maturity scores"
                        :doc/path "90-docs/business/maturity-scores.edn"
                        :doc/body body
                        :doc/source "gftd score md (ADR-2607021700); SSoT = maturity-facts.edn"}]]
               (nc/spit f (pr-str tx))
               (println "wrote" f))

             ["ledger" "show"]
             (let [es (ledger/read-events (:ledger ps))
                   n (parse-long (str (or (:tail flags) "20")))]
               (doseq [e (take-last n es)] (println (pr-str e))))

             ;; default: help
             (println (help-text cli-key nil)))
           ;; ExceptionInfo only, matching the :clj branch — catching :default
           ;; here silently swallowed every unexpected bug (TypeErrors etc.)
           ;; into a generic "error: ..." + exit 1 instead of surfacing them.
           (catch ExceptionInfo e
             (println "error:" (ex-message e))
             (nc/exit 1)))))))))

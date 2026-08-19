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
     react tick [--product P] [--metrics k=v…] [--advisor auto|gate|murakumo]
     react loop [--product P] [--max-ticks N] [--advisor auto|gate|murakumo]
     gate [--product P|--all]                     — 仮説 gate の現況（測定/距離/需）
     funnel show [--product P]                    — 獲得→収益ファネルの現況
     funnel analyze [--product P|--all]           — bottleneck に GTM 提案（governor 経由 ledger）
     allocate [--budget N] [--epsilon E] [--iters K] [--score-key bmc|yc]
                                                    — OT (Sinkhorn) 予算配分表示（ADR-2607194500）
     allocate md                                   — portfolio-allocation.edn 再生成
     allocate write                                — 配分結果を governor 経由で ledger へ記録（任意）
     allocate pools [--epsilon E] [--iters K] [--score-key bmc|yc]
                                                    — released tranche だけを配分（ADR-2608062300）
     allocate pools md                             — capital-pools.edn 再生成
     ledger show [--tail N]
     (ADR-2607180400: default advisor=auto → murakumo LLM + gate; ledger dual-writes to kotobase)"
  (:require [clojure.string :as str]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            #?(:cljs [scripts.nbb-compat :as nc])
            [gftd.canvas :as canvas]
            [gftd.ledger :as ledger]
            [gftd.react :as react]
            [gftd.gate :as gate]
            [gftd.funnel :as funnel]
            [gftd.score :as score]
            [gftd.allocate :as allocate]
            [gftd.murakumo :as murakumo]
            [gftd.kotobase :as kbase]))

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
(def budget-supply-rel "90-docs/business/budget-supply.edn")

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
                            "canvas datoms [--product P|--all]     — fold 済 canvas を構造化 EDN で投影（app 等の consumer 向け）"
                            "canvas add|retract <canvas-id> <text> — item 追加/撤回"
                            "canvas note <canvas-id> <text>        — note 差替"]}
   {:cmd "hyp"      :usage ["hyp list [--product P]"
                            "hyp pass|fail <hyp-id> --evidence \"…\""]}
   {:cmd "react"    :usage ["react tick [--product P] [--metrics k=v…] [--advisor auto|gate|murakumo]"
                            "react loop [--product P] [--max-ticks N] [--advisor auto|gate|murakumo]"
                            "  auto (default)=gate + murakumo LLM; gate=deterministic only; murakumo=require LLM"]}
   {:cmd "gate"     :usage ["gate [--product P|--all]                     — 仮説 gate の現況（測定/距離/需）"]}
   {:cmd "funnel"   :usage ["funnel show [--product P]                    — 獲得→収益ファネルの現況"
                            "funnel analyze [--product P|--all]           — bottleneck に GTM 提案（governor 経由 ledger）"]}
   {:cmd "score"    :usage ["score [md|datoms]                            — 成熟度スコア表示 / md 出力 / 構造化 EDN 投影"]}
   {:cmd "allocate" :usage ["allocate [--budget N] [--epsilon E] [--iters K] [--score-key bmc|yc]"
                            "                                              — OT (Sinkhorn) 予算配分表示（ADR-2607194500）"
                            "allocate md                                   — portfolio-allocation.edn 再生成"
                            "allocate write                                — 配分結果を governor 経由で ledger へ記録（任意）"
                            "allocate pools [--epsilon E] [--iters K] [--score-key bmc|yc]"
                            "                                              — released tranche だけを配分（ADR-2608062300）"
                            "allocate pools md                             — capital-pools.edn 再生成"]}
   {:cmd "ledger"   :usage ["ledger show [--tail N]  (local SSoT; dual-write → kotobase unless --no-kotobase)"]}])

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
                           [(str "flags: --product P --all --out-dir D --evidence \"…\" --metrics k=v,… --max-ticks N --tail N"
                                 " --budget N --epsilon E --iters K --score-key bmc|yc")
                            (str "       --advisor auto|gate|murakumo  --no-kotobase  (BMC_ADVISOR / BMC_KOTOBASE_DUAL_WRITE env)")
                            (str "run `" (name cli-key) " <command> --help` for command-specific usage.")]))))

(defn advisor-mode
  "Resolve advisor mode: flag --advisor > BMC_ADVISOR env > auto."
  [flags env-get]
  (let [raw (or (:advisor flags)
                (when env-get (env-get "BMC_ADVISOR"))
                "auto")
        m (str/lower-case (str raw))]
    (if (contains? #{"auto" "gate" "murakumo"} m) m "auto")))

(defn actor-for-mode [mode]
  (case mode
    "gate" "advisor:gate"
    "murakumo" "advisor:murakumo"
    "advisor:auto"))

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

(defn normalizing-advisor
  "Wrap an LLM-backed advisor so its proposals go through
   `normalize-llm-proposal` before reaching the governor."
  [llm-advisor]
  (fn [obs] (map normalize-llm-proposal (llm-advisor obs))))


#?(:clj
   (do
     ;; ---- paths ---------------------------------------------------------------
     (defn find-root
       "明示 root、または cwd から上方向に base datoms を探して repo root を決める。"
       ([] (find-root nil))
       ([configured-root]
       (or configured-root
           (loop [d (.getCanonicalFile (java.io.File. ".")) n 0]
             (cond
               (.exists (java.io.File. d ^String base-rel)) (.getPath d)
               (or (nil? (.getParentFile d)) (>= n 8))
               (throw (ex-info (str "repo root not found (looked for " base-rel
                                    "). run from repo root or set GFTD_ROOT.") {}))
               :else (recur (.getParentFile d) (inc n)))))))

     (defn paths
       ([] (paths nil))
       ([configured-root]
       (let [root (find-root configured-root)
             j (fn [rel] (str root "/" rel))]
         {:root root :base (j base-rel) :ledger (j ledger-rel)
          :md-out (j md-out-rel) :metrics (j metrics-rel) :facts (j facts-rel)
          :budget-supply (j budget-supply-rel)})))

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
     (defn jvm-http-post!
       "Minimal POST for murakumo / kotobase on the JVM branch."
       [{:keys [url headers body]}]
       (try
         (let [conn (doto (.openConnection (java.net.URL. url))
                      (.setRequestMethod "POST")
                      (.setDoOutput true)
                      (.setConnectTimeout 15000)
                      (.setReadTimeout 120000))]
           (doseq [[k v] headers] (.setRequestProperty conn k v))
           (with-open [os (.getOutputStream conn)]
             (.write os (.getBytes ^String body "UTF-8")))
           (let [code (.getResponseCode conn)
                 b (try (slurp (.getInputStream conn))
                        (catch Exception _
                          (try (slurp (.getErrorStream conn))
                               (catch Exception _ ""))))]
             {:status code :body b}))
         (catch Exception e {:status 0 :body (.getMessage e)})))

     (defn dual-write-kotobase!
       "Best-effort dual-write. JVM path: HTTP Bearer if KOTOBASE_TOKEN set; else skip."
       [ps flags events]
       (when (and (seq events) (kbase/enabled? #(System/getenv %) flags))
         (if-let [token (System/getenv "KOTOBASE_TOKEN")]
           (let [r (kbase/dual-write-via-http! jvm-http-post! events
                                               {:endpoint (or (System/getenv "BMC_KOTOBASE_ENDPOINT")
                                                              kbase/default-endpoint)
                                                :db-name (or (System/getenv "BMC_KOTOBASE_DB")
                                                             kbase/default-db-name)
                                                :token token})]
             (println "kotobase dual-write:" (pr-str r)))
           (println "kotobase dual-write: skipped (set KOTOBASE_TOKEN or use nbb CACAO helper)"))))

     (defn persist-events!
       "Local ledger append (SSoT) + optional kotobase dual-write.

        With --strict-dual-write, a failed publication exits 3 -- not 1. The
        local ledger WAS written, so this is neither success nor a failed tick,
        and a caller must not treat it as 'retry me': re-running would append
        the same observation twice."
       [ps flags events]
       (let [stamped (ledger/append! (:ledger ps) events)
             ok (dual-write-kotobase! ps flags stamped)]
         (when (and (false? ok) (:strict-dual-write flags))
           (println "kotobase dual-write: strict mode — local ledger written, remote publication failed")
           (nc/exit 3))
         stamped))

     (defn governed-append!
       "proposals → governor → 可決分+拒否記録を ledger へ。拒否があれば表示して exit 1。"
       ([cli-key ps idx proposals] (governed-append! cli-key ps idx proposals {}))
       ([cli-key ps idx proposals flags]
        (let [{:keys [approved rejected]} (react/governor idx proposals)
              actor (str "cli:" (name cli-key))
              events (concat (map #(react/proposal->event 0 actor %) approved)
                             (for [{:keys [proposal reason]} rejected]
                               {:event/type :governor/rejected :event/actor "governor"
                                :event/value (select-keys proposal [:proposal/action :canvas/id :hyp/id :event/value])
                                :event/reason reason}))]
          (persist-events! ps (or flags {}) events)
          (doseq [p approved]
            (println "ok:" (name (:proposal/action p)) (or (:canvas/id p) (:hyp/id p)) (pr-str (:event/value p))))
          (doseq [{:keys [proposal reason]} rejected]
            (println "REJECTED by governor:" reason "--" (pr-str (:event/value proposal))))
          (when (seq rejected) (System/exit 1)))))

     (defn resolve-advisor [flags]
       (let [mode (advisor-mode flags #(System/getenv %))]
         (if (= mode "gate")
           {:mode mode :advisor react/gate-aware-advisor :actor (actor-for-mode mode)}
           (let [complete (murakumo/make-complete jvm-http-post! {})
                 llm (normalizing-advisor (react/llm-advisor complete))]
             {:mode mode
              :advisor (react/compose-advisors react/gate-aware-advisor llm)
              :actor (actor-for-mode mode)}))))

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
             {:keys [mode advisor actor]} (resolve-advisor flags)
             _ (println "advisor:" mode "actor:" actor)
             run (case sub
                   "tick" (let [r (react/tick {:idx idx :product product :metrics metrics
                                               :advisor advisor :actor actor})]
                            {:ticks [r]})
                   "loop" (react/run-ticks {:idx idx :product product :metrics metrics
                                            :advisor advisor :actor actor
                                            :max-ticks (parse-long (str (or (:max-ticks flags) "5")))}))]
         (doseq [[i r] (map-indexed vector (:ticks run))]
           (persist-events! ps flags (:events r))
           (println (str "tick " (inc i) ": " (count (:proposals r)) " proposal(s), "
                         (count (:approved r)) " approved, " (count (:rejected r)) " rejected"))
           (doseq [p (:approved r)] (println "  +" (or (:canvas/id p) (:hyp/id p)) (pr-str (:event/value p))))
           (doseq [{:keys [proposal reason]} (:rejected r)]
             (println "  x governor:" reason "--" (pr-str (:event/value proposal)))))
         (when (:dry? run) (println "loop went dry (no more proposals) — 進化は収束"))))

     ;; The folded canvas as data, for consumers that cannot re-fold it.
     ;; `canvas md` writes prose; this writes the same fold as queryable
     ;; entities. Gates are attached only where metrics and a spec exist —
     ;; a hypothesis nobody measured gets no gate keys rather than a red one.
     (defn canvas-gates [ps idx product flags]
       (let [metrics (read-metrics ps product flags)]
         (into {} (for [h (canvas/product-hyps idx product)
                        :let [spec (get gate/gate-specs (:hyp/id h))]
                        :when spec]
                    [(:hyp/id h) (gate/evaluate-hyp metrics spec)]))))

     (defn persist-projection! [path tx]
       (let [f (java.io.File. path)]
         (.mkdirs (.getParentFile f))
         (spit f (pr-str tx))
         (println "wrote" (.getPath f) (str "(" (count tx) " entities)"))))

     (defn cmd-canvas-datoms [cli-key ps idx [_ _] flags]
       (let [products (if (:all flags) (cli-products cli-key idx) [(resolve-product cli-key idx flags)])
             out-dir (or (:out-dir flags) (:md-out ps))
             as-of (str (java.time.LocalDate/now))]
         (doseq [p products]
           (let [f (java.io.File. (str out-dir "/" (name p) "-canvas.datoms.edn"))
                 tx (canvas/render-datoms idx p {:as-of as-of
                                                 :gates (canvas-gates ps idx p flags)})]
             (.mkdirs (.getParentFile f))
             (spit f (pr-str tx))
             (println "wrote" (.getPath f) (str "(" (count tx) " entities)"))))))

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
                         props (funnel/proposals idx p metrics)]
                   :when (seq props)]
             (let [{:keys [approved rejected]} (react/governor idx props)
                   actor (str "advisor:funnel")
                   events (concat (map #(react/proposal->event 0 actor %) approved)
                                  (for [{:keys [proposal reason]} rejected]
                                    {:event/type :governor/rejected :event/actor "governor"
                                     :event/value (select-keys proposal [:proposal/action :canvas/id :hyp/id :event/value])
                                     :event/reason reason}))]
               (persist-events! ps flags events)
               (println (name p) "funnel:" (count approved) "approved," (count rejected) "rejected")
               (doseq [a approved] (println "  +" (:canvas/id a) (pr-str (:event/value a))))
               (doseq [{:keys [proposal reason]} rejected]
                 (println "  x governor:" reason "--" (pr-str (:event/value proposal)))))))))

     ;; ---- allocate (ADR-2607194500) ------------------------------------------
     (defn allocate-inputs
       "score.edn (demand) + budget-supply.edn (supply) + flags → {:result
        :budget-supply :score-key}。allocate/allocate md/allocate write の
        3 コマンド共通の計算 — 常に全ポートフォリオ（cli-products :gftd idx）を
        対象にする（呼び出し元 CLI が単一 product 束縛でも無視する。予算配分は
        本質的にポートフォリオ横断の意思決定であって、部分集合の split は
        実際の予算決定を表さないため — score md が :gftd 固定なのと同じ流儀）。"
       [ps idx flags]
       (let [facts (edn/read-string (slurp (:facts ps)))
             scores (score/score-all idx facts (cli-products :gftd idx))
             budget-supply (edn/read-string (slurp (:budget-supply ps)))
             score-key (keyword (or (:score-key flags) "yc"))
             budget (if (:budget flags)
                      (parse-double (str (:budget flags)))
                      (double (:supply/total-amount budget-supply)))
             epsilon (if (:epsilon flags) (parse-double (str (:epsilon flags))) 0.05)
             max-iters (if (:iters flags) (parse-long (str (:iters flags))) 200)
             demand (into {} (for [[p s] scores] [p (get-in s [score-key :score])]))
             floors (or (:supply/floors budget-supply) {})
             caps (or (:supply/caps budget-supply) {})
             result (allocate/allocate demand budget {:epsilon epsilon :max-iters max-iters
                                                       :floors floors :caps caps})]
         {:result result :budget-supply budget-supply :score-key score-key}))

     (defn pools-inputs
       "budget-supply.edn の :supply/pools（owner が ADR で決めた実額のみ）+ demand
        → {:result :budget-supply :score-key}。

        `allocate-inputs` と違い :supply/total-amount を**読まない** — あれは owner
        未確認の placeholder であって pool ではない（ADR-2608062200 決定 4）。
        `--budget` での上書きも受けない: pool の総額は正本 ADR が決めるもので
        あって CLI flag が決めるものではない。"
       [ps idx flags]
       (let [facts (edn/read-string (slurp (:facts ps)))
             scores (score/score-all idx facts (cli-products :gftd idx))
             budget-supply (edn/read-string (slurp (:budget-supply ps)))
             score-key (keyword (or (:score-key flags) "yc"))
             epsilon (if (:epsilon flags) (parse-double (str (:epsilon flags))) 0.05)
             max-iters (if (:iters flags) (parse-long (str (:iters flags))) 200)
             demand (into {} (for [[p s] scores] [p (get-in s [score-key :score])]))
             pools (vec (:supply/pools budget-supply))
             result (allocate/allocate-pools pools demand {:epsilon epsilon :max-iters max-iters})]
         {:result result :budget-supply budget-supply :score-key score-key}))

     (defn -main-for
       "Entry point shared by the 7 wrappers. `--help` / `help` (グローバルまたは
        `<cmd> --help` / `help <cmd>`) は repo root 解決や datoms 読込より前に
        処理し、help-text を印字するだけで終える。"
       ([cli-key args] (-main-for cli-key args nil))
       ([cli-key args configured-root]
       (let [[pos flags] (parse-args args)
             [c1 c2 c3 c4] pos
             help? (or (:help flags) (= c1 "help") (= c1 "--help"))
             help-topic (cond (and (= c1 "help") c2) c2
                              (= c1 "help") nil
                              help? c1
                              :else nil)]
        (if help?
          (println (help-text cli-key help-topic))
          (let [ps (paths configured-root)
                idx (load-idx ps)]
           (try
           (case [c1 c2]
             ["products" nil]
             (doseq [p (cli-products cli-key idx)]
               (println (name p) "\t" (get canvas/layer-labels (canvas/product-layer idx p))))

             ["canvas" "show"]
             (println (canvas/render-text idx (resolve-product cli-key idx flags)))

             ["canvas" "md"] (cmd-canvas-md cli-key ps idx pos flags)
             ["canvas" "datoms"] (cmd-canvas-datoms cli-key ps idx pos flags)

             ["canvas" "add"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :canvas/add-item :canvas/id (->kw c3)
                                 :event/value c4 :proposal/reason "manual edit"}]
                               flags)
             ["canvas" "retract"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :canvas/retract-item :canvas/id (->kw c3)
                                 :event/value c4 :proposal/reason "manual edit"}]
                               flags)
             ["canvas" "note"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :canvas/note :canvas/id (->kw c3)
                                 :event/value c4 :proposal/reason "manual edit"}]
                               flags)

             ["hyp" "list"]
             (let [p (resolve-product cli-key idx flags)]
               (doseq [{:keys [hyp/id hyp/status hyp/claim]} (canvas/product-hyps idx p)]
                 (println id (str "[" (name status) "]") claim)))
             ["hyp" "pass"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :hyp/status :hyp/id (->kw c3) :event/value :validated
                                 :event/evidence (:evidence flags) :proposal/reason "gate passed"}]
                               flags)
             ["hyp" "fail"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :hyp/status :hyp/id (->kw c3) :event/value :refuted
                                 :event/evidence (:evidence flags) :proposal/reason "gate failed"}]
                               flags)

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
             ["score" "datoms"]
             (let [facts (edn/read-string (slurp (:facts ps)))
                   scores (score/score-all idx facts (cli-products :gftd idx))
                   tx (score/render-datoms scores facts)]
               (persist-projection! (str (:md-out ps) "/maturity-scores.datoms.edn") tx))

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

             ["allocate" nil]
             (let [{:keys [result]} (allocate-inputs ps idx flags)]
               (print (allocate/render-table result)))
             ["allocate" "pools"]
             (let [{:keys [result budget-supply score-key]} (pools-inputs ps idx flags)]
               (if (= "md" c3)
                 (let [f (java.io.File. (str (:md-out ps) "/capital-pools.edn"))
                       body (allocate/render-pools-md result budget-supply score-key)
                       tx [{:db/id -1
                            :doc/id "capital-pools"
                            :doc/doc_type "capital-pools-projection"
                            :doc/title "Capital pools — released tranche allocation"
                            :doc/path "90-docs/business/capital-pools.edn"
                            :doc/body body
                            :doc/source "gftd allocate pools md (ADR-2608062300); SSoT = budget-supply.edn :supply/pools + maturity-scores.edn"}]]
                   (spit f (pr-str tx))
                   (println "wrote" (.getPath f)))
                 (print (allocate/render-pools-table result))))
             ["allocate" "md"]
             (let [{:keys [result budget-supply score-key]} (allocate-inputs ps idx flags)
                   f (java.io.File. (str (:md-out ps) "/portfolio-allocation.edn"))
                   body (allocate/render-md result budget-supply score-key)
                   tx [{:db/id -1
                        :doc/id "portfolio-allocation"
                        :doc/doc_type "portfolio-allocation-projection"
                        :doc/title "Portfolio budget allocation (entropic OT / Sinkhorn)"
                        :doc/path "90-docs/business/portfolio-allocation.edn"
                        :doc/body body
                        :doc/source "gftd allocate md; SSoT = maturity-scores.edn + budget-supply.edn"}]]
               (spit f (pr-str tx))
               (println "wrote" (.getPath f)))
             ["allocate" "write"]
             (let [{:keys [result]} (allocate-inputs ps idx flags)
                   props (allocate/proposals result)
                   {:keys [approved rejected]} (react/governor idx props)
                   actor "cli:allocate"
                   events (concat (map #(react/proposal->event 0 actor %) approved)
                                  (for [{:keys [proposal reason]} rejected]
                                    {:event/type :governor/rejected :event/actor "governor"
                                     :event/value (select-keys proposal [:proposal/action :canvas/id :event/value])
                                     :event/reason reason}))]
               (ledger/append! (:ledger ps) events)
               (println "allocate write:" (count approved) "approved," (count rejected) "rejected")
               (doseq [a approved] (println "  +" (:canvas/id a) (pr-str (:event/value a))))
               (doseq [{:keys [proposal reason]} rejected]
                 (println "  x governor:" reason "--" (pr-str (:event/value proposal)))))

             ["ledger" "show"]
             (let [es (ledger/read-events (:ledger ps))
                   n (parse-long (str (or (:tail flags) "20")))]
               (doseq [e (take-last n es)] (println (pr-str e))))

             ;; default: help
             (println (help-text cli-key nil)))
           (catch clojure.lang.ExceptionInfo e
             (println "error:" (ex-message e))
             (System/exit 1)))))))))

   :cljs
   (do
     ;; ---- paths ---------------------------------------------------------------
     (defn find-root
       "明示 root、または cwd から上方向に base datoms を探して repo root を決める。"
       ([] (find-root nil))
       ([configured-root]
       (or configured-root
           (loop [d (.getCanonicalFile (nc/file ".")) n 0]
             (cond
               (.exists (nc/file d base-rel)) (.getPath d)
               (or (nil? (.getParentFile d)) (>= n 8))
               (throw (ex-info (str "repo root not found (looked for " base-rel
                                    "). run from repo root or set GFTD_ROOT.") {}))
               :else (recur (.getParentFile d) (inc n)))))))

     (defn paths
       ([] (paths nil))
       ([configured-root]
       (let [root (find-root configured-root)
             j (fn [rel] (str root "/" rel))]
         {:root root :base (j base-rel) :ledger (j ledger-rel)
          :md-out (j md-out-rel) :metrics (j metrics-rel) :facts (j facts-rel)
          :budget-supply (j budget-supply-rel)})))

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

     ;; ---- governed writes / murakumo+kotobase I/O (nbb) -------------------------
     (defn nbb-http-post!
       "POST via sync curl (same strategy as babashka.curl). Returns {:status :body}."
       [{:keys [url headers body]}]
       (let [hdr-args (mapcat (fn [[k v]] ["-H" (str k ": " v)]) headers)
             args (cond-> (into ["curl" "-sS" "-L" "-X" "POST" "-w" "\n%{http_code}"
                                 "--max-time" "120"]
                                hdr-args)
                    body (into ["--data-binary" body])
                    true (conj url))
             r (apply nc/sh args)
             out (str (:out r))
             idx (str/last-index-of out "\n")
             [b status-str] (if idx
                              [(subs out 0 idx) (subs out (inc idx))]
                              ["" out])
             status (js/parseInt status-str 10)]
         {:status (if (js/isNaN status) (or (:exit r) 0) status)
          :body b}))

     (defn dual-write-status-path
       "Where the last dual-write outcome is recorded, so a scheduler can see it.

        The failure this exists for is silent by construction: dual-write is
        fail-open, so a broken publication path leaves exit 0 and one line on
        stdout that nobody reads. Measured 2026-08-19: kotobase dual-write had
        been failing on a missing `@noble/curves` for an unknown length of time
        while every tick reported success."
       [ps]
       (str (or (nc/getenv "GFTD_STATE_DIR")
                (str (nc/getenv "HOME") "/.gftd"))
            "/bmc-dual-write-status.edn"))

     (defn record-dual-write-status!
       "Write the outcome where a health check can read it. Never throws."
       [ps ok? detail n]
       (try
         ;; nc/spit already mkdir -p's the parent.
         (let [f (dual-write-status-path ps)]
           (nc/spit f (pr-str {:ok ok?
                               :events n
                               :detail (when detail (subs (str detail) 0 (min 500 (count (str detail)))))
                               :at (.toISOString (js/Date.))})))
         (catch :default _ nil)))

     (defn dual-write-kotobase!
       "Best-effort: spawn kotobase-dual-write.cljs (CACAO via kotobase-client).
        Fail-open — never throws into the local ledger path.

        Returns true on success, false on failure, nil when not attempted, and
        records the outcome to `dual-write-status-path` either way. Fail-open is
        deliberate and stays: a kotobase.net outage must not stop the local
        ledger. What is NOT acceptable is the failure being invisible — the exit
        code deliberately does not change here, because a caller that retries a
        non-zero tick would append duplicate observations and corrupt the very
        series this is meant to protect. Use --strict-dual-write to opt into a
        non-zero exit when you control the caller."
       [ps flags events]
       (when (and (seq events) (kbase/enabled? nc/getenv flags))
         (try
           (let [root (:root ps)
                 tmp (str "/tmp/bmc-kotobase-events-" (js/Date.now) ".edn")
                 helper (str root "/70-tools/bmc/bin/kotobase-dual-write.cljs")
                 cp (str root "/orgs/kotoba-lang/kotobase-client/src:"
                         root "/70-tools/bmc/src:"
                         root "/scripts/nbb_compat:"
                         root)
                 _ (nc/spit tmp (pr-str (vec events)))
                 node-path (str root "/orgs/kotoba-lang/kotobase-client/node_modules"
                                (when-let [p (nc/getenv "NODE_PATH")] (str ":" p)))
                 ;; nbb-compat/sh merges options into spawnSync; set env so
                 ;; @noble/curves resolves from kotobase-client's node_modules.
                 _ (aset (.-env js/process) "NODE_PATH" node-path)
                 r (nc/sh "nbb" "--classpath" cp helper tmp)]
             (try (.unlinkSync (js/require "node:fs") tmp) (catch :default _))
             (if (zero? (:exit r))
               (do (println "kotobase dual-write:" (str/trim (str (:out r))))
                   (record-dual-write-status! ps true nil (count events))
                   true)
               (let [detail (str/trim (str (:err r) " " (:out r)))]
                 (println "kotobase dual-write: FAILED exit" (:exit r) detail)
                 (record-dual-write-status! ps false detail (count events))
                 false)))
           (catch :default e
             (let [detail (or (.-message e) (str e))]
               (println "kotobase dual-write: error" detail)
               (record-dual-write-status! ps false detail (count events))
               false)))))

     (defn persist-events!
       "Local ledger append (SSoT) + optional kotobase dual-write.

        With --strict-dual-write, a failed publication exits 3 -- not 1. The
        local ledger WAS written, so this is neither success nor a failed tick,
        and a caller must not treat it as 'retry me': re-running would append
        the same observation twice."
       [ps flags events]
       (let [stamped (ledger/append! (:ledger ps) events)
             ok (dual-write-kotobase! ps flags stamped)]
         (when (and (false? ok) (:strict-dual-write flags))
           (println "kotobase dual-write: strict mode — local ledger written, remote publication failed")
           (nc/exit 3))
         stamped))

     (defn governed-append!
       "proposals → governor → 可決分+拒否記録を ledger へ。拒否があれば表示して exit 1。"
       ([cli-key ps idx proposals] (governed-append! cli-key ps idx proposals {}))
       ([cli-key ps idx proposals flags]
        (let [{:keys [approved rejected]} (react/governor idx proposals)
              actor (str "cli:" (name cli-key))
              events (concat (map #(react/proposal->event 0 actor %) approved)
                             (for [{:keys [proposal reason]} rejected]
                               {:event/type :governor/rejected :event/actor "governor"
                                :event/value (select-keys proposal [:proposal/action :canvas/id :hyp/id :event/value])
                                :event/reason reason}))]
          (persist-events! ps (or flags {}) events)
          (doseq [p approved]
            (println "ok:" (name (:proposal/action p)) (or (:canvas/id p) (:hyp/id p)) (pr-str (:event/value p))))
          (doseq [{:keys [proposal reason]} rejected]
            (println "REJECTED by governor:" reason "--" (pr-str (:event/value proposal))))
          (when (seq rejected) (nc/exit 1)))))

     (defn resolve-advisor [flags]
       (let [mode (advisor-mode flags nc/getenv)]
         (if (= mode "gate")
           {:mode mode :advisor react/gate-aware-advisor :actor (actor-for-mode mode)}
           (let [complete (murakumo/make-complete nbb-http-post! {})
                 llm (normalizing-advisor (react/llm-advisor complete))]
             {:mode mode
              :advisor (react/compose-advisors react/gate-aware-advisor llm)
              :actor (actor-for-mode mode)}))))

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

     (defn cmd-react [cli-key ps idx sub flags]
       (let [product (resolve-product cli-key idx flags)
             metrics (read-metrics ps product flags)
             {:keys [mode advisor actor]} (resolve-advisor flags)
             _ (println "advisor:" mode "actor:" actor)
             run (case sub
                   "tick" (let [r (react/tick {:idx idx :product product :metrics metrics
                                               :advisor advisor :actor actor})]
                            {:ticks [r]})
                   "loop" (react/run-ticks {:idx idx :product product :metrics metrics
                                            :advisor advisor :actor actor
                                            :max-ticks (parse-long (str (or (:max-ticks flags) "5")))}))]
         (doseq [[i r] (map-indexed vector (:ticks run))]
           (persist-events! ps flags (:events r))
           (println (str "tick " (inc i) ": " (count (:proposals r)) " proposal(s), "
                         (count (:approved r)) " approved, " (count (:rejected r)) " rejected"))
           (doseq [p (:approved r)] (println "  +" (or (:canvas/id p) (:hyp/id p)) (pr-str (:event/value p))))
           (doseq [{:keys [proposal reason]} (:rejected r)]
             (println "  x governor:" reason "--" (pr-str (:event/value proposal)))))
         (when (:dry? run) (println "loop went dry (no more proposals) — 進化は収束"))))

     ;; The folded canvas as data, for consumers that cannot re-fold it.
     ;; `canvas md` writes prose; this writes the same fold as queryable
     ;; entities. Gates are attached only where metrics and a spec exist —
     ;; a hypothesis nobody measured gets no gate keys rather than a red one.
     (defn canvas-gates [ps idx product flags]
       (let [metrics (read-metrics ps product flags)]
         (into {} (for [h (canvas/product-hyps idx product)
                        :let [spec (get gate/gate-specs (:hyp/id h))]
                        :when spec]
                    [(:hyp/id h) (gate/evaluate-hyp metrics spec)]))))

     (defn persist-projection! [path tx]
       (nc/spit path (pr-str tx))
       (println "wrote" path (str "(" (count tx) " entities)")))

     (defn cmd-canvas-datoms [cli-key ps idx [_ _] flags]
       (let [products (if (:all flags) (cli-products cli-key idx) [(resolve-product cli-key idx flags)])
             out-dir (or (:out-dir flags) (:md-out ps))
             now (js/Date.)
             as-of (str (.getFullYear now) "-"
                        (.padStart (str (inc (.getMonth now))) 2 "0") "-"
                        (.padStart (str (.getDate now)) 2 "0"))]
         (doseq [p products]
           (let [path (str out-dir "/" (name p) "-canvas.datoms.edn")
                 tx (canvas/render-datoms idx p {:as-of as-of
                                                 :gates (canvas-gates ps idx p flags)})]
             (nc/spit path (pr-str tx))
             (println "wrote" path (str "(" (count tx) " entities)"))))))

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
                         props (funnel/proposals idx p metrics)]
                   :when (seq props)]
             (let [{:keys [approved rejected]} (react/governor idx props)
                   actor (str "advisor:funnel")
                   events (concat (map #(react/proposal->event 0 actor %) approved)
                                  (for [{:keys [proposal reason]} rejected]
                                    {:event/type :governor/rejected :event/actor "governor"
                                     :event/value (select-keys proposal [:proposal/action :canvas/id :hyp/id :event/value])
                                     :event/reason reason}))]
               (persist-events! ps flags events)
               (println (name p) "funnel:" (count approved) "approved," (count rejected) "rejected")
               (doseq [a approved] (println "  +" (:canvas/id a) (pr-str (:event/value a))))
               (doseq [{:keys [proposal reason]} rejected]
                 (println "  x governor:" reason "--" (pr-str (:event/value proposal)))))))))

     ;; ---- allocate (ADR-2607194500) ------------------------------------------
     (defn allocate-inputs
       "score.edn (demand) + budget-supply.edn (supply) + flags → {:result
        :budget-supply :score-key}。allocate/allocate md/allocate write の
        3 コマンド共通の計算 — 常に全ポートフォリオ（cli-products :gftd idx）を
        対象にする（呼び出し元 CLI が単一 product 束縛でも無視する。予算配分は
        本質的にポートフォリオ横断の意思決定であって、部分集合の split は
        実際の予算決定を表さないため — score md が :gftd 固定なのと同じ流儀）。"
       [ps idx flags]
       (let [facts (edn/read-string (nc/slurp (:facts ps)))
             scores (score/score-all idx facts (cli-products :gftd idx))
             budget-supply (edn/read-string (nc/slurp (:budget-supply ps)))
             score-key (keyword (or (:score-key flags) "yc"))
             budget (if (:budget flags)
                      (parse-double (str (:budget flags)))
                      (double (:supply/total-amount budget-supply)))
             epsilon (if (:epsilon flags) (parse-double (str (:epsilon flags))) 0.05)
             max-iters (if (:iters flags) (parse-long (str (:iters flags))) 200)
             demand (into {} (for [[p s] scores] [p (get-in s [score-key :score])]))
             floors (or (:supply/floors budget-supply) {})
             caps (or (:supply/caps budget-supply) {})
             result (allocate/allocate demand budget {:epsilon epsilon :max-iters max-iters
                                                       :floors floors :caps caps})]
         {:result result :budget-supply budget-supply :score-key score-key}))

     (defn pools-inputs
       "budget-supply.edn の :supply/pools（owner が ADR で決めた実額のみ）+ demand
        → {:result :budget-supply :score-key}。

        `allocate-inputs` と違い :supply/total-amount を**読まない** — あれは owner
        未確認の placeholder であって pool ではない（ADR-2608062200 決定 4）。
        `--budget` での上書きも受けない: pool の総額は正本 ADR が決めるもので
        あって CLI flag が決めるものではない。"
       [ps idx flags]
       (let [facts (edn/read-string (nc/slurp (:facts ps)))
             scores (score/score-all idx facts (cli-products :gftd idx))
             budget-supply (edn/read-string (nc/slurp (:budget-supply ps)))
             score-key (keyword (or (:score-key flags) "yc"))
             epsilon (if (:epsilon flags) (parse-double (str (:epsilon flags))) 0.05)
             max-iters (if (:iters flags) (parse-long (str (:iters flags))) 200)
             demand (into {} (for [[p s] scores] [p (get-in s [score-key :score])]))
             pools (vec (:supply/pools budget-supply))
             result (allocate/allocate-pools pools demand {:epsilon epsilon :max-iters max-iters})]
         {:result result :budget-supply budget-supply :score-key score-key}))

     (defn -main-for
       "Entry point shared by the 7 wrappers. `--help` / `help` (グローバルまたは
        `<cmd> --help` / `help <cmd>`) は repo root 解決や datoms 読込より前に
        処理し、help-text を印字するだけで終える。"
       ([cli-key args] (-main-for cli-key args nil))
       ([cli-key args configured-root]
       (let [[pos flags] (parse-args args)
             [c1 c2 c3 c4] pos
             help? (or (:help flags) (= c1 "help") (= c1 "--help"))
             help-topic (cond (and (= c1 "help") c2) c2
                              (= c1 "help") nil
                              help? c1
                              :else nil)]
        (if help?
          (println (help-text cli-key help-topic))
          (let [ps (paths configured-root)
                idx (load-idx ps)]
           (try
           (case [c1 c2]
             ["products" nil]
             (doseq [p (cli-products cli-key idx)]
               (println (name p) "\t" (get canvas/layer-labels (canvas/product-layer idx p))))

             ["canvas" "show"]
             (println (canvas/render-text idx (resolve-product cli-key idx flags)))

             ["canvas" "md"] (cmd-canvas-md cli-key ps idx pos flags)
             ["canvas" "datoms"] (cmd-canvas-datoms cli-key ps idx pos flags)

             ["canvas" "add"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :canvas/add-item :canvas/id (->kw c3)
                                 :event/value c4 :proposal/reason "manual edit"}]
                               flags)
             ["canvas" "retract"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :canvas/retract-item :canvas/id (->kw c3)
                                 :event/value c4 :proposal/reason "manual edit"}]
                               flags)
             ["canvas" "note"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :canvas/note :canvas/id (->kw c3)
                                 :event/value c4 :proposal/reason "manual edit"}]
                               flags)

             ["hyp" "list"]
             (let [p (resolve-product cli-key idx flags)]
               (doseq [{:keys [hyp/id hyp/status hyp/claim]} (canvas/product-hyps idx p)]
                 (println id (str "[" (name status) "]") claim)))
             ["hyp" "pass"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :hyp/status :hyp/id (->kw c3) :event/value :validated
                                 :event/evidence (:evidence flags) :proposal/reason "gate passed"}]
                               flags)
             ["hyp" "fail"]
             (governed-append! cli-key ps idx
                               [{:proposal/action :hyp/status :hyp/id (->kw c3) :event/value :refuted
                                 :event/evidence (:evidence flags) :proposal/reason "gate failed"}]
                               flags)

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
             ["score" "datoms"]
             (let [facts (edn/read-string (nc/slurp (:facts ps)))
                   scores (score/score-all idx facts (cli-products :gftd idx))
                   tx (score/render-datoms scores facts)]
               (persist-projection! (str (:md-out ps) "/maturity-scores.datoms.edn") tx))

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

             ["allocate" nil]
             (let [{:keys [result]} (allocate-inputs ps idx flags)]
               (print (allocate/render-table result)))
             ["allocate" "pools"]
             (let [{:keys [result budget-supply score-key]} (pools-inputs ps idx flags)]
               (if (= "md" c3)
                 (let [f (str (:md-out ps) "/capital-pools.edn")
                       body (allocate/render-pools-md result budget-supply score-key)
                       tx [{:db/id -1
                            :doc/id "capital-pools"
                            :doc/doc_type "capital-pools-projection"
                            :doc/title "Capital pools — released tranche allocation"
                            :doc/path "90-docs/business/capital-pools.edn"
                            :doc/body body
                            :doc/source "gftd allocate pools md (ADR-2608062300); SSoT = budget-supply.edn :supply/pools + maturity-scores.edn"}]]
                   (nc/spit f (pr-str tx))
                   (println "wrote" f))
                 (print (allocate/render-pools-table result))))
             ["allocate" "md"]
             (let [{:keys [result budget-supply score-key]} (allocate-inputs ps idx flags)
                   f (str (:md-out ps) "/portfolio-allocation.edn")
                   body (allocate/render-md result budget-supply score-key)
                   tx [{:db/id -1
                        :doc/id "portfolio-allocation"
                        :doc/doc_type "portfolio-allocation-projection"
                        :doc/title "Portfolio budget allocation (entropic OT / Sinkhorn)"
                        :doc/path "90-docs/business/portfolio-allocation.edn"
                        :doc/body body
                        :doc/source "gftd allocate md; SSoT = maturity-scores.edn + budget-supply.edn"}]]
               (nc/spit f (pr-str tx))
               (println "wrote" f))
             ["allocate" "write"]
             (let [{:keys [result]} (allocate-inputs ps idx flags)
                   props (allocate/proposals result)
                   {:keys [approved rejected]} (react/governor idx props)
                   actor "cli:allocate"
                   events (concat (map #(react/proposal->event 0 actor %) approved)
                                  (for [{:keys [proposal reason]} rejected]
                                    {:event/type :governor/rejected :event/actor "governor"
                                     :event/value (select-keys proposal [:proposal/action :canvas/id :event/value])
                                     :event/reason reason}))]
               (ledger/append! (:ledger ps) events)
               (println "allocate write:" (count approved) "approved," (count rejected) "rejected")
               (doseq [a approved] (println "  +" (:canvas/id a) (pr-str (:event/value a))))
               (doseq [{:keys [proposal reason]} rejected]
                 (println "  x governor:" reason "--" (pr-str (:event/value proposal)))))

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
             (nc/exit 1))))))))))

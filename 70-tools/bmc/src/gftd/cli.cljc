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
     canvas md [--product P|--all] [--out-dir D]  — md 生成（正本は datoms+ledger）
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
                            "canvas md [--product P|--all] [--out-dir D]  — md 生成（正本は datoms+ledger）"
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
           (let [f (java.io.File. (str out-dir "/" (name p) "-business-model.md"))]
             (.mkdirs (.getParentFile f))
             (spit f (canvas/render-md idx p {:as-of as-of}))
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
                   f (java.io.File. (str (:md-out ps) "/maturity-scores.md"))]
               (spit f (score/render-md scores facts))
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
           (let [f (nc/file (str out-dir "/" (name p) "-business-model.md"))]
             (.mkdirs (.getParentFile f))
             (nc/spit f (canvas/render-md idx p {:as-of as-of}))
             (println "wrote" (.getPath f))))))

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
             (let [facts (edn/read-string (nc/slurp (:facts ps)))
                   products (if (or (:all flags) (not (:product flags)))
                              (cli-products cli-key idx)
                              [(resolve-product cli-key idx flags)])]
               (print (score/render-table (score/score-all idx facts products))))
             ["score" "md"]
             (let [facts (edn/read-string (nc/slurp (:facts ps)))
                   scores (score/score-all idx facts (cli-products :gftd idx))
                   f (nc/file (str (:md-out ps) "/maturity-scores.md"))]
               (nc/spit f (score/render-md scores facts))
               (println "wrote" (.getPath f)))

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

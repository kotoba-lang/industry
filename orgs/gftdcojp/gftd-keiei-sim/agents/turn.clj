;; turn.clj — ターン全体を1つの kotoba-clj defgraph で統括 (制御フローを完全に clj 化)。
;;
;; Rust は本コンポーネントを1回 run するだけ。社員(営業/開発/財務/法務)→財務反論→
;; CEO統括 をノード連鎖(逐次)で実行し、結果は kqe-assert! の quad で外に出す:
;;   sim/proposal / <role>     / sim.proposal/action  ← 各社員/ CEO の提案
;;   sim/discussion / critique / sim.disc/text        ← 財務の反論
;;   sim/discussion / ceo      / sim.disc/text         ← CEO の統括
;;   sim/activity / <role>     / observes              ← 観測回数(観測ログ)
;; Rust は execute の assert_quads を回収して proposals/discussion を組み立てる。
;;
;; 各ノードは intel を kqe で観測(brief/detail/digest/calendar/learn)→ llm-infer。
;; 後段ノード(反論/統括)は前段の提案を state から読んで相互観測する。
;; ctx CBOR: {"brief": <共通の現状況>} / 出力: {"ok": "done"}

(defn buf-str! [b s]
  (loop [i 0] (if (>= i (str-len s)) b (do (byte-append! b (byte-at s i)) (recur (+ i 1))))))
(defn cat2 [a b]
  (let [buf (bytes-alloc (+ 8 (+ (str-len a) (str-len b))))]
    (buf-str! buf a) (buf-str! buf b) (bytes-finish buf)))
(defn obj-text [h]
  (let [r (cbor-reader h)] (if (= (cbor-map-seek r "Text") 1) (cbor-text r) "")))
(defn read-intel [pred]
  (let [h (kqe-get-objects "sim/intel" "all" pred)]
    (if (>= (kqe-count h) 1) (obj-text (kqe-obj-nth h 0)) "")))
(defn ctx-brief [ctx]
  (let [r (cbor-reader ctx)] (if (= (cbor-map-seek r "brief") 1) (cbor-text r) "")))

;; CBOR {"Text": s} を作って quad を assert する (役割=subject)
(defn txt-obj [s]
  (let [o (bytes-alloc (+ 16 (str-len s)))]
    (cbor-enc-map-header! o 1) (cbor-enc-text! o "Text") (cbor-enc-text! o s) (bytes-finish o)))
(defn assert-q [g subj pred s] (kqe-assert! g subj pred (txt-obj s)))

;; persona + 現状況 + 観測 → プロンプト。先頭に [[M:model]] を付け Rust 側でモデル振分け。
(defn mk-prompt [model persona situation obs]
  (let [b (bytes-alloc 2816)]
    (buf-str! b "[[M:")
    (buf-str! b model)
    (buf-str! b "]]")
    (buf-str! b persona)
    (buf-str! b " 観測を踏まえ、必ず『〜を確認した結果、…』の形で観測した具体根拠(社名/金額/件名/予定)を冒頭に含め、この四半期に取るべき施策を1つだけ日本語1文(90字以内)で提案してください。\n--- 現状況 ---\n")
    (buf-str! b situation)
    (buf-str! b "\n--- 観測(社内インテリジェンス) ---\n")
    (buf-str! b obs)
    (bytes-finish b)))

;; ---- 機能部門ノード (観測→提案→assert) -------------------------------------

(defn node-sales [state]
  (let [obs (cat2 (read-intel "sim.intel/brief") (read-intel "sim.intel/digest"))
        a (llm-infer "gftd-sim" (mk-prompt "qwen/qwen3.7-max" "あなたは株式会社gftdの営業責任者です。最有力リードへの具体策を優先します。" (map-get state "brief") obs))]
    (assert-q "sim/proposal" "sales" "sim.proposal/action" a)
    (assert-q "sim/activity" "sales" "observes" "2")
    (map-assoc! state "sales" a)))

(defn node-eng [state]
  (let [obs (cat2 (read-intel "sim.intel/brief") (read-intel "sim.intel/detail"))
        a (llm-infer "gftd-sim" (mk-prompt "moonshotai/kimi-k2-thinking" "あなたは株式会社gftdのエンジニアリング責任者です。プロダクト/開発体制を扱います。" (map-get state "brief") obs))]
    (assert-q "sim/proposal" "eng" "sim.proposal/action" a)
    (assert-q "sim/activity" "eng" "observes" "2")
    (map-assoc! state "eng" a)))

(defn node-finance [state]
  (let [obs (cat2 (cat2 (read-intel "sim.intel/brief") (read-intel "sim.intel/detail")) (read-intel "sim.intel/learn"))
        a (llm-infer "gftd-sim" (mk-prompt "qwen/qwen3.7-max" "あなたは株式会社gftdの財務責任者(CFO)です。資金繰り/コスト/不良債権を扱います。" (map-get state "brief") obs))]
    (assert-q "sim/proposal" "finance" "sim.proposal/action" a)
    (assert-q "sim/activity" "finance" "observes" "3")
    (map-assoc! state "finance" a)))

(defn node-legal [state]
  (let [obs (cat2 (read-intel "sim.intel/brief") (read-intel "sim.intel/detail"))
        a (llm-infer "gftd-sim" (mk-prompt "anthropic/claude-opus-4.8" "あなたは株式会社gftdの法務責任者です。契約更新リスク/コンプラを扱います。" (map-get state "brief") obs))]
    (assert-q "sim/proposal" "legal" "sim.proposal/action" a)
    (assert-q "sim/activity" "legal" "observes" "2")
    (map-assoc! state "legal" a)))

;; ---- 討議ノード (前段の提案を相互観測) --------------------------------------

(defn peers [state]
  (cat2 (cat2 (cat2 (cat2 (cat2 "営業: " (map-get state "sales")) "\n開発: ") (map-get state "eng"))
              (cat2 "\n財務: " (map-get state "finance")))
        (cat2 "\n法務: " (map-get state "legal"))))

(defn node-critique [state]
  (let [a (llm-infer "gftd-sim" (mk-prompt "qwen/qwen3.7-max" "あなたは株式会社gftdの財務責任者です。各部門案の最大の財務リスクを1つ指摘し、どの案を優先すべきか述べます。" "各責任者の提案:" (peers state)))]
    (assert-q "sim/discussion" "critique" "sim.disc/text" a)
    (map-assoc! state "critique" a)))

(defn node-ceo [state]
  (let [obs (cat2 (cat2 (peers state) "\n財務の反論: ") (map-get state "critique"))
        a (llm-infer "gftd-sim" (mk-prompt "minimax/minimax-m3" "あなたは株式会社gftdのCEO補佐(経営参謀)です。各提案と財務の反論を俯瞰し、最優先の経営判断を1つ具申します。" "社内の議論:" obs))]
    (assert-q "sim/proposal" "ceo" "sim.proposal/action" a)
    (assert-q "sim/discussion" "ceo" "sim.disc/text" a)
    (assert-q "sim/activity" "ceo" "observes" "2")
    (map-assoc! state "ceo" a)))

;; ---- Evolution ノード (AI Co-Scientist) -------------------------------------
;; 各部門案 + 財務反論 + CEO統括 を統合し、上位案を結合・先鋭化した「進化版」を生成。
;; Arbor では親ノードを refine/extend した子仮説に相当する。
(defn node-evolve [state]
  (let [obs (cat2 (cat2 (cat2 (peers state) "\n財務の反論: ") (map-get state "critique"))
                  (cat2 "\nCEO統括: " (map-get state "ceo")))
        a (llm-infer "gftd-sim" (mk-prompt "anthropic/claude-opus-4.8" "あなたは株式会社gftdの経営参謀(Evolutionエージェント)です。上記の議論から最も成果が見込める2案を結合・先鋭化し、欠点を補った『進化版』の施策を1つに統合します。" "社内の議論(各案/反論/統括):" obs))]
    (assert-q "sim/proposal" "evolution" "sim.proposal/action" a)
    (assert-q "sim/activity" "evolution" "observes" "4")
    (map-assoc! state "evolution" a)))

(defgraph turn-graph
  :state {:brief :override :sales :override :eng :override :finance :override
          :legal :override :critique :override :ceo :override :evolution :override}
  :entry :sales
  :nodes {:sales node-sales :eng node-eng :finance node-finance :legal node-legal
          :critique node-critique :ceo node-ceo :evolve node-evolve}
  :edges {:sales :eng :eng :finance :finance :legal :legal :critique
          :critique :ceo :ceo :evolve :evolve :end})

(defn ok-result [s]
  (let [out (bytes-alloc (+ 16 (str-len s)))]
    (cbor-enc-map-header! out 1) (cbor-enc-text! out "ok") (cbor-enc-text! out s) (bytes-finish out)))

(defn run [ctx]
  (let [s (map-make 16)]
    (map-assoc! s "brief" (ctx-brief ctx))
    (turn-graph s)
    (ok-result "done")))

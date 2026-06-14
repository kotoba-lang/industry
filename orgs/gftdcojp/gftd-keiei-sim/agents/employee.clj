;; employee.clj — 汎用の社員エージェント (kotoba-clj)。役割/モデル/ペルソナを ctx で受け、
;; intel を kqe で観測してから提案する。Rust が役割ごとに本コンポーネントを並列実行する。
;;
;; ctx CBOR: {"brief": <共通状況>, "role": <subject>, "model": <OpenRouterモデル>, "persona": <役割指示>}
;; 出力 CBOR: {"ok": <提案>, "rounds": <観測ソース数>}
;; プロンプト先頭に [[M:model]] を付け、Rust 側 infer がモデルを振り分ける。

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
(defn ctx-str [ctx key]
  (let [r (cbor-reader ctx)] (if (= (cbor-map-seek r key) 1) (cbor-text r) "")))

;; intel を多角観測 (brief+detail+digest+calendar+learn を結合)
(defn observe-all []
  (cat2 (cat2 (cat2 (cat2 (cat2 (read-intel "sim.intel/brief") "\n")
                          (cat2 (read-intel "sim.intel/detail") "\n"))
                    (cat2 (read-intel "sim.intel/digest") "\n"))
              (cat2 (read-intel "sim.intel/calendar") "\n"))
        (read-intel "sim.intel/learn")))

(defn ok-result [s rounds]
  (let [out (bytes-alloc (+ 48 (str-len s)))]
    (cbor-enc-map-header! out 2)
    (cbor-enc-text! out "ok") (cbor-enc-text! out s)
    (cbor-enc-text! out "rounds") (cbor-enc-uint! out rounds)
    (bytes-finish out)))

(defn propose! [role action]
  (let [obj (bytes-alloc (+ 32 (str-len action)))]
    (cbor-enc-map-header! obj 1) (cbor-enc-text! obj "Text") (cbor-enc-text! obj action)
    (kqe-assert! "sim/proposal" role "sim.proposal/action" (bytes-finish obj))))

(defn run [ctx]
  (let [brief   (ctx-str ctx "brief")
        role    (ctx-str ctx "role")
        model   (ctx-str ctx "model")
        persona (ctx-str ctx "persona")
        obs     (observe-all)
        b (bytes-alloc 3072)]
    (buf-str! b "[[M:") (buf-str! b model) (buf-str! b "]]")
    (buf-str! b persona)
    (buf-str! b " 社内インテリジェンスを踏まえ、必ず『〜を確認した結果、…』の形で観測した具体根拠(社名/金額/件名/予定)を冒頭に含め、この四半期に取るべき施策を1つだけ日本語1文(120字以内)で提案してください。\n--- 現状況 ---\n")
    (buf-str! b brief)
    (buf-str! b "\n--- 観測(社内インテリジェンス) ---\n")
    (buf-str! b obs)
    (let [action (llm-infer "gftd-sim" (bytes-finish b))]
      (propose! role action)
      (ok-result action 5))))

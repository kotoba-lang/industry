;; sales.clj — gftd 経営シム社員 (kotoba-clj defgraph ReActループ)。
;; 提案前に intel を kqe で観測(brief→detail)し、参謀が「追加データ必要(MORE)」と
;; 判断したら再観測してループ(bounded 最大2ラウンド)→ 施策を提案。制御は全て clj。
;; ctx CBOR: {"brief": <役割別状況>, "role": "sales", "turn": <uint>} / 出力: {"ok": <提案>}

(defn buf-str! [b s]
  (loop [i 0] (if (>= i (str-len s)) b (do (byte-append! b (byte-at s i)) (recur (+ i 1))))))
(defn cat2 [a b]
  (let [buf (bytes-alloc (+ 8 (+ (str-len a) (str-len b))))]
    (buf-str! buf a) (buf-str! buf b) (bytes-finish buf)))
(defn obj-text [h]
  (let [r (cbor-reader h)] (if (= (cbor-map-seek r "Text") 1) (cbor-text r) "")))
(defn read-intel [r]
  (let [h (if (= r 0)
            (kqe-get-objects "sim/intel" "all" "sim.intel/brief")
            (kqe-get-objects "sim/intel" "all" "sim.intel/detail"))]
    (if (>= (kqe-count h) 1) (obj-text (kqe-obj-nth h 0)) "")))
(defn ctx-brief [ctx]
  (let [r (cbor-reader ctx)] (if (= (cbor-map-seek r "brief") 1) (cbor-text r) "")))

;; Act=観測: intel を読み obs に追記し round を進める
(defn observe [state]
  (let [r (map-get state "round")]
    (map-assoc! state "obs" (cat2 (cat2 (map-get state "obs") "\n") (read-intel r)))
    (map-assoc! state "round" (+ r 1))))

;; Reason=思考: 観測を踏まえ提案 or MORE 要求
(defn reason [state]
  (let [b (bytes-alloc 2048)]
    (buf-str! b "あなたは株式会社gftdの営業責任者です。最有力リードへの具体策を優先し、 観測(社内インテリジェンス)を踏まえ、追加データが必要なら一行目に MORE とだけ書いてください。十分なら この四半期に取るべき施策を1つだけ、日本語で簡潔に1文()で提案してください。\n--- 現状況 ---\n")
    (buf-str! b (map-get state "brief"))
    (buf-str! b "\n--- 観測 ---\n")
    (buf-str! b (map-get state "obs"))
    (map-assoc! state "action" (llm-infer "gftd-sim" (bytes-finish b)))))

(defn starts-more? [s]
  (and (>= (str-len s) 4) (= (byte-at s 0) 77) (= (byte-at s 1) 79) (= (byte-at s 2) 82) (= (byte-at s 3) 69)))
(defn need-more? [state]
  (and (< (map-get state "round") 2) (starts-more? (map-get state "action"))))

(defgraph sales-agent
  :state {:brief :override :obs :override :round :override :action :override}
  :entry :observe
  :nodes {:observe observe :reason reason}
  :edges {:observe :reason :reason (if-edge need-more? :observe :end)})

(defn ok-result [s]
  (let [out (bytes-alloc (+ 32 (str-len s)))]
    (cbor-enc-map-header! out 1) (cbor-enc-text! out "ok") (cbor-enc-text! out s) (bytes-finish out)))
(defn propose! [action]
  (let [obj (bytes-alloc (+ 32 (str-len action)))]
    (cbor-enc-map-header! obj 1) (cbor-enc-text! obj "Text") (cbor-enc-text! obj action)
    (kqe-assert! "sim/proposal" "sales" "sim.proposal/action" (bytes-finish obj))))

(defn run [ctx]
  (let [s (map-make 8)]
    (map-assoc! s "brief" (ctx-brief ctx))
    (map-assoc! s "obs" "")
    (map-assoc! s "round" 0)
    (let [final (sales-agent s) action (map-get final "action")]
      (propose! action)
      (ok-result action))))

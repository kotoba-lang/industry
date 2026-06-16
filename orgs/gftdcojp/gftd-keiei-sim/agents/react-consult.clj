;; react-consult.clj — 相談agent (kotoba-clj defgraph による ReAct ループ)。
;;
;; CEO の相談に対し、Reason→Act(Observe)→繰り返し→結論 を defgraph で回す。
;; Rust は WASM 実行と intel quad の snapshot 受け渡しのみ(制御ループは全て clj)。
;;
;; グラフ: :observe → :reason → (if-edge need-more? :observe :end)
;;   観測= datomic 由来 intel quad を kqe で読む(round 0=要約 brief / 1+=詳細 detail)。
;;   参謀(gemma4)が「追加データ必要」と判断したら一行目に MORE と書き、再観測へループ。
;;
;; ctx CBOR: {"q": <文脈+相談文>}   出力 CBOR: {"ok": <結論>}

(defn buf-str! [b s]
  (loop [i 0]
    (if (>= i (str-len s))
      b
      (do (byte-append! b (byte-at s i)) (recur (+ i 1))))))

;; 2 つの文字列ハンドルを連結して新しいハンドルを返す
(defn cat2 [a b]
  (let [buf (bytes-alloc (+ 8 (+ (str-len a) (str-len b))))]
    (buf-str! buf a)
    (buf-str! buf b)
    (bytes-finish buf)))

;; CBOR {"Text": <s>} オブジェクトから本文を取り出す
(defn obj-text [h]
  (let [r (cbor-reader h)]
    (if (= (cbor-map-seek r "Text") 1) (cbor-text r) "")))

;; round に応じて intel quad を kqe で観測 (Act): 0=要約, 1+=詳細
(defn read-intel [r]
  (let [h (if (= r 0)
            (kqe-get-objects "sim/intel" "all" "sim.intel/brief")
            (kqe-get-objects "sim/intel" "all" "sim.intel/detail"))]
    (if (>= (kqe-count h) 1) (obj-text (kqe-obj-nth h 0)) "")))

;; ctx から相談文 "q" を取り出す
(defn ctx-q [ctx]
  (let [r (cbor-reader ctx)]
    (if (= (cbor-map-seek r "q") 1) (cbor-text r) "")))

;; ノード: Act=観測。intel を読み obs に追記し round を進める
(defn observe [state]
  (let [r (map-get state "round")]
    (map-assoc! state "obs" (cat2 (cat2 (map-get state "obs") "\n") (read-intel r)))
    (map-assoc! state "round" (+ r 1))))

;; ノード: Reason=思考。観測を踏まえ参謀(gemma4)が回答 or MORE 要求
(defn reason [state]
  (let [b (bytes-alloc 2048)]
    (buf-str! b "[[M:minimax/minimax-m3]]あなたは株式会社gftdの経営参謀です。CEOの相談に答えます。これまでの観測(社内インテリジェンス)を踏まえ、追加データが必要なら一行目に MORE とだけ書いてください。十分なら結論を日本語2〜3文で、必要なら推奨アクションを1つ添えて述べてください。\n--- 相談 ---\n")
    (buf-str! b (map-get state "q"))
    (buf-str! b "\n--- これまでの観測 ---\n")
    (buf-str! b (map-get state "obs"))
    (map-assoc! state "answer" (llm-infer "gftd-sim" (bytes-finish b)))))

;; 回答が "MORE" で始まるか (byte 比較; M=77 O=79 R=82 E=69)
(defn starts-more? [s]
  (and (>= (str-len s) 4)
       (= (byte-at s 0) 77)
       (= (byte-at s 1) 79)
       (= (byte-at s 2) 82)
       (= (byte-at s 3) 69)))

;; if-edge 述語: 追加観測が必要か (最大3ラウンドで打ち切り = bounded ReAct)
(defn need-more? [state]
  (and (< (map-get state "round") 3)
       (starts-more? (map-get state "answer"))))

(defgraph react-consult-graph
  :state {:q :override :obs :override :round :override :answer :override}
  :entry :observe
  :nodes {:observe observe :reason reason}
  :edges {:observe :reason
          :reason (if-edge need-more? :observe :end)})

(defn ok-result [s]
  (let [out (bytes-alloc (+ 32 (str-len s)))]
    (cbor-enc-map-header! out 1)
    (cbor-enc-text! out "ok")
    (cbor-enc-text! out s)
    (bytes-finish out)))

(defn run [ctx]
  (let [s (map-make 8)]
    (map-assoc! s "q" (ctx-q ctx))
    (map-assoc! s "obs" "")
    (map-assoc! s "round" 0)
    (let [final (react-consult-graph s)]
      (ok-result (map-get final "answer")))))

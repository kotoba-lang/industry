;; sales.clj — gftd 経営シム「営業責任者」社員エージェント
;;
;; これは JVM Clojure ではなく kotoba-clj (Clojure/EDN サブセット → WASM コンパイラ)。
;; Rust 側で prelude() を前置して compile_kais_component_str で kotoba-node
;; コンポーネント (run: func(ctx-cbor) -> result<list<u8>, string>) にコンパイルされる。
;;
;; ctx CBOR: {"brief": <現状況テキスト>, "role": "sales", "turn": <uint>}
;; 出力 CBOR: {"ok": <提案テキスト>}
;; 副作用: kqe-assert! で提案 quad (sim/proposal / "sales" / sim.proposal/action) を立てる
;;         → ホスト(Rust)が InvokeResult.assert_quads として回収し、承認時に datom 化。

;; 文字列ハンドル s の生バイトをバイトビルダ b に追記して b を返す
(defn buf-str! [b s]
  (loop [i 0]
    (if (>= i (str-len s))
      b
      (do (byte-append! b (byte-at s i)) (recur (+ i 1))))))

;; ctx マップから "brief" を取り出す (無ければ空文字)
(defn ctx-brief [ctx]
  (let [r (cbor-reader ctx)]
    (if (= (cbor-map-seek r "brief") 1) (cbor-text r) "")))

;; datomic に投影された intel ブリーフ quad を kqe で読む (社員自身が intel を参照)
(defn intel-kqe []
  (let [h (kqe-get-objects "sim/intel" "all" "sim.intel/brief")]
    (if (>= (kqe-count h) 1)
      (let [r (cbor-reader (kqe-obj-nth h 0))]
        (if (= (cbor-map-seek r "Text") 1) (cbor-text r) ""))
      "")))

;; 役割プリアンブル + 現状況 brief + intel(kqe) から LLM プロンプト文字列を組み立てる
(defn build-prompt [brief]
  (let [b (bytes-alloc 1536)]
    (buf-str! b "あなたは株式会社gftdの営業責任者です。以下の現状況とインテリジェンスを踏まえ、この四半期に取るべき営業アクションを1つだけ、日本語で簡潔に1文(80字以内)で提案してください。最有力リードへの具体策を優先してください。\n--- 現状況 ---\n")
    (buf-str! b brief)
    (buf-str! b "\n--- 社内インテリジェンス(datomic/kqe) ---\n")
    (buf-str! b (intel-kqe))
    (bytes-finish b)))

;; {"ok": <text>} を CBOR で返す (画面表示用)
(defn ok-result [s]
  (let [out (bytes-alloc (+ 32 (str-len s)))]
    (cbor-enc-map-header! out 1)
    (cbor-enc-text! out "ok")
    (cbor-enc-text! out s)
    (bytes-finish out)))

;; 提案 quad を立てる: object は QuadObject::Text 互換の CBOR {"Text": <action>}
(defn propose! [action]
  (let [obj (bytes-alloc (+ 32 (str-len action)))]
    (cbor-enc-map-header! obj 1)
    (cbor-enc-text! obj "Text")
    (cbor-enc-text! obj action)
    (kqe-assert! "sim/proposal" "sales" "sim.proposal/action" (bytes-finish obj))))

;; defgraph ノード: 状態の "brief" を読み LLM を呼んで "action" に書き戻す
(defn call-model [state]
  (map-assoc! state "action"
    (llm-infer "gftd-sim" (build-prompt (map-get state "brief")))))

;; LangGraph 風ステートグラフ: START → call → END
(defgraph sales-agent
  :state {:brief :override :action :override}
  :entry :call
  :nodes {:call call-model}
  :edges {:call :end})

;; kotoba-node world のエクスポート
(defn run [ctx]
  (let [s (map-make 4)]
    (map-assoc! s "brief" (ctx-brief ctx))
    (let [final  (sales-agent s)
          action (map-get final "action")]
      (propose! action)
      (ok-result action))))

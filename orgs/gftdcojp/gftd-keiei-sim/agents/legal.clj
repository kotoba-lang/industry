;; legal.clj — gftd 経営シム「法務責任者」社員エージェント
;; ctx CBOR: {"brief": <現状況>, "role": "legal", "turn": <uint>}
;; 提案 quad: sim/proposal / "legal" / sim.proposal/action
;; 契約更新リスク・コンプラを intel(kqe) から読んで具申する。

(defn buf-str! [b s]
  (loop [i 0]
    (if (>= i (str-len s))
      b
      (do (byte-append! b (byte-at s i)) (recur (+ i 1))))))

(defn ctx-brief [ctx]
  (let [r (cbor-reader ctx)]
    (if (= (cbor-map-seek r "brief") 1) (cbor-text r) "")))

(defn intel-kqe []
  (let [h (kqe-get-objects "sim/intel" "all" "sim.intel/brief")]
    (if (>= (kqe-count h) 1)
      (let [r (cbor-reader (kqe-obj-nth h 0))]
        (if (= (cbor-map-seek r "Text") 1) (cbor-text r) ""))
      "")))

(defn build-prompt [brief]
  (let [b (bytes-alloc 1536)]
    (buf-str! b "あなたは株式会社gftdの法務責任者です。現状況とインテリジェンス(特に契約更新リスク)を踏まえ、今期に取るべき契約・コンプラ・リスク是正のアクションを1つだけ、日本語で簡潔に1文(80字以内)で具申してください。\n--- 現状況 ---\n")
    (buf-str! b brief)
    (buf-str! b "\n--- 社内インテリジェンス(datomic/kqe) ---\n")
    (buf-str! b (intel-kqe))
    (bytes-finish b)))

(defn ok-result [s]
  (let [out (bytes-alloc (+ 32 (str-len s)))]
    (cbor-enc-map-header! out 1)
    (cbor-enc-text! out "ok")
    (cbor-enc-text! out s)
    (bytes-finish out)))

(defn propose! [action]
  (let [obj (bytes-alloc (+ 32 (str-len action)))]
    (cbor-enc-map-header! obj 1)
    (cbor-enc-text! obj "Text")
    (cbor-enc-text! obj action)
    (kqe-assert! "sim/proposal" "legal" "sim.proposal/action" (bytes-finish obj))))

(defn call-model [state]
  (map-assoc! state "action"
    (llm-infer "gftd-sim" (build-prompt (map-get state "brief")))))

(defgraph legal-agent
  :state {:brief :override :action :override}
  :entry :call
  :nodes {:call call-model}
  :edges {:call :end})

(defn run [ctx]
  (let [s (map-make 4)]
    (map-assoc! s "brief" (ctx-brief ctx))
    (let [final  (legal-agent s)
          action (map-get final "action")]
      (propose! action)
      (ok-result action))))

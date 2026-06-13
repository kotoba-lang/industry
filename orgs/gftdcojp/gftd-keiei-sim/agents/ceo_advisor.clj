;; ceo_advisor.clj — gftd 経営シム「CEO補佐(経営参謀)」社員エージェント
;; ctx CBOR: {"brief": <全KPIサマリ>, "role": "ceo", "turn": <uint>}
;; 提案 quad: sim/proposal / "ceo" / sim.proposal/action

(defn buf-str! [b s]
  (loop [i 0]
    (if (>= i (str-len s))
      b
      (do (byte-append! b (byte-at s i)) (recur (+ i 1))))))

(defn ctx-brief [ctx]
  (let [r (cbor-reader ctx)]
    (if (= (cbor-map-seek r "brief") 1) (cbor-text r) "")))

(defn build-prompt [brief]
  (let [b (bytes-alloc 1024)]
    (buf-str! b "あなたは株式会社gftdのCEO補佐(経営参謀)です。営業・開発・財務の各指標を俯瞰し、意思決定者(CEO)が今期 最優先で下すべき経営判断を1つだけ、日本語で簡潔に1文(90字以内)で具申してください。\n--- 経営KPIサマリ ---\n")
    (buf-str! b brief)
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
    (kqe-assert! "sim/proposal" "ceo" "sim.proposal/action" (bytes-finish obj))))

(defn call-model [state]
  (map-assoc! state "action"
    (llm-infer "gftd-sim" (build-prompt (map-get state "brief")))))

(defgraph ceo-agent
  :state {:brief :override :action :override}
  :entry :call
  :nodes {:call call-model}
  :edges {:call :end})

(defn run [ctx]
  (let [s (map-make 4)]
    (map-assoc! s "brief" (ctx-brief ctx))
    (let [final  (ceo-agent s)
          action (map-get final "action")]
      (propose! action)
      (ok-result action))))

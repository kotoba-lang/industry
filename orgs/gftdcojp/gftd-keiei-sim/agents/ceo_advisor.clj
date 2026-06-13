;; ceo_advisor.clj — gftd 経営シム社員 (kotoba-clj defgraph ReActループ)。
;; 提案前に intel を kqe で多角観測(brief→detail→digest→calendar→learn)し、参謀が
;; 「追加データ必要(MORE)」なら別ソースを再観測してループ(bounded 最大5R)。
;; 出力 {ok, rounds}: rounds=観測回数(=どのActをいくつ引いたか/活動ログ用)。制御は全て clj。
;; learn = 前ターンの承認結果+KPI変化(Build-Measure-Learn)。
;; ctx CBOR: {"brief": <役割別状況>, "role": "ceo", "turn": <uint>} / 出力: {"ok","rounds"}

(defn buf-str! [b s]
  (loop [i 0] (if (>= i (str-len s)) b (do (byte-append! b (byte-at s i)) (recur (+ i 1))))))
(defn cat2 [a b]
  (let [buf (bytes-alloc (+ 8 (+ (str-len a) (str-len b))))]
    (buf-str! buf a) (buf-str! buf b) (bytes-finish buf)))
(defn obj-text [h]
  (let [r (cbor-reader h)] (if (= (cbor-map-seek r "Text") 1) (cbor-text r) "")))

;; Act 種別: round に応じ観測ソース切替 (0要約 1詳細 2商談履歴 3予定 4前回結果)
(defn read-intel [r]
  (let [pred (cond (= r 0) "sim.intel/brief"
                   (= r 1) "sim.intel/detail"
                   (= r 2) "sim.intel/digest"
                   (= r 3) "sim.intel/calendar"
                   :else   "sim.intel/learn")
        h (kqe-get-objects "sim/intel" "all" pred)]
    (if (>= (kqe-count h) 1) (obj-text (kqe-obj-nth h 0)) "")))
(defn ctx-brief [ctx]
  (let [r (cbor-reader ctx)] (if (= (cbor-map-seek r "brief") 1) (cbor-text r) "")))

(defn observe [state]
  (let [r (map-get state "round")]
    (map-assoc! state "obs" (cat2 (cat2 (map-get state "obs") "\n") (read-intel r)))
    (map-assoc! state "round" (+ r 1))))

(defn reason [state]
  (let [b (bytes-alloc 2560)]
    (buf-str! b "あなたは株式会社gftdのCEO補佐(経営参謀)です。各指標と社内の議論を俯瞰します。 観測(社内インテリジェンス)を踏まえます。追加データが必要なら一行目に MORE とだけ書いてください(brief→詳細→商談履歴→予定→前回結果learn の順で観測可)。前回結果(learn)があれば前期施策の効きを踏まえて調整してください。十分なら この四半期の施策を1つだけ、必ず『〜を確認した結果、…』の形で観測した具体根拠(社名/金額/件名/予定)を冒頭に含め、日本語1文()で提案してください。\n--- 現状況 ---\n")
    (buf-str! b (map-get state "brief"))
    (buf-str! b "\n--- 観測 ---\n")
    (buf-str! b (map-get state "obs"))
    (map-assoc! state "action" (llm-infer "gftd-sim" (bytes-finish b)))))

(defn starts-more? [s]
  (and (>= (str-len s) 4) (= (byte-at s 0) 77) (= (byte-at s 1) 79) (= (byte-at s 2) 82) (= (byte-at s 3) 69)))
(defn need-more? [state]
  (and (< (map-get state "round") 5) (starts-more? (map-get state "action"))))

(defgraph ceo-agent
  :state {:brief :override :obs :override :round :override :action :override}
  :entry :observe
  :nodes {:observe observe :reason reason}
  :edges {:observe :reason :reason (if-edge need-more? :observe :end)})

;; 出力 {"ok": action, "rounds": 観測回数}
(defn ok-result [s rounds]
  (let [out (bytes-alloc (+ 48 (str-len s)))]
    (cbor-enc-map-header! out 2)
    (cbor-enc-text! out "ok") (cbor-enc-text! out s)
    (cbor-enc-text! out "rounds") (cbor-enc-uint! out rounds)
    (bytes-finish out)))
(defn propose! [action]
  (let [obj (bytes-alloc (+ 32 (str-len action)))]
    (cbor-enc-map-header! obj 1) (cbor-enc-text! obj "Text") (cbor-enc-text! obj action)
    (kqe-assert! "sim/proposal" "ceo" "sim.proposal/action" (bytes-finish obj))))

(defn run [ctx]
  (let [s (map-make 8)]
    (map-assoc! s "brief" (ctx-brief ctx))
    (map-assoc! s "obs" "")
    (map-assoc! s "round" 0)
    (let [final (ceo-agent s) action (map-get final "action")]
      (propose! action)
      (ok-result action (map-get final "round")))))

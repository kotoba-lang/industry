#!/usr/bin/env nbb
;; buyer-journey-score.cljs — **買い手の問いに、その場で答えているか**を測る。
;;
;; `murakumo-site-scenario.cljs` は「面が自分の役割に合っているか」を測った。
;; それは**売り手の視点**である —— 面・役割・CTA はどれも売る側の語彙で、
;; 買い手はそんな区分けを持っていない。買い手が持っているのは**順序のある問い**
;; だけで、答えが出ない問いに当たった時点で読むのをやめる。
;;
;; ## この計器が売り手視点と決定的に違う 1 点
;;
;; **問いには順序があり、前の問いが未回答なら後ろの問いは到達しない。**
;;
;; 面ごとの適合度は「5 つの検査のうち 3 つ通った = 60%」と数えるが、
;; 買い手は平均を取らない。**最初に答えの無い問いで止まる。** だから
;; `:reach` —— 何問目まで到達できるか —— が主指標で、通過率は副である。
;;
;; 実測 2026-08-23 の例: ある面が「価格」に答えていなくても、その後ろの
;; 「誰から買うか」「どう買うか」に答えていれば役割適合度は上がる。
;; **しかし買い手はそこへ行けない。**
;;
;; ## 問いの集合は判断であって測定ではない
;;
;; `journeys` は手書き。ICP（`90-docs/business/gtm-icp.datoms.edn`）の
;; `:icp/buyer-role` と `:icp/segment`、および `/onprem` 自身が名指しする
;; 買い手から起こした。**順序も手書き。** 変えれば点数は変わるので、
;; 「測ったら 73% だった」ではなく「**この問いの順序を仮定すると** 73% だった」
;; と読むこと。
;;
;; ## 使い方
;;
;;   nbb scripts/buyer-journey-score.cljs
;;   nbb scripts/buyer-journey-score.cljs --json

(ns buyer-journey-score
  (:require ["node:child_process" :as cp]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(defn- flag? [n] (some #(= n %) args))
(def base "https://murakumo.cloud")

;; ---------------------------------------------------------------------------
;; 問い —— 買い手が持つ順に。検出は決定論的な語の一致。
;; ---------------------------------------------------------------------------

(def negation-markers
  ["ではありません" "ではない" "ません" "しない" "無い" "not " "no " "never" "without "])

(defn- sentences [t] (str/split t #"[。！？\.\!\?]"))

(defn- positive-hit?
  "`re` に一致する文が、**否定文でないこと**まで見る。

   これが要る理由（実測 2026-08-23）: `/onprem` で「本当に動く？証拠は？」が
   ✓ になっていたが、唯一の一致は
   **「測った推論速度を売る商品ではない」** —— 否定文だった。
   **『測ったものを売らない』を『測った証拠が在る』として数えていた。**
   買い手が止まる位置が Q7 から Q3 へ変わる誤りで、
   検出器が肯定と否定を区別しないと、**主張の不在が主張として通る。**"
  [t re]
  (letfn [(negated? [s]
            ;; **否定は文末の述語に来る。** 部分一致で見ると
            ;; 「まだ無いものを並べて出しています」の `無い` を否定として拾い、
            ;; **肯定文を否定として捨てる**（実測 2026-08-23、この形で
            ;; 自分が足した証拠文を自分の検出器が落とした）。
            ;; 先の偽陽性（否定文を証拠として数える）と同じ根で、向きが逆。
            ;; 日本語の述語は文末なので、末尾だけを見る。
            (let [tail (subs s (max 0 (- (count s) 12)))]
              (some #(str/includes? tail %) negation-markers)))]
    (boolean (some (fn [s] (and (re-find re s) (not (negated? s))))
                   (sentences t)))))

(def questions
  "`:id` `:ask`（買い手の言葉）`:detect`（その面が答えているか）。
   `:positive?` が真なら、**否定文の中の一致は数えない**。

   **検出は「答えが在るか」であって「答えが良いか」ではない。**
   良し悪しは `lp-copy-score` の register 側が扱う。ここは到達性だけを見る。"
  {:what      {:ask "これは何？"
               :detect #(and (re-find #"murakumo Node|オンプレ|on-prem|inference PC" %)
                             (re-find #"推論|inference" %))}
   :for-me    {:ask "自分の状況に効く？"
               :detect #(re-find #"こんなときに|When the data|クラウドに上げられない|外に出したくない|NDA|機密" %)}
   :works     {:ask "本当に動く？ 証拠は？" :positive? true
               :detect #(positive-hit? % #"実測|測った値|測った結果|round.?trip|1e-9|0\.00000|entity [0-9]|behaviour check|probe")}
   :price     {:ask "いくら？"
               :detect #(re-find #"99,900|JPY 99,900" %)}
   :expense   {:ask "経費で落ちる？"
               :detect #(re-find #"10万円未満|法令 133|under JPY 100,000|Order Art\. 133" %)}
   :who       {:ask "誰から買う？ 信用できる？"
               :detect #(and (re-find #"Gftd Japan" %) (re-find #"特商法|disclosure" %))}
   :deliver   {:ask "いつ届く？"
               :detect #(re-find #"出荷日は[0-9]|発送予定日|ships in [0-9]|営業日以内" %)}
   :how       {:ask "どうやって買う？"
               :detect #(re-find #"buy\.stripe\.com" %)}
   :abroad    {:ask "日本の外からでも買える？"
               :detect #(re-find #"日本国内のみ|Japan only|ships inside Japan" %)}
   :software  {:ask "上で動かすソフトは別料金？" :positive? true
               :detect #(positive-hit? % #"Apache-2\.0|無償|free of charge|, and free")}
   :formats   {:ask "うちの形式を読める？" :positive? true
               :detect #(positive-hit? % #"STEP|IFC|USD|glTF|DXF|Alembic")}
   :limits    {:ask "できないことは何？"
               :detect #(re-find #"ではありません|ではない|Not a |is not|未実装|not implemented" %)}})

(def journeys
  "買い手 → 着地面 → **その買い手が持つ問いの順序**。

   `:landing` はその買い手が最初に見る面。**問いはそこで答えられなければ
   ならない** —— 別の面に答えが在っても、そこへ行く理由が要る。"
  [{:id :smb
    :label "中小・個人事業（/onprem が名指しする買い手）"
    :landing "/onprem"
    :order [:what :for-me :works :price :expense :who :deliver :how]}
   {:id :threed
    :label "3D/CAD のツール・パイプライン担当（ICP murakumo-jp-threed）"
    :landing "/onprem/3d"
    :order [:for-me :formats :software :works :limits :price :expense :who :how]}
   {:id :threed-en
    :label "同上・日本国外から読む（ソフトは無制限、箱は JP のみ）"
    :landing "/onprem/3d/en"
    :order [:for-me :formats :software :works :limits :abroad :price :who]}
   {:id :entry
    :label "検索・SNS から玄関に来た人"
    :landing "/"
    :order [:what :for-me :price :how]}])

;; ---------------------------------------------------------------------------

(defn- text-of [t]
  (str (cp/execSync (str "curl -sS -L --max-time 30 " (pr-str (str base t)))
                    #js {:encoding "utf8" :maxBuffer 20000000})))

(defn- plain [h]
  (-> h
      (str/replace #"(?s)<script.*?</script>" " ")
      (str/replace #"(?s)<style.*?</style>" " ")
      (str/replace #"<[^>]+>" " ")
      (str/replace #"\s+" " ")))

(defn evaluate [{:keys [landing order] :as j}]
  (let [html (text-of landing)
        ;; **購入リンクは素の HTML でしか見えない**（href の中なので plain では消える）。
        ;; だから検出は本文と HTML の両方に当てる —— 片方だけだと
        ;; 「答えが在るのに無いと報告する」。
        hay (str (plain html) " " html)
        answered (mapv (fn [q]
                         (let [{:keys [ask detect]} (questions q)]
                           {:q q :ask ask :answered? (boolean (detect hay))}))
                       order)
        reach (count (take-while :answered? answered))]
    (assoc j :answers answered
             :asked (count order)
             :answered-count (count (filter :answered? answered))
             :reach reach
             :stall (when (< reach (count order))
                      (nth order reach))
             ;; **主指標は到達**。平均ではない。
             :reach-pct (js/Number (.toFixed (* 100.0 (/ reach (count order))) 1))
             :coverage-pct (js/Number (.toFixed (* 100.0 (/ (count (filter :answered? answered))
                                                            (count order))) 1)))))

(defn -main []
  (let [rs (mapv evaluate journeys)]
    (if (flag? "--json")
      (println (js/JSON.stringify (clj->js rs) nil 2))
      (do
        (println "murakumo.cloud — 買い手の問いに、その場で答えているか\n")
        (doseq [{:keys [label landing answers reach asked stall reach-pct coverage-pct]} rs]
          (println (str "── " label))
          (println (str "   着地: " landing
                        "   到達 " reach "/" asked " (" reach-pct "%)"
                        "   回答 " coverage-pct "%"))
          (doseq [[i {:keys [ask answered?]}] (map-indexed vector answers)]
            (println (str "     " (if answered? "✓" "✗") " "
                          (inc i) ". " ask
                          (when (and stall (= i reach)) "   ← ここで止まる"))))
          (println))
        (println "**主指標は到達（reach）であって回答率ではない。**")
        (println "買い手は平均を取らない —— 最初に答えの無い問いで読むのをやめる。")
        (println "問いの集合と順序は手書きの仮定。変えれば点数は変わる。")))))

(-main)

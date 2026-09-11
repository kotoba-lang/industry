(ns gftd.gate
  "Gate evaluator (ADR-2607022100): turn collected metrics into hypothesis
   progression so each BMC actually *cycles* on a schedule instead of going dry.

   Per riskiest hypothesis, a gate-spec is either:
   - machine-measurable — {:metric [path...] :op :>= :threshold N} over the
     product's metrics edn. When satisfied → propose `hyp/status :validated`
     with the measured value as evidence (auto-advance). When measurable but
     not yet met → promote hyp to :measuring once + emit a 'gate distance'
     observation (hyp no longer stays untested when the instrument is live).
   - instrument-blocked — {:needs [\"missing instrument\" ...]}. The gate can't be
     measured until those are prepared, so propose a concrete '準備:' item into
     the solution block (actionable to-do) and record the gate as :blocked.

   This is what makes the daily schedule a real kaizen cycle: validation moves
   when real data crosses a gate, and the preparation gap for each product is
   surfaced as an actionable proposal (through the governor) rather than the loop
   silently going dry. Pure .cljc; io stays in cli/collect.

   ## 判定そのものの正本は `70-tools/bmc/kotoba/gate_core.kotoba`

   「測った値と閾値と演算子から validated / measuring / blocked のどれか」と
   「その状態をもう一度提案してよいか」は、この名前空間の**参照実装**であり、
   正本は `.kotoba` の decision core（ADR-2608159300）。両者の完全一致は fleet
   gate `root-gate-core` が毎回見る。**食い違ったら kernel が正しく、ここを直す。**

   対応する関数は `value-admitted` / `clause-code` / `code-verdict` /
   `clause-verdict` / `all-init` / `all-step` / `all-verdict` / `should-propose`
   と、その上の定数（`op-*` / `code-*` / `verdict-*` / `status-*` /
   `absent-bp` / `max-abs-bp`）。名前も引数の順序も kernel と同じにしてある。

   kernel に無いもの（ここに残るもの）: `gate-specs` という表そのもの、metrics
   map の `get-in`、canvas item の重複除去、提案 map の組み立て、evidence 文。"
  (:require [clojure.string :as str]
            [gftd.canvas :as canvas]))

;; ---- per-hypothesis gate specs ----------------------------------------------
;; Grounded in what collect.cljs can measure today (Cloudflare zone / Stripe /
;; health / product-supplied metrics edn) vs. the product-repo instruments still
;; missing (ADR-2607021900/2607022000 の診断)。

(def gate-specs
  {:hyp/murakumo-tok-price
   ;; measurable via GET /infer/cost emitter (ADR-2607022200): fleet ¥/Mtok <= spot ¥/Mtok
   {:compare {:lhs [:cost :fleet-yen-per-mtok] :op :<= :rhs [:cost :spot-yen-per-mtok]}
    :evidence-label "fleet ¥/Mtok vs spot"
    :needs-when-unmeasurable ["run ledger の原価/tok export (node別 tok/s × 電力)"
                              "社内3アプリ推論の fleet 移管で原価比較"]}

   :hyp/apex-privacy-premium
   ;; measurable via subscription telemetry emitter (apex): Free→Plus 転換率 >= Proton 水準
   ;; Stripe product は既存 (Gftd AI Pro prod_SdVVTqTHT1Z206 / price_1RiEU5… $20)。
   {:metric [:conversion :pct] :op :>= :threshold 0.02
    :evidence-label "Free→Plus 転換率"
    :needs-when-unmeasurable ["Stripe checkout 配線 (product 既存)" "Free→Plus 転換テレメトリ"]}

   :hyp/kotobase-graph-arpu
   ;; machine-measurable: first paid tenant = Stripe active subscription >= 1
   {:metric [:stripe :active-subscriptions] :op :>= :threshold 1
    :evidence-label "Stripe active subscriptions"
    :needs-when-unmeasurable ["signup→checkout 配線 (kotobase price 既存)" "tenant 従量計測"]}

   :hyp/itonami-smb-pay
   ;; measurable via tenant metrics emitter (itonami): 外部有償 org >= 1
   {:metric [:tenants :external-paid] :op :>= :threshold 1
    :evidence-label "外部有償 org 数"
    :needs-when-unmeasurable ["初期 vertical の絞り込み" "外部オンボーディング導線" "per-seat billing"]}

   :hyp/aozora-organism-content
   ;; measurable via appview.aozora.app/api/engagement (2026-07-16 live、ADR-2607151900)。
   ;; ratio = organism_engagement / total_engagement。total engagement=0 のとき
   ;; nil-honest (0/0) → unmeasurable。emitter は既に稼働しているので、残る
   ;; needs は「計器」でなく「実 engagement を生む content/audience」。
   {:metric [:engagement :organism-engagement-ratio] :op :>= :threshold 0.3
    :evidence-label "organism engagement 比率"
    :needs-when-unmeasurable ["organism content への実 engagement (like/reply/repost) — emitter は稼働済、母数がゼロ"
                              "organism 投稿の増加 (agent actor の投稿頻度) + 閲覧者の獲得"]}

   :hyp/yoro-aozora-funnel
   ;; measurable via yoro MAU emitter: aozora→yoro MAU 転換率が閾値超え
   {:metric [:mau :conversion-pct] :op :>= :threshold 0.1
    :evidence-label "aozora→yoro MAU 転換率"
    :needs-when-unmeasurable ["yoro child repo 分離" "MAU テレメトリ"]}

   :hyp/manimani-ledger-pay
   ;; measurable via /telemetry/install + signups emitter (ADR-2607022200): 転換率 >= Obsidian Sync 水準
   {:metric [:conversion :pct] :op :>= :threshold 0.04
    :evidence-label "OSS→cloud 転換率"
    :needs-when-unmeasurable ["OSS install テレメトリ" "cloud signup funnel" "価格設計"]}

   :hyp/etzhayyim-registry-value
   ;; 非営利・主観 gate: 自動 validate しない (needs-only)。
   ;; etzhayyim RAD emitter (PR#2840) の :rad {:attestation-refs 320 ...} は「既存
   ;; attestation(sigref)数」であって gate 意図の「itonami が契約要件として参照した回数」
   ;; ではない — これで auto-pass させると未達成 gate を誤 validate する。最終「価値を
   ;; 認める」判断は評議=人間 (scout/PR#2840 明記)。itonami-contract-ref の実計測が
   ;; 入るまで needs-only を維持し、通過は人手で hyp pass する。
   {:needs ["itonami 契約の RAD attestation 参照フック (rad_attestation_ref scaffold の live 化)"
            "資金チャネル (寄付/助成)"]}

   :hyp/isekai-fork-viral
   ;; measurable via public/feed/fork-stats.edn emitter (network-isekai PR#15): viral 係数 >= 1.0
   {:metric [:fork :viral-coefficient] :op :>= :threshold 1.0
    :evidence-label "fork viral 係数"
    :needs-when-unmeasurable ["fork イベントテレメトリ (週次 fork 数 / fork 由来新規作品比率)"]}

   :hyp/club-shinshi-creator-take
   ;; measurable via creator_billing_daily emitter (ADR-2607022200): creator GMV > ad 収益
   {:compare {:lhs [:revenue :creator-gmv-jpy] :op :> :rhs [:revenue :ad-revenue-jpy]}
    :evidence-label "creator GMV vs ad 収益"
    :needs-when-unmeasurable ["PSP/crypto rail 解禁 (ADR-2605220000 凍結)" "creator billing" "creator GMV 計測"]}

   :hyp/yukkuri-ypp-then-rpm
   ;; machine-measurable: YPP = subscribers>=1000 AND watch-hours>=4000
   {:all [{:metric [:channel :subscribers] :op :>= :threshold 1000}
          {:metric [:channel :watch-hours] :op :>= :threshold 4000}]
    :evidence-label "YouTube 登録者/総再生"
    :needs-when-unmeasurable ["YouTube Data API OAuth 復活" "投稿本数の量産"]}

   :hyp/nexus-x402-adoption
   ;; machine-measurable via public GET /catalog census (collect.cljs :nexus-x402
   ;; gate-emitter, no admin auth needed): 最低1 seller が nexus 経由で live 稼働
   ;; (「最低1つが…移行」の :hyp/gate 文言に忠実な proxy — 個別ゲート「廃止」まで
   ;; は問わない。廃止の有無は README/ADR に別途明記し続ける)。
   {:metric [:catalog :count] :op :>= :threshold 1
    :evidence-label "nexus /catalog 登録 seller 数"
    :needs-when-unmeasurable ["seller registry 実装 (ADR-0001 landed)" "seller 登録 (SELLERS_JSON/KV)"]}

   :hyp/nexus-x402-agent-demand
   ;; machine-measurable since nexus-x402 shipped GET /stats (2026-07-10):
   ;; aggregate-only settlement census, :agent-hint classified by
   ;; nexus.settlements/classify-user-agent (a HONEST HEURISTIC, not proof --
   ;; User-Agent is spoofable by either side; this gate tracks "settlements
   ;; that didn't present a browser fingerprint", not "verified autonomous
   ;; agents"). collect.bb's :nexus-x402 emitter now fetches /stats (superset
   ;; of /catalog) into :catalog, so :catalog :settlements :agent-hint :agent
   ;; is the real path.
   {:metric [:catalog :settlements :agent-hint :agent] :op :> :threshold 0
    :evidence-label "nexus /stats agent-hint 決済件数 (heuristic)"
    :needs-when-unmeasurable ["SETTLEMENTS_KV に実決済が記録される運用開始 (現状 GMV 0 — 未計測)"]}

   :hyp/nexus-x402-external-seller
   ;; machine-measurable via the same /catalog census: known internal family is
   ;; exactly {murakumo, kotobase, shinshi} (3) — count > 3 necessarily means a
   ;; non-family (external) seller registered.
   {:metric [:catalog :count] :op :> :threshold 3
    :evidence-label "nexus /catalog 登録 seller 数 (内部3社超)"
    :needs-when-unmeasurable ["self-serve onboarding (docs/adr/0002 ステップ2)" "外部 seller 1件以上の登録"]}})

;; ---- the decision core, mirrored ---------------------------------------------
;; 以下は `70-tools/bmc/kotoba/gate_core.kotoba` の写し。**名前・引数の順序・
;; 戻り値は kernel と 1 対 1**（gate が名前で突き合わせる）。i64 の形をそのまま
;; 保つため bool ではなく 0/1 を返す。Clojure 側で読みやすくするための糖衣は
;; 付けない —— 付けた瞬間、突き合わせるものが 2 つに増える。

(def op-ge 1)
(def op-gt 2)
(def op-le 3)
(def op-lt 4)
(def op-eq 5)
;; `gate-specs` に無い演算子が来たときに host が渡すコード。kernel は
;; `code-unanswerable` を返し、`status-of` がそれを例外にする。
(def op-unknown 0)

(def code-unanswerable -2)
(def code-absent -1)
(def code-unmet 0)
(def code-met 1)

(def verdict-blocked 0)
(def verdict-measuring 1)
(def verdict-validated 2)

(def status-untested 0)
(def status-measuring 1)
(def status-validated 2)

;; 「host がこの欄を数にできなかった」を表す basis point。JS の
;; `Number.MIN_SAFE_INTEGER`。cljs では i64 が double を経由するので、
;; 番兵も admit する幅も 2^53 の内側に収める（ADR-2608122000）。
(def absent-bp -9007199254740991)
(def max-abs-bp 9000000000000000)

(defn value-admitted
  "1 = 比較してよい値、0 = 欠測として扱う。番兵と等しいかだけを見ず、
   admit 範囲の外はすべて信用しない。"
  [x]
  (if (< x (- 0 max-abs-bp)) 0 (if (< max-abs-bp x) 0 1)))

(defn clause-code
  "`lhs op rhs` → 1 met / 0 unmet / -1 absent / -2 unanswerable。
   節（測定値 vs 閾値）と cross-metric 比較（lhs vs rhs）は同じ判断なので
   同じ関数で決める。"
  [lhs op rhs]
  (cond
    (< (value-admitted lhs) 1) code-absent
    (< (value-admitted rhs) 1) code-absent
    (== op op-ge) (if (< lhs rhs) 0 1)
    (== op op-gt) (if (< rhs lhs) 1 0)
    (== op op-le) (if (< rhs lhs) 0 1)
    (== op op-lt) (if (< lhs rhs) 1 0)
    (== op op-eq) (if (== lhs rhs) 1 0)
    :else code-unanswerable))

(defn code-verdict
  "節の結果 → 判定。未知のコードは **blocked にしない** —— seam が壊れたことを
   『計器が無い』と綴らないため。"
  [c]
  (cond (== c code-met) verdict-validated
        (== c code-unmet) verdict-measuring
        (== c code-absent) verdict-blocked
        :else code-unanswerable))

(defn clause-verdict [lhs op rhs] (code-verdict (clause-code lhs op rhs)))

(defn all-init [] verdict-validated)

(defn all-step
  "連言の畳み込み。吸収順は -2 > blocked > measuring > validated。"
  [acc c]
  (cond (== acc code-unanswerable) code-unanswerable
        (== c code-unanswerable) code-unanswerable
        (== acc verdict-blocked) verdict-blocked
        (== c code-absent) verdict-blocked
        (== c code-unmet) verdict-measuring
        :else acc))

(defn all-verdict
  "節が 0 本の `:all` を validated と綴らない（ADR-2608136000）。移行前は
   `(every? :met [])` = true でそのまま validated を返していた。"
  [acc n]
  (if (< n 1) verdict-blocked acc))

(defn should-propose
  "既に持っている状態を再提案しない。untested < measuring < validated の
   全順序なので、規則は『順位が上がるときだけ』1 本に畳める。"
  [current proposed]
  (if (< current proposed) 1 0))

;; ---- host 側の橋渡し（kernel には無い） ---------------------------------------

(defn- num [x] (cond (number? x) x (string? x) (parse-double x) :else nil))

(def ^:private op-codes
  ;; `:=` と `:==` がどちらも同じコードなのは、移行前の `op-fn` が両方 `==` に
  ;; 落としていたのをそのまま写したもの。
  {:>= op-ge :> op-gt :<= op-le :< op-lt := op-eq :== op-eq})

(defn op-code
  "`gate-specs` の `:op` → kernel の演算子コード。**未知の op を既定値に
   落とさない** —— `op-unknown` を渡すと kernel が `-2` を返し、`status-of` が
   例外にする（移行前は `case` が IllegalArgumentException を投げていた）。"
  [op]
  (get op-codes op op-unknown))

(defn ->bp
  "測定値・閾値を整数 basis point（10000 = 1.0）へ。**数にできないものは 0 では
   なく `absent-bp`**（ADR-2608136000）—— 欠測を 0 にすると `>= 0.02` の gate では
   『測って落第』、`:> 0` の gate では『合格』に見え、どちらも欠測とは読めない。

   丸めは **最近傍 bp（同値は +∞ 方向）**。`Math/round` は JVM でも JS でも
   `floor(x + 0.5)` なので runtime をまたいで同じ値になる。切り捨て（floor）に
   しない理由は、10 進の閾値が壊れるから: `0.3 * 10000` は倍精度で
   2999.9999999999995 なので、floor だと閾値 0.3 が 2999 bp（= 0.29990）になり、
   **意図した閾値そのものが 1 bp 甘くなる**。量子化の粒度が 1e-4 なので、
   閾値の 0.00005 未満の差は met 側に倒れる —— これは意図した挙動変更で、
   ADR-2608159300 に記録してある。

   `max-abs-bp` を超える値も absent。cljs では i64 が double を経由するので、
   2^53 の外は正確に往復しない —— 黙って別の数で比較するより欠測と綴る。"
  [x]
  (let [v (num x)]
    (if (or (nil? v) (not (== v v)))            ; nil / 非数値 / NaN
      absent-bp
      (let [s (Math/round (* v 10000.0))]
        (if (and (<= (- 0 max-abs-bp) s) (<= s max-abs-bp)) s absent-bp)))))

(def ^:private verdict-keywords
  {verdict-blocked :blocked
   verdict-measuring :measuring
   verdict-validated :validated})

(defn- status-of
  "kernel の i64 判定 → この pipeline が読む keyword。**全域であり、対応の無い
   値は既定ではなく例外**。`:i64` の戻りを map で引く継ぎ目は、cloud-itonami-app
   の `bot/status` が静かに壊れた場所そのもの（ADR-2608122000）—— 既定値を
   置くと『kernel が答えられなかった』が『blocked』として運用に溶ける。"
  [code ctx]
  (or (get verdict-keywords code)
      (throw (ex-info (str "gate-core が host の知らない判定コードを返した: " code
                           (when (== code code-unanswerable)
                             " — gate-spec の演算子が :>= :> :<= :< := のどれでもない"))
                      (assoc ctx :verdict-code code)))))

(defn- threshold-bp
  "閾値は `gate-specs` のリテラルなので、数でないのは計器の欠測ではなく
   **spec の欠陥**。黙って blocked にせず落とす（移行前の cljs は
   `(>= 5 nil)` が JS の null 強制で true になっていた）。"
  [spec threshold]
  (let [t (->bp threshold)]
    (if (== t absent-bp)
      (throw (ex-info (str "gate-spec の :threshold が数ではない: " (pr-str threshold))
                      {:spec spec :threshold threshold}))
      t)))

(defn- clause-bits
  "1 節 → {:code i64 :value 生の測定値}。判定は bp で下し、**evidence 文には
   生の値を使う**（移行前と同じ文字列が出る）。"
  [metrics {:keys [metric op threshold] :as clause}]
  (let [v (num (get-in metrics metric))]
    {:value v
     :code (clause-code (->bp v) (op-code op) (threshold-bp clause threshold))}))

(defn evaluate-hyp
  "Evaluate one hypothesis' gate against the product's metrics.
   → {:status :validated|:measuring|:blocked :evidence str :needs [..] :distance str}"
  [metrics spec]
  (cond
    (nil? spec) {:status :blocked :needs ["gate-spec 未定義"]}

    ;; conjunction of clauses (:all)
    (:all spec)
    (let [rs (mapv #(clause-bits metrics %) (:all spec))
          acc (reduce all-step (all-init) (map :code rs))]
      (case (status-of (all-verdict acc (count rs)) {:spec spec})
        :blocked {:status :blocked :needs (:needs-when-unmeasurable spec)}
        :validated
        {:status :validated
         :evidence (str (:evidence-label spec) " gate 到達: "
                        (str/join " / " (map :value rs)))}
        :measuring
        {:status :measuring
         :distance (str (:evidence-label spec) " 現在 "
                        (str/join " / " (map :value rs)) " (gate 未到達)")}))

    ;; cross-metric comparison (lhs op rhs) — 節と同じ判断（clause-code）に落ちる
    (:compare spec)
    (let [{:keys [lhs op rhs]} (:compare spec)
          l (num (get-in metrics lhs)) r (num (get-in metrics rhs))]
      (case (status-of (clause-verdict (->bp l) (op-code op) (->bp r)) {:spec spec})
        :blocked {:status :blocked :needs (:needs-when-unmeasurable spec)}
        :validated {:status :validated
                    :evidence (str (:evidence-label spec) " gate 到達: " l " vs " r)}
        :measuring {:status :measuring
                    :distance (str (:evidence-label spec) " 現在 " l " vs " r " (gate 未到達)")}))

    ;; single measurable clause
    (:metric spec)
    (let [r (clause-bits metrics spec)]
      (case (status-of (code-verdict (:code r)) {:spec spec})
        :blocked {:status :blocked :needs (:needs-when-unmeasurable spec)}
        :validated {:status :validated
                    :evidence (str (:evidence-label spec) " = " (:value r) " (gate 到達)")}
        :measuring {:status :measuring
                    :distance (str (:evidence-label spec) " = " (:value r) " (gate 未到達)")}))

    ;; instrument-blocked
    (:needs spec) {:status :blocked :needs (:needs spec)}

    :else {:status :blocked :needs ["gate-spec 不明"]}))

;; ---- proposals ---------------------------------------------------------------

(def ^:private status-ranks
  {:untested status-untested :measuring status-measuring :validated status-validated})

(defn status-rank
  "`:hyp/status` → kernel の順位。**既定は untested**。これは
   `status-of` と違って既定を持ってよい —— 移行前の規則は
   `(not= :validated s)` / `(and (not= :measuring s) (not= :validated s))` で、
   nil も `:untested` も `:invalidated` も等しく『validated より下』として
   扱っていた。その意味をそのまま順位 0 に写している。"
  [s]
  (get status-ranks s status-untested))

(defn- block-id [product suffix] (keyword (str (name product) "." suffix)))

(defn- block-items-set [idx block-id]
  "Get current canvas items for a block as a set for dedup."
  (let [block (get-in idx [:blocks block-id])]
    (if block (set (:canvas/items block)) #{})))

(defn proposals
  "Given the folded index, a product, and its metrics, return governor-ready
   proposals that advance the BMC cycle:
   - :validated → hyp/status :validated (evidence attached)
   - :blocked   → 準備 to-do item into the solution block (dedup'd)
   - :measuring → hyp/status :measuring once (instrument live) + gate-distance
                  observation into key-metrics (dedup'd)"
  [idx product metrics]
  (let [hyps (canvas/product-hyps idx product)]
    (mapcat
     (fn [h]
       (let [hid (:hyp/id h)
             spec (get gate-specs hid)]
        (if (nil? spec)
          []                                  ; gate-spec の無い仮説はスキップ（no-op）
          (let [r (evaluate-hyp metrics spec)]
           (case (:status r)
           :validated
           ;; 既に :validated の仮説は再提案しない — これが無いと gate 到達後の
           ;; 毎 tick/毎日 run が同じ昇格 event を ledger に積み続け、loop が
           ;; 収束 (dry) しない (2026-07-15 cloud-murakumo 実測で発見)。
           ;; 判定は kernel の `should-propose`（順位が上がるときだけ）。
           (when (== 1 (should-propose (status-rank (:hyp/status h)) status-validated))
             [{:proposal/action :hyp/status :hyp/id hid :event/value :validated
               :event/evidence (:evidence r)
               :proposal/reason "gate 到達 (機械測定) — 仮説を validated に昇格"}])
           :blocked
           (let [solution-items (block-items-set idx (block-id product "solution"))]
             (for [need (:needs r)
                   :let [txt (str "準備 (" (name hid) "): " need)]
                   :when (not (contains? solution-items txt))]
               {:proposal/action :canvas/add-item
                :canvas/id (block-id product "solution")
                :event/value txt
                :proposal/reason "gate 測定に必要な計器/前提が未整備 — 準備項目として提案"}))
           :measuring
           ;; 計器が読めて未到達なら hyp を :measuring に昇格（一度きり）し、
           ;; gate 距離を key-metrics に載せる。:validated と同様に既に
           ;; :measuring の hyp は status 再提案しない。
           (let [metrics-items (block-items-set idx (block-id product "metrics"))
                 txt (str "gate 距離 (" (name hid) "): " (:distance r))
                 status-up (when (== 1 (should-propose (status-rank (:hyp/status h))
                                                       status-measuring))
                             [{:proposal/action :hyp/status :hyp/id hid
                               :event/value :measuring
                               :event/evidence (or (:distance r) (:evidence r)
                                                   "gate instrumented, threshold not met")
                               :proposal/reason "gate 計器稼働・未到達 — measuring に昇格"}])
                 dist-item (if (contains? metrics-items txt)
                             []
                             [{:proposal/action :canvas/add-item
                               :canvas/id (block-id product "metrics")
                               :event/value txt
                               :proposal/reason "gate は機械測定可能・未到達 — 距離を運用指標に反映"}])]
             (concat status-up dist-item))
           [])))))
     hyps)))

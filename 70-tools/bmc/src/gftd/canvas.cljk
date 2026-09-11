(ns gftd.canvas
  "Lean canvas as datoms + append-only event fold (ADR-2607021500 / ADR-2607021600).

   Base (SSoT) = 90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn.
   The base file is never rewritten by tooling; every edit is an event appended
   to the canvas ledger (gftd.ledger) and folded over the base here.
   Portable .cljc: pure data in the core, io only behind #?(:clj)."
  (:require [clojure.string :as str]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            #?(:cljs [scripts.nbb-compat :as nc])))

;; ---- base datoms -----------------------------------------------------------

(defn parse-datoms [s] (edn/read-string s))

(defn canvas-block? [d] (contains? d :canvas/id))
(defn hyp? [d] (contains? d :hyp/id))

(defn index
  "datoms vector → {:blocks {canvas-id block-datom} :hyps {hyp-id hyp-datom}
                    :products (sorted set)}"
  [datoms]
  {:blocks   (into {} (comp (filter canvas-block?) (map (juxt :canvas/id identity))) datoms)
   :hyps     (into {} (comp (filter hyp?) (map (juxt :hyp/id identity))) datoms)
   :products (into (sorted-set) (keep :canvas/product) datoms)})

;; ---- event fold ------------------------------------------------------------

(def rolling-observation-prefixes
  "機械生成の定期観測 item の接頭辞。routine が毎時/毎日積む「観測 (signal):」
   「観測 (paths):」は数値だけが進む同型行で、無制限に conj すると Problem/
   Channels block が観測ログに埋まる(実測: cloud-murakumo の Problem block に
   約 70 行堆積、ADR-2607151900 の分析 4 項)。fold 時に接頭辞ごとの rolling
   window で最新 N 件だけ残す。ledger は append-only のまま(全履歴保持)—
   これは fold(= 表示・advisor の観測面)の保持ポリシーであって削除ではない。
   人手や分析の実質的観測(「観測 (2026-07-06): …」「根本原因判明 …」等、
   この接頭辞に一致しないもの)は無期限に残る。"
  ["観測 (signal):" "観測 (paths):"])

(def rolling-observation-window
  "接頭辞ごと・block ごとに fold が残す定期観測の件数。3 = 直近の傾向が
   目視できる最小限。"
  3)

(defn- rolling-prefix [s]
  (some #(when (str/starts-with? (str s) %) %) rolling-observation-prefixes))

(defn conj-item
  "block items へ 1 件追加。定期観測(rolling-observation-prefixes)は同じ
   接頭辞の古い item を window 超過分だけ落としてから追加する。それ以外は
   ただの conj。"
  [items v]
  (let [items (vec items)
        p (rolling-prefix v)]
    (if-not p
      (conj items v)
      (let [same (filterv #(= p (rolling-prefix %)) items)
            drop-n (max 0 (- (inc (count same)) rolling-observation-window))
            to-drop (into #{} (take drop-n same))]
        (conj (if (seq to-drop) (vec (remove to-drop items)) items) v)))))

(defn apply-event
  "Fold one ledger event into the index. Unknown/observation events are no-ops
   (they are history, not canvas state)."
  [idx e]
  (case (:event/type e)
    :canvas/add-item
    (update-in idx [:blocks (:canvas/id e) :canvas/items] (fnil conj-item []) (:event/value e))
    :canvas/retract-item
    (update-in idx [:blocks (:canvas/id e) :canvas/items]
               (fn [items] (vec (remove #{(:event/value e)} items))))
    :canvas/note
    (assoc-in idx [:blocks (:canvas/id e) :canvas/note] (:event/value e))
    :hyp/status
    (update-in idx [:hyps (:hyp/id e)] assoc
               :hyp/status (:event/value e)
               :hyp/evidence (:event/evidence e))
    idx))

(defn fold [idx events] (reduce apply-event idx events))

;; ---- product / layer labels --------------------------------------------------

(def layer-labels
  {:llm-inference-infra          "L1 LLM 推論 — infra（Civitai × exo）"
   :llm-inference-app            "L1 LLM 推論 — app（Proton 型）"
   :storage-hosting              "L2 storage hosting（graph BaaS + map / git / search）"
   :business-operator            "L3 business operator（全業種・職種 SaaS + investment platform）"
   :social-network               "L4 social network（atproto SNS）"
   :messenger                    "L4 messenger"
   :adult-creator-platform       "L4 adult creator platform（PornHub / OnlyFans / FANZA 型）"
   :personal-wellbecoming-os     "L5 personal wellbecoming OS"
   :ugc-game-platform            "L6 UGC game / creator platform（Roblox 型）"
   :video-content-channel        "L7 AI video content channel（YouTube 収益型）"
   :artificial-organism-platform "L0 artificial organism platform（非営利・公益）"
   :payment-facilitator-infra    "Lx cross-cutting: payment facilitator/gateway（Cloudflare Monetization Gateway 型）"})

(def block-order
  [:lean/problem :lean/customer-segments :lean/uvp :lean/solution :lean/channels
   :lean/revenue-streams :lean/cost-structure :lean/key-metrics :lean/unfair-advantage])

(defn product-blocks [idx product]
  (let [by-block (into {} (map (juxt :canvas/block identity))
                       (filter #(= product (:canvas/product %)) (vals (:blocks idx))))]
    (keep by-block block-order)))

(defn product-hyps [idx product]
  (sort-by :hyp/id (filter #(= product (:hyp/product %)) (vals (:hyps idx)))))

(defn product-layer [idx product]
  (some #(when (= product (:canvas/product %)) (:canvas/layer %)) (vals (:blocks idx))))

;; ---- render ------------------------------------------------------------------

(defn render-md
  "Render one product's lean canvas (post-fold) as markdown text (embedded in EDN
   :doc/body). opts: {:as-of str}.
   正本は datoms+ledger。投影ファイルは .edn only（ADR-2607171600）。"
  [idx product {:keys [as-of]}]
  (let [layer (product-layer idx product)]
    (str "# " (name product) " — business model / lean canvas\n\n"
         "<!-- generated by gftd cli (70-tools/bmc, ADR-2607021600).\n"
         "     正本 = 90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn + canvas ledger.\n"
         "     手編集禁止 — `gftd canvas add|retract|note` / `gftd hyp pass|fail` で編集し再生成する。 -->\n\n"
         "**Layer**: " (get layer-labels layer (str layer)) "  \n"
         "**As-of**: " (or as-of "base") "  \n"
         "**Prose 解説**: `90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn`\n\n"
         (apply str
                (for [{:keys [canvas/label canvas/items canvas/note]} (product-blocks idx product)]
                  (str "## " label "\n\n"
                       (apply str (for [i items] (str "- " i "\n")))
                       (when note (str "\n> " note "\n"))
                       "\n")))
         "## Hypotheses\n\n"
         "| id | risk | status | claim | gate | evidence |\n"
         "|---|---|---|---|---|---|\n"
         (apply str
                (for [{:keys [hyp/id hyp/risk hyp/status hyp/claim hyp/gate hyp/evidence]}
                      (product-hyps idx product)]
                  (str "| `" id "` | " (name risk) " | " (name status) " | "
                       claim " | " gate " | " (or evidence "—") " |\n"))))))

(defn render-edn
  "DataScript-transactable projection of one product's canvas (EDN-only surface)."
  [idx product {:keys [as-of]}]
  [{:db/id -1
    :doc/id (str "business-model-" (name product))
    :doc/product product
    :doc/doc_type "business-model-projection"
    :doc/title (str (name product) " — business model / lean canvas")
    :doc/path (str "90-docs/business/" (name product) "-business-model.edn")
    :doc/as-of (or as-of "base")
    :doc/layer (product-layer idx product)
    :doc/source "gftd canvas (ADR-2607021600); SSoT = portfolio BMC datoms + canvas-ledger"
    :doc/body (render-md idx product {:as-of as-of})}])

(defn render-datoms
  "Structured, DataScript-transactable projection of one product's FOLDED canvas.

   `render-edn` already exists and is a different thing: it wraps `render-md`'s
   markdown in `:doc/body`, which reads well and cannot be queried. Nothing could
   ask 「この product の Problem block の item は何か」 without parsing prose, and
   nothing outside this repository could render the 9 blocks at all.

   That mattered once a consumer appeared. `cloud-itonami-app`'s business plane
   (ADR-2607309600) binds a business to a `:canvas/product` and has to show the
   blocks; it is released on its own, cannot depend on this tool, and re-folding
   the ledger inside the app would be a second implementation of `apply-event`
   that drifts. So the fold stays here, once, and emits data.

   The base datoms carry PRE-fold state: the ledger events are not in the docs
   EDN plane at all, so a `:find` over 90-docs today answers with the canvas as
   it was first written, not as it stands. This projection is the folded value,
   tagged `:source/dataset \"canvas-projection\"` so a query can tell the two
   apart rather than silently mixing them.

   `:gates` is an optional map of hyp-id → the map `gftd.gate/evaluate-hyp`
   returns. It is passed IN rather than computed here: gate evaluation needs
   metrics, and this namespace deliberately knows nothing about them. A
   hypothesis with no entry gets no gate keys at all — an absent evaluation is
   not a failed one, and writing `:gate/status :blocked` for 「まだ測っていない」
   would be a measurement of nothing."
  [idx product {:keys [as-of gates]}]
  (let [blocks (product-blocks idx product)
        hyps (product-hyps idx product)]
    (into
     [{:db/id -1
       :projection/id (str "canvas-" (name product))
       :projection/product product
       :projection/doc_type "canvas-projection"
       :projection/as-of (or as-of "base")
       :projection/layer (product-layer idx product)
       :projection/blocks (count blocks)
       :projection/hypotheses (count hyps)
       :projection/source (str "gftd canvas datoms (ADR-2607021600); "
                               "SSoT = portfolio BMC datoms + canvas-ledger")
       :source/dataset "canvas-projection"}]
     (concat
      (map-indexed
       (fn [i {:keys [canvas/id canvas/block canvas/label canvas/items canvas/note]}]
         (cond-> {:db/id (- (+ i 2))
                  :canvas/id id
                  :canvas/product product
                  :canvas/block block
                  :canvas/label label
                  ;; `block-order`'s index, carried as data: a consumer that
                  ;; renders the 9 blocks as a grid needs the canonical order,
                  ;; and re-deriving it from a hardcoded list on the other side
                  ;; is how the two orders drift apart.
                  :canvas/order i
                  :canvas/items (vec items)
                  :source/dataset "canvas-projection"}
           note (assoc :canvas/note note)))
       blocks)
      (map-indexed
       (fn [i {:keys [hyp/id hyp/claim hyp/gate hyp/risk hyp/status hyp/evidence]}]
         (let [g (get gates id)]
           (cond-> {:db/id (- (+ i 2 (count blocks)))
                    :hyp/id id
                    :hyp/product product
                    :hyp/claim claim
                    :hyp/gate gate
                    :hyp/risk risk
                    :hyp/status status
                    :source/dataset "canvas-projection"}
             evidence (assoc :hyp/evidence evidence)
             g (assoc :gate/status (:status g))
             (:distance g) (assoc :gate/distance (:distance g))
             (:evidence g) (assoc :gate/evidence (:evidence g))
             (seq (:needs g)) (assoc :gate/needs (vec (:needs g))))))
       hyps)))))

(defn render-text
  "Compact terminal rendering of one product's canvas."
  [idx product]
  (str "== " (name product) "  [" (get layer-labels (product-layer idx product)) "]\n"
       (apply str
              (for [{:keys [canvas/id canvas/label canvas/items canvas/note]} (product-blocks idx product)]
                (str "-- " label "  (" id ")\n"
                     (apply str (for [i items] (str "   * " i "\n")))
                     (when note (str "   note: " note "\n")))))
       (apply str
              (for [{:keys [hyp/id hyp/status hyp/claim hyp/gate]} (product-hyps idx product)]
                (str "-- hyp " id " [" (name status) "] " claim "\n   gate: " gate "\n")))))

;; ---- io (clj/bb only) ----------------------------------------------------------

#?(:clj
   (defn load-index
     "Read base datoms file + ledger events (seq) → folded index."
     [base-path events]
     (-> (parse-datoms (slurp base-path))
         index
         (fold events)))
   :cljs
   (defn load-index
     "Read base datoms file + ledger events (seq) → folded index."
     [base-path events]
     (-> (parse-datoms (nc/slurp base-path))
         index
         (fold events))))

(ns sanction-sim
  "Multi-jurisdiction sanction survivability, as a real XMILE 1.0 model.

  Seven capability layers, each a stock in [0,1], shocked at t=6 months by a
  designation from one jurisdiction and then recovered only as far as the
  alternates that are ACTUALLY LANDED TODAY allow. The layers are in SERIES —
  operating capability is their MIN, not their mean — because a business that
  cannot be paid does not care that its storage is fine.

  Exposure and restorable values are JUDGEMENTS ANCHORED TO MEASURED COUNTS.
  Every one carries a :basis of :measured or :judged so the two are never
  read as the same kind of number."
  (:require [xmile.model :as m]
            [xmile.execute :as ex]
            [xmile.validate :as v]
            [xmile.xml :as xml]
            [dynamics.core :as dyn]
            [clojure.string :as str]
            ["fs" :as fs]))

;; ── the layers ────────────────────────────────────────────────────────────

(def layers [:names :compute :storage :code :money :hardware :legal])

(def layer-label
  {:names "名前 (DNS/naming)" :compute "実行" :storage "保管" :code "ソース"
   :money "決済" :hardware "機材の更新" :legal "法人格・銀行"})

;; What each jurisdiction can remove, per layer, 0..1.
(def exposure
  {:US {:names 1.00 :compute 0.90 :storage 0.95 :code 1.00 :money 0.85 :hardware 0.60 :legal 0.10}
   :CN {:names 0.00 :compute 0.00 :storage 0.00 :code 0.00 :money 0.05 :hardware 0.85 :legal 0.00}
   :JP {:names 0.05 :compute 0.20 :storage 0.10 :code 0.00 :money 0.90 :hardware 0.10 :legal 1.00}
   :RU {:names 0.00 :compute 0.00 :storage 0.00 :code 0.00 :money 0.00 :hardware 0.05 :legal 0.00}
   :EU {:names 0.00 :compute 0.05 :storage 0.05 :code 0.00 :money 0.15 :hardware 0.10 :legal 0.05}})

(def exposure-basis
  "Why each column is what it is, and whether it is measured or judged."
  {:US {:basis :measured
        :note "6/6 ドメインが US の DNS 事業者(Cloudflare 5 / Google 1)、TLD も全て US レジストリ。wrangler config 318 本。west 4,227 project が GitHub。DataLad 実体が B2、r2 binding 35。x402 facilitator が CF Worker、pay が USDC(Circle)/Base(Coinbase)"}
   :CN {:basis :judged
        :note "実行中の Mac mini は止まらない。効くのは*更新*(組立・部材・レアアース輸出管理)。fleet 10 台の replacement/expansion が対象で、現行運転ではない"}
   :JP {:basis :judged
        :note "本国。法人登記・税・全銀の口座が対象で、技術的な代替が存在しない。fleet の物理的所在地でもある。names は現状 .jp を持たないので低い —— S0c が着地すると上がる"}
   :RU {:basis :measured
        :note "ロシア由来の依存を repo 横断で探して 0 件。効くのは間接(部材)のみ"}
   :EU {:basis :judged
        :note "現状ほぼ無い。ただし ADR-2608220100 が薦めた DNS fallback (deSEC=独) と、ユーロ建て清算を採ると上がる —— 分散のつもりが EU に寄る"}})

;; How much of the lost capability today's LANDED alternates can restore.
(def restorable
  {:names    {:v 0.35 :basis :measured :note "IPNS の publish→自前 router 解決は実測済み(ADR-2608220200)。ただし常時ミラーは未通電、ccTLD 未取得"}
   :compute  {:v 0.70 :basis :measured :note "Kotoba→WASM component は Workers/aiueos/Linux/browser で同一 artifact。fleet 10 台は稼働中"}
   :storage  {:v 0.60 :basis :judged   :note "kotobase-storage の契約と CARv2 pack の自己検証は在る。B2/R2 以外で本番稼働した実績はゼロ"}
   :code     {:v 0.95 :basis :measured :note "git は元から分散。full clone が手元にある"}
   :money    {:v 0.25 :basis :judged   :note "XMR は受取のみ。ENGI(発行体の無い相互信用)は未 live。USDC は Circle が凍結できるので数えない"}
   :hardware {:v 0.20 :basis :judged   :note "代替供給の当てが無い。既存 10 台の寿命がそのまま滑走路"}
   :legal    {:v 0.00 :basis :judged   :note "本国からの指定に技術的復旧は存在しない。再ドミサイルは可能だが本モデルの窓では効かない"}})

(def restorable-after
  "S0a/S0b/S0c/S2/S4 と hw second-source が着地した後の値。**legal は動かない** ——
  技術的な作業は本国の法人格に一切触れない。これがこのモデルの中心的な発見。"
  {:names 0.85 :compute 0.85 :storage 0.90 :code 0.98 :money 0.70 :hardware 0.45 :legal 0.00})

(def ^:dynamic *scenario* :today)

(defn restorable-v [l]
  (if (= *scenario* :after) (get restorable-after l) (:v (restorable l))))

(def recovery-rate
  "月あたりの復旧速度。速いものほど pre-position 済み。"
  {:names 0.55 :compute 0.80 :storage 0.35 :code 0.90 :money 0.20 :hardware 0.06 :legal 0.0})

(def recovery-lag {:names 1.0 :compute 0.5 :storage 2.0 :code 0.5 :money 3.0 :hardware 6.0 :legal 0.0})

(def ^:const t-shock 6.0)
(def ^:const loss-rate 2.5)

;; ── model construction ────────────────────────────────────────────────────

(defn nm [prefix l] (str prefix "_" (name l)))

(defn layer-vars
  "Three stocks per layer, because loss and recovery must not fight over the
  same accumulator. An earlier single-stock version reached a tug-of-war
  equilibrium (loss_rate*c = recov_rate*(ceiling-c)) whose value looked like a
  result but meant nothing the variable names claimed.

    native_L    the share this jurisdiction cannot touch. Never flows.
    exposed_L   the share it can. Drains to zero after the designation.
    alt_L       what pre-positioned alternates rebuild, capped at what is
                LANDED today (restorable), filling only after the lag.

  cap_L = native + exposed + alt, so the floor and the ceiling are exactly the
  numbers in the tables above."
  [j l]
  (let [expo (get-in exposure [j l])
        native (- 1.0 expo)
        alt-cap (* expo (restorable-v l))
        rr (recovery-rate l)
        lag (recovery-lag l)]
    (cond-> [[(nm "native" l) (m/aux (nm "native" l) (str native))]
             [(nm "exposed" l)
              (m/stock (nm "exposed" l) (str expo)
                       {:xmile/inflows #{} :xmile/outflows #{(nm "loss" l)}})]
             [(nm "loss" l)
              (m/flow (nm "loss" l)
                      (str "IF TIME >= " t-shock " THEN " loss-rate " * " (nm "exposed" l)
                           " ELSE 0"))]
             [(nm "alt" l)
              (m/stock (nm "alt" l) "0"
                       {:xmile/inflows #{(nm "recov" l)} :xmile/outflows #{}})]
             [(nm "recov" l)
              (m/flow (nm "recov" l)
                      (if (or (zero? rr) (zero? alt-cap))
                        "0"
                        (str "IF TIME >= " (+ t-shock lag) " THEN " rr
                             " * MAX(0, " alt-cap " - " (nm "alt" l) ") ELSE 0")))]
             [(nm "cap" l)
              (m/aux (nm "cap" l)
                     (str (nm "native" l) " + " (nm "exposed" l) " + " (nm "alt" l)))]]
      true identity)))

(defn min-expr [names]
  (reduce (fn [a b] (str "MIN(" a ", " b ")")) names))

(defn build [j]
  (let [vars (into {} (mapcat #(layer-vars j %) layers))
        caps (mapv #(nm "cap" %) layers)]
    (-> (m/model (str "sanction-survivability-" (name j))
                 {:xmile/variables
                  (assoc vars
                         "operating_capability"
                         (m/aux "operating_capability" (min-expr caps))
                         "mean_capability"
                         (m/aux "mean_capability"
                                (str "(" (str/join " + " caps) ") / " (count caps))))})
        (m/set-sim-specs (m/sim-specs 0 36 {:xmile/dt 0.25 :xmile/method :euler})))))

;; ── scoring ───────────────────────────────────────────────────────────────

(defn score [j]
  (let [model (build j)
        probs (v/validate model)]
    (when-not (v/valid? probs)
      (throw (ex-info (str "model invalid for " j) {:errors (v/errors probs)})))
    (let [{:keys [xmile/times xmile/series]} (ex/run model)
          op (get series "operating_capability")
          final (last op)
          trough (apply min op)
          t-trough (nth times (.indexOf (to-array op) trough) nil)
          finals (into {} (for [l layers] [l (last (get series (nm "cap" l)))]))
          binding-layer (key (apply min-key val finals))]
      {:jurisdiction j
       :survivability (js/Math.round (* 100 final))
       :trough (js/Math.round (* 100 trough))
       :t-trough t-trough
       :binding-layer binding-layer
       :layer-finals (into {} (for [[l v] finals] [l (js/Math.round (* 100 v))]))
       :basis (get-in exposure-basis [j :basis])})))

;; ── interventions, scored on Meadows bands ────────────────────────────────

(def interventions
  [{:id :S0a-own-router   :band :band/D :tractability 0.80 :note "自前 delegated router — socket まで landed"}
   {:id :S0b-own-gateway  :band :band/D :tractability 0.75 :note "自前 IPFS gateway — 実装は pure .cljc で既存"}
   {:id :S0c-cctld        :band :band/E :tractability 0.40 :note "ccTLD 二重委任 — 外部調達が要る"}
   {:id :S0d-kubo-free    :band :band/C :tractability 0.55 :note "Kubo 非依存ノード — handshake 実証済み、残りは Yamux と kad"}
   {:id :S0e-reachability :band :band/E :tractability 0.20 :note "CPE 到達性 — 外部ゲート、blocked"}
   {:id :S2-inga-refs     :band :band/C :tractability 0.60 :note "inga を live ref plane に。ベンダ primitive が経路から消える"}
   {:id :S4-engi-money    :band :band/A :tractability 0.30 :note "ENGI 相互信用 — 発行体が無い＝凍結を実行できる主体が存在しない"}
   {:id :redomicile       :band :band/A :tractability 0.15 :note "本国リスクに効く唯一の手。技術ではない"}
   {:id :hw-second-source :band :band/D :tractability 0.25 :note "非 Apple / 非中国組立の機材を fleet に混ぜる"}])

;; ── main ──────────────────────────────────────────────────────────────────

(defn -main [& _]
  (println "=== 管轄別 生存性スコア（XMILE 実行、36 か月、t=6 で指定） ===\n")
  (println (str (.padEnd "管轄" 6) (.padStart "生存性" 8) (.padStart "谷" 6)
                (.padStart "谷の時刻" 10) "  拘束層"))
  (let [rows (mapv score [:US :CN :JP :RU :EU])
        after (binding [*scenario* :after] (mapv score [:US :CN :JP :RU :EU]))]
    (doseq [r rows]
      (println (str (.padEnd (name (:jurisdiction r)) 6)
                    (.padStart (str (:survivability r)) 8)
                    (.padStart (str (:trough r)) 6)
                    (.padStart (str (:t-trough r)) 10)
                    "  " (name (:binding-layer r))
                    " (" (get-in r [:layer-finals (:binding-layer r)]) ")")))
    (println "\n=== ロードマップ着地後（S0a/S0b/S0c/S2/S4 + hw second-source） ===")
    (println (str (.padEnd "管轄" 6) (.padStart "今日" 8) (.padStart "着地後" 10)
                  (.padStart "差" 6) "  着地後の拘束層"))
    (doseq [[a b] (map vector rows after)]
      (println (str (.padEnd (name (:jurisdiction a)) 6)
                    (.padStart (str (:survivability a)) 8)
                    (.padStart (str (:survivability b)) 10)
                    (.padStart (str (- (:survivability b) (:survivability a))) 6)
                    "  " (name (:binding-layer b)))))
    (println "\n=== 層ごとの最終値 (%) ===")
    (println (str (.padEnd "層" 18) (str/join "" (map #(.padStart (name %) 6) [:US :CN :JP :RU :EU]))))
    (doseq [l layers]
      (println (str (.padEnd (layer-label l) 16)
                    (str/join "" (map #(.padStart (str (get-in (first (filter (fn [r] (= % (:jurisdiction r))) rows))
                                                               [:layer-finals l])) 6)
                                      [:US :CN :JP :RU :EU])))))
    (println "\n=== 介入の leverage 順位（Meadows band × tractability） ===")
    (doseq [i (dyn/rank-interventions interventions)]
      (println (str (.padStart (str (:base-score i)) 5) "  "
                    (.padEnd (name (:id i)) 18)
                    (get-in dyn/meadows-bands [(:band i) :label]))))
    ;; XMILE 1.0 XML を実際に書き出す
    (doseq [j [:US :CN :JP :RU :EU]]
      (let [outdir (or (first *command-line-args*) ".")
            f (str outdir "/sanction-" (name j) ".xmile")]
        (let [mdl (build j)
              doc {:xmile/header {:xmile/vendor "kotoba-lang/org-oasis-open-xmile"
                                  :xmile/product "sanction-survivability"
                                  :xmile/name (str "sanction-survivability-" (name j))}
                   :xmile/sim-specs (:xmile/sim-specs mdl)
                   :xmile/models [mdl]}]
          (fs/writeFileSync f (xml/emit-xml-string (xml/emit-doc doc))))
        (println (str "\nXMILE 出力: " f " (" (.-size (fs/statSync f)) " bytes)"))))))

(apply -main *command-line-args*)

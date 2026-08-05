#!/usr/bin/env nbb
;; analyze-ci-plane-dynamics.cljs — CI プレーンの選択を system dynamics で採点する。
;;
;; 問い（オーナー、2026-08-05）「どちらが適切か、美しいかを分析, system dynamics
;; xmile でスコア分析」。対象は 2 つの候補:
;;
;;   A. scripts/fleet-ci  — tick.cljs + gates.edn（+ kagami が署名層）
;;   B. murakumo.cloud/actions — .murakumo/actions.edn + 中央 control plane
;;
;; **kagami は 3 つ目のプレーンではない。** ADR-2607300900 は「kagami と tick.cljs の
;; どちらかが単一プレーンにならないと matrix は飾り」と書いているが、実際には
;; tick.cljs が `fleet ci-verify --gate 'name=cmd'` で kagami を呼んでおり
;; （bin/fleet.cljs:696、tick.cljs の args 構築）、kagami は呼び手から gate を
;; 受け取る**署名層**で独自の matrix を持たない。競合ではなく合成である。
;;
;; 計算は kotoba-lang/dynamics（Meadows leverage-point / loop-structural-strength）と
;; kotoba-lang/org-oasis-open-xmile（OASIS XMILE 1.0）に委譲する。CLAUDE.md
;; ADR-2607203000 の repo-wide 規則: ゼロから再発明しない。
;;
;;   nbb --classpath "orgs/kotoba-lang/dynamics/src:orgs/kotoba-lang/org-oasis-open-xmile/src" \
;;       scripts/analyze-ci-plane-dynamics.cljs
(ns analyze-ci-plane-dynamics
  (:require [clojure.string :as str]
            [dynamics.core :as d]
            [xmile.model :as xm]
            [xmile.execute :as xe]
            [xmile.xml :as xx]))

(def fs (js/require "fs"))

;; ---------------------------------------------------------------------------
;; 入力。**実測と推定を分けて持つ。** ADR-2607203000: 捏造した数値を測定値として
;; 提示しない。:measured には取得方法を、:estimate? true には根拠を書く。

(def evidence
  {:fleet-ci
   {:tick-interval-min {:v 5 :measured "LaunchAgent com.gftd.fleet-ci-tip-tick (loaded, PID 確認)"}
    :gates {:v 23 :measured "gates.edn :repos の件数"}
    :receipts {:v 230 :measured "manifest/fleet-ci.edn の署名済み receipt 行数"}
    :historical-passes {:v 2957 :measured "~/.gftd/fleet-ci-tick.log の 'pass test-' 出現数"}
    :commit-statuses {:v 0 :measured "root と net-kotobase の直近 5 commit を GitHub API で確認、contexts 空"}
    :enforced? {:v false :measured "required status checks 未設定（status が無いので設定すると全 merge が固まる）"}}
   :murakumo-actions
   {:root-endpoint {:v 200 :measured "GET https://murakumo.cloud/api/actions"}
    :subroutes {:v 0 :measured "runs/receipts/status/leases/list/health/repos の 7 経路すべて 404 'actions route not found'"}
    :manifests {:v 1 :measured "find orgs -name actions.edn -path '*/.murakumo/*' → net-kotobase のみ"}
    :observed-runs {:v 0 :measured "commit status 0 件 + サブ経路 404。実行の観測可能な痕跡なし"}
    :declares-enforcement? {:v true :measured ".murakumo/actions.edn :required-statuses 4 本"}}})

;; ---------------------------------------------------------------------------
;; ループ強度。dynamics/loop-structural-strength に渡す。
;;
;; **cycle-time-days が数値でないと関数は nil を返す**（推測した cycle time から
;; 出した強度は fiction、というライブラリ側の設計）。B はまさにその状態で、
;; これは「弱い」ではなく **「測れない」** —— 区別が本質。

(def loops
  {:fleet-ci
   {:label "A. scripts/fleet-ci (tick.cljs + gates.edn, kagami 署名)"
    :cycle-time-days (/ 5.0 1440)        ;; 5 分 tick（実測）
    :self-funding-coefficient 0.15
    :self-funding-basis {:estimate? true
                         :why "green の見返りは west pin 前進のみ。検証容量そのものは増えない。低い側に置いた"}
    :instrumentation-completeness 0.60
    :instrumentation-basis {:measured "gate 結果は署名 receipt に 230 件、batch 所要時間も記録。ただし commit status は 0 件で、**強制チャネルが未計装**。2 チャネルのうち 1 本が欠けているので 0.6"}
    :friction 0.10
    :friction-basis {:measured "対象追加は gates.edn に 1 行（実測: 今回 legislation gate と gh-workflow gate を各 1 行で追加）"}}

   :murakumo-actions
   {:label "B. murakumo.cloud/actions (.murakumo/actions.edn + 中央 control plane)"
    :cycle-time-days nil                 ;; 一度も発火していない → 測れない
    :cycle-time-basis {:measured "サブ経路 7/7 が 404、commit status 0 件。ループが回った観測記録が無い"}
    :self-funding-coefficient 0.15
    :self-funding-basis {:estimate? true :why "A と同条件で置く（設計が同種のため）"}
    :instrumentation-completeness 0.0
    :instrumentation-basis {:measured "観測可能な run・receipt・status がゼロ"}
    :friction 0.60
    :friction-basis {:estimate? true
                     :why "対象追加に repo ごとの .murakumo/actions.edn と、control plane 側の action 実装・allowlist 登録が要る（宣言だけでは実行権限を得られないと manifest 自身が書いている）"}}})

;; ---------------------------------------------------------------------------
;; XMILE モデル。**「検証された commit」ではなく「信頼」を stock に置く**のが要点。
;;
;; CI の目的は commit を検査することではなく、**検査結果を根拠に意思決定できる状態**
;; を保つこと。GitHub Actions が「走っていないのに green」で壊したのはまさにこの
;; stock で、件数ではなかった。したがって stock は trust、流入は「信じられた検証」、
;; 流出は「無視された赤」と「書かれなかった status」。
;;
;; enforcement-gain は「status が書かれ、branch protection が required にしている」
;; ときだけ 1 になる係数。A も B も今は 0 —— **どちらのプレーンを選んでも、
;; ここが 0 のままなら trust は増えない**というのがモデルの中心的な帰結。

(defn trust-model
  [{:keys [nm verify-rate signal-fidelity enforcement-gain red-ignored-rate]}]
  (-> (xm/model nm)
      (xm/add-variable (xm/stock "Stock" "0"
                                 {:xmile/inflows #{"trust_gain"}
                                  :xmile/outflows #{"trust_decay"}}))
      (xm/add-variable (xm/aux "verify_rate" (str verify-rate)))
      (xm/add-variable (xm/aux "signal_fidelity" (str signal-fidelity)))
      (xm/add-variable (xm/aux "enforcement_gain" (str enforcement-gain)))
      (xm/add-variable (xm/aux "red_ignored" (str red-ignored-rate)))
      ;; 信頼は「検証が回り、信号が正しく、しかもそれが強制に効いている」ときだけ増える。
      ;; 3 つの積にしてあるのは、どれか 1 つが 0 なら残りが何であれ 0 になるから。
      (xm/add-variable (xm/flow "trust_gain"
                                "verify_rate * signal_fidelity * enforcement_gain"))
      ;; 無視された赤は信頼を削る。ここが「永久に赤い gate は signal を運ばない」の
      ;; 定量表現（ADR-2607300400）。
      (xm/add-variable (xm/flow "trust_decay" "red_ignored * Stock"))
      (assoc :xmile/sim-specs (xm/sim-specs 0 90 {:xmile/dt 1 :xmile/time-units "day"}))))

;; xmile.execute/run は **model を直接**取る（doc ではない。:xmile/sim-specs は
;; model 側に載せる）。doc を渡すと assert は通っても series が空で返り、
;; 「0 のまま増えなかった」と読めてしまう —— 実際は実行されていない。
(defn run-trust [model]
  (let [r (xe/run model)
        series (get-in r [:xmile/series "Stock"])]
    (when (empty? series)
      (throw (js/Error. (str "no series for Stock in " (:xmile/name model)
                             " — refusing to report a projection that did not run"))))
    {:t0 (first series) :t90 (last series) :points (count series)}))

;; ---------------------------------------------------------------------------
;; 介入の leverage 採点（Meadows）

(def interventions
  [{:id :write-commit-statuses
    :label "PAT を発行し commit status を書けるようにする"
    :band :band/B :tractability 0.5
    :why "情報フローの構造そのもの（Meadows 6）。検証結果が意思決定点に届いていない状態を解消する。tractability 0.5 は人間の操作（PAT 発行）が要るため"}
   {:id :fix-chronic-red
    :label "慢性的に赤い gate を直す"
    :band :band/C :tractability 1.0
    :why "ループのゲイン（Meadows 8）。赤が常態だと信号が無視され trust_decay が効き続ける。実施済み（3 本とも原因特定・修正）"}
   {:id :require-status-checks
    :label "branch protection の required checks を fleet の context にする"
    :band :band/B :tractability 0.2
    :why "ルール（Meadows 5）。ただし status が書かれるまで実施すると全 merge が固まるので、上の前提が満たされるまで tractability は低い"}
   {:id :adopt-murakumo-actions
    :label "murakumo.cloud/actions を正のプレーンにする"
    :band :band/A :tractability 0.1
    :why "パラダイム/目標（Meadows 2-4）: 中央 control plane が lease を発行し GitHub は status の射影に徹する、という設計。理論上いちばん高い band だが、実装が観測できない（サブ経路 7/7 が 404）ので tractability は最低"}
   {:id :register-more-gates
    :label "gates.edn に対象 repo を足す"
    :band :band/E :tractability 0.95
    :why "パラメータ（Meadows 12）。安くて即効だが、構造を変えないので上限が低い"}
   {:id :unify-planes
    :label "2 プレーンを 1 本に畳む"
    :band :band/B :tractability 0.3
    :why "ルール/情報構造。ただし今日の実測では kagami は tick.cljs の署名層であって競合ではなく、畳む対象は A と B の 2 つ"}])

;; ---------------------------------------------------------------------------

(defn- xml-str
  "emit-doc の {:tag :attrs :content} を XML 文字列に。org-oasis-open-xmile は
   host の XML 実装に依存しない設計（README）なので、直列化は呼び手の仕事。"
  [{:keys [tag attrs content]}]
  (let [esc #(-> (str %) (str/replace "&" "&amp;") (str/replace "<" "&lt;")
                 (str/replace ">" "&gt;") (str/replace "\"" "&quot;"))
        a (str/join (for [[k v] attrs] (str " " (name k) "=\"" (esc v) "\"")))]
    (if (seq content)
      (str "<" (name tag) a ">"
           (str/join (map #(if (map? %) (xml-str %) (esc %)) content))
           "</" (name tag) ">")
      (str "<" (name tag) a "/>"))))

(defn -main []
  (let [strengths (into {}
                        (map (fn [[k m]]
                               [k (assoc (select-keys m [:label :cycle-time-days])
                                         :strength (d/loop-structural-strength m))]))
                        loops)
        ;; A: verify は回る(1)・信号は 3/3 gate 修正後に正しい(1.0)・強制は 0
        ;; B: verify が回らない(0)
        models {:fleet-ci-today
                (trust-model {:nm "fleet-ci today" :verify-rate 1.0 :signal-fidelity 1.0
                              :enforcement-gain 0.0 :red-ignored-rate 0.02})
                :fleet-ci-with-status
                (trust-model {:nm "fleet-ci + statuses" :verify-rate 1.0 :signal-fidelity 1.0
                              :enforcement-gain 1.0 :red-ignored-rate 0.02})
                :murakumo-actions-today
                (trust-model {:nm "murakumo actions today" :verify-rate 0.0 :signal-fidelity 0.0
                              :enforcement-gain 0.0 :red-ignored-rate 0.02})}
        runs (into {} (map (fn [[k m]] [k (run-trust m)])) models)
        ranked (d/rank-interventions (map d/leverage-score interventions))
        doc {:xmile/header {:xmile/name "ci-plane-choice"
                            :xmile/vendor "com-junkawasaki/root"
                            :xmile/product "analyze-ci-plane-dynamics.cljs"}
             :xmile/sim-specs (:xmile/sim-specs (:fleet-ci-today models))
             :xmile/models (vec (vals models))}
        out {:analysis/as-of "2026-08-05"
             :analysis/question "どちらの CI プレーンが適切か（system dynamics + XMILE 採点）"
             :analysis/evidence evidence
             :analysis/loop-strength strengths
             :analysis/trust-projection runs
             :analysis/ranked-interventions (vec ranked)
             :analysis/note
             (str "loop-structural-strength は cycle-time-days が数値でないと nil を返す"
                  "（dynamics の設計: 一度も発火していないループの強度を推測値から出すのは fiction）。"
                  "B の nil は「弱い」ではなく「測れない」。")}]
    (fs.writeFileSync "90-docs/ci-plane-dynamics.edn" (str (pr-str out) "\n"))
    (fs.writeFileSync "90-docs/ci-plane-dynamics.xmile"
                      (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                           (xml-str (xx/emit-doc doc)) "\n"))
    (println "=== loop structural strength (dynamics/loop-structural-strength) ===")
    (doseq [[k {:keys [label strength cycle-time-days]}] strengths]
      (println (str "  " (name k) "  strength=" (if strength (.toFixed strength 1) "nil（測定不能）")
                    "  cycle-time-days=" (or cycle-time-days "—")))
      (println (str "     " label)))
    (println)
    (println "=== trust stock projection (XMILE, 90 days) ===")
    (doseq [[k {:keys [t0 t90]}] runs]
      (println (str "  " (name k) ": t0=" t0 " → t90=" t90)))
    (println)
    (println "=== Meadows leverage ranking ===")
    (doseq [{:keys [label band tractability base-score]} ranked]
      (println (str "  " (.toFixed base-score 2) "  [" (name band) " ×" tractability "]  " label)))
    (println)
    (println "wrote 90-docs/ci-plane-dynamics.edn + .xmile")))

(-main)

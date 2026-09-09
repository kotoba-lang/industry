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
    :historical-passes {:v 2957 :measured "~/.itonami/fleet-ci-tick.log の 'pass test-' 出現数"}
    :commit-statuses {:v 0 :measured "root と net-kotobase の直近 5 commit を GitHub API で確認、contexts 空"}
    :enforced? {:v false :measured "required status checks 未設定（status が無いので設定すると全 merge が固まる）"}}
   :murakumo-actions
   ;; ⚠ 2026-08-05 に**この節の初版を全面的に訂正した**。初版は「サブ経路 7/7 が 404、
   ;; 実行の痕跡なし」と書いたが、**叩いた 7 経路のうち 6 つは存在しない経路を私が
   ;; 創作したもの**で、runs は POST 専用だった。実 API は
   ;; GET /api/actions, POST /api/actions/github, POST /api/actions/runs,
   ;; GET /api/actions/runs/:id, POST /api/actions/jobs/claim,
   ;; POST /api/actions/jobs/:id/{complete,approve} の 7 本
   ;; （cloud_murakumo/actions_http.cljs:285）。存在しない経路の 404 を
   ;; 「実装されていない証拠」として採点に入れていた。
   {:root-endpoint {:v 200 :measured "GET https://murakumo.cloud/api/actions → 200。ACTIONS_DB 未設定なら 503 が先に返る実装なので、**D1 は bound**"}
    :webhook {:v :delivering :measured "net-kotobase の hook 661126468 が https://murakumo.cloud/api/actions/github へ push/pull_request を配送、直近 6 件すべて 202 OK"}
    :queued-since {:v "2026-08-04T10:01:45Z" :measured "claim で返った最古 job の created-at。**job は 1 日以上溜まっていた**"}
    :runner-existed? {:v true :measured "scripts/actions-runner.mjs（153 行、action allowlist つき）が repo main に存在"}
    :runner-was-running? {:v false :measured "どのノードにも常駐が無く、queue が消化されていなかった"}
    :loop-closed? {:v true :measured "2026-08-05 に手で 1 回実行 → hermetic job を claim → sha で clone → 実テスト（py_compile / unittest 2 tests / metadata checks）→ complete まで通過"}
    :github-status-token {:v :unset :measured "wrangler secret list --name murakumo-cloud に MURAKUMO_GITHUB_STATUS_TOKEN が無い。github-status! は token 未設定なら no-op なので、**status が 0 件だった理由はこれ**"}
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
    ;; runner を常駐させた 2026-08-05 以降の cycle time。poll 30 秒。
    ;; 初版は nil（測定不能）としたが、それは私が存在しない経路を叩いた誤測定に
    ;; 基づいていた。実際には webhook が 202 で配送し job が queue に積まれており、
    ;; 欠けていたのは claim するプロセスだけだった。
    :cycle-time-days (/ 30.0 86400)
    :cycle-time-basis {:measured "MURAKUMO_ACTIONS_POLL_MS=30000 の常駐 runner（com.gftd.murakumo-actions-runner）。手動 1 回で claim→clone→test→complete を通過済み"}
    :self-funding-coefficient 0.15
    :self-funding-basis {:estimate? true :why "A と同条件で置く（設計が同種のため）"}
    ;; run/job の状態は D1 に、log は content-addressed（log-cid）で記録される。
    ;; status だけが MURAKUMO_GITHUB_STATUS_TOKEN 未設定で欠けている → 2/3。
    :instrumentation-completeness 0.67
    :instrumentation-basis {:measured "run/job 状態は D1、log は sha256 の log-cid で complete 時に送る。commit status のみ token 未設定で no-op"}
    :friction 0.60
    :friction-basis {:estimate? true
                     :why "対象追加に repo ごとの .murakumo/actions.edn と、runner 側 actionCatalog へのコマンド登録が要る（宣言だけでは実行権限を得られない設計。A の gates.edn 1 行より重い）"}}})

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
    :label "MURAKUMO_GITHUB_STATUS_TOKEN を Worker secret に設定する"
    :band :band/B :tractability 0.5
    :why "情報フローの構造そのもの（Meadows 6）。**ローカル PAT ファイルではなく Worker secret 1 本**（B は github-status! を自前で持っており、token が無いときだけ no-op する）。tractability 0.5 は GitHub token の発行に人間の操作が要るため"}
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
    :band :band/A :tractability 0.8
    :why "パラダイム/目標（Meadows 2-4）: 中央 control plane が lease を発行し GitHub は status の射影に徹する設計。**tractability は 0.1 ではなく 0.8** — 初版は実装が無いと誤判定していたが、control plane も runner も allowlist も既にあり、webhook は配送済みで、欠けていたのは runner の常駐だけだった（2026-08-05 に導入して claim→実行→complete を通過）"}
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
                (trust-model {:nm "murakumo actions today" :verify-rate 1.0 :signal-fidelity 1.0
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

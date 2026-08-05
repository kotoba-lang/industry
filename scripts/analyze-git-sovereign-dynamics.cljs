#!/usr/bin/env nbb
;; analyze-git-sovereign-dynamics.cljs — git.kotobase.net を正本にする経路の
;; 選択を system dynamics + XMILE で採点する。
;;
;; 問い（オーナー、2026-08-05）「おすすめは? system dynamics xmile で計算,
;; d1 正本にしないで」。後半は制約であって選択肢ではない —— ADR-2608039000
;; （accepted, repo-wide）が既に「分散を名乗る経路で、消したら正しさが壊れる
;; ものを D1 に置くな。ref 面は inga」と定めており、オーナー指示はそれを
;; この経路に適用したもの。したがって D1 案は**採点せず除外**する。
;;
;;   nbb --classpath "orgs/kotoba-lang/dynamics/src:orgs/kotoba-lang/org-oasis-open-xmile/src" \
;;       scripts/analyze-git-sovereign-dynamics.cljs
(ns analyze-git-sovereign-dynamics
  (:require [clojure.string :as str]
            [dynamics.core :as d]
            [xmile.model :as xm]
            [xmile.execute :as xe]
            [xmile.xml :as xx]))

(def fs (js/require "fs"))

;; ---------------------------------------------------------------------------
;; 実測（2026-08-05）。推定は :estimate? で必ず区別する。

(def evidence
  {:surface
   {:worker {:v "kotobase-git" :measured "wrangler.jsonc: custom_domain git.kotobase.net"}
    :secret {:v "ADMIN_TOKEN" :measured "wrangler secret list --name kotobase-git → ADMIN_TOKEN のみ。kagi net-kotobase/KOTOBASE_GIT_ADMIN_TOKEN に実在"}
    :storage {:v [:kv :d1 :r2] :measured "GIT_STORE(KV) + GIT_DB(D1) + R2"}
    :write-api {:v :xrpc :measured "/xrpc/kotobase.git.{object.put,ref.set,delegate.set,quorum.set}。dumb-HTTP の PUT は 405"}}
   :client
   {:helper {:v "git-remote-kotobase.mjs" :measured "capabilities → push。Ed25519 + CACAO"}
    :push-progress {:v :objects-landed
                    :measured "bearer 認可 ✅ / 全 object 投入 ✅（objects/61/0e66c… が HTTP 200, 916B で読める）/ ref 登録は kotobase.net の CACAO で 401"}}
   :ref-plane-candidate
   {:inga {:v :implemented
           :measured "orgs/kotoba-lang/inga に ref.cljc(ref-store, :conditional-ref :linearizable-ref) / head.cljc / consensus.cljc / attest.cljc。kotobase.storage.core/IRefStore を満たす"}
    :quorum-running? {:v :unknown
                      :measured "witness-quorum repo は CHARTER-RIDER.md と deps.edn のみで src が無い。**稼働中の witness を観測できていない**"}}})

;; ---------------------------------------------------------------------------
;; 選択肢
;;
;; 問い 1: ミラーの identity（今すぐ必要。push の最終段が CACAO で 401）
;; 問い 2: 正本の ref 面（D1 は除外済み）

(def loops
  {:reuse-fleet-seed
   {:label "A1. 既存の kotobase 認可済み seed を流用（例 itonami-fleet-kotobase-seed）"
    ;; 鍵は既にあり apex で通る。push は helper が回すso cycle は push 頻度。
    :cycle-time-days (/ 1.0 24)          ;; 時間単位のミラー想定
    :self-funding-coefficient 0.10
    :self-funding-basis {:estimate? true :why "ミラーが増えても検証容量は増えない"}
    :instrumentation-completeness 0.9
    :instrumentation-basis {:measured "object は read で存在確認でき、ref は datom 面に射影されるので query できる"}
    ;; friction は「導入の摩擦」ではなく **1 サイクルの相手方コスト**。
    ;; 既存鍵の流用は導入が軽い＝低摩擦。
    :friction 0.10
    :friction-basis {:measured "kagi に鍵があり apex 登録済み。追加作業ゼロ"}}

   :dedicated-did
   {:label "A2. ミラー専用 DID を新規に発行し apex に登録"
    :cycle-time-days (/ 1.0 24)
    :self-funding-coefficient 0.10
    :self-funding-basis {:estimate? true :why "同上"}
    :instrumentation-completeness 0.9
    :instrumentation-basis {:measured "同上"}
    :friction 0.35
    :friction-basis {:estimate? true
                     :why "apex 側に tenant DID を登録する作業が要る（kotobase.net の変更）。鍵自体は生成済み（kagi KOTOBASE_GIT_MIRROR_KEY）"}}})

;; ---------------------------------------------------------------------------
;; XMILE。stock は「主権」= この履歴を GitHub 無しで復元・検証できる度合い。
;;
;; 3 因子の積にする理由は前回の CI モデルと同じ: どれか 1 つが 0 なら残りが
;; 何であれ 0。ここでは
;;   mirror_rate      … ミラーが回るか（identity が通るか）
;;   ref_integrity    … ref 面が「消えても正しさが壊れない」か
;;   independence     … GitHub を落としても成立するか
;; ref_integrity を D1 で 0 にするのが今回の制約の定量表現。

(defn sovereignty-model [{:keys [nm mirror-rate ref-integrity independence decay]}]
  (-> (xm/model nm)
      (xm/add-variable (xm/stock "Stock" "0" {:xmile/inflows #{"gain"} :xmile/outflows #{"loss"}}))
      (xm/add-variable (xm/aux "mirror_rate" (str mirror-rate)))
      (xm/add-variable (xm/aux "ref_integrity" (str ref-integrity)))
      (xm/add-variable (xm/aux "independence" (str independence)))
      (xm/add-variable (xm/aux "decay_rate" (str decay)))
      (xm/add-variable (xm/flow "gain" "mirror_rate * ref_integrity * independence"))
      (xm/add-variable (xm/flow "loss" "decay_rate * Stock"))
      (assoc :xmile/sim-specs (xm/sim-specs 0 180 {:xmile/dt 1 :xmile/time-units "day"}))))

(defn run-model [m]
  (let [r (xe/run m) s (get-in r [:xmile/series "Stock"])]
    (when (empty? s) (throw (js/Error. (str "no series for " (:xmile/name m)))))
    {:t0 (first s) :t180 (last s)}))

(def interventions
  [{:id :ref-plane-to-inga
    :label "ref 面を D1 から inga（2f+1 quorum）へ移す"
    :band :band/D :tractability 0.45
    :why "stock-flow 構造そのもの（Meadows 9-10）。**正本化の前提条件**で、これ無しでは他の投資が正本にならない。tractability 0.45 — inga の ref-store は実装済みだが、**稼働中の witness quorum を観測できていない**（witness-quorum repo に src が無い）。実装ではなく運用が未知"}
   {:id :reuse-fleet-seed
    :label "A1. 既存 seed を流用してミラーを今すぐ動かす"
    :band :band/E :tractability 0.95
    :why "パラメータ（Meadows 12）。安くて即効。ただし**艦隊 seed の blast radius に git 書き込み権限を足す** — CLAUDE.md はこの seed が既に ~1,197 actor を覆うと記録しており、漏れたとき git 履歴の改竄まで及ぶ"}
   {:id :dedicated-did
    :label "A2. ミラー専用 DID を apex に登録する"
    :band :band/B :tractability 0.6
    :why "ルール/情報構造（Meadows 5-6）: 権限の境界を引き直す。侵害範囲が git ミラーに限定され、失効も独立にできる。apex 登録という一手間が要る"}
   {:id :flip-source-of-truth
    :label "正本を GitHub から git.kotobase.net へ反転する"
    :band :band/A :tractability 0.15
    :why "パラダイム（Meadows 2-4）。最高位だが、ref 面が inga に載り、witness が運用され、PR/issue/レビュー面の代替が要る。4,117 repo と west pin と fleet-ci が GitHub を指している"}
   {:id :mirror-only
    :label "ミラー止まりで運用（正本は GitHub のまま）"
    :band :band/E :tractability 1.0
    :why "パラメータ。可用性と content-addressed な履歴保全は得られるが、主権は得られない"}])

(defn- xml-str [{:keys [tag attrs content]}]
  (let [esc #(-> (str %) (str/replace "&" "&amp;") (str/replace "<" "&lt;")
                 (str/replace ">" "&gt;") (str/replace "\"" "&quot;"))
        a (str/join (for [[k v] attrs] (str " " (name k) "=\"" (esc v) "\"")))]
    (if (seq content)
      (str "<" (name tag) a ">" (str/join (map #(if (map? %) (xml-str %) (esc %)) content)) "</" (name tag) ">")
      (str "<" (name tag) a "/>"))))

(defn -main []
  (let [strengths (into {} (map (fn [[k m]]
                                  [k (assoc (select-keys m [:label])
                                            :strength (d/loop-structural-strength m))]))
                        loops)
        models {:today-d1-refs
                ;; 今日: ミラーは identity で止まり、ref 面は D1。
                (sovereignty-model {:nm "today (refs on D1)" :mirror-rate 0.0
                                    :ref-integrity 0.0 :independence 0.5 :decay 0.01})
                :mirror-working-d1-refs
                ;; identity を解決してミラーは回るが ref 面は D1 のまま。
                ;; ref_integrity 0 = 「D1 を消したら正しさが壊れる」= 正本たりえない。
                (sovereignty-model {:nm "mirror works, refs still on D1" :mirror-rate 1.0
                                    :ref-integrity 0.0 :independence 0.5 :decay 0.01})
                :mirror-working-inga-refs
                (sovereignty-model {:nm "mirror works, refs on inga" :mirror-rate 1.0
                                    :ref-integrity 1.0 :independence 0.5 :decay 0.01})
                :source-of-truth-inga
                (sovereignty-model {:nm "source of truth on inga" :mirror-rate 1.0
                                    :ref-integrity 1.0 :independence 1.0 :decay 0.01})}
        runs (into {} (map (fn [[k m]] [k (run-model m)])) models)
        ranked (d/rank-interventions (map d/leverage-score interventions))
        doc {:xmile/header {:xmile/name "git-sovereign-choice"
                            :xmile/vendor "com-junkawasaki/root"
                            :xmile/product "analyze-git-sovereign-dynamics.cljs"}
             :xmile/sim-specs (:xmile/sim-specs (:today-d1-refs models))
             :xmile/models (vec (vals models))}
        out {:analysis/as-of "2026-08-05"
             :analysis/question "git.kotobase.net 正本化: ミラー identity と ref 面の選択"
             :analysis/excluded {:option :refs-on-d1
                                 :why "オーナー指示 + ADR-2608039000（accepted, repo-wide）。採点せず除外"}
             :analysis/evidence evidence
             :analysis/loop-strength strengths
             :analysis/sovereignty-projection runs
             :analysis/ranked-interventions (vec ranked)}]
    (fs.writeFileSync "90-docs/git-sovereign-dynamics.edn" (str (pr-str out) "\n"))
    (fs.writeFileSync "90-docs/git-sovereign-dynamics.xmile"
                      (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" (xml-str (xx/emit-doc doc)) "\n"))
    (println "=== identity 案のループ強度 ===")
    (doseq [[k {:keys [label strength]}] strengths]
      (println (str "  " (.toFixed strength 1) "  " label)))
    (println)
    (println "=== 主権 stock の 180 日投影（XMILE）===")
    (doseq [[k {:keys [t180]}] runs]
      (println (str "  " (.padEnd (name k) 30) " t180=" (.toFixed t180 1))))
    (println)
    (println "=== Meadows leverage ranking ===")
    (doseq [{:keys [label band tractability base-score]} ranked]
      (println (str "  " (.toFixed base-score 2) "  [" (name band) " x" tractability "]  " label)))
    (println)
    (println "wrote 90-docs/git-sovereign-dynamics.{edn,xmile}")))

(-main)

(ns probe-mcad-healing (:require [kami.modeling.brep :as b]))
;; 不変条件: (1) 許容内の重複頂点を診断が見つけ、heal が併合する
;;          (2) 許容を締めれば見つけない（両方向。片方だけの検査器は検査器ではない）
;;          (3) 縮退エッジを作る heal は **黙って消さず、名指しで失敗する**
;;              —— 実装の docstring が自分でそう約束している
;;          (4) heal 後の body が valid-body? を通る
(try
  (let [body (b/box-body "probe/heal" 10 10 10 1.0e-6)
        dup #uuid "00000000-0000-5000-a000-000000009999"
        v0 (first (vals (:brep/vertices body)))
        near (update (:vertex/point v0) 0 + 1.0e-7)
        with-dup (assoc-in body [:brep/vertices dup] (b/vertex dup near 1.0e-6))
        found (fn [tol] (some #(= :near-duplicate-vertices (:diagnostic %))
                              (b/tolerance-diagnostics with-dup tol)))
        healed (b/heal-body with-dup 1.0e-6)
        ;; 縮退させる: エッジの端点を始点に重ねる
        e (first (vals (:brep/edges body)))
        sp (get-in body [:brep/vertices (:edge/start e) :vertex/point])
        broken (assoc-in body [:brep/vertices (:edge/end e) :vertex/point]
                         (update sp 0 + 1.0e-8))
        refused (try (b/heal-body broken 1.0e-6) :not-refused
                     (catch :default ex (.-message ex)))]
    (cond
      (not (found 1.0e-6))
      (println "PROBE mcad-healing FAIL" "許容 1e-6 で 1e-7 離れた重複を見つけない")
      (found 1.0e-9)
      (println "PROBE mcad-healing FAIL"
               "許容 1e-9 でも重複と報告する —— 許容が効いておらず両方向を出さない")
      (not= 8 (count (:brep/vertices healed)))
      (println "PROBE mcad-healing FAIL"
               (str "heal 後の頂点が " (count (:brep/vertices healed)) "（9 → 8 を期待）"))
      (not= 1 (get-in healed [:brep/healing :merged]))
      (println "PROBE mcad-healing FAIL" "併合件数を報告しない")
      (not (b/valid-body? healed))
      (println "PROBE mcad-healing FAIL" "heal 後の body が valid-body? を通らない")
      (= :not-refused refused)
      (println "PROBE mcad-healing FAIL"
               "縮退エッジができる heal が成功として返る —— 黙ってエッジを消している")
      (not (re-find #"degenerate" (str refused)))
      (println "PROBE mcad-healing FAIL" (str "拒否はするが理由を名指ししない: " refused))
      :else (println "PROBE mcad-healing PASS"
                     (str "1e-6 で発見・1e-9 で不発見 / heal 9→8 併合 1 件 / "
                          "縮退は名指しで拒否"))))
  (catch :default ex (println "PROBE mcad-healing UNMEASURABLE" (.-message ex))))

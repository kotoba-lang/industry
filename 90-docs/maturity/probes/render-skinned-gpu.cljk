(ns probe-render-skinned-gpu (:require [kami.webgpu.mesh :as mesh]))
;; ⚠ **この probe が測るのは描画ではなく、その手前の簿記**である。実 GPU が要る部分
;; （joint palette のアップロード、bind group、draw）は Node に navigator.gpu が無いので
;; ここでは測れない。測れるのは CPU 側のキャッシュ台帳と、その 3 つの拒否:
;;   (1) 破棄済みキャッシュの再利用を拒む
;;   (2) 別デバイスでの再利用を拒む（GPU リソースの取り違えは静かに壊れる）
;;   (3) `:entity-id` 由来を持たない draw を拒む（誰の palette か決められない）
;; そして evict / reset が lifecycle カウンタに実際に現れること。
;; **描画そのものは実ブラウザ E2E でしか測れない** —— この軸を :working にしても
;; 「スキン描画が正しい」ではなく「スキン提出の簿記が正しい」の意味しかない。
(try
  (let [c (mesh/create-skinned-submission-cache)
        ev0 (mesh/skinned-submission-evidence c)
        dev-a #js {:label "device-a"} dev-b #js {:label "device-b"}
        bad-provenance (try (mesh/prepare-skinned-submission-packets!
                             {:device nil} c [{:material-range 0}])
                            :not-refused (catch :default e (.-message e)))
        c2 (mesh/create-skinned-submission-cache dev-a)
        wrong-device (try (mesh/prepare-skinned-submission-packets!
                           {:device dev-b} c2 [{:entity-id "e1"}])
                          :not-refused (catch :default e (.-message e)))
        _ (mesh/reset-skinned-submission-cache! c)
        _ (mesh/evict-skinned-entity! c "nobody")
        ev1 (mesh/skinned-submission-evidence c)
        _ (mesh/destroy-skinned-submission-cache! c)
        after-destroy (try (mesh/prepare-skinned-submission-packets!
                            {:device nil} c [{:entity-id "e1"}])
                           :not-refused (catch :default e (.-message e)))]
    (cond
      (not= :kotoba.webgpu/skinned-submission-evidence-v1 (:schema ev0))
      (println "PROBE render-skinned-gpu FAIL" (str "evidence の schema が " (pr-str (:schema ev0))))
      (or (:cache-device-bound? ev0) (pos? (:resident-entity-count ev0)))
      (println "PROBE render-skinned-gpu FAIL" "新しいキャッシュが空でない")
      (= :not-refused bad-provenance)
      (println "PROBE render-skinned-gpu FAIL"
               ":entity-id を持たない draw を受け入れる —— どの palette か決められないのに進む")
      (= :not-refused wrong-device)
      (println "PROBE render-skinned-gpu FAIL"
               "別デバイスでのキャッシュ再利用を拒まない —— GPU リソースの取り違えは静かに壊れる")
      (not= 1 (get-in ev1 [:lifecycle :reset-count]))
      (println "PROBE render-skinned-gpu FAIL"
               (str "reset が lifecycle に現れない: " (pr-str (:lifecycle ev1))))
      (= :not-refused after-destroy)
      (println "PROBE render-skinned-gpu FAIL" "破棄済みキャッシュを再利用できてしまう")
      :else (println "PROBE render-skinned-gpu PASS"
                     (str "簿記のみ（描画は実ブラウザ E2E）: 3 つの拒否（由来なし draw / "
                          "別デバイス / 破棄済み）と lifecycle "
                          (pr-str (select-keys (:lifecycle ev1) [:reset-count :entity-evictions]))))))
  (catch :default ex (println "PROBE render-skinned-gpu UNMEASURABLE" (.-message ex))))

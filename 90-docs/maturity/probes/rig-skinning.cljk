(ns probe-rig-skinning (:require [kami.animation :as a]))
;; 不変条件: (1) rest pose のスキニング行列は単位行列
;;          (2) 親ボーンを動かすと **子の**行列も変わる（階層が効いている）
;;          (3) 2 ボーン IK が root/mid の回転を返す
;; 親を動かして子が動かないリグは、行列を並べただけで階層ではない。
(try
  (let [sk (a/skeleton [(a/bone :root "root" nil {:translation [0 0 0]})
                        (a/bone :mid "mid" :root {:translation [1 0 0]})
                        (a/bone :tip "tip" :mid {:translation [1 0 0]})])
        tgt (a/bone-track-target :root :translation :y)
        tl (a/timeline 2 [(a/track tgt [(a/keyframe 0 0) (a/keyframe 2 3)])])
        m0 (a/evaluate-skinning sk tl 0)
        m1 (a/evaluate-skinning sk tl 2)
        ident? (fn [m] (every? true? (map #(< (Math/abs (- %1 %2)) 1e-9) m
                                          [1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1])))
        ik (a/solve-two-bone-ik {:root [0 0] :length-a 1.0 :length-b 1.0 :target [1.5 0.5]})]
    (cond
      (not (ident? (first m0)))
      (println "PROBE rig-skinning FAIL" (str "rest pose の行列が単位行列でない: " (pr-str (first m0))))
      (= m0 m1)
      (println "PROBE rig-skinning FAIL" "root を動かしても skinning 行列が変わらない")
      (= (nth m0 2) (nth m1 2))
      (println "PROBE rig-skinning FAIL" "root を動かしても tip の行列が変わらない —— 階層が効いていない")
      (not (and (map? ik) (some? (:ik/root-rotation ik)) (some? (:ik/mid-rotation ik))))
      (println "PROBE rig-skinning FAIL" (str "2 ボーン IK が解を返さない: " (pr-str ik)))
      :else (println "PROBE rig-skinning PASS"
                     (str "bones=" (count m0) " 階層伝播 ok IK root=" (:ik/root-rotation ik)
                          " mid=" (:ik/mid-rotation ik)))))
  (catch :default ex (println "PROBE rig-skinning UNMEASURABLE" (.-message ex))))

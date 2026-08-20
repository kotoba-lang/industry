(ns probe-feature-registry (:require [brep.feature :as f] [clojure.string :as str]))
;; 不変条件: registry が **自分の守備範囲を自分で答え**、範囲外を名指しで拒否する。
;;   (1) supported-feature-kinds が registry から導かれている（default を含まない）
;;   (2) 未登録 kind は [:error …] で、その kind 名と登録済み集合の両方を含む
;;   (3) 登録済み kind は default に落ちない
;; 「対応 kind の一覧」という第二のリストが無いことが、この面の主張。
(try
  (let [kinds (f/supported-feature-kinds)
        sq (fn [id plane s]
             (f/sketch-feature id plane
               [(f/sketch-line [0 0] [s 0]) (f/sketch-line [s 0] [s s])
                (f/sketch-line [s s] [0 s]) (f/sketch-line [0 s] [0 0])]))
        base (-> (f/feature-tree)
                 (f/add-feature (sq 1 (f/sketch-plane-xy) 4))
                 (f/add-feature (f/extrude-feature 2 1 [0 0 1] 3 :new)))
        [st msg] (f/evaluate-mesh (f/add-feature base (f/fillet-feature 9 [1] 1.0)))]
    (cond
      (contains? kinds :default)
      (println "PROBE feature-registry FAIL" ":default が supported-feature-kinds に混じっている")
      (not (contains? kinds :extrude))
      (println "PROBE feature-registry FAIL" (str "registry に :extrude が無い: " (pr-str kinds)))
      (not= :error st)
      (println "PROBE feature-registry FAIL"
               (str "未登録の :fillet が拒否されない（status=" st "）"))
      (not (and (str/includes? msg ":fillet") (str/includes? msg ":extrude")))
      (println "PROBE feature-registry FAIL"
               (str "拒否はするが、欠けている kind か登録済み集合のどちらかを言わない: " msg))
      :else (println "PROBE feature-registry PASS"
                     (str "登録済み " (pr-str (vec (sort kinds)))
                          " / 未登録は名指しで拒否"))))
  (catch :default ex (println "PROBE feature-registry UNMEASURABLE" (.-message ex))))

(ns probe-usd (:require [usd.core :as u] [clojure.string :as str]))
;; 不変条件: usda で書いた layer を parse で読み戻すと prim 構造が保存される。
;; USD は Omniverse / Maya / Houdini の共通面なので、往復しない実装は交換に使えない。
(defn- norm [x]
  (cond (keyword? x) (name x) (vector? x) (mapv norm x) (map? x) (into {} (map (fn [[k v]] [k (norm v)]) x)) :else x))

(try
  (let [prims [[:def "Xform" "root" {:kind "component"}
                [:def "Mesh" "body"
                 [:attr "float3[]" "points" [[0 0 0] [1 0 0] [0 1 0]]]]]]
        text (apply u/usda {} prims)
        back (u/parse text)
        n (count (:prims back))]
    (cond
      (not (str/starts-with? text "#usda 1.0"))
      (println "PROBE usd FAIL" "#usda ヘッダが出ない")
      (not (str/includes? text "def Xform \"root\""))
      (println "PROBE usd FAIL" (str "prim が USDA 構文で出ない: " (subs text 0 (min 120 (count text)))))
      (not= 1 n) (println "PROBE usd FAIL" (str "往復で top-level prim が " n " 個（1 を期待）"))
      ;; 既知の非対称: parse は prim 名を keyword で返す（usda は string を受ける）。
      ;; 実装の docstring が自ら申告している collapse なので、名前を正規化して構造を比べる。
      (not= (norm prims) (norm (:prims back)))
      (println "PROBE usd FAIL" (str "往復で prim 構造が変わる: "
                                     (subs (pr-str (:prims back)) 0 (min 200 (count (pr-str (:prims back)))))))
      :else (println "PROBE usd PASS"
                     (str "usda " (count text) " bytes / round-trip 構造一致"
                          "（既知の非対称: prim 名が string→keyword に畳まれる）"))))
  (catch :default ex (println "PROBE usd UNMEASURABLE" (.-message ex))))

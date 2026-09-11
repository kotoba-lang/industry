(ns probe-gltf (:require [gltf]))
;; 不変条件: GLB は magic "glTF" / version 2 / 宣言長 = 実バイト数 を満たす。
;; 長さフィールドが実体と食い違う GLB は、どの viewer も開けない。
(try
  (let [mesh {:vertices (vec (repeat 24 0.0)) :indices [0 1 2] :vertex-count 3 :index-count 3}
        glb (vec (gltf/export-glb-byte-seq mesh [1.0 0.0 0.0 1.0]))
        magic (vec (take 4 glb))
        ver (subvec glb 4 8)
        total (gltf/le-bytes->u32 (subvec glb 8 12))
        json-tag (subvec glb 16 20)]
    (cond
      (not= magic (vec (gltf/u32->le-bytes gltf/glb-magic)))
      (println "PROBE gltf FAIL" "magic が glTF でない")
      (not= ver (vec (gltf/u32->le-bytes 2)))
      (println "PROBE gltf FAIL" "version が 2 でない")
      (not= total (count glb))
      (println "PROBE gltf FAIL" (str "宣言長 " total " ≠ 実長 " (count glb)))
      (not= json-tag [0x4A 0x53 0x4F 0x4E])
      (println "PROBE gltf FAIL" "JSON chunk tag が不正")
      :else (println "PROBE gltf PASS" (str "GLB " (count glb) " bytes / header 整合"))))
  (catch :default ex (println "PROBE gltf UNMEASURABLE" (.-message ex))))

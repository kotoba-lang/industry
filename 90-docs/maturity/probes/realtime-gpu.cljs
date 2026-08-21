(ns probe-realtime-gpu)
;; リアルタイム描画は実 GPU が要る。nbb の Node には `navigator.gpu` が無いので、
;; **この経路では描画そのものを測れない。** したがってこの probe は PASS を出さない ——
;; 測っていないものを緑にしないため（CLAUDE.md 2608136000 の第 4 問）。
;;
;; 描画を実際に discriminate する検査は実ブラウザの E2E に在るべきで、その置き場は
;; `kotoba-lang/wasm-webcomponent` の render harness（WebGPU/Metal backend）。
;; この軸を :working にしたければ、そこを呼ぶ probe を書くこと。
(println "PROBE realtime-gpu UNMEASURABLE"
         (str "Node に navigator.gpu が無い（"
              (if (and (exists? js/navigator) (some? (.-gpu js/navigator))) "在る" "無い")
              "）。リアルタイム描画は実ブラウザ E2E でしか測れない —— "
              "この軸は :declared のまま据え置く"))

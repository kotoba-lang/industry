(ns probe-interop-ogawa (:require [ogawa.core :as o] [clojure.string :as str]))
;; Ogawa は Alembic の**容器**（下層）で、object model は別の層。ここで測るのは容器。
;;
;; 決定的なのは **「自分が書いていないバイト列を読めるか」**。writer は子を親より
;; 前に置く（子の位置が決まるまで親を書けないから）ので、**root group が
;; ヘッダ直後にある配置は writer が決して出さない**。仕様から手で組んだその形を
;; 読ませることが、「自分と一致している」ではなく「Alembic と一致している」の証拠。
;;
;; もう 1 つは frozen バイト。Alembic は書き込み中 0 を置き、完了時に 0xff を打つ。
;; ここを見ない reader は、**まだ書かれている途中のファイル**を「部分的にある木」
;; として返す。
(try
  (let [le (fn [n d?] (let [b (mapv (fn [i] (mod (long (/ n (js/Math.pow 256 i))) 256)) (range 8))]
                        (if d? (assoc b 7 (bit-or (nth b 7) 0x80)) b)))
        hand (vec (concat [0x4f 0x67 0x61 0x77 0x61 0xff 0 1] (le 16 false)
                          (le 2 false) (le 40 true) (le 0 true)
                          (le 4 false) [0xde 0xad 0xbe 0xef]))
        [hst htree] (o/read-archive hand)
        tree (o/group (o/data [1 2 3]) (o/group (o/data (range 10)) o/empty-data) o/empty-group)
        [wst bs] (o/write-archive tree)
        [rst back] (o/read-archive bs)
        payload (vec (range 256))
        [_ pbs] (o/write-archive (o/group (o/data payload)))
        [_ pback] (o/read-archive pbs)
        err (fn [f] (first (o/read-archive f)))
        msg (fn [f] (str (second (o/read-archive f))))]
    (cond
      (not= :ok hst)
      (println "PROBE interop-ogawa FAIL"
               (str "仕様から手で組んだバイト列を読めない: " (pr-str htree)))
      (not= {:groups 1 :data 1 :bytes 4 :empty-groups 0 :empty-data 1} (o/tree-summary htree))
      (println "PROBE interop-ogawa FAIL"
               (str "手組みバイト列の解釈が違う: " (pr-str (o/tree-summary htree))))
      (not= [0xde 0xad 0xbe 0xef] (:ogawa/bytes (first (:ogawa/children htree))))
      (println "PROBE interop-ogawa FAIL"
               (str "data ブロックの中身が違う: " (pr-str (:ogawa/bytes (first (:ogawa/children htree))))))
      (not= o/empty-data (second (:ogawa/children htree)))
      (println "PROBE interop-ogawa FAIL"
               "高位ビット + 位置 0 を EMPTY_DATA ではなく「位置 0 の data」と読んでいる")
      (or (not= :ok wst) (not= [0x4f 0x67 0x61 0x77 0x61] (vec (take 5 bs)))
          (not= 0xff (nth bs 5)) (not= [0 1] [(nth bs 6) (nth bs 7)]))
      (println "PROBE interop-ogawa FAIL"
               (str "書き出したヘッダが仕様と違う: " (pr-str (vec (take 8 bs)))))
      (or (not= :ok rst) (not= (o/tree-summary tree) (o/tree-summary back)))
      (println "PROBE interop-ogawa FAIL"
               (str "自分の出力を読み戻せない: " (pr-str (o/tree-summary back))))
      (not= payload (:ogawa/bytes (first (:ogawa/children pback))))
      (println "PROBE interop-ogawa FAIL" "0 と 255 を含む 256 値のペイロードが往復しない")
      (not= :error (err (assoc hand 0 0x58)))
      (println "PROBE interop-ogawa FAIL" "magic が違うバイト列を受理している")
      (or (not= :error (err (assoc hand 5 0)))
          (not (str/includes? (msg (assoc hand 5 0)) "never finished")))
      (println "PROBE interop-ogawa FAIL"
               "frozen バイト（0xff）を見ていない —— 書き込み途中のファイルを部分的な木として返す")
      (not= :error (err (assoc hand 7 9)))
      (println "PROBE interop-ogawa FAIL" "読めない版を受理している")
      (not= :error (first (o/write-archive (o/group (o/data [1 2 999])))))
      (println "PROBE interop-ogawa FAIL" "バイトでない値を data として書き出す")
      :else
      (println "PROBE interop-ogawa PASS"
               (str "仕様から手で組んだ配置（writer が出さない形）を解釈 / "
                    "ヘッダが Ogawa/0xff/version 1 / 木が往復 / 256 値のペイロードが往復 / "
                    "magic・未完成・版違い・非バイトを拒否"))))
  (catch :default ex (println "PROBE interop-ogawa UNMEASURABLE" (.-message ex))))

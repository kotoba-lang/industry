(ns hikobae.emit
  "モデルを OASIS XMILE 1.0 の XML として書き出す。

   XML の生成は `xmile.xml` が持つ。ここは『どのモデルをどこへ』だけを決める。
   出力は Stella / iThink / insightmaker など XMILE を読む道具で開ける。"
  (:require [xmile.xml :as xml]
            [hikobae.model :as model]
            [hikobae.kumamoto :as kumamoto]
            #?(:clj [clojure.java.io :as io])))

(defn xmile-string
  "パラメータから完全な XMILE 1.0 ドキュメント文字列。"
  [params]
  (xml/emit-string (model/build-doc params)))

(defn round-trips?
  "書き出した XML を読み戻して、変数の名前集合が保たれるかを検査する。

   これは飾りではない。書き出しだけを検査すると『出力はしたが XMILE として
   読めない』が緑で通る。読み戻して突き合わせるまでが1組。"
  [params]
  (let [doc (model/build-doc params)
        s (xml/emit-string doc)
        back (xml/parse-string s)
        names-of (fn [d] (set (keys (:xmile/variables (first (:xmile/models d))))))]
    {:ok? (= (names-of doc) (names-of back))
     :emitted (count (names-of doc))
     :parsed (count (names-of back))
     :missing (into #{} (remove (names-of back) (names-of doc)))
     :extra (into #{} (remove (names-of doc) (names-of back)))}))

#?(:clj
   (defn write!
     [path params]
     (io/make-parents path)
     (spit path (xmile-string params))
     {:path path :bytes (count (xmile-string params))}))

#?(:clj
   (defn -main [& args]
     (let [dir (or (first args) "models")
           targets [["kumamoto-2026.xmile"
                     (kumamoto/params)
                     "令和8年熊本地震 (2026-07-28) 実観測校正済み"]
                    ["kumamoto-2026-digital-certificates.xmile"
                     (kumamoto/scenario-params (kumamoto/params) :digital-certificates-day-10)
                     "同上 + 罹災証明オンライン申請を day 10 導入（反実仮想）"]
                    ["global-baseline.xmile"
                     (assoc model/default-params
                            :model/name "post-earthquake recovery (generic baseline)")
                     "法域非依存の既定値。実イベントに校正されていない骨格。"]]]
       (doseq [[fname params note] targets]
         (let [p (str dir "/" fname)
               r (write! p params)
               rt (round-trips? params)]
           (println (format "%-46s %7d bytes  round-trip=%s  vars=%d  — %s"
                            fname (:bytes r) (:ok? rt) (:emitted rt) note))
           (when-not (:ok? rt)
             (println "  ⚠ ROUND-TRIP FAILED" (pr-str (select-keys rt [:missing :extra])))
             (System/exit 1))))
       (println "\nwrote" (count targets) "XMILE 1.0 documents to" dir))))

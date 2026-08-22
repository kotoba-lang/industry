#!/usr/bin/env nbb
;; threed-icp-drift — 売り文句と実測のずれを見つける。
;;
;; `90-docs/business/gtm-icp.datoms.edn` は手で書く文書で、
;; `90-docs/maturity/threed-parity.datoms.edn` は測定の生成物である。
;; **両者は静かにずれる。** 能力が着地すると私は ICP に「実装済み」と書き足すが、
;; 前に書いた「無い」を消し忘れる —— CLAUDE.md 2607257000 が「文書は最新状態のみを
;; 表す。履歴は git に任せる」と書いているのに、追記してしまう。
;;
;; 実測 2026-08-24、この検査で 2 件見つかった。どちらも同じ anti-claim の中で:
;;   「fillet / chamfer / shell は evaluator に未登録（:absent）」（3 日前の記述。
;;    chamfer は 08-21、shell は 08-22、fillet は 08-23 に着地している）
;;   「fillet は円弧ブレンドという、このカーネルが表現を持たない曲面なので
;;    chamfer の一般化では届かない」（**当時の判断自体が誤り**だった —— 解析曲面は
;;    要らず、テセレートでよかった）
;; 同じ文書の別の場所には「shell は実装済み」「fillet は実装済み」と書いてあった。
;; **文書が自分と矛盾していた。**
;;
;; 検査は 2 つ:
;;   (1) `:icp/proof-axes` が引用する軸 id が実在し、かつ :working であること
;;   (2) anti-claim の中に「<実装済みの能力> は無い / 未登録」と読める記述が
;;       残っていないこと
;;
;; (2) は**語の一致であって意味の理解ではない**ので、偽陽性が出る。実例:
;; 「chamfer・shell・fillet の 3 つでは**足りない**」は範囲の説明であって
;; 不在の主張ではないので、「足りない」だけは除外した。**それ以上は絞らない**
;; —— 絞りすぎると本物のずれが隠れる。実例:
;; 「OpenVDB の `.vdb` は無い」は正しい（NanoVDB の `.nvdb` だけが在る）。
;; だから見つけたものは **報告であって失格ではない** —— exit 1 にはせず、
;; 人が読んで判断する。黙って直す方が危ない。
;;
;; ## 今の基準線は DRIFT 1（2026-08-24）
;;
;; 残っている 1 件は**正しい記述**である:
;;   「複数稜の同時 fillet はまだ通らない組み合わせがある」（2 稜 16 分割は
;;    8 通りの工具長を全部外す —— 実測どおりの範囲の但し書きであって不在の主張ではない）
;; これ以上パターンを絞らない。**0 件になるまで絞る道は、何も報告しない検査で終わる。**
;; 1 件を基準線として置き、**2 件以上出たら何かが変わった**と読む。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/threed-icp-drift.cljs
;;
;; exit 0 = 検査できた（ずれの有無は出力を読む）  3 = 答えられなかった

(ns threed-icp-drift
  (:require [clojure.edn :as edn] [clojure.string :as str] ["fs" :as fs] ["path" :as path]))

(def root (or (aget js/process.env "THREED_ROOT") (js/process.cwd)))
(def parity-file (path/join root "90-docs" "maturity" "threed-parity.datoms.edn"))
(def icp-file (path/join root "90-docs" "business" "gtm-icp.datoms.edn"))

(defn refuse! [msg]
  (binding [*print-fn* *print-err-fn*] (println (str "REFUSED\t" msg)))
  (js/process.exit 3))

;; 語 → 軸。**軸が :working のときだけ**「無い」という記述を疑う。
(def capability-words
  {"IGES" ":interop/iges"
   "DXF" ":interop/dxf-read"
   "fillet" ":kernel/fillet"
   "chamfer" ":kernel/chamfer"
   "shell" ":kernel/shell"
   "板金" ":mcad/sheet-metal"
   "デノイズ" ":render/denoise"
   "path tracer" ":render/offline-pathtracer"
   "色管理" ":render/color-management"
   "布" ":dcc/cloth-hair"
   "パーティクル" ":dcc/particles-vfx"
   "Ogawa" ":interop/ogawa"})

(defn -main []
  (when-not (and (fs/existsSync parity-file) (fs/existsSync icp-file))
    (refuse! "parity か ICP のどちらかが無い"))
  (let [parity (edn/read-string (str (fs/readFileSync parity-file "utf8")))
        status (into {} (map (fn [d] [(:parity/axis d) (:parity/status d)]) parity))
        icp (edn/read-string (str (fs/readFileSync icp-file "utf8")))
        findings (atom [])]
    (when (empty? status) (refuse! "parity datoms が 0 件 —— 先に audit を回すこと"))
    (doseq [e icp]
      ;; (1) 引用された軸
      (when-let [pa (:icp/proof-axes e)]
        (doseq [cited (re-seq #":[a-z0-9-]+/[a-z0-9-]+" (str pa))]
          (cond
            (not (contains? status cited))
            (swap! findings conj [(:icp/id e) :missing-axis cited])
            (not= ":working" (get status cited))
            (swap! findings conj [(:icp/id e) :not-working (str cited " は " (get status cited))]))))
      ;; (2) 実装済みの能力を「無い」と書いていないか
      (when-let [ac (:icp/anti-claim e)]
        ;; 「…」の中は**記録であって主張ではない**。CLAUDE.md 2607257000 は
        ;; 「古い記述を黙って消さず、いつ・なぜ変えたかを残せ」と言うので、
        ;; 誤りを訂正した文書はその誤りを引用として含む。引用まで拾うと、
        ;; **正しく訂正した文書ほど多く警告される** —— 直した人を罰する検査は
        ;; 直さない方が得になるので、鉤括弧の中は外す。
        (let [a (str/replace (str ac) #"「[^」]*」" "")]
          (doseq [[w axis] capability-words
                  :when (= ":working" (get status axis))]
            (when-let [m (re-find (re-pattern (str w "[^。]{0,24}(は|も)?(無い|未登録|(?<!足り)ない)")) a)]
              (swap! findings conj [(:icp/id e) :contradiction
                                    (str w " → " axis " は :working だが「" (first m) "」と読める")]))))))
    (println (str "SCANNED\taxes=" (count status) "\ticp=" (count icp)
                  "\twords=" (count capability-words)))
    (if (empty? @findings)
      (println "DRIFT\t0")
      (do (println (str "DRIFT\t" (count @findings)
                        "\t（語の一致なので偽陽性が混じる。読んで判断すること）"))
          (doseq [[id kind detail] @findings]
            (println (str "  " id "\t" (name kind) "\t" detail)))))))

(-main)

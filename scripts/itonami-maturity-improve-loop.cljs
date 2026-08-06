#!/usr/bin/env nbb
;; scripts/itonami-maturity-improve-loop.cljs — 成熟度を**上げる**ローカル Claude
;; loop の入口（superproject ADR-2608080000）。LaunchAgent
;; com.gftd.itonami-maturity-improve が起こす。
;;
;; 兄弟 `itonami-os-connect-loop.cljs` と同じ 2 段構え:
;;   1. `itonami-maturity-improve-tick.cljs` が対象と軸を**測る**（決定論）
;;   2. 条件を満たしたときだけ `claude -p "/itonami-maturity-improve"` を起こす
;;
;; ## 横と縦
;;
;;   itonami-os-connect        まだ繋がっていない産業を OS に繋ぐ（横に広げる）
;;   itonami-maturity-improve  既にある repo の軸を上げる（縦に深める）
;;
;; ADR-2608052000 の配分（substrate 2〜5% / 残りは breadth）は tick が lane と
;; して出す。**この loop は配分を判断しない。**
;;
;; ## モデルを起こさない条件
;;
;;   - origin/main に FF できない  … 古い base の上に積まない
;;   - 計測値が stale             … 古い順位に従うのは、測っていないものを
;;                                   測ったことにするのと同じ（測り直しが先）
;;   - 目標にできる軸が無い       … 水増しに行かせない
;;
;; exit 0 常に。
;;
;; 使い方:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/itonami-maturity-improve-loop.cljs
;;   ... --dry-run   ; 測って対象を出すところまで（モデルを起こさない）

(ns itonami-maturity-improve-loop
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "fs"))
(def os (js/require "os"))
(def cp (js/require "child_process"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-file (str home "/.gftd/itonami-maturity-improve.ledger.edn"))
(def dry-run? (boolean (some #{"--dry-run"} *command-line-args*)))

(defn log! [& xs]
  (println (str (.toISOString (js/Date.)) " " (str/join " " (map str xs)))))

(defn- sh [cmd args opts]
  (try (let [r (.spawnSync cp cmd (clj->js args)
                           (clj->js (merge {:encoding "utf8" :cwd root} opts)))]
         {:code (aget r "status") :out (str (aget r "stdout")) :err (str (aget r "stderr"))})
       (catch :default e {:code nil :out "" :err (str e)})))

(defn- append-ledger! [m]
  (try (.appendFileSync fs ledger-file (str (pr-str m) "\n"))
       (catch :default e (log! "ledger 追記に失敗:" (str e)))))

(defn- last-measured
  "tick が書いた最終の測定行。**loop 自身の記録行は読み飛ばす** —— 混ぜると
  『前回の結果』と『今回の測定』が区別できなくなる。"
  []
  (try
    (->> (str (.readFileSync fs ledger-file "utf8"))
         str/split-lines
         (remove str/blank?)
         (keep #(try (edn/read-string %) (catch :default _ nil)))
         (filter #(= :measured (:outcome %)))
         last)
    (catch :default _ nil)))

(defn -main []
  (let [started (.toISOString (js/Date.))]

    (sh "git" ["fetch" "origin" "--quiet"] {})
    (let [{:keys [code err]} (sh "git" ["merge" "--ff-only" "origin/main"] {})]
      (when (not= 0 code)
        (log! "origin/main に FF できない。この周は何もしない:" (str/trim (or err "")))
        (append-ledger! {:at started :outcome :skipped :why :not-fast-forwardable})
        (js/process.exit 0)))

    (let [{:keys [out]} (sh "nbb" ["--classpath" ".:scripts/nbb_compat"
                                   "scripts/itonami-maturity-improve-tick.cljs"] {})]
      (println out))

    (let [m (last-measured)
          top (first (:ranked m))
          axis (first (filter :targetable? (:weakest top)))]
      (cond
        (nil? m)
        (do (log! "tick の測定行が読めない。この周は何もしない")
            (append-ledger! {:at started :outcome :skipped :why :no-measurement}))

        (:datoms-stale? m)
        ;; **測り直しもモデルの仕事**（scan → dynamics → 着地は判断を含む）。
        ;; ただし「軸を上げる」周ではないので lane は記録しない。
        (if dry-run?
          (do (log! "--dry-run: 計測値が stale。次の 1 手は測り直し")
              (append-ledger! {:at started :outcome :dry-run :why :stale-measurement}))
          (let [{:keys [code out]} (sh "claude" ["-p" "/itonami-maturity-improve"
                                                 "--allow-dangerously-skip-permissions"]
                                       {:timeout 5400000})]
            (println out)
            (append-ledger! {:at started :finished (.toISOString (js/Date.))
                             :outcome (if (= 0 code) :ran :failed) :exit code
                             :why :stale-measurement
                             :note "測り直しの周。lane は消費しない"})))

        (nil? axis)
        (do (log! "目標にできる軸が無い（上位が『狙わない軸』ばかり）。水増しに行かせない")
            (append-ledger! {:at started :outcome :skipped :why :no-targetable-axis
                             :top (:repo top)}))

        dry-run?
        (do (log! "--dry-run: lane=" (:lane m) " 対象=" (:repo top)
                  " 軸=" (:axis axis) " (+" (:headroom-bp axis) "bp)")
            (append-ledger! {:at started :outcome :dry-run :lane (:lane m)
                             :target (:repo top) :axis (:axis axis)}))

        :else
        (let [{:keys [code out err]}
              (sh "claude" ["-p" "/itonami-maturity-improve"
                            "--allow-dangerously-skip-permissions"]
                  {:timeout 5400000})]
          (println out)
          (when (seq (str/trim (or err ""))) (log! "stderr:" (str/trim err)))
          (log! "claude 終了コード:" code)
          (append-ledger! {:at started
                           :finished (.toISOString (js/Date.))
                           :outcome (if (= 0 code) :ran :failed)
                           :exit code
                           :lane (:lane m)
                           :target (:repo top)
                           :axis (:axis axis)
                           :headroom-bp (:headroom-bp axis)
                           :own-before (:own top)
                           ;; **上がったかはここでは判定しない。** 次周の tick が
                           ;; 測り直す —— 自己申告の green を信用しない
                           ;; （ADR-2607189300）。`:own-before` を残すのは、
                           ;; 次周の測定と突き合わせられるようにするため。
                           :note "実際に上がったかは次周の測定が言う"}))))

    (js/process.exit 0)))

(-main)

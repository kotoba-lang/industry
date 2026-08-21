#!/usr/bin/env nbb
;; shard-index-check.cljs — kotobase-shard-index の gate。
;; superproject ADR-2608071500。
;;
;; **判定ロジックはここに複製しない。** 正本は tool 側の 2 本
;;   test/run_tests.cljs      （正しさ）
;;   bench/check_budget.cljs  （コスト）
;; で、この gate は展開済み tree に対してそれを呼ぶだけ。gate に判定を書くと
;; fleet と repo で別実装になり、片方だけ通る状態が黙って生まれる
;; （permit-index-check.cljs / west-pin-policy-check.cljs と同じ理由）。
;;
;; ## 何が守られるか
;;
;; 1. **正しさ** — early termination が答えを変えていないこと。停止条件は
;;    「証明」であって近似ではない、というのがこの設計の主張なので、その主張の
;;    検査が落ちたら設計が嘘になる。run_tests.cljs の exactness 節は bounded
;;    scan を同じ索引の full scan と突き合わせる。
;;
;; 2. **コスト** — 1 クエリあたりの GET 数と waves。これが要る理由は
;;    ADR-2607250100 軸 1 が名指ししている: kotobase-peer の CI は unit test しか
;;    走らせないので、**性能が退行しても検出する仕組みが無い**。コストモデルが
;;    主張の全部である設計は、コストモデルを CI に入れないと主張を維持できない。
;;
;; 予算は**回数**であって時間ではない。共有ノードでの時間予算はノードの
;; 他テナントを測る。
;;
;; ## exit code だけを信用しない
;;
;; nbb の runner は失敗しても exit code を立てないことがあるので、出力の語も
;; 見る。何も出力せず exit 0 で終わるのが一番危険（検査が 0 件走って合格に
;; 見える）。

(ns shard-index-check
  (:require [clojure.string :as str]))

(def cp (js/require "child_process"))
(def fs (js/require "fs"))

(def dir
  ;; argv[0] は node、argv[1] は nbb 自身のパス。gate の引数は **その次**から。
  ;; 位置を推測せず、スクリプト自身のパスの後ろを取る（permit-index-check が
  ;; 2026-08-06 に `/opt/homebrew/bin/nbb` を tree 根と誤解した実例がある）。
  (let [argv (vec (js->clj js/process.argv))
        i (first (keep-indexed
                  (fn [i a] (when (str/ends-with? (str a) "shard-index-check.cljs") i))
                  argv))]
    (or (when i (get argv (inc i))) ".")))

;; `:cd true` puts us inside the shipped repo, so paths are repo-relative.
;; They were "70-tools/kotobase-shard-index/…" while the subsystem was staged
;; in the superproject (ADR-2608087000 moved it out).
(def tool ".")

(defn- run [label script extra]
  (let [r (.spawnSync cp "npx"
                      (clj->js (concat ["nbb" "--classpath" (str tool "/src") script] extra))
                      #js {:encoding "utf8" :cwd dir :timeout 1500000})
        out (str (.-stdout r) (.-stderr r))]
    (println (str "--- " label " ---"))
    (println out)
    {:out out :status (.-status r)}))

(defn -main []
  (doseq [f [(str tool "/test/run_tests.cljs")
             (str tool "/bench/check_budget.cljs")
             (str tool "/bench/budget.edn")]]
    (when-not (.existsSync fs (str dir "/" f))
      (println "shard-index-check: 必要なファイルが無い（" f "）— tree が不完全")
      (js/process.exit 1)))

  (let [t (run "correctness" (str tool "/test/run_tests.cljs") [])
        ;; 「failed: 0」が出ていることを積極的に確認する。exit 0 かつ無出力を
        ;; 合格にしない。
        t-ok? (and (str/includes? (:out t) "failed: 0")
                   (not (str/includes? (:out t) "FAIL:")))
        b (run "get budget" (str tool "/bench/check_budget.cljs") [])
        b-ok? (and (str/includes? (:out b) "OK: within budget")
                   (not= 1 (:status b)))]
    (cond
      (not t-ok?)
      (do (println "shard-index-check: FAIL — exactness/unit suite did not report `failed: 0`")
          (js/process.exit 1))

      (not b-ok?)
      (do (println "shard-index-check: FAIL — GETs-per-query regressed past the recorded budget")
          (js/process.exit 1))

      :else
      (println "shard-index-check: OK — 一致（exactness 合格 / GET 予算内）"))))

(-main)

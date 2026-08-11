#!/usr/bin/env nbb
;; placement-shim.cljs — murakumo.task.plan を「murakumo の classpath の中で」呼ぶ薄い殻。
;;
;; ADR-2608111721 決定 1: placement authority は `murakumo.task.plan`。
;;
;; ## なぜ subprocess なのか
;;
;; `murakumo.task.plan` は `:task-plan` KIR を要求し、その KIR インタプリタ
;; （`kotoba.kir`）は kotoba-hir / security / io-multiformats / dag-cbor へ推移的に
;; 依存する —— 実測で **34 の classpath entry**（`clojure -Spath`）。これを
;; `tick.cljs` 自身の classpath に載せると、**tick の起動方法が変わる**（plist も
;; README の手動コマンドも全部）。tick は 5 分ごとの常駐なので、そこは変えない。
;;
;; したがって placement だけを別プロセスで解く。**gate をノードで走らせるのと同じ形**
;; （EDN を stdin で渡し、EDN を stdout で受ける）で、fleet-ci 側は murakumo の
;; 依存グラフを一切知らない。
;;
;; stdin:  {:nodes [{:name :host :cores :roles :mem-bytes :slots :online?} …]
;;          :tasks [{:id :placement {:roles [...]} :cost N} …]   ← cost 降順で渡すこと
;;          :opts  {…}}
;; stdout: {:assignments [{:task-id :node :wave :slot} …]
;;          :unschedulable [{:task-id :reason :detail} …]}
;;
;; **tasks の順序がそのまま LPT になる。** `murakumo.task.plan/assign` は与えられた
;; 順に fold して「最も空いている eligible node」へ置く greedy なので、cost 降順で
;; 渡せば LPT と同じ順序付けになる（実測: placement-parity.cljs）。並べ替えは
;; 呼び側の責任 —— ここでは並べ替えない（渡された順を尊重する）。
;;
;; 実行（classpath は呼び側が用意する。placement.cljs が cache して渡す）:
;;   nbb --classpath "<murakumo と推移依存>" scripts/fleet-ci/placement-shim.cljs \
;;     --resources <murakumo>/resources < in.edn

(ns placement-shim
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [murakumo.kotoba.oracle :as oracle]))

(def args (vec *command-line-args*))

(defn- flag [n d] (let [i (.indexOf args n)] (if (neg? i) d (nth args (inc i)))))

(def resources (flag "--resources" nil))

;; oracle の既定ローダは `process.cwd()/resources/` を見る。fleet-ci は superproject
;; root から走るので解決しない —— `set-resource-loader!` が oracle 自身が document
;; している拡張点（回避策ではない）。
(when resources
  (oracle/set-resource-loader!
   (fn [p]
     (let [f (path/join resources p)]
       (when (.existsSync fs f) (.readFileSync fs f "utf8"))))))

(oracle/preload! [:task-plan])

(require '[murakumo.task.plan :as mk])

(def input (reader/read-string (str (.readFileSync fs 0 "utf8"))))

(let [{:keys [nodes tasks opts]} input
      r (mk/assign (vec nodes) (vec tasks) (or opts {}))]
  (println
   (pr-str
    {:assignments (vec (for [a (:assignments r)]
                         {:task-id (get-in a [:task :id])
                          :node (:node a)
                          :host (:host a)
                          :wave (:wave a)
                          :slot (:slot a)}))
     :unschedulable (vec (for [u (:unschedulable r)]
                           {:task-id (get-in u [:task :id])
                            :reason (:reason u)
                            :detail (:detail u)}))})))

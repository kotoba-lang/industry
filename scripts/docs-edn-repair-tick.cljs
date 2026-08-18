#!/usr/bin/env nbb
;; scripts/docs-edn-repair-tick.cljs — 90-docs のうち **reader を通らない文書**を
;; 測る決定論的 tick。ここにモデルは居ない。
;;
;; ## なぜこの tick が要るか
;;
;; `90-docs/` の文書は datom 面（`manifest/edn-query.cljs`）の入力である。reader を
;; 通らない文書は **存在するのに、どの query にも出てこない** —— 消えたのではなく、
;; 見えない。2026-08-08 実測で 7 件あり、最古のものは 2026-07-24 から不可視だった。
;;
;; `manifest/docs-edn-only.cljs verify` はこれを既に検出しているが、その 7 件は
;; `known-parse-errors` の baseline に載っていて **fail しない**（載せた理由は正しい:
;; 恒久的な赤は「新しく壊れた」を報告できなくなる）。つまり誰も直さない限り
;; 静かに残り続ける。この tick はその baseline を**減らす仕事の候補**を出す。
;;
;; ## 分類は `scripts/diagnose-unreadable-edn.cljs` に委譲する
;;
;; 判定ロジックをここに複製しない。あちらが正本で、この tick は `--edn` で
;; 機械可読の結果を受け取るだけ（`parity-probe-check` / `capital-pools-check` と
;; 同じ形）。片方だけ通る状態を作らないため。
;;
;; ## 不変条件
;;
;;   - **走査対象が少なすぎたら候補 0 を報告しない。** sparse checkout は「部分で
;;     ある」と自己申告しない —— 2026-08-08 に同じ罠が 2 回刺さっている
;;     （docs-edn-only の baseline が sparse から取られていた件と、その調査中に
;;     私が同じ worktree で md=30 を見た件）。**部分ビューからの「もう無い」は
;;     嘘になる**ので、床を割ったら :insufficient-scan として報告する。
;;   - ledger は追記のみ。捏造ゼロ。
;;   - exit 0 常に（監視 tick であって gate ではない）。
;;
;; usage:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/docs-edn-repair-tick.cljs
;;   ... --min-edn N   ; 走査の床（既定 1500）

(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def os (js/require "node:os"))
(def cp (js/require "node:child_process"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT") (.cwd js/process)))
(def ledger-file (str home "/.gftd/docs-edn-repair-tick.ledger.edn"))

(defn- arg [flag default]
  (let [a (vec *command-line-args*)
        i (.indexOf a flag)]
    (if (neg? i) default (nth a (inc i)))))

(defn- walk-edn [dir]
  (let [{:keys [status stdout]}
        (let [r (.spawnSync cp "find" (clj->js [dir "-name" "*.edn" "-type" "f"])
                            #js {:encoding "utf8" :maxBuffer (* 256 1024 1024)})]
          {:status (.-status r) :stdout (str (.-stdout r))})]
    (if (zero? (or status 1))
      (vec (remove str/blank? (str/split-lines stdout)))
      [])))

(defn- readable? [p]
  (try (do (cljs.reader/read-string (.readFileSync fs p "utf8")) true)
       (catch :default _ false)))

(defn- classify [paths]
  (if (empty? paths)
    []
    (let [r (.spawnSync cp "nbb"
                        (clj->js (into ["scripts/diagnose-unreadable-edn.cljs" "--edn"] paths))
                        #js {:encoding "utf8" :cwd root :maxBuffer (* 32 1024 1024)})]
      (try (cljs.reader/read-string (str/trim (str (.-stdout r))))
           (catch :default _ [])))))

(defn -main []
  (let [started (.toISOString (js/Date.))
        min-edn (js/parseInt (str (arg "--min-edn" "1500")))
        docs (.join path root "90-docs")
        all (walk-edn docs)
        rel (fn [p] (let [i (str/index-of p "90-docs/")] (if i (subs p i) p)))
        broken (vec (remove readable? all))
        result
        (cond
          (< (count all) min-edn)
          {:at started :outcome :insufficient-scan
           :scanned (count all) :min-edn min-edn
           :note (str "90-docs の .edn が " (count all) " 件しか見つからない。"
                      "sparse checkout の可能性が高い —— 部分ビューからの「候補 0」は嘘になるので"
                      "候補を報告しない")}

          (empty? broken)
          {:at started :outcome :clean :scanned (count all) :candidates []}

          :else
          (let [cls (classify broken)
                by-status (group-by :status cls)]
            {:at started :outcome :candidates
             :scanned (count all)
             :broken (count broken)
             ;; 修復しやすい順に出す。closers-only は機械的に直る（--write が
             ;; キー集合の一致を確認して初めて書く）。
             :candidates (vec (for [c (concat (:closers-only by-status)
                                              (:key-set-changed by-status)
                                              (:needs-reconstruction by-status))]
                                (assoc c :path (rel (:path c)))))
             :by-status (into {} (for [[k v] by-status] [k (count v)]))}))]

    (println (str "docs-edn-repair-tick: 走査 " (count all) " / 読めない " (count broken)
                  " / outcome " (name (:outcome result))))
    (doseq [c (:candidates result)]
      (println (str "  " (name (:status c)) "  " (:path c)
                    (when-let [r (:reason c)] (str "\n      " r)))))
    (when (= :insufficient-scan (:outcome result))
      (println (str "  " (:note result))))

    (try (.appendFileSync fs ledger-file (str (pr-str result) "\n"))
         (catch :default e (println "ledger 追記に失敗:" (str e))))
    (js/process.exit 0)))

(-main)

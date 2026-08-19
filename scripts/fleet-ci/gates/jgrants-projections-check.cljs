#!/usr/bin/env nbb
;; jgrants-projections-check.cljs — the jGrants subsidy catalogue, checked on every tip.
;;
;; **検証ロジックはここに複製しない。** 正本は repo 内の
;; `scripts/verify-projections.cljs`（nta-projections-check.cljs と同じ形）。
;; fleet 側と手回し側で別実装を持つと、片方だけ通る状態が黙って生まれる。
;;
;; ## なぜ tip ごとに回すのか
;;
;; このカタログの危ない読み方は 2 つあり、どちらも「健康なファイル」の顔をする:
;;
;;   - **募集中を全件と混同する。** 3,751 件のうち募集中は 308 件で、詳細まで
;;     取れているのもその 308 件だけ。`:corpus/open-count` / `:corpus/detail-count`
;;     が manifest から消えると「3,751 件の補助金が使える」に読める。gate は
;;     manifest の申告と本文の実数が一致することも見る（片方だけ直る事故を塞ぐ）。
;;   - **締切が日付に丸まる。** `:subsidy/acceptance-end` は時刻付きで、丸めると
;;     当日締切が 1 日ずれる —— 募集中判定がそこに乗っている。
;;
;; ## exit 0 を信用しない
;;
;; 検査器は 0 clean / 1 violations / **2 could-not-answer**（data/ が無い、
;; 読めない）を返す。2 を pass に丸めない。
;;
;; ノード側で `npx nbb jgrants-projections-check.cljs <dir>` として実行。
(ns fleet-ci.gates.jgrants-projections-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

(defn- die! [code & msg]
  (println (str "FLEET-CI: " (str/join " " msg)))
  (js/process.exit code))

(def verifier (path/join root "scripts" "verify-projections.cljs"))
(def data-dir (path/join root "data"))

;; 展開失敗による false-pass を構造的に防ぐ。90 は「tree が期待どおり届いて
;; いない」を表す tick.cljs 側の慣習。
(when-not (fs/existsSync verifier)
  (die! 90 "scripts/verify-projections.cljs missing after extract"))
(when-not (fs/existsSync data-dir)
  (die! 90 "data/ missing after extract"))

(let [r (.spawnSync cp "npx" #js ["--yes" "nbb" verifier root]
                    #js {:encoding "utf8" :cwd root :maxBuffer (* 64 1024 1024)})
      out (str (.-stdout r)) err (str (.-stderr r))
      code (.-status r)]
  (print out)
  (when-not (str/blank? err) (print err))
  (cond
    (nil? code) (die! 90 "verifier did not run:" (str (.-error r)))
    ;; 検証器が自分で印字する行が無ければ、走っていない。**その非 0 を
    ;; 「違反があった」と読ませない** —— 実測 2026-08-19、operator 機の npx が
    ;; 壊れていて検証器を起動できず、この gate は「違反あり」と言った。
    ;; 走らなかったことと、走って違反を見つけたことは別の結論である。
    (not (str/includes? out "SCANNED\t"))
    (die! 90 "verifier produced no SCANNED line — it did not run; this is not a violation report")
    (= 0 code) (println "FLEET-CI: jgrants projections OK")
    (= 2 code) (die! 2 "verifier could not answer — treat as red, not as a pass")
    :else (die! 1 "jgrants projections have violations"))
  (js/process.exit (or code 90)))

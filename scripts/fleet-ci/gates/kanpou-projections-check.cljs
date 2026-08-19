#!/usr/bin/env nbb
;; kanpou-projections-check.cljs — the two 官報 projections, checked on every tip.
;;
;; **検証ロジックはここに複製しない。** 正本は repo 内の
;; `scripts/verify-projections.cljs`（nta-projections-check.cljs と同じ形）。
;; fleet 側と手回し側で別実装を持つと、片方だけ通る状態が黙って生まれる。
;;
;; ## なぜ tip ごとに回すのか
;;
;; 官報は**直近 90 日しか無料で読めない**。収集しなかった日は永久に失われるので、
;; この repo のデータは日次 cell が押し込む唯一の記録である。腐り方は 3 つ:
;;
;;   - **個人名が混ざる。** 落札公示の発注機関欄は「財務担当理事 鈴木 康晴」の形で
;;     役職と氏名を並べる。実測 2026-08-19、この経路で 2 名の氏名を committed data に
;;     書き出しかけた。**ファイルの中の何も、それが人だとは言わない。**
;;   - **窓が閉じたことが見えなくなる。** `:corpus/window-days` が消えると、
;;     取り逃した日があることを読み手が知る手段が無くなる。
;;   - **名寄せの分母が消える。** `:corpus/ambiguous-count` が無いと、法人番号の
;;     付いた件数が「全部結び付いた」に読める。
;;
;; ## exit 0 を信用しない
;;
;; 検査器は 0 clean / 1 violations / **2 could-not-answer**（data/ が無い、
;; 読めない）を返す。2 を pass に丸めない。
;;
;; ノード側で `npx nbb kanpou-projections-check.cljs <dir>` として実行。
(ns fleet-ci.gates.kanpou-projections-check
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
    (= 0 code) (println "FLEET-CI: kanpou projections OK")
    (= 2 code) (die! 2 "verifier could not answer — treat as red, not as a pass")
    :else (die! 1 "kanpou projections have violations"))
  (js/process.exit (or code 90)))

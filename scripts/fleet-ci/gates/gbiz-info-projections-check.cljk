#!/usr/bin/env nbb
;; gbiz-info-projections-check.cljs — the gBizINFO 全件 projections, checked on every tip.
;;
;; **検証ロジックはここに複製しない。** 正本は repo 内の
;; `scripts/verify-projections.cljs`（nta-projections-check.cljs と同じ形）。
;; fleet 側と手回し側で別実装を持つと、片方だけ通る状態が黙って生まれる。
;;
;; ## なぜ tip ごとに回すのか
;;
;; **この repo のファイルは形が 2 つあり、片方に合わせた検査は他方に黙る。**
;; `*-joined` は section ごとに manifest を 1 本ずつ持ち、`*-summary` は畳んだ側で
;; manifest 1 本。腐り方:
;;
;;   - **分母が消える。** 一致行だけを列挙する projection は、読んだ全行
;;     （`:corpus/record-count`）と一致した行（`:projection/matched-rows`）の両方が
;;     無いと「gBizINFO にはこれだけしか無い」と読める。
;;   - **畳んだ側が畳む前を忘れる。** 実測 2026-08-19、summary は manifest 行を
;;     **1 本も持っておらず**、2,358 件の集計が 125,144 行の fold だと読めなかった。
;;   - **publish の鮮度が読めない。** sha256 と日付付き `:source/publish` が
;;     無いと、古い publish から切った projection が「過去についての正しい答え」を
;;     返し続ける。
;;   - **指し先が消える。** summary は sha256 を複製せず兄弟ファイルを名前で指す。
;;     指し先の実在と section 網羅を検査する —— 誰も読まない key は嘘の飾りになる。
;;
;; ## exit 0 を信用しない
;;
;; 検査器は 0 clean / 1 violations / **2 could-not-answer**（data/ が無い、
;; 読めない）を返す。2 を pass に丸めない。
;;
;; ノード側で `npx nbb gbiz-info-projections-check.cljs <dir>` として実行。
(ns fleet-ci.gates.gbiz-info-projections-check
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
    (= 0 code) (println "FLEET-CI: gbiz-info projections OK")
    (= 2 code) (die! 2 "verifier could not answer — treat as red, not as a pass")
    :else (die! 1 "gbiz-info projections have violations"))
  (js/process.exit (or code 90)))

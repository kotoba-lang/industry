#!/usr/bin/env nbb
;; company-web-presence-projections-check.cljs — the committed public-surface
;; projections, checked on every tip.
;;
;; **検証ロジックはここに複製しない。** 正本は repo 内の
;; `scripts/verify-projections.cljs`（nta-projections-check.cljs と同じ形）。
;; fleet 側と手回し側で別実装を持つと、片方だけ通る状態が黙って生まれる。
;;
;; ## なぜ tip ごとに回すのか
;;
;; この 3 つの projection は**ノードの cell が毎日書き換える**。人がレビューする
;; 経路が無いので、腐り方は artifact の側でしか捕まえられない:
;;
;;   - **個人が混ざる。** 汎用 `.jp` の登録者は自然人でありうるし、リリースの
;;     会社概要カードは住所のすぐ隣に代表者名を置いている。**ファイルの中の何も**
;;     それを個人だと言わない。混入を禁じている規則は collector の中に在り、
;;     collector を通らずに commit された artifact には効かない。
;;   - **分母が消える。** `:projection/queried` が無いと、41 feed が
;;     「41 社が発信している」に読める。
;;   - **件数がずれる。** 途中で終わった run も manifest 行と正しい形のレコードを
;;     残す。食い違うのは数だけ。
;;
;; ## exit 0 を信用しない
;;
;; 検査器は 0 clean / 1 violations / **2 could-not-answer**（data/ が無い、
;; 読めない）を返す。2 を pass に丸めない。
;;
;; ノード側で `npx nbb company-web-presence-projections-check.cljs <dir>` として実行。
(ns fleet-ci.gates.company-web-presence-projections-check
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
    (= 0 code) (println "FLEET-CI: company-web-presence projections OK")
    (= 2 code) (die! 2 "verifier could not answer — treat as red, not as a pass")
    :else (die! 1 "company-web-presence projections have violations"))
  (js/process.exit (or code 90)))

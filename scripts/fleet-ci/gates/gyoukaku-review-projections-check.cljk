#!/usr/bin/env nbb
;; gyoukaku-review-projections-check.cljs — the 行政事業レビュー projection, checked on every tip.
;;
;; **検証ロジックはここに複製しない。** 正本は repo 内の
;; `scripts/verify-projections.cljs`（nta-projections-check.cljs と同じ形）。
;; fleet 側と手回し側で別実装を持つと、片方だけ通る状態が黙って生まれる。
;;
;; ## なぜ tip ごとに回すのか
;;
;; これは**畳んだ側**（会社 × 府省、3,463）で、畳む前は面の番号に一致した 17,808 行、
;; 全体では 80,319 行。腐り方:
;;
;;   - **畳む前を忘れる。** `:projection/folded-from` と `:projection/recipients-seen`
;;     が消えると、3,463 が「国の支出先はこれで全部」に読める。
;;   - **落とした数が消える。** 組織だと言えず落とした 6,776 件を黙らせると、規則が
;;     厳しすぎた月にそれが分からない（実測 2026-08-19、最初の規則は 13,669 件を
;;     落としており、中身は協議会・センター・法務局だった）。
;;   - **単位が混ざる。** ここは百万円で gBizINFO は円。円の属性が現れたら赤にする。
;;
;; ## exit 0 を信用しない
;;
;; 検査器は 0 clean / 1 violations / **2 could-not-answer**（data/ が無い、
;; 読めない）を返す。2 を pass に丸めない。
;;
;; ノード側で `npx nbb gyoukaku-review-projections-check.cljs <dir>` として実行。
(ns fleet-ci.gates.gyoukaku-review-projections-check
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
    (= 0 code) (println "FLEET-CI: gyoukaku-review projections OK")
    (= 2 code) (die! 2 "verifier could not answer — treat as red, not as a pass")
    :else (die! 1 "gyoukaku-review projections have violations"))
  (js/process.exit (or code 90)))

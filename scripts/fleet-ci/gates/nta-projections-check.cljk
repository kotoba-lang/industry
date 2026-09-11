#!/usr/bin/env nbb
;; nta-projections-check.cljs — the committed NTA projections, checked on every tip.
;;
;; **検証ロジックはここに複製しない。** 正本は repo 内の
;; `scripts/verify-projections.cljs` で、この gate は展開済み tree に対してそれを
;; 呼ぶだけ（itonami-maturity-freshness-check.cljs と同じ形）。fleet 側と手回し側で
;; 別実装を持つと、片方だけ通る状態が黙って生まれる。
;;
;; ## なぜ tip ごとに回すのか
;;
;; この projection は月次で切り直される node 側の産物で、**腐っても誰も気づかない**:
;;
;;   - authority は毎月新しい publish を出す。古い publish から切った projection は
;;     「過去についての、完全に正しい答え」を返し続ける。
;;   - stream が途中で終わった projection も、manifest 行と正しい形のレコードを持つ。
;;     食い違うのは件数だけ。
;;   - invoice projection に個人事業主の登録が混ざっても、**ファイルの中の何も**
;;     それを個人だと言わない。混入を禁じている規則は projector の中に在り、
;;     projector を通らずに commit された artifact には効かない。
;;
;; 3 つとも「健康なファイル」と同じ顔をするので、artifact 側で検査する。
;;
;; ## exit 0 を信用しない
;;
;; 検査器は 0 clean / 1 violations / **2 could-not-answer**（data/ が無い、読めない）
;; を返す。2 を pass に丸めない —— 「検査対象が無かった」と「検査して問題無し」が
;; 同じ出力になるのが、この class の欠陥そのものである。
;;
;; ノード側で `npx nbb nta-projections-check.cljs <dir>` として実行。
(ns fleet-ci.gates.nta-projections-check
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

;; 展開失敗による false-pass を構造的に防ぐ。90 は「tree が期待どおり届いていない」
;; を表す tick.cljs 側の慣習。
(when-not (fs/existsSync verifier)
  (die! 90 "scripts/verify-projections.cljs missing after extract"))
(when-not (fs/existsSync data-dir)
  (die! 90 "data/ missing after extract — the projections are the subject of this gate"))

(def result
  (try
    {:out (cp/execSync "npx --yes nbb scripts/verify-projections.cljs ."
                       #js {:cwd root :encoding "utf8" :stdio "pipe" :timeout 600000})
     :code 0}
    (catch :default e
      {:out (str (or (.-stdout e) "") (or (.-stderr e) ""))
       :code (or (.-status e) 1)})))

(println (str/trim (str (:out result))))

(let [out (str (:out result))
      scanned (re-find #"SCANNED\t(\d+) file\(s\)\t(\d+) record\(s\)" out)]
  (cond
    (= 2 (:code result))
    (die! 92 "verify-projections could not answer (no data/*.datoms.edn, or a file it could not read)")

    ;; ⚠ この分岐は `(pos? code)` より**手前**でなければならない。後ろに置くと
    ;; 「検証器が起動できなかった」非 0 が「違反があった」として報告される ——
    ;; 実測 2026-08-19、operator 機の npx が壊れていて同型の gate がそう言った。
    ;; **走らなかったことと、走って違反を見つけたことは別の結論。** 床は最初から
    ;; 在ったが、非 0 の分岐の裏にいて到達できなかった。
    (nil? scanned)
    (die! 93 "no SCANNED line in output — the verifier did not run; this is not a violation report")

    (pos? (:code result))
    (die! 1 "verify-projections reported violations")

    (zero? (js/parseInt (second scanned) 10))
    (die! 94 "zero projection files scanned")

    :else
    (println (str "FLEET-CI: NTA projections OK (" (second scanned) " files, "
                  (nth scanned 2) " records)"))))

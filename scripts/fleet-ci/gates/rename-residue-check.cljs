#!/usr/bin/env nbb
;; rename-residue-check.cljs — cleanup-land の「改名残骸 gate」が生きているかを tip ごとに測る。
;;
;; **検証ロジックはここに複製しない。** 正本は repo 内の
;; `scripts/rename-residue-test.cljs` で、この gate は展開済み tree に対してそれを
;; 呼ぶだけ（itonami-maturity-freshness-check.cljs と同じ形）。fleet 側と手回し側で
;; 別実装を持つと、片方だけ通る状態が黙って生まれる。
;;
;; ## なぜこれが要るか
;;
;; これは **壊れても誰も気づかない**種類の検査である。分類器が沈黙して全部を
;; :wip に倒しても、cleanup-land は今までどおり exit 0 で `plan :additive N files`
;; と印字し、PR は開き、merge も通る。壊れていることが観測できるのは、
;; **次にディレクトリ改名が起きた後**に main へ古いファイルが戻ってきた時だけで、
;; 実測ではそれに 6 日かかり（改名 08-03/08-04 → 発覚 08-12）、その間に
;; 2 回の cleanup が 17 ファイルを戻した（net-kotobase/control-plane、
;; manifest/cleanup-workflow.edn の :residue-gate）。
;;
;; つまり fail は静かで、遅れて、別の症状として現れる。tip ごとに回す価値がある。
;;
;; ## exit 0 を信用しない
;;
;; classpath 破壊等で「何も検証していないのに exit 0」になる経路があるので、
;; summary 行（`N cases OK`）の存在と N が期待本数以上あることを assert する。
;; 単に N>0 にすると、fixture repo を建てられない環境（git 不在）で pure case
;; だけが走り、実 git に対する判定が 1 本も動いていないのに緑になる。
;;
;; ノード側で `npx nbb rename-residue-check.cljs <dir>` として実行。
;; ⚠ `<dir>` は引数の**先頭**に置く（CLAUDE.md「赤い gate を直す前に 3 つ確かめる」2）。
(ns fleet-ci.gates.rename-residue-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

(defn- die! [code & msg]
  (println (str "FLEET-CI: " (str/join " " msg)))
  (js/process.exit code))

;; pure case 13 + fixture case 8 = 21。fixture が丸ごと落ちても pure だけで
;; 緑にならないよう、床を fixture を含む本数に置く。
(def min-cases 21)

(def test-entry (path/join root "scripts" "rename-residue-test.cljs"))
(def gate-ns (path/join root "scripts" "rename_residue.cljs"))
(def compat-dir (path/join root "scripts" "nbb_compat"))

;; 展開失敗による false-pass を構造的に防ぐ。90 は「tree が期待どおり届いていない」
;; を表す tick.cljs 側の慣習。
(when-not (fs/existsSync test-entry)
  (die! 90 "scripts/rename-residue-test.cljs missing after extract"))
(when-not (fs/existsSync gate-ns)
  (die! 90 "scripts/rename_residue.cljs missing after extract"))
(when-not (fs/existsSync compat-dir)
  (die! 90 "scripts/nbb_compat missing after extract — classpath would be broken"))

(def result
  (try
    (cp/execSync "npx --yes nbb --classpath \".:scripts/nbb_compat\" scripts/rename-residue-test.cljs"
                 #js {:cwd root :encoding "utf8" :stdio "pipe" :timeout 600000})
    (catch :default e
      (let [out (str (or (.-stdout e) "") (or (.-stderr e) ""))]
        (println (str/trim out))
        (die! 1 "rename-residue-test failed")))))

(println (str/trim (str result)))

(let [summary (re-find #"(\d+)\s+cases?\s+OK" (str result))
      n (some-> summary second (js/parseInt 10))]
  (cond
    (nil? summary)
    (die! 93 "no 'N cases OK' summary in output — refusing to report a pass")

    (< n min-cases)
    (die! 94 (str "only " n " cases ran, expected at least " min-cases
                  " — the git fixture half probably did not run"))

    :else
    (println (str "FLEET-CI: rename-residue gate OK (" n " cases)"))))

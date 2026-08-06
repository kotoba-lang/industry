#!/usr/bin/env nbb
;; permit-index-check.cljs — 法規制の datom 射影が正本とズレていないかの gate。
;; superproject ADR-2608080000。
;;
;; **検証ロジックはここに複製しない。** 正本は `scripts/gen-permit-index.cljs` の
;; `--check` で、この gate は展開済み tree に対してそれを呼ぶだけ。gate 側に
;; 判定を書くと fleet と repo で別実装になり、片方だけ通る状態が黙って生まれる
;; （west-pin-policy-check.cljs / repository-roles-check.cljs と同じ理由）。
;;
;; ## 何が守られるか
;;
;; `90-docs/regulatory/permits.datoms.edn` は各 actor の `facts.cljc` の射影で、
;; **正本は actor 側**。actor が法令や所管庁を直したのに射影を作り直していないと、
;; datom 面が古い規制を返し続ける —— しかも古い値も「それらしい」ので、
;; 読み手には見分けが付かない。ここが落ちるのはその状態。
;;
;; ## exit code だけを信用しない
;;
;; nbb の runner は失敗しても exit code を立てないことがあるので、出力の語を
;; 見る。「一致」が出ていなければ落とす —— 何も出力せず exit 0 で終わるのが
;; 一番危険（検査が 0 件走って合格に見える）。

(ns permit-index-check
  (:require [clojure.string :as str]))

(def cp (js/require "child_process"))
(def fs (js/require "fs"))

(def dir
  ;; argv[0] は node、argv[1] は nbb 自身のパス。gate の引数は **その次**から。
  ;; `(second argv)` を使って `/opt/homebrew/bin/nbb` を tree 根と解釈しかけた
  ;; （実測 2026-08-06）—— 引数の位置を推測せず、スクリプト自身のパスの後ろを取る。
  (let [argv (vec (js->clj js/process.argv))
        i (first (keep-indexed (fn [i a] (when (str/ends-with? (str a) "permit-index-check.cljs") i))
                               argv))]
    (or (when i (get argv (inc i))) ".")))

(defn -main []
  (let [gen (str dir "/scripts/gen-permit-index.cljs")
        out-file (str dir "/90-docs/regulatory/permits.datoms.edn")]
    (when-not (.existsSync fs gen)
      (println "permit-index-check: 生成器が無い（" gen "）— tree が不完全")
      (js/process.exit 1))
    (when-not (.existsSync fs out-file)
      (println "permit-index-check: 射影が無い（" out-file "）— 生成されていない")
      (js/process.exit 1))
    (let [r (.spawnSync cp "npx"
                        (clj->js ["nbb" "--classpath" ".:scripts"
                                  "scripts/gen-permit-index.cljs" "--check"])
                        #js {:encoding "utf8" :cwd dir :timeout 900000})
          out (str (aget r "stdout") (aget r "stderr"))
          code (aget r "status")]
      (println out)
      (cond
        (not= 0 code)
        (do (println "permit-index-check: FAIL — 射影が正本とズレている"
                     "（nbb --classpath \".:scripts\" scripts/gen-permit-index.cljs で再生成）")
            (js/process.exit 1))

        ;; **「一致」の語を実際に見る。** 出力が空のまま exit 0 は、検査が
        ;; 走っていないのと区別が付かない。
        (not (str/includes? out "一致"))
        (do (println "permit-index-check: FAIL — --check が一致を報告していない"
                     "（検査が走っていない可能性）")
            (js/process.exit 1))

        :else
        (do (println "permit-index-check: OK") (js/process.exit 0))))))

(-main)

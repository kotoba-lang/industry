#!/usr/bin/env nbb
;; west-pin-policy-check.cljs — west pin guard の policy gate。
;;
;; GitHub Actions の west-pin-verify.yml の **1 本目の job**（policy test）を
;; fleet 側に持つ。2 本目（変更 pin の server-side 検証）は port しない — 理由は
;; 下の「なぜ半分だけか」。
;;
;; **検証ロジックはここに複製しない。** 正本は repo 内の
;; `scripts/west-pin-guard-policy-test.cljs` で、この gate は展開済み tree に対して
;; それを呼ぶだけ。Actions 側と fleet 側で別実装を持つと、片方だけ通る状態が
;; 黙って生まれる（repository-roles-check.cljs と同じ理由）。
;;
;; ## なぜ半分だけか
;;
;; west-pin-verify.yml の 2 本目は「1 つ前の commit の west.yml を baseline に
;; して `verify-west-pins.cljs` を回す」job で、baseline の取得と pin の到達性
;; 判定に **GitHub API 認証が必要**。ノードに credential を置かないのは
;; fleet-ci の不変条件 3 なので、あれをノード側 gate として port することはできない。
;;
;; そして port する必要も無い: tick.cljs は CD の pin 前進の直前に既に
;; `scripts/verify-west-pins.cljs` を通している（README 不変条件 5）。つまり
;; server-side 検証は **operator 側に既にある**。欠けていたのは、認証の要らない
;; policy 層がどこからも実行されていなかったことだけで、それがこの gate。
;;
;; ## exit 0 を信用しない
;;
;; policy test が REPL に落ちる・classpath が壊れる等で「何も検証していないのに
;; exit 0」になる経路があるので、summary 行（`N cases OK`）の存在と N>0 を
;; assert する（jvm-test gate の `Ran N tests` と同じ床）。
;;
;; ノード側で `npx nbb west-pin-policy-check.cljs <dir>` として実行。
(ns fleet-ci.gates.west-pin-policy-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

(defn- die! [code & msg]
  (println (str "FLEET-CI: " (str/join " " msg)))
  (js/process.exit code))

(def policy-test (path/join root "scripts" "west-pin-guard-policy-test.cljs"))
(def compat-dir (path/join root "scripts" "nbb_compat"))

;; 展開失敗による false-pass を構造的に防ぐ（不変条件 2）。90 は
;; 「tree が期待どおり届いていない」を表す tick.cljs 側の慣習。
(when-not (fs/existsSync policy-test)
  (die! 90 "scripts/west-pin-guard-policy-test.cljs missing after extract"))
(when-not (fs/existsSync compat-dir)
  (die! 90 "scripts/nbb_compat missing after extract — classpath would be broken"))

(def result
  (try
    (cp/execSync "npx --yes nbb --classpath \".:scripts/nbb_compat\" scripts/west-pin-guard-policy-test.cljs"
                 #js {:cwd root :encoding "utf8" :stdio "pipe" :timeout 600000})
    (catch :default e
      ;; 落ちても出力は見せる（原因を receipt に残す）
      (let [out (str (or (.-stdout e) "") (or (.-stderr e) ""))]
        (println (str/trim out))
        (die! 1 "west-pin-guard-policy-test failed")))))

(println (str/trim (str result)))

(let [summary (re-find #"(\d+)\s+cases?\s+OK" (str result))]
  (cond
    (nil? summary)
    (die! 93 "no 'N cases OK' summary in output — refusing to report a pass")

    (zero? (js/parseInt (second summary) 10))
    (die! 94 "zero policy cases ran")

    :else
    (println (str "FLEET-CI: west-pin policy OK (" (second summary) " cases)"))))

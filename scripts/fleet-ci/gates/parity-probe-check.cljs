#!/usr/bin/env nbb
;; parity-probe-check.cljs — engine parity 台帳の probe の gate。
;;
;; 展開済みの superproject tree を受け取り、`90-docs/maturity/engine-parity.datoms.edn`
;; の各軸について「不在を主張する probe が、その主張を支える範囲を探しているか」を
;; 検証する。
;;
;; **検証ロジックはここに複製しない。** 正本は `scripts/verify-parity-probes.cljs`
;; で、この gate はノード上でそれを呼ぶだけ（repository-roles-check.cljs と同じ形）。
;; 片方だけ通る状態を黙って作らないため。
;;
;; なぜこの gate が要るか: 2026-08-04〜06 の 5 回、台帳は「無い」と書いて 5 回とも
;; 誤っていた。毎回手で訂正したが、訂正の次の反復で同じ種類の誤りが起きた。
;; CLAUDE.md には既に「結論する前に検索せよ」が 2 箇所ある —— prose では止まらな
;; かったので、probe の**形**を機械で検査する。
;;
;; ネットワーク: 不要（tree 内の .edn を読むだけ）。
;;
;; ノード側で `npx nbb parity-probe-check.cljs <dir> [--min N]` として実行。
(ns fleet-ci.gates.parity-probe-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))

(defn- flag [name default]
  (let [i (.indexOf args name)]
    (if (neg? i) default (nth args (inc i)))))

(def min-probed (flag "--min" "30"))

(def root (or (first (remove #(str/starts-with? % "--")
                             (remove #{min-probed} args)))
              "."))

(def verifier (path/join root "scripts" "verify-parity-probes.cljs"))
(def ledger (path/join root "90-docs" "maturity" "engine-parity.datoms.edn"))

(when-not (fs/existsSync verifier)
  (println "FAIL verifier not in the shipped tree:" verifier)
  (println "  (:include-ext must carry .cljs, or this gate cannot run the authority)")
  (js/process.exit 1))

(when-not (fs/existsSync ledger)
  (println "FAIL parity ledger not in the shipped tree:" ledger)
  (js/process.exit 1))

(let [r (try (cp/execFileSync "npx" (clj->js ["--yes" "nbb" verifier ledger
                                              "--min" min-probed])
                              #js {:encoding "utf8" :cwd root :maxBuffer 33554432})
             (catch :default e
               {:err (or (.-status e) 1)
                :out (str (or (some-> (.-stdout e) str) "")
                          (or (some-> (.-stderr e) str) ""))}))]
  (if (string? r)
    (print r)
    (do (print (:out r))
        (js/process.exit (:err r)))))

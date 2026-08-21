#!/usr/bin/env nbb
;; capital-pools-check.cljs — 資本 pool の生成物 gate。
;;
;; 展開済みの superproject tree を受け取り、`90-docs/business/capital-pools.edn` が
;; `90-docs/business/budget-supply.edn` の `:supply/pools` から**今も**導かれることを
;; 検証する。
;;
;; **検証ロジックはここに複製しない。** 正本は `scripts/verify-capital-pools.cljs`
;; で、この gate はノード上でそれを呼ぶだけ（parity-probe-check.cljs と同じ形）。
;; 片方だけ通る状態を黙って作らないため。
;;
;; なぜこの gate が要るか。2026-08-06 に capital-pools.edn を導入したとき、これを
;; 検査するものが 1 つも無かった —— `manifest/projections/` の contract は 2 件
;; （agent-source-query / training-corpus）だけで business 系は 0 件、
;; `scripts/fleet-ci/gates.edn` にも budget-supply / capital-pools を見る entry が
;; 無かった。生成物を出しておいて検査を付けないのは、`gftd allocate pools md` を
;; 回し忘れた状態と回した状態を区別できないということで、**pool の tranche 化が
;; 解こうとした「予算がある」と「今それを配れる」の混同が、別の場所で再発する**。
;;
;; projection-verify（`:canonical-edn-v1`）で代替しない理由は
;; `scripts/verify-capital-pools.cljs` の冒頭に書いた —— 要点は、あれが捕まえるのは
;; 生成物の手編集だけで、**budget-supply.edn だけが動いた場合を捕まえない**こと。
;; T2 を release する編集は budget-supply.edn 側にしか現れないので、実運用で最初に
;; 起きるドリフトがちょうど死角に入る。
;;
;; ネットワーク: 不要（tree 内の .edn を読むだけ）。
;;
;; ノード側で `npx nbb capital-pools-check.cljs <dir>` として実行。
(ns fleet-ci.gates.capital-pools-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))

(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

(def verifier (path/join root "scripts" "verify-capital-pools.cljs"))
(def supply (path/join root "90-docs" "business" "budget-supply.edn"))
(def pools (path/join root "90-docs" "business" "capital-pools.edn"))

(doseq [[label p hint]
        [["verifier" verifier "(:include-ext に .cljs が要る。無いと gate が正本を呼べない)"]
         ["budget-supply.edn" supply "(:include-ext に .edn が要る)"]
         ["capital-pools.edn" pools "(:include-ext に .edn が要る)"]]]
  (when-not (fs/existsSync p)
    (println (str "FAIL " label " が配布 tree に無い: " p))
    (println (str "  " hint))
    (js/process.exit 1)))

(let [r (try (cp/execFileSync "npx" (clj->js ["--yes" "nbb" verifier "--root" root])
                              #js {:encoding "utf8" :cwd root :maxBuffer 33554432})
             (catch :default e
               {:err (or (.-status e) 1)
                :out (str (or (some-> (.-stdout e) str) "")
                          (or (some-> (.-stderr e) str) ""))}))]
  (if (string? r)
    (print r)
    (do (print (:out r))
        (js/process.exit (:err r)))))

#!/usr/bin/env nbb
;; adr-identity-check.cljs — `:adr/id` が 1 つの文書を指すことの gate。
;;
;; **検証ロジックはここに複製しない。** 正本は `scripts/verify-adr-identity.cljs`
;; で、この gate は展開済み tree に対してそれを呼ぶだけ
;; （capital-pools-check.cljs / rename-residue-check.cljs と同じ形）。fleet 側と
;; 手回し側で別実装を持つと、片方だけ通る状態が黙って生まれる。
;;
;; ## なぜこの gate が要るか
;;
;; 2026-08-07、`:adr/id "2608080000"` を datom 面に問い合わせると **2 つの別々の
;; ADR が返った**。このワークスペースの ADR は互いを `:adr/related` で常時参照して
;; おり、id が 2 つの文書に解決すると**その参照が何を指すか構造的に決まらなくなる**。
;; 壊れ方は静かで、壊れたことは参照を辿ろうとした時にしか現れない。
;;
;; そして 2026-08-08 に、まさにそれが 2 件増えた（`2608082000` / `2608089000`）。
;; 増えたことに気づいたのは 4 日後で、その間 gate は 1 つも無かった。
;; **規則ではなく機械検査が足りていない**種類の欠陥である。
;;
;; ## なぜ root gate として成立するか（大半の検査は成立しない）
;;
;; ADR-2608124800 が全 verifier を棚卸ししたとおり、`scripts/verify-*.cljs` の
;; 多くは `orgs/` 配下の子リポを読むので fleet gate にできない —— fleet が配るのは
;; **その repo 自身の tree だけ**で、west 管理の `orgs/` は入らない（`git ls-files
;; orgs/cloud-itonami` は 0 件）。この検査は `90-docs/adr` しか読まないので、
;; 構造的な障害なしに今日 gate にできる数少ない 1 本。
;;
;; ## exit 0 を信用しない
;;
;; 「何も検査していないのに exit 0」を 3 重に塞ぐ:
;;
;;   1. `:min-files 1500`（gates.edn）—— tick.cljs が **tarball を作る前に**
;;      reject する。:include-ext が壊れて 90-docs/adr が落ちた tree は、
;;      gate が起動する前に入力ごと拒否される。
;;   2. この gate —— verifier 本体と `90-docs/adr` の存在を extract 後に確認し、
;;      無ければ exit 90（tick.cljs 側の「tree が期待どおり届いていない」慣習）。
;;   3. この gate —— verifier の要約行 `verify-adr-identity: ADR N …` の存在と
;;      N >= --min を assert する。verifier 自身も同じ床を持つが、**verifier が
;;      起動しなかった場合**（npx が壊れる等）は verifier の床は効かないので、
;;      呼び出し側でも測る。
;;
;; ネットワーク: 不要（tree 内の .edn を読むだけ）。
;;
;; ノード側で `npx nbb adr-identity-check.cljs <dir> --min 1400` として実行。
;; ⚠ `<dir>` は引数の**先頭**に置く（CLAUDE.md「赤い gate を直す前に 3 つ確かめる」2）。
(ns fleet-ci.gates.adr-identity-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))

(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

;; 読める ADR の下限。root の docs-edn-check gate が同じ corpus に対して使う
;; `--min 1400` に揃える（2026-08-12 実測 2,100 件）。
;; `<dir>` が先頭に来る呼び出し規約なので、位置に依らず `--min` の次を取る。
(def min-adrs (or (second (drop-while #(not= % "--min") args)) "1400"))

(defn- die! [code & msg]
  (println (str "FLEET-CI: " (str/join " " msg)))
  (js/process.exit code))

(def verifier (path/join root "scripts" "verify-adr-identity.cljs"))
(def adr-dir (path/join root "90-docs" "adr"))

;; 展開失敗による false-pass を構造的に防ぐ。90 は「tree が期待どおり届いて
;; いない」を表す tick.cljs 側の慣習。
(when-not (fs/existsSync verifier)
  (die! 90 "scripts/verify-adr-identity.cljs missing after extract"
        "— :include-ext に .cljs が要る（無いと gate が正本を呼べない）"))

(when-not (fs/existsSync adr-dir)
  (die! 90 "90-docs/adr missing after extract"
        "— :include-ext に .edn が要る。ADR corpus の無い tree で"
        "「衝突 0 件 = 合格」を報告させない"))

(def result
  (try
    {:code 0
     :out (cp/execFileSync "npx" (clj->js ["--yes" "nbb" verifier
                                           "--dir" adr-dir "--min" min-adrs])
                           #js {:encoding "utf8" :cwd root :maxBuffer 33554432})}
    (catch :default e
      {:code (or (.-status e) 1)
       :out (str (or (some-> (.-stdout e) str) "")
                 (or (some-> (.-stderr e) str) ""))})))

(print (:out result))

;; verifier が実際に corpus を走査したことを、呼び出し側でも測る。
;; verifier 自身の --min は verifier が**起動した**場合しか効かない。
(let [m (re-find #"verify-adr-identity: ADR (\d+)" (str (:out result)))]
  (when-not m
    (die! 93 "no `verify-adr-identity: ADR N` summary in output"
          "— refusing to report a pass for a run that may not have scanned anything"))
  (when (< (js/parseInt (second m)) (js/parseInt min-adrs))
    (die! 93 "verifier scanned only" (second m) "ADRs (floor" (str min-adrs ")")
          "— refusing to report a pass on an under-filled corpus")))

(js/process.exit (:code result))

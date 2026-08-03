#!/usr/bin/env nbb
;; PreToolUse(Bash) ガード: `git commit` / `git push` の前に、変更された
;; `90-docs/**/*.edn` をパースし、**このコミットで新たに壊れた**ものがあれば deny する。
;;
;; なぜ必要か。2026-08-02 の 1 日で `manifest/docs-edn-only.cljs verify` が 2 回赤に
;; なった。原因はどちらも同じで、`:adr/body` の本文に生の `"` が入って文字列が早期終了し、
;; map のフォーム数が奇数になる（あるいは stray key が生える）というもの。実例:
;; `"#ffffff"` / `"Connect wallet"` / `["https://…/app.js"]`。
;;
;; これは murakumo fleet の tick を止める。docs gate は tick の gate の 1 つなので、
;; 1 ファイルの引用符が fleet 全体の pin 前進を止める。実際に 2026-08-01 は
;; `2607321000` の 1 行が原因で tick が連続失敗していた。
;;
;; 手で直し続ける類ではない ── 書いた本人がその場で気づけないと再発する。だから
;; commit/push の直前に、その変更に含まれる edn だけを見る。
;;
;; 設計上の判断:
;;   * **新規の破壊だけを止める。** HEAD 側も壊れているファイルは素通しする。
;;     `known-parse-errors` に載っている既存の壊れたファイル（現時点で 7 件）を
;;     触っただけで作業が止まると、直す作業そのものができなくなる。
;;     本物の gate と同じ問いを立てる: 「今日、何かが壊れたか」。
;;   * **変更されたファイルだけを読む。** 本物の gate は 1,933 ファイルを走査して
;;     数分かかる。hook でそれをやると commit のたびに待たされて、結局外される。
;;   * fail-open。判定途中のあらゆる失敗で commit を通す（誤ブロック防止）。

(require '[cheshire.core :as json]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[scripts.nbb-compat :as compat])

(defn- sh
  "コマンドを実行し、成功時は stdout を、失敗時は nil を返す。"
  [dir & args]
  (try
    (let [{:keys [exit out]} (p/sh (into ["git" "-C" dir] (map str args)))]
      (when (zero? exit) out))
    (catch :default _ nil)))

(defn allow! [] (compat/exit 0))

(defn deny! [reason]
  (println (json/generate-string
             {:hookSpecificOutput
              {:hookEventName "PreToolUse"
               :permissionDecision "deny"
               :permissionDecisionReason reason}}))
  (compat/exit 0))

(defn- parse-problem
  "TEXT の edn としての問題を人間向け文字列で返す。問題なしなら nil。

  2 段階で見る。パース例外はもちろん捕まえるが、**パースが通っても壊れている**
  形がある ── 本文中の `\"` が 2 個そろうと引用符の釣り合いは保たれたまま文字列だけが
  途中で切れ、切れた先の語が map のキーとして生える。本物の gate が
  SPLIT STRINGS / stray keys と呼んでいるのがこれで、実際 `2607319600` は
  `:trust` が `\"trust\"` になる一文でそうなっていた。"
  [text]
  (try
    (let [form (edn/read-string text)]
      ;; ADR / datoms の形（tx-data = map の vector）の時だけキーを検査する。
      ;; ledger 系は 1 行 1 map で全体が vector ではないので、その場合は素通し。
      (when (and (vector? form) (seq form) (every? map? form))
        (when-let [bad (->> form
                            (mapcat keys)
                            (remove keyword?)
                            seq)]
          (str "map のキーに keyword でないものが混ざっています: "
               (str/join ", " (map pr-str (take 5 bad)))
               " ── 本文中の生の `\"` で文字列が途中で切れ、切れた先が"
               "キーとして読まれた形です（gate が SPLIT STRINGS と呼ぶもの）。"))))
    (catch :default e
      (str "edn としてパースできません: " (or (.-message e) (str e))))))

(defn- changed-edn-files
  "90-docs 配下で、この commit に含まれうる .edn の相対パス集合。"
  [top]
  (->> [(sh top "diff" "--name-only" "--" "90-docs")
        (sh top "diff" "--cached" "--name-only" "--" "90-docs")
        (sh top "ls-files" "-o" "--exclude-standard" "--" "90-docs")]
       (keep identity)
       (mapcat str/split-lines)
       (map str/trim)
       (filter #(str/ends-with? % ".edn"))
       distinct))

(defn- head-text
  "HEAD 側の内容。新規ファイル等で取れなければ nil。"
  [top path]
  (sh top "show" (str "HEAD:" path)))

(try
  (let [cmd (or (some-> (compat/read-stdin)
                        (json/parse-string true)
                        (get-in [:tool_input :command]))
                "")]
    ;; 対象は git commit / git push だけ。それ以外は即許可。
    (when-not (re-find #"\bgit\b(?:\s+-C\s+\S+)?\s+(?:commit|push)\b" cmd)
      (allow!))
    (let [top (some-> (sh "." "rev-parse" "--show-toplevel") str/trim)]
      (when (str/blank? top) (allow!))
      (let [broken
            (->> (changed-edn-files top)
                 (keep (fn [path]
                         (when-let [text (try (compat/slurp (str top "/" path))
                                              (catch :default _ nil))]
                           (when-let [problem (parse-problem text)]
                             ;; HEAD 側も壊れているなら既存の破損。素通しする。
                             (let [was (head-text top path)
                                   pre-existing? (and was (parse-problem was))]
                               (when-not pre-existing?
                                 [path problem]))))))
                 seq)]
        (when broken
          (deny!
            (str "90-docs の edn がこの変更で壊れています。commit/push を止めました。\n\n"
                 (str/join "\n" (for [[path problem] broken]
                                  (str "  " path "\n    " problem)))
                 "\n\nよくある原因は 1 つです: 文字列の本文に生の `\"` を書いたこと。"
                 "`\\\"` にエスケープしてください（例: `\"#ffffff\"` → `\\\"#ffffff\\\"`）。\n"
                 "確認: nbb --classpath \".:scripts/nbb_compat\" manifest/docs-edn-only.cljs verify\n"
                 "既存の壊れたファイル（known-parse-errors の 7 件）は対象外です ── "
                 "止めているのは『今日新たに壊れたもの』だけです。"))))
      (allow!)))
  (catch :default _ (compat/exit 0)))

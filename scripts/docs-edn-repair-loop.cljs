#!/usr/bin/env nbb
;; scripts/docs-edn-repair-loop.cljs — **ローカル Claude loop** の入口。
;; LaunchAgent `com.gftd.docs-edn-repair` が一定間隔でこれを起こす。
;;
;; ## 2 段構え（姉妹 loop と同じ）
;;
;;   1. `scripts/docs-edn-repair-tick.cljs` が「読めない文書」を**測る**（決定論）
;;   2. 候補があるときだけ `claude -p "/docs-edn-repair"` を起こす
;;
;; 順番が逆だと（先にモデルを起こして状態を調べさせると）、毎周 2,200 ファイルを
;; 走査し直し、しかも周ごとに違う基準で選ぶ。**探索は機械、判断と再構成はモデル。**
;;
;; ## なぜ 1 反復 = 1 文書か
;;
;; 対象は「生成器が壊した文書の**内容を再構成する**」仕事で、機械的な rewrite では
;; ない（2026-08-08 に 2 通りの機械修復を試し、7 件とも第 2 のバグで止まった）。
;; 再構成は取り違えると文書の意味を静かに変えるので、**1 件ずつ、検証を付けて、
;; 個別に着地させる**。まとめて直すと、どれが正しくてどれが取り違えかを後から
;; 分離できない。
;;
;; ## 不変条件
;;
;;   - **候補が 0 ならモデルを起こさない。** 無い仕事に token を使わない。
;;   - **走査が不十分（sparse checkout 等）ならモデルを起こさない。** 部分ビューは
;;     「部分である」と自己申告しない。`:insufficient-scan` は候補 0 とは別物。
;;   - **共有 checkout を書き換えない。** `git merge --ff-only` はしない —— 他
;;     セッションの未コミットが 1 個あるだけで loop が止まる（姉妹 loop が実測して
;;     やめた）。ここが見るのは「この checkout が origin/main から分岐していないか」
;;     だけで、実装側は `origin/main` から自分の worktree を切る。
;;   - ledger は追記のみ。捏造ゼロ（起こさなかったら :skipped と書く）。
;;   - **成否をここで判定しない。** 次周の tick が測り直す（自己申告の green を
;;     信用しない）。
;;   - exit 0 常に（監視 loop であって gate ではない）。
;;
;; 使い方:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/docs-edn-repair-loop.cljs
;;   ... --dry-run    ; 測って候補を出すところまで（モデルを起こさない）

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def os (js/require "node:os"))
(def cp (js/require "node:child_process"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-file (str home "/.itonami/docs-edn-repair.ledger.edn"))
(def tick-ledger (str home "/.itonami/docs-edn-repair-tick.ledger.edn"))
(def dry-run? (boolean (some #{"--dry-run"} *command-line-args*)))

(defn log! [& xs]
  (println (str (.toISOString (js/Date.)) " " (str/join " " (map str xs)))))

(defn- sh
  "走らせる。**投げない。**"
  [cmd args opts]
  (try
    (let [r (.spawnSync cp cmd (clj->js args)
                        (clj->js (merge {:encoding "utf8" :cwd root} opts)))]
      {:code (aget r "status") :out (str (aget r "stdout")) :err (str (aget r "stderr"))})
    (catch :default e {:code nil :out "" :err (str e)})))

(defn- append-ledger! [m]
  (try (.appendFileSync fs ledger-file (str (pr-str m) "\n"))
       (catch :default e (log! "ledger 追記に失敗:" (str e)))))

(defn- last-tick []
  (try
    (let [lines (->> (str (.readFileSync fs tick-ledger "utf8"))
                     str/split-lines (remove str/blank?))]
      (when (seq lines) (edn/read-string (last lines))))
    (catch :default _ nil)))

(defn -main []
  (let [started (.toISOString (js/Date.))]

    ;; 1) 分岐していないか。**共有 checkout は書き換えない。**
    (sh "git" ["fetch" "origin" "--quiet"] {})
    (let [{:keys [code]} (sh "git" ["merge-base" "--is-ancestor" "HEAD" "origin/main"] {})]
      (when (not= 0 code)
        (log! "この checkout は origin/main から分岐している。この周は何もしない。")
        (append-ledger! {:at started :outcome :skipped :why :diverged-from-main})
        (js/process.exit 0)))

    ;; 2) 測る（決定論。ここにモデルは居ない）
    (let [{:keys [out]} (sh "nbb" ["--classpath" ".:scripts/nbb_compat"
                                   "scripts/docs-edn-repair-tick.cljs"] {})]
      (println out))

    (let [tick (last-tick)
          cands (:candidates tick)]
      (cond
        (nil? tick)
        (do (log! "tick の ledger が読めない。この周は何もしない")
            (append-ledger! {:at started :outcome :skipped :why :no-tick-ledger}))

        ;; 部分ビューからの「もう無い」は嘘になる
        (= :insufficient-scan (:outcome tick))
        (do (log! "走査が不十分（" (:scanned tick) " < " (:min-edn tick) "）。モデルを起こさない")
            (append-ledger! {:at started :outcome :skipped :why :insufficient-scan
                             :scanned (:scanned tick)}))

        (empty? cands)
        (do (log! "読めない文書は 0 件。無い仕事にモデルを起こさない")
            (append-ledger! {:at started :outcome :skipped :why :no-candidates
                             :scanned (:scanned tick)}))

        dry-run?
        (do (log! "--dry-run: 次の候補は" (:path (first cands))
                  "（" (name (:status (first cands))) "）")
            (append-ledger! {:at started :outcome :dry-run :next (first cands)
                             :remaining (count cands)}))

        :else
        ;; 3) 再構成はモデル。**skill が手順書の正本**で、ここには書かない
        ;;    （2 箇所に手順があると必ず片方が古くなる）。
        (let [{:keys [code out err]}
              (sh "claude" ["-p" "/docs-edn-repair"
                            "--dangerously-skip-permissions"]
                  {:timeout 3600000})]
          (println out)
          (when (seq (str/trim (or err ""))) (log! "stderr:" (str/trim err)))
          (log! "claude 終了コード:" code)
          (append-ledger! {:at started
                           :finished (.toISOString (js/Date.))
                           :outcome (if (= 0 code) :ran :failed)
                           :exit code
                           :next-was (first cands)
                           :remaining-before (count cands)
                           ;; **直ったかはここでは判定しない。** 次周の tick が
                           ;; 測り直す —— 自己申告の green を信用しない。
                           :note "修復の成否は次周の tick が測る"}))))

    (js/process.exit 0)))

(-main)

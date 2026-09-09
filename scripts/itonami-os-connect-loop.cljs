#!/usr/bin/env nbb
;; scripts/itonami-os-connect-loop.cljs — **ローカル Claude loop** の入口
;; （superproject ADR-2608070000）。LaunchAgent cloud.itonami.bot.itonami-os-connect が
;; 一定間隔でこれを起こす。
;;
;; ## 姉妹 loop との違い: ここにはモデルが居る
;;
;; `cloud.itonami.bot.itonami-maturity-tick` / `cloud.itonami.bot.tsukuru-maturity-tick` は
;; 決定論的で、モデルを 1 度も呼ばない。この loop は違う —— **測るのは決定論、
;; 判断と実装は Claude Code**、という 2 段構えになっている:
;;
;;   1. `scripts/itonami-os-maturity-tick.cljs` が現在地と候補を**測る**
;;   2. 候補があるときだけ `claude -p "/itonami-os-connect"` を起こす
;;
;; 順番が逆だと（先にモデルを起こして状態を調べさせると）、毎周 4,000 repo を
;; 探索し直し、しかも周ごとに違う基準で選ぶ。**探索は機械の仕事、判断は
;; モデルの仕事**として分けてある。
;;
;; ## tamaki で包んでいない理由
;;
;; `tamaki exec` は「モデルが居ない」と主張する mode で、`tamaki local` は
;; 「kotoba-code に渡した」と主張する mode。**どちらもこの loop の事実と違う**
;; （launchd が Claude Code を headless で起こしている）。嘘の lifecycle を
;; event store に書くくらいなら、自分の ledger に正直に書く。
;;
;; ## 不変条件
;;
;;   - **候補が 0 ならモデルを起こさない。** 無い仕事に token を使わない。
;;   - **同期できなければ何もしない。** origin/main に FF できない状態で
;;     分岐を作ると、その反復の作業全部が古い base に載る（CLAUDE.md）。
;;   - ledger は追記のみ。捏造ゼロ（起こさなかったら :skipped と書く）。
;;   - exit 0 常に（監視 loop であって gate ではない）。
;;
;; 使い方:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/itonami-os-connect-loop.cljs
;;   ... --dry-run    ; 測って候補を出すところまで（モデルを起こさない）

(ns itonami-os-connect-loop
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "fs"))
(def os (js/require "os"))
(def cp (js/require "child_process"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-file (str home "/.itonami/itonami-os-connect.ledger.edn"))
(def tick-ledger (str home "/.itonami/itonami-os-maturity-tick.ledger.edn"))
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

(defn- last-tick
  "tick の ledger の最終行。tick 自身が書くので、ここでは**読むだけ**。"
  []
  (try
    (let [lines (->> (str (.readFileSync fs tick-ledger "utf8"))
                     str/split-lines
                     (remove str/blank?))]
      (when (seq lines) (edn/read-string (last lines))))
    (catch :default _ nil)))

(defn -main []
  (let [started (.toISOString (js/Date.))]

    ;; 1) 同期の確認。**共有 checkout を書き換えない。**
    ;;
    ;; 以前はここで `git merge --ff-only origin/main` していたが、これは
    ;; superproject の**共有 checkout を書き換える**ので、
    ;;   (a) 他セッションの未コミットが 1 個あるだけで FF が失敗し、この loop が
    ;;       止まる（実測 2026-08-05T18:38 以降 9 時間、`:not-fast-forwardable`
    ;;       で 1 周も回らなかった。原因は無関係な `scripts/maturity-loop/
    ;;       mutations.edn` と受信メールファイル）
    ;;   (b) 手で作業している人の tree と index.lock を奪い合う（実測 2026-08-06、
    ;;       同じ repo で作業中の別セッションと衝突した）
    ;;
    ;; そもそも**書き換える必要が無い**: tick は現在のファイルを読むだけで、
    ;; 実装側（skill）は `origin/main` から自分の worktree を切る。この loop が
    ;; 知りたいのは「この checkout が origin/main から分岐していないか」だけ。
    ;; 分岐していなければ、遅れていても skill 側は origin/main から始まる。
    (sh "git" ["fetch" "origin" "--quiet"] {})
    (let [{:keys [code]} (sh "git" ["merge-base" "--is-ancestor" "HEAD" "origin/main"] {})]
      (when (not= 0 code)
        (log! "この checkout は origin/main から分岐している。この周は何もしない。")
        (append-ledger! {:at started :outcome :skipped :why :diverged-from-main})
        (js/process.exit 0)))

    ;; 2) 測る（決定論。ここにモデルは居ない）
    (let [{:keys [code out]} (sh "nbb" ["--classpath" ".:scripts/nbb_compat"
                                        "scripts/itonami-os-maturity-tick.cljs"] {})]
      (when (not= 0 code) (log! "tick が非 0 で終了（測定は ledger を見る）"))
      (println out))

    (let [tick (last-tick)
          cands (:candidates tick)
          drift (:ops-drift tick)]

      (cond
        (nil? tick)
        (do (log! "tick の ledger が読めない。この周は何もしない")
            (append-ledger! {:at started :outcome :skipped :why :no-tick-ledger}))

        ;; 3) 面が嘘をついている状態で 1 本足しても嘘が増えるだけ
        (seq drift)
        (do (log! "宣言と actor の op がズレている。接続より先に直す:" (pr-str drift))
            (append-ledger! {:at started :outcome :skipped :why :ops-drift :drift drift}))

        (empty? cands)
        (do (log! "標準形の候補が 0 本。無い仕事にモデルを起こさない")
            (append-ledger! {:at started :outcome :skipped :why :no-candidates
                             :declared (:declared tick) :bound (:bound tick)}))

        dry-run?
        (do (log! "--dry-run: 次の候補は" (:repo (first cands)) "（" (:ns (first cands)) "）")
            (append-ledger! {:at started :outcome :dry-run :next (first cands)}))

        :else
        ;; 4) 判断と実装はモデル。**skill が手順書の正本**で、ここには書かない
        ;;    （2 箇所に手順があると必ず片方が古くなる）。
        (let [{:keys [code out err]}
              (sh "claude" ["-p" "/itonami-os-connect"
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
                           :declared (:declared tick)
                           :bound (:bound tick)
                           ;; **接続できたかはここでは判定しない。** 次の周の
                           ;; tick が :declared/:bound を測り直す —— 自己申告の
                           ;; green を信用しない（ADR-2607189300）。
                           :note "接続の成否は次周の tick が測る"}))))

    (js/process.exit 0)))

(-main)

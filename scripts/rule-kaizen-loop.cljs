#!/usr/bin/env nbb
;; scripts/rule-kaizen-loop.cljs — ローカル Claude loop の入口。
;; LaunchAgent `cloud.itonami.bot.rule-kaizen` が一定間隔でこれを起こす（未配置）。
;;
;; 2 段構え（姉妹 loop と同じ）:
;;   1. `scripts/rule-kaizen-tick.cljs` が候補を測る（決定論）
;;   2. 候補があるときだけ `claude -p /rule-kaizen` を起こす
;;
;; 1 反復 = 1 finding。2 件まとめない（ADR-2607189300）。
;; 手順の正本は skill。ここには書かない。
;;
;; 不変条件:
;;   - 候補が 0 ならモデルを起こさない。
;;   - :insufficient-scan ならモデルを起こさない。
;;   - 共有 checkout を書き換えない。git merge --ff-only はしない。
;;   - ledger は追記のみ。成否は次周の tick が測る。
;;   - exit 0 常に。
;;
;; usage:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/rule-kaizen-loop.cljs
;;   ... --dry-run

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def os (js/require "node:os"))
(def cp (js/require "node:child_process"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-file (str home "/.itonami/rule-kaizen.ledger.edn"))
(def tick-ledger (str home "/.itonami/rule-kaizen-tick.ledger.edn"))
(def dry-run? (boolean (some #{"--dry-run"} *command-line-args*)))

(defn log! [& xs]
  (println (str (.toISOString (js/Date.)) " " (str/join " " (map str xs)))))

(defn- sh [cmd args opts]
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
    (sh "git" ["fetch" "origin" "--quiet"] {})
    (let [{:keys [code]} (sh "git" ["merge-base" "--is-ancestor" "HEAD" "origin/main"] {})]
      (when (not= 0 code)
        (log! "この checkout は origin/main から分岐している。この周は何もしない。")
        (append-ledger! {:at started :outcome :skipped :why :diverged-from-main})
        (js/process.exit 0)))

    (let [{:keys [out]} (sh "nbb" ["--classpath" ".:scripts/nbb_compat"
                                   "scripts/rule-kaizen-tick.cljs"] {})]
      (println out))

    (let [tick (last-tick)
          next (:next tick)]
      (cond
        (nil? tick)
        (do (log! "tick の ledger が読めない。この周は何もしない")
            (append-ledger! {:at started :outcome :skipped :why :no-tick-ledger}))

        (= :insufficient-scan (:outcome tick))
        (do (log! "走査が不十分。モデルを起こさない")
            (append-ledger! {:at started :outcome :skipped :why :insufficient-scan
                             :listed (:listed tick)}))

        (nil? next)
        (do (log! "スナップショット候補は 0 件。無い仕事にモデルを起こさない")
            (append-ledger! {:at started :outcome :skipped :why :no-candidates
                             :listed (:listed tick)}))

        dry-run?
        (do (log! "--dry-run: 次の候補は" (:path next) "（" (name (:kind next)) "）")
            (append-ledger! {:at started :outcome :dry-run :next next
                             :remaining (:remaining tick)}))

        :else
        (let [{:keys [code out err]}
              (sh "claude" ["-p" "/rule-kaizen"
                            "--dangerously-skip-permissions"]
                  {:timeout 3600000})]
          (println out)
          (when (seq (str/trim (or err ""))) (log! "stderr:" (str/trim err)))
          (log! "claude 終了コード:" code)
          (append-ledger! {:at started
                           :finished (.toISOString (js/Date.))
                           :outcome (if (= 0 code) :ran :failed)
                           :exit code
                           :next-was next
                           :remaining-before (:remaining tick)
                           :note "成否は次周の tick が測る"}))))
    (js/process.exit 0)))

(-main)

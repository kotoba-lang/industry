#!/usr/bin/env nbb
;; scripts/isekai-game-dev-loop.cljs — isekai.network ゲーム制作 bot の
;; **ローカル Claude loop** 入口（ADR-2608291600）。LaunchAgent
;; cloud.itonami.bot.isekai-game-dev が 6 時間ごとに起こす。
;;
;; 2 段構え（姉妹 fleet-refactor-wave-loop.cljs / repo-bots/drain-loop.cljs と同型）:
;;   1. scripts/isekai-game-dev-tick.cljs が候補を**測る**（決定論）
;;   2. 候補があるときだけ `claude -p "/isekai-game-dev"` を起こす
;;
;; 手順の正本は skill `isekai-game-dev`。**ここには書かない**。
;;
;; ## モデルを起こさない条件
;;
;;   - origin/main から分岐している checkout（共有 checkout を壊さない）
;;   - :no-candidates（無い仕事にモデルを起こさない）
;;   - :not-measured（tick exit 2。**候補 0 件とは別の事実** —— 測れなかった周に
;;     モデルを起こしても直す対象が無いし、起こすと「測れなかった」が
;;     「仕事があった」の顔をする）
;;   - ロック中（前周の claude がまだ走っている）
;;
;; ledger は追記のみ。成否は次周の tick が測る（自分で成功と書かない）。
;; exit 0 常に（監視 loop であって gate ではない）。
;;
;;   nbb scripts/isekai-game-dev-loop.cljs [--dry-run]

(ns isekai-game-dev-loop
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))
(def os (js/require "node:os"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-dir (str home "/.itonami/isekai-game-dev"))
(def ledger-file (str ledger-dir "/ledger.edn"))
(def lock-file (str home "/.itonami/isekai-game-dev.lock"))
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
  (try (.mkdirSync fs ledger-dir #js {:recursive true})
       (.appendFileSync fs ledger-file (str (pr-str m) "\n"))
       (catch :default e (log! "ledger 追記失敗:" (str e)))))

(defn- lock-held? []
  (try
    (when (.existsSync fs lock-file)
      (let [pid (js/parseInt (str/trim (str (.readFileSync fs lock-file "utf8"))) 10)]
        (if (js/isNaN pid)
          (do (.unlinkSync fs lock-file) false)
          (try (.kill js/process pid 0) true
               (catch :default _ (.unlinkSync fs lock-file) false)))))
    (catch :default _ false)))

(defn- acquire-lock! [] (.writeFileSync fs lock-file (str (.-pid js/process) "\n")))
(defn- release-lock! [] (try (.unlinkSync fs lock-file) (catch :default _ nil)))

(defn- last-edn-line
  "tick の stdout の最終 EDN 行。読めなければ nil（0 件と同じ顔をさせない）。"
  [out]
  (let [lines (->> (str/split-lines (str out)) (remove str/blank?))]
    (when (seq lines)
      (try (edn/read-string (last lines)) (catch :default _ nil)))))

(defn -main []
  (let [started (.toISOString (js/Date.))]

    (when (lock-held?)
      (log! "前周がまだ走っている（lock）。この周は何もしない。")
      (append-ledger! {:at started :outcome :skipped :why :lock-held})
      (js/process.exit 0))

    (sh "git" ["fetch" "origin" "--quiet"] {})
    (let [{:keys [code]} (sh "git" ["merge-base" "--is-ancestor" "HEAD" "origin/main"] {})]
      (when (not= 0 code)
        (log! "この checkout は origin/main から分岐している。この周は何もしない。")
        (append-ledger! {:at started :outcome :skipped :why :diverged-from-main})
        (js/process.exit 0)))

    (let [{:keys [code out err]}
          (sh "nbb" ["scripts/isekai-game-dev-tick.cljs"] {:timeout 120000})
          tick (last-edn-line out)]
      (println (str/trim (str out)))
      (when (seq (str/trim (str err))) (log! "tick stderr:" (str/trim (str err))))
      (cond
        (= 2 code)
        ;; 測れなかった。**候補 0 件ではない** —— ledger にもその名前で書く。
        (do (log! "tick が測れなかった（exit 2）。この周はモデルを起こさない。")
            (append-ledger! {:at started :outcome :skipped :why :not-measured
                             :tick tick}))

        (nil? tick)
        (do (log! "tick の出力が読めない。この周は何もしない。")
            (append-ledger! {:at started :outcome :skipped :why :tick-unreadable
                             :exit code}))

        (= :no-candidates (:outcome tick))
        (do (log! "候補 0 件。無い仕事にモデルを起こさない。")
            (append-ledger! {:at started :outcome :skipped :why :no-candidates
                             :freshness (get-in tick [:freshness :verdict])}))

        (= :not-measured (:outcome tick))
        (do (log! "tick が :not-measured（exit は" code "）。モデルを起こさない。")
            (append-ledger! {:at started :outcome :skipped :why :not-measured
                             :tick tick}))

        dry-run?
        (do (log! "--dry-run: 次の候補は" (pr-str (:candidate tick)))
            (append-ledger! {:at started :outcome :dry-run :next (:candidate tick)}))

        :else
        (do
          (acquire-lock!)
          (try
            (let [{:keys [code out err]}
                  (sh "claude" ["-p" "/isekai-game-dev"
                                "--dangerously-skip-permissions"]
                      {:timeout 3600000})]
              (println out)
              (when (seq (str/trim (or err ""))) (log! "stderr:" (str/trim err)))
              (log! "claude 終了コード:" code)
              (append-ledger! {:at started
                               :finished (.toISOString (js/Date.))
                               :outcome (if (= 0 code) :ran :failed)
                               :exit code
                               :next-was (:candidate tick)
                               :note "成否は次周の tick が測る"}))
            (finally (release-lock!))))))

    (js/process.exit 0)))

(-main)

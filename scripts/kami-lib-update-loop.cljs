#!/usr/bin/env nbb
;; scripts/kami-lib-update-loop.cljs — kami family + core render/game lib の
;; **ライブラリ更新 bot（pin 前進）** のローカル Claude loop 入口
;; （ADR-2608291600）。LaunchAgent com.gftd.kami-lib-update が 6 時間ごとに起こす。
;;
;; 2 段構え（姉妹 isekai-game-dev-loop.cljs / fleet-refactor-wave-loop.cljs と同型）:
;;   1. scripts/kami-lib-update-tick.cljs が pin 鮮度を**測る**（決定論・有界 30 repo）
;;   2. 遅れた pin があるときだけ `claude -p "/kami-lib-update"` を起こす
;;
;; 手順の正本は skill `kami-lib-update`。**ここには書かない**。
;;
;; ## モデルを起こさない条件
;;
;;   - origin/main から分岐している checkout
;;   - :no-candidates（全 pin が tip。無い仕事にモデルを起こさない）
;;   - :not-measured（tick exit 2。rate limit 等で 1 repo も測れなかった周。
;;     **候補 0 件とは別の事実** —— 2026-08-12 の advance-pins 事故の再発防止）
;;   - ロック中（前周の claude がまだ走っている）
;;
;; ledger は追記のみ。成否は次周の tick が測る。exit 0 常に。
;;
;;   nbb scripts/kami-lib-update-loop.cljs [--dry-run]

(ns kami-lib-update-loop
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))
(def os (js/require "node:os"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-dir (str home "/.itonami/kami-lib-update"))
(def ledger-file (str ledger-dir "/ledger.edn"))
(def lock-file (str home "/.itonami/kami-lib-update.lock"))
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

(defn- last-edn-line [out]
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
          (sh "nbb" ["scripts/kami-lib-update-tick.cljs"] {:timeout 900000})
          tick (last-edn-line out)]
      (println (str/trim (str out)))
      (when (seq (str/trim (str err))) (log! "tick stderr:" (str/trim (str err))))
      (cond
        (= 2 code)
        (do (log! "tick が測れなかった（exit 2）。この周はモデルを起こさない。")
            (append-ledger! {:at started :outcome :skipped :why :not-measured
                             :tick (when tick (select-keys tick [:outcome :why :checked :not-measured]))}))

        (nil? tick)
        (do (log! "tick の出力が読めない。この周は何もしない。")
            (append-ledger! {:at started :outcome :skipped :why :tick-unreadable
                             :exit code}))

        (= :not-measured (:outcome tick))
        (do (log! "tick が :not-measured（exit は" code "）。モデルを起こさない。")
            (append-ledger! {:at started :outcome :skipped :why :not-measured}))

        (= :no-candidates (:outcome tick))
        (do (log! "遅れた pin 0 件（測定" (:fresh tick) "fresh /"
                  (count (:not-measured tick)) "unmeasured）。モデルを起こさない。")
            (append-ledger! {:at started :outcome :skipped :why :no-candidates
                             :checked (:checked tick)
                             :unmeasured (count (:not-measured tick))}))

        dry-run?
        (do (log! "--dry-run: lagging =" (count (:lagging tick)) "repo:"
                  (str/join " " (map :entry (:lagging tick))))
            (append-ledger! {:at started :outcome :dry-run
                             :lagging (mapv :entry (:lagging tick))}))

        :else
        (do
          (acquire-lock!)
          (try
            (let [{:keys [code out err]}
                  (sh "claude" ["-p" "/kami-lib-update"
                                "--dangerously-skip-permissions"]
                      {:timeout 3600000})]
              (println out)
              (when (seq (str/trim (or err ""))) (log! "stderr:" (str/trim err)))
              (log! "claude 終了コード:" code)
              (append-ledger! {:at started
                               :finished (.toISOString (js/Date.))
                               :outcome (if (= 0 code) :ran :failed)
                               :exit code
                               :lagging-was (mapv :entry (:lagging tick))
                               :note "成否は次周の tick が測る（pin 鮮度は再測定される）"}))
            (finally (release-lock!))))))

    (js/process.exit 0)))

(-main)

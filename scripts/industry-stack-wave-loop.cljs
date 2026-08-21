#!/usr/bin/env nbb
;; scripts/industry-stack-wave-loop.cljs — industry-stack flagship wave の
;; **ローカル Claude loop** 入口（ADR-2608090800）。
;; LaunchAgent com.gftd.industry-stack-wave が一定間隔で起こす。
;;
;; 2 段構え（姉妹 itonami-os-connect / itonami-maturity-improve と同型）:
;;   1. industry-stack-wave-tick.cljs が候補を**測る**（決定論）
;;   2. 候補があるときだけ `claude -p "/industry-stack-wave"` を起こす
;;
;; ## この loop がやること
;;
;; flagship item2（REAL actor build-time demo）を、render_html が無い
;; shape-matched isic に対して **最大 24 本並列**で進める。手順の正本は
;; skill `industry-stack-wave`（ここには書かない — 2 箇所に書くと必ず古い）。
;;
;; ## モデルを起こさない条件
;;
;;   - origin/main から分岐している checkout
;;   - 候補 0 本（プール枯渇）
;;   - ロック中（前周の claude がまだ走っている）
;;
;; exit 0 常に（監視 loop であって gate ではない）。
;;
;; 使い方:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/industry-stack-wave-loop.cljs
;;   ... --dry-run

(ns industry-stack-wave-loop
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "fs"))
(def cp (js/require "child_process"))
(def os (js/require "os"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-file (str home "/.gftd/industry-stack-wave.ledger.edn"))
(def tick-ledger (str home "/.gftd/industry-stack-wave-tick.ledger.edn"))
(def lock-file (str home "/.gftd/industry-stack-wave.lock"))
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
       (catch :default e (log! "ledger 追記失敗:" (str e)))))

(defn- last-tick []
  (try
    (let [lines (->> (str (.readFileSync fs tick-ledger "utf8"))
                     str/split-lines
                     (remove str/blank?))]
      (when (seq lines) (edn/read-string (last lines))))
    (catch :default _ nil)))

(defn- lock-held?
  "前周の claude が生きていれば true。stale lock（pid 死）は回収する。"
  []
  (try
    (when (.existsSync fs lock-file)
      (let [raw (str/trim (str (.readFileSync fs lock-file "utf8")))
            pid (js/parseInt raw 10)]
        (if (js/isNaN pid)
          (do (.unlinkSync fs lock-file) false)
          (try
            ;; kill -0: 存在確認
            (.kill js/process pid 0)
            true
            (catch :default _
              (.unlinkSync fs lock-file)
              false)))))
    (catch :default _ false)))

(defn- acquire-lock! []
  (.writeFileSync fs lock-file (str (.-pid js/process) "\n")))

(defn- release-lock! []
  (try (.unlinkSync fs lock-file) (catch :default _ nil)))

(defn -main []
  (let [started (.toISOString (js/Date.))]

    (when (lock-held?)
      (log! "前周がまだ走っている（lock）。この周は何もしない。")
      (append-ledger! {:at started :outcome :skipped :why :lock-held})
      (js/process.exit 0))

    ;; 共有 checkout を書き換えない。分岐していないかだけ見る。
    (sh "git" ["fetch" "origin" "--quiet"] {})
    (let [{:keys [code]} (sh "git" ["merge-base" "--is-ancestor" "HEAD" "origin/main"] {})]
      (when (not= 0 code)
        (log! "この checkout は origin/main から分岐している。この周は何もしない。")
        (append-ledger! {:at started :outcome :skipped :why :diverged-from-main})
        (js/process.exit 0)))

    (let [{:keys [code out]}
          (sh "nbb" ["--classpath" ".:scripts/nbb_compat"
                     "scripts/industry-stack-wave-tick.cljs"
                     "--limit" "24"] {})]
      (when (not= 0 code) (log! "tick が非 0（測定は ledger を見る）"))
      (println out))

    (let [tick (last-tick)
          cands (:candidates tick)]
      (cond
        (nil? tick)
        (do (log! "tick ledger が読めない")
            (append-ledger! {:at started :outcome :skipped :why :no-tick-ledger}))

        (empty? cands)
        (do (log! "候補 0 本。無い仕事にモデルを起こさない。pool=" (:pool tick))
            (append-ledger! {:at started :outcome :skipped :why :no-candidates
                             :pool (:pool tick)}))

        dry-run?
        (do (log! "--dry-run: pool=" (:pool tick)
                  " next=" (count cands) " first=" (:repo (first cands)))
            (append-ledger! {:at started :outcome :dry-run
                             :pool (:pool tick)
                             :n (count cands)
                             :next (mapv :repo cands)}))

        :else
        (do
          (acquire-lock!)
          (try
            (let [{:keys [code out err]}
                  ;; wave は 24 並列で長い。90 分上限（姉妹 maturity と同型）
                  (sh "claude" ["-p" "/industry-stack-wave"
                                "--allow-dangerously-skip-permissions"]
                      {:timeout 5400000})]
              (println out)
              (when (seq (str/trim (or err ""))) (log! "stderr:" (str/trim err)))
              (log! "claude 終了コード:" code)
              (append-ledger! {:at started
                               :finished (.toISOString (js/Date.))
                               :outcome (if (= 0 code) :ran :failed)
                               :exit code
                               :pool (:pool tick)
                               :n (count cands)
                               :next (mapv :repo (take 5 cands))
                               :note "成否は次周 tick の pool 減で測る（自己申告 green を信じない）"}))
            (finally (release-lock!))))))

    (js/process.exit 0)))

(-main)

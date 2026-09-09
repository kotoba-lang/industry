#!/usr/bin/env nbb
;; scripts/svelte-cljs-wave-loop.cljs — Svelte → cljs 移行波の **ローカル
;; Claude loop** 入口（ADR-2608260900）。LaunchAgent com.gftd.svelte-cljs-wave
;; が一定間隔で起こす。
;;
;; 2 段構え（姉妹 industry-stack-wave / itonami-os-connect と同型）:
;;   1. svelte-cljs-wave-tick.cljs が候補を**測る**（決定論・ネットワークは
;;      in-flight 判定だけ）
;;   2. 候補があるときだけ `claude -p "/svelte-cljs-wave"` を起こす
;;
;; 手順の正本は skill `svelte-cljs-wave`。**ここには書かない** —— 2 箇所に
;; 書くと必ず片方が古くなる。
;;
;; ## モデルを起こさない条件
;;
;;   - origin/main から分岐している checkout（共有 checkout を壊さない）
;;   - 候補 0 本（プール枯渇 = 移行完了、または全部 in-flight）
;;   - ロック中（前周の claude がまだ走っている）
;;   - tick が exit 2（測れなかった）—— **測れなかった周にモデルを起こさない**。
;;     0 と 2 を区別する意味がそこにある（ADR-2608136000）。
;;
;; exit 0 常に（監視 loop であって gate ではない）。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/svelte-cljs-wave-loop.cljs [--dry-run]

(ns svelte-cljs-wave-loop
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "fs"))
(def cp (js/require "child_process"))
(def os (js/require "os"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-file (str home "/.itonami/svelte-cljs-wave.ledger.edn"))
(def tick-ledger (str home "/.itonami/svelte-cljs-wave-tick.ledger.edn"))
(def lock-file (str home "/.itonami/svelte-cljs-wave.lock"))
(def dry-run? (boolean (some #{"--dry-run"} *command-line-args*)))

;; 並列度は build lock に合わせる。実測 2026-08-26: resource-guard の build lock は
;; 二本目を exit 2 で**拒否する**（待たせない）ので、6 本出しても同時に build できるのは
;; 1 本で、残りは retry 待ちで長く idle した。4 が測定済みの妥協点。
(def wave-size 4)

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
                     str/split-lines (remove str/blank?))]
      (when (seq lines) (edn/read-string (last lines))))
    (catch :default _ nil)))

(defn- lock-held?
  "前周の claude が生きていれば true。stale lock（pid 死）は回収する。"
  []
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
                     "scripts/svelte-cljs-wave-tick.cljs"
                     "--limit" (str wave-size)]
              {:timeout 600000})]
      (println out)
      ;; 測れなかった周にモデルを起こさない。tick の exit 2 は「答えられなかった」。
      (when (= 2 code)
        (log! "tick が測れなかった（exit 2）。この周はモデルを起こさない。")
        (append-ledger! {:at started :outcome :skipped :why :tick-inconclusive})
        (js/process.exit 0))
      (when (not= 0 code) (log! "tick が非 0（exit=" code "）")))

    (let [tick (last-tick)
          cands (:candidates tick)]
      (cond
        (nil? tick)
        (do (log! "tick ledger が読めない")
            (append-ledger! {:at started :outcome :skipped :why :no-tick-ledger}))

        (empty? cands)
        (do (log! "候補 0 本。無い仕事にモデルを起こさない。pool=" (:pool tick)
                  " in-flight-skipped=" (count (:in-flight-skipped tick)))
            (append-ledger! {:at started :outcome :skipped :why :no-candidates
                             :pool (:pool tick)
                             :in-flight (:in-flight-skipped tick)}))

        dry-run?
        (do (log! "--dry-run: pool=" (:pool tick) " next=" (count cands)
                  " first=" (:repo (first cands)))
            (append-ledger! {:at started :outcome :dry-run :pool (:pool tick)
                             :n (count cands) :next (mapv :repo cands)}))

        :else
        (do
          (acquire-lock!)
          (try
            (let [{:keys [code out err]}
                  ;; 1 波 = 4 並列 + pin 前進 + 再測定。実測で 1 repo 20〜30 分、
                  ;; build lock 待ちが乗るので 90 分上限（姉妹 wave と同型）。
                  (sh "claude" ["-p" "/svelte-cljs-wave"
                                "--dangerously-skip-permissions"]
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
                               :next (mapv :repo cands)
                               :note "成否は次周 tick の pool 減で測る（自己申告 green を信じない）"}))
            (finally (release-lock!))))))

    (js/process.exit 0)))

(-main)

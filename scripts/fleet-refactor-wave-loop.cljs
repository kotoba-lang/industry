#!/usr/bin/env nbb
;; scripts/fleet-refactor-wave-loop.cljs — cloud-itonami fleet refactor 波の
;; **ローカル Claude loop** 入口（ADR-2608290100）。LaunchAgent
;; com.gftd.fleet-refactor-wave が一定間隔で起こす。
;;
;; 2 段構え（姉妹 svelte-cljs-wave-loop.cljs と同型）:
;;   1. fleet-refactor-wave-tick.cljs が候補を**測る**（決定論・ネットワークは
;;      in-flight 判定だけ）
;;   2. 候補があるときだけ `claude -p "/fleet-refactor-wave"` を起こす
;;
;; 手順の正本は skill `fleet-refactor-wave`。**ここには書かない**。
;;
;; ## モデルを起こさない条件
;;
;;   - origin/main から分岐している checkout（共有 checkout を壊さない）
;;   - 候補 0 本（両 mission とも枯渇、または全部 in-flight）
;;   - ロック中（前周の claude がまだ走っている）
;;   - tick が exit 2（測れなかった）
;;
;; exit 0 常に（監視 loop であって gate ではない）。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/fleet-refactor-wave-loop.cljs [--dry-run]

(ns fleet-refactor-wave-loop
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "fs"))
(def cp (js/require "child_process"))
(def os (js/require "os"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-file (str home "/.itonami/fleet-refactor-wave.ledger.edn"))
(def tick-ledger (str home "/.itonami/fleet-refactor-wave-tick.ledger.edn"))
(def lock-file (str home "/.itonami/fleet-refactor-wave.lock"))
(def dry-run? (boolean (some #{"--dry-run"} *command-line-args*)))

;; svelte-cljs-wave の実測（resource-guard build lock は二本目を exit 2 で拒否）
;; と同じ理由で 4 に揃える。
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

    (let [classpath (str ".:scripts/nbb_compat:orgs/cloud-itonami/loop-fleet-refactor-wave/src")
          {:keys [code out]}
          (sh "nbb" ["--classpath" classpath
                     "scripts/fleet-refactor-wave-tick.cljs"
                     "--limit" (str wave-size)]
              {:timeout 600000})]
      (println out)
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
        (do (log! "候補 0 本。pool=" (:pool tick)
                  " mission-a-raw=" (:mission-a-raw tick)
                  " mission-b-raw=" (:mission-b-raw tick))
            (append-ledger! {:at started :outcome :skipped :why :no-candidates
                             :pool (:pool tick)}))

        dry-run?
        (do (log! "--dry-run: pool=" (:pool tick) " next=" (count cands))
            (append-ledger! {:at started :outcome :dry-run :pool (:pool tick)
                             :n (count cands) :next (mapv :repo cands)}))

        :else
        (do
          (acquire-lock!)
          (try
            (let [{:keys [code out err]}
                  (sh "claude" ["-p" "/fleet-refactor-wave"
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
                               :note "成否は次周 tick の pool/raw 減で測る（自己申告 green を信じない）"}))
            (finally (release-lock!))))))

    (js/process.exit 0)))

(-main)

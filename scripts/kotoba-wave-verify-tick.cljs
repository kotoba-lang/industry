#!/usr/bin/env nbb
;; scripts/kotoba-wave-verify-tick.cljs — Q9 wave-1 pilot の品質検証 tick。
;;
;; EDN 読みについて: tranche 記録（lang/q9-wave1-tranche-*.edn）は kotoba EDN
;; （Clojure EDN と同型の語族）。nbb 環境に kotoba.edn reader ns は無いので
;; clojure.edn で読む（kotoba EDN のサブセット構文のため同型読解が成立する）。
;; パース失敗は REFUSING (exit 2) — 読めない=測れていない、であって許可ではない。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/kotoba-wave-verify-tick.cljs \
;;     [--sample N] [--gate full|check-only]
;;
;; 頻度: fleet-refactor-wave-tick (1h) と同じ launchd リズムで回す。1 回の実行で
;; tranche 全 repo の .kotoba を 4 重 gate (kotoba -M check / test / compile / amu check)
;; で検証するが、時間がかかるため --sample N で N repo を抽選検証する（既定 5）。
;; 5 repo × 2 ファイル × 4 gate は約 4 分/回 なので、1h 間隔なら全 repo が約 4h で
;; 一巡する（rotating sample）。
;;
;; exit code:
;;   0  検証できた（全 green。sample でも同様）
;;   1  検証できたが regression（gate 失敗があった）
;;   2  測れなかった（tranche 記録が読めない / kotoba CLI 不在）

(ns kotoba-wave-verify-tick
  (:require [clojure.string :as str]
            [clojure.edn :as edn]))

(def cp (js/require "child_process"))
(def fs (js/require "fs"))
(def path (js/require "path"))

(def home (or (aget (.-env js/process) "HOME") "/Users/junkawasaki"))
(def root (str home "/github/com-junkawasaki"))
(def ledger-file (str home "/.gftd/kotoba-wave-verify.ledger.edn"))
(def amu-bin (str root "/orgs/kotoba-lang/amu/bin/amu"))
(def kotoba-bin (str root "/orgs/kotoba-lang/amu/bin/kotoba"))
(def authority-repo "orgs/kotoba-lang/kotoba-lang")

(def args (vec *command-line-args*))
(defn- arg [flag default]
  (if-let [i (first (keep-indexed #(when (= %2 flag) %1) args))]
    (nth args (inc i))
    default))
(def sample-n (js/parseInt (arg "--sample" "5") 10))
(def gate-mode (arg "--gate" "full")) ; full = 4 gate / check-only = check+amu

(defn- sh [cmd-args opts]
  (try
    (let [r (.spawnSync cp (first cmd-args) (clj->js (rest cmd-args))
                        (clj->js (merge {:encoding "utf8" :cwd root :timeout 120000} opts)))]
      {:code (aget r "status") :out (str (aget r "stdout")) :err (str (aget r "stderr"))})
    (catch :default e {:code nil :out "" :err (str e)})))

(defn- tranche-repos
  "q9-wave1-tranche-{1,2}.edn (origin/main) から pilot repo 一覧を取る。
  読めないなら nil（= 測れなかった）。"
  []
  (let [repos (atom #{})]
    (doseq [f ["lang/q9-wave1-tranche-1.edn" "lang/q9-wave1-tranche-2.edn"]]
      (let [{:keys [code out]} (sh ["git" "-C" authority-repo "show" (str "origin/main:" f)] {})]
        (when (= 0 code)
          (try
            (let [edn (edn/read-string out)]
              (doseq [p (mapv :repository (:pilots edn))]
                (when p (swap! repos conj (last (str/split p #"/"))))))
            (catch :default _ nil)))))
    (when (seq @repos) (vec (sort @repos)))))

(defn- kotoba-files [repo-dir]
  (let [{:keys [code out]} (sh ["find" (str repo-dir "/src") "-name" "*.kotoba"] {})]
    (when (= 0 code)
      (vec (->> (str/split-lines out) (remove str/blank?) sort)))))

(defn- verify-file [f]
  (let [abs (path.resolve root f)
        mk (fn [k cmd] [k (str/includes? (str (:out cmd) (:err cmd)) ":ok true")])]
    (let [ok? (fn [r] (and (= 0 (or (:code r) -1))
                           (str/includes? (str (:out r) (:err r)) ":ok true")))
        c (sh [kotoba-bin "-M" "check" abs] {})
        a (sh [amu-bin "check" abs "--jvm-free"] {})]
      (if (= "full" gate-mode)
        (let [t (sh [kotoba-bin "-M" "test" abs] {})
              m (re-find #"(\d+)/(\d+) passed" (str (:out t) (:err t)))
              ktest (if (and m (= (nth m 1) (nth m 2))) (str (nth m 1) "/" (nth m 2)) "FAIL")
              comp (sh [kotoba-bin "-M" "compile" abs "--target" "wasm32" "--output" "/tmp/kvt.wasm"] {})]
          {:kcheck (ok? c) :amu (ok? a)
           :ktest ktest :ktest-ok (not= "FAIL" ktest)
           :kcompile (ok? comp)})
        {:kcheck (ok? c) :amu (ok? a)}))))

(defn- file-green? [r]
  (every? (fn [[k v]] (if (boolean? v) v (not (str/starts-with? (str v) "FAIL"))))
          (select-keys r [:kcheck :amu :ktest :kcompile])))

(defn- deterministic-sample [repos n]
  ;; 時間窓ごとに決定的な回転 sample（同じ 1h 内は同じ set → 冪等、1h 違えば別 set）
  (if (<= (count repos) n)
    repos
    (let [bucket (quot (.getTime (js/Date.)) 3600000)
          start (mod bucket (count repos))]
      (vec (take n (drop start (cycle repos)))))))

(defn- append-ledger! [entry]
  (.appendFileSync fs ledger-file (str (pr-str entry) "\n")))

(defn -main []
  (when-not (and (fs.existsSync kotoba-bin) (fs.existsSync amu-bin))
    (println "REFUSING\tkotoba/amu CLI not found")
    (js/process.exit 2))
  (let [repos (tranche-repos)]
    (when-not repos
      (println "REFUSING\ttranche records unreadable from origin/main")
      (js/process.exit 2))
    (println (str "POOL\t" (count repos) "\tpilot repos"))
    (let [sample (deterministic-sample repos sample-n)
          results (mapv (fn [r]
                          (let [dir (str root "/orgs/kotoba-lang/" r)
                                ;; 検証は origin/main に同期してから（stale pin で測らない）
                                _ (sh ["git" "-C" dir "fetch" "origin" "-q"] {})
                                _ (sh ["git" "-C" dir "merge" "--ff-only" "origin/main" "-q"] {})
                                files (kotoba-files dir)
                                checks (mapv (fn [f]
                                               {:file (subs f (count dir))
                                                :res (verify-file f)})
                                             files)
                                green (and (seq checks) (every? :green (map (fn [c] {:green (file-green? (:res c))}) checks)))]
                            {:repo r :files (count files) :checks checks :green green}))
                        sample)
          n-green (count (filter :green results))]
      (doseq [r results]
        (println (str (:repo r) "\t" (if (:green r) "GREEN" "REGRESSION")))
        (doseq [c (:checks r)]
          (when-not (file-green? (:res c))
            (println (str "  NOT-GREEN\t" (:file c) "\t" (pr-str (:res c)))))))
      (append-ledger! {:at (.toISOString (js/Date.))
                       :sample sample
                       :gate-mode gate-mode
                       :green n-green
                       :total (count results)
                       :results results})
      (println (str "VERIFIED\t" n-green "/" (count results) "\tgreen (sample of " (count repos) ")"))
      (js/process.exit (if (= n-green (count results)) 0 1)))))

(-main)

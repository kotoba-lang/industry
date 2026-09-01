#!/usr/bin/env nbb
;; 常駐 agent が「走れる状態か」と「実際に走っているか」を別々に測る。
;;
;; ## なぜ要るか
;;
;; 実測 2026-09-01。`launchctl list` は 4 台の itonami/manimani agent に
;; `exit=78` を表示していた。78 は EX_CONFIG で、読むと「設定を直せばよい」に
;; 見える。実際には **WorkingDirectory が存在しないディレクトリを指しており、
;; launchd は何も書かずに即死していた** —— repo が `orgs/gftdcojp/` から
;; `orgs/network-awai/` へ移り、plist が追従していなかった。
;;
;; そのせいで 2 つの状態が同じ顔をする:
;;
;;   毎回 exec 前に死んでいる  →  exit=78、ログの mtime は最後に成功した日
;;   1 か月動いていない         →  exit=78、ログの mtime は最後に成功した日
;;
;; ログの最終更新は 2026-07-27〜29 で、**36 日**前だった。15 分周期の agent が
;; その間ずっと 78 を返し続けていた。直したのは 6 台。
;;
;; adr-2608301700 が記録した欠陥の裏返しである。あちらは「終わらない job が
;; 前回の exit を持ち続ける」、こちらは「**始まれない job が同じ値を返し続ける**」。
;;
;; ## この検査が出す 2 種類
;;
;;   cannot-start  WorkingDirectory / 実行ファイル / script が実在しない
;;   silent        exit は 0 だが、ログを N 日書いていない
;;
;; 後者が今日の手がかりだった。**exit だけを見ていると見えない。**
;;
;;   exit 0  全 agent が走れて、黙っている agent もいない
;;   exit 1  上のいずれかが在る
;;   exit 2  測れなかった（launchctl も plist も読めない等）
;;
;; usage: nbb scripts/verify-launchd-agents.cljs [--stale-days 7] [--findings]
(ns verify-launchd-agents
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.string :as str]))

(def argv (vec (drop 2 (.-argv js/process))))
(defn- flag-value [name default]
  (if-let [i (first (keep-indexed #(when (= %2 name) %1) argv))]
    (or (js/parseInt (nth argv (inc i) "") 10) default)
    default))
(def stale-days (flag-value "--stale-days" 7))
(def show-findings? (some #{"--findings"} argv))
(def home (or (aget (.-env js/process) "HOME") ""))
(def agents-dir (path/join home "Library" "LaunchAgents"))

(defn- sh [cmd]
  (try (.toString (cp/execSync cmd #js {:encoding "utf8" :stdio #js ["ignore" "pipe" "ignore"]}))
       (catch :default _ nil)))

(defn- plist-value [file key]
  ;; plutil -extract answers nothing for an absent key, which is what we want:
  ;; an agent with no WorkingDirectory is not broken, it simply has none.
  (some-> (sh (str "plutil -extract " key " raw " (pr-str file) " 2>/dev/null"))
          str/trim
          not-empty))

(defn- program-args [file]
  (loop [i 0 acc []]
    (if (> i 8)
      acc
      (if-let [v (plist-value file (str "ProgramArguments." i))]
        (recur (inc i) (conj acc v))
        acc))))

(defn- loaded-agents []
  ;; `launchctl list` columns: PID STATUS LABEL. A '-' PID means not currently
  ;; running, which is normal for an interval job between firings.
  (when-let [out (sh "launchctl list")]
    (->> (str/split-lines out)
         (drop 1)
         (keep (fn [line]
                 (let [[pid status label] (str/split line #"\t")]
                   (when (and label (not (str/blank? label)))
                     {:pid (str/trim (str pid))
                      :status (js/parseInt (str/trim (str status)) 10)
                      :label (str/trim label)}))))
         vec)))

(defn- mtime-days-ago [file]
  (try (let [ms (.getTime (.-mtime (fs/statSync file)))]
         (/ (- (js/Date.now) ms) 86400000.0))
       (catch :default _ nil)))

(defn- check [{:keys [label status]}]
  (let [file (path/join agents-dir (str label ".plist"))]
    (when (fs/existsSync file)
      (let [wd (plist-value file "WorkingDirectory")
            args (program-args file)
            program (first args)
            ;; The script is whatever argument looks like a path that should
            ;; resolve under the working directory.
            ;;
            ;; Measured 2026-09-01 on the first run of this check: 9 of 15
            ;; `cannot-start` findings were the value of `--classpath`
            ;; (`.:scripts/nbb_compat`), which contains a slash and is not a
            ;; flag, so a naive filter called it a missing script. A check that
            ;; cries wolf is worse than no check, so two things are excluded:
            ;; the argument immediately after any flag (it belongs to the
            ;; flag), and anything containing `:` (a classpath separator; a
            ;; script path does not carry one).
            script (->> (rest args)
                        (map vector (cons nil (rest args)))
                        (keep (fn [[prev v]]
                                (when (and (str/includes? v "/")
                                           (not (str/includes? v ":"))
                                           (not (str/starts-with? v "-"))
                                           (not (str/starts-with? v "/"))
                                           (not (and prev (str/starts-with? prev "-"))))
                                  v)))
                        first)
            out (plist-value file "StandardOutPath")
            log-age (some-> out mtime-days-ago)]
        (cond-> []
          (and wd (not (fs/existsSync wd)))
          (conj {:kind "cannot-start" :label label :detail (str "WorkingDirectory absent: " wd)})

          (and program (str/starts-with? program "/") (not (fs/existsSync program)))
          (conj {:kind "cannot-start" :label label :detail (str "program absent: " program)})

          (and wd script (fs/existsSync wd) (not (fs/existsSync (path/join wd script))))
          (conj {:kind "cannot-start" :label label
                 :detail (str "script absent under workdir: " script)})

          ;; exit 0 and nothing written for a long time. Not proof of a fault --
          ;; a weekly job is quiet by design -- but it is the shape that hid a
          ;; 36-day outage behind a plausible exit code, so it is reported and
          ;; the reader decides.
          (and (= 0 status) log-age (> log-age stale-days))
          (conj {:kind "silent" :label label
                 :detail (str "exit 0 but log untouched for "
                              (.toFixed log-age 1) " days: " out)}))))))

(defn- main []
  (let [agents (loaded-agents)]
    (if (empty? agents)
      (do (println "UNMEASURED\tlaunchctl-list-empty\tcould not read launchctl list")
          (.exit js/process 2))
      (let [findings (vec (mapcat check agents))
            cannot (filter #(= "cannot-start" (:kind %)) findings)
            silent (filter #(= "silent" (:kind %)) findings)]
        (println (str "SCANNED\t" (count agents)))
        (println (str "CANNOT-START\t" (count cannot)))
        (println (str "SILENT\t" (count silent) "\t(>" stale-days "d)"))
        (when show-findings?
          (doseq [f findings]
            (println (str "  " (:kind f) "\t" (:label f) "\t" (:detail f)))))
        (.exit js/process (if (seq findings) 1 0))))))

(main)

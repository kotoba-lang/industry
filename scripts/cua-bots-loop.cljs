#!/usr/bin/env nbb
;; scripts/cua-bots-loop.cljs — CUA bot（画面操作の常駐 bot）の loop 入口
;; （ADR-2608291900）。LaunchAgent com.gftd.cua-bots が 1 時間ごとに起こす。
;;
;; 2 段構え。ただし姉妹 loop（isekai-game-dev 等）と 1 点違う:
;; **この loop は `claude -p` を呼ばない。** モデルの解釈は
;; kotoba-lang/computer-use の session の**中**に居る（budget は名簿の
;; :bot/budget-tokens が縛る）。skip-permissions フラグはどこにも無い。
;;
;;   1. scripts/cua-bots-tick.cljs が名簿を検査し due を測る（決定論）
;;   2. due な bot ごとに、computer-use checkout から CLI を回す:
;;        nbb bin/cua_bot_run.cljs --roster <roster> --bot <id> --receipts-dir <dir>
;;      exit 0 = done / 1 = failed / 2 = could-not-measure（3 値のまま ledger へ。
;;      **could-not-measure を failed にも done にも畳まない**）
;;
;; checkout や bin entry が無い（lib がまだ landing していない）周は、その bot を
;; **:not-measured :why :lib-missing** として ledger に書く —— この loop は lib より
;; 先に land してよく、その間の周回が「全部 done」にも「全部 failed」にも見えては
;; ならない。
;;
;; ledger は追記のみ（1 bot 1 run = 1 行、skip も書く）。exit 0 常に。
;;
;;   nbb scripts/cua-bots-loop.cljs [--dry-run]
;;   （--dry-run は lib CLI にもそのまま渡る = validate + probe、行動しない）

(ns cua-bots-loop
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))
(def os (js/require "node:os"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def state-dir (str home "/.itonami/cua-bots"))
(def ledger-file (str state-dir "/ledger.edn"))
(def receipts-root (str state-dir "/receipts"))
(def lock-file (str home "/.itonami/cua-bots.lock"))
(def roster-file (str root "/manifest/cua-bots.edn"))
(def lib-dir (str root "/orgs/kotoba-lang/computer-use"))
(def lib-cli "bin/cua_bot_run.cljs")
(def dry-run? (boolean (some #{"--dry-run"} *command-line-args*)))

(defn log! [& xs]
  (println (str (.toISOString (js/Date.)) " " (str/join " " (map str xs)))))

(defn- exists? [p] (try (.existsSync fs p) (catch :default _ false)))

(defn- sh [cmd args opts]
  (try
    (let [r (.spawnSync cp cmd (clj->js args)
                        (clj->js (merge {:encoding "utf8" :cwd root} opts)))]
      {:code (aget r "status") :out (str (aget r "stdout")) :err (str (aget r "stderr"))})
    (catch :default e {:code nil :out "" :err (str e)})))

(defn- append-ledger! [m]
  (try (.mkdirSync fs state-dir #js {:recursive true})
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

;; ---------------------------------------------------------------- run one bot

(defn- run-bot!
  "1 bot 1 session。CLI の 3 値 exit をそのまま ledger 語彙にする。"
  [started bot-id]
  (let [receipts-dir (str receipts-root "/" bot-id)]
    (cond
      (not (exists? lib-dir))
      (do (log! bot-id ": computer-use checkout が無い。:not-measured")
          (append-ledger! {:at started :bot bot-id :outcome :not-measured
                           :why :lib-missing :path lib-dir}))

      (not (exists? (str lib-dir "/" lib-cli)))
      ;; lib はこの loop の後から land してよい。その間を failed に見せない。
      (do (log! bot-id ": " lib-cli " がまだ無い（lib 未着地）。:not-measured")
          (append-ledger! {:at started :bot bot-id :outcome :not-measured
                           :why :cli-missing :path (str lib-dir "/" lib-cli)}))

      :else
      (do
        (try (.mkdirSync fs receipts-dir #js {:recursive true}) (catch :default _ nil))
        ;; 呼び出しは lib の公示 contract どおり（classpath は CLI 側が面倒を見る）:
        ;;   nbb bin/cua_bot_run.cljs --roster … --bot … --receipts-dir … [--dry-run]
        (let [args (cond-> [lib-cli
                            "--roster" roster-file
                            "--bot" bot-id
                            "--receipts-dir" receipts-dir]
                     dry-run? (conj "--dry-run"))
              {:keys [code out err]} (sh "nbb" args {:cwd lib-dir :timeout 900000})]
          (println (str/join "\n" (take-last 8 (str/split-lines (str out)))))
          (when (seq (str/trim (str err))) (log! bot-id " stderr:" (str/trim (str err))))
          (append-ledger! {:at started
                           :finished (.toISOString (js/Date.))
                           :bot bot-id
                           :dry-run? dry-run?
                           :exit code
                           ;; 3 値をそのまま。2 を 1 にも 0 にも畳まない。
                           :outcome (case code
                                      0 :done
                                      1 :failed
                                      2 :could-not-measure
                                      :unknown-exit)
                           :receipt (last-edn-line out)}))))))

;; ---------------------------------------------------------------- main

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

    (let [{:keys [code out err]} (sh "nbb" ["scripts/cua-bots-tick.cljs"] {:timeout 120000})
          tick (last-edn-line out)]
      (println (str/trim (str out)))
      (when (seq (str/trim (str err))) (log! "tick stderr:" (str/trim (str err))))
      (cond
        (= 2 code)
        ;; 名簿が測れなかった。「due 0 件」とは別の事実。
        (do (log! "tick が測れなかった（roster invalid / exit 2）。何もしない。")
            (append-ledger! {:at started :outcome :skipped :why :roster-invalid
                             :problems (:problems tick)}))

        (nil? tick)
        (do (log! "tick の出力が読めない。何もしない。")
            (append-ledger! {:at started :outcome :skipped :why :tick-unreadable
                             :exit code}))

        (= :no-due (:outcome tick))
        ;; due 無し。何も起こさない。
        (do (log! "due な bot 0 体（" (count (:skipped tick)) "体は interval 内）。")
            (append-ledger! {:at started :outcome :skipped :why :no-due
                             :skipped (mapv :bot/id (:skipped tick))}))

        :else
        (do
          (acquire-lock!)
          (try
            (doseq [b (:due tick)]
              (run-bot! started (:bot/id b)))
            (finally (release-lock!))))))

    (js/process.exit 0)))

(-main)

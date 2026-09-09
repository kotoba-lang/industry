#!/usr/bin/env nbb
;; scripts/ma-business-loop.cljs — M&A マッチング事業 loop の入口。
;; LaunchAgent `cloud.itonami.bot.ma-business-advance` が一定間隔でこれを起こす。
;;
;; 2 段構え（姉妹 loop と同じ）:
;;   1. `scripts/ma-business-tick.cljs` が割れている床を測る（決定論）
;;   2. **割れた床が在るときだけ** `claude -p "/ma-business-advance"` を起こす
;;
;; 1 反復 = 床 1 つ。2 つまとめない（ADR-2607189300: 大きいバッチと自己申告の
;; green が 61% 欠陥の fan-out を起こした）。手順の正本は skill。ここには書かない。
;;
;; 不変条件:
;;   - **測れなかった周はモデルを起こさない。** tick が exit 2（構成 repo の
;;     checkout が 0 本）なら、次の 1 手は「事業を進めること」ではなく
;;     「west update すること」であって、それは判断を要さない。
;;   - 割れた床が 0 ならモデルを起こさない。無い仕事に fleet の時間を使わない。
;;   - 共有 checkout を書き換えない。`git merge --ff-only` もしない。
;;   - ledger は追記のみ。成否は次周の tick が測る（自己申告を信じない）。
;;   - exit 0 常に。
;;
;; usage:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/ma-business-loop.cljs
;;   nbb --classpath ".:scripts/nbb_compat" scripts/ma-business-loop.cljs --dry-run

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def os (js/require "node:os"))
(def cp (js/require "node:child_process"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-file (str home "/.itonami/ma-business.ledger.edn"))
(def tick-ledger (str home "/.itonami/ma-business-tick.ledger.edn"))
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
  ;; ~/.gftd が無い機械では最初の追記が失敗する。tick も同じ dir を作るが、
  ;; diverged 経路は tick より前に書くのでここでも作る。
  (try (.mkdirSync fs (.dirname (js/require "node:path") ledger-file) #js {:recursive true})
       (catch :default _ nil))
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

    (let [{:keys [code out]} (sh "nbb" ["--classpath" ".:scripts/nbb_compat"
                                        "scripts/ma-business-tick.cljs"] {})]
      (println out)
      ;; tick は 0 か 2 しか返さない。**0 以外はすべて「進めない」** ——
      ;; 2 を 0 と同じに扱わないのはもちろん、`nil`（nbb が PATH に無い等で
      ;; 起動自体に失敗）も同じ側に置く。旧版は `(= 2 code)` だけを見ていたので、
      ;; **tick が一度も走らなかった周に、前の周が書いた古い ledger の `:next` で
      ;; モデルを起こしていた** —— 起動失敗が「測って問題が無かった」と同じ形で
      ;; 通る経路で、この loop が防ごうとしている当のものだった。
      (when (not= 0 code)
        (log! (str "tick が答えを返さなかった（exit " code "）。モデルを起こさない。"))
        (append-ledger! {:at started :outcome :skipped
                         :why (if (= 2 code) :tick-could-not-measure :tick-did-not-run)
                         :exit code})
        (js/process.exit 0)))

    (let [tick (last-tick)
          next (:next tick)]
      (cond
        (nil? tick)
        (do (log! "tick の ledger が読めない。この周は何もしない")
            (append-ledger! {:at started :outcome :skipped :why :no-tick-ledger}))

        (nil? next)
        (do (log! "割れている床は 0。無い仕事にモデルを起こさない")
            (append-ledger! {:at started :outcome :skipped :why :no-broken-floor
                             :floors (:floors tick)}))

        dry-run?
        (do (log! "--dry-run: 次の 1 手は床" (name next))
            (append-ledger! {:at started :outcome :dry-run :next next
                             :floors (:floors tick)}))

        :else
        (let [{:keys [code out err]}
              (sh "claude" ["-p" "/ma-business-advance"
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
                           :floors-before (:floors tick)
                           :note "成否は次周の tick が測る"}))))
    (js/process.exit 0)))

(-main)

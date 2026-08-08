#!/usr/bin/env nbb
;; scripts/kaiyu-kaizen-loop.cljs — **ローカル Claude loop** の入口。
;; LaunchAgent `com.gftd.kaiyu-kaizen` が一定間隔でこれを起こす。
;;
;; ## 2 段構え（姉妹 loop と同じ）
;;
;;   1. `scripts/kaiyu-kaizen-tick.cljs` が回遊の計測を読んで候補を**測る**（決定論）
;;   2. 候補があるときだけ `claude -p "/kaiyu-kaizen"` を起こす
;;
;; 順番が逆だと、毎周 3 サイト分の JSON をモデルに読ませ、周ごとに違う基準で選ぶ。
;; **探索と判定は機械、文脈の確認と提案はモデル。**
;;
;; ## なぜ 1 反復 = 1 issue か
;;
;; issue は「答えるべき問い」であって作業指示ではない。まとめて 5 件出すと、
;; どれも読まれないまま queue に積み上がる —— queue を読まれなくすることが、
;; 計測から得られるものを全部無駄にする唯一の方法である。1 周 1 件なら、
;; 次の周に「前のは答えられたか」が効く。
;;
;; ## 不変条件
;;
;;   - **候補が 0 ならモデルを起こさない。** 無い仕事に token を使わない。
;;     サイトが健全な周に issue を出す loop は noise を出す
;;   - **secret が無い / 到達できないサイトは候補にしない。** 読めなかったことを
;;     「問題が無い」とも「問題がある」とも書かない
;;   - **同じ issue id が既に開いていれば ingress が 200 already-open を返す。**
;;     loop 側で重複を防ごうとしない（状態を 2 箇所で持つことになる）
;;   - ledger は追記のみ。起こさなかった周は :skipped と理由を書く
;;   - **成否をここで判定しない。** 次周の tick が測り直す
;;   - exit 0 常に（監視 loop であって gate ではない）
;;
;; 使い方:
;;   nbb --classpath ".:scripts/nbb_compat:orgs/kotoba-lang/kaiyu/src" \
;;       scripts/kaiyu-kaizen-loop.cljs [--dry-run]

(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))
(def path (js/require "node:path"))

(def root (or (.-COM_JUNKAWASAKI_ROOT js/process.env) (.cwd js/process)))
(def ledger (.join path root "90-docs" "kaizen" "kaiyu-kaizen.ledger.edn"))
(def argv (vec (drop 2 (js->clj js/process.argv))))
(def dry-run? (some? (some #{"--dry-run"} argv)))

(defn- now [] (.toISOString (js/Date.)))

(defn- append-ledger!
  "Append-only. This is a measurement/event stream, not a document — the
  exception CLAUDE.md names for append-only files."
  [entry]
  (try
    (.mkdirSync fs (.dirname path ledger) #js {:recursive true})
    (.appendFileSync fs ledger (str (pr-str (assoc entry :at (now))) "\n") "utf8")
    (catch :default e (js/console.error "kaiyu-kaizen-loop: ledger write failed" e))))

(defn- run-tick []
  (try
    (let [out (.execFileSync cp "nbb"
                             #js ["--classpath" ".:scripts/nbb_compat:orgs/kotoba-lang/kaiyu/src"
                                  "scripts/kaiyu-kaizen-tick.cljs" "--json"]
                             #js {:encoding "utf8" :cwd root
                                  :stdio #js ["ignore" "pipe" "ignore"]})]
      (js->clj (js/JSON.parse (str out)) :keywordize-keys true))
    (catch :default e
      (js/console.error "kaiyu-kaizen-loop: tick failed" (str e))
      nil)))

(defn- wake-model! [candidate]
  (let [prompt (str "/kaiyu-kaizen " (js/JSON.stringify (clj->js candidate)))]
    (try
      (.execFileSync cp "claude" #js ["-p" prompt]
                     #js {:cwd root :stdio "inherit" :timeout (* 20 60 1000)})
      {:woke true}
      (catch :default e {:woke true :error (str e)}))))

(let [tick (run-tick)]
  (cond
    (nil? tick)
    (append-ledger! {:status :skipped :reason :tick-failed})

    (empty? (:candidates tick))
    ;; The healthy round. Recorded so a reader can tell "nothing to do" from
    ;; "the loop is not running" — the same distinction kaiyu.diagnose insists
    ;; on for the data itself.
    (append-ledger! {:status :skipped :reason :no-candidates
                     :window (:window tick)
                     :sites (mapv #(select-keys % [:site :status :finding-count]) (:results tick))})

    :else
    (let [candidate (first (:candidates tick))]
      (if dry-run?
        (println (str "would wake: " (get-in candidate [:issue :id])))
        (let [result (wake-model! candidate)]
          (append-ledger! (merge {:status :woke
                                  :window (:window tick)
                                  :site (:site candidate)
                                  :issue-id (get-in candidate [:issue :id])
                                  :severity (get-in candidate [:top :severity])
                                  :candidates (count (:candidates tick))}
                                 (select-keys result [:error]))))))))

(js/process.exit 0)

#!/usr/bin/env nbb
;; scripts/kaiyu-kaizen-loop.cljs — **ローカル Claude loop** の入口。
;; LaunchAgent `cloud.itonami.bot.kaiyu-kaizen` が一定間隔でこれを起こす。
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
;;   - **書き込みの重複は ingress が防ぐ**（同じ issue id なら 200 already-open）。
;;     loop 側で「既に開いているか」を持たない —— それは queue の状態で、写しを
;;     持てば必ずずれる
;;   - **起こしたかどうかは loop 自身の状態**なので ledger が持つ。これは別の問い
;;     で、答えないと **答えの出た問いのために 6 時間ごとにモデルを起こし続ける**。
;;     実測（2026-08-08）: shinshi の transitions の issue は実ブラウザ実験で答えが
;;     出て ledger に :answered まで書いたのに、tick は同じ finding を出し続ける
;;     （行が 0 なのは事実なので当然）。ingress は 200 already-open を返すから
;;     queue は汚れないが、**モデルは毎周起きる**
;;   - ledger は追記のみ。起こさなかった周は :skipped と理由を書く
;;   - **成否をここで判定しない。** 次周の tick が測り直す
;;   - exit 0 常に（監視 loop であって gate ではない）
;;
;; 使い方:
;;   nbb --classpath ".:scripts/nbb_compat:orgs/kotoba-lang/kaiyu/src" \
;;       scripts/kaiyu-kaizen-loop.cljs [--dry-run]

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))
(def path (js/require "node:path"))
(def os (js/require "node:os"))

(def root (or (.-COM_JUNKAWASAKI_ROOT js/process.env) (.cwd js/process)))
(def ledger (.join path root "90-docs" "kaizen" "kaiyu-kaizen.ledger.edn"))
(def argv (vec (drop 2 (js->clj js/process.argv))))
(def dry-run? (some? (some #{"--dry-run"} argv)))

(defn- now [] (.toISOString (js/Date.)))

(defn- land-ledger-line!
  "台帳に足した 1 行を origin/main へ着地させる。

  これが無いと loop の判断は共有 checkout の working tree にしか存在しない。実測
  2026-08-19: 08-18 に書いた `:woke` 2 行（kotobase.net 17:27Z / itonami.cloud
  23:34Z）が、main 同期のための stash に退避されたまま 1 日以上どのブランチにも
  remote にも無く、issue-id で突き合わせるまで失われかけていることに誰も気づいて
  いなかった。書くことと残ることは別である。

  着地は best-effort。失敗しても loop は止めない —— 行は working tree に残るので
  次の周が拾える。ただし **失敗を成功と区別して印字する**（沈黙で緑にしない）。"
  [entry]
  (try
    (let [tmp (.join path (.tmpdir os) (str "kaiyu-ledger-" (.now js/Date) ".edn"))]
      (.writeFileSync fs tmp (str (pr-str entry) "\n") "utf8")
      (let [r (.spawnSync cp "nbb"
                          #js ["scripts/ledger-land.cljs" "com-junkawasaki/root"
                               "90-docs/kaizen/kaiyu-kaizen.ledger.edn" tmp]
                          #js {:encoding "utf8" :cwd root :timeout 180000
                               :stdio #js ["ignore" "pipe" "pipe"]})
            st (or (.-status r) 1)]
        (println (str "ledger-land: exit=" st " " (str/trim (str (or (.-stdout r) "")))))
        (zero? st)))
    (catch :default e
      (println (str "ledger-land: failed to invoke (" (.-message e) ") — line stays local"))
      false)))

(defn- append-ledger!
  "Append-only. This is a measurement/event stream, not a document — the
  exception CLAUDE.md names for append-only files."
  [entry]
  (try
    (.mkdirSync fs (.dirname path ledger) #js {:recursive true})
    (let [stamped (assoc entry :at (now))]
      (.appendFileSync fs ledger (str (pr-str stamped) "\n") "utf8")
      ;; 書いただけでは残らない。自分で着地させる。
      (land-ledger-line! stamped))
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

(defn- handled-issue-ids
  "Issue ids this loop has already woken for (or recorded an answer to).

  Reads its OWN ledger, not the queue: 「まだ開いているか」は queue の問いで
  ingress が答える（200 already-open）。ここが答えるのは「もう起こしたか」で、
  別の問いである。両方を queue に訊くと、答えの出た問いのためにモデルを起こし
  続けることになる。

  Parses the whole file as one vector of forms rather than line by line —
  entries written by hand span several lines, and a per-line reader silently
  yields the first keyword it finds instead of the map, which reads as『まだ
  起こしていない』for every entry. Found by the dry-run still saying it would
  wake for an issue the ledger already recorded as answered."
  []
  (try
    (let [content (str (.readFileSync fs ledger "utf8"))]
      (->> (edn/read-string (str "[" content "]"))
           (keep :issue-id)
           set))
    (catch :default _ #{})))

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
    (let [handled (handled-issue-ids)
          fresh (remove #(contains? handled (get-in % [:issue :id])) (:candidates tick))]
      (if (empty? fresh)
        ;; Every candidate is one this loop already took to the model. Recorded
        ;; rather than silent, so 「何も無い」 and 「同じものばかり」 stay
        ;; distinguishable — they need different fixes.
        (append-ledger! {:status :skipped :reason :all-candidates-already-woken
                         :window (:window tick)
                         :issue-ids (mapv #(get-in % [:issue :id]) (:candidates tick))})
        (let [candidate (first fresh)]
          (if dry-run?
            (println (str "would wake: " (get-in candidate [:issue :id])))
            (let [result (wake-model! candidate)]
              (append-ledger! (merge {:status :woke
                                      :window (:window tick)
                                      :site (:site candidate)
                                      :issue-id (get-in candidate [:issue :id])
                                      :severity (get-in candidate [:top :severity])
                                      :candidates (count (:candidates tick))
                                      :fresh (count fresh)}
                                     (select-keys result [:error]))))))))))

(js/process.exit 0)

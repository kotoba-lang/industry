#!/usr/bin/env nbb
;; hermes cron fleet の 1 日あたり API 予算 governor。
;;
;; 5 分ごとに OpenRouter の /api/v1/credits を読み、JST の当日消費が
;; manifest/hermes-budget-policy.edn の段に達したら、その tier 以上の cron job を
;; `hermes cron pause` で止める。日が変わったら自分が止めたものだけを resume する。
;;
;; 設計上の不変条件（CLAUDE.md「検査を書く前・緑を信じる前の 8 問」）:
;;
;;  1. **測れなかった tick は、測って予算内だった tick と同じ値を返さない。**
;;     credits が読めなければ exit 2（0 でも 1 でもない = 「答えられなかった」）。
;;     連続 :budget/unreadable-ticks-before-pause 回で全段 pause へ昇格する
;;     —— メーターの無い無制限の支出は、最も危険な既定だからである。
;;  2. **「飛ばした」と「合格した」を出力で区別する。** 毎 tick に SCANNED / SPEND /
;;     RATIO / LEVEL / PAUSED-NOW / PAUSED-TOTAL を印字する。証拠の床であって
;;     沈黙を緑と読ませない。
;;  3. **境界ちょうどの入力を持つ。** decide は `>=`。ratio == 0.60 / 0.80 / 1.00 の
;;     3 点を --self-check が持つ。これが無いと `>` と `>=` の反転が緑のまま通る
;;     （2026-09-06 の実測事例と同型）。
;;  4. **self-check は個数を返す。** boolean は 1 件の退行と壊れた検査を区別できない。
;;  5. **自分が止めたものだけを resume する。** 人や他の道具が止めた job には触らない。
;;     state を失ったら resume できないが、それは他人の pause を勝手に外すより安全。
;;
;; token からドルを推定しない。単価は動くが credits は動かない事実を答えるので、
;; 判断に使うのは credits が答えたドルだけにしてある。
(ns hermes-budget-guard
  (:require ["fs" :as fs]
            ["path" :as path]
            ["os" :as os]
            ["child_process" :as cp]
            [clojure.string :as str]
            [cljs.reader :as reader]))

(def ROOT (or (aget js/process.env "ROOT") "/Users/junkawasaki/github/com-junkawasaki"))
(def HERMES (path/join (os/homedir) ".hermes"))
(def HERMES-BIN (path/join HERMES "hermes-agent" "venv" "bin" "hermes"))
;; GUARD_STATE_DIR は試験用の逃し口。本番 state を汚さずに fail-closed 分岐を
;; 実際に踏むために要る —— 踏んだことのない分岐は、無い分岐と区別できない。
(def STATE-DIR (or (aget js/process.env "GUARD_STATE_DIR")
                   (path/join (os/homedir) ".gftd" "hermes-budget-guard")))
(def STATE (path/join STATE-DIR "state.json"))
(def LEDGER (path/join STATE-DIR "usage.ledger.jsonl"))

(defn- exists? [p] (try (fs/existsSync p) (catch :default _ false)))

;; ---------------------------------------------------------------- pure core

(defn decide
  "予算に対する当日消費から、止め始める tier を返す。止めないときは nil。
   `>=` —— ratio がちょうど段の値のとき、その段は効く。"
  [spend budget steps]
  (when (and (number? spend) (number? budget) (pos? budget))
    (let [ratio (/ spend budget)]
      (->> steps
           (filter #(>= ratio (:at %)))
           (map :pause-from)
           (reduce (fn [a b] (if (or (nil? a) (< b a)) b a)) nil)))))

(defn should-pause?
  "tier が閾値以上なら止める。閾値が nil なら何も止めない。"
  [tier pause-from]
  (boolean (and pause-from (number? tier) (>= tier pause-from))))

;; ---------------------------------------------------------------- self-check

(def ^:private CASES
  ;; [spend budget expected-pause-from ラベル]
  [[0.0    50.0 nil "zero"]
   [29.99  50.0 nil "just below 0.60"]
   [30.0   50.0 3   "EXACTLY 0.60 — > と >= の違いはここでしか出ない"]
   [30.01  50.0 3   "just above 0.60"]
   [39.99  50.0 3   "just below 0.80"]
   [40.0   50.0 2   "EXACTLY 0.80"]
   [49.99  50.0 2   "just below 1.00"]
   [50.0   50.0 1   "EXACTLY 1.00 — 予算ちょうどは超過として扱う"]
   [75.0   50.0 1   "over"]
   [30.0   0.0  nil "budget 0 は判定不能（nil）"]])

(def ^:private TIER-CASES
  [[3 3 true "tier3 at level3"] [2 3 false "tier2 at level3"] [1 3 false "tier1 at level3"]
   [3 2 true "tier3 at level2"] [2 2 true "tier2 at level2"] [1 2 false "tier1 at level2"]
   [1 1 true "tier1 at level1"] [3 nil false "no level -> nothing pauses"]])

(defn self-check [steps]
  (let [fails (atom 0)]
    (doseq [[spend budget expected label] CASES]
      (let [got (decide spend budget steps)]
        (when (not= got expected)
          (swap! fails inc)
          (println (str "FAIL decide spend=" spend " budget=" budget
                        " expected=" expected " got=" got "  (" label ")")))))
    (doseq [[tier level expected label] TIER-CASES]
      (let [got (should-pause? tier level)]
        (when (not= got expected)
          (swap! fails inc)
          (println (str "FAIL should-pause? tier=" tier " level=" level
                        " expected=" expected " got=" got "  (" label ")")))))
    (println (str "SELF-CHECK-CASES\t" (+ (count CASES) (count TIER-CASES))))
    (println (str "SELF-CHECK-FAILURES\t" @fails))
    @fails))

;; ---------------------------------------------------------------- io

(defn read-policy []
  (let [p (path/join ROOT "manifest" "hermes-budget-policy.edn")]
    (when-not (exists? p)
      (println (str "REFUSED\tpolicy not found: " p))
      (js/process.exit 2))
    (let [d (reader/read-string (fs/readFileSync p "utf8"))]
      ;; 読めたことは正しいことではない —— 型まで見る。
      (doseq [[k pred] [[:budget/usd-per-day number?] [:budget/steps vector?]
                        [:budget/tiers map?] [:budget/credits-url string?]
                        [:budget/key-service string?]]]
        (when-not (pred (get d k))
          (println (str "REFUSED\tpolicy key " k " has wrong shape: " (pr-str (get d k))))
          (js/process.exit 2)))
      d)))

(defn read-state []
  (if (exists? STATE)
    (try (js->clj (js/JSON.parse (fs/readFileSync STATE "utf8")) :keywordize-keys true)
         (catch :default _ {}))
    {}))

(def ^:dynamic *dry?* false)

(defn write-state! [m]
  ;; --dry-run は観測だけで、state も ledger も動かさない。
  ;; 動かすと「試しに見た」が「その日の baseline」になり、次の tick が
  ;; 部分観測を全量として読む。
  (when-not *dry?*
    (fs/mkdirSync STATE-DIR #js{:recursive true})
    (fs/writeFileSync STATE (js/JSON.stringify (clj->js m) nil 2))))

(defn ledger! [m]
  (when-not *dry?*
    (fs/mkdirSync STATE-DIR #js{:recursive true})
    (fs/appendFileSync LEDGER (str (js/JSON.stringify (clj->js m)) "\n"))))

(defn jst-day []
  (let [f (js/Intl.DateTimeFormat. "en-CA" #js{:timeZone "Asia/Tokyo" :year "numeric"
                                               :month "2-digit" :day "2-digit"})]
    (.format f (js/Date.))))

(defn keychain-secret [service]
  (try
    (str/trim (.toString (cp/execFileSync "security"
                                          #js["find-generic-password" "-s" service "-w"]
                                          #js{:stdio #js["ignore" "pipe" "ignore"]})))
    (catch :default _ nil)))

(defn fetch-usage [url key]
  (-> (js/fetch url #js{:headers #js{"Authorization" (str "Bearer " key)}})
      (.then (fn [r]
               (if (.-ok r)
                 (.then (.json r)
                        (fn [j]
                          (let [u (some-> j .-data .-total_usage)
                                c (some-> j .-data .-total_credits)]
                            (if (number? u)
                              {:ok true :usage u :credits c}
                              {:ok false :reason "no total_usage in response"}))))
                 {:ok false :reason (str "HTTP " (.-status r))})))
      (.catch (fn [e] {:ok false :reason (str "fetch failed: " (.-message e))}))))

;; ---------------------------------------------------------------- jobs

(defn profile-homes []
  (let [pd (path/join HERMES "profiles")]
    (into [["DEFAULT" HERMES]]
          (when (exists? pd)
            (for [d (sort (fs/readdirSync pd))
                  :let [h (path/join pd d)]
                  :when (exists? (path/join h "cron" "jobs.json"))]
              [d h])))))

(defn scan-jobs []
  (vec (for [[prof home] (profile-homes)
             :let [f (path/join home "cron" "jobs.json")]
             :when (exists? f)
             j (or (some-> (try (js/JSON.parse (fs/readFileSync f "utf8")) (catch :default _ nil))
                           (aget "jobs")
                           array-seq)
                   [])]
         {:profile prof :home home :id (aget j "id") :name (aget j "name")
          :enabled (aget j "enabled") :state (aget j "state")})))

(defn hermes! [home & args]
  (try
    (cp/execFileSync HERMES-BIN (clj->js (vec args))
                     #js{:env (clj->js (assoc (js->clj js/process.env) "HERMES_HOME" home))
                         :stdio #js["ignore" "pipe" "pipe"] :timeout 60000})
    true
    (catch :default _ false)))

;; ---------------------------------------------------------------- main

(defn run! [dry?]
  (set! *dry?* dry?)
  (let [pol      (read-policy)
        budget   (:budget/usd-per-day pol)
        steps    (:budget/steps pol)
        tiers    (:budget/tiers pol)
        never    (or (:budget/never-pause pol) #{})
        max-fail (or (:budget/unreadable-ticks-before-pause pol) 3)
        st       (read-state)
        day      (jst-day)
        key      (keychain-secret (:budget/key-service pol))]
    (when-not key
      (println (str "REFUSED\tno key for service " (:budget/key-service pol)))
      (js/process.exit 2))
    (-> (fetch-usage (:budget/credits-url pol) key)
        (.then
         (fn [res]
           (ledger! (merge {:ts (.toISOString (js/Date.)) :day day} res))
           (if-not (:ok res)
             ;; ---- 測れなかった。予算内とは答えない。
             (let [n (inc (or (:unreadable st) 0))]
               (write-state! (assoc st :unreadable n :last-ts (.toISOString (js/Date.))))
               (println (str "UNREADABLE\t" (:reason res)))
               (println (str "UNREADABLE-STREAK\t" n "/" max-fail))
               (if (>= n max-fail)
                 (let [jobs (remove #(contains? never (str (:profile %) "/" (:id %))) (scan-jobs))
                       live (filter #(and (:enabled %) (not= "paused" (:state %))) jobs)
                       done (doall (for [j live] [j (or dry? (hermes! (:home j) "cron" "pause" (:id j)))]))
                       okd  (map (comp #(str (:profile %) "/" (:id %)) first) (filter second done))]
                   (write-state! (assoc st :unreadable n :day day
                                        :paused (vec (distinct (concat (:paused st) okd)))
                                        :level 1 :reason "unreadable-streak"))
                   (println (str "ESCALATED-TO-PAUSE-ALL\t" (count okd) " job(s)"))
                   (js/process.exit 2))
                 (js/process.exit 2)))
             ;; ---- 測れた。
             (let [usage (:usage res)
                   newday? (not= day (:day st))
                   ;; 初回（この日の baseline がまだ無い）は「今」を baseline にする。
                   ;; その日の 0:00 以降に既に使った分は再構成できないので、
                   ;; 初日の SPEND は当日全部ではなく「観測開始からの分」である。
                   ;; state に出所を書いておき、部分観測を全量として読ませない。
                   base (if newday? usage (or (:baseline st) usage))
                   base-src (cond (nil? (:baseline st)) "first-run (partial day)"
                                  newday? "day-rollover"
                                  :else "carried")
                   spend (max 0 (- usage base))
                   level (decide spend budget steps)
                   jobs (scan-jobs)
                   prev-paused (set (:paused st))
                   resumed (when newday?
                             (doall (for [k prev-paused
                                          :let [[p i] (str/split k #"/" 2)
                                                j (first (filter #(and (= p (:profile %)) (= i (:id %))) jobs))]
                                          :when j]
                                      (do (or dry? (hermes! (:home j) "cron" "resume" i)) k))))
                   jobs (if newday? (scan-jobs) jobs)
                   want (->> jobs
                             (remove #(contains? never (str (:profile %) "/" (:id %))))
                             (filter #(should-pause? (get tiers (str (:profile %) "/" (:id %)) 3) level))
                             (filter #(and (:enabled %) (not= "paused" (:state %)))))
                   done (doall (for [j want] [j (or dry? (hermes! (:home j) "cron" "pause" (:id j)))]))
                   okd (map (comp #(str (:profile %) "/" (:id %)) first) (filter second done))
                   failed (count (remove second done))
                   paused' (if newday? (vec okd)
                               (vec (distinct (concat (:paused st) okd))))]
               (write-state! {:day day :baseline base :baseline-source base-src :usage usage :spend spend
                              :level level :paused paused' :unreadable 0
                              :last-ts (.toISOString (js/Date.))})
               (println (str "DAY\t" day (when newday? "\t(new day: baseline reset)")))
               (println (str "SCANNED\t" (count jobs) "\tjobs across " (count (profile-homes)) " homes"))
               (println (str "USAGE\t" (.toFixed usage 3) "\tcredits-remaining "
                             (if (number? (:credits res)) (.toFixed (- (:credits res) usage) 2) "unknown")))
               (println (str "SPEND\t" (.toFixed spend 3) "\tof " budget "\tbaseline=" base-src))
               (println (str "RATIO\t" (.toFixed (/ spend budget) 4)))
               (println (str "LEVEL\t" (or level "none") (when dry? "\t(DRY-RUN: nothing applied)")))
               (when newday? (println (str "RESUMED\t" (count resumed))))
               (println (str "PAUSED-NOW\t" (count okd) (when (pos? failed) (str "\tFAILED " failed))))
               (println (str "PAUSED-TOTAL\t" (count paused')))
               (js/process.exit (if (pos? failed) 2 0)))))))))

(let [args (set (vec (.slice js/process.argv 2)))]
  (cond
    (contains? args "--self-check")
    (js/process.exit (if (pos? (self-check (:budget/steps (read-policy)))) 1 0))
    :else (run! (contains? args "--dry-run"))))

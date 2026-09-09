#!/usr/bin/env nbb
;; SessionStart フック: repo ごとの常駐 bot（scripts/repo-bots/）が測った現在地を、
;; セッション冒頭で agent の目の前に置く。
;;
;; ## なぜ log ではなく hook なのか
;;
;; ADR-2608124800 の中心にある失敗と同じ理由 —— 検出器は正しく動いていて、正しい
;; 違反を報告していて、数週間だれにも読まれなかった。出力先の欠陥である。だから
;; 出力先は log ではなく、既に読まれている面にする。
;;
;; ## 名簿に載っていることを「動いている」と読ませない
;;
;; manifest/repo-bots.edn は 4,000 体を宣言する。宣言と稼働を混ぜると、
;; manifest/observatories.edn が記録した失敗（22 actor 中 8 本が「壊れていたのでは
;; なく、誰も一度も走らせていなかった」）がそのまま 4,000 体規模で起きる。だから
;; この hook が最初に出すのは **未踏（never ticked）の数** と **scheduler の有無**。
;;
;; STANDING は列挙しない。件数と最古の齢だけ —— もう言ったことを毎セッション
;; 言い直すのは、言っていないのと同じ。
;;
;; fail-open: 判定途中のあらゆる失敗はセッション開始をブロックしない。
;;
;; 手動実行: nbb .claude/hooks/session-start-repo-bots.cljs [--state <path>]

(require '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[scripts.nbb-compat :as compat])

(def fs (js/require "node:fs"))
(def os (js/require "node:os"))

(def argv (vec js/process.argv))
(defn- opt [n] (let [i (.indexOf argv n)] (when (and (>= i 0) (< (inc i) (count argv)))
                                            (nth argv (inc i)))))

(def home (.homedir os))
(def state-file (or (opt "--state") (str home "/.itonami/repo-bots/state.edn")))
(def ledger-file (str home "/.itonami/repo-bots/observations.ledger.edn"))
(def plist (str home "/Library/LaunchAgents/com.gftd.repo-bots-tick.plist"))
(def registry-file (str (or (not-empty (str (or js/process.env.CLAUDE_PROJECT_DIR ""))) ".")
                        "/manifest/repo-bots.edn"))

(def day-ms (* 24 60 60 1000))

(defn- read-edn [f] (try (edn/read-string (.readFileSync fs f "utf8")) (catch :default _ nil)))
(defn- exists? [f] (try (.existsSync fs f) (catch :default _ false)))
(defn- ms [iso] (let [t (.parse js/Date (str iso))] (if (js/Number.isFinite t) t 0)))
(defn- human [x]
  (let [m (/ x 60000) h (/ m 60) d (/ h 24)]
    (cond (< m 90) (str (js/Math.round m) "m")
          (< h 48) (str (js/Math.round h) "h")
          :else    (str (js/Math.round d) "d"))))

(defn done!
  ([] (compat/exit 0))
  ([msg]
   (println (json/generate-string
             {:systemMessage msg
              :hookSpecificOutput {:hookEventName "SessionStart" :additionalContext msg}}))
   (compat/exit 0)))

(defn- growth [days]
  (let [cut (- (.now js/Date) (* days day-ms))
        evs (->> (try (str/split-lines (.readFileSync fs ledger-file "utf8")) (catch :default _ []))
                 (remove str/blank?)
                 (keep #(try (edn/read-string %) (catch :default _ nil)))
                 (filter #(> (ms (:at %)) cut)))]
    {:cleared (count (filter #(= :cleared (:event %)) evs))
     :broke (count (filter #(= :broke (:event %)) evs))}))

(try
  (let [registry (read-edn registry-file)
        state (read-edn state-file)]
    (cond
      (not (seq registry)) (done!)

      (nil? state)
      (done! (str/join "\n"
              ["⚠ repo ごとの常駐 bot（scripts/repo-bots/）は名簿だけが在って、一度も動いていません"
               (str "  名簿 " (count registry) " 体 / state ファイルが在りません: " state-file)
               ""
               "**報告の無い tick と、動いていない tick は外から見て同じです。**"
               "1 回走らせる: nbb scripts/repo-bots/tick.cljs --wave 200"
               (str "常駐させる: cp scripts/com.gftd.repo-bots-tick.plist ~/Library/LaunchAgents/ && "
                    "launchctl load " plist)]))

      :else
      (let [all (:bots state)
            ids (set (map :bot/id registry))
            ;; 名簿の中だけで数える。state には名簿から消えた repo の行が残る
            ;; （west entry の rename で実際に起きた）ので、生の件数を使うと
            ;; 「名簿 4186 体 / 測定済み 4187」という**有り得ない行**が出る。
            bots (into {} (filter #(contains? ids (key %)) all))
            orphans (- (count all) (count bots))
            roster (count ids)
            ticked (count bots)
            never (- roster ticked)
            unmeasured (count (filter #(= :unmeasured (:status (val %))) bots))
            standing (->> (for [[_ r] bots [f {:keys [since]}] (:broken r)] [f since])
                          (group-by first))
            total-broken (reduce + 0 (map (comp count val) standing))
            g7 (growth 7)
            age (some->> (keep #(:last-tick (val %)) bots) sort last ms
                         (- (.now js/Date)))]
        (done!
         (str/join
          "\n"
          (concat
           [(str "repo 常駐 bot: 名簿 " roster " 体 / 測定済み " ticked
                 (when (pos? never) (str "（**未踏 " never "**）"))
                 (when (pos? orphans) (str " / 名簿から消えた state 行 " orphans))
                 (when age (str " / 最終測定 " (human age) "前")))]
           (when-not (exists? plist)
             [(str "  ⚠⚠ scheduler がありません —— " plist " が無い。"
                   "上の「最終測定」は「変化が無い」ではなく「誰も走らせていない」です。"
                   "install: cp scripts/com.gftd.repo-bots-tick.plist ~/Library/LaunchAgents/ "
                   "&& launchctl load " plist)])
           [(str "  床を割っている: " total-broken " 件"
                 (when (seq standing)
                   (str "（" (str/join " / " (for [[f xs] (sort-by (comp - count val) standing)]
                                               (str (name f) " " (count xs)))) "）")))]
           (when (pos? unmeasured)
             [(str "  測れなかった bot: " unmeasured " 体（checkout が無い等。**clean ではない**）")])
           [(str "  直近 7 日の成長: 塞がった " (:cleared g7) " / 割れた " (:broke g7)
                 "  —— 詳細は `nbb scripts/repo-bots/tick.cljs --report`")]))))))
  (catch :default _ nil))
(compat/exit 0)

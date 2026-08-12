#!/usr/bin/env nbb
;; SessionStart フック: `scripts/orgs-detector-tick.cljs` が測った結果を、
;; セッション冒頭で agent の目の前に置く。
;;
;; ## なぜ log ではなく hook なのか
;;
;; ADR-2608124800 の中心にある失敗はこれ: `audit-gftd-scripts` は**正しく動いて
;; いて、正しい違反を報告していて、数週間だれにも読まれなかった。**
;; 検出器の欠陥ではなく、出力先の欠陥である。だからこの tick の出力先は log では
;; なく、既に読まれている面 —— CLAUDE.md が既に 3 本の SessionStart hook を
;; 置いている、この場所 —— にする。
;;
;; ## 3 分類を分ける。これがこの hook の全部
;;
;; このワークスペースの 4 つの gate は 867 / 282 / 269 / 268 連続失敗している。
;; ADR の結論は **standing red は new red と区別がつかない**。だからここでは:
;;
;;   NEW       直近の run で初めて現れた。名前と詳細を出す。
;;   RESOLVED  前は在って、今は無い。1 行で名前を出して、7 日で忘れる。
;;   STANDING  それ以外。**列挙しない。** 件数と最古の経過日数だけ。
;;             もう言ったことを毎セッション言い直すのは、言っていないのと同じ。
;;
;; **detector の初回 run は BASELINE として出す。** 初日は全部 new なので、
;; それを NEW と呼ぶのは逆側からの同じ誤報になる。
;;
;; ## tick が動いていないことも finding である
;;
;; 「何も報告してこない tick」と「動いていない tick」は外から見て同じ。だから
;; 状態ファイルが無い / 古い場合は、finding と同じ強さで報告する。ADR が数えた
;; 5 本の未インストール plist は、まさにこの沈黙で数週間気付かれなかった。
;; 平常時（新規も解決も無い）でも **1 行だけ**「いつ測った / いくつ standing」を
;; 出す —— 沈黙しないことが、この hook が生きている唯一の証拠になる。
;;
;; fail-open: 判定途中のあらゆる失敗はセッション開始をブロックしない。
;;
;; 手動実行: nbb .claude/hooks/session-start-orgs-detectors.cljs [--state <path>]

(require '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[scripts.nbb-compat :as compat])

(def fs (js/require "node:fs"))
(def os (js/require "node:os"))

(def argv (vec js/process.argv))
(defn- opt [n] (let [i (.indexOf argv n)] (when (and (>= i 0) (< (inc i) (count argv)))
                                            (nth argv (inc i)))))

(def state-file (or (opt "--state")
                    (str (.homedir os) "/.gftd/orgs-detector-tick/state.edn")))
(def registry-file (str (or (not-empty (str (or js/process.env.CLAUDE_PROJECT_DIR "")))
                            ".")
                        "/manifest/orgs-detectors.edn"))

;; 何件まで名前を出すか。NEW が 20 件出る状況はそれ自体が異常なので、
;; 切ったことを明示する。
(def max-listed 6)

(defn read-edn [f]
  (try (edn/read-string (.readFileSync fs f "utf8")) (catch :default _ nil)))

(defn ms->human [ms]
  (let [m (/ ms 60000) h (/ m 60) d (/ h 24)]
    (cond (< m 90) (str (js/Math.round m) "m")
          (< h 48) (str (js/Math.round h) "h")
          :else    (str (js/Math.round d) "d"))))

(defn age-ms [iso] (when iso (- (js/Date.now) (.getTime (js/Date. iso)))))

(defn done!
  ([] (compat/exit 0))
  ([msg]
   (println (json/generate-string
              {:systemMessage msg
               :hookSpecificOutput {:hookEventName "SessionStart"
                                    :additionalContext msg}}))
   (compat/exit 0)))

(defn detector-report
  "1 detector 分の行。返り値 {:lines [..] :loud? bool}"
  [id {:keys [title last-run last-status last-note ok-runs findings resolved
              interval-ms last-scanned]}]
  (let [now (js/Date.now)
        ;; NEW の定義は「first-seen が直近 run の時刻と一致する」。時間窓では
        ;; なく run 単位にするのは、tick の間隔が detector ごとに違う (6h と 24h)
        ;; ので、固定の窓だと片方で取りこぼし片方で二重報告になるため。
        new-ks (sort (for [[k v] findings :when (= (:first-seen v) last-run)] k))
        ;; RESOLVED も NEW と同じ「直近 run で起きたこと」だけを出す。state 側は
        ;; 7 日保持しているが、時間窓で出すと同じ解決を 7 日間毎セッション読ませる
        ;; ことになり、それは standing red を毎回読ませるのと同じ形の騒音になる。
        res-ks (sort (for [[k v] resolved :when (= (:resolved-at v) last-run)] k))
        standing (sort-by #(:first-seen (second %))
                          (remove #(= (:first-seen (second %)) last-run) findings))
        ;; 初回は「測れた run が 1 回目」で判定する。試行回数ではない ——
        ;; 走り出す前に死んだ 1 回目が baseline を消費すると、本当の初回測定が
        ;; 「NEW 13 件」の顔で出てくる (実際に踏んだ)。
        baseline? (= 1 ok-runs)
        ;; 30 分の下限を置く。倍率だけで判定すると、短い interval の
        ;; detector が「たった今走った」直後に stale と言い出す(fixture で実測)。
        ;; 誤報する警告は読まれなくなり、読まれない警告は何も守らない。
        stale-run? (and last-run interval-ms
                        (> (age-ms last-run) (max (* 3 interval-ms) (* 30 60 1000))))
        head (str "● " (name id) " — " title)]
    (cond
      ;; 走った結果を信用できない run。:ok で無いものを黙って通さない。
      (not= :ok last-status)
      {:loud? true
       :lines [head
               (str "  ⚠ 直近の run は " (name (or last-status :unknown))
                    " —— 結果は使えない。" (or last-note ""))
               (str "  最終 run: " (ms->human (age-ms last-run)) " 前")]}

      baseline?
      {:loud? false
       :lines [head
               (str "  BASELINE (初回 run, " (ms->human (age-ms last-run)) "前): "
                    (count findings) " 件を記録。次の run から NEW/RESOLVED が出る。"
                    (when last-scanned (str " scanned=" last-scanned)))]}

      :else
      {:loud? (or (seq new-ks) (seq res-ks) stale-run?)
       :lines
       (concat
         [head]
         (when stale-run?
           [(str "  ⚠ 最終 run が " (ms->human (age-ms last-run)) " 前 —— interval "
                 (ms->human interval-ms) " を大きく超えている。tick が動いていない疑い。")])
         (when (seq new-ks)
           (concat [(str "  NEW " (count new-ks) " 件 (この " (ms->human (age-ms last-run))
                         " 以内に初めて現れた):")]
                   (for [k (take max-listed new-ks)]
                     (str "    + " k "  —  " (get-in findings [k :detail])))
                   (when (> (count new-ks) max-listed)
                     [(str "    … 他 " (- (count new-ks) max-listed) " 件")])))
         (when (seq res-ks)
           [(str "  RESOLVED " (count res-ks) " 件: " (str/join ", " (take max-listed res-ks))
                 (when (> (count res-ks) max-listed)
                   (str " … 他 " (- (count res-ks) max-listed) " 件")))])
         (when (seq standing)
           [(str "  standing " (count standing) " 件 (最古 "
                 (ms->human (age-ms (:first-seen (second (first standing)))))
                 " 前から)。**列挙しない** —— 詳細は "
                 "`nbb --classpath \".:scripts/nbb_compat\" scripts/"
                 (name id) ".cljs`")])
         (when (and (empty? new-ks) (empty? res-ks) (empty? standing))
           [(str "  clean (" (ms->human (age-ms last-run)) "前に測定"
                 (when last-scanned (str ", scanned=" last-scanned)) ")")]))})))

(try
  (let [state (read-edn state-file)
        registry (read-edn registry-file)
        ids (map :id (:detectors registry))]
    (cond
      ;; registry が無い環境(別 repo の checkout 等)では黙る。
      (nil? registry) (done!)

      (nil? state)
      (done!
        (str/join
          "\n"
          [(str "⚠ orgs 依存 detector の tick が一度も走っていない ("
                (count ids) " 件登録済み)")
           ""
           "`scripts/orgs-detector-tick.cljs` は fleet gate になれない検出器"
           "(orgs/ を読むもの)の実行場所です。状態ファイルが在りません:"
           (str "  " state-file)
           ""
           "**報告の無い tick と、動いていない tick は外から見て同じです。**"
           "ADR-2608124800 が数えた 5 本の未インストール plist は、この沈黙で"
           "数週間気付かれませんでした。1 本は 3 つの検証器の唯一の呼び出し元で、"
           "うち 1 つは既に不可逆な資産喪失を出している custody を守るものでした。"
           ""
           "手で 1 回走らせる:"
           "  nbb --classpath \".:scripts/nbb_compat\" scripts/orgs-detector-tick.cljs --force"
           "常駐させる (owner 判断): scripts/com.gftd.orgs-detector-tick.plist の"
           "ヘッダに何がいつ走り何に触るかが書いてあります。"]))

      :else
      (let [reports (for [id ids
                          :let [d (get-in state [:detectors (keyword (name id))])]
                          :when d]
                      (detector-report id d))
            loud? (some :loud? reports)
            unrun (remove #(get-in state [:detectors (keyword (name %))]) ids)
            oldest (some->> (:detectors state) vals (keep :last-run) sort first)]
        (done!
          (str/join
            "\n"
            (concat
              [(if loud?
                 "⚠ orgs 依存 detector (fleet gate にできない検出器) に動きがあります"
                 (str "orgs 依存 detector: 最終測定 "
                      (if oldest (str (ms->human (age-ms oldest)) "前") "不明")
                      " — 新規なし"))]
              (when (seq unrun)
                [(str "  ⚠ 未実行のまま登録されている: " (str/join ", " (map name unrun)))])
              (mapcat :lines reports)))))))
  (catch :default _ nil))
(compat/exit 0)

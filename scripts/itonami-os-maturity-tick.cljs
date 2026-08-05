#!/usr/bin/env nbb
;; scripts/itonami-os-maturity-tick.cljs — 営み OS（ADR-2608060000）の成熟度を
;; 測る。`itonami-os/maturity` loop が毎サイクルこれを先に走らせ、**状態を
;; 再発見せずに**次の 1 手を決めるための入力にする。
;;
;; ## この tick が答える問い
;;
;;   1. 宣言 9 本のうち、いま OS から実際に回せるのは何本か
;;   2. `:unbound-reason` に書いた理由は**まだ本当か**（.clj が消えていないか）
;;   3. 宣言した `:ops` は、各 actor 自身の allowlist と**まだ一致しているか**
;;   4. 公開面と live endpoint は生きているか
;;   5. **次に繋げるべき営みはどれか**（機械的に繋がるものが残っているか）
;;
;; 2 と 3 が要るのは ADR-2607252000 の教訓と同じ: landed は出来事ではなく状態で、
;; 誰も検査しなければ嘘になったことに気付けない。`:unbound-reason` は
;; 「`render_html.clj` だけが JVM 専用」と書いてあるが、それは書いた日の観測で
;; あって、今日の事実ではない。
;;
;; ## 不変条件（姉妹 tick と同一 — ADR-2607254000 / tsukuru tick）
;;
;;   - 捏造ゼロ。probe が走らなかったら :unknown。:ok に丸めない。
;;   - ledger は追記のみ。既存行の編集・削除禁止。
;;   - **この tick は何も書かない・deploy しない・git を触らない。** 測って言うだけ。
;;     着地は loop の agent 側が、この出力を入力として、gate を通してやる。
;;   - 秘密の値は書かない（在否だけ）。
;;
;; 使い方:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/itonami-os-maturity-tick.cljs
;;   nbb ... scripts/itonami-os-maturity-tick.cljs --offline   ; live probe を飛ばす
;;
;; exit 0 常に（監視であって gate ではない）。

(ns itonami-os-maturity-tick
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "fs"))
(def os (js/require "os"))
(def cp (js/require "child_process"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def app (str root "/orgs/network-awai/cloud-itonami"))
(def ledger-file (str home "/.gftd/itonami-os-maturity-tick.ledger.edn"))
(def offline? (boolean (some #{"--offline"} *command-line-args*)))

(defn log! [& xs] (println (str/join " " (map str xs))))

(defn- exists? [p] (try (.existsSync fs p) (catch :default _ false)))
(defn- slurp* [p] (try (str (.readFileSync fs p "utf8")) (catch :default _ nil)))

(defn- sh
  "走らせる。**投げない** —— probe が落ちても tick 全体を道連れにせず
  :unknown を書けるようにする。"
  [cmd args]
  (try
    (let [r (.spawnSync cp cmd (clj->js args)
                        #js {:encoding "utf8" :timeout 60000})]
      {:code (aget r "status") :out (str (aget r "stdout"))})
    (catch :default e {:code nil :out (str e)})))

;; ── 1) 宣言を読む ────────────────────────────────────────────────────────────

(def declaration
  (some-> (slurp* (str app "/os.edn")) edn/read-string))

(defn- tenant-key [v] (str (:org v) "/" (:repo v)))

;; ── 2) :unbound-reason はまだ本当か ─────────────────────────────────────────
;;
;; 理由の本体は「中核が JVM 専用ファイルを持つか」なので、そこだけを実測する。
;; **散文を判定しようとしない** —— 書いてある文と、今日 .clj が何本あるかを
;; 並べて出し、判断は読み手（loop の agent）に渡す。

(defn- jvm-only-files [v]
  (let [dir (str root "/orgs/" (:org v) "/" (:repo v) "/src")]
    (if-not (exists? dir)
      {:checkout :absent :files :unknown}
      (let [{:keys [code out]} (sh "find" [dir "-name" "*.clj"])]
        (if (not= 0 code)
          {:checkout :present :files :unknown}
          {:checkout :present
           :files (->> (str/split-lines out)
                       (remove str/blank?)
                       (mapv #(last (str/split % #"/"))))})))))

;; ── 3) 宣言した :ops は actor の allowlist と一致するか ─────────────────────
;;
;; actor 側の正本は `governor/allowed-ops` か `phase/{read,write}-ops`。
;; **読めなかったら :unknown。** 一致していると仮定しない（検査対象の答えを
;; 仮定することになる）。

(def ^:private set-re #"#\{([^}]*)\}")

(defn- parse-op-set [src marker]
  (when src
    (when-let [i (str/index-of src marker)]
      (when-let [m (re-find set-re (subs src i (min (count src) (+ i 600))))]
        (->> (str/split (second m) #"\s+")
             (map str/trim)
             (remove str/blank?)
             (filter #(str/starts-with? % ":"))
             (map #(keyword (subs % 1)))
             set)))))

(defn- actor-ops [v]
  (let [base (str root "/orgs/" (:org v) "/" (:repo v) "/src/" (:ns v))
        gov (slurp* (str base "/governor.cljc"))
        ph (slurp* (str base "/phase.cljc"))]
    (or (parse-op-set gov "(def allowed-ops")
        (let [r (parse-op-set ph "(def read-ops")
              w (parse-op-set ph "(def write-ops")]
          (when (or r w) (into (or r #{}) (or w #{})))))))

;; ── 3.5) まだ宣言されていない産業の候補 ─────────────────────────────────────
;;
;; superproject ADR-2608070000。**「次にどの産業を繋ぐか」を loop の agent に
;; 毎回考え直させない。** 探索を毎周やり直すと、同じ repo を何度も調べ直し、
;; しかも周ごとに違う基準で選ぶ。
;;
;; 基準は 1 つ: **標準形に適合しているか。** この fleet の governed actor は
;;
;;     operation/build（langgraph の StateGraph）/ phase/{read,write}-ops /
;;     phase/default-phase / store/seed-db / governor
;;
;; を共通して持ち、これが揃っていれば `os/adapters/standard` に数行の shim を
;; 足すだけで OS に繋がる（実測 2026-08-05: 4711 / 4659 / 4920 の 3 本は
;; まさにこの経路で繋がった）。**揃っていない repo は「駄目」ではなく「この
;; 経路では繋がらない」** —— 判定は出すが、順位からは外すだけにする。

(def ^:private isic-dir (str root "/orgs/cloud-itonami"))

(defn- own-scores
  "成熟度スキャンの M_own（ADR-2608052000 の生成物）。無ければ空 —— **無い値を
  0 で埋めない**（測っていない軸は分母から外す、と同じ規律）。"
  []
  (let [p (str root "/90-docs/system-dynamics/itonami-maturity.datoms.edn")]
    (if-let [s (slurp* p)]
      (try
        (into {} (keep (fn [m] (when (and (:repo/path m) (:maturity/own m))
                                 [(:repo/path m) (:maturity/own m)]))
                       (edn/read-string s)))
        (catch :default _ {}))
      {})))

(defn- conformance
  "1 repo が標準形かを実測する。返すのは判定と、外れた理由。"
  [repo]
  (let [src (str isic-dir "/" repo "/src")
        nss (try (->> (.readdirSync fs src #js {:withFileTypes true})
                      (filter #(.isDirectory %))
                      (mapv #(.-name %)))
                 (catch :default _ []))]
    (if (not= 1 (count nss))
      {:repo repo :conformant? false :why :not-a-single-namespace}
      (let [ns- (first nss)
            base (str src "/" ns- "/")
            phase (slurp* (str base "phase.cljc"))
            oper (slurp* (str base "operation.cljc"))
            store (slurp* (str base "store.cljc"))
            gov? (exists? (str base "governor.cljc"))
            core-clj (->> (try (vec (.readdirSync fs base)) (catch :default _ []))
                          (filter #(and (str/ends-with? % ".clj")
                                        (not= "render_html.clj" %)))
                          vec)
            ops (let [r (parse-op-set phase "(def read-ops")
                      w (parse-op-set phase "(def write-ops")]
                  (when (or r w) (into (or r #{}) (or w #{}))))
            why (cond
                  (nil? ops) :no-phase-op-sets
                  (not (and phase (str/includes? phase "(def default-phase"))) :no-default-phase
                  (not (and oper (str/includes? oper "(defn build"))) :no-operation-build
                  (not (and oper (str/includes? oper "langgraph.graph"))) :not-a-langgraph-actor
                  (not (and store (str/includes? store "(defn seed-db"))) :no-seed-db
                  (not gov?) :no-governor
                  (seq core-clj) :jvm-only-core
                  :else nil)]
        (cond-> {:repo repo :ns ns- :conformant? (nil? why) :ops (vec (sort ops))}
          why (assoc :why why)
          (seq core-clj) (assoc :jvm-only-core core-clj))))))

(defn- candidates
  "宣言されていない ISIC repo のうち、標準形に適合しているものを M_own 順で。

  **順位は毎周計算し直す。** 固定リストにすると、繋いだ repo が残り続けたり、
  新しく標準形になった repo が永久に出てこなくなる。"
  [declared-repos]
  (let [scores (own-scores)
        repos (->> (try (vec (.readdirSync fs isic-dir)) (catch :default _ []))
                   (filter #(str/starts-with? % "cloud-itonami-isic-"))
                   (remove declared-repos))
        rows (->> repos
                  (map conformance)
                  (filter :conformant?)
                  (map (fn [r] (assoc r :own (get scores (str "orgs/cloud-itonami/" (:repo r))))))
                  (sort-by #(- (or (:own %) 0))))]
    {:scanned (count repos)
     :conformant (count rows)
     :top (mapv #(select-keys % [:repo :ns :own :ops]) (take 5 rows))}))

;; ── 4) live ──────────────────────────────────────────────────────────────────

(defn- http-status
  ([url] (http-status url nil))
  ([url method]
   (if offline?
     :skipped
     (let [args (cond-> ["-s" "-o" "/dev/null" "-w" "%{http_code}" "--max-time" "20"]
                  method (into ["-X" method "-H" "content-type: application/json" "-d" "{}"])
                  true (conj url))
           {:keys [code out]} (sh "curl" args)]
       (if (= 0 code) (js/parseInt out 10) :unknown)))))

;; ── 走る ─────────────────────────────────────────────────────────────────────

(defn format-str [r]
  (str "  " (if (= :native (:binding r)) "●" "○") " "
       (:vertical r)
       "  ops=" (:ops-declared r) "/" (name (:ops-match r))
       "  jvm-only=" (if (vector? (:jvm-only r))
                       (if (seq (:jvm-only r)) (str/join "," (:jvm-only r)) "なし")
                       (name (:jvm-only r)))
       (when (:mechanically-connectable? r) "  ← 繋げられる")))


(defn -main []
  (when-not declaration
    (log! "itonami-os tick: os.edn が読めない（" app "）— 何も測らずに終了")
    (js/process.exit 0))

  (let [vs (:verticals declaration)
        rows
        (for [v vs]
          (let [k (tenant-key v)
                {:keys [checkout files]} (jvm-only-files v)
                declared (:ops v)
                actual (actor-ops v)]
            {:vertical k
             :binding (:binding v)
             :checkout checkout
             :jvm-only files
             ;; **繋げられるか**の一次判定。中核が portable（.clj が
             ;; render_html だけ、または 0 本）なら adapter を書くだけで繋がる。
             :mechanically-connectable?
             (and (= :unbound (:binding v))
                  (= :present checkout)
                  (vector? files)
                  (every? #{"render_html.clj"} files))
             :ops-declared (count declared)
             :ops-match (cond
                          (nil? actual) :unknown
                          (= (set declared) actual) :ok
                          :else :drift)
             :ops-drift (when (and actual (not= (set declared) actual))
                          {:declared-only (vec (sort (remove actual declared)))
                           :actor-only (vec (sort (remove (set declared) actual)))})}))
        rows (vec rows)
        bound (count (filter #(= :native (:binding %)) rows))
        connectable (filterv :mechanically-connectable? rows)
        drift (filterv #(= :drift (:ops-match %)) rows)
        unknown-ops (filterv #(= :unknown (:ops-match %)) rows)

        surfaces (into {}
                       (for [p (concat ["os"] (map :repo vs))]
                         [p (http-status (str "https://cloud-itonami.itonami.cloud/" p "/"))]))
        ;; 認証ゲートが生きているか。**POST で叩く。** GET だと shim の
        ;; post-only が 405 を返し、認証を 1 度も通らないまま「gate 健在」と
        ;; 読める値が出る（実測 2026-08-05: GET で 405 を見て、コメントには
        ;; 「401 が正」と書いてあった —— 検査が主張どおりのものを検査していない）。
        ;; **未認証で 200 が返ったらそれ自体が欠陥**なので、期待値は 401。
        gate (http-status "https://itonami.cloud/api/cloud-itonami/os-verify/os/journal" "POST")

        pool (candidates (set (map :repo vs)))

        entry {:at (.toISOString (js/Date.))
               :declared (count rows)
               :bound bound
               :unbound (- (count rows) bound)
               :mechanically-connectable (mapv :vertical connectable)
               :ops-drift (mapv #(select-keys % [:vertical :ops-drift]) drift)
               :ops-unknown (mapv :vertical unknown-ops)
               :surfaces surfaces
               :api-gate gate
               ;; **まだ宣言されていない産業の候補**（ADR-2608070000）。
               ;; loop はここを読んで次の 1 本を選ぶ。探索を毎周やり直さない。
               :candidate-pool (select-keys pool [:scanned :conformant])
               :candidates (:top pool)
               :offline? offline?}]

    (log! "── 営み OS 成熟度 tick ──")
    (log! "宣言" (:declared entry) "/ 接続済み" (:bound entry) "/ 未接続" (:unbound entry))
    (doseq [r rows]
      (log! (format-str r)))
    (log! "")
    (log! "機械的に繋げられる営み:" (if (seq connectable)
                                     (str/join ", " (map :vertical connectable))
                                     "なし"))
    (when (seq drift)
      (log! "⚠ 宣言と actor の op がズレている:" (pr-str (mapv :vertical drift))))
    (when (seq unknown-ops)
      (log! "op 集合が読めなかった（:unknown、ok に丸めない）:" (pr-str (mapv :vertical unknown-ops))))
    (log! "")
    (log! "未宣言の ISIC" (:scanned pool) "本のうち、標準形に適合"
          (:conformant pool) "本 —— shim だけで繋がる")
    (doseq [c (:top pool)]
      (log! (str "  · " (:repo c) "  ns=" (:ns c)
                 "  M_own=" (if (:own c) (.toFixed (:own c) 4) "未測定")
                 "  ops=" (count (:ops c)))))
    (log! "公開面:" (pr-str surfaces))
    (log! "API gate（未認証 POST で 401 が正）:" gate
          (if (= 401 gate) "" "  ⚠ 401 ではない"))

    ;; ledger は追記のみ、1 行 1 EDN
    (try
      (.appendFileSync fs ledger-file (str (pr-str entry) "\n"))
      (log! "ledger:" ledger-file)
      (catch :default e (log! "ledger 追記に失敗（測定自体は有効）:" (str e))))

    (log! "")
    (log! "次の 1 手:"
          (cond
            (not= 401 gate) "API gate が 401 を返していない。認証境界を先に確認する"
            (seq drift) "宣言と actor の op のズレを先に直す（面が嘘をついている）"
            (seq drift) "宣言と actor の op のズレを先に直す（面が嘘をついている）"
            (seq connectable) (str (:vertical (first connectable)) " の adapter を書いて接続する")
            (seq (:top pool)) (str "新しい産業を 1 本繋ぐ: " (:repo (first (:top pool)))
                                   "（" (:ns (first (:top pool))) "）")
            (pos? (:unbound entry)) "残る未接続は repo 側の .clj を .cljc に割る作業が要る（機械的ではない）"
            :else "宣言された営みはすべて接続済み。次は operator の実データ経路"))
    (js/process.exit 0)))

(-main)

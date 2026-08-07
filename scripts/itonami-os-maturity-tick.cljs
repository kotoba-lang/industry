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

(defn- dep-classpath
  "この vertical を nbb で load するのに要る classpath。repo 自身と、
  `deps.edn` の `:local/root` が指す sibling の `src` を並べる（推移依存は
  1 段だけ —— これで足りなければ load は失敗し、それは正しい答えになる）。"
  [v]
  (let [self (str root "/orgs/" (:org v) "/" (:repo v))
        deps (or (slurp* (str self "/deps.edn")) "")
        sibs (->> (re-seq #"\"\.\./\.\./([a-z0-9-]+)/([a-z0-9.-]+)\"" deps)
                  (map (fn [[_ o r]] (str root "/orgs/" o "/" r "/src")))
                  distinct)]
    (str/join ":" (cons (str self "/src") sibs))))

(defn- norm
  "照合用の正規化 —— 英数字だけに落とす。**branch 名は repo 名と綴りが揃わない**
  （実測 2026-08-07: repo `cloud-itonami-cofog-03.2` に対して branch は
  `agent/itonami-os-cofog-032`。ドットが落ち prefix も無い）。"
  [x]
  (str/replace (str/lower-case (str x)) #"[^a-z0-9]" ""))

(defn- in-flight?
  "`branches` の中に、この repo を触っている枝があるか。

  **後ろが数字なら別 repo。** `isic-851` の正規化 `isic851` は `isic8510` の
  部分文字列なので、境界を見ないと 8510 の枝が 851 を永久に隠す。"
  [branches repo]
  (let [t (norm (str/replace repo #"^cloud-itonami-" ""))]
    (boolean
     (some (fn [b]
             (let [nb (norm b)]
               (when-let [i (str/index-of nb t)]
                 (let [after (get nb (+ i (count t)))]
                   (not (and after (re-matches #"[0-9]" (str after))))))))
           branches))))

(def ^:private candidate-family-re
  "候補に出す repo の族。**分類に紐づく営みだけ**（産業 / 職業 / 政府機能 / 品目）。

  2026-08-07 まではここが `cloud-itonami-isic-` 前綴じで、**ISIC 以外は候補集合に
  一度も入っていなかった**。COFOG 03.2（消防）は実装済み・nbb で load 可・標準形
  なのに毎周見えず、繋がったのは人が名指しで選んだからである（ADR-2608091000 D8）。

  族を広げるとき entity 面（`lei-` 法人実体 / `iso3166-` 国・官庁 / `assoc-` 業界団体 /
  `municipality-` 自治体）は入れない —— **あれは営みの分類ではなく実体の名簿**で、
  『次に繋ぐ 1 本』として提案する対象ではない。ただし黙って落とすのではなく、
  適合している件数を別枠で報告する（人が判断できるように）。"
  #"^cloud-itonami-(isic|isco|cofog|jsic|nace|naics|unspsc)-")

(defn- candidates
  "宣言されていない**分類ファミリ**の repo のうち、標準形に適合しているものを
  M_own 順で。

  **順位は毎周計算し直す。** 固定リストにすると、繋いだ repo が残り続けたり、
  新しく標準形になった repo が永久に出てこなくなる。"
  [declared-repos in-flight-branches]
  (let [scores (own-scores)
        all-dirs (->> (try (vec (.readdirSync fs isic-dir)) (catch :default _ []))
                      (filter #(str/starts-with? % "cloud-itonami-"))
                      (remove declared-repos))
        family (filterv #(re-find candidate-family-re %) all-dirs)
        ;; **着手中の枝がある repo は候補から外す。** ここを見ていなかったのが
        ;; 2026-08-07 まで残っていた穴 —— `in-flight?` は宣言済み vertical にしか
        ;; 掛かっておらず、**二重実装が起きるのは候補側**である（ADR-2608070000 が
        ;; 記録した isic-4921 の 2 回実装はまさにこれ）。実測 2026-08-07: 別セッションが
        ;; `agent/itonami-os-isic-6492` で作業中なのに、tick は isic-6492 を
        ;; 「次の 1 手」に出し続けていた。
        in-flight-skipped (filterv #(in-flight? in-flight-branches %) family)
        repos (filterv #(not (in-flight? in-flight-branches %)) family)
        ;; 分類に紐づかない適合 repo（entity 面など）。**候補には出さないが数える。**
        outside (->> (remove #(re-find candidate-family-re %) all-dirs)
                     (map conformance)
                     (filter :conformant?)
                     (mapv :repo))
        rows (->> repos
                  (map conformance)
                  (filter :conformant?)
                  (map (fn [r] (assoc r :own (get scores (str "orgs/cloud-itonami/" (:repo r))))))
                  (sort-by #(- (or (:own %) 0))))
        ;; **提案する分だけ nbb で実際に require してみる。** 形が揃っていても
        ;; 面を作る nbb で load できなければ繋がらない（isic-6910 の js-mod が
        ;; その実例）。222 本全部を probe すると tick が遅くなるので、
        ;; **順位上位だけ**を確かめて、落ちたものは順に次へ送る。
        ;; 落ちた理由も残す —— 「候補から消えた」だけだと理由が失われる。
        probed (loop [cs rows acc [] rejected []]
                 (cond
                   (>= (count acc) 5) {:top acc :rejected rejected}
                   (empty? cs) {:top acc :rejected rejected}
                   :else
                   (let [c (first cs)
                         v {:org "cloud-itonami" :repo (:repo c) :ns (:ns c)}
                         {:keys [code]} (sh "nbb" ["--classpath" (dep-classpath v)
                                                   "-e" (str "(require '[" (:ns c) ".operation])")])]
                     (if (= 0 code)
                       (recur (rest cs) (conj acc c) rejected)
                       (recur (rest cs) acc
                              (conj rejected {:repo (:repo c) :why :not-loadable-under-nbb}))))))]
    {:scanned (count repos)
     :conformant (count rows)
     :nbb-rejected (:rejected probed)
     ;; **1 行直せば候補になる族を見えるようにする。** 実測 2026-08-07:
     ;; COFOG は 5 本中 4 本が `:no-seed-db` だけで落ちている —— cofog-03.2 に
     ;; 足したのと同じ 1 行で候補になる。族ごとに『落ちた理由の最頻値』を出す。
     :near-miss (into (sorted-map)
                      (for [[fam rs] (group-by #(second (re-find candidate-family-re %))
                                               family)
                            :let [cs (map conformance rs)
                                  fails (remove :conformant? cs)]
                            :when (seq fails)]
                        [fam {:n (count rs)
                              :conformant (count (filter :conformant? cs))
                              :why (into (sorted-map) (frequencies (map :why fails)))}]))
     :outside-families {:conformant (count outside)
                        :sample (vec (take 3 outside))}
     :in-flight-skipped in-flight-skipped
     :top (mapv #(select-keys % [:repo :ns :own :ops]) (:top probed))}))


;; ── 2b) 標準形か（shim だけで繋がるか）────────────────────────────────────
;;
;; **`.clj` の有無では判定できない。** 実測（2026-08-06）: isic-853 / 854 は
;; JVM 専用が `render_html.clj` 1 本だけで、旧判定は「繋げられる」と言い続けたが、
;; 両者とも `operation/build` を持たず langgraph StateGraph 自体が無い
;; （`all-operations` + `execute-proposal!` という別アーキテクチャ）。
;; `adapters/standard` は駆動できず、繋ぐには adapter に判定を足すことになる ——
;; それは禁じている変更なので、書けば捨てることになる。実際にループが 1 周
;; まるごと使って同じ結論に達した。
;;
;; だから **`standard/vertical` が実際に要求するもの**を見る:
;;   - `operation/build`         … StateGraph を組む入口
;;   - `phase/{read,write}-ops`  … op の allowlist（`ops` の出所）
;;   - `store/seed-db`           … store の入口
;; どれか 1 つでも欠ければ shim では繋がらない。

(defn- standard-shape [v]
  (let [base (str root "/orgs/" (:org v) "/" (:repo v) "/src/" (:ns v))
        op (slurp* (str base "/operation.cljc"))
        ph (slurp* (str base "/phase.cljc"))
        st (slurp* (str base "/store.cljc"))]
    (cond
      (not (and op ph st)) {:standard? :unknown :missing [:source-unreadable]}
      :else
      (let [missing (cond-> []
                      (not (str/includes? op "(defn build")) (conj :operation/build)
                      (not (str/includes? ph "(def read-ops")) (conj :phase/read-ops)
                      (not (str/includes? ph "(def write-ops")) (conj :phase/write-ops)
                      (not (str/includes? st "(defn seed-db")) (conj :store/seed-db))]
        (if (seq missing)
          {:standard? false :missing missing}
          ;; **形が揃っていても、面を作る nbb で load できなければ繋がらない。**
          ;; 実測（2026-08-06）: isic-6910 は標準 4 点を全部持ち JVM でも動くが、
          ;; `formation/registry.cljc` の ISO 7064 検査数字が `js-mod` を使って
          ;; おり（LEI の 18-20 桁は 53-bit double を溢れるので BigInt 演算が
          ;; 要る）、nbb の cljs.core にこの関数が無い。面は nbb の生成器が
          ;; **実物の actor を回して**描くので、これは繋がらないことを意味する。
          ;;
          ;; 形だけを見ていた版はこの repo を毎周「次の 1 手」に出し続け、
          ;; ループが 1 反復まるごと使って同じ結論に達するところだった。
          ;; **実際に require してみるのが唯一の確かめ方**なので、そうする。
          (let [{:keys [code]} (sh "nbb" ["--classpath" (str (dep-classpath v))
                                          "-e" (str "(require '[" (:ns v) ".operation])")])]
            (if (= 0 code)
              {:standard? true :missing []}
              {:standard? false :missing [:not-loadable-under-nbb]}))))))) 


;; ── 2c) 既に PR で待っている営み ─────────────────────────────────────────
;;
;; **tick は main しか読まない。** そのままだと、open PR で review 待ちの営みを
;; 毎周「次の 1 手」として出し続け、ループが同じものを作り直す。実測
;; （2026-08-05）: isic-4921 が #506 と #507 で 2 回実装され、3 本目も作られ
;; かけた（作った側が「#507 と同じもの」と気づいて破棄した）。1 反復まるごとの
;; 損失。
;;
;; `gh` が無い / 落ちた場合は **:unknown** にして、候補から外さない ——
;; 「PR があるかもしれない」で作業を止める方が、二重実装より悪い。

(defn- queued-repos
  "既に着手済みの営みを拾う。**PR だけでは足りない。**

  実測 2026-08-07: この repo の open PR は **0 件**だが、`agent/itonami-os-isic-6492`
  という接続作業中の枝が remote に在った。接続フロー（skill `itonami-os-connect`）は
  PR を作らず `gh api .../merges` でサーバ側マージするので、**PR を見ている限り
  進行中の作業は 1 件も見えない**。旧実装はさらに branch 名から
  `cloud-itonami-isic-NNNN` を抜こうとしていたが、実際の枝名は
  `agent/itonami-os-isic-6492` で prefix が無く、**1 件も抽出できていなかった**
  （二重実装を防ぐはずの仕掛けが、静かに何も防いでいなかった）。

  見るのは接続フローが作る名前（`itonami-os` / `os-connect`）の枝だけ。無関係な
  枝まで見ると、たまたま似た綴りの枝が候補を隠す。`gh` が落ちたら `:unknown` に
  して候補から外さない（従来どおり fail-open）。"
  []
  (if offline?
    {:status :skipped :branches []}
    (let [pr (sh "gh" ["pr" "list" "--repo" "network-awai/cloud-itonami"
                       "--state" "open" "--limit" "50"
                       "--json" "headRefName" "--jq" ".[].headRefName"])
          br (sh "gh" ["api" "repos/network-awai/cloud-itonami/branches"
                       "--paginate" "--jq" ".[].name"])]
      (if (and (not= 0 (:code pr)) (not= 0 (:code br)))
        {:status :unknown :branches []}
        {:status (if (and (= 0 (:code pr)) (= 0 (:code br))) :ok :partial)
         :branches (->> (concat (str/split-lines (or (:out pr) ""))
                                (str/split-lines (or (:out br) "")))
                        (map str/trim)
                        (remove str/blank?)
                        (filter #(or (str/includes? % "itonami-os") (str/includes? % "os-connect")))
                        distinct
                        vec)}))))

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
                shape (standard-shape v)
                declared (:ops v)
                actual (actor-ops v)]
            {:vertical k
             :binding (:binding v)
             :checkout checkout
             :jvm-only files
             ;; **繋げられるか**の判定は `standard/vertical` が実際に要求する
             ;; ものを見る（`.clj` の有無ではない —— 上の standard-shape 参照）。
             :standard? (:standard? shape)
             :standard-missing (:missing shape)
             ;; **2 条件の AND。**片方だけでは足りない:
             ;;   - 標準形だけ見ると 6310/5820 が候補に出る。あちらは
             ;;     `facts.clj` / store・http・llm など **JVM 専用の中核**を持ち、
             ;;     cljs の edge bundle に載らない。
             ;;   - `.clj` だけ見ると 853/854 が候補に出る。あちらは JVM 専用が
             ;;     `render_html.clj` 1 本でも StateGraph 自体が無い。
             ;; 実測でどちらの誤りも 1 反復ずつ無駄にした（2026-08-05／06）。
             :mechanically-connectable?
             (and (= :unbound (:binding v))
                  (= :present checkout)
                  (true? (:standard? shape))
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
        queued (queued-repos)
        ;; 既に PR が open な営みは候補から外す（ただし理由を残す）
        connectable-all (filterv :mechanically-connectable? rows)
        in-flight (fn [v] (in-flight? (:branches queued) (last (str/split (:vertical v) #"/"))))
        connectable (filterv (complement in-flight) connectable-all)
        queued-out (filterv in-flight connectable-all)
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

        pool (candidates (set (map :repo vs)) (:branches queued))

        entry {:at (.toISOString (js/Date.))
               :declared (count rows)
               :bound bound
               :unbound (- (count rows) bound)
               :mechanically-connectable (mapv :vertical connectable)
               :queued-in-pr (mapv :vertical queued-out)
               :pr-lookup (:status queued)
               :in-flight-branches (:branches queued)
               :ops-drift (mapv #(select-keys % [:vertical :ops-drift]) drift)
               :ops-unknown (mapv :vertical unknown-ops)
               :surfaces surfaces
               :api-gate gate
               ;; **まだ宣言されていない産業の候補**（ADR-2608070000）。
               ;; loop はここを読んで次の 1 本を選ぶ。探索を毎周やり直さない。
               :candidate-pool (select-keys pool [:scanned :conformant :near-miss :outside-families])
               :candidates (:top pool)
               :offline? offline?}]

    (log! "── 営み OS 成熟度 tick ──")
    (log! "宣言" (:declared entry) "/ 接続済み" (:bound entry) "/ 未接続" (:unbound entry))
    (doseq [r rows]
      (log! (format-str r)))
    (log! "")
    (when (seq queued-out)
      (log! "既に別の枝が触っている（候補から外した）:" (str/join ", " (map :vertical queued-out))))
    (when (= :unknown (:status queued))
      (log! "⚠ open PR も branch 一覧も引けなかった（gh 不在/失敗）。候補は二重着手を含みうる。"))
    (when (= :partial (:status queued))
      (log! "⚠ PR / branch のどちらかしか引けなかった。二重着手の検出は不完全。"))
    (log! "機械的に繋げられる営み:" (if (seq connectable)
                                     (str/join ", " (map :vertical connectable))
                                     "なし"))
    (when (seq drift)
      (log! "⚠ 宣言と actor の op がズレている:" (pr-str (mapv :vertical drift))))
    (when (seq unknown-ops)
      (log! "op 集合が読めなかった（:unknown、ok に丸めない）:" (pr-str (mapv :vertical unknown-ops))))
    (log! "")
    (log! "未宣言の分類ファミリ（isic/isco/cofog/jsic/nace/naics/unspsc）" (:scanned pool)
          "本のうち、標準形に適合" (:conformant pool)
          "本 —— ただし「適合」は静的な形の話で、面を作る
                              nbb で load できるかは別（上位だけ実際に require して確かめる）")
    (log! "  族ごとの落ちた理由（1 行直せば候補になる族が見える）:")
    (doseq [[fam m] (:near-miss pool)]
      (log! (str "    " fam ": " (:n m) " 本中 適合 " (:conformant m)
                 " / " (pr-str (:why m)))))
    (when (seq (:in-flight-skipped pool))
      (log! (str "  別の枝が着手中なので候補から外した: "
                 (str/join ", " (:in-flight-skipped pool)))))
    (let [o (:outside-families pool)]
      (log! (str "  分類に紐づかない族（lei / iso3166 / assoc / municipality 等）で適合しているもの: "
                 (:conformant o) " 本 —— **候補には出さない**（営みの分類ではなく実体の名簿）。例: "
                 (str/join ", " (:sample o)))))
    (when (seq (:nbb-rejected pool))
      (log! "  nbb で load できず候補から外した:"
            (str/join ", " (map #(str (:repo %) "(" (name (:why %)) ")") (:nbb-rejected pool)))))
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
            ;; `--offline` のとき gate は :skipped であって「401 でない」ではない。
            ;; 測っていないものを異常として報告すると、次の 1 手が毎回
            ;; 『認証境界を確認しろ』になり、本当の次の 1 手が隠れる
            ;; （実測 2026-08-06）。**測っていない = 分母から外す。**
            (and (not offline?) (not= 401 gate))
            "API gate が 401 を返していない。認証境界を先に確認する"
            (seq drift) "宣言と actor の op のズレを先に直す（面が嘘をついている）"
            (seq drift) "宣言と actor の op のズレを先に直す（面が嘘をついている）"
            (seq connectable) (str (:vertical (first connectable)) " の adapter を書いて接続する")
            (seq (:top pool)) (str "新しい産業を 1 本繋ぐ: " (:repo (first (:top pool)))
                                   "（" (:ns (first (:top pool))) "）")
            (pos? (:unbound entry)) "残る未接続は repo 側の .clj を .cljc に割る作業が要る（機械的ではない）"
            :else "宣言された営みはすべて接続済み。次は operator の実データ経路"))
    (js/process.exit 0)))

(-main)

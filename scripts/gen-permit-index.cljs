#!/usr/bin/env nbb
;; scripts/gen-permit-index.cljs — cloud-itonami の governed actor が各自の
;; `facts.cljc` に持っている**法規制カタログ**を 1 つの datom データセットに
;; 抽出する。superproject ADR-2608080000。
;;
;;   nbb --classpath ".:scripts" scripts/gen-permit-index.cljs
;;   nbb --classpath ".:scripts" scripts/gen-permit-index.cljs --check
;;
;; 出力: 90-docs/regulatory/permits.datoms.edn（`manifest/edn-query.cljs` の
;; index 面に `:source/dataset "permits"` として載る）
;;
;; ## なぜ要るか
;;
;; 「採掘許可が要る法域はどこか」「配電の所管庁は誰か」は**今日どこにも問い合わせ
;; られない**。答えは実在する —— 373 repo の `facts/catalog` に、根拠法・所管庁・
;; 必要証跡・公式 URL つきで 1,455 行ある（実測 2026-08-06）。ただし 373 個の
;; `.cljc` に散っていて、横断で引く手段が無い。ここが作るのはその 1 面。
;;
;; **正本は各 actor の `facts.cljc` のまま。** ここが作るのは射影であって、
;; 規制の正本ではない。actor 側を直せば次の生成で反映される。
;;
;; ## 実測が設計を決めた 5 点（推測ではない）
;;
;; 1. **`catalog` という var 名は 3 種類の別物に使われている。**
;;    requirement map（1,455 行 / 373 repo・これが目的のもの）/ record vector
;;    （`:statute/*` `:ordinance/*`、2,663 行 / 291 repo）/ source allowlist
;;    （16 repo、governor の引用元 allowlist）。**var 名ではなく値の形で分岐する。**
;; 2. **第 1 階層のキーは法域とは限らない。** 881 個の map catalog のうち 187 が
;;    自治体 slug（"paris"）・団体 slug・制度 keyword（`:davis-bacon-act`）・
;;    製品セグメント（"AUTOMOTIVE"）で keyed。生キーは `:permit/subject-key` に
;;    残し、**実在の ISO3 と照合できたときだけ** `:permit/jurisdiction` を出す
;;    —— [A-Z]{3} を国だと思うと 161 件の偽の国コードを鋳造する（"EUR" "IEC" は
;;    3 文字だが国ではない）。
;; 3. **iso3 は文字列と keyword の両方で書かれている**（9 ファイルが `:JPN` 形）。
;; 4. **第 2 の引用系統は固定リストではない。** `<prefix>-<suffix>` の族が 194 種類
;;    （`:corporate-number-*` が 166 repo、`:rep-*` が 113、`:blast-*` が 2、
;;    残り約 180 は 1 repo ずつ）。ハードコードすると構造上 8 属性 × N トピックで
;;    済むものが 692 属性になる。**接尾辞で束ねて、接頭辞をトピックにする。**
;; 5. **`:legal-basis` は結合キーにできない。** 1,355 スロットに 1,018 個の異なる
;;    文字列があり、同じ法律が repo ごとに違う表記で書かれている（"FAR" と
;;    "Federal Acquisition Regulation (FAR); System for Award Management"）。
;;    手続き側（kyoninka）との交差は**実測ゼロ**。だから生成物は
;;    `:join/status :unusable` を**データとして申告する** —— 使えない結合を
;;    query の書き手が自分で発見しなくて済むように。

(ns gen-permit-index
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [lib.cljc-literal :as lit]))

(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (.cwd js/process)))
(def isic-dir (str root "/orgs/cloud-itonami"))
(def out-file (str root "/90-docs/regulatory/permits.datoms.edn"))
(def check? (boolean (some #{"--check"} *command-line-args*)))

(defn- slurp* [p] (try (str (.readFileSync fs p "utf8")) (catch :default _ nil)))
(defn- ls [p] (try (vec (.readdirSync fs p)) (catch :default _ [])))

;; ── 実在の ISO3 は「導出」せず「観測」する ──────────────────────────────────
;;
;; `cloud-itonami-iso3166-<iso3>` repo の実在が、この workspace における
;; 「その国コードを誰かが実際に調べた」という唯一の証拠。**正規表現で
;; [A-Z]{3} を国だと決めない** —— isic-3811 が `{:jpn "JPN" …}` という閉じた map で
;; 写しているのと同じ理由で、「まだ誰も調べていない id を鋳造しない」。

(def known-iso3
  (into #{}
        (comp (filter #(str/starts-with? % "cloud-itonami-iso3166-"))
              (map #(str/upper-case (subs % (count "cloud-itonami-iso3166-"))))
              (filter #(re-matches #"[A-Z]{3}" %)))
        (ls isic-dir)))

;; ── 引用トピックの束ね方 ────────────────────────────────────────────────────

(def ^:private citation-suffixes
  #{"owner-authority" "legal-basis" "national-spec" "provenance"
    "criteria" "basis" "note" "authority"})

(def ^:private base-keys
  #{:name :owner-authority :legal-basis :national-spec :provenance :required-evidence})

(defn- split-topic
  "`:blast-legal-basis` → `[\"blast\" \"legal-basis\"]`。基本キーなら nil。"
  [k]
  (let [s (name k)]
    (when-not (contains? base-keys k)
      (some (fn [suf]
              (when (and (str/ends-with? s (str "-" suf))
                         (> (count s) (inc (count suf))))
                [(subs s 0 (- (count s) (count suf) 1)) suf]))
            citation-suffixes))))

(defn- citations
  "基本キー以外を `<topic> → {<suffix> 値}` に束ねる。**族名をハードコードしない。**"
  [req]
  (reduce (fn [acc [k v]]
            (if-let [[topic suf] (split-topic k)]
              (assoc-in acc [topic suf] v)
              acc))
          {} req))

(defn- provenance-urls
  "`:provenance` は 1 文字列に複数 URL が \" ; \" で入っていることがある（実測）。"
  [v]
  (when (string? v)
    (->> (str/split v #"\s+;\s+") (map str/trim) (remove str/blank?) vec)))

;; ── requirement map かどうかは値の形で決める ────────────────────────────────

(defn- requirement-map?
  "第 1 階層の値が『引用を持つ平坦なマップ』か。record vector（`:statute/*` 等）と
  source allowlist（`[{:id :name :class :access :url}]`）を除くのが目的。"
  [v]
  (and (map? v)
       (some #(contains? v %) [:legal-basis :owner-authority :national-spec :provenance])))

(defn- normalize-key [k]
  (cond (keyword? k) (if (namespace k) (str (namespace k) "/" (name k)) (name k))
        (string? k) k
        :else (str k)))

(defn- jurisdictions-row?
  "第 4 の形（`(def jurisdictions)`、31 repo）。`{:jp/maff {:id :name
  :required-evidence [...]}}` で、**法的引用を持たない** —— 法令名は `:name` の
  中に散文で入っているだけ。要件行と同じ tier に混ぜると『引用付き』の件数が
  水増しされるので、`:permit/source-shape` で分ける。

  キーは `<iso2>/<authority>` の keyword なので、**法域は導出しない** ——
  `jp` を `JPN` に写すのは「誰も調べていない id を鋳造する」側の操作。"
  [v]
  (and (map? v) (contains? v :required-evidence) (contains? v :name)))

;; ── 1 repo を読む ───────────────────────────────────────────────────────────

(defn- facts-files [repo]
  (let [src (str isic-dir "/" repo "/src")]
    (for [ns- (ls src)
          :let [p (str src "/" ns- "/facts.cljc")]
          :when (.existsSync fs p)]
      {:ns ns- :path p})))

(defn- isic-of [repo]
  (second (re-find #"cloud-itonami-isic-([0-9]+)" repo)))

(defn- scan-repo [repo]
  (for [{:keys [ns path]} (facts-files repo)
        :let [src (slurp* path)
              cat0 (when src (lit/read-def-literal src "catalog"))
              ;; `catalog` が無い repo のうち 31 本は `(def jurisdictions)` を使う
              ;; （実測 2026-08-06）。**「catalog が無い」を「規制が無い」と読まない。**
              alt (when (and src (lit/error? cat0))
                    (lit/read-def-literal src "jurisdictions"))
              ;; **エラー値は map なので `map?` だけでは判定できない。**
              ;; `read-def-literal` は失敗を `{::error ...}` で返す（nil を返して
              ;; 「空」と区別が付かなくしないため）。その設計の代償がここで、
              ;; `(map? alt)` が真になって全部 :jurisdictions-map に化けた（実測）。
              ok-cat (and (map? cat0) (not (lit/error? cat0)))
              ok-alt (and (map? alt) (not (lit/error? alt)))
              shape (cond ok-cat :requirement-map ok-alt :jurisdictions-map :else nil)
              cat (cond ok-cat cat0 ok-alt alt :else nil)]]
    (cond
      (nil? src) {:repo repo :ns ns :outcome :unreadable-file}
      ;; **:unparseable と呼ばない。** パーサは落ちていない —— その var が無いだけ。
      ;; 誤ラベルは「抽出器が壊れている」と読ませ、実際の欠落（規制を持たない
      ;; actor か、まだ別の形）を隠す。
      (nil? cat) {:repo repo :ns ns
                  :outcome (if (lit/error? cat0) :no-catalog-def :not-a-map)}
      :else
      (let [rows (for [[k v] cat
                       :when (if (= shape :jurisdictions-map)
                               (jurisdictions-row? v)
                               (requirement-map? v))
                       :let [sk (normalize-key k)]]
                   (cond-> {:permit/repo (str "orgs/cloud-itonami/" repo)
                            :permit/ns ns
                            :permit/subject-key sk
                            ;; **どの形から来たか。** :jurisdictions-map の行は
                            ;; 法的引用を持たない —— 引用付きの件数を数えるときは
                            ;; ここで絞る。
                            :permit/source-shape shape
                            :source/dataset "permits"}
                     (isic-of repo) (assoc :permit/isic (isic-of repo))
                     ;; **観測済みの ISO3 のときだけ法域として出す。**
                     (contains? known-iso3 sk) (assoc :permit/jurisdiction sk)
                     (:name v) (assoc :permit/subject-name (:name v))
                     (:owner-authority v) (assoc :permit/authority (:owner-authority v))
                     (:legal-basis v) (assoc :permit/legal-basis (:legal-basis v))
                     (:national-spec v) (assoc :permit/national-spec (:national-spec v))
                     (seq (provenance-urls (:provenance v)))
                     (assoc :permit/provenance (provenance-urls (:provenance v)))
                     (seq (:required-evidence v))
                     (assoc :permit/required-evidence (mapv str (:required-evidence v)))
                     (seq (citations v))
                     (assoc :permit/citations (pr-str (citations v)))))]
        {:repo repo :ns ns
         :outcome (if (seq rows) :rows :other-catalog-shape)
         :rows (vec rows)}))))

;; ── 手続き側との結合（動くものだけ宣言する）────────────────────────────────
;;
;; 実測（2026-08-06）で今日壊れずに動く結合は 1 本だけ:
;; licensed-operator の `:licence/kyoninka-procedure` → kyoninka の `:procedure/id`
;; （キーワード厳密一致・片方向）。**それを拾い、他は「使えない」と申告する。**

(defn- kyoninka-links []
  ;; パスは `src/cloud_itonami/licensed_operator/catalog.cljc`（実測 2026-08-06）。
  ;; 最初 `src/licensed_operator/…` と書いて 0 本になった —— **0 本を「結合が
  ;; 無い」と読むと、実装済みの唯一の結合を見落とす。** だから存在しない場合は
  ;; :absent ではなく :file-not-found を出して区別する。
  (let [p (str isic-dir "/cloud-itonami-licensed-operator/src/cloud_itonami"
               "/licensed_operator/catalog.cljc")
        src (slurp* p)]
    (if-not src
      {:found false :links []}
      {:found true
       :links (vec (distinct (map second (re-seq #"kyoninka-procedure\s+:([a-z0-9-]+)" src))))})))


;; ── 手続きへの橋（licensed-operator の catalog）──────────────────────────────
;;
;; ここだけが**要件側と手続き側を実際に繋いでいる**。キーは
;; `["JPN" :sector/second-hand-dealing]` の 2 つ組で、値が `:licence/*` を持ち、
;; そのうち `:licence/kyoninka-procedure` が kotoba-lang/kyoninka の
;; `:procedure/id` を**キーワードの厳密一致で**指す（表記揺れが構造的に起きない）。
;;
;; 各 actor の facts.cljc とは形も出所も違うので、`:permit/source-shape
;; :licensed-operator` で分ける。**同じ tier に混ぜると「引用付きの要件」の
;; 件数に手続きの行が紛れ込む。**

(defn- licensed-operator-rows []
  (let [p (str isic-dir "/cloud-itonami-licensed-operator/src/cloud_itonami"
               "/licensed_operator/catalog.cljc")
        src (slurp* p)
        cat (when src (lit/read-def-literal src "catalog"))]
    (if-not (and (map? cat) (not (lit/error? cat)))
      {:found false :rows []}
      {:found true
       :rows (vec (for [[k v] cat
                        :when (and (vector? k) (map? v) (:licence v))
                        :let [[juris sector] k
                              lic (:licence v)]]
                    (cond-> {:permit/repo "orgs/cloud-itonami/cloud-itonami-licensed-operator"
                             :permit/ns "licensed_operator"
                             :permit/subject-key (str juris " " sector)
                             :permit/source-shape :licensed-operator
                             :source/dataset "permits"}
                      (contains? known-iso3 (str juris))
                      (assoc :permit/jurisdiction (str juris))
                      sector (assoc :permit/sector (str sector))
                      (:licence/name lic) (assoc :permit/licence-name (:licence/name lic))
                      (:licence/law lic) (assoc :permit/legal-basis (:licence/law lic))
                      (:licence/authority lic) (assoc :permit/authority (:licence/authority lic))
                      (:licence/window lic) (assoc :permit/window (:licence/window lic))
                      (:licence/fee-jpy lic) (assoc :permit/fee-jpy (:licence/fee-jpy lic))
                      (:licence/valid-years lic) (assoc :permit/valid-years (:licence/valid-years lic))
                      (some? (:licence/obtainable-by-company? lic))
                      (assoc :permit/obtainable-by-company? (:licence/obtainable-by-company? lic))
                      ;; **辿れる結合。** これがあるから query から手続きへ降りられる。
                      (:licence/kyoninka-procedure lic)
                      (assoc :permit/kyoninka-procedure (name (:licence/kyoninka-procedure lic))))))})))


;; ── 手続きそのもの（kotoba-lang/kyoninka）────────────────────────────────────
;;
;; ADR-2608080000 の「次の 1 手」。あちらの schema を実データに合わせ、法域軸と
;; 通貨中立な手数料を足したので（kyoninka be342c4）、手続き側も同じ面に載る。
;;
;; **要件側とは別の entity。** 要件（何が要るか）と手続き（どう取るか）は別の
;; 事実で、混ぜると「引用付きの要件」の件数に手続きが紛れ込む。
;; `:procedure/id` で `:permit/kyoninka-procedure` と結合する。
;;
;; 複合値（`:procedure/fee` / `:procedure/standard-period-days`）は**スカラに割って
;; 載せる** —— map のまま 1 属性にすると「¥19,000 の手続きを探す」が書けない。
;; `:verify` は落とさず `*-verify` に残す（改定されうる標準値だと分かるように）。

(defn- kyoninka-procedures []
  (let [dir (str root "/orgs/kotoba-lang/kyoninka/src/kyoninka")]
    (if-not (.existsSync fs dir)
      {:found false :rows []}
      {:found true
       :rows
       (vec (for [f (ls dir)
                  :when (str/ends-with? f ".cljc")
                  :let [src (slurp* (str dir "/" f))
                        p (when src (lit/read-def-literal src "procedure"))]
                  :when (and (map? p) (not (lit/error? p)) (:procedure/id p))]
              (let [fee (:procedure/fee p)
                    per (:procedure/standard-period-days p)
                    verify-str (fn [m] (when-let [v (:verify m)] (:how v)))]
                (cond-> {:procedure/id (name (:procedure/id p))
                         :procedure/source-repo "orgs/kotoba-lang/kyoninka"
                         :source/dataset "permits"}
                  (:procedure/name p) (assoc :procedure/name (:procedure/name p))
                  (:procedure/law p) (assoc :procedure/law (:procedure/law p))
                  (:procedure/authority p) (assoc :procedure/authority (:procedure/authority p))
                  (:procedure/window p) (assoc :procedure/window (:procedure/window p))
                  (:procedure/jurisdiction p) (assoc :procedure/jurisdiction (:procedure/jurisdiction p))
                  ;; **法の適用範囲。** 法域コードより狭いことがある（SMDA 2013 は
                  ;; England and Wales のみ）。省略は法域全域だが、確認したなら
                  ;; 書く —— 省略は『確認した』と『考えていない』を区別できない。
                  (:procedure/extent p) (assoc :procedure/extent (:procedure/extent p))
                  ;; 出所を data として持つ（docstring だと query から引けない）。
                  (seq (:procedure/source-urls p))
                  (assoc :procedure/source-urls (vec (:procedure/source-urls p)))
                  ;; **額は最小単位の整数**（£191.02 → 19102 pence）。額を持たない
                  ;; 手続きが実在する —— 英スクラップ金属は council が、独は州が
                  ;; 決めるので、額はここでは決まらない（`:verify` がそれを言う）。
                  (:amount fee) (assoc :procedure/fee-amount (:amount fee))
                  (:minor-unit fee) (assoc :procedure/fee-minor-unit (:minor-unit fee))
                  (:currency fee) (assoc :procedure/fee-currency (:currency fee))
                  (:kind fee) (assoc :procedure/fee-kind (str (:kind fee)))
                  (verify-str fee) (assoc :procedure/fee-verify (verify-str fee))
                  (:value per) (assoc :procedure/standard-period-days (:value per))
                  (verify-str per) (assoc :procedure/standard-period-verify (verify-str per))
                  (:procedure/valid-years p) (assoc :procedure/valid-years (:procedure/valid-years p))
                  (seq (:procedure/steps p)) (assoc :procedure/step-count (count (:procedure/steps p)))
                  (seq (:procedure/documents p)) (assoc :procedure/document-count (count (:procedure/documents p)))
                  ;; **人が動く step の数。** この library は提案するだけで実行しない、
                  ;; という性質を query から見えるようにする。
                  (seq (:procedure/steps p))
                  (assoc :procedure/human-step-count
                         (count (filter :step/requires-human (:procedure/steps p))))
                  (seq (:procedure/legal-questions p))
                  (assoc :procedure/open-legal-questions
                         (count (remove #(= :settled (:question/status %))
                                        (:procedure/legal-questions p))))))))})))

;; ── 走る ────────────────────────────────────────────────────────────────────

(defn -main []
  (let [repos (->> (ls isic-dir) (filter #(not (str/starts-with? % "."))) sort)
        results (mapcat scan-repo repos)
        by (group-by :outcome results)
        lo (licensed-operator-rows)
        procs (kyoninka-procedures)
        rows (-> (vec (mapcat :rows (:rows by)))
                 (into (:rows lo))
                 (into (:rows procs)))
        links (kyoninka-links)
        juris (->> rows (keep :permit/jurisdiction) distinct sort vec)
        non-juris (->> rows (remove :permit/jurisdiction) (map :permit/subject-key) distinct count)
        coverage
        {:permit/coverage true
         :source/dataset "permits"
         :coverage/repos-scanned (count repos)
         :coverage/facts-files (count results)
         :coverage/files-with-rows (count (:rows by))
         :coverage/rows-with-citation
         (count (filter #(= :requirement-map (:permit/source-shape %)) rows))
         :coverage/rows-without-citation
         (count (filter #(= :jurisdictions-map (:permit/source-shape %)) rows))
         :coverage/rows (count rows)
         :coverage/jurisdictions (count juris)
         :coverage/jurisdiction-list juris
         :coverage/non-jurisdiction-subject-keys non-juris
         ;; **除外したものを数えて申告する。** 「載っていない = 存在しない」と
         ;; 読まれないように、何を意図的に外したかをデータ側に置く。
         :coverage/excluded
         (pr-str {:other-catalog-shape (count (:other-catalog-shape by))
                  :not-a-map (count (:not-a-map by))
                  :no-catalog-def (count (:no-catalog-def by))
                  :unreadable-file (count (:unreadable-file by))
                  :note (str "record vector（:statute/* :ordinance/* 等）と "
                             "source allowlist はこの dataset の対象外 —— 形も意味も別物。"
                             ":no-catalog-def はパース失敗ではなく、その var が無い repo。")})
         ;; **法域ごとの件数を「その国の制度の数」と読まないこと。**
         ;; iso3166 の marketentry catalog は各国 repo に USA/DEU/GBR の比較行を
         ;; 種として置くので、USA が 304 件に見えても実体は少数の制度の再掲。
         ;; 自国行だけを数えるなら :permit/repo が iso3166-<その国> のものに絞る。
         :coverage/caveat
         (str "法域別の行数は制度の数ではない。iso3166 の marketentry catalog が "
              "各国 repo に USA/DEU/GBR の比較行を再掲するため（実測: 545 行中 "
              "自国行は 185）。国ごとの制度を数えるには :permit/repo で絞ること。")
         :coverage/known-iso3 (count known-iso3)
         :coverage/licensed-operator-rows (count (:rows lo))
         :coverage/kyoninka-linked-rows
         (count (filter :permit/kyoninka-procedure rows))
         :coverage/procedures (count (:rows procs))
         ;; 橋が両側に渡っているか。片側だけなら結合は宣言だけで辿れない。
         :coverage/procedures-reachable
         (let [ids (into #{} (map :procedure/id) (:rows procs))]
           (count (filter #(contains? ids (:permit/kyoninka-procedure %)) rows)))}
        ;; 結合の可否そのものをデータにする（ADR-2608080000）
        joins
        [{:join/id "permit->kyoninka-procedure"
          :source/dataset "permits"
          :join/from ":permit/kyoninka-procedure"
          :join/to ":procedure/id (kotoba-lang/kyoninka)"
          :join/status (cond (seq (:links links)) :works
                            (:found links) :declared-but-empty
                            :else :source-not-found)
          :join/evidence (str "licensed-operator の catalog が宣言する keyword の厳密一致。"
                              "実測で " (count (:links links)) " 本: "
                              (str/join " " (:links links)) "。片方向（kyoninka 側に逆参照は無い）。")}
         {:join/id "permit->procedure-by-law"
          :source/dataset "permits"
          :join/from ":permit/legal-basis"
          :join/to ":procedure/law"
          :join/status :unusable
          :join/evidence (str "法令名の文字列一致は使えない。実測 2026-08-06: "
                              "要件側 1,355 スロットに 1,018 個の異なる文字列があり、"
                              "同じ法律が repo ごとに別表記（\"FAR\" と "
                              "\"Federal Acquisition Regulation (FAR); System for Award Management\"）。"
                              "kyoninka の :procedure/law との交差は空集合。"
                              "結合するなら法令番号（e-Gov の lawNum 形）を別 entity に立てる必要がある。")}
         {:join/id "permit->authority"
          :source/dataset "permits"
          :join/from ":permit/authority"
          :join/to ":procedure/authority"
          :join/status :unusable
          :join/evidence (str "当局名も文字列では結合できない（\"都道府県公安委員会\" と "
                              "\"東京都公安委員会\" は粒度が違う）。実測 4 例中 1 例のみ一致。"
                              ":permit/provenance の公式 URL のドメインの方が同一性の代理として安定する。")}
         {:join/id "permit->repo"
          :source/dataset "permits"
          :join/from ":permit/repo"
          :join/to ":repo/path (repo-taxonomy / itonami-maturity)"
          :join/status :works
          :join/evidence "同形の repo パス。成熟度や分類と突き合わせられる。"}
         {:join/id "permit->jurisdiction"
          :source/dataset "permits"
          :join/from ":permit/jurisdiction"
          :join/to "cloud-itonami-iso3166-<iso3> repo"
          :join/status :works
          :join/evidence (str "観測済みの ISO3 のみ。導出していない —— "
                              "iso3166 repo の実在が「誰かが実際に調べた」ことの証拠で、"
                              "[A-Z]{3} の正規表現では \"EUR\" \"IEC\" のような非国コードを国にしてしまう。")}]
        out (str ";; GENERATED by scripts/gen-permit-index.cljs — 手で編集しない。\n"
                 ";; 正本は各 actor の src/<ns>/facts.cljc の catalog。ADR-2608080000。\n"
                 (pr-str (into (conj rows coverage) joins)) "\n")
        existing (when (.existsSync fs out-file) (slurp* out-file))]
    (cond
      (and check? (= out existing))
      (println "permit-index: 一致（" (count rows) "行 /" (count juris) "法域）")

      check?
      (do (println "permit-index: STALE — 再生成が要る（nbb --classpath \".:scripts\" scripts/gen-permit-index.cljs）")
          (js/process.exit 1))

      :else
      (do (.mkdirSync fs (path/dirname out-file) #js {:recursive true})
          (.writeFileSync fs out-file out)
          (println "permit-index:" (count rows) "行 /" (count juris) "法域 /"
                   (count (:rows by)) "ファイル →" out-file)
          (println "  法域上位:" (str/join " " (take 12 juris)))
          (println "  法域でない subject-key:" non-juris "種（自治体 slug・団体 slug・制度 keyword 等）")
          (println "  引用あり:" (:coverage/rows-with-citation coverage)
                   "/ 引用なし(jurisdictions 形):" (:coverage/rows-without-citation coverage))
          (println "  対象外:" (:coverage/excluded coverage))
          (println "  licensed-operator（手続きへの橋）:" (count (:rows lo)) "行、"
                   "うち kyoninka 手続きを指すもの"
                   (count (filter :permit/kyoninka-procedure rows)) "行")
          (println "  kyoninka の手続き:" (count (:rows procs)) "本、"
                   "橋が両側に渡っているもの"
                   (let [ids (into #{} (map :procedure/id) (:rows procs))]
                     (count (filter #(contains? ids (:permit/kyoninka-procedure %)) rows))) "本")))))

(-main)

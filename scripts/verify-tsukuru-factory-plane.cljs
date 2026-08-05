#!/usr/bin/env nbb
;; scripts/verify-tsukuru-factory-plane.cljs — ADR-2800003200 Phase 1 の不変条件検査
;;
;; 検査するのは「載っているか」ではなく **同意の段階が混ざっていないか**。
;; tsukuru の工場データは 3 つの dataset に分かれており、その区別は
;; 「どれだけ本物か」ではなく **その企業がこのプラットフォームに何を同意したか**:
;;
;;   tsukuru-candidates     公開ディレクトリ由来の research 参照。同意も onboarding も無い
;;   tsukuru-registry-seed  実在企業の例示 seed。登録済み関係ではない
;;   tsukuru-seed           R0 の worked example。実在の取引ではない
;;
;; この区別が壊れる壊れ方は静かで、クエリは常に「成功」する —— タグが 1 つに
;; 畳まれても、行が落ちても、did:web が混入しても、Datalog は黙って答えを返す。
;; だからここで**落ちる検査**にする。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-tsukuru-factory-plane.cljs
;;
;; コスト: 面を 3 回ロードするので **3 分前後**かかる（1 ロード約 55 秒）。
;; edn-query.cljs は読み込むと -main が走ってしまうので require では共有できず、
;; 検査ごとに子プロセスで q を叩いている。CI ではなく手で回す前提。
;;
;; murakumo fleet gate には**入れていない**。ノードは tailnet だけに繋がっていて
;; npm 依存（datascript）が配られないため、`:nbb-script` gate では面を組めない
;; （CLAUDE.md「fleet gate の書き方」の実測制約）。ここが解決したら gate 化する。
;;
;; EDN_QUERY_SCRIPT env で対象スクリプトを差し替えられる（既定
;; manifest/edn-query.cljs）。**この検査自体が落ちることを確かめる**ために使う —
;; 壊したコピーを指して exit 1 になることを見てから landed とする。

(ns verify-tsukuru-factory-plane
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str]
            [scripts.nbb-compat :as compat]
            ["fs" :as fs]))

(def root (str/trim (:out (shell/sh "git" "rev-parse" "--show-toplevel"))))
(def kotoba-dir (str root "/orgs/cloud-itonami/tsukuru-actor/kotoba"))
(def query-script (or (some-> js/process.env.EDN_QUERY_SCRIPT not-empty)
                      "manifest/edn-query.cljs"))

;; [ファイル名 dataset 床]。床は「静かに切り詰められた load」を捕まえるための
;; 下限であって正確な件数ではない（データが増えるのは正常、減るのは異常）。
;; 2026-08-05 実測: candidates 2,119 / registry-seed 99 / seed 3（工場 entity 数。
;; seed.edn は他に production-order・progress・sbt entity を 7 件持つ）。
(def sources
  [["candidates.edn"                 "tsukuru-candidates"    2000]
   ["manufacturer-registry-seed.edn" "tsukuru-registry-seed"   90]
   ["seed.edn"                       "tsukuru-seed"             3]])

(def failures (atom []))
(defn fail! [msg] (swap! failures conj msg))
(defn ok [msg] (println (str "  ok   " msg)))

(defn file-factory-dids
  "EDN ファイルを直接読み、:factory/did を持つ entity の did 集合を返す。
   面を経由しないので『loader が落とした行』を検出できる。"
  [fname]
  (let [p (str kotoba-dir "/" fname)]
    (if-not (fs.existsSync p)
      (do (fail! (str fname ": ファイルが無い (" p ")")) #{})
      (let [content (edn/read-string {:default (fn [_ v] v)} (fs.readFileSync p "utf8"))]
        (if-not (vector? content)
          (do (fail! (str fname ": トップレベルが vector ではない")) #{})
          (into #{} (keep :factory/did) (filter map? content)))))))

(defn plane-q
  "面に 1 クエリ投げて結果をパースする。面のロードに約 55 秒かかる。"
  [label query]
  (println (str "  ... " label " (面をロード中、約 55 秒)"))
  (let [{:keys [exit out err]} (shell/sh "nbb" "--classpath" ".:scripts/nbb_compat"
                                          query-script "q" query {:cwd root})]
    (if-not (zero? exit)
      (do (fail! (str label ": edn-query が exit " exit " — " (str/trim (str err)))) nil)
      (try (edn/read-string (str/trim out))
           (catch :default e
             (fail! (str label ": 結果をパースできない — " (.-message e)))
             nil)))))

(println "verify-tsukuru-factory-plane (ADR-2800003200 Phase 1)")
(println (str "  対象: " query-script))
(println)

;; ── 検査 1/3: dataset ごとの件数が、ファイルを直接読んだ件数と一致するか ──
;; 一致しなければ、行が落ちたか・タグが付いていないか・別 dataset に混ざったか。
;; どれも「クエリは成功するのに答えが嘘」になる壊れ方。
(println "[1/3] dataset の分割 (面 vs ファイル実体)")
(let [file-dids (into {} (for [[fname dataset _] sources] [dataset (file-factory-dids fname)]))
      rows (plane-q "factory/did × source/dataset"
                    "[:find ?ds ?did :where [?e \"factory/did\" ?did] [?e \"source/dataset\" ?ds]]")
      plane-dids (reduce (fn [m [ds did]] (update m ds (fnil conj #{}) did)) {} (or rows []))]
  (when rows
    (doseq [[_ dataset floor] sources]
      (let [expect (get file-dids dataset #{})
            got (get plane-dids dataset #{})]
        (cond
          (not= (count expect) (count got))
          (fail! (str dataset ": 面 " (count got) " 件 ≠ ファイル " (count expect) " 件"
                      " — 落ちた/混ざった did: "
                      (str/join ", " (take 3 (concat (remove got expect) (remove expect got))))))
          (< (count got) floor)
          (fail! (str dataset ": " (count got) " 件は床 " floor " を下回る (静かな切り詰め?)"))
          :else (ok (str dataset " = " (count got) " 件 (床 " floor ")")))))
    ;; dataset を跨いで同じ did が現れないこと（タグの partition）
    (let [overlaps (for [[_ d1 _] sources [_ d2 _] sources
                         :when (neg? (compare d1 d2))
                         :let [shared (set/intersection (get plane-dids d1 #{})
                                                        (get plane-dids d2 #{}))]
                         :when (seq shared)]
                     (str d1 " ∩ " d2 " = " (count shared) " 件"))]
      (if (seq overlaps)
        (fail! (str "dataset が partition になっていない: " (str/join "; " overlaps)))
        (ok "3 dataset は互いに素 (同じ工場が 2 つのタグを持たない)")))
    ;; 同意していない候補が did:web を名乗らないこと。ここが崩れると
    ;; 「未 onboard の企業が登録済みに見える」という、この dataset 最大の嘘になる。
    (let [bad (filter #(str/starts-with? (str %) "did:web:") (get plane-dids "tsukuru-candidates" #{}))]
      (if (seq bad)
        (fail! (str "tsukuru-candidates に did:web が " (count bad) " 件 — 未同意の企業が"
                    " onboarding 済みに見える: " (str/join ", " (take 3 bad))))
        (ok "tsukuru-candidates に did:web は 0 件 (未同意を登録済みに見せない)")))))

;; ── 検査 2/3: 能力で工場を引けるか（cardinality-many が効いているか）──
;; ds-schema に :db.cardinality/many を宣言し忘れると datascript は JS array を
;; 1 つの値として持ち、この dataset の主目的（能力で工場を探す）が**0 件を返して
;; 静かに壊れる**。壊れても例外は出ない。
(println "[2/3] 能力による検索 (cardinality-many)")
(let [rows (plane-q "factory/capabilities"
                    "[:find ?did :where [?e \"factory/capabilities\" \"industrial-robotics\"] [?e \"factory/did\" ?did]]")]
  (when rows
    (if (empty? rows)
      (fail! "factory/capabilities \"industrial-robotics\" が 0 件 — cardinality-many 未宣言の疑い")
      (ok (str "能力 \"industrial-robotics\" で " (count rows) " 社ヒット")))))

;; ── 検査 3/3: 結合キーを捏造していないか ──
;; candidates.edn は LEI を持たない。誰かが「便利だから」と :company/lei を
;; 合成したら、market-intel / cloud-itonami-lei との join が**嘘の同定**になる。
;; Phase 1 は join を約束しない（ADR-2800003200）。
(println "[3/3] 結合キーの捏造 (:company/lei)")
(let [rows (plane-q "factory × company/lei"
                    "[:find ?ds ?did :where [?e \"factory/did\" ?did] [?e \"company/lei\" _] [?e \"source/dataset\" ?ds]]")]
  (when rows
    (if (seq rows)
      (fail! (str "factory entity に :company/lei が " (count rows) " 件 — LEI を持たない"
                  " dataset に結合キーを合成している: " (str/join ", " (take 3 (map second rows)))))
      (ok "factory entity に :company/lei は 0 件 (同定を捏造していない)"))))

(println)
(if (seq @failures)
  (do (println (str "FAIL (" (count @failures) " 件)"))
      (doseq [m @failures] (println (str "  - " m)))
      (compat/exit 1))
  (do (println "PASS — 3 dataset は分割されたまま面に載っている")
      (compat/exit 0)))

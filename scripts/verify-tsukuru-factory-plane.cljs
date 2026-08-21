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
;;   ... --findings   ; 機械可読な finding も出す（orgs-detector-tick 用）
;;
;; ## --findings が出すもの（2026-08-13）
;;
;;   FINDING<TAB>severity<TAB>key<TAB>detail   failure 1 件につき 1 行
;;   SCANNED<TAB>n<TAB>unit                    証拠行（n=0 は :inconclusive 扱い）
;;
;; key は **構造的な識別子**（不変条件名 + dataset 名）で、件数などの run ごとに
;; 動く数を含めない —— 含めると同じ違反が毎回 resolved かつ new として報告され、
;; 「今日出た赤」と「3 週間前から赤」の区別という tick 唯一の要点が消える。
;; --findings を付けなければ出力は従来とバイト同一。
;;
;; ## この検査自身が「測らずに PASS する」のを止める 2 層（2026-08-13）
;;
;; 以前の版は **面のクエリが空を返すと 3 検査すべてを無言で skip して exit 0**
;; していた（`edn/read-string ""` は例外を投げず nil を返し、各検査が
;; `(when rows …)` で守られていたため、**nil が「失敗」ではなく「飛ばす」に
;; なっていた**）。同日に削除された 5 本の `security-gate-*` と同じ形が、
;; この workspace が回復させたい検査器の内側に居た。今は 2 層で塞ぐ:
;;
;;   1. `plane-q*` は空・解析不能・本数不一致・非コレクションを**必ず fail! する**。
;;      nil を返す経路は全て失敗を記録済みであることが不変条件。
;;   2. `assert-check!` が実行済み検査を数え、末尾で `expected-checks` に
;;      満たなければ落ちる（**床**）。1 を将来の編集が壊しても、飛ばした検査は
;;      「実行されていない」として赤くなる。
;;
;;   検証（実測 2026-08-13）: 何も出力せず exit 0 する stub を EDN_QUERY_SCRIPT に
;;   指すと exit 1 / FAIL 2 件。壊れていない経路では exit 0。
;;
;; ## コスト
;;
;; 面のロードは 1 回だけ（`q*` モードで 3 本のクエリを 1 プロセスに流す）。
;; 旧版は検査ごとに子プロセスを起こしていたので **3 回ロード**していた。
;; 実測は下の「実測」節を参照 —— このマシンの load が 3 桁のときは 1 ロードでも
;; 15 分級になるので、**6 時間 tick に載せる前に、そのホストの load で測り直す**。
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

(def expected-checks 3)

(def argv (vec (drop 2 js/process.argv)))
(def findings? (boolean (some #{"--findings"} argv)))

(def failures (atom []))
(def checks-run (atom 0))
;; 証拠。面のロードが失敗すればここは 0 のままで、tick 側の evidence 床が
;; その run を :inconclusive にする（clean にはならない）。
(def scanned-factories (atom 0))
(defn fail!
  "key は **構造的な識別子**。件数などの run ごとに動く数を混ぜない —— 混ぜると
   同じ違反が毎回 resolved かつ new として報告される。"
  [key msg] (swap! failures conj {:key key :msg msg}))
(defn ok [msg] (println (str "  ok   " msg)))

(defn assert-check!
  "検査 1 本を実行したことを記録してから本体を走らせる。`checks-run` が
   `expected-checks` に届かなければ末尾で落ちるので、**検査を飛ばす経路は
   黙って緑にならない**。"
  [label f]
  (swap! checks-run inc)
  (f)
  label)

(defn- clip
  "診断に載せる外部出力を、読める長さに切り詰める（空なら空と明示する）。"
  [s limit]
  (let [t (str/trim (str s))]
    (cond
      (str/blank? t) "(空)"
      (> (count t) limit) (str (subs t 0 limit) " …[" (count t) " 文字を切り詰め]")
      :else t)))

(defn file-factory-dids
  "EDN ファイルを直接読み、:factory/did を持つ entity の did 集合を返す。
   面を経由しないので『loader が落とした行』を検出できる。"
  [fname]
  (let [p (str kotoba-dir "/" fname)]
    (if-not (fs.existsSync p)
      (do (fail! (str "file-missing:" fname) (str fname ": ファイルが無い (" p ")")) #{})
      (let [content (edn/read-string {:default (fn [_ v] v)} (fs.readFileSync p "utf8"))]
        (if-not (vector? content)
          (do (fail! (str "not-a-vector:" fname) (str fname ": トップレベルが vector ではない")) #{})
          (into #{} (keep :factory/did) (filter map? content)))))))

(defn plane-q*
  "面を 1 回だけロードして `queries` を順に流し、同じ順の結果ベクタを返す。

   **異常は全て失敗として記録する** —— 空出力・解析不能・nil・非コレクション・
   本数不一致。この関数が nil を返すときは必ず fail! 済みであること、が
   呼び出し側から見た不変条件（旧版はここで nil を返しながら何も記録せず、
   全検査を skip したまま PASS していた）。"
  [queries]
  (println (str "  ... 面をロード中（1 回だけ / クエリ " (count queries) " 本）"))
  (let [t0 (js/Date.now)
        argv (concat ["nbb" "--classpath" ".:scripts/nbb_compat" query-script "q*"] queries)
        {:keys [exit out err signal]} (apply shell/sh (concat argv [{:cwd root}]))
        secs (/ (- (js/Date.now) t0) 1000.0)
        ;; 失敗時にコマンドと両ストリームを必ず名指しする。旧版は err だけを
        ;; 見せており、err が空なら「edn-query が exit 1 — 」とダッシュの後に
        ;; 何も無い行を出して、自分の診断を捨てていた。
        diag (str "cmd=" (clip (str/join " " argv) 200)
                  " / exit=" exit
                  (when signal (str " / **signal=" signal "**（プロセスが殺された。"
                                    "面のロードは重いので OOM kill を疑う）"))
                  " / stderr=" (clip err 600)
                  " / stdout=" (clip out 300))]
    (println (str "  ... ロード+クエリ " (.toFixed secs 1) " 秒"))
    (cond
      (not (zero? exit))
      (do (fail! "plane-query:nonzero-exit" (str "面のクエリが exit " exit " — " diag)) nil)

      (str/blank? (str out))
      (do (fail! "plane-query:empty-output"
                (str "面のクエリが exit 0 で**何も出力しなかった** — "
                      "空出力は「結果 0 件」ではなく検査不能。" diag))
          nil)

      :else
      (let [parsed (try (edn/read-string (str/trim out))
                        (catch :default e
                          (fail! "plane-query:unparseable"
                                 (str "面のクエリ結果をパースできない — "
                                      (.-message e) " / " diag))
                          ::unparseable))]
        (cond
          (= ::unparseable parsed) nil

          (not (sequential? parsed))
          (do (fail! "plane-query:not-sequential"
                 (str "面のクエリ結果が結果ベクタではない (" (pr-str (type parsed))
                          ") — " diag))
              nil)

          (not= (count queries) (count parsed))
          (do (fail! "plane-query:arity-mismatch"
                 (str "面のクエリ結果が " (count parsed) " 本 ≠ 投げた "
                          (count queries) " 本 — " diag))
              nil)

          (some nil? parsed)
          (do (fail! "plane-query:nil-slot"
                 (str "面のクエリ結果に nil の枠がある（" (count (filter nil? parsed))
                          " 本）— 0 件と未実行は区別できない。" diag))
              nil)

          :else (vec parsed))))))

(println "verify-tsukuru-factory-plane (ADR-2800003200 Phase 1)")
(println (str "  対象: " query-script))
(println)

(def q-dataset-split
  "[:find ?ds ?did :where [?e \"factory/did\" ?did] [?e \"source/dataset\" ?ds]]")
(def q-capabilities
  (str "[:find ?did :where [?e \"factory/capabilities\" \"industrial-robotics\"] "
       "[?e \"factory/did\" ?did]]"))
(def q-lei
  (str "[:find ?ds ?did :where [?e \"factory/did\" ?did] [?e \"company/lei\" _] "
       "[?e \"source/dataset\" ?ds]]"))

(let [results (plane-q* [q-dataset-split q-capabilities q-lei])
      [split-rows cap-rows lei-rows] (or results [nil nil nil])]

  (if-not results
    (println "  面のクエリに失敗したため、3 検査は 1 つも実行されていない（下の FAIL を参照）")

    (do
      ;; 面から実際に返ってきた factory entity の数。証拠行はこれを出す。
      (reset! scanned-factories (count split-rows))
      ;; ── 検査 1/3: dataset ごとの件数が、ファイルを直接読んだ件数と一致するか ──
      ;; 一致しなければ、行が落ちたか・タグが付いていないか・別 dataset に混ざったか。
      ;; どれも「クエリは成功するのに答えが嘘」になる壊れ方。
      (println "[1/3] dataset の分割 (面 vs ファイル実体)")
      (assert-check!
       :dataset-split
       (fn []
         (let [file-dids (into {} (for [[fname dataset _] sources]
                                    [dataset (file-factory-dids fname)]))
               plane-dids (reduce (fn [m [ds did]] (update m ds (fnil conj #{}) did))
                                  {} split-rows)]
           (doseq [[_ dataset floor] sources]
             (let [expect (get file-dids dataset #{})
                   got (get plane-dids dataset #{})]
               (cond
                 (not= (count expect) (count got))
                 (fail! (str "dataset-count:" dataset)
                        (str dataset ": 面 " (count got) " 件 ≠ ファイル " (count expect) " 件"
                             " — 落ちた/混ざった did: "
                             (str/join ", " (take 3 (concat (remove got expect)
                                                            (remove expect got))))))
                 (< (count got) floor)
                 (fail! (str "dataset-floor:" dataset)
                        (str dataset ": " (count got) " 件は床 " floor " を下回る (静かな切り詰め?)"))
                 :else (ok (str dataset " = " (count got) " 件 (床 " floor ")")))))
           ;; dataset を跨いで同じ did が現れないこと（タグの partition）
           (let [overlaps (for [[_ d1 _] sources [_ d2 _] sources
                                :when (neg? (compare d1 d2))
                                :let [shared (set/intersection (get plane-dids d1 #{})
                                                               (get plane-dids d2 #{}))]
                                :when (seq shared)]
                            (str d1 " ∩ " d2 " = " (count shared) " 件"))]
             (if (seq overlaps)
               (fail! "dataset-not-a-partition"
                      (str "dataset が partition になっていない: " (str/join "; " overlaps)))
               (ok "3 dataset は互いに素 (同じ工場が 2 つのタグを持たない)")))
           ;; 同意していない候補が did:web を名乗らないこと。ここが崩れると
           ;; 「未 onboard の企業が登録済みに見える」という、この dataset 最大の嘘になる。
           (let [bad (filter #(str/starts-with? (str %) "did:web:")
                             (get plane-dids "tsukuru-candidates" #{}))]
             (if (seq bad)
               (fail! "candidates-did-web"
                      (str "tsukuru-candidates に did:web が " (count bad) " 件 — 未同意の企業が"
                           " onboarding 済みに見える: " (str/join ", " (take 3 bad))))
               (ok "tsukuru-candidates に did:web は 0 件 (未同意を登録済みに見せない)"))))))

      ;; ── 検査 2/3: 能力で工場を引けるか（cardinality-many が効いているか）──
      ;; ds-schema に :db.cardinality/many を宣言し忘れると datascript は JS array を
      ;; 1 つの値として持ち、この dataset の主目的（能力で工場を探す）が**0 件を返して
      ;; 静かに壊れる**。壊れても例外は出ない。
      ;; 宣言の在り処は manifest/edn-query.cljs の `ds-schema`（manifest/schema.edn は
      ;; edn-datomize.cljs の生成物で、tsukuru の kotoba ファイルはそこを通らないため
      ;; factory/* が入らない。手編集は禁止なので patent/applicant-norm・yakuwari/* と
      ;; 同じくコード側に置く）。
      (println "[2/3] 能力による検索 (cardinality-many)")
      (assert-check!
       :capabilities
       (fn []
         (if (empty? cap-rows)
           (fail! "capabilities-cardinality-many"
                  (str "factory/capabilities \"industrial-robotics\" が 0 件 — "
                       "manifest/edn-query.cljs の ds-schema に "
                       "\"factory/capabilities\" の :db.cardinality/many 宣言が無い疑い"))
           (ok (str "能力 \"industrial-robotics\" で " (count cap-rows) " 社ヒット")))))

      ;; ── 検査 3/3: 結合キーを捏造していないか ──
      ;; candidates.edn は LEI を持たない。誰かが「便利だから」と :company/lei を
      ;; 合成したら、market-intel / cloud-itonami-lei との join が**嘘の同定**になる。
      ;; Phase 1 は join を約束しない（ADR-2800003200）。
      (println "[3/3] 結合キーの捏造 (:company/lei)")
      (assert-check!
       :fabricated-join-key
       (fn []
         (if (seq lei-rows)
           (fail! "fabricated-join-key"
                  (str "factory entity に :company/lei が " (count lei-rows) " 件 — LEI を持たない"
                       " dataset に結合キーを合成している: "
                       (str/join ", " (take 3 (map second lei-rows)))))
           (ok "factory entity に :company/lei は 0 件 (同定を捏造していない)")))))))

;; 床: 3 検査すべてが実際に走ったか。走っていなければ、それが PASS の理由に
;; ならないよう**ここで落とす**。
(when (not= expected-checks @checks-run)
  (fail! "checks-not-all-run"
         (str "検査が " @checks-run "/" expected-checks
              " しか実行されていない — 実行されなかった検査は「合格」ではない")))

(when findings?
  ;; 証拠行を先に出す。0 件の run を clean として記録させないための床。
  ;; 数えるのは面に載った factory entity で、面のロードに失敗した run では
  ;; `split-rows` が無いので 0 になり、tick 側が :inconclusive として扱う。
  (println (str "SCANNED\t" @scanned-factories "\tfactory entit(ies) on the plane"))
  (doseq [{:keys [key msg]} @failures]
    (println (str "FINDING\tfail\t" key "\t" msg))))

(println)
(if (seq @failures)
  (do (println (str "FAIL (" (count @failures) " 件)"))
      (doseq [{:keys [msg]} @failures] (println (str "  - " msg)))
      (compat/exit 1))
  (do (println (str "PASS — 3 dataset は分割されたまま面に載っている（検査 "
                    @checks-run "/" expected-checks " 実行）"))
      (compat/exit 0)))

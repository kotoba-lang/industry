#!/usr/bin/env nbb
;; clearance-policy-check.cljs — manifest/clearance.edn の機械検査。ADR-2608083000。
;;
;; ## なぜこの gate が要るか
;;
;; クリアランス設計は、放っておくと**必ず**同じ 2 方向に腐る:
;;
;;   1. **秘匿の根拠が第三者の危害から組織の都合へ滑る。** 「恥ずかしいから」
;;      「交渉で不利だから」は最も使われる分類理由で、しかも本人はそれを
;;      危害だと感じている。
;;   2. **位（役職・年功）が級（クリアランス）を連れてくる。** 「Lv7 なんだから
;;      見られて当然」は一度でも通ると、以後 need-to-know は形骸化する。
;;
;; どちらも人間のレビューで止めるのが難しい（その場では筋が通って見える）ので、
;; **語彙の側で不可能にする**。この gate はその語彙を強制する。
;;
;; ## 検査する不変条件
;;
;;   1  級はちょうど 7 段（C0..C6）で、番号が連続している
;;   2  各級が :harm-to（誰が傷つくか）と :declassify（いつ解けるか）を持つ
;;   3  **:harm-to / :basis に :forbidden-bases の語が現れない**（組織の都合）
;;   4  **:access-record が :published 系でない級が 1 つも無い**（§2(c) 相互性）
;;   5  **位はクリアランスを与えない** —— :confers-clearance が false、
;;      かつ級に「位から自動で導出する」キーが無い
;;   6  C5/C6 は :two-person-rule true、C0 以外は :floor-lv が床以上
;;   7  :decision が deny 既定・per-request 評価で、7 つの conjunct が揃っている
;;   8  区画は :custodian / :purpose / :review-interval-days を持つ
;;   9  分類済み dataset は級が語彙内で :basis と :evidence を持つ
;;   10 **:status が :proposed の間は、施行を主張する語を書けない**
;;
;; ネットワーク: 不要。実行: 不要（静的検査のみ）。
;;
;; 実行: `npx nbb clearance-policy-check.cljs <dir>`

(ns fleet-ci.gates.clearance-policy-check
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def argv (vec *command-line-args*))
(def dir (or (first (remove #(str/starts-with? % "--") argv)) "."))
(defn- p [& xs] (apply (.-join path) (clj->js (cons dir xs))))
(defn- exists? [f] (.existsSync fs f))
(defn- rd [f] (.readFileSync fs f "utf8"))

(def violations (atom []))
(defn- v! [& msg] (swap! violations conj (str/join "" msg)))

(def pol-file (p "manifest" "clearance.edn"))
(when-not (exists? pol-file)
  (println "FAIL manifest/clearance.edn が無い（tree の絞り込みが壊れている可能性）")
  (.exit js/process 1))

(def pol (edn/read-string (rd pol-file)))
(def grades (vec (sort-by :c (:grades pol))))
(def forbidden (:forbidden-bases pol #{}))

;; 床。空の政策を「違反 0 = 合格」にしないため（他 gate と同じ作法）。
(when (< (count grades) 7)
  (println (str "FAIL 級が " (count grades) " 段 < 7 — 政策が空か、tree の絞り込みが壊れている"))
  (.exit js/process 1))

;; ── 1: C0..C6 が連続 ────────────────────────────────────────────────
(let [cs (mapv :c grades)]
  (when-not (= cs (vec (range 7)))
    (v! "級の番号が C0..C6 の連続でない: " (pr-str cs))))

;; ── 2,3,4,6: 各級 ───────────────────────────────────────────────────
(def floor-lv (get-in pol [:commitment-floor :minimum-lv]))

(doseq [g grades]
  (let [n (str "C" (:c g) " " (:ja g))]
    (doseq [k [:harm-to :declassify :access-record :meaning]]
      (when (nil? (get g k)) (v! n ": " k " が無い")))

    ;; 3. 組織の都合を根拠にしていないか。**ここがこの gate の主目的。**
    (when (contains? forbidden (:harm-to g))
      (v! n ": :harm-to " (:harm-to g) " は禁じられた根拠"
          " — 秘匿の理由になるのは第三者・子孫への危害だけで、組織の都合ではない"))
    ;; 自由記述にも見に行く（キーワードを避けて散文で書けば通る、を防ぐ）
    (doseq [bad ["恥" "評判" "交渉" "都合" "面倒" "偉い" "上の人"]]
      (when (str/includes? (str (:meaning g)) bad)
        (v! n ": :meaning に『" bad "』が現れる"
            " — 組織の都合を級の説明にしない")))

    ;; 4. 相互性。**例外を作れる形にしない。**
    (when-not (str/starts-with? (str (name (:access-record g))) "published")
      (v! n ": :access-record " (:access-record g) " が published 系でない"
          " — §2(c) は非対称（watcher-unwatched）な監視を禁じている。"
          "読む行為が見られない級は憲章違反"))

    ;; 5. 位から導出していないか
    (doseq [k (keys g)]
      (when (re-find #"(?i)auto.*(level|lv)|(level|lv).*auto|from-lv|by-rank|by-seniority" (name k))
        (v! n ": キー " k " は位から級を導出している"
            " — 位が上がっても級は上がらない（オーナー指示 2026-08-08）")))

    ;; 6. 床と two-person
    (when (and (pos? (:c g)) (not= floor-lv (:floor-lv g)))
      (v! n ": :floor-lv " (:floor-lv g) " が :commitment-floor " floor-lv " と違う"
          " — 級ごとに床を変えると『級が上がるほど位も要る』= 位と級が連動する"))
    (when (and (>= (:c g) 5) (not (:two-person-rule g)))
      (v! n ": C5 以上なのに :two-person-rule が立っていない"))))

;; ── 5: ladder 側 ────────────────────────────────────────────────────
(when-not (false? (get-in pol [:commitment-ladder :confers-clearance]))
  (v! ":commitment-ladder の :confers-clearance が false でない"
      " — 位はクリアランスを与えない、が設計の核心"))
(when-not (false? (get-in pol [:commitment-floor :is-sufficient]))
  (v! ":commitment-floor の :is-sufficient が false でない"
      " — 床は必要条件であって十分条件ではない"))

;; ── 7: zero trust の判定 ────────────────────────────────────────────
(let [d (:decision pol)
      ids (into #{} (map :id) (:conjuncts d))
      need #{:commitment-floor :grade :compartment :declared-purpose
             :reference-freshness :key-posture :reciprocity}]
  (when-not (= :deny (:default d))
    (v! ":decision の :default が :deny でない — zero trust は deny 既定"))
  (when-not (= :per-request (:evaluated d))
    (v! ":decision の :evaluated が :per-request でない"
        " — session やロールに焼いた時点で zero trust ではない"))
  (when-not (= :deny (:unknown-is d))
    (v! ":decision の :unknown-is が :deny でない — 不明は『たぶん大丈夫』ではない"))
  (doseq [x need]
    (when-not (contains? ids x)
      (v! ":decision に conjunct " x " が無い")))
  (doseq [c (:conjuncts d)]
    (when (str/blank? (str (:asks c)))
      (v! ":decision の " (:id c) " に :asks が無い — 何を見るかが書かれていない"))))

;; ── 8: 区画 ─────────────────────────────────────────────────────────
(doseq [c (:compartments pol)]
  (doseq [k [:name :custodian :purpose :review-interval-days]]
    (when (nil? (get c k))
      (v! "区画 " (or (:name c) "<no-name>") ": " k " が無い"))))

;; ── 9: dataset 分類 ─────────────────────────────────────────────────
(def grade-nums (into #{} (map :c) grades))
(doseq [d (:dataset-classification pol)]
  (let [n (or (:dataset d) "<no-dataset>")]
    (when-not (contains? grade-nums (:c d))
      (v! "dataset " n ": :c " (:c d) " は級の語彙外"))
    (when (contains? forbidden (:basis d))
      (v! "dataset " n ": :basis " (:basis d) " は禁じられた根拠"))
    (when (str/blank? (str (:evidence d)))
      (v! "dataset " n ": :evidence が無い"
          " — 何を根拠に分類したかを書かない分類は、推測と区別が付かない"))
    (when (and (pos? (:c d 0)) (str/blank? (str (:declassify d))))
      (v! "dataset " n ": :declassify が無い"
          " — 解ける条件の無い分類は恒久秘匿になり、既定公開の憲章に反する"))))

;; 分類と公開宣言が重複していないか（同じ dataset が両方に居ると読めない）
(let [cls (into #{} (map :dataset) (:dataset-classification pol))
      pub (into #{} (:dataset-public pol))]
  (doseq [x (filter cls pub)]
    (v! "dataset " x " が :dataset-classification と :dataset-public の両方に在る")))

;; ── 10: proposed の間は施行を主張しない ─────────────────────────────
(when (= :proposed (:status pol))
  (let [txt (rd pol-file)]
    (doseq [w ["施行済" "運用中" "enforced" "in force"]]
      (when (str/includes? txt w)
        (v! ":status が :proposed なのに本文に『" w "』が現れる"
            " — 批准されていない政策を施行済みと書かない"))))
  (let [r (:ratification pol)]
    (when (nil? (:required r)) (v! ":ratification の :required が無い"))
    (when (and (number? (:council-seats-filled r)) (number? (:council-seats-total r))
               (= (:council-seats-filled r) (:council-seats-total r))
               (:blocking r))
      (v! ":ratification: 席が埋まっているのに :blocking が残っている"))))

;; ── 11: 実装プロトコル（ADR-2608084000）──────────────────────────
;;
;; **仕様名を挙げただけの設計を『設計済み』と読ませない。** 引用する repo が
;; west.yml に実在することを機械で確かめる。実装の有無まではここでは見ないが、
;; 「名前だけ在って repo が無い」は落とす。
(def west-names
  (if (exists? (p "manifest" "west.yml"))
    (into #{} (map second) (re-seq #"(?m)^    - name: (\S+)$" (rd (p "manifest" "west.yml"))))
    #{}))

(defn- collect-repos [x]
  (cond (map? x) (concat (when (vector? (:repo x)) (:repo x)) (mapcat collect-repos (vals x)))
        (sequential? x) (mapcat collect-repos x)
        :else nil))

(when-let [pr* (:protocols pol)]
  ;; 参照する repo が west に実在するか
  (when (seq west-names)
    (doseq [r (distinct (collect-repos pr*))]
      (when-not (contains? west-names r)
        (v! ":protocols が west.yml に無い repo " (pr-str r) " を引用している"
            " — 実装の在るものだけを protocol として書く"))))

  ;; :repo は必ずベクタ（文字列だと 1 文字ずつに散る。実測 2026-08-08）
  (letfn [(scan [x path]
            (when (map? x)
              (when (and (contains? x :repo) (not (vector? (:repo x))))
                (v! (str path " の :repo がベクタでない: " (pr-str (:repo x)))))
              (doseq [[k v*] x] (scan v* (str path "/" k)))
              (doseq [v* (filter sequential? (vals x))
                      e v* :when (map? e)] (scan e path))))]
    (scan pr* ":protocols"))

  ;; 権限更新は 4 事象すべてに別々の機構が要る
  (let [au (:authority-update pr*)
        evs (into #{} (map :event) au)]
    (doseq [e [:grant :suspend :revoke :expiry]]
      (when-not (contains? evs e)
        (v! ":authority-update に事象 " e " が無い"
            " — 授与/停止/剥奪/失効 は別々の機構を持つ。混ぜると停止が高価になり"
            "運用が停止をためらう")))
    (doseq [a au]
      (doseq [k [:mechanism :cost :repo]]
        (when (nil? (get a k))
          (v! ":authority-update " (:event a) " に " k " が無い"))))
    ;; 停止が再鍵を伴うと、安く可逆であるという設計意図が壊れる
    (when-let [s (first (filter #(= :suspend (:event %)) au))]
      (when-not (= "O(1)" (:cost s))
        (v! ":authority-update :suspend の :cost が O(1) でない"
            " — 停止が高価だと運用が停止をためらう。安いことが安全側に効く"))))

  ;; 閲覧履歴: 順序と保存とギャップの申告
  (let [al (:access-log pr*)]
    (when-not (= :log-then-serve (:ordering al))
      (v! ":access-log の :ordering が :log-then-serve でない"
          " — 平文を配ってから書くと、途中で落ちたときに記録だけが消えて"
          "相互性の条件が黙って破れる"))
    (when (nil? (:retention al))
      (v! ":access-log に :retention が無い"))
    (when (and (= :none (:inclusion-proof al)) (nil? (:anchor al)))
      (v! ":access-log は :inclusion-proof :none なのに :anchor が無い"
          " — 出せない証明の代わりに何をするかを書かずに『できない』とだけ書かない"))
    (when (and (= :permanent (:retention al)) (nil? (:long-term-integrity al)))
      (v! ":access-log は :retention :permanent なのに :long-term-integrity が無い"
          " — 永久保存はハッシュが先に老いる。RFC 4998 相当の更新計画が要る")))

  ;; 穴は影響と深刻度つきで名指す
  (doseq [m (:missing pr*)]
    (doseq [k [:what :impact :severity]]
      (when (nil? (get m k))
        (v! ":missing " (pr-str (:what m)) " に " k " が無い"
            " — 穴を穴として名指すには、何がどう効くかまで書く")))))

;; ── 結果 ────────────────────────────────────────────────────────────
(let [vs @violations]
  (println (str "clearance-policy-check: 級 " (count grades)
                " / 区画 " (count (:compartments pol))
                " / 分類済み dataset " (count (:dataset-classification pol))
                " / status " (:status pol)
                " / 違反 " (count vs)))
  (doseq [x vs] (println (str "  ✗ " x)))
  (if (seq vs)
    (do (println "FAIL") (.exit js/process 1))
    (println "OK")))

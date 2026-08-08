#!/usr/bin/env nbb
;; observatory-registry-check.cljs — ADR-2608081200 の機械検査。
;;
;; 展開済みの superproject tree を受け取り、`manifest/observatories.edn`（登録簿・
;; 手書き）と `90-docs/observatory/observatory.datoms.edn`（生成物）と
;; `manifest/west.yml` の三者が食い違っていないかを検証する。
;;
;; ## なぜこの gate が要るか
;;
;; 2026-08-08 の実測で、領域別 observatory 13 本のうち **5 本が起動しなかった**。
;; だが README も MATURITY.md も manifest.edn も、それらが動くと書いていた。
;; 「動くと書いてある」と「動く」の差を埋めるのが observatory-run で、この gate は
;; **その登録簿が腐るのを止める**。
;;
;; observatory-run 自体はここでは走らせない —— 実行には west checkout 一式
;; （sibling の :local/root 依存を含む）が要り、fleet ノードには配れない。
;; ノードで検査できるのは「登録簿が現実と整合しているか」までで、**実際に動くか
;; どうかは operator 側の日次 run（com.gftd.observatory-run）が測る**。
;; この境界を曖昧にしないこと —— この gate が green でも、それは actor が動く
;; 証拠にはならない。
;;
;; ## 検査する不変条件
;;
;;   1. 必須キー（:name :org :domain :runtime :expect）が揃っている
;;   2. :expect が語彙内（produces-datoms / produces-datoms-idempotent /
;;      produces-files / runs-ok / runs-empty / known-broken）
;;   3. **:known-broken と :runs-empty は :blocked-by を持つ。**
;;      壊れているものを「壊れている」と登録するだけで理由を書かないと、
;;      それは記録ではなく黙認になる
;;   4. **:args のパスは ${REPO} 起点。** 相対パスの既定値に依存しないための
;;      登録簿なのに、ここで相対パスを書いたら同じ罠を踏み直す
;;   5. 登録された name が west.yml の project として実在する
;;   6. 台帳が在るなら、登録された actor を 1 つも落としていない
;;      （部分実行の結果で全体の台帳が上書きされていないか）
;;   7. :unmeasured は :note を持つ（「対象外」と読ませないため）
;;
;; ネットワーク: 不要。実行: 不要（登録簿の静的検査のみ）。
;;
;; 実行: `npx nbb observatory-registry-check.cljs <dir> [--min 10]`

(ns fleet-ci.gates.observatory-registry-check
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; nbb は script 自身のパスも process.argv に含めるので、そこから手で drop すると
;; script 名を第 1 引数と誤読する（実測 2026-08-08）。他の gate と同じく
;; *command-line-args* を使う。
(def argv (vec *command-line-args*))
(def dir (or (first (remove #(str/starts-with? % "--") argv)) "."))
(defn- opt [f d] (let [i (.indexOf (clj->js argv) f)] (if (>= i 0) (js/parseInt (nth argv (inc i))) d)))
(def min-obs (opt "--min" 10))

(defn- p [& xs] (apply (.-join path) (clj->js (cons dir xs))))
(defn- exists? [f] (.existsSync fs f))
(defn- rd [f] (.readFileSync fs f "utf8"))

(def violations (atom []))
(defn- v! [& msg] (swap! violations conj (str/join "" msg)))

;; ── 入力 ────────────────────────────────────────────────────────────────
(def reg-file (p "manifest" "observatories.edn"))
(when-not (exists? reg-file)
  (println "FAIL manifest/observatories.edn が無い（tree の絞り込みが壊れている可能性）")
  (.exit js/process 1))

(def reg (edn/read-string (rd reg-file)))
(def obs (:observatories reg))

;; **床**。tree の絞り込みが壊れて空の登録簿を「違反 0 件 = 合格」にしないため。
(when (< (count obs) min-obs)
  (println (str "FAIL 登録 " (count obs) " 件 < --min " min-obs
                " — 登録簿が空か、tree の絞り込みが壊れている"))
  (.exit js/process 1))

(def west-names
  (if (exists? (p "manifest" "west.yml"))
    (into #{} (map second) (re-seq #"(?m)^    - name: (\S+)$" (rd (p "manifest" "west.yml"))))
    #{}))

(def expect-vocab #{:produces-datoms :produces-datoms-idempotent
                    :produces-files :runs-ok :runs-empty :known-broken})

;; ── 1–5: 登録簿そのもの ─────────────────────────────────────────────────
(doseq [o obs]
  (let [n (or (:name o) "<no-name>")]
    (doseq [k [:name :org :domain :runtime :expect]]
      (when (nil? (get o k)) (v! n ": 必須キー " k " が無い")))

    (when (and (:expect o) (not (expect-vocab (:expect o))))
      (v! n ": :expect " (:expect o) " は語彙外 " (pr-str expect-vocab)))

    ;; 3. 壊れているものは理由を名前で持つ
    (when (and (#{:known-broken :runs-empty} (:expect o))
               (str/blank? (str (:blocked-by o))))
      (v! n ": :expect " (:expect o) " なのに :blocked-by が無い"
          " — 理由を書かない登録は記録ではなく黙認"))

    ;; 4. :args のパスは ${REPO} 起点
    (doseq [a (:args o)]
      (when (and (string? a)
                 (not (str/starts-with? a "--"))
                 (or (str/includes? a "/") (str/ends-with? a ".edn"))
                 (not (str/starts-with? a "${REPO}")))
        (v! n ": :args のパス " (pr-str a) " が ${REPO} 起点でない"
            " — 相対パス依存を避けるための登録簿でそれをやると意味が無い")))

    ;; 5. west.yml に実在するか
    (when (and (seq west-names) (:name o) (not (contains? west-names (:name o))))
      (v! n ": west.yml に project として登録が無い"))))

;; 7. :unmeasured は :note を持つ
(doseq [u (:unmeasured reg)]
  (when (str/blank? (str (:note u)))
    (v! (or (:name u) "<unmeasured>") ": :unmeasured なのに :note が無い"
        " — 『対象外』と読まれないよう、なぜ未測定かを書くこと")))

;; 8. :unmeasured が空なら :inventory-note が要る。
;;    **空の :unmeasured は「全部見た」と読める** —— 実際には 4,148 project から
;;    手で拾った 22 件でしかない。網羅を証明していないことを登録簿自身に言わせる。
(when (and (empty? (:unmeasured reg)) (str/blank? (str (:inventory-note reg))))
  (v! ":unmeasured が空なのに :inventory-note が無い"
      " — 空の未測定リストは『網羅した』と読まれる。どう作った候補かを書くこと"))

;; 9. :next（次にやると効くこと）は :target と :fix を持つ。
;;    skill `observatory-collect` の毎周の入力なので、目標だけ書いて手当てが
;;    書かれていない行は、次の反復が読んでも何も決まらない。
(doseq [n (:next reg)]
  (doseq [k [:target :fix]]
    (when (str/blank? (str (get n k)))
      (v! (or (:target n) "<next>") ": :next の " k " が無い"
          " — 何を直すかが書かれていない行は次の反復の入力にならない"))))

;; ── 10: 頻度の入力（ADR-2608082600）────────────────────────────────────
;; 走る actor には λ と importance が要る。**「無いから毎周走らせる」を既定に
;; しない** —— 既定が「速い方」だと、測っていない actor ほど頻繁に叩かれる。
(def basis-vocab #{:measured :prior :lower-bound :upper-bound})
(doseq [o obs
        :when (not= :known-broken (:expect o))]
  (let [n (or (:name o) "<no-name>") cr (:change-rate o)]
    (cond
      (nil? cr)
      (v! n ": 走る actor なのに :change-rate が無い"
          " — λ [1/day] を宣言するか、測れないなら :uncomputable-until-measured と書くこと")

      (= :uncomputable-until-measured cr)
      (when (str/blank? (str (:change-rate-source o)))
        (v! n ": :change-rate が :uncomputable-until-measured なのに理由が無い"))

      (number? cr)
      (do
        (when-not (pos? cr) (v! n ": :change-rate は正の数であること（" cr "）"))
        (when-not (basis-vocab (:change-rate-basis o))
          (v! n ": :change-rate-basis " (pr-str (:change-rate-basis o)) " は語彙外 "
              (pr-str basis-vocab)
              " — **prior を測定値として提示させない**ための必須キー"))
        (when (str/blank? (str (:change-rate-source o)))
          (v! n ": :change-rate-source が無い — その数がどこから来たかを書くこと"))
        (when-not (and (number? (:importance o)) (pos? (:importance o)))
          (v! n ": :importance が無いか正でない"
              " — 単位は [sec·day]（陳腐化 1 単位を避けるのに払ってよい計算秒数）")))

      :else (v! n ": :change-rate " (pr-str cr) " は数でも :uncomputable-until-measured でもない"))))

;; ── 11: plist が XML として妥当か ──────────────────────────────────────
;; **壊れた plist は落ちるのではなく、launchd に読まれないだけ。**「設定したのに
;; 一度も走っていない」という最も気付きにくい壊れ方をする。実測 2026-08-08、
;; 初版の observatory-run.plist は XML コメント中に 2 連ハイフン（フラグ名）を
;; 含んでいて不正だった。ここで機械に見せる。
(let [plist-dir (p "scripts" "fleet-ci")]
  (when (exists? plist-dir)
    (doseq [f (vec (.readdirSync fs plist-dir))
            :when (str/ends-with? f ".plist")]
      (let [txt (rd ((.-join path) plist-dir f))]
        ;; **`(?s)` を使わない。** JS の正規表現はインラインの dotall 修飾子を
        ;; 解釈しないので、`(?s).*?` は改行をまたげず**黙って 1 件も一致しない**
        ;; （実測 2026-08-08: この検査が無反応で、壊した plist を素通りさせた）。
        ;; 明示的な `[\s\S]` で書く。
        ;; ⚠ 捕獲グループの無い正規表現の `re-seq` は**文字列**を返す（ベクタでは
        ;; ない）。`[whole]` で分配束縛すると先頭 1 文字を掴み、検査が常に無反応に
        ;; なる（実測 2026-08-08、この 1 行で 2 度目の空振りをした）。
        (doseq [whole (re-seq #"<!--[\s\S]*?-->" txt)]
          ;; コメント本体（開始 4 文字と終了 3 文字を除く）に "--" が在ってはならない
          (let [body (subs whole 4 (- (count whole) 3))]
            (when (str/includes? body "--")
              (v! f ": XML コメントに 2 連ハイフンが入っている（XML 仕様違反）"
                  " — plist は落ちずに『読まれない』ので、走っていないことに気付けない"))))
        (when-not (str/includes? txt "<plist")
          (v! f ": <plist> 要素が無い"))))))

;; ── 6: 台帳が登録を落としていないか ──────────────────────────────────────
(def ledger-file (p (or (:ledger reg) "90-docs/observatory/observatory.datoms.edn")))
(if-not (exists? ledger-file)
  (println "  note: 台帳が未生成（observatory-run をまだ回していない）— 6 は skip")
  (let [txt (rd ledger-file)
        named (into #{} (map second) (re-seq #":observatory/name \"([^\"]+)\"" txt))
        registered (into #{} (keep :name) obs)
        missing (remove named registered)]
    (when (seq missing)
      (v! "台帳が登録済み actor を落としている: " (str/join ", " missing)
          " — 部分実行(--only)の結果で全体の台帳を上書きしていないか"))
    (when-not (str/includes? txt ":observatory/coverage")
      (v! "台帳に :observatory/coverage entity が無い"
          " — 何を数えていないかを申告しない台帳は、網羅と読み違えられる"))))

;; ── 結果 ────────────────────────────────────────────────────────────────
(let [vs @violations]
  (println (str "observatory-registry-check: 登録 " (count obs)
                " / 未測定 " (count (:unmeasured reg))
                " / 違反 " (count vs)))
  (doseq [x vs] (println (str "  ✗ " x)))
  (if (seq vs)
    (do (println "FAIL") (.exit js/process 1))
    (println "OK")))

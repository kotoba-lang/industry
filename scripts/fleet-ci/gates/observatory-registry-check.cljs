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
;;   4. **:args のパスは ${REPO} 起点か、絶対パス + :args-outside-repo（理由）。**
;;      捕まえたいのは相対パス（起動場所で意味が変わる）。repo の外へ逃がすのが
;;      必須な出力は実在する（hayari の --summary-out）ので、禁じずに理由を持たせる
;;   5. 登録された repo が west.yml の project として実在する。
;;      **見るのは (or :repo :name)** —— :name は台帳の識別子、:repo が在処
;;   6. 台帳が在るなら、登録された actor を 1 つも落としていない
;;      （部分実行の結果で全体の台帳が上書きされていないか）
;;   7. :unmeasured は :note を持つ（「対象外」と読ませないため）
;;
;; ネットワーク: 不要。実行: 不要（登録簿の静的検査のみ）。
;;
;; 実行: `npx nbb observatory-registry-check.cljs <dir> [--min 10]`
;;
;; ─────────────────────────────────────────────────────────────────────────
;; ## この gate が両方向に動くことの証明（2026-08-12）
;;
;; **不変条件を編集するなら、この節を一緒に読むこと。** 検査を足したら
;; `observatory-registry-discriminate.cljs` にケースを 1 つ足し、赤くなることを
;; 実際に見てから landed とする。**落ちない gate は劇場**（CLAUDE.md）。
;;
;; ### なぜここに記録があるか
;;
;; この gate は **生涯一度も green にならなかった**（0 pass / 274 fail、
;; ADR-2608124800）。隣の gate と違って break/unbreak の記録も無く、
;; **どちらの向きにも discriminate することが一度も示されていなかった。**
;; 常時赤は常時緑と同じく無情報である —— 「今日も赤い」は誰も行動できない。
;;
;; 9 件の違反の内訳は 4 種類で、**gate 自身の誤りが 8 件を占めていた**:
;;   - 5 件: 2026-08-11 の rename に不変条件 5 が追随していなかった（:repo 未対応）
;;   - 1 件: 登録簿の本物の穴（com-etzhayyim-rasen に :repo が無い）
;;   - 1 件: 不変条件 4 が「このパスは repo の**外**でなければならない」を表現できず、
;;           意図的に文書化された要件を違反として報告していた
;;   - 2 件: 不変条件 11 は真だが、**主張していた害が実測で偽だった**（下の 11 節）
;;
;; ### 実測（`nbb gates/observatory-registry-discriminate.cljs <tree>`）
;;
;;   baseline（無改変）                                    exit=0
;;   inv 1  :runtime を落とす                              exit=1 → 戻して exit=0
;;   inv 2  :expect を語彙外に                             exit=1 → 戻して exit=0
;;   inv 3  :known-broken から :blocked-by を落とす         exit=1 → 戻して exit=0
;;   inv 4  :args に相対パス                               exit=1 → 戻して exit=0
;;   inv 4  repo 外の絶対パスから :args-outside-repo を落とす exit=1 → 戻して exit=0
;;   inv 5  :repo を west.yml に無い名前に                  exit=1 → 戻して exit=0
;;   inv 5  :repo を落として :name へ落ちる経路             exit=1 → 戻して exit=0
;;   inv 6  台帳から登録済み actor を 1 件消す              exit=1 → 戻して exit=0
;;   inv 7  :note の無い :unmeasured を足す                exit=1 → 戻して exit=0
;;   inv 8  :inventory-note を落とす                       exit=1 → 戻して exit=0
;;   inv 9  :next から :fix を落とす                       exit=1 → 戻して exit=0
;;   inv 10 :change-rate-basis を語彙外に                   exit=1 → 戻して exit=0
;;   inv 10 :change-rate を落とす                          exit=1 → 戻して exit=0
;;   inv 10 :importance を落とす                           exit=1 → 戻して exit=0
;;   inv 11 XML コメントに 2 連ハイフンを戻す               exit=1 → 戻して exit=0
;;   inv 11 <plist> 要素を落とす                            exit=1 → 戻して exit=0
;;   → cases 16 / 失敗 0、harness 自身も exit=0
;;
;; **各ケースは「赤くなった」だけでなく、その不変条件固有のメッセージが出たことも
;; 主張する。** さもないと、別の不変条件が偶然落ちただけで「検出した」と読める。
;;
;; ### harness を書いて初めて分かったこと（2 件とも harness 側の誤り）
;;
;; 最初の実行は 16 中 2 ケースが「壊しても緑」と出た。どちらも **gate ではなく
;; 壊し方が間違っていた** —— つまり「壊したつもりで壊れていなかった」:
;;   - `:inventory-note` はコメント行にも現れるので、素朴な `replace-first` が
;;     **コメントを潰して本体を無傷で残していた**
;;   - `<plist` を `<plistDISABLED` に変えても、**部分文字列としては一致し続ける**
;;
;; **これは harness が要る理由そのものである。** 目視なら «壊した→赤い» を確かめた
;; つもりで通っていた。壊れたことを機械に確認させないと、検査の検査もまた劇場になる。
;; ─────────────────────────────────────────────────────────────────────────

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

    ;; 4. :args のパスは ${REPO} 起点か、**意図的に repo の外**だと宣言されているか。
    ;;
    ;; この不変条件が本当に捕まえたいのは **相対パス**（起動場所に依存して静かに
    ;; 別の場所を読み書きする）であって、「${REPO} でない」ことそのものではない。
    ;; 絶対パスにはその欠陥が無い —— どこから起動しても同じ場所を指す。
    ;;
    ;; そして登録簿には「repo の外へ逃がすのが**必須**」な出力が実在する（hayari の
    ;; `--summary-out`）。既定の出力先 data/hayari-summary.edn は **tracked** なので、
    ;; 既定のまま走らせると 1 run ごとに west checkout が dirty になり、以後
    ;; `west update` がその project を skip して query 面が静かに固まる。
    ;; つまりここで要るのは「このパスは repo の**中にあってはならない**」という、
    ;; 元の不変条件が構文的に表現できなかった向きの要求である。
    ;;
    ;; 逃がすこと自体は禁じず、**理由を名前で持たせる**（:blocked-by / :note /
    ;; :change-rate-source と同じ、この登録簿の一貫した作法）。宣言の無い絶対パスは
    ;; 従来どおり違反 —— 事故で repo の外に書くのと、そう設計したのは別物。
    (let [outside (:args-outside-repo o)]
      (doseq [a (:args o)]
        (when (and (string? a)
                   (not (str/starts-with? a "--"))
                   (or (str/includes? a "/") (str/ends-with? a ".edn"))
                   (not (str/starts-with? a "${REPO}")))
          (cond
            ;; 相対パス —— 元からの違反。逃がす宣言があっても許さない
            ;; （宣言が意味を持つのは「repo の外」であって「どこか」ではない）
            (not (str/starts-with? a "/"))
            (v! n ": :args のパス " (pr-str a) " が ${REPO} 起点でも絶対パスでもない"
                " — 相対パス依存を避けるための登録簿でそれをやると意味が無い")

            (str/blank? (str outside))
            (v! n ": :args のパス " (pr-str a) " が repo の外を指しているのに"
                " :args-outside-repo が無い"
                " — 意図的に外へ逃がすなら理由を書くこと（事故で外に書くのと区別できない）")))))

    ;; 5. west.yml に実在するか。
    ;;
    ;; ⚠ 見るのは **(or (:repo o) (:name o))**。2026-08-11 の rename で repo 名が
    ;; observatory 名から離れ（`com-etzhayyim-*` → role 面の `actor-*`）、登録簿は
    ;; `:name` を台帳・cadence・datoms の識別子として据え置いたまま、repo の在処を
    ;; `:repo` で指す契約になった（manifest/observatories.edn 冒頭が明記:
    ;; 「`:repo` 省略時は `:name` が repo 名として使われる」）。
    ;; **`:name` だけを見ると、正しく登録された 5 件を「west に無い」と報告する。**
    (let [repo-name (or (:repo o) (:name o))]
      (when (and (seq west-names) repo-name (not (contains? west-names repo-name)))
        (v! n ": west.yml に project として登録が無い（repo 名 " repo-name "）")))))

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
;; **この不変条件は正しいが、2026-08-12 まで書かれていた理由は誤りだった。**
;;
;; 旧文は「壊れた plist は落ちるのではなく launchd に読まれないだけ / 設定したのに
;; 一度も走っていないという気付きにくい壊れ方をする」と書いていた。**測ったら偽。**
;;
;;   plutil -lint scripts/fleet-ci/*.plist   → 10/10 OK（2 連ハイフンを含む 2 件も）
;;   launchctl list | grep residency         → 62161  1  com.gftd.residency-alarm
;;   diff ~/Library/LaunchAgents/com.gftd.residency-alarm.plist <repo の同名> → 差分なし
;;
;; つまり **gate が「読まれない」と言っていた当のファイルは、バイト一致のまま
;; 実際に load されて PID を持って動いていた。** Apple の CFPropertyList は意図的に
;; 寛容で、コメント内の 2 連ハイフンを受け入れる。主張していた害は起きない。
;;
;; **ではなぜ残すか —— 準拠 XML パーサは本当に拒否するから。**
;;
;;   python3 -c "import xml.etree.ElementTree as ET; ET.parse('com.gftd.residency-alarm.plist')"
;;     → xml.etree.ElementTree.ParseError: not well-formed (invalid token): line 5, column 43
;;   同 com.gftd.hayari-tick.plist → line 14, column 5
;;   同 com.gftd.observatory-run.plist → 正常に parse できる
;;
;; expat（準拠パーサ）は問題の行をピンポイントで拒否する。XML 1.0 §2.5 は
;; コメント本体に `--` が現れてはならないと定めており、これらのファイルは
;; **本当に well-formed XML ではない**。害は「launchd が読まない」ことではなく、
;; **Apple の寛容なパーサ以外のどの XML ツールもこのファイルを扱えない**こと。
;; 検査そのものは正しかった —— 間違っていたのは理由の方である。
;;
;; **不変条件の主張は、それが防ぐと称する害まで含めて検証すること。** 検査が
;; 真であることと、書かれた理由が真であることは別物で、後者が偽のまま残ると
;; 次に読む者は「launchd が読まない」を既知の事実として引用する（ADR-2608124800）。
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
              (v! f ": XML コメントに 2 連ハイフンが入っている（XML 1.0 §2.5 違反）"
                  " — Apple の寛容なパーサは受け入れるが、準拠 XML パーサは拒否する"
                  "（launchd に読まれなくなるわけではない。上のヘッダ参照）"))))
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

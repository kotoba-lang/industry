#!/usr/bin/env nbb
;; observatory-registry-discriminate.cljs
;; ── observatory-registry-check.cljs が **両方向に動く**ことの機械証明 ──────────
;;
;; CLAUDE.md:「gate は『落ちること』を確かめてから landed とする。対象を 1 箇所
;; 壊したコピーで exit 1 になり、無改変で exit 0 になることを実際に見る。
;; 落ちない gate は劇場。」
;;
;; **なぜこれが要ったか。** root-observatory-registry は 2026-08-12 の監査
;; （ADR-2608124800）まで **0 pass / 274 fail、生涯一度も green にならなかった**。
;; 隣の gate と違って break/unbreak の記録も無く、**どちらの向きにも discriminate
;; することが一度も示されていなかった**。常時赤は常時緑と同じく無情報である ——
;; 「今日も赤い」は誰も行動できない。緑にするのは半分でしかないので、ここで
;; 残った不変条件を 1 つずつ壊して赤くなることを見る。
;;
;; **各ケースは 3 つを主張する**（どれか 1 つでも欠けたら劇場になる）:
;;   1. 壊したコピーで exit != 0
;;   2. その時の出力に**その不変条件固有の**メッセージが出る
;;      （別の不変条件が偶然落ちて赤くなっただけ、を弾く）
;;   3. 戻したコピーで exit 0
;;
;; 実行: `nbb scripts/fleet-ci/gates/observatory-registry-discriminate.cljs <tree>`
;; ネットワーク: 不要。実 tree は書き換えない（temp へ複製して壊す）。

(ns fleet-ci.gates.observatory-registry-discriminate
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]))

(def argv (vec *command-line-args*))
(def src-dir (or (first (remove #(str/starts-with? % "--") argv)) "."))
;; 検査対象の gate は **その tree のもの**を使う（harness と gate が別 tree を
;; 見ていると、緑の証明が別物の証明になる）。
(def gate (.join path src-dir "scripts" "fleet-ci" "gates" "observatory-registry-check.cljs"))

(defn- j [& xs] (apply (.-join path) (clj->js xs)))
(defn- rd [f] (.readFileSync fs f "utf8"))
(defn- wr [f s] (.writeFileSync fs f s))

;; ── 作業用の最小 tree を組む ────────────────────────────────────────────
;; gate が読むのは登録簿・west.yml・plist 群・台帳だけなので、その 4 つだけ複製する。
;; 実 tree を壊さないことがこの harness の前提条件（壊す harness は harness でない）。
(def work (.mkdtempSync fs (j (.tmpdir os) "obs-discriminate-")))
(def REG (j work "manifest" "observatories.edn"))
(def WEST (j work "manifest" "west.yml"))
(def PLIST (j work "scripts" "fleet-ci" "com.gftd.observatory-run.plist"))

(defn- setup! []
  (doseq [d ["manifest" "scripts/fleet-ci" "90-docs/observatory"]]
    (.mkdirSync fs (j work d) #js {:recursive true}))
  (.copyFileSync fs (j src-dir "manifest" "observatories.edn") REG)
  (.copyFileSync fs (j src-dir "manifest" "west.yml") WEST)
  (doseq [f (vec (.readdirSync fs (j src-dir "scripts" "fleet-ci")))
          :when (str/ends-with? f ".plist")]
    (.copyFileSync fs (j src-dir "scripts" "fleet-ci" f) (j work "scripts" "fleet-ci" f)))
  (let [led (j src-dir "90-docs" "observatory" "observatory.datoms.edn")]
    (when (.existsSync fs led)
      (.copyFileSync fs led (j work "90-docs" "observatory" "observatory.datoms.edn")))))

(defn- run-gate [& extra]
  (let [r (.spawnSync cp "nbb" (clj->js (concat [gate work "--min" "10"] extra))
                      #js {:encoding "utf8"})]
    {:status (.-status r) :out (str (.-stdout r) (.-stderr r))}))

;; ── 壊し方の語彙 ────────────────────────────────────────────────────────
(defn- sub! [f from to]
  (let [t (rd f)]
    (when-not (str/includes? t from)
      (throw (js/Error. (str "harness が壊れている: 想定した文字列が無い: " (pr-str from)))))
    (wr f (str/replace-first t from to))))

;; 正規表現版。**キー名がコメント中にも現れるとき、`sub!` は先に現れる方（＝コメント）を
;; 潰して本体を無傷で残す** —— 壊したつもりで壊れておらず、gate は正しく緑のままになる。
;; 実際にそれで 1 ケースが「検出していない」と誤診された（2026-08-12）。行頭アンカーで
;; 本体だけを狙う。
(defn- sub-re! [f re to]
  (let [t (rd f)]
    (when-not (re-find re t)
      (throw (js/Error. (str "harness が壊れている: 想定した正規表現が一致しない: " (str re)))))
    (wr f (str/replace t re to))))

;; ケース: [不変条件, 説明, 壊す手順, 赤いときに出るべき文字列]
(def cases
  [[1 ":runtime（必須キー）を落とす"
    #(sub! REG ":runtime :clojure :main \"inochi.methods.autorun\""
           ":main \"inochi.methods.autorun\"")
    "必須キー :runtime が無い"]

   [2 ":expect を語彙外の値にする"
    #(sub! REG ":expect :produces-datoms-idempotent" ":expect :produces-nonsense")
    "は語彙外"]

   [3 ":known-broken から :blocked-by を落とす"
    #(sub! REG ":blocked-by \"seed が repo に無い" ":blocked-by-DISABLED \"seed が repo に無い")
    ":blocked-by が無い"]

   [4 ":args に相対パスを書く（元からの不変条件）"
    #(sub! REG "\"/tmp/hayari-registry-gate-summary.edn\"" "\"tmp/relative-summary.edn\"")
    "${REPO} 起点でも絶対パスでもない"]

   [4 "repo 外の絶対パスから :args-outside-repo（理由）を落とす（今回足した向き）"
    #(sub! REG ":args-outside-repo \"data/hayari-summary.edn は tracked"
           ":args-outside-repo-DISABLED \"data/hayari-summary.edn は tracked")
    ":args-outside-repo が無い"]

   [5 ":repo を west.yml に無い名前にする"
    #(sub! REG ":repo \"actor-inochi\"" ":repo \"actor-does-not-exist\"")
    "west.yml に project として登録が無い"]

   [5 ":repo を落として :name へ落ちることを見る（rename 前の形に戻す）"
    #(sub! REG ":repo \"actor-inochi\"" ":repo-DISABLED \"actor-inochi\"")
    "west.yml に project として登録が無い"]

   [6 "台帳から登録済み actor を 1 件消す"
    #(let [f (j work "90-docs" "observatory" "observatory.datoms.edn")]
       (wr f (str/replace (rd f) ":observatory/name \"hayari\"" ":observatory/name \"hayari-GONE\"")))
    "台帳が登録済み actor を落としている"]

   [7 ":note の無い :unmeasured を足す"
    #(sub! REG ":unmeasured []" ":unmeasured [{:name \"未測定サンプル\"}]")
    ":unmeasured なのに :note が無い"]

   ;; ⚠ `:inventory-note` は 648 行目のコメントにも現れる。行頭アンカーで本体だけを狙う
   ;; （素朴な replace-first はコメントを潰して本体を残す）。
   [8 ":unmeasured が空のまま :inventory-note を落とす"
    #(sub-re! REG #"(?m)^\s*:inventory-note\s*$" ":inventory-note-DISABLED")
    ":inventory-note が無い"]

   [9 ":next から :fix を落とす"
    #(sub! REG ":target \"tadori\" :fix" ":target \"tadori\" :fix-DISABLED")
    ":next の :fix が無い"]

   [10 ":change-rate-basis を語彙外にする（prior を測定値と偽らせない検査）"
    #(sub! REG ":change-rate-basis :prior" ":change-rate-basis :guessed")
    ":change-rate-basis :guessed は語彙外"]

   [10 ":change-rate を落とす（走る actor に λ が無い）"
    #(sub! REG ":change-rate 0.02" ":change-rate-DISABLED 0.02")
    "走る actor なのに :change-rate が無い"]

   [10 ":importance を落とす"
    #(sub! REG ":importance 5           ; [sec·day] 陳腐化" ":importance-DISABLED 5  ; 陳腐化")
    ":importance が無いか正でない"]

   [11 "plist の XML コメントに 2 連ハイフンを戻す"
    #(sub! PLIST "<!--" "<!-- flag: --pin\n")
    "2 連ハイフン"]

   ;; ⚠ gate の検査は `str/includes? txt "<plist"` という素の部分文字列判定なので、
   ;; `<plistDISABLED` に書き換えても**部分文字列としては一致し続ける**（実測 2026-08-12、
   ;; これで「検出していない」と誤診した）。要素名ごと置き換えて本当に消す。
   ;; 閉じタグ `</plist>` は `<plist` を含まない（`<` の次が `/`）ので残ってよい。
   [11 "plist から <plist> 要素を落とす"
    #(sub! PLIST "<plist version=\"1.0\">" "<propertylist version=\"1.0\">")
    "<plist> 要素が無い"]])

;; ── 実行 ────────────────────────────────────────────────────────────────
(setup!)

(println "observatory-registry-discriminate")
(println (str "  tree: " src-dir))
(println (str "  work: " work))
(println)

;; 前提: 無改変で緑。これが赤いと以降の「赤くなった」は何の証拠にもならない。
(let [{:keys [status out]} (run-gate)]
  (println (str "baseline (無改変): exit=" status))
  (when-not (zero? status)
    (println out)
    (println "FAIL baseline が緑でない — discriminate 以前の問題")
    (.exit js/process 1)))
(println)

(def failures (atom []))

(doseq [[inv desc break! expect-msg] cases]
  (let [pristine (rd REG)
        pristine-plist (rd PLIST)
        pristine-led (let [f (j work "90-docs" "observatory" "observatory.datoms.edn")]
                       (when (.existsSync fs f) (rd f)))]
    (break!)
    (let [{:keys [status out]} (run-gate)
          red? (not (zero? status))
          said? (str/includes? out expect-msg)]
      ;; 復元してから、戻ったことも確かめる
      (wr REG pristine)
      (wr PLIST pristine-plist)
      (when pristine-led
        (wr (j work "90-docs" "observatory" "observatory.datoms.edn") pristine-led))
      (let [back (:status (run-gate))
            ok? (and red? said? (zero? back))]
        (println (str (if ok? "  ✓" "  ✗") " inv " inv " — " desc))
        (println (str "      壊した: exit=" status
                      (if said? (str " / 期待した違反を報告: " (pr-str expect-msg))
                          (str " / **期待した違反が出ていない** " (pr-str expect-msg)))))
        (println (str "      戻した: exit=" back))
        (when-not ok?
          (swap! failures conj (str "inv " inv " " desc
                                    (cond (not red?) " — 壊しても緑のまま（検出していない）"
                                          (not said?) " — 赤くなったが別の理由（固有メッセージが出ていない）"
                                          :else " — 戻しても赤いまま"))))))))

(println)
(let [fs* @failures]
  (println (str "cases " (count cases) " / 失敗 " (count fs*)))
  (doseq [x fs*] (println (str "  ✗ " x)))
  (if (seq fs*)
    (do (println "FAIL この gate は主張どおりに discriminate していない") (.exit js/process 1))
    (println (str "OK 全 " (count cases) " ケースで 赤→緑 の両方向を確認"))))

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
;;   2. :expect が語彙内（produces-datoms / produces-files / runs-ok /
;;      runs-empty / known-broken）
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

(def expect-vocab #{:produces-datoms :produces-files :runs-ok :runs-empty :known-broken})

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

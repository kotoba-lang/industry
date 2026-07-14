#!/usr/bin/env nbb
;; verify.cljs — RelationalTime 検証ハーネス（nbb。repo 規約: 検証/テストハーネスは
;; sh でなく nbb .cljs で書く）。旧 verify.sh を置換。
;;
;; 手順:
;;   1. lake exe cache get  — mathlib prebuilt olean 取得（cold cache でのソース
;;      ビルド数時間を回避。オフライン等の失敗は警告のみで続行）
;;   2. lake build
;;   3. 未証明マーカー検査 — 外部 grep/rg に依存せず in-process で走査するので
;;      「scanner 不在で検査が黙って skip される」fail-open が構造的に起きない。
;;      対象: sorry / sorryAx / admit / native_decide、および修飾子付き・インデント
;;      された axiom（namespace 内の `  axiom` / `private axiom`）。lake build は
;;      sorry を warning でしか報告せず axiom は何も出さないため、この検査が唯一の
;;      防衛線。読めないファイル/ディレクトリは fail-closed（検査失敗 = verify 失敗）。
;;   4. lake exe time の出力に RELATIONAL_TIME_PASS 行を要求
;; すべて通った時のみ TIME_VERIFY_PASS を出力して exit 0。
;;
;; 使い方:
;;   npx nbb verify.cljs               # フル検証
;;   npx nbb verify.cljs --scan-only   # 手順3のみ（CI での軽量ゲート/自己テスト用）

(require '[clojure.string :as str]
         '["node:child_process" :as cp]
         '["node:fs" :as fs]
         '["node:path" :as path])

(def dir (path/dirname (path/resolve *file*)))

(defn- die! [msg]
  (js/console.error msg)
  (js/process.exit 1))

(defn- lake!
  "lake <args> を stdio 継承で実行。:tolerate? true は失敗を警告に落として false を
   返す（cache get 用）。それ以外は失敗で即 exit 1。"
  [{:keys [tolerate?]} & args]
  (let [r (cp/spawnSync "lake" (clj->js args) #js {:cwd dir :stdio "inherit"})
        problem (or (some-> (.-error r) str)
                    (when-not (zero? (or (.-status r) 1))
                      (str "exit " (.-status r))))]
    (cond
      (nil? problem) true
      tolerate? (do (js/console.error
                     (str "warning: lake " (str/join " " args)
                          " failed (" problem ") — continuing with local cache"))
                    false)
      :else (die! (str "lake " (str/join " " args) " failed (" problem ")")))))

;; ---------- 手順3: 未証明マーカー検査 ----------

(def ^:private word-markers-re
  #"(?:^|[^A-Za-z0-9_])(sorry|sorryAx|admit|native_decide)(?:$|[^A-Za-z0-9_])")

(def ^:private axiom-re
  #"^\s*(?:(?:private|protected|noncomputable)\s+)*axiom(?:[^A-Za-z0-9_]|$)")

(defn- lean-files
  "検査対象の .lean 一覧。RelationalTime/ は再帰、RelationalTime.lean / Main.lean は
   単体で必須 — 見つからなければ throw（fail-closed: 対象が消えた状態で緑にしない）。"
  []
  (letfn [(walk [p]
            (if (.isDirectory (fs/statSync p))
              (mapcat #(walk (path/join p %)) (js->clj (fs/readdirSync p)))
              (when (str/ends-with? p ".lean") [p])))]
    (concat (walk (path/join dir "RelationalTime"))
            (map (fn [f]
                   (let [p (path/join dir f)]
                     (when-not (fs/existsSync p)
                       (throw (js/Error. (str "scan target missing: " f))))
                     p))
                 ["RelationalTime.lean" "Main.lean"]))))

(defn- scan-markers []
  (try
    (let [hits (for [f (lean-files)
                     [i line] (map-indexed vector (str/split-lines (fs/readFileSync f "utf8")))
                     :when (or (re-find word-markers-re line) (re-find axiom-re line))]
                 (str (path/relative dir f) ":" (inc i) ": " (str/trim line)))]
      (when (seq hits)
        (doseq [h hits] (js/console.error h))
        (die! "unproved declaration marker found")))
    (catch :default e
      (die! (str "marker scan failed (fail-closed): " (or (.-message e) e))))))

;; ---------- 手順4: 実行出力の検査 ----------

(defn- run-exe! []
  (let [r (cp/spawnSync "lake" #js ["exe" "time"] #js {:cwd dir :encoding "utf8"})
        out (or (.-stdout r) "")
        err (or (.-stderr r) "")]
    (when (seq out) (print out))
    (when (seq err) (js/console.error err))
    (when (or (some? (.-error r)) (not (zero? (or (.-status r) 1))))
      (die! "lake exe time failed"))
    (when-not (some #(= "RELATIONAL_TIME_PASS" %) (str/split-lines out))
      (die! "RELATIONAL_TIME_PASS not found in output"))))

;; ---------- main ----------

(let [args (js->clj (.slice js/process.argv 2))]
  (if (some #{"--scan-only"} args)
    (do (scan-markers)
        (println "MARKER_SCAN_PASS"))
    (do (lake! {:tolerate? true} "exe" "cache" "get")
        (lake! {} "build")
        (scan-markers)
        (run-exe!)
        (println "TIME_VERIFY_PASS"))))

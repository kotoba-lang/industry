#!/usr/bin/env nbb
;; closing のチェックリストを 1 コマンドにする。
;;
;;   nbb scripts/closing-check.cljs [--root .] [--quick]
;;
;; CLAUDE.md の `closing` 節は 6 段の手順を prose で書いている。prose の手順は
;; **思い出した時だけ実行される** —— このセッションだけで、思い出されなかった段が
;; 4 つあった（west 登録漏れ / ADR 本文の切断 / データセットの縮小 / 共有 checkout
;; への直接編集）。どれも「やらないと決めた」のではなく「その時 prose を読み返さな
;; かった」だけである。だから機械が読み返す。
;;
;; ## この script が守る規律
;;
;; **走らなかった検査を、通った検査と同じに見せない。** nbb が無い・script が無い・
;; timeout した —— どれも FAIL として数える。このセッションで作った検査は 2 つとも
;; 「入力が見えないのに OK と言う」形で一度沈黙しており（west 子リポに `origin/main`
;; が無い / superproject に子リポの内容が無い）、**沈黙は緑と区別が付かない**。
;;
;; ## 何を検査しないか
;;
;; 他セッションの worktree / branch / stash は**報告するだけで落とさない**。closing は
;; 自分の作業物だけを片付ける手順であって、他人の WIP を判定する権限は無い。

(ns closing-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(defn- flag [n d] (let [i (.indexOf args n)] (if (neg? i) d (nth args (inc i)))))
(def root  (path/resolve (flag "--root" ".")))
(def quick? (some #{"--quick"} args))
;; 検査対象の tree（--root）と、検査「する」script の在り処は別。worktree で
;; 育てた検査を本体の checkout に当てる、という運用が実際に要る。
(def self-dir (path/dirname *file*))

(defn- run [cmd argv & [{:keys [timeout-ms cwd]}]]
  (let [r (cp/spawnSync cmd (clj->js (vec argv))
                        #js {:cwd (or cwd root) :encoding "utf8"
                             :timeout (or timeout-ms 600000) :maxBuffer 67108864})]
    {:code (if (some? (.-status r)) (.-status r) :killed)
     :out  (str (.-stdout r) (.-stderr r))}))

(defn- last-lines [s n]
  (->> (str/split-lines (str/trim (or s ""))) (remove str/blank?) (take-last n) (str/join "\n    ")))

;; ── 検査 ────────────────────────────────────────────────────────────────

(defn- check-main-sync []
  (run "git" ["fetch" "origin" "--quiet"] {:timeout-ms 120000})
  (let [{:keys [code out]} (run "git" ["rev-list" "--left-right" "--count" "origin/main...HEAD"])]
    (if (not= 0 code)
      {:ok? false :detail "origin/main と比較できない"}
      (let [[behind ahead] (map #(js/parseInt % 10) (str/split (str/trim out) #"\s+"))]
        {:ok? (zero? behind)
         :detail (str "origin/main から " ahead " ahead / " behind " behind"
                      (when (pos? behind) " — 同期してから着地する（rebase しない）"))}))))

(defn- script-check
  "script を走らせて exit code で判定する。**script が無いことは skip ではなく FAIL。**
  検査が消えたのに closing が緑になるのが、一番静かな退行だから。"
  [rel argv]
  (let [f (path/join self-dir rel)]
    (if-not (fs/existsSync f)
      {:ok? false :detail (str "script が無い — " f)}
      (let [{:keys [code out]} (run "nbb" (into [f] argv))]
        {:ok? (= 0 code)
         :detail (if (= 0 code)
                   (last-lines out 1)
                   (str "exit=" code "\n    " (last-lines out 6)))}))))

(defn- inventory []
  (let [wt  (->> (str/split-lines (:out (run "git" ["worktree" "list"])))
                 (remove str/blank?) (drop 1))
        st  (->> (str/split-lines (:out (run "git" ["stash" "list"]))) (remove str/blank?))
        dirty (->> (str/split-lines (:out (run "git" ["status" "--porcelain" "--untracked-files=no"])))
                   (remove str/blank?))]
    (println "\n── 棚卸し（報告のみ。他セッションの作業物は判定しない）")
    (println (str "  worktree " (count wt) " · stash " (count st) " · 本体の未コミット " (count dirty) " 件"))
    (doseq [l (take 8 wt)] (println (str "    " l)))
    (when (seq st)   (println (str "    stash: " (str/join " / " (take 3 st)))))
    (when (seq dirty)
      (println "    ⚠ superproject 本体は統合・閲覧専用。編集は worktree でやる")
      (doseq [l (take 5 dirty)] (println (str "      " l))))))

;; ── 実行 ────────────────────────────────────────────────────────────────

(def checks
  (cond-> [["main 同期"            check-main-sync]
           ["west 登録漏れ"        #(script-check "west-orphan-audit.cljs" ["--blocking"])]
           ["ADR/EDN の切断"       #(script-check "fleet-ci/gates/docs-edn-truncation-check.cljs"
                                                  ["--root" root])]
           ["データセットの縮小"   #(script-check "dataset-monotonic-check.cljs"
                                                  ["--root" root "--decl" (path/join self-dir ".." "manifest" "monotonic-datasets.edn")])]]
    (not quick?)
    (conj ["west.yml が canonical" #(script-check "gen-west-manifest.cljs" ["--check"])])))

(println (str "closing-check: " (count checks) " 件 · " root
              (when quick? " · --quick（west.yml の canonical 検査を省略）")))

(def results
  (doall
    (for [[label f] checks]
      (let [r (try (f) (catch :default e {:ok? false :detail (str "検査自体が落ちた: " (.-message e))}))]
        (println (str (if (:ok? r) "  OK   " "  FAIL ") label
                      (when (seq (:detail r)) (str ": " (:detail r)))))
        [label r]))))

(inventory)

(def failed (filter (comp not :ok? second) results))

(println)
(if (empty? failed)
  (println (str "OK closing-check: " (count checks) " 件すべて通過。"
                "\n   残るのは機械にできない分 —— 正本 ADR の更新、PR の着地、"
                "resume point の記録。"))
  (do (println (str "FAIL closing-check: " (count failed) " 件 — "
                    (str/join ", " (map first failed))))
      (println "   closing を完了扱いにしない。直すか、blocker として明示する。")
      (js/process.exit 1)))

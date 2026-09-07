#!/usr/bin/env nbb
;; gen-agents-md.cljs — AGENTS.md（Codex 向け agent 指示）を CLAUDE.md から生成する。
;;
;; 正本は CLAUDE.md。AGENTS.md は生成物で、手書き禁止（west.yml と同じ規律）。
;; ADR-2609062600。gate は `root-agents-md-generated`。
;;
;;   nbb scripts/gen-agents-md.cljs           # 生成して書き込む
;;   nbb scripts/gen-agents-md.cljs --check   # 差分があれば exit 1（書き込まない）
;;
;; exit code:
;;   0  生成した / --check で一致
;;   1  --check で不一致（再生成が要る）
;;   2  REFUSED — 置換表が期待どおりに当たらなかった。**答えを出さずに止まる**
;;
;; 置換は「期待した文字列が期待した回数だけ在る」ことを先に確かめ、1 つでも
;; 食い違えば exit 2 で拒否する。黙って違う置換をするより、答えないほうがよい
;; —— CLAUDE.md 自身が「測れなかった検査が、測って問題が無かった検査と同じ値を
;; 返す」形を repo-wide の失敗クラスとして名指ししている。
;;
;; ⚠ 置換してよいのは「この文書自身への自己参照」と「agent の名前」だけ。
;;   `.claude/hooks/*` `.claude/settings.json` `.claude/skills/` `.claude/workflows/`
;;   `.claude/worktrees/` `claude.ai` は **実在する path と service** であって
;;   agent の別名ではない。過去に素の Claude→Codex 一括置換が当てられ、AGENTS.md の
;;   10 個の path が実在しない `.Codex/` を指していた（2026-09-06 実測）。

(require '[clojure.string :as str])

(def fs (js/require "node:fs"))

(def source "CLAUDE.md")
(def target "AGENTS.md")

(def header
  (str "<!-- GENERATED FILE — DO NOT EDIT.\n"
       "     正本は CLAUDE.md。ここを直しても次の生成で消える。\n"
       "     規則を足す/直すときは CLAUDE.md を編集し、\n"
       "       nbb scripts/gen-agents-md.cljs\n"
       "     を回す。検査は --check（fleet gate root-agents-md-generated）。\n"
       "     ADR-2609062600. -->\n"))

;; [expected-count find replace]
;; count を明示するのは、source が動いたときに黙って違う置換をしないため。
(def substitutions
  [[1 "# CLAUDE.md"
      "# AGENTS.md"]
   ;; agent の名前（節の見出しと本文）
   [1 "## Claude Code の Agent 委譲 — fork は調査専用、実行系は fresh agent + worktree 隔離（2026-07-12）"
      "## Codex の Agent 委譲 — fork は調査専用、実行系は fresh agent + worktree 隔離（2026-07-12）"]
   ;; この文書自身への自己参照
   [1 "（この CLAUDE.md 含む）"          "（この AGENTS.md 含む）"]
   [1 "この CLAUDE.md は既に"            "この AGENTS.md は既に"]
   [1 "CLAUDE.md には既に「既存を確認せよ」が" "AGENTS.md には既に「既存を確認せよ」が"]
   [1 "CLAUDE.md が禁じている rebase か"  "AGENTS.md が禁じている rebase か"]
   [1 "この CLAUDE.md 自身が fleet-ci の節でそう警告している"
      "この AGENTS.md 自身が fleet-ci の節でそう警告している"]
   [1 "この CLAUDE.md が\n   fleet-ci 節で繰り返し警告しているとおり"
      "この AGENTS.md が\n   fleet-ci 節で繰り返し警告しているとおり"]
   [1 "**この CLAUDE.md 自身が「一括正規表現の書き換えが"
      "**この AGENTS.md 自身が「一括正規表現の書き換えが"]])

(defn- refuse! [msg]
  (binding [*print-fn* *print-err-fn*]
    (println (str "REFUSED: " msg))
    (println "置換表が source と食い違っている。答えを出さずに止まる。")
    (println "CLAUDE.md を直したなら scripts/gen-agents-md.cljs の substitutions も直す。"))
  (js/process.exit 2))

(defn generate [src]
  (reduce (fn [acc [expected find replace]]
            (let [n (count (str/split acc (re-pattern (str/replace find #"([.*+?^${}()|\[\]\\])" "\\$1"))))
                  actual (dec n)]
              (when (not= actual expected)
                (refuse! (str "期待 " expected " 回 / 実際 " actual " 回 — "
                              (subs find 0 (min 60 (count find))))))
              (str/replace acc find replace)))
          src
          substitutions))

(defn -main []
  (let [args (set (js->clj (.slice (.-argv js/process) 2)))
        src (str (.readFileSync fs source "utf8"))
        out (str header "\n" (generate src))]
    ;; 置換が実在しない path を **持ち込んで** いないことを確かめる（過去の事故）。
    ;; source に既に在る出現は数えない —— この文書はその事故自体を記述しており、
    ;; 文字列としての `.Codex/` は正当に登場する。増えたときだけ拒否する。
    (doseq [bad [".Codex/" ".codex/hooks/"]]
      (let [before (dec (count (str/split src (re-pattern (str/replace bad "." "\\.")))))
            after  (dec (count (str/split out (re-pattern (str/replace bad "." "\\.")))))]
        (when (> after before)
          (refuse! (str "置換が " bad " を " (- after before) " 個持ち込んだ —— "
                        "実在しない path。置換表が広すぎる")))))
    (if (contains? args "--check")
      (let [cur (when (.existsSync fs target) (str (.readFileSync fs target "utf8")))]
        (if (= cur out)
          (do (println "AGENTS.md: up to date") (js/process.exit 0))
          (do (binding [*print-fn* *print-err-fn*]
                (println "AGENTS.md is STALE — regenerate with: nbb scripts/gen-agents-md.cljs"))
              (js/process.exit 1))))
      (do (.writeFileSync fs target out)
          (println (str "wrote " target " (" (count (str/split-lines out)) " lines) from " source))
          (js/process.exit 0)))))

(-main)

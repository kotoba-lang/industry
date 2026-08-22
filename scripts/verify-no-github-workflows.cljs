#!/usr/bin/env nbb
;; verify-no-github-workflows.cljs — この tree が GitHub に「自分で走ってよい」と
;; 言っていないことを確かめる。CI の正本は murakumo fleet（ADR-2607300900、
;; オーナー指示 2026-08-05「github は使わない、murakumo.cloud の cdci, workflow を
;; 使う」）であり、repo root の `.github/workflows/*.yml` は **GitHub が自分の
;; 判断で runner を起こす唯一の入口**なので、そこが空であることが不変条件。
;;
;;   nbb scripts/verify-no-github-workflows.cljs [<dir>] [--min N]
;;
;; ## 三値で答える（0 と 2 を混ぜない）
;;
;;   0  走査して違反なし
;;   1  違反あり（root の .github/workflows に workflow がある）
;;   2  **答えられなかった** — tree が届いていない / yaml を 1 つも見なかった
;;
;; 2 が 0 と別であることが要点。fleet は `:include-ext` で絞った tarball を配る
;; ので、絞り込みを 1 つ書き間違えると「.github/workflows が無い tree」が
;; 「workflow を持たない repo」と同じ顔で緑になる（CLAUDE.md 実測:
;; gh-workflow-assoc-gapki）。
;;
;; 床は **件数ではなく錨（anchor）** —— 既知の yaml が 1 本、実際に届いている
;; ことを要求する。件数を床にした最初の版は間違いだった: この repo が自分で
;; 持つ yaml は 5 本しかなく（残りは vendored な takeout / comfyui）、
;; vendored を整理した日に床を割って**恒久的に exit 2** になる。
;; 錨は「配送が生きているか」だけを問い、tree の中身の変化に連動しない。
;;
;; ## 見ていないものを名指しする
;;
;; ここが見るのは **tree だけ**。GitHub 側の設定（`actions/permissions`、
;; Dependabot / dependency graph）は credential を要するのでノードでは引けない
;; （fleet の不変条件: ノードに credential を置かない）。そちらは operator 側の
;; `scripts/github-actions-billable-audit.cljs` が持つ。**この検査の緑は
;; 「GitHub Actions が無効である」ことを意味しない** —— 意味するのは
;; 「この tree は GitHub に workflow を渡していない」だけ。
;;
;; ## nested な .github/workflows は違反にしない
;;
;; GitHub が honor するのは repo root の `.github/workflows/` だけで、
;; `orgs/personal/takeout/**` に混ざった vendored な workflow は実行されない
;; （実測 2026-08-22: root の registered workflow に 1 本も現れない）。
;; 件数は報告するが verdict には入れない —— 数えたことと罰することは別。
(ns verify-no-github-workflows
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(defn- opt [f d] (let [i (.indexOf args f)] (if (neg? i) d (nth args (inc i) d))))

(def dir (or (first (remove #(str/starts-with? % "--") args)) "."))
(def floor (js/parseInt (str (opt "--min" "3")) 10))
;; 錨: これが tree に無ければ、配送が壊れているか対象が root ではない。
;; どちらの場合も「workflow は無い」と答えてはいけない。
(def anchor (opt "--anchor" "manifest/west.yml"))

(defn- yaml? [p] (or (str/ends-with? p ".yml") (str/ends-with? p ".yaml")))

(def max-depth 40)

;; 深さで打ち切ったことを黙って件数に反映させない。最初の版は 12 で切っており、
;; vendored な takeout（深さ 12 超）を数え落としたまま SCANNED を印字していた
;; —— 走れなかった分が「無かった」分と同じ顔になる、この repo が繰り返し
;; 踏んでいる形（CLAUDE.md「検査を書く前・緑を信じる前の 5 問」）。
(def truncated (atom 0))

(defn- walk
  "dir 配下の yaml を相対パスで列挙する。`.git` を持たない tarball 用の経路。"
  [root]
  (let [out (atom [])]
    ((fn step [d depth]
       (if (>= depth max-depth)
         (swap! truncated inc)
         (doseq [e (try (fs/readdirSync d #js {:withFileTypes true})
                        (catch :default _ []))]
           (let [n (.-name e) p (path/join d n)]
             (cond
               (and (.isDirectory e) (not= n "node_modules") (not= n ".git"))
               (step p (inc depth))
               (and (.isFile e) (yaml? n))
               (swap! out conj (path/relative root p)))))))
     root 0)
    @out))

(defn- tracked
  "`.git` があるときは git に訊く。作業ディレクトリの取りこぼし（sparse cone の
   外、未 checkout の west project）を『無い』と読まないため —— CLAUDE.md の
   『手元に無いことは存在しないことではない』の同型。"
  [root]
  (let [r (cp/spawnSync "git" #js ["-C" root "ls-files" "-z" "--" "*.yml" "*.yaml"]
                        #js {:encoding "utf8" :maxBuffer (* 64 1024 1024)})]
    (when (zero? (or (.-status r) 1))
      (->> (str/split (str (.-stdout r)) #"\x00")
           (remove str/blank?)
           vec))))

(let [git?   (fs/existsSync (path/join dir ".git"))
      files  (or (when git? (tracked dir)) (walk dir))
      n      (count files)
      root-wf (filterv #(str/starts-with? % ".github/workflows/") files)
      nested  (filterv #(and (str/includes? % "/.github/workflows/")
                             (not (str/starts-with? % ".github/workflows/")))
                       files)
      dependabot (filterv #(#{".github/dependabot.yml" ".github/dependabot.yaml"} %) files)]
  (println (str "SCANNED\t" n "\tsource\t" (if git? "git-ls-files" "walk")
                "\tanchor\t" anchor "\t" (if (some #{anchor} files) "present" "MISSING")))
  (println (str "ROOT-WORKFLOWS\t" (count root-wf)))
  (println (str "NESTED-VENDORED-IGNORED\t" (count nested)))
  (println (str "TRUNCATED-AT-DEPTH\t" @truncated
                (when (pos? @truncated)
                  (str " (depth " max-depth " reached; the counts above are a floor,"
                       " not a total — the verdict below is unaffected because"
                       " .github/workflows sits at depth 2))"))))
  (println (str "ROOT-DEPENDABOT-CONFIG\t" (count dependabot)))
  (println "NOT-CHECKED\tactions/permissions, dependency graph, Dependabot settings"
           "(GitHub-side; needs credentials — scripts/github-actions-billable-audit.cljs owns those)")
  (cond
    (or (< n floor) (not (some #{anchor} files)))
    (do (println (str "Refusing to report a verdict: saw " n " yaml file(s)"
                      (when-not (some #{anchor} files)
                        (str ", and the anchor " anchor " is not in the tree"))
                      ". An empty or over-filtered tree must not read as"
                      " 'this repo has no workflows'."))
        (js/process.exit 2))

    (seq root-wf)
    (do (doseq [f (sort root-wf)] (println (str "VIOLATION\t" f)))
        (println (str "CI belongs to the murakumo fleet (ADR-2607300900)."
                      " A workflow at the repo root is the one input that makes"
                      " GitHub start a runner on its own. Add the check to"
                      " scripts/fleet-ci/gates.edn instead."))
        (js/process.exit 1))

    :else
    (do (when (seq dependabot)
          (println (str "NOTE\t" (first dependabot) " is present. Reported, not"
                        " failed: Dependabot's dynamic runs report"
                        " billable.total_ms = 0, so they are not Actions spend."
                        " Measure before treating this as a cost.")))
        (println "OK\tno workflow at the repo root")
        (js/process.exit 0))))

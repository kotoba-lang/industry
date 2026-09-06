#!/usr/bin/env nbb
;; verify-frontend-stack-retirement.cljs — Svelte / React / Next.js の著述面が
;; 増えていないことの検査（ADR-2608260900 の ratchet）。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-frontend-stack-retirement.cljs [--write-baseline]
;;
;; ## 何を検査するか
;;
;; ADR-2608260900（オーナー指示 2026-08-26）で、UI の著述面から Svelte / React /
;; Next.js を退役させ cljs + reagent + re-frame + jp-go-dds に寄せると決めた。
;; 既存の 1,379 ファイルは既知の負債なので**全部を finding にはしない** ——
;; それをやると毎回 1,379 件が出て、誰も読まないので新規追加が埋もれる。
;;
;; **finding にするのは baseline から増えた repo だけ。** 規則が縛るのは
;; 「新しく書かないこと」なので、検査もそこを見る。合計は evidence 行に出す
;; ので、減っていることは毎回読める。
;;
;; ## 3 つの実測済みの落とし穴（この検査が持っている理由）
;;
;; 1. **`.claude/worktrees/` を除外しないと二重に数える。** 2026-08-26 の実測で
;;    React が 754 件に見えたが、うち 386 件は `etzhayyim/root` の使い捨て
;;    worktree 2 本に**同じ 193 件が複製**されたものだった。使い捨て worktree は
;;    移行対象ではない。
;;
;; 2. **Next.js を `"next"` 依存で数えると取りこぼす。** `gftdcojp/hrse` は
;;    `next.config.mjs` と `src/app` router を持つ紛れもない Next.js app だが、
;;    package.json に `next` を宣言していない（`@clerk/nextjs` 経由）。
;;    だからここでは**設定ファイルと router ディレクトリ**で判定する。
;;
;; 3. **vendored な第三者コードを移行対象に数えない。** smart-contract の
;;    `lib/` 配下に他人の Next.js サイトが入っている。これは我々が書いた UI では
;;    ないので除外するが、**除外は宣言する** —— 黙った除外は、移行が進んだように
;;    見せる。下記 `vendored-prefixes` が正本。
;;
;; ## exit code は三値
;;
;;   0  走査して、baseline から増えた repo は無かった
;;   1  増えた repo があった（FINDING 行を見る）
;;   2  **答えられなかった** —— orgs/ が無い、baseline が読めない、走査結果が
;;      0 件（0 件を clean と読ませない）。これが 0 と区別されることが要点。

(ns verify-frontend-stack-retirement
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]))

(def root (or js/process.env.FLEET_ROOT (.cwd js/process)))
(def baseline-path (path/join root "manifest" "frontend-stack-baseline.edn"))

(def authored-exts #{".svelte" ".tsx" ".jsx"})

;; 除外は宣言する。黙った除外は移行が進んだように見せる。
(def skip-dirs #{"node_modules" ".git" "dist" "build" "out" "target" ".shadow-cljs"})

(defn worktree-path?
  "`.claude/worktrees/` 配下は使い捨て。同じファイルが複数 worktree に複製される。"
  [rel] (str/includes? rel "/.claude/worktrees/"))

(def vendored-prefixes
  ;; 移行対象ではないもの。**除外は必ずここに宣言する** —— 黙った除外は
  ;; 移行が進んだように見せる（CLAUDE.md「no silent caps」）。
  [;; 我々が書いていない第三者コード（smart-contract の lib/ に同梱された他人のサイト）
   "orgs/etzhayyim/root/50-infra/vultr/geth-private/contracts/lib/"
   "orgs/gftdcojp/apps-gftdcojp/50-infra/vultr/geth-private/contracts/lib/"
   ;; m365-archive は OneDrive の履歴アーカイブ（DataLad dataset）であって live な
   ;; コードではない。2026-08-26 の実測で next.config.* 15 件のうち 8 件がここだった。
   ;; 過去の成果物を「移行されていない Next.js app」として数えない。
   "orgs/gftdcojp/m365-archive/"
   ;; llama.cpp の vendored checkout。2026-09-07 の実測で .svelte/.tsx/.jsx が
   ;; 323 件出るが **git は 1 件も追跡していない**（repo の tracked は 406 件で、
   ;; そのどれでもない）。上流 llama.cpp のツール UI であって我々が書いた面ではない。
   ;; ADR-2608260900 が縛るのは我々が著述する面なので、ここは移行対象ではない。
   "orgs/kotoba-lang/com-github-big-moe-on-edge/third_party/"])

(defn vendored? [rel] (some #(str/starts-with? rel %) vendored-prefixes))

;; nbb の同期再帰は 4,700 checkout に対して分オーダーで、2 分で終わらなかった
;; （実測 2026-08-26）。`find(1)` は同じ走査を秒で返すので、歩くのは find に任せ、
;; 判断（除外・集計）だけをここで持つ。find が無い/失敗したら nil を返し、
;; 呼び出し側が exit 2（答えられなかった）にする —— 空配列と区別する。
;; ⚠ find の stdout を execFileSync でパイプ受けすると、この機械では
;; **11.5 秒の走査が 69 秒になる**（実測 2026-08-26、load 100 超）。Node の同期
;; exec が stdout をポーリングで吸うためで、find が遅いのではない。だから
;; **find には 1 回だけ走ってもらい、結果はファイルに落として読む。**
;; 2 パターンを 1 パスで採って cljs 側で振り分ける（2 回呼ぶと 2 分の上限を超える）。
(defn scan!
  "orgs/ を 1 回走査して {:authored [...] :nextcfg [...]} を返す。失敗時は nil。"
  []
  (let [tmp (path/join (or js/process.env.TMPDIR "/tmp")
                       (str "fsr-" (.getTime (js/Date.)) ".txt"))
        args ["orgs"
              "(" "-name" "node_modules" "-o" "-name" ".git"
              "-o" "-name" "dist" "-o" "-name" "build" "-o" "-name" "out"
              "-o" "-name" "target" "-o" "-name" ".shadow-cljs"
              "-o" "-name" ".claude" ")" "-prune" "-o"
              "(" "-name" "*.svelte" "-o" "-name" "*.tsx" "-o" "-name" "*.jsx"
              "-o" "-name" "next.config.*" ")" "-type" "f" "-print"]]
    (try
      (let [fd (fs/openSync tmp "w")]
        (try (cp/execFileSync "find" (clj->js args)
                              #js {:cwd root :stdio #js ["ignore" fd "ignore"]})
             (finally (fs/closeSync fd)))
        (let [rels (->> (str/split-lines (fs/readFileSync tmp "utf8"))
                        (remove str/blank?)
                        (remove #(worktree-path? (str "/" %)))
                        (remove vendored?)
                        vec)]
          (fs/unlinkSync tmp)
          {:authored (vec (remove #(str/includes? % "next.config.") rels))
           :nextcfg  (vec (filter #(str/includes? % "next.config.") rels))}))
      (catch :default _ nil))))

(defn repo-of [rel] (str/join "/" (take 3 (str/split rel #"/"))))

(defn count-by-repo [rels]
  (reduce (fn [m r] (update m (repo-of r) (fnil inc 0))) {} rels))

(defn -main []
  (let [orgs (path/join root "orgs")]
    (when-not (try (.isDirectory (fs/statSync orgs)) (catch :default _ false))
      (println "REFUSING\tno orgs/ directory at" orgs)
      (println "SCANNED\t0")
      (.exit js/process 2))

    (let [scan    (scan!)
          files   (:authored scan)
          nextcfg (:nextcfg scan)]

      ;; find が走らなかった（nil）のと、走って 0 件だったのを混ぜない。
      (when (nil? scan)
        (println "REFUSING\tfind(1) failed — the scan did not run")
        (println "SCANNED\t0")
        (.exit js/process 2))

      ;; evidence floor: 0 件を clean と読ませない。この workspace には
      ;; 移行前の実測で 1,379 件在ることが分かっているので、0 は「走査が壊れた」。
      (when (zero? (count files))
        (println "REFUSING\tscanned 0 authored files — the walk is broken, not the tree")
        (println "SCANNED\t0")
        (.exit js/process 2))

      (let [scanned  (count files)
            now      (count-by-repo files)
            base     (try (edn/read-string (fs/readFileSync baseline-path "utf8"))
                          (catch :default _ nil))
            write?   (some #{"--write-baseline"} (vec (.-argv js/process)))]

        (println (str "SCANNED\t" scanned))
        (println (str "REPOS\t" (count now)))
        (println (str "NEXTJS-CONFIGS\t" (count nextcfg)))
        (doseq [c (sort nextcfg)] (println (str "  nextjs\t" c)))

        (cond
          write?
          (do (fs/writeFileSync baseline-path
                                (str ";; frontend-stack baseline — ADR-2608260900\n"
                                     ";; 生成: nbb scripts/verify-frontend-stack-retirement.cljs --write-baseline\n"
                                     ";; 手で編集しない。移行が進んだら再生成する（減る方向のみ）。\n"
                                     (pr-str {:total scanned :by-repo (into (sorted-map) now)}) "\n"))
              (println (str "WROTE\t" baseline-path "\ttotal=" scanned))
              (.exit js/process 0))

          (nil? base)
          (do (println "REFUSING\tno readable baseline at" baseline-path
                       "— run with --write-baseline first")
              (.exit js/process 2))

          :else
          (let [b (:by-repo base)
                grew (->> now
                          (keep (fn [[repo n]]
                                  (let [was (get b repo 0)]
                                    (when (> n was) [repo was n]))))
                          (sort-by first))]
            (println (str "BASELINE-TOTAL\t" (:total base) "\tNOW\t" scanned
                          "\tDELTA\t" (- scanned (:total base))))
            (doseq [[repo was n] grew]
              (println (str "FINDING\twarn\tgrew:" repo
                            "\t" repo ": authored frontend files " was " -> " n
                            " (ADR-2608260900: new .svelte/.tsx/.jsx are not written)")))
            (if (seq grew)
              (.exit js/process 1)
              (do (println "OK\tno repo grew its Svelte/React surface") (.exit js/process 0)))))))))

(-main)

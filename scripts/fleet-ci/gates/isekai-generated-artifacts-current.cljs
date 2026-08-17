#!/usr/bin/env nbb
;; isekai-generated-artifacts-current.cljs — network-isekai の checked-in 生成物が
;; source と一致しているか。
;;
;; この repo は「生成して checked in する」artifact を 2 つ持っている。どちらも
;; **deploy に載る**ので、request path で作り直す余地が無い:
;;
;;   functions/_lib/social-cards.mjs        per-game の Open Graph / X card copy。
;;                                          source は public/games/*/*/game.edn（ADR-0080）
;;   functions/api/_lib/content-ratings-catalog.mjs
;;                                          年齢レーティング catalog。source は
;;                                          resources/content-ratings.edn（ADR-0072 §5）。
;;                                          LOBBY_RATING_ENFORCE を on にしておける根拠
;;
;; **stale になっても何も落ちない**のが問題である。card は古いゲーム名を出し続け、
;; rating catalog は新しいゲームを知らないまま enforce する。どちらも「静かに嘘になる」
;; 種類の壊れ方で、生成器はどちらも `--check` を持っているのに、それを回す者が居なかった
;; （実測 2026-08-17: この repo の Actions は無効、最終 run は 2026-08-04 で failure、
;; fleet-ci の 149 gate に network-isekai は 0 本）。
;;
;; 生成ロジックを**ここに複製しない**。repo 自身の `--check` を回す —— 検査と生成が
;; 2 箇所に分かれた瞬間、gate が緑のまま artifact がずれる経路ができる。
;;
;; ノード側: npx nbb isekai-generated-artifacts-current.cljs <dir>
;;           <dir> が第1引数（tick.cljs の :nbb-script はそう渡す）。

(ns fleet-ci.gates.isekai-generated-artifacts-current
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

(def checks
  [{:label "social-cards"
    :script "scripts/isekai/social_catalog.cljs"
    :artifact "functions/_lib/social-cards.mjs"
    :source "public/games/*/*/game.edn"}
   {:label "content-ratings"
    :script "scripts/isekai/content_ratings.cljs"
    :artifact "functions/api/_lib/content-ratings-catalog.mjs"
    :source "resources/content-ratings.edn"}])

(defn- spawn-failed?
  "Did the launcher fail to reach the script, rather than the script reporting a verdict?

   `npx` resolves its own arguments before handing anything to the package, and on some
   installs (pnpm's npx, measured on this workstation 2026-08-17) it decides the .cljs path
   IS the command and dies with `spawn … EACCES`. That output is a broken harness, not a
   stale artifact, and it must not be reported as one."
  [out]
  (boolean (re-find #"(?i)EACCES|ENOENT|command not found|could not determine executable" (str out))))

(defn- run-nbb
  "Run a repo script under nbb, preferring a real `nbb` on PATH over `npx --yes nbb`.

   The fleet's node capability probe checks for `npx`, not `nbb`, so `npx` is the portable
   path and has to stay the fallback. But where `nbb` is installed directly it avoids npx's
   argument handling entirely, which is the thing that breaks."
  [script]
  (let [direct (cp/spawnSync "nbb" #js[script "--check"]
                             #js{:cwd root :encoding "utf8"})]
    (if (and (some? (.-status direct))
             (not (spawn-failed? (str (or (.-stdout direct) "") (or (.-stderr direct) "")))))
      direct
      (cp/spawnSync "npx" #js["--yes" "nbb" script "--check"]
                    #js{:cwd root :encoding "utf8"}))))

(defn- run-check [{:keys [label script artifact]}]
  (let [script-path (path/join root script)
        artifact-path (path/join root artifact)]
    (cond
      ;; A generator that is not in the shipped tree means `:include-ext` dropped it. Saying
      ;; "nothing to check, all good" there is the exact false pass this file exists against.
      (not (fs/existsSync script-path))
      {:label label :status :unanswerable
       :detail (str "generator missing from the tree: " script
                    " — :include-ext dropped it, refusing to report a pass")}

      (not (fs/existsSync artifact-path))
      {:label label :status :unanswerable
       :detail (str "artifact missing from the tree: " artifact
                    " — :include-ext dropped it, refusing to report a pass")}

      :else
      (let [r (run-nbb script)
            out (str/trim (str (or (.-stdout r) "") (or (.-stderr r) "")))
            code (.-status r)]
        (cond
          ;; The runner itself failed to start, or started and could not load the script.
          ;; That is NOT staleness. Conflating them is how a broken harness reports a broken
          ;; repo — the first version of this file did exactly that, calling an `EACCES` from
          ;; the launcher "the artifact does not match its source".
          (or (nil? code) (spawn-failed? out))
          {:label label :status :unanswerable
           :detail (str "could not run " script ": "
                        (or (some-> (.-error r) .-message) (first (str/split-lines out)) "unknown"))}

          (zero? code) {:label label :status :current :detail out}
          :else {:label label :status :stale :detail out})))))

(when-not (fs/existsSync root)
  (println "FLEET-CI: root does not exist:" root "— refusing to report a pass")
  (js/process.exit 90))

(let [results (mapv run-check checks)]
  ;; Every check names its own outcome on its own line, so "did not run" and "was fine" are
  ;; never the same output.
  (println (str "CHECKED\t" (count results) " generated artifact(s)"))
  (doseq [{:keys [label status detail]} results]
    (println (str "  " (str/upper-case (name status)) "\t" label
                  (when (seq detail) (str " — " (first (str/split-lines detail)))))))

  (let [unanswerable (filterv #(= :unanswerable (:status %)) results)
        stale (filterv #(= :stale (:status %)) results)]
    (cond
      (seq unanswerable)
      (do (println "FLEET-CI: could not answer for"
                   (str/join ", " (map :label unanswerable))
                   "— this is neither a pass nor a failure of the repo")
          (js/process.exit 90))

      (seq stale)
      (do (println "FLEET-CI FAIL:" (str/join ", " (map :label stale))
                   "— the checked-in artifact does not match its source."
                   "It ships with the deploy, so a stale one is served, not regenerated.")
          (doseq [{:keys [label detail]} stale]
            (println (str "--- " label " ---"))
            (println detail))
          (js/process.exit 1))

      :else
      (do (println "FLEET-CI OK: every checked-in generated artifact matches its source")
          (js/process.exit 0)))))

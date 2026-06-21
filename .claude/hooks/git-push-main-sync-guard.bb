#!/usr/bin/env bb
;; PreToolUse(Bash) ガード: git push 前に対象リポが origin/main（既定ブランチ）より
;; 遅れていれば push をブロックし、先に同期するよう指示する。
;;
;; 方針 (CLAUDE.md): 常に main と同期し、乖離を作らない。
;; フック自体は破壊的な自動マージをしない（deny + 指示のみ）。
;; fail-open: 判定途中のあらゆる失敗時は push を許可する（誤ブロック防止）。

(require '[cheshire.core :as json]
         '[babashka.process :as p]
         '[clojure.string :as str])

(defn git
  "git -C dir <args...> を実行し、成功時は trim した stdout を、失敗時は nil を返す。"
  [dir & args]
  (try
    (let [{:keys [exit out]} (p/sh (into ["git" "-C" dir] (map str args)))]
      (when (zero? exit) (str/trim out)))
    (catch Throwable _ nil)))

(defn allow! [] (System/exit 0))

(defn deny! [reason]
  (println (json/generate-string
             {:hookSpecificOutput
              {:hookEventName "PreToolUse"
               :permissionDecision "deny"
               :permissionDecisionReason reason}}))
  (System/exit 0))

(try
  (let [cmd (or (some-> (slurp *in*)
                        (json/parse-string true)
                        (get-in [:tool_input :command]))
                "")]
    ;; git push を含むコマンドのみ対象。--dry-run は実 push でないので素通り。
    (when-not (str/includes? cmd "git push") (allow!))
    (when (str/includes? cmd "--dry-run") (allow!))

    ;; 対象リポ dir: コマンド中の `cd <path>` を尊重、無ければ cwd。
    (let [m   (re-find #"cd\s+([^\s;&|\"']+)" cmd)
          dir (if m (second m) ".")
          top (git dir "rev-parse" "--show-toplevel")]
      (when (str/blank? top) (allow!))

      ;; 比較先: origin/main があれば優先、無ければ origin/HEAD の指す既定ブランチ。
      (let [ref (if (git top "rev-parse" "--verify" "-q" "origin/main")
                  "origin/main"
                  (some-> (git top "symbolic-ref" "-q" "refs/remotes/origin/HEAD")
                          (str/replace #"^refs/remotes/" "")))]
        (when (str/blank? ref) (allow!))
        (let [branch (str/replace ref #"^origin/" "")]
          ;; 比較ブランチだけ fetch（best-effort, 全体 fetch を避け高速化）。
          (git top "fetch" "-q" "origin" branch)
          (let [behind (try (-> (git top "rev-list" "--count" (str "HEAD.." ref))
                                str/trim Integer/parseInt)
                            (catch Throwable _ 0))]
            (when (pos? behind)
              (deny!
                (format (str "%s is behind %s by %d commits. Sync %s before pushing, "
                             "then retry. Run: git fetch origin && git merge --ff-only %s "
                             "(if FF fails, merge or rebase to resolve divergence). "
                             "Policy (CLAUDE.md): always stay synced with main, never create divergence.")
                        top ref behind ref ref)))))))
    (allow!))
  (catch Throwable _ (System/exit 0)))

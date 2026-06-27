#!/usr/bin/env bb
;; PreToolUse(Bash) ガード: 既定ブランチ(main)を push しようとする時に限り、対象リポが
;; origin/main より遅れていれば push をブロックし、先に同期するよう指示する。
;;
;; 方針 (CLAUDE.md): 常に main と同期し、main に乖離を作らない。
;;   - main(既定ブランチ)への push が遅れている → deny(従来どおり)。
;;   - feature / reconcile 等の「main 以外のブランチ」への push は許可する。
;;     （PR ワークフローでは main より遅れた作業ブランチを push するのが普通で、
;;       これを塞ぐと未 PR 作業をバックアップ/レビューに出せない。）
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

(defn strip-ref [s] (-> s (str/replace #"^refs/heads/" "") (str/replace #"^refs/" "")))

(defn pushed-dst
  "push コマンドから「更新先ブランチ名」を推定する。refspec が無ければ dir の現在ブランチ。
   <src>:<dst> は dst 側、HEAD:refs/heads/x は x。判定不能なら nil。"
  [cmd dir]
  (let [after (second (re-find #"git\s+push\b(.*)$" cmd))
        toks  (->> (str/split (or after "") #"\s+")
                   (remove str/blank?)
                   (remove #(str/starts-with? % "-")))   ; フラグ除去(origin/branch だけ残す)
        ;; toks 例: ["origin" "reconcile/x"] / ["origin" "HEAD:refs/heads/x"] / []
        refspec (second toks)]
    (cond
      (str/blank? refspec) (some-> (git dir "rev-parse" "--abbrev-ref" "HEAD") str/trim)
      (str/includes? refspec ":") (strip-ref (last (str/split refspec #":" 2)))
      :else (strip-ref refspec))))

(try
  (let [cmd (or (some-> (slurp *in*)
                        (json/parse-string true)
                        (get-in [:tool_input :command]))
                "")]
    ;; git push を含むコマンドのみ対象。--dry-run / --delete は対象外で素通り。
    (when-not (str/includes? cmd "git push") (allow!))
    (when (str/includes? cmd "--dry-run") (allow!))
    (when (or (str/includes? cmd "--delete") (re-find #"\spush\b[^|;&]*\s-d\b" cmd)) (allow!))

    ;; 対象リポ dir: `git -C <path>` を優先、無ければ `cd <path>`、それも無ければ cwd。
    (let [cdir (some-> (re-find #"git\s+-C\s+([^\s;&|\"']+)" cmd) second)
          cd   (some-> (re-find #"cd\s+([^\s;&|\"']+)" cmd) second)
          dir  (or cdir cd ".")
          top  (git dir "rev-parse" "--show-toplevel")]
      (when (str/blank? top) (allow!))

      ;; 比較先: origin/main があれば優先、無ければ origin/HEAD の指す既定ブランチ。
      (let [ref (if (git top "rev-parse" "--verify" "-q" "origin/main")
                  "origin/main"
                  (some-> (git top "symbolic-ref" "-q" "refs/remotes/origin/HEAD")
                          (str/replace #"^refs/remotes/" "")))]
        (when (str/blank? ref) (allow!))
        (let [branch (str/replace ref #"^origin/" "")
              dst    (pushed-dst cmd dir)]
          ;; 既定ブランチ(main)への push でなければ許可する。
          (when (not= dst branch) (allow!))
          ;; main への push のみ、遅れていれば deny。
          (git top "fetch" "-q" "origin" branch)
          (let [behind (try (-> (git top "rev-list" "--count" (str "HEAD.." ref))
                                str/trim Integer/parseInt)
                            (catch Throwable _ 0))]
            (when (pos? behind)
              (deny!
                (format (str "%s: %s への push が %s より %d commits 遅れています。先に同期してから push してください。"
                             "Run: git fetch origin && git merge --ff-only %s "
                             "(FF 不可なら merge / rebase で乖離を解消)。"
                             "Policy (CLAUDE.md): main に乖離を作らない。"
                             "(feature/reconcile ブランチへの push はブロックしません)")
                        top branch ref behind ref)))))))
    (allow!))
  (catch Throwable _ (System/exit 0)))

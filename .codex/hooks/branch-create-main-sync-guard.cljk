#!/usr/bin/env nbb
;; PreToolUse(Bash) ガード: 分岐を作る操作の「前」に、対象リポが origin/<default> より
;; 遅れていればブロックし、先に同期するよう指示する。
;;
;; 対象: git worktree add / git checkout -b / git switch -c / git branch <new>
;;
;; 方針 (CLAUDE.md「分岐を作る前に、必ず local を remote に同期する」):
;; 遅れた base の上に積んだ commit は、後から同期しても遅れたまま。その worktree の作業
;; 全部が古い base に載り、着地時に乖離・conflict・pin 退行として現れる。push 直前の
;; 同期では手遅れで、救うには rebase(禁止) か clean branch への移植が要る。
;; 同期のコストは分岐前なら fetch 1回、分岐後なら作業のやり直し — この非対称性が、
;; push guard とは別にこのガードが要る理由。
;;
;; **分岐元を origin/<default> で明示していればブロックしない。** それが推奨形であり
;; (`git worktree add -b x /tmp/y origin/main`)、ローカルの遅れと無関係に正しい base に
;; なる。ローカル HEAD から暗黙に分岐する形だけを止める。
;;
;; フック自体は破壊的な自動同期をしない(deny + 指示のみ)。
;; fail-open: 判定途中のあらゆる失敗時は許可する(誤ブロック防止)。

(require '[cheshire.core :as json]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[scripts.nbb-compat :as compat])

(defn git
  "git -C dir <args...> を実行し、成功時は trim した stdout を、失敗時は nil を返す。"
  [dir & args]
  (try
    (let [{:keys [exit out]} (p/sh (into ["git" "-C" dir] (map str args)))]
      (when (zero? exit) (str/trim out)))
    (catch :default _ nil)))

(defn allow! [] (compat/exit 0))

(defn deny! [reason]
  (println (json/generate-string
             {:hookSpecificOutput
              {:hookEventName "PreToolUse"
               :permissionDecision "deny"
               :permissionDecisionReason reason}}))
  (compat/exit 0))

;; git-push-main-sync-guard.cljs と同じパストークン定義。クォート付きパスを
;; 裸トークン用の文字クラスだけで拾うと先頭の `"` で即マッチ失敗し、無関係な
;; cwd の同期状態を見る誤検出になる(実例 2026-07-02)。
(def ^:private path-tok-src "\"[^\"]*\"|'[^']*'|[^\\s;&|]+")

(defn- strip-quotes [s]
  (when s
    (if (and (>= (count s) 2)
             (or (and (str/starts-with? s "\"") (str/ends-with? s "\""))
                 (and (str/starts-with? s "'") (str/ends-with? s "'"))))
      (subs s 1 (dec (count s)))
      s)))

(def ^:private branch-create-re
  "分岐を作る形。`-C <path>` が git と subcommand の間に挟まる形も拾う
   (push guard がこれを取りこぼしてガードごと素通りしていた前例がある)。"
  (re-pattern
   (str "git\\s+(?:-C\\s+(?:" path-tok-src ")\\s+)?"
        "(?:worktree\\s+add\\b"
        "|checkout\\s+(?:[^|;&]*\\s)?-[bB]\\b"
        "|switch\\s+(?:[^|;&]*\\s)?-[cC]\\b"
        "|branch\\s+(?!-[dDlvam])[^\\s-][^\\s]*)")))

(try
  (let [cmd (or (some-> (compat/read-stdin)
                        (json/parse-string true)
                        (get-in [:tool_input :command]))
                "")]
    (when-not (re-find branch-create-re cmd) (allow!))

    (let [cdir (some-> (re-find (re-pattern (str "git\\s+-C\\s+(" path-tok-src ")")) cmd)
                       second strip-quotes)
          cd   (some-> (re-find (re-pattern (str "cd\\s+(" path-tok-src ")")) cmd)
                       second strip-quotes)
          dir  (or cdir cd ".")
          top  (git dir "rev-parse" "--show-toplevel")]
      (when (str/blank? top) (allow!))

      (let [ref (if (git top "rev-parse" "--verify" "-q" "origin/main")
                  "origin/main"
                  (some-> (git top "symbolic-ref" "-q" "refs/remotes/origin/HEAD")
                          (str/replace #"^refs/remotes/" "")))]
        (when (str/blank? ref) (allow!))

        ;; 分岐元を origin/<default> で明示していれば、ローカルが遅れていても
        ;; base は正しい。推奨形なのでブロックしない。
        (when (str/includes? cmd ref) (allow!))

        (let [branch (str/replace ref #"^origin/" "")]
          (git top "fetch" "-q" "origin" branch)
          (let [raw    (git top "rev-list" "--count" (str "HEAD.." ref))
                parsed (js/parseInt (or raw "0") 10)
                behind (if (js/isNaN parsed) 0 parsed)]
            (when (pos? behind)
              (deny!
               (compat/format
                (str "%s: 分岐を作ろうとしていますが、この checkout は %s より %d commits "
                     "遅れています。遅れた base の上に積んだ commit は後から同期しても "
                     "遅れたままで、着地時に乖離・pin 退行になります。"
                     "先に同期するか: git fetch origin && git merge --ff-only %s "
                     "(FF 不可なら停止。rebase しない) — "
                     "あるいは分岐元を明示: git worktree add -b <branch> <path> %s。"
                     "Policy (CLAUDE.md): 分岐を作る前に、必ず local を remote に同期する。")
                top ref behind ref ref)))))))
    (allow!))
  (catch :default _ (compat/exit 0)))

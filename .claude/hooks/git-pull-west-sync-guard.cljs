#!/usr/bin/env nbb
;; PreToolUse(Bash) ガード: west manifest 管理下の superproject（.west/ または
;; manifest/west.yml を持つ repo）で plain `git pull` を実行しようとした時に deny する。
;;
;; 理由 (CLAUDE.md 「Git operations」): このリポジトリの正しい同期手順は
;;   git fetch origin && git merge --ff-only origin/main && west update --fetch smart
;; の3ステップで、子リポの pin を west update で追従させる。
;; `git pull`(単体)はこれを満たさない(west update が走らず子リポの pin が
;; manifest と食い違ったままになる)ため、実行を許さず代替コマンドを提示する。
;;
;; west 管理下でない repo（.west/ も manifest/west.yml も無い）は対象外で素通りさせる
;; （この規約は superproject 固有であり、無関係な repo の plain pull を妨げない）。
;; fail-open: 判定不能なあらゆる失敗時は許可する。

(require '[cheshire.core :as json]
         '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[scripts.nbb-compat :as compat])

(defn- run [& args]
  (try (let [{:keys [exit out]} (p/sh (mapv str args))]
         (when (zero? exit) (str/trim out)))
       (catch :default _ nil)))

(defn git [dir & args] (apply run "git" "-C" dir args))

(defn allow! [] (compat/exit 0))

(defn deny! [reason]
  (println (json/generate-string
             {:hookSpecificOutput
              {:hookEventName "PreToolUse"
               :permissionDecision "deny"
               :permissionDecisionReason reason}}))
  (compat/exit 0))

(def ^:private path-tok-src "\"[^\"]*\"|'[^']*'|[^\\s;&|]+")

(defn- strip-quotes [s]
  (when s
    (if (and (>= (count s) 2)
             (or (and (str/starts-with? s "\"") (str/ends-with? s "\""))
                 (and (str/starts-with? s "'") (str/ends-with? s "'"))))
      (subs s 1 (dec (count s)))
      s)))

;; `git -C <path> pull` の -C も拾う（他ガードと同じ取りこぼし対策）。
(def ^:private git-pull-re
  (re-pattern (str "git\\s+(?:-C\\s+(?:" path-tok-src ")\\s+)?pull\\b")))

(try
  (let [cmd (or (some-> (compat/read-stdin)
                        (json/parse-string true)
                        (get-in [:tool_input :command]))
                "")]
    (when-not (re-find git-pull-re cmd) (allow!))

    (let [cdir (some-> (re-find (re-pattern (str "git\\s+-C\\s+(" path-tok-src ")")) cmd)
                        second strip-quotes)
          cd   (some-> (re-find (re-pattern (str "cd\\s+(" path-tok-src ")")) cmd)
                        second strip-quotes)
          dir  (or cdir cd ".")
          top  (git dir "rev-parse" "--show-toplevel")]
      (when (str/blank? top) (allow!))

      ;; west 管理下（.west/ か manifest/west.yml）でなければ対象外。
      (when-not (or (fs/exists? (fs/path top ".west"))
                    (fs/exists? (fs/path top "manifest" "west.yml")))
        (allow!))

      (deny!
        (str "west manifest 管理下のこの repo では plain `git pull` を使わないでください "
             "(CLAUDE.md: Git operations)。west update を経由しないと子リポの pin が manifest と "
             "食い違ったままになります。代わりに次を実行してください:\n\n"
             "  git fetch origin && git merge --ff-only origin/main "
             "&& west update --fetch smart\n\n"
             "(FF 不可なら乖離あり。rebase せず、乖離解消の方針は CLAUDE.md を参照。)"))))
  (catch :default _ (compat/exit 0)))

#!/usr/bin/env nbb
;; PreToolUse(Bash) ガード: 既定ブランチ(main)を push しようとする時に限り、対象リポが
;; origin/main より遅れていれば push をブロックし、先に同期するよう指示する。
;;
;; 方針 (CLAUDE.md): 常に main と同期し、main に乖離を作らない。
;;   - main(既定ブランチ)への push が遅れている → deny(従来どおり)。
;;   - feature / reconcile 等の「main 以外のブランチ」への push は許可する。
;;     （PR ワークフローでは main より遅れた作業ブランチを push するのが普通で、
;;       これを塞ぐと未 PR 作業をバックアップ/レビューに出せない。）
;; フック自体は破壊的な自動マージをしない(deny + 指示のみ)。
;; fail-open: 判定途中のあらゆる失敗時は push を許可する(誤ブロック防止)。

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

(defn upstream-ref
  "この checkout の『上流の既定ブランチ』を `<remote>/<branch>` で返す。解決
   できなければ nil。

   **remote は `origin` とは限らない。** west が作る checkout は remote を org 名
   で持つ（`network-awai` / `cloud-itonami` …）。実測 2026-08-13、`orgs/` 配下の
   4,406 checkout のうち **2,824（64%）に `origin` remote が無い**。

   それまでこの解決は `origin/main` → `refs/remotes/origin/HEAD` の 2 段で、
   どちらも解決できなければ `(allow!)` していた。つまり**ワークスペースの
   3 分の 2 の子リポで、遅れた main への push が黙って素通りしていた。**

   remote が複数あるときは **URL に `github.com` を含むものを選ぶ**。
   `git remote | head -1` はアルファベット順の先頭を返すので、annex repo では
   `b2`（special remote）を選んでしまう —— この誤りはこの workspace で
   3 回起きている。実測 2026-08-13 の fixture では、`b2` を選ぶと
   「0 behind」、正しい remote を選ぶと「1 behind」で判定が逆になった。

   `wrangler-deploy-main-sync-guard.cljs` / `branch-create-main-sync-guard.cljs`
   と同一の実装。**3 本目なので、次に 4 本目が要るときは共有 ns へ抽出する**
   （今インライン置きなのは、これらの hook が settings.json から `--classpath`
   無しで起動され、require 失敗が try/catch の外で hook ごと落とすため）。"
  [top]
  (let [remotes (->> (or (git top "remote") "") str/split-lines
                     (map str/trim) (remove str/blank?) vec)
        gh? (fn [r] (some-> (git top "remote" "get-url" r) (str/includes? "github.com")))
        ordered (concat (filter #{"origin"} remotes)
                        (filter gh? (remove #{"origin"} remotes))
                        (remove #{"origin"} remotes))]
    (some (fn [r]
            (or (when (git top "rev-parse" "--verify" "-q" (str r "/main")) (str r "/main"))
                (some-> (git top "symbolic-ref" "-q" (str "refs/remotes/" r "/HEAD"))
                        (str/replace #"^refs/remotes/" ""))))
          ordered)))

(defn deny! [reason]
  (println (json/generate-string
             {:hookSpecificOutput
              {:hookEventName "PreToolUse"
               :permissionDecision "deny"
               :permissionDecisionReason reason}}))
  (compat/exit 0))

(defn strip-ref [s] (-> s (str/replace #"^refs/heads/" "") (str/replace #"^refs/" "")))

;; パス1トークン: "..." / '...' / スペース・;&| を含まない裸トークン。
;; クォート付きパス（`cd "$SCRATCH/x"` 等）を裸トークン用の文字クラスだけで拾おうとすると、
;; 先頭の `"` がクラス除外文字に当たって即マッチ失敗し、`dir` が "."(=判定不能な cwd) に
;; フォールバックする。その結果、対象と無関係な repo（このスクリプト自身の cwd）の同期状態を
;; 見てしまう誤検出が起きる（実例: 2026-07-02, `git -C "$SCRATCH/repos/map-live" push` を
;; superproject の乖離ありと誤判定）。クォート付き/裸の両方を候補にして拾う。
(def ^:private path-tok-src "\"[^\"]*\"|'[^']*'|[^\\s;&|]+")

(defn- strip-quotes [s]
  (when s
    (if (and (>= (count s) 2)
             (or (and (str/starts-with? s "\"") (str/ends-with? s "\""))
                 (and (str/starts-with? s "'") (str/ends-with? s "'"))))
      (subs s 1 (dec (count s)))
      s)))

;; トップレベルの対象判定に生の (str/includes? cmd "git push") を使うと、
;; `git -C <path> push ...` のように git と push の間に -C 引数が挟まる形を
;; 取りこぼし（"git push" という隣接部分文字列が存在しないため）、ガードが
;; まるごと素通りしてしまう。`git -C <path> push` も明示的に対象に含める。
(def ^:private git-push-re
  (re-pattern (str "git\\s+(?:-C\\s+(?:" path-tok-src ")\\s+)?push\\b")))

(def ^:private git-push-tail-re
  "`push` 以降の引数列を丸ごと取り出す。`git-push-re` と同じく `-C <path>` を
   git と push の間に許す（`pushed-dst` も同じ取りこぼしを持っていた:
   `git -C <path> push origin <branch>` で refspec を拾えず、常に dir の
   現在ブランチにフォールバックして every push looked like a main-push）。"
  (re-pattern (str "git\\s+(?:-C\\s+(?:" path-tok-src ")\\s+)?push\\b(.*)$")))

(defn pushed-dst
  "push コマンドから「更新先ブランチ名」を推定する。refspec が無ければ dir の現在ブランチ。
   <src>:<dst> は dst 側、HEAD:refs/heads/x は x。判定不能なら nil。"
  [cmd dir]
  (let [after (second (re-find git-push-tail-re cmd))
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
  (let [cmd (or (some-> (compat/read-stdin)
                        (json/parse-string true)
                        (get-in [:tool_input :command]))
                "")]
    ;; git push (`git -C <path> push` 含む) を含むコマンドのみ対象。--dry-run / --delete は対象外で素通り。
    (when-not (re-find git-push-re cmd) (allow!))
    (when (str/includes? cmd "--dry-run") (allow!))
    (when (or (str/includes? cmd "--delete") (re-find #"\spush\b[^|;&]*\s-d\b" cmd)) (allow!))

    ;; 対象リポ dir: `git -C <path>` を優先、無ければ `cd <path>`、それも無ければ cwd。
    ;; <path> はクォート付き("...` / '...') でも裸トークンでも拾う（strip-quotes で正規化）。
    (let [cdir (some-> (re-find (re-pattern (str "git\\s+-C\\s+(" path-tok-src ")")) cmd)
                        second strip-quotes)
          cd   (some-> (re-find (re-pattern (str "cd\\s+(" path-tok-src ")")) cmd)
                        second strip-quotes)
          dir  (or cdir cd ".")
          top  (git dir "rev-parse" "--show-toplevel")]
      (when (str/blank? top) (allow!))

      ;; 比較先: remote 名は origin とは限らない（upstream-ref の docstring 参照）。
      (let [ref (upstream-ref top)]
        (when (str/blank? ref)
          ;; **黙って通さない。** ここに来るのは「遅れていない」ではなく
          ;; 「判定できなかった」であり、両者を同じ無言の exit 0 で表すと、
          ;; ガードが評価しなかったことが外から見えない。
          (js/console.error
           (str "git-push-main-sync-guard: " top
                " の upstream ref を解決できませんでした（remote: "
                (or (git top "remote") "なし")
                "）。**この push は検査されていません。**"))
          (allow!))
        (let [[remote branch] (str/split ref #"/" 2)
              dst    (pushed-dst cmd dir)]
          ;; 既定ブランチ(main)への push でなければ許可する。
          (when (not= dst branch) (allow!))
          ;; main への push のみ、遅れていれば deny。
          (git top "fetch" "-q" remote branch)
          (let [raw    (git top "rev-list" "--count" (str "HEAD.." ref))
                parsed (js/parseInt (or raw "0") 10)
                behind (if (js/isNaN parsed) 0 parsed)]
            (when (pos? behind)
              (deny!
                (compat/format (str "%s: %s への push が %s より %d commits 遅れています。先に同期してから push してください。"
                                    "Run: git fetch %s && git merge --ff-only %s "
                                    "(FF 不可なら乖離。rebase しない — CLAUDE.md の方針に従って解消)。"
                                    "Policy (CLAUDE.md): main に乖離を作らない。"
                                    "(feature/reconcile ブランチへの push はブロックしません)")
                               top branch ref behind remote ref)))))))
    (allow!))
  (catch :default _ (compat/exit 0)))

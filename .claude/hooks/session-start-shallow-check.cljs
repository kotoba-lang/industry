#!/usr/bin/env nbb
;; SessionStart フック: この checkout(superproject root)と、その配下の
;; west project checkout が shallow になっていないかを毎セッション確認する。
;;
;; 背景 (ADR-2608124400): shallow は 2026-07-21 に撤回された(ADR-2607211600)
;; はずだったが、実際には superproject root 自身と 11 個の子リポが shallow の
;; まま残り、root は 2026-08-01〜08-11 のどこかで `--depth N` fetch により
;; **再び** shallow 化された。**10 日以上、誰も気づかなかった。**
;;
;; なぜ気づけなかったか、そしてなぜ「検知」がこの hook でなければならないか:
;;
;;   1. **症状が出ない。** shallow な clone は `merge-base` /
;;      `--is-ancestor` / `--contains` に **誤った答えを、正しい答えと同じ顔で**
;;      返す。ADR-2608124000 の調査は「その commit は stale な side branch から
;;      しか到達できない」と結論したが、実際には `main` の 643 commit 前に居た。
;;      CLAUDE.md は「full 履歴が既定になったのでローカルの ancestry 判定を
;;      信用してよい」と書いている —— **full 履歴が実在する場所でのみ正しい。**
;;      frontier が ~12,000 commit も深いと、日常の merge-base 作業には
;;      一切ひっかからない。だから 10 日間見えなかった。
;;   2. **痕跡が残らない。** git は fetch の引数を reflog message に残すが、
;;      **ref を動かさない depth fetch は reflog に何も書かない。**
;;      事後に「誰がやったか」を追う経路は原理的に無い。したがって
;;      **状態を毎回見る**以外に方法が無い。
;;   3. **linked worktree は `.git/shallow` を共有する。** linked worktree の
;;      `$GIT_COMMON_DIR` は元 repo の `.git` なので、`/tmp` の使い捨て
;;      worktree の中で `--depth` fetch すると **superproject 本体が shallow に
;;      なる。** このワークスペースは agent に worktree 作業を指示しており、
;;      root には現在 4 つの linked worktree がある。worktree は working tree
;;      の隔離であって、object store と shallow file の隔離ではない。
;;
;; **なぜ PreToolUse の禁止ではなく SessionStart の検知なのか。** ADR は
;; 犯人の command を特定できず、「committed な tooling に起因しない、
;; 一回限りの ad-hoc command と整合的」と結論した。PreToolUse hook が見るのは
;; **今このセッションの tool call だけ**で、別セッション・launchd job・手打ちの
;; シェル・サードパーティツールは素通りする。禁止は原因の一部しか塞げないが、
;; 検知は **原因が何であれ結果を捕まえる。** 実際に起きた事象は「原因不明の
;; 一回限りの fetch」なので、検知の方が事実に合っている。
;;
;; カバー範囲(正直に): この checkout の common dir と `orgs/<org>/<repo>` の
;; 全 checkout。`.git` がディレクトリの場合と `gitdir:` ポインタファイルの場合の
;; 両方を解決する。checkout を持たない orphan gitdir は見ない(ADR いわく
;; checkout が無ければ ancestry の答えも出てこないので、実害が無い)。
;;
;; fail-open: 判定途中のあらゆる失敗はセッション開始をブロックしない
;; (何も出力せず exit 0)。sweep が予算を超えたら、そこまでの結果を
;; 「部分的である」と明示して報告する —— 黙って全件走査したふりをしない。
;;
;; 手動実行 / テスト: `nbb .claude/hooks/session-start-shallow-check.cljs [<dir>]`
;; (<dir> 省略時は CLAUDE_PROJECT_DIR、それも無ければ カレント)。

(require '[cheshire.core :as json]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[scripts.nbb-compat :as compat])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))

;; 子リポ sweep の時間予算。超えたら打ち切って「部分的」と申告する。
;; 実測(2026-08-12, 4,414 checkout): 約 1.2 秒。予算はその 6 倍。
(def sweep-budget-ms 8000)

;; 報告に列挙する子リポの上限(全部並べるとセッション冒頭が埋まる)。
(def max-listed 8)

(defn git
  [dir & args]
  (try
    (let [{:keys [exit out]} (p/sh (into ["git" "-C" dir] (map str args)))]
      (when (zero? exit) (str/trim out)))
    (catch :default _ nil)))

(defn exists? [p] (try (.existsSync fs p) (catch :default _ false)))

(defn graft-count
  "shallow ファイルの graft 行数。**ファイルの存在ではなく graft 数で判定する。**

   `git rev-parse --is-shallow-repository` は *ファイルの存在だけ* を見ており、
   **中身が空の shallow ファイルに対しても `true` を返す**(git 2.50.1 で実測。
   shallow.c の `is_repository_shallow()` は fopen 成功時点で is_shallow=1 を立て、
   1 行も読まないうちに確定させるため)。空ファイルの repo は graft が 0 なので
   履歴は完全で、`merge-base --is-ancestor` も距離も正しく答える —— つまり
   git のフラグはここで **false positive** になる。

   この hook が答えたい問いは「このフラグが立っているか」ではなく
   **「この checkout は ancestry に嘘をつくか」** なので、graft 数を見る。
   誤検知する guard は無視されるようになり、無視される guard は何も守らない。"
  [shallow-file]
  (try
    (->> (str/split-lines (.readFileSync fs shallow-file "utf8"))
         (remove str/blank?)
         count)
    (catch :default _ 0)))

(defn common-dir
  "checkout の $GIT_COMMON_DIR を絶対パスで返す。linked worktree から呼んでも
   元 repo の .git を指す —— それがまさに ADR の危険経路なので、ここを見る。"
  [dir]
  (when-let [cd (git dir "rev-parse" "--git-common-dir")]
    (when-not (str/blank? cd)
      (.resolve path dir cd))))

(defn resolve-gitdir
  "checkout パスから、その repo の gitdir を返す。`.git` がディレクトリなら
   それ自身、`gitdir: <path>` のポインタファイルなら解決先(相対も絶対も可)。"
  [checkout]
  (let [g (.join path checkout ".git")]
    (try
      (let [st (.lstatSync fs g)]
        (cond
          (.isDirectory st) g
          (.isFile st)
          (let [txt (.readFileSync fs g "utf8")
                m   (re-find #"(?m)^gitdir:\s*(.+)$" txt)]
            (when m (.resolve path (.dirname path g) (str/trim (second m)))))
          :else nil))
      (catch :default _ nil))))

(defn shallow-entry
  "gitdir が shallow なら {:label ... :grafts n}、そうでなければ nil。"
  [label gitdir]
  (when gitdir
    (let [sf (.join path gitdir "shallow")]
      (when (exists? sf)
        (let [n (graft-count sf)]
          (when (pos? n) {:label label :grafts n}))))))

(defn child-checkouts
  "orgs/<org>/<repo> の checkout パスと表示ラベルの列(遅延)。"
  [base]
  (let [orgs-dir (.join path base "orgs")]
    (when (exists? orgs-dir)
      (for [org   (try (vec (.readdirSync fs orgs-dir #js {:withFileTypes true}))
                       (catch :default _ []))
            :when (.isDirectory org)
            :let  [od (.join path orgs-dir (.-name org))]
            repo  (try (vec (.readdirSync fs od #js {:withFileTypes true}))
                       (catch :default _ []))
            :when (.isDirectory repo)]
        {:checkout (.join path od (.-name repo))
         :label    (str "orgs/" (.-name org) "/" (.-name repo))}))))

(defn sweep-children
  "全子 checkout を舐めて shallow なものを返す。
   {:hits [...] :scanned n :complete? bool :no-orgs? bool}"
  [base]
  (if-not (exists? (.join path base "orgs"))
    {:hits [] :scanned 0 :complete? false :no-orgs? true}
    (let [deadline (+ (js/Date.now) sweep-budget-ms)]
      (loop [cs (seq (child-checkouts base)), hits [], scanned 0]
        (cond
          (nil? cs)               {:hits hits :scanned scanned :complete? true}
          (> (js/Date.now) deadline) {:hits hits :scanned scanned :complete? false}
          :else
          (let [{:keys [checkout label]} (first cs)
                hit (shallow-entry label (resolve-gitdir checkout))]
            (recur (next cs) (if hit (conj hits hit) hits) (inc scanned))))))))

(defn registered-paths
  "manifest/west.yml に `path:` として登録されている checkout パスの集合。
   **hit が 1 件でもあるときにしか読まない**ので、平常時のコストはゼロ。"
  [base]
  (try
    (let [f (.join path base "manifest" "west.yml")]
      (if-not (exists? f)
        nil                                   ; west.yml が無ければ判定不能 → nil
        (->> (str/split-lines (.readFileSync fs f "utf8"))
             (keep #(second (re-find #"^\s*path:\s*(\S+)\s*$" %)))
             set)))
    (catch :default _ nil)))

(defn done!
  ([] (compat/exit 0))
  ([msg]
   (println (json/generate-string
              {:systemMessage msg
               :hookSpecificOutput {:hookEventName "SessionStart"
                                    :additionalContext msg}}))
   (compat/exit 0)))

(defn describe [{:keys [label grafts unregistered?]}]
  (str "  - " label " (" grafts " graft" (when (> grafts 1) "s") ")"
       (when unregistered? "  ← west.yml に未登録 — unshallow ではなく checkout 撤去が正解の可能性")))

(try
  (let [base (or (first *command-line-args*)
                 (not-empty (str (or js/process.env.CLAUDE_PROJECT_DIR "")))
                 ".")
        top  (or (git base "rev-parse" "--show-toplevel") base)]
    (when (str/blank? top) (done!))

    (let [root-hit (shallow-entry (str top " (この checkout の common dir)")
                                  (common-dir top))
          {:keys [hits scanned complete? no-orgs?]} (sweep-children top)
          reg      (when (seq hits) (registered-paths top))
          hits     (if reg
                     (mapv #(assoc % :unregistered? (not (contains? reg (:label %)))) hits)
                     hits)
          all      (concat (when root-hit [root-hit]) hits)]

      (when (empty? all) (done!))

      (let [shown  (take max-listed all)
            extra  (- (count all) (count shown))
            scope  (cond
                     no-orgs?       "(この checkout に orgs/ が無いため、子リポは未走査)"
                     complete?      (compat/format "(子リポ %d checkout を全走査)" scanned)
                     :else          (compat/format
                                      "(⚠ 子リポ走査は %d checkout で時間予算を超えて打ち切り — 部分的な結果)"
                                      scanned))]
        (done!
          (str/join
            "\n"
            (concat
              [(compat/format
                 "⚠ shallow clone 検知: %d 個の checkout が shallow です %s"
                 (count all) scope)
               ""
               "shallow な clone は merge-base / --is-ancestor / --contains に"
               "**誤った答えを、正しい答えと同じ顔で** 返します。ADR-2608124400 の実例では"
               "「stale な side branch からしか到達できない」と誤結論した commit が"
               "実際には main の 643 commit 前に居ました。ADR-2607211600 は shallow を"
               "撤回済みで、CLAUDE.md がローカル ancestry 判定を信用してよいと言えるのは"
               "full 履歴が実在する場所だけです。"
               ""]
              (map describe shown)
              (when (pos? extra) [(compat/format "  … 他 %d 件" extra)])
              [""
               "直し方: git -C <dir> -c gc.auto=0 fetch --unshallow origin"
               "(⚠ `git fetch --dry-run --unshallow` は preview ではありません —— ref 更新を"
               " 飛ばすだけで fetch 自体は実行され、実際に shallow を解除します。)"
               "(⚠ west.yml 未登録の checkout を反射的に unshallow しない —— 改名前の古いパスが"
               " 残っているだけなら、登録済みパスに full 履歴が既に在り、unshallow は無駄な"
               " 数 GB の fetch になります。ADR-2608124400 に実例。撤去は git-cleanup-conflict へ。)"
               ""
               "再発経路: linked worktree は $GIT_COMMON_DIR/shallow を元 repo と共有します。"
               "使い捨て worktree の中の `--depth` fetch は superproject 本体を shallow にします。"]))))))
  (catch :default _ nil))
(compat/exit 0)

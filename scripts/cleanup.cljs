#!/usr/bin/env nbb
;; cleanup.cljs — worktree / branch / stash 調査 → PR候補 → merge-2-main の読取専用 survey。
;;
;; オーナー指示を恒常化する:「subfolder 含め git worktree list / branch / stash list し、
;; PR/merge 未了は pr create / merge 2 main。これを cleanup というメッセージで same 処理。」
;;
;; 基本は dry-run（読取専用）。副作用は --apply で孤児 PR の close のみ。
;; 子リポの未コミット WIP は一切触らない（ガードレール: オーナーWIP保護・force-push 禁止・
;;   manifest は API single-entry・main 常に同期優先）。
;;
;; 使い方:
;;   nbb scripts/cleanup.cljs            ; survey のみ（dry-run・既定）
;;   nbb scripts/cleanup.cljs --apply    ; 孤児 PR を close する安全処置のみ実行
;;   nbb scripts/cleanup.cljs --merge    ; MERGEABLE な PR を gh pr merge --merge（main 同期を先に）
;;   nbb scripts/cleanup.cljs --subrepos ; 子リポ survey を省略（superproject のみ）
;;   nbb scripts/cleanup.cljs --unlanded ; 子リポ survey を UNLANDED（要着地）だけに絞る
;;
;; full history が既定（2026-07-21、ADR-2607211600 で shallow 既定は撤回済み）だが、
;; PR の ahead/behind・mergeable は引き続き GitHub API（server-side full history）で
;; 確定すること（ローカル判定だけに頼らない）。
;;
;; 2026-07-25 追加 — UNLANDED 判定（cloud-itonami Workspace 事故の再発防止）:
;;
;;   実測事故: orgs/gftdcojp/cloud-itonami に Directory/Mail/Drive/backup 一式
;;   ~4,000 行が **untracked のまま**（どのブランチにも無い・GitHub にも無い・
;;   デプロイもされていない）で共有 west checkout に置かれていた。当時のこの
;;   script はそれを `dirty=57` と出すだけで、内訳が「commit すらされていない
;;   feature 一式」だとは分からなかった。共有 checkout で誰かが `git checkout`
;;   した瞬間に消える状態だったのに、survey 上は他の repo の `dirty=1` と
;;   見分けがつかない。
;;
;;   そこで子リポごとに次の 4 段の着地状況（landing status）を出す:
;;     untracked=N   commit されていないファイル数（★最も危険。git が守らない）
;;     dirty=N       tracked だが未 commit の変更数
;;     unpushed=B:N  ローカル branch B が origin/B より N commit 先行（push 未了）
;;     nopr=B        push 済みだが open PR も default branch への merge も無い
;;   これらのいずれかがあれば UNLANDED としてマークする。
;;   PR の有無は各子リポの slug に対して `gh pr list --head <branch>` で引く
;;   （従来は superproject の PR しか見ていなかった＝子リポの未 PR は素通り）。
(require '[scripts.nbb-compat :refer [slurp spit file-seq format]]
         '[clojure.string :as str]
         '[clojure.java.shell :refer [sh]]
         '[cheshire.core :as json]
         '[clojure.java.io :as io])

(def root (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))
(def args (set *command-line-args*))
(def apply? (args "--apply"))
(def merge? (args "--merge"))
(def skip-sub? (args "--subrepos"))
(def unlanded-only? (args "--unlanded"))

(defn git [& xs] (let [{:keys [out exit]} (apply sh "git" xs)] (when (zero? exit) out)))
(defn gitc [dir & xs] (let [{:keys [out exit]} (apply sh "git" "-C" dir xs)] (when (zero? exit) out)))

;; superproject の slug（PR/branch API 用）。gh repo view から確実に採る。
(def slug
  (-> (sh "gh" "repo" "view" "--json" "nameWithOwner")
      :out (json/parse-string true) :nameWithOwner))

(defn hr [title]
  (println)
  (println (str "=== " title " ===")))

;; ---------- 1. superproject worktree / branch / stash ----------
(hr "superproject: git worktree list")
(println (str/trim (or (git "worktree" "list") "(none)")))

(hr "superproject: git branch -a")
(println (str/trim (or (git "branch" "-a") "(none)")))

(hr "superproject: git stash list")
(let [st (str/trim (or (git "stash" "list") ""))]
  (println (if (seq st) st "(no stash)")))

;; ---------- 2. open PR 分類（server-side） ----------
(hr "superproject: open PRs（server-side で分類）")
(def prs
  (let [{:keys [out exit]} (sh "gh" "pr" "list" "--state" "open"
                               "--json" "number,title,headRefName,baseRefName,mergeable,mergeStateStatus")]
    (if (zero? exit) (json/parse-string out true) [])))

(defn branch-exists? [head]
  (let [{:keys [exit]} (sh "gh" "api" (str "repos/" slug "/branches/" head) "--silent")]
    (zero? exit)))

(defn classify [pr]
  (let [head (:headRefName pr)
        mb (str/lower-case (str (:mergeable pr)))
        mss (str/lower-case (str (:mergeStateStatus pr)))]
    (cond
      (not (branch-exists? head))                              :orphan
      (or (= mb "dirty") (= mb "conflicting")
          (= mss "dirty") (= mss "conflicting") (= mss "unknown")) :conflict
      (= mb "mergeable")                                       :mergeable
      :else                                                    :unknown)))

(def classified (map #(assoc % :cls (classify %)) prs))

(doseq [pr classified]
  (println (format "#%-4s %-10s %s  <-  %s"
                   (:number pr) (name (:cls pr)) (:title pr) (:headRefName pr))))

(def orphan-prs (filter #(= (:cls %) :orphan) classified))
(def merge-prs (filter #(= (:cls %) :mergeable) classified))
(def conflict-prs (filter #(#{:conflict :unknown} (:cls %)) classified))

(println)
(println (format "分類: orphan(close候補)=%d  mergeable=%d  conflict/unknown=%d"
                 (count orphan-prs) (count merge-prs) (count conflict-prs)))

;; ---------- 3. 子リポ survey（読取専用） ----------
;;
;; 「UNLANDED」= その子リポの成果が default branch に到達していない状態。
;; untracked > unpushed > nopr > dirty の順に危険（左ほど git が守ってくれない）。
(defn- repo-slug
  "子リポの GitHub slug。remote URL から採る（gh repo view はネットワーク往復が
  重いので使わない）。取れなければ nil。"
  [dir]
  (some-> (gitc dir "remote" "get-url" "origin")
          str/trim
          (as-> u (or (second (re-find #"github\.com[:/](.+?)(?:\.git)?$" u)) nil))))

(defn- default-branch
  "origin/HEAD が指す既定ブランチ。未設定なら main を仮定する。"
  [dir]
  (or (some-> (gitc dir "symbolic-ref" "--quiet" "refs/remotes/origin/HEAD")
              str/trim (str/replace #"^refs/remotes/origin/" "") not-empty)
      "main"))

(defn- ahead-of-remote
  "ローカル branch が origin/<branch> より何 commit 先行しているか。
  upstream が無ければ :no-remote。"
  [dir branch]
  (if-not (gitc dir "rev-parse" "--verify" "--quiet" (str "refs/remotes/origin/" branch))
    :no-remote
    (some-> (gitc dir "rev-list" "--count" (str "origin/" branch ".." branch))
            str/trim parse-long)))

(defn- open-pr-for
  "その branch を head に持つ open PR の番号。無ければ nil。
  slug が取れない/gh が失敗した場合も nil（fail-open — survey を落とさない）。"
  [slug branch]
  (when slug
    (let [{:keys [out exit]} (sh "gh" "pr" "list" "--repo" slug "--state" "open"
                                 "--head" branch "--json" "number" "--limit" "1")]
      (when (zero? exit)
        (some-> (json/parse-string out true) first :number)))))

(defn- merged-into-default?
  "branch の tip が既に default branch から到達可能か（= 着地済み）。"
  [dir branch default]
  (boolean (gitc dir "merge-base" "--is-ancestor" branch (str "origin/" default))))

(defn- annex?
  "git-annex / DataLad dataset か。実測: orgs/gftdcojp/m365-archive は untracked=15945
  / dirty=122792 を常時抱えており（annex はコンテンツを working tree に materialize
  するので当然）、これを UNLANDED として最上位に並べると本物の未着地 WIP が
  埋もれる。annex は別扱いにして UNLANDED から外す（branch/stash は従来どおり報告）。"
  [dir]
  (or (.exists (io/file dir ".git" "annex"))
      (.exists (io/file dir ".datalad"))))

(defn- survey-repo [dir]
  (let [br      (str/trim (or (gitc dir "rev-parse" "--abbrev-ref" "HEAD") ""))
        stash   (count (remove str/blank? (str/split-lines (or (gitc dir "stash" "list") ""))))
        status  (remove str/blank? (str/split-lines (or (gitc dir "status" "--porcelain") "")))
        untracked (count (filter #(str/starts-with? % "??") status))
        dirty     (- (count status) untracked)
        locals  (remove #{"" "main" "master" "synced/main" "git-annex" "manifest-rev"}
                        (str/split-lines (or (gitc dir "for-each-ref"
                                                   "--format=%(refname:short)" "refs/heads/") "")))
        default (default-branch dir)
        slug    (repo-slug dir)
        ;; 実際に「まだ着地していない」ローカル branch だけを見る。既に default
        ;; から到達可能な branch は着地済みなので PR の有無を問わない。
        live    (remove #(merged-into-default? dir % default) locals)
        unpushed (for [b live
                       :let [n (ahead-of-remote dir b)]
                       :when (or (= n :no-remote) (and (number? n) (pos? n)))]
                   (str b ":" (if (= n :no-remote) "no-remote" n)))
        ;; push 済みで未着地の branch。PR 照会は 1 branch = 1 API 往復なので、
        ;; 長期 branch farm（実測: kotoba-lang/webgpu は 80 本超、slides は 90 本超）
        ;; では survey が実質終わらない。上限を超えたら PR 照会を諦めるが、
        ;; 黙って切り捨てず :nopr-skipped として必ず報告する。
        pushed-live (for [b live :let [n (ahead-of-remote dir b)]
                          :when (and (number? n) (zero? n))] b)
        pr-cap  20
        skip-pr? (> (count pushed-live) pr-cap)
        nopr    (if skip-pr? [] (vec (remove #(open-pr-for slug %) pushed-live)))]
    {:dir dir :branch br :stash stash :dirty dirty :untracked untracked
     :locals locals :unpushed (vec unpushed) :nopr nopr :annex? (annex? dir)
     :nopr-skipped (when skip-pr? (count pushed-live))
     ;; annex/DataLad の untracked/dirty は正常状態なので UNLANDED に数えない。
     ;; branch 側の未着地（unpushed / nopr）は annex でも本物なので残す。
     :unlanded? (boolean (if (annex? dir)
                           (or (seq unpushed) (seq nopr) skip-pr?)
                           (or (pos? untracked) (pos? dirty)
                               (seq unpushed) (seq nopr) skip-pr?)))}))

(when-not skip-sub?
  (hr "子リポ survey: UNLANDED 判定（detached-HEAD + manifest-rev のみは通常状態）")
  (println "凡例: untracked=commit すらされていない / unpushed=push 未了 / nopr=push 済みだが PR 無し")
  (println)
  (let [repos (->> (sh "find" "orgs" "-maxdepth" "3" "-name" ".git" "-type" "d")
                   :out str/trim str/split-lines sort)
        rows  (->> repos
                   (map #(survey-repo (.substring % 0 (- (count %) 5))))
                   (filter (fn [{:keys [unlanded? branch stash locals]}]
                             (if unlanded-only?
                               unlanded?
                               (or unlanded? (pos? stash) (seq locals)
                                   (and (not= branch "HEAD") (not= branch ""))))))
                   ;; 危険な順（untracked が最優先）。annex は untracked を 0 扱いに
                   ;; して並べる（m365-archive の 15945 件が先頭を占拠しないように）。
                   (sort-by (juxt #(if (:annex? %) 0 (- (:untracked %)))
                                  #(if (:annex? %) 0 (- (:dirty %)))
                                  :dir)))]
    (doseq [{:keys [dir branch stash dirty untracked locals unpushed nopr
                    nopr-skipped annex? unlanded?]} rows]
      (let [parts (cond-> []
                    unlanded?                           (conj "UNLANDED")
                    annex?                              (conj "annex(untracked/dirty は既定状態)")
                    (and (pos? untracked) (not annex?)) (conj (str "untracked=" untracked))
                    (and (pos? dirty) (not annex?))     (conj (str "dirty=" dirty))
                    (seq unpushed)                      (conj (str "unpushed=" (str/join "," unpushed)))
                    (seq nopr)                          (conj (str "nopr=" (str/join "," nopr)))
                    nopr-skipped                        (conj (str "nopr=?(" nopr-skipped " branches, PR照会を打切り)"))
                    (pos? stash)                        (conj (str "stash=" stash))
                    (and (not= branch "HEAD") (not= branch "")) (conj (str "branch=" branch))
                    (seq locals)                        (conj (str "locals=" (count locals))))]
        (println (format "%-58s %s" dir (str/join "; " parts)))))
    (println)
    (println (format "UNLANDED な子リポ=%d / 掲載=%d（untracked を持つ repo=%d、annex 除外=%d）"
                     (count (filter :unlanded? rows)) (count rows)
                     (count (filter #(and (pos? (:untracked %)) (not (:annex? %))) rows))
                     (count (filter :annex? rows))))
    (when (some #(and (pos? (:untracked %)) (not (:annex? %))) rows)
      (println)
      (println "⚠ untracked を持つ repo は最優先で着地させること — どのブランチにも")
      (println "  存在しないので、共有 checkout で `git checkout` が走った瞬間に消える。")
      (println "  手順は skill git-cleanup-conflict / manifest/cleanup-workflow.edn :retirement。"))))

;; ---------- 4. 処置 ----------
(hr "処置")

;; 4a. 孤児 PR（head branch 削除済み）の close
(if (seq orphan-prs)
  (if apply?
    (doseq [pr orphan-prs]
      (let [n (:number pr)
            head (:headRefName pr)
            cmt (str "Superseded by merged cleanup work. Head branch " head
                     " deleted from origin. Closing as orphaned (cleanup runbook).")]
        (println (format "closing #%d ..." n))
        (let [{:keys [exit err]} (sh "gh" "pr" "close" (str n) "--comment" cmt)]
          (println (if (zero? exit) "  ok" (str "  FAIL: " err))))))
    (do
      (println "孤児 PR close 候補（dry-run: --apply で実行）:")
      (doseq [pr orphan-prs]
        (println (format "  gh pr close %d  # %s <- %s"
                         (:number pr) (:title pr) (:headRefName pr))))))
  (println "close 候補の孤児 PR は無し。"))

;; 4b. MERGEABLE PR の merge（--merge 明示時のみ。main 同期は git-push-main-sync-guard.cljs 別担保）
(if (seq merge-prs)
  (if merge?
    (doseq [pr merge-prs]
      (println (format "merging #%d (--merge) ..." (:number pr)))
      (let [{:keys [exit err]} (sh "gh" "pr" "merge" (str (:number pr)) "--merge")]
        (println (if (zero? exit) "  ok" (str "  FAIL: " err)))))
    (do
      (println "MERGEABLE PR（dry-run: --merge で merge）:")
      (doseq [pr merge-prs]
        (println (format "  gh pr merge %d --merge  # %s"
                         (:number pr) (:title pr)))))))

(println)
(println "※ 子リポ WIP は一切変更していません。一覧は上記「要オーナー確認」のみ。")

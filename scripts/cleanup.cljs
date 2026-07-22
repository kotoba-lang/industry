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
;;
;; full history が既定（2026-07-21、ADR-2607211600 で shallow 既定は撤回済み）だが、
;; PR の ahead/behind・mergeable は引き続き GitHub API（server-side full history）で
;; 確定すること（ローカル判定だけに頼らない）。
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
(when-not skip-sub?
  (hr "子リポ survey: 要オーナー確認（detached-HEAD+manifest-rev のみは通常状態）")
  (let [repos (->> (sh "find" "orgs" "-maxdepth" "3" "-name" ".git" "-type" "d")
                   :out str/trim str/split-lines sort)]
    (doseq [r repos]
      (let [dir (.substring r 0 (- (count r) 5))
            br (str/trim (or (gitc dir "rev-parse" "--abbrev-ref" "HEAD") ""))
            stash (count (str/split-lines (or (gitc dir "stash" "list") "")))
            dirty (count (remove str/blank?
                                 (str/split-lines (or (gitc dir "status" "--porcelain") ""))))
            locals (remove #{"" "main" "master" "synced/main" "git-annex"}
                           (str/split-lines (or (gitc dir "for-each-ref"
                                                      "--format=%(refname:short)" "refs/heads/") "")))
            interesting? (or (and (not= br "HEAD") (not= br "") (seq locals))
                             (and (seq locals) (not= (set locals) #{"manifest-rev"}))
                             (pos? stash) (pos? dirty))]
        (when interesting?
          (let [parts (cond-> []
                        (and (not= br "HEAD") (not= br "")) (conj (str "branch=" br))
                        (pos? stash) (conj (str "stash=" stash))
                        (pos? dirty) (conj (str "dirty=" dirty))
                        (seq locals) (conj (str "locals=" (str/join "," locals))))]
            (println (format "%-58s %s" dir (str/join "; " parts)))))))))

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

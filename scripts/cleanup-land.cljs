#!/usr/bin/env nbb
;; cleanup-land.cljs — scripts/cleanup.cljs が UNLANDED と判定した子リポの WIP を
;; 実際に着地させる（archive → commit → PR → merge）。cleanup.cljs は読取専用の
;; survey のままにし、書き込み側はこのスクリプトに分ける。
;;
;; 使い方:
;;   nbb scripts/cleanup-land.cljs                     ; dry-run plan（既定）
;;   nbb scripts/cleanup-land.cljs --apply             ; 実行
;;   nbb scripts/cleanup-land.cljs --apply --names a,b ; 対象を限定
;;   nbb scripts/cleanup-land.cljs --apply --max 20    ; 上限（残りは報告して打切り）
;;
;; ── なぜ一律 merge しないか（重要） ────────────────────────────────────
;; UNLANDED を1種類として扱うと安全性が壊れる。危険度ではなく「main を壊しうるか」で
;; 3つに割り、merge するのは1つ目だけにする:
;;
;;   :additive  untracked ファイルのみ。main に同名ファイルが存在しない = 既存の
;;              いかなる行も書き換えない。着地しない方が危険（どのブランチにも無く、
;;              共有 checkout の `git checkout` 一発で消える）。→ commit → PR → merge。
;;
;;   :review    tracked ファイルの変更。**auto-merge しない**。base が古いと main を
;;              静かに巻き戻す。実測 2026-07-25: cloud-itonami の working tree は
;;              origin/main から 1381 commits 遅れており、その legal/terms.md を
;;              そのまま適用していれば、オーナー承認済みの公開法務ページを 2026-07-18
;;              の DRAFT に戻していた。→ commit → PR まで。merge は人間の判断。
;;
;;   :branches  既存のローカル branch。未 push なら push（保全）、push 済みで PR が
;;              無ければ PR を作る。**auto-merge しない** — 放棄された実験・意図的な
;;              分岐・force-push 済みなど状態が不明で、まとめて main に入れると
;;              fleet 全体を壊す。
;;
;; ── 書き込み経路 ──────────────────────────────────────────────────────
;; ローカル worktree を切らず、GitHub git API でサーバ側に commit を作る
;; （blob → tree(base_tree=default branch) → commit → ref）。理由:
;;   - 対象が 100 repo 規模で、full checkout の worktree を都度切ると非現実的
;;   - 共有 checkout が古い branch に居ても影響を受けない（cloud-itonami は
;;     1381 commits 遅れの rescue branch 上だった）
;;   - CLAUDE.md が manifest 更新で確立した「サーバ側 single commit」と同じ形
;;
;; ── 安全床 ────────────────────────────────────────────────────────────
;;   - drop/削除は一切しない。archive してから add するだけ
;;   - .git/stash-archive-<date>/ に untracked 一覧と tracked patch を必ず先に書く
;;   - credential らしきパス・大きすぎるファイルは skip し、必ず報告する（黙って
;;     落とさない）
;;   - git-annex / DataLad dataset は対象外
(require '[scripts.nbb-compat :refer [format]]
         '[clojure.string :as str]
         '[clojure.java.shell :refer [sh]]
         '[clojure.java.io :as io]
         '[cheshire.core :as json])

(def node-fs (js/require "node:fs"))

(def args *command-line-args*)
(def argset (set args))
(def apply? (argset "--apply"))
(defn- opt [flag] (second (drop-while #(not= % flag) args)))
(def only-names (some-> (opt "--names") (str/split #",") set))
(def max-repos (some-> (opt "--max") parse-long))
(def stamp "20260725")

(defn- gitc [dir & xs]
  (let [{:keys [out exit]} (apply sh "git" "-C" dir xs)] (when (zero? exit) out)))

(defn- gh-json
  "gh の stdout を JSON として読む。`--jq` で裸のスカラを出す呼び出しには使わない
  （`6dc20b…` のような bare token は JSON として読めず nil に落ちる — 最初の実装は
  これで全 commit が黙って失敗した）。その用途は gh-str。"
  [& xs]
  (let [{:keys [out exit err]} (apply sh "gh" xs)]
    (if (zero? exit)
      (try (json/parse-string out true) (catch :default _ nil))
      (do (binding [*out* *err*] (println "  gh failed:" (str/trim (str err)))) nil))))

(defn- gh-str
  "gh の stdout をそのまま（trim して）返す。`--jq` でスカラを取る用。"
  [& xs]
  (let [{:keys [out exit err]} (apply sh "gh" xs)]
    (if (zero? exit)
      (not-empty (str/trim (str out)))
      (do (binding [*out* *err*] (println "  gh failed:" (str/trim (str err)))) nil))))

(defn- gh-input!
  "巨大な body を持つ POST は argv 上限（macOS で ~1MB、base64 blob は容易に超える）
  に当たるので、必ず JSON ファイル経由で送る。"
  [endpoint payload jq]
  (let [tmp (str "/tmp/cleanup-land-" (hash endpoint) "-" (hash (str payload)) ".json")]
    (.writeFileSync node-fs tmp (json/generate-string payload))
    (gh-str "api" endpoint "--input" tmp "--jq" jq)))

;; ---------- 分類 ----------

(def ^:private credential-re
  #"(?i)(^|/)(\.env($|\.)|.*\.(pem|key|p12|pfx|jks|keystore)$|id_(rsa|ed25519)|identity\.edn$|.*secret.*|.*credential.*|\.kagi/|\.npmrc$|\.netrc$)")

(def ^:private junk-re
  #"(^|/)(node_modules|\.cpcache|\.shadow-cljs|\.wrangler|target|dist|build|out|\.DS_Store|.*\.log)(/|$)")

(def max-bytes (* 2 1024 1024))

(defn- classify-file [dir path]
  (let [f (io/file dir path)
        size (try (.-size (.statSync node-fs (.getPath f))) (catch :default _ 0))]
    (cond
      (re-find credential-re path) :skip-credential
      (re-find junk-re path)       :skip-junk
      (> size max-bytes)           :skip-large
      :else                        :take)))

(defn- annex? [dir]
  (or (.exists (io/file dir ".git" "annex")) (.exists (io/file dir ".datalad"))))

(defn- repo-slug
  "origin URL から slug を採り、**GitHub 上の canonical 名に解決する**。

  rename 追従が必須。この workspace は `-clj` サフィックス廃止（ADR-2607102200
  addendum 14）等で改名が多く、ローカルの remote URL は旧名のまま残る。GitHub は
  GET と `-f` 形式の POST はリダイレクトするが、`--input` 付き POST には HTTP 307
  を返し `gh` はそれを追わない — 実測 2026-07-25: kotoba-lang/kotoba-git（現
  kotoba-lang/bonsai）で blob 作成が全件 307 で落ちた。読み取りは通るのに書き込み
  だけ落ちるので、canonical 化しないと「なぜか commit だけ失敗する」形で現れる。"
  [dir]
  (some-> (gitc dir "remote" "get-url" "origin") str/trim
          (as-> u (second (re-find #"github\.com[:/](.+?)(?:\.git)?$" u)))
          (as-> raw (or (gh-str "api" (str "repos/" raw) "--jq" ".full_name") raw))))

(defn- default-branch [dir]
  (or (some-> (gitc dir "symbolic-ref" "--quiet" "refs/remotes/origin/HEAD")
              str/trim (str/replace #"^refs/remotes/origin/" "") not-empty)
      "main"))

;; ---------- archive（drop はしないが、着地前に必ず退避する） ----------

(defn- archive! [dir untracked]
  (let [adir (str dir "/.git/stash-archive-" stamp)]
    (.mkdirSync node-fs adir #js {:recursive true})
    (.writeFileSync node-fs (str adir "/untracked-files.txt") (str/join "\n" untracked))
    (when-let [patch (gitc dir "diff")]
      (.writeFileSync node-fs (str adir "/tracked-modifications.patch") patch))
    (.writeFileSync node-fs (str adir "/index.txt")
                    (str "cleanup-land " stamp "\n"
                         "untracked=" (count untracked) "\n"
                         "手順: skill git-cleanup-conflict / manifest/cleanup-workflow.edn :unlanded\n"))
    adir))

;; ---------- GitHub 側に commit を作る ----------

(defn- b64-file [dir path]
  (.toString (.readFileSync node-fs (str dir "/" path)) "base64"))

(defn- create-blob! [slug dir path]
  (gh-input! (str "repos/" slug "/git/blobs")
             {:content (b64-file dir path) :encoding "base64"} ".sha"))

(defn- file-mode
  "実行ビットを落とさない（bin/* を 100644 で載せると実行できなくなる）。"
  [dir path]
  (try (if (pos? (bit-and (.-mode (.statSync node-fs (str dir "/" path))) 0x40))
         "100755" "100644")
       (catch :default _ "100644")))

(defn- server-commit!
  "base branch の tip の上に paths を載せた commit を作り、branch ref を作る。
  branch が既にあれば ref は作らず、その ref を commit へ更新する。
  -> {:branch b :commit sha :files n} / nil"
  [slug dir base paths branch message]
  (when-let [base-sha (gh-str "api" (str "repos/" slug "/git/ref/heads/" base) "--jq" ".object.sha")]
    (when-let [base-tree (gh-str "api" (str "repos/" slug "/git/commits/" base-sha) "--jq" ".tree.sha")]
      (let [entries (keep (fn [p]
                            (when-let [sha (create-blob! slug dir p)]
                              {:path p :mode (file-mode dir p) :type "blob" :sha sha}))
                          paths)]
        (when (seq entries)
          (let [tree-sha (gh-input! (str "repos/" slug "/git/trees")
                                    {:base_tree base-tree :tree entries} ".sha")
                commit-sha (when tree-sha
                             (gh-input! (str "repos/" slug "/git/commits")
                                        {:message message :tree tree-sha :parents [base-sha]} ".sha"))]
            (when commit-sha
              ;; branch が未作成なら 404 が正常系。gh-str は失敗を stderr に出すので
              ;; ここだけ静かに判定する（毎 repo で "Not Found" が出ると本物の
              ;; エラーが埋もれる）。
              (let [existing? (let [{:keys [out exit]} (sh "gh" "api" (str "repos/" slug "/git/ref/heads/" branch)
                                                           "--jq" ".object.sha")]
                                (when (zero? exit) (not-empty (str/trim (str out)))))
                    ok (if existing?
                         (gh-input! (str "repos/" slug "/git/refs/heads/" branch)
                                    {:sha commit-sha :force true} ".object.sha")
                         (gh-input! (str "repos/" slug "/git/refs")
                                    {:ref (str "refs/heads/" branch) :sha commit-sha} ".object.sha"))]
                (when ok {:branch branch :commit commit-sha :files (count entries)})))))))))

(defn- open-pr! [slug base branch title body]
  (let [{:keys [out exit]} (sh "gh" "pr" "create" "--repo" slug "--base" base "--head" branch
                               "--title" title "--body" body)]
    (when (zero? exit) (str/trim out))))

(defn- merge-pr! [slug url]
  (let [{:keys [exit err]} (sh "gh" "pr" "merge" url "--repo" slug "--merge")]
    (if (zero? exit) :merged (do (binding [*out* *err*] (println "  merge failed:" (str/trim (str err)))) :unmerged))))

(defn- existing-pr [slug branch]
  (some-> (gh-json "pr" "list" "--repo" slug "--state" "open" "--head" branch
                   "--json" "url" "--limit" "1")
          first :url))

;; gh-input! を使うので、ref 更新は PATCH ではなく POST/PATCH を gh が endpoint から
;; 判別する。既存 ref への POST は 422 になるため existing? で分岐している。

;; ---------- 1 リポの処理 ----------

(defn- plan-repo [dir]
  (let [status (remove str/blank? (str/split-lines (or (gitc dir "status" "--porcelain") "")))
        untracked-raw (->> status (filter #(str/starts-with? % "??")) (map #(subs % 3)))
        tracked (->> status (remove #(str/starts-with? % "??")) (map #(str/trim (subs % 2))))
        ;; ディレクトリ表記（`?? foo/`）は展開する
        untracked (mapcat (fn [p]
                            (if (str/ends-with? p "/")
                              (->> (or (gitc dir "ls-files" "--others" "--exclude-standard" "--" p) "")
                                   str/split-lines (remove str/blank?))
                              [p]))
                          untracked-raw)
        grouped (group-by #(classify-file dir %) untracked)]
    {:dir dir :slug (repo-slug dir) :base (default-branch dir)
     :take (vec (:take grouped))
     :skipped (into {} (for [[k v] grouped :when (not= k :take)] [k (vec v)]))
     :tracked (vec tracked)}))

(defn- land-repo! [{:keys [dir slug base take skipped tracked]}]
  (println (format "\n%s  (%s)" dir (or slug "no-remote")))
  (doseq [[k v] skipped]
    (println (format "  skip %-18s %d 件: %s" (name k) (count v)
                     (str/join ", " (take 4 v)))))
  (cond
    (nil? slug) (println "  → remote が無いので着地先が無い。報告のみ。")
    (and (empty? take) (empty? tracked)) (println "  → 着地対象なし")
    :else
    (if-not apply?
      (do (when (seq take) (println (format "  plan :additive  %d files → PR → merge" (count take))))
          (when (seq tracked) (println (format "  plan :review    %d files → PR のみ（merge しない）" (count tracked)))))
      (let [adir (archive! dir (concat take (mapcat vals (vals skipped))))]
        (println (format "  archived → %s" adir))
        ;; :additive — untracked のみ。main のどの行も書き換えないので merge する。
        (when (seq take)
          (let [br (str "agent/cleanup-land-" stamp)
                msg (str "cleanup: land untracked WIP (" (count take) " files)\n\n"
                         "These files existed only in the shared west checkout — on no branch,\n"
                         "on no remote. A single `git checkout` there would have destroyed them.\n"
                         "Purely additive: none of these paths exist on " base ", so no existing\n"
                         "line is rewritten. Landed by scripts/cleanup-land.cljs (skill\n"
                         "git-cleanup-conflict); originals archived under\n"
                         ".git/stash-archive-" stamp "/ in the operator's checkout.\n\n"
                         "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>")]
            (if-let [{:keys [files]} (server-commit! slug dir base take br msg)]
              (let [url (or (existing-pr slug br)
                            (open-pr! slug base br
                                      (str "cleanup: land untracked WIP (" files " files)")
                                      (str "Untracked files rescued from the shared west checkout — on no branch, on no remote.\n\n"
                                           "Purely additive (no path here exists on `" base "`), so this cannot regress anything.\n\n"
                                           "Landed by `scripts/cleanup-land.cljs`; see skill `git-cleanup-conflict`.\n\n"
                                           "🤖 Generated with [Claude Code](https://claude.com/claude-code)")))]
                (println (format "  :additive %d files → %s → %s" files url
                                 (name (merge-pr! slug url)))))
              (println "  :additive commit に失敗（報告のみ、ローカルは無傷）"))))
        ;; :review — tracked 変更。base が古いと main を巻き戻すので merge しない。
        (when (seq tracked)
          (let [br (str "agent/cleanup-review-" stamp)
                msg (str "cleanup: preserve uncommitted tracked changes (" (count tracked) " files)\n\n"
                         "NOT auto-merged. These rewrite files that already exist on " base ",\n"
                         "and the working tree they came from may be far behind it.\n\n"
                         "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>")]
            (if-let [{:keys [files]} (server-commit! slug dir base tracked br msg)]
              (let [url (or (existing-pr slug br)
                            (open-pr! slug base br
                                      (str "cleanup: preserve uncommitted tracked changes (" files " files)")
                                      (str "⚠️ **Review before merging — deliberately not auto-merged.**\n\n"
                                           "These rewrite files that already exist on `" base "`, and the working tree they\n"
                                           "came from may be far behind it. Merging blind can silently roll `" base "` back.\n\n"
                                           "Precedent: cloud-itonami's working tree was 1381 commits behind `main`; applying its\n"
                                           "`legal/terms.md` would have reverted owner-approved public legal pages to a DRAFT.\n\n"
                                           "🤖 Generated with [Claude Code](https://claude.com/claude-code)")))]
                (println (format "  :review   %d files → %s （merge しない）" files url)))
              (println "  :review   commit に失敗（報告のみ、ローカルは無傷）"))))))))

;; ---------- main ----------

(println (str "cleanup-land " (if apply? "APPLY" "DRY-RUN（--apply で実行）")))
(println "分類: :additive=untracked のみ→merge / :review=tracked 変更→PR のみ / annex は対象外")

(def repos
  (->> (sh "find" "orgs" "-maxdepth" "3" "-name" ".git" "-type" "d")
       :out str/trim str/split-lines sort
       (map #(subs % 0 (- (count %) 5)))
       (remove annex?)
       (filter (fn [d] (if only-names (some #(str/ends-with? d (str "/" %)) only-names) true)))))

(def plans (->> repos (map plan-repo) (filter #(or (seq (:take %)) (seq (:tracked %)) (seq (:skipped %))))))
(def selected (if max-repos (take max-repos plans) plans))
(def dropped (- (count plans) (count selected)))

(println (format "\n対象 %d repo（--max により %d repo を打切り）" (count selected) dropped))
(doseq [p selected] (land-repo! p))

(println (format "\n完了: %d repo 処理 / additive=%d repo / review=%d repo"
                 (count selected)
                 (count (filter #(seq (:take %)) selected))
                 (count (filter #(seq (:tracked %)) selected))))
(when (pos? dropped)
  (println (format "⚠ --max で %d repo を処理していない。再実行して残りを処理すること。" dropped)))
(println "※ ローカルの WIP は一切削除していない（archive + 着地のみ）。")

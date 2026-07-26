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
;;   nbb scripts/cleanup-land.cljs --apply --branches  ; :branches も処理（push / PR。merge しない）
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
(def branches? (argset "--branches"))
(def stamp
  "Archive directory suffix. Derived from today, not hardcoded: a fixed
  stamp makes every run write into the SAME .git/stash-archive-<stamp>/
  and overwrite the previous run's untracked-files.txt / index.txt /
  tracked-modifications.patch. The local WIP is never deleted here, so
  that was not data loss in practice, but it silently destroyed the
  audit trail the archive exists to provide and mislabelled archives
  with a date they were not taken on."
  (-> (js/Date.) .toISOString (subs 0 10) (str/replace "-" "")))

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
          (as-> u (second (re-find #"github\.com[:/](.+?)(?:\.git)?$" u)))))

(defn- canonical-slug
  "raw slug -> GitHub 上の現在名。**着地対象がある repo にだけ呼ぶこと**。
  planning 段階で全 repo に対して呼ぶと ~700 回の API 往復になり、実測で
  25 分経っても plan が終わらなかった（かつ rate limit を無駄に消費する）。"
  [raw]
  (when raw (or (gh-str "api" (str "repos/" raw) "--jq" ".full_name") raw)))

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

(defn- b64-file
  "読めなければ nil。1 ファイルの不整合で fleet 全体の実行を落とさない
  （broken symlink・実行中に消えたファイル・権限など）。"
  [dir path]
  (try (.toString (.readFileSync node-fs (str dir "/" path)) "base64")
       (catch :default e
         (println (format "  skip unreadable     %s (%s)" path (ex-message e)))
         nil)))

(defn- create-blob! [slug dir path]
  (when-let [c (b64-file dir path)]
    (gh-input! (str "repos/" slug "/git/blobs") {:content c :encoding "base64"} ".sha")))

(defn- file-mode
  "実行ビットを落とさない（bin/* を 100644 で載せると実行できなくなる）。"
  [dir path]
  (try (if (pos? (bit-and (.-mode (.statSync node-fs (str dir "/" path))) 0x40))
         "100755" "100644")
       (catch :default _ "100644")))

(defn- base-blobs
  "base branch の tree を1回だけ取って path -> blob-sha の map にする。

  これが無いと再実行が冪等にならない。着地しても**ローカルの untracked ファイルは
  消さない**（安全床）ので、2 回目の実行では同じファイルがまた untracked として
  現れる。実測 2026-07-25: kotoba-lang/bonsai は PR #3 で 15 件 merge 済みなのに、
  次の dry-run がその 15 件をもう一度 :additive として計画した。ローカルの blob sha
  （git hash-object）が base 側と一致するものは既に着地済みなので落とす。"
  [slug base]
  (when-let [tree-sha (some-> (gh-str "api" (str "repos/" slug "/git/ref/heads/" base) "--jq" ".object.sha")
                              (as-> c (gh-str "api" (str "repos/" slug "/git/commits/" c) "--jq" ".tree.sha")))]
    (some->> (gh-json "api" (str "repos/" slug "/git/trees/" tree-sha "?recursive=1"))
             :tree
             (filter #(= "blob" (:type %)))
             (map (juxt :path :sha))
             (into {}))))

(defn- local-blob-sha [dir path]
  (some-> (gitc dir "hash-object" "--" path) str/trim not-empty))

(defn- drop-already-landed
  "base 側と同一内容のパスを落とす。-> [残り 落としたもの]"
  [dir base-map paths]
  (if (empty? base-map)
    [paths []]
    (let [landed? (fn [p] (and (contains? base-map p)
                               (= (get base-map p) (local-blob-sha dir p))))]
      [(vec (remove landed? paths)) (vec (filter landed? paths))])))

(defn- server-commit!
  "base branch の tip の上に paths を載せた commit を作り、branch ref を作る。
  branch が既にあれば ref は作らず、その ref を commit へ更新する。
  -> {:branch b :commit sha :files n} / nil"
  [slug dir base paths branch message]
  ;; base が未作成（= commit が1つも無い新規 repo）なら parents 無し・base_tree 無しの
  ;; ルートコミットを作る。placeholder repo（例 kotoba-lang/org-threejs: branch
  ;; init_placeholder に commit ゼロ、ファイルは全部 untracked）はこの経路でしか
  ;; 着地できない。
  (let [base-sha (gh-str "api" (str "repos/" slug "/git/ref/heads/" base) "--jq" ".object.sha")
        base-tree (when base-sha
                    (gh-str "api" (str "repos/" slug "/git/commits/" base-sha) "--jq" ".tree.sha"))]
    (let [_ nil]
      (let [entries (keep (fn [p]
                            (when-let [sha (create-blob! slug dir p)]
                              {:path p :mode (file-mode dir p) :type "blob" :sha sha}))
                          paths)]
        (when (seq entries)
          (let [tree-sha (gh-input! (str "repos/" slug "/git/trees")
                                    (cond-> {:tree entries} base-tree (assoc :base_tree base-tree))
                                    ".sha")
                commit-sha (when tree-sha
                             (gh-input! (str "repos/" slug "/git/commits")
                                        (cond-> {:message message :tree tree-sha}
                                          base-sha (assoc :parents [base-sha]))
                                        ".sha"))]
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

(def ^:private org-visibility
  "新規 repo の visibility。**既存 repo の visibility 変更ではない** — 新規作成を
  org 既定に合わせるのは skill new-project-scaffold の恒久承認の範囲で、CLAUDE.md が
  事前確認を要求する「公開リポ化」は既存 private を public に反転する操作を指す。
  実測 2026-07-25（`gh repo list <org> --limit 60`）: kotoba-lang 60/60 public、
  etzhayyim 60/60 public、cloud-itonami 60/60 public、gftdcojp 58 private / 2 public。
  repos.edn の :orgs が SSoT（ADR-2607021330）。"
  {"kotoba-lang" "--public" "etzhayyim" "--public" "cloud-itonami" "--public"
   "gftdcojp" "--private" "com-junkawasaki" "--private" "jk-luxury" "--private"})

(defn- create-remote!
  "remote が無いローカル repo に GitHub repo を作って push する。
  -> canonical slug / nil。commit が1つも無い repo は push できないので作らない。"
  [dir]
  (let [parts (str/split dir #"/")
        org (nth parts 1) name (nth parts 2)
        slug (str org "/" name)
        vis (get org-visibility org "--private")]
    (cond
      ;; commit ゼロの placeholder。`gh repo create --source --push` は push する
      ;; ものが無いので使えない。空 repo だけ作り、着地は server-commit! の
      ;; ルートコミット経路に任せる（ローカル checkout には一切触らない）。
      (not (gitc dir "rev-parse" "--verify" "--quiet" "HEAD"))
      (if (gh-str "api" (str "repos/" slug) "--jq" ".full_name")
        (do (println (format "  → %s は既存（commit ゼロのローカル）" slug)) slug)
        (let [{:keys [exit err]} (sh "gh" "repo" "create" slug vis)]
          (if (zero? exit)
            (do (println (format "  → created empty %s (%s) — root commit で着地させる"
                                 slug (subs vis 2)))
                slug)
            (do (println (format "  → repo 作成に失敗: %s" (str/trim (str err)))) nil))))

      :else
      (if (gh-str "api" (str "repos/" slug) "--jq" ".full_name")
        (do (println (format "  → GitHub に %s は既存。remote を追加するだけ。" slug))
            (gitc dir "remote" "add" "origin" (str "https://github.com/" slug ".git"))
            slug)
        (let [{:keys [exit err]} (sh "gh" "repo" "create" slug vis "--source" dir "--remote" "origin" "--push")]
          (if (zero? exit)
            (do (println (format "  → created %s (%s) + pushed" slug (subs vis 2))) slug)
            (do (println (format "  → repo 作成に失敗: %s" (str/trim (str err)))) nil)))))))

;; ---------- 1 リポの処理 ----------

(defn- plan-repo [dir]
  (let [status (remove str/blank? (str/split-lines (or (gitc dir "status" "--porcelain") "")))
        untracked-raw (->> status (filter #(str/starts-with? % "??")) (map #(subs % 3)))
        ;; porcelain のステータス2文字を見る。削除（D）は載せない — 古い working
        ;; tree の削除をそのまま main に適用すると、その repo で他人が追加した
        ;; ファイルを消しうる。rename（R）は "old -> new" 形式なので new 側を採る。
        ;; 実測 2026-07-25: 削除エントリを読みに行って
        ;; ENOENT: orgs/kotoba-lang/com-8th-wall/schema/8th_wall.kotoba で
        ;; fleet 実行が 28/220 repo で落ちた。
        tracked-rows (->> status (remove #(str/starts-with? % "??")))
        deleted (->> tracked-rows (filter #(re-find #"^.?D" %)) (map #(str/trim (subs % 2))) vec)
        tracked (->> tracked-rows
                     (remove #(re-find #"^.?D" %))
                     (map #(let [p (str/trim (subs % 2))]
                             (if (str/includes? p " -> ") (second (str/split p #" -> ")) p)))
                     (filter #(.exists (io/file dir %)))
                     vec)
        ;; ディレクトリ表記（`?? foo/`）は展開する
        untracked (mapcat (fn [p]
                            (if (str/ends-with? p "/")
                              (->> (or (gitc dir "ls-files" "--others" "--exclude-standard" "--" p) "")
                                   str/split-lines (remove str/blank?))
                              [p]))
                          untracked-raw)
        grouped (group-by #(classify-file dir %) untracked)]
    {:dir dir :slug (repo-slug dir) :base (default-branch dir)
     :additive (vec (:take grouped))
     :deleted deleted
     :skipped (into {} (for [[k v] grouped :when (not= k :take)] [k (vec v)]))
     :tracked (vec tracked)}))

(declare land-branches!)

(defn- land-repo! [{:keys [dir slug base additive skipped tracked deleted]}]
  ;; canonical 化はここ（着地対象がある repo だけ）。plan 段階ではやらない。
  (let [slug (when (or (seq additive) (seq tracked))
               (if slug
                 (canonical-slug slug)
                 ;; remote が無いなら作る（オーナー指示 2026-07-25「remote がなければ
                 ;; repo を作って ok」）。dry-run では作らない。
                 (when apply?
                   (println (format "\n%s  (remote 無し → 作成する)" dir))
                   (create-remote! dir))))]
  (println (format "\n%s  (%s)" dir (or slug "no-remote")))
  (when (seq deleted)
    (println (format "  skip deleted        %d 件（削除は main に適用しない）: %s"
                     (count deleted) (str/join ", " (take 4 deleted)))))
  (doseq [[k v] skipped]
    (println (format "  skip %-18s %d 件: %s" (name k) (count v)
                     (str/join ", " (take 4 v)))))
  (cond
    (nil? slug) (println "  → remote が無いので着地先が無い。報告のみ。")
    (and (empty? additive) (empty? tracked)) (println "  → 着地対象なし")
    :else
    (if-not apply?
      (do (when (seq additive) (println (format "  plan :additive  %d files → PR → merge" (count additive))))
          (when (seq tracked) (println (format "  plan :review    %d files → PR のみ（merge しない）" (count tracked)))))
      (let [adir (archive! dir (concat additive (mapcat val skipped)))
            base-map (base-blobs slug base)
            [additive landed-additive] (drop-already-landed dir base-map additive)
            [tracked landed-tracked] (drop-already-landed dir base-map tracked)]
        (println (format "  archived → %s" adir))
        (when (seq (concat landed-additive landed-tracked))
          (println (format "  already landed on %s（内容一致でスキップ）: %d 件"
                           base (count (concat landed-additive landed-tracked)))))
        (when (and (empty? additive) (empty? tracked))
          (println "  → 全て着地済み。新規 PR なし。"))
        ;; :additive — untracked のみ。main のどの行も書き換えないので merge する。
        (when (seq additive)
          (let [br (str "agent/cleanup-land-" stamp)
                msg (str "cleanup: land untracked WIP (" (count additive) " files)\n\n"
                         "These files existed only in the shared west checkout — on no branch,\n"
                         "on no remote. A single `git checkout` there would have destroyed them.\n"
                         "Purely additive: none of these paths exist on " base ", so no existing\n"
                         "line is rewritten. Landed by scripts/cleanup-land.cljs (skill\n"
                         "git-cleanup-conflict); originals archived under\n"
                         ".git/stash-archive-" stamp "/ in the operator's checkout.\n\n"
                         "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>")]
            (if-let [{:keys [files]} (server-commit! slug dir base additive br msg)]
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
              (println "  :review   commit に失敗（報告のみ、ローカルは無傷）"))))
        (when branches? (land-branches! dir slug base)))))))

(defn- live-branches
  "default branch から到達できないローカル branch。"
  [dir base]
  (->> (str/split-lines (or (gitc dir "for-each-ref" "--format=%(refname:short)" "refs/heads/") ""))
       (remove #{"" "main" "master" "synced/main" "git-annex" "manifest-rev"})
       (remove #(gitc dir "merge-base" "--is-ancestor" % (str "origin/" base)))
       vec))

(defn- land-branches!
  "`:branches` クラス。**merge は決してしない** — 放棄された実験・意図的な分岐・
  force-push 済み履歴が見分けられない。やるのは保全（push）とレビュー導線（PR）だけ。

  PR 照会は 1 branch = 1 API 往復なので、branch farm（実測: kotoba-lang/webgpu は
  ローカル branch 67本、slides は 90本超）では打ち切って必ず報告する。"
  [dir slug base]
  (let [live (live-branches dir base)
        cap 20]
    (when (seq live)
      (println (format "  branches: 未着地 %d 本" (count live)))
      (if (> (count live) cap)
        (println (format "  → %d 本は上限 %d 超のため未処理（branch farm。個別に扱うこと）"
                         (count live) cap))
        (doseq [b live]
          (let [has-remote? (gitc dir "rev-parse" "--verify" "--quiet" (str "refs/remotes/origin/" b))
                ahead (when has-remote?
                        (some-> (gitc dir "rev-list" "--count" (str "origin/" b ".." b)) str/trim parse-long))]
            (cond
              (not has-remote?)
              (let [{:keys [exit]} (sh "git" "-C" dir "push" "-u" "origin" b)]
                (println (format "    %-46s %s" b (if (zero? exit) "pushed (新規)" "push 失敗"))))

              (and ahead (pos? ahead))
              (let [{:keys [exit]} (sh "git" "-C" dir "push" "origin" b)]
                (println (format "    %-46s %s" b (if (zero? exit) (str "pushed (+" ahead ")") "push 失敗"))))

              :else
              (if-let [url (existing-pr slug b)]
                (println (format "    %-46s PR 既存 %s" b url))
                (if-let [url (open-pr! slug base b
                                       (str "cleanup: review un-landed branch " b)
                                       (str "⚠️ **Not auto-merged.** Opened so this branch is on a review path.\n\n"
                                            "`" b "` is pushed but not reachable from `" base "` and had no open PR.\n"
                                            "Abandoned experiments, deliberate forks and force-pushed histories all look\n"
                                            "alike from outside, so landing it is a human call.\n\n"
                                            "Opened by `scripts/cleanup-land.cljs` (skill `git-cleanup-conflict`).\n\n"
                                            "🤖 Generated with [Claude Code](https://claude.com/claude-code)"))]
                  (println (format "    %-46s PR 作成 %s" b url))
                  (println (format "    %-46s PR 作成に失敗（差分なし等）" b)))))))))))

;; ---------- main ----------

(println (str "cleanup-land " (if apply? "APPLY" "DRY-RUN（--apply で実行）")))
(println "分類: :additive=untracked のみ→merge / :review=tracked 変更→PR のみ / annex は対象外")

(def repos
  (->> (sh "find" "orgs" "-maxdepth" "3" "-name" ".git" "-type" "d")
       :out str/trim str/split-lines sort
       (map #(subs % 0 (- (count %) 5)))
       (remove annex?)
       (filter (fn [d] (if only-names (some #(str/ends-with? d (str "/" %)) only-names) true)))))

(def plans (->> repos (map plan-repo) (filter #(or (seq (:additive %)) (seq (:tracked %)) (seq (:skipped %))))))
(def selected (if max-repos (take max-repos plans) plans))
(def dropped (- (count plans) (count selected)))

(println (format "\n対象 %d repo（--max により %d repo を打切り）" (count selected) dropped))
(doseq [p selected] (land-repo! p))

(println (format "\n完了: %d repo 処理 / additive=%d repo / review=%d repo"
                 (count selected)
                 (count (filter #(seq (:additive %)) selected))
                 (count (filter #(seq (:tracked %)) selected))))
(when (pos? dropped)
  (println (format "⚠ --max で %d repo を処理していない。再実行して残りを処理すること。" dropped)))
(println "※ ローカルの WIP は一切削除していない（archive + 着地のみ）。")

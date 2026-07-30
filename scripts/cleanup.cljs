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

;; ---------- survey の実行形（2026-07-26 全面改訂） ----------
;;
;; 実測事故（2026-07-26）: この section は **一度も完走していなかった**。orgs 配下は
;; 3,690 repo あり、旧実装は repo ごとに `status --porcelain` を回した上で、さらに
;; **branch ごとに `gh pr list` のネットワーク往復**を挟んでいた。30 分の timeout で
;; kill され、`println` 済みの見出しだけが残り、肝心の行と集計は 1 件も出ないまま
;; 終了した。結果として出力は「UNLANDED な子リポは無い」と読める空セクションになり、
;; 実際には net-kotobase に未 push commit + untracked WIP があったのに素通りした。
;; これは runbook 自身が禁じている silent truncation（"a truncated survey that looks
;; complete is worse than a slow one"）の実例である。
;;
;; 対策は 4 つ:
;;   1. **2 フェーズ化**。phase 1 はローカル git のみ（ネットワーク往復ゼロ）で全 repo を
;;      走査する。`gh` 照会は phase 2 に隔離し、phase 1 を絶対にネットワークで詰まらせない。
;;   2. **ストリーミング出力**。危険な repo（untracked あり）は見つけた瞬間に 1 行出す。
;;      途中で kill されても、そこまでの真実は画面に残る（旧実装は全部失った）。
;;   3. **予算と打切りの明示**。phase 2 の API 往復に全体上限を置き、打ち切ったら
;;      件数を必ず報告する。黙って切らない。
;;   4. **annex repo は status を撮らない**。m365-archive は untracked 15,945 /
;;      dirty 122,792 を常時抱えており、`status --porcelain` 自体が極端に重い。
;;      annex は untracked/dirty が既定状態なので、そもそも数える必要がない。

(def ^:private pr-budget
  "phase 2 で許す `gh pr list` の総往復数。超えたら打ち切って件数を報告する。"
  200)

(def ^:private pr-spent (atom 0))
(def ^:private pr-truncated (atom 0))

(defn- eprogress [s] (.error js/console s))

(def ^:private junk-re
  ;; ビルド副産物。untracked として数えると本物の未着地 WIP が埋もれる。
  ;; 実測 2026-07-26: untracked を持つ 167 repo のうち大半が `.cpcache/` か
  ;; `target/` の 1 エントリだけで、直前の cleanup-land 実行はこれを 16 本の
  ;; PR にして出してしまっていた（全 close 済み）。数えるが別枠で報告する。
  #"(^|/)(\.cpcache|target|node_modules|\.nbb|dist|\.shadow-cljs|\.wrangler|\.clj-kondo|\.lsp|\.cljs_node_repl)/?$|\.log$|(^|/)\.DS_Store$")

(defn- junk-path? [line]
  (boolean (re-find junk-re (str/trim (subs line (min 3 (count line)))))))

(defn- already-upstream?
  "その untracked path の内容が、既に origin/<default> の同じ path に同一内容で
  存在するか。存在すれば「未着地の WIP」ではなく「ローカル checkout が古いだけ」。

  実測 2026-07-27: untracked 上位 8 repo（inc 95件 / kotobase 24 / toshokan 23 /
  kotoba-git 17 / org-threejs 12 / mangaka 11 / shell 9 / net-kotobase 8）を
  cleanup-land にかけたところ **全件が内容一致で既に main にあった**。つまり
  untracked= は runbook が最も危険と位置づけるマーカーなのに、実際には安全な
  ケースを大量に上位に押し上げていた。ビルド副産物(junk-path?)とは別クラスの
  誤検知なので、別に潰す。判定はローカルのみ（origin/<default> は fetch 済み前提）。"
  [dir default path]
  ;; gitc は `git -C dir` なので path は dir 相対でよい。
  (let [local-sha (some-> (gitc dir "hash-object" path) str/trim)
        upstream-sha (some-> (gitc dir "rev-parse" (str "origin/" default ":" path)) str/trim)]
    (and local-sha upstream-sha (= local-sha upstream-sha))))

(def ^:private branch-cap
  "1 repo あたり per-branch 解析（merge-base + ahead-of-remote = 3 git 呼び出し）を
  許す branch 数。実測: kotoba-lang/webgpu は 67 本、slides は 90 本超あり、
  これだけで数百回の git 起動になって survey が完走できなかった。上限を超えたら
  解析を打ち切るが、PR 照会と同じ規律で件数を必ず報告する。"
  20)

(defn- survey-local
  "phase 1: ローカル git のみ。ネットワークに一切触らない。"
  [dir]
  (let [annex   (annex? dir)
        br      (str/trim (or (gitc dir "rev-parse" "--abbrev-ref" "HEAD") ""))
        stash   (count (remove str/blank? (str/split-lines (or (gitc dir "stash" "list") ""))))
        ;; annex/DataLad の untracked/dirty は既定状態。数えないだけでなく、
        ;; status 自体を撮らない（12 万件の working tree walk を避ける）。
        status  (when-not annex
                  (remove str/blank? (str/split-lines (or (gitc dir "status" "--porcelain") ""))))
        untracked-lines (filter #(str/starts-with? % "??") status)
        junk-untracked (count (filter junk-path? untracked-lines))
        untracked (- (count untracked-lines) junk-untracked)
        dirty     (- (count status) (count untracked-lines))
        locals  (remove #{"" "main" "master" "synced/main" "git-annex" "manifest-rev"}
                        (str/split-lines (or (gitc dir "for-each-ref"
                                                   "--format=%(refname:short)" "refs/heads/") "")))
        default (default-branch dir)
        ;; branch 数が上限を超える repo は per-branch 解析を打ち切る（下で報告）。
        branch-analysis-skipped (when (> (count locals) branch-cap) (count locals))
        ;; 既に default から到達可能な branch は着地済み。PR の有無を問わない。
        live    (if branch-analysis-skipped
                  []
                  (remove #(merged-into-default? dir % default) locals))
        ;; ahead-of-remote は branch ごとに 2 回呼ばれていた（unpushed 判定と
        ;; pushed-live 判定）。1 回に畳んで分岐する。
        aheads  (into {} (map (fn [b] [b (ahead-of-remote dir b)]) live))
        unpushed (vec (for [[b n] aheads
                            :when (or (= n :no-remote) (and (number? n) (pos? n)))]
                        (str b ":" (if (= n :no-remote) "no-remote" n))))
        pushed-live (vec (for [[b n] aheads :when (and (number? n) (zero? n))] b))]
    {:dir dir :branch br :stash stash :dirty dirty :untracked untracked
     :junk-untracked junk-untracked
     :branch-analysis-skipped branch-analysis-skipped
     :locals locals :unpushed unpushed :pushed-live pushed-live
     :annex? annex :slug (repo-slug dir)
     ;; phase 1 の時点で確定する未着地。nopr は phase 2 で足す。
     :local-unlanded? (boolean (if annex
                                 (seq unpushed)
                                 (or (pos? untracked) (pos? dirty) (seq unpushed)
                                     branch-analysis-skipped)))}))

(defn- survey-prs
  "phase 2: push 済み未着地 branch にだけ `gh pr list` を当てる。予算超過は打切り、
  件数は :nopr-skipped として必ず報告する（黙って切らない）。"
  [{:keys [pushed-live slug] :as row}]
  (let [pr-cap 20
        over-cap? (> (count pushed-live) pr-cap)
        budget-left (- pr-budget @pr-spent)
        skip? (or over-cap? (nil? slug) (< budget-left (count pushed-live)))]
    (if skip?
      (do (when (seq pushed-live) (swap! pr-truncated + (count pushed-live)))
          (assoc row :nopr [] :nopr-skipped (when (seq pushed-live) (count pushed-live))))
      (let [nopr (vec (remove #(do (swap! pr-spent inc) (open-pr-for slug %)) pushed-live))]
        (assoc row :nopr nopr :nopr-skipped nil)))))

(defn- finalize [row]
  (assoc row :unlanded? (boolean (or (:local-unlanded? row)
                                     (seq (:nopr row))
                                     (:nopr-skipped row)))))

(def js-fs (js/require "node:fs"))

(defn- real-path [p] (try (.realpathSync js-fs p) (catch :default _ nil)))

(defn- own-repo-root?
  "`dir` それ自身が git repo の toplevel か。**`.git` が存在するだけでは repo ではない。**

  git は無効な `.git`（objects/refs を持たない中断クローン、あるいは中身が
  `stash-archive-*` だけのスタブ）を見つけると *上方向に探索を続け*、superproject の
  `.git` を掴む。その状態で `git -C <dir> status` を叩くと **superproject の**
  untracked が返るので、この survey はそれを子リポの WIP と誤認して報告する（書込側の cleanup-land.cljs では
  `com-junkawasaki/root` への commit 試行にまで至った）。

  実測 2026-07-30: `find orgs -maxdepth 3 -name .git -type d` が拾った 4 path
  （com-junkawasaki/net-kotobase-commoncrawler・gftdcojp/ai-gftd-itonami・
  gftdcojp/net-isekai-gen・kotoba-lang/com-line-messaging）が全て superproject に
  解決し、4 件とも slug が `com-junkawasaki/root` になった。うち
  net-kotobase-commoncrawler の `.git` は中身が `stash-archive-20260730/` だけ
  ——**前回の cleanup 自身の archive! が作った産物**で、それが次の survey に
  「ここは repo だ」と誤認させる自己増殖ループになっていた。commit が失敗したのは
  偶然で、設計上の防御ではなかった。"
  [dir]
  (let [top (some-> (gitc dir "rev-parse" "--show-toplevel") str/trim not-empty)]
    (boolean (and top (some? (real-path dir)) (= (real-path top) (real-path dir))))))

(when-not skip-sub?
  (hr "子リポ survey: UNLANDED 判定（detached-HEAD + manifest-rev のみは通常状態）")
  (println "凡例: untracked=commit すらされていない / unpushed=push 未了 / nopr=push 済みだが PR 無し")
  (println)
  (let [repos (->> (sh "find" "orgs" "-maxdepth" "3" "-name" ".git" "-type" "d")
                   :out str/trim str/split-lines (remove str/blank?) sort
                   (map #(subs % 0 (- (count %) 5)))
                   (filter own-repo-root?)
                   (map #(str % "/.git")))
        total (count repos)
        ;; 0 件は「fleet が綺麗」ではなく「orgs/ が展開されていない checkout で
        ;; 走らせた」の意味である（west project は submodule ではないので、
        ;; superproject の worktree には orgs/ が存在しない）。旧実装はこの場合も
        ;; 空セクションを出すだけで、健全な fleet と見分けがつかなかった。
        _ (when (zero? total)
            (println "⚠ orgs/ 配下に子リポが 1 件も見つからない。")
            (println "  これは「未着地の作業が無い」という意味ではない — orgs/ が展開されて")
            (println "  いない checkout（superproject の worktree 等）で走らせた可能性が高い。")
            (println "  west project は submodule ではないので worktree には展開されない。")
            (println "  superproject 本体の checkout（west update 済み）で再実行すること。"))
        _ (eprogress (str "phase 1 (local only): " total " repos ..."))
        ;; phase 1: ローカルのみ。危険な repo は見つけた瞬間に出す（途中で kill されても残る）。
        local-rows
        (doall
         (map-indexed
          (fn [i g]
            (when (zero? (mod i 250))
              (eprogress (str "  phase 1 " i "/" total)))
            (let [row (survey-local (.substring g 0 (- (count g) 5)))]
              (when (and (pos? (:untracked row)) (not (:annex? row)))
                (println (format "! %-56s untracked=%d dirty=%d  ← commit すらされていない"
                                 (:dir row) (:untracked row) (:dirty row))))
              row))
          repos))
        ;; phase 1.5: untracked を持つ repo だけを精査する。phase 1 は `?? dir/` の
        ;; ように git がディレクトリで畳んだ行を数えるだけなので、ここで -uall に
        ;; 展開し、1 ファイルずつ「既に upstream に同一内容で存在するか」を見る。
        ;; 対象を untracked>0 の repo に絞るので全走査のコストは増えない。
        untracked-repos (filter #(pos? (:untracked %)) local-rows)
        _ (eprogress (str "phase 1.5 (untracked containment): " (count untracked-repos) " repos"))
        refined (into {}
                      (map (fn [row]
                             (let [dir (:dir row)
                                   default (default-branch dir)
                                   files (->> (or (gitc dir "status" "--porcelain" "-uall") "")
                                              str/split-lines
                                              (filter #(str/starts-with? % "??"))
                                              (map #(str/trim (subs % 2)))
                                              (remove str/blank?)
                                              (remove junk-path?))
                                   upstream (count (filter #(already-upstream? dir default %) files))
                                   real (- (count files) upstream)]
                               [dir (assoc row
                                           :untracked real
                                           :untracked-upstream upstream
                                           :local-unlanded?
                                           (boolean (if (:annex? row)
                                                      (seq (:unpushed row))
                                                      (or (pos? real) (pos? (:dirty row))
                                                          (seq (:unpushed row))
                                                          (:branch-analysis-skipped row)))))])))
                      untracked-repos)
        local-rows (mapv #(get refined (:dir %) %) local-rows)
        candidates (filter #(seq (:pushed-live %)) local-rows)
        _ (eprogress (str "phase 2 (gh pr lookup): " (count candidates)
                          " repos have pushed-but-unlanded branches"))
        pr-map (into {} (map (juxt :dir identity)
                             (map survey-prs candidates)))
        rows  (->> local-rows
                   (map #(finalize (get pr-map (:dir %) (assoc % :nopr []))))
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
                    nopr-skipped annex? unlanded?] :as row} rows]
      (let [parts (cond-> []
                    unlanded?                           (conj "UNLANDED")
                    annex?                              (conj "annex(untracked/dirty は既定状態)")
                    (and (pos? untracked) (not annex?)) (conj (str "untracked=" untracked))
                    (and (pos? dirty) (not annex?))     (conj (str "dirty=" dirty))
                    (seq unpushed)                      (conj (str "unpushed=" (str/join "," unpushed)))
                    (seq nopr)                          (conj (str "nopr=" (str/join "," nopr)))
                    nopr-skipped                        (conj (str "nopr=?(" nopr-skipped " branches, PR照会を打切り)"))
                    (:branch-analysis-skipped row)       (conj (str "branches=?(" (:branch-analysis-skipped row) " 本, branch解析を打切り)"))
                    (pos? (:junk-untracked row 0))       (conj (str "junk-untracked=" (:junk-untracked row) "(ビルド副産物・着地対象外)"))
                    (pos? (:untracked-upstream row 0))   (conj (str "untracked-but-upstream=" (:untracked-upstream row) "(内容は既に main にある・着地不要)"))
                    (pos? stash)                        (conj (str "stash=" stash))
                    (and (not= branch "HEAD") (not= branch "")) (conj (str "branch=" branch))
                    (seq locals)                        (conj (str "locals=" (count locals))))]
        (println (format "%-58s %s" dir (str/join "; " parts)))))
    (println)
    (println (format "UNLANDED な子リポ=%d / 掲載=%d / 走査=%d（untracked を持つ repo=%d、annex 除外=%d）"
                     (count (filter :unlanded? rows)) (count rows) total
                     (count (filter #(and (pos? (:untracked %)) (not (:annex? %))) rows))
                     (count (filter :annex? rows))))
    ;; 打切りは必ず報告する。「完走したように見える不完全な survey」は
    ;; 遅い survey より悪い（runbook :unlanded :caps）。
    (println (format "PR 照会: %d/%d 往復を使用%s"
                     @pr-spent pr-budget
                     (if (pos? @pr-truncated)
                       (format " / ⚠ %d branch は照会を打切り（nopr=? として上に表示）" @pr-truncated)
                       "（打切りなし）")))
    (println "※ このセクションは完走した（この行が出ていれば途中 kill されていない）。")
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

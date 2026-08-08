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
(def node-os (js/require "node:os"))

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
  #"(?i)(^|/)(\.env($|\.)|.*\.(pem|key|p12|pfx|jks|keystore|jwk)$|id_(rsa|ed25519)|identity\.(edn|json|yaml|yml)$|.*secret.*|.*credential.*|\.kagi/|\.npmrc$|\.netrc$)")

;; パス名だけの判定は実際に破れた（2026-07-26）。actor の identity.json は
;; `identity\.edn$` にも `.*secret.*` にも当たらず、private-b64 を含んだまま
;; public repo cloud-itonami/cloud-itonami-isic-6310 に merge された
;; （sibling の 7810/6399 は .gitignore に identity.json があり無事だった）。
;; 名前が何であれ鍵素材そのものを撃ち落とす content 側の網を足す。
(def ^:private secret-content-re
  #"(?i)(private[-_](b64|key|pem|jwk)|BEGIN [A-Z ]*PRIVATE KEY|secret[-_]key|mnemonic)")

(defn- secret-content?
  "中身に鍵素材のマーカーがあるか。読めなければ false（fail-open にはしない —
   読めないファイルは size/パス側の網で拾う）。"
  [f]
  (try
    (boolean (re-find secret-content-re (.toString (.readFileSync node-fs (.getPath f)) "utf8")))
    (catch :default _ false)))

(def ^:private junk-re
  "着地させないビルド副産物 / ランタイム残骸。

  `cljs-runtime` と `.fleet-run` は 2026-07-30 に追加。実測:
  orgs/network-awai/cloud-murakumo の untracked 111 件のうち **107 件がこの2つ**
  だった（`public/mobile/js/cljs-runtime/` に cljs コンパイラ出力 97 件、
  `organism/.fleet-run/tmp/` に fleet 実行の audit/lease/journal EDN 7 件）。
  `.shadow-cljs` は入っていたが、shadow が吐く *出力先* である cljs-runtime は
  入っていなかったため、:additive（= PR を作って **merge する**クラス）として
  111 件が計上されていた。本物の新規コンテンツは legal/docs の 4 件だけで、
  そのまま流していればコンパイラ出力を repo に commit するところだった。

  `.*-cache\\.json` は contracts/cache/solidity-files-cache.json（foundry の
  ビルドキャッシュ）向け。`cache` 単体を足すと正当な `cache/` ディレクトリまで
  巻き込むので、ファイル名の形で絞る。"
  #"(^|/)(node_modules|\.cpcache|\.shadow-cljs|cljs-runtime|\.fleet-run|\.wrangler|target|dist|build|out|\.DS_Store|.*\.log|.*-cache\.json)(/|$)")

(def max-bytes (* 2 1024 1024))

(def ^:private workflow-policy-re
  "GitHub Actions の workflow ファイル。**着地させない。**

  理由は 2 つあり、順序が大事（policy が先、token は結果論）:

  1. **repo-wide mandatory な禁止事項である。** CLAUDE.md 「CI/CD は murakumo fleet。
     GitHub Actions を使わない」（ADR-2607300900、オーナー指示 2026-08-05）は
     『新しい `.github/workflows/*.yml` を書かない』と明示している。検査を足したいなら
     `scripts/fleet-ci/gates.edn` に足す。cleanup が共有 checkout に落ちていた
     workflow ファイルを拾って main に載せるのは、この規則を裏口から破ることになる。
  2. そもそも **token に `workflow` OAuth scope が無いので書き込めない**。

  2 だけに任せると失敗の見え方が最悪になる: GitHub は workflow ファイルへの書き込みを
  **403 ではなく 404** で拒否するので、`gh failed: gh: Not Found (HTTP 404)` としか出ず、
  **repo が存在しないと誤読する**（CLAUDE.md がこの誤読を名指しで警告している）。

  実測 2026-08-08: `kotoba-lang/user-test` の untracked は `.github/workflows/ci.yml`
  1 件だけで、これが 404 になり `:additive commit に失敗` として報告された。repo は
  実在し archived でもなく default=main で健在——原因は scope だった。着地しなかったこと
  自体は正しい（policy が禁じている）が、**理由が伝わらないまま失敗していた**ので、
  policy 側で先に落として名前の付いた skip クラスとして報告する。"
  #"(^|/)\.github/workflows/")

(defn- classify-file [dir path]
  (let [f (io/file dir path)
        size (try (.-size (.statSync node-fs (.getPath f))) (catch :default _ 0))]
    (cond
      (re-find credential-re path)      :skip-credential
      (re-find workflow-policy-re path) :skip-workflow-policy
      (re-find junk-re path)            :skip-junk
      (> size max-bytes)                :skip-large
      (secret-content? f)               :skip-credential
      :else                             :take)))

(defn- annex? [dir]
  (or (.exists (io/file dir ".git" "annex")) (.exists (io/file dir ".datalad"))))

(def primary-remote
  "この repo の upstream remote 名。**`origin` 決め打ちにしてはいけない。**

  west は remote を manifest の remote 名（`cloud-itonami` / `kotoba-lang` /
  `gftdcojp` …）で作るので、west 管理下の repo には `origin` が無いことの方が
  多い。実測 2026-07-30: ローカルに存在する west project 3,353 のうち **1,644**
  に `origin` が無かった。`origin` 決め打ちだと repo-slug が nil に落ち、その
  repo は `(no-remote)` → 「remote が無いので着地先が無い。報告のみ。」として
  静かに全件スキップされる。この行は所見のように読めて失敗に見えないため、
  **バックログの実サイズが過小に見える**のが最大の害だった（着地漏れそのものより、
  漏れていることが分からないことが問題）。

  `origin` があればそれを優先し（人が clone した repo・ensure-remote! が作った
  repo はこちら）、無ければ最初の remote を使う。remote が1つも無ければ nil で、
  これは本当に着地先が無いケース。"
  (memoize
   (fn [dir]
     (let [rs (->> (str/split-lines (or (gitc dir "remote") ""))
                   (map str/trim) (remove str/blank?) vec)]
       (cond (some #{"origin"} rs) "origin"
             (seq rs)              (first rs))))))

(defn- repo-slug
  "upstream remote の URL から slug を採り、**GitHub 上の canonical 名に解決する**。

  rename 追従が必須。この workspace は `-clj` サフィックス廃止（ADR-2607102200
  addendum 14）等で改名が多く、ローカルの remote URL は旧名のまま残る。GitHub は
  GET と `-f` 形式の POST はリダイレクトするが、`--input` 付き POST には HTTP 307
  を返し `gh` はそれを追わない — 実測 2026-07-25: kotoba-lang/kotoba-git（現
  kotoba-lang/bonsai）で blob 作成が全件 307 で落ちた。読み取りは通るのに書き込み
  だけ落ちるので、canonical 化しないと「なぜか commit だけ失敗する」形で現れる。"
  [dir]
  (when-let [r (primary-remote dir)]
    (some-> (gitc dir "remote" "get-url" r) str/trim
            (as-> u (second (re-find #"github\.com[:/](.+?)(?:\.git)?$" u))))))

(defn- canonical-slug
  "raw slug -> GitHub 上の現在名。**着地対象がある repo にだけ呼ぶこと**。
  planning 段階で全 repo に対して呼ぶと ~700 回の API 往復になり、実測で
  25 分経っても plan が終わらなかった（かつ rate limit を無駄に消費する）。"
  [raw]
  (when raw (or (gh-str "api" (str "repos/" raw) "--jq" ".full_name") raw)))

(defn- default-branch [dir]
  (or (when-let [r (primary-remote dir)]
        (some-> (gitc dir "symbolic-ref" "--quiet" (str "refs/remotes/" r "/HEAD"))
                str/trim
                (str/replace (re-pattern (str "^refs/remotes/" r "/")) "")
                not-empty))
      "main"))

(defn- real-path [p] (try (.realpathSync node-fs p) (catch :default _ nil)))

(defn- own-repo-root?
  "`dir` それ自身が git repo の toplevel か。**`.git` が存在するだけでは repo ではない。**

  git は無効な `.git`（objects/refs を持たない中断クローン、あるいは中身が
  `stash-archive-*` だけのスタブ）を見つけると *上方向に探索を続け*、superproject の
  `.git` を掴む。その状態で `git -C <dir> status` を叩くと **superproject の**
  untracked が返るので、この script はそれを子リポの :additive 集合と誤認し、
  `com-junkawasaki/root` に commit しようとする。

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

;; ---------- archive（drop はしないが、着地前に必ず退避する） ----------

(defn- archive! [dir untracked]
  ;; `.git` を新規に作ってはならない。`:recursive true` の mkdir は `.git` 自体を
  ;; 生やすので、repo でない path にスタブ `.git` が残り、次回の survey がそこを
  ;; repo と誤認する（own-repo-root? の docstring にある自己増殖ループの発生源）。
  (when-not (.existsSync node-fs (str dir "/.git"))
    (throw (js/Error. (str "archive!: " dir " に .git が無い。repo でない path に "
                           ".git を作らない（own-repo-root? を通してから呼ぶこと）"))))
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

(defn- three-way-blob!
  "tracked ファイルを載せるときの blob。**作業ツリーの中身をそのまま置かない。**

  この関数が存在する理由（2026-07-26/27 の実害）: server-commit! は base_tree に
  default branch の tree を使い、その上へ作業ツリーのファイルを丸ごと重ねていた。
  west の子リポは pin された SHA の **detached HEAD が正常状態**なので、checkout は
  default branch より古いのが普通である。したがって丸ごと上書きすると、pin 以降に
  default branch が足した行が全部消える。これが `cleanup: preserve uncommitted
  tracked changes` PR の削除の正体で、17 本が merge されて main から約 913 行が
  失われた（langchain の schema-from-tx-data、kotobase-protocols の
  ADR-2607171700/2607172210 文書、toshokan の README 205 行 等）。checkout が古い
  ことは異常ではないので、直すべきは checkout ではなくここ。

  正しい内容は「作業ツリーの編集を default branch の上に載せ替えたもの」= 3-way
  merge（base = その checkout の HEAD 版、ours = 作業ツリー、theirs = default 版）。
  衝突したら **その 1 ファイルを諦めて報告する**（conflict marker 入りのファイルを
  main に載せる方が、載せないより遥かに悪い）。HEAD に無い（= 新規 tracked）や
  default に無いファイルは 3-way の意味が無いので作業ツリーをそのまま使う。"
  [slug dir base path]
  (let [tmp (str (.tmpdir node-os) "/cl-" (str/replace path #"[^A-Za-z0-9._-]" "_"))
        head-f (str tmp ".head") main-f (str tmp ".main")
        head-out (sh "git" "-C" dir "show" (str "HEAD:" path))
        main-out (sh "git" "-C" dir "show"
                     (str (or (primary-remote dir) "origin") "/" base ":" path))]
    (if-not (and (zero? (:exit head-out)) (zero? (:exit main-out)))
      ;; 片側に存在しない → 3-way の基準が無い。従来どおり作業ツリーを載せる。
      (create-blob! slug dir path)
      (let [wt-f (str tmp ".wt")]
        (.writeFileSync node-fs head-f (:out head-out))
        (.writeFileSync node-fs main-f (:out main-out))
        (.copyFileSync node-fs (str dir "/" path) wt-f)
        (let [{:keys [exit]} (sh "git" "merge-file" "-q" wt-f head-f main-f)]
          (if (neg? exit)
            (do (println (format "  skip 3-way 失敗     %s" path)) nil)
            (if (pos? exit)
              (do (println (format "  skip conflict       %s（%d hunk が衝突。手動で解決すること）" path exit)) nil)
              (gh-input! (str "repos/" slug "/git/blobs")
                         {:content (.toString (.readFileSync node-fs wt-f) "base64")
                          :encoding "base64"} ".sha"))))))))

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
  "base 側と比べて 3 つに分ける。-> [残り 着地済み base-に既存で内容違い]

  3 つ目が **:additive の安全床**。以前はここが 2-way で、「base に存在し内容が
  一致」だけを落としていた。**base に存在するが内容が違う**パスは :additive に
  残り、`server-commit!` が base の tree にそのまま上書き commit していた —
  `:additive` の定義（「main のどの行も書き換えない」）に真っ向から反する。

  実測事故 2026-07-30、kotoba-lang/compiler PR #444: untracked と報告された 5 件
  のうち 3 件（host_profile.clj / host_profile_test.clj / linear_resource_test.clj）
  は main に存在し、Phase B 抽出前の古い版で上書きされた。351 行が消え、
  `kotoba.artifact.core` の require が削除済み ns へ巻き戻り、ADR 0103 の
  ingress-methods 分離も消えて **main の test suite が起動不能**になった。
  commit message は「Purely additive: none of these paths exist on main」と
  書いており、その主張自体が偽だった。

  ローカルで untracked に見えることは、base に無いことを意味しない（別ブランチに
  parked された共有 checkout では日常的に起きる）。**判定は git status ではなく
  base tree に対して行う。** 内容が違うものは :review（PR のみ、auto-merge 禁止）
  に回す。"
  [dir base-map paths]
  (if (empty? base-map)
    [paths [] []]
    (let [on-base? (fn [p] (contains? base-map p))
          same?    (fn [p] (= (get base-map p) (local-blob-sha dir p)))
          {landed true differs false} (group-by same? (filter on-base? paths))]
      [(vec (remove on-base? paths)) (vec landed) (vec differs)])))

(defn- server-commit!
  "base branch の tip の上に paths を載せた commit を作り、branch ref を作る。
  branch が既にあれば ref は作らず、その ref を commit へ更新する。
  -> {:branch b :commit sha :files n} / nil"
  [slug dir base paths branch message & [three-way?]]
  ;; base が未作成（= commit が1つも無い新規 repo）なら parents 無し・base_tree 無しの
  ;; ルートコミットを作る。placeholder repo（例 kotoba-lang/org-threejs: branch
  ;; init_placeholder に commit ゼロ、ファイルは全部 untracked）はこの経路でしか
  ;; 着地できない。
  (let [base-sha (gh-str "api" (str "repos/" slug "/git/ref/heads/" base) "--jq" ".object.sha")
        base-tree (when base-sha
                    (gh-str "api" (str "repos/" slug "/git/commits/" base-sha) "--jq" ".tree.sha"))]
    (let [_ nil]
      (let [entries (keep (fn [p]
                            (when-let [sha (if three-way?
                                             (three-way-blob! slug dir base p)
                                             (create-blob! slug dir p))]
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

(defn- open-pr!
  "PR を作る。`draft?` の PR は GitHub 側が merge をブロックするので、
  「auto-merge するな」を本文のお願いではなく機構として強制できる。

  実測事故 2026-07-26/27: :review クラスの PR は本文に警告を書いていたが、
  タイトルは中立（`cleanup: preserve uncommitted tracked changes`）で draft でも
  なかったため、別セッションの一括 merge が mergeable なものとして 17 本を main に
  入れ、約 913 行が main から消えた（langchain の `schema-from-tx-data`、
  kotobase-protocols の ADR-2607171700/2607172210 文書、toshokan の README 205 行 等。
  8 本は復旧済み）。本文の警告は読まれない前提で設計する。"
  ([slug base branch title body] (open-pr! slug base branch title body false))
  ([slug base branch title body draft?]
   (let [args (cond-> ["gh" "pr" "create" "--repo" slug "--base" base "--head" branch
                       "--title" title "--body" body]
                draft? (conj "--draft"))
         {:keys [out exit]} (apply sh args)]
     (when (zero? exit) (str/trim out)))))

(defn- merge-pr! [slug url]
  (let [{:keys [exit err]} (sh "gh" "pr" "merge" url "--repo" slug "--merge")]
    (if (zero? exit) :merged (do (binding [*out* *err*] (println "  merge failed:" (str/trim (str err)))) :unmerged))))

(defn- existing-pr [slug branch]
  (some-> (gh-json "pr" "list" "--repo" slug "--state" "open" "--head" branch
                   "--json" "url" "--limit" "1")
          first :url))

(defn- prior-cleanup-pr
  "この tool が過去に開いた **未 close の** PR を、branch 名の *接頭辞* で探す。
  -> {:url ... :headRefName ...} / nil

  **なぜ接頭辞なのか（2026-08-08 の実測バグ）。** 呼び出し側は branch を
  `agent/cleanup-review-<stamp>` と日付入りで組み立てるのに、重複防止は
  `existing-pr slug br`（= その日の branch と完全一致）で行っていた。**stamp は
  今日の日付なので、この照合は同日中しか一致しえない。** 日をまたぐと必ず外れ、
  毎回 branch も PR も新規に作られる —— 重複防止の意図で置かれたガードが、
  構造的に発火できない位置にあった。

  実測: `agent/cleanup-review-*` は 20260803 / 20260805 / 20260806 / 20260808 の
  4 世代・22 PR を fleet に積んでいた。`etzhayyim/root#3376`(0803) と `#3392`(0808)
  は **file/line 集合が完全に一致**（+59008/-8421・36 files）で、5 日空けて同じ内容を
  2 度開いていた。`com-etzhayyim-kawaraban#27/#28` も同型。これらは人手で
  :close-superseded にするまで滞留し続ける —— backlog は放置の結果ではなく、
  **この関数が無いことによって毎日生成されていた**。

  接頭辞で拾えば、日付入り branch のまま過去世代（別 stamp）も掴める。掴んだら
  呼び出し側はその branch へ commit し直し、PR は開き直さず更新する（server-commit!
  は既存 ref を force 更新できる）。結果として repo あたり preservation PR は常に 1 本、
  中身は最新になる。

  :additive にも同じガードを効かせる。通常 :additive は開いた直後に merge される
  ので重複は残らないが、**merge が落ちた回**（CI 不通・conflict・権限）に PR が
  open のまま残り、翌日そこに 2 本目が積まれる経路は :review と同一である。"
  [slug prefix]
  (->> (gh-json "pr" "list" "--repo" slug "--state" "open"
                "--json" "url,headRefName" "--limit" "100")
       (filter #(str/starts-with? (str (:headRefName %)) prefix))
       first))

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

(declare land-branches! live-branches)

(defn- land-repo! [{:keys [dir slug base additive skipped tracked deleted]}]
  ;; canonical 化はここ（着地対象がある repo だけ）。plan 段階ではやらない。
  (let [branch-work? (and branches? (seq (live-branches dir base)))
        ;; raw-slug（= remote が実在するか）と slug（= canonical 化して着地に使う名）
        ;; を混同しないこと。slug は「着地対象がある repo」でだけ計算するので、
        ;; junk しか無い repo では nil になる。以前はこの nil をそのまま
        ;; `(no-remote)` と「remote が無いので着地先が無い」として印字していたため、
        ;; **remote が正常にある repo が remote 欠損として報告されていた**
        ;; （実測 2026-07-30: orgs/cloud-itonami/animeka は remote を3つ持つのに
        ;; `.cpcache` しか untracked が無いというだけで no-remote 表示。この誤表示は
        ;; 「所見」に見えて失敗に見えないので、バックログの実態を誤読させる）。
        raw-slug slug
        slug (when (or (seq additive) (seq tracked) branch-work?)
               (if raw-slug
                 (canonical-slug raw-slug)
                 ;; remote が無いなら作る（オーナー指示 2026-07-25「remote がなければ
                 ;; repo を作って ok」）。dry-run では作らない。
                 (when apply?
                   (println (format "\n%s  (remote 無し → 作成する)" dir))
                   (create-remote! dir))))]
  (println (format "\n%s  (%s)" dir (or slug raw-slug "no-remote")))
  (when (seq deleted)
    (println (format "  skip deleted        %d 件（削除は main に適用しない）: %s"
                     (count deleted) (str/join ", " (take 4 deleted)))))
  (doseq [[k v] skipped]
    (println (format "  skip %-18s %d 件: %s" (name k) (count v)
                     (str/join ", " (take 4 v)))))
  (cond
    (and (nil? slug) (nil? raw-slug))
    (println "  → remote が無いので着地先が無い。報告のみ。")

    ;; remote はある。着地対象が無いだけ（junk/credential/large を除いた結果ゼロ）。
    (nil? slug)
    (println "  → 着地対象なし（remote はある。skip 分のみ）")

    (and (empty? additive) (empty? tracked))
    (cond
      (not branches?) (println "  → 着地対象なし")
      apply?          (land-branches! dir slug base)
      :else           (println (format "  plan :branches  %d 本 → push / PR（merge しない）"
                                       (count (live-branches dir base)))))
    :else
    (if-not apply?
      ;; dry-run も base tree を引いて分類する。引かないと「:additive N files →
      ;; PR → merge」と表示したものが apply で :review に降格し、plan が嘘になる。
      (let [base-map (base-blobs slug base)
            [additive _ demoted] (drop-already-landed dir base-map additive)
            tracked (vec (concat tracked demoted))]
        (when (seq demoted)
          (println (format "  ⚠ untracked だが %s に既存・内容差あり: %d 件 → :review（auto-merge しない）"
                           base (count demoted)))
          (doseq [p demoted] (println (str "      " p))))
        (when (seq additive) (println (format "  plan :additive  %d files → PR → merge" (count additive))))
        (when (seq tracked) (println (format "  plan :review    %d files → PR のみ（merge しない）" (count tracked)))))
      (let [adir (archive! dir (concat additive (mapcat val skipped)))
            base-map (base-blobs slug base)
            [additive landed-additive demoted] (drop-already-landed dir base-map additive)
            [tracked landed-tracked tracked-differs] (drop-already-landed dir base-map tracked)
            ;; base に存在するのに untracked と報告されたものは :additive ではない。
            ;; :review へ落として auto-merge の対象から外す（PR #444 の再発防止）。
            tracked (vec (concat tracked tracked-differs demoted))]
        (println (format "  archived → %s" adir))
        (when (seq (concat landed-additive landed-tracked))
          (println (format "  already landed on %s（内容一致でスキップ）: %d 件"
                           base (count (concat landed-additive landed-tracked)))))
        (when (seq demoted)
          (println (format "  ⚠ untracked だが %s に既存・内容差あり: %d 件 → :review へ降格（auto-merge しない）"
                           base (count demoted)))
          (doseq [p demoted] (println (str "      " p))))
        (when (and (empty? additive) (empty? tracked))
          (println "  → 全て着地済み。新規 PR なし。"))
        ;; :additive — untracked のみ。main のどの行も書き換えないので merge する。
        (when (seq additive)
          ;; merge が落ちた回に open のまま残った PR を再利用する（:review と同じ経路）。
          (let [prior (prior-cleanup-pr slug "agent/cleanup-land-")
                br (or (:headRefName prior) (str "agent/cleanup-land-" stamp))
                msg (str "cleanup: land untracked WIP (" (count additive) " files)\n\n"
                         "These files existed only in the shared west checkout — on no branch,\n"
                         "on no remote. A single `git checkout` there would have destroyed them.\n"
                         "Purely additive: none of these paths exist on " base ", so no existing\n"
                         "line is rewritten. Landed by scripts/cleanup-land.cljs (skill\n"
                         "git-cleanup-conflict); originals archived under\n"
                         ".git/stash-archive-" stamp "/ in the operator's checkout.\n\n"
                         "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>")]
            (if-let [{:keys [files]} (server-commit! slug dir base additive br msg)]
              (let [url (or (:url prior)
                            (existing-pr slug br)
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
          ;; 過去世代の preservation PR があればその branch を再利用する。無いときだけ
          ;; 日付入りの新しい branch を切る（prior-cleanup-pr の docstring 参照）。
          (let [prior (prior-cleanup-pr slug "agent/cleanup-review-")
                br (or (:headRefName prior) (str "agent/cleanup-review-" stamp))
                msg (str "cleanup: preserve uncommitted tracked changes (" (count tracked) " files)\n\n"
                         "NOT auto-merged. These rewrite files that already exist on " base ",\n"
                         "and the working tree they came from may be far behind it.\n\n"
                         "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>")]
            ;; tracked は必ず 3-way で載せる（three-way-blob! の docstring 参照）。
            (if-let [{:keys [files]} (server-commit! slug dir base tracked br msg true)]
              (let [url (or (:url prior)
                            (existing-pr slug br)
                            (open-pr! slug base br
                                      (str "DO-NOT-MERGE cleanup: preserve uncommitted tracked changes ("
                                           files " files)")
                                      (str "⚠️ **Draft on purpose — this must not be merged as-is.**\n\n"
                                           "Opened as a draft so GitHub itself blocks the merge. On 2026-07-26/27, 17 PRs of\n"
                                           "exactly this shape were merged by an automated pass because the body's warning was\n"
                                           "advisory and the PR looked mergeable; ~913 lines were deleted from `" base "` before\n"
                                           "the damage was found and 8 of them restored.\n\n"
                                           "To use this: keep the additions, drop any deletions, or re-cut the branch from\n"
                                           "current `" base "` so it only adds. Then mark it ready.\n\n"
                                           "These rewrite files that already exist on `" base "`, and the working tree they\n"
                                           "came from may be far behind it. Merging blind can silently roll `" base "` back.\n\n"
                                           "Precedent: cloud-itonami's working tree was 1381 commits behind `main`; applying its\n"
                                           "`legal/terms.md` would have reverted owner-approved public legal pages to a DRAFT.\n\n"
                                           "🤖 Generated with [Claude Code](https://claude.com/claude-code)")
                                      true))]
                (println (format "  :review   %d files → %s （draft・merge しない）" files url)))
              (println "  :review   commit に失敗（報告のみ、ローカルは無傷）"))))
        (when branches? (land-branches! dir slug base)))))))

(defn- live-branches
  "default branch から到達できないローカル branch。"
  [dir base]
  (->> (str/split-lines (or (gitc dir "for-each-ref" "--format=%(refname:short)" "refs/heads/") ""))
       (remove #{"" "main" "master" "synced/main" "git-annex" "manifest-rev"})
       (remove #(gitc dir "merge-base" "--is-ancestor" %
                      (str (or (primary-remote dir) "origin") "/" base)))
       vec))

(defn- land-branches!
  "`:branches` クラス。**merge は決してしない** — 放棄された実験・意図的な分岐・
  force-push 済み履歴が見分けられない。やるのは保全（push）とレビュー導線（PR）だけ。

  PR 照会は 1 branch = 1 API 往復なので、branch farm（実測: kotoba-lang/webgpu は
  ローカル branch 67本、slides は 90本超）では打ち切って必ず報告する。"
  [dir slug base]
  ;; stale な remote-tracking ref を先に落とす。has-remote? は
  ;; refs/remotes/<remote>/<b> の存在で判定するので、upstream から消えた
  ;; (あるいは一度も存在しなかった) branch のローカルキャッシュが残っていると
  ;; 「push 済み」と誤判定し、push をスキップして PR 作成に回り、それが失敗して
  ;; 「PR 作成に失敗（差分なし等）」という無害そうな行になる。実測 2026-07-27:
  ;; 残存 102 本のうち 100 本がこれで、この機械にしか無い commit を保全するという
  ;; このクラスの唯一の目的が静かに達成されていなかった。
  (let [remote (or (primary-remote dir) "origin")]
   (gitc dir "fetch" "--prune" "--quiet" remote)
   (let [live (live-branches dir base)
        cap 20]
    (when (seq live)
      (println (format "  branches: 未着地 %d 本" (count live)))
      (if (> (count live) cap)
        (println (format "  → %d 本は上限 %d 超のため未処理（branch farm。個別に扱うこと）"
                         (count live) cap))
        (doseq [b live]
          (let [has-remote? (gitc dir "rev-parse" "--verify" "--quiet" (str "refs/remotes/" remote "/" b))
                ahead (when has-remote?
                        (some-> (gitc dir "rev-list" "--count" (str remote "/" b ".." b)) str/trim parse-long))]
            (cond
              (not has-remote?)
              (let [{:keys [exit]} (sh "git" "-C" dir "push" "-u" remote b)]
                (println (format "    %-46s %s" b (if (zero? exit) "pushed (新規)" "push 失敗"))))

              (and ahead (pos? ahead))
              (let [{:keys [exit]} (sh "git" "-C" dir "push" remote b)]
                (println (format "    %-46s %s" b (if (zero? exit) (str "pushed (+" ahead ")") "push 失敗"))))

              :else
              (if-let [url (existing-pr slug b)]
                (println (format "    %-46s PR 既存 %s" b url))
                (if-let [url (open-pr! slug base b
                                       (str "DO-NOT-MERGE cleanup: review un-landed branch " b)
                                       (str "⚠️ **Draft on purpose — opened only to put this branch on a review path.**\n\n"
                                            "`" b "` is pushed but not reachable from `" base "` and had no open PR.\n"
                                            "Abandoned experiments, deliberate forks and force-pushed histories all look\n"
                                            "alike from outside, so landing it is a human call.\n\n"
                                            "Draft so GitHub blocks the merge rather than relying on this note being\n"
                                            "read — on 2026-07-26/27 an automated pass merged 17 advisory-only PRs of the\n"
                                            "sibling `:review` class and deleted ~913 lines from `" base "`.\n\n"
                                            "Opened by `scripts/cleanup-land.cljs` (skill `git-cleanup-conflict`).\n\n"
                                            "🤖 Generated with [Claude Code](https://claude.com/claude-code)")
                                       true)]
                  (println (format "    %-46s PR 作成 %s" b url))
                  (println (format "    %-46s PR 作成に失敗（差分なし等）" b))))))))))))

;; ---------- main ----------

(println (str "cleanup-land " (if apply? "APPLY" "DRY-RUN（--apply で実行）")))
(println "分類: :additive=untracked のみ→merge / :review=tracked 変更→PR のみ / annex は対象外")

(def repos
  (->> (sh "find" "orgs" "-maxdepth" "3" "-name" ".git" "-type" "d")
       :out str/trim str/split-lines sort
       (map #(subs % 0 (- (count %) 5)))
       (remove annex?)
       (filter own-repo-root?)
       (filter (fn [d] (if only-names (some #(str/ends-with? d (str "/" %)) only-names) true)))))

(def plans
  (->> repos (map plan-repo)
       (filter #(or (seq (:additive %)) (seq (:tracked %)) (seq (:skipped %))
                    ;; --branches のときは作業ツリーが綺麗でもローカル専用 branch を
                    ;; 持つ repo を候補に入れる。これが無いと、この機械にしか存在しない
                    ;; branch しか持たない repo は候補にすら入らず、--branches を付けても
                    ;; 一本も push されない（実測 2026-07-27: 70 repo だけ処理され、
                    ;; 227 repo が machine-only のまま残った）。
                    (and branches? (seq (live-branches (:dir %) (:base %))))))))
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

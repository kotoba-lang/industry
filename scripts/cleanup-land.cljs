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
;;   nbb scripts/cleanup-land.cljs --apply --only-branches ; :branches だけ（:review を再生成しない）
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
;;   - **改名の残骸は :additive に入れない**（scripts/rename_residue.cljs）。詳細は
;;     residue-gate! の docstring と manifest/cleanup-workflow.edn の :residue-gate。
(require '[scripts.nbb-compat :refer [format]]
         '[scripts.rename-residue :as residue]
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
(def only-branches? (argset "--only-branches"))
;; `--branches` は :additive / :review も一緒に走らせる。ところが cleanup-land は
;; ローカル WIP を決して消さない（安全床）ので、再実行のたびに同じ未コミット
;; ファイルから同じ preservation PR を作り直す。一度 triage して close した PR が
;; 次の run で復活するため、branch だけを処理したいときに :review を巻き添えに
;; できない（実測 2026-09-06: --branches を回したら 44 repo / 37 PR が全て前日に
;; close 済みの PR の再生成だった)。--only-branches はその巻き添えを外す。
(def branches? (or (argset "--branches") only-branches?))
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

(defn- gh-ref
  "ref を引いて **{:state :found/:absent/:error, :sha s}** を返す。

  なぜ gh-str ではだめか（2026-08-12 の実害）: gh-str は失敗も「無い」も同じ nil に
  潰す。server-commit! はその nil を『commit が1つも無い新規 repo』と読んで
  parents 無し・base_tree 無しのルートコミットを作る経路を持っているので、
  **通信が失敗しただけで親無し commit が生える**。実測: secondary rate limit で
  全 gh 呼び出しが 403 になった状態で --apply を回したところ、6 repo に
  『No common ancestor』な branch ができた（tree は新規ファイルだけで repo の履歴を
  1 つも含まない = 構造的に merge 不能）。しかも default branch と比較しない限り
  見えない。:jq-scalar-is-not-json と同型の『失敗した照会を値として読む』誤り。

  404 だけを :absent とし、それ以外の非ゼロ exit は :error として呼び出し側に
  中断させる。gh は HTTP 状態を stderr に `(HTTP 404)` の形で書く。"
  [slug ref]
  (let [{:keys [out exit err]} (sh "gh" "api" (str "repos/" slug "/git/ref/" ref) "--jq" ".object.sha")
        e (str err)]
    (cond
      (zero? exit)                  {:state :found :sha (not-empty (str/trim (str out)))}
      (str/includes? e "(HTTP 404)") {:state :absent}
      :else                          (do (binding [*out* *err*]
                                           (println "  gh failed (ref lookup):" (str/trim e)))
                                         {:state :error}))))

(defn- gh-input!
  "巨大な body を持つ POST は argv 上限（macOS で ~1MB、base64 blob は容易に超える）
  に当たるので、必ず JSON ファイル経由で送る。"
  [endpoint payload jq]
  (let [tmp (str "/tmp/cleanup-land-" (hash endpoint) "-" (hash (str payload)) ".json")]
    (.writeFileSync node-fs tmp (json/generate-string payload))
    (gh-str "api" endpoint "--input" tmp "--jq" jq)))

;; ---------- 分類 ----------

;; `identity` は拡張子を固定しない。`identity\.(edn|json|yaml|yml)$` と書いていた版は
;; 2026-08-13 に破れた: kotoba-lang/toshokan-patents の
;; `scripts/.kotobase-ingest-toshokan-patents-identity.hex` は語尾が `-identity.hex` で
;; あって `identity.<ext>` ではなく、`.hex` も拡張子列に無く、`.*secret.*` にも
;; `.*credential.*` にも当たらなかった。dry-run はこれを :additive（PR → **merge**）に
;; 計上しており、対象 repo は **public** だった。
;; skip は誤検知しても名前付きで報告されるだけで復旧可能、公開は不可逆——網は広く取る。
(def ^:private credential-re
  #"(?i)(^|/)(\.env($|\.)|.*\.(pem|key|p12|pfx|jks|keystore|jwk|hex)$|id_(rsa|ed25519)|.*identity.*|.*(seed|privkey|keypair).*|.*secret.*|.*credential.*|\.kagi/|\.npmrc$|\.netrc$)")

;; パス名だけの判定は実際に破れた（2026-07-26）。actor の identity.json は
;; `identity\.edn$` にも `.*secret.*` にも当たらず、private-b64 を含んだまま
;; public repo cloud-itonami/cloud-itonami-isic-6310 に merge された
;; （sibling の 7810/6399 は .gitignore に identity.json があり無事だった）。
;; 名前が何であれ鍵素材そのものを撃ち落とす content 側の網を足す。
(def ^:private secret-content-re
  #"(?i)(private[-_](b64|key|pem|jwk)|BEGIN [A-Z ]*PRIVATE KEY|secret[-_]key|mnemonic)")

;; ラベルの付いた鍵素材しか撃てない網は、**生の鍵**を素通しする（2026-08-13 実測）。
;; 64 文字の hex 1 行だけのファイルは `private-b64` とも `BEGIN … PRIVATE KEY` とも
;; 名乗らない。ファイル全体がひとつの高エントロピー token であることを形で捕える。
;; 全体一致に限るので、hex/base64 を *含む* 正当な source は巻き込まない。
(def ^:private bare-key-re
  #"(?s)\A\s*(?:0x)?(?:[0-9a-fA-F]{32,}|[A-Za-z0-9+/_-]{40,}={0,2})\s*\z")

(defn- secret-content?
  "中身に鍵素材のマーカーがあるか、あるいはファイル全体が生の鍵そのものか。
   読めなければ false（fail-open にはしない — 読めないファイルは size/パス側の網で拾う）。"
  [f]
  (try
    (let [s (.toString (.readFileSync node-fs (.getPath f)) "utf8")]
      (boolean (or (re-find secret-content-re s)
                   ;; 生鍵は短い。長い文書を総なめしない。
                   (and (< (count s) 4096) (re-find bare-key-re s)))))
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
  巻き込むので、ファイル名の形で絞る。

  `scratch` / `.probe` / `tmp` は 2026-08-27 に追加。ここまでの列挙は**ビルドの
  副産物**だけを見ており、**人が手で置いた使い捨て**を見ていなかった。実測: この
  日 `:additive`（= PR を作って **merge する**クラス）で着地した 5 repo のうち
  2 つが診断用の投げ捨てスクリプトで、`cloud-itonami-isic-862` の
  `.probe/probe.clj` と `cloud-itonami-isic-6310` の `scratch/probe.clj` が
  **main に merge された**。どちらも ns を持たず top-level `require` で書かれた
  REPL 用の走り書きで、両 repo とも**その 1 本以外にそのディレクトリ配下を
  1 件も track していない**（= 意図して置いている artifact ディレクトリではない）。

  ⚠ **原因を「scratch worktree から着地させたこと」と読んではいけない。** 同じ
  日、同じ scratch worktree 由来で `etzhayyim/tamaki` の test と
  `kotoba-lang/kakeibo` の accounting 実装（src+test）が正しく着地している。
  効く信号は**取り込み元のディレクトリ**ではなく**着地する path そのもの**で、
  取り込み元で弾いていれば本物 3 件を巻き添えにしていた。

  `\\.clj-kondo/\\.cache` は 2026-09-07 に追加。clj-kondo の lint cache
  （`.clj-kondo/.cache/v1/**/*.transit.json` と `lock`）はエディタ/LSP が走るたびに
  書き換わる生成物で、repo が `.gitignore` に持っていなければ `??` に上がる。実測:
  fleet の dry-run が `:additive`（= PR を作って **merge する**クラス）として計上した
  4 repo のうち **2 repo（kotoba-lang/kami-app-modeler 12 件 / langgraph 8 件）は
  この cache だけ**で、そのまま apply していれば lint cache を main に commit していた。
  `.clj-kondo` 全体を落とさないのは、`.clj-kondo/config.edn` が正当に track される
  設定ファイルだから —— 落とすのは `.cache` 配下だけ。"
  #"(^|/)(node_modules|\.cpcache|\.shadow-cljs|cljs-runtime|\.fleet-run|\.wrangler|target|dist|build|out|scratch|\.probe|tmp|\.DS_Store|\.clj-kondo/\.cache|.*\.log|.*-cache\.json)(/|$)")

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

(def nested-repo-note
  "untracked なディレクトリの中に**別の git repo** が居ると、展開しても展開されない。

  `plan-repo` は `?? foo/` というディレクトリ表記を
  `git ls-files --others --exclude-standard -- foo/` で個別ファイルに展開する。
  ところが `ls-files -o` は**入れ子の repo 境界で止まり、`foo/` を trailing slash
  付きのまま返す**。展開後もディレクトリのままなので、`classify-file` の regex は
  どれも当たらず `statSync` も成功し、**ディレクトリのパスが `:additive` に入る**。
  そのまま `--apply` すると blob を作れないパスを commit しようとする。

  実測 2026-08-18（この gate ができた理由）:

  | repo | untracked | 実体 |
  |---|---|---|
  | `kotoba-lang/amu` | `.claude/worktrees/agent-a62da554fc36aeff3/` ほか 1 | **稼働中の登録済み worktree**（branch `agent/log-v1-aot-surface` / `agent/storage-v1-aot-surface`、計 1,623 ファイル） |
  | `kotoba-lang/kotoba-lang` | `netsync/` | **west 登録済みの別 repo `kotoba-lang/netsync` の重複 clone**（pin と同一 commit c7ca033、unpushed 0） |

  どちらも `plan :additive → PR → merge` として計画されていた。前者は他セッションの
  worktree を repo に取り込み、後者は独立した repo を親 repo に吸収する。
  **どちらも「main に同名パスが無い」を完全に満たす** —— `:additive` の論拠
  （既存の行を書き換えない）は真なのに、やってよい理由にはならない。rename residue
  （`:residue-gate`）と同型で、*パスが main に無い*ことは*新しい仕事*の証明ではない。")

(defn- classify-file [dir path]
  (let [f (io/file dir path)
        dir? (try (.isDirectory (.statSync node-fs (.getPath f)))
                  (catch :default _ false))
        size (try (.-size (.statSync node-fs (.getPath f))) (catch :default _ 0))]
    (cond
      ;; nested-repo-note: 展開後もディレクトリ = 入れ子 repo の境界。ファイルではない。
      (str/ends-with? path "/")         :skip-nested-repo
      dir?                              :skip-nested-repo
      (re-find credential-re path)      :skip-credential
      (re-find workflow-policy-re path) :skip-workflow-policy
      (re-find junk-re path)            :skip-junk
      (> size max-bytes)                :skip-large
      (secret-content? f)               :skip-credential
      :else                             :take)))

(defn- annex? [dir]
  (or (.exists (io/file dir ".git" "annex")) (.exists (io/file dir ".datalad"))))

(defn- send-outcome
  "`git push` の結果を、**理由まで含めて**返す。

  旧実装は結果を `{:keys [exit]}` だけで受け、**`:err` を捨てていた**。
  ADR-2608136000 の 3 問目（受け取ったエラー本文を捨てていないか）そのもので、
  実測 2026-08-27 の 6 件の `push 失敗` は **3 つの別の原因**が 1 文言に潰れていた:

    non-fast-forward  branch が実際に diverge している（bonsai / nekko）
                      → force は禁止。別名で保全するしかない
    workflow-scope    `refusing to allow an OAuth App to create or update
                      workflow ... without workflow scope`（torch / club-shinshi）
                      → CLAUDE.md のとおり **HTTPS remote にだけ効く制約**で、
                        同じ repo の SSH URL に送れば通る
    transient         TLS handshake timeout 等。再試行で通る

  最初の 2 つは対処が正反対（前者は絶対に送ってはいけない / 後者は送れる）なので、
  同じ文言にしてはならない。戻り値は `[:ok <stderr>]` か `[:failed <kind> <reason>]`。"
  [dir remote branch flags]
  (let [{:keys [exit err]} (apply sh (concat ["git" "-C" dir "push"] flags [remote branch]))
        e (str/trim (str err))]
    (if (zero? exit)
      [:ok e]
      [:failed
       (cond (re-find #"without .?workflow.? scope" e)            :workflow-scope
             (re-find #"non-fast-forward|behind its remote" e)    :non-fast-forward
             (re-find #"timeout|Could not resolve|Connection reset|TLS" e) :transient
             :else :other)
       (or (->> (str/split-lines e)
                (filter #(re-find #"remote rejected|! \[rejected\]|^error:|^fatal:" %))
                first (#(some-> % str/trim)) not-empty)
           (first (remove str/blank? (str/split-lines e)))
           "（stderr 空）")])))

(defn- preserve-branch!
  "branch を remote に保全する。成功判定は exit ではなく **ls-remote で実測する**。

  実測 2026-08-27: 素朴な成否判定は `->` を含む拒否行にも一致してしまい、
  **拒否された 3 件を成功として印字した**（同じ日に私自身がやった）。
  唯一の証拠は remote 側の tip が local と一致することである。

  `workflow-scope` で弾かれたときだけ、同じ repo の SSH URL へ送り直す
  （CLAUDE.md: OAuth scope が効くのは HTTPS remote への push だけ。remote 設定は
  書き換えない — 送り先を明示するだけ）。それ以外は**再試行しない** ——
  non-fast-forward を通す道は force しかなく、それは禁止されている。"
  [dir remote branch slug flags]
  (let [local (some-> (gitc dir "rev-parse" branch) str/trim)
        landed? (fn [r]
                  (= local (some-> (gitc dir "ls-remote" "--heads" r (str "refs/heads/" branch))
                                   str/trim (str/split #"\s+") first)))
        [st kind reason] (send-outcome dir remote branch flags)]
    (cond
      (and (= st :ok) (landed? remote)) [:ok nil]
      (= kind :workflow-scope)
      (let [ssh (str "git@github.com:" slug ".git")
            [st2 _ reason2] (send-outcome dir ssh branch flags)]
        (if (and (= st2 :ok) (landed? ssh))
          [:ok "SSH 経由（HTTPS は workflow scope で拒否）"]
          [:failed (str "workflow-scope、SSH でも不可: " reason2)]))
      (= st :ok) [:failed "exit 0 だが remote に tip が無い"]
      :else [:failed (str (name kind) ": " reason)])))

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
                   (map str/trim) (remove str/blank?) vec)
           github? (fn [r] (some-> (gitc dir "remote" "get-url" r)
                                   (->> (re-find #"github\.com"))))
           gh (filterv github? rs)]
       ;; **GitHub を指す remote だけを候補にする。** git-annex の special remote
       ;; （`b2` / `s3` …）は `git remote` に並ぶが git remote ではないので、
       ;; ls-remote も push も通らない。名前だけで選ぶと、annex を使う repo で
       ;; slug が nil に落ち、この docstring が警告しているとおり `(no-remote)`
       ;; として **静かに全件スキップ**される。実測 2026-08-27:
       ;; `kotoba-lang/newsfeed` の remote は `b2` と `kotoba-lang` の 2 つで、
       ;; 旧実装は `origin` が無いので先頭の `b2` を選び、この repo に在った
       ;; `rescue/west-detached-20260812`（この機械にしか無い branch）は
       ;; 1 行も報告されないまま処理から落ちていた。
       (cond (some #{"origin"} gh) "origin"
             (seq gh)              (first gh)
             ;; GitHub remote が1つも無いときだけ従来どおり（挙動を狭めない）
             (some #{"origin"} rs) "origin"
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

;; 複数の checkout が同じ upstream slug へ解決することがある（repo 改名 + 旧 path が
;; ローカルに残るとこうなる）。それぞれが別の untracked を持っていると、**2 本目の
;; commit が 1 本目を上書きする** —— どちらもレビューされないまま。実測 2026-08-14:
;; orgs/etzhayyim/com-etzhayyim-busshi と orgs/cloud-itonami/actor-busshi が
;; ともに cloud-itonami/actor-busshi へ解決し、両方が data/（observatory の観測台帳）
;; を着地させる計画になっていた。同様に orgs/kotoba-lang/compiler は
;; kotoba-lang/amu へ解決する（GitHub の改名リダイレクト）。
;; 先に claim した checkout だけを通し、2 本目以降は名前の付いた skip として報告する
;; （黙って落とさない。どちらを採るかは人間の判断）。
(def ^:private slug-claims (atom {}))

(defn- canonical-repo
  "raw slug -> `{:slug <GitHub 上の現在名> :archived <true|false|nil>}`。
  **着地対象がある repo にだけ呼ぶこと**。planning 段階で全 repo に対して呼ぶと
  ~700 回の API 往復になり、実測で 25 分経っても plan が終わらなかった（かつ
  rate limit を無駄に消費する）。

  `archived` を**同じ 1 回の呼び出しで**取るのは、archived repo が read-only で
  blob/tree/commit/ref の作成も PR の close も **403** を返すからである（実測
  2026-08-14: `PATCH repos/gftdcojp/241001-lifescience-web/pulls/559` →
  `403 Repository was archived so is read-only`）。オーナー判断 2026-08-14
  「archive repo は対象外でいいよ」。**この判定のために往復を増やさない** —
  full_name を引く既存の 1 回に tab 区切りで相乗りさせる。

  実測 2026-08-14: fleet の archived repo 43 件のうち **38 件が west 登録 + local
  checkout 済み**なので、survey も land もそれらを歩く。prose の除外規則だけでは
  効かないため、ここで機械的に落とす。

  ⚠ 引けなかったときは `:archived nil`（= 判定不能）を返し、**false を返さない**。
  不明を「archived でない」と同じ値にすると、答えられなかったことが合格として
  蓄積する（ADR-2608136000）。呼び出し側は nil を従来どおり進めてよい —— そこで
  archived だったなら書き込みが 403 で**大きな音を立てて**落ちる。"
  [raw]
  (when raw
    (let [out (gh-str "api" (str "repos/" raw) "--jq"
                      ".full_name + \"\\t\" + (.archived|tostring)")
          [fname arch] (some-> out (str/split #"\t"))]
      (if fname
        {:slug fname :archived (case arch "true" true "false" false nil)}
        {:slug raw :archived nil}))))

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
  ;; `.git` がファイル（submodule 時代の gitdir ポインタ / linked worktree）のとき
  ;; `<dir>/.git/...` への mkdir は ENOTDIR で落ちる。実体の gitdir を git に訊く。
  ;; ここを直さずに enumeration だけ広げると、見えるようになった repo が archive で
  ;; 落ちる —— 退避せずに着地させないための非交渉ルール（:retirement :archive）が
  ;; 崩れるので、2 つは同じ変更で直す必要がある。
  (let [gd   (or (some-> (gitc dir "rev-parse" "--absolute-git-dir") str/trim not-empty)
                 (str dir "/.git"))
        adir (str gd "/stash-archive-" stamp)]
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

(defn- tracked-safety-gate!
  "`:review`（tracked 変更）にも credential / junk / size の網をかける。
   -> [残り 落としたもの info]

  **`classify-file` は untracked にしか当たっていなかった。** `plan-repo` は
  `grouped (group-by #(classify-file dir %) untracked)` で untracked だけを分類し、
  `:tracked` は一切の検査を通らずに `server-commit!` まで到達する。つまり
  credential 網・秘密内容の走査・2 MB 上限・ビルド副産物の除外は、どれも
  **:additive 専用**だった。

  実測 2026-08-21（この gate ができた回、両方とも :review 経由で PR になった）:

  | PR | 中身 |
  |---|---|
  | `kotoba-lang/kotobase-worker-shell#3` | 103 件すべて `.shadow-cljs/builds/test/dev/` のコンパイラ出力、**+29,071 行** |
  | `cloud-itonami/ai-gftd-dougaka#6` | `clj/.cpcache/*` 3 件。中身は `/Users/junkawasaki/.m2/…` という**このマシンの絶対パス** |

  `junk-re` は `.cpcache` も `.shadow-cljs` も最初から持っている。当たらなかった
  のは*パターン*ではなく*経路*である。同じ穴が credential 側にも空いている ——
  **tracked な鍵ファイルをローカルで編集すれば、そのまま PR に載って push される。**

  落とし方はクラスで変える:

  - **credential / 秘密内容** — 無条件で落とす。安全床①に例外を作らない。
  - **size 超過** — 落とす。
  - **junk パス** — `base` に**無いときだけ**落とす。base に在るなら、その repo は
    そのファイルを意図して track している（生成物を commit する方針の repo は実在
    する）ので、黙って捨てるほうが危険。base に在る junk は残して人が読む。

  `base-map` が引けなかったときは junk の判定ができない。そのときは credential と
  size だけを当て、`:applied :partial` を返して**そう印字する** —— 『測れなかった』が
  『測って問題が無かった』と同じ顔をしてはならない（ADR-2608136000）。

  ローカルの working tree には触らない。落としたものは repo ごとの
  `.git/stash-archive-<date>/tracked-modifications.patch` に既に入っている。"
  [dir base-map paths]
  (let [have-base? (seq base-map)
        classify (fn [p]
                   (let [f (io/file dir p)
                         size (try (.-size (.statSync node-fs (.getPath f)))
                                   (catch :default _ 0))]
                     (cond
                       (re-find credential-re p)           :skip-credential
                       (> size max-bytes)                  :skip-large
                       (secret-content? f)                 :skip-credential
                       (and (re-find junk-re p)
                            have-base?
                            (not (contains? base-map p)))  :skip-junk-new
                       (re-find junk-re p)                 :kept-tracked-junk
                       :else                               :take)))
        grouped (group-by classify paths)
        dropped (into {} (for [[k v] grouped
                               :when (contains? #{:skip-credential :skip-large :skip-junk-new} k)]
                           [k (vec v)]))]
    [(vec (concat (:take grouped) (:kept-tracked-junk grouped)))
     dropped
     {:applied (if have-base? true :partial)
      :scanned (count paths)
      :kept-junk (vec (:kept-tracked-junk grouped))}]))

(defn- report-tracked-safety! [dropped info]
  (if (= :partial (:applied info))
    (println (format "  ⚠ tracked safety gate: base tree が引けず junk 判定は未適用（credential/size のみ・scanned %d）"
                     (:scanned info)))
    (when (zero? (reduce + (map (comp count val) dropped)))
      (println (format "  tracked safety gate: 落とすものは無し（scanned %d）" (:scanned info)))))
  (doseq [[k v] dropped]
    (println (format "  skip %-18s %d 件（:review から除外）: %s"
                     (name k) (count v) (str/join ", " (take 4 v)))))
  (when (seq (:kept-junk info))
    (println (format "  note tracked-junk %d 件は base に在るので残す（この repo は生成物を track している）: %s"
                     (count (:kept-junk info)) (str/join ", " (take 3 (:kept-junk info)))))))

(defn- ns-source-candidates
  "Namespace symbol -> the source paths it could live at in this repo."
  [ns-sym]
  (let [rel (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/"))]
    (for [root ["src/" "test/" ""] ext [".clj" ".cljc" ".cljs"]]
      (str root rel ext))))

(defn- read-repo-source
  "Text of `path`, preferring the working tree and falling back to the base ref.
  Returns nil when neither has it -- which the caller must treat as
  'not this repo's namespace', never as 'clean'."
  [dir base path]
  (or (try (when (.exists (io/file dir path))
             (.toString (.readFileSync node-fs (.getPath (io/file dir path)))))
           (catch :default _ nil))
      (let [{:keys [exit out]} (sh "git" "-C" dir "show" (str base ":" path))]
        (when (zero? exit) out))))

(defn- defined-names
  "Every top-level name `source` defines.

  Tokenised rather than matched in one regex: `(defn- execute` and
  `(def ^:private ops` and `(def ^{:doc \"…\"} t` all put the name in a
  different position, and an optional-metadata group inside the pattern happily
  captures the NAME instead of the metadata. Measured while building this gate:
  that mistake reported `kir/execute` as unresolved against a file that defines
  it on line one -- a gate whose false positives land on healthy files would be
  turned off within a day, which is the same as not having it."
  [source]
  (into #{}
        (keep (fn [line]
                (when (re-find #"^\(def[a-z\-]*\s" line)
                  (let [toks (-> line
                                 (str/replace #"^\(def[a-z\-]*\s+" "")
                                 (str/split #"[\s\(\[\{]+"))]
                    (->> toks
                         (remove str/blank?)
                         ;; drop ^:private, ^String, ^{:doc "…"} fragments
                         (drop-while #(str/starts-with? % "^"))
                         first)))))
        (str/split-lines source)))

(defn- required-aliases
  "{alias -> namespace-symbol} from the file's ns form. Regex rather than the
  reader: these files carry metadata, reader conditionals and #_ forms that
  edn/read-string refuses, and a gate that throws on the input it exists to
  judge is a gate that never runs."
  [source]
  (into {}
        (for [[_ ns-name alias] (re-seq #"\[([a-zA-Z0-9\-\.]+)\s+:as\s+([a-zA-Z0-9\-\.\*]+)\]" source)]
          [alias ns-name])))

(defn- code-only
  "`source` with string literals and line comments blanked out.

  Both false positives measured on the first sweep of 99 healthy files came from
  reading prose as code: `i64/f64` inside a docstring (a slash meaning \"or\"),
  and a comment mentioning a namespace. A gate that reads documentation as
  references reports the best-documented files as the most broken."
  [source]
  (-> source
      (str/replace #"\"(?:\\\\.|[^\"\\\\])*\"" "\"\"")
      (str/replace #"(?m);.*$" "")))

(defn- unresolved-refs
  "Symbols the file reads through an alias whose namespace lives IN THIS REPO
  and which that namespace does not define. Returns a sorted vec."
  [dir base source]
  (let [aliases (required-aliases source)
        body    (code-only source)]
    (->> (re-seq #"(?:^|[\s\(\[\{\'`~@])([a-zA-Z0-9\-\.\*]+)/([A-Za-z0-9\-\?!*<>=+._]+)" body)
         (keep (fn [[_ alias sym]]
                 (when-let [ns-name (get aliases alias)]
                   (when-let [src (some #(read-repo-source dir base %)
                                        (ns-source-candidates ns-name))]
                     ;; the namespace IS in this repo, so its definitions are knowable.
                     ;; `Rec.` is host-interop construction of a deftype/defrecord --
                     ;; the name the namespace defines has no trailing dot.
                     (let [bare (str/replace sym #"\.$" "")]
                       (when-not (contains? (defined-names src) bare)
                         (str alias "/" sym)))))))
         distinct sort vec)))

(defn- base-gitignore
  "Text of the base ref's root `.gitignore`, read from the tree we are about to
  commit onto -- deliberately NOT from the working tree.

  `read-repo-source` prefers the working tree, which is exactly wrong here: the
  stale copy is the thing being defended against. The blob sha already sits in
  `base-blobs`, so this costs one extra API call and only for repos that still
  have `:additive` candidates after the cheaper gates."
  [slug base-map]
  (when-let [sha (get base-map ".gitignore")]
    (when-let [b64 (gh-str "api" (str "repos/" slug "/git/blobs/" sha) "--jq" ".content")]
      (try (.toString (.from js/Buffer (str/replace b64 #"\s" "") "base64") "utf8")
           (catch :default _ nil)))))

(defn- base-ignore-gate!
  "Keep `:additive` from landing files the BASE branch declares ignored.
  -> [additive' ignored info]

  ## Why the other gates do not cover this

  Candidates come from `git status`, which honours the WORKING TREE's
  `.gitignore`. In a west checkout that is routinely months behind, so a rule
  added upstream is invisible locally and the build output it was written to
  exclude keeps presenting as never-committed new work. `drop-already-landed`
  cannot see it (the path is not in the base tree -- that is what ignoring
  means) and `residue-gate!` only probes ignore status for paths a rename
  killed. This is the same false argument those two gates exist to refute:
  *not on main* does not mean *new work*.

  Measured 2026-08-19, kotoba-lang/murakumo: the checkout is 35 commits behind,
  and `kotoba/prices_core.kotoba.perf.mjs` plus its 4 inputs/manifest/provenance
  sidecars (60 KB of `amu compile --target js` output) planned as `:additive ->
  PR -> merge`. `origin/main`'s `.gitignore` has carried `/kotoba/*.perf.mjs`
  and `/kotoba/*.perf.mjs.*` since 2026-08-18, added by an earlier cleanup
  session for this exact symptom. Those five were the ONLY `:additive` files in
  a 61-repo fleet plan, so without this gate the whole pass lands nothing but
  build output. The repo tracks no other `.mjs` or `manifest.edn` under
  `kotoba/`, and nothing in it references the artifact.

  Dropped rather than demoted, on `residue-gate!`'s precedent: a repo declaring
  a path ignored at its live address is the repo answering the question, so
  nothing is lost by not landing it. The local file is never touched.

  ## Limits, stated so a pass is not misread

  Root `.gitignore` only -- nested per-directory ignore files in the base are
  not fetched. `core.excludesFile` is lower precedence than an in-tree
  `.gitignore`, so a local negation still wins; that is the conservative
  direction (the file stays a candidate and a human sees it). When the base has
  no `.gitignore`, or the blob cannot be read, the gate reports that it did not
  run -- `:applied false` -- and never lets 'we could not check' print like
  'nothing to drop' (ADR-2608136000)."
  [dir slug base-map additive]
  (cond
    (empty? additive) [additive [] {:scanned 0 :applied false :reason :no-candidates}]
    (not (contains? base-map ".gitignore"))
    [additive [] {:scanned 0 :applied false :reason :base-has-no-gitignore}]
    :else
    (if-let [txt (base-gitignore slug base-map)]
      (let [tmp (str "/tmp/cleanup-land-base-gitignore-" (hash slug) "-" (hash txt))
            _ (.writeFileSync node-fs tmp txt)
            ignored (into #{}
                          (filter (fn [p]
                                    (zero? (:exit (sh "git" "-C" dir
                                                      "-c" (str "core.excludesFile=" tmp)
                                                      "check-ignore" "--no-index" "-q" "--" p)))))
                          additive)]
        (try (.unlinkSync node-fs tmp) (catch :default _ nil))
        [(vec (remove ignored additive)) (vec (filter ignored additive))
         {:scanned (count additive) :applied true}])
      [additive [] {:scanned 0 :applied false :reason :gitignore-blob-unreadable}])))

(def ^:private clj-source-exts #{"clj" "cljs" "cljc"})

(defn- source-stem
  "path -> [stem ext] for Clojure sources, else nil."
  [path]
  (when-let [m (re-find #"^(.*)\.(clj|cljs|cljc)$" path)]
    [(nth m 1) (nth m 2)]))

(defn- shadowing-twin
  "Does adding `ext` at `stem` shadow, or get shadowed by, something on base?

  Clojure resolves a namespace by trying the platform extension BEFORE `.cljc`:
  on the JVM `foo.clj` then `foo.cljc`, in ClojureScript `foo.cljs` then
  `foo.cljc`. So a `.cljc` paired with `.clj` or `.cljs` means one of the two
  files is loaded and the other is not, for that platform.

  `foo.clj` + `foo.cljs` with no `.cljc` is NOT that -- it is the ordinary
  platform-split pattern and must not be flagged."
  [base-paths stem ext]
  (let [others (->> (disj clj-source-exts ext)
                    (filter #(contains? base-paths (str stem "." %)))
                    sort vec)
        hazard (if (= "cljc" ext)
                 others                                   ; adding .cljc over .clj/.cljs
                 (filterv #{"cljc"} others))]             ; adding .clj/.cljs over .cljc
    (when (seq hazard) hazard)))

(defn- source-twin-gate!
  "Keep `:additive` from landing a source file that shadows one already on base.
  -> [additive' twins info] where `twins` is demoted to :review.

  ## The same wrong argument, in a new costume

  `:additive` merges on one argument: no path of this name exists on the default
  branch. `src/kotoba/native/elf64.clj` satisfies that perfectly while
  `src/kotoba/native/elf64.cljc` sits on main -- **the paths differ, the
  namespace does not.** This is the fourth time this file has had to record that
  `not on main` does not mean `new work` (see residue-gate!, nested-repo-gate,
  stale-ignore-gate).

  Measured 2026-08-23 on the fleet's 22 open preservation PRs. Five were
  classified merge-candidate by the disposition procedure in
  manifest/cleanup-workflow.edn (overlap 0 with the default branch, 0 deleted
  lines). Three of the five were not safe:

  - `kotoba-lang/kotoba-native#56` adds `src/kotoba/native/elf64.clj` (804 lines)
    while main carries `src/kotoba/native/elf64.cljc` (46,629 bytes). On the JVM
    the 804-line file wins, so the deployed behaviour would come from a file a
    reader opening the `.cljc` never sees. Overlap is 0 precisely BECAUSE the
    extension differs.
  - `kotoba-lang/bonsai#18` adds a `.bb` into a repo with none (CLAUDE.md forbids
    new babashka entry points) -- policy, not shadowing, and out of this gate's
    scope; reported so the next reader does not assume this gate covers it.
  - `network-awai/app-aozora-engine#9` carries 0 files under a title claiming 1.

  ## It demotes, it never drops

  A twin can be deliberate mid-migration, so this goes to :review for a human,
  on unresolved-refs-gate!'s precedent. Nothing in the working tree is touched.

  ## When it cannot answer

  With no `base-map` there is nothing to compare against, so the gate reports
  `:applied false` rather than passing everything -- a run that could not look
  must not read like a run that looked and found nothing (ADR-2608136000)."
  [base-map additive]
  (if (nil? base-map)
    [additive [] {:applied false :scanned 0}]
    (let [base-paths (set (keys base-map))
          judged (map (fn [p]
                        (if-let [[stem ext] (source-stem p)]
                          [p (shadowing-twin base-paths stem ext)]
                          [p nil]))
                      additive)
          twins (->> judged (filter second) vec)
          kept (->> judged (remove second) (mapv first))]
      [kept (mapv first twins)
       {:applied true
        :scanned (count (filter (comp some? source-stem) additive))
        :findings (mapv (fn [[p exts]]
                          [p (mapv #(str (first (source-stem p)) "." %) exts)])
                        twins)}])))

(defn- revert-residue-gate!
  "Keep `:additive` from re-landing content the default branch DELETED.
  -> [additive' reverted info] where `reverted` is demoted to :review.

  ## The most dangerous reading of `not on main`

  `:additive` means no path of this name exists on the default branch. A revert
  produces exactly that state, and it produces it **on purpose**. Re-adding the
  path does not add new work; it reverses somebody's decision.

  Measured 2026-08-23, `kotoba-lang/kotoba`, found while surveying the west
  update skip set. `cleanup: land untracked WIP (20 files)` merged as PR #487 at
  10:07Z and was reverted the same day by `4903fba1`, which removed all 20 files
  including `docs/ADR-codebase-actor-ipld.edn` and `src/kotoba/codebase_actor.clj`.
  Because cleanup-land never deletes local copies -- correctly, that is the
  safety floor -- both files were still sitting untracked in the shared checkout
  afterwards. On the next `--apply` they are absent from main, carry no rename,
  are not ignored, and have no source twin, so they plan as `:additive` -> PR ->
  **merge**. `unresolved-refs-gate!` happens to catch the `.clj` (it requires
  `kotoba.ipld-block-store`, which the revert also removed), but the `.edn` is a
  document with no references and would sail straight through.

  So the revert would have been undone by the tool that caused it, on its next
  run, with a commit message calling the content new.

  ## It demotes, it never drops

  A path can legitimately be re-created after a deletion, and a human is the one
  who can tell that from a reversal. Goes to :review with the deleting commit
  named, so the reviewer starts from the evidence rather than from the diff.

  ## Which ref, and what happens when there is none

  The default branch when it resolves, HEAD otherwise -- residue-gate!'s
  fallback and for its reason: a west checkout resolves `<remote>/<default>`
  only about a third of the time. With neither, `:applied false` is printed
  rather than passing everything (ADR-2608136000)."
  [dir base additive]
  (let [resolves? (fn [r] (some? (gitc dir "rev-parse" "--verify" "-q" r)))
        ref (or (first (for [r (str/split-lines (or (gitc dir "remote") ""))
                            :let [r (str/trim r)]
                            :when (seq r)
                            :let [cand (str r "/" base)]
                            :when (resolves? cand)]
                        cand))
                (when (resolves? "HEAD") "HEAD"))]
    (if (nil? ref)
      [additive [] {:applied false :scanned 0}]
      ;; 候補が多いときは history を 1 回だけ歩く。実測 2026-08-23
       ;; (`kotoba-lang/kotoba`): 1 パスあたり `git log` を起動すると **279 ms**、
       ;; 全 history を 1 回歩くと **3.4 秒**（削除パス 27,196 行）。損益分岐は
       ;; **12 候補**。片方に決め打ちすると、候補 2 件の repo で 3.4 秒を払うか、
       ;; 候補 1,000 件の repo で 4.6 分を払うかのどちらかになる。
       ;; 閾値は測った値であって好みではない。
      (let [batch? (> (count additive) 12)
            deleted (when batch?
                      ;; `git log` は新しい順なので、あるパスの **最初の** 出現が
                      ;; 直近の削除 commit。後から来た古い削除で上書きしない。
                      (let [out (or (gitc dir "log" "--diff-filter=D" "--name-only"
                                          "--format=%x00%h%x09%s" ref) "")]
                        (loop [ls (str/split-lines out) cur nil acc {}]
                          (if-let [l (first ls)]
                            (if (str/starts-with? l "\u0000")
                              (recur (rest ls) (subs l 1) acc)
                              (let [pth (str/trim l)]
                                (recur (rest ls) cur
                                       (if (and (seq pth) cur (not (contains? acc pth)))
                                         (assoc acc pth cur) acc))))
                            acc))))
            judged (for [pth additive
                         :let [line (if batch?
                                      (get deleted pth)
                                      (let [o (str/trim (or (gitc dir "log" "--diff-filter=D" "-n" "1"
                                                                  "--format=%h%x09%s" ref "--" pth) ""))]
                                        (when (seq o) o)))]]
                     [pth line])
            hits (->> judged (filter second) vec)]
        [(->> judged (remove second) (mapv first))
         (mapv first hits)
         {:applied true :scanned (count additive) :findings (vec hits) :ref ref
         :mode (if (> (count additive) 12) :one-history-walk :per-path)}]))))

(defn- unresolved-refs-gate!
  "Keep `:additive` from landing code whose references do not resolve.
  -> [additive' unresolved] where `unresolved` is demoted to :review.

  ## Why :additive is not enough

  `:additive` merges on one argument: no path of this name exists on the default
  branch, so no existing line is rewritten. That is true of LINES and silent about
  THE BUILD. A test file is additive by path and still breaks compilation.

  Measured (kotoba-lang/kotoba-kir, found 2026-08-19). `b0472c3`
  `cleanup: land untracked WIP (1 files)` on 2026-08-14 landed
  `test/kotoba/kir_value_runtime_test.clj`, which reads
  `kir/value-runtime-operations`. That var is defined in no ref of the repo and
  nowhere in the fleet, and all four of the file's deftests drive `kir/execute`
  with `value-intern` / `value-hydrate` / `value-resolve` / `value-cid-of` /
  `value-release`, none of which exist in `src/kotoba/kir.cljc` either. The suite
  did not COMPILE for five days -- so zero tests ran and nothing in that repo was
  checked at all, which is worse than a red suite because it looks like nothing.

  The commit's own message reads 'Purely additive: none of these paths exist on
  main, so no existing line is rewritten.' Correct, and beside the point.

  ## What it will not do

  Only namespaces whose source is in THIS repo are judged; an alias pointing at
  an external dependency is out of scope, not 'clean'. When a namespace's source
  cannot be read from either the working tree or the base ref, the reference is
  left alone rather than reported -- the gate declines to answer instead of
  guessing, and says how many files it actually scanned so a zero-finding run is
  distinguishable from a zero-scan one.

  ## It demotes, it never drops

  Findings go to :review -- a PR a human reads -- exactly like residue-gate!'s
  :suspect. That is deliberate, because the reference scan is regex lexing and
  regex lexing of Clojure is approximate.

  Measured over 99 files on main across 5 repos whose suites run, so every
  finding is by construction a false positive: **1 of 99**. It is
  `kotoba.kir.value`, which really does alias `kotoba.kir.cljs-i64 :as i64`, and
  really does contain the characters `i64/f64` -- on line 1437, inside a
  docstring, meaning \"i64 or f64\". Blanking strings and comments before the scan
  removed the other one; this one survives because a file with regex literals can
  mispair quotes. A 1% cost of one human glance is the right price for catching
  a repo whose suite silently stopped compiling; a 1% cost of silently discarded
  work would not be."
  [dir base additive]
  (let [clj? #(re-find #"\.clj[cs]?$" %)
        cands (filter clj? additive)]
    (if (empty? cands)
      [additive [] {:scanned 0}]
      (let [findings (into {}
                           (keep (fn [p]
                                   (when-let [src (read-repo-source dir base p)]
                                     (when-let [bad (seq (unresolved-refs dir base src))]
                                       [p (vec bad)]))))
                           cands)
            bad-paths (set (keys findings))]
        [(vec (remove bad-paths additive))
         (vec (sort bad-paths))
         {:scanned (count cands) :findings findings}]))))

(defn- residue-gate!
  "`:additive` から **改名の残骸**を外す。-> [additive' suspects residues]

  ## なぜ drop-already-landed では足りないのか

  `drop-already-landed` は **同じパス**が base tree に在るかを見る。ディレクトリ
  改名はまさにその前提を壊す —— `worker/` が `clj-edge/` になった瞬間、旧パスは
  base から消える。それが改名の定義である。だから共有 checkout に取り残された
  古い写しは `:additive` の条件（「main のどの行も書き換えない」）を**完璧に**
  満たし、`commit → PR → merge` の経路に乗る。

  実測（`net-kotobase/control-plane`、2026-08-12 に発覚）:

  | commit | 日付 | 起きたこと |
  |---|---|---|
  | `04e1514` | 08-03 | `worker/` → `kotobase-edge/`、100% 一致の改名。tracked 48 → 0 |
  | `5a6a55b` | 08-04 | `clj-edge/` → `kotobase-api-gateway-cljs/`、同上。136 → 0 |
  | `8f7fbaf` | 08-05 | **`cleanup: land untracked WIP (1 files)`** — `worker/` 0 → 1 |
  | `d96f18b` | 08-06 | **`cleanup: land untracked WIP (16 files)`** — `clj-edge/` 0 → 14 |

  2 回の cleanup が、1〜2 日前に意図して改名した先の**旧名で** 17 ファイルを
  main に戻した。うち `clj-edge/src/kotobase/site_skin.cljc` は、その設計を
  supersede した commit の**直前**の版と byte 一致 —— 議論して捨てた古い doctrine が
  1 週間後に復活していた。17 本目 `worker/src/edge-app.generated.mjs` はビルド生成物で、
  `.gitignore` は**生きているパス**（`kotobase-api-gateway/src/edge-app.generated.mjs`）
  しか書けないので、改名前のアドレスに落ちていたそれは除外されなかった。
  ファイルは `3c99bd5` で消したが、**症状はファイルで、バグはこの cleanup 経路**である。

  ## 判定の分け方 —— 「黙って落とす」を作らない

  cleanup が黙ってファイルを落とすのは、いま直しているバグより悪い。だから
  証明できる側だけを外す:

  - `:residue` → `:additive` から外し、名前の付いた skip クラスとして報告する。
    **bytes は既にこの repo の object database に在る**（あるいは repo 自身が
    ignore すると宣言している）ので、着地させないことで失われるものが無い。
    `git show <commit>:<path>` は何年後でも同じ bytes を返す。ローカルの working
    tree には一切触らない（安全床）。archive! は gate の前に呼ばれるので、
    `.git/stash-archive-<date>/untracked-files.txt` にも残る。
  - `:suspect` → `:review` へ降格。パスは死んでいるが**内容は history に無い**ので、
    stale な写しに対する本物の編集かもしれない。draft PR を開いて人が決める
    （base に同名が在って内容が違う時の既存の降格と同じ機構）。
  - `:wip` → そのまま `:additive`。

  ## どの ref で判定するか（west checkout の実測）

  west は `refs/west/*` に fetch するので、多くの子 checkout には
  remote-tracking ref が無い。実測 2026-08-12: `orgs/kotoba-lang/*` の 121
  checkout のうち `<remote>/main` が解決したのは **43 (36%)**。ローカル ref だけを
  見ると、この gate は fleet の 2/3 で**沈黙して素通りする**。

  そこで 2 つに分ける:

  - **生きているパスの集合**は呼び出し側が既に持っている `base-blobs`
    （GitHub API で取った default branch の tree）を渡す。これが最も正確で、
    追加コストはゼロ。
  - **commit graph**（改名履歴と過去 blob）はローカルから読む。`<remote>/<base>`
    が引ければそれ、無ければ `HEAD`。HEAD で足りる理由: 改名前のパスに untracked
    の残骸が在りうるのは、その checkout が**改名以後**に居る場合だけである
    （改名前なら、それらは untracked ではなく tracked だ）。

  どちらも引けなければ gate は判定できない —— **判定できないことを『残骸ゼロ』
  として静かに通さない**。警告を出して skip する（fail-open。cleanup は止めない）。"
  [dir base additive base-map]
  (let [remote (or (primary-remote dir) "origin")]
    (if-let [{:keys [ref how]} (residue/resolve-baseref dir remote base)]
      (let [_ (when (= how :head)
                (println (format "  ⚠ residue gate: %s/%s が無いので HEAD の履歴で判定する（west は refs/west/* に fetch する）"
                                 remote base)))
            {:keys [results truncated? renames]}
            (residue/scan dir ref additive
                          (when (seq base-map) {:base-paths (set (keys base-map))}))
            {:keys [residue suspect wip]} (residue/split-verdicts results)]
        (when truncated?
          (println (format "  ⚠ residue gate: 改名 log を %d commit で打切り。古い改名は見ていない。"
                           residue/max-rename-commits)))
        (when (seq residue)
          (println (format "  skip rename-residue  %d 件（改名で死んだパス。bytes は object database に在るので失われない。renames-seen=%d）"
                           (count residue) renames))
          (doseq [r residue]
            (println (format "      %s\n        %s → %s" (:path r)
                             (get residue/residue-explanation (:reason r) (name (:reason r)))
                             (or (:renamed-to r) (:mapped-path r) "?")))))
        (when (seq suspect)
          (println (format "  ⚠ 改名で死んだパスだが内容は history に無い: %d 件 → :review へ降格（auto-merge しない）"
                           (count suspect)))
          (doseq [r suspect]
            (println (format "      %s\n        %s → %s" (:path r)
                             (get residue/residue-explanation (:reason r) (name (:reason r)))
                             (or (:renamed-to r) (:mapped-path r) "?")))))
        [(mapv :path wip) (mapv :path suspect) (mapv :path residue)])
      (do (println "  ⚠ residue gate 不可: 判定に使える rev が無い（remote ref も HEAD も解決しない）。改名残骸の検査を飛ばした。")
          [additive [] []]))))

(defn- server-commit!
  "base branch の tip の上に paths を載せた commit を作り、branch ref を作る。
  branch が既にあれば ref は作らず、その ref を commit へ更新する。
  -> {:branch b :commit sha :files n} / nil"
  [slug dir base paths branch message & [three-way?]]
  ;; base が未作成（= commit が1つも無い新規 repo）なら parents 無し・base_tree 無しの
  ;; ルートコミットを作る。placeholder repo（例 kotoba-lang/org-threejs: branch
  ;; init_placeholder に commit ゼロ、ファイルは全部 untracked）はこの経路でしか
  ;; 着地できない。
  ;; ⚠ base の照会は「無い」と「引けなかった」を必ず分ける。潰すと、通信失敗が
  ;; そのままルートコミット生成に化ける（gh-ref の docstring 参照）。
  (let [{base-state :state base-sha :sha} (gh-ref slug (str "heads/" base))
        base-tree (when base-sha
                    (gh-str "api" (str "repos/" slug "/git/commits/" base-sha) "--jq" ".tree.sha"))]
    (if (or (= base-state :error)
            ;; base はあるのに tree が引けなかった = 同じ罠。base_tree 無しで書くと
            ;; 既存ファイルを1つも含まない tree になる。
            (and base-sha (nil? base-tree)))
      (do (println "  ⚠ base の照会に失敗したので中断（ローカルは無傷）。"
                   "親無し commit を作らないための fail-closed。")
          nil)
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
              ;; branch が未作成なら 404 が正常系。ただし「404 だから無い」と
              ;; 「引けなかった」を潰さない —— 旧実装は exit≠0 を一律 :absent と読んで
              ;; いたので、rate limit で照会が落ちると既存 branch に対して ref 作成を
              ;; 投げ、`Reference already exists (HTTP 422)` で着地に失敗していた
              ;; （2026-08-12 実測）。
              (let [{br-state :state existing? :sha} (gh-ref slug (str "heads/" branch))
                    ok (when (not= br-state :error)
                         (if existing?
                           (gh-input! (str "repos/" slug "/git/refs/heads/" branch)
                                      {:sha commit-sha :force true} ".object.sha")
                           (gh-input! (str "repos/" slug "/git/refs")
                                      {:ref (str "refs/heads/" branch) :sha commit-sha} ".object.sha")))]
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

;; 集計は plan の row ではなく atom に持つ。row（= plan-repo が返す map）の
;; :additive は **gate を通す前**の値で、drop-already-landed / residue-gate! /
;; skip-nested-repo はそのあとに効く。row を数えると「gate が落としたもの」まで
;; 着地予定として数えてしまう。実測 2026-08-18: 66 repo の dry-run が
;; `additive=19 repo` と表示したが、gate 後に :additive が残ったのは **1 repo**
;; だけだった（19 倍の過大報告）。同じ誤りを scripts/cleanup.cljs が先に踏んで
;; おり（manifest/cleanup-workflow.md「集計は row ではなく atom に持つ」）、
;; ここはその修正を land 側に写したもの。
(def planned (atom {:additive #{} :review #{}}))
(defn- record-planned! [dir additive tracked]
  (when (seq additive) (swap! planned update :additive conj dir))
  (when (seq tracked)  (swap! planned update :review conj dir)))

(defn- report-base-ignored!
  "Print the base-ignore outcome. A gate that ran and found nothing and a gate
  that could not run must not look the same (ADR-2608136000), so the
  `:applied false` cases say so out loud instead of printing nothing."
  [base ignored {:keys [scanned applied reason]}]
  (cond
    (seq ignored)
    (do (println (format "  skip base-ignored     %d 件（%s の .gitignore が除外を宣言。scanned %d）: %s"
                         (count ignored) base scanned (str/join ", " (take 4 ignored))))
        (when (> (count ignored) 4) (println (format "      … 他 %d 件" (- (count ignored) 4)))))
    (and (not applied) (= reason :base-has-no-gitignore))
    (println (format "  base-ignore gate 未適用: %s に .gitignore が無い" base))
    (and (not applied) (= reason :gitignore-blob-unreadable))
    (println (format "  ⚠ base-ignore gate 未適用: %s の .gitignore blob を読めなかった（合格ではない）" base))
    applied
    (println (format "  base-ignore gate: 除外宣言に当たるものは無し（scanned %d）" scanned))
    :else nil))

(defn- land-repo! [{:keys [dir slug base additive skipped tracked deleted]}]
  ;; --only-branches: :additive / :review を空にして branch だけの経路へ落とす。
  ;; 「見なかったこと」にはせず、下で件数を named skip として印字する。
  (let [held-additive additive held-tracked tracked
        additive (if only-branches? [] additive)
        tracked  (if only-branches? [] tracked)
        deleted  (if only-branches? [] deleted)
        skipped  (if only-branches? {} skipped)]
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
        canon (when (and raw-slug (or (seq additive) (seq tracked) branch-work?))
                (canonical-repo raw-slug))
        archived? (:archived canon)
        slug (when (or (seq additive) (seq tracked) branch-work?)
               (if raw-slug
                 (:slug canon)
                 ;; remote が無いなら作る（オーナー指示 2026-07-25「remote がなければ
                 ;; repo を作って ok」）。dry-run では作らない。
                 (when apply?
                   (println (format "\n%s  (remote 無し → 作成する)" dir))
                   (create-remote! dir))))
        ;; 最初にこの slug へ到達した checkout が claim する。2 本目以降は下の
        ;; cond で slug-collision として弾かれる（自分自身は not= で素通り）。
        _ (when (and slug (not (contains? @slug-claims slug)))
            (swap! slug-claims assoc slug dir))]
  (println (format "\n%s  (%s)" dir (or slug raw-slug "no-remote")))
  (when (and only-branches? (or (seq held-additive) (seq held-tracked)))
    (println (format "  skip only-branches  :additive %d / :review %d 件（--only-branches のため今回は着地させない）"
                     (count held-additive) (count held-tracked))))
  (when (seq deleted)
    (println (format "  skip deleted        %d 件（削除は main に適用しない）: %s"
                     (count deleted) (str/join ", " (take 4 deleted)))))
  (doseq [[k v] skipped]
    (println (format "  skip %-18s %d 件: %s" (name k) (count v)
                     (str/join ", " (take 4 v)))))
  (cond
    ;; archived repo は read-only（blob/tree/commit/ref も PR close も 403）。
    ;; オーナー判断 2026-08-14「archive repo は対象外でいいよ」。黙って落とさず
    ;; 名前の付いた skip として件数ごと報告する —— backlog から外すのであって
    ;; 「WIP が無い」と主張するのではない。
    archived?
    (do (println (format "  → skip archived-repo（GitHub 上で archived = read-only。着地対象 %d 件は報告のみ）"
                         (+ (count additive) (count tracked))))
        ;; 呼び出し側の集計に「着地した」と数えさせないための戻り値。
        ;; 行を印字するだけでは summary が additive=N に混ぜてしまい、
        ;; 「飛ばした」と「合格した」が出力で区別できなくなる（ADR-2608136000）。
        :archived-skip)

    ;; 同じ slug を別の checkout が既に claim している。着地させると先に着地した
    ;; 内容を上書きするので通さない（上の slug-claims のコメント参照）。
    (and slug (get @slug-claims slug) (not= (get @slug-claims slug) dir))
    (println (format "  → skip slug-collision（%s は既に %s が着地対象として claim 済み。着地対象 %d 件は報告のみ — どちらの写しを採るかは人間の判断）"
                     slug (get @slug-claims slug) (+ (count additive) (count tracked))))

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
            ;; base が ignore すると宣言しているものを :additive から外す。
            ;; 候補は working tree の .gitignore で決まっており、west checkout は
            ;; 平気で数十 commit 遅れる（base-ignore-gate! の docstring）。
            [additive base-ignored bi] (base-ignore-gate! dir slug base-map additive)
            [additive suspects _] (residue-gate! dir base additive base-map)
            ;; 参照が解決しないコードを :additive から外す。:additive は「main に
            ;; 同名パスが無い」= 行を書き換えない、しか言っていない —— test file は
            ;; パス的に additive でも compile を壊す（unresolved-refs-gate! の
            ;; docstring / kotoba-kir b0472c3 の実例）。
            [additive unresolved uinfo] (unresolved-refs-gate! dir base additive)
            ;; 同じ namespace を別拡張子で二重に持たせない。:additive は
            ;; 「main に同名パスが無い」しか言っておらず、elf64.clj と
            ;; elf64.cljc は同名パスではない（source-twin-gate! の docstring）。
            [additive twins twinfo] (source-twin-gate! base-map additive)
            ;; default branch が **削除した** パスを足し直さない。revert は
            ;; 「main に無い」を意図的に作る（revert-residue-gate! の docstring）。
            [additive reverted rvinfo] (revert-residue-gate! dir base additive)
            tracked (vec (concat tracked demoted suspects unresolved twins reverted))
            ;; :review にも credential / junk / size の網をかける。
            ;; classify-file は untracked にしか当たっていない
            ;; （tracked-safety-gate! の docstring）。
            [tracked ts-dropped ts-info] (tracked-safety-gate! dir base-map tracked)]
        (when (seq demoted)
          (println (format "  ⚠ untracked だが %s に既存・内容差あり: %d 件 → :review（auto-merge しない）"
                           base (count demoted)))
          (doseq [p demoted] (println (str "      " p))))
        (report-base-ignored! base base-ignored bi)
        ;; scanned は **常に** 印字する。`when (seq unresolved)` にすると、
        ;; 参照を 1 件も見なかった run と、見て問題が無かった run が同じ沈黙になる
        ;; —— この gate の docstring 自身が「zero-finding と zero-scan を区別する
        ;; ために scanned を言う」と約束している、その約束が呼び出し側で消えていた
        ;; （実測 2026-09-01、cloud-itonami-app への run が 11 件の .clj/.cljc を
        ;; :additive に載せたが、gate が走ったのか走らなかったのかは出力から
        ;; 判らなかった）。ADR-2608136000 の 4 問目そのもの。
        (if (seq unresolved)
          (do (println (format "  ⚠ 参照が解決しない %d 件 → :review（scanned %d）"
                               (count unresolved) (:scanned uinfo)))
              (doseq [[p syms] (:findings uinfo)]
                (println (str "      " p "  " (str/join ", " syms)))))
          (println (format "  unresolved-refs gate: 解決しない参照は無し（scanned %d）"
                           (:scanned uinfo))))
        (if (:applied rvinfo)
          (when (seq reverted)
            (println (format "  ⚠ default branch が削除済みのパス %d 件 → :review（scanned %d, ref %s）"
                             (count reverted) (:scanned rvinfo) (:ref rvinfo)))
            (doseq [[pth c] (:findings rvinfo)]
              (println (str "      " pth "  ← 削除: " c))))
          (println "  ⚠ revert-residue gate: 比較できる ref が無く未適用（:applied false）"))
        (if (:applied twinfo)
          (when (seq twins)
            (println (format "  ⚠ 同 namespace の別拡張子が base に在る %d 件 → :review（scanned %d）"
                             (count twins) (:scanned twinfo)))
            (doseq [[p others] (:findings twinfo)]
              (println (str "      " p "  ← base: " (str/join ", " others)))))
          (println "  ⚠ source-twin gate: base tree が引けず未適用（:applied false）"))
        (report-tracked-safety! ts-dropped ts-info)
        (record-planned! dir additive tracked)
        (when (seq additive) (println (format "  plan :additive  %d files → PR → merge" (count additive))))
        (when (seq tracked) (println (format "  plan :review    %d files → PR のみ（merge しない）" (count tracked)))))
      (let [adir (archive! dir (concat additive (mapcat val skipped)))
            base-map (base-blobs slug base)
            [additive landed-additive demoted] (drop-already-landed dir base-map additive)
            [tracked landed-tracked tracked-differs] (drop-already-landed dir base-map tracked)
            ;; dry-run と同じ位置・同じ理由（base-ignore-gate! の docstring）。
            [additive base-ignored bi] (base-ignore-gate! dir slug base-map additive)
            ;; 改名で死んだパスの残骸を :additive から外す（residue-gate! の
            ;; docstring / manifest/cleanup-workflow.edn :residue-gate）。
            ;; drop-already-landed の後に置くのは、同じパスが base に在る場合は
            ;; そちらの既存判定の方が安く強いから。
            [additive suspects _] (residue-gate! dir base additive base-map)
            ;; 参照が解決しないコードも :additive から外す（上の dry-run と同じ理由）。
            [additive unresolved uinfo] (unresolved-refs-gate! dir base additive)
            ;; dry-run と同じ位置・同じ理由（source-twin-gate! の docstring）。
            [additive twins twinfo] (source-twin-gate! base-map additive)
            ;; default branch が **削除した** パスを足し直さない。revert は
            ;; 「main に無い」を意図的に作る（revert-residue-gate! の docstring）。
            [additive reverted rvinfo] (revert-residue-gate! dir base additive)
            ;; base に存在するのに untracked と報告されたものは :additive ではない。
            ;; :review へ落として auto-merge の対象から外す（PR #444 の再発防止）。
            tracked (vec (concat tracked tracked-differs demoted suspects unresolved twins reverted))
            ;; dry-run と同じ位置・同じ理由（tracked-safety-gate! の docstring）。
            [tracked ts-dropped ts-info] (tracked-safety-gate! dir base-map tracked)]
        (record-planned! dir additive tracked)
        (if (:applied rvinfo)
          (when (seq reverted)
            (println (format "  ⚠ default branch が削除済みのパス %d 件 → :review（scanned %d, ref %s）"
                             (count reverted) (:scanned rvinfo) (:ref rvinfo)))
            (doseq [[pth c] (:findings rvinfo)]
              (println (str "      " pth "  ← 削除: " c))))
          (println "  ⚠ revert-residue gate: 比較できる ref が無く未適用（:applied false）"))
        (if (:applied twinfo)
          (when (seq twins)
            (println (format "  ⚠ 同 namespace の別拡張子が base に在る %d 件 → :review（scanned %d）"
                             (count twins) (:scanned twinfo)))
            (doseq [[p others] (:findings twinfo)]
              (println (str "      " p "  ← base: " (str/join ", " others)))))
          (println "  ⚠ source-twin gate: base tree が引けず未適用（:applied false）"))
        (report-tracked-safety! ts-dropped ts-info)
        ;; scanned は **常に** 印字する。`when (seq unresolved)` にすると、
        ;; 参照を 1 件も見なかった run と、見て問題が無かった run が同じ沈黙になる
        ;; —— この gate の docstring 自身が「zero-finding と zero-scan を区別する
        ;; ために scanned を言う」と約束している、その約束が呼び出し側で消えていた
        ;; （実測 2026-09-01、cloud-itonami-app への run が 11 件の .clj/.cljc を
        ;; :additive に載せたが、gate が走ったのか走らなかったのかは出力から
        ;; 判らなかった）。ADR-2608136000 の 4 問目そのもの。
        (if (seq unresolved)
          (do (println (format "  ⚠ 参照が解決しない %d 件 → :review（scanned %d）"
                               (count unresolved) (:scanned uinfo)))
              (doseq [[p syms] (:findings uinfo)]
                (println (str "      " p "  " (str/join ", " syms)))))
          (println (format "  unresolved-refs gate: 解決しない参照は無し（scanned %d）"
                           (:scanned uinfo))))
        (println (format "  archived → %s" adir))
        (when (seq (concat landed-additive landed-tracked))
          (println (format "  already landed on %s（内容一致でスキップ）: %d 件"
                           base (count (concat landed-additive landed-tracked)))))
        (when (seq demoted)
          (println (format "  ⚠ untracked だが %s に既存・内容差あり: %d 件 → :review へ降格（auto-merge しない）"
                           base (count demoted)))
          (doseq [p demoted] (println (str "      " p))))
        (report-base-ignored! base base-ignored bi)
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
                         "line is rewritten. Checked against the base tree and against paths that\n"
                         "a rename left behind (scripts/rename_residue.cljs), because a renamed-away\n"
                         "path is absent from " base " for exactly the reason a new path is.\n"
                         "Landed by scripts/cleanup-land.cljs (skill\n"
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
                                      (str "cleanup: rescue " files
                                           " uncommitted tracked change(s) from the shared checkout")
                                      (str "Uncommitted changes to files that already exist on `" base "`, rescued\n"
                                           "from the shared west checkout so they are on a branch rather than on no\n"
                                           "branch at all. Opened as a draft: the state is the mechanism, so nothing\n"
                                           "here relies on a title or a note being read.\n\n"
                                           "**This pull request needs a disposition — merge it or close it.** Leaving it\n"
                                           "open is not a third option; an undecided rescue PR is a parking space.\n\n"
                                           "Measure three things before deciding:\n\n"
                                           "1. **Deletions.** `0` deleted lines rewrites nothing and is normally safe.\n"
                                           "   One or more means merging applies somebody else's unfinished edit.\n"
                                           "2. **Base freshness.** How far behind `" base "` was the working tree these\n"
                                           "   came from? A stale base makes a merge a silent rollback.\n"
                                           "3. **Overlap.** Has `" base "` since moved the same files?\n"
                                           "   `gh api repos/<repo>/compare/<base-sha>...<default> --jq '[.files[].filename]'`\n\n"
                                           "Why the caution is not theoretical: on 2026-07-26/27, 17 pull requests of exactly\n"
                                           "this shape were merged by an automated pass and ~913 lines were deleted from\n"
                                           "`" base "`. Separately, cloud-itonami's working tree was 1381 commits behind\n"
                                           "`main`; applying its `legal/terms.md` would have reverted owner-approved public\n"
                                           "legal pages to a DRAFT.\n\n"
                                           "🤖 Generated with [Claude Code](https://claude.com/claude-code)")
                                      true))]
                (println (format "  :review   %d files → %s （draft・merge しない）" files url)))
              (println "  :review   commit に失敗（報告のみ、ローカルは無傷）"))))
        (when branches? (land-branches! dir slug base))))))))

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
              (let [[st note] (preserve-branch! dir remote b slug ["-u"])]
                (println (format "    %-46s %s" b
                                 (if (= st :ok)
                                   (str "pushed (新規)" (when note (str " — " note)))
                                   (str "push 失敗 — " note)))))

              (and ahead (pos? ahead))
              (let [[st note] (preserve-branch! dir remote b slug [])]
                (println (format "    %-46s %s" b
                                 (if (= st :ok)
                                   (str "pushed (+" ahead ")" (when note (str " — " note)))
                                   (str "push 失敗 — " note)))))

              :else
              (if-let [url (existing-pr slug b)]
                (println (format "    %-46s PR 既存 %s" b url))
                (if-let [url (open-pr! slug base b
                                       (str "cleanup: un-landed branch " b " (not reachable from " base ")")
                                       (str "`" b "` is pushed but not reachable from `" base "`, and had no open pull\n"
                                            "request. Opened as a draft so it is on a review path instead of rotting\n"
                                            "unseen. The draft state is the mechanism; nothing here depends on a title\n"
                                            "or a note being read.\n\n"
                                            "**This pull request needs a disposition — merge it or close it.** Leaving it\n"
                                            "open is not a third option.\n\n"
                                            "Landing it is a judgement call because abandoned experiments, deliberate\n"
                                            "forks and force-pushed histories are indistinguishable from outside. What\n"
                                            "separates them is measurable:\n\n"
                                            "- `gh api repos/<repo>/compare/" base "..." b " --jq '{status,ahead_by,behind_by}'`\n"
                                            "- whether the added lines already exist on `" base "` (content containment)\n"
                                            "- whether it still builds against current `" base "`, not against the base it\n"
                                            "  was written on — a branch can be additive by path and still break the build\n\n"
                                            "Closing is a fine answer, and it does not delete the branch.\n\n"
                                            "Opened by `scripts/cleanup-land.cljs` (skill `git-cleanup-conflict`).\n\n"
                                            "🤖 Generated with [Claude Code](https://claude.com/claude-code)")
                                       true)]
                  (println (format "    %-46s PR 作成 %s" b url))
                  (println (format "    %-46s PR 作成に失敗（差分なし等）" b))))))))))))

;; ---------- main ----------

(println (str "cleanup-land " (if apply? "APPLY" "DRY-RUN（--apply で実行）")))
(println "分類: :additive=untracked のみ→merge / :review=tracked 変更→PR のみ / annex は対象外")
(println "改名で死んだパスの残骸は :additive から外す（residue gate）。ローカルは無傷。")


;; ── tracked-safety-gate! の自己検査 ────────────────────────────────────
;; `--selftest-tracked-gate` で、fleet 走査に入る前に両方向を実演して終わる。
;; 「落ちること」を確かめずに landed としない、という repo の規則（CLAUDE.md
;; 「gate は劇場になりうる」）を、この gate については誰でも再実行できる形にする。
;; 実ファイルを触るので一時ディレクトリに作って必ず消す。

;; `--selftest-source-twin-gate` — 両方向を実演して終わる。source-twin-gate! は
;; (base-map, additive) の純関数なので、ファイルシステムも GitHub も要らない。
;; 大事なのは「落ちること」だけでなく「落ちないこと」も見せることで、
;; `foo.clj` + `foo.cljs`（.cljc 無し）は正当な platform split なので通す。

;; `--selftest-revert-residue-gate` — 実 git repo を一時ディレクトリに建てて
;; 両方向を実演する。history を読む gate なので合成の base-map では足りない。
(when (argset "--selftest-revert-residue-gate")
  (let [dir (str (.tmpdir node-os) "/cleanup-land-revert-selftest")
        g (fn [& xs] (apply sh "git" "-C" dir xs))
        w! (fn [rel content]
             (let [f (io/file dir rel)]
               (.mkdirSync node-fs (.getPath (.getParentFile f)) #js {:recursive true})
               (.writeFileSync node-fs (.getPath f) content)))]
    (try (.rmSync node-fs dir #js {:recursive true :force true}) (catch :default _ nil))
    (.mkdirSync node-fs dir #js {:recursive true})
    (sh "git" "init" "-q" "-b" "main" dir)
    (g "config" "user.email" "selftest@example.invalid")
    (g "config" "user.name" "selftest")
    (w! "kept.txt" "never deleted\n")
    (w! "reverted.txt" "landed then reverted\n")
    (g "add" "-A") (g "-c" "commit.gpgsign=false" "commit" "-q" "-m" "land both")
    ;; ここが revert に相当する: default branch から 1 本だけ消す
    (g "rm" "-q" "reverted.txt")
    (g "-c" "commit.gpgsign=false" "commit" "-q" "-m" "Revert \"land both\"")
    ;; 消えたファイルを working tree に置き直す（cleanup-land はローカルを消さないので
    ;; 実際にこの状態になる）
    (w! "reverted.txt" "landed then reverted\n")
    (w! "brand-new.txt" "genuinely new\n")
    ;; 13 件以上で batch 分岐（1 回の history 走査）に入る。**同じ入力で
    ;; per-path と batch が同じ答えを出すこと**を確かめる —— 使われる分岐を
    ;; 一度も通さない検査は、その分岐について何も言っていない。
    (doseq [i (range 14)] (w! (str "filler-" i ".txt") "x\n"))
    (let [many (into ["reverted.txt"] (map #(str "filler-" % ".txt") (range 14)))
          [bk bd bi] (revert-residue-gate! dir "main" many)
          [pk pd pi] (revert-residue-gate! dir "main" ["reverted.txt"])
          [k1 d1 i1] (revert-residue-gate! dir "main" ["reverted.txt" "brand-new.txt"])
          [k2 d2 i2] (revert-residue-gate! dir "main" ["brand-new.txt"])
          [k3 d3 i3] (revert-residue-gate! (str dir "/does-not-exist") "main" ["x.txt"])
          ok (and (= ["brand-new.txt"] k1) (= ["reverted.txt"] d1)
                  (true? (:applied i1)) (= 2 (:scanned i1))
                  (= ["brand-new.txt"] k2) (= [] d2) (= 1 (:scanned i2))
                  (= ["x.txt"] k3) (= [] d3) (false? (:applied i3))
                  ;; batch 分岐に入っていること、そして per-path と同じ答えであること
                  (= :one-history-walk (:mode bi)) (= :per-path (:mode pi))
                  (= ["reverted.txt"] bd) (= bd pd)
                  (= (get-in bi [:findings 0 1]) (get-in pi [:findings 0 1])))]
      (println "revert-residue-gate! selftest")
      (println (format "  (1) default branch が削除済み + 新規  -> kept=%s demoted=%s  %s"
                       (pr-str k1) (pr-str d1) (pr-str (:findings i1))))
      (println (format "  (2) 新規のみ                          -> kept=%s demoted=%s  ← 落としてはいけない側"
                       (pr-str k2) (pr-str d2)))
      (println (format "  (3) ref が引けない                     -> kept=%s applied=%s  ← 通すが「未適用」と申告"
                       (pr-str k3) (:applied i3)))
      (println (str "  (4) 13 件以上 → batch 分岐        -> mode=" (name (:mode bi))
                    " demoted=" (pr-str bd)))
      (println (str "  (5) per-path と batch の一致       -> "
                    (if (= (get-in bi [:findings 0 1]) (get-in pi [:findings 0 1]))
                      "同じ削除 commit を返す" "不一致")))
      (println (if ok "  SELFTEST PASS" "  SELFTEST FAIL"))
      (try (.rmSync node-fs dir #js {:recursive true :force true}) (catch :default _ nil))
      (js/process.exit (if ok 0 1)))))

(when (argset "--selftest-source-twin-gate")
  (let [base {"src/a/elf64.cljc" "s1"      ; .clj を足すと shadow される
              "src/a/plat.cljs"  "s2"      ; .clj を足しても正当な split
              "src/a/legacy.clj" "s3"}     ; .cljc を足すと shadow する
        run (fn [add bm] (source-twin-gate! bm add))
        [k1 d1 i1] (run ["src/a/elf64.clj"] base)
        [k2 d2 _]  (run ["src/a/plat.clj"] base)
        [k3 d3 _]  (run ["src/a/brand-new.clj"] base)
        [k4 d4 _]  (run ["src/a/legacy.cljc"] base)
        [k5 d5 i5] (run ["src/a/elf64.clj"] nil)
        [k6 d6 i6] (run ["docs/README.md"] base)
        ok (and (= [] k1) (= ["src/a/elf64.clj"] d1) (true? (:applied i1)) (= 1 (:scanned i1))
                (= ["src/a/plat.clj"] k2) (= [] d2)
                (= ["src/a/brand-new.clj"] k3) (= [] d3)
                (= [] k4) (= ["src/a/legacy.cljc"] d4)
                (= ["src/a/elf64.clj"] k5) (= [] d5) (false? (:applied i5))
                (= ["docs/README.md"] k6) (= [] d6) (= 0 (:scanned i6)))]
    (println "source-twin-gate! selftest")
    (println (format "  (1) .clj over base .cljc      -> kept=%d demoted=%d %s"
                     (count k1) (count d1) (pr-str (:findings i1))))
    (println (format "  (2) .clj over base .cljs      -> kept=%d demoted=%d  ← 正当な platform split、通す"
                     (count k2) (count d2)))
    (println (format "  (3) 対応する base ファイル無し -> kept=%d demoted=%d" (count k3) (count d3)))
    (println (format "  (4) .cljc over base .clj      -> kept=%d demoted=%d" (count k4) (count d4)))
    (println (format "  (5) base tree が引けない       -> kept=%d demoted=%d applied=%s  ← 通すが「未適用」と申告"
                     (count k5) (count d5) (:applied i5)))
    (println (format "  (6) Clojure source ではない     -> kept=%d demoted=%d scanned=%d"
                     (count k6) (count d6) (:scanned i6)))
    (println (if ok "  SELFTEST PASS" "  SELFTEST FAIL"))
    (js/process.exit (if ok 0 1))))

(when (argset "--selftest-tracked-gate")
  (let [dir (str (.tmpdir node-os) "/cleanup-land-gate-selftest")
        w! (fn [rel content]
             (let [f (io/file dir rel)]
               (.mkdirSync node-fs (.getPath (.getParentFile f)) #js {:recursive true})
               (.writeFileSync node-fs (.getPath f) content)))
        _ (do (try (.rmSync node-fs dir #js {:recursive true :force true}) (catch :default _ nil))
              (w! "clj/.cpcache/1.basis" "{:classpath {\"/Users/x/.m2/foo.jar\" {}}}")
              (w! ".shadow-cljs/builds/test/dev/out/cljs-runtime/a.js" "goog.provide('a');")
              (w! "src/app/core.cljc" "(ns app.core)\n(defn go [] :ok)\n")
              (w! "secrets/id_ed25519" "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\n"))
        paths ["clj/.cpcache/1.basis"
               ".shadow-cljs/builds/test/dev/out/cljs-runtime/a.js"
               "src/app/core.cljc"
               "secrets/id_ed25519"]
        ;; (1) base tree が引けて、junk が base に無い = 新しいビルド副産物
        [kept-a drop-a info-a] (tracked-safety-gate! dir {"src/app/core.cljc" "deadbeef"} paths)
        ;; (2) 同じ junk が base に在る = その repo は意図して track している
        [kept-b drop-b _] (tracked-safety-gate!
                            dir {"src/app/core.cljc" "deadbeef"
                                 "clj/.cpcache/1.basis" "cafe"
                                 ".shadow-cljs/builds/test/dev/out/cljs-runtime/a.js" "cafe"}
                            paths)
        ;; (3) base tree が引けない = junk 判定は下せない。credential だけ落として申告する
        [kept-c drop-c info-c] (tracked-safety-gate! dir {} paths)
        n (fn [m] (reduce + (map (comp count val) m)))]
    (println "tracked-safety-gate! selftest")
    (println (format "  (1) junk が base に無い    -> kept=%d dropped=%d %s  applied=%s"
                     (count kept-a) (n drop-a) (pr-str (into {} (for [[k v] drop-a] [k (count v)]))) (:applied info-a)))
    (println (format "  (2) junk が base に在る    -> kept=%d dropped=%d %s"
                     (count kept-b) (n drop-b) (pr-str (into {} (for [[k v] drop-b] [k (count v)])))))
    (println (format "  (3) base tree が引けない   -> kept=%d dropped=%d %s  applied=%s"
                     (count kept-c) (n drop-c) (pr-str (into {} (for [[k v] drop-c] [k (count v)]))) (:applied info-c)))
    (let [ok (and (= #{"src/app/core.cljc"} (set kept-a))
                  (= 2 (count (:skip-junk-new drop-a)))
                  (= 1 (count (:skip-credential drop-a)))
                  (= true (:applied info-a))
                  (= 3 (count kept-b))
                  (nil? (:skip-junk-new drop-b))
                  (= 1 (count (:skip-credential drop-b)))
                  (= 1 (count (:skip-credential drop-c)))
                  (nil? (:skip-junk-new drop-c))
                  (= :partial (:applied info-c)))]
      (try (.rmSync node-fs dir #js {:recursive true :force true}) (catch :default _ nil))
      (println (if ok "  SELFTEST PASS" "  SELFTEST FAIL"))
      (js/process.exit (if ok 0 1)))))

(def all-git-paths
  ;; `-type d` を付けてはならない。**`.git` はファイルのこともある** —— submodule
  ;; 時代の gitdir ポインタ（`gitdir: ../../../.git/modules/...`）と linked worktree が
  ;; そう。実測 2026-08-22、`orgs/` の 5 checkout がこの形で、そのうち
  ;; `orgs/kotoba-lang/kotoba` は untracked 22 + tracked 変更 10（codebase-actor /
  ;; IPLD / semantic supply-chain の一式、7,494 行）を抱えていた。どの branch にも
  ;; どの remote にも無く、共有 checkout で `git checkout` が走れば消える状態である。
  ;;
  ;; **`-type d` はそれを「repo が 0 件」として報告した。**「見に行けなかった」が
  ;; 「見に行って何も無かった」と同じ出力になる、ADR-2608136000 の形そのもの。
  ;; スタブ `.git` の防御は `own-repo-root?`（`rev-parse --show-toplevel` が dir 自身に
  ;; 解決するか）が既に担っており、それは `.git` がファイルでも正しく働く —— つまり
  ;; `-type d` は防御には寄与しておらず、見える範囲を狭めていただけだった。
  (->> (sh "find" "orgs" "-maxdepth" "3" "-name" ".git")
       :out str/trim str/split-lines
       (remove str/blank?) sort
       (map #(subs % 0 (- (count %) 5)))))

(def gitdir-file-repos
  ;; 証拠床: 「今回 `-type d` なら見えなかったはずの checkout」を数える。
  ;; 0 件でも印字する（数えたことと、数えて 0 だったことを区別できるように）。
  (->> all-git-paths
       (filter (fn [d] (try (.isFile (.statSync node-fs (str d "/.git")))
                            (catch :default _ false))))))

(def repos
  ;; `--names` は `own-repo-root?` より **前** に当てる。順序を逆にすると、1 repo に
  ;; 絞ったつもりの run が 4,296 回の `git rev-parse --show-toplevel` を spawn してから
  ;; 絞り込む —— 実測 2026-09-01（load 690 のこの機械）、`--names animeka` が evidence
  ;; 行（下の「走査 N checkout」）に到達する前に数分かかり、その間の出力は header 3 行
  ;; だけだった。**「走査中」と「対象 0 repo」が出力から区別できない**という、この
  ;; script 自身が gitdir-file-blindspot で警告している形である。3 つの述語はどれも
  ;; 純粋（`annex?` は statSync、`own-repo-root?` は rev-parse + realpath 比較、名前
  ;; 照合は文字列)なので、並べ替えても結果集合は変わらない —— 変わるのは費用だけ。
  (->> all-git-paths
       (filter (fn [d] (if only-names (some #(str/ends-with? d (str "/" %)) only-names) true)))
       (remove annex?)
       (filter own-repo-root?)))

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

(println (format "\n走査 %d checkout（うち .git がファイル = 旧 -type d では不可視だった分 %d）"
                 (count all-git-paths) (count gitdir-file-repos)))
(println (format "対象 %d repo（--max により %d repo を打切り）" (count selected) dropped))
(def outcomes (mapv (fn [p] [p (land-repo! p)]) selected))
(def archived-skipped (filterv #(= (second %) :archived-skip) outcomes))
(def acted (mapv first (remove #(= (second %) :archived-skip) outcomes)))

(println (format "\n完了: %d repo 処理 / additive=%d repo / review=%d repo"
                 (count acted)
                 (count (:additive @planned))
                 (count (:review @planned))))
(when (seq archived-skipped)
  ;; 別枠で数える。混ぜると backlog が減らないように見え続ける（cleanup-workflow.edn
  ;; :preservation-pr-disposition と同じ理由）。件数は残置であって消滅ではない。
  (println (format "skip archived-repo: %d repo / 着地対象 %d 件を残置（GitHub 上で read-only。オーナー判断 2026-08-14「archive repo は対象外」）"
                   (count archived-skipped)
                   (reduce + (for [[p _] archived-skipped]
                               (+ (count (:additive p)) (count (:tracked p))))))))
(when (pos? dropped)
  (println (format "⚠ --max で %d repo を処理していない。再実行して残りを処理すること。" dropped)))
(println "※ ローカルの WIP は一切削除していない（archive + 着地のみ）。")

;; ── west 登録の受け渡し（2026-08-23 追加、ADR-2608230300）──────────────────
;;
;; この script は **GitHub に着地させるところまで**しかやらない。west への登録は
;; 別の道具（scripts/west-triple-sync.cljs）が持っている。skill git-cleanup-conflict
;; は「Push to GitHub alone is not done … Incomplete = GH-only orphan」と書いて
;; いるが、**その受け渡しは散文にしか無く、出力には現れなかった**。着地して満足
;; して終わる経路が、GH-only orphan を作る主要な経路である（ADR-2607173200 の
;; crm がまさにこの形: push 済み・west 未登録・consumer 3 件が壊れる）。
;;
;; ここでやるのは検出と次の 1 コマンドの提示だけで、登録はしない（登録は
;; repos.edn と west.yml を書き換えるので、plan を見てから人/agent が回す）。
(let [west-text (try (.readFileSync node-fs "manifest/west.yml" "utf8")
                    (catch :default _ nil))
      west-paths (when west-text
                   (set (map second (re-seq #"(?m)^\s+path:\s+(\S+)" west-text))))
      touched (map :dir acted)
      unregistered (when west-paths
                     (->> touched (remove #(contains? west-paths %)) sort vec))]
  (println)
  (cond
    ;; west.yml を読めなかった run が「登録漏れ 0 件」と言わないこと。
    (nil? west-paths)
    (println "⚠ west 登録の確認: manifest/west.yml を読めなかった。未測定（0 件ではない）。")

    (empty? touched)
    (println "west 登録の確認: 着地対象 0 repo。確認対象なし。")

    (empty? unregistered)
    (println (format "west 登録の確認: 処理した %d repo はすべて west.yml に path を持つ。"
                     (count touched)))

    :else
    (do
      (println (format "⚠ west 未登録のまま着地した repo: %d / %d"
                       (count unregistered) (count touched)))
      (println "   GitHub に push しただけでは終わっていない —— fresh checkout と CI は")
      (println "   この repo を解決できない（:local/root で参照している consumer は壊れる）。")
      (doseq [d unregistered] (println (str "     " d)))
      (println "   次の 1 手（plan が既定。apply で repos.edn + west.yml を書く）:")
      (println (str "     nbb scripts/west-triple-sync.cljs plan --names "
                    (str/join "," (map #(last (str/split % #"/")) unregistered))))
      (println "   ⚠ この確認が見ているのは west.yml の path: だけ。repos.edn の")
      (println "     :extra-projects に載っているかは見ていない（両方要る）。"))))

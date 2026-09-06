#!/usr/bin/env nbb
;; PreToolUse(Bash) ガード: **本番** デプロイを、`:local/root` 依存の checkout が
;; west pin からずれている（または dirty な）状態からビルドされた artifact で
;; 実行しようとした時にブロックする。
;;
;; なぜ必要か（実インシデント 2026-08-03、このガードが生まれた日）:
;;   network-isekai を `origin/main` と完全一致する worktree からビルドして
;;   isekai.network に deploy した。checkout は main を包含しており、既存の
;;   wrangler-deploy-main-sync-guard は正しく通した。ビルドは exit 0、
;;   verify-deploy-assets も ok、本番のバイト列はローカル成果物と一致した。
;;   **それでも本番の全 3D ゲームが boot しなくなった。**
;;
;;   `deps.edn` の `:local/root` は west pin ではなく **共有 checkout の今の状態**
;;   を指す。その日 24 本中 8 本が pin からずれており、特に `kotoba-lang/host` が
;;   ローカル 9c47171(07-12) / pin 8a5b460c(08-01) — ADR-2608022000 で入った
;;   `kami.input` の `:scale` API を欠いた古いコードが bundle に入り、
;;   `isekai.game/scene-input-map` が `TypeError` で落ちた。約25分間、本番が死んだ。
;;
;;   main 同期ガードはこれを見られない。あれが検査するのは「この checkout が
;;   main を包含しているか」であって、「この checkout が *何に対して* ビルド
;;   されるか」ではない。deploy は push と違って、成果物がどの依存から作られたかを
;;   誰も検査しない——**ビルドが通ることは、正しい依存から作られたことを意味しない。**
;;
;; 方針:
;;   - 本番デプロイのみ対象。`--env <name>`（staging/testnet 等）と `--dry-run` は素通り
;;     （既存 deploy ガードと同じ理由: 隔離環境の検証を塞ぐと検証自体ができなくなる）。
;;   - `deps.edn` の `:local/root` が topdir の `orgs/` 配下を指すものだけを見る。
;;     外部 path や `:local/root` を持たない repo は素通り。
;;   - pin ずれ **と dirty** の両方を deny する。dirty は他セッションの WIP が
;;     黙って本番 artifact に入るということ（実際 uikit に未コミット変更があった）。
;;   - 破壊的な自動同期はしない（deny + 指示のみ）。
;;   - fail-open: 判定途中のあらゆる失敗でデプロイを許可する。ガードが壊れて
;;     本番デプロイが全面停止する方が、退行より高くつく（既存ガードと同じ判断）。

(require '[cheshire.core :as json]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[scripts.nbb-compat :as compat]
         '["fs" :as fs]
         '["path" :as path])

(defn git [dir & args]
  (try
    (let [{:keys [exit out]} (p/sh (into ["git" "-C" dir] (map str args)))]
      (when (zero? exit) (str/trim out)))
    (catch :default _ nil)))

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

;; 既存 wrangler-deploy-main-sync-guard と同一に保つこと。片方だけが反応する形にすると、
;; 「main 同期は見るが pin は見ない」窓ができる。
;;
;; `pages\s+` を含むのは必須。**これが無いと Cloudflare Pages のデプロイが
;; ガードを丸ごと素通りする** — 2026-08-03 のインシデントで、`npx wrangler pages deploy`
;; は既存ガードに評価すらされなかった（`wrangler\s+deploy` にしかマッチしないため）。
;; isekai.network も kotobase.net の Pages も、本番はこの形で出る。
(def ^:private deploy-re
  #"(?:wrangler\s+(?:pages\s+)?(?:versions\s+)?deploy\b|wrangler\s+pages\s+deployment\s+create\b|(?:npm|pnpm|yarn)\s+(?:run\s+)?deploy\b)")

(def ^:private named-env-re
  #"--env(?:\s+|=)(?!\"\"|''|\s|$)([^\s;&|]+)")

(defn- find-topdir
  "`manifest/west.yml` を持つ最も近い祖先。west の topdir 探索と同じ向き。"
  [start]
  (loop [d (path/resolve start) n 0]
    (cond
      (> n 12) nil
      (fs/existsSync (path/join d "manifest" "west.yml")) d
      :else (let [up (path/dirname d)]
              (when (not= up d) (recur up (inc n)))))))

(defn- west-pins
  "west.yml → {path → revision}。project ブロックごとに切って読むので、
   revision を持たない entry があっても隣のブロックと混ざらない。"
  [west-yml]
  (into {}
        (keep (fn [chunk]
                (let [rev (second (re-find #"(?m)^\s*revision:\s*(\S+)" chunk))
                      pth (second (re-find #"(?m)^\s*path:\s*(\S+)" chunk))]
                  (when (and rev pth) [pth rev]))))
        (rest (str/split west-yml #"(?m)^\s*-\s+name:\s*"))))

(defn- local-roots
  "deps.edn の `:local/root \"…\"` を絶対パスに解決して返す。"
  [repo-dir]
  (let [f (path/join repo-dir "deps.edn")]
    (when (fs/existsSync f)
      (->> (re-seq #":local/root\s+\"([^\"]+)\"" (str (fs/readFileSync f "utf8")))
           (map (comp #(path/resolve repo-dir %) second))
           distinct
           vec))))

(defn- wrangler-config
  "The wrangler config in `dir`, as text, or nil."
  [dir]
  (some (fn [n]
          (let [f (path/join dir n)]
            (when (fs/existsSync f) (str (fs/readFileSync f "utf8")))))
        ["wrangler.toml" "wrangler.json" "wrangler.jsonc"]))

(defn- self-contained-deploy?
  "Whether the artifact this deploy ships is exactly the files in `dir`.

  The guard's own policy says `:local/root` を持たない repo は素通り, but it
  reads `deps.edn` from the GIT TOPLEVEL — so a Worker that is one JavaScript
  file in a subdirectory is judged by the JVM application's dependencies, which
  cannot reach it. Measured 2026-09-06: `services/agent-edge` (no deps.edn, no
  build step, `main` beside it) was blocked by a 135-byte untracked markdown
  file in `kotoba-lang/kotobase`, a library it does not and cannot link.

  This narrows the guard to what it claims to measure rather than weakening it.
  All four must hold, and any one of them failing falls back to the toplevel
  check:

    - the directory has a wrangler config of its own (it IS the project)
    - it has no `deps.edn` and no `shadow-cljs.edn` (nothing Clojure to build)
    - its wrangler config declares no `[build]` (nothing else builds into it)
    - `main` resolves INSIDE the directory (the artifact is not carried in
      from a tree built elsewhere)

  A project that has its own `deps.edn` is NOT skipped: it falls through to the
  toplevel check, exactly as before. This adds an exit, it does not change
  which file the check reads — `services/app-edge` still gets judged by the
  application's root `deps.edn` rather than by its own, which names jp-go-dds
  and kotoba-kir. Making the check read the nearest file is a separate, larger
  change; claiming it here would be this comment asserting more than the code
  does. Measured 2026-09-06: app-edge and the repo root both still deny."
  [dir]
  (when-let [cfg (wrangler-config dir)]
    (let [main (some-> (re-find #"(?m)^\s*\"?main\"?\s*[=:]\s*[\"']([^\"']+)[\"']" cfg)
                       second)]
      (and (not (fs/existsSync (path/join dir "deps.edn")))
           (not (fs/existsSync (path/join dir "shadow-cljs.edn")))
           ;; `[build]` in TOML, `"build"` in JSON. Either means something else
           ;; produces what ships, and that something else is out of view here.
           (not (re-find #"(?m)^\s*\[build\]" cfg))
           (not (re-find #"(?m)^\s*\"build\"\s*:" cfg))
           (some? main)
           (let [abs (path/resolve dir main)
                 rel (path/relative dir abs)]
             (not (str/starts-with? rel "..")))))))

(try
  (let [cmd (or (some-> (compat/read-stdin)
                        (json/parse-string true)
                        (get-in [:tool_input :command]))
                "")]
    (when-not (re-find deploy-re cmd) (allow!))
    (when (str/includes? cmd "--dry-run") (allow!))
    (when (re-find named-env-re cmd) (allow!))

    (let [cd  (some-> (re-find (re-pattern (str "cd\\s+(" path-tok-src ")")) cmd)
                      second strip-quotes)
          dir (or cd ".")
          top (git dir "rev-parse" "--show-toplevel")]
      (when (str/blank? top) (allow!))
      ;; A project that ships only its own files cannot ship a stale dependency.
      (when (self-contained-deploy? (path/resolve dir)) (allow!))
      (let [roots (local-roots top)]
        (when (empty? roots) (allow!))
        (let [topdir (find-topdir top)]
          (when-not topdir (allow!))
          (let [pins (west-pins (str (fs/readFileSync (path/join topdir "manifest" "west.yml") "utf8")))
                classified
                (map (fn [abs]
                       (let [rel (path/relative topdir abs)
                             inside? (not (str/starts-with? rel ".."))
                             pin (get pins rel)]
                         (cond
                           ;; topdir の外の依存はこのガードの担当外（west が pin を
                           ;; 持たないので比べる相手が無い）。判定不能ではなく圏外。
                           (not inside?) nil
                           ;; **workspace の中にあるのに manifest に載っていない依存。**
                           ;; pin が無いので deny できないが、artifact には入る。
                           ;; 黙って落とすと「検査して問題なし」と区別が付かない。
                           (nil? pin) {:rel rel :kind :unpinned}
                           :else
                           (let [head  (git abs "rev-parse" "HEAD")
                                 dirty (git abs "status" "--porcelain")]
                             (cond
                               (str/blank? head) {:rel rel :kind :unreadable}
                               (not= head pin)   {:rel rel :kind :stale
                                                  :head (subs head 0 8) :pin (subs pin 0 8)}
                               (not (str/blank? dirty))
                               {:rel rel :kind :dirty
                                :files (count (str/split-lines dirty))})))))
                     roots)
                drift     (filter (comp #{:stale :dirty} :kind) classified)
                unchecked (filter (comp #{:unpinned :unreadable} :kind) classified)]
            ;; deny できない分は **必ず名指しで報告する**。ガードが「見たが問題なし」
            ;; と「そもそも見られなかった」を同じ沈黙で表すのが、このクラスの欠陥。
            (when (seq unchecked)
              (js/console.error
               (str "deploy-local-root-pin-guard: " top
                    " の `:local/root` 依存 " (count unchecked)
                    " 本を検査できませんでした（west.yml に pin が無い / HEAD が読めない）: "
                    (str/join ", " (map (fn [{:keys [rel kind]}]
                                          (str rel "(" (name kind) ")")) unchecked))
                    "。**これらは artifact に入りますが、このデプロイでは検査されていません。**")))
            (when (seq drift)
              (deny!
               (compat/format
                (str "%s: `:local/root` 依存 %d 本が west pin と一致していません。"
                     "本番デプロイをブロックしました——ビルドが通っても、"
                     "**古い / 他人の WIP を含む依存から作られた artifact** は本番を壊します"
                     "（2026-08-03 に実際に発生: 古い kotoba-lang/host にリンクした"
                     "isekai.network の bundle で全 3D ゲームが boot 不能になり約25分ダウン）。\n\n"
                     "%s\n\n"
                     "pin に揃えてから再ビルドしてください:\n"
                     "  cd %s && west update --fetch smart %s\n\n"
                     "dirty な依存は **破棄しないこと**（他セッションの WIP の可能性）。"
                     "その依存だけ pin の clean な worktree を作り、ビルドをそちらに向けてください:\n"
                     "  git -C <dep> worktree add --detach <tmp> <pin>\n\n"
                     "隔離環境へのデプロイ（--env <name>）と --dry-run はブロックしません。")
                top (count drift)
                (str/join "\n"
                          (map (fn [{:keys [rel kind head pin files]}]
                                 (if (= :stale kind)
                                   (compat/format "  STALE %s  local=%s  pin=%s" rel head pin)
                                   (compat/format "  DIRTY %s  (%d uncommitted file(s))" rel files)))
                               drift))
                topdir
                (str/join " " (map (comp path/basename :rel) drift)))))))))
    (allow!))
  (catch :default _ (compat/exit 0)))

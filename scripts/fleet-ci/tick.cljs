#!/usr/bin/env nbb
;; tick.cljs — fleet-native CI/CD の tick（murakumo mac-mini fleet を git の CD/CI にする本体）。
;;
;; ADR-2607178000 の standing runner を 4 点拡張したもの:
;;   A) trigger を **pin の6時間ごと回帰** から **default branch tip の変化駆動**に
;;      （5 分間隔で tip を見て、前回検証した sha と違う repo だけ gate を回す）
;;   B) 実行先を zebulun/asher ハードコードから **nodes.edn の capability による
;;      動的割り当て + fan-out** に（ノードの provision/増減は probe.cljs 再実行で反映）
;;   C) 結果を **GitHub commit status** として書き戻す（fleet の検証が commit/PR 上に
;;      出て、required status check にできる。Checks API は GitHub App 必須だが
;;      statuses は既存 gh の repo scope で足りる）
;;   D) green なら **west pin を前進**させる（CD。既存のサーバ側 pin 検証
;;      scripts/verify-west-pins.cljs を必ず通し、branch + PUT + server-side merge。
;;      fleet-owner-key は使わない = 署名権限の昇格なし）
;;
;; 不変条件（ADR-2607178000 から引き継ぐ）:
;;   * 対象 commit の tree は **毎回 GitHub の tarball API から取得**する。ローカル
;;     checkout を信用しない（stale/dirty 汚染を構造的に排除）。
;;   * gate は必ず「展開が成功したこと」を assert してから実行する（ADR addendum の
;;     false-pass 事故 = deps.edn の無いディレクトリで clojure が REPL に落ちて exit 0）。
;;   * receipt の署名は **弱権限鍵 fleet-agent-murakumo-ci** のみ。pin 前進は
;;     この鍵ではなく operator の gh 認証 + サーバ側検証で行う（鍵の権限は増やさない）。
;;   * 署名鍵はこのマシン（operator）から出さない。ノードはテストを実行するだけ。
;;   * 秘密情報をノードに配らない: private repo の tree は operator が認証付きで取得し、
;;     tarball を **ssh stdin** でノードに流す（ノードに token を置かない）。
;;
;; 使い方:
;;   nbb scripts/fleet-ci/tick.cljs                   ;; 通常の tick（変化した repo のみ）
;;   nbb scripts/fleet-ci/tick.cljs --dry-run         ;; gate まで実行するが landing/status/CD をしない
;;   nbb scripts/fleet-ci/tick.cljs --only kagami     ;; repo を絞る（変化が無くても回す）
;;   nbb scripts/fleet-ci/tick.cljs --all             ;; 全 repo を強制的に回す
;;   nbb scripts/fleet-ci/tick.cljs --no-cd           ;; pin 前進（CD）だけ止める
;;   nbb scripts/fleet-ci/tick.cljs --plan            ;; 何を回すかだけ出して終わる
(ns fleet-ci.tick
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; args / paths

(defn parse-args [argv]
  (loop [opts {} [a & more] argv]
    (cond
      (nil? a) opts
      (str/starts-with? a "--")
      (let [k (keyword (subs a 2))]
        (if (or (nil? (first more)) (str/starts-with? (first more) "--"))
          (recur (assoc opts k true) more)
          (recur (assoc opts k (first more)) (rest more))))
      :else (recur opts more))))

(def opts (parse-args *command-line-args*))
(def here (path/dirname *file*))   ;; nbb: js/__filename は nil。*file* が正
(def root (path/resolve here ".." ".."))          ;; superproject checkout
(def gftd (path/join (os/homedir) ".gftd"))
(def state-file (or (:state opts) (path/join gftd "fleet-ci-state.edn")))
(def log-file (or (:log opts) (path/join gftd "fleet-ci-tick.log")))
(def lock-file (path/join gftd "fleet-ci-tick.lock"))
(def cache-dir (path/join gftd "fleet-ci-cache"))

(defn now [] (.toISOString (js/Date.)))
;; sha は nil のことがある（tip 解決失敗）。subs で落ちないよう必ずここを通す。
(defn sha7 [s] (let [s (str s)] (if (>= (count s) 7) (subs s 0 7) (if (seq s) s "?"))))
(defn sha12 [s] (let [s (str s)] (if (>= (count s) 12) (subs s 0 12) (if (seq s) s "?"))))

(defn log [& xs]
  (let [line (str (now) " " (str/join " " (map #(if (string? %) % (pr-str %)) xs)))]
    (println line)
    (try (fs/mkdirSync gftd #js {:recursive true}) (catch :default _))
    (try (fs/appendFileSync log-file (str line "\n")) (catch :default _))))

(defn die [msg] (log "FATAL" msg) (js/process.exit 1))

;; ---------------------------------------------------------------------------
;; shell / gh

(defn sh
  "同期実行。-> {:exit n :out s}（throw しない）"
  ([cmd args] (sh cmd args nil))
  ([cmd args {:keys [input timeout binary?] :as o}]
   (try
     (let [out (cp/execFileSync cmd (clj->js (vec args))
                                (clj->js (cond-> {:maxBuffer (* 256 1024 1024)
                                                  :timeout (or timeout 600000)}
                                           (not binary?) (assoc :encoding "utf8")
                                           input (assoc :input input))))]
       {:exit 0 :out (if binary? out (str out))})
     (catch :default e
       {:exit (or (.-status e) 1)
        :out (str (or (some-> (.-stdout e) str) "") (or (some-> (.-stderr e) str) "")
                  (when-not (.-status e) (str e)))}))))

;; launchd から動かす場合、gh は keyring を読めず匿名にフォールバックする（public repo の
;; GET だけ通り、書き込みが 401）。plist に token を書くのは論外（tracked file）なので、
;; **mode 600 のファイルから読んで子プロセスの env にだけ載せる**経路を用意する。
;; ファイルを作るかどうかは運用判断（作らなければ preflight が理由付きで skip する）。
(let [f (or (:gh-token-file opts) (.-FLEET_CI_GH_TOKEN_FILE js/process.env))]
  (when (and f (fs/existsSync f))
    (set! (.-GH_TOKEN js/process.env) (str/trim (str (fs/readFileSync f "utf8"))))))

(defn gh [& args] (sh "gh" (vec args)))

(defn gh! [& args]
  (let [{:keys [exit out]} (apply gh args)]
    (when-not (zero? exit)
      ;; どの endpoint で落ちたかを必ず残す（"gh api failed: 404" だけでは追えない）
      (die (str "gh " (str/join " " (remove #(str/starts-with? (str %) "-") args))
                " failed: " (str/trim out))))
    (str/trim out)))

(declare gh-blob-sha)

(defn gh-raw
  "repo の path を branch から取る。
  第一経路は contents API の raw media type（base64 を経由しないので速い）。ただし
  **実測（2026-07-25）で、725KB の manifest/west.yml に対してこの経路が exit 0 かつ
  空文字列を返すことがあった**（同時刻に 172KB の fleet-ci.edn は正常）。空を掴んだまま
  進むと『west.yml に project が 1 件も無い』＝全 repo skip という静かな無動作になるので、
  空なら git blobs API（base64、100MB まで）へフォールバックし、それでも空なら die する。"
  [repo branch p]
  (let [raw (gh! "api" "-H" "Accept: application/vnd.github.raw"
                 (str "repos/" repo "/contents/" p "?ref=" branch))]
    (if (seq raw)
      raw
      (let [_ (log "WARN contents-raw returned empty for" p "— falling back to git blobs API")
            b64 (gh! "api" (str "repos/" repo "/git/blobs/" (gh-blob-sha repo branch p))
                     "--jq" ".content")
            decoded (str (.toString (js/Buffer.from (str/replace b64 #"\s" "") "base64") "utf8"))]
        (when (empty? decoded)
          (die (str "could not fetch " p " from " repo "@" branch " (both contents-raw and git blobs came back empty)")))
        decoded))))

(defn gh-blob-sha
  "contents API の楽観ロック用 blob sha（ディレクトリ listing から取ると
  巨大な base64 を落とさずに済む）。"
  [repo branch p]
  (let [dir (or (path/dirname p) "")
        base (path/basename p)]
    (gh! "api" (str "repos/" repo "/contents/" dir "?ref=" branch)
         "--jq" (str ".[] | select(.name==\"" base "\") | .sha"))))

(defn gh-tip
  "上流 default branch の tip sha（1 call）。"
  [org-repo]
  (let [{:keys [exit out]} (gh "api" (str "repos/" org-repo "/commits?per_page=1")
                               "--jq" ".[0].sha")]
    (when (zero? exit) (str/trim out))))

;; ---------------------------------------------------------------------------
;; west.yml（生成物 — ここでは読み取りと **1 entry の revision 行だけ**の書き換え）

(defn parse-west
  "-> {:remotes {name url-base} :projects {name {:remote r :revision sha :path p}}}"
  [yaml]
  (loop [[l & more] (str/split-lines yaml)
         mode nil cur nil acc {:remotes {} :projects {}}]
    (if (nil? l)
      acc
      (cond
        (= l "  remotes:") (recur more :remotes nil acc)
        (= l "  projects:") (recur more :projects nil acc)
        (re-matches #"^  [a-z-]+:.*" l) (recur more nil nil acc)   ;; defaults/self/…
        :else
        (if-let [[_ nm] (re-matches #"^    - name: (\S+)\s*$" l)]
          (recur more mode nm (if (= mode :projects)
                                (assoc-in acc [:projects nm] {})
                                acc))
          (if-let [[_ k v] (re-matches #"^      ([a-z-]+): (.*?)\s*$" l)]
            (recur more mode cur
                   (case [mode k]
                     [:remotes "url-base"] (assoc-in acc [:remotes cur] v)
                     [:projects "remote"] (assoc-in acc [:projects cur :remote] v)
                     [:projects "revision"] (assoc-in acc [:projects cur :revision] v)
                     [:projects "path"] (assoc-in acc [:projects cur :path] v)
                     acc))
            (recur more mode cur acc)))))))

(defn org-of [west nm]
  (let [remote (get-in west [:projects nm :remote])
        url (get-in west [:remotes remote])]
    (when url (last (str/split url #"[:/]")))))

(defn replace-revision
  "west.yml の当該 entry の revision 行だけを差し替える（最小 diff）。
  entry が見つからない / revision 行が無い場合は nil（呼び出し側で fail）。"
  [yaml nm new-sha]
  (let [lines (vec (str/split-lines yaml))
        i (first (keep-indexed (fn [i l] (when (= l (str "    - name: " nm)) i)) lines))]
    (when i
      (let [j (first (for [k (range (inc i) (min (count lines) (+ i 8)))
                           :let [l (nth lines k)]
                           :when (re-matches #"^      revision: [0-9a-f]{40}\s*$" l)]
                       k))
            ;; 次の entry に入っていないことを確認（別 repo の revision を書き換えない）
            next-entry (first (for [k (range (inc i) (min (count lines) (+ i 8)))
                                    :when (str/starts-with? (nth lines k) "    - name: ")]
                                k))]
        (when (and j (or (nil? next-entry) (< j next-entry)))
          (str (str/join "\n" (assoc lines j (str "      revision: " new-sha)))
               (when (str/ends-with? yaml "\n") "\n")))))))

;; ---------------------------------------------------------------------------
;; state

(defn read-edn-file [f fallback]
  (if (fs/existsSync f)
    (try (reader/read-string (str (fs/readFileSync f "utf8")))
         (catch :default e (log "WARN unreadable" f (ex-message e)) fallback))
    fallback))

(def state (atom (read-edn-file state-file {:repos {}})))

(defn save-state! []
  (fs/mkdirSync gftd #js {:recursive true})
  (fs/writeFileSync state-file
                    (str ";; fleet-ci tick state — generated. 手編集不要（消せば全 repo を再検証する）\n"
                         (pr-str (assoc @state :updated-at (now))) "\n")))

;; ---------------------------------------------------------------------------
;; lock（5 分間隔なので前の tick が走っている可能性が常にある）

(defn pid-alive? [pid]
  (try (js/process.kill pid 0) true (catch :default _ false)))

(defn acquire-lock! []
  (fs/mkdirSync gftd #js {:recursive true})
  (when (fs/existsSync lock-file)
    (let [{:keys [pid at]} (read-edn-file lock-file {})
          age-min (/ (- (.getTime (js/Date.)) (.getTime (js/Date. (or at 0)))) 60000)]
      (cond
        (and pid (pid-alive? pid) (< age-min 90))
        (do (log "busy — tick" pid "still running (" (js/Math.round age-min) "min ) — skip")
            (js/process.exit 0))
        :else (log "WARN reclaiming stale lock" (pr-str {:pid pid :at at})))))
  (fs/writeFileSync lock-file (pr-str {:pid js/process.pid :at (now)})))

(defn release-lock! [] (try (fs/unlinkSync lock-file) (catch :default _)))

;; ---------------------------------------------------------------------------
;; preflight — credential が使えない context で走り始めない
;;
;; 実測（2026-07-25）: LaunchAgent（gui/<uid>）から起動すると macOS Keychain に
;; 触れる 2 経路が両方だめになる:
;;   * `gh` は keyring の token を読めず **匿名にフォールバック**する。public repo の
;;     GET は通るので途中まで進むが、status POST / contents PUT が HTTP 401 で落ちる。
;;   * `kagi get`（署名鍵）は Keychain の unlock prompt を出せず **120s timeout** する。
;; 対話セッションから同じ tick を走らせると両方通る（本番 tick を実証済み）。
;; → credential が無い context では **11 分かけて何も作らない** のをやめ、最初に
;;   fail fast して理由をログに残す。launchd 側で使う場合は
;;   `--signer-pem <file>`（または FLEET_CI_SIGNER_PEM）と GH_TOKEN を与える。

(defn signer-pem []
  (or (:signer-pem opts) (.-FLEET_CI_SIGNER_PEM js/process.env)))

(def under-launchd?
  ;; launchd 起動のプロセスには job label が XPC_SERVICE_NAME に入る（shell 起動だと
  ;; 未設定か "0"）。Keychain が使えない context の判定に使う — kagi を毎 tick 叩いて
  ;; 確かめる（= Keychain hit を増やす / 20s では終わらないこともある）のを避ける。
  (let [x (.-XPC_SERVICE_NAME js/process.env)]
    (boolean (and x (not= x "0")))))

(defn preflight!
  "-> nil（続行可）| 理由文字列（この tick は skip すべき）"
  [cfg]
  (let [{:keys [exit out]} (gh "api" "user" "--jq" ".login")]
    (cond
      (not (zero? exit))
      (str "gh is not authenticated in this context (writes would 401 — public-repo "
           "GETs still succeed anonymously, so this must be checked explicitly). "
           "Under launchd the keyring is unreadable: pass GH_TOKEN in the LaunchAgent "
           "env, or run the tick from an interactive session. detail: "
           (first (str/split-lines (str/trim out))))

      (and (signer-pem) (not (fs/existsSync (signer-pem))))
      (str "--signer-pem points at a missing file: " (signer-pem))

      (and under-launchd? (not (signer-pem)))
      (str "running under launchd (" (.-XPC_SERVICE_NAME js/process.env) ") without "
           "--signer-pem: kagi cannot show the Keychain unlock prompt there and blocks "
           "until timeout, so no receipt could be signed. Provide FLEET_CI_SIGNER_PEM "
           "(mode 600, the ADR-2607178000 pattern) or run the tick from an interactive session.")

      :else
      (do (log "preflight: gh authenticated as" (str/trim out)
               (if (signer-pem) "· signer=pem" "· signer=kagi"))
          nil))))

;; ---------------------------------------------------------------------------
;; kagami（fleet CLI）— **tip の kagami を使う**。ローカル checkout は west pin に
;; 縛られていて --subject-extra を持たない可能性がある（自己ホスト: CI ツール自身も
;; 常に最新 main で動かす）。sha ごとに ~/.gftd/fleet-ci-cache に展開して再利用。

(def max-ship-mb 200)

(defn fetch-tarball!
  "GitHub tarball API を **ファイルへ直接** 流す（メモリに載せない — 展開後 1.6GB の
  repo が実在するので execFileSync の maxBuffer に載せる設計は破綻する）。"
  [org-repo sha out-file]
  (fs/mkdirSync (path/dirname out-file) #js {:recursive true})
  (let [{:keys [exit out]} (sh "bash" ["-c" (str "gh api repos/" org-repo "/tarball/" sha
                                                 " > " (js/JSON.stringify out-file))]
                               {:timeout 900000})]
    (if (zero? exit)
      true
      (do (log "ERROR tarball fetch" org-repo (sha12 sha) (str/trim out)) false))))

(defn ensure-tree!
  "org/repo の sha の tree を dir に展開して dir を返す（キャッシュ済みなら再取得しない）。
  展開の健全性（期待ファイルの存在）は呼び出し側が assert する。"
  [org-repo sha]
  (let [dir (path/join cache-dir (str (str/replace org-repo "/" "-") "-" (sha12 sha)))]
    (if (fs/existsSync dir)
      dir
      (let [tgz (str dir ".tar.gz")]
        (when-not (fetch-tarball! org-repo sha tgz)
          (die (str "tarball fetch failed for " org-repo "@" sha)))
        (fs/mkdirSync dir #js {:recursive true})
        (let [{:keys [exit out]} (sh "tar" ["xzf" tgz "-C" dir "--strip-components=1"])]
          (fs/unlinkSync tgz)
          (when-not (zero? exit) (die (str "tar extract failed: " out))))
        dir))))

(defn blob-tarball!
  "**必要な path だけ**を集めた tarball を作る（asset 重量 repo 用）。
  git trees API で 1 回だけ tree を取り、拡張子で絞った blob のみ落とす。
  実測: org-spirit-in-physics-comics の全 tarball は 1.6GB（assets）で、
  EDN を parse するだけの gate に毎回それを転送するのは論外だった。
  この経路なら数百 KB で済み、ノードには『検査対象そのもの』だけが渡る。"
  [org-repo sha {:keys [include-ext min-files] :as spec}]
  (let [f (path/join cache-dir (str (str/replace org-repo "/" "-") "-" (sha12 sha) "-paths.tar.gz"))]
    (if (fs/existsSync f)
      f
      (let [json (gh! "api" (str "repos/" org-repo "/git/trees/" sha "?recursive=1"))
            tree (js->clj (js/JSON.parse json) :keywordize-keys true)
            _ (when (:truncated tree)
                (log "WARN trees API truncated for" org-repo "— path fetch may be incomplete"))
            files (->> (:tree tree)
                       (filter #(= "blob" (:type %)))
                       (filter (fn [e] (some #(str/ends-with? (:path e) %) include-ext))))
            stage (fs/mkdtempSync (path/join (os/tmpdir) "fleet-ci-paths-"))]
        (log "path-fetch" org-repo (sha12 sha) ":" (count files) "files"
             (pr-str include-ext))
        (when (and min-files (< (count files) min-files))
          (die (str "path fetch found only " (count files) " files for " org-repo
                    " (expected >= " min-files ") — refusing to build a gate input that "
                    "would trivially pass")))
        (doseq [e files]
          (let [dest (path/join stage (:path e))
                content (gh! "api" (str "repos/" org-repo "/git/blobs/" (:sha e)) "--jq" ".content")]
            (fs/mkdirSync (path/dirname dest) #js {:recursive true})
            (fs/writeFileSync dest (js/Buffer.from (str/replace content #"\s" "") "base64"))))
        (let [{:keys [exit out]} (sh "tar" ["czf" f "-C" stage "."] {:timeout 300000})]
          (sh "rm" ["-rf" stage])
          (when-not (zero? exit) (die (str "tar create failed: " out))))
        f))))

(defn gate-input!
  "gate に渡す tarball を用意する。:include-ext があれば path 限定 tarball、
  無ければ repo 全体の tarball。ノードへ送るサイズに上限を掛ける。"
  [{:keys [org-repo tip include-ext min-files name]}]
  (let [f (if (seq include-ext)
            (blob-tarball! org-repo tip {:include-ext include-ext :min-files min-files})
            (let [f (path/join cache-dir (str (str/replace org-repo "/" "-") "-" (sha12 tip) ".tar.gz"))]
              (when-not (fs/existsSync f)
                (fs/mkdirSync cache-dir #js {:recursive true})
                (when-not (fetch-tarball! org-repo tip f) (die (str "gate input fetch failed: " name))))
              f))
        mb (/ (.-size (fs/statSync f)) 1048576)]
    (when (> mb max-ship-mb)
      (die (str name " gate input is " (js/Math.round mb) "MB (> " max-ship-mb
                "MB) — add :include-ext to gates.edn so only the inspected paths are shipped")))
    f))

;; ---------------------------------------------------------------------------
;; gate script 生成（ノード側で bash -s に stdin で流す。committed な .sh は作らない）

(def remote-base "/tmp/fleet-ci")

(defn remote-dir [nm sha] (str remote-base "/" nm "-" (subs sha 0 7)))

;; **SSH の exit code は信用できない。**
;; 実測（2026-07-25、この fleet の全ノード）: `ssh <node> false` も `ssh <node> 'exit 7'`
;; も **exit 0** を返す。Tailscale SSH のセッションは macOS 上で `/usr/bin/login` 配下に
;; 張られ（remote 側 $PPID が /usr/bin/login）、login が子プロセスの終了ステータスを
;; 伝播しないため。つまり「ssh で叩いて exit code を見る」型の gate は、テストが
;; 落ちていても必ず pass になる = 全部 false pass。
;; → 判定は **出力に埋めた sentinel `FLEET-CI-EXIT: <code>`** を operator 側で grep する
;;   ことで行う。exit code には一切依存しない。
(def exit-sentinel "FLEET-CI-EXIT:")

(defn gate-script
  "gate 1 本ぶんのノード側スクリプト。どの終了経路でも最後に
  `FLEET-CI-EXIT: <code>` を必ず出す（これが唯一の verdict 伝達路）。"
  [{:keys [name gate classpath entry script script-args]} node sha script-body]
  (let [d (remote-dir name sha)
        ;; JVM の依存解決（~/.gitlibs の git clone と maven）はノード内で共有なので、
        ;; 同一ノードで 2 本並列に走ると衝突する（実測: cognitect test-runner の
        ;; gitlibs clone が "destination path already exists and is not an empty
        ;; directory" で落ちた）。取得フェーズだけ mkdir lock で直列化し、テスト実行は
        ;; 並列のままにする。stale lock は 20 分で回収。
        dep-lock (str remote-base "/.deps.lock")
        fail-fn ["fail() { echo \"FLEET-CI: $1\"; echo \"FLEET-CI-EXIT: $2\"; exit 0; }"]]
    (str/join
     "\n"
     (concat
      ["set -u"
       "export PATH=/opt/homebrew/bin:/usr/local/bin:$PATH"
       (str "mkdir -p " remote-base)]
      fail-fn
      [(str "cd " d " || fail 'extract dir missing' 90")]
      (case gate
        :jvm-test
        [;; 展開失敗の false-pass を構造的に防ぐ（ADR-2607178000 addendum の事故）
         "test -f deps.edn || fail 'deps.edn missing after extract' 90"
         "grep -q ':test' deps.edn || fail 'no :test alias in deps.edn' 91"
         (str "export JAVA_HOME=" (or (:java-home node) "/opt/homebrew/opt/openjdk"))
         "export PATH=$JAVA_HOME/bin:$PATH"
         "java -version 2>&1 | head -1"
         (str "for i in $(seq 1 900); do mkdir " dep-lock " 2>/dev/null && break;"
              " [ -n \"$(find " dep-lock " -maxdepth 0 -mmin +20 2>/dev/null)\" ]"
              " && rmdir " dep-lock " 2>/dev/null; sleep 1; done")
         "clojure -P -M:test >/dev/null 2>&1"
         (str "rmdir " dep-lock " 2>/dev/null || true")
         ;; **テストが実際に走ったことも assert する**: summary 行が無い / 0 件は fail。
         "out=$(clojure -M:test 2>&1); code=$?"
         "echo \"$out\" | tail -25"
         "echo \"$out\" | grep -qE 'Ran [0-9]+ tests' || fail 'no test summary in output — refusing to report a pass' 93"
         "echo \"$out\" | grep -qE 'Ran 0 tests' && fail 'zero tests ran' 94"
         "echo \"FLEET-CI-EXIT: $code\""]
        :nbb-test
        [(str "test -f " (or entry "run-tests.cljs")
              " || fail 'test entry missing after extract' 90")
         (str "out=$(npx --yes nbb --classpath " (or classpath "src:test") " "
              (or entry "run-tests.cljs") " 2>&1); code=$?")
         "echo \"$out\" | tail -25"
         "echo \"$out\" | grep -qE 'Ran [0-9]+ tests' || fail 'no test summary in output — refusing to report a pass' 93"
         "echo \"$out\" | grep -qE 'Ran 0 tests' && fail 'zero tests ran' 94"
         "echo \"FLEET-CI-EXIT: $code\""]
        :nbb-script
        [(str "cat > " remote-base "/gate-" name ".cljs <<'FLEET_CI_GATE_EOF'\n"
              script-body
              "\nFLEET_CI_GATE_EOF")
         (str "out=$(npx --yes nbb " remote-base "/gate-" name ".cljs " d " "
              (str/join " " (map str script-args)) " 2>&1); code=$?")
         "echo \"$out\" | tail -25"
         "echo \"FLEET-CI-EXIT: $code\""]
        ["fail 'unknown gate kind' 92"])))))

(defn gate-command
  "ci-verify の --gate に渡す 1 行コマンド。
  ① tarball を ssh stdin でノードへ流して展開（token をノードに置かない）
  ② gate script を ssh stdin で流して実行
  ③ **verdict は出力の sentinel を operator 側で grep して決める**
     （ssh の exit code は Tailscale SSH + /usr/bin/login で必ず 0 になるため）"
  [{:keys [tarball script-file host name sha out-file]}]
  (let [d (remote-dir name sha)
        ssh-opts "-o BatchMode=yes -o ConnectTimeout=20"]
    (str "cat " tarball " | ssh " ssh-opts " " host
         " \"find " remote-base " -maxdepth 1 -type d -mtime +1 -exec rm -rf {} + 2>/dev/null;"
         " rm -rf " d "; mkdir -p " d "; tar xz -C " d " --strip-components=1"
         " && echo FLEET-CI-EXTRACT-OK\" > " out-file ".extract 2>&1; "
         "grep -q FLEET-CI-EXTRACT-OK " out-file ".extract"
         " || { tail -5 " out-file ".extract; echo 'FLEET-CI: extract failed on " host "'; exit 90; }; "
         "ssh " ssh-opts " " host " bash -s < " script-file " > " out-file " 2>&1; "
         "tail -30 " out-file "; "
         "grep -q '^" exit-sentinel " 0$' " out-file
         " || { echo 'FLEET-CI: gate did not report success on " host "'; exit 1; }")))

;; ---------------------------------------------------------------------------
;; node 割り当て

(defn slots
  "cap を満たすノードから (host 反復) の slot 列を作る。JVM 可能ノードは JVM gate の
  ために温存したいので、:node gate は非 JVM ノードを優先して埋める。"
  [nodes cap]
  (let [ns (filter #(and (:reachable? %) (contains? (set (:caps %)) cap)) nodes)
        ns (if (= cap :node)
             (sort-by #(if (contains? (set (:caps %)) :jvm) 1 0) ns)
             ns)]
    (vec (mapcat (fn [n] (repeat (:max-parallel n 1) n)) ns))))

(defn assign
  "work を batch に割る。1 batch = 全 slot を 1 周ぶん（= 同時に走る gate 群）。"
  [work nodes]
  (let [by-cap (group-by (fn [w] (if (= :jvm-test (:gate w)) :jvm :node)) work)
        jvm-slots (slots nodes :jvm)
        node-slots (slots nodes :node)
        chunk (fn [items slots]
                (if (empty? slots)
                  (mapv (fn [w] (assoc w :unassigned true)) items)
                  (->> items
                       (map-indexed (fn [i w] (assoc w :node (nth slots (mod i (count slots)))
                                                     :batch (quot i (count slots)))))
                       vec)))
        assigned (into (chunk (:jvm by-cap) jvm-slots)
                       (chunk (:node by-cap) node-slots))]
    (->> assigned (group-by :batch) (sort-by key) (map second))))

;; ---------------------------------------------------------------------------
;; landing（append-only receipt log / west.yml pin）— branch + PUT + server merge

(defn put-file!
  "repo の path を content に置き換える（branch 経由 → server-side merge → branch 削除）。
  blob sha による楽観ロックなので、他の書き手と race したら 409 で弾かれる（再試行は呼び側）。"
  [{:keys [repo branch path content message blob-sha work-branch]}]
  (let [body (js/JSON.stringify
              (clj->js {:message message
                        :content (.toString (js/Buffer.from content "utf8") "base64")
                        :branch work-branch
                        :sha blob-sha}))
        ;; branch を作る（既存なら無視）
        base-sha (gh! "api" (str "repos/" repo "/git/refs/heads/" branch) "--jq" ".object.sha")
        _ (gh "api" "--method" "POST" (str "repos/" repo "/git/refs")
              "-f" (str "ref=refs/heads/" work-branch) "-f" (str "sha=" base-sha))
        {:keys [exit out]} (sh "gh" ["api" "--method" "PUT"
                                     (str "repos/" repo "/contents/" path) "--input" "-"]
                               {:input body :timeout 180000})]
    (if-not (zero? exit)
      (do (gh "api" "--method" "DELETE" (str "repos/" repo "/git/refs/heads/" work-branch))
          {:ok false :detail (str/trim out)})
      (let [{:keys [exit out]} (sh "gh" ["api" (str "repos/" repo "/merges")
                                         "-f" (str "base=" branch) "-f" (str "head=" work-branch)
                                         "-f" (str "commit_message=" message)])]
        (gh "api" "--method" "DELETE" (str "repos/" repo "/git/refs/heads/" work-branch))
        (if (zero? exit)
          {:ok true :detail (str/trim out)}
          {:ok false :detail (str "merge failed: " (str/trim out))})))))

(defn append-receipt!
  "receipt 1 行を manifest/fleet-ci.edn に追記（append-only）。409 は再取得して再試行。
  path は landing の :receipts（:path ではない — landing map の key 名に合わせる）。"
  [{:keys [repo branch receipts]} line]
  (loop [attempt 1]
    (let [path receipts
          cur (gh-raw repo branch path)
          blob (gh-blob-sha repo branch path)
          content (str (if (str/ends-with? cur "\n") cur (str cur "\n")) line "\n")
          r (put-file! {:repo repo :branch branch :path path :content content
                        :message "fleet-ci: tip-driven murakumo tick receipt"
                        :blob-sha blob
                        :work-branch (str "agent/fleet-ci-receipt-"
                                          (subs (str (.getTime (js/Date.))) 4) "-" attempt)})]
      (cond
        (:ok r) {:ok true}
        (< attempt 3) (do (log "WARN receipt landing retry" attempt (:detail r)) (recur (inc attempt)))
        :else {:ok false :detail (:detail r)}))))

;; ---------------------------------------------------------------------------
;; commit status 書き戻し（Checks API は GitHub App 必須。statuses は repo scope で足りる）

(defn post-status! [org-repo sha {:keys [state context description url]}]
  (let [{:keys [exit out]}
        (gh "api" "--method" "POST" (str "repos/" org-repo "/statuses/" sha)
            "-f" (str "state=" state) "-f" (str "context=" context)
            "-f" (str "description=" (subs (str description) 0 (min 140 (count (str description)))))
            "-f" (str "target_url=" (or url "")))]
    (when-not (zero? exit) (log "WARN status post failed" org-repo (subs sha 0 7) (str/trim out)))
    (zero? exit)))

;; ---------------------------------------------------------------------------
;; CD: green なら pin 前進（サーバ側検証を必ず通す）

(defn advance-pin!
  "west.yml の当該 entry の revision を new-sha に進める。
  検証は scripts/verify-west-pins.cljs（存在 + default branch 到達性 + 前進）に委譲。"
  [{:keys [repo branch west] :as landing} nm new-sha]
  (let [cur (gh-raw repo branch west)
        old (get-in (parse-west cur) [:projects nm :revision])]
    (cond
      (nil? old) {:ok false :detail (str "entry not found in west.yml: " nm)}
      (= old new-sha) {:ok true :detail "already current"}
      :else
      (if-let [cand (replace-revision cur nm new-sha)]
        (let [tmp (fs/mkdtempSync (path/join (os/tmpdir) "fleet-ci-pin-"))
              basef (path/join tmp "base.yml")
              candf (path/join tmp "cand.yml")]
          (fs/writeFileSync basef cur)
          (fs/writeFileSync candf cand)
          (let [{:keys [exit out]} (sh "npx" ["nbb" (path/join root "scripts" "verify-west-pins.cljs")
                                              "--baseline" basef "--candidate" candf
                                              "--only" nm "--dir" root]
                                       {:timeout 300000})]
            (if-not (zero? exit)
              {:ok false :detail (str "pin verification refused: " (str/trim out))}
              (let [blob (gh-blob-sha repo branch west)
                    r (put-file! {:repo repo :branch branch :path west :content cand
                                  :message (str "west: advance " nm " pin to " (sha12 new-sha)
                                                " (fleet-ci green on murakumo)")
                                  :blob-sha blob
                                  :work-branch (str "agent/fleet-ci-pin-" nm "-" (sha7 new-sha))})]
                (if (:ok r) {:ok true :detail (str old " -> " new-sha)} r)))))
        {:ok false :detail "could not locate revision line (minimal-diff refused)"}))))

;; ---------------------------------------------------------------------------
;; main

(defn -main []
  (let [cfg (read-edn-file (path/join here "gates.edn") nil)
        nodes (:nodes (read-edn-file (path/join here "nodes.edn") {:nodes []}))
        _ (when-not cfg (die "gates.edn missing"))
        landing (:landing cfg)
        skip (preflight! cfg)
        _ (when skip
            (log "preflight SKIP —" skip)
            (release-lock!)
            (js/process.exit 0))
        dry? (boolean (:dry-run opts))
        plan? (boolean (:plan opts))
        only (when (:only opts) (set (map str/trim (str/split (str (:only opts)) #","))))
        west (parse-west (gh-raw (:repo landing) (:branch landing) (:west landing)))
        repos (cond->> (:repos cfg)
                only (filter #(contains? only (:name %))))
        ;; 各 repo の tip（fresh）と west pin
        work (vec (for [r repos
                        :let [nm (:name r)
                              org (org-of west nm)
                              org-repo (str org "/" nm)
                              tip (when org (gh-tip org-repo))
                              pin (get-in west [:projects nm :revision])
                              last-sha (get-in @state [:repos nm :sha])]]
                    (assoc r :org org :org-repo org-repo :tip tip :pin pin
                           :last-sha last-sha
                           :changed? (and tip (not= tip last-sha)))))
        missing (filter #(nil? (:tip %)) work)
        todo (cond
               (:all opts) (remove #(nil? (:tip %)) work)
               only (remove #(nil? (:tip %)) work)
               :else (filter :changed? work))]
    (doseq [m missing] (log "WARN no tip resolved (skipped):" (:name m) (:org-repo m)))
    (log "tick:" (count repos) "covered," (count todo) "to verify;"
         "nodes" (pr-str (mapv (juxt :host :caps) (filter :reachable? nodes))))
    (when (empty? todo)
      (log "nothing changed — no receipt")
      (release-lock!)
      (js/process.exit 0))
    (let [batches (assign todo nodes)]
      (doseq [[i b] (map-indexed vector batches)]
        (log "batch" i ":" (pr-str (mapv (fn [w] [(:name w) (sha7 (:tip w))
                                                  (get-in w [:node :host] :UNASSIGNED)]) b))))
      (when plan? (release-lock!) (js/process.exit 0))
      ;; kagami tip を自己ホストで使う（--subject-extra / --policy を持つ版）
      (let [kagami-org (str (org-of west "kagami") "/kagami")
            kagami-sha (gh-tip kagami-org)
            kagami-dir (ensure-tree! kagami-org kagami-sha)
            fleet-bin (path/join kagami-dir "bin" "fleet.cljs")
            _ (when-not (fs/existsSync fleet-bin) (die "kagami tree missing bin/fleet.cljs"))
            tmp (fs/mkdtempSync (path/join (os/tmpdir) "fleet-ci-tick-"))
            db-file (path/join tmp "fleet-db.edn")
            _ (fs/writeFileSync db-file (gh-raw (:repo landing) (:branch landing) (:db landing)))
            results (atom [])]
        (log "fleet CLI: kagami@" (sha12 kagami-sha))
        (doseq [[bi batch] (map-indexed vector batches)]
          (let [unassigned (filter :unassigned batch)
                batch (remove :unassigned batch)]
            (doseq [u unassigned]
              (log "WARN no capable node for" (:name u) "(gate" (:gate u) ") — skipped"))
            (when (seq batch)
              ;; pending status
              (when-not (or dry? (:no-status opts))
                (doseq [w batch]
                  (post-status! (:org-repo w) (:tip w)
                                {:state "pending"
                                 :context (str (:status-context-prefix cfg) "/" (name (:gate w)))
                                 :description (str "queued on " (get-in w [:node :host]))})))
              ;; gate ごとに tarball + script を用意
              (let [prepared
                    (vec (for [w batch]
                           (let [tgz (gate-input! w)
                                 body (when (:script w)
                                        (str (fs/readFileSync (path/join here (:script w)) "utf8")))
                                 sfile (path/join tmp (str "gate-" (:name w) ".bash-stdin"))
                                 _ (fs/writeFileSync sfile (gate-script w (:node w) (:tip w) body))
                                 gname (str "test-" (:name w) "-" (sha7 (:tip w))
                                            "-murakumo-" (get-in w [:node :host]))]
                             (assoc w :tarball tgz :script-file sfile :gate-name gname
                                    :cmd (gate-command {:tarball tgz :script-file sfile
                                                        :host (get-in w [:node :host])
                                                        :name (:name w) :sha (:tip w)
                                                        ;; gate のノード側出力をここに残す
                                                        ;; （verdict の grep 対象 + 失敗時の調査用）
                                                        :out-file (path/join tmp (str "gate-" (:name w) ".out"))})))))
                    out-file (path/join tmp (str "receipts-" bi ".edn"))
                    _ (fs/writeFileSync out-file "")
                    args (concat ["nbb" "--classpath" (path/join kagami-dir "src") fleet-bin
                                  "ci-verify"
                                  "--db" db-file
                                  "--repos" (str/join "," (map :name prepared))
                                  (if (signer-pem) "--key" "--kagi")
                                  (or (signer-pem) (:signer-kagi cfg) "fleet-agent-murakumo-ci")
                                  "--out" out-file
                                  "--policy" (:policy cfg)
                                  "--gate-timeout" (str (:gate-timeout-ms cfg))
                                  "--subject-extra"
                                  (pr-str {:tips (into {} (map (juxt :name :tip)) prepared)
                                           :pins (into {} (map (juxt :name :pin)) prepared)
                                           :nodes (into {} (map (fn [w] [(:name w) (get-in w [:node :host])])) prepared)
                                           :trigger :tip-change
                                           :runner "scripts/fleet-ci/tick.cljs"})
                                  "--gate" (str/join ";;" (map #(str (:gate-name %) "=" (:cmd %)) prepared))])
                    started (js/Date.now)
                    {:keys [exit out]} (sh "npx" args {:timeout (+ 120000 (:gate-timeout-ms cfg))
                                                       :input ""})]
                (log "batch" bi "ci-verify exit" exit
                     (str "(" (js/Math.round (/ (- (js/Date.now) started) 1000)) "s)"))
                (doseq [l (str/split-lines (str/trim out))] (log "  |" l))
                ;; 追記された receipt を読む
                (let [lines (->> (str/split (str (fs/readFileSync out-file "utf8")) #"\n")
                                 (remove str/blank?))
                      receipt (try (reader/read-string (last lines)) (catch :default _ nil))
                      checks (get-in receipt [:receipt :ci/checks])
                      outcome-of (fn [w]
                                   (let [k (keyword (str "gate/" (:gate-name w)))]
                                     (:outcome (first (filter #(= k (:name %)) checks)))))]
                  (if-not receipt
                    (log "ERROR no receipt produced for batch" bi)
                    (do
                      (when-not dry?
                        (let [r (append-receipt! landing (last lines))]
                          (log "receipt" (sha12 (:cid receipt))
                               (if (:ok r) "landed" (str "LANDING FAILED " (:detail r))))))
                      (doseq [w prepared]
                        (let [oc (outcome-of w)
                              ok? (= :pass oc)]
                          (swap! results conj (assoc w :outcome oc :cid (:cid receipt)))
                          (when-not (or dry? (:no-status opts))
                            (post-status! (:org-repo w) (:tip w)
                                          {:state (if ok? "success" "failure")
                                           :context (str (:status-context-prefix cfg) "/" (name (:gate w)))
                                           :description (str (if ok? "passed" "FAILED") " on murakumo/"
                                                             (get-in w [:node :host])
                                                             " — receipt " (sha12 (:cid receipt)))
                                           :url (str "https://github.com/" (:repo landing)
                                                     "/blob/" (:branch landing) "/" (:receipts landing))}))
                          (when-not dry?
                            (swap! state assoc-in [:repos (:name w)]
                                   {:sha (:tip w) :outcome oc :cid (:cid receipt) :at (now)})
                            (save-state!)))))))))))
        ;; ---- CD: green かつ pin が遅れているものを前進
        (let [cd? (and (get-in cfg [:cd :pin-advance-on-green])
                       (not (:no-cd opts)) (not dry?))
              green (filter #(and (= :pass (:outcome %)) (:cd %)
                                  (not= (:tip %) (:pin %))) @results)]
          (if-not cd?
            (when (seq green) (log "CD skipped (--no-cd/--dry-run):"
                                   (pr-str (mapv :name green))))
            (doseq [w green]
              ;; 直前に tip を再確認（gate 実行中に新 commit が来ていたら見送る）
              (let [tip-now (gh-tip (:org-repo w))]
                (if (not= tip-now (:tip w))
                  (log "CD skip" (:name w) "— tip moved during gate ("
                       (sha7 (:tip w)) "->" (sha7 tip-now) ")")
                  (let [r (advance-pin! landing (:name w) (:tip w))]
                    (log "CD pin-advance" (:name w) (if (:ok r) "OK" "FAILED") (:detail r))))))))
        (log "tick done —"
             (pr-str (mapv (fn [r] [(:name r) (:outcome r)]) @results)))))))

;; js/process.exit は try/finally を通らないので exit hook で必ず解放する。
(.on js/process "exit" (fn [_] (release-lock!)))
(acquire-lock!)
(-main)

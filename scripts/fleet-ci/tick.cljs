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
;;   nbb scripts/fleet-ci/tick.cljs --no-pr           ;; PR head の検証を止める（tip のみ）
;;   nbb scripts/fleet-ci/tick.cljs --pr-cap 3        ;; repo あたりの PR 検証上限（既定 10）
(ns fleet-ci.tick
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]
            ;; placement authority は murakumo.task.plan（ADR-2608111721 決定 1）。
            ;; この ns はもう配置を決めない —— `placement/assign` が murakumo へ
            ;; 委譲する（EMA cost は入力の順序付けとして残る）。
            [placement :as pl]))

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

(defn reject-input!
  "Refuse ONE gate's input without taking the tick down with it.

  The refusals this raises are all `the input for this gate is unusable` —
  under the :min-files floor, over the argv ceiling, over :max-ship-mb, or an
  archive that would not build. Every one of them is the right call for that
  gate and none of them says anything about the other gates in the matrix.

  They used to `die`. Measured 2026-08-11: amu's windows-loader gate hit its
  :min-files floor on an older sha (67 files, floor 70) and the process exited,
  so every work item after it in the run silently got no receipt at all — the
  ledger simply had no row, which reads exactly like `not scheduled yet` rather
  than `refused`. kotoba-selfhost-contracts was one of them, in the same batch,
  already assigned to a node.

  The refusal is kept — a gate whose input cannot be trusted must never run and
  must never pass. What changes is its blast radius, and that it is reported as
  an outcome instead of as the end of the run."
  [msg]
  (throw (ex-info msg {:fleet-ci/gate-input-rejected true})))

(defn gate-input-rejection
  "The message if `e` is a gate-input refusal, else nil (so it rethrows)."
  [e]
  (when (:fleet-ci/gate-input-rejected (ex-data e)) (ex-message e)))

;; ---------------------------------------------------------------------------
;; shell / gh

(defn sh
  "同期実行。-> {:exit n :out s}（throw しない）"
  ([cmd args] (sh cmd args nil))
  ([cmd args {:keys [input timeout binary? env-extra cwd] :as o}]
   (try
     (let [out (cp/execFileSync cmd (clj->js (vec args))
                                (clj->js (cond-> {:maxBuffer (* 256 1024 1024)
                                                  :timeout (or timeout 600000)}
                                           (not binary?) (assoc :encoding "utf8")
                                           input (assoc :input input)
                                           ;; placement の tools.deps 解決など、checkout
                                           ;; 固有の command は呼び側の cwd を保つ。
                                           cwd (assoc :cwd cwd)
                                           ;; token は env で子にだけ渡す（argv に出さない
                                           ;; = ps で見えない）
                                           env-extra (assoc :env (js/Object.assign
                                                                  #js {} js/process.env
                                                                  (clj->js env-extra))))))]
       {:exit 0 :out (if binary? out (str out))})
     (catch :default e
       {:exit (or (.-status e) 1)
        :out (str (or (some-> (.-stdout e) str) "") (or (some-> (.-stderr e) str) "")
                  (when-not (.-status e) (str e)))}))))


;; ---------------------------------------------------------------------------
;; git transport — GitHub の **API を使わない**経路（2026-07-26、オーナー方針
;; 「github token は使わない」）。
;;
;; 経緯: launchd から起動すると gh は Keychain の token を読めず匿名に落ちる。
;; private repo は匿名だと 404 なので preflight が毎 tick skip し、**scheduled
;; CI が一度も動かない**状態になっていた（実測 2026-07-26 11:00Z）。token を
;; 置けば直るが、置かない方針なので入力経路ごと git+SSH に移す。
;;
;; SSH なら token は要らない。実測: ssh-agent に identity が 1 つも無い状態でも
;; `git ls-remote git@github.com:com-junkawasaki/root.git HEAD` が private repo
;; の sha を返す（~/.ssh の鍵を直接使うので、launchd でも agent 不要）。
;;
;; API でしかできないこと＝**commit status の書き戻し**は落とした。反映面は
;; Radicle issue（:rad）に一本化する。

(def landing-repo
  "landing 先（= superproject）。mirror! が「自分自身は clone しない」判定に使う。
  gates.edn を直に読むのは、この定数が read-edn-file / cfg より前に必要だから。"
  (try (get-in (reader/read-string (str (fs/readFileSync (path/join here "gates.edn") "utf8")))
               [:landing :repo])
       (catch :default _ "com-junkawasaki/root")))

(defn ssh-url [org-repo] (str "git@github.com:" org-repo ".git"))

(defn git
  "git を1回叩く。dir が nil なら cwd。"
  [dir args & [opts]]
  (sh "git" (into (if dir ["-C" dir] []) args) (merge {:timeout 600000} opts)))

(defn git-tip
  "上流 default branch の tip sha。`ls-remote HEAD` は default branch を指すので
  branch 名を知らなくてよい（gh の commits API と同じ意味）。"
  [org-repo]
  (let [{:keys [exit out]} (git nil ["ls-remote" (ssh-url org-repo) "HEAD"] {:timeout 120000})]
    (when (zero? exit)
      (let [line (first (remove str/blank? (str/split-lines (str/trim out))))]
        (when line (first (str/split line #"\s+")))))))

(def mirror-dir (path/join cache-dir "mirrors"))

(defn mirror!
  "org/repo の bare mirror を cache に用意して path を返す（2回目以降は fetch のみ）。
  superproject 自身は自分の clone をそのまま使う — 既にここに居るので二重に持たない。"
  [org-repo]
  (if (= org-repo landing-repo)
    root
    (let [d (path/join mirror-dir (str (str/replace org-repo "/" "-") ".git"))]
      (if (fs/existsSync d)
        (do (git d ["fetch" "--prune" "--quiet" "origin" "+refs/heads/*:refs/heads/*"]) d)
        (do (fs/mkdirSync mirror-dir #js {:recursive true})
            (let [{:keys [exit out]} (git nil ["clone" "--mirror" "--quiet" (ssh-url org-repo) d]
                                          {:timeout 1800000})]
              (when-not (zero? exit) (die (str "git clone --mirror failed for " org-repo ": " (str/trim out))))
              d))))))

(defn git-show
  "ref:path の blob を文字列で。空なら nil（呼び側が die する）。"
  [dir ref p]
  (let [{:keys [exit out]} (git dir ["show" (str ref ":" p)] {:maxBuffer 268435456})]
    (when (and (zero? exit) (seq out)) out)))


(defn gh-raw
  "landing repo の path を branch の tip から読む。名前は呼び出し側との互換で
  残しているが、経路は git（fetch → show）で API は通らない。"
  [repo branch p]
  (let [d (mirror! repo)]
    (git d ["fetch" "--quiet" "origin" (str "+refs/heads/" branch ":refs/remotes/origin/" branch)]
         {:timeout 600000})
    (or (git-show d (str "origin/" branch) p)
        (die (str "could not read " p " from " repo "@" branch " over git")))))

(def gh-tip git-tip)

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

(defn gate-id
  "gates.edn entry の識別子。既定は :name（= repo 名）だが、**1 つの repo に
  複数の gate を載せたいときは :id を明示する**。:name は org / tip 解決に使う
  repo 名のままにしておく必要があるので、識別子を分ける。

  これが無いと 2 つ目の entry が (a) state の [:repos <name>] を共有して
  tip 変化検出を奪い合い (b) gate-name が `test-<name>-<sha7>-murakumo-<host>`
  で衝突して checks map で潰し合う。superproject は不変条件が増えていく対象
  なので、1 gate/repo の制約はここで外しておく。"
  [r] (or (:id r) (:name r)))

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
  "-> nil（続行可）| 理由文字列（この tick は skip すべき）

  auth 判定は **landing 先の private repo を git で読めるか**で行う。以前は
  `gh api repos/<landing>` だったが、gh は launchd 配下で Keychain の token を
  読めず匿名に落ち、private repo は 404 になる — つまり毎 tick skip していた
  （実測 2026-07-26 11:00Z）。git+SSH は ~/.ssh の鍵を直接使うので agent も
  Keychain も要らず、この context でも通る。"
  [cfg]
  (let [landing-repo (get-in cfg [:landing :repo])
        {:keys [exit out]} (git nil ["ls-remote" (ssh-url landing-repo) "HEAD"] {:timeout 120000})]
    (cond
      (not (zero? exit))
      (str "cannot reach " landing-repo " over git+SSH — check that ~/.ssh has a key "
           "authorised for GitHub (no token is used by design). detail: "
           (first (str/split-lines (str/trim out))))

      (and (signer-pem) (not (fs/existsSync (signer-pem))))
      (str "--signer-pem points at a missing file: " (signer-pem))

      (and under-launchd? (not (signer-pem)))
      (str "running under launchd (" (.-XPC_SERVICE_NAME js/process.env) ") without "
           "--signer-pem: kagi cannot show the Keychain unlock prompt there and blocks "
           "until timeout, so no receipt could be signed. Provide FLEET_CI_SIGNER_PEM "
           "(mode 600, the ADR-2607178000 pattern) or run the tick from an interactive session.")

      :else
      (do (log "preflight: git+SSH can reach" landing-repo
               (if (signer-pem) "· signer=pem" "· signer=kagi"))
          nil))))

;; ---------------------------------------------------------------------------
;; kagami（fleet CLI）— **tip の kagami を使う**。ローカル checkout は west pin に
;; 縛られていて --subject-extra を持たない可能性がある（自己ホスト: CI ツール自身も
;; 常に最新 main で動かす）。sha ごとに ~/.gftd/fleet-ci-cache に展開して再利用。

(def max-ship-mb 200)

(defn- ensure-sha! [dir org-repo sha]
  "mirror に sha が居ることを保証する（default branch の tip 以外を要求されても
  取れるように、名前付き ref も一緒に取る）。"
  (when-not (zero? (:exit (git dir ["cat-file" "-e" (str sha "^{commit}")])))
    (git dir ["fetch" "--quiet" "origin" "+refs/heads/*:refs/heads/*"] {:timeout 1800000}))
  ;; PR head は refs/heads の外（refs/pull/N/head）に居るので、branch の fetch
  ;; では届かない。まず **sha を直に要求する** — GitHub は ref から到達可能な
  ;; sha の直指定 fetch を許可する（実測 2026-07-29、kotoba-lang/nekko の PR
  ;; head で確認）。mirror は既に main の履歴を持っているので増分で済む。
  (when-not (zero? (:exit (git dir ["cat-file" "-e" (str sha "^{commit}")])))
    (git dir ["fetch" "--quiet" "origin" sha] {:timeout 1800000}))
  ;; それでも駄目なときだけ pull refs を総取りする。これは重い経路で、root は
  ;; refs/pull/*/head を 1,411 本持つ（うち open は 1 本）。常用しない。
  (when-not (zero? (:exit (git dir ["cat-file" "-e" (str sha "^{commit}")])))
    (log "ensure-sha: falling back to a full refs/pull fetch for" org-repo (sha7 sha))
    (git dir ["fetch" "--quiet" "origin" "+refs/pull/*/head:refs/pull/*/head"]
         {:timeout 1800000}))
  (when-not (zero? (:exit (git dir ["cat-file" "-e" (str sha "^{commit}")])))
    (die (str sha " is not reachable in " org-repo " after fetch")))
  dir)

(defn open-prs
  "org/repo の **open な** PR を [{:number :head}] で返す。API も token も使わない。

  GitHub は PR ごとに refs/pull/N/head を作り、**close すると消えない**ので
  ls-remote の head だけでは open を判別できない（実測 2026-07-29:
  com-junkawasaki/root は head 1,411 件に対し open PR 0 件）。一方
  **refs/pull/N/merge は open かつ mergeable な PR にだけ存在し、close で
  消える** — これが token 無しで得られる open シグナル。実測で
  merge ref 数と `gh pr list --state open` の件数が 3 repo とも一致した。

  merge ref が無い open PR（conflict 状態）は取りこぼす。API を使わない代償
  として明示しておく — 黙って「PR は無い」と報告するより、取りこぼす条件を
  書いておく方がよい。"
  [org-repo]
  (let [{:keys [exit out]} (git nil ["ls-remote" (ssh-url org-repo)
                                     "refs/pull/*/merge" "refs/pull/*/head"]
                                {:timeout 180000})]
    (if-not (zero? exit)
      []
      (let [rows (for [l (str/split-lines (str/trim out))
                       :let [[sha ref] (str/split (str/trim l) #"\s+")]
                       :when (and sha ref)]
                   [ref sha])
            merge-nums (set (keep (fn [[ref _]]
                                    (second (re-find #"^refs/pull/(\d+)/merge$" ref)))
                                  rows))
            heads (into {} (keep (fn [[ref sha]]
                                   (when-let [n (second (re-find #"^refs/pull/(\d+)/head$" ref))]
                                     [n sha]))
                                 rows))]
        (vec (sort-by :number
                      (for [n merge-nums :when (get heads n)]
                        {:number (js/parseInt n 10) :head (get heads n)})))))))

(defn ensure-tree!
  "org/repo の sha の tree を展開して dir を返す（キャッシュ済みなら再展開しない）。
  API tarball ではなく mirror からの `git archive`。"
  [org-repo sha]
  (let [dir (path/join cache-dir (str (str/replace org-repo "/" "-") "-" (sha12 sha)))]
    (if (fs/existsSync dir)
      dir
      (let [m (ensure-sha! (mirror! org-repo) org-repo sha)
            tgz (str dir ".tar.gz")]
        (fs/mkdirSync dir #js {:recursive true})
        (let [{:keys [exit out]} (git m ["archive" "--format=tar.gz" "-o" tgz sha]
                                      {:timeout 1800000})]
          (when-not (zero? exit) (die (str "git archive failed for " org-repo "@" sha ": " (str/trim out)))))
        (let [{:keys [exit out]} (sh "tar" ["xzf" tgz "-C" dir])]
          (fs/unlinkSync tgz)
          (when-not (zero? exit) (die (str "tar extract failed: " out))))
        dir))))

(defn filtered-tarball!
  "検査対象だけを詰めた tarball。

  **`git archive` に pathspec を渡す**（tree を展開してから find で絞らない）。
  最初の git 版は ensure-tree! で全展開してから `find | tar` していて、
  superproject では tracked 230,000 ファイルを 2 回歩くことになり、実測で
  5 分の timeout を超えて `spawnSync bash ETIMEDOUT` で落ちた（2026-07-26
  12:11Z の launchd tick）。pathspec なら該当ファイルだけが 1 パスで出る。

  以前は 2 経路あった（trees+blobs API で blob ごとに落とす経路と、全体
  tarball を取って絞る経路）。git 化でどちらも同じ形になったので :include-from
  は無くなり、:include-ext があればこれ、無ければ全体、の 2 択。"
  [org-repo sha {:keys [include-ext min-files]}]
  ;; **キャッシュキーに include-ext を含める。** 含めないと「1 repo = 1 filter」を
  ;; 暗黙に仮定することになり、同じ repo・同じ sha に別 filter の gate を足した
  ;; 瞬間に、先に走った方の tarball を後の方が黙って再利用する。実際に起きた
  ;; （2026-07-29）: root の docs-edn gate（[".edn"]）が先に tarball を作り、
  ;; repository-roles gate（[".edn" ".cljs"]）がそれを掴んで verifier 本体を
  ;; 見つけられず exit 90。gate 側の床が拾ったので false-pass にはならなかったが、
  ;; 床が無ければ「.cljs が無い tree で検査して合格」になっていた。
  (let [slug (if (seq include-ext)
               (str "-" (str/join "" (map #(str/replace % #"[^a-zA-Z0-9]" "") include-ext)))
               "")
        f (path/join cache-dir (str (str/replace org-repo "/" "-") "-" (sha12 sha)
                                    "-filtered" slug ".tar.gz"))]
    (if (fs/existsSync f)
      f
      (let [m (ensure-sha! (mirror! org-repo) org-repo sha)
            ;; ワイルドカード pathspec は使わない。実測: `-- '*.edn'` は 0 件、
            ;; `:(glob)**/*.edn` は 1 件しか拾わなかった（git の pathspec は
            ;; fnmatch のアンカー規則が直感と違う）。名前一覧を出して手元で
            ;; 絞り、**リテラルな path を archive に渡す**方が曖昧さがない。
            all (str/split-lines
                 (str (:out (git m ["ls-tree" "-r" "--name-only" sha]
                                 {:timeout 600000 :maxBuffer 536870912}))))
            files (filterv (fn [p] (and (seq p) (some #(str/ends-with? p %) include-ext))) all)
            n (count files)]
        (log "pathspec-filter" org-repo (sha12 sha) ":" n "files" (pr-str include-ext))
        (when (and min-files (< n min-files))
          (reject-input! (str "filter found only " n " files for " org-repo
                              " (expected >= " min-files ") — refusing to build a gate input that "
                              "would trivially pass")))
        ;; argv 長の上限が現実的な天井（実測 1,889 path で ~95KB、ARG_MAX の 1/10）。
        ;; 桁が変わる repo が出たら分割ではなく設計を見直す方が良いので、黙って
        ;; 壊れるのでなく先に落とす。
        (when (> n 20000)
          (reject-input! (str n " matching files for " org-repo " — too many to pass as argv; "
                              "narrow :include-ext or give the gate a subdirectory")))
        (fs/mkdirSync cache-dir #js {:recursive true})
        (let [{:keys [exit out]}
              (git m (into ["archive" "--format=tar.gz" "-o" f "--prefix=repo/" sha "--"] files)
                   {:timeout 1800000})]
          (when-not (zero? exit)
            (reject-input! (str "git archive (filtered) failed: " (str/trim out)))))
        f))))

(defn full-tarball!
  "repo 全体の tarball（mirror からの git archive）。"
  [org-repo sha]
  (let [f (path/join cache-dir (str (str/replace org-repo "/" "-") "-" (sha12 sha) ".tar.gz"))]
    (when-not (fs/existsSync f)
      (fs/mkdirSync cache-dir #js {:recursive true})
      (let [m (ensure-sha! (mirror! org-repo) org-repo sha)
            {:keys [exit out]} (git m ["archive" "--format=tar.gz" "-o" f "--prefix=repo/" sha]
                                    {:timeout 1800000})]
        (when-not (zero? exit) (die (str "git archive failed for " org-repo "@" sha ": " (str/trim out))))))
    f))

(defn gate-input!
  "gate に渡す tarball。:include-ext があれば絞り込み版、無ければ全体。
  ノードへ送るサイズに上限を掛ける。"
  [{:keys [org-repo tip include-ext min-files name]}]
  (let [f (if (seq include-ext)
            (filtered-tarball! org-repo tip {:include-ext include-ext :min-files min-files})
            (full-tarball! org-repo tip))
        mb (/ (.-size (fs/statSync f)) 1048576)]
    (when (> mb max-ship-mb)
      (reject-input! (str name " gate input is " (js/Math.round mb) "MB (> " max-ship-mb
                          "MB) — add :include-ext to gates.edn so only the inspected paths are shipped")))
    f))

;; ---------------------------------------------------------------------------
;; gate script 生成（ノード側で bash -s に stdin で流す。committed な .sh は作らない）

(def remote-base "/tmp/fleet-ci")

;; nm は work item の **:id**（repo 名ではない）。1 repo に複数 gate があると、
;; repo 名で割ると両者が同じ展開ディレクトリと同じ /gate-<nm>.cljs を共有し、
;; 後から書いた方のスクリプト本体を先の方が自分の引数で実行する。実測
;; (2026-07-29): root の 2 gate が衝突し、root-repository-roles が
;; docs-edn-check.cljs を --sub 無しで走らせて 90-docs/gates/ の壊れた EDN を
;; 報告した（自分の gate が一度も動いていないのに「落ちた」ように見えていた）。
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
  [{:keys [name gate classpath entry script script-args] :as w} node sha script-body]
  ;; `:alias` は data のキーなので destructure で clojure.core/alias を隠さない。
  (let [alias-name (or (:alias w) "test")
        d (remote-dir name sha)
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
         ;;
         ;; `:alias` は既定 "test"。**別 alias を許す理由**: fleet は 1 repo の
         ;; tree しか配らないので、`:test` が sibling の `:local/root` を掴んで
         ;; いる repo は :jvm-test の対象にできない。cloud-itonami の `:test` は
         ;; `../../kotoba-lang/kototama` 等を参照しており、ノードには存在しない。
         ;; git だけで解決する alias を repo 側に用意すれば gate にできる。
         "test -f deps.edn || fail 'deps.edn missing after extract' 90"
         (str "grep -q ':" alias-name "' deps.edn"
              " || fail 'no :" alias-name " alias in deps.edn' 91")
         (str "export JAVA_HOME=" (or (:java-home node) "/opt/homebrew/opt/openjdk"))
         "export PATH=$JAVA_HOME/bin:$PATH"
         "java -version 2>&1 | head -1"
         (str "for i in $(seq 1 900); do mkdir " dep-lock " 2>/dev/null && break;"
              " [ -n \"$(find " dep-lock " -maxdepth 0 -mmin +20 2>/dev/null)\" ]"
              " && rmdir " dep-lock " 2>/dev/null; sleep 1; done")
         (str "clojure -P -M:" alias-name " >/dev/null 2>&1")
         (str "rmdir " dep-lock " 2>/dev/null || true")
         ;; **テストが実際に走ったことも assert する**: summary 行が無い / 0 件は fail。
         (str "out=$(clojure -M:" alias-name " 2>&1); code=$?")
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

(def ^:private git-dep-re
  #"io\.github\.([a-zA-Z0-9_.-]+)/([a-zA-Z0-9_.-]+)\s*\{[^}]*?:git/sha\s+\"([0-9a-f]{40})\"")

(defn git-deps-of
  "deps.edn の本文 → [{:lib \"io.github.org/name\" :org :repo :sha} …]。
  :override-deps（:local 用）は :git/sha を持たないので自然に外れる。"
  [deps-text]
  (mapv (fn [[_ org repo sha]]
          {:lib (str "io.github." org "/" repo) :org org :repo repo :sha sha})
        (re-seq git-dep-re (str deps-text))))

(defn ship-git-deps!
  "gate repo の git 依存を **ノードの ~/.gitlibs に直接置く**。

  murakumo のノードは tailnet だけに繋がっていて、外向きの HTTPS が無い
  （実測 2026-07-26、zebulun: github.com:443 も 1.1.1.1:443 も届かない。DNS は
  引ける）。したがって `clojure -M:test` は deps.edn の :git/sha を自力で
  取得できず、**たまたま ~/.gitlibs にキャッシュされている sha でしか動かない**。
  bonsai の pin を正しく前進させた途端 gate が落ちたのはこれで、ノードには古い
  1c2a677 しか無かった（`Error building classpath. Unable to fetch …
  Failed to connect to github.com port 443`）。依存を更新すると CI が壊れる、
  という形の壊れ方なので、キャッシュ待ちにはできない。

  operator（ここ）は GitHub に届くので、mirror から `git archive` して
  `~/.gitlibs/libs/<lib>/<sha>/` に展開する。ノードは何も取りに行かない —
  ソース tarball を ssh stdin で渡すのと同じ経路で、token も置かない。

  推移的な依存も辿る（依存先の deps.edn を mirror から読んで再帰）。"
  [host deps-text]
  (loop [queue (git-deps-of deps-text) seen #{} shipped 0]
    (if (empty? queue)
      shipped
      (let [{:keys [lib org repo sha] :as d} (first queue)
            k [lib sha]]
        (if (contains? seen k)
          (recur (rest queue) seen shipped)
          ;; $HOME は **リモートで**展開させる。ローカルの bash -c の二重引用符の
          ;; 中に素で置くと手元で展開され、リモートに他人の HOME パスが渡る
          ;; （実測: asher に `mkdir: /Users/junkawasaki: Permission denied`）。
          (let [dest (str "\\$HOME/.gitlibs/libs/" lib "/" sha)
                ;; **exit code は見ない**。Tailscale SSH + /usr/bin/login では
                ;; リモートの終了ステータスが伝播せず必ず 0 になる（gate-command
                ;; が sentinel を grep しているのと同じ理由）。最初の版はこれを
                ;; 忘れて `test -d` を exit で判定したので「常に present」と読み、
                ;; 依存を 1 つも送らないまま成功したように見えていた。
                present? (str/includes?
                          (str (:out (sh "ssh" ["-o" "BatchMode=yes" "-o" "ConnectTimeout=20" host
                                                (str "test -d " dest " && echo FLEET-CI-DEP-PRESENT")])))
                          "FLEET-CI-DEP-PRESENT")
                m (try (ensure-sha! (mirror! (str org "/" repo)) (str org "/" repo) sha)
                       (catch :default e
                         (log "WARN dep mirror failed for" lib (sha7 sha) "—" (ex-message e))
                         nil))
                next-deps (when m (git-deps-of (or (git-show m sha "deps.edn") "")))]
            (when (and m (not present?))
              (let [tgz (path/join cache-dir (str (str/replace lib "/" "-") "-" (sha12 sha) "-dep.tar.gz"))]
                (when-not (fs/existsSync tgz)
                  (let [{:keys [exit out]} (git m ["archive" "--format=tar.gz" "-o" tgz sha]
                                                {:timeout 900000})]
                    (when-not (zero? exit) (die (str "git archive failed for dep " lib ": " (str/trim out))))))
                (let [{:keys [out]}
                      (sh "bash" ["-c" (str "cat " tgz " | ssh -o BatchMode=yes -o ConnectTimeout=20 "
                                            host " \"mkdir -p " dest " && tar xz -C " dest
                                            " && test -f " dest "/deps.edn && echo FLEET-CI-DEP-OK\"")]
                          {:timeout 600000})]
                  ;; 同上 — 成功判定も出力の sentinel で行う
                  (if (str/includes? (str out) "FLEET-CI-DEP-OK")
                    (log "dep shipped" lib (sha7 sha) "->" host)
                    (log "WARN dep ship failed" lib (sha7 sha) "—" (str/trim (str out)))))))
            (recur (concat (rest queue) next-deps) (conj seen k)
                   (if (and m (not present?)) (inc shipped) shipped))))))))

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
         ;; 保持は **分** で切る。`-mtime +1`（1 日）だと回収が生産に追いつかない:
         ;; 実測 2026-07-30、asher の /tmp/fleet-ci は 474 dir / 20GB まで育ち
         ;; ディスクを 100% 使い切っていたが、**1 日より古い dir は 0 件**だった
         ;; ため prune は何も回収できなかった（gate は展開のたびに ~40MB 置き、
         ;; 1 日あたり数百回走る）。ディスクが満杯のノードでは tar が
         ;; "No space left on device" で落ち、gate は走る前に死ぬ。
         ;; gate timeout は 30 分なので 120 分は 4 倍の余裕があり、走っている
         ;; gate の dir を消す危険は無い。保持量は 24 時間分から 2 時間分に下がる。
         " \"find " remote-base " -maxdepth 1 -mindepth 1 -type d -mmin +120 -exec rm -rf {} + 2>/dev/null;"
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
;;
;; **配置はここに無い。** ADR-2608111721 決定 1（オーナー判断 2026-08-11）で
;; placement authority は `murakumo.task.plan`（`:task-plan` KIR に載った Kotoba の
;; 決定核）に一本化した。この ns が持っていた LPT / slots / node-speed / EMA cost は
;; `scripts/fleet-ci/placement.cljs` に移り、`placement/assign` が murakumo へ委譲する。
;;
;; EMA cost は捨てていない —— **placement の決定器から入力の順序付け器へ降りた**。
;; murakumo の `assign` は与えられた順に greedy least-filled で置くので、cost 降順で
;; 渡せば LPT と同じ順序付けになる（実測 `placement-parity.cljs`: 101 gate × 8 node で
;; placed / cap 制約 / unschedulable が一致、makespan は 509s → 389s）。
;;
;; 交換に得たのが murakumo の `admit`（満杯・張り付き・到達不能なノードを
;; :saturated / :unreachable として**理由付きで報告する**）と `why-unschedulable`。

(defn live-load
  "対象ノードの 1 分 load average を実測。取れなければ nil（= 未知として扱い、
  静的な cores/free-gb だけで見積もる）。ssh 1 回ぶんなので tick あたり数回。"
  [host]
  (try
    (let [out (str (:out (sh "ssh" ["-o" "BatchMode=yes" "-o" "ConnectTimeout=6" host
                                    "sysctl -n vm.loadavg"] {:timeout 15000})))
          m (re-find #"\{?\s*([0-9.]+)" out)]
      (when m (js/parseFloat (nth m 1))))
    (catch :default _ nil)))

(defn assign
  "配置を murakumo に解かせる。ここは機構だけ（root の解決と子プロセス起動）。"
  [work nodes]
  (pl/assign work nodes {:root root
                         :spawn-fn (fn [cmd args opts] (sh cmd args opts))
                         :log-fn log}))

;; ---------------------------------------------------------------------------
;; landing（append-only receipt log / west.yml pin）— branch + PUT + server merge

(defn put-file!
  "landing repo の path を content に置き換えて push する。

  以前は contents API の PUT（blob sha による楽観ロック）+ server-side merge
  だったが、それは token を要求する。git では ref 更新そのものが楽観ロックで、
  non-fast-forward push が拒否されるのが 409 に相当する — 呼び側は「読み直して
  作り直して再試行」すればよく、意味論は変わらない。

  作業は使い捨ての worktree で行う（superproject の working tree には触らない）。"
  [{:keys [repo branch path content message]}]
  (let [d (mirror! repo)
        wt (fs/mkdtempSync (path/join (os/tmpdir) "fleet-ci-put-"))]
    (try
      (let [fetch (git d ["fetch" "--quiet" "origin"
                          (str "+refs/heads/" branch ":refs/remotes/origin/" branch)])]
        (if-not (zero? (:exit fetch))
          {:ok false :detail (str "fetch failed: " (str/trim (:out fetch)))}
          (let [add (git d ["worktree" "add" "--detach" "--quiet" wt (str "origin/" branch)])]
            (if-not (zero? (:exit add))
              {:ok false :detail (str "worktree add failed: " (str/trim (:out add)))}
              (do
                (fs/mkdirSync (path/join wt (path/dirname path)) #js {:recursive true})
                (fs/writeFileSync (path/join wt path) content)
                ;; landing repo が sparse worktree でも、ledger 1 file だけは明示的に
                ;; index へ載せる。通常の `git add` は skip-worktree 対象を拒否する。
                (let [a (git wt ["add" "--sparse" "--" path])]
                  (if-not (zero? (:exit a))
                    {:ok false :detail (str "add failed: " (str/trim (:out a)))}
                    (let [c (git wt ["-c" "user.name=fleet-ci" "-c" "user.email=fleet-ci@murakumo"
                                     "commit" "-q" "-m" message])]
                      (if-not (zero? (:exit c))
                        {:ok false :detail (str "commit failed: " (str/trim (:out c)))}
                        (let [pu (git wt ["push" "--quiet" "origin" (str "HEAD:" branch)])]
                          (if (zero? (:exit pu))
                            {:ok true :detail (str "pushed to " branch)}
                            {:ok false :detail (str "push rejected (someone else moved "
                                                    branch " — retry): "
                                                    (str/trim (:out pu)))})))))))))))
      (finally
        (git d ["worktree" "remove" "--force" wt])
        (sh "rm" ["-rf" wt])))))

(defn append-receipt!
  "receipt 1 行を manifest/fleet-ci.edn に追記（append-only）。409 は再取得して再試行。
  path は landing の :receipts（:path ではない — landing map の key 名に合わせる）。"
  [{:keys [repo branch receipts]} line]
  (loop [attempt 1]
    (let [path receipts
          cur (gh-raw repo branch path)
          content (str (if (str/ends-with? cur "\n") cur (str cur "\n")) line "\n")
          r (put-file! {:repo repo :branch branch :path path :content content
                        :message "fleet-ci: tip-driven murakumo tick receipt"})]
      (cond
        (:ok r) {:ok true}
        (< attempt 3) (do (log "WARN receipt landing retry" attempt (:detail r)) (recur (inc attempt)))
        :else {:ok false :detail (:detail r)}))))

;; ---------------------------------------------------------------------------
;; commit status 書き戻し（Checks API は GitHub App 必須。statuses は repo scope で足りる）


;; ---------------------------------------------------------------------------
;; Radicle 反映（失敗時だけ issue）
;;
;; Radicle には GitHub の commit status に当たる COB が無い（rad 1.9.1 の
;; サブコマンドは issue / patch / inbox …で、job も ci も status も無い）。
;; 書ける面は issue か patch のコメント/レビューだが、patch は tip 駆動の
;; この CI には存在しない — 検証しているのは既に default branch に載った
;; commit なので。したがって **失敗したときだけ issue を開く**。
;;
;; green を毎回書かないのは意図的で、Radicle 側に「常に最新の緑」を置く面が
;; 無い以上、pass ごとに issue を作れば台帳がノイズで埋まるだけになる。緑の
;; 正本は署名付き receipt（manifest/fleet-ci.edn）のままで、issue は
;; 「人間が気付く必要がある事象」だけを載せる。
;;
;; 実行は seed node（既定 gad）へ ssh して行う。この laptop の node は鍵の
;; passphrase が要り、その item は kagi に存在しなかった（ADR-2607252200 は
;; 置く前提で書かれているが実測 `no such item`）。一方 gad の node は既に
;; systemd で常駐していて 16 repo を seed 済みなので、announce できる identity
;; はそちらにある。

(defn rad-rid-map
  "manifest/repos.edn の :manifest.repos/rad-rids（pr-str された map の string
  blob）→ {\"orgs/<org>/<repo>\" \"rad:z…\"}。"
  [landing]
  (try
    (let [edn (reader/read-string (gh-raw (:repo landing) (:branch landing) "manifest/repos.edn"))
          blob (:manifest.repos/rad-rids (first edn))]
      (if (string? blob) (reader/read-string blob) (or blob {})))
    (catch :default e
      (log "WARN rad: could not read rad-rids —" (ex-message e))
      {})))

(def ^:private rad-passphrase
  "COB は署名するので、node が動いていても鍵が unlock できないと書けない。
  passphrase は kagi（compartment personal、item は :rad :passphrase-item）
  から取る — ADR-2607252200 がそう決めている置き場所で、2026-07-26 に実際に
  置いた（それまで `no such item` で、ADR は実行されなかった意図を書いていた）。

  memo 化するのは 1 tick に複数回 rad を叩くため（issue list → issue open）。"
  (memoize
   (fn [item]
     (let [{:keys [exit out]}
           (sh (path/join root "orgs" "kotoba-lang" "kagi" "bin" "kagi")
               ["get" item "--compartment" "personal"]
               {:env (js/Object.assign #js {} js/process.env #js {:FLEET_ROOT root})})]
       (when (zero? exit)
         (let [v (str/trim (str out))]
           (when-not (str/starts-with? v "no such item") v)))))))

(defn- rad-sh
  "seed node 上で rad を1回実行する。script は heredoc（= stdin）で渡すので、
  タイトルや本文に引用符が入っても壊れない。

  passphrase も **同じ stdin 経由**で渡し、ssh の argv には置かない — argv は
  リモートの `ps` に出る。ローカルの argv にも置かない（同じ理由）。"
  [{:keys [host bin run-as passphrase-item]} script]
  (let [pass (rad-passphrase (or passphrase-item "radicle-seed-gad-passphrase"))
        wrapped (str "sudo -u " (or run-as "gad")
                     " env RAD_PASSPHRASE=\"$RAD_PASSPHRASE\" RAD_HOME=/home/" (or run-as "gad")
                     "/.radicle " (or bin "rad") " \"$@\"")]
    (sh "bash" ["-c" (str "ssh -o BatchMode=yes -o ConnectTimeout=20 " host
                          " bash -s <<'FLEET_CI_RAD_EOF'\n"
                          (if pass (str "RAD_PASSPHRASE=" (pr-str pass) "\n") "")
                          "rad() { " wrapped "; }\n"
                          script
                          "\nFLEET_CI_RAD_EOF")]
        {:timeout 120000})))

(defn rad-issue-for-failure!
  "失敗 1 件につき issue 1 件。同じ sha について既に開いていれば作らない
  （tick は 5 分ごとに回るので、これが無いと同じ失敗で issue が増え続ける）。"
  [rad-cfg rid {:keys [name tip gate-name node detail cid]}]
  (let [sha (sha7 tip)
        marker (str "fleet-ci:" name "@" sha)
        title (str "fleet-ci: " name " gate failed at " sha)
        body (str "murakumo fleet-ci gate failed.\n\n"
                  "- repo: " name "\n- commit: " tip "\n- gate: " gate-name
                  "\n- node: " (get node :host "?") "\n- receipt: " (sha12 cid)
                  "\n- marker: " marker
                  "\n\n" (str/trim (str detail)) "\n")
        existing (rad-sh rad-cfg (str "rad issue list --repo " rid " 2>/dev/null | grep -c '" sha "' || true"))]
    (if (pos? (js/parseInt (str/trim (or (:out existing) "0")) 10))
      (do (log "rad: issue already open for" name sha "— skipped") :skipped)
      (let [{:keys [exit out]}
            ;; title/description は argv に直接置かず、リモートで quoted heredoc
            ;; に書き出してから $(cat) で読む。gate の detail は任意のテキストで、
            ;; 最初の版は本文の ``` が bash のバッククォート（コマンド置換）に
            ;; 食われて `nexit: command not found` を撒いた。--labels も外した:
            ;; label の適用は repo の delegate 権限を要求し、seed node の DID は
            ;; 持っていない（実測 `not authorized to apply Label`）。issue 本体は
            ;; 開けるので、分類は本文の marker 行で足りる。
            (rad-sh rad-cfg
                    (str "cat > /tmp/fleet-ci-issue-title <<'FLEET_CI_TITLE_EOF'\n"
                         title
                         "\nFLEET_CI_TITLE_EOF\n"
                         "cat > /tmp/fleet-ci-issue-body <<'FLEET_CI_BODY_EOF'\n"
                         body
                         "\nFLEET_CI_BODY_EOF\n"
                         "rad issue open --repo " rid
                         " --title \"$(cat /tmp/fleet-ci-issue-title)\""
                         " --description \"$(cat /tmp/fleet-ci-issue-body)\""
                         " --quiet 2>&1; rc=$?;"
                         " rm -f /tmp/fleet-ci-issue-title /tmp/fleet-ci-issue-body; exit $rc"))]
        (cond
          (zero? exit)
          (do (log "rad: issue opened for" name sha "in" rid) :opened)

          ;; COB は署名するので、node が動いているだけでは足りない。鍵が
          ;; unlock できないと必ずここに来る。gad には ssh-agent が無いので
          ;; passphrase 経路が唯一の unlock 手段 — kagi に item が無い/読めない
          ;; ときにここへ落ちる。原因を毎回 1 行で名指しする。
          (re-find #"(?i)ssh-agent|SSH_AUTH_SOCK|passphrase" (str out))
          (do (log "WARN rad: cannot sign COBs on" (:host rad-cfg)
                   "— the signing key is locked. kagi item"
                   (pr-str (or (:passphrase-item rad-cfg) "radicle-seed-gad-passphrase"))
                   "(compartment personal) must hold the node passphrase; see ADR-2607252200."
                   "Detail:" (str/trim (str out)))
              :locked)

          :else
          (do (log "WARN rad: issue open failed for" name sha "—" (str/trim (str out))) :failed))))))

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
              (let [r (put-file! {:repo repo :branch branch :path west :content cand
                                  :message (str "west: advance " nm " pin to " (sha12 new-sha)
                                                " (fleet-ci green on murakumo)")})]
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
                only (filter #(or (contains? only (:name %))
                                  (contains? only (gate-id %)))))
        ;; tip は repo ごとに **1 tick 1 回**だけ解決する。entry ごとに引くと、
        ;; 同じ repo の 2 gate が別 sha を検証しうる（実測 2026-07-29: root の
        ;; 2 gate が 49884cd と d802afb に分かれた — main が tick 中に動いた）。
        ;; tick は一貫したスナップショットであるべきで、ls-remote も減る。
        tip-of (let [m (into {} (for [org-repo (distinct
                                                (keep (fn [r]
                                                        (when-let [o (or (:org r) (org-of west (:name r)))]
                                                          (str o "/" (:name r))))
                                                      repos))]
                                  [org-repo (gh-tip org-repo)]))]
                 (fn [org-repo] (get m org-repo)))
        ;; 各 repo の tip（fresh）と west pin
        work (vec (for [r repos
                        :let [nm (:name r)
                              ;; :id は「1 repo に複数 gate」を許すための識別子で、
                              ;; :name は repo 名のまま（org / tip 解決に使う）。
                              id (gate-id r)
                              ;; org は west.yml の remote から引くのが既定（drift 防止）。
                              ;; superproject 自身は west project ではないので引けない —
                              ;; そういう対象だけ gates.edn に :org を明示する。
                              org (or (:org r) (org-of west nm))
                              org-repo (str org "/" nm)
                              tip (tip-of org-repo)
                              pin (get-in west [:projects nm :revision])
                              last-sha (get-in @state [:repos id :sha])]]
                    (assoc r :org org :org-repo org-repo :tip tip :pin pin
                           :id id
                           :last-sha last-sha
                           :changed? (and tip (not= tip last-sha)))))
        ;; ---- PR head の検証。tip だけを見ていると「壊れたものが main に入った
        ;; 後で赤を教える」CI にしかならず、merge を止められない。PR head も
        ;; 同じ gate に通す（ADR-2607255500 :not-done の :pr-head-verification）。
        ;;
        ;; :id を "<id>#pr<N>" にすることで state・gate-name・tarball 名がすべて
        ;; tip 側と自然に分かれる（:id 導入時に得た性質をそのまま使う）。
        ;; :cd false — PR head で west pin を前進させることは絶対にない。
        pr-cap (js/parseInt (str (or (:pr-cap opts) (:pr-cap cfg) 10)) 10)
        ;; repo ごとに 1 回だけ ls-remote する（同じ repo に複数 gate があっても
        ;; PR 列挙は共有する）。
        prs-by-repo (when-not (:no-pr opts)
                      (into {} (for [org-repo (distinct
                                               (keep (fn [r]
                                                       (when-let [o (or (:org r) (org-of west (:name r)))]
                                                         (str o "/" (:name r))))
                                                     repos))]
                                 [org-repo (open-prs org-repo)])))
        _ (doseq [[org-repo prs] prs-by-repo
                  :when (> (count prs) pr-cap)]
            ;; 黙って切り捨てない（切り捨てを報告しないと「全部見た」と読める）。
            (log "PR cap:" org-repo "has" (count prs) "open PRs — verifying the"
                 pr-cap "lowest-numbered;" (pr-str (mapv :number (drop pr-cap prs)))
                 "NOT verified"))
        pr-work
        (vec (for [r repos
                   :let [org (or (:org r) (org-of west (:name r)))
                         org-repo (when org (str org "/" (:name r)))]
                   :when org-repo
                   {:keys [number head]} (take pr-cap (get prs-by-repo org-repo))
                   :let [id (str (gate-id r) "#pr" number)
                         last-sha (get-in @state [:repos id :sha])]]
               (assoc r :org org :org-repo org-repo :tip head :pin nil
                      :id id :pr number :cd false
                      :last-sha last-sha
                      :changed? (not= head last-sha))))
        work (into work pr-work)
        missing (filter #(nil? (:tip %)) work)
        todo (cond
               (:all opts) (remove #(nil? (:tip %)) work)
               only (remove #(nil? (:tip %)) work)
               :else (filter :changed? work))]
    (doseq [m missing] (log "WARN no tip resolved (skipped):" (:name m) (:org-repo m)))
    (log "tick:" (count repos) "covered,"
         (count (remove :pr work)) "tip +" (count (filter :pr work)) "PR head,"
         (count todo) "to verify (" (count (filter :pr todo)) "of them PR);"
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
            ;; CLI の入口名は repo の改名で動いた。**両方を試す。**
            ;;
            ;; ⚠ 実測 2026-08-10: ここが `bin/fleet.cljs` 決め打ちで、**fleet CI 全体が
            ;; 2026-08-07 から 3 日間まったく動いていなかった**。kagami は同日
            ;; `refactor: this repo is kagami, so its namespaces are kagami.*`（bc7cdce）で
            ;; `bin/fleet.cljs` を `bin/kagami.cljs` に改名しており、tick は毎回
            ;; `FATAL kagami tree missing bin/fleet.cljs` で **gate を 1 本も実行せずに
            ;; 死んでいた**。`manifest/fleet-ci.edn` の最終 receipt が
            ;; 2026-08-07T03:47Z で止まっているのがその証拠。
            ;;
            ;; GitHub Actions を撤去した（ADR-2607300900）後の唯一の CI がこれなので、
            ;; **この 3 日間このワークスペースには動く CI が 1 つも無かった**。しかも
            ;; 誰にも報告されなかった —— ADR-2607300900 自身が「動いていない CI は
            ;; green に見えたまま何も検査しない」と書いた失敗を、移行先の側で繰り返した。
            ;;
            ;; 名前を 1 つに決め打ちしない。tip の kagami を使う設計なので、**上流の
            ;; 改名は「起こりうること」であって例外ではない。**
            fleet-bin (or (->> ["kagami.cljs" "fleet.cljs"]
                               (map #(path/join kagami-dir "bin" %))
                               (filter #(fs/existsSync %))
                               first)
                          (die (str "kagami tree has neither bin/kagami.cljs nor bin/fleet.cljs"
                                    " (looked in " kagami-dir "/bin) — the CLI entrypoint was"
                                    " renamed again; add the new name here")))
            tmp (fs/mkdtempSync (path/join (os/tmpdir) "fleet-ci-tick-"))
            db-file (path/join tmp "fleet-db.edn")
            _ (fs/writeFileSync db-file (gh-raw (:repo landing) (:branch landing) (:db landing)))
            results (atom [])
            landing-failed? (atom false)]
        (log "fleet CLI: kagami@" (sha12 kagami-sha) (path/basename fleet-bin))
        (doseq [[bi batch] (map-indexed vector batches)]
          (let [unassigned (filter :unassigned batch)
                batch (remove :unassigned batch)]
            (doseq [u unassigned]
              (log "WARN no capable node for" (:name u) "(gate" (:gate u) ") — skipped"))
            (when (seq batch)
              ;; gate ごとに tarball + script を用意
              (let [prepared-or-rejected
                    (vec (for [w batch]
                           (try
                           (let [tgz (gate-input! w)
                                 ;; JVM gate はノード上で deps.edn を解決する。
                                 ;; ノードに外向き HTTPS が無いので、git 依存は
                                 ;; operator から ~/.gitlibs へ先に置いておく。
                                 _ (when (or (= :jvm-test (:gate w))
                                              ;; :nbb-script は既定では要らない
                                              ;; （多くは data を見る gate で
                                              ;; classpath を持たない）。repo の
                                              ;; nbb スイートを回す gate だけが
                                              ;; 同じ ~/.gitlibs から classpath を
                                              ;; 組むので、gate 側で明示的に要求する。
                                              (:ship-git-deps w))
                                     (let [m (mirror! (:org-repo w))
                                           dtxt (git-show m (:tip w) "deps.edn")]
                                       (when dtxt
                                         (ship-git-deps! (get-in w [:node :host]) dtxt))))
                                 body (when (:script w)
                                        (str (fs/readFileSync (path/join here (:script w)) "utf8")))
                                 sfile (path/join tmp (str "gate-" (:id w) ".bash-stdin"))
                                 _ (fs/writeFileSync sfile (gate-script (assoc w :name (:id w))
                                                           (:node w) (:tip w) body))
                                 gname (str "test-" (:id w) "-" (sha7 (:tip w))
                                            "-murakumo-" (get-in w [:node :host]))]
                             (assoc w :tarball tgz :script-file sfile :gate-name gname
                                    :cmd (gate-command {:tarball tgz :script-file sfile
                                                        :host (get-in w [:node :host])
                                                        :name (:id w) :sha (:tip w)
                                                        ;; gate のノード側出力をここに残す
                                                        ;; （verdict の grep 対象 + 失敗時の調査用）
                                                        ;;
                                                        ;; **:id + sha で割る（:name ではない）。** verdict は
                                                        ;; このファイルを `grep '^FLEET-CI-EXIT: 0$'` して決めるので、
                                                        ;; 同じ batch の 2 つの work item が同じ path を共有すると
                                                        ;; **互いの出力を上書きし、verdict が取り違わる**。
                                                        ;; 実測 2026-08-03: net-kotobase の tip 1 + PR head 6 が
                                                        ;; 同一 batch で全部 `gate-net-kotobase.out` に書き、
                                                        ;; 落ちた gate が pass と記録され（detail には
                                                        ;; `FLEET-CI-EXIT: 1` が残っていた）、その pass を根拠に
                                                        ;; **west pin が前進した**。root の 4 gate も :name が同じ
                                                        ;; なので同じ競合をずっと起こしていた。
                                                        ;; ノード側の展開 dir と script は line 500 の事故で既に
                                                        ;; :id 基準に直っていたが、operator 側のこの 1 箇所だけが
                                                        ;; :name のまま残っていた。
                                                        :out-file (path/join tmp (str "gate-" (:id w) "-"
                                                                                      (sha7 (:tip w)) ".out"))})))
                           (catch :default e
                             (if-let [why (gate-input-rejection e)]
                               (assoc w :input-rejected why)
                               (throw e))))))
                    rejected (filterv :input-rejected prepared-or-rejected)
                    prepared (filterv (complement :input-rejected) prepared-or-rejected)
                    _ (doseq [w rejected]
                        (log "GATE-INPUT-REJECTED" (:id w) (sha7 (:tip w)) "—" (:input-rejected w))
                        (swap! results conj (assoc w :outcome :input-rejected)))
                    ;; every item in this batch was refused: there is nothing to
                    ;; verify, and asking the fleet CLI to verify an empty repo
                    ;; list would be a receipt over nothing. The refusals are
                    ;; already in `results`, so the run still reports them.
                    _ (when (empty? prepared)
                        (log "batch" bi "— all" (count rejected) "work items refused their input; nothing to run"))
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
                    ;; asking the fleet CLI to verify an empty repo list would
                    ;; sign a receipt over nothing, which is the one outcome
                    ;; worse than no receipt
                    {:keys [exit out]} (if (empty? prepared)
                                         {:exit 0 :out ""}
                                         (sh "npx" args {:timeout (+ 120000 (:gate-timeout-ms cfg))
                                                         :input ""}))]
                (when (seq prepared)
                  (log "batch" bi "ci-verify exit" exit
                       (str "(" (js/Math.round (/ (- (js/Date.now) started) 1000)) "s)")))
                ;; 次回の配分に効かせるため実測を残す（EMA、上界）。
                (pl/record-cost! (map #(keyword (or (:id %) (:name %))) prepared)
                              (js/Math.round (/ (- (js/Date.now) started) 1000)))
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
                    ;; not an error when there was deliberately nothing to run
                    (when (seq prepared)
                      (log "ERROR no receipt produced for batch" bi))
                    (let [landing-result (when-not dry?
                                           (append-receipt! landing (last lines)))
                          landed? (or dry? (:ok landing-result))]
                      (when-not dry?
                        (log "receipt" (sha12 (:cid receipt))
                             (if landed? "landed"
                                 (str "LANDING FAILED " (:detail landing-result)))))
                      (when-not landed? (reset! landing-failed? true))
                      (doseq [w prepared]
                        (let [oc (outcome-of w)
                              ok? (= :pass oc)
                              ;; check の :detail は Radicle issue 本文に入れる。
                              ;; 「落ちた」だけの issue は読んでも何も分からない。
                              det (let [k (keyword (str "gate/" (:gate-name w)))]
                                    (:detail (first (filter #(= k (:name %)) checks))))]
                          (swap! results conj (assoc w :outcome oc :cid (:cid receipt)
                                                     :detail det))
                          ;; receipt が無い pass を state に保存すると次の tick が
                          ;; same tip を skip し、証跡が永久に欠ける。
                          (when (and (not dry?) landed?)
                            (swap! state assoc-in [:repos (:id w)]
                                   {:sha (:tip w) :outcome oc :cid (:cid receipt) :at (now)})
                            (save-state!))))))))))
        ;; ---- Radicle: 落ちたものだけ issue を開く
        (let [rc (:rad cfg)
              failed (filter #(not= :pass (:outcome %)) @results)]
          (cond
            (not (:enabled rc)) nil
            (or dry? (:no-rad opts))
            (when (seq failed) (log "rad skipped (--no-rad/--dry-run):"
                                    (pr-str (mapv :name failed))))
            (empty? failed) nil
            :else
            (let [rids (rad-rid-map landing)]
              (doseq [w failed]
                (if-let [rid (get rids (str "orgs/" (:org w) "/" (:name w)))]
                  (rad-issue-for-failure! rc rid w)
                  (log "rad: no RID registered for" (str (:org w) "/" (:name w))
                       "— skipped (register it in repos.edn rad-rids first)"))))))
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
             (pr-str (mapv (fn [r] [(:name r) (:outcome r)]) @results)))
        (when @landing-failed?
          (throw (ex-info "one or more signed receipts failed to land; state was not advanced"
                          {:fleet-ci/receipt-landing-failed true}))))))))

;; js/process.exit は try/finally を通らないので exit hook で必ず解放する。
(.on js/process "exit" (fn [_] (release-lock!)))
(acquire-lock!)
(-main)

#!/usr/bin/env nbb
;; tick.cljs — fleet-native CI/CD の tick（murakumo mac-mini fleet を git の CD/CI にする本体）。
;;
;; ADR-2607178000 の standing runner を 4 点拡張したもの:
;;   A) trigger を **pin の6時間ごと回帰** から **default branch tip の変化駆動**に
;;      （5 分間隔で tip を見て、前回検証した sha と違う repo だけ gate を回す）
;;   B) 実行先を zebulun/asher ハードコードから **nodes.edn の capability による
;;      動的割り当て + fan-out** に（ノードの provision/増減は probe.cljs 再実行で反映）
;;   C) ~~結果を GitHub commit status として書き戻す~~ — **2026-07-26 に撤回した。**
;;      commit status は GitHub API でしか書けず、launchd 下の gh は Keychain の
;;      token を読めないので匿名に落ちる（下記「git transport」節の実測）。
;;      オーナー方針「github token は使わない」に従って API 経路ごと落とし、
;;      **反映面は Radicle issue（:rad）に一本化**した。
;;      2026-08-17 追記: この行は「書き戻す」と現在形で書かれたまま 22 日残り、
;;      status が 0 件なのを見た者に「壊れている」と読ませた（実際は仕様どおり
;;      存在しない）。撤回した機能を現在形で書かない。
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
;;   nbb scripts/fleet-ci/tick.cljs --dry-run         ;; gate まで実行するが landing/rad/CD をしない
;;   nbb scripts/fleet-ci/tick.cljs --only kagami     ;; repo を絞る（変化が無くても回す）
;;   nbb scripts/fleet-ci/tick.cljs --all             ;; 全 repo を強制的に回す
;;   nbb scripts/fleet-ci/tick.cljs --no-cd           ;; pin 前進（CD）だけ止める
;;   nbb scripts/fleet-ci/tick.cljs --plan            ;; 何を回すかだけ出して終わる
;;   nbb scripts/fleet-ci/tick.cljs --no-pr           ;; PR head の検証を止める（tip のみ）
;;   nbb scripts/fleet-ci/tick.cljs --pr-cap 3        ;; repo あたりの PR 検証上限（既定 10）
(ns tick
  (:require ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
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

(defn west-path
  "west project の checkout path（`orgs/<org>/<repo>`）。rad-rids はこれを key にする。"
  [west nm]
  (get-in west [:projects nm :path]))

(defn repo-name-of
  "**west の project 名ではなく、GitHub 上の repo 名。**

  west は `name:` と `repo-path:` を分けられる。実測 2026-08-18、4,191 project のうち
  **59 が両者で違う**（例: name `cloud-itonami-gftd-audio-actor` / repo-path
  `gftd-audio-actor` / path `orgs/cloud-itonami/gftd-audio-actor`）。

  ここを project 名で組み立てていたせいで、その 7 件は gates.edn に載っていながら
  **一度も走っていなかった** —— `git ls-remote git@github.com:cloud-itonami/
  cloud-itonami-gftd-audio-actor.git` は「repository does not exist」で、tip が nil に
  なり、tip の無い work は黙って落とされる。state ファイルの 2,063 entry に
  `gftd-` を含むものは 1 件も無い（実測）。

  `path:` の最終セグメントが正しい repo 名で、`repo-path:` が無ければ name と同じに
  なるので、この 1 行が両方を吸収する。"
  [west nm]
  (if-let [p (west-path west nm)]
    (last (str/split p #"/"))
    nm))

(defn org-repo-of
  "`<github-org>/<github-repo>` for a gates.edn entry. **The one place this string is
  built.**

  It used to be built in two places — the `tip-of` map that calls `gh-tip`, and the work
  item that looks the tip up — and on 2026-08-18 they disagreed: the lookup was corrected
  to the west path while the map still used the project name. Both produce nil for a
  missing key, so half a fix looked exactly like no fix, and the seven affected gates went
  on printing `no tip resolved`. Producer and consumer now cannot drift apart."
  [west r]
  (when-let [o (or (:org r) (org-of west (:name r)))]
    (str o "/" (repo-name-of west (:name r)))))

(defn gate-id
  "gates.edn entry の識別子。既定は :name（= repo 名）だが、**1 つの repo に
  複数の gate を載せたいときは :id を明示する**。:name は org / tip 解決に使う
  repo 名のままにしておく必要があるので、識別子を分ける。

  これが無いと 2 つ目の entry が (a) state の [:repos <name>] を共有して
  tip 変化検出を奪い合い (b) gate-name が `test-<name>-<sha7>-murakumo-<host>`
  で衝突して checks map で潰し合う。superproject は不変条件が増えていく対象
  なので、1 gate/repo の制約はここで外しておく。"
  [r] (or (:id r) (:name r)))

;; ---------------------------------------------------------------------------
;; gate declaration identity（ADR-2608137000）
;;
;; trigger は「対象 repo の tip が動いたか」だけだった（:trigger :tip-change）。
;; それは **検査される側**が動いたことの正しい合図であって、**検査する側**が
;; 動いたことは一切見ていない。gates.edn の宣言や gate script を直しても、
;; 対象 repo が休んでいる限り tick はその gate を二度と回さない —— 直した gate は
;; 直す前の赤を永久に表示し続け、しかもそれが古い判定であることを言うものが無い。
;;
;; 実測 2026-08-13: `gh-workflow-assoc-gapki` は :include-ext に ".kotoba" を
;; 足して直った（それが欠陥の全部だった）が、対象 repo
;; cloud-itonami-assoc-0126-idn-gapki の tip は 2026-08-11 の e261787 から動いて
;; おらず、最後の receipt は修正の前日のもの。「gate は fleet で一度 green を
;; 見るまで landed としない」という規則が、この経路では構造的に果たせない。
;;
;; 直し方は最小にする: state に **その gate 自身の宣言の hash** を並べて持ち、
;; tip と同じ扱いで比較する。tip か宣言のどちらかが動いたら回す。
;;
;; 範囲を意図的に狭くしてある:
;;   * hash は **その entry 1 件**（+ :script が名指しするファイルの中身）だけを
;;     見る。gates.edn は毎日編集されるので、ファイル全体の hash にすると
;;     1 行の編集で 125 repo が再配置される —— それは元の問題より悪い。
;;   * key の並び順は宣言の一部ではないので sorted-map に正規化してから pr-str
;;     する（entry を書き換えずに並べ替えただけで再実行しない）。
;;   * コメントは reader が捨てるので hash に入らない（コメントだけの編集では
;;     再実行しない）。
;;   * cfg の global key（:policy / :gate-timeout-ms / :landing …）は入れない。
;;     それらを変えると全 gate の意味が変わるが、そこで 125 repo を一斉に回す
;;     判断は tick が黙ってするものではない（--all が明示的にある）。
;;   * 共有 script（gates/github_workflow_run.cljs は 3 gate が使う）を編集すると
;;     その script を使う gate だけが回る。これは正しい範囲で、有界。

(def derived-keys
  "tick が work item に足す key。**宣言の一部ではない**ので hash から外す。

  ここを外し忘れると :tip が hash に混ざり、毎 tick 全 gate の spec-hash が動く
  = 毎 tick 125 repo 再配置、という最悪形になる。下の unit test
  `derived-keys-do-not-leak-into-the-declaration-hash` がそれを見張っている。"
  #{:org-repo :tip :pin :last-sha :changed? :spec-hash :last-spec :pr :node
    :gate-name :cmd :tarball :script-file :sha :out-file :bundle? :outcome
    :cid :detail :input-rejected})

(defn canonical-decl
  "gate 宣言の決定的な文字列表現。key の並び順は宣言の一部ではない。"
  [r]
  (pr-str (into (sorted-map) (apply dissoc r derived-keys))))

(defn decl-hash
  "gates.edn の 1 entry だけの hash（script の中身は含まない）。
  gate-spec-drift.cljs が履歴を歩くときにも使う。"
  [r]
  (subs (-> (crypto/createHash "sha256") (.update (canonical-decl r)) (.digest "hex")) 0 16))

(defn gate-spec-hash
  "gate の宣言 + それが名指しする script の中身の hash。
  script が読めないときは nil を混ぜる（読めないこと自体は tick の別経路が落とす）。"
  [r]
  (let [body (when (:script r)
               (try (str (fs/readFileSync (path/join here (:script r)) "utf8"))
                    (catch :default _ nil)))]
    (subs (-> (crypto/createHash "sha256")
              (.update (canonical-decl r))
              ;; Domain separator. Written as an escape, NOT as a raw NUL
              ;; byte: `"\u0000"` reads to the same one-character string, so
              ;; the digest is unchanged, but a literal 0x00 made `file` call
              ;; this script "binary data" and every `grep` over it return
              ;; NOTHING -- silently, with exit 1, indistinguishable from
              ;; "no match". Measured 2026-08-17: three separate searches of
              ;; this file during one session came back empty and were read
              ;; as absence of code.
              (.update "\u0000")
              (.update (str body))
              (.digest "hex"))
          0 16)))

(defn work-changed?
  "この work item を回すべきか。

  `last-spec` が nil のときは **回さない**。これが移行の全部で、既存 state の
  1,583 entry には spec-hash が無いから —— nil を「変わった」と読むと導入した
  tick が 125 repo 全部を一斉に配置する。nil は下の backfill が現在の hash で
  埋め、以後の編集からが検出対象になる。埋めた時点で赤かった gate（= 既に
  drift していたもの）は黙って現行扱いになるので、その母集団は
  gate-spec-drift.cljs が git 履歴から別に数える。"
  [{:keys [tip last-sha spec-hash last-spec]}]
  (boolean (and tip (or (not= tip last-sha)
                        (and last-spec (not= spec-hash last-spec))))))

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

(defn- head-already-in-default?
  "Is `sha` already reachable from `org-repo`'s default branch?

  An open pull request whose head the default branch already contains has
  nothing left to verify — the tip gate covers exactly those commits. A
  CLOSED-and-merged one has the same shape, and that is the case this
  predicate is for.

  `open-prs` below infers openness from `refs/pull/N/merge`, on the stated
  ground that GitHub deletes that ref when a PR closes. **Measured
  2026-09-06, it does not always**: net-kotobase/search-origin had 1 merge
  ref and 0 open PRs (`gh pr list --state open` → `[]`), and
  net-kotobase/control-plane 17 against 16. A phantom like that is not a
  one-off red — it is permanent, and it reads as a defect in the repo:
  search-origin's stale head predates the test entry its new gate needs, so
  the gate reported `test entry missing after extract` forever.

  **This fixes one of the two shapes, and the other is left standing rather
  than assumed away.** A phantom from a MERGED pull request has its head on
  the default branch, and that is what this drops — search-origin's #2,
  measured. A phantom from a pull request CLOSED WITHOUT MERGING does not:
  control-plane's 17 merge refs against 16 open PRs, and **none** of the 17
  heads is an ancestor of main. Deciding that case needs the PR's state,
  which is the API this runner deliberately does not use (owner policy:
  github token は使わない). So control-plane keeps its phantom, and this
  docstring is where someone will find out why.

  The mirror only fetches `refs/heads/*`, so a genuinely open PR's head is
  not in it at all and `cat-file` fails — which is the fail-open branch.
  **Ancestry that cannot be decided KEEPS the PR.** The cost of an extra
  verification is one run; the cost of dropping one silently is an unverified
  PR that looks verified, and this file should not be in the business of
  making that trade quietly."
  [org-repo sha]
  (try
    (let [d (mirror! org-repo)
          default (str/trim (str (:out (git d ["symbolic-ref" "--short" "HEAD"]))))]
      (boolean
       (and (seq default)
            (zero? (:exit (git d ["cat-file" "-e" (str sha "^{commit}")])))
            (zero? (:exit (git d ["merge-base" "--is-ancestor" sha default]))))))
    (catch :default _ false)))

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
        (let [candidates (sort-by :number
                                  (for [n merge-nums :when (get heads n)]
                                    {:number (js/parseInt n 10) :head (get heads n)}))
              [keep- dropped] [(remove #(head-already-in-default? org-repo (:head %)) candidates)
                               (filter #(head-already-in-default? org-repo (:head %)) candidates)]]
          ;; Report the drop rather than performing it silently: "0 PRs" and
          ;; "1 PR whose head is already on main" are different facts, and the
          ;; second one is how a stale merge ref shows itself.
          (when (seq dropped)
            (log "PR skip:" org-repo (pr-str (mapv :number dropped))
                 "— head already reachable from the default branch"
                 "(merged, or a merge ref GitHub did not delete);"
                 "the tip gate covers those commits"))
          (vec keep-))))))

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
  [org-repo sha {:keys [include-ext min-files require-paths]}]
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
        ;; **名指しした入力の不在は、件数の不足より先に報告する。** どちらも
        ;; `:input-rejected` を出すが、読み手を送る先が違う。
        ;;
        ;; 実測 2026-08-19: `amu-windows-loader-cross` が amu の PR #547/#548
        ;; （9〜10 日前の Dependabot 枝）に対して 90 秒ごとに
        ;; 「filter found only 67 files (expected >= 70)」を出し続けていた。
        ;; その枝に無いのは 67 番目のファイルではなく、**この gate が実行する
        ;; verifier 本体**（`scripts/windows-loader-cross-compile.cljs`。gate より
        ;; 枝の方が古い）で、件数はその副作用にすぎない。件数だけを読むと
        ;; `:min-files` を下げに行く —— 実際にそうしかけた。床を下げれば gate は
        ;; 走り、wrapper が exit 90 で「verifier is missing」と正しく言うが、
        ;; 空 tree を弾くという床本来の役目は弱まる。直すべきは枝であって床ではない。
        ;;
        ;; ⚠ この検査は cache miss のときだけ走る（上の `fs/existsSync` で早期 return
        ;; するため）。既に tarball がある sha に後から `:require-paths` を足すと、
        ;; その sha では検査されない。fail-open だが degrade 先は今日の挙動
        ;; （wrapper 自身の exit 90 が拾う）なので、tick ごとに ls-tree を増やして
        ;; まで塞がない。拒否された sha は tarball を作れていないので、実害のある
        ;; stale cache は原理的に存在しない。
        ;;
        ;; 出所を 2 つに分けるのは、対処が違うから: tree に無いなら枝を
        ;; rebase / close する、tree にあって filter が落としたなら
        ;; `:include-ext` を直す。後者は 2026-07-29 に実際に起きている
        ;; （この関数の docstring が記録している root の 2 gate の取り違え）—— その時は
        ;; wrapper の exit 90 が拾ったが、名前で拒否していれば tick が言えていた。
        (when (seq require-paths)
          (let [in-tree (set all)
                shipped (set files)
                absent (filterv #(not (contains? in-tree %)) require-paths)
                filtered-out (filterv #(and (contains? in-tree %)
                                            (not (contains? shipped %)))
                                      require-paths)]
            (when (seq absent)
              (reject-input!
               (str "this gate reads " (str/join ", " absent) ", which " org-repo
                    " does not have at " (sha7 sha) " — the gate cannot answer here."
                    " Rebase or close the branch; do not lower :min-files")))
            (when (seq filtered-out)
              (reject-input!
               (str "this gate reads " (str/join ", " filtered-out) ", which exists in "
                    org-repo " at " (sha7 sha) " but :include-ext " (pr-str include-ext)
                    " does not ship — widen :include-ext")))))
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

(defn self-pin-ref
  "bundle が運ぶ ref 名。dep 側（ship-git-deps!）と同じ規則にしてある —
  `mirror!` の fetch refspec は `+refs/heads/*:refs/heads/*` なので
  `refs/fleet-ci/*` は prune されず、object も保持される。"
  [sha]
  (str "refs/fleet-ci/pin/" sha))

(defn self-bundle!
  "**gate 対象 repo 自身**を `git bundle` として用意する（entry の `:ship-self-bundle true`）。

  既定の配送は `git archive` の tarball で、**その出力に `.git` は入らない**（入れる
  option も無い）。したがって「この repo の履歴」を入力にする gate は、既定の経路では
  ノード上で問いを立てられない —— 実測 ADR-2608132400、`kotoba-provider-orphan-check`
  は展開 tree に `.git` が無いことを検出して exit 90 で拒否する。

  これは新機構ではない。**同じ日に依存側で landed した経路の適用範囲を 1 つ広げただけ**
  である（ADR-2608132000）: `ship-git-deps!` は `~/.gitlibs/libs/<lib>/<sha>` に
  `git archive` の展開物を置いていたため、amu の JDK-free resolver の
  `git rev-parse HEAD` が nil を返し、`amu-native-conformance` が 28 run 連続で赤だった。
  直し方は bundle を送ってノードで本物の checkout を作ることで、
  **object の検証は git 自身がやる**（`bundle` は不完全な prerequisite を拒否する）。

  ここで送るのは `<mirror>` の `refs/fleet-ci/pin/<sha>` から到達可能な全 object、
  つまり **full history** である。それがこの経路の目的物なので絞り込めない ——
  `:include-ext` との併用は `gate-input!` が拒否する（下記）。"
  [org-repo sha]
  (let [f (path/join cache-dir (str (str/replace org-repo "/" "-") "-" (sha12 sha) "-self.bundle"))]
    (when-not (fs/existsSync f)
      (fs/mkdirSync cache-dir #js {:recursive true})
      (let [m (ensure-sha! (mirror! org-repo) org-repo sha)
            pin-ref (self-pin-ref sha)]
        (when-not (zero? (:exit (git m ["update-ref" pin-ref sha])))
          (reject-input! (str "could not create " pin-ref " in the mirror of " org-repo
                              " — :ship-self-bundle needs a named ref to bundle")))
        (let [{:keys [exit out]} (git m ["bundle" "create" f pin-ref] {:timeout 1800000})]
          (when-not (zero? exit)
            ;; tarball 経路へ黙って落ちない。落ちれば `.git` の無い tree が届き、
            ;; 履歴を読む gate は exit 90 を返す —— 配送の失敗が検査の失敗に化ける。
            (reject-input! (str "git bundle create failed for " org-repo "@" (sha7 sha)
                                ": " (str/trim out)))))))
    f))

(defn gate-input!
  "gate に渡す入力ファイル。

  - `:ship-self-bundle true` … repo 自身の **git bundle**（履歴つき）
  - `:include-ext` あり       … 絞り込み tarball
  - どちらも無し             … repo 全体の tarball（既定。今日までと同じ）

  ノードへ送るサイズに上限を掛ける。"
  [{:keys [org-repo tip include-ext min-files require-paths name ship-self-bundle]}]
  (let [f (cond
            ship-self-bundle
            (do
              ;; **filter と bundle は両立しない。** bundle は「その commit から
              ;; 到達可能な object 全部」であって tree の部分集合ではないので、
              ;; `:include-ext` を書いても効かない。黙って無視すると、entry を書いた
              ;; 人は「絞って送っている」と読み続ける —— このワークスペースが
              ;; 繰り返し踏んできた形（no-op な設定が正しく見える）なので、拒否する。
              ;; 履歴を読む gate は tree 全体を必要とするので、絞る要求自体が誤り。
              (when (or (seq include-ext) min-files (seq require-paths))
                (reject-input!
                 (str name ": :ship-self-bundle cannot be combined with"
                      " :include-ext/:min-files/:require-paths"
                      " — a git bundle carries the whole repository (that is the point: the gate"
                      " reads history), so the filter would silently do nothing. Drop them;"
                      " the gate's own --min floor is what guards against an unread tree.")))
              (self-bundle! org-repo tip))

            (seq include-ext)
            (filtered-tarball! org-repo tip {:include-ext include-ext :min-files min-files
                                             :require-paths require-paths})

            :else (full-tarball! org-repo tip))
        mb (/ (.-size (fs/statSync f)) 1048576)]
    (when (> mb max-ship-mb)
      (reject-input! (str name " gate input is " (js/Math.round mb) "MB (> " max-ship-mb "MB) — "
                          (if ship-self-bundle
                            (str ":ship-self-bundle ships full history, which for a repo this size"
                                 " is too much to push over ssh each tick; this gate needs a"
                                 " different delivery, not a bigger ceiling")
                            "add :include-ext to gates.edn so only the inspected paths are shipped"))))
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

;; The gate command emits this when the tree could not be placed on the node --
;; almost always because ssh could not reach it. `unreachable-outcome?` below
;; matches the same constant, so the emitter and the reader cannot drift.
;;
;; **This is not a verdict about the repository.** It is the node-side twin of
;; `reject-input!`: in both cases the gate never ran, so there is nothing to
;; conclude. `reject-input!` already keeps its case out of the ledger; this one
;; did not, and the consequence is worse than a misleading line. `work-changed?`
;; skips a gate whose recorded sha equals the current tip, so recording a
;; :fail here **pins the red until somebody pushes a new commit** -- on a quiet
;; repo, indefinitely.
;;
;; Measured 2026-08-19 over the tick log since 2026-08-03: 566 extract failures,
;; of which 505 are `ssh: connect ... Operation timed out`, plus 13 `Read from
;; remote host` and 10 `Connection timed out during banner exchange` -- 528
;; unreachability events written into the ledger as judgements about code.
;; Spread across every node (levi 196, dan 187, judah 124, and six others), so
;; it is transient tailnet loss rather than one bad machine.
;;
;; The signed receipt still says :fail, and that stays honest -- the gate really
;; did not report success. What changes is that the OPERATOR's ledger no longer
;; treats that as having judged this sha, so the next tick tries again.
(def extract-fail-sentinel "FLEET-CI: extract failed on")

;; The tree ARRIVED and the gate then found the node cannot run it — a missing
;; toolchain, not a missing file. `:cap` is supposed to prevent that, and a gate
;; script cannot know its own `:cap` declaration, so this is the second line of
;; defence rather than a substitute: `:cap` stops the misplacement, this stops a
;; misplacement that happens anyway from being written down as a defect.
;;
;; Measured 2026-08-19: 31 `itonami-regenerate-*` gates ran `clojure
;; -M:dev:render-html` with no `:cap`, so a third of the fleet answered about
;; itself — 13 failures on the three nodes without `clojure`, zero passes there.
;; Declaring `:cap :jvm` (ADR-2608198600) stops new ones. It does not un-record
;; `itonami-regenerate-854 :fail 48334d3`, which now sits red until that repo
;; gets a commit, for a reason that was never about that repo.
;;
;; A gate that emits this is saying "ask somebody else", not "this is broken".
(def cannot-answer-sentinel "FLEET-CI: cannot answer on")

(defn not-a-verdict?
  "True when this check did not judge the repository at all — the tree never
  arrived, or it arrived somewhere that cannot run the gate. Both read sentinels
  emitted on this side of the wire, so there is no second spelling to keep in
  step.

  The caller must neither record these nor open an issue for them: recording one
  makes `work-changed?` skip the sha, which pins a red that says nothing about
  the code until somebody pushes."
  [detail]
  (and (some? detail)
       (let [d (str detail)]
         (or (str/includes? d extract-fail-sentinel)
             (str/includes? d cannot-answer-sentinel)))))

;; Kept as the old name because `unreachable` is still the common case and reads
;; better at the call site that logs it.
(def unreachable-outcome? not-a-verdict?)

(defn gate-script
  "gate 1 本ぶんのノード側スクリプト。どの終了経路でも最後に
  `FLEET-CI-EXIT: <code>` を必ず出す（これが唯一の verdict 伝達路）。"
  [{:keys [name gate classpath entry script script-args npm-script] :as w} node sha script-body]
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
         ;; The pinned Wasm toolchain prefix, ahead of Homebrew on purpose.
         ;; amu refuses any wasm-tools other than the version its language
         ;; contract pins, and Homebrew can only ever install the newest, so the
         ;; pinned pair lives in a prefix of its own. probe.cljs reads the same
         ;; path and grants :wasm-tools only when all three tools are there, so
         ;; the two must stay in step. A no-op on nodes without the prefix.
         "export PATH=$HOME/.gftd/wasm-pin/bin:$JAVA_HOME/bin:$PATH"
         "java -version 2>&1 | head -1"
         (str "for i in $(seq 1 900); do mkdir " dep-lock " 2>/dev/null && break;"
              " [ -n \"$(find " dep-lock " -maxdepth 0 -mmin +20 2>/dev/null)\" ]"
              " && rmdir " dep-lock " 2>/dev/null; sleep 1; done")
         (str "clojure -P -M:" alias-name " >/dev/null 2>&1")
         (str "rmdir " dep-lock " 2>/dev/null || true")
         ;; **テストが実際に走ったことも assert する**: summary 行が無い / 0 件は fail。
         (str "out=$(clojure -M:" alias-name " 2>&1); code=$?")
         "echo \"$out\" | tail -25"
         ;; Keep the count IN the receipt, as the last line before the exit
         ;; marker. `tail -25` puts it wherever the suite happened to print it,
         ;; and the receipt detail is truncated from the end -- measured
         ;; 2026-08-19, comparing 15 gates against their JVM baselines, 7 of
         ;; them had passed with a summary the receipt no longer carried, so
         ;; the only question that matters when moving a suite between
         ;; runtimes ("did it run the SAME tests?") could not be answered from
         ;; the record. The floor below already greps for this line; printing
         ;; it costs nothing and makes every receipt comparable.
         "echo \"$out\" | grep -E 'Ran [0-9]+ tests containing [0-9]+ assertions' | tail -1"
         "echo \"$out\" | grep -qE 'Ran [0-9]+ tests' || fail 'no test summary in output — refusing to report a pass' 93"
         "echo \"$out\" | grep -qE 'Ran 0 tests' && fail 'zero tests ran' 94"
         "echo \"FLEET-CI-EXIT: $code\""]
        :nbb-test
        [(str "test -f " (or entry "run-tests.cljs")
              " || fail 'test entry missing after extract' 90")
         ;; npm deps, when the repo has them. Measured 2026-08-18: of the eight
         ;; repos this gate kind can newly take, four need `npm install` and four
         ;; do not — io-ipld-car reached `Cannot find module '@noble/hashes/sha2.js'`
         ;; with every Clojure namespace already resolved. Silent, and skipped
         ;; entirely when there is no package.json.
         "if [ -f package.json ]; then npm install --silent >/dev/null 2>&1 || fail 'npm install failed' 95; fi"
         (str "out=$(npx --yes nbb --classpath " (or classpath "src:test") " "
              (or entry "run-tests.cljs") " 2>&1); code=$?")
         "echo \"$out\" | tail -25"
         ;; Keep the count IN the receipt, as the last line before the exit
         ;; marker. `tail -25` puts it wherever the suite happened to print it,
         ;; and the receipt detail is truncated from the end -- measured
         ;; 2026-08-19, comparing 15 gates against their JVM baselines, 7 of
         ;; them had passed with a summary the receipt no longer carried, so
         ;; the only question that matters when moving a suite between
         ;; runtimes ("did it run the SAME tests?") could not be answered from
         ;; the record. The floor below already greps for this line; printing
         ;; it costs nothing and makes every receipt comparable.
         "echo \"$out\" | grep -E 'Ran [0-9]+ tests containing [0-9]+ assertions' | tail -1"
         "echo \"$out\" | grep -qE 'Ran [0-9]+ tests' || fail 'no test summary in output — refusing to report a pass' 93"
         "echo \"$out\" | grep -qE 'Ran 0 tests' && fail 'zero tests ran' 94"
         "echo \"FLEET-CI-EXIT: $code\""]
        ;; `:shadow-test` — a suite whose runner is `shadow-cljs compile … && node …`.
        ;;
        ;; This is the third runtime this workspace ships to, and until now the
        ;; only one with no gate: `:jvm-test` is meaningless for a `.cljs` suite
        ;; and `:nbb-script` is the wrong shape. The note further up this file
        ;; said so and stopped there, so two repos carrying live auth code —
        ;; kotobase-server and net-kotobase/engine — had no automated check at
        ;; all while being changed daily.
        ;;
        ;; It needs BOTH runtimes on the node (JVM to compile, node to run), and
        ;; it refuses by name on the two dependency shapes a node cannot satisfy,
        ;; because a fleet node holds ONE repo's tree: a `file:` npm dep on a
        ;; sibling, and a `:local/root` sibling in deps.edn. Measured 2026-08-19:
        ;; kotobase-server has neither and net-kotobase/engine has both, which is
        ;; why only the first is registered.
        ;;
        ;; The npm script is named by `:npm-script`, NOT `:script`. `:script`
        ;; already means "a file under gates/" for `:nbb-script`, and reusing it
        ;; here made the tick try to open scripts/fleet-ci/test:cljs. It failed
        ;; loudly, which was luck: the two meanings are both strings, and a key
        ;; that collides with an optional one fails quietly.
        :shadow-test
        [(str "test -f shadow-cljs.edn || fail 'shadow-cljs.edn missing after extract' 90")
         "test -f package.json || fail 'package.json missing after extract' 90"
         (str "grep -q '\"file:\\.\\./' package.json"
              " && fail 'npm file: dependency on a sibling — a node holds one repo tree' 96 || true")
         (str "grep -q ':local/root' deps.edn 2>/dev/null"
              " && fail ':local/root dependency on a sibling — a node holds one repo tree' 96 || true")
         (str "export JAVA_HOME=" (or (:java-home node) "/opt/homebrew/opt/openjdk"))
         "export PATH=$JAVA_HOME/bin:$PATH"
         "npm install --silent >/dev/null 2>&1 || fail 'npm install failed' 95"
         (str "for i in $(seq 1 900); do mkdir " dep-lock " 2>/dev/null && break;"
              " [ -n \"$(find " dep-lock " -maxdepth 0 -mmin +20 2>/dev/null)\" ]"
              " && rmdir " dep-lock " 2>/dev/null; sleep 1; done")
         (str "out=$(npm run " (or npm-script "test:cljs") " 2>&1); code=$?")
         (str "rmdir " dep-lock " 2>/dev/null || true")
         "echo \"$out\" | tail -25"
         ;; And the summary explicitly, because for THIS kind `tail -25` does
         ;; not contain it: shadow prints "Build completed" after the tests, so
         ;; the receipt recorded a pass whose detail said only that something
         ;; compiled. A gate whose receipt cannot answer "how many tests ran" is
         ;; green in a way nobody can read. Measured on receipt 7b9cdd831236.
         "echo \"$out\" | grep -E 'Ran [0-9]+ tests' | tail -1"
         ;; Same floor as the other kinds: a build that compiled and ran nothing
         ;; is not a pass, and the whole point of this file is that the two must
         ;; not share an outcome.
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
  #"io\.github\.([a-zA-Z0-9_.-]+)/([a-zA-Z0-9_.-]+)(\s*\{[^}]*?:git/sha\s+\"([0-9a-f]{40})\"[^}]*\})")

(defn- dep-repo-name
  "依存 entry の :git/url から GitHub repo 名を取る。ライブラリ記号は repo 名と
   一致しないことがある（実測: cloud-itonami-app の
   `io.github.kotoba-lang/authentication-email` は repo `authentication` の
   module alias（:deps/root \"modules/email\"）。lib 記号を repo 名として
   mirror! すると `Repository not found` で die し、tick 全体が毎回死亡した
   （実測 2026-09-01 13:28Z 以降 77 件の FATAL、fleet CI 停止）。
   :git/url が無い entry は従来形（lib 記号 = repo 名）にフォールバックする。"
  [lib-symbol entry-text]
  (let [repo (second (re-find #":git/url \"https://github\.com/([a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+?)(\.git)?\"" entry-text))]
    (or repo lib-symbol)))

(defn git-deps-of
  "deps.edn の本文 → [{:lib \"io.github.org/name\" :org :repo :sha} …]。
   :repo は **:git/url から導出**する（lib 記号は repo 名の別名のことがある —
   dep-repo-name の docstring 参照）。
   :override-deps（:local 用）は :git/sha を持たないので自然に外れる。"
  [deps-text]
  (mapv (fn [[_ org lib entry sha]]
          (let [repo (dep-repo-name (str org "/" lib) entry)
                [rorg rrepo] (str/split repo #"/")]
            {:lib (str "io.github." org "/" lib)
             :org (or rorg org)
             :repo (or rrepo lib)
             :sha sha}))
        (re-seq git-dep-re (str deps-text))))

(defn git-deps-closure
  "gate repo の git 依存の推移閉包を、`ship-git-deps!` が置くのと同じ順で返す。

  なぜ要るか。`:nbb-test` の classpath は gates.edn に書いた literal
  （既定 `src:test`）で、ノードに送られた **その repo の tree の中しか指せない**。
  sibling repo を require するテストはそこで `Could not find namespace` になり、
  ADR-2608180100 はそれを「`:nbb-test` の設計の性質」と書いた。半分は誤りで、
  運搬機構（`ship-git-deps!`）は既に在り、欠けていたのは
  `~/.gitlibs/libs/<lib>/<sha>/src` を classpath に組む一手だけだった。

  実測 2026-08-18、条件3 で弾かれていた 14 本にこの classpath を与えると
  **8 本が JVM と同じ件数で通った**（io-ipld-car 16/54、kotobase-lake 77/259、
  kotobase-protocols 66/238 ほか）。残りは 3 本が `.clj` テストを持つため件数が
  合わず（classpath では直らない）、3 本が pin の古い io-ipld に `ipld.value` が
  無いため落ちる。"
  [deps-text]
  (loop [queue (git-deps-of deps-text) seen #{} out []]
    (if (empty? queue)
      out
      (let [{:keys [lib sha org repo] :as d} (first queue)
            k [lib sha]]
        (if (contains? seen k)
          (recur (rest queue) seen out)
          (let [m (try (ensure-sha! (mirror! (str org "/" repo)) (str org "/" repo) sha)
                       (catch :default _ nil))
                child (if m (git-deps-of (or (git-show m sha "deps.edn") "")) [])]
            (recur (concat (rest queue) child) (conj seen k) (conj out d))))))))

(defn nbb-deps-classpath
  "`git-deps-closure` を `~/.gitlibs` の path へ。`$HOME` は **リモートで**展開させる
   （`ship-git-deps!` の dest と同じ理由 — 手元で展開すると他人の HOME が渡る）。"
  [base deps-text]
  (->> (git-deps-closure deps-text)
       (map (fn [{:keys [lib sha]}] (str "$HOME/.gitlibs/libs/" lib "/" sha "/src")))
       (cons base)
       (str/join ":")))

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

  推移的な依存も辿る（依存先の deps.edn を mirror から読んで再帰）。

  `ready` は tick 内で共有する atom {[host lib sha] child-deps}。同じ node に同じ pin
  を使う gate が複数あっても、SSH 確認・mirror・archive 搬送は最初の1回だけにする。
  child-deps は再び queue に入れるので、前回失敗した推移依存は必ず retry される。"
  ([host deps-text] (ship-git-deps! host deps-text (atom {})))
  ([host deps-text ready]
   (loop [queue (git-deps-of deps-text) seen #{} shipped 0]
     (if (empty? queue)
       shipped
       (let [{:keys [lib org repo sha]} (first queue)
             k [lib sha]
             ready-k [host lib sha]]
         (cond
           (contains? seen k)
           (recur (rest queue) seen shipped)

           ;; Calls are sequential inside one tick. A ready dependency came
           ;; from an earlier completed traversal, so its transitive closure
           ;; was already visited too.
           (contains? @ready ready-k)
           (recur (concat (rest queue) (get @ready ready-k)) (conj seen k) shipped)

           :else
           ;; $HOME は **リモートで**展開させる。ローカルの bash -c の二重引用符の
           ;; 中に素で置くと手元で展開され、リモートに他人の HOME パスが渡る
           ;; （実測: asher に `mkdir: /Users/junkawasaki: Permission denied`）。
           (let [dest (str "\\$HOME/.gitlibs/libs/" lib "/" sha)
                ;; **exit code は見ない**。Tailscale SSH + /usr/bin/login では
                ;; リモートの終了ステータスが伝播せず必ず 0 になる（gate-command
                ;; が sentinel を grep しているのと同じ理由）。最初の版はこれを
                ;; 忘れて `test -d` を exit で判定したので「常に present」と読み、
                ;; 依存を 1 つも送らないまま成功したように見えていた。
                ;; **present とは「その sha の checkout がそこに在る」ことで、
                ;; 「その名前の dir が在る」ことではない**（2026-08-13）。
                ;;
                ;; 旧版は `test -d <dest>` だったが、これは 2 つの理由で壊れていた:
                ;;
                ;; ① **一度も真にならなかった。** `dest` は `\$HOME/...` で始まる。
                ;;    この文字列を **argv 経由**でリモートに渡すと（ローカルの
                ;;    shell を通らないので `\$` がそのまま残り）、リモートの sh は
                ;;    `\$` を「$ のエスケープ」と読んで**リテラルの `$HOME`** に
                ;;    する。つまり毎 tick 全依存を再送していた（実測 2026-08-13、
                ;;    simeon に対して既存 dir で空文字列が返る）。下の ship 側は
                ;;    ローカル `bash -c` の二重引用符を通るので正しく展開されて
                ;;    いた —— 同じ文字列が経路によって別物になっていた。
                ;; ② 名前だけでは中身を保証しない。`ship-git-deps!` が置くのは
                ;;    `git archive` の展開で `.git` が無い。tools.deps は dir の
                ;;    存在しか見ないので気付かないが、**JDK-free resolver は
                ;;    `git rev-parse HEAD` で content-address を検証する**
                ;;    （amu の `kotoba.compiler.nbb.classpath/head-sha`）。
                ;;    実測 2026-08-13: simeon / judah とも amu の lock 19 件中
                ;;    18 件がこの archive 形で、`amu-native-conformance` は
                ;;    `{:phase :verify :expected "32ee84b2…" :actual nil}` で
                ;;    28 回連続で落ちていた。**dir が在るせいで、その gate に
                ;;    `:ship-git-deps true` を足しても直らない** —— 旧 present?
                ;;    が偶然 false だったので毎回上書きされ、しかも上書きされる
                ;;    たびに archive 形に戻っていた。
                ;;
                ;; したがって present? は sha を照合する。`bash -c` を通すのは
                ;; `\$HOME` をリモートで展開させるため（① の再発防止）。
                present? (str/includes?
                          (str (:out (sh "bash"
                                         ["-c" (str "ssh -o BatchMode=yes -o ConnectTimeout=20 "
                                                    host " \"git -C " dest
                                                    " rev-parse HEAD 2>/dev/null | grep -qx " sha
                                                    " && echo FLEET-CI-DEP-PRESENT\"")]
                                         {:timeout 120000})))
                          "FLEET-CI-DEP-PRESENT")
                m (try (ensure-sha! (mirror! (str org "/" repo)) (str org "/" repo) sha)
                       (catch :default e
                         (log "WARN dep mirror failed for" lib (sha7 sha) "—" (ex-message e))
                         nil))
                 next-deps (when m (git-deps-of (or (git-show m sha "deps.edn") "")))
                 status
                 (cond
                   (nil? m) :unavailable
                   present? :present
                   :else
                   ;; **bundle を送って本物の checkout にする**（2026-08-13）。
                   ;;
                   ;; 送るものを `git archive` の tarball から `git bundle` に
                   ;; 変えた。理由は上の present? の②で、`~/.gitlibs/libs/<lib>/<sha>`
                   ;; という path には **2 つの契約が同居している**:
                   ;;   - tools.deps（`clojure -Spath`）は dir の存在しか見ない
                   ;;   - JDK-free resolver は `git rev-parse HEAD` が pin と
                   ;;     一致することを見る（「commit の名前が付いた dir は、
                   ;;     その commit を保持している証拠ではない」— amu の
                   ;;     classpath ns の docstring）
                   ;; tarball は前者しか満たさない。bundle なら **git 自身が
                   ;; object を検証する**ので、両方を満たしたうえで検査が緩まない。
                   ;;
                   ;; ノードに外向き HTTPS があるかどうかに依存しない点は tarball
                   ;; と同じ（実測 2026-08-13、simeon/judah とも github/maven/npm
                   ;; へ 200 だが、それを前提にはしない）。token もノードに置かない。
                   ;;
                   ;; 展開は **その場で**行う（dir を消さない）。既に archive 形の
                   ;; 中身が在る dir でも `git init` → `fetch` → `checkout -f` は
                   ;; 同じ tree を書き直すだけなので、並行して読んでいる gate から
                   ;; ファイルが消える瞬間が無い。
                   ;;
                   ;; bundle 経路が失敗したときは **今日と同じ tarball 経路に落ちる**。
                   ;; この変更で「今動いている gate が壊れる」ことを構造的に無くす
                   ;; ため —— 落ちたことは WARN で必ず名指しする（黙って劣化しない）。
                   (let [slug (str (str/replace lib "/" "-") "-" (sha12 sha))
                         bundle (path/join cache-dir (str slug "-dep.bundle"))
                         pin-ref (str "refs/fleet-ci/pin/" sha)
                         remote-bundle (str "/tmp/fleet-ci-dep-" slug ".bundle")
                         ;; bundle は ref を要求する。mirror! の fetch refspec は
                         ;; `+refs/heads/*:refs/heads/*` なので refs/fleet-ci/* は
                         ;; prune されず、object も保持される。
                         bundled?
                         (or (fs/existsSync bundle)
                             (and (zero? (:exit (git m ["update-ref" pin-ref sha])))
                                  (let [{:keys [exit out]}
                                        (git m ["bundle" "create" bundle pin-ref]
                                             {:timeout 900000})]
                                    (or (zero? exit)
                                        (do (log "WARN dep bundle failed for" lib (sha7 sha)
                                                 "—" (str/trim out))
                                            false)))))
                         ok?
                         (when bundled?
                           (let [{:keys [out]}
                                 (sh "bash"
                                     ["-c" (str "cat " bundle
                                                " | ssh -o BatchMode=yes -o ConnectTimeout=20 "
                                                host " \"cat > " remote-bundle
                                                " && mkdir -p " dest
                                                " && git init -q " dest
                                                " && git -C " dest " fetch -q " remote-bundle
                                                " " pin-ref ":" pin-ref
                                                " && git -C " dest " checkout -q -f " sha
                                                " && rm -f " remote-bundle
                                                " && test \\$(git -C " dest
                                                " rev-parse HEAD) = " sha
                                                " && echo FLEET-CI-DEP-OK\"")]
                                     {:timeout 900000})]
                             (or (str/includes? (str out) "FLEET-CI-DEP-OK")
                                 (do (log "WARN dep bundle ship failed for" lib (sha7 sha)
                                          "on" host "— falling back to archive:"
                                          (str/trim (str out)))
                                     false))))]
                     (if ok?
                       (do (log "dep shipped (checkout)" lib (sha7 sha) "->" host) :shipped)
                       ;; ---- fallback: 今日と同じ tarball 経路 ------------------
                       (let [tgz (path/join cache-dir (str slug "-dep.tar.gz"))]
                         (when-not (fs/existsSync tgz)
                           (let [{:keys [exit out]} (git m ["archive" "--format=tar.gz" "-o" tgz sha]
                                                         {:timeout 900000})]
                             (when-not (zero? exit)
                               (die (str "git archive failed for dep " lib ": " (str/trim out))))))
                         (let [{:keys [out]}
                               (sh "bash" ["-c" (str "cat " tgz
                                                     " | ssh -o BatchMode=yes -o ConnectTimeout=20 "
                                                     host " \"mkdir -p " dest " && tar xz -C " dest
                                                     " && test -f " dest
                                                     "/deps.edn && echo FLEET-CI-DEP-OK\"")]
                                   {:timeout 600000})]
                           ;; 同上 — 成功判定も出力の sentinel で行う
                           (if (str/includes? (str out) "FLEET-CI-DEP-OK")
                             (do (log "dep shipped (archive; NOT sha-verifiable)"
                                      lib (sha7 sha) "->" host)
                                 :shipped)
                             (do (log "WARN dep ship failed" lib (sha7 sha) "—"
                                      (str/trim (str out)))
                                 :failed)))))))]
             (when (contains? #{:present :shipped} status)
               (swap! ready assoc ready-k (vec next-deps)))
             (recur (concat (rest queue) next-deps) (conj seen k)
                    (if (= :shipped status) (inc shipped) shipped)))))))))

(defn gate-command
  "ci-verify の --gate に渡す 1 行コマンド。
  ① tarball を ssh stdin でノードへ流して展開（token をノードに置かない）
  ② gate script を ssh stdin で流して実行
  ③ **verdict は出力の sentinel を operator 側で grep して決める**
     （ssh の exit code は Tailscale SSH + /usr/bin/login で必ず 0 になるため）"
  [{:keys [tarball script-file host name sha out-file bundle?]}]
  (let [d (remote-dir name sha)
        ssh-opts "-o BatchMode=yes -o ConnectTimeout=20"
        ;; bundle 経路の受け側。**展開先 dir の中に置く**ので、失敗して残っても
        ;; 次の prune（下の find）と次回の `rm -rf <d>` が確実に回収する。
        ;; tracked ではないので `git ls-files` を汚さない。
        rb (str d "/.fleet-ci-self.bundle")
        place (if bundle?
                ;; :ship-self-bundle — ノード側で **本物の checkout** を作る。
                ;; 手順は ship-git-deps! が依存に対してやっているものと同じ
                ;; （ADR-2608132000）: init → fetch(bundle) → checkout -f → sha 照合。
                ;; sha 照合まで入れるのは、`.git` が在るだけでは「その commit を
                ;; 保持している証拠」にならないため。照合が落ちれば
                ;; FLEET-CI-EXTRACT-OK が出ず、gate は走らずに exit 90 になる。
                (str " cat > " rb
                     " && git init -q " d
                     " && git -C " d " fetch -q " rb " " (self-pin-ref sha) ":" (self-pin-ref sha)
                     " && git -C " d " checkout -q -f " sha
                     " && rm -f " rb
                     " && git -C " d " rev-parse HEAD | grep -qx " sha)
                ;; 既定（今日までと同じ）。tarball は --prefix=repo/ 付きなので strip 1。
                (str " tar xz -C " d " --strip-components=1"))]
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
         " rm -rf " d "; mkdir -p " d ";" place
         " && echo FLEET-CI-EXTRACT-OK\" > " out-file ".extract 2>&1; "
         "grep -q FLEET-CI-EXTRACT-OK " out-file ".extract"
         " || { tail -5 " out-file ".extract; echo '" extract-fail-sentinel " " host "'; exit 90; }; "
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

(defn cleanup-worktree!
  "使い捨て worktree を削除する。linked/sparse checkout の組合せで通常の
  `worktree remove` が `.git is not a .git file` になっても、directory を消した後に
  missing worktree metadata を即時 prune し、prunable entry を残さない。"
  [repo-dir wt]
  (let [removed (git repo-dir ["worktree" "remove" "--force" wt])]
    (sh "rm" ["-rf" wt])
    (when-not (zero? (:exit removed))
      (let [pruned (git repo-dir ["worktree" "prune" "--expire" "now"])]
        (when-not (zero? (:exit pruned))
          (log "WARN worktree cleanup failed" wt "—"
               (str/trim (str (:out removed) " " (:out pruned)))))))
    nil))

(defn prepare-landing-worktree!
  "Create a disposable worktree containing only `target-path`.

  `git worktree add` normally checks out the entire root tree before the
  receipt ledger is touched. On the 4,000-project superproject that takes
  minutes, widening the non-fast-forward race window on every retry. Start
  without a checkout, install an exact non-cone sparse specification, then
  materialize only the ledger path."
  [repo-dir wt ref target-path]
  (let [steps [[repo-dir ["worktree" "add" "--detach" "--no-checkout" "--quiet" wt ref]]
               [wt ["sparse-checkout" "init" "--no-cone"]]
               [wt ["sparse-checkout" "set" "--no-cone" target-path]]
               [wt ["read-tree" "-mu" "HEAD"]]]]
    (loop [[[dir args] & more] steps]
      (if-not dir
        {:ok true}
        (let [r (git dir args)]
          (if (zero? (:exit r))
            (recur more)
            {:ok false
             :detail (str "landing worktree preparation failed at "
                          (str/join " " args) ": " (str/trim (:out r)))}))))))

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
          (let [prepared (prepare-landing-worktree! d wt (str "origin/" branch) path)]
            (if-not (:ok prepared)
              prepared
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
        (cleanup-worktree! d wt)))))

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
;; commit status 書き戻しは **存在しない**（2026-07-26 撤回、上記 C）。
;; この見出しは実装が消えた後も 22 日残り、空のまま「ここに在るはず」と読ませた。
;; 反映面は次節の Radicle issue が唯一である。


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

(defn- advance-pin-once!
  "1 回ぶんの pin 前進。west.yml を読み直すところから始まるので、呼び直せば
  そのまま再試行になる（stale な base に対して PUT し続けることがない）。"
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

(defn advance-pin!
  "west.yml の当該 entry の revision を new-sha に進める。検証は
  scripts/verify-west-pins.cljs（存在 + default branch 到達性 + 前進）に委譲。

  **push race は再試行する。** landing repo の ref 更新そのものが楽観ロックなので、
  fleet が忙しいときは別セッションに負けて `push rejected` が返る。receipt landing
  は最初からこれを 3 回まで再試行していたが（`land-receipt!`）、pin 前進は 1 回で
  諦めていた —— 同じ race を、片方だけが吸収していた。

  症状は静かで、しかも green のときにしか起きない: gate は通り、署名 receipt は
  着地し、pin だけが取り残される。実測 2026-08-17、3 本走らせて 2 本
  （cloud-itonami-isco-4311 と tehai）がこれで置き去りになり、手で進めた。
  『赤い gate』としては現れないので、誰も気付かない。

  再試行は毎回 `advance-pin-once!` を呼び直す = west.yml を読み直すので、
  stale な base に対して PUT を繰り返すことはない。"
  [landing nm new-sha]
  (loop [attempt 1]
    (let [r (advance-pin-once! landing nm new-sha)]
      (cond
        (:ok r) r
        ;; 負けたのが race のときだけ再試行する。pin verification の拒否や
        ;; entry 不在は、何度やっても同じ答えなので即返す。
        (and (< attempt 3)
             (str/includes? (str (:detail r)) "push rejected"))
        (do (log "WARN CD pin-advance retry" attempt nm (:detail r))
            (recur (inc attempt)))
        :else r))))

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
        ;; `repo-name-of`, NOT (:name r) — this map is keyed by the same string the
        ;; lookup below builds, and on 2026-08-18 the two disagreed. The consumer was
        ;; fixed to use the west path and this producer was not, so the map held
        ;; `cloud-itonami/cloud-itonami-gftd-audio-actor` (a repo that does not exist,
        ;; value nil) while the lookup asked for `cloud-itonami/gftd-audio-actor` and
        ;; got nil for the different reason of the key being absent. Same symptom —
        ;; "no tip resolved" — so fixing half of it changed nothing observable.
        tip-of (let [m (into {} (for [org-repo (distinct
                                                (keep #(org-repo-of west %) repos))]
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
                              org-repo (org-repo-of west r)
                              tip (tip-of org-repo)
                              pin (get-in west [:projects nm :revision])
                              last-sha (get-in @state [:repos id :sha])
                              spec (gate-spec-hash r)
                              last-spec (get-in @state [:repos id :spec-hash])]]
                    (let [w (assoc r :org org :org-repo org-repo :tip tip :pin pin
                                   :west-path (west-path west nm)
                                   :id id
                                   :last-sha last-sha
                                   :spec-hash spec :last-spec last-spec)]
                      (assoc w :changed? (work-changed? w)))))
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
                   ;; `repo-name-of`, not (:name r) — same reason as the tip branch above:
                   ;; a west project's name is not always its GitHub repo name.
                   :let [org (or (:org r) (org-of west (:name r)))
                         org-repo (org-repo-of west r)]
                   :when org-repo
                   {:keys [number head]} (take pr-cap (get prs-by-repo org-repo))
                   :let [id (str (gate-id r) "#pr" number)
                         last-sha (get-in @state [:repos id :sha])
                         spec (gate-spec-hash r)
                         last-spec (get-in @state [:repos id :spec-hash])]]
               (let [w (assoc r :org org :org-repo org-repo :tip head :pin nil
                              :west-path (west-path west (:name r))
                              :id id :pr number :cd false
                              :last-sha last-sha
                              :spec-hash spec :last-spec last-spec)]
                 (assoc w :changed? (work-changed? w)))))
        work (into work pr-work)
        missing (filter #(nil? (:tip %)) work)
        todo (cond
               (:all opts) (remove #(nil? (:tip %)) work)
               only (remove #(nil? (:tip %)) work)
               :else (filter :changed? work))]
    (doseq [m missing] (log "WARN no tip resolved (skipped):" (:name m) (:org-repo m)))
    ;; spec-hash の backfill（一度きり）。既に判定を持っている entry に現在の
    ;; 宣言 hash を書き込むだけで、**何も回さない**。これをやらないと
    ;; work-changed? の nil ガードが永久に効いたままになり、新しい trigger が
    ;; 一度も発火しない。dry-run / plan では state を触らない。
    (when-not (or dry? plan?)
      (let [backfill (for [w work
                           :when (and (nil? (:last-spec w))
                                      (get-in @state [:repos (:id w) :sha]))]
                       w)]
        (when (seq backfill)
          (doseq [w backfill]
            (swap! state assoc-in [:repos (:id w) :spec-hash] (:spec-hash w)))
          (save-state!)
          (log "spec-hash backfilled for" (count backfill)
               "existing state entries (no gate was re-run for this)"))))
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
            git-deps-ready (atom {})
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
                                         (ship-git-deps! (get-in w [:node :host])
                                                         dtxt git-deps-ready))))
                                 body (when (:script w)
                                        (str (fs/readFileSync (path/join here (:script w)) "utf8")))
                                 sfile (path/join tmp (str "gate-" (:id w) ".bash-stdin"))
                                 ;; `:nbb-test` + `:ship-git-deps` … the deps are on the
                                 ;; node under ~/.gitlibs by now, so point the classpath at
                                 ;; them. Without this the entry sees only its own tree and
                                 ;; a sibling require is `Could not find namespace`.
                                 w' (if (and (= :nbb-test (:gate w)) (:ship-git-deps w))
                                      (let [m (mirror! (:org-repo w))
                                            dtxt (git-show m (:tip w) "deps.edn")]
                                        (assoc w :classpath
                                               (nbb-deps-classpath (or (:classpath w) "src:test")
                                                                   (or dtxt ""))))
                                      w)
                                 _ (fs/writeFileSync sfile (gate-script (assoc w' :name (:id w))
                                                           (:node w) (:tip w) body))
                                 gname (str "test-" (:id w) "-" (sha7 (:tip w))
                                            "-murakumo-" (get-in w [:node :host]))]
                             (assoc w :tarball tgz :script-file sfile :gate-name gname
                                    :cmd (gate-command {:tarball tgz :script-file sfile
                                                        :host (get-in w [:node :host])
                                                        :name (:id w) :sha (:tip w)
                                                        ;; 履歴を読む gate だけが true。
                                                        ;; 既定は今日までと同じ tarball 経路。
                                                        :bundle? (boolean (:ship-self-bundle w))
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
                                    (:detail (first (filter #(= k (:name %)) checks))))
                              unreachable? (unreachable-outcome? det)]
                          (swap! results conj (assoc w :outcome (if unreachable? :unreachable oc)
                                                     :cid (:cid receipt) :detail det))
                          (when unreachable?
                            (log "UNREACHABLE" (:id w) (sha7 (:tip w))
                                 "— the tree never reached the node; not recorded as a"
                                 "judgement of this sha, so the next tick retries"))
                          ;; receipt が無い pass を state に保存すると次の tick が
                          ;; same tip を skip し、証跡が永久に欠ける。
                          ;;
                          ;; unreachable も同じ理由で保存しない —— こちらは逆向きの
                          ;; 事故で、保存すると次の tick が same tip を skip し、
                          ;; **ssh の一過性断で付いた赤が次の commit まで残る**。
                          (when (and (not dry?) landed? (not unreachable?))
                            (swap! state assoc-in [:repos (:id w)]
                                   {:sha (:tip w) :spec-hash (:spec-hash w)
                                    :outcome oc :cid (:cid receipt) :at (now)})
                            (save-state!))))))))))
        ;; ---- Radicle: 落ちたものだけ issue を開く
        (let [rc (:rad cfg)
              ;; `:unreachable` is excluded on purpose: the repository has no
              ;; defect to file, and an issue that says "ssh timed out" reaches
              ;; the wrong reader entirely.
              failed (filter #(not (contains? #{:pass :unreachable} (:outcome %))) @results)]
          (cond
            (not (:enabled rc)) nil
            (or dry? (:no-rad opts))
            (when (seq failed) (log "rad skipped (--no-rad/--dry-run):"
                                    (pr-str (mapv :name failed))))
            (empty? failed) nil
            :else
            (let [rids (rad-rid-map landing)]
              (doseq [w failed]
                ;; rad-rids は west の `path:`（orgs/<org>/<repo>）を key にする。
                ;; `orgs/<org>/<project-name>` で引いていたので、name != repo-path の
                ;; project は RID が登録されていても見つからず、issue が開かなかった。
                ;; 実測 2026-08-18: そういう project は 59、うち **36 が path key で
                ;; だけ RID を持つ** = 反映面が登録済みで到達不能だった。
                (if-let [rid (get rids (or (:west-path w)
                                           (str "orgs/" (:org w) "/" (:name w))))]
                  (rad-issue-for-failure! rc rid w)
                  (log "rad: no RID registered for" (str (:org w) "/" (:name w))
                       "— skipped (register it in repos.edn rad-rids first)"))))))
        ;; ---- CD: green かつ pin が遅れているものを前進
        (let [cd? (and (get-in cfg [:cd :pin-advance-on-green])
                       (not (:no-cd opts)) (not dry?))
              ;; **A repo's pin advances on the repo's checks, not on one of
              ;; them.** `:cd` is written per gate, and gates.edn's policy note
              ;; says the opt-in is per *repo* -- but the filter below used to
              ;; read one result at a time, so a repo with `:jvm-test :cd true`
              ;; and a second gate at `:cd false` advanced its pin on the JVM
              ;; green while the second gate was failing at the same sha.
              ;;
              ;; Not hypothetical. Measured 2026-08-18 in manifest/fleet-ci.edn:
              ;; org-apache-arrow at 0be7587 carries
              ;; `:gate/test-org-apache-arrow-0be7587-...  :outcome :pass` and
              ;; `:gate/test-nbb-cross-runtime-arrow-0be7587-... :outcome :fail`
              ;; on the same sha, and the passing one is the `:cd true` entry.
              ;; org-apache-parquet at 8245502 is the same shape.
              ;;
              ;; This only sees gates that RAN in this tick -- a gate skipped
              ;; because its tip did not move cannot vote. So it is a floor,
              ;; not a proof: it stops the case where both gates ran and
              ;; disagreed, which is the case that was actually happening.
              not-green (into #{} (comp (remove #(= :pass (:outcome %)))
                                        (map :name))
                              @results)
              candidates (filter #(and (= :pass (:outcome %)) (:cd %)
                                       (not= (:tip %) (:pin %))) @results)
              held (filterv #(not-green (:name %)) candidates)
              ;; One advance per REPO, not per gate. Seven repos here carry
              ;; `:cd true` on both of their gates; without this the second
              ;; call asks to move the pin from tip to the same tip, which
              ;; verify-west-pins correctly refuses as not-a-forward-move --
              ;; and it is logged as `CD pin-advance ... FAILED` right after
              ;; the advance that succeeded.
              green (->> candidates
                         (remove #(not-green (:name %)))
                         (group-by :name)
                         vals
                         (map first))]
          ;; Say it out loud. A pin that was held back and a pin that was never
          ;; a candidate look identical in a log that only prints advances.
          (doseq [w held]
            (log "CD hold" (:name w) "— another gate for this repo did not pass at"
                 (sha7 (:tip w))))
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
(when-not (= "1" (.. js/process -env -FLEET_CI_LIBRARY_MODE))
  (acquire-lock!)
  (-main))

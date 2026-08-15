#!/usr/bin/env nbb
;; verify-west-pins.cljs — west.yml の pin 変更をサーバ側(GitHub API)で検証する。
;;
;; baseline と candidate の west.yml を比較し、revision が変わった / 新規の entry ごとに:
;;   1. 存在+到達性: pin が上流 repo の default branch から到達可能であること
;;      (compare <default>...<pin> の status が identical|behind)。
;;      → 未 push のローカル commit を pin 化する事故(ADR-2607022900 の 44件)と、
;;        rewrite されうる未 merge branch 上の commit を pin 化する事故
;;        (net-kotobase の kotoba b214b15)を両方防ぐ。
;;   2. 前進: 旧 pin が上流に存在するなら compare <旧>...<新> が ahead であること
;;      (behind = 静かな pin 退行 / diverged = 系統違い。どちらも fail)。
;;      旧 pin 自体が上流に無い場合(壊れた pin の修復時)は WARN で通す。
;;
;; 判定はすべて GitHub API(サーバ側 full 履歴)。ローカルの ancestry 判定だけに
;; 頼らない(CLAUDE.md「マージ / ancestry 判定」節と同じ理由)。
;;
;; 使い方:
;;   nbb scripts/verify-west-pins.cljs                        ; baseline=origin/main(無ければ HEAD), candidate=working tree
;;   nbb scripts/verify-west-pins.cljs --baseline <ref|file> --candidate <ref|file> [--dir <repo>] [--only <name>]
;;     <ref|file>: 実在ファイルはそのまま読む。それ以外は git show <ref>:manifest/west.yml
;;                 (":" を含む値は git show <値> をそのまま実行)。
;;     --only <name>: 検証対象をその entry 名に絞る(繰り返し/カンマ区切り可)。
;;                    gen-west-manifest.cljs --entry の最小 diff ワークフロー用 —
;;                    ローカル west.yml が main と乖離した checkout(WIP branch 等)でも
;;                    無関係 entry の大量 API 検証で timeout しない。
;;
;; exit 0: 変更 pin なし / 全 pin 検証 OK(検証不能 entry は WARN で fail-open)
;; exit 1: 検証 FAIL あり(理由を列挙)
;; exit 2: baseline/candidate が解決できない
;; exit 3: gh が無い / gh 認証が無い(呼び出し側の判断でフェイルオープン可)
;;
;; 環境変数 WEST_PIN_VERIFY_SKIP=1 で無条件 skip(緊急用)。

;; babashka.process/clojure.java.io are unavailable under nbb (ClojureScript-
;; on-Node) -- scripts.nbb-compat provides `sh`/`file` with the same shape,
;; aliased as `io` too so the existing `io/file` call-sites below keep
;; working unchanged. (A local `sh` wrapper is defined below, so `sh` itself
;; is deliberately NOT :refer'd here -- it's called as `io/sh`.)
(require '[scripts.nbb-compat :as io :refer [slurp spit-append file-seq format]]
         '[clojure.string :as str])

(when (= "1" (scripts.nbb-compat/getenv "WEST_PIN_VERIFY_SKIP"))
  (println "WEST_PIN_VERIFY_SKIP=1 — pin verification skipped.")
  (scripts.nbb-compat/exit 0))

(defn- parse-args [args]
  (loop [args args opts {:dir "."}]
    (if-let [[k & more] (seq args)]
      (case k
        "--baseline"  (recur (rest more) (assoc opts :baseline (first more)))
        "--candidate" (recur (rest more) (assoc opts :candidate (first more)))
        "--dir"       (recur (rest more) (assoc opts :dir (first more)))
        "--only"      (recur (rest more)
                             (update opts :only (fnil into #{})
                                     (->> (str/split (or (first more) "") #",")
                                          (map str/trim) (remove str/blank?))))
        (recur more opts))
      opts)))

(def opts (parse-args *command-line-args*))

(defn- sh [& args]
  (try (let [{:keys [exit out err]} (apply io/sh args)]
         {:exit exit :out (str/trim (or out "")) :err (str/trim (or err ""))})
       (catch :default e {:exit -1 :out "" :err (str e)})))

(defn- git [& args] (apply sh "git" "-C" (:dir opts) args))

;; --- gh 前提チェック --------------------------------------------------------
(when (not= 0 (:exit (sh "gh" "--version")))
  (binding [*out* *err*] (println "verify-west-pins: gh が見つかりません。検証できません。"))
  (scripts.nbb-compat/exit 3))
(when (and (not (scripts.nbb-compat/getenv "GH_TOKEN")) (not (scripts.nbb-compat/getenv "GITHUB_TOKEN"))
           (not= 0 (:exit (sh "gh" "auth" "status"))))
  (binding [*out* *err*] (println "verify-west-pins: gh 認証がありません。検証できません。"))
  (scripts.nbb-compat/exit 3))

;; --- west.yml の読み込み ----------------------------------------------------
(defn- resolve-content
  "実在ファイルならその中身、':'を含めば git show <spec>、それ以外は git show <ref>:manifest/west.yml。"
  [spec]
  (cond
    (nil? spec) nil
    (.exists (io/file spec)) (slurp spec)
    (str/includes? spec ":") (let [{:keys [exit out]} (git "show" spec)]
                               (when (zero? exit) out))
    :else (let [{:keys [exit out]} (git "show" (str spec ":manifest/west.yml"))]
            (when (zero? exit) out))))

(def baseline-content
  (or (resolve-content (:baseline opts))
      (when-not (:baseline opts)
        (or (resolve-content "origin/main") (resolve-content "HEAD")))))

(def candidate-content
  (or (resolve-content (:candidate opts))
      (when-not (:candidate opts)
        (let [f (io/file (:dir opts) "manifest/west.yml")]
          (when (.exists f) (slurp f))))))

(when-not baseline-content
  (binding [*out* *err*] (println "verify-west-pins: baseline を解決できません(--baseline を指定してください)。"))
  (scripts.nbb-compat/exit 2))
(when-not candidate-content
  (binding [*out* *err*] (println "verify-west-pins: candidate を解決できません(--candidate を指定してください)。"))
  (scripts.nbb-compat/exit 2))

(defn- parse-west
  "west.yml → {:remotes {name url-base}, :entries {name {...}}, :project-names [name ...]}

   remotes と projects は同じインデントの `- name:` を使うのでセクションで区別する。

   `:project-names` は出現順の**生のベクタ**で、重複を潰さない。`:entries` は name を
   キーにした map なので、同じ name が2回現れても静かに1件に畳まれ、重複が見えない
   ——それが `duplicate-names` を別に持つ理由。"
  [content]
  (let [remotes (atom {}) entries (atom {}) current (atom nil) rname (atom nil)
        section (atom nil) order (atom [])]
    (doseq [line (str/split-lines content)]
      (cond
        (re-find #"^  remotes:\s*$" line)  (reset! section :remotes)
        (re-find #"^  projects:\s*$" line) (reset! section :projects)
        (re-find #"^  \S" line)            (reset! section nil))   ; defaults:/self: 等で節を抜ける
      (case @section
        :remotes
        (do
          (when-let [n (second (re-find #"^    - name:\s*(\S+)" line))]
            (reset! rname n))
          (when-let [u (second (re-find #"^      url-base:\s*(\S+)" line))]
            (when @rname (swap! remotes assoc @rname u))))
        :projects
        (do
          (when-let [n (second (re-find #"^    - name:\s*(\S+)\s*$" line))]
            (when-let [c @current] (swap! entries assoc (:name c) c))
            (swap! order conj n)
            (reset! current {:name n}))
          (when @current
            (when-let [v (second (re-find #"^      remote:\s*(\S+)" line))]
              (swap! current assoc :remote v))
            (when-let [v (second (re-find #"^      repo-path:\s*(\S+)" line))]
              (swap! current assoc :repo-path v))
            (when-let [v (second (re-find #"^      revision:\s*(\S+)" line))]
              (swap! current assoc :revision v))
            (when-let [v (second (re-find #"^      path:\s*(\S+)" line))]
              (swap! current assoc :path v))))
        nil))
    (when-let [c @current] (swap! entries assoc (:name c) c))
    {:remotes @remotes :entries @entries :project-names @order}))

(def baseline (parse-west baseline-content))
(def candidate (parse-west candidate-content))

;; --- 重複 name 検査 ---------------------------------------------------------
;; west は同名 project を2つ持つ manifest を **読み込めない**
;; ("Malformed manifest file ... project name X used twice")。pin が不正な west.yml は
;; 誤った checkout を生むが、name が重複した west.yml は fleet 全体で `west` コマンド
;; そのものを止める。したがって pin 検証より前に、無条件で見る。
;;
;; この検査は 2026-07-30 の実事故で追加した。API single-entry 経路で
;; org-microsoft-riff / org-xiph-flac を登録した際、**別セッションが同じ2件を既に
;; 登録済み**だったことに気づかず重複を作り、west.yml が parse 不能になった。
;; PreToolUse hook は正しく発火して本 script を呼んだのに素通りした。理由は2つあり、
;; 両方ここで塞いでいる:
;;   1. `parse-west` が entries を name キーの map に集めるので、重複が静かに畳まれて
;;      そもそも観測できなかった（→ :project-names を生のまま持つようにした）。
;;   2. 追加した entry の revision が既存と同一だったため `changed` が空になり、
;;      下の early-exit が「変更された pin はありません。OK.」で exit 0 していた
;;      （→ この検査を changed の計算より前に置いた）。
(def duplicate-names
  (->> (:project-names candidate)
       frequencies
       (filter (fn [[_ n]] (> n 1)))
       (map first)
       sort
       vec))

(when (seq duplicate-names)
  (println (str "FAIL 重複 project name: " (str/join ", " duplicate-names)))
  (println (str "  west はこの manifest を読み込めません"
                "(Malformed manifest file: project name ... used twice)。"))
  (println (str "  既に登録済みでないか、**書き込み先の tip** を見て確認してください"
                "(ローカルの west.yml は遅れていることがあります)。"))
  (println "verify-west-pins: 1 件の検証に失敗しました。west.yml を main に載せる前に修正してください。")
  (scripts.nbb-compat/exit 1))

(defn- owner-of [url-base]
  (second (re-find #"github\.com[:/]([^/\s]+)/?$" (or url-base ""))))

(defn- gh-repo
  "entry → \"owner/repo\"。owner は candidate の remotes(url-base)から。"
  [{:keys [name remote repo-path]}]
  (when-let [owner (owner-of (get (:remotes candidate) remote))]
    (str owner "/" (or repo-path name))))

;; --- 変更検出 ---------------------------------------------------------------
(def changed
  (let [all (->> (vals (:entries candidate))
                 (keep (fn [{:keys [name revision] :as e}]
                         (let [old (get-in baseline [:entries name :revision])]
                           (when (and revision (not= revision old))
                             (assoc e :old old)))))
                 (sort-by :name))]
    (if-let [only (not-empty (:only opts))]
      (let [scoped (filterv #(contains? only (:name %)) all)
            dropped (- (count all) (count scoped))]
        (when (pos? dropped)
          (println (str "verify-west-pins: --only " (str/join "," (sort only))
                        " — 対象外の変更 pin " dropped " 件は検証しません(呼び出し側が書き込む entry のみ検証)。")))
        scoped)
      all)))

(when (empty? changed)
  (println "verify-west-pins: 変更された pin はありません。OK.")
  (scripts.nbb-compat/exit 0))

;; --- API 検証 ---------------------------------------------------------------
(defn- gh-api [& args] (apply sh "gh" "api" args))

(def default-branch-cache (atom {}))
(defn- default-branch [repo]
  (if (contains? @default-branch-cache repo)
    (get @default-branch-cache repo)
    (let [{:keys [exit out]} (gh-api (str "repos/" repo) "--jq" ".default_branch")
          v (when (zero? exit) out)]
      (swap! default-branch-cache assoc repo v)
      v)))

(defn- compare-status
  "compare base...head → status 文字列 / :missing(404/422) / nil(その他失敗)"
  [repo base head]
  (let [{:keys [exit out err]} (gh-api (str "repos/" repo "/compare/" base "..." head)
                                       "--jq" ".status")]
    (cond
      (zero? exit) out
      (re-find #"HTTP (404|422)" (str err)) :missing
      :else nil)))

(defn- commit-exists?
  "その sha 自体が上流に在るか。true / false(確実に無い) / nil(**答えられない**)。

  **compare の 404 と、commit が無いことは別の問い**である——前者は merge 直後などに
  一時的に起きる（実測 2026-08-15）。

  ⚠ **status code の割り当てを推測しない。**実測 2026-08-15 の GitHub の応答:
    commit が無い  → **HTTP 422** `No commit found for SHA: ...`
    repo が無い    → **HTTP 404** `Not Found`
  最初この二つを逆に書いており、**存在しない sha に対して nil(分からない)、
  存在しない repo に対して false(commit は無い)** を返していた。
  したがって 404 は『commit が無い』の証拠にならない——repo に届いていないだけである。"
  [repo sha]
  (let [{:keys [exit err]} (gh-api (str "repos/" repo "/commits/" sha) "--jq" ".sha")]
    (cond
      (zero? exit) true
      (re-find #"No commit found for SHA" (str err)) false
      :else nil)))

(def failures (atom []))
(def warnings (atom []))

(doseq [{:keys [name revision old] :as e} changed]
  (let [repo (gh-repo e)
        s8   #(when % (subs % 0 (min 12 (count %))))]
    (cond
      (nil? repo)
      (swap! warnings conj (str "WARN " name ": remote/url-base から owner を解決できず検証不能(素通し)"))

      :else
      (let [db (default-branch repo)]
        (if (nil? db)
          ;; repo メタが読めない(private + 権限不足 等) → 検証不能。fail-open で WARN。
          (swap! warnings conj (str "WARN " name " (" repo "): repo が参照できず検証不能(素通し)"))
          (let [reach (compare-status repo db revision)]
            (cond
              (= reach :missing)
              (swap! failures conj
                     (str "FAIL " name " (" repo "): pin " (s8 revision)
                          " が上流に存在しません。先に子リポを push してください(未 push HEAD の pin 化は禁止)。"))

              (nil? reach)
              (swap! warnings conj (str "WARN " name " (" repo "): compare API 失敗、検証不能(素通し)"))

              (not (contains? #{"identical" "behind"} reach))
              (swap! failures conj
                     (str "FAIL " name " (" repo "): pin " (s8 revision) " は存在しますが default branch("
                          db ")から到達できません(status=" reach ")。"
                          "rewrite されうる未 merge branch 上の commit は pin 化禁止。main に merge してから pin してください。"))

              :else
              ;; 到達性 OK → 前進検証(旧 pin がある場合のみ)
              (if (nil? old)
                (println (str "OK   " name " (" repo "): 新規 entry, pin " (s8 revision) " は " db " から到達可能"))
                (let [fwd (compare-status repo old revision)]
                  (cond
                    ;; **compare の 404 を「旧 pin が消えた」と即断しない。**
                    ;; 実測 2026-08-15: merge 直後の head に対する compare が一時的に 404 を返し、
                    ;; この分岐が前進検証を skip したうえ **OK と印字した**（数分後に同じ compare は
                    ;; 正常に ahead を返した）。**答えられなかったことが、合格と同じ顔をしていた。**
                    ;; 旧 sha 自体の存在を問い直し、実在するなら「消失」ではなく「検証不能」に落とす。
                    (= fwd :missing)
                    ;; **commit-exists? が nil(問い合わせ失敗)のときも「消失」に倒さない。**
                    ;; 確実な 404 が返ったときだけ消失とみなす。
                    (if-not (false? (commit-exists? repo old))
                      (swap! warnings conj
                             (str "WARN " name " (" repo "): 旧 pin " (s8 old)
                                  " の消失を確認できないのに compare が 404。**前進検証できていません**"
                                  "(一時的な失敗の可能性。再実行して確認すること)"))
                      (do (swap! warnings conj
                                 (str "WARN " name " (" repo "): 旧 pin " (s8 old)
                                      " が上流に存在しない(壊れた pin の修復とみなし前進検証を skip)"))
                          (println (str "OK   " name " (" repo "): " (s8 old) "→" (s8 revision) " (旧 pin 消失からの修復)"))))

                    (= fwd "ahead")
                    (println (str "OK   " name " (" repo "): " (s8 old) "→" (s8 revision) " (fast-forward)"))

                    (nil? fwd)
                    (swap! warnings conj (str "WARN " name " (" repo "): 前進検証の compare API 失敗(素通し)"))

                    :else
                    (swap! failures conj
                           (str "FAIL " name " (" repo "): " (s8 old) "→" (s8 revision)
                                " は前進ではありません(status=" fwd ")。"
                                (if (= fwd "behind")
                                  "pin 退行です — ローカル子リポが遅れたまま再生成していませんか(git pull + west update してから再生成)。"
                                  "系統が分岐しています — pin の見直しが必要です。")))))))))))))

(doseq [w @warnings] (binding [*out* *err*] (println w)))

;; CI 実行時(GitHub Actions)だけ、WARN を stderr ログの奥に埋もれさせず表に出す:
;; ::warning:: アノテーション(Checks/Files タブに出る、ログを開かなくても見える)と
;; $GITHUB_STEP_SUMMARY(実行のサマリページに残る)。green run でも見落とされない
;; ようにする — 検証不能 = fail-open で通す設計自体は変えない(private org の子リポは
;; github.token では見えず、これは token 権限の問題であって pin 自体は無罪の可能性が
;; 高い。厳密化するには WEST_PIN_VERIFY_TOKEN secret。ワークフロー先頭のコメント参照)。
(when (and (seq @warnings) (= "true" (scripts.nbb-compat/getenv "GITHUB_ACTIONS")))
  (doseq [w @warnings]
    (println (str "::warning::" w)))
  (when-let [summary-path (scripts.nbb-compat/getenv "GITHUB_STEP_SUMMARY")]
    (try
      (spit-append summary-path
            (str "\n### ⚠️ west-pin-verify: " (count @warnings)
                 " 件が検証不能で素通し(fail-open)\n\n"
                 "private org(gftdcojp / com-junkawasaki)の子リポは `github.token` では"
                 "見えず、pin 自体の正否ではなく **権限不足で検証できなかった** だけの可能性が"
                 "高い — `WEST_PIN_VERIFY_TOKEN` secret(org read 権限の PAT)を設定すると"
                 "厳密化できる。\n\n"
                 (str/join "\n" (map #(str "- " %) @warnings))
                 "\n"))
      (catch :default _ nil))))

(if (seq @failures)
  (do (binding [*out* *err*]
        (println)
        (doseq [f @failures] (println f))
        (println (str "\nverify-west-pins: " (count @failures) " 件の pin が検証に失敗しました。west.yml を main に載せる前に修正してください。")))
      (scripts.nbb-compat/exit 1))
  (do (println (str "verify-west-pins: " (count changed) " 件の pin 変更をすべて検証 OK"
                    (when (seq @warnings) (str "(WARN " (count @warnings) " 件は検証不能で素通し)"))))
      (scripts.nbb-compat/exit 0)))

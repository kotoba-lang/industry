#!/usr/bin/env bb
;; verify-west-pins.bb — west.yml の pin 変更をサーバ側(GitHub API)で検証する。
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
;; 判定はすべて GitHub API(サーバ側 full 履歴)。ローカル shallow の ancestry は
;; 一切信用しない(CLAUDE.md「マージ / ancestry 判定」節と同じ理由)。
;;
;; 使い方:
;;   bb scripts/verify-west-pins.bb                        ; baseline=origin/main(無ければ HEAD), candidate=working tree
;;   bb scripts/verify-west-pins.bb --baseline <ref|file> --candidate <ref|file> [--dir <repo>]
;;     <ref|file>: 実在ファイルはそのまま読む。それ以外は git show <ref>:manifest/west.yml
;;                 (":" を含む値は git show <値> をそのまま実行)。
;;
;; exit 0: 変更 pin なし / 全 pin 検証 OK(検証不能 entry は WARN で fail-open)
;; exit 1: 検証 FAIL あり(理由を列挙)
;; exit 2: baseline/candidate が解決できない
;; exit 3: gh が無い / gh 認証が無い(呼び出し側の判断でフェイルオープン可)
;;
;; 環境変数 WEST_PIN_VERIFY_SKIP=1 で無条件 skip(緊急用)。

(require '[clojure.string :as str]
         '[babashka.process :as p]
         '[clojure.java.io :as io])

(when (= "1" (System/getenv "WEST_PIN_VERIFY_SKIP"))
  (println "WEST_PIN_VERIFY_SKIP=1 — pin verification skipped.")
  (System/exit 0))

(defn- parse-args [args]
  (loop [args args opts {:dir "."}]
    (if-let [[k & more] (seq args)]
      (case k
        "--baseline"  (recur (rest more) (assoc opts :baseline (first more)))
        "--candidate" (recur (rest more) (assoc opts :candidate (first more)))
        "--dir"       (recur (rest more) (assoc opts :dir (first more)))
        (recur more opts))
      opts)))

(def opts (parse-args *command-line-args*))

(defn- sh [& args]
  (try (let [{:keys [exit out err]} (p/sh (mapv str args))]
         {:exit exit :out (str/trim (or out "")) :err (str/trim (or err ""))})
       (catch Throwable e {:exit -1 :out "" :err (str e)})))

(defn- git [& args] (apply sh "git" "-C" (:dir opts) args))

;; --- gh 前提チェック --------------------------------------------------------
(when (not= 0 (:exit (sh "gh" "--version")))
  (binding [*out* *err*] (println "verify-west-pins: gh が見つかりません。検証できません。"))
  (System/exit 3))
(when (and (not (System/getenv "GH_TOKEN")) (not (System/getenv "GITHUB_TOKEN"))
           (not= 0 (:exit (sh "gh" "auth" "status"))))
  (binding [*out* *err*] (println "verify-west-pins: gh 認証がありません。検証できません。"))
  (System/exit 3))

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
  (System/exit 2))
(when-not candidate-content
  (binding [*out* *err*] (println "verify-west-pins: candidate を解決できません(--candidate を指定してください)。"))
  (System/exit 2))

(defn- parse-west
  "west.yml → {:remotes {name url-base}, :entries {name {:remote :repo-path :revision :path}}}
   remotes と projects は同じインデントの `- name:` を使うのでセクションで区別する。"
  [content]
  (let [remotes (atom {}) entries (atom {}) current (atom nil) rname (atom nil)
        section (atom nil)]
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
    {:remotes @remotes :entries @entries}))

(def baseline (parse-west baseline-content))
(def candidate (parse-west candidate-content))

(defn- owner-of [url-base]
  (second (re-find #"github\.com[:/]([^/\s]+)/?$" (or url-base ""))))

(defn- gh-repo
  "entry → \"owner/repo\"。owner は candidate の remotes(url-base)から。"
  [{:keys [name remote repo-path]}]
  (when-let [owner (owner-of (get (:remotes candidate) remote))]
    (str owner "/" (or repo-path name))))

;; --- 変更検出 ---------------------------------------------------------------
(def changed
  (->> (vals (:entries candidate))
       (keep (fn [{:keys [name revision] :as e}]
               (let [old (get-in baseline [:entries name :revision])]
                 (when (and revision (not= revision old))
                   (assoc e :old old)))))
       (sort-by :name)))

(when (empty? changed)
  (println "verify-west-pins: 変更された pin はありません。OK.")
  (System/exit 0))

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
                    (= fwd :missing)
                    (do (swap! warnings conj
                               (str "WARN " name " (" repo "): 旧 pin " (s8 old)
                                    " が上流に存在しない(壊れた pin の修復とみなし前進検証を skip)"))
                        (println (str "OK   " name " (" repo "): " (s8 old) "→" (s8 revision) " (旧 pin 消失からの修復)")))

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
(if (seq @failures)
  (do (binding [*out* *err*]
        (println)
        (doseq [f @failures] (println f))
        (println (str "\nverify-west-pins: " (count @failures) " 件の pin が検証に失敗しました。west.yml を main に載せる前に修正してください。")))
      (System/exit 1))
  (do (println (str "verify-west-pins: " (count changed) " 件の pin 変更をすべて検証 OK"
                    (when (seq @warnings) (str "(WARN " (count @warnings) " 件は検証不能で素通し)"))))
      (System/exit 0)))

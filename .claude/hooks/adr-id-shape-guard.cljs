#!/usr/bin/env nbb
;; PreToolUse(Bash) ガード: `git commit` / `git push` の前に、この変更で
;; **新しく現れた** ADR の `:adr/id` を見て、bare な番号形（`2608190100` /
;; `ADR-2608190100`）なら deny する。
;;
;; ## なぜ prose では足りなかったか
;;
;; 番号だけの id は衝突する ── 並行セッションが同じ日時 prefix で採番するため。
;; 実測: 08-16〜08-19 の 4 日で新規衝突 7 件、それを解消した **1 時間後**に 8 件目、
;; さらに 5 時間で 3 件。
;;
;; 2026-08-19 16:22 JST に CLAUDE.md へ「slug 形 `adr-<番号>-<slug>` にする」を
;; 足した。**その後に作られた 6 ファイルのうち 4 つが bare のままだった。** 既に
;; 走っているセッションは CLAUDE.md を読み直さないので、prose の追加はその日の
;; 書き手には届かない。branch-sync が同じ理由で hook 化されたのと同型
;; （CLAUDE.md「prose instruction だけに頼らず hook で強制する」）。
;;
;; ## 設計上の判断
;;
;;   * **新規の id だけを止める。** HEAD に既に在るファイルは素通しする。
;;     bare な既存 ADR は 353 + 65 件あり、そのどれかを編集しただけで作業が
;;     止まると、直す作業そのものができなくなる。docs-edn-parse-guard と同じ
;;     問いを立てる: 「今日、新しく増えたか」。
;;   * **slug 形かどうかだけを見る。** 衝突しているかは見ない ── 衝突検査は
;;     `scripts/verify-adr-identity.cljs` と fleet gate `root-adr-identity` の
;;     仕事で、ここでそれを再実装すると片方だけ直る状態が生まれる。
;;   * fail-open。判定途中のあらゆる失敗で commit を通す。

(require '[cheshire.core :as json]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[scripts.nbb-compat :as compat])

(defn- target-dir
  "コマンドが実際に触るリポジトリのディレクトリ。cwd から決めてはならない ──
  `cd /tmp/other && git commit` を superproject の状態で判定してしまう。"
  [cmd]
  (or (second (re-find #"\bgit\s+-C\s+(\S+)" cmd))
      (second (re-find #"^\s*cd\s+(\S+)\s*&&" cmd))
      "."))

(defn- sh [dir & args]
  (try
    (let [{:keys [exit out]} (p/sh (into ["git" "-C" dir] (map str args)))]
      (when (zero? exit) out))
    (catch :default _ nil)))

(defn allow! [] (compat/exit 0))

(defn deny! [reason]
  (println (json/generate-string
             {:hookSpecificOutput
              {:hookEventName "PreToolUse"
               :permissionDecision "deny"
               :permissionDecisionReason reason}}))
  (compat/exit 0))

(defn- candidate-adrs
  "この commit に含まれうる 90-docs/adr の .edn。"
  [top]
  (->> [(sh top "diff" "--name-only" "--" "90-docs/adr")
        (sh top "diff" "--cached" "--name-only" "--" "90-docs/adr")
        (sh top "ls-files" "-o" "--exclude-standard" "--" "90-docs/adr")]
       (keep identity)
       (mapcat str/split-lines)
       (map str/trim)
       (filter #(str/ends-with? % ".edn"))
       distinct))

(defn- bare-id
  "TEXT が宣言する bare な `:adr/id`、無ければ nil。

  bare とするのは 2 形だけ: `\"2608190100\"` と `\"ADR-2608190100\"`。slug 形
  `adr-2608190100-...` は通す。数値（文字列でない）id は別の既知の債務
  （171 件、cloud-itonami の生成物）なので、ここでは触らない。"
  [text]
  (some-> (re-find #":adr/id\s+\"((?:ADR-)?\d{10})\"" text) second))

(defn- new-file? [top path]
  (nil? (sh top "cat-file" "-e" (str "HEAD:" path))))

(try
  (let [cmd (or (some-> (compat/read-stdin) (json/parse-string true)
                        (get-in [:tool_input :command])) "")]
    (when-not (re-find #"\bgit\s+(?:-C\s+\S+\s+)?(?:commit|push)\b" cmd)
      (allow!))
    (let [top (target-dir cmd)
          offenders
          (->> (candidate-adrs top)
               (keep (fn [path]
                       (when (new-file? top path)
                         (when-let [text (try (compat/slurp (str top "/" path))
                                              (catch :default _ nil))]
                           (when-let [id (bare-id text)]
                             [path id])))))
               seq)]
      (when offenders
        (deny!
          (str "新しい ADR の :adr/id が bare な番号形です。commit/push を止めました。\n\n"
               (str/join "\n" (for [[path id] offenders]
                                (str "  " path "\n    :adr/id \"" id "\"")))
               "\n\n番号だけの id は衝突します ── 並行セッションが同じ日時 prefix で\n"
               "採番するためで、実測は 4 日で 7 件、直した 1 時間後に 8 件目、さらに\n"
               "5 時間で 3 件です。slug を含めれば同じ番号でも id は分かれ、\n"
               ":adr/related の参照先も一意に決まります（既存 1,362 件がこの形）。\n\n"
               "直し方: :adr/id \"adr-<番号>-<ファイル名の slug 部分>\"\n"
               "確認: nbb --classpath \".:scripts/nbb_compat\" scripts/verify-adr-identity.cljs\n\n"
               "既存の bare な ADR を編集しただけなら止めません ── 止めているのは\n"
               "『今日新しく増えた bare な id』だけです。"))))
    (allow!))
  (catch :default _ (compat/exit 0)))

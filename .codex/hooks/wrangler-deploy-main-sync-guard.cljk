#!/usr/bin/env nbb
;; PreToolUse(Bash) ガード: **本番** Worker デプロイを、origin/main のコミットを
;; 取り込んでいない checkout から実行しようとした時にブロックする。
;;
;; なぜ必要か（実インシデント 2026-07-25）:
;;   kotobase.net の signup funnel が 404 で死んでいたのを直し、AUTHN_URL を
;;   新しい authn.kotobase.net に向けて 07:01 にデプロイした。その11分後、
;;   別セッションが **その変更を含まない古い checkout** から同じ Worker を
;;   デプロイし、funnel を 404 に巻き戻した。誰も気付かないまま本番が退行した。
;;
;;   どちらのセッションも悪くない。Wrangler は「あなたの checkout が main より
;;   古い」ことを知らず、教えてもくれない。デプロイは push と違って
;;   fast-forward 検査を持たない——**最後に実行した人が勝つ**。だから、その
;;   検査を外から足す。
;;
;; 方針:
;;   - origin/main に、この checkout に無いコミットがあれば deny。
;;     （＝ HEAD が main を包含していない ＝ 古い artifact を出荷しようとしている）
;;   - **本番デプロイだけ**を対象にする。`--env <name>`（staging/testnet 等）は
;;     許可する——隔離環境へ feature branch を出すのは正常な作業であり、
;;     そこを塞ぐと検証そのものができなくなる。`--env=""` と env 指定なしが本番。
;;   - `--dry-run` は素通り。
;;   - 破壊的な自動同期はしない（deny + 指示のみ）。
;;   - fail-open: 判定途中のあらゆる失敗でデプロイを許可する（誤ブロック防止）。
;;     ガードが壊れて本番デプロイが全面停止する方が、退行より高くつく。

(require '[cheshire.core :as json]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[scripts.nbb-compat :as compat])

(defn git
  "git -C dir <args...>。成功時は trim した stdout、失敗時は nil。"
  [dir & args]
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

;; パス1トークン: "..." / '...' / スペース・;&| を含まない裸トークン
;; （git-push-main-sync-guard と同じ理由・同じ形。クォート付きパスを取りこぼすと
;;   対象と無関係な repo を見てしまう）。
(def ^:private path-tok-src "\"[^\"]*\"|'[^']*'|[^\\s;&|]+")

(defn- strip-quotes [s]
  (when s
    (if (and (>= (count s) 2)
             (or (and (str/starts-with? s "\"") (str/ends-with? s "\""))
                 (and (str/starts-with? s "'") (str/ends-with? s "'"))))
      (subs s 1 (dec (count s)))
      s)))

(def ^:private deploy-re
  "wrangler の直接呼び出しと、npm/pnpm/yarn の deploy script の両方。
   後者を外すと `pnpm run deploy`（net-kotobase の RUNBOOK が指定している
   本番手順そのもの）がガードを素通りする。`wrangler versions deploy` も
   本番トラフィックを切り替えるので同じ扱い。

   **`pages` を含めるのは 2026-08-03 の実インシデントによる。** それまでこの
   パターンは `wrangler\\s+deploy` にしかマッチせず、`wrangler pages deploy` —
   isekai.network の本番デプロイがまさにこの形 — はガードに評価すらされなかった。
   その日、main より古い依存から作られた bundle が本番に出て全 3D ゲームが
   boot 不能になり、約25分ダウンした。ガードは黙って何もしていなかった。
   Pages は Workers と同じく最後に実行した人が勝つので、同じ検査が要る。
   `deploy-local-root-pin-guard.cljs` の同名パターンと一致させ続けること。"
  #"(?:wrangler\s+(?:pages\s+)?(?:versions\s+)?deploy\b|wrangler\s+pages\s+deployment\s+create\b|(?:npm|pnpm|yarn)\s+(?:run\s+)?deploy\b)")

(def ^:private named-env-re
  "`--env foo` / `--env=foo`（foo が空でないもの）。空文字 `--env=\"\"` /
   `--env ''` は本番なので、ここでは named 扱いしない。"
  #"--env(?:\s+|=)(?!\"\"|''|\s|$)([^\s;&|]+)")

(try
  (let [cmd (or (some-> (compat/read-stdin)
                        (json/parse-string true)
                        (get-in [:tool_input :command]))
                "")]
    (when-not (re-find deploy-re cmd) (allow!))
    (when (str/includes? cmd "--dry-run") (allow!))
    ;; 隔離環境（staging/testnet/b2 …）へのデプロイは許可。
    (when (re-find named-env-re cmd) (allow!))

    (let [cd  (some-> (re-find (re-pattern (str "cd\\s+(" path-tok-src ")")) cmd)
                      second strip-quotes)
          dir (or cd ".")
          top (git dir "rev-parse" "--show-toplevel")]
      (when (str/blank? top) (allow!))

      (let [ref (if (git top "rev-parse" "--verify" "-q" "origin/main")
                  "origin/main"
                  (some-> (git top "symbolic-ref" "-q" "refs/remotes/origin/HEAD")
                          (str/replace #"^refs/remotes/" "")))]
        (when (str/blank? ref) (allow!))
        (git top "fetch" "-q" "origin" (str/replace ref #"^origin/" ""))
        (let [raw    (git top "rev-list" "--count" (str "HEAD.." ref))
              parsed (js/parseInt (or raw "0") 10)
              behind (if (js/isNaN parsed) 0 parsed)]
          (when (pos? behind)
            (deny!
             (compat/format
              (str "%s: この checkout は %s より %d commits 遅れています。"
                   "本番デプロイをブロックしました——古い artifact を出荷すると、"
                   "その間に他のセッションが入れた変更を黙って巻き戻します"
                   "（2026-07-25 に実際に発生: kotobase.net の signup funnel が"
                   "11分で 404 に戻された）。\n\n"
                   "先に同期してください:\n"
                   "  git -C %s fetch origin && git -C %s merge --ff-only %s\n"
                   "FF できない場合は乖離しています——CLAUDE.md の方針に従って"
                   "解消してから再実行してください（rebase はしない）。\n\n"
                   "隔離環境へのデプロイ（--env <name>）と --dry-run は"
                   "ブロックしません。")
              top ref behind top top ref))))))
    (allow!))
  (catch :default _ (compat/exit 0)))

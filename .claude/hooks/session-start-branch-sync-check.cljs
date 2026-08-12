#!/usr/bin/env nbb
;; SessionStart フック: セッション開始のたびに現在のブランチが origin/main
;; (無ければ origin/HEAD の指す既定ブランチ) からどれだけ乖離しているか
;; (ahead/behind コミット数) を確認し、乖離が大きければ警告として提示する。
;;
;; 背景 (2026-07-20): agent/pin-docs-edn-only ブランチが誰も気づかないまま
;; origin/main から 848 commits ahead / 1607 commits behind まで積み上がった
;; 実インシデント。CLAUDE.md 「常に main と同期し、乖離を作らない」は
;; prose instruction で agent が都度思い出す前提だったため、毎セッション
;; 自動で可視化するここに hook 化する。
;;
;; fail-open: 判定途中のあらゆる失敗はセッション開始をブロックしない
;; (何も出力せず exit 0)。ネットワーク不調・非 git ディレクトリ等も同様。

(require '[cheshire.core :as json]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[scripts.nbb-compat :as compat])

;; 乖離がこれを超えたら警告扱い(小さい WIP の日常的な ahead/behind は素通り)。
(def ahead-threshold 30)
(def behind-threshold 30)

(defn git
  [dir & args]
  (try
    (let [{:keys [exit out]} (p/sh (into ["git" "-C" dir] (map str args)))]
      (when (zero? exit) (str/trim out)))
    (catch :default _ nil)))

(defn done!
  ([] (compat/exit 0))
  ([msg]
   (println (json/generate-string
              {:systemMessage msg
               :hookSpecificOutput {:hookEventName "SessionStart"
                                     :additionalContext msg}}))
   (compat/exit 0)))

(defn upstream-ref
  "この checkout の『上流の既定ブランチ』を `<remote>/<branch>` で返す。解決
   できなければ nil。

   **remote は `origin` とは限らない。** west が作る checkout は remote を org 名
   で持つ。実測 2026-08-13、`orgs/` 配下の 4,406 checkout のうち
   **2,824（64%）に `origin` remote が無い**。この hook はセッションの cwd で
   走るので、子リポで開いたセッションでは `origin/main` も
   `refs/remotes/origin/HEAD` も解決せず、**乖離が何 commit あっても
   何も表示しないまま終わっていた**（ahead/behind ゼロと見分けが付かない）。

   remote が複数あるときは URL に `github.com` を含むものを選ぶ。
   `git remote | head -1` はアルファベット順の先頭で、annex repo では `b2` を
   引く（実測 2026-08-13: 該当 15 checkout）。"
  [top]
  (let [remotes (->> (or (git top "remote") "") str/split-lines
                     (map str/trim) (remove str/blank?) vec)
        gh? (fn [r] (some-> (git top "remote" "get-url" r) (str/includes? "github.com")))
        ordered (concat (filter #{"origin"} remotes)
                        (filter gh? (remove #{"origin"} remotes))
                        (remove #{"origin"} remotes))]
    (some (fn [r]
            (or (when (git top "rev-parse" "--verify" "-q" (str r "/main")) (str r "/main"))
                (some-> (git top "symbolic-ref" "-q" (str "refs/remotes/" r "/HEAD"))
                        (str/replace #"^refs/remotes/" ""))))
          ordered)))

(try
  (let [dir  "."
        top  (git dir "rev-parse" "--show-toplevel")]
    (when (str/blank? top) (done!))

    (let [branch (git top "rev-parse" "--abbrev-ref" "HEAD")]
      (when (or (str/blank? branch) (= branch "HEAD")) (done!)) ; detached HEAD は対象外

      ;; 既定ブランチを fetch(full history — shallow は使わない。ADR-2606241600 は
      ;; ADR-2607211600 で reverse 済み: --depth 1 は fetch のたびに新しい graft を作り、
      ;; ここでの merge-base 前提の ahead/behind 判定を誤検出させる原因だった)。
      (let [ref (upstream-ref top)]
        (when (str/blank? ref)
          ;; **黙って終わらない。** ここは「乖離なし」ではなく「測れなかった」。
          ;; stderr は settings.json の登録が `2>/dev/null` で捨てるので、
          ;; 言うなら systemMessage で言うしかない。
          (done! (str "git 乖離チェック: " top
                      " の upstream ref を解決できませんでした（remote: "
                      (or (git top "remote") "なし")
                      "）。**このセッションの ahead/behind は測れていません。**")))
        (let [[remote branch0] (str/split ref #"/" 2)]
          (git top "fetch" "-q" remote branch0))

        (let [raw    (git top "rev-list" "--left-right" "--count" (str ref "..." branch))
              [b a]  (some-> raw (str/split #"\s+"))
              behind (js/parseInt (or b "0") 10)
              ahead  (js/parseInt (or a "0") 10)
              behind (if (js/isNaN behind) 0 behind)
              ahead  (if (js/isNaN ahead) 0 ahead)]
          (if (or (> ahead ahead-threshold) (> behind behind-threshold))
            (done!
              (compat/format
                (str "⚠ git 乖離チェック: ブランチ '%s' が %s から %d ahead / %d behind です。"
                     "CLAUDE.md 「常に main と同期し、乖離を作らない」に従い、"
                     "本格的な作業の前に同期を検討してください"
                     "(848 ahead まで気づかず積み上がった実インシデントあり — "
                     "rebase は禁止、stale なら git-cleanup-conflict skill で処理)。")
                branch ref ahead behind))
            (when (or (pos? ahead) (pos? behind))
              (done!
                (compat/format "git 乖離チェック: '%s' は %s から %d ahead / %d behind(閾値内)。"
                               branch ref ahead behind))))))))
  (catch :default _ nil))
(compat/exit 0)

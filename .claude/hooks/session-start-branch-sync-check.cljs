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

(try
  (let [dir  "."
        top  (git dir "rev-parse" "--show-toplevel")]
    (when (str/blank? top) (done!))

    (let [branch (git top "rev-parse" "--abbrev-ref" "HEAD")]
      (when (or (str/blank? branch) (= branch "HEAD")) (done!)) ; detached HEAD は対象外

      ;; --depth 1 で既定ブランチだけ軽量 fetch(shallow 既定ポリシーに準拠)。
      (git top "fetch" "-q" "--depth" "1" "origin" "main")
      (let [ref (if (git top "rev-parse" "--verify" "-q" "origin/main")
                  "origin/main"
                  (some-> (git top "symbolic-ref" "-q" "refs/remotes/origin/HEAD")
                          (str/replace #"^refs/remotes/" "")))]
        (when (str/blank? ref) (done!))

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

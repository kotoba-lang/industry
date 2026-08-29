#!/usr/bin/env nbb
;; scripts/fleet-refactor-wave-tick.cljs — orgs/cloud-itonami/ 向け refactor 波の
;; 候補を**測る**（ADR-2608290100）。決定論。モデルを起こさない。
;;
;;   nbb --classpath ".:scripts/nbb_compat:orgs/cloud-itonami/loop-fleet-refactor-wave/src" \
;;     scripts/fleet-refactor-wave-tick.cljs [--limit 4] [--mission a|b|both]
;;
;; 姉妹 tick（svelte-cljs-wave-tick.cljs）と同型。手順の正本は skill
;; `fleet-refactor-wave` であって、ここではない（2 箇所に書くと必ず片方が古くなる）。
;;
;; ## 2 mission
;;
;; Mission A（D1 premise 除去、ADR-2608039000）と Mission B（clj/cljc → .kotoba、
;; ADR-2608261100）を同じ tick で扱う。判定基準そのものはここで再定義しない —
;; `loop-fleet-refactor-wave.mission-a` / `.mission-b`（同名 repo の src、classpath
;; で require）の純関数に委譲する（この tick は I/O — find/grep/git — だけを持つ）。
;;
;; ## exit code
;;
;;   0  測れた（候補 0 本でも 0。プールが在って候補が無いのは legitimate な
;;      状態 — 例えば Mission A は 2026-08-29 実測で orgs/cloud-itonami/ 配下
;;      candidate 0 件だった。これは gate ではなく測定器）。
;;   2  **測れなかった** —— orgs/cloud-itonami/ が無い、find が失敗した、
;;      pool（repo 数）が 0。0 と 2 を区別する意味がそこにある（ADR-2608136000）。

(ns fleet-refactor-wave-tick
  (:require [clojure.string :as str]
            [loop-fleet-refactor-wave.mission-a :as mission-a]
            [loop-fleet-refactor-wave.mission-b :as mission-b]))

(def fs (js/require "fs"))
(def cp (js/require "child_process"))
(def os (js/require "os"))
(def path (js/require "path"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-file (str home "/.gftd/fleet-refactor-wave-tick.ledger.edn"))
(def fleet-dir "orgs/cloud-itonami")

(def args (vec *command-line-args*))
(defn- arg [flag default]
  (if-let [i (first (keep-indexed #(when (= %2 flag) %1) args))]
    (nth args (inc i))
    default))
(def limit (js/parseInt (arg "--limit" "4") 10))
(def mission-filter (arg "--mission" "both")) ; "a" | "b" | "both"
;; Mission B: 1 repo あたりこの本数を超える .clj/.cljc 候補は、この機械的な波の
;; 対象にしない（svelte-cljs-wave の max-files と同じ理由 — 完走確率が高い順に消す）。
(def max-line-count 400)

(defn- sh [cmd cmd-args opts]
  (try
    (let [r (.spawnSync cp cmd (clj->js cmd-args)
                        (clj->js (merge {:encoding "utf8" :cwd root :timeout 120000} opts)))]
      {:code (aget r "status") :out (str (aget r "stdout")) :err (str (aget r "stderr"))})
    (catch :default e {:code nil :out "" :err (str e)})))

(defn- read-file [rel]
  (try (str (.readFileSync fs (path.join root rel) "utf8")) (catch :default _ nil)))

(defn- list-repos!
  "orgs/cloud-itonami/ 直下の checked-out repo 名（各 direct child が west
   project の checkout）。走査できなければ nil。"
  []
  (try
    (when (.existsSync fs (path.join root fleet-dir))
      (->> (.readdirSync fs (path.join root fleet-dir) #js {:withFileTypes true})
           (filter #(.isDirectory %))
           (map #(.-name %))
           (remove #(str/starts-with? % "."))          ; stale worktree 掃き出し先の対策
           (remove #(str/includes? % "/.claude/worktrees/"))
           sort
           vec))
    (catch :default _ nil)))

(defn- find! [repo-name globs]
  "その repo 内で glob（拡張子リスト）に一致する相対パスを返す。find(1) を使う。"
  (let [repo-path (path.join fleet-dir repo-name)
        name-args (mapcat (fn [g] ["-o" "-name" g]) globs)
        name-args (vec (rest name-args))                ; 先頭の余分な "-o" を落とす
        find-args (concat [repo-path
                            "(" "-name" "node_modules" "-o" "-name" ".git" "-o" "-name" "dist"
                            "-o" "-name" "build" "-o" "-name" "out" "-o" "-name" "target"
                            "-o" "-name" "test" "-o" "-name" ".shadow-cljs" ")" "-prune" "-o"
                            "(" ] name-args [")" "-type" "f" "-print"])]
    (let [{:keys [code out]} (sh "find" find-args {})]
      (if (zero? (or code -1))
        (->> (str/split-lines out) (remove str/blank?) vec)
        []))))

;; ───────────────────────── Mission A ─────────────────────────

(defn- mission-a-repo-candidate
  "1 repo を Mission A 候補として評価する。判定は mission-a/mission-a-candidate?
   （純関数）に委譲する — ここは README/wrangler/source テキストを集めるだけ。"
  [repo-name]
  (let [readme (or (read-file (path.join fleet-dir repo-name "README.md"))
                    (read-file (path.join fleet-dir repo-name "README.edn")))
        wrangler-paths (find! repo-name ["wrangler.jsonc" "wrangler.toml" "wrangler.json"])
        wrangler-text (apply str (keep read-file wrangler-paths))]
    (when (and (mission-a/decentralization-claim? readme)
               (mission-a/d1-binding? wrangler-text))
      ;; 高い方の 2 条件が揃った時だけ、コストの高い arbiter grep を走らせる。
      (let [{:keys [out]} (sh "sh" ["-c"
                                    (str "grep -rniE "
                                         "'WHERE[[:space:]]+sequence|onlyIf\\.etagMatches|If-Match|head[_-]?db|conditional[_-]?write|cas[_-]?arbiter|ref[_-]?plane' "
                                         (path.join fleet-dir repo-name)
                                         " --include=*.ts --include=*.js --include=*.cljc --include=*.clj --include=*.cljs 2>/dev/null | head -20")]
                            {})]
        (when (mission-a/mission-a-candidate? {:readme-text readme
                                                :wrangler-text wrangler-text
                                                :source-text out})
          {:repo (str "orgs/cloud-itonami/" repo-name)
           :org "cloud-itonami"
           :name repo-name
           :mission :a
           :evidence (str/trim out)})))))

;; ───────────────────────── Mission B ─────────────────────────

(defn- custody-gated-repo? [repo-name]
  (let [scripts (find! repo-name ["*.cljs"])
        ;; docs/ か scripts/ 配下だけを見る（svelte-cljs-wave の実測知見と同じ)
        relevant (filter #(re-find #"/(docs|scripts)/" %) scripts)]
    (boolean (some #(mission-b/custody-gated? (read-file %)) relevant))))

(defn- mission-b-repo-candidates
  "1 repo から Mission B 候補ファイルを 0 本以上返す。"
  [repo-name]
  (when-not (custody-gated-repo? repo-name)
    (let [clj-files (find! repo-name ["*.clj" "*.cljc"])
          kotoba-files (find! repo-name ["*.kotoba"])]
      (->> clj-files
           (keep (fn [f]
                   (let [text (read-file f)
                         line-count (if text (count (str/split-lines text)) 0)
                         has-twin? (mission-b/kotoba-twin-exists? f kotoba-files)
                         host-sig? (mission-b/host-mechanism-signal? text)]
                     (when (mission-b/candidate-slice? {:line-count line-count
                                                         :custody-gated? false
                                                         :has-kotoba-twin? has-twin?
                                                         :host-mechanism? host-sig?})
                       {:repo (str "orgs/cloud-itonami/" repo-name)
                        :org "cloud-itonami"
                        :name repo-name
                        :mission :b
                        :file f
                        :lines line-count}))))
           (sort-by :lines)
           vec))))

;; ───────────────────────── in-flight ─────────────────────────

(defn- remote-name [repo-name]
  (try
    (let [r (.spawnSync cp "git" (clj->js ["-C" (path.join root fleet-dir repo-name) "remote"])
                        #js {:encoding "utf8" :timeout 30000})
          names (->> (str/split-lines (str (aget r "stdout"))) (remove str/blank?) set)]
      (cond (contains? names "cloud-itonami") "cloud-itonami"
            (contains? names "origin") "origin"
            :else (first (sort names))))
    (catch :default _ nil)))

(defn- has-linked-worktree? [repo-name]
  (try
    (let [r (.spawnSync cp "git"
                        (clj->js ["-C" (path.join root fleet-dir repo-name) "worktree" "list" "--porcelain"])
                        #js {:encoding "utf8" :timeout 30000})]
      (if (not= 0 (aget r "status"))
        true
        (> (count (re-seq #"(?m)^worktree " (str (aget r "stdout")))) 1)))
    (catch :default _ true)))

(defn- in-flight [repo-name branch-name]
  (if (has-linked-worktree? repo-name)
    :worktree
    (if-let [rem (remote-name repo-name)]
      (try
        (let [r (.spawnSync cp "git"
                            (clj->js ["-C" (path.join root fleet-dir repo-name)
                                      "ls-remote" "--heads" rem branch-name])
                            #js {:encoding "utf8" :timeout 30000})]
          (cond (not= 0 (aget r "status")) :unmeasured
                (str/blank? (str (aget r "stdout"))) nil
                :else :branch))
        (catch :default _ :unmeasured))
      :unmeasured)))

;; ───────────────────────── main ─────────────────────────

(defn -main []
  (let [repos (list-repos!)]
    (when (nil? repos)
      (println (str "REFUSING\t" fleet-dir " が読めない — 走査していない"))
      (println "SCANNED\t0")
      (js/process.exit 2))
    (when (zero? (count repos))
      (println (str "REFUSING\t" fleet-dir " に repo が 0 件 — walk が壊れている"
                    "（cloud-itonami fleet は 100+ repo を持つはず）"))
      (println "SCANNED\t0")
      (js/process.exit 2))

    (let [want-a (contains? #{"a" "both"} mission-filter)
          want-b (contains? #{"b" "both"} mission-filter)
          a-cands (if want-a (vec (keep mission-a-repo-candidate repos)) [])
          b-cands (if want-b (vec (mapcat mission-b-repo-candidates repos)) [])
          all-raw (->> (concat a-cands b-cands)
                       (sort-by (juxt :mission (fnil :lines 0) :repo))
                       vec)
          ranked (take (* 4 limit) all-raw)
          skipped (atom [])
          unmeasured (atom [])
          picked (->> ranked
                      (remove (fn [c]
                                (let [branch (if (= :a (:mission c))
                                               "agent/d1-premise-fix"
                                               "agent/kotoba-migration")]
                                  (case (in-flight (:name c) branch)
                                    nil false
                                    :unmeasured (do (swap! unmeasured conj (:repo c)) true)
                                    (do (swap! skipped conj (:repo c)) true)))))
                      (take limit)
                      vec)
          rec {:at (.toISOString (js/Date.))
               :pool (count repos)
               :mission-a-raw (count a-cands)
               :mission-b-raw (count b-cands)
               :limit limit
               :in-flight-skipped @skipped
               :unmeasured-skipped @unmeasured
               :candidates picked}]

      (println (str "SCANNED\t" (count repos) "\trepos under " fleet-dir))
      (println (str "POOL\t" (count repos)))
      (println (str "MISSION-A-RAW\t" (count a-cands)))
      (println (str "MISSION-B-RAW\t" (count b-cands)))
      (println (str "IN-FLIGHT-SKIPPED\t" (count @skipped)
                    (when (seq @skipped) (str "\t" (str/join " " @skipped)))))
      (println (str "UNMEASURED-SKIPPED\t" (count @unmeasured)
                    (when (seq @unmeasured) (str "\t" (str/join " " @unmeasured)))))
      (println (str "CANDIDATES\t" (count picked)))
      (doseq [c picked]
        (println (str "  " (name (:mission c)) "\t" (:repo c)
                      (when (:file c) (str "\t" (:file c) "\t" (:lines c) "L")))))
      (try (.appendFileSync fs ledger-file (str (pr-str rec) "\n"))
           (catch :default e (println "ledger 追記失敗:" (str e))))
      (js/process.exit 0))))

(-main)

#!/usr/bin/env nbb
;; scripts/fleet-refactor-wave-tick.cljs — orgs/cloud-itonami/ 向け refactor 波の
;; 候補を**測る**（ADR-2608290100）。決定論。モデルを起こさない。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/fleet-refactor-wave-tick.cljs \
;;     [--limit 4] [--mission a|b|both]
;;
;; 姉妹 tick（svelte-cljs-wave-tick.cljs）と同型。手順の正本は skill
;; `fleet-refactor-wave` であって、ここではない（2 箇所に書くと必ず片方が古くなる）。
;;
;; ## 2 mission
;;
;; Mission A（D1 premise 除去、ADR-2608039000）と Mission B（clj/cljc → .kotoba、
;; ADR-2608261100）を同じ tick で扱う。判定基準そのものはここで再定義しない ——
;; `orgs/cloud-itonami/loop-fleet-refactor-wave` の `src/loop_fleet_refactor_wave/
;; mission_{a,b}.cljc` が正本の実装で、このファイルの判定関数はその
;; **verbatim な写し**。判定ロジックを変えるときは両方を同じ commit で変える。
;;
;; ## なぜ classpath require ではなく複製か
;;
;; 最初は companion repo を classpath に足して require する設計だった。実測
;; 2026-08-29: 新しく west 登録した project は、登録した直後の他マシン/他
;; worktree では **checked out されていない**（`west update` を別途要る）。
;; その状態で require が失敗すると nbb は `-main` に達する前に例外で落ち、
;; 生成される exit code は 1（「測れなかった」の専用 exit 2 ではない）。この
;; tick は svelte-cljs-wave-tick.cljs と同じく自己完結にし、companion repo が
;; 未 checkout でも exit 2 を正しく返せるようにする。
;;
;; ## なぜ 1 回の find で全 repo を走査するか（repo ごとの find/grep にしない）
;;
;; 実測 2026-08-29: `orgs/cloud-itonami/` は 1,857 repo が実在する（west 管理、
;; 共有 checkout）。repo ごとに `find`/`grep` を subprocess spawn すると
;; 1,857 × 数回 = 万単位の spawn になり、この機械（並行 agent が多数走る負荷の
;; 高いワークステーション）では非現実的に遅い。svelte-cljs-wave-tick.cljs の
;; `scan!`（1 回の find で全ファイルを tmp file に落として読む）と同じ形にする。
;;
;; ## exit code
;;
;;   0  測れた（候補 0 本でも 0。プールが在って候補が無いのは legitimate な
;;      状態 — 例えば Mission A は 2026-08-29 実測で orgs/cloud-itonami/ 配下
;;      candidate 0 件だった。これは gate ではなく測定器）。
;;   2  **測れなかった** —— orgs/cloud-itonami/ が無い、find が失敗した、
;;      pool（repo 数）が 0。0 と 2 を区別する意味がそこにある（ADR-2608136000）。

(ns fleet-refactor-wave-tick
  (:require [clojure.string :as str]))

;; ─────────────── Mission A 判定（…mission_a.cljc の写し） ──────────────────

(defn- decentralization-claim? [text]
  (boolean (re-find #"(?i)(decentrali[sz]|blockchain|分散)" (or text ""))))

(defn- d1-binding? [wrangler-text]
  (boolean (re-find #"d1_databases" (or wrangler-text ""))))

(defn- cas-arbiter-signal? [source-text]
  (boolean
   (re-find #"(?i)(WHERE\s+sequence|onlyIf\.etagMatches|If-Match|head[_-]?db|conditional[_-]?write|cas[_-]?arbiter|ref[_-]?plane)"
            (or source-text ""))))

(defn- mission-a-candidate? [{:keys [readme-text wrangler-text source-text]}]
  (and (decentralization-claim? readme-text)
       (d1-binding? wrangler-text)
       (cas-arbiter-signal? source-text)))

;; ─────────────── Mission B 判定（…mission_b.cljc の写し） ─────────────────

(defn- custody-gated? [script-text]
  (boolean (re-find #"(?i)(migration\.edn|svelte/)" (or script-text ""))))

(defn- kotoba-twin-exists? [clj-relative-path kotoba-relative-paths]
  (let [stem (some-> clj-relative-path (str/replace #"\.cljc?$" ""))]
    (boolean (some #(str/includes? % stem) kotoba-relative-paths))))

(defn- host-mechanism-signal? [source-text]
  (boolean
   (re-find #"(?i)(clj-http|hato\.client|java\.net\.Socket|fs/readFileSync|fs/writeFileSync|process/exec|System/getenv|System/exit|clojure\.java\.io|\.execFileSync|\.spawnSync)"
            (or source-text ""))))

(defn- operational-script-signal?
  "Real candidates found 2026-08-29 (production run against orgs/cloud-itonami/,
   1,857 repos): 4-line run_tests.clj files (require + run-tests + System/exit,
   zero product decisions) ranked as the smallest -- therefore highest-priority
   -- candidates. line-count<=400 alone does not separate 'small and pure' from
   'small and empty of decisions'; requiring defn-count>=1 (below) and rejecting
   runner basenames here both do."
  [path source-text]
  (or (boolean (re-find #"(?i)(run[_-]?tests?|test[_-]?runner|runner)\.(clj|cljc)$" (or path "")))
      (boolean (re-find #"clojure\.test/run-tests|cljs\.test/run-tests" (or source-text "")))))

(defn- defn-count [source-text]
  (count (re-seq #"\(defn-?\s" (or source-text ""))))

(defn- candidate-slice? [{:keys [line-count custody-gated? has-kotoba-twin? host-mechanism?
                                  operational-script? defn-count]}]
  (and (not custody-gated?)
       (not has-kotoba-twin?)
       (not host-mechanism?)
       (not operational-script?)
       (pos? (or defn-count 0))
       (pos? line-count)
       (<= line-count 400)))

;; ───────────────────────────── I/O ──────────────────────────────

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

(defn- read-file [rel]
  (try (str (.readFileSync fs (path.join root rel) "utf8")) (catch :default _ nil)))

(defn- repo-of
  "orgs/cloud-itonami/<repo>/... の <repo> を取り出す。"
  [rel]
  (nth (str/split rel #"/") 2 nil))

(defn- worktree-artifact? [rel]
  (or (str/includes? (str "/" rel) "/.claude/worktrees/")
      ;; stale linked worktree（repo 名が "." で始まる）: svelte-cljs-wave-tick.cljs
      ;; の実測知見と同じ罠 — 掃き出し先を repo として二重に数えない。
      (str/starts-with? (str (repo-of rel)) ".")))

(defn- scan!
  "orgs/cloud-itonami/ を 1 パス走査して、対象拡張子の rel-path を返す。
   失敗時 nil。find の stdout を execFileSync でパイプ受けすると、負荷の高い
   機械では遅くなる（svelte-cljs-wave-tick.cljs の実測知見）ので、ファイルに
   落として読む。"
  []
  (let [tmp (path.join (or (aget (.-env js/process) "TMPDIR") "/tmp")
                       (str "frw-" (.getTime (js/Date.)) ".txt"))
        find-args ["orgs/cloud-itonami"
                   "(" "-name" "node_modules" "-o" "-name" ".git" "-o" "-name" "dist"
                   "-o" "-name" "build" "-o" "-name" "out" "-o" "-name" "target"
                   "-o" "-name" "test" "-o" "-name" ".shadow-cljs" "-o" "-path" "*/.claude/worktrees/*"
                   ")" "-prune" "-o"
                   "(" "-name" "*.clj" "-o" "-name" "*.cljc" "-o" "-name" "*.kotoba"
                   "-o" "-name" "*.cljs" "-o" "-name" "wrangler.jsonc" "-o" "-name" "wrangler.toml"
                   "-o" "-name" "wrangler.json" "-o" "-name" "README.md" "-o" "-name" "README.edn"
                   ")" "-type" "f" "-print"]]
    (try
      (let [fd (.openSync fs tmp "w")]
        (try (.execFileSync cp "find" (clj->js find-args)
                            #js {:cwd root :stdio #js ["ignore" fd "ignore"] :timeout 900000})
             (finally (.closeSync fs fd)))
        (let [rels (->> (str/split-lines (str (.readFileSync fs tmp "utf8")))
                        (remove str/blank?)
                        (remove worktree-artifact?)
                        vec)]
          (.unlinkSync fs tmp)
          rels))
      (catch :default _ nil))))

;; ───────────────────────── in-flight ─────────────────────────

(defn- sh [cmd cmd-args opts]
  (try
    (let [r (.spawnSync cp cmd (clj->js cmd-args)
                        (clj->js (merge {:encoding "utf8" :cwd root :timeout 30000} opts)))]
      {:code (aget r "status") :out (str (aget r "stdout")) :err (str (aget r "stderr"))})
    (catch :default e {:code nil :out "" :err (str e)})))

(defn- remote-name [repo-name]
  (let [{:keys [out]} (sh "git" ["-C" (path.join fleet-dir repo-name) "remote"] {})
        names (->> (str/split-lines out) (remove str/blank?) set)]
    (cond (contains? names "cloud-itonami") "cloud-itonami"
          (contains? names "origin") "origin"
          :else (first (sort names)))))

(defn- has-linked-worktree? [repo-name]
  (let [{:keys [code out]} (sh "git" ["-C" (path.join fleet-dir repo-name) "worktree" "list" "--porcelain"] {})]
    (if (not= 0 (or code -1))
      true
      (> (count (re-seq #"(?m)^worktree " out)) 1))))

(defn- in-flight [repo-name branch-name]
  (if (has-linked-worktree? repo-name)
    :worktree
    (if-let [rem (remote-name repo-name)]
      (let [{:keys [code out]} (sh "git" ["-C" (path.join fleet-dir repo-name)
                                          "ls-remote" "--heads" rem branch-name] {})]
        (cond (not= 0 (or code -1)) :unmeasured
              (str/blank? out) nil
              :else :branch))
      :unmeasured)))

;; ───────────────────────── candidate assembly ─────────────────────────

(defn- mission-a-candidates
  "readme-by-repo / wrangler-text-by-repo / source-text-by-repo（同じ repo の
   .ts/.js/.clj/.cljc/.cljs を連結したもの、arbiter grep 代わりに全文検索）
   から Mission A 候補を作る。source は README+wrangler の 2 条件が揃った repo
   だけに絞ってから合成する（コストの高い連結を全 repo に対して行わない）。"
  [by-repo]
  (->> by-repo
       (keep (fn [[repo-name files]]
               (let [readme (some #(when (re-find #"README\.(md|edn)$" %) (read-file %)) files)
                     wrangler-files (filter #(re-find #"wrangler\.(jsonc|toml|json)$" %) files)
                     wrangler-text (apply str (keep read-file wrangler-files))]
                 (when (and (decentralization-claim? readme) (d1-binding? wrangler-text))
                   (let [src-files (filter #(re-find #"\.(clj|cljc|cljs)$" %) files)
                         source-text (apply str (keep read-file src-files))]
                     (when (mission-a-candidate? {:readme-text readme
                                                  :wrangler-text wrangler-text
                                                  :source-text source-text})
                       {:repo (str fleet-dir "/" repo-name)
                        :org "cloud-itonami"
                        :name repo-name
                        :mission :a}))))))
       vec))

(defn- mission-b-candidates
  [by-repo]
  (->> by-repo
       (mapcat (fn [[repo-name files]]
                 (let [scripts (filter #(and (re-find #"\.cljs$" %) (re-find #"/(docs|scripts)/" %)) files)
                       custody? (some #(custody-gated? (read-file %)) scripts)]
                   (when-not custody?
                     (let [clj-files (filter #(re-find #"\.(clj|cljc)$" %) files)
                           kotoba-files (filter #(re-find #"\.kotoba$" %) files)]
                       (->> clj-files
                            (keep (fn [f]
                                    (let [text (read-file f)
                                          line-count (if text (count (str/split-lines text)) 0)
                                          has-twin? (kotoba-twin-exists? f kotoba-files)
                                          host-sig? (host-mechanism-signal? text)
                                          op-sig? (operational-script-signal? f text)
                                          n-defn (defn-count text)]
                                      (when (candidate-slice? {:line-count line-count
                                                               :custody-gated? false
                                                               :has-kotoba-twin? has-twin?
                                                               :host-mechanism? host-sig?
                                                               :operational-script? op-sig?
                                                               :defn-count n-defn})
                                        {:repo (str fleet-dir "/" repo-name)
                                         :org "cloud-itonami"
                                         :name repo-name
                                         :mission :b
                                         :file f
                                         :lines line-count})))))))))
                 )
       (sort-by :lines)
       vec))

;; ───────────────────────── main ─────────────────────────

(defn -main []
  (let [rels (scan!)]
    (when (nil? rels)
      (println "REFUSING\tfind(1) failed — the scan did not run")
      (println "SCANNED\t0")
      (js/process.exit 2))

    (let [by-repo (reduce (fn [m r] (update m (repo-of r) (fnil conj []) r)) {} rels)
          pool (count by-repo)]
      (when (zero? pool)
        (println (str "REFUSING\t" fleet-dir " に repo が 0 件 — walk が壊れている"
                      "（cloud-itonami fleet は 100+ repo を持つはず）"))
        (println "SCANNED\t0")
        (js/process.exit 2))

      (let [want-a (contains? #{"a" "both"} mission-filter)
            want-b (contains? #{"b" "both"} mission-filter)
            a-cands (if want-a (mission-a-candidates by-repo) [])
            b-cands (if want-b (mission-b-candidates by-repo) [])
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
                 :pool pool
                 :files (count rels)
                 :mission-a-raw (count a-cands)
                 :mission-b-raw (count b-cands)
                 :limit limit
                 :in-flight-skipped @skipped
                 :unmeasured-skipped @unmeasured
                 :candidates picked}]

        (println (str "SCANNED\t" (count rels) "\tfiles under " fleet-dir))
        (println (str "POOL\t" pool "\trepos"))
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
        (js/process.exit 0)))))

(-main)

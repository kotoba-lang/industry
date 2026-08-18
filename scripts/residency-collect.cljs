#!/usr/bin/env nbb
;; residency-collect.cljs — 常駐の宣言と、実際に動いているものを突き合わせる。
;;
;; ADR-2608111721 決定 6 の collector。**各 repo が `residency.edn` で自分の常駐を
;; 宣言し、ここは集めて実機と比べるだけ。** 起動方法をここに書かない。
;;
;;   nbb scripts/residency-collect.cljs            # 報告
;;   nbb scripts/residency-collect.cljs --check    # drift があれば exit 1（gate 用）
;;   nbb scripts/residency-collect.cljs --edn      # 機械可読
;;
;; ## なぜ「宣言」と「実機」を両方見るのか
;;
;; 実測 2026-08-11、このラップトップには 27 本の常駐が load されていて、
;; **そのうち 18 本は plist がどの repo にも無かった** —— 1 台のディスクにしか
;; 存在しない常駐で、消えたら何が動いていたのかを誰も再構成できない。
;; さらに 10 本は last-exit 78/127（設定エラー・コマンド不在）で、**走らないので
;; 落ちもせず**、誰も気づかないまま静かに死んでいた。
;;
;; 宣言だけを見ると「18 本」は見えない。実機だけを見ると「これは動くべきものか」が
;; 分からない。**両方を突き合わせて初めて drift になる。**
;;
;; ## 検査する 4 つの drift
;;
;;   1. :undeclared   installed だが宣言が無い  → 1 台にしか存在しない常駐
;;   2. :missing      宣言があるが installed でない
;;   3. :dead         installed だが last-exit が 78/127（構造的に走っていない）
;;   4. :slot-breach  :attested なのに gate rotation に残っている（不変条件 3 の趣旨違反）
;;
;; ## `--check` の床（ratchet）
;;
;; :undeclared は今日 18 本ある。**永久に赤い gate は gate が無いのと同じ**なので、
;; 件数が baseline を**超えたときだけ**落とす（`manifest/residency-baseline.edn`）。
;; 減ったら baseline を下げる。docs-edn-only の「accepted baseline に無い新しい .md」と
;; 同じ作法。:missing / :dead / :slot-breach は baseline を持たない —— こちらは
;; 「今は無い」を守る回帰ガードで、1 本でも出たら落とす。
;;
;; ## 走査範囲について正直に書く
;;
;; 宣言は **checkout 済みの repo にしか無い**。west は 4,000 超の project を管理し、
;; 必要になるまで取得しない。したがって「宣言が無い」は「その repo が常駐を持たない」
;; ことの証拠にならない。走査した repo 数を必ず出す。
;;
;; 実機側はいま **このホストだけ**（`launchctl list`）。fleet ノード側の
;; `/Library/LaunchDaemons` は `--host <node>` で見る（実測 2026-08-11: asher に
;; murakumo 系 10 枚 + hayari-collect + itonami の media orchestrator が居る）。

(ns residency-collect
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def check? (some #{"--check"} args))
(def edn-out? (some #{"--edn"} args))
(def host-arg (let [i (.indexOf args "--host")] (when-not (neg? i) (get args (inc i)))))

(def root (or (some (fn [a] (when-not (str/starts-with? a "--") a)) args) "."))

;; 対象の label はこのワークスペースの常駐だけに絞る。他人の LaunchAgent
;; （Homebrew・Dropbox 等）を drift として数えない。
(def ours #"gftd|murakumo|itonami|kotoba|awai|isekai|network-awai")

(defn- slurp* [p] (try (.readFileSync fs p "utf8") (catch :default _ nil)))

(defn- read-edn [p]
  (when-let [s (slurp* p)]
    (try (edn/read-string s)
         (catch :default e
           (println "WARNING: unreadable" p "—" (.-message e))
           nil))))

;; --- 宣言を集める -----------------------------------------------------------

(defn- dirs [p]
  (try (->> (.readdirSync fs p #js {:withFileTypes true})
            (filter #(.isDirectory %))
            (mapv #(.-name %)))
       (catch :default _ [])))

(defn- declaration-paths
  "root 自身の residency.edn と、checkout 済み orgs/<org>/<repo>/residency.edn。"
  [root]
  (let [self (path/join root "residency.edn")
        orgs-root (path/join root "orgs")
        child (for [org (dirs orgs-root)
                    repo (dirs (path/join orgs-root org))
                    :let [p (path/join orgs-root org repo "residency.edn")]
                    :when (.existsSync fs p)]
                p)]
    (cond-> (vec child) (.existsSync fs self) (conj self))))

(defn- scanned-repo-count [root]
  (let [orgs-root (path/join root "orgs")]
    (reduce + 0 (for [org (dirs orgs-root)] (count (dirs (path/join orgs-root org)))))))

(defn- west-project-count [root]
  (let [s (slurp* (path/join root "manifest" "west.yml"))]
    (if s (count (re-seq #"(?m)^\s+path: orgs/" s)) 0)))

;; --- 実機を見る -------------------------------------------------------------

(defn- sh [cmd args]
  (let [r (cp/spawnSync cmd (clj->js args) #js {:encoding "utf8" :maxBuffer (* 8 1024 1024)})]
    {:rc (aget r "status") :out (str (aget r "stdout"))}))

(defn- installed
  "launchctl の 1 行 = pid / last-exit / label。pid が `-` なら今は走っていない
  （interval 起動なら正常。KeepAlive なら異常）。"
  [host]
  (let [{:keys [out]} (if host
                        (sh "ssh" ["-o" "ConnectTimeout=8" "-o" "BatchMode=yes" host "launchctl list"])
                        (sh "launchctl" ["list"]))]
    (->> (str/split-lines out)
         (drop 1)
         (keep (fn [line]
                 (let [[pid ex lbl] (str/split (str/trim line) #"\s+")]
                   (when (and lbl (re-find ours lbl))
                     {:label lbl
                      :pid (when-not (= "-" pid) pid)
                      :last-exit (js/parseInt ex 10)}))))
         (into {} (map (juxt :label identity))))))

;; --- gate rotation（:attested の交差検査） ---------------------------------

(defn- operator-hosts
  "probe.cljs の operator-hosts。source が正本で、nodes.edn は生成物。"
  [root]
  (let [s (slurp* (path/join root "scripts" "fleet-ci" "probe.cljs"))]
    (if-let [m (re-find #"(?s)\(def operator-hosts.*?#\{([^}]*)\}\)" (str s))]
      (set (map #(str/replace % "\"" "") (re-seq #"\"[^\"]+\"" (nth m 1))))
      #{})))

(defn- gate-rotation-hosts
  "nodes.edn で :caps が空でないノード = gate が飛ぶノード。"
  [root]
  (let [d (read-edn (path/join root "scripts" "fleet-ci" "nodes.edn"))]
    (->> (:nodes d)
         (remove #(empty? (:caps %)))
         (map :host)
         set)))

;; --- 突き合わせ -------------------------------------------------------------

(def declarations
  (->> (declaration-paths root)
       (keep (fn [p] (when-let [d (read-edn p)] (assoc d :residency/path p))))
       vec))

(def declared-jobs
  (vec (for [d declarations
             j (:jobs d)]
         (assoc j :repo (:residency/repo d) :from (:residency/path d)))))

(def retired-labels
  (set (for [d declarations r (:retired d)] (:label r))))

(def live (installed host-arg))
(def declared-labels (set (map :label declared-jobs)))

(def undeclared
  (vec (sort (remove #(or (declared-labels %) (retired-labels %)) (keys live)))))

(def missing
  (vec (sort (for [j declared-jobs
                   :when (and (not (contains? live (:label j)))
                              ;; 別ホストに置く宣言は、そのホストを見るまで判定しない
                              (or (nil? host-arg) (= (:host j) host-arg))
                              (= :operator (:host j)))]
               (:label j)))))

(def dead
  (vec (sort (for [[lbl s] live
                   :when (contains? #{78 127} (:last-exit s))]
               (str lbl " exit=" (:last-exit s))))))

(def slot-breach
  (let [rotation (gate-rotation-hosts root)
        ops (operator-hosts root)]
    (vec (for [j declared-jobs
               :when (and (= :attested (:slot j))
                          (string? (:host j))
                          (or (contains? rotation (:host j))
                              (not (contains? ops (:host j)))))]
           (str (:label j) " on " (:host j)
                (if (contains? rotation (:host j))
                  " — gate rotation に残っている"
                  " — operator-hosts に無い"))))))

;; --- 報告 -------------------------------------------------------------------

(def baseline
  (or (read-edn (path/join root "manifest" "residency-baseline.edn"))
      {:undeclared 0}))

(def report
  {:host (or host-arg :this-machine)
   :declarations (mapv :residency/path declarations)
   :declared (count declared-jobs)
   :retired (count retired-labels)
   :installed (count live)
   :coverage {:repos-scanned (scanned-repo-count root)
              :west-projects (west-project-count root)}
   :drift {:undeclared undeclared
           :missing missing
           :dead dead
           :slot-breach slot-breach}
   :baseline baseline})

(if edn-out?
  (println (pr-str report))
  (do
    (println "residency —" (name (if (keyword? (:host report)) (:host report) :remote))
             (str "(" (or host-arg "launchctl list") ")"))
    (println)
    (println "  宣言:" (count declarations) "repo /" (count declared-jobs) "job"
             (str "(retired " (count retired-labels) ")"))
    (doseq [d declarations]
      (println "   -" (:residency/repo d) (str "(" (count (:jobs d)) " job)")))
    (println "  実機:" (count live) "job load 済み")
    (println "  走査:" (:repos-scanned (:coverage report)) "repo checkout /"
             (:west-projects (:coverage report)) "west project")
    (println "        ↑ 宣言は checkout 済み repo にしか無い。『宣言が無い』は"
             "『常駐を持たない』の証拠ではない")
    (println)
    (println "  drift:")
    (println "    undeclared " (count undeclared) (str "(baseline " (:undeclared baseline) ")"))
    (doseq [l undeclared] (println "       " l))
    (println "    missing    " (count missing))
    (doseq [l missing] (println "       " l))
    (println "    dead       " (count dead))
    (doseq [l dead] (println "       " l))
    (println "    slot-breach" (count slot-breach))
    (doseq [l slot-breach] (println "       " l))))

(when check?
  (let [over (- (count undeclared) (:undeclared baseline))
        fails (cond-> []
                (pos? over) (conj (str "undeclared " (count undeclared) " > baseline "
                                       (:undeclared baseline) " — 1 台にしか存在しない常駐が増えた"))
                (seq missing) (conj (str "missing " (count missing) " — 宣言されているのに load されていない"))
                (seq dead) (conj (str "dead " (count dead) " — exit 78/127（走らないので落ちもしない）"))
                (seq slot-breach) (conj (str "slot-breach " (count slot-breach)
                                             " — :attested なホストが gate を実行しうる")))]
    (println)
    (if (seq fails)
      (do (doseq [f fails] (println "FAIL" f))
          (set! (.-exitCode js/process) 1))
      (println "OK — 宣言と実機が一致し、undeclared は baseline 以下"))))

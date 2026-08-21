#!/usr/bin/env nbb
;; sweep.cljs — fleet ノードのディスクを回収する。
;;
;; 満杯のノードは gate を **走らせる前に** 殺す（tar が "No space left on device"）。
;; CI が赤になるのではなく、実行されないので signal が一切出ない。2026-07-30 に
;; asher が実際にこの状態で、dan / issachar / naphtali も数百 MiB まで詰まっていた。
;;
;; ポリシーは scripts/fleet-ci/sweep.edn（allowlist）。ノードは nodes.edn から引く。
;;
;; 使い方:
;;   nbb scripts/fleet-ci/sweep.cljs                 ;; dry-run（既定）。何を消すかだけ出す
;;   nbb scripts/fleet-ci/sweep.cljs --apply         ;; 実際に消す
;;   nbb scripts/fleet-ci/sweep.cljs --all           ;; low-water を無視して全ノード
;;   nbb scripts/fleet-ci/sweep.cljs --only dan,levi ;; ノードを絞る
;;   nbb scripts/fleet-ci/sweep.cljs --apply --json  ;; 1 行 JSON（ログ集約用）
;;
;; 不変条件は sweep.edn の冒頭に書いた。特に:
;;   * allowlist only（実データは「列挙されていない」ことで守られる）
;;   * 前提を満たさない entry は消さずに理由を出す
;;   * 回収量は df の前後差で実測する（exit code を信用しない）
(ns fleet-ci.sweep
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]))

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
(def here (path/dirname *file*))
(def apply? (boolean (:apply opts)))

(defn- read-edn [f] (reader/read-string (fs/readFileSync f "utf8")))

(def policy (read-edn (path/join here "sweep.edn")))
(def nodes (:nodes (read-edn (path/join here "nodes.edn"))))

(defn log [& xs] (println (str/join " " (map str xs))))

(defn- shell-quote
  "リモートに渡す 1 引数ぶんの single-quote エスケープ。`pr-str` ではだめ:
  double quote で包むとローカルの shell が `$4` や `\\`` を先に展開してしまい、
  リモートに届く前にコマンドが別物になる（実測: `awk '{print $4}'` が
  `awk '{print }'` になって df の出力が消えた）。"
  [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn ssh
  "ノード上でコマンドを実行し {:exit :out} を返す。BatchMode なので鍵が無ければ即失敗する。"
  [host command & [{:keys [timeout] :or {timeout 600000}}]]
  (try
    (let [out (cp/execSync (str "ssh -o BatchMode=yes -o ConnectTimeout=15 " host
                                " " (shell-quote command))
                           #js {:encoding "utf8" :stdio "pipe" :timeout timeout})]
      {:exit 0 :out (str out)})
    (catch :default e
      {:exit (or (.-status e) 1)
       :out (str (or (.-stdout e) "") (or (.-stderr e) ""))})))

(defn free-kib
  "ルートの空き KiB を {:kib n} で返す。取れないときは **理由を区別して** 返す:
  `{:error :unreachable}` と `{:error :unparseable}` は別物で、後者を前者として
  報告すると「ノードが落ちている」という嘘の診断になる（実測でそれをやった）。"
  [host]
  (let [{:keys [exit out]} (ssh host "df -k / | awk 'NR==2{print $4}'" {:timeout 40000})]
    (if-not (zero? exit)
      {:error :unreachable}
      (let [n (js/parseInt (str/trim out) 10)]
        (if (js/isNaN n) {:error :unparseable :out (str/trim out)} {:kib n})))))

(defn- gib [kib] (when kib (/ (js/Math.round (/ kib 104857.6)) 10)))

;; --- 前提の検査 ------------------------------------------------------------
;; path 名を信用しない。1 つでも満たされなければ、その entry は消さない。

(defn preconditions-unmet
  "満たされていない前提の説明のリスト（空なら消してよい）。"
  [host {:keys [path require-absent-binaries require-no-launchd-match
                require-untouched-days]}]
  (cond-> []
    ;; 既に無い path を「消す予定」と報告しない（dry-run の出力が嘘になる）。
    path
    (into (let [{:keys [out]} (ssh host (str "test -e " path " && echo present || echo absent")
                                   {:timeout 40000})]
            (when (str/includes? out "absent")
              [(str path " does not exist — nothing to reclaim")])))
    (seq require-absent-binaries)
    (into (for [b require-absent-binaries
                :let [{:keys [out]} (ssh host (str "command -v " b " >/dev/null 2>&1 && echo present || echo absent")
                                         {:timeout 40000})]
                :when (str/includes? out "present")]
            (str b " is installed — the cache may be live")))

    require-no-launchd-match
    (into (let [{:keys [out]} (ssh host (str "launchctl list 2>/dev/null | grep -ci " require-no-launchd-match " || true")
                                   {:timeout 40000})]
            (when-not (= "0" (str/trim out))
              [(str "a launchd job matches " require-no-launchd-match " — something may still use it")])))

    require-untouched-days
    (into (let [{:keys [out]} (ssh host (str "find " path " -type f -newermt '" require-untouched-days " days ago' 2>/dev/null | head -1")
                                   {:timeout 120000})]
            (when-not (str/blank? (str/trim out))
              [(str "written within " require-untouched-days " days — not stale")])))))

;; --- 回収 ------------------------------------------------------------------

(defn target-command [{:keys [kind path older-than-min command]}]
  (case kind
    :command command
    :prune-children (str "find " path " -maxdepth 1 -mindepth 1 -type d -mmin +"
                         older-than-min " -exec rm -rf {} + 2>/dev/null || true")
    ;; ~ はリモート側で展開させる（ローカルの HOME を混ぜない）
    :rmrf (str "test -d " path " && rm -rf " path "; true")
    nil))

(defn sweep-node [host]
  (let [probe (free-kib host)
        before (:kib probe)]
    (if (nil? before)
      (do (log (str "  " host " — " (name (:error probe)) ", skipped"
                    (when-let [o (:out probe)] (str " (df said: " (pr-str o) ")"))))
          {:host host :skipped (:error probe)})
      (let [low (* (:low-water-gib policy) 1048576)
            act? (or (:all opts) (< before low))]
        (if-not act?
          (do (log (str "  " host " — " (gib before) "GiB free, above low-water, skipped"))
              {:host host :skipped :above-low-water :free-gib (gib before)})
          (let [results
                (doall
                 (for [t (:targets policy)]
                   (let [unmet (preconditions-unmet host t)]
                     (cond
                       (seq unmet)
                       (do (log (str "  " host " " (name (:id t)) ": SKIP — " (first unmet)))
                           {:id (:id t) :skipped (first unmet)})

                       (not apply?)
                       (do (log (str "  " host " " (name (:id t)) ": would sweep"))
                           {:id (:id t) :dry-run true})

                       :else
                       (let [{:keys [exit]} (ssh host (target-command t))]
                         (log (str "  " host " " (name (:id t)) ": swept"
                                   (when-not (zero? exit) " (command reported non-zero)")))
                         {:id (:id t) :exit exit})))))
                after (if apply? (:kib (free-kib host)) before)
                ;; 回収量は df の前後差で決める。コマンドの exit code ではない。
                delta (when (and after before) (gib (- after before)))]
            (log (str "  " host " — free " (gib before) "GiB -> " (gib after) "GiB"
                      (when (and delta (pos? delta)) (str "  (+" delta "GiB)"))))
            (when (and after (< after (* (:alert-below-gib policy) 1048576)))
              (log (str "  " host " — WARNING still below " (:alert-below-gib policy)
                        "GiB after sweep; real data, not cache, is filling this node")))
            {:host host :free-before-gib (gib before) :free-after-gib (gib after)
             :reclaimed-gib delta :targets results}))))))

(let [only (when (string? (:only opts)) (set (str/split (:only opts) #",")))
      hosts (cond->> (map :host nodes)
              only (filter only))]
  (log (str "sweep " (:policy policy) (if apply? " — APPLY" " — dry-run (pass --apply to act)")
            " — " (count hosts) " node(s), low-water " (:low-water-gib policy) "GiB"))
  (let [results (mapv sweep-node hosts)
        total (reduce + 0 (keep :reclaimed-gib results))]
    (log (str "sweep done — " (js/Math.round (* 10 total)) "/10 GiB reclaimed across "
              (count (remove :skipped results)) " node(s)"))
    (when (:json opts)
      (println (js/JSON.stringify (clj->js {:policy (:policy policy) :apply apply?
                                            :reclaimed-gib total :nodes results}))))))

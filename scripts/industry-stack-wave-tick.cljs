#!/usr/bin/env nbb
;; scripts/industry-stack-wave-tick.cljs — industry-stack wave の**測定**入口
;; （ADR-2608090800）。loop が毎サイクル先に走らせ、次 wave の候補を機械的に出す。
;;
;; 答える問い:
;;   1. shape-matched（operation/governor/store/phase）で render_html が無い isic は何本か
;;   2. 次 wave に載せる 24 本はどれか（sector 分散、dirty skip）
;;   3. プールが枯れたら候補 0（モデルを起こさない）
;;
;; 使い方:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/industry-stack-wave-tick.cljs
;;   nbb ... scripts/industry-stack-wave-tick.cljs --limit 24

(ns industry-stack-wave-tick
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def fs (js/require "fs"))
(def path (js/require "path"))
(def cp (js/require "child_process"))
(def os (js/require "os"))

(def home (.homedir os))
(def root (or (aget (.-env js/process) "COM_JUNKAWASAKI_ROOT")
              (str home "/github/com-junkawasaki")))
(def ledger-file (str home "/.itonami/industry-stack-wave-tick.ledger.edn"))
(def wave-ledger (str home "/.itonami/industry-stack-wave.ledger.edn"))
(def project-ledger (str root "/90-docs/business/industry-stack-ledger.edn"))
(def cloud-root (.join path root "orgs" "cloud-itonami"))

(def need-files ["operation.cljc" "governor.cljc" "store.cljc" "phase.cljc"])

(def done-file (str home "/.itonami/industry-stack-wave-done.edn"))

(defn- done-from-project-ledger
  "project ledger の構造化行だけから着地 repo を拾う。
  free-text scrape はしない（dry-run の :next 候補や note の名前で
  未着地 repo が done 扱いになりプールが偽縮みするのを防ぐ）。"
  []
  (try
    (let [lines (->> (str (.readFileSync fs project-ledger "utf8"))
                     str/split-lines
                     (remove str/blank?)
                     (remove #(str/starts-with? (str/trim %) ";")))]
      (reduce
       (fn [acc line]
         (try
           (let [m (edn/read-string line)
                 t (:event/type m)]
             (cond
               (= t :industry-stack/wave)
               (into acc
                     (keep (fn [x]
                             (let [r (if (map? x) (:repo x) x)]
                               (when (and (string? r)
                                          (str/starts-with? r "cloud-itonami-isic-"))
                                 r)))
                           (concat (:event/merged m)
                                   (:event/merged-new m)
                                   ;; 一部 wave は :event/merged 無しで note だけ
                                   ;; その場合は pins 行側を信じる
                                   )))
               (= t :industry-stack/pins)
               (into acc
                     (filter #(and (string? %)
                                   (str/starts-with? % "cloud-itonami-isic-"))
                             (:event/pins m)))
               :else acc))
           (catch :default _ acc)))
       #{}
       lines))
    (catch :default _ #{})))

(defn- done-repos
  "既 wave で着地した repo 名集合。local checkout が pin より遅れていても
  再ピックしないための床（skill 側は origin/main を見るが、slot を浪費しない）。
  正本は ~/.itonami/industry-stack-wave-done.edn（wave 着地時に skill が conj）。
  保険は project ledger の :event/merged / :event/pins だけ — loop/tick
  ledger の free-text や dry-run 候補は見ない。"
  []
  (let [from-file (try (set (edn/read-string (str (.readFileSync fs done-file "utf8"))))
                       (catch :default _ #{}))]
    (into from-file (done-from-project-ledger))))

(defn- log! [& xs]
  (println (str (.toISOString (js/Date.)) " " (str/join " " (map str xs)))))

(defn- sh [cmd args]
  (try
    (let [r (.spawnSync cp cmd (clj->js args)
                        #js {:encoding "utf8" :cwd root})]
      {:code (.-status r) :out (str (.-stdout r)) :err (str (.-stderr r))})
    (catch :default e {:code 1 :out "" :err (str e)})))

(defn- append-ledger! [m]
  (try (.appendFileSync fs ledger-file (str (pr-str m) "\n"))
       (catch :default e (log! "ledger 追記失敗:" (str e)))))

(defn- limit-arg []
  (let [args (vec *command-line-args*)
        i (.indexOf args "--limit")]
    (if (and (>= i 0) (< (inc i) (count args)))
      (js/parseInt (nth args (inc i)) 10)
      24)))

(defn- real-checkout?
  "west が管理する実 checkout だけを repo とみなす。

  過去の wave が共有 `orgs/cloud-itonami/` の *中* に linked worktree を
  残しており（実測 2026-08-12 に 14 件、`<repo>-wt-flagship-item2` 等）、
  名前が `cloud-itonami-isic-` で始まるので repo として数えられていた。
  linked worktree は `.git` が **ファイル**（gitdir ポインタ）、実 checkout は
  `.git` が **ディレクトリ**。名前の `-wt-` 規約ではなくこの構造で判定する。"
  [name]
  (try
    (.isDirectory (.statSync fs (.join path cloud-root name ".git")))
    (catch :default _ false)))

(defn- isic-dirs []
  (try
    (->> (.readdirSync fs cloud-root)
         (map str)
         (filter #(str/starts-with? % "cloud-itonami-isic-"))
         (filter real-checkout?)
         sort
         vec)
    (catch :default _ [])))

(defn- domain-dir [repo-path]
  (let [src (.join path repo-path "src")]
    (try
      (->> (.readdirSync fs src #js {:withFileTypes true})
           (filter #(.isDirectory %))
           (map #(.-name %))
           (filter (fn [d]
                     (every? #(.existsSync fs (.join path src d %)) need-files)))
           first)
      (catch :default _ nil))))

(defn- dirty? [repo-path]
  (let [{:keys [code out]} (sh "git" ["-C" repo-path "status" "--porcelain"])]
    (and (= 0 code) (seq (str/trim out)))))

(defn- render-path? [n]
  (or (str/ends-with? n "render_html.clj")
      (str/ends-with? n "render_html.cljc")))

(defn- has-render-in-worktree? [repo-path]
  (let [walk (fn walk [dir]
               (try
                 (let [ents (.readdirSync fs dir #js {:withFileTypes true})]
                   (some (fn [e]
                           (let [n (.-name e)
                                 p (.join path dir n)]
                             (cond
                               (and (.isFile e) (render-path? n)) true
                               (.isDirectory e)
                               (when-not (#{"node_modules" ".git" "target"} n)
                                 (walk p))
                               :else false)))
                         ents))
                 (catch :default _ false)))]
    (boolean (walk repo-path))))

(defn- has-render-on-main?
  "origin/main の tree を見る。ref が無ければ nil（不明）を返し、判定を
  worktree 側に委ねる — false を返すと『無い』と誤って断定してしまう。"
  [repo-path]
  (let [{:keys [code out]} (sh "git" ["-C" repo-path "ls-tree" "-r"
                                      "--name-only" "origin/main"])]
    (when (= 0 code)
      (boolean (some render-path? (str/split-lines out))))))

(defn- has-render?
  "worktree と origin/main の **どちらかに** あれば着地扱いにする。

  どちらの面も単独では信用できない:
  - working tree は west pin が遅れていると、既に main へ着地している
    render_html を『無い』と報告する。実測 2026-08-12: isic-0124 は main に
    24KB の render_html.clj があるのに local checkout が pin 手前 (d61a7e7)
    で止まっており、pool が 226 から何周も動かず、同じ 24 本が毎周
    再ピックされ続けていた（wave あたり 20 agent-slot の空振り）。
  - local の origin/main ref も最後に fetch した時点の snapshot でしかなく、
    merge 済みでも fetch していなければ遅れている。

  union を取るのは、この tick の誤りのコストが非対称だからである:
  着地済みを候補に残す誤りは 1 本あたり agent 1 体を空振りさせるが、
  未着地を候補から外す誤りは次周で拾い直せる。"
  [repo-path]
  (boolean (or (has-render-in-worktree? repo-path)
               (has-render-on-main? repo-path))))

(defn- sector [name]
  (let [m (re-find #"cloud-itonami-isic-(\d+)" name)]
    (when m (subs (second m) 0 (min 2 (count (second m)))))))

(defn- pick-sector-diverse [cands n]
  (let [by (group-by :sec cands)
        first-pass (mapv (comp first second) (sort-by first by))
        rest-pool (mapcat (fn [[_ xs]] (rest xs)) (sort-by first by))]
    (vec (take n (concat first-pass rest-pool)))))

(defn -main []
  (let [started (.toISOString (js/Date.))
        n (limit-arg)
        names (isic-dirs)
        done (done-repos)
        scanned
        (for [name names
              :let [rp (.join path cloud-root name)
                    dom (domain-dir rp)]
              :when (and dom
                         (not (contains? done name))
                         (not (dirty? rp))
                         (not (has-render? rp)))]
          {:repo name
           :domain dom
           :sec (or (sector name) "??")
           :path (str "orgs/cloud-itonami/" name)})
        candidates (pick-sector-diverse scanned n)
        row {:at started
             :outcome :measured
             :pool (count scanned)
             :done (count done)
             :limit n
             :candidates candidates
             :cloud-root cloud-root
             :note "missing render_html + full modules + clean + not in prior ledgers; sector-first pick"}]
    (log! "done" (count done) "pool" (count scanned) "picked" (count candidates) "limit" n)
    (doseq [c candidates]
      (println (str "  " (:repo c) "  " (:domain c) "  sec=" (:sec c))))
    (append-ledger! row)
    (println (pr-str row))
    (js/process.exit 0)))

(-main)

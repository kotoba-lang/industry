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
(def ledger-file (str home "/.gftd/industry-stack-wave-tick.ledger.edn"))
(def wave-ledger (str home "/.gftd/industry-stack-wave.ledger.edn"))
(def project-ledger (str root "/90-docs/business/industry-stack-ledger.edn"))
(def cloud-root (.join path root "orgs" "cloud-itonami"))

(def need-files ["operation.cljc" "governor.cljc" "store.cljc" "phase.cljc"])

(def done-file (str home "/.gftd/industry-stack-wave-done.edn"))

(defn- done-repos
  "既 wave で着地した repo 名集合。local checkout が pin より遅れていても
  再ピックしないための床（skill 側は origin/main を見るが、slot を浪費しない）。
  正本は ~/.gftd/industry-stack-wave-done.edn（wave 着地時に skill が conj）。
  ledger 文字列からも拾う（seed 漏れの保険）。"
  []
  (let [from-file (try (edn/read-string (str (.readFileSync fs done-file "utf8")))
                       (catch :default _ #{}))
        texts (for [p [project-ledger wave-ledger ledger-file]]
                (try (str (.readFileSync fs p "utf8")) (catch :default _ "")))
        from-ledger (->> (str/join "\n" texts)
                         (re-seq #"cloud-itonami-isic-[\w.-]+")
                         set)]
    (into (set from-file) from-ledger)))

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

(defn- isic-dirs []
  (try
    (->> (.readdirSync fs cloud-root)
         (map str)
         (filter #(str/starts-with? % "cloud-itonami-isic-"))
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

(defn- has-render? [repo-path]
  (let [walk (fn walk [dir]
               (try
                 (let [ents (.readdirSync fs dir #js {:withFileTypes true})]
                   (some (fn [e]
                           (let [n (.-name e)
                                 p (.join path dir n)]
                             (cond
                               (and (.isFile e)
                                    (or (str/ends-with? n "render_html.clj")
                                        (str/ends-with? n "render_html.cljc")))
                               true
                               (.isDirectory e)
                               (when-not (#{"node_modules" ".git" "target"} n)
                                 (walk p))
                               :else false)))
                         ents))
                 (catch :default _ false)))]
    (boolean (walk repo-path))))

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

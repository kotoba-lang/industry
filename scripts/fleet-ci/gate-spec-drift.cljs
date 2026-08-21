#!/usr/bin/env nbb
;; gate-spec-drift.cljs — 「自分自身の古い版についての判定を表示している gate」を数える。
;;
;; tick の trigger は対象 repo の tip の変化だけ（:trigger :tip-change）。gate の
;; **宣言**（gates.edn の 1 entry）や **script**（gates/*.cljs）を直しても、対象 repo が
;; 休んでいれば tick はその gate を回さない。直った gate が直る前の赤を表示し続け、
;; それが古い判定であることを言うものが無い —— これがその母集団を出す道具。
;;
;; tick.cljs 側は ADR-2608137000 で state に spec-hash を持つようになったので、
;; **これから**の drift は tick 自身が拾う。この script が要るのは
;;   (a) その仕組みが入る前から溜まっていた分（backfill は黙って現行扱いにする）
;;   (b) 「今どれが古い判定を表示しているか」を receipt を待たずに知りたいとき
;;
;; 使い方:
;;   FLEET_CI_LIBRARY_MODE=1 nbb --classpath scripts/fleet-ci \
;;     scripts/fleet-ci/gate-spec-drift.cljs [--ref origin/main] [--edn]
;;
;; 判定: gate の宣言 hash が最後に変わった commit の日時、または gate script が
;; 最後に変わった commit の日時が、その gate の最後の receipt の日時より **後**なら
;; drift。state に entry が無い gate（一度も回っていない）は :never-run として別に出す。

(ns gate-spec-drift
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [tick :as tick]))

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
(def root (path/resolve here ".." ".."))
(def gates-path "scripts/fleet-ci/gates.edn")
(def ref (or (:ref opts) "origin/main"))

(defn git [& args]
  (try
    {:exit 0 :out (str (cp/execFileSync "git" (clj->js (into ["-C" root] args))
                                        #js {:encoding "utf8" :maxBuffer (* 64 1024 1024)}))}
    (catch :default e {:exit 1 :out (str (or (.-stdout e) "") (or (.-stderr e) ""))})))

(defn read-edn-file [f fallback]
  (if (fs/existsSync f)
    (try (reader/read-string (str (fs/readFileSync f "utf8"))) (catch :default _ fallback))
    fallback))

;; --- gates.edn の履歴を歩いて、entry ごとに「宣言が最後に変わった commit」を出す ---

(defn commits-touching [p]
  ;; 古い順。%cI は committer date（ISO8601）。
  (->> (:out (git "log" "--reverse" "--format=%H %cI" ref "--" p))
       str/split-lines
       (remove str/blank?)
       (mapv #(let [[sha at] (str/split (str/trim %) #"\s+")] {:sha sha :at at}))))

(defn gates-at [sha]
  (let [{:keys [exit out]} (git "show" (str sha ":" gates-path))]
    (when (zero? exit)
      (try (:repos (reader/read-string out)) (catch :default _ nil)))))

(defn decl-hashes [entries]
  (into {} (for [e entries] [(tick/gate-id e) (tick/decl-hash e)])))

(defn last-decl-change
  "-> {gate-id iso8601}. entry が現れた commit / 宣言 hash が変わった commit のうち最後。"
  []
  (loop [[c & more] (commits-touching gates-path) prev {} acc {}]
    (if (nil? c)
      acc
      (if-let [entries (gates-at (:sha c))]
        (let [now (decl-hashes entries)
              changed (for [[id h] now :when (not= h (get prev id))] id)]
          (recur more now (reduce #(assoc %1 %2 (:at c)) acc changed)))
        (recur more prev acc)))))

(defn last-script-change []
  (into {} (for [f (fs/readdirSync (path/join here "gates"))
                 :when (str/ends-with? f ".cljs")
                 :let [p (str "scripts/fleet-ci/gates/" f)
                       out (str/trim (:out (git "log" "-1" "--format=%cI" ref "--" p)))]
                 :when (seq out)]
             [(str "gates/" f) out])))

(defn ms
  "ISO8601 -> epoch ms。git の %cI は `+09:00` オフセット付き、state の :at は `Z`。
  **文字列比較してはいけない** —— `2026-08-10T18:23:08+09:00`（= 09:23Z）は
  `2026-08-10T09:41:16.712Z` より字面では大きいが、時刻としては前。実測でこの
  取り違えが drift を 1 件水増ししていた。"
  [s] (when (seq (str s)) (let [t (.getTime (js/Date. (str s)))] (when-not (js/isNaN t) t))))

(defn -main []
  (let [cfg (read-edn-file (path/join here "gates.edn") nil)
        _ (when-not cfg (println "gates.edn missing") (js/process.exit 1))
        state (read-edn-file (path/join (os/homedir) ".gftd" "fleet-ci-state.edn") {:repos {}})
        decl-at (last-decl-change)
        script-at (last-script-change)
        rows
        (for [r (:repos cfg)
              :let [id (tick/gate-id r)
                    st (get-in state [:repos id])
                    d (get decl-at id)
                    s (get script-at (:script r))
                    ;; 宣言と script のうち新しい方（epoch で比べる — 下の ms を参照）
                    spec-at (->> [d s] (remove nil?) (sort-by ms) last)]]
          {:id id :name (:name r) :script (:script r)
           :decl-changed d :script-changed s :spec-changed spec-at
           :last-receipt (:at st) :outcome (:outcome st)
           :status (cond
                     (nil? st) :never-run
                     (or (nil? (ms spec-at)) (nil? (ms (:at st)))) :unknown
                     (> (ms spec-at) (ms (:at st))) :stale-verdict
                     :else :current)})
        by (group-by :status rows)]
    (if (:edn opts)
      (println (pr-str {:ref ref :counts (into {} (for [[k v] by] [k (count v)])) :rows (vec rows)}))
      (do
        (println (str "gate-spec-drift @ " ref " — " (count rows) " gates"))
        (doseq [k [:stale-verdict :never-run :unknown :current]
                :let [v (get by k)] :when (seq v)]
          (println (str "\n" (name k) ": " (count v)))
          (when (not= k :current)
            (doseq [r (sort-by :id v)]
              (println (str "  " (:id r)
                            "  spec-changed=" (or (:spec-changed r) "?")
                            "  last-receipt=" (or (:last-receipt r) "-")
                            "  outcome=" (or (:outcome r) "-"))))))
        (println (str "\nstale-verdict = この gate の宣言か script が、最後の receipt より後に変わっている。"))))
    (js/process.exit 0)))

(-main)

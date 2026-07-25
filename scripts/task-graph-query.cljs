#!/usr/bin/env nbb
;; scripts/task-graph-query.cljs — pure projector over
;; 90-docs/task-graphs/task-graph.datoms.edn (topology + current
;; :task/status of each task).
;;
;; `ready-tasks` is the load-bearing function: given a catalog, it returns
;; the set of task ids whose :task/requires are all :completed and which are
;; not themselves :completed/:blocked/:skipped yet. It is a pure
;; state -> ready-set computation (catalog in, ready-set out,
;; no I/O, no side effects) — the same shape kotoba's Application Profile
;; (ADR-2607201300) requires for a `kotoba/pure` decision function, so this
;; logic is written to be lift-and-shift portable into a `.kotoba`/`.cljc`
;; task-graph library later (see ADR-2607202800 "next steps") without
;; rewriting the algorithm — only the I/O shell (file reads, CLI) changes.
;;
;; A langgraph.graph StateGraph node or a local-manimani ReAct tool can both
;; call this same `ready-tasks` fn: the node/tool just supplies the catalog
;; and turns the resulting ready set into a proposal, which for
;; :task/external-gate true tasks must go through a Governor / HITL gate
;; before anything is dispatched (this script does not dispatch anything —
;; it only reports readiness).
;;
;; Usage:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/task-graph-query.cljs status [--workflow <id>]
;;   nbb --classpath ".:scripts/nbb_compat" scripts/task-graph-query.cljs ready  [--workflow <id>]

(require '[scripts.nbb-compat :refer [slurp file file-seq exit format]]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def catalog-dir "90-docs/task-graphs")

(defn load-catalog []
  (->> (file-seq (file catalog-dir))
       (filter #(str/ends-with? (str %) ".datoms.edn"))
       (mapcat (fn [f]
                 (try (edn/read-string {:default (fn [_tag v] v)} (slurp (str f)))
                      (catch :default _ nil))))))

;; ---------- pure core ----------

(defn task-status
  "Current state of a task, read straight off its catalog entry.
   One of :pending | :dispatched | :started | :completed | :blocked | :skipped;
   :pending when :task/status is absent. Owner decision 2026-07-25
   (ADR-2607257000): the catalog holds the latest state, git holds history."
  [catalog task-id]
  (or (some (fn [t] (when (= task-id (:task/id t)) (:task/status t))) catalog)
      :pending))

(defn ready-tasks
  "catalog: seq of {:task/id :task/requires :task/status ...} maps (workflow
   maps, which have no :task/id, are ignored). Returns a seq of task maps
   (full catalog entry, plus :task/status :ready) that are not yet
   completed/blocked/skipped and whose every :task/requires entry IS
   completed."
  [catalog]
  (let [tasks (filter :task/id catalog)
        completed? (fn [id] (= :completed (task-status catalog id)))]
    (->> tasks
         (remove (fn [t] (#{:completed :blocked :skipped} (task-status catalog (:task/id t)))))
         (filter (fn [t] (every? completed? (:task/requires t))))
         (map (fn [t] (assoc t :task/status :ready))))))

(defn all-statuses [catalog]
  (->> (filter :task/id catalog)
       (map (fn [t] (assoc t :task/status (task-status catalog (:task/id t)))))))

;; ---------- CLI ----------

(defn parse-args [argv]
  (loop [args (seq argv) out {}]
    (if (empty? args)
      out
      (let [[k v & more] args]
        (if (and (string? k) (str/starts-with? k "--"))
          (recur more (assoc out (keyword (subs k 2)) v))
          (recur (rest args) out))))))

(defn filter-workflow [rows workflow]
  (if workflow (filter #(= workflow (:task/workflow %)) rows) rows))

(defn -main [& argv]
  (let [[cmd & rest-argv] argv
        {:keys [workflow]} (parse-args rest-argv)
        catalog (load-catalog)]
    (cond
      (= cmd "ready")
      (doseq [t (filter-workflow (ready-tasks catalog) workflow)]
        (println (pr-str (select-keys t [:task/id :task/workflow :task/title
                                          :task/external-gate :task/gate-reason]))))

      (= cmd "status")
      (doseq [t (filter-workflow (all-statuses catalog) workflow)]
        (println (format "%-28s %-12s %s" (:task/id t) (name (:task/status t)) (:task/title t))))

      :else
      (do (println "usage: nbb scripts/task-graph-query.cljs <status|ready> [--workflow <workflow/id>]")
          (exit 1)))))

(apply -main *command-line-args*)

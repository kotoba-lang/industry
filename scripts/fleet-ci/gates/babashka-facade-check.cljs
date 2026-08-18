#!/usr/bin/env nbb
;; babashka-facade-check.cljs — fail if a repo still advertises the retired `bb` binary.
;;
;; ADR-2607173000 retired babashka as this workspace's script host. ADR-2608131600's census
;; recorded ONE "facade" — an nbb task registry whose :cmd shells straight back out to `bb` —
;; ADR-2608133200 found five, and ADR-2608135000 converted the last four.
;;
;; WHY THIS IS A SEPARATE GATE FROM scripts/verify-no-babashka.cljs. That script's three checks
;; (a bb.edn file, a *.bb file, a `#!/usr/bin/env bb` shebang) would have caught NONE of the five:
;; four had had their bb.edn deleted by the conversion itself, none had a .bb file, none had a
;; shebang. The binary was named in the one place that script did not look — the task registry,
;; which is also the only place a machine reads. And a superproject-level gate cannot ask the
;; question at all: the fleet ships a repo's OWN tree, and the superproject's tree contains zero
;; scripts/tasks.edn, because every registry lives in a west child repo. So the check has to run
;; where the registries are: per repo.
;;
;; Two facade shapes, because one hides inside the other:
;;   ["bb" "-m" "ns"]                      -- argv head
;;   ["sh" "-c" "cd ../x && exec bb ..."]  -- inside a shell string, where an argv-head check reads
;;                                            "sh" and sees nothing wrong (kotoba-lang/
;;                                            kami-isekai-assets was exactly this shape)
;;
;; Also fails on a surviving bb.edn or *.bb in the tree, since those are the same claim in a
;; different file.
;;
;; Run on a node as: npx nbb babashka-facade-check.cljs <dir> [--min-tasks N]
;; <dir> FIRST — several gates take the tree as the first non-flag argument and this one matches
;; that convention deliberately (ADR-2608102000 records three gates misdiagnosed as broken because
;; a flag was read as the tree path).
;;
;; --min-tasks is a floor, not a target: it fails when the registry it found is smaller than the
;; repo is known to have, so that a mis-shipped or empty tree cannot report "0 facades = pass".
;; An absent registry is reported and is NOT a pass unless --allow-no-registry is given.
(ns fleet-ci.gates.babashka-facade-check
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def ^:private bb-in-shell
  ;; `bb` as a COMMAND inside a shell string: at line start, after a shell operator, or after
  ;; `exec`. Deliberately not a bare #"bb" — that matches "bb" inside a path (nbb, a file called
  ;; bb-notes.cljs) or an English word, and a detector that cries wolf gets switched off.
  #"(?:^|[;|&(\n]\s*|\bexec\s+)bb\s")

(def ^:private skip-dirs
  #{"node_modules" ".git" ".west" "target" "out" ".shadow-cljs" "dist" "build"
    ".cpcache" ".clj-kondo" ".lsp" "annex"})

(defn- walk [dir acc]
  (let [entries (try (.readdirSync fs dir #js {:withFileTypes true})
                     (catch :default _ #js []))]
    (reduce
     (fn [a ent]
       (let [nm (.-name ent)
             p (.join path dir nm)]
         (cond
           (contains? skip-dirs nm) a
           (.isDirectory ent) (walk p a)
           (.isFile ent)
           (cond-> a
             (= nm "bb.edn") (update :bb-edn conj p)
             (str/ends-with? nm ".bb") (update :dot-bb conj p)
             (= nm "tasks.edn") (update :registries conj p))
           :else a)))
     acc entries)))

(defn- read-registry [p]
  (try (let [m (reader/read-string (.readFileSync fs p "utf8"))] (when (map? m) m))
       (catch :default e
         (println "  ! could not read" p "-" (.-message e))
         nil)))

(defn- facades [p m]
  (vec (for [[k v] m
             :when (map? v)
             :let [argv (:cmd v)]
             :when (and (sequential? argv)
                        (or (= "bb" (first argv))
                            (some #(and (string? %) (re-find bb-in-shell %)) argv)))]
         {:file p :task (name k) :cmd (vec argv)})))

(defn- parse-args [args]
  (loop [xs (vec args) dir nil min-tasks 0 allow-none? false]
    (cond
      (empty? xs) {:dir (or dir ".") :min-tasks min-tasks :allow-none? allow-none?}
      (= "--min-tasks" (first xs)) (recur (subvec xs 2) dir (js/parseInt (second xs)) allow-none?)
      (= "--allow-no-registry" (first xs)) (recur (subvec xs 1) dir min-tasks true)
      (str/starts-with? (first xs) "--") (recur (subvec xs 1) dir min-tasks allow-none?)
      :else (recur (subvec xs 1) (or dir (first xs)) min-tasks allow-none?))))

(defn -main [& args]
  (let [{:keys [dir min-tasks allow-none?]} (parse-args args)
        {:keys [bb-edn dot-bb registries]} (walk dir {:bb-edn [] :dot-bb [] :registries []})
        loaded (into {} (keep (fn [p] (when-let [m (read-registry p)] [p m])) registries))
        total-tasks (reduce + 0 (map (comp count val) loaded))
        found (vec (mapcat (fn [[p m]] (facades p m)) loaded))]
    (println "babashka-facade-check dir=" dir)
    (println "  task registries:" (count registries) "· tasks:" total-tasks)
    (println "  bb.edn:" (count bb-edn) "· *.bb:" (count dot-bb) "· facades:" (count found))
    (doseq [f found]
      (println "  FACADE" (:file f) ":" (:task f) (pr-str (:cmd f))))
    (doseq [p (concat bb-edn dot-bb)] (println "  BABASHKA FILE" p))
    (cond
      (and (empty? registries) (not allow-none?))
      (do (println "FAIL: no scripts/tasks.edn under" dir
                   "- this gate cannot pose its question against this tree."
                   "Pass --allow-no-registry only if the repo genuinely has no registry.")
          (.exit js/process 1))

      (< total-tasks min-tasks)
      (do (println "FAIL: found" total-tasks "tasks, expected at least" min-tasks
                   "- the tree looks truncated, and 0 facades in a truncated tree is not a pass.")
          (.exit js/process 1))

      (or (seq found) (seq bb-edn) (seq dot-bb))
      (do (println "FAIL:" (count found) "facade(s),"
                   (+ (count bb-edn) (count dot-bb)) "babashka file(s)."
                   "babashka was retired by ADR-2607173000 and is not installed on fleet nodes.")
          (.exit js/process 1))

      :else (do (println "OK") (.exit js/process 0)))))

(apply -main (let [args (vec *command-line-args*)]
               (if (and (seq args) (str/includes? (str (first args)) "babashka-facade-check"))
                 (rest args) args)))

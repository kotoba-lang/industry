#!/usr/bin/env nbb
;; Inventory / gate: fail if tracked-style Babashka entrypoints remain.
;;
;;   nbb scripts/verify-no-babashka.cljs              ; scan from repo root
;;   nbb scripts/verify-no-babashka.cljs --root orgs   ; limit
;;   nbb scripts/verify-no-babashka.cljs --allow-zero  ; report only (exit 0)
;;
;; Looks for:
;;   - files named bb.edn
;;   - files ending in .bb
;;   - shebang #!/usr/bin/env bb
;;   - FACADES: a scripts/tasks.edn entry that shells back out to `bb`
;; Skips .git, node_modules, and common heavy dirs.
;;
;; The facade check exists because the first three do NOT find one. ADR-2608131600 recorded a
;; single "facade" -- an nbb task registry whose :cmd is ["bb" ...] -- and ADR-2608133200 found
;; five. NONE of the five would have been caught here: four had already had their bb.edn deleted
;; by the conversion, none had a .bb file, and none had a shebang. The retired binary was named in
;; the one place this script did not look, which is also the one place a machine actually reads.
;;
;; Two shapes, because a facade can hide:
;;   ["bb" "-m" "ns"]                       -- argv head
;;   ["sh" "-c" "cd ../x && exec bb ..."]   -- inside a shell string, where an argv-head check
;;                                             reads "sh" and sees nothing wrong. kami-isekai-
;;                                             assets was exactly this (ADR-2608135000).
(ns scripts.verify-no-babashka
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [scripts.nbb-compat :as compat :refer [exit]]))

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))

(def ^:private bb-in-shell
  ;; `bb` as a COMMAND inside a shell string: at the start, or after a shell operator
  ;; (; | & && || ( newline) or `exec`. Deliberately not a bare #"bb" -- that matches "bb" inside
  ;; a path, a flag or an English word, and a detector that cries wolf gets switched off.
  #"(?:^|[;|&(\n]\s*|\bexec\s+)bb\s")

(def skip-dir-names
  #{"node_modules" ".git" ".west" "target" "out" ".shadow-cljs"
    "dist" "build" ".cpcache" ".clj-kondo" ".lsp" "annex"})

(defn- skip-dir? [name]
  (contains? skip-dir-names name))

(defn- shebang-bb? [p]
  (let [head (try (.readFileSync fs p "utf8") (catch :default _ ""))]
    (or (str/starts-with? head "#!/usr/bin/env bb")
        (str/starts-with? head "#!/usr/bin/bb"))))

(defn- facade-entries
  "Task names in a tasks.edn whose command runs the retired `bb` binary."
  [p]
  (try
    (let [m (edn/read-string (.readFileSync fs p "utf8"))]
      (when (map? m)
        (vec (for [[k v] m
                   :when (map? v)
                   :let [argv (:cmd v)]
                   :when (and (sequential? argv)
                              (or (= "bb" (first argv))
                                  (some #(and (string? %) (re-find bb-in-shell %)) argv)))]
               (str p " :" (name k) " " (pr-str (vec argv)))))))
    (catch :default _ nil)))

(defn- walk [dir acc]
  (let [entries (try (.readdirSync fs dir #js {:withFileTypes true})
                     (catch :default _ #js []))]
    (reduce
     (fn [a ent]
       (let [name (.-name ent)
             p (.join path dir name)]
         (cond
           (skip-dir? name) a
           (.isDirectory ent) (walk p a)
           (.isFile ent)
           (cond-> a
             (= name "bb.edn") (update :bb-edn conj p)
             (str/ends-with? name ".bb") (update :dot-bb conj p)
             (= name "tasks.edn") (update :facade into (facade-entries p))
             (shebang-bb? p) (update :shebang-bb conj p))
           :else a)))
     acc
     entries)))

(defn- parse-args [args]
  (loop [xs (vec args) root "." allow-zero? false]
    (cond
      (empty? xs) {:root root :allow-zero? allow-zero?}
      (= "--root" (first xs)) (recur (subvec xs 2) (second xs) allow-zero?)
      (= "--allow-zero" (first xs)) (recur (subvec xs 1) root true)
      :else (recur (subvec xs 1) root allow-zero?))))

(defn -main [& args]
  (let [{:keys [root allow-zero?]} (parse-args args)
        result (walk root {:bb-edn [] :dot-bb [] :shebang-bb [] :facade []})
        counts {:bb-edn (count (:bb-edn result))
                :dot-bb (count (:dot-bb result))
                :shebang-bb (count (:shebang-bb result))
                :facade (count (:facade result))}
        total (+ (:bb-edn counts) (:dot-bb counts) (:shebang-bb counts) (:facade counts))]
    (println "verify-no-babashka root=" root)
    (println "  bb.edn:" (:bb-edn counts))
    (println "  *.bb:" (:dot-bb counts))
    (println "  shebang bb:" (:shebang-bb counts))
    (println "  tasks.edn facades:" (:facade counts))
    (when (pos? total)
      (doseq [p (take 40 (concat (:bb-edn result) (:dot-bb result) (:shebang-bb result)
                                 (:facade result)))]
        (println "   " p))
      (when (> total 40)
        (println "  … +" (- total 40) "more")))
    (if (and (pos? total) (not allow-zero?))
      (exit 1)
      (exit 0))))

(apply -main
       (let [args (vec *command-line-args*)
             script? (when (seq args)
                       (or (str/ends-with? (first args) "verify-no-babashka.cljs")
                           (str/ends-with? (first args) "verify-no-babashka")))]
         (if script? (rest args) args)))

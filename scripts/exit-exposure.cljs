#!/usr/bin/env nbb
;; exit-exposure.cljs — which repositories can have a test end the JVM.
;;
;; `System/exit` inside a namespace a test requires is the shape that took
;; amu's suite from 128 namespaces to 11 while printing no failures: the exit
;; is not a failing assertion, it is the end of the run, and everything after
;; it is simply never asked. This reports the exposure, not a verdict — a repo
;; is listed when a test requires a namespace whose source can exit, which is
;; the precondition, not proof that any test reaches it.
;;
;;   nbb exit-exposure.cljs <orgs-dir>

(ns exit-exposure
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def root (or (first *command-line-args*) "."))

(defn- files-under [dir ext]
  (letfn [(walk [d]
            (try
              (mapcat (fn [entry]
                        (let [p (path/join d entry)]
                          (cond
                            (str/starts-with? entry ".") []
                            (.isDirectory (fs/statSync p)) (walk p)
                            (str/ends-with? entry ext) [p]
                            :else [])))
                      (fs/readdirSync d))
              (catch :default _ [])))]
    (walk dir)))

(defn- slurp* [f] (try (str (fs/readFileSync f "utf8")) (catch :default _ "")))

(defn- ns-name-of [source]
  (second (re-find #"\(ns\s+([a-zA-Z0-9_.\-]+)" source)))

(defn- exiting-namespaces
  "Namespaces under src whose source can end the JVM, and whether the exit is
   reachable only through a dynamic var a test could rebind."
  [repo]
  (keep (fn [f]
          (let [src (slurp* f)]
            (when (str/includes? src "System/exit")
              {:ns (ns-name-of src)
               :guardable? (boolean (re-find #"\^:dynamic\s+\*exit\*" src))})))
        (files-under (path/join repo "src") ".clj")))

(defn- required-namespaces [repo]
  (into #{}
        (mapcat (fn [f] (re-seq #"[a-zA-Z][a-zA-Z0-9_.\-]*\.[a-zA-Z0-9_.\-]+"
                                (slurp* f)))
                (concat (files-under (path/join repo "test") ".clj")
                        (files-under (path/join repo "test") ".cljc")))))

(defn -main []
  (let [repos (->> (fs/readdirSync root)
                   ;; Dotted entries are scratch worktrees and tool state, not
                   ;; repositories; counting them inflates both numbers and
                   ;; reports the same finding several times under names that
                   ;; look like repositories and are not.
                   (remove #(str/starts-with? % "."))
                   (map #(path/join root %))
                   (filter #(and (try (.isDirectory (fs/statSync %)) (catch :default _ false))
                                 (fs/existsSync (path/join % "src"))
                                 (fs/existsSync (path/join % "test")))))
        rows (keep (fn [repo]
                     (let [exiting (exiting-namespaces repo)]
                       (when (seq exiting)
                         (let [required (required-namespaces repo)
                               reached (filter #(contains? required (:ns %)) exiting)]
                           (when (seq reached)
                             {:repo (path/basename repo)
                              :namespaces (mapv :ns reached)
                              :guardable (every? :guardable? reached)})))))
                   repos)]
    (println (str "SCANNED\t" (count repos) " repositories with both src/ and test/"))
    (println (str "EXPOSED\t" (count rows)
                  " where a test requires a namespace whose source can exit"))
    (println)
    (doseq [{:keys [repo namespaces guardable]} (sort-by :repo rows)]
      (println (str (if guardable "  guardable  " "  RAW EXIT  ")
                    repo "  " (str/join " " namespaces))))
    (println)
    (println "guardable = the exit goes through a ^:dynamic *exit*, so a fixture")
    (println "            can bind it; RAW EXIT = nothing to rebind.")
    (when (zero? (count repos))
      (println "REFUSING: scanned nothing. A scan of no repositories is not a clean result.")
      (js/process.exit 2))))

(-main)

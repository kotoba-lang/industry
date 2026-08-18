;; Report every 90-docs EDN file that fails to parse, with its reader error.
;; docs-edn-only.cljs verify caps its listing at 20; this prints all of them so a
;; repair pass can work through the whole set.
;;   nbb --classpath ".:scripts/nbb_compat" scripts/docs-edn-parse-report.cljs [--count]
(ns docs-edn-parse-report
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def root (or (.-WEST_ROOT js/process.env) (.cwd js/process)))

(defn- edn-files [dir]
  (if-not (fs/existsSync dir)
    []
    (mapcat (fn [entry]
              (let [full (path/join dir entry)]
                (cond
                  (.isDirectory (fs/statSync full)) (edn-files full)
                  (str/ends-with? entry ".edn") [full]
                  :else [])))
            (fs/readdirSync dir))))

(defn -main [& args]
  (let [files (sort (edn-files (path/join root "90-docs")))
        failures (keep (fn [f]
                         (try
                           (edn/read-string {:default (fn [_ v] v)} (fs/readFileSync f "utf8"))
                           nil
                           (catch :default e
                             [(str/replace f (str root "/") "") (ex-message e)])))
                       files)]
    (println (str "scanned " (count files) " edn, " (count failures) " fail to parse"))
    (when-not (some #{"--count"} args)
      (doseq [[f m] failures]
        (println (str "  " f "\n      " m))))))

(apply -main *command-line-args*)

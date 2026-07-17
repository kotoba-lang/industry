#!/usr/bin/env nbb
;; Promote tasks-complex.edn require/-main forms into tasks.edn as nbb -m commands.
;;
;;   nbb scripts/wire_tasks_complex.cljs --repo /path/to/repo
;;
;; Patterns handled:
;;   (do (require 'foo.bar) (apply (resolve 'foo.bar/-main) *command-line-args*))
;;   (do (require 'foo.bar) (apply (resolve 'foo.bar/-some-fn) *command-line-args*))
;;
;; Emits:
;;   nbb --classpath <from nbb.edn paths> -e "(require 'ns) (apply (resolve 'ns/fn) *command-line-args*)"
;; Shell forms with {:dir ...} python kept in complex.
;; Remaining unpromoted stay in tasks-complex.edn.
(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[scripts.nbb-compat :refer [slurp spit exit]])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))

(defn- parse-args [args]
  (loop [xs (vec args) m {:repo nil}]
    (cond (empty? xs) m
          (= "--repo" (first xs)) (recur (subvec xs 2) (assoc m :repo (second xs)))
          :else (recur (subvec xs 1) m))))

(defn- classpath-from-nbb-edn [repo]
  (let [p (.join path repo "nbb.edn")]
    (if (.existsSync fs p)
      (let [edn (edn/read-string (slurp p))
            paths (or (:paths edn) [])]
        (str/join ":" paths))
      "src:test")))

(defn- promote-form
  "Return {:cmd [...] :pass-args? true} or nil."
  [form-str cp]
  (let [s (str/replace form-str #"\\\"" "\"")]
    (cond
      ;; (do (require 'ns) (apply (resolve 'ns/-main) *command-line-args*))
      (re-find #"\(do \(require '([A-Za-z0-9_.*+-]+)\) \(apply \(resolve '([A-Za-z0-9_.*+/-]+)\) \*command-line-args\*\)\)" s)
      (let [[_ ns-name var-name]
            (re-find #"\(do \(require '([A-Za-z0-9_.*+-]+)\) \(apply \(resolve '([A-Za-z0-9_.*+/-]+)\) \*command-line-args\*\)\)" s)
            ;; if var is ns/-main, prefer -m
            main? (str/ends-with? var-name "/-main")]
        (if main?
          {:cmd ["nbb" "--classpath" cp "-m" ns-name] :pass-args? true}
          {:cmd ["nbb" "--classpath" cp "-e"
                 (str "(require '" ns-name ") "
                      "(apply (resolve '" var-name ") "
                      "*command-line-args*)")]
           :pass-args? true}))
      ;; (apply shell "python3" ...) keep complex
      (re-find #"shell" s) nil
      :else nil)))

(defn wire! [repo]
  (let [complex-path (.join path repo "scripts/tasks-complex.edn")
        tasks-path (.join path repo "scripts/tasks.edn")
        cp (classpath-from-nbb-edn repo)]
    (when-not (.existsSync fs complex-path)
      (println "no tasks-complex.edn")
      (exit 0))
    (let [complex (edn/read-string (slurp complex-path))
          tasks (if (.existsSync fs tasks-path)
                  (edn/read-string (slurp tasks-path))
                  {})
          promoted (atom {})
          remaining (atom {})]
      (doseq [[k v] complex]
        (let [form (:form v)
              cmd (when form (promote-form form cp))]
          (if cmd
            (swap! promoted assoc k (cond-> cmd
                                      (:doc v) (assoc :doc (:doc v))))
            (swap! remaining assoc k v))))
      (let [new-tasks (merge tasks @promoted)]
        (spit tasks-path
              (str ";; tasks.edn — shell + nbb -m promotions (ADR-2607173000)\n"
                   (pr-str new-tasks) "\n"))
        (spit complex-path
              (str ";; tasks-complex.edn — remaining hand-port (ADR-2607173000)\n"
                   (pr-str @remaining) "\n"))
        (println "repo" repo)
        (println "  promoted" (count @promoted) "remaining" (count @remaining))))))

(let [raw (vec *command-line-args*)
      raw (if (and (seq raw) (str/includes? (str (first raw)) "wire_tasks_complex"))
            (subvec raw 1) raw)
      opts (parse-args raw)]
  (when-not (:repo opts)
    (println "usage: wire_tasks_complex.cljs --repo <path>")
    (exit 2))
  (wire! (:repo opts)))

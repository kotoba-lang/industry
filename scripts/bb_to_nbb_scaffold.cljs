#!/usr/bin/env nbb
;; Classify child-repo bb.edn files and emit nbb replacements for mechanical patterns.
;;
;;   nbb scripts/bb_to_nbb_scaffold.cljs classify [--root orgs]
;;   nbb scripts/bb_to_nbb_scaffold.cljs emit --repo orgs/kotoba-lang/com-aave [--dry-run]
;;   nbb scripts/bb_to_nbb_scaffold.cljs emit-batch --class sci-test [--root orgs] [--limit 10] [--dry-run]
;;
;; Classes:
;;   :shell-only  — tasks only shell/clojure/npx (no :requires run-tests)
;;   :sci-test    — single (or multi) clojure.test/run-tests + System/exit scaffold
;;   :complex     — needs hand port (large task map, :deps, load-file, etc.)
(ns scripts.bb-to-nbb-scaffold
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [scripts.nbb-compat :as compat :refer [slurp spit exit sh]]))

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))

(def skip-dir-names
  #{"node_modules" ".git" ".west" "target" "out" ".shadow-cljs" "dist" "build"
    ".cpcache" "annex" ".claude" "worktrees" "_wt-pr427" ".gitlibs"})

(defn- find-bb-edn
  "West project roots by default: orgs/<org>/<repo>/bb.edn plus one nested
  level (e.g. clj/bb.edn). When root is already a single repo, scan shallowly."
  [root]
  (let [acc (atom [])
        ;; If root looks like the monorepo orgs/ tree, only scan two levels of
        ;; org/repo (+ one nested). Otherwise shallow walk max-depth 3.
        orgs-tree? (or (str/ends-with? root "/orgs")
                       (str/ends-with? root "/orgs/")
                       (= (.basename path root) "orgs"))]
    (if orgs-tree?
      (let [orgs (try (.readdirSync fs root) (catch :default _ #js []))]
        (doseq [org orgs
                :let [org-path (.join path root org)]
                :when (try (.isDirectory (.statSync fs org-path)) (catch :default _ false))]
          (let [repos (try (.readdirSync fs org-path) (catch :default _ #js []))]
            (doseq [repo repos
                    :let [repo-path (.join path org-path repo)]
                    :when (try (.isDirectory (.statSync fs repo-path)) (catch :default _ false))]
              (let [bb (.join path repo-path "bb.edn")]
                (when (.existsSync fs bb) (swap! acc conj bb)))
              ;; one nested level: clj/bb.edn, scripts/bb.edn, etc.
              (let [children (try (.readdirSync fs repo-path) (catch :default _ #js []))]
                (doseq [c children
                        :let [cp (.join path repo-path c)
                              bb (.join path cp "bb.edn")]
                        :when (and (try (.isDirectory (.statSync fs cp)) (catch :default _ false))
                                   (not (contains? skip-dir-names c))
                                   (not (str/starts-with? c "."))
                                   (.existsSync fs bb))]
                  (swap! acc conj bb)))))))
      (let [max-depth 3]
        (letfn [(walk [dir depth]
                  (when (<= depth max-depth)
                    (let [entries (try (.readdirSync fs dir #js {:withFileTypes true})
                                       (catch :default _ #js []))]
                      (doseq [ent entries]
                        (let [name (.-name ent)
                              p (.join path dir name)]
                          (cond
                            (contains? skip-dir-names name) nil
                            (str/starts-with? name ".") nil
                            (.isDirectory ent) (walk p (inc depth))
                            (= name "bb.edn") (swap! acc conj p)))))))]
          (walk root 0))))
    @acc))

(defn- read-bb [p]
  (try
    (edn/read-string (slurp p))
    (catch :default e
      {:__read-error (str e)})))

(defn- task-forms [bb]
  (vals (:tasks bb)))

(defn- form-str [x]
  (pr-str x))

(defn- sci-test-nses [bb]
  (let [forms (task-forms bb)
        nss (atom [])]
    (doseq [t forms]
      (let [s (form-str t)]
        (when (and (str/includes? s "run-tests")
                   (str/includes? s "System/exit"))
          (doseq [[_ ns-name] (re-seq #"'([A-Za-z0-9_.*+-]+)" s)]
            (when (str/includes? ns-name "test")
              (swap! nss conj ns-name))))))
    (vec (distinct @nss))))

(defn- shell-only? [bb]
  (and (map? bb)
       (seq (:tasks bb))
       (every? (fn [t]
                 (let [s (form-str t)]
                   (and (or (str/includes? s "shell")
                            (str/includes? s "clojure")
                            (str/includes? s "npx")
                            (str/includes? s "nbb"))
                        (not (str/includes? s "run-tests"))
                        (not (str/includes? s ":requires")))))
               (task-forms bb))))

(defn- sci-test? [bb]
  (and (map? bb)
       (seq (sci-test-nses bb))
       ;; no heavy :deps (git/local) — those are complex
       (empty? (:deps bb))
       ;; keep small task maps mechanical
       (<= (count (:tasks bb)) 3)))

(defn classify-one [p]
  (let [bb (read-bb p)
        class (cond
                (:__read-error bb) :unreadable
                (sci-test? bb) :sci-test
                (shell-only? bb) :shell-only
                :else :complex)
        nses (when (= class :sci-test) (sci-test-nses bb))]
    {:path p
     :class class
     :test-nses nses
     :task-count (count (:tasks bb))
     :has-deps (boolean (seq (:deps bb)))
     :paths (:paths bb)}))

(defn classify-all [root]
  (mapv classify-one (find-bb-edn root)))

(defn- nbb-edn-content [paths]
  (str ";; Generated by scripts/bb_to_nbb_scaffold.cljs (ADR-2607173000).\n"
       ";; Classpath for nbb. Prefer `nbb -m …` / package.json scripts over bb.edn.\n"
       (pr-str {:paths (vec (or (seq paths) ["src" "test"]))})
       "\n"))

(defn- package-json-sci [nses]
  ;; nbb -e needs :as alias; bare clojure.test/run-tests does not resolve after
  ;; (require 'clojure.test) the way it does on JVM/bb.
  (let [req-nses (str/join " " (map #(str "'" %) nses))
        run-nses (str/join " " (map #(str "'" %) nses))
        test-cmd (str "nbb --classpath src:test -e "
                      "\"(require '[clojure.test :as t] " req-nses ") "
                      "(let [r (t/run-tests " run-nses ")] "
                      "(.exit js/process (if (pos? (+ (:fail r) (:error r))) 1 0)))\"")]
    (str "{\n"
         "  \"private\": true,\n"
         "  \"scripts\": {\n"
         "    \"test\": " (pr-str test-cmd) "\n"
         "  }\n"
         "}\n")))

(defn- package-json-shell [bb]
  ;; Map common shell tasks to npm scripts when we can parse them.
  (let [tasks (:tasks bb)
        scripts
        (into {}
              (for [[k t] tasks
                    :let [s (form-str t)
                          cmd (cond
                                (str/includes? s "clojure -M:test") "clojure -M:test"
                                (str/includes? s "clojure -M:dev:test") "clojure -M:dev:test"
                                (str/includes? s "shadow-cljs watch") "npx shadow-cljs watch app"
                                (str/includes? s "shadow-cljs release") "npx shadow-cljs release app"
                                :else nil)]
                    :when cmd]
                [(name k) cmd]))]
    (when (seq scripts)
      (str "{\n  \"private\": true,\n  \"scripts\": {\n"
           (str/join ",\n"
                     (map (fn [[k v]] (str "    " (pr-str k) ": " (pr-str v))) scripts))
           "\n  }\n}\n"))))

(defn emit-repo! [repo-dir {:keys [dry-run?]}]
  (let [bb-path (.join path repo-dir "bb.edn")
        info (classify-one bb-path)
        bb (read-bb bb-path)]
    (when-not (.existsSync fs bb-path)
      (binding [*out* *err*] (println "no bb.edn at" bb-path))
      (exit 1))
    (println "emit" (:class info) repo-dir)
    (case (:class info)
      :sci-test
      (let [nbb-edn (nbb-edn-content (or (:paths info) ["src" "test"]))
            pkg (package-json-sci (:test-nses info))]
        (if dry-run?
          (do (println "--- nbb.edn ---\n" nbb-edn)
              (println "--- package.json ---\n" pkg)
              (println "would delete" bb-path))
          (do (spit (.join path repo-dir "nbb.edn") nbb-edn)
              (when-not (.existsSync fs (.join path repo-dir "package.json"))
                (spit (.join path repo-dir "package.json") pkg))
              (.unlinkSync fs bb-path)
              (println "wrote nbb.edn; deleted bb.edn"))))
      :shell-only
      (let [pkg (package-json-shell bb)]
        (if dry-run?
          (do (println "--- package.json ---\n" (or pkg "(no parseable scripts)"))
              (println "would delete" bb-path))
          (do (when (and pkg (not (.existsSync fs (.join path repo-dir "package.json"))))
                (spit (.join path repo-dir "package.json") pkg))
              (.unlinkSync fs bb-path)
              (println "deleted bb.edn; package.json" (if pkg "written/kept" "unchanged")))))
      (do (binding [*out* *err*]
            (println "skip complex/unreadable:" (:class info) bb-path))
          (exit 2)))))

(defn -main [& args]
  (let [args (vec args)
        cmd (first args)
        opts (loop [xs (vec (rest args)) m {:root "orgs" :dry-run? false :limit nil :class nil :repo nil}]
               (cond
                 (empty? xs) m
                 (= "--root" (first xs)) (recur (subvec xs 2) (assoc m :root (second xs)))
                 (= "--repo" (first xs)) (recur (subvec xs 2) (assoc m :repo (second xs)))
                 (= "--class" (first xs)) (recur (subvec xs 2) (assoc m :class (keyword (second xs))))
                 (= "--limit" (first xs)) (recur (subvec xs 2) (assoc m :limit (js/parseInt (second xs) 10)))
                 (= "--dry-run" (first xs)) (recur (subvec xs 1) (assoc m :dry-run? true))
                 :else (recur (subvec xs 1) m)))]
    (case cmd
      "classify"
      (let [rows (classify-all (:root opts))
            by (group-by :class rows)]
        (println "total" (count rows))
        (doseq [c [:sci-test :shell-only :complex :unreadable]]
          (println (name c) (count (get by c []))))
        (doseq [r (take 30 (get by :complex []))]
          (println " complex" (:path r) "tasks=" (:task-count r) "deps=" (:has-deps r))))
      "emit"
      (emit-repo! (:repo opts) opts)
      "emit-batch"
      (let [rows (filter #(= (:class %) (or (:class opts) :sci-test))
                         (classify-all (:root opts)))
            rows (cond->> rows
                   (:limit opts) (take (:limit opts)))]
        (doseq [r rows]
          (let [repo (.dirname path (:path r))]
            (try
              (emit-repo! repo opts)
              (catch :default e
                (binding [*out* *err*] (println "FAIL" repo e)))))))
      (do (println "usage: bb_to_nbb_scaffold.cljs classify|emit|emit-batch …")
          (exit 2)))))

;; Only auto-run when this file is the entry script (not when required as a lib).
(when (and (string? *file*)
           (or (str/ends-with? *file* "bb_to_nbb_scaffold.cljs")
               (str/ends-with? *file* "bb-to-nbb-scaffold")))
  (apply -main
         (let [args (vec *command-line-args*)
               script? (when (seq args)
                         (or (str/ends-with? (first args) "bb_to_nbb_scaffold.cljs")
                             (str/ends-with? (first args) "bb-to-nbb-scaffold")))]
           (if script? (rest args) args))))

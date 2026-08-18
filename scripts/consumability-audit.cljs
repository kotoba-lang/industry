#!/usr/bin/env nbb
(ns consumability-audit
  "Can each kotoba-lang library build a classpath outside this workspace?

  A library whose :deps use :local/root cannot: tools.deps resolves \"../sibling\"
  relative to the CONSUMER's gitlibs directory, where nothing of the sort exists. The
  repositories say fork me, deploy me, sell it -- and a forker's first command fails.

  THIS READS THE DEFAULT BRANCH, NOT THE LOCAL CHECKOUT, and that distinction is the
  reason the script exists. Scanning orgs/*/deps.edn gives a wrong answer because those
  working copies drift: kotoba-lang/phone reads as unconsumable locally and has been
  fixed on main since 2026-07-30, because that checkout predates the fix. A local scan
  said 115 unconsumable; the default branches say 85.

  Three classes, and only the first is a consumability defect:

    :deps-local-root   :local/root in :deps -- the LIBRARY cannot be consumed
    :alias-local-root  only in an alias (:test/:lint/:dev) -- the library is fine; only
                       its own development setup wants the workspace. A consumer never
                       runs someone else's :test alias.
    :clean             neither

  usage:
    nbb scripts/consumability-audit.cljs <out.edn> [start] [limit]

  Measured 2026-07-31 over 1,927 kotoba-lang projects in west.yml:
    clean 1629 | deps-local-root 85 | alias-local-root 74 | no deps.edn 137 | unparseable 2"
  (:require ["fs" :as fs] ["child_process" :as cp] [clojure.edn :as edn] [clojure.string :as str]))

(def names
  (->> (str/split-lines (fs/readFileSync "manifest/west.yml" "utf8"))
       (keep (fn [l] (let [t (str/trim l)]
                       (when (str/starts-with? t "path: orgs/kotoba-lang/")
                         (subs t (count "path: orgs/kotoba-lang/"))))))
       distinct vec))

(defn- fetch [name]
  (try (let [out (cp/execSync
                  (str "gh api repos/kotoba-lang/" name "/contents/deps.edn "
                       "-H 'Accept: application/vnd.github.raw' 2>/dev/null")
                  #js {:maxBuffer 20000000})]
         (str out))
       (catch :default _ nil)))

(defn- classify [text]
  (let [d (try (edn/read-string text) (catch :default _ nil))]
    (cond
      (nil? text) :no-deps-file
      (not (map? d)) :unparseable
      (some #(and (map? %) (contains? % :local/root)) (vals (:deps d))) :deps-local-root
      (some (fn [[_ a]] (some #(and (map? %) (contains? % :local/root))
                              (concat (vals (:extra-deps a)) (vals (:override-deps a))
                                      (vals (:replace-deps a)))))
            (:aliases d)) :alias-local-root
      :else :clean)))

(def out-file (nth *command-line-args* 0))
(def start (js/parseInt (or (nth *command-line-args* 1 nil) "0")))
(def limit (js/parseInt (or (nth *command-line-args* 2 nil) "99999")))
(def batch (vec (take limit (drop start names))))
(println "kotoba-lang projects in west.yml:" (count names) " scanning:" (count batch))
(let [results (atom [])]
  (doseq [[i n] (map-indexed vector batch)]
    (when (zero? (mod i 100)) (println "  " i "/" (count batch)))
    (swap! results conj {:name n :class (classify (fetch n))}))
  (fs/writeFileSync out-file (pr-str @results))
  (println "\ntotals:" (pr-str (frequencies (map :class @results)))))

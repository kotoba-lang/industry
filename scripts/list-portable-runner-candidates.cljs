#!/usr/bin/env nbb
;; The first tranche of ADR-2608170400 (step 1 of 3: candidates), narrowed to what can be verified without
;; a classpath: CLEAN repos (portable tests, no ClojureScript runner, no JVM-only
;; token in the tests) that ALSO have no cross-repo dependency their test path
;; needs. Those run under `nbb --classpath src:test` with nothing else set up.
;;
;; Emits one line per repo: path, test-namespace count, then the namespaces.
;;
;; Step 1 of the tranche pipeline. The namespace column is ADVISORY ONLY --
;; sweep-portable-runners.cljs re-derives it from the default-branch tree,
;; because this script reads local checkouts and one of them was seven
;; namespaces behind main (network-awai/app-hyakka, which produced a 2-of-9
;; runner that had to be reverted in 5416b0d).
;;
;;   1. nbb --classpath ".:scripts/nbb_compat" scripts/list-portable-runner-candidates.cljs > candidates.tsv
;;   2. CLEAN_TSV=candidates.tsv SWEEP_WORKDIR=/tmp/sweep \
;;        nbb --classpath ".:scripts/nbb_compat" scripts/sweep-portable-runners.cljs > sweep.tsv
;;   3. SWEEP_TSV=sweep.tsv SWEEP_WORKDIR=/tmp/sweep LEDGER=land.tsv \
;;        nbb --classpath ".:scripts/nbb_compat" scripts/land-portable-runners.cljs
(require '[clojure.string :as str]
         '[clojure.edn :as edn])

(def fs (js/require "node:fs"))
(def node-path (js/require "node:path"))
(def root (.cwd js/process))
(defn full [p] (.join node-path root p))
(defn read-text [p] (try (.readFileSync fs p "utf8") (catch :default _ nil)))

(def prune-dirs
  #{"node_modules" ".git" "target" ".cpcache" ".shadow-cljs" "out" "dist"
    ".datalad" ".claude" "vendor"})

(defn walk [dir]
  (let [acc (volatile! [])]
    (letfn [(go [d depth]
              (when (< depth 12)
                (doseq [e (try (.readdirSync fs d #js {:withFileTypes true})
                               (catch :default _ #js []))]
                  (let [nm (.-name e) p (.join node-path d nm)]
                    (cond
                      (.isDirectory e)
                      (when-not (or (prune-dirs nm) (str/starts-with? nm "."))
                        (go p (inc depth)))
                      (.isFile e) (vswap! acc conj p))))))]
      (go dir 0))
    @acc))

;; The token list is empirical: each of the last four was added because the
;; sweep's LOAD-FAIL message named it. `format` is JVM clojure.core only, and
;; `Long/parseLong` and friends are boxed-numeric statics with no cljs form.
(def jvm-res
  [#"\(\s*byte-array\b" #"\(\s*byte\s" #"\bclojure\.lang\." #"\(:import\b"
   #"\bjava\.[a-z]" #"\bSystem/" #"\bThread/" #"\bclojure\.java\."
   #"\(\s*proxy\b" #"\.getDeclared|\bClass/forName"
   #"\(\s*format\s" #"\bLong/" #"\bInteger/" #"\bDouble/" #"\bCharacter/"
   #"\bBigDecimal\b|\bBigInteger\b" #"\(\s*with-open\b" #"\bThrowable\b"])

(defn ns-of [t] (second (re-find #"\(ns\s+\^?[:a-z{}\s]*?([a-zA-Z][a-zA-Z0-9._<>*+!?-]*)" t)))

(def registered
  (->> (str/split-lines (read-text (full "manifest/west.yml")))
       (keep #(second (re-find #"^\s+path:\s*(\S+)\s*$" %)))
       (into (sorted-set))))

(def out (atom []))
(def tally (atom {:clean 0 :clean-selfcontained 0}))

(doseq [rel registered]
  (let [abs (full rel)]
    (when (try (.isDirectory (.statSync fs abs)) (catch :default _ false))
      (let [rels (map #(.relative node-path abs %) (walk abs))
            by-rel (zipmap rels (map #(.join node-path abs %) rels))
            txt (fn [r] (read-text (by-rel r)))
            portable? #(re-find #"\.clj[sc]$" %)
            deftest? (fn [r] (re-find #"\(\s*deftest" (or (txt r) "")))
            tests (filter #(and (portable? %) (deftest? %)) rels)
            runner? (some (fn [r] (and (portable? r) (not (deftest? r))
                                       (re-find #"\(\s*[a-zA-Z0-9._/-]*run-(all-)?tests"
                                                (or (txt r) ""))))
                          rels)]
        (when (and (seq tests) (not runner?))
          (let [clean? (not-any? (fn [r] (let [s (or (txt r) "")]
                                           (some #(re-find % s) jvm-res)))
                                 tests)]
            (when clean?
              (swap! tally update :clean inc)
              ;; cross-repo deps: any :git/* or :local/root in the top-level :deps
              ;; or in the :test alias -- those are what `--classpath src:test`
              ;; cannot reach.
              (let [de (get by-rel "deps.edn")
                    form (when de (try (edn/read-string {:default (fn [_ v] v)}
                                                        (or (read-text de) ""))
                                       (catch :default _ nil)))
                    coords (concat (vals (:deps form))
                                   (mapcat (fn [a] (when (map? a)
                                                     (concat (vals (:extra-deps a))
                                                             (vals (:deps a)))))
                                           (vals (:aliases form))))
                    ;; A maven jar is as unreachable from nbb as a sibling repo, so
                    ;; it counts too -- the first pass only looked for git/local and
                    ;; let `cheshire.core` through, which is why 20 of 37 candidates
                    ;; died on LOAD-FAIL. The allowlist is what sits in nearly every
                    ;; deps.edn here and is irrelevant to an nbb run.
                    harmless #{"org.clojure/clojure" "org.clojure/clojurescript"
                               "io.github.cognitect-labs/test-runner"
                               "clj-kondo/clj-kondo" "thheller/shadow-cljs"
                               "lambdaisland/kaocha" "org.clojure/test.check"}
                    named (concat (keys (:deps form))
                                  (mapcat (fn [a] (when (map? a)
                                                    (concat (keys (:extra-deps a))
                                                            (keys (:deps a)))))
                                          (vals (:aliases form))))
                    cross (count (remove #(harmless (str %)) named))
                    nss (sort (keep #(some-> (txt %) ns-of) tests))]
                (when (and (zero? cross) (seq nss) (= (count nss) (count tests)))
                  (swap! tally update :clean-selfcontained inc)
                  (swap! out conj (str rel "\t" (count nss) "\t" (str/join " " nss))))))))))))

(binding [*out* *err*]
  (println "CLEAN:" (:clean @tally)
           "| CLEAN and self-contained (no git/local dep anywhere):" (:clean-selfcontained @tally)))
(doseq [l (sort @out)] (println l))

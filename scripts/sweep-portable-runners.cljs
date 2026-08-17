#!/usr/bin/env nbb
;; For each CLEAN self-contained repo from clean.tsv: fetch, extract its
;; default-branch tree, generate a portable nbb runner, run it with that
;; project's own src/test on the classpath, and record what happened.
;;
;; VERIFIES before anything is landed. ADR-2608170400 says CLEAN means "the tests
;; can load", not "they pass", so the yield of this sweep is the number that
;; matters and it is measured rather than assumed.
;;
;; Three corrections the first version needed. Each had reported a failure as a
;; benign category -- the exact class this line of work exists to catch,
;; committed by the tool hunting it:
;;
;;   1. It never fetched. `<remote>/main` does not exist in a checkout nobody has
;;      fetched, `git archive` wrote nothing, `tar` failed, and the empty
;;      directory was reported as `NO-TEST-DIR-ON-MAIN` -- 32 of 37 rows.
;;   2. It assumed tests live in `test/`. They also live in `clj/test/` and
;;      `lg/test/`, so the entry and classpath are derived from where the test
;;      files actually are.
;;   3. It did not check the extract's exit code, which is why (1) was invisible.
;;      EXTRACT-FAIL is now its own outcome.
;;
;;   CLEAN_TSV=… SWEEP_WORKDIR=… nbb --classpath ".:scripts/nbb_compat" sweep-portable-runners.cljs
(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))
(def node-path (js/require "node:path"))
(def tsv (.-CLEAN_TSV js/process.env))
(def workdir (.-SWEEP_WORKDIR js/process.env))
(def root "/Users/junkawasaki/github/com-junkawasaki")

(defn sh [cmd opts]
  (try {:out (str (.execSync cp cmd (clj->js (merge {:encoding "utf8" :maxBuffer 64000000
                                                     :stdio ["pipe" "pipe" "pipe"]}
                                                    opts)))) :exit 0}
       (catch :default e
         {:out (str (or (.-stdout e) "") (or (.-stderr e) "")) :exit (or (.-status e) 1)})))

(defn runner-src [nss cp-str entry]
  (str "#!/usr/bin/env nbb\n"
       ";; The portable suite on nbb — no build step, no JVM.\n"
       ";;\n"
       ";; Until this file existed, every namespace below ran under\n"
       ";; `clojure -M:test` and nowhere else, so a defect in the ClojureScript\n"
       ";; half of a `.cljc` was invisible here. This project needs no classpath\n"
       ";; beyond its own source:\n"
       ";;\n"
       ";;   nbb --classpath " cp-str " " entry "\n"
       ";;\n"
       ";; Every `deftest`-bearing portable namespace has to be named here AND in\n"
       ";; the `run-tests` call: requiring a test namespace registers its vars,\n"
       ";; only `run-tests` runs them, and a runner naming a subset prints the\n"
       ";; same `Ran N tests` shape as one naming all of them (ADR-2608170300).\n"
       ";; `scripts/verify-cljs-runner-completeness.cljs` checks this file\n"
       ";; against the tree.\n"
       "(require '[cljs.test :as t]\n"
       (str/join "\n" (map #(str "         '[" % "]") nss)) ")\n\n"
       "(defmethod t/report [:cljs.test/default :end-run-tests] [m]\n"
       "  (when-not (t/successful? m) (set! (.-exitCode js/process) 1)))\n\n"
       "(t/run-tests " (str/join "\n              " (map #(str "'" %) nss)) ")\n"))

(defn find-files [dir]
  (let [acc (volatile! [])]
    (letfn [(go [d depth]
              (when (< depth 10)
                (doseq [e (try (.readdirSync fs d #js {:withFileTypes true})
                               (catch :default _ #js []))]
                  (let [nm (.-name e) p (.join node-path d nm)]
                    (cond
                      (.isDirectory e)
                      (when-not (or (#{"node_modules" ".git" "target" "out"} nm)
                                    (str/starts-with? nm "."))
                        (go p (inc depth)))
                      (.isFile e) (vswap! acc conj p))))))]
      (go dir 0))
    @acc))

(def rows
  (->> (str/split-lines (.readFileSync fs tsv "utf8"))
       (remove str/blank?)
       (map #(str/split % #"\t"))
       ;; a row without all three fields is the summary line, not a repo
       (filter #(= 3 (count %)))))

(.mkdirSync fs workdir #js {:recursive true})
(println "repo\tnss\tclasspath\tresult")

(doseq [[rel _n nss-str] rows]
  (let [nss (str/split (str/trim nss-str) #"\s+")
        dir (.join node-path workdir (.replace rel (js/RegExp. "/" "g") "_"))
        src (.join node-path root rel)
        rem1 (first (str/split-lines (str/trim (:out (sh (str "git -C " src " remote") {})))))
        _ (sh (str "git -C " src " fetch " rem1 " -q") {})
        br (first (filter #(zero? (:exit (sh (str "git -C " src " rev-parse --verify -q "
                                                 rem1 "/" %) {})))
                          ["main" "master"]))]
    (.rmSync fs dir #js {:recursive true :force true})
    (.mkdirSync fs dir #js {:recursive true})
    ;; An archived repo is read-only: the push and the merge both fail, and GitHub
    ;; reports 403 "Must have admin rights to Repository", which reads like a
    ;; permissions bug rather than the actual reason. cloud-itonami/open-robo cost
    ;; a landing attempt this way.
    (let [arch (str/trim (:out (sh (str "gh api repos/"
                                        (second (str/split rel #"/")) "/"
                                        (nth (str/split rel #"/") 2)
                                        " --jq .archived") {})))]
      (when (= "true" arch)
        (println (str rel "\t-\t-\tARCHIVED (read-only on GitHub)"))))
    (if-not br
      (println (str rel "\t" (count nss) "\t-\tNO-DEFAULT-BRANCH (remote " rem1 ")"))
      (let [arch2 (str/trim (:out (sh (str "gh api repos/"
                                           (second (str/split rel #"/")) "/"
                                           (nth (str/split rel #"/") 2)
                                           " --jq .archived") {})))
            ex (when-not (= "true" arch2)
                 (sh (str "git -C " src " archive " rem1 "/" br " | tar -x -C " dir)
                     {:shell "/bin/bash"}))]
       (when ex
        (if (or (pos? (:exit ex)) (empty? (vec (.readdirSync fs dir))))
          (println (str rel "\t" (count nss) "\t-\tEXTRACT-FAIL "
                        (str/replace (str/trim (:out ex)) "\n" " ")))
          (let [files (map #(.relative node-path dir %) (find-files dir))
                tests (filter (fn [f]
                                (and (re-find #"\.clj[sc]$" f)
                                     (re-find #"\(\s*deftest"
                                              (or (try (.readFileSync fs (.join node-path dir f) "utf8")
                                                       (catch :default _ nil)) ""))))
                              files)
                ;; the directory component that ends in test/ or tests/
                ;; The namespace list is re-derived HERE, from the tree that was
                ;; just extracted from the default branch. Taking it from the TSV
                ;; is what produced a 2-of-9 runner for network-awai/app-hyakka:
                ;; the scan had read a local checkout seven namespaces behind main,
                ;; and the generated runner printed `Ran 20 tests ... 0 failures`
                ;; while running two ninths of the suite. Reverted in 5416b0d.
                main-nss (sort (distinct (keep (fn [f]
                                                 (some-> (try (.readFileSync fs (.join node-path dir f) "utf8")
                                                              (catch :default _ nil))
                                                         (as-> t (second (re-find #"\(ns\s+\^?[:a-z{}\s]*?([a-zA-Z][a-zA-Z0-9._<>*+!?-]*)" t)))))
                                               tests)))
                tdir (first (keep #(second (re-find #"^((?:.*/)?tests?)/" %)) tests))
                proj (when tdir (let [p (.dirname node-path tdir)] (when-not (= p ".") p)))
                cpath (if proj (str proj "/src:" tdir) (str "src:" (or tdir "test")))
                entry (str (or tdir "test") "/run_portable.cljs")]
            (cond
              (empty? tests) (println (str rel "\t" (count nss) "\t-\tNO-DEFTEST-ON-MAIN"))
              (nil? tdir) (println (str rel "\t" (count nss) "\t-\tTESTS-NOT-UNDER-A-TEST-DIR "
                                        (first tests)))
              :else
              (do
                (.writeFileSync fs (.join node-path dir entry) (runner-src main-nss cpath entry) "utf8")
                (let [{:keys [out exit]} (sh (str "timeout 240 nbb --classpath " cpath " " entry)
                                             {:cwd dir :shell "/bin/bash"})
                      ran (second (re-find #"(Ran \d+ tests containing \d+ assertions)" out))
                      bad (second (re-find #"(\d+ failures, \d+ errors)" out))
                      err (or (second (re-find #"(Unable to resolve symbol: \S+)" out))
                              (second (re-find #"(Could not find namespace: \S+)" out))
                              (second (re-find #"Cannot find module '([^']+)'" out)))]
                  (println (str rel "\t" (count main-nss) "\t" cpath "\t"
                                (cond
                                  ;; The floor the fleet's :nbb-test gate already has
                                  ;; (`grep -qE 'Ran 0 tests' && fail … 94`) and this
                                  ;; sweep did not: a run of nothing exits 0 and was
                                  ;; reported PASS for gftdcojp/defence.
                                  (re-find #"Ran 0 tests" (or ran "")) (str "ZERO-TESTS " ran)
                                  (and ran (zero? exit)) (str "PASS " ran)
                                  ran (str "RED " ran " / " bad " / exit " exit)
                                  :else (str "LOAD-FAIL "
                                             (or err (str/join " | "
                                                               (take-last 2 (remove str/blank?
                                                                                    (str/split-lines out)))))))))))))))))))

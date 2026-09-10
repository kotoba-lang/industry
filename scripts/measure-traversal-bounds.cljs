#!/usr/bin/env nbb
;; scripts/measure-traversal-bounds.cljs — where the read paths are bounded,
;; and where the query plane's read path is not.
;;
;; root ADR-2609108000 listed IPLD traversal bounds as UNMEASURED, and
;; unmeasured is not clean. This measures them by EXERCISING each seam, not
;; by grepping for the word "limit" -- a seam that names a limit and defaults
;; it to unlimited reads exactly like one that enforces it.
;;
;; Returns a COUNT of failures. Exits 2 if its own control fails.
;;
;; Run (from the superproject root):
;;   nbb --classpath "<workspace src dirs>" scripts/measure-traversal-bounds.cljs
;;   nbb --classpath "..." scripts/measure-traversal-bounds.cljs --root /path/to/root

(ns measure-traversal-bounds
  (:require [clojure.string :as str]
            ["fs" :as fs]
            [ipld.core :as ipld]
            [ipld.graph :as graph]
            [prolly-tree.core :as pt]
            [prolly-tree.diff :as diff]))

;; Section F reads the west-managed `orgs/` tree, which a task worktree does
;; not have. `--root <dir>` names the checkout that does. Default `.` is right
;; when running from the superproject root; anywhere else it fails closed and
;; says so rather than reporting a pass it did not measure.
(def ^:private root
  (let [args (vec *command-line-args*)
        i (.indexOf args "--root")]
    (if (neg? i) "." (nth args (inc i) "."))))

(def ^:private failures (atom 0))
(def ^:private scanned (atom 0))

(defn- check! [label ok? detail]
  (swap! scanned inc)
  (when-not ok? (swap! failures inc))
  (println (if ok? "  ok  " "  FAIL") label "—" detail))

(defn- thrown-type [f]
  (try (f) nil (catch :default e (:type (ex-data e)))))

;; An in-memory block store, so every read below is a real block read.
(defn- store []
  (let [blocks (atom {})]
    {:put! (fn [cid bytes] (swap! blocks assoc cid bytes) cid)
     :get-fn (fn [cid] (get @blocks cid))
     :count (fn [] (count @blocks))}))

(def ^:private n 400)

(defn- tree []
  (let [{:keys [put! get-fn] :as s} (store)
        entries (mapv (fn [i] [(str "k" (.padStart (str i) 6 "0")) (str "v" i)])
                      (range n))
        root (pt/build-tree put! entries)]
    (assoc s :root root :entries entries)))

(defn -main []
  (println "\n=== C. control — the harness reads a real tree ===")
  (let [{:keys [get-fn root count]} (tree)]
    (check! "the tree has more than one block" (> (count) 1)
            (str (count) " blocks for " n " entries — not a single leaf"))
    (check! "an unbounded prefix scan returns every entry"
            (= n (clojure.core/count (pt/scan-prefix get-fn root "")))
            (str n " entries read back")))

  (when (pos? @failures)
    (println "\nREFUSING to report: the control did not hold.")
    (js/process.exit 2))

  (println "\n=== A. ipld.graph — fail closed, no default ===")
  (println "  The selector traversal refuses to be created without positive")
  (println "  limits. There is no permissive default to fall back on.")
  (doseq [missing [{} {:max-blocks 10} {:max-blocks 10 :max-bytes 10 :max-depth 10}]]
    (check! (str "selection-cursor refuses " (pr-str (keys missing)))
            (= :ipld/invalid-limit
               (thrown-type #(graph/selection-cursor "bafy-root" {:selector :matcher} missing)))
            "throws :ipld/invalid-limit before reading a block"))
  (check! "with all four it admits the work"
          (nil? (thrown-type
                 #(graph/selection-cursor
                   "bafy-root" {:selector :matcher}
                   {:max-blocks 10 :max-bytes 100 :max-depth 3 :max-matches 5})))
          ":max-blocks :max-bytes :max-depth :max-matches")

  (println "\n=== B. prolly-tree.diff — fail closed too ===")
  (let [{:keys [get-fn root]} (tree)]
    (check! "sync-blocks* refuses without limits"
            (= :prolly-tree/invalid-limit
               (thrown-type #(diff/sync-blocks* get-fn nil root {})))
            "throws :prolly-tree/invalid-limit")
    (check! "sync-blocks* refuses a zero limit, not just a missing one"
            (= :prolly-tree/invalid-limit
               (thrown-type #(diff/sync-blocks* get-fn nil root
                                                {:max-blocks 0 :max-bytes 1 :max-reads 1})))
            "0 is not a limit"))

  (println "\n=== D. scan-prefix — the arity the query plane uses is unlimited ===")
  (println "  Same function, two arities. The 4-arity bounds the walk. The")
  (println "  3-arity passes nil, and nil means unlimited -- so the default")
  (println "  on the shorter arity is the absence of a bound.")
  (let [{:keys [get-fn root]} (tree)]
    (check! "3-arity returns the whole tree" (= n (count (pt/scan-prefix get-fn root "")))
            (str n " of " n " — O(database), whatever the caller wanted"))
    (check! "4-arity with a limit stops" (= 5 (count (pt/scan-prefix get-fn root "" 5)))
            "5 of 400")
    (check! "limit 0 returns empty without walking"
            (= 0 (count (pt/scan-prefix get-fn root "" 0))) "documented behaviour")
    ;; Boundary: limit == n must return n, limit == n-1 must return n-1.
    ;; An off-by-one in the countdown moves exactly these two and nothing else.
    (check! "boundary: limit == n returns n" (= n (count (pt/scan-prefix get-fn root "" n)))
            (str "limit " n))
    (check! "boundary: limit == n-1 returns n-1"
            (= (dec n) (count (pt/scan-prefix get-fn root "" (dec n))))
            (str "limit " (dec n))))

  (println "\n=== E. scan-range takes no limit at all ===")
  (let [{:keys [get-fn root]} (tree)]
    (check! "a nil..nil range reads everything"
            (= n (count (pt/scan-range get-fn root nil nil)))
            "the arity is [get-fn root-cid lo hi] — there is no limit to pass"))

  (println "\n=== F. the call sites — which arity the query plane actually uses ===")
  (println "  Exercising the seam says what the function does. This says which")
  (println "  door the deployed read path walks through. Anchors are exact and")
  (println "  the section refuses if one is not found exactly once.")
  (let [sites [["orgs/kotoba-lang/arrangement/src/arrangement/core.cljc"
                "(pt/scan-prefix get-fn root-cid \"\")"
                "snapshot hydrate — empty prefix, no limit, decrypts every leaf"]
               ["orgs/kotoba-lang/arrangement/src/arrangement/source.cljc"
                "(pt/scan-prefix get-fn root (key-prefix blind-fn values))"
                "pattern read — key-range pruned, still no limit"]]]
    (doseq [[path anchor why] sites]
      (let [full (str root "/" path)
            src (try (fs/readFileSync full "utf8") (catch :default _ nil))
            hits (when src (dec (count (str/split src anchor))))]
        (cond
          (nil? src)
          (do (swap! failures inc)
              (println "  FAIL unreadable —" full
                       "— pass --root <superproject checkout> when not running there"))
          (not= 1 hits)
          (do (swap! failures inc)
              (println "  FAIL anchor found" hits "times in" path
                       "— the call site moved; re-read before trusting section F"))
          :else
          (check! (str "3-arity call site: " (last (str/split path #"/"))) true why)))))

  (println "\n---")
  (println (str "SCANNED\t" @scanned))
  (println "FAILURES=" @failures)
  (js/process.exit (if (pos? @failures) 1 0)))

(-main)

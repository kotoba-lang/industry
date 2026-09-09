;; A definition repository that nothing names.
;;
;; Twenty-three kotoba.lang namespaces are one repository per definition
;; (ADR-2609091200). A repo nobody names is not automatically wrong -- four
;; kinds of name are deliberately not carried by the assembled namespace -- so
;; the useful question is not "is it named" but "WHY is it not named". This
;; separates the four expected reasons from the one that is a defect, and the
;; separation is the whole point: without it the answer is nine repos and the
;; real finding is one.
;;
;;   SUPERSEDED    another repo in the family holds the same definition under a
;;                 different name. Measured 2026-09-09: process-iprocess, left
;;                 behind when the protocol was renamed Process.
;;   UNCARRIED     a value var, a macro, a protocol or a record type. The
;;                 assembled namespace cannot carry these -- copying a value
;;                 makes with-redefs a silent no-op, a macro var cannot be
;;                 copied at all, a protocol copy extends nothing, a type is not
;;                 a var. A consumer requires the repo directly.
;;   DEAD-PRIVATE  the source declares it private and the source never calls it.
;;                 That is a finding about the SOURCE, not the split. Measured:
;;                 kotoba.lang.text's byte-index->codepoint-index, one
;;                 occurrence in the file and it is the definition head.
;;   UNREACHABLE   a public function nothing names. THIS is the defect: the
;;                 assembled namespace lost a name and the call site gets a
;;                 symbol that does not resolve, which is silent until called.
;;                 kotoba.coll spent part of a day exporting 7 of 27 this way.
;;
;; Not a fleet gate: a gate ships one repo's tree, and this reads every sibling
;; under orgs/ to find out who names whom.
;;
;; Usage: nbb verify-definition-repo-reachability.cljs [--findings] <orgs-dir>
(require '[clojure.string :as s])

(def fs (js/require "node:fs"))
(def path-mod (js/require "node:path"))
(def args (vec *command-line-args*))
(def findings? (some #(= "--findings" %) args))
(def orgs (or (last (remove #(s/starts-with? % "--") args)) "orgs"))
(def root (.join path-mod orgs "kotoba-lang"))

(defn exists? [p] (.existsSync fs p))
(defn slurp* [p] (try (str (.readFileSync fs p "utf8")) (catch :default _ nil)))

(when-not (exists? root)
  (println (str "REFUSING: " root " is not there. A directory that cannot be read"
                " must not be reported as a tree with nothing wrong in it."))
  (js/process.exit 2))

(def cp (js/require "node:child_process"))

(defn walk
  "Files under dir. Eager, with a vector frontier: the first version grew a lazy
  concat chain per directory and blew the stack on orgs/ -- and it exited 0
  while doing it, which would have read as a clean tree."
  [dir]
  (if-not (exists? dir) []
    (loop [todo [dir] out []]
      (if (empty? todo) out
        (let [x (peek todo) rest' (pop todo)]
          (if-not (exists? x) (recur rest' out)
            (let [st (.statSync fs x)]
              (if (.isDirectory st)
                (if (= ".git" (.basename path-mod x))
                  (recur rest' out)
                  (recur (into rest' (map #(.join path-mod x %) (.readdirSync fs x))) out))
                (recur rest' (conj out x))))))))))

(defn find-files
  "Project files anywhere under a tree. `find` rather than a walk in here: the
  tree is ~46,000 files and the point of this script is elsewhere."
  [dir names]
  (let [expr (s/join " -o " (map #(str "-name " %) names))
        out (try (str (.execSync cp (str "find " dir " -type f \\( " expr " \\) 2>/dev/null")
                                 #js {:encoding "utf8" :maxBuffer 67108864}))
                 (catch :default _ nil))]
    (when (nil? out)
      (println (str "REFUSING: could not list project files under " dir "."))
      (js/process.exit 2))
    (remove s/blank? (s/split-lines out))))

;; Every definition repo, found by the marker the generator writes into each one:
;; "Split out of <source-ns> on". The SOURCE NAMESPACE comes out of the marker
;; rather than from the directory name -- an earlier version built the marker as
;; "kotoba.lang.<family-dir>" and silently missed every family whose source
;; namespace is named differently, json.core among them: 344 repos found where
;; west registers 471.
(def all-dirs (vec (.readdirSync fs root)))

(def def-repos
  (vec (for [d all-dirs
             :let [src (.join path-mod root d "src")
                   files (filter #(re-find #"\.clj[cs]?$" %) (walk src))
                   f (first files)
                   txt (or (slurp* f) "")
                   m (re-find #"Split out of ([a-z][a-zA-Z0-9._*<>=-]*) on" txt)
                   head (re-find #"(?m)^\((def[a-z-]*)\s+(?:\^\S+\s+)*([^\s()]+)" txt)]
             :when (and m head)]
         {:repo d :source-ns (second m) :kind (second head) :name (nth head 2 nil)})))

(when (empty? def-repos)
  (println "REFUSING: no repository carries the generator's marker. Either nothing is"
           " split or this is the wrong tree; neither is a clean result.")
  (js/process.exit 2))

;; EVIDENCE FLOOR, and it has to be non-circular. Comparing against west's
;; registered checkouts counts 2,520 ordinary repositories that were never split
;; and refuses on all of them. What IS checkable without begging the question:
;; every repository an assembled namespace names must be one this file found.
;; If a facade names string-blank and the scan did not classify string-blank as a
;; definition repo, the scan is reporting on a subset and says so.
(def facade-required
  "The NAMESPACES each assembled namespace requires, restricted to its own.

  Counting the repos a facade's deps.edn names counted the family repository
  itself and its external dependencies -- amu, org-ietf-tls -- and refused on
  seventeen things that were never definition repos. A facade requires
  kotoba.<fam>.<segment> for each definition and nothing else with that prefix,
  so the namespaces are exact where the repo names were not."
  (let [acc (atom #{})]
    (doseq [d all-dirs
            :let [src (.join path-mod root d "src")
                  files (filter #(re-find #"\.clj[cs]?$" %) (walk src))
                  f (first (filter #(s/includes? (or (slurp* %) "")
                                                 "Assembled from one repo per definition")
                                   files))]
            :when f
            :let [txt (or (slurp* f) "")
                  target (second (re-find #"\(ns\s+([a-z][a-zA-Z0-9._*<>=-]*)" txt))]]
      (doseq [m (re-seq #"\[([a-z][a-zA-Z0-9._*<>=-]*)\s+:as" txt)]
        (let [ns (second m)]
          (when (s/starts-with? ns (str target "."))
            (swap! acc conj ns)))))
    @acc))

(def source-of
  (into {} (for [ns (distinct (map :source-ns def-repos))
                 :let [rel (str (s/replace (s/replace ns "." "/") "-" "_"))
                       cands (for [d all-dirs
                                   e [".cljc" ".clj" ".cljs"]]
                               (.join path-mod root d "src" (str rel e)))]]
             [ns (or (first (filter exists? cands)) nil)])))

;; every name any project file names, so "named by nothing" means what it says
(def named
  (let [acc (atom #{})]
    (doseq [f (find-files orgs ["deps.edn" "bb.edn"])
            :let [txt (or (slurp* f) "")]]
      (doseq [m (re-seq #"io\.github\.kotoba-lang/([A-Za-z0-9._-]+)" txt)]
        (swap! acc conj (second m)))
      (doseq [m (re-seq #"\.\./([A-Za-z0-9._-]+)" txt)]
        (swap! acc conj (second m))))
    @acc))

(def scanned (atom 0))
(def rows (atom []))
(def by-ns (group-by :source-ns def-repos))

(doseq [{:keys [repo source-ns kind name]} def-repos]
  (swap! scanned inc)
  (let [source (or (slurp* (get source-of source-ns)) "")
        ;; ONE backslash, not two. "\\\\$1" produces a literal backslash in the
        ;; replacement, so the pattern became byte\\-index\\->... and matched
        ;; nothing at all: uses came back 0 and private? false, and the same one
        ;; bug broke both halves of the dead-code test, which then read as a
        ;; missing public function.
        esc (fn [x] (s/replace x #"([*+?^$.\[\]\\(){}|/-])" "\\$1"))
        twin (first (for [{o :repo n :name} (get by-ns source-ns)
                          :when (and (not= o repo) (= n name))]
                      o))
        uncarried? (contains? #{"def" "defonce" "defmacro" "defprotocol" "defrecord"} kind)
        private? (and (seq source)
                      (re-find (re-pattern (str "\\(defn-\\s+" (esc name) "\\b")) source))
        uses (if (seq source) (count (re-seq (re-pattern (esc name)) source)) 99)]
    (when-not (contains? named repo)
      (swap! rows conj
             [(cond twin "SUPERSEDED"
                    uncarried? "UNCARRIED"
                    (and private? (<= uses 1)) "DEAD-PRIVATE"
                    (empty? source) "UNKNOWN-SOURCE"
                    :else "UNREACHABLE")
              repo name kind (or twin "")]))))

(println (str "SCANNED\t" @scanned "\tdefinition repos across "
              (count (distinct (map :source-ns def-repos))) " source namespaces"))
(let [provided (set (for [{:keys [repo]} def-repos
                         f (filter #(re-find #"\.clj[cs]?$" %)
                                   (walk (.join path-mod root repo "src")))
                         :let [ns (second (re-find #"\(ns\s+([a-z][a-zA-Z0-9._*<>=-]*)"
                                                   (or (slurp* f) "")))]
                         :when ns]
                     ns))
      missed (sort (remove provided facade-required))]
  (println (str "REQUIRED-BY-A-FACADE\t" (count facade-required) "\tdefinition namespaces"))
  (when (seq missed)
    (doseq [m (take 12 missed)] (println (str "UNACCOUNTED\t" m)))
    (println (str "REFUSING: " (count missed) " namespace(s) an assembled namespace requires"
                  " are provided by no repository this file found, so it is reporting on a subset."))
    (js/process.exit 2)))
(doseq [[cat r nm kind twin] (sort @rows)]
  (println (str cat "\t" r "\t" nm "\t" kind (when (seq twin) (str "\tsuperseded-by " twin)))))
(let [bad (filter #(= "UNREACHABLE" (first %)) @rows)]
  (when findings?
    (doseq [[_ r nm _ _] bad]
      (println (str "FINDING\thigh\t" r "\tpublic function " nm
                    " is in no assembled namespace and no other repo names it"))))
  (println (str "UNREACHABLE\t" (count bad)))
  (js/process.exit (if (seq bad) 1 0)))

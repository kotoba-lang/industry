#!/usr/bin/env nbb
(ns verify-shadow-source-paths
  "Namespaces a shadow-cljs build requires that NO declared source-path provides.

  The failure this exists for does not look like a failure. It is not a test
  that fails -- the build never gets far enough to run one. `shadow-cljs
  compile` stops at `The required namespace \"x\" is not available`, exit 1,
  and if nothing runs that build nothing ever says so.

  Measured 2026-08-18: `kotoba-lang/kotobase-worker-shell` and
  `net-kotobase/cypher` were both in this state, each missing the same three
  source-paths, each surfacing only after the previous was added. The shell's
  test suite had therefore never executed. Both were found by hand, because
  they were the two repos that session happened to touch.

  What this checks: for every repo whose shadow-cljs.edn declares source-paths,
  build the namespace index those paths actually provide, then walk every
  `(:require ...)` in every file they contain and report the ones nothing
  provides.

  What it deliberately does NOT do: compile. Compiling 198 repos is not a
  check anyone will run. This is a static approximation, and it is honest
  about being one -- see :limits below.

  usage:
    nbb scripts/verify-shadow-source-paths.cljs [<root>] [--all] [--json]

  <root> defaults to the superproject. Without --all only repos declaring a
  RELATIVE sibling path (\"../\") are examined, which is where drift comes
  from: a repo's own src moving is caught by its own compile, a sibling's is
  not."
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

(def argv (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") argv))
              "/Users/junkawasaki/github/com-junkawasaki"))
(def all? (some #{"--all"} argv))
(def json? (some #{"--json"} argv))
(def findings? (some #{"--findings"} argv))

;; Namespaces the compiler and runtime provide. Not from any source-path, so
;; not drift. Kept deliberately short: a prefix that is too broad hides real
;; breakage, which is the failure mode this whole file is about.
(def provided-prefixes
  ["cljs." "clojure." "goog" "shadow." "js." "cljs$" "react" "module$"])

(defn- provided? [ns-sym]
  (let [s (str ns-sym)]
    (or (some #(str/starts-with? s %) provided-prefixes)
        (= s "cljs.core"))))

(defn- slurp* [f] (try (.readFileSync fs f "utf8") (catch :default _ nil)))

(defn- walk-files [dir exts]
  (if-not (.existsSync fs dir)
    []
    (->> (.readdirSync fs dir #js {:withFileTypes true})
         array-seq
         (mapcat (fn [e]
                   (let [p (.join path dir (.-name e))]
                     (cond
                       (= "node_modules" (.-name e)) []
                       (str/starts-with? (.-name e) ".") []
                       (.isDirectory e) (walk-files p exts)
                       (contains? exts (.extname path p)) [p]
                       :else []))))
         vec)))

(def cljs-exts #{".cljs" ".cljc" ".clj"})

(defn- path->ns
  "src/a_b/c.cljs relative to its source-path -> a-b.c"
  [source-path file]
  (-> (.relative path source-path file)
      (str/replace #"\.(cljs|cljc|clj)$" "")
      (str/replace "\\" "/")
      (str/replace "/" ".")
      (str/replace "_" "-")))

(defn- source-paths
  "The :source-paths vector of a shadow-cljs.edn, read textually.

  Textual because nbb has no edn reader here and the shape is uniform: the
  vector is the first [...] after :source-paths and its entries are quoted
  strings. Returns [] if that assumption does not hold, which is reported as
  :unparsed rather than as zero paths -- an unreadable config must not look
  like a clean one."
  [txt-raw]
  (let [txt (str/replace txt-raw #"(?m);;.*$" "")]
   (when-let [i (str/index-of txt ":source-paths")]
    (let [rest- (subs txt i)
          open (str/index-of rest- "[")
          close (str/index-of rest- "]")]
      (when (and open close (< open close))
        (->> (re-seq #"\"([^\"]+)\"" (subs rest- open close))
             (map second)
             vec))))))

;; ── require extraction ───────────────────────────────────────────────────────
;; A regex over the whole file cannot do this. The first version of this script
;; used one and reported 41 of 41 repos broken: it matched regex character
;; classes ("A-Za-z0-9"), the symbols inside :refer vectors (deftest), local
;; bindings, and the java.* imports in a .cljc file's :clj branch. A detector
;; with a 100% false-positive rate is worse than no detector, because someone
;; has to read it before ignoring it.
;;
;; So: find the ns form, walk it with balanced-paren scanning, and take only
;; what is actually a required namespace.

(defn- forms-end
  "Index just past the form starting at `i` (which must be an opening bracket).
  String- and comment-aware, because both contain unbalanced brackets."
  [s i]
  (loop [j i depth 0 in-str? false esc? false in-cmt? false]
    (if (>= j (count s))
      j
      (let [c (nth s j)]
        (cond
          in-str? (recur (inc j) depth (not (or esc? (= c \"))) (and (not esc?) (= c \\)) in-cmt?)
          in-cmt? (recur (inc j) depth false false (not= c \newline))
          (= c \") (recur (inc j) depth true false false)
          (= c \;) (recur (inc j) depth false false true)
          (= c \\) (recur (+ j 2) depth false false false)
          (contains? #{\( \[ \{} c) (recur (inc j) (inc depth) false false false)
          (contains? #{\) \] \}} c) (if (= 1 depth) (inc j)
                                        (recur (inc j) (dec depth) false false false))
          :else (recur (inc j) depth false false false))))))

(defn- find-ns-form [s]
  (loop [from 0]
    (let [i (.indexOf s "(ns" from)]
      (cond
        (neg? i) nil
        ;; must be followed by whitespace and preceded by line start / space
        (and (< (+ i 3) (count s))
             (contains? #{\space \newline \tab} (nth s (+ i 3)))
             (or (zero? i) (contains? #{\newline \space} (nth s (dec i)))))
        (subs s i (forms-end s i))
        :else (recur (inc i))))))

(defn- clause
  "The body text of (:require ...) / (:require-macros ...) inside `form`."
  [form kw]
  (loop [from 0 acc []]
    (let [i (.indexOf form (str "(" kw) from)]
      (if (neg? i)
        acc
        (let [e (forms-end form i)]
          (recur e (conj acc (subs form (+ i 1 (count kw)) (dec e)))))))))

(def ^:private ns-sym-re #"^[a-zA-Z][a-zA-Z0-9._$*+!?<>=-]*$")

(defn- top-level-requires
  "Namespaces named at the top level of a :require body.

  Takes the FIRST symbol of each [..] entry (so :refer/:rename contents are
  never read as namespaces) and each bare symbol. Reader conditionals are
  descended into for :cljs and :default ONLY -- the :clj branch is where the
  java.* imports live, and this is a ClojureScript build."
  [body]
  (loop [i 0 out []]
    (if (>= i (count body))
      out
      (let [c (nth body i)]
        (cond
          (contains? #{\space \newline \tab \,} c) (recur (inc i) out)
          (= c \;) (recur (or (str/index-of body "\n" i) (count body)) out)
          ;; #?( ... ) / #?@( ... )
          (and (= c \#) (< (inc i) (count body)) (= \? (nth body (inc i))))
          (let [open (loop [k i] (if (= \( (nth body k)) k (recur (inc k))))
                e (forms-end body open)
                inner (subs body (inc open) (dec e))
                cljs-part (loop [j 0]
                            (let [k (str/index-of inner ":cljs" j)
                                  d (str/index-of inner ":default" j)
                                  p (cond (and k d) (min k d) k k d d :else nil)]
                              (when p
                                (let [o (loop [m (+ p 5)]
                                          (cond (>= m (count inner)) nil
                                                (contains? #{\[ \(} (nth inner m)) m
                                                :else (recur (inc m))))]
                                  (when o (subs inner o (forms-end inner o)))))))]
            (recur e (into out (when cljs-part (top-level-requires cljs-part)))))
          (= c \[)
          (let [e (forms-end body i)
                inner (str/trim (subs body (inc i) (dec e)))
                head (first (str/split inner #"[\s\]\[]+"))]
            (recur e (if (and head (re-matches ns-sym-re head)) (conj out head) out)))
          (= c \() (recur (forms-end body i) out)
          (= c \") (recur (forms-end body (dec i)) out)   ; npm string require: skip
          :else
          (let [e (loop [k i] (if (or (>= k (count body))
                                      (contains? #{\space \newline \tab \, \] \) \[ \(} (nth body k)))
                                k (recur (inc k))))
                tok (subs body i e)]
            (recur e (if (re-matches ns-sym-re tok) (conj out tok) out))))))))

(defn- requires-in [txt]
  (if-let [form (find-ns-form txt)]
    (->> (concat (clause form ":require") (clause form ":require-macros"))
         (mapcat top-level-requires)
         set)
    #{}))

;; ── the workspace index ──────────────────────────────────────────────────────
;; An unresolved namespace has two very different meanings and they must not be
;; reported as one thing:
;;
;;   IN the workspace  -> the file exists and this build simply is not wired to
;;                        it. That is the drift, and the fix is a source-path.
;;   NOT in it         -> almost certainly a maven or npm dependency resolved by
;;                        :dependencies, which this script cannot see. Reported,
;;                        never failed on -- otherwise every reagent/re-frame
;;                        build reads as broken.

(defn- workspace-index []
  (let [orgs-dir (.join path root "orgs")
        org-names (if (.existsSync fs orgs-dir)
                    (->> (.readdirSync fs orgs-dir #js {:withFileTypes true})
                         array-seq (filter #(.isDirectory %)) (map #(.-name %)))
                    [])]
    (reduce
     (fn [acc org]
       (let [od (.join path orgs-dir org)]
         (reduce
          (fn [a repo]
            (let [rd (.join path od repo)
                  ;; <repo>/src and <repo>/<sub>/src, which is where this
                  ;; workspace puts sources
                  srcs (concat
                        (when (.existsSync fs (.join path rd "src")) [(.join path rd "src")])
                        (when (.existsSync fs rd)
                          (->> (try (array-seq (.readdirSync fs rd #js {:withFileTypes true}))
                                    (catch :default _ []))
                               (filter #(.isDirectory %))
                               (map #(.join path rd (.-name %) "src"))
                               (filter #(.existsSync fs %)))))]
              (reduce (fn [m sp]
                        (reduce (fn [mm f] (assoc mm (path->ns sp f) {:root (.relative path root sp) :file f}))
                                m (walk-files sp cljs-exts)))
                      a srcs)))
          acc
          (try (->> (.readdirSync fs od #js {:withFileTypes true})
                    array-seq (filter #(.isDirectory %)) (map #(.-name %)))
               (catch :default _ [])))))
     {} org-names)))

(def ws-index (delay (workspace-index)))

(defn- check-repo
  "Walk the build the way the compiler does: seed with the namespaces the repo
  itself provides (its own non-relative source-paths -- `src`, `test`), then
  follow requires transitively through the sibling libraries.

  Scanning every file in every sibling instead would be wrong, and measurably
  so: it reported 37 of 41 repos broken because a library can carry modules
  this build never reaches. shadow-cljs compiles a closure, not a directory."
  [repo-dir shadow-file]
  (let [txt (slurp* shadow-file)
        paths (source-paths txt)]
    (if (nil? paths)
      {:repo (.relative path root repo-dir)
       ;; the comment-STRIPPED text: `:source-paths` mentioned only in a comment
       ;; (several configs explain that :deps makes it ignored) is not an
       ;; unreadable declaration, it is the absence of one.
       :status (if (and txt (str/includes? (str/replace txt #"(?m);;.*$" "") ":source-paths"))
                 :unparsed :deps-managed)}
      (let [own (filterv #(not (str/starts-with? % "..")) paths)
            abs (mapv #(.resolve path repo-dir %) paths)
            existing (filterv #(.existsSync fs %) abs)
            missing-dirs (vec (remove #(.existsSync fs %) abs))
            index (reduce (fn [acc sp]
                            (reduce (fn [a f] (assoc a (path->ns sp f) f))
                                    acc (walk-files sp cljs-exts)))
                          {} existing)
            seeds (reduce (fn [acc sp]
                            (let [d (.resolve path repo-dir sp)]
                              (into acc (map #(path->ns d %) (walk-files d cljs-exts)))))
                          #{} own)
            ;; BFS the closure, exactly as the compiler resolves it
            [reached unresolved]
            (loop [queue (vec seeds) seen #{} unres {}]
              (if (empty? queue)
                [seen unres]
                (let [n (peek queue) q (pop queue)]
                  (cond
                    (contains? seen n) (recur q seen unres)
                    (provided? n) (recur q seen unres)
                    (nil? (index n))
                    ;; Not on this build's source-paths. If the workspace has it,
                    ;; keep walking THROUGH it -- otherwise the tool reports one
                    ;; link per run and the user rediscovers the cascade by hand,
                    ;; which is exactly what this exists to replace.
                    (let [hit (get @ws-index n)
                          deps (if hit (or (some-> (slurp* (:file hit)) requires-in) #{}) #{})]
                      (recur (into q deps) (conj seen n) (assoc unres n :unresolved)))
                    :else
                    (let [f (index n)
                          deps (or (some-> (slurp* f) requires-in) #{})]
                      (recur (into q deps) (conj seen n) unres))))))
            ;; who required each unresolved one -- an unresolved name with no
            ;; requirer would mean the walk, not the build, is wrong
            blamed (into {}
                         (for [u (keys unresolved)]
                           [u (->> reached
                                   (keep (fn [n]
                                           (when-let [f (index n)]
                                             (when (contains? (or (some-> (slurp* f) requires-in) #{}) u)
                                               (.relative path repo-dir f)))))
                                   first)]))]
        {:repo (.relative path root repo-dir)
         :status (cond (seq (filter #(get @ws-index %) (keys unresolved))) :unresolved
                       (seq missing-dirs) :missing-dirs
                       (empty? seeds) :no-own-sources
                       :else :ok)
         :declared (count abs)
         :missing-dirs (mapv #(.relative path repo-dir %) missing-dirs)
         :indexed-namespaces (count index)
         :reached (count reached)
         :unresolved (vec (keys unresolved))
         :in-workspace (into {} (for [u (keys unresolved)
                                      :let [hit (get @ws-index u)]
                                      :when hit]
                                  ;; repo-relative, so it can be pasted into
                                  ;; :source-paths as printed
                                  [u (.relative path repo-dir (.join path root (:root hit)))]))
         :external (vec (remove #(get @ws-index %) (keys unresolved)))
         :blamed blamed}))))

(defn -main []
  (let [shadows (->> (walk-files (.join path root "orgs") #{".edn"})
                     (filter #(= "shadow-cljs.edn" (.basename path %)))
                     vec)
        cands (->> shadows
                   (filter (fn [f]
                             (let [ps (some-> (slurp* f) source-paths)]
                               (or all?
                                   ;; unreadable shapes stay in: not checking them
                                   ;; is a fact to report, not one to filter away
                                   (and (nil? ps)
                                        (str/includes?
                                         (str/replace (or (slurp* f) "") #"(?m);;.*$" "")
                                         ":source-paths"))
                                   (some #(str/starts-with? % "..") ps))))))
        results (mapv (fn [f] (check-repo (.dirname path f) f)) cands)
        bad (filterv #(#{:unresolved :missing-dirs :unparsed :no-own-sources} (:status %)) results)]
    (if json?
      (println (js/JSON.stringify (clj->js results) nil 2))
      (do
        (println (str "verify-shadow-source-paths: " (count shadows)
                      " shadow-cljs.edn found, " (count cands) " examined"
                      (when-not all? " (relative sibling paths only; --all for every one)")))
        (println (str "SCANNED\t" (count cands) "\tshadow-cljs build(s)"))
        (when findings?
          (doseq [r results]
            (case (:status r)
              :unresolved
              (doseq [n (:unresolved r)
                      :when (get (:in-workspace r) n)]
                (println (str "FINDING\tfail\t" (:repo r) "::" n
                              "\t" n " is reachable from this build and no :source-path"
                              " provides it; it lives at "
                              (get (:in-workspace r) n)
                              " — the build stops at `The required namespace is not"
                              " available`, so nothing in this repo runs")))
              :missing-dirs
              (doseq [d (:missing-dirs r)]
                (println (str "FINDING\tfail\t" (:repo r) "::dir::" d
                              "\tdeclared :source-path does not exist: " d)))
              :unparsed
              (println (str "FINDING\twarn\t" (:repo r) "::unparsed"
                            "\t:source-paths is not in the shape this reader"
                            " understands, so this build was NOT checked — absence"
                            " of findings here is absence of measurement"))
              :no-own-sources
              (println (str "FINDING\twarn\t" (:repo r) "::no-own-sources"
                            "\tno non-relative :source-path provided any file, so"
                            " the walk had no seed and checked nothing"))
              nil)))
        (doseq [r results]
          (case (:status r)
            :ok (println (str "  ok         " (:repo r)
                              "  paths=" (:declared r)
                              " indexed=" (:indexed-namespaces r)
                              " reached=" (:reached r)))
            :no-own-sources (println (str "  NO-SOURCES " (:repo r)
                                          "  — no non-relative source-path provided any file;"
                                          " nothing to walk, NOT counted clean"))
            :unparsed (println (str "  UNPARSED   " (:repo r)
                                    "  — :source-paths not in the expected shape; NOT counted clean"))
            :deps-managed (println (str "  DEPS-EDN   " (:repo r)
                                        "  — no :source-paths; sources come from deps.edn"))
            :missing-dirs (println (str "  MISSING    " (:repo r)
                                        "  declared paths that do not exist: "
                                        (str/join ", " (:missing-dirs r))))
            :unresolved (do (println (str "  UNRESOLVED " (:repo r)
                                          "  " (count (:unresolved r))
                                          " namespace(s) reachable from this build that"
                                          " no source-path provides:"))
                            (doseq [n (:unresolved r)]
                              (println (str "               " n
                                            (when-let [b (get (:blamed r) n)]
                                              (str "   <- " b)))))
                            (when-let [adds (seq (distinct (vals (:in-workspace r))))]
                              (println "               fix: add to :source-paths —")
                              (doseq [a adds]
                                (println (str "                 \"" a "\""))))
                            (when (seq (:external r))
                              (println (str "               (also unresolved, but not in this"
                                            " workspace, so probably :dependencies: "
                                            (str/join ", " (:external r)) ")"))))))
        (println)
        (when (zero? (count cands))
          (println "REFUSING to report a pass: examined 0 repos.")
          (js/process.exit 2))
        (if (seq bad)
          (do (println (str (count bad) " of " (count cands)
                            " repos cannot compile as configured."))
              (js/process.exit 1))
          (println (str "OK — all " (count cands) " examined repos resolve every required namespace.")))))))

(-main)

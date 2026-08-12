#!/usr/bin/env nbb
;; Report Clojure namespaces that more than one checked-out repo ships.
;;
;;   nbb scripts/verify-namespace-collisions.cljs            ; divergent only
;;   nbb scripts/verify-namespace-collisions.cljs --all      ; identical ones too
;;   nbb scripts/verify-namespace-collisions.cljs --check    ; exit 1 if any divergent
;;
;; ## Why this exists
;;
;; Two packages can ship the same namespace file. Whichever wins the classpath
;; decides which implementation you get, silently, and not necessarily the same
;; way on JVM and ClojureScript. A facade that re-exports a var the winner does
;; not have throws at load on the JVM and becomes `undefined` in cljs.
;;
;; That is not hypothetical. `network-awai/network-isekai` keeps a local copy of
;; a player FSM, and says why in its docstring: `kami-engine-sdk` and the
;; extracted `kotoba-lang/fsm` both publish `kami/fsm.cljc` with incompatible
;; APIs, "the SDK wins the classpath, so the `kotoba.fsm` facade compiles to
;; undefined vars in ClojureScript". The workaround cost a third copy of the
;; rule; nothing reported the cause.
;;
;; ## Co-classpath is the axis that matters, not content
;;
;; Content alone is not the signal. Most duplication here is a scaffold: the
;; actor repos are generated from one template, so `association/facts.cljc`
;; exists in dozens of repos AND legitimately differs in each -- they are
;; different associations. Reporting those as findings buries the real ones.
;;
;; Two repos only fight over a namespace if they can be on ONE classpath. So
;; the report is ranked by that: a collision is CO-CLASSPATH when one owner
;; depends on the other, or some third repo depends on both. Dependencies are
;; read from each repo's `deps.edn` (`:git/url` github paths and `:local/root`
;; siblings) and closed transitively.
;;
;; ## Identical is separated from divergent, on purpose
;;
;; Most duplication here is a scaffold: the actor repos are generated from one
;; template, so `marketentry/store.cljc` exists in 186 repos by construction.
;; Those are never on one classpath and reporting them would bury the signal.
;; Rather than keep a list of families to ignore -- which would need editing
;; every time a family is added, and the edit is what gets forgotten -- the
;; report splits on CONTENT: same bytes is noise, different bytes is the
;; finding. A scaffolded family that starts to drift shows up on its own.
;;
;; ## One repo checked out twice is not a collision
;;
;; A rename leaves the old west entry in place, so the same upstream repository
;; can be checked out at two paths -- `kotoba-lang/compiler` and
;; `kotoba-lang/amu` are one repo (GitHub id 1297097065), as are
;; `gftdcojp/cloud-murakumo` and `network-awai/cloud-murakumo`. Every namespace
;; they share then looks like a collision, and it is not: it is two commits of
;; one project. Measured 2026-08-12, that accounted for 19 of what a naive
;; count called 27 findings.
;;
;; Owners that share a root commit are therefore reported separately, as
;; DUPLICATE REGISTRATION, and `scripts/verify-duplicate-registrations.cljs` is
;; where that problem belongs.
;;
;; ## What it cannot see
;;
;; Only repos that are checked out. west manages far more than are on disk, so
;; a clean report means "no collisions among what is here", never "none exist".
;; The count of scanned repos is printed for that reason.

(ns verify-namespace-collisions
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:crypto" :as crypto]
            ["node:child_process" :as child]
            [clojure.string :as str]))

(def argv (vec (drop 2 js/process.argv)))
(def show-all? (some #{"--all"} argv))
(def check? (some #{"--check"} argv))

(def source-extensions #{".clj" ".cljc" ".cljs"})

(defn- source-file? [name]
  (some #(str/ends-with? name %) source-extensions))

(defn- walk
  "Every source file under `dir`, as paths relative to it. Bounded: skips
  node_modules and dot-directories, which are neither ours nor namespaces."
  [dir]
  (letfn [(step [current prefix acc]
            (reduce
             (fn [acc entry]
               (let [name (.-name entry)
                     full (path/join current name)
                     rel (if (str/blank? prefix) name (str prefix "/" name))]
                 (cond
                   (str/starts-with? name ".") acc
                   (= name "node_modules") acc
                   (.isDirectory entry) (step full rel acc)
                   (source-file? name) (conj acc rel)
                   :else acc)))
             acc
             (js->clj (fs/readdirSync current #js {:withFileTypes true}))))]
    (try (step dir "" []) (catch :default _ []))))

(defn- sh [cmd]
  (try (str/trim (str (child/execSync cmd #js {:encoding "utf8"
                                               :stdio #js ["ignore" "pipe" "ignore"]})))
       (catch :default _ nil)))

(defn- root-commit
  "First root commit of a checkout, or nil. Two checkouts of one repository
  share it; so does a fork, which is why this only downgrades a finding to a
  separate bucket rather than dropping it."
  [dir]
  (some-> (sh (str "git -C " dir " rev-list --max-parents=0 HEAD"))
          (str/split #"\n")
          first))

(defn- sha256 [file]
  (try
    (-> (crypto/createHash "sha256")
        (.update (fs/readFileSync file))
        (.digest "hex"))
    (catch :default _ nil)))

(defn- deps-edges
  "repo -> set of repos it depends on, from its deps.edn.

  Read textually rather than by parsing EDN: deps.edn files here carry aliases,
  reader conditionals and comments, and all this needs is which repositories
  are named. A missed edge understates co-classpath reach, which is the safe
  direction -- it can only make the report quieter, never invent a finding."
  [repo-dir]
  (let [f (path/join repo-dir "deps.edn")]
    (if-not (fs/existsSync f)
      #{}
      (let [text (try (str (fs/readFileSync f "utf8")) (catch :default _ ""))
            git (map second (re-seq #"github\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)" text))
            local (map (fn [[_ p]] (last (str/split p #"/")))
                       (re-seq #":local/root\s+\"([^\"]+)\"" text))]
        (into #{} (concat (map #(str/replace % #"\.git$" "") git) local))))))

(defn- reachable
  "Transitive closure of `start` over `edges`, bounded so a cycle terminates."
  [edges start]
  (loop [seen #{} frontier #{start} guard 0]
    (if (or (empty? frontier) (> guard 64))
      seen
      (let [seen' (into seen frontier)
            next (->> frontier (mapcat #(get edges % #{})) (remove seen') set)]
        (recur seen' next (inc guard))))))

(defn- src-dirs
  "Every directory named `src` under `root`, to a bounded depth.

  Not just `<root>/src`. The first version of this scan looked only at the top
  level, and the cost was not hypothetical: of 4,148 checkouts it entered 3,528
  and never opened 620, 59 of which carry Clojure sources in a nested tree
  (`clj/src`, `appview/<name>/cljs/src`, ...). Among the files it therefore
  could not see were `net-kotobase/kotobase-api-gateway-cljs/src/treasury/
  core.cljc` -- the copy ADR-2608121000 calls the worst in the fleet, the one
  missing the guard that decides whether an unconfirmed payment is accepted.
  The report named five owners of `treasury/core.cljc`; seven were on disk.

  The sibling script `verify-vendored-copies.cljs` had this same narrowness and
  fixed it the same morning. Repeating it here is the third instance of one
  error: a scan that knows one layout finds the files that follow it, and being
  reassured by that is the failure."
  [root]
  (letfn [(step [cur depth acc]
            (if (> depth 6)
              acc
              (reduce (fn [acc e]
                        (let [n (.-name e) full (path/join cur n)]
                          (cond
                            (not (.isDirectory e)) acc
                            (str/starts-with? n ".") acc
                            (#{"node_modules" "target" "out" "dist"} n) acc
                            (= n "src") (conj acc full)
                            :else (step full (inc depth) acc))))
                      acc
                      (try (js->clj (fs/readdirSync cur #js {:withFileTypes true}))
                           (catch :default _ [])))))]
    (step root 0 [])))

(defn- repos
  "One entry per (repo, src root). A repo with several source trees appears
  several times, and `:repo` stays the repository so downstream grouping still
  treats it as one owner."
  []
  (->> (try (js->clj (fs/readdirSync "orgs" #js {:withFileTypes true})) (catch :default _ []))
       (filter #(.isDirectory %))
       (mapcat (fn [org]
                 (let [org-name (.-name org)]
                   (->> (try (js->clj (fs/readdirSync (path/join "orgs" org-name)
                                                      #js {:withFileTypes true}))
                             (catch :default _ []))
                        (filter #(.isDirectory %))
                        (map #(str "orgs/" org-name "/" (.-name %)))))))
       (mapcat (fn [root]
                 (map (fn [src] {:repo (str/replace root #"^orgs/" "")
                                 :root root
                                 :src src})
                      (src-dirs root))))
       vec))

(defn- index
  "namespace path -> [{:repo :file :sha}]"
  [repos]
  (reduce
   (fn [acc {:keys [repo src]}]
     (reduce (fn [acc rel]
               (let [file (path/join src rel)]
                 (update acc rel (fnil conj []) {:repo repo :file file :sha (sha256 file)})))
             acc
             (walk src)))
   {}
   repos))

(defn -main []
  (let [rs (repos)
        idx (index rs)
        bare-name (fn [r] (last (str/split r #"/")))
        ;; Edges are keyed by BOTH the full org/repo and the bare repo name: a
        ;; deps.edn names a github path while a :local/root names a directory,
        ;; and the two have to meet somewhere.
        ;; A repo's deps live next to whichever tree declares them: a top-level
        ;; deps.edn for `<repo>/src`, a nested one for `<repo>/clj/src`. Union
        ;; them, or a sub-project's dependencies vanish and no closure can put
        ;; its namespaces on anyone's classpath.
        edges (reduce (fn [acc {:keys [repo root src]}]
                        (let [ds (into (deps-edges root) (deps-edges (path/dirname src)))
                              merged (into (get acc repo #{}) ds)]
                          (-> acc (assoc repo merged) (assoc (bare-name repo) merged))))
                      {} rs)
        ;; One closure per repo, computed once. Each is small; recomputing them
        ;; per collision would be the difference between seconds and minutes.
        closures (into {} (map (fn [r] [r (reachable edges r)]))
                       (distinct (map :repo rs)))
        ;; repo -> root commit, so owners that are one upstream checked out
        ;; twice can be separated from owners that are different projects.
        roots (into {} (map (fn [{:keys [repo root]}] [repo (root-commit root)])) rs)
        ;; Owners are counted per REPOSITORY, not per file. Now that a repo can
        ;; contribute several source trees, one repo could otherwise appear
        ;; twice in `owners` and satisfy "two of these are on one classpath" by
        ;; itself -- a collision manufactured out of a single project.
        owner-repos (fn [owners] (distinct (map :repo owners)))
        within-repo? (fn [owners] (= 1 (count (owner-repos owners))))
        same-upstream?
        (fn [owners]
          (let [names (owner-repos owners)
                rs' (keep #(get roots %) names)]
            (and (= (count rs') (count names))
                 (= 1 (count (distinct rs'))))))
        co-classpath?
        (fn [owners]
          (let [names (owner-repos owners)]
            (boolean
             (and (> (count names) 1)
                  (some (fn [[_ seen]]
                          (>= (count (filter #(or (contains? seen %)
                                                  (contains? seen (bare-name %)))
                                             names))
                              2))
                        closures)))))
        collisions (->> idx
                        (filter (fn [[_ owners]] (> (count owners) 1)))
                        (map (fn [[ns-path owners]]
                               {:ns ns-path
                                :owners owners
                                :divergent? (> (count (distinct (map :sha owners))) 1)}))
                        (map (fn [c] (assoc c
                                            :co-classpath? (co-classpath? (:owners c))
                                            :same-upstream? (same-upstream? (:owners c))
                                            :within-repo? (within-repo? (:owners c)))))
                        (sort-by :ns))
        ;; One repository carrying the same namespace in two of its own source
        ;; trees. Not a cross-repo collision, but not nothing either: which file
        ;; wins depends on the order the trees enter the classpath.
        self-shadowing (filter #(and (:within-repo? %) (:divergent? %)) collisions)
        cross (remove :within-repo? collisions)
        divergent (filter :divergent? cross)
        identical (remove :divergent? cross)
        co (filter :co-classpath? divergent)
        duplicate-registration (filter :same-upstream? co)
        reachable-divergent (remove :same-upstream? co)]
    (println (str "scanned " (count (distinct (map :repo rs))) " checked-out repos ("
                  (count rs) " source trees), " (count idx) " namespace paths"))
    (println (str "collisions: " (count divergent) " divergent, "
                  (count identical) " byte-identical"))
    (println (str "of the divergent, " (count co) " are CO-CLASSPATH -- one owner"
                  " depends on the other, or a third repo depends on both"))
    (println (str "  of those, " (count duplicate-registration)
                  " are ONE repo checked out twice (see"
                  " verify-duplicate-registrations.cljs), leaving "
                  (count reachable-divergent) " real collisions"))
    (println)
    (doseq [{:keys [ns owners]} reachable-divergent]
      (println (str "DIVERGENT  " ns))
      (doseq [{:keys [repo sha]} owners]
        (println (str "             " (subs (or sha "????????") 0 8) "  " repo))))
    (when (seq duplicate-registration)
      (println)
      (println (str "-- " (count duplicate-registration)
                    " suppressed: same upstream, two checkouts --"))
      (doseq [{:keys [ns]} duplicate-registration] (println (str "   " ns))))
    (when (seq self-shadowing)
      (println)
      (println (str "-- " (count self-shadowing)
                    " namespace(s) appear twice WITHIN one repo, with different"
                    " content; which file wins depends on classpath order --"))
      (doseq [{:keys [ns owners]} self-shadowing]
        (println (str "   " ns))
        (doseq [{:keys [file sha]} owners]
          (println (str "     " (subs (or sha "????????") 0 8) "  " file)))))
    (when show-all?
      (println)
      (doseq [{:keys [ns owners co-classpath?]} identical]
        (println (str "identical  " ns "  (" (count owners) " repos"
                      (when co-classpath? ", co-classpath") ")"))))
    (println)
    (println (str "Only checked-out repos were scanned. A clean report means no "
                  "collisions among these, not that none exist."))
    (when (and check? (seq reachable-divergent))
      (println)
      (println (str "verify-namespace-collisions: " (count reachable-divergent)
                    " namespace(s) are shipped with different content by two "
                    "repos that can share a classpath."))
      (set! (.-exitCode js/process) 1))))

(-main)

#!/usr/bin/env nbb
;; How does each build get the shared library it requires — and can it get it
;; at all?
;;
;;   nbb scripts/verify-shared-library-consumption.cljs [--findings] [<orgs dir>]
;;
;; Measured 2026-08-31 (root ADR-2608311600): `kotobase.biscuit-grant` moved out
;; of net-kotobase/control-plane into kotoba-lang/grant. The API gateway
;; followed, through a pinned git dep. `protocols-worker` did not, and nothing
;; said so: its shadow-cljs source path still pointed at the emptied
;; `../shared/src`, its source still required the namespace, and MAIN COULD NOT
;; COMPILE IT -- while the deployed artifact went on serving sparql, gremlin and
;; graphql perfectly well. The gap between `deployed` and `buildable` is
;; invisible until somebody builds.
;;
;; ## The three ways, and why only one of them is silent
;;
;;   vendored     a copy under the consumer's own src, carrying a `VENDORED
;;                from … pinned at <sha>` header. Pinned AND checkable:
;;                verify-vendored-copies.cljs diffs it against that sha.
;;   pinned dep   a git dep in deps.edn naming the library. Pinned; a resolver
;;                fails loudly when the sha is gone.
;;   source path  a shadow-cljs `:source-paths` entry into a sibling checkout.
;;                NO pin -- it takes whatever that checkout happens to hold --
;;                and no error until a build runs.
;;
;; A build that requires the namespace and has none of the three is DANGLING:
;; it cannot compile, and it will say so only when someone tries.
;;
;; Exit is three-valued:
;;   0  every consuming build can reach its library, and by a pinned route
;;   1  every build was classified, at least one is dangling or unpinned
;;   2  the tree could not be searched — REFUSING to report either way.
(ns verify-shared-library-consumption
  (:require [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]))

(def libraries
  "Namespace -> the repository that provides it. A table and not a scan,
  because the question is `who should provide this`, which no scan can answer."
  [{:ns "kotobase.biscuit-grant" :repo "kotoba-lang/grant"
    :why "the fleet's one Biscuit verifier"}])

(defn- sh
  "{:status n :out s}. The status is REPORTED, never interpreted here.

  The first version folded it into an `:ok` that meant `exit 0 or 1`, which is
  right for grep (1 = no match, an answer) and wrong for everything else. It
  made `git rev-parse --verify` look successful when it had failed, so every
  repository resolved to the first remote in the list and every `git show`
  afterwards missed -- turning a correctly pinned build into `dangling`.
  Measured 2026-08-31 while writing this: the API gateway, which takes a
  proper git dep, was reported as unable to compile."
  [args]
  (try {:status 0 :out (str (cp/execSync (str/join " " (map pr-str args))
                                         #js {:encoding "utf8"
                                              :stdio #js ["pipe" "pipe" "pipe"]
                                              :maxBuffer (* 64 1024 1024)}))}
       (catch :default e {:status (or (aget e "status") 127)
                          :out (str (or (aget e "stdout") ""))})))

(defn- grep-ok?
  "grep exits 1 for `no match`, which is an ANSWER and not a failure."
  [{:keys [status]}] (contains? #{0 1} status))

(defn- requiring-files [orgs nsname]
  (let [r (sh ["grep" "-rl" "--binary-files=without-match"
               "--exclude-dir=node_modules" "--exclude-dir=.git"
               "--exclude-dir=dist" "--exclude-dir=out" "--exclude-dir=release"
               "--exclude-dir=target" "--include=*.cljc" "--include=*.cljs"
               "--" nsname orgs])]
    (when (grep-ok? r) (vec (remove str/blank? (str/split-lines (:out r)))))))

(defn- build-root
  "The nearest ancestor holding a build config. Classification has to happen
  per BUILD, not per repo: one repository here has a gateway that took a git
  dep and a protocols worker that did not, and a repo-level answer would have
  called that repository fine."
  [orgs f]
  (loop [d (path/dirname f)]
    (cond
      (or (str/blank? d) (= d orgs) (= d (path/dirname d))) nil
      (or (fs/existsSync (path/join d "shadow-cljs.edn"))
          (fs/existsSync (path/join d "deps.edn"))) d
      :else (recur (path/dirname d)))))

(def ^:private remotes
  ["origin" "net-kotobase" "network-awai" "kotoba-lang" "cloud-itonami"
   "com-junkawasaki" "gftdcojp" "etzhayyim"])

(defn- repo-of
  "The git repository a path sits in: orgs/<org>/<repo>."
  [orgs f]
  (let [rel (str/split (path/relative orgs f) #"/")]
    (when (>= (count rel) 2) (path/join orgs (first rel) (second rel)))))

(defn- default-ref [repo]
  (some (fn [r] (let [ref (str r "/main")]
                  (when (zero? (:status (sh ["git" "-C" repo "rev-parse" "--verify" "-q" ref])))
                    ref)))
        remotes))

(defn- show
  "A file as the repository's default branch has it, or nil.

  Reading WORKING TREES was the first version, and it was wrong in the
  direction that manufactures findings: a west checkout sits at its pin, so a
  file landed on main minutes ago is simply absent, and the detector called a
  correctly-vendored build `dangling`. Measured 2026-08-31 on nexus-x402 the
  same day its copy landed."
  [repo ref rel]
  (let [r (sh ["git" "-C" repo "show" (str ref ":" rel)])]
    (when (zero? (:status r)) (:out r))))

(defn- behind? [repo ref]
  (let [r (sh ["git" "-C" repo "rev-list" "--count" (str "HEAD.." ref)])]
    (when (zero? (:status r)) (let [n (js/parseInt (str/trim (:out r)) 10)]
                    (when (and (js/Number.isFinite n) (pos? n)) n)))))

(defn- provides?
  "How this build reaches the library, or :dangling. Classified from the
  repository's DEFAULT BRANCH, never from the working tree."
  [orgs build {:keys [ns repo]}]
  (let [lib-name (last (str/split repo #"/"))
        home (repo-of orgs build)
        ref (and home (default-ref home))]
    (if-not ref
      {:how :unreadable :pinned? true
       :detail "no default branch could be resolved; not classified"}
      (let [rel (fn [f] (path/relative home f))
            shadow (show home ref (rel (path/join build "shadow-cljs.edn")))
            deps (show home ref (rel (path/join build "deps.edn")))
            tree (sh ["git" "-C" home "ls-tree" "-r" "--name-only" ref "--"
                      (rel (path/join build "src"))])
            vendored (when (zero? (:status tree))
                       (first (filter #(str/ends-with?
                                        % (str (str/replace ns #"[.-]" {"." "/" "-" "_"}) ".cljc"))
                                      (str/split-lines (:out tree)))))]
        (let [lag (behind? home ref)
              note (if lag (str " [checkout is " lag " behind " ref "]") "")]
          (cond
            vendored {:how :vendored :pinned? true
                      :detail (str "copy in this build's src" note)}
            (and deps (str/includes? deps (str "kotoba-lang/" lib-name)))
            {:how :pinned-dep :pinned? true :detail (str "deps.edn git dep" note)}
            (and shadow (re-find (re-pattern (str "\"[^\"]*/" lib-name "/src\"")) shadow))
            {:how :source-path :pinned? false
             :detail (str "shadow-cljs :source-paths, no pin" note)}
            :else {:how :dangling :pinned? false
                   :detail (str "nothing on this build provides it" note)}))))))

(defn -main [& args]
  (let [findings? (boolean (some #{"--findings"} args))
        orgs (or (first (remove #(str/starts-with? % "--") args)) "orgs")
        rows (atom [])
        unread (atom false)]
    (doseq [{:keys [ns repo] :as lib} libraries]
      (if-let [files (requiring-files orgs ns)]
        (let [provider-path (path/join orgs repo)
              builds (->> files
                          (remove #(str/starts-with? % (str provider-path "/")))
                          (keep #(build-root orgs %))
                          distinct sort)]
          (doseq [b builds]
            (swap! rows conj (assoc (provides? orgs b lib)
                                    :build (path/relative orgs b) :ns ns))))
        (reset! unread true)))
    (if @unread
      (do (println "REFUSED\tthe tree could not be searched") 2)
      (let [rs @rows
            bad (filter #(not (:pinned? %)) rs)]
        (println (str "SCANNED\t" (count rs) "\tconsuming builds across "
                      (count libraries) " shared librar(y/ies)"))
        (doseq [r (sort-by :build rs)]
          (println (str "  " (case (:how r)
                              :vendored "ok   vendored  "
                              :pinned-dep "ok   pinned-dep"
                              :source-path "WARN source-path"
                              :unreadable "?    unreadable"
                              "FAIL dangling  ")
                        " " (:build r) "  — " (:detail r))))
        (doseq [r bad]
          (let [sev (if (= :dangling (:how r)) "high" "medium")
                detail (str (:build r) " requires " (:ns r) " by "
                            (name (:how r)) ": " (:detail r)
                            (when (= :dangling (:how r))
                              " — this build cannot compile, and will say so only when somebody builds it"))]
            (when findings?
              (println (str "FINDING\t" sev "\t" (:build r) "::" (:ns r) "\t" detail)))))
        (println)
        (cond
          (zero? (count rs))
          (do (println "  REFUSING: no consuming build found, which is not a clean result") 2)
          (seq bad) (do (println (str "  " (count bad) " build(s) reach the library by an unpinned or missing route")) 1)
          :else (do (println "  clean") 0))))))

(let [code (apply -main *command-line-args*)]
  (set! (.-exitCode js/process) code))

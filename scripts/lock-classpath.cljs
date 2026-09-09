#!/usr/bin/env nbb
;; Write `deps-lock.edn` for one project: the resolved dependency closure, with
;; every input addressed by content.
;;
;; Generalised from kotoba-lang/amu's script, which was the only lock in this
;; workspace (measured 2026-09-09: 1 of 4,336 project files). Two differences,
;; both deliberate:
;;
;;   MAVEN ARTIFACTS ARE LOCKED, NOT DROPPED. amu's version skips them -- "nbb
;;   cannot load them, and their ~/.m2 paths are machine-local" -- which is true
;;   for amu's purpose and leaves out exactly the class that has no other
;;   integrity. A :git/sha IS a content hash; a :mvn/version is a name, and
;;   6,317 of them in this workspace carry nothing else. So each jar is recorded
;;   with its SHA-256, which is what Deno's lock does per fetched artifact.
;;   The path is not recorded, only the coordinate, version and hash: the path
;;   is machine-local, the hash is not.
;;
;;   IT REFUSES RATHER THAN GUESS. No deps.edn, no JDK, an unreadable jar, or a
;;   closure with nothing in it -- each says which, and exits 2. A lock that
;;   silently omits an input is worse than no lock, because it looks like one.
;;
;;   IT DECLARES ITS OWN COMPLETENESS. A :local/root resolves to a plain path,
;;   which is neither a git sha nor a jar hash. Those are recorded under
;;   :lock/unaddressed and :lock/complete? is false, so a lock that cannot cover
;;   everything cannot be mistaken for one that does.
;;
;; ADR-2609092000. Run it whenever deps.edn changes: :lock/deps-digest binds the
;; two, so a consumer that checks it fails closed until this is regenerated.
;;
;;   nbb scripts/lock-classpath.cljs [project-root]
(ns lock-classpath
  (:require ["node:child_process" :as child]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as np]
            [clojure.string :as str]))

(def lock-version 2)

(defn- refuse! [msg] (println (str "REFUSED: " msg)) (.exit js/process 2))

(defn- gitlibs-root []
  (or (.-GITLIBS js/process.env) (.join np (.homedir os) ".gitlibs")))

(defn- sha256-file [p]
  (try (-> (.createHash crypto "sha256") (.update (.readFileSync fs p)) (.digest "hex"))
       (catch :default _ nil)))

(defn- git-out [args]
  (let [r (.spawnSync child "git" (clj->js args) #js {:encoding "utf8" :maxBuffer 8388608})]
    (when (and (not (.-error r)) (zero? (or (.-status r) 1))) (str/trim (.-stdout r)))))

(defn- git-entry [libs-prefix e]
  (when (str/starts-with? e libs-prefix)
    (let [segs (str/split (subs e (count libs-prefix)) (re-pattern (str "\\" (.-sep np))))]
      (when (<= 3 (count segs))
        (let [[group artifact sha & path] segs]
          {:coordinate (str group "/" artifact) :git-sha sha
           :path (if (seq path) (str/join (.-sep np) path) ".")})))))

(defn- maven-entry
  "A ~/.m2 jar, as coordinate + version + the SHA-256 of the bytes.

  .m2/repository/<group/as/path>/<artifact>/<version>/<artifact>-<version>.jar"
  [m2-prefix e]
  (when (and (str/starts-with? e m2-prefix) (str/ends-with? e ".jar"))
    (let [segs (str/split (subs e (count m2-prefix)) (re-pattern (str "\\" (.-sep np))))]
      (when (<= 3 (count segs))
        (let [version  (nth segs (- (count segs) 2))
              artifact (nth segs (- (count segs) 3))
              group    (str/join "." (take (- (count segs) 3) segs))
              sha (sha256-file e)]
          (when-not sha (refuse! (str "cannot read " e " to hash it; the lock would be a lie")))
          {:coordinate (str group "/" artifact) :mvn-version version :sha256 sha})))))

(defn- origin-url [coordinate sha]
  (let [[group artifact] (str/split coordinate #"/")
        dir (.join np (gitlibs-root) "libs" group artifact sha)]
    (if-let [url (git-out ["-C" dir "remote" "get-url" "origin"])]
      ;; two resolvers can warm the same checkout with origins differing only by
      ;; a trailing .git; normalise so regenerating an identical lock is stable
      (if (and (str/starts-with? url "https://github.com/") (not (str/ends-with? url ".git")))
        (str url ".git") url)
      (refuse! (str "cannot read the origin URL of " coordinate " at " sha)))))

(defn -main [& args]
  (let [root (.resolve np (or (first args) "."))
        deps-file (.join np root "deps.edn")
        _ (when-not (.existsSync fs deps-file) (refuse! (str "no deps.edn at " root)))
        r (.spawnSync child "clojure" #js ["-Spath"] #js {:cwd root :encoding "utf8" :maxBuffer 16777216})
        _ (when-not (and (not (.-error r)) (zero? (or (.-status r) 1)))
            (refuse! (str "clojure -Spath failed for " root "; this script needs a JDK.\n"
                          (some-> (.-stderr r) str/trim))))
        libs-prefix (str (.join np (gitlibs-root) "libs") (.-sep np))
        m2-prefix   (str (.join np (.homedir os) ".m2" "repository") (.-sep np))
        entries (str/split (str/trim (.-stdout r)) (re-pattern (str "\\" (.-delimiter np))))
        ;; ANYTHING THAT IS NEITHER. A :local/root resolves to a plain path --
        ;; not ~/.gitlibs, not ~/.m2 -- and the first version of this script
        ;; dropped those silently. Found by running it on cloud-itonami/junbi,
        ;; whose deps.edn names three cross-repo :local/root and whose lock
        ;; recorded none of them: exactly the failure this script's own header
        ;; calls worse than no lock. They are recorded, and the lock says of
        ;; itself that it is not complete.
        unaddressed (->> entries
                         (remove #(or (str/starts-with? % libs-prefix)
                                      (str/starts-with? % m2-prefix)
                                      (not (str/starts-with? % "/"))))
                         distinct sort vec)
        gits (->> entries (keep #(git-entry libs-prefix %))
                  (group-by (juxt :coordinate :git-sha)) (sort-by first)
                  (mapv (fn [[[c sha] g]] {:coordinate c :git-url (origin-url c sha)
                                           :git-sha sha :paths (vec (distinct (map :path g)))})))
        mvns (->> entries (keep #(maven-entry m2-prefix %)) distinct (sort-by :coordinate) vec)]
    (when (and (empty? gits) (empty? mvns))
      (refuse! "the resolved closure had no git and no maven inputs; nothing to lock"))
    (let [out (.join np root "deps-lock.edn")]
      (.writeFileSync fs out
        (str ";; Generated by scripts/lock-classpath.cljs -- do not hand-edit.\n"
             ";; Every build input, addressed by content: a git dependency by the sha\n"
             ";; git already hashes it under, a maven artifact by the SHA-256 of its\n"
             ";; bytes, since a :mvn/version is only a name. Bound to deps.edn's\n"
             ";; digest -- change a pin and a consumer that checks this fails closed.\n"
             ";; ADR-2609092000.\n"
             (with-out-str
               (println "{:lock/version" lock-version)
               (println " :lock/deps-digest" (pr-str (sha256-file deps-file)))
               (println " :lock/resolver" (pr-str "clojure -Spath (authoring time only)"))
               (println " :lock/entries")
               (println " [")
               (doseq [e gits]
                 (println "  {:coordinate" (pr-str (:coordinate e)))
                 (println "   :git-url" (pr-str (:git-url e)))
                 (println "   :git-sha" (pr-str (:git-sha e)))
                 (println "   :paths" (pr-str (:paths e)) "}"))
               (println " ]")
               (println " :lock/maven")
               (println " [")
               (doseq [e mvns]
                 (println "  {:coordinate" (pr-str (:coordinate e)))
                 (println "   :mvn-version" (pr-str (:mvn-version e)))
                 (println "   :sha256" (pr-str (:sha256 e)) "}"))
               (println " ]")
               (println " :lock/unaddressed")
               (println " [")
               (doseq [p unaddressed] (println "  " (pr-str p)))
               (println " ]")
               (println " :lock/complete?" (empty? unaddressed) "}"))))
      (println (str "LOCKED\t" out "\t" (count gits) " git, " (count mvns) " maven, "
                    (count unaddressed) " unaddressed"))
      (when (seq unaddressed)
        (println (str "INCOMPLETE\t" (count unaddressed)
                      " classpath entr(ies) are plain paths -- a :local/root or"
                      " similar -- and cannot be addressed by content. The lock"
                      " records them and declares :lock/complete? false; it must"
                      " not be read as a complete one."))))))

(apply -main *command-line-args*)

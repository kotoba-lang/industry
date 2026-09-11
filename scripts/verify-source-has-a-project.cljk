;; Clojure source in a west project with nothing that declares a project.
;;
;; A repository with .clj/.cljc files and no deps.edn, bb.edn, shadow-cljs.edn,
;; nbb.edn or kotoba.app.edn is not built by anything, and a dependency cannot
;; be added to it because there is nowhere to put one.
;;
;; ## Why this is measured
;;
;; The clojure.string -> kotoba.lang.text migration refused 67 repositories with
;; "every one of the N file(s) to rewrite has no project file above it; nothing
;; was written" (2026-09-09). clojure.string ships with Clojure and resolves for
;; free; kotoba.lang.text is a dependency and does not. So the migration cannot
;; touch those files until something declares what the repository depends on,
;; and that is a decision per repository rather than a rewrite.
;;
;; Sweeping the whole workspace rather than the migration's refusal list found
;; 47 such projects holding 299 files.
;;
;; ## What is NOT a finding
;;
;; kotoba.app.edn IS a project descriptor -- the kotoba toolchain's own deploy
;; manifest, read by `kotoba app deploy`. Four of the 47 have one and are
;; reported separately rather than as a defect.
;;
;; manifest.edn is NOT one. Seventeen of these repositories have a manifest.edn
;; and it is actor datoms -- :actor/glyph, :actor/gates, :actor/cells -- not a
;; build file. Counting it would have turned 43 findings into 26 and the
;; difference would have been invisible.
;;
;; Not a fleet gate: it reads every west project under orgs/, and a gate ships
;; one repository's tree.
;;
;; Usage: nbb verify-source-has-a-project.cljs [--findings] <orgs-dir>
(require '[clojure.string :as s])

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))
(def args (vec *command-line-args*))
(def findings? (some #(= "--findings" %) args))
(def orgs (or (last (remove #(s/starts-with? % "--") args)) "orgs"))
;; --only <path-prefix> narrows the sweep to one project. A check that takes
;; three minutes over 4,780 projects is a check whose control never gets run:
;; two attempts at one here were killed mid-flight and left a planted file in a
;; shared checkout. With --only the same code path answers in seconds.
(def only (second (drop-while #(not= "--only" %) args)))

(when-not (.existsSync fs orgs)
  (println (str "REFUSING: " orgs " is not there. A directory that cannot be read must"
                " not be reported as a tree with nothing wrong in it."))
  (js/process.exit 2))

(defn sh [c]
  (try (str (.execSync cp c #js {:encoding "utf8" :stdio #js ["pipe" "pipe" "pipe"]
                                 :maxBuffer 33554432}))
       (catch :default _ nil)))

(defn lines [x] (if (nil? x) nil (remove s/blank? (s/split-lines x))))

;; the projects, from west rather than from a directory listing: a checkout that
;; is not registered is not this file's business, and one that is registered but
;; absent must be counted as absent rather than clean.
(def west
  (let [t (or (some-> (sh (str "git -C " (.dirname (js/require "node:path") orgs)
                               " show origin/main:manifest/west.yml 2>/dev/null")))
              (some-> (sh "git show origin/main:manifest/west.yml 2>/dev/null")))]
    (when-not t
      (println "REFUSING: could not read manifest/west.yml from origin/main.")
      (js/process.exit 2))
    (map second (re-seq #"(?m)^      path: (orgs/\S+)$" t))))

(defn- longest-prefix
  "The west project a path belongs to. Paths are bucketed once rather than each
  project being searched: the first version ran two `find` calls per project --
  9,588 subprocess spawns for 4,794 projects -- and produced no output at all,
  which is indistinguishable from a clean tree."
  [sorted-projects path]
  (loop [ps sorted-projects]
    (when (seq ps)
      (if (s/starts-with? path (str (first ps) "/")) (first ps) (recur (rest ps))))))

(def project-set (set west))

(defn- bucket [paths]
  (reduce (fn [m p]
            (let [segs (s/split p #"/")
                  ;; orgs/<org>/<repo>/...
                  proj (when (>= (count segs) 3) (s/join "/" (take 3 segs)))]
              (if (and proj (contains? project-set proj))
                (update m proj (fnil inc 0))
                m)))
          {} paths))

(def src-counts
  (let [out (sh (str "find " (or only orgs) " -type f \\( -name '*.clj' -o -name '*.cljc' \\)"
                     " -not -path '*/node_modules/*' -not -path '*/.git/*' 2>/dev/null"))]
    (when (nil? out)
      (println "REFUSING: could not list Clojure source under " orgs ".")
      (js/process.exit 2))
    (bucket (or (lines out) []))))

(def proj-counts
  (let [out (sh (str "find " (or only orgs) " -type f \\( -name deps.edn -o -name bb.edn"
                     " -o -name shadow-cljs.edn -o -name nbb.edn \\)"
                     " -not -path '*/node_modules/*' -not -path '*/.git/*' 2>/dev/null"))]
    (when (nil? out)
      (println "REFUSING: could not list project files under " orgs ".")
      (js/process.exit 2))
    (bucket (or (lines out) []))))

(def scanned (atom 0))
(def absent (atom 0))
(def rows (atom []))
(def has-app-edn (atom []))
(def runner-declared (atom []))

(doseq [p (if only (filter #(= % only) west) west)]
  (if-not (.existsSync fs p)
    (swap! absent inc)
    (do
      (swap! scanned inc)
      (let [src (get src-counts p 0)
            proj (get proj-counts p 0)
            app? (.existsSync fs (str p "/kotoba.app.edn"))]
        (when (and (pos? src) (zero? proj))
          (cond
            app?
            (swap! has-app-edn conj (str "KOTOBA-APP-ONLY\t" p "\t" src))

            ;; A repository whose runner states its own classpath is not an
            ;; oversight, it is a pattern: run_tests.cljs is invoked as
            ;; `nbb --classpath src:test run_tests.cljs`, so the classpath is
            ;; declared at the INVOCATION SITE and the repository has nowhere
            ;; for a dependency because it was never meant to have one. Nine of
            ;; the 43 are this. Saying only "no project file" invites the wrong
            ;; repair; these need a decision about where their classpath lives,
            ;; not a file dropped in.
            (.existsSync fs (str p "/run_tests.cljs"))
            (swap! runner-declared conj (str "CLASSPATH-AT-INVOCATION\t" p "\t" src))

            :else
            (swap! rows conj (str "NO-PROJECT\t" p "\t" src))))))))

(doseq [x (sort @has-app-edn)] (println x))
(doseq [x (sort @runner-declared)] (println x))
(doseq [x (sort @rows)] (println x))
(println (str "SCANNED\t" @scanned "\twest projects present on disk"))
(when (pos? @absent)
  (println (str "ABSENT\t" @absent "\tregistered but not checked out -- not measured, not clean")))
(println (str "KOTOBA-APP-ONLY\t" (count @has-app-edn)))
(println (str "CLASSPATH-AT-INVOCATION\t" (count @runner-declared)))
(println (str "NO-PROJECT\t" (count @rows) "\tprojects, "
              (reduce + 0 (map #(js/parseInt (nth (s/split % #"\t") 2)) @rows)) " files"))
(when findings?
  (doseq [r @rows]
    (let [[_ p n] (s/split r #"\t")]
      (println (str "FINDING\tmedium\t" p "\t" n " .clj/.cljc file(s) and nothing that declares"
                    " a project: no build reads them and a dependency cannot be added")))))
(js/process.exit (cond (zero? @scanned) 2 (seq @rows) 1 :else 0))

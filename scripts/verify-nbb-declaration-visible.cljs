;; .cljs entry points that require a namespace nbb cannot resolve.
;;
;; nbb reads `nbb.edn`. It does NOT read `deps.edn` and it does NOT read
;; `bb.edn` -- their `:deps` are invisible to it. So a repository whose .cljs
;; were migrated to kotoba.lang.text, with the coordinate written to deps.edn,
;; is not migrated: it is broken, and every tool in the chain reports success.
;;
;; Measured 2026-09-09 on three repositories in one wave -- kotoba-lang/grammar,
;; cloud-itonami/cybersecurity, kotoba-lang/kotoba-fleet. Each printed
;; `DEPS added <path>/deps.edn`; each then answered
;;
;;     Error: Could not find namespace: kotoba.lang.text
;;
;; the first time its own entry point was run. `clojure -M:test` was green on
;; all three throughout, because the JVM suite reads deps.edn and never loads
;; the .cljs.
;;
;; ## What counts as a finding, and what deliberately does not
;;
;; A file is reported only when nbb must resolve the namespace from a project
;; file: an ENTRY POINT invoked as a bare `nbb <file>`, with no --classpath.
;;
;;   * `#!/usr/bin/env nbb` in line 1, or
;;   * named by a package.json script as `nbb <file>` with no `--classpath`.
;;
;; A file invoked as `nbb --classpath src:test <file>` is NOT reported: the
;; classpath is supplied at the invocation site, which is a different (and
;; legitimate) declaration. Counting those would have turned a list of things
;; that are broken into a list of things that merely look unusual -- 1,114
;; files rather than the entry points among them.
;;
;; A namespace that ships with nbb (clojure.string, clojure.set, cljs.test) is
;; not a finding either: only namespaces that must come from :deps count.
;;
;; Not a fleet gate: it reads every west project under orgs/, and a gate ships
;; one repository's tree.
;;
;; Usage: nbb verify-nbb-declaration-visible.cljs [--findings] <orgs-dir>
(require '[clojure.string :as s])

(def fs (js/require "node:fs"))
(def path-mod (js/require "node:path"))
(def cp (js/require "node:child_process"))
(def args (vec *command-line-args*))
(def findings? (some #(= "--findings" %) args))
(def only (second (drop-while #(not= "--only" %) args)))
(def orgs (or (last (remove #(s/starts-with? % "--") (remove #{only} args))) "orgs"))

(defn sh [c]
  (try (str (.execSync cp c #js {:encoding "utf8" :stdio #js ["pipe" "pipe" "pipe"]
                                 :maxBuffer 67108864}))
       (catch :default _ nil)))

(defn- slurp* [p] (try (str (.readFileSync fs p "utf8")) (catch :default _ nil)))

(when-not (try (.isDirectory (.statSync fs orgs)) (catch :default _ false))
  (println (str "REFUSING: " orgs " is not a directory. A tree that cannot be read"
                " must not be reported as a tree with nothing wrong in it."))
  (js/process.exit 2))

;; namespaces that only a :deps coordinate can supply. nbb ships clojure.string,
;; clojure.set, clojure.walk, cljs.test and the node interop; requiring one of
;; those from a bare `nbb file.cljs` works with no project file at all.
(def bundled
  #{"clojure.string" "clojure.set" "clojure.walk" "clojure.edn" "clojure.data"
    "clojure.test" "cljs.test" "cljs.reader" "cljs.pprint" "clojure.pprint"
    "clojure.core" "cljs.core" "goog.string" "promesa.core"})

(defn- external-requires
  "The non-bundled namespaces this file requires, as strings."
  [src]
  (->> (re-seq #"(?m)[\[']([a-z][a-zA-Z0-9]*(?:[.][a-zA-Z0-9*+!?<>=_-]+)+)\s+:as\b" src)
       (map second)
       (remove bundled)
       (remove #(s/starts-with? % "node:"))
       distinct))

(def west
  (let [t (or (sh "git show origin/main:manifest/west.yml 2>/dev/null")
              (sh (str "git -C " (.dirname path-mod orgs) " show origin/main:manifest/west.yml 2>/dev/null")))]
    (when-not t
      (println "REFUSING: could not read manifest/west.yml from origin/main.")
      (js/process.exit 2))
    (map second (re-seq #"(?m)^      path: (orgs/\S+)$" t))))

(defn- nbb-above [repo file]
  (loop [d (.dirname path-mod file)]
    (let [c (.join path-mod d "nbb.edn")]
      (cond
        (try (.isFile (.statSync fs c)) (catch :default _ false)) c
        (or (= d repo) (= d (.dirname path-mod d))) nil
        :else (recur (.dirname path-mod d))))))

(defn- declared-in [nbb-edn-path]
  (let [t (or (slurp* nbb-edn-path) "")]
    ;; textual on purpose: a coordinate inside a comment does not count, and
    ;; blanking comments first is cheaper than a reader here.
    (set (map second (re-seq #"(?m)^[^;\n]*io\.github\.([a-zA-Z0-9-]+/[a-zA-Z0-9._-]+)" t)))))

;; kotoba-lang/text -> kotoba.lang.text, and the same shape for its siblings.
(defn- coordinate-for [ns-name]
  (when-let [m (re-matches #"kotoba\.lang\.([a-z0-9-]+)" ns-name)]
    (str "kotoba-lang/" (second m))))

(def ^:private shebang-index
  "Every `#!/usr/bin/env nbb` .cljs under `orgs`, bucketed by west project, from
  ONE grep. The first version ran a grep per project -- 4,800 subprocess spawns
  -- and the sibling detector that did the same thing produced no output at all,
  which is indistinguishable from a clean tree."
  (delay
    (let [out (sh (str "grep -rl --include='*.cljs' --exclude-dir=node_modules"
                       " --exclude-dir=.git -e '^#!/usr/bin/env nbb' "
                       (or only orgs) " 2>/dev/null"))]
      (when (nil? out)
        (println (str "REFUSING: could not search " (or only orgs) " for nbb entry points."))
        (js/process.exit 2))
      (reduce (fn [m f]
                (let [segs (s/split f #"/")]
                  (if (>= (count segs) 3)
                    (update m (s/join "/" (take 3 segs)) (fnil conj []) f)
                    m)))
              {} (remove s/blank? (s/split-lines out))))))

(def entry-points
  "Bare `nbb <file>` entry points per repo: shebang files plus package.json
  scripts with no --classpath."
  (fn [repo]
    (let [shebangs (get @shebang-index repo [])
          pkg      (slurp* (.join path-mod repo "package.json"))
          scripted (when pkg
                     (->> (re-seq #"\"nbb ((?!--classpath)[^\"]*?\.cljs)" pkg)
                          (map (comp #(.join path-mod repo %) s/trim second))
                          (filter #(try (.isFile (.statSync fs %)) (catch :default _ false)))))]
      (distinct (concat shebangs (or scripted []))))))

(def scanned (atom 0))
(def absent (atom 0))
(def rows (atom []))

(doseq [p (if only (filter #(= % only) west) west)]
  (if-not (try (.isDirectory (.statSync fs p)) (catch :default _ false))
    (swap! absent inc)
    (do
      (swap! scanned inc)
      (doseq [f (entry-points p)]
        (when-let [src (slurp* f)]
          (let [needs (keep coordinate-for (external-requires src))
                np    (nbb-above p f)
                have  (if np (declared-in np) #{})
                miss  (remove have needs)]
            (when (seq miss)
              (swap! rows conj
                     (str "UNRESOLVABLE\t" f "\t" (s/join "," miss) "\t"
                          (if np "nbb.edn-without-it" "no-nbb.edn"))))))))))

(doseq [x (sort @rows)] (println x))
(println (str "SCANNED\t" @scanned "\twest projects present on disk"))
(when (pos? @absent)
  (println (str "ABSENT\t" @absent "\tregistered but not checked out -- not measured, not clean")))
(println (str "UNRESOLVABLE\t" (count @rows) "\tbare-nbb entry point(s)"))
(when findings?
  (doseq [r @rows]
    (let [[_ f miss why] (s/split r #"\t")]
      (println (str "FINDING\thigh\t" f "\truns as a bare `nbb " (.basename path-mod f)
                    "` and requires " miss ", which nbb cannot resolve (" why
                    "); nbb reads nbb.edn, never deps.edn or bb.edn")))))
(js/process.exit (cond (zero? @scanned) 2 (seq @rows) 1 :else 0))

#!/usr/bin/env nbb
;; nbb-cross-runtime.cljs — run a repo's `run-tests.cljs` under nbb, with the
;; classpath built from its own deps.edn.
;;
;; Why this exists rather than a plain `:nbb-test` entry: `:nbb-test` passes a
;; literal `--classpath` string from gates.edn, which works only for a repo
;; whose suite needs nothing but `src:test`. A repo with git deps would need
;; `~/.gitlibs/libs/<lib>/<sha>/src` spelled out — and the sha changes every
;; time the pin moves, so gates.edn would silently rot into "the suite ran
;; against last month's dependency".
;;
;; What it guards (ADR-2608060500). Four defects were found building the
;; kotobase block compression frame. THREE of them were invisible on the JVM
;; and only appeared on a second runtime:
;;
;;   - `org-ietf-deflate` built a different Huffman tree on ClojureScript than
;;     on the JVM, because equal frequencies fell back on hash-map iteration
;;     order. Both streams inflate, so every round-trip test passed. It stops
;;     being harmless the moment the compressed bytes are content-addressed.
;;   - `(count uint8-array)` throws `ICounted` where `(count byte-array)` works.
;;   - `ipld.link/link-cid` read a deftype field directly, which returns nil
;;     under nbb: no node containing a link could be encoded at all, and two
;;     links to one CID compared unequal while hashing equal.
;;
;; The JVM suites were green through all three. Nothing in fleet-ci ran the
;; second runtime, so nothing would have caught the next one either.
;;
;; Usage (tick.cljs ships this and calls it with the repo dir):
;;   npx nbb nbb-cross-runtime.cljs <repo-dir> [--entry run-tests.cljs]
;;
;; Exit 0 only if the suite ran, reported a summary, and had zero failures and
;; zero errors. A suite that runs zero tests fails: "no tests" must not read as
;; "nothing broken".
;;
;; The nbb version is PINNED, and that is not tidiness. Measured 2026-08-06,
;; on the same source: nbb 1.4.210 runs `ipld.link`'s deftype fine, while
;; 1.4.208 dies with "Protocol not found: IEquiv" — a custom protocol in a
;; deftype does not work there at all. `npx nbb` inside this superproject
;; resolves 1.4.208 out of its node_modules, so an unpinned gate would report
;; a language-level failure as a code failure, on some machines and not
;; others. Bump this deliberately, never by ambient resolution.

(ns fleet-ci.gates.nbb-cross-runtime
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))
(def entry
  (or (second (drop-while #(not= "--entry" %) args)) "run-tests.cljs"))

(def nbb-version
  "See the header. A custom protocol in a deftype needs at least this."
  "1.4.210")

(defn- exists? [p]
  (try (.accessSync fs p) true (catch :default _ false)))

(defn- slurp* [p]
  (try (.readFileSync fs p "utf8") (catch :default _ nil)))

;; Same shape tick.cljs/git-deps-of matches, kept independent on purpose: this
;; script runs on the node with only the repo tree, not the runner's source.
(def ^:private git-dep-re
  #"io\.github\.([a-zA-Z0-9_.-]+)/([a-zA-Z0-9_.-]+)\s*\{[^}]*?:git/sha\s+\"([0-9a-f]{40})\"")

(defn- git-deps-of [deps-text]
  (mapv (fn [[_ org repo sha]]
          {:lib (str "io.github." org "/" repo) :org org :repo repo :sha sha})
        (re-seq git-dep-re (str deps-text))))

(defn- dep-dir
  "Where this dependency's checkout is.

  `~/.gitlibs/libs/<lib>/<sha>` is what `ship-git-deps!` populates on a fleet
  node, and it is the authoritative answer: it pins the exact sha deps.edn
  asks for. The sibling `<repo>/../<name>` fallback is the west layout, so the
  same gate can be run by hand from a workspace checkout to verify it — but it
  is NOT sha-pinned, so it reports which one it used and the caller can tell
  the difference."
  [{:keys [lib repo sha]}]
  (let [home (or (.-HOME (.-env js/process)) "")
        pinned (path/join home ".gitlibs" "libs" lib sha)
        sibling (path/join root ".." repo)]
    (cond
      (exists? pinned) {:dir pinned :source :gitlibs}
      (exists? sibling) {:dir sibling :source :sibling}
      :else nil)))

(defn- resolve-classpath
  "Transitive closure of git deps → their `src` directories, plus this repo's
  own `src` and `test`. Order is deterministic (breadth-first from deps.edn)
  so a failure is reproducible.

  `:conflicts` names any library reached at TWO different shas. tools.deps
  resolves such a diamond to one version; this walk cannot, and putting both
  on the classpath builds something the code never runs on -- first-wins per
  namespace, mixing one library's API with another library's expectations.

  Measured 2026-08-18 on `arrangement`: it pins `io-ipld` at e08dc3b2 while a
  transitive dep pins 45917645, and both landed on the classpath. The suite
  died at `Cannot read properties of undefined (reading 'lastIndexOf')` deep
  inside a CID decode -- a failure with nothing whatsoever to do with the
  repo's own code, on a machine where the JVM run is green. Its deps.edn even
  carries the comment \"the same SHA, so this adds no new transitive
  dependency\", which stopped being true without anything noticing."
  []
  (loop [queue (git-deps-of (slurp* (path/join root "deps.edn")))
         seen #{}
         by-lib {}
         conflicts []
         dirs []
         missing []
         sibling-used []]
    (if (empty? queue)
      {:paths (into ["src" "test"] dirs) :missing missing :sibling sibling-used
       :conflicts conflicts}
      (let [{:keys [lib sha] :as d} (first queue)
            k [lib sha]
            prior (get by-lib lib)
            conflicts (cond-> conflicts
                        (and prior (not= prior sha))
                        (conj (str lib " @ " (subs prior 0 8) " and " (subs sha 0 8))))]
        (if (contains? seen k)
          (recur (rest queue) seen by-lib conflicts dirs missing sibling-used)
          (if-let [{:keys [dir source]} (dep-dir d)]
            (let [src (path/join dir "src")
                  nested (git-deps-of (slurp* (path/join dir "deps.edn")))]
              (recur (concat (rest queue) nested)
                     (conj seen k)
                     (assoc by-lib lib (or prior sha))
                     conflicts
                     (cond-> dirs (exists? src) (conj src))
                     missing
                     (cond-> sibling-used (= :sibling source) (conj lib))))
            (recur (rest queue) (conj seen k) (assoc by-lib lib (or prior sha))
                   conflicts dirs (conj missing lib) sibling-used)))))))

(defn- npm-declared
  "What npm was asked to install, and what is actually there afterwards.

  Only `dependencies`. The gate installs with `--omit=dev` on purpose, so a
  dev-scoped entry is not something it promises to provide -- but that is
  exactly how kotobase-projection failed on 2026-08-18: the entry was there,
  dev-scoped, silently skipped, and the suite died requiring
  `@noble/hashes/sha2.js` before any assertion ran. The receipt recorded
  `no test summary in output`, which is true and says nothing about why.

  **The install's exit status cannot report this.** Measured on issachar with
  this gate's own flags:

      devDependencies   npm install --silent --omit=dev   exit=0  node_modules=0
      dependencies      npm install --silent --omit=dev   exit=0  node_modules=1

  npm succeeds at installing nothing. So this looks at what is absent
  afterwards rather than at how the install exited."
  [root pkg]
  (try
    (let [j (js->clj (js/JSON.parse (fs/readFileSync pkg "utf8")))
          deps (vec (keys (get j "dependencies")))
          dev (vec (keys (get j "devDependencies")))]
      {:deps deps :dev dev
       :missing (vec (remove #(exists? (path/join root "node_modules" %)) deps))})
    (catch :default e {:unreadable (str e)})))

(defn- fail! [msg]
  (println (str "FAIL: " msg))
  (set! (.-exitCode js/process) 1))

(let [entry-path (path/join root entry)]
  (if-not (exists? entry-path)
    (fail! (str "no nbb entry at " entry
                " — this gate is only for repos that have one"))
    (let [{:keys [paths missing sibling conflicts]} (resolve-classpath)
          cp-str (str/join ":" paths)]
      (println "entry:     " entry "  nbb:" nbb-version)
      (println "classpath: " cp-str)
      (when (seq sibling)
        (println "NOTE: resolved from sibling checkouts, NOT sha-pinned:"
                 (str/join ", " sibling)))
      ;; A classpath carrying two shas of one library is not one tools.deps
      ;; would ever build: it resolves a diamond to a single version, this walk
      ;; cannot, and first-wins-per-namespace silently mixes them.
      ;;
      ;; **Printed, not fatal, and that is a judgement rather than an
      ;; oversight.** Measured 2026-08-18 across the repos gated by this
      ;; script: nine carry a diamond and EIGHT of them run green anyway --
      ;; first-wins happens to pick compatible versions. Failing on the
      ;; condition would turn eight working gates red for something that did
      ;; not affect them, and the fix is re-pinning across the workspace, not
      ;; anything those repos can do.
      ;;
      ;; The ninth is why this line exists at all. `arrangement` reaches
      ;; io-ipld at e08dc3b2 and 45917645, and its suite dies at `Cannot read
      ;; properties of undefined (reading 'lastIndexOf')` inside a CID decode
      ;; -- a failure with nothing to do with its own code, on a machine whose
      ;; JVM run is green. One line here turns half an hour of confusion into
      ;; a diagnosis.
      ;;
      ;; So a green run on a repo listed here means "green on a classpath the
      ;; JVM would not have built", and that is worth knowing when reading it.
      (when (seq conflicts)
        (println "CONFLICT: two versions of one library on this classpath:"
                 (str/join "; " conflicts)
                 "— tools.deps would resolve this to one. Align the pins."))
      (cond
        (seq missing)
        (fail! (str "unresolved git deps (not in ~/.gitlibs and no sibling "
                    "checkout): " (str/join ", " missing)
                    " — ship-git-deps! should have placed these"))

        :else
        ;; npm deps first: io-multiformats needs @noble/hashes to hash a CID,
        ;; and without it every require of `ipld.core` dies at load time.
        (let [pkg (path/join root "package.json")
              _ (when (and (exists? pkg) (not (exists? (path/join root "node_modules"))))
                  (println "npm install (package.json present, node_modules absent)")
                  (.spawnSync cp "npm" #js ["install" "--silent" "--omit=dev"]
                              #js {:cwd root :encoding "utf8" :stdio "inherit"}))
              npm (when (exists? pkg) (npm-declared root pkg))
              ;; Say what was asked for and what arrived. A repo with no
              ;; package.json and a repo whose every dependency is present
              ;; both go on to run the suite, but they are not the same state,
              ;; and a line printed only on failure cannot tell them apart
              ;; when the run is read back later.
              _ (println (cond
                           (nil? npm) "NPM-DEPS\tnone\t(no package.json)"
                           (:unreadable npm) (str "NPM-DEPS\tunreadable\t" (:unreadable npm))
                           :else (str "NPM-DEPS\t"
                                      (- (count (:deps npm)) (count (:missing npm)))
                                      "/" (count (:deps npm)) "\tpresent"
                                      (when (seq (:dev npm))
                                        (str "\t" (count (:dev npm))
                                             " devDependencies omitted by --omit=dev")))))
              blocked (cond
                        (:unreadable npm)
                        (str "package.json is present but will not parse: "
                             (:unreadable npm)
                             " — refusing to run a suite whose dependencies cannot be read")

                        (seq (:missing npm))
                        (str "declared npm dependencies absent after install: "
                             (str/join ", " (:missing npm))
                             " — npm exits 0 while installing nothing, so this is found "
                             "by looking rather than by status. If the entry sits under "
                             "devDependencies, move it: this gate installs --omit=dev.")

                        :else nil)
              r (when-not blocked
                  (.spawnSync cp "npx" (clj->js ["--yes" (str "nbb@" nbb-version)
                                                 "--classpath" cp-str entry])
                              #js {:cwd root :encoding "utf8"}))
              out (if blocked "" (str (.-stdout r) (.-stderr r)))]
          (when-not blocked
            (println (str/join "\n" (take-last 30 (str/split-lines out)))))
          (if blocked
            (fail! blocked)
           (let [m (re-find #"Ran (\d+) tests containing (\d+) assertions" out)
                ran (some-> m second js/parseInt)
                fails (re-find #"(\d+) failures, (\d+) errors" out)
                f (some-> fails second js/parseInt)
                e (some-> fails (nth 2) js/parseInt)]
            (cond
              (nil? m)
              (fail! "no test summary in output — refusing to report a pass")

              (zero? ran)
              (fail! "zero tests ran — 'no tests' is not 'nothing broken'")

              (or (pos? (or f 1)) (pos? (or e 1)))
              (fail! (str ran " tests, " f " failures, " e " errors"))

              (not (zero? (.-status r)))
              (fail! (str "nbb exited " (.-status r) " despite a clean summary"))

              :else
              (println (str "OK: " ran " tests, 0 failures, 0 errors on nbb"))))))))))

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
  so a failure is reproducible."
  []
  (loop [queue (git-deps-of (slurp* (path/join root "deps.edn")))
         seen #{}
         dirs []
         missing []
         sibling-used []]
    (if (empty? queue)
      {:paths (into ["src" "test"] dirs) :missing missing :sibling sibling-used}
      (let [{:keys [lib sha] :as d} (first queue)
            k [lib sha]]
        (if (contains? seen k)
          (recur (rest queue) seen dirs missing sibling-used)
          (if-let [{:keys [dir source]} (dep-dir d)]
            (let [src (path/join dir "src")
                  nested (git-deps-of (slurp* (path/join dir "deps.edn")))]
              (recur (concat (rest queue) nested)
                     (conj seen k)
                     (cond-> dirs (exists? src) (conj src))
                     missing
                     (cond-> sibling-used (= :sibling source) (conj lib))))
            (recur (rest queue) (conj seen k) dirs (conj missing lib) sibling-used)))))))

(defn- fail! [msg]
  (println (str "FAIL: " msg))
  (set! (.-exitCode js/process) 1))

(let [entry-path (path/join root entry)]
  (if-not (exists? entry-path)
    (fail! (str "no nbb entry at " entry
                " — this gate is only for repos that have one"))
    (let [{:keys [paths missing sibling]} (resolve-classpath)
          cp-str (str/join ":" paths)]
      (println "entry:     " entry "  nbb:" nbb-version)
      (println "classpath: " cp-str)
      (when (seq sibling)
        (println "NOTE: resolved from sibling checkouts, NOT sha-pinned:"
                 (str/join ", " sibling)))
      (if (seq missing)
        (fail! (str "unresolved git deps (not in ~/.gitlibs and no sibling "
                    "checkout): " (str/join ", " missing)
                    " — ship-git-deps! should have placed these"))
        ;; npm deps first: io-multiformats needs @noble/hashes to hash a CID,
        ;; and without it every require of `ipld.core` dies at load time.
        (let [pkg (path/join root "package.json")
              _ (when (and (exists? pkg) (not (exists? (path/join root "node_modules"))))
                  (println "npm install (package.json present, node_modules absent)")
                  (.spawnSync cp "npm" #js ["install" "--silent" "--omit=dev"]
                              #js {:cwd root :encoding "utf8" :stdio "inherit"}))
              r (.spawnSync cp "npx" (clj->js ["--yes" (str "nbb@" nbb-version)
                                               "--classpath" cp-str entry])
                            #js {:cwd root :encoding "utf8"})
              out (str (.-stdout r) (.-stderr r))]
          (println (str/join "\n" (take-last 30 (str/split-lines out))))
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
              (println (str "OK: " ran " tests, 0 failures, 0 errors on nbb")))))))))

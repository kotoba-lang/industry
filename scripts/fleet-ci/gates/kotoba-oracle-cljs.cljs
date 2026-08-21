#!/usr/bin/env nbb
;; kotoba-oracle-cljs.cljs — run a repo's ClojureScript oracle gate on a fleet
;; node, and refuse to report a pass it cannot see evidence for.
;;
;; ## What this is for
;;
;; A repo that delegates a Kotoba decision core executes the shipped artifact
;; through `kotoba.kir`. Three host↔guest asymmetries make that work on the JVM
;; and fail on ClojureScript, and **all three are invisible to a green JVM
;; suite** (ADR-2608122000):
;;
;;   1. `string-substring` at an `:i64` offset — the older interpreter guards
;;      with `(integer? start)`, false for the `js/BigInt` an `:i64` is there.
;;   2. an `:i64` field INSIDE a record — `kir/execute` coerces a top-level
;;      `:i64` argument, but a record field goes through
;;      `value/bounded-typed-value!`, which rejects a `js/Number`. The seam's
;;      `i64` conversion is the fix, and its absence is silent on the JVM,
;;      where `(long n)` and `n` are the same value. In the RETURN direction it
;;      does not even throw: `cloud-itonami-app`'s `bot/status` mapped an
;;      `:i64` result through `{0 :connect 1 :use 2 :ask}`, missed every lookup
;;      on cljs, and answered `:idle` for every Bot.
;;   3. `[:set :i64]` does not work on cljs at all at some pins —
;;      `compare-typed-values` sends `:i64` to `cljs.core/compare`, which cannot
;;      compare two BigInts, so a one-element set traps inside the guest.
;;
;; Four repos were measured broken on 2026-08-12 (`com-cloudflare`,
;; `calendar`, `cloud-itonami-app`, `murakumo`), two of them counted as
;; running cores in production. Each now has a ClojureScript gate — and until
;; this file existed, every one of them was a command somebody had to remember
;; to type. A check that does not run is neither green nor red.
;;
;; ## Usage
;;
;;   npx nbb kotoba-oracle-cljs.cljs <repo-dir> --entry <path> [--min N] [--nbb CMD]
;;
;; `--entry` is the repo's own gate, run as-is: this script contributes the
;; classpath and the verdict, never the assertions. `--min` is the floor on how
;; many cases must have run (default 1).
;;
;; ## What it demands, and why each demand exists
;;
;; * **exit 0** — the entry's own verdict.
;; * **a count line** matching `ran N cases` or clojure.test's `Ran N tests
;;   containing`, with N >= `--min`. Exit 0 alone is not evidence: a gate whose
;;   artifact failed to load, or whose case table was emptied, exits 0 while
;;   asserting nothing. This is the same floor `:min-files` puts under the
;;   shipped tree, applied to the thing that actually ran.
;; * **no npm/npx resolution drift** — nbb is pinned here, deliberately, the
;;   same way `nbb-cross-runtime.cljs` pins it and for the same measured
;;   reason: an ambient `npx nbb` resolves out of whatever `node_modules` is
;;   nearest and reports a language-level difference as a code failure.
;;
;; `--nbb CMD` runs an already-installed nbb instead of resolving the pin, and
;; exists for ONE purpose: verifying this gate by hand. `npx --yes <pkg> <args>`
;; is broken on the author's laptop — npm 11.12.1 reads the script path as a
;; package name and dies with EACCES, while the fleet nodes (npm 10.9.8 /
;; 11.17.0) run it correctly, so a gate that could only be exercised through
;; npx could not be shown to fail before being trusted. It PRINTS that it took
;; the override, because an unpinned interpreter is a different measurement.
;;
;; ## Classpath
;;
;; Resolved from the repo's own `deps.edn` git deps, transitively, so the
;; interpreter the gate runs is the one the repo PINS rather than whatever is
;; on the node. `ship-git-deps!` populates `~/.gitlibs/libs/<lib>/<sha>`; a
;; sibling west checkout is accepted as a fallback so this can be verified by
;; hand from a workspace, and it says which one it used, because the sibling is
;; not sha-pinned and a gate that hides that is lying about what it tested.
;;
;; This duplicates `nbb-cross-runtime.cljs`'s resolver rather than requiring
;; it: nbb has no `load-file`, a fleet gate is shipped as ONE file, and the two
;; gates ask different questions (that one runs a portable test suite on a
;; second runtime; this one runs a bespoke oracle gate and enforces a floor).

(ns fleet-ci.gates.kotoba-oracle-cljs
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

(defn- flag [name fallback]
  (or (second (drop-while #(not= name %) args)) fallback))

(def entry (flag "--entry" nil))
(def min-cases (js/parseInt (flag "--min" "1")))
(def nbb-override (flag "--nbb" nil))

(def nbb-version
  "Pinned. See the header — an ambient `npx nbb` is a different interpreter on
  different machines."
  "1.4.210")

(defn- exists? [p]
  (try (.accessSync fs p) true (catch :default _ false)))

(defn- slurp* [p]
  (try (.readFileSync fs p "utf8") (catch :default _ nil)))

(def ^:private git-dep-re
  #"io\.github\.([a-zA-Z0-9_.-]+)/([a-zA-Z0-9_.-]+)\s*\{[^}]*?:git/sha\s+\"([0-9a-f]{40})\"")

(defn- git-deps-of [deps-text]
  (mapv (fn [[_ org repo sha]]
          {:lib (str "io.github." org "/" repo) :org org :repo repo :sha sha})
        (re-seq git-dep-re (str deps-text))))

(defn- dep-dir [{:keys [lib repo sha]}]
  (let [home (or (.-HOME (.-env js/process)) "")
        pinned (path/join home ".gitlibs" "libs" lib sha)
        sibling (path/join root ".." repo)]
    (cond
      (exists? pinned) {:dir pinned :source :gitlibs}
      (exists? sibling) {:dir sibling :source :sibling}
      :else nil)))

(defn- resolve-classpath
  "Transitive closure of git deps → their source dirs, plus this repo's own.

  `resources` is on the list because a shipped KIR artifact lives there and the
  seam reads it as a classpath resource — omitting it turns a delegation gate
  into a loader-error gate."
  []
  (loop [queue (git-deps-of (slurp* (path/join root "deps.edn")))
         seen #{}
         dirs []
         missing []
         sibling-used []]
    (if (empty? queue)
      {:paths (into ["src" "test" "resources"] dirs)
       :missing missing :sibling sibling-used}
      (let [{:keys [lib sha] :as d} (first queue)
            k [lib sha]]
        (if (contains? seen k)
          (recur (rest queue) seen dirs missing sibling-used)
          (if-let [{:keys [dir source]} (dep-dir d)]
            (let [src (path/join dir "src")
                  res (path/join dir "resources")
                  nested (git-deps-of (slurp* (path/join dir "deps.edn")))]
              (recur (concat (rest queue) nested)
                     (conj seen k)
                     (cond-> dirs
                       (exists? src) (conj src)
                       (exists? res) (conj res))
                     missing
                     (cond-> sibling-used (= :sibling source) (conj lib))))
            (recur (rest queue) (conj seen k) dirs (conj missing lib) sibling-used)))))))

(defn- fail! [msg]
  (println (str "FAIL: " msg))
  (set! (.-exitCode js/process) 1))

;; ---------------------------------------------------------------------------
;; nbb.edn の :deps は deps.clj 経由で解決される —— つまり JVM が要る
;;
;; この gate は cljs しか検査しないのに JDK を要求する。理由は nbb の設計で、
;; **cwd の `nbb.edn` に `:deps` があると nbb は deps.clj を呼んで解決する**
;; （`--classpath` を明示しても、である）。com-cloudflare の nbb.edn は
;; kotoba-kir を pin しており、これは我々が組む classpath と同じ pin なので
;; 情報としては冗長だが、**人が `nbb test/...` を直に叩く経路の正本**なので
;; repo から消すべきものではない。
;;
;; cwd を変えて nbb.edn を見せない、という逃げは効かない。実測 2026-08-13、
;; scratch dir + 絶対 classpath で回すと entry が
;; `Error: oracle case table not readable` で落ちる —— case 表は cwd 相対で
;; 読まれる。**gate は repo root で回すしかない。**
;;
;; したがって JDK を PATH に用意する。ノードには openjdk が入っているが
;; `/usr/bin/java` は macOS の stub（`Unable to locate a Java Runtime`）で、
;; 非 login な ssh shell の PATH には本物が居ない。実測 2026-08-13、simeon で
;; PATH に `/opt/homebrew/opt/openjdk/bin` を足すと 220 cases が緑になった。
;;
;; **これは検査を緩めていない。** JVM は依存解決にしか使われず、oracle は
;; 最初から最後まで nbb（ClojureScript）で実行される —— 出力の
;; `executing shipped KIR on node vXX` がその証拠。
(defn- needs-jvm-resolution?
  "repo root の nbb.edn が :deps を宣言しているか（= nbb が deps.clj を呼ぶか）。"
  []
  (some-> (slurp* (path/join root "nbb.edn")) (str/includes? ":deps")))

(defn- java-runnable? [env]
  (let [r (.spawnSync cp "java" #js ["-version"] #js {:encoding "utf8" :env env})]
    (and (not (.-error r)) (zero? (or (.-status r) 1)))))

(defn- jdk-bin
  "ノードで実際に動く JDK の bin。決め打ちしないで順に測る。"
  []
  (let [candidates (cond-> []
                     (.-JAVA_HOME (.-env js/process))
                     (conj (path/join (.-JAVA_HOME (.-env js/process)) "bin"))
                     true (into ["/opt/homebrew/opt/openjdk/bin"
                                 "/usr/local/opt/openjdk/bin"])
                     true (conj (let [r (.spawnSync cp "/usr/libexec/java_home" #js []
                                                    #js {:encoding "utf8"})]
                                  (when (zero? (or (.-status r) 1))
                                    (path/join (str/trim (str (.-stdout r))) "bin")))))]
    (first (filter #(and % (exists? (path/join % "java"))) candidates))))

(def gate-env
  (let [base (.-env js/process)]
    (if-not (needs-jvm-resolution?)
      base
      (if (java-runnable? base)
        base
        (if-let [bin (jdk-bin)]
          (do (println (str "nbb.edn declares :deps (deps.clj resolution needs a JVM); "
                            "prepending " bin " to PATH"))
              (js/Object.assign #js {} base
                                #js {"PATH" (str bin ":" (.-PATH base))}))
          (do (println "WARNING: nbb.edn declares :deps and no runnable JVM was found —"
                       "nbb's deps.clj resolution will fail. Give this gate a :cap :jvm node.")
              base))))))

(defn- cases-in
  "How many cases the entry reports having run, or nil if it reported none.

  Two shapes are accepted because two families of gate exist in the fleet: a
  bespoke oracle gate (`ran 220 cases over 117 shipped exports`) and a
  clojure.test runner on nbb (`Ran 54 tests containing 221 assertions`). A
  third shape should be added here rather than loosened into something that
  matches prose."
  [out]
  (or (some-> (re-find #"(?i)\bran\s+(\d+)\s+cases?\b" out) second js/parseInt)
      (some-> (re-find #"(?i)\bRan\s+(\d+)\s+tests?\s+containing\b" out) second js/parseInt)))

(defn- clojure-test-failures
  "For the clojure.test shape only: its exit code is not always the verdict."
  [out]
  (when-let [m (re-find #"(\d+)\s+failures?,\s+(\d+)\s+errors?" out)]
    [(js/parseInt (second m)) (js/parseInt (nth m 2))]))

(cond
  (nil? entry)
  (fail! "no --entry given — this gate runs a repo's own ClojureScript gate and needs its path")

  (not (exists? (path/join root entry)))
  (fail! (str "no entry at " entry " — the tree shipped to this node does not contain it"
              " (check :include-ext covers .cljs/.cljc and the artifact's extension)"))

  :else
  (let [{:keys [paths missing sibling]} (resolve-classpath)
        cp-str (str/join ":" paths)]
    (println "entry:     " entry "  nbb:" (or nbb-override (str "npx nbb@" nbb-version))
             "  min-cases:" min-cases)
    ;; The count, not the string: com-cloudflare resolves ~90 dependency source
    ;; dirs and a receipt full of them hides the verdict. The full classpath is
    ;; printed only when something failed and it is evidence.
    (println "classpath: " (count paths) "entries, first:"
             (str/join ":" (take 3 paths)))
    (when nbb-override
      (println "NOTE: --nbb override in use — NOT the pinned interpreter:" nbb-override))
    (when (seq sibling)
      (println "NOTE: resolved from sibling checkouts, NOT sha-pinned:"
               (str/join ", " sibling)))
    (if (seq missing)
      (fail! (str "unresolved git deps (not in ~/.gitlibs and no sibling checkout): "
                  (str/join ", " missing) " — ship-git-deps! should have placed these"))
      (let [pkg (path/join root "package.json")
            _ (when (and (exists? pkg) (not (exists? (path/join root "node_modules"))))
                (println "npm install (package.json present, node_modules absent)")
                (.spawnSync cp "npm" #js ["install" "--silent" "--omit=dev"]
                            #js {:cwd root :encoding "utf8" :stdio "inherit"}))
            [bin bin-args] (if nbb-override
                             [nbb-override ["--classpath" cp-str entry]]
                             ["npx" ["--yes" (str "nbb@" nbb-version)
                                     "--classpath" cp-str entry]])
            r (.spawnSync cp bin (clj->js bin-args)
                          #js {:cwd root :encoding "utf8" :env gate-env})
            out (str (.-stdout r) (.-stderr r))
            status (.-status r)
            ran (cases-in out)
            [f e] (or (clojure-test-failures out) [0 0])]
        (println (str/join "\n" (take-last 30 (str/split-lines out))))
        (cond
          (not (zero? status))
          (do (println "classpath was:" cp-str)
              (fail! (str "the repo's ClojureScript gate exited " status)))

          (nil? ran)
          (fail! (str "exit 0, but no case count in the output — refusing to report a pass. "
                      "The entry must print `ran N cases …` or a clojure.test summary; "
                      "without it, a gate whose artifact failed to load reports success."))

          (< ran min-cases)
          (fail! (str "only " ran " cases ran, floor is " min-cases
                      " — a case table that emptied itself still exits 0"))

          (or (pos? f) (pos? e))
          (fail! (str ran " cases, " f " failures, " e " errors"))

          :else
          (println (str "OK: " ran " cases on ClojureScript, 0 failures")))))))

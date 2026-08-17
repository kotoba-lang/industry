#!/usr/bin/env nbb
;; verify-jvm-dependency-surface — where this workspace still needs a JVM, and
;; what each of those places would have to become to stop needing one.
;;
;; ## Why this exists
;;
;; CLAUDE.md fixes the runtime order as `kotoba wasm` > `clojurewasm` >
;; ClojureScript > nbb, with JVM and babashka demoted to last resort, and
;; `90-docs/migration/kotoba-wasm-runtime-cutover.edn` freezes a JVM inventory
;; of exactly four entries under `orgs/kotoba-lang/`. That contract is correct
;; about the four it names and says nothing about the other 4,179 registered
;; repositories. Measured 2026-08-17, the JVM surface outside those four is
;; three orders of magnitude larger. A frozen inventory that covers 4 of N is
;; not wrong, but on its own it reads as though N were 4.
;;
;; This detector answers the question the frozen inventory does not: across
;; every west-registered checkout, WHERE is a JVM still required, and for WHAT.
;;
;; ## Why "does it have maven deps" is the wrong question
;;
;; 3,683 of 3,994 `deps.edn` files carry a `:mvn/version` coordinate, which
;; sounds like near-total JVM capture. It is not: 3,293 of them are a
;; `clj-kondo` lint alias, a tool with a native binary and an npm package that
;; never needs to be a JVM dependency at all. Counting files with maven
;; coordinates would report a catastrophe and point at a rename.
;;
;; So classification is by WHAT THE JVM IS FOR, because that is what determines
;; the exit:
;;
;;   :jvm-source    `.clj` under the repo's own source tree. The JVM is not a
;;                  tool here, it is the runtime. Exit = port to `.cljc`/`.cljs`
;;                  (portable) or to `.kotoba` (decision core).
;;   :jvm-runtime-deps  maven coordinates in the TOP-LEVEL `:deps` map. Shipped
;;                  code resolves them. Exit = a cljs/npm equivalent, or drop.
;;   :jvm-build     shadow-cljs / ClojureScript compiler / tools.build in an
;;                  alias. Produces JS but spawns a JVM to do it.
;;   :jvm-test      cognitect test-runner / kaocha in an alias. CLAUDE.md's
;;                  cutover contract already allows these as
;;                  `:historical-clj-tests :allowed-as-oracle-until-replaced`,
;;                  so they are reported at :info and never as a failure.
;;   :jvm-lint      clj-kondo only. The cheapest exit in the whole set.
;;   :jvm-chicory   Chicory, the JVM Wasm runtime. The cutover contract names
;;                  `:new-chicory-call-sites :forbidden`, so any site outside
;;                  the frozen four is a contract breach, not a debt.
;;   :babashka      `bb.edn`. ADR-2607173000 retired bb as a script host.
;;
;; ## Two things this detector refuses to do
;;
;; 1. It refuses to count unregistered checkouts. `orgs/` on this machine holds
;;    4,581 repo-shaped directories against west.yml's 4,183, and the surplus is
;;    agent worktrees -- `.tamaki-tamaki-loop-*`, `.cloud-itonami-app-adopt`,
;;    `.wt-run`. Measured 2026-08-17 they contribute 3,281 `.clj` files, 15% of
;;    the raw total, and every one of them is a copy of a file already counted
;;    under its registered path. Counting them would report the same debt many
;;    times and make the number move whenever a loop happened to be running.
;; 2. It refuses to report a pass it did not measure. If west.yml yields no
;;    paths, or fewer than `min-repos` of them exist on disk, it prints
;;    `SCANNED\t0` and exits 2 -- neither 0 nor 1 -- because "I could not look"
;;    and "I looked and it was clean" must not be the same value. This is the
;;    five-question rule in CLAUDE.md (ADR-2608136000) applied to its own output.
;;
;; ## Output
;;
;;   --findings   FINDING<TAB>sev<TAB>key<TAB>detail, plus SCANNED<TAB>n<TAB>unit,
;;                for scripts/orgs-detector-tick.cljs. Keys are structural
;;                (kind + repo path) and carry no counts, so a repo that still
;;                has the same defect tomorrow keys the same tomorrow.
;;   --edn <f>    full per-repo inventory, for planning a migration tranche.
;;   --kind <k>   restrict findings to one kind.
;;   --strict     exit 1 when any :fail-severity finding exists.
(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def node-path (js/require "node:path"))
(def argv (vec (drop 2 js/process.argv)))
(defn flag? [f] (some #{f} argv))
(defn opt [f] (second (drop-while #(not= f %) argv)))
(def findings? (flag? "--findings"))
(def strict? (flag? "--strict"))
(def edn-out (opt "--edn"))
(def only-kind (some-> (opt "--kind") keyword))
(def root (.cwd js/process))
(defn full [p] (.join node-path root p))

;; The floor from invariant 2. A run that finds fewer registered checkouts than
;; this on disk is answering from an empty or half-checked-out workspace and
;; must not be recorded as clean.
(def min-repos 500)

;; ---------------------------------------------------------------------------
;; Registered paths. west.yml is generated, so `path:` is emitted one per line
;; at a fixed indent; parsing it as YAML would need a dependency this detector
;; deliberately does not have.
(def registered
  (->> (str/split-lines (.readFileSync fs (full "manifest/west.yml") "utf8"))
       (keep #(second (re-find #"^\s+path:\s*(\S+)\s*$" %)))
       (into (sorted-set))))

(when (empty? registered)
  (println "SCANNED\t0\tregistered repo(s) -- west.yml yielded no path: entries")
  (binding [*out* *err*]
    (println "Refusing to report a pass: manifest/west.yml parsed to zero paths."))
  (.exit js/process 2))

;; ---------------------------------------------------------------------------
;; Walking one checkout.
(def prune-dirs
  #{"node_modules" ".git" "target" ".cpcache" ".shadow-cljs" "out" "dist"
    ".datalad" ".claude" "vendor"})

(defn walk-files
  "Every file under `dir`, pruning build output and nested checkouts. Dot-dirs
   are pruned because on this machine they are agent worktrees, never sources."
  [dir]
  (let [acc (volatile! [])]
    (letfn [(go [d depth]
              (when (< depth 12)
                (doseq [e (try (.readdirSync fs d #js {:withFileTypes true})
                               (catch :default _ #js []))]
                  (let [nm (.-name e)
                        p (.join node-path d nm)]
                    (cond
                      (.isDirectory e)
                      (when-not (or (contains? prune-dirs nm)
                                    (str/starts-with? nm "."))
                        (go p (inc depth)))
                      (.isFile e) (vswap! acc conj p))))))]
      (go dir 0))
    @acc))

(defn test-path? [p]
  (or (re-find #"(^|/)(test|tests)/" p)
      (re-find #"_test\.[a-z]+$" p)
      (re-find #"(^|/)dev/" p)))

;; ---------------------------------------------------------------------------
;; deps.edn classification.
(def lint-coords #{"clj-kondo/clj-kondo"})
(def test-coords #{"io.github.cognitect-labs/test-runner" "lambdaisland/kaocha"
                   "cloverage/cloverage" "org.clojure/test.check"})
(def build-coords #{"thheller/shadow-cljs" "org.clojure/clojurescript"
                    "io.github.clojure/tools.build" "com.thheller/shadow-css"})

;; The four places a Clojure CLI dep map can hide, measured rather than assumed:
;; `:replace-deps` is what clj-kondo's own recommended alias uses, and reading
;; only `:extra-deps`/`:deps` found 30 clj-kondo sites where there are 3,000+.
(def dep-map-keys [:deps :extra-deps :replace-deps :override-deps :default-deps])

;; A git coordinate pointing back into this workspace is a graph edge, not a
;; leaf JVM dependency: whether it drags in a JVM depends on what that repo is
;; written in, which this detector measures separately under its own path.
;; Only maven jars and EXTERNAL git deps are leaves.
(def workspace-orgs
  #{"kotoba-lang" "cloud-itonami" "com-junkawasaki" "etzhayyim" "gftdcojp"
    "network-awai" "net-kotobase" "jk-luxury" "kawasakijun" "personal"})

(defn coord-org
  "The GitHub org a git coordinate resolves to. `:git/url` when present, else
   derived from the `io.github.<org>/<repo>` symbol the way the Clojure CLI
   itself derives it -- 424 workspace deps pin `:git/tag`+`:git/sha` with no
   url and were being counted as external before this read the symbol too."
  [sym v]
  (or (second (re-find #"github\.com[:/]([^/]+)/" (str (:git/url v ""))))
      (second (re-find #"^(?:io|com|net)\.github\.([^/]+)/" sym))))

(defn coord-kind [sym v]
  (cond
    (not (map? v)) :unknown
    (contains? v :mvn/version) :maven
    (contains? v :local/root) :local
    (or (contains? v :git/url) (contains? v :git/sha) (contains? v :git/tag))
    (if (workspace-orgs (coord-org sym v)) :workspace-git :external-git)
    :else :unknown))

(defn coords
  "coordinate-string -> kind, across every dep map in `m`."
  [m]
  (when (map? m)
    (into {} (for [k dep-map-keys
                   [sym v] (get m k)
                   :when (symbol? sym)]
               [(str sym) (coord-kind (str sym) v)]))))

(defn classify-deps-file [p]
  (let [text (try (.readFileSync fs p "utf8") (catch :default _ nil))
        form (when text
               (try (edn/read-string {:default (fn [_ v] v)} text)
                    (catch :default _ ::unparsed)))]
    (cond
      (nil? text) {:unreadable true}
      (= ::unparsed form) {:unparsed true}
      (not (map? form)) {:unparsed true}
      :else
      (let [top (coords form)
            alias-coords (apply merge (map coords (filter map? (vals (:aliases form)))))
            all (merge alias-coords top)
            ;; A leaf needs a JVM by itself. A workspace git/local edge does not.
            leaf? (fn [c] (contains? #{:maven :external-git} (get all c)))
            top-leaves (set (filter leaf? (keys top)))]
        {:all-coords all
         :chicory (set (filter #(str/includes? % "chicory") (keys all)))
         ;; Runtime = a leaf JVM dependency in the TOP-LEVEL :deps, i.e. one
         ;; that shipped code resolves, not one a tool alias opts into.
         ;;
         ;; Split, because the two halves have different exits and lumping them
         ;; would misreport the size of the problem by 9x. `org.clojure/clojure`
         ;; alone is a DECLARATION: measured 2026-08-17, 1,210 of the 1,292
         ;; repos whose only runtime coord is Clojure itself contain zero .clj
         ;; files, so nothing they ship needs a JVM -- the line pins a version
         ;; for the test alias. A third-party jar is a real BINDING: cheshire,
         ;; http-kit, ring, datalevin, transit-clj have no cljs/kotoba form, so
         ;; the code that requires them cannot run anywhere else.
         :runtime (set (remove #(or (lint-coords %) (test-coords %) (build-coords %))
                               top-leaves))
         :runtime-third-party (set (remove #(or (lint-coords %) (test-coords %)
                                                (build-coords %)
                                                (= "org.clojure/clojure" %))
                                          top-leaves))
         :lint (set (filter #(and (leaf? %) (lint-coords %)) (keys all)))
         :test (set (filter #(and (leaf? %) (test-coords %)) (keys all)))
         :build (set (filter #(and (leaf? %) (build-coords %)) (keys all)))}))))

;; ---------------------------------------------------------------------------
;; Per-repo inventory.
(def stats (atom {:registered (count registered) :present 0 :walked 0
                  :deps-files 0 :deps-unparsed 0}))

(defn inventory [rel]
  (let [abs (full rel)]
    (when (try (.isDirectory (.statSync fs abs)) (catch :default _ false))
      (swap! stats update :present inc)
      (let [files (walk-files abs)
            rels (map #(.relative node-path abs %) files)
            by-ext (group-by #(last (str/split % #"\.")) rels)
            cljs-all (get by-ext "clj" [])
            clj-src (remove test-path? cljs-all)
            clj-test (filter test-path? cljs-all)
            deps-files (filter #(= "deps.edn" (.basename node-path %)) rels)
            bb? (some #(= "bb.edn" (.basename node-path %)) rels)
            deps (map (fn [d] (assoc (classify-deps-file (.join node-path abs d))
                                     :file d))
                      deps-files)]
        (swap! stats update :walked inc)
        (swap! stats update :deps-files + (count deps-files))
        (swap! stats update :deps-unparsed + (count (filter :unparsed deps)))
        {:repo rel
         :clj-src (count clj-src)
         :clj-src-sample (vec (take 3 clj-src))
         :clj-test (count clj-test)
         :cljc (count (get by-ext "cljc" []))
         :cljs (count (get by-ext "cljs" []))
         :kotoba (count (get by-ext "kotoba" []))
         :bb bb?
         :deps deps
         :runtime (into #{} (mapcat :runtime deps))
         :runtime-third-party (into #{} (mapcat :runtime-third-party deps))
         :chicory (into #{} (mapcat :chicory deps))
         :lint (into #{} (mapcat :lint deps))
         :test-tool (into #{} (mapcat :test deps))
         :build (into #{} (mapcat :build deps))}))))

(def repos (vec (keep inventory registered)))

;; ---------------------------------------------------------------------------
;; Evidence floor: refuse to answer from a workspace that is not there.
(when (< (:present @stats) min-repos)
  (println (str "SCANNED\t" (:present @stats) "\tregistered repo(s) present on disk"))
  (binding [*out* *err*]
    (println (str "Refusing to report a pass: only " (:present @stats)
                  " of " (count registered) " registered checkouts exist"
                  " (floor " min-repos "). This run could not measure the"
                  " workspace, which is not the same as measuring it clean.")))
  (.exit js/process 2))

;; ---------------------------------------------------------------------------
;; Findings. Severity encodes what the cutover contract already decided:
;; chicory outside the frozen four is a breach; `.clj` sources and runtime maven
;; deps are the debt; test/lint tooling is allowed and reported for planning.
(def frozen-chicory
  #{"orgs/kotoba-lang/kototama" "orgs/kotoba-lang/aiueos"
    "orgs/kotoba-lang/kotoba" "orgs/kotoba-lang/amu"
    "orgs/kotoba-lang/compiler"})

;; Which classes get a per-repo FINDING line, and which are counted only.
;;
;; Every EMITTED finding names something that genuinely cannot run under
;; ClojureScript or Kotoba as it stands. The counted-only classes are the ones
;; where a per-repo key would carry no decision:
;;
;;   :jvm-test / :jvm-test-oracle  the cutover contract allows them outright
;;                                 (`:historical-clj-tests
;;                                 :allowed-as-oracle-until-replaced`)
;;   :jvm-lint                     a clj-kondo alias, 3,023 repos, identical
;;                                 in all of them
;;   :jvm-runtime-clojure-only     `org.clojure/clojure` and nothing else in
;;                                 :deps -- 1,292 repos, and 1,210 of them ship
;;                                 zero .clj, so the line pins a test-alias
;;                                 version rather than binding anything
;;
;; Together those four cover ~8,300 repo-class pairs, none of which names a
;; defect. Emitting them would make the tick's baseline five times the size of
;; the part that does, and bury it.
(def emitted-kinds
  #{:jvm-chicory :jvm-source :jvm-runtime-deps :jvm-build :babashka})

(defn findings-for [{:keys [repo clj-src clj-test runtime runtime-third-party
                            chicory lint test-tool build bb kotoba]}]
  (cond-> []
    (seq chicory)
    (conj {:sev (if (frozen-chicory repo) "info" "fail") :kind :jvm-chicory :repo repo
           :detail (str "Chicory JVM Wasm runtime: " (str/join " " (sort chicory))
                        (when (frozen-chicory repo) " [frozen inventory]"))})
    (pos? clj-src)
    (conj {:sev "fail" :kind :jvm-source :repo repo
           :detail (str clj-src " .clj source file(s); " kotoba " .kotoba")})
    ;; A third-party jar is the binding; Clojure-only is the declaration.
    (seq runtime-third-party)
    (conj {:sev "fail" :kind :jvm-runtime-deps :repo repo
           :detail (str "third-party JVM lib(s) in top-level :deps: "
                        (str/join " " (take 4 (sort runtime-third-party))))})
    (and (seq runtime) (empty? runtime-third-party))
    (conj {:sev "warn" :kind :jvm-runtime-clojure-only :repo repo
           :detail (str "org.clojure/clojure in top-level :deps with "
                        clj-src " .clj source file(s)"
                        (when (zero? clj-src) " -- nothing shipped here needs a JVM"))})
    (seq build)
    (conj {:sev "warn" :kind :jvm-build :repo repo
           :detail (str "JVM build toolchain: " (str/join " " (sort build)))})
    bb
    (conj {:sev "warn" :kind :babashka :repo repo
           :detail "bb.edn present; ADR-2607173000 retired bb as a script host"})
    (pos? clj-test)
    (conj {:sev "info" :kind :jvm-test-oracle :repo repo
           :detail (str clj-test " .clj test file(s) [allowed-as-oracle-until-replaced]")})
    (seq test-tool)
    (conj {:sev "info" :kind :jvm-test :repo repo
           :detail (str "JVM test runner: " (str/join " " (sort test-tool)))})
    (seq lint)
    (conj {:sev "info" :kind :jvm-lint :repo repo
           :detail "clj-kondo via maven; a native binary and an npm package exist"})))

(def all-findings
  (cond->> (mapcat findings-for repos)
    only-kind (filter #(= only-kind (:kind %)))))

;; ---------------------------------------------------------------------------
;; Human report.
(defn tally [k] (count (filter #(= k (:kind %)) all-findings)))
(println "verify-jvm-dependency-surface" (pr-str @stats))
(println)
(println "  kind                        repos  what a JVM is doing there")
(doseq [[k what]
        [[:jvm-chicory      "Chicory JVM Wasm runtime (contract: new sites forbidden)"]
         [:jvm-source       ".clj sources -- the JVM is the runtime, not a tool"]
         [:jvm-runtime-deps "third-party JVM lib in top-level :deps"]
         [:jvm-build        "shadow-cljs / cljs compiler / tools.build"]
         [:babashka         "bb.edn (retired script host)"]
         [:jvm-runtime-clojure-only "org.clojure/clojure only -- a declaration"]
         [:jvm-test-oracle  ".clj tests (allowed as oracle)"]
         [:jvm-test         "JVM test runner in an alias"]
         [:jvm-lint         "clj-kondo via maven"]]]
  (let [n (str (tally k))
        mark (if (emitted-kinds k) "*" " ")]
    (println (str "  " mark (subs (str (name k) "                          ") 0 26)
                  (subs (str "     " n) (count n)) "  " what))))
(println "  (* emitted as per-repo FINDING lines; the rest are counted only)")
(println)
(println "  totals: .clj src" (reduce + (map :clj-src repos))
         "| .clj test" (reduce + (map :clj-test repos))
         "| .cljc" (reduce + (map :cljc repos))
         "| .cljs" (reduce + (map :cljs repos))
         "| .kotoba" (reduce + (map :kotoba repos)))

(when edn-out
  (.writeFileSync fs edn-out (pr-str {:stats @stats :repos repos}) "utf8")
  (println "  wrote" edn-out))

(when findings?
  (println (str "SCANNED\t" (:present @stats) "\tregistered repo(s) present on disk"))
  (doseq [f (sort-by (juxt :kind :repo) all-findings)
          :when (emitted-kinds (:kind f))]
    (println (str "FINDING\t" (:sev f) "\t" (name (:kind f)) ":" (:repo f)
                  "\t" (:detail f)))))

(when (and strict? (some #(= "fail" (:sev %)) all-findings))
  (.exit js/process 1))

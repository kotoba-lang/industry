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
;; ## The key is the namespace, and the extension is not part of it
;;
;; This once keyed on the relative path INCLUDING the extension, so
;; `kami/backend/browser.cljc` in `kotoba-lang/host` and
;; `kami/backend/browser.cljs` in `kami-engine-sdk` were two entries and no
;; collision was reported. Both files declare `kami.backend.browser`. A build
;; loads one of them.
;;
;; That gap hid the WORSE half of the problem rather than a marginal one.
;; ClojureScript prefers `.cljs` over `.cljc` -- and Clojure `.clj` over `.cljc`
;; -- REGARDLESS of classpath order. Every collision this script found before
;; has a remedy in ordering; this class does not. `network-isekai`'s own
;; `deps.edn` records having been bitten by exactly it, and ADR-2608123200 names
;; `kami/backend/browser` as the live instance the detector could not see.
;;
;; ## But two extensions in ONE repo are how you write a namespace
;;
;; `foo.clj` beside `foo.cljs` is the ordinary platform split, and `foo.cljc`
;; beside a platform file is the ordinary override. Measured 2026-08-12, 20
;; namespaces are authored that way here -- `kotoba/signal/*` in `org-signal`,
;; `mangaka/*` in `cloud-itonami/mangaka`, `kotobase/engine`. Collapsing
;; extensions naively turns all 20 into findings, which is trading one blindness
;; for another.
;;
;; So a repo is ONE owner of a namespace however many files it ships for it, and
;; a collision is two owners. The extension only matters ACROSS repos, where
;; nobody chose the pairing.
;;
;; ## What divergent means when the candidates are different files
;;
;; The obvious move is to give each owner a signature of what it ships -- the
;; extension/content pairs -- and call the owners divergent when the signatures
;; disagree. Under that rule two owners with different extensions are ALWAYS
;; divergent, because their signatures cannot match.
;;
;; It was tried, and measurement says it is wrong. Files with different
;; extensions ARE byte-identical here: `mangaka/runtime.clj` and
;; `mangaka/runtime.cljc` in `cloud-itonami/mangaka` and `mangaka/runtime.cljc`
;; in `ai-gftd-mangaka` are one sha across all three. Nothing about that is
;; hazardous -- whichever one a build picks, it gets the same code -- and the
;; signature rule reported it.
;;
;; So the byte test stays exactly as it was and only the GROUPING changes: a
;; namespace is divergent when the files that could win do not all agree. That
;; keeps one definition of divergent for every finding, and lets the extension
;; question be answered by the same evidence rather than by a rule about
;; filenames.
;;
;; Regrouping alone reclassifies five namespaces that were called byte-identical
;; and were not safe. `cloud-itonami/mangaka` and `ai-gftd-mangaka` ship
;; `mangaka/server.cljc` at one sha -- the agreement the old report saw -- and
;; `cloud-itonami` also ships a `mangaka/server.clj` at a DIFFERENT sha. On the
;; JVM that `.clj` wins over both `.cljc`s, so the content the two repos agree
;; on is the content nobody loads. Comparing file to file said safe; comparing
;; what each repo makes available for the namespace says otherwise.
;;
;; EXTENSION-SHADOWED marks the divergent findings where one repo's `.clj` or
;; `.cljs` covers another repo's `.cljc`, because that is the subset ordering
;; cannot fix. `.clj` against `.cljs` alone does not qualify -- those never
;; compete, since neither platform can load the other's file.
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
;; where that problem belongs. It is decided over the whole owner set, which is
;; the weaker of the two available tests and is kept deliberately -- see
;; `same-upstream?` for what asking it per pair of owners costs.
;;
;; ## A source root is what a repo declares, not what it is called
;;
;; Which trees to read was once answered by the name `src`, and narrowing it to
;; declared trees was bolted on as a filter over that guess. An intersection
;; cannot be wider than either side, so `src-cljs` was dropped for its name
;; however plainly it was declared -- 21 such trees, 51 files. The declaration
;; is the list now. `source-roots` documents what a declared root still has to
;; satisfy, and both conditions were found by measurement, not by reasoning.
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

;; --findings additionally emits one machine-readable line per finding:
;;
;;   FINDING<TAB>severity<TAB>key<TAB>detail
;;
;; for scripts/orgs-detector-tick.cljs, which needs a stable identity per
;; finding so it can tell a collision that appeared today from one that has
;; stood for weeks. This check cannot be a fleet-CI gate -- a gate is shipped
;; only the target repo's own tree and this question is about two repos at
;; once -- so the registry in manifest/orgs-detectors.edn is where it runs.
;;
;; severity `fail` is exactly the set --check exits 1 on (the co-classpath
;; divergent collisions). `warn` is report-only: EXTENSION-SHADOWED namespaces
;; that no closure puts on one classpath cannot bite today, but they are the
;; class ordering cannot fix if a dependency is ever added, so a NEW one should
;; be visible without ever gating on it. Self-shadowing files are `warn` for
;; the same reason -- one repo, decided by the order its own trees are declared.
;;
;; The flag changes nothing about what is measured, what is printed, or the
;; exit code. It is matched by set membership like --all and --check, so no
;; positional parsing can be disturbed by it.
(def findings? (some #{"--findings"} argv))

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

(defn- declared-source-roots
  "Directories a repo actually SHIPS, from top-level `:paths` / `:source-paths`.

  `nil` means the repo declares nothing anywhere, and the caller should then
  include every source tree it can find rather than none.

  Deliberately excludes `:extra-paths`. An alias-only tree is on the classpath
  only for whoever invokes that alias in that repo: tools.deps does not export
  a dependency's aliases, so a consumer can never inherit one. That distinction
  is the whole point -- see the caller.

  Also drops a root that IS the directory declaring it -- `:paths [\".\"]`. Such
  a root is on the classpath here for resources or for a script, never for a
  package tree, and both repos that say so document it: `kotoba-lang/kotoba-lang`
  keeps `.` because `kotoba.launcher` reads `lang/cli.edn` through `io/resource`
  and dropping it cost 66 load errors, and the twenty `games/*/deps.edn` under
  `kami-genre-base-systems` keep it so `clojure -M author.clj` resolves -- those
  files carry no `ns` form at all. Honouring `.` as a namespace root instead
  re-derives every namespace WITH the real tree's own name still on the front,
  so `src/kotoba/lang/package_registry.cljc` appears beside the true
  `kotoba/lang/package_registry.cljc`; because `kotoba-lang` and
  `kotoba-core-contracts` both declare `.`, those phantoms even collide with
  each other and read as a second, independent finding for one file."
  [repo-dir]
  (letfn [(files [cur depth acc]
            (if (> depth 6)
              acc
              (reduce (fn [acc e]
                        (let [n (.-name e) full (path/join cur n)]
                          (cond
                            (and (not (.isDirectory e))
                                 (#{"deps.edn" "shadow-cljs.edn"} n)) (conj acc full)
                            (not (.isDirectory e)) acc
                            (str/starts-with? n ".") acc
                            (#{"node_modules" "target" "out" "dist"} n) acc
                            :else (files full (inc depth) acc))))
                      acc
                      (try (js->clj (fs/readdirSync cur #js {:withFileTypes true}))
                           (catch :default _ [])))))]
    (let [fs' (files repo-dir 0 [])
          roots (reduce
                 (fn [acc f]
                   (let [text (try (str (fs/readFileSync f "utf8")) (catch :default _ ""))
                         ;; `:extra-paths` does not contain the substring
                         ;; `:paths` -- there is no colon before `paths` in it --
                         ;; so a literal search cannot pick aliases up by accident.
                         m (concat (re-seq #":paths\s*\[([^\]]*)\]" text)
                                   (re-seq #":source-paths\s*\[([^\]]*)\]" text))
                         dir (path/dirname f)]
                     (into acc
                           (for [[_ body] m
                                 [_ p] (re-seq #"\"([^\"]+)\"" body)
                                 :let [abs (path/normalize (path/join dir p))]
                                 :when (not= abs (path/normalize dir))]
                             abs))))
                 #{} fs')]
      (when (seq fs') roots))))

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

  This is the FALLBACK, used only for a repo that declares no `:paths` anywhere
  -- see `source-roots`. For everyone else the declaration is the list, because
  a source root is whatever a repo says it is and nothing about the word `src`
  makes a directory one. Guessing by name missed 21 `src-*` trees holding 51
  Clojure files, among them `wasm-webcomponent/src-cljs/vendor/kotoba/
  kami_host.cljc`, a vendored copy of `kotoba-lang/kotoba`'s `kotoba.kami-host`
  that has since drifted from it. That repo is worth the whole example: what it
  has named `src` is shadow-cljs's OUTPUT directory, so filtering by the name
  did not merely miss a tree, it selected the build product and discarded the
  two source trees the config actually names.

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

(defn- source-roots
  "The directories `root` puts on a classpath: what it DECLARES, if it declares
  anything, and otherwise every tree named `src` that it has.

  A declared root has to survive two checks beyond existing and being a
  directory, and both are load-bearing.

  It must lie inside the repo. Nested shadow-cljs configs here reach sideways
  with `../..` -- `net-kotobase` names 28 roots that resolve into OTHER repos,
  `kotoba-lang/pay/src` and `kotoba-lang/kotobase/src` among them, because a
  worker builds against sibling checkouts. Taking those at face value would
  make `net-kotobase` an owner of `kotoba-lang/pay`'s files, so `pay/core.cljc`
  would be reported as shipped twice by two repos when one file is on disk. It
  also breaks the opposite way: the extra owner arrives with its own root
  commit, and every DUPLICATE REGISTRATION pair it lands in stops looking like
  one upstream.

  And it must not be the directory that declared it -- see
  `declared-source-roots` for why `:paths [\".\"]` is a resource root here and
  never a package tree."
  [root]
  (let [declared (declared-source-roots root)
        inside? (fn [p] (str/starts-with? p (str root "/")))
        dir? (fn [p] (try (.isDirectory (fs/statSync p)) (catch :default _ false)))]
    (filter dir? (if declared (filter inside? declared) (src-dirs root)))))

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
                 ;; A tree counts when the repo DECLARES it. That rule arrived
                 ;; as a filter over directories named `src`, to stop
                 ;; `test/e2e/src` being read as a source root: three repos in
                 ;; the kami-ongaku family were reported as colliding on
                 ;; `kami/ongaku/e2e/*` when no build can put two of those trees
                 ;; on one classpath -- each is reachable only through its own
                 ;; `:e2e` alias, and tools.deps does not export a dependency's
                 ;; aliases. Measured with `clojure -Spath -A:e2e` in the one
                 ;; repo depending on two of them: neither tree appears.
                 ;;
                 ;; The rule was right and the filter was the wrong shape for
                 ;; it. An intersection with `src`-named directories can only
                 ;; ever be as wide as the guess, so a declared `src-cljs` was
                 ;; dropped for its name however plainly it was declared. The
                 ;; declaration is now the list itself and the name test is
                 ;; gone; `source-roots` says what a declared root still has to
                 ;; satisfy. `kami/ongaku/e2e/*` stays out for the reason it
                 ;; always should have -- nobody declares those trees.
                 (map (fn [src] {:repo (str/replace root #"^orgs/" "")
                                 :root root
                                 :src src})
                      (source-roots root))))
       vec))

(defn- index
  "relative file path (extension included) -> [{:repo :file :sha}]

  Kept at file granularity because SELF-SHADOWING is a question about files:
  one repo declaring two source trees that both hold `foo.cljc` is decided by
  the order those trees enter the classpath. The cross-repo report regroups this
  by namespace -- see `by-namespace`."
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

(defn- ns-key
  "The namespace a source file declares, as a path with the extension removed.

  Only the extension. The underscore is NOT rewritten to a hyphen even though
  the real namespace has it: `foo_bar.cljc` is loadable as `foo.bar` and a
  literal `foo-bar.cljc` is loadable as nothing, so folding them would merge a
  namespace with a file that cannot hold one and report a collision between a
  thing and a non-thing."
  [rel]
  (or (some (fn [e] (when (str/ends-with? rel e) (subs rel 0 (- (count rel) (count e)))))
            source-extensions)
      rel))

(defn- by-namespace
  "namespace -> {repo -> [{:ext :sha :file}]}, over the whole file index.

  A repo appears once per namespace however many files it ships for it. That is
  the point: `foo.clj` beside `foo.cljs` in one repo is how the namespace is
  authored, not a fight over it, and there are 20 such namespaces here."
  [idx]
  (reduce-kv
   (fn [acc rel owners]
     (let [k (ns-key rel)
           ext (subs rel (count k))]
       (reduce (fn [acc {:keys [repo file sha]}]
                 (update-in acc [k repo] (fnil conj []) {:ext ext :file file :sha sha}))
               acc owners)))
   {} idx))

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
        ;; Deduplicated first: a repo contributes one checkout however many
        ;; source trees it declares, and `root-commit` forks a git per call.
        roots (into {} (map (fn [[repo root]] [repo (root-commit root)]))
                    (distinct (map (juxt :repo :root) rs)))
        ;; Owners are counted per REPOSITORY, not per file. A repo can contribute
        ;; several source trees and, since the key became the namespace rather
        ;; than the filename, several extensions -- either would otherwise let
        ;; one repo appear twice in `owners` and satisfy "two of these are on one
        ;; classpath" by itself, a collision manufactured out of a single
        ;; project. `by-namespace` groups by repo for that reason, so everything
        ;; downstream is handed repo NAMES.
        owner-repos (fn [owners] (vec (distinct (map :repo owners))))
        within-repo? (fn [owners] (= 1 (count (owner-repos owners))))
        co-classpath?
        (fn [names]
          (boolean
           (and (> (count names) 1)
                (some (fn [[_ seen]]
                        (>= (count (filter #(or (contains? seen %)
                                                (contains? seen (bare-name %)))
                                           names))
                            2))
                      closures))))
        ;; Whether the owners are one repository checked out twice is asked of
        ;; the whole set, and it has to stay that way, though asking it per pair
        ;; of owners is the more obviously correct thing and was tried here.
        ;; Measured 2026-08-12: the only pair of `treasury/core`'s owners that
        ;; any closure reaches together is `gftdcojp/cloud-murakumo` with
        ;; `network-awai/cloud-murakumo`, which is one repo at two paths.
        ;; The rest are co-classpath with nobody. So this finding is
        ;; promoted by a relationship the script itself calls not-a-collision,
        ;; and survives suppression only because owners that took no part in
        ;; promoting it disagree about upstream. Per-pair, both halves are
        ;; decided by the same pair, and the file ADR-2608121000 calls the worst
        ;; copy in the fleet stops being reported at all.
        ;;
        ;; That is worth stating plainly rather than tuning away: eight repos
        ;; ship this namespace with six different contents, one of them
        ;; missing the guard on unconfirmed payments, and CO-CLASSPATH is not
        ;; the axis that finds it -- it is reported by accident. Ranking that
        ;; catches it on purpose needs a second axis this script does not have,
        ;; and `verify-vendored-copies.cljs` is the likelier home for it.
        same-upstream?
        (fn [names]
          (let [rs' (keep #(get roots %) names)]
            (and (= (count rs') (count names))
                 (= 1 (count (distinct rs'))))))
        ;; One repository carrying the same FILE in two of its own source trees.
        ;; Not a cross-repo collision, but not nothing either: which one wins
        ;; depends on the order the trees enter the classpath. Asked of the file
        ;; index rather than the namespace one, because two extensions in one
        ;; repo is authorship and not a shadow -- see `by-namespace`.
        self-shadowing (->> idx
                            (filter (fn [[_ owners]]
                                      (and (> (count owners) 1)
                                           (within-repo? owners)
                                           (> (count (distinct (map :sha owners))) 1))))
                            (map (fn [[rel owners]] {:ns rel :owners owners}))
                            (sort-by :ns))
        ;; A `.clj` or `.cljs` in one repo covering a `.cljc` in ANOTHER. This is
        ;; the subset of collisions that classpath order cannot decide, since
        ;; both compilers prefer the platform file over `.cljc` whatever the
        ;; order. Within one repo it is just how the namespace is authored, so
        ;; the two extensions have to come from different repos.
        ext-shadowed?
        (fn [by-repo]
          (boolean
           (some (fn [[repo files]]
                   (and (some #(#{".clj" ".cljs"} (:ext %)) files)
                        (some (fn [[other others]]
                                (and (not= other repo)
                                     (some #(= ".cljc" (:ext %)) others)))
                              by-repo)))
                 by-repo)))
        cross (->> (by-namespace idx)
                   (filter (fn [[_ by-repo]] (> (count by-repo) 1)))
                   (map (fn [[nsp by-repo]]
                          (let [names (vec (sort (keys by-repo)))
                                files (mapcat (fn [r] (map #(assoc % :repo r) (get by-repo r)))
                                              names)]
                            {:ns nsp
                             :names names
                             :owners files
                             ;; The byte test, unchanged. Only what it is asked
                             ;; about changed: every file that could win the
                             ;; namespace, rather than one filename at a time.
                             :divergent? (> (count (distinct (map :sha files))) 1)
                             :ext-shadowed? (ext-shadowed? by-repo)
                             :co-classpath? (co-classpath? names)
                             :same-upstream? (same-upstream? names)})))
                   (sort-by :ns))
        divergent (filter :divergent? cross)
        identical (remove :divergent? cross)
        co (filter :co-classpath? divergent)
        duplicate-registration (filter :same-upstream? co)
        reachable-divergent (remove :same-upstream? co)
        ;; Divergent, and no closure puts two owners together. Printed only
        ;; under --all, but printed: until now these appeared in no mode at all,
        ;; and they are most of what is found -- 253 of 281. `kotoba/
        ;; kami_host` is here rather than above, `kotoba-lang/kotoba`
        ;; against a vendored copy in `wasm-webcomponent` that has drifted from
        ;; it, and nothing depends on both.
        unreachable-divergent (remove :co-classpath? divergent)
        ;; Owners are printed with the extension each ships, because the key no
        ;; longer carries it and a reader would otherwise have no way to see
        ;; that two owners are not even competing on the same file.
        print-owners
        (fn [owners]
          (doseq [{:keys [repo sha ext]} owners]
            (println (str "             " (subs (or sha "????????") 0 8) "  "
                          repo "  " ext))))]
    (println (str "scanned " (count (distinct (map :repo rs))) " checked-out repos ("
                  (count rs) " source trees), " (count idx) " file paths in "
                  (count (by-namespace idx)) " namespaces"))
    (println (str "collisions: " (count divergent) " divergent, "
                  (count identical) " identical"))
    (println (str "of the divergent, " (count co) " are CO-CLASSPATH -- one owner"
                  " depends on the other, or a third repo depends on both"))
    (println (str "  of those, " (count duplicate-registration)
                  " are ONE repo checked out twice (see"
                  " verify-duplicate-registrations.cljs), leaving "
                  (count reachable-divergent) " real collisions"))
    (println)
    (doseq [{:keys [ns owners ext-shadowed?]} reachable-divergent]
      (println (str "DIVERGENT  " ns
                    (when ext-shadowed?
                      (str "  [EXTENSION-SHADOWED: another repo's platform file"
                           " covers a .cljc here whatever the classpath order]"))))
      (print-owners owners))
    (when (seq duplicate-registration)
      (println)
      (println (str "-- " (count duplicate-registration)
                    " suppressed: same upstream, two checkouts --"))
      (doseq [{:keys [ns]} duplicate-registration] (println (str "   " ns))))
    (when (seq self-shadowing)
      (println)
      (println (str "-- " (count self-shadowing)
                    " file(s) appear twice WITHIN one repo's source trees, with"
                    " different content; which one wins depends on classpath"
                    " order --"))
      (doseq [{:keys [ns owners]} self-shadowing]
        (println (str "   " ns))
        (doseq [{:keys [file sha]} owners]
          (println (str "     " (subs (or sha "????????") 0 8) "  " file)))))
    (when show-all?
      (println)
      (println (str "-- " (count unreachable-divergent)
                    " divergent, but no closure puts two owners on one"
                    " classpath --"))
      (doseq [{:keys [ns owners ext-shadowed?]} unreachable-divergent]
        (println (str "divergent  " ns (when ext-shadowed? "  [EXTENSION-SHADOWED]")))
        (print-owners owners))
      (println)
      (doseq [{:keys [ns names co-classpath?]} identical]
        (println (str "identical  " ns "  (" (count names) " repos"
                      (when co-classpath? ", co-classpath") ")"))))
    (println)
    (println (str "Only checked-out repos were scanned. A clean report means no "
                  "collisions among these, not that none exist."))
    (when findings?
      ;; Zero scanned repos is not a clean run (the tick refuses to record :ok
      ;; without this line reading > 0). It is the same floor --check has no
      ;; way to express: a scan that entered no directory finds no collisions.
      (println (str "SCANNED\t" (count (distinct (map :repo rs)))
                    "\tchecked-out repo(s)"))
      (doseq [{:keys [ns names]} reachable-divergent]
        (println (str "FINDING\tfail\tcollision:" ns
                      "\tshipped with different content by " (count names)
                      " co-classpath repo(s): " (str/join ", " names))))
      (doseq [{:keys [ns names]} (filter :ext-shadowed? unreachable-divergent)]
        (println (str "FINDING\twarn\text-shadow:" ns
                      "\tone repo's platform file covers another's .cljc"
                      " whatever the order; no closure holds both today: "
                      (str/join ", " names))))
      (doseq [{:keys [ns owners]} self-shadowing]
        (println (str "FINDING\twarn\tself-shadow:" ns
                      "\tsame path in " (count owners)
                      " source trees of one repo, different content: "
                      (str/join ", " (map :file owners))))))
    (when (and check? (seq reachable-divergent))
      (println)
      (println (str "verify-namespace-collisions: " (count reachable-divergent)
                    " namespace(s) are shipped with different content by two "
                    "repos that can share a classpath."))
      (set! (.-exitCode js/process) 1))))

(-main)

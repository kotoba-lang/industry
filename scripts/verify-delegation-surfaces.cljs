#!/usr/bin/env nbb
;; Every place that decides a `kotoba://` authority, and how it decides.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-delegation-surfaces.cljs
;;   ... --check      exit 1 if any repo decides authority without the lattice
;;   ... --findings   emit FINDING<TAB>sev<TAB>key<TAB>detail lines
;;   ... --self-test  prove the matchers discriminate AND the walk arrives
;;
;; Run from the superproject root.
;;
;; ## Why
;;
;; Root ADR-2608180200 makes biscuit the delegation centre and keeps ONE
;; decider: `kotoba-lang/authority`. Wires may multiply; the answer to *does
;; this cover that* may not. `authority.scope`'s own docstring records what a
;; second answer costs -- `covers?` had been written once per URI scheme, and
;; one copy stripped a trailing `*` and called `starts-with?`, so
;;
;;     kotoba://graph/alice*   covered   kotoba://graph/alice-evil
;;
;; That copy is fixed. Nothing checks for the next one, and "wire it
;; everywhere" is not a plan for 130 repos: measured 2026-08-19, 130 repos
;; touch a delegation verification surface. You cannot hand-wire that many
;; without producing unreachable code; you CAN make the fleet able to answer
;; where the decision is made.
;;
;; ## What it looks for
;;
;; A repo that BOTH mentions a `kotoba…://` authority string AND compares one
;; with `starts-with?` / defines its own covering relation, while NOT
;; depending on `authority`. Each half alone is fine: plenty of repos hold
;; such strings, and `starts-with?` has a thousand innocent uses. The pair,
;; without the lattice, is the shape that goes wrong.
;;
;; Known-correct implementations are named rather than pattern-matched, because
;; the lattice and the wire formats necessarily contain the comparison they
;; replace.
;;
;; ## The walk is `git grep --cached`, and that is the whole fix (2026-08-21)
;;
;; The first two versions of this scan walked `<repo>/src`. Measured on
;; 2026-08-21 that missed **616 of 4,210** on-disk west paths outright, 132 of
;; which hold Clojure source -- and running this file's own patterns over the
;; skipped set found **6 files in `net-kotobase/control-plane`**, the
;; production edge API gateway of kotobase.net, whose `edge_chain.cljc` is
;; literally titled *"Delegation chains at the edge"*. The one repo this
;; detector most needed to read was the one it structurally could not reach,
;; because control-plane is a multi-project repo with no top-level `src/`.
;;
;; A second bug hid inside the first. `depends-on-authority?` read
;; `<repo>/deps.edn`; control-plane has none (the gateway's own deps.edn DOES
;; declare `io.github.kotoba-lang/authority`). So a fixed walk alone would
;; have reported all 6 files as findings against a repo that uses the lattice.
;; **Blindness and misjudgement cancelled into a clean report.**
;;
;; `git grep --cached` makes four separate hazards unrepresentable at once
;; rather than four things to remember:
;;
;;   monorepo shape      -- the whole repo is in the index, wherever src lives
;;   sparse checkout     -- `--cached` reads the index, not the worktree, so a
;;                          cone-excluded file is searched, not silently absent
;;   node_modules        -- untracked, therefore not in the index
;;   .claude/worktrees   -- likewise untracked
;;
;; The scheme pattern is `kotoba[-a-z]*://` rather than the literal
;; `kotoba://`, because `kotoba-rad://` -- one of the eight schemes
;; `authority`'s README counts -- does not contain `kotoba://` as a
;; substring. Measured 2026-08-21: 3 files use it with zero `kotoba://`, and
;; none of them currently holds the comparison shape. Latent, not live -- and
;; none of those three repos depends on the lattice either.
;;
;; ## What the self-test now covers, and why that is the point
;;
;; The previous `--self-test` proved the MATCHERS discriminate, 5/5, including
;; the historical `cacao.core/covers?` body that the first version could not
;; see. It proved nothing about the WALK, and the walk is what was broken.
;; A self-test that guards the matcher and not the walk reports the same
;; "5/5 cases" whether or not the scan ever arrives at a file.
;;
;; So the walk cases below build a real git repository on disk and assert the
;; scan reaches source that is (a) under a sub-project rather than `src/`,
;; (b) tracked but absent from the worktree, and (c) named with a non-`kotoba://`
;; scheme -- and that the lattice check finds a nested deps.edn.

(require '[scripts.nbb-compat :as io :refer [slurp sh]]
         '[clojure.string :as str]
         '["node:fs" :as fs]
         '["node:os" :as os]
         '["node:path" :as path])

(def flags (set (drop 2 (js->clj (.-argv js/process)))))
(def self-test? (contains? flags "--self-test"))
(def check? (contains? flags "--check"))
(def findings? (contains? flags "--findings"))

(def owns-the-comparison
  "Repos where the comparison IS the deliverable. Named, not inferred: a
  pattern that tried to spot them would also spot the next defect."
  #{"authority" "org-chainagnostic-cacao" "xyz-ucan" "org-biscuitsec" "macaroon"
    "kotoba-lang"
    ;; `aiueos` was named here until 2026-08-21. It no longer holds a covering
    ;; relation -- `grant` does (root ADR-2608219500), and the exemption moved
    ;; with the code rather than staying with the repository name. Leaving it
    ;; on aiueos would have exempted a repository that no longer decides
    ;; anything, while the repository that does would have been measured
    ;; against a rule nobody had decided to apply to it.
    ;;
    ;; This is still an exemption, not a clean bill: `grant.authority`'s
    ;; `resource-match?` is a second answer to *does this grant cover this
    ;; intent*, over `[action resource]` pairs with a `:*` wildcard rather than
    ;; segment paths. Unifying it with `authority.scope` is a semantic change,
    ;; and it has its own gap entry in ADR-2608219500.
    "grant"})

(def scheme-ere
  "Rule 0: an authority string of ANY kotoba scheme.

  Not the literal `kotoba://`. `authority`'s README counts eight schemes and
  one of them is `kotoba-rad://<rid>/push/<ref>`, which does not contain
  `kotoba://` as a substring -- so a literal match cannot see it at all."
  "kotoba[-a-z]*://")

(def line-ere
  "Rule A: a prefix test applied to something NAMED like an authority."
  "starts-with\\?.*(resource|scope|capabilit|cap-|grant|kotoba[-a-z]*://)")

(def file-ere
  "Rule B: a file that defines its own covering relation.

  Rule A alone could not catch the defect this detector exists for. The
  historical `cacao.core/covers?` was

      (defn covers? [parent child]
        (str/starts-with? child (subs parent 0 (dec (count parent)))))

  and the `starts-with?` LINE names nothing -- `parent` and `child` are the
  whole vocabulary."
  "defn-?[[:space:]]+covers\\?|defn-?[[:space:]]+covered\\?")

(def compare-ere (str line-ere "|" file-ere))

;; The same two rules as JS regexes, for the matcher self-test only. The scan
;; itself hands the ERE strings to git, which is the thing that must agree
;; with production -- these exist so a case can be asserted without a repo.
(def line-matcher (re-pattern line-ere))
(def file-matcher (re-pattern "defn-?\\s+covers\\?|defn-?\\s+covered\\?"))

(def source-pathspec ["*.clj" "*.cljc" "*.cljs"])

(defn- test-path? [f]
  (or (re-find #"(^|/)tests?/" f)
      (re-find #"_test\.clj[cs]?$" f)))

;; ---------------------------------------------------------------- the walk

(defn- tracked-source [dir]
  "Tracked .clj/.cljc/.cljs in `dir`, from the INDEX -- so a sparse-excluded
  file counts as scanned rather than vanishing. -> {:files n} or {:error s}."
  (let [{:keys [exit out err]}
        (apply sh (concat ["git" "-C" dir "ls-files" "--"] source-pathspec))]
    (if (zero? exit)
      {:files (count (remove str/blank? (str/split-lines (str out))))}
      {:error (str "git ls-files exit " exit
                   (when-let [l (first (remove str/blank? (str/split-lines (str err))))]
                     (str ": " l)))})))

(defn- co-occurring-files [dir]
  "Files holding BOTH an authority string and a comparison. `--all-match`
  keeps the file-level conjunction the previous two-grep pipeline had."
  (let [{:keys [exit out err]}
        (apply sh (concat ["git" "-C" dir "grep" "-l" "--cached" "--all-match" "-E"
                           "-e" scheme-ere "-e" compare-ere "--"]
                          source-pathspec))]
    (case exit
      0 {:hits (vec (remove str/blank? (str/split-lines (str out))))}
      1 {:hits []}
      {:error (str "git grep exit " exit
                   (when-let [l (first (remove str/blank? (str/split-lines (str err))))]
                     (str ": " l)))})))

(defn- lattice? [dir]
  "Does ANY deps.edn in the repo depend on the lattice -- not just the root
  one. control-plane has no root deps.edn and its gateway sub-project does."
  (zero? (:exit (sh "git" "-C" dir "grep" "-q" "--cached"
                    "-e" "kotoba-lang/authority" "--" "*deps.edn"))))

;; ----------------------------------------------------------- the self-test

(def matcher-cases
  [{:rule :line :text "(when (str/starts-with? % \"kotoba://can/\") %)"
    :match? true  :why "a capability prefix test"}
   {:rule :line :text "(if (str/starts-with? iss \"did:key:\") \"1\" ...)"
    :match? false :why "a DID method check — measured false positive, gftd-audio-actor"}
   {:rule :line :text "(str/starts-with? path \"/xrpc/\")"
    :match? false :why "an ordinary route test"}
   {:rule :file :text "(defn covers? [parent child]\n  (str/starts-with? child (subs parent 0 1)))"
    :match? true  :why "THE defect: a private covering relation, named nothing"}
   {:rule :file :text "(defn covers-the-window? [a b] (str/starts-with? a b))"
    :match? false :why "a similarly-named function that is not a covering relation"}
   {:rule :line :text "(str/starts-with? scope \"kotoba-rad://r/push/\")"
    :match? true  :why "the kotoba-rad:// scheme, which a literal kotoba:// cannot see"}])

(defn- sh! [& args]
  (let [{:keys [exit err] :as r} (apply sh args)]
    (when-not (zero? exit)
      (println (str "  self-test setup failed: " (str/join " " args) " -> " exit " " err))
      (js/process.exit 2))
    r))

(defn- build-walk-fixture!
  "A real repository shaped like the one this detector could not read:
  source under a sub-project, one file tracked but deleted from the worktree,
  and deps.edn nested rather than at the root."
  []
  (let [root (path/join (os/tmpdir) (str "delegation-walk-" (.-pid js/process)))
        sub  (path/join root "gateway-cljs")
        src  (path/join sub "src" "kotobase")]
    (fs/rmSync root #js {:recursive true :force true})
    (fs/mkdirSync src #js {:recursive true})
    ;; (a) monorepo shape + (c) a non-`kotoba://` scheme
    (fs/writeFileSync (path/join src "edge.cljc")
                      (str "(ns kotobase.edge)\n"
                           "(def s \"kotoba-rad://rid/push/ref\")\n"
                           "(defn covers? [parent child] (str/starts-with? child parent))\n"))
    ;; (b) tracked, then removed from the worktree — the sparse-checkout shape
    (fs/writeFileSync (path/join src "sparse.cljc")
                      (str "(ns kotobase.sparse)\n"
                           "(def s \"kotoba://graph/g1\")\n"
                           "(defn covered? [a b] (str/starts-with? b a))\n"))
    (fs/writeFileSync (path/join sub "deps.edn") "{:deps {}}\n")
    (sh! "git" "-C" root "init" "-q")
    (sh! "git" "-C" root "add" "-A")
    {:root root :sub sub}))

(defn- run-self-test! []
  (let [matcher-bad
        (vec (for [{:keys [rule text match? why]} matcher-cases
                   :let [hit (boolean (re-find (if (= :file rule) file-matcher line-matcher) text))]
                   :when (not= hit match?)]
               (str "  matcher: expected " (if match? "MATCH" "no match")
                    " (" why "): " (first (str/split-lines text)))))
        {:keys [root sub]} (build-walk-fixture!)
        ;; The worktree copy goes away; the index entry stays. A walk that
        ;; reads the filesystem loses this file and says nothing.
        _ (fs/rmSync (path/join sub "src" "kotobase" "sparse.cljc") #js {:force true})
        scanned (:files (tracked-source root))
        hits (set (:hits (co-occurring-files root)))
        before (lattice? root)
        _ (fs/writeFileSync (path/join sub "deps.edn")
                            "{:deps {io.github.kotoba-lang/authority {:git/sha \"0\"}}}\n")
        _ (sh! "git" "-C" root "add" "-A")
        after (lattice? root)
        walk-cases
        [{:ok (= 2 scanned)          :why "counts both tracked sources (got " :got scanned}
         {:ok (contains? hits "gateway-cljs/src/kotobase/edge.cljc")
          :why "reaches source under a sub-project, not <repo>/src (got " :got (vec hits)}
         {:ok (contains? hits "gateway-cljs/src/kotobase/sparse.cljc")
          :why "reaches a tracked file absent from the worktree (got " :got (vec hits)}
         {:ok (false? before) :why "no nested deps.edn declares the lattice yet (got " :got before}
         {:ok (true? after)   :why "finds the lattice in a NESTED deps.edn (got " :got after}]
        walk-bad (vec (for [{:keys [ok why got]} walk-cases :when (not ok)]
                        (str "  walk: " why (pr-str got) ")")))
        bad (into matcher-bad walk-bad)]
    (fs/rmSync root #js {:recursive true :force true})
    (doseq [b bad] (println b))
    (println (str "self-test: "
                  (- (count matcher-cases) (count matcher-bad)) "/" (count matcher-cases)
                  " matcher cases, "
                  (- (count walk-cases) (count walk-bad)) "/" (count walk-cases)
                  " walk cases"))
    (js/process.exit (if (seq bad) 1 0))))

;; ----------------------------------------------------------------- the run

(defn- finding! [sev k detail]
  (when findings? (println (str "FINDING\t" sev "\t" k "\t" detail))))

(defn- repo-paths []
  (let [west (slurp "manifest/west.yml")]
    (->> (str/split-lines west)
         (keep #(second (re-find #"^\s+path:\s*(orgs/\S+)" %)))
         vec)))

(when self-test? (run-self-test!))

(let [paths (repo-paths)]
  (when (empty? paths)
    (println "west.yml has no path: entries — could not look")
    (js/process.exit 2))
  (let [on-disk (filterv #(fs/existsSync %) paths)
        results
        (vec (for [dir on-disk
                   :let [repo (last (str/split dir #"/"))]
                   :when (not (contains? owns-the-comparison repo))
                   :let [ts (tracked-source dir)]]
               (cond
                 (:error ts) {:dir dir :repo repo :skip :not-a-git-repo :detail (:error ts)}
                 (zero? (:files ts)) {:dir dir :repo repo :skip :no-tracked-source :files 0}
                 :else
                 (let [co (co-occurring-files dir)]
                   (if (:error co)
                     {:dir dir :repo repo :skip :grep-failed :detail (:error co) :files (:files ts)}
                     {:dir dir :repo repo :files (:files ts)
                      :hits (mapv #(str dir "/" %) (:hits co))})))))
        skipped (filterv :skip results)
        looked (remove :skip results)
        scanned-files (reduce + 0 (map :files looked))
        with-hits (filterv #(seq (:hits %)) looked)
        ;; The lattice question is only asked where there is something to
        ;; judge — that keeps the third git call off 4,000 quiet repos.
        judged (mapv #(assoc % :lattice? (lattice? (:dir %))) with-hits)
        bad (remove :lattice? judged)
        bad-files (mapcat :hits bad)
        prod (remove test-path? bad-files)
        tests (filter test-path? bad-files)]

    ;; Evidence floor: files, not repos. "3,588 repos with a src/ tree" passed
    ;; a `[1-9][0-9]*` floor while the walk was reading the wrong set — a floor
    ;; detects "measured nothing", never "measured the wrong thing". Naming the
    ;; skips is what makes skipped and clean different outputs.
    (println (str "SCANNED\t" scanned-files "\ttracked source file(s) in "
                  (count looked) " repo(s) of " (count on-disk) " on disk"))
    (doseq [[reason n] (sort-by key (frequencies (map :skip skipped)))]
      (println (str "SKIPPED\t" n "\t" (name reason))))
    (println (str "files holding an authority string AND a comparison: "
                  (reduce + 0 (map (comp count :hits) judged))
                  " in " (count judged) " repo(s)"))
    (println (str "of those repo(s), folding into the lattice: "
                  (- (count judged) (count bad))))

    (doseq [{:keys [repo hits]} bad
            f hits]
      (let [t? (boolean (test-path? f))]
        (finding! (if t? "info" "warn") (str "own-comparison:" f)
                  (str f " holds a kotoba…:// authority string and compares with "
                       "starts-with? or its own covers?, and no deps.edn in "
                       repo " depends on kotoba-lang/authority — the shape that "
                       "let kotoba://graph/alice* cover kotoba://graph/alice-evil"
                       (when t? " (test path: reported, not failed on)")))
        (println (str "  " (if t? "info" "warn") "  " f))))

    (when (zero? scanned-files)
      (println "no repo yielded a tracked source file — this run measured nothing")
      (js/process.exit 2))
    (when (seq tests)
      (println (str "note: " (count tests) " of the above are test paths and do not fail --check")))
    (js/process.exit (if (and check? (seq prod)) 1 0))))

;; repo-reality org-wide generic scan (2026-07-16) — applies :axis/repo-hygiene and
;; :axis/doc-internal-coherence uniformly across kotoba-lang org repos via the GitHub API, with
;; NO hand-curated :claim/* entity per repo (doesn't scale to ~1410 repos; see
;; repo-reality.datoms.edn's org-wide-axes comment). Emits ledger lines tagged :eval/repo
;; instead of :eval/claim.
;;
;; Usage:
;;   nbb 90-docs/repo-reality/orgwide_scan.cljs <repo1> <repo2> ...   (explicit list, for validation)
;;   nbb 90-docs/repo-reality/orgwide_scan.cljs --all                (fetch full org repo list, then scan)
;;   nbb 90-docs/repo-reality/orgwide_scan.cljs --all --limit 30     (fetch full list, scan first N -- for dry runs)
;;
;; Requires `gh` CLI authenticated (shells out via child_process, same as this session's manual
;; API calls) — no HTTP client dependency needed. Output: ledger event lines to stdout (redirect
;; into repo-reality-ledger.edn, same convention as verify_<project>.cljs).

(ns orgwide-scan
  (:require ["child_process" :as cp]
            [clojure.string :as str]))

(def org "kotoba-lang")

(defn sh
  "Run a shell command, return stdout string. Returns nil (not throw) on nonzero exit,
   since a single repo's API hiccup shouldn't kill a 1000+ repo scan."
  [cmd]
  (try
    (.toString (cp/execSync cmd #js {:maxBuffer (* 64 1024 1024)}))
    (catch :default _ nil)))

(defn gh-json [path]
  (when-let [out (sh (str "gh api \"" path "\" 2>/dev/null"))]
    (try (js/JSON.parse out) (catch :default _ nil))))

(defn repo-list []
  (when-let [out (sh (str "gh api \"orgs/" org "/repos\" --paginate --jq "
                          "'.[] | select(.archived==false) | .name' 2>/dev/null"))]
    (->> (str/split-lines out) (remove str/blank?) vec)))

(defn tree-paths
  "All file paths in repo's default branch, recursive. nil if repo/branch lookup fails."
  [repo]
  (when-let [info (gh-json (str "repos/" org "/" repo))]
    (let [branch (.-default_branch info)]
      (when-let [tree (gh-json (str "repos/" org "/" repo "/git/trees/" branch "?recursive=1"))]
        (when-let [entries (.-tree tree)]
          (->> entries
               (filter #(= "blob" (.-type %)))
               (map #(.-path %))
               vec))))))

(defn file-content [repo path]
  (sh (str "gh api \"repos/" org "/" repo "/contents/" path "\" "
           "-H \"Accept: application/vnd.github.raw\" 2>/dev/null")))

(defn has? [s re] (boolean (re-find re s)))

;; ---- :axis/repo-hygiene ---------------------------------------------------------------------

(defn readme-path [paths]
  (first (filter #(re-find #"(?i)^readme\.(md|org|adoc|txt)$" %) paths)))

(defn hygiene-score [repo paths]
  (let [readme (readme-path paths)
        readme-content (when readme (file-content repo readme))
        readme-substantial? (and readme-content (> (count (str/trim readme-content)) 200))
        ci? (some #(re-find #"^\.github/workflows/.+\.ya?ml$" %) paths)
        test? (some #(re-find #"^(test|spec)/.+\.(clj|cljc|cljs|js|py|rs)$" %) paths)
        signals [(if readme-substantial? 1.0 0.0) (if ci? 1.0 0.0) (if test? 1.0 0.0)]
        score (/ (reduce + signals) 3.0)]
    {:score score
     :note (str "readme=" (boolean readme) "/substantial=" (boolean readme-substantial?)
                " ci=" (boolean ci?) " test=" (boolean test?)
                " (" (count paths) " files in tree)")}))

;; ---- :axis/doc-internal-coherence -------------------------------------------------------------

(def maturity-doc-candidates
  ["docs/coverage.edn" "docs/lang/coverage.edn" "coverage.edn"
   "docs/maturity.md" "maturity.md"])

(defn extract-evidence-paths
  "Pull path-shaped substrings out of a coverage.edn's :evidence vectors -- a plain regex over
   the raw text (coverage.edn's own :stages value is often itself a STRING of nested/escaped EDN,
   see kotoba/kotobase/aiueos's coverage.edn, so quotes inside it are backslash-escaped `\\\"...\\\"`
   not plain `\"...\"` -- a regex anchored on plain quote delimiters silently matches nothing, as
   an earlier version of this fn did). Matching the path shape itself (contains '/', ends in a
   dotted extension) without anchoring on surrounding quote characters at all is what actually
   works against both plain and escaped-string EDN uniformly. Leading char class includes '.'
   (an earlier version excluded it, silently truncating '.github/...' to 'github/...' and
   '../sibling-repo/...' to 'sibling-repo/...' -- both real bugs found validating against
   kotoba/kotobase/aiueos's actual coverage.edn). Extension length widened to 2-8 (an earlier
   4-char cap truncated '.kotoba', a real extension in this ecosystem)."
  [content]
  (->> (re-seq #"[a-zA-Z0-9_.][a-zA-Z0-9_.\-/]*/[a-zA-Z0-9_.\-]+\.[a-zA-Z]{2,8}" content)
       distinct))

(defn cross-repo-path?
  "True if a cited evidence path is a reference into a DIFFERENT repo (a sibling west project,
   or a superproject-relative orgs/<org>/<repo>/... path -- both conventions used throughout
   this monorepo's docs, e.g. kototama's maturity.md citing wasm-webcomponent's
   test/verify-http-post.mjs, or kotobase's coverage.edn citing
   orgs/gftdcojp/cloud-manimani/deps.edn). Such paths will NEVER exist in the scanned repo's own
   git tree even when the citation is completely accurate -- must be excluded from the
   evidence-existence denominator, not counted as 'missing'. Validated against all 5 curated
   projects during this axis's build-out: without this filter, every one of them showed false
   'missing evidence' findings purely from legitimate cross-repo citations."
  [path]
  (boolean (or (str/starts-with? path "../")
               (str/starts-with? path "orgs/"))))

(defn coherence-score [repo paths]
  (if-let [doc-path (first (filter (set paths) maturity-doc-candidates))]
    (let [content (file-content repo doc-path)]
      (if-not content
        {:score nil :note (str doc-path " found in tree but content fetch failed -- skipped")}
        (let [all-evidence-paths (extract-evidence-paths content)
              cross-repo (filter cross-repo-path? all-evidence-paths)
              evidence-paths (remove cross-repo-path? all-evidence-paths)
              path-set (set paths)
              present (filter path-set evidence-paths)
              missing (remove path-set evidence-paths)
              evidence-score (if (empty? evidence-paths) 1.0 (/ (double (count present)) (count evidence-paths)))
              other-docs (filter #(and (not= % doc-path) (re-find #"(?i)versioning\.md$|readme\.md$" %)) paths)
              cross-doc-flag?
              (some (fn [p]
                      (when-let [c (file-content repo p)]
                        (and (re-find #"(?i)open.{0,20}(gap|issue)" c)
                             (has? content #"(?i)resolved"))))
                    other-docs)]
          {:score (if cross-doc-flag? (min 0.4 evidence-score) evidence-score)
           :note (str doc-path ": " (count present) "/" (count evidence-paths) " cited evidence paths exist (in-repo only)"
                      (when (seq cross-repo) (str "; " (count cross-repo) " cross-repo citation(s) excluded from scoring (e.g. " (first cross-repo) ")"))
                      (when (seq missing)
                        (str " (missing: " (str/join ", " (take 3 missing)) (when (> (count missing) 3) ", ...") ")"
                             (when (<= (count missing) 2)
                               " -- LOW CONFIDENCE on this small a miss count: likely candidates are either a genuine stale citation OR an unrecognized cross-repo/prose reference the ../ and orgs/ prefix filter doesn't catch (e.g. a path mentioned in a '# in <other-repo>' comment, or two filenames joined in prose like 'effects.rs/policy.rs' describing a boundary, not a literal path) -- re-verify by hand before treating this as a confirmed doc-code problem, do not just trust the count.")))
                      (when cross-doc-flag? " -- POSSIBLE cross-doc conflict: another doc says 'open gap/issue' while this doc says 'resolved' somewhere; needs manual review, this is a heuristic flag not a confirmed finding (same pattern as the hand-found kotobase M5 case, but NOT independently verified here)."))})))
    {:score nil :note "no maturity/coverage doc found at any candidate path -- not scored (absence != incoherence)"}))

;; ---- main -------------------------------------------------------------------------------------

(defn scan-repo [repo run-id now seq-atom]
  (if-let [paths (tree-paths repo)]
    (let [hyg (hygiene-score repo paths)
          coh (coherence-score repo paths)]
      (cond-> []
        true (conj {:eval/repo (str org "/" repo) :eval/axis :axis/repo-hygiene :eval/layer :lint
                    :eval/score (double (:score hyg)) :eval/judge "orgwide-scan-script"
                    :eval/run-id run-id :eval/at now :eval/seq (swap! seq-atom inc) :eval/note (:note hyg)})
        (some? (:score coh)) (conj {:eval/repo (str org "/" repo) :eval/axis :axis/doc-internal-coherence :eval/layer :lint
                                     :eval/score (double (:score coh)) :eval/judge "orgwide-scan-script"
                                     :eval/run-id run-id :eval/at now :eval/seq (swap! seq-atom inc) :eval/note (:note coh)})))
    [{:eval/repo (str org "/" repo) :eval/axis :axis/repo-hygiene :eval/layer :lint
      :eval/score 0.0 :eval/judge "orgwide-scan-script"
      :eval/run-id run-id :eval/at now :eval/seq (swap! seq-atom inc)
      :eval/note "repo/tree lookup failed (API error or repo gone) -- scored 0.0 as a fetch-failure signal, re-verify by hand before trusting this as a real hygiene score."}]))

(defn -main [args]
  (let [args (vec args)
        all? (some #{"--all"} args)
        limit (when-let [i (.indexOf args "--limit")]
                (when (>= i 0) (js/parseInt (nth args (inc i)))))
        explicit (remove #(or (= % "--all") (= % "--limit") (re-find #"^\d+$" %)) args)
        repos (cond
                all? (let [rs (repo-list)] (if limit (take limit rs) rs))
                (seq explicit) explicit
                :else (do (println "Usage: nbb orgwide_scan.cljs <repo1> <repo2> ... | --all [--limit N]")
                          (.exit js/process 1)))
        run-id (str "repo-reality-orgwide-" (str/replace (.toISOString (js/Date.)) #"[-:]|\.\d+Z$" ""))
        now (.toISOString (js/Date.))
        seq-atom (atom 0)]
    (binding [*print-namespace-maps* false]
      (doseq [[i repo] (map-indexed vector repos)]
        (.write js/process.stderr (str "[" (inc i) "/" (count repos) "] " repo "\n"))
        (doseq [line (scan-repo repo run-id now seq-atom)]
          (println (pr-str line)))))))

;; nbb binds *command-line-args* to the args AFTER the script path (unlike plain node's
;; process.argv, which also includes the node binary + script path and needs a manual drop).
(-main *command-line-args*)

#!/usr/bin/env nbb
;; flagship-checklist-scan.cljs — read-only diagnostic scanner for the cloud-itonami
;; flagship rollout (ADR-2607189300, Wave 5).
;;
;; WHY: iterations 1-4 of the Wave5 rollout each spent a full agent iteration
;; (~15-30min) re-deriving a single repo's state via ad hoc grep/inspection before
;; doing anything. At ~1,112 non-flagship cloud-itonami-* repos, that does not scale.
;; This script is a cheap, mechanical, filesystem-first pass any future iteration can
;; run in a couple of minutes to get a starting signal instead of re-deriving from
;; scratch — it does NOT replace independent verification before marking a checklist
;; item "done" (ADR-2607189300's guardrail still applies: don't trust a green without
;; running the real thing).
;;
;; READ-ONLY CONTRACT: this script only reads files (fs/readFileSync, fs/readdirSync,
;; fs/existsSync) and shells out to `git log`/`git rev-parse` (read commands only — no
;; git command that mutates a working tree or ref is ever invoked). It never writes to
;; any path under orgs/. The only write this script performs is its own --out report.
;;
;; What it checks per orgs/cloud-itonami/cloud-itonami-* repo (see ADR-2607189300 body
;; for the full 7-item flagship checklist; this scanner covers items 2, 4, 6 plus
;; cohort-table membership — items 1/3/5/7 need semantic judgement this script does not
;; attempt):
;;   item 4 (nightly regen workflow)   — HARD FACT: .github/workflows/regenerate.yml exists.
;;                                        Heuristic supplement: any other workflow file
;;                                        containing a `schedule:` cron trigger.
;;   item 2 (build-time-generated demo) — HEURISTIC: looks for a plausible generator
;;                                        script (web/generate.cljs, or any
;;                                        generate*/render*html* file under .clj/.cljc/
;;                                        .cljs found via a bounded recursive walk) AND
;;                                        a demo HTML file (docs/index.html, docs/samples/
;;                                        *.html, or top-level index.html). Classifies as
;;                                        "likely-generated" / "likely-hand-authored" /
;;                                        "unknown-no-demo" per ADR direction — see
;;                                        classify-item2 below for the exact rule. The
;;                                        commit-count-on-demo-file check follows
;;                                        iteration 3's method (ledger seq 3).
;;   item 6 (registry.edn :demo entry) — HARD FACT: parses
;;                                        orgs/kotoba-lang/industry/resources/kotoba/
;;                                        industry/registry.edn (:business-id match) and
;;                                        records whether that entry has a :demo field.
;;   cohort table membership           — HEURISTIC: substring search of the repo's
;;                                        ISIC/ISCO-like numeric code inside
;;                                        90-docs/business/cloud-itonami-vertical-
;;                                        maturity.edn's doc body (that file is prose in
;;                                        an EDN wrapper, not structured per-repo data,
;;                                        so this is necessarily approximate).
;;   checkout-lag                      — HARD FACT (given a --west-yml source): compares
;;                                        the locally checked-out git HEAD of each repo
;;                                        against the revision pinned for it in west.yml.
;;                                        IMPORTANT, discovered while building this
;;                                        scanner: local orgs/cloud-itonami checkouts can
;;                                        lag far behind GitHub, because Wave5 iterations
;;                                        1/2/4 merged their fixes to the child repos via
;;                                        `gh api .../merges` but never advanced the
;;                                        corresponding west.yml pin, and `west update`
;;                                        only ever moves a checkout TO whatever the pin
;;                                        already says (it does not itself discover new
;;                                        upstream commits). A repo can be fully fixed on
;;                                        GitHub and this scanner will still (correctly,
;;                                        for what is actually on disk) report the gap as
;;                                        open unless :checkout/lag? is also checked.
;;                                        Wave5 iteration 5 fixed the pins for isic-6310/
;;                                        7810/2910 (commit f2c635cc, single-entry GitHub
;;                                        API PUT) but could NOT force-sync the shared
;;                                        orgs/ checkouts themselves (blocked by the
;;                                        session's own permission guardrail against
;;                                        mutating shared checkouts, and `west update`
;;                                        needs the SUPERPROJECT'S OWN main checkout to
;;                                        first `git pull` the new pin, which this
;;                                        iteration declined to do because that checkout
;;                                        was found to be 44 commits ahead of origin/main
;;                                        in a way this iteration did not cause and should
;;                                        not blindly reconcile). So :checkout/lag? true
;;                                        is an EXPECTED, understood, currently-open state
;;                                        for those 3 repos specifically — not a scanner
;;                                        bug. See the flagship-rollout-ledger seq 5 entry
;;                                        for the full account.
;;
;; Usage:
;;   nbb 90-docs/business/scripts/flagship-checklist-scan.cljs \
;;     [--data-root <path>]   ; where orgs/, 90-docs/business/, manifest/ live to READ
;;                            ; from (default: cwd). Point this at the superproject's
;;                            ; main checkout, since that's normally where orgs/
;;                            ; cloud-itonami is actually populated (west-managed,
;;                            ; gitignored — NOT checked out inside a git worktree by
;;                            ; default).
;;     [--west-yml <path>]    ; manifest/west.yml to read pins from (default:
;;                            ; <data-root>/manifest/west.yml). Override this to a
;;                            ; freshly-pulled copy if the data-root's own manifest/
;;                            ; west.yml might itself be stale, as this iteration had to.
;;     [--out <path>]         ; where to WRITE the report edn (default:
;;                            ; 90-docs/business/cloud-itonami-flagship-checklist-scan.edn
;;                            ; relative to cwd — i.e. wherever you're committing from,
;;                            ; NOT necessarily data-root).
;;     [--only a,b,c]         ; comma-separated repo dir names (without the
;;                            ; cloud-itonami- prefix is fine too) to scan instead of the
;;                            ; whole fleet — fast, for spot-checking/validation.
;;     [--limit N]            ; scan only the first N repos found (after --only
;;                            ; filtering, if given) — for quick smoke-testing.
;;
;; This file is READ-ONLY tooling; its own output (--out) is a GENERATED file — do not
;; hand-edit the report edn, rerun this script instead. Same convention as
;; 90-docs/business/maturity-scores.edn / design-quality.datoms.edn.

(require '[clojure.string :as str]
         '[clojure.edn :as edn]
         '["fs" :as fs]
         '["path" :as node-path]
         '["child_process" :as cp])

;; ---------------------------------------------------------------- arg parsing

(defn- argv [] (into [] (.slice js/process.argv 2)))

(defn- arg-val [flag default]
  (let [args (argv)
        i (.indexOf args flag)]
    (if (and (>= i 0) (< (inc i) (count args)))
      (nth args (inc i))
      default)))

(def data-root (node-path/resolve (arg-val "--data-root" ".")))
(def west-yml-path (node-path/resolve (arg-val "--west-yml" (node-path/join data-root "manifest" "west.yml"))))
(def out-path (arg-val "--out" "90-docs/business/cloud-itonami-flagship-checklist-scan.edn"))
(def only-filter (when-let [v (arg-val "--only" nil)]
                    (set (map str/trim (str/split v #",")))))
(def limit (when-let [v (arg-val "--limit" nil)] (js/parseInt v 10)))

(println "flagship-checklist-scan: data-root=" data-root)
(println "flagship-checklist-scan: west-yml=" west-yml-path)
(println "flagship-checklist-scan: out=" out-path)
(when only-filter (println "flagship-checklist-scan: --only" (pr-str only-filter)))
(when limit (println "flagship-checklist-scan: --limit" limit))

;; -------------------------------------------------------------- fs helpers

(defn- exists? [& parts] (fs/existsSync (apply node-path/join parts)))

(defn- read-file [& parts]
  (let [p (apply node-path/join parts)]
    (when (fs/existsSync p)
      (try (str (fs/readFileSync p "utf8"))
           (catch :default _ nil)))))

(defn- list-dirs [p]
  (if (fs/existsSync p)
    (try
      (->> (fs/readdirSync p #js {:withFileTypes true})
           (filter #(.isDirectory %))
           (map #(.-name %)))
      (catch :default _ []))
    []))

(defn- list-entries [p]
  (if (fs/existsSync p)
    (try
      (->> (fs/readdirSync p #js {:withFileTypes true})
           (map (fn [e] {:name (.-name e) :dir? (.isDirectory e) :file? (.isFile e)})))
      (catch :default _ []))
    []))

;; skip heavy/vendor/build dirs when walking a repo tree looking for generator scripts
(def skip-dir-names
  #{".git" "node_modules" "target" "out" ".cpcache" ".shadow-cljs" ".clj-kondo"
    ".west" "dist" "build" ".vscode" ".idea"})

(defn- walk-files
  "Bounded recursive file listing under root (repo dir). Returns a seq of relative
   paths. depth-limit and file-visit-cap are safety valves so one huge repo can't stall
   the whole fleet scan."
  [root & {:keys [depth-limit file-visit-cap] :or {depth-limit 6 file-visit-cap 4000}}]
  (let [acc (atom [])
        visited (atom 0)]
    (letfn [(walk [dir rel depth]
              (when (and (<= depth depth-limit) (< @visited file-visit-cap))
                (doseq [{:keys [name dir? file?]} (list-entries dir)
                        :while (< @visited file-visit-cap)]
                  (swap! visited inc)
                  (let [child-rel (if (str/blank? rel) name (str rel "/" name))]
                    (cond
                      (and dir? (not (skip-dir-names name)))
                      (walk (node-path/join dir name) child-rel (inc depth))

                      file?
                      (swap! acc conj child-rel))))))]
      (walk root "" 0)
      @acc)))

;; ------------------------------------------------------------- git helpers (read-only)

(defn- git-out [repo-abs & args]
  (try
    (str (.execSync cp (str "git " (str/join " " args))
                     #js {:cwd repo-abs :stdio #js ["ignore" "pipe" "ignore"]}))
    (catch :default _ nil)))

(defn- git-head [repo-abs]
  (some-> (git-out repo-abs "rev-parse" "HEAD") str/trim not-empty))

(defn- git-shallow? [repo-abs]
  (= "true" (some-> (git-out repo-abs "rev-parse" "--is-shallow-repository") str/trim)))

(defn- git-follow-commit-count [repo-abs rel-path]
  (when-let [out (git-out repo-abs "log" "--follow" "--oneline" "--" (str "\"" rel-path "\""))]
    (count (remove str/blank? (str/split-lines out)))))

;; ------------------------------------------------------------- west.yml (YAML, line-oriented)

(defn parse-west-yml-pins
  "west.yml is real YAML (west/Zephyr manifest format), not EDN — we don't pull in a
   YAML parser for one field. Each project renders as a small fixed block (see
   scripts/gen-west-manifest.cljs project-entry); we split on `    - name:` boundaries
   and regex out `path:` / `revision:` per block. Returns {path -> revision}."
  [content]
  (if-not content
    {}
    (let [blocks (str/split content #"(?=\n    - name: )")]
      (into {}
            (keep (fn [b]
                    (let [path (second (re-find #"(?m)^\s*path:\s*(\S+)\s*$" b))
                          rev  (second (re-find #"(?m)^\s*revision:\s*(\S+)\s*$" b))]
                      (when (and path rev) [path rev])))
                  blocks)))))

(def west-pins
  (parse-west-yml-pins (read-file west-yml-path)))

;; ------------------------------------------------------------- registry.edn (item 6)

(def registry-path
  (node-path/join data-root "orgs" "kotoba-lang" "industry" "resources" "kotoba" "industry" "registry.edn"))

(defn- repo-url-basename [e]
  (when-let [repo (:repo e)] (last (str/split repo #"/"))))

(def registry-industries
  (let [content (read-file registry-path)]
    (if-not content
      (do (println "WARN: registry.edn not found at" registry-path "-- item6 will be reported as \"unknown\" for all repos")
          [])
      (try
        (:industries (edn/read-string content))
        (catch :default e
          (println "WARN: failed to parse registry.edn:" (.-message e))
          [])))))

;; CORRELATION BUG FOUND WHILE VALIDATING THIS SCANNER (see ledger seq 5): registry.edn's
;; :business-id field disagrees with its own :repo URL basename for 53/648 entries as of
;; this writing (e.g. isic-7810's entry has :business-id "cloud-itonami-7810", missing
;; the "isic-" infix, while :repo correctly says ".../cloud-itonami-isic-7810") -- an
;; upstream data-quality issue in kotoba-lang/industry, not something this scanner can
;; fix. Looking up ONLY by :business-id silently misses real :demo entries (isic-7810
;; has a :demo field that a business-id-only lookup would report as absent). So we
;; correlate by BOTH keys and prefer whichever actually matches.
(def registry-by-business-id
  (into {} (keep (fn [e] (when-let [bid (:business-id e)] [bid e])) registry-industries)))

(def registry-by-repo-basename
  (into {} (keep (fn [e] (when-let [rb (repo-url-basename e)] [rb e])) registry-industries)))

(def registry-business-id-repo-mismatches
  (count (keep (fn [e]
                 (let [bid (:business-id e) rb (repo-url-basename e)]
                   (when (and bid rb (not= bid rb)) true)))
               registry-industries)))

(println "registry.edn: loaded" (count registry-industries) "industry entries ("
         (count registry-by-business-id) "with :business-id," (count registry-by-repo-basename) "with :repo )")
(println "registry.edn: DATA QUALITY --" registry-business-id-repo-mismatches
         "entries have :business-id != :repo basename (upstream bug, worked around via dual-key lookup)")

;; -------------------------------------------------------- vertical-maturity.edn (cohort)

(def cohort-body
  (let [p (node-path/join data-root "90-docs" "business" "cloud-itonami-vertical-maturity.edn")
        content (read-file p)]
    (if-not content
      (do (println "WARN: cloud-itonami-vertical-maturity.edn not found at" p) "")
      (try
        (let [data (edn/read-string content)]
          (or (:doc/body (first data)) ""))
        (catch :default e
          (println "WARN: failed to parse cloud-itonami-vertical-maturity.edn:" (.-message e))
          "")))))

(defn cohort-code-for [repo-name]
  ;; "cloud-itonami-isic-6399" -> "6399", "cloud-itonami-assoc-0126-idn-gapki" -> "0126"
  (second (re-find #"(\d{3,4})" repo-name)))

(defn cohort-mentioned? [repo-name]
  ;; digit-boundary match, not plain substring: cloud-itonami-vertical-maturity.edn's
  ;; doc body is free-form markdown prose (dates, percentages, revenue figures), so a
  ;; naive str/includes? on a bare 3-4 digit code false-positives heavily (e.g. a repo
  ;; whose code is "2600" was matching purely because some unrelated larger number in
  ;; the prose happened to contain "2600" as a substring). Still imperfect: a code that
  ;; happens to equal a YYYY date fragment (e.g. "2026", extremely common in this doc's
  ;; timestamps) will still false-positive under boundary matching alone -- this remains
  ;; a HEURISTIC, not a hard fact; see this script's header comment.
  (when-let [code (cohort-code-for repo-name)]
    (boolean (re-find (js/RegExp. (str "(?<![0-9])" code "(?![0-9])")) cohort-body))))

;; ------------------------------------------------------------------- item 4

(defn scan-item4 [repo-abs]
  (let [wf-dir (node-path/join repo-abs ".github" "workflows")
        wf-files (if (fs/existsSync wf-dir)
                   (->> (list-entries wf-dir) (filter :file?) (map :name) sort vec)
                   [])
        regenerate? (exists? wf-dir "regenerate.yml")
        cron-files (->> wf-files
                        (filter (fn [f] (or (str/ends-with? f ".yml") (str/ends-with? f ".yaml"))))
                        (filter (fn [f]
                                  (when-let [c (read-file wf-dir f)]
                                    (str/includes? c "schedule:"))))
                        vec)]
    {:item4/regenerate-yml-exists regenerate?
     :item4/workflow-files wf-files
     :item4/cron-workflow-files cron-files
     :item4/has-any-cron-workflow (boolean (seq cron-files))}))

;; ------------------------------------------------------------------- item 2

(def generator-name-re
  #"(?i)(generate|render[_-]?html)")

(def generator-ext-re
  #"(?i)\.(clj|cljc|cljs)$")

(defn find-generator-candidates [repo-abs]
  (let [files (walk-files repo-abs)]
    (->> files
         (filter (fn [f]
                   (let [base (node-path/basename f)]
                     (and (re-find generator-ext-re base)
                          (re-find generator-name-re base)))))
         sort vec)))

(defn deps-edn-render-hint [repo-abs]
  (when-let [c (read-file repo-abs "deps.edn")]
    (boolean (re-find #"(?i)render[_-]?html" c))))

(defn find-demo-file [repo-abs]
  (cond
    (exists? repo-abs "docs" "index.html")
    {:path "docs/index.html" :kind :primary}

    :else
    (let [samples-dir (node-path/join repo-abs "docs" "samples")
          samples (when (fs/existsSync samples-dir)
                     (->> (list-entries samples-dir)
                          (filter :file?)
                          (map :name)
                          (filter #(str/ends-with? % ".html"))
                          sort))]
      (if (seq samples)
        {:path (str "docs/samples/" (first samples)) :kind :samples :all-samples (vec samples)}
        (if (exists? repo-abs "index.html")
          {:path "index.html" :kind :top-level}
          nil)))))

(defn classify-item2 [{:keys [generator-found? demo commit-count]}]
  (cond
    (nil? demo) "unknown-no-demo"
    generator-found? "likely-generated"
    :else "likely-hand-authored")) ; covers both near-zero-history and the rarer
                                    ; >1-commit-but-no-generator-found case; raw
                                    ; :item2/demo-commit-count preserves that nuance.

(defn scan-item2 [repo-abs shallow?]
  (let [generators (find-generator-candidates repo-abs)
        deps-hint (deps-edn-render-hint repo-abs)
        demo (find-demo-file repo-abs)
        commit-count (when demo (git-follow-commit-count repo-abs (:path demo)))
        generator-found? (boolean (seq generators))
        classification (classify-item2 {:generator-found? generator-found?
                                         :demo demo
                                         :commit-count commit-count})]
    (cond-> {:item2/generator-candidates generators
             :item2/deps-edn-render-hint (boolean deps-hint)
             :item2/demo-path (:path demo)
             :item2/demo-kind (some-> demo :kind name)
             :item2/demo-commit-count commit-count
             :item2/classification classification}
      shallow? (assoc :item2/history-caveat "local clone is shallow -- commit-count is a lower bound, not exact full history"))))

;; ------------------------------------------------------------------- item 6 + checkout-lag

(defn scan-item6 [repo-name]
  (let [by-bid (get registry-by-business-id repo-name)
        by-repo (get registry-by-repo-basename repo-name)
        entry (or by-bid by-repo)
        matched-via (cond by-bid "business-id" by-repo "repo-url" :else nil)]
    (if entry
      {:item6/registry-entry-found true
       :item6/matched-via matched-via
       :item6/demo-field (:demo entry)
       :item6/has-demo-field (boolean (:demo entry))}
      {:item6/registry-entry-found false
       :item6/matched-via nil
       :item6/demo-field nil
       :item6/has-demo-field false})))

(defn scan-checkout-lag [repo-name repo-abs local-head]
  (let [west-path (str "orgs/cloud-itonami/" repo-name)
        pinned (get west-pins west-path)]
    (cond
      (nil? pinned)
      {:checkout/west-yml-entry-found false :checkout/lag? "unknown"}

      (nil? local-head)
      {:checkout/west-yml-entry-found true :checkout/pinned-revision pinned
       :checkout/local-head nil :checkout/lag? "unknown"}

      :else
      {:checkout/west-yml-entry-found true
       :checkout/pinned-revision pinned
       :checkout/local-head local-head
       :checkout/lag? (not= pinned local-head)})))

;; ------------------------------------------------------------------------- main scan

(def cloud-itonami-dir (node-path/join data-root "orgs" "cloud-itonami"))

(def all-repo-dirs
  (->> (list-dirs cloud-itonami-dir)
       (filter #(str/starts-with? % "cloud-itonami-"))
       sort vec))

(def repos-with-git
  (->> all-repo-dirs
       (filter #(exists? cloud-itonami-dir % ".git"))
       vec))

(println "found" (count all-repo-dirs) "cloud-itonami-* dirs under" cloud-itonami-dir
         "--" (count repos-with-git) "have a .git (real checkouts)")

(def target-repos
  (cond->> repos-with-git
    only-filter (filter (fn [r] (or (only-filter r)
                                    (only-filter (str/replace r #"^cloud-itonami-" "")))))
    true vec
    limit (take limit)
    true vec))

(println "scanning" (count target-repos) "repo(s)...")

(def start-ms (js/Date.now))

(def per-repo-results
  (vec
   (map-indexed
    (fn [i repo-name]
      (when (zero? (mod i 100)) (println "  ..." i "/" (count target-repos)))
      (let [repo-abs (node-path/join cloud-itonami-dir repo-name)
            local-head (git-head repo-abs)
            shallow? (git-shallow? repo-abs)
            item4 (scan-item4 repo-abs)
            item2 (scan-item2 repo-abs shallow?)
            item6 (scan-item6 repo-name)
            lag (scan-checkout-lag repo-name repo-abs local-head)]
        (merge {:repo/name repo-name
                :repo/local-head local-head
                :repo/shallow? shallow?
                :repo/cohort-code (cohort-code-for repo-name)
                :repo/cohort-table-mentioned? (boolean (cohort-mentioned? repo-name))}
               item4 item2 item6 lag)))
    target-repos)))

(def elapsed-ms (- (js/Date.now) start-ms))
(println "scan done in" elapsed-ms "ms")

;; ------------------------------------------------------------------------- aggregate

(defn count-where [pred coll] (count (filter pred coll)))

(def aggregate
  {:aggregate/n (count per-repo-results)
   :aggregate/item4-has-regenerate-yml (count-where :item4/regenerate-yml-exists per-repo-results)
   :aggregate/item4-has-any-cron-workflow (count-where :item4/has-any-cron-workflow per-repo-results)
   :aggregate/item2-likely-generated (count-where #(= "likely-generated" (:item2/classification %)) per-repo-results)
   :aggregate/item2-likely-hand-authored (count-where #(= "likely-hand-authored" (:item2/classification %)) per-repo-results)
   :aggregate/item2-unknown-no-demo (count-where #(= "unknown-no-demo" (:item2/classification %)) per-repo-results)
   :aggregate/item6-has-demo-field (count-where :item6/has-demo-field per-repo-results)
   :aggregate/item6-registry-entry-found (count-where :item6/registry-entry-found per-repo-results)
   :aggregate/item6-matched-only-via-repo-url-fallback
   (count-where #(= "repo-url" (:item6/matched-via %)) per-repo-results)
   :aggregate/registry-business-id-repo-mismatches registry-business-id-repo-mismatches
   :aggregate/cohort-table-mentioned (count-where :repo/cohort-table-mentioned? per-repo-results)
   :aggregate/checkout-lag-true (count-where #(true? (:checkout/lag? %)) per-repo-results)
   :aggregate/checkout-lag-unknown (count-where #(= "unknown" (:checkout/lag? %)) per-repo-results)
   ;; the interesting "cheap win" bucket future iterations should look at first:
   ;; has a generator (item2 = likely-generated) but item4 workflow is missing.
   :aggregate/has-generator-missing-regenerate-workflow
   (count-where #(and (= "likely-generated" (:item2/classification %))
                      (not (:item4/regenerate-yml-exists %)))
                per-repo-results)})

(println "aggregate:" (pr-str aggregate))

;; ------------------------------------------------------------------------- write report

(def header
  (str
   ";; cloud-itonami-flagship-checklist-scan.edn — GENERATED, DO NOT HAND-EDIT.\n"
   ";; Regenerate with:\n"
   ";;   nbb 90-docs/business/scripts/flagship-checklist-scan.cljs \\\n"
   ";;     --data-root <superproject main checkout, where orgs/cloud-itonami is actually\n"
   ";;                  populated -- west-managed, gitignored, NOT present in a fresh\n"
   ";;                  git worktree> --west-yml <freshest manifest/west.yml you have>\n"
   ";;\n"
   ";; Produced by ADR-2607189300 (cloud-itonami flagship Wave5 rollout), iteration 5.\n"
   ";; See 90-docs/business/scripts/flagship-checklist-scan.cljs's own header comment for\n"
   ";; exactly which fields are HARD FACTS (filesystem/registry truth) vs HEURISTICS\n"
   ";; (best-effort inference this script cannot fully verify -- generator-script\n"
   ";; detection, demo-file hand-authored-vs-generated classification, cohort-table\n"
   ";; substring matching). Validated against 5 known-ground-truth repos before being\n"
   ";; trusted for anything downstream -- see flagship-rollout-ledger.edn seq 5 for the\n"
   ";; validation record, including the known :checkout/lag? true caveat for isic-6310/\n"
   ";; 7810/2910 (local orgs/ checkout genuinely lags their now-fixed GitHub state; see\n"
   ";; this script's own header comment).\n"
   ";;\n"
   ";; Structure: a vector. First element is scan metadata + aggregate counts. Remaining\n"
   ";; elements are one map per scanned repo.\n\n"))

(def metadata
  {:scan/at (.toISOString (js/Date.))
   :scan/data-root data-root
   :scan/west-yml-path west-yml-path
   :scan/cloud-itonami-dirs-found (count all-repo-dirs)
   :scan/cloud-itonami-dirs-with-git (count repos-with-git)
   :scan/repos-scanned (count per-repo-results)
   :scan/only-filter (when only-filter (vec only-filter))
   :scan/limit limit
   :scan/elapsed-ms elapsed-ms
   :scan/aggregate aggregate})

(def report (into [metadata] per-repo-results))

(def out-content (str header (with-out-str (pr report)) "\n"))

(fs/mkdirSync (node-path/dirname (node-path/resolve out-path)) #js {:recursive true})
(fs/writeFileSync (node-path/resolve out-path) out-content)

(println "wrote" (node-path/resolve out-path) "(" (count out-content) "bytes )")

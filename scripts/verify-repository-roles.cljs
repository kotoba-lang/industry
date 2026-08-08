#!/usr/bin/env nbb
;; Validate kotoba.repository-role-rules.v0 per-repo declarations against the
;; workspace authority (manifest/repository-rules.edn) and against real
;; production git dependencies.  Test-only dependencies are intentionally
;; excluded: the rule describes the shipped dependency closure.
;;
;; Two vocabularies share the v0 declaration schema and are discriminated by
;; the shape of :this-repo (ADR-2607299000):
;;
;;   :name + :tier  -> :stack-tier   (kotoba stack t0-t6, ADR-2607266000)
;;   :prefix        -> :name-prefix  (loop-/skill-/action- naming convention)
;;
;; Before this split the stack-tier checks were applied to every declaration,
;; so a name-prefix declaration produced the nonsense error
;; `declared repository name differs from checkout {:repo "kotoba-lang/"}`.
;;
;; A third vocabulary (:origin) and a fourth (:product-family) were added
;; 2026-08-04 (ADR-2608040100). They are not per-repo declarations — they
;; classify a NAME, so they are checked by --name-audit over the whole west
;; manifest rather than by reading resources/repository-rules.edn.
;;
;; Usage:
;;   nbb scripts/verify-repository-roles.cljs <repo-dir>...
;;   nbb scripts/verify-repository-roles.cljs --prefix-audit [--west <west.yml>] [--workspace <dir>]
;;   nbb scripts/verify-repository-roles.cljs --name-audit [--west <west.yml>] [--org <org>]

(require '[clojure.edn :as edn]
         '[clojure.set :as set]
         '[clojure.string :as str]
         '[scripts.nbb-compat :as compat]
         '[scripts.org-id-derivation :as org-id])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))

(def declaration-schema "kotoba.repository-role-rules.v0")
(def workspace-schema "kotoba.repository-role-rules.workspace.v1")

(defn fail! [message data]
  (binding [*print-namespace-maps* false]
    (println (str "ERROR " message " " (pr-str data))))
  (compat/exit 1))

(defn read-edn [file]
  (edn/read-string (compat/slurp file)))

;; ---------------------------------------------------------------- authority

(defn- authority-file []
  ;; Walk up from cwd so the verifier works from the superproject root, from a
  ;; worktree, or from a subdirectory. CI runs it with cwd = superproject root.
  (let [candidates (loop [dir (.cwd js/process) acc []]
                     (let [f (.join path dir "manifest" "repository-rules.edn")
                           parent (.dirname path dir)]
                       (if (= parent dir)
                         (conj acc f)
                         (recur parent (conj acc f)))))]
    (or (first (filter #(.existsSync fs %) candidates))
        (fail! "workspace authority not found" {:tried (vec (take 3 candidates))}))))

(def authority
  (let [file (authority-file)
        doc (read-edn file)]
    (when-not (= workspace-schema (:schema doc))
      (fail! "unsupported workspace authority schema" {:file file :schema (:schema doc)}))
    (assoc doc :file file)))

(def vocabularies
  (into {} (map (juxt :vocabulary/id identity)) (:vocabularies authority)))

(def allowed-role-edges
  (or (get-in vocabularies [:stack-tier :vocabulary/allowed-role-edges])
      (fail! "stack-tier vocabulary declares no :vocabulary/allowed-role-edges" {})))

(def prefix-rules
  (or (get-in vocabularies [:name-prefix :vocabulary/rules])
      (fail! "name-prefix vocabulary declares no :vocabulary/rules" {})))

(def prefix-rule-by-prefix
  (into {} (keep (fn [r] (when (:prefix r) [(:prefix r) r]))) prefix-rules))

(def forbidden-suffixes
  (get-in authority [:naming :forbidden-suffixes] []))

;; ------------------------------------------------------------- origin plane

(def origin-vocab (get vocabularies :origin))
(def family-vocab (get vocabularies :product-family))

(def origin-tlds
  ;; Deliberately the OBSERVED set, not a copy of the IANA root zone. A repo
  ;; under a TLD nobody has used here yet is reported as an undeclared prefix,
  ;; which surfaces it for a decision instead of silently absorbing it.
  (set (keys (:vocabulary/observed-tlds origin-vocab {}))))

(def family-prefixes
  (into #{} (map :family) (:vocabulary/rules family-vocab [])))

;; The derivation itself moved to `scripts/org_id_derivation.cljs` so that this
;; file and the org-id intake stop being two implementations of one rule
;; (ADR-2608094000). The canonical form is `manifest/org-id.kotoba`; the fleet
;; gate `root-org-id-derivation` holds the two in agreement.
(def domain->prefix org-id/domain->prefix)

(defn name-under-prefix? [nm prefix]
  (or (= nm prefix) (str/starts-with? nm (str prefix "-"))))

(defn- origin-data-file []
  (.join path (.dirname path (:file authority)) "origin-domains.edn"))

(def origin-data
  (let [f (origin-data-file)]
    (when (.existsSync fs f)
      (let [doc (read-edn f)]
        (when-not (= "kotoba.origin-domains.v1" (:schema doc))
          (fail! "unsupported origin-domains schema" {:file f :schema (:schema doc)}))
        (assoc doc :file f)))))

(def origin-domain-by-repo
  (merge (:anchors origin-data {}) (:domains origin-data {})))

(def origin-anchors (set (keys (:anchors origin-data {}))))

(def origin-unresolvable
  (into {} (map (juxt :repo identity)) (:unresolvable origin-data [])))

(def org-domain-rules
  (get-in vocabularies [:org-domain :vocabulary/rules] []))

(def org-reversed
  (into {} (map (juxt :org :reversed)) org-domain-rules))

(def existing-family-form-wins?
  (boolean (get-in vocabularies [:org-domain :vocabulary/existing-family-form-wins])))

(defn org-segment-repeated?
  "The org segment already carries the org's own domain (ADR-2608040170), so a
   name that restates it says the same thing twice. Detects both shapes: the org
   name verbatim (cloud-itonami/cloud-itonami-isic-6492) and its reversed form
   (kotoba-lang/org-kotoba-lang-kami-engine)."
  [org nm]
  (let [rev (get org-reversed org)]
    (boolean (or (str/starts-with? nm (str org "-"))
                 (and rev (str/starts-with? nm (str rev "-")))))))


;; ------------------------------------------------------------ declarations

(defn normalize-repo [value]
  (let [repo (if (map? value) (:repo value) value)]
    (cond
      (not (string? repo)) nil
      (str/includes? repo "/") repo
      :else (str "kotoba-lang/" repo))))

(defn git-url->repo [url]
  (some->> url
           (re-find #"github\.com[/:]([^/]+)/([^/.]+)(?:\.git)?$")
           rest
           ((fn [[org repo]] (when (and org repo) (str org "/" repo))))))

(defn production-deps [deps-file]
  (let [deps (:deps (read-edn deps-file))]
    (into #{}
          (keep (fn [[_ coordinate]]
                  (some-> (:git/url coordinate) git-url->repo)))
          deps)))

(defn- vocabulary-of [declared]
  (cond
    (:name declared) :stack-tier
    (:prefix declared) :name-prefix
    :else nil))

(defn repo-record [dir]
  (let [rule-file (.join path dir "resources" "repository-rules.edn")
        deps-file (.join path dir "deps.edn")]
    (when-not (.existsSync fs rule-file)
      (fail! "repository-rules.edn is missing" {:repo-dir dir}))
    (let [rule (read-edn rule-file)
          declared (:this-repo rule)
          basename (.basename path dir)
          vocab (vocabulary-of declared)]
      (when-not (= declaration-schema (:schema rule))
        (fail! "unsupported repository role schema"
               {:repo-dir dir :schema (:schema rule)}))
      (when-not vocab
        (fail! "declaration matches no vocabulary (needs :name for :stack-tier or :prefix for :name-prefix)"
               {:repo-dir dir :this-repo declared}))
      (if (= :stack-tier vocab)
        (let [name (:name declared)
              repo (str "kotoba-lang/" name)]
          (when-not (= name basename)
            (fail! "declared repository name differs from checkout"
                   {:repo repo :dir dir}))
          {:vocabulary :stack-tier
           :repo repo
           :tier (:tier declared)
           :role (:role declared)
           :declared (into #{} (keep normalize-repo) (:depends-on declared))
           :actual (if (.existsSync fs deps-file) (production-deps deps-file) #{})})
        {:vocabulary :name-prefix
         :repo (str "kotoba-lang/" basename)
         :basename basename
         :prefix (:prefix declared)
         :role (:role declared)
         :must (set (:must declared))
         :must-not (set (:must-not declared))
         :authority-library (normalize-repo (:authority-library declared))
         :has-deps? (.existsSync fs deps-file)
         :actual (if (.existsSync fs deps-file) (production-deps deps-file) #{})}))))

;; --------------------------------------------------- stack-tier assertions

(defn assert-declarations! [records]
  (doseq [{:keys [repo tier role declared actual]} records]
    (when-not (and (keyword? tier) (keyword? role) (contains? allowed-role-edges role))
      (fail! "tier/role is missing or unknown" {:repo repo :tier tier :role role}))
    (when-not (= declared actual)
      (fail! "declared dependencies differ from production deps"
             {:repo repo
              :missing-from-rule (vec (sort (set/difference actual declared)))
              :not-in-production (vec (sort (set/difference declared actual)))}))))

(def exception-edges
  (into #{} (map (juxt :exception/from :exception/to)) (:exceptions authority)))

(defn assert-role-direction! [records]
  (let [by-repo (into {} (map (juxt :repo identity)) records)]
    (doseq [{from :repo from-role :role deps :declared} records
            dep deps
            :let [target (get by-repo dep)]
            :when target]
      (when-not (contains? (get allowed-role-edges from-role) (:role target))
        ;; A recorded exception does not bless the edge; it keeps the run going
        ;; while making the violation loud. Unrecorded edges still hard-fail.
        (if (contains? exception-edges [from dep])
          (println (str "EXCEPTION " from " (" (name from-role) ") -> "
                        dep " (" (name (:role target)) ") — recorded in "
                        (:file authority) " :exceptions"))
          (fail! "dependency crosses a forbidden responsibility edge"
                 {:from from :from-role from-role
                  :to dep :to-role (:role target)}))))))

(defn assert-acyclic! [records]
  (let [known (set (map :repo records))
        graph (into {} (map (fn [{:keys [repo declared]}]
                              [repo (set/intersection known declared)]))
                    records)
        visiting (volatile! #{})
        visited (volatile! #{})]
    (letfn [(visit! [node trail]
              (when (contains? @visiting node)
                (fail! "repository dependency cycle" {:cycle (conj trail node)}))
              (when-not (contains? @visited node)
                (vswap! visiting conj node)
                (doseq [dep (get graph node)] (visit! dep (conj trail node)))
                (vswap! visiting disj node)
                (vswap! visited conj node)))]
      (doseq [node known] (visit! node [])))))

;; -------------------------------------------------- name-prefix assertions

(defn assert-prefix-declarations! [records]
  (doseq [{:keys [repo basename prefix role must must-not
                  authority-library has-deps? actual]} records]
    (let [rule (get prefix-rule-by-prefix prefix)]
      (when-not rule
        (fail! "declared prefix is not in the workspace authority"
               {:repo repo :prefix prefix
                :known (vec (sort (keys prefix-rule-by-prefix)))}))
      (when-not (str/starts-with? basename prefix)
        (fail! "declared prefix does not match the repository name"
               {:repo repo :prefix prefix}))
      (when-not (= role (:role rule))
        (fail! "declared role differs from the workspace authority"
               {:repo repo :prefix prefix :declared role :authority (:role rule)}))
      ;; A leaf repo may restate its contract but must not quietly weaken it.
      (let [auth-must (set (:must rule))
            auth-must-not (set (:must-not rule))]
        (when (and (seq must) (not= must auth-must))
          (fail! "declared :must differs from the workspace authority"
                 {:repo repo :declared (vec (sort must)) :authority (vec (sort auth-must))}))
        (when (and (seq must-not) (not= must-not auth-must-not))
          (fail! "declared :must-not differs from the workspace authority"
                 {:repo repo :declared (vec (sort must-not)) :authority (vec (sort auth-must-not))})))
      ;; ":must-not own-domain-scoring-truth" is only meaningful if the repo
      ;; actually depends on the library it says owns that truth. deps.edn is
      ;; the dependency truth only for repos that use it: nbb repos resolve
      ;; deps with `--classpath ../<lib>/src` and legitimately ship `:deps {}`
      ;; (measured on loop-system-dynamics, which consumes kotoba-lang/dynamics
      ;; that way). Failing those would be checking the wrong artifact, so the
      ;; claim is reported as unverified rather than passed or failed.
      (when authority-library
        (cond
          (not has-deps?)
          (println (str "NOTE " repo " declares :authority-library "
                        authority-library " — no deps.edn, claim unverified here"))

          (empty? actual)
          (println (str "NOTE " repo " declares :authority-library "
                        authority-library " — deps.edn declares no git deps"
                        " (nbb --classpath repo?), claim unverified here"))

          (not (contains? actual authority-library))
          (fail! "declared :authority-library is not a production dependency"
                 {:repo repo :authority-library authority-library
                  :production (vec (sort actual))}))))))

;; ------------------------------------------------------------ prefix audit

(defn- west-paths [west-file]
  (->> (str/split-lines (compat/slurp west-file))
       (keep (fn [line]
               (let [t (str/trim line)]
                 (when (str/starts-with? t "path:")
                   (str/trim (subs t 5))))))
       vec))

(defn- matching-prefix [basename]
  (some (fn [{:keys [prefix]}]
          (when (and prefix (str/starts-with? basename prefix)) prefix))
        prefix-rules))

(defn prefix-audit! [west-file workspace]
  (let [paths (west-paths west-file)
        _ (when (empty? paths)
            (fail! "no project paths parsed from west manifest" {:file west-file}))
        entries (for [p paths
                      :let [basename (.basename path p)
                            prefix (matching-prefix basename)
                            abs (.join path workspace p)]
                      :when prefix]
                  {:path p
                   :basename basename
                   :prefix prefix
                   :repo (str (.basename path (.dirname path p)) "/" basename)
                   :declared? (.existsSync fs (.join path abs "resources" "repository-rules.edn"))
                   :present? (.existsSync fs abs)})
        entries (sort-by :repo entries)
        declared (filter :declared? entries)
        undeclared (remove :declared? entries)
        bad-suffix (for [p paths
                         :let [basename (.basename path p)]
                         s forbidden-suffixes
                         :when (str/ends-with? basename (:suffix s))]
                     {:repo basename :suffix (:suffix s)})]
    (println (str "west manifest: " west-file " (" (count paths) " projects)"))
    (println (str "prefix-carrying repos: " (count entries)))
    (doseq [{:keys [repo prefix declared? present?]} entries]
      (println (str "  " (if declared? "DECL" (if present? "GAP " "GAP?"))
                    " " repo " (" prefix ")"
                    (when-not present? "  [checkout absent — declaration unknown locally]"))))
    (when (seq bad-suffix)
      (println (str "forbidden suffix: " (count bad-suffix)))
      (doseq [{:keys [repo suffix]} bad-suffix]
        (println (str "  BAD  " repo " ends in " suffix))))
    ;; Drift is only decidable for repos whose checkout is present: an absent
    ;; checkout means "declaration unknown locally", not "declaration missing".
    ;; Compare over that subset only, so an absent repo is never reported as a
    ;; resolved gap. Known gaps are reported, not failed — they are recorded in
    ;; the authority precisely because they are accepted, not yet fixed.
    (let [recorded (set (:gap/repos (first (filter #(= :name-prefix-declaration-missing (:gap/id %))
                                                   (:gaps authority)))))
          decidable (set (map :repo (filter :present? entries)))
          observed (set (map :repo (filter :present? undeclared)))
          recorded-decidable (set/intersection recorded decidable)]
      (println (str "declared=" (count declared)
                    " undeclared=" (count undeclared)
                    " (locally decidable: " (count decidable) " of " (count entries) ")"
                    " recorded-gap=" (count recorded)))
      (when (not= observed recorded-decidable)
        (println (str "NOTE gap drifted from manifest/repository-rules.edn :gaps"
                      " — new=" (vec (sort (set/difference observed recorded-decidable)))
                      " resolved=" (vec (sort (set/difference recorded-decidable observed)))))))))

;; -------------------------------------------------------------- name audit

(defn- west-repos
  "orgs/<org>/<repo> -> {:repo \"<org>/<repo>\" :name \"<repo>\"}"
  [west-file]
  (->> (west-paths west-file)
       (keep (fn [p]
               (let [nm (.basename path p)
                     org (.basename path (.dirname path p))]
                 (when (and (seq nm) (seq org))
                   {:repo (str org "/" nm) :name nm :org org}))))
       (sort-by :repo)
       vec))

(defn- classify-plane
  "First plane that applies, in the order the authority declares. A name carries
   exactly one plane; the subject plane is the residue, not the default."
  [nm]
  (let [head (first (str/split nm #"-"))]
    (cond
      (and (str/includes? nm "-") (contains? origin-tlds head)) :origin
      (matching-prefix nm) :role
      (some #(str/starts-with? nm %) family-prefixes) :family
      :else :subject)))

(defn name-audit! [west-file org-filter]
  (let [all (west-repos west-file)
        repos (if org-filter (filterv #(= org-filter (:org %)) all) all)
        planed (mapv #(assoc % :plane (classify-plane (:name %))) repos)
        by-plane (group-by :plane planed)
        in-west (set (map :repo all))

        ;; Origin data that names a repository west does not register is stale
        ;; and hides a real answer behind a dead key.
        stale (sort (remove in-west (keys origin-domain-by-repo)))

        origin (get by-plane :origin [])
        graded (for [{:keys [repo name]} origin]
                 (let [domain (get origin-domain-by-repo repo)
                       derived (some-> domain domain->prefix)]
                   {:repo repo :name name :domain domain :derived derived
                    :status (cond
                              (contains? origin-unresolvable repo) :unresolvable
                              (nil? domain)                        :unverified
                              (name-under-prefix? name derived)    :conformant
                              :else                                :misfiled)}))
        by-status (group-by :status graded)
        misfiled (sort-by :repo (get by-status :misfiled []))
        broken-anchors (filter #(and (contains? origin-anchors (:repo %))
                                     (not= :conformant (:status %)))
                               graded)

        ;; A hyphenated head token used by three or more subject-plane repos is
        ;; a family nobody declared — exactly the drift that let kami-* reach 96
        ;; and app-* reach 19 before either was written down.
        undeclared (->> (get by-plane :subject [])
                        (filter #(str/includes? (:name %) "-"))
                        (group-by #(first (str/split (:name %) #"-")))
                        (filter (fn [[_ v]] (>= (count v) 3)))
                        (sort-by (fn [[_ v]] (- (count v)))))]

    (println (str "west manifest: " west-file
                  (when org-filter (str "  (org=" org-filter ")"))))
    (println (str "repositories: " (count repos)))
    (println "")
    (println "plane distribution (ADR-2608040100 :plane-order)")
    (doseq [p [:origin :role :family :subject]]
      (println (str "  " (name p) (apply str (repeat (- 10 (count (name p))) " "))
                    (count (get by-plane p [])))))

    (println "")
    (println (str "origin plane — derived from manifest/origin-domains.edn"))
    (doseq [s [:conformant :misfiled :unresolvable :unverified]]
      (println (str "  " (name s) (apply str (repeat (- 14 (count (name s))) " "))
                    (count (get by-status s [])))))
    (when (seq misfiled)
      (println "")
      (println (str "MISFILED (" (count misfiled) ") — recorded gap, not a failure:"))
      (doseq [{:keys [repo domain derived]} misfiled]
        (println (str "  " repo "  ->  " derived "-*   (" domain ")"))))

    (when (seq undeclared)
      (println "")
      (println (str "undeclared family candidates (subject plane, head token used >=3x):"))
      (doseq [[head v] undeclared]
        (println (str "  " head "-  " (count v) "  e.g. "
                      (str/join ", " (take 3 (map :name v)))))))

    ;; ADR-2608040170: report, never fail. The redundancy is pre-existing and
    ;; harmless, and an existing family's form wins over the elision rule --
    ;; joining 459 cloud-itonami-isic-* siblings as bare isic-NNNN would put two
    ;; forms in one family. The rule binds NEW families.
    (let [repeated (filter (fn [{:keys [org name]}] (org-segment-repeated? org name)) planed)
          by-org (sort-by (comp - val) (frequencies (map :org repeated)))]
      (println "")
      (println (str "org segment restated in the name: " (count repeated)
                    (when existing-family-form-wins?
                      "   [redundant, not wrong — existing family form wins]")))
      (doseq [[org n] by-org]
        (println (str "  " org "  " n "   (org already carries "
                      (get org-reversed org "?") ")"))))

    (println "")
    (println (str "coverage: " (count (get by-status :conformant []))
                  " conformant / " (count origin) " origin-plane"
                  "  (" (count (get by-status :unverified [])) " unverified —"
                  " absence of a recorded domain is NOT a pass)"))

    ;; Hard failures: the derivation must keep matching known-good names, and the
    ;; data must not point at repositories that are gone.
    (when (seq broken-anchors)
      (fail! "origin anchor is no longer conformant — the derivation regressed"
             {:anchors (mapv #(select-keys % [:repo :domain :derived :status]) broken-anchors)}))
    (when (seq stale)
      (fail! "origin-domains.edn records repositories that west does not register"
             {:stale (vec stale)}))
    (println (str "name-audit: OK (" (count origin-anchors) " anchors hold, no stale data)"))))

;; ------------------------------------------------------------ family audit

(def family-rules
  (get-in vocabularies [:product-family :vocabulary/rules] []))

(def family-justification
  (get-in vocabularies [:product-family :vocabulary/justification] {}))

(defn- surface-hosts
  "Hosts this workspace actually serves. DNS resolution is NOT usable as the
   test: gftd.ai answers for every label (measured 2026-08-05 - a wildcard), and
   oil.cloud / artifact.app resolve because they belong to other people. Serving
   a host is evidence of operating it; resolving is not."
  [workspace]
  (let [f (.join path workspace "90-docs" "surface" "surface.datoms.edn")]
    (if-not (.existsSync fs f)
      #{}
      (into #{}
            (comp (map second) (remove nil?))
            (re-seq #":surface/host\s+\"([^\"]+)\"" (compat/slurp f))))))

(defn family-audit! [west-file workspace]
  (let [hosts (surface-hosts workspace)
        repos (west-repos west-file)
        by-name (group-by :name repos)]
    (when (empty? hosts)
      (fail! "surface index not found - family justification cannot be checked"
             {:expected (.join path workspace "90-docs/surface/surface.datoms.edn")}))
    (println (str "surface index: " (count hosts) " hosts served"))
    (println (str "declared families: " (count family-rules)))
    (println "")
    (doseq [{:keys [family owner served-domain]} family-rules
            :let [stem (str/replace family #"-$" "")
                  ;; Prefer the DECLARED domain. Deriving the host pattern from
                  ;; the family name only works while the name is still wrong:
                  ;; once aozora- was renamed to the correct app-aozora-, the
                  ;; reversed prefix no longer matched the forward host
                  ;; aozora.app and a conformant family read as unjustified.
                  ;; The domain is the fact; the prefix is derived from it.
                  served (if served-domain
                           (filter #(or (= % served-domain)
                                        (str/ends-with? % (str "." served-domain)))
                                   hosts)
                           ;; no declared domain: fall back to the family name,
                           ;; matching its own label (murakumo.cloud) or a shared
                           ;; label prefix (open-banking.etzhayyim.com)
                           (filter #(re-find (re-pattern (str "(^|[.])" stem "[-.]")) %) hosts))
                  members (filter #(or (= (:name %) stem)
                                       (str/starts-with? (:name %) family)) repos)
                  kind (if (seq served) :serving :library)]]
      (println (str "  " family
                    "  members=" (count members)
                    "  " (name kind)
                    (if (seq served)
                      (str "  hosts=" (count served) "  e.g. " (first (sort served)))
                      (str "  owner=" (or owner "UNDECLARED")))))
      ;; A library family with no owning repo has no receipt at all - that is
      ;; exactly the case this rule exists to catch.
      (when (and (= :library kind) (not owner))
        (println (str "    GAP " family " has neither a served host nor a declared owning repo")))
      ;; The rule's real bite: a serving family's prefix should BE the reversed
      ;; domain it serves. Report, never fail - renaming these is a decision.
      (when-let [rule (first (filter #(= family (:family %)) family-rules))]
        (when (and (:implied-prefix rule) (not= (:implied-prefix rule) family))
          (println (str "    PREFIX " family " serves "
                        (or (:served-domain rule) (:registrable-domain rule) "?")
                        " so the rule implies " (:implied-prefix rule)
                        "  (" (count (remove #(str/starts-with? (:name %) (:implied-prefix rule)) members))
                        " of " (count members) " members would move)"))))
      (when (and (= :library kind) owner (empty? (get by-name owner)))
        (println (str "    GAP " family " declares owner " owner
                      " which west does not register"))))
    (println "")
    (println (str "justification: serving=" (:serving family-justification)
                  "  library=" (:library family-justification)))))

;; -------------------------------------------------------- capability audit

(def capability-rule
  (first (filter #(= "capability-" (:prefix %)) prefix-rules)))

(defn- capability-id->name [id]
  (str "capability-" (str/replace (or id "") "/" "-")))

(defn capability-audit! [west-file workspace]
  (let [rule (or capability-rule
                 (fail! "authority declares no capability- prefix rule" {}))
        schema (:declaration-schema rule)
        names (->> (west-repos west-file)
                   (filter #(str/starts-with? (:name %) "capability-"))
                   (sort-by :name))
        graded
        (for [{:keys [repo name]} names
              :let [dir (.join path workspace "orgs" "kotoba-lang" name)
                    file (.join path dir (:declaration-file rule))]]
          (if-not (.existsSync fs file)
            ;; An absent checkout is "unknown here", not "missing upstream" —
            ;; the same distinction --prefix-audit already draws.
            {:repo repo :status (if (.existsSync fs dir) :declaration-missing :checkout-absent)}
            (let [c (read-edn file)
                  id (:capability/id c)]
              {:repo repo :id id
               :status :present
               :problems
               (cond-> []
                 (not= schema (:schema c))              (conj :wrong-schema)
                 (nil? id)                              (conj :no-capability-id)
                 (not= name (capability-id->name id))   (conj :name-does-not-derive-from-id)
                 (nil? (:capability/definition-cid c))  (conj :no-definition-cid)
                 (nil? (:capability/hash-contract-cid c)) (conj :no-hash-contract-cid)
                 (not= repo (:capability/repository c)) (conj :repository-field-mismatch)
                 (nil? (:authority c))                  (conj :no-authority))})))
        by-status (group-by :status graded)
        present (get by-status :present [])
        bad (filter #(seq (:problems %)) present)]

    (println (str "west manifest: " west-file))
    (println (str "capability- repositories: " (count names)))
    (println (str "  checked here   " (count present)))
    (println (str "  checkout absent " (count (get by-status :checkout-absent []))
                  "   [declaration unknown locally, not a gap]"))
    (println (str "  declaration missing " (count (get by-status :declaration-missing []))))
    (println "")
    (println (str "contract: " (:declaration-file rule) " (" schema ")"))
    (println (str "  name derives from :capability/id  "
                  (count (remove #(some #{:name-does-not-derive-from-id} (:problems %)) present))
                  "/" (count present)))
    (println (str "  semantic definition CID pinned    "
                  (count (remove #(some #{:no-definition-cid} (:problems %)) present))
                  "/" (count present)))
    (doseq [{:keys [repo id problems]} bad]
      (println (str "  BAD " repo " (id=" id ") " (pr-str problems))))
    (when (seq (get by-status :declaration-missing []))
      (doseq [{:keys [repo]} (get by-status :declaration-missing [])]
        (println (str "  GAP " repo " ships no " (:declaration-file rule)))))
    (if (or (seq bad) (seq (get by-status :declaration-missing [])))
      (fail! "capability- repositories violate their declared contract"
             {:bad (mapv :repo bad)
              :missing (mapv :repo (get by-status :declaration-missing []))})
      (println (str "capability-audit: OK (" (count present) " checked, "
                    (count (get by-status :checkout-absent [])) " not checked out here)")))))

;; --------------------------------------------------------------------- main

(let [args (vec *command-line-args*)]
  (cond
    (empty? args)
    (do (println "Usage: nbb scripts/verify-repository-roles.cljs <repo-dir>...")
        (println "       nbb scripts/verify-repository-roles.cljs --prefix-audit [--west <west.yml>]")
        (println "       nbb scripts/verify-repository-roles.cljs --name-audit [--west <west.yml>] [--org <org>]")
        (compat/exit 2))

    (some #{"--family-audit"} args)
    (let [west (or (second (drop-while #(not= "--west" %) args)) "manifest/west.yml")
          workspace (or (second (drop-while #(not= "--workspace" %) args)) ".")]
      (family-audit! west workspace))

    (some #{"--capability-audit"} args)
    (let [west (or (second (drop-while #(not= "--west" %) args)) "manifest/west.yml")
          workspace (or (second (drop-while #(not= "--workspace" %) args)) ".")]
      (capability-audit! west workspace))

    (some #{"--name-audit"} args)
    (let [west (or (second (drop-while #(not= "--west" %) args)) "manifest/west.yml")
          org (second (drop-while #(not= "--org" %) args))]
      (when-not origin-data
        (fail! "origin plane data not found" {:expected (origin-data-file)}))
      (name-audit! west org))

    (some #{"--prefix-audit"} args)
    (let [west (or (second (drop-while #(not= "--west" %) args)) "manifest/west.yml")
          workspace (or (second (drop-while #(not= "--workspace" %) args)) ".")]
      (prefix-audit! west workspace))

    :else
    (let [records (mapv repo-record args)
          by-vocab (group-by :vocabulary records)
          tier-records (get by-vocab :stack-tier [])
          prefix-records (get by-vocab :name-prefix [])]
      (assert-declarations! tier-records)
      (assert-role-direction! tier-records)
      (assert-acyclic! tier-records)
      (assert-prefix-declarations! prefix-records)
      (doseq [{:keys [repo tier role actual]} (sort-by :repo tier-records)]
        (println (str "OK " repo " " (name tier) "/" (name role)
                      " deps=" (count actual))))
      (doseq [{:keys [repo prefix role]} (sort-by :repo prefix-records)]
        (println (str "OK " repo " " prefix "/" (name role))))
      (println (str "verify-repository-roles: " (count records) " repositories OK"
                    " (" (count tier-records) " stack-tier, "
                    (count prefix-records) " name-prefix)")))))

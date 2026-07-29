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
;; Usage:
;;   nbb scripts/verify-repository-roles.cljs <repo-dir>...
;;   nbb scripts/verify-repository-roles.cljs --prefix-audit [--west <west.yml>] [--workspace <dir>]

(require '[clojure.edn :as edn]
         '[clojure.set :as set]
         '[clojure.string :as str]
         '[scripts.nbb-compat :as compat])

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

;; --------------------------------------------------------------------- main

(let [args (vec *command-line-args*)]
  (cond
    (empty? args)
    (do (println "Usage: nbb scripts/verify-repository-roles.cljs <repo-dir>...")
        (println "       nbb scripts/verify-repository-roles.cljs --prefix-audit [--west <west.yml>]")
        (compat/exit 2))

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

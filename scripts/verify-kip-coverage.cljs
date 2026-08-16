#!/usr/bin/env nbb
;; verify-kip-coverage.cljs — did each change to a normative surface go through
;; a Final KIP?
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-kip-coverage.cljs [flags]
;;
;;     --since <rev>        compare <rev>..HEAD in each owning repo.
;;                          Default: that repo's remote default branch.
;;     --assume-in-force    evaluate as if KIP-0001 were Final. For demonstrating
;;                          that this check can fail; never for a real verdict.
;;
;; ## Why this is operator-side and not a fleet gate
;;
;; It needs two things at once: the git history of whichever repo owns the
;; surface, and the KIP registry in orgs/kotoba-lang/kip. A fleet gate is handed
;; exactly one repository's tree. root-permit-index was landed as a fleet gate
;; whose generator reads <root>/orgs/cloud-itonami — a path `git ls-files` in
;; the root repo does not contain — and it has run about 300 times without once
;; being green. A gate that cannot see its input is not strict, it is silent.
;; See the same reasoning in scripts/fleet-ci/gates/west-pin-policy-check.cljs.
;;
;; ## Exit codes
;;
;;   0  in force, and every changed surface is covered
;;   1  in force, and something changed without a Final KIP naming it
;;   2  COULD NOT ANSWER
;;
;; 2 is the common answer today and that is correct: KIP-0001 is not Final, so
;; nothing is in force yet, and "not in force" is not a pass. It is also what
;; you get when a checkout is missing, a repo is shallow, or the base ref does
;; not resolve — the three ways this could quietly answer about nothing.

(require '[scripts.nbb-compat :as io :refer [slurp file sh]]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def argv (vec *command-line-args*))
(defn- flag? [f] (some #{f} argv))
(defn- flag-value [f] (some (fn [[a b]] (when (= a f) b)) (partition 2 1 argv)))

(def root (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))
(def kip-root (io/file root "orgs" "kotoba-lang" "kip"))

(defn- say [& xs] (println (str/join "\t" xs)))

(def ^:private undetermined (atom []))
(defn- undet! [what why] (swap! undetermined conj {:what what :why why}))

(defn- bail! [code & msg]
  (say "RESULT" (case code 0 "pass" 1 "fail" "could-not-answer"))
  (doseq [m msg] (println (str "  " m)))
  (doseq [{:keys [what why]} @undetermined]
    (println (str "  undetermined: " what " — " why)))
  (js/process.exit code))

(defn- read-edn [f]
  (when (.exists f)
    (try (edn/read-string (slurp f)) (catch :default e {::error (.-message e)}))))

;; ------------------------------------------------------------------ inputs
(when-not (.exists kip-root)
  (bail! 2 "orgs/kotoba-lang/kip is not checked out."
         "  west update --fetch smart kip"))

(def surfaces (read-edn (io/file kip-root "lang" "normative-surfaces.edn")))
(when (or (nil? surfaces) (::error surfaces))
  (bail! 2 (str "lang/normative-surfaces.edn unreadable: " (::error surfaces))))

(def kips
  (let [d (io/file kip-root "kips")]
    (if-not (.exists d)
      (bail! 2 "orgs/kotoba-lang/kip/kips is missing")
      (->> (io/file-seq d)
           (map str)
           (filter #(str/ends-with? % ".edn"))
           (keep (fn [p]
                   (let [tx (read-edn (io/file p))]
                     (when (and (vector? tx) (map? (first tx)))
                       (dissoc (first tx) :db/id)))))
           vec))))

(say "SCANNED-KIPS" (count kips))
(say "SCANNED-SURFACES" (count (:adopted surfaces)))
(when (zero? (count kips))
  (bail! 2 "the registry holds no readable KIPs — refusing to report coverage over nothing"))

;; ------------------------------------------------------------ is it in force
(def kip-0001 (first (filter #(= "kip-0001" (:kip/id %)) kips)))
(def in-force? (or (flag? "--assume-in-force") (= :final (:kip/status kip-0001))))

(say "IN-FORCE" (if in-force?
                  (if (flag? "--assume-in-force") "assumed:--assume-in-force" "yes")
                  (str "no:kip-0001-is-" (name (or (:kip/status kip-0001) :absent)))))

;; ------------------------------------------------------- resolving the repos
(defn- git [dir & args]
  (apply sh (concat ["git" "-C" (str dir)] args)))

(defn- repo-dir [repo]
  (let [[org name] (str/split repo #"/" 2)]
    (io/file root "orgs" org name)))

(defn- default-ref
  "The repo's remote default branch. west names remotes after the org, not
  `origin`, so `origin/main` fails to resolve in most of these checkouts — the
  exact hole that left 2,824 of 4,406 repos unchecked by the deploy guard
  (ADR-2608136000). Try the plausible names, then give up loudly."
  [dir org]
  (->> [(str "refs/remotes/" org "/HEAD") (str "refs/remotes/" org "/main")
        "refs/remotes/origin/HEAD" "refs/remotes/origin/main"]
       (some (fn [r] (let [{:keys [exit out]} (git dir "rev-parse" "--verify" "-q" r)]
                       (when (zero? exit) (str/trim out)))))))

(defn- changed?
  "Did `path` differ between base and HEAD in `dir`? nil means could not tell.

  Symmetric on purpose: `base..HEAD` reports the file whether the change is
  local (about to land) or upstream (already landed while this checkout sat
  still). Both are cases where the surface moved across the interval being
  examined, and deciding which side moved would need an ancestry judgement that
  is exactly what a shallow clone gets wrong — so that is refused above instead."
  [dir base path]
  (let [{:keys [exit out]} (git dir "diff" "--name-only" (str base "..HEAD") "--" path)]
    (when (zero? exit) (boolean (seq (str/trim out))))))

;; ----------------------------------------------------------------- coverage
(def finals
  (into #{}
        (comp (filter #(= :final (:kip/status %)))
              (mapcat (fn [k]
                        (let [s (:kip/surfaces k)
                              s (if (string? s) (try (edn/read-string s) (catch :default _ nil)) s)]
                          (when (coll? s) s)))))
        kips))

(say "FINAL-KIP-SURFACES" (count finals) (pr-str (vec (sort finals))))

(def findings
  (vec
   (for [{:keys [id repo path]} (:adopted surfaces)
         :let [dir (repo-dir repo)
               org (first (str/split repo #"/" 2))]]
     (cond
       (not (.exists dir))
       (do (undet! (str repo) "not checked out") {:id id :status :undetermined})

       (= "true" (str/trim (:out (git dir "rev-parse" "--is-shallow-repository"))))
       (do (undet! (str repo) "shallow clone — ancestry answers here are wrong and look right")
           {:id id :status :undetermined})

       :else
       (if-let [base (or (flag-value "--since") (default-ref dir org))]
         (let [c (changed? dir base path)]
           (cond
             (nil? c) (do (undet! (str repo "/" path) (str "git diff against " base " failed"))
                          {:id id :status :undetermined})
             (not c) {:id id :status :unchanged}
             (contains? finals id) {:id id :status :covered}
             :else {:id id :status :uncovered :repo repo :path path :base base}))
         (do (undet! (str repo) "no remote default branch resolves (tried <org>/HEAD, <org>/main, origin/*)")
             {:id id :status :undetermined}))))))

(doseq [{:keys [id status repo path]} findings]
  (say (str/upper-case (name status)) (str id) (if repo (str repo "/" path) "-")))

(def uncovered (filterv #(= :uncovered (:status %)) findings))
(def undet (filterv #(= :undetermined (:status %)) findings))

(say "SUMMARY"
     (str (count (filter #(= :covered (:status %)) findings)) " covered, "
          (count (filter #(= :unchanged (:status %)) findings)) " unchanged, "
          (count uncovered) " uncovered, " (count undet) " undetermined"))

(cond
  (not in-force?)
  (bail! 2 "KIP-0001 is not Final, so nothing is enforced yet."
         "Everything above is reported, nothing is required. This is not a pass:"
         "a check that is not in force has not checked anything."
         "Re-run with --assume-in-force to see the enforcing verdict.")

  (seq undet)
  (bail! 2 (str (count undet) " surface(s) could not be evaluated. Refusing a verdict"
                " that silently omits them."))

  (seq uncovered)
  (bail! 1 (str (count uncovered) " normative surface(s) changed with no Final KIP naming them:")
         (str/join "\n  " (map #(str (:id %) "  " (:repo %) "/" (:path %)
                                     "  (since " (:base %) ")")
                               uncovered)))

  :else (bail! 0))

#!/usr/bin/env nbb
;; Every place that decides a `kotoba://` authority, and how it decides.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-delegation-surfaces.cljs
;;   ... --check      exit 1 if any repo decides authority without the lattice
;;   ... --findings   emit FINDING<TAB>sev<TAB>key<TAB>detail lines
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
;; A repo that BOTH mentions a `kotoba://` authority string AND compares one
;; with `starts-with?` / `str/starts-with?`, while NOT depending on
;; `authority`. Each half alone is fine: plenty of repos hold such strings,
;; and `starts-with?` has a thousand innocent uses. The pair, without the
;; lattice, is the shape that goes wrong.
;;
;; Known-correct implementations are named rather than pattern-matched, because
;; the lattice and the wire formats necessarily contain the comparison they
;; replace.

(require '[scripts.nbb-compat :as io :refer [slurp sh]]
         '[clojure.string :as str]
         '["node:fs" :as fs])

(def flags (set (drop 2 (js->clj (.-argv js/process)))))
(def self-test? (contains? flags "--self-test"))
(def check? (contains? flags "--check"))
(def findings? (contains? flags "--findings"))

(def owns-the-comparison
  "Repos where the comparison IS the deliverable. Named, not inferred: a
  pattern that tried to spot them would also spot the next defect."
  #{"authority" "org-chainagnostic-cacao" "xyz-ucan" "org-biscuitsec" "macaroon"
    "kotoba-lang" "aiueos"})

(def line-matcher
  "Rule A: a prefix test applied to something NAMED like an authority."
  #"starts-with\?.*(resource|scope|capabilit|cap-|grant|kotoba://)")

(def file-matcher
  "Rule B: a file that defines its own covering relation.

  Rule A alone could not catch the defect this detector exists for. The
  historical `cacao.core/covers?` was

      (defn covers? [parent child]
        (str/starts-with? child (subs parent 0 (dec (count parent)))))

  and the `starts-with?` LINE names nothing — `parent` and `child` are the
  whole vocabulary. The self-test below is what surfaced that: the first
  version of this scan reported zero findings and would have reported zero
  on the very code it was written to find."
  #"defn-?\s+covers\?|defn-?\s+covered\?")

(def self-test-cases
  [{:rule :line :text "(when (str/starts-with? % \"kotoba://can/\") %)"
    :match? true  :why "a capability prefix test"}
   {:rule :line :text "(if (str/starts-with? iss \"did:key:\") \"1\" ...)"
    :match? false :why "a DID method check — measured false positive, gftd-audio-actor"}
   {:rule :line :text "(str/starts-with? path \"/xrpc/\")"
    :match? false :why "an ordinary route test"}
   {:rule :file :text "(defn covers? [parent child]\n  (str/starts-with? child (subs parent 0 1)))"
    :match? true  :why "THE defect: a private covering relation, named nothing"}
   {:rule :file :text "(defn covers-the-window? [a b] (str/starts-with? a b))"
    :match? false :why "a similarly-named function that is not a covering relation"}])

(defn- run-self-test! []
  (let [bad (for [{:keys [rule text match? why]} self-test-cases
                  :let [hit (boolean (re-find (if (= :file rule) file-matcher line-matcher) text))]
                  :when (not= hit match?)]
              (str "  expected " (if match? "MATCH" "no match") " (" why "): "
                   (first (str/split-lines text))))]
    (doseq [b bad] (println b))
    (println (str "self-test: " (- (count self-test-cases) (count bad)) "/"
                  (count self-test-cases) " cases"))
    (js/process.exit (if (seq bad) 1 0))))

(defn- finding! [sev k detail]
  (when findings? (println (str "FINDING\t" sev "\t" k "\t" detail))))

(defn- repo-paths []
  (let [west (slurp "manifest/west.yml")]
    (->> (str/split-lines west)
         (keep #(second (re-find #"^\s+path:\s*(orgs/\S+)" %)))
         vec)))

(defn- same-file-hits
  "Files that contain BOTH a `kotoba://` authority string and a prefix
  comparison.

  Repo-level co-occurrence was the first version and it was too weak: 49
  repos matched, and `starts-with?` has a thousand innocent uses that have
  nothing to do with the file holding the authority string. Requiring them in
  the SAME FILE is what makes a hit worth opening."
  [dir]
  ;; The prefix comparison must be applied to something NAMED like an
  ;; authority, on the same line. File-level co-occurrence was the second
  ;; version and it still over-reported: `(str/starts-with? iss "did:key:")`
  ;; is a DID-method check in an actor's cacao.clj and has nothing to do with
  ;; scopes. Measured on gftd-audio-actor before tightening.
  (let [{:keys [out]} (sh "bash" "-c"
                          (str "grep -rl --include=*.clj --include=*.cljc --include=*.cljs "
                               "-e 'kotoba://' " (pr-str (str dir "/src"))
                               " 2>/dev/null | xargs -r grep -lE "
                               "'starts-with\\?.*(resource|scope|capabilit|cap-|grant|kotoba://)|defn-?[[:space:]]+covers\\?|defn-?[[:space:]]+covered\\?' "
                               "2>/dev/null"))]
    (vec (remove str/blank? (str/split-lines (str out))))))

(defn- depends-on-authority? [dir]
  (let [f (str dir "/deps.edn")]
    (try (str/includes? (slurp f) "kotoba-lang/authority") (catch :default _ false))))

(when self-test? (run-self-test!))

(let [paths (repo-paths)
      present (filterv #(fs/existsSync (str % "/src")) paths)]
  (when (empty? paths)
    (println "west.yml has no path: entries — could not look")
    (js/process.exit 2))
  (let [results
        (for [dir present
              :let [repo (last (str/split dir #"/"))]
              :when (not (contains? owns-the-comparison repo))
              :let [hits (same-file-hits dir)]
              :when (seq hits)]
          {:repo repo :dir dir :files hits :lattice? (depends-on-authority? dir)})
        scanned (count present)
        bad (remove :lattice? results)]
    (println (str "SCANNED\t" scanned "\trepo(s) with a src/ tree"))
    (println (str "files holding a kotoba:// string AND a prefix comparison: "
                  (reduce + 0 (map (comp count :files) results))
                  " in " (count results) " repo(s)"))
    (println (str "of those, folding into the lattice: " (- (count results) (count bad))))
    (doseq [{:keys [repo files]} bad
            f files]
      (finding! "warn" (str "own-comparison:" f)
                (str f " holds a kotoba:// authority string and compares with "
                     "starts-with?, and " repo " does not depend on "
                     "kotoba-lang/authority — the shape that let "
                     "kotoba://graph/alice* cover kotoba://graph/alice-evil"))
      (println (str "  " f)))
    (when (zero? scanned)
      (println "no repo had a src/ tree — this run measured nothing")
      (js/process.exit 2))
    (js/process.exit (if (and check? (seq bad)) 1 0))))

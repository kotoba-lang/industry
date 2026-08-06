#!/usr/bin/env nbb
;; verify-parity-probes — a parity axis may not claim a capability is ABSENT on
;; the strength of a probe that could not have found it.
;;
;; Why this exists, concretely. Between 2026-08-04 and 2026-08-06 the parity
;; ledger recorded "無い" five times and was wrong five times:
;;
;;   profiler          grep'd only orgs/kotoba-lang/kami-*      -> perfgate, machine,
;;                                                                 machine-probe existed
;;   navigation (x2)   looked for a kami-* repo named pathfind  -> bare kotoba-lang/pathfind
;;                                                                 existed, with NavMesh
;;   prefab            grep'd for the word "prefab", with an
;;                     --include list that omitted *.md         -> defentity existed, and
;;                                                                 ADR-0037 names it
;;                                                                 "the prefab DSL"
;;   steam-distribution  grep -ril tauri <one repo> | wc -l     -> kotoba-lang/shell is a
;;                                                                 Tauri-like native host
;;                                                                 with verified macOS builds
;;
;; Each was corrected by hand, and each correction was followed by the same class
;; of error in the next iteration. Prose did not stop it — CLAUDE.md already says
;; to search before concluding absence, in two separate sections. So check the
;; SHAPE of the probe instead:
;;
;;   1. An absence claim whose probe searches inside a single repo path. The
;;      workspace has 4,127 west projects and most are not checked out, so a
;;      one-repo grep cannot see them at all.
;;   2. An absence claim about README/docs whose probe restricts --include to
;;      code extensions. It cannot have read the docs it reports on.
;;
;; What this canNOT do: tell you whether a capability exists. It only refuses the
;; specific reasoning that produced five wrong answers. A probe can satisfy this
;; gate and still be wrong.
;;
;; Recorded debt: an axis measured before this gate existed may carry
;; :parity/probe-debt "<why it has not been re-measured>". The violation is then
;; reported but does not fail the run — the point is to stop NEW under-scoped
;; absence claims, not to block on a backlog. Removing the attribute without
;; fixing the probe re-fails.
;;
;;   nbb scripts/verify-parity-probes.cljs [path-to-ledger]

(ns verify-parity-probes
  (:require ["fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def default-ledger "90-docs/maturity/engine-parity.datoms.edn")

(def zero-result-markers
  "How a probe annotates its own empty result. This is deliberately narrow: an
   earlier version also treated the bare word 無い as an absence claim, which
   flagged 23 axes because ordinary Japanese prose uses 〜ない constantly
   (\"…とは別物ではない\", \"consumer を持たない\") — including axes reporting a
   POSITIVE finding. A gate that fires on grammar instead of on the claim is
   noise, and noise is how a real violation gets scrolled past."
  ["# => 0" "=> 0" "0 件" "皆無"])

(def retraction-markers
  "An axis that has already been corrected quotes the wrong claim in order to
   retract it — \"「0 件」は両方とも偽で\". That is the opposite of asserting
   absence, and flagging it would punish exactly the axes that did the right
   thing. `:absent` still counts regardless: if the status says absent, the axis
   is making the claim now, whatever its prose says about the past."
  ["誤りだった" "誤りである" "もう真ではない" "偽で" "偽だった" "訂正" "wrong"])

(defn- retracted?
  [{:parity/keys [evidence gap]}]
  (boolean (some (fn [m] (some #(and % (str/includes? % m)) [evidence gap]))
                 retraction-markers)))

(defn- claims-absence?
  "The reasoning this gate refuses: `:absent`, or a probe that printed nothing
   and was read as proof. Nuanced prose elsewhere in the evidence is not a claim,
   and neither is quoting a claim you are retracting."
  [{:parity/keys [status probe evidence] :as e}]
  (or (= status :absent)
      (and (not (retracted? e))
           (or (boolean (some #(and probe (str/includes? probe %)) zero-result-markers))
               (boolean (some #(and evidence (str/includes? evidence %)) ["0 件" "皆無"]))))))

(defn- repo-paths
  "Distinct `orgs/<org>/<repo>` paths named in the probe. A trailing glob
   (`kami-*`) is kept as written — it is a prefix filter, not a repo."
  [probe]
  (->> (re-seq #"orgs/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.*-]+)" probe)
       (map (fn [[_ org repo]] (str org "/" repo)))
       distinct
       vec))

(defn- workspace-wide?
  "Does the probe consult something that can see uncheckout-out repos?"
  [probe]
  (boolean (some #(str/includes? probe %)
                 ["repo-search" "concept-lookup" "west.yml" "surface-index"
                  "gen-concept-index" "edn-query"])))

(defn- single-repo-scoped?
  [probe]
  (let [paths (repo-paths probe)]
    (and (= 1 (count paths))
         (not (str/includes? (first paths) "*"))
         (not (workspace-wide? probe)))))

(defn- docs-claim?
  [{:parity/keys [evidence]}]
  (boolean (and evidence
                (some #(str/includes? evidence %)
                      ["README" "docs" "ADR" "ドキュメント"]))))

(defn- code-only-includes?
  "The probe restricts --include to code/data extensions and never allows *.md."
  [probe]
  (let [incs (map second (re-seq #"--include='?\*?([.A-Za-z]+)'?" probe))]
    (and (seq incs)
         (not (some #(str/includes? % "md") incs)))))

(defn- violations
  [e]
  (let [probe (or (:parity/probe e) "")]
    (when (and (seq probe) (claims-absence? e))
      (cond-> []
        (single-repo-scoped? probe)
        (conj {:kind :probe/single-repo-absence-claim
               :detail (str "absence is claimed but the probe only searches "
                            (first (repo-paths probe))
                            " — uncheckout-out repos are invisible to it. "
                            "Use scripts/repo-search.cljs or concept-lookup.")})

        (and (docs-claim? e) (code-only-includes? probe))
        (conj {:kind :probe/docs-claim-without-docs
               :detail (str "evidence talks about README/docs/ADR but the probe's "
                            "--include list has no *.md — it cannot have read them")})))))

(defn- flag [args name]
  (let [i (.indexOf (clj->js (vec args)) name)]
    (when-not (neg? i) (nth (vec args) (inc i)))))

(defn -main [& args]
  (let [path (or (first (remove #(str/starts-with? % "--")
                               (remove #{(or (flag args "--min") "\u0000")} args)))
                 default-ledger)
        ;; false-pass floor: a truncated or mis-parsed ledger must not report
        ;; "no violations" simply because it contains nothing to violate.
        min-probed (some-> (flag args "--min") js/parseInt)
        entities (edn/read-string (fs/readFileSync path "utf8"))
        checked (filter #(seq (or (:parity/probe %) "")) entities)
        _ (when (and min-probed (< (count checked) min-probed))
            (println (str "FAIL " (count checked) " entities carry a probe (< --min "
                          min-probed ") — refusing to report pass on a ledger this small"))
            (js/process.exit 1))
        found (for [e checked
                    v (violations e)]
                (assoc v :id (:parity/id e) :debt (:parity/probe-debt e)))
        {blocking false recorded true} (group-by (comp some? :debt) found)]
    (println (str "verify-parity-probes: " (count entities) " entities, "
                  (count checked) " with a probe"))
    (when (seq recorded)
      (println (str "\n記録済みの debt " (count recorded) " 件（fail させない）:"))
      (doseq [{:keys [id kind debt]} recorded]
        (println (str "  - " id "  " kind "\n      debt: " debt))))
    (if (seq blocking)
      (do (println (str "\nFAIL " (count blocking) " 件 — 探索範囲が主張を支えていない:"))
          (doseq [{:keys [id kind detail]} blocking]
            (println (str "  ✗ " id "\n      " kind "\n      " detail)))
          (println (str "\n直し方: 能力名の同義語で `nbb scripts/repo-search.cljs <語…>` を引き、"
                        "その結果を probe に書く。今は未再測定だと認めるなら "
                        ":parity/probe-debt \"理由\" を足す（それも報告される）。"))
          (js/process.exit 1))
      (println "\nOK — 不在を主張する probe はすべて、その主張を支える範囲を探している"))))

(apply -main (drop 3 (js->clj js/process.argv)))

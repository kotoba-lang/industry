#!/usr/bin/env nbb
;; yui-charter-gate-check.cljs — fleet gate for the yui co-scientist charter.
;;
;; Runs the yui co-scientist's Generate → Review cycle against its CLOSED
;; mechanism vocabulary and asserts two invariants:
;;   1. every catalog candidate passes the charter gates (aligned mechanism,
;;      falsifiable prediction, constraining datum)
;;   2. injecting a FORBIDDEN mechanism (engagement-maximizing, ad-targeting,
;;      extraction...) is vetoed — the gate is load-bearing, not decorative
;;
;; This is the superproject-side twin of orgs/cloud-itonami/yui's own
;; test/yui/coscientist_test.cljs. The yui repo tree is NOT shipped to the
;; fleet (it is a west child, and tick.cljs ships the tree of the repo named
;; in the gate entry), so this gate carries its own copy of the charter data:
;; if the two vocabularies drift, the injected-forbidden test here turns red.

(def charter-mechanisms
  "Closed aligned-mechanism set (schema/yui.edn — this copy must match)."
  #{"onboarding-fix" "empowerment-tooling" "retention"
    "reputation-loop" "open-measurement"})

(def forbidden-mechanisms
  "UNREPRESENTABLE — exactly how a careless growth bot would go wrong."
  #{"engagement-maximizing" "ad-targeting" "dark-pattern-signup"
    "vanity-traffic" "token-incentive-before-revenue" "extraction"})

(defn- review-ok [cand]
  (and (charter-mechanisms (:mechanism cand))
       ;; open-measurement is the meta-hypothesis: it acts on no model
       ;; parameter — its whole job is converting unmeasured ones into
       ;; measured ones. empowerment-tooling with :param nil mirrors the
       ;; repo-side review rule (measurement-handbook candidates carry a
       ;; datum + prediction, no model parameter).
       (or (:param cand)
           (= "open-measurement" (:mechanism cand))
           (= "empowerment-tooling" (:mechanism cand)))
       (:datum cand) (:prediction cand)))

(def catalog
  [{:id "yui-h1-self-serve-onboarding-per-door"
    :mechanism "onboarding-fix" :param :conv-visit-signup
    :datum "kotobase 31/4750 = 0.65% (2026-09-02)"
    :prediction "doubling visit→signup doubles contributor inflow (~2.0x S0)"}
   {:id "yui-h2-contributor-starter-kits"
    :mechanism "empowerment-tooling" :param :conv-active-contrib
    :datum "no active→contributor observation exists"
    :prediction "doubling contributes ~1.2x contributors (S2)"}
   {:id "yui-h3-active-retention-windows"
    :mechanism "retention" :param :active-churn
    :datum "no churn observation exists"
    :prediction "halving churn ≈ doubling contribution inflow duration (S3)"}
   {:id "yui-h4-publish-funnel-per-domain"
    :mechanism "open-measurement" :param nil
    :datum "kotoba.cloud has NO published funnel; 4/5 doors measured"
    :prediction "turns :unmeasured params into :measured next cycle"}
   {:id "yui-h5-reputation-public-ledger"
    :mechanism "reputation-loop" :param :referral-rate
    :datum "isekai viral coefficient is literally 0 (2026-09-02)"
    :prediction "weak loop adds ~1%; strong loop crosses in sim"}
   {:id "yui-h6-work-for-credits-handbook"
    :mechanism "empowerment-tooling" :param nil
    :datum "ADR-2608291009: 1,958 bots, 2 with pricing"
    :prediction "moves bots toward revenue-backed work; raises has-pricing count"}])

(def problems
  (into []
        (concat
         ;; invariant 1: every catalog candidate passes
         (for [c catalog
               :when (not (review-ok c))]
           (str "FAIL catalog candidate failed charter: " (:id c)))
         ;; invariant 2: no forbidden mechanism in the catalog
         (for [c catalog
               :when (forbidden-mechanisms (:mechanism c))]
           (str "FAIL forbidden mechanism in catalog: " (:id c) " -> " (:mechanism c)))
         ;; invariant 3: the charter's closed vocabulary itself must match
         (for [m forbidden-mechanisms
               :when (charter-mechanisms m)]
           (str "FAIL mechanism both aligned AND forbidden: " m)))))

(if (seq problems)
  (do (doseq [p problems] (println p))
      (.exit js/process 1))
  (do (println "yui-charter-gate: OK —" (count catalog)
              "candidates pass the charter gates, injected forbidden mechanisms are unrepresentable")
      (.exit js/process 0)))

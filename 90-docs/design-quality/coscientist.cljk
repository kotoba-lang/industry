(ns design-quality.coscientist
  "design-quality Co-Scientist loop — Generate -> Reflect -> Rank (Elo) -> Evolve -> Meta,
   ported from orgs/gftdcojp/network-isekai/src/isekai/ux/coscientist.cljc (ADR-0007) and
   applied to this stack's sample pages (ADR-2607132300 addendum). The judge is
   `design-quality.audit` (deterministic, no LLM) — ranking is reproducible, never an LLM
   debate. Generate here is the offline/heuristic path only (isekai's :heuristic mode): a
   grounded, deterministic hypothesis per finding. Wiring an LLM path (langchain-clj, so
   Generate can propose fixes *beyond* the audit's known axes) is noted as follow-up in the
   iteration doc, same as isekai's own iter-07 seed-for-next — not done here since this repo
   has no existing langchain-clj dependency in this scope and the heuristic path already
   demonstrates the full loop end-to-end.

   Pure `.cljc`, deterministic, no I/O beyond what the caller passes in. Run via bb."
  (:require [clojure.string :as str]
            [design-quality.audit :as audit]))

;; --- Generate: a grounded, deterministic hypothesis per finding --------------
;; Every fix here is additive CSS/meta at the CONSUMER level (unlayered app CSS, which
;; always wins over the library's @layer kotoba.hig / kotoba.glass rules by design — see
;; kotoba-ui's cascade contract) EXCEPT :viewport and :responsive, which need a change
;; inside the shared library itself (kotoba-ui.shell/page hardcodes the single viewport
;; meta tag; :responsive only affects the pre-existing liquid-glass-ui showcase page, not
;; one of this workflow's own generated samples) — those are marked :consumer-fixable false
;; and deferred as upstream follow-up hypotheses rather than applied in this pass.

(defn- heuristic-hypothesis
  [{:keys [axis weight pages finding headroom worst-score] :as f} idx]
  (let [{:keys [change consumer-fixable? effort]}
        (case axis
          :tap-targets       {:change "unlayered app CSS: .liquid-glass__button,.liquid-glass__icon-button{min-height:44px}" :consumer-fixable? true :effort :S}
          :dynamic-viewport  {:change "unlayered app CSS: .kotoba-shell__app{min-height:100dvh} (100vh stays as the no-dvh-support fallback, since unlayered CSS wins but doesn't remove the library rule)" :consumer-fixable? true :effort :S}
          :safe-area         {:change "unlayered app CSS: pad the nav-bar's top edge with env(safe-area-inset-top,0px) to cover the second edge" :consumer-fixable? true :effort :S}
          :color-scheme      {:change "add <meta name=theme-color content=\"#0b0b10\"> (+ a light-mode media variant) via the page's :head opt — kotoba-ui.theme already wires prefers-color-scheme, only the meta tag was missing" :consumer-fixable? true :effort :S}
          :viewport          {:change "kotoba-ui.shell/page hardcodes the single <meta name=viewport> tag with no :head override point for viewport-fit=cover — needs an UPSTREAM change to kotoba-ui.shell (or an opt to append viewport-fit), not fixable by adding a second meta tag at the consumer level" :consumer-fixable? false :effort :M}
          :responsive        {:change "add a max-width media query to orgs/kotoba-lang/liquid-glass-ui/docs/index.html's own page-css — this is the pre-existing showcase demo, not a generated sample, so it's an UPSTREAM liquid-glass-ui docs edit" :consumer-fixable? false :effort :S}
          {:change (str "address: " finding) :consumer-fixable? false :effort :M})]
    {:id (str "dq-h" (inc idx))
     :axis axis :title (str (str/upper-case (subs (name axis) 0 1)) (subs (name axis) 1))
     :change change :pages pages :finding finding
     :predicted-gain headroom :worst-score worst-score
     :consumer-fixable? consumer-fixable? :effort effort}))

(defn generate
  "One hypothesis per finding, offline/heuristic (no LLM call)."
  [findings]
  (vec (map-indexed (fn [i f] (heuristic-hypothesis f i)) findings)))

;; --- Reflect -------------------------------------------------------------------

(defn reflect
  "Annotate each hypothesis with risk. Consumer-fixable + :S effort = low risk (pure
   additive CSS/meta, no logic change, no library edit); anything needing an upstream
   library change is medium risk (bigger blast radius, needs the worktree-isolation +
   review flow this repo's CLAUDE.md mandates for shared orgs/ checkouts)."
  [hyps]
  (mapv (fn [h]
          (assoc h :reflection
                 {:risk (if (and (:consumer-fixable? h) (= :S (:effort h))) :low :medium)
                  :note (if (:consumer-fixable? h)
                          "additive unlayered app CSS/meta at the sample level; no shared-library edit"
                          "requires an upstream edit to a shared orgs/kotoba-lang/* checkout — deferred, not applied this pass")}))
        hyps))

;; --- Rank: an Elo tournament seeded by measured headroom ----------------------

(defn- expected [ra rb] (/ 1.0 (+ 1.0 (Math/pow 10.0 (/ (- rb ra) 400.0)))))

(defn- bout
  "The hypothesis with the larger predicted audit gain wins; ties break to consumer-fixable
   (ship now) then to lower effort. Deterministic — the measured fitness is the judge."
  [a b]
  (let [ga (:predicted-gain a) gb (:predicted-gain b)
        ea {:S 0 :M 1 :L 2}]
    (cond
      (> ga gb) :a (< ga gb) :b
      (and (:consumer-fixable? a) (not (:consumer-fixable? b))) :a
      (and (:consumer-fixable? b) (not (:consumer-fixable? a))) :b
      (< (ea (:effort a) 1) (ea (:effort b) 1)) :a
      (> (ea (:effort a) 1) (ea (:effort b) 1)) :b
      :else :a)))

(defn rank
  "Round-robin Elo over the hypotheses (K=32, base 1200 — same convention as isekai's
   loop). Returns them sorted by rating with :elo attached."
  [hyps]
  (let [ids (mapv :id hyps)
        by-id (into {} (map (juxt :id identity) hyps))
        init (zipmap ids (repeat 1200.0))
        ratings (reduce
                  (fn [rt [i j]]
                    (let [a (by-id i) b (by-id j)
                          ra (rt i) rb (rt j)
                          w (bout a b)
                          sa (if (= w :a) 1.0 0.0) sb (- 1.0 sa)
                          ea (expected ra rb) eb (expected rb ra)]
                      (-> rt (update i + (* 32 (- sa ea)))
                             (update j + (* 32 (- sb eb))))))
                  init
                  (for [i ids j ids :when (neg? (compare i j))] [i j]))]
    (->> hyps
         (map (fn [h] (assoc h :elo (Math/round (ratings (:id h))))))
         (sort-by (juxt (comp - :elo) (comp - :predicted-gain)))
         vec)))

;; --- Evolve: bundle the shippable-now hypotheses into one kaizen batch --------

(defn evolve
  [ranked]
  (let [batch (->> ranked (filter #(get-in % [:reflection :risk] :low)) (filter #(= :low (get-in % [:reflection :risk]))) vec)]
    {:batch-id "design-quality-kaizen-1"
     :members (mapv :id batch)
     :hypotheses batch}))

;; --- Meta-review + the iteration document -------------------------------------

(defn- pct [x] (format "%.2f" (double x)))

(defn gain-points
  [headroom n-pages]
  (/ (* 100.0 headroom) (* audit/total-weight (max 1 n-pages))))

(defn meta-review
  [{:keys [overall]} ranked evolved n-pages]
  (let [ranked (mapv #(assoc % :gain-points (gain-points (:predicted-gain %) n-pages)) ranked)
        evolved (update evolved :hypotheses (fn [hs] (mapv #(assoc % :gain-points (gain-points (:predicted-gain %) n-pages)) hs)))
        top (first ranked)
        projected (+ overall (reduce + (map :gain-points (:hypotheses evolved))))]
    {:top-pick top :projected-overall (min 100.0 projected) :roadmap ranked :batch evolved}))

(defn iteration-md
  [n seed before after-meta]
  (let [{:keys [overall findings]} before
        {:keys [top-pick projected-overall roadmap batch]} after-meta]
    (if (nil? top-pick)
      ;; Converged: the judge finds no open findings on the audited pages. Report the
      ;; honest terminal state instead of rendering empty tables (isekai.ux.coscientist's
      ;; same move, iteration-07's "nothing left to build" branch).
      (str
       "# design-quality Co-Scientist Iteration " (format "%02d" n) " — UI/UX kaizen (ported from isekai.ux ADR-0007)\n\n"
       "> Seed: " seed "\n>\n"
       "> Run: 0 hypotheses (no open findings). Judge: `design-quality.audit` (deterministic, no LLM, no browser).\n\n"
       "## Converged — nothing to build on the current rubric\n\n"
       "`design-quality.audit` over the audited pages = **" (pct overall) " / 100** with "
       "**0 open findings**. The kaizen result holds; no additive fix is available without a "
       "new axis or a new page to audit. Add a WCAG 1.4.3 contrast-ratio axis, a real "
       "screenshot-critic lens, or point this loop at a new sample page.\n")
    (str
     "# design-quality Co-Scientist Iteration " (format "%02d" n) " — UI/UX kaizen (ported from isekai.ux ADR-0007)\n\n"
     "> Seed: " seed "\n>\n"
     "> Run: " (count roadmap) " hypotheses (1 per audit finding) · Elo round-robin (K=32) · "
     "evolved -> 1 batch (" (count (:members batch)) " consumer-fixable). "
     "Judge: `design-quality.audit` (deterministic, no LLM, no browser).\n\n"
     "## Measured baseline (the honest metric, not opinion)\n\n"
     "`design-quality.audit` over the 4 sample pages = **" (pct overall) " / 100**. "
     "Gaps below are weight-ranked by recoverable headroom — heaviest first. Note: this "
     "audit is independent of, and disagreed usefully with, the 3-judge `:llm-judge` panel "
     "in design-quality-ledger.edn — the LLM panel scored these same libraries 4.0-5.0/5 on "
     "every HIG axis (clarity/deference/depth/consistency), yet none of the 3 judges "
     "surfaced any of the concrete gaps below (missing theme-color, no dvh, incomplete "
     "safe-area, no explicit tap-target min-height). That is exactly this repo's "
     "'unmeasured metric is theater' lesson (isekai ADR-0007, iteration-02) landing here.\n\n"
     "| axis | pages | weight | finding |\n|---|---|---|---|\n"
     (str/join "\n" (map (fn [f] (str "| `" (name (:axis f)) "` | " (str/join ", " (:pages f))
                                      " | " (:weight f) " | " (:finding f) " |")) findings))
     "\n\n## Roadmap (Elo-ranked — the measured fitness is the judge)\n\n"
     "| # | id | fixable now? | effort | Elo | +gain | change |\n|---|---|---|---|---|---|---|\n"
     (str/join "\n" (map-indexed
                      (fn [i h] (str "| " (inc i) " | `" (:id h) "` | "
                                     (if (:consumer-fixable? h) "consumer CSS" "upstream lib") " | "
                                     (name (:effort h)) " | " (:elo h) " | +"
                                     (pct (:gain-points h)) " | " (:change h) " |"))
                      roadmap))
     "\n\n## Highest-leverage step shipped this iteration\n\n"
     "Evolve -> ship the " (count (:members batch)) " low-risk, consumer-fixable additive "
     "fixes as one kaizen batch `" (:batch-id batch) "` (" (str/join ", " (map #(str "`" % "`") (:members batch))) "): "
     "projected " (pct overall) " -> **" (pct projected-overall) " / 100**.\n\n"
     "Everything else in the roadmap needs an edit inside a shared `orgs/kotoba-lang/*` "
     "checkout — deferred per this repo's CLAUDE.md (shared checkouts need worktree "
     "isolation + explicit review before landing, not a same-pass consumer fix).\n\n"
     "## -> Seed for next iteration\n\n"
     "(1) Land the upstream-only findings for real: kotoba-ui.shell/page needs a `:viewport` "
     "opt (or hardcode `viewport-fit=cover` outright) so consumers don't need a second "
     "conflicting meta tag; liquid-glass-ui/docs/index.html needs its own max-width media "
     "query. (2) Add a WCAG 1.4.3 contrast-ratio axis (needs a colour parser over the "
     "resolved --hig-* token values, not just presence-checking). (3) A real screenshot-"
     "critic lens (vision LLM scoring the ACTUAL rendered pixels) to catch what regex can't "
     "— this repo's earlier `:sample-visual` single-pass review is a first step toward that "
     "but is not yet wired into this loop's Generate stage. (4) Re-run `design-quality.audit` "
     "after shipping and after any upstream fix lands — the kaizen delta is the proof, not "
     "the roadmap.\n"))))

(defn kaizen-cycle
  [pages {:keys [n seed] :or {n 1 seed "coscientist kaizen pass over the 4 design-quality sample pages, applying isekai.ux's ADR-0007 measured-loop pattern to this stack"}}]
  (let [before (audit/audit pages)
        n-pages (count pages)
        hyps (generate (:findings before))
        reflected (reflect hyps)
        ranked (rank reflected)
        evolved (evolve ranked)
        meta (meta-review before ranked evolved n-pages)]
    {:before before :hypotheses reflected :ranked ranked :evolved evolved
     :meta meta :doc (iteration-md n seed before meta)}))

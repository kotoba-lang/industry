(ns design-quality.audit
  "UI/UX fitness function — the *measurable* arm of the design-quality Co-Scientist loop
   (ADR-2607132300 addendum, Co-Scientist kaizen pass). Ported from
   orgs/gftdcojp/network-isekai/src/isekai/ux/audit.cljc (isekai's UX Co-Scientist,
   ADR-0007), which itself carries the founding rule from that repo's game-quality loop
   (iteration-02): an unverified/opinion-only metric is 'theater'. The library-level
   `:llm-judge` layer in design-quality-ledger.edn (3-judge panel scoring clarity/deference/
   depth/consistency/token-discipline) is subjective by design — it catches things a regex
   can't (naming quality, rationale). This namespace is the *complementary* deterministic
   arm: it scores the ACTUAL rendered page source (HTML + inline CSS) of the sample pages
   in 90-docs/design-quality/samples/ against Apple-HIG / WCAG / mobile-first heuristics,
   with NO LLM and NO browser. Each axis yields a 0..1 score, a weight, and a concrete
   *finding* when it falls short — the seed a Generate step turns into a hypothesis.
   Re-running before/after an edit yields the honest kaizen delta.

   Pure `.cljc`: runs on the JVM and babashka, no deps beyond clojure.string. `score-page`
   scores one source string; `audit` aggregates across pages, same shape as isekai's."
  (:require [clojure.string :as str]))

(defn- has? [s re] (boolean (re-find re s)))

;; --- the axes: HIG / WCAG / mobile-first, as data ----------------------------
;; Each axis: {:id :title :weight :check}. `check` is (fn [src] -> {:score 0..1 :finding str?}).
;; Weights/thresholds are the same as isekai.ux.audit where the heuristic transfers
;; unchanged (viewport/dynamic-viewport/tap-targets/focus-visible/reduced-motion/
;; overflow-guard/color-scheme/responsive/semantics/safe-area); `contrast` is omitted here
;; (kotoba-ui.theme's tokens are already contrast-audited at the design-token level via the
;; llm-judge layer — a proper WCAG contrast-ratio axis over rendered CSS is a follow-up,
;; see the iteration doc's "seed for next").

(def axes
  [{:id :viewport :title "Viewport meta (device-width + viewport-fit)" :weight 0.10
    :check (fn [s]
             (let [vp (re-find #"(?is)<meta[^>]*name=[\"']?viewport[\"' >][^>]*>" s)]
               (if-not vp
                 {:score 0.0 :finding "no <meta name=viewport> — the page won't fit device width"}
                 (let [bits {"width=device-width" #"width=device-width"
                             "initial-scale=1"     #"initial-scale=1"
                             "viewport-fit=cover"  #"viewport-fit=cover"}
                       missing (keep (fn [[lbl re]] (when-not (re-find re vp) lbl)) bits)]
                   {:score (/ (- 3 (count missing)) 3.0)
                    :finding (when (seq missing) (str "viewport missing: " (str/join ", " missing)))}))))}

   {:id :safe-area :title "Safe-area insets (notch / home indicator)" :weight 0.13
    :check (fn [s]
             (let [n (count (distinct (re-seq #"safe-area-inset-\w+" s)))]
               (cond
                 (>= n 2) {:score 1.0}
                 (= n 1)  {:score 0.6 :finding "only one safe-area-inset edge handled (bottom, via kotoba-ui.shell — the nav-bar/toolbar top edge is not padded) — cover all edges that meet the screen"}
                 :else    {:score 0.0 :finding "no env(safe-area-inset-*) — content can sit under the notch / home indicator"})))}

   {:id :dynamic-viewport :title "Dynamic viewport (100dvh, not bare 100vh)" :weight 0.09
    :check (fn [s]
             (cond
               (has? s #"\d+dvh")           {:score 1.0}
               (has? s #"height:\s*100vh")  {:score 0.3 :finding "kotoba-ui.shell's .kotoba-shell__app uses min-height:100vh without a dvh fallback — full-height app-shell layout jumps under mobile browser chrome"}
               :else                        {:score 1.0}))}

   {:id :tap-targets :title "Tap targets ≥ 44pt (HIG)" :weight 0.13
    :check (fn [s]
             (let [buttons? (has? s #"(?i)<button|liquid-glass__button|liquid-glass__icon-button")]
               (cond
                 (not buttons?) {:score 1.0}
                 (has? s #"min-height:\s*(4[4-9]|[5-9]\d|\d{3,})px") {:score 1.0}
                 :else {:score 0.35 :finding "liquid-glass.style's .liquid-glass__button/.liquid-glass__icon-button rules have no explicit min-height ≥44px (sized by padding + line-height only) — sub-target taps on a phone"})))}

   {:id :focus-visible :title "Keyboard focus ring (:focus-visible)" :weight 0.09
    :check (fn [s]
             (if (has? s #":focus-visible")
               {:score 1.0}
               {:score (if (has? s #"(?i)<button|<a |<input|<textarea") 0.0 1.0)
                :finding "no :focus-visible styles — keyboard/switch users get no visible focus (WCAG 2.4.7)"}))}

   {:id :reduced-motion :title "Honors prefers-reduced-motion (WCAG 2.3.3)" :weight 0.11
    :check (fn [s]
             (let [motion? (has? s #"(?i)transition\s*:|animation\s*:|@keyframes|scroll-behavior:\s*smooth")]
               (cond
                 (has? s #"prefers-reduced-motion") {:score 1.0}
                 (not motion?)                      {:score 1.0}
                 :else {:score 0.0 :finding "animations/transitions with no @media (prefers-reduced-motion) — ignores the OS 'reduce motion' setting (WCAG 2.3.3)"})))}

   {:id :overflow-guard :title "No horizontal scroll" :weight 0.06
    :check (fn [s]
             (if (has? s #"(?i)overflow-x:\s*(clip|hidden)|overflow:\s*hidden")
               {:score 1.0}
               {:score 0.5 :finding "no overflow-x guard on html/body — a stray wide element causes sideways scroll on mobile"}))}

   {:id :color-scheme :title "Dark mode + theme-color" :weight 0.06
    :check (fn [s]
             (let [signals (cond-> 0
                             (has? s #"(?i)name=[\"']?theme-color[\"' >]") inc
                             (has? s #"(?i)prefers-color-scheme|color-scheme:") inc)]
               (case signals
                 2 {:score 1.0}
                 1 {:score 0.6 :finding "kotoba-ui.theme wires prefers-color-scheme dark overrides but no <meta name=theme-color> — the browser UI chrome (status bar / address bar) won't match the page in dark mode even though the page content does"}
                 0 {:score 0.0 :finding "no theme-color and no color-scheme — the browser UI and form controls won't match the page in dark mode"})))}

   {:id :responsive :title "Responsive breakpoints" :weight 0.07
    :check (fn [s]
             (if (has? s #"@media[^{]*max-width")
               {:score 1.0}
               {:score 0.0 :finding "no max-width media query — a single fixed layout for phone and desktop"}))}

   {:id :semantics :title "Document semantics (lang + charset)" :weight 0.05
    :check (fn [s]
             (let [ok (cond-> 0 (has? s #"(?i)<html[^>]*\blang=") inc (has? s #"(?i)charset=") inc)]
               {:score (/ ok 2.0)
                :finding (when (< ok 2) "missing <html lang> or <meta charset> — hurts screen readers / encoding")}))}])

(def total-weight (reduce + (map :weight axes)))   ;; normalize by this, not a bare mean

(defn score-page
  "Score one page's source string against every axis → {:overall 0..100 :axes [...]}.
   Trusts `src` to already be the COMPLETE page (all CSS/JS that matters inlined) — if
   the real page splits CSS into an externally-linked stylesheet, scoring the HTML alone
   silently undercounts every CSS-dependent axis (found the hard way auditing
   kami-creative-studio in iteration 03: scoring index.html alone gave 67.72/100 with a
   wrong finding set; concatenating the real public/css/ui.css gave the true 66.59/100
   with `overflow-guard`/`responsive` correctly passing and a real `reduced-motion` gap
   surfacing that the html-only pass never saw). Prefer `score-site` when the page might
   have external assets — it assembles them for you and flags when it can't be sure."
  [src]
  (let [results (mapv (fn [{:keys [id title weight check]}]
                        (let [{:keys [score finding]} (check src)]
                          {:id id :title title :weight weight
                           :score (double score) :finding finding}))
                      axes)
        weighted (reduce + (map (fn [a] (* (:score a) (:weight a))) results))]
    {:overall (* 100.0 (/ weighted total-weight))
     :axes results}))

(defn external-stylesheet-hrefs
  "hrefs of every `<link rel=stylesheet href=...>` in an HTML source string — the set of
   external CSS this page depends on that `score-page` on the HTML alone will NOT see.
   Matches both quoted (href=\"x\") and bare (href=x) attribute values — real generated
   HTML is quoted, but this stays defensive against hand-written markup that isn't."
  [html]
  (->> (re-seq #"(?is)<link\b[^>]*>" html)
       (filter #(re-find #"(?i)rel\s*=\s*[\"']?stylesheet" %))
       (keep #(when-let [m (re-find #"(?i)href\s*=\s*(?:\"([^\"]+)\"|'([^']+)'|([^\s>]+))" %)]
                (some identity (rest m))))
       distinct
       vec))

(defn assemble-site
  "Build one complete-page source string from a page's parts, so CSS-dependent axes see
   the REAL styling regardless of how the build splits it across files. `parts` is
   {:html <string, required> :css <string or [string...]> :js <string or [string...],
   currently unused by any axis but accepted for forward-compat}. Any external
   `<link rel=stylesheet>` in :html that isn't accounted for by a non-empty :css is a
   real, silent risk of the exact undercount iteration 03 found — `score-site` (not this
   fn) is where that gets flagged; this fn just does the concatenation."
  [{:keys [html css]}]
  (let [css-strs (cond (nil? css) [] (string? css) [css] :else (vec css))]
    (str html (when (seq css-strs) (str "<style>" (str/join "\n" css-strs) "</style>")))))

(defn score-site
  "Like `score-page`, but takes a page's parts (see `assemble-site`) instead of a single
   pre-assembled string, and — critically — tells you when it can't be confident the
   score is complete: if the HTML references an external stylesheet and no :css was
   supplied to cover it, `:incomplete?` is true and `:warning` names exactly which hrefs
   are unaccounted for, instead of silently scoring the HTML-only shell as if it were the
   whole page. A bare string is also accepted (treated as `{:html s}`) for callers who
   are certain the string is already complete."
  [parts]
  (let [parts (if (string? parts) {:html parts} parts)
        hrefs (external-stylesheet-hrefs (:html parts))
        css-supplied? (boolean (seq (let [c (:css parts)] (cond (nil? c) nil (string? c) [c] :else c))))
        incomplete? (and (seq hrefs) (not css-supplied?))
        report (score-page (assemble-site parts))]
    (cond-> report
      incomplete?
      (assoc :incomplete? true
             :warning (str "HTML references external stylesheet(s) " (str/join ", " hrefs)
                            " but no :css was supplied to score-site — every CSS-dependent axis "
                            "below may be undercounted (scored against the HTML shell alone). "
                            "Pass the real stylesheet content(s) via :css.")))))

(defn audit
  "Audit many pages: `pages` is {page-name -> source-string-or-parts-map} (see `score-site`
   for the parts-map shape — a plain string is scored as-is via `score-page`, unchanged).
   Returns {:overall mean :pages {name -> page-report} :findings [...] :incomplete [names]}
   — findings sorted heaviest-headroom first (the hypothesis seed list); :incomplete lists
   any page score-site flagged as possibly undercounted, so a caller never has to notice
   that silently (ADR-2607132300 iteration 03)."
  [pages]
  (let [reports (into {} (map (fn [[k v]] [k (score-site v)]) pages))
        overall (if (empty? reports) 0.0
                    (/ (reduce + (map :overall (vals reports))) (count reports)))
        findings (->> axes
                      (keep (fn [{:keys [id weight]}]
                              (let [hits (keep (fn [[pg rep]]
                                                 (let [a (first (filter #(= (:id %) id) (:axes rep)))]
                                                   (when (:finding a) [pg (:finding a) (:score a)])))
                                               reports)]
                                (when (seq hits)
                                  {:axis id :weight weight
                                   :pages (mapv first hits)
                                   :finding (second (first hits))
                                   :worst-score (reduce min (map #(nth % 2) hits))
                                   :headroom (* weight (- (count hits) (reduce + (map #(nth % 2) hits))))}))))
                      (sort-by :headroom >)
                      vec)
        incomplete (->> reports (keep (fn [[pg rep]] (when (:incomplete? rep) pg))) vec)]
    {:overall overall :pages reports :findings findings :incomplete incomplete}))

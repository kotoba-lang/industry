#!/usr/bin/env nbb
;; scripts/verify-appview-page-summary.cljs — does the landing page tell the truth
;; about the worker it is served by?
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-appview-page-summary.cljs \
;;     [--root <orgs-bearing checkout>] [--findings]
;;
;; ## What it compares
;;
;; The generated appviews embed a summary object at the top of
;; `svelte/src/routes/+page.svelte` and RENDER it — a `Routes` figure, a
;; "Public Routes" list, a "Runtime Bindings" list, each with an `{:else}` branch.
;; The two `{:else}` sentences name the wrangler config explicitly:
;;
;;   No public route is declared next to this app surface.
;;   No public vars are declared in the nearest wrangler config.
;;
;; So the page claims to report what wrangler says, and can be checked against it.
;; The nearest `wrangler.jsonc` is the authority; nothing here needs a judgement
;; call about which of several declarations is canonical.
;;
;; ## Why (measured 2026-08-16)
;;
;; Found while writing an operator quickstart for cloud-itonami/app-air-sched, then
;; asked whether it was one repository's slip. It is not: of 166 pages that carry the
;; summary and have a parsable wrangler beside them, **160 disagree about routeCount**
;; — almost all claiming 0 while wrangler declares 2 — **163 disagree about how many
;; vars exist**, and **163 still carry a `relativePath` pointing into the monorepo the
;; repository was extracted from**. So 160 live surfaces tell a visitor they have no
;; public route, at an address wrangler declares, while serving that very page there.
;;
;; There is no generator for the object in these repositories or in the root's
;; `scripts/`, so it is hand-maintained: change routes or vars in wrangler and the
;; page does not follow.
;;
;; **This is the class of defect the maturity ranking can no longer surface.** Those
;; repositories had `axis-docs` raised by operator quickstarts, so they have left the
;; top of the ranking with the page still lying. Leaving it to the ranking is leaving
;; it, which is the reason this exists as a detector rather than as another round.
;;
;; It cannot be a fleet gate: it reads `orgs/`, and a gate ships one repository's
;; tree, so on a node it would find an empty workspace and never go green
;; (ADR-2608124800).
;;
;; ## Only repositories the fleet manages
;;
;; A path under `orgs/` is not proof the fleet owns it. Measured 2026-08-16 on the
;; first run of this detector: of 157 repositories it named, **42 are absent from
;; `manifest/west.yml`** — nine of them `etzhayyim/com-etzhayyim-app-air-*`, the
;; pre-rename checkouts of repositories that now live at `cloud-itonami/app-air-*`
;; and are registered under the new path. They are orphaned directories left on this
;; disk after an org move (the same hazard `west-pin-advance` warns about for
;; unshallowing). Reporting them inflates the count and sends a reader to a
;; repository nobody would push.
;;
;; So the manifest is the roster: a page is examined only when its repository has a
;; `path:` line in `manifest/west.yml`, and the number excluded for that reason is
;; printed on every run — an exclusion nobody can see is one nobody can question.
;;
;; ## Fail-closed
;;
;; Exit 2 CANNOT ANSWER when fewer than `floor-pages` pages are examined, because
;; "no page disagrees" and "no pages were found" otherwise print the same zero.
;; Pages skipped for a readable reason are counted and printed, never dropped in
;; silence.
;;
;; Exit codes: 0 clean · 1 findings · 2 COULD NOT ANSWER.

(ns verify-appview-page-summary
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

(def args (vec (drop 2 (js->clj js/process.argv))))
(def findings-mode? (boolean (some #{"--findings"} args)))
(def root (or (second (drop-while #(not= "--root" %) args)) (.cwd js/process)))

;; The floor is on the INPUT, not on what survives filtering.
;;
;; Measured 2026-08-16: 225 `+page.svelte` files exist under orgs/, of which 122 are
;; in a west-registered repository and carry the summary. A run that finds materially
;; fewer candidates has lost its input — an un-populated orgs/, a wrong --root.
;;
;; **Flooring the examined count instead would punish the fix.** Deleting the dead
;; summary object is a legitimate repair, and it removes that page from `examined`; at
;; 122 examined against a floor of 120, two such repairs would have made the detector
;; answer CANNOT ANSWER about a fleet that had just improved. Candidates only fall when
;; the input is actually missing, which is the thing worth refusing on.
(def floor-candidates 150)

(def findings (atom []))
(defn finding! [sev k detail] (swap! findings conj [sev k detail]))
(defn- die [code msg] (println msg) (js/process.exit code))

(def west-paths
  "The `path:` lines of manifest/west.yml — the fleet's roster. Empty when the file
  cannot be read, which the caller turns into CANNOT ANSWER rather than into
  `nothing is registered`."
  (let [p (.join (js/require "node:path") root "manifest" "west.yml")
        s (try (str (fs/readFileSync p "utf8")) (catch :default _ nil))]
    (if s
      (into #{} (map second) (re-seq #"path:\s*(orgs/\S+)" s))
      #{})))

(defn- read-file [p]
  (try (str (fs/readFileSync p "utf8")) (catch :default _ nil)))

(defn- dir-entries [p]
  (try (vec (js->clj (fs/readdirSync p))) (catch :default _ [])))

(defn- exists? [p] (try (fs/existsSync p) (catch :default _ false)))

(defn- pages
  "Every `svelte/src/routes/+page.svelte` under orgs/<org>/<repo>, whether the
  svelte app is at the repository root or nested one level under `appview/`."
  []
  (let [orgs-dir (path/join root "orgs")]
    (vec
     (for [org (dir-entries orgs-dir)
           repo (dir-entries (path/join orgs-dir org))
           base (concat [(path/join orgs-dir org repo)]
                        (let [av (path/join orgs-dir org repo "appview")]
                          (map #(path/join av %) (dir-entries av))))
           :let [pg (path/join base "svelte" "src" "routes" "+page.svelte")]
           :when (exists? pg)]
       {:page pg
        :repo (str "orgs/" org "/" repo)
        :base base}))))

(defn- nearest-wrangler
  "wrangler.jsonc / .json / .toml at `base` or above, stopping before `orgs/`."
  [base]
  (loop [d base n 0]
    (when (and d (< n 4) (not= (path/basename d) "orgs"))
      (or (first (for [nm ["wrangler.jsonc" "wrangler.json" "wrangler.toml"]
                       :let [p (path/join d nm)]
                       :when (exists? p)]
                   p))
          (recur (path/dirname d) (inc n))))))

(defn- strip-jsonc
  "wrangler.jsonc allows // comments. Removes them only when the // is not inside
  a string, which matters because the vars carry URLs (https://…)."
  [s]
  (str/join "\n"
            (for [line (str/split-lines s)]
              (loop [i 0 in-str? false esc? false]
                (cond
                  (>= i (count line)) line
                  esc? (recur (inc i) in-str? false)
                  (= \\ (nth line i)) (recur (inc i) in-str? true)
                  (= \" (nth line i)) (recur (inc i) (not in-str?) false)
                  (and (not in-str?) (= "//" (subs line i (min (count line) (+ i 2)))))
                  (subs line 0 i)
                  :else (recur (inc i) in-str? false))))))

(defn- parse-config [p]
  (when p
    (when-not (str/ends-with? p ".toml")
      (when-let [s (read-file p)]
        (try (js->clj (js/JSON.parse (strip-jsonc s)) :keywordize-keys false)
             (catch :default _ nil))))))

(defn- summary-fields
  "The three fields this checks, read out of the page's embedded object. nil for a
  field the page does not carry — absent is not zero."
  [s]
  {:route-count (some-> (re-find #"\"routeCount\":\s*(\d+)" s) second js/parseInt)
   :var-count   (some-> (re-find #"(?s)\"vars\":\s*\[(.*?)\]" s) second
                        (#(count (re-seq #"\"[^\"]+\"" %))))
   :rel-path    (some-> (re-find #"\"relativePath\":\s*\"([^\"]*)\"" s) second)})

(let [ps (pages)]
  (when (empty? ps)
    (die 2 (str "CANNOT ANSWER: no svelte/src/routes/+page.svelte under "
                (path/join root "orgs") ". Wrong --root, or orgs/ is not populated.")))
  (when (empty? west-paths)
    (die 2 (str "CANNOT ANSWER: could not read any `path:` line from "
                (path/join root "manifest" "west.yml") ". Without the roster this"
                " cannot tell a managed repository from a leftover directory, and"
                " `nothing is registered` must not be reported as `all clean`.")))
  (let [rows (atom []) no-summary (atom 0) no-config (atom 0) unparsable (atom 0)
        unregistered (atom 0)]
    (doseq [{:keys [page repo base]} ps]
      (let [s (read-file page)]
        (cond
          (not (contains? west-paths repo)) (swap! unregistered inc)
          (or (nil? s) (not (str/includes? s "routeCount"))) (swap! no-summary inc)
          :else
          (let [w (nearest-wrangler base)
                cfg (parse-config w)]
            (cond
              (nil? w) (swap! no-config inc)
              (nil? cfg) (swap! unparsable inc)
              :else
              (let [f (summary-fields s)
                    wr (count (get cfg "routes" []))
                    wv (count (get cfg "vars" {}))]
                (swap! rows conj repo)
                (when (and (:route-count f) (not= (:route-count f) wr))
                  (finding! "STALE" (str "page-summary-route-count:" repo)
                            (str "the page renders " (:route-count f)
                                 " route(s) while " (path/basename w) " declares " wr
                                 (when (zero? (:route-count f))
                                   " -- so it prints \"No public route is declared next to this app surface\" at an address wrangler declares"))))
                (when (and (:var-count f) (not= (:var-count f) wv))
                  (finding! "STALE" (str "page-summary-var-count:" repo)
                            (str "the page lists " (:var-count f) " var(s) while "
                                 (path/basename w) " declares " wv)))
                (when (and (:rel-path f) (re-find #"^\d\d-" (:rel-path f)))
                  (finding! "STALE" (str "page-summary-source-path:" repo)
                            (str "relativePath still points into the source monorepo: "
                                 (:rel-path f))))))))))
    (let [n (count @rows)]
      ;; The skipped counts are printed whether or not anything was skipped. A run
      ;; that quietly examined half its input reads exactly like a clean one.
      (println (str "SCANNED\t" n "\tappview-page-summary"))
      (println (str "candidates=" (count ps)
                    " examined=" n
                    " skipped: not-in-west=" @unregistered
                    " no-summary=" @no-summary
                    " no-wrangler=" @no-config
                    " unparsable-or-toml=" @unparsable))
      (when (< (count ps) floor-candidates)
        (die 2 (str "CANNOT ANSWER: found " (count ps) " candidate pages, floor "
                    floor-candidates ". Reporting `no page disagrees` from almost no"
                    " input is the failure this floor exists to prevent. (The floor is"
                    " on candidates, not on `examined`: repairing a page can remove it"
                    " from `examined`, and a check must not report CANNOT ANSWER"
                    " because the fleet improved.)")))
      (if findings-mode?
        (doseq [[sev k detail] @findings]
          (println (str "FINDING\t" sev "\t" k "\t" detail)))
        (do (doseq [[sev k detail] @findings]
              (println (str sev " " k " -- " detail)))
            (println (str (count @findings) " finding(s) over " n " pages"))))
      (js/process.exit (if (seq @findings) 1 0)))))

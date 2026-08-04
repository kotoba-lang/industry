;; lei_catalog.cljs — the cloud-itonami-lei catalog, read from its SOURCE OF
;; TRUTH: the EDN in git. No SQL anywhere in this namespace.
;;
;; ## Why this exists (2026-08-04, owner direction「なぜ sql ? edn で datalad 保存では?」)
;;
;; The work list — "which companies still have no phone / no contact route?" —
;; used to be a `SELECT` against the D1 projection. That made a rebuildable
;; cache load-bearing for deciding what work to do. The catalog's正本 is
;; `cloud-itonami-lei-<LEI>/blueprint.edn` (+ `80-data/public/tos.journal.edn`),
;; so the work list is derived from those directly and D1 is out of this path.
;;
;; ## Why GitHub raw and not the local west checkout
;;
;; Because the writers write to GitHub. `lei-contact-discover` and
;; `lei-profile-enrich` update `blueprint.edn` through the Contents API, so the
;; local checkout — pinned to a west revision — does not contain what was just
;; written. Reading it would make the loop's after-measurement blind to its own
;; action, and the loop's whole premise is that the after-score is MEASURED,
;; never predicted. The local checkout is a cache; `main` on GitHub is the git
;; that "git is the source of truth" refers to.
;;
;; The cost is one HTTP GET per repo per read (~185 today, run concurrently,
;; under a minute — the same pass `d1-ingest-cloud-itonami-lei.cljs` makes).
;;
;; ## Completeness is reported, never assumed
;;
;; `fetch-catalog` returns the repos it could NOT read alongside the ones it
;; could. A work list silently computed over a partial catalog looks exactly
;; like a work list over a complete one, and the difference only shows up as
;; work that never gets done.
;;
;; Consumers must be run with `scripts` on the classpath:
;;   nbb --classpath scripts scripts/lei-contact-discover.cljs

(ns lei-catalog
  (:require ["child_process" :refer [execSync]]
            [clojure.string :as str]
            [cljs.reader :as edn]))

;; ── source: git (via GitHub) ────────────────────────────────────────────────

(defn list-repos
  "REST (`gh api .../repos`), not `gh repo list` (GraphQL): the GraphQL rate
  limit is shared and exhaustible session-wide, REST has its own much larger
  budget."
  []
  (->> (str/split-lines
        (execSync "gh api \"orgs/cloud-itonami/repos?per_page=100\" --paginate --jq '.[].name'"
                  #js {:encoding "utf8" :maxBuffer (* 20 1024 1024)}))
       (filter #(str/starts-with? % "cloud-itonami-lei-"))
       sort vec))

(defn- raw-url [repo file]
  (str "https://raw.githubusercontent.com/cloud-itonami/" repo "/main/" file))

(defn- fetch-text [url]
  (-> (js/fetch url)
      (.then (fn [^js r]
               (cond
                 (.-ok r) (.text r)
                 (= 404 (.-status r)) nil   ; absent file is data, not failure
                 :else (throw (js/Error. (str "HTTP " (.-status r) " " url))))))))

(defn journal->docs
  "`tos.journal.edn` is a flat vector of datoms, not a map, so group by doc-id
  and rebuild each document's map before reading fields off it."
  [journal-text]
  (when journal-text
    (->> (try (edn/read-string journal-text) (catch :default _ nil))
         (group-by first)
         vals
         (map (fn [datoms] (reduce (fn [m d] (assoc m (nth d 1) (nth d 2))) {} datoms)))
         (keep (fn [d]
                 (when-let [url (:tos/source-url d)]
                   {:source-url   url
                    :doc-type     (some-> (:tos/doc-type d) str)
                    :retrieved-at (:tos/retrieved-at d)
                    :sha256       (:tos/sha256 d)
                    :text-chars   (some-> (:tos/full-text d) count)
                    :lang         (:tos/lang d)})))
         vec)))

;; ── country normalisation ───────────────────────────────────────────────────

(def ^:private country-aliases
  "Free-text jurisdiction spellings seen in the wild, mapped to ISO 3166-1
  alpha-2. `blueprint.edn` is hand-written by whoever scaffolded the repo, so
  the same country arrives as `US`, `US-DE`, `United States` and
  `United States (Delaware)`. Left unnormalised, a plain group-by reports 53
  'jurisdictions' for what is really ~27 countries and makes the catalog's
  geography look far broader than it is.

  Normalised on READ, not by rewriting 185 blueprints: git is the source of
  truth, the raw string stays verbatim in it, so a subdivision (US-DE vs US-TX)
  is still recoverable and a bad guess here is fixed by editing this table
  rather than by another mass edit."
  {"united states" "US" "united states of america" "US" "usa" "US"
   "france" "FR" "germany" "DE" "japan" "JP" "new zealand" "NZ"
   "united kingdom" "GB" "great britain" "GB" "england" "GB"
   "cayman islands" "KY" "bermuda" "BM" "singapore" "SG" "canada" "CA"
   "australia" "AU" "switzerland" "CH" "netherlands" "NL" "italy" "IT"
   "ireland" "IE" "hong kong" "HK" "south korea" "KR" "korea" "KR"
   "brazil" "BR" "south africa" "ZA" "nigeria" "NG" "algeria" "DZ"
   "ethiopia" "ET" "zambia" "ZM" "liberia" "LR" "malaysia" "MY"
   "marshall islands" "MH" "guernsey" "GG"})

(defn ->country
  "ISO 3166-1 alpha-2 for a raw jurisdiction string, or nil if it cannot be
  determined. Returns nil rather than guessing: an unknown value left blank is
  visibly incomplete, whereas a wrong country silently corrupts every
  geographic rollup built on it."
  [j]
  (when (string? j)
    (let [s (str/trim j)
          bare (str/trim (str/replace s #"\(.*?\)" ""))
          lower (str/lower-case bare)]
      (cond
        (re-matches #"[A-Z]{2}" bare) bare
        (re-matches #"[A-Z]{2}-[A-Z0-9]{1,3}" bare) (subs bare 0 2)
        (contains? country-aliases lower) (get country-aliases lower)
        :else nil))))

;; ── the catalog ─────────────────────────────────────────────────────────────

(defn- run-bounded [items f n]
  (let [q (atom (vec items)) out (atom [])
        worker (fn worker []
                 (if-let [it (first @q)]
                   (do (swap! q rest)
                       (-> (f it) (.then (fn [r] (swap! out conj r) (worker)))))
                   (js/Promise.resolve nil)))]
    (-> (js/Promise.all (clj->js (repeatedly (min n (max 1 (count items))) worker)))
        (.then (fn [_] @out)))))

(defn- fetch-one [repo want-docs?]
  (-> (js/Promise.all
       #js [(fetch-text (raw-url repo "blueprint.edn"))
            (if want-docs? (fetch-text (raw-url repo "80-data/public/tos.journal.edn"))
                (js/Promise.resolve nil))])
      (.then (fn [[bp-text journal]]
               (let [bp (try (edn/read-string bp-text) (catch :default _ nil))]
                 (if-not (:company/lei bp)
                   {:repo repo :ok false :error "blueprint.edn missing or has no :company/lei"}
                   {:repo repo :ok true
                    :company (assoc bp :company/repo repo
                                    :company/country (->country (:company/jurisdiction bp)))
                    :docs (or (journal->docs journal) [])}))))
      (.catch (fn [e] {:repo repo :ok false :error (.-message e)}))))

(defn fetch-catalog
  "Every cloud-itonami-lei-* repo's blueprint (and optionally its ToS journal),
  read from git. Returns {:companies [...] :docs {repo [...]} :unreadable [...]}.

  `:unreadable` is returned, not logged and dropped: a caller that computes a
  work list over a partial catalog and does not say so has produced a number
  nobody can check."
  ([] (fetch-catalog {}))
  ([{:keys [concurrency docs?] :or {concurrency 12 docs? false}}]
   (let [repos (list-repos)]
     (-> (run-bounded repos #(fetch-one % docs?) concurrency)
         (.then (fn [rs]
                  {:repos-seen (count repos)
                   :companies (vec (keep :company (filter :ok rs)))
                   :docs (into {} (keep (fn [r] (when (:ok r) [(:repo r) (:docs r)])) rs))
                   :unreadable (vec (keep (fn [r] (when-not (:ok r)
                                                    {:repo (:repo r) :error (:error r)}))
                                          rs))}))))))

;; ── derived views ───────────────────────────────────────────────────────────

(defn reachable?
  "Has a published route a human could actually use."
  [c]
  (boolean (or (:company/contact-email c) (:company/inquiry-form-url c))))

(defn observe
  "The metrics `catalog-maturity` scores, computed from the EDN. Provenance is
  counted strictly: a document needs BOTH a retrieval timestamp and a content
  hash, since either alone leaves the archived text uncheckable."
  [{:keys [companies docs repos-seen unreadable]}]
  (let [all-docs (mapcat val docs)]
    {:companies (count companies)
     :docs (count all-docs)
     :with-doc (count (filter (fn [[_ ds]] (seq ds)) docs))
     :docs-with-provenance (count (filter #(and (:retrieved-at %) (:sha256 %)) all-docs))
     :reachable (count (filter reachable? companies))
     :country-counts (->> companies (keep :company/country) frequencies)
     :repos-seen repos-seen
     :unreadable (count unreadable)}))

(defn needing-contact-route
  "Work list for lei-contact-discover: a site to visit and no published route."
  [companies]
  (->> companies
       (filter #(and (:company/website %) (not (reachable? %))))
       (sort-by :company/lei)
       vec))

(defn needing-profile
  "Work list for lei-profile-enrich: a site to visit and neither a switchboard
  number nor a registered address recorded yet."
  [companies]
  (->> companies
       (filter #(and (:company/website %)
                     (nil? (:company/phone %))
                     (nil? (:company/postal-address %))))
       (sort-by :company/lei)
       vec))

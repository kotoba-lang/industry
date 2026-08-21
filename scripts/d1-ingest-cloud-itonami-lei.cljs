#!/usr/bin/env nbb
;; d1-ingest-cloud-itonami-lei.cljs — load every public `cloud-itonami-lei-<LEI>`
;; repo (blueprint.edn + 80-data/public/tos.journal.edn, the git-authoritative
;; source) into the Cloudflare D1 database `cloud-itonami-lei-catalog`.
;;
;; WHY D1 (2026-07-25, owner direction: "kotobase がまだ安定していないので, 今は
;; d1 を使う前提でok"): the kotobase graph plane is not dependable yet. Its head
;; record lives on B2, which has no conditional-write primitive, so the write
;; path cannot do a real compare-and-swap -- a background auto-fold silently
;; dropped transacts that had ALREADY returned ok to the client (ADR-2607252000
;; ledger seq 65; measured: 6 then 1 of 161 companies lost across two runs, only
;; caught because a separate completeness check compared the graph against the
;; GitHub repo listing). D1 is SQLite with real transactions, so "the write
;; succeeded" and "the row is durably there" are one statement, not two.
;;
;; This does NOT retire the kotobase ingest (scripts/kotobase-ingest-cloud-
;; itonami-lei.cljs). Git stays the source of truth and both targets are
;; derived, rebuildable projections of the same repos, so they can be
;; cross-checked against each other while kotobase stabilises.
;;
;; Run (from the superproject root):
;;   nbb scripts/d1-ingest-cloud-itonami-lei.cljs [--local]
;;
;; Idempotent: every write is an upsert on the natural key (LEI for a company,
;; LEI+source_url for a document), so re-running converges rather than
;; duplicating. Full document TEXT is deliberately NOT copied into D1 -- only
;; its provenance (url/sha256/retrieved-at/char count). The bytes already live
;; in git, and duplicating megabytes of scraped legal prose into a query plane
;; buys nothing that `:tos/sha256` plus the repo URL doesn't already give.

(ns d1-ingest-cloud-itonami-lei
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            ["child_process" :refer [execSync]]
            [clojure.string :as str]
            [cljs.reader :as edn]))

(def db-name "cloud-itonami-lei-catalog")
(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(def local? (some #(= % "--local") argv))
(def remote-flag (if local? "--local" "--remote"))

(defn- now-iso [] (.toISOString (js/Date.)))

;; ── source: GitHub ──────────────────────────────────────────────────────────

(defn list-repos
  "REST (`gh api .../repos`), not `gh repo list` (GraphQL): the GraphQL rate
  limit is shared and exhaustible session-wide, REST has its own much larger
  budget. Same call the kotobase ingest makes, so both targets enumerate an
  identical repo set and a divergence between them is real, not a listing
  artifact."
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

;; ── shaping ─────────────────────────────────────────────────────────────────

(defn journal->docs
  "`tos.journal.edn` is a flat vector of datoms, not a map, so group by doc-id
  and pivot each group into one row. Entries are FIVE-tuples
  `[doc-id :tos/attr value tx op]` -- an earlier version of this fn matched on
  `(= 3 (count %))`, which silently dropped every single entry and produced a
  clean-looking run with 161 companies and 0 documents. Match on `>= 3` and
  read the op positionally instead, so a journal that ever does emit bare
  triples still loads.

  `:retract` datoms are skipped rather than assoc'd: replaying a retraction as
  if it were an assertion would resurrect exactly the fact the journal recorded
  as withdrawn. Entries without a `:tos/source-url` are dropped -- source_url is
  half the table's primary key, so a row without one has no identity to upsert
  on."
  [journal]
  (when (sequential? journal)
    (->> journal
         (filter #(and (sequential? %) (>= (count %) 3)))
         (remove #(= :retract (nth (vec %) 4 nil)))
         (group-by first)
         (map (fn [[_doc-id datoms]]
                (reduce (fn [m d] (assoc m (nth d 1) (nth d 2))) {} datoms)))
         (keep (fn [d]
                 (when-let [url (:tos/source-url d)]
                   {:source-url   url
                    :doc-type     (some-> (:tos/doc-type d) str)
                    :retrieved-at (:tos/retrieved-at d)
                    :sha256       (:tos/sha256 d)
                    :text-chars   (some-> (:tos/full-text d) count)
                    :lang         (:tos/lang d)})))
         vec)))

;; ── SQL ─────────────────────────────────────────────────────────────────────

(defn sql-str
  "A SQL string literal, or bare NULL. Doubles single quotes (the only escape a
  SQLite string literal has) and drops NUL, which cannot appear in one at all.
  The NUL strip is spelled as a \\u0000 regex, never as a raw control byte in
  source: a literal NUL is invisible in a diff and one keystroke from being an
  ordinary space, which would silently strip every space out of every legal
  name in the catalog."
  [v]
  (if (or (nil? v) (and (string? v) (str/blank? v)))
    "NULL"
    (str "'" (-> (str v) (str/replace #"\u0000" "") (str/replace "'" "''")) "'")))

(defn sql-num [v] (if (number? v) (str v) "NULL"))

(def ^:private country-aliases
  "Free-text jurisdiction spellings seen in the wild, mapped to ISO 3166-1
  alpha-2. `blueprint.edn` is hand-written by whoever scaffolded the repo, so
  the same country arrives as `US`, `US-DE`, `United States` and
  `United States (Delaware)`. Left unnormalised, a plain
  `GROUP BY jurisdiction` reports 53 'jurisdictions' for what is really ~27
  countries and makes the catalog's geography look far broader than it is.

  Normalised in the PROJECTION, not by rewriting 161 blueprints: git is the
  source of truth and the raw `jurisdiction` string is kept verbatim beside
  this, so a subdivision (US-DE vs US-TX) is still recoverable and a bad guess
  here is fixable by re-running the ingest rather than by another mass edit."
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
  determined. Handles `XX-SUB` subdivision codes, bare alpha-2, and the
  free-text spellings above (including a trailing parenthetical such as
  `KY (Cayman Islands)` / `United States (Delaware)`). Returns nil rather than
  guessing: an unknown value left blank is visibly incomplete, whereas a
  wrong country silently corrupts every geographic rollup built on it."
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

(defn company-upsert
  "Projects blueprint.edn into the company row.

  ORGANISATION-level fields only. `:company/phone`, `:company/postal-address`
  and `:company/registration-number` (ADR-2608043000) identify the company, not
  a natural person, so they project here like every other public field. A
  representative's name never appears in blueprint.edn in the first place --
  `scripts/lei-profile-enrich.cljs` routes person-level facts to the private
  cloud-itonami-contact-pii dataset -- so there is nothing here to filter out.
  That is the design: the public projection cannot leak what the public source
  never held."
  [bp repo at]
  (str "INSERT INTO company (lei, legal_name, jurisdiction, website, ticker, isic_rev5, "
       "sector, reg_status, contact_email, contact_email_note, inquiry_form_url, "
       "phone, postal_address, registration_number, repo, country, ingested_at) VALUES ("
       (str/join ", " [(sql-str (:company/lei bp))
                       (sql-str (:company/legal-name bp))
                       (sql-str (:company/jurisdiction bp))
                       (sql-str (:company/website bp))
                       (sql-str (:company/ticker bp))
                       (sql-str (:company/isic-rev5 bp))
                       (sql-str (some-> (:company/sector bp) str))
                       (sql-str (:company/reg-status bp))
                       (sql-str (:company/contact-email bp))
                       (sql-str (:company/contact-email-note bp))
                       (sql-str (:company/inquiry-form-url bp))
                       (sql-str (:company/phone bp))
                       (sql-str (:company/postal-address bp))
                       (sql-str (:company/registration-number bp))
                       (sql-str (str "https://github.com/cloud-itonami/" repo))
                       (sql-str (->country (:company/jurisdiction bp)))
                       (sql-str at)])
       ") ON CONFLICT(lei) DO UPDATE SET "
       ;; Only overwrite with a non-NULL incoming value: a later scaffold that
       ;; drops a field from blueprint.edn must not silently erase a column an
       ;; earlier run legitimately populated.
       (str/join ", " (map #(str % " = COALESCE(excluded." % ", company." % ")")
                           ["legal_name" "jurisdiction" "website" "ticker" "isic_rev5"
                            "sector" "reg_status" "contact_email" "contact_email_note"
                            "inquiry_form_url" "phone" "postal_address"
                            "registration_number" "repo" "country"]))
       ", ingested_at = excluded.ingested_at;"))

(defn doc-upsert [lei d at]
  (str "INSERT INTO tos_doc (lei, source_url, doc_type, retrieved_at, sha256, text_chars, lang, ingested_at) VALUES ("
       (str/join ", " [(sql-str lei) (sql-str (:source-url d)) (sql-str (:doc-type d))
                       (sql-str (:retrieved-at d)) (sql-str (:sha256 d))
                       (sql-num (:text-chars d)) (sql-str (:lang d)) (sql-str at)])
       ") ON CONFLICT(lei, source_url) DO UPDATE SET "
       (str/join ", " (map #(str % " = COALESCE(excluded." % ", tos_doc." % ")")
                           ["doc_type" "retrieved_at" "sha256" "text_chars" "lang"]))
       ", ingested_at = excluded.ingested_at;"))

(defn- exec-sql!
  "Apply `statements` through `wrangler d1 execute --file`. A file, not
  --command: the whole point of moving off kotobase is that a write either
  lands or reports failure, and a batched file executes as one D1 call with one
  outcome instead of N independently-failing shell invocations."
  [statements label]
  (let [f (path/join (os/tmpdir) (str "d1-lei-" label ".sql"))]
    (fs/writeFileSync f (str/join "\n" statements))
    (try
      (execSync (str "npx wrangler d1 execute " db-name " " remote-flag " --file=" f " -y")
                #js {:encoding "utf8" :stdio "pipe" :maxBuffer (* 40 1024 1024)})
      {:ok true :n (count statements)}
      (catch :default e
        {:ok false :n (count statements)
         :error (str/join " | " (take 3 (str/split-lines (str (or (some-> (.-stderr e) str) (.-message e))))))})
      (finally (try (fs/unlinkSync f) (catch :default _ nil))))))

;; ── run ─────────────────────────────────────────────────────────────────────

(defn fetch-one [repo]
  (-> (js/Promise.all #js [(fetch-text (raw-url repo "blueprint.edn"))
                           (fetch-text (raw-url repo "80-data/public/tos.journal.edn"))])
      (.then (fn [^js pair]
               (let [bp (some-> (aget pair 0) edn/read-string)
                     journal (try (some-> (aget pair 1) edn/read-string)
                                  (catch :default _ nil))]
                 (if-not (:company/lei bp)
                   {:repo repo :ok false :error "blueprint.edn missing or has no :company/lei"}
                   {:repo repo :ok true :bp bp :docs (or (journal->docs journal) [])}))))
      (.catch (fn [e] {:repo repo :ok false :error (.-message e)}))))

(defn fetch-all
  "Bounded-concurrency fetch. GitHub raw tolerates this fine and it keeps a
  161-repo pass under a minute; the D1 write is a single batched call
  afterwards, so there is no write-side rate to pace against (unlike the
  kotobase ingest, which had to pace every individual transact)."
  [repos concurrency]
  (let [out (atom [])
        queue (atom (vec repos))
        worker (fn worker []
                 (if-let [repo (first @queue)]
                   (do (swap! queue subvec 1)
                       (-> (fetch-one repo)
                           (.then (fn [r] (swap! out conj r) (worker)))))
                   (js/Promise.resolve nil)))]
    (-> (js/Promise.all (clj->js (repeatedly (min concurrency (count repos)) worker)))
        (.then (fn [_] @out)))))

(defn -main []
  (let [started (now-iso)
        repos (list-repos)]
    (println "target:" db-name (if local? "(local)" "(remote)"))
    (println "repos:" (count repos))
    (-> (fetch-all repos 12)
        (.then
         (fn [results]
           (let [ok (filter :ok results)
                 failed (remove :ok results)
                 at (now-iso)
                 stmts (concat (map #(company-upsert (:bp %) (:repo %) at) ok)
                               (mapcat (fn [r] (map #(doc-upsert (get-in r [:bp :company/lei]) % at)
                                                    (:docs r)))
                                       ok))
                 doc-n (reduce + 0 (map #(count (:docs %)) ok))
                 ;; Chunked so one oversized batch cannot fail the whole load,
                 ;; and so a partial failure names which chunk to look at.
                 chunks (partition-all 60 stmts)
                 applied (doall (map-indexed (fn [i c] (exec-sql! c (str "b" i))) chunks))
                 bad (remove :ok applied)]
             (doseq [f failed] (println "FETCH-FAIL" (:repo f) "-" (:error f)))
             (doseq [b bad] (println "SQL-FAIL" (:error b)))
             (exec-sql! [(str "INSERT INTO ingest_run (started_at, finished_at, repos_seen, "
                              "companies_ok, companies_fail, docs_ok, note) VALUES ("
                              (str/join ", " [(sql-str started) (sql-str (now-iso))
                                              (sql-num (count repos)) (sql-num (count ok))
                                              (sql-num (count failed)) (sql-num doc-n)
                                              (sql-str (if (seq bad)
                                                         (str (count bad) " SQL chunk(s) failed")
                                                         "clean"))])
                              ");")]
                       "run")
             (println "=== SUMMARY ===")
             (println "repos:" (count repos)
                      " companies ok:" (count ok)
                      " fetch-failed:" (count failed)
                      " docs:" doc-n
                      " sql-chunks:" (count chunks) "failed:" (count bad))
             (when (or (seq failed) (seq bad))
               (set! (.-exitCode js/process) 1)))))
        (.catch (fn [e] (println "FATAL:" (.-message e)) (set! (.-exitCode js/process) 1))))))

(-main)

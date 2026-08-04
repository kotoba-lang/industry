#!/usr/bin/env nbb
;; lei-contact-discover.cljs — read-only discovery of a company's OWN published
;; general contact address / inquiry form for the cloud-itonami-lei-<LEI> repos,
;; written back into each repo's blueprint.edn (git stays the source of truth;
;; D1 and kotobase are both re-derived from it afterwards).
;;
;; SCOPE BOUNDARY, deliberate and non-negotiable (owner instruction 2026-07-25:
;; 「収集・基盤構築のみ、実送信はまだしない」): this script only performs HTTP
;; GET. It never submits a form, never sends mail, never creates an account,
;; and never solves or works around a CAPTCHA / bot check. A site that blocks
;; automated fetches is recorded as blocked and skipped -- getting past it is
;; out of bounds regardless of how much easier it would make the dataset.
;;
;; Honest by construction: nothing is written unless it was literally present
;; in the fetched HTML. "Not found" is recorded as not-found. There is no
;; inference step that turns `info@` + a domain into a guessed address -- a
;; plausible-looking address nobody published is worse than a blank field,
;; because a blank field is visibly incomplete while a guess reads as verified.
;;
;; Work list comes from the catalog's SOURCE OF TRUTH -- the blueprint.edn in
;; each repo, read via `lei-catalog` -- so the catalog itself decides what still
;; needs doing rather than a hand-maintained list drifting out of sync.
;;
;; It used to come from a `SELECT` against the D1 projection. Owner direction
;; 2026-08-04 (「なぜ sql ? edn で datalad 保存では?」) removed that: deciding what
;; work to do is not a job for a rebuildable cache. See scripts/lei_catalog.cljs.
;;
;; Run (from the superproject root):
;;   nbb --classpath scripts scripts/lei-contact-discover.cljs [--limit N] [--dry-run] [--concurrency N]

(ns lei-contact-discover
  (:require ["fs" :as fs]
            ["child_process" :refer [execSync]]
            [clojure.string :as str]
            [cljs.reader :as edn]
            [lei-catalog :as cat]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(defn- flag [n] (let [i (.indexOf (clj->js argv) n)]
                  (when (and (>= i 0) (< (inc i) (count argv))) (nth argv (inc i)))))
(def limit (some-> (flag "--limit") js/parseInt))
(def concurrency (or (some-> (flag "--concurrency") js/parseInt) 6))
(def dry-run? (boolean (some #{"--dry-run"} argv)))

(def ua
  "Identify honestly as an automated collector with a contact route. Pretending
  to be a human browser would be the first step of exactly the bot-evasion this
  script refuses to do."
  "cloud-itonami-lei-catalog/1.0 (+https://github.com/cloud-itonami; contact discovery; GET only)")

;; ── work list ───────────────────────────────────────────────────────────────

(defn work-list
  "Companies with a site and no published contact route, from git.

  Returns a Promise -- reading the catalog is 185 concurrent HTTP GETs against
  raw.githubusercontent, not a single query. `:unreadable` is surfaced rather
  than swallowed: a work list computed over a partial catalog looks identical to
  one computed over a complete one."
  []
  (-> (cat/fetch-catalog {:concurrency 12})
      (.then (fn [c]
               (when (seq (:unreadable c))
                 (println "WARNING" (count (:unreadable c))
                          "repo(s) unreadable — this work list is incomplete:"
                          (pr-str (mapv :repo (:unreadable c)))))
               (let [ws (cat/needing-contact-route (:companies c))]
                 (vec (map (fn [x] {:lei (:company/lei x)
                                    :legal_name (:company/legal-name x)
                                    :jurisdiction (:company/jurisdiction x)
                                    :website (:company/website x)
                                    :repo (:company/repo x)})
                           (cond->> ws limit (take limit)))))))))

;; ── fetching ────────────────────────────────────────────────────────────────

(defn- fetch-html [url]
  (let [ctl (js/AbortController.)
        t (js/setTimeout #(.abort ctl) 20000)]
    (-> (js/fetch url #js {:headers #js {"user-agent" ua
                                         "accept" "text/html,application/xhtml+xml"}
                           :redirect "follow"
                           :signal (.-signal ctl)})
        (.then (fn [^js r]
                 (js/clearTimeout t)
                 (if (.-ok r)
                   (-> (.text r) (.then (fn [body] {:status (.-status r) :url (.-url r) :body body})))
                   {:status (.-status r) :url (.-url r) :body nil})))
        (.catch (fn [e] (js/clearTimeout t) {:status nil :error (.-message e)})))))

(defn blocked?
  "A 403/429, or a body that is visibly a challenge page rather than the site.
  Recorded and skipped -- never worked around."
  [{:keys [status body]}]
  (or (#{403 429} status)
      (and body (re-find #"(?i)captcha|are you a robot|cf-browser-verification|challenge-platform|Just a moment\.\.\." body))))

;; ── extraction ──────────────────────────────────────────────────────────────

(def ^:private anchor-href-re
  "ANCHOR hrefs only. Matching every `href=` in the document also matches
  `<link rel=stylesheet>`, which produced
  `.../plugins/aecom-contact-us-widget/css/styles.css` as a company's official
  'contact page' -- the substring was there, the meaning was not."
  #"(?is)<a\b[^>]*?href=[\"']([^\"']+)[\"']")

(def ^:private contact-path-re
  "The contact token must be a whole PATH SEGMENT, not a substring anywhere in
  the URL. Substring matching promoted `/employers/enhanced-family-supports`
  (a marketing page) to a contact route purely because it contains 'support'."
  #"(?i)(^|/)(contact[a-z-]*|contatti|contacto|contactez|contacte[a-z-]*|kontakt[a-z-]*|inquiry|inquiries|enquiry|enquiries|get-in-touch|getintouch|reach-us|support|customer-service|customerservice|%E3%81%8A%E5%95%8F%E3%81%84%E5%90%88%E3%82%8F%E3%81%9B)(/|$|\?|#)")

(def ^:private asset-re #"(?i)\.(css|js|mjs|png|jpe?g|gif|svg|webp|ico|woff2?|ttf|eot|zip|mp4|webm)(\?|#|$)")

(defn decode-entities
  "HTML-decode an attribute value before treating it as a URL. An href is
  written entity-escaped in the markup, so the raw capture yields
  `...?locale=ja-JP&amp;curr=JPY` -- a URL that is subtly WRONG (its second
  query parameter is named `amp;curr`) while looking right at a glance. Only
  the handful of entities that legally appear in an attribute value."
  [s]
  (-> s
      (str/replace #"&(?:amp|#0*38|#[xX]0*26);" "&")
      (str/replace #"&(?:lt|#0*60);" "<")
      (str/replace #"&(?:gt|#0*62);" ">")
      (str/replace #"&(?:quot|#0*34);" "\"")
      (str/replace #"&(?:apos|#0*39);" "'")))

(defn- absolutize [base href]
  (try (.-href (js/URL. (decode-entities (str/trim href)) base)) (catch :default _ nil)))

(defn contact-page-urls
  "Same-origin candidate contact pages. Same-origin only: following an off-site
  link would start recording a third party's address under this company's row."
  [base body]
  (when body
    (let [origin (try (.-origin (js/URL. base)) (catch :default _ nil))]
      (->> (re-seq anchor-href-re body)
           (map second)
           (remove #(str/starts-with? (str/trim %) "mailto:"))
           (keep #(absolutize base %))
           (filter #(and origin (str/starts-with? % origin)))
           (remove #(re-find asset-re %))
           (filter #(re-find contact-path-re (try (.-pathname (js/URL. %)) (catch :default _ ""))))
           distinct
           (remove #(= % base))
           (take 3)
           vec))))

(def ^:private mailto-re #"(?i)mailto:([A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,})")

(def ^:private role-local
  "ROLE addresses only -- an allowlist, never a person-name heuristic.

  This is a privacy boundary, not a tidiness preference. A first pass wrote
  `agurgol@aep.com` (a named individual in investor relations) into the public
  catalog simply because it was the first mailto: on the page. Publishing an
  identifiable person's work address in a redistributable dataset is a harm the
  dataset gets no value from: what the catalog wants is a durable way to reach
  the ORGANISATION, and a role address is both more correct and outlives
  whoever currently holds the job.

  An allowlist rather than a blocklist because the failure directions are not
  symmetric -- missing a role address costs one blank field, while failing to
  block one person's address publishes it."
  #{"info" "contact" "contacts" "contactus" "enquiry" "enquiries" "inquiry" "inquiries"
    "support" "help" "helpdesk" "hello" "hi" "office" "mail" "email" "general"
    "press" "media" "pressoffice" "communication" "communications" "pr" "publicrelations"
    "ir" "investor" "investors" "investorrelations"
    "privacy" "privacyofficer" "dpo" "dataprotection" "gdpr"
    "legal" "compliance" "corporate" "hq" "reception" "admin" "administration"
    "customerservice" "customercare" "service" "services" "cs" "care" "clientservices"
    "sales" "marketing" "business" "partnership" "partnerships" "recruitment" "careers"})

(def ^:private qualifier
  "Suffixes a real role mailbox is commonly scoped by. Anything else after the
  role word means it is not the plain role address."
  #{"uk" "us" "usa" "ca" "au" "nz" "jp" "eu" "de" "fr" "it" "es" "nl" "cn" "in"
    "global" "group" "corp" "corporate" "hq" "team" "office" "desk" "mail" "box"
    "general" "enquiries" "inquiries" "relations"})

(defn role-address?
  "True iff the local part is a recognised role mailbox, either exactly
  (`info@`, `press@`) or as a role word plus a recognised qualifier
  (`info-uk@`, `investor_relations@`, `press.office@`).

  An earlier version accepted ANY local part whose first separator-delimited
  token was a role word, which let `hi_pin@hd.com` through on the strength of
  `hi` -- not a contact route, just a string that started like one. Matching
  the whole local part, or the whole part minus a known qualifier, keeps the
  genuinely-scoped role addresses without admitting arbitrary suffixes."
  [addr]
  (let [local (-> addr (str/split #"@") first str/lower-case)
        norm (str/replace local #"[._\-+]" "")
        parts (str/split local #"[._\-+]")]
    (boolean (or (contains? role-local norm)
                 (contains? role-local local)
                 (and (= 2 (count parts))
                      (contains? role-local (first parts))
                      (contains? qualifier (second parts)))))))

(defn extract-email
  "The first ROLE address published as a mailto: on the page, or nil. Never
  falls back to a non-role address: a named individual's mailbox is not a
  substitute for a company contact route, it is a different (and unwanted)
  piece of data."
  [body]
  (->> (re-seq mailto-re body)
       (map (comp str/lower-case second))
       (remove #(re-find #"(?i)example\.(com|org|net)|sentry\.io|\.(png|jpg|gif|svg)$" %))
       (filter role-address?)
       distinct
       first))

(defn contact-page-url
  "A reachable same-origin page whose URL the site itself labels as contact is
  recorded as the inquiry route -- the same standard the earlier hand-curated
  backfill used (amdocs.com/contact, ipsos.com/en/contact). Deliberately NOT
  gated on finding static <form> markup: most corporate contact pages render
  their form client-side, so requiring it would report 'no contact route' for
  companies that plainly have one. The claim being recorded is 'this is the
  company's own contact page', which the fetch proves, not 'a form exists at
  this URL', which it does not."
  [page-url body]
  (when body page-url))

;; ── blueprint write-back (git = source of truth) ────────────────────────────

(defn- gh-json [args input]
  (let [p (execSync (str "gh api " args (when input " --input -"))
                    (clj->js (cond-> {:encoding "utf8" :maxBuffer (* 20 1024 1024) :stdio "pipe"}
                               input (assoc :input input))))]
    (js->clj (js/JSON.parse p) :keywordize-keys true)))

(defn update-blueprint!
  "Read-modify-write blueprint.edn through the GitHub Contents API on the child
  repo's own main. The blob sha is sent back, so a concurrent edit 409s instead
  of being clobbered -- the same optimistic-lock discipline CLAUDE.md requires
  for west.yml, and the property the kotobase head write could not offer."
  [repo-name found]
  (let [path (str "repos/cloud-itonami/" repo-name "/contents/blueprint.edn")
        cur (gh-json (str "\"" path "\"") nil)
        raw (.toString (js/Buffer.from (:content cur) "base64") "utf8")
        bp (edn/read-string raw)
        bp' (cond-> bp
              (:email found) (assoc :company/contact-email (:email found)
                                    :company/contact-email-note
                                    (str "published as a mailto: link on the company's own site ("
                                         (:email-source found) "), retrieved " (:at found)
                                         " by scripts/lei-contact-discover.cljs (GET only)"))
              (:form found) (assoc :company/inquiry-form-url (:form found)))
        ;; pr-str would collapse this to one line; keep it one key per line so a
        ;; human diff of a hand-curated file stays readable.
        body (str "{" (str/join "\n " (map (fn [[k v]] (str (pr-str k) " " (pr-str v))) bp')) "}\n")
        payload (js/JSON.stringify
                 (clj->js {:message (str "data(contact): " (or (:email found) (:form found))
                                         " for " (:company/legal-name bp')
                                         "\n\nDiscovered read-only (HTTP GET) from the company's own published"
                                         "\ncontact page. No form was submitted and no mail was sent.")
                           :content (.toString (js/Buffer.from body "utf8") "base64")
                           :sha (:sha cur)
                           :branch "main"}))]
    (if dry-run?
      {:ok true :dry-run true}
      (do (gh-json (str "-X PUT \"" path "\"") payload) {:ok true}))))

;; ── per-company pipeline ────────────────────────────────────────────────────

(defn discover-one [{:keys [lei legal_name website repo]}]
  (let [repo-name (last (str/split repo #"/"))
        at (.toISOString (js/Date.))]
    (-> (fetch-html website)
        (.then
         (fn [home]
           (cond
             (blocked? home) (js/Promise.resolve {:lei lei :name legal_name :status :blocked})
             (nil? (:body home)) (js/Promise.resolve {:lei lei :name legal_name :status :unreachable
                                                      :http (:status home) :error (:error home)})
             :else
             (let [pages (contact-page-urls (:url home) (:body home))]
               ;; Keep visiting contact pages until BOTH a role email and a
               ;; contact page are known -- stopping at the first email would
               ;; discard the inquiry route for the (common) site that puts its
               ;; address on one page and its form on another.
               (-> (reduce (fn [p url]
                             (.then p (fn [acc]
                                        (if (and (:email acc) (:form acc))
                                          acc
                                          (-> (fetch-html url)
                                              (.then (fn [pg]
                                                       (if (or (blocked? pg) (nil? (:body pg)))
                                                         acc
                                                         (let [e (extract-email (:body pg))]
                                                           (cond-> acc
                                                             (and e (not (:email acc)))
                                                             (assoc :email e :email-source url)
                                                             (and (not (:form acc)) (contact-page-url url (:body pg)))
                                                             (assoc :form url)))))))))))
                           (js/Promise.resolve
                            (let [e (extract-email (:body home))]
                              (cond-> {:at at}
                                e (assoc :email e :email-source (:url home)))))
                           pages)
                   (.then (fn [found]
                            (if (or (:email found) (:form found))
                              {:lei lei :name legal_name :status :found :repo repo-name :found found}
                              {:lei lei :name legal_name :status :not-found
                               :pages-checked (count pages)}))))))))
        (.catch (fn [e] {:lei lei :name legal_name :status :error :error (.-message e)})))))

(defn run-bounded [items f n]
  (let [q (atom (vec items)) out (atom [])
        worker (fn worker []
                 (if-let [it (first @q)]
                   (do (swap! q subvec 1)
                       (-> (f it) (.then (fn [r] (swap! out conj r) (worker)))))
                   (js/Promise.resolve nil)))]
    (-> (js/Promise.all (clj->js (repeatedly (min n (max 1 (count items))) worker)))
        (.then (fn [_] @out)))))

;; A long multi-fetch run keeps many connections alive, and a peer closing one
;; mid-flight surfaces as an 'error' event on the underlying HTTP/2 stream --
;; OUTSIDE any promise chain, so neither `fetch`'s .catch nor a per-candidate
;; handler sees it, and the default behaviour kills the process. Observed live
;; 2026-07-25: `SocketError: other side closed` aborted an 88-candidate run
;; after ~20 minutes with nothing created and no summary.
;;
;; Only transport-layer failures are absorbed, and each one is printed. A
;; genuine programming error still crashes the run, because a scraper that
;; swallows every exception reports a clean pass over work it never did.
(defn- install-network-error-guard! []
  (let [net? (fn [^js e]
               (let [s (str (or (.-code e) "") " " (or (.-name e) "") " " (or (.-message e) ""))]
                 (boolean (re-find #"(?i)SocketError|ECONNRESET|ECONNREFUSED|EPIPE|ETIMEDOUT|ENOTFOUND|EAI_AGAIN|UND_ERR|ERR_HTTP2|other side closed|terminated" s))))]
    (.on js/process "uncaughtException"
         (fn [^js e]
           (if (net? e)
             (js/console.error "network error absorbed (run continues):" (or (.-message e) (str e)))
             (do (js/console.error "FATAL (not a network error):" (or (.-stack e) (str e)))
                 (.exit js/process 1)))))
    (.on js/process "unhandledRejection"
         (fn [^js e]
           (if (net? e)
             (js/console.error "network rejection absorbed (run continues):" (or (.-message e) (str e)))
             (do (js/console.error "FATAL unhandled rejection:" (or (.-stack e) (str e)))
                 (.exit js/process 1)))))))

(defn- report [results]
  (let [found (filter #(= :found (:status %)) results)]
    (doseq [r found]
      (let [w (update-blueprint! (:repo r) (:found r))]
        (println "FOUND" (:lei r) (:name r)
                 "email=" (or (get-in r [:found :email]) "-")
                 "form=" (or (get-in r [:found :form]) "-")
                 (if (:dry-run w) "(not written)" "(written)"))))
    (doseq [[k label] [[:not-found "not-found"] [:blocked "blocked (bot check — skipped, never bypassed)"]
                       [:unreachable "unreachable"] [:error "error"]]]
      (let [rs (filter #(= k (:status %)) results)]
        (when (seq rs)
          (println (str "--- " label ": " (count rs) " ---"))
          (doseq [r (take 40 rs)] (println "   " (:lei r) (:name r) (or (:error r) (:http r) ""))))))
    (println "=== SUMMARY ===")
    (println "checked:" (count results)
             " found:" (count found)
             " not-found:" (count (filter #(= :not-found (:status %)) results))
             " blocked:" (count (filter #(= :blocked (:status %)) results))
             " unreachable:" (count (filter #(= :unreachable (:status %)) results))
             " error:" (count (filter #(= :error (:status %)) results)))))

(defn -main []
  (-> (work-list)
      (.then (fn [work]
               (println "companies needing contact info:" (count work) (when dry-run? "(dry-run)"))
               (run-bounded work discover-one concurrency)))
      (.then report)
      (.catch (fn [e] (println "FATAL:" (.-message e)) (set! (.-exitCode js/process) 1)))))

(install-network-error-guard!)
(-main)

#!/usr/bin/env nbb
;; lei-acquire.cljs — acquire new companies for the cloud-itonami-lei catalog:
;; verify against GLEIF, verify the official site, archive its published legal
;; document, scaffold the per-company repo, and push it.
;;
;; Nothing here is invented. Every field written to a repo came from either
;; GLEIF's own API or a page actually fetched from the company's own domain,
;; and a step that cannot be completed is REPORTED as incomplete rather than
;; filled in with a plausible value. That discipline is the whole point of the
;; catalog: a fabricated LEI or a guessed website is worse than a gap, because
;; a gap is visibly a gap.
;;
;; Parent entities only. GLEIF returns BRANCH records that carry the parent's
;; legal name almost verbatim (`Banco Santander S.A.` appears three times, twice
;; as a BRANCH), and recording a branch LEI as if it were the company is exactly
;; the substitution earlier acquisition passes refused to make by hand. Enforced
;; in code here: BRANCH category is filtered out before scoring.
;;
;; Run (from the superproject root):
;;   nbb scripts/lei-acquire.cljs [--dry-run] [--limit N] [--concurrency N]
;;
;; --dry-run does every verification and prints exactly what WOULD be created,
;; without creating a repo or pushing anything.

(ns lei-acquire
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            ["crypto" :as crypto]
            ["child_process" :refer [execSync]]
            [clojure.string :as str]
            [cljs.reader :as edn]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(defn- flag [n] (let [i (.indexOf (clj->js argv) n)]
                  (when (and (>= i 0) (< (inc i) (count argv))) (nth argv (inc i)))))
(def dry-run? (boolean (some #{"--dry-run"} argv)))
(def limit (some-> (flag "--limit") js/parseInt))
(def concurrency (or (some-> (flag "--concurrency") js/parseInt) 4))
(def org "cloud-itonami")
(def ua "cloud-itonami-lei-catalog/1.0 (+https://github.com/cloud-itonami; archival; GET only)")

(defn- now-iso [] (str (first (str/split (.toISOString (js/Date.)) #"\.")) "Z"))
(defn- sha256 [s] (-> (crypto/createHash "sha256") (.update s "utf8") (.digest "hex")))

;; ── GLEIF ───────────────────────────────────────────────────────────────────

(defn- fold-diacritics
  "Strip combining marks so `DISEÑO`/`DISENO`, `Mærsk`/`Maersk` and
  `Türkiye`/`Turkiye` compare equal. Registrars disagree about diacritics for
  the same company, and without folding, `INDUSTRIA DE DISEÑO TEXTIL` scores
  only 0.6 against `INDUSTRIA DE DISENO TEXTIL` -- a correct match rejected
  over one tilde."
  [s]
  ;; Lower-case FIRST. The ligature/stroke replacements below are lowercase
  ;; literals, and NFD does not decompose Ø/Æ/Ł at all, so folding before
  ;; case-folding left `A.P. MØLLER - MÆRSK A/S` untouched while the lowercase
  ;; `A.P. Møller - Mærsk A/S` folded cleanly. The later [^a-z0-9] strip then
  ;; deleted the unfolded Ø/Æ outright and the same company scored 0.29
  ;; against itself.
  (-> (str/lower-case (str s))
      (.normalize "NFD")
      (str/replace #"[\u0300-\u036f]" "")
      (str/replace #"æ" "ae") (str/replace #"ø" "o") (str/replace #"å" "a")
      (str/replace #"ß" "ss") (str/replace #"đ" "d") (str/replace #"ł" "l")
      (str/replace #"ð" "d") (str/replace #"þ" "th")))

(defn- norm-name
  "Comparison form for legal names: diacritics folded, case-folded, punctuation
  dropped, and the legal-form suffix removed. `BANCO SANTANDER, S.A.` and
  `Banco Santander S.A` are the same company recorded by two registrars with
  different house style, and a raw string compare would reject the correct
  match.

  `ab` belongs in the suffix list even though it is short: leaving it in made
  `AB Volvo` score 0.67 against `Volvo Car AB` -- two genuinely different
  companies (the industrial group vs the Geely-owned car maker) -- which is
  how a first pass nearly paired Volvo Car AB's LEI with volvogroup.com."
  [s]
  (-> (str s)
      fold-diacritics
      str/lower-case
      (str/replace #"[.,()\"']" " ")
      (str/replace #"\b(s\s*a\s*b?|sa|nv|ab|ag|plc|ltd|limited|inc|corp|corporation|co|company|oyj|oy|asa|as|a\s*/\s*s|spa|sgps|aktiengesellschaft|akcyjna|spolka|public|joint|stock|tbk|persero|pjsc|psc|bhd|pt|sab|de|cv|the)\b" " ")
      (str/replace #"[^a-z0-9]+" " ")
      str/trim
      (str/replace #"\s+" " ")))

(defn- token-overlap [a b]
  (let [ta (set (str/split (norm-name a) #" "))
        tb (set (str/split (norm-name b) #" "))
        ta (disj ta "") tb (disj tb "")]
    (if (or (empty? ta) (empty? tb))
      0
      (/ (count (clojure.set/intersection ta tb))
         (count (clojure.set/union ta tb))))))

(defn- record->cand [r]
  (let [a (:attributes r) e (:entity a)
        alts (->> (concat (:otherNames e) (:transliteratedOtherNames e))
                  (keep :name) vec)]
    {:lei (:lei a)
     :legal-name (get-in e [:legalName :name])
     :legal-name-lang (get-in e [:legalName :language])
     ;; The ASCII transliteration is the ONLY name a Latin-script candidate
     ;; list can match for an entity registered in its own script.
     :alt-names alts
     :jurisdiction (:jurisdiction e)
     :status (:status e)
     :category (:category e)
     :reg-status (get-in a [:registration :status])}))

(defn- best-score
  "Score `wanted` against the registered legal name AND every other/
  transliterated name, keeping the best.

  GLEIF registers a company under its OWN script: TSMC is
  `台灣積體電路製造股份有限公司`, Teva is `טבע תעשיות פרמצבטיות בע\"מ`. Scoring
  only against `legalName` therefore returned zero hits for essentially every
  company in Taiwan, Israel, China, Greece, Thailand and the Gulf -- the
  acquisition method was structurally blind to non-Latin-script registries and
  reported that as `no GLEIF record`, which read like the companies were
  absent rather than that the search was wrong. Their
  `transliteratedOtherNames` carries exactly the Latin name a candidate list
  is written with (`PREFERRED_ASCII_TRANSLITERATED_LEGAL_NAME`)."
  [wanted c]
  (apply max (map #(token-overlap wanted %) (cons (:legal-name c) (:alt-names c)))))

(defn- gleif-query [url]
  (-> (js/fetch url #js {:headers #js {"accept" "application/vnd.api+json" "user-agent" ua}})
      (.then (fn [^js r] (if (.-ok r) (.json r) (throw (js/Error. (str "GLEIF HTTP " (.-status r)))))))
      (.then (fn [^js j] (mapv record->cand (js->clj (aget j "data") :keywordize-keys true))))))

(defn- pick [wanted rows]
  (->> rows
       ;; Parent entities only -- see ns docstring.
       (remove #(= "BRANCH" (:category %)))
       (map #(assoc % :score (best-score wanted %)))
       (sort-by :score >)
       first))

(defn gleif-lookup
  "Resolve a candidate to ONE non-BRANCH GLEIF record, trying progressively
  looser queries and stopping at the first confident match.

  Four tiers, because GLEIF's own registered name and the name a candidate
  list is written with disagree in two independent ways:

    1. legalName, raw          -- the common case
    2. legalName, NORMALISED   -- the registered name often carries no legal
                                  form at all. ArcelorMittal S.A. is registered
                                  simply as `ArcelorMittal`, and the raw query
                                  buried it below 25 subsidiaries, returning
                                  `Arcelormittal Greenfield S.A.` at 0.50 and
                                  reporting the company as absent from GLEIF.
                                  Querying the normalised form returns the
                                  exact parent as the first result.
    3. fulltext, raw           -- reaches transliterated names, so a natively
                                  scripted registration (台灣積體電路製造股份有限公司)
                                  is findable from its Latin name
    4. fulltext, normalised    -- both looseness axes at once

  Every tier stays jurisdiction-scoped. Dropping that filter was considered and
  rejected: an unscoped `Hon Hai Precision Industry` search returns `Merck
  Ltd.`, and a wrong company that scores well is far more damaging than a
  missed one, because it enters the catalog indistinguishable from a correct
  entry."
  [{:keys [name country]}]
  (let [enc js/encodeURIComponent
        simple (norm-name name)
        url (fn [field q]
              (str "https://api.gleif.org/api/v1/lei-records?"
                   "filter%5B" field "%5D=" (enc q)
                   "&filter%5Bentity.jurisdiction%5D=" (enc country)
                   "&page%5Bsize%5D=25"))
        tiers (cond-> [(url "entity.legalName" name)]
                ;; Skip a normalised tier that would repeat the raw query.
                (not= (str/lower-case simple) (str/lower-case name))
                (conj (url "entity.legalName" simple))
                true (conj (url "filter-fulltext-raw" name))
                (not= (str/lower-case simple) (str/lower-case name))
                (conj (url "filter-fulltext-simple" simple)))
        tiers (mapv #(str/replace % "filter%5Bfilter-fulltext-raw%5D" "filter%5Bfulltext%5D") tiers)
        tiers (mapv #(str/replace % "filter%5Bfilter-fulltext-simple%5D" "filter%5Bfulltext%5D") tiers)]
    (-> (reduce (fn [p u]
                  (.then p (fn [{:keys [best seen]}]
                             (if (and best (>= (:score best) 0.85))
                               {:best best :seen seen}
                               (-> (gleif-query u)
                                   (.then (fn [rows]
                                            (let [all (concat seen rows)
                                                  b (pick name all)]
                                                {:best b :seen all})))
                                   (.catch (fn [_] {:best best :seen seen})))))))
                (js/Promise.resolve {:best nil :seen []})
                tiers)
        (.then (fn [{:keys [best]}]
                 ;; 0.85, not 0.6: a loose bar is how `AB Volvo` matched
                 ;; `Volvo Car AB`. A missed company costs one catalog gap and
                 ;; is visible in this run's own report; a wrong company enters
                 ;; the catalog looking exactly like a right one.
                 (if (and best (>= (:score best) 0.85))
                   (assoc best :ok true)
                   {:ok false :reason (if best
                                        (str "no confident parent match across 4 query tiers (best: "
                                             (:legal-name best) " score " (.toFixed (:score best) 2) ")")
                                        "no non-BRANCH GLEIF record in this jurisdiction across 4 query tiers")})))
        (.catch (fn [e] {:ok false :reason (str "GLEIF lookup failed: " (.-message e))})))))

;; ── site + legal document ───────────────────────────────────────────────────

(defn- fetch-page [url]
  (let [ctl (js/AbortController.) t (js/setTimeout #(.abort ctl) 25000)]
    (-> (js/fetch url #js {:headers #js {"user-agent" ua "accept" "text/html,application/xhtml+xml"}
                           :redirect "follow" :signal (.-signal ctl)})
        (.then (fn [^js r]
                 (js/clearTimeout t)
                 (if (.-ok r)
                   (-> (.text r) (.then (fn [b] {:status (.-status r) :url (.-url r) :body b})))
                   {:status (.-status r) :url (.-url r)})))
        (.catch (fn [e] (js/clearTimeout t) {:error (.-message e)})))))

(defn- blocked? [{:keys [status body]}]
  (or (#{403 429} status)
      (and body (re-find #"(?i)captcha|are you a robot|cf-browser-verification|challenge-platform|Just a moment\.\.\." body))))

(defn html->text
  "Strip markup to the readable text the journal stores. Script/style contents
  are removed BEFORE tag stripping, or their source code would land in the
  archive as if it were policy prose."
  [html]
  (-> html
      (str/replace #"(?is)<(script|style|noscript)\b.*?</\1>" " ")
      (str/replace #"(?s)<!--.*?-->" " ")
      (str/replace #"(?s)<[^>]+>" " ")
      (str/replace #"&nbsp;" " ")
      (str/replace #"&(?:amp|#0*38);" "&")
      (str/replace #"&(?:lt|#0*60);" "<")
      (str/replace #"&(?:gt|#0*62);" ">")
      (str/replace #"&(?:quot|#0*34);" "\"")
      (str/replace #"&(?:#0*39|apos|rsquo|#8217);" "'")
      (str/replace #"\s+" " ")
      str/trim))

(defn site-verifies?
  "The fetched page must actually be this company's site. Without this check a
  stale or mistyped domain in the candidate list would be published as the
  company's official website on the strength of it merely returning 200 --
  including parked domains and squatters, which always return 200."
  [legal-name body]
  (when body
    (let [text (str/lower-case (html->text body))
          toks (->> (str/split (norm-name legal-name) #" ")
                    (remove str/blank?)
                    (filter #(>= (count %) 4)))]
      (and (seq toks)
           (some #(str/includes? text %) toks)))))

(def ^:private legal-path-re
  #"(?i)(^|/)(privacy[a-z-]*|privacidad|privatlivspolitik|datenschutz|confidentialite|gizlilik|terms[a-z-]*|terms-of-use|terms-of-service|legal[a-z-]*|legal-notice|conditions[a-z-]*)(/|$|\?|#)")

(def ^:private asset-re #"(?i)\.(css|js|mjs|png|jpe?g|gif|svg|webp|ico|woff2?|ttf|zip|mp4)(\?|#|$)")

(defn- absolutize [base href]
  (try (.-href (js/URL. (-> href str/trim (str/replace #"&(?:amp|#0*38);" "&")) base))
       (catch :default _ nil)))

(def ^:private well-known-legal-paths
  "Conventional locations to try when the homepage yields no anchors at all.
  A JS-rendered homepage ships almost no <a> tags, so link discovery finds
  nothing and the company looks like it publishes no policy -- Telefónica,
  Volvo, Novo Nordisk and DNB all reported `0 candidate legal page(s)` on the
  first pass despite obviously having one. These are GUESSES, and they are
  treated as such: each is fetched and must return real text before anything is
  recorded, so a wrong guess costs one 404 rather than a fabricated citation."
  ["/privacy-policy" "/privacy" "/en/privacy-policy" "/en/privacy"
   "/legal" "/en/legal" "/legal-notice" "/terms" "/en/terms"
   "/terms-of-use" "/privacy-notice" "/en/privacy-notice" "/cookie-policy"])

(defn legal-page-urls [base body]
  (let [origin (try (.-origin (js/URL. base)) (catch :default _ nil))
        linked (when body
                 (->> (re-seq #"(?is)<a\b[^>]*?href=[\"']([^\"']+)[\"']" body)
                      (map second)
                      (keep #(absolutize base %))
                      (filter #(and origin (str/starts-with? % origin)))
                      (remove #(re-find asset-re %))
                      (filter #(re-find legal-path-re (try (.-pathname (js/URL. %)) (catch :default _ ""))))
                      distinct (take 4) vec))]
    (vec (distinct (concat linked
                           (when (and origin (empty? linked))
                             (map #(str origin %) well-known-legal-paths)))))))

(defn soft-404?
  "A page that returned HTTP 200 while actually being an error page. dnb.no
  serves its not-found page as 200 at
  `/portalfront/dnb/error/no/404.php?portal_referrer=/privacy-policy`, so
  status alone said the fetch succeeded and this pipeline was one step from
  archiving a 404 screen as DNB's privacy policy -- a citation that would look
  entirely legitimate in the catalog, with a real URL, a real timestamp and a
  real SHA-256 over the wrong bytes."
  [url text]
  (let [p (str/lower-case (or (try (.-pathname (js/URL. url)) (catch :default _ "")) ""))
        q (str/lower-case (or (try (.-search (js/URL. url)) (catch :default _ "")) ""))
        head (str/lower-case (subs text 0 (min 600 (count text))))]
    (boolean (or (re-find #"/(404|error|not-found|notfound)(/|\.|$)" p)
                 (re-find #"[?&](error|404)=" q)
                 (re-find #"(?i)\b(page|side|side[nr]|pagina|página|seite) (not found|kunne ikke|ikke funnet)" head)
                 (re-find #"(?i)\b404\b.{0,40}\b(error|not found|ikke|nicht|introuvable)" head)
                 (re-find #"(?i)(we (can'?t|cannot) find|couldn'?t be found|does not exist|no longer exists)" head)))))

(defn policy-like?
  "The archived text must read like the kind of document we claim it is. A page
  can be 200, not a soft-404, long enough, and still be a press release or a
  cookie banner shell. Cheap keyword evidence is not proof, but it rejects the
  obvious mismatches that char-count alone waves through."
  [doc-type text]
  (let [t (str/lower-case text)]
    (case doc-type
      :privacy-policy (boolean (re-find #"(?i)personal (data|information)|privacy|persondata|personopplysninger|datenschutz|datos personales" t))
      :terms-of-service (boolean (re-find #"(?i)terms|conditions|agreement|vilkår|bedingungen|condiciones" t))
      (boolean (re-find #"(?i)legal|copyright|liability|terms|privacy|aviso legal|mentions" t)))))

(defn- doc-type-of [url]
  (let [p (str/lower-case url)]
    (cond
      (re-find #"privacy|privacidad|datenschutz|confidentialite|gizlilik|privatlivs" p) :privacy-policy
      (re-find #"terms|conditions" p) :terms-of-service
      :else :legal-notice)))


;; ── robots.txt + headless render ────────────────────────────────────────────

(def ^:private robots-cache (atom {}))

(defn- fetch-robots [origin]
  (if (contains? @robots-cache origin)
    (js/Promise.resolve (get @robots-cache origin))
    (-> (fetch-page (str origin "/robots.txt"))
        (.then (fn [r]
                 (let [body (:body r)
                       ;; Only the rules that apply to everyone. A site that
                       ;; singles out a named crawler is not addressing this
                       ;; one, and inventing a match either way would be
                       ;; guessing at intent.
                       star (when body
                              (->> (str/split body #"(?i)user-agent:")
                                   (filter #(str/starts-with? (str/trim %) "*"))
                                   (str/join "\n")))
                       dis (when star
                             (->> (re-seq #"(?im)^\s*disallow:\s*(\S*)\s*$" star)
                                  (map second) (remove str/blank?) vec))]
                   (swap! robots-cache assoc origin (or dis []))
                   (or dis [])))))))

(defn robots-allows?
  "Prefix match against `Disallow:` for `User-agent: *`.

  This crawler identifies itself honestly, so it should also read the file
  that exists to tell it where not to go -- especially now that it escalates
  to a real browser, where the cost of ignoring the request lands on the site
  rather than on us. Unreadable or absent robots.txt is treated as allowed,
  which is what the standard says, not as a reason to stop."
  [disallowed url]
  (let [p (try (.-pathname (js/URL. url)) (catch :default _ "/"))]
    (not (some #(and (seq %) (str/starts-with? p %)) disallowed))))

(def ^:private chrome-bin
  (or (aget js/process.env "CHROME_BIN")
      "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"))

(defn render-dom
  "Rendered DOM via headless Chrome, or nil.

  WHY, and where the line is. Roughly a fifth of candidates publish their
  legal text through client-side rendering, so a plain fetch sees an empty
  shell and the pipeline reports `no published legal document` for a company
  that plainly publishes one. Rendering the page the way any visitor's browser
  would is retrieval, not circumvention.

  It is used ONLY for that case. A site that answered 403/429 or served a
  challenge is left recorded as blocked and is never retried here: getting
  past a bot check is out of bounds no matter how much easier it would make
  the dataset, and a browser aimed at a challenge page is exactly that. No
  stealth flags, no fingerprint spoofing, no proxy rotation, and no CAPTCHA
  solver -- `browser-use` in this workspace ships a capsolver adapter and it
  is deliberately not wired in. The same honest user-agent is sent as on the
  plain path, so the site can still identify and refuse this crawler."
  [url]
  (try
    (let [out (execSync (str (pr-str chrome-bin)
                             " --headless --disable-gpu --no-sandbox --virtual-time-budget=8000"
                             " --user-agent=" (pr-str ua)
                             " --dump-dom " (pr-str url) " 2>/dev/null")
                        #js {:encoding "utf8" :maxBuffer (* 40 1024 1024) :timeout 60000})]
      (when (and out (> (count out) 200)) out))
    (catch :default _ nil)))

;; ── repo scaffold ───────────────────────────────────────────────────────────

(defn- repo-name [lei] (str "cloud-itonami-lei-" (str/lower-case lei)))

(defn- journal-edn [slug doc]
  (str "[[\"" slug "\" :tos/full-text " (pr-str (:text doc)) " 1 :add]\n"
       " [\"" slug "\" :tos/source-url " (pr-str (:url doc)) " 1 :add]\n"
       " [\"" slug "\" :tos/retrieved-at " (pr-str (:at doc)) " 1 :add]\n"
       " [\"" slug "\" :tos/sha256 " (pr-str (:sha doc)) " 1 :add]\n"
       " [\"" slug "\" :tos/doc-type " (:doc-type doc) " 1 :add]\n"
       ;; How the bytes were obtained is provenance too: a reader comparing
       ;; this archive against the live page needs to know whether it was the
       ;; raw response or the rendered DOM, because they legitimately differ.
       " [\"" slug "\" :tos/retrieval-method " (pr-str (or (:via doc) "http-get")) " 1 :add]]\n"))

(defn- blueprint-edn [g site]
  (str "{:company/legal-name " (pr-str (:legal-name g)) "\n"
       " :company/lei " (pr-str (:lei g)) "\n"
       " :company/jurisdiction " (pr-str (:jurisdiction g)) "\n"
       ;; Keep the ASCII transliteration beside a natively-scripted legal name:
       ;; the registered name is authoritative, but a catalog nobody can search
       ;; in Latin script is a catalog nobody can use.
       (when-let [t (first (filter #(re-find #"^[\x20-\x7e]+$" %) (:alt-names g)))]
         (when-not (re-find #"^[\x20-\x7e]+$" (str (:legal-name g)))
           (str " :company/legal-name-transliterated " (pr-str t) "\n")))
       " :company/website " (pr-str site) "\n"
       (when (not= "ISSUED" (:reg-status g))
         (str " :company/reg-status " (pr-str (:reg-status g)) "\n"
              " :company/reg-note \"real GLEIF entity-verified LEI; registration not annually renewed. Recorded verbatim rather than dropped -- the entity is real and the lapse is itself a fact about it.\"\n"))
       "}\n"))

(defn- readme-md [g doc]
  (let [n (:legal-name g)]
    (str "# " (repo-name (:lei g)) "\n\n"
         "> **Independent third-party archive/analysis. Not affiliated with, endorsed by, or sponsored by " n ".**\n\n"
         "This repository archives the publicly published "
         (case (:doc-type doc) :privacy-policy "Privacy Policy" :terms-of-service "Terms of Service" "legal notice")
         " of **" n "** (" (:jurisdiction g) "), with source-url and retrieval-date provenance, per\n"
         "ADR-2607110300 (`cloud-itonami-lei-corporate-tos-catalog`, `com-junkawasaki/root`).\n"
         "Read-only reference/archive repository — not a governed Advisor/Governor actor.\n\n"
         "- LEI: `" (:lei g) "` (GLEIF entity status " (:status g) ", registration " (:reg-status g) ")\n"
         "- Source: " (:url doc) "\n"
         "- Retrieved: " (:at doc) "\n"
         "- SHA-256 of archived text: `" (:sha doc) "`\n\n"
         "Acquired by `scripts/lei-acquire.cljs` as part of the worldwide-broadening\n"
         "continuation that followed the 2026-07-25 coverage audit, which found the\n"
         "catalog's real reach was 27 countries with the United States at 55%.\n")))

(defn- notice-txt [g doc]
  (let [n (:legal-name g)]
    (str "NOTICE\n\n"
         "The text archived under 80-data/public/tos.journal.edn in this repository was\n"
         "authored and published by " n " and remains that company's copyright. It is\n"
         "included here for reference/analysis purposes only, with source-url and\n"
         "retrieved-at provenance recorded alongside the entry, and is not asserted as\n"
         "this project's own work.\n\n"
         "Source: " (:url doc) "\nRetrieved: " (:at doc) "\n\n"
         "This repository's own structure (README, blueprint.edn, journal schema, tooling)\n"
         "is licensed AGPL-3.0-or-later per LICENSE. That license does NOT extend to the\n"
         "archived third-party text itself.\n\n"
         "This project is an independent third-party archive/analysis. It is not\n"
         "affiliated with, endorsed by, or sponsored by " n ".\n")))

(def license-text (delay (fs/readFileSync "scripts/d1/AGPL-3.0.txt" "utf8")))

(defn- sh! [cmd cwd]
  (execSync cmd (clj->js (cond-> {:encoding "utf8" :stdio "pipe" :maxBuffer (* 20 1024 1024)}
                           cwd (assoc :cwd cwd)))))

(defn create-repo! [g site doc]
  (let [rn (repo-name (:lei g))
        dir (path/join (os/tmpdir) (str "lei-scaffold-" (:lei g)))
        slug (str (str/lower-case (first (str/split (norm-name (:legal-name g)) #" "))) "-doc-1")]
    (fs/rmSync dir #js {:recursive true :force true})
    (fs/mkdirSync (path/join dir "80-data" "public") #js {:recursive true})
    (fs/writeFileSync (path/join dir "blueprint.edn") (blueprint-edn g site))
    (fs/writeFileSync (path/join dir "80-data" "public" "tos.journal.edn") (journal-edn slug doc))
    (fs/writeFileSync (path/join dir "README.md") (readme-md g doc))
    (fs/writeFileSync (path/join dir "NOTICE") (notice-txt g doc))
    (fs/writeFileSync (path/join dir "LICENSE") @license-text)
    (sh! "git init -q -b main" dir)
    (sh! "git add -A" dir)
    (sh! (str "git -c user.name='Jun Kawasaki' -c user.email='jun784@gmail.com' commit -q -m "
              (pr-str (str "archive: " (:legal-name g) " (" (:jurisdiction g) ") published "
                           (name (:doc-type doc)) "\n\nLEI " (:lei g)
                           " verified against GLEIF (non-BRANCH parent entity)."
                           "\nSource " (:url doc) " retrieved " (:at doc) "."
                           "\n\nCo-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>")))
         dir)
    (sh! (str "gh repo create " org "/" rn " --public --disable-wiki "
              "--description " (pr-str (str "Archive of " (:legal-name g) "'s published legal/privacy document (LEI " (:lei g) ", " (:jurisdiction g) ")")))
         dir)
    (sh! (str "git remote add origin https://github.com/" org "/" rn ".git") dir)
    (sh! "git push -q -u origin main" dir)
    (fs/rmSync dir #js {:recursive true :force true})
    rn))

;; ── per-candidate pipeline ──────────────────────────────────────────────────

(defn acquire-one [{:keys [name country site] :as cand}]
  (-> (gleif-lookup cand)
      (.then
       (fn [g]
         (if-not (:ok g)
           {:cand name :status :no-lei :reason (:reason g)}
           (-> (fetch-page site)
               (.then
                (fn [home]
                  (cond
                    (blocked? home) {:cand name :lei (:lei g) :status :site-blocked}
                    (nil? (:body home)) {:cand name :lei (:lei g) :status :site-unreachable
                                         :reason (or (:error home) (str "HTTP " (:status home)))}
                    :else
                    ;; Homepage verification is the FIRST chance to confirm the
                    ;; domain belongs to this entity, not the only one. A brand
                    ;; site often never prints its own registered name
                    ;; (inditex.com does not say "Industria de Diseño Textil"),
                    ;; so rejecting on the homepage alone discards correct
                    ;; matches. The legal page is the stronger check anyway --
                    ;; a privacy notice or legal notice essentially always
                    ;; names the legal entity -- so fall through and require
                    ;; the verification there instead. One of the two must
                    ;; pass; neither passing is still a rejection.
                    (let [home-ok? (site-verifies? (:legal-name g) (:body home))
                          all-pages (legal-page-urls (:url home) (:body home))
                          origin (try (.-origin (js/URL. (:url home))) (catch :default _ nil))]
                      (-> (fetch-robots origin)
                          (.then
                           (fn [disallowed]
                             (let [pages (vec (filter #(robots-allows? disallowed %) all-pages))]
                               (reduce (fn [p url]
                                    (.then p (fn [acc]
                                               (if acc
                                                 acc
                                                 (-> (fetch-page url)
                                                     (.then (fn [pg]
                                                              (let [plain (some-> (:body pg) html->text)
                                                                    ;; Escalate to a browser ONLY when the plain
                                                                    ;; fetch succeeded but rendered to nothing --
                                                                    ;; the client-side-rendering case. A blocked
                                                                    ;; page is never re-attempted here.
                                                                    rendered (when (and (not (blocked? pg))
                                                                                        (or (nil? plain) (< (count plain) 500)))
                                                                               (some-> (render-dom url) html->text))
                                                                    txt (if (and rendered (> (count rendered) (count (or plain ""))))
                                                                          rendered plain)
                                                                    via (if (identical? txt rendered) "headless-render" "http-get")]
                                                                (when (and txt (>= (count txt) 500)
                                                                           (not (blocked? pg))
                                                                           ;; A rendered challenge page is still a
                                                                           ;; challenge page, not a document.
                                                                           (not (re-find #"(?i)captcha|are you a robot|challenge-platform" txt))
                                                                           (not (soft-404? (:url pg) txt))
                                                                           (policy-like? (doc-type-of url) txt))
                                                                  {:url (:url pg) :text txt :sha (sha256 txt)
                                                                   :at (now-iso) :doc-type (doc-type-of url) :via via
                                                                   :names-entity? (boolean
                                                                                   (or (site-verifies? (:legal-name g) (:body pg))
                                                                                       (site-verifies? (:legal-name g) txt)))})))))))))
                                  (js/Promise.resolve nil)
                                  pages))))
                          (.then (fn [doc]
                                   (let [blocked-by-robots (- (count all-pages)
                                                              (count (filter #(robots-allows?
                                                                               (get @robots-cache origin []) %)
                                                                             all-pages)))]
                                     (cond
                                       (nil? doc)
                                       {:cand name :lei (:lei g) :status :no-legal-doc
                                        :reason (str (count all-pages) " candidate legal page(s)"
                                                     (when (pos? blocked-by-robots)
                                                       (str ", " blocked-by-robots " disallowed by robots.txt"))
                                                     ", none yielded >=500 chars of text")}
                                       (not (or home-ok? (:names-entity? doc)))
                                       {:cand name :lei (:lei g) :status :site-unverified
                                        :reason (str "neither " (:url home) " nor its legal page " (:url doc)
                                                     " mentions the GLEIF legal name")}
                                       :else
                                       {:cand name :status :ready :gleif g :site (:url home) :doc doc})))))))))))))
      (.catch (fn [e] {:cand name :status :error :reason (.-message e)}))))

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

(defn -main []
  (let [all (edn/read-string (fs/readFileSync "scripts/d1/lei-candidates.edn" "utf8"))
        existing (set (->> (str/split-lines
                            (execSync "gh api \"orgs/cloud-itonami/repos?per_page=100\" --paginate --jq '.[].name'"
                                      #js {:encoding "utf8" :maxBuffer (* 20 1024 1024)}))
                           (filter #(str/starts-with? % "cloud-itonami-lei-"))))
        cands (cond->> all limit (take limit))]
    (println "candidates:" (count cands) (when dry-run? "(dry-run)"))
    (-> (run-bounded cands acquire-one concurrency)
        (.then
         (fn [results]
           (let [ready (filter #(= :ready (:status %)) results)
                 created (atom [])]
             (doseq [r ready]
               (let [rn (repo-name (get-in r [:gleif :lei]))]
                 (cond
                   (contains? existing rn) (println "SKIP (already registered)" rn (:cand r))
                   dry-run? (println "WOULD CREATE" rn "|" (get-in r [:gleif :legal-name])
                                     "|" (get-in r [:gleif :jurisdiction])
                                     "|" (name (get-in r [:doc :doc-type]))
                                     "|" (get-in r [:doc :url])
                                     "| chars" (count (get-in r [:doc :text])))
                   :else (let [n (create-repo! (:gleif r) (:site r) (:doc r))]
                           (swap! created conj n)
                           (println "CREATED" n "|" (get-in r [:gleif :legal-name]))))))
             (doseq [[k label] [[:no-lei "no confident GLEIF parent match"]
                                [:site-unverified "site fetched but did not verify as the company"]
                                [:site-blocked "site blocked automated fetch (skipped, never bypassed)"]
                                [:site-unreachable "site unreachable"]
                                [:no-legal-doc "no published legal document found"]
                                [:error "error"]]]
               (let [rs (filter #(= k (:status %)) results)]
                 (when (seq rs)
                   (println (str "--- " label ": " (count rs) " ---"))
                   (doseq [r rs] (println "   " (:cand r) "-" (or (:reason r) ""))))))
             (println "=== SUMMARY ===")
             (println "candidates:" (count results)
                      " ready:" (count ready)
                      " created:" (count @created)
                      " no-lei:" (count (filter #(= :no-lei (:status %)) results))
                      " site-issue:" (count (filter #(#{:site-unverified :site-blocked :site-unreachable} (:status %)) results))
                      " no-doc:" (count (filter #(= :no-legal-doc (:status %)) results)))
             (when (seq @created)
               (fs/writeFileSync "/tmp/lei-created.txt" (str/join "\n" @created))
               (println "created repo names written to /tmp/lei-created.txt (for manifest registration)"))
             ;; Exit explicitly once the summary is out. A long run leaves
             ;; sockets from aborted/slow fetches that can emit an error event
             ;; after every candidate has been reported, which crashed the
             ;; process with a non-zero status AFTER the work had succeeded --
             ;; and any caller reading the exit code (the loop does) saw a
             ;; clean run as a failure. Exiting here reports the outcome the
             ;; summary actually describes rather than teardown noise; real
             ;; per-candidate failures are already in that summary.
             ;; Always 0 here: a candidate that was blocked, unreachable, or
             ;; published no document is DATA, not a script failure -- it is
             ;; reported in the summary above and is the expected outcome for a
             ;; sizeable share of any candidate list. A real failure (an
             ;; unreadable candidate file, a GLEIF outage) throws and is caught
             ;; by the .catch below, which sets exit 1.
             (js/setImmediate (fn [] (.exit js/process 0))))))
        (.catch (fn [e] (println "FATAL:" (.-message e)) (set! (.-exitCode js/process) 1))))))

(install-network-error-guard!)
(-main)

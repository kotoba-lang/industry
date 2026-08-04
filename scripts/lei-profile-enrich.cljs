#!/usr/bin/env nbb
;; lei-profile-enrich.cljs — read-only discovery of a company's OWN published
;; switchboard number, registered address, register number, and (separately)
;; its legally-disclosed representative, for the cloud-itonami-lei-<LEI> repos.
;;
;; ## The one rule this script exists to enforce
;;
;; Organisation-level facts and person-level facts go to DIFFERENT PLACES, and
;; the split is in the code, not in a policy document:
;;
;;   phone / postal address / register number  -> blueprint.edn (PUBLIC git)
;;   representative name + title               -> cloud-itonami-contact-pii
;;                                                (PRIVATE, age-encrypted, annex->B2)
;;
;; A switchboard number identifies a company. A director's name identifies a
;; person, and personal data does not belong in a world-readable git history
;; that is also projected into D1 and kotobase. `write-blueprint!` below cannot
;; write a person field because it is never handed one -- the routing happens
;; before either writer is called. See ADR-2608043000 and the dataset's
;; policy.edn.
;;
;; ## Scope boundary (inherited from lei-contact-discover, unchanged)
;;
;; HTTP GET only. No form is submitted, no mail is sent, no account is created,
;; and no CAPTCHA or bot check is solved or worked around -- a site that blocks
;; automated fetches is recorded as blocked and skipped. No third-party data
;; broker is queried: the only source is the company's own published pages.
;;
;; ## Honest by construction
;;
;; Nothing is written unless it was literally present in the fetched HTML.
;; Phone and address come from schema.org JSON-LD or a `tel:` href -- structured
;; data the site itself emitted -- not from a regex hunting for anything that
;; looks like a phone number in prose, which is where invented facts come from.
;; A representative is written only when a LABEL said so ("Vertreten durch",
;; "代表取締役", "Managing Director"); an unlabelled proper noun is not evidence
;; that the person is a director.
;;
;; Absence is recorded as absence, with a reason, so the next cycle can tell
;; "nobody publishes this" from "not looked at yet".
;;
;; Run (from the superproject root):
;;   nbb scripts/lei-profile-enrich.cljs [--limit N] [--dry-run] [--concurrency N]

(ns lei-profile-enrich
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :refer [execSync execFileSync]]
            [clojure.string :as str]
            [cljs.reader :as edn]
            [lei-catalog :as cat]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(defn- flag [n] (let [i (.indexOf (clj->js argv) n)]
                  (when (and (>= i 0) (< (inc i) (count argv))) (nth argv (inc i)))))
(def limit (some-> (flag "--limit") js/parseInt))
(def concurrency (or (some-> (flag "--concurrency") js/parseInt) 6))
(def dry-run? (boolean (some #{"--dry-run"} argv)))
(def only-lei (some-> (flag "--lei") str/upper-case))

(def pii-dir
  (or (.-env.LEI_PII_DIR js/process)
      "orgs/cloud-itonami/cloud-itonami-contact-pii"))

(def age-identity
  (or (.-env.LEI_PII_AGE_IDENTITY js/process)
      (path/join (or (.-env.HOME js/process) "~") ".config/cloud-itonami/contact-pii.age-identity")))

(def ua
  "Identify honestly as an automated collector with a contact route. Pretending
  to be a human browser would be the first step of exactly the bot-evasion this
  script refuses to do."
  "cloud-itonami-lei-catalog/1.0 (+https://github.com/cloud-itonami; profile enrichment; GET only)")

;; ── work list ───────────────────────────────────────────────────────────────

(defn work-list
  "Companies with a site and neither a switchboard number nor a registered
  address, read from the blueprints in git -- the catalog's source of truth, not
  a SQL projection of it. Returns a Promise."
  []
  (-> (cat/fetch-catalog {:concurrency 12})
      (.then (fn [c]
               (when (seq (:unreadable c))
                 (println "WARNING" (count (:unreadable c))
                          "repo(s) unreadable — this work list is incomplete:"
                          (pr-str (mapv :repo (:unreadable c)))))
               (vec (map (fn [x] {:lei (:company/lei x)
                                  :legal_name (:company/legal-name x)
                                  :jurisdiction (:company/jurisdiction x)
                                  :website (:company/website x)
                                  :repo (:company/repo x)})
                         (cond->> (if only-lei
                                    ;; --lei bypasses the "still needs doing"
                                    ;; filter on purpose: re-running one company
                                    ;; to check an extractor is the main way this
                                    ;; script gets debugged, and it must work
                                    ;; after the fields are already populated.
                                    (filter #(= only-lei (:company/lei %)) (:companies c))
                                    (cat/needing-profile (:companies c)))
                           limit (take limit))))))))

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

(defn blocked? [{:keys [status body]}]
  (or (#{403 429} status)
      (and body (re-find #"(?i)captcha|are you a robot|cf-browser-verification|challenge-platform|Just a moment\.\.\." body))))

;; ── page discovery ──────────────────────────────────────────────────────────

(def ^:private anchor-href-re #"(?is)<a\b[^>]*?href=[\"']([^\"']+)[\"']")
(def ^:private asset-re #"(?i)\.(css|js|mjs|png|jpe?g|gif|svg|webp|ico|woff2?|ttf|eot|zip|mp4|webm)(\?|#|$)")

(def ^:private disclosure-path-re
  "Pages a jurisdiction actually REQUIRES a company to publish, plus the general
  company-profile pages that carry the same facts voluntarily.

  Whole PATH SEGMENT, never a substring -- the lesson lei-contact-discover
  learned when `/employers/enhanced-family-supports` was promoted to a contact
  route because it contains 'support'. `legal` as a substring would match
  `/legal-tech-blog`; as a segment it matches the imprint."
  #"(?i)(^|/)(impressum|imprint|legal-notice|legal-notices|legal|mentions-legales|mentions-l%C3%A9gales|aviso-legal|note-legali|colofon|about-us|about|company|company-profile|corporate-profile|corporate|kaisha|%E4%BC%9A%E7%A4%BE%E6%A6%82%E8%A6%81|%E4%BC%81%E6%A5%AD%E6%83%85%E5%A0%B1|%E7%89%B9%E5%AE%9A%E5%95%86%E5%8F%96%E5%BC%95%E6%B3%95|contact[a-z-]*|kontakt[a-z-]*)(/|$|\?|#)")

(defn decode-entities [s]
  (-> s
      (str/replace #"&(?:amp|#0*38|#[xX]0*26);" "&")
      (str/replace #"&(?:lt|#0*60);" "<")
      (str/replace #"&(?:gt|#0*62);" ">")
      (str/replace #"&(?:quot|#0*34);" "\"")
      (str/replace #"&(?:apos|#0*39);" "'")
      (str/replace #"&(?:nbsp|#0*160);" " ")))

(defn- absolutize [base href]
  (try (.-href (js/URL. (decode-entities (str/trim href)) base)) (catch :default _ nil)))

(defn disclosure-urls
  "Same-origin candidate pages. Same-origin only: following an off-site link
  would start recording a third party's address under this company's row."
  [base html]
  (let [origin (try (.-origin (js/URL. base)) (catch :default _ nil))]
    (->> (re-seq anchor-href-re html)
         (map second)
         (keep #(absolutize base %))
         (filter #(and origin (str/starts-with? % origin)))
         (remove #(re-find asset-re %))
         (filter #(re-find disclosure-path-re (try (.-pathname (js/URL. %)) (catch :default _ ""))))
         distinct
         (take 6)
         vec)))

;; ── structured extraction (schema.org JSON-LD) ──────────────────────────────

(def ^:private jsonld-re #"(?is)<script[^>]+type=[\"']application/ld\+json[\"'][^>]*>(.*?)</script>")

(defn- jsonld-nodes
  "Every JSON-LD object on the page, flattened out of @graph and arrays.
  Parse failures are skipped silently -- a malformed blob on someone else's page
  is not this script's problem, and refusing the whole page over it would throw
  away the fields that DID parse."
  [html]
  (letfn [(walk [x acc]
            (cond
              (vector? x) (reduce #(walk %2 %1) acc x)
              (map? x) (let [acc (conj acc x)]
                         (reduce #(walk %2 %1) acc (vals (select-keys x [(keyword "@graph") :address :contactPoint]))))
              :else acc))]
    (reduce (fn [acc [_ raw]]
              (let [parsed (try (js->clj (js/JSON.parse raw) :keywordize-keys true)
                                (catch :default _ nil))]
                (if parsed (walk parsed acc) acc)))
            [] (re-seq jsonld-re html))))

(defn- org-node? [n]
  (let [t (get n (keyword "@type"))
        ts (cond (string? t) #{t} (vector? t) (set t) :else #{})]
    (boolean (some #(re-find #"(?i)organization|corporation|localbusiness|company" (str %)) ts))))

(def ^:private plausible-phone-re
  "A published phone number: at least 7 digits, and nothing that is obviously
  prose. Deliberately permissive about FORMAT (a site may publish
  `+49 (0)89 1234-0`) and strict about PROVENANCE -- the value must have come
  from a `telephone` property or a `tel:` href, never from scanning body text."
  #"^[+()0-9][-+()0-9.  /]{5,}[0-9]$")

(defn- clean-phone [s]
  (let [v (-> (str s) decode-entities (str/replace #"^tel:" "") str/trim
              (str/replace #"\s+" " "))]
    (when (and (re-find plausible-phone-re v)
               (>= (count (re-seq #"[0-9]" v)) 7)
               (<= (count v) 40))
      v)))

(defn extract-phone
  "schema.org `telephone` first, then a `tel:` href. Both are the site's own
  structured assertion that this string is a phone number."
  [html]
  (or (some (fn [n] (when (org-node? n) (clean-phone (:telephone n)))) (jsonld-nodes html))
      (some (fn [n] (clean-phone (:telephone n))) (jsonld-nodes html))
      (some (fn [[_ href]] (clean-phone href))
            (re-seq #"(?is)<a\b[^>]*?href=[\"'](tel:[^\"']+)[\"']" html))))

(defn extract-address
  "A schema.org PostalAddress, composed in a fixed part order. Structured only:
  there is no free-text address parser here, because parsing an address out of
  prose is exactly the step that turns a page into a plausible invention."
  [html]
  (some (fn [n]
          (when (re-find #"(?i)postaladdress" (str (get n (keyword "@type"))))
            (let [parts (keep #(some-> (% n) str str/trim not-empty)
                              [:streetAddress :addressLocality :addressRegion
                               :postalCode :addressCountry])]
              (when (>= (count parts) 2)
                (str/join ", " (map decode-entities parts))))))
        (jsonld-nodes html)))

;; ── text extraction (labelled facts only) ───────────────────────────────────

(def ^:private named-entities
  "The accented characters that actually appear in European company officers'
  names. Without these, `Anja Sch&ouml;llmann` is recorded verbatim -- a name
  that belongs to nobody."
  {"ouml" "ö" "auml" "ä" "uuml" "ü" "Ouml" "Ö" "Auml" "Ä" "Uuml" "Ü"
   "szlig" "ß" "eacute" "é" "egrave" "è" "ecirc" "ê" "agrave" "à" "acirc" "â"
   "ccedil" "ç" "ntilde" "ñ" "oacute" "ó" "aacute" "á" "iacute" "í" "uacute" "ú"
   "Eacute" "É" "Ccedil" "Ç" "oslash" "ø" "aring" "å" "aelig" "æ"})

(defn- decode-text-entities
  "Full-ish entity decoding for TEXT (not attributes): the named set above plus
  every numeric reference. A half-decoded name is worse than an undecoded one,
  because it still looks like a name."
  [s]
  (-> s
      decode-entities
      (str/replace #"&#(\d+);" (fn [[_ d]] (js/String.fromCodePoint (js/parseInt d 10))))
      (str/replace #"&#[xX]([0-9a-fA-F]+);" (fn [[_ h]] (js/String.fromCodePoint (js/parseInt h 16))))
      (str/replace #"&([A-Za-z]+);" (fn [[whole nm]] (get named-entities nm whole)))))

(defn- visible-text [html]
  (-> html
      (str/replace #"(?is)<(script|style|noscript)\b.*?</\1>" " ")
      (str/replace #"(?s)<[^>]+>" "\n")
      decode-text-entities
      (str/replace #"[ \t ]+" " ")
      (str/replace #"\n{2,}" "\n")))

(def ^:private register-re
  #"(?i)\b(HRB|HRA)\s?([0-9]{1,7})\b|法人番号[\s:：]*([0-9]{13})")

(defn extract-registration-number [text]
  (when-let [m (re-find register-re text)]
    (cond (nth m 3) (str "法人番号 " (nth m 3))
          (nth m 1) (str (str/upper-case (nth m 1)) " " (nth m 2)))))

(def ^:private rep-label-re
  "The LABEL is the evidence, and it must be a legally-meaningful one. `CEO`
  alone is deliberately absent: it appears in marketing copy, press quotes and
  blog bylines on every corporate site, and matching it would fill this dataset
  with people who are not the legally-disclosed representative."
  #"(?im)^[\s]*(Vertreten durch|Vertretungsberechtigte[rn]?|Vertretungsberechtigt|Gesch..?ftsf..?hrer(?:in)?|Vorstand|Managing Director|Directeur de la publication|G..?rant|Amministratore(?: [Dd]elegato)?|Administrador|代表取締役(?:社長|会長)?|代表者(?:名)?|代表社員)[\s]*[:：]?[\s]*$")

(def ^:private rep-inline-re
  "Label and names on one line. The capture is generous (200 chars) because a
  board is a comma-separated list: capping it at 60 truncated
  `Dr. Michael Peterson (Vorsitzender), Wilken Bormann (Finanzen), ...` into
  `Wilken Bormann (Finanze` -- a fragment that still passed the name check and
  was written to the dataset as a person."
  #"(?i)(Vertreten durch|Vertretungsberechtigte[rn]?|Gesch..?ftsf..?hrer(?:in)?|Vorstand|Managing Director|Directeur de la publication|G..?rant|Amministratore(?: [Dd]elegato)?|代表取締役(?:社長|会長)?|代表者(?:名)?|代表社員)[\s]*[:：][\s]*([^\n]{2,200})")

(def ^:private entity-mention-re
  "A company-like name: a token run ending in a legal form. Used to find which
  legal entity a section of an Impressum is speaking about."
  #"(?i)([\p{L}\p{N}&.\-' ]{2,80}?\s(?:AG|Aktiengesellschaft|SE|GmbH|KGaA|KG|OHG|PLC|Ltd\.?|Limited|Inc\.?|Corp\.?|Corporation|N\.V\.|B\.V\.|S\.A\.|SAS|S\.p\.A\.|Oyj|AB|ASA))(?:\b|$)|((?:株式会社[\p{L}\p{N}]{1,30})|(?:[\p{L}\p{N}]{1,30}株式会社))")

(defn- name-like?
  "A person's name, conservatively. No digits, no URL/email, not a sentence.
  Latin names are 2-5 whitespace-separated tokens; CJK names are one token of
  2-8 characters. Anything else is prose that happened to follow the label."
  [s]
  (let [v (str/trim (str s))]
    (and (seq v)
         (<= (count v) 60)
         (not (re-find #"[0-9@]|https?://|\.(com|org|net|jp|de)\b" v))
         ;; Unbalanced brackets mean the string was cut mid-list.
         (= (count (re-seq #"[（(]" v)) (count (re-seq #"[)）]" v)))
         (not (re-find #"[.。!?]" v))
         (or (re-find #"^[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}・\s]{2,20}$" v)
             (<= 2 (count (str/split v #"\s+")) 5)))))

(def ^:private rep-sentence-re
  "The prose form: `Die DB Fernverkehr AG wird vertreten durch den Vorstand:`
  followed by the names on the next line. Common on real Impressum pages, and
  DANGEROUS -- see `entity-matches?`."
  #"(?im)^\s*(?:Die|Der|Das)?\s*([^\n]{2,90}?)\s+(?:wird\s+)?vertreten durch(?:\s+(?:den|die|das))?\s*([^:\n]{0,30}?)\s*[:：]\s*$")

(defn- normalise-entity
  "Company names for comparison: lowercase, legal form and punctuation removed.
  `Deutsche Bahn AG` and `Deutsche Bahn Aktiengesellschaft` compare equal;
  `DB Fernverkehr AG` does not compare equal to either."
  [s]
  (-> (str s)
      str/lower-case
      (str/replace #"\b(ag|aktiengesellschaft|se|gmbh|kgaa|kg|ohg|plc|ltd|limited|inc|corp|corporation|nv|bv|sa|sas|spa|oyj|ab|as|a/s|株式会社|有限会社)\b" " ")
      (str/replace #"[^\p{L}\p{N}]+" " ")
      str/trim))

(defn- entity-matches?
  "Does this Impressum paragraph speak for the company we are enriching?

  This guard is the reason the representative extractor is trustworthy at all.
  `bahn.de/impressum` -- reached from Deutsche Bahn AG's own website, on its own
  domain -- declares the board of **DB Fernverkehr AG**, a different legal
  entity with a different LEI. Without this check the catalog would have
  recorded four people as Deutsche Bahn AG's representatives, sourced from a
  real page, with correct spelling, and entirely wrong. A group Impressum
  covering several subsidiaries is the normal case, not the exception."
  [company-name entity-phrase]
  (let [a (normalise-entity company-name)
        b (normalise-entity entity-phrase)]
    (and (seq a) (seq b)
         (or (= a b)
             (str/includes? a b)
             (str/includes? b a)))))

(defn- split-names
  "`Dr. Michael Peterson (Vorsitzender), Wilken Bormann (Finanzen)` -> two
  people, each with the parenthetical kept as their title. Splitting on the
  comma is safe here only because the parentheticals are removed first."
  [s]
  (->> (str/split (str s) #"[,;、]")
       (map str/trim)
       (remove str/blank?)
       (map (fn [part]
              (let [m (re-find #"^(.*?)\s*[（(]([^)）]*)[)）]\s*$" part)]
                (if m
                  {:person/name (str/trim (nth m 1)) :person/title (str/trim (nth m 2))}
                  {:person/name part}))))
       (filter #(name-like? (:person/name %)))
       vec))

(defn- nearest-entity-above
  "Which legal entity is this line of the Impressum speaking about? The nearest
  company-like name at or above it, within 12 lines. nil if there is none."
  [lines i]
  (loop [j i n 0]
    (cond
      (or (neg? j) (> n 12)) nil
      :else (if-let [m (re-find entity-mention-re (nth lines j))]
              (str/trim (or (nth m 1) (nth m 2)))
              (recur (dec j) (inc n))))))

(defn extract-representatives
  "Labelled representatives only, as {:person/name :person/title}.

  EVERY path is gated on the entity: a label is accepted only when the nearest
  company name at or above it is THIS company. A group Impressum listing several
  subsidiaries is the normal case, and without the gate the wrong board gets
  attributed to the wrong LEI.

  This was not hypothetical. `bahn.de/impressum` -- reached from Deutsche Bahn
  AG's own site, on its own domain -- declares the board of DB Fernverkehr AG.
  An earlier version of this function gated only the prose form, the `Vorstand:`
  inline form walked straight past the gate, and a DB Fernverkehr director was
  written into the dataset under Deutsche Bahn AG's LEI: a real page, correct
  spelling, wrong company.

  A record with no label is never written at all (schema.edn); one whose entity
  cannot be verified is not written either. An unverifiable representative is
  worse than a missing one."
  [company-name html]
  (let [lines (vec (str/split-lines (visible-text html)))
        mine? (fn [i] (if-let [e (nearest-entity-above lines i)]
                        (entity-matches? company-name e)
                        false))
        per-line
        (fn [i]
          (let [line (nth lines i)]
            (cond
              ;; Prose: `Die X AG wird vertreten durch den Vorstand:` -- the
              ;; entity is named in the sentence itself, so use that directly.
              (re-find rep-sentence-re line)
              (let [m (re-find rep-sentence-re line)]
                (when (entity-matches? company-name (nth m 1))
                  (map #(assoc % :person/source-label (str/trim line))
                       (split-names (or (get lines (inc i)) "")))))

              ;; `Label: names` on one line.
              (re-find rep-inline-re line)
              (when (mine? i)
                (let [m (re-find rep-inline-re line)]
                  (map #(assoc % :person/source-label (str/trim (nth m 1)))
                       (split-names (nth m 2)))))

              ;; Label alone on its line, names on the next.
              (re-find rep-label-re line)
              (when (mine? i)
                (map #(assoc % :person/source-label (str/trim line))
                     (split-names (or (get lines (inc i)) ""))))

              :else nil)))]
    (->> (range (count lines))
         (mapcat per-line)
         (map #(update % :person/source-label (fn [l] (str/replace l #"[\s:：]+$" ""))))
         (distinct)
         (take 8)
         vec)))

;; ── writers ─────────────────────────────────────────────────────────────────

(defn- gh-json [args input]
  (let [p (execSync (str "gh api " args (when input " --input -"))
                    (clj->js (cond-> {:encoding "utf8" :maxBuffer (* 20 1024 1024) :stdio "pipe"}
                               input (assoc :input input))))]
    (js->clj (js/JSON.parse p) :keywordize-keys true)))

(defn write-blueprint!
  "ORGANISATION-level fields only. This fn is never handed a person field --
  the routing happens in `enrich-one`, before either writer is called.

  Read-modify-write through the Contents API with the blob sha sent back, so a
  concurrent edit 409s instead of being clobbered (same optimistic lock
  lei-contact-discover and west.yml use)."
  [repo-name {:keys [phone address registration at source-url]}]
  (let [p (str "repos/cloud-itonami/" repo-name "/contents/blueprint.edn")
        cur (gh-json (str "\"" p "\"") nil)
        raw (.toString (js/Buffer.from (:content cur) "base64") "utf8")
        bp (edn/read-string raw)
        note (str "published on the company's own site (" source-url "), retrieved "
                  at " by scripts/lei-profile-enrich.cljs (GET only)")
        bp' (cond-> bp
              phone (assoc :company/phone phone)
              address (assoc :company/postal-address address)
              registration (assoc :company/registration-number registration)
              (or phone address registration) (assoc :company/profile-note note))
        body (str "{" (str/join "\n " (map (fn [[k v]] (str (pr-str k) " " (pr-str v))) bp')) "}\n")
        payload (js/JSON.stringify
                 (clj->js {:message (str "data(profile): organisation contact facts for "
                                         (:company/legal-name bp')
                                         "\n\nDiscovered read-only (HTTP GET) from the company's own"
                                         "\npublished pages. Organisation-level only -- no person data"
                                         "\nis written to this public repo (ADR-2608043000).")
                           :content (.toString (js/Buffer.from body "utf8") "base64")
                           :sha (:sha cur)
                           :branch "main"}))]
    (if dry-run?
      {:ok true :dry-run true}
      (do (gh-json (str "-X PUT \"" p "\"") payload) {:ok true}))))

(def ^:private recipient
  (delay
    (->> (str/split-lines (fs/readFileSync (path/join pii-dir "recipients.txt") "utf8"))
         (map str/trim)
         (filter #(str/starts-with? % "age1"))
         first)))

(defn- record-path [lei] (path/join pii-dir "records" (str lei ".edn.age")))

(defn- read-record
  "Decrypt the existing record, or nil. Used to compare BEFORE writing: age
  re-encrypts with a fresh file key every time, so writing an unchanged record
  would produce a different annex key and a spurious new B2 object every cycle."
  [lei]
  (let [p (record-path lei)]
    (when (fs/existsSync p)
      (try (edn/read-string (.toString (execFileSync "age" #js ["-d" "-i" age-identity p]
                                                     #js {:maxBuffer (* 8 1024 1024)})))
           (catch :default _ nil)))))

(defn write-record!
  "PERSON-level facts, age-encrypted, into the private dataset. Never touches
  blueprint.edn, D1 or kotobase."
  [lei rec]
  (let [p (record-path lei)
        prev (read-record lei)]
    (cond
      (= (dissoc prev :record/as-of) (dissoc rec :record/as-of)) {:ok true :unchanged true}
      dry-run? {:ok true :dry-run true}
      :else
      (do (fs/mkdirSync (path/dirname p) #js {:recursive true})
          (execFileSync "age" #js ["-r" @recipient "-o" p]
                        #js {:input (pr-str rec) :maxBuffer (* 8 1024 1024)})
          {:ok true}))))

;; ── per-company pipeline ────────────────────────────────────────────────────

(defn enrich-one [{:keys [lei legal_name jurisdiction website repo]}]
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
             (let [pages (disclosure-urls (:url home) (:body home))]
               (-> (reduce
                    (fn [pp url]
                      (.then pp (fn [acc]
                                  (if (and (:phone acc) (:address acc) (seq (:persons acc)))
                                    acc
                                    (-> (fetch-html url)
                                        (.then (fn [pg]
                                                 (if (or (blocked? pg) (nil? (:body pg)))
                                                   acc
                                                   (let [b (:body pg)]
                                                     (cond-> (update acc :seen conj url)
                                                       (not (:phone acc)) (as-> a (if-let [v (extract-phone b)]
                                                                                    (assoc a :phone v :phone-src url) a))
                                                       (not (:address acc)) (as-> a (if-let [v (extract-address b)]
                                                                                      (assoc a :address v :address-src url) a))
                                                       (not (:registration acc)) (as-> a (if-let [v (extract-registration-number (visible-text b))]
                                                                                           (assoc a :registration v) a))
                                                       (empty? (:persons acc))
                                                       (as-> a (let [ps (extract-representatives legal_name b)]
                                                                 (if (seq ps)
                                                                   (assoc a :persons (mapv #(assoc % :person/source-url url :person/as-of at) ps))
                                                                   a)))))))))))))
                    (js/Promise.resolve
                     (let [b (:body home)]
                       (cond-> {:seen [(:url home)]}
                         true (as-> a (if-let [v (extract-phone b)] (assoc a :phone v :phone-src (:url home)) a))
                         true (as-> a (if-let [v (extract-address b)] (assoc a :address v :address-src (:url home)) a)))))
                    pages)
                   (.then
                    (fn [found]
                      ;; ── THE ROUTING. Organisation facts and person facts are
                      ;; separated here, once, before either writer runs.
                      (let [org (select-keys found [:phone :address :registration])
                            org (into {} (remove (comp nil? val) org))
                            persons (:persons found)
                            org-res (when (seq org)
                                      (try (write-blueprint! repo-name
                                                             (assoc org :at at
                                                                    :source-url (or (:phone-src found) (:address-src found))))
                                           (catch :default e {:ok false :error (.-message e)})))
                            rec (when (seq persons)
                                  {:record/lei lei :record/legal-name legal_name
                                   :record/as-of at
                                   :record/source-urls (vec (distinct (map :person/source-url persons)))
                                   :record/persons (vec persons)})
                            pii-res (cond
                                      rec (try (write-record! lei rec)
                                               (catch :default e {:ok false :error (.-message e)}))
                                      :else nil)]
                        {:lei lei :name legal_name :jurisdiction jurisdiction
                         :status (if (or (seq org) (seq persons)) :found :not-found)
                         :org org :org-ok (:ok org-res)
                         :persons (count persons) :pii-ok (:ok pii-res)
                         :pii-unchanged (:unchanged pii-res)
                         :pages-checked (count (:seen found))
                         :absent (when (empty? persons)
                                   {:absent/reason (if (seq pages)
                                                     :page-found-but-unlabelled
                                                     :no-legal-disclosure-page)
                                    :absent/as-of at})}))))))))
        (.catch (fn [e] {:lei lei :name legal_name :status :error :error (.-message e)})))))

(defn run-bounded [items f n]
  (let [q (atom (vec items)) out (atom [])
        worker (fn worker []
                 (if-let [it (first @q)]
                   (do (swap! q rest)
                       (-> (f it) (.then (fn [r] (swap! out conj r) (worker)))))
                   (js/Promise.resolve nil)))]
    (-> (js/Promise.all (clj->js (repeatedly (min n (max 1 (count items))) worker)))
        (.then (fn [_] @out)))))

(defn- install-network-error-guard! []
  (let [net? (fn [^js e]
               (let [s (str (or (.-code e) "") " " (or (.-name e) "") " " (or (.-message e) ""))]
                 (boolean (re-find #"(?i)SocketError|ECONNRESET|ECONNREFUSED|EPIPE|ETIMEDOUT|ENOTFOUND|EAI_AGAIN|UND_ERR|ERR_HTTP2|other side closed|terminated" s))))]
    (.on js/process "uncaughtException"
         (fn [^js e] (if (net? e)
                       (js/console.error "network error absorbed (run continues):" (or (.-message e) (str e)))
                       (do (js/console.error "FATAL (not a network error):" (or (.-stack e) (str e)))
                           (.exit js/process 1)))))
    (.on js/process "unhandledRejection"
         (fn [^js e] (if (net? e)
                       (js/console.error "network rejection absorbed (run continues):" (or (.-message e) (str e)))
                       (do (js/console.error "FATAL unhandled rejection:" (or (.-stack e) (str e)))
                           (.exit js/process 1)))))))

(defn -main []
  (when-not (fs/existsSync (path/join pii-dir "recipients.txt"))
    (println "PII dataset not found at" pii-dir "-- refusing to run.")
    (println "Person-level facts have nowhere safe to go, and writing them to the")
    (println "public catalog instead is the exact failure this script prevents.")
    (.exit js/process 2))
  (-> (work-list)
      (.then (fn [work]
               (println "companies needing profile facts:" (count work) (when dry-run? "(dry-run)"))
               (run-bounded work enrich-one concurrency)))
        (.then
         (fn [results]
           (doseq [r (filter #(= :found (:status %)) results)]
             (println "FOUND" (:lei r) (:name r)
                      "| phone=" (or (:phone (:org r)) "-")
                      "| addr=" (if (:address (:org r)) "yes" "-")
                      "| reg=" (or (:registration (:org r)) "-")
                      "| persons=" (:persons r)
                      ;; Names are NEVER printed -- only the count. This output
                      ;; goes to ~/.gftd/lei-profile-tick.stdout.log, and a log
                      ;; file is exactly the plaintext side channel the
                      ;; encrypted dataset exists to avoid.
                      (cond (zero? (:persons r)) ""
                            dry-run? "(pii not written — dry run)"
                            (:pii-unchanged r) "(pii unchanged)"
                            (:pii-ok r) "(pii written)"
                            :else "(pii FAILED)")))
           (doseq [[k label] [[:not-found "not-found"]
                              [:blocked "blocked (bot check — skipped, never bypassed)"]
                              [:unreachable "unreachable"] [:error "error"]]]
             (let [rs (filter #(= k (:status %)) results)]
               (when (seq rs)
                 (println (str "--- " label ": " (count rs) " ---"))
                 (doseq [r (take 40 rs)] (println "   " (:lei r) (:name r) (or (:error r) (:http r) ""))))))
           (let [found (filter #(= :found (:status %)) results)]
             (println "=== SUMMARY ===")
             (println "checked:" (count results)
                      " with-phone:" (count (filter #(:phone (:org %)) found))
                      " with-address:" (count (filter #(:address (:org %)) found))
                      " with-register:" (count (filter #(:registration (:org %)) found))
                      " with-persons:" (count (filter #(pos? (:persons %)) found))
                      " not-found:" (count (filter #(= :not-found (:status %)) results))
                      " blocked:" (count (filter #(= :blocked (:status %)) results))
                      " unreachable:" (count (filter #(= :unreachable (:status %)) results)))
             (when-not dry-run?
               (println)
               (println "person records were written into" pii-dir "-- they are NOT committed yet.")
               (println "  cd" pii-dir "&& datalad save -m 'records: <n> companies' && datalad push --to b2")))))
      (.catch (fn [e] (println "FATAL:" (.-message e)) (set! (.-exitCode js/process) 1)))))

(install-network-error-guard!)
(-main)

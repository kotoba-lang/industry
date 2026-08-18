#!/usr/bin/env nbb
;; Report GitHub repositories that `manifest/west.yml` registers more than once.
;;
;;   nbb scripts/verify-duplicate-registrations.cljs             ; report
;;   nbb scripts/verify-duplicate-registrations.cljs --check     ; exit 1 if any
;;   nbb scripts/verify-duplicate-registrations.cljs --compare   ; + pin divergence
;;   nbb scripts/verify-duplicate-registrations.cljs --findings  ; detector protocol
;;   nbb scripts/verify-duplicate-registrations.cljs --disk      ; old checkout scan
;;
;; Run from the superproject root.
;;
;; ## What it finds
;;
;; A repository that is renamed keeps working under its old name, because GitHub
;; redirects. So a rename can leave BOTH names registered -- two projects, two
;; paths, two pins, one upstream. The pins then drift, and a consumer that
;; resolves through the old path gets a different commit from one that resolves
;; through the new. Measured 2026-08-13: `kotoba-lang/compiler` and
;; `kotoba-lang/amu` are one repository (id 1297097065) whose two pins are 62
;; commits apart (ADR-2608133800).
;;
;; **A rename never breaks anything, so it duplicates silently instead.**
;;
;; ## Identity is the repository id, because the name is what lies
;;
;; The unit scanned is the WEST ENTRY, not the checkout. That is a deliberate
;; change from the 2026-08-12 version, which grouped checkouts on disk by shared
;; root commit. Two problems with that, both structural:
;;
;;   1. A shared root commit is what a FORK has too, so every group was a
;;      candidate needing confirmation. Two of the 42 groups measured on
;;      2026-08-12 were genuinely different repositories.
;;   2. It could only see registrations whose checkout was on disk. west manages
;;      more entries than are checked out, and the duplicate that matters most
;;      is precisely the one whose stale half nobody has cloned lately.
;;
;; Grouping west entries by GitHub id has neither problem: a fork has its own
;; id, and every entry is scanned whether or not it is on disk.
;;
;; ## The cost, stated rather than implied
;;
;; The naive form is one `gh api repos/<slug>` per entry: 4,166 calls against a
;; token shared with every concurrent agent session on this machine. Instead:
;;
;;   - ONE `gh api orgs/<org>/repos --paginate` per org (8 orgs) resolves every
;;     name that CURRENTLY exists, 100 repos per HTTP request. Measured
;;     2026-08-13: 9 gh invocations, about 48 HTTP requests, for 4,166 entries.
;;   - A name absent from its org's listing has been renamed, transferred or
;;     deleted. Only those get a per-repo GET, which follows the redirect.
;;     Measured 2026-08-13: 27 of 4,166.
;;   - Those per-repo lookups are cached in `.cache/duplicate-registrations.edn`
;;     (30-day TTL). The cache is only ever consulted for a name the listing
;;     says does not exist, so a recreated name cannot be served stale: it would
;;     be in the listing and the listing wins.
;;
;; Measured 2026-08-13 on this machine, load recorded because an identical
;; script has swung 3.7x on load alone:
;;
;;   default, cold cache   37 gh invocations   105 s   load 88
;;   default, warm cache   10 gh invocations    65 s   load 90
;;   --disk-ids, warm       9 gh invocations   175 s   load 114
;;
;; `--disk-ids` adds one lookup per undeclared checkout (249 on 2026-08-13) on a
;; cold cache, and its time is dominated by ~1,250 local `git` subprocesses, not
;; by the network. Nothing in the default path reads `orgs/` at all.
;;
;; **Budget exhaustion is not a clean run.** `--budget N` caps per-repo lookups.
;; If the budget runs out, or a lookup fails after backoff, the affected entries
;; are UNRESOLVED, they are printed, the summary says so, and the exit code is 2
;; -- distinct from both 0 (clean) and 1 (findings). This is the defect fixed in
;; `advance-pins.cljs`, where 210 failed lookups were reported as a tidy count
;; and exit 0, and the defect the 2026-08-12 version of this script fixed for
;; its own UNVERIFIABLE class. A verifier that could not reach its evidence must
;; never be indistinguishable from one that looked and found nothing.
;;
;; `gh api rate_limit` does NOT report the secondary rate limit: it will claim
;; thousands of requests remain while every `repos/*` GET returns 403. So the
;; backoff keys on the error text, not on the advertised budget.
;;
;; ## Progress goes to stderr
;;
;; The 2026-08-12 version emitted nothing until the end, so a 240-second run at
;; load 90 was indistinguishable from a hang and was killed at exit 124 having
;; printed zero lines (ADR-2608133800). Progress now goes to stderr via
;; `js/console.error` -- note that `(binding [*out* *err*] (println ...))` does
;; nothing in nbb and writes to stdout instead (ADR-2608130600). stdout stays
;; machine-readable.

(ns verify-duplicate-registrations
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as child]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def argv (vec (drop 2 js/process.argv)))
(defn- flag? [f] (some #{f} argv))
(defn- opt [f d]
  (let [i (.indexOf argv f)]
    (if (neg? i) d (get argv (inc i) d))))

(def check? (flag? "--check"))
(def findings? (flag? "--findings"))
(def compare? (flag? "--compare"))
(def disk? (or (flag? "--disk") (flag? "--disk-ids")))
(def disk-ids? (flag? "--disk-ids"))
(def no-cache? (flag? "--no-cache"))
(def budget (js/parseInt (opt "--budget" "600") 10))
;; Where `orgs/` actually lives. A worktree cut from this superproject is
;; usually sparse and has no `orgs/` at all, so [on-disk] would read [absent]
;; for every entry and the consequence ranking would be silently wrong.
(def orgs-root (opt "--root" "."))
(def cache-file ".cache/duplicate-registrations.edn")
(def cache-ttl-ms (* 30 24 60 60 1000))

(defn- log! [& parts] (js/console.error (str "[dupreg] " (str/join "" parts))))

(def api-calls (atom 0))
(def budget-hit (atom false))

;; ---------------------------------------------------------------- west.yml

(defn- parse-west
  "Entries as {:name :remote :rev :path}. Only project entries -- the `remotes:`
  block uses the same `- name:` shape at the same indent and is excluded by
  requiring a `path:`."
  [file]
  (let [lines (str/split-lines (str (fs/readFileSync file "utf8")))]
    (loop [ls lines cur nil acc []]
      (if (empty? ls)
        (filterv :path (if cur (conj acc cur) acc))
        (let [l (first ls)
              done (fn [] (if cur (conj acc cur) acc))]
          (cond
            (re-find #"^    - name: " l)
            (recur (rest ls) {:name (str/trim (subs l 12))} (done))

            (nil? cur) (recur (rest ls) cur acc)

            (re-find #"^      remote: " l)
            (recur (rest ls) (assoc cur :remote (str/trim (subs l 14))) acc)

            ;; `repo-path:` overrides the project name as the GITHUB repo name.
            ;; Measured 2026-08-13: 59 of 4,166 entries carry one, and the
            ;; first version of this rewrite ignored it -- so it asked GitHub
            ;; about `cloud-itonami/cloud-itonami-adserver` (404) when the repo
            ;; is `cloud-itonami/adserver`. The west NAME is not the repo name;
            ;; that is the same lesson as the rename, one level further in.
            (re-find #"^      repo-path: " l)
            (recur (rest ls) (assoc cur :repo-path (str/trim (subs l 17))) acc)

            (re-find #"^      revision: " l)
            (recur (rest ls) (assoc cur :rev (str/trim (subs l 16))) acc)

            (re-find #"^      path: " l)
            (recur (rest ls) (assoc cur :path (str/trim (subs l 12))) acc)

            :else (recur (rest ls) cur acc)))))))

;; ------------------------------------------------------------------- cache

(defn- read-cache []
  (if no-cache?
    {}
    (try (let [m (edn/read-string (str (fs/readFileSync cache-file "utf8")))
               now (.now js/Date)]
           (into {} (remove (fn [[_ v]] (> (- now (:at v 0)) cache-ttl-ms)) m)))
         (catch :default _ {}))))

(defn- write-cache! [m]
  (when-not no-cache?
    (try (fs/mkdirSync (path/dirname cache-file) #js {:recursive true}) (catch :default _ nil))
    (try (fs/writeFileSync cache-file (with-out-str (pr m))) (catch :default _ nil))))

;; --------------------------------------------------------------------- gh

(defn- gh
  "{:out s} or {:error s}. Never a bare nil -- a nil cannot say whether the
  answer was `no` or whether `gh` never answered, and collapsing those two is
  how a verifier reports a clean fleet during a rate limit."
  [args]
  (swap! api-calls inc)
  (try {:out (str/trim (str (child/execSync (str "gh api " args)
                                            #js {:encoding "utf8" :maxBuffer (* 64 1024 1024)
                                                 :stdio #js ["ignore" "pipe" "pipe"]})))}
       (catch :default e
         {:error (or (some-> (.-stderr e) str str/trim str/split-lines first)
                     (str/trim (str (.-message e))))})))

(defn- rate-limited? [msg]
  (boolean (some #(str/includes? (str/lower-case (or msg "")) %)
                 ["rate limit" "secondary" "abuse detection" "403"])))

(defn- gh-retry
  "Backs off on the secondary rate limit, which `gh api rate_limit` does not
  report -- it will claim thousands of requests remain while every repos/* GET
  returns 403. So the signal is the error text."
  [args]
  (loop [attempt 0]
    (let [r (gh args)]
      (cond
        (:out r) r
        (and (rate-limited? (:error r)) (< attempt 4))
        (let [wait (* 15 (inc attempt))]
          (log! "rate limited, sleeping " wait "s (attempt " (inc attempt) "/4): " (:error r))
          (try (child/execSync (str "sleep " wait)) (catch :default _ nil))
          (recur (inc attempt)))
        :else r))))

(defn- gh-name
  "The GitHub repo name for a west entry: `repo-path:` when present, else the
  project name."
  [e] (or (:repo-path e) (:name e)))

(defn- org-listing
  "name -> {:id :full-name} for every repo that CURRENTLY exists in the org."
  [org]
  (let [q (fn [kind]
            ;; The `select(.owner.login == ...)` is load-bearing, not defensive.
            ;; `users/<name>/repos?type=all` returns MEMBER repos from other
            ;; orgs too: measured 2026-08-13, `users/com-junkawasaki/repos`
            ;; returned 70 kotoba-lang repos among its 115. Keying the map on
            ;; the bare `.name` then let `kotoba-lang/svgraph` (id 1272252907)
            ;; overwrite `com-junkawasaki/svgraph` (id 1285663576), and the
            ;; detector reported the two as ONE repository registered twice.
            ;; A detector whose whole subject is `the name is not the identity`
            ;; had itself keyed on a name.
            (gh-retry (str "\"" kind "/" org "/repos?per_page=100&type=all\" --paginate "
                           "--jq '.[] | select(.owner.login == \"" org "\") "
                           "| [.name, (.id|tostring), .full_name] | @tsv'")))
        ;; `com-junkawasaki` is a USER, not an org, so `orgs/.../repos` 404s.
        ;; Without this fallback all 36 of its entries fell through to per-repo
        ;; lookups, which is 36 wasted calls that still happened to work --
        ;; the failure was invisible in the result and visible only in the cost.
        r (let [a (q "orgs")] (if (:error a) (q "users") a))]
    (if (:error r)
      {:error (:error r)}
      {:repos (into {}
                    (keep (fn [line]
                            (let [[n id full] (str/split line #"\t")]
                              (when (and n id) [n {:id id :full-name full}]))))
                    (str/split-lines (:out r)))})))

(defn- resolve-slug
  "One per-repo GET, which follows the rename/transfer redirect."
  [slug]
  (if (>= @api-calls budget)
    (do (reset! budget-hit true) {:error (str "budget " budget " exhausted")})
    (let [r (gh-retry (str "repos/" slug " --jq '[(.id|tostring), .full_name] | @tsv'"))]
      (if (:error r)
        r
        (let [[id full] (str/split (:out r) #"\t")]
          (if id {:id id :full-name full} {:error (str "unparseable: " (:out r))}))))))

;; ------------------------------------------------------------------ divergence

(defn- divergence [full-name a b]
  (let [r (gh-retry (str "repos/" full-name "/compare/" a "..." b
                         " --jq '[.status, (.ahead_by|tostring), (.behind_by|tostring)] | @tsv'"))]
    (if (:error r)
      {:error (:error r)}
      (let [[s ahead behind] (str/split (:out r) #"\t")]
        {:status s :ahead ahead :behind behind}))))

;; ----------------------------------------------------------------- disk scan

(defn- sh [cmd]
  (try (str/trim (str (child/execSync cmd #js {:encoding "utf8"
                                               :stdio #js ["ignore" "pipe" "ignore"]})))
       (catch :default _ nil)))

(defn- remote-slug
  "owner/name for a checkout's best GitHub remote, or nil.

  NOT `origin`, and not the first remote either. west names each remote after
  the ORG, so a west-managed checkout usually has no `origin` at all: measured
  2026-08-13, asking only for `origin` returned nothing for 182 of 249 orphans
  and they were silently reported as having no remote. And taking the first
  remote alphabetically is how the 2026-08-12 version picked a git-annex `b2`
  remote, and how `ai-gftd-apps-gftdcojp` would be read through its
  `kotoba-upstream` remote, which points at an entirely different repository.

  So: `origin` if it is a GitHub remote, else the remote named after the org
  segment of the path, else the first GitHub remote."
  [dir org]
  (let [names (->> (or (some-> (sh (str "git -C " dir " remote")) (str/split #"\n")) [])
                   (map str/trim)
                   (remove str/blank?))
        pairs (keep (fn [n] (when-let [u (sh (str "git -C " dir " remote get-url " n))]
                              [n u]))
                    names)
        github? (fn [[_ u]] (re-find #"github\.com[:/]" u))
        pick (or (first (filter (fn [[n _ :as p]] (and (= n "origin") (github? p))) pairs))
                 (first (filter (fn [[n _ :as p]] (and (= n org) (github? p))) pairs))
                 (first (filter github? pairs)))]
    (when pick
      (-> (second pick)
          (str/replace #"^.*github\.com[:/]" "")
          (str/replace #"\.git$" "")
          (str/replace #"/$" "")))))

(defn- disk-checkouts []
  (->> (try (js->clj (fs/readdirSync (path/join orgs-root "orgs") #js {:withFileTypes true})) (catch :default _ []))
       (filter #(.isDirectory %))
       (mapcat (fn [org]
                 (let [org-name (.-name org)]
                   (->> (try (js->clj (fs/readdirSync (path/join orgs-root "orgs" org-name)
                                                      #js {:withFileTypes true}))
                             (catch :default _ []))
                        (filter #(.isDirectory %))
                        (map #(str "orgs/" org-name "/" (.-name %)))))))
       (filter #(fs/existsSync (path/join orgs-root % ".git")))
       vec))

;; ---------------------------------------------------------------------- main

(defn -main []
  (let [t0 (.now js/Date)
        load1 (first (js->clj (os/loadavg)))
        entries (parse-west "manifest/west.yml")
        by-org (group-by :remote entries)
        _ (log! "west.yml: " (count entries) " project entries across "
                (count by-org) " orgs; load1=" (.toFixed load1 1))

        ;; 1. batched org listings
        listings (into {}
                       (map (fn [[org es]]
                              (log! "listing org " org " (" (count es) " entries) ...")
                              (let [r (org-listing org)]
                                (log! "  org " org ": "
                                      (if (:error r) (str "FAILED: " (:error r))
                                          (str (count (:repos r)) " repos live")))
                                [org r])))
                       (sort-by first by-org))

        ;; 2. names absent from their org's listing need a redirect-following GET
        stale (vec (for [e entries
                         :let [live (get-in listings [(:remote e) :repos (gh-name e)])]
                         :when (nil? live)]
                     e))
        _ (log! (count stale) " entr(ies) name a repo that is not in its org listing"
                " -- resolving each (budget " budget ", used " @api-calls ")")

        cache0 (read-cache)
        resolved (atom cache0)
        _ (doseq [[i e] (map-indexed vector stale)
                  :let [slug (str (:remote e) "/" (gh-name e))]]
            (if-let [hit (get @resolved slug)]
              (log! "  [" (inc i) "/" (count stale) "] " slug " (cached) -> "
                    (or (:id hit) (:error hit)))
              (let [r (resolve-slug slug)]
                (swap! resolved assoc slug (assoc r :at (.now js/Date)))
                (log! "  [" (inc i) "/" (count stale) "] " slug " -> "
                      (or (:full-name r) (str "UNRESOLVED: " (:error r)))))))
        _ (write-cache! @resolved)

        ;; 3. attach an id to every entry
        with-id (mapv (fn [e]
                        (let [slug (str (:remote e) "/" (gh-name e))
                              live (get-in listings [(:remote e) :repos (gh-name e)])
                              hit (or live (get @resolved slug))]
                          (assoc e :slug slug
                                 :id (:id hit)
                                 :full-name (:full-name hit)
                                 :unresolved (when-not (:id hit)
                                               (or (:error hit)
                                                   (get-in listings [(:remote e) :error])
                                                   "no id")))))
                      entries)
        unresolved (filterv :unresolved with-id)
        groups (->> (group-by :id (remove :unresolved with-id))
                    (filter (fn [[_ v]] (> (count v) 1)))
                    (sort-by (fn [[_ v]] (:full-name (first v)))))

        ;; 4. divergence between the pins of each duplicate group
        divs (when compare?
               (into {}
                     (for [[id members] groups
                           :let [full (:full-name (first members))
                                 sorted (sort-by :name members)]]
                       [id (vec (for [[a b] (partition 2 1 sorted)]
                                  (do (log! "compare " full " " (:name a) "..." (:name b))
                                      (assoc (divergence full (:rev a) (:rev b))
                                             :from (:name a) :to (:name b)))))])))

        dupe-entries (reduce + (map (comp count second) groups))
        elapsed (/ (- (.now js/Date) t0) 1000.0)]

    ;; ------------------------------------------------------------- report
    (println (str "SCANNED\t" (count entries) "\twest-entries"))
    (println (str "scanned " (count entries) " west entries; " (count groups)
                  " GitHub id(s) registered more than once, covering "
                  dupe-entries " entries"))
    ;; `gh-invocations`, not HTTP requests: one `--paginate` invocation makes
    ;; one request per 100 repos. Measured 2026-08-13, the 8 org listings are 9
    ;; invocations and about 48 HTTP requests. Quoting the smaller number as
    ;; `API calls` would understate the load on a token shared with every
    ;; concurrent session, which is the thing the budget exists to protect.
    (println (str "gh-invocations\t" @api-calls "\tbudget\t" budget
                  "\tunresolved\t" (count unresolved)
                  "\tmanifest-phase-s\t" (.toFixed elapsed 1)
                  "\tload1\t" (.toFixed load1 1)))
    (println)

    (doseq [[id members] groups]
      (let [full (:full-name (first members))]
        (println (str "DUPLICATE  id=" id "  " full))
        (doseq [m (sort-by :name members)]
          (println (str "           " (:name m) "  remote=" (:remote m)
                        "  pin=" (subs (:rev m) 0 (min 8 (count (:rev m))))
                        "  path=" (:path m)
                        (if (fs/existsSync (path/join orgs-root (:path m))) "  [on-disk]" "  [absent]"))))
        (when compare?
          (doseq [d (get divs id)]
            (println (str "           divergence " (:from d) "..." (:to d) ": "
                          (if (:error d) (str "COMPARE FAILED: " (:error d))
                              (str (:status d) " ahead_by=" (:ahead d)
                                   " behind_by=" (:behind d)))))))
        (println)))

    (when (seq unresolved)
      (println (str "UNRESOLVED " (count unresolved)
                    " entr(ies) -- these were NOT checked and this run is not clean:"))
      (doseq [e (take 60 unresolved)]
        (println (str "           " (:slug e) "  " (:unresolved e))))
      (when (> (count unresolved) 60)
        (println (str "           ... and " (- (count unresolved) 60) " more")))
      (println))

    (when @budget-hit
      (println (str "BUDGET-EXHAUSTED after " budget
                    " api calls -- raise --budget or wait out the rate limit."))
      (println))

    (when disk?
      ;; A duplicate REGISTRATION is not the only way one repository ends up on
      ;; disk twice. A rename also strands the old checkout at the old path,
      ;; and west simply stops mentioning it -- so `west update` never touches
      ;; it again and it sits at whatever commit it had. It is invisible to the
      ;; manifest and fully visible to anything that walks `orgs/`, which is
      ;; where the 2026-08-13 namespace survey found 19 of its 28 co-classpath
      ;; collisions. Classifying an orphan needs its remote, one `git` call.
      (let [dirs (disk-checkouts)
            declared-paths (set (map :path entries))
            declared-slugs (into {} (map (fn [e] [(str/lower-case (str (:remote e) "/" (gh-name e)))
                                                  (:path e)])) entries)
            orphans (vec (remove declared-paths dirs))
            _ (log! "disk scan: " (count dirs) " checkouts, " (count orphans)
                    " not declared -- resolving each remote")
            classified (vec (for [[i o] (map-indexed vector (sort orphans))
                                  :let [slug (remote-slug (path/join orgs-root o)
                                                          (second (str/split o #"/")))
                                        shadows (get declared-slugs (some-> slug str/lower-case))]]
                              (do (when (zero? (mod i 50))
                                    (log! "  orphan " i "/" (count orphans)))
                                  {:dir o :slug slug :shadows shadows})))
            ;; Slug matching alone finds nothing, and that is the whole point:
            ;; a stranded checkout carries the OLD slug in its remote while the
            ;; declared entry carries the new one. Measured 2026-08-13: 0 of 249
            ;; orphans matched by slug. Only the id closes it, which costs one
            ;; lookup per orphan -- hence opt-in.
            declared-ids (into {} (keep (fn [e] (when (:id e) [(:id e) (:path e)]))) with-id)
            classified (if-not disk-ids?
                         classified
                         (vec (for [[i c] (map-indexed vector classified)]
                                (if (or (:shadows c) (nil? (:slug c)))
                                  c
                                  (let [hit (or (get @resolved (:slug c))
                                                (let [r (resolve-slug (:slug c))]
                                                  (swap! resolved assoc (:slug c)
                                                         (assoc r :at (.now js/Date)))
                                                  r))]
                                    (log! "  orphan-id [" (inc i) "/" (count classified) "] "
                                          (:slug c) " -> " (or (:id hit) (:error hit)))
                                    (assoc c :id (:id hit)
                                           :shadows (get declared-ids (:id hit))
                                           :by-id true))))))
            _ (when disk-ids? (write-cache! @resolved))
            ;; Whether the two checkouts of one repository actually SHOW the
            ;; same code is a local question -- no API call. `west update`
            ;; maintains only the declared path, so the undeclared twin sits at
            ;; whatever commit it had when it was cloned.
            classified (mapv (fn [c]
                               (if-not (:shadows c)
                                 c
                                 (let [a (sh (str "git -C " (path/join orgs-root (:dir c))
                                                  " rev-parse HEAD"))
                                       b (sh (str "git -C " (path/join orgs-root (:shadows c))
                                                  " rev-parse HEAD"))]
                                   (assoc c :head-a a :head-b b :same-head (and a b (= a b))))))
                             classified)
            shadowing (filter :shadows classified)
            divergent (filter #(and (:shadows %) (not (:same-head %))) classified)]
        (println (str "DISK\t" (count dirs) "\tcheckouts\t"
                      (count orphans) "\tnot declared in west.yml\t"
                      (count shadowing) "\tshadow a declared entry\t"
                      (count divergent) "\tof those show different code"))
        (doseq [c (sort-by :dir classified)]
          (println (str "           " (if (:shadows c) "SHADOW " "ORPHAN ")
                        (:dir c) "  remote=" (or (:slug c) "?")
                        (when (:id c) (str "  id=" (:id c)))
                        (when (:shadows c) (str "  also-checked-out-at=" (:shadows c)))
                        (when (:shadows c)
                          (if (:same-head c) "  same-head"
                              (str "  DIVERGENT head=" (some-> (:head-a c) (subs 0 8))
                                   " vs " (some-> (:head-b c) (subs 0 8))))))))
        (println)))

    (when findings?
      (doseq [[id members] groups]
        (let [full (:full-name (first members))
              names (str/join "," (sort (map :name members)))
              pins (str/join "," (map #(subs (:rev %) 0 8) (sort-by :name members)))
              ;; Both directions count. `compare A...B` reports `behind` when B
              ;; is the older pin, so keying severity on `ahead_by` alone read
              ;; the amu/compiler pair -- 62 commits apart -- as divergence 0.
              worst (when compare?
                      (apply max 0 (mapcat (fn [d]
                                             (keep #(some-> % (js/parseInt 10))
                                                   [(:ahead d) (:behind d)]))
                                           (get divs id))))
              sev (cond (and worst (> worst 0)) "high" :else "med")]
          (println (str "FINDING\t" sev "\tdup-id-" id
                        "\t" full " registered as " names " pins " pins
                        (when worst (str " max-divergence " worst))))))
      (doseq [e unresolved]
        (println (str "FINDING\tmed\tunresolved-" (:slug e)
                      "\tcould not resolve " (:slug e) ": " (:unresolved e)))))

    (println "Identity is the GitHub repository id, because the name is what lies:")
    (println "a renamed repo keeps answering under its old name via redirect.")

    (cond
      (or @budget-hit (seq unresolved))
      (do (log! "exit 2: evidence incomplete after "
                (.toFixed (/ (- (.now js/Date) t0) 1000.0) 1) "s")
          (set! (.-exitCode js/process) 2))

      (and check? (seq groups))
      (do (log! "exit 1: " (count groups) " duplicate registration(s)")
          (set! (.-exitCode js/process) 1))

      :else (log! "done in " (.toFixed (/ (- (.now js/Date) t0) 1000.0) 1)
                  "s total, " @api-calls " gh invocations"))))

(-main)

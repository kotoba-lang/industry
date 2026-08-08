#!/usr/bin/env nbb
;; gen-itonami-fleet-catalog.cljs — fold the cloud-itonami fleet's blueprint.edn
;; files into one catalog artifact that cloud-itonami-app can ship.
;;
;; Why an artifact rather than a live read: cloud-itonami-app is the
;; tenant-neutral application, released on its own. It cannot assume a west
;; checkout of ~1,340 sibling repositories exists on the machine running it, so
;; the catalog has to travel with it. Same shape as manifest/west.yml — a
;; generated file with a --check mode, never hand-edited.
;;
;; What the catalog can and cannot support:
;;
;;   blueprint.edn describes what an actor IS (id, domain, governor, maturity,
;;   ISIC/ISO-3166 coding). Until 2026-07-30 nothing in the fleet described
;;   where an actor is — across every blueprint there was no endpoint, URL,
;;   XRPC surface or DID. So a catalog built from these files is a DIRECTORY:
;;   it can answer "what exists, in what sector, at what maturity", and it
;;   cannot answer "call this actor" for any entry that has no
;;   :itonami.blueprint/endpoint.
;;
;;   Entries that do carry one are callable. That distinction is the single
;;   most important thing this catalog carries, and :callable? below makes it
;;   explicit rather than leaving every consumer to rediscover it.
;;
;; Deliberately NOT recorded: a generation timestamp. It would make every
;; regeneration a diff and defeat --check.
;;
;;   nbb scripts/gen-itonami-fleet-catalog.cljs [--out PATH] [--rules PATH] [--check]

(ns gen-itonami-fleet-catalog
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            [cljs.reader :as reader]))

(def ^:private fleet-dir "orgs/cloud-itonami")
(def ^:private west-file "manifest/west.yml")
(def ^:private default-rules "manifest/repository-rules.edn")
;; Overridable so the generator can be run against an edited authority before
;; that edit has landed — the fleet is read relative to the superproject root,
;; and the rules need not be.
(def ^:private rules-file (atom default-rules))
(def ^:private default-out
  "orgs/cloud-itonami/cloud-itonami-app/resources/itonami-fleet-catalog.edn")

;; Kept in the catalog. The rest of the blueprint keys stay in the repositories:
;; this is a directory, not a mirror, and a consumer that needs the full record
;; should read the blueprint itself.
(def ^:private carried
  [:itonami.blueprint/id
   :itonami.blueprint/name
   :itonami.blueprint/domain
   :itonami.blueprint/governor
   :itonami.blueprint/maturity
   :itonami.blueprint/status
   :itonami.blueprint/isic
   :itonami.blueprint/isic-rev4
   :itonami.blueprint/isic-rev5
   :itonami.blueprint/isco-08
   :itonami.blueprint/iso3166
   :itonami.blueprint/municipality
   :itonami.blueprint/social-impact
   :itonami.blueprint/required-technologies
   :itonami.blueprint/robotics
   :itonami.blueprint/orchestrator
   :itonami.blueprint/endpoint
   :itonami.blueprint/endpoint-kind
   :itonami.blueprint/health-path
   ;; ADR-2608093000 D1. A "connect this app" surface needs two things the
   ;; catalog did not carry: where the app lives under the shared host, and
   ;; which organization owns it. :surface (the route list) is deliberately
   ;; NOT carried — that is the mirror this file refuses to be, and a caller
   ;; needing routes should read the blueprint.
   :itonami.blueprint/mount
   :itonami.blueprint/org])

(defn- short-key [k] (keyword (name k)))

(def ^:private prefix-rules
  "Role/execution rules read from the workspace authority, not restated here.
  ADR-2607299000 moved this vocabulary into the superproject precisely so a
  script would stop being a second place the taxonomy lives."
  (delay
    (let [doc (reader/read-string (fs/readFileSync @rules-file "utf8"))
          ;; :name-prefix is a :vocabulary/id VALUE, not a key — the file is a
          ;; map whose :vocabularies is a vector of vocabulary maps.
          np (some #(when (= :name-prefix (:vocabulary/id %)) %) (:vocabularies doc))]
      (when-not np
        (throw (ex-info "no :name-prefix vocabulary in the authority" {:file @rules-file})))
      (vec (:vocabulary/rules np)))))

(defn- role+execution
  "Match a repository name against the authority's prefix rules.

  Returns nil when no rule matches, and nil is deliberate. 459 repositories
  match cloud-itonami-isic- and are :on-demand; the marketplace, assoc and
  municipality families match nothing yet. Guessing :on-demand for them because
  they happen to be reachable would turn an unfinished taxonomy into a
  confident-looking answer — the absence is the honest signal that the
  vocabulary does not cover them."
  [dir]
  (some (fn [{:keys [prefix role execution authority-library]}]
          (when (and prefix (str/starts-with? dir prefix))
            (cond-> {}
              role (assoc :role role)
              execution (assoc :execution execution)
              authority-library (assoc :authority-library authority-library))))
        @prefix-rules))

(def ^:private deploy-configs
  "Filename → the deploy path it declares. Presence is a FACT read off the
  repository, never an inference: a repo that ships `wrangler.jsonc` has a
  Cloudflare deploy path an operator can actually run, and one that does not
  has none that this generator can see.

  Fourteen of ~1,200 repositories carry one. That number is the honest answer
  to an operator asking \"can I deploy this?\" — for almost every blueprint the
  answer is that they would be building the deployment themselves, and a UI
  that offered a deploy button anyway would be inventing a path. Same rule as
  `:endpoint`: never guess an address, and never guess a way to create one."
  {"wrangler.jsonc" :cloudflare
   "wrangler.toml" :cloudflare
   "fly.toml" :fly
   "Dockerfile" :container})

(defn- deploy-config
  "The deploy paths a repository actually ships, or nil."
  [dir]
  (let [found (into [] (keep (fn [[f kind]]
                               (when (fs/existsSync (path/join fleet-dir dir f)) kind)))
                    deploy-configs)]
    (when (seq found) (vec (distinct found)))))

(defn- read-blueprint
  "Classify one blueprint.edn: {:entry m}, {:non-actor {...}} or {:skipped {...}}.

  Two different populations share this filename, which is worth knowing before
  reading any count here. 804 repositories describe ACTORS with
  :itonami.blueprint/* keys. 534 describe LEGAL ENTITIES with :company/* keys
  — legal-name, lei, jurisdiction, website, ticker — and are not actors at all;
  they are the `cloud-itonami-lei` dataset the docs EDN plane already loads and
  joins on :company/lei.

  Those 534 are counted, not warned about. Calling them \"skipped\" would report
  a working dataset as breakage and bury any real parse failure in the noise.
  A file that is neither shape IS a real problem and does get warned."
  [dir]
  (let [p (path/join fleet-dir dir "blueprint.edn")]
    (when (fs/existsSync p)
      (try
        (let [raw (reader/read-string (fs/readFileSync p "utf8"))
              ;; A third dialect: the cloud-itonami-isic-* blueprints are
              ;; wrapped in a vector — `[{:itonami.blueprint/id ...}]`, the
              ;; same tx-data shape 90-docs/adr uses. The contents are ordinary
              ;; actors. Unwrapping here rather than treating them as malformed
              ;; is the difference between cataloging 804 actors and 1,183.
              m (if (and (vector? raw) (map? (first raw))) (first raw) raw)]
          (cond
            (not (map? m))
            {:skipped {:repo dir :reason "not a map"}}

            (string? (:itonami.blueprint/id m))
            ;; :repo is carried because :id is NOT unique — three repositories
            ;; declare an id that another already owns. Without the directory
            ;; name a consumer cannot tell the colliding entries apart, and a
            ;; lookup by id silently returns whichever sorted first.
            {:entry (into (merge (sorted-map :repo dir)
                                 (role+execution dir)
                                 (when-some [d (deploy-config dir)] {:deploy-config d}))
                          (keep (fn [k]
                                  (when-some [v (get m k)]
                                    [(short-key k) v])))
                          carried)}

            (:company/lei m)
            {:non-actor {:repo dir :kind :company :lei (:company/lei m)}}

            :else
            {:skipped {:repo dir :reason "neither an actor blueprint nor a company record"}}))
        (catch :default e
          {:skipped {:repo dir :reason (str "parse error: " (.-message e))}})))))

(defn- west-projects
  "name -> {:repo :remote :revision :path :groups} from manifest/west.yml.

  west is the reference plane: it already pins every repository in the
  workspace to a revision, so an entry can point at a repo and a hash without
  the catalog carrying any of its content. That is the whole mechanism for
  including the resident actors — person-* and loop-* have no blueprint.edn
  and are not going to grow one just to be listed."
  []
  (let [lines (str/split-lines (fs/readFileSync west-file "utf8"))]
    (loop [ls lines cur nil out {}]
      (if-let [l (first ls)]
        (let [t (str/trim l)]
          (cond
            (str/starts-with? t "- name:")
            (recur (rest ls) {:name (str/trim (subs t 7))} (cond-> out cur (assoc (:name cur) cur)))
            (and cur (str/starts-with? t "remote:"))
            (recur (rest ls) (assoc cur :remote (str/trim (subs t 7))) out)
            (and cur (str/starts-with? t "revision:"))
            (recur (rest ls) (assoc cur :revision (str/trim (subs t 9))) out)
            (and cur (str/starts-with? t "path:"))
            (recur (rest ls) (assoc cur :path (str/trim (subs t 5))) out)
            :else (recur (rest ls) cur out)))
        (cond-> out cur (assoc (:name cur) cur))))))

(defn- evidence-of
  "The newest evidence a resident actor recorded: {:evidence-at :evidence-entries}.

  `repository-rules.edn` makes :record-evidence an obligation of every loop-*, and
  the convention is an append-only `evidence/*.ledger.edn`. This reads the last
  line's timestamp rather than the file mtime, because a git checkout rewrites
  mtimes and would report every freshly-cloned loop as having just run.

  nil when the actor keeps no ledger — absent evidence is reported as absent, not
  as zero, so a loop that has never run cannot be read as one that ran and found
  nothing."
  [repo-path]
  (when repo-path
    (let [dir (path/join repo-path "evidence")]
      (when (fs/existsSync dir)
        (let [ledgers (->> (seq (fs/readdirSync dir))
                           (filter #(str/ends-with? % ".ledger.edn"))
                           sort)]
          (when-some [f (last ledgers)]
            (let [lines (->> (str/split-lines (fs/readFileSync (path/join dir f) "utf8"))
                             (remove str/blank?))]
              (when-some [last-line (last lines)]
                (let [e (try (reader/read-string last-line) (catch :default _ nil))]
                  (cond-> {:evidence-entries (count lines)}
                    (:tick/at e) (assoc :evidence-at (:tick/at e))
                    (:tick/outcome e) (assoc :evidence-outcome (:tick/outcome e))))))))))))

(defn- reference
  "The pin: repository name, remote and revision. Never any content."
  [w]
  (when w
    (cond-> {}
      (:name w) (assoc :repo-name (:name w))
      (:remote w) (assoc :remote (:remote w))
      (:revision w) (assoc :revision (:revision w))
      (:path w) (assoc :path (:path w)))))

(defn- commit-date
  "Author date of a pinned commit, via the GitHub API. nil if it cannot be read.

  Fetched only for the eight :resident actors. Pin age is a liveness proxy for
  something that runs a loop; for an on-demand agent that is merely undeployed
  it says nothing worth a network call. An earlier version fetched it for every
  reference entry, which was fine at eighteen and timed the generator out once
  the vocabulary matched most of west.

  What this is: the age of the PIN. What it is not: liveness. A loop- repo does
  commit what it produces (loop-system-dynamics carries evidence/ and ledger/
  and writes to them), so a pin that has not moved in months means either the
  loop stopped or nobody advanced the pin — both worth looking at, and this
  cannot tell them apart. Named :revision-committed-at rather than anything
  suggesting health."
  [remote name revision]
  (try
    (let [out (.execSync (js/require "child_process")
                         (str "gh api repos/" remote "/" name "/commits/" revision
                              " --jq .commit.author.date 2>/dev/null")
                         #js {:encoding "utf8" :timeout 20000})
          t (str/trim (str out))]
      (when (seq t) t))
    (catch :default _ nil)))

(defn- build []
  (let [west (west-projects)
        ;; Directories are enumerated FROM WEST, not from the filesystem.
        ;;
        ;; Scanning orgs/cloud-itonami/* was wrong and produced three phantom
        ;; actors, which then showed up as three colliding ids. None of the
        ;; three was a duplicate repository:
        ;;   cloud-itonami-isic-7500  — a stale checkout under the pre-rename
        ;;     name; GitHub reports its canonical .name as cloud-itonami-isic-750
        ;;   cloud-itonami-commitment-ledger-component — 404 on GitHub
        ;;   cloud-itonami-marketplace-order-codex     — 404 on GitHub
        ;; The last two were never pushed; they are local working directories.
        ;;
        ;; west registers exactly one of each, which is the whole reason it is
        ;; the reference plane. Trusting the filesystem instead let a stale
        ;; rename and two unpushed scratch directories into a shipped artifact.
        dirs (->> (vals west)
                  (keep :path)
                  (filter #(str/starts-with? % (str fleet-dir "/")))
                  (map #(last (str/split % #"/")))
                  (filter #(fs/existsSync (path/join fleet-dir % "blueprint.edn")))
                  sort)
        results (keep read-blueprint dirs)
        entries (vec (sort-by :id (keep :entry results)))
        skipped (vec (sort-by :repo (keep :skipped results)))
        non-actors (vec (keep :non-actor results))
        callable (filterv :endpoint entries)
        ;; Every entry gains its west pin. An actor that lives in
        ;; orgs/cloud-itonami is looked up by directory name.
        entries (mapv (fn [e] (merge e (reference (get west (:repo e))))) entries)
        ;; Repositories the authority classifies as runnable but which carry no
        ;; blueprint.edn — person-*, loop-*, skill-*, action-* — enter the
        ;; catalog BY REFERENCE. They contribute a repo, a remote and a pinned
        ;; revision, plus the role and execution the prefix rule already
        ;; determines. No file is read from them and no code is copied: the
        ;; hash is the whole payload, and it is west's, already verified there.
        ;;
        ;; Libraries (:execution :none) are excluded — there are ~3,500 of them
        ;; and a directory of things that do not run is a different artifact.
        ;; A library that an actor depends on is reachable through that actor's
        ;; :authority-library instead.
        have (into #{} (map :repo) entries)
        referenced
        (vec (for [[nm w] (sort west)
                   :let [rx (role+execution (or (some-> (:path w) (str/split #"/") last) nm))]
                   :when (and rx
                              (not= :none (:execution rx))
                              (not (contains? have (some-> (:path w) (str/split #"/") last))))]
               (merge (sorted-map :repo (or (some-> (:path w) (str/split #"/") last) nm)
                                  :id nm
                                  :reference-only true)
                      rx
                      (reference w)
                      ;; Only for :resident actors — eight of them. Pin age is
                      ;; a liveness proxy for something that runs a loop, and
                      ;; means little for an on-demand agent that is simply not
                      ;; deployed yet. Fetching it for every reference entry
                      ;; meant hundreds of gh calls and a generator that timed
                      ;; out once the vocabulary started matching most of west.
                      (when (= :resident (:execution rx))
                        (when-some [d (commit-date (:remote w) nm (:revision w))]
                          {:revision-committed-at d}))
                      ;; Liveness for a resident actor. cloud.itonami.app.fleet
                      ;; says so itself: a loop-* has no HTTP surface by design,
                      ;; so "is it alive" is "did it record evidence recently",
                      ;; and the catalog did not carry that. A pin date is a poor
                      ;; proxy — it says when someone last committed, not when
                      ;; the loop last ran, and those diverge the moment the loop
                      ;; is healthy and nobody is editing it.
                      (when (= :resident (:execution rx))
                        (evidence-of (some-> (:path w))))
                      ;; The model this actor embodies, as a pin rather than a
                      ;; dependency: resolved from the authority's
                      ;; :authority-library through west.
                      (when-some [lib (:authority-library rx)]
                        {:authority-library
                         (or (reference (get west lib)) {:repo-name lib})}))))
        entries (vec (sort-by (juxt :repo :id) (into entries referenced)))
        dup-ids (->> entries (map :id) frequencies
                     (keep (fn [[id n]] (when (> n 1) id)))
                     sort vec)]
    {:catalog {:schema "cloud.itonami.fleet-catalog.v1"
               :source "cloud-itonami/*/blueprint.edn"
               :generator "scripts/gen-itonami-fleet-catalog.cljs"
               :count (count entries)
               :callable-count (count callable)
               ;; Recorded so a reader can tell this catalog apart from the
               ;; repository count: not every repo with a blueprint.edn is an
               ;; actor.
               :company-record-count (count non-actors)
               ;; Shipped so a consumer can see the collisions instead of
               ;; discovering them through a wrong lookup.
               :duplicate-ids dup-ids
               :actors entries}
     :non-actors non-actors
     :dup-ids dup-ids
     :skipped skipped}))

(defn- render [catalog]
  ;; One actor per line: the file is ~1,300 entries and a pretty-printer would
  ;; make every diff unreadable. Line-per-entry keeps `git diff` to the actors
  ;; that actually changed.
  (str ";; GENERATED by scripts/gen-itonami-fleet-catalog.cljs — do not hand-edit.\n"
       ";; Regenerate: nbb scripts/gen-itonami-fleet-catalog.cljs\n"
       ";; Verify:     nbb scripts/gen-itonami-fleet-catalog.cljs --check\n"
       ";;\n"
       ";; :endpoint is present only for actors that are deployed and answering.\n"
       ";; An entry without one is a directory record, not something to call.\n"
       "{:schema " (pr-str (:schema catalog)) "\n"
       " :source " (pr-str (:source catalog)) "\n"
       " :generator " (pr-str (:generator catalog)) "\n"
       " :count " (:count catalog) "\n"
       " :callable-count " (:callable-count catalog) "\n"
       " :company-record-count " (:company-record-count catalog) "\n"
       " :duplicate-ids " (pr-str (:duplicate-ids catalog)) "\n"
       " :actors\n ["
       (str/join "\n  " (map pr-str (:actors catalog)))
       "]}\n"))

(defn -main [& args]
  (let [args (vec args)
        check? (some #{"--check"} args)
        out (or (second (drop-while #(not= "--out" %) args)) default-out)
        _ (when-some [r (second (drop-while #(not= "--rules" %) args))]
            (reset! rules-file r))
        {:keys [catalog skipped non-actors dup-ids]} (build)
        text (render catalog)]
    ;; Skipped files are reported to stderr, always. A loader that swallows
    ;; shape mismatches makes `:count` read as "everything is here" when it is
    ;; not — the same trap the docs EDN plane hit with tos.journal.edn.
    (when (seq non-actors)
      (println "note:" (count non-actors)
               "repos carry a blueprint.edn that is a :company/* legal-entity"
               "record, not an actor — they belong to the cloud-itonami-lei"
               "dataset and are counted, not cataloged."))
    (when (seq dup-ids)
      (binding [*print-fn* *print-err-fn*]
        (println "WARNING:" (count dup-ids)
                 "id(s) are declared by more than one repository — a lookup by"
                 "id cannot resolve these:")
        (doseq [id dup-ids] (println "  -" id))))
    (when (seq skipped)
      (binding [*print-fn* *print-err-fn*]
        (println "WARNING:" (count skipped) "blueprint(s) skipped:")
        (doseq [s skipped] (println "  -" (:repo s) "—" (:reason s)))))
    (if check?
      (let [current (when (fs/existsSync out) (fs/readFileSync out "utf8"))]
        (if (= current text)
          (println "catalog OK —" (:count catalog) "actors,"
                   (:callable-count catalog) "callable")
          (do (binding [*print-fn* *print-err-fn*]
                (println "catalog STALE —" out "does not match the generator."))
              (js/process.exit 1))))
      (do (fs/mkdirSync (path/dirname out) #js {:recursive true})
          (fs/writeFileSync out text)
          (println "wrote" out "—" (:count catalog) "actors,"
                   (:callable-count catalog) "callable,"
                   (count skipped) "skipped")))))

(apply -main *command-line-args*)

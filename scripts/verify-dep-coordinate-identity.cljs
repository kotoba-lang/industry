#!/usr/bin/env nbb
(ns verify-dep-coordinate-identity
  "One repository, reachable under two dependency coordinates.

  ## The failure this exists for

  tools.deps dedupes by the coordinate SYMBOL. Two symbols naming the same
  GitHub repository are two libraries to it, so both land on the classpath, at
  whatever sha each was pinned to, and the same namespaces are loaded twice
  from two revisions. Nothing errors. The suite is green on a classpath
  tools.deps would never have built from a single coordinate.

  Measured 2026-08-20 across 4,460 checkouts: 2,665 in-house git dependencies
  naming 421 repositories, of which **25 are reached under more than one
  coordinate**. The two largest are `kotoba-lang/langgraph` (454 references,
  as both `io.github.kotoba-lang/langgraph` and
  `io.github.com-junkawasaki/langgraph-clj`) and
  `kotoba-lang/jp-go-digital-design-system` (447, also as `jp-go-dds`).

  This is what the fleet's `nbb-cross-runtime` gate reports as a long
  `CONFLICT:` line -- aiueos reaches roughly fifteen libraries at two shas
  each -- and it is also how a repository stays pinned behind a fix that
  already exists: two names means two pins to move, and moving one looks done.

  ## Two shapes, both reported

  - **rename-shaped**: the old repository name survives as a coordinate after
    the repository was renamed. `compiler` (now amu), `jp-go-dds`,
    `langgraph-clj`, `ed25519`, `langchain-clj`, `mcp`, `svg`.
  - **group-shaped**: same name, different group segment -- `kotoba-lang/css`
    beside `io.github.kotoba-lang/css`, `org.kotoba-lang/io-multiformats`,
    `com.etzhayyim/ie-flow` beside `com-etzhayyim/ie-flow`. A bare group has
    no derivable URL, so those entries carry an explicit `:git/url` and work;
    they are still a second identity for one library.

  ## How identity is decided, stated because the answer depends on it

  A dependency's repository is its explicit `:git/url` when it has one,
  normalised to `org/repo` with the scheme and any `.git` suffix removed and
  lower-cased. With no `:git/url`, tools.deps derives the URL from the
  coordinate, so the coordinate minus its `io.github.` prefix IS the
  repository. Anything else would compare names to names and find nothing.

  Only in-house orgs are examined. A third-party library legitimately appears
  under whatever coordinate its author published.

  ## What it reads, and what that costs

  Working trees, not `origin/main`. A finding therefore describes the deps.edn
  that is CHECKED OUT, which in this workspace is regularly not the one that
  exists upstream -- checkout, west pin and the repository's main are three
  different things.

  Measured the first time this ran: it reported
  `orgs/etzhayyim/com-etzhayyim-isic`'s deps.edn as unparseable, and it was --
  in a checkout four weeks behind, at the initial scaffold. That repository's
  main had already closed the unterminated vector. The finding was true of the
  tree and false of the repository.

  Reading `origin/main` instead would trade this for a worse problem: it would
  report on code nobody here can run, and it would need a fetch per checkout.
  So the tree is the right input and the caveat belongs here rather than in a
  reader's memory: before acting on a finding, check the repository's main.

  ## Refusing to answer

  Zero checkouts, or zero deps.edn files, is a detector pointed at the wrong
  tree rather than a clean workspace. Both exit 2. A deps.edn that does not
  parse is counted and reported, never silently treated as having no
  dependencies -- that would turn an unreadable file into a clean one."
  (:require ["fs" :as fs] ["path" :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def argv (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") argv)) "."))

(def in-house
  #{"kotoba-lang" "cloud-itonami" "com-junkawasaki" "etzhayyim"
    "gftdcojp" "net-kotobase" "network-awai" "jk-luxury"})

(defn- die-unanswered [msg]
  (println (str "UNANSWERED\t" msg))
  (js/process.exit 2))

(defn- checkouts []
  (let [o (p/join root "orgs")]
    (when-not (fs/existsSync o) (die-unanswered (str "no orgs/ under " root)))
    (for [org (sort (fs/readdirSync o))
          :let [od (p/join o org)]
          :when (.isDirectory (fs/statSync od))
          repo (sort (fs/readdirSync od))
          :let [rd (p/join od repo)]
          :when (fs/existsSync (p/join rd ".git"))]
      rd)))

(defn- dep-entries
  "Every dependency entry anywhere in the map, at any nesting depth -- aliases
  put them two and three levels down.

  BOTH coordinate kinds, because the first version of this took only
  `:git/sha` and therefore could not see the larger half. A `:local/root`
  entry names a repository just as a git coordinate does; it simply names it
  by directory. `io.github.com-junkawasaki/langchain-clj {:local/root
  \"../../kotoba-lang/langchain\"}` is a coordinate that says one repository
  and a path that resolves to another, and tools.deps keys on the coordinate."
  [m path]
  (reduce-kv (fn [acc k v]
               (cond
                 (and (map? v) (contains? v :git/sha))
                 (conj acc {:lib (str k) :sha (:git/sha v) :url (:git/url v)
                            :kind :git :in path})
                 (and (map? v) (contains? v :local/root))
                 (conj acc {:lib (str k) :root (:local/root v) :kind :local :in path})
                 (map? v) (into acc (dep-entries v path))
                 :else acc))
             [] m))

(defn repo-of
  "The repository a dependency entry resolves to. Exported because the whole
  report depends on it and it is the part most likely to be wrong.

  Three inputs, in order of authority: an explicit `:git/url`; a
  `:local/root` path, normalised against the declaring repository and read
  back as `orgs/<org>/<repo>`; otherwise the coordinate itself, which is what
  tools.deps derives a URL from."
  [{:keys [lib url root in]}]
  (-> (cond
        (seq (str url))
        (-> (str url) (str/replace #"^https?://github\.com/" "") (str/replace #"\.git$" ""))

        (seq (str root))
        (let [abs (p/normalize (p/join (str in) (str root)))
              segs (str/split abs #"/")
              i (.lastIndexOf (into-array segs) "orgs")]
          (if (and (>= i 0) (> (count segs) (+ i 2)))
            (str (nth segs (inc i)) "/" (nth segs (+ i 2)))
            ;; A path that leaves orgs/ names something this check cannot
            ;; identify. Fall back to the coordinate rather than inventing one.
            (str/replace lib #"^io\.github\." "")))

        :else (str/replace lib #"^io\.github\." ""))
      str/lower-case))

(defn -main []
  (let [dirs (vec (checkouts))
        _ (when (zero? (count dirs)) (die-unanswered (str "0 checkouts under " root "/orgs")))
        files (filterv #(fs/existsSync (p/join % "deps.edn")) dirs)
        _ (when (zero? (count files))
            (die-unanswered (str (count dirs) " checkouts, none with a deps.edn")))
        parsed (for [d files]
                 (let [f (p/join d "deps.edn")]
                   (try {:dir d :deps (dep-entries (edn/read-string (fs/readFileSync f "utf8")) d)}
                        (catch :default e {:dir d :error (.-message e)}))))
        parsed (vec parsed)
        broken (filterv :error parsed)
        rows (->> parsed (remove :error) (mapcat :deps)
                  (filter #(in-house (first (str/split (repo-of %) #"/")))))
        by-repo (group-by repo-of rows)
        multi (->> by-repo
                   (filter (fn [[_ rs]] (> (count (set (map :lib rs))) 1)))
                   (sort-by (fn [[_ rs]] (- (count rs)))))]
    (println (str "SCANNED\t" (count rows) "\tin-house deps (git + local-root) in "
                  (count files) " deps.edn of " (count dirs) " checkouts"
                  (when (seq broken) (str "\tUNPARSEABLE\t" (count broken)))))
    ;; LATENT vs ACTIVE. "50 repositories are reachable under two coordinates"
    ;; is a count of names, not of damage, and the two are not the same
    ;; number: a collision only happens when ONE dependency graph pulls both
    ;; coordinates. Reporting only the name count invites multiplying a pool
    ;; by a guess (ADR-2607203000), so the pool is split here by measurement.
    ;;
    ;; This counts DIRECT co-occurrence -- two coordinates for one repository
    ;; named in the same deps.edn -- which is a LOWER BOUND. Transitive
    ;; co-occurrence (A needs X, and A needs B which needs X under the other
    ;; name) also collides and is not resolved here, because resolving it
    ;; means building the real dependency graph. The bound is stated rather
    ;; than the gap being left silent.
    (let [active (for [[repo rs] multi
                       :let [per-dir (group-by :in rs)
                             clashing (for [[d es] per-dir
                                            :when (> (count (set (map :lib es))) 1)]
                                        {:dir d :libs (sort (set (map :lib es)))})]
                       :when (seq clashing)]
                   {:repo repo :clashing (vec clashing)})
          active (vec active)]
      (println (str "CENSUS\trepos=" (count by-repo) "\tmulti-coordinate=" (count multi)
                    "\tACTIVE=" (count active) " (both coordinates in one deps.edn)"
                    "\tLATENT=" (- (count multi) (count active))
                    " (two names across the fleet, no single graph yet takes both)"))
      (doseq [{:keys [repo clashing]} active]
        (doseq [{:keys [dir libs]} clashing]
          (println (str "FINDING\tfail\tactive-collision:" repo
                        "\t" dir " names " (str/join " and " libs)
                        " -- one classpath, the same namespaces from two revisions"))))
      (when (zero? (count active))
        (println (str "  no DIRECT collision: every multi-coordinate repository is named "
                      "under one coordinate per deps.edn. Transitive collisions are not "
                      "measured here, so this is a lower bound, not an all-clear."))))
    (doseq [b (take 5 broken)]
      (println (str "FINDING\twarn\tunparseable:" (:dir b)
                    "\tdeps.edn did not parse, so its dependencies were not examined: "
                    (:error b))))
    (doseq [[repo rs] multi]
      (println (str "FINDING\tfail\tmulti-coordinate:" repo
                    "\treached as " (str/join " and " (sort (set (map :lib rs))))
                    " across " (count rs) " references"
                    " -- tools.deps dedupes by coordinate, so both can land on one classpath")))
    (println)
    (let [n (+ (count multi) (count broken))]
      (if (pos? n)
        (do (println (str n " finding(s): " (count multi)
                          " repositor(y|ies) reachable under two coordinates"
                          (when (seq broken) (str ", " (count broken) " unparseable deps.edn"))))
            (js/process.exit 1))
        (println (str "OK — all " (count by-repo)
                      " in-house repositories are reached under exactly one coordinate."))))))

(-main)

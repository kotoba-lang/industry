#!/usr/bin/env nbb
(ns verify-worker-name-collisions
  "Cloudflare Worker names claimed by more than one wrangler config, split by
  whether the configs agree about which hostnames the Worker serves.

  ## Why the split is the whole point

  A Worker is addressed by NAME at publish time. Two configs with the same name
  are two ways to overwrite one live Worker, and there is no fast-forward check
  the way there is for git -- the last publish wins. But the two cases are not
  equally bad, and reporting them as one thing buries the dangerous case under
  271 harmless ones:

    same routes       a duplicate checkout of the same service. Publishing the
                      stale one ships older code to the same hostnames. Bad,
                      recoverable, and very common here.

    DIFFERENT routes  which hostnames the live Worker serves depends on which
                      config was published last. Where one config claims a
                      hostname that a DIFFERENT Worker serves today, publishing
                      it is a topology change performed by a build command.

  ## What this was built from

  Measured 2026-08-18: `kotobase-protocols-worker` is declared by five configs
  with three different route sets across seven hostnames. One of them
  (net-kotobase-enterprise-wave2) claims `cypher.kotobase.net`, which is served
  today by the separate `net-kotobase-cypher` Worker. Publishing it would both
  replace the live protocols Worker and take cypher's hostname away from it.

  It is currently harmless only because that build cannot compile -- it is one
  of the 13 found by `verify-shadow-source-paths`. **Repairing its source-paths
  would arm it.** That is why the two detectors belong in one report: one of
  them prints a remedy the other says not to apply yet.

  usage:
    nbb scripts/verify-worker-name-collisions.cljs [<root>] [--findings] [--all]"
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.set :as set]
            [clojure.string :as str]))

(def argv (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") argv))
              "/Users/junkawasaki/github/com-junkawasaki"))
(def findings? (some #{"--findings"} argv))
(def all? (some #{"--all"} argv))

(defn- slurp* [f] (try (.readFileSync fs f "utf8") (catch :default _ nil)))

;; Paths that are not a repository's own configuration. An agent worktree and a
;; pre-adoption backup both carry real wrangler files, and counting them would
;; manufacture collisions nobody can act on.
(def ^:private excluded-segments
  [".claude/worktrees/" "/node_modules/" "/.git/" ".root-repository-adopt/"])

(defn- excluded? [p] (some #(str/includes? p %) excluded-segments))

(defn- walk-configs [dir]
  (if-not (.existsSync fs dir)
    []
    (->> (.readdirSync fs dir #js {:withFileTypes true})
         array-seq
         (mapcat (fn [e]
                   (let [p (.join path dir (.-name e))]
                     (cond
                       (excluded? (str p "/")) []
                       (.isDirectory e) (walk-configs p)
                       (contains? #{"wrangler.jsonc" "wrangler.json" "wrangler.toml"}
                                  (.-name e)) [p]
                       :else []))))
         vec)))

(defn- parse-config [f]
  (when-let [txt (slurp* f)]
    (if (str/ends-with? f ".toml")
      (when-let [n (second (re-find #"(?m)^\s*name\s*=\s*\"([^\"]+)\"" txt))]
        {:name n :routes (set (map second (re-seq #"pattern\s*=\s*\"([^\"]+)\"" txt)))})
      (let [c (try (js->clj (js/JSON.parse (str/replace txt #"(?m)^\s*//.*$" ""))
                            :keywordize-keys true)
                   (catch :default _ nil))]
        (when-let [n (:name c)]
          {:name n
           :routes (set (keep (fn [r] (if (map? r) (:pattern r) r)) (:routes c)))})))))

(defn -main []
  (let [files (walk-configs (.join path root "orgs"))
        parsed (keep (fn [f] (when-let [c (parse-config f)]
                               (assoc c :file (.relative path root f))))
                     files)
        dup (into {} (filter (fn [[_ v]] (> (count v) 1)) (group-by :name parsed)))
        reassigns (into {} (filter (fn [[_ v]] (> (count (set (map :routes v))) 1)) dup))
        same (into {} (remove (fn [[k _]] (contains? reassigns k)) dup))]
    (println (str "verify-worker-name-collisions: " (count files) " wrangler config(s), "
                  (count parsed) " named"))
    (println (str "SCANNED\t" (count parsed) "\tnamed wrangler config(s)"))
    (when (zero? (count parsed))
      (println "REFUSING to report a pass: read 0 named configs.")
      (js/process.exit 2))
    (println (str "  " (count dup) " name(s) declared by more than one config"))
    (println (str "    " (count same) " with IDENTICAL routes  — duplicate checkouts"))
    (println (str "    " (count reassigns) " with DIFFERENT routes — publishing one"
                  " REASSIGNS hostnames"))
    (println)
    (doseq [[n v] (sort-by (fn [[_ v]] (- (count (reduce into #{} (map :routes v)))))
                           (seq reassigns))]
      (let [hosts (reduce into #{} (map :routes v))]
        (println (str "  REASSIGNS  " n "  (" (count v) " configs, "
                      (count (set (map :routes v))) " distinct route sets, "
                      (count hosts) " hostname(s))"))
        (doseq [c (sort-by :file v)]
          (println (str "               [" (count (:routes c)) "] " (:file c))))
        (when findings?
          (println (str "FINDING\tfail\tworker::" n
                        "\t" (count v) " wrangler configs declare Worker `" n "` with "
                        (count (set (map :routes v))) " different route sets over "
                        (count hosts) " hostname(s); a Worker is addressed by name at"
                        " publish time, so which hostnames this Worker ends up serving"
                        " depends on which of these was published last: "
                        (str/join ", " (map :file (sort-by :file v))))))))
    (when (and all? (seq same))
      (println)
      (doseq [[n v] (sort-by key (seq same))]
        (println (str "  duplicate  " n "  (" (count v) " configs, same routes)"))
        (doseq [c (sort-by :file v)] (println (str "               " (:file c))))))
    (when (and findings? (seq same))
      (println (str "FINDING\twarn\tworker::same-routes-duplicates\t"
                    (count same) " Worker name(s) are declared by more than one config"
                    " with identical routes — publishing a stale copy ships older code"
                    " to the same hostnames (--all lists them)")))
    (println)
    (if (seq reassigns)
      (do (println (str (count reassigns) " Worker name(s) would reassign hostnames"
                        " depending on which config is published."))
          (js/process.exit 1))
      (println "OK — no Worker name is declared with conflicting routes."))))

(-main)

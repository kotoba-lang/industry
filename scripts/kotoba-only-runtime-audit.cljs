#!/usr/bin/env nbb
(require '[scripts.nbb-compat :refer [slurp spit file-seq format]]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str])

(def root
  (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))

(def ledger-path
  "90-docs/migration/kotoba-only-runtime-ledger.edn")

(def repos-path
  "manifest/repos.edn")

(def west-path
  "manifest/west.yml")

;; ledger-path/repos-path は manifest/edn-datomize.cljs により tx-data 形式に
;; 変換済み。元のトップレベル map を復元する。
(defn- unblob [v]
  (if (string? v)
    (try (let [parsed (edn/read-string v)] (if (coll? parsed) parsed v))
         (catch :default _ v))
    v))

(defn- reconstitute-entity [tx-data]
  (into {} (map (fn [[k v]] [(keyword (name k)) (unblob v)]))
        (dissoc (first tx-data) :db/id)))

(def ledger
  (reconstitute-entity (edn/read-string (slurp (io/file root ledger-path)))))

(def source-exts (:source-extensions ledger))
(def non-kotoba-exts (:non-kotoba-extensions ledger))
(def forbidden-kotoba-lang-artifacts (:forbidden-kotoba-lang-artifacts ledger))
(def forbidden-kotoba-lang-ci (:forbidden-kotoba-lang-ci ledger))
(def forbidden-root-ci (:forbidden-root-ci ledger))
(def excludes (:exclude-path-fragments ledger))
(def quarantined-legacy-path-fragments (:quarantined-legacy-path-fragments ledger))
(def quarantined-legacy-roots (:quarantined-legacy-roots ledger))
(def quarantine-scan-excludes (:quarantine-scan-exclude-path-fragments ledger))
(def repos (:repos ledger))

(def manifest-repos
  (reconstitute-entity (edn/read-string (slurp (io/file root repos-path)))))

(def west-paths
  (->> (str/split-lines (slurp (io/file root west-path)))
       (keep #(second (re-find #"^[ \t]+path:[ \t]+(.+?)[ \t]*$" %)))
       set))

(defn rel [f] (scripts.nbb-compat/relative-path root f))

(defn ext [path]
  (second (re-find #"(\.[^./]+)$" path)))

(defn excluded-by? [fragments path]
  (some #(str/includes? path %) fragments))

(defn excluded? [path]
  (excluded-by? excludes path))

(defn quarantined-legacy? [path]
  (excluded-by? quarantined-legacy-path-fragments path))

(defn children [dir]
  (seq (.listFiles dir)))

(defn walk-files [dir]
  (letfn [(walk [f]
            (let [path (rel f)]
              (cond
                (excluded? (str "/" path (when (.isDirectory f) "/")))
                nil

                (.isDirectory f)
                (mapcat walk (children f))

                (.isFile f)
                [path]

                :else
                nil)))]
    (walk dir)))

(defn walk-files-with-excludes [dir fragments]
  (letfn [(walk [f]
            (let [path (rel f)]
              (cond
                (excluded-by? fragments (str "/" path (when (.isDirectory f) "/")))
                nil

                (.isDirectory f)
                (mapcat walk (children f))

                (.isFile f)
                [path]

                :else
                nil)))]
    (walk dir)))

(defn files-under [repo]
  (let [dir (io/file root repo)]
    (when (.exists dir)
      (walk-files dir))))

(defn repo-exists? [repo]
  (.exists (io/file root repo)))

(defn adapter-for [repo path]
  (some (fn [adapter]
          (when (str/starts-with? path (:path adapter))
            adapter))
        (get-in repos [repo :allowed-adapters])))

(defn legacy-debt-for [repo path]
  (some (fn [debt]
          (when (str/starts-with? path (:path debt))
            debt))
        (get-in repos [repo :legacy-debt])))

(defn classify [repo path]
  (let [e (ext path)]
    (cond
      (contains? source-exts e)
      {:repo repo :path path :ext e :class :kotoba-source}

      (contains? non-kotoba-exts e)
      (cond
        (legacy-debt-for repo path)
        (let [debt (legacy-debt-for repo path)]
          {:repo repo :path path :ext e :class :legacy-debt
           :debt-reason (:debt-reason debt)
           :language (:language debt)})

        (adapter-for repo path)
        (let [adapter (adapter-for repo path)]
          {:repo repo :path path :ext e :class :allowed-adapter
           :adapter-reason (:adapter-reason adapter)
           :language (:language adapter)})

        :else
        {:repo repo :path path :ext e :class :unclassified-non-kotoba})

      :else
      {:repo repo :path path :ext e :class :other})))

(defn entries []
  (->> (keys repos)
       (mapcat (fn [repo] (map #(classify repo %) (files-under repo))))))

(defn local-source-paths [source]
  (cond
    (and (string? source) (str/starts-with? source "orgs/"))
    [source]

    (vector? source)
    (filter #(and (string? %) (str/starts-with? % "orgs/")) source)

    :else
    []))

(defn source-exists? [path]
  (.exists (io/file root path)))

(defn file-name [path]
  (.getName (io/file path)))

(defn quarantine-root-path [root-entry]
  (if (map? root-entry)
    (:path root-entry)
    root-entry))

(defn quarantine-root-for [path]
  (->> quarantined-legacy-roots
       (map quarantine-root-path)
       (filter #(and % (str/starts-with? path %)))
       (sort-by count >)
       first))

(defn forbidden-kotoba-lang-artifact-errors []
  (let [forbidden-exts (:extensions forbidden-kotoba-lang-artifacts)
        forbidden-names (:file-names forbidden-kotoba-lang-artifacts)]
    (->> (files-under "orgs/kotoba-lang")
         (keep (fn [path]
                 (cond
                   (contains? forbidden-exts (ext path))
                   {:path path
                    :message "forbidden kotoba-lang source/runtime extension remains"
                    :ext (ext path)}

                   (contains? forbidden-names (file-name path))
                   {:path path
                    :message "forbidden kotoba-lang runtime/build artifact remains"
                    :file-name (file-name path)}

                   :else nil)))
         vec)))

(defn workflow-file? [ci-rule path]
  (and (str/includes? path (:path-fragment ci-rule))
       (contains? #{".yml" ".yaml"} (ext path))))

(defn active-workflow-line? [line]
  (let [trimmed (str/trim line)]
    (and (seq trimmed)
         (not (str/starts-with? trimmed "#")))))

(defn forbidden-ci-patterns [ci-rule]
  (map re-pattern (:line-patterns ci-rule)))

(defn forbidden-ci-errors [scan-root ci-rule message]
  (let [patterns (forbidden-ci-patterns ci-rule)]
    (->> (files-under scan-root)
         (filter #(workflow-file? ci-rule %))
         (mapcat
          (fn [path]
            (let [lines (str/split-lines (slurp (io/file root path)))]
              (keep-indexed
               (fn [idx line]
                 (when (and (active-workflow-line? line)
                            (some #(re-find % line) patterns))
                   {:path path
                    :line (inc idx)
                    :message message
                    :text (str/trim line)}))
               lines))))
         vec)))

(defn forbidden-kotoba-lang-ci-errors []
  (forbidden-ci-errors
   "orgs/kotoba-lang"
   forbidden-kotoba-lang-ci
   "forbidden kotoba-lang CI runtime/toolchain command remains"))

(defn forbidden-root-ci-errors []
  (forbidden-ci-errors
   ".github"
   forbidden-root-ci
   "forbidden root CI runtime/toolchain command remains"))

(defn workflow-files [scan-root ci-rule]
  (->> (files-under scan-root)
       (filter #(workflow-file? ci-rule %))
       sort
       vec))

(defn ci-audit-summary []
  (let [root-workflows (workflow-files ".github" forbidden-root-ci)
        kotoba-workflows (workflow-files "orgs/kotoba-lang" forbidden-kotoba-lang-ci)
        root-errors (forbidden-root-ci-errors)
        kotoba-errors (forbidden-kotoba-lang-ci-errors)]
    {:root
     {:path-fragment (:path-fragment forbidden-root-ci)
      :workflow-files (count root-workflows)
      :violations (count root-errors)
      :forbidden-patterns (:line-patterns forbidden-root-ci)}
     :kotoba-lang
     {:path-fragment (:path-fragment forbidden-kotoba-lang-ci)
      :workflow-files (count kotoba-workflows)
      :violations (count kotoba-errors)
      :forbidden-patterns (:line-patterns forbidden-kotoba-lang-ci)}}))

(defn quarantine-root-errors []
  (->> quarantined-legacy-roots
       (mapcat
        (fn [entry]
          (let [path (quarantine-root-path entry)
                display-path (or path "<missing>")]
            (remove nil?
                    [(when (str/blank? (str path))
                       {:path display-path
                        :message "quarantined legacy root is missing :path"})
                     (when (and path (not (.exists (io/file root path))))
                       {:path path
                        :message "quarantined legacy root directory is missing"})
                     (when (and path (not (excluded? (str "/" path "/"))))
                       {:path path
                        :message "quarantined legacy root is not excluded from active kotoba audit"})
                     (when (and (map? entry) (not= false (:authority? entry)))
                       {:path display-path
                        :message "quarantined legacy root must declare :authority? false"})]))))
       vec))

(defn quarantined-legacy-artifacts []
  (let [forbidden-exts (:extensions forbidden-kotoba-lang-artifacts)
        forbidden-names (:file-names forbidden-kotoba-lang-artifacts)]
    (->> quarantined-legacy-roots
         (map quarantine-root-path)
         (map #(io/file root %))
         (filter #(.exists %))
         (mapcat #(walk-files-with-excludes % quarantine-scan-excludes))
         (keep (fn [path]
                 (cond
                   (contains? forbidden-exts (ext path))
                   {:path path
                    :root (quarantine-root-for path)
                    :artifact (ext path)
                    :kind :extension}

                   (contains? forbidden-names (file-name path))
                   {:path path
                    :root (quarantine-root-for path)
                    :artifact (file-name path)
                    :kind :file-name}

                   :else nil)))
         vec)))

(defn migration-errors []
  (vec
   (concat
    (when-not (= :kotoba-only-runtime-ledger (:id ledger))
      [{:path ledger-path
        :message "unexpected ledger :id"}])
    (when-not (= #{:kotoba :edn :cljc} (:semantic-source-of-truth (:policy ledger)))
      [{:path ledger-path
        :message "unexpected semantic source-of-truth policy"}])
    (quarantine-root-errors)
    (forbidden-kotoba-lang-artifact-errors)
    (forbidden-kotoba-lang-ci-errors)
    (forbidden-root-ci-errors)
    (map (fn [repo]
           {:repo repo
            :message "ledger repo directory is missing"})
         (remove repo-exists? (keys repos)))
    (mapcat
     (fn [repo]
       (remove nil?
               [(when-not (or (contains? (:extra-projects manifest-repos) repo)
                              (contains? west-paths repo))
                  {:repo repo
                   :message "ledger repo is missing from manifest/repos.edn or manifest/west.yml"})
                (when-not (contains? west-paths repo)
                  {:repo repo
                   :message "ledger repo is missing from generated manifest/west.yml"})]))
     (keys repos))
    (mapcat
     (fn [[repo data]]
       (concat
        (when-not (:target-state data)
          [{:repo repo
            :message "ledger repo is missing :target-state"}])
        (when-not (seq (:migration-items data))
          [{:repo repo
            :message "ledger repo has no :migration-items"}])
        (keep-indexed
         (fn [idx item]
           (when-not (= :done (:status item))
             {:repo repo
              :item-index idx
              :item-id (:id item)
              :status (:status item)
              :message "migration item is not :done"}))
         (:migration-items data))
        (mapcat
         (fn [idx item]
           (map (fn [path]
                  {:repo repo
                   :item-index idx
                   :item-id (:id item)
                   :path path
                   :message "migration item :source path is missing"})
                (remove source-exists? (local-source-paths (:source item)))))
         (range)
         (:migration-items data))))
     repos))))

(defn counts [xs keyfn]
  (into (sorted-map) (frequencies (map keyfn xs))))

(defn nested-counts [xs outer-key inner-key]
  (into (sorted-map)
        (map (fn [[outer grouped]]
               [outer {:total (count grouped)
                       :by-artifact (counts grouped inner-key)}])
             (group-by outer-key xs))))

(defn repo-summary [xs repo]
  (let [rs (filter #(= repo (:repo %)) xs)]
    {:repo repo
     :target-state (get-in repos [repo :target-state])
     :migration-status (frequencies (map :status (get-in repos [repo :migration-items])))
     :total (count rs)
     :by-class (counts rs :class)
     :by-ext (counts rs :ext)
     :unclassified (count (filter #(= :unclassified-non-kotoba (:class %)) rs))}))

(defn summary [xs]
  (let [errors (migration-errors)
        quarantined (quarantined-legacy-artifacts)]
    {:ledger ledger-path
     :policy (:policy ledger)
     :total (count xs)
     :by-class (counts xs :class)
     :by-ext (counts xs :ext)
     :migration-status
     (counts (mapcat (fn [[_ repo]] (:migration-items repo)) repos) :status)
     :ci-audit (ci-audit-summary)
     :quarantined-legacy
     {:fragments quarantined-legacy-path-fragments
      :roots quarantined-legacy-roots
      :total (count quarantined)
      :by-artifact (counts quarantined :artifact)
      :by-root (nested-counts quarantined :root :artifact)}
     :valid? (empty? errors)
     :errors errors
     :repos (mapv #(repo-summary xs %) (sort (keys repos)))}))

(defn print-human [xs]
  (let [s (summary xs)
        bad (filter #(= :unclassified-non-kotoba (:class %)) xs)]
    (println "kotoba-only runtime audit")
    (println "ledger:" (:ledger s))
    (println "total:" (:total s))
    (println "by-class:" (pr-str (:by-class s)))
    (println "by-ext:" (pr-str (:by-ext s)))
    (println "migration-status:" (pr-str (:migration-status s)))
    (println "ci-audit:" (pr-str (:ci-audit s)))
    (println "quarantined-legacy:" (pr-str (:quarantined-legacy s)))
    (println)
    (doseq [r (:repos s)]
      (println (format "%-42s target=%s migration=%s unclassified=%s by-class=%s"
                       (:repo r) (name (:target-state r))
                       (pr-str (:migration-status r)) (:unclassified r)
                       (pr-str (:by-class r)))))
    (when (seq (:errors s))
      (println)
      (println "migration ledger errors:")
      (doseq [{:keys [repo item-index item-id path line status message text]} (:errors s)]
        (println (str "  " repo " - " message
                      (when item-index (str " item-index=" item-index))
                      (when item-id (str " item-id=" item-id))
                      (when path (str " path=" path))
                      (when line (str ":" line))
                      (when text (str " text=" (pr-str text)))
                      (when status (str " status=" status))))))
    (when (seq bad)
      (println)
      (println "unclassified non-kotoba files:")
      (doseq [{:keys [path ext]} (sort-by :path bad)]
        (println (format "  %-8s %s" ext path))))))

(defn -main [& args]
  (let [xs (vec (entries))
        edn? (some #{"--edn"} args)
        strict? (some #{"--strict"} args)
        s (summary xs)
        bad? (some #(= :unclassified-non-kotoba (:class %)) xs)
        invalid? (or bad? (seq (:errors s)))]
    (if edn?
      (prn (assoc (summary xs)
                  :unclassified
                  (vec (sort-by :path (filter #(= :unclassified-non-kotoba (:class %)) xs)))))
      (print-human xs))
    (when (and strict? invalid?)
      (scripts.nbb-compat/exit 1))))

(apply -main *command-line-args*)

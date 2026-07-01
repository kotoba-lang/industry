#!/usr/bin/env bb
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str])

(def root
  (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))

(def ledger-path
  "90-docs/migration/kami-webgpu-dsl-runtime-split.edn")

(def repos-path
  "manifest/repos.edn")

(def west-path
  "manifest/west.yml")

(defn read-edn [path]
  (edn/read-string (slurp (io/file root path))))

(defn read-edn-file [file]
  (edn/read-string (slurp file)))

(def ledger
  (read-edn ledger-path))

(def repos
  (read-edn repos-path))

(def required-verification-commands
  #{"bb scripts/kami-webgpu-dsl-runtime-split-audit.bb --strict"
    "bb scripts/kami-webgpu-dsl-runtime-split-audit.bb --publish-manifest --strict"
    "bb scripts/kami-webgpu-dsl-runtime-split-audit.bb --publish-checklist --strict"
    "bb scripts/kami-webgpu-dsl-runtime-split-audit.bb --publish-checklist --batch ci-only --strict"
    "bb scripts/kami-webgpu-dsl-runtime-split-audit.bb --publish-checklist --batch readme-ci --strict"
    "bb scripts/kami-webgpu-dsl-runtime-split-audit.bb --publish-checklist --batch source-bearing --strict"
    "bb scripts/kami-webgpu-dsl-runtime-split-audit.bb --publish-progress --strict"
    "bb scripts/kami-webgpu-dsl-runtime-split-audit.bb --strict --requested-tests"
    "bash scripts/verify-clj-everywhere.sh"
    "bb scripts/kotoba-only-runtime-audit.bb --strict"
    "bb scripts/kami-provider-split-audit.bb --strict"})

(def extra-projects
  (set (:extra-projects repos)))

(def west-paths
  (->> (str/split-lines (slurp (io/file root west-path)))
       (keep #(second (re-find #"^[ \t]+path:[ \t]+(.+?)[ \t]*$" %)))
       set))

(def manifest-paths
  (set (concat (:extra-projects repos)
               (vals (:path-overrides repos))
               west-paths)))

(defn entries []
  (concat
   (map #(assoc % :kind :format-dsl) (:format-dsl-repos ledger))
   (map #(assoc % :kind :runtime) (:runtime-repos ledger))
   (map #(assoc % :kind :render-domain) (:render-domain-repos ledger))))

(def split-repos
  (set (map :repo (entries))))

(def allowed-west-drift-repos
  (set (concat split-repos (:allowed-non-webgpu-west-drift-repos ledger))))

(defn west-projects-from-string [content]
  (let [entries (atom {})
        current (atom nil)]
    (doseq [line (str/split-lines content)]
      (when (re-find #"^[ \t]*self:[ \t]*$" line)
        (reset! current nil))
      (when-let [name (second (re-find #"^[ \t]*- name:[ \t]*(\S+)" line))]
        (reset! current {:name name}))
      (when @current
        (doseq [[k pattern] {:remote #"^[ \t]*remote:[ \t]*(\S+)"
                             :repo-path #"^[ \t]*repo-path:[ \t]*(\S+)"
                             :revision #"^[ \t]*revision:[ \t]*(\S+)"
                             :path #"^[ \t]*path:[ \t]*(\S+)"
                             :groups #"^[ \t]*groups:[ \t]*(.+?)[ \t]*$"}]
          (when-let [value (second (re-find pattern line))]
            (swap! current assoc k value)))
        (when (re-find #"^[ \t]*submodules:[ \t]*true[ \t]*$" line)
          (swap! current assoc :submodules true))
        (when-let [path (:path @current)]
          (swap! entries assoc path @current))))
    @entries))

(defn head-west-content []
  (let [result (sh "git" "-C" root "show" (str "HEAD:" west-path))]
    (when (zero? (:exit result))
      (:out result))))

(defn west-drift-errors []
  (if-let [base-content (head-west-content)]
    (let [base (west-projects-from-string base-content)
          current (west-projects-from-string (slurp (io/file root west-path)))
          structural (fn [project] (dissoc project :revision))
          changed (->> (concat (keys base) (keys current))
                       set
                       (remove allowed-west-drift-repos)
                       (filter #(not= (structural (get base %))
                                      (structural (get current %))))
                       sort)]
      (map (fn [path]
             {:repo path
              :message "manifest/west.yml has non-split project drift"
              :base (get base path)
              :current (get current path)})
           changed))
    []))

(defn ns->path [ns-sym ext]
  (str "src/"
       (-> (name ns-sym)
           (str/replace "." "/")
           (str/replace "-" "_"))
       ext))

(defn expected-ns-files [ns-sym]
  (map #(ns->path ns-sym %) [".cljc" ".clj"]))

(defn declares-ns? [repo rel-path ns-sym]
  (let [file (io/file root repo rel-path)]
    (and (.exists file)
         (str/includes? (slurp file) (str "(ns " ns-sym)))))

(defn has-declared-ns? [repo ns-sym]
  (some #(declares-ns? repo % ns-sym) (expected-ns-files ns-sym)))

(defn exists-rel? [repo path]
  (.exists (io/file root repo path)))

(defn exists-root-rel? [path]
  (.exists (io/file root path)))

(defn list-files [repo]
  (let [dir (io/file root repo)]
    (when (.exists dir)
      (letfn [(walk [f]
                (cond
                  (str/includes? (.getPath f) (str java.io.File/separator ".git" java.io.File/separator))
                  nil

                  (.isDirectory f)
                  (mapcat walk (or (seq (.listFiles f)) []))

                  (.isFile f)
                  [(-> (.toPath f)
                       (.toAbsolutePath)
                       (.normalize)
                       str
                       (str/replace (str (.normalize (.toAbsolutePath (.toPath (io/file root)))) "/") ""))]

                  :else nil))]
        (vec (walk dir))))))

(def authority-exts #{".rs" ".js" ".mjs" ".cjs" ".ts" ".tsx" ".jsx"})

(def timeout-bin
  (let [candidates ["timeout" "/opt/homebrew/opt/coreutils/libexec/gnubin/timeout" "gtimeout"]]
    (some (fn [cmd]
            (when (zero? (:exit (sh "bash" "-lc" (str "command -v " cmd " >/dev/null 2>&1"))))
              cmd))
          candidates)))

(def test-timeout-seconds
  (or (some-> (System/getenv "SPLIT_AUDIT_TEST_TIMEOUT_SECONDS")
              parse-long)
      120))

(defn ext [path]
  (second (re-find #"(\.[^./]+)$" path)))

(defn approved-authority-adapter? [path]
  (= path "orgs/kotoba-lang/playwright/scripts/pw_eval.cjs"))

(defn authority-files [repo]
  (->> (list-files repo)
       (filter #(contains? authority-exts (ext %)))
       (remove approved-authority-adapter?)
       sort
       vec))

(def ci-policy
  (get-in ledger [:policy :ci]))

(def forbidden-ci-patterns
  (mapv re-pattern (:forbidden-patterns ci-policy)))

(def required-ci-patterns
  (mapv re-pattern (:required-patterns ci-policy)))

(defn workflow-file? [repo path]
  (and (str/starts-with? path (str repo (:workflow-path-fragment ci-policy)))
       (or (str/ends-with? path ".yml")
           (str/ends-with? path ".yaml"))))

(defn workflow-files [repo]
  (->> (list-files repo)
       (filter #(workflow-file? repo %))
       sort
       vec))

(defn forbidden-ci-matches [path]
  (let [content (slurp (io/file root path))]
    (->> forbidden-ci-patterns
         (keep (fn [pattern]
                 (when (re-find pattern content)
                   (str pattern))))
         vec)))

(defn missing-required-ci-matches [path]
  (let [content (slurp (io/file root path))]
    (->> required-ci-patterns
         (keep (fn [pattern]
                 (when-not (re-find pattern content)
                   (str pattern))))
         vec)))

(defn forbidden-ci-errors [repo]
  (->> (workflow-files repo)
       (keep (fn [path]
               (let [matches (forbidden-ci-matches path)]
                 (when (seq matches)
                   {:repo repo
                    :path path
                    :message "workflow contains Rust/Node authority commands"
                    :files matches}))))))

(defn requested-ci-shape-errors [repo]
  (->> (workflow-files repo)
       (keep (fn [path]
               (let [missing (missing-required-ci-matches path)]
                 (when (seq missing)
                   {:repo repo
                    :path path
                    :message "requested-scope workflow is not a Clojure test CI"
                    :files missing}))))))

(defn ci-audit-summary []
  (let [repos (map :repo (entries))
        files (mapcat workflow-files repos)
        violations (mapcat forbidden-ci-errors repos)]
    {:workflow-files (count files)
     :violations (count violations)
     :required-patterns (:required-patterns ci-policy)
     :forbidden-patterns (:forbidden-patterns ci-policy)}))

(defn requested-scope-summary []
  (into {}
        (map (fn [[k repos]]
               [k (count repos)])
             (:requested-scope ledger))))

(defn requested-test-repos []
  (->> (:requested-scope ledger)
       vals
       (apply concat)
       distinct
       sort
       vec))

(defn requested-ci-summary []
  (let [repos (requested-test-repos)
        with-workflow (filter #(seq (workflow-files %)) repos)
        shape-violations (mapcat requested-ci-shape-errors repos)]
    {:repos (count repos)
     :with-workflow (count with-workflow)
     :missing-workflow (- (count repos) (count with-workflow))
     :shape-violations (count shape-violations)
     :required-patterns (:required-patterns ci-policy)}))

(def readme-required-patterns
  (mapv re-pattern (get-in ledger [:policy :repo-shape :required-readme-patterns])))

(defn readme-file [repo]
  (io/file root repo "README.md"))

(defn missing-readme-patterns [repo]
  (let [file (readme-file repo)]
    (if (.exists file)
      (let [content (slurp file)]
        (->> readme-required-patterns
             (keep (fn [pattern]
                     (when-not (re-find pattern content)
                       (str pattern))))
             vec))
      (mapv str readme-required-patterns))))

(defn requested-readme-errors [repo]
  (let [file (readme-file repo)
        missing (missing-readme-patterns repo)]
    (when (or (not (.exists file)) (seq missing))
      [{:repo repo
        :path (str repo "/README.md")
        :message "requested-scope README is missing required migration/package text"
        :files missing}])))

(defn requested-readme-summary []
  (let [repos (requested-test-repos)
        present (filter #(.exists (readme-file %)) repos)
        violations (mapcat requested-readme-errors repos)]
    {:repos (count repos)
     :with-readme (count present)
     :missing-readme (- (count repos) (count present))
     :shape-violations (count violations)
     :required-patterns (get-in ledger [:policy :repo-shape :required-readme-patterns])}))

(defn license-file? [repo]
  (or (.exists (io/file root repo "LICENSE"))
      (.exists (io/file root repo "LICENSE.md"))
      (.exists (io/file root repo "COPYING"))))

(defn requested-license-summary []
  (let [repos (requested-test-repos)
        present (filter license-file? repos)]
    {:repos (count repos)
     :with-license (count present)
     :missing-license (- (count repos) (count present))
     :required? (boolean (get-in ledger [:policy :repo-shape :license-required?]))}))

(defn git-status-lines [repo]
  (let [result (sh "git" "-C" (str (io/file root repo)) "status" "--short")]
    (if (zero? (:exit result))
      (remove str/blank? (str/split-lines (:out result)))
      [(str "!! git status failed: " (str/trim (:err result)))])))

(defn status-kind [line]
  (cond
    (str/includes? line ".github/") :ci
    (str/starts-with? line "??") :untracked
    (str/includes? line "README.md") :readme
    (re-find #"^[ MARCUD?!]{1,2} " line) :tracked
    :else :other))

(defn parse-git-status-line [line]
  (let [status (if (<= 2 (count line)) (subs line 0 2) "!!")
        path (if (<= 4 (count line)) (str/trim (subs line 3)) (str/trim line))]
    {:status status
     :path path
     :kind (status-kind line)}))

(defn requested-git-details []
  (->> (requested-test-repos)
       (keep (fn [repo]
               (let [changes (mapv parse-git-status-line (git-status-lines repo))]
                 (when (seq changes)
                   {:repo repo
                    :changes changes}))))
       vec))

(defn requested-git-summary []
  (let [details (requested-git-details)
        entries (map (fn [{:keys [repo changes]}]
                       {:repo repo
                        :changes (count changes)
                        :by-kind (frequencies (map :kind changes))})
                     details)
        totals (apply merge-with +
                      (map :by-kind entries))]
    {:repos (count (requested-test-repos))
     :dirty-repos (count entries)
     :changes (reduce + 0 (map :changes entries))
     :by-kind (or totals {})
     :repos-with-changes (mapv :repo entries)}))

(defn publish-plan-batches []
  (vec (get-in ledger [:requested-publish-plan :batches])))

(defn batch-ids []
  (set (map :id (publish-plan-batches))))

(defn publish-plan-repos []
  (mapcat :repos (publish-plan-batches)))

(defn publish-plan-entry-by-repo []
  (into {}
        (for [{:keys [id intent allowed-change-kinds repos]} (publish-plan-batches)
              repo repos]
          [repo {:batch id
                 :intent intent
                 :allowed-change-kinds allowed-change-kinds}])))

(defn requested-publish-manifest []
  (let [plan-by-repo (publish-plan-entry-by-repo)
        details-by-repo (into {} (map (juxt :repo :changes) (requested-git-details)))]
    (->> (publish-plan-repos)
         distinct
         sort
         (mapv (fn [repo]
                 (merge {:repo repo
                         :test "clojure -M:test"
                         :changes (get details-by-repo repo [])}
                        (get plan-by-repo repo)))))))

(defn repo-name [repo]
  (last (str/split repo #"/")))

(defn publish-commit-message [{:keys [repo batch]}]
  (case batch
    :ci-only (str "ci: add clj test workflow for " (repo-name repo))
    :readme-ci (str "docs: align clj test docs for " (repo-name repo))
    :source-bearing (str "feat: publish kotoba split for " (repo-name repo))
    (str "chore: publish kotoba split for " (repo-name repo))))

(defn requested-publish-checklist []
  (mapv (fn [{:keys [repo changes test] :as item}]
          (let [paths (mapv :path changes)
                pending? (seq paths)]
            (assoc item
                   :state (if pending? :pending :completed)
                   :change-paths paths
                   :commands (if pending?
                               [(str "cd " repo)
                                test
                                "git status --short"
                                (str "git add " (str/join " " paths))
                                (str "git commit -m " (pr-str (publish-commit-message item)))]
                               [(str "cd " repo)
                                "git status --short"])
                   :commit-message (publish-commit-message item))))
        (requested-publish-manifest)))

(defn filter-by-batch [batch items]
  (if batch
    (filterv #(= batch (:batch %)) items)
    (vec items)))

(defn requested-publish-progress []
  (let [items (requested-publish-checklist)
        dirty? #(seq (:changes %))]
    {:repos (count items)
     :pending-repos (count (filter dirty? items))
     :pending-changes (reduce + 0 (map #(count (:changes %)) items))
     :by-batch (->> items
                    (group-by :batch)
                    (map (fn [[batch xs]]
                           [batch {:repos (count xs)
                                   :pending-repos (count (filter dirty? xs))
                                   :pending-changes (reduce + 0 (map #(count (:changes %)) xs))}]))
                    (into (sorted-map)))}))

(defn requested-publish-plan-summary []
  (let [batches (publish-plan-batches)
        dirty (set (map :repo (requested-git-details)))
        planned (set (publish-plan-repos))
        completed (sort (remove dirty planned))]
    {:unit (get-in ledger [:requested-publish-plan :unit])
     :batches (mapv (fn [{:keys [id allowed-change-kinds repos]}]
                      {:id id
                       :repos (count repos)
                       :allowed-change-kinds allowed-change-kinds})
                    batches)
     :planned-repos (count planned)
     :dirty-repos (count dirty)
     :missing-dirty-repos (vec (sort (remove planned dirty)))
     :completed-repos (count completed)
     :completed-planned-repos (vec completed)}))

(defn requested-publish-plan-errors []
  (let [batches (publish-plan-batches)
        details-by-repo (into {} (map (juxt :repo :changes) (requested-git-details)))
        dirty (set (keys details-by-repo))
        planned-repos (vec (publish-plan-repos))
        planned (set planned-repos)
        requested (set (requested-test-repos))
        duplicate-repos (->> planned-repos
                             frequencies
                             (filter (fn [[_ n]] (< 1 n)))
                             (map first)
                             sort)]
    (concat
     (map (fn [repo]
            {:repo repo
             :message "dirty requested repo is missing from requested publish plan"})
          (sort (remove planned dirty)))
     (map (fn [repo]
            {:repo repo
             :message "requested publish plan repo is outside requested scope"})
          (sort (remove requested planned)))
     (map (fn [repo]
            {:repo repo
             :message "requested publish plan repo appears in multiple batches"})
          duplicate-repos)
     (mapcat (fn [{:keys [id allowed-change-kinds repos]}]
               (keep (fn [repo]
                       (let [changes (get details-by-repo repo)
                             bad (vec (remove #(contains? allowed-change-kinds (:kind %)) changes))]
                         (when (seq bad)
                           {:repo repo
                            :kind id
                            :message "requested publish plan batch has unexpected change kind"
                            :entries bad})))
                     repos))
             batches))))

(def test-source-exts #{".clj" ".cljc"})

(defn test-source-files [repo]
  (->> (list-files repo)
       (filter #(str/starts-with? % (str repo "/test/")))
       (filter #(contains? test-source-exts (ext %)))
       sort
       vec))

(def split-source-exts #{".clj" ".cljc"})

(defn src-source-files [repo]
  (->> (list-files repo)
       (filter #(str/starts-with? % (str repo "/src/")))
       (filter #(contains? split-source-exts (ext %)))
       sort
       vec))

(defn source-path-coverage-errors [{:keys [repo source-paths] :as entry}]
  (let [actual (set (src-source-files repo))
        declared (set source-paths)
        missing (sort (remove declared actual))
        stale (sort (remove actual declared))]
    (remove nil?
            [(when (seq missing)
               (assoc entry
                      :message "ledger :source-paths is missing src source files"
                      :files (vec missing)))
             (when (seq stale)
               (assoc entry
                      :message "ledger :source-paths contains stale or non-src files"
                      :files (vec stale)))])))

(defn test-path-coverage-errors [{:keys [repo test-paths] :as entry}]
  (let [actual (set (test-source-files repo))
        declared (set test-paths)
        missing (sort (remove declared actual))
        stale (sort (remove actual declared))]
    (remove nil?
            [(when (empty? test-paths)
               (assoc entry :message "entry is missing :test-paths"))
             (when (seq missing)
               (assoc entry
                      :message "ledger :test-paths is missing test source files"
                      :files (vec missing)))
             (when (seq stale)
               (assoc entry
                      :message "ledger :test-paths contains stale or non-test files"
                      :files (vec stale)))])))

(def source-like-exts #{".clj" ".cljc" ".cljs" ".edn" ".kotoba" ".rs" ".js" ".mjs" ".cjs" ".ts" ".tsx" ".jsx"})

(def webgpu-source-prefixes
  ["orgs/kotoba-lang/webgpu/src/"
   "orgs/kotoba-lang/webgpu/test/"
   "orgs/kotoba-lang/webgpu/scripts/"
   "orgs/kotoba-lang/webgpu/fixtures/"])

(defn webgpu-source-file? [path]
  (and (some #(str/starts-with? path %) webgpu-source-prefixes)
       (contains? source-like-exts (ext path))))

(defn webgpu-pruning-errors []
  (let [removed (:removed-from-webgpu ledger)
        forbidden (:forbidden-paths removed)
        allowed (set (:allowed-webgpu-source-paths removed))
        webgpu-files (filter webgpu-source-file? (list-files "orgs/kotoba-lang/webgpu"))
        unexpected (remove allowed webgpu-files)]
    (remove nil?
            (concat
             (map (fn [path]
                    (when (exists-root-rel? path)
                      {:repo "orgs/kotoba-lang/webgpu"
                       :path path
                       :message "split source remains in webgpu"}))
                  forbidden)
             (map (fn [path]
                    {:repo "orgs/kotoba-lang/webgpu"
                     :path path
                     :message "webgpu has source outside allowed split residue"})
                  unexpected)))))

(defn test-command [repo]
  (cond
    (exists-rel? repo "bb.edn") ["bb" "test"]
    (exists-rel? repo "deps.edn") ["clojure" "-M:test"]
    :else nil))

(defn declared-test-command? [repo]
  (let [bb-file (io/file root repo "bb.edn")
        deps-file (io/file root repo "deps.edn")]
    (cond
      (.exists bb-file)
      (contains? (:tasks (read-edn-file bb-file)) 'test)

      (.exists deps-file)
      (contains? (:aliases (read-edn-file deps-file)) :test)

      :else false)))

(defn deps-edn [repo]
  (let [deps-file (io/file root repo "deps.edn")]
    (when (.exists deps-file)
      (read-edn-file deps-file))))

(defn deps-has-path? [repo path]
  (contains? (set (:paths (deps-edn repo))) path))

(defn deps-test-has-extra-path? [repo path]
  (contains? (set (get-in (deps-edn repo) [:aliases :test :extra-paths])) path))

(defn root-relative-path [file]
  (-> (.toURI (io/file root))
      (.relativize (.toURI (.getCanonicalFile file)))
      str
      (str/replace #"/$" "")))

(defn deps-local-roots [repo]
  (let [deps (deps-edn repo)]
    (->> (:deps deps)
         vals
         (keep :local/root)
         (map #(root-relative-path (io/file root repo %)))
         sort
         vec)))

(defn missing-manifest-local-roots [repo]
  (->> (deps-local-roots repo)
       (remove (fn [path]
                 (or (contains? manifest-paths path)
                     (some #(str/starts-with? path (str % "/")) manifest-paths))))
       vec))

(def allowed-dep-symbols
  '#{org.clojure/clojure
     org.clojure/clojurescript
     cheshire/cheshire
     babashka/process
     com.microsoft.playwright/playwright})

(defn allowed-dep? [dep]
  (or (contains? allowed-dep-symbols dep)
      (= "io.github.kotoba-lang" (namespace dep))))

(defn disallowed-deps [repo]
  (->> (keys (:deps (deps-edn repo)))
       (remove allowed-dep?)
       sort
       vec))

(def forbidden-build-manifests
  ["Cargo.toml"
   "Cargo.lock"
   "package.json"
   "package-lock.json"
   "pnpm-lock.yaml"
   "yarn.lock"
   "bun.lockb"
   "tsconfig.json"
   "vite.config.js"
   "vite.config.ts"
   "webpack.config.js"])

(defn forbidden-build-manifest-files [repo]
  (->> forbidden-build-manifests
       (filter #(exists-rel? repo %))
       (map #(str repo "/" %))
       vec))

(def ^:dynamic *print-test-progress?*
  true)

(defn run-test [repo]
  (if-let [cmd (test-command repo)]
    (let [_ (when *print-test-progress?*
              (println "test:" repo)
              (flush))
          test-cmd (str/join " " (map pr-str cmd))
          shell-cmd (str "cd " (pr-str (str root "/" repo))
                         " && "
                         (if timeout-bin
                           (str (pr-str timeout-bin) " " test-timeout-seconds "s " test-cmd)
                           test-cmd))
          result (sh "bash" "-lc" shell-cmd)]
      {:repo repo
       :command (str/join " " cmd)
       :timeout-seconds (when timeout-bin test-timeout-seconds)
       :exit (:exit result)
       :ok? (zero? (:exit result))
       :out (:out result)
       :err (:err result)})
    {:repo repo
     :command nil
     :exit 1
     :ok? false
     :err "no bb.edn or deps.edn test command"}))

(defn webgpu-origin-path? [path]
  (str/starts-with? path "orgs/kotoba-lang/webgpu/"))

(defn repo-local-path? [repo path]
  (and repo path (str/starts-with? path (str repo "/"))))

(defn entry-source-errors [{:keys [repo source-paths from-webgpu] :as entry}]
  (remove nil?
          (concat
           [(when (empty? source-paths)
              (assoc entry :message "entry is missing :source-paths"))
            (when-let [path (first (remove #(repo-local-path? repo %) source-paths))]
              (assoc entry
                     :path path
                     :message "declared split source path is outside entry repo"))]
           (map (fn [path]
                  (when-not (exists-root-rel? path)
                    (assoc entry
                           :path path
                           :message "declared split source path is missing")))
                source-paths)
           (map (fn [path]
                  (cond
                    (not (webgpu-origin-path? path))
                    (assoc entry
                           :path path
                           :message "declared WebGPU origin path is not under orgs/kotoba-lang/webgpu")

                    (exists-root-rel? path)
                    (assoc entry
                           :path path
                           :message "declared WebGPU origin path still exists after split")))
                from-webgpu))))

(defn entry-errors [{:keys [repo ns status] :as entry}]
  (let [dir (io/file root repo)
        ns-files (expected-ns-files ns)
        authority (authority-files repo)
        tests (test-source-files repo)
        missing-local-roots (missing-manifest-local-roots repo)
        bad-deps (disallowed-deps repo)
        build-manifests (forbidden-build-manifest-files repo)]
    (remove nil?
            (concat
             [(when-not repo
                (assoc entry :message "entry is missing :repo"))
              (when-not ns
                (assoc entry :message "entry is missing :ns"))
              (when-not (= :done status)
                (assoc entry :message (str "entry status is not :done: " status)))
              (when-not (.exists dir)
                (assoc entry :message "repo directory is missing"))
              (when-not (contains? extra-projects repo)
                (assoc entry :message "repo is missing from manifest/repos.edn :extra-projects"))
              (when-not (contains? manifest-paths repo)
                (assoc entry :message "repo is missing from manifest/repos.edn or manifest/west.yml"))
              (when-not (contains? west-paths repo)
                (assoc entry :message "repo is missing from generated manifest/west.yml"))
              (when-not (exists-rel? repo "deps.edn")
                (assoc entry :message "deps.edn is missing"))
              (when-not (deps-has-path? repo "src")
                (assoc entry :message "deps.edn is missing src in :paths"))
              (when-not (deps-test-has-extra-path? repo "test")
                (assoc entry :message "deps.edn :test alias is missing test in :extra-paths"))
              (when (seq missing-local-roots)
                (assoc entry
                       :message "deps.edn has :local/root dependencies missing from manifest"
                       :files missing-local-roots))
              (when (seq bad-deps)
                (assoc entry
                       :message "deps.edn has dependencies outside the split allowlist"
                       :files (mapv str bad-deps)))
              (when (seq build-manifests)
                (assoc entry
                       :message "forbidden Rust/Node build manifest remains"
                       :files build-manifests))
              (when-not (exists-rel? repo "src")
                (assoc entry :message "src directory is missing"))
              (when-not (exists-rel? repo "test")
                (assoc entry :message "test directory is missing"))
              (when (empty? tests)
                (assoc entry :message "test source files are missing under test/"))
              (when-not (declared-test-command? repo)
                (assoc entry :message "test command is missing from bb.edn or deps.edn"))
              (when (and ns (not-any? #(exists-rel? repo %) ns-files))
                (assoc entry :message (str "expected namespace source is missing: " (str/join " or " ns-files))))
              (when (and ns (not (has-declared-ns? repo ns)))
                (assoc entry :message (str "expected namespace declaration is missing: " ns)))
             (when (seq authority)
                (assoc entry :message "non-CLJ authority source remains"
                       :files authority))]
             (entry-source-errors entry)
             (forbidden-ci-errors repo)
             (source-path-coverage-errors entry)
             (test-path-coverage-errors entry)))))

(defn duplicate-errors [entries key-fn label]
  (->> entries
       (group-by key-fn)
       (filter (fn [[k xs]] (and k (> (count xs) 1))))
       (map (fn [[k xs]]
              {:message (str "duplicate " label ": " k)
               :entries (mapv #(select-keys % [:kind :repo :ns]) xs)}))))

(defn path-owner-errors [entries key label]
  (->> entries
       (mapcat (fn [entry]
                 (map (fn [path] [path entry]) (get entry key))))
       (group-by first)
       (filter (fn [[path owners]] (and path (> (count owners) 1))))
       (map (fn [[path owners]]
              {:path path
               :message (str "duplicate ledger " label " ownership")
               :entries (mapv #(select-keys (second %) [:kind :repo :ns]) owners)}))))

(defn coverage-errors [entries required-key kind label]
  (let [required (get ledger required-key)
        actual (set (map :repo (filter #(= kind (:kind %)) entries)))
        missing (sort (remove actual required))]
    (map (fn [repo]
           {:repo repo
            :kind kind
            :message (str "required " label " repo is missing from ledger")})
         missing)))

(defn requested-scope-errors [entries]
  (let [entry-by-repo (into {} (map (juxt :repo identity) entries))
        checks [[:format-dsl :format-dsl "requested format DSL"]
                [:runtime-browser :runtime "requested runtime/browser"]]]
    (for [[scope-key expected-kind label] checks
          repo (sort (get-in ledger [:requested-scope scope-key]))
          error (let [entry (get entry-by-repo repo)]
                  (concat
                   (remove nil?
                           [(when-not entry
                              {:repo repo
                               :kind expected-kind
                               :message (str label " repo is missing from ledger")})
                            (when (and entry (not= expected-kind (:kind entry)))
                              {:repo repo
                               :kind (:kind entry)
                               :message (str label " repo has unexpected ledger kind; expected " (name expected-kind))})
                            (when-not (contains? manifest-paths repo)
                              {:repo repo
                               :kind expected-kind
                               :message (str label " repo is missing from manifest paths")})
                            (when (empty? (workflow-files repo))
                              {:repo repo
                               :kind expected-kind
                               :message (str label " repo is missing .github/workflows CI")})])
                   (requested-ci-shape-errors repo)
                   (requested-readme-errors repo)))]
      error)))

(defn verification-command-errors []
  (let [declared (set (get-in ledger [:verification :manifest]))
        missing (sort (remove declared required-verification-commands))]
    (map (fn [command]
           {:path ledger-path
            :command command
            :message "required verification command is missing"})
         missing)))

(defn audit []
  (let [xs (vec (entries))]
    (vec
     (concat
      (when-not (= :kami-webgpu-dsl-runtime-split (:id ledger))
        [{:path ledger-path :message "unexpected ledger :id"}])
      (west-drift-errors)
      (webgpu-pruning-errors)
      (verification-command-errors)
      (coverage-errors xs :required-format-dsl-repos :format-dsl "format DSL")
      (coverage-errors xs :required-runtime-repos :runtime "runtime")
      (requested-scope-errors xs)
      (requested-publish-plan-errors)
      (duplicate-errors xs :repo "repo")
      (duplicate-errors xs :ns "namespace")
      (path-owner-errors xs :source-paths "source path")
      (path-owner-errors xs :test-paths "test path")
      (mapcat entry-errors xs)))))

(defn audit-summary []
  (let [xs (vec (entries))]
    {:ledger ledger-path
     :repos (count xs)
     :manifest-paths (count manifest-paths)
     :by-kind (frequencies (map :kind xs))
     :requested-scope (requested-scope-summary)
     :requested-readme (requested-readme-summary)
     :requested-license (requested-license-summary)
     :requested-git (requested-git-summary)
     :requested-git-details (requested-git-details)
     :requested-publish-plan (requested-publish-plan-summary)
     :requested-publish-manifest (requested-publish-manifest)
     :requested-publish-checklist (requested-publish-checklist)
     :requested-publish-progress (requested-publish-progress)
     :requested-ci (requested-ci-summary)
     :ci-audit (ci-audit-summary)}))

(defn print-human [errors test-results]
  (let [summary (audit-summary)]
    (println "KAMI WebGPU DSL/runtime split audit")
    (println "ledger:" (:ledger summary))
    (println "repos:" (:repos summary))
    (println "manifest paths:" (:manifest-paths summary))
    (println "by-kind:" (pr-str (:by-kind summary)))
    (println "requested-scope:" (pr-str (:requested-scope summary)))
    (println "requested-readme:" (pr-str (:requested-readme summary)))
    (println "requested-license:" (pr-str (:requested-license summary)))
    (println "requested-git:" (pr-str (:requested-git summary)))
    (println "requested-publish-plan:" (pr-str (:requested-publish-plan summary)))
    (println "requested-publish-progress:" (pr-str (:requested-publish-progress summary)))
    (println "requested-ci:" (pr-str (:requested-ci summary)))
    (println "ci-audit:" (pr-str (:ci-audit summary)))
    (if (empty? errors)
      (println "status: ok")
      (do
        (println "status: failed")
        (doseq [{:keys [kind repo path ns message files entries base current]} errors]
          (println (str "  " (or repo ledger-path) " - " message
                        (when kind (str " [" (name kind) "]"))
                        (when path (str " path=" path))
                        (when ns (str " ns=" ns))))
          (when base
            (println (str "    base=" (pr-str base))))
          (when current
            (println (str "    current=" (pr-str current))))
          (doseq [f files]
            (println (str "    " f)))
          (doseq [entry entries]
            (println (str "    " (pr-str entry)))))))
    (when (seq test-results)
      (println)
      (println "test results:")
      (doseq [{:keys [repo command ok?]} test-results]
        (println (format "  %-42s %-18s %s" repo (or command "-") (if ok? "ok" "failed")))))))

(defn arg-value [args flag]
  (some (fn [[k v]]
          (when (= flag k) v))
        (partition-all 2 1 args)))

(defn batch-filter-errors [batch]
  (when (and batch (not (contains? (batch-ids) batch)))
    [{:message "unknown requested publish batch"
      :batch batch
      :known-batches (vec (sort (map name (batch-ids))))}]))

(defn -main [& args]
  (let [edn? (some #{"--edn"} args)
        publish-manifest? (some #{"--publish-manifest"} args)
        publish-checklist? (some #{"--publish-checklist"} args)
        publish-progress? (some #{"--publish-progress"} args)
        batch (some-> (arg-value args "--batch") keyword)
        strict? (some #{"--strict"} args)
        tests? (some #{"--tests"} args)
        requested-tests? (some #{"--requested-tests"} args)
        errors (audit)
        test-repos (cond
                     tests? (map :repo (entries))
                     requested-tests? (requested-test-repos)
                     :else nil)
        test-results (when test-repos
                       (binding [*print-test-progress?* (not edn?)]
                         (mapv run-test test-repos)))
        failed-tests (filter (complement :ok?) test-results)
        cli-errors (vec (batch-filter-errors batch))
        all-errors (vec (concat errors cli-errors))]
    (cond
      publish-manifest?
      (prn {:valid? (and (empty? all-errors) (empty? failed-tests))
            :batch batch
            :manifest (filter-by-batch batch (requested-publish-manifest))
            :errors all-errors})

      publish-checklist?
      (prn {:valid? (and (empty? all-errors) (empty? failed-tests))
            :batch batch
            :checklist (filter-by-batch batch (requested-publish-checklist))
            :errors all-errors})

      publish-progress?
      (prn {:valid? (and (empty? all-errors) (empty? failed-tests))
            :progress (requested-publish-progress)
            :errors all-errors})

      edn?
      (prn {:valid? (and (empty? all-errors) (empty? failed-tests))
            :summary (audit-summary)
            :errors all-errors
            :tests test-results})

      :else
      (print-human all-errors test-results))
    (when (and strict? (or (seq all-errors) (seq failed-tests)))
      (System/exit 1))))

(apply -main *command-line-args*)

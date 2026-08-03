#!/usr/bin/env nbb
;; ADR-2608039700 executable projection gate.
;;
;; v2 deliberately has no contract-provided argv. A loader ID selects one
;; repository-owned command, output is forced into an untracked temporary
;; directory, and the child receives a minimal environment.

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def crypto (js/require "node:crypto"))
(def child-process (js/require "node:child_process"))
(def process-argv (vec (js->clj (.-argv js/process))))
(def root (or (some-> (.-env js/process) (aget "PROJECTION_ROOT"))
              (.cwd js/process)))
(def snapshot-mode? (= "1" (some-> (.-env js/process) (aget "PROJECTION_SNAPSHOT"))))

(def allowed-loaders
  {:canonical-edn-v1 {:path "manifest/project-edn.cljs"
                      :args ["--classpath" ".:scripts/nbb_compat"
                             "manifest/project-edn.cljs" "project"]}})

(def hex64? #(boolean (re-matches #"[0-9a-f]{64}" (str %))))
(def git-oid? #(boolean (re-matches #"[0-9a-f]{40}|[0-9a-f]{64}" (str %))))
(def nonblank? #(and (string? %) (not (str/blank? %))))
(def annex-key? #(boolean (re-matches #"[A-Z0-9][A-Z0-9_-]*-s[0-9]+--.+" (str %))))
(def cid? #(boolean (or (re-matches #"Qm[1-9A-HJ-NP-Za-km-z]{44}" (str %))
                        (re-matches #"b[a-z2-7]{20,}" (str %)))))

(defn fail! [message data]
  (throw (ex-info message (assoc data :projection/error true))))

(defn inside-root [p]
  (when-not (nonblank? p)
    (fail! "projection path must be a non-empty string" {:path p}))
  (let [absolute (.resolve path root p)
        relative (.relative path root absolute)]
    (when (or (= relative "..") (str/starts-with? relative (str ".." (.-sep path))))
      (fail! "projection path escapes repository root" {:path p}))
    absolute))

(defn run-file [program args options]
  (let [result (.spawnSync child-process program (clj->js args) (clj->js options))]
    {:exit (or (.-status result) (when (.-error result) 127))
     :out (if (.-stdout result) (.toString (.-stdout result)) "")
     :err (if (.-stderr result) (.toString (.-stderr result)) "")
     :error (.-error result)}))

(defn git [& args]
  (run-file "git" args {:cwd root :encoding "utf8" :stdio ["ignore" "pipe" "pipe"]}))

(defn sha256-bytes [bytes]
  (-> (.createHash crypto "sha256") (.update bytes) (.digest "hex")))

(defn sha256-file [p]
  (let [absolute (inside-root p)]
    (when-not (.existsSync fs absolute)
      (fail! "declared projection file is missing" {:path p}))
    (sha256-bytes (.readFileSync fs absolute))))

(defn git-relative-path [p]
  (let [absolute (inside-root p)
        relative (.relative path root absolute)]
    (str/join "/" (js->clj (.split relative (.-sep path))))))

(defn sha256-at-commit [commit p]
  (let [git-path (git-relative-path p)
        result (git "show" (str commit ":" git-path))]
    (when-not (zero? (:exit result))
      (fail! "declared projection file is absent from source commit"
             {:git/commit commit :path git-path}))
    (sha256-bytes (js/Buffer.from (:out result)))))

(defn require-key [m k where]
  (when-not (contains? m k)
    (fail! "projection contract is missing a required key" {:where where :key k}))
  (get m k))

(defn validate-input [i n]
  (when-not (map? i)
    (fail! "projection input must be a map" {:index n :value i}))
  (case (:input/type i)
    :git
    (do (inside-root (require-key i :input/path [:projection/inputs n]))
        (when-not (hex64? (require-key i :input/sha256 [:projection/inputs n]))
          (fail! "git input requires a lowercase SHA-256" {:index n})))
    :annex
    (when-not (annex-key? (require-key i :input/key [:projection/inputs n]))
      (fail! "annex input requires a structurally valid git-annex key" {:index n}))
    :cid
    (when-not (cid? (require-key i :input/cid [:projection/inputs n]))
      (fail! "CID input requires a structurally valid CIDv0 or base32 CIDv1" {:index n}))
    (fail! "projection input type must be :git, :annex, or :cid"
           {:index n :type (:input/type i)})))

(defn safe-output-path! [projection-id output-path]
  (inside-root output-path)
  (let [expected (str ".projection-cache/" projection-id ".edn")]
    (when-not (= expected output-path)
      (fail! "projection output path must be the projection-specific cache path"
             {:expected expected :actual output-path}))))

(defn validate-contract [m]
  (when-not (map? m) (fail! "projection contract must be an EDN map" {}))
  (when-not (= 2 (:projection/version m))
    (fail! "unsupported projection contract version; v1 argv contracts are unsafe"
           {:version (:projection/version m)}))
  (let [projection-id (:projection/id m)]
    (when-not (and (nonblank? projection-id)
                   (re-matches #"[a-z0-9][a-z0-9-]*" projection-id))
      (fail! "projection id must be a lowercase filesystem-safe slug" {}))
    (let [source (require-key m :projection/source :projection)
          inputs (require-key m :projection/inputs :projection)
          identity-attrs (require-key m :projection/identity-attrs :projection)
          contracts (require-key m :projection/contracts :projection)
          rebuild (require-key m :projection/rebuild :projection)
          output (require-key m :projection/output :projection)
          loader-id (:loader/id rebuild)
          allowed (get allowed-loaders loader-id)]
      (when-not (and (map? source) (git-oid? (:git/commit source))
                     (nonblank? (:dataset/id source)))
        (fail! "projection source requires :git/commit and :dataset/id" {:source source}))
      (when-not (vector? inputs) (fail! "projection inputs must be a vector" {}))
      (doseq [[n i] (map-indexed vector inputs)] (validate-input i n))
      (when-not (and (vector? identity-attrs) (seq identity-attrs)
                     (every? keyword? identity-attrs))
        (fail! "projection identity attrs must be a non-empty vector of keywords" {}))
      (doseq [[path-key hash-key] [[:schema/path :schema/sha256]
                                   [:loader/path :loader/sha256]]]
        (inside-root (require-key contracts path-key :projection/contracts))
        (when-not (hex64? (require-key contracts hash-key :projection/contracts))
          (fail! "projection contract file requires a lowercase SHA-256"
                 {:path-key path-key :hash-key hash-key})))
      (when-not allowed
        (fail! "projection loader is not allowlisted" {:loader/id loader-id}))
      (when-not (= (:path allowed) (:loader/path contracts))
        (fail! "projection loader path does not match allowlisted loader"
               {:loader/id loader-id :expected (:path allowed)
                :actual (:loader/path contracts)}))
      (safe-output-path! projection-id
                         (require-key rebuild :output/path :projection/rebuild))
      (when (contains? rebuild :argv)
        (fail! "projection v2 forbids contract-provided argv" {}))
      (when-not (hex64? (:logical-sha256 output))
        (fail! "projection output requires :logical-sha256" {}))
      (when-let [physical (:physical-sha256 output)]
        (when-not (hex64? physical)
          (fail! "projection physical-sha256 must be lowercase SHA-256" {})))
      (when-not (and (integer? (:entity-count output))
                     (not (neg? (:entity-count output))))
        (fail! "projection output entity-count must be a non-negative integer" {}))))
  m)

(defn verify-current-hash [p expected kind]
  (let [actual (sha256-file p)]
    (when-not (= expected actual)
      (fail! "projection file hash mismatch"
             {:kind kind :path p :expected expected :actual actual}))))

(defn verify-pinned-hash [commit p expected kind]
  (when-not snapshot-mode?
    (let [pinned (sha256-at-commit commit p)]
      (when-not (= expected pinned)
        (fail! "projection source-commit file hash mismatch"
               {:kind kind :git/commit commit :path p
                :expected expected :actual pinned}))))
  (verify-current-hash p expected kind))

(defn stable-key [x] (pr-str x))
(defn canonical-value [x]
  (cond
    (map? x) [:map (->> x (map (fn [[k v]] [(canonical-value k) (canonical-value v)]))
                        (sort-by stable-key) vec)]
    (set? x) [:set (->> x (map canonical-value) (sort-by stable-key) vec)]
    (vector? x) [:vector (mapv canonical-value x)]
    (sequential? x) [:list (mapv canonical-value x)]
    :else x))

(defn schema-index [schema-path]
  (let [schema (edn/read-string (.readFileSync fs (inside-root schema-path) "utf8"))]
    (into {} (map (juxt :db/ident identity) schema))))

(defn logical-datoms [contract output-path]
  (let [x (edn/read-string (.readFileSync fs (inside-root output-path) "utf8"))
        entities (cond (vector? x) x (map? x) [x]
                       :else (fail! "projection output must be EDN tx-data" {}))
        identity-attrs (:projection/identity-attrs contract)
        schema (schema-index (get-in contract [:projection/contracts :schema/path]))]
    (->> entities
         (mapcat
          (fn [entity]
            (when-not (map? entity)
              (fail! "projection output entity must be a map" {:entity entity}))
            (let [identity (mapv (fn [attr]
                                   (when-not (contains? entity attr)
                                     (fail! "projection output lacks identity attribute"
                                            {:attribute attr :db/id (:db/id entity)}))
                                   [attr (canonical-value (get entity attr))])
                                 identity-attrs)]
              (for [[attr value] entity
                    :when (not= attr :db/id)
                    logical-value (if (= :db.cardinality/many
                                         (get-in schema [attr :db/cardinality]))
                                    (if (or (set? value) (sequential? value)) value [value])
                                    [value])]
                [identity attr (canonical-value logical-value)]))))
         distinct
         (sort-by stable-key)
         vec)))

(defn output-measurement [contract output-path]
  (let [datoms (logical-datoms contract output-path)
        x (edn/read-string (.readFileSync fs (inside-root output-path) "utf8"))]
    {:logical-sha256 (sha256-bytes (js/Buffer.from (str (pr-str datoms) "\n")))
     :physical-sha256 (sha256-file output-path)
     :entity-count (if (vector? x) (count x) 1)
     :datom-count (count datoms)}))

(defn clean-child-env []
  (let [env (.-env js/process)]
    #js {:PATH (or (aget env "PATH") "")
         :HOME (or (aget env "HOME") "")
         :TMPDIR (or (aget env "TMPDIR") "/tmp")
         :LANG (or (aget env "LANG") "C.UTF-8")
         :PROJECTION_ROOT root
         :NO_PROXY "*"}))

(defn run-loader! [contract contract-path output-path]
  (let [loader (get allowed-loaders (get-in contract [:projection/rebuild :loader/id]))
        ;; Reuse the already-running, trusted nbb entrypoint. This avoids PATH
        ;; lookup and prevents a child from installing or resolving packages.
        result (run-file (first process-argv)
                         (into [(second process-argv)]
                               (into (:args loader) [contract-path output-path]))
                         {:cwd root :env (clean-child-env) :timeout 60000
                          :maxBuffer (* 4 1024 1024)
                          :stdio ["ignore" "pipe" "pipe"]})]
    (when-not (zero? (:exit result))
      (fail! "projection rebuild command failed"
             {:loader/id (get-in contract [:projection/rebuild :loader/id])
              :exit (:exit result) :err (:err result)}))))

(defn verify-source! [commit]
  (when-not snapshot-mode?
    (when-not (zero? (:exit (git "cat-file" "-e" (str commit "^{commit}"))))
      (fail! "projection source commit is not available locally" {:git/commit commit}))
    (when-not (zero? (:exit (git "merge-base" "--is-ancestor" commit "HEAD")))
      (fail! "projection source commit is not an ancestor of checkout HEAD"
             {:git/commit commit}))))

(defn measure-contract [contract contract-path]
  (validate-contract contract)
  (.mkdirSync fs (.join path root ".projection-cache") #js {:recursive true})
  (let [tmp (.mkdtempSync fs (.join path root ".projection-cache/.verify-"))
        output (.join path tmp "output.edn")
        relative (.relative path root output)]
    (try
      (run-loader! contract contract-path relative)
      (output-measurement contract relative)
      (finally (.rmSync fs tmp #js {:recursive true :force true})))))

(defn verify-contract [contract contract-path]
  (validate-contract contract)
  (let [commit (get-in contract [:projection/source :git/commit])
        contracts (:projection/contracts contract)]
    (verify-source! commit)
    (doseq [i (:projection/inputs contract) :when (= :git (:input/type i))]
      (verify-pinned-hash commit (:input/path i) (:input/sha256 i) :input))
    (verify-pinned-hash commit (:schema/path contracts) (:schema/sha256 contracts) :schema)
    (verify-pinned-hash commit (:loader/path contracts) (:loader/sha256 contracts) :loader)
    (let [actual (measure-contract contract contract-path)
          expected (:projection/output contract)]
      (doseq [[expected-key actual-key] [[:logical-sha256 :logical-sha256]
                                         [:entity-count :entity-count]]]
        (when-not (= (get expected expected-key) (get actual actual-key))
          (fail! "projection logical output mismatch"
                 {:field expected-key :expected (get expected expected-key)
                  :actual (get actual actual-key)})))
      (when-let [expected-physical (:physical-sha256 expected)]
        (when-not (= expected-physical (:physical-sha256 actual))
          (fail! "projection physical output mismatch"
                 {:expected expected-physical :actual (:physical-sha256 actual)})))
      actual)))

(defn read-contract [p]
  (edn/read-string (.readFileSync fs (inside-root p) "utf8")))

(defn contract-files []
  (let [dir (inside-root "manifest/projections")]
    (->> (.readdirSync fs dir)
         js->clj
         (filter #(str/ends-with? % ".edn"))
         sort
         (mapv #(str "manifest/projections/" %)))))

(defn self-test []
  (let [h (apply str (repeat 64 "a"))
        g (apply str (repeat 40 "b"))
        valid {:projection/version 2 :projection/id "test"
               :projection/source {:git/commit g :dataset/id "test/data"}
               :projection/inputs [{:input/type :git :input/path "CLAUDE.md" :input/sha256 h}
                                   {:input/type :annex :input/key "SHA256E-s1--abc"}
                                   {:input/type :cid :input/cid "bafybeigdyrzt234567abcdefghijklmnopqrstuvwxyz"}]
               :projection/identity-attrs [:adr/id]
               :projection/contracts {:schema/path "manifest/schema.edn" :schema/sha256 h
                                      :loader/path "manifest/project-edn.cljs" :loader/sha256 h}
               :projection/rebuild {:loader/id :canonical-edn-v1
                                    :output/path ".projection-cache/test.edn"}
               :projection/output {:logical-sha256 h :entity-count 0}}]
    (assert (= valid (validate-contract valid)))
    (assert (try (validate-contract (assoc valid :projection/version 1)) false
                 (catch :default _ true)))
    (assert (try (validate-contract (assoc-in valid [:projection/rebuild :argv] ["sh"])) false
                 (catch :default _ true)))
    (assert (try (validate-contract (assoc-in valid [:projection/rebuild :output/path] "CLAUDE.md")) false
                 (catch :default _ true)))
    (assert (try (validate-contract (assoc-in valid [:projection/rebuild :loader/id] :evil)) false
                 (catch :default _ true)))
    (.mkdirSync fs (.join path root ".projection-cache") #js {:recursive true})
    (let [tmp (.mkdtempSync fs (.join path root ".projection-cache/.logical-test-"))
          a (.relative path root (.join path tmp "a.edn"))
          b (.relative path root (.join path tmp "b.edn"))
          logical-contract (-> valid
                               (assoc :projection/identity-attrs [:adr/id])
                               (assoc-in [:projection/contracts :schema/path]
                                         "manifest/projection-schemas/agent-source-query-adr.edn"))]
      (try
        (.writeFileSync fs (inside-root a)
                        (pr-str [{:db/id -1 :adr/id "same" :adr/repos ["a" "b"]}]))
        (.writeFileSync fs (inside-root b)
                        (pr-str [{:db/id -99 :adr/id "same" :adr/repos ["b" "a"]}]))
        (assert (= (:logical-sha256 (output-measurement logical-contract a))
                   (:logical-sha256 (output-measurement logical-contract b))))
        (assert (not= (:physical-sha256 (output-measurement logical-contract a))
                      (:physical-sha256 (output-measurement logical-contract b))))
        (finally (.rmSync fs tmp #js {:recursive true :force true}))))
    (println "projection-verify self-test: 7/7 pass")))

(defn usage []
  (println "usage: projection-verify.cljs check|verify|measure <projection.edn> | verify-all | self-test"))

(let [command (first (filter #{"check" "verify" "measure" "verify-all" "self-test"}
                             process-argv))
      command-index (.indexOf process-argv command)
      contract-path (when (and command (not= command "verify-all")
                               (< (inc command-index) (count process-argv)))
                      (get process-argv (inc command-index)))]
  (try
    (case command
      "check" (do (validate-contract (read-contract contract-path))
                    (println "projection check: PASS" contract-path))
      "measure" (println (pr-str (measure-contract (read-contract contract-path) contract-path)))
      "verify" (do (println (pr-str (verify-contract (read-contract contract-path) contract-path)))
                     (println "projection verify: PASS" contract-path))
      "verify-all" (let [files (contract-files)]
                     (when-not (seq files) (fail! "no projection contracts found" {}))
                     (doseq [p files]
                       (verify-contract (read-contract p) p)
                       (println "projection verify: PASS" p))
                     (println "projection verify-all: PASS" (count files) "contract(s)"))
      "self-test" (self-test)
      (do (usage) (set! (.-exitCode js/process) 2)))
    (catch :default e
      (js/console.error "projection verify: FAIL" (ex-message e) (pr-str (ex-data e)))
      (set! (.-exitCode js/process) 1))))

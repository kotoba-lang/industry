#!/usr/bin/env nbb
;; manifest/projection-verify.cljs — ADR-2608039700 executable projection gate.
;;
;; A query database is a projection only when a pinned Git commit, declared
;; inputs, schema, and loader can reproduce a declared output. This verifier
;; checks that claim without invoking a shell:
;;
;;   nbb --classpath ".:scripts/nbb_compat" manifest/projection-verify.cljs check  projection.edn
;;   nbb --classpath ".:scripts/nbb_compat" manifest/projection-verify.cljs verify projection.edn
;;   nbb --classpath ".:scripts/nbb_compat" manifest/projection-verify.cljs self-test
;;
;; Contract shape:
;; {:projection/version 1
;;  :projection/id "docs"
;;  :projection/source {:git/commit "<40-or-64-hex>" :dataset/id "root/docs"}
;;  :projection/inputs [{:input/type :git :input/path "manifest/schema.edn"
;;                       :input/sha256 "<64-hex>"}
;;                      {:input/type :annex :input/key "SHA256E-s...--..."}
;;                      {:input/type :cid :input/cid "bafy..."}]
;;  :projection/contracts {:schema/path "manifest/schema.edn"
;;                         :schema/sha256 "<64-hex>"
;;                         :loader/path "manifest/edn-query.cljs"
;;                         :loader/sha256 "<64-hex>"}
;;  :projection/rebuild {:argv ["nbb" "..."], :output/path "out/datoms.edn"}
;;  :projection/output {:sha256 "<64-hex>" :determinism :logical
;;                      :entity-count 1}}
;;
;; `check` is read-only and structural. `verify` additionally checks that the
;; commit exists, hashes every declared local input/contract, runs :argv
;; directly (never through sh -c), hashes :output/path, parses it as EDN, and
;; checks :entity-count. Annex/CID declarations prove identity binding; their
;; custody/availability remains the separate annex-custody gate.

(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.shell :as shell])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def crypto (js/require "node:crypto"))
(def root
  (str/trim (:out (shell/sh "git" "rev-parse" "--show-toplevel"))))

(def hex64? #(boolean (re-matches #"[0-9a-f]{64}" (str %))))
(def git-oid? #(boolean (re-matches #"[0-9a-f]{40}|[0-9a-f]{64}" (str %))))
(def nonblank? #(and (string? %) (not (str/blank? %))))

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

(defn sha256-file [p]
  (let [absolute (inside-root p)]
    (when-not (.existsSync fs absolute)
      (fail! "declared projection file is missing" {:path p}))
    (-> (.createHash crypto "sha256")
        (.update (.readFileSync fs absolute))
        (.digest "hex"))))

(defn require-key [m k where]
  (when-not (contains? m k)
    (fail! "projection contract is missing a required key" {:where where :key k}))
  (get m k))

(defn validate-input [i n]
  (when-not (map? i)
    (fail! "projection input must be a map" {:index n :value i}))
  (case (:input/type i)
    :git
    (do
      (inside-root (require-key i :input/path [:projection/inputs n]))
      (when-not (hex64? (require-key i :input/sha256 [:projection/inputs n]))
        (fail! "git input requires a lowercase SHA-256" {:index n})))

    :annex
    (when-not (nonblank? (require-key i :input/key [:projection/inputs n]))
      (fail! "annex input requires a non-empty key" {:index n}))

    :cid
    (when-not (nonblank? (require-key i :input/cid [:projection/inputs n]))
      (fail! "CID input requires a non-empty CID" {:index n}))

    (fail! "projection input type must be :git, :annex, or :cid"
           {:index n :type (:input/type i)})))

(defn validate-contract [m]
  (when-not (map? m)
    (fail! "projection contract must be an EDN map" {}))
  (when-not (= 1 (:projection/version m))
    (fail! "unsupported projection contract version"
           {:version (:projection/version m)}))
  (when-not (nonblank? (:projection/id m))
    (fail! "projection id must be a non-empty string" {}))
  (let [source (require-key m :projection/source :projection)
        inputs (require-key m :projection/inputs :projection)
        contracts (require-key m :projection/contracts :projection)
        rebuild (require-key m :projection/rebuild :projection)
        output (require-key m :projection/output :projection)]
    (when-not (and (map? source)
                   (git-oid? (:git/commit source))
                   (nonblank? (:dataset/id source)))
      (fail! "projection source requires :git/commit and :dataset/id" {:source source}))
    (when-not (vector? inputs)
      (fail! "projection inputs must be a vector" {}))
    (doseq [[n i] (map-indexed vector inputs)] (validate-input i n))
    (doseq [[path-key hash-key] [[:schema/path :schema/sha256]
                                 [:loader/path :loader/sha256]]]
      (inside-root (require-key contracts path-key :projection/contracts))
      (when-not (hex64? (require-key contracts hash-key :projection/contracts))
        (fail! "projection contract file requires a lowercase SHA-256"
               {:path-key path-key :hash-key hash-key})))
    (when-not (and (vector? (:argv rebuild))
                   (seq (:argv rebuild))
                   (every? nonblank? (:argv rebuild)))
      (fail! "projection rebuild argv must be a non-empty vector of strings" {}))
    (inside-root (require-key rebuild :output/path :projection/rebuild))
    (when-not (hex64? (:sha256 output))
      (fail! "projection output requires a lowercase SHA-256" {}))
    (when-not (#{:logical :physical} (:determinism output))
      (fail! "projection determinism must be :logical or :physical" {}))
    (when-not (and (integer? (:entity-count output))
                   (not (neg? (:entity-count output))))
      (fail! "projection output entity-count must be a non-negative integer" {})))
  m)

(defn verify-hash [p expected kind]
  (let [actual (sha256-file p)]
    (when-not (= expected actual)
      (fail! "projection file hash mismatch"
             {:kind kind :path p :expected expected :actual actual}))))

(defn edn-entity-count [p]
  (let [x (edn/read-string (.readFileSync fs (inside-root p) "utf8"))]
    (cond
      (vector? x) (count x)
      (map? x) 1
      :else (fail! "projection output must be an EDN map or vector" {:path p}))))

(defn verify-contract [m]
  (validate-contract m)
  (let [source (:projection/source m)
        contracts (:projection/contracts m)
        rebuild (:projection/rebuild m)
        output (:projection/output m)
        commit (:git/commit source)
        commit-check (shell/sh "git" "cat-file" "-e" (str commit "^{commit}"))]
    (when-not (zero? (:exit commit-check))
      (fail! "projection source commit is not available locally" {:git/commit commit}))
    (doseq [i (:projection/inputs m)
            :when (= :git (:input/type i))]
      (verify-hash (:input/path i) (:input/sha256 i) :input))
    (verify-hash (:schema/path contracts) (:schema/sha256 contracts) :schema)
    (verify-hash (:loader/path contracts) (:loader/sha256 contracts) :loader)
    (let [argv (:argv rebuild)
          result (apply shell/sh argv)]
      (when-not (zero? (:exit result))
        (fail! "projection rebuild command failed"
               {:argv argv :exit (:exit result) :err (:err result)})))
    (verify-hash (:output/path rebuild) (:sha256 output) :output)
    (let [actual-count (edn-entity-count (:output/path rebuild))]
      (when-not (= (:entity-count output) actual-count)
        (fail! "projection entity count mismatch"
               {:expected (:entity-count output) :actual actual-count})))
    m))

(defn read-contract [p]
  (edn/read-string (.readFileSync fs (inside-root p) "utf8")))

(defn expect-failure [f]
  (try (f) false (catch :default e (true? (:projection/error (ex-data e))))))

(defn self-test []
  (let [h (apply str (repeat 64 "a"))
        g (apply str (repeat 40 "b"))
        valid {:projection/version 1
               :projection/id "test"
               :projection/source {:git/commit g :dataset/id "test/data"}
               :projection/inputs [{:input/type :git :input/path "CLAUDE.md"
                                    :input/sha256 h}
                                   {:input/type :annex :input/key "SHA256E-s1--00"}
                                   {:input/type :cid :input/cid "bafytest"}]
               :projection/contracts {:schema/path "manifest/schema.edn"
                                      :schema/sha256 h
                                      :loader/path "manifest/edn-query.cljs"
                                      :loader/sha256 h}
               :projection/rebuild {:argv ["nbb" "noop.cljs"]
                                    :output/path "out/test.edn"}
               :projection/output {:sha256 h :determinism :logical
                                   :entity-count 0}}]
    (assert (= valid (validate-contract valid)))
    (assert (expect-failure #(validate-contract (dissoc valid :projection/source))))
    (assert (expect-failure #(validate-contract (assoc-in valid [:projection/source :git/commit] "main"))))
    (assert (expect-failure #(validate-contract (assoc-in valid [:projection/inputs 0 :input/path] "../escape"))))
    (assert (expect-failure #(validate-contract (assoc-in valid [:projection/output :determinism] :unknown))))
    (let [tmp (.mkdtempSync fs (.join path root ".projection-self-test-"))
          relative #(.relative path root %)
          input (.join path tmp "input.edn")
          schema (.join path tmp "schema.edn")
          loader (.join path tmp "loader.cljs")
          output (.join path tmp "output.edn")]
      (try
        (.writeFileSync fs input "[{:db/id -1 :test/value 1}]\n")
        (.writeFileSync fs schema "[]\n")
        (.writeFileSync fs loader ";; self-test loader identity\n")
        (let [head (str/trim (:out (shell/sh "git" "rev-parse" "HEAD")))
              input-hash (sha256-file (relative input))
              contract {:projection/version 1
                        :projection/id "self-test-rebuild"
                        :projection/source {:git/commit head :dataset/id "test/data"}
                        :projection/inputs [{:input/type :git
                                             :input/path (relative input)
                                             :input/sha256 input-hash}]
                        :projection/contracts {:schema/path (relative schema)
                                               :schema/sha256 (sha256-file (relative schema))
                                               :loader/path (relative loader)
                                               :loader/sha256 (sha256-file (relative loader))}
                        :projection/rebuild
                        {:argv ["node" "-e"
                                "require('node:fs').copyFileSync(process.argv[1],process.argv[2])"
                                input output]
                         :output/path (relative output)}
                        :projection/output {:sha256 input-hash
                                            :determinism :physical
                                            :entity-count 1}}]
          (verify-contract contract))
        (finally
          (.rmSync fs tmp #js {:recursive true :force true}))))
    (println "projection-verify self-test: 6/6 pass")))

(defn usage []
  (println "usage: projection-verify.cljs check|verify <projection.edn> | self-test"))

(let [argv (vec (js->clj (.-argv js/process)))
      command-index (first (keep-indexed (fn [n x]
                                           (when (#{"check" "verify" "self-test"} x) n))
                                         argv))
      command (when command-index (nth argv command-index))
      contract-path (when (and command-index (< (inc command-index) (count argv)))
                      (nth argv (inc command-index)))]
  (try
    (case command
      "check" (do (validate-contract (read-contract contract-path))
                  (println "projection check: PASS" contract-path))
      "verify" (do (verify-contract (read-contract contract-path))
                   (println "projection verify: PASS" contract-path))
      "self-test" (self-test)
      (do (usage) (set! (.-exitCode js/process) 2)))
    (catch :default e
      (js/console.error "projection verify: FAIL" (ex-message e) (pr-str (ex-data e)))
      (set! (.-exitCode js/process) 1))))

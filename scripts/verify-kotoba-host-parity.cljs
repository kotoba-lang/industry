#!/usr/bin/env nbb
;; verify-kotoba-host-parity — the declared host-import set in
;; orgs/kotoba-lang/kotoba-lang/lang/host-parity.edn against the two
;; implementations that must agree with it: kototama's contract.cljc and
;; wasm-webcomponent's actor-host.js.
;;
;; It reads three files under `orgs/`, so it cannot be a murakumo fleet gate:
;; a gate ships only the target repo's own tree and would find an empty
;; workspace. It is registered in `manifest/orgs-detectors.edn` instead.
;;
;; ## It used to stop at the first violation
;;
;; Until 2026-08-13 every check called `process.exit(1)` on the spot, so a run
;; only ever showed ONE violation and the true count was never visible. That is
;; the same defect ADR-2608124800 measured on `root-repository-roles`, where a
;; fail-fast verifier meant every later assertion had gone unexecuted since
;; 2026-08-03 without anyone knowing. The checks are independent -- none of them
;; consumes a value another one produces -- so they now all run and the failures
;; are collected. The exit code is unchanged: 1 if anything failed, 0 otherwise.
;;
;; --findings additionally emits one machine-readable line per finding:
;;
;;   FINDING<TAB>severity<TAB>key<TAB>detail
;;
;; plus one evidence line
;;
;;   SCANNED<TAB>n<TAB>unit
;;
;; for scripts/orgs-detector-tick.cljs, which needs stable per-finding identity
;; so it can tell a finding that appeared today from one that has been true for
;; weeks. The key is the name of the invariant, never a count -- a key carrying
;; a number that moves while the defect stays the same would report the same
;; violation as resolved-and-new on every run.
(require '[cljs.reader :as reader]
         '[clojure.set :as set]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def argv (vec (drop 2 js/process.argv)))
(def findings? (some #{"--findings"} argv))
(def root (.cwd js/process))
(def sources ["orgs/kotoba-lang/kotoba-lang/lang/host-parity.edn"
              "orgs/kotoba-lang/kototama/src/kototama/contract.cljc"
              "orgs/kotoba-lang/wasm-webcomponent/src/actor-host.js"])
(defn text [relative] (.readFileSync fs (str root "/" relative) "utf8"))

(def failures (atom []))
(defn fail! [key message data]
  (swap! failures conj {:key key :message message :data data})
  (println "HOST PARITY FAIL:" message (pr-str data)))

(let [catalog (reader/read-string (text (nth sources 0)))
      required (:required-imports catalog)
      contract-source (text (nth sources 1))
      browser-source (text (nth sources 2))
      contract-ids (set (map (comp keyword second)
                             (re-seq #":import/id\s+:([a-z0-9-]+)" contract-source)))
      browser-ids (set (map (comp keyword second)
                            (re-seq #"\{\s*id:\s*'([a-z0-9-]+)'\s*,\s*category:" browser-source)))
      override-ids (set (keys (:imports catalog)))
      profile (:browser-profile catalog)
      categories (map #(get profile % #{})
                      [:required :intentional-native-boundary
                       :deferred-provider-components :deferred-host-injection])
      classified (apply set/union #{} categories)
      category-total (reduce + (map count categories))
      statuses (get-in catalog [:acceptance :browser-linkable-statuses])
      default-row (:unlisted-import-default catalog)
      browser-linkable (set (filter (fn [id]
                                      (contains? statuses
                                                 (:browser (merge default-row
                                                                  (get-in catalog [:imports id])))))
                                    required))]
  (when-not (= required contract-ids browser-ids)
    (fail! "import-sets-drifted" "declared import sets drifted"
           {:language-only (set/difference required contract-ids browser-ids)
            :contract-only (set/difference contract-ids required)
            :browser-only (set/difference browser-ids required)}))
  (when-not (set/subset? override-ids required)
    (fail! "unknown-override" "host-parity overrides contain unknown imports"
           (set/difference override-ids required)))
  (when-not (and (= required classified) (= (count required) category-total))
    (fail! "browser-profile-not-a-partition" "browser profile is not a complete disjoint partition"
           {:required-count (count required)
            :classified-count (count classified)
            :category-total category-total
            :unclassified (set/difference required classified)}))
  (let [implemented-fields (set (map second (re-seq #"fns\.([a-z0-9_]+)\s*=" browser-source)))
        implemented-ids (set (map #(keyword (str/replace % "_" "-")) implemented-fields))
        browser-status (fn [id] (:browser (merge default-row (get-in catalog [:imports id]))))
        ;; `actor-host.js` is ONE file serving two hosts: the browser host and
        ;; the Node actor host. `fns.<x> =` therefore does not mean "callable in
        ;; a browser", and nothing in the file's syntax separates the two. This
        ;; set is the discriminator, and it is hand-maintained, so it is checked
        ;; below rather than trusted.
        ;;
        ;; 2026-08-13 (ADR-2608132600): it had rotted. The eleven pg wire/pool
        ;; functions added on 2026-07-31 ("Node pg-pool/wire/scram inject
        ;; fail-closed") were never added here, so 11 of the 17 ids this check
        ;; reported were the list being stale rather than the hosts disagreeing.
        ;; The six that remain are the real defect.
        not-browser-callable
        #{;; no synchronous network in a browser
          :llm-infer
          ;; intentional native boundary: the key, socket or credential must
          ;; never enter a browser guest
          :kagi-sign :transport-connect :tls-open :tls-server-end-point
          :transport-write :transport-read :transport-close :scram-sha256
          :pg-cancel-register :pg-cancel
          ;; provider-gated: the function exists, but every path through it is
          ;; behind an injected JVM-tender/Node provider (`wireP` / `poolP`),
          ;; so in a browser it is fail-closed rather than available
          :pg-open :pg-query :pg-simple-query
          :pg-pool-open :pg-pool-acquire :pg-pool-query :pg-pool-release
          :pg-pool-stats :pg-pool-health :pg-pool-drain :pg-pool-close}
        browser-callable (apply disj implemented-ids not-browser-callable)]
    ;; The exclusion list is the one hand-written thing in this verifier, so it
    ;; gets its own two assertions. Without them a stale entry is invisible: it
    ;; silently subtracts an id from the comparison, which is the direction that
    ;; makes a check pass rather than fail.
    (let [absent (set/difference not-browser-callable implemented-ids)]
      (when (seq absent)
        (fail! "exclusion-list-names-unimplemented"
               "exclusion list names host functions actor-host.js no longer implements"
               {:absent absent})))
    (let [claimed (set (filter #(not= :no (browser-status %)) not-browser-callable))]
      (when (seq claimed)
        (fail! "exclusion-list-contradicts-matrix"
               "matrix claims browser availability for an import this verifier excludes as not browser-callable"
               {:ids (into (sorted-map) (map (juxt identity browser-status)) claimed)})))
    (when-not (= browser-linkable browser-callable)
      (fail! "matrix-vs-implementation" "browser matrix disagrees with implemented host functions"
             {:matrix-only (set/difference browser-linkable browser-callable)
              :implementation-only (set/difference browser-callable browser-linkable)})))
  (when-not (= 10 (count browser-linkable))
    (fail! "browser-linkable-count-changed" "browser linkability evidence changed without qualification"
           {:count (count browser-linkable) :imports browser-linkable}))
  (when (empty? @failures)
    (println "KOTOBA HOST PARITY PASS:"
             (count required) "declared imports;"
             (count browser-linkable) "browser-linkable; contract and JS sets agree"))
  (when findings?
    ;; The evidence line first: a run that scanned nothing must not be
    ;; recordable as clean. `required` is the set this verifier actually
    ;; resolved out of the three sources, so a run that failed to read them
    ;; cannot emit a non-zero count here.
    (println (str "SCANNED\t" (count required) "\tdeclared host import(s) across "
                  (count sources) " source file(s)"))
    (doseq [{:keys [key message data]} @failures]
      (println (str "FINDING\tfail\t" key "\t" message " " (pr-str data))))))

(when (seq @failures) (.exit js/process 1))

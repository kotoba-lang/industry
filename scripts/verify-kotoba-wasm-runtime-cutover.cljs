#!/usr/bin/env nbb
;; verify-kotoba-wasm-runtime-cutover — the frozen legacy inventory in
;; 90-docs/migration/kotoba-wasm-runtime-cutover.edn against the deps.edn files
;; it freezes, AND a sweep of every registered checkout for Chicory sites the
;; inventory does not name.
;;
;; The sweep is the half that was missing until 2026-09-08. The contract says
;; `:new-chicory-call-sites :forbidden`, and the only enforcement was a scan of
;; the same four files the inventory already froze -- so a new site in a fifth
;; repository was invisible by construction, and `problems=0` meant "did not
;; look".
;;
;; It reads `orgs/kotoba-lang/*/deps.edn`, so it cannot be a murakumo fleet
;; gate: a gate ships only the target repo's own tree and would find an empty
;; workspace. It is registered in `manifest/orgs-detectors.edn` instead.
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
;; weeks. The key must be structural (a path, an id) and must NOT contain a
;; count or any other number that moves while the defect stays the same --
;; otherwise every run reports the same defect as resolved-and-new.
;; Adding the flag changes nothing about what is measured or about the exit
;; code; without it, output is byte-identical to before.
(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def argv (vec (drop 2 js/process.argv)))
(def findings? (some #{"--findings"} argv))
(def root (.cwd js/process))
(def contract-path "90-docs/migration/kotoba-wasm-runtime-cutover.edn")
(defn full [p] (.join path root p))
(defn read-text [p] (.readFileSync fs (full p) "utf8"))
(defn exists? [p] (.existsSync fs (full p)))
(def contract (edn/read-string (read-text contract-path)))
(def errors (atom []))
;; `key` is the stable identity; `message`/`data` stay exactly as they were so
;; the human-readable report is unchanged.
(defn fail! [key message data] (swap! errors conj {:key key :message message :data data}))

;; Every path this run actually opened or asserted on. The evidence line counts
;; this, so a run that resolved nothing cannot be recorded as clean.
(def inspected (atom #{contract-path}))
(defn inspect! [p] (swap! inspected conj p) p)

(when-not (= :kotoba-native-selfhost (get-in contract [:cutover/authority :compiler]))
  (fail! "authority:compiler" "compiler authority must be Kotoba native/selfhost"
         (get contract :cutover/authority)))
(when-not (= :wasm-runtime (get-in contract [:cutover/authority :runtime]))
  (fail! "authority:runtime" "runtime authority must be Wasm"
         (get contract :cutover/authority)))
(when-not (= :forbidden (get-in contract [:cutover/rules :new-jvm-runtime-dependencies]))
  (fail! "rules:new-jvm-runtime-dependencies" "new JVM runtime dependencies must be forbidden"
         (get contract :cutover/rules)))

(doseq [{:keys [id dependency-file implementation status]} (:cutover/legacy-inventory contract)]
  (when-not (contains? #{:deprecated-compat :bootstrap-debt} status)
    (fail! (str "legacy-status:" id) "legacy entry has an invalid status"
           {:id id :status status}))
  (doseq [p [dependency-file implementation]]
    (inspect! p)
    (when-not (exists? p)
      (fail! (str "legacy-path-missing:" p) "legacy inventory path is missing"
             {:id id :path p}))))

(let [allowed (set (map :dependency-file (:cutover/legacy-inventory contract)))
      dependency-files ["orgs/kotoba-lang/amu/deps.edn"
                        "orgs/kotoba-lang/kototama/deps.edn"
                        "orgs/kotoba-lang/aiueos/deps.edn"
                        "orgs/kotoba-lang/kotoba/deps.edn"]]
  ;; The inventory and the scan list have to name the SAME paths, and nothing
  ;; used to check that. When `kotoba-lang/compiler` was renamed to
  ;; `kotoba-lang/amu`, the scan list moved and the inventory did not, and the
  ;; two halves of the contract silently pointed at different repositories: the
  ;; real deps.edn read as an un-frozen JVM dependency, while the frozen entry
  ;; guarded a path this verifier never opens. The existing
  ;; `legacy-path-missing` check did not catch it either, because a stale
  ;; checkout of the OLD name was still on disk, so `existsSync` was true.
  ;; A path in the inventory that is never scanned is a freeze with no subject.
  (doseq [p (sort allowed)
          :when (not (some #{p} dependency-files))]
    (fail! (str "inventory-path-not-scanned:" p)
           "frozen inventory names a dependency file this verifier never scans"
           {:path p :scanned dependency-files}))
  (doseq [p dependency-files
          :let [text (read-text (inspect! p))]
          :when (or (str/includes? text "com.dylibso.chicory")
                    (str/includes? text "org.clojure/clojure"))]
    (when-not (contains? allowed p)
      (fail! (str "jvm-dep-not-frozen:" p)
             "JVM/runtime dependency is not in the frozen legacy inventory" {:path p})))

  ;; --- the rule says FORBIDDEN; until now nothing looked outside four files --
  ;;
  ;; `:cutover/rules :new-chicory-call-sites :forbidden` was enforced by
  ;; scanning exactly the four `dependency-files` above, which are the four the
  ;; inventory already freezes. A new Chicory site in a FIFTH repository could
  ;; not be seen by construction, and `problems=0` therefore meant "did not
  ;; look" rather than "looked and found nothing" -- the failure this
  ;; workspace's own 8 questions put first.
  ;;
  ;; Measured 2026-09-08, when this sweep was added, it immediately found what
  ;; the four-file scan could not: `provider-postgres` and `provider-transport`
  ;; each import `com.dylibso.chicory.wasm.types` directly, both landed AFTER
  ;; the 2026-07-18 freeze; `kototama/clj/deps.edn` carries a second, separate
  ;; Chicory pin; and `mesh` is a new production consumer of the frozen path.
  ;;
  ;; The sweep is over registered checkouts, not the whole disk, and reports
  ;; how many it opened -- a run that walked nothing must not read as clean.
  (let [orgs-dir (full "orgs")
        skip-dir #"/(node_modules|\.git|target|out|dist|build|\.cpcache|\.gitlibs)(/|$)"
        interesting? (fn [f] (or (str/ends-with? f "deps.edn")
                                 (str/ends-with? f ".clj")
                                 (str/ends-with? f ".cljc")))
        ;; A directory holding its own `.git` is a DIFFERENT repository -- a
        ;; nested clone or a worktree someone left inside a checkout. Its files
        ;; are not the registered repo's files and must not be attributed to
        ;; it. Without this the sweep walked
        ;; `orgs/kotoba-lang/kotoba/kotoba/kotoba/kotoba/...` and reported each
        ;; nesting level as its own unfrozen Chicory site.
        nested-repo? (fn [d root?]
                       (and (not root?)
                            (try (.existsSync fs (.join path d ".git"))
                                 (catch :default _ false))))
        walk (fn walk [d depth acc]
               (if (or (> depth 6) (re-find skip-dir (str d "/"))
                       (nested-repo? d (zero? depth))
                       (>= (count acc) 40000))
                 acc
                 (let [entries (try (vec (.readdirSync fs d)) (catch :default _ nil))]
                   (if (nil? entries)
                     acc
                     (reduce (fn [a e]
                               (let [f (.join path d e)]
                                 (if (try (.isDirectory (.statSync fs f)) (catch :default _ false))
                                   (walk f (inc depth) a)
                                   (if (interesting? f) (conj a f) a))))
                             acc entries)))))
        ;; REGISTERED checkouts only, from manifest/west.yml -- not everything
        ;; under orgs/. The first version walked orgs/ wholesale and reported
        ;; 22 "unfrozen Chicory sites" that were all one stray worktree someone
        ;; had left INSIDE the superproject
        ;; (orgs/kotoba-lang/wt-kbb-docstring/kotoba/kotoba/kotoba/...), which
        ;; CLAUDE.md already forbids and which is not a registered repository
        ;; at all. A contract verifier that reports a violation for a directory
        ;; the contract does not govern trains its reader to ignore it.
        registered (->> (str/split-lines (read-text "manifest/west.yml"))
                        (keep #(second (re-find #"^\s*path:\s*(orgs/\S+)" %)))
                        distinct
                        (filter #(try (.isDirectory (.statSync fs (full %)))
                                      (catch :default _ false))))
        files (vec (mapcat #(walk (full %) 0 []) registered))
        rel (fn [f] (subs f (inc (count root))))
        ;; A file is EXCUSED when the inventory already names it, either as the
        ;; dependency-file or as the implementation of a frozen entry.
        frozen (into (set (map :dependency-file (:cutover/legacy-inventory contract)))
                     (keep :implementation (:cutover/legacy-inventory contract)))
        hits (for [f files
                   :let [text (try (.readFileSync fs f "utf8") (catch :default _ nil))]
                   :when (and text (str/includes? text "com.dylibso.chicory"))]
               (rel f))]
    ;; A sweep that walked nothing is not a clean sweep. The floor is stated
    ;; rather than implied: this workspace registers thousands of repos, so a
    ;; handful means the manifest or the disk is not what this run assumed.
    (when (< (count registered) 100)
      (fail! "chicory-sweep-unmeasured"
             "too few registered checkouts were readable, so the forbidden-new-site rule went unmeasured"
             {:registered (count registered)}))
    (swap! inspected into registered)
    (doseq [h (sort hits)
            :when (not (contains? frozen h))]
      (fail! (str "chicory-site-not-frozen:" h)
             "names com.dylibso.chicory and is not in the frozen legacy inventory"
             {:path h}))
    (println (str "  chicory sweep: " (count registered) " registered checkout(s), "
                  (count files) " file(s), "
                  (count hits) " naming com.dylibso.chicory, "
                  (count (remove #(contains? frozen %) hits)) " outside the frozen inventory"))))

(let [ids (set (map :id (:cutover/legacy-inventory contract)))]
  (doseq [{:keys [id owns blocked-by]} (:cutover/tranches contract)
          ref (concat owns blocked-by)]
    (when-not (or (contains? ids ref)
                  (some #(= ref (:id %)) (:cutover/tranches contract)))
      (fail! (str "tranche-unknown-ref:" id ":" ref)
             "tranche references an unknown inventory/tranche id"
             {:tranche id :ref ref}))))

(println (str "Kotoba Wasm/JVM cutover: legacy=" (count (:cutover/legacy-inventory contract))
              " tranches=" (count (:cutover/tranches contract))
              " problems=" (count @errors)))
(doseq [error @errors] (println "  -" (pr-str (dissoc error :key))))
(when findings?
  ;; The evidence line first: a run that scanned nothing must not be recordable
  ;; as clean. The tick refuses to call this detector :ok unless this line is
  ;; present and non-zero.
  (println (str "SCANNED\t" (count @inspected) "\tcontract/dependency path(s)"))
  (doseq [{:keys [key message data]} @errors]
    (println (str "FINDING\tfail\t" key "\t" message " " (pr-str data)))))
(when (seq @errors) (.exit js/process 1))

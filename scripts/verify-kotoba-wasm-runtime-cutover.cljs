#!/usr/bin/env nbb
;; verify-kotoba-wasm-runtime-cutover — the frozen legacy inventory in
;; 90-docs/migration/kotoba-wasm-runtime-cutover.edn against the four deps.edn
;; files it freezes.
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
             "JVM/runtime dependency is not in the frozen legacy inventory" {:path p}))))

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

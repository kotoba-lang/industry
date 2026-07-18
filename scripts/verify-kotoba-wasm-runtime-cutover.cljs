#!/usr/bin/env nbb
(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def root (.cwd js/process))
(def contract-path "90-docs/migration/kotoba-wasm-runtime-cutover.edn")
(defn full [p] (.join path root p))
(defn read-text [p] (.readFileSync fs (full p) "utf8"))
(defn exists? [p] (.existsSync fs (full p)))
(def contract (edn/read-string (read-text contract-path)))
(def errors (atom []))
(defn fail! [message data] (swap! errors conj {:message message :data data}))

(when-not (= :kotoba-native-selfhost (get-in contract [:cutover/authority :compiler]))
  (fail! "compiler authority must be Kotoba native/selfhost" (get contract :cutover/authority)))
(when-not (= :wasm-runtime (get-in contract [:cutover/authority :runtime]))
  (fail! "runtime authority must be Wasm" (get contract :cutover/authority)))
(when-not (= :forbidden (get-in contract [:cutover/rules :new-jvm-runtime-dependencies]))
  (fail! "new JVM runtime dependencies must be forbidden" (get contract :cutover/rules)))

(doseq [{:keys [id dependency-file implementation status]} (:cutover/legacy-inventory contract)]
  (when-not (contains? #{:deprecated-compat :bootstrap-debt} status)
    (fail! "legacy entry has an invalid status" {:id id :status status}))
  (doseq [p [dependency-file implementation]]
    (when-not (exists? p) (fail! "legacy inventory path is missing" {:id id :path p}))))

(let [allowed (set (map :dependency-file (:cutover/legacy-inventory contract)))
      dependency-files ["orgs/kotoba-lang/compiler/deps.edn"
                        "orgs/kotoba-lang/kototama/deps.edn"
                        "orgs/kotoba-lang/aiueos/deps.edn"
                        "orgs/kotoba-lang/kotoba/deps.edn"]]
  (doseq [p dependency-files
          :let [text (read-text p)]
          :when (or (str/includes? text "com.dylibso.chicory")
                    (str/includes? text "org.clojure/clojure"))]
    (when-not (contains? allowed p)
      (fail! "JVM/runtime dependency is not in the frozen legacy inventory" {:path p}))))

(let [ids (set (map :id (:cutover/legacy-inventory contract)))]
  (doseq [{:keys [id owns blocked-by]} (:cutover/tranches contract)
          ref (concat owns blocked-by)]
    (when-not (or (contains? ids ref)
                  (some #(= ref (:id %)) (:cutover/tranches contract)))
      (fail! "tranche references an unknown inventory/tranche id" {:tranche id :ref ref}))))

(println (str "Kotoba Wasm/JVM cutover: legacy=" (count (:cutover/legacy-inventory contract))
              " tranches=" (count (:cutover/tranches contract))
              " problems=" (count @errors)))
(doseq [error @errors] (println "  -" (pr-str error)))
(when (seq @errors) (.exit js/process 1))

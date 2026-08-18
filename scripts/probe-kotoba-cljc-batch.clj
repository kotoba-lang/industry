(require '[clojure.data.json :as json]
         '[kotoba.compiler.core :as compiler])
(import '(java.security MessageDigest))

(defn sha256 [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (if (bytes? value) value (.getBytes ^String value "UTF-8")))]
    (apply str (map #(format "%02x" (bit-and (int %) 255)) digest))))

(defn diagnostic-context [error]
  (let [{:keys [form span]} (ex-data error)
        rendered (when (some? form) (pr-str form))]
    {:operation (when (and (seq? form) (symbol? (first form)))
                  (str (first form)))
     :form (when rendered
             (subs rendered 0 (min 512 (count rendered))))
     :form_truncated (boolean (and rendered (> (count rendered) 512)))
     :span span}))

(defn compile-one [source target]
  (try
    (let [profile (case target "web" :js-kotoba-v1 "wasm" :wasm32-browser-kotoba-v1)
          artifact (compiler/compile-source source profile)
          payload (if (= target "web") (:source artifact) (:bytes artifact))]
      {:status "accepted"
       :exit_status 0
       :code "ok"
       :phase nil
       :message "compiled"
       :artifact_sha256 (sha256 payload)
       :provenance_manifest false})
    (catch clojure.lang.ExceptionInfo error
      (merge
       {:status "rejected"
        :exit_status 1
        :code "compile-rejected"
        :phase (some-> (ex-data error) :phase name)
        :message (ex-message error)
        :artifact_sha256 nil
        :provenance_manifest false}
       (diagnostic-context error)))
    (catch Throwable error
      {:status "rejected"
       :exit_status 1
       :code "unexpected-compiler-error"
       :phase nil
       :message (or (ex-message error) (.getName (class error)))
       :artifact_sha256 nil
       :provenance_manifest false})))

(let [[input-path output-path] *command-line-args*
      tasks (json/read-str (slurp input-path) :key-fn keyword)
      results
      (mapv (fn [{:keys [source transformed target] :as task}]
              (let [direct (compile-one source target)
                    remediation (when (and (= "rejected" (:status direct)) transformed)
                                  (compile-one transformed target))]
                (-> task
                    (dissoc :source :transformed)
                    (assoc :direct direct :explicit_export_remediation remediation))))
            tasks)]
  (spit output-path (json/write-str results)))

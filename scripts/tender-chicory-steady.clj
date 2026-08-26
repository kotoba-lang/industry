;; Steady-state Chicory tender benchmark (same JVM, reused open-session).
;; Run from kototama checkout: clojure -M /path/to/tender-chicory-steady.clj <wasm> <warmup> <calls>
(require '[clojure.java.io :as io]
         '[kototama.tender :as tender]
         '[kototama.contract :as contract])

(defn -main [& [wasm-path warmup-str calls-str]]
  (when (or (nil? wasm-path) (nil? warmup-str) (nil? calls-str))
    (binding [*out* *err*]
      (println "usage: tender-chicory-steady.clj <wasm> <warmup> <calls>"))
    (System/exit 2))
  (let [wasm (with-open [in (io/input-stream wasm-path)] (.readAllBytes in))
        warmup (Long/parseLong warmup-str)
        calls (Long/parseLong calls-str)
        caps (contract/host-caps {:grants [] :limits {}})
        session (tender/open-session wasm [] caps)
        _ (dotimes [_ warmup] (tender/session-call-main session))
        t0 (System/nanoTime)
        result (reduce (fn [_ _] (tender/session-call-main session)) 0 (range calls))
        t1 (System/nanoTime)]
    (println
     (str "{\"format\":\"kotoba.tender-sample/v1\""
          ",\"host\":\"chicory-jvm\""
          ",\"calls\":" calls
          ",\"warmupCalls\":" warmup
          ",\"elapsedNanoseconds\":" (- t1 t0)
          ",\"result\":" result "}"))
    (System/exit 0)))

(apply -main *command-line-args*)

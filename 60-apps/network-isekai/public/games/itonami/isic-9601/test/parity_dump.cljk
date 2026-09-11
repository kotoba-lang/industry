(ns parity-dump
  "Dumps every float the JVM engine produces for the street, so the browser build can be
  compared against it number for number.

  This exists because the preview page now runs `kami.webgpu.ir` and
  `kami.webgpu.submission` in the browser, compiled by squint rather than by ClojureScript.
  Squint is a different compiler with a different core library: it is genuinely a second
  *execution* of the engine, and the only thing that keeps it from becoming a second
  *engine* is a check that both executions produce the same numbers.

  The check is worth more than it looks. Squint compiles an unresolved symbol to a bare
  JavaScript identifier — no warning, no error — so a core function it happens not to
  implement becomes a `ReferenceError` at runtime, or, if it sits on a branch the smoke test
  does not take, a wrong number that nobody sees. `pos-int?` and `double` were both missing
  and both are shimmed in `preview/squint_shim.mjs`; this dump is what proves the shims are
  right rather than merely quiet.

  Run: clojure -M:parity-dump > /tmp/parity-jvm.json"
  (:require [clojure.data.json :as json]
            [itonami.isic-9601.world :as world]
            [itonami.isic-9601.world3d :as w3]
            [kami.webgpu.submission :as sub]))

(defn dump [cleared aspect w h]
  (let [ir (w3/render-ir (assoc (world/init) :cleared cleared) aspect)]
    {:cleared cleared
     :n (count (:instances ir))
     :inst (vec (sub/pack-instances (:instances ir)))
     :g (vec (sub/pack-globals ir w h))}))

(defn -main [& _]
  (println (json/write-str
            {:frames (mapv (fn [c] (dump c (/ 1280.0 720.0) 1280 720)) [0 3 8])})))

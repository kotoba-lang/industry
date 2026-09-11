(ns parity-probe
  "Packs the street with the squint-compiled engine and prints the floats as JSON.

  Compiled by squint and bundled by esbuild exactly as `preview/ui.cljs` is, so what this
  prints is what the browser page computes — not an approximation of it. `test/parity.cljs`
  diffs it against `clojure -M:parity-dump`."
  (:require [itonami.isic-9601.world :as world]
            [itonami.isic-9601.world3d :as w3]
            [kami.webgpu.submission :as sub]))

(defn- frame [cleared aspect w h]
  (let [ir (w3/render-ir (assoc (world/init) :cleared cleared) aspect)]
    #js {:cleared cleared
         :n (count (:instances ir))
         :inst (into-array (sub/pack-instances (:instances ir)))
         :g (into-array (sub/pack-globals ir w h))}))

(js/console.log
 (js/JSON.stringify
  #js {:frames (into-array (map (fn [c] (frame c (/ 1280.0 720.0) 1280 720)) [0 3 8]))}))

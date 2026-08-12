(ns render
  "Dispatcher for ISIC 9601 visual capture (ADR-2608108000).

  Usage:
    nbb bin/render.cljs [--view street|board] [--state FILE] [--out FILE] …

  * `--view street` (default) → `bin/render_street.cljs` — WebGL 2.0 / WebGPU
    street PNG. Needs `orgs/kotoba-lang/webgpu` (+ render) checked out.
    Street argv includes camera flags (#1751): `--eye/--target/--orbit/--zoom/--fov`
    and `--dry` (parse + print IR, no Chromium). Underground / inside-fit eyes exit 2.
  * `--view board` → `bin/render_board.cljs` — inject envelope via
    `window.__setState`, screenshot the DOM shop board. No engine required.

  The split exists so board capture does not load kami/engine namespaces that
  fail when the west checkout is absent. All argv is forwarded to the chosen
  path — including camera refusal / `--dry` on street."
  (:require ["node:child_process" :as cp]
            ["node:path" :as path]
            ["node:fs" :as fs]
            [clojure.string :as str]))

(def argv (vec *command-line-args*))

(defn opt [k default]
  (let [i (.indexOf (into-array argv) (str "--" k))]
    (if (neg? i) default (nth argv (inc i) default))))

(def here (path/resolve (path/dirname *file*) ".."))
(def view (opt "view" "street"))

(defn- engine-classpath []
  (let [root (path/resolve here "../../../../../../orgs/kotoba-lang")
        webgpu (path/join root "webgpu/src")
        render (path/join root "render/src")]
    (str "src:" webgpu ":" render)))

(defn- run! [script classpath]
  (let [nbb (path/join here "node_modules/.bin/nbb")
        bin (if (fs/existsSync nbb) nbb "nbb")
        args (clj->js (into [bin "--classpath" classpath script] argv))
        r (cp/spawnSync (aget args 0) (.slice args 1)
                        #js {:cwd here :stdio "inherit"})
        st (.-status r)]
    ;; status 0 is success — do not `(or st 1)`, which treats 0 as false.
    (js/process.exit (if (nil? st) 1 st))))

(case view
  "board"  (do (println "  render --view board  (DOM shop board, no WebGL engine)")
               (run! "bin/render_board.cljs" "src"))
  "street" (do (println "  render --view street (WebGL/WebGPU, needs engine checkout)")
               (run! "bin/render_street.cljs" (engine-classpath)))
  (do (println (str "unknown --view " (pr-str view) " (want street|board)"))
      (js/process.exit 2)))

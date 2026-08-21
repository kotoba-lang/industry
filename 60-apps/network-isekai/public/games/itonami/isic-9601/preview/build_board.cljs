(ns build-board
  "Builds `preview/board.html` — shop board only, no WebGL engine.

  Run from the package root:
    npx nbb preview/build_board.cljs

  Used by `bin/render.cljs --view board`. Deliberately does not touch
  `street` / `gl` / `kami.webgpu`, so this works when
  `orgs/kotoba-lang/webgpu` is not checked out (the street render path
  still needs the engine)."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]))

(def here (path/resolve (path/dirname *file*) ".."))
(def out (path/join here "preview/board.html"))

(defn- sh [cmd]
  (println "  $" cmd)
  (cp/execSync cmd #js {:cwd here :stdio "inherit"}))

(defn- ensure-engine-stubs!
  "squint.edn lists engine src paths; scandir fails if they are missing.
  Empty dirs are enough for the board compile (we never import those pkgs)."
  []
  (doseq [p ["../../../../../../orgs/kotoba-lang/webgpu/src"
             "../../../../../../orgs/kotoba-lang/render/src"]]
    (fs/mkdirSync (path/resolve here p) #js {:recursive true})))

(println "[board 1/3] squint compile (board entry + deps only)")
(ensure-engine-stubs!)
(sh (str "npx --yes squint-cljs@0.8.147 compile"
         " preview/board_entry.cljs"
         " preview/shop_view.cljs"
         " preview/set_state.cljs"
         " src/itonami/isic_9601/logic.cljc"
         " src/itonami/isic_9601/world.cljc"
         " src/itonami/isic_9601/district.cljc"))

(println "[board 2/3] esbuild bundle")
(sh (str "npx --yes esbuild@0.25.0 .build/board_entry.mjs --bundle --format=iife "
         "--minify --target=es2020 --inject:preview/squint_shim.mjs "
         "--outfile=.build/board_bundle.js"))

(println "[board 3/3] assemble preview/board.html")
(def style (fs/readFileSync (path/join here "preview/style.css") "utf8"))
(def script (fs/readFileSync (path/join here ".build/board_bundle.js") "utf8"))

(def markup
  (str
   "<!doctype html>\n<meta charset=utf-8>\n"
   "<title>クリーニング営み — board capture</title>\n"
   "<style>" style "</style>\n"
   "<main class='wrap'>\n"
   "  <header class='top'>\n"
   "    <h1 class='hig-title'>クリーニング営み</h1>\n"
   "    <p class='hig-subhead'>board capture — <code>window.__setState</code> / "
   "<code>render --view board</code></p>\n"
   "  </header>\n"
   "  <div id='shop'></div>\n"
   "</main>\n"
   "<script>" script "</script>\n"))

(fs/writeFileSync out markup)
(println "wrote" out (str "(" (js/Math.round (/ (count markup) 1024)) " KB)"))

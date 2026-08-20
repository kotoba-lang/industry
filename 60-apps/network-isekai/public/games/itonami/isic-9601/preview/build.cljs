(ns build
  "Builds `preview/index.html` -- a single self-contained page that plays the
  shop in a browser.

  Run from the repo root:
    npx nbb 60-apps/network-isekai/public/games/itonami/isic-9601/preview/build.cljs

  Pipeline: squint compiles the game and the engine to ESM, esbuild bundles
  them with squint's core into one dependency-free script, and that script is
  inlined into the page together with the stylesheet and the engine's GLSL.
  Nothing is fetched at runtime, so the page works offline and inside a strict
  CSP.

  Two details are load-bearing:

  `--inject:preview/squint_shim.mjs` supplies the two core functions squint does
  not implement. Without it the page dies at first paint with a `ReferenceError`
  naming a Clojure function, from inside minified engine code.

  The GLSL is read from the engine's own `fixtures/glsl/` and inlined as
  `window.__GLSL`. It is generated from the one EDN shader the WGSL also comes
  from, so the page and the WebGPU path are shading the same scene description
  — copying the shader source here instead would be a second renderer wearing
  the first one's numbers.

  The page is deliberately fragment-shaped (no <!doctype>/<html>/<body>): it
  renders standalone in a browser and can also be published verbatim as an
  Artifact, which supplies its own document skeleton."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]))

(def here
  "This package's directory, resolved from the script's own path rather than the working
  directory. It used to be `(path/join (js/process.cwd) \"<the whole path>\")`, which meant
  the build only worked when run from the repo root and failed from anywhere else with
  `spawnSync /bin/sh ENOENT` — the cwd handed to the subprocess did not exist, and the
  error names the shell rather than the directory. `npm run build` from this package hit
  exactly that."
  (path/resolve (path/dirname *file*) ".."))
(def out (path/join here "preview/index.html"))

(defn sh [cmd]
  (println "  $" cmd)
  (cp/execSync cmd #js {:cwd here :stdio "inherit"}))

;; --------------------------------------------------------------------------

(println "[1/3] squint compile")
(sh "npx --yes squint-cljs@0.8.147 compile")

(println "[2/3] esbuild bundle")
(sh (str "npx --yes esbuild@0.25.0 .build/ui.mjs --bundle --format=iife "
         "--minify --target=es2020 --inject:preview/squint_shim.mjs "
         "--outfile=.build/bundle.js"))

(println "[3/3] assemble")

(def engine-root
  (path/resolve here "../../../../../../orgs/kotoba-lang/webgpu"))

(defn- glsl [f]
  (let [p (path/join engine-root "fixtures" "glsl" f)]
    (when-not (fs/existsSync p)
      (println (str "  missing " p))
      (println "  west update --fetch smart webgpu")
      (js/process.exit 3))
    (fs/readFileSync p "utf8")))

(def style (fs/readFileSync (path/join here "preview/style.css") "utf8"))
(def script (fs/readFileSync (path/join here "preview/../.build/bundle.js") "utf8"))

(def markup
  (str
   "<title>クリーニング営み — cloud-itonami ISIC 9601</title>\n"
   "<style>" style "</style>\n"
   "<main class='wrap'>\n"
   "  <header class='top'>\n"
   "    <h1 class='hig-title'>クリーニング営み</h1>\n"
   "    <p class='hig-subhead'>cloud-itonami の governed actor 8 つを、そのまま放置系タイクーンにしたもの。</p>\n"
   "    <p class='lede'>ルールは考案したものではなく、8 つの repo "
   "(<code>cloud-itonami-isic-{9601,4520,9609,8121,8129,3700,3811,3900}</code>) の "
   "<code>phase.cljc</code> と <code>governor.cljc</code> からの転写です。"
   "<b>どの店にも、どの段階でも自動化されない工程が必ずあります</b> — 街全体で 9 件。"
   "放置ゲーは全部を自動化するゲームなので、その 1 件が自動化されないことが、そのまま遊びになっています。</p>\n"
   "    <p class='fine'>街をタップして店に入ります。3D は <code>kami.webgpu</code> の "
   "canonical render-IR を WebGL 2.0 で描いたもので、CLI の PNG と同じ数値です。</p>\n"
   "  </header>\n"
   "  <div id='street-wrap'><canvas id='street-canvas'></canvas></div>\n"
   "  <div id='shop'></div>\n"
   "  <footer class='foot'>\n"
   "    <p>遊び方: 工程が満ちたら <b>承認</b>。2 番目の工程で提示された根拠と提案が食い違っていたら <b>差し戻す</b> — "
   "そのまま通すと後段で governor が HOLD し、顧客の信頼を失います。"
   "資格が切れたら全工程が止まるので更新を。40 件で監査クローズ、隣の営みが開きます。</p>\n"
   "    <p class='fine'>プレビュー実装。乱数は <code>:seed</code> から決定的に回るので、同じ種は同じ試合になります。</p>\n"
   "  </footer>\n"
   "</main>\n"
   "<script>window.__GLSL=" (js/JSON.stringify #js {:vert (glsl "lit.vert")
                                                     :frag (glsl "lit.frag")}) ";</script>\n"
   "<script>" script "</script>\n"))

(fs/writeFileSync out markup)
(println "wrote" out (str "(" (js/Math.round (/ (count markup) 1024)) " KB)"))

(ns build
  "Builds `preview/index.html` -- a single self-contained page that plays the
  shop in a browser.

  Run from the repo root:
    npx nbb 60-apps/network-isekai/public/games/itonami/isic-9601/preview/build.cljs

  Pipeline: squint compiles `logic.cljc` and `ui.cljs` to ESM, esbuild bundles
  them with squint's core into one dependency-free script, and that script is
  inlined into the page together with the stylesheet. Nothing is fetched at
  runtime, so the page works offline and inside a strict CSP.

  The page is deliberately fragment-shaped (no <!doctype>/<html>/<body>): it
  renders standalone in a browser and can also be published verbatim as an
  Artifact, which supplies its own document skeleton."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:child_process" :as cp]))

(def here (path/join (js/process.cwd) "60-apps/network-isekai/public/games/itonami/isic-9601"))
(def out (path/join here "preview/index.html"))

(defn sh [cmd]
  (println "  $" cmd)
  (cp/execSync cmd #js {:cwd here :stdio "inherit"}))

;; --------------------------------------------------------------------------

(println "[1/3] squint compile")
(sh "npx --yes squint-cljs@0.8.147 compile")

(println "[2/3] esbuild bundle")
(sh (str "npx --yes esbuild@0.25.0 .build/ui.mjs --bundle --format=iife "
         "--minify --target=es2020 --outfile=.build/bundle.js"))

(println "[3/3] assemble")

(def style (fs/readFileSync (path/join here "preview/style.css") "utf8"))
(def script (fs/readFileSync (path/join here "preview/../.build/bundle.js") "utf8"))

(def markup
  (str
   "<title>クリーニング営み — cloud-itonami ISIC 9601</title>\n"
   "<style>" style "</style>\n"
   "<main class='wrap'>\n"
   "  <header class='top'>\n"
   "    <h1 class='hig-title'>クリーニング営み</h1>\n"
   "    <p class='hig-subhead'>ISIC Rev.5 9601 — 洗濯・ドライクリーニング業の governed actor を、そのまま放置系タイクーンにしたもの。</p>\n"
   "    <p class='lede'>ルールは考案したものではなく <code>cloud-itonami/cloud-itonami-isic-9601</code> からの転写です。"
   "5つの工程は <code>laundry.phase/write-ops</code>、段階の梯子は <code>laundry.phase/phases</code>、"
   "6つの HOLD は <code>laundry.governor</code> の HARD チェック。"
   "<b>洗浄</b> と <b>返却</b> だけは、どれだけ店が大きくなっても自動化されません — "
   "phase 3 の <code>:auto</code> 集合が <code>#{:garment/intake}</code> 1つきりだからです。</p>\n"
   "  </header>\n"
   "  <div id='shop'></div>\n"
   "  <footer class='foot'>\n"
   "    <p>遊び方: 工程が満ちたら <b>承認</b>。<b>取扱方法</b> で洗濯表示と提案処理が食い違っていたら <b>差し戻す</b> — "
   "そのまま通すと <b>洗浄</b> で governor が HOLD し、顧客の信頼を失います。"
   "資格が切れたら全工程が止まるので更新を。40点returnで監査クローズ。</p>\n"
   "    <p class='fine'>プレビュー実装。乱数は <code>:seed</code> から決定的に回るので、同じ種は同じ試合になります。</p>\n"
   "  </footer>\n"
   "</main>\n"
   "<script>" script "</script>\n"))

(fs/writeFileSync out markup)
(println "wrote" out (str "(" (js/Math.round (/ (count markup) 1024)) " KB)"))

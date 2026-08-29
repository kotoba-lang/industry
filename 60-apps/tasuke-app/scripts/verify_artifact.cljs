(ns verify-artifact
  "Checks `out/artifact.html` — the single-file packaging — in a real browser.

  The packaging is a different risk from the app: the skeleton comes from the host,
  the bundle is inlined rather than fetched, and the CSS is concatenated by hand
  here rather than by `page/->page`. Any of those can break while `public/index.html`
  stays perfect, so the packaging is measured separately rather than assumed."
  (:require ["playwright$default" :as pw]
            ["http" :as http]
            ["fs" :as fs]
            [clojure.string :as str]
            [promesa.core :as p]))

(def port 8932)

(defn serve! []
  (p/create
   (fn [resolve _]
     (let [srv (.createServer
                http
                (fn [_req res]
                  (.setHeader res "content-type" "text/html; charset=utf-8")
                  (.end res (fs/readFileSync "out/artifact.html"))))]
       (.listen srv port #(resolve srv))))))

(defn check! [ok? msg]
  (println (if ok? "  ok  " "  FAIL") msg)
  (when-not ok? (js/process.exit 1)))

(defn -main [& _]
  (p/let [srv (serve!)
          browser (.launch (.-chromium pw)
                           #js {:args #js ["--no-sandbox"]
                                :executablePath "/opt/pw-browsers/chromium-1194/chrome-linux/chrome"})
          page (.newPage browser)
          errors (atom [])
          _ (.on page "pageerror" #(swap! errors conj (str %)))
          _ (.goto page (str "http://127.0.0.1:" port "/"))
          _ (.waitForSelector page "#app .app-verdict")
          _ (.fill page "#narrative" "Xのアカウントを乗っ取られたかもしれません")
          _ (.waitForFunction page
                              "document.querySelector('.app-verdict__kind').textContent.includes('アカウント乗っ取り')")
          bg (.evaluate page "getComputedStyle(document.body).backgroundColor")
          styled (.evaluate page "getComputedStyle(document.querySelector('.dads-button')).borderRadius")
          title (.title page)
          _ (check! (empty? @errors) (str "no page errors " (pr-str @errors)))
          _ (check! (= "助 乗っ取り初動キット" title) (str "title is the page's name (" title ")"))
          _ (check! (not= "rgba(0, 0, 0, 0)" bg)
                    (str "body paints its own ground, not the host's (" bg ")"))
          _ (check! (not= "0px" styled) (str "DADS CSS survived the concatenation (" styled ")"))
          _ (.screenshot page #js {:path "out/artifact.png" :fullPage false})
          _ (.close browser)
          _ (.close srv)]
    (println "\nOK — artifact packaging verified")
    (js/process.exit 0)))

(-main)

#!/usr/bin/env nbb
(ns verify-kotoba-pages
  "生成した Pages アプリを **実ブラウザで動かして** 検証する。

  なぜ必要か: design-quality の決定論 audit が 100.00 でも、配線が外れていれば
  ページは静かに何もしない。ADR-2607301300 は幾何テスト全通過の状態で印影が
  判読不能だった実例(と、head に置いた script が getElementById で全部 nil を
  返して静かに終わる罠)を記録している。**『測っていない品質は演出』。**

  使い方:
    nbb scripts/verify-kotoba-pages.cljs [repo ...]   # 既定は registry の全 repo"
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:process" :as process]
            ["playwright$default" :as pw]
            [clojure.string :as str]
            [promesa.core :as p]))

(def root (path/resolve (path/dirname (or js/__filename "scripts/x")) ".."))
(defn- rpath [& xs] (apply path/join root xs))

(def registry
  (read-string (fs/readFileSync (rpath "manifest" "kotoba-pages.edn") "utf8")))

(defn- entries [only]
  (let [all (get-in registry [:family/edn-editor :repos])]
    (if (seq only) (filter #((set only) (:repo %)) all) all)))

(def failures (atom []))
(defn- check! [repo label ok? detail]
  (when-not ok? (swap! failures conj {:repo repo :check label :detail detail}))
  (println (str "    " (if ok? "ok  " "FAIL") "  " label
                (when-not ok? (str " — " detail)))))

(defn verify-page [browser {:keys [repo storage-key create-label download]}]
  (p/let [ctx (.newContext browser #js {:viewport #js {:width 1280 :height 900}
                                        :acceptDownloads true})
          page (.newPage ctx)
          errors (atom [])
          _ (.on page "pageerror" (fn [e] (swap! errors conj (str e))))
          _ (.on page "console" (fn [m] (when (= "error" (.type m)) (swap! errors conj (.text m)))))
          _ (.goto page (str "file://" (rpath "orgs" "kotoba-lang" repo "docs" "index.html")))
          _ (println (str "  " repo))

          ;; 1. 配線: 5 つの要素が実在するか(head 置き script の罠はここで出る)
          n (.evaluate page "['create','render','download','edn','preview'].filter(i=>document.getElementById(i)).length")
          _ (check! repo "5 要素が DOM にある" (= 5 n) (str "found=" n))

          ;; 2. textarea が sample で初期化されているか(script が走った証拠)
          v0 (.inputValue page "#edn")
          _ (check! repo "textarea が sample で初期化" (str/starts-with? (str v0) "{:")
                    (str "value=" (subs (str v0) 0 (min 40 (count (str v0))))))

          ;; 3. Preview ボタンが preview に反映するか
          _ (.fill page "#edn" "{:probe 1}")
          _ (.click page "#render")
          t (.textContent page "#preview")
          _ (check! repo "Preview が textarea を反映" (= "{:probe 1}" (str t)) (str "preview=" t))

          ;; 4. localStorage に保存されているか
          s (.evaluate page (str "localStorage.getItem(" (pr-str storage-key) ")"))
          _ (check! repo "localStorage に保存" (= "{:probe 1}" (str s)) (str "stored=" s))

          ;; 5. Create ボタンが sample へ戻すか
          _ (.click page "#create")
          v1 (.inputValue page "#edn")
          _ (check! repo (str "\"" create-label "\" が sample へ戻す") (str/starts-with? (str v1) "{:")
                    (str "value=" (subs (str v1) 0 (min 30 (count (str v1))))))

          ;; 6. Download が実際に blob を出すか(ボタンが飾りでないことの確認)
          dl (p/all [(.waitForEvent page "download" #js {:timeout 5000})
                     (.click page "#download")])
          f (first dl)
          _ (check! repo "Download が .edn を出す" (= download (.suggestedFilename f))
                    (str "filename=" (.suggestedFilename f)))

          ;; 7. 横スクロールが出ていないか(実測。DADS の table/overflow 由来の実害)
          _ (.setViewportSize page #js {:width 390 :height 844})
          o (.evaluate page "document.body.scrollWidth - document.body.clientWidth")
          _ (check! repo "390px で横スクロール無し" (<= o 1) (str "overflow=" o "px"))

          ;; 8. DADS の CSS が実際に効いているか(class があっても CSS 未適用は静かに壊れる)
          _ (.setViewportSize page #js {:width 1280 :height 900})
          bg (.evaluate page "getComputedStyle(document.querySelector('.dads-button')).backgroundColor")
          _ (check! repo "dads-button に CSS が当たっている"
                    (and bg (not= "rgba(0, 0, 0, 0)" (str bg)) (not= "" (str bg))) (str "bg=" bg))

          ;; 9. mono フォントが preview に効いているか
          ff (.evaluate page "getComputedStyle(document.querySelector('#preview')).fontFamily")
          _ (check! repo "preview が mono" (str/includes? (str/lower-case (str ff)) "mono") (str "font=" ff))

          ;; 10. JS エラーゼロ
          _ (check! repo "console/page エラー無し" (empty? @errors) (str/join " | " @errors))

          ;; 11. 2 面が同じ幅で grid セルを埋めているか。
          ;; これは元々 **目視でしか出なかった** 欠陥(上流 .dads-textarea が inline
          ;; span で、textarea が既定 cols 幅 ~190px のまま隣の preview ~520px と
          ;; 並ばなかった)。audit 100.00 と機能 check 10 件を全部通っていたので、
          ;; 測れる形にして退行を止める。
          w (.evaluate page "(()=>{const a=document.querySelector('#edn').getBoundingClientRect().width,b=document.querySelector('#preview').getBoundingClientRect().width;return {a:Math.round(a),b:Math.round(b),d:Math.abs(a-b)}})()")
          _ (check! repo "textarea と preview が同幅で列を埋める"
                    (<= (.-d w) 2) (str "textarea=" (.-a w) "px preview=" (.-b w) "px 差=" (.-d w) "px"))

          ;; 目視面(数値では捕まらない崩れのため。ADR-2607301300 の :square-1 の教訓)
          _ (.screenshot page #js {:path (rpath "90-docs" "design-quality" "samples"
                                                (str "kotoba-pages-" repo ".png"))
                                   :fullPage true})
          _ (.close ctx)]
    nil))

(defn -main [& args]
  (p/let [es (entries args)
          _ (println (str "実ブラウザ検証 — " (count es) " page(s)\n"))
          browser (.launch pw/chromium)
          ;; browser は必ず閉じる(残留 chrome が他セッションの負荷になる — repo rule)
          _ (-> (p/run! #(verify-page browser %) es)
                (p/finally (fn [_ _] (.close browser))))
          _ (println)]
    (if (seq @failures)
      (do (println (str (count @failures) " 件失敗:"))
          (doseq [f @failures] (println "  " (:repo f) "/" (:check f) "—" (:detail f)))
          (process/exit 1))
      (println (str "全 " (count es) " page × 10 check 通過")))))

(apply -main (drop 3 (.-argv process)))

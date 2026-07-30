#!/usr/bin/env nbb
(ns build-kotoba-pages
  "kotoba-lang の GitHub Pages アプリの index.html を jp-go-dds(デジタル庁
  デザインシステム)で生成する。ADR-2607301400。

  なぜ superproject に置くか: 8 repo が **バイト同一** の 47 行テンプレートを
  各々持っていた(実測)。同じページを 8 箇所で手編集する設計は、2 箇所目の
  時点でもう共通ではなくなる —— ADR-2607301300 決定 8 が cloud-itonami の
  ヘッダーで同じ結論に至っている(『共通クロームはプラットフォームが被せる。
  サイト側に書かせない』)。ここが kotoba-lang 側のその『プラットフォーム』。

  各 repo の docs/index.html は **生成物**(手編集禁止)。Pages は main:/docs を
  直配信するので、生成して commit すれば CI 変更は要らない。

  使い方:
    nbb scripts/build-kotoba-pages.cljs              # 全 repo を生成
    nbb scripts/build-kotoba-pages.cljs --repo docs  # 1 repo だけ
    nbb scripts/build-kotoba-pages.cljs --check      # 生成物が canonical か検査(CI)

  実行には west checkout の jp-go-dds / html / css が要る:
    nbb --classpath \"orgs/kotoba-lang/jp-go-digital-design-system/src:orgs/kotoba-lang/html/src:orgs/kotoba-lang/css/src\" \\
        scripts/build-kotoba-pages.cljs"
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:process" :as process]
            [clojure.string :as str]
            [css.core :as css]
            [html.core :as html]
            [jp-go-dds.core :as dds]
            [jp-go-dds.page :as page]
            [jp-go-dds.tokens :as tokens]))

(def root
  "superproject ルート(この script の 1 つ上)。cwd に依存させない。"
  (path/resolve (path/dirname (or js/__filename "scripts/x")) ".."))

(defn- rpath [& xs] (apply path/join root xs))

(def registry-path (rpath "manifest" "kotoba-pages.edn"))
(def dds-css-path
  (rpath "orgs" "kotoba-lang" "jp-go-digital-design-system"
         "resources" "jp_go_dds" "dds.css"))

;; ---------------------------------------------------------------------------
;; app CSS —— 生 CSS 記法も raw hex も書かない([selector decls] を css.core へ)
;; ---------------------------------------------------------------------------

(def editor-rules
  "EDN エディタ 2 面ぶんの app CSS。DADS token のみ参照する。

  `.dds-ext-*` は jp-go-dds の所有物なので名乗らない。上流 DADS にも対応物が
  無いので `kl-*`(kotoba-lang)を prefix にする —— jp-go-dds 自身が上流と
  `dds-ext-*` で区別しているのと同じ規律(ADR-2607301300 決定 8 と同型)。"
  [[".kl-editor" {:display "grid"
                  :grid-template-columns "minmax(0,1fr) minmax(0,1fr)"
                  :gap "1.5rem"}]
   [".kl-editor__label" {:font-size ".8125rem"
                         :color "var(--color-neutral-solid-gray-600)"
                         :margin "0 0 .5rem"}]
   ;; 上流 .dads-textarea は **inline な <span>** ラッパで、内側の textarea にも
   ;; width 指定が無い。grid セルに入れると textarea が textarea 既定の cols 幅
   ;; (実測 ~190px)のまま残り、隣の preview(セル全幅 ~520px)と並ばない。
   ;; **audit 100.00 でも 10 個の機能 check でも捕まらず、目視でしか出ない**
   ;; (ADR-2607301300 の :square-1 と同型)。上流 class を触るが restyle では
   ;; なく grid セルへの充填なので、自分の .kl-editor 配下に限定する。
   [".kl-editor .dads-textarea" {:display "block" :width "100%"}]
   [".kl-editor .dads-textarea__textarea" {:width "100%" :box-sizing "border-box"}]
   ;; textarea / preview は同じ寸法・同じ字面にする(左右で行がずれると差分が読めない)
   [".kl-editor .dads-textarea__textarea,.kl-preview"
    {:min-height "24rem"
     :box-sizing "border-box"
     :font-family "var(--font-family-mono)"
     :font-size ".8125rem"
     :line-height 1.6}]
   [".kl-preview" {:margin 0
                   :padding "1rem"
                   :overflow "auto"
                   :white-space "pre-wrap"
                   :border "1px solid var(--color-neutral-solid-gray-300)"
                   :border-radius 8
                   :background "var(--color-neutral-white)"
                   :color "var(--color-neutral-solid-gray-800)"}]])

(def editor-media
  {"(max-width:48rem)" [[".kl-editor" {:grid-template-columns "1fr"}]]})

(def editor-css (css/css {:rules editor-rules :media editor-media}))

;; ---------------------------------------------------------------------------
;; view
;; ---------------------------------------------------------------------------

(defn- editor-script
  "移行前と **同じ挙動** の配線。sample / storage key / download 名は registry
  から来る(旧ページから機械抽出したもの)。

  script は body 末尾に置く —— head に置くと body 生成前に走って
  getElementById が nil を返し、**何も起きないまま静かに終わる**
  (ADR-2607301300『発見した実装上の罠』1)。"
  [{:keys [storage-key sample-js download]}]
  (str "const key='" storage-key "';\n"
       "const sample=" sample-js ";\n"
       "const edn=document.getElementById('edn'), preview=document.getElementById('preview');\n"
       "edn.value=localStorage.getItem(key)||sample;\n"
       "function save(){localStorage.setItem(key,edn.value)}\n"
       "function render(){save();preview.textContent=edn.value;return edn.value}\n"
       "document.getElementById('render').onclick=render;\n"
       "document.getElementById('create').onclick=()=>{edn.value=sample;render()};\n"
       "document.getElementById('download').onclick=()=>{"
       "const blob=new Blob([render()],{type:'application/edn'});"
       "const a=document.createElement('a');a.href=URL.createObjectURL(blob);"
       "a.download='" download "';a.click();"
       "setTimeout(()=>URL.revokeObjectURL(a.href),1000)};\n"
       "edn.addEventListener('input',save);render();\n"))

(defn- panel
  [label control]
  [:section
   [:p {:class "kl-editor__label"} label]
   control])

(defn edn-editor-page
  "EDN エディタ 1 ページぶんの完全な HTML 文書。"
  [{:keys [repo title lead create-label] :as entry} dds-css]
  (page/->page
   {:title title
    ;; lead は <code> を含む生 hiccup ではなく description には平文が要る
    :description (-> lead (str/replace #"<[^>]*>" "") (str/replace #"\s+" " "))
    :css dds-css
    ;; skin-css(--hig-* 橋渡し + a11y 補正)を app CSS より **前** に置く
    :app-css (str tokens/skin-css "\n" editor-css)}
   (dds/container
    [:main
     (dds/section
      {}
      (dds/stack
       (dds/heading 1 title {:size "45"})
       ;; lead は旧ページの文言そのまま(<code> を含むので raw で流す)
       [:p {:class "dds-ext-lead"} (html/raw lead)]
       (dds/row
        (dds/button create-label {:type :solid-fill :id "create"})
        (dds/button "Preview EDN" {:type :outline :id "render"})
        (dds/button "Download EDN" {:type :outline :id "download"})
        (dds/button "GitHub" {:type :text
                              :href (str "https://github.com/kotoba-lang/" repo)}))
       [:div {:class "kl-editor"}
        (panel "Editable EDN" (dds/textarea {:id "edn" :rows 16}))
        (panel "Preview" [:div {:id "preview" :class "kl-preview"}])]))])
   ;; body 末尾
   [:script (editor-script entry)]))

;; ---------------------------------------------------------------------------
;; driver
;; ---------------------------------------------------------------------------

(defn- out-path [repo] (rpath "orgs" "kotoba-lang" repo "docs" "index.html"))

(defn- read-registry []
  (read-string (fs/readFileSync registry-path "utf8")))

(defn- entries [registry only]
  (cond->> (get-in registry [:family/edn-editor :repos])
    only (filter #(= only (:repo %)))))

(defn -main [& args]
  (let [args (vec args)
        check? (some #{"--check"} args)
        only (second (drop-while #(not= "--repo" %) args))
        dds-css (fs/readFileSync dds-css-path "utf8")
        registry (read-registry)
        es (entries registry only)]
    (when (empty? es)
      (println "対象 repo が無い" (pr-str {:only only}))
      (process/exit 1))
    (let [results
          (doall
           (for [e es]
             (let [html (edn-editor-page e dds-css)
                   p (out-path (:repo e))
                   existing (when (fs/existsSync p) (fs/readFileSync p "utf8"))]
               (if check?
                 {:repo (:repo e) :ok (= existing html)}
                 (do (fs/mkdirSync (path/dirname p) #js {:recursive true})
                     (fs/writeFileSync p html)
                     {:repo (:repo e) :bytes (count html)})))))]
      (if check?
        (let [bad (remove :ok results)]
          (doseq [r results]
            (println (if (:ok r) "  ok  " " STALE") (:repo r)))
          (when (seq bad)
            (println (str "\n" (count bad) " 件が canonical と不一致 —— "
                          "nbb scripts/build-kotoba-pages.cljs で再生成すること"))
            (process/exit 1))
          (println (str "\n" (count results) " 件すべて canonical")))
        (do (doseq [r results] (println "  wrote" (:repo r) (:bytes r) "bytes"))
            (println (str "\n" (count results) " 件生成した")))))))

(apply -main (drop 3 (.-argv process)))

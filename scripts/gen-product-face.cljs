;; cloud-itonami の product face(共有テンプレート由来の docs/index.html)を
;; デジタル庁デザインシステム(DADS)で再描画する fleet ツール。
;; superproject ADR-2607261600 の follow-up 1。
;;
;; **内容は既存ページから抽出して持ち越す**(blueprint.edn から再導出しない)。
;; 対象 387 repo は badge の書式が repo 種別ごとに違い(ISIC Rev.4/Rev.5 /
;; UNSPSC segment / domain のみ)、blueprint.edn を持たない repo も 4 件ある。
;; 再導出すると欠落や取り違えが起きうるので、**この移行は presentation 層だけ**を
;; 差し替え、text は 1 文字も作り直さない。抽出できなければその repo は skip する
;; (黙って部分的なページを書かない)。
;;
;; **一方向の移行ツールであって再生成器ではない。** 入力は旧テンプレートの markup
;; (class="pitch" / "badge" / "links" 等)で、DADS 化済みのページはそれらを持たない
;; ので extract が nil を返し `unparsed` として **skip される(書き込まない)**。
;; つまり再実行は既移行ページに対して安全な no-op(実測で確認済み)。移行後に
;; 内容を変えたい場合は README/blueprint 側を直して各 repo の docs を作り直す。
;;
;; usage:
;;   nbb --classpath "<html>/src:<css>/src:<jp-go-dds>/src" gen_product_face.cljs \
;;       <orgs/cloud-itonami> <repo-name>...        # 指定 repo だけ
;;       <orgs/cloud-itonami> --all <list-file>     # list-file の 1行1repo
(require '[clojure.string :as str]
         '[css.core :as css]
         '[jp-go-dds.core :as dds]
         '[jp-go-dds.page :as page]
         '["fs" :as fs]
         '["path" :as path])

(def dds-css
  (fs/readFileSync
   (or (some-> js/process.env.JP_GO_DDS_CSS not-empty)
       (throw (js/Error. "JP_GO_DDS_CSS を指定してください")))
   "utf8"))

;; ---------- 抽出 ----------

;; 数値文字参照(&#8867; = ⊣ など)も必ず復号する。放置すると抽出結果に
;; "&#8867;" という文字列が残り、html.core が & を再エスケープして
;; "&amp;#8867;" になり、画面に実体参照がそのまま露出する(実測: isic-750/7500)。
;; &amp; の復号は最後 — 先にやると "&amp;lt;" が "<" に化ける。
(defn- decode-entities [s]
  (-> s
      (str/replace #"&#x([0-9a-fA-F]+);"
                   (fn [[_ h]] (js/String.fromCodePoint (js/parseInt h 16))))
      (str/replace #"&#(\d+);"
                   (fn [[_ d]] (js/String.fromCodePoint (js/parseInt d 10))))
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&nbsp;" " ")
      (str/replace "&amp;" "&")))

(defn- strip-tags [s] (str/replace s #"<[^>]*>" ""))

(defn- text-of [s] (-> s strip-tags decode-entities str/trim))

(defn- find1 [re s] (second (re-find re s)))

(defn- find-all [re s] (mapv second (re-seq re s)))

;; 旧テンプレートの pitch は README を素朴に平文化していて、blockquote の
;; "> " が本文に漏れている(実測: isic-5820 の "> Why an actor layer at all?")。
;; 内容ではなく描画の壊れなので、行頭の引用マーカーだけ落とす。
;; 注意: 矢印 `->` を壊さないこと。引用マーカーは「空白に挟まれた >」なので、
;; `>` の**前に空白があるときだけ**落とす(`\s*` にするとゼロ幅にマッチして
;; `advise -> govern` の `>` まで食う。実測でこの破損を出した)。
(defn- unquote-markers [s]
  (-> s
      (str/replace #"(?m)^\s*>\s?" "")
      (str/replace #"\s>\s+" " ")
      (str/replace #"\s{2,}" " ")
      str/trim))

(defn- parse-links
  "<ul class=\"links\"> の <li><a href>text</a></li> を [{:href :text}] にする。"
  [html]
  (when-let [block (find1 #"(?s)<ul class=\"links\">(.*?)</ul>" html)]
    (mapv (fn [[_ href label]] {:href (decode-entities href) :text (text-of label)})
          (re-seq #"(?s)<a href=\"([^\"]*)\"[^>]*>(.*?)</a>" block))))

(defn- parse-cta [html]
  (when-let [block (find1 #"(?s)<div class=\"cta\">(.*?)</div>" html)]
    (mapv (fn [[_ cls href label]]
            {:kind (if (= "primary" cls) :primary :secondary)
             :href (decode-entities href) :text (text-of label)})
          (re-seq #"(?s)<a class=\"(primary|secondary)\" href=\"([^\"]*)\"[^>]*>(.*?)</a>" block))))

(defn- parse-meta
  "<p class=\"meta\">Domain <code>x</code> · Governor <code>y</code> · ...</p> を
  そのまま [{:label :code}] 列として取り出す(ラベル語も repo 差があるので固定しない)。"
  [html]
  (when-let [block (find1 #"(?s)<p class=\"meta\">(.*?)</p>" html)]
    (let [parts (str/split block #"·")]
      (vec (keep (fn [p]
                   (when-let [code (find1 #"<code>(.*?)</code>" p)]
                     {:label (text-of (str/replace p #"<code>.*?</code>" ""))
                      :code  (text-of code)}))
                 parts)))))

(defn extract
  "既存 product face から内容を抜く。抜けない要素があれば nil(= skip)。"
  [html]
  (let [h1     (some-> (find1 #"(?s)<h1>(.*?)</h1>" html) text-of)
        badge  (some-> (find1 #"(?s)<div class=\"badge\">(.*?)</div>" html) text-of)
        pitch  (when-let [b (find1 #"(?s)<div class=\"pitch\">(.*?)</div>" html)]
                 (mapv (comp unquote-markers text-of) (find-all #"(?s)<p>(.*?)</p>" b)))
        meta   (parse-meta html)
        cta    (parse-cta html)
        links  (parse-links html)
        footer (some-> (find1 #"(?s)<footer>\s*<p>(.*?)</p>" html) text-of)
        title  (some-> (find1 #"(?s)<title>(.*?)</title>" html) text-of)
        desc   (find1 #"<meta name=\"description\" content=\"([^\"]*)\"" html)]
    (when (and h1 badge (seq pitch) (seq links) footer title)
      {:h1 h1 :badge badge :pitch pitch :meta meta :cta cta
       :links links :footer footer :title title
       :description (some-> desc decode-entities)})))

;; ---------- 描画(EDN / hiccup のみ。生 CSS も生 HTML も書かない) ----------

(def app-rules
  ;; header(h1 + badge)と pitch カードの間に余白を入れる — 0 だとバッジが
  ;; カードの枠線に貼り付いて見える。
  [[".pf-header" {:padding-block "3rem 1.25rem"}]
   [".pf-header .dads-heading" {:margin "0 0 1rem"}]
   [".pf-pitch p" {:margin "0 0 1rem" :line-height 1.8
                   :color "var(--color-neutral-solid-gray-800)"}]
   [".pf-pitch p:last-child" {:margin-bottom 0}]
   [".pf-meta" {:color "var(--color-neutral-solid-gray-600)" :font-size ".875rem"
                :line-height 1.9 :margin "1.5rem 0 0"}]
   [".pf-ctarow" {:display "flex" :gap ".75rem" :flex-wrap "wrap"
                  :margin-top "2rem"}]
   [".pf-links" {:line-height 2 :padding-left "1.25rem" :margin "1.5rem 0 0"}]
   [".pf-footer" {:border-top "1px solid var(--color-neutral-solid-gray-200)"
                  :margin-top "3rem" :padding-block "1.5rem 3rem"
                  :color "var(--color-neutral-solid-gray-600)"
                  :font-size ".875rem" :line-height 1.8}]
   [".pf-footer p" {:margin 0}]
   ["code" {:font-family "var(--font-family-mono)"
            :background "var(--color-neutral-solid-gray-50)"
            :border "1px solid var(--color-neutral-solid-gray-200)"
            :border-radius 4 :padding "1px 5px" :font-size ".9em"}]])

(def app-css (css/css {:rules app-rules}))

(defn- meta-line [meta]
  (when (seq meta)
    (into [:p {:class "pf-meta"}]
          (interpose " · "
                     (map (fn [{:keys [label code]}]
                            [:<> (when (seq label) (str label " ")) [:code code]])
                          meta)))))

(defn render [{:keys [h1 badge pitch meta cta links footer title description]}]
  (page/->page
   {:title title :description description :lang "en"
    :css dds-css :app-css app-css}
   (dds/container
    [:header {:class "pf-header"}
     (dds/heading 1 h1)
     (dds/chip-label badge {:color "blue" :style "filled-1"})]
    (dds/card
     (into [:div {:class "pf-pitch"}] (map (fn [p] [:p p]) pitch)))
    (meta-line meta)
    (when (seq cta)
      (into [:div {:class "pf-ctarow"}]
            (map (fn [{:keys [kind href text]}]
                   (dds/button text {:type (if (= :primary kind) :solid-fill :outline)
                                     :size "lg" :href href}))
                 cta)))
    (into [:ul {:class "pf-links"}]
          (map (fn [{:keys [href text]}] [:li [:a {:href href} text]]) links))
    [:footer {:class "pf-footer"} [:p footer]])))

;; ---------- 実行 ----------

(defn process! [root repo]
  (let [f (path/join root repo "docs" "index.html")]
    (if-not (fs/existsSync f)
      {:repo repo :status :missing}
      (let [html (fs/readFileSync f "utf8")]
        (if-let [content (extract html)]
          (do (fs/writeFileSync f (str (render content) "\n"))
              {:repo repo :status :ok :h1 (:h1 content)
               :pitch-paras (count (:pitch content))})
          {:repo repo :status :unparsed})))))

(let [[root & rest*] *command-line-args*
      repos (if (= "--all" (first rest*))
              (->> (fs/readFileSync (second rest*) "utf8")
                   str/split-lines (map str/trim) (remove str/blank?) vec)
              (vec rest*))
      results (mapv #(process! root %) repos)
      by (group-by :status results)]
  (doseq [[st rs] (sort-by key by)]
    (println (str "  " (name st) ": " (count rs))))
  (doseq [r (concat (:unparsed by) (:missing by))]
    (println (str "  !! " (name (:status r)) " " (:repo r))))
  (println (str "total " (count results))))

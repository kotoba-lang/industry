(ns tasuke-app.views
  "The page. Every rule it shows comes from `tasuke-app.oracle`, i.e. from the
  compiled `.kotoba`; this namespace decides nothing.

  Design system is jp-go-dds (DADS) — the workspace base since 2026-08-05. No raw
  hex, no px font size: every value is a `--hig-*` token that
  `jp-go-dds.tokens/bridge-css` resolves onto DADS primitives, so this page follows
  the design system without its own palette."
  (:require [clojure.string :as str]
            [jp-go-dds.core :as dds]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [tasuke-app.route :as route]
            [tasuke-app.windows :as windows]))

(def severity-chip
  {"critical" ["red" "危険 — いま動く"]
   "urgent"   ["red" "緊急 — 今日中に"]
   "elevated" ["orange" "要注意"]
   "info"     ["blue" "情報"]})

(defn- field [k]  @(rf/subscribe [:field k]))
(defn- put! [k v] (rf/dispatch [:field/set k v]))

;; A real handler cannot be attached through `dds/button` (it takes attrs it
;; knows), so the copy control is the DADS button markup with an on-click. The
;; class list is the component's, not a re-derivation of its styling.
(defn- copy-control [label text]
  [:button {:class "dads-button" :data-type "outline" :data-size "sm"
            :type "button"
            :on-click (fn [_]
                        (when-let [c (.-clipboard js/navigator)]
                          (.writeText c text)))}
   label])

(defn nav []
  (let [current @(rf/subscribe [:route])]
    (into [:nav {:class "app-nav" :aria-label "画面"}]
          (for [{:keys [id label]} route/views]
            [:a {:class "dads-button" :data-type (if (= id current) "solid-fill" "text")
                 :data-size "sm"
                 :href (route/view->fragment id)
                 :aria-current (when (= id current) "page")}
             label]))))

(defn banner []
  (let [{:keys [severity ja-kind cost-jpy]} @(rf/subscribe [:triage])
        [color text] (severity-chip severity ["blue" severity])]
    [:div {:class "app-verdict"}
     (dds/chip-label text {:color color :style "filled-1"})
     [:span {:class "app-verdict__kind"} ja-kind]
     [:span {:class "app-verdict__free"} (str "支援費用 ¥" cost-jpy "（無料）")]]))

;; --- 相談 -------------------------------------------------------------------

(defn soudan []
  [:<>
   (dds/section
    {:title "何が起きましたか"}
    [:p {:class "app-lede"}
     "書いた内容はこの端末の中だけで処理され、どこにも送信されず、保存もされません。タブを閉じれば消えます。"]
    (dds/form-field
     {:label "被害の状況" :for "narrative"
      :support "例）Xのアカウントを乗っ取られたかもしれません。メールアドレスが勝手に変更されたとの通知メールが来ました。"}
     (dds/textarea {:id "narrative" :rows 5
                    :value (field :narrative)
                    :on-change #(put! :narrative (.. % -target -value))}))
    (dds/grid
     {:min "16rem"}
     (let [{:keys [ok?]} @(rf/subscribe [:loss])]
       (dds/form-field
        {:label "金銭被害の額" :for "loss"
         :support (if ok?
                    "例）48万 / 48万5千 / 480,000 / なし"
                    "読み取れませんでした。数字か「48万5千」のように書いてください（このままだと 0 円として書面に出ます）")
         :status (when-not ok? "読み取れません")}
        (dds/input-text {:id "loss" :value (field :loss)
                         :inputMode "text"
                         :aria-invalid (when-not ok? "true")
                         :on-change #(put! :loss (.. % -target -value))})))
     (dds/form-field
      {:label "サービス名" :for "service" :support "例）X（旧Twitter）/ ○○銀行"}
      (dds/input-text {:id "service" :value (field :service)
                       :on-change #(put! :service (.. % -target -value))}))
     (dds/form-field
      {:label "対象アカウント / 口座 / URL" :for "account"}
      (dds/input-text {:id "account" :value (field :account)
                       :on-change #(put! :account (.. % -target -value))}))
     (dds/form-field
      {:label "いつ起きましたか" :for "occurred" :support "例）2026-08-28 夜"}
      (dds/input-text {:id "occurred" :value (field :occurred)
                       :on-change #(put! :occurred (.. % -target -value))})))
    [:div {:class "app-inline"}
     [:label {:class "dads-checkbox" :data-size "md"}
      [:span {:class "dads-checkbox__checkbox"}
       [:input {:class "dads-checkbox__input" :type "checkbox"
                :checked (boolean (field :ongoing?))
                :on-change #(put! :ongoing? (.. % -target -checked))}]]
      [:span {:class "dads-checkbox__label"} "いまも被害が続いている"]]]
    [:p {:class "app-note"}
     "判定は「どの窓口・どの手順に案内するか」を決めるためのもので、犯罪の認定ではありません（G4）。"])
   [banner]
   (dds/section
    {:title "この内容で出る答え"}
    [:p {:class "app-note"} "続きは上の「初動」「書面」から。"]
    [:div {:class "app-inline"}
     [:a {:class "dads-button" :data-type "solid-fill" :data-size "md"
          :href (route/view->fragment :plan)} "初動プランを見る"]])])

;; --- 初動 -------------------------------------------------------------------

(defn plan []
  (let [{:keys [actions deadlines windows kind]} @(rf/subscribe [:triage])
        done @(rf/subscribe [:done kind])]
    [:<>
     [banner]
     (when (seq deadlines)
       (dds/notification-banner
        {:type :warning :heading "もう動いている時計"}
        (into [:ul {:class "app-list"}] (for [d deadlines] [:li d]))))
     (dds/section
      {:title "いま順にやること"}
      [:p {:class "app-note"}
       "上から順に。証拠の保全が先で、パスワード変更はその次です — 先に変えると「乗っ取られていた」証拠が消えます。"]
      (into [:ol {:class "app-steps"}]
            (for [[i step] (map-indexed vector actions)]
              [:li {:class (when (contains? done i) "app-steps__done")}
               [:label {:class "dads-checkbox" :data-size "md"}
                [:span {:class "dads-checkbox__checkbox"}
                 [:input {:class "dads-checkbox__input" :type "checkbox"
                          :checked (contains? done i)
                          :on-change #(rf/dispatch [:action/toggle kind i])}]]
                [:span {:class "dads-checkbox__label"} step]]])))
     (dds/section
      {:title "この被害に対応する無料の窓口"}
      (into [:ul {:class "app-list"}]
            (for [w windows] [:li (windows/describe w) " " [:code w]]))
      [:p {:class "app-note"}
       "窓口の連絡先は「窓口」画面。助 は有料の紹介をしません（G5）。"])]))

;; --- 書面 -------------------------------------------------------------------

(defn- document-block [title text]
  (dds/card
   (dds/heading 3 title {:size "20"})
   [:pre {:class "app-doc"} text]
   [:div {:class "app-inline"} [copy-control "コピー" text]]))

(def doc-titles
  "The document kinds the guest warrants, in the order it returns them."
  {"damage-report"       "被害届（下書き）"
   "incident-statement"  "被害状況報告書"
   "evidence-index"      "証拠目録"
   "damage-calculation"  "被害額算定書"
   "bank-freeze-request" "銀行 組戻し・口座凍結依頼"
   "platform-request"    "プラットフォーム凍結・復旧依頼"
   "recovery-plan"       "アカウント復旧手順書"})

(defn shorui []
  (let [{:keys [documents]} @(rf/subscribe [:triage])
        filings @(rf/subscribe [:filings])]
    [:<>
     [banner]
     (dds/section
      {:title "書類に入れる情報"}
      [:p {:class "app-note"}
       "空欄のままでも下書きは出ます（「（記入）」として残ります）。ここも保存されません。"]
      (dds/grid
       {:min "16rem"}
       (dds/form-field
        {:label "申告者氏名" :for "subject"}
        (dds/input-text {:id "subject" :value (field :subject)
                         :on-change #(put! :subject (.. % -target -value))}))
       (dds/form-field
        {:label "提出先の警察署" :for "station" :support "例）渋谷警察署長 殿"}
        (dds/input-text {:id "station" :value (field :station)
                         :on-change #(put! :station (.. % -target -value))}))
       (dds/form-field
        {:label "金融機関" :for "bank" :support "不正送金があった場合"}
        (dds/input-text {:id "bank" :value (field :bank)
                         :on-change #(put! :bank (.. % -target -value))}))
       (dds/form-field
        {:label "振込先（判明分）" :for "counterparty"}
        (dds/input-text {:id "counterparty" :value (field :counterparty)
                         :on-change #(put! :counterparty (.. % -target -value))})))
      (dds/form-field
       {:label "経緯（1 行に 1 つ、起きた順）" :for "timeline"}
       (dds/textarea {:id "timeline" :rows 4 :value (field :timeline)
                      :on-change #(put! :timeline (.. % -target -value))}))
      (dds/grid
       {:min "16rem"}
       (dds/form-field
        {:label "気づいた契機" :for "discovery"}
        (dds/input-text {:id "discovery" :value (field :discovery)
                         :on-change #(put! :discovery (.. % -target -value))}))
       (dds/form-field
        {:label "現在の状況" :for "current" :support "例）パスワード変更済"}
        (dds/input-text {:id "current" :value (field :current)
                         :on-change #(put! :current (.. % -target -value))}))))
     (dds/section
      {:title "この被害で作る書面"}
      [:p {:class "app-note"}
       "すべて本人が作成し、本人が署名して、本人が提出する下書きです。助 が警察やプラットフォームの名義で書くことはありません（G3）。費用は ¥0（G1）。どれを作るかも、本文も、guest が決めています。"]
      (into [:ul {:class "app-list"}]
            (for [d documents] [:li (get doc-titles d d) " " [:code d]])))
     (into [:<>]
           (for [d documents
                 :let [text (get filings d)]
                 :when text]
             (dds/section {:title (get doc-titles d d)}
                          [document-block (get doc-titles d d) text])))]))

;; --- 証拠 -------------------------------------------------------------------

(defn- sha256-hex [^js buf]
  (-> (.digest (.. js/crypto -subtle) "SHA-256" buf)
      (.then (fn [d]
               (->> (js/Uint8Array. d)
                    (map #(.padStart (.toString % 16) 2 "0"))
                    (apply str))))))

(defn shoko []
  ;; The two r/atoms are constructor state, which is what form-2 is for. The
  ;; SUBSCRIPTION is not: deref'd out here it is read once and the table never
  ;; repaints — added evidence would not appear and 削除 would do nothing
  ;; visible. Measured 2026-08-29.
  (let [label (r/atom "")
        kind  (r/atom "screenshot")]
    (fn []
      (let [items @(rf/subscribe [:evidence])]
       [:<>
       (dds/section
        {:title "証拠を固める"}
        [:p {:class "app-note"}
         "ファイルの中身はこの端末から出ません。ここに残るのはラベル・種別・sha256 だけで、内容そのものは保存しません（G6）。同じファイルが後で改変されていないことは、このハッシュで確認できます。"]
        (dds/grid
         {:min "14rem"}
         (dds/form-field
          {:label "ラベル" :for "ev-label" :support "例）変更通知メールのスクショ"}
          (dds/input-text {:id "ev-label" :default-value ""
                           :on-change #(reset! label (.. % -target -value))}))
         (dds/form-field
          {:label "種別" :for "ev-kind"}
          [:span {:class "dads-select"}
           [:select {:class "dads-select__select" :data-size "md" :id "ev-kind"
                     :on-change #(reset! kind (.. % -target -value))}
            (for [k ["screenshot" "email-header" "url" "chat-log" "transaction-record"
                     "account-id" "file-hash" "other"]]
              ^{:key k} [:option {:value k} k])]]))
        [:div {:class "app-inline"}
         [:input {:type "file" :class "app-file"
                  :on-change
                  (fn [e]
                    (when-let [f (aget (.. e -target -files) 0)]
                      (-> (.arrayBuffer f)
                          (.then sha256-hex)
                          (.then (fn [hex]
                                   (rf/dispatch
                                    [:evidence/add
                                     {:label (if (str/blank? @label) (.-name f) @label)
                                      :kind @kind
                                      :sha256 hex
                                      :bytes (.-size f)}])))
                          (.catch (fn [err]
                                    (js/alert (str "ハッシュを計算できませんでした: " err
                                                   "\n（https で開いた場合のみ利用できます）")))))))}]]
        (if (seq items)
          [:table {:class "dads-table app-table"}
           [:thead [:tr [:th "ラベル"] [:th "種別"] [:th "sha256"] [:th ""]]]
           (into [:tbody]
                 (for [[i it] (map-indexed vector items)]
                   [:tr
                    [:td (:label it)]
                    [:td [:code (:kind it)]]
                    [:td [:code {:class "app-hash"} (subs (:sha256 it) 0 16) "…"]]
                    [:td [:button {:class "dads-button" :data-type "text" :data-size "sm"
                                   :type "button"
                                   :on-click #(rf/dispatch [:evidence/drop i])} "削除"]]]))]
          [:p {:class "app-note"} "まだ 1 件もありません。"]))]))))

;; --- 窓口 -------------------------------------------------------------------
;; The codes come from the guest; the contact details are facts about the
;; outside world, kept here as a plain table and dated. If one is wrong, it is
;; wrong as data, not as a rule.

(defn madoguchi []
  (let [{:keys [windows]} @(rf/subscribe [:triage])]
    [:<>
     (dds/section
      {:title "この被害に対応する窓口（優先順）"}
      (into [:ol {:class "app-list"}]
            (for [w windows
                  :let [[name contact url] (get windows/directory w [w "" ""])]]
              [:li [:strong name] (when (seq contact) (str " — " contact))
               (when (seq url) [:span " " [:a {:href url :target "_blank" :rel "noreferrer"} url]])]))
      [:p {:class "app-note"} "いずれも無料の公的／公益窓口です。有料の紹介はしません（G5）。連絡先は 2026-08-29 時点の記載。"])
     (dds/section
      {:title "すべての窓口"}
      (into [:ul {:class "app-list"}]
            (for [[code [name contact url]] (sort windows/directory)]
              [:li [:code code] " " name (when (seq contact) (str " — " contact))])))]))

;; --- shell ------------------------------------------------------------------

(def view-fn
  {:soudan soudan :plan plan :shorui shorui :shoko shoko :madoguchi madoguchi})

(defn app []
  (let [current @(rf/subscribe [:route])
        {:keys [hint]} (first (filter #(= current (:id %)) route/views))]
    (dds/container
     [:header {:class "app-header"}
      (dds/heading 1 "助 — 乗っ取り・サイバー被害の初動" {:size "32"})
      [:p {:class "app-tagline"}
       "無料。本人が出す書類を作るところまで。代理ログインも代理提出もしません。"]
      [nav]
      [:p {:class "app-hint"} hint]]
     [:main {:id "main"} [(get view-fn current soudan)]]
     [:footer {:class "app-footer"}
      [:p "決定は "
       [:code "kotoba/triage_core.kotoba"]
       " が持ち、コンパイル済みの KIR をこのページが実行しています。判定・窓口・手順・書面はすべてその 1 つの成果物から出ています。"]
      [:p "実装元: cloud-itonami/tasuke（ADR-2606060900）。この画面は R0 — 送信も提出も行いません。"]])))

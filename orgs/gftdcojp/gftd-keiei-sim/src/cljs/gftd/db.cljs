(ns gftd.db
  "re-frame app-db の初期値と、2Dオフィスの静的レイアウト定数。")

;; 提案者(LLMエージェント社員): home(席) と meeting(会議卓近く) の座標(%)。
(def workers
  [{:role "sales"   :label "営業"    :emoji "🧑‍💼" :desk "📞" :hx 14 :hy 22 :mx 40 :my 52 :ph 0.0}
   {:role "eng"     :label "開発"    :emoji "🧑‍💻" :desk "💻" :hx 50 :hy 16 :mx 50 :my 48 :ph 1.2}
   {:role "finance" :label "財務"    :emoji "👩‍💼" :desk "💴" :hx 86 :hy 22 :mx 60 :my 52 :ph 2.4}
   {:role "legal"   :label "法務"    :emoji "🧑‍⚖️" :desk "📜" :hx 28 :hy 44 :mx 44 :my 58 :ph 3.6}
   {:role "ceo"     :label "CEO補佐" :emoji "🧑‍🏫" :desk "📊" :hx 72 :hy 44 :mx 58 :my 58 :ph 4.8}])

;; アンビエント社員: LLMは呼ばず役割別ライブ情報を表示しながら自席で働く。
(def ambient
  [{:id "soumu" :label "総務"     :emoji "🗂️" :hx 11 :hy 72 :ph 0.5 :cap :renewal}
   {:id "shomu" :label "庶務"     :emoji "🧾" :hx 31 :hy 78 :ph 1.7 :cap :deps}
   {:id "phone" :label "電話番"   :emoji "☎️" :hx 50 :hy 73 :ph 2.9 :cap :calls}
   {:id "mail"  :label "メール担当" :emoji "📧" :hx 70 :hy 78 :ph 4.1 :cap :inbound}
   {:id "rep"   :label "営業担当"  :emoji "🕴️" :hx 89 :hy 70 :ph 5.3 :cap :chase}])

(def default-db
  {:data     nil      ; サーバ /api/state 由来のスナップショット (旧 app-data)
   :thinking false    ; ターン進行中(社員が会議中)フラグ
   :modal    nil})    ; レポート/商談要約モーダル {:title :body :loading} or nil

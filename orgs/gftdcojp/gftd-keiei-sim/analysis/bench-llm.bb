#!/usr/bin/env bb
;; bench-llm.bb — 経営判断プロンプトで推論モデルの性能比較 (thinking 有効・十分な context)。
;; gemma4(local Ollama, think:true) / minimax-m3 / kimi-k2.7-code / qwen3.7-max
;; (OpenRouter, reasoning:{effort:high}) を同一プロンプトで叩き、レイテンシ・思考量・最終回答を比較。
;; OpenRouter には intel を含むプロンプトが外部送信される。
;;   bb analysis/bench-llm.bb
(require '[babashka.process :as p] '[cheshire.core :as json] '[clojure.string :as str])

;; 十分な context: 実 intel を厚めに与える
(def prompt
  (str "あなたは株式会社gftdのCEO補佐(経営参謀)です。以下の社内インテリジェンスを踏まえ、"
       "今期(当四半期)に最優先で下すべき経営判断を1つ、観測根拠を明示して日本語で具申してください。\n\n"
       "【財務】現金15.05億円 / 月次バーン1.5億円 / ランウェイ約10ヶ月 / 累計売上(発行請求)4.54億 / 累計コスト(受領請求)3.17億 / 人員215名 / 士気70\n"
       "【不良債権(回収要確認, 売掛1年超)】セールスリンク¥4,308万 / イー・ラーニング研究所¥3,557万 / ウィネクト¥2,695万 ほか計8社 1.31億円\n"
       "【潜在リード(確度/path-weight)】linkedin.com(確度100,未返信6件) / moneyforward.com(確度100) / shikigaku.com(確度97,26名関与) / d-standing.co.jp(接触14,637) / rokes.exchange(離反リスク,未返信204)\n"
       "【契約更新リスク】Gftd DAO/DeSci(業務委託) / iChain株式会社(満了2023-02-28) / 東京信用保証協会\n"
       "【市場】jp-corp 35社 / global 27社 / web3 2社 / finance 1社\n"
       "【各部門の提案】営業:LinkedIn最有力リード追客 / 開発:商談貢献プロジェクト集中 / 財務:不良債権回収 / 法務:Gftd DAO契約更新確認\n"
       "【直近の実活動(M365)】定時取締役会 / GJ/Lawfirm/HR / IVS2026 M&Aカンファレンス案内 / 国際テクノロジーセンター×GftdサイバーセキュリティMTG\n\n"
       "結論(最優先の判断)とその観測根拠を、簡潔に述べてください。"))

(defn now [] (System/currentTimeMillis))
(defn clip [s n] (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn gemma-local []
  (let [t (now)
        out (-> (p/shell {:out :string :err :string :continue true} "curl" "-s" "--max-time" "420"
                         "http://localhost:11434/api/chat" "-H" "Content-Type: application/json"
                         "-d" (json/generate-string {:model "gemma4:latest"
                                                     :messages [{:role "user" :content prompt}]
                                                     :think true :stream false
                                                     :options {:num_predict 1200 :num_ctx 8192}}))
                :out)
        m (-> (json/parse-string out true) :message)]
    {:model "gemma4:latest (local, think)" :ms (- (now) t)
     :think (count (or (:thinking m) "")) :text (:content m)}))

(defn openrouter [model]
  (let [key (-> (p/shell {:out :string} "security" "find-generic-password" "-s" "gftd.openrouter" "-w") :out str/trim)
        t (now)
        out (-> (p/shell {:out :string :err :string :continue true} "curl" "-s" "--max-time" "420"
                         "https://openrouter.ai/api/v1/chat/completions"
                         "-H" (str "Authorization: Bearer " key)
                         "-H" "Content-Type: application/json"
                         "-d" (json/generate-string {:model model
                                                     :messages [{:role "user" :content prompt}]
                                                     :reasoning {:effort "high"}
                                                     :max_tokens 4000}))
                :out)
        j (try (json/parse-string out true) (catch Exception _ {}))
        msg (-> j :choices first :message)]
    {:model model :ms (- (now) t)
     :think (count (or (:reasoning msg) ""))
     :text (or (not-empty (str/trim (or (:content msg) "")))
               (:reasoning msg)
               (str "ERR: " (get-in j [:error :message])))}))

(println "=== 推論モデル性能比較 (thinking有効・十分なcontext) ===\n")
(doseq [f [#(openrouter "google/gemma-3n-e4b-it")
           #(openrouter "minimax/minimax-m3")
           #(openrouter "moonshotai/kimi-k2.7-code")
           #(openrouter "qwen/qwen3.7-max")]]
  (let [r (try (f) (catch Exception e {:model "?" :ms 0 :think 0 :text (str "ERR: " (ex-message e))}))]
    (println (format "▼ %-30s  %6d ms  | 思考%5d字" (:model r) (:ms r) (:think r)))
    (println "  " (clip (str/replace (str/trim (or (:text r) "")) #"\s+" " ") 220) "\n")))

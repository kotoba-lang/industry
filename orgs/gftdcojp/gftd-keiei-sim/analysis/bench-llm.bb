#!/usr/bin/env bb
;; bench-llm.bb — 経営判断プロンプトで推論モデルの性能比較。
;; gemma4(local Ollama) / minimax-m3 / kimi-k2.7-code(OpenRouter) を同一プロンプトで叩き、
;; レイテンシ(ms)と出力を比較する。OpenRouter には intel を含むプロンプトが外部送信される。
;;   bb analysis/bench-llm.bb
(require '[babashka.process :as p] '[cheshire.core :as json] '[clojure.string :as str])

(def prompt
  "あなたは株式会社gftdのCEO補佐です。現状況: 現金15.05億/ランウェイ10ヶ月/人員215名/月次バーン1.5億/不良債権8社計1.31億/最有力リードlinkedin.com(確度100,未返信6件)。各部門の提案(営業:LinkedIn追客/財務:不良債権回収/法務:Gftd DAO契約更新確認)を踏まえ、今期最優先の経営判断を1つ、観測根拠つきで日本語2文以内で具申してください。")

(defn now [] (System/currentTimeMillis))

(defn gemma-local []
  (let [t (now)
        out (-> (p/shell {:out :string :err :string} "curl" "-s" "--max-time" "180"
                         "http://localhost:11434/api/chat" "-H" "Content-Type: application/json"
                         "-d" (json/generate-string {:model "gemma4:latest"
                                                     :messages [{:role "user" :content prompt}]
                                                     :think false :stream false
                                                     :options {:num_predict 200}}))
                :out)]
    {:model "gemma4:latest (local)" :ms (- (now) t)
     :text (-> (json/parse-string out true) :message :content)}))

(defn openrouter [model]
  (let [key (-> (p/shell {:out :string} "security" "find-generic-password" "-s" "gftd.openrouter" "-w") :out str/trim)
        t (now)
        out (-> (p/shell {:out :string :err :string} "curl" "-s" "--max-time" "180"
                         "https://openrouter.ai/api/v1/chat/completions"
                         "-H" (str "Authorization: Bearer " key)
                         "-H" "Content-Type: application/json"
                         "-d" (json/generate-string {:model model
                                                     :messages [{:role "user" :content prompt}]
                                                     :max_tokens 1500}))
                :out)
        j (try (json/parse-string out true) (catch Exception _ {}))
        msg (-> j :choices first :message)]
    {:model model :ms (- (now) t)
     :text (or (not-empty (str/trim (or (:content msg) "")))
               (:reasoning msg)
               (str "ERR: " (get-in j [:error :message])))}))

(println "=== 推論モデル性能比較 (同一経営判断プロンプト) ===\n")
(doseq [r [(gemma-local)
           (openrouter "minimax/minimax-m3")
           (openrouter "moonshotai/kimi-k2.7-code")
           (openrouter "qwen/qwen3.7-max")]]
  (println (format "▼ %-32s  %6d ms" (:model r) (:ms r)))
  (println "  " (str/trim (or (:text r) "")) "\n"))

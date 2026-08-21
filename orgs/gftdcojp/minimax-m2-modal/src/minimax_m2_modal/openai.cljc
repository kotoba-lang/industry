(ns minimax-m2-modal.openai
  (:require [babashka.http-client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defn normalize-config [{:keys [url model api-key]}]
  (let [url (some-> url str str/trim)]
    (when (str/blank? url)
      (throw (ex-info "An explicit OpenAI-compatible endpoint URL is required."
                      {:field :url})))
    {:url (str/replace url #"/+$" "")
     :model (or model "MiniMaxAI/MiniMax-M2.7")
     :api-key (or api-key "minimax-m2-7-testkey-7f3a")}))

(defn endpoint [config]
  (str (:url (normalize-config config)) "/v1/chat/completions"))

(defn model [config]
  (:model (normalize-config config)))

(defn post-chat [config body]
  (let [{:keys [model api-key] :as config} (normalize-config config)
        resp (http/post (endpoint config)
                        {:headers {"authorization" (str "Bearer " api-key)
                                   "content-type" "application/json"}
                         :throw false
                         :timeout 1800000
                         :body (json/write-str (assoc body :model model) :escape-slash false)})
        parsed (json/read-str (:body resp) :key-fn keyword)]
    (when (<= 400 (:status resp))
      (throw (ex-info "LLM request failed" {:status (:status resp) :body parsed})))
    parsed))

(defn first-message [resp]
  (get-in resp [:choices 0 :message]))

(defn show-message [resp]
  (let [msg (first-message resp)
        reasoning (:reasoning_content msg)]
    (when (seq reasoning)
      (println)
      (println "[reasoning]")
      (println reasoning))
    (println (or (:content msg) "(no content)"))
    msg))

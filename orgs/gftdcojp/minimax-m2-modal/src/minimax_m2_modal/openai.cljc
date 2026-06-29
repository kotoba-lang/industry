(ns minimax-m2-modal.openai
  (:require [babashka.http-client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defn endpoint []
  (let [url (or (System/getenv "LLM_URL") (System/getenv "MINIMAX_URL"))]
    (when (str/blank? url)
      (throw (ex-info "Set LLM_URL to the OpenAI-compatible endpoint without trailing /v1."
                      {:env ["LLM_URL" "MINIMAX_URL"]})))
    (str (str/replace url #"/+$" "") "/v1/chat/completions")))

(defn model []
  (or (System/getenv "LLM_MODEL") "MiniMaxAI/MiniMax-M2.7"))

(defn api-key []
  (or (System/getenv "LLM_KEY") "minimax-m2-7-testkey-7f3a"))

(defn post-chat [body]
  (let [resp (http/post (endpoint)
                        {:headers {"authorization" (str "Bearer " (api-key))
                                   "content-type" "application/json"}
                         :throw false
                         :timeout 1800000
                         :body (json/write-str (assoc body :model (model)) :escape-slash false)})
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

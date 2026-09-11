(ns minimax-m2-modal.client
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [minimax-m2-modal.openai :as openai]))

(defn chat [config]
  (println "LLM chat. Ctrl-D to exit.")
  (loop [history []]
    (print "\nyou> ")
    (flush)
    (if-let [line (read-line)]
      (let [text (str/trim line)]
        (if (str/blank? text)
          (recur history)
          (let [messages (conj history {:role "user" :content text})
                resp (openai/post-chat config {:messages messages
                                        :temperature 1.0
                                        :top_p 0.95
                                        :max_tokens 2048})
                msg (openai/show-message resp)]
            (recur (conj messages {:role "assistant" :content (or (:content msg) "")})))))
      (println))))

(def weather-tool
  {:type "function"
   :function {:name "get_weather"
              :description "Get the current weather for a city."
              :parameters {:type "object"
                           :properties {:city {:type "string" :description "City name"}
                                        :unit {:type "string" :enum ["c" "f"]}}
                           :required ["city"]}}})

(defn tools [config]
  (let [messages [{:role "user"
                   :content "What's the weather in Tokyo right now? Use the tool, then tell me in Celsius."}]
        resp (openai/post-chat config {:messages messages :tools [weather-tool] :temperature 1.0})
        msg (openai/show-message resp)]
    (if-not (seq (:tool_calls msg))
      (println "(model did not call a tool)")
      (let [tool-messages
            (reduce
             (fn [acc tc]
               (let [args (json/read-str (get-in tc [:function :arguments]) :key-fn keyword)
                     result {:city (:city args) :temp_c 22 :condition "clear"}]
                 (println)
                 (println "[tool_call]" (str (get-in tc [:function :name]) "(" args ")"))
                 (conj acc {:role "tool"
                            :tool_call_id (:id tc)
                            :content (json/write-str result)})))
             (conj messages msg)
             (:tool_calls msg))
            final (openai/post-chat config {:messages tool-messages :tools [weather-tool] :temperature 1.0})]
        (println)
        (println "[final answer]")
        (openai/show-message final)))))

(defn coding [config]
  (openai/show-message
   (openai/post-chat config
    {:messages [{:role "user"
                 :content "Write a Clojure function `merge-intervals` that merges overlapping [start end] vectors. Include 3 clojure.test examples and explain the time complexity."}]
     :temperature 1.0
     :top_p 0.95
     :max_tokens 2048})))

(defn run [config mode]
  (case (or mode "chat")
    "tools" (tools config)
    "coding" (coding config)
    "chat" (chat config)
    (chat config)))

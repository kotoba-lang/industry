(ns minimax-m2-modal.bench
  (:require [clojure.data.json :as json]
            [minimax-m2-modal.openai :as openai]))

(def prompts
  {:reasoning "A farmer has 17 sheep. All but 9 run away. Then he buys 5 more, and twice as many goats as the sheep he now has. How many animals total? Show your reasoning step by step, then give the final number."
   :coding "Write a Clojure function `merge-intervals` that merges overlapping [start end] vectors. Include 3 clojure.test examples and state the time complexity."})

(def weather-tool
  {:type "function"
   :function {:name "get_weather"
              :description "Get current weather for a city."
              :parameters {:type "object"
                           :properties {:city {:type "string"}
                                        :unit {:type "string" :enum ["c" "f"]}}
                           :required ["city"]}}})

(defn timed [label f]
  (let [t0 (System/nanoTime)
        result (f)
        seconds (/ (double (- (System/nanoTime) t0)) 1000000000.0)]
    (println (format "[latency] %s %.1fs" label seconds))
    result))

(defn show [label resp]
  (println)
  (println (str "========== " label " =========="))
  (let [msg (openai/show-message resp)
        usage (:usage resp)]
    (when usage
      (println)
      (println (format "[tokens] prompt=%s completion=%s"
                       (:prompt_tokens usage) (:completion_tokens usage))))
    msg))

(defn reasoning []
  (timed "reasoning"
         #(show "1) REASONING"
                (openai/post-chat {:temperature 1.0
                                   :top_p 0.95
                                   :max_tokens 1500
                                   :messages [{:role "user" :content (:reasoning prompts)}]}))))

(defn tool-call []
  (let [messages [{:role "user"
                   :content "What's the weather in Tokyo? Use the tool, then answer in Celsius."}]]
    (timed "tools"
           (fn []
             (let [resp (openai/post-chat {:messages messages :tools [weather-tool] :temperature 1.0})
                   msg (show "2) TOOL CALL (round 1)" resp)]
               (if-not (seq (:tool_calls msg))
                 (println ">>> MODEL DID NOT CALL THE TOOL")
                 (let [with-tools
                       (reduce
                        (fn [acc tc]
                          (let [args (json/read-str (get-in tc [:function :arguments]) :key-fn keyword)]
                            (println (str ">>> tool_call: " (get-in tc [:function :name]) "(" args ")"))
                            (conj acc {:role "tool"
                                       :tool_call_id (:id tc)
                                       :content (json/write-str {:city (:city args)
                                                                :temp_c 22
                                                                :condition "clear"})})))
                        (conj messages msg)
                        (:tool_calls msg))]
                   (show "2) TOOL CALL (final)"
                         (openai/post-chat {:messages with-tools
                                            :tools [weather-tool]
                                            :temperature 1.0})))))))))

(defn coding []
  (timed "coding"
         #(show "3) CODING"
                (openai/post-chat {:temperature 1.0
                                   :top_p 0.95
                                   :max_tokens 2000
                                   :messages [{:role "user" :content (:coding prompts)}]}))))

(defn -main [& _]
  (println (str "### TARGET: " (openai/model) " @ " (or (System/getenv "LLM_URL")
                                                        (System/getenv "MINIMAX_URL"))))
  (reasoning)
  (tool-call)
  (coding)
  (println)
  (println "### DONE"))

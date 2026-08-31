(ns gftd.murakumo
  "cloud-murakumo OpenAI-compatible complete() for BMC ReAct advisor
   (ADR-2607180400). Pure builders + response parsers; I/O is injected.

   Default endpoint: https://api.murakumo.cloud/v1/chat/completions
   (public try-it path; enable_thinking off so content is not empty)."
  (:require [clojure.string :as str]
            #?(:clj [clojure.data.json :as json])))

(def default-url "https://api.murakumo.cloud/v1/chat/completions")
(def default-model
  "The ALIAS, never a checkpoint id (ADR-2607173100).

  This read `\"qwen3.6-35b-a3b\"` until 2026-08-31, and by then the fleet had
  stopped serving it — measured, that id answers HTTP 522 from the origin while
  `murakumo-main` completes normally. Every auto-advisor tick had been failing
  the call and falling through to gate-only, which prints as `proposals: 0`
  rather than as an error, so the loop looked idle instead of broken.

  Naming the *next* checkpoint would buy the same bug again on the next swap.
  The alias is one KV entry the operator repoints, and its dereference is the
  only thing that survives a model change. Measured the same day:
  `qwen3.8-27b-fastmtp-aggressive` — the concrete id first proposed as the
  replacement — is itself served out of `qwen3.8-27b-throughput-b70`, so it
  does not even name the model that answers it."
  "murakumo-main")
(def default-max-tokens 400)
(def user-agent "gftd-bmc/0.1 (portfolio business react loop)")

(defn build-body
  "OpenAI chat.completions request body map for a single prompt."
  [prompt {:keys [model max-tokens temperature]
           :or {model default-model
                max-tokens default-max-tokens
                temperature 0}}]
  {:model model
   :messages [{:role "user" :content (str prompt)}]
   :max_tokens max-tokens
   :temperature temperature
   ;; qwen3.x otherwise fills reasoning_content and leaves content blank
   :chat_template_kwargs {:enable_thinking false}})

(defn strip-fences
  "Strip a ```edn … ``` (or bare ```) markdown fence from LLM output.
   react/llm-advisor read-strings the returned content, so fences are
   removed here at the transport layer rather than at the parse site."
  [s]
  (let [s (str/trim (str s))]
    (if (str/starts-with? s "```")
      (-> s
          (str/replace #"^```[a-zA-Z0-9]*\s*" "")
          (str/replace #"\s*```\s*$" ""))
      s)))

(defn extract-content
  "Pull assistant text from an OpenAI-style response map, with any markdown
   code fence stripped. Prefers :content; falls back to :reasoning_content
   (thinking models)."
  [response]
  (let [msg (or (get-in response ["choices" 0 "message"])
                (get-in response [:choices 0 :message])
                {})
        content (or (get msg "content") (:content msg) "")
        reasoning (or (get msg "reasoning_content") (:reasoning_content msg) "")]
    (let [c (str/trim (str content))]
      (strip-fences (if (seq c) c (str/trim (str reasoning)))))))

(defn parse-json-body
  "Parse JSON body string → cljs/clj map. nil on failure."
  [body]
  (try
    #?(:clj  (json/read-str (str body))
       :cljs (js->clj (js/JSON.parse (str body)) :keywordize-keys false))
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn complete-from-http-result
  "Map {:status n :body s} → content string or nil.
   Non-2xx / empty content → nil (caller falls back to gate-only)."
  [{:keys [status body]}]
  (when (and status (>= status 200) (< status 300))
    (let [parsed (parse-json-body body)
          content (when parsed (extract-content parsed))]
      (when (seq content) content))))

(defn make-complete
  "Return (fn [prompt] → string-or-nil).
   `http-post!` is (fn [{:keys [url headers body]}] → {:status :body}).
   Fail-soft: network/HTTP/parse errors yield nil so the ReAct tick can fall
   back to the deterministic gate-aware advisor."
  [http-post! {:keys [url model max-tokens] :as opts}]
  (let [url (or url default-url)]
    (fn [prompt]
      (try
        (let [body #?(:clj  (json/write-str (build-body prompt opts))
                      :cljs (js/JSON.stringify
                             (clj->js (build-body prompt opts))))
              res (http-post! {:url url
                               :headers {"Content-Type" "application/json"
                                         "User-Agent" user-agent}
                               :body body})]
          (complete-from-http-result res))
        (catch #?(:clj Exception :cljs :default) e
          (println "murakumo complete failed:"
                   #?(:clj (.getMessage e) :cljs (or (.-message e) (str e))))
          nil)))))

(defn compose-gate+llm
  "Advisor that always runs gate-aware (deterministic progression), then
   optionally concatenates LLM proposals when `complete` returned parseable EDN.
   `gate-advisor` is e.g. react/gate-aware-advisor; `llm-advisor` is
   (react/llm-advisor complete)."
  [gate-advisor llm-advisor]
  (fn [obs]
    (into [] (concat (gate-advisor obs) (llm-advisor obs)))))

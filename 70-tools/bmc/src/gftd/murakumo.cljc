(ns gftd.murakumo
  "cloud-murakumo OpenAI-compatible complete() for BMC ReAct advisor
   (ADR-2607180400). Pure builders + response parsers; I/O is injected.

   Default endpoint: https://api.murakumo.cloud/v1/chat/completions
   (public try-it path; enable_thinking off so content is not empty).

   Model resolution (ADR-2607173100, repo-wide mandatory): concrete model
   ids are never baked into defaults — they die when the fleet repoints
   one KV entry. Resolution order:

     1. explicit `:model` opt, else BMC_MURAKUMO_MODEL env
     2. `murakumo-main` alias → GET /infer/models/murakumo-main, serve
        `alias-for` when the entry is :serving
     3. endpoint-only fallback → GET /v1/models, take the first id

   The failure this replaces: `default-model` was a bare checkpoint id,
   the fleet stopped serving it, and every advisor tick failed the call
   and fell through to gate-only — printing as `proposals: 0` rather than
   as an error, so the loop looked idle for as long as nobody read the
   ledger closely (root#2844, measured 2026-08-31)."
  (:require [clojure.string :as str]
            #?(:clj [clojure.data.json :as json])))

(def default-url "https://api.murakumo.cloud/v1/chat/completions")

(def alias-url "https://api.murakumo.cloud/infer/models/murakumo-main")
(def models-url "https://api.murakumo.cloud/v1/models")

;; The STARTING point of resolution, not the answer: `resolve-model` treats
;; this as the `murakumo-main` alias and dereferences it. Naming a concrete
;; checkpoint here would reintroduce exactly the root#2844 failure the
;; resolution exists to prevent.
(def default-model "murakumo-main")

(def default-max-tokens 400)
(def user-agent "gftd-bmc/0.1 (portfolio business react loop)")

(defn parse-json-body
  "Parse JSON body string → cljs/clj map. nil on failure."
  [body]
  (try
    #?(:clj  (json/read-str (str body))
       :cljs (js->clj (js/JSON.parse (str body)) :keywordize-keys false))
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn- serving?
  "An alias entry answers only while its status is serving — a stale or
  withdrawn alias must fall through, not be trusted."
  [entry]
  (= "serving" (or (get entry "status") (:status entry))))

(defn alias-target
  "Extract `alias-for` from a decoded /infer/models/murakumo-main body.
  nil unless the entry is present, serving, and names a target."
  [parsed]
  (when (serving? parsed)
    (or (get parsed "alias-for") (:alias-for parsed))))

(defn first-model-id
  "First id out of a decoded /v1/models body (endpoint-only fallback)."
  [parsed]
  (when-let [data (or (get parsed "data") (:data parsed))]
    (when (sequential? data)
      (when-let [m (first data)]
        (or (get m "id") (:id m))))))

(defn resolve-model
  "ADR-2607173100 resolution order:

     1. explicit `model` opt (caller argument — beats everything)
     2. BMC_MURAKUMO_MODEL env (env-get injected for tests)
     3. murakumo-main alias: GET alias-url, serve alias-for while :serving
     4. endpoint-only fallback: GET models-url, take the first id

  Returns a string, or nil when nothing could be resolved. nil is the
  caller's cue to build the request with the ALIAS itself (`default-model`)
  rather than a guessed checkpoint id: the OpenAI-compatible endpoint
  dereferences `murakumo-main` server-side, so sending the alias is always
  safe and never bakes an id.

  `http-get!` is (fn [url] → {:status n :body s}); injected so resolution
  is testable. It may return the same shape `http-post!` does."
  [http-get! {:keys [model env-get]}]
  (or model
      (let [env-model (when env-get (env-get "BMC_MURAKUMO_MODEL"))]
        (or (when (seq env-model) env-model)
            (let [res (try (http-get! alias-url) (catch #?(:clj Exception :cljs :default) _ nil))
                  parsed (and res (:body res) (parse-json-body (:body res)))]
              (or (alias-target parsed)
                  (let [res (try (http-get! models-url)
                                 (catch #?(:clj Exception :cljs :default) _ nil))]
                    (when (and res (:body res))
                      (first-model-id (parse-json-body (:body res)))))))))))

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
   `http-get!` (optional) is (fn [url] → {:status :body}) and powers the
   ADR-2607173100 model resolution; without it the request carries the
   `murakumo-main` alias itself, which the endpoint resolves server-side.
   Fail-soft: network/HTTP/parse errors yield nil so the ReAct tick can fall
   back to the deterministic gate-aware advisor."
  ([http-post! opts] (make-complete http-post! nil opts))
  ([http-post! http-get! {:keys [url model env-get] :as opts}]
   (let [url (or url default-url)
         ;; Resolution happens once per complete-maker, not per call: one
         ;; tick dereferences the alias once and then issues its calls.
         resolved (or model (resolve-model http-get! opts) default-model)]
     (fn [prompt]
       (try
         (let [opts' (assoc opts :model resolved)
               body #?(:clj  (json/write-str (build-body prompt opts'))
                       :cljs (js/JSON.stringify
                              (clj->js (build-body prompt opts'))))
               res (http-post! {:url url
                                :headers {"Content-Type" "application/json"
                                          "User-Agent" user-agent}
                                :body body})]
           (complete-from-http-result res))
         (catch #?(:clj Exception :cljs :default) e
           (println "murakumo complete failed:"
                    #?(:clj (.getMessage e) :cljs (or (.-message e) (str e))))
           nil))))))

(defn compose-gate+llm
  "Advisor that always runs gate-aware (deterministic progression), then
   optionally concatenates LLM proposals when `complete` returned parseable EDN.
   `gate-advisor` is e.g. react/gate-aware-advisor; `llm-advisor` is
   (react/llm-advisor complete)."
  [gate-advisor llm-advisor]
  (fn [obs]
    (into [] (concat (gate-advisor obs) (llm-advisor obs)))))

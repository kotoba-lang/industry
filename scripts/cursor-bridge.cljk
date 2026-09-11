#!/usr/bin/env nbb
;; cursor-bridge — an OpenAI-compatible /v1 endpoint backed by the local
;; `cursor-agent -p` CLI, so agents that speak chat/completions (hermes' `custom`
;; provider, aider, cline, …) run against the Cursor SUBSCRIPTION.
;;
;;   nbb ~/.itonami/cursor-bridge.cljs            # listens on 127.0.0.1:9181
;;
;; Sibling of ~/.itonami/claude-bridge.cljs (port 9180). Same request/response
;; shaping, different backend, different subscription. Two bridges rather than
;; one because 23 hermes profiles were sharing a single `claude` process capped
;; at 2 concurrent — and because the Cursor plan was measured 2026-08-19 as
;; entirely unused by the bots while carrying models the Claude route has not
;; got (GPT-5.3 Codex, GPT-5.2, Grok 4.6, Composer 2.5, GPT-5.6 Sol).
;;
;; Env: CURSOR_BRIDGE_PORT (9181) CURSOR_BRIDGE_CONCURRENCY (2)
;;      CURSOR_BRIDGE_MODEL (cursor-grok-4.6-high) — used when a caller names none
;;      CURSOR_BRIDGE_TIMEOUT (600s) CURSOR_BRIDGE_BIN (cursor-agent)
;;      CURSOR_API_KEY — OPTIONAL. Unset means the CLI's own logged-in account.
;;        Set it to bind THIS bridge to one specific account, which is how a
;;        personal and a company plan stay separate: two bridges, two ports,
;;        two keys, and no request can silently take the other one's quota.
;;
;; Why each flag is here (measured 2026-08-19, cursor-agent on this machine):
;;   -p --output-format json  `.result` carries the answer, same shape as claude
;;   --model <id>             `cursor-agent models` lists them PER ACCOUNT, so a
;;                            personal and a company plan can legitimately serve
;;                            different sets; this bridge does not hardcode one
;;   --trust                  without it the CLI refuses with "Workspace Trust
;;                            Required" and exits 1 — measured, it is not
;;                            optional for a non-interactive run
;;   --sandbox enabled        keep the guest boxed even though it has no work to
;;                            do in the filesystem
;;   cwd = a neutral empty dir  so the CLI does not adopt the repository (and
;;                            the ~90 KB CLAUDE.md) of wherever it was started
;;
;; NOT used, deliberately:
;;   -f / --yolo  "Force allow commands unless explicitly denied". That is the
;;                approval gate, and this endpoint is consumed by unattended
;;                bots. A text bridge has no reason to hold it. If a future
;;                caller genuinely needs tool use, it should get its own
;;                endpoint with its own argument set, not this one widened.

(ns cursor-bridge
  (:require ["node:http" :as http]
            ["node:child_process" :as cp]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:fs" :as fs]
            [clojure.string :as str]))

(def env (.-env js/process))
(defn env-int [k d] (let [v (aget env k)] (if (and v (seq v)) (js/parseInt v 10) d)))

(def port          (env-int "CURSOR_BRIDGE_PORT" 9181))
(def max-inflight  (env-int "CURSOR_BRIDGE_CONCURRENCY" 2))
(def child-timeout (* 1000 (env-int "CURSOR_BRIDGE_TIMEOUT" 600)))
(def cursor-bin    (or (aget env "CURSOR_BRIDGE_BIN") "cursor-agent"))
(def workdir       (path/join (os/homedir) ".itonami" "cursor-bridge-cwd"))

;; ids `cursor-agent models` reported for the account this bridge was started
;; with. Grok 4.6 leads because it is what this bridge was asked to serve, and
;; `cursor-grok-4.6-high` is the plain "Cursor Grok 4.6" of that family — the
;; -low/-medium/-xhigh siblings are effort settings of the same model, exposed
;; so a caller can trade latency for depth without leaving the route.
;;
;; Measured 2026-08-19 on the same prompt (17 * 23), through this CLI:
;;   cursor-grok-4.6-high  15.6s  in 15,329  out 42
;;   gpt-5.2               30.2s  in 21,715  out 26
;; so the default is also the cheaper and faster of the two.
(def served-models
  ["cursor-grok-4.6-high" "cursor-grok-4.6-high-fast"
   "cursor-grok-4.6-medium" "cursor-grok-4.6-medium-fast"
   "cursor-grok-4.6-low" "cursor-grok-4.6-low-fast"
   "cursor-grok-4.6-xhigh" "cursor-grok-4.6-xhigh-fast"
   "grok" "grok-4.6"
   "auto"
   "gpt-5.3-codex" "gpt-5.3-codex-high" "gpt-5.2" "gpt-5.6-sol-high"
   "composer-2.5" "claude-opus-5-thinking-high"])

;; Short names callers reach for, mapped to the id the CLI actually accepts.
;; Without this a request for "grok" dies in the CLI with an unknown-model
;; error that surfaces as a 502 — the caller asked for something reasonable.
(def model-aliases
  {"grok" "cursor-grok-4.6-high"
   "grok-4.6" "cursor-grok-4.6-high"
   "grok-4.6-fast" "cursor-grok-4.6-high-fast"})

(defn log [& xs]
  (.error js/console (str (.toISOString (js/Date.)) " [cursor-bridge] "
                          (str/join " " xs))))

;; ── request shaping ──────────────────────────────────────────────────────
(defn block-text [b]
  (cond (string? b) b
        (and (map? b) (= "text" (:type b))) (or (:text b) "")
        :else ""))

(defn content->text [c]
  (cond (string? c) c
        (sequential? c) (str/join "\n" (remove str/blank? (map block-text c)))
        :else ""))

;; ── what this bridge CANNOT carry, said out loud ─────────────────────────
;;
;; `cursor-agent` (measured 2026-08-19) has no --json-schema and no
;; --input-format: there is no structured-output channel to return tool calls
;; through, and no way to put an image into the request at all. The prompt is
;; text.
;;
;; So unlike ~/.itonami/claude-bridge.cljs, which gained both on 2026-08-19, this
;; one genuinely cannot. What it must NOT do is drop them silently: a caller
;; that attached a screenshot and got a confident answer has been told the
;; model looked at something it never received. Count them, log them, and tell
;; the guest -- a model that knows an image was withheld says so instead of
;; describing one.

(defn image-block? [b]
  (and (map? b) (contains? #{"image" "image_url"} (:type b))))

(defn count-images [messages]
  (reduce + 0 (for [m messages
                    :let [c (:content m)]
                    :when (sequential? c)]
                (count (filter image-block? c)))))

(defn count-tools [body]
  (if (= "none" (:tool_choice body)) 0 (count (:tools body))))

(defn dropped-directive [images tools]
  (str/join
    " "
    (remove nil?
      [(when (pos? images)
         (str "The caller attached " images " image(s) to this conversation. "
              "This bridge CANNOT carry images and you have NOT received them. "
              "Do not describe, guess at, or pretend to have seen them: say "
              "plainly that the image did not reach you."))
       (when (pos? tools)
         (str "The caller declared " tools " tool(s). This bridge cannot return "
              "tool calls, so you cannot invoke any of them. Say which one you "
              "would call and with what arguments, in prose."))])))

(def default-model
  (or (aget env "CURSOR_BRIDGE_MODEL") "cursor-grok-4.6-high"))

(defn resolve-model [m]
  (let [m (str/trim (or m ""))
        ;; callers label the route ("cursor/grok", "cursor-cli/gpt-5.2")
        m (if-let [i (str/last-index-of m "/")] (subs m (inc i)) m)]
    (cond
      (str/blank? m) default-model
      (contains? model-aliases m) (get model-aliases m)
      :else m)))

;; One prompt string. `cursor-agent -p` takes a single prompt and this bridge
;; does not resume a CLI chat per HTTP request, so a real conversation is
;; rendered as a transcript.
(defn messages->prompt [messages]
  (let [turns (remove #(= "system" (:role %)) messages)]
    (if (= 1 (count turns))
      (content->text (:content (first turns)))
      (str/join "\n\n"
        (concat
          (for [{:keys [role content]} turns]
            (str "[" (or role "user") "]\n" (content->text content)))
          ["[assistant]"])))))

(defn messages->system [messages]
  (->> messages
       (filter #(= "system" (:role %)))
       (map #(content->text (:content %)))
       (remove str/blank?)
       (str/join "\n\n")))

;; cursor-agent has no --append-system-prompt, so the system turn is prepended
;; to the prompt. Marked, so the model can tell instruction from conversation.
(def no-tools-directive
  (str "You are answering through a text-only bridge. Do not run commands, "
       "edit files, or use tools — answer from what is already in this "
       "conversation. If the request needs a command or a file, say what you "
       "would run and what you would look for."))

(defn compose-prompt [system prompt & [dropped]]
  (let [sys (str/join "\n\n" (remove str/blank? [system no-tools-directive dropped]))]
    (str "[system]\n" sys "\n\n" prompt)))

;; ── the subprocess ───────────────────────────────────────────────────────
(defn extract-text
  "cursor-agent's --output-format json has varied across versions; take the
  first field that actually carries prose rather than assuming one shape.
  Returns nil when none of them do, so the caller reports a real error instead
  of a confident empty string."
  [parsed]
  (or (not-empty (str (or (:result parsed) "")))
      (not-empty (str (or (:text parsed) "")))
      (not-empty (str (or (:response parsed) "")))
      (when-let [ms (:messages parsed)]
        (not-empty (str/join "\n" (remove str/blank? (map #(content->text (:content %)) ms)))))))

(defn spawn-cursor [{:keys [model system prompt]} cb]
  (let [args ["-p" "--output-format" "json"
              "--model" model
              "--trust"
              "--sandbox" "enabled"]
        child (cp/spawn cursor-bin (clj->js args)
                        #js {:cwd workdir :stdio "pipe"})
        out (atom "") err (atom "") done (atom false)
        finish (fn [res] (when-not @done (reset! done true) (cb res)))
        timer (js/setTimeout
                (fn [] (.kill child "SIGKILL")
                       (finish {:error (str "cursor-agent timed out after "
                                            (/ child-timeout 1000) "s")}))
                child-timeout)]
    (.on (.-stdout child) "data" #(swap! out str (.toString %)))
    (.on (.-stderr child) "data" #(swap! err str (.toString %)))
    (.on child "error"
         (fn [e] (js/clearTimeout timer)
                 (finish {:error (str "could not spawn '" cursor-bin "': " (.-message e))})))
    (.on child "close"
      (fn [code]
        (js/clearTimeout timer)
        (let [raw (str/trim @out)
              parsed (try (js->clj (js/JSON.parse raw) :keywordize-keys true)
                          (catch :default _ nil))
              text (when parsed (extract-text parsed))]
          (cond
            ;; keep the body. status alone cannot say why this failed — the
            ;; invalid-key warning, for one, only appears on stderr.
            (nil? parsed)
            (finish {:error (str "cursor-agent exited " code
                                 "; stdout was not JSON: "
                                 (subs raw 0 (min 400 (count raw)))
                                 (when (seq @err) (str " | stderr: " (str/trim @err))))})

            ;; the CLI says so itself; do not read past it and hand back prose
            ;; that happens to sit in :result while is_error is true.
            (and (:is_error parsed) (nil? text))
            (finish {:error (str "cursor-agent reported an error"
                                 (when-let [st (:subtype parsed)] (str " subtype=" st))
                                 (when-let [r (:result parsed)] (str " result=" (pr-str r)))
                                 (when (seq @err) (str " stderr=" (pr-str (str/trim @err))))
                                 " | raw=" (subs raw 0 (min 600 (count raw))))})

            (nil? text)
            (finish {:error (str "cursor-agent returned JSON with no text"
                                 (when (seq @err) (str " stderr=" (pr-str (str/trim @err))))
                                 " | raw=" (subs raw 0 (min 600 (count raw))))})

            :else
            (finish {:text text :usage (:usage parsed)})))))
    (doto (.-stdin child) (.write prompt) (.end))))

;; ── admission ────────────────────────────────────────────────────────────
(def inflight (atom 0))
(def queued (atom []))

(defn run-next! []
  (if-let [f (first @queued)]
    (do (swap! queued #(vec (rest %))) (f))
    (swap! inflight dec)))

(defn admit! [f]
  (if (< @inflight max-inflight)
    (do (swap! inflight inc) (f))
    (swap! queued conj f)))

;; ── responses ────────────────────────────────────────────────────────────
(defn now-secs [] (js/Math.floor (/ (.now js/Date) 1000)))
(defn req-id [] (str "chatcmpl-" (.toString (js/Math.floor (* 1e12 (js/Math.random))) 36)))

(defn send-json [res status body]
  (.writeHead res status #js {"Content-Type" "application/json"})
  (.end res (js/JSON.stringify (clj->js body))))

(defn usage->openai
  "cursor-agent reports usage in camelCase — measured 2026-08-19:
  {:inputTokens 20251 :outputTokens 5 :cacheReadTokens 1408 :cacheWriteTokens 0}.
  Reading only the snake_case names claude uses reported 0/0/0 for every call,
  which is worse than no number at all: a caller watching spend would have seen
  a bridge that costs nothing. Both spellings are accepted so the same function
  keeps working if the CLI changes its mind.

  Cache reads and writes are real input against the plan, so they belong in
  prompt_tokens, and total is the sum of what is reported here."
  [u]
  (let [g (fn [& ks] (or (some #(get u %) ks) 0))
        in (+ (g :inputTokens :input_tokens :prompt_tokens)
              (g :cacheReadTokens :cache_read_input_tokens)
              (g :cacheWriteTokens :cache_creation_input_tokens))
        out (g :outputTokens :output_tokens :completion_tokens)]
    {:prompt_tokens in :completion_tokens out :total_tokens (+ in out)}))

(defn send-completion [res id model {:keys [text usage]}]
  (send-json res 200
    {:id id :object "chat.completion" :created (now-secs) :model model
     :choices [{:index 0
                :message {:role "assistant" :content text}
                :finish_reason "stop"}]
     :usage (usage->openai usage)}))

;; One chunk. A valid SSE stream to every OpenAI client, and it does not pretend
;; to token-level streaming the bridge cannot do.
(defn send-stream [res id model {:keys [text usage]}]
  (.writeHead res 200 #js {"Content-Type" "text/event-stream"
                           "Cache-Control" "no-cache"
                           "Connection" "keep-alive"})
  (let [chunk (fn [delta finish]
                (.write res (str "data: "
                                 (js/JSON.stringify
                                   (clj->js {:id id :object "chat.completion.chunk"
                                             :created (now-secs) :model model
                                             :choices [{:index 0 :delta delta
                                                        :finish_reason finish}]}))
                                 "\n\n")))]
    (chunk {:role "assistant" :content ""} nil)
    (chunk {:content text} nil)
    (chunk {} "stop")
    (.write res (str "data: "
                     (js/JSON.stringify
                       (clj->js {:id id :object "chat.completion.chunk"
                                 :created (now-secs) :model model
                                 :choices [] :usage (usage->openai usage)}))
                     "\n\n"))
    (.write res "data: [DONE]\n\n")
    (.end res)))

(defn handle-completion [res body]
  (let [model (resolve-model (:model body))
        prompt (messages->prompt (:messages body))
        system (messages->system (:messages body))
        id (req-id)
        stream? (true? (:stream body))
        images (count-images (:messages body))
        tools (count-tools body)
        dropped (dropped-directive images tools)
        t0 (.now js/Date)]
    (if (str/blank? prompt)
      (send-json res 400 {:error {:message "no user content in messages"
                                  :type "invalid_request_error"}})
      (admit!
        (fn []
          (log "->" model (str (count prompt) "B prompt")
               (if stream? "stream" "sync")
               ;; DROPPED is logged every time, not only when someone looks:
               ;; a caller whose screenshot vanished has no other way to find
               ;; out, and the answer will read as if it arrived.
               (if (pos? (+ images tools))
                 (str "DROPPED images=" images " tools=" tools
                      " (cursor-agent has no image input and no structured output)")
                 "")
               (str "inflight=" @inflight " queued=" (count @queued)))
          (spawn-cursor {:model model :system system
                         :prompt (compose-prompt system prompt dropped)}
            (fn [result]
              (run-next!)
              (let [ms (- (.now js/Date) t0)]
                (if (:error result)
                  (do (log "<- ERROR" model (str ms "ms") (:error result))
                      (send-json res 502 {:error {:message (:error result)
                                                  :type "upstream_error"}}))
                  (do (log "<-" model (str ms "ms")
                           (str (count (:text result)) "B"))
                      (if stream?
                        (send-stream res id model result)
                        (send-completion res id model result))))))))))))

(defn read-body [req cb]
  (let [buf (atom "")]
    (.on req "data" #(swap! buf str (.toString %)))
    (.on req "end" #(cb (try (js->clj (js/JSON.parse @buf) :keywordize-keys true)
                             (catch :default _ nil))))))

(def server
  (http/createServer
    (fn [req res]
      (let [url (str/replace (.-url req) #"\?.*$" "")
            method (.-method req)]
        (cond
          (and (= method "GET") (contains? #{"/health" "/"} url))
          ;; `keyed` says WHICH subscription this bridge spends, without
          ;; printing the key. With two bridges up, that is the only way to
          ;; tell the personal one from the company one from outside.
          (send-json res 200 {:status "ok" :backend cursor-bin
                              :keyed (boolean (seq (str (or (aget env "CURSOR_API_KEY") ""))))
                              :inflight @inflight :queued (count @queued)})

          (and (= method "GET") (str/ends-with? url "/models"))
          (send-json res 200
            {:object "list"
             :data (mapv (fn [m] {:id m :object "model" :created (now-secs)
                                  :owned_by "cursor-agent-cli"})
                         served-models)})

          (and (= method "POST") (str/ends-with? url "/chat/completions"))
          (read-body req (fn [body]
                           (if body
                             (handle-completion res body)
                             (send-json res 400 {:error {:message "body was not JSON"
                                                         :type "invalid_request_error"}}))))

          :else
          (send-json res 404 {:error {:message (str "no route for " method " " url)
                                      :type "not_found"}}))))))

(fs/mkdirSync workdir #js {:recursive true})
(.listen server port "127.0.0.1"
  (fn []
    (log "listening on http://127.0.0.1:" port "/v1")
    (log "backend:" cursor-bin "| cwd:" workdir
         "| max-inflight:" max-inflight "| timeout:" (/ child-timeout 1000) "s"
         "| default-model:" default-model "| CURSOR_API_KEY:" (if (seq (str (or (aget env "CURSOR_API_KEY") ""))) "set" "unset (CLI login)"))))

#!/usr/bin/env nbb
;; claude-bridge — an OpenAI-compatible /v1 endpoint backed by the local
;; `claude -p` CLI, so agents that speak chat/completions (hermes' `custom`
;; provider, aider, cline, …) run against the Claude Code SUBSCRIPTION
;; instead of the metered api.anthropic.com path.
;;
;;   nbb ~/.gftd/claude-bridge.cljs            # listens on 127.0.0.1:9180
;;
;; Env: CLAUDE_BRIDGE_PORT (9180) CLAUDE_BRIDGE_CONCURRENCY (2)
;;      CLAUDE_BRIDGE_TIMEOUT (600s) CLAUDE_BRIDGE_BIN (claude)
;;
;; Why each flag is here (measured 2026-08-19, Claude Code 2.1.234):
;;   --input-format stream-json / --output-format stream-json / --verbose
;;                          stream-json is the only input that carries image
;;                          blocks, so vision works on every request instead of
;;                          only the ones we remembered to special-case. The CLI
;;                          refuses stream-json output without --verbose, and
;;                          refuses stream-json input without stream-json output.
;;   --max-turns 12         the schema path's whole reply lives inside the final
;;                          StructuredOutput call, so a run that hits the cap
;;                          before reaching it returns no text at all, and the
;;                          truncation recovery below cannot fire -- it 502s
;;                          instead. Measured 2-5 turns on one
;;                          request shape; 3 failed 22% of live traffic. Raising
;;                          it costs nothing on the common path -- a run that is
;;                          done stops at 2 -- and the worst case is still bounded
;;                          by CLAUDE_BRIDGE_TIMEOUT.
;;   --tools ""             the GUEST runs no tools of its own. Caller-declared
;;                          tools come back as OpenAI tool_calls for the CALLER
;;                          to execute -- that is what /v1/chat/completions
;;                          means, and a guest that ran them would be an agent
;;                          running commands the caller never sanctioned.
;;                          --allowedTools "" does NOT do this: it is a PERMISSION
;;                          allowlist, not an availability list. With it alone the
;;                          init event still advertised all 25 built-in tools and
;;                          the guest really executed Bash (measured 2026-08-19:
;;                          "Run: echo hello-from-guest" came back with a
;;                          tool_result, not a refusal). --tools "" takes the
;;                          count to 0 and leaves --json-schema working, because
;;                          StructuredOutput is not drawn from the built-in set.
;;                          --allowedTools "" stays as a second, redundant floor.
;;   --json-schema          only when the caller declares tools: constrains the
;;                          reply to {content, tool_calls} so a call arrives as
;;                          data instead of prose we would have to parse out.
;;   cwd = a neutral dir    otherwise the CLI loads the CLAUDE.md of wherever
;;                          the server happened to start (ours is ~90 KB)
;;   --no-session-persistence / --strict-mcp-config / --disable-slash-commands
;;                          one HTTP request must not leave session state behind
;; Do NOT add CLAUDE_CODE_SIMPLE=1: in that mode the CLI reads only
;; ANTHROPIC_API_KEY and never the keychain — which is the whole point here.
;;
;; ## Tool use and vision (2026-08-19)
;;
;; This bridge used to be text-only by construction: it flattened every content
;; block to text (dropping images) and told the guest it had no tools. Both are
;; now supported, and both were verified against the real CLI before the code
;; was written:
;;
;;   vision   a 64x64 PNG generated in-process (magenta field, green stripe)
;;            was described correctly through --input-format stream-json. The
;;            image never existed on disk, so the answer could not be guessed.
;;   tools    --json-schema returns a parsed object on the result event
;;            (`structured_output`), and it composes with stream-json input --
;;            one request can carry an image AND come back with a tool call.
;;
;; The distinction that matters: the guest still runs NO tools. A caller's
;; tools come back as `tool_calls` for the caller to execute. Letting the guest
;; execute them would make this an agent, and an agent behind a completions
;; endpoint runs commands the caller never sanctioned.

(ns claude-bridge
  (:require ["node:http" :as http]
            ["node:child_process" :as cp]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:fs" :as fs]
            [clojure.string :as str]))

(def env (.-env js/process))
(defn env-int [k d] (let [v (aget env k)] (if (and v (seq v)) (js/parseInt v 10) d)))

(def port          (env-int "CLAUDE_BRIDGE_PORT" 9180))
(def max-inflight  (env-int "CLAUDE_BRIDGE_CONCURRENCY" 2))
(def child-timeout (* 1000 (env-int "CLAUDE_BRIDGE_TIMEOUT" 600)))
(def claude-bin    (or (aget env "CLAUDE_BRIDGE_BIN") "claude"))
(def workdir       (path/join (os/homedir) ".gftd" "claude-bridge-cwd"))

(def served-models
  ["claude-opus-5" "claude-sonnet-5" "claude-haiku-4-5" "opus" "sonnet" "haiku"])

(defn log [& xs]
  ;; Timestamp every line. A log without one cannot answer "is it failing now
  ;; or did it fail an hour ago" -- which is the only question anyone asks it.
  (.error js/console (str (.toISOString (js/Date.)) " [claude-bridge] "
                          (str/join " " xs))))

;; ── request shaping ──────────────────────────────────────────────────────
;; OpenAI content is either a string or a list of typed blocks. We keep BOTH
;; the text and the images: flattening to text is what made this bridge blind.

(defn resolve-model [m]
  (let [m (str/trim (or m ""))
        ;; callers label the route ("claude-code/sonnet", "claude-cli/opus")
        m (if-let [i (str/last-index-of m "/")] (subs m (inc i)) m)]
    (if (str/blank? m) "sonnet" m)))

(defn data-url->image [url]
  ;; data:image/png;base64,AAAA...  ->  an Anthropic base64 image source
  (when-let [m (re-find #"^data:([^;,]+);base64,(.*)$" (str url))]
    {:type "image" :source {:type "base64" :media_type (nth m 1) :data (nth m 2)}}))

(defn remote-image [url]
  (when (re-find #"^https?://" (str url))
    {:type "image" :source {:type "url" :url url}}))

(defn block->anthropic
  "One OpenAI content block as an Anthropic block, or nil if it carries nothing.

  Both OpenAI shapes are accepted: `image_url` (what the API documents) and a
  bare `image` with a base64 `source` (what several clients actually send)."
  [b]
  (cond
    (string? b) (when (seq b) {:type "text" :text b})
    (not (map? b)) nil
    (= "text" (:type b)) (when (seq (str (:text b))) {:type "text" :text (:text b)})
    (= "image_url" (:type b))
    (let [u (get-in b [:image_url :url] (:image_url b))]
      (or (data-url->image u) (remote-image u)))
    (= "image" (:type b))
    (if (map? (:source b))
      b                                   ; already Anthropic-shaped
      (or (data-url->image (:image_url b)) (remote-image (:url b))))
    :else nil))

(defn content->blocks [c]
  (cond
    (string? c) (if (seq c) [{:type "text" :text c}] [])
    (sequential? c) (vec (keep block->anthropic c))
    :else []))

(defn block-text [b]
  (cond (string? b) b
        (and (map? b) (= "text" (:type b))) (or (:text b) "")
        :else ""))

(defn content->text [c]
  (cond (string? c) c
        (sequential? c) (str/join "\n" (remove str/blank? (map block-text c)))
        :else ""))

(defn image-block? [b] (= "image" (:type b)))

;; ── the transcript ───────────────────────────────────────────────────────
;; `claude -p` takes ONE turn, so a conversation is rendered as a transcript.
;; Images are emitted as blocks AT THEIR POSITION rather than appended, so
;; "the second screenshot" still refers to the second one.

(defn tool-call->text [{:keys [function] :as call}]
  (str "  -> called " (or (:name function) (:name call))
       "(" (or (:arguments function) (:arguments call)) ")"))

(defn message->blocks
  "One chat message as a labelled run of Anthropic blocks."
  [{:keys [role content tool_calls tool_call_id name]}]
  (let [label (case role
                "tool" (str "[tool result" (when tool_call_id (str " for " tool_call_id))
                            (when name (str " (" name ")")) "]")
                (str "[" (or role "user") "]"))
        blocks (content->blocks content)
        calls (seq tool_calls)]
    (vec (concat [{:type "text" :text label}]
                 blocks
                 (when calls [{:type "text" :text (str/join "\n" (map tool-call->text calls))}])))))

(defn messages->content
  "The full user content for the single CLI turn.

  A lone user turn goes through verbatim (no `[user]` label to explain away);
  anything longer is labelled, because otherwise the guest cannot tell its own
  prior words from the caller's."
  [messages]
  (let [turns (vec (remove #(= "system" (:role %)) messages))]
    (if (and (= 1 (count turns)) (empty? (:tool_calls (first turns))))
      (content->blocks (:content (first turns)))
      (vec (concat (mapcat message->blocks turns)
                   [{:type "text" :text "[assistant]"}])))))

(defn messages->system [messages]
  (->> messages
       (filter #(= "system" (:role %)))
       (map #(content->text (:content %)))
       (remove str/blank?)
       (str/join "\n\n")))

;; ── caller-declared tools ────────────────────────────────────────────────
;; The guest does not RUN these. It names one, and the caller runs it. So the
;; schema constrains `name` to an enum: a call the caller cannot execute is
;; worse than no call, and an open string invites one.

(defn tool-specs [body]
  (vec (keep (fn [t]
               (let [f (or (:function t) t)]
                 (when (seq (str (:name f)))
                   {:name (:name f)
                    :description (or (:description f) "")
                    :parameters (or (:parameters f) {:type "object" :properties {}})})))
             (:tools body))))

(defn tool-choice [body]
  (let [c (:tool_choice body)]
    (cond (= "none" c) :none
          (= "required" c) :required
          (and (map? c) (get-in c [:function :name])) :required
          :else :auto)))

(defn tools->schema [specs]
  {:type "object"
   :additionalProperties false
   :properties
   {:content {:type "string"
              :description "Your reply to the user. Empty string when you are only calling tools."}
    :tool_calls
    {:type "array"
     :description "Tools for the CALLER to run. Empty array when you are answering directly."
     :items {:type "object"
             :additionalProperties false
             :required ["name" "arguments"]
             :properties {:name {:type "string" :enum (mapv :name specs)}
                          :arguments {:type "object"
                                      :description "Arguments object for that tool."}}}}}
   :required ["content" "tool_calls"]})

(defn tools-directive [specs choice]
  (str "You are answering through a bridge that returns your reply to a caller.\n\n"
       "You have NO tools of your own: you cannot run commands, read files, or "
       "fetch pages yourself.\n\n"
       "The CALLER has these tools and will run whichever you name:\n\n"
       (str/join "\n" (for [t specs]
                        (str "- " (:name t)
                             (when (seq (:description t)) (str " — " (:description t)))
                             "\n  parameters: " (js/JSON.stringify (clj->js (:parameters t))))))
       "\n\nReply as the required object. Put your answer in `content`. To use a "
       "tool, add an entry to `tool_calls` with its exact name and an `arguments` "
       "object matching its parameters. "
       (if (= :required choice)
         "You MUST name at least one tool this turn."
         "When you can answer directly, leave `tool_calls` empty — do not invent a call to fill it.")))

;; The no-tools case keeps its original directive: callers routinely ask for
;; shell commands, and without being told, the guest emits a tool_use, burns
;; the turn, and the run dies as error_max_turns with no text.
(def no-tools-directive
  ;; Wording matters: this used to say "text-only bridge", and with vision
  ;; working the guest repeated that back to callers as a limitation it no
  ;; longer has. Say what is actually true -- no tools THIS turn, images fine.
  (str "You are answering through a bridge. You can see any images in this "
       "conversation. You have NO tools this turn: you cannot run commands, "
       "read files, or fetch pages, and any tool call you attempt will fail. "
       "If the request asks you to run or read something, say what you would "
       "run and what you would look for, and answer from what is already in "
       "the conversation. Never emit a tool call."))

;; ── the subprocess ───────────────────────────────────────────────────────

(defn- stream-json-line [content]
  (str (js/JSON.stringify
         (clj->js {:type "user" :message {:role "user" :content content}}))
       "\n"))

(defn- parse-structured
  "The schema'd reply, as {:content .. :tool-calls [..]}.

  `structured_output` is the CLI's own parsed object; `result` is the same
  thing as a string. Prefer the parsed one, fall back to parsing, and if
  NEITHER yields the shape, say so rather than returning an empty answer that
  looks like the model declined to speak."
  [parsed]
  (let [so (:structured_output parsed)
        obj (or (when (map? so) so)
                (try (let [v (js->clj (js/JSON.parse (str (:result parsed)))
                                      :keywordize-keys true)]
                       (when (map? v) v))
                     (catch :default _ nil)))]
    (if (nil? obj)
      {:error (str "tools were requested, so the reply had to be a "
                   "{content, tool_calls} object, and it was not: "
                   (pr-str (subs (str (:result parsed)) 0
                                 (min 300 (count (str (:result parsed)))))))}
      {:text (or (:content obj) "")
       :tool-calls (vec (keep (fn [c]
                                (when (seq (str (:name c)))
                                  {:name (:name c) :arguments (or (:arguments c) {})}))
                              (:tool_calls obj)))})))

(defn spawn-claude
  "One CLI run. `content` is a vector of Anthropic blocks (text and images);
  `specs` is the caller's tool list, empty when there are none."
  [{:keys [model system content specs choice]} cb]
  (let [tools? (and (seq specs) (not= :none choice))
        sys (str/join "\n\n" (remove str/blank?
                                     [system (if tools?
                                               (tools-directive specs choice)
                                               no-tools-directive)]))
        args (cond-> ["-p" "--model" model
                      ;; stream-json in BOTH directions: it is the only input
                      ;; that carries image blocks. --verbose is not optional,
                      ;; the CLI refuses stream-json output without it.
                      "--input-format" "stream-json"
                      "--output-format" "stream-json"
                      "--verbose"
                      ;; 12, measured: the schema path puts the entire reply in
                      ;; the final StructuredOutput call, so hitting the cap first
                      ;; yields NO text and the max_turns recovery below cannot
                      ;; fire -- it 502s. One request shape needed 2, 4 and 5 turns
                      ;; across runs; at 3, 30 of 136 live responses died this way.
                      "--max-turns" "12"
                      ;; --tools "" removes the built-in tools; --allowedTools ""
                      ;; only declines to pre-approve them. Passing the allowlist
                      ;; alone left all 25 exposed and the guest ran Bash for real.
                      ;; Keep both: availability first, permission as a second floor.
                      "--tools" ""
                      "--allowedTools" ""
                      "--no-session-persistence"
                      "--strict-mcp-config"
                      "--disable-slash-commands"]
               (seq sys) (into ["--append-system-prompt" sys])
               tools? (into ["--json-schema"
                             (js/JSON.stringify (clj->js (tools->schema specs)))]))
        child (cp/spawn claude-bin (clj->js args)
                        #js {:cwd workdir :stdio "pipe"})
        out (atom "") err (atom "") done (atom false)
        finish (fn [res] (when-not @done (reset! done true) (cb res)))
        timer (js/setTimeout
                (fn [] (.kill child "SIGKILL")
                       (finish {:error (str "claude timed out after "
                                            (/ child-timeout 1000) "s")}))
                child-timeout)]
    (.on (.-stdout child) "data" #(swap! out str (.toString %)))
    (.on (.-stderr child) "data" #(swap! err str (.toString %)))
    (.on child "error"
         (fn [e] (js/clearTimeout timer)
                 (finish {:error (str "could not spawn '" claude-bin "': " (.-message e))})))
    (.on child "close"
      (fn [code]
        (js/clearTimeout timer)
        ;; stream-json output is one JSON object per line. The one that matters
        ;; is type=result; the rest are progress. A line that does not parse is
        ;; skipped rather than fatal -- but if NO result line arrives we say
        ;; that, because "no result" and "empty result" are different failures.
        (let [raw (str/trim @out)
              lines (remove str/blank? (str/split-lines raw))
              events (keep (fn [l] (try (js->clj (js/JSON.parse l) :keywordize-keys true)
                                        (catch :default _ nil)))
                           lines)
              parsed (last (filter #(= "result" (:type %)) events))]
          (cond
            (nil? parsed)
            (finish {:error (str "claude exited " code
                                 " with no result event in " (count lines)
                                 " line(s) of output: "
                                 (subs raw 0 (min 400 (count raw)))
                                 (when (seq @err) (str " | stderr: " (str/trim @err))))})

            ;; max_turns with text already produced is a truncation, not a
            ;; failure: returning 502 threw away an answer the model had
            ;; written. Only an empty result is a real error.
            (and (:is_error parsed)
                 (= "error_max_turns" (:subtype parsed))
                 (seq (str (:result parsed))))
            (finish (assoc (if tools? (parse-structured parsed)
                               {:text (:result parsed)})
                           :usage (:usage parsed)))

            (:is_error parsed)
            ;; Report what the CLI actually said. Reading only `result` gave
            ;; "(no message)" on every real failure and made 10 errors
            ;; undiagnosable -- the reason was in the fields we dropped.
            (finish {:error (str "claude reported an error"
                                 (when-let [st (:subtype parsed)] (str " subtype=" st))
                                 (when-let [tr (:terminal_reason parsed)] (str " terminal_reason=" tr))
                                 (when-let [as (:api_error_status parsed)] (str " api_error_status=" (pr-str as)))
                                 (when-let [r (:result parsed)] (str " result=" (pr-str r)))
                                 (when (seq @err) (str " stderr=" (pr-str (str/trim @err))))
                                 " | raw=" (subs raw 0 (min 600 (count raw))))})

            :else
            (let [r (if tools? (parse-structured parsed)
                        {:text (or (:result parsed) "")})]
              (finish (if (:error r) r (assoc r :usage (:usage parsed)))))))))
    (doto (.-stdin child) (.write (stream-json-line content)) (.end))))

;; ── admission: this machine already runs hot; don't fan out ──────────────
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

(defn usage->openai [u]
  ;; cache reads/writes are real input tokens against the plan, so they belong
  ;; in prompt_tokens — and total must be the sum of what we just reported,
  ;; not of the uncached half.
  (let [in (+ (or (:input_tokens u) 0)
              (or (:cache_read_input_tokens u) 0)
              (or (:cache_creation_input_tokens u) 0))
        out (or (:output_tokens u) 0)]
    {:prompt_tokens in :completion_tokens out :total_tokens (+ in out)}))

(defn tool-calls->openai [calls]
  (vec (map-indexed
         (fn [i c]
           {:id (str "call_" i "_" (.toString (js/Math.floor (* 1e9 (js/Math.random))) 36))
            :type "function"
            :function {:name (:name c)
                       ;; OpenAI carries arguments as a JSON STRING, not an
                       ;; object. Clients json.loads() this; handing them an
                       ;; object makes every one of them throw.
                       :arguments (js/JSON.stringify (clj->js (:arguments c)))}})
         calls)))

(defn send-completion [res id model {:keys [text usage tool-calls]}]
  (let [calls (seq tool-calls)]
    (send-json res 200
      {:id id :object "chat.completion" :created (now-secs) :model model
       :choices [{:index 0
                  :message (cond-> {:role "assistant" :content (if (seq text) text nil)}
                             calls (assoc :tool_calls (tool-calls->openai calls)))
                  :finish_reason (if calls "tool_calls" "stop")}]
       :usage (usage->openai usage)})))

;; `claude -p` answers once, so the stream is one chunk. That is still a valid
;; SSE stream to every OpenAI client, and it keeps the bridge honest: it does
;; not pretend to token-level streaming it cannot do.
(defn send-stream [res id model {:keys [text usage tool-calls]}]
  (.writeHead res 200 #js {"Content-Type" "text/event-stream"
                           "Cache-Control" "no-cache"
                           "Connection" "keep-alive"})
  (let [calls (seq tool-calls)
        chunk (fn [delta finish]
                (.write res (str "data: "
                                 (js/JSON.stringify
                                   (clj->js {:id id :object "chat.completion.chunk"
                                             :created (now-secs) :model model
                                             :choices [{:index 0 :delta delta
                                                        :finish_reason finish}]}))
                                 "\n\n")))]
    (chunk {:role "assistant" :content ""} nil)
    (when (seq text) (chunk {:content text} nil))
    ;; Streamed tool_calls carry an :index; clients accumulate by it.
    (when calls
      (chunk {:tool_calls (vec (map-indexed (fn [i c] (assoc c :index i))
                                            (tool-calls->openai calls)))}
             nil))
    (chunk {} (if calls "tool_calls" "stop"))
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
        content (messages->content (:messages body))
        system (messages->system (:messages body))
        specs (tool-specs body)
        choice (tool-choice body)
        id (req-id)
        stream? (true? (:stream body))
        images (count (filter image-block? content))
        t0 (.now js/Date)]
    (if (empty? content)
      (send-json res 400 {:error {:message "no user content in messages"
                                  :type "invalid_request_error"}})
      (admit!
        (fn []
          (log "->" model
               (str (count content) " block(s)")
               (str "images=" images)
               (str "tools=" (count specs) (when (= :none choice) "(none)"))
               (if stream? "stream" "sync")
               (str "inflight=" @inflight " queued=" (count @queued)))
          (spawn-claude {:model model :system system :content content
                         :specs specs :choice choice}
            (fn [result]
              (run-next!)
              (let [ms (- (.now js/Date) t0)]
                (if (:error result)
                  (do (log "<- ERROR" model (str ms "ms") (:error result))
                      (send-json res 502 {:error {:message (:error result)
                                                  :type "upstream_error"}}))
                  (do (log "<-" model (str ms "ms")
                           (str (count (str (:text result))) "B")
                           (str "calls=" (count (:tool-calls result))))
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
          (send-json res 200 {:status "ok" :backend claude-bin
                              :inflight @inflight :queued (count @queued)})

          (and (= method "GET") (str/ends-with? url "/models"))
          (send-json res 200
            {:object "list"
             :data (mapv (fn [m] {:id m :object "model" :created (now-secs)
                                  :owned_by "claude-code-cli"})
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
    (log "backend:" claude-bin "| cwd:" workdir
         "| max-inflight:" max-inflight "| timeout:" (/ child-timeout 1000) "s")))

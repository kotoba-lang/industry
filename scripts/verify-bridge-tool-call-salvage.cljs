#!/usr/bin/env nbb
;; verify-bridge-tool-call-salvage — the bridge must never tell a caller that
;; the caller's own tools are broken.
;;
;;   nbb scripts/verify-bridge-tool-call-salvage.cljs
;;
;; exit 0 = every case held, 1 = a case failed, 2 = could not measure.
;;
;; ## What went wrong (2026-08-31)
;;
;; `--tools ""` leaves the guest one tool, StructuredOutput. Told the CALLER
;; holds `terminal`, the guest emits a `tool_use` for `terminal` anyway and the
;; CLI answers `<tool_use_error>Error: No such tool available: terminal</...>`.
;; Sometimes it recovers and names the call; sometimes it gives up and writes
;; that error into `content` with `tool_calls` EMPTY. Measured live that day:
;;
;;   "Both `terminal` and `read_file` returned \"No such tool available\"
;;    errors ... This looks like a tool-access issue on the bridge side."
;;
;; Hermes bots relayed exactly that to their operator as a fleet-wide tool
;; outage. Nothing was wrong with hermes; the caller's tools were fine.
;;
;; ## Why a stub and not the live CLI
;;
;; The behaviour under test is one the real guest shows only SOMETIMES, so a
;; live probe cannot fail on demand -- and a check that cannot fail on demand
;; is the silence this file exists to break (CLAUDE.md, question 5). The stub
;; replays both a giving-up transcript and a healthy one through the REAL
;; bridge over REAL HTTP, so what is exercised is the shipped code path.
;;
;; ## The four cases, and why each is here
;;
;;   salvage        gave up after a refused `terminal` -> the caller still gets
;;                  the tool_call. This is the fix.
;;   healthy        named the call properly -> byte-identical to before. This
;;                  is the no-regression direction.
;;   recovered      refused once, then answered the question -> NO tool_call is
;;                  invented. Salvage that fires here would be its own bug.
;;   unrecoverable  the attempt named `Bash`, which the caller never declared,
;;                  and the reply is about the plumbing -> 502, not a relayed
;;                  lie. Proves salvage is scoped to declared names.

(ns verify-bridge-tool-call-salvage
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:net" :as net]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]))

(def ^:private stub-source
  "#!/usr/bin/env nbb
;; stub-claude — a fake `claude -p` for verify-bridge-tool-call-salvage.
;;
;; Real CLI runs cost quota and are nondeterministic about the very behaviour
;; under test (the guest reaches for the caller's tools SOMETIMES). A stub is
;; the only way to assert both directions of the salvage floor on demand.
;;
;; It ignores its argv and picks a canned stream-json transcript from a
;; `SCENARIO:<name>` marker in the stdin prompt, so one bridge process can be
;; driven through every case.

(ns stub-claude
  (:require [clojure.string :as str]))

(defn- line [m] (str (js/JSON.stringify (clj->js m)) \"\\n\"))

(defn- init [] (line {:type \"system\" :subtype \"init\" :tools [\"StructuredOutput\"]}))

(defn- attempt
  \"One rejected direct invocation: the guest reaches for a tool it does not hold.\"
  [id nm args]
  (str (line {:type \"assistant\"
              :message {:role \"assistant\"
                        :content [{:type \"tool_use\" :id id :name nm :input args}]}})
       (line {:type \"user\"
              :message {:role \"user\"
                        :content [{:type \"tool_result\" :tool_use_id id :is_error true
                                   :content (str \"<tool_use_error>Error: No such tool \"
                                                 \"available: \" nm \"</tool_use_error>\")}]}})))

(defn- result [obj]
  (line {:type \"result\" :subtype \"success\" :is_error false :num_turns 4
         :result (js/JSON.stringify (clj->js obj))
         :structured_output obj
         :usage {:input_tokens 100 :output_tokens 20}}))

(def ^:private gave-up
  (str \"Both `terminal` and `read_file` returned \\\"No such tool available\\\" \"
       \"errors, so I couldn't list the files. This looks like a tool-access \"
       \"issue on the bridge side.\"))

(def scenarios
  {;; the bug: the guest decided on `terminal`, was refused, and told the
   ;; caller its tools are broken instead of naming the call.
   \"salvage\"
   #(str (init)
         (attempt \"tu1\" \"terminal\" {:command \"ls -a /tmp/hermes-probe\"})
         (attempt \"tu2\" \"terminal\" {:command \"ls -a /tmp/hermes-probe\"})
         (result {:content gave-up :tool_calls []}))

   ;; the common path: the guest named the call properly. Salvage must be
   ;; invisible here.
   \"healthy\"
   #(str (init)
         (result {:content \"\" :tool_calls [{:name \"read_file\"
                                            :arguments {:path \"/etc/hosts\"}}]}))

   ;; the regression guard: refused once, then answered the question anyway.
   ;; Forcing the abandoned call would be its own bug.
   \"recovered\"
   #(str (init)
         (attempt \"tu1\" \"terminal\" {:command \"ls\"})
         (result {:content \"The capital of France is Paris.\" :tool_calls []}))

   ;; the CLI does not know the model: a caller failed over to this bridge
   ;; still carrying its PRIMARY provider's model name. The marker rides on
   ;; STDERR, which is why a status mapping reading only the result event
   ;; could not see it.
   \"unknown-model\"
   #(do (.write (.-stderr js/process)
                \"[claude-code:unrecognized_model] model=murakumo-main\")
        (str (init)
             (line {:type \"result\" :subtype \"success\" :is_error true
                    :terminal_reason \"api_error\" :api_error_status 404
                    :result \"There is an issue with the selected model (murakumo-main).\"})))

   ;; nothing recoverable -- the attempt named a tool the CALLER never
   ;; declared -- and the reply is about the plumbing. Must not be relayed.
   \"unrecoverable\"
   #(str (init)
         (attempt \"tu1\" \"Bash\" {:command \"ls\"})
         (result {:content gave-up :tool_calls []}))})

(defn- emit [in]
  (let [m (re-find #\"SCENARIO:([A-Za-z0-9_-]+)\" (str in))
        f (get scenarios (second m))]
    (.write (.-stdout js/process)
            (if f (f) (str (init) (result {:content (str \"no scenario in prompt: \"
                                                         (pr-str (second m)))
                                           :tool_calls []}))))))

(let [buf (atom \"\")]
  (.on (.-stdin js/process) \"data\" #(swap! buf str (.toString %)))
  (.on (.-stdin js/process) \"end\" #(emit @buf)))
")

(def ^:private bridge-path (path/join (.cwd js/process) "scripts" "claude-bridge.cljs"))

(defn- die [code msg]
  (.error js/console msg)
  (set! (.-exitCode js/process) code))

(defn- free-port []
  (js/Promise.
    (fn [resolve _]
      (let [srv (net/createServer)]
        (.listen srv 0 "127.0.0.1"
          (fn [] (let [p (.-port (.address srv))]
                   (.close srv (fn [] (resolve p))))))))))

(defn- sleep [ms] (js/Promise. (fn [r _] (js/setTimeout r ms))))

(defn- wait-healthy [port tries]
  (-> (js/fetch (str "http://127.0.0.1:" port "/health"))
      (.then (fn [r] (if (.-ok r) true (js/Promise.reject "not ok"))))
      (.catch (fn [_] (if (pos? tries)
                        (.then (sleep 300) #(wait-healthy port (dec tries)))
                        (js/Promise.reject "bridge never became healthy"))))))

(def ^:private tools
  [{:type "function"
    :function {:name "terminal" :description "Run a shell command."
               :parameters {:type "object"
                            :properties {:command {:type "string"}}
                            :required ["command"]}}}
   {:type "function"
    :function {:name "read_file" :description "Read a file."
               :parameters {:type "object"
                            :properties {:path {:type "string"}}
                            :required ["path"]}}}])

(defn- ask [port scenario]
  (if (= :health scenario)
    (-> (js/fetch (str "http://127.0.0.1:" port "/health"))
        (.then (fn [r] (.then (.json r) (fn [j] {:status (.-status r)
                                                 :body (js->clj j :keywordize-keys true)})))))
  (-> (js/fetch (str "http://127.0.0.1:" port "/v1/chat/completions")
        #js {:method "POST"
             :headers #js {"Content-Type" "application/json"}
             :body (js/JSON.stringify
                     ;; the unknown-model case must REQUEST the unserved model:
                     ;; a fixture where the caller asks for a served model and
                     ;; the CLI rejects a different one makes the bridge's own
                     ;; error text read as a lie about a model it does serve.
                     (clj->js {:model (if (= "unknown-model" scenario)
                                        "murakumo-main"
                                        "claude-sonnet-5")
                               :messages [{:role "user"
                                           :content (str "SCENARIO:" scenario
                                                         " list the files")}]
                               :tools tools
                               :max_tokens 512}))})
      (.then (fn [r] (.then (.json r) (fn [j] {:status (.-status r)
                                               :body (js->clj j :keywordize-keys true)})))))))

(defn- calls [resp]
  (get-in resp [:body :choices 0 :message :tool_calls]))

(defn- content [resp]
  (get-in resp [:body :choices 0 :message :content]))

(defn- arg-of [c k]
  (-> (get-in c [:function :arguments]) js/JSON.parse (js->clj :keywordize-keys true) k))

;; Each case returns nil when it holds, or the reason it did not.
(def ^:private cases
  [["salvage — a giving-up reply still yields the caller's tool_call"
    "salvage"
    (fn [r]
      (let [cs (calls r)]
        (cond
          (not= 200 (:status r)) (str "expected 200, got " (:status r) " " (pr-str (:body r)))
          (not= 1 (count cs)) (str "expected exactly 1 tool_call (deduped), got "
                                   (count cs) ": " (pr-str cs))
          (not= "terminal" (get-in (first cs) [:function :name]))
          (str "expected terminal, got " (pr-str (first cs)))
          (not= "ls -a /tmp/hermes-probe" (arg-of (first cs) :command))
          (str "arguments were not carried through: " (pr-str (first cs)))
          (str/includes? (str (content r)) "No such tool available")
          (str "the bridge's own error was relayed as the answer: " (pr-str (content r)))
          :else nil)))]

   ["healthy — a properly named call is untouched"
    "healthy"
    (fn [r]
      (let [cs (calls r)]
        (cond
          (not= 200 (:status r)) (str "expected 200, got " (:status r))
          (not= 1 (count cs)) (str "expected 1 tool_call, got " (count cs))
          (not= "read_file" (get-in (first cs) [:function :name]))
          (str "expected read_file, got " (pr-str (first cs)))
          (not= "/etc/hosts" (arg-of (first cs) :path))
          (str "arguments changed: " (pr-str (first cs)))
          :else nil)))]

   ["recovered — an abandoned attempt is NOT resurrected"
    "recovered"
    (fn [r]
      (cond
        (not= 200 (:status r)) (str "expected 200, got " (:status r))
        (seq (calls r)) (str "salvage fired on a reply that answered directly: "
                             (pr-str (calls r)))
        (not (str/includes? (str (content r)) "Paris"))
        (str "the answer was lost: " (pr-str (content r)))
        :else nil))]

   ["unrecoverable — a plumbing complaint is refused, not relayed"
    "unrecoverable"
    (fn [r]
      (cond
        (= 200 (:status r))
        (str "the lie was relayed to the caller as a normal answer: "
             (pr-str (content r)))
        (not= 502 (:status r)) (str "expected 502, got " (:status r))
        (not (str/includes? (str (get-in r [:body :error :message]))
                            "relayed this bridge's own"))
        (str "502 for some other reason: " (pr-str (:body r)))
        :else nil))]

   ["unknown-model — a model this bridge does not serve is 400, not a retryable 502"
    "unknown-model"
    (fn [r]
      (let [msg (str (get-in r [:body :error :message]))
            typ (str (get-in r [:body :error :type]))]
        (cond
          (= 502 (:status r))
          (str "still 502 upstream_error -- hermes reads that as transient and "
               "retries a request that can never succeed: " (subs msg 0 (min 200 (count msg))))
          (not= 400 (:status r)) (str "expected 400, got " (:status r))
          (not= "invalid_request_error" typ)
          (str "type is " (pr-str typ) " -- OpenAI clients route on this, and "
               "calling a bad request an upstream fault is what caused the retries")
          (not (str/includes? msg "murakumo-main"))
          (str "the message does not name the model that was refused: " msg)
          (not (str/includes? msg "claude-sonnet-5"))
          (str "the message does not say what this bridge DOES serve: " msg)
          :else nil)))]

   ;; Last on purpose: it reads counters the five above moved. Order is
   ;; guaranteed because the cases run sequentially.
   ["counters — the reach for tools it does not hold is countable, not just tailable"
    :health
    (fn [r]
      (let [st (get-in r [:body :tool_runs])]
        (cond
          (nil? st) "/health does not report tool_runs at all"
          ;; 5 tool-bearing requests that reached a verdict: 3 answered, 1
          ;; refused by the salvage floor, 1 rejected as an unserved model. A
          ;; verdict that went uncounted would hide a whole failure class from
          ;; the denominator.
          (not= 5 (:runs st)) (str "expected runs=5, got " (pr-str st))
          ;; salvage and recovered each reached for `terminal`; healthy reached
          ;; for nothing; unrecoverable reached for `Bash`, which the caller
          ;; never declared and which therefore must NOT count as a reach for
          ;; a caller tool.
          (not= 2 (:with-attempts st)) (str "expected with-attempts=2, got " (pr-str st))
          (not= 1 (:salvaged st)) (str "expected salvaged=1, got " (pr-str st))
          :else nil)))]
])

(defn- run []
  (let [tmp (fs/mkdtempSync (path/join (os/tmpdir) "bridge-salvage-"))
        stub (path/join tmp "stub-claude.cljs")]
    (fs/writeFileSync stub stub-source)
    (fs/chmodSync stub 493)   ; 0755; cljs has no octal literal
    (-> (free-port)
        (.then
          (fn [port]
            (let [child (cp/spawn "nbb" #js [bridge-path]
                          #js {:stdio "pipe"
                               :env (js/Object.assign
                                      #js {} (.-env js/process)
                                      #js {"CLAUDE_BRIDGE_PORT" (str port)
                                           "CLAUDE_BRIDGE_BIN" stub
                                           "CLAUDE_BRIDGE_TIMEOUT" "60"})})
                  err (atom "")]
              (.on (.-stderr child) "data" #(swap! err str (.toString %)))
              (-> (wait-healthy port 100)
                  (.then
                    (fn [_]
                      (.then
                        ;; sequential on purpose: the bridge admits 2 at a
                        ;; time and a shared stub makes interleaved failures
                        ;; hard to attribute.
                        (reduce
                          (fn [acc [title scenario check]]
                            (.then acc (fn [fails]
                                         (.then (ask port scenario)
                                           (fn [r]
                                             (if-let [why (check r)]
                                               (do (println "FAIL " title)
                                                   (println "      " why)
                                                   (conj fails title))
                                               (do (println "ok   " title)
                                                   fails)))))))
                          (js/Promise.resolve [])
                          cases)
                        (fn [fails]
                          (.kill child "SIGKILL")
                          (println (str "CASES\t" (count cases)))
                          (if (seq fails)
                            (die 1 (str "FAILED " (count fails) "/" (count cases)))
                            (println "all cases held"))))))
                  (.catch (fn [e]
                            (.kill child "SIGKILL")
                            (die 2 (str "REFUSED: " e
                                        (when (seq @err)
                                          (str " | bridge stderr: "
                                               (subs @err 0 (min 800 (count @err)))))))))))))
        (.catch (fn [e] (die 2 (str "REFUSED: " e)))))))

(run)

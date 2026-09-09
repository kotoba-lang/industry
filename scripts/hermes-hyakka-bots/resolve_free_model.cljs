#!/usr/bin/env nbb
(ns resolve-free-model
  "Pick the free model the hyakka growth bots run on, by measuring it.

  The bots used to run on `murakumo-main`, the fleet's public alias. That is
  free in the only sense that matters to a bill, but it spends fleet GPU time,
  and the owner asked for the bots to run on free models instead. This is what
  stands between that request and the failure ADR-2608271450 is about.

  Two things are true about the free tier at once. It carries models this
  workspace could not otherwise afford — a 550B and several 1M-context models
  were free on 2026-08-27. And it is the most volatile set of model ids that
  exists: `stealth/ox-alpha` was the most-used model on OpenRouter and served
  zero endpoints four days later. Writing one of those ids into a cron job
  reproduces the pinned-id bug exactly, one tier down.

  So nothing here names a model. The policy states a floor; OpenRouter is
  asked which models are free right now; and the survivors are PROBED rather
  than ranked, because the ranking proxies all lie. Sorting the free tier by
  context length on 2026-08-27 put `liquid/lfm-2.5-2.6b:free` above
  `nvidia/nemotron-3-ultra-550b-a55b:free` — 2.6B ahead of 550B — and a bot
  that draws the first one cannot fetch a URL, write an EDN proposal and open
  a PR.

  The probe asks for the three things a hyakka bot actually does:

    1. emit a well-formed tool call with exact arguments   (Hermes drives
       every action through tools; a model that cannot do this does nothing)
    2. obey an exact-output instruction                    (the prompts say
       `stop`, `land nothing`, `report and stop`)
    3. write parseable EDN                                 (the proposal file
       the gate reads is EDN, and a fence or a stray word makes it garbage)

  A candidate that fails 1 is not probed further. Highest score installs;
  context length breaks ties. Everything measured is written to a receipt,
  including the models that lost and why.

  Exit codes follow this family (ADR-2608271450 §3):

    0  a free model passed the probe and is installed as primary
    1  none passed — murakumo-main stays primary, and this says so out loud
    2  REFUSED: could not measure at all (no key, models API unreachable,
       unreadable policy, or a config carrying keys this script does not own)

  1 and 2 are different answers and must not collapse. `no free model works`
  is a measurement. `I could not find out` is not."
  (:require [cljs.reader :as edn]
            [cljs.pprint :as pp]
            [clojure.string :as str]
            [promesa.core :as p]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            ["node:child_process" :as cp]))

(def argv (vec (or *command-line-args* [])))

(defn arg [flag default]
  (let [i (.indexOf argv flag)]
    (if (neg? i) default (get argv (inc i) default))))

(defn flag? [f] (some? (some #{f} argv)))

;; nbb leaves `js/__filename` nil, and `process.argv[1]` is the nbb binary —
;; not this file. Both mistakes were made here: the first made the default
;; policy path depend on the caller's cwd and worked only because the first
;; runs happened from the repo root; the second made the run-as-script guard
;; below always false, so the whole script exited 0 having done nothing.
;; `process.argv[1]` is the nbb binary and `js/__filename` is nil, so the
;; entry script has to be found by scanning — argv[2] is `--classpath` the
;; moment anyone passes one. Both simpler readings were tried and both were
;; silently wrong: one made the default policy path depend on the caller's
;; cwd, the other made the run-as-script guard below always false, so the
;; script exited 0 having done nothing at all.
(def script-path
  (or (first (filter #(str/ends-with? (str %) ".cljs") (drop 2 (vec js/process.argv)))) ""))
(def here (if (str/ends-with? script-path ".cljs") (path/dirname script-path) "."))
(def policy-path (arg "--policy" (path/join here "free-model-policy.edn")))
(def config-path (arg "--config" (path/join (os/homedir) ".hermes" "config.yaml")))
(def receipt-path (arg "--receipt" (path/join (os/homedir) ".itonami" "hermes-free-model" "receipt.edn")))
(def hermes-bin (arg "--hermes" (path/join (os/homedir) ".hermes" "hermes-agent" "venv" "bin" "hermes")))
(def write? (flag? "--write"))
(def dry-list? (flag? "--list"))
(def check-config? (flag? "--check-config"))
(def if-stale? (flag? "--if-stale"))
(def job-ids (let [s (arg "--jobs" "")] (remove str/blank? (str/split s #","))))

;; Overridable so the unreachable-API refusal can actually be exercised.
(def or-base (arg "--api" "https://openrouter.ai/api/v1"))

;; Keys this script owns in ~/.hermes/config.yaml — the ones it renders from
;; scratch on every rewrite. Anything else at the top level is somebody else's
;; (Hermes writes `_config_version`, `agent` and `plugins` during a
;; config-format migration), and `carry-forward` copies those blocks through
;; the rewrite verbatim. This used to be a refusal; see `carry-forward` for
;; why a refusal was the wrong remedy for a hazard it named correctly.
(def managed-config-keys #{"model" "fallback_providers" "providers" "secrets" "auxiliary"
                           "custom_providers"
                            "openrouter"})

(defn die!
  "Exit immediately with `code`. Setting `exitCode` and throwing reports 1
  under nbb — the code this family reserves for `measured, and found
  something` — so a refusal has to call process.exit or it is
  indistinguishable from a finding."
  [code msg]
  (js/console.error msg)
  (.exit js/process code))

(defn refuse! [why]
  (die! 2 (str "REFUSED — the free-model question was not answered.\n" why
               "\n\nThe bots' model was left exactly as it was.")))

;; ------------------------------------------------------------------ policy

(def policy-error (atom nil))

(def policy
  ;; Reading this at require time would make merely loading the namespace
  ;; exit the process — which is what happened to the test suite the first
  ;; time. The error is remembered and raised by -main instead.
  (try (edn/read-string (fs/readFileSync policy-path "utf8"))
       (catch :default e
         (reset! policy-error (str "cannot read policy " policy-path ": " (.-message e)))
         {})))

(def min-context (or (:min-context policy) 131072))
(def require-tools? (not (false? (:require-tools policy))))
(def exclude-ids (set (:exclude-ids policy)))
(def probe-count (js/parseInt (arg "--probe-count" (str (or (:probe-count policy) 6))) 10))
(def fallback (:fallback policy))
(def prefer (vec (:prefer policy)))
(def auxiliary (:auxiliary policy))
(def max-age-days (or (:max-receipt-age-days policy) 7))
(def provider-name (or (:provider-name policy) "openrouter-free"))
(def key-env (or (:key-env policy) "OPENROUTER_API_KEY"))
(def key-read-cmd (:key-read-cmd policy))
;; nil leaves the provider block exactly as it was, so an absent policy key
;; hands the watchdog back to Hermes's implicit derivation rather than to 0.
(def stale-timeout-seconds (:stale-timeout-seconds policy))
;; nil emits no `openrouter:` block at all, which is Hermes's own default
;; (no response cache) rather than a cache with an unstated TTL.
(def response-cache (:response-cache policy))

;; Who OpenRouter credits for these calls. nil emits no header block, which
;; leaves Hermes's own attribution in place — and Hermes names ITSELF there
;; (`HTTP-Referer: https://hermes-agent.nousresearch.com`, `X-Title: Hermes
;; Agent`, `_OR_HEADERS_BASE` in agent/auxiliary_client.py), so an absent
;; policy key is not a neutral default. See :attribution in the policy.
(def attribution (:attribution policy))

(defn attribution-headers
  "The header pairs OpenRouter reads for app attribution, or nil.

  Three names for two facts. `HTTP-Referer` and `X-OpenRouter-Title` are what
  OpenRouter documents; `X-Title` is documented as also accepted and is the
  one Hermes's built-in default already occupies. Writing only the new name
  would leave `X-Title: Hermes Agent` on the wire beside it, and which of two
  conflicting titles OpenRouter believes is not a thing to assume — so the
  legacy name is overridden rather than left."
  []
  (when (map? attribution)
    (let [{:keys [referer title]} attribution]
      (seq (cond-> []
             (not (str/blank? referer)) (conj ["HTTP-Referer" referer])
             (not (str/blank? title))   (conj ["X-OpenRouter-Title" title]
                                              ["X-Title" title]))))))

(defn render-headers
  "An `extra_headers:` YAML block at `indent`, or nil when there is nothing
  to say. Values are quoted: a bare `https://...` is a mapping key away from
  being read as one by YAML."
  [indent]
  (when-let [hs (attribution-headers)]
    (str indent "extra_headers:\n"
         (apply str (for [[k v] hs] (str indent "  " k ": " (pr-str v) "\n"))))))
;; Owner override, admitted 2026-08-30. Not a preference — `:prefer` reorders
;; the probe queue and `:primary` replaces it, because the model the owner
;; chose is BILLED and so can never appear in `cands` (`candidate?` requires
;; zero-priced). See :primary in free-model-policy.edn for the measurement.
;;
;; This reintroduces exactly the hazard ADR-2608271450 is about — a model id
;; written down as the answer — so the one thing the resolver still owes is
;; to check that the id is real every run, and to say so out loud when it
;; stops being real instead of letting every tick 401 and exit 0.
(def primary (:primary policy))
(def displaced-primary (:displaced-primary policy))

(defn check-policy! []
  (when-let [e @policy-error] (refuse! e))
  (when-not (map? fallback)
    (refuse! (str policy-path " has no :fallback. Without a floor to fall back to, "
                  "installing a free model would be a one-way door."))))

;; ------------------------------------------------------------------- creds

(defn api-key
  "Env first, then the very command Hermes will run. Reading the key the same
  way Hermes does is the point: a probe that passes on a key Hermes cannot
  reach would install a model that 401s on the first cron fire, and that
  failure looks exactly like the one ADR-2608271450 is about."
  []
  (let [env (or (aget js/process.env "OPENROUTER_API_KEY")
                (aget js/process.env "OPENROUTER_KEY"))]
    (if-not (str/blank? env)
      {:key (str/trim env) :via key-env}
      (when-not (str/blank? key-read-cmd)
        (let [r (cp/spawnSync "/bin/sh" #js ["-c" key-read-cmd]
                              #js {:encoding "utf8" :timeout 30000})
              out (str/trim (str (or (.-stdout r) "")))]
          (when (and (zero? (or (.-status r) 1)) (not (str/blank? out)))
            {:key out :via (str "keychain: " key-read-cmd)}))))))

;; -------------------------------------------------------------------- http

(defn fetch-json [url opts]
  (-> (js/fetch url (clj->js (merge {:signal (js/AbortSignal.timeout 120000)} opts)))
      (p/then (fn [res]
                (p/let [t (.text res)]
                  {:status (.-status res) :text t})))
      (p/catch (fn [e] {:status 0 :text (str "network error: " (.-message e))}))))

(defn parse-json [s]
  (try (js->clj (js/JSON.parse s) :keywordize-keys true)
       (catch :default _ nil)))

;; -------------------------------------------------------------- candidates

(defn zero-priced? [m]
  (let [pr (:pricing m)
        n #(js/parseFloat (or % "1"))]
    (and (zero? (n (:prompt pr))) (zero? (n (:completion pr))))))

(defn zero-priced-id?
  "Whether an id names a free model, by OpenRouter's own `:free` suffix.

  Deliberately textual rather than a pricing lookup: this is asked while
  rendering config, where the models list is not in hand, and it has to
  agree with Hermes's `free_only` check — which is also a suffix test."
  [id]
  (str/ends-with? (str id) ":free"))

(defn text-out? [m]
  (let [outs (set (get-in m [:architecture :output_modalities] []))]
    (or (empty? outs) (contains? outs "text"))))

(defn candidate? [m]
  (let [sp (set (:supported_parameters m))]
    (and (zero-priced? m)
         (text-out? m)
         (not (contains? exclude-ids (:id m)))
         (>= (or (:context_length m) 0) min-context)
         (or (not require-tools?) (contains? sp "tools")))))

(defn order-candidates
  "Preference list first, in its own order, then everything else by context
  descending. The preference list only reorders the probe queue — every
  candidate still has to pass the same measurement."
  [cands]
  (let [by-id (into {} (map (juxt :id identity) cands))
        preferred (keep by-id prefer)
        rest' (->> cands
                   (remove #(contains? (set prefer) (:id %)))
                   (sort-by (juxt #(- (or (:context_length %) 0)) :id)))]
    (concat preferred rest')))

;; ------------------------------------------------------------------- probe

(defn chat
  "`reasoning {:exclude true}` is not a nicety. Without it a reasoning model
  prints its chain of thought into `content`, probe 2 reads that as a refusal
  to follow an exact-output instruction, and the run rejects a capable model
  for a reason the probe does not name. Hermes normalises this at runtime;
  the probe has to ask for the same shape or it is not measuring what Hermes
  will see."
  ([key body] (chat key body or-base))
  ([key body base]
   ;; No Authorization header when there is no key. The fleet admits an
   ;; unauthenticated caller; sending `Bearer null` would be admitted too, on
   ;; the same anonymous path, and would only make a failure harder to read.
   (fetch-json (str base "/chat/completions")
               {:method "POST"
                :headers (cond-> {"Content-Type" "application/json"
                                  "HTTP-Referer" "https://wiki.kotobase.net"
                                  "X-Title" "hyakka-growth-bots"}
                           key (assoc "Authorization" (str "Bearer " key)))
                :body (js/JSON.stringify (clj->js (assoc body :reasoning {:exclude true})))})))

(defn body-error
  "OpenRouter returns upstream failures INSIDE a 200 — `{\"error\": {...}}`
  with no choices, e.g. `Upstream error from Nvidia: Service temporarily
  overloaded`. Reading only the status code files that under `this model
  cannot call a tool`, which is a verdict about the model rather than about
  the afternoon."
  [resp]
  (let [j (parse-json (:text resp))]
    (or (get-in j [:error :message])
        (get-in j [:choices 0 :error :message]))))

(defn message-of [resp]
  (when (and (= 200 (:status resp)) (nil? (body-error resp)))
    (some-> (parse-json (:text resp)) :choices first :message)))

(defn unavailable?
  "429 / 403 / 5xx say something about the provider right now, not about the
  model's ability to do this job. Scoring them 0/3 would file `busy` and
  `cannot call a tool` under one verdict, and the receipt would then read as
  though the free tier had been measured and found wanting."
  [resp]
  (let [st (:status resp)]
    (cond
      ;; 400 / 422 reject the REQUEST — `tool_choice unsupported` is a fact
      ;; about the model, and filing it as `busy` would let an incapable
      ;; model keep its place in the queue forever.
      (or (= 400 st) (= 422 st)) false
      (zero? st) true
      (= 402 st) true          ; out of quota: an account fact, not a model fact
      (#{403 404 408 429} st) true
      (>= st 500) true
      ;; A 200 that carries an error object is the provider declining, not
      ;; the model answering badly.
      :else (some? (body-error resp)))))

(defn http-why [resp]
  (let [m (or (body-error resp) (:text resp))]
    (str "HTTP " (:status resp) " " (subs (str m) 0 (min 180 (count (str m)))))))

(defn probe-tools
  "1/3 — a well-formed tool call with the exact arguments asked for."
  ([key id] (probe-tools key id or-base))
  ([key id base]
   (p/let [resp (chat key
                 {:model id :max_tokens 400 :temperature 0
                 :tool_choice "required"
                 :tools [{:type "function"
                          :function {:name "record_source"
                                     :description "Record one ingest source."
                                     :parameters {:type "object"
                                                  :properties {:url {:type "string"}
                                                               :license {:type "string"}}
                                                  :required ["url" "license"]}}}]
                 :messages [{:role "user"
                             :content (str "Call record_source exactly once, with url "
                                           "https://example.org/a and license CC0-1.0. "
                                           "Do not call it more than once.")}]}
                 base)]
    (if-let [msg (message-of resp)]
      (let [calls (:tool_calls msg)
            c (first calls)
            fname (get-in c [:function :name])
            args (parse-json (or (get-in c [:function :arguments]) ""))]
        (cond
          (empty? calls) {:task :tools :pass false :why "no tool_calls in the reply"}
          (not= "record_source" fname) {:task :tools :pass false :why (str "called " fname)}
          (nil? args) {:task :tools :pass false :why "tool arguments were not JSON"}
          (not= "https://example.org/a" (:url args)) {:task :tools :pass false
                                                      :why (str "url was " (pr-str (:url args)))}
          (not= "CC0-1.0" (:license args)) {:task :tools :pass false
                                            :why (str "license was " (pr-str (:license args)))}
          :else {:task :tools :pass true}))
      (if (unavailable? resp)
        {:task :tools :pass false :unavailable true :why (http-why resp)}
        {:task :tools :pass false :why (http-why resp)})))))

(defn probe-exact
  "2/3 — obeys an exact-output instruction. The bot prompts say `stop`,
  `land nothing`, `report and stop`; a model that decorates its replies
  will decorate those too."
  ([key id] (probe-exact key id or-base))
  ([key id base]
   (p/let [resp (chat key {:model id :max_tokens 200 :temperature 0
                           :messages [{:role "user"
                                       :content "Reply with exactly the token HYAKKA-OK and nothing else."}]}
                          base)]
    (if-let [msg (message-of resp)]
      (let [c (str/trim (str (:content msg)))]
        (if (= "HYAKKA-OK" c)
          {:task :exact :pass true}
          {:task :exact :pass false :why (str "replied " (pr-str (subs c 0 (min 80 (count c)))))}))
      (if (unavailable? resp)
        {:task :exact :pass false :unavailable true :why (http-why resp)}
        {:task :exact :pass false :why (http-why resp)})))))

(defn strip-fence [s]
  (let [t (str/trim (str s))]
    (if (str/starts-with? t "```")
      (-> t (str/replace #"^```[a-zA-Z]*\n?" "") (str/replace #"```\s*$" "") str/trim)
      t)))

(defn probe-edn
  "3/3 — writes parseable EDN. The proposal file the gate reads is EDN, and
  a stray sentence in front of it makes the whole run unreadable."
  ([key id] (probe-edn key id or-base))
  ([key id base]
   (p/let [resp (chat key {:model id :max_tokens 300 :temperature 0
                          :messages [{:role "user"
                                      :content (str "Output only a Clojure EDN map with exactly two keys: "
                                                    ":id whose value is the string \"a\", and :ctx whose value "
                                                    "is the integer 7. No prose, no code fence, no explanation.")}]}
                          base)]
    (if-let [msg (message-of resp)]
      (let [raw (strip-fence (:content msg))
            v (try (edn/read-string raw) (catch :default _ ::unreadable))]
        (cond
          (= ::unreadable v) {:task :edn :pass false :why (str "unparseable: " (pr-str (subs raw 0 (min 80 (count raw)))))}
          (not= {:id "a" :ctx 7} v) {:task :edn :pass false :why (str "read as " (pr-str v))}
          :else {:task :edn :pass true}))
      (if (unavailable? resp)
        {:task :edn :pass false :unavailable true :why (http-why resp)}
        {:task :edn :pass false :why (http-why resp)})))))

(defn verdict
  "A score is only a verdict when every task got an answer. If the provider
  declined ANY of the three, the run learned nothing about this model and
  must not file a number — a 429 on task 2 arriving as `2/3` is the same
  mistake as a 429 on task 1 arriving as `0/3`, one level in."
  [m tasks]
  (let [unavail (some :unavailable tasks)]
    {:id (:id m) :context (:context_length m)
     :score (when-not unavail (count (filter :pass tasks)))
     :unavailable (boolean unavail)
     :tasks (vec tasks)}))

(def fleet-primary?
  "Is the pinned primary somewhere other than OpenRouter?

  `:base-url` is the tell. Its presence changes which guard is honest: the
  resolver's usual one asks whether the id is still on OpenRouter's model
  list, and for `murakumo-main` the answer is permanently no. Left in place it
  would `die! 1` every run; removed and not replaced, the primary would have
  no guard at all -- which is the shape ADR-2608271450 is about, one level up."
  (boolean (and primary (:base-url primary))))

(defn probe-context-floor
  "4/4 for a fleet primary — does the endpoint actually SERVE the context the
  policy requires?

  The other three probes send prompts under a hundred tokens, and on
  2026-09-09 all three passed against murakumo-main while the endpoint could
  not accept a Hermes turn at all. `/v1/models` advertises
  `context_window: 262144`; measured by bisection the same hour, ~3,016 prompt
  tokens were accepted and ~4,000 answered

    {\"error\": {\"type\": \"invalid_request_error\",
                \"message\": \"Input reserve exceeds this model's serving context window.\"}}

  Hermes reads that as a context signal, adopts it, compresses, and collapses
  to `Context length exceeded (32 tokens)` on a 32-token prompt. Every one of
  the 58 cron jobs would have failed that way, and the three capability probes
  would have gone on passing.

  So this asks for `:min-context` worth of input, which is the floor the rest
  of this policy is written against. An endpoint that advertises a window it
  will not serve fails here, and the primary is refused."
  [floor-tokens]
  ;; ~1 token per short word is close enough: the question is two orders of
  ;; magnitude, not a boundary.
  (let [filler (str/join " " (repeat floor-tokens "word"))]
    (p/let [resp (chat nil {:model (:model primary) :max_tokens 8 :temperature 0
                            :messages [{:role "user"
                                        :content (str filler " Reply with OK.")}]}
                       (:base-url primary))]
      (if (message-of resp)
        {:task :context :pass true}
        {:task :context :pass false
         :why (str "asked for " floor-tokens " input tokens: " (http-why resp))}))))

(defn probe-primary-endpoint
  "Run the three bot tasks against a non-OpenRouter primary.

  The same probes, against a different base URL and with no key: the fleet
  admits an unauthenticated caller, and sending a placeholder would not make
  it more authenticated -- it would only make the failure harder to read.

  This is a REPLACEMENT guard, not an addition. It answers the question the
  OpenRouter-list check answers for a listed model: is the thing the bots are
  about to be pointed at still able to do their work."
  []
  (p/let [r1 (probe-tools nil (:model primary) (:base-url primary))]
    (if-not (:pass r1)
      [r1]
      (p/let [r2 (probe-exact nil (:model primary) (:base-url primary))
              r3 (probe-edn nil (:model primary) (:base-url primary))
              r4 (probe-context-floor min-context)]
        [r1 r2 r3 r4]))))

(defn probe-fallback
  "Ask the floor whether it is there. `:fallback` is described in the policy
  as ours and unbilled, which is true, and as available, which on 2026-08-27
  it was not — 200 at 18:50, 400 {\"error\":\"Unauthorized\"} at 19:05, while
  its own alias endpoint went on reporting `status: serving`. A floor nobody
  stood on is a claim, not a floor."
  []
  (p/let [resp (fetch-json (str (:base-url fallback) "/chat/completions")
                           {:method "POST"
                            :headers {"Content-Type" "application/json"}
                            :body (js/JSON.stringify
                                    (clj->js {:model (:model fallback)
                                              :max_tokens 8
                                              :messages [{:role "user" :content "say PONG"}]}))})]
    {:reachable (some? (message-of resp))
     :why (when-not (message-of resp) (http-why resp))}))

(defn probe-model [key m]
  (p/let [r1 (probe-tools key (:id m))]
    (if-not (:pass r1)
      (verdict m [r1])
      (p/let [r2 (probe-exact key (:id m))
              r3 (probe-edn key (:id m))]
        (verdict m [r1 r2 r3])))))

(defn probe-seq
  "Walk the queue until `want` candidates have actually been MEASURED.

  Sequential on purpose: the free tier allows 20 requests per minute, and a
  parallel fan-out across candidates trips it and then reports capable models
  as broken. Candidates that come back unavailable do not count against
  `want` — a busy provider must not consume the budget that was meant to
  measure capability — but they do count against `attempts`, so a free tier
  that is entirely rate-limited terminates instead of walking all 15."
  [key ms want attempts-left]
  (if (or (empty? ms) (<= attempts-left 0))
    (p/resolved [])
    (p/let [r (probe-model key (first ms))
            _ (js/console.error (str "  " (:id (first ms)) " -> "
                                     (if (:unavailable r)
                                       (str "unavailable (" (:why (first (:tasks r))) ")")
                                       (str (:score r) "/3"
                                            (when (zero? (:score r))
                                              (str " (" (:why (first (:tasks r))) ")"))))))
            want' (if (:unavailable r) want (dec want))
            more (if (pos? want')
                   (probe-seq key (rest ms) want' (dec attempts-left))
                   (p/resolved []))]
      (into [r] more))))

;; ------------------------------------------------------------------ config

(defn top-level-keys [yaml]
  (->> (str/split-lines yaml)
       (keep #(second (re-find #"^([A-Za-z_][A-Za-z0-9_-]*):" %)))
       set))

(defn top-level-blocks
  "`yaml` split into [key text] pairs, one per top-level `key:` line. A block
  runs from its key line to the line before the next top-level key. Leading
  comments therefore attach to the block ABOVE them, which is safe here
  because owned blocks are re-rendered from scratch — only the text of
  unowned blocks is ever reused."
  [yaml]
  (let [lines (vec (str/split-lines yaml))
        starts (vec (keep-indexed
                      (fn [i l]
                        (when-let [k (second (re-find #"^([A-Za-z_][A-Za-z0-9_-]*):" l))]
                          [i k]))
                      lines))]
    (vec (for [[n [i k]] (map-indexed vector starts)
               :let [end (if-let [nxt (get starts (inc n))] (first nxt) (count lines))]]
           [k (str/replace (str/join "\n" (subvec lines i end)) #"\s+$" "")]))))

(defn carried-keys
  "Top-level keys present in `yaml` that this script does not own."
  [yaml]
  (sort (remove managed-config-keys (map first (top-level-blocks yaml)))))

(defn carry-forward
  "The verbatim text of every top-level block this script does not own, so a
  wholesale rewrite PRESERVES it instead of dropping it.

  This used to be a refusal, and the refusal was correct about the hazard and
  wrong about the remedy. Measured 2026-08-31: the v0.20.5 -> v0.20.6 config
  migration made Hermes itself write `_config_version`, `agent` and `plugins`
  into ~/.hermes/config.yaml, and from that moment every run exited 2 with
  `carries top-level keys this script does not own`. The daily model re-check
  stopped, which is exactly the silence ADR-2608271450 exists to prevent —
  and it would recur on every future Hermes config migration, because the set
  of keys Hermes owns is not ours to enumerate ahead of time.

  Nothing is dropped and nothing is interpreted: the block is copied out and
  appended after the managed sections. The keys are printed by the caller, so
  carrying is visible rather than silent."
  [yaml]
  (let [blocks (remove #(managed-config-keys (first %)) (top-level-blocks yaml))]
    (when (seq blocks)
      (str "\n"
           "# ── Carried forward verbatim (not written by this script) ─────────\n"
           "# Top-level keys this script does not own — Hermes writes these\n"
           "# during a config-format migration. Copied through the rewrite\n"
           "# unchanged and never interpreted here.\n"
           (str/join "\n\n" (map second blocks))
           "\n"))))


;; ---------------------------------------------------- per-profile configs
;;
;; A Hermes profile gets its OWN config.yaml and that file REPLACES this one
;; — `get_config_path()` is `get_hermes_home() / "config.yaml"` and a profile
;; moves HERMES_HOME to `~/.hermes/profiles/<n>/`. Nothing is layered over
;; the default config, so a setting written only there reaches exactly the
;; runs that name no profile.
;;
;; Measured 2026-08-31: 23 profiles existed, and every one of them still
;; resolved `HTTP-Referer: https://hermes-agent.nousresearch.com`. This
;; script had been treating ~/.hermes/config.yaml as "the one config all the
;; bots share" — which it is not, and the mistake is invisible from the
;; default profile, where everything reads correct.
;;
;; New profiles copy this config at creation (`profiles.py`: `source =
;; get_hermes_home() / "config.yaml"`), so they inherit whatever is here.
;; Only the ones that already exist have to be reached.

(defn- indent-of [l] (count (take-while #{" "} (map str l))))

(defn- find-line [lines pred]
  (first (keep-indexed (fn [i l] (when (pred l) i)) lines)))

(defn- section-end
  "One past the last line of the section headed at `i` — the first later
  line indented no deeper than the header."
  [lines i]
  (let [hi (indent-of (nth lines i))]
    (or (first (for [j (range (inc i) (count lines))
                     :let [l (nth lines j)]
                     :when (and (not (str/blank? l)) (<= (indent-of l) hi))]
                 j))
        (count lines))))

(defn- drop-subblock
  "Drop `key:` at exactly `indent` and every line below it that is deeper."
  [lines indent k]
  (let [hit (str (apply str (repeat indent " ")) k ":")]
    (loop [in (seq lines) out []]
      (if-let [l (first in)]
        (if (= l hit)
          (recur (seq (drop-while #(and (not (str/blank? %)) (> (indent-of %) indent))
                                  (rest in)))
                 out)
          (recur (next in) (conj out l)))
        out))))

(defn- upsert-headers
  "Replace the `extra_headers` block inside the section headed by the exact
  line `header` with `text`, appended at that section's end. Stripping first
  is what makes a second run a refresh rather than a duplicate key — and YAML
  takes the LAST of two duplicate keys, so a version that only appended would
  keep working while silently ignoring every earlier edit."
  [lines header text]
  (if-let [i (find-line lines #(= % header))]
    (let [e (section-end lines i)
          body (drop-subblock (subvec lines (inc i) e) (+ (indent-of header) 2) "extra_headers")]
      (vec (concat (subvec lines 0 (inc i)) body
                   (str/split-lines text)
                   (subvec lines e))))
    lines))

(defn patch-attribution
  "Put the attribution headers into a config this script did not render.

  Surgical rather than wholesale on purpose: a profile carries its own
  answers (`pr-cleanup` runs a different model), so re-rendering the file
  would replace real per-profile differences with this script's defaults.
  Returns `yaml` unchanged when the policy states no attribution, and when
  the file has neither section to put it in."
  [yaml]
  (if-let [text-4 (render-headers "    ")]
    (-> (vec (str/split-lines yaml))
        (upsert-headers (str "  " provider-name ":") text-4)
        (upsert-headers "model:" (render-headers "  "))
        (->> (str/join "\n"))
        (str "\n"))
    yaml))

(defn install-profile-configs!
  "Apply `patch-attribution` to every existing profile config. Reports one
  entry per profile, including the unchanged ones — `already right` and
  `never looked` must not read the same."
  [profiles-dir]
  (when (fs/existsSync profiles-dir)
    (vec (for [n (sort (vec (fs/readdirSync profiles-dir)))
               :let [f (path/join profiles-dir n "config.yaml")]
               :when (fs/existsSync f)
               :let [before (fs/readFileSync f "utf8")
                     after (patch-attribution before)]]
           (do (when (not= before after) (fs/writeFileSync f after))
               {:profile n :changed (not= before after)})))))

(defn render-config [model-id runners-up fallback-reachable]
  (str "# Managed by scripts/hermes-hyakka-bots/resolve_free_model.cljs.\n"
       "# Do not hand-edit the sections below: this file is rewritten whole.\n"
       "# Top-level keys the resolver does not own (Hermes writes some during\n"
       "# a config-format migration) are carried forward verbatim at the end.\n"
       "#\n"
       "# The model below was chosen by measurement, not by name — see\n"
       "# free-model-policy.edn and the receipt at\n"
       "# ~/.itonami/hermes-free-model/receipt.edn for what it beat and how.\n"
       "\n"
       "# The key stays in the login Keychain. This helper hands it to Hermes\n"
       "# once per process as a KEY=VALUE line, so nothing writes it to disk\n"
       "# here or in ~/.hermes/.env.\n"
       "#\n"
       "# NOT providers." provider-name ".key_cmd, which is the obvious fit and\n"
       "# the first thing tried: on hermes v0.20.5 the main turn works and every\n"
       "# auxiliary task then dies with\n"
       "#   'CommandTokenSource' object has no attribute 'strip'\n"
       "# Context compression is an auxiliary task and these bots run 35 minutes.\n"
       "secrets:\n"
       "  command:\n"
       "    enabled: true\n"
       "    command: " (pr-str (str "printf '" key-env "=%s\\n' \"$(" key-read-cmd ")\"")) "\n"
       "    helper_timeout_seconds: 10\n"
       "\n"
       "providers:\n"
       "  " provider-name ":\n"
       "    base_url: " or-base "\n"
       "    api_mode: chat_completions\n"
       "    key_env: " key-env "\n"
       (when stale-timeout-seconds
         (str "    # Floor for the non-streaming stale watchdog. Implicit, it is 90s,\n"
              "    # widening to 150s past 50k estimated tokens and 240s past 100k, and\n"
              "    # an implicit budget is also capped at half the remaining run budget\n"
              "    # — so it can only come down. Measured 2026-08-30: 19 of the 21\n"
              "    # recorded cron errors are this watchdog firing at exactly those\n"
              "    # three values. Set here, it is explicit: the scaling raises from it\n"
              "    # and the run-budget halving does not apply. See free-model-policy.edn.\n"
              "    stale_timeout_seconds: " stale-timeout-seconds "\n"))
       (when-let [h (render-headers "    ")]
         (str "    # Who OpenRouter credits these calls to — its app leaderboard\n"
              "    # and this account's activity read them off the request. Hermes\n"
              "    # ships its own values here and names ITSELF, so this is an\n"
              "    # override, not a blank being filled. See :attribution in\n"
              "    # free-model-policy.edn for why three names carry two facts.\n"
              "    #\n"
              "    # Matched by base_url, so the fallback entry into the same\n"
              "    # endpoint below inherits it. Applied last of all header\n"
              "    # sources on the main client, and it survives a credential\n"
              "    # swap (`apply_custom_provider_extra_headers_to_client_kwargs`).\n"
              h))
       ;; A fleet primary needs a provider block of its own: the `model:` block
       ;; below names a provider, and a name that is not in `providers:` is not
       ;; a route. The fallback entry further down carries its base_url inline,
       ;; which is enough for a fallback and not enough for a primary.
       (when fleet-primary?
         (str "  " (:provider primary) ":\n"
              "    base_url: " (:base-url primary) "\n"
              "    api_mode: chat_completions\n"
              "    # No key_env. api.murakumo.cloud admits an unauthenticated\n"
              "    # caller, and a placeholder would be admitted on the same\n"
              "    # anonymous path while making a failure harder to read.\n"))
       "\n"
       "model:\n"
       "  provider: " (if fleet-primary? (:provider primary) provider-name) "\n"
       "  default: " (if fleet-primary? (:model primary) model-id) "\n"
       ;; Explicit, because Hermes cannot look it up for this endpoint and the
       ;; value it falls back to is unusable -- measured 2026-09-09, a 32-token
       ;; prompt died with `Context length exceeded (32 tokens)`.
       (when (and fleet-primary? (:context-length primary))
         (str "  context_length: " (:context-length primary) "\n"))
       (when-let [h (render-headers "  ")]
         (str "  # The same headers again, one level up, because the block above\n"
              "  # does not reach every client Hermes builds. Auxiliary calls —\n"
              "  # context compression, session titles — construct their own\n"
              "  # OpenAI client and merge `model.extra_headers` (aliased from\n"
              "  # `model.default_headers`), never the per-provider block. Set\n"
              "  # only above, side-job traffic would still be credited to\n"
              "  # Hermes. This one is not route-scoped, so the fleet fallback\n"
              "  # sees it too; it is inert to an endpoint that reads no such\n"
              "  # header, and carries nothing secret.\n"
              h))
       "\n"
       (when (map? auxiliary)
         (str "# Side jobs — context compression, session titles. Hermes's own\n"
              "# default for these is google/gemini-3.6-flash, which is PAID, so\n"
              "# an OpenRouter key with no auxiliary block silently opens a billed\n"
              "# lane for background traffic on an account with no spend cap.\n"
              "#\n"
              "# Not pinned to a provider. Pinning them at the fleet was tried and\n"
              "# every session then emitted\n"
              "#   ⚠ Auxiliary title generation failed: HTTP 400 Unauthorized\n"
              "# for as long as the fleet was down — a pin is no more available\n"
              "# than what it points at. Unpinned, side jobs ride the main\n"
              "# provider and walk the chain below when it rate-limits.\n"
              "# free_only is the ceiling: never a billed model for background work.\n"
              "auxiliary:\n"
              "  free_only: " (if (:free-only auxiliary) "true" "false") "\n"
              ;; NOT model-id. When the primary is billed, writing it here
              ;; would pin context compression and title generation to the
              ;; billed model on the same line that declares free_only —
              ;; the config would state a ceiling and breach it. Side jobs
              ;; take the first free runner-up instead, and when there is
              ;; none the key is omitted so free_only alone decides.
              (let [aux-model (if (zero-priced-id? model-id)
                                model-id
                                (first runners-up))]
                (if aux-model
                  (str "  openrouter_model: " aux-model "\n")
                  "  # no free model on record for side jobs; free_only decides alone\n"))
              "\n"))
       (when (map? response-cache)
         (str "# OpenRouter response caching is separate from provider prompt caching.\n"
              "# Keep the TTL short: bot requests may include changing repository and\n"
              "# tool state, while an exact retry within the window should be reusable.\n"
              "openrouter:\n"
              "  response_cache: " (if (:enabled response-cache) "true" "false") "\n"
              "  response_cache_ttl: " (:ttl-seconds response-cache) "\n"
              "\n"))
       ;; `model.context_length` alone did not reach the agent: measured
       ;; 2026-09-09, with it set, `hermes -z "hi"` still died with
       ;; `Context length exceeded (19 tokens)` -- the number tracking the
       ;; prompt, which is what a limit of zero looks like. The supported
       ;; per-model override is this route-matched block
       ;; (hermes_cli/config_providers.py, get_custom_provider_context_length),
       ;; and it is checked before any probe.
       ;;
       ;; The endpoint is not at fault: /v1/models publishes
       ;; `context_window: 262144` for murakumo-main. Hermes reads
       ;; `context_length`, OpenRouter's field name, and finds nothing.
       (when (and fleet-primary? (:context-length primary))
         (str "custom_providers:\n"
              "  - name: " (:provider primary) "\n"
              "    base_url: " (:base-url primary) "\n"
              "    models:\n"
              "      " (:model primary) ":\n"
              "        context_length: " (:context-length primary) "\n"
              "\n"))
       "# Walked in order when the model above rate-limits or goes away.\n"
       "# Most of the free tier answers 429 on any given afternoon, so these\n"
       "# are models that each scored 3/3 on different providers, not a\n"
       "# second lane into the same pool.\n"
       "#\n"
       (if fleet-primary?
         (str "# The fleet is the PRIMARY above, not an entry here. It was probed\n"
              "# with the same three tasks this run, and the free chain below is\n"
              "# what the bots walk when it rate-limits or goes down.\n")
         (str "# The fleet is last and is not assumed up. Measured at install time:\n"
              "# " (if fallback-reachable "REACHABLE." "UNREACHABLE — see the receipt.") "\n"))
       "fallback_providers:\n"
       ;; The displaced primary first: it is the one entry here with a
       ;; multi-day failure record rather than a single probe, and on a run
       ;; where the free tier yields nothing it is the difference between a
       ;; chain and an empty list.
       (when (and fleet-primary? displaced-primary)
         (str "  - provider: " (:provider displaced-primary) "\n"
              "    model: " (:model displaced-primary) "\n"
              "    base_url: " or-base "\n"))
       (when (and fleet-primary? (empty? runners-up) (nil? displaced-primary))
         "  # no free model scored 3/3 this run and no displaced primary is\n  # recorded: the fleet has NOTHING under it. See free-model-policy.edn.\n")
       (apply str
         (for [m runners-up]
           (str "  - provider: " provider-name "\n"
                "    model: " m "\n"
                "    base_url: " or-base "\n")))
       ;; Not when it is already the primary: an entry that repeats the
       ;; primary is not a fallback, it is the same endpoint tried twice, and
       ;; it turns one outage into two identical failures before the chain
       ;; reaches a provider that could have answered.
       (when-not fleet-primary?
         (str "  - provider: " (:provider fallback) "\n"
              "    model: " (:model fallback) "\n"
              "    base_url: " (:base-url fallback) "\n"))))

(defn install-config! [model-id runners-up fallback-reachable]
  (let [prior   (when (fs/existsSync config-path) (fs/readFileSync config-path "utf8"))
        carried (when prior (carry-forward prior))
        keys'   (when prior (carried-keys prior))]
    (fs/mkdirSync (path/dirname config-path) #js {:recursive true})
    (fs/writeFileSync config-path
                      (str (render-config model-id runners-up fallback-reachable) carried))
    (let [profs (install-profile-configs! (path/join (path/dirname config-path) "profiles"))
          moved (filter :changed profs)]
      (str "wrote " config-path
           (when (seq keys')
             (str " (carried forward: " (str/join ", " keys') ")"))
           ;; Said out loud even when it is zero. A profile config that this
           ;; script never opened and one it opened and found already right
           ;; are different facts, and only one of them means the profiles
           ;; are covered.
           (when profs
             (str "; profiles " (count profs) " seen, " (count moved) " patched"
                  (when (seq moved)
                    (str ": " (str/join ", " (map :profile moved))))))))))

(defn install-jobs!
  "The cron jobs carry their own model/provider, which override config.yaml.
  Changing only the config would look like a change and alter nothing."
  [model-id]
  ;; The provider has to travel with the model. Writing `--provider
  ;; openrouter-free` beside `--model murakumo-main` names a route that does
  ;; not exist, and the job would fail on every tick while the config file
  ;; sitting next to it said the swap had happened.
  (let [prov (if fleet-primary? (:provider primary) provider-name)]
   (vec (for [id job-ids]
         (let [r (cp/spawnSync hermes-bin
                               #js ["cron" "edit" id "--model" model-id "--provider" prov]
                               #js {:encoding "utf8" :timeout 120000})]
           {:job id
            :ok (zero? (or (.-status r) 1))
            :out (str/trim (str (or (.-stdout r) "") (or (.-stderr r) "")))})))))

;; ---------------------------------------------------------------- receipt

(defn free-runners-up
  "The free models that last scored 3/3, read back from the receipt.

  A billed `:primary` still wants free models under it: the chain is walked
  when the primary rate-limits or goes away, and the whole point of measuring
  them was so that the fallback is a model known to do this work rather than
  the next id in a list. Empty when there is no receipt yet — an empty chain
  is honest, and `render-config` still writes the fleet entry beneath it."
  []
  (or (try (:free-fallbacks (edn/read-string (fs/readFileSync receipt-path "utf8")))
           (catch :default _ nil))
      []))

(defn prior-passes
  "3/3 verdicts from the last receipt, for models still on the free list.

  Most of the free tier answers 429 on any given afternoon — nine of fifteen
  in one run here. Discarding a model's verdict because it was busy today
  means the fallback chain shrinks exactly when the tier is under load, which
  is when it is needed. A verdict is kept for the same window the receipt is
  considered fresh for; past that the model is re-measured like any other."
  [listed]
  (let [r (try (edn/read-string (fs/readFileSync receipt-path "utf8"))
               (catch :default _ nil))
        age (when-let [t (:measured-at r)]
              (/ (- (.getTime (js/Date.)) (.getTime (js/Date. t))) 86400000.0))]
    (if (or (nil? r) (nil? age) (> age max-age-days))
      #{}
      (->> (:probed r)
           (filter #(= 3 (:score %)))
           (map :id)
           (filter listed)
           set))))

(defn write-receipt! [m]
  (fs/mkdirSync (path/dirname receipt-path) #js {:recursive true})
  (fs/writeFileSync receipt-path (with-out-str (pp/pprint m)))
  receipt-path)

;; ------------------------------------------------------------------- main

(defn -main []
  (check-policy!)
  ;; The config guard is the one path that can destroy something the
  ;; owner wrote, so it has to be reachable without a key and without a
  ;; live probe — otherwise it ships untested.
  (when check-config?
    (println (str "config\t" config-path))
    (println (str "owned\t" (str/join ", " (sort managed-config-keys))))
    (if (fs/existsSync config-path)
      (let [yaml (fs/readFileSync config-path "utf8")
            ks   (top-level-keys yaml)
            extra (carried-keys yaml)]
        (println (str "present\t" (str/join ", " (sort ks))))
        (println (str "carried\t" (if (seq extra) (str/join ", " extra) "(none)")))
        (println "verdict\tsafe to rewrite"))
      (println "verdict\tno config yet; one will be created"))
    (.exit js/process 0))
  (p/let [resp (fetch-json (str or-base "/models") {})]
    (when (not= 200 (:status resp))
      (refuse! (str "OpenRouter's model list did not answer: " (http-why resp)
                    "\nWithout it there is no way to know which models are free,\n"
                    "and guessing is the whole thing this script exists to avoid.")))
    (let [all (:data (parse-json (:text resp)))
          _ (when-not (seq all)
              (refuse! "OpenRouter returned an empty model list, which cannot be true."))
          cands (order-candidates (filter candidate? all))]
      (js/console.error (str "SCANNED\t" (count all) " models, " (count cands)
                             " free and over the floor (ctx>=" min-context
                             (when require-tools? ", tools") ")"))
      ;; A `:primary` in the policy short-circuits the probe entirely. The
      ;; measurement that justifies it is in the policy, not here, and it is
      ;; not a measurement this script can make: it compares a BILLED model
      ;; against the free tier on the bots' own failure record, which lives
      ;; in ~/.hermes/cron/usage_audit.jsonl and covers days rather than one
      ;; probe. What is checked here is the only thing that goes stale on its
      ;; own — whether the id still exists.
      ;;
      ;; `all`, not `cands`: `candidate?` requires zero-priced, so a billed
      ;; primary is never in `cands` and looking for it there would report
      ;; every healthy day as a disappearance.
      ;; A fleet primary takes the replacement guard: the three bot tasks
      ;; against its own endpoint. The OpenRouter-list check below cannot run
      ;; for it -- `murakumo-main` will never be on that list -- and leaving
      ;; it in would die! 1 on every healthy run, while removing it without a
      ;; substitute would leave the pinned primary with no guard at all. That
      ;; second shape is the one ADR-2608271450 is about.
      (when (and primary fleet-primary?)
        (p/let [rs (probe-primary-endpoint)]
          (let [failed (remove :pass rs)]
            (when (seq failed)
              (die! 1 (str "The pinned primary " (:model primary) " at " (:base-url primary)
                           " did not pass the bots' own probe:\n"
                           (str/join "\n" (for [f failed]
                                             (str "  " (name (:task f)) "\t" (:why f))))
                           "\n\nNothing was changed. The four tasks are the four things a bot does\n"
                           "on every turn: call a tool, obey an exact-output instruction, write\n"
                           "parseable EDN, and fit its prompt. Failing any one of them is a bot\n"
                           "that fails on its first turn, every tick, while exiting 0.\n\n"
                           "Either fix the endpoint, or drop :primary in "
                           (str policy-path) " to hand the choice back to the probe.")))
            (let [jobs (when write? (install-jobs! (:model primary)))
                  wrong (remove :ok jobs)
                  cfg-written (when write? (install-config! (:model primary) (free-runners-up) true))]
              (println (str "primary\t" (:model primary) "\t" (:base-url primary)
                            "\tprobed " (count rs) "/4 pass"))
              (when cfg-written (println (str "config\t" cfg-written)))
              (doseq [j jobs]
                (println (str "job\t" (:job j) "\t" (if (:ok j) "ok" (str "FAILED " (:out j))))))
              (when-not write?
                (println "dry-run\tpass --write to install"))
              (.exit js/process (if (seq wrong) 1 0))))))

      (when (and primary (not fleet-primary?))
        (let [pid (:model primary)
              m (some #(when (= pid (:id %)) %) all)]
          (when-not m
            (die! 1 (str "The pinned primary " pid " is no longer on OpenRouter's model list.\n"
                         "This is the failure ADR-2608271450 records, caught early: leave it\n"
                         "in place and every cron tick 401s while still exiting 0.\n\n"
                         "Nothing was changed. Either pick a successor and update :primary in\n"
                         (str policy-path) ", or drop :primary to hand the choice back to\n"
                         "the probe.")))
          (let [jobs (when write? (install-jobs! pid))
                wrong (remove :ok jobs)
                cfg-written (when write? (install-config! pid (free-runners-up) false))]
            (println (str "primary\t" pid "\tlisted, ctx=" (:context_length m)
                          ", prompt=$" (get-in m [:pricing :prompt])))
            (when cfg-written (println (str "config\t" cfg-written)))
            (doseq [j jobs]
              (println (str "job\t" (:job j) "\t" (if (:ok j) "ok" (str "FAILED " (:out j))))))
            (when-not write?
              (println "dry-run\tpass --write to install"))
            (.exit js/process (if (seq wrong) 1 0)))))

      ;; --if-stale: the cheap daily question. One keyless request has already
      ;; been spent above; if the installed model is still on the list and the
      ;; receipt is young, stop here rather than spending ~25 more to re-learn
      ;; the same answer — and rather than moving the bots to a different model
      ;; for no reason.
      (when if-stale?
        (let [r (try (edn/read-string (fs/readFileSync receipt-path "utf8"))
                     (catch :default _ nil))
              chosen (:chosen r)
              age-days (when-let [t (:measured-at r)]
                         (/ (- (.getTime (js/Date.)) (.getTime (js/Date. t))) 86400000.0))
              listed? (boolean (some #(= chosen (:id %)) cands))]
          (cond
            (nil? chosen)
            (js/console.error "stale: no previous choice on record; probing")
            (not listed?)
            (js/console.error (str "stale: " chosen " is no longer free or no longer clears the floor; probing"))
            (or (nil? age-days) (> age-days max-age-days))
            (js/console.error (str "stale: receipt is "
                                   (if age-days (str (.toFixed age-days 1) "d") "undated")
                                   " old (max " max-age-days "d); probing"))
            :else
            ;; Current — but "the model is still free" and "the bots are on it"
            ;; are different questions, and only the first one was ever asked
            ;; here. Measured 2026-08-28: the scheduled refresh ran, reported
            ;; `current`, exited 0, and left two agent jobs pointing at the
            ;; fleet, because writing the jobs only ever happened on the
            ;; install path. Reconciling costs no request and no probe.
            (let [jobs (when write? (install-jobs! chosen))
                  wrong (remove :ok jobs)
                  cfg (when (fs/existsSync config-path)
                        (second (re-find #"(?m)^  default: (\S+)$"
                                         (fs/readFileSync config-path "utf8"))))]
              (println (str "current\t" chosen "\tstill free, receipt "
                            (.toFixed age-days 1) "d old"))
              (doseq [j jobs]
                (println (str "job\t" (:job j) "\t" (if (:ok j) "ok" (str "FAILED " (:out j))))))
              (when (and cfg (not= cfg chosen))
                (println (str "drift\t" config-path " says " cfg
                              ", the receipt says " chosen)))
              (.exit js/process (if (seq wrong) 1 0))))))

      (when (empty? cands)
        (die! 1 (str "No free model currently clears the floor.\n"
                     "murakumo-main stays primary. This is a measurement, not an error —\n"
                     "the free tier moves, and today it has nothing this workload can use.")))
      (when dry-list?
        (doseq [m (take 20 cands)]
          (println (str (:id m) "\tctx=" (:context_length m))))
        (.exit js/process 0))

      (let [{:keys [key via]} (api-key)]
        (when-not key
          (refuse! (str "No OpenRouter credential.\n"
                        "  tried: $" key-env ", then " (pr-str key-read-cmd) "\n"
                        "\n" (count cands) " free models clear the floor, but whether any of them\n"
                        "can actually do this job is unmeasured — and unmeasured is not the\n"
                        "same answer as none.\n\n"
                        "  security add-generic-password -U -s gftd.openrouter -a \"$USER\" -w <key>\n")))
        (js/console.error (str "key via " via))
        (js/console.error (str "probing by context, until " probe-count " have been measured:"))
        (p/let [scored (probe-seq key cands probe-count (* 2 probe-count))]
          (let [incumbent (try (:chosen (edn/read-string (fs/readFileSync receipt-path "utf8")))
                               (catch :default _ nil))
                perfect (filter #(= 3 (:score %)) scored)
                ;; Prefer the model already installed when it still scores 3/3.
                ;; Which free model is reachable at any moment is mostly a
                ;; question of who is rate-limited, so ranking purely on this
                ;; run's results moved the bots between three different models
                ;; in three consecutive runs — churn that measured nothing.
                best (or (first (filter #(= incumbent (:id %)) perfect))
                         (first (sort-by (juxt #(- (or (:score %) 0))
                                               #(- (or (:context %) 0)))
                                         perfect)))
                receipt {:measured-at (.toISOString (js/Date.))
                         :models-listed (count all)
                         :candidates (count cands)
                         :floor {:min-context min-context :require-tools require-tools?}
                         :probed scored
                         :measured (count (remove :unavailable scored))
                         :unavailable (mapv :id (filter :unavailable scored))
                         :chosen (:id best)
                         :fallback fallback
                         :installed (boolean (and best write?))
                         :provider provider-name}]
            (if-not best
              (let [measured (remove :unavailable scored)
                    unavail (filter :unavailable scored)]
                (write-receipt! (assoc receipt :chosen nil))
                (die! 1 (str "No free model passed all three probes.\n"
                             "measured " (count measured) ":\n"
                             (str/join "\n" (for [s measured]
                                               (str "  " (:id s) "  " (:score s) "/3  "
                                                    (str/join "; " (keep :why (:tasks s))))))
                             (when (seq unavail)
                               (str "\nunavailable " (count unavail)
                                    " (not a verdict on the model):\n"
                                    (str/join "\n" (for [s unavail]
                                                     (str "  " (:id s) "  " (:why (first (:tasks s))))))))
                             "\n\nmurakumo-main stays primary. Receipt: " receipt-path)))
              (p/let [listed (set (map :id cands))
                      ctx-of (into {} (map (juxt :id :context_length) cands))
                      remembered (prior-passes listed)
                      passed (into (set (map :id (filter #(= 3 (:score %)) scored)))
                                   remembered)
                      runners-up (->> (disj passed (:id best))
                                      (sort-by #(- (or (ctx-of %) 0)))
                                      vec)
                      floor (probe-fallback)
                      jobs (when write? (install-jobs! (:id best)))
                      cfg (when write? (install-config! (:id best) runners-up (:reachable floor)))
                      receipt (assoc receipt :jobs jobs
                                     :free-fallbacks runners-up
                                     :fallback-reachable (:reachable floor)
                                     :fallback-why (:why floor)
                                     :carried-over (vec (sort (filter remembered runners-up))))]
                (write-receipt! receipt)
                (println (str "chosen\t" (:id best) "\t3/3\tctx=" (:context best)))
                (doseq [m runners-up]
                  (println (str "fallback\t" m "\tfree, 3/3"
                                (when (and (remembered m)
                                           (not (some #(and (= (:id %) m) (= 3 (:score %))) scored)))
                                  " (carried over — busy this run)"))))
                (println (str "fallback\t" (:model fallback) "\t"
                              (if (:reachable floor)
                                "the fleet, measured REACHABLE"
                                (str "the fleet, measured UNREACHABLE: " (:why floor)))))
                (doseq [s scored :when (not= (:id s) (:id best))]
                  (println (str (if (:unavailable s) "unavailable\t" "beat\t")
                                (:id s) "\t"
                                (if (:unavailable s) "-" (str (:score s) "/3")) "\t"
                                (str/join "; " (keep :why (:tasks s))))))
                (println (str "receipt\t" receipt-path))
                (if write?
                  (do (println (str "installed\t" cfg))
                      (doseq [j jobs]
                        (println (str "job\t" (:job j) "\t" (if (:ok j) "ok" (str "FAILED " (:out j)))))) 
                      (when (some (complement :ok) jobs)
                        (die! 1 "at least one cron job did not take the new model")))
                  (println "installed\tno (--write not given)"))))))))))

;; Run only when invoked as the script. Required as a namespace — which is how
;; `resolve_free_model_test.cljs` gets at `unavailable?` and `body-error` — it
;; must define and not act. A test that re-implements the classifier instead
;; would stay green after the classifier was deleted.
(when (str/ends-with? script-path "resolve_free_model.cljs")
  (-main))

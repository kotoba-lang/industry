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
(def receipt-path (arg "--receipt" (path/join (os/homedir) ".gftd" "hermes-free-model" "receipt.edn")))
(def hermes-bin (arg "--hermes" (path/join (os/homedir) ".hermes" "hermes-agent" "venv" "bin" "hermes")))
(def write? (flag? "--write"))
(def dry-list? (flag? "--list"))
(def check-config? (flag? "--check-config"))
(def if-stale? (flag? "--if-stale"))
(def job-ids (let [s (arg "--jobs" "")] (remove str/blank? (str/split s #","))))

;; Overridable so the unreachable-API refusal can actually be exercised.
(def or-base (arg "--api" "https://openrouter.ai/api/v1"))

;; Keys this script owns in ~/.hermes/config.yaml. Anything else at the top
;; level means somebody configured Hermes by hand and a wholesale rewrite
;; would silently drop it, so we refuse instead of clobbering.
(def managed-config-keys #{"model" "fallback_providers" "providers" "secrets" "auxiliary"})

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
  [key body]
  (fetch-json (str or-base "/chat/completions")
              {:method "POST"
               :headers {"Authorization" (str "Bearer " key)
                         "Content-Type" "application/json"
                         "HTTP-Referer" "https://wiki.kotobase.net"
                         "X-Title" "hyakka-growth-bots"}
               :body (js/JSON.stringify (clj->js (assoc body :reasoning {:exclude true})))}))

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
  [key id]
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
                                           "Do not call it more than once.")}]})]
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
        {:task :tools :pass false :why (http-why resp)}))))

(defn probe-exact
  "2/3 — obeys an exact-output instruction. The bot prompts say `stop`,
  `land nothing`, `report and stop`; a model that decorates its replies
  will decorate those too."
  [key id]
  (p/let [resp (chat key {:model id :max_tokens 200 :temperature 0
                          :messages [{:role "user"
                                      :content "Reply with exactly the token HYAKKA-OK and nothing else."}]})]
    (if-let [msg (message-of resp)]
      (let [c (str/trim (str (:content msg)))]
        (if (= "HYAKKA-OK" c)
          {:task :exact :pass true}
          {:task :exact :pass false :why (str "replied " (pr-str (subs c 0 (min 80 (count c)))))}))
      (if (unavailable? resp)
        {:task :exact :pass false :unavailable true :why (http-why resp)}
        {:task :exact :pass false :why (http-why resp)}))))

(defn strip-fence [s]
  (let [t (str/trim (str s))]
    (if (str/starts-with? t "```")
      (-> t (str/replace #"^```[a-zA-Z]*\n?" "") (str/replace #"```\s*$" "") str/trim)
      t)))

(defn probe-edn
  "3/3 — writes parseable EDN. The proposal file the gate reads is EDN, and
  a stray sentence in front of it makes the whole run unreadable."
  [key id]
  (p/let [resp (chat key {:model id :max_tokens 300 :temperature 0
                          :messages [{:role "user"
                                      :content (str "Output only a Clojure EDN map with exactly two keys: "
                                                    ":id whose value is the string \"a\", and :ctx whose value "
                                                    "is the integer 7. No prose, no code fence, no explanation.")}]})]
    (if-let [msg (message-of resp)]
      (let [raw (strip-fence (:content msg))
            v (try (edn/read-string raw) (catch :default _ ::unreadable))]
        (cond
          (= ::unreadable v) {:task :edn :pass false :why (str "unparseable: " (pr-str (subs raw 0 (min 80 (count raw)))))}
          (not= {:id "a" :ctx 7} v) {:task :edn :pass false :why (str "read as " (pr-str v))}
          :else {:task :edn :pass true}))
      (if (unavailable? resp)
        {:task :edn :pass false :unavailable true :why (http-why resp)}
        {:task :edn :pass false :why (http-why resp)}))))

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

(defn render-config [model-id runners-up fallback-reachable]
  (str "# Managed by scripts/hermes-hyakka-bots/resolve_free_model.cljs.\n"
       "# Do not hand-edit: this file is rewritten whole, and the resolver\n"
       "# refuses to touch it if it finds a top-level key it does not own.\n"
       "#\n"
       "# The model below was chosen by measurement, not by name — see\n"
       "# free-model-policy.edn and the receipt at\n"
       "# ~/.gftd/hermes-free-model/receipt.edn for what it beat and how.\n"
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
       "\n"
       "model:\n"
       "  provider: " provider-name "\n"
       "  default: " model-id "\n"
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
              "  openrouter_model: " model-id "\n"
              "\n"))
       "# Walked in order when the model above rate-limits or goes away.\n"
       "# Most of the free tier answers 429 on any given afternoon, so the\n"
       "# entries above the fleet are the OTHER models that scored 3/3 — each a\n"
       "# different provider, not a second lane into the same pool.\n"
       "#\n"
       "# The fleet is last and is not assumed up. Measured at install time:\n"
       "# " (if fallback-reachable "REACHABLE." "UNREACHABLE — see the receipt.") "\n"
       "fallback_providers:\n"
       (apply str
         (for [m runners-up]
           (str "  - provider: " provider-name "\n"
                "    model: " m "\n"
                "    base_url: " or-base "\n")))
       "  - provider: " (:provider fallback) "\n"
       "    model: " (:model fallback) "\n"
       "    base_url: " (:base-url fallback) "\n"))

(defn install-config! [model-id runners-up fallback-reachable]
  (when (fs/existsSync config-path)
    (let [extra (remove managed-config-keys (top-level-keys (fs/readFileSync config-path "utf8")))]
      (when (seq extra)
        (refuse! (str config-path " carries top-level keys this script does not own: "
                      (str/join ", " (sort extra))
                      "\nRewriting it whole would drop them. Move them into the managed\n"
                      "set in resolve_free_model.cljs, or install by hand.")))))
  (fs/mkdirSync (path/dirname config-path) #js {:recursive true})
  (fs/writeFileSync config-path (render-config model-id runners-up fallback-reachable))
  (str "wrote " config-path))

(defn install-jobs!
  "The cron jobs carry their own model/provider, which override config.yaml.
  Changing only the config would look like a change and alter nothing."
  [model-id]
  (vec (for [id job-ids]
         (let [r (cp/spawnSync hermes-bin
                               #js ["cron" "edit" id "--model" model-id "--provider" provider-name]
                               #js {:encoding "utf8" :timeout 120000})]
           {:job id
            :ok (zero? (or (.-status r) 1))
            :out (str/trim (str (or (.-stdout r) "") (or (.-stderr r) "")))}))))

;; ---------------------------------------------------------------- receipt

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
      (let [ks (top-level-keys (fs/readFileSync config-path "utf8"))
            extra (remove managed-config-keys ks)]
        (println (str "present\t" (str/join ", " (sort ks))))
        (when (seq extra)
          (refuse! (str config-path " carries top-level keys this script does not own: "
                        (str/join ", " (sort extra))
                        "\nRewriting it whole would drop them.")))
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

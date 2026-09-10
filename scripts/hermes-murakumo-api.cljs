;; scripts/hermes-murakumo-api.cljs — point the Hermes profile bot fleet at
;; api.murakumo.cloud, and hold it at a shape the endpoint can actually serve.
;;
;;   nbb scripts/hermes-murakumo-api.cljs              # report; writes nothing
;;   nbb scripts/hermes-murakumo-api.cljs --apply      # write ~/.hermes
;;   nbb scripts/hermes-murakumo-api.cljs --verify     # conformance + live probe
;;   nbb scripts/hermes-murakumo-api.cljs --findings   # detector protocol
;;   nbb scripts/hermes-murakumo-api.cljs --self-test
;;
;; Exit codes: 0 conformant / 1 findings / 2 COULD-NOT-MEASURE (never a pass).
;;
;; ## Why this exists
;;
;; Measured 2026-09-10. 234 profiles under ~/.hermes, 236 enabled cron jobs,
;; 770 scheduled runs/day. 227 of 231 profile configs named
;; `z-ai/glm-5.3-flash` on OpenRouter; the OpenRouter account had $10.00 left
;; of $2,386, and 77 of the 98 failing jobs carried the same last_error --
;; "This request requires more credits, or fewer max_tokens". The fleet was
;; not misconfigured. It had run out of money.
;;
;; ## What the endpoint actually serves (measured, not read off a config)
;;
;; `POST https://api.murakumo.cloud/v1/chat/completions` answers anonymously
;; for FLEET-HOSTED models only. Every proxied name 402s:
;;
;;   z-ai/glm-5.3-flash        402 external_model_requires_payment
;;   anthropic/claude-*        402 external_model_requires_payment
;;   qwen/qwen3.8-flash        402 external_model_requires_payment
;;   awai-network/basho        402 self_model_requires_identity
;;
;; and each 402 body names `hosted_alternative: murakumo-main`. So 24
;; profiles already pointing at api.murakumo.cloud were pointing at it with
;; a model it will not serve them -- a cutover that had been half-done for
;; long enough to look finished.
;;
;;   murakumo-main                    200   (alias -> qwen3.8-27b-throughput-b70)
;;   qwen3.8-27b-throughput-b70       200
;;   qwen3.8-27b-fastmtp-aggressive   200   but /v1/models says is_ready false
;;   qwen3.8-27b-throughput           502   murakumo fleet unreachable
;;   qwen3.8-27b-throughput-5090      502   murakumo fleet unreachable
;;   murakumo-edge                    timeout at 120s
;;
;; ADR-2607173100 says name the ALIAS and let KV resolve it, so the contract
;; below pins `murakumo-main` and not the head it happens to resolve to today.
;;
;; ## Why the numbers below are these numbers
;;
;; `max_tokens 2048`, not the 16384 the profiles carried. /ready reports
;; `request-capacity {configured 2, busy N}` for the WHOLE fleet and the
;; measured decode rate is 13-17 tok/s. 16384 tokens is up to 18 minutes of
;; one of two slots; 2048 is ~140s. Nothing else in this file matters as
;; much: a 429 storm is mostly one long request holding a slot.
;;
;; `context_length 65536`, and this number is NOT a free choice -- it is a
;; floor imposed from the other side. Hermes refuses to start an agent whose
;; window is under 64,000 (agent/model_metadata.py MINIMUM_CONTEXT_LENGTH,
;; enforced in agent_init.py::_enforce_minimum_context, waived only for
;; lmstudio) because its own system prompt plus tool schemas are a large
;; fixed prefix. The first cutover set 16384 -- b70's live slot-context --
;; and every agent job then died before its first turn with
;;
;;   ValueError: Model murakumo-main has a context window of 16,384 tokens,
;;   which is below the minimum 64,000 required by Hermes Agent.
;;
;; That was found by RUNNING a job, not by reading the file back: the
;; rehearsal on a copied ~/.hermes produced byte-correct YAML for a value
;; the agent rejects at startup.
;;
;; So 65536 is the smallest value Hermes will accept. It is honest about
;; what the fleet CAN serve only while a >=64K head is up. Measured
;; 2026-09-10: capacity-members are gad 262144, b70 16384, xavier 8192, and
;; /ready reported `pool-available {gad 0, b70 2, xavier 1}` with `degraded
;; ["fleet-incomplete" "primary-unavailable"]` -- i.e. the ONLY head that
;; clears Hermes's floor was down. While that holds, a request whose prompt
;; passes b70's window comes back
;;
;;   HTTP 400 "Input reserve exceeds this model's serving context window."
;;
;; measured, fast, and NOT a failover reason in hermes
;; (agent_runtime_helpers.py maps 402/429/401, not 400), so it is a hard
;; error rather than a walk down the fallback chain. Do not read the 65536
;; here as a claim that 64K is available; it is the floor Hermes demands,
;; and `--verify` measures whether any head actually clears it.
;;
;; `cron.max_parallel_jobs 6`, where the default is UNBOUNDED. Measured from
;; 19,033 execution rows: median run 133s, mean 398s. 770 runs/day x 398s =
;; 3.55 job-seconds per second, i.e. the pool was already averaging ~3.5
;; concurrent jobs with nothing capping it. 6 caps the burst at under 2x the
;; measured mean and leaves the tick ~59% utilized. A job is only part-time
;; LLM-bound, so this lands expected concurrent inference near the 2-3 slots
;; the endpoint has. Resolution order in hermes is env > config > unbounded
;; (cron/scheduler.py::_resolve_max_parallel_workers) and the pool is
;; module-global across every multiplexed profile, so this cap is GLOBAL.
;;
;; ## What the endpoint could NOT do, measured 2026-09-10
;;
;; The cutover landed and then a real job was run, which is the only step
;; that answers this. It failed twice, each time for a reason no amount of
;; reading the config back would have shown.
;;
;; 1. `context_length: 16384` -- b70's live slot-context, and the obviously
;;    correct value -- is REFUSED BY HERMES. It will not start an agent
;;    under 64,000 tokens (MINIMUM_CONTEXT_LENGTH) because its own system
;;    prompt plus tool schemas are a large fixed prefix. Hence 65536.
;;
;; 2. With that fixed, the request reached the endpoint and came back
;;    `502 murakumo fleet unreachable: Error: connection_refused` in 0.2s.
;;    Replaying Hermes's exact body with curl reproduced it, so it is the
;;    request, not the client. Bisecting a synthetic prompt found a sharp,
;;    repeatable boundary:
;;
;;      3,018 prompt tokens (15,115 body bytes)   HTTP 200
;;      ~3,200 prompt tokens (15,615 body bytes)  HTTP 502, no route-head header
;;
;;    The 502 carries no `x-murakumo-route-head` at all -- the router picked
;;    no head. Above ~288k chars the gateway rejects cleanly with `400 Input
;;    reserve exceeds this model's serving context window`; the band between
;;    is where it forwards to nothing.
;;
;;    Hermes's MINIMUM request -- system prompt plus 19 tool schemas, before
;;    a single conversation turn -- measured 4,459 tokens. That is above the
;;    ceiling. So while this holds, api.murakumo.cloud cannot serve a Hermes
;;    agent at all, and no setting in this file changes that. 17 of the 234
;;    enabled jobs are `no_agent` scripts and never call an LLM; the other
;;    217 all send that prefix.
;;
;;    `--verify` measures this rather than restating it: it reports which
;;    heads clear Hermes's floor, and it says NONE while gad (262144) is at
;;    zero free slots.
;;
;; ## The refusal
;;
;; --apply probes the endpoint first and REFUSES to write if it cannot get a
;; tool call back. Cutting a 236-job fleet onto an endpoint nobody just
;; measured is how you find out at 03:00. A refusal exits 2, which is neither
;; the pass value nor the findings value.

(ns hermes-murakumo-api
  (:require ["child_process" :as cp] ["fs" :as fs] ["os" :as os] ["path" :as path]
            [clojure.string :as str]))

(def ^:private argv (vec (drop 2 (js->clj js/process.argv))))
(defn- flag? [f] (boolean (some #{f} argv)))
(defn- opt [f d] (or (second (drop-while #(not= f %) argv)) d))

(def apply?     (flag? "--apply"))
(def verify?    (flag? "--verify"))
(def findings?  (flag? "--findings"))
(def self-test? (flag? "--self-test"))
(def no-probe?  (flag? "--no-probe"))

(def home (opt "--home" (path/join (os/homedir) ".hermes")))
(def profiles-root (path/join home "profiles"))

;; ── The contract ─────────────────────────────────────────────────────────
;; One map. Everything downstream reads it; nothing re-derives a value.

(def contract
  {:base-url          "https://api.murakumo.cloud/v1"
   :ready-url         "https://api.murakumo.cloud/ready"
   :provider          "murakumo"
   :model             "murakumo-main"
   :max-tokens        2048
   :context-length    65536
   :stale-timeout     600
   :request-timeout   900
   :max-parallel-jobs 6})

;; Models api.murakumo.cloud will NOT serve an anonymous caller. Measured
;; 2026-09-10; each returned 402 with `hosted_alternative: murakumo-main`.
;; Named literally so a config that keeps one is a finding with a reason,
;; not a diff.
(def unservable-models
  #{"z-ai/glm-5.3-flash" "z-ai/glm-5.3" "qwen/qwen3.8-flash" "qwen/qwen3.8-27b"
    "anthropic/claude-haiku-4.5" "anthropic/claude-sonnet-5" "anthropic/claude-opus-5"
    "awai-network/basho" "stepfun/step-3.7-flash" "google/gemma-4-31b-it"
    "deepseek/deepseek-v4-flash-0731" "openai/gpt-oss-120b"
    ;; fleet-hosted but not answering: 502 / is_ready false / timeout
    "qwen3.8-27b-throughput" "qwen3.8-27b-throughput-5090"
    "qwen3.8-27b-fastmtp-aggressive" "murakumo-edge"})

;; ── tiny io ──────────────────────────────────────────────────────────────

(defn- slurp* [p] (try (fs/readFileSync p "utf8") (catch :default _ nil)))
(defn- spit* [p s] (fs/writeFileSync p s "utf8"))
(defn- dir? [p] (try (.isDirectory (fs/statSync p)) (catch :default _ false)))
(defn- ls [p] (try (vec (fs/readdirSync p)) (catch :default _ [])))

(defn- curl
  "One curl. Returns {:status int :body string} or {:error string}. Never throws."
  [args]
  (try
    (let [out (cp/execFileSync "curl" (clj->js (concat ["-s" "-m" "45" "-w" "\n%{http_code}"] args))
                               #js {:encoding "utf8" :maxBuffer 8000000})
          lines (str/split-lines (str out))
          status (js/parseInt (last lines) 10)]
      {:status status :body (str/join "\n" (butlast lines))})
    (catch :default e {:error (or (.-message e) (str e))})))

;; ── probe: what the endpoint says about itself, right now ────────────────

;; Hermes refuses to start an agent under a 64,000-token window. So "is the
;; endpoint up" is not the question that decides whether the fleet can run --
;; "is a head at least that wide up" is. They are different questions, and on
;; 2026-09-10 they had different answers: chat/completions returned a correct
;; tool call while no head in the pool could hold a Hermes prompt.
(def hermes-minimum-context 64000)

(defn heads-clearing-floor
  "Heads that are BOTH serving and wide enough for Hermes.

  Width comes from /v1/models `murakumo.capacity-members` (each head's own
  /slots, read server-side); liveness from /ready `pool-available`, the count
  of free slots per head. A wide head with no free slot is not an answer, and
  neither is a live head that is too narrow -- so this intersects them rather
  than trusting either alone."
  [models ready]
  (let [main (first (filter #(= "murakumo-main" (:id %)) (:data models)))
        members (get-in main [:murakumo :capacity-members])
        pool (get-in ready [:inference :pool-available])]
    (vec (for [m members
               :let [head (:head m)
                     ctx (or (:context m) 0)
                     free (get pool (keyword head))]
               :when (and (>= ctx hermes-minimum-context) (pos? (or free 0)))]
           {:head head :context ctx :free free}))))

(defn usable?
  "Can the bots actually use what came back. Both halves are load-bearing:
  the status, and the tool call itself -- Hermes drives every action through
  tool calls, and a 200 whose body carries an upstream error yields none."
  [chat tool]
  (and (= 200 (:status chat)) (= "get_weather" tool)))

(defn probe
  "Ask api.murakumo.cloud two things: is it ready, and will it return a tool
  call for the contract model. Both, because /ready has been ok:true while
  the model that matters was not answering, and a completion alone does not
  say how many slots are left."
  []
  (let [ready (curl [(:ready-url contract)])
        models (curl [(str (:base-url contract) "/models")])
        chat  (curl ["-X" "POST" (str (:base-url contract) "/chat/completions")
                     "-H" "content-type: application/json"
                     "-d" (js/JSON.stringify
                           (clj->js {:model (:model contract)
                                     :messages [{:role "user" :content "What is the weather in Kyoto? Use the tool."}]
                                     :tools [{:type "function"
                                              :function {:name "get_weather"
                                                         :description "Get weather for a city"
                                                         :parameters {:type "object"
                                                                      :properties {:city {:type "string"}}
                                                                      :required ["city"]}}}]
                                     :max_tokens 128}))])
        parse (fn [r] (try (js->clj (js/JSON.parse (:body r)) :keywordize-keys true) (catch :default _ nil)))
        rj    (parse ready)
        mj    (parse models)
        cj    (parse chat)
        tool  (some-> cj :choices first :message :tool_calls first :function :name)]
    {:ready-status (:status ready)
     :ready-ok     (boolean (:ok rj))
     :degraded     (vec (or (:degraded rj) []))
     :capacity     (get-in rj [:inference :request-capacity])
     :pool         (get-in rj [:inference :pool-available])
     :wide-heads   (heads-clearing-floor mj rj)
     :chat-status  (:status chat)
     :served-by    (:model cj)
     :tool-call    tool
     ;; The one question that decides the cutover. A 200 whose body carries an
     ;; upstream error is not an answer (ADR-2608272100 measured that three
     ;; times in one afternoon), so this asks for the tool call itself.
     :usable       (usable? chat tool)
     :error        (or (:error ready) (:error chat))}))

;; ── YAML: split on top-level keys, own a few, carry the rest verbatim ────
;; Deliberately not a YAML parser. Hermes writes keys this script has never
;; heard of during a config-format migration, and re-rendering a file from a
;; parse would drop them. Same approach as resolve_free_model.cljs.

(defn- comment-run-start
  "Index of the first line of the contiguous comment/blank run ending at
  `i`-1. A block's leading comments belong to that block: replace the block
  and leave the prose, and the file now explains a decision it no longer
  makes. That is the failure this repo keeps recording -- the reverted rule
  that stays readable and keeps steering."
  [lines i]
  (loop [j (dec i) last i]
    (cond
      (neg? j) last
      (str/starts-with? (nth lines j) "#") (recur (dec j) j)
      (str/blank? (nth lines j)) (recur (dec j) last)
      :else last)))

(defn top-level-blocks
  "`yaml` as [[key text] ...], one entry per top-level `key:` line, each
  block carrying the comment run directly above it. Text before the first
  such run comes back under key nil."
  [yaml]
  (let [lines (vec (str/split-lines yaml))
        starts (keep-indexed (fn [i l]
                               (when-let [m (re-matches #"^([A-Za-z_][A-Za-z0-9_-]*):.*$" l)]
                                 [i (second m)]))
                             lines)
        withc (map (fn [[i k]] [(comment-run-start lines i) k]) starts)
        bounds (map (fn [[i k] [j _]] [k i (or j (count lines))])
                    withc (concat (rest withc) [nil]))
        head (if (seq withc) (subvec lines 0 (first (first withc))) lines)]
    (into (if (seq head) [[nil (str/join "\n" head)]] [])
          (map (fn [[k i j]] [k (str/join "\n" (subvec lines i j))]) bounds))))

(def owned-keys #{"model" "providers" "auxiliary" "fallback_providers" "cron"})

(defn- headers-block
  "Attribution headers at `indent`. Carried through the cutover unchanged:
  they cost nothing on an endpoint that reads no such header and they are
  what OpenRouter's leaderboard reads if the fallback lane is ever taken."
  [indent]
  (str indent "extra_headers:\n"
       indent "  HTTP-Referer: \"https://itonami.cloud\"\n"
       indent "  X-OpenRouter-Title: \"Itonami By KotobaLabs\"\n"
       indent "  X-Title: \"Itonami By KotobaLabs\""))

(defn render-owned
  "The blocks this script owns, rendered from `contract`.

  `root?` adds the two things only the gateway's own config decides: the
  cron parallelism cap (module-global pool, so one place) and the OpenRouter
  fallback lane. Profile configs get neither -- a per-profile fallback would
  be 231 independent chances to spend money that the budget guard prices as
  one account."
  [root?]
  (let [{:keys [base-url provider model max-tokens context-length
                stale-timeout request-timeout max-parallel-jobs]} contract]
    (str/join
     "\n"
     (remove
      nil?
      [(str "model:\n"
            "  provider: " provider "\n"
            "  base_url: " base-url "\n"
            "  default: " model "\n"
            ;; ~140s of one of two slots at the measured 13-17 tok/s decode.
            "  max_tokens: " max-tokens "\n"
            ;; b70's live slot-context. Not murakumo-main's advertised 262144:
            ;; only gad holds that and gad measured 0 available.
            "  context_length: " context-length "\n"
            (headers-block "  "))
       (str "providers:\n"
            "  " provider ":\n"
            "    base_url: " base-url "\n"
            "    api_mode: chat_completions\n"
            ;; No key_env. The commons lane is anonymous for fleet-hosted
            ;; models; sending an unknown bearer is how you find out it is
            ;; not (x-murakumo-commons-lane: commons, floor 1).
            "    stale_timeout_seconds: " stale-timeout "\n"
            "    request_timeout_seconds: " request-timeout "\n"
            (headers-block "    ")
            (when true
              (str "\n  openrouter-free:\n"
                   "    base_url: https://openrouter.ai/api/v1\n"
                   "    api_mode: chat_completions\n"
                   "    key_env: OPENROUTER_API_KEY\n"
                   "    stale_timeout_seconds: " stale-timeout "\n"
                   (headers-block "    "))))
       ;; Side jobs -- compression, titles, triage. Left unpinned they ride
       ;; the main provider; pinned at a head that reports is_ready false
       ;; (24 profiles were) every session emits an auxiliary failure line
       ;; for as long as that head is down. free_only is the ceiling either
       ;; way: never a billed model for background work.
       (str "auxiliary:\n"
            "  free_only: true\n"
            (str/join "\n"
                      (for [t ["vision" "web_extract" "compression" "skills_hub"
                               "approval" "mcp" "title_generation" "triage_specifier"]]
                        (str "  " t ":\n"
                             "    base_url: " base-url "\n"
                             "    model: " model))))
       ;; EVERY config gets the lane, root and profile alike.
       ;;
       ;; A profile's config.yaml REPLACES the root's -- get_config_path() is
       ;; get_hermes_home()/"config.yaml" and HERMES_HOME points at the
       ;; profile -- so a root-only chain is not inherited, it is absent.
       ;; The first cutover rendered profiles with a bare `fallback_providers:`
       ;; on the reasoning that one lane per account is enough; measured
       ;; afterwards, `hermes --profile cron-health fallback list` said "No
       ;; fallback providers configured" and a murakumo 502 was terminal
       ;; instead of walking anywhere. 502 classifies as
       ;; FailoverReason.server_error, which retries and only then arms the
       ;; fallback -- so with no chain to arm, three retries and the job dies.
       (str "fallback_providers:\n"
            "  - provider: openrouter-free\n"
            "    model: z-ai/glm-5.3-flash\n"
            "    base_url: https://openrouter.ai/api/v1")
       (when root?
         (str "cron:\n"
              "  bot_chat_delivery_timeout_seconds: 900\n"
              "  max_parallel_jobs: " max-parallel-jobs))]))))

(def root-header
  (str "# Managed by scripts/hermes-murakumo-api.cljs. Do not hand-edit the\n"
       "# blocks it owns (model / providers / auxiliary / fallback_providers /\n"
       "# cron): they are rendered whole from one contract map. Every other\n"
       "# top-level key is carried through verbatim.\n"
       "#\n"
       "# Primary inference is https://api.murakumo.cloud/v1, model\n"
       "# murakumo-main -- fleet-hosted, anonymous, and the only name the\n"
       "# endpoint serves without payment or identity. Read the script's\n"
       "# header for what every number below was measured against.\n"
       "#\n"
       "# scripts/hermes-hyakka-bots/resolve_free_model.cljs USED to own this\n"
       "# file and would rewrite the primary back onto an OpenRouter free\n"
       "# model. Its two cron jobs are disabled by the cutover; re-enabling\n"
       "# one silently reverts everything here within the hour.\n"))

(defn- strip-stale-header
  "Drop a leading comment run that names the script which no longer owns
  this file. Left in place it is a set of instructions pointing at the wrong
  owner, which is worse than no header."
  [yaml]
  (let [lines (vec (str/split-lines (or yaml "")))
        n (count (take-while #(or (str/starts-with? % "#") (str/blank? %)) lines))
        head (str/join "\n" (take n lines))]
    (if (str/includes? head "resolve_free_model")
      (str/join "\n" (drop n lines))
      yaml)))

(defn rewrite-yaml
  "Owned blocks replaced, unowned blocks carried through in their original
  order, owned blocks that were absent appended."
  [yaml root?]
  (let [yaml (if root? (strip-stale-header yaml) yaml)
        blocks (top-level-blocks (or yaml ""))
        owned  (into {} (map (fn [[k _]] [k true]) (filter #(owned-keys (first %)) blocks)))
        new    (into {} (map (fn [b] [(first (str/split b #":")) b])
                             (str/split (render-owned root?) #"\n(?=[A-Za-z_])")))
        kept   (map (fn [[k text]]
                      (if (owned-keys k) (get new k) text))
                    blocks)
        missing (remove #(get owned %) (keys new))]
    (str (when root? root-header)
         (str/join "\n" (remove nil? (concat kept (map new missing)))) "\n")))

;; ── the fleet on disk ────────────────────────────────────────────────────

;; Two cron jobs run `refresh_free_model.py`, which asks
;; resolve_free_model.cljs to pick an OpenRouter free model and INSTALL it --
;; rewriting ~/.hermes/config.yaml and reconciling the cron table onto it.
;; One of them fires at :40 of every hour. Cutting the fleet to murakumo
;; without stopping them means the cutover is reverted before the next tick,
;; and the reversion looks exactly like a job doing its job.
;;
;; Disabled, not deleted: the resolver is still the right answer if the
;; fleet endpoint is ever retired, and a deleted job cannot be re-enabled by
;; someone reading this. The reason is written into the job record so the
;; next reader does not have to find this file.
(def resolver-scripts #{"refresh_free_model.py" "resolve_free_model.cljs"})

(def resolver-pause-reason
  (str "Disabled by the murakumo cutover (scripts/hermes-murakumo-api.cljs). "
       "This job installs an OpenRouter free model as the PRIMARY by "
       "rewriting ~/.hermes/config.yaml, which reverts the fleet off "
       "api.murakumo.cloud within the hour. Re-enable only together with a "
       "decision to stop using murakumo-main as primary."))

(defn- resolver-job? [j]
  (boolean (or (resolver-scripts (:script j))
               (some-> (:name j) (str/includes? "model-refresh")))))

(defn profile-dirs []
  (->> (ls profiles-root)
       (map #(path/join profiles-root %))
       (filter dir?)
       sort))

(defn- yaml-scalar
  "The value of a `key:` line nested under `parent:`. Enough for reporting;
  the rewrite never reads its own output back through this."
  [yaml parent k]
  (when yaml
    (let [lines (str/split-lines yaml)
          i (first (keep-indexed #(when (= %2 (str parent ":")) %1) lines))]
      (when i
        (some (fn [l]
                (when-let [m (re-matches (re-pattern (str "^\\s{2}" k ": (.*)$")) l)]
                  (str/trim (str/replace (second m) #"['\"]" ""))))
              (take-while #(or (str/blank? %) (str/starts-with? % " "))
                          (drop (inc i) lines)))))))

(defn read-jobs [p]
  (try (js->clj (js/JSON.parse (slurp* p)) :keywordize-keys true) (catch :default _ nil)))

(defn job-files []
  (->> (profile-dirs)
       (map #(path/join % "cron" "jobs.json"))
       (filter fs/existsSync)))

(defn survey
  "What the fleet names right now. Counts; judges nothing."
  []
  (let [confs (for [d (profile-dirs)
                    :let [p (path/join d "config.yaml")]
                    :when (fs/existsSync p)]
                {:profile (path/basename d) :path p
                 :model (yaml-scalar (slurp* p) "model" "default")
                 :base-url (yaml-scalar (slurp* p) "model" "base_url")})
        jobs (for [p (job-files)
                   :let [d (read-jobs p)]
                   j (or (:jobs d) [])]
               {:path p :profile (path/basename (path/dirname (path/dirname p)))
                :id (:id j) :name (:name j)
                :model (:model j) :provider (:provider j) :base-url (:base_url j)
                :enabled (:enabled j) :status (:last_status j) :resolver (resolver-job? j)
                :streak (or (:failure_streak j) 0)})
        root-yaml (slurp* (path/join home "config.yaml"))]
    {:root-model (yaml-scalar root-yaml "model" "default")
     :root-base  (yaml-scalar root-yaml "model" "base_url")
     :configs (vec confs)
     :jobs (vec jobs)}))

(defn findings-of [s]
  (let [{:keys [root-model configs jobs]} s
        off-conf (remove #(= (:model %) (:model contract)) configs)
        bad-conf (filter #(unservable-models (:model %)) configs)
        off-job  (filter :enabled (remove #(= (:model %) (:model contract)) jobs))
        bad-job  (filter #(unservable-models (:model %)) (filter :enabled jobs))]
    (remove
     nil?
     [(when (not= root-model (:model contract))
        {:id "root-model" :level :fail :n 1
         :text (str "~/.hermes/config.yaml names model.default " (pr-str root-model)
                    ", not " (:model contract) ". The gateway process reads this one.")})
      (when (seq bad-conf)
        {:id "unservable-model-config" :level :fail :n (count bad-conf)
         :text (str (count bad-conf) " profile config(s) name a model api.murakumo.cloud "
                    "answers 402/502/timeout for: "
                    (str/join ", " (take 6 (map #(str (:profile %) "=" (:model %)) bad-conf))))})
      (when (seq off-conf)
        {:id "off-contract-config" :level :warn :n (count off-conf)
         :text (str (count off-conf) " of " (count configs)
                    " profile config(s) do not name " (:model contract))})
      (when (seq bad-job)
        {:id "unservable-model-job" :level :fail :n (count bad-job)
         :text (str (count bad-job) " enabled cron job(s) pin a model the endpoint will not serve: "
                    (str/join ", " (take 6 (map #(str (:profile %) "/" (:name %)) bad-job))))})
      (let [live (filter #(and (:enabled %) (:resolver %)) jobs)]
        (when (seq live)
          {:id "free-model-resolver-enabled" :level :fail :n (count live)
           :text (str (count live) " enabled job(s) install an OpenRouter free model as PRIMARY "
                      "by rewriting ~/.hermes/config.yaml, which reverts this cutover: "
                      (str/join ", " (map #(str (:profile %) "/" (:name %)) live)))}))
      (when (seq off-job)
        {:id "off-contract-job" :level :warn :n (count off-job)
         :text (str (count off-job) " enabled cron job(s) do not pin " (:model contract)
                    " -- an unpinned or drifted snapshot is what makes a tick drift_skip")})])))

;; ── writing ──────────────────────────────────────────────────────────────

(defn backup! [p]
  (let [b (str p ".bak-murakumo-" (.replace (subs (.toISOString (js/Date.)) 0 19) #"[:T-]" ""))]
    (when (and (fs/existsSync p) (not (fs/existsSync b)))
      (fs/copyFileSync p b))
    b))

(defn write-config! [p root?]
  (let [before (slurp* p)
        after  (rewrite-yaml before root?)]
    (if (= before after)
      {:path p :changed false}
      (do (backup! p) (spit* p after) {:path p :changed true}))))

(defn write-jobs!
  "Repin every job in one profile's jobs.json.

  The snapshots matter as much as the pin. Hermes compares a job's
  provider_snapshot/model_snapshot against the live inference config and
  skips the run with `drift_skip` when they disagree -- and a drift_skip is
  recorded as an error, so leaving them stale converts a working cutover
  into 236 failing jobs."
  [p]
  (let [d (read-jobs p)]
    (if-not d
      {:path p :changed false :error "unreadable"}
      (let [{:keys [provider model base-url]} contract
            jobs (:jobs d)
            new  (mapv (fn [j]
                         (if (resolver-job? j)
                           (assoc j :enabled false
                                  :paused_at (.toISOString (js/Date.))
                                  :paused_reason resolver-pause-reason)
                           (assoc j :provider provider :model model :base_url base-url
                                  :provider_snapshot provider :model_snapshot model)))
                       jobs)
            n    (count (filter (fn [[a b]] (not= a b)) (map vector jobs new)))
            dis  (count (filter resolver-job? jobs))]
        (if (zero? n)
          {:path p :changed false :jobs (count jobs)}
          (do (backup! p)
              (spit* p (js/JSON.stringify (clj->js (assoc d :jobs new)) nil 2))
              {:path p :changed true :repinned (- n dis) :disabled dis :jobs (count jobs)}))))))

(defn apply! []
  (let [root (write-config! (path/join home "config.yaml") true)
        confs (doall (for [d (profile-dirs)
                           :let [p (path/join d "config.yaml")]
                           :when (fs/existsSync p)]
                       (write-config! p false)))
        jobs (doall (map write-jobs! (job-files)))]
    {:root root
     :configs-changed (count (filter :changed confs))
     :configs-total (count confs)
     :jobs-files-changed (count (filter :changed jobs))
     :jobs-repinned (reduce + 0 (keep :repinned jobs))
     :jobs-disabled (reduce + 0 (keep :disabled jobs))
     :jobs-total (reduce + 0 (keep :jobs jobs))}))

;; ── self-test ────────────────────────────────────────────────────────────
;; Counts failures. A boolean cannot tell one regression from a broken build
;; (CLAUDE.md, the 8 questions).

(defn self-test []
  (let [fails (atom [])
        is (fn [name got want]
             (when-not (= got want)
               (swap! fails conj (str name ": got " (pr-str got) " want " (pr-str want)))))]

    ;; top-level splitting keeps unowned keys and their bodies
    (let [y "model:\n  default: x\nsecrets:\n  command:\n    enabled: true\n_config_version: 39\n"
          bs (top-level-blocks y)]
      (is "blocks/count" (count bs) 3)
      (is "blocks/keys" (mapv first bs) ["model" "secrets" "_config_version"])
      (is "blocks/body-kept" (second (second bs)) "secrets:\n  command:\n    enabled: true"))

    ;; the rewrite replaces owned keys and carries the rest verbatim, in order
    (let [y "model:\n  default: z-ai/glm-5.3-flash\nsecrets:\n  command:\n    enabled: true\n_config_version: 39\n"
          out (rewrite-yaml y false)]
      (is "rewrite/keeps-secrets" (str/includes? out "command:\n    enabled: true") true)
      (is "rewrite/keeps-version" (str/includes? out "_config_version: 39") true)
      ;; NOT a substring check. z-ai/glm-5.3-flash legitimately reappears
      ;; below as the FALLBACK model, so `does the file mention it` stopped
      ;; being able to tell "old primary still there" from "new fallback
      ;; lane" -- and that ambiguity is the whole failure mode this file is
      ;; about. Ask the structured question instead: what is model.default.
      (is "rewrite/primary-is-contract" (yaml-scalar out "model" "default") (:model contract))
      (is "rewrite/old-primary-gone" (= "z-ai/glm-5.3-flash" (yaml-scalar out "model" "default")) false)
      (is "rewrite/names-contract" (str/includes? out "default: murakumo-main") true)
      (is "rewrite/base-url" (str/includes? out (str "base_url: " (:base-url contract))) true)
      (is "rewrite/caps-max-tokens" (str/includes? out "max_tokens: 2048") true)
      ;; the cron cap is a single module-global pool per gateway PROCESS, so
      ;; only the root config carries it
      (is "rewrite/profile-no-cron-cap" (str/includes? out "max_parallel_jobs") false)
      ;; but the fallback lane MUST be in every profile: a profile config
      ;; replaces the root's rather than layering over it, so a root-only
      ;; chain is absent, not inherited
      (is "rewrite/profile-has-fallback" (str/includes? out "provider: openrouter-free") true)
      (is "rewrite/profile-has-or-provider" (str/includes? out "openrouter.ai/api/v1") true))

    ;; the root config is the only one that carries the global knobs
    (let [out (rewrite-yaml "model:\n  default: old\n" true)]
      (is "root/cron-cap" (str/includes? out (str "max_parallel_jobs: " (:max-parallel-jobs contract))) true)
      (is "root/fallback-lane" (str/includes? out "provider: openrouter-free") true))

    ;; idempotent: applying to its own output changes nothing
    (let [once (rewrite-yaml "model:\n  default: old\n_config_version: 39\n" true)]
      (is "rewrite/idempotent" (rewrite-yaml once true) once))

    ;; a config with no `model:` block at all still gets one
    (let [out (rewrite-yaml "_config_version: 41\n" false)]
      (is "rewrite/appends-missing" (str/includes? out "default: murakumo-main") true)
      (is "rewrite/appends-keeps-other" (str/includes? out "_config_version: 41") true))

    ;; the models the endpoint refuses are named, so a config keeping one fails
    (is "unservable/glm" (boolean (unservable-models "z-ai/glm-5.3-flash")) true)
    (is "unservable/not-main" (boolean (unservable-models "murakumo-main")) false)
    ;; is_ready false is as unservable as 402, and for a worse reason: it answers
    (is "unservable/fastmtp" (boolean (unservable-models "qwen3.8-27b-fastmtp-aggressive")) true)

    ;; findings fire on the shapes they name, and only those
    (let [bad {:root-model "z-ai/glm-5.3-flash"
               :configs [{:profile "a" :model "z-ai/glm-5.3-flash"}]
               :jobs [{:profile "a" :name "j" :model "z-ai/glm-5.3-flash" :enabled true}]}
          ids (set (map :id (findings-of bad)))]
      (is "findings/root" (contains? ids "root-model") true)
      (is "findings/conf" (contains? ids "unservable-model-config") true)
      (is "findings/job" (contains? ids "unservable-model-job") true))
    (let [good {:root-model "murakumo-main"
                :configs [{:profile "a" :model "murakumo-main"}]
                :jobs [{:profile "a" :name "j" :model "murakumo-main" :enabled true}]}]
      (is "findings/clean" (count (findings-of good)) 0))
    ;; a DISABLED job on a dead model is not a finding -- it is not running
    (let [s {:root-model "murakumo-main" :configs []
             :jobs [{:profile "a" :name "j" :model "z-ai/glm-5.3-flash" :enabled false}]}]
      (is "findings/ignores-disabled" (count (findings-of s)) 0))

    ;; the job that reverts the cutover is recognised by script AND by name,
    ;; because the two live instances differ: one carries the script, the
    ;; other was created with the same script under a different profile.
    (is "resolver/by-script" (resolver-job? {:script "refresh_free_model.py"}) true)
    (is "resolver/by-name"   (resolver-job? {:name "hyakka-model-refresh"}) true)
    (is "resolver/not-others" (resolver-job? {:script "hyakka_evidence.py" :name "hyakka-source-scout"}) false)
    ;; and while one is still enabled, --verify must FAIL. A green verify
    ;; beside a live reverter is the "measured nothing, reported clean" shape.
    (let [s {:root-model "murakumo-main" :configs []
             :jobs [{:profile "hyakka" :name "hyakka-model-refresh" :model "murakumo-main"
                     :enabled true :resolver true}]}
          f (findings-of s)]
      (is "findings/reverter-fails" (some #(= "free-model-resolver-enabled" (:id %)) f) true)
      (is "findings/reverter-is-fail" (:level (first (filter #(= "free-model-resolver-enabled" (:id %)) f))) :fail))
    (let [s {:root-model "murakumo-main" :configs []
             :jobs [{:profile "hyakka" :name "hyakka-model-refresh" :model "murakumo-main"
                     :enabled false :resolver true}]}]
      (is "findings/reverter-disabled-clean" (count (findings-of s)) 0))

    ;; the probe's usable? is the tool call AND the status, and it must read
    ;; the key the curl result actually carries. The first version read
    ;; :chat-status -- a key of the RESULT map, not of the curl map -- so it
    ;; reported "usable false" against an endpoint that had just returned a
    ;; correct tool call, and --apply would have refused for a reason that
    ;; was not true. usable? is factored out so the test can reach it.
    ;; the floor check intersects width and liveness -- either alone lies
    (let [models {:data [{:id "murakumo-main"
                          :murakumo {:capacity-members [{:head "gad" :context 262144}
                                                        {:head "b70" :context 16384}
                                                        {:head "xavier" :context 8192}]}}]}]
      ;; wide head present but no free slot: not an answer. This is the
      ;; shape measured on 2026-09-10.
      (is "floor/wide-but-busy"
          (heads-clearing-floor models {:inference {:pool-available {:gad 0 :b70 2 :xavier 1}}}) [])
      ;; live heads but all too narrow: also not an answer
      (is "floor/live-but-narrow"
          (heads-clearing-floor models {:inference {:pool-available {:b70 2 :xavier 1}}}) [])
      ;; both at once is the only pass
      (is "floor/wide-and-free"
          (mapv :head (heads-clearing-floor models {:inference {:pool-available {:gad 2 :b70 2}}})) ["gad"]))

    (is "probe/usable-yes"  (usable? {:status 200} "get_weather") true)
    (is "probe/usable-no-tool" (usable? {:status 200} nil) false)
    (is "probe/usable-bad-status" (usable? {:status 429} "get_weather") false)
    ;; a 200 whose body carried an upstream error yields no tool call, and
    ;; that is the case ADR-2608272100 recorded three times in one afternoon
    (is "probe/usable-200-with-error-inside" (usable? {:status 200} nil) false)

    (if (seq @fails)
      (do (println "SELF-TEST FAILURES\t" (count @fails))
          (doseq [f @fails] (println "  " f))
          (js/process.exit 1))
      (do (println "SELF-TEST\tok\t37 assertions")
          (js/process.exit 0)))))

;; ── report ───────────────────────────────────────────────────────────────

(defn print-report [s p fs]
  (let [{:keys [configs jobs root-model]} s]
    (println)
    (println "hermes profile bots -> api.murakumo.cloud")
    (println "=========================================================================")
    (println (str "  home                        " home))
    (println (str "  profiles / configs          " (count (profile-dirs)) " / " (count configs)))
    (println (str "  cron jobs (enabled)         " (count jobs) " (" (count (filter :enabled jobs)) ")"))
    (println (str "  root model.default          " (pr-str root-model)))
    (println (str "  contract model              " (:model contract) " @ " (:base-url contract)))
    (println)
    (when p
      (println "  endpoint, measured just now")
      (println (str "    /ready                    HTTP " (:ready-status p)
                    "  ok=" (:ready-ok p)
                    (when (seq (:degraded p)) (str "  degraded=" (str/join "," (:degraded p))))))
      (println (str "    request-capacity          " (pr-str (:capacity p))))
      (println (str "    pool-available            " (pr-str (:pool p))))
      (println (str "    chat/completions          HTTP " (:chat-status p)
                    "  served-by=" (pr-str (:served-by p))
                    "  tool_call=" (pr-str (:tool-call p))))
      (println (str "    usable for bots           " (:usable p)))
      (println (str "    heads >= hermes 64K floor " (if (seq (:wide-heads p))
                                                       (str/join ", " (map #(str (:head %) " ctx=" (:context %)
                                                                                 " free=" (:free %)) (:wide-heads p)))
                                                       "NONE")))
      (when (empty? (:wide-heads p))
        (println)
        (println "    ! Hermes will not start an agent under a 64,000-token window")
        (println "      (MINIMUM_CONTEXT_LENGTH, agent/agent_init.py). No serving head")
        (println "      clears it right now, so a run whose prompt passes the serving")
        (println "      head's real window comes back HTTP 400 \"Input reserve exceeds")
        (println "      this model's serving context window\" -- and 400 is not a")
        (println "      failover reason in hermes, so it does not walk the fallback")
        (println "      chain. Short runs work; long ones do not. The fix is upstream:")
        (println "      a wide head back in the pool (gad is 262144, measured 0 free)."))
      (println))
    (if (seq fs)
      (doseq [f fs]
        (println (str "  [" (name (:level f)) "] " (:id f)))
        (println (str "      " (:text f))))
      (println "  no findings — every config and enabled job names the contract model"))
    (println)))

;; ── main ─────────────────────────────────────────────────────────────────

(defn -main []
  (cond
    self-test? (self-test)

    :else
    (let [p (when-not no-probe? (probe))
          s (survey)
          fs (findings-of s)]

      (when (and apply? p (not (:usable p)))
        ;; Refuse rather than answer. A gate that cannot measure its
        ;; precondition and writes anyway is the failure this repo keeps
        ;; recording: the unmeasured run returns the value of the clean one.
        (println "REFUSED\tapi.murakumo.cloud did not return a tool call for"
                 (:model contract))
        (println (str "  /ready HTTP " (:ready-status p) " ok=" (:ready-ok p)
                      "  chat HTTP " (:chat-status p)
                      "  tool_call=" (pr-str (:tool-call p))
                      (when (:error p) (str "  error=" (:error p)))))
        (println "  Not cutting 236 scheduled jobs onto an endpoint that just failed a probe.")
        (js/process.exit 2))

      (if findings?
        (do (println (str "SCANNED\t" (+ (count (:configs s)) (count (:jobs s)))))
            (doseq [f fs] (println (str (name (:level f)) "\t" (:id f) "\t" (:n f) "\t" (:text f)))))
        (print-report s p fs))

      (when apply?
        (let [r (apply!)]
          (println "APPLIED")
          (println (str "  ~/.hermes/config.yaml       " (if (:changed (:root r)) "rewritten" "already conformant")))
          (println (str "  profile configs             " (:configs-changed r) " / " (:configs-total r) " rewritten"))
          (println (str "  cron jobs repinned          " (:jobs-repinned r) " / " (:jobs-total r)
                        " in " (:jobs-files-changed r) " file(s)"))
          (println (str "  free-model resolver jobs    " (:jobs-disabled r) " disabled"
                        " (they rewrite config.yaml back onto OpenRouter)"))
          (println)
          (println "  Backups sit beside each file as *.bak-murakumo-<utc>.")
          (println "  The gateway caches its client at startup (ADR-2608251016): the")
          (println "  change is not live until the multiplexed `gateway run` is recycled.")
          (println)))

      ;; The exit code is what a scheduler reads. It agrees with the text in
      ;; every mode: a report that PRINTS [fail] and RETURNS 0 is the shape
      ;; this repo keeps recording -- the unmeasured run and the clean run
      ;; returning the same value.
      (js/process.exit (if (seq (filter #(= :fail (:level %)) fs)) 1 0)))))

(-main)

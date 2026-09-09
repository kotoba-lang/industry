#!/usr/bin/env nbb
(ns resolve-free-model-test
  "Does the resolver tell `busy` apart from `cannot`?

  This is the one distinction the whole script turns on, and it was wrong
  three times while being written — each time in a way that read as a
  measurement. A 429 scored 0/3. An upstream overload arrived inside a
  HTTP 200 and scored 0/3. A 429 on the second of three tasks scored 2/3.
  All three filed `the free tier was measured and found wanting` when what
  had happened was `the free tier was busy`.

  It requires the real namespace on purpose. A copy of `unavailable?` here
  would keep passing after the real one was deleted."
  (:require [resolve-free-model :as r]
            [clojure.string :as str]))

(def cases
  ;; [name response unavailable? why]
  [["429 from the provider"
    {:status 429 :text "{\"error\":{\"message\":\"Provider returned error\"}}"} true
    "rate limit says nothing about the model"]
   ["403 harness-only model"
    {:status 403 :text "{\"error\":{\"message\":\"only available on agentic harnesses\"}}"} true
    "gated, not incapable"]
   ["upstream overload inside a 200"
    {:status 200 :text "{\"error\":{\"message\":\"Upstream error from Nvidia: Service temporarily overloaded\"}}"} true
    "the error is in the body; reading only the status files it as incapacity"]
   ["per-choice error inside a 200"
    {:status 200 :text "{\"choices\":[{\"error\":{\"message\":\"overloaded\"}}]}"} true
    "same, one level in"]
   ["404 no endpoints"
    {:status 404 :text "{\"error\":{\"message\":\"No endpoints found\"}}"} true
    "the model is delisted — an availability fact"]
   ["402 out of quota"
    {:status 402 :text "{\"error\":{\"message\":\"Insufficient credits\"}}"} true
    "an account fact, not a model fact"]
   ["network failure"
    {:status 0 :text "network error: fetch failed"} true
    "we did not reach it"]
   ["400 bad request"
    {:status 400 :text "{\"error\":{\"message\":\"tool_choice unsupported\"}}"} false
    "the REQUEST was rejected — that is a fact about the model, and calling it
     `busy` would leave an incapable model in the queue forever"]
   ;; ── status-only fixtures ────────────────────────────────────────────
   ;; The cases above all carry an `error` object in the body, so they pass
   ;; through the body-error branch and cannot pin the status rules. Deleting
   ;; 429 from the status set left the suite green until these existed.
   ["429 with a bare body"
    {:status 429 :text "Too Many Requests"} true
    "only the status rule can catch this one"]
   ["403 with a bare body"
    {:status 403 :text "Forbidden"} true
    "same, for the gated-model rule"]
   ["404 with a bare body"
    {:status 404 :text ""} true
    "same, for the delisted rule"]
   ["402 with a bare body"
    {:status 402 :text ""} true
    "same, for the out-of-quota rule"]
   ["503 with a bare body"
    {:status 503 :text "Service Unavailable"} true
    "same, for the 5xx rule"]
   ["400 with a bare body"
    {:status 400 :text "Bad Request"} false
    "the request was rejected; still a fact about the model"]

   ["a real reply"
    {:status 200 :text "{\"choices\":[{\"message\":{\"content\":\"HYAKKA-OK\"}}]}"} false
    "answered; now it can be judged"]])

(def message-cases
  [["a body error hides the message"
    {:status 200 :text "{\"error\":{\"message\":\"Upstream error\"}}"} false]
   ["a clean reply yields the message"
    {:status 200 :text "{\"choices\":[{\"message\":{\"content\":\"x\"}}]}"} true]])

(def config-cases
  [["response cache is enabled"
    #"(?m)^openrouter:\n  response_cache: true$"]
   ["response cache TTL stays bounded to five minutes"
    #"(?m)^  response_cache_ttl: 300$"]
   ;; ── attribution ──────────────────────────────────────────────────
   ;; Hermes ships its own values for these and names itself in them, so an
   ;; unrendered block is not a blank — it is upstream's name on this
   ;; account's spend. Both placements are pinned because they reach
   ;; different clients: the provider block covers the main turn, and only
   ;; `model.extra_headers` reaches the auxiliary lane.
   ["the provider block carries the referer"
    #"(?m)^    HTTP-Referer: \"https://itonami.cloud\"$"]
   ["the provider block carries the documented title header"
    #"(?m)^    X-OpenRouter-Title: \"Itonami By KotobaLabs\"$"]
   ["the legacy title header is overridden too, not left to Hermes"
    #"(?m)^    X-Title: \"Itonami By KotobaLabs\"$"]
   ["the model block repeats them, so side jobs are attributed too"
    #"(?m)^model:\n(?:  .*\n|  #.*\n)*  extra_headers:\n    HTTP-Referer: \"https://itonami\.cloud\"$"]])

;; ── carry-forward ────────────────────────────────────────────────────
;; The rewrite is whole-file, so anything not rendered is gone unless it is
;; carried. Measured 2026-08-31: the Hermes v0.20.5 -> v0.20.6 config
;; migration added `_config_version`, `agent` and `plugins`, and the resolver
;; exited 2 on every run from that moment. Both directions are pinned here —
;; a config with only owned keys must carry NOTHING, or "carried it" and
;; "there was nothing to carry" would return the same value.

(def owned-only-config
  (str "secrets:\n  command:\n    enabled: true\n"
       "\nproviders:\n  openrouter-free:\n    api_mode: chat_completions\n"
       "\nmodel:\n  provider: openrouter-free\n  default: x/y:free\n"))

(def migrated-config
  (str owned-only-config
       "\nplugins:\n  enabled: []\n"
       "_config_version: 39\n"
       "agent: {}\n"))

(defn- carry-cases []
  ;; `carried` is coerced with `str` on purpose: when carry-forward regresses
  ;; to nil, `re-find` against nil THROWS, and a crashed suite is a weaker
  ;; demonstration than a red one — it proves the code broke, not that these
  ;; assertions can tell. Coerced, a regression prints FAIL for each case.
  (let [nothing (r/carry-forward owned-only-config)
        carried (str (r/carry-forward migrated-config))
        keys'   (r/carried-keys migrated-config)]
    [["a config of only owned keys carries nothing"
      (nil? nothing)
      "if this returns text, the negative control is dead and every 'carried'
       assertion below passes for the wrong reason"]
     ["the three keys Hermes added are named"
      (= ["_config_version" "agent" "plugins"] (vec keys'))
      "the report has to say what it moved, or carrying is silent"]
     ["`plugins` keeps its nested value, not just its key"
      (boolean (re-find #"(?m)^plugins:\n  enabled: \[\]$" carried))
      "a block splitter that stops at the key line would drop the body and
       still look like it worked"]
     ["`_config_version` survives with its value"
      (boolean (re-find #"(?m)^_config_version: 39$" carried))
      "a leading underscore has to survive the key regex"]
     ["`agent` survives"
      (boolean (re-find #"(?m)^agent: \{\}$" carried))
      ""]
     ["no owned key is duplicated into the carried tail"
      (not (re-find #"(?m)^(secrets|providers|model):" carried))
      "carrying an owned block too would emit the key twice and YAML would
       take the LAST one — the rewrite would silently lose its own answer"]
     ["what install writes still contains the rendered model"
      ;; Asserted against whatever the renderer actually emitted, not against
      ;; the argument. Since 2026-09-09 a fleet `:primary` overrides the
      ;; passed model-id, and pinning the literal here would make this
      ;; composition check fail for a reason that has nothing to do with
      ;; composition — the exact confusion it exists to rule out.
      (let [body (r/render-config "example/model:free" [] false)
            line (re-find #"(?m)^  default: .*$" body)]
        (boolean (and line (.includes (str body carried) line))))
      "composition check: the tail must not truncate the rendered body"]]))


;; ── per-profile configs ──────────────────────────────────────────────
;; A profile's config.yaml REPLACES the default one rather than layering
;; over it, so a setting written only to ~/.hermes/config.yaml reaches
;; exactly the runs that name no profile. Measured 2026-08-31: 23 profiles,
;; all still resolving Hermes's own attribution. The patch is surgical
;; because profiles differ from each other — `pr-cleanup` runs a different
;; model — and re-rendering would overwrite that difference with a default.

(def profile-config
  ;; Shaped like the real thing: Hermes dumps these with comments stripped
  ;; and no blank lines, and this one carries a per-profile model on purpose.
  (str "secrets:\n  command:\n    enabled: true\n"
       "providers:\n  openrouter-free:\n    base_url: https://openrouter.ai/api/v1\n"
       "    stale_timeout_seconds: 600\n"
       "model:\n  provider: openrouter-free\n  default: upstage/solar-pro4:free\n"
       "auxiliary:\n  free_only: true\n"))

(defn- profile-cases []
  (let [once  (r/patch-attribution profile-config)
        twice (r/patch-attribution once)
        stale (r/patch-attribution
                (str/replace once "Itonami By KotobaLabs" "Someone Else"))]
    [["the provider section gains the headers"
      (boolean (re-find #"(?m)^    X-OpenRouter-Title: \"Itonami By KotobaLabs\"$" once))
      ""]
     ["the model section gains them too"
      (boolean (re-find #"(?m)^  extra_headers:\n    HTTP-Referer: \"https://itonami\.cloud\"$" once))
      "only this one reaches the auxiliary client"]
     ["the profile's own model is untouched"
      (boolean (re-find #"(?m)^  default: upstage/solar-pro4:free$" once))
      "the whole reason this is a patch and not a re-render"]
     ["nothing else in the file moved"
      (every? #(str/includes? once %)
              ["secrets:" "    enabled: true" "    stale_timeout_seconds: 600"
               "auxiliary:" "  free_only: true"])
      "a section-end that overshoots would swallow the next block"]
     ["a second run changes nothing"
      (= once twice)
      "YAML takes the LAST of two duplicate keys, so an append-only version
       would keep looking right while ignoring every later edit"]
     ["a stale value is replaced, not appended beside"
      (and (not (str/includes? stale "Someone Else"))
           (= 2 (count (re-seq #"(?m)^\s+X-OpenRouter-Title:" stale))))
      "this is the case the strip-first exists for"]
     ["a config with neither section is returned untouched"
      (= "unrelated: true\n" (r/patch-attribution "unrelated: true\n"))
      "negative control: if the patcher writes into anything it is handed,
       'patched 23 profiles' stops being evidence of anything"]]))

(defn -main []
  (let [fails (atom 0)]
    (doseq [[nm resp want why] cases]
      (let [got (boolean (r/unavailable? resp))]
        (when (not= got want) (swap! fails inc))
        (println (if (= got want) "ok  " "FAIL") nm "->" (if got "unavailable" "measured")
                 (str "— " (str/replace why #"\s+" " ")))))
    (doseq [[nm resp want] message-cases]
      (let [got (some? (r/message-of resp))]
        (when (not= got want) (swap! fails inc))
        (println (if (= got want) "ok  " "FAIL") nm "-> message-of"
                 (if got "returned a message" "returned nil"))))
    (let [rendered (r/render-config "example/model:free" [] false)]
      (doseq [[nm pattern] config-cases]
        (let [got (boolean (re-find pattern rendered))]
          (when-not got (swap! fails inc))
          (println (if got "ok  " "FAIL") nm))))
    (let [cc (concat (carry-cases) (profile-cases))]
      (doseq [[nm got why] cc]
        (when-not got (swap! fails inc))
        (println (if got "ok  " "FAIL") nm
                 (if (str/blank? why) "" (str "— " (str/replace why #"\s+" " ")))))
      (println (str "RAN\t" (+ (count cases) (count message-cases) (count config-cases)
                               (count cc))
                    " cases, " @fails " failed")))
    (when (pos? @fails) (.exit js/process 1))))

(-main)

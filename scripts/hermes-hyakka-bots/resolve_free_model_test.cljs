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
    (println (str "RAN\t" (+ (count cases) (count message-cases)) " cases, " @fails " failed"))
    (when (pos? @fails) (.exit js/process 1))))

(-main)

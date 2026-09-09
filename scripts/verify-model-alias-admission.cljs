#!/usr/bin/env nbb
;; verify-model-alias-admission — the model an alias resolves to must be one
;; the app will admit.
;;
;;   nbb scripts/verify-model-alias-admission.cljs             # live probe
;;   nbb scripts/verify-model-alias-admission.cljs --self-test # both directions
;;
;; ## What this would have caught, 2026-08-27
;;
;; Bots ran at 37.3% completion for a day and the CLI, the server and the
;; gateway all looked healthy, because they were. `murakumo-main` is an alias;
;; the fleet had repointed it at a deployment calling itself
;; `qwen3.8-27b-throughput-b70`, and `cloud-itonami-app` still admitted only
;; `Qwen3.8-27B-Q4_K_M.gguf`. Every turn generated fine over HTTP 200 and was
;; then refused by `assert-response-model!` -- 25 of the last 50 resident runs
;; came back `model-mismatch`. Nothing said so anywhere a person would look.
;;
;; The fix enumerated the measured names, which is a floor and not a fix: the
;; next redeploy that moves the suffix reproduces the outage exactly. This
;; detector exists so that the next time costs one session-start line instead
;; of a day.
;;
;; ## Why it probes rather than reads
;;
;; Three sources disagreed at once that day, and only one of them was right:
;;
;;   POST /v1/chat/completions  -> .model = "qwen3.8-27b-throughput-b70"   <- true
;;   GET  /infer/models/murakumo-main -> alias-for = "qwen3.8-27b"
;;   GET  /infer/models               -> listed neither name
;;
;; So reading the registry is not enough to answer the question this asks.
;;
;; ## Why it probes MORE THAN ONCE
;;
;; The alias reports `parallel: 2`, and the two slots do not label their
;; responses the same way. Measured 2026-08-27 06:01Z, three calls a second
;; apart: `murakumo-main`, `murakumo-main`, `qwen3.8-27b-throughput-b70`.
;;
;; That is why 37.3% of turns completed rather than none of them -- turns that
;; landed on the slot echoing the alias were admitted and turns that landed on
;; the other were refused, at roughly the traffic split. It is also why a
;; single probe is worthless here: it reports clean half the time while half of
;; production is being thrown away. Every distinct name seen is checked, and
;; the sample size is printed so a reader can see how hard it looked.
;;
;; `alias-for` is still checked, one severity lower, because the registry
;; moving is an early warning that the served name is about to.
;;
;; ## What it refuses to do
;;
;; It never prints a clean result it did not measure. No accepted set, no API
;; key, or a probe that did not return a model name all exit 2 -- neither 0
;; nor 1 -- because "could not ask" and "asked and it was fine" must not leave
;; the same trace. That is the failure class this whole detector is about.

(ns verify-model-alias-admission
  (:require ["node:fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def alias-model "murakumo-main")
(def registry-url "https://api.murakumo.cloud/infer/models/murakumo-main")
(def chat-url "https://api.murakumo.cloud/v1/chat/completions")
(def key-path (str (.-HOME js/process.env) "/.itonami/murakumo-api-key"))
(def resident-config (str (.-HOME js/process.env) "/.cloud-itonami/data/config.edn"))
(def shipped-defaults
  "orgs/cloud-itonami/cloud-itonami-app/resources/cloud-itonami-app.defaults.edn")

(defn- slurp* [p]
  (try (.toString (fs/readFileSync p)) (catch :default _ nil)))

(defn- murakumo-provider [config]
  (->> (:providers config)
       (filter #(= "murakumo" (:id %)))
       first))

(defn- accepted-set
  "The set the app will admit for the alias, and where it came from.

  The resident override wins when it declares one, exactly as the app's own
  config merge does. A file that exists but declares nothing is not an answer:
  it falls through rather than being read as an empty set, because an empty
  set would make every served name a finding and look like a detection."
  []
  (let [read-one (fn [p label]
                   (when-let [text (slurp* p)]
                     (when-let [set* (some-> (try (edn/read-string text)
                                                  (catch :default _ nil))
                                             murakumo-provider
                                             :accepted-response-models
                                             (get alias-model))]
                       (when (seq set*) [set* label]))))]
    (or (read-one resident-config "resident config.edn")
        (read-one shipped-defaults "shipped defaults.edn"))))

(defn- fetch-json [url opts timeout-ms]
  (let [ctl (js/AbortController.)
        timer (js/setTimeout #(.abort ctl) timeout-ms)]
    (-> (js/fetch url (clj->js (assoc opts :signal (.-signal ctl))))
        (.then (fn [r] (.then (.text r) (fn [t] {:status (.-status r) :body t}))))
        (.catch (fn [e] {:status nil :error (str (.-message e))}))
        (.finally (fn [_] (js/clearTimeout timer))))))

(defn- json-get [body k]
  (try (aget (js/JSON.parse body) k) (catch :default _ nil)))

(defn- finding! [sev k detail]
  (println (str "FINDING\t" sev "\t" k "\t" detail)))

(defn- refuse! [why]
  (println (str "REFUSED\t" why))
  (set! (.-exitCode js/process) 2))

(def probe-count
  "How many times to ask. The alias has two slots that label differently, so
  one call answers about one slot. Five keeps the chance of missing a 50/50
  split under 4% while staying inside a session-start budget."
  5)

(defn- judge
  "Pure, so --self-test exercises the same code the live path does.

  `served` is the SET of names observed, not one name: the question is whether
  every slot behind the alias is admitted, and asking once cannot answer it."
  [{:keys [accepted served alias-for declared]}]
  (let [refused (sort (remove #(contains? accepted %) served))]
    (cond-> []
      (seq refused)
      (conj [:fail "model-alias-served-not-admitted"
             (str alias-model " is served by " (str/join ", " (map pr-str refused))
                  ", which the app will not admit. Turns landing on "
                  (if (= 1 (count refused)) "that slot" "those slots")
                  " generate and are then refused at admission. Observed: "
                  (str/join ", " (sort served)) ". Admitted: "
                  (str/join ", " (sort accepted)))])

      (and alias-for (not (contains? accepted alias-for)))
      (conj [:warn "model-alias-registry-not-admitted"
             (str alias-model " reports alias-for=" (pr-str alias-for)
                  ", which the app will not admit. The registry has moved;"
                  " the served name usually follows. Admitted: "
                  (str/join ", " (sort accepted)))])

      ;; A pool nobody declared is a pool every client has to discover the
      ;; expensive way. Measured 2026-08-27: local-murakumo PR #161 pooled the
      ;; alias across two owned origins the same day, the registry record still
      ;; read "Two production slots with 262144 tokens per slot" from before the
      ;; pool, and cloud-itonami-app found out by refusing half its own answers.
      ;; While this stands, an accepted set can only be maintained by hand.
      (let [undeclared (sort (remove (set declared) served))]
        (and (> (count served) 1) (seq undeclared)))
      (conj [:warn "model-alias-pool-undeclared"
             (str alias-model " answers to " (count served) " different names but the"
                  " registry does not declare them: " (str/join ", " (sort served))
                  (if (seq declared)
                    (str " (declared: " (str/join ", " (sort declared)) ")")
                    " (no served-model-names field)")
                  ". Until the registry names its pool, every client's accepted set"
                  " is hand-maintained and drifts on the next redeploy.")]))))

(defn- self-test! []
  (let [cases [{:name "a name outside the set is a fail"
                :in {:accepted #{"murakumo-main"} :served #{"qwen3.8-27b-throughput-b70"}
                     :alias-for "murakumo-main"}
                :want #{"model-alias-served-not-admitted"}}
               ;; Two names and nothing declaring them, so the pool warning is
               ;; correct here too -- the fail is what must not be swallowed.
               {:name "one good slot does not excuse a bad one"
                :in {:accepted #{"murakumo-main"} :served #{"murakumo-main" "qwen3.8-27b-throughput-b70"}
                     :alias-for "murakumo-main"}
                :want #{"model-alias-served-not-admitted" "model-alias-pool-undeclared"}}
               {:name "registry drift alone is a warn"
                :in {:accepted #{"murakumo-main" "served-x"} :served #{"served-x"}
                     :alias-for "qwen3.8-27b"}
                :want #{"model-alias-registry-not-admitted"}}
               {:name "two names the registry never declared is a warn"
                :in {:accepted #{"murakumo-main" "served-x"} :served #{"murakumo-main" "served-x"}
                     :alias-for "murakumo-main" :declared nil}
                :want #{"model-alias-pool-undeclared"}}
               {:name "a declared pool is not a finding"
                :in {:accepted #{"murakumo-main" "served-x"} :served #{"murakumo-main" "served-x"}
                     :alias-for "murakumo-main" :declared #{"murakumo-main" "served-x"}}
                :want #{}}
               {:name "one name needs no pool declaration"
                :in {:accepted #{"murakumo-main"} :served #{"murakumo-main"}
                     :alias-for "murakumo-main" :declared nil}
                :want #{}}]
        bad (for [{:keys [name in want]} cases
                  :let [got (set (map second (judge in)))]
                  :when (not= got want)]
              (str "  " name ": wanted " (pr-str want) " got " (pr-str got)))]
    (doseq [line bad] (println line))
    (println (str "SELF-TEST\t" (- (count cases) (count bad)) "/" (count cases) " cases"))
    (if (seq bad) 1 0)))

(defn- report! [accepted source served alias-for declared]
  (let [findings (judge {:accepted accepted :served served
                         :alias-for alias-for :declared declared})]
    (println (str "SCANNED\t1 alias\t" alias-model
                  "\tprobes=" probe-count
                  "\tserved=" (str/join "," (sort served))
                  "\talias-for=" (or alias-for "(unreadable)")
                  "\tdeclared=" (if (seq declared) (str/join "," (sort declared)) "(none)")
                  "\tadmitted=" (count accepted)
                  "\tfrom=" source))
    (doseq [[sev k detail] findings]
      (finding! (name sev) k detail))
    (when (some #(= :fail (first %)) findings)
      (set! (.-exitCode js/process) 1))))

(defn- chat-once [api-key]
  (fetch-json chat-url
              {:method "POST"
               :headers {"content-type" "application/json"
                         "authorization" (str "Bearer " api-key)}
               :body (js/JSON.stringify
                      (clj->js {:model alias-model
                                :max_tokens 1
                                :messages [{:role "user" :content "hi"}]}))}
              90000))

(defn- probe! [accepted source api-key]
  ;; `Promise.all` resolves to a JS array whose elements are the ClojureScript
  ;; maps `fetch-json` returns. Do NOT `js->clj` it: that leaves the maps alone
  ;; while the reader here starts asking for string keys, every status reads
  ;; nil, and the detector refuses with "unmeasurable" against a gateway that
  ;; answered fine. Measured on the first live run of this script.
  (-> (js/Promise.all
       (into-array (cons (fetch-json registry-url {:method "GET"} 20000)
                         (repeatedly probe-count #(chat-once api-key)))))
      (.then (fn [results]
               (let [registry (aget results 0)
                     chats (map #(aget results %) (range 1 (inc probe-count)))
                     served (->> chats
                                 (filter #(= 200 (:status %)))
                                 (keep #(json-get (:body %) "model"))
                                 set)
                     alias-for (when (= 200 (:status registry))
                                 (json-get (:body registry) "alias-for"))
                     declared (when (= 200 (:status registry))
                                (some-> (json-get (:body registry) "served-model-names")
                                        js->clj set))]
                 (if (seq served)
                   (report! accepted source served alias-for declared)
                   (refuse! (str "no probe named a served model for " alias-model
                                 " in " probe-count " attempts (statuses "
                                 (str/join "," (map #(or (:status %) "-") chats))
                                 ") -- admission is unmeasurable from here"))))))))

(defn- arg-after [argv flag]
  (second (drop-while #(not= flag %) argv)))

(defn- run-live! [argv]
  ;; `--accepted` exists so this detector can be shown FAILING against the real
  ;; gateway, not only in --self-test. A check nobody has watched reject
  ;; something is a check nobody can trust; CLAUDE.md requires seeing exit 1 on
  ;; a broken copy and exit 0 unmodified before it counts as landed. It
  ;; overrides only what the app WOULD admit -- never what the gateway answers.
  (let [override (some-> (arg-after argv "--accepted")
                         (str/split #",")
                         (->> (map str/trim) (remove str/blank?) set)
                         not-empty)
        pair (accepted-set)
        accepted (or override (first pair))
        source (if override "--accepted (verification override)" (second pair))
        api-key (some-> (slurp* key-path) str/trim not-empty)]
    (cond
      (nil? accepted)
      (refuse! (str "no accepted-response-models for " alias-model
                    " in " resident-config " or " shipped-defaults
                    " -- cannot say whether the served model would be admitted"))

      (nil? api-key)
      (refuse! (str "no murakumo API key at " key-path
                    " -- the served model name is unmeasurable, which is not the same as fine"))

      :else
      (probe! accepted source api-key))))

(let [argv (vec (.slice js/process.argv 2))]
  (if (some #{"--self-test"} argv)
    (set! (.-exitCode js/process) (self-test!))
    (run-live! argv)))

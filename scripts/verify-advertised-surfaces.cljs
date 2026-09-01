#!/usr/bin/env nbb
;; verify-advertised-surfaces — a document that advertises an endpoint the
;; host does not actually serve.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-advertised-surfaces.cljs [--findings]
;;   nbb ... scripts/verify-advertised-surfaces.cljs --self-test
;;   nbb ... scripts/verify-advertised-surfaces.cljs --host x402.nexus
;;
;; ## The shape
;;
;; A discovery document is a promise: an agent reads it and calls what it
;; names. Nothing checks that the promise is kept, and the two ways it breaks
;; both look like success from the outside.
;;
;; Measured on x402.nexus, 2026-09-01, three times in one session:
;;
;;   1. Four endpoints were reported as already live because each answered
;;      200. They answered 200 because the worker served its index page for
;;      EVERY unrouted path. A nonsense path returned the same bytes. Only
;;      one of the four existed.
;;   2. /.well-known/mcp.json served a well-formed manifest advertising zero
;;      tools -- the renderer was called in the JSON-to-EDN direction, so it
;;      looked for string keys in an EDN map, found none, and returned an
;;      empty model rather than an error.
;;   3. A tool in that manifest reached its endpoint by fetching the worker's
;;      own origin. Cloudflare answered 522 and the tool returned that error
;;      text as its result, with a 200 around it.
;;
;; ## Why the negative control is the whole detector
;;
;; Checking that an advertised path answers is not enough, and the first
;; measurement above is why: every path answered. What separates a served
;; path from an unserved one is that the served path's response differs from
;; what the host says to a path nobody could have advertised. So the control
;; is fetched FIRST, and a path whose body matches the control byte for byte
;; is reported as unserved however healthy its status code looks.
;;
;; A 404 is not a finding by itself -- a host that says "no" is telling the
;; truth. It is a finding only when a document advertised that path.
;;
;; Cannot be a fleet gate: it reads live public documents and probes hosts
;; over the network, neither of which exists in a shipped tree.

(ns verify-advertised-surfaces
  (:require [clojure.string :as str]))

(def ^:private argv (vec (drop 2 (.-argv js/process))))
(defn- flag? [f] (boolean (some #{f} argv)))
(defn- opt [f] (second (drop-while #(not= f %) argv)))
(def ^:private findings? (flag? "--findings"))

(def ^:private default-hosts
  ;; Hosts whose discovery documents this workspace publishes. A host belongs
  ;; here once it serves /llms.txt or /openapi.json; probing a host that
  ;; publishes neither would measure nothing and report clean.
  ["x402.nexus"])

;; ---------------------------------------------------------------- extraction

(def ^:private method-re #"(?i)\b(GET|POST|PUT|PATCH|DELETE|ANY)\b[^A-Za-z0-9]{0,4}$")

(defn advertised-paths
  "Same-host endpoints a discovery document names, as {:path :method}.

  Three details, each of which produced a false finding when it was missing:

  - The method is read from the token in front of the URL. A document saying
    `POST /verify` promises nothing about GET, and probing GET reported a
    served endpoint as absent. Measured 2026-09-01: three of four findings in
    the detector's first live run were this.
  - A URL followed immediately by `<` or `{` is a shape, not an address. The
    regex stops at the placeholder and leaves a truncated prefix behind, so
    `/gateway/<seller>/<path>` became `/gateway/`, which no host serves. The
    character after the match decides.
  - Only this host's URLs count. Another host's outage is not this document's
    broken promise."
  [host body]
  (let [h (str/replace host #"^https?://" "")
        re (re-pattern (str "https?://" (str/replace h "." "\\.") "([^\\s\"'`)\\]<>{},]*)"))]
    (->> (loop [idx 0 acc []]
           (if-let [m (.exec (js/RegExp. (.-source re) "g")
                             (subs body idx))]
             (let [at (+ idx (.-index m))
                   whole (aget m 0)
                   after (get body (+ at (count whole)))
                   path (aget m 1)
                   before (subs body (max 0 (- at 12)) at)]
               (recur (+ at (count whole))
                      (if (or (#{"<" "{"} (str after))
                              (str/blank? path)
                              (= path "/"))
                        acc
                        (conj acc {:path (str/replace (if (str/starts-with? path "/") path (str "/" path))
                                                      #"[.,;:]+$" "")
                                   :method (if-let [mm (re-find method-re before)]
                                             (let [x (str/upper-case (second mm))]
                                               (if (= x "ANY") "GET" x))
                                             "GET")}))))
             acc))
         (remove #(re-find #"&lt;|&gt;|&#" (:path %)))
         ;; A fragment names a place inside a document, not a second document.
         ;; Probing `/#go-live` fetches `/` and then reports that it answered
         ;; what `/` answers, which is true and means nothing. Measured
         ;; 2026-09-01 on itonami.cloud: both findings in that run were this.
         (remove #(str/includes? (:path %) "#"))
         (group-by :path)
         ;; One probe per path. A path named with both GET and POST is probed
         ;; with the non-GET method, which is the one a GET would misreport.
         (map (fn [[_ vs]] (or (first (remove #(= "GET" (:method %)) vs)) (first vs))))
         (sort-by :path)
         vec)))

(defn verdict
  "Given the control response and one advertised path's response, say what the
  host is doing with that path.

  `:same-as-control` is the one that reads as success from outside: a 200
  whose body is what an unadvertised path also gets."
  [control resp]
  (cond
    (nil? resp)                              :unreachable
    (= (:body resp) (:body control))         :same-as-control
    (>= (:status resp) 500)                  :server-error
    (= (:status resp) 404)                   :absent
    ;; 402 is the whole point of this host, and 400/422 from an endpoint handed
    ;; an empty body is that endpoint answering. Neither is an absent path.
    (>= (:status resp) 400)                  :refused
    :else                                    :served))

(def ^:private bad? #{:same-as-control :absent :server-error :unreachable})

;; --------------------------------------------------------------------- probe

(defn- fetch-text
  ([url timeout-ms] (fetch-text url timeout-ms "GET"))
  ([url timeout-ms method]
  (let [ctl (js/AbortController.)
        t (js/setTimeout #(.abort ctl) timeout-ms)
        init (if (= method "GET")
               #js {:signal (.-signal ctl) :redirect "follow"}
               ;; An empty JSON body: enough for the endpoint to answer, and
               ;; deliberately not enough to make it do anything. The probe
               ;; must not settle a payment to find out that /settle exists.
               #js {:signal (.-signal ctl) :redirect "follow" :method method
                    :headers #js {"content-type" "application/json"} :body "{}"})]
    (-> (js/fetch url init)
        (.then (fn [^js r] (-> (.text r) (.then (fn [b] {:status (.-status r) :body b})))))
        (.catch (fn [_] nil))
        (.finally #(js/clearTimeout t))))))

(defn- probe-host [host timeout-ms]
  (let [;; A full base URL is accepted so the discriminating run can point at a
        ;; local server. Without it this detector could only ever be exercised
        ;; against hosts it must not break on purpose.
        base (if (re-find #"^https?://" host) host (str "https://" host))
        nonce (str "/__control-" (.toString (js/Math.random) 36) "-not-a-route")]
    (-> (js/Promise.all
         #js [(fetch-text (str base nonce) timeout-ms)
              (fetch-text (str base "/llms.txt") timeout-ms)
              (fetch-text (str base "/openapi.json") timeout-ms)])
        (.then
         (fn [^js rs]
           (let [control (aget rs 0)
                 docs (remove nil? [(aget rs 1) (aget rs 2)])]
             (cond
               (nil? control) {:host host :refused "the control path could not be fetched"}
               (empty? docs) {:host host :refused "no discovery document answered"}
               :else
               (let [eps (->> docs
                              (mapcat #(advertised-paths host (:body %)))
                              (group-by :path)
                              (map (fn [[_ vs]] (or (first (remove #(= "GET" (:method %)) vs))
                                                    (first vs))))
                              (sort-by :path) vec)]
                 (-> (js/Promise.all
                      (clj->js (map #(fetch-text (str base (:path %)) timeout-ms (:method %)) eps)))
                     (.then (fn [^js rs2]
                              {:host host
                               :control control
                               :results (mapv (fn [e r] (assoc e :verdict (verdict control r)
                                                               :status (:status r)))
                                              eps (js->clj rs2))})))))))))))

;; ----------------------------------------------------------------- self-test

(defn- self-test []
  (let [ctl {:status 200 :body "INDEX"}
        cases [[(verdict ctl {:status 200 :body "INDEX"}) :same-as-control "a 200 identical to the control is not a served path"]
               [(verdict ctl {:status 200 :body "REAL"}) :served "a 200 that differs from the control is served"]
               [(verdict ctl {:status 404 :body "nope"}) :absent "an advertised path answering 404 is absent"]
               [(verdict ctl {:status 522 :body "error code: 522"}) :server-error "522 is the host failing, not serving"]
               [(verdict ctl nil) :unreachable "no response is not a pass"]
               [(mapv :path (advertised-paths "x402.nexus" "see [c](https://x402.nexus/catalog) and `https://x402.nexus/mcp`"))
                ["/catalog" "/mcp"] "markdown and backticked URLs are both read"]
               [(mapv :path (advertised-paths "x402.nexus" "ANY https://x402.nexus/gateway/&lt;seller&gt;/x"))
                [] "an escaped placeholder is a shape, not an address"]
               [(mapv :path (advertised-paths "x402.nexus" "ANY https://x402.nexus/gateway/<seller>/<path>"))
                [] "a raw placeholder truncates the URL; the prefix is not an address"]
               [(mapv (juxt :method :path) (advertised-paths "x402.nexus" "- `POST https://x402.nexus/verify` thin API"))
                [["POST" "/verify"]] "the method in front of the URL is the method to probe"]
               [(mapv (juxt :method :path)
                      (advertised-paths "x402.nexus" "GET https://x402.nexus/verify and POST https://x402.nexus/verify"))
                [["POST" "/verify"]] "a path named with two methods is probed with the one a GET would misreport"]
               [(mapv :path (advertised-paths "x402.nexus" "https://other.example/catalog"))
                [] "another host's paths are not this host's promise"]
               [(mapv :path (advertised-paths "itonami.cloud" "[go](https://itonami.cloud/#go-live)"))
                [] "a fragment is a place inside a document, not an address"]]
        fails (remove (fn [[got want _]] (= got want)) cases)]
    (doseq [[got want why] fails] (println "FAIL" why "\n  got " (pr-str got) "\n  want" (pr-str want)))
    (println (str "SELF-TEST\t" (- (count cases) (count fails)) "/" (count cases)))
    (if (seq fails) 1 0)))

;; ---------------------------------------------------------------------- main

(defn- report [hosts]
  (let [refused (filter :refused hosts)
        measured (remove :refused hosts)
        results (mapcat (fn [h] (map #(assoc % :host (:host h)) (:results h))) measured)
        bads (filter #(bad? (:verdict %)) results)]
    (println (str "SCANNED\t" (count results) "\tadvertised path(s) across "
                  (count measured) " host(s)"))
    (doseq [r refused]
      (println (str "REFUSED\t" (:host r) "\t" (:refused r))))
    (when findings?
      (doseq [b (sort-by (juxt :host :path) bads)]
        (println (str "FINDING\t" (:method b) " " (:host b) (:path b) "\t" (name (:verdict b))
                      "\tHTTP " (or (:status b) "-")
                      "\t" (case (:verdict b)
                             :same-as-control "advertised, but answers exactly what an unadvertised path answers"
                             :absent "advertised by this host's own discovery document, and absent"
                             :server-error "advertised, and the host is failing on it"
                             :unreachable "advertised, and no response came back")))))
    (cond
      (seq refused) 2
      (zero? (count results)) 2
      (seq bads) 1
      :else 0)))

(defn- main []
  (cond
    (flag? "--self-test") (js/Promise.resolve (self-test))
    :else
    (let [hosts (if-let [h (opt "--host")] [h] default-hosts)
          timeout-ms (js/parseInt (or (opt "--timeout-ms") "8000"))]
      (-> (js/Promise.all (clj->js (map #(probe-host % timeout-ms) hosts)))
          (.then (fn [^js rs] (report (js->clj rs :keywordize-keys true))))))))

(-> (main)
    (.then (fn [code] (set! (.-exitCode js/process) code)))
    (.catch (fn [e]
              (println (str "REFUSED\tthe detector itself failed: " (.-message e)))
              (set! (.-exitCode js/process) 2))))

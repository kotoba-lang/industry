(ns kotobase-auth-maturity.probe
  "Measure the kotobase.net authentication and authorization planes.

  Every value below is an HTTP status or a response body read from the live
  surface. Nothing here reads a document to decide whether something works:
  on 2026-08-29 two ADRs in this tree said `/ipni/v1/head` was unpublished
  while the live surface returned a signed advertisement chain, and one
  vocabulary note said no path used Biscuit while the datom plane had been
  refusing everything else since 2026-08-28. Documents go stale silently;
  a probe cannot.

  Run:
    npx --yes nbb --classpath 90-docs 90-docs/kotobase_auth_maturity/run.cljs

  ## :unknown is not a zero, and not a pass

  A request that could not be made is `:unknown`. The audit refuses to score
  an :unknown axis rather than counting silence either way. This is the one
  failure class this workspace names most often -- a check that could not run
  returning the same value as a check that ran and found nothing wrong."
  (:require [clojure.string :as str]
            ["child_process" :as cp]))

(def authn "https://auth.kotobase.net")
(def apex "https://kotobase.net")
(def query-surfaces ["sparql" "cypher" "graphql" "gremlin"])

(defn- sh
  "Run a command, returning trimmed stdout, or nil when it did not answer.
  nil becomes :unknown at the call sites -- never 0, never a pass."
  ([cmd] (sh cmd 40000))
  ([cmd timeout-ms]
   (try
     (str/trim (str (cp/execSync cmd #js {:encoding "utf8"
                                          :stdio #js ["pipe" "pipe" "pipe"]
                                          :timeout timeout-ms})))
     (catch :default _ nil))))

(defn- http
  "One request. Returns {:status long-or-:unknown :body string-or-nil}.

  The status is appended after a sentinel rather than taken from a second
  request, because a surface that rate-limits or rotates would otherwise be
  measured twice in two different states and reported as one."
  [{:keys [url method data headers]}]
  (let [hs (str/join " " (map #(str "-H " (pr-str %)) (or headers [])))
        m (if method (str "-X " method " ") "")
        d (if data (str "--data-binary " (pr-str data) " ") "")
        out (sh (str "curl -sS --max-time 25 " m d hs
                     " -w '\\n<<<STATUS>>>%{http_code}' " (pr-str url)))]
    (if-not out
      {:status :unknown :body nil}
      (let [i (str/last-index-of out "<<<STATUS>>>")
            code (when i (subs out (+ i 12)))
            body (if i (subs out 0 i) out)]
        {:status (if (and code (re-matches #"\d{3}" code) (not= "000" code))
                   (js/parseInt code 10)
                   :unknown)
         :body (str/trim body)}))))

(defn- names?
  "Does a response body name this credential class? Case-insensitive, and
  nil-safe: a body we could not read answers :unknown, not false. `false`
  here means 'read it, the word is absent', which is a real finding."
  [body word]
  (if (nil? body) :unknown (boolean (str/includes? (str/lower-case body) (str/lower-case word)))))

(defn capabilities-name-credentials?
  "Does the capability document say which credential to present?

  Structure, not a word. This axis used to be `(names? body \"credential\")`,
  and on 2026-08-29 a document that listed every accepted scheme under
  `authentication.accepted` -- Bearer, AWS4-HMAC-SHA256, CACAO, plus Biscuit
  under `not_accepted` -- still scored false, because it happened not to spell
  the English word. A substring search over a JSON body cannot tell a document
  that answers the question from one that merely mentions the topic, and it
  gets BOTH directions wrong: prose saying `no credential is required` would
  have scored true.

  So: parse it, and require a non-empty `authentication.accepted` whose entries
  carry a `scheme`. Unparseable or absent body stays `:unknown` -- a document we
  could not read is not a document that failed."
  [body]
  (if (nil? body)
    :unknown
    (try
      (let [d (js->clj (js/JSON.parse body))
            accepted (get-in d ["authentication" "accepted"])]
        (boolean (and (sequential? accepted)
                      (seq accepted)
                      (every? #(and (map? %) (string? (get % "scheme"))
                                    (seq (get % "scheme")))
                              accepted))))
      (catch :default _ :unknown))))

(defn- present?
  "An endpoint is present when it answers anything except 404/405/:unknown.

  A named 4xx (`{\"error\":\"valid tenantId required\"}`) is the strongest
  evidence available without a credential: the route exists, parsed the
  request, and refused it for a reason it is willing to say."
  [status]
  (cond
    (= :unknown status) :unknown
    (contains? #{404 405} status) false
    :else true))

(defn probe []
  (let [health (http {:url (str authn "/health")})
        pk-login (http {:url (str authn "/v1/passkey/login/options")
                        :method "POST" :data "{}"
                        :headers ["content-type: application/json"]})
        pk-reg (http {:url (str authn "/v1/passkey/register/options")
                      :method "POST" :data "{}"
                      :headers ["content-type: application/json"]})
        siwe-opt (http {:url (str authn "/v1/siwe/options")
                        :method "POST" :data "{}"
                        :headers ["content-type: application/json"]})
        siwe-ver (http {:url (str authn "/v1/siwe/verify")
                        :method "POST" :data "{}"
                        :headers ["content-type: application/json"]})
        bisc-x (http {:url (str authn "/v1/biscuit/token")
                      :method "POST" :data "{}"
                      :headers ["content-type: application/json"]})
        bisc-o (http {:url (str authn "/v1/biscuit/token")
                      :method "POST" :data "{}"
                      :headers ["content-type: application/json"
                                (str "Origin: " authn)]})
        signin (http {:url (str authn "/sign-in")})
        pk-js (http {:url (str authn "/passkey.js")})
        w-js (http {:url (str authn "/wallet.js")})
        datom (http {:url (str apex "/xrpc/ai.gftd.apps.kotobase.datomic.q")
                     :method "POST" :data "{\"query\":\"[:find ?e :where [?e :a ?v]]\"}"
                     :headers ["content-type: application/json"]})
        ;; The same request carrying a syntactically-shaped Biscuit header.
        ;; If the surface's refusal is identical with and without it, the
        ;; surface does not read the header at all -- which is the claim,
        ;; demonstrated rather than asserted.
        surf (into {}
                   (for [h query-surfaces]
                     [h {:bare (http {:url (str "https://" h ".kotobase.net/sparql")
                                      :method "POST" :data "{}"
                                      :headers ["content-type: application/json"]})
                         :with-biscuit (http {:url (str "https://" h ".kotobase.net/sparql")
                                              :method "POST" :data "{}"
                                              :headers ["content-type: application/json"
                                                        "Authorization: Biscuit EnwAAA"]})}]))
        caps (http {:url "https://graphql.kotobase.net/.well-known/kotobase-capabilities"})
        one (get surf "sparql")]
    {:probe/at (.toISOString (js/Date.))
     :probe/method "live HTTP, no credential held by the prober"

     ;; --- authentication foundation: passkey + smart contract ---
     :authn/health (:status health)
     :authn/passkey-login-options (:status pk-login)
     :authn/passkey-challenge-issued (names? (:body pk-login) "challenge")
     :authn/passkey-rp-id (names? (:body pk-login) "rpid")
     :authn/passkey-register-present (present? (:status pk-reg))
     :authn/passkey-register-reason-named (names? (:body pk-reg) "required")
     :authn/siwe-options-present (present? (:status siwe-opt))
     :authn/siwe-verify-present (present? (:status siwe-ver))
     :authn/signin-ships-passkey (and (= 200 (:status signin))
                                      (= true (names? (:body signin) "passkey.js")))
     :authn/signin-ships-wallet (and (= 200 (:status signin))
                                     (= true (names? (:body signin) "wallet.js")))
     :authn/passkey-module (:status pk-js)
     :authn/wallet-module (:status w-js)

     ;; --- authorization: biscuit ---
     :biscuit/issuance-present (present? (:status bisc-x))
     :biscuit/issuance-same-origin-guarded (= 403 (:status bisc-x))
     :biscuit/issuance-reason-named (names? (:body bisc-o) "tenant")
     :biscuit/datom-plane-status (:status datom)
     :biscuit/datom-plane-names-biscuit (names? (:body datom) "biscuit")
     :biscuit/surfaces-naming-biscuit
     (let [vs (map (fn [h] (names? (:body (:bare (get surf h))) "biscuit")) query-surfaces)]
       (cond (some #(= :unknown %) vs) :unknown
             :else (count (filter true? vs))))
     :biscuit/surfaces-total (count query-surfaces)
     :biscuit/surface-header-changes-answer
     (let [a (:bare one) b (:with-biscuit one)]
       (cond (or (= :unknown (:status a)) (= :unknown (:status b))) :unknown
             :else (not= [(:status a) (:body a)] [(:status b) (:body b)])))
     :biscuit/surface-refusal-text (:body (:bare one))

     ;; --- discovery: can a stranger learn which credential to present? ---
     :discovery/capabilities-status (:status caps)
     :discovery/capabilities-names-credentials (capabilities-name-credentials? (:body caps))}))

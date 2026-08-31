(ns auth-plane-integration.probe
  "Measure whether the six apexes implement ONE integrated auth design.

  The design being measured is the union of two accepted ADRs that have never
  been stated together:

    ADR-2608039950  one authority, many apexes -- an apex does not mint a
                    second identity; it gets a custom auth hostname on the
                    shared authn worker.
    ADR-2608291700  passkey and smart contract authenticate; Biscuit
                    authorizes; kotoba-lang/authority stays the one decider.

  Nothing here reads a document to decide whether something works. Every
  value is an HTTP status or a response body from the live surface. The
  reason is on the record: on 2026-08-29 two ADRs in this tree described a
  surface as unpublished while it was serving a signed chain, and one
  vocabulary note said no path used Biscuit while a plane had been refusing
  everything else for a day.

  ## Three values, not two

  `:unknown` is a request that could not be made -- a timeout, a TLS failure.
  It is never scored. A host that does not resolve is NOT unknown: that is a
  measurement, and it means the apex has no such surface. curl's exit code
  separates the two, which `%{http_code}` alone cannot: both answer 000."
  (:require [clojure.string :as str]
            ["child_process" :as cp]))

;; Candidate planes were chosen by probing for a surface that REFUSES. An
;; apex whose only public answer is a landing document has no capability
;; gate to measure, and that is the finding, not a gap in the probe.
(def apexes
  [{:apex "itonami.cloud"
    :auth "https://auth.itonami.cloud"
    :planes [{:url "https://itonami.cloud/api/authority" :method "GET"}]}
   {:apex "murakumo.cloud"
    :auth "https://auth.murakumo.cloud"
    :planes [{:url "https://api.murakumo.cloud/v1/cdci/jobs" :method "GET"}]}
   ;; Four planes, because this apex HAS four and measuring one of them was
   ;; under-measuring from the start. Discovered 2026-08-31: the three query
   ;; surfaces were wired to read a Biscuit and this probe reported no change,
   ;; because the one endpoint it asked was the datom plane -- a different
   ;; Worker. An axis that samples one surface answers about that surface and
   ;; is quoted as answering about the apex. Adding planes can only lower a
   ;; score, never raise one: `plane-reads-capability` is true only when EVERY
   ;; listed plane reads the header.
   {:apex "kotobase.net"
    :auth "https://auth.kotobase.net"
    :planes [{:url "https://kotobase.net/xrpc/ai.gftd.apps.kotobase.datomic.q"
              :method "POST" :data "{\"query\":\"[:find ?e]\"}"}
             {:url "https://sparql.kotobase.net/sparql" :method "POST" :data "{}"}
             {:url "https://gremlin.kotobase.net/gremlin" :method "POST" :data "{}"}
             {:url "https://graphql.kotobase.net/graphql" :method "POST" :data "{}"}]}
   {:apex "x402.nexus"
    :auth "https://auth.x402.nexus"
    :planes [{:url "https://x402.nexus/admin/settlements/__probe__" :method "GET"}]}
   {:apex "isekai.network"
    :auth "https://auth.isekai.network"
    :planes [{:url "https://isekai.network/api/fork" :method "POST" :data "{}"}]}
   {:apex "aozora.app"
    :auth "https://auth.aozora.app"
    :planes [{:url "https://pds.aozora.app/xrpc/com.atproto.server.getSession"
              :method "GET"}]}])

;; The shared contract, and the shapes a second implementation has actually
;; used. `one-authority` is true only when the FIRST one answered.
(def shared-passkey-path "/v1/passkey/login/options")
(def alternate-passkey-paths ["/api/passkey/login/begin" "/passkey/login/options"])

(def auth-words ["biscuit" "bearer" "cacao" "credential" "authorization"
                 "unauthorized" "auth" "token" "sigv4"])

(defn- curl
  "One request. Returns {:status :body :curl-exit}.

  :curl-exit 6 (could not resolve) and 7 (could not connect) are FACTS about
  the surface. Everything else that produced no status is :unknown."
  [{:keys [url method data headers]}]
  (let [hs (str/join " " (map #(str "-H " (pr-str %)) (or headers [])))
        m (if method (str "-X " method " ") "")
        d (if data (str "--data-binary " (pr-str data) " ") "")
        cmd (str "curl -sS --max-time 20 " m d hs
                 " -w '\\n<<<STATUS>>>%{http_code}' " (pr-str url)
                 " 2>/dev/null; printf '<<<EXIT>>>%s' \"$?\"")
        out (try (str (cp/execSync cmd #js {:encoding "utf8"
                                            :stdio #js ["pipe" "pipe" "pipe"]
                                            :timeout 40000}))
                 (catch :default _ nil))]
    (if-not out
      {:status :unknown :body nil :curl-exit :unknown}
      (let [ei (str/last-index-of out "<<<EXIT>>>")
            exit (when ei (js/parseInt (subs out (+ ei (count "<<<EXIT>>>"))) 10))
            head (if ei (subs out 0 ei) out)
            si (str/last-index-of head "<<<STATUS>>>")
            code (when si (subs head (+ si 12)))]
        {:status (if (and code (re-matches #"\d{3}" code) (not= "000" code))
                   (js/parseInt code 10)
                   :unknown)
         :body (when si (str/trim (subs head 0 si)))
         :curl-exit exit}))))

(defn- absent?
  "Did the request establish that there is no such host?"
  [{:keys [curl-exit status]}]
  (and (= :unknown status) (contains? #{6 7} curl-exit)))

(defn- names-any? [body words]
  (if (nil? body)
    :unknown
    (boolean (some #(str/includes? (str/lower-case body) %) words))))

(defn- normalise
  "Strip what changes between two identical requests -- timestamps, request
  ids, nonces -- so that `differs?` reports a difference in BEHAVIOUR.

  Without this the axis reports success for any surface that stamps its
  answer, which is the shape of a test that passes for the wrong reason."
  [body]
  (when body
    (-> body
        (str/replace #"[0-9a-fA-F]{16,}" "H")
        (str/replace #"\d+" "N")
        (str/trim))))

(defn- challenge?
  "A ceremony is live when the answer carries a challenge AND an rpId. Either
  alone is a document about a ceremony, not one."
  [{:keys [status body]}]
  (cond
    (= :unknown status) :unknown
    (nil? body) :unknown
    :else (boolean (and (= 200 status)
                        (str/includes? (str/lower-case body) "challenge")
                        (str/includes? (str/lower-case body) "rpid")))))

(defn- rp-id [body]
  (when body
    (second (re-find #"(?i)\"rpid\"\s*:\s*\"([^\"]+)\"" body))))

(defn refusal?
  "Did this answer refuse for want of a credential?

  A 200 is never a refusal. Reading an auth word out of a SUCCESS body is how
  a landing document that happens to contain the word \"authority\" gets
  scored as a capability gate -- measured on itonami.cloud, 2026-08-31, where
  an unauthenticated 200 landing JSON scored `refusal-names-credential` true.
  Public so the selftest can pin that literal case."
  [status body]
  (cond
    (= :unknown status) :unknown
    (contains? #{401 403} status) true
    (< status 400) false
    :else (names-any? body ["unauthorized" "credential" "authorization"])))

(defn- probe-plane
  "Measure one plane twice: bare, and with a syntactically-shaped Biscuit."
  [plane]
  (let [bare (curl (assoc plane :headers ["content-type: application/json"]))
        with (curl (assoc plane :headers ["content-type: application/json"
                                          "Authorization: Biscuit EnwAAA"]))]
    {:url (:url plane)
     :bare bare
     :with with
     :reads (cond
              (or (= :unknown (:status bare)) (= :unknown (:status with))) :unknown
              :else (not= [(:status bare) (normalise (:body bare))]
                          [(:status with) (normalise (:body with))]))}))

(defn- probe-apex [{:keys [apex auth planes]}]
  (let [shared (curl {:url (str auth shared-passkey-path) :method "POST" :data "{}"
                      :headers ["content-type: application/json"]})
        shared-live (challenge? shared)
        alts (when-not (true? shared-live)
               (into {} (for [p alternate-passkey-paths]
                          [p (curl {:url (str auth p) :method "POST" :data "{}"
                                    :headers ["content-type: application/json"]})])))
        ;; An alternate shape counts as "a ceremony exists here" when it
        ;; answers anything other than 404/405/absent -- including a 503 that
        ;; names its own misconfiguration, which is presence, not liveness.
        alt-present (when alts
                      (boolean (some (fn [[_ r]]
                                       (and (not (absent? r))
                                            (not= :unknown (:status r))
                                            (not (contains? #{404 405} (:status r)))))
                                     alts)))
        alt-live (when alts
                   (boolean (some (fn [[_ r]] (true? (challenge? r))) alts)))
        mint (curl {:url (str auth "/v1/biscuit/token") :method "POST" :data "{}"
                    :headers ["content-type: application/json"]})
        measured (mapv probe-plane planes)
        ;; The first plane is the one the credential-shape axes are read from:
        ;; the others say whether the apex is consistent, not what it demands.
        bare (:bare (first measured))
        with (:with (first measured))
        refuses (refusal? (:status bare) (:body bare))]
    {:apex apex

     ;; --- raw, so a reader can re-derive every axis below ---
     :raw/shared-passkey-status (:status shared)
     :raw/shared-passkey-rp-id (rp-id (:body shared))
     :raw/alternate-passkey (when alts
                              (into {} (for [[p r] alts] [p (:status r)])))
     :raw/auth-host-absent? (absent? shared)
     :raw/mint-status (:status mint)
     :raw/plane-status (:status bare)
     :raw/plane-body (:body bare)
     :raw/plane-body-with-biscuit (:body with)
     :raw/planes (mapv (fn [m] {:url (:url m) :status (:status (:bare m)) :reads (:reads m)})
                       measured)
     :raw/planes-reading (str (count (filter #(true? (:reads %)) measured))
                              "/" (count measured))

     ;; --- the five invariants, as scorable axes ---
     :controller-live (cond
                        (absent? shared) false
                        (true? shared-live) true
                        (= :unknown shared-live) (if (true? alt-live) true :unknown)
                        :else (boolean alt-live))
     :one-authority (cond
                      (absent? shared) false
                      (true? shared-live) true
                      (= :unknown shared-live) :unknown
                      ;; A ceremony that answers somewhere else is a second
                      ;; implementation whether or not it currently works.
                      (true? alt-present) false
                      :else false)
     :capability-issuance (cond
                            (absent? mint) false
                            (= :unknown (:status mint)) :unknown
                            (contains? #{404 405} (:status mint)) false
                            ;; present AND guarded: an unguarded mint is not
                            ;; a capability issuer, it is a token faucet.
                            :else (= 403 (:status mint)))
     ;; EVERY listed plane, not the first one. An apex whose query surfaces
     ;; read the header while its datom plane does not has not finished.
     :plane-reads-capability (cond
                               (some #(= :unknown (:reads %)) measured) :unknown
                               :else (every? #(true? (:reads %)) measured))
     :refusal-names-credential (cond
                                 (= :unknown refuses) :unknown
                                 (false? refuses) false
                                 :else (names-any? (:body bare) auth-words))
     :auth-failure-status (cond
                            (= :unknown refuses) :unknown
                            (false? refuses) false
                            :else (contains? #{401 403} (:status bare)))}))

(defn probe []
  {:probe/at (.toISOString (js/Date.))
   :probe/method "live HTTP, no credential held by the prober"
   :probe/design ["adr-2608039950-ninsho-one-authority-many-apexes"
                  "adr-2608291700-passkey-and-smart-contract-authenticate-biscuit-authorizes"]
   :apexes (mapv probe-apex apexes)})

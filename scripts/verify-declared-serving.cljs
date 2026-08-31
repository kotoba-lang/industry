#!/usr/bin/env nbb
;; verify-declared-serving — a model catalog that says `serving` about an
;; endpoint that does not answer.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-declared-serving.cljs [--findings]
;;   nbb ... scripts/verify-declared-serving.cljs --self-test
;;   nbb ... scripts/verify-declared-serving.cljs --catalog <url> --timeout-ms 8000
;;
;; ## Why this is a detector and not a change to the read path
;;
;; `local-murakumo.model-status` already thought about this and decided against
;; the obvious fix, in its own docstring:
;;
;;   Probing the fleet on every read is deliberately NOT the fix. It would make
;;   the registry unanswerable exactly when the fleet is down, which is when it
;;   is most needed. [...] Comparing a declaration against an actual probe
;;   belongs to a detector that can fail, not to a read.
;;
;; That reasoning holds. What was missing is the second half: the detector it
;; names did not exist, so nothing ever compared the declaration to the world.
;;
;; The gap the read path structurally cannot close: `annotate` downgrades a
;; declaration once it is older than seven days, which cannot catch an endpoint
;; that died on day one. Measured 2026-08-31 -- `murakumo-main` served
;; `status: serving`, `verdict: fresh`, `age_days: 6`, while its declared
;; endpoint `infer.murakumo.cloud/health` answered 404 and every request to it
;; was failing. Six is less than seven, so the read path was working exactly as
;; designed and still could not say so.
;;
;; ADR-2608270230 recorded the same shape lasting eleven days.
;;
;; ## What it flags, and what it deliberately does not
;;
;; Only a record whose OWN declaration is `serving` and whose OWN endpoint does
;; not answer. A record declaring `unverified` is already telling the truth and
;; is not a finding, however dead its endpoint is -- flagging it would punish
;; the honest state and train a reader to ignore the output.
;;
;; A catalog that cannot be fetched is `UNREADABLE` and exit 2, never a clean
;; run: a detector for silent staleness must not itself go silent.

(ns verify-declared-serving
  (:require [clojure.string :as str]))

(def ^:private argv (vec (drop 2 (.-argv js/process))))
(defn- flag? [f] (boolean (some #{f} argv)))
(defn- opt [f d] (let [i (.indexOf argv f)] (if (neg? i) d (get argv (inc i) d))))

(def ^:private findings? (flag? "--findings"))
(def ^:private self-test? (flag? "--self-test"))
(def ^:private catalog-url (opt "--catalog" "https://api.murakumo.cloud/infer/models"))
(def ^:private timeout-ms (js/parseInt (opt "--timeout-ms" "8000") 10))

;; ── pure: what counts as a claim, and what counts as an answer ─────────

(defn declares-serving?
  "Only `serving` is a claim about the present. Every other value -- including
   `unverified`, which is the honest one -- is already saying it does not know."
  [record]
  (= "serving" (str/trim (str (or (get record "status") "")))))

(defn health-url
  "The health face of a chat-completions endpoint, or nil when the record has
   no endpoint to check. nil is NOT a pass: the caller counts it separately."
  [record]
  (let [e (str/trim (str (or (get record "endpoint") "")))]
    (when-not (str/blank? e)
      (str/replace e #"/v1/chat/completions/?$" "/health"))))

(defn answered?
  "A health probe answered if it returned a 2xx. A 404 is the case that started
   this: the host resolves, TLS completes, Cloudflare replies -- and there is no
   service behind it. Treating any HTTP response as `alive` is how a dead origin
   passes a liveness check."
  [status]
  (and (number? status) (<= 200 status 299)))

;; ── host ───────────────────────────────────────────────────────────────

(defn- fetch-json [url]
  (-> (js/fetch url #js {:signal (.timeout js/AbortSignal timeout-ms)})
      (.then (fn [r] (if (.-ok r) (.json r)
                         (js/Promise.reject (js/Error. (str "HTTP " (.-status r)))))))
      (.then #(js->clj %))))

(defn- probe-status [url]
  (-> (js/fetch url #js {:signal (.timeout js/AbortSignal timeout-ms)})
      (.then (fn [r] {:status (.-status r)}))
      (.catch (fn [e] {:status nil :error (str (.-message e))}))))

;; ── self-test: both directions, or this is theatre ─────────────────────

(defn- self-test! []
  (let [serving {"id" "murakumo-main" "status" "serving"
                 "endpoint" "https://infer.murakumo.cloud/v1/chat/completions"}
        honest  {"id" "gemma-4-12b-it" "status" "unverified"
                 "endpoint" "https://infer.murakumo.cloud/v1/chat/completions"}
        no-ep   {"id" "murakumo-edge" "status" "serving"}
        checks
        [["flags a serving claim" (declares-serving? serving) true]
         ["silent on an honest unverified record" (declares-serving? honest) false]
         ["derives the health face" (health-url serving)
          "https://infer.murakumo.cloud/health"]
         ["no endpoint yields nil, not a pass" (health-url no-ep) nil]
         ["2xx is an answer" (answered? 200) true]
         ;; The one that matters: the live case was a 404 from a host that
         ;; resolves and terminates TLS. Any-response-is-alive would pass it.
         ["404 is NOT an answer" (answered? 404) false]
         ["503 is NOT an answer" (answered? 503) false]
         ["an unreachable probe is NOT an answer" (answered? nil) false]]]
    (doseq [[label got want] checks]
      (println (str "self-test  " (if (= got want) "ok  " "FAIL") "  " label
                    "  got=" (pr-str got) " want=" (pr-str want))))
    (if (every? (fn [[_ got want]] (= got want)) checks)
      (do (println "self-test OK — discriminates in both directions") 0)
      (do (println "self-test FAILED — a check that cannot fail is theatre") 1))))

(defn- report! [records claims results]
  (let [rs (js->clj results :keywordize-keys true)
        bad (filterv #(and (not (:no-endpoint %)) (not (answered? (:status %)))) rs)
        epless (filterv :no-endpoint rs)]
    ;; Evidence floor. A catalog with zero serving claims is a real, clean
    ;; answer; a catalog we could not read is not, and never reaches here.
    (println (str "SCANNED\t" (count records) "\trecord(s)"
                  "\tCLAIMS\t" (count claims)
                  (when (seq epless) (str "\tNO-ENDPOINT\t" (count epless)))))
    (doseq [b bad]
      (let [id (get-in b [:record "id"] "?")
            detail (str "declares status=serving while " (:url b) " answered "
                        (if (:status b) (str "HTTP " (:status b))
                            (str "no response (" (:error b) ")"))
                        ". The read path cannot catch this: annotate downgrades on"
                        " declaration AGE, so an endpoint that dies inside the"
                        " window stays `fresh`.")]
        (if findings?
          (println (str "FINDING\tfail\tdeclared-serving:" id "\t" detail))
          (println (str "  [fail] " id "\n      " detail)))))
    (doseq [e epless]
      (let [id (get-in e [:record "id"] "?")
            detail (str "declares status=serving and carries no endpoint, so the"
                        " claim cannot be checked by anything.")]
        (if findings?
          (println (str "FINDING\twarn\tdeclared-serving-no-endpoint:" id "\t" detail))
          (println (str "  [warn] " id " — " detail)))))
    (when (or (seq bad) (seq epless))
      (set! (.-exitCode js/process) 1))))

(defn- probe-all [records]
  (let [claims (filterv declares-serving? records)]
    (-> (js/Promise.all
         (clj->js (mapv (fn [r]
                          (if-let [u (health-url r)]
                            (.then (probe-status u) #(assoc % :record r :url u))
                            (js/Promise.resolve {:record r :no-endpoint true})))
                        claims)))
        (.then #(report! records claims %)))))

(defn- refuse! [e]
  (binding [*print-fn* *print-err-fn*]
    (println (str "CANNOT ANSWER — could not read the catalog at " catalog-url
                  ": " (.-message e)
                  ". Refusing to report a truthful registry from one that did"
                  " not answer.")))
  (set! (.-exitCode js/process) 2))

(defn -main []
  (if self-test?
    (set! (.-exitCode js/process) (self-test!))
    (-> (fetch-json catalog-url)
        (.then (fn [d] (probe-all (vec (if (sequential? d) d [d])))))
        (.catch refuse!))))

(-main)

#!/usr/bin/env nbb
(ns verify-endpoint-health
  "Call the endpoints this workspace promises to serve, and report what they
  actually return.

  ## Why this exists, and why it is not clever

  On 2026-08-18 two production paths were found dark by hand (ADR-2608180100):
  shinshi.club's chat had returned 502 to every visitor since a model id went
  stale, and api.murakumo.cloud's image endpoint had returned 503 for 20 days
  because a config var stayed unset after the tunnel it was waiting for existed.

  Two cleverer detectors were built first and thrown away, because measuring
  them showed neither would have caught either outage:

    A. probe every upstream origin the wrangler configs declare
       -> 118 of 164 dead, but chat's declared upstream was ALIVE (the dead
          thing was a model id inside source), and images had NO declared
          upstream to probe (the var was absent). Catches neither.

    B. flag *_URL vars a worker reads from env but its config never declares
       -> 25 hits in one repo, 24 of them secrets or deliberate. And the one
          that mattered was DELIBERATE for its first nine days. Declaration
          presence does not separate right from wrong.

  What both outages had in common was much simpler: **our own endpoint said what
  was wrong, in the body, to anyone who asked.** Nobody asked. This asks.

  The list of what to ask is hand-written (`manifest/endpoint-health.edn`),
  because which endpoints matter is a judgement. Deriving it was tried; see
  above.

  ## The four answers, kept apart

    ok              answered, and the promise in :expect holds
    degraded        answered, and the response names its own problem
    answered-badly  answered, promise broken, no self-diagnosis
    unanswered      could not be reached at all

  `unanswered` is NOT a failure of the target until the controls say the network
  works. If the controls cannot be reached, this reports nothing about any probe
  and exits 2 -- the worst thing it could do is announce that everything is dead
  because the laptop is offline.

  ## Exit codes

    0  every due probe ok
    1  at least one degraded or answered-badly  (a real finding)
    2  could not measure: manifest missing/unreadable, zero probes due,
       or the controls failed. NEVER conflated with 0.

  usage:
    nbb scripts/verify-endpoint-health.cljs [<root>] [--findings] [--all]
                                            [--only <id>] [--cheap-only]

    --all         ignore :every-hours and run every probe
    --cheap-only  skip :cost :gpu probes (no inference is triggered)
    --only <id>   run one probe by id, e.g. --only shinshi/chat"
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def argv (vec *command-line-args*))
(def flag? (set (filter #(str/starts-with? % "--") argv)))
(def findings? (contains? flag? "--findings"))
(def all? (contains? flag? "--all"))
(def cheap-only? (contains? flag? "--cheap-only"))
(def only-id
  (when-let [i (first (keep-indexed #(when (= %2 "--only") %1) argv))]
    (get argv (inc i))))
(def edn-out
  ;; Where to write this run's observations, in the shape kotoba-lang/uptime's
  ;; `observation` takes. A single run cannot say anything about availability --
  ;; that needs a window -- so this emits the raw probes and leaves the statement
  ;; to whoever accumulates them (scripts/endpoint-health-resident.cljs).
  (when-let [i (first (keep-indexed #(when (= %2 "--edn") %1) argv))]
    (get argv (inc i))))
(def root (or (first (remove #(or (str/starts-with? % "--")
                                  (= % only-id) (= % edn-out)) argv))
              (js/process.cwd)))
(def state-path (.join path (or (.-HOME js/process.env) "/tmp")
                       ".itonami" "endpoint-health.state.edn"))

(defn die! [code & msg]
  (apply println msg)
  (js/process.exit code))

;; ---------------------------------------------------------------- state
;; Only used to honour :every-hours. A missing/corrupt state file means every
;; probe is due -- it must never mean "nothing to do".

(defn read-state []
  (try (edn/read-string (.readFileSync fs state-path "utf8")) (catch :default _ {})))

(defn write-state! [m]
  (try (.mkdirSync fs (.dirname path state-path) #js {:recursive true})
       (.writeFileSync fs state-path (pr-str m))
       (catch :default e (println (str "  (state not persisted: " e ")")))))

;; ---------------------------------------------------------------- probing

(defn json-in
  "Walk :json-path through parsed JSON. Vector index or map key."
  [v ks]
  (reduce (fn [acc k] (cond (nil? acc) nil (number? k) (get acc k) :else (get acc k))) v ks))

(defn fetch-once [{:keys [url method body timeout-ms]}]
  (let [ctl (js/AbortController.)
        t (js/setTimeout #(.abort ctl) (or timeout-ms 30000))
        opts (cond-> {:method (str/upper-case (name (or method :get)))
                      :signal (.-signal ctl)}
               body (assoc :headers #js {"content-type" "application/json"}
                           :body (js/JSON.stringify (clj->js body))))]
    (-> (js/fetch url (clj->js opts))
        (.then (fn [^js r]
                 (-> (.text r)
                     (.then (fn [t*]
                              (js/clearTimeout t)
                              {:reached? true :status (.-status r) :text t*
                               :json (try (js->clj (js/JSON.parse t*) :keywordize-keys true)
                                          (catch :default _ nil))})))))
        (.catch (fn [e]
                  (js/clearTimeout t)
                  {:reached? false :error (str e)})))))

(defn classify [probe res]
  (let [{:keys [status json text reached?]} res
        {:keys [expect degraded-when]} probe]
    (cond
      (not reached?) {:verdict :unanswered :detail (:error res)}

      (some (fn [d]
              (let [v (json-in json (:json-path d))]
                (and (string? v) (str/includes? (str/lower-case v)
                                                (str/lower-case (:contains d))))))
            degraded-when)
      {:verdict :degraded
       :detail (str "HTTP " status " -- " (str/trim (subs text 0 (min 240 (count text)))))}

      (and (= status (:status expect))
           (every? (fn [p] (let [v (json-in json p)]
                             (and (some? v) (not= "" v) (not= [] v) (not= {} v))))
                   (let [ps (:json-non-empty expect)]
                     (if (vector? (first ps)) ps [ps]))))
      {:verdict :ok :detail (str "HTTP " status)}

      :else
      {:verdict :answered-badly
       :detail (str (if (= status (:status expect))
                      (str "HTTP " status " but a promised field was empty/absent")
                      (str "HTTP " status ", expected " (:status expect)))
                    " -- " (str/trim (subs text 0 (min 240 (count text)))))})))

(defn due? [state now p]
  (or all? only-id
      (let [last (get-in state [(:id p) :at])
            every-ms (* 3600000 (or (:every-hours p) 6))]
        (or (nil? last) (> (- now last) every-ms)))))

;; ---------------------------------------------------------------- main

(defn verdict->outcome
  "uptime.core/outcomes has exactly three values, and the mapping matters:

   :ok                          -> :up
   :degraded / :answered-badly  -> :down   (a claim about the SERVICE)
   :unanswered                  -> :down   (only reached here once the controls
                                            were reachable, so it is the
                                            service, not this prober)

  Nothing maps to :inconclusive from here. A run that could not measure exits 2
  before reporting, and writes no observations at all -- which is what keeps
  `unobserved` distinguishable from `up` downstream. If a run could write
  :inconclusive observations, a prober that fails every time would produce a
  full, healthy-looking window of them."
  [verdict]
  (if (= :ok verdict) :up :down))

(defn write-observations! [now rs]
  (when edn-out
    (let [obs (mapv (fn [{:keys [verdict detail probe]}]
                      (cond-> {:observation/target (str (:id probe))
                               :observation/at now
                               :observation/outcome (verdict->outcome verdict)}
                        detail (assoc :observation/detail detail)))
                    rs)]
      (try (.mkdirSync fs (.dirname path edn-out) #js {:recursive true})
           (.writeFileSync fs edn-out (pr-str obs))
           (println (str "OBSERVATIONS\t" (count obs) "\t" edn-out))
           (catch :default e (println (str "  (observations not written: " e ")")))))))

(defn report! [state now due rs]
  ;; SCANNED is printed HERE, not at selection time: it is the evidence floor,
  ;; and it must count probes that were actually called. An earlier version
  ;; printed it before the control check, so a run that called nothing (controls
  ;; down) still announced "1 probe(s) actually called".
  (println (str "SCANNED\t" (count rs) "\tprobe(s) actually called"))
  (write-observations! now rs)
  (let [by (group-by :verdict rs)
        ;; UNANSWERED counts as failure here. This function only runs after the
        ;; controls were reachable, so "could not reach it" is a fact about the
        ;; endpoint, not about this machine. An earlier version printed
        ;; "treat as down" and then exited 0 -- the exact shape this tool exists
        ;; to catch, reproduced inside it (found by discrimination, 2026-08-18).
        bad (concat (:degraded by) (:answered-badly by) (:unanswered by))]
    (println)
    (doseq [{:keys [verdict detail probe]} rs]
      (println (str "  " (str/upper-case (name verdict))
                    (apply str (repeat (max 1 (- 16 (count (name verdict)))) " "))
                    (:id probe) "  " detail))
      (when (and findings? (#{:degraded :answered-badly} verdict))
        (println (str "FINDING\tfail\tendpoint::" (subs (str (:id probe)) 1)
                      "\t" (name verdict) " -- " detail
                      " [owner " (:owner probe) "]"))))
    (write-state! (reduce (fn [s r] (assoc s (:id (:probe r))
                                           {:at now :verdict (:verdict r)}))
                          state rs))
    (println)
    (when (seq (:unanswered by))
      (println (str (count (:unanswered by)) " probe(s) UNANSWERED while the controls WERE"
                    " reachable -- counted as down, not as unmeasured.")))
    (if (seq bad)
      (do (println (str (count bad) " endpoint(s) are not keeping their promise."))
          (js/process.exit 1))
      (println (str "OK -- " (count (:ok by)) "/" (count rs)
                    " probe(s) answered as promised.")))))

(defn run-probes! [state now due]
  (-> (js/Promise.all
       (clj->js (map (fn [p] (-> (fetch-once p) (.then #(assoc (classify p %) :probe p))))
                     due)))
      (.then (fn [rs] (report! state now due (js->clj rs :keywordize-keys true))))))

(defn check-controls! [controls]
  (-> (js/Promise.all (clj->js (map fetch-once controls)))
      (.then (fn [cres]
               (let [cres (js->clj cres :keywordize-keys true)
                     live (filter :reached? cres)]
                 (println (str "CONTROLS\t" (count live) "/" (count cres) " reachable"))
                 (when (empty? live)
                   (die! 2 (str "REFUSING to report on any probe: no control host was\n"
                                "reachable, so this machine cannot tell a dead endpoint\n"
                                "from its own dead network.")))
                 true)))))

(defn -main []
  (let [mpath (.join path root "manifest" "endpoint-health.edn")]
    (when-not (.existsSync fs mpath)
      (die! 2 (str "REFUSING to report a pass: no manifest at " mpath)))
    (let [m (try (edn/read-string (.readFileSync fs mpath "utf8"))
                 (catch :default e
                   (die! 2 (str "REFUSING to report a pass: manifest unreadable -- " e))))
          probes (cond->> (:probes m)
                   only-id     (filter #(= (str (:id %)) (str ":" only-id)))
                   cheap-only? (remove #(= :gpu (:cost %))))
          state (read-state)
          now (.now js/Date)
          due (filterv #(due? state now %) probes)]
      (println (str "verify-endpoint-health: " (count (:probes m)) " declared, "
                    (count probes) " selected, " (count due) " due"))
      (println (str "DUE\t" (count due) "\tprobe(s) selected to call"))
      (when (zero? (count due))
        (die! 2 (str "REFUSING to report a pass: 0 probes were called. Nothing was\n"
                     "measured, which is not the same as nothing being wrong.\n"
                     "(--all runs every probe regardless of :every-hours.)")))
      (-> (check-controls! (:controls m))
          (.then (fn [_] (run-probes! state now due)))))))

(-main)

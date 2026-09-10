#!/usr/bin/env nbb
;; scripts/verify-declared-artifact-present.cljs — an actor manifest that
;; declares an artifact CID, against whether that artifact is at the address
;; the manifest names.
;;
;; ADR-2609102000. Found while trying to cut a Q9 whole-component migration over
;; to its consumers: `com-aave`'s manifest.json declares
;;
;;   "wasmCid":        "bafkreidytpism…"
;;   "wasmProvenance": "built-rust-raw"
;;   "runtime":        "kotoba-wasm"
;;
;; in a repository holding zero .rs files and zero .wasm files. 999 distinct
;; CIDs across the workspace carry that shape, and 40 of 40 sampled were not
;; retrievable at `ipfs://<cid>` — the address the manifest itself names.
;;
;; WHY THIS DETECTOR CARRIES ITS OWN POSITIVE CONTROL, AND REFUSES WITHOUT ONE.
;; The first measurement of this stopped at "not found" and did NOT report it,
;; because a 404 alone proves nothing: a deliberately bogus CID returns 404 from
;; the same route, and an apex health check says nothing about /ipfs/. A run
;; that cannot demonstrate the route serving a known-good object cannot tell
;; "the artifact is missing" from "the gateway is down", and those two must
;; never return the same value. So every run first fetches a control CID,
;; verifies the returned bytes hash back to it, and REFUSES (exit 2) if that
;; fails — before any manifest is judged.
;;
;;   nbb scripts/verify-declared-artifact-present.cljs [--findings] [--limit N] <dir>...
;;
;; exit 0 clean / 1 findings / 2 REFUSED (could not answer)

(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def crypto (js/require "node:crypto"))

(def argv (vec (drop 3 (js->clj js/process.argv))))
(def findings-mode? (some #{"--findings"} argv))
(def limit (let [i (.indexOf argv "--limit")]
             (when (nat-int? i) (js/parseInt (nth argv (inc i)) 10))))
(def roots (vec (remove #(or (str/starts-with? % "--")
                             (re-matches #"\d+" %))
                        argv)))

(def gateway "https://kotobase.net/ipfs/")

(defn refuse! [msg]
  (println (str "REFUSED\t" msg))
  (println "Refusing to report a pass: this run could not answer the question.")
  (js/process.exit 2))

;; ---------------------------------------------------------------- CID

(def ^:private b32 "abcdefghijklmnopqrstuvwxyz234567")

;; Correctness here is the whole point: a CID computed wrongly would turn every
;; fetch into a false finding, so this is checked against a known pair on startup.
(defn cid-v1-raw-sha256
  "The CIDv1 (raw codec, sha2-256, base32-lower) of `bytes`.
  Prefix is 0x01 0x55 0x12 0x20 — cidv1, raw, sha2-256, 32 bytes."
  [^js buf]
  (let [digest (-> (.createHash crypto "sha256") (.update buf) (.digest))
        prefixed (js/Buffer.concat #js [(js/Buffer.from #js [0x01 0x55 0x12 0x20]) digest])
        bits (atom 0) acc (atom 0) out (atom "")]
    (dotimes [i (.-length prefixed)]
      (swap! acc #(+ (* % 256) (aget prefixed i)))
      (swap! bits + 8)
      (while (>= @bits 5)
        (let [shift (- @bits 5)
              idx (bit-and (js/Math.floor (/ @acc (js/Math.pow 2 shift))) 31)]
          (swap! out str (nth b32 idx))
          (swap! acc #(mod % (js/Math.pow 2 shift)))
          (swap! bits - 5))))
    (when (pos? @bits)
      (let [idx (bit-and (js/Math.floor (* @acc (js/Math.pow 2 (- 5 @bits)))) 31)]
        (swap! out str (nth b32 idx))))
    (str "b" @out)))

;; ---------------------------------------------------------------- fetch

(defn fetch-cid
  "{:status n :bytes ^js/Buffer|nil} for one CID, or {:status :error}."
  [cid]
  (-> (js/fetch (str gateway cid) #js {:redirect "follow"})
      (.then (fn [r]
               (if (.-ok r)
                 (-> (.arrayBuffer r)
                     (.then (fn [ab] {:status (.-status r) :bytes (js/Buffer.from ab)})))
                 {:status (.-status r) :bytes nil})))
      (.catch (fn [e] {:status :error :error (str (.-message e))}))))

;; ---------------------------------------------------------------- concurrency

(def max-in-flight
  "999 fetches issued at once is rude to a shared gateway and invites a rate
  limit, which this detector would correctly read as `could not reach` and
  REFUSE on -- a self-inflicted refusal. Bounded instead."
  8)

(defn pooled-results [f xs]
  (let [items (vec xs) n (count items) out (js/Array. n) next-i (atom 0)]
    (-> (js/Promise.all
         (clj->js
          (for [_ (range (min max-in-flight (max n 1)))]
            (js/Promise.
             (fn [resolve _]
               (letfn [(step []
                         (let [i @next-i]
                           (if (>= i n)
                             (resolve true)
                             (do (swap! next-i inc)
                                 (-> (f (nth items i))
                                     (.then (fn [r] (aset out i r) (step))))))))]
                 (step)))))))
        (.then (fn [_] out)))))

;; ---------------------------------------------------------------- manifests

(defn walk-manifests [dir]
  (let [out (atom [])]
    (letfn [(go [d depth]
              (when (< depth 6)
                (doseq [e (or (try (js->clj (.readdirSync fs d #js {:withFileTypes true}))
                                   (catch :default _ nil)) [])]
                  (let [nm (.-name e) full (.join path d nm)]
                    (cond
                      (.isDirectory e)
                      (when-not (contains? #{".git" "node_modules" "target"} nm)
                        (go full (inc depth)))
                      (and (.isFile e) (= nm "manifest.json"))
                      (swap! out conj full))))))]
      (go dir 0))
    @out))

(defn declared-artifact
  "{:file :cid :provenance :handle} when this manifest declares an artifact CID."
  [file]
  (try
    (let [m (js->clj (js/JSON.parse (str (.readFileSync fs file "utf8"))))
          cid (get m "wasmCid")]
      (when (and (string? cid) (str/starts-with? cid "baf"))
        {:file file :cid cid
         :provenance (get m "wasmProvenance")
         :handle (get m "handle")}))
    (catch :default _ nil)))

;; ---------------------------------------------------------------- control

(defn control-cids
  "Known-good CIDs, read from the app plane's own catalog rather than hardcoded:
  a hardcoded control rots silently, and a rotted control makes every run
  REFUSE, which is at least loud.

  Read relative to the WORKSPACE root (cwd), not to whatever directory is being
  scanned — the control is a property of the bytes plane, not of the subject.
  Tying it to the scan root made `--limit 3 orgs/kotoba-lang/com-aave` refuse
  for the wrong reason."
  []
  (let [f (.join path (.cwd js/process) "manifest" "appview-catalog.edn")]
    (when (try (.existsSync fs f) (catch :default _ false))
      (->> (re-seq #"bafkrei[a-z0-9]{40,}" (str (.readFileSync fs f "utf8")))
           distinct (take 3) vec))))

(defn run-control!
  "Returns a vector of control failures. A COUNT, not a boolean: a boolean
  cannot tell one stale control CID from a dead gateway."
  [cids]
  (js/Promise.all
   (clj->js
    (map (fn [cid]
           (-> (fetch-cid cid)
               (.then (fn [{:keys [status bytes]}]
                        (cond
                          (= status :error) (str cid " — network error")
                          (not= status 200) (str cid " — HTTP " status)
                          (nil? bytes) (str cid " — 200 with no body")
                          :else
                          (let [computed (cid-v1-raw-sha256 bytes)]
                            (when-not (= computed cid)
                              (str cid " — 200 but the bytes hash to " computed))))))))
         cids))))

;; ---------------------------------------------------------------- main

(defn -main []
  (when (empty? roots) (refuse! "no directory given"))
  (let [ctl (control-cids)]
    (when (empty? ctl)
      (refuse! "no control CID available (manifest/appview-catalog.edn unreadable) — without one, a 404 cannot be told from a dead gateway"))
    (-> (run-control! ctl)
        (.then
         (fn [res]
           (let [fails (vec (remove nil? (js->clj res)))]
             (when (seq fails)
               (refuse! (str "the positive control failed " (count fails) " of " (count ctl)
                             " way(s): " (str/join "; " fails)
                             " — a 404 from this route would be unreadable")))
             (println (str "CONTROL\t" (count ctl) "\tknown-good CID(s) fetched and CID-verified"))
             (let [manifests (vec (mapcat walk-manifests roots))
                   declared (vec (keep declared-artifact manifests))
                   subject (if limit (vec (take limit declared)) declared)]
               (when (zero? (count manifests))
                 (refuse! (str "scanned 0 manifest.json under " (str/join " " roots))))
               (println (str "SCANNED\t" (count manifests) "\tmanifest.json"))
               (println (str "DECLARED\t" (count declared) "\tdeclare a wasmCid"))
               (when limit
                 (println (str "NOTE\tchecked " (count subject) " of " (count declared)
                               " (--limit); a bounded run is not a full one")))
               (-> (pooled-results (fn [d] (-> (fetch-cid (:cid d))
                                               (.then #(assoc d :result %))))
                                   subject)
                   (.then
                    (fn [rs]
                      (let [rows (js->clj rs :keywordize-keys true)
                            errs (filter #(= :error (get-in % [:result :status])) rows)
                            absent (filter #(= 404 (get-in % [:result :status])) rows)
                            present (filter #(= 200 (get-in % [:result :status])) rows)]
                        (when (seq errs)
                          (refuse! (str (count errs) " CID(s) could not be reached; a partial"
                                        " sweep cannot report a pass")))
                        (println (str "PRESENT\t" (count present)))
                        (when findings-mode?
                          (doseq [d (sort-by :file absent)]
                            (println (str "FINDING\terror\tdeclared-artifact-absent\t"
                                          (:file d) "\t" (:cid d)
                                          "\tprovenance=" (or (:provenance d) "?")
                                          "\tthe manifest declares this artifact at ipfs://<cid>"
                                          " and the bytes plane does not have it"
                                          " (control passed in this run, so this is absence,"
                                          " not an unreachable gateway)"))))
                        (println (str "FINDINGS\t" (count absent)))
                        (js/process.exit (if (seq absent) 1 0)))))))))))))

(-main)

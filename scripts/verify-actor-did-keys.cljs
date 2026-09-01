#!/usr/bin/env nbb
;; A key in a DID document outside the enumerated actor set.
;;
;; `etzhayyim.com` resolves `/actor/<handle>/did.json` for ANY handle -- measured
;; 2026-09-01, `definitely-not-an-actor-9x7q` returns 200 with a well-formed
;; document. That is deliberate. `/.well-known/actors.json` says so in its own
;; note: 104 named/service actors are enumerated, and beyond them are 42,659
;; resolvable "society-scale keyless mirror-actors", each namespace marked
;; `keyless observational mirror -- NOT the entity itself (no impersonation, G1)`.
;;
;; The safety of that open namespace rests on ONE property: a document with no
;; verification method cannot sign, so a mirror cannot speak as the thing it
;; mirrors. Add a key to one and the mirror becomes an impersonator, and nothing
;; about the URL changes to say so.
;;
;; This was nearly broken from the outside on the day it was written. Making the
;; actor DID plane usable for agent authentication means putting keys in
;; documents, and the obvious way to do that -- key the actors -- is correct only
;; for the enumerated ones. `tomoshibi`, the single keyed document today, is in
;; the enumerated set; that is the pattern, and it was nowhere enforced.
;;
;;   nbb scripts/verify-actor-did-keys.cljs [--findings] [<root>]
;;
;; exit 0 clean, 1 findings, 2 could not be checked.

(ns verify-actor-did-keys
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def ^:private argv
  "nbb's own arg vector. `js/process.argv` still carries the script path, and
  taking it as the root made this refuse to report a pass on the grounds that
  `scripts/verify-actor-did-keys.cljs/orgs/...` was not checked out."
  (vec *command-line-args*))
(def ^:private findings? (some #{"--findings"} argv))
(def ^:private root (or (first (remove #(str/starts-with? % "--") argv)) "."))

(def ^:private did-web-dir
  "orgs/etzhayyim/root/50-infra/etzhayyim-did-web")

(defn- refuse! [why]
  (println (str "REFUSING to report a pass: " why))
  (set! (.-exitCode js/process) 2))

(defn- read-json [p]
  (try (js->clj (js/JSON.parse (fs/readFileSync p "utf8")) :keywordize-keys false)
       (catch :default _ nil)))

(defn- keyed?
  "Does this document carry something that can verify a signature?

  Any of the three, because a document that lists an assertion or authentication
  method is claiming a key even when `verificationMethod` is empty -- the
  reference form points at another DID's key and authenticates just as well."
  [doc]
  (boolean (or (seq (get doc "verificationMethod"))
               (seq (get doc "assertionMethod"))
               (seq (get doc "authentication")))))

(defn- enumerated
  "The named/service actors, from the registry the Worker compiles in.

  Parsed out of the TypeScript rather than fetched, because a detector that
  needs the network answers `could not check` on a bad day and this question is
  answerable from the tree. Returns nil when it cannot be read -- which is
  reported, never treated as an empty set: an empty set would make every keyed
  document a finding and bury the real one."
  [dir]
  ;; `INFRA_ACTORS` is `{...TIER_B_ACTORS, ...HAND_AUTHORED_ACTORS}`, so the set
  ;; is the union of the two files it spreads -- reading only the export site
  ;; finds two spread operators and no handles, which is why the first version
  ;; refused to report a pass rather than calling an unread set empty.
  (let [files ["src/registry/infra-actors.ts" "src/registry/tier-b-actors.gen.ts"]
        hs (mapcat (fn [rel]
                     (let [f (path/join dir rel)]
                       (when (fs/existsSync f)
                         (map second
                              (re-seq #"(?m)^\s*[\"']?([a-z0-9][a-z0-9-]*)[\"']?\s*:\s*\{"
                                      (fs/readFileSync f "utf8"))))))
                   files)]
    (when (seq hs) (set hs))))

(defn -main []
  (let [dir (path/join root did-web-dir)
        actor-dir (path/join dir "public/actor")]
    (cond
      (not (fs/existsSync actor-dir))
      (refuse! (str actor-dir " is not checked out; the actor DID plane could not be read"))

      :else
      (let [handles (vec (sort (fs/readdirSync actor-dir)))
            known (enumerated dir)
            docs (for [h handles
                       :let [p (path/join actor-dir h "did.json")]
                       :when (fs/existsSync p)]
                   [h (read-json p)])
            unreadable (filter (fn [[_ d]] (nil? d)) docs)
            keyed (filter (fn [[_ d]] (and d (keyed? d))) docs)]
        (println (str "SCANNED\t" (count docs) "\tactor DID document(s)"))
        (println (str (count keyed) " carry a verification method"
                      (when known (str "; " (count known) " actors are enumerated"))))
        (cond
          (zero? (count docs))
          (refuse! "no DID documents were read")

          (seq unreadable)
          (do (doseq [[h _] unreadable] (println (str "UNREADABLE " h "/did.json")))
              (refuse! (str (count unreadable) " document(s) did not parse")))

          (nil? known)
          (do (doseq [[h _] keyed] (println (str "keyed: " h)))
              (refuse! (str "the enumerated actor set could not be read from "
                            "src/registry/entity-actors.ts, so `keyed but not "
                            "enumerated` is unanswerable")))

          :else
          (let [bad (remove (fn [[h _]] (contains? known h)) keyed)]
            (doseq [[h _] keyed]
              (println (str (if (contains? known h) "ok   " "FAIL ") h)))
            (when findings?
              (doseq [[h _] bad]
                (println (str "FINDING\tfail\tkeyed-but-not-enumerated:" h
                              "\ta DID document outside the enumerated actor set "
                              "carries a verification method; the open namespace is "
                              "safe only while its mirrors cannot sign"))))
            (when (seq bad) (set! (.-exitCode js/process) 1))))))))

(-main)

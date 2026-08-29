(ns aozora-core
  "The strings, naming rules and exit contract for the Hermes bots' aozora.app
   accounts — one place, so register / pulse cannot drift apart (the same
   reason `gftd.fabric-actor-core` exists for the `*-organism` actors).

   Roster: NOT a hardcoded list. The bots are whatever `~/.hermes/cron/jobs.json`
   holds — the two-name-horizon bug (hermes-hyakka-bots README, 2026-08-28:
   a refresh job that knew two names silently skipped the two itonami bots
   another session had added) is why coverage must be derived, not enumerated.
   A deliberate opt-out is expressed via HERMES_AOZORA_OPT_OUT (names, comma-
   separated), mirroring HYAKKA_MODEL_OPT_OUT.

   Identity: Ed25519 seed → did:key (the aozora account model — the DID *is*
   the account). Seed custody: macOS Keychain, service
   `aozora.app/actor-seed/<handle>` — the same namespace the fabric actors
   use, so `secrets-location-map` needs no new section.

   Exit contract (shared by register.cljs and pulse.cljs):
     0 — every non-opted-out bot handled (posted, registered, or upstream-
         BLOCKED with a banner: the run happened and said what it saw)
     1 — measured, and at least one bot failed for a reason that is OURS
         (bad seed, keychain refusal, unexpected local error)
     2 — REFUSED: could not read the roster at all. Not the same as 'no bots'."
  (:require [clojure.string :as str]))

(def pds "https://pds.aozora.app")
(def pds-aud "did:web:pds.aozora.app")
(def pds-domain "pds.aozora.app")

(defn handle [job-name] (str job-name ".aozora.app"))
(defn seed-service [h] (str "aozora.app/actor-seed/" h))
(defn display-name [job-name] (str job-name " (bot)"))

(defn description [job-name]
  (str job-name " — a scheduled Hermes bot in the com-junkawasaki workspace. "
       "Evidence-first: it proposes only what a deterministic gate verified. "
       "Source of record: scripts/hermes-*/ in com-junkawasaki/root."))

(defn registration-text [job-name]
  (str job-name " bot registered on aozora.app. "
       "Identity is this account's did:key; writes are CACAO-signed. "
       "Posts here are run receipts, not prose."))

(def ^:private unknown "unknown")
(defn- text-or-unknown [v]
  (if (and v (not (str/blank? (str v)))) (str v) unknown))

(defn pulse-text
  "One post per bot per pulse run: what the scheduler measured, nothing the
   model imagined. `job` is the bot's jobs.json entry as a map with string keys."
  [job-name {:strs [last_run_at last_status last_error failure_streak enabled]}]
  (str job-name " bot pulse — "
       "last-run=" (text-or-unknown last_run_at)
       " status=" (text-or-unknown (or last_status (when last_error "error")))
       " failure-streak=" (if (number? failure_streak) failure_streak unknown)
       " enabled=" (if (nil? enabled) unknown (boolean enabled))))

(defn opted-out? [job-name]
  (let [raw #?(:cljs (or (aget (.-env js/process) "HERMES_AOZORA_OPT_OUT") "")
               :clj (or (System/getenv "HERMES_AOZORA_OPT_OUT") ""))]
    (contains? (->> (str/split raw #",") (map str/trim) (remove str/blank?) set)
               job-name)))

(defn exit-code
  "n = bots considered, local-failures = failures that are ours (not upstream
   BLOCKED). Empty roster is REFUSED — a run that saw nothing must not read
   as a run where everything passed."
  [n local-failures]
  (cond (zero? n) 2
        (pos? local-failures) 1
        :else 0))

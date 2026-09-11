(ns itonami.isic-9601.state
  "Game state as a first-class EDN value.

  `play --dump` writes this envelope; `play --state` and `render --state` read it.
  Street progress (`world`) and the shop board (`logic`) travel together so an agent can
  hand a played run to the renderer without inventing a second state channel.

  The envelope shape is fixed by ADR-2608108000:

    {:kind :itonami-game/state :version 1
     :district \"isic-9601\" :seed 20260808
     :world {...}   ; world/init + progress (:cleared …)
     :shop  {...}}  ; logic state, or nil when not inside a shop

  Top-level `:seed` is the run's *starting* seed (the identifier), not the shop's live LCG
  cursor — that lives inside `:shop`. Same seed + district + script must reproduce the
  dump; the dump is a shorthand for that input column."
  (:require [clojure.edn :as edn]
            [itonami.isic-9601.world :as world]))

(def kind
  "Discriminant every consumer checks before trusting the map."
  :itonami-game/state)

(def version
  "Bump when `:shop` / `:world` shape changes in a way old readers cannot ignore."
  1)

(defn envelope
  "Assemble a v1 envelope. Callers pass the *original* seed, not `(:seed shop)`."
  [{:keys [district seed world shop]}]
  {:kind kind
   :version version
   :district district
   :seed seed
   :world (or world (world/init))
   :shop shop})

(defn wrap
  "Envelope for a shop that has been (or is being) played on a street."
  ([shop world seed]
   (envelope {:district (:district shop)
              :seed seed
              :world world
              :shop shop}))
  ([shop world seed district]
   (envelope {:district district
              :seed seed
              :world world
              :shop shop})))

(defn encode
  "Canonical EDN text for dump files. Trailing newline keeps `cat` and diffs tidy;
  round-trip identity is dump→load→dump of this string."
  [env]
  (str (pr-str env) "\n"))

(defn validate!
  "Reject anything that is not a v1 itonami game state. Unknown `:version` errors
  loudly — silent coercion would let an agent debug against the wrong rules."
  [data]
  (when-not (map? data)
    (throw (ex-info "itonami game state must be a map"
                    {:error :itonami-game/state-invalid :got (type data)})))
  (when (not= (:kind data) kind)
    (throw (ex-info (str "not an itonami game state (expected :kind "
                         kind " , got " (pr-str (:kind data)) ")")
                    {:error :itonami-game/state-invalid
                     :expected-kind kind
                     :got (:kind data)})))
  (let [v (:version data)]
    (when (nil? v)
      (throw (ex-info "itonami game state missing :version"
                      {:error :itonami-game/state-version-missing})))
    (when (not= v version)
      (throw (ex-info (str "unsupported itonami game state :version " (pr-str v)
                           " (this build reads version " version ")")
                      {:error :itonami-game/state-version-unsupported
                       :version v
                       :supported version}))))
  (when-not (contains? data :world)
    (throw (ex-info "itonami game state missing :world"
                    {:error :itonami-game/state-invalid})))
  (when-not (contains? data :district)
    (throw (ex-info "itonami game state missing :district"
                    {:error :itonami-game/state-invalid})))
  (when-not (contains? data :seed)
    (throw (ex-info "itonami game state missing :seed"
                    {:error :itonami-game/state-invalid})))
  data)

(defn parse
  "Read EDN text into a validated envelope."
  [text]
  (validate! (edn/read-string text)))

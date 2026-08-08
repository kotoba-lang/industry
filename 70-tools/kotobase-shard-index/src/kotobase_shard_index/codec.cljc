(ns kotobase-shard-index.codec
  "Canonical bytes for content addressing.

  A block's identity is the hash of its bytes, so two writers that mean the
  same block must produce the same bytes. Clojure switches map implementation
  above 8 entries and stops preserving insertion order (measured in this
  workspace on `css.core/declarations`, CLAUDE.md), so `pr-str` over a literal
  map is NOT deterministic at block sizes we actually use. `canonical` sorts
  every map before printing.

  The hash itself is INJECTED, never embedded — this namespace stays portable
  and the Node adapter supplies sha-256 (`kotobase-shard-index.node`). Same
  port-not-embed convention `kotoba.map.data` uses."
  (:require [clojure.edn :as edn]))

(defn canonical
  "`x` with every map replaced by a sorted-map, recursively. Vectors keep their
  order (it is meaningful — postings are impact-ordered). Sets are rejected:
  the block formats deliberately contain none, because sorting a set of maps
  needs a comparator that would itself become part of the wire format."
  [x]
  (cond
    (map? x) (into (sorted-map) (map (fn [[k v]] [k (canonical v)])) x)
    (vector? x) (mapv canonical x)
    (set? x) (throw (ex-info "sets are not representable in a block" {:value x}))
    (seq? x) (mapv canonical x)
    :else x))

(defn encode
  "Block value -> canonical string. The bytes that get hashed and stored."
  [x]
  (pr-str (canonical x)))

(defn decode
  "Stored string -> block value."
  [s]
  (edn/read-string s))

(defn byte-length
  "UTF-8 byte length. `count` on a JS string counts UTF-16 code units, which
  is not what an object store bills or a Range header addresses."
  [s]
  #?(:clj (alength (.getBytes ^String s "UTF-8"))
     :cljs (.-length (.encode (js/TextEncoder.) s))))

(defn cid
  "Content address of `x` under `hash-fn` (bytes-string -> hex string).

  Prefix `b` marks this as this subsystem's own address form. It is NOT a
  multiformats CIDv1 — `kotoba-lang/io-multiformats` exists and adopting it is
  a named follow-up, not something quietly assumed to have happened."
  [hash-fn x]
  (str "b" (hash-fn (encode x))))

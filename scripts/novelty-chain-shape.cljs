#!/usr/bin/env nbb
;; novelty-chain-shape.cljs — count the NODES of a kotobase novelty chain,
;; classified by shape, instead of reading the O(1) `novelty-count` field.
;;
;; WHY THIS EXISTS
;; ---------------
;; `/_diag/health` reports `novelty_size`, which is the chain state's
;; `"novelty-count"` — the number of unfolded TRANSACTIONS. The thing that
;; costs a hydrating read is the number of sequential BLOCK FETCHES, i.e. the
;; number of NODES, and since kotoba-lang/kotobase-peer c63468f4 one node holds
;; up to `novelty-segment-size` = 16 entries. Nodes written before that landing
;; hold exactly one entry and are never rewritten (push-novelty!'s docstring:
;; "A legacy single-entry head is never rewritten -- the new entry starts a
;; fresh segment pointing at it"). So entries and hops are different numbers and
;; only one of them can be read off the diagnostic. This walks the chain and
;; counts the other one.
;;
;; 2026-08-17-live-latency-and-deployed-fold-threshold.edn predicted 21 hops by
;; arithmetic and recorded, honestly, that the arithmetic did not reconcile with
;; an earlier measurement. Its own `:what-would-discriminate` names this walk.
;;
;; AUTHORITY (read, not guessed)
;; -----------------------------
;;   R2 key layout   protocols-worker/src/kotobase_protocols_worker/kotobase_r2.cljs
;;                   block-key = "<prefix>blocks/<cid>", head-key = "<prefix>heads/<graph>"
;;   chain envelope  kotoba-lang/chain      chain.core  {"state" .. "prev" .. "seq" ..}
;;                   (decoded here, not via commit-info — see the Link note below)
;;   novelty shape   kotoba-lang/kotobase-peer  kotobase-peer.core
;;                     walk-novelty-entries / node-entries / push-novelty!
;;   decode + CID    kotoba-lang/io-ipld    ipld.core/get-node (rehashes before decode)
;;
;; This script mirrors `walk-novelty-entries` exactly: follow "rest" until nil,
;; and read each node through `node-entries`' shape test — a node with an "es"
;; key is a SEGMENT, a node with an "e" key is a legacy SINGLE. A chain freely
;; mixes both.
;;
;; READ-ONLY. Every R2 call is `wrangler r2 object get`. Nothing here writes,
;; deletes, deploys, or touches a secret.
;;
;; EXIT CODES — "could not answer" is not "answered zero"
;; -----------------------------------------------------
;;   0  walk completed AND entries counted == the state's novelty-count
;;   1  walk completed BUT the two counts disagree — a real finding, printed
;;      loudly, because then one of the two numbers is wrong
;;   2  walk could NOT be completed (missing block, CID mismatch, decode
;;      failure, cycle, unreadable head). NOT 0 and NOT 1: this script must
;;      never let "I could not look" be read as either a clean answer or a
;;      substantive disagreement. Every count it prints carries an explicit
;;      scanned/expected denominator for the same reason.
;;
;; USAGE
;;   nbb --classpath "<io-ipld>/src:<org-ietf-cbor>/src:<io-multiformats>/src" \
;;       scripts/novelty-chain-shape.cljs --wrangler-dir <dir-with-wrangler-and-node_modules>
;;
;;   NODE_PATH must point at a node_modules containing @noble/hashes
;;   (io-multiformats' sha2 dependency); the worker's own node_modules has it.

(ns novelty-chain-shape
  (:require [clojure.string :as str]
            [ipld.core :as ipld]
            ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]))

;; ── argv ─────────────────────────────────────────────────────────────────────

;; `*command-line-args*` rather than slicing process.argv: the latter shifts
;; depending on whether `--classpath` was passed, and this script is always
;; invoked with one.
(def ^:private argv (vec *command-line-args*))

(defn- flag
  ([k] (flag k nil))
  ([k default]
   (let [i (.indexOf argv k)]
     (if (neg? i) default (get argv (inc i) default)))))

(defn- num-flag [k default]
  (let [v (flag k)] (if v (js/Number v) default)))

(def ^:private opts
  {:graph        (flag "--graph" "kotobase-protocols-v2")
   :bucket       (flag "--bucket" "kotobase-protocols-state")
   :prefix       (flag "--prefix" "")
   :wrangler-dir (flag "--wrangler-dir")
   :cache        (flag "--cache" (path/join (or (.-TMPDIR (.-env js/process)) "/tmp")
                                            "novelty-chain-shape-cache"))
   :out          (flag "--out")
   ;; walk the novelty of an ARBITRARY commit instead of the current head.
   ;; Chain blocks are immutable, so this reads history as it actually was —
   ;; e.g. the commit just before a fold, to see what that fold consumed.
   :chain-cid    (flag "--chain-cid")
   ;; handler cost window from 2026-08-17-live-latency-and-deployed-fold-threshold.edn
   :ms-min       (num-flag "--handler-ms-min" 3974)
   :ms-max       (num-flag "--handler-ms-max" 4437)
   :prolly-waves (num-flag "--prolly-waves" 2)
   ;; a chain longer than this is treated as runaway rather than walked forever
   :max-nodes    (num-flag "--max-nodes" 5000)
   ;; optional secondary diagnostic; nil = skip it
   :scan-commits (when (flag "--scan-commits") (num-flag "--scan-commits" 0))})

;; ── failure that must not look like an answer ────────────────────────────────

(defn- cannot-answer!
  "Exit 2. Used for every condition under which the walk did not complete."
  [msg data]
  (println)
  (println "COULD-NOT-ANSWER" msg)
  (doseq [[k v] data] (println "   " (name k) v))
  (println "Exiting 2 (neither pass nor fail): the walk did not complete, so no"
           "count below is a total.")
  (.exit js/process 2))

;; ── R2: read-only block/head fetch via wrangler ──────────────────────────────

(def ^:private block-key #(str (:prefix opts) "blocks/" %))
(def ^:private head-key
  #(str (:prefix opts) "heads/" (str/replace % #"[^A-Za-z0-9._:-]" "_")))

(def ^:private fetch-count (atom 0))
(def ^:private cache-hits (atom 0))

(defn- r2-get!
  "GET one R2 object to `dest`. Returns true on success. READ-ONLY: `object get`
  is the only wrangler subcommand this script ever runs."
  [key dest]
  (swap! fetch-count inc)
  (let [res (cp/spawnSync "npx"
                          #js ["wrangler" "r2" "object" "get"
                               (str (:bucket opts) "/" key)
                               "--file" dest "--remote"]
                          #js {:cwd (:wrangler-dir opts) :encoding "utf8"})]
    (and (zero? (.-status res)) (fs/existsSync dest))))

(defn- block-bytes!
  "Bytes for `cid`, from the on-disk cache or R2. nil when absent — the caller
  turns that into exit 2, never into a shorter chain."
  [cid]
  (let [dest (path/join (:cache opts) (str cid ".bin"))]
    (if (fs/existsSync dest)
      (do (swap! cache-hits inc) (js/Uint8Array. (fs/readFileSync dest)))
      (when (r2-get! (block-key cid) dest)
        (js/Uint8Array. (fs/readFileSync dest))))))

;; `ipld.core/get-node` rehashes the bytes and throws on a CID mismatch, so a
;; store that lied about a block cannot be silently counted.
(defn- get-fn [cid] (block-bytes! cid))

;; ── reading a Link under nbb ─────────────────────────────────────────────────
;;
;; MEASURED HAZARD, 2026-08-17: `ipld.core/link-cid` is `(.-cid l)` on a
;; `deftype Link`. Under nbb the namespace is interpreted by SCI, which stores
;; deftype fields under a MANGLED property key (observed: `Kb`), so `.-cid`
;; resolves to `undefined` and `link-cid` returns **nil** — no throw, no
;; warning. On the first run of this script that turned every link into "chain
;; ends here" and produced a confident `TOTAL nodes 0`. The scanned/expected
;; denominator caught it (0 != 63), which is exactly why that guard exists.
;;
;; `str` on the Link works, because the deftype's own toString — `#ipld/link
;; "<cid>"` — is written in ipld.core's SOURCE rather than being an artifact of
;; how fields are laid out. That is the accessor with a stable contract here.
;; Both paths are validated against the base32 CIDv1 shape, and anything that
;; does not parse exits 2 rather than degrading to nil.

;; The same hazard reaches INTO the authority libraries: `chain.core/commit-info`
;; computes `:prev` as `(some-> prev ipld/link-cid)`, so under nbb every commit
;; reports `:prev nil` and a history walk "reaches genesis" after one step. It
;; did exactly that here before this note existed. `commit-at` below therefore
;; decodes the commit envelope directly rather than using `commit-info`.
;;
;; This is a hazard of READING these namespaces with SCI, not a production bug:
;; the deployed Worker is compiled by shadow-cljs, where a deftype field is a
;; real property access. Nothing below is evidence about the running service.

(def ^:private cid-re #"^b[a-z2-7]{20,}$")

(defn- link->cid
  "The CID string inside a Link, or exit 2. Never nil for a non-nil link —
  silently reading a link as 'no link' shortens the very chain being counted."
  [l]
  (when (some? l)
    (let [direct (try (ipld/link-cid l) (catch :default _ nil))
          parsed (when-not (and (string? direct) (re-matches cid-re direct))
                   (second (re-matches #"^#ipld/link \"([^\"]+)\"$" (str l))))
          cid (or (when (and (string? direct) (re-matches cid-re direct)) direct)
                  parsed)]
      (if (and (string? cid) (re-matches cid-re cid))
        cid
        (cannot-answer! "could not read the CID out of an IPLD Link"
                        {:link-str (str l)
                         :link-cid-returned (pr-str direct)
                         :note "reading this as nil would silently end the walk"})))))

;; ── the walk ─────────────────────────────────────────────────────────────────

(defn- node-shape
  "kotobase-peer.core/node-entries' own test: an \"es\" key means the segment
  shape this build writes, otherwise the single-entry shape live chains are
  full of."
  [node]
  (if (contains? node "es") :segment :single))

(defn- node-entry-count [node]
  (if (contains? node "es") (count (get node "es")) 1))

(defn- walk!
  "Follow `rest` from `head-cid` to nil, mirroring
  kotobase-peer.core/walk-novelty-entries. Returns
  {:nodes [{:cid :shape :entries}] :entries n}. Any condition that stops the
  walk early exits 2 rather than returning a short answer."
  [label head-cid]
  (loop [cid head-cid, nodes [], seen #{}]
    (cond
      (nil? cid)
      {:nodes nodes :entries (reduce + 0 (map :entries nodes))}

      (contains? seen cid)
      (cannot-answer! (str "cycle in the " label " chain")
                      {:cid cid :nodes-walked (count nodes)})

      (> (count nodes) (:max-nodes opts))
      (cannot-answer! (str "the " label " chain exceeded --max-nodes")
                      {:max-nodes (:max-nodes opts)})

      :else
      (let [node (try
                   (ipld/get-node get-fn cid)
                   (catch :default e
                     (cannot-answer! (str "block failed CID verification or decode in the "
                                          label " chain")
                                     {:cid cid :error (ex-message e)
                                      :nodes-walked (count nodes)})))]
        (when (nil? node)
          (cannot-answer! (str "block missing from R2 in the " label " chain")
                          {:cid cid :nodes-walked (count nodes)
                           :note "the chain does not end here; the block is simply not readable"}))
        (print ".") ; progress: one dot per sequential node fetch
        (recur (link->cid (get node "rest"))
               (conj nodes {:cid cid
                            :shape (node-shape node)
                            :entries (node-entry-count node)})
               (conj seen cid))))))

(defn- commit-at
  "`{:cid :state :prev :seq}` for a chain commit — chain.core's envelope
  (`{\"state\" .. \"prev\" Link|nil \"seq\" n}`), decoded here so that `:prev`
  is read through `link->cid`. `ipld/get-node` still rehashes the bytes, so the
  CID verification chain.core/commit-info provides is not lost."
  [cid]
  (when-let [m (ipld/get-node get-fn cid)]
    {:cid cid :state (get m "state")
     :prev (link->cid (get m "prev"))
     :seq (get m "seq")}))

;; ── secondary diagnostic: when did this graph last fold? ─────────────────────
;;
;; The primary count above says WHAT the chain looks like now. This says how it
;; got that way. Walking `prev` backwards, "novelty-count" should fall by one
;; per commit; a FOLD is the only thing that removes entries, so it shows up as
;; the point where the count stops decreasing and jumps back up.
;;
;; BOUNDED, and it says so. A scan that reaches its limit without finding a fold
;; reports :not-within-limit — never "this graph has never folded", which is a
;; claim about all 972 commits that a scan of the last N cannot support.

(defn- scan-commits!
  [tip-cid limit]
  (loop [cid tip-cid, n 0, rows []]
    (if (or (nil? cid) (>= n limit))
      {:rows rows :scanned n :limit limit
       :fold (if (nil? cid) :chain-genesis-reached :not-within-limit)}
      (let [info (try (commit-at cid) (catch :default _ nil))]
        (if (nil? info)
          {:rows rows :scanned n :limit limit :fold :scan-incomplete
           :incomplete-at cid}
          (let [st (:state info)
                cnt (when (map? st) (get st "novelty-count"))
                row {:seq (:seq info) :novelty-count cnt
                     :front? (some? (get st "novelty-front"))
                     :back? (some? (get st "novelty-back"))}
                prev-row (peek rows)]
            (print ".") (flush)
            ;; walking BACKWARDS: an older commit holding MORE novelty than the
            ;; newer one is a fold boundary.
            (if (and prev-row (number? cnt) (number? (:novelty-count prev-row))
                     (> cnt (:novelty-count prev-row)))
              {:rows (conj rows row) :scanned (inc n) :limit limit
               :fold :found
               :fold-boundary {:pre-fold-seq (:seq info) :pre-fold-count cnt
                               :post-fold-seq (:seq prev-row)
                               :post-fold-count (:novelty-count prev-row)}}
              (recur (:prev info) (inc n) (conj rows row)))))))))

;; ── report ───────────────────────────────────────────────────────────────────

(defn- tally [nodes]
  (let [by-shape (group-by :shape nodes)
        segs (get by-shape :segment [])]
    {:nodes (count nodes)
     :singles (count (get by-shape :single []))
     :segments (count segs)
     :single-entries (reduce + 0 (map :entries (get by-shape :single [])))
     :segment-entries (reduce + 0 (map :entries segs))
     :segment-fill (vec (map :entries segs))}))

(defn- round1 [x] (/ (js/Math.round (* 10 x)) 10))

(defn -main []
  (when-not (:wrangler-dir opts)
    (cannot-answer! "--wrangler-dir is required"
                    {:hint "a directory whose node_modules has wrangler and whose account can read the bucket"}))
  (fs/mkdirSync (:cache opts) #js {:recursive true})

  (println "novelty-chain-shape — counting NODES, not entries")
  (println "  graph  " (:graph opts))
  (println "  bucket " (:bucket opts))
  (println "  mode    READ-ONLY (wrangler r2 object get only)")
  (println)

  ;; 1. head pointer → chain tip CID (or an explicit historical commit)
  (let [head-dest (path/join (:cache opts) "HEAD.txt")
        chain-cid
        (or (:chain-cid opts)
            (do (when (fs/existsSync head-dest) (fs/unlinkSync head-dest)) ; head is MUTABLE, never cached
                (when-not (r2-get! (head-key (:graph opts)) head-dest)
                  (cannot-answer! "could not read the graph head pointer"
                                  {:key (head-key (:graph opts))}))
                (str/trim (fs/readFileSync head-dest "utf8"))))
        _ (when (:chain-cid opts)
            (println "  NOTE: walking an explicit --chain-cid, not the live head"))

        ;; 2. chain tip → opaque state (chain.core verifies the commit's CID).
        ;; NB: destructure `:seq` to `chain-seq` — binding it to `seq` shadows
        ;; clojure.core/seq for the whole body.
        {state :state chain-seq :seq}
        (or (try (commit-at chain-cid)
                 (catch :default e
                   (cannot-answer! "could not read the chain head commit"
                                   {:chain-cid chain-cid :error (ex-message e)})))
            (cannot-answer! "the chain head commit block is missing from R2"
                            {:chain-cid chain-cid}))

        _ (when (contains? state "novelty")
            (cannot-answer! "this chain is in the LEGACY flat-vector novelty shape"
                            {:note "legacy-novelty-state? is true; there are no chain nodes to walk"}))

        expected (get state "novelty-count")
        front-cid (link->cid (get state "novelty-front"))
        back-cid  (link->cid (get state "novelty-back"))
        indexed-cid (link->cid (get state "indexed"))]

    (println "  chain tip  " chain-cid)
    (println "  seq        " chain-seq)
    (println "  indexed    " (or indexed-cid "nil (never folded)"))
    (println "  novelty-count (the O(1) field) " expected)
    (println "  novelty-front " (or front-cid "nil"))
    (println "  novelty-back  " (or back-cid "nil"))
    (println)
    (print "walking front ") (flush)
    (let [front (walk! "front" front-cid)
          _ (println)
          _ (print "walking back  ")
          back (walk! "back" back-cid)
          _ (println)
          ft (tally (:nodes front))
          bt (tally (:nodes back))
          scanned (+ (:entries front) (:entries back))
          hops (+ (:nodes ft) (:nodes bt))
          round-trips (+ hops (:prolly-waves opts))
          agree? (= scanned expected)]

      (println)
      (println "── nodes by shape ──────────────────────────────────────────")
      (println (str "  front  nodes " (:nodes ft)
                    "  singles " (:singles ft) "  segments " (:segments ft)
                    "  entries " (:entries front)))
      (println (str "  back   nodes " (:nodes bt)
                    "  singles " (:singles bt) "  segments " (:segments bt)
                    "  entries " (:entries back)))
      (println (str "  TOTAL  nodes " hops
                    "  singles " (+ (:singles ft) (:singles bt))
                    "  segments " (+ (:segments ft) (:segments bt))))
      (when (seq (:segment-fill bt))
        (println "  back segment fill (entries per segment, walk order):" (:segment-fill bt)))
      (when (seq (:segment-fill ft))
        (println "  front segment fill:" (:segment-fill ft)))
      (println)
      (println "── entries: scanned vs expected ────────────────────────────")
      (println (str "  SCANNED " scanned " / EXPECTED " expected
                    "   (expected = the state's O(1) \"novelty-count\")"))
      (if agree?
        (println "  the two counts AGREE, so the node count below is over the whole chain")
        (do (println)
            (println "  *** THE TWO COUNTS DISAGREE ***")
            (println "  Walking the chain found" scanned "entries; the state field says" expected ".")
            (println "  One of these two numbers is wrong. The node count below is over the")
            (println "  chain that was ACTUALLY WALKED, which is a complete walk (rest reached")
            (println "  nil) — so this is a disagreement between two live sources, not a")
            (println "  truncated scan.")))
      (println)
      (println "── implied cost ────────────────────────────────────────────")
      (println (str "  hops (sequential novelty block fetches) " hops))
      (println (str "  + prolly-tree waves " (:prolly-waves opts)
                    "  = round trips " round-trips))
      (println (str "  measured handler cost " (:ms-min opts) "–" (:ms-max opts) "ms"
                    "  →  per round trip "
                    (round1 (/ (:ms-min opts) round-trips)) "–"
                    (round1 (/ (:ms-max opts) round-trips)) "ms"))
      (println)
      (println (str "  R2 GETs issued this run " @fetch-count
                    " (cache hits " @cache-hits ")"))

      (let [scan
            (when-let [limit (:scan-commits opts)]
              (println)
              (println "── commit scan: when did this graph last fold? ─────────────")
              (print (str "scanning back from seq " chain-seq " (limit " limit ") "))
              (flush)
              (let [s (scan-commits! chain-cid limit)]
                (println)
                (println (str "  commits scanned " (:scanned s) " / limit " (:limit s)))
                (case (:fold s)
                  :found
                  (let [{:keys [pre-fold-seq pre-fold-count post-fold-seq post-fold-count]}
                        (:fold-boundary s)]
                    (println (str "  FOLD OBSERVED between seq " pre-fold-seq
                                  " (novelty-count " pre-fold-count ")"
                                  " and seq " post-fold-seq
                                  " (novelty-count " post-fold-count ")")))
                  :not-within-limit
                  (println (str "  NO fold within the last " (:scanned s) " commits."
                                " This does NOT mean the graph has never folded —"
                                " the scan is bounded and did not reach genesis."))
                  :chain-genesis-reached
                  (println "  reached genesis without seeing a fold")
                  :scan-incomplete
                  (println (str "  SCAN INCOMPLETE at " (:incomplete-at s)
                                " — a block was unreadable, so this scan answers nothing")))
                s))]

      (when-let [out (:out opts)]
        (fs/writeFileSync
         out
         (with-out-str
           (println
            (pr-str
             {:observation :kotobase/novelty-chain-node-shapes
              :read-only true
              :graph (:graph opts) :bucket (:bucket opts)
              :chain-cid chain-cid :chain-seq chain-seq
              :indexed indexed-cid
              :novelty-count-field expected
              :entries-scanned scanned
              :counts-agree? agree?
              :front (assoc ft :entries (:entries front) :head front-cid)
              :back (assoc bt :entries (:entries back) :head back-cid)
              :hops hops
              :prolly-waves (:prolly-waves opts)
              :round-trips round-trips
              :handler-ms [(:ms-min opts) (:ms-max opts)]
              :implied-per-round-trip-ms [(round1 (/ (:ms-min opts) round-trips))
                                          (round1 (/ (:ms-max opts) round-trips))]
              :front-node-cids (mapv :cid (:nodes front))
              :back-node-cids (mapv :cid (:nodes back))
              :commit-scan (when scan
                             {:scanned (:scanned scan) :limit (:limit scan)
                              :result (:fold scan)
                              :boundary (:fold-boundary scan)
                              :novelty-count-by-seq
                              (mapv (juxt :seq :novelty-count) (:rows scan))})}))))
        (println "  wrote" out))

      (.exit js/process (if agree? 0 1))))))

(-main)

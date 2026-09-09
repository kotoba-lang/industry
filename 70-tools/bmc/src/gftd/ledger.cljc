(ns gftd.ledger
  "Append-only canvas ledger — 1 EDN event map per line.
   Never rewritten, never reordered; the fold (gftd.canvas) is the only reader
   that gives events meaning. Event shape:
   {:event/seq 1 :event/at \"2026-07-02T…\" :event/actor \"cli:itonami\"
    :event/type :canvas/add-item|:canvas/retract-item|:canvas/note|:hyp/status
                |:react/observation|:react/thought|:governor/rejected
    :canvas/id … :hyp/id … :event/value … :event/evidence … :event/reason … :event/tick …}"
  (:require [clojure.string :as str]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            #?(:cljs [scripts.nbb-compat :as nc])))

(defn parse-events
  "Parse ledger file content (string) → vector of event maps."
  [s]
  (into []
        (comp (remove str/blank?)
              (remove #(str/starts-with? (str/trim %) ";"))
              (map edn/read-string))
        (str/split-lines (or s ""))))

(defn next-seq
  "The next seq to issue.

  `floor` is a high-water mark that survives the file being rewritten. It is
  needed because the ledger is not a monotonic record of what this machine has
  issued: a main sync reverts it to origin/main, dropping lines that were never
  landed. Counting only the file then re-issues the numbers those dropped
  events already used, and git merging the two copies puts two events on one
  seq.

  Measured 2026-08-31 on the committed canvas-ledger: of 362 colliding seq
  values the SMALLEST gap between the two events was 32 seconds, the median
  about an hour and the largest 3.1 days. Nothing that slow is two writers
  racing between a read and a write, which is what this was documented as -- so
  a lock around the append would have fixed none of it."
  ([events] (next-seq events 0))
  ([events floor] (inc (max (or floor 0) (reduce max 0 (keep :event/seq events))))))

(defn stamp
  "Assign :event/seq (continuing from existing) and :event/at to new events."
  ([existing at new-events] (stamp existing at new-events 0))
  ([existing at new-events floor]
   (let [n0 (next-seq existing floor)]
     (vec (map-indexed (fn [i e] (assoc e :event/seq (+ n0 i) :event/at at)) new-events)))))

(defn high-water
  "Highest seq present in `events`, or 0. The value to persist after an append."
  [events]
  (reduce max 0 (keep :event/seq events)))

(defn ledger-key
  "The LOGICAL identity of a ledger: its path relative to the repository root.

  Keying the mark on the absolute path was wrong, and wrong in the direction
  that matters. Measured 2026-08-31, hours after the floor landed: two mark
  files existed for one ledger --

    …com-junkawasaki_90-docs_business_canvas-ledger.edn.seq          13968
    …T_itonami-qwen36-1788174887288_90-docs_business_canvas…seq      13966

  The second is `cloud.itonami.bot.itonami-qwen36-tick`, which by design builds a
  sibling worktree per run (`(str (.tmpdir os) \"/itonami-qwen36-\" ts)`) so it
  never touches the shared checkout, and deletes it afterwards. Every tick
  therefore got a NEW absolute path, a NEW key, and a floor of zero -- the
  writer the floor most needed to constrain was the one it did not constrain,
  and each run leaked one more orphan mark file.

  Relative to the repository root both spellings are
  `90-docs/business/canvas-ledger.edn`, so they share one floor."
  [git-root ledger-path]
  (let [p (str ledger-path)
        root (when (seq (str git-root)) (str/replace (str git-root) #"/+$" ""))]
    (if (and root (str/starts-with? p (str root "/")))
      (subs p (inc (count root)))
      ;; no root to relativise against: fall back to the last two segments,
      ;; which still collapses two checkouts of the same ledger onto one key
      (let [segs (remove str/blank? (str/split p #"/"))]
        (str/join "/" (take-last 2 segs))))))

(defn hwm-file
  "Where the high-water mark for a ledger lives.

  Deliberately NOT beside the ledger and not inside the repository: the whole
  point is to survive `git checkout` reverting the ledger, and anything tracked
  or ignorable inside the tree can be reverted or cleaned with it. `~/.itonami`
  is where this workspace keeps machine-local loop state.

  The directory was `~/.gftd` until 2026-09-09. `gftd` is retired
  (manifest/gftd-retirement.edn), and a hardcoded path under a retired name is
  not inert: a missing parent is created rather than reported, so this line
  silently resurrected `~/.gftd` after the home-directory cutover had removed
  it. The high-water mark migrates with the directory -- moving the path without
  carrying the value forward would let seq numbers roll back, which is the exact
  collision this floor exists to prevent."
  ([home ledger-path] (hwm-file home nil ledger-path))
  ([home git-root ledger-path]
   (str home "/.itonami/ledger-hwm/"
        (-> (ledger-key git-root ledger-path)
            (str/replace #"[^A-Za-z0-9._-]" "_"))
        ".seq")))

(defn parse-hwm
  "Stored high-water mark → int. Anything unreadable is 0, which is exactly the
  pre-existing behaviour (count the file alone), so a missing or corrupt mark
  degrades to the old bug rather than to a wrong number."
  [s]
  (let [n (when s #?(:clj (try (Long/parseLong (str/trim s)) (catch Exception _ nil))
                     :cljs (let [x (js/parseInt (str/trim s) 10)]
                             (when-not (js/isNaN x) x))))]
    (if (and n (nat-int? n)) n 0)))

#?(:clj
   (do
     (defn read-events [path]
       (let [f (java.io.File. ^String path)]
         (if (.exists f) (parse-events (slurp f)) [])))

     (defn- git-root-of [path]
       ;; the repository root the ledger belongs to; nil when git cannot say,
       ;; in which case ledger-key falls back to the last two path segments
       (try
         (let [d (.getParent (java.io.File. ^String path))
               p (.. (ProcessBuilder. ["git" "-C" (str d) "rev-parse" "--show-toplevel"])
                     (redirectErrorStream true) start)
               out (slurp (.getInputStream p))]
           (when (zero? (.waitFor p)) (clojure.string/trim out)))
         (catch Exception _ nil)))

     (defn- read-hwm [path]
       (let [f (java.io.File. ^String (hwm-file (System/getProperty "user.home") (git-root-of path) path))]
         (if (.exists f) (parse-hwm (slurp f)) 0)))

     (defn- write-hwm! [path n]
       (let [f (java.io.File. ^String (hwm-file (System/getProperty "user.home") (git-root-of path) path))]
         (when-let [p (.getParentFile f)] (.mkdirs p))
         (spit f (str n))))

     (defn- ends-with-newline? [^java.io.File f]
       (or (not (.exists f)) (zero? (.length f))
           (let [s (slurp f)] (str/ends-with? s "\n"))))

     (defn append!
       "Stamp and append events to the ledger file. Returns the stamped events."
       [path events]
       (let [existing (read-events path)
             at (str (java.time.Instant/now))
             stamped (stamp existing at events (read-hwm path))
             f (java.io.File. ^String path)]
         (when-let [p (.getParentFile f)] (.mkdirs p))
         ;; without this a file not ending in a newline swallows the event: the
         ;; appended map joins the last line and edn/read-string returns only
         ;; the first form, so the line parses and the event is simply gone
         (when-not (ends-with-newline? f) (spit f "\n" :append true))
         (spit f (apply str (map #(str (pr-str %) "\n") stamped)) :append true)
         (write-hwm! path (high-water stamped))
         stamped)))

   :cljs
   (do
     (defn read-events [path]
       (let [f (nc/file path)]
         (if (.exists f) (parse-events (nc/slurp f)) [])))

     (defn- git-root-of [path]
       (try
         (let [cp (js/require "node:child_process")
               d (.dirname (js/require "node:path") path)
               r (.spawnSync cp "git" (clj->js ["-C" d "rev-parse" "--show-toplevel"])
                             #js {:encoding "utf8"})]
           (when (zero? (or (.-status r) 1)) (str/trim (str (.-stdout r)))))
         (catch :default _ nil)))

     (defn- hwm-path* [path]
       (hwm-file (.homedir (js/require "node:os")) (git-root-of path) path))

     (defn- read-hwm [path]
       (let [f (nc/file (hwm-path* path))]
         (if (.exists f) (parse-hwm (nc/slurp f)) 0)))

     ;; nc/spit already creates the parent directory
     (defn- write-hwm! [path n] (nc/spit (hwm-path* path) (str n)))

     (defn- ends-with-newline? [path]
       (let [f (nc/file path)]
         (or (not (.exists f))
             (let [s (nc/slurp f)] (or (= "" s) (str/ends-with? s "\n"))))))

     (defn append!
       "Stamp and append events to the ledger file. Returns the stamped events."
       [path events]
       (let [existing (read-events path)
             at (.toISOString (js/Date.))
             stamped (stamp existing at events (read-hwm path))
             f (nc/file path)]
         (some-> (.getParentFile f) .mkdirs)
         ;; see the :clj branch — a missing trailing newline silently eats the event
         (when-not (ends-with-newline? path) (nc/spit-append f "\n"))
         (nc/spit-append f (apply str (map #(str (pr-str %) "\n") stamped)))
         (write-hwm! path (high-water stamped))
         stamped))))

#!/usr/bin/env nbb
;; scripts/adr-new.cljs — allocate an ADR id and create its file atomically.
;;
;; Why this exists: on 2026-07-25 two concurrent sessions both created an ADR
;; with id 2607252000 (shiropico-datalad-b2-asset-custody and
;; kotobase-lei-catalog-cpu-outage-async-block-discovery). Both had "checked"
;; first — `ls 90-docs/adr/ | grep <id>` — and both saw the id free, because
;; check-then-act is not atomic when many agents run at once. The ledger already
;; solved the identical problem for :event/seq with an exclusive lock
;; (scripts/adr-ledger-append.cljs); ADR ids simply never got the same
;; treatment. This script is that treatment.
;;
;; The reservation is the file creation itself: O_EXCL ("wx") fails if the path
;; already exists, so the winner of a race is decided by the filesystem rather
;; than by who read the directory listing last. On collision we advance to the
;; next candidate id and retry, so concurrent callers deterministically get
;; distinct ids instead of silently sharing one.
;;
;; A numeric id can also be taken by a *differently-named* file (same id,
;; different slug — exactly the 2607252000 case), so we additionally refuse any
;; id whose numeric key already appears on disk, normalising both :adr/id
;; spellings the way adr-ledger-append.cljs does.
;;
;; Usage:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/adr-new.cljs \
;;     --slug my-decision-slug --title "タイトル" [--status draft] \
;;     [--id-style bare|prefixed] [--id 2607252100]
;;
;; Prints the allocated id and path. Does not commit.

(require '[scripts.nbb-compat :refer [slurp file file-seq exit format]]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def adr-dir "90-docs/adr")

(defn- die [& msg]
  (binding [*out* *err*] (apply println msg))
  (exit 1))

(defn canonical-key
  "Same normalisation as scripts/adr-ledger-append.cljs: `2607252000` and
   `adr-2607252000-slug` are the same id. Kept in sync deliberately — an
   allocator that disagreed with the verifier about identity would hand out ids
   the verifier then calls duplicates."
  [id]
  (when (some? id)
    (let [s (str id)
          s (if (str/starts-with? s "adr-") (subs s 4) s)]
      (or (re-find #"^\d+" s) s))))

(defn taken-keys
  "Numeric keys already in use, from BOTH the filename prefix and the internal
   :adr/id. Filenames alone are not enough (a file may carry an id that differs
   from its name), and internal ids alone are not enough (a file that fails to
   parse still occupies its id)."
  []
  (let [files (->> (file-seq (file adr-dir))
                   (map str)
                   (filter #(str/ends-with? % ".edn")))
        from-name (keep (fn [p]
                          (let [base (last (str/split p #"/"))]
                            (re-find #"^\d+" base)))
                        files)
        from-body (mapcat (fn [p]
                            (try
                              (let [c (edn/read-string {:default (fn [_ v] v)} (slurp p))]
                                (cond
                                  (and (vector? c) (every? map? c)) (keep (comp canonical-key :adr/id) c)
                                  (map? c) [(canonical-key (:adr/id c))]
                                  :else nil))
                              (catch :default _ nil)))
                          files)]
    (set (remove nil? (concat from-name from-body)))))

(defn- pad [n w] (let [s (str n)] (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn- date-candidate
  "YYMMDD + HHMM, matching the existing id shape (e.g. 2607252000 =
   2026-07-25 20:00). Only a starting point — collisions walk forward."
  []
  (let [d (js/Date.)]
    (str (pad (mod (.getFullYear d) 100) 2)
         (pad (inc (.getMonth d)) 2)
         (pad (.getDate d) 2)
         (pad (.getHours d) 2)
         (pad (.getMinutes d) 2))))

(defn- skeleton [id style slug title status]
  (let [adr-id (if (= style "prefixed") (str "adr-" id "-" slug) id)
        date (let [d (js/Date.)]
               (str (.getFullYear d) "-" (pad (inc (.getMonth d)) 2) "-" (pad (.getDate d) 2)))]
    (str "[{:db/id -1\n"
         "  :adr/id \"" adr-id "\"\n"
         "  :adr/title " (pr-str title) "\n"
         "  :adr/status \"" status "\"\n"
         "  :adr/date \"" date "\"\n"
         "  :adr/body \"## Context\\n\\nTODO\\n\\n## Decision\\n\\nTODO\\n\\n## Consequences\\n\\nTODO\\n\"}]\n")))

(defn- arg [argv k] (second (drop-while #(not= % k) argv)))

(let [argv (vec *command-line-args*)
      slug (arg argv "--slug")
      title (arg argv "--title")
      status (or (arg argv "--status") "draft")
      style (or (arg argv "--id-style") "bare")
      forced (arg argv "--id")]
  (when (or (str/blank? slug) (str/blank? title))
    (die "usage: nbb scripts/adr-new.cljs --slug <slug> --title \"...\" [--status draft] [--id-style bare|prefixed] [--id <id>]"))
  (when-not (#{"bare" "prefixed"} style)
    (die "--id-style must be bare or prefixed"))
  (let [taken (taken-keys)]
    (loop [id (or forced (date-candidate))
           tries 0]
      (when (> tries 2000)
        (die "no free ADR id found after 2000 attempts"))
      (let [path (str adr-dir "/" id "-" slug ".edn")]
        (if (contains? taken id)
          (if forced
            (die (format "ADR id %s は既に使用されています。--id を外して自動採番してください。" id))
            (recur (str (inc (js/parseInt id 10))) (inc tries)))
          ;; O_EXCL: the filesystem, not a prior directory listing, decides the
          ;; winner of a concurrent race.
          (let [fd (try (.openSync fs path "wx")
                        (catch :default e
                          (if (= (.-code e) "EEXIST") nil (throw e))))]
            (if (nil? fd)
              (if forced
                (die (format "%s は既に存在します。" path))
                (recur (str (inc (js/parseInt id 10))) (inc tries)))
              (do
                (.writeSync fs fd (skeleton id style slug title status))
                (.closeSync fs fd)
                (println (format "allocated ADR id %s (style=%s)" id style))
                (println path)))))))))

#!/usr/bin/env nbb
(ns prune-cache
  "Reclaim the fleet-ci ship cache.

  `~/.itonami/fleet-ci-cache` holds one tarball per (repo, sha, :include-ext).
  The key pins an immutable sha, and `tick.cljs` looks entries up with nothing
  but `existsSync`, so an entry stops being reachable the moment that repo's
  tip moves — and nothing has ever deleted one. Measured 2026-08-27: 17,370
  tarballs, 179 GB, on a volume at 99%.

  Deleting is safe by construction rather than by judgement. Both producers —
  `filtered-tarball!` and `full-tarball!` — are `if (existsSync f) f (build f)`,
  so the worst a miss can cost is one `git archive` from the local mirror. That
  is the whole reason this can be a plain age sweep instead of a liveness
  analysis: being wrong is slow, not incorrect.

  Still, pinned shas are kept regardless of age. A pin that has not moved in a
  month is the case where age is most misleading — the tarball is old *and*
  live — and rebuilding the superproject's is minutes, not seconds.

  Dry by default. `--apply` deletes.

  exit 0  swept (or would sweep)
  exit 2  REFUSED: could not measure. Never the same code as `nothing to do`."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]))

(def argv (vec (or *command-line-args* [])))
(defn flag? [f] (some? (some #{f} argv)))
(defn arg [f d] (let [i (.indexOf argv f)] (if (neg? i) d (get argv (inc i) d))))

(def cache-dir (arg "--cache" (path/join (os/homedir) ".itonami" "fleet-ci-cache")))
;; Resolved from the working directory, not from this file: nbb's ESM loader
;; gives no `__dirname`, and a wrong guess here silently becomes "no pins",
;; which the refusal below turns into a stop rather than a sweep.
(def west-yml (arg "--west" (path/resolve "manifest" "west.yml")))
(def max-age-days (js/parseFloat (arg "--max-age-days" "7")))
(def apply? (flag? "--apply"))

(defn refuse! [msg]
  (js/console.error (str "REFUSED: " msg))
  (.exit js/process 2))

(defn gb [bytes] (/ bytes 1073741824.0))
(defn fmt-gb [bytes] (.toFixed (gb bytes) 1))

(defn pinned-shas
  "Every 12-char sha prefix west.yml pins. Read from the file rather than
  `git`, because this runs on the operator host where that file IS the pin
  set the tick will ship next."
  []
  (when-not (fs/existsSync west-yml)
    (refuse! (str "no manifest at " west-yml
                  " — the pinned shas are unknown, and sweeping without them"
                  " could delete a live pin's tarball on every run")))
  (let [src (fs/readFileSync west-yml "utf8")
        shas (map #(subs (second %) 0 12)
                  (re-seq #"revision:\s*([0-9a-f]{40})" src))]
    (when (empty? shas)
      (refuse! (str west-yml " parsed to zero pins — refusing to treat that as"
                    " `nothing is pinned`")))
    (set shas)))

(defn entries []
  (when-not (fs/existsSync cache-dir)
    (refuse! (str "no cache at " cache-dir)))
  (->> (fs/readdirSync cache-dir)
       (filter #(or (str/ends-with? % ".tar.gz") (str/ends-with? % ".bundle")))
       (keep (fn [name]
               (let [p (path/join cache-dir name)
                     st (try (fs/statSync p) (catch :default _ nil))]
                 (when (and st (.isFile st))
                   ;; `<org>-<repo>-<sha12>[-filtered<exts>|-self|-dep].(tar.gz|bundle)`
                   (let [m (re-find #"-([0-9a-f]{12})(?:-|\.)" name)]
                     {:name name :path p :size (.-size st)
                      :mtime (.getTime (.-mtime st))
                      :sha (second m)})))))
       vec))

(defn -main []
  (let [pins (pinned-shas)
        all (entries)
        _ (when (empty? all)
            (refuse! (str "cache at " cache-dir " holds no tarballs — that is not"
                          " a clean sweep, it is a cache that is not where it was")))
        now (.getTime (js/Date.))
        cutoff (* max-age-days 86400000)
        classify (fn [{:keys [sha mtime]}]
                   (cond (and sha (contains? pins sha)) :pinned
                         (< (- now mtime) cutoff) :recent
                         (nil? sha) :unparsed
                         :else :stale))
        by (group-by classify all)
        total (reduce + 0 (map :size all))
        sum #(reduce + 0 (map :size (get by % [])))
        stale (get by :stale [])]

    (println "# fleet-ci cache sweep")
    (println (str "cache\t" cache-dir))
    (println (str "policy\tdelete tarballs older than " max-age-days
                  "d whose sha is not pinned in west.yml"))
    (println (str "mode\t" (if apply? "APPLY" "dry-run (pass --apply to delete)")))
    (println)
    (println (str "pins-in-manifest\t" (count pins)))
    (doseq [k [:pinned :recent :unparsed :stale]]
      (println (str (name k) "\t" (count (get by k []))
                    " files\t" (fmt-gb (sum k)) " GB"
                    (case k
                      :pinned "\tkept — west.yml pins this sha"
                      :recent (str "\tkept — newer than " max-age-days "d")
                      :unparsed "\tkept — no sha in the name; not ours to judge"
                      :stale "\tDELETE"))))
    (println)
    (println (str "total\t" (count all) " files\t" (fmt-gb total) " GB"))
    (println (str "reclaim\t" (count stale) " files\t" (fmt-gb (sum :stale)) " GB"))
    (println)

    (if-not apply?
      (println "SWEPT\t0\t(dry run — nothing was deleted)")
      (let [freed (atom 0) gone (atom 0) failed (atom 0)]
        (doseq [{:keys [path size]} stale]
          (try (fs/unlinkSync path) (swap! freed + size) (swap! gone inc)
               (catch :default _ (swap! failed inc))))
        (println (str "SWEPT\t" @gone "\tfiles\t" (fmt-gb @freed) " GB reclaimed"))
        (when (pos? @failed)
          (println (str "FAILED\t" @failed "\tfiles could not be unlinked")))))))

(-main)

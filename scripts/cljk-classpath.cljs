#!/usr/bin/env nbb
;; scripts/cljk-classpath.cljs — make a classpath loadable after the .cljk rename.
;;
;; Owner decision 2026-09-11 (b): the .cljk rename is authoritative and build
;; breakage is fixed forward. Measured the same day: nbb resolves a namespace
;; by trying exactly [.cljs .cljc .clj] (a fixed vector in nbb_core.js), JVM
;; Clojure tries [.clj .cljc] (clojure.lang.RT, no hook), and shadow-cljs scans
;; for [.cljs .cljc]. A renamed repo's main therefore fails its own test
;; runner with `Could not find namespace` on every one of them.
;;
;; Each renamed repo carries `cljk-origin.edn`, which records the extension
;; every .cljk file had before the rename. That record is exactly what a
;; loader needs: for every classpath entry whose repo has one, this builds a
;; symlink farm OUTSIDE the repo -- `~/.cache/cljk-mirror/<sha1 of the entry>/`
;; -- holding `foo/bar.cljc -> /abs/path/foo/bar.cljk` per the record, and
;; prints the classpath with those entries replaced. Nothing inside any repo
;; is written, which is what keeps this a loader and not the compatibility
;; mirror the rename deliberately refused to commit.
;;
;;   nbb scripts/cljk-classpath.cljs "$(clojure -Spath -A:test)"
;;   nbb --classpath "$(nbb scripts/cljk-classpath.cljs "$(clojure -Spath -A:test)")" bin/run_tests.cljk
;;   clojure -Scp "$(nbb scripts/cljk-classpath.cljs "$(clojure -Spath -A:test)")" \
;;     -M -m cognitect.test-runner -d <the mirror printed for test/ on stderr>
;;
;; ⚠ The JVM runner needs `-d`: cognitect.test-runner discovers tests by
;; scanning a DIRECTORY for *_test.clj*, and the repo's own test/ now holds
;; only .cljk, so without `-d` it reports `Ran 0 tests ... 0 failures` --
;; green for having found nothing. Measured 2026-09-11 on kotobase-protocol-ipq
;; before this note existed. clj-kondo `--lint src test` has the same shape:
;; `0 errors in 7ms` over directories it read nothing from.
;;
;; What it does NOT cover: shadow-cljs computes its own classpath from
;; deps.edn and offers no hook to substitute entries, so a Worker build that
;; depends on a renamed git library cannot see it through this. That gap is
;; stated in the exit code: entries this script mirrored are listed on
;; stderr, and a consumer whose build tool ignores the printed classpath is
;; not helped by having printed it.
;;
;; Exit 0 = printed. 2 = an entry claims a cljk-origin.edn this script could
;; not read (refused rather than passed through as if unrenamed).

(ns cljk-classpath
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            ["crypto" :as crypto]))

(def cache-root (path/join (os/homedir) ".cache" "cljk-mirror"))

(defn- repo-root-of
  "Walk up from a classpath entry to the directory holding cljk-origin.edn,
   or nil. Bounded: a classpath entry is at most a few levels below its repo."
  [entry]
  (loop [d (path/resolve entry) n 0]
    (cond
      (fs/existsSync (path/join d "cljk-origin.edn")) d
      (or (>= n 4) (= d (path/dirname d))) nil
      :else (recur (path/dirname d) (inc n)))))

(defn- read-origins! [repo]
  (let [f (path/join repo "cljk-origin.edn")
        v (try (edn/read-string (fs/readFileSync f "utf8"))
               (catch :default e (throw (ex-info "cljk-origin.edn unreadable" {:file f :cause (str e)}))))]
    (when-not (map? (:origins v))
      (throw (ex-info "cljk-origin.edn has no :origins map" {:file f})))
    (:origins v)))

(defn- mirror-for!
  "The mirror directory for one classpath entry, built if absent. Returns the
   mirror path, or the entry itself when nothing under it was renamed."
  [entry repo origins]
  (let [abs (path/resolve entry)
        rel-entry (path/relative repo abs)
        under (filter (fn [[p _]] (or (= rel-entry "") (str/starts-with? p (str rel-entry "/")))) origins)]
    (if (empty? under)
      entry
      (let [key (-> (crypto/createHash "sha1") (.update abs) (.digest "hex"))
            mirror (path/join cache-root key)]
        (doseq [[p ext] under]
          (let [src (path/join repo p)
                ;; foo/bar.cljk -> foo/bar<ext>, relative to the entry
                out-rel (str (subs (path/relative abs src) 0
                                   (- (count (path/relative abs src)) (count ".cljk")))
                             ext)
                out (path/join mirror out-rel)]
            (fs/mkdirSync (path/dirname out) (js-obj "recursive" true))
            (when (fs/existsSync out) (fs/unlinkSync out))
            (fs/symlinkSync src out)))
        (.write js/process.stderr
                (str "MIRRORED\t" abs "\t-> " mirror "\t" (count under) " file(s)\n"))
        mirror))))

(defn -main [cp]
  (when (str/blank? cp)
    (.write js/process.stderr "usage: cljk-classpath.cljs <classpath>\n")
    (.exit js/process 2))
  (let [entries (str/split cp #":")
        out (try (mapv (fn [e]
                         (if-let [repo (repo-root-of e)]
                           (mirror-for! e repo (read-origins! repo))
                           e))
                       entries)
                 (catch :default e
                   (.write js/process.stderr (str "REFUSED\t" (ex-message e) "\t" (pr-str (ex-data e)) "\n"))
                   (.exit js/process 2)))]
    (println (str/join ":" out))))

(-main (first *command-line-args*))

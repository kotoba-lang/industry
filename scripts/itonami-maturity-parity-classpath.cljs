;; scripts/itonami-maturity-parity-classpath.cljs
;;
;; Print the --classpath the kernel parity gate needs, derived from deps.edn.
;;
;; Why this is a script and not three lines of shell in the skill:
;;
;;   The parity gate's failure mode is that it does not START. A missing
;;   namespace exits 1 — the same value a real FAIL exits — so a gate that
;;   never ran is indistinguishable from a gate that ran and found nothing.
;;   Three separate rounds landed a measurement whose score arithmetic was
;;   never actually checked against the Kotoba kernel:
;;
;;     2026-08-08  kotoba.artifact.core       no closure was passed at all
;;     2026-08-09  kotoba.compiler.frontend   ns moved compiler -> kotoba-sema
;;     2026-08-31  sha2.core                  closure was derived ONE level deep
;;
;; The third one is the reason this file exists. The skill already said
;; "never hand-write the closure, derive it from amu/deps.edn every time" —
;; and that was being obeyed. But a one-level derivation cannot see past the
;; first level, and dependencies are transitive. org-nist-sha2 (which owns
;; sha2.core) is reached via security, not directly from amu.
;;
;; The same round then hit a second, unrelated bug when the fix was written
;; as shell: `for r in $CLOSURE` does not word-split in zsh, so the classpath
;; silently collapsed to two entries. Both bugs share one shape — the
;; classpath came out wrong and nothing said so.
;;
;; Usage:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/itonami-maturity-parity-classpath.cljs [--root <dir>]
;;
;; Exit codes are deliberately three-valued, so "could not answer" is not
;; spelled the same way as either answer:
;;   0  a classpath was derived and every repo in the closure is checked out
;;   1  a classpath was derived AND PRINTED, but some repo in the closure has no
;;      checkout; the named repos are listed on stderr. The gate may still run
;;      (a closure member the gate never loads costs nothing) — this is an
;;      advisory, so callers that want it should use `CP=$(... ) || true`.
;;   2  REFUSED: the walk could not start (no amu/deps.edn under --root).
;;      Nothing is printed to stdout — a partial classpath is worse than none,
;;      because it fails later with a namespace error that looks like a FAIL.

(ns itonami-maturity-parity-classpath
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))

(defn- arg-value [flag default]
  (let [i (.indexOf args flag)]
    (if (neg? i) default (get args (inc i) default))))

(def root (arg-value "--root" (.cwd js/process)))
(def org-dir (path/join root "orgs" "kotoba-lang"))

(def dep-re #"io\.github\.kotoba-lang/([a-z0-9-]+)")

(defn- direct-deps
  "Names this repo depends on, or nil when it has no deps.edn to read.
   nil and #{} are different answers and must not be collapsed."
  [repo]
  (let [f (path/join org-dir repo "deps.edn")]
    (when (fs/existsSync f)
      (into #{} (map second) (re-seq dep-re (fs/readFileSync f "utf8"))))))

(defn transitive-closure
  "Every repo reachable from `start` through deps.edn, including `start`.
   Visited-set guarded, so a dependency cycle terminates instead of hanging."
  [start]
  (loop [queue [start] seen #{}]
    (if-let [r (first queue)]
      (if (contains? seen r)
        (recur (subvec queue 1) seen)
        (recur (into (subvec queue 1) (or (direct-deps r) #{}))
               (conj seen r)))
      seen)))

(let [closure (sort (transitive-closure "amu"))]
  (when-not (fs/existsSync (path/join org-dir "amu" "deps.edn"))
    ;; nbb does not honour (binding [*out* *err*] ...) — diagnostics written with
    ;; println land on stdout and corrupt the classpath they are describing.
    (js/console.error (str "REFUSED: no amu/deps.edn under " org-dir
                           " — cannot derive the closure, and will not print a partial one."))
    (.exit js/process 2))
  (let [missing (remove #(fs/existsSync (path/join org-dir % "src")) closure)
        entries (concat [".", "scripts/nbb_compat"]
                        (mapcat (fn [r]
                                  (keep (fn [sub]
                                          (let [p (path/join org-dir r sub)]
                                            (when (fs/existsSync p) p)))
                                        ["src" "resources"]))
                                closure))]
    (js/console.error (str "closure: " (count closure) " repos (transitive from amu)"))
    (doseq [m missing]
      (js/console.error (str "MISSING checkout: " m
                             "   ← west update --fetch smart " m)))
    (println (str/join ":" entries))
    (.exit js/process (if (seq missing) 1 0))))

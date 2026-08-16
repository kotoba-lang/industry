#!/usr/bin/env nbb
;; kip-registry-check.cljs — registry integrity for kotoba-lang/kip.
;;
;; **The checking logic is not duplicated here.** The authority is the repo's own
;; `scripts/check-kips.cljs`; this gate extracts the tree and runs it. Two
;; implementations of one check is how a state where only one of them passes
;; gets created silently (same reasoning as west-pin-policy-check.cljs and
;; repository-roles-check.cljs).
;;
;; ## What this gate does NOT check
;;
;; Coverage — "did this change to a normative surface go through a Final KIP" —
;; is not here and must not be added here. It needs the git history of whichever
;; repo owns the surface *and* this registry, and a fleet gate is handed exactly
;; one repository's tree. That check is operator-side, at
;; `scripts/verify-kip-coverage.cljs` in the superproject.
;;
;; root-permit-index is the reason this is spelled out: it was landed as a fleet
;; gate whose generator reads `<root>/orgs/cloud-itonami`, which `git ls-files`
;; in the root repo does not contain. It has run about 300 times and has never
;; been green — not because anything is broken, but because it was never able to
;; ask its question.
;;
;; Node-side: `npx nbb kip-registry-check.cljs <dir>`. <dir> is the FIRST
;; positional argument.

(ns fleet-ci.gates.kip-registry-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") args)) "."))

(defn- die! [code & msg]
  (println (str "FLEET-CI: " (str/join " " msg)))
  (js/process.exit code))

;; 90 = "the tree did not arrive as expected" (tick.cljs convention). Structural
;; defence against a false pass from a truncated extract: :include-ext filters
;; the tarball, and a filter that drops these files would otherwise leave a gate
;; that checks nothing and says so in no way at all.
(doseq [[p what] [["scripts/check-kips.cljs" "the checker"]
                  ["lang/kip-process.edn" "the process authority"]
                  ["src/kotoba/kip/core.cljc" "the core namespace"]
                  ["src/kotoba/kip/registry.cljc" "the registry namespace"]
                  ["kips" "the registry directory"]]]
  (when-not (fs/existsSync (path/join root p))
    (die! 90 p "missing after extract —" what "is not in the shipped tree")))

(def result
  (try
    {:out (cp/execSync "npx --yes nbb --classpath src scripts/check-kips.cljs ."
                       #js {:cwd root :encoding "utf8" :stdio "pipe" :timeout 600000})
     :code 0}
    (catch :default e
      {:out (str (or (.-stdout e) "") (or (.-stderr e) ""))
       :code (or (.-status e) 1)})))

(def out (str/trim (str (:out result))))
(println out)

;; The evidence floor. check-kips.cljs prints SCANNED before anything else and
;; exits 2 on an empty registry; this re-reads it rather than trusting the exit
;; code alone, because an exit code cannot say how much was looked at.
(def scanned
  (when-let [m (re-find #"(?m)^SCANNED\t(\d+)" out)]
    (js/parseInt (second m) 10)))

(cond
  (nil? scanned)
  (die! 93 "no SCANNED line in output — refusing to report a pass on a run that did not say what it looked at")

  (zero? scanned)
  (die! 94 "SCANNED 0 — the registry is empty, which is not the same as clean")

  ;; 2 is check-kips.cljs's "could not answer". Passing it through as a distinct
  ;; code keeps it from being read as either verdict.
  (= 2 (:code result))
  (die! 95 "checker could not answer (exit 2)")

  (not (zero? (:code result)))
  (die! 1 (str "kip registry has findings (exit " (:code result) ")"))

  :else
  (println (str "FLEET-CI: kip registry OK (" scanned " KIP(s) checked)")))

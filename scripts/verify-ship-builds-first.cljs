#!/usr/bin/env nbb
;; Workers whose ship path can send out something nobody built.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-ship-builds-first.cljs [--findings]
;;
;; ## What this measures, and why it is not a fleet gate
;;
;; It reads `orgs/`, so a murakumo node -- shipped only the target repo's own
;; tree -- would find an empty workspace and report clean forever. That is the
;; shape ADR-2608124800 named, and it is why this lives here.
;;
;; ## The failure it looks for
;;
;; The CLI ships whatever bytes are at the configured entry point. The
;; ship guard checks the CHECKOUT's git state, not the artifact's, so a
;; checkout perfectly in step with main can send out a bundle that disagrees
;; with its own source while the guard stays green.
;;
;; It happened twice on 2026-08-31, both times to the person fixing it:
;;
;;   morning   a stale build went out over a settlement fix and put an
;;             inflated public revenue figure back
;;   evening   a build exited 127 (its compiler was not on PATH), the ship ran
;;             as a separate command, and only the second exit code was read --
;;             sending out a bundle three and a half hours old
;;
;; Two properties keep a ship path honest, and they are independent:
;;
;;   BUILDS   the script builds first, so it cannot send an artifact nobody
;;            made. `&&` also means a failed build stops the ship, which is
;;            what the second incident lacked.
;;   GUARDED  that build goes through scripts/resource-guard.mjs, so it takes
;;            its turn. On this machine a build has held that lock for twelve
;;            minutes while another session's retries were all refused.
;;
;; ## What it does NOT claim
;;
;; A script that builds first still cannot stop someone running the CLI by
;; hand -- both incidents went around the script rather than through it. This
;; measures the sanctioned path, which is the part that can be made safe.
(ns verify-ship-builds-first
  (:require ["fs" :as fs]
            ["path" :as p]
            [clojure.string :as str]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(def root (let [i (.indexOf argv "--root")] (if (neg? i) "." (nth argv (inc i) "."))))
(def findings? (boolean (some #{"--findings"} argv)))

(def ship-verb "deploy")
(def cli-name "wrangler")

(defn- refuse! [msg]
  (.write (.-stderr js/process) (str "REFUSING: " msg "\n"))
  (.exit js/process 2))

(defn- slurp* [f] (try (fs/readFileSync f "utf8") (catch :default _ nil)))

(defn- packages []
  (let [orgs (p/join root "orgs")]
    (when-not (fs/existsSync orgs)
      (refuse! (str "no orgs tree at " orgs " -- a scan of nothing is not a clean scan")))
    (for [org (js->clj (fs/readdirSync orgs))
          repo (try (js->clj (fs/readdirSync (p/join orgs org))) (catch :default _ []))
          ;; A dot-prefixed directory under an org is not a repo -- they are
          ;; another tool's scratch copies, and counting them reported four
          ;; findings against mirrors of a repo that is already correct.
          :when (not (str/starts-with? repo "."))
          :let [f (p/join orgs org repo "package.json")]
          :when (fs/existsSync f)]
      {:path (str "orgs/" org "/" repo) :file f})))

(defn- ship-shape
  "-> {:path :builds? :guarded?} for one package.json, or nil when it has no
  ship script. Reads the text rather than parsing, because a package.json that
  will not parse must still be counted rather than skipped."
  [{:keys [path file]}]
  (let [t (or (slurp* file) "")
        ;; No scripts-block extraction. `[^}]*` stops at the first closing
        ;; brace, and a guarded script contains one inside
        ;; `${COM_JUNKAWASAKI_ROOT:-../../..}` -- so the block ended mid-script
        ;; and the correct repos read as findings. Measured: BOTH=0 on a tree
        ;; with two known-good repos, twice, for two different reasons in the
        ;; same expression.
        scripts t
        ;; `[^"]*` stops at the first escaped quote, and a guarded script
        ;; contains several -- so the naive pattern truncated exactly the
        ;; scripts that were already correct and reported them as findings.
        ;; Measured: it read BOTH=0 on a tree with two known-good repos.
        line (fn [k] (second (re-find (re-pattern (str "\"" k "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")) (or scripts ""))))
        dep (line ship-verb) bld (line "build")]
    (when (and dep (str/includes? dep cli-name))
      {:path path
       :builds? (boolean (or (str/includes? dep "run build")
                             (str/includes? dep "shadow-cljs")
                             (str/includes? dep "vite build")
                             (str/includes? dep "next build")))
       :guarded? (boolean (or (str/includes? dep "resource-guard")
                              (and bld (str/includes? bld "resource-guard"))))})))

(defn -main []
  (let [pkgs (vec (packages))]
    (when (zero? (count pkgs))
      (refuse! "zero package.json found -- the scan did not run"))
    (let [ships (vec (keep ship-shape pkgs))
          unbuilt (filterv #(not (:builds? %)) ships)
          unguarded (filterv #(and (:builds? %) (not (:guarded? %))) ships)
          ok (filterv #(and (:builds? %) (:guarded? %)) ships)]
      (println (str "SCANNED\t" (count pkgs) " package.json, " (count ships)
                    " with a ship script"))
      (when (zero? (count ships))
        (refuse! "no ship scripts found at all -- either the tree is wrong or the pattern is"))
      (println (str "CAN-SHIP-UNBUILT\t" (count unbuilt)))
      (println (str "BUILDS-OUTSIDE-THE-GUARD\t" (count unguarded)))
      (println (str "BOTH\t" (count ok)))
      ;; The protocol the detector home parses: FINDING<TAB>severity<TAB>key<TAB>detail.
      ;; Without these the home reads a run that exited 1 as carrying ZERO
      ;; findings -- a detector that fires and reports nothing is the shape this
      ;; whole registry exists to prevent. The indented lines below stay because
      ;; they are what a person reads when running it by hand.
      (doseq [r (concat unbuilt unguarded)]
        (println (str "FINDING\tfail\t" (:path r) "\t"
                      (if (:builds? r)
                        "builds outside the guard"
                        "ships without building first"))))
      (when findings?
        (doseq [r (concat unbuilt unguarded)]
          (println (str "  " (if (:builds? r) "unguarded" "unbuilt  ") "\t" (:path r)))))
      (.exit js/process (if (seq (concat unbuilt unguarded)) 1 0)))))

(-main)
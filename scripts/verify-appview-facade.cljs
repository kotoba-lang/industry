#!/usr/bin/env nbb
;; scripts/verify-appview-facade.cljs — migrated appviews whose deployed handler
;; is not the file a reader opens.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-appview-facade.cljs --root <orgs-bearing checkout>
;;   ... --findings
;;
;; ## What this caught
;;
;; Measured 2026-08-15 across the 329 appview repositories in cloud-itonami and
;; etzhayyim that carry a wrangler.jsonc:
;;
;;   175  wrangler main points at the SvelteKit build output
;;   147  ship an appview/*/src/app.ts facade
;;    89  /health exists in that facade and in NO deployed route
;;   118  the deployed route turns a malformed body into {}
;;    58  the facade returns 400 InvalidJson while the deployed route sends {}
;;
;; Two consequences, both of which look fine from the source:
;;
;;   - 89 services document a health endpoint that does not answer in production.
;;     `wrangler.jsonc` deploys `svelte/.svelte-kit/cloudflare/_worker.js`, no
;;     route under `svelte/src/` serves `/health`, and nothing imports the facade.
;;     Reading the facade is worse than having no endpoint, because it says 200.
;;   - 58 have request validation in the file a reader opens and not in the file
;;     that runs: the facade answers 400 InvalidJson, the deployed route does
;;     `.catch(() => ({}))` and calls the tool with empty arguments.
;;
;; Found while writing an operator quickstart for cloud-itonami/port and then
;; asking whether it was one repository's quirk. It was not.
;;
;; ## Why this is a detector and not a fleet gate
;;
;; It reads `orgs/`, and a murakumo gate ships one repository's tree, so on a node
;; this finds an empty workspace and can never go green (ADR-2608124800, and
;; `root-permit-index` has been 0 pass / 282 fail for exactly this reason).
;;
;; ## What it does NOT claim
;;
;; It does not decide which handler should be authoritative -- that is the app
;; owner's call, and the same divergence is deliberate in a repository that has
;; retired its facade but not deleted it. It reports the divergence and the
;; consequence, keyed per repository so a fix clears one finding.
;;
;; Exit codes: 0 clean · 1 findings · 2 COULD NOT ANSWER.

(ns verify-appview-facade
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.string :as str]))

(def args (vec (drop 2 (js->clj js/process.argv))))
(def findings-mode? (boolean (some #{"--findings"} args)))
(def root (or (second (drop-while #(not= "--root" %) args)) (.cwd js/process)))
(def orgs ["cloud-itonami" "etzhayyim"])

;; Measured 2026-08-15: 329 appview repositories carry a wrangler.jsonc. A run
;; that sees materially fewer has lost its input -- an un-populated orgs/, a
;; wrong --root -- and must NOT report clean, because "no divergence" and "no
;; repositories" print the same zero.
(def floor-appviews 250)

(def findings (atom []))
(defn finding! [sev k detail] (swap! findings conj [sev k detail]))
(defn- die [code msg] (println msg) (js/process.exit code))
(defn- slurp* [p] (try (str (fs/readFileSync p "utf8")) (catch :default _ nil)))

(defn- git-files [d]
  (let [r (.spawnSync cp "git" (clj->js ["-C" d "ls-files"])
                      #js {:encoding "utf8" :maxBuffer 20000000})]
    (if (zero? (.-status r))
      (remove str/blank? (str/split-lines (str (.-stdout r))))
      [])))

(defn- inspect
  "One appview repository -> what its facade and its deployed route each do."
  [repo-path files wrangler]
  (let [dir (path/dirname wrangler)
        wtext (str (slurp* (path/join repo-path wrangler)))
        main (second (re-find #"\"main\"\s*:\s*\"([^\"]+)\"" wtext))
        facade-rel (str dir "/src/app.ts")
        facade (when (some #{facade-rel} files) facade-rel)
        ftext (when facade (str (slurp* (path/join repo-path facade))))
        routes (filter #(and (str/starts-with? % (str dir "/svelte/src/"))
                             (str/ends-with? % "+server.ts"))
                       files)
        rtext (str/join "\n" (keep #(slurp* (path/join repo-path %)) routes))]
    {:main main
     :deploys-svelte? (boolean (and main (str/includes? main ".svelte-kit")))
     :facade? (boolean facade)
     :facade-health? (boolean (and ftext (str/includes? ftext "\"/health\"")))
     :facade-guard? (boolean (and ftext (str/includes? ftext "InvalidJson")))
     :route? (boolean (seq routes))
     :route-health? (boolean (and (seq routes) (str/includes? rtext "health")))
     :route-silent-empty? (boolean (str/includes? rtext "catch(() => ({}))"))}))

(let [repos (for [org orgs
                  d (let [p (path/join root "orgs" org)]
                      (if (fs/existsSync p) (sort (js->clj (fs/readdirSync p))) []))
                  :let [rp (path/join root "orgs" org d)]
                  :when (fs/existsSync (path/join rp ".git"))
                  :let [files (git-files rp)
                        wrangler (first (filter #(str/ends-with? % "wrangler.jsonc") files))]
                  :when wrangler]
              [(str "orgs/" org "/" d) (inspect rp files wrangler)])
      rows (vec repos)]
  (when (< (count rows) floor-appviews)
    (die 2 (str "CANNOT ANSWER: found " (count rows) " appview repositories with a"
                " wrangler.jsonc, floor " floor-appviews ". orgs/ is probably not"
                " populated at --root " root ", and reporting no divergence from"
                " no repositories would be indistinguishable from a clean fleet.")))
  (doseq [[repo r] rows]
    ;; the operational one: a documented health endpoint that does not answer
    (when (and (:deploys-svelte? r) (:facade-health? r) (not (:route-health? r)))
      (finding! "medium" (str "health-only-in-undeployed-facade:" repo)
                (str "appview/*/src/app.ts serves /health and no route under"
                     " svelte/src/ does, while wrangler main deploys "
                     (:main r) " -- health-checking this service at /health hits"
                     " the SvelteKit 404")))
    ;; the correctness one: validation in the file you read, not the one that runs
    (when (and (:deploys-svelte? r) (:facade-guard? r) (:route-silent-empty? r))
      (finding! "medium" (str "validation-only-in-undeployed-facade:" repo)
                (str "the facade answers 400 InvalidJson on a malformed body while"
                     " the deployed route does .catch(() => ({})) and calls the"
                     " tool with empty arguments"))))
  (let [n (count rows)]
    ;; evidence floor: orgs-detectors.edn records a run with SCANNED 0 as
    ;; inconclusive rather than clean.
    (println (str "SCANNED\t" n "\tappview-facade"))
    (if findings-mode?
      (doseq [[sev k detail] @findings]
        (println (str "FINDING\t" sev "\t" k "\t" detail)))
      (do (doseq [[sev k detail] @findings]
            (println (str sev " " k " -- " detail)))
          (println (str (count @findings) " finding(s) over " n " appview repositories"))))
    (js/process.exit (cond (zero? n) 2
                           (seq @findings) 1
                           :else 0))))

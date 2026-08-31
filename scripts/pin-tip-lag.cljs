#!/usr/bin/env nbb
;; scripts/pin-tip-lag.cljs — is each WEST PIN at its upstream default-branch TIP?
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/pin-tip-lag.cljs \
;;     [--root <superproject checkout>] [--limit N]
;;
;; ## The leg nothing measured
;;
;; CLAUDE.md and ADR-2608136800 say the checkout, the west pin and the upstream
;; repository's main are THREE different things. Two of the three pairs already
;; have a tool:
;;
;;   checkout <-> remote default branch   scripts/checkout-staleness.cljs
;;   checkout <-> west pin                scripts/pin-checkout-drift.cljs
;;   west pin <-> remote default branch   (this file)
;;
;; The third pair is the one CLAUDE.md made mandatory on 2026-08-20 -- "pin の
;; 既定状態は upstream default branch の tip" -- and the only documented way to
;; answer it was `gh api repos/<org>/<repo>/compare/<pin>...<default>` for ONE
;; repository. At fleet scale that is one REST call per project, which is both
;; slower than the hourly budget and slower than anyone is willing to wait, so
;; in practice the question went unasked and pins drifted until something broke.
;;
;; ## Why GraphQL
;;
;; `defaultBranchRef { target { oid } }` is aliasable, so one query answers it
;; for a whole batch of repositories. Measured 2026-08-26: the whole fleet in 35
;; queries instead of ~4,200, about eleven minutes end to end.
;;
;; ## What this DOES NOT answer, deliberately
;;
;; It reports that a pin is NOT the tip. It does not classify the direction --
;; behind, ahead and diverged all look the same here, because telling them apart
;; needs an ancestry walk this query does not do. That classification already
;; exists downstream and is not duplicated: `scripts/west-pin-put-batch.cljs`
;; checks, per entry and against the server, that the new pin is reachable from
;; the default branch and that old -> new is `ahead` with behind_by 0, and drops
;; the entries that are not. So the output here is a CANDIDATE list, and the
;; thing that decides is the thing that writes:
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/pin-tip-lag.cljs > pins.tsv
;;   PINS=pins.tsv DRY=1 nbb --classpath ".:scripts/nbb_compat" scripts/west-pin-put-batch.cljs
;;
;; stdout is exactly the TSV that script eats: <west-entry-name> <tip-sha> <slug>.
;;
;; ## Why it refuses rather than returning a clean answer
;;
;; A GraphQL batch comes back 502 often enough that one attempt per batch turns
;; "could not measure these 120 repositories" into a silent gap that reads as
;; "these 120 are at their tip" -- the failure CLAUDE.md's six questions are
;; about. So: batches are retried; whatever still cannot be read is counted as
;; UNRESOLVED and printed next to the finding count; and any UNRESOLVED or any
;; failed batch exits 2, which is neither the 0 of a clean fleet nor the 1 of a
;; fleet with lagging pins. A run that surveys nothing at all also exits 2
;; rather than reporting an empty, passing fleet.
;;
;; That retry only ever covered a batch failing WHOLE. GitHub answers a partial
;; failure as 200-with-errors: the repositories it could read are on stdout and
;; the rest are null. That parses, so the batch counted as :ok and its nulls
;; were reported UNRESOLVED without being asked a second time. Measured
;; 2026-08-31: a run reported FAILED_BATCHES 0 with UNRESOLVED 240 of 4252, and
;; re-asking for exactly those 240 once resolved every one -- they were neither
;; archived nor renamed, just a transient partial failure the tool could not
;; retry. So the nulls are now re-asked too (resolve-batch, up to 3 rounds).
;; This does not weaken the refusal: what still will not resolve is still
;; UNRESOLVED and still exits 2. It stops `could not measure` being the routine
;; answer, which is what teaches a reader to skip the survey.
;;
;; `--self-test` proves both directions offline, with no network and no fleet.
;;
;; exit 0: every surveyable pin is at its tip
;; exit 1: at least one pin differs from its tip (rows on stdout)
;; exit 2: the survey could not answer -- do not read this as either of the above
(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))
(def node-os (js/require "node:os"))
(def node-path (js/require "node:path"))

(def argv (vec (drop 2 (js->clj js/process.argv))))

(defn arg [flag default]
  (if-let [i (first (keep-indexed #(when (= %2 flag) %1) argv))]
    (nth argv (inc i) default)
    default))

(def root (arg "--root" "."))
(def west-file (.join node-path root "manifest" "west.yml"))
(def limit (some-> (arg "--limit" nil) js/parseInt))

(defn err-println [& xs]
  (binding [*print-fn* *print-err-fn*] (apply println xs)))

(when-not (.existsSync fs west-file)
  (err-println "no west.yml at" west-file "— pass --root <superproject checkout>")
  (js/process.exit 2))

;; ---------------------------------------------------------------- west.yml
;; Only the `projects:` section. The `remotes:` entries share the `- name:`
;; shape at the same indent, and folding them in would inflate the denominator.

(def projects
  (loop [lines (drop-while #(not= "  projects:" %) (str/split-lines (.readFileSync fs west-file "utf8")))
         cur nil acc []]
    (if-let [l (first lines)]
      (cond
        (str/starts-with? l "    - name: ")
        (recur (rest lines) {:name (str/trim (subs l 12))} (if cur (conj acc cur) acc))

        (and cur (str/starts-with? l "      "))
        (let [[k v] (str/split (str/trim l) #":\s*" 2)]
          (recur (rest lines)
                 (case k
                   "remote"    (assoc cur :remote v)
                   "repo-path" (assoc cur :repo-path v)
                   "revision"  (assoc cur :revision v)
                   "path"      (assoc cur :path v)
                   "groups"    (assoc cur :groups v)
                   cur)
                 acc))

        :else (recur (rest lines) cur acc))
      (if cur (conj acc cur) acc))))

(def surveyable
  (->> projects
       (filter :remote)
       ;; a `revision: main` entry has no pin to be stale; only SHAs are surveyable
       (filter #(re-matches #"[0-9a-f]{40}" (str (:revision %))))
       (remove #(str/includes? (str (:groups %)) "archived"))
       (remove #(str/includes? (str (:groups %)) "datalad"))
       (map #(assoc % :slug (str (:remote %) "/" (or (:repo-path %) (:name %)))))
       vec))

;; `--limit` is a smoke-test knob, not a filter: it is reported separately so a
;; truncated run cannot read as a whole-fleet pass.
(def candidates (if limit (vec (take limit surveyable)) surveyable))

(def skipped (- (count projects) (count surveyable)))
(err-println "west projects:" (count projects)
             " surveyable pins:" (count surveyable)
             " not surveyed (no SHA pin / archived / datalad):" skipped
             (if limit (str " -- LIMITED to " (count candidates) ", NOT a whole-fleet answer") ""))

;; The evidence floor. An empty survey is not a clean fleet.
(when (zero? (count candidates))
  (err-println "Refusing to report a pass: surveyed 0 pins.")
  (js/process.exit 2))

(def batch-size 120)

(defn gql-batch [items idx]
  (let [q (str "query {"
               (str/join " "
                 (map-indexed
                   (fn [i {:keys [remote repo-path name]}]
                     (str "r" i ": repository(owner:\"" remote "\", name:\"" (or repo-path name) "\")"
                          "{ defaultBranchRef { target { oid } } }"))
                   items))
               " }")
        f (.join node-path (.tmpdir node-os) (str "pin-tip-lag-" idx ".graphql"))]
    (.writeFileSync fs f q "utf8")
    (try
      (let [out (.execSync cp (str "gh api graphql -F query=@" f)
                           #js {:encoding "utf8" :maxBuffer (* 32 1024 1024)
                                :stdio #js ["pipe" "pipe" "pipe"]})]
        {:ok (js->clj (js/JSON.parse out) :keywordize-keys true)})
      (catch :default e
        ;; A 200-with-errors still carries the repositories it COULD resolve on
        ;; stdout while `gh` exits non-zero; a transport failure carries none.
        ;; Keeping the partial answer is the difference between losing one
        ;; repository and losing a batch of 120.
        (let [so (some-> (.-stdout e) str)]
          (if (and so (str/starts-with? (str/trim so) "{"))
            {:ok (js->clj (js/JSON.parse so) :keywordize-keys true)}
            {:err (or (some-> (.-stderr e) str) (.-message e))})))
      (finally
        (try (.unlinkSync fs f) (catch :default _ nil))))))

(defn gql-batch-retry [items idx]
  (loop [n 0]
    (let [r (gql-batch items idx)]
      (if (or (:ok r) (>= n 3))
        r
        (do (try (.execSync cp "sleep 5") (catch :default _ nil))
            (err-println "  retry" (inc n) "for batch" idx)
            (recur (inc n)))))))

(defn- fill-tips
  "items -> items with :tip filled from one GraphQL round. Unresolvable ones
  come back without :tip."
  [items idx]
  (let [{:keys [ok err]} (gql-batch-retry items idx)]
    (if err
      {:err err}
      {:items (vec (map-indexed
                     (fn [j it]
                       (assoc it :tip (get-in (:data ok)
                                              [(keyword (str "r" j)) :defaultBranchRef :target :oid])))
                     items))})))

(defn resolve-batch
  "Resolve every item's tip, re-asking for the ones that came back empty.

  gql-batch-retry only retries when the WHOLE batch fails. GitHub's GraphQL
  answers a partial failure as 200-with-errors: the repositories it could read
  are on stdout and the rest are null. That parses, so the batch counts as :ok
  and its nulls were reported as UNRESOLVED without ever being asked again.

  Measured 2026-08-31 on this fleet: a run reported FAILED_BATCHES 0 with
  UNRESOLVED 240 of 4252 -- and re-asking for exactly those 240, once, resolved
  all of them. They were not archived, renamed or missing; they were a transient
  partial failure that the tool was structurally unable to retry. A survey whose
  routine answer is `could not measure` teaches its reader to skip it, which is
  worse than the drift it exists to find."
  ([items idx] (resolve-batch items idx fill-tips true))
  ([items idx fill sleep?]
   (let [first-round (fill items idx)]
     (if (:err first-round)
       first-round
       (loop [acc (:items first-round) round 1]
         (let [missing (vec (remove :tip acc))]
           (if (or (empty? missing) (> round 3))
             {:items acc}
             (do (err-println "  re-asking for" (count missing)
                              "null repositor(ies) in batch" idx "- round" round)
                 (when sleep? (try (.execSync cp "sleep 3") (catch :default _ nil)))
                 (let [again (fill missing (str idx "-r" round))]
                   (if (:err again)
                     {:items acc}          ;; keep what we have; the caller counts the rest
                     (let [fixed (into {} (for [it (:items again) :when (:tip it)]
                                            [(:name it) (:tip it)]))]
                       (recur (mapv #(if (and (nil? (:tip %)) (fixed (:name %)))
                                       (assoc % :tip (fixed (:name %)))
                                       %)
                                    acc)
                              (inc round)))))))))))))

(when (some #{"--self-test"} argv)
  (let [mk (fn [n] {:name n :remote "o" :revision "dead"})
        three [(mk "a") (mk "b") (mk "c")]
        ;; round 1 returns nulls for b and c, later rounds answer everything:
        ;; a transient partial failure, which is what 200-with-errors is
        calls (atom 0)
        flaky (fn [items _idx]
                (swap! calls inc)
                {:items (mapv #(assoc % :tip (if (and (= 1 @calls) (not= "a" (:name %)))
                                               nil "tip")) items)})
        recovered (resolve-batch three "t" flaky false)
        ;; and one that never answers: must stay unresolved, not loop forever
        never (fn [items _idx] {:items (mapv #(assoc % :tip nil) items)})
        stuck (resolve-batch three "t" never false)
        ok? (fn [label pred] (println (if pred "PASS" "FAIL") label) pred)
        r1 (ok? "a transient null is re-asked and recovered"
                (every? :tip (:items recovered)))
        r2 (ok? "and it took more than one round to get there" (> @calls 1))
        r3 (ok? "a permanent null stays unresolved rather than being invented"
                (every? (complement :tip) (:items stuck)))
        r4 (ok? "and the re-ask loop terminates" (= 3 (count (:items stuck))))]
    (js/process.exit (if (and r1 r2 r3 r4) 0 1))))

(def total-batches (js/Math.ceil (/ (count candidates) batch-size)))

(loop [i 0 differing 0 unresolved 0 failed 0]
  (if (>= i (count candidates))
    (do
      (err-println "")
      (err-println "SURVEYED" (- (count candidates) unresolved) "of" (count candidates))
      (err-println "PINS_DIFFERING_FROM_TIP" differing)
      (err-println "UNRESOLVED" unresolved)
      (err-println "FAILED_BATCHES" failed)
      (js/process.exit (cond (or (pos? failed) (pos? unresolved)) 2
                             (pos? differing)                     1
                             :else                                0)))
    (let [items (subvec candidates i (min (count candidates) (+ i batch-size)))
          outcome (resolve-batch items (quot i batch-size))
          err (:err outcome)]
      (err-println "batch" (inc (quot i batch-size)) "of" total-batches
                   (if err (str "FAILED: " (subs (str err) 0 (min 160 (count (str err))))) ""))
      (if err
        (recur (+ i batch-size) differing (+ unresolved (count items)) (inc failed))
        (let [res  (:items outcome)
              hits (filter #(and (:tip %) (not= (:tip %) (:revision %))) res)]
          ;; streamed, not accumulated: an eleven-minute run that is killed at
          ;; minute nine should still have told you what it found by minute nine
          (doseq [{:keys [name tip slug]} hits]
            (println (str name "\t" tip "\t" slug)))
          (recur (+ i batch-size)
                 (+ differing (count hits))
                 (+ unresolved (count (remove :tip res)))
                 failed))))))

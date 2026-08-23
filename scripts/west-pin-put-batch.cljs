#!/usr/bin/env nbb
;; scripts/west-pin-put-batch.cljs — advance MANY west.yml pins in one
;; server-side commit, with the same three checks scripts/west-pin-put.cljs makes
;; per entry.
;;
;; Why: the per-entry path GETs a 1 MB west.yml and PUTs it back for every pin.
;; Measured while landing ADR-2608170400's first tranche, the landing rate fell
;; from 12s to ~38s per repo as concurrent sessions wrote west.yml and the 409
;; retries piled up; ~200 pins that way is two hours of round trips. Amortising
;; the read and the write over N entries makes it one.
;;
;; What is NOT amortised, deliberately, is the verification. Each entry is still
;; checked against the server:
;;
;;   1. the new pin is reachable from the upstream default branch
;;      (refuses an unpushed commit, and a commit on an unmerged branch)
;;   2. tip's old pin -> new pin is `ahead` with behind_by 0
;;      (refuses a silent regression and a diverged pin)
;;   3. one blob-SHA precondition for the whole write, so a concurrent writer
;;      gets a 409 and this retries from a fresh read rather than forcing
;;
;; A single entry failing 1 or 2 drops that entry and keeps the rest; it never
;; downgrades the check. Nothing is written if no entry survives.
;;
;;   PINS=pins.tsv  # lines of: <west-entry-name>\t<40-hex sha>\t<repo slug>
;;   PINS=… [DRY=1] nbb --classpath ".:scripts/nbb_compat" west-pin-put-batch.cljs
(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))
(def pins-file (.-PINS js/process.env))
(def dry? (= "1" (.-DRY js/process.env)))
(def repo "com-junkawasaki/root")
(def path "manifest/west.yml")

(defn sh [cmd]
  (try {:out (str (.execSync cp cmd #js {:encoding "utf8" :maxBuffer 64000000
                                         :stdio #js ["pipe" "pipe" "pipe"]})) :exit 0}
       (catch :default e
         {:out (str (or (.-stdout e) "") (or (.-stderr e) "")) :exit (or (.-status e) 1)})))

(def rows
  (->> (str/split-lines (.readFileSync fs pins-file "utf8"))
       (remove str/blank?)
       (map #(str/split % #"\t"))
       (filter #(and (= 3 (count %)) (re-matches #"[0-9a-f]{40}" (second %))))))

(defn tip []
  (let [blob (str/trim (:out (sh (str "gh api repos/" repo "/contents/manifest --jq '.[] | select(.name==\"west.yml\") | .sha'"))))
        text (:out (sh (str "gh api repos/" repo "/contents/" path
                            " -H 'Accept: application/vnd.github.raw'")))]
    {:blob blob :text text}))

(defn revision-line-index
  "Index of the `revision:` line belonging to the PROJECT `- name: <entry>`.

   Two things this must not do, both measured 2026-08-23:

   1. Stop at the FIRST `- name: <entry>`. A remote and a project can share a
      name -- `kotoba-lang` is both -- and the remote block has no `revision:`
      within the scan window, so the original `first` returned nil and reported
      `no revision line at tip` for an entry that is present and pinnable. That
      verdict reads as a fact about west.yml; it was a fact about the search.
      Hence `some` over every match. (A `path:`-presence guard was tried and
      dropped: with `some` in place it never changed an outcome, and a check
      that cannot be made to fire is not a check.)
   2. Hit a repo whose name prefixes another -- hence the exact `str/trim`
      compare and the bounded window."
  [lines entry]
  (let [starts (keep-indexed (fn [i l] (when (= (str/trim l) (str "- name: " entry)) i)) lines)]
    (some (fn [start]
            (first (keep (fn [j] (when (and (< j (count lines))
                                            (str/starts-with? (str/trim (nth lines j)) "revision:"))
                                   j))
                         (range (inc start) (min (count lines) (+ start 8))))))
          starts)))

(defn default-branch
  "The upstream default branch. NOT assumed to be `main`: measured 2026-08-23,
   three of nineteen entries were dropped as `not reachable from main` when
   their defaults were `Production`, `gh-pages` and a `rescue/...` holding pen.
   The 404 that produced that verdict was the compare endpoint saying the base
   ref does not exist -- not the pin saying it is unreachable. The single-entry
   scripts/west-pin-put.cljs already resolved this; only the batch path did not."
  [slug]
  (let [b (str/trim (:out (sh (str "gh api repos/" slug " --jq .default_branch"))))]
    (if (or (str/blank? b) (str/includes? b "\n")) nil b)))

(defn verify [slug old-pin new-sha]
  (let [db (default-branch slug)
        _ (when-not db (println (str "  ! " slug ": default branch unresolved")))
        reach (if-not db
                "UNRESOLVED"
                (str/trim (:out (sh (str "gh api repos/" slug "/compare/"
                                         db "..." new-sha " --jq .status")))))
        fwd (str/trim (:out (sh (str "gh api repos/" slug "/compare/" old-pin "..." new-sha
                                     " --jq '.status + \" \" + (.behind_by|tostring)'"))))]
    (cond
      (not (contains? #{"identical" "behind"} reach))
      {:ok false :why (str "not reachable from default branch "
                           (pr-str db) " (status " reach ")")}
      (not= fwd "ahead 0")
      {:ok false :why (str "not a clean forward move (" fwd ")")}
      :else {:ok true})))

(loop [attempt 0]
  (let [{:keys [blob text]} (tip)
        lines (vec (str/split-lines text))
        planned (doall
                 (for [[entry sha slug] rows
                       :let [idx (revision-line-index lines entry)
                             cur (when idx (str/replace (str/trim (nth lines idx)) "revision: " ""))]]
                   (cond
                     (nil? idx) {:entry entry :ok false :why "no revision line at tip"}
                     (= cur sha) {:entry entry :ok false :why "already at that pin"}
                     :else (let [v (verify slug cur sha)]
                             (if (:ok v)
                               {:entry entry :ok true :idx idx :sha sha :from cur}
                               (assoc v :entry entry))))))
        good (filter :ok planned)]
    (doseq [p planned]
      (when-not (:ok p) (println (str "  drop " (:entry p) ": " (:why p)))))
    (println (str "  " (count good) " of " (count rows) " entr(ies) verified"))
    (cond
      (empty? good) (println "nothing to write")
      dry? (doseq [g good] (println (str "  DRY " (:entry g) " " (subs (:from g) 0 7)
                                        " -> " (subs (:sha g) 0 7))))
      :else
      (let [updated (reduce (fn [ls {:keys [idx sha]}]
                              (assoc ls idx (str "      revision: " sha)))
                            lines good)
            out (str (str/join "\n" updated) (when (str/ends-with? text "\n") "\n"))
            changed (count (remove true? (map = lines updated)))]
        (if (not= changed (count good))
          (println (str "REFUSING: planned " (count good) " edits but " changed " lines differ"))
          (let [payload {:message (str "west: advance " (count good)
                                       " pin(s) — portable suite now runs on nbb")
                         :content (.toString (js/Buffer.from out "utf8") "base64")
                         :sha blob :branch "main"}
                pf "/tmp/west-pin-batch-payload.json"]
            (.writeFileSync fs pf (js/JSON.stringify (clj->js payload)) "utf8")
            (let [r (sh (str "gh api -X PUT repos/" repo "/contents/" path
                             " --input " pf " --jq .commit.sha"))]
              (cond
                (re-matches #"[0-9a-f]{40}" (str/trim (:out r)))
                (println (str "committed " (str/trim (:out r)) " (" (count good) " pins)"))
                (< attempt 5)
                (do (println (str "  409/conflict, re-reading tip (attempt " (inc attempt) ")"))
                    (recur (inc attempt)))
                :else (println (str "FAILED after retries: " (str/trim (:out r))))))))))))

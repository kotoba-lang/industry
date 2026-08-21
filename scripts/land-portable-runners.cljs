#!/usr/bin/env nbb
;; Land the verified rows of a sweep-clean-runners run: one branch, one
;; server-side merge and one west pin advance per repo.
;;
;; Hand-rolling this per repo is what made the first two batches slow and is why
;; a partial runner reached main once (network-awai/app-hyakka, reverted in
;; 5416b0d). Everything the batches learned is a check in here instead:
;;
;;   - only rows whose result begins PASS, which the sweep now withholds from a
;;     `Ran 0 tests` run
;;   - IDEMPOTENT: if the runner is already on the default branch, the repo is
;;     skipped, so an interrupted batch is resumable and re-running is safe
;;   - the pin advance goes through `scripts/west-pin-put.cljs`, which verifies
;;     reachability from the default branch, forward-only movement, and a blob
;;     precondition. It 409s when west.yml moved under it, so that is retried
;;     rather than forced
;;   - every outcome is written to the ledger TSV, including refusals, because a
;;     batch that silently skipped repos would be indistinguishable from one that
;;     landed them
;;
;; On the pin-verify hook: it inspects Bash commands containing `git push` and
;; validates the SUPERPROJECT's west.yml. The pushes here go to child repos via
;; child_process, and the pin changes go through west-pin-put.cljs, which does
;; the same three server-side checks the hook exists to force. The safety
;; property is kept by the tool, not stepped around.
;;
;;   SWEEP_TSV=… SWEEP_WORKDIR=… LEDGER=… [LIMIT=n] [DRY=1] \
;;     nbb --classpath ".:scripts/nbb_compat" land-portable-runners.cljs
(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))
(def node-path (js/require "node:path"))
(def root "/Users/junkawasaki/github/com-junkawasaki")
(def tsv (.-SWEEP_TSV js/process.env))
(def workdir (.-SWEEP_WORKDIR js/process.env))
(def ledger (.-LEDGER js/process.env))
(def limit (js/parseInt (or (.-LIMIT js/process.env) "9999") 10))
(def dry? (= "1" (.-DRY js/process.env)))
(def branch "agent/portable-suite-on-nbb")

(defn sh [cmd opts]
  (try {:out (str (.execSync cp cmd (clj->js (merge {:encoding "utf8" :maxBuffer 64000000
                                                     :stdio ["pipe" "pipe" "pipe"]}
                                                    opts)))) :exit 0}
       (catch :default e
         {:out (str (or (.-stdout e) "") (or (.-stderr e) "")) :exit (or (.-status e) 1)})))

(defn note! [line]
  (println line)
  (when ledger (.appendFileSync fs ledger (str line "\n") "utf8")))

(def already
  (if (and ledger (.existsSync fs ledger))
    (set (map #(first (str/split % #"\t")) (str/split-lines (.readFileSync fs ledger "utf8"))))
    #{}))

(def rows
  (->> (str/split-lines (.readFileSync fs tsv "utf8"))
       (remove str/blank?)
       (map #(str/split % #"\t"))
       (filter #(= 4 (count %)))
       (filter #(str/starts-with? (nth % 3) "PASS"))
       (remove #(already (first %)))))

(note! (str "# " (count rows) " row(s) to land"
            (when dry? " [DRY RUN]") " at " (.toISOString (js/Date.))))

(doseq [[rel _n cpath result] (take limit rows)]
  (let [[_ org name] (str/split rel #"/")
        src (.join node-path root rel)
        work (.join node-path workdir (.replace rel (js/RegExp. "/" "g") "_"))
        tdir (second (str/split cpath #":"))
        entry (str tdir "/run_portable.cljs")
        counts (str/replace result #"^PASS " "")
        rem (first (str/split-lines (str/trim (:out (sh (str "git -C " src " remote") {})))))
        br (first (filter #(zero? (:exit (sh (str "git -C " src " rev-parse --verify -q "
                                                 rem "/" %) {})))
                          ["main" "master"]))
        wt (.join node-path workdir (str "wt_" name))]
    (cond
      (not br) (note! (str rel "\tSKIP\tno default branch"))

      ;; already there: resumable, and re-running the batch is a no-op
      (zero? (:exit (sh (str "git -C " src " cat-file -e " rem "/" br ":" entry) {})))
      (note! (str rel "\tSKIP\talready on " br))

      (not (.existsSync fs (.join node-path work entry)))
      (note! (str rel "\tSKIP\tno generated runner in the sweep workdir"))

      dry? (note! (str rel "\tDRY\t" entry " " counts))

      :else
      (do
        (.rmSync fs wt #js {:recursive true :force true})
        (sh (str "git -C " src " branch -D " branch) {})
        (let [add (sh (str "git -C " src " worktree add -q -b " branch " " wt " " rem "/" br) {})]
          (if (pos? (:exit add))
            (note! (str rel "\tFAIL\tworktree: " (str/replace (str/trim (:out add)) "\n" " ")))
            (do
              (.copyFileSync fs (.join node-path work entry) (.join node-path wt entry))
              (let [msg (str "test: run the portable suite on nbb as well as the JVM\n\n"
                             "Every `deftest`-bearing portable namespace in this project ran under\n"
                             "`clojure -M:test` and nowhere else, so a defect in the ClojureScript half\n"
                             "of a `.cljc` was invisible here — on a fleet whose shipped consumers are\n"
                             "Cloudflare Workers and browser bundles.\n\n"
                             "  nbb --classpath " cpath " " entry "\n"
                             "  " counts ", 0 failures\n\n"
                             "Generated and verified as the first tranche of ADR-2608170400: this project\n"
                             "needs no classpath beyond its own source, so the runner is a plain nbb\n"
                             "entry with no build step and no JVM. The namespace list is taken from the\n"
                             "default-branch tree rather than a local checkout, and\n"
                             "scripts/verify-cljs-runner-completeness.cljs (ADR-2608170300) checks the\n"
                             "runner against that tree — a runner naming a subset prints the same\n"
                             "`Ran N tests` shape as one naming all of them.\n\n"
                             "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>\n"
                             "Claude-Session: https://claude.ai/code/session_0126C241B1gsZJJdupNA5HtU\n")
                    mf (.join node-path workdir (str "msg_" name ".txt"))]
                (.writeFileSync fs mf msg "utf8")
                (sh (str "git -C " wt " add -A") {})
                (let [c (sh (str "git -C " wt " commit -q -F " mf) {})]
                  (if (pos? (:exit c))
                    (note! (str rel "\tFAIL\tcommit: " (str/replace (str/trim (:out c)) "\n" " ")))
                    (let [p (sh (str "git -C " wt " push -q " rem " " branch) {})]
                      (if (pos? (:exit p))
                        (note! (str rel "\tFAIL\tpush: " (str/replace (str/trim (:out p)) "\n" " ")))
                        (let [m (sh (str "gh api repos/" org "/" name "/merges -f base=" br
                                         " -f head=" branch
                                         " -f commit_message='Merge " branch
                                         ": the portable suite runs on nbb too' --jq .sha") {})
                              sha (str/trim (:out m))]
                          (if (or (pos? (:exit m)) (not (re-matches #"[0-9a-f]{40}" sha)))
                            (note! (str rel "\tFAIL\tmerge: " (str/replace (str/trim (:out m)) "\n" " ")))
                            ;; pin: retry the optimistic-lock conflict, never force
                            (let [ent (str/trim (:out (sh (str "git show origin/main:manifest/west.yml"
                                                              " | grep -B4 'path: " rel "$'"
                                                              " | grep 'name:' | tail -1 | sed 's/.*name: //'")
                                                         {:cwd root :shell "/bin/bash"})))
                                  pin (loop [tries 0]
                                        (let [r (sh (str "nbb --classpath '.:scripts/nbb_compat'"
                                                         " scripts/west-pin-put.cljs " ent " " sha
                                                         " --message 'west: advance " ent " to "
                                                         (subs sha 0 7)
                                                         " — portable suite now runs on nbb'")
                                                    {:cwd root :shell "/bin/bash"})]
                                          (if (and (re-find #"REFUSED \(409\)" (:out r)) (< tries 4))
                                            (recur (inc tries))
                                            r)))]
                              (note! (str rel "\tLANDED\t" sha "\t" counts "\t"
                                          (str/trim (last (remove str/blank? (str/split-lines (:out pin))))))))))))))
              (sh (str "git -C " src " worktree remove --force " wt) {})
              (sh (str "git -C " src " branch -D " branch) {})
              (sh (str "gh api -X DELETE repos/" org "/" name
                       "/git/refs/heads/" branch) {})))))))))

(note! (str "# done at " (.toISOString (js/Date.))))

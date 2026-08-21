#!/usr/bin/env nbb
;; Convert `:sci-test` bb.edn repos to an nbb test script and land it: one
;; worktree, one server-side merge per repo, and ONE batched pin commit at the
;; end.
;;
;; Two things this does that driving `bb_to_nbb_scaffold.cljs emit-batch`
;; directly does not:
;;
;;   1. It runs `emit` against a WORKTREE, never the shared checkout. `emit`
;;      deletes bb.edn and writes nbb.edn in place, and CLAUDE.md forbids editing
;;      a west-managed checkout directly — a concurrent session's branch switch
;;      would silently revert it.
;;   2. It RE-VERIFIES in the worktree. The yield measurement ran the emitted
;;      command against the local checkout, which can be behind the default
;;      branch; that exact staleness produced a 2-of-9 runner earlier in this
;;      tranche (network-awai/app-hyakka, reverted in 5416b0d). A repo whose
;;      emitted command does not pass on the DEFAULT-BRANCH tree is not landed.
;;
;; Pins are collected into a TSV rather than advanced per repo. Per-entry pin
;; advance GETs and PUTs a 1 MB west.yml every time; measured on the runner
;; tranche the rate fell from 12s to ~38s per repo as 409 retries piled up.
;; west-pin-put-batch.cljs makes it one round trip, with the same three
;; server-side checks per entry.
;;
;;   YIELD_TSV=… WORKDIR=… LEDGER=… PINS_OUT=… [LIMIT=n] [DRY=1] \
;;     nbb --classpath ".:scripts/nbb_compat" land-bb-to-nbb.cljs
(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))
(def node-path (js/require "node:path"))
(def root "/Users/junkawasaki/github/com-junkawasaki")
(def yield-tsv (.-YIELD_TSV js/process.env))
(def workdir (.-WORKDIR js/process.env))
(def ledger (.-LEDGER js/process.env))
(def pins-out (.-PINS_OUT js/process.env))
(def limit (js/parseInt (or (.-LIMIT js/process.env) "9999") 10))
(def dry? (= "1" (.-DRY js/process.env)))
(def branch "agent/bb-edn-to-nbb")

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
  (->> (str/split-lines (.readFileSync fs yield-tsv "utf8"))
       (remove #(or (str/blank? %) (str/starts-with? % "#") (str/starts-with? % "repo\t")))
       (map #(str/split % #"\t"))
       (filter #(and (= 2 (count %)) (str/starts-with? (second %) "PASS")))
       (remove #(already (first %)))))

(.mkdirSync fs workdir #js {:recursive true})
(note! (str "# " (count rows) " row(s)" (when dry? " [DRY]") " at " (.toISOString (js/Date.))))

(doseq [[rel result] (take limit rows)]
  (let [[_ org name] (str/split rel #"/")
        src (.join node-path root rel)
        wt (.join node-path workdir (str "wt_" name))
        rem (first (str/split-lines (str/trim (:out (sh (str "git -C " src " remote") {})))))
        _ (sh (str "git -C " src " fetch " rem " -q") {})
        br (first (filter #(zero? (:exit (sh (str "git -C " src " rev-parse --verify -q "
                                                 rem "/" %) {})))
                          ["main" "master"]))
        arch (str/trim (:out (sh (str "gh api repos/" org "/" name " --jq .archived") {})))]
    (cond
      (not br) (note! (str rel "\tSKIP\tno default branch"))
      (= "true" arch) (note! (str rel "\tSKIP\tarchived on GitHub (read-only)"))
      ;; already converted upstream
      (pos? (:exit (sh (str "git -C " src " cat-file -e " rem "/" br ":bb.edn") {})))
      (note! (str rel "\tSKIP\tno bb.edn on " br))
      dry? (note! (str rel "\tDRY\t" result))
      :else
      (do
        (.rmSync fs wt #js {:recursive true :force true})
        (sh (str "git -C " src " branch -D " branch) {})
        (let [add (sh (str "git -C " src " worktree add -q -b " branch " " wt " " rem "/" br) {})]
          (if (pos? (:exit add))
            (note! (str rel "\tFAIL\tworktree: " (str/replace (str/trim (:out add)) "\n" " ")))
            (let [em (sh (str "nbb --classpath '.:scripts/nbb_compat'"
                              " scripts/bb_to_nbb_scaffold.cljs emit --repo " wt)
                         {:cwd root :shell "/bin/bash"})]
              (if (or (pos? (:exit em)) (not (.existsSync fs (.join node-path wt "nbb.edn"))))
                (note! (str rel "\tFAIL\temit: " (str/replace (str/trim (:out em)) "\n" " ")))
                ;; re-verify ON THE DEFAULT-BRANCH TREE, not on the checkout
                (let [pkg (try (js/JSON.parse (.readFileSync fs (.join node-path wt "package.json") "utf8"))
                               (catch :default _ nil))
                      cmd (some-> pkg .-scripts .-test)]
                  (if-not cmd
                    (note! (str rel "\tFAIL\tno test script emitted"))
                    (let [{:keys [out exit]} (sh (str "timeout 180 " cmd) {:cwd wt :shell "/bin/bash"})
                          ran (second (re-find #"(Ran \d+ tests containing \d+ assertions)" out))]
                      (cond
                        (re-find #"Ran 0 tests" (or ran "")) (note! (str rel "\tSKIP\tzero tests on " br))
                        (not (and ran (zero? exit)))
                        (note! (str rel "\tSKIP\tdoes not pass on " br ": "
                                    (str/join " | " (take-last 2 (remove str/blank? (str/split-lines out))))))
                        :else
                        (let [mf (.join node-path workdir (str "msg_" name ".txt"))]
                          (.writeFileSync
                           fs mf
                           (str "build: retire bb.edn for an nbb test script\n\n"
                                "ADR-2607173000 retired babashka as this workspace's script host; nbb is\n"
                                "the script host. This repo still shipped a bb.edn, so its test task named\n"
                                "a runtime that is no longer the one here.\n\n"
                                "The bb.edn was a single `clojure.test/run-tests` scaffold, which converts\n"
                                "mechanically: `nbb.edn` carries the classpath and `package.json` carries\n"
                                "the `test` script. Generated by scripts/bb_to_nbb_scaffold.cljs.\n\n"
                                "  " ran ", 0 failures\n\n"
                                "Verified on the default-branch tree, not on a local checkout: the yield\n"
                                "survey for this tranche ran against checkouts, and a stale one had\n"
                                "already produced a wrong artifact once (a 2-of-9 test runner, reverted in\n"
                                "5416b0d). A repo whose emitted command does not pass here is not landed.\n\n"
                                "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>\n"
                                "Claude-Session: https://claude.ai/code/session_0126C241B1gsZJJdupNA5HtU\n")
                           "utf8")
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
                                                   ": retire bb.edn for an nbb test script' --jq .sha") {})
                                        sha (str/trim (:out m))]
                                    (if-not (re-matches #"[0-9a-f]{40}" sha)
                                      (note! (str rel "\tFAIL\tmerge: " (str/replace sha "\n" " ")))
                                      (let [ent (str/trim (:out (sh (str "git show origin/main:manifest/west.yml"
                                                                        " | grep -B4 'path: " rel "$'"
                                                                        " | grep 'name:' | tail -1 | sed 's/.*name: //'")
                                                                   {:cwd root :shell "/bin/bash"})))]
                                        (when (and pins-out (seq ent))
                                          (.appendFileSync fs pins-out
                                                           (str ent "\t" sha "\t" org "/" name "\n") "utf8"))
                                        (note! (str rel "\tLANDED\t" sha "\t" ran "\tpin queued as " ent)))))))))))))))
              (sh (str "git -C " src " worktree remove --force " wt) {})
              (sh (str "git -C " src " branch -D " branch) {})
              (sh (str "gh api -X DELETE repos/" org "/" name "/git/refs/heads/" branch) {}))))))))

(note! (str "# done at " (.toISOString (js/Date.))))

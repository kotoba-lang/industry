#!/usr/bin/env nbb
;; What fraction of the `:sci-test` bb.edn population actually converts?
;;
;; `scripts/bb_to_nbb_scaffold.cljs` has classified 181 repos as :sci-test —
;; mechanically convertible to an nbb `test` script — since ADR-2607173000. That
;; count is a claim about the SHAPE of the bb.edn, not about whether the emitted
;; command runs. Measured on the first two by hand:
;;
;;   com-etzhayyim-app-organism-viz   Ran 9 tests containing 37 assertions, 0 failures
;;   com-etzhayyim-ooyake             Unable to resolve symbol: System/getProperty
;;
;; So the same gap as ADR-2608170400's first tranche: "the shape is right" is not
;; "it runs". This measures the yield before anything is converted, so the tranche
;; is costed rather than projected.
;;
;; Read-only: it runs the emitted command against the existing checkout and
;; writes nothing. Nothing here touches bb.edn.
;;
;;   nbb --classpath ".:scripts/nbb_compat" measure-bb-to-nbb-yield.cljs
(require '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))
(def root "/Users/junkawasaki/github/com-junkawasaki")

(defn sh [cmd opts]
  (try {:out (str (.execSync cp cmd (clj->js (merge {:encoding "utf8" :maxBuffer 64000000
                                                     :stdio ["pipe" "pipe" "pipe"]}
                                                    opts)))) :exit 0}
       (catch :default e
         {:out (str (or (.-stdout e) "") (or (.-stderr e) "")) :exit (or (.-status e) 1)})))

;; One classify pass, then one dry-run emit per repo to get the command the
;; converter would install.
(def classified
  (:out (sh (str "nbb --classpath '.:scripts/nbb_compat' scripts/bb_to_nbb_scaffold.cljs"
                 " emit-batch --class sci-test --dry-run")
            {:cwd root :shell "/bin/bash"})))

;; The dry-run prints, per repo: `emit :sci-test <dir>` then a package.json block
;; holding the `test` script.
(def blocks
  (->> (str/split classified #"(?=emit :sci-test )")
       (remove str/blank?)
       (keep (fn [b]
               (let [dir (second (re-find #"emit :sci-test (\S+)" b))
                     cmd (second (re-find #"\"test\": \"(.*?)\"\n" b))]
                 (when (and dir cmd) {:dir dir :cmd cmd}))))))

(println "repo\tresult")
(println (str "# " (count blocks) " :sci-test repo(s) with an emitted test command"))

(doseq [{:keys [dir cmd]} blocks]
  (let [;; the printed JSON escapes the inner quotes; undo that to get a shell command
        shell-cmd (-> cmd (str/replace "\\\"" "\"") (str/replace "\\\\" "\\"))
        abs (str root "/" dir)]
    (if-not (.existsSync fs abs)
      (println (str dir "\tNO-CHECKOUT"))
      (let [{:keys [out exit]} (sh (str "timeout 180 " shell-cmd) {:cwd abs :shell "/bin/bash"})
            ran (second (re-find #"(Ran \d+ tests containing \d+ assertions)" out))
            bad (second (re-find #"(\d+ failures, \d+ errors)" out))
            err (or (second (re-find #"(Unable to resolve symbol: \S+)" out))
                    (second (re-find #"(Could not find namespace: \S+)" out))
                    (second (re-find #"Cannot find module '([^']+)'" out)))]
        (println (str dir "\t"
                      (cond
                        (re-find #"Ran 0 tests" (or ran "")) (str "ZERO-TESTS " ran)
                        (and ran err) (str "PARTIAL-LOAD " ran " then " err)
                        (and ran (zero? exit)) (str "PASS " ran)
                        ran (str "RED " ran " / " bad)
                        :else (str "LOAD-FAIL " (or err (str/join " | " (take-last 2 (remove str/blank? (str/split-lines out)))))))))))))

#!/usr/bin/env nbb
(require '[scripts.nbb-compat :refer [sh slurp spit getenv exit]])

(let [[old new adr] *command-line-args*
      fs (js/require "node:fs") path (js/require "node:path")
      fail (fn [message] (binding [*out* *err*] (println message)) (exit 1))]
  (when-not (and old new adr) (fail "usage: migrate-etzhayyim-compat.cljs <old-compat-dir> <new-kotoba-lang-repo> <adr-id>"))
  (let [root (clojure.string/trim (:out (sh "git" "rev-parse" "--show-toplevel")))
        source (str root "/orgs/etzhayyim/root/20-actors/" old)
        explicit-workdir (getenv "MIGRATE_WORKDIR")
        ;; A fixed os.tmpdir()+new path collides across concurrent invocations
        ;; targeting the same target repo name (this repo's normal multi-agent
        ;; workflow) — one run's rmSync/cpSync can clobber another's in-progress
        ;; checkout. mkdtempSync gives each unset-MIGRATE_WORKDIR run a private
        ;; dir; an explicit MIGRATE_WORKDIR is left as a reusable debug path.
        workdir (if explicit-workdir
                  (do (.rmSync fs explicit-workdir #js {:recursive true :force true})
                      explicit-workdir)
                  (.mkdtempSync fs (str (.join path (.tmpdir (js/require "node:os")) new) "-")))]
    (when-not (.existsSync fs source) (fail (str "no such source dir: " source)))
    (.cpSync fs source workdir #js {:recursive true})
    (let [test-dir (if (.existsSync fs (str workdir "/tests")) "tests" "test")
          deps (str "{:paths [\"src\" \"" test-dir "\"]\n :deps {org.clojure/clojure {:mvn/version \"1.11.1\"}}}\n")]
      (spit (str workdir "/deps.edn") deps)
      (.appendFileSync fs (str workdir "/README.md")
                       (str "\n## Provenance\n\nRelocated " (.slice (.toISOString (js/Date.)) 0 10)
                            " from `etzhayyim/root/20-actors/" old "` to `kotoba-lang/" new
                            "` per ADR-" adr ".\n"))
      ;; Best-effort test run before push: find the test namespace via its own
      ;; (ns ...) form (not inferred from the file path — vendor names can
      ;; contain literal underscores) and run it via bb before ever pushing —
      ;; a real test failure must not become a new public repo.
      (let [find-r (sh "find" test-dir "-name" "*_test.cljc" "-o" "-name" "*-test.cljc" {:cwd workdir})
            ns-file (first (remove clojure.string/blank? (clojure.string/split-lines (:out find-r))))]
        (if-not ns-file
          (binding [*out* *err*] (println (str "WARNING: no *_test.cljc found for " old "; skipping test run")))
          (let [content (slurp (str workdir "/" ns-file))
                ns-name (second (re-find #"\(ns\s+([A-Za-z0-9_.-]+)" content))
                bb? (zero? (:exit (sh "which" "bb")))]
            (if (and ns-name bb?)
              (let [r (sh "bb" "--classpath" (str "src:" test-dir) "-e"
                          (str "(require 'clojure.test) (require (symbol \"" ns-name "\")) "
                               "(let [r (clojure.test/run-tests (symbol \"" ns-name "\"))] "
                               "(System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))")
                          {:cwd workdir})]
                (when-not (zero? (:exit r))
                  (fail (str "TEST FAILED for " new " (" ns-name ") — not pushing\n" (:err r) (:out r)))))
              (binding [*out* *err*] (println (str "WARNING: could not determine test namespace for " old "; skipping test run")))))))
      (doseq [args [["git" "init" "-q" "-b" "main"]
                    ["git" "add" "-A"]
                    ["git" "commit" "-q" "-m" (str "Relocate " old " to kotoba-lang/" new " (clean-room API-compat cljc actor)")]]
              :let [r (apply sh (concat args [{:cwd workdir}]))]]
        (when-not (zero? (:exit r)) (fail (:err r))))
      (let [r (sh "gh" "repo" "create" (str "kotoba-lang/" new) "--public" "--source=." "--remote=origin" "--push" {:cwd workdir})]
        (when-not (zero? (:exit r)) (fail (:err r))))
      (println (str "created: https://github.com/kotoba-lang/" new))
      (println (str "worktree left at: " workdir)))))

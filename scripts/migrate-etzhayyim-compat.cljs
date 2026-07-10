#!/usr/bin/env nbb
(require '[scripts.nbb-compat :refer [sh slurp spit getenv exit]])

(let [[old new adr] *command-line-args*
      fs (js/require "node:fs") path (js/require "node:path")
      fail (fn [message] (binding [*out* *err*] (println message)) (exit 1))]
  (when-not (and old new adr) (fail "usage: migrate-etzhayyim-compat.cljs <old-compat-dir> <new-kotoba-lang-repo> <adr-id>"))
  (let [root (clojure.string/trim (:out (sh "git" "rev-parse" "--show-toplevel")))
        source (str root "/orgs/etzhayyim/root/20-actors/" old)
        workdir (str (or (getenv "MIGRATE_WORKDIR") (.tmpdir (js/require "node:os"))) "/" new)]
    (when-not (.existsSync fs source) (fail (str "no such source dir: " source)))
    (.rmSync fs workdir #js {:recursive true :force true})
    (.cpSync fs source workdir #js {:recursive true})
    (let [test-dir (if (.existsSync fs (str workdir "/tests")) "tests" "test")
          deps (str "{:paths [\"src\" \"" test-dir "\"]\n :deps {org.clojure/clojure {:mvn/version \"1.11.1\"}}}\n")]
      (spit (str workdir "/deps.edn") deps)
      (.appendFileSync fs (str workdir "/README.md")
                       (str "\n## Provenance\n\nRelocated " (.slice (.toISOString (js/Date.)) 0 10)
                            " from `etzhayyim/root/20-actors/" old "` to `kotoba-lang/" new
                            "` per ADR-" adr ".\n"))
      (doseq [args [["git" "init" "-q" "-b" "main"]
                    ["git" "add" "-A"]
                    ["git" "commit" "-q" "-m" (str "Relocate " old " to kotoba-lang/" new " (clean-room API-compat cljc actor)")]]
              :let [r (apply sh (concat args [{:cwd workdir}]))]]
        (when-not (zero? (:exit r)) (fail (:err r))))
      (let [r (sh "gh" "repo" "create" (str "kotoba-lang/" new) "--public" "--source=." "--remote=origin" "--push" {:cwd workdir})]
        (when-not (zero? (:exit r)) (fail (:err r))))
      (println (str "created: https://github.com/kotoba-lang/" new))
      (println (str "worktree left at: " workdir)))))

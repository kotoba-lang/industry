#!/usr/bin/env nbb
;; Fleet snapshot adapter for the root projection gate. The operator has
;; already admitted and content-addressed the Git tip before shipping this
;; tree, so the node verifies current pinned hashes and reproducibility without
;; requiring Git history in the extracted archive.

(def path (js/require "node:path"))
(def child-process (js/require "node:child_process"))
(def argv (vec (js->clj (.-argv js/process))))
(def tree (when-let [p (last argv)] (.resolve path p)))

(if-not tree
  (do (js/console.error "projection-check: extracted tree path is required")
      (set! (.-exitCode js/process) 2))
  (let [env (js/Object.assign #js {} (.-env js/process)
                              #js {:PROJECTION_ROOT tree
                                   :PROJECTION_SNAPSHOT "1"})
        result (.spawnSync child-process
                           (first argv)
                           (clj->js [(second argv)
                                     "--classpath"
                                     (str tree ":" tree "/scripts/nbb_compat")
                                     (str tree "/manifest/projection-verify.cljs")
                                     "verify-all"])
                           #js {:cwd tree :env env :stdio "inherit"})]
    (set! (.-exitCode js/process) (or (.-status result) 1))))

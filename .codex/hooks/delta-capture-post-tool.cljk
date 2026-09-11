#!/usr/bin/env nbb
;; kotoba-delta auto-capture PostToolUse hook (ADR-2607161325 follow-up).
;; FAIL-OPEN BY DESIGN: this hook must never block or annoy a session —
;; every path exits 0. It records ONLY when the session opts in:
;;   DELTA_CAPTURE=1            enable
;;   DELTA_KEY=<privkey.pem>    signing key (agent did:key)
;;   DELTA_LOG=<ops.edn>        log file (default: .claude/delta-ops.edn)
;; Records Edit/Write tool calls as signed kotoba-delta ops with the
;; session id as :op/turn (prompt->line provenance). Secrets are rejected
;; by delta's own admission gate (the op simply isn't logged).
(ns delta-capture-post-tool
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(defn- read-stdin []
  (try (fs/readFileSync 0 "utf8") (catch :default _ nil)))

(try
  (let [env js/process.env
        enabled? (= "1" (.-DELTA_CAPTURE env))
        key (.-DELTA_KEY env)]
    (when (and enabled? key (fs/existsSync key))
      (let [in (js/JSON.parse (read-stdin))
            tool (.-tool_name in)
            ti (.-tool_input in)
            root (or (.-cwd in) (js/process.cwd))
            delta-src (path/join root "orgs/kotoba-lang/kotoba-delta/src")
            delta-cli (path/join root "orgs/kotoba-lang/kotoba-delta/bin/delta.cljs")
            log (or (.-DELTA_LOG env) (path/join root ".claude/delta-ops.edn"))
            file (when ti (.-file_path ti))
            rel (when file (path/relative root file))]
        (when (and (contains? #{"Edit" "Write"} tool) rel
                   (not (.startsWith rel ".."))
                   (fs/existsSync delta-cli))
          (let [args (case tool
                       "Edit" ["record" "--log" log "--key" key
                               "--kind" "edit" "--file" rel
                               "--old" (.-old_string ti) "--new" (.-new_string ti)
                               "--turn" (str "session:" (.-session_id in))]
                       "Write" ["record" "--log" log "--key" key
                                "--kind" "write" "--file" rel
                                "--new" (.-content ti)
                                "--turn" (str "session:" (.-session_id in))])]
            (cp/execFileSync "nbb"
                             (clj->js (concat ["--classpath" delta-src delta-cli] args))
                             #js {:stdio "ignore" :timeout 15000}))))))
  (catch :default _ nil))
(js/process.exit 0)

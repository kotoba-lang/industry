#!/usr/bin/env nbb
;; pre-push-tree-guard.cljs — reject a push whose commit's ROOT TREE is
;; "collapsed": missing any of the superproject's required top-level paths.
;;
;; WHY: a sparse-checkout worktree that materialises only a subset of paths can
;; produce a commit whose tree drops everything outside the sparse cone (the
;; skip-worktree entries fall out of the index on a bad `git add -A`). Pushing
;; that to `main` silently wipes manifest/orgs/70-tools/scripts/CLAUDE.md from
;; the tip until someone notices and restores it. This actually happened
;; repeatedly (2026-07-16, ADR-2607151900): main oscillated between a 25-entry
;; healthy tree and a 1-entry (90-docs only) collapsed tree, produced by an
;; automated fleet's sparse worktrees. Committed history is never lost (the
;; blobs live in ancestor commits), but any fresh clone / west update / CI
;; checkout during the collapsed window gets a broken tree.
;;
;; SCOPE (per owner decision 2026-07-16 — "A だけ, lefthook ローカル"): this is a
;; LOCAL guard. It fires via lefthook's pre-push for pushes from THIS clone's
;; worktrees only. It does NOT cover other clones / other agents' machines —
;; that would need the server-side layer (CI check + branch protection),
;; deliberately out of scope here.
;;
;; git feeds pre-push ref updates on stdin, one per line:
;;   <local-ref> <local-sha> <remote-ref> <remote-sha>
;; For each branch update that is not a delete, ls-tree the local commit and
;; require every path in `required-top-level` to be present. Missing → reject.
;;
;; Pure Node built-ins only (fs + child_process) — lefthook invokes this as a
;; bare `nbb <script>` with no --classpath, so it must not depend on the repo's
;; scripts.nbb-compat shim.
;;
;; Bypass a single push (emergency only, leaves the reason in the reflog):
;;   LEFTHOOK=0 git push …     (or)     git push --no-verify

(require '[clojure.string :as str]
         '["fs" :as fs]
         '["child_process" :as cp])

(def required-top-level
  "Top-level entries that a healthy superproject tree ALWAYS has. A push whose
   tip is missing any of these is a collapse, not a legitimate change — none of
   these directories/files is ever removed in normal work."
  #{"manifest" "orgs" "70-tools" "scripts" "90-docs" "CLAUDE.md" "deps.edn"})

(def zero-sha "0000000000000000000000000000000000000000")

(defn read-stdin []
  (try (.readFileSync fs 0 "utf8") (catch :default _ "")))

(defn top-level-paths
  "Set of top-level path names in `sha`'s tree, or nil if the commit is
   unreadable (shallow graft etc. — fail open there, we only guard what we can
   see locally)."
  [sha]
  (try
    (let [out (.execSync cp (str "git ls-tree --name-only " sha)
                         #js {:encoding "utf8"})]
      (into #{} (remove str/blank?) (str/split-lines out)))
    (catch :default _ nil)))

(let [lines (->> (read-stdin) str/split-lines (remove str/blank?))
      violations
      (for [line lines
            :let [[_local-ref local-sha remote-ref _remote-sha] (str/split line #"\s+")]
            :when (and remote-ref
                       (str/starts-with? remote-ref "refs/heads/")
                       local-sha
                       (not= local-sha zero-sha))     ; skip branch deletes
            :let [present (top-level-paths local-sha)]
            :when present                              ; readable tree only
            :let [missing (sort (remove present required-top-level))]
            :when (seq missing)]
        {:ref remote-ref :sha local-sha :missing missing :entry-count (count present)})]
  (if (seq violations)
    (do
      (binding [*out* *err*]
        (println "\n✗ pre-push BLOCKED — collapsed root tree detected\n")
        (doseq [{:keys [ref sha missing entry-count]} violations]
          (println (str "  " ref " @ " (subs sha 0 12)
                        "  (top-level entries: " entry-count ")"))
          (println (str "    missing required paths: " (str/join ", " missing))))
        (println (str "\n  これは sparse-worktree からの tree 縮退の兆候です"
                      " (ADR-2607151900)。"))
        (println "  full checkout (git sparse-checkout disable) で作業し直すか、")
        (println "  contents-API single-file PUT で push してください。")
        (println "  緊急バイパス: LEFTHOOK=0 git push …  (理由を残すこと)\n"))
      (js/process.exit 1))
    (js/process.exit 0)))

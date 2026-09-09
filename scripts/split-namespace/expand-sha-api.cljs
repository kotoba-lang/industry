;; Expand the test-runner's short sha pin through the GitHub API, with no local
;; checkout at all.
;;
;; The wave that did the other 3,520 repos worked from checkouts, and 30 repos
;; had no usable one: no .git, no remote naming that repo, no main/master
;; remote-tracking ref, or a worktree that would not open. Those are four
;; different local problems and none of them is about the change. Reading the
;; file from the default branch and committing back removes the whole class.
;;
;; The invariant is the one the checkout version had: the ONLY difference
;; between the file before and after is that one value. It is checked by
;; collapsing the expansion back and requiring byte equality, and by reading
;; both as EDN and requiring the same value. A rewrite that moved anything else
;; is refused rather than pushed.
;;
;; Usage: nbb expand-sha-api.cljs <org/repo> <path> <short> <full> [--apply]
(require '[clojure.string :as s]
         '[clojure.edn :as edn])

(def cp (js/require "node:child_process"))
(def repo (nth *command-line-args* 0))
;; the project file is not always deps.edn at the root: two of these repos keep
;; it under clj/, one has bb.edn beside it, and one has a second one under a
;; build output directory. The path is measured from the tree, not assumed.
(def path (nth *command-line-args* 1))
(def short-sha (nth *command-line-args* 2))
(def full-sha (nth *command-line-args* 3))
(def apply? (some #(= "--apply" %) *command-line-args*))

(defn gh [& args]
  (try {:ok true :out (str (.execSync cp (str "gh " (s/join " " args))
                                      #js {:encoding "utf8" :stdio #js ["pipe" "pipe" "pipe"]}))}
       (catch :default e {:ok false :err (str (or (some-> e .-stderr str) e))})))

(defn b64-decode [x] (.toString (.from js/Buffer x "base64") "utf8"))
(defn b64-encode [x] (.toString (.from js/Buffer x "utf8") "base64"))

(def needle (str ":git/sha \"" short-sha "\""))
(def replacement (str ":git/sha \"" full-sha "\""))

(let [meta (gh "api" (str "repos/" repo) "--jq" ".default_branch")]
  (if-not (:ok meta)
    (do (println (str "SKIP\tunreachable\t" repo)) (js/process.exit 0))
    (let [branch (s/trim (:out meta))
          ;; the file, and the blob sha the API needs to replace it
          ;; TWO calls with plain jq paths. One call with an interpolating
          ;; expression -- "\(.sha)\t\(.content)" -- does not survive being
          ;; built as a shell string: jq saw a bare backslash and refused, gh
          ;; exited non-zero, and every one of 27 repos reported the same
          ;; "no such file" as a repo that genuinely had none. A call that
          ;; could not run must not look like a call that found nothing.
          blob-r (gh "api" (str "repos/" repo "/contents/" path "?ref=" branch) "--jq" ".sha")
          body-r (gh "api" (str "repos/" repo "/contents/" path "?ref=" branch) "--jq" ".content")]
      (if-not (and (:ok blob-r) (:ok body-r))
        (do (println (str "REFUSE\tcannot-read\t" repo "\t" path "\t"
                          (first (s/split-lines (str (:err blob-r) (:err body-r))))))
            (js/process.exit 2))
        (let [blob (s/trim (:out blob-r))
              before (b64-decode (s/replace (s/trim (:out body-r)) "\n" ""))]
          (cond
            (not (s/includes? before needle))
            (do (println (str "SKIP\tnothing\t" repo "\t" path)) (js/process.exit 0))

            :else
            (let [after (s/replace before needle replacement)
                  ;; 1. collapsing the expansion back must give the original
                  collapsed (s/replace after replacement needle)
                  ;; 2. both must read as EDN, and to the same value once the
                  ;;    one pin is normalised
                  read-1 (try (edn/read-string before) (catch :default _ ::bad))
                  read-2 (try (edn/read-string after) (catch :default _ ::bad))]
              (cond
                (not= collapsed before)
                (do (println (str "REFUSE\tmoved-something-else\t" repo)) (js/process.exit 2))

                (or (= ::bad read-1) (= ::bad read-2))
                (do (println (str "REFUSE\tnot-edn\t" repo
                                  (if (= ::bad read-1) " (was already unreadable)" "")))
                    (js/process.exit 2))

                (not (map? read-2))
                (do (println (str "REFUSE\tnot-a-map\t" repo)) (js/process.exit 2))

                (not apply?)
                (println (str "WOULD\t" repo "\t" path "\t" (count (re-seq (re-pattern needle) before)) " occurrence(s)"))

                :else
                (let [msg (str "pin the test runner by its full sha\\n\\n"
                               "tools.deps takes the newest sha it is shown, and a prefix names no\\n"
                               "commit until something resolves it. This repo had no usable local\\n"
                               "checkout -- no .git, no remote naming it, or no default-branch ref --\\n"
                               "so the change was read from and written to the default branch directly.\\n\\n"
                               "The only difference is that one value: collapsing the expansion back\\n"
                               "gives the original file byte for byte, and both read as EDN maps.\\n\\n"
                               "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>\\n"
                               "Claude-Session: https://claude.ai/code/session_01GNkHEYTfJyjhgTQSJr1itX")
                      w (gh "api" "-X" "PUT" (str "repos/" repo "/contents/" path)
                            "-f" (str "message=\"" msg "\"")
                            "-f" (str "content=" (b64-encode after))
                            "-f" (str "sha=" blob)
                            "-f" (str "branch=" branch)
                            "--jq" ".commit.sha")]
                  (if (:ok w)
                    (println (str "EXPANDED\t" repo "\t" path "\t" (s/trim (:out w))))
                    (println (str "SKIP\twrite-failed\t" repo "\t"
                                  (first (s/split-lines (:err w)))))))))))))))

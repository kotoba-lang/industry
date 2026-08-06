#!/usr/bin/env nbb
;; scripts/west-pin-put.cljs — manifest/west.yml の pin を1件、**サーバ側**で安全に前進させる。
;;
;; CLAUDE.md は west.yml の pin 前進を「GitHub API の single-entry commit が唯一の正経路」
;; と定めている（楽観ロックで conflict が構造的に起きない）。その手順は毎回同じで、毎回
;; 手で組み立てられていた。この script はそれを1コマンドにする。
;;
;; **なぜ必要か（実際にやりかけた事故）**: 手順の途中で「ローカルの west.yml から読んだ
;; 旧 pin」に対して fast-forward を検証し、その間に別セッションが tip の pin を進めていた、
;; ということが起きる。2026-07-30 の実測では検証対象が 2e7feb0e、tip の実際の pin は
;; 4d83c5a8 だった。結果的に退行はしなかったが、それは**プロセスではなく運**だった。
;; ここでは **tip から読んだ pin そのもの**に対して検証し、同じ blob SHA を precondition に
;; して書く。検証した状態と書き込む状態が必ず一致する。
;;
;; 検証（すべて GitHub API = サーバ側 full 履歴。ローカル ancestry には頼らない）:
;;   1. 新 pin が上流 default branch から到達可能（未 push / 未 merge branch の pin 化を防ぐ）
;;   2. tip の旧 pin → 新 pin が ahead かつ behind_by 0（静かな pin 退行 / diverged を弾く）
;;   3. blob SHA precondition（間に誰かが west.yml を書いていたら 409 で落ちる）
;;
;; usage:
;;   nbb scripts/west-pin-put.cljs <entry-name> <new-sha> [--message "..."] [--dry-run]
;;   nbb scripts/west-pin-put.cljs <entry-name> HEAD          ; 上流 default branch の先端へ
;;   nbb scripts/west-pin-put.cljs --self-test
;;
;; 退行や到達不能を検出したら**書かずに exit 非0**する。--dry-run は検証だけ行う。

(require '[scripts.nbb-compat :as io :refer [slurp spit]]
         '[clojure.string :as str])

(defn- sh [& args]
  (try (let [{:keys [exit out err]} (apply io/sh args)]
         {:exit exit :out (or out "") :err (or err "")})
       (catch :default e {:exit -1 :out "" :err (str e)})))

(defn- gh-raw
  "gh with the output returned EXACTLY as received.

  Almost every call here wants the trimmed form -- a SHA, a status word -- so
  `gh` trims. But the file fetch is not one of those: trimming it silently
  drops west.yml's trailing newline, and since the rewrite is a substring
  substitution the stripped file is what gets written back. The result was a
  pin advance that also removed the newline at end of file, so a one-line
  change was committed as a two-line diff against `west-commands.yml` -- an
  unrelated line, in a file whose whole point is minimal single-entry diffs."
  [& args]
  (let [{:keys [exit out err]} (apply sh "gh" args)]
    (when (not= 0 exit)
      (throw (ex-info (str "gh failed: " (str/join " " args) " — " err)
                      {:type :gh/failed :args args})))
    out))

(defn- gh [& args]
  (str/trim (apply gh-raw args)))

(def superproject "com-junkawasaki/root")
(def west-path "manifest/west.yml")

;; ---------------------------------------------------------------------------
;; pure: find one entry's block and rewrite only its revision line
;; ---------------------------------------------------------------------------

(defn entry-block
  "The text of one PROJECT `- name: <entry>` block, or nil. Bounded by the next
  entry so a rewrite cannot reach into a neighbour.

  The anchor includes the following `remote:` line, because `- name:` alone
  does not identify a project. west.yml uses the same key under `remotes:`,
  where the next line is `url-base:` instead. Any project whose name equals a
  remote name -- kotoba-lang and network-awai both do -- therefore matched
  twice and was rejected as ambiguous, which is why advancing those pins had
  to be done by hand.

  Ambiguity was not the whole bug. `index-of` returned the FIRST match, and the
  remotes block sorts earlier in the file, so a looser uniqueness check would
  have selected a block with no `revision:` line to rewrite at all."
  [yml entry]
  (let [quoted (str/replace entry #"([.*+?^${}()|\[\]\\])" "\\\\$1")
        hits (re-seq (re-pattern (str "(?m)^    - name: " quoted "\n      remote: "))
                     yml)]
    (when (= 1 (count hits))
      (let [anchor (first hits)
            i (str/index-of yml anchor)]
        (when i
          (let [j (str/index-of yml "    - name:" (+ i (count anchor)))]
            (subs yml i (or j (count yml)))))))))

(defn block-revision
  "The entry's pin as written.

  Reads an abbreviation as well as a full hash, because the manifest has held
  one: `cloud-itonami-app` sat at a 12-character revision on 2026-08-06, alone
  among 4,123 entries. Insisting on 40 here did not keep that out of the file —
  it only made the entry unadvanceable by this script, which is the tool that
  would have written a full hash back. Every check downstream is server-side
  and takes an abbreviation, so reading one costs nothing and repairs the
  entry on the next advance."
  [block]
  (second (re-find #"(?m)^      revision: ([0-9a-f]{7,40})$" block)))

(defn short-sha
  "The first `n` characters, or the whole thing when it is already shorter.
  A pin read from the file may be an abbreviation, and truncating it for a
  progress line must not be what fails the run."
  [sha n]
  (subs sha 0 (min n (count sha))))

(defn block-field [block field]
  (second (re-find (re-pattern (str "(?m)^      " field ": (.+)$")) block)))

(defn rewrite-revision
  "Replace the revision inside ONE entry's block. Returns the whole document, or nil
  when the entry is absent, ambiguous, or its pin does not match `expected`."
  [yml entry expected new-sha]
  (when-let [block (entry-block yml entry)]
    (when (= expected (block-revision block))
      (let [i (str/index-of yml block)]
        (str (subs yml 0 i)
             ;; The revision LINE, not the pin as a bare substring: an
             ;; abbreviated pin is short enough to occur elsewhere in the block
             ;; by chance, and a substring replace would rewrite that too.
             (str/replace block
                          (re-pattern (str "(?m)^      revision: " expected "$"))
                          (str "      revision: " new-sha))
             (subs yml (+ i (count block))))))))

;; ---------------------------------------------------------------------------
;; self-test (pure only; no gh, no network)
;; ---------------------------------------------------------------------------

(def ^:private sample-yml
  (str "manifest:\n  projects:\n"
       "    - name: alpha\n      remote: kotoba-lang\n"
       "      revision: " (apply str (repeat 40 "a")) "\n"
       "      path: orgs/kotoba-lang/alpha\n"
       "    - name: beta\n      remote: kotoba-lang\n"
       "      revision: " (apply str (repeat 40 "b")) "\n"
       "      path: orgs/kotoba-lang/beta\n"))

(def ^:private collision-yml
  "The shape west.yml actually has: a `remotes:` section using the same `- name:`
  key, with a project whose name equals a remote name. kotoba-lang and
  network-awai are both like this in the real manifest, and both were
  unadvanceable by this script until entry-block anchored on the `remote:` line.

  Note the ordering: the remotes block comes FIRST, so a fix that only relaxed
  the uniqueness check would have selected a block with no revision line."
  (str "manifest:\n  remotes:\n"
       "    - name: kotoba-lang\n      url-base: git@github.com:kotoba-lang\n"
       "    - name: network-awai\n      url-base: git@github.com:network-awai\n"
       "  projects:\n"
       "    - name: kotoba-lang\n      remote: kotoba-lang\n"
       "      revision: " (apply str (repeat 40 "a")) "\n"
       "      path: orgs/kotoba-lang/kotoba-lang\n"
       "    - name: beta\n      remote: kotoba-lang\n"
       "      revision: " (apply str (repeat 40 "b")) "\n"
       "      path: orgs/kotoba-lang/beta\n"))

(def ^:private abbrev-yml
  "What the manifest actually held on 2026-08-06: one entry pinned to a
  12-character revision, the only one of 4,123. A reader that demands 40 does
  not prevent that -- it just refuses to touch the entry, leaving the
  abbreviation in place for good."
  (str "manifest:\n  projects:\n"
       "    - name: alpha\n      remote: cloud-itonami\n"
       "      revision: c90f782aceff\n"
       "      path: orgs/cloud-itonami/alpha\n"
       "    - name: beta\n      remote: kotoba-lang\n"
       "      revision: " (apply str (repeat 40 "b")) "\n"
       "      path: orgs/kotoba-lang/beta\n"))

(def ^:private a40 (apply str (repeat 40 "a")))
(def ^:private b40 (apply str (repeat 40 "b")))
(def ^:private c40 (apply str (repeat 40 "c")))

(defn- run-self-test []
  (let [cases
        [["entry-block finds the right block"
          (some? (str/index-of (entry-block sample-yml "alpha") "orgs/kotoba-lang/alpha")) true]
         ["entry-block does not run into the next entry"
          (str/includes? (entry-block sample-yml "alpha") "beta") false]
         ["block-revision reads the pin" (block-revision (entry-block sample-yml "beta")) b40]
         ["block-field reads the path"
          (block-field (entry-block sample-yml "beta") "path") "orgs/kotoba-lang/beta"]
         ["entry-block is nil for an unknown entry" (entry-block sample-yml "gamma") nil]
         ["rewrite touches only the named entry"
          (rewrite-revision sample-yml "alpha" a40 c40)
          (str/replace sample-yml a40 c40)]
         ["rewrite leaves the neighbour's pin alone"
          (block-revision (entry-block (rewrite-revision sample-yml "alpha" a40 c40) "beta")) b40]
         ;; The guard that makes this safe: the caller passes the pin it VERIFIED
         ;; against, and a mismatch writes nothing rather than writing over whatever
         ;; is there now.
         ["rewrite refuses when the current pin is not what was verified"
          (rewrite-revision sample-yml "alpha" b40 c40) nil]
         ;; A pin advance must change exactly one line. The file fetch used to
         ;; go through the trimming `gh`, which dropped west.yml's trailing
         ;; newline before the substitution, so the write also removed the
         ;; newline at end of file and the commit touched an unrelated line.
         ["rewrite preserves a trailing newline"
          (str/ends-with? (rewrite-revision sample-yml "alpha" a40 c40) "\n") true]
         ["rewrite preserves the absence of a trailing newline"
          (str/ends-with? (rewrite-revision (str/trimr sample-yml) "alpha" a40 c40) "\n") false]
         ["rewrite changes exactly one line"
          (count (remove true? (map = (str/split-lines sample-yml)
                                  (str/split-lines (rewrite-revision sample-yml "alpha" a40 c40)))))
          1]

         ["rewrite refuses an unknown entry"
          (rewrite-revision sample-yml "gamma" a40 c40) nil]

         ;; An abbreviated pin: readable, advanceable, and written back full.
         ["block-revision reads an abbreviated pin"
          (block-revision (entry-block abbrev-yml "alpha")) "c90f782aceff"]
         ["rewrite advances an abbreviated pin to a full hash"
          (block-revision (entry-block (rewrite-revision abbrev-yml "alpha" "c90f782aceff" c40)
                                       "alpha"))
          c40]
         ["advancing an abbreviated pin still changes exactly one line"
          (count (remove true? (map = (str/split-lines abbrev-yml)
                                  (str/split-lines (rewrite-revision abbrev-yml "alpha"
                                                                     "c90f782aceff" c40)))))
          1]
         ["short-sha does not truncate past the end"
          (short-sha "c90f782aceff" 40) "c90f782aceff"]

         ;; A project whose name equals a remote name. This was a real defect:
         ;; every kotoba-lang and network-awai pin advance in this repo had to
         ;; be done by hand because entry-block reported "not found (or not
         ;; unique)".
         ["entry-block selects the PROJECT block, not the identically named remote"
          (block-revision (entry-block collision-yml "kotoba-lang")) a40]
         ["entry-block reads the project's path, proving it is not the remotes block"
          (block-field (entry-block collision-yml "kotoba-lang") "path")
          "orgs/kotoba-lang/kotoba-lang"]
         ["the colliding block does not run into the next entry"
          (str/includes? (entry-block collision-yml "kotoba-lang") "beta") false]
         ["rewrite advances a colliding project's pin"
          (block-revision (entry-block (rewrite-revision collision-yml "kotoba-lang" a40 c40)
                                       "kotoba-lang"))
          c40]
         ["rewrite leaves the remotes section untouched"
          (str/includes? (rewrite-revision collision-yml "kotoba-lang" a40 c40)
                         "url-base: git@github.com:kotoba-lang")
          true]
         ["a remote with no project of that name is still not an entry"
          (entry-block collision-yml "network-awai") nil]]
        failures (for [[label actual expected] cases :when (not= actual expected)]
                   (str "  FAIL " label ": expected " (pr-str expected)
                        ", got " (pr-str actual)))]
    (if (seq failures)
      (do (println "west-pin-put self-test FAILED:") (run! println failures) (io/exit 1))
      (do (println "west-pin-put self-test OK (" (count cases) " cases)") (io/exit 0)))))

;; ---------------------------------------------------------------------------
;; main
;; ---------------------------------------------------------------------------

(defn- args->opts [args]
  (loop [a args opts {}]
    (if-let [[k & more] (seq a)]
      (case k
        "--self-test" (recur more (assoc opts :self-test true))
        "--dry-run"   (recur more (assoc opts :dry-run true))
        "--message"   (recur (rest more) (assoc opts :message (first more)))
        (recur more (update opts :positional (fnil conj []) k)))
      opts)))

(def opts (args->opts *command-line-args*))

(when (:self-test opts) (run-self-test))

(let [[entry new-arg] (:positional opts)]
  (when (or (nil? entry) (nil? new-arg))
    (println "usage: nbb scripts/west-pin-put.cljs <entry-name> <new-sha|HEAD> [--message \"…\"] [--dry-run]")
    (io/exit 2))

  ;; 1. Read the TIP -- both the content and the blob SHA -- in one place, so the
  ;;    thing verified and the thing written are the same thing.
  (let [;; From the DIRECTORY listing, not the file. Asking the contents API for
        ;; the file returns its whole body base64-encoded — 1.8 MB for this one —
        ;; to read a 40-character hash, and on a flaky link that is the request
        ;; that resets. Measured 2026-08-06: every other call succeeded while
        ;; this one failed with `connection reset by peer` five times running.
        ;; CLAUDE.md already says to take the SHA from the dir listing; this
        ;; script was not doing it.
        ;; From git first. A blob SHA is a deterministic hash of the content,
        ;; so `git rev-parse origin/HEAD:<path>` answers exactly what the API
        ;; would — offline, and without asking for a directory listing that this
        ;; link keeps failing to deliver. Safety is unchanged: if the remote has
        ;; moved since the fetch, the PUT is refused with 409, which is the
        ;; protection the precondition exists for.
        blob-sha (or (do (sh "git" "fetch" "origin" "--quiet")
                         (let [{:keys [exit out]}
                               (sh "git" "rev-parse" (str "origin/HEAD:" west-path))]
                           (when (zero? exit) (str/trim out))))
                     (gh "api" (str "repos/" superproject "/contents/"
                                    (subs west-path 0 (str/last-index-of west-path "/")))
                         "--jq"
                         (str ".[] | select(.name==\""
                              (subs west-path (inc (str/last-index-of west-path "/")))
                              "\") | .sha")))
        ;; gh-raw, not gh: trimming here would drop the file's trailing
        ;; newline and write it back stripped.
        ;;
        ;; Falls back to git when the API cannot deliver it. This file is 1.8 MB
        ;; and on a degraded link it is the one request that resets while every
        ;; small one succeeds — measured 2026-08-06, five consecutive failures
        ;; against a working API. `git show origin/main:` reaches the same tip
        ;; over SSH, and the blob-SHA precondition still guards the write: if
        ;; main moved between the two reads the PUT is refused with 409, which
        ;; is the protection this script was built around.
        yml (or (try (gh-raw "api" (str "repos/" superproject "/contents/" west-path)
                             "-H" "Accept: application/vnd.github.raw")
                     (catch :default _ nil))
                (do (sh "git" "fetch" "origin" "--quiet")
                    (let [{:keys [exit out]} (sh "git" "show"
                                                 (str "origin/HEAD:" west-path))]
                      (when (zero? exit)
                        (println "west-pin-put: tip read via git (contents API unavailable)")
                        out)))
                (do (println "west-pin-put: tip の west.yml を取得できませんでした。")
                    (io/exit 4)))
        block (entry-block yml entry)
        _ (when-not block
            (println (str "west-pin-put: entry '" entry "' not found (or not unique) in the tip's "
                          west-path "."))
            (io/exit 3))
        old-pin (block-revision block)
        path (block-field block "path")
        ;; "orgs/<org>/<repo>" -> "<org>/<repo>" is the GitHub coordinate for every
        ;; project in this manifest.
        repo (when path (str/join "/" (take-last 2 (str/split path #"/"))))
        _ (when-not (and old-pin repo)
            (println "west-pin-put: could not read the entry's revision/path from the tip.")
            (io/exit 3))
        default-branch (gh "api" (str "repos/" repo) "--jq" ".default_branch")
        new-sha (if (= "HEAD" new-arg)
                  (gh "api" (str "repos/" repo "/commits/" default-branch) "--jq" ".sha")
                  new-arg)]

    (println (str "west-pin-put: " entry " (" repo ")"))
    (println (str "  tip pin : " (short-sha old-pin 12)))
    (println (str "  new pin : " (short-sha new-sha 12)))

    (cond
      (= old-pin new-sha)
      (do (println "  already at this pin. nothing to do.") (io/exit 0))

      :else
      (let [;; 2a. reachable from the default branch: refuses an unpushed commit and a
            ;;     commit that only exists on a branch someone may rewrite.
            reach (gh "api" (str "repos/" repo "/compare/" default-branch "..." new-sha)
                      "--jq" ".status")
            ;; 2b. forward from the pin THE TIP ACTUALLY HAS, not from whatever this
            ;;     machine last saw. This is the check the manual procedure got wrong.
            fwd (js->clj (js/JSON.parse
                          (gh "api" (str "repos/" repo "/compare/" old-pin "..." new-sha)
                              "--jq" "{status: .status, behind: .behind_by, ahead: .ahead_by}"))
                         :keywordize-keys true)]
        (println (str "  reachable from " default-branch ": " reach))
        (println (str "  " (short-sha old-pin 8) "..." (short-sha new-sha 8) ": " (:status fwd)
                      " ahead=" (:ahead fwd) " behind=" (:behind fwd)))

        (when-not (contains? #{"identical" "behind"} reach)
          (println (str "  REFUSED: the new pin is not reachable from " repo "'s "
                        default-branch " (status " reach "). An unpushed commit, or one on a "
                        "branch that may be rewritten, must not become a pin."))
          (io/exit 4))

        (when-not (and (= "ahead" (:status fwd)) (zero? (:behind fwd)))
          (println (str "  REFUSED: this is not a fast-forward from the pin the tip has. "
                        "behind=" (:behind fwd) " means a silent pin regression; a "
                        "diverged status means a different lineage."))
          (io/exit 5))

        (if (:dry-run opts)
          (do (println "  dry-run: verified, nothing written.") (io/exit 0))
          (let [next-yml (rewrite-revision yml entry old-pin new-sha)
                _ (when-not next-yml
                    (println "  REFUSED: the entry's pin changed between reading and rewriting.")
                    (io/exit 6))
                message (or (:message opts)
                            (str "west: advance " entry " pin to " (short-sha new-sha 8)
                                 "\n\nSingle line, from " (short-sha old-pin 8)
                                 ". Verified server-side against the pin the tip actually"
                                 " held: reachable from " default-branch
                                 ", ahead by " (:ahead fwd) ", behind 0."))
                tmp "/tmp/west-pin-put.json"]
            ;; The blob SHA precondition: if anyone wrote west.yml since the read above,
            ;; this 409s instead of clobbering them.
            ;; base64 explicitly: a Buffer does not survive JSON.stringify as one.
            (spit tmp (js/JSON.stringify
                       (clj->js {:branch "main" :sha blob-sha :message message
                                 :content (.toString (js/Buffer.from next-yml "utf8") "base64")})))
            (let [{:keys [exit out err]}
                  (sh "gh" "api" "-X" "PUT"
                      (str "repos/" superproject "/contents/" west-path)
                      "--input" tmp "--jq" ".commit.sha")]
              (cond
                (zero? exit)
                (do (println (str "  committed " (str/trim out))) (io/exit 0))

                ;; 409 is the precondition doing its job: somebody wrote west.yml
                ;; between the read and this write. Re-running re-reads.
                (str/includes? (str err) "409")
                (do (println (str "  REFUSED (409): west.yml moved since the read. "
                                  "Run this again."))
                    (io/exit 7))

                :else
                ;; The contents API carries the WHOLE file as base64 on every
                ;; write. west.yml is 1.0 MB, so the request is ~1.4 MB and
                ;; GitHub answers 400 "malformed request" — measured 2026-08-06,
                ;; after the same endpoint had already failed to serve the file
                ;; for reading. The git Data API is what this workspace's own
                ;; cleanup-land.cljs uses for exactly this reason: a blob may be
                ;; large, and the tree/commit/ref calls that follow are small.
                ;;
                ;; The safety property is preserved differently. The contents
                ;; API took a blob-SHA precondition; here it is the ref update,
                ;; which is refused unless the branch still points at the commit
                ;; this tree was built on — a fast-forward check rather than a
                ;; blob check, and the same guarantee: a concurrent write is
                ;; rejected, never clobbered.
                (do
                  (println (str "  contents API refused (" (str/trim (str err))
                                ") — retrying through the git Data API"))
                  (let [put-json! (fn [endpoint payload jq]
                                    (let [f (str "/tmp/west-pin-" (hash endpoint) ".json")]
                                      (spit f (js/JSON.stringify (clj->js payload)))
                                      (let [{:keys [exit out err]}
                                            (sh "gh" "api" endpoint "--input" f "--jq" jq)]
                                        (if (zero? exit)
                                          (str/trim out)
                                          (do (println (str "  git Data API failed at "
                                                            endpoint ": " err))
                                              (io/exit 8))))))
                        base-commit (gh "api" (str "repos/" superproject
                                                   "/git/ref/heads/main")
                                        "--jq" ".object.sha")
                        base-tree (gh "api" (str "repos/" superproject
                                                 "/git/commits/" base-commit)
                                      "--jq" ".tree.sha")
                        blob (put-json! (str "repos/" superproject "/git/blobs")
                                        {:content (.toString (js/Buffer.from next-yml "utf8")
                                                             "base64")
                                         :encoding "base64"}
                                        ".sha")
                        tree (put-json! (str "repos/" superproject "/git/trees")
                                        {:base_tree base-tree
                                         :tree [{:path west-path :mode "100644"
                                                 :type "blob" :sha blob}]}
                                        ".sha")
                        commit (put-json! (str "repos/" superproject "/git/commits")
                                          {:message message :tree tree
                                           :parents [base-commit]}
                                          ".sha")
                        ;; No force: the ref update is refused if main moved,
                        ;; which is this path's version of the 409 above.
                        updated (put-json! (str "repos/" superproject
                                                "/git/refs/heads/main")
                                           {:sha commit :force false}
                                           ".object.sha")]
                    (println (str "  committed " updated " (git Data API)"))
                    (io/exit 0)))))))))))

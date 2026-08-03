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

(defn- gh [& args]
  (let [{:keys [exit out err]} (apply sh "gh" args)]
    (when (not= 0 exit)
      (throw (ex-info (str "gh failed: " (str/join " " args) " — " err)
                      {:type :gh/failed :args args})))
    (str/trim out)))

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

(defn block-revision [block]
  (second (re-find #"(?m)^      revision: ([0-9a-f]{40})$" block)))

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
             (str/replace block expected new-sha)
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
         ["rewrite refuses an unknown entry"
          (rewrite-revision sample-yml "gamma" a40 c40) nil]

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
  (let [blob-sha (gh "api" (str "repos/" superproject "/contents/" west-path) "--jq" ".sha")
        yml (gh "api" (str "repos/" superproject "/contents/" west-path)
                "-H" "Accept: application/vnd.github.raw")
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
    (println (str "  tip pin : " (subs old-pin 0 12)))
    (println (str "  new pin : " (subs new-sha 0 12)))

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
        (println (str "  " (subs old-pin 0 8) "..." (subs new-sha 0 8) ": " (:status fwd)
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
                            (str "west: advance " entry " pin to " (subs new-sha 0 8)
                                 "\n\nSingle line, from " (subs old-pin 0 8)
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
              (if (zero? exit)
                (do (println (str "  committed " (str/trim out))) (io/exit 0))
                (do (println (str "  PUT failed (409 means someone else wrote west.yml "
                                  "since the read — just run this again): " err))
                    (io/exit 7))))))))))

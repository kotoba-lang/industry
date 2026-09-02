#!/usr/bin/env nbb
;; resource-guard-test.cljs — proof for scripts/resource-guard.mjs.
;;
;;   nbb scripts/resource-guard-test.cljs
;;   nbb scripts/resource-guard-test.cljs --guard <path-to-a-resource-guard.mjs>
;;
;; The second form points the suite at another copy of the guard; it is how the
;; red/green pair was measured against the pre-fix file rather than asserted.
;;
;; Every case spawns the real script as a real process. The thing under test is
;; an exit code, and an exit code is not observable from the inside: a unit test
;; that called `run` in-process would see `process.exitCode` set and would keep
;; passing if the script later exited before flushing it.
;;
;; TMPDIR is redirected into a scratch directory for the whole suite, because
;; `os.tmpdir()` is where the guard keeps the lock the entire machine shares.
;; A test that used the real one would block every other agent's build for as
;; long as it held the lock, and would go red whenever a real build was running.
;;
;; What is asserted, and why each case exists:
;;   runs-when-free          the happy path, so the refusal cases below cannot
;;                           pass merely because nothing ever runs.
;;   refuses-when-held       exit 2, command NOT run. The documented contract.
;;   refusal-message         the `REFUSED` line, plus the legacy "already
;;                           running" wording that ship.sh and maturity-loop
;;                           grep for while waiting — dropping it would silently
;;                           turn their wait into a give-up.
;;   propagates-status       a failing command's own status, verbatim.
;;   propagates-signal       128+n, not 1. A signalled child used to be
;;                           indistinguishable from an ordinary failure.
;;   spawn-failure           127, so "could not run" is not reportable as any
;;                           status the command itself could have returned.
;;   reclaims-dead-owner     the documented stale-lock path still reclaims.
;;   refuses-half-created    a lock directory without owner.json is a peer
;;                           mid-acquisition, not a stale lock. Treating it as
;;                           stale let a second guard delete a live owner's lock
;;                           and run concurrently.
;;   reclaims-aged-unreadable  ...but a genuinely abandoned one is still
;;                           reclaimed, so the case above cannot deadlock.
;;   scopes-are-independent  a held build lock does not refuse a deploy.

(require '[clojure.string :as str]
         '["child_process" :as cp]
         '["fs" :as fs]
         '["os" :as os]
         '["path" :as path])

(def args (vec *command-line-args*))
(defn- opt [flag] (second (drop-while #(not= % flag) args)))

(def guard (or (opt "--guard")
               (path/join (js/process.cwd) "scripts" "resource-guard.mjs")))

(when-not (fs/existsSync guard)
  (println (str "REFUSED to report a pass — guard not found at " guard))
  (js/process.exit 2))

(def tmpdir (fs/mkdtempSync (path/join (os/tmpdir) "resource-guard-test-")))
(def lock-root (path/join tmpdir "com-junkawasaki-resource-guard-v1"))

(def child-env
  (doto (js/Object.assign #js {} js/process.env)
    (aset "TMPDIR" tmpdir)))

(defn- guard-run
  "Run the guard synchronously. Returns {:code :out :err}."
  [argv]
  (let [r (cp/spawnSync "node" (clj->js (into [guard] argv))
                        #js {:encoding "utf8" :shell false :env child-env})]
    {:code (if (some? (.-status r)) (.-status r) :killed)
     :out (or (.-stdout r) "")
     :err (or (.-stderr r) "")}))

(defn- reset-locks! []
  (fs/rmSync lock-root #js {:recursive true :force true}))

(defn- sleep! [seconds]
  (cp/spawnSync "sleep" #js [(str seconds)] #js {:shell false}))

(defn- start-holder!
  "Take the build lock in a separate live process. Returns the handle, or nil if
  the lock never appeared (a nil holder must fail the case, never skip it)."
  []
  (let [child (cp/spawn "node" (clj->js [guard "run" "build" "--" "sleep" "60"])
                        #js {:stdio "ignore" :shell false :env child-env})
        owner (path/join lock-root "build" "owner.json")]
    (loop [tries 0]
      (cond
        (fs/existsSync owner) child
        (< tries 200) (do (sleep! 0.05) (recur (inc tries)))
        :else (do (.kill child) nil)))))

(def failures (atom 0))
(def passes (atom 0))

(defn- check [ok? label detail]
  (if ok?
    (do (swap! passes inc) (println (str "OK   " label)))
    (do (swap! failures inc) (println (str "FAIL " label " — " detail)))))

;; ─────────────────────────────────────────────────────────── the command runs

(reset-locks!)
(let [{:keys [code out]} (guard-run ["run" "build" "--" "echo" "marker-free"])]
  (check (and (= 0 code) (str/includes? out "marker-free"))
         "runs-when-free: exit 0 and the command ran"
         (str "code=" code " out=" (pr-str out))))

;; ──────────────────────────────────────────────── refused while a peer holds it

(reset-locks!)
(if-let [holder (start-holder!)]
  (do
    (let [{:keys [code out err]} (guard-run ["run" "build" "--" "echo" "marker-intruder"])]
      (check (= 2 code)
             "refuses-when-held: exit 2"
             (str "code=" code " (a refusal that returns 0 is the forbidden shape)"))
      (check (not (str/includes? out "marker-intruder"))
             "refuses-when-held: the command did not run"
             (str "out=" (pr-str out)))
      (check (re-find #"REFUSED build-lock held by pid=\d+ cwd=\S+ since=\S+" err)
             "refusal-message: REFUSED line names pid/cwd/since"
             (str "err=" (pr-str err)))
      (check (str/includes? err "already running")
             "refusal-message: legacy wording kept for ship.sh / maturity-loop"
             (str "err=" (pr-str err))))
    (let [{:keys [code out]} (guard-run ["run" "deploy" "--" "echo" "marker-deploy"])]
      (check (and (= 0 code) (str/includes? out "marker-deploy"))
             "scopes-are-independent: a held build lock does not refuse deploy"
             (str "code=" code " out=" (pr-str out))))
    (.kill holder))
  (check false "refuses-when-held: holder process never took the lock"
         "the lock never appeared; the refusal cases could not be measured"))

;; ─────────────────────────────────────────────────── the child's own exit status

(reset-locks!)
(let [{:keys [code]} (guard-run ["run" "build" "--" "sh" "-c" "exit 3"])]
  (check (= 3 code) "propagates-status: a child exiting 3 exits 3" (str "code=" code)))

(reset-locks!)
(let [{:keys [code]} (guard-run ["run" "build" "--" "sh" "-c" "kill -TERM $$"])]
  (check (= 143 code)
         "propagates-signal: SIGTERM is 128+15, not 1"
         (str "code=" code " (1 would be indistinguishable from an ordinary failure)")))

(reset-locks!)
(let [{:keys [code]} (guard-run ["run" "build" "--" (path/join tmpdir "no-such-command")])]
  (check (= 127 code)
         "spawn-failure: a command that cannot start exits 127"
         (str "code=" code " (must not be 0, and must not look like a child status)")))

;; ───────────────────────────────────────────────────────────── lock reclamation

(reset-locks!)
(fs/mkdirSync (path/join lock-root "build") #js {:recursive true})
(fs/writeFileSync (path/join lock-root "build" "owner.json")
                  "{\"pid\":999999,\"startedAt\":\"2026-01-01T00:00:00.000Z\",\"cwd\":\"/nowhere\",\"scope\":\"build\"}\n")
(let [{:keys [code out]} (guard-run ["run" "build" "--" "echo" "marker-reclaimed"])]
  (check (and (= 0 code) (str/includes? out "marker-reclaimed"))
         "reclaims-dead-owner: a stale lock is still reclaimed"
         (str "code=" code " out=" (pr-str out))))

(reset-locks!)
(fs/mkdirSync (path/join lock-root "build") #js {:recursive true})
(let [{:keys [code out]} (guard-run ["run" "build" "--" "echo" "marker-half"])]
  (check (and (= 2 code) (not (str/includes? out "marker-half")))
         "refuses-half-created: a lock without owner.json is a peer, not a stale lock"
         (str "code=" code " out=" (pr-str out)
              " (running here deletes a live owner's lock and builds concurrently)")))

(reset-locks!)
(fs/mkdirSync (path/join lock-root "build") #js {:recursive true})
(let [old (/ (- (js/Date.now) (* 10 60 1000)) 1000)]
  (fs/utimesSync (path/join lock-root "build") old old))
(let [{:keys [code out]} (guard-run ["run" "build" "--" "echo" "marker-aged"])]
  (check (and (= 0 code) (str/includes? out "marker-aged"))
         "reclaims-aged-unreadable: an abandoned lock cannot deadlock the scope"
         (str "code=" code " out=" (pr-str out))))

;; ─────────────────────────────────────────────────────────────────────── summary

(fs/rmSync tmpdir #js {:recursive true :force true})

;; The count is printed, not just the exit code: a run that spawned nothing and
;; asserted nothing would otherwise exit 0 and read as a pass.
(println (str "\n" (if (zero? @failures) "PASS" "FAIL")
              " — guard=" guard
              ", " @passes " cases OK, " @failures
              " failure" (if (= 1 @failures) "" "s")))
(js/process.exit (if (zero? @failures) 0 1))

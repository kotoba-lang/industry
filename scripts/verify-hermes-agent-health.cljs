#!/usr/bin/env nbb
;; verify-hermes-agent-health — a bot fleet whose credentials died, reported by
;; nobody, for two days.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-hermes-agent-health.cljs [--findings]
;;   nbb ... scripts/verify-hermes-agent-health.cljs --self-test
;;   nbb ... scripts/verify-hermes-agent-health.cljs --home <dir>     ; fixtures
;;
;; ## What happened, and why prose would not have caught it
;;
;; 2026-08-29 21:13 the OpenRouter key in `~/.hermes/auth.json`'s credential
;; pool answered 401 `User not found.` — a revoked key, not an empty balance.
;; The pool wrote `last_status: exhausted` with `last_error_reset_at: null`,
;; which is permanent: nothing retries it, nothing clears it, nothing says so.
;;
;; What the operator saw, 37 hours later, was two errors that both named the
;; wrong thing:
;;
;;   Context length exceeded (510,398 tokens). Cannot compress further.
;;   HTTP 500 ... Error 1101: Worker threw exception    (api.murakumo.cloud)
;;
;; Neither is about length or about murakumo. With the pool dead the provider
;; resolved to the placeholder `no-key-required`, every call 401'd, the
;; auxiliary compressor 401'd with them — and `Cannot compress further` is what
;; conversation_loop.py:6030 prints when compression makes NO PROGRESS, whatever
;; the size. Measured proof it is not about size: the identical sentence appears
;; in this machine's own logs at **8,214 tokens**. Falling past the dead primary
;; to the last fallback then handed a 327k request to a model with a 262,144
;; ceiling behind a single shared llama.cpp queue, which is where the 1101 came
;; from. The primary's real context window is 1,310,720. It always fitted.
;;
;; So one dead credential produced two errors, both of which blamed something
;; downstream and true-sounding, for two days, across 22 profiles.
;;
;; ## The floor this holds
;;
;; CLAUDE.md's question 1: *入力が無いとき何を返すか。pass ならそれが欠陥.*
;; A missing `~/.hermes`, an unparseable auth.json, or an unreadable log is
;; recorded UNREADABLE and exits 2 — never as a clean fleet.
;;
;; ## Why a time window, and why it is not a knob to hide behind
;;
;; Log classes are only counted inside `--window-hours` (default 48). Without
;; it the first 401 ever logged would be a finding forever, and a detector that
;; can never return to green is the same as silence — the failure mode the
;; registry's own header names. The window is what lets fixing the credential
;; actually clear the finding. Credential-pool state carries no window: an
;; `exhausted` entry is a fact about now, not about a moment in a log.
;;
;; ## What it deliberately does NOT do
;;
;; It never reads a secret. The obvious stronger check — compare the pool's
;; `secret_fingerprint` against a fresh read of the configured source, which is
;; exactly what proved the diagnosis by hand (dead `c78ceb…` vs live `bba65f…`)
;; — needs `security find-generic-password`, and this file's home is a launchd
;; tick. ADR-2607178000 recorded that wall: launchd cannot answer a Keychain
;; unlock prompt. A check that prompts on a schedule becomes a permanent
;; :inconclusive. The fingerprint comparison belongs in the hand-run set.

(ns verify-hermes-agent-health
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [clojure.string :as str]))

(def ^:private argv (vec (drop 2 (.-argv js/process))))
(defn- flag? [f] (boolean (some #{f} argv)))
(defn- opt [f d] (let [i (.indexOf argv f)] (if (neg? i) d (get argv (inc i) d))))

(def ^:private findings? (flag? "--findings"))
(def ^:private self-test? (flag? "--self-test"))
(def ^:private window-hours (js/parseFloat (opt "--window-hours" "48")))

;; ── log classes ────────────────────────────────────────────────────────
;; Each is a distinct failure the operator would act on differently. They are
;; NOT collapsed into "auth broken": `no resolvable api_key` is a credential
;; that never resolved, `all fallbacks exhausted` is every lane being gone at
;; once, and the compression pair is the one that lies about its own cause.
(def ^:private log-classes
  [{:key "credential-unresolved" :sev "fail"
    :re #"no resolvable api_key"
    :say "provider resolved with the placeholder key and will 401 on any auth-required endpoint"}
   {:key "fallbacks-exhausted" :sev "fail"
    :re #"all fallbacks exhausted"
    :say "every provider lane failed in one turn — the agent had nowhere left to send the request"}
   {:key "compression-dead" :sev "warn"
    :re #"Failed to generate context summary"
    :say "the auxiliary compressor could not be called; context stops shrinking and the NEXT error will name length instead"}
   {:key "compression-no-progress" :sev "warn"
    :re #"Cannot compress further"
    :say "compression made no progress. The token count printed beside this is the request size, NOT a proven ceiling"}])

(def ^:private ts-re #"^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})")

(defn- line-ms
  "Epoch ms of a hermes log line's leading timestamp, or nil when it carries
   none (continuation lines of a traceback do not)."
  [line]
  (when-let [m (re-find ts-re line)]
    (let [[_ y mo d h mi s] m]
      (.getTime (js/Date. (js/parseInt y) (dec (js/parseInt mo)) (js/parseInt d)
                          (js/parseInt h) (js/parseInt mi) (js/parseInt s))))))

(def ^:private tail-bytes (* 4 1024 1024))

(defn- read-tail
  "Last `tail-bytes` of a file. Throws on unreadable — the caller records that
   as UNREADABLE rather than as a file with nothing in it."
  [p]
  (let [size (.-size (fs/statSync p))
        from (max 0 (- size tail-bytes))
        len (- size from)
        fd (fs/openSync p "r")]
    (try
      (let [buf (js/Buffer.alloc len)]
        (fs/readSync fd buf 0 len from)
        (.toString buf "utf8"))
      (finally (fs/closeSync fd)))))

(defn- oldest-ms
  "Epoch ms of the earliest timestamped line in `text`, or nil.

  This is what makes the window claim checkable. Without it a clean scan of a
  log that only reaches back 55 minutes prints the same thing as a clean scan
  of a log that reaches back 48 hours -- CLAUDE.md's question 4, applied to
  this file's own output."
  [text]
  (reduce (fn [acc line] (if-let [t (line-ms line)] (if acc (min acc t) t) acc))
          nil (str/split-lines text)))

(defn- merge-scans
  "Combine per-file class maps: counts add, and the newest sample wins."
  [a b]
  (reduce (fn [acc [k v]]
            (let [cur (get acc k)]
              (assoc acc k (if (and cur (>= (:last cur) (:last v)))
                             (update cur :n + (:n v))
                             (assoc v :n (+ (:n v) (:n cur 0)))))))
          a b))

(defn- scan-log
  "{class-key -> {:n :last :sample}} for lines inside the window."
  [text cutoff-ms]
  (reduce
   (fn [acc line]
     (if-let [t (line-ms line)]
       (if (< t cutoff-ms)
         acc
         (reduce (fn [a {:keys [key re]}]
                   (if (re-find re line)
                     (-> a
                         (update-in [key :n] (fnil inc 0))
                         (assoc-in [key :last] t)
                         (assoc-in [key :sample] (subs line 0 (min 200 (count line)))))
                     a))
                 acc log-classes))
       acc))
   {}
   (str/split-lines text)))

;; ── credential pool ────────────────────────────────────────────────────
;; `exhausted` is the state that was silent. A non-nil `last_error_code` on a
;; pool entry is the same class one step earlier: the pool tried, was refused,
;; and kept the refusal.
(defn- pool-findings
  "auth MUST already be js->clj'd. `get` on a raw JS object returns nil, which
   would report every dead credential as healthy — the self-test caught exactly
   that on the first run of this file."
  [auth profile]
  (for [[provider entries] (or (get auth "credential_pool") {})
        e entries
        :let [status (get e "last_status")
              code (get e "last_error_code")
              id (or (get e "id") "?")]
        :when (or (= status "exhausted") (some? code))]
    {:key (str "hermes-credential:" profile "/" provider "/" id)
     :sev "fail"
     :detail (str "credential pool entry is " (or status "in error")
                  (when code (str " (last HTTP " code ")"))
                  (when-let [m (get e "last_error_message")]
                    (str " — " (subs m 0 (min 160 (count m)))))
                  (if (contains? e "last_error_reset_at")
                    (if (get e "last_error_reset_at")
                      "; will retry"
                      "; last_error_reset_at is null — it will NEVER retry on its own")
                    "")
                  ". Clear with `hermes auth reset " provider
                  "` then restart the gateway (the running one holds it in memory).")}))

(defn- profiles [home]
  (let [pd (path/join home "profiles")]
    (cons ["<root>" home]
          (when (fs/existsSync pd)
            (for [n (sort (fs/readdirSync pd))
                  :let [d (path/join pd n)]
                  :when (try (.isDirectory (fs/statSync d)) (catch :default _ false))]
              [n d])))))

(def ^:private log-names
  ;; hermes rotates errors.log at ~2 MB. Reading only the live file made this
  ;; detector claim a 48h window over whatever survived rotation -- measured
  ;; 2026-08-31: 96.3% of errors.log was one repeating warning and the oldest
  ;; surviving line was 55 minutes old. Rotation is not a reason to shrink the
  ;; window silently.
  ["errors.log" "errors.log.1" "errors.log.2"])

(defn- scan-profile [[name dir] cutoff-ms]
  (let [auth-p (path/join dir "auth.json")
        log-ps (filterv fs/existsSync
                        (mapv #(path/join dir "logs" %) log-names))
        out {:name name :read 0}
        out (if (fs/existsSync auth-p)
              (try (-> out
                       (update :read inc)
                       (assoc :pool (pool-findings
                                     (js->clj (js/JSON.parse (str (fs/readFileSync auth-p "utf8"))))
                                     name)))
                   (catch :default e
                     (assoc out :error (str "auth.json: " (.-message e)))))
              out)]
    (if (seq log-ps)
      (try (reduce (fn [acc lp]
                     (let [text (read-tail lp)]
                       (-> acc
                           (update :read inc)
                           (update :log merge-scans (scan-log text cutoff-ms))
                           (update :oldest (fn [o] (let [t (oldest-ms text)]
                                                     (cond (nil? t) o
                                                           (nil? o) t
                                                           :else (min o t))))))))
                   (assoc out :log {} :rotated? (> (count log-ps) 1)) log-ps)
           (catch :default e
             (assoc out :error (str "errors.log: " (.-message e)))))
      out)))

;; ── self-test: both directions, or this is theatre ─────────────────────
(def ^:private exhausted-fixture
  #js {"credential_pool"
       #js {"openrouter"
            #js [#js {"id" "e9650b" "last_status" "exhausted" "last_error_code" 401
                      "last_error_message" "Error code: 401 - User not found."
                      "last_error_reset_at" nil}]}})
(def ^:private healthy-fixture
  #js {"credential_pool"
       #js {"openrouter" #js [#js {"id" "e9650b" "last_status" nil "last_error_code" nil}]}})

(defn- self-test! []
  (let [now (.now js/Date)
        recent (str (-> (js/Date. (- now 3600000)) (.toISOString) (.slice 0 10))
                    " 12:00:00 WARNING x: no resolvable api_key here")
        ;; A line that carries the pattern but is OUTSIDE the window. Without
        ;; the window this is what would keep the detector red forever.
        old "2020-01-01 12:00:00 WARNING x: no resolvable api_key here"
        cutoff (- now (* window-hours 3600000))
        bad (count (pool-findings (js->clj exhausted-fixture) "p"))
        good (count (pool-findings (js->clj healthy-fixture) "p"))
        ;; A parse that could not run must not look like a parse that found
        ;; nothing: this is the shape the whole file exists to refuse.
        unreadable (try (do (js/JSON.parse "{not json") :parsed)
                        (catch :default _ :refused))
        in-win (count (scan-log recent cutoff))
        out-win (count (scan-log old cutoff))
        reason (get-in (scan-log recent cutoff) ["credential-unresolved" :n])]
    (println "self-test  flags an exhausted pool entry:      " bad "(expect 1)")
    (println "self-test  silent on a healthy pool entry:     " good "(expect 0)")
    (println "self-test  flags an in-window log line:        " in-win "(expect 1)")
    (println "self-test  silent on an out-of-window line:    " out-win "(expect 0)")
    (println "self-test  the in-window hit is the NAMED one:" (pr-str reason) "(expect 1)")
    (println "self-test  refuses an unparseable auth.json:  " (name unreadable) "(expect refused)")
    (if (and (= 1 bad) (zero? good) (= 1 in-win) (zero? out-win)
             (= 1 reason) (= :refused unreadable))
      (do (println "self-test OK — discriminates in both directions, for its own stated reason") 0)
      (do (println "self-test FAILED — a check that cannot fail is theatre") 1))))

(defn- fmt1 [x] (.toFixed x 1))

(defn- covered-h
  "Hours of log this profile's files actually reach back, or nil when it has
   no timestamped log at all."
  [r]
  (when-let [o (:oldest r)] (/ (- (.now js/Date) o) 3600000)))

(defn- -main []
  (if self-test?
    (set! (.-exitCode js/process) (self-test!))
    (let [home (opt "--home" (path/join (os/homedir) ".hermes"))]
      (if-not (fs/existsSync home)
        (binding [*print-fn* *print-err-fn*]
          (println (str "CANNOT ANSWER — " home " does not exist."
                        " Refusing to report a healthy agent fleet from a missing one."))
          (set! (.-exitCode js/process) 2))
        (let [cutoff (- (.now js/Date) (* window-hours 3600000))
              ps (vec (profiles home))
              rs (mapv #(scan-profile % cutoff) ps)
              broken (filterv :error rs)
              read-n (reduce + 0 (map :read rs))
              all (concat
                   (mapcat :pool rs)
                   (for [r rs
                         {:keys [key sev say]} log-classes
                         :let [hit (get (:log r) key)]
                         :when hit]
                     {:key (str "hermes-" key ":" (:name r))
                      :sev sev
                      ;; Say the span this profile's logs actually reach back,
                      ;; not the nominal window. They are the same only when
                      ;; rotation has not eaten the difference.
                      :detail (str (:n hit) "x in the last "
                                   (let [cov (covered-h r)]
                                     (if (and cov (< cov (* 0.95 window-hours)))
                                       (str (fmt1 cov) "h of log that survives"
                                            " (window is " window-hours "h; the rest"
                                            " has rotated away)")
                                       (str window-hours "h")))
                                   " — " say " | " (:sample hit))}))]
          ;; Evidence floor. A profile whose files could not be read is not a
          ;; profile with nothing wrong, and the two used to print the same 0.
          (println (str "SCANNED\t" read-n "\tfile(s) across " (count ps) " profile(s)"
                        (when (seq broken) (str "\tUNREADABLE\t" (count broken)))))
          ;; Coverage is evidence, not a finding: rotation is normal, and a
          ;; class that could never return to green is the silence this file
          ;; exists to break. But a clean scan over 55 minutes must not read
          ;; as a clean scan over 48 hours.
          ;; A young log and a rotated-away log both cover less than the
          ;; window, and only one of them is a problem. The fleet MINIMUM
          ;; conflates them: one profile whose log was created a minute ago
          ;; drags it to 0.0h and hides that everyone else is fine.
          (let [with-logs (filterv covered-h rs)
                short (filterv #(< (covered-h %) (* 0.95 window-hours)) with-logs)
                truncated (filterv :rotated? short)]
            (println (str "LOGSPAN\tfull=" (- (count with-logs) (count short))
                          "/" (count with-logs) " profile(s) cover " window-hours "h"
                          (when (seq truncated)
                            (str "\tTRUNCATED\t"
                                 (str/join ", "
                                   (for [r (take 3 (sort-by covered-h truncated))]
                                     (str (:name r) "=" (fmt1 (covered-h r)) "h")))
                                 " — rotation ate the rest; absence of a finding"
                                 " there is not evidence over the full window"))
                          (when (and (seq short) (empty? truncated))
                            (str "\tYOUNG\t" (count short)
                                 " profile(s) simply have less history than the"
                                 " window; nothing has rotated away")))))
          (doseq [b broken]
            (binding [*print-fn* *print-err-fn*]
              (println (str "  unreadable: " (:name b) " — " (:error b)))))
          (when (or (zero? read-n) (seq broken))
            (binding [*print-fn* *print-err-fn*]
              (println (str "CANNOT ANSWER — " (count broken) " profile(s) unreadable,"
                            " " read-n " file(s) read. Refusing to report clean.")))
            (set! (.-exitCode js/process) 2))
          (doseq [f (sort-by :key all)]
            (if findings?
              (println (str "FINDING\t" (:sev f) "\t" (:key f) "\t" (:detail f)))
              (println (str "  [" (:sev f) "] " (:key f) "\n      " (:detail f)))))
          (when (and (seq all) (not= 2 (.-exitCode js/process)))
            (set! (.-exitCode js/process) 1)))))))

(-main)

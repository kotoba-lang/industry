;; scripts/verify-hermes-profile-fitness.cljs — the hermes agent bot fleet,
;; measured rather than described.
;;
;;   nbb scripts/verify-hermes-profile-fitness.cljs            # human report
;;   nbb scripts/verify-hermes-profile-fitness.cljs --findings # detector protocol
;;   nbb scripts/verify-hermes-profile-fitness.cljs --self-test
;;
;; ## Why this exists
;;
;; `scripts/hermes-cron-jobs/` says its purpose is to make a new terminal's
;; rebuild procedural, because the live cron definitions live in
;; `~/.hermes/profiles/<p>/cron/jobs.json`, are terminal-local, and are in no
;; repo. Measured 2026-09-04: 98 profiles carry cron jobs and 21 are in that
;; ledger. A rebuild from it would restore roughly a fifth of the fleet.
;;
;; The ledger's own `--check` reports that correctly -- it exits 1 with
;; "STALE: ledger differs from live cron definitions". Nothing runs it. It
;; appears in no detector registry, no cron job, and no gate; the only two
;; references to it in the tree are its own README and its own output. And
;; the file records `"generated_at": "regenerated-on-run"` -- a literal, not
;; a timestamp -- so a reader cannot tell whether it is an hour or a season
;; behind. A registry that cannot say how stale it is reads exactly like one
;; that is current.
;;
;; This detector is the part that keeps looking. It counts; it rates nothing.
;;
;; ## The measurement that nearly went out wrong
;;
;; The first version resolved a job's `:script` against `(or workdir ".")`
;; and reported 79 of 83 missing. `:script` is a bare filename and a job with
;; no `:workdir` runs it from its own profile directory, so 73 of those 79
;; existed. The real number is 6. A resolution rule that is wrong in the
;; direction of "missing" produces a page of alarming findings that are all
;; the tool's fault, which is worse than silence because it gets acted on.
;; Every path here is tried against the four places a job can actually mean.
(ns verify-hermes-profile-fitness
  (:require ["fs" :as fs] ["os" :as os] ["path" :as path]
            [clojure.string :as str]))

(def ^:private argv (vec (drop 2 (js->clj js/process.argv))))
(defn- flag? [f] (boolean (some #{f} argv)))
(def ^:private findings? (flag? "--findings"))
(def ^:private self-test? (flag? "--self-test"))

(def ^:private home
  (or (second (drop-while #(not= "--home" %) argv))
      (path/join (os/homedir) ".hermes")))
(def ^:private profiles-root (path/join home "profiles"))
(def ^:private ledger-path "scripts/hermes-cron-jobs/hermes-cron-jobs.json")

(defn- read-json [p]
  (try {:ok (js->clj (js/JSON.parse (fs/readFileSync p "utf8")) :keywordize-keys true)}
       (catch :default e {:error (or (.-message e) (str e))})))

(defn- exists? [p] (and (string? p) (seq p) (fs/existsSync p)))

(defn profile-model
  "`model.default` out of a profile's config.yaml, plus its fallback models.

  Deliberately not a YAML parser: the two shapes wanted here are a
  two-space-indented `default:` under a top-level `model:` block, and
  `model:` entries under `fallback_providers:`. Anything else is reported as
  nil rather than guessed.

  Measured 2026-09-04, this is why a job-level `:model` of nil is NOT a
  finding: 61 of 154 jobs declare none, and every one of them inherits a
  profile default that does exist. An earlier version of this detector
  warned about all 61."
  [text]
  (let [lines (str/split-lines (str text))
        in-model (atom false) in-fb (atom false)
        default (atom nil) fbs (atom [])]
    (doseq [l lines]
      (cond
        (re-matches #"^model:\s*$" l) (do (reset! in-model true) (reset! in-fb false))
        (re-matches #"^fallback_providers:\s*$" l) (do (reset! in-fb true) (reset! in-model false))
        (re-matches #"^[A-Za-z_].*" l) (do (reset! in-model false) (reset! in-fb false))
        :else
        (do (when @in-model
              (when-let [m (re-matches #"^\s+default:\s*['\"]?([^'\"\s]+)['\"]?\s*$" l)]
                (reset! default (second m))))
            (when @in-fb
              (when-let [m (re-matches #"^\s+-?\s*model:\s*['\"]?([^'\"\s]+)['\"]?\s*$" l)]
                (swap! fbs conj (second m)))))))
    {:default @default :fallbacks @fbs
     ;; provider NAMES only, never their contents: a definition can carry a
     ;; key_env and a secrets helper, and this detector prints what it counts.
     :providers (set (keep #(second (re-matches #"^  ([A-Za-z0-9_.-]+):\s*$" %))
                           (->> lines
                                (drop-while #(not (re-matches #"^providers:\s*$" %)))
                                rest
                                (take-while #(not (re-matches #"^[A-Za-z_].*" %))))))}))

(defn- expand [p]
  (if (str/starts-with? (str p) "~")
    (str (os/homedir) (subs (str p) 1))
    p))

(defn resolve-script
  "Where a job's `:script` can actually live. A bare filename with no
  `:workdir` runs from the profile's own directory -- resolving it against
  the process cwd is what produced 79 false findings on the first run."
  [{:keys [script workdir]} profile root]
  (let [base (first (str/split (str script) #"\s"))]
    (cond
      (str/blank? base) []
      (str/starts-with? base "/") [base]
      :else (cond-> [(path/join root profile base)
                     (path/join root profile "scripts" base)]
              (seq (str workdir)) (conj (path/join (expand workdir) base))))))

;; ── measurement ─────────────────────────────────────────────────────────────

(defn- collect []
  (let [names (vec (sort (fs/readdirSync profiles-root)))
        reads (for [p names
                    :let [f (path/join profiles-root p "cron" "jobs.json")]
                    :when (fs/existsSync f)]
                (assoc (read-json f) :profile p :file f))
        broken (filterv :error reads)
        good (filterv :ok reads)
        jobs (vec (mapcat (fn [{:keys [profile ok]}]
                            (map #(assoc % :profile profile) (:jobs ok)))
                          good))]
    {:profile-models
     (into {} (for [p names
                    :let [c (path/join profiles-root p "config.yaml")]
                    :when (fs/existsSync c)]
                [p (profile-model (fs/readFileSync c "utf8"))]))
     :profiles names
     ;; SCHEDULED means "carries at least one job", not "has the file". A
     ;; profile whose jobs.json holds an empty list is not scheduled work, and
     ;; export_cron.py correctly omits it -- counting it here produced a
     ;; coverage finding against the one profile that was right (measured
     ;; 2026-09-04: `aozora`, jobs: []).
     :scheduled (mapv :profile (filter #(seq (:jobs (:ok %))) good))
     :broken broken :jobs jobs :files-read (count good)}))

(defn- ledger-state []
  (let [{:keys [ok error]} (read-json ledger-path)]
    {:error error
     :names (set (map name (keys (:profiles ok))))
     :generated-at (:generated_at ok)}))

(defn- dated? [s] (boolean (re-find #"\d{4}-\d{2}-\d{2}" (str s))))

(def ^:const decided-primary
  "ADR-2608313500, measured N=24: 100% usable vs 91.7% at a third of the cost."
  "z-ai/glm-5.3-flash")

(def ^:const fleet-alias
  "ADR-2607173100: model ids move; consumers should name the alias and let the
  KV entry resolve it. A profile on this is following a DIFFERENT accepted
  rule, not violating one."
  "murakumo-main")

(defn findings-for
  "Pure: the finding set for an already-collected fleet."
  [{:keys [scheduled jobs profile-models]} {:keys [names generated-at]} root]
  (let [n-sched (count scheduled)
        absent (remove names scheduled)
        failing (filter #(pos? (or (:failure_streak %) 0)) jobs)
        ;; :monitor_script resolves the same way and was not checked at all.
        ;; Measured 2026-09-04, that gap hid the longest break in the fleet:
        ;; fleet-cron-watchdog, 16 consecutive failures, `Script not found:
        ;; .../scripts/scripts/fleet_cron_watchdog.py` -- the runner already
        ;; resolves against <workdir>/scripts/ and the value carried its own
        ;; `scripts/` prefix. The job whose whole purpose is to notice failing
        ;; cron jobs had been down longest, and this detector could not see it.
        dangling (filter (fn [j]
                           (some (fn [k]
                                   (and (seq (str (get j k)))
                                        (not-any? exists?
                                                  (resolve-script {:script (get j k)
                                                                   :workdir (:workdir j)}
                                                                  (:profile j) root))))
                                 [:script :monitor_script]))
                         jobs)
        bad-wd (filter (fn [j] (and (seq (str (:workdir j)))
                                    (not (exists? (expand (:workdir j))))))
                       jobs)
        ;; `openrouter` needs no definition -- 29 profiles name it with an empty
        ;; providers map and work. A name the profile does NOT define, with no
        ;; :base_url on the job either, resolves to nothing.
        wrong-monitor-ext
        (filter (fn [j]
                  (let [m (str (:monitor_script j))]
                    (and (seq m)
                         (not (str/ends-with? m ".sh"))
                         (not (str/ends-with? m ".py")))))
                jobs)
        unresolvable-provider
        (filter (fn [j]
                  (let [p (str (:provider j))
                        defined (get-in profile-models [(:profile j) :providers] #{})]
                    (and (seq p) (not= "openrouter" p)
                         (str/blank? (str (:base_url j)))
                         (not (contains? defined p)))))
                jobs)]
    (cond-> []
      (seq absent)
      (conj {:sev "fail" :key "ledger-coverage"
             :detail (str (count absent) " of " n-sched " scheduled profiles are absent from "
                          ledger-path " (" (- n-sched (count absent)) " present). A rebuild from "
                          "the ledger restores " (.toFixed (* 100.0 (/ (- n-sched (count absent))
                                                                       (double (max 1 n-sched)))) 0)
                          "% of the fleet. `python3 scripts/hermes-cron-jobs/export_cron.py` "
                          "regenerates it; its own --check already says STALE and nothing runs it.")})

      (not (dated? generated-at))
      (conj {:sev "fail" :key "ledger-staleness-unmeasurable"
             :detail (str "the ledger records generated_at=" (pr-str generated-at)
                          " -- a literal, not a timestamp. A reader cannot tell whether it is "
                          "an hour or a season behind, so a stale registry reads as a current one.")})

      (seq dangling)
      (conj {:sev "fail" :key "dangling-script"
             :detail (str (count dangling) " job(s) name a :script that exists nowhere they could "
                          "mean (profile dir, profile/scripts, workdir, absolute): "
                          (str/join ", "
                                     (take 6 (map #(str (:profile %) "/"
                                                        (or (:script %) (:monitor_script %))
                                                        (when (and (:monitor_script %)
                                                                   (not (:script %)))
                                                          " (monitor_script)"))
                                                  dangling))))})

      (seq bad-wd)
      (conj {:sev (if (some #(not= "ok" (:last_status %)) bad-wd) "fail" "warn")
             :key "missing-workdir"
             ;; Severity is measured, not assumed. 2026-09-04: 19 jobs named a
             ;; missing :workdir and 13 of them reported last_status ok, having
             ;; run that same day -- the runner does not require it. Rating all
             ;; 19 as `fail` would have sent someone to change 13 working bots.
             :detail (str (count bad-wd) " job(s) name a :workdir that does not exist ("
                          (count (filter #(= "ok" (:last_status %)) bad-wd))
                          " of them last ran OK, so the value is drift rather than the "
                          "cause): "
                          (str/join ", " (take 6 (distinct (map :workdir bad-wd)))))})


      (seq (remove (fn [[_ m]] (#{decided-primary fleet-alias} (:default m))) profile-models))
      (conj {:sev "warn" :key "model-primary-split"
             :detail (let [by (frequencies (map (fn [[_ m]] (or (:default m) "(unreadable)"))
                                                profile-models))
                           glm (get by decided-primary 0)
                           alias' (get by fleet-alias 0)
                           other (- (count profile-models) glm alias')]
                       (str "primary model.default across " (count profile-models) " profiles: "
                            glm " on " decided-primary " (ADR-2608313500), "
                            alias' " on " fleet-alias " (ADR-2607173100's alias), "
                            other " on something else -- "
                            (pr-str (into (sorted-map) (apply dissoc by [decided-primary fleet-alias])))
                            ". The two ADRs do not reference each other: 2608313500 names a "
                            "concrete id after measuring it, 2607173100 says consumers should "
                            "name the alias and let KV resolve it. This is reported, not judged."))})

      (seq wrong-monitor-ext)
      (conj {:sev "fail" :key "monitor-script-unrunnable"
             ;; The runner executes a .sh monitor via bash and EVERYTHING ELSE
             ;; via Python. A .cljs monitor cannot run even when the file is
             ;; present. The fleet's own convention, stated in the comment at
             ;; the top of canvas-signal-itonami.py, is a thin .py launcher
             ;; that shells out to nbb -- because this workspace prohibits new
             ;; .sh and writes new scripts in nbb.
             :detail (str (count wrong-monitor-ext)
                          " job(s) name a :monitor_script the runner cannot execute "
                          "(it runs .sh via bash and everything else via Python, so a "
                          ".cljs monitor fails even when present -- use a thin .py "
                          "launcher that calls nbb): "
                          (str/join ", " (map #(str (:profile %) "/" (:monitor_script %))
                                              wrong-monitor-ext)))})

      (seq unresolvable-provider)
      (conj {:sev "fail" :key "unresolvable-provider"
             ;; The failure ADR-2608313500 documented, still live: a job naming
             ;; `custom` with no :base_url, in a profile whose `providers` map
             ;; does not define it. Measured 2026-09-04: six otent jobs, 11-15
             ;; consecutive `RuntimeError: Connection error.`, while the
             ;; profile's own model config was correct the whole time.
             :detail (str (count unresolvable-provider)
                          " job(s) name a provider their profile cannot resolve and carry no "
                          ":base_url of their own -- "
                          (str/join ", " (take 6 (map #(str (:profile %) "/" (:name %)
                                                            " -> " (:provider %))
                                                      unresolvable-provider))))})

      (seq failing)
      (conj {:sev "warn" :key "failure-streak"
             :detail (str (count failing) " job(s) carry failure_streak > 0: "
                          (str/join ", " (take 6 (map #(str (:profile %) "/" (:name %) "="
                                                            (:failure_streak %)) failing))))}))))

;; ── self-test ───────────────────────────────────────────────────────────────

(defn- self-test []
  (let [root "/tmp/hpf-selftest/profiles"
        mk (fn [p f content]
             (fs/mkdirSync (path/join root p (or f ".")) #js {:recursive true})
             (when content
               (fs/writeFileSync (path/join root p "cron" "jobs.json") content)))
        _ (do (fs/rmSync "/tmp/hpf-selftest" #js {:recursive true :force true})
              (fs/mkdirSync (path/join root "a" "cron") #js {:recursive true})
              (fs/writeFileSync (path/join root "a" "runme.sh") "x")
              (fs/writeFileSync (path/join root "a" "cron" "jobs.json")
                                (js/JSON.stringify
                                 (clj->js {:jobs [{:name "ok" :script "runme.sh" :model "z-ai/glm-5.3-flash"}
                                                  {:name "gone" :script "nope.sh" :model "z-ai/glm-5.3-flash"}
                                                  {:name "nomodel" :failure_streak 3}]}))))
        fleet {:scheduled ["a"]
               :jobs (mapv #(assoc % :profile "a")
                           [{:name "ok" :script "runme.sh" :model "z-ai/glm-5.3-flash"}
                            {:name "gone" :script "nope.sh" :model "z-ai/glm-5.3-flash"}
                            {:name "nomodel" :failure_streak 3}])}
        by-key #(into {} (map (juxt :key identity)) %)
        covered (by-key (findings-for fleet {:names #{"a"} :generated-at "2026-09-04T00:00:00Z"} root))
        naked   (by-key (findings-for fleet {:names #{} :generated-at "regenerated-on-run"} root))
        checks
        [["a bare script in the profile dir resolves"
          (= 1 (count (filter exists? (resolve-script {:script "runme.sh"} "a" root))))]
         ["a script that is nowhere is reported"
          (str/includes? (:detail (covered "dangling-script")) "nope.sh")]
         ["and the one that resolves is NOT reported"
          (not (str/includes? (:detail (covered "dangling-script")) "runme.sh"))]
         ["a fully covered ledger raises no coverage finding"
          (nil? (covered "ledger-coverage"))]
         ["an empty ledger does"
          (some? (naked "ledger-coverage"))]
         ["a dated generated_at raises no staleness finding"
          (nil? (covered "ledger-staleness-unmeasurable"))]
         ["a literal generated_at does"
          (some? (naked "ledger-staleness-unmeasurable"))]
         ;; the correction this axis replaced: 61 of 154 jobs declare no
         ;; :model and every one inherits a profile default, so warning about
         ;; them was noise about the wrong layer
         ["a job with no :model is not a finding"
          (nil? (covered "model-unset"))]
         ["a fleet all on the decided primary raises no split finding"
          (nil? ((by-key (findings-for
                          {:scheduled ["a"] :jobs []
                           :profile-models {"a" {:default decided-primary}
                                            "b" {:default decided-primary}}}
                          {:names #{"a"} :generated-at "2026-09-04T00:00:00Z"} root))
                 "model-primary-split"))]
         ["the fleet alias counts as following a rule, not breaking one"
          (nil? ((by-key (findings-for
                          {:scheduled ["a"] :jobs []
                           :profile-models {"a" {:default decided-primary}
                                            "b" {:default fleet-alias}}}
                          {:names #{"a"} :generated-at "2026-09-04T00:00:00Z"} root))
                 "model-primary-split"))]
         ["a third model is reported, and named"
          (let [f ((by-key (findings-for
                            {:scheduled ["a"] :jobs []
                             :profile-models {"a" {:default decided-primary}
                                              "b" {:default "some/other-model"}}}
                            {:names #{"a"} :generated-at "2026-09-04T00:00:00Z"} root))
                   "model-primary-split")]
            (and (some? f) (str/includes? (:detail f) "some/other-model")))]
         ["model.default, fallbacks and provider NAMES are read separately"
          (= {:default "z-ai/glm-5.3-flash" :fallbacks ["claude-sonnet-5"]
              :providers #{"openrouter-free"}}
             (profile-model (str "model:\n  provider: openrouter\n"
                                 "  default: z-ai/glm-5.3-flash\n"
                                 "providers:\n  openrouter-free:\n    base_url: x\n"
                                 "fallback_providers:\n  - provider: custom\n"
                                 "    model: claude-sonnet-5\n")))]
         ;; the class that broke six jobs for 11-15 runs
         ["a job naming a provider its profile does not define is a fail"
          (let [f ((by-key (findings-for
                            {:scheduled ["a"] :profile-models {"a" {:providers #{}}}
                             :jobs [{:profile "a" :name "j" :provider "custom"}]}
                            {:names #{"a"} :generated-at "2026-09-04T00:00:00Z"} root))
                   "unresolvable-provider")]
            (and (= "fail" (:sev f)) (str/includes? (:detail f) "a/j -> custom")))]
         ["plain `openrouter` needs no definition and is not a finding"
          (nil? ((by-key (findings-for
                          {:scheduled ["a"] :profile-models {"a" {:providers #{}}}
                           :jobs [{:profile "a" :name "j" :provider "openrouter"}]}
                          {:names #{"a"} :generated-at "2026-09-04T00:00:00Z"} root))
                 "unresolvable-provider"))]
         ["nor is a job that carries its own :base_url"
          (nil? ((by-key (findings-for
                          {:scheduled ["a"] :profile-models {"a" {:providers #{}}}
                           :jobs [{:profile "a" :name "j" :provider "custom"
                                   :base_url "http://127.0.0.1:9180/v1"}]}
                          {:names #{"a"} :generated-at "2026-09-04T00:00:00Z"} root))
                 "unresolvable-provider"))]
         ;; the gap that hid the fleet's longest break
         ["a missing :monitor_script is reported like a missing :script"
          (str/includes? (:detail ((by-key (findings-for
                                            {:scheduled ["a"] :profile-models {}
                                             :jobs [{:profile "a" :name "w"
                                                     :monitor_script "scripts/nope.py"}]}
                                            {:names #{"a"} :generated-at "2026-09-04T00:00:00Z"} root))
                                   "dangling-script"))
                         "nope.py")]
         ["a .cljs monitor_script is unrunnable under a Python-invoking runner"
          (some? ((by-key (findings-for
                           {:scheduled ["a"] :profile-models {}
                            :jobs [{:profile "a" :name "j" :monitor_script "x.cljs"}]}
                           {:names #{"a"} :generated-at "2026-09-04T00:00:00Z"} root))
                  "monitor-script-unrunnable"))]
         ["a .py launcher is not"
          (nil? ((by-key (findings-for
                          {:scheduled ["a"] :profile-models {}
                           :jobs [{:profile "a" :name "j" :monitor_script "x.py"}]}
                          {:names #{"a"} :generated-at "2026-09-04T00:00:00Z"} root))
                 "monitor-script-unrunnable"))]
         ;; severity is measured: 13 of 19 such jobs last ran OK
         ["a missing :workdir on a healthy job is a warn, not a fail"
          (= "warn" (:sev ((by-key (findings-for
                                    {:scheduled ["a"] :profile-models {}
                                     :jobs [{:profile "a" :name "j"
                                             :workdir "/tmp/definitely-not-here"
                                             :last_status "ok"}]}
                                    {:names #{"a"} :generated-at "2026-09-04T00:00:00Z"} root))
                           "missing-workdir")))]
         ["and a fail when one of them is not healthy"
          (= "fail" (:sev ((by-key (findings-for
                                    {:scheduled ["a"] :profile-models {}
                                     :jobs [{:profile "a" :name "j"
                                             :workdir "/tmp/definitely-not-here"
                                             :last_status "error"}]}
                                    {:names #{"a"} :generated-at "2026-09-04T00:00:00Z"} root))
                           "missing-workdir")))]
         ["failure_streak is counted"
          (str/includes? (:detail (covered "failure-streak")) "=3")]
         ;; the correction above, pinned: a profile with an empty :jobs list is
         ;; not scheduled, so a ledger that omits it is complete, not 99% done
         ["an empty jobs list is not scheduled work"
          (nil? ((by-key (findings-for {:scheduled [] :jobs []}
                                       {:names #{} :generated-at "2026-09-04T00:00:00Z"} root))
                 "ledger-coverage"))]]]
    (doseq [[label ok] checks] (println (if ok "  ok  " "  FAIL") label))
    (fs/rmSync "/tmp/hpf-selftest" #js {:recursive true :force true})
    (if (every? second checks)
      (do (println "SCANNED\t" (count checks)) (js/process.exit 0))
      (js/process.exit 1))))

;; ── main ────────────────────────────────────────────────────────────────────

(defn- report [{:keys [profiles scheduled jobs] :as fleet} ledger]
  (let [n (count jobs)]
    (println)
    (println "hermes agent bots — profile fitness (counted; nothing here is a rating)")
    (println (str/join (repeat 74 "=")))
    (println "  profiles on disk                 " (count profiles))
    (println "  scheduled (cron/jobs.json)       " (count scheduled))
    (println "  jobs                             " n)
    (println "  in the ledger                    " (count (:names ledger))
             (str "(" (.toFixed (* 100.0 (/ (count (:names ledger)) (double (max 1 (count scheduled))))) 1) "%)"))
    (println "  generated_at                     " (pr-str (:generated-at ledger)))
    (println "  enabled / paused / never-ran     "
             (count (filter #(true? (:enabled %)) jobs)) "/"
             (count (filter #(some? (:paused_at %)) jobs)) "/"
             (count (filter #(nil? (:last_run_at %)) jobs)))
    (println "  last_status                      "
             (pr-str (into (sorted-map) (frequencies (map #(or (:last_status %) "nil") jobs)))))
    (println "  model                            "
             (pr-str (into (sorted-map) (frequencies (map #(if (str/blank? (str (:model %)))
                                                             "unset" (:model %)) jobs)))))
    (println)))

(defn- -main []
  (cond
    self-test? (self-test)

    (not (fs/existsSync profiles-root))
    (do (binding [*print-fn* *print-err-fn*]
          (println (str "CANNOT ANSWER — no " profiles-root
                        ". Refusing to report a healthy fleet from an absent one.")))
        (js/process.exit 2))

    :else
    (let [fleet (collect)
          ledger (ledger-state)
          fs' (findings-for fleet ledger profiles-root)]
      (println (str "SCANNED\t" (:files-read fleet)))
      (when-not findings? (report fleet ledger))
      (doseq [b (:broken fleet)]
        (binding [*print-fn* *print-err-fn*]
          (println (str "  unreadable: " (:profile b) " — " (:error b)))))
      (doseq [f (sort-by :key fs')]
        (if findings?
          (println (str "FINDING\t" (:sev f) "\t" (:key f) "\t" (:detail f)))
          (println (str "  [" (:sev f) "] " (:key f) "\n      " (:detail f)))))
      (cond
        (or (zero? (:files-read fleet)) (seq (:broken fleet)))
        (do (binding [*print-fn* *print-err-fn*]
              (println (str "CANNOT ANSWER — " (count (:broken fleet)) " unreadable, "
                            (:files-read fleet) " read. Refusing to report clean.")))
            (js/process.exit 2))
        (seq fs') (js/process.exit 1)
        :else (js/process.exit 0)))))

(-main)

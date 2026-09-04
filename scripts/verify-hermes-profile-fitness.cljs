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
    {:profiles names
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

(defn findings-for
  "Pure: the finding set for an already-collected fleet."
  [{:keys [scheduled jobs]} {:keys [names generated-at]} root]
  (let [n-sched (count scheduled)
        absent (remove names scheduled)
        no-model (filter #(str/blank? (str (:model %))) jobs)
        failing (filter #(pos? (or (:failure_streak %) 0)) jobs)
        dangling (filter (fn [j]
                           (and (seq (str (:script j)))
                                (not-any? exists? (resolve-script j (:profile j) root))))
                         jobs)
        bad-wd (filter (fn [j] (and (seq (str (:workdir j)))
                                    (not (exists? (expand (:workdir j))))))
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
                          (str/join ", " (take 6 (map #(str (:profile %) "/" (:script %)) dangling))))})

      (seq bad-wd)
      (conj {:sev "fail" :key "missing-workdir"
             :detail (str (count bad-wd) " job(s) name a :workdir that does not exist -- "
                          (str/join ", " (take 6 (distinct (map :workdir bad-wd)))))})

      (seq no-model)
      (conj {:sev "warn" :key "model-unset"
             :detail (str (count no-model) " of " (count jobs) " jobs declare no :model, so "
                          "ADR-2608313500's order (z-ai/glm-5.3-flash primary) does not reach them "
                          "-- they take whatever the profile default is, which the job does not record.")})

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
         ["a job with no :model is a warn, not a fail"
          (= "warn" (:sev (covered "model-unset")))]
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

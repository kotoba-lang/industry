(ns transition-alarm
  "Ring once on a transition, not once per check.

  `residency-alarm.cljs` left instructions for whoever wrote the third one:
  *3 本目を書くときに `transition-alarm` として抽出する*. `capacity-alarm.cljs`
  is the third one, so this is that extraction.

  What every alarm here shares is not the check — it is the discipline
  around the check:

  - **Ringing every time is the same as not ringing.** The registry's lesson
    about a permanently red gate applies to notifications: healthy→unhealthy
    once, and recovery once.
  - **A first run does not know the previous state, and must not read that
    as 'unchanged'.** Introduced while already broken, an alarm that treats
    an absent state file as healthy stays silent forever.
  - **The alarm never carries the verdict.** It exits 0 even when what it
    watches is broken, because a red notifier confuses 'the monitor is
    broken' with 'the thing is broken'. `hayari-alarm` argued this and
    `residency-alarm` did the opposite (exit 1); this extraction adopts
    hayari's, and `residency-alarm` keeps its own exit contract because
    something may already branch on it.
  - **The judgement is never duplicated here.** A caller passes a `check`
    that returns `{:healthy? :why}`; thresholds live wherever the thing
    being measured is defined.

  **Not yet the single home for this.** `capacity-alarm` is the only caller;
  `residency-alarm` and `hayari-alarm` still carry their own copies of the
  transition logic. Porting them is a separate change, kept separate because
  verifying their transitions means driving the collectors they spawn, and a
  half-checked refactor of two working monitors is worse than a truthful
  note that the copies are still there."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]))

(defn notify!
  "macOS notification centre — the only human-facing channel that exists on
  this machine (measured: no slack or mail integration in this workspace).
  A failed notification is swallowed: the verdict still lands in the state
  file and the log."
  [title body]
  (try
    (cp/spawnSync "osascript"
                  (clj->js ["-e" (str "display notification " (pr-str body)
                                      " with title " (pr-str title))])
                  #js {:timeout 15000})
    (catch :default _ nil)))

(defn- read-state [f]
  (when (fs/existsSync f)
    (try (edn/read-string (fs/readFileSync f "utf8")) (catch :default _ nil))))

(defn run!
  "Run `check`, ring only on a transition, persist, and report.

  - `:check`     `(fn [] {:healthy? bool :why str :detail any})`
  - `:state-file` where the previous verdict lives
  - `:notify?`   false in tests
  - `:messages`  `{:broke [title body-fn] :recovered [title body-fn]}`,
                 each body-fn taking the check result

  Returns the check result. Never throws for an unhealthy subject."
  [{:keys [check state-file notify? messages label]}]
  (let [result (check)
        healthy? (boolean (:healthy? result))
        was (:healthy? (read-state state-file))
        ring! (fn [[title body-fn]]
                (when notify? (notify! title (body-fn result))))]
    (cond
      (nil? was) (when-not healthy? (ring! (:broke messages)))
      (and was (not healthy?)) (ring! (:broke messages))
      (and (not was) healthy?) (ring! (:recovered messages)))
    (fs/mkdirSync (path/dirname state-file) #js {:recursive true})
    (fs/writeFileSync state-file
                      (pr-str {:healthy? healthy?
                               :at (subs (.toISOString (js/Date.)) 0 19)
                               :why (when-not healthy? (:why result))}))
    (println (str (or label "alarm") ": healthy?=" healthy?
                  (cond (nil? was) " (初回)"
                        (= was healthy?) " (変化なし — 鳴らさない)"
                        :else " (遷移 — 鳴らした)")
                  (when-not healthy? (str " — " (:why result)))))
    result))

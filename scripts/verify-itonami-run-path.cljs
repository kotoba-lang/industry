#!/usr/bin/env nbb
;; scripts/verify-itonami-run-path.cljs — the run path the resident is
;; CONFIGURED to take, measured against the tree it would take it in.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-itonami-run-path.cljs \
;;     [--root <superproject checkout>] [--app <app checkout>] [--plist <path>]
;;     [--findings] [--self-test]
;;
;;   0  measured, nothing broken
;;   1  measured, findings
;;   2  could not measure  (NOT 0, NOT 1)
;;
;; ## The question nothing answered
;;
;; `cloud-itonami-app` is being moved off the JVM. On 2026-09-02 PR #270 landed
;; with the title "Guest wasm run path; leftover JVM aliases deleted (**not
;; JVM-removed**)" — an honest title for a change that deletes `:server`,
;; `:mcp` and `:cli` from `deps.edn` and replaces the launchers with nbb hosts
;; that load a wasm guest.
;;
;; The parenthetical is the whole problem. Deleting a run path and porting what
;; ran on it look identical from the tree: in both cases the alias is gone. What
;; separates them is whether anything still answers, and that is a different
;; file — the launchd plist on the operator's workstation, which on that day
;; still read `clojure -M:server`.
;;
;; Production did not notice, for one reason that is not a safeguard:
;; `~/.cloud-itonami/current` pointed at a release cut BEFORE #270, so the alias
;; the plist names still existed in the release it names. The next release flips
;; that. A resident whose launch command does not resolve does not fail loudly —
;; launchd `KeepAlive` restarts it, forever, and the port stays closed.
;;
;; ## Three legs, and why they are not collapsed
;;
;;   run-path   the plist's command, against the alias/file it needs in the tree
;;   cli        whether `bin/itonami` answers a command from the registry
;;   guest      whether the wasm the nbb hosts load is present in the tree
;;
;; A tree can pass one and fail another, and each failure has a different fix.
;; Reporting a single boolean would make "the CLI is closed" and "the resident
;; cannot start" the same sentence, and only one of them stops the app.
;;
;; ## The denominator is the registry, not a count of files
;;
;; `resources/cloud-itonami-app.commands.edn` is generated from `server.clj`'s
;; own routes and carries `:counts`. It is the only number in the tree that says
;; how much app there is to serve, so it is what coverage is reported against.
;; A migration that serves two routes out of four hundred is not "done with a
;; caveat"; it is a number, and this prints it.
;;
;; ## Absence is not zero
;;
;; Every leg can come back `:unmeasured` with its own reason — no plist (this is
;; not the operator's workstation), no app checkout, an unreadable `deps.edn`.
;; `:unmeasured` is never folded into `ok`, and any unmeasured leg that was
;; ASKED for exits 2 rather than 0, because "I could not look" and "I looked and
;; it was fine" are the two answers this workspace keeps confusing
;; (ADR-2608136000).

(ns verify-itonami-run-path
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def argv (vec (drop 3 (js->clj js/process.argv))))

(defn- flag [name]
  (let [i (.indexOf argv name)]
    (when (nat-int? i)
      (let [v (get argv (inc i))]
        (when (and v (not (str/starts-with? v "--"))) v)))))

(defn- switch? [name] (some? (some #{name} argv)))

(defn- exists? [p] (try (fs/existsSync p) (catch :default _ false)))
(defn- slurp-file [p] (try (fs/readFileSync p "utf8") (catch :default _ nil)))

;; ---------------------------------------------------------------------------
;; leg results
;; ---------------------------------------------------------------------------

(defn- ok [leg detail] {:leg leg :status :ok :detail detail})
(defn- broken [leg id detail] {:leg leg :status :broken :id id :detail detail})
(defn- unmeasured [leg why] {:leg leg :status :unmeasured :why why})

;; ---------------------------------------------------------------------------
;; the plist's run command
;; ---------------------------------------------------------------------------

(def default-plist
  (when-let [home (aget js/process.env "HOME")]
    (path/join home "Library" "LaunchAgents" "dev.cloud-itonami.app.plist")))

(defn read-run-command
  "The last shell word-group of the plist's ProgramArguments that names a run.

  The plist runs a login shell with one long `-lc` string that exports
  credentials and ends in `exec <runner>`. Only the runner is wanted, and it is
  read by locating `exec` rather than by parsing the shell, because everything
  before it is credential plumbing this must not copy or echo."
  [text]
  (when text
    (let [;; `&amp;&amp;` and friends survive as XML entities; the run word is plain.
          m (re-find #"exec\s+([^<&\n]+)" text)]
      (some-> (second m) str/trim not-empty))))

(defn classify-run-command
  "What the run command needs to exist, as data.

  `:clojure-alias` — `clojure -M:server` needs `:server` in deps.edn's aliases.
  `:node-script`   — `nbb … bin/x` needs the file.
  `:unknown`       — reported as such; a guess here would be a third leg that
                     silently passes."
  [command]
  (cond
    (nil? command) {:kind :unknown}

    (re-find #"clojure\b" command)
    (if-let [alias (second (re-find #"-M:([A-Za-z0-9_.-]+)" command))]
      {:kind :clojure-alias :alias alias :command command}
      {:kind :unknown :command command})

    (re-find #"\bnbb\b" command)
    (if-let [script (second (re-find #"(bin/[A-Za-z0-9_.-]+)" command))]
      {:kind :node-script :script script :command command}
      {:kind :unknown :command command})

    :else {:kind :unknown :command command}))

(defn deps-aliases
  "The alias keys `deps.edn` declares, or nil when it could not be read.

  Read with the EDN reader and NOT with a regex, because a regex over a 650-line
  deps.edn matches alias-shaped text inside comments and strings — which would
  report an alias as present on a tree that cannot run it."
  [app-dir]
  (let [f (path/join app-dir "deps.edn")]
    (when-let [text (slurp-file f)]
      (try
        (let [m (edn/read-string text)]
          (when (map? m)
            (some-> (:aliases m) keys set)))
        (catch :default _ nil)))))

(defn check-run-path [app-dir plist-path]
  (cond
    (nil? plist-path) (unmeasured :run-path "plist-path-not-given")
    (not (exists? plist-path)) (unmeasured :run-path "plist-absent")
    :else
    (let [text (slurp-file plist-path)]
      (if (nil? text)
        (unmeasured :run-path "plist-unreadable")
        (let [command (read-run-command text)
              spec (classify-run-command command)]
          (case (:kind spec)
            :clojure-alias
            (let [aliases (deps-aliases app-dir)]
              (cond
                (nil? aliases) (unmeasured :run-path "deps-edn-unreadable")
                (contains? aliases (keyword (:alias spec)))
                (ok :run-path (str "plist runs -M:" (:alias spec)
                                   "; deps.edn declares it"))
                :else
                (broken :run-path :resident-launch-alias-absent
                        (str "plist runs `clojure -M:" (:alias spec)
                             "` and deps.edn declares no such alias."
                             " A release cut from this tree gives the resident a"
                             " launch command that cannot resolve; launchd"
                             " KeepAlive restarts it and the port stays closed."))))

            :node-script
            (let [f (path/join app-dir (:script spec))]
              (if (exists? f)
                (ok :run-path (str "plist runs " (:script spec) "; present"))
                (broken :run-path :resident-launch-script-absent
                        (str "plist runs `" (:script spec)
                             "` and the tree does not carry it."))))

            (unmeasured :run-path
                        (str "run-command-unrecognized"
                             (when command (str ": " command))))))))))

;; ---------------------------------------------------------------------------
;; the CLI
;; ---------------------------------------------------------------------------

(def cli-closed-marker
  "The sentence bin/itonami prints when it refuses every command.

  Matched as a literal because that is what the file says today; if the CLI is
  ported the sentence goes with it and this stops matching, which is the
  direction this check wants to be wrong in."
  "leftover JVM run path is closed")

(defn check-cli [app-dir]
  (let [f (path/join app-dir "bin" "itonami")]
    (cond
      (not (exists? f)) (broken :cli :cli-absent "bin/itonami does not exist")
      :else
      (let [text (slurp-file f)]
        (cond
          (nil? text) (unmeasured :cli "bin-itonami-unreadable")
          (str/includes? text cli-closed-marker)
          (broken :cli :cli-closed
                  (str "bin/itonami refuses every command and names no"
                       " replacement that runs one. The registry's commands"
                       " have no front end in this tree."))
          :else (ok :cli "bin/itonami dispatches"))))))

;; ---------------------------------------------------------------------------
;; the guest wasm the nbb hosts load
;; ---------------------------------------------------------------------------

(defn guest-requirements
  "Every `target/…\\.wasm` path the bin/ hosts name, with the host that names it."
  [app-dir]
  (let [bin (path/join app-dir "bin")]
    (if-not (exists? bin)
      nil
      (->> (try (vec (fs/readdirSync bin)) (catch :default _ []))
           (keep (fn [name]
                   (when-let [text (slurp-file (path/join bin name))]
                     (let [hits (re-seq #"\"(target/[A-Za-z0-9_./-]+\.wasm)\"" text)]
                       (when (seq hits)
                         (mapv (fn [[_ p]] {:host name :wasm p}) hits))))))
           (apply concat)
           vec))))

(defn check-guest [app-dir]
  (let [reqs (guest-requirements app-dir)]
    (cond
      (nil? reqs) (unmeasured :guest "bin-directory-absent")
      (empty? reqs) (ok :guest "no host in bin/ loads a guest wasm")
      :else
      (let [missing (remove #(exists? (path/join app-dir (:wasm %))) reqs)]
        (if (empty? missing)
          (ok :guest (str (count reqs) " guest wasm present"))
          (broken :guest :guest-wasm-absent
                  (str (count missing) " of " (count reqs)
                       " guest wasm the nbb hosts load are not in the tree ("
                       (str/join ", " (map :wasm missing))
                       "). They are build output, so a checkout or a release cut"
                       " from this tree cannot start those hosts until they are"
                       " compiled.")))))))

;; ---------------------------------------------------------------------------
;; the denominator
;; ---------------------------------------------------------------------------

(defn registry-counts [app-dir]
  (let [f (path/join app-dir "resources" "cloud-itonami-app.commands.edn")]
    (when-let [text (slurp-file f)]
      (try (:counts (edn/read-string text)) (catch :default _ nil)))))

;; ---------------------------------------------------------------------------
;; report
;; ---------------------------------------------------------------------------

(defn measure [app-dir plist-path]
  [(check-run-path app-dir plist-path)
   (check-cli app-dir)
   (check-guest app-dir)])

(defn- label [{:keys [status]}]
  (case status :ok "OK  " :broken "FAIL" :unmeasured "UNMEASURED"))

(defn report! [app-dir plist-path legs counts]
  (println "APP" app-dir)
  (println "PLIST" (or plist-path "-"))
  (if counts
    (println (str "REGISTRY routes=" (:routes counts)
                  " commands=" (:commands counts)
                  " human-only=" (:human-only counts)
                  " unauthenticated=" (:unauthenticated counts)))
    (println "REGISTRY unreadable"))
  (println (str "SCANNED\t" (count legs)))
  (println)
  (doseq [{:keys [leg status detail why id] :as row} legs]
    (println (str (label row) "\t" (name leg)
                  (when id (str "\t" (name id)))
                  "\t" (or detail why)))))

(defn findings-report!
  "FINDING<TAB>severity<TAB>key<TAB>detail — the orgs-detector protocol.

  The key carries the leg, so a `run-path` finding and a `cli` finding are two
  rows that age independently. Folding them under one key would let a fixed
  resident mark a still-closed CLI as resolved."
  [app-dir legs]
  (println (str "SCANNED\t" (count legs)))
  (doseq [{:keys [leg status id detail why]} legs]
    (case status
      :broken
      (println (str "FINDING\tfail\t" (name leg) ":" (name id) "\t"
                    (path/basename app-dir) " — " detail))
      :unmeasured
      (println (str "FINDING\tunmeasured\t" (name leg) ":unmeasured\t"
                    (path/basename app-dir) " — " why))
      nil)))

;; ---------------------------------------------------------------------------
;; self-test — both directions of each distinction
;; ---------------------------------------------------------------------------

(defn- write! [p text]
  (fs/mkdirSync (path/dirname p) #js {:recursive true})
  (fs/writeFileSync p text))

(defn- fixture!
  "A tree with the given deps.edn aliases, bin/itonami body and wasm presence."
  [root {:keys [aliases cli-body wasm?]}]
  (write! (path/join root "deps.edn")
          (str "{:deps {} :aliases {"
               (str/join " " (map #(str % " {:main-opts []}") aliases))
               "}}"))
  (write! (path/join root "bin" "itonami") (str "#!/usr/bin/env nbb\n" cli-body "\n"))
  (write! (path/join root "bin" "host")
          "#!/usr/bin/env nbb\n(guest/load-guest d \"target/amu/server_main.wasm\")\n")
  ;; Only presence is checked, so the fixture's bytes are a marker, not a module.
  (when wasm? (write! (path/join root "target" "amu" "server_main.wasm") "fixture"))
  root)

(defn- plist! [p command]
  (write! p (str "<plist><dict><key>ProgramArguments</key><array>"
                 "<string>/bin/zsh</string><string>-lc</string>"
                 "<string>cd /x &amp;&amp; exec " command "</string>"
                 "</array></dict></plist>"))
  p)

(defn self-test []
  (let [tmp (path/join (or (aget js/process.env "TMPDIR") "/tmp")
                       (str "itonami-run-path-" (.getTime (js/Date.))))
        ;; a tree that can run what the plist names
        good (fixture! (path/join tmp "good")
                       {:aliases [":server"] :cli-body "(println :dispatch)"
                        :wasm? true})
        ;; the tree main actually has today
        bad (fixture! (path/join tmp "bad")
                      {:aliases [":test"]
                       :cli-body (str "(println \"" cli-closed-marker ".\")")
                       :wasm? false})
        p-clj (plist! (path/join tmp "clj.plist") "/opt/homebrew/bin/clojure -M:server")
        p-nbb (plist! (path/join tmp "nbb.plist") "nbb --classpath bin bin/host")
        p-odd (plist! (path/join tmp "odd.plist") "/usr/bin/true")
        run (fn [dir plist] (into {} (map (juxt :leg identity)) (measure dir plist)))
        g (run good p-clj)
        b (run bad p-clj)
        gn (run good p-nbb)
        odd (run good p-odd)
        checks
        [["a declared alias passes run-path" (= :ok (:status (:run-path g)))]
         ["a deleted alias fails run-path" (= :broken (:status (:run-path b)))]
         ["the deleted-alias failure is named"
          (= :resident-launch-alias-absent (:id (:run-path b)))]
         ["an nbb script the tree has passes" (= :ok (:status (:run-path gn)))]
         ["an unrecognized run command is unmeasured, not ok"
          (= :unmeasured (:status (:run-path odd)))]
         ["an absent plist is unmeasured, not ok"
          (= :unmeasured (:status (check-run-path good (path/join tmp "none.plist"))))]
         ["a dispatching CLI passes" (= :ok (:status (:cli g)))]
         ["a closed CLI fails" (= :broken (:status (:cli b)))]
         ["present guest wasm passes" (= :ok (:status (:guest g)))]
         ["absent guest wasm fails" (= :broken (:status (:guest b)))]
         ["unmeasured never reads as ok"
          (not-any? #(= :ok (:status %))
                    (filter #(= :unmeasured (:status %))
                            (concat (vals g) (vals b) (vals odd))))]]]
    (doseq [[name pass?] checks]
      (println (if pass? "PASS" "FAIL") name))
    (fs/rmSync tmp #js {:recursive true :force true})
    (if (every? second checks) 0 1)))

;; ---------------------------------------------------------------------------

(defn -main []
  (if (switch? "--self-test")
    (js/process.exit (self-test))
    (let [root (or (flag "--root") ".")
          app (or (flag "--app")
                  (path/join root "orgs" "cloud-itonami" "cloud-itonami-app"))
          plist (or (flag "--plist") default-plist)]
      (if-not (exists? (path/join app "deps.edn"))
        (do (println "REFUSED could-not-measure: no deps.edn at" app)
            (println "  pass --app <checkout>; an app that is not here is not an"
                     "app that is fine")
            (js/process.exit 2))
        (let [legs (measure app plist)
              counts (registry-counts app)]
          (if (switch? "--findings")
            (findings-report! app legs)
            (report! app plist legs counts))
          (let [broken (filter #(= :broken (:status %)) legs)
                unmeasured (filter #(= :unmeasured (:status %)) legs)]
            (println)
            (println "FINDINGS" (count broken) "UNMEASURED" (count unmeasured))
            (cond
              (seq broken) (js/process.exit 1)
              (seq unmeasured) (js/process.exit 2)
              :else (js/process.exit 0))))))))

(-main)

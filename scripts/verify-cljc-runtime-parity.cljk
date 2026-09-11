(ns verify-cljc-runtime-parity
  "A `.cljc` file is a claim: this code runs identically on the JVM and on
  ClojureScript. Nothing enforces that claim by itself -- `.cljc` just means
  `clojure.core/read` sees `#?(:clj ...)` / `#?(:cljs ...)` branches and a
  plain top-level form runs on whichever host reads the file. A repo can
  ship a `.cljc` library, pass `clojure -M:test` forever, and be silently
  wrong on ClojureScript in the one place JVM and JS numerics disagree:
  32-bit bitwise arithmetic.

  This happened eight times in two days in this workspace while it was
  shipping 26 protocol codecs (`org-opcfoundation-ua`, `org-iso-8583`,
  `org-ietf-diameter`, `org-can-cia-canopen`, `org-ethercat`,
  `com-profibus-profinet`, `org-amqp`, `org-lora-alliance-lorawan`), and every
  one of the eight was caught only because someone actually ran the suite
  on both runtimes and diffed the results -- never by reading the code,
  never by the JVM suite alone. `org-modbus`'s own README names the trap
  by name. The second wave of repos walked into it anyway, days later.
  Prose did not prevent it; that is the reason this script exists.

  ## Two signals, and they are not the same kind of evidence

  Signal A (structural, `error`): a `.cljc` repo with *no ClojureScript
  verification path at all* -- no script, no test target, nothing that
  would have caught any of the eight bugs above even by accident. This is
  the strong, unambiguous, actionable finding: the fix is always the same
  shape (add a cljs run of the same suite) and doing that is what caught
  every one of the eight real bugs. A repo can have this and still be
  bug-free -- the finding is `the claim of portability is untested`, not
  `the code is wrong`.

  Signal B (pattern match, `warn`): specific constructs known to behave
  differently on the two runtimes -- `(int <char-or-string>)`, `(mapv int
  ...)`/`(map int ...)` over a string, `.getBytes` with no explicit
  charset, and unnormalized 32-bit `bit-or`/`bit-shift-left` composition of
  four bytes. This WILL false-positive: `(int x)` on a number is fine, a
  docstring that quotes the very idiom it warns against as a negative
  example contains the string `(int \\A)` without being a bug (this
  script's own test run found exactly that in `org-amqp`'s
  `frame.cljc`, in the sentence explaining why the file does NOT use it),
  and a signed 32-bit composition can be deliberate. Signal B is not
  presented as a defect list. It is presented as `read these lines`.

  ## What `has a ClojureScript verification path` means here

  A repo counts as verified if ANY of:
    - some `.cljs` file under `src/`, `test/`, `tests/`, `scripts/` or
      `script/` either (a) has `cljs` in its filename stem (excluding pure
      `gen-*` shadow-cljs.edn generators, which configure a build but do
      not run one) or (b) requires/report-hooks `cljs.test` or calls
      `run-tests`/`run-all-tests` -- covers `scripts/verify-cljs.cljs`
      (org-modbus, org-dnp3, org-amqp, org-lora-alliance-lorawan,
      org-can-cia-canopen, org-ethercat, com-profibus-profinet,
      org-ietf-diameter), `scripts/test-cljs.cljs`, `test/cljs_smoke.cljs`,
      `scripts/cljs-boundary-check.cljs`, and unnamed-but-content-evident
      runners like `chain/run-tests.cljs`
    - `package.json` has a `scripts` entry whose command mentions both
      (`shadow-cljs`|`nbb`) and `test` -- covers repos where the runner is
      named generically and shadow-cljs itself compiles+runs the suite
    - `shadow-cljs.edn` (or a file `shadow-cljs.edn`-shaped) declares a
      `:node-test` target

  This is deliberately loose (OR of several independent signals) because
  this workspace has no single naming convention for the runner -- proven
  by grepping the checked-out tree, which turned up at least 6 different
  naming shapes for the same 'run the suite under nbb' intent. A tighter
  match (one filename) would have false-negatived most of the known-good
  controls this script was built against.

  ## Rules this run obeys (see CLAUDE.md)

  A check that could not run does not return the value of a check that
  ran and found nothing. Unreadable/absent/not-checked-out repos are
  `UNVERIFIED`, never clean -- this superproject is a cone-mode sparse
  checkout and most west projects are simply not present on disk; `ls`
  will not show them, and their absence is not evidence of anything.
  `SCANNED<TAB>n` with n=0 is refused (exit 2), not printed as clean.
  Exit codes: 0 = scanned something and found nothing, 1 = findings
  (error and/or warn), 2 = could not measure at all.

  ## What this cannot answer

  This walks the repository root non-recursively (which is where
  `run-tests.cljs` lives in kotoba-sema and kotoba-native), plus
  `src/`, `test/`, `tests/`, `scripts/`, `script/` under
  each checked-out repo (plus root-level `package.json`/`nbb.edn`/
  `shadow-cljs.edn`) -- not the whole tree -- to keep a ~4300-repo sparse
  checkout tractable. A verification runner living somewhere else (repo
  root, a nonstandard directory) will not be found and the repo will be
  reported UNVERIFIED-shaped-as-a-finding when it may not deserve to be.
  A repo that is not checked out on this machine is not examined at all
  and is counted separately, never folded into 'clean'. Signal B's
  bit-composition heuristic is a line-window regex, not a parser -- it can
  miss compositions split unusually across lines and can flag deliberately
  signed ones; it is offered as `warn` for that reason, and this script
  does not claim to know which flagged lines are real bugs."
  (:require ["fs" :as fs] ["path" :as p] [clojure.string :as str]))

(def argv (vec (drop 2 (.-argv js/process))))
(defn flag [n] (some #{n} argv))
(defn opt [n d] (let [i (.indexOf argv n)] (if (neg? i) d (get argv (inc i) d))))
(def root (opt "--root" "."))
(def findings? (flag "--findings"))
(def json? (flag "--json"))
(def max-findings 400)

(defn ls [d] (try (vec (.readdirSync fs d)) (catch :default _ ::error)))
(defn dir? [f] (try (.isDirectory (.statSync fs f)) (catch :default _ false)))
(defn file? [f] (try (.isFile (.statSync fs f)) (catch :default _ false)))
(defn slurp* [f] (try (.readFileSync fs f "utf8") (catch :default e [::error (.-message e)])))
(defn ok? [x] (not (and (vector? x) (= ::error (first x)))))

;; --- west registry -----------------------------------------------------

(defn west-paths []
  (let [y (slurp* (p/join root "manifest" "west.yml"))]
    (when (ok? y)
      (into #{} (map second) (re-seq #"(?m)^\s*path:\s*(\S+)\s*$" y)))))

;; --- bounded, targeted walk ---------------------------------------------
;; Full-tree walk of ~4300 checked-out repos (node_modules and all) is not
;; tractable. The evidence this script needs -- source, tests, and the
;; verification-runner scripts -- lives under a small, fixed set of
;; top-level directory names by convention across this workspace. Walk
;; only those, recursively, with the same vendor/build skip-list the other
;; verify-*.cljs scripts in this repo use.

(def skip-dir
  #"/(node_modules|\.git|\.shadow-cljs|\.cache|target|dist|build|out|coverage|vendor|_archive|_working)(/|$)")

(def target-subdirs ["src" "test" "tests" "scripts" "script"])
(def max-files-per-repo 1500)

(defn walk [d depth acc]
  (if (or (> depth 8) (re-find skip-dir (str d "/")) (>= (count acc) max-files-per-repo))
    acc
    (let [entries (ls d)]
      (if (= entries ::error)
        acc
        (reduce (fn [a e]
                  (if (>= (count a) max-files-per-repo)
                    a
                    (let [f (p/join d e)]
                      (cond
                        (dir? f) (walk f (inc depth) a)
                        :else (conj a f)))))
                acc entries)))))

(defn root-level-files
  "Files sitting directly in the repository root, without descending.

  The subdirectory walk below misses these, and one of them is a runner this
  workspace actually uses: `kotoba-sema` and `kotoba-native` both keep their
  ClojureScript suite at `<repo>/run-tests.cljs`, and the sibling detector
  `verify-cljs-runner-completeness.cljs` reads it as the runner it is. Before
  this, those two repos were reported as having NO ClojureScript verification
  path while their nbb suites were green -- kotoba-sema 356 tests / 1371
  assertions, kotoba-native 24 / 37, both measured 2026-09-08.

  A false `error` on the two repos furthest along the migration is worse than
  a missed finding elsewhere: this detector's whole job is to say where the
  ClojureScript half has never been run, and it was saying it about halves
  that run.

  No recursion, so a repo whose root holds a large generated tree costs one
  `ls` here, not a walk."
  [abs]
  (let [entries (ls abs)]
    (if (= entries ::error)
      []
      (into [] (comp (map #(p/join abs %)) (remove dir?)) entries))))

(defn repo-files [abs]
  (reduce (fn [acc sub]
            (let [d (p/join abs sub)]
              (if (dir? d) (walk d 0 acc) acc)))
          (root-level-files abs) target-subdirs))

;; --- Signal A: cljs verification path -----------------------------------

(def cljs-runner-content-re #"(?i)(cljs\.test|run-all-tests|run-tests)")
(def gen-only-re #"(?i)^gen[-_]")
(def shadow-node-test-re #":node-test\b")
(def pkg-script-cljs-re #"(?i)((shadow-cljs|nbb).{0,80}\btest\b|\btest\b.{0,80}(shadow-cljs|nbb))")

(defn cljs-stem [path]
  (-> path p/basename (str/replace #"\.cljs$" "") str/lower-case))

(defn direct-cljs-runner? [files]
  (boolean
    (some (fn [f]
            (and (str/ends-with? f ".cljs")
                 (let [stem (cljs-stem f)]
                   (or (and (str/includes? stem "cljs") (not (re-find gen-only-re stem)))
                       (let [c (slurp* f)]
                         (and (ok? c) (re-find cljs-runner-content-re c)))))))
          files)))

(defn package-json-cljs-test? [abs]
  (let [c (slurp* (p/join abs "package.json"))]
    (and (ok? c)
         (try
           (let [scripts (some-> (js/JSON.parse c) .-scripts)]
             (boolean
               (when scripts
                 (some #(re-find pkg-script-cljs-re (str %))
                       (js->clj (js/Object.values scripts))))))
           (catch :default _ false)))))

(defn shadow-cljs-node-test? [abs]
  (let [c (slurp* (p/join abs "shadow-cljs.edn"))]
    (and (ok? c) (boolean (re-find shadow-node-test-re c)))))

(defn has-cljs-verification? [abs files]
  (or (direct-cljs-runner? files)
      (package-json-cljs-test? abs)
      (shadow-cljs-node-test? abs)))

;; --- Signal B: risky constructs, .cljc only -----------------------------

(def char-int-re #"\(int\s+\\\S")
(def mapv-int-re #"\(\s*(?:mapv|map)\s+int\b")
(def getbytes-re #"\(\.getBytes\s+[A-Za-z0-9_.\-]+\s*\)")
(def mod64-re #"(?i)\bmod\b[^\)]{0,50}(18446744073709551616|0x1_?0000_?0000_?0000_?0000|0xFFFFFFFFFFFFFFFF)")
(def shift-left-re #"bit-shift-left")
(def bitor-re #"bit-or\b")
(def normalized-re #"(?i)(unsigned-bit-shift-right|0x100000000|0xffffffff|0xFFFFFFFF|neg\?\s)")

(defn line-findings
  "Simple single-line regex matches -> [{:line :text}]."
  [lines re]
  (keep-indexed (fn [i l] (when (re-find re l) {:line (inc i) :text (str/trim l)})) lines))

(defn bitcompose-findings
  "Windowed heuristic: a `bit-or` line whose surrounding lines contain >= 3
  `bit-shift-left` (the shape of a 4-byte -> 32-bit fold) is flagged unless
  the same window also contains a recognized unsigned-normalization idiom.
  Advances past a flagged/skipped window instead of re-scanning it line by
  line, so one composition is not reported 3-4 times."
  [lines]
  (loop [i 0 acc []]
    (if (>= i (count lines))
      acc
      (let [l (nth lines i)]
        (if (re-find bitor-re l)
          (let [lo (max 0 (- i 1))
                hi (min (count lines) (+ i 9))
                window (str/join "\n" (subvec (vec lines) lo hi))
                shifts (count (re-seq shift-left-re window))]
            (if (and (>= shifts 3) (not (re-find normalized-re window)))
              (recur hi (conj acc {:line (inc i) :text (str/trim l)}))
              (recur (inc i) acc)))
          (recur (inc i) acc))))))

(defn signal-b-for-file [text]
  (let [lines (str/split-lines text)]
    {:char-int (line-findings lines char-int-re)
     :mapv-int (line-findings lines mapv-int-re)
     :getbytes (line-findings lines getbytes-re)
     :mod64 (line-findings lines mod64-re)
     :bitcompose (bitcompose-findings lines)}))

;; --- per-repo scan --------------------------------------------------------

(defn scan-repo [rel abs]
  (let [files (repo-files abs)
        cljc-files (filterv #(str/ends-with? % ".cljc") files)]
    (if (empty? cljc-files)
      {:repo rel :status :not-portable-claiming}
      (let [cljs-ok? (has-cljs-verification? abs files)
            reads (mapv (fn [f] [f (slurp* f)]) cljc-files)
            unread (count (remove (fn [[_ c]] (ok? c)) reads))
            sigb (reduce (fn [m [f c]]
                           (if (ok? c)
                             (let [{:keys [char-int mapv-int getbytes mod64 bitcompose]} (signal-b-for-file c)]
                               (-> m
                                   (update :char-int into (map #(assoc % :file f) char-int))
                                   (update :mapv-int into (map #(assoc % :file f) mapv-int))
                                   (update :getbytes into (map #(assoc % :file f) getbytes))
                                   (update :mod64 into (map #(assoc % :file f) mod64))
                                   (update :bitcompose into (map #(assoc % :file f) bitcompose))))
                             m))
                         {:char-int [] :mapv-int [] :getbytes [] :mod64 [] :bitcompose []}
                         reads)]
        {:repo rel
         :status :portable-claiming
         ;; Does this repo actually manipulate bits/bytes? The eight measured
         ;; JVM-vs-cljs incidents were ALL in byte code -- ToInt32 coercion,
         ;; (int <char>) returning 0, 64-bit precision loss. A .cljc repo with
         ;; no cljs runner is a real gap either way, but for a repo that never
         ;; touches a bit the gap is theoretical, and burying 321 repos that
         ;; can actually carry the bug inside 2180 that mostly cannot is how a
         ;; detector gets ignored. Computed from the reads already in hand.
         :byte-work? (boolean
                      (some (fn [[_ c]]
                              (and (ok? c)
                                   (re-find #"bit-and|bit-or|bit-xor|bit-shift-left|unsigned-bit-shift-right" c)))
                            reads))
         :cljc-files (count cljc-files)
         :unread-cljc-files unread
         :cljs-verified? cljs-ok?
         :signal-b sigb
         :signal-b-count (reduce + (map count (vals sigb)))}))))

;; --- main ------------------------------------------------------------------

(defn cap [v] (if (> (count v) max-findings) (subvec (vec v) 0 max-findings) v))

(defn -main []
  (let [registered (west-paths)
        orgs-dir (p/join root "orgs")]
    (when-not registered
      (println "REFUSED\tno manifest/west.yml under" root
                "-- cannot tell a registered checkout from a stale/unregistered one")
      (.exit js/process 2))
    (when-not (dir? orgs-dir)
      (println "REFUSED\tno orgs/ under" root "-- nothing to scan")
      (.exit js/process 2))
    (let [org-registered (into #{} (filter #(str/starts-with? % "orgs/")) registered)
          org-entries (ls orgs-dir)
          checked-out (if (= org-entries ::error)
                        []
                        (for [o org-entries :when (dir? (p/join orgs-dir o))
                              r (let [e (ls (p/join orgs-dir o))] (if (= e ::error) [] e))
                              :let [rel (str "orgs/" o "/" r)]
                              :when (and (contains? org-registered rel) (dir? (p/join root rel)))]
                          rel))
          unverified (- (count org-registered) (count checked-out))
          scan-results (mapv (fn [rel] (scan-repo rel (p/join root rel))) checked-out)
          scanned (count scan-results)]
      (when (zero? scanned)
        (println "REFUSED\tzero registered orgs/ checkouts were present on disk to scan"
                  "-- a run that examines nothing has not measured the rule")
        (.exit js/process 2))
      (let [portable (filterv #(= :portable-claiming (:status %)) scan-results)
            not-portable (filterv #(= :not-portable-claiming (:status %)) scan-results)]
        (when (zero? (count portable))
          (println "REFUSED\tzero .cljc-claiming repos found among" scanned
                    "checked-out repos -- runtime-parity has nothing to measure against")
          (.exit js/process 2))
        (let [no-cljs (filterv #(not (:cljs-verified? %)) portable)
              no-cljs-byte (filterv :byte-work? no-cljs)
              no-cljs-other (filterv #(not (:byte-work? %)) no-cljs)
              has-cljs (filterv :cljs-verified? portable)
              signal-b-repos (filterv #(pos? (:signal-b-count %)) portable)
              signal-b-total (reduce + (map :signal-b-count portable))
              unread-total (reduce + (map :unread-cljc-files portable))
              findings-a
              (into
               (mapv (fn [{:keys [repo cljc-files]}]
                       (str "FINDING\terror\tno-cljs-verification-path-with-byte-work:" repo "\t"
                            cljc-files " .cljc file(s) doing bit/byte operations, and "
                            "no scripts/*cljs*.cljs runner, no package.json test script "
                            "mentioning nbb/shadow-cljs+test, no shadow-cljs.edn :node-test target -- "
                            "this repo manipulates bytes and its ClojureScript half has never "
                            "been run. Every one of the eight measured signedness incidents "
                            "looked exactly like this before it was found."))
                     no-cljs-byte)
               (mapv (fn [{:keys [repo cljc-files]}]
                       (str "FINDING\tinfo\tno-cljs-verification-path:" repo "\t"
                            cljc-files " .cljc file(s), no cljs runner, but no bit/byte "
                            "operations found -- the portability claim is still unverified, "
                            "but this repo cannot carry the signedness bug class."))
                     no-cljs-other))
              b-line
              (fn [repo kind {:keys [file line text]}]
                (str "FINDING\twarn\t" kind ":" repo "\t"
                     (subs file (inc (count (p/join root repo)))) ":" line "\t"
                     (subs text 0 (min 160 (count text)))))
              findings-b
              (vec
                (for [{:keys [repo signal-b]} signal-b-repos
                      [kind key] [["risky-int-of-char-or-string" :char-int]
                                  ["risky-mapv-int-over-string" :mapv-int]
                                  ["getbytes-no-charset" :getbytes]
                                  ["mod-2pow64-conversion" :mod64]
                                  ["unnormalized-32bit-bitor-composition" :bitcompose]]
                      row (get signal-b key)]
                  (b-line repo kind row)))]
        (if json?
          (println (.stringify js/JSON
                                (clj->js {:scanned scanned
                                          :registered-under-orgs (count org-registered)
                                          :checked-out (count checked-out)
                                          :unverified-not-checked-out unverified
                                          :not-portable-claiming (count not-portable)
                                          :portable-claiming (count portable)
                                          :signal-a-no-cljs-verification (count no-cljs)
                                          :signal-a-has-cljs-verification (count has-cljs)
                                          :signal-b-repos-with-hits (count signal-b-repos)
                                          :signal-b-total-hits signal-b-total
                                          :unread-cljc-files unread-total
                                          :signal-a-no-cljs-byte-work (count no-cljs-byte)
                                          :signal-a-error-repos (mapv :repo no-cljs-byte)
                                          :signal-a-info-repos (mapv :repo no-cljs-other)})
                                nil 2))
          (do
            (when findings?
              (doseq [l (cap findings-a)] (println l))
              (when (> (count findings-a) max-findings)
                (println (str "... " (- (count findings-a) max-findings)
                              " more error finding(s) not shown (bounded output)")))
              (doseq [l (cap findings-b)] (println l))
              (when (> (count findings-b) max-findings)
                (println (str "... " (- (count findings-b) max-findings)
                              " more warn finding(s) not shown (bounded output)"))))
            (println (str "SCANNED\t" scanned
                          "\tregistered orgs/ checkouts present on disk and readable, of "
                          (count org-registered) " registered under orgs/ ("
                          unverified " not checked out -> UNVERIFIED, not scanned)"))
            (println (str "portable-claiming(.cljc)=" (count portable)
                          " not-portable=" (count not-portable)
                          " unread-cljc-files=" unread-total))
            (println (str "signal-a: no-cljs-verification-path=" (count no-cljs)
                          " (of which DOING BYTE WORK=" (count no-cljs-byte)
                          " <- the actionable set; other=" (count no-cljs-other) ")"
                          " has-cljs-verification-path=" (count has-cljs)))
            (println (str "signal-b (pattern match, NOT presented as defects -- read the lines): "
                          "repos-with-hits=" (count signal-b-repos)
                          " total-hits=" signal-b-total))
            (when (pos? unverified)
              (println (str "UNVERIFIED\t" unverified
                            "\tregistered checkouts not present on disk (sparse checkout) -- "
                            "not examined, not reported clean")))))
        (.exit js/process (if (or (seq no-cljs) (seq signal-b-repos)) 1 0)))))))

(-main)

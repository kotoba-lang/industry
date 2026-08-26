#!/usr/bin/env nbb
;; verify-grammar-lowering-drift.cljs -- forms the grammar authority declares
;; admitted, against what the compiler can actually lower.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-grammar-lowering-drift.cljs \
;;     [--findings] [--root <dir>]
;;
;; ## What it is about
;;
;; `kotoba-lang/kotoba-lang`'s `lang/guest-grammar.edn` is the authority for
;; the source surface (root ADR-2607279200, Delivery #1: "what
;; compiler/frontend.cljc accepts and what the authority admits are different
;; things, and the drift between them is something to close with a machine
;; check"). Measured 2026-08-24, nine forms it declares for all three backends
;; are rejected by the compiler with "operation has no admitted lowering".
;;
;; Not a fleet gate: the authority lives in one repository and the compiler in
;; another, and the fleet ships one repo's tree. Same shape as
;; verify-wasm-toolchain-pin.
;;
;; ## The floor that makes the number trustworthy
;;
;; A probe is a program that uses the form. If it is written wrong, the
;; compiler rejects it and a naive detector calls that drift. That is not
;; hypothetical -- the first hand pass at this reported 15 rejections and at
;; least five were the probe's fault: `conj`, `take`, `peek` and `pop` failed
;; with "heterogeneous vector types must be a bounded vector", which rejects
;; the TYPE SHAPE and says nothing about the operation.
;;
;; So every probe is PAIRED with a control: the same program with the form
;; under test replaced by one already known to lower. Then
;;
;;   control lowers, probe says "no admitted lowering"  ->  DRIFT
;;   control lowers, probe rejected for another reason  ->  reported, not drift
;;   control lowers, probe lowers                       ->  the form works
;;   control does NOT lower                             ->  the probe's shape is
;;                                                          wrong; this detector
;;                                                          cannot answer, and
;;                                                          says so
;;
;; ## Coverage is printed, always
;;
;; The corpus is hand-written, so it covers a fraction of the declared surface.
;; "9 drift" must never be read as "9 of 73". Every run prints how many
;; declared forms were probed at all, and an unprobed form is UNMEASURED, not
;; clean.
;;
;; ## exit codes
;;
;;   0  every probed form either lowers or was rejected for a stated reason
;;   1  at least one declared form has no lowering
;;   2  COULD NOT ANSWER -- authority or compiler unreachable, the classpath
;;      did not resolve, or no control lowered (which would mean the harness
;;      itself is broken, not the compiler)

(require '["node:fs" :as fs]
         '["node:path" :as path]
         '["node:child_process" :as cp]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def argv (vec (drop 2 js/process.argv)))
(defn- flag? [f] (some #{f} argv))
(defn- opt [f] (second (drop-while #(not= f %) argv)))
(def findings? (flag? "--findings"))
(def root (or (opt "--root") (.cwd js/process)))

(def authority-file
  (path/join root "orgs" "kotoba-lang" "kotoba-lang" "lang" "guest-grammar.edn"))
(def amu-dir (path/join root "orgs" "kotoba-lang" "amu"))

(defn- finding! [severity k detail]
  (println (str "FINDING\t" severity "\t" k "\t" detail)))

(defn- cannot-answer! [why]
  (println (str "SCANNED\t0\t" why))
  (println (str "Refusing to report a pass: " why))
  (js/process.exit 2))

;; ---------------------------------------------------------------------------
;; The corpus. Each entry is [form probe-source control-source].
;;
;; The control differs from the probe in ONE operation, and that operation is
;; one this detector also probes and expects to lower -- so a control that
;; stops lowering shows up as its own finding rather than silently disarming
;; the probes that lean on it.
;;
;; Two rules, both learned by breaking them:
;;
;;   ONE form, not two. The first `set-literal` entry read
;;   `(if (contains? #{1 2} 1) 1 0)` against `(if (= 1 1) 1 0)`. That changes
;;   the set literal AND `contains?`, so its rejection could belong to either,
;;   and it was reported as drift in the set literal. A keyword set lowers.
;;
;;   The SAME value types on both sides. `#{1 2}` is rejected with
;;   `expected keyword, got i64` before any operation on it is considered, so
;;   a probe built on one measures the element type and calls the answer
;;   something else.
;; ---------------------------------------------------------------------------

(def corpus
  [["count"
    "(defn n [v :vector-i64] :i64 (count v)) (defn main [] :i64 (n [1 2]))"
    "(defn n [v :vector-i64] :i64 (nth v 0)) (defn main [] :i64 (n [1 2]))"]
   ["contains?"
    "(defn main [] :i64 (let [s #{:a :b}] (if (contains? s :a) 1 0)))"
    "(defn main [] :i64 (let [s #{:a :b}] (if (= 1 1) 1 0)))"]
   ["keys"
    "(defn f [m :map] :i64 (nth (keys m) 0)) (defn main [] :i64 (f {:a 1}))"
    "(defn f [m :map] :i64 (get m :a 0)) (defn main [] :i64 (f {:a 1}))"]
   ["vals"
    "(defn f [m :map] :i64 (nth (vals m) 0)) (defn main [] :i64 (f {:a 1}))"
    "(defn f [m :map] :i64 (get m :a 0)) (defn main [] :i64 (f {:a 1}))"]
   ["dissoc"
    "(defn f [m :map] :i64 (get (dissoc m :a) :a 0)) (defn main [] :i64 (f {:a 1}))"
    "(defn f [m :map] :i64 (get (assoc m :a 2) :a 0)) (defn main [] :i64 (f {:a 1}))"]
   ["str"
    "(defn f [a :string b :string] :string (str a b)) (defn main [] :i64 (string-byte-length (f \"a\" \"b\")))"
    "(defn f [a :string b :string] :string (string-concat a b)) (defn main [] :i64 (string-byte-length (f \"a\" \"b\")))"]
   ;; `string=` is declared in :predicates and has no lowering in any
   ;; position -- test, return, let-bound or literal-vs-literal. The builtin
   ;; is spelled `string=?`, and `=` on strings is rejected with a message
   ;; that names it. Found 2026-08-26 writing kotoba/smtp/protocol_response;
   ;; the control is the spelling that does lower, so this entry reports the
   ;; name and not the operation.
   ["string="
    "(defn f [a :string b :string] :bool (string= a b)) (defn main [] :i64 (if (f \"a\" \"a\") 1 0))"
    "(defn f [a :string b :string] :bool (string=? a b)) (defn main [] :i64 (if (f \"a\" \"a\") 1 0))"]
   ["subs"
    "(defn f [a :string] :string (subs a 0 1)) (defn main [] :i64 (string-byte-length (f \"ab\")))"
    "(defn f [a :string] :string (string-substring a 0 1)) (defn main [] :i64 (string-byte-length (f \"ab\")))"]
   ["assoc"
    "(defn f [m :map] :i64 (get (assoc m :b 2) :b 0)) (defn main [] :i64 (f {:a 1}))"
    "(defn f [m :map] :i64 (get m :a 0)) (defn main [] :i64 (f {:a 1}))"]
   ["get"
    "(defn f [m :map] :i64 (get m :a 0)) (defn main [] :i64 (f {:a 1}))"
    "(defn f [m :map] :i64 1) (defn main [] :i64 (f {:a 1}))"]
   ["nth"
    "(defn n [v :vector-i64] :i64 (nth v 0)) (defn main [] :i64 (n [1 2]))"
    "(defn n [v :vector-i64] :i64 1) (defn main [] :i64 (n [1 2]))"]
   ["cond"
    "(defn f [n :i64] :i64 (cond (= n 0) 1 :else 2)) (defn main [] :i64 (f 0))"
    "(defn f [n :i64] :i64 (if (= n 0) 1 2)) (defn main [] :i64 (f 0))"]
   ["case"
    "(defn f [n :i64] :i64 (case n 0 1 2)) (defn main [] :i64 (f 0))"
    "(defn f [n :i64] :i64 (if (= n 0) 1 2)) (defn main [] :i64 (f 0))"]
   ["and"
    "(defn f [a :bool b :bool] :bool (and a b)) (defn main [] :i64 (if (f true true) 1 0))"
    "(defn f [a :bool b :bool] :bool (if a b false)) (defn main [] :i64 (if (f true true) 1 0))"]
   ["->"
    "(defn f [a :string] :i64 (-> a string-byte-length (+ 1))) (defn main [] :i64 (f \"ab\"))"
    "(defn f [a :string] :i64 (+ (string-byte-length a) 1)) (defn main [] :i64 (f \"ab\"))"]
   ["map"
    "(defn main [] :i64 (reduce + 0 (map (fn [x] (+ x 1)) [1 2])))"
    "(defn main [] :i64 (reduce + 0 [1 2]))"]
   ["filter"
    "(defn main [] :i64 (reduce + 0 (filter (fn [x] (> x 1)) [1 2])))"
    "(defn main [] :i64 (reduce + 0 [1 2]))"]
   ["reduce"
    "(defn main [] :i64 (reduce + 0 [1 2]))"
    "(defn main [] :i64 (+ 1 2))"]
   ["dotimes"
    "(defn main [] :i64 (do (dotimes [i 2] i) 1))"
    "(defn main [] :i64 (do 0 1))"]
   ["condp"
    "(defn f [n :i64] :i64 (condp = n 0 1 2)) (defn main [] :i64 (f 0))"
    "(defn f [n :i64] :i64 (if (= n 0) 1 2)) (defn main [] :i64 (f 0))"]
   ["as->"
    "(defn main [] :i64 (as-> 1 v (+ v 1)))"
    "(defn main [] :i64 (let [v 1] (+ v 1)))"]
   ["disj"
    "(defn main [] :i64 (let [s (disj #{:a :b} :a)] 1))"
    "(defn main [] :i64 (let [s #{:a :b}] 1))"]
   ;; The probe and its control differ in the SET LITERAL and nothing else.
   ;; The first version of this entry read `(if (contains? #{1 2} 1) 1 0)`
   ;; against `(if (= 1 1) 1 0)` -- two forms changed at once AND an element
   ;; type the surface does not take -- and reported `set-literal` as drift.
   ;; It is not: a keyword set lowers. `contains?` is what does not, and it
   ;; has its own entry above.
   ["set-literal"
    "(defn main [] :i64 (let [s #{:a :b}] 1))"
    "(defn main [] :i64 (let [s [:a :b]] 1))"]
   ["conj"
    "(defn f [v :vector-i64] :vector-i64 (conj v 2)) (defn main [] :i64 0)"
    "(defn f [v :vector-i64] :vector-i64 v) (defn main [] :i64 0)"]
   ["take"
    "(defn f [v :vector-i64] :vector-i64 (take 1 v)) (defn main [] :i64 0)"
    "(defn f [v :vector-i64] :vector-i64 v) (defn main [] :i64 0)"]
   ["peek"
    "(defn f [v :vector-i64] :i64 (peek v)) (defn main [] :i64 0)"
    "(defn f [v :vector-i64] :i64 (nth v 0)) (defn main [] :i64 0)"]])

;; ---------------------------------------------------------------------------

(defn- sh
  ([cmd args cwd] (sh cmd args cwd nil))
  ([cmd args cwd env-extra]
   (.spawnSync cp cmd (clj->js args)
               #js {:cwd cwd :encoding "utf8" :maxBuffer 33554432
                    :env (if env-extra
                           (js/Object.assign #js {} js/process.env (clj->js env-extra))
                           js/process.env)})))

(when-not (.existsSync fs authority-file)
  (cannot-answer! (str "the grammar authority is not checked out: " authority-file)))
(when-not (.existsSync fs amu-dir)
  (cannot-answer! (str "the compiler is not checked out: " amu-dir)))

(def declared
  (let [g (try (edn/read-string (str (.readFileSync fs authority-file "utf8")))
               (catch :default _ nil))]
    (when-not g (cannot-answer! "the grammar authority did not parse"))
    (into #{} (map name) (keys (into {} (:sugar g))))))

(when (empty? declared)
  (cannot-answer! "the grammar authority declares no sugar, which cannot be right"))

;; The compiler's classpath, resolved WITHOUT a JVM, the same way `bin/amu`
;; does it. Deriving it from `clojure -Spath` would make this detector need a
;; JDK to ask a question about a JDK-free path.
(def classpath
  (let [r (sh "nbb" ["--classpath" "src" "scripts/print-classpath.cljs"] amu-dir)]
    (when-not (zero? (or (.-status r) 1))
      (cannot-answer! (str "amu's dependency lock did not resolve: "
                           (str/trim (str (or (.-stderr r) ""))))))
    (let [dirs (remove str/blank? (str/split-lines (str (or (.-stdout r) ""))))]
      (when (empty? dirs)
        (cannot-answer! "amu's dependency lock resolved to nothing"))
      (str/join ":" (concat ["src"] dirs)))))

(def ^:private source-file
  "The probe source goes through a FILE, not a command-line argument: `nbb -e`
   treats a trailing argument as a script path to run, so passing the program
   that way made every control fail to lower. The first version of this
   detector did exactly that, and its own floor caught it -- it refused to
   report anything rather than call 20 harness errors 20 drifts."
  (path/join (or (.-TMPDIR js/process.env) "/tmp") "grammar-lowering-probe.kotoba"))

(defn- lower!
  "`:lowered`, or the compiler's rejection message."
  [source]
  (.writeFileSync fs source-file source)
  (let [expr (str "(require '[kotoba.sema :as sema] '[kotoba.kir :as ir] '[\"node:fs\" :as fs])"
                  "(let [src (str (.readFileSync fs (.-KOTOBA_PROBE_FILE js/process.env) \"utf8\"))]"
                  "  (try (do (ir/lower (sema/analyze src)) (println \"LOWERED\"))"
                  "       (catch :default e (println (str \"REJECTED \" (.-message e))))))")
        r (sh "nbb" ["--classpath" classpath "-e" expr] amu-dir
              {:KOTOBA_PROBE_FILE source-file})
        out (str (or (.-stdout r) "") (or (.-stderr r) ""))]
    (cond
      (str/includes? out "LOWERED") :lowered
      (str/includes? out "REJECTED")
      (str/trim (second (str/split (str/trim (re-find #"REJECTED.*" out)) #"REJECTED" 2)))
      :else (str "harness error: " (str/trim (str/join " " (take 3 (str/split-lines out))))))))

(def results
  (vec (for [[form probe control] corpus]
         (let [c (lower! control)
               p (if (= :lowered c) (lower! probe) :control-failed)]
           {:form form :control c :probe p}))))

(def controls-ok (count (filter #(= :lowered (:control %)) results)))

(when (zero? controls-ok)
  (cannot-answer! "no control program lowered, so this harness is broken, not the compiler"))

(def drift
  (filterv #(and (= :lowered (:control %))
                 (string? (:probe %))
                 (str/includes? (:probe %) "no admitted lowering"))
           results))

(def other-rejections
  (filterv #(and (= :lowered (:control %))
                 (string? (:probe %))
                 (not (str/includes? (:probe %) "no admitted lowering")))
           results))

(def shape-unverified (filterv #(not= :lowered (:control %)) results))

(println (str "SCANNED\t" (count corpus) "\tprobed forms of " (count declared)
              " declared in guest-grammar.edn; controls lowered " controls-ok "/" (count corpus)))
(println (str "COVERAGE\tprobed=" (count corpus) "\tdeclared=" (count declared)
              "\tunprobed=" (- (count declared) (count corpus))
              "\t-- an unprobed form is UNMEASURED, not clean"))

(when (seq shape-unverified)
  (doseq [{:keys [form control]} shape-unverified]
    (println (str "UNVERIFIED\t" form "\tthe control did not lower either, so the probe's shape is wrong: " control))))

(when findings?
  (doseq [{:keys [form probe]} (sort-by :form drift)]
    (finding! "fail" (str "grammar-lowering-drift:" form)
              (str "guest-grammar.edn declares `" form
                   "` admitted, and the compiler answers: " probe
                   ". The control for this probe lowered, so the shape is not the reason.")))
  (doseq [{:keys [form probe]} (sort-by :form other-rejections)]
    (finding! "warn" (str "grammar-rejects-with-a-reason:" form)
              (str "`" form "` is declared and is rejected, but for a stated reason rather than a missing lowering: " probe))))

(println (str "\n" (count drift) " declared form(s) have no lowering; "
              (count other-rejections) " rejected for a stated reason; "
              (count shape-unverified) " probe(s) unverified."))

(js/process.exit (if (seq drift) 1 0))

;; Generate one repo per definition from a single namespace, plus a facade that
;; re-exports them. The dependency edges come from the extractor, so each repo's
;; deps.edn names exactly the definitions its own body reaches.
(require '[clojure.string :as s])
(def fs (js/require "node:fs"))
(def path-mod (js/require "node:path"))
(def src-path (nth *command-line-args* 0))
(def graph-path (nth *command-line-args* 1))
(def out-dir (nth *command-line-args* 2))
;; family parameters: the namespace the pieces live under, and the repo prefix
(def target-ns (nth *command-line-args* 3))     ; e.g. "kotoba.string"
(def repo-prefix (nth *command-line-args* 4))   ; e.g. "string-"
(def facade-repo (nth *command-line-args* 5))   ; e.g. "string"
(def source-ns (nth *command-line-args* 6))     ; e.g. "kotoba.lang.text"
(def src (str (.readFileSync fs src-path "utf8")))
(def lines (remove s/blank? (s/split-lines (str (.readFileSync fs graph-path "utf8")))))

(def raw-defs
  (vec (for [l lines
             :let [[tag nm kind deps] (s/split l #"\t" 4)]
             :when (= "DEF" tag)]
         {:name nm :kind kind :deps (if (s/blank? deps) [] (s/split deps #","))})))
(def by-raw (into {} (map (juxt :name identity) raw-defs)))

;; An input with no DEF lines is not an empty family -- it is the wrong file.
;; Measured 2026-09-09: the scc output alone was passed here, and the generator
;; wrote 19 repos with zero dependencies each and reported success. Every one of
;; them would have failed to compile. Absent input must not return what a clean
;; input returns.
(when (empty? raw-defs)
  (println (str "REFUSING: no DEF lines in " graph-path
                " -- the generator needs the extractor output and the scc output"
                " concatenated, not the scc output alone."))
  (js/process.exit 2))
(def provides-of
  (into {} (for [l lines
                 :let [[tag nm ps] (s/split l #"\t" 3)]
                 :when (= "PROVIDES" tag)]
             [nm (s/split ps #",")])))
;; Components come from the SCC pass. A component with more than one member is a
;; cycle of mutually recursive definitions -- kotoba.lang.edn's reader has one,
;; read-form <-> read-sequence, and the source says so itself with a
;; (declare read-form). Two definitions that call each other cannot be two repos
;; with an acyclic dependency, so the unit is the cycle. Unison groups mutually
;; recursive definitions the same way.
(def components
  (vec (for [l lines
             :let [[tag lead members] (s/split l #"\t" 3)]
             :when (= "TOPO" tag)]
         {:lead lead :members (vec (s/split members #","))})))
(def member->lead (into {} (for [{:keys [lead members]} components, m members] [m lead])))
(def defs
  (vec (for [{:keys [lead members]} components
             :let [ds (->> members
                           (mapcat #(:deps (get by-raw %)))
                           (map member->lead)
                           (remove #(= % lead))
                           distinct sort vec)]]
         {:name lead
          :members members
          :kind (:kind (get by-raw lead))
          :deps ds})))


(def java-lang-auto-imports
  "The names Clojure hands every namespace whether it asked for them or not.

  Measured 2026-09-09 with (ns-imports (create-ns (gensym))) on Clojure
  1.12.0 rather than recalled: 96 of them. This is a fact about one HOST --
  ClojureScript has no such set -- so it belongs at the host boundary and
  not in the naming rule."
  #{"AbstractMethodError" "Appendable" "ArithmeticException" "ArrayIndexOutOfBoundsException" "ArrayStoreException" "AssertionError" "BigDecimal" "BigInteger" "Boolean" "Byte" "Callable" "CharSequence" "Character" "Class" "ClassCastException" "ClassCircularityError" "ClassFormatError" "ClassLoader" "ClassNotFoundException" "CloneNotSupportedException" "Cloneable" "Comparable" "Compiler" "Deprecated" "Double" "Enum" "EnumConstantNotPresentException" "Error" "Exception" "ExceptionInInitializerError" "Float" "IllegalAccessError" "IllegalAccessException" "IllegalArgumentException" "IllegalMonitorStateException" "IllegalStateException" "IllegalThreadStateException" "IncompatibleClassChangeError" "IndexOutOfBoundsException" "InheritableThreadLocal" "InstantiationError" "InstantiationException" "Integer" "InternalError" "InterruptedException" "Iterable" "LinkageError" "Long" "Math" "NegativeArraySizeException" "NoClassDefFoundError" "NoSuchFieldError" "NoSuchFieldException" "NoSuchMethodError" "NoSuchMethodException" "NullPointerException" "Number" "NumberFormatException" "Object" "OutOfMemoryError" "Override" "Package" "Process" "ProcessBuilder" "Readable" "Runnable" "Runtime" "RuntimeException" "RuntimePermission" "SecurityException" "SecurityManager" "Short" "StackOverflowError" "StackTraceElement" "StrictMath" "String" "StringBuffer" "StringBuilder" "StringIndexOutOfBoundsException" "SuppressWarnings" "System" "Thread" "Thread$State" "Thread$UncaughtExceptionHandler" "ThreadDeath" "ThreadGroup" "ThreadLocal" "Throwable" "TypeNotPresentException" "UnknownError" "UnsatisfiedLinkError" "UnsupportedClassVersionError" "UnsupportedOperationException" "VerifyError" "VirtualMachineError" "Void"})

(defn clojure-name
  "The name a protocol takes in the split.

  Measured 2026-09-09 against the actual libraries rather than guessed:
  clojure.core.protocols declares CollReduce, InternalReduce, Datafiable and
  Navigable; java.io and java.lang declare Closeable, Readable and Appendable.
  Neither prefixes an interface with I -- that is a C#/COM habit. So a leading
  I is dropped, which is the whole change: no new name is invented.

  There is no exception for names java.lang happens to occupy. IProcess
  becomes Process like every other protocol; the collision is a grant this
  namespace never asked for, and it is declined where it arises -- see
  java-lang-auto-imports and the (ns-unmap ...) the emitter writes. A rule
  that bends the NAME to fit one host puts that host in the identity."
  [n]
  (if (re-matches #"I[A-Z][A-Za-z0-9]*" n) (subs n 1) n))

(defn slug
  "A repo/namespace segment for a definition name.

  `?` and `->` are legal in a Clojure symbol and not in a repo name, so the
  predicate marker is dropped from the SEGMENT and kept on the function inside
  it: kotoba.string.blank still provides `blank?`.

  `*` is NOT dropped. `?` and `!` are markers that rarely have an unmarked
  twin, but `*` almost always does -- read-string and read-string* are two
  definitions, and dropping the marker would give them one repo. It becomes
  `-star`, and `slugs-distinct!` below refuses if any two names still collide."
  [n]
  (-> n
      (s/replace "->" "-to-")
      (s/replace ">=" "-gte")
      (s/replace "<=" "-lte")
      (s/replace ">" "-gt")
      (s/replace "<" "-lt")
      (s/replace "=" "-eq")
      (s/replace "*" "-star")
      (s/replace "?" "")
      (s/replace "!" "")
      ;; A protocol is named IFilesystem, and a repo may not be. CamelCase is
      ;; kebab-cased for the SEGMENT only -- the protocol keeps its own name
      ;; inside the file, because that name is the contract.
      (s/replace #"([a-z0-9])([A-Z])" "$1-$2")
      s/lower-case))

(defn slugs-legal!
  "Refuse a definition whose repo name GitHub will not accept.

  Measured 2026-09-09: kotoba.lang.lsp has pos< and pos>=, and `gh repo create`
  failed on both. The publish driver reported `create-failed` and `push-failed`
  and carried on, so the family went out ten repos of twelve and the facade
  would have named two repos that do not exist. A name the host will not take
  has to stop the split, not thin it."
  [names]
  (let [bad (remove #(re-matches #"[A-Za-z0-9._-]+" (slug %)) names)]
    (when (seq bad)
      (doseq [n bad] (println (str "ILLEGAL-REPO-NAME\t" n "\t" (slug n))))
      (println "REFUSING: GitHub takes only letters, digits, '-', '_' and '.' in a repository name.")
      (js/process.exit 2))))

(defn slugs-distinct!
  "Refuse if two definition names slug to the same repo. Dropping `?` and `!`
  is lossy, so this is the only thing standing between `foo` and `foo?` and a
  single repo holding whichever was generated last. It has never fired -- that
  is a measurement, not a reason to remove it."
  [names]
  (let [by (group-by slug names)
        bad (into (sorted-map) (filter (fn [[_ v]] (> (count v) 1))) by)]
    (when (seq bad)
      (doseq [[k v] bad] (println (str "COLLISION\t" k "\t" (s/join "," v))))
      (println "REFUSING: two definitions would share one repo.")
      (js/process.exit 2))))

;; the body text of each definition, taken from the source by the same
;; balanced scan the extractor used
(defn code-mask [t]
  (let [n (count t)]
    (loop [i 0, in-str? false, esc? false, in-cmt? false, out (transient [])]
      (if (>= i n) (persistent! out)
        (let [c (nth t i)]
          (cond
            in-cmt? (recur (inc i) false false (not= c \newline) (conj! out false))
            esc?    (recur (inc i) in-str? false false (conj! out false))
            in-str? (cond (= c \\) (recur (inc i) true true false (conj! out false))
                          (= c \") (recur (inc i) false false false (conj! out false))
                          :else    (recur (inc i) true false false (conj! out false)))
            (= c \") (recur (inc i) true false false (conj! out false))
            (= c \;) (recur (inc i) false false true (conj! out false))
            (and (= c \\) (< (inc i) n)) (recur (+ i 2) false false false (conj! (conj! out false) false))
            :else (recur (inc i) false false false (conj! out true))))))))
(def mask (code-mask src))
(defn code-at? [i] (get mask i false))
(def tops
  ;; a top-level reader conditional is a top-level form -- see extract.cljs
  (vec (for [i (range (count src))
             :when (and (code-at? i)
                        (or (zero? i) (= \newline (nth src (dec i))))
                        (or (= \( (nth src i))
                            (and (= \# (nth src i)) (= \? (get src (inc i))) (= \( (get src (+ i 2))))))]
         i)))
(defn form-end [start]
  (loop [i (inc (if (= \# (nth src start)) (+ start 2) start)), depth 1]
    (cond (>= i (count src)) (count src)
          (not (code-at? i)) (recur (inc i) depth)
          (= \( (nth src i)) (recur (inc i) (inc depth))
          (= \) (nth src i)) (if (= 1 depth) (inc i) (recur (inc i) (dec depth)))
          :else (recur (inc i) depth))))
(def bodies
  (into {} (for [st tops
                 :let [en (form-end st)
                       head (subs src st (min (count src) (+ st 400)))
                       ;; This list must match the extractor's. It did not: the extractor
                       ;; learned defonce and defrecord and this copy did not, so
                       ;; kotoba.lang.test's RNG and test-registry had no body here
                       ;; and the generator died on a null. The refusal below turns
                       ;; that class into a named failure instead of a stack trace.
                       m (or (re-find #"^\((def|defn|defn-|def-|defonce|defmacro|defprotocol|defrecord)\s+(?:\^\S+\s+)*([a-zA-Z0-9*+!?<>=_.-]+)" head)
                             (re-find #"^#\?@?\([\s\S]*?\((def|defn|defn-|def-|defonce|defmacro|defprotocol|defrecord)\s+(?:\^\S+\s+)*([a-zA-Z0-9*+!?<>=_.-]+)" head))]
                 :when m]
             [(or (nth m 2) (nth m 4)) (subs src st en)])))


;; EXTERNAL REQUIRES.
;;
;; kotoba.lang.fs requires [kotoba.lang.text :as str] inside a reader
;; conditional and calls str/join, str/split, str/blank?, str/starts-with? and
;; str/last-index-of. The first version of this generator carried only the
;; INTERNAL edges, dropped that require, and produced repos that compiled into
;; "No such namespace: str" -- caught by running the suite, not by reading the
;; output. Every alias the source namespace binds is carried into each
;; definition repo whose body uses it, along with the deps.edn entry for the
;; library it names.
(def external-requires
  (let [ns-end (form-end (.indexOf src "(ns "))
        head   (subs src 0 ns-end)]
    ;; distinct: a require written once per reader-conditional branch is one
    ;; dependency, not two, and emitting it twice puts a duplicate key in the
    ;; generated deps.edn
    (vec (distinct (for [m (re-seq #"\[([a-z][a-zA-Z0-9._-]*)\s+:as\s+([a-zA-Z0-9*+!?<>=_-]+)\]" head)
                         :let [n (nth m 1) a (nth m 2)]
                         :when (not= n source-ns)]
                     {:ns n :alias a})))))

(def conditional-clauses
  "The `#?(...)` clauses of the source ns form, carried verbatim.

  Extracted by the same balanced scan that finds a form's end, not by a regex:
  kotoba.lang.process's ns has
  #?(:clj (:import (java.io ByteArrayOutputStream InputStream) ...)), two levels
  of nesting past the conditional, and a regex written for one level silently
  dropped it -- leaving a definition whose signature is [^InputStream in
  max-bytes] with no InputStream."
  (let [ns-end (form-end (.indexOf src "(ns "))]
    (vec (for [i (range (count src))
               :when (and (< i ns-end) (code-at? i)
                          (= \# (nth src i)) (= \? (get src (inc i)))
                          (or (= \( (get src (+ i 2)))
                              (and (= \@ (get src (+ i 2))) (= \( (get src (+ i 3))))))]
           (s/trim (subs src i (form-end i)))))))

(def ns-head
  (let [ns-end (form-end (.indexOf src "(ns "))] (subs src 0 ns-end)))

;; REFUSE ON AN ns FORM THIS GENERATOR CANNOT CARRY FAITHFULLY.
;;
;; kotoba.lang.process's ns has #?(:clj (:import (java.io ByteArrayOutputStream
;; InputStream) (java.util.concurrent TimeUnit))) -- two levels of nesting past
;; the conditional, which the clause copier does not reproduce. A definition
;; whose signature is [^InputStream in max-bytes] then has no InputStream, and
;; the only thing that says so is a compile error in a repo that has already
;; been published. Better to refuse and say which clause.
(let [carried (s/join "\n" conditional-clauses)
      missing (remove #(s/includes? carried %)
                      (map second (re-seq #"(\(:import\b[^\n]*)" ns-head)))]
  (when (seq missing)
    (println (str "REFUSED: the source ns form has " (count missing)
                  " clause(s) this generator does not carry into the split."))
    (doseq [m missing] (println (str "UNCARRIED\t" (s/trim m))))
    (js/process.exit 2)))

(def external-repo
  ;; kotoba.lang.text -> kotoba-lang/text. Only this shape is understood; any
  ;; other namespace makes the generator refuse rather than silently drop it.
  (into {} (for [{:keys [ns]} external-requires]
             [ns (second (re-find #"^kotoba\.lang\.([a-z0-9-]+)$" ns))])))

(when (re-find #"(^|[^:a-zA-Z0-9])::[a-zA-Z][a-zA-Z0-9*+!?<>=_-]*/" src)
  (println (str "REFUSED: this namespace uses an ALIASED auto-resolved keyword"
                " (::alias/name). Its value depends on an alias map this"
                " generator does not resolve, and moving it would change the"
                " value silently."))
  (js/process.exit 2))

(doseq [{:keys [ns]} external-requires]
  (when-not (get external-repo ns)
    (println (str "REFUSED: the source namespace requires " ns
                  ", which this generator cannot map to a repo. Splitting would"
                  " drop it and the pieces would not compile."))
    (js/process.exit 2)))

(def source-project
  "The deps.edn of the repository the source namespace lives in -- the nearest
  one at or above it."
  (loop [d (.dirname path-mod src-path)]
    (let [f (.join path-mod d "deps.edn")]
      (cond (.existsSync fs f) f
            (= d (.dirname path-mod d)) nil
            :else (recur (.dirname path-mod d))))))

(def external-shas
  ;; The sha the SOURCE REPOSITORY pins, not the tip of a local checkout.
  ;; Reading the checkout's HEAD silently upgraded every external dependency:
  ;; measured 2026-09-09, kotoba.lang.store pins fs at e3a7cd7 and io at
  ;; 39cf95c, and the split was handed 0fd66a6 and 516e984 instead. store's own
  ;; suite then failed ten assertions -- a byte array came back as the ASCII of
  ;; its own toString -- and nothing in the split was wrong. tools.deps takes
  ;; the newest sha it is shown, so an unasked-for upgrade here is not visible
  ;; at the call site either.
  (let [txt (when source-project (str (.readFileSync fs source-project "utf8")))]
    (into {}
          (for [[n r] external-repo]
            (let [m (re-find (re-pattern (str "kotoba-lang/" r "\\s*\\{[^}]*:git/sha\\s+\"([0-9a-f]{40})\"")) (or txt ""))]
              (when-not m
                (println (str "REFUSING: " source-project " does not pin kotoba-lang/" r
                              ", which " src-path " requires as " n
                              ". A split must carry the pin the source repository declares,"
                              " not whatever a local checkout happens to be at."))
                (js/process.exit 2))
              [n [r (second m)]])))))

(defn mkdirp [d] (.mkdirSync fs d #js {:recursive true}))
(defn spit [p t] (mkdirp (.dirname path-mod p)) (.writeFileSync fs p t "utf8"))

(def names (set (map :name defs)))
;; The names kotoba.lang.text itself had to exclude from clojure.core. Taken
;; from that file rather than guessed, so the split cannot disagree with it.
(def core-names
  (set (let [m (re-find #"(?s):refer-clojure :exclude \[(.*?)\]" src)]
         (when m (remove s/blank? (s/split (s/trim (second m)) #"\s+"))))))
(def by-name (into {} (map (juxt :name identity) defs)))

;; A definition the graph names but this file could not read the body of is a
;; definition that would be published empty. The two files each hold their own
;; copy of the form-recognition regex, so they can drift; this makes the drift
;; say which name it lost.
(let [missing (sort (remove #(contains? bodies %) (mapcat #(or (:members %) [(:name %)]) defs)))]
  (when (seq missing)
    (doseq [n missing] (println (str "NO-BODY\t" n)))
    (println "REFUSING: the graph names definitions whose source this generator could not read.")
    (js/process.exit 2)))

(defn ns-of [n] (str target-ns "." (slug (clojure-name n))))
(defn repo-of [n] (str repo-prefix (slug (clojure-name n))))

(doseq [{:keys [name kind deps]} defs]
  (let [members (or (:members (first (filter #(= name (:name %)) defs))) [name])
        one-body (fn [n]
                   (-> (get bodies n)
                       ;; A definition inside a top-level #?(...) is still a
                       ;; definition, and it still has to become public: the
                       ;; anchors are not at the start of the body when the body
                       ;; starts with the conditional.
                       (s/replace-first #"\(defn-\s" "(defn ")
                       (s/replace-first #"\(def-\s" "(def ")
                       (s/replace-first #"\^:private\s+" "")
                       (s/replace-first #"\^\{:private true\}\s+" "")))
        ;; AN AUTO-RESOLVED KEYWORD IS A VALUE, AND MOVING IT CHANGES IT.
        ;;
        ;; `::invalid` means "the keyword named invalid in the namespace this
        ;; form is written in". kotoba.lang.spec's sentinel is
        ;; :kotoba.lang.spec/invalid; moved into kotoba.spec.invalid it silently
        ;; became :kotoba.spec.invalid/invalid, and its own suite caught it
        ;; only because the two namespaces were loaded side by side. So the
        ;; keyword is written out against the namespace it came from: the value
        ;; is preserved exactly, and renaming the sentinel stays a deliberate
        ;; breaking change rather than a side effect of a refactor.
        expand-auto (fn [t] (s/replace t #"(^|[^:a-zA-Z0-9])::([a-zA-Z][a-zA-Z0-9*+!?<>=_-]*)"
                                       (fn [m] (str (nth m 1) ":" source-ns "/" (nth m 2)))))
        body (if (= 1 (count members))
               (one-body name)
               ;; source order, with the forward declaration the cycle needs
               (str "(declare " (s/join " " (sort members)) ")\n\n"
                    (s/join "\n\n" (map one-body
                                         (sort-by #(.indexOf src (str "(" (:kind (get by-raw %)) " " %)) members)))))
        ;; the protocol's own name, everywhere it appears in code that this
        ;; split emits -- the declaration, the reify/extend sites, and the
        ;; :refer lists of anything that reaches it
        rename-protocols (fn [t] (reduce (fn [acc pn]
                                           (if (= pn (clojure-name pn))
                                             acc
                                             (s/replace acc (re-pattern (str "\\b" pn "\\b"))
                                                        (clojure-name pn))))
                                         t (keys provides-of)))
        body (rename-protocols (expand-auto body))
        _unused (get bodies name)
        ;; a private def becomes public: another repo has to be able to require it
        ;; A protocol's repo provides its methods as well as its own name, and a
        ;; dependent calls the methods. Referring only the protocol name would
        ;; compile and then fail to resolve `read` at the call site.
        ;; A loose test on purpose. A false positive costs one unused
        ;; dependency; a false negative ships a repo that cannot compile --
        ;; fs-split did exactly that under a stricter boundary regex, using
        ;; str/split with no require and no complaint until the suite ran.
        ;; The ns form carries the source's reader-conditional clauses verbatim,
        ;; and those name namespaces too. Counting only the body left three
        ;; published fs repos requiring kotoba.lang.text with nothing in their
        ;; deps.edn behind it -- measured 2026-09-09, kotoba-lang/fs-split could
        ;; not be loaded from its own main at all.
        carried-text (s/join "\n" conditional-clauses)
        used-external (filterv (fn [{:keys [alias ns]}]
                                 (or (s/includes? body (str alias "/"))
                                     (s/includes? carried-text ns)))
                               external-requires)
        ;; A record TYPE cannot be :refer'd -- it is not a var, and
        ;; (:require [ns :refer [RNG]]) fails with "RNG does not exist". What a
        ;; caller uses is ->RNG / map->RNG, which are functions. So a defrecord
        ;; dependency contributes its constructors and not its type name.
        provided (fn [d]
                   (let [ds (or (get provides-of d) [d])
                         k (:kind (get by-name d))]
                     (map clojure-name
                          (if (= "defrecord" k) (remove #(= % d) ds) ds))))
        reqs (s/join "\n            "
                     (concat
                      (for [{:keys [ns alias]} used-external] (str "[" ns " :as " alias "]"))
                      (for [d (sort deps)]
                        (str "[" (ns-of d) " :refer [" (s/join " " (sort (provided d))) "]]"))))
        core-shadow (when (contains? core-names name)
                      (str "  (:refer-clojure :exclude [" name "])\n"))
        cycle? (> (count members) 1)
        nsform (str "(ns " (ns-of name) "\n"
                    "  \"" (s/join ", " (sort members)) " -- addressed on its own.\n\n"
                    "  Split out of " source-ns " on 2026-09-09 (ADR-2609091200). The unit\n"
                    "  here is the DEFINITION, and this repo's deps.edn names exactly the\n"
                    "  definitions it reaches -- nothing else.\n"
                    (if cycle?
                      (str "\n  It holds " (count members) " definitions, not one, because they call each\n"
                           "  other: " (s/join " and " (sort members)) " are mutually recursive, and the\n"
                           "  source says so itself with a (declare ...). Two definitions that call each\n"
                           "  other cannot be two repos with an acyclic dependency, so the unit is the\n"
                           "  cycle. Unison groups mutually recursive definitions the same way.\"\n")
                      "\"\n")
                    (or core-shadow "")
                    ;; the require form is emitted when there is ANYTHING to
                    ;; require. Gating it on internal deps alone dropped it for
                    ;; fs-split, whose only dependency is the external
                    ;; kotoba.lang.text -- the repo compiled to
                    ;; "No such namespace: str".
                    (if (or (seq deps) (seq used-external))
                      (str "  (:require " reqs ")\n")
                      "")
                    (if (seq conditional-clauses)
                      (str (s/join "\n" (map #(str "  " %) conditional-clauses)) ")\n")
                      (if (or (seq deps) (seq used-external)) ")\n" "  )\n")))
        dep-entries (s/join "\n        "
                            (concat
                             (for [{:keys [ns]} used-external
                                   :let [[r sha] (get external-shas ns)]]
                               (str "io.github.kotoba-lang/" r "\n        {:git/url \"https://github.com/kotoba-lang/" r
                                    ".git\"\n         :git/sha \"" sha "\"}"))
                             (for [d (sort deps)]
                               (str "io.github.kotoba-lang/" (repo-of d) " {:local/root \"../" (repo-of d) "\"}"))))
        deps-edn (str "{:paths [\"src\"]\n"
                      ;; The ns form guards on (or deps used-external); this
                      ;; line guarded on deps alone, so a repo whose only
                      ;; dependency is an external namespace got the require
                      ;; and not the dependency. Measured 2026-09-09:
                      ;; log-with-context required kotoba.lang.coll with an
                      ;; empty :deps.
                      " :deps {" (if (or (seq deps) (seq used-external)) (str "\n        " dep-entries) "") "}\n"
                      " :aliases\n"
                      " {:test {:extra-paths [\"test\"]\n"
                      "         :extra-deps {io.github.cognitect-labs/test-runner\n"
                      "                      {:git/tag \"v0.5.1\" :git/sha \"dfb30dd\"}}\n"
                      "         :main-opts [\"-m\" \"cognitect.test-runner\"]}}}\n")
        dir (.join path-mod out-dir (repo-of name))]
    ;; the file path comes from the namespace, not from a hard-coded family:
    ;; kotoba.coll.assoc-some -> src/kotoba/coll/assoc_some.cljc
    (spit (.join path-mod dir "src"
                 (str (s/replace (s/replace (ns-of name) "." "/") "-" "_") ".cljc"))
          (str nsform "\n"
               ;; DECLINE THE ONE GRANT THIS NAMESPACE DID NOT ASK FOR.
               ;;
               ;; On the JVM every namespace is handed 96 java.lang names before
               ;; it says anything, and a protocol whose name is one of them
               ;; cannot be declared: (defprotocol Process ...) is "Expecting
               ;; var, but Process is mapped to class java.lang.Process".
               ;;
               ;; The first fix bent the NAME -- IProcess kept its I while every
               ;; other protocol dropped one -- which puts a JVM accident into
               ;; the identity of a definition, on a host this workspace ranks
               ;; last. So the name is uniform and the ambient grant is declined
               ;; where it arises, for that one symbol, on that one host.
               ;; java.lang.Process stays reachable by its full name; measured.
               (let [taken (filter java-lang-auto-imports
                                   (map clojure-name (or (get provides-of name) [name])))]
                 (if (seq taken)
                   (str "\n;; This namespace declines the java.lang names it was handed but did not\n"
                        ";; ask for. On the JVM every namespace gets 96 of them before it says\n"
                        ";; anything, and " (s/join ", " (sort taken))
                        " is one. The class stays reachable\n;; by its full name.\n"
                        "#?(:clj (do "
                        (s/join " " (for [x (sort taken)] (str "(ns-unmap *ns* '" x ")")))
                        "))\n")
                   ""))
               body "\n"))
    (spit (.join path-mod dir "deps.edn") deps-edn)
    (spit (.join path-mod dir "README.md")
          (str "# " (repo-of name) "\n\n`" (ns-of name) "/" name "`\n\n"
               "One definition. " (if (seq deps)
                                    (str "Reaches " (s/join ", " (map ns-of (sort deps))) ".")
                                    "Reaches nothing else in this family.") "\n"))))

;; the facade
;; THE FACADE RE-EXPORTS FUNCTIONS, NOT VALUES.
;;
;; `(def x other/x)` copies. For a function that is harmless -- the same
;; function object ends up under both names -- but for a value var it makes
;; `with-redefs` on the facade a SILENT NO-OP: the rebind changes the copy and
;; every reader still sees the original. kotoba.lang.edn has five such vars
;; (max-depth, max-token-chars, max-string-chars, max-nodes, max-edn-bytes) and
;; its own suite rebinds them; against a copying facade three assertions passed
;; nothing and reported no error at all.
;;
;; So a value var is not re-exported. `kotoba.edn/max-depth` does not resolve,
;; which is a compile error rather than a rebind that does nothing, and code
;; that needs the value requires the one repo that defines it -- which is what
;; the split is for.
(def publics (remove #(#{"defn-" "def-" "def" "defonce" "defmacro"} (:kind %)) defs))
;; defonce is a value var like def: copying it through the facade shares the
;; object, which happens to work for an atom, but with-redefs through the facade
;; is the same silent no-op a copied def has.
;;
;; A MACRO cannot be copied at all. Measured 2026-09-09 on kotoba.lang.test:
;; (def are other/are) is a compile error -- "Can't take value of a macro" --
;; because a macro var holds a compile-time function the reader must see through
;; the var itself. Wrapping it in a forwarding defmacro would work on the JVM
;; and needs :require-macros gymnastics in cljs, so the facade does not re-export
;; macros either: a caller that needs deftest requires the one repo defining it.
;; defonce is a value var like def: copying it through the facade shares the
;; object, which happens to work for an atom, but with-redefs through the facade
;; is the same silent no-op a copied def has. Same rule, same reason.

;; What the facade actually re-exports, name by name.
;;
;; A protocol's METHODS are functions and copy fine -- fs/exists? has to work or
;; kotoba.fs is unusable for the thing it exists for. The PROTOCOL itself does
;; not: its identity is what extend-type and reify dispatch on, and a copy would
;; make an implementation silently extend nothing. So methods are exported and
;; the protocol name is not, which means an implementer gets a compile error
;; pointing at the one repo that defines the contract.
(def facade-names
  (vec (for [{:keys [name kind]} publics
             n (cond
                 ;; methods, not the protocol
                 (= "defprotocol" kind) (rest (or (get provides-of name) [name]))
                 ;; A defrecord is the same shape of problem as a protocol, and
                 ;; worse to get wrong: RNG is a TYPE, not a var, so
                 ;; (def RNG other/RNG) does not even compile. What a caller
                 ;; actually uses is ->RNG and map->RNG, which are functions and
                 ;; copy fine. So the constructors are exported and the type name
                 ;; is not -- and an implementer reaching for the type gets a
                 ;; compile error pointing at the one repo that defines it.
                 (= "defrecord" kind) (remove #(= % name) (or (get provides-of name) []))
                 :else [name])]
         {:export n :from name})))
(let [dir (.join path-mod out-dir facade-repo)
      reqs (s/join "\n            " (for [{:keys [name]} (sort-by :name publics)]
                                      (str "[" (ns-of name) " :as " (slug name) "-ns]")))
      _ nil
      ;; A `:refer` would make the names usable INSIDE this namespace and leave
      ;; kotoba.string/split unresolvable from outside, which is the one thing a
      ;; facade has to provide. So each name is re-bound as a var here.
      alias-defs (s/join "\n" (for [{:keys [export from]} (sort-by :export facade-names)]
                                (str "(def " export " \"See " (ns-of from) "/" export ".\" "
                                     (slug from) "-ns/" export ")")))
      excl (s/join " " (sort (filter core-names (map :export facade-names))))
      body (str (str "(ns " target-ns "\n")
                "  \"Assembled from one repo per definition.\n\n"
                "  This namespace holds no implementation. It re-exports the definitions\n"
                "  that each live in their own repo, so a call site can require one name\n"
                "  and a library can require only the definitions it actually uses.\n"
                (let [omitted (sort (remove (set (map :export facade-names))
                                            (map :name publics)))
                      protos  (set (map :name (filter #(= "defprotocol" (:kind %)) publics)))
                      vals    (sort (map :name (filter #(#{"def"} (:kind %)) defs)))]
                  (str
                   (let [protos (sort (map :name (filter #(= "defprotocol" (:kind %)) defs)))]
                     (when (seq protos)
                       (str "\n  AN IMPLEMENTATION BUILT AGAINST " source-ns " IS NOT ACCEPTED HERE.\n"
                            "  " source-ns " still declares " (s/join ", " protos)
                            ", and a protocol split into its own\n"
                            "  repo is a DIFFERENT protocol from the one the source namespace declares\n"
                            "  (ADR-2609091900). Measured 2026-09-09 on kotoba.lang.fs: a filesystem\n"
                            "  reified against the source protocol answers through the source namespace\n"
                            "  and fails through this one -- No implementation of method: :exists?.\n"
                            "  Build the implementation against the repo that declares the protocol here,\n"
                            "  or call through " source-ns ".\n")))
                   (when (seq omitted)
                     (str "\n  NOT re-exported here, on purpose: "
                          (s/join ", " omitted)
                          ". A protocol's identity is what extend-type and reify dispatch on,\n"
                          "  and a copy would make an implementation silently extend nothing, so the\n"
                          "  protocol name stays in the one repo that declares it. Requiring that repo\n"
                          "  is a compile error away; a copy would not be.\n"))
                   (let [macs (sort (map :name (filter #(= "defmacro" (:kind %)) defs)))]
                     (when (seq macs)
                       (str "\n  Macros are not re-exported: " (s/join ", " macs)
                            ". A macro var cannot be copied -- (def x other/x) on one is a\n"
                            "  compile error: Can't take value of a macro. A forwarding\n"
                            "  defmacro would need :require-macros to work in ClojureScript.\n"
                            "  Require the repo that defines the macro.\n")))
                   (when (seq vals)
                     (str "\n  Value vars are not re-exported either: "
                          (s/join ", " vals)
                          ". `(def x other/x)` copies, which is harmless for a function and makes\n"
                          "  with-redefs through this namespace a SILENT no-op for a value -- measured\n"
                          "  on kotoba.lang.edn, where three assertions passed against nothing at all.\n"
                          "  Require the repo that defines the value.\n"))))
                "\"\n"
                (if (s/blank? excl) "" (str "  (:refer-clojure :exclude [" excl "])\n"))
                "  (:require " reqs "))\n\n" alias-defs "\n")
      dep-entries (s/join "\n        " (for [{:keys [name]} (sort-by :name publics)]
                                         (str "io.github.kotoba-lang/" (repo-of name) " {:local/root \"../" (repo-of name) "\"}")))]
  (spit (.join path-mod dir "src" (str (s/replace (s/replace target-ns "." "/") "-" "_") ".cljc")) body)
  (spit (.join path-mod dir "deps.edn")
        (str "{:paths [\"src\"]\n :deps {\n        " dep-entries "}\n"
             " :aliases\n {:test {:extra-paths [\"test\"]\n"
             "         :extra-deps {io.github.cognitect-labs/test-runner\n"
             "                      {:git/tag \"v0.5.1\" :git/sha \"dfb30dd\"}}\n"
             "         :main-opts [\"-m\" \"cognitect.test-runner\"]}}}\n"))
  (spit (.join path-mod dir "README.md")
        (str "# " facade-repo "\n\n`" target-ns "` -- assembled from one repo per definition.\n\nNo implementation lives here. Every definition is its own repo and this one re-exports them.\n")))

;; A definition that names a record TYPE directly -- (instance? RNG x), (RNG. 1)
;; -- cannot get it through :require/:refer; that needs :import on the JVM and
;; has no cljc form. Nothing in the families split so far does it, and if one
;; does the split has to stop rather than emit a repo that will not load.
(let [rec-names (set (map :name (filter #(= "defrecord" (:kind %)) defs)))
      bad (for [{:keys [name members]} defs
                m (or members [name])
                :let [body (get bodies m "")]
                r rec-names
                :when (and (not= r m)
                           (re-find (re-pattern (str "\\(" r "\\.|instance\\?\\s+" r "\\b")) body))]
            (str m " -> " r))]
  (when (seq bad)
    (doseq [b (distinct bad)] (println (str "RECORD-TYPE-REFERENCE\t" b)))
    (println "REFUSING: a record type cannot cross a namespace boundary through :refer.")
    (js/process.exit 2)))

;; A protocol whose name is one the JVM hands out must carry the form that hands
;; it back. The two live in different places -- the naming rule and the emitter --
;; so they can drift, and the drift is silent until someone compiles on the JVM.
;; This is the reverse check: it fails if a name was bent instead of a grant
;; declined, and it fails if the grant was neither declined nor bent.
(let [bent (filter (fn [{:keys [name kind]}]
                     (and (= "defprotocol" kind)
                          (re-matches #"I[A-Z][A-Za-z0-9]*" name)
                          (= name (clojure-name name))))
                   defs)]
  (when (seq bent)
    (doseq [d bent] (println (str "BENT-NAME\t" (:name d))))
    (println "REFUSING: a protocol name was kept as-is to dodge a host collision. Decline the grant instead -- see java-lang-auto-imports.")
    (js/process.exit 2)))

(slugs-legal! (map :name defs))
(slugs-distinct! (map :name defs))

;; The definition name -> repo mapping, written out so no driver has to
;; reimplement it. Every shell reimplementation of `slug` so far has diverged:
;; one did not kebab-case CamelCase and looked for fs-IAsyncFilesystem, another
;; did not drop the leading I and looked for fs-iasync-filesystem, and both
;; failed as "no such directory" -- which is what a definition that was
;; deliberately not generated also looks like.
(.writeFileSync fs (.join path-mod out-dir "REPOS.tsv")
                (s/join "" (for [{:keys [name]} defs]
                             (str name "\t" (repo-of name) "\n"))))

;; Every namespace a generated repo requires must be reachable from that repo's
;; own deps.edn. A require with no dependency behind it does not fail at
;; generation, does not fail at commit, and does not fail at push -- it fails
;; the first time someone loads the namespace, which for a definition nobody
;; consumes yet may be never. Measured 2026-09-09: log-with-context required
;; kotoba.lang.coll with an empty :deps map.
(let [dirs (->> (.readdirSync fs out-dir)
                (filter #(s/starts-with? % repo-prefix)))
      bad (atom [])]
  (doseq [d dirs]
    (let [dep-file (.join path-mod out-dir d "deps.edn")
          deps-txt (str (.readFileSync fs dep-file "utf8"))
          ;; the namespaces this repo can resolve: its own, the family repos it
          ;; names by ../, and the external repos it names by :git/url
          local (set (map #(second (re-matches #"\.\./(.*)" %))
                          (re-seq #"\.\./[a-z0-9.*-]+" deps-txt)))
          ext   (set (map second (re-seq #"kotoba-lang/([a-z0-9-]+)\.git" deps-txt)))
          src-dir (.join path-mod out-dir d "src")
          files (loop [todo [src-dir] out []]
                  (if (empty? todo) out
                    (let [x (first todo)
                          st (.statSync fs x)]
                      (if (.isDirectory st)
                        (recur (concat (rest todo)
                                       (map #(.join path-mod x %) (.readdirSync fs x))) out)
                        (recur (rest todo) (conj out x))))))]
      (doseq [f files]
        (let [txt (str (.readFileSync fs f "utf8"))
              required (map second (re-seq #"\[([a-z][a-z0-9.-]*) :(?:as|refer)" txt))]
          (doseq [r required]
            (let [family? (s/starts-with? r (str target-ns "."))
                  seg (when family? (subs r (inc (count target-ns))))
                  ok (if family?
                       (contains? local (str repo-prefix seg))
                       ;; an external ns: some repo named by :git/url must plausibly
                       ;; own it. We cannot resolve that here, so require only that
                       ;; the repo names at least one external dependency.
                       (seq ext))]
              (when-not ok
                (swap! bad conj (str d "\t" r)))))))))
  (println (str "REQUIRES-SATISFIED\t" (- (count dirs) (count (distinct (map #(first (s/split % #"\t")) @bad))))
                "\tof " (count dirs) " definition repos"))
  (when (seq @bad)
    (doseq [b (sort (distinct @bad))] (println (str "UNSATISFIED\t" b)))
    (println "REFUSING: a repo requires a namespace its deps.edn does not provide.")
    (js/process.exit 2)))


(println (str "GENERATED\t" (count defs) "\tdefinition repos + 1 facade into " out-dir))

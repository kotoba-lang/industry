(ns squint-unary-minus
  "Fails the build when a source the browser bundle loads contains an arithmetic form
  squint-cljs 0.8.147 compiles to the wrong expression.

  ## The bug

  Squint splices a nested `+`/`-` form's operands into an enclosing **unary** minus instead
  of grouping them:

      (- (+ a b))        =>   -(a) + (b)          ; wants -((a) + (b))
      (- (- a b))        =>   -(a) - (b)          ; wants -((a) - (b))

  `*` and `/` survive by luck, because negation distributes over them:

      (- (* a b))        =>   -(a) * (b)          ; correct
      (- (/ a b))        =>   -(a) / (b)          ; correct

  and binary minus is fine, which is why a naive regex over `(- (` reports sites that are
  not bugs:

      (- a (+ b c))      =>   (a) - ((b) + (c))   ; correct
      (- (- q) rings)    =>   (-q) - (rings)      ; correct — binary, not unary

  So arity decides, and this check reads the form rather than pattern-matching the text.

  ## Why it needs a check rather than a note

  The output compiles and runs. There is no warning, no error and no artefact — the number
  is simply wrong, and only in the browser: Clojure and ClojureScript compile the same source
  correctly, so the JVM tests pass, the nbb CLI renderer draws the right picture, and the
  page is quietly askew. This game hit it twice at once — a road ring yawed to `π/2 - a`
  instead of `-(a + π/2)`, and the engine's orthographic projection returning `l - r` for the
  shadow matrix's translation lane.

  `test/parity.cljs` catches this class semantically, by running the real street through both
  compilers and diffing every float. That is the stronger check and it is the one that found
  the bug. This one is the cheap one: it runs in a second, needs no JVM, and names the line.

  Squint is an external npm package, not a repo in this workspace, so the fix is local: write
  `(* -1.0 (+ a b))`, which is correct under all three compilers.

  Run: nbb squint-unary-minus-check.cljs <repo-tree>

  The package keeps a copy at `test/squint_unary_minus.cljs` that resolves the same sources
  through west-relative paths, so it can be run from the game directory during development."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]))

(def here (or (first *command-line-args*) "."))

(def roots
  ["60-apps/network-isekai/public/games/itonami/isic-9601/src"
   "60-apps/network-isekai/public/games/itonami/isic-9601/preview"
   "60-apps/network-isekai/public/games/itonami/isic-9601/test"
   "orgs/kotoba-lang/webgpu/src"
   "orgs/kotoba-lang/render/src"])

;; --------------------------------------------------------------------------

(defn- cljish? [f] (some (fn [e] (.endsWith f e)) [".clj" ".cljc" ".cljs"]))

(defn- walk [dir]
  (if-not (fs/existsSync dir)
    []
    (mapcat (fn [e]
              (let [p (path/join dir (.-name e))]
                (cond (.isDirectory e) (walk p)
                      (cljish? (.-name e)) [p]
                      :else [])))
            (fs/readdirSync dir #js {:withFileTypes true}))))

(defn- strip
  "Blank out string literals, character literals and line comments so that a `;` or a `(`
  inside them cannot be mistaken for code. Keeps length and newlines so offsets stay true."
  [s]
  (let [n (count s)]
    (loop [i 0 out [] mode :code]
      (if (>= i n)
        (apply str out)
        (let [c (nth s i)
              c1 (when (< (inc i) n) (nth s (inc i)))]
          (case mode
            :code (cond
                    (and (= c \\) c1) (recur (+ i 2) (conj out \space \space) :code)
                    (= c \") (recur (inc i) (conj out \space) :string)
                    (= c \;) (recur (inc i) (conj out \space) :comment)
                    :else (recur (inc i) (conj out c) :code))
            :string (cond
                      (and (= c \\) c1) (recur (+ i 2) (conj out \space \space) :string)
                      (= c \") (recur (inc i) (conj out \space) :code)
                      :else (recur (inc i) (conj out (if (= c \newline) c \space)) :string))
            :comment (if (= c \newline)
                       (recur (inc i) (conj out c) :code)
                       (recur (inc i) (conj out \space) :comment))))))))

(defn- form-end
  "Index just past the form that opens at `i` (which must be `(`)."
  [s i]
  (loop [j (inc i) depth 1]
    (cond (>= j (count s)) j
          (= (nth s j) \() (recur (inc j) (inc depth))
          (= (nth s j) \)) (if (= depth 1) (inc j) (recur (inc j) (dec depth)))
          :else (recur (inc j) depth))))

(defn- top-level-args
  "The argument forms of `(op a b c)` as strings, given the whole form's text."
  [form]
  (let [body (subs form 1 (dec (count form)))]
    (loop [i 0 acc [] cur "" depth 0]
      (if (>= i (count body))
        (let [acc (if (seq (.trim cur)) (conj acc (.trim cur)) acc)]
          (rest acc))                                   ; drop the operator itself
        (let [c (nth body i)]
          (cond
            (= c \() (recur (inc i) acc (str cur c) (inc depth))
            (= c \)) (recur (inc i) acc (str cur c) (dec depth))
            (and (zero? depth) (or (= c \space) (= c \newline) (= c \tab) (= c \,)))
            (recur (inc i) (if (seq (.trim cur)) (conj acc (.trim cur)) acc) "" depth)
            :else (recur (inc i) acc (str cur c) depth)))))))

(defn- sites
  "Every unary `(- X)` in `src` whose `X` is a `(+ …)` or `(- …)` form."
  [src]
  (let [s (strip src)]
    (loop [i 0 out []]
      (if-let [k (let [k (.indexOf s "(-" i)] (when (not (neg? k)) k))]
        (let [after (when (< (+ k 2) (count s)) (nth s (+ k 2)))]
          (if-not (or (= after \space) (= after \newline) (= after \tab))
            (recur (inc k) out)                         ; `(->`, `(-foo`, etc.
            (let [end (form-end s k)
                  form (subs s k end)
                  args (top-level-args form)]
              (recur end
                     (if (and (= 1 (count args))
                              (or (.startsWith (first args) "(+ ")
                                  (.startsWith (first args) "(- ")
                                  (.startsWith (first args) "(+\n")
                                  (.startsWith (first args) "(-\n")))
                       (conj out [k form])
                       out)))))
        out))))

;; --------------------------------------------------------------------------

(def scanned
  "Per root: how many files were read, or that the root is not on disk.

  Reported rather than skipped. The engine roots are west checkouts, so in a tree that does
  not have them — a fleet-CI shipment, a fresh clone — `walk` returns nothing and the check
  passes without having looked at the code it exists to look at. A gate that cannot see its
  subject must say so; a green tick that means 'found no files' is worse than no gate,
  because it reads exactly like 'found no problems'."
  (mapv (fn [root]
          (let [dir (path/join here root)]
            {:root root
             :present? (fs/existsSync dir)
             :files (count (walk dir))}))
        roots))

(def findings
  (vec (mapcat
        (fn [root]
          (mapcat (fn [f]
                    (let [src (fs/readFileSync f "utf8")]
                      (map (fn [[off form]]
                             {:file (path/relative here f)
                              :line (count (.split (subs src 0 off) "\n"))
                              :form (first (.split form "\n"))})
                           (sites src))))
                  (walk (path/join here root))))
        roots)))

(println)
(doseq [{:keys [root present? files]} scanned]
  (println (str "  " (if present? (str files " files") "ABSENT — not scanned") "  " root)))
(when-let [missing (seq (remove :present? scanned))]
  (println)
  (println (str "  ⚠ " (count missing) " root(s) absent. The engine lives at west paths"
                " (`west update --fetch smart webgpu render`); without them this checked"
                " only the game's own sources.")))
(println)
(if (seq findings)
  (do
    (println "FAIL squint-unary-minus —" (count findings) "site(s) squint compiles wrongly")
    (println)
    (doseq [f findings]
      (println (str "  " (:file f) ":" (:line f)))
      (println (str "    " (:form f)))
      (println "    → squint splices the inner operands out of the negation."
               "Write (* -1.0 (+ …))."))
    (println)
    (js/process.exit 1))
  (println "OK squint-unary-minus — no miscompiled arithmetic in the bundled sources"))

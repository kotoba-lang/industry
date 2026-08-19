#!/usr/bin/env nbb
;; scripts/verify-silent-js-callbacks.cljs
;;
;; `letfn` siblings handed to a JS callback BY NAME. Under nbb/SCI the callback
;; is never invoked -- no exception, no warning, no trace. The code reads as if
;; the handler is installed.
;;
;;   (letfn [(retry [e] …)
;;           (step  []  (-> (js/Promise.resolve (f)) (.catch retry)))]  ; inert
;;
;; Measured 2026-08-19 (root ADR-2608190100): reduced to four lines --
;; `(.catch h)` where `h` is a letfn sibling does not fire, `(.catch (fn [e] (h e)))`
;; does. `let`-bound fns and inline fns are FINE; this is specific to letfn, so
;; the detector does not flag the shapes that work.
;;
;; Two severities, because the same source line means different things:
;;   high — the repo is run under nbb today (it ships an nbb test runner), so a
;;          handler that cannot fire is a live defect.
;;   med  — the repo is built with shadow-cljs, where passing by name works.
;;          It is a portability hazard: the day the suite moves to nbb, the
;;          handler silently disappears rather than failing to compile.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-silent-js-callbacks.cljs [root…]
;;   ... --self-test   run the fixtures (both directions) and stop
;;   ... --findings    emit FINDING<TAB>sev<TAB>key<TAB>detail lines
;;
;; exit 0 clean · 1 findings · 2 could not answer

(ns verify-silent-js-callbacks
  (:require ["fs" :as fs] ["path" :as p] [clojure.string :as str]))

(def argv
  "Arguments after THIS script's own path.

  Not `(drop 3 …)`: that is only right when the script is argv[2], which stops
  being true the moment a `--classpath` is passed -- measured on the first real
  run, where the detector took its own path as a root, scanned one file, and
  reported findings in its own fixture strings. Locate the script instead."
  (let [a (vec js/process.argv)
        i (first (keep-indexed (fn [i v] (when (str/ends-with? v "verify-silent-js-callbacks.cljs") i)) a))]
    (vec (drop (inc (or i 2)) a))))
(def flags (set (filter #(str/starts-with? % "--") argv)))
(def roots (let [r (vec (remove #(str/starts-with? % "--") argv))]
             (if (seq r) r ["orgs"])))
(def self-test? (contains? flags "--self-test"))
(def findings? (contains? flags "--findings"))

(def js-callback-methods
  "JS methods whose argument is called back. `.then`/`.catch` are where this was
  actually found; the rest are the same hazard with the same shape."
  #{"then" "catch" "finally" "forEach" "map" "filter" "find" "some" "every"
    "reduce" "sort" "addEventListener"})

(defn- strip-noncode
  "Line comments AND double-quoted strings removed before matching.

  Both were measured, both on this file's own ancestors: the first run flagged
  the very file whose bug had just been fixed, because the fix's comment quotes
  the broken form; the second flagged this detector's own self-test fixtures,
  which are string literals containing it. 壊した箇所と報告された箇所が一致
  すること (ADR-2608136000) applies to a detector's input too.

  Newlines are preserved so line numbers survive."
  [s]
  (-> s
      (str/replace #"(?m);.*$" "")
      (str/replace #"\"(?:[^\"\\\\\n]|\\\\.)*\"" "\"\"")))

(defn- balanced-end
  "Index just past the form opening at `i` (which must be `(` or `[`)."
  [txt i]
  (let [open (nth txt i)
        close (if (= open \() \) \])]
    (loop [j (inc i) d 1 in-str? false]
      (cond
        (>= j (count txt)) j
        in-str? (recur (inc j) d (not= \" (nth txt j)))
        (= \" (nth txt j)) (recur (inc j) d true)
        (= open (nth txt j)) (recur (inc j) (inc d) false)
        (= close (nth txt j)) (if (= 1 d) (inc j) (recur (inc j) (dec d) false))
        :else (recur (inc j) d false)))))

(defn- letfn-blocks
  "For each `letfn` in `txt`: the names it binds, and the [start end) span of
  each binding's own text.

  The span is the whole point. Measured 2026-08-19 in four directions under nbb:

    single-binding letfn, name passed as a callback          -> WORKS
    two bindings, name passed as a callback FROM THE BODY    -> WORKS
    two bindings, name passed as a callback FROM A SIBLING   -> NEVER FIRES
    two bindings, sibling calls the other directly           -> WORKS

  So the defect is not `letfn`, and it is not passing a name to JS: it is a name
  reaching a JS callback from INSIDE another binding of the same `letfn`, which
  is where SCI's mutual-recursion machinery sits. A detector that flags the
  other three shapes reports working code as broken -- the first version of this
  one did, including a production `hydrate-db` that measurably builds its
  database correctly."
  [txt]
  (for [m (vec (.matchAll txt (js/RegExp. "\\(letfn\\s*\\[" "g")))
        :let [vec-start (dec (+ (.-index m) (count (aget m 0))))
              vec-end (balanced-end txt vec-start)
              base (inc vec-start)
              inner (subs txt base (max base (dec vec-end)))]]
    (loop [i 0 bindings []]
      (let [rel (.indexOf inner "(" i)]
        (if (neg? rel)
          {:names (set (map :name bindings)) :bindings bindings}
          (let [end (balanced-end inner rel)
                head (re-find #"^\(([a-zA-Z][a-zA-Z0-9<>?!*+_-]*)\s*\[" (subs inner rel (min (count inner) (+ rel 80))))]
            (recur end (if head
                         (conj bindings {:name (second head) :start (+ base rel) :end (+ base end)})
                         bindings))))))))

(defn findings-in
  "[{:method :arg :host :line} …] for `txt` -- a JS callback that receives a
  `letfn` name from inside a SIBLING binding of the same `letfn`.

  Both call shapes matter and they look different: written out it is
  `(.catch p handler)`, and inside a `->` thread it is `(.catch handler)` with
  no receiver at all. The threaded shape is the one that was actually in
  kotobase-server, so a scanner that only knows the two-argument form reports
  the bug it was written for as absent."
  [txt]
  (let [clean (strip-noncode txt)]
    (if-not (str/includes? clean "(letfn")
      []
      (vec (for [blk (letfn-blocks clean)
                 m (vec (.matchAll clean (js/RegExp. "\\(\\.([a-zA-Z]+)((?:\\s+[^()\\s]+){1,2})\\s*\\)" "g")))
                 :let [meth (aget m 1)
                       arg (last (str/split (str/trim (aget m 2)) #"\s+"))
                       at (.-index m)
                       host (first (filter #(and (>= at (:start %)) (< at (:end %)))
                                           (:bindings blk)))]
                 :when (and (contains? js-callback-methods meth)
                            (contains? (:names blk) arg)
                            host
                            (not= (:name host) arg))]
             {:method meth :arg arg :host (:name host)
              :line (inc (count (re-seq #"\n" (subs clean 0 at))))})))))

(def self-test-cases
  "Every case is a shape that was actually run under nbb, not a guess. The four
  measured directions are in `letfn-blocks`; these encode them plus the two
  input-hygiene cases that earlier versions got wrong."
  [{:why "threaded `.catch` on a sibling — the shape measured in kotobase-server"
    :text "(letfn [(retry [e] e) (step [] (-> (p) (.catch retry)))] (step))" :match? true}
   {:why "two-argument form, from inside a sibling"
    :text "(letfn [(retry [e] e) (step [] (.catch pr retry))] (step))" :match? true}
   {:why "single-binding letfn passed by name — MEASURED to work"
    :text "(letfn [(retry [e] e)] (.catch pr retry))" :match? false}
   {:why "two bindings, but passed from the letfn BODY — MEASURED to work"
    :text "(letfn [(retry [e] e) (other [] nil)] (.catch pr retry))" :match? false}
   {:why "a sibling passing ITSELF (self-recursion, not the mutual case)"
    :text "(letfn [(step [] (.catch pr step))] (step))" :match? false}
   {:why "wrapped in an inline fn — this is the FIX, must not be flagged"
    :text "(letfn [(retry [e] e) (step [] (-> (p) (.catch (fn [e] (retry e)))))] (step))" :match? false}
   {:why "`let`-bound fn — measured to work under SCI"
    :text "(let [retry (fn [e] e)] (-> (p) (.catch retry)))" :match? false}
   {:why "a non-callback method receiving a sibling name"
    :text "(letfn [(retry [e] e) (step [] (.push arr retry))] (step))" :match? false}
   {:why "the broken form quoted inside a STRING (this detector's own fixtures)"
    :text "(letfn [(retry [e] e) (step [] (def doc \"use (.catch retry)\") (.catch pr (fn [e] (retry e))))] (step))" :match? false}
   {:why "the broken form quoted inside a comment"
    :text "(letfn [(retry [e] e)\n        (step []\n          ;; `(.catch retry)` is inert under SCI\n          (.catch pr (fn [e] (retry e))))]\n  (step))" :match? false}])

(defn- run-self-test! []
  (let [bad (for [{:keys [text match? why]} self-test-cases
                  :let [hit (boolean (seq (findings-in text)))]
                  :when (not= hit match?)]
              why)]
    (println (str "self-test: " (- (count self-test-cases) (count bad)) "/"
                  (count self-test-cases) " cases"))
    (doseq [w bad] (println (str "  FAILED: " w)))
    (when (seq bad) (js/process.exit 2))))

(defn- source-files [dir]
  (if-not (fs/existsSync dir)
    []
    (let [st (fs/statSync dir)]
      (cond
        (.isFile st) (if (re-find #"\.clj[sc]$" dir) [dir] [])
        (.isDirectory st)
        (if (contains? #{".git" "node_modules" ".shadow-cljs" "target" ".cpcache" ".datalad" "out"}
                       (p/basename dir))
          []
          (vec (mapcat #(source-files (p/join dir %)) (fs/readdirSync dir))))
        :else []))))

(defn- repo-of
  "orgs/<org>/<repo> for a path under an orgs tree, else nil."
  [file]
  (let [seg (str/split file #"/")
        i (.indexOf seg "orgs")]
    (when (and (not (neg? i)) (> (count seg) (+ i 2)))
      (str/join "/" (subvec seg i (+ i 3))))))

(defn- runs-on-nbb?
  "Does this repo ship an nbb runner? That is what separates a live defect from
  a hazard, and it is a fact on disk rather than a judgement."
  [repo]
  (boolean (some #(fs/existsSync (p/join repo %))
                 ["run-nbb-tests.cljs" "test/run_portable.cljs" "run-tests.cljs"])))

(when self-test? (run-self-test!) (js/process.exit 0))

(let [files (vec (mapcat source-files roots))]
  (println (str "SCANNED\t" (count files)))
  (when (zero? (count files))
    (println "no .cljs/.cljc files under " (pr-str roots)
             " — refusing to report clean")
    (js/process.exit 2))
  (let [hits (vec (for [f files
                        h (findings-in (str (fs/readFileSync f "utf8")))]
                    (assoc h :file f :repo (repo-of f))))
        live (group-by #(boolean (and (:repo %) (runs-on-nbb? (:repo %)))) hits)]
    (doseq [[on-nbb? group] (sort-by (comp not first) live)
            h (sort-by :file group)]
      (let [sev (if on-nbb? "high" "med")
            k (str (:file h) ":" (:line h))
            detail (str "(." (:method h) " … " (:arg h) ") — `" (:arg h)
                        "` is a letfn sibling passed by name to a JS callback; "
                        "under nbb/SCI it is never invoked. "
                        (if on-nbb?
                          "This repo ships an nbb runner, so the handler is dead there today."
                          "This repo is built with shadow-cljs, where it works; it breaks on the move to nbb."))]
        (if findings?
          (println (str "FINDING\t" sev "\t" k "\t" detail))
          (println (str "  " sev "  " k "  " detail)))))
    (println (str "FINDINGS\t" (count hits)))
    (js/process.exit (if (seq hits) 1 0))))

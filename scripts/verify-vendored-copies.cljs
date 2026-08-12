#!/usr/bin/env nbb
;; Perform the diff the vendored files ask for.
;;
;;   nbb scripts/verify-vendored-copies.cljs           ; report
;;   nbb scripts/verify-vendored-copies.cljs --check   ; exit 1 if any is stale
;;   nbb scripts/verify-vendored-copies.cljs --diff    ; also show the def names
;;
;; Run from the superproject root.
;;
;; ## Why
;;
;; Several repos copy a library file into their own `src/` instead of depending
;; on it, because their CI checks out one repo with no west workspace. Each copy
;; says so in a header, and most name the commit they were taken from:
;;
;;     ;; VENDORED from kotoba-lang (pay/facilitator.cljc), pay pinned at 8a77e516…
;;     ;; Diff against upstream to check drift.
;;
;; Nothing performed that diff. Measured 2026-08-12 (ADR-2608121000): six repos
;; vendor `pay/*` and `treasury/core.cljc`, and several were running older rules
;; on paths that decide whether a payment happened — one missing the guard whose
;; own fix comment says its absence lets an unconfirmed payment be accepted as
;; confirmed. The copies had drifted for weeks with nobody notified, because the
;; instruction to check was addressed to a person.
;;
;; ## What it compares
;;
;; Two questions, and they are different:
;;
;;   PIN-HONEST  does the copy match the commit its header claims?
;;               A copy that does not is worse than a stale one: the header is
;;               the only provenance there is, and it is wrong.
;;   CURRENT     does the copy match the library's HEAD?
;;               Stale is not automatically a defect — vendoring is a deliberate
;;               snapshot — but it must be visible, and the header does not say
;;               when the snapshot was last refreshed.
;;
;; A copy that declares no pin at all has only the CURRENT axis. It is not
;; pin-honest and it is not pin-dishonest; there is nothing to be honest about.
;; The report says `no pin declared` for those, distinct from `pin unverifiable`
;; (a pin was named but the file could not be read at that revision).
;;
;; Comparison starts at the first `(ns ` form, so the vendored header itself is
;; not counted as drift. Everything before it is provenance, not code. Four of
;; the copies put their provenance INSIDE the ns docstring instead, where that
;; subtraction cannot reach; those lines say so, because otherwise their drift
;; number reads as code drift when it is the header talking about itself.
;;
;; ## Where it looks
;;
;; Every directory named `src` or `src-*` within a repo, not just `<repo>/src`.
;; Two earlier versions of this scan were each narrow in a way that produced a
;; confident, wrong, smaller number:
;;
;;   - only `<repo>/src` at the top level: missed the copies in nested
;;     sub-projects (`<repo>/kotobase-api-gateway-cljs/src`,
;;     `<repo>/appview/<name>/cljs/src`), including the one ADR-2608121000
;;     calls the worst in the fleet.
;;   - only directories named exactly `src`: missed
;;     `wasm-webcomponent/src-cljs/vendor/kotoba/kami_host.cljc`. Accepting
;;     `src-*` costs 21 extra directories against 4,717 named `src`, and those
;;     21 are where a shadow-cljs repo keeps its ClojureScript.
;;
;; A scan that only knows one layout finds the copies that follow it, which is
;; the wrong thing to be reassured by.
;;
;; ## Which headers it recognises
;;
;; Five spellings are in use across the fleet. A regex that knows some of them
;; reports a subset and says nothing about the rest, which reads as a clean
;; bill of health for files it never opened. Measured 2026-08-12: knowing two
;; spellings found 24 of the 31 declared-vendored files.

(ns verify-vendored-copies
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as child]
            [clojure.string :as str]))

(def argv (vec (drop 2 js/process.argv)))
(def check? (some #{"--check"} argv))
(def show-diff? (some #{"--diff"} argv))

(defn- sh [cmd]
  (try (str (child/execSync cmd #js {:encoding "utf8"
                                     :stdio #js ["ignore" "pipe" "ignore"]
                                     :maxBuffer (* 8 1024 1024)}))
       (catch :default _ nil)))

(defn- source-file? [n]
  (some #(str/ends-with? n %) [".clj" ".cljc" ".cljs"]))

(defn- walk [dir]
  (letfn [(step [cur acc]
            (reduce (fn [acc e]
                      (let [n (.-name e) full (path/join cur n)]
                        (cond (str/starts-with? n ".") acc
                              (= n "node_modules") acc
                              (.isDirectory e) (step full acc)
                              (source-file? n) (conj acc full)
                              :else acc)))
                    acc
                    (js->clj (fs/readdirSync cur #js {:withFileTypes true}))))]
    (try (step dir []) (catch :default _ []))))

(defn- source-root?
  "`src`, or `src-cljs` / `src-clj` / `src-cljc` / `src-host` and friends.

  Not any directory that merely contains Clojure: widening to `test` or to
  every directory would turn a bounded scan into a full walk of 4,000 repos
  for no finding — every vendored copy in the fleet is under a `src*` root."
  [n]
  (or (= n "src") (str/starts-with? n "src-")))

(defn- src-dirs
  "Every source root under `root`, to a bounded depth.

  Depth-bounded rather than unbounded so one deep vendor tree cannot turn this
  into a full-disk walk; 6 covers `<repo>/<project>/<lang>/src` with room."
  [root]
  (letfn [(step [cur depth acc]
            (if (> depth 6)
              acc
              (reduce (fn [acc e]
                        (let [n (.-name e) full (path/join cur n)]
                          (cond
                            (not (.isDirectory e)) acc
                            (str/starts-with? n ".") acc
                            (#{"node_modules" "target" "out" "dist"} n) acc
                            (source-root? n) (conj acc full)
                            :else (step full (inc depth) acc))))
                      acc
                      (try (js->clj (fs/readdirSync cur #js {:withFileTypes true}))
                           (catch :default _ [])))))]
    (step root 0 [])))

(defn- org-names []
  (->> (try (js->clj (fs/readdirSync "orgs" #js {:withFileTypes true})) (catch :default _ []))
       (filter #(.isDirectory %))
       (map #(.-name %))
       vec))

(defn- checkouts []
  (->> (org-names)
       (mapcat (fn [o]
                 (->> (try (js->clj (fs/readdirSync (str "orgs/" o) #js {:withFileTypes true}))
                           (catch :default _ []))
                      (filter #(.isDirectory %))
                      (map #(str "orgs/" o "/" (.-name %))))))
       vec))

;; ---------------------------------------------------------------------------
;; Header spellings
;;
;; Five are in use, and each says a different subset of {source, path, pin}:
;;
;;  1 ;; VENDORED from kotoba-lang (pay/core.cljc), pay pinned at <sha>.
;;  2 ;; VENDORED from kotoba-lang/treasury (treasury/core.cljc, ADR-…),
;;    ;; pinned at commit <sha>.
;;  3 ;; VENDORED from kotoba-lang/kaiyu @ <sha> — do not edit here.
;;  4 ;; VENDORED, do not edit here — copied file-for-file from
;;    ;; kotoba-lang/kotoba src/kotoba/kami_host.cljc @ 367b4a141b94
;;  5 (inside the ns docstring)
;;    VENDORED, not a dependency: … copied from `kotobase-protocols`'
;;    `kotobase.protocols.json` …
;;
;; 3 names no path — it is inferred from where the copy itself sits under its
;; source root. 5 names no path and no org either: the "path" is a namespace
;; and the repo is bare, so it is resolved by looking for `orgs/*/<repo>`, and
;; there is no pin to check at all.
;;
;; 4 and 5 both continue onto a second line, so these run against the first 12
;; lines joined with "\n" and rely on `\s` and `[\s;]` crossing that newline
;; (and eating the `;;` that opens the continuation line).

(def ^:private paren-re
  #"VENDORED from\s+([A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?)\s*\(\s*([^,)]+)")

(def ^:private at-re
  #"VENDORED from\s+([A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?)\s*@\s*([0-9a-f]{7,40})")

(def ^:private copied-file-re
  #"copied\s+(?:file-for-file\s+)?from[\s;]+([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)\s+([A-Za-z0-9_./-]+\.cljc?s?)\s*@\s*([0-9a-f]{7,40})")

(def ^:private copied-ns-re
  #"copied\s+from[\s;]+`([A-Za-z0-9_.-]+)`'?\s*`([A-Za-z0-9_.-]+)`")

(def ^:private pin-re #"pinned at\s+(?:commit\s+)?([0-9a-f]{7,40})")

(defn- ns->path
  "`kotobase.protocols.json` + `.cljc` -> `kotobase/protocols/json.cljc`."
  [nsname ext]
  (str (-> nsname (str/replace "-" "_") (str/replace "." "/")) ext))

(defn- parse-header
  "The declared {source, kind, lib-path, pin} for one head, or nil.

  `head` is sliced to start at the VENDORED token, so a `copied from` sentence
  elsewhere in a file that merely mentions the word cannot be mistaken for a
  provenance claim. That case is real: wasm-webcomponent's `kami_ecs.cljs`
  documents the vendored file next to it, and must not be reported as one."
  [head rel-path ext]
  (when-let [i (str/index-of head "VENDORED")]
    (let [h (subs head i)
          pin (second (re-find pin-re h))]
      (or (when-let [[_ src p] (re-find paren-re h)]
            {:source src :kind :org-or-org-repo :lib-path (str/trim p) :pin pin})
          (when-let [[_ src sha] (re-find at-re h)]
            ;; No path named: the copy sits at the same path under its own
            ;; source root that it occupies upstream.
            {:source src :kind :org-or-org-repo :lib-path rel-path :pin sha})
          (when-let [[_ src p sha] (re-find copied-file-re h)]
            {:source src :kind :org-or-org-repo :lib-path p :pin sha})
          (when-let [[_ repo nsname] (re-find copied-ns-re h)]
            {:source repo :kind :repo-name :lib-path (ns->path nsname ext) :pin pin})))))

(defn- body
  "From the first `(ns ` form on. The header above it is provenance, not code."
  [text]
  (if-let [i (str/index-of text "\n(ns ")]
    (subs text (inc i))
    text))

(defn- vendored-header [src-dir file]
  (let [text (try (str (fs/readFileSync file "utf8")) (catch :default _ ""))
        head (str/join "\n" (take 12 (str/split-lines text)))]
    (when-let [decl (parse-header head (path/relative src-dir file) (path/extname file))]
      (assoc decl
             :file file
             ;; True when the VENDORED token is at or after the ns form, i.e.
             ;; the provenance lives in the docstring. `body` cannot subtract
             ;; it, so its own text shows up as drift.
             :in-ns-provenance? (let [n (str/index-of text "\n(ns ")]
                                  (or (nil? n) (> (str/index-of text "VENDORED") n)))
             :body (body text)))))

(defn- library-dir
  "`source` is `<org>` (repo inferred from the path's first segment), an
  explicit `<org>/<repo>`, or -- spelling 5 -- a bare repo name with no org,
  which is resolved by looking for it under each org."
  [source kind lib-path]
  (if (= kind :repo-name)
    (first (for [o (org-names)
                 :let [d (str "orgs/" o "/" source)]
                 :when (fs/existsSync d)]
             d))
    (let [dir (if (str/includes? source "/")
                (str "orgs/" source)
                (str "orgs/" source "/" (first (str/split lib-path #"/"))))]
      (when (fs/existsSync dir) dir))))

(defn- upstream-candidates
  "The paths the library file could be at, given what the header wrote.

  Several spellings are in use and each puts something different where the
  path goes:

    (pay/core.cljc)          -> src/pay/core.cljc
    (src/i18n/core.cljc)     -> src/i18n/core.cljc      (already rooted)
    (core.cljc)              -> src/<lib>/core.cljc     (bare file name)

  Building `\"src/\" + captured` unconditionally -- which is what this did at
  first -- turns the second into `src/src/i18n/core.cljc` and the third into
  `src/core.cljc`. Both miss, `git show` returns nothing, and the copy was
  reported STALE. Four copies were labelled that way while being byte-identical
  to HEAD. A scan that cannot tell `drifted` from `not looked at` is worse than
  no scan, because the wrong label is the confident one."
  [source kind lib-path]
  (let [lib (if (or (= kind :repo-name) (not (str/includes? source "/")))
              (first (str/split lib-path #"/"))
              (last (str/split source #"/")))]
    (distinct
     [(if (str/starts-with? lib-path "src/") lib-path (str "src/" lib-path))
      (str "src/" lib "/" lib-path)
      lib-path])))

(defn- resolve-upstream
  "{:path :body} for the first candidate git can actually show at `rev`."
  [dir rev source kind lib-path]
  (some (fn [candidate]
          (when-let [t (sh (str "git -C " dir " show " rev ":" candidate))]
            {:path candidate :body (body t)}))
        (upstream-candidates source kind lib-path)))

(defn- drift-lines
  "How many lines differ, by diff(1). The label STALE says only that a copy is
  not byte-identical; the size says whether that is a rename or a rewrite."
  [a b]
  (when (and a b (not= a b))
    (let [d (os/tmpdir)
          pid js/process.pid
          fa (path/join d (str "vendored-a-" pid ".txt"))
          fb (path/join d (str "vendored-b-" pid ".txt"))]
      (fs/writeFileSync fa a)
      (fs/writeFileSync fb b)
      (->> (str/split-lines (or (sh (str "diff " fa " " fb " || true")) ""))
           (filter #(re-find #"^[<>]" %))
           count))))

(defn- commits-since
  "How many commits touched the upstream file since the declared pin."
  [dir pin upstream-path]
  (when (and dir pin upstream-path)
    (some-> (sh (str "git -C " dir " rev-list --count " pin "..HEAD -- " upstream-path))
            str/trim
            not-empty)))

(defn- def-names [text]
  (into #{} (map second) (re-seq #"(?m)^\(def[a-z-]*\s+([^\s\[(]+)" (or text ""))))

(defn -main []
  (let [copies (->> (checkouts)
                    (mapcat src-dirs)
                    (mapcat (fn [d] (map (fn [f] [d f]) (walk d))))
                    (keep (fn [[d f]] (vendored-header d f)))
                    (sort-by :file)
                    vec)
        results
        (for [{:keys [source kind lib-path pin body] :as c} copies
              :let [dir (library-dir source kind lib-path)
                    head-up (when dir (resolve-upstream dir "HEAD" source kind lib-path))
                    pin-up (when (and dir pin) (resolve-upstream dir pin source kind lib-path))
                    head-body (:body head-up)]]
          (assoc c
                 :library dir
                 :upstream-path (:path head-up)
                 :pin-honest? (when pin-up (= body (:body pin-up)))
                 :current? (when head-body (= body head-body))
                 :drift (drift-lines body head-body)
                 :behind (commits-since dir pin (:path head-up))
                 :missing-defs (when head-body
                                 (sort (remove (def-names body) (def-names head-body))))
                 :extra-defs (when head-body
                               (sort (remove (def-names head-body) (def-names body))))))
        ;; A copy whose upstream could not be read is UNRESOLVED, not stale.
        ;; Collapsing the two is what let four byte-identical copies be
        ;; reported as drifted.
        unresolved-body (filter #(nil? (:current? %)) results)
        stale (filter #(false? (:current? %)) results)
        dishonest (filter #(false? (:pin-honest? %)) results)
        no-pin (remove :pin results)
        unresolved (remove :library results)]
    (println (str (count copies) " vendored file(s) found across "
                  (count (distinct (map #(second (str/split (:file %) #"/" 3)) copies)))
                  " org(s)"))
    (println (str (count (filter :current? results)) " current, "
                  (count stale) " stale, "
                  (count unresolved-body) " UNRESOLVED (upstream file not found -- "
                  "not a drift finding), "
                  (count dishonest) " do NOT match the commit their header claims"))
    (println (str (count no-pin) " declare no pin at all -- for those the CURRENT "
                  "axis is the only one there is."))
    (println)
    (doseq [{:keys [file library pin pin-honest? current? drift behind upstream-path
                    in-ns-provenance? missing-defs extra-defs]} results]
      (println (str (cond (not library) "NO-LIBRARY "
                          (true? current?) "current    "
                          (nil? current?) "UNRESOLVED "
                          :else "STALE      ")
                    file))
      (println (str "             library: " (or library "not checked out")
                    "   pin: " (if pin (subs pin 0 (min 8 (count pin))) "none declared")
                    (cond (nil? pin) "  (no pin declared -- CURRENT axis only)"
                          (true? pin-honest?) "  (matches its pin)"
                          (false? pin-honest?) "  (DOES NOT MATCH ITS PIN)"
                          :else "  (pin unverifiable)")))
      (when (or drift behind)
        (println (str "             "
                      (when drift (str "drift vs HEAD: " drift " line(s)"))
                      (when (and drift behind) "   ")
                      (when behind (str behind " upstream commit(s) to "
                                        upstream-path " since the pin")))))
      (when (and drift in-ns-provenance?)
        (println (str "             note: this copy's provenance is inside the ns "
                      "docstring, so the header itself counts toward that drift.")))
      (when (and show-diff? (seq missing-defs))
        (println (str "             missing vs HEAD: " (str/join " " missing-defs))))
      (when (and show-diff? (seq extra-defs))
        (println (str "             not in HEAD:     " (str/join " " extra-defs)))))
    (println)
    (println (str "A stale copy is not automatically a defect -- vendoring is a "
                  "deliberate snapshot. A copy that does not match its own pin is."))
    (when (seq unresolved)
      (println (str (count unresolved) " could not be checked: the library repo is "
                    "not checked out here.")))
    (when (and check? (or (seq dishonest) (seq stale) (seq unresolved-body)))
      (set! (.-exitCode js/process) 1))))

(-main)

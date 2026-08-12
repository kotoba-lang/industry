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
;; Comparison starts at the first `(ns ` form, so the vendored header itself is
;; not counted as drift. Everything before it is provenance, not code.
;;
;; ## Where it looks
;;
;; Every directory named `src` within a repo, not just `<repo>/src` — the first
;; version of this scan looked only at the top level and missed two copies,
;; including the one ADR-2608121000 calls the worst in the fleet. They live in
;; nested sub-projects (`<repo>/kotobase-api-gateway-cljs/src`,
;; `<repo>/appview/<name>/cljs/src`). A scan that only knows one layout finds
;; the copies that follow it, which is the wrong thing to be reassured by.

(ns verify-vendored-copies
  (:require ["node:fs" :as fs]
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

(defn- src-dirs
  "Every directory named `src` under `root`, to a bounded depth.

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
                            (= n "src") (conj acc full)
                            :else (step full (inc depth) acc))))
                      acc
                      (try (js->clj (fs/readdirSync cur #js {:withFileTypes true}))
                           (catch :default _ [])))))]
    (step root 0 [])))

(defn- checkouts []
  (->> (try (js->clj (fs/readdirSync "orgs" #js {:withFileTypes true})) (catch :default _ []))
       (filter #(.isDirectory %))
       (mapcat (fn [org]
                 (let [o (.-name org)]
                   (->> (try (js->clj (fs/readdirSync (str "orgs/" o) #js {:withFileTypes true}))
                             (catch :default _ []))
                        (filter #(.isDirectory %))
                        (map #(str "orgs/" o "/" (.-name %)))))))
       vec))

(def ^:private header-re
  ;; Two spellings are in use, and a regex that knows only one silently misses
  ;; the other -- which is how this scan first reported 15 files instead of 19:
  ;;
  ;;   ;; VENDORED from kotoba-lang (pay/core.cljc), pay pinned at <sha>.
  ;;   ;; VENDORED from kotoba-lang/treasury (treasury/core.cljc, ADR-...),
  ;;   ;; pinned at commit <sha>.
  ;;
  ;; So the source may be `<org>` or `<org>/<repo>`, and the parenthesised part
  ;; may carry extra comma-separated notes after the path.
  #"VENDORED from\s+([A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?)\s*\(\s*([^,)]+)")

(def ^:private pin-re #"pinned at\s+(?:commit\s+)?([0-9a-f]{7,40})")

(defn- body
  "From the first `(ns ` form on. The header above it is provenance, not code."
  [text]
  (if-let [i (str/index-of text "\n(ns ")]
    (subs text (inc i))
    text))

(defn- vendored-header [file]
  (let [text (try (str (fs/readFileSync file "utf8")) (catch :default _ ""))
        head (str/join "\n" (take 12 (str/split-lines text)))]
    (when-let [[_ org lib-path] (re-find header-re head)]
      {:file file
       :org org
       :lib-path (str/trim lib-path)
       :pin (second (re-find pin-re head))
       :body (body text)})))

(defn- library-dir
  "`source` is either `<org>` (repo inferred from the path's first segment) or
  an explicit `<org>/<repo>`."
  [source lib-path]
  (let [dir (if (str/includes? source "/")
              (str "orgs/" source)
              (str "orgs/" source "/" (first (str/split lib-path #"/"))))]
    (when (fs/existsSync dir) dir)))

(defn- upstream-candidates
  "The paths the library file could be at, given what the header wrote.

  Three spellings are in use and each puts something different in the parens:

    (pay/core.cljc)          -> src/pay/core.cljc
    (src/i18n/core.cljc)     -> src/i18n/core.cljc      (already rooted)
    (core.cljc)              -> src/<lib>/core.cljc     (bare file name)

  Building `\"src/\" + captured` unconditionally -- which is what this did at
  first -- turns the second into `src/src/i18n/core.cljc` and the third into
  `src/core.cljc`. Both miss, `git show` returns nothing, and the copy was
  reported STALE. Four copies were labelled that way while being byte-identical
  to HEAD. A scan that cannot tell `drifted` from `not looked at` is worse than
  no scan, because the wrong label is the confident one."
  [source lib-path]
  (let [lib (if (str/includes? source "/")
              (last (str/split source #"/"))
              (first (str/split lib-path #"/")))]
    (distinct
     [(if (str/starts-with? lib-path "src/") lib-path (str "src/" lib-path))
      (str "src/" lib "/" lib-path)
      lib-path])))

(defn- upstream-body [dir rev source lib-path]
  (some (fn [candidate]
          (some-> (sh (str "git -C " dir " show " rev ":" candidate)) body))
        (upstream-candidates source lib-path)))

(defn- def-names [text]
  (into #{} (map second) (re-seq #"(?m)^\(def[a-z-]*\s+([^\s\[(]+)" (or text ""))))

(defn -main []
  (let [copies (->> (checkouts)
                    (mapcat src-dirs)
                    (mapcat walk)
                    (keep vendored-header)
                    (sort-by :file)
                    vec)
        results
        (for [{:keys [file org lib-path pin body] :as c} copies
              :let [dir (library-dir org lib-path)
                    head-body (when dir (upstream-body dir "HEAD" org lib-path))
                    pin-body (when (and dir pin) (upstream-body dir pin org lib-path))]]
          (assoc c
                 :library dir
                 :pin-honest? (when pin-body (= body pin-body))
                 :current? (when head-body (= body head-body))
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
        unresolved (remove :library results)]
    (println (str (count copies) " vendored file(s) found across "
                  (count (distinct (map #(second (str/split (:file %) #"/" 3)) copies)))
                  " org(s)"))
    (println (str (count (filter :current? results)) " current, "
                  (count stale) " stale, "
                  (count unresolved-body) " UNRESOLVED (upstream file not found -- "
                  "not a drift finding), "
                  (count dishonest) " do NOT match the commit their header claims"))
    (println)
    (doseq [{:keys [file library pin pin-honest? current? missing-defs extra-defs]} results]
      (println (str (cond (not library) "NO-LIBRARY "
                          (true? current?) "current    "
                          (nil? current?) "UNRESOLVED "
                          :else "STALE      ")
                    file))
      (println (str "             library: " (or library "not checked out")
                    "   pin: " (if pin (subs pin 0 (min 8 (count pin))) "none declared")
                    (case pin-honest?
                      true "  (matches its pin)"
                      false "  (DOES NOT MATCH ITS PIN)"
                      "  (pin unverifiable)")))
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

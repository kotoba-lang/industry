#!/usr/bin/env nbb
;; scripts/fix-appview-page-summary.cljs — rewrite the landing page's embedded
;; summary from the wrangler config it claims to report.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/fix-appview-page-summary.cljs \
;;     [--root <orgs-bearing checkout>] [--only <repo path>] [--write]
;;
;; Dry-run by default. `--write` edits files and nothing else: it does not stage,
;; commit, push, or advance a pin. Landing the change stays a human decision, and one
;; repository at a time is a legitimate way to use this.
;;
;; ## What it is for
;;
;; `scripts/verify-appview-page-summary.cljs` enumerates the pages whose embedded
;; summary disagrees with their `wrangler.jsonc` — measured 2026-08-16, 352 findings
;; over 122 pages, most of them rendering "No public route is declared next to this app
;; surface" at an address wrangler declares. Three repositories were then repaired by
;; hand (app-air-sched, app-air-yield, app-cowork), and writing the same paragraph into
;; three quickstarts made the shape of the problem obvious: **there is no generator for
;; that object**, so every repair is manual and every future wrangler edit re-opens it.
;;
;; This is the missing generator, kept deliberately small: it re-derives four fields
;; and touches nothing else.
;;
;;   routeCount     (count routes)
;;   routes         the route patterns, in declared order
;;   vars           the var NAMES, sorted. Never values -- the page prints keys, and
;;                  a var block can carry a URL or a tenant id
;;   relativePath   the path within this repository, not the monorepo it came from
;;
;; ## Exact match or refuse
;;
;; Measured before writing this: of 163 west-registered candidate pages, 122 carry the
;; summary and **all 122 use the identical canonical block layout** — there is no
;; second shape. So the matcher is strict and anything else is refused rather than
;; guessed at, which is the only safe way to rewrite source with a regex.
;;
;; It also refuses, and says which:
;;
;;   - a repository absent from `manifest/west.yml`. Measured on the detector's first
;;     run: 42 of 157 repositories it named are not in the roster, nine of them
;;     pre-rename `etzhayyim/com-etzhayyim-app-*` checkouts left after an org move.
;;     Editing an orphan is work nobody will ever merge.
;;   - a dirty working tree. Another session's uncommitted edit must never be swept
;;     into a mechanical rewrite.
;;   - an unparsable or absent wrangler config: with no authority there is nothing to
;;     derive from, and writing zeros would be inventing the very claim being fixed.
;;
;; Exit codes: 0 nothing to change · 1 changes needed (or made) · 2 COULD NOT ANSWER.

(ns fix-appview-page-summary
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.string :as str]))

(def args (vec (drop 2 (js->clj js/process.argv))))
(def write? (boolean (some #{"--write"} args)))
(def root (or (second (drop-while #(not= "--root" %) args)) (.cwd js/process)))
(def only (second (drop-while #(not= "--only" %) args)))

;; Measured 2026-08-16: 225 `+page.svelte` files exist under orgs/. As in the detector,
;; the floor is on the INPUT rather than on what survives filtering, so that repairing
;; pages -- which is the point of this script -- can never make it answer CANNOT ANSWER.
(def floor-candidates 150)

(defn- read-file [p] (try (str (fs/readFileSync p "utf8")) (catch :default _ nil)))
(defn- exists? [p] (try (fs/existsSync p) (catch :default _ false)))
(defn- dir-entries [p] (try (vec (js->clj (fs/readdirSync p))) (catch :default _ [])))
(defn- die [code msg] (println msg) (js/process.exit code))

(def west-paths
  (let [s (read-file (path/join root "manifest" "west.yml"))]
    (if s (into #{} (map second) (re-seq #"path:\s*(orgs/\S+)" s)) #{})))

(defn- git-dirty?
  "nil when git cannot answer -- treated as `do not touch`, since a repository whose
  state cannot be read is not one to rewrite."
  [dir]
  (let [r (.spawnSync cp "git" (clj->js ["-C" dir "status" "--porcelain"])
                      #js {:encoding "utf8"})]
    (when (number? (.-status r))
      (if (zero? (.-status r))
        (not (str/blank? (str (.-stdout r))))
        nil))))

(defn- pages []
  (let [orgs-dir (path/join root "orgs")]
    (vec (for [org (dir-entries orgs-dir)
               repo (dir-entries (path/join orgs-dir org))
               base (concat [(path/join orgs-dir org repo)]
                            (let [av (path/join orgs-dir org repo "appview")]
                              (map #(path/join av %) (dir-entries av))))
               :let [pg (path/join base "svelte" "src" "routes" "+page.svelte")]
               :when (exists? pg)]
           {:page pg :repo (str "orgs/" org "/" repo) :base base
            :repo-dir (path/join orgs-dir org repo)}))))

(defn- nearest-wrangler [base]
  (loop [d base n 0]
    (when (and d (< n 4) (not= (path/basename d) "orgs"))
      (or (first (for [nm ["wrangler.jsonc" "wrangler.json"]
                       :let [p (path/join d nm)] :when (exists? p)] p))
          (recur (path/dirname d) (inc n))))))

(defn- strip-jsonc
  "// comments, but only outside strings -- the vars carry https:// URLs."
  [s]
  (str/join "\n"
            (for [line (str/split-lines s)]
              (loop [i 0 in? false esc? false]
                (cond
                  (>= i (count line)) line
                  esc? (recur (inc i) in? false)
                  (= \\ (nth line i)) (recur (inc i) in? true)
                  (= \" (nth line i)) (recur (inc i) (not in?) false)
                  (and (not in?) (= "//" (subs line i (min (count line) (+ i 2)))))
                  (subs line 0 i)
                  :else (recur (inc i) in? false))))))

;; The one canonical block. All 122 pages carrying a summary match this exactly.
(def block-re
  #"(?m)^  \"routeCount\": (\d+),\n  \"routes\": (\[[^\]]*\]),\n  \"vars\": (\[[^\]]*\]),\n  \"xrpc\": (\w+),\n  \"relativePath\": \"([^\"]*)\"")

(defn- json-str
  "`[\"a\", \"b\"]` -- a space after each comma, matching the three pages repaired by
  hand before this script existed. `JSON.stringify` omits that space, and emitting the
  other style would have made every already-correct page look like a change: the first
  run reported `already-correct=0` and offered to rewrite app-air-sched from
  `routeCount 2` to `routeCount 2`. Churn is not a fix, and a formatter pretending to
  be one is worse than no script."
  [xs]
  (str "[" (str/join ", " (map #(js/JSON.stringify %) xs)) "]"))

(defn- parse-strings
  "The string literals inside a `[\"a\", \"b\"]` source fragment, so the comparison is
  over VALUES and not over whitespace."
  [frag]
  (mapv #(second (re-find #"\"(.*)\"" %)) (re-seq #"\"[^\"]*\"" (or frag ""))))

(let [ps (pages)]
  (when (< (count ps) floor-candidates)
    (die 2 (str "CANNOT ANSWER: found " (count ps) " candidate pages under "
                (path/join root "orgs") ", floor " floor-candidates
                ". Wrong --root, or orgs/ is not populated.")))
  (when (empty? west-paths)
    (die 2 (str "CANNOT ANSWER: no `path:` line read from "
                (path/join root "manifest" "west.yml") ". Without the roster this"
                " cannot tell a managed repository from a leftover directory.")))
  (let [counted (atom 0) already (atom 0) changed (atom []) refused (atom [])]
    (doseq [{:keys [page repo base repo-dir]} ps
            :when (or (nil? only) (= only repo))]
      (let [s (read-file page)]
        (cond
          (not (contains? west-paths repo))
          (when only (swap! refused conj [repo "not in manifest/west.yml"]))

          (or (nil? s) (not (str/includes? s "routeCount"))) nil ; no summary: not ours

          :else
          (let [w (nearest-wrangler base)
                cfg (when w (try (js->clj (js/JSON.parse (strip-jsonc (read-file w))))
                                 (catch :default _ nil)))
                dirty (git-dirty? repo-dir)
                m (re-find block-re s)]
            (swap! counted inc)
            (cond
              (nil? w) (swap! refused conj [repo "no wrangler config beside the page"])
              (nil? cfg) (swap! refused conj [repo (str "unparsable " (path/basename w))])
              (nil? dirty) (swap! refused conj [repo "git could not report the tree state"])
              dirty (swap! refused conj [repo "working tree is dirty -- another session may be editing it"])
              (nil? m) (swap! refused conj [repo "the summary block is not the canonical layout; refusing to rewrite by guess"])
              :else
              (let [[whole rc routes-s vars-s xrpc rel] m
                    routes (mapv #(get % "pattern") (get cfg "routes" []))
                    var-names (vec (sort (keys (get cfg "vars" {}))))
                    want-rel (str/replace page (re-pattern (str "^" repo-dir "/")) "")
                    want (str "  \"routeCount\": " (count routes) ",\n"
                              "  \"routes\": " (json-str routes) ",\n"
                              "  \"vars\": " (json-str var-names) ",\n"
                              "  \"xrpc\": " xrpc ",\n"
                              "  \"relativePath\": \"" want-rel "\"")
                    ;; VALUES, not bytes. See json-str.
                    same? (and (= (js/parseInt rc) (count routes))
                               (= (parse-strings routes-s) routes)
                               (= (parse-strings vars-s) var-names)
                               (= rel want-rel))]
                (if same?
                  (swap! already inc)
                  (do (swap! changed conj
                             [repo (str "routeCount " rc "->" (count routes)
                                        ", vars " (count (re-seq #"\"[^\"]+\"" vars-s))
                                        "->" (count var-names)
                                        (when (not= rel want-rel) (str ", relativePath -> " want-rel)))])
                      (when write?
                        (fs/writeFileSync page (str/replace s whole want) "utf8"))))))))))
    (println (str "SCANNED\t" @counted "\tappview-page-summary-fix"))
    (println (str "candidates=" (count ps) " examined=" @counted
                  " already-correct=" @already
                  " " (if write? "written=" "would-change=") (count @changed)
                  " refused=" (count @refused)))
    (doseq [[repo detail] @refused] (println (str "  REFUSED " repo " -- " detail)))
    (doseq [[repo detail] @changed]
      (println (str "  " (if write? "WROTE   " "WOULD   ") repo " -- " detail)))
    (when-not write?
      (println "\nDry run. Nothing was written. Pass --write to edit, then review and land the diff yourself:"))
    (js/process.exit (if (seq @changed) 1 0))))

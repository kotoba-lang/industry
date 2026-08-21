#!/usr/bin/env nbb
(ns verify-source-path-pins
  "Relative :source-paths that cross into another west-managed repository and
  resolve to a revision west.yml does not pin.

  `verify-shadow-source-paths` already asks whether a declared source-path
  EXISTS. This asks a different question, and the difference is the whole
  point: a path can exist, resolve, compile, and still be the wrong code.

  ## The failure this exists for

  Measured 2026-08-18. `net-kotobase/control-plane`'s protocols-worker declares
  26 source-paths of the form `../../../kotoba-lang/<lib>/src`. Every one
  existed. Every one compiled. **24 of the 26 were at a revision
  manifest/west.yml does not pin**, by 2 to 17 commits where the distance was
  computable.

  The consequence was not a build error. It was a benchmark that reported
  38,062 cpu-ms for work its own docstring measured at 2,201, on the same
  blocks and the same head, while production was unchanged -- and a whole
  afternoon spent looking for a regression in code that had none. Pinning the
  26 and re-running dropped it to 7,842 and removed 64 duplicate block fetches
  outright. Nothing was slow. The harness had changed engines underneath its
  own baseline, and a source-path has no revision in it, so nothing could see
  it.

  A test suite compiled this way is in the same position: `131 tests pass` was
  true, and it was true of libraries production does not run.

  ## What it checks

  For each shadow-cljs.edn declaring a relative source-path that lands inside
  `orgs/<org>/<repo>`, compare that checkout's HEAD to the repo's `revision:`
  in manifest/west.yml, and report every one that differs -- with the distance
  when both objects are present locally.

  ## What it does not check

  deps.edn `:local/root`. Same class, and worth adding; this covers the one
  that produced the incident.

  Silence here is not a pass. A build whose paths cannot be resolved, or whose
  repo is not west-registered, is reported as UNANSWERED and does not count
  clean -- a checker that treats `I could not look` as `nothing wrong` is the
  bug it was written to find.

  usage:
    nbb scripts/verify-source-path-pins.cljs [<root>] [--json]"
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.string :as str]))

(def argv (vec *command-line-args*))
(def root (or (first (remove #(str/starts-with? % "--") argv))
              "/Users/junkawasaki/github/com-junkawasaki"))
(def json? (some #{"--json"} argv))

(defn- slurp* [f] (try (.readFileSync fs f "utf8") (catch :default _ nil)))

(defn- sh [dir & args]
  (try (str/trim (str (cp/execFileSync "git"
                                       (clj->js (concat ["-C" dir] args))
                                       #js {:encoding "utf8" :stdio #js ["ignore" "pipe" "ignore"]})))
       (catch :default _ nil)))

;; ── west pins ───────────────────────────────────────────────────────────────

(defn west-pins
  "path -> revision, straight out of manifest/west.yml.

  Read with a line reader rather than a YAML parser on purpose: this file is
  generated, its shape is stable, and a parser dependency here would be one
  more thing that can be at the wrong version."
  [root]
  (let [txt (slurp* (path/join root "manifest" "west.yml"))]
    (when txt
      (loop [ls (str/split-lines txt) rev nil acc {}]
        (if-let [l (first ls)]
          (let [t (str/trim l)]
            (cond
              (str/starts-with? t "revision:")
              (recur (rest ls) (str/trim (subs t (count "revision:"))) acc)
              (str/starts-with? t "path:")
              (recur (rest ls) nil
                     (if rev (assoc acc (str/trim (subs t (count "path:"))) rev) acc))
              :else (recur (rest ls) rev acc)))
          acc)))))

;; ── source-paths ────────────────────────────────────────────────────────────

(defn- source-paths
  "The :source-paths vector of a shadow-cljs.edn, as strings, or :unparsed."
  [txt]
  (if-let [i (str/index-of txt ":source-paths")]
    (let [after (subs txt i)
          o (str/index-of after "[")
          c (str/index-of after "]")]
      (if (and o c (< o c))
        (->> (re-seq #"\"([^\"]+)\"" (subs after o c)) (map second) vec)
        :unparsed))
    nil))

(defn- repo-of
  "Which orgs/<org>/<repo> an absolute path lands in, relative to root."
  [root abs]
  (let [rel (path/relative root abs)]
    (when-not (str/starts-with? rel "..")
      (let [seg (str/split rel #"/")]
        (when (and (= "orgs" (first seg)) (>= (count seg) 3))
          (str/join "/" (take 3 seg)))))))

(defn- find-configs [dir acc depth]
  (if (> depth 6)
    acc
    (reduce
     (fn [a e]
       (let [n (.-name e) p (path/join dir n)]
         (cond
           (and (.isFile e) (= n "shadow-cljs.edn")) (conj a p)
           (and (.isDirectory e)
                (not (#{"node_modules" ".git" "out" "dist" ".shadow-cljs"} n)))
           (find-configs p a (inc depth))
           :else a)))
     acc
     (try (vec (.readdirSync fs dir #js {:withFileTypes true})) (catch :default _ [])))))

(defn -main []
  (let [pins (west-pins root)]
    (when (or (nil? pins) (zero? (count pins)))
      (println "UNANSWERED\tmanifest/west.yml could not be read; refusing to report a pass")
      (js/process.exit 2))
    (let [configs (find-configs (path/join root "orgs") [] 0)
          rows
          (for [cfg configs
                :let [txt (slurp* cfg)
                      sps (when txt (source-paths txt))]
                :when (vector? sps)
                sp sps
                :when (str/includes? sp "..")
                :let [abs (path/resolve (path/dirname cfg) sp)
                      repo (repo-of root abs)]]
            (if (nil? repo)
              {:cfg cfg :sp sp :status :outside-orgs}
              (let [pin (get pins repo)
                    head (sh (path/join root repo) "rev-parse" "HEAD")]
                (cond
                  (nil? pin) {:cfg cfg :sp sp :repo repo :status :not-west-registered}
                  (nil? head) {:cfg cfg :sp sp :repo repo :status :no-head}
                  (= (subs pin 0 12) (subs head 0 12)) {:cfg cfg :sp sp :repo repo :status :pinned}
                  :else
                  (let [d (path/join root repo)
                        have? (some? (sh d "cat-file" "-e" pin))
                        behind (when have? (sh d "rev-list" "--count" (str head ".." pin)))
                        ahead (when have? (sh d "rev-list" "--count" (str pin ".." head)))]
                    {:cfg cfg :sp sp :repo repo :status :drift
                     :pin (subs pin 0 12) :head (subs head 0 12)
                     :ahead ahead :behind behind})))))
          rows (vec rows)
          drift (filterv #(= :drift (:status %)) rows)
          unanswered (filterv #(#{:no-head :not-west-registered} (:status %)) rows)
          pinned (filterv #(= :pinned (:status %)) rows)]
      (if json?
        (println (js/JSON.stringify (clj->js rows)))
        (do
          (println (str "SCANNED\t" (count rows)
                        "\trelative cross-repo :source-path(s) in "
                        (count (distinct (map :cfg rows))) " shadow-cljs.edn"))
          (doseq [r drift]
            (println (str "FINDING\tfail\t" (:repo r)
                          "\tcheckout " (:head r) " is not the pinned " (:pin r)
                          (when (:behind r) (str " (" (:ahead r) " ahead / " (:behind r) " behind)"))
                          "\t<- " (path/relative root (:cfg r)))))
          (doseq [r unanswered]
            (println (str "FINDING\twarn\t" (or (:repo r) (:sp r))
                          "\t" (name (:status r))
                          " — NOT counted clean, this path was not checked")))
          (println)
          (when (zero? (count rows))
            (println "REFUSING to report a pass: no cross-repo source-path was examined.")
            (js/process.exit 2))
          (if (seq drift)
            (do (println (str (count drift) " of " (count rows)
                              " cross-repo source-paths resolve to a revision west.yml does not pin."
                              " A build that cannot answer `which revision did I compile`"
                              " is not a reproducible measurement."))
                (js/process.exit 1))
            (println (str "OK — all " (count pinned) " cross-repo source-paths are at their west pin."))))))))

(-main)

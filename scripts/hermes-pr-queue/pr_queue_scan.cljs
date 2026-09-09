#!/usr/bin/env nbb
;; Decision-free measurement of the fleet's open PR queue.
;;
;; It decides nothing. It measures the queue the drain bots (pr-cleanup,
;; kotoba-merger) work on, so an agent run does not spend its whole budget
;; discovering state -- the failure that left 146 of our own PRs open on
;; 2026-09-05 while the drain jobs were being killed mid-discovery.
;;
;; Evidence floor (CLAUDE.md, ADR-2608136000): a query that could not run is
;; never reported as an empty queue. Any failed query -> exit 2 (neither pass
;; nor fail: "could not answer"), and the refusal names what failed.
;;
;; Usage:
;;   nbb scripts/hermes-pr-queue/pr_queue_scan.cljs [--author LOGIN|any]
;;       [--orgs a,b,c] [--cap N] [--work N] [--containment N] [--include-others]
(ns pr-queue-scan
  (:require ["child_process" :as cp]
            [clojure.string :as str]))

(def ^:private argv (vec (drop 2 (js->clj (.-argv js/process)))))

(defn- flag [name default]
  (if-let [i (first (keep-indexed #(when (= %2 (str "--" name)) %1) argv))]
    (or (get argv (inc i)) default)
    default))

(def AUTHOR      (flag "author" "com-junkawasaki"))
(def ORGS        (str/split (flag "orgs" "cloud-itonami,network-awai,kotoba-lang,net-kotobase,gftdcojp,etzhayyim,com-junkawasaki") #","))
(def CAP         (js/parseInt (flag "cap" "5")))
(def WORK-LIMIT  (js/parseInt (flag "work" "25")))
(def CONTAINMENT (js/parseInt (flag "containment" "40")))

(def failures (atom []))
(defn- fail! [what] (swap! failures conj what) nil)

(defn- gh [args]
  (try
    {:ok true :out (cp/execFileSync "gh" (clj->js args)
                                    #js {:encoding "utf8" :maxBuffer 64000000 :timeout 180000
                                         :stdio #js ["ignore" "pipe" "pipe"]})}
    (catch :default e
      {:ok false :err (str (or (some-> (.-stderr e) str) (.-message e)))})))

(defn- gh-json [args what]
  (let [r (gh args)]
    (if-not (:ok r)
      (fail! (str what " :: " (str/replace (or (:err r) "") #"\s+" " ")))
      (try (js->clj (js/JSON.parse (:out r)) :keywordize-keys true)
           (catch :default _ (fail! (str what " :: unparseable JSON")))))))

(def ^:private QUERY "
query($q:String!,$after:String){
  search(query:$q, type:ISSUE, first:50, after:$after){
    issueCount
    pageInfo{hasNextPage endCursor}
    nodes{ ... on PullRequest {
      number title createdAt updatedAt isDraft mergeable
      headRefName headRefOid baseRefName
      author{login}
      repository{nameWithOwner}
      commits(last:1){nodes{commit{statusCheckRollup{state}}}}
      files(first:100){totalCount nodes{path}}
    }}
  }
}")

(defn- search-org [org]
  (loop [after nil acc [] guard 0]
    (let [args (cond-> ["api" "graphql" "-f" (str "query=" QUERY)
                        "-F" (str "q=is:pr is:open org:" org
                                  (when (not= AUTHOR "any") (str " author:" AUTHOR)))]
                 after (into ["-F" (str "after=" after)]))
          d    (gh-json args (str "search org:" org))
          s    (get-in d [:data :search])]
      (cond
        (nil? s) acc
        :else
        (let [nodes (remove #(contains? #{"gftdcojp/241001-lifescience-web" "kotoba-lang/kotoba-v2025"} (get-in % [:repository :nameWithOwner])) (:nodes s))
              acc' (into acc nodes)
              pi   (:pageInfo s)]
          (if (and (:hasNextPage pi) (< guard 20))
            (recur (:endCursor pi) acc' (inc guard))
            acc'))))))

;; ---- content containment (is this PR's content already in the base branch?) ----
(def tree-cache (atom {}))

(defn- tree [repo rev]
  (let [k [repo rev]]
    (if-let [c (get @tree-cache k)]
      c
      (let [d (gh-json ["api" (str "repos/" repo "/git/trees/" rev "?recursive=1")]
                       (str "tree " repo "@" rev))
            v (when d
                (if (:truncated d)
                  :truncated
                  (into {} (for [e (:tree d) :when (= "blob" (:type e))] [(:path e) (:sha e)]))))]
        (swap! tree-cache assoc k v)
        v))))

(defn- containment
  "all-in-base | differs | unknown -- blob-sha equality for every path the PR touches."
  [pr]
  (let [repo  (get-in pr [:repository :nameWithOwner])
        files (get-in pr [:files :nodes])]
    (cond
      (> (get-in pr [:files :totalCount] 0) 100) "unknown:>100-files"
      (empty? files)                             "unknown:no-files"
      :else
      (let [base (tree repo (:baseRefName pr))
            head (tree repo (:headRefOid pr))]
        (cond
          (or (nil? base) (nil? head))                 "unknown:tree-unavailable"
          (or (= :truncated base) (= :truncated head)) "unknown:tree-truncated"
          :else
          (if (every? (fn [f] (let [p (:path f)] (and (get base p) (= (get base p) (get head p)))))
                      files)
            "all-in-base" "differs"))))))

;; ---- classification ----
(defn- age-days [pr]
  (let [t (js/Date.parse (:createdAt pr))]
    (js/Math.floor (/ (- (js/Date.now) t) 86400000))))

(defn- checks [pr]
  (or (get-in pr [:commits :nodes 0 :commit :statusCheckRollup :state]) "none"))

(defn- klass [pr]
  (let [m (:mergeable pr) c (checks pr)]
    (cond
      ;; a draft is not "not yet merged", it is "not offered for merge" -- the drain
      ;; must not merge it, so it is its own class and is ranked last.
      (:isDraft pr)                              :draft
      (= m "CONFLICTING")                        :dirty
      (= m "UNKNOWN")                            :unknown
      (contains? #{"FAILURE" "ERROR"} c)         :red
      (= c "PENDING")                            :pending
      :else                                      :clean)))

(defn- line [pr k extra]
  (str/join "\t"
            [(str (get-in pr [:repository :nameWithOwner]) "#" (:number pr))
             (str "age=" (age-days pr) "d")
             (str "class=" (name k))
             (str "mergeable=" (:mergeable pr))
             (str "checks=" (checks pr))
             (str "draft=" (if (:isDraft pr) "yes" "no"))
             (str "author=" (get-in pr [:author :login]))
             (str "head=" (:headRefName pr))
             (str "files=" (get-in pr [:files :totalCount] 0))
             extra
             (str "title=" (subs (or (:title pr) "") 0 (min 60 (count (or (:title pr) "")))))]))

(defn -main []
  (println "PR_QUEUE_SCAN_V1")
  (println (str "measured_at=" (.toISOString (js/Date.))))
  (println (str "author=" AUTHOR "  orgs=" (str/join "," ORGS) "  cap=" CAP))
  (let [prs (vec (mapcat search-org ORGS))
        by  (group-by klass prs)
        n   (count prs)]
    (println (str "SCANNED\torgs=" (count ORGS) "\tprs=" n "\tfailed_queries=" (count @failures)))
    (when (seq @failures)
      (println "REFUSED\tthe queue could not be measured in full; do not treat this as an empty queue")
      (doseq [f (take 5 @failures)] (println (str "REFUSED-DETAIL\t" f)))
      (println "END_PR_QUEUE_SCAN_V1")
      (.exit js/process 2))
    (println (str "TOTAL\topen=" n
                  "\tclean=" (count (:clean by))
                  "\tpending=" (count (:pending by))
                  "\tred=" (count (:red by))
                  "\tdirty=" (count (:dirty by))
                  "\tunknown=" (count (:unknown by))
                  "\tdraft=" (count (:draft by))))
    ;; per-repo backlog, cap violations first
    (let [per (->> prs (group-by #(get-in % [:repository :nameWithOwner]))
                   (map (fn [[r v]] [r (count v)])) (sort-by (comp - second)))]
      (doseq [[r c] (take 12 per)]
        (println (str (if (> c CAP) "OVERCAP" "REPO") "\t" r "\topen=" c "\tcap=" CAP))))
    ;; work list: oldest first within each class, cheapest work first
    (let [oldest-first #(sort-by (comp - age-days) %)
          ranked (concat (oldest-first (:clean by))
                         (oldest-first (:dirty by))
                         (oldest-first (:red by))
                         (oldest-first (:pending by))
                         (oldest-first (:unknown by))
                         (oldest-first (:draft by)))
          shown  (take WORK-LIMIT ranked)
          cont   (into {} (map-indexed
                            (fn [i pr]
                              [[(get-in pr [:repository :nameWithOwner]) (:number pr)]
                               (if (< i CONTAINMENT) (containment pr) "unmeasured")])
                            shown))]
      (doseq [[i pr] (map-indexed vector shown)]
        (let [k (klass pr)
              c (get cont [(get-in pr [:repository :nameWithOwner]) (:number pr)])
              action (cond
                       (= c "all-in-base")        "CLOSE-SUPERSEDED?"
                       (= k :draft)               "DRAFT-DECIDE"
                       (= k :clean)               "VERIFY-AND-MERGE"
                       (= k :dirty)               "RESOLVE-THEN-MERGE"
                       (= k :red)                 "INVESTIGATE-RED"
                       :else                      "RECHECK")]
          (println (str "WORK\t" (inc i) "\t" action "\t" (line pr k (str "content=" c))))))
      (when (> (count ranked) (count shown))
        (println (str "WORK-TRUNCATED\tshown=" (count shown) "\tremaining=" (- (count ranked) (count shown))))))
    (when (seq @failures)
      (doseq [f @failures] (println (str "WARN\t" f))))
    (println "END_PR_QUEUE_SCAN_V1")))

(-main)

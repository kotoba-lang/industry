#!/usr/bin/env nbb
;; scripts/gen-appview-page-summary.cljs — derive the appview landing page's
;; summary object from the wrangler config it claims to report.
;;
;;   nbb scripts/gen-appview-page-summary.cljs [--root <dir>] [--apply]
;;                                             [--names a,b] [--limit N]
;;
;; ## Why this exists
;;
;; `verify-appview-page-summary` says it plainly: "There is no generator for the
;; object in these repositories or in the root's scripts/, so it is hand-
;; maintained: change routes or vars in wrangler and the page does not follow."
;;
;; It did not follow. Measured 2026-08-18: 343 findings over 121 examined pages —
;; pages rendering `routeCount: 0` and printing "No public route is declared next
;; to this app surface" at an address wrangler declares two routes for, with a
;; `relativePath` still naming the monorepo the repository was extracted from.
;;
;; A hand-maintained field that claims to mirror another file is a promise nobody
;; keeps. This makes the four derived fields derived.
;;
;; ## What it rewrites, and what it leaves alone
;;
;;   routeCount     <- (count routes) in the nearest wrangler
;;   routes         <- their patterns
;;   vars           <- the var NAMES (the page lists names, never values)
;;   relativePath   <- the page's path relative to its repository root
;;
;; Everything else in the object — title, project, name, kind, xrpc — is left
;; exactly as written. Those are editorial, not derived, and overwriting them
;; would replace one hand-maintained field with another.
;;
;; **Never emits a var VALUE.** The page renders names only, and wrangler vars
;; carry endpoints and handles; a generator that widened that to values would be
;; publishing configuration to every visitor.
;;
;; Dry-run by default. `--apply` writes.
(ns gen-appview-page-summary
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

(def argv (vec *command-line-args*))
(defn- flag [n d] (or (second (drop-while #(not= n %) argv)) d))
(def root (flag "--root" "/Users/junkawasaki/github/com-junkawasaki"))
(def apply? (some #{"--apply"} argv))
(def only (let [s (flag "--names" nil)] (when s (set (str/split s #",")))))
(def limit (some-> (flag "--limit" nil) js/parseInt))

(defn- slurp* [f] (try (.readFileSync fs f "utf8") (catch :default _ nil)))
(defn- dir? [p] (try (.isDirectory (.statSync fs p)) (catch :default _ false)))

(defn- walk [dir out]
  (if-not (.existsSync fs dir)
    out
    (reduce (fn [a e]
              (let [n (.-name e) p (.join path dir n)]
                (cond
                  (or (= n "node_modules") (= n ".git") (str/starts-with? n ".")) a
                  (.isDirectory e) (walk p a)
                  (= n "+page.svelte") (conj a p)
                  :else a)))
            out (array-seq (.readdirSync fs dir #js {:withFileTypes true})))))

(defn- repo-of [p]
  (let [rel (.relative path root p) parts (str/split rel #"/")]
    (when (and (>= (count parts) 3) (= "orgs" (first parts)))
      (str/join "/" (take 3 parts)))))

(defn- nearest-wrangler
  "Walk up from the page toward its repository root; the first wrangler wins."
  [start repo]
  (let [stop (.join path root repo)]
    (loop [d start]
      (let [c (.join path d "wrangler.jsonc")]
        (cond
          (.existsSync fs c) c
          (= (.resolve path d) (.resolve path stop)) nil
          :else (let [up (.dirname path d)]
                  (when (not= up d) (recur up))))))))

(defn- parse-jsonc [f]
  (when-let [s (slurp* f)]
    (try (js->clj (js/JSON.parse (str/replace s #"(?m)^\s*//.*$" "")) :keywordize-keys false)
         (catch :default _ nil))))

(defn- json-str [v] (js/JSON.stringify (clj->js v)))

(defn- rewrite
  "Replace only the four derived fields inside the `const app = {…}` object."
  [s {:keys [route-count routes vars rel]}]
  (-> s
      (str/replace #"\"routeCount\":\s*\d+" (str "\"routeCount\": " route-count))
      (str/replace #"(?s)\"routes\":\s*\[.*?\]" (str "\"routes\": " (json-str routes)))
      (str/replace #"(?s)\"vars\":\s*\[.*?\]" (str "\"vars\": " (json-str vars)))
      (str/replace #"\"relativePath\":\s*\"[^\"]*\"" (str "\"relativePath\": " (json-str rel)))))

(let [pages (->> (walk (.join path root "orgs") [])
                 (filter (fn [p] (when-let [s (slurp* p)] (str/includes? s "routeCount")))))
      rows (atom []) skipped (atom {:no-wrangler 0 :unparsable 0})]
  (doseq [p pages
          :let [repo (repo-of p)]
          :when repo
          :when (or (nil? only) (contains? only repo))]
    (let [w (nearest-wrangler (.dirname path p) repo)
          cfg (when w (parse-jsonc w))]
      (cond
        (nil? w) (swap! skipped update :no-wrangler inc)
        (nil? cfg) (swap! skipped update :unparsable inc)
        :else
        (let [s (slurp* p)
              routes (vec (keep #(get % "pattern") (get cfg "routes" [])))
              vars (vec (sort (keys (get cfg "vars" {}))))
              rel (.relative path (.join path root repo) p)
              want {:route-count (count routes) :routes routes :vars vars :rel rel}
              out (rewrite s want)]
          (when (not= out s)
            (swap! rows conj {:repo repo :page (.relative path root p)
                              :routes (count routes) :vars (count vars) :rel rel
                              :file p :out out}))))))
  (let [rs (if limit (take limit @rows) @rows)]
    (println (str "gen-appview-page-summary: " (count pages) " page(s) carry a summary; "
                  (count @rows) " differ from their wrangler"
                  (when limit (str " (showing " (count rs) ")"))))
    (println (str "  skipped: no-wrangler=" (:no-wrangler @skipped)
                  " unparsable=" (:unparsable @skipped)))
    (doseq [r rs]
      (println (str "  " (if apply? "WROTE " "would ") (:repo r)
                    "  routes=" (:routes r) " vars=" (:vars r)))
      (println (str "        relativePath -> " (:rel r)))
      (when apply? (.writeFileSync fs (:file r) (:out r))))
    (when-not apply?
      (println "  (dry run — pass --apply to write)"))))

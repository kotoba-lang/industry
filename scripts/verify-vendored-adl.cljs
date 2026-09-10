#!/usr/bin/env nbb
;; scripts/verify-vendored-adl.cljs — the vendored Kotoba ADL codec must equal upstream.
;;
;; scripts/kotoba_adl/ is a copy of kotoba-lang/kotoba's src/kotoba/adl*.cljc,
;; kept because a fleet gate ships a tree filtered by :include-ext and cannot
;; reach orgs/ at all. A copy that drifts is worse than no copy: readers would
;; decode .kotoba documents by two different rules and neither would complain.
;;
;; Exit codes:
;;   0  vendored == upstream
;;   1  they differ (drifted)
;;   2  REFUSED — upstream not reachable, so nothing was compared
;;
;; 2 is the important one. Without it a run in a checkout that lacks the west
;; child would report the same silence as a run that actually matched.

(ns verify-vendored-adl
  (:require ["fs" :as fs] ["path" :as path] [clojure.string :as str]))

(def root (or (first *command-line-args*) "."))

(def pairs
  [["orgs/kotoba-lang/kotoba/src/kotoba/adl.cljc"        "scripts/kotoba_adl/kotoba/adl.cljs"]
   ["orgs/kotoba-lang/kotoba/src/kotoba/adl/reader.cljc" "scripts/kotoba_adl/kotoba/adl/reader.cljs"]])

(defn- body
  "The code, with any leading provenance comment removed. Upstream and the
   vendored copy both begin their code at the first form."
  [txt]
  (let [lines (str/split-lines txt)
        i (count (take-while #(not (str/starts-with? % "(")) lines))]
    (str/join "\n" (drop i lines))))

(defn- slurp* [p] (try (fs/readFileSync p "utf8") (catch :default _ nil)))

(let [checked (atom 0) drifted (atom []) missing (atom [])]
  (doseq [[up ven] pairs]
    (let [u (slurp* (path/join root up))
          v (slurp* (path/join root ven))]
      (cond
        (nil? u) (swap! missing conj up)
        (nil? v) (swap! missing conj ven)
        :else (do (swap! checked inc)
                  (when-not (= (body u) (body v)) (swap! drifted conj ven))))))
  (println (str "SCANNED\t" @checked))
  (cond
    (seq @missing)
    (do (println "REFUSING to report a match: these were not readable —")
        (doseq [m @missing] (println "  " m))
        (println "  (the west child is probably not checked out; run `west update --fetch smart kotoba`)")
        (js/process.exit 2))

    (seq @drifted)
    (do (println (str "DRIFTED\t" (count @drifted)))
        (doseq [d @drifted] (println "   " d "differs from upstream"))
        (println "  Fix: re-copy from orgs/kotoba-lang/kotoba, do not edit the vendored copy.")
        (js/process.exit 1))

    (zero? @checked)
    (do (println "REFUSING to report a match: compared nothing") (js/process.exit 2))

    :else
    (do (println (str "OK — vendored ADL codec matches upstream (" @checked " files)"))
        (js/process.exit 0))))

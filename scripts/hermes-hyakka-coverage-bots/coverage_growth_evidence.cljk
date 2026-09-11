#!/usr/bin/env nbb
(ns coverage-growth-evidence
  "Decision-free measurement for the two coverage bots (vuln-coverage-scout,
  osm-coverage-scout). Reports what exists so neither bot proposes a
  duplicate or guesses at a number it could have just read.

  Exit codes match the family (itonami-growth-evidence.cljs etc.):
    0  a report was produced
    2  REFUSED — could not measure"
  (:require [cljs.reader :as edn]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def argv (vec (or *command-line-args* [])))
(defn arg [flag default]
  (let [i (.indexOf argv flag)]
    (if (neg? i) default (get argv (inc i) default))))

(def root (path/resolve (arg "--root" ".")))
(def config-path (path/join root "config" "knowledge-ingest.edn"))
(def lake-dir (arg "--lake-dir" (path/join (aget js/process.env "HOME") ".itonami" "hyakka-lake")))

(defn refuse! [why]
  (println "REFUSED — no evidence was gathered this run.")
  (println why)
  (println)
  (println "Do not propose anything. A coverage proposal built on an unread")
  (println "tree is a proposal built on nothing. Report this refusal and stop.")
  (.exit js/process 2))

(when-not (.existsSync fs config-path) (refuse! (str config-path " does not exist")))
(def config
  (try (edn/read-string (fs/readFileSync config-path "utf8"))
       (catch :default e (refuse! (str "cannot read " config-path ": " (.-message e))))))

(def nvd-source (first (filter #(= "nvd-cve" (:id %)) (:sources config))))
(when-not nvd-source (refuse! "no source with :id \"nvd-cve\" in config — the vuln bot has nothing to raise"))

(def osm-sources (filter #(= :overpass-osm (:kind %)) (:sources config)))

(defn read-claims []
  (let [p (path/join lake-dir "hyakka_claim.json")]
    (when (.existsSync fs p)
      (try (js->clj (js/JSON.parse (fs/readFileSync p "utf8")) :keywordize-keys false)
           (catch :default _ nil)))))

(def claims (read-claims))

(defn coverage-window-facts []
  (when claims
    (let [vuln-claims (filter #(= "vulnerability" (get % "corpus")) claims)
          by-prop (fn [p] (map #(get % "value") (filter #(= p (get % "property")) vuln-claims)))
          total (map #(try (js/parseInt % 10) (catch :default _ nil)) (by-prop "prop/total-results"))
          ingested (map #(try (js/parseInt % 10) (catch :default _ nil)) (by-prop "prop/ingested-count"))]
      {:windows-seen (count total)
       :avg-total-results (when (seq total) (/ (reduce + 0 (remove nil? total)) (max 1 (count (remove nil? total)))))
       :avg-ingested (when (seq ingested) (/ (reduce + 0 (remove nil? ingested)) (max 1 (count (remove nil? ingested)))))})))

(println (str "SCANNED\t" config-path))
(println)
(println "## nvd-cve (vulnerability coverage)")
(println (str "current-max-cves\t" (:max-cves nvd-source)))
(println (str "window-hours\t" (:window-hours nvd-source)))
(println (str "interval-seconds\t" (:interval-seconds nvd-source)))
(if-let [cw (coverage-window-facts)]
  (do (println (str "windows-seen-in-local-lake\t" (:windows-seen cw)))
      (println (str "avg-total-results-per-window\t" (:avg-total-results cw)))
      (println (str "avg-ingested-per-window\t" (:avg-ingested cw))))
  (println "coverage-window-facts\tUNAVAILABLE (no local ~/.itonami/hyakka-lake export — run `npm run datalake` first, or judge from the ledger yourself)"))
(println (str "note\tnvd.cljc's own comment: \"the knob to turn after watching the "
              "projection cost is :max-cves, not the ledger format.\" avg-total-results "
              "much bigger than avg-ingested and current-max-cves means real headroom, "
              "not proof the raise is free — the gate re-verifies with a live run either way."))
(println)
(println "## overpass-osm (already-configured bboxes — do not duplicate)")
(if (empty? osm-sources)
  (println "none configured yet")
  (doseq [s osm-sources]
    (println (str (:id s) "\t" (:name s) "\t" (pr-str (:bbox s)) "\tmax-results=" (:max-results s)))))
(.exit js/process 0)

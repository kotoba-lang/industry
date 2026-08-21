#!/usr/bin/env nbb
;; scripts/fold-adr-ledger.cljs — ONE-SHOT migration: fold the append-only
;; adr-ledger back into each ADR document, so the ADR file is the single
;; latest-state record and git carries the history.
;;
;; Owner decision 2026-07-25: docs (ADR / md / task-graph) stop being
;; append-only. Edit them in place to reflect the current state; `git log` /
;; `git blame` is the durable history. This retires the ledger indirection
;; introduced by ADR-2607181900 (and ADR-2607173000 decision item 6).
;;
;; What it does, per ADR that has ledger events:
;;   1. groups events by :adr/id, orders by :event/seq
;;   2. appends a "## 改訂履歴" section to the END of that ADR's :adr/body
;;      string, one subsection per event (date / type / summary / body /
;;      related), so nothing in the ledger is lost
;;   3. applies :status-change events to :adr/status (last one wins)
;;
;; It edits the ADR files TEXTUALLY (locating the :adr/body string and scanning
;; to its unescaped closing quote) rather than read->modify->pr-str, because a
;; round-trip through the reader would reflow every file and destroy the
;; hand-authored formatting and comments.
;;
;; Usage:
;;   nbb scripts/fold-adr-ledger.cljs --dry-run     # report only
;;   nbb scripts/fold-adr-ledger.cljs               # write

(ns fold-adr-ledger
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]
            [cljs.reader :as reader]))

(def root (or js/process.env.FLEET_ROOT (js/process.cwd)))
(def adr-dir (path/join root "90-docs" "adr"))
(def ledger-path (path/join root "90-docs" "adr-ledger" "adr-ledger.edn"))

;; ------------------------------------------------------------------ ledger

(defn read-ledger
  "One EDN map per line, ;; comments and blank lines skipped."
  []
  (->> (str/split-lines (fs/readFileSync ledger-path "utf8"))
       (map str/trim)
       (remove str/blank?)
       (remove #(str/starts-with? % ";;"))
       (map reader/read-string)))

;; ------------------------------------------------------- ADR file location

(def adr-files
  (->> (fs/readdirSync adr-dir)
       (filter #(str/ends-with? % ".edn"))
       (map #(path/join adr-dir %))
       vec))

(def id->file
  "Index every ADR file by the :adr/id it actually declares. The ledger's
  :adr/id is sometimes the full slug (adr-2607181900-foo), sometimes the bare
  date stamp (2607173000), so filename-derived matching alone is not enough."
  (reduce (fn [acc f]
            (let [s (fs/readFileSync f "utf8")]
              (reduce (fn [a m] (assoc a (second m) f))
                      acc
                      (re-seq #":adr/id\s+\"([^\"]+)\"" s))))
          {}
          adr-files))

(def ambiguous-overrides
  "Ledger ids whose date stem matches more than one ADR file, resolved by
  reading the event body. Kept explicit so the choice is auditable:
    2607210000 — event seq 15 is about mathlib4 / AlgebraicClosure Q, i.e. the
                 IUT-Lean progress checkpoint, NOT the ISIC-2599 metal-coverage
                 ADR that shares the stamp."
  {"2607210000" "2607210000-com-junkawasaki-iut-lean-progress-checkpoint.edn"})

(defn adr-file
  "Resolve a ledger :adr/id to an ADR file: declared-id match, then explicit
  override, then a UNIQUE date-stem prefix match. Ambiguous stems with no
  override stay unresolved on purpose rather than being guessed."
  [adr-id]
  (or (get id->file adr-id)
      (get id->file (str "adr-" adr-id))
      (when-let [n (get ambiguous-overrides adr-id)] (path/join adr-dir n))
      (let [stem (str/replace adr-id #"^adr-" "")
            hits (filter #(str/starts-with? (path/basename %) (str stem "-")) adr-files)]
        (when (= 1 (count hits)) (first hits)))))

;; --------------------------------------------------- textual :adr/body edit

(defn body-string-bounds
  "Return [open-quote-idx close-quote-idx] of the :adr/body string literal,
  scanning past backslash escapes. nil when the file has no :adr/body."
  [s]
  (when-let [k (let [i (str/index-of s ":adr/body")] (when i i))]
    (let [open (str/index-of s "\"" k)]
      (when open
        (loop [i (inc open)]
          (cond
            (>= i (count s)) nil
            (= \\ (nth s i)) (recur (+ i 2))
            (= \" (nth s i)) [open i]
            :else (recur (inc i))))))))

(defn edn-escape
  "Escape a string for insertion into an existing EDN string literal."
  [s]
  (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")))

(defn event->markdown [{:event/keys [at type summary body related]}]
  (str "\n### " (subs (or at "") 0 10) " — " (name (or type :note))
       (when (seq summary) (str "\n\n" summary))
       (when (seq body) (str "\n\n" body))
       (when (seq related)
         (str "\n\n関連: " (str/join ", " related)))
       "\n"))

(def fold-preamble
  (str "（2026-07-25 に `90-docs/adr-ledger/adr-ledger.edn` から統合。"
       "以後この文書は最新状態のみを表し、履歴は git が持つ。）\n"))

(defn fold-body
  "Append the 改訂履歴 section to the :adr/body literal inside file text s.
  ADRs written with the context/decision/consequences schema have no
  :adr/body; those get a :adr/revision-history attribute instead so the
  events still land in the document."
  [s events]
  (let [md (str fold-preamble (str/join (map event->markdown events)))]
    (if-let [[_ close] (body-string-bounds s)]
      (str (subs s 0 close)
           "\\n\\n## 改訂履歴\\n\\n" (edn-escape md)
           (subs s close))
      ;; no :adr/body — insert a sibling attribute after :adr/id
      (if-let [m (re-find #":adr/id\s+\"[^\"]+\"" s)]
        (str/replace-first s m (str m "\n  :adr/revision-history\n  \"" (edn-escape md) "\""))
        s))))

(defn apply-status [s events]
  (if-let [new-status (->> events
                           (filter #(= :status-change (:event/type %)))
                           (keep :event/new-status)
                           last)]
    (str/replace s #":adr/status\s+\"[^\"]*\"" (str ":adr/status \"" new-status "\""))
    s))

;; ------------------------------------------------------------------- main

(defn -main [& args]
  (let [dry? (some #{"--dry-run"} args)
        events (read-ledger)
        ;; Group by RESOLVED FILE, not by :adr/id. The ledger spells the same
        ;; ADR two ways (bare stamp "2607203000" AND full slug
        ;; "adr-2607203000-universal-…"); grouping by id would fold the same
        ;; file twice and emit two 改訂履歴 sections.
        resolved (->> events
                      (filter :adr/id)
                      (map (fn [e] (assoc e ::file (adr-file (:adr/id e))))))
        by-file (->> (filter ::file resolved)
                     (group-by ::file)
                     (map (fn [[k v]] [k (sort-by #(or (:event/seq %) 0) v)]))
                     (into {}))
        orphans (atom (->> (remove ::file resolved)
                           (map :adr/id)
                           distinct
                           (mapv (fn [id] [id "ADR file not found"]))))
        written (atom 0)]
    (println (str "ledger events: " (count events)
                  " / distinct ADR id: " (count (distinct (keep :adr/id events)))
                  " / distinct target file: " (count by-file)))
    (doseq [[f evs] (sort-by first by-file)]
      (let [adr-id (:adr/id (first evs))
            s (fs/readFileSync f "utf8")
            out (-> s (fold-body evs) (apply-status evs))]
        (cond
          (str/includes? s "## 改訂履歴")
          (swap! orphans conj [adr-id "already folded — refusing to double-append"])

          (= out s)
          (swap! orphans conj [adr-id "unchanged (fold produced no diff)"])

          :else
          (do (when-not dry? (fs/writeFileSync f out "utf8"))
              (swap! written inc)
              (println (str (if dry? "would fold " "folded ") (count evs) " event(s) -> "
                            (path/basename f)))))))
    (println (str (if dry? "\nwould write " "\nwrote ") @written " file(s)"))
    (when (seq @orphans)
      (println "\nUNRESOLVED (need manual handling):")
      (doseq [[id why] @orphans] (println (str "  " id " — " why))))))

(apply -main (drop 3 (js->clj js/process.argv)))

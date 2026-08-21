#!/usr/bin/env nbb
;; scripts/itonami-maturity-pin-drift.cljs — did the maturity measurement read
;; the trees the manifest pins?
;;
;; `itonami-maturity-scan.cljs` records `:git/head-sha` for every repo it
;; measures. This compares that against `manifest/west.yml` and answers one
;; question: is any row describing a tree that is not the pinned one?
;;
;; Why it exists. A checkout left BEHIND its pin produces a confident row about
;; older work — landed axes read as 0bp, and the ranking sends the next round
;; back to a repo that was already raised. The tick guards against this by
;; comparing commit TIMES, but only for the top candidates it ranks, so drift
;; outside that window is invisible. Measured 2026-08-20: 26 of 1,835
;; cloud-itonami checkouts were behind their pin and only the handful that
;; surfaced in the top 24 were caught.
;;
;; AHEAD is not drift to fix. Actors that commit on their own schedule
;; (`yabai-actor`'s ct-watch and friends) sit in front of the pin as a matter
;; of course, and there HEAD is the truth. Only BEHIND is reported as a
;; failure; ahead is counted and listed so the number is not mistaken for zero.
;;
;; A checkout that is behind AND dirty is somebody's uncommitted work, not a
;; defect this can fix — syncing it would destroy the WIP. Those are reported
;; and counted, but they do not fail the check, because an exit code that
;; nobody can act on gets ignored and then the actionable ones ride along with
;; it. Behind AND clean is the fixable class: `west update --fetch smart <name>`.
;;
;; Exit codes:
;;   0 — answered, no CLEAN row is behind its pin
;;   1 — answered, at least one clean row is behind (fix: west update)
;;   2 — could not answer (no evidence, no pins, or the evidence predates
;;       `:git/head-sha` — an old file would otherwise report "no drift"
;;       because it has nothing to compare)
;;
;; usage:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/itonami-maturity-pin-drift.cljs
;;   … --evidence <path> --west <path> --org <prefix>   (defaults below)

(ns itonami-maturity-pin-drift
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["child_process" :as cp]
            ["fs" :as fs]))

(def argv (vec (drop 3 (js->clj js/process.argv))))
(defn flag [f d] (let [i (.indexOf argv f)] (if (neg? i) d (get argv (inc i) d))))

(def evidence-path (flag "--evidence" "manifest/itonami-maturity-evidence.edn"))
(def west-path (flag "--west" "manifest/west.yml"))
(def org-prefix (flag "--org" "orgs/"))

(defn die [code & msg]
  (println (str/join " " msg))
  (js/process.exit code))

(defn- pins
  "path -> pinned revision, from the generated manifest. Parsed rather than
  read as YAML because this repository ships no YAML reader and the two fields
  needed are one regex each."
  [text]
  (loop [lines (str/split-lines text) rev nil out {}]
    (if-let [line (first lines)]
      (cond
        (re-find #"^\s*- name: " line) (recur (rest lines) nil out)
        (re-find #"^\s*revision: " line)
        (recur (rest lines) (str/trim (subs line (inc (str/index-of line ":")))) out)
        (re-find #"^\s*path: " line)
        (let [p (str/trim (subs line (inc (str/index-of line ":"))))]
          (recur (rest lines) nil (if rev (assoc out p rev) out)))
        :else (recur (rest lines) rev out))
      out)))

(defn- dirty?
  "Does this checkout hold uncommitted work? Asked only of rows already known
  to be behind, so it costs nothing on the common path."
  [path]
  (try
    (not (str/blank?
          (str/trim (.execSync cp (str "git -C " path " status --porcelain")
                               #js {:encoding "utf8" :timeout 15000}))))
    (catch :default _ false)))

(defn- behind?
  "Is `head` an ancestor of `pin`? Only asked for rows that already mismatch,
  so this costs one git call per drifted repo rather than per repo."
  [path head pin]
  (try
    (.execSync cp (str "git -C " path " merge-base --is-ancestor " head " " pin)
               #js {:stdio "ignore" :timeout 15000})
    true
    (catch :default _ false)))

(defn -main []
  (when-not (.existsSync fs evidence-path) (die 2 "MISSING evidence" evidence-path))
  (when-not (.existsSync fs west-path) (die 2 "MISSING west manifest" west-path))
  (let [raw (edn/read-string (.readFileSync fs evidence-path "utf8"))
        ;; The file is one vector of maps. Wrapping it in another `[]` — the
        ;; obvious thing when a file looks like a stream of forms — yields a
        ;; one-element sequence whose only member is a vector, `filter map?`
        ;; drops it, and the check reports EMPTY on a file with 1,942 rows.
        ;; It said "could not answer" rather than "no drift", which is the only
        ;; reason that mistake was visible at all.
        rows (->> (if (sequential? raw) raw [raw])
                  (filter map?)
                  (filter :repo/path)
                  (filter #(str/starts-with? (:repo/path %) org-prefix)))
        pinned (pins (.readFileSync fs west-path "utf8"))
        measured (filterv :git/head-sha rows)]
    (when (empty? rows) (die 2 "EMPTY evidence — nothing to compare"))
    ;; The floor that keeps an old evidence file from reporting a clean bill of
    ;; health it never checked.
    (when (empty? measured)
      (die 2 (str "NO SHA — " (count rows) " rows and none carry :git/head-sha."
                  " Re-run itonami-maturity-scan.cljs before trusting this.")))
    (let [drifted (for [{:repo/keys [path] :git/keys [head-sha]} measured
                        :let [pin (get pinned path)]
                        :when (and pin (not= pin head-sha))]
                    (let [b (behind? path head-sha pin)]
                      {:path path :head head-sha :pin pin
                       :behind? b
                       :dirty? (and b (dirty? path))}))
          behind (filter :behind? drifted)
          fixable (remove :dirty? behind)
          held (filter :dirty? behind)
          ahead (remove :behind? drifted)]
      (println (str "CHECKED " (count measured) " measured rows of " (count rows)
                    " (" (- (count rows) (count measured)) " without a sha)"
                    "  DRIFT " (count drifted)
                    "  BEHIND " (count behind)
                    " (fixable " (count fixable) ", held by WIP " (count held) ")"
                    "  AHEAD " (count ahead)))
      (doseq [{:keys [path head pin]} fixable]
        (println (str "  BEHIND " path " measured=" (subs head 0 12) " pin=" (subs pin 0 12)
                      "  -> west update --fetch smart " (last (str/split path #"/")))))
      (doseq [{:keys [path head pin]} held]
        (println (str "  behind+DIRTY " path " measured=" (subs head 0 12)
                      " pin=" (subs pin 0 12)
                      "  (uncommitted work here — its row is stale and this"
                      " cannot be fixed without destroying that work)")))
      (doseq [{:keys [path]} (take 5 ahead)]
        (println (str "  ahead  " path " (not a defect — actors commit ahead of the pin)")))
      (when (> (count ahead) 5)
        (println (str "  ahead  … " (- (count ahead) 5) " more")))
      (if (seq fixable)
        (die 1 (str "FAIL — " (count fixable)
                    " clean checkouts describe a tree older than the pin; their"
                    " axes are the axes of work that has since landed"))
        (println (str "PASS — no clean checkout is behind its pin"
                      (when (seq held)
                        (str " (" (count held)
                             " behind but holding uncommitted work — reported above,"
                             " not counted as a failure)"))))))))

(-main)

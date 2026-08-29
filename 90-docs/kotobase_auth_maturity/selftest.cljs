#!/usr/bin/env nbb
;; Does the judge discriminate, and does it fail for the reason it names?
;;
;;   npx --yes nbb --classpath 90-docs 90-docs/kotobase_auth_maturity/selftest.cljs \
;;     90-docs/kotobase_auth_maturity/probe-2026-08-29.edn
;;
;; Four claims, each checked against the live measurement rather than a
;; hand-built map, so the fixtures cannot drift away from the probe's shape:
;;
;;   1. the plane as measured today produces findings          (not vacuously green)
;;   2. closing exactly the measured gaps produces zero        (green is reachable)
;;   3. an input that could not be read scores neither         (unmeasured != pass)
;;   4. repairing ONE input clears ONE finding, by name        (reason-specific)
;;
;; Claim 4 is the one that matters. A negative test that asserts only "it went
;; red" counts a run that failed for an unrelated cause as a success; this
;; workspace logged four separate instances of that shape on 2026-08-22 alone.
(ns kotobase-auth-maturity.selftest
  (:require [kotobase-auth-maturity.audit :as audit]
            [clojure.edn :as edn]
            [clojure.string :as str]
            ["fs" :as fs]))

(def repairs
  "The exact inputs today's findings name. Applying all of them must make the
  judge green; applying one must clear that one finding and no other."
  {:biscuit/surfaces-naming-biscuit 4
   :biscuit/surface-header-changes-answer true
   :biscuit/datom-plane-names-biscuit true
   :discovery/capabilities-names-credentials true})

(defn- finding-axes [p] (set (map :axis (:findings (audit/audit p)))))

(def ^:private results (atom []))
(defn- check! [label ok? detail]
  (swap! results conj [label ok? detail])
  (println (str (if ok? "  ok   " "  FAIL ") label (when detail (str "  — " detail)))))

(defn -main [& args]
  (let [live (edn/read-string (str (fs/readFileSync (first args) "utf8")))
        p (:probe live)
        base (finding-axes p)
        green (audit/audit (merge p repairs))
        unmeasured (audit/audit (assoc p :authn/passkey-login-options :unknown))]

    (check! "1. today's plane is not vacuously green"
            (seq base) (str (count base) " findings: " (str/join ", " (map name base))))

    (check! "2. repairing the named inputs reaches 100.00"
            (and (empty? (:findings green)) (empty? (:incomplete green))
                 (< (abs (- 100.0 (:overall green))) 0.001))
            (str "overall " (.toFixed (:overall green) 2)))

    ;; The guarded failure is "unknown counted as a zero", so the comparison
    ;; has to be against what the score WOULD be under that bug -- not against
    ;; the baseline. Dropping an axis that was scoring 1.0 legitimately lowers
    ;; a mean over the axes that remain; that is arithmetic, not a defect.
    (let [as-if-zero (* 100.0 (/ (- (/ (:overall (audit/audit p)) 100.0)
                                    (:weight (first (filter #(= :passkey-login-live (:axis %))
                                                            (:axes (audit/audit p))))))
                                 1.0))]
      (check! "3. an unreadable input scores nil, not 0 and not 1"
              (and (contains? (set (:incomplete unmeasured)) :passkey-login-live)
                   (not-any? #(= :passkey-login-live (:axis %)) (:findings unmeasured))
                   (> (:overall unmeasured) (+ as-if-zero 0.001)))
              (str "incomplete=" (str/join "," (map name (:incomplete unmeasured)))
                   " overall=" (.toFixed (:overall unmeasured) 2)
                   " vs " (.toFixed as-if-zero 2) " if :unknown were scored 0.0")))

    (doseq [[k v] repairs]
      (let [after (finding-axes (assoc p k v))
            cleared (clojure.set/difference base after)]
        (check! (str "4. repairing " k " clears exactly one finding")
                (= 1 (count cleared))
                (str "cleared " (str/join "," (map name cleared))
                     "; still open " (count after)))))

    (let [failed (remove second @results)]
      (println (str "\n" (- (count @results) (count failed)) "/" (count @results) " checks passed"))
      (if (seq failed) 1 0))))

(let [code (apply -main *command-line-args*)]
  (set! (.-exitCode js/process) code))

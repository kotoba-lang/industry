#!/usr/bin/env nbb
;; verify-adr-edn-shape — a commit gate for a single ADN/ADR `.edn` document.
;;
;;   nbb scripts/verify-adr-edn-shape.cljs <file.edn> [--tail "final words"]
;;
;; Exits 0 when the file is one entity whose keys are ALL keywords and whose
;; `:adr/body` ends with `--tail` verbatim. Exits 1 otherwise, printing which
;; assertion failed. Meant to be chained to the commit in one shell expression:
;;
;;   nbb scripts/verify-adr-edn-shape.cljs f.edn --tail '...**' && git commit ...
;;
;; ## Why keyword-ness is checked separately from parseability
;;
;; **`edn/read-string` succeeding proves nothing about the keys.** A document
;; built by hand-splicing strings can carry `"adr/note"` where `:adr/note` was
;; meant; the reader accepts it, the file still holds exactly one entity, and
;; `(:adr/note m)` silently returns nil forever after. Every query over the
;; datom plane addresses attributes as keywords, so a string key is a fact that
;; is present in the file and absent from every answer.
;;
;; This is why `--self-test` builds its negative control that way. A control
;; that makes the READER THROW proves only that malformed EDN is rejected,
;; which was never in doubt -- that fake demonstration was made and caught on
;; 2026-08-13. The control here parses cleanly, yields one entity, and still
;; must fail.
;;
;; ## Why the tail is checked
;;
;; The body is a long `pr-str`-built string. A truncated write, a lost final
;; paragraph, or a dropped trailing `**` all leave a file that parses. Pinning
;; the exact final characters is the cheapest evidence that what landed is what
;; was written -- and the trailing `**` matters precisely because it is the
;; part an eye skips.
;;
;;   nbb scripts/verify-adr-edn-shape.cljs --self-test

(ns verify-adr-edn-shape
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; nbb leaves its own script path in `process.argv`, so `(drop 2 ...)` yields
;; [this-script, ...real args] and a naive `first` picks the SCRIPT as the file
;; to check -- measured 2026-08-13, that made the gate report `top level is
;; function(...)` about itself.
(def argv (let [a (vec (drop 2 (js->clj js/process.argv)))]
            (if (and (seq a) (str/ends-with? (str (first a)) ".cljs")) (vec (rest a)) a)))
(defn- flag? [f] (some #{f} argv))
(defn- opt [f d] (let [i (.indexOf argv f)] (if (neg? i) d (get argv (inc i) d))))

(defn- check
  "[ok? [messages]] for one file. Never throws: a reader failure is a finding,
  not a crash."
  [file tail]
  (let [src (try {:s (str (fs/readFileSync file "utf8"))}
                 (catch :default e {:err (str "unreadable: " (.-message e))}))]
    (if (:err src)
      [false [(:err src)]]
      (let [parsed (try {:v (edn/read-string (:s src))}
                        (catch :default e {:err (str "edn/read-string threw: " (.-message e))}))]
        (if (:err parsed)
          [false [(:err parsed)]]
          (let [v (:v parsed)
                msgs (atom [])
                fail! (fn [m] (swap! msgs conj m))]
            (when-not (vector? v) (fail! (str "top level is " (type v) ", want a vector")))
            (when (and (vector? v) (not= 1 (count v)))
              (fail! (str "vector holds " (count v) " entities, want exactly 1")))
            (let [m (first (filter map? v))]
              (if-not (map? m)
                (fail! "no map entity found")
                (do
                  ;; The whole point: parses fine, one entity, wrong key TYPE.
                  (doseq [k (keys m)]
                    (when-not (keyword? k)
                      (fail! (str "non-keyword key " (pr-str k) " (a " (type k) ")"))))
                  (doseq [k [:db/id :adr/id :adr/title :adr/status :adr/date
                             :adr/supersedes :adr/superseded-by :adr/body]]
                    (when-not (contains? m k) (fail! (str "missing key " k))))
                  (when-let [b (:adr/body m)]
                    (when (and tail (not (str/ends-with? b tail)))
                      (fail! (str "body does not end with the expected tail.\n"
                                  "  want ...: " (pr-str tail) "\n"
                                  "  got  ...: " (pr-str (subs b (max 0 (- (count b) (count tail))))))))))))
            [(empty? @msgs) @msgs]))))))

(defn- self-test!
  "Positive control and NEGATIVE control. The negative control must still parse
  as one entity -- see the header."
  []
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "adr-shape-"))
        good (path/join dir "good.edn")
        bad (path/join dir "bad.edn")
        tail "ends here**"
        body (str "some body that " tail)]
    (fs/writeFileSync good (pr-str [{:db/id -1 :adr/id "ADR-0" :adr/title "t" :adr/status "accepted"
                                     :adr/date "2026-08-13" :adr/supersedes "" :adr/superseded-by ""
                                     :adr/body body}]))
    ;; Same document with ONE key written as a string. `edn/read-string` is
    ;; perfectly happy; the vector still holds exactly one map.
    (fs/writeFileSync bad (str "[{:db/id -1 :adr/id \"ADR-0\" :adr/title \"t\" :adr/status \"accepted\" "
                               ":adr/date \"2026-08-13\" :adr/supersedes \"\" :adr/superseded-by \"\" "
                               "\"adr/note\" \"stray string key\" "
                               ":adr/body " (pr-str body) "}]"))
    (let [reread (edn/read-string (str (fs/readFileSync bad "utf8")))
          [good-ok _] (check good tail)
          [bad-ok bad-msgs] (check bad tail)
          control-parses? (and (vector? reread) (= 1 (count reread)) (map? (first reread)))]
      (println (str "control parses as exactly one entity: " control-parses?
                    "  (entities=" (count reread) ")"))
      (println (str "positive control passes: " good-ok))
      (println (str "negative control fails:  " (not bad-ok) "  " (pr-str bad-msgs)))
      (if (and control-parses? good-ok (not bad-ok))
        (do (println "self-test OK") 0)
        (do (println "self-test FAILED") 1)))))

(defn -main []
  (if (flag? "--self-test")
    (set! (.-exitCode js/process) (self-test!))
    (let [tail (when (flag? "--tail") (opt "--tail" nil))
          ti (.indexOf argv "--tail")
          ;; Drop `--tail` AND its value BY INDEX. Removing the value by
          ;; EQUALITY (the first draft) needs a sentinel default, and a stray
          ;; NUL in that sentinel made grep treat this file as binary.
          file (first (keep-indexed (fn [k v]
                                      (when (and (not (str/starts-with? v "--"))
                                                 (not= k (inc ti)))
                                        v))
                                    argv))]
      (if-not file
        (do (println "usage: verify-adr-edn-shape.cljs <file.edn> [--tail \"final words\"]")
            (set! (.-exitCode js/process) 1))
        (let [[ok msgs] (check file tail)]
          (if ok
            (println (str "OK  " file (when tail (str "  (tail pinned: " (pr-str tail) ")"))))
            (do (println (str "FAIL  " file))
                (doseq [m msgs] (println (str "  - " m)))
                (set! (.-exitCode js/process) 1))))))))

(-main)

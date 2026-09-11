#!/usr/bin/env nbb
;; Validator for the ADR file. Exits NON-ZERO on any failure, so it can be chained ahead of the
;; commit in the same shell expression.
;;
;; What it guards against: an unescaped quote inside the body ends the EDN string early. The file
;; STILL PARSES, still yields exactly one entity, and the truncated remainder reappears as a stray
;; TOP-LEVEL key -- silently losing thousands of characters. So parsing is not the check. The
;; checks are: exactly one entity, every key a keyword, no stray top-level keys, and a body that
;; ends with the exact final words written (including any trailing markup).
(ns validate-adr-2608135000
  (:require ["node:fs" :as fs]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def expected-tail
  "and the two archived repositories, still read-only.")

(def required-keys
  #{:db/id :adr/id :adr/title :adr/status :adr/date :adr/supersedes :adr/superseded-by :adr/body})

(defn fail! [msg] (println "INVALID:" msg) (.exit js/process 1))

(let [p (first *command-line-args*)
      raw (.readFileSync fs p "utf8")
      data (try (reader/read-string raw)
                (catch :default e (fail! (str "does not parse: " (.-message e)))))]
  (when-not (vector? data) (fail! "top level is not a vector"))
  (when-not (= 1 (count data)) (fail! (str "expected exactly 1 entity, found " (count data))))
  (let [e (first data)]
    (when-not (map? e) (fail! "the single element is not a map"))
    (doseq [k (keys e)]
      (when-not (keyword? k)
        (fail! (str "non-keyword top-level key " (pr-str k)
                    " -- this is the signature of a body string that ended early"))))
    (let [extra (remove required-keys (keys e))]
      (when (seq extra) (fail! (str "unexpected keys: " (pr-str (vec extra))))))
    (doseq [k required-keys]
      (when-not (contains? e k) (fail! (str "missing key " k))))
    (let [b (:adr/body e)]
      (when-not (string? b) (fail! "body is not a string"))
      (when-not (str/ends-with? (str/trimr b) expected-tail)
        (fail! (str "body does not end with the expected final words.\n  expected tail: "
                    (pr-str expected-tail)
                    "\n  actual tail:   " (pr-str (subs b (max 0 (- (count b) 80)))))))
      (println "VALID:" (:adr/id e) "-" (count b) "body chars, 1 entity, all keys keywords,"
               "tail matches"))))

#!/usr/bin/env nbb
(require '[scripts.west-pin-guard-policy :as policy]
         '[scripts.nbb-compat :as compat])

(def cases
  [{:name "child repo has no west blobs"
    :head "" :main "" :expected false}
   {:name "unrelated superproject push"
    :head "same" :main "same" :expected false}
   {:name "west-changing superproject push"
    :head "candidate" :main "baseline" :expected true}])

(doseq [{:keys [name head main expected]} cases]
  (let [actual (boolean (policy/requires-verification? head main))]
    (when-not (= expected actual)
      (println (str "FAIL " name " expected=" expected " actual=" actual))
      (compat/exit 1))
    (println (str "OK " name))))

(def payload-cases
  [{:name "payload file read -> verify it"
    :named? true :read? true :expected :verify}
   {:name "payload path named but unreadable -> cannot verify, do not fail open"
    :named? true :read? false :expected :unreadable}
   {:name "no payload path (inline / $VAR / wrapper) -> nothing to read"
    :named? false :read? false :expected :unknown}])

(doseq [{:keys [name named? read? expected]} payload-cases]
  (let [actual (policy/payload-decision named? read?)]
    (when-not (= expected actual)
      (println (str "FAIL " name " expected=" expected " actual=" actual))
      (compat/exit 1))
    (println (str "OK " name))))

(println "west-pin-guard-policy: 6 cases OK")


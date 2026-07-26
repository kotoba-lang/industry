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

(println "west-pin-guard-policy: 3 cases OK")


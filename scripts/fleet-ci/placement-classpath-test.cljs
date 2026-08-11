#!/usr/bin/env nbb
;; Regression test for placement's cross-cwd murakumo classpath cache.

(ns placement-classpath-test
  (:require [cljs.test :refer [deftest is run-tests]]
            [placement :as placement]
            ["node:path" :as path]))

(deftest relative-classpath-entries-use-murakumo-checkout
  (let [base (path/resolve "/tmp" "murakumo-a")
        absolute (path/resolve "/opt" "shared" "lib.jar")
        cp (str "src" path/delimiter absolute path/delimiter "resources")]
    (is (= (str (path/join base "src") path/delimiter
                absolute path/delimiter
                (path/join base "resources"))
           (placement/absolutize-classpath base cp)))))

(deftest cache-identity-includes-checkout
  (let [st #js {:mtimeMs 1234 :size 5678}]
    (is (not= (placement/classpath-cache-key "/tmp/murakumo-a" st)
              (placement/classpath-cache-key "/tmp/murakumo-b" st)))))

(let [{:keys [fail error]} (run-tests 'placement-classpath-test)]
  (when (pos? (+ fail error))
    (js/process.exit 1)))

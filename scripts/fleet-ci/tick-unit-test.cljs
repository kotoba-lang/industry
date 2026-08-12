#!/usr/bin/env nbb
;; Run with:
;;   FLEET_CI_LIBRARY_MODE=1 nbb --classpath scripts/fleet-ci \
;;     scripts/fleet-ci/tick-unit-test.cljs

(ns tick-unit-test
  (:require [cljs.test :refer [deftest is run-tests]]
            [tick :as tick]))

(def dep-sha "1111111111111111111111111111111111111111")
(def deps-text
  (str "{:deps {io.github.example/shared {:git/sha \"" dep-sha "\"}}}"))

(deftest dependency-shipping-is-deduplicated-per-host-and-sha
  (let [ready (atom {})
        calls (atom [])]
    (with-redefs [tick/sh (fn [cmd args & _]
                            (swap! calls conj [cmd args])
                            {:exit 0 :out "FLEET-CI-DEP-PRESENT"})
                  tick/mirror! (fn [_] "/fake-mirror")
                  tick/ensure-sha! (fn [m _ _] m)
                  tick/git-show (fn [& _] "")]
      (tick/ship-git-deps! "judah" deps-text ready)
      (tick/ship-git-deps! "judah" deps-text ready)
      (is (= 1 (count @calls)))
      (is (contains? @ready ["judah" "io.github.example/shared" dep-sha])))))

(deftest failed-dependency-shipping-is-not-cached
  (let [ready (atom {})
        shell-calls (atom 0)]
    (with-redefs [tick/sh (fn [cmd _ & _]
                            (swap! shell-calls inc)
                            {:exit 0 :out (if (= cmd "ssh") "" "ship failed")})
                  tick/git (fn [& _] {:exit 0 :out ""})
                  tick/mirror! (fn [_] "/fake-mirror")
                  tick/ensure-sha! (fn [m _ _] m)
                  tick/git-show (fn [& _] "")
                  tick/log (fn [& _])]
      (tick/ship-git-deps! "judah" deps-text ready)
      (tick/ship-git-deps! "judah" deps-text ready)
      (is (= 4 @shell-calls))
      (is (empty? @ready)))))

(deftest cached-parent-still-retries-a-failed-transitive-dependency
  (let [ready (atom {})
        calls (atom [])
        child-sha "2222222222222222222222222222222222222222"
        child-deps (str "{:deps {io.github.example/child {:git/sha \""
                        child-sha "\"}}}")]
    (with-redefs [tick/sh (fn [cmd args & _]
                            (swap! calls conj [cmd args])
                            {:exit 0
                             :out (cond
                                    (and (= cmd "ssh")
                                         (some #(re-find #"shared" (str %)) args))
                                    "FLEET-CI-DEP-PRESENT"
                                    :else "ship failed")})
                  tick/git (fn [& _] {:exit 0 :out ""})
                  tick/mirror! (fn [repo] repo)
                  tick/ensure-sha! (fn [m _ _] m)
                  tick/git-show (fn [m _ _]
                                  (if (= m "example/shared") child-deps ""))
                  tick/log (fn [& _])]
      (tick/ship-git-deps! "judah" deps-text ready)
      (tick/ship-git-deps! "judah" deps-text ready)
      ;; parent: 1 presence check; child: presence + failed ship twice
      (is (= 5 (count @calls)))
      (is (contains? @ready ["judah" "io.github.example/shared" dep-sha]))
      (is (not (contains? @ready ["judah" "io.github.example/child" child-sha]))))))

(deftest failed-worktree-remove-prunes-metadata-after-directory-removal
  (let [calls (atom [])]
    (with-redefs [tick/git (fn [dir args & _]
                             (swap! calls conj [:git dir args])
                             (if (= "remove" (second args))
                               {:exit 1 :out "invalid .git"}
                               {:exit 0 :out ""}))
                  tick/sh (fn [cmd args & _]
                            (swap! calls conj [:sh cmd args])
                            {:exit 0 :out ""})
                  tick/log (fn [& _])]
      (tick/cleanup-worktree! "/repo" "/tmp/fleet-ci-put-test")
      (is (= [[:git "/repo" ["worktree" "remove" "--force"
                               "/tmp/fleet-ci-put-test"]]
              [:sh "rm" ["-rf" "/tmp/fleet-ci-put-test"]]
              [:git "/repo" ["worktree" "prune" "--expire" "now"]]]
             @calls)))))

(deftest landing-worktree-checks-out-only-the-target-ledger
  (let [calls (atom [])]
    (with-redefs [tick/git (fn [dir args & _]
                             (swap! calls conj [dir args])
                             {:exit 0 :out ""})]
      (is (:ok (tick/prepare-landing-worktree!
                "/mirror" "/tmp/landing" "origin/main"
                "manifest/fleet-ci.edn")))
      (is (= [["/mirror" ["worktree" "add" "--detach" "--no-checkout"
                            "--quiet" "/tmp/landing" "origin/main"]]
              ["/tmp/landing" ["sparse-checkout" "init" "--no-cone"]]
              ["/tmp/landing" ["sparse-checkout" "set" "--no-cone"
                                "manifest/fleet-ci.edn"]]
              ["/tmp/landing" ["read-tree" "-mu" "HEAD"]]]
             @calls)))))

(let [{:keys [fail error]} (run-tests 'tick-unit-test)]
  (when (pos? (+ fail error))
    (js/process.exit 1)))

#!/usr/bin/env nbb
;; Run with:
;;   FLEET_CI_LIBRARY_MODE=1 nbb --classpath scripts/fleet-ci \
;;     scripts/fleet-ci/tick-unit-test.cljs

(ns tick-unit-test
  (:require [cljs.reader]
            [cljs.test :refer [deftest is run-tests]]
            [clojure.string :as str]
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
    ;; 1 回の ship につきシェルは 3 回（present 照合 / bundle 搬送 / archive
    ;; fallback 搬送）。どれも sentinel を返さないので :failed になり、
    ;; :failed は `ready` に載らない = 次の tick で必ず再試行される。
    (with-redefs [tick/sh (fn [_ _ & _]
                            (swap! shell-calls inc)
                            {:exit 0 :out "ship failed"})
                  tick/git (fn [& _] {:exit 0 :out ""})
                  tick/mirror! (fn [_] "/fake-mirror")
                  tick/ensure-sha! (fn [m _ _] m)
                  tick/git-show (fn [& _] "")
                  tick/log (fn [& _])]
      (tick/ship-git-deps! "judah" deps-text ready)
      (tick/ship-git-deps! "judah" deps-text ready)
      (is (= 6 @shell-calls))
      (is (empty? @ready)))))

(deftest cached-parent-still-retries-a-failed-transitive-dependency
  (let [ready (atom {})
        calls (atom [])
        child-sha "2222222222222222222222222222222222222222"
        child-deps (str "{:deps {io.github.example/child {:git/sha \""
                        child-sha "\"}}}")]
    ;; present 照合も搬送も `bash -c` 経由になったので、cmd ではなく
    ;; **コマンド文字列の中身**で区別する（sentinel を要求している方が照合）。
    (with-redefs [tick/sh (fn [cmd args & _]
                            (swap! calls conj [cmd args])
                            (let [line (str/join " " (map str args))]
                              {:exit 0
                               :out (if (and (re-find #"shared" line)
                                             (re-find #"FLEET-CI-DEP-PRESENT" line))
                                      "FLEET-CI-DEP-PRESENT"
                                      "ship failed")}))
                  tick/git (fn [& _] {:exit 0 :out ""})
                  tick/mirror! (fn [repo] repo)
                  tick/ensure-sha! (fn [m _ _] m)
                  tick/git-show (fn [m _ _]
                                  (if (= m "example/shared") child-deps ""))
                  tick/log (fn [& _])]
      (tick/ship-git-deps! "judah" deps-text ready)
      (tick/ship-git-deps! "judah" deps-text ready)
      ;; parent: presence 1 回（2 回目は ready から）。
      ;; child: 毎回 presence + bundle 搬送 + archive fallback 搬送 = 3 回。
      (is (= 7 (count @calls)))
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

;; --- gate declaration identity（ADR-2608137000）--------------------------------

(deftest a-moved-tip-still-triggers-exactly-as-before
  ;; 既定の trigger を壊していないこと。spec-hash が両側にあっても、tip が
  ;; 動いていなければ回さない / 動いていれば回す。
  (is (true? (tick/work-changed? {:tip "b" :last-sha "a" :spec-hash "h" :last-spec "h"})))
  (is (false? (tick/work-changed? {:tip "a" :last-sha "a" :spec-hash "h" :last-spec "h"})))
  ;; tip が解決できていない item は回さない（従来どおり missing に落ちる）
  (is (false? (tick/work-changed? {:tip nil :last-sha nil :spec-hash "h" :last-spec "g"})))
  ;; 一度も回っていない gate は従来どおり回る
  (is (true? (tick/work-changed? {:tip "a" :last-sha nil :spec-hash "h" :last-spec nil}))))

(deftest a-repaired-declaration-triggers-even-when-the-tip-is-dormant
  ;; これが新しい trigger。gh-workflow-assoc-gapki の形。
  (is (true? (tick/work-changed? {:tip "a" :last-sha "a" :spec-hash "new" :last-spec "old"}))))

(deftest a-state-entry-without-a-spec-hash-does-not-trigger
  ;; 移行の安全弁。1,583 entry が spec-hash を持たないので、nil を「変わった」と
  ;; 読むと導入した tick が全 repo を一斉に配置する。
  (is (false? (tick/work-changed? {:tip "a" :last-sha "a" :spec-hash "new" :last-spec nil}))))

(deftest key-order-and-comments-are-not-part-of-the-declaration
  (is (= (tick/decl-hash {:name "x" :gate :jvm-test :cd true})
         (tick/decl-hash {:cd true :gate :jvm-test :name "x"})))
  ;; reader がコメントを捨てるので、コメントだけの編集は hash に出ない
  (is (= (tick/decl-hash (cljs.reader/read-string "{:name \"x\" :gate :jvm-test}"))
         (tick/decl-hash (cljs.reader/read-string ";; why\n{:name \"x\" ;; inline\n :gate :jvm-test}")))))

(deftest editing-one-gate-does-not-move-another-gates-hash
  ;; gates.edn は毎日編集される。1 行の編集で 125 repo が再配置されたら、
  ;; それは元の問題より悪い。
  (let [a {:name "a" :gate :jvm-test}
        b {:name "b" :gate :jvm-test}]
    (is (not= (tick/decl-hash a) (tick/decl-hash (assoc a :min-files 5))))
    (is (= (tick/decl-hash b) (tick/decl-hash b)))))

(deftest derived-keys-do-not-leak-into-the-declaration-hash
  ;; 最悪形の見張り: :tip が hash に混ざると毎 tick 全 gate の spec-hash が動き、
  ;; 毎 tick 125 repo が再配置される。宣言そのものと、tick が work item にした
  ;; あとの map は、同じ hash でなければならない。
  ;; :org は gates.edn に書ける宣言側の key なので decl に含める（tick が
  ;; west から補うこともあるが、補った値は宣言と同じでなければならない）。
  (let [decl {:name "x" :org "o" :gate :nbb-script :script nil :min-files 5}
        as-work (merge decl {:org-repo "o/x" :tip "aaa" :pin "bbb"
                             :last-sha "ccc" :changed? true :spec-hash "zz"
                             :last-spec "yy" :node {:host "judah"}
                             :gate-name "test-x-aaa" :outcome :pass :cid "c"})]
    (is (= (tick/decl-hash decl) (tick/decl-hash as-work)))
    ;; :org は宣言側の key（gates.edn に書ける）なので残る — 消えていないことも見る
    (is (not= (tick/decl-hash decl) (tick/decl-hash (assoc decl :org "other"))))))

(deftest the-gapki-repair-is-the-kind-of-edit-that-now-triggers
  ;; :include-ext に ".kotoba" を足すこと自体が再実行の合図になる。
  (let [before {:name "cloud-itonami-assoc-0126-idn-gapki" :id "gh-workflow-assoc-gapki"
                :include-ext [".yml" ".edn" ".clj" ".cljc"]}
        after  (assoc before :include-ext [".yml" ".edn" ".clj" ".cljc" ".kotoba"])]
    (is (not= (tick/decl-hash before) (tick/decl-hash after)))
    (is (true? (tick/work-changed? {:tip "e261787" :last-sha "e261787"
                                    :spec-hash (tick/decl-hash after)
                                    :last-spec (tick/decl-hash before)})))))


;; ---------------------------------------------------------------------------
;; CD: a lost push race must be retried, as receipt landing already was
;; ---------------------------------------------------------------------------
;;
;; Measured 2026-08-17: three gates went green in one session and TWO of the
;; three pins were left behind (cloud-itonami-isco-4311, tehai), because
;; `advance-pin!` gave up after one `push rejected` while `land-receipt!`
;; retried three times. The failure is silent and only happens on GREEN --
;; the gate passes, the signed receipt lands, and only the pin is stranded.

(def ^:private pin-landing {:repo "com-junkawasaki/root" :branch "main"
                            :west "manifest/west.yml"})

(defn- with-pin-stubs
  "Drive advance-pin! with a scripted sequence of put-file! outcomes."
  [outcomes f]
  (let [remaining (atom outcomes)
        puts (atom 0)
        reads (atom 0)]
    (with-redefs [tick/gh-raw (fn [& _] (swap! reads inc) "west")
                  tick/parse-west (fn [_] {:projects {"r" {:revision "old"}}})
                  tick/replace-revision (fn [& _] "cand")
                  tick/sh (fn [& _] {:exit 0 :out ""})
                  tick/put-file! (fn [& _]
                                   (swap! puts inc)
                                   (let [[o & more] @remaining]
                                     (reset! remaining (vec more))
                                     o))
                  tick/log (fn [& _])]
      (let [r (tick/advance-pin! pin-landing "r" "new")]
        (f r @puts @reads)))))

(deftest pin-advance-retries-a-lost-push-race
  (with-pin-stubs [{:ok false :detail "push rejected (someone else moved main)"}
                   {:ok true :detail "old -> new"}]
    (fn [r puts reads]
      (is (:ok r) "a race lost once must not strand the pin")
      (is (= 2 puts))
      (is (= 2 reads)
          "each attempt re-reads west.yml — retrying against a stale base
           would fail forever"))))

(deftest pin-advance-gives-up-after-three-attempts
  (with-pin-stubs (vec (repeat 5 {:ok false :detail "push rejected (someone else moved main)"}))
    (fn [r puts _]
      (is (not (:ok r)))
      (is (= 3 puts) "bounded, like receipt landing"))))

(deftest pin-advance-does-not-retry-a-refusal
  ;; A pin verification refusal is the same answer every time. Retrying it
  ;; would turn one honest "no" into three, and hide it in the log.
  (with-pin-stubs [{:ok false :detail "pin verification refused: behind"}]
    (fn [r puts _]
      (is (not (:ok r)))
      (is (= 1 puts)))))

(deftest pin-advance-succeeding-first-time-does-not-retry
  (with-pin-stubs [{:ok true :detail "old -> new"}]
    (fn [r puts _]
      (is (:ok r))
      (is (= 1 puts)))))

;; ---------------------------------------------------------------------------
;; A west project's name is not always its GitHub repo name.
;;
;; west allows `repo-path:` to differ from `name:`, and `path:` is then
;; `orgs/<org>/<repo-path>`. tick used to build both the GitHub coordinate and the
;; rad-rids key from the project NAME, so for those projects it asked GitHub about a
;; repository that does not exist.
;;
;; Measured 2026-08-18: 59 of 4,191 projects differ that way. Seven were in gates.edn —
;; `cloud-itonami-gftd-{audio,avatar,illust,motion,rig,sculpt,voice}-actor` — and
;; `git ls-remote git@github.com:cloud-itonami/cloud-itonami-gftd-audio-actor.git`
;; answers "repository does not exist" while `.../gftd-audio-actor.git` returns the
;; pinned sha. A work item with a nil tip is dropped, so those seven gates produced
;; nothing: the tick state file held 2,063 entries and not one matched `gftd-`.
;;
;; Separately, 36 projects have a RID under the path key and none under
;; `orgs/<org>/<name>` — a reflection surface registered and unreachable.

(def ^:private west-fixture
  {:remotes {"cloud-itonami" "git@github.com:cloud-itonami"}
   :projects {"cloud-itonami-gftd-audio-actor"
              {:remote "cloud-itonami" :revision "50caaa0"
               :path "orgs/cloud-itonami/gftd-audio-actor"}
              "kagami"
              {:remote "kotoba-lang" :revision "abc1234"
               :path "orgs/kotoba-lang/kagami"}}})

(deftest repo-name-comes-from-the-west-path-not-the-project-name
  (is (= "gftd-audio-actor"
         (tick/repo-name-of west-fixture "cloud-itonami-gftd-audio-actor")))
  (is (= "kagami" (tick/repo-name-of west-fixture "kagami")))
  (is (= "unregistered" (tick/repo-name-of west-fixture "unregistered"))))

(deftest west-path-is-the-rad-rids-key
  (is (= "orgs/cloud-itonami/gftd-audio-actor"
         (tick/west-path west-fixture "cloud-itonami-gftd-audio-actor")))
  (is (nil? (tick/west-path west-fixture "unregistered"))))

(deftest org-repo-is-built-in-exactly-one-place
  ;; The regression this pins is not "the wrong string" — it is TWO strings. `tip-of` built
  ;; the map key and the work item built the lookup key, and when only one was corrected the
  ;; map held a key nobody asked for while the lookup asked for a key nobody held. Both
  ;; misses surface identically as `no tip resolved`, which is why fixing half of it changed
  ;; nothing observable for a full day.
  (is (= "cloud-itonami/gftd-audio-actor"
         (tick/org-repo-of west-fixture {:name "cloud-itonami-gftd-audio-actor"}))
      "a west project resolves to the repo that exists, not to its project name")
  (is (= "com-junkawasaki/root"
         (tick/org-repo-of west-fixture {:name "root" :org "com-junkawasaki"}))
      "an explicit :org wins — such an entry is not a west project")
  (is (nil? (tick/org-repo-of west-fixture {:name "unknown-to-west"}))
      "and an entry west does not know, with no :org, yields nil rather than a bad key"))

(let [{:keys [fail error]} (run-tests 'tick-unit-test)]
  (when (pos? (+ fail error))
    (js/process.exit 1)))

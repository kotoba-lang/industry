(ns kotoba.signal.membership-test
  "Every test here asserts one of the five invariants in the namespace
  docstring, or the honesty property (a removal reports what it did not
  protect). No crypto backend is needed — that is the point of the split."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.signal.membership :as m]))

(def A "did:key:zA") (def B "did:key:zB") (def C "did:key:zC")
(def t0 "2026-08-08T00:00:00Z")

(defn- st [r] (:state r))

(deftest init-has-one-member-at-epoch-zero
  (let [s (m/init "kagi" A t0)]
    (is (= 0 (:membership/epoch s)))
    (is (= #{A} (m/current-members s)))
    (is (empty? (m/violations s)))))

(deftest every-change-advances-the-epoch
  ;; Invariant 2. A given epoch must have exactly one member set for its life.
  (let [s0 (m/init "kagi" A t0)
        s1 (st (m/add-member s0 B t0))
        s2 (st (m/add-member s1 C t0))
        s3 (st (m/remove-member s2 B t0))]
    (is (= [0 1 2 3] [(:membership/epoch s0) (:membership/epoch s1)
                      (:membership/epoch s2) (:membership/epoch s3)]))
    (is (= #{A C} (m/current-members s3)))))

(deftest join-is-forward-only
  ;; Invariant 3. B joins at epoch 1 and must not be able to read epoch 0.
  (let [s (st (m/add-member (m/init "kagi" A t0) B t0))]
    (is (false? (m/can-decrypt? s B 0)) "a joiner must not read the epoch before it joined")
    (is (true? (m/can-decrypt? s B 1)))
    (is (= #{A} (m/members-at s 0)))
    (is (= #{A B} (m/members-at s 1)))))

(deftest a-join-still-rekeys
  ;; The reason a join costs a distribution round at all: reusing the chain key
  ;; would hand the joiner the past.
  (let [e (:effect (m/add-member (m/init "kagi" A t0) B t0))]
    (is (true? (:create-sender-key e)))
    (is (= :join-must-not-read-past (:reason e)))
    (is (= #{A B} (:distribute-to e)))))

(deftest removal-is-forward-only-and-says-what-it-did-not-protect
  ;; Invariant 4 + the honesty property.
  (let [s1 (st (m/add-member (m/init "kagi" A t0) B t0))     ; epoch 1, B joins
        s2 (st (m/rotate s1 :scheduled t0))                  ; epoch 2
        r  (m/remove-member s2 B t0)                         ; epoch 3, B leaves
        s3 (st r) e (:effect r)]
    (is (= #{A} (:distribute-to e)))
    (is (= #{B} (:never-distribute-to e)))
    (is (false? (m/can-decrypt? s3 B 3)) "removed at 3 → must not hold epoch 3")
    (is (true? (m/can-decrypt? s3 B 2)) "but epoch 2 stays readable — this is not undoable")
    (is (= [1 2] (get-in e [:still-readable-by B]))
        "the effect must report the range the removal did NOT protect")
    (is (= [1 2] (:re-seal-required e))
        "and must name re-sealing as the only thing that would")))

(deftest removed-member-cannot-be-removed-twice
  (let [s (st (m/remove-member (st (m/add-member (m/init "kagi" A t0) B t0)) B t0))]
    (is (= :not-a-member (:error (m/remove-member s B t0))))
    (is (= :already-a-member (:error (m/add-member s A t0))))))

(deftest re-adding-a-member-does-not-restore-their-old-epochs
  ;; The subtle one: B leaves and comes back. Their new interval must start at
  ;; the new epoch, not reopen the gap they were absent for.
  (let [s1 (st (m/add-member (m/init "kagi" A t0) B t0))   ; 1: B in
        s2 (st (m/remove-member s1 B t0))                  ; 2: B out
        s3 (st (m/rotate s2 :scheduled t0))                ; 3: B absent
        s4 (st (m/add-member s3 B t0))]                    ; 4: B back
    (is (false? (m/can-decrypt? s4 B 3)) "the gap must stay closed")
    (is (true? (m/can-decrypt? s4 B 4)))))

(deftest log-is-append-only-and-state-is-derivable-from-it
  ;; Invariant 1 + 5. A membership record you cannot re-derive from its own log
  ;; is not a record.
  (let [s (-> (m/init "kagi" A t0)
              (m/add-member B t0) st
              (m/add-member C t0) st
              (m/rotate :scheduled t0) st
              (m/remove-member B t0) st)]
    (is (= 5 (count (:membership/log s))))
    (is (= [0 1 2 3 4] (map :epoch (:membership/log s))))
    (is (empty? (m/violations s)))))

(deftest violations-actually-fire
  ;; A check that never fails is theatre. Break the state by hand and see it.
  (let [s (st (m/add-member (m/init "kagi" A t0) B t0))]
    (is (empty? (m/violations s)))
    (is (some #{:epoch-head-mismatch} (m/violations (assoc s :membership/epoch 99))))
    (is (some #{:left-before-joined}
              (m/violations (assoc-in s [:membership/members B] {:joined 5 :left 2}))))
    (is (some #{:no-members-left}
              (m/violations (-> s
                                (assoc-in [:membership/members A :left] 2)
                                (assoc-in [:membership/members B :left] 2)))))))

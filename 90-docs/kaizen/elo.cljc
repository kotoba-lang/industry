(ns kaizen.elo
  "The Co-Scientist ranking stage: a round-robin Elo tournament over
  hypotheses, judged by a deterministic fitness function.

  Extracted so a second kaizen loop does not need a second copy. The shape
  is isekai.ux.coscientist's (ADR-0007) as ported into
  `design-quality.coscientist` (ADR-2607132300) — same K, same base, same
  tie-break order, so a hypothesis set ranks identically in either.

  **`design-quality.coscientist` still has its own copy of this.** That is a
  known duplicate, recorded rather than silently created: it lives under a
  directory whose name does not munge to its namespace
  (`design-quality/` vs `design_quality/`), so it does not load through a
  normal require at all, and this repo has no test around it to prove a
  delegation would be behaviour-preserving. Making it delegate is a named
  follow-up with a prerequisite — a test — not a change to make blind.

  The judge is the caller's measured fitness, never an LLM debate: `bout`
  reads `:predicted-gain` first and only breaks ties on shippability and
  effort."
  (:require [clojure.string :as str]))

(def ^:const k-factor 32)
(def ^:const base-rating 1200.0)

(defn expected [ra rb] (/ 1.0 (+ 1.0 (Math/pow 10.0 (/ (- rb ra) 400.0)))))

(def ^:private effort-order {:S 0 :M 1 :L 2})

(defn bout
  "Who wins a pairing. Larger measured gain wins; ties go to the hypothesis
  that can ship now, then to the smaller effort. Deterministic by
  construction — two runs over the same findings produce the same roadmap,
  which is what makes the ranking quotable."
  [a b]
  (let [ga (:predicted-gain a) gb (:predicted-gain b)]
    (cond
      (> ga gb) :a
      (< ga gb) :b
      (and (:shippable? a) (not (:shippable? b))) :a
      (and (:shippable? b) (not (:shippable? a))) :b
      (< (effort-order (:effort a) 1) (effort-order (:effort b) 1)) :a
      (> (effort-order (:effort a) 1) (effort-order (:effort b) 1)) :b
      :else :a)))

(defn rank
  "Round-robin Elo over `hyps`, returning them sorted by rating with `:elo`
  attached. Every pair meets exactly once, so no ordering of the input can
  change the result."
  [hyps]
  (let [ids (mapv :id hyps)
        by-id (into {} (map (juxt :id identity) hyps))
        ratings (reduce
                 (fn [rt [i j]]
                   (let [a (by-id i) b (by-id j)
                         ra (rt i) rb (rt j)
                         sa (if (= :a (bout a b)) 1.0 0.0)]
                     (-> rt
                         (update i + (* k-factor (- sa (expected ra rb))))
                         (update j + (* k-factor (- (- 1.0 sa) (expected rb ra)))))))
                 (zipmap ids (repeat base-rating))
                 (for [i ids j ids :when (neg? (compare i j))] [i j]))]
    (->> hyps
         (map (fn [h] (assoc h :elo (Math/round (ratings (:id h))))))
         (sort-by (juxt (comp - :elo) (comp - :predicted-gain)))
         vec)))

(ns speed-coscientist
  "amu / 綾 speed Co-Scientist — Generate -> Reflect -> Rank (Elo) -> Evolve -> Meta.

   Ported from `kotobase-performance.cost-coscientist` (itself from
   design-quality.coscientist / isekai.ux.coscientist, ADR-0007). The loop
   shape is Google Co-Scientist. The judge is not an LLM.

   Owner criterion (2026-08-18): beat LLVM -O3 on the measured kernel, not
   Cranelift parity. ADR-2608179400 decision 2 named Cranelift as the ceiling;
   ADR-2608189100 supersedes that ceiling. Cranelift remains a checkpoint.

   THE JUDGE is min ns/call on Apple M4, same Lehmer kernel, result
   1830338420. Medians on that host are contention; min and p25 carry the
   signal (ADR-2608179400). A claim that LLVM is beaten seals only through
   `kotoba-lang/perfgate`. Instruction count is not a substitute: codegen
   iterations 1-2 removed bytes and did not move the clock.

   Pure .cljc, no I/O beyond what the caller passes.")

(def llvm-target
  "rustc -C opt-level=3 on this kernel, Apple M4, 2026-08-17."
  {:engine "rustc -> LLVM -O3"
   :min-ns 12.19
   :p25-ns 13.31
   :median-ns 17.32})

(def cranelift-checkpoint
  {:engine "rustc -> Cranelift"
   :min-ns 14.65
   :p25-ns 16.59
   :median-ns 22.47})

(def baseline
  "amu AArch64, 2026-08-17, before this iteration's coalescing pass.

  The 8-round kernel already emits LLVM's 7-instruction remainder round
  (madd + smulh + add + asr + add-lsr#63 + sub-lsl#31 + add) once the leaf
  cache and Mersenne reduction fire. Arithmetic matching LLVM is therefore
  the baseline, not a remaining hypothesis."
  {:engine "amu AArch64"
   :min-ns 15.05
   :p25-ns 15.68
   :median-ns 26.66
   :inst-per-round 7
   :ratio-vs-llvm (/ 15.05 12.19)
   :host "Apple M4, load average 33-44, 25 rotated samples"
   :oracle 1830338420
   :kernel "orgs/kotoba-lang/amu/bench/runtime-comparison/kernel.kotoba"
   :sources {:ns :adr-2608179400
             :inst-per-round :dumped-compile-kir-module-2026-08-18}})

(defn ns-saved
  "THE JUDGE. Nanoseconds saved at the min quantile versus today's amu."
  [after-min-ns]
  (- (:min-ns baseline) after-min-ns))

(def ^:private hypotheses
  [{:id "h-inline-loop-artifact"
    :title "Put the timed loop in the native artifact and inline the leaf"
    :change "kexe-benchmark.c calls kexe_fn8 100k times. rustc inlines kernel
             into main's 100k loop (with black_box). Matching that shape is
             what the 1.24x gap is made of once arithmetic already matches."
    :after-min-ns 12.40
    :basis :measured-shape
    :basis-note "LLVM's AArch64 dump of the same kernel is inlined into the
                 timed loop. The 7-inst round is already identical. Predicted
                 remainder to LLVM (12.19) is black_box vs function-pointer ABI,
                 not more IR fusion."
    :effort :M
    :reversible? true
    :on-critical-path? true
    :admitted? true}

   {:id "h-a64-coalesce-direct-results"
    :title "Write AArch64 producers into phi/return registers"
    :change "Three-operand AArch64 can write the phi or x0 directly and drop
             the adjacent edge/return MOV, provided the source is not live-in
             at the join."
    :after-min-ns 15.05
    :basis :predicted
    :basis-note "Removes a register-to-register MOV. Codegen iteration 3
                 measured that this class of core renames such a move rather
                 than executing it (x86-64 D1: -24 bytes, no time). Predicted
                 clock movement: none. Landed for encoding shape and because
                 the pass has been finished unmerged since 2026-08-12."
    :effort :S
    :reversible? true
    :on-critical-path? false
    :admitted? true}

   {:id "h-fused-srem"
    :title "Fuse quotient-constant + msub into a remainder encoding"
    :change "Emit remainder = v - q*(2^s-1) at encode time so a one-round
             rem does not need the leaf cache to fire Mersenne."
    :after-min-ns 15.05
    :basis :measured-and-refuted-for-this-shape
    :basis-note "The 8-round kernel already matches LLVM's 7 inst/round.
                 Completeness for a one-use divisor, zero on this bench."
    :effort :S
    :reversible? true
    :on-critical-path? false
    :admitted? true}

   {:id "h-licm"
    :title "Hoist loop-invariant arithmetic"
    :change "LICM on the Lehmer kernel."
    :after-min-ns 15.05
    :basis :measured-shape
    :basis-note "Eight serial rounds, v_{i+1} depends on v_i. Nothing to hoist
                 besides the constants the leaf cache already keeps."
    :effort :M
    :reversible? true
    :on-critical-path? false
    :admitted? true}

   {:id "h-autovec"
    :title "Autovectorize the eight rounds"
    :change "SIMD across Park-Miller rounds."
    :after-min-ns 15.05
    :basis :measured-shape
    :basis-note "Loop-carried multiply-add. Predicted zero, same as LICM."
    :effort :L
    :reversible? true
    :on-critical-path? false
    :admitted? true}

   {:id "h-range-mersenne-later-rounds"
    :title "Drop sign correction once v is proved non-negative"
    :change "Rounds 2-8 take two fewer instructions if v is known >= 0."
    :after-min-ns 13.20
    :basis :rejected-unsound
    :basis-note "Exported kernel(n) admits arbitrary i64. A range proof that
                 holds only for the bench's n=200 is not a compiler invariant."
    :effort :M
    :reversible? true
    :on-critical-path? true
    :admitted? false}

   {:id "h-fma"
    :title "Contract madd into an FMA"
    :change "Fuse the integer madd with a later add."
    :after-min-ns 15.05
    :basis :rejected-policy
    :basis-note "Integer Park-Miller is already madd. FP FMA is a different
                 kernel and is refused (silent rounding change)."
    :effort :S
    :reversible? true
    :on-critical-path? false
    :admitted? false}

   {:id "h-pgo"
    :title "Profile-guided layout and inlining"
    :change "Instrument, recompile hot edges."
    :after-min-ns 15.05
    :basis :rejected-policy
    :basis-note "PGO is refused. The kernel has one hot path."
    :effort :L
    :reversible? true
    :on-critical-path? false
    :admitted? false}])

(defn generate [] hypotheses)

(defn reflect
  "Annotate admitted hypotheses with judged saving and risk.

  Risk is not effort. An unsound range proof is worse than a large reversible
  inliner. Instruction-count-only hypotheses that sit off the chain inherit
  the lesson of codegen iterations 1-2 and are marked high risk for the clock
  even when they save bytes."
  [hyps]
  (mapv (fn [h]
          (let [saving (ns-saved (:after-min-ns h))
                share (/ saving (:min-ns baseline))]
            (assoc h
                   :ns-before (:min-ns baseline)
                   :ns-after (:after-min-ns h)
                   :saving saving
                   :saving-share share
                   :gap-to-llvm (- (:after-min-ns h) (:min-ns llvm-target))
                   :reflection
                   {:risk (cond
                            (not (:admitted? h)) :reject
                            (= :measured-and-refuted-for-this-shape (:basis h)) :high
                            (and (not (:on-critical-path? h))
                                 (pos? saving)) :high
                            (and (= :measured-shape (:basis h)) (:reversible? h)) :low
                            (= :predicted (:basis h)) :medium
                            (not (:reversible? h)) :high
                            :else :medium)
                    :note (case (:basis h)
                            :measured-shape
                            "effect read off this kernel's own dump or LLVM's"
                            :predicted
                            "not yet timed; static size only"
                            :measured-and-refuted-for-this-shape
                            "mechanism is real and was measured NOT to help this kernel"
                            :rejected-unsound
                            "would change answers for legal inputs"
                            :rejected-policy
                            "refused: FMA / PGO / kototama optimizer"
                            "unbasis")})))
        hyps))

(defn- expected [ra rb] (/ 1.0 (+ 1.0 (Math/pow 10.0 (/ (- rb ra) 400.0)))))

(defn- bout
  "Larger measured-or-predicted ns saving wins. A refuted-for-this-shape
  hypothesis loses a tie to one that still has a chain to walk. Effort breaks
  remaining ties. Deliberately not breaking on 'already implemented'."
  [a b]
  (let [ea {:S 0 :M 1 :L 2}
        basis-rank {:measured-shape 0 :predicted 1
                    :measured-and-refuted-for-this-shape 2}]
    (cond
      (> (:saving a) (:saving b)) :a
      (< (:saving a) (:saving b)) :b
      (< (basis-rank (:basis a) 3) (basis-rank (:basis b) 3)) :a
      (> (basis-rank (:basis a) 3) (basis-rank (:basis b) 3)) :b
      (< (ea (:effort a) 1) (ea (:effort b) 1)) :a
      (> (ea (:effort a) 1) (ea (:effort b) 1)) :b
      :else :a)))

(defn rank
  "Round-robin Elo, K=32, base 1200 — same convention as the cost and
  design-quality loops. Only admitted hypotheses enter the tournament."
  [hyps]
  (let [admitted (filterv :admitted? hyps)
        ids (mapv :id admitted)
        by-id (into {} (map (juxt :id identity) admitted))
        ratings (reduce
                 (fn [rt [i j]]
                   (let [a (by-id i) b (by-id j)
                         ra (rt i) rb (rt j)
                         sa (if (= :a (bout a b)) 1.0 0.0)]
                     (-> rt
                         (update i + (* 32 (- sa (expected ra rb))))
                         (update j + (* 32 (- (- 1.0 sa) (expected rb ra)))))))
                 (zipmap ids (repeat 1200.0))
                 (for [i ids j ids :when (neg? (compare i j))] [i j]))]
    (->> admitted
         (map #(assoc % :elo (Math/round ^double (ratings (:id %)))))
         (sort-by (juxt (comp - :elo) (comp - :saving)))
         vec)))

(defn evolve
  "The tournament scores one change against today's 15.05 ns. It cannot see
  that arithmetic is already LLVM's 7-inst round, so a fused-srem candidate
  looks like 'the remainder pass' until dumped.

  The pair that actually closes the gap is (keep the 7-inst round) +
  (put the 100k loop in the artifact). That pair is worth the inline
  hypothesis alone, not the sum with fused-srem. Coalescing is finished
  unmerged work and lands as WIP=1 because it is :S; iteration 3 says the
  MOV it removes is free on this core. It is not the closer."
  [ranked]
  (let [by-id (into {} (map (juxt :id identity) ranked))
        inline (by-id "h-inline-loop-artifact")
        fuse (by-id "h-fused-srem")
        coal (by-id "h-a64-coalesce-direct-results")]
    {:batch-id "amu-llvm-plus-1"
     :members ["h-inline-loop-artifact"]
     :order "inline-loop-artifact next; coalescing is the current :S landing"
     :why "Arithmetic already matches LLVM. Further remainder fusion is a
           completeness gap on one-use divisors and does not move this
           kernel. The 1.24x is call overhead versus LLVM inlining."
     :pair-ns (:after-min-ns inline)
     :pair-saving (:saving inline)
     :not-the-sum-of-parts
     {:inline-alone (:after-min-ns inline)
      :fused-srem-alone (:after-min-ns fuse)
      :pair (:after-min-ns inline)
      :coalescing-this-wip (:after-min-ns coal)}
     :wip-this-iteration
     "h-a64-coalesce-direct-results — unmerged since a3ed13b (2026-08-12),
      goldens, three-operand shape. Iteration 3 says a register MOV is free
      on this core; not claimed as the LLVM closer."}))

(defn meta-review
  [ranked evolved]
  (let [top (first ranked)
        rejected (filterv (complement :admitted?) (reflect (generate)))]
    {:baseline-min-ns (:min-ns baseline)
     :llvm-min-ns (:min-ns llvm-target)
     :ratio-vs-llvm (:ratio-vs-llvm baseline)
     :top-pick (:id top)
     :top-saving (:saving top)
     :top-after-ns (:ns-after top)
     :still-above-llvm (max 0.0 (- (:ns-after top) (:min-ns llvm-target)))
     :roadmap ranked
     :batch evolved
     :rejected (mapv #(select-keys % [:id :basis :basis-note]) rejected)
     :what-the-judge-cannot-see
     ["Contention. Medians on this host are wider than the engine gap; only
       min/p25 are quoted, and even those need perfgate before a win is
       claimed."
      "Other kernels. Beating LLVM here does not generalize to alloc, strings,
       or capability calls."
      "Correctness. Range-restricted remainder looks faster and is unsound on
       the exported kernel."
      "x86-64 silicon. Rosetta numbers remain conservative for removing a
       divide and unsafe the other way (ADR-2608179400)."]
     :lessons-from-codegen-iterations-1-2
     "Instruction count is a proxy for time only on the dependency chain.
      Iterations 1-2 removed 12% of x86-64 bytes and did not move the clock."}))

(defn run []
  (let [g (generate)
        r (reflect g)
        k (rank r)
        e (evolve k)]
    (assoc (meta-review k e)
           :llvm llvm-target
           :cranelift cranelift-checkpoint
           :baseline baseline)))

(defn print-ranking
  [result]
  (println "baseline" (:baseline-min-ns result) "ns  llvm" (:llvm-min-ns result)
           "ns  ratio" (:ratio-vs-llvm result))
  (println "top" (:top-pick result) "saves" (:top-saving result)
           "ns ->" (:top-after-ns result))
  (doseq [h (:roadmap result)]
    (println "  elo" (:elo h) (:id h) "after" (:ns-after h)
             "saves" (:saving h) (name (:basis h))
             (name (get-in h [:reflection :risk]))))
  (println "evolve" (pr-str (select-keys (:batch result)
                                         [:batch-id :members :order])))
  (println "rejected" (mapv :id (:rejected result))))

(ns itonami.isic-9601.logic
  "「クリーニング営み」 — the cloud-itonami ISIC 9601 garment-care actor as an
  idle tycoon.

  This is the game's whole rule set: a pure `state + event -> state` reducer
  with no I/O, no atoms, no interop and no host calls, so the same file drives
  the nbb test suite, the browser preview, and (once ported) the
  network-isekai guest.

  It is a TRANSCRIPTION of `cloud-itonami/cloud-itonami-isic-9601`, not an
  invention. Every rule below names the namespace it came from:

    stations      <- `laundry.phase/write-ops` (the five ops, in order)
    tier ladder   <- `laundry.phase/phases`    (0 read-only -> 3 supervised-auto)
    `auto-ops`    <- `laundry.phase/phases`' `:auto` sets
    hazards       <- `laundry.governor`'s six HARD checks
    `confidence-floor` <- `laundry.governor/confidence-floor` (0.6)
    forbidden?    <- `laundry.registry/cleaning-process-forbidden-by-care-label?`

  The design thesis, and the reason this is an idle game specifically: an idle
  tycoon is a game about automating everything, and this shop **structurally
  cannot** automate its last two stations. `laundry.phase` keeps
  `:actuation/apply-cleaning-process` and `:actuation/return-garment` out of
  every phase's `:auto` set -- including phase 3 -- and `laundry.governor`'s
  `high-stakes` set enforces the same invariant independently. So washing a
  real garment and handing it back to its owner stay on the player's finger
  forever, however big the shop grows. `auto-ops` below is the same table; if
  a future edit ever put an actuation op in it, `test/logic_test.cljs`'s
  `actuation-never-auto-at-any-phase` fails.

  Subset discipline: plain maps/vectors/keywords and pure functions only --
  no `defrecord`/`defprotocol`/`atom`/multimethod, no `Math/random` (the RNG
  is an LCG threaded through state, so every run is reproducible from
  `:seed`). That intersection is what both squint (browser preview) and the
  network-isekai kami-clj guest subset accept."
  (:require [itonami.isic-9601.district :as district]))

;; --------------------------------------------------------------------------
;; deterministic RNG -- state carries its own seed, nothing reads a clock
;; --------------------------------------------------------------------------

(defn- next-seed [seed]
  ;; MINSTD (Lehmer, 16807 / 2^31-1). Chosen over the more familiar
  ;; Numerical-Recipes constants because 16807 * (2^31-2) is about 3.6e13,
  ;; comfortably inside a float64 mantissa -- 1103515245 * 2^31 is 2.4e18 and
  ;; would silently lose precision, so nbb and squint would drift apart and
  ;; `:seed` would stop reproducing a run.
  (let [s (mod (* 16807 (if (pos? seed) seed 1)) 2147483647)]
    (if (pos? s) s 1)))

(defn- rand-int* [seed n]
  (let [s (next-seed seed)]
    [s (mod s n)]))

(defn- pick [seed coll]
  (let [[s i] (rand-int* seed (count coll))]
    [s (nth coll i)]))

;; --------------------------------------------------------------------------
;; the actor's tables, transcribed
;; --------------------------------------------------------------------------

(def default-spec
  "The laundry. Kept as the default so every existing caller — the tests, the preview, the
  guest — keeps working unchanged, and so the module-level tables below stay meaningful."
  (district/spec "isic-9601"))

(defn first-station
  "The station a job enters at — the district's first operation."
  [spec] (first (:station-keys spec)))

;; The three stations the rules single out, by name rather than by position.
;;
;; They used to be recomputed by index at each use site — `(nth ks (- n 2))` here,
;; `(min 1 (dec n))` there — and every consumer outside this file had to either repeat that
;; arithmetic or guess. Both guessed the laundry: `preview/ui.cljs` emitted the 差し戻す
;; button only for a station literally named `"verify"`, and `bin/kuriningu.cljs`'s script
;; vocabulary was five hardcoded laundry keys. Five of the eight districts therefore had no
;; reject button at all in the browser, and `--script` silently did nothing and exited 0.
;;
;; A district's chain is `intake → verify → … → act → settle`.

(defn verify-station
  "Where the care label is read and a bad plan can still be rejected cheaply."
  [spec] (nth (:station-keys spec) (min 1 (dec (count (:station-keys spec))))))

(defn act-station
  "The irreversible one — the process is actually applied to the subject here."
  [spec] (nth (:station-keys spec) (- (count (:station-keys spec)) 2)))

(defn settle-station
  "Handing the subject back. Completing this is what counts toward the target."
  [spec] (last (:station-keys spec)))

(defn- station-index
  "Position of `k` in the chain, or -1.

  `keep-indexed` rather than `.indexOf` on an `into-array`: the latter is JS-only, and this
  is a `.cljc` whose entire premise is that the rules run wherever they are read. Compared
  as strings because squint turns a keyword into its name and nbb does not."
  [ks k]
  (or (first (keep-indexed (fn [i x] (when (= (str x) (str k)) i)) ks)) -1))

(defn assess-stations
  "The stations a hired human approver may clear: everything that is not the entry point
  and not one the governor keeps for a human permanently. For the laundry that is
  取扱方法 and 資格照合, which is what it always was."
  [spec]
  (set (keep (fn [st] (when (and (not= (:key st) (first (:station-keys spec)))
                                 (not (:hard-human? st)))
                        (:key st)))
             (:stations spec))))

(defn spec-of
  "The board this shop is running. State carries it, because the rules ARE the district:
  which operations exist, which of them never automates, what the subject is."
  [st]
  (or (:spec st) default-spec))

(def stations
  "`laundry.phase/write-ops` in operating order. `:op` is the actor's own
  keyword; `:hard-human?` marks the two ops `laundry.governor/high-stakes`
  never lets commit without a human."
  (:stations default-spec))

;; NOTE: `(mapv :key stations)` would be idiomatic Clojure, but a keyword is
;; not callable in every runtime this file has to work in -- squint compiles
;; `:key` to the string "key", which throws in function position. Keyword-as-
;; function is avoided throughout this namespace for that reason.
(def station-keys (:station-keys default-spec))

(defn station
  ([k] (station default-spec k))
  ([spec k] (first (filter (fn [s] (= (:key s) k)) (:stations spec)))))

(def phase-table
  "`laundry.phase/phases`. `:writes` is which stations exist at all at this
  tier; `:auto` is which may commit with no human in the loop."
  (:phase-table default-spec))

(def max-phase 3)

(def confidence-floor
  "`laundry.governor/confidence-floor` -- below this the advisor's own
  proposal escalates to a human even when no hard check fired."
  0.6)

(defn auto-ops
  "Stations that may run with no human at `phase`. The invariant this game is built on:
  the district's never-auto op is never a member, at any phase."
  ([phase] (auto-ops default-spec phase))
  ([spec phase]
   (let [t (:phase-table spec)]
     (:auto (get t phase (get t max-phase))))))

(defn writes-at
  ([phase] (writes-at default-spec phase))
  ([spec phase]
   (let [t (:phase-table spec)]
     (:writes (get t phase (get t max-phase))))))

;; --------------------------------------------------------------------------
;; garment ground truth
;; --------------------------------------------------------------------------

(def processes (:processes default-spec))

(def garment-kinds (:kinds default-spec))

(def evidence-required
  "`laundry.facts/required-evidence-satisfied?` -- the records the
  jurisdiction wants on file. They are produced in this order, one per station
  (`:intake` files the consent, `:verify` the care plan, `:screen` the label
  verification, `:clean` the cleaning record)."
  ["顧客同意記録" "取扱方法記録" "洗濯表示確認記録" "洗濯処理記録"])

(defn evidence-needed-for
  "How much of the checklist must already be on file before this station may
  commit. `:clean` needs three, NOT four: the fourth record is the 洗濯処理記録,
  which the cleaning itself produces -- demanding it beforehand would make the
  station unreachable. `:return` needs all four, which is exactly what makes
  `:return` unreachable until the garment has actually been cleaned."
  ([station-key] (evidence-needed-for default-spec station-key))
  ([spec station-key]
   (let [ks (:station-keys spec)
         n (count ks)
         i (station-index ks station-key)]
     ;; Every station EXCEPT the last one files a record, so the checklist is one shorter
     ;; than the station list — for the laundry, five stations and four records. Using the
     ;; station count instead makes the settling act demand a record nothing produces, and
     ;; the shop stalls one step from the end with no rule to blame.
     (let [checklist (dec n)]
       (cond
         (= i (dec n)) checklist         ; settling needs the whole checklist
         (= i (- n 2)) (dec checklist)   ; the act before it produces the last record
         :else 0)))))

;; --------------------------------------------------------------------------
;; the six HARD checks -- `laundry.governor`
;; --------------------------------------------------------------------------

(defn forbidden-by-care-label?
  "`laundry.registry/cleaning-process-forbidden-by-care-label?` -- a pure
  ground-truth recompute against the garment's own permanent fields. The
  governor never asks the advisor about this; it looks at the label itself."
  [g]
  (boolean (some (fn [p] (= p (:proposed-process g))) (:forbidden g))))

(defn hard-violation
  "The governor's six checks in priority order, for `station-key` acting on
  garment `g` in shop `st`. Returns a violation map or nil. All six are HARD:
  a human approver cannot override any of them."
  [st station-key g]
  (let [spec (spec-of st)
        ;; compared by name, not by index. The index form used `.indexOf` on an
        ;; `into-array`, which is JS-only — in a `.cljc` file whose whole point is that the
        ;; rules run anywhere.
        act? (= (str station-key) (str (act-station spec)))
        settle? (= (str station-key) (str (settle-station spec)))
        ;; "actuation" is the act and its settlement: the last two stations. For 9601 that
        ;; is :clean and :return exactly.
        actuation? (or act? settle?)
        verify-station (verify-station spec)]
    (cond
      ;; 1. spec-basis -- the advisor cited no official source
      (and (or actuation? (= station-key verify-station)) (not (:cited? g)))
      {:rule :no-spec-basis
       :detail "公式spec-basisの引用が無い提案はクリーニング業運営基準として扱えない"}

      ;; 2. evidence incomplete -- the jurisdiction's checklist is not satisfied
      (and actuation? (< (:evidence g) (evidence-needed-for spec station-key)))
      {:rule :evidence-incomplete
       :detail (str "必要書類が " (:evidence g) "/" (evidence-needed-for spec station-key)
                    " しか揃っていない")}

      ;; 3. the care label itself forbids the proposed process
      (and act? (forbidden-by-care-label? g))
      {:rule :cleaning-process-forbidden-by-care-label
       :detail (str (:desc g) " の" (:conflict-label spec) "が「" (:proposed-process g)
                    "」を禁止している")}

      ;; 4. solvent-handling certification not current -- evaluated
      ;;    unconditionally, exactly as the governor does
      (not (:cert-current? st))
      {:rule :certification-not-current
       :detail (str (:cert-label spec) "が最新でない状態では提案を進められない")}

      ;; 5. double application
      (and act? (:cleaning-applied? g))
      {:rule :already-cleaned :detail (str (:id g) " は既に処理済み")}

      ;; 6. double return
      (and settle? (:garment-returned? g))
      {:rule :already-returned :detail (str (:id g) " は既に完了済み")}

      :else nil)))

(defn disposition
  "`laundry.governor/check` composed with `laundry.phase/gate`. Returns
  `:hold` | `:escalate` | `:commit` for acting on `g` at `station-key`.

  `:hold` cannot be approved away. `:escalate` means a human must say yes --
  either the player's tap or a hired approver. `:commit` means it may run
  with nobody in the loop, which only ever happens for `:intake` at phase 3."
  [st station-key g]
  (let [phase (:phase st)
        spec (spec-of st)]
    (cond
      (hard-violation st station-key g)                    :hold
      (not (contains? (writes-at spec phase) station-key)) :hold
      (:hard-human? (station spec station-key))            :escalate
      (< (:confidence g) confidence-floor)            :escalate
      (contains? (auto-ops spec phase) station-key)        :commit
      :else                                           :escalate)))

;; --------------------------------------------------------------------------
;; shop economy
;; --------------------------------------------------------------------------

(def upgrade-base
  "Cash for the first upgrade of each station; each level multiplies by 1.6.
  `:approver` is the one upgrade that buys automation: hiring a human
  approver lets the shop clear `:escalate` on the three assessment stations
  by itself. It deliberately does NOT touch `:clean`/`:return` -- those
  escalate because of `high-stakes`, and no amount of cash changes that.

  The laundry's map, kept for reference / fallback. Other districts read
  `(:upgrade (spec-of st))`, which is role-keyed in `district/spec` so settle
  and act keep their prices when the chain is shorter than five."
  (:upgrade default-spec))

(defn upgrade-cost [st k]
  (let [lvl (get-in st [:levels k] 0)
        base (get (:upgrade (spec-of st)) k (get upgrade-base k 100))]
    ;; 1.6^lvl by repeated multiplication -- no `Math/pow` interop, so the
    ;; guest subset and squint both accept it
    (int (reduce (fn [acc _] (* acc 1.6)) base (range lvl)))))

(defn- speed-of
  "Ticks of work a station needs. Level 1 = 8 ticks, halving asymptotically."
  [st k]
  (let [lvl (get-in st [:levels k] 1)]
    (max 1 (int (/ 24 (+ 2 lvl))))))

(def station-payout
  "The laundry's per-station payouts, kept for reference. A running shop reads
  `(:payout (spec-of st))`, which is the same numbers for 9601 — and, for every
  other district, the same *roles* (entry/verify/middle/act/settle) rather than
  a truncated position table.

  Cash each committed station act earns, before the tier multiplier.

  A real クリーニング屋 is paid at drop-off and settles the balance at
  collection, so intake/verify/screen carry income too. That is not
  decoration: `laundry.phase` opens the stations one tier at a time, so if
  only `:return` paid, phases 1 and 2 would have literally no income and the
  tier ladder could never be climbed."
  (:payout default-spec))

;; --------------------------------------------------------------------------
;; initial state
;; --------------------------------------------------------------------------

(def victory-target
  "`:flow :victory :when-picked <n>` in the sibling itonami games -- here, the
  number of garments returned that closes the audit."
  40)

(defn init
  "A fresh shop. `seed` makes the whole run reproducible; `district-id` chooses which
  business you are running (default the laundry). An unknown or unlabelled district falls
  back to the laundry rather than starting a shop with no stations."
  ([seed] (init seed "isic-9601"))
  ([seed district-id]
   (let [spec (or (district/spec district-id) default-spec)]
    {:spec spec
     :district (:id spec)
     :seed seed
     :t 0
     :phase 1
     :cash 0
     :lives 3
     :next-id 1
     :queue []
     :garments []
     :ledger []
     :returned 0
     :commits 0
     ;; one level per station this district actually has, plus the approver
     :levels (assoc (into {} (map (fn [k] [k 1]) (:station-keys spec))) :approver 0)
     :cert-current? true
     :cert-ticks 1200
     :flow :playing})))

;; --------------------------------------------------------------------------
;; audit ledger -- the actor writes a fact for every disposition
;; --------------------------------------------------------------------------

(def ledger-window 40)

(defn- log
  [st fact]
  (update st :ledger
          (fn [l]
            (let [v (conj (vec l) (assoc fact :t (:t st)))
                  over (- (count v) ledger-window)]
              (if (> over 0) (vec (drop over v)) v)))))

;; --------------------------------------------------------------------------
;; customers
;; --------------------------------------------------------------------------

(defn- spawn-garment [st]
  (let [seed (:seed st)
        spec (spec-of st)
        [s1 kind] (pick seed (:kinds spec))
        [s2 proc] (pick s1 (:processes spec))
        [s3 conf-r] (rand-int* s2 100)
        [s4 cite-r] (rand-int* s3 100)
        id (str "garment-" (:next-id st))]
    (-> st
        (assoc :seed s4)
        (update :next-id inc)
        (update :queue conj
                {:id id
                 :desc (:desc kind)
                 :forbidden (:forbidden kind)
                 :proposed-process proc
                 ;; the advisor's own self-reported confidence
                 :confidence (/ (+ 40 conf-r) 100.0)
                 ;; ~1 in 12 proposals arrives with a fabricated spec-basis
                 :cited? (>= cite-r 8)
                 :evidence 0
                 :stage (first-station (spec-of st))
                 :work 0
                 :cleaning-applied? false
                 :garment-returned? false
                 :blocked? false}))))

(defn- arrival-interval [st]
  ;; busier as the shop grows; floor of 3 ticks
  (max 3 (- 10 (get-in st [:levels (first-station (spec-of st))] 1))))

;; --------------------------------------------------------------------------
;; acting on a garment
;; --------------------------------------------------------------------------

(def ^:private stage-after
  "Explicit successor table for the laundry — the fallback when a spec carries none."
  {:intake :verify :verify :screen :screen :clean :clean :return :return :done})

(defn- next-stage
  ([k] (next-stage default-spec k))
  ([spec k] (get (:stage-after spec) k :done)))

(defn- advance-garment
  "Move `g` one station on and file the evidence record that station produces."
  [spec g]
  (let [k (:stage g)
        ks (:station-keys spec)
        n (count ks)
        i (station-index ks k)]
    (-> g
        (assoc :work 0 :blocked? false :awaiting? false)
        ;; each station files one more evidence record; `:clean` files the
        ;; fourth (the 洗濯処理記録), which is what unlocks `:return`
        (update :evidence (fn [e] (min (dec n) (inc (or e 0)))))
        (assoc :cleaning-applied? (or (:cleaning-applied? g) (= i (- n 2))))
        (assoc :garment-returned? (or (:garment-returned? g) (= i (dec n))))
        (assoc :stage (next-stage spec k)))))

(defn- apply-commit
  "Shop-level effects of a committed station act: pay, count, audit."
  [st g]
  (let [spec (spec-of st)
        k (:stage g)
        last-k (last (:station-keys spec))
        paid (* (get (:payout spec) k 0) (:phase st))]
    (-> st
        (update :cash + paid)
        (update :commits inc)
        (update :returned (fn [n] (if (= k last-k) (inc n) n)))
        ;; `:forbidden-applied?` is written on every commit, not only the bad ones. An
        ;; audit line that appears only when something went wrong cannot be used to prove
        ;; that nothing went wrong — `(filter :forbidden-applied? ledger)` would be empty
        ;; both when the invariant holds and when the key was never written at all, and
        ;; `logic_test`'s "no forbidden process was ever applied" spent its whole life
        ;; asserting the second case. Recording false explicitly is what makes the absence
        ;; of true mean something.
        (log {:t* :committed :op (:op (station spec k)) :subject (:id g)
              :disposition :commit
              :forbidden-applied? (boolean (and (= k (act-station spec))
                                                (forbidden-by-care-label? g)))}))))

(defn- apply-hold
  "A governor HARD violation -- `laundry.governor/hold-fact`. The garment
  leaves the shop unprocessed and the ledger records which of the six rules
  fired. No human can approve past this.

  A life is the customer's trust, so it is docked for holds the shop could
  have avoided -- not for `:certification-not-current`, which is a blocking
  state the shop sits in until it renews (see `tick`), and docking per garment
  would turn one lapse into an instant loss."
  [st g v]
  (-> st
      (update :lives (fn [n] (if (= (:rule v) :certification-not-current) n (dec n))))
      (log {:t* :governor-hold :op (:op (station (spec-of st) (:stage g))) :subject (:id g)
            :disposition :hold :basis (:rule v) :detail (:detail v)})))

(defn- replace-garment [st g']
  (update st :garments
          (fn [gs] (mapv (fn [x] (if (= (:id x) (:id g')) g' x)) gs))))

(defn- drop-garment [st id]
  (update st :garments (fn [gs] (vec (remove (fn [x] (= (:id x) id)) gs)))))

(defn- settle
  "Run the governor + phase gate for `g` at its current stage and apply the
  outcome. `human?` is true when a human (the player's tap, or a hired
  approver) is standing behind this act.

  Note the two kinds of HOLD, which `laundry.phase/gate` also keeps apart: a
  governor HARD violation is a real compliance failure and costs a life, while
  `:phase-disabled` just means this tier has not opened that station yet. The
  second is not a mistake -- the garment simply waits."
  [st g human?]
  (let [k (:stage g)
        d (disposition st k g)
        v (hard-violation st k g)]
    (cond
      (and (= d :hold) v)
      (-> st (apply-hold g v) (drop-garment (:id g)))

      ;; phase-disabled: the station is not open at this tier. Nothing
      ;; happens, nothing is lost.
      (= d :hold)
      (replace-garment st (assoc g :blocked? true :work 0))

      (and (= d :escalate) (not human?))
      ;; waits for a human; nothing commits
      (replace-garment st (assoc g :awaiting? true))

      :else
      (let [st' (apply-commit st g)
            g'  (advance-garment (spec-of st) g)]
        (if (= (:stage g') :done)
          (drop-garment st' (:id g'))
          (replace-garment st' g'))))))

;; --------------------------------------------------------------------------
;; events
;; --------------------------------------------------------------------------

(defn ready?
  "Has this garment finished its station's work? Only then is there anything
  for a human to approve -- tapping early does nothing, which is what gives
  the shop its wait-then-tap rhythm."
  [st g]
  (>= (or (:work g) 0) (speed-of st (:stage g))))

(defn tap
  "The player taps a station. This is the ONLY way `:clean` and `:return` ever
  advance -- see the ns docstring. Acts on the garment that has been waiting
  at that station the longest, and only once its machine time is up."
  [st station-key]
  (if (not= (:flow st) :playing)
    st
    (let [waiting (filter (fn [g] (and (= (:stage g) station-key) (ready? st g)))
                          (:garments st))]
      (if (empty? waiting)
        st
        (settle st (first waiting) true)))))

(defn- compliant-process
  "The first process the garment's own care label does not forbid."
  [spec g]
  (or (first (filter (fn [p] (not (forbidden-by-care-label? (assoc g :proposed-process p))))
                     (:processes spec)))
      (:proposed-process g)))

(defn reject
  "The human approver sends the advisor's care plan back -- `laundry.
  operation`'s approval workflow resumes with `{:approval {:status
  :rejected}}`. The plan is redrafted against the garment's own care label and
  the station's work restarts.

  This is the player's counter-play to hazard 3: the governor WILL catch a
  care-label-forbidden process at `:clean`, but catching it there costs the
  customer's trust. Reading the label at 取扱方法 and rejecting the plan costs
  only time. Both outcomes are correct compliance; only one keeps the shop."
  [st station-key]
  (if (not= (:flow st) :playing)
    st
    (let [waiting (filter (fn [g] (and (= (:stage g) station-key) (ready? st g)))
                          (:garments st))]
      (if (empty? waiting)
        st
        (let [g (first waiting)]
          (-> st
              (replace-garment (assoc g :proposed-process (compliant-process (spec-of st) g)
                                        :cited? true :work 0 :awaiting? false))
              (log {:t* :approval :op (:op (station (spec-of st) station-key)) :subject (:id g)
                    :disposition :rejected
                    :detail "取扱方法を洗濯表示に合わせて差し戻した"})))))))

(defn take-in
  "Pull the head of the queue onto the floor. Auto at phase 3, tap otherwise."
  [st human?]
  (let [q (:queue st)]
    (cond
      (not= (:flow st) :playing) st
      (empty? q) st
      (>= (count (:garments st)) (* 3 (get-in st [:levels (first-station (spec-of st))] 1))) st
      :else
      (let [g (first q)
            entry (first-station (spec-of st))
            d (disposition st entry g)]
        (cond
          ;; a governor HARD violation at the counter -- the customer is
          ;; turned away and the shop takes the hit
          (and (= d :hold) (hard-violation st entry g))
          (-> st (assoc :queue (vec (rest q)))
              (apply-hold (assoc g :stage entry) (hard-violation st entry g)))

          ;; phase 0: the counter is not open. The queue simply waits.
          (= d :hold) st

          (and (= d :escalate) (not human?)) st

          :else
          (-> st
              (assoc :queue (vec (rest q)))
              (update :garments conj (assoc g :stage (next-stage (spec-of st) entry)
                                              :evidence 1 :work 0))
              (update :cash + (* (get (:payout (spec-of st)) entry 0) (:phase st)))
              (update :commits inc)
              (log {:t* :committed :op (:op (station (spec-of st) entry)) :subject (:id g)
                    :disposition :commit
                    ;; the approver only ever clears the intake station, which is never the
                    ;; acting station — but the key is written here too, because a ledger
                    ;; where some commits carry the flag and some omit it is one where
                    ;; "no entry has it set" is again ambiguous
                    :forbidden-applied? false})))))))

(defn buy
  "Spend cash on a station level, or on hiring a human approver."
  [st k]
  (let [cost (upgrade-cost st k)]
    (if (< (:cash st) cost)
      st
      (-> st
          (update :cash - cost)
          (update-in [:levels k] (fn [l] (inc (or l 0))))
          (log {:t* :upgrade :op k :disposition :commit})))))

(defn renew-certification
  "Renew the solvent-handling certification. While it is lapsed, check 4 HARD holds every
  op in the shop -- the governor evaluates it unconditionally.

  Renewing one that is still current is a NO-OP, not a purchase. It used to charge the fee
  and reset the timer, which meant a player who tapped the button twice, or any script
  that called it every turn, quietly paid ¥90 a tick and never climbed a tier. Found by
  running the reference strategy from the CLI, where the shop sat at phase 1 with ¥80
  forever; the browser preview hid it because the button only invites a press while the
  certification is lapsed."
  [st]
  (let [cost 90]
    (if (or (:cert-current? st) (< (:cash st) cost))
      st
      (-> st
          (update :cash - cost)
          (assoc :cert-current? true :cert-ticks 1200)
          ;; the district's own screening op, not the laundry's. `:certification/screen`
          ;; is a real op in 9601 and in none of the other seven, so the audit ledger was
          ;; naming an operation the actor does not have.
          (log {:t* :committed :op (:op (station (spec-of st) (verify-station (spec-of st))))
                :disposition :commit
                ;; renewal is a `:committed` line too, so it carries the flag. Every entry
                ;; of a kind must answer the question or "none answered true" means nothing.
                :forbidden-applied? false
                :detail "溶剤取扱資格を更新した"})))))

(defn phase-requirement
  "What the next tier costs: cash, and committed acts already on the audit
  ledger. Throughput is counted in COMMITS, not returns -- `:return` does not
  exist below phase 3, so gating on returns would deadlock the ladder."
  [st]
  (let [p (:phase st)]
    {:cash (* 120 p) :commits (* 8 p)}))

(defn advance-phase
  "Move up a tier. Costs cash and requires throughput, mirroring the actor's
  staged rollout -- you do not get supervised-auto on day one."
  [st]
  (let [p (:phase st)
        {:keys [cash commits]} (phase-requirement st)]
    (if (or (>= p max-phase) (< (:cash st) cash) (< (:commits st) commits))
      st
      (-> st
          (update :cash - cash)
          (update :phase inc)
          (log {:t* :phase :op :rollout :disposition :commit
                :detail (str "phase " p " -> " (inc p) " "
                             (:label (get (:phase-table (spec-of st)) (inc p))))})))))

(defn- approver-pass
  "A hired human approver clears escalations on the three assessment stations.
  It never touches `:clean`/`:return`: those escalate because of
  `laundry.governor/high-stakes`, which is not a staffing problem."
  [st]
  (let [n (get-in st [:levels :approver] 0)]
    (if (zero? n)
      st
      (reduce
       (fn [acc g]
         (let [g' (first (filter (fn [x] (= (:id x) (:id g))) (:garments acc)))]
           (cond
             (not (and g' (contains? (assess-stations (spec-of acc)) (:stage g'))
                       (ready? acc g'))) acc
             ;; a hired approver reads the care label too -- otherwise
             ;; automating the assessment stations would be strictly worse
             ;; than doing them by hand
             (and (= (:stage g') (second (:station-keys (spec-of acc))))
                  (or (forbidden-by-care-label? g') (not (:cited? g'))))
             (reject acc (second (:station-keys (spec-of acc))))
             :else (settle acc g' true))))
       st
       (take n (filter (fn [g] (contains? (assess-stations (spec-of st)) (:stage g)))
                       (:garments st)))))))

(defn- sweep-off-system
  "A garment sitting at a station this tier has not opened yet is not stuck --
  it is handled BY HAND, off-system, the way the shop worked before the actor
  covered that step. Once its machine time is up it leaves, paying half.

  This is what makes phases 1 and 2 playable at all, and it is the honest
  reading of `laundry.phase`: a phase-disabled op is not a failure, it is a
  step the actor does not cover yet. Without it the floor fills with garments
  the tier cannot process and the shop deadlocks."
  [st]
  (reduce
   (fn [acc g]
     (if (and (not (contains? (writes-at (spec-of acc) (:phase acc)) (:stage g)))
              (ready? acc g))
       (-> acc
           (update :cash + (int (/ (* (get (:payout (spec-of acc)) (:stage g) 0) (:phase acc)) 2)))
           (drop-garment (:id g))
           (log {:t* :off-system :op (:op (station (spec-of acc) (:stage g))) :subject (:id g)
                 :disposition :manual
                 :detail (str (:label (station (spec-of acc) (:stage g))) " は phase " (:phase acc)
                              " では未対応 — 手作業で処理した")}))
       acc))
   st
   (:garments st)))

(defn- accrue-work
  "Every garment on the floor makes progress at its station. Reaching the
  station's speed threshold means the WORK is done -- whether it may then
  commit is the governor's and the phase gate's call, not the timer's."
  [st]
  (update st :garments
          (fn [gs]
            (mapv (fn [g]
                    (update g :work (fn [w] (min (speed-of st (:stage g)) (inc (or w 0))))))
                  gs))))

(defn stalled?
  "Lapsed certification and cash below the ¥90 renewal fee — the shop cannot earn
  (every op HARD-holds) and cannot recover. Distinct from `:gameover` (trust gone)
  and `:victory` (audit closed): the run is soft-locked."
  [st]
  (and (not (:cert-current? st))
       (< (:cash st) 90)))

(defn tick
  "One step of shop time."
  [st]
  (if (not= (:flow st) :playing)
    st
    (let [st (update st :t inc)
          ;; certification decays and eventually lapses
          st (update st :cert-ticks dec)
          st (if (<= (:cert-ticks st) 0) (assoc st :cert-current? false) st)
          ;; A lapsed solvent-handling certification stops the shop dead: the
          ;; governor evaluates check 4 UNCONDITIONALLY, so no op anywhere can
          ;; proceed. Time still passes and customers still queue -- renew.
          st (if (:cert-current? st)
               (let [st (if (zero? (mod (:t st) (arrival-interval st))) (spawn-garment st) st)
                     ;; intake auto-commits only where the phase table allows it
                     st (if (contains? (auto-ops (spec-of st) (:phase st))
                            (first-station (spec-of st)))
               (take-in st false) st)
                     st (accrue-work st)
                     st (sweep-off-system st)]
                 (approver-pass st))
               (if (zero? (mod (:t st) (arrival-interval st))) (spawn-garment st) st))]
      (cond
        (<= (:lives st) 0)                  (assoc st :flow :gameover)
        (>= (:returned st) victory-target)  (assoc st :flow :victory)
        (stalled? st)                       (assoc st :flow :stalled)
        :else st))))

(defn reduce-event
  "The single entry point a host drives. `ev` is `[:tick]`, `[:tap k]`,
  `[:take-in]`, `[:buy k]`, `[:renew]` or `[:phase]`.

  Once `:flow` leaves `:playing` every event is a no-op -- a finished run does
  not keep docking lives or earning cash. `:stalled` still accepts `:renew`
  (escape if cash somehow appears) and `:reset`."
  [st ev]
  (let [[kind arg] ev
        playing? (= (:flow st) :playing)
        stalled-ok? (and (= (:flow st) :stalled)
                         (or (= kind :renew) (= kind :reset)))]
    (if (and (not playing?) (not= kind :reset) (not stalled-ok?))
      st
      (cond
        (= kind :tick)    (tick st)
        (= kind :tap)     (tap st arg)
        (= kind :reject)  (reject st arg)
        (= kind :take-in) (take-in st true)
        (= kind :buy)     (buy st arg)
        (= kind :renew)   (let [st' (renew-certification st)]
                            ;; renew may clear the soft-lock; leave :stalled only while stuck
                            (if (and (= (:flow st) :stalled) (not (stalled? st')))
                              (assoc st' :flow :playing)
                              st'))
        (= kind :phase)   (advance-phase st)
        ;; same district. The 1-arity `init` defaults to the laundry, so a reset in 洗車 or
        ;; 汚染浄化 quietly handed the player a different business.
        (= kind :reset)   (init (or arg (:seed st)) (:district st))
        :else st))))

(defn summary
  "A flat, host-agnostic view for whatever is drawing the shop -- the browser
  preview and the network-isekai guest both render from this and nothing else,
  so neither one needs to know the internal state shape."
  [st]
  {:t (:t st)
     :phase (:phase st)
   :phase-label (:label (get (:phase-table (spec-of st)) (:phase st)))
   :next-phase (when (< (:phase st) max-phase) (phase-requirement st))
     :cash (:cash st)
     :lives (:lives st)
     :queue (count (:queue st))
     :commits (:commits st)
     :returned (:returned st)
   :target victory-target
   :cert-current? (:cert-current? st)
   :cert-ticks (:cert-ticks st)
     :levels (:levels st)
   ;; keyed from THIS district's upgrade table, not the laundry's. It used to read
   ;; `(keys upgrade-base)` — the five laundry stations plus :approver — so in the other
   ;; seven districts `station-card` looked its own key up in a map that did not have it and
   ;; rendered "強化 ¥" with no number. The fallback in `upgrade-cost` meant the button still
   ;; worked when pressed; only the price was missing, which is the kind of breakage that
   ;; survives a play-through.
   :costs (into {} (map (fn [k] [k (upgrade-cost st k)])
                        (keys (:upgrade (spec-of st)))))
   ;; A state dumped before `:flow :stalled` existed may still be soft-locked while
   ;; claiming `:playing`. Name the stall for hosts (CLI exit, HUD) without waiting
   ;; for another tick.
   :flow (if (and (= (:flow st) :playing) (stalled? st)) :stalled (:flow st))
     :ledger (:ledger st)
   :district (:district st)
   :district-label (:label (spec-of st))
   :subject (:subject (spec-of st))
   :stations (mapv (fn [s]
                     (let [k (:key s)
                           here (filter (fn [g] (= (:stage g) k)) (:garments st))]
                       {:key k
                        :label (:label s)
                        ;; the view must not work out which station takes a rejection by
                        ;; matching a name. `preview/ui.cljs` matched the literal string
                        ;; "verify" and so drew no 差し戻す button in the five districts whose
                        ;; second station is called something else — reading the care label
                        ;; is the whole skill of the game, and in those five it was
                        ;; unreachable, which makes a careful run lose exactly like a
                        ;; careless one.
                        :rejectable? (= (str k) (str (verify-station (spec-of st))))
                        :open? (contains? (writes-at (spec-of st) (:phase st)) k)
                        :auto? (contains? (auto-ops (spec-of st) (:phase st)) k)
                        :hard-human? (:hard-human? s)
                        :level (get-in st [:levels k] 1)
                        :count (count here)
                        :ready (count (filter (fn [g] (ready? st g)) here))
                        :garments (mapv (fn [g]
                                          {:id (:id g) :desc (:desc g)
                                           :process (:proposed-process g)
                                           :forbidden (:forbidden g)
                                           :evidence (:evidence g)
                                           :confidence (:confidence g)
                                           :cited? (:cited? g)
                                           :work (:work g)
                                           :need (speed-of st k)
                                           :ready? (ready? st g)
                                           ;; what the governor would say about
                                           ;; this garment HERE, right now
                                           :risk (:rule (hard-violation st k g))
                                           ;; and what it will say at `:clean`
                                           ;; unless the plan is rejected first
                                           ;; -- this is what reading the care
                                           ;; label tells the player, and it is
                                           ;; visible from `:verify` onward
                                           :label-conflict? (forbidden-by-care-label? g)})
                                        here)}))
                   (:stations (spec-of st)))})

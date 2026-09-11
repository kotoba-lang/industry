(ns kotoba.signal.membership
  "Membership bookkeeping for the sender-keys group ratchet — the layer this
  library's own README names as the missing one:

      \"the sender-keys chain ratchet itself is implemented, membership
       bookkeeping is not\"
      \"Key revocation / rotation policy ... a scheduler/policy for *when*
       does not [exist]\"

  This namespace is that *when* and *to whom*. It holds **no keys and performs
  no crypto** — it is a pure value→value decision core over an epoch counter and
  a member set, and it tells the caller which `group/create-sender-key` and
  `group/distribution-message` calls to make. That is deliberate:

  - `group.clj` and `group.cljs` are separate host backends. Bookkeeping that
    touches no primitive can be a single portable `.cljc` and stay identical on
    both, so the two hosts cannot drift on *who is in the group*.
  - Keeping it key-free means the membership rules are reviewable without
    reasoning about ratchet state, and testable without a crypto backend.

  ## The one thing this cannot do, stated up front

  **Removing a member does not un-read what they already read, and does not
  take back the chain keys they already hold.** A sender-key chain is forward-
  secure only in the sense that a *new* chain is unrelated to the old one;
  epochs the removed member was in stay readable to them forever. This namespace
  therefore never reports a removal as if it were complete — `remove-member`
  returns `:still-readable-epochs`, and callers are expected to surface it
  rather than drop it. A membership layer that let you believe otherwise would
  be worse than none.

  ## Invariants

  1. Epoch is monotonically increasing. It is never reset or rewound.
  2. **Every membership change advances the epoch.** A given epoch has exactly
     one member set for its whole life.
  3. **Joins are forward-only.** A member added at epoch n cannot decrypt
     epochs < n; the caller must not hand them an older chain key.
  4. **Removals are forward-only.** A member removed at epoch n keeps
     epochs [joined, n-1]; the caller must not hand them the epoch-n key.
  5. The log is append-only. Who joined and who left, and when, is not erasable
     — the reciprocal-watching (相互監視) requirement of the charter applies to
     membership itself, not only to reads.

  Domain errors are returned as `{:error ...}` values, never thrown: 'already a
  member' is an ordinary answer, not an exceptional condition."
  (:require [clojure.set :as set]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------
;;
;; {:membership/compartment "kagi-rotation"
;;  :membership/epoch 3
;;  :membership/members {did {:joined 0 :left nil} ...}   ; left=nil → current
;;  :membership/log [{:epoch 1 :op :add :who did :at "..."} ...]}
;;
;; `members` keeps departed members with `:left` set rather than dissoc-ing
;; them, because invariant 5 makes their history part of the record and because
;; `still-readable-epochs` needs their interval afterwards.

(defn init
  "A new compartment at epoch 0 whose only member is `founder`."
  [compartment founder at]
  {:membership/compartment compartment
   :membership/epoch 0
   :membership/members {founder {:joined 0 :left nil}}
   :membership/log [{:epoch 0 :op :init :who founder :at at}]})

(defn current-members
  "The dids in the group right now."
  [state]
  (into #{} (keep (fn [[did m]] (when (nil? (:left m)) did)))
        (:membership/members state)))

(defn member?
  [state did]
  (contains? (current-members state) did))

(defn members-at
  "Who was in the group during `epoch`. Answers for past epochs too — that is
  what makes an access record checkable after the fact."
  [state epoch]
  (into #{} (keep (fn [[did {:keys [joined left]}]]
                    (when (and (<= joined epoch)
                               (or (nil? left) (< epoch left)))
                      did)))
        (:membership/members state)))

(defn can-decrypt?
  "Whether `did` legitimately holds the chain key for `epoch`. This is a
  statement about what the protocol handed them, not about what they could
  obtain by other means."
  [state did epoch]
  (contains? (members-at state epoch) did))

(defn still-readable-epochs
  "The epochs `did` can still read after leaving — i.e. what the removal did
  NOT protect. Empty only if they never held a key."
  [state did]
  (when-let [{:keys [joined left]} (get (:membership/members state) did)]
    (let [last-held (dec (or left (inc (:membership/epoch state))))]
      (when (<= joined last-held) [joined last-held]))))

;; ---------------------------------------------------------------------------
;; Transitions
;; ---------------------------------------------------------------------------

(defn- advance
  [state op did at]
  (let [e (inc (:membership/epoch state))]
    [e (update state :membership/log conj {:epoch e :op op :who did :at at})]))

(defn add-member
  "Admit `did` at a fresh epoch.

  Returns `{:state s' :effect e}` or `{:error :already-a-member}`.

  The effect names a NEW sender key even though nobody left: handing the
  existing chain key to a joiner would let them derive the keys of messages
  already sent on that chain, which is invariant 3. Advancing costs one
  distribution round and is the only way to make the join forward-only."
  [state did at]
  (if (member? state did)
    {:error :already-a-member :did did}
    (let [[e state'] (advance state :add did at)
          state' (assoc-in state' [:membership/members did] {:joined e :left nil})
          state' (assoc state' :membership/epoch e)]
      {:state state'
       :effect {:epoch e
                :create-sender-key true
                :reason :join-must-not-read-past
                :distribute-to (current-members state')
                :never-distribute-to #{}
                :still-readable-by {}}})))

(defn remove-member
  "Remove `did` at a fresh epoch and re-key for everyone who remains.

  Returns `{:state s' :effect e}` or `{:error :not-a-member}`.

  `:still-readable-by` is the honest part: it reports the epoch range the
  removed member keeps. **Callers must not drop it.** Protecting those epochs
  requires re-sealing their objects under the new epoch, which this layer
  cannot do and does not pretend to."
  [state did at]
  (if-not (member? state did)
    {:error :not-a-member :did did}
    (let [[e state'] (advance state :remove did at)
          state' (assoc-in state' [:membership/members did :left] e)
          state' (assoc state' :membership/epoch e)
          remaining (current-members state')]
      {:state state'
       :effect {:epoch e
                :create-sender-key true
                :reason :removal-requires-fresh-chain
                :distribute-to remaining
                :never-distribute-to #{did}
                :still-readable-by {did (still-readable-epochs state' did)}
                :re-seal-required (still-readable-epochs state' did)}})))

(defn rotate
  "Advance the epoch without a membership change — scheduled rotation, or a
  reaction to a suspicion that does not yet justify removal.

  This is the `when` half the README says is missing: the policy that decides
  the schedule lives in the caller (for etzhayyim, the clearance grade's
  review interval), and this is the transition it drives."
  [state reason at]
  (let [[e state'] (advance state :rotate reason at)
        state' (assoc state' :membership/epoch e)]
    {:state state'
     :effect {:epoch e
              :create-sender-key true
              :reason reason
              :distribute-to (current-members state')
              :never-distribute-to #{}
              :still-readable-by {}}}))

;; ---------------------------------------------------------------------------
;; Checking
;; ---------------------------------------------------------------------------

(defn violations
  "Re-derive the invariants from the log alone and report any that the state
  does not satisfy. Used as a self-check after replay: a membership record you
  cannot re-derive from its own log is not a record."
  [state]
  (let [log (:membership/log state)
        epochs (map :epoch log)]
    (cond-> []
      (not= epochs (sort epochs))
      (conj :epoch-not-monotonic)

      (not= (count epochs) (count (distinct epochs)))
      (conj :duplicate-epoch)

      (not= (:membership/epoch state) (last epochs))
      (conj :epoch-head-mismatch)

      (some (fn [[_ {:keys [joined left]}]] (and left (< left joined))) (:membership/members state))
      (conj :left-before-joined)

      (empty? (current-members state))
      (conj :no-members-left))))

(defn replay
  "Rebuild state from `log` and compare with `state`. Returns
  `{:ok true}` or `{:ok false :difference ...}`."
  [state]
  (let [log (:membership/log state)
        {:keys [who at]} (first log)
        rebuilt (reduce (fn [s {:keys [op who at]}]
                          (case op
                            :init s
                            :add (:state (add-member s who at) s)
                            :remove (:state (remove-member s who at) s)
                            :rotate (:state (rotate s who at) s)))
                        (init (:membership/compartment state) who at)
                        (rest log))]
    (if (= (dissoc rebuilt :membership/log) (dissoc state :membership/log))
      {:ok true}
      {:ok false
       :difference {:expected (dissoc state :membership/log)
                    :rebuilt (dissoc rebuilt :membership/log)}})))

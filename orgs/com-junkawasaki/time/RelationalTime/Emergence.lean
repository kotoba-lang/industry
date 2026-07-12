import RelationalTime.Relation

/-! # Emergent clocks

A clock is a derived labelling compatible with local change. It is not part of
the event ontology. Different compatible clocks are allowed; causal order is
the invariant content.
-/

namespace RelationalTime

universe u

/-- A relational clock strictly advances on every observed local step. -/
structure Clock (system : EventSystem) where
  tick : system.Event → Nat
  advances : ∀ {a b}, system.step a b → tick a < tick b

namespace Clock

theorem before_advances {system : EventSystem} (clock : Clock system)
    {a b : system.Event} (h : system.before a b) : clock.tick a < clock.tick b := by
  induction h with
  | single h => exact clock.advances h
  | tail _ h ih => exact Nat.lt_trans ih (clock.advances h)

/-- Existence of a clock certifies that the relational dynamics is acyclic. -/
theorem causal {system : EventSystem} (clock : Clock system) : system.IsCausal := by
  intro event h
  exact (Nat.lt_irrefl _) (clock.before_advances h)

/-- Regraduation preserves time when it is strictly monotone. -/
def regraduate {system : EventSystem} (clock : Clock system) (f : Nat → Nat)
    (monotone : ∀ {a b}, a < b → f a < f b) : Clock system where
  tick := f ∘ clock.tick
  advances h := monotone (clock.advances h)

end Clock

/-- Events simultaneous for a clock need not be identical or causally related. -/
def simultaneous {system : EventSystem} (clock : Clock system)
    (a b : system.Event) : Prop := clock.tick a = clock.tick b

end RelationalTime

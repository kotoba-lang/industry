import Mathlib.Logic.Relation

/-! # Relational time

Time is reconstructed from local precedence, rather than supplied as a
background coordinate. `before` is the observable, transitive causal order.
-/

namespace RelationalTime

universe u

/-- A system contributes events and one-step precedence observations. -/
structure EventSystem where
  Event : Type u
  step : Event → Event → Prop

namespace EventSystem

/-- Causal precedence is a non-empty chain of local observations. -/
def before (system : EventSystem) : system.Event → system.Event → Prop :=
  Relation.TransGen system.step

theorem step_before {system : EventSystem} {a b : system.Event}
    (h : system.step a b) : system.before a b :=
  Relation.TransGen.single h

theorem before_trans {system : EventSystem} {a b c : system.Event}
    (hab : system.before a b) (hbc : system.before b c) : system.before a c :=
  hab.trans hbc

/-- A causal system has no event preceding itself. -/
def IsCausal (system : EventSystem) : Prop :=
  ∀ event, ¬ system.before event event

theorem before_asymm {system : EventSystem} (causal : system.IsCausal)
    {a b : system.Event} (hab : system.before a b) : ¬ system.before b a := by
  intro hba
  exact causal a (hab.trans hba)

end EventSystem
end RelationalTime

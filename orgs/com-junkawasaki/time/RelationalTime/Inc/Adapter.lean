import RelationalTime.Emergence
import IncidenceTheory

/-! # Inc adapter

An oriented Inc endpoint becomes a local temporal observation. Negative
endpoints precede their incidence; positive endpoints follow it. Zero endpoints
carry no temporal orientation.
-/

namespace RelationalTime

open IncidenceCore

universe u

def incStep {I R T : Type u} [DecidableEq I]
    (inc : Incidence I R T) (a b : I) : Prop :=
  (∃ endpoint ∈ inc.boundary b, endpoint.i = a ∧ endpoint.sign = Sign.neg) ∨
  (∃ endpoint ∈ inc.boundary a, endpoint.i = b ∧ endpoint.sign = Sign.pos)

def fromInc {I R T : Type u} [DecidableEq I]
    (inc : Incidence I R T) : EventSystem where
  Event := I
  step := incStep inc

theorem negativeEndpoint_step {I R T : Type u} [DecidableEq I]
    (inc : Incidence I R T) {cell : I} {endpoint : Endpoint I R}
    (member : endpoint ∈ inc.boundary cell) (negative : endpoint.sign = Sign.neg) :
    (fromInc inc).step endpoint.i cell := by
  left
  exact ⟨endpoint, member, rfl, negative⟩

theorem positiveEndpoint_step {I R T : Type u} [DecidableEq I]
    (inc : Incidence I R T) {cell : I} {endpoint : Endpoint I R}
    (member : endpoint ∈ inc.boundary cell) (positive : endpoint.sign = Sign.pos) :
    (fromInc inc).step cell endpoint.i := by
  right
  exact ⟨endpoint, member, rfl, positive⟩

end RelationalTime

import RelationalTime
import Init.System.IO

inductive Event where | source | split | left | right | joined
deriving DecidableEq, Repr

def localStep : Event → Event → Prop
  | .source, .split | .split, .left | .split, .right
  | .left, .joined | .right, .joined => True
  | _, _ => False

def demo : RelationalTime.EventSystem := ⟨Event, localStep⟩

def demoClock : RelationalTime.Clock demo where
  tick
    | .source => 0 | .split => 1 | .left => 2 | .right => 2 | .joined => 3
  advances := by
    intro a b h
    cases a <;> cases b <;> simp [demo, localStep] at h ⊢

def main : IO Unit := do
  IO.println "Relational / emergent time reference experiment"
  IO.println s!"source={demoClock.tick .source}, split={demoClock.tick .split}"
  IO.println s!"left={demoClock.tick .left}, right={demoClock.tick .right}"
  IO.println s!"joined={demoClock.tick .joined}"
  if demoClock.tick .left == demoClock.tick .right &&
      demoClock.tick .source < demoClock.tick .joined then
    IO.println "RELATIONAL_TIME_PASS"
  else
    throw <| IO.userError "RELATIONAL_TIME_FAIL"

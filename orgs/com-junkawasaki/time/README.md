# time — relational and emergent time

`time` is a Lean 4 model in the `inc` / `inc-rqm` lineage. It interprets time
without assuming an external global clock:

1. signed incidence boundaries induce **local precedence** observations;
2. their transitive closure is **relational time** (`before`);
3. a compatible event labelling is an **emergent clock** (`Clock`).

The invariant object is causal order. A numeric tick is a derived coordinate
and may be replaced by any strictly monotone regraduation. Independent events
may share a tick, as the executable fork/join example demonstrates.

## Checked claims

- every local step is causal precedence;
- causal precedence is transitive;
- every clock advances along every causal chain;
- existence of a clock implies absence of causal loops;
- strictly monotone regraduation preserves clocks;
- negative Inc endpoints precede a cell and positive endpoints follow it.

These claims are deliberately finite and order-theoretic. The project does not
yet claim a unique clock, a Lorentzian metric, continuum spacetime, quantum
gravity, or a derivation of physical time from dynamics.

## Verify

```bash
npx nbb verify.cljs               # full: cache get → lake build → marker scan → run
npx nbb verify.cljs --scan-only   # unproved-marker scan only
```

## Next design layer

The next milestone is a finite DAG construction that computes the canonical
depth clock (longest causal chain), followed by observer-relative clocks and
clock comparison along `inc-rqm` interaction records.

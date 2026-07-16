# ADR-2607102300: `kotoba-lang/standard-model` — opening "Phase 1" (GTG field equations / the $h$–$\Omega$ relation), superseding the "action principle out of scope" boundary for this one item

## Status

Accepted. Scope-opening ADR — no implementation lands with this ADR itself. It
records the decision to pursue a specific, previously-excluded piece of Gauge
Theory Gravity, and sets the verification bar that any follow-up PR must clear
before merging.

## Context

ADR-2607051500 (original `standard-model` landing) and ADR-2607101700/2607101900
(Phase 0a–0c, the GTG rotation/position-gauge sectors) both explicitly listed
**"the GTG action principle and field equations"** as out of scope. Phase 0d and
0e (ADR-2607101900's follow-ups, PRs #7/#8) each tried to reach the true
Lasenby-Doran-Gull curvature scalar $R$ without touching that boundary, and both
independently hit the same wall from different angles:

- Phase 0d: translating the vector·bivector curvature contraction into this
  codebase's array representation had unresolved index/sign risk with no
  independent reference value.
- Phase 0e: attempting an independent numeric check (linearized-GR Ricci scalar,
  computed two ways) found there is **no $h_\mu(x) \to \Omega_\mu(x)$
  correspondence anywhere in this codebase** — GTG's position gauge field $h$
  is not an ordinary-GR tetrad (both of its indices are components in the same
  flat background space), so the standard tetrad-postulate formula doesn't
  transfer verbatim.

Both dead ends are symptoms of the same fact: in GTG, $h$ and $\Omega$ are
**independent** gauge potentials by construction — nothing forces a relation
between them a priori. The relation only appears as the **equation of motion**
obtained by varying the gravitational action with respect to $\Omega$ (the GTG
analogue of the vanishing-torsion / first-Cartan-structure-equation condition
in the tetrad formalism of ordinary GR). There is no way to get the $h$–$\Omega$
relation, and therefore no way to get the true curvature scalar, without
implementing at least the $\Omega$-field equation of motion — which means
touching the action principle.

The owner has asked to open this scope deliberately rather than keep bouncing
off it from adjacent angles.

## Decision

Open a new phase, **Phase 1: GTG field equations**, scoped specifically to:

1. The GTG gravitational action (vacuum, source-free case only — no matter
   coupling, no cosmological constant term, no Yang-Mills/Dirac/Higgs terms
   from the rest of `kotoba.sm.standard-model` folded in yet).
2. The $\Omega$-field equation of motion obtained by varying that action with
   respect to $\Omega$ — the GTG analogue of the vanishing-torsion condition,
   giving $\Omega$ algebraically in terms of $h$ and its derivatives in the
   source-free case.
3. Only *after* (1)–(2) land with independent numeric verification: revisit the
   true linear curvature scalar $R$ (Phase 0d/0e's original target), now that
   an actual $h\to\Omega$ map exists to test it against.

**Explicitly still out of scope for Phase 1** (unchanged from ADR-2607051500 /
ADR-2607101700's boundary, not implicitly widened by this ADR): matter coupling
to gravity (the full three-factor-gauge-plus-gravity covariant derivative),
the Einstein multivector / stress-energy sourcing, any GR-equivalence *proof*
(the equivalence is cited as an established 1998 literature result throughout
this repo, not re-derived), quantization/RGE/scattering/lattice, and any
dark-sector or de-Sitter extension. Phase 1 is bounded to the source-free
$\Omega(h)$ relation and, contingent on that, the curvature scalar — nothing
broader is authorized by this ADR.

**Verification bar, given this repo's track record on exactly this class of
derivation** (the `self-interaction-term` slot-order bug fixed in PR #6 was a
real, merged, silently-wrong index convention; Phase 0d/0e both separately
declined to guess at under-specified conventions): any PR implementing the
$\Omega$-field equation must clear the same bar Phase 0e attempted and
Phase 0d's structure-constant fix actually achieved —

- At least two independent, mutually-corroborating primary/secondary sources
  for the exact equation (not just one paper skimmed once), **and**
- A genuine independent numeric check (e.g. reproduce a known GTG worked
  example from the literature, or cross-check against the linearized-GR
  vanishing-torsion condition in a simple case) — not merely "the formula looks
  like the one in the PDF."

If that bar cannot be cleared, the correct outcome is the same as Phase 0d/0e's:
document the investigation honestly, do not implement, and defer again. Opening
this phase is permission to keep trying with a wider toolkit (the action
principle itself), not permission to lower the evidentiary bar that has been
enforced throughout Phase 0.

## Consequences

- This ADR does not, by itself, change any code. Follow-up ADRs or PR
  descriptions referencing this one should record what was actually
  implemented (mirroring the ADR-2607101700 → ADR-2607101900 pattern of a
  scope-decision ADR followed by a landing-record ADR once something merges).
- If Phase 1 succeeds, the repo will for the first time contain a genuine
  (source-free) GTG field equation, not merely field-content algebra — a
  qualitative change from every prior ADR's "classical field algebra only,
  not an action/field-equation engine" framing. That framing should be
  revisited explicitly in whatever ADR records Phase 1's landing, not left
  stale.
- If Phase 1 fails to clear the verification bar (a live possibility, given two
  prior attempts from adjacent angles both failed), the correct record is
  another honest non-implementation writeup, same as Phase 0d/0e, and the
  curvature-scalar chase should not be reopened a fourth time without a
  materially new approach (e.g. finding worked numeric examples in the
  literature, or independent expert consultation) rather than another
  literature re-read.

# ADR-2607101700: `kotoba-lang/standard-model` — Gauge Theory Gravity rotation-gauge sector (Phase 0a) + generic non-compact structure-constants fix

## Status

Accepted and landed (Phase 0a — so(1,3) rotation-gauge sector — merged; the
structure-constants generalization it surfaced as a bug is in review, CI green,
pending merge). Position gauge field / derived metric / curvature / GTG action /
GR-equivalence / dark sector remain explicitly out of scope, deferred to a future
phase (see Consequences).

**Landed 2026-07-09:**
[`kotoba-lang/standard-model#1`](https://github.com/kotoba-lang/standard-model/pull/1)
merged as `315ec62e88f77aa02d62e3d05d90e08edcac1997` — new namespace
`kotoba.sm.gtg`, 30 tests / 428 assertions (up from 22/85 at ADR-2607051500),
CI green (JDK 17+21). Superproject `manifest/west.yml` pin advanced to this
commit via the sanctioned GitHub API single-entry commit
(`858f537b49283b88512e2920ea1128695759016c`), server-side pin verification
(exists upstream / reachable from default branch / fast-forward from old pin)
confirmed via `gh api .../compare` before the write.

**In review 2026-07-09:**
[`kotoba-lang/standard-model#2`](https://github.com/kotoba-lang/standard-model/pull/2)
— fixes a bug in `kotoba.sm.gauge/structure-constants` that PR #1's own honesty
tests surfaced (see Context). 33 tests / 1114 assertions, CI green (JDK 17+21),
not yet merged as of this ADR's authoring.

## Context

This is a direct follow-up to ADR-2607051500 (`kotoba-lang/standard-model`'s
original six namespaces: `kotoba.sm.{complex,tensor,vector-field,spinor,gauge,
standard-model}`, the classical Standard Model field algebra — gauge fields for
SU(3)×SU(2)×U(1), i.e. the strong/weak/electromagnetic forces, plus Dirac
fermions and the Higgs mechanism).

The owner asked (this session) for a computational approach to unifying gravity,
gauge fields, spacetime geometry, entropy/information (Landauer's principle), and
dark matter/dark energy into one framework, in a "Google co-scientist"
generate-critique-synthesize style. A `Workflow` run (3 candidate unification
ansätze, judged against 5 known-limit/consistency criteria) produced no candidate
that passed cleanly — all three were flagged `fail`/`partial` on at least one
criterion, with concrete defects (a "phantom" Gauss-Bonnet term that the written
action doesn't actually expand to, a dimensional mismatch in a proposed dark-energy
scalar potential, and conflation of the Λ→0 limit with the true flat-spacetime
limit). The synthesis step's most load-bearing observation was structural, not
about any one candidate: **this repo has no gravity content at all** — none of the
existing six namespaces include a metric that varies, a connection, or curvature —
so any credible extension must start by adding an established, literature-faithful
gravity sector before attempting anything speculative (dark sector, unification
with the internal gauge groups, etc.).

The owner chose the narrowest defensible starting point: **Gauge Theory Gravity**
(Lasenby, Doran & Gull, "Gravity, gauge theories and geometric algebra", 1998),
which reformulates general relativity as two gauge fields (a position gauge $h_\mu$
and a rotation gauge $\Omega_\mu$) on a flat spacetime algebra background, rather
than as curvature of a manifold — and, specifically, only its rotation-gauge
sector, because GTG's own well-known observation is that $SO(1,3)^+$ can be gauged
exactly like the compact internal groups already in `kotoba.sm.gauge`: the Lorentz
algebra's six bivector generators $T^{ab}=\frac{i}{4}[\gamma^a,\gamma^b]$ (built
from `kotoba.sm.spinor`'s existing Dirac gamma matrices) can be handed directly to
`kotoba.sm.gauge`'s *generic* (not SU(2)/SU(3)-specific) structure-constant /
covariant-derivative / field-strength machinery in place of an internal-symmetry
generator set.

**This worked, but surfaced a real bug, not just a successful reuse.**
`kotoba.sm.gauge/structure-constants`'s formula, $f^{abc}=-2i\,\mathrm{Tr}([T^a,T^b]T^c)$,
hard-codes the assumption $\mathrm{Tr}(T^aT^b)=\tfrac12\delta^{ab}$ — true for the
compact generators it was written against (Pauli/2 for SU(2), Gell-Mann/2 for
SU(3)) but **not** for $so(1,3)$'s noncompact bivector generators, whose own trace
pairing $K^{AB}=\mathrm{Tr}(T^AT^B)$ is diagonal but $\pm1$ (not a uniform $+1/2$):
$-1$ for the three boost-type generators, $+1$ for the three rotation-type
generators (the indefinite Minkowski signature showing up directly in the
generators' own Killing-form-like pairing). Applied as-is, the existing function
silently returned values $2K^{DD}$ times the genuine $so(1,3)$ structure constants
— always double the correct magnitude, and **wrong-signed** whenever the third
index was boost-type. PR #1 recorded this honestly (a dedicated, non-massaged test
comparing the "raw" output against an independently-derived "true" value for all
216 structure-constant triples) rather than silently accepting wrong numbers; PR #2
then generalized `structure-constants` to compute each generator set's own
trace-Gram matrix and use it directly, proven backward-compatible for the existing
compact groups (see Decision).

## Decision

### PR #1 — `kotoba.sm.gtg` (rotation-gauge sector, Phase 0a only)

New namespace `kotoba.sm.gtg`, scoped to exactly six items, explicitly bounded (see
its own extensive namespace docstring):

1. The six $so(1,3)$ bivector generators $T^{ab}=\frac{i}{4}[\gamma^a,\gamma^b]$,
   $a<b$, as 4×4 complex matrices, built from `kotoba.sm.spinor`'s existing gamma
   matrices (reusing `sigma-munu`, not reimplementing the commutator).
2. A numeric check that these six generators close the Lorentz algebra
   $[T^{ab},T^{cd}]=i(\eta^{bc}T^{ad}-\eta^{ac}T^{bd}-\eta^{bd}T^{ac}+\eta^{ad}T^{bc})$.
3. The honesty check described above: `generator-trace-gram`,
   `true-structure-constants` (an independent derivation via direct commutator
   projection, not going through `kotoba.sm.gauge` at all), and
   `compact-group-trace-normalization-holds?` — kept in the namespace even after
   PR #2's fix, as a standing, re-checkable record of *why* the noncompact case
   differs, not merely a one-time assertion.
4. The rotation gauge field $\Omega_\mu$ (6-component bivector-valued potential,
   the same data shape `kotoba.sm.gauge` already uses for $A_\mu^a$) and its field
   strength / curvature bivector $R_{\mu\nu}$, via
   `kotoba.sm.gauge/field-strength` applied to this generator set unmodified.
5. The spin-connection covariant derivative on a Dirac spinor,
   $D_\mu\psi=\partial_\mu\psi-\tfrac12\Omega_\mu^{ab}T_{ab}\psi$, via
   `kotoba.sm.gauge/covariant-derivative` unmodified.
6. A flat-limit check: at $\Omega_\mu=0$ everywhere, this reduces *exactly* (not
   merely approximately) to `kotoba.sm.spinor`'s existing free Dirac-equation
   residual.

A related, independently-verified noncompactness symptom: the three boost-type
generators are anti-Hermitian, not Hermitian, while the three rotation-type
generators are Hermitian — the textbook reason (Peskin & Schroeder §3.2, whose
$S^{\mu\nu}=\frac{i}{4}[\gamma^\mu,\gamma^\nu]$ is literally this namespace's
$T^{ab}$) the Dirac spinor representation of the Lorentz group is not unitary.

### PR #2 — generalize `kotoba.sm.gauge/structure-constants`

Replaces the hard-coded $-2i$ formula with

$$f^{ABD} = -i\,\frac{\mathrm{Tr}([T_A,T_B]T_D)}{K^{DD}}, \qquad K^{AB}=\mathrm{Tr}(T_AT_B)$$

computed from the generator set's own trace-Gram matrix, via a new
`generator-gram` / `diagonal-gram-real`. **Scope limit, enforced at runtime, not
just documented:** only a *diagonal* $K$ is supported — `diagonal-gram-real`
throws `ex-info` otherwise. Every generator set actually used in this repo (U(1)
trivial; SU(2) Pauli/2; SU(3) Gell-Mann/2; $so(1,3)$'s six bivectors) has a
diagonal Gram matrix, so this is not a practical limitation today; a fully general
(non-orthogonal-basis) version would need a matrix inverse and is deliberately
deferred as documented future work.

**Backward compatibility, proven not asserted:** for the historical convention
$K^{DD}=\tfrac12$ uniformly, $-i/K^{DD}=-2i$, so the new formula is algebraically
identical to the old one. New regression tests reimplement the *old* formula
independently and check element-wise equality against the new generic output for
every SU(2) ($3\times3\times3$) and SU(3) ($8\times8\times8$) structure-constant
triple — in addition to the pre-existing textbook-value tests
(`su2-structure-constants`/`su3-structure-constants`), which continue to pass
unchanged. Function signature is unchanged (`(structure-constants generators)`,
with an optional `eps`), so no other call site in the repo needed modification.

**so(1,3) after the fix:** `kotoba.sm.gtg`'s "raw" (via `kotoba.sm.gauge`) and
"true" (independently derived) structure constants now agree exactly — maximum
$|{\rm raw}-{\rm true}|$ over all 216 triples is $0.0$. The test that used to
document the divergence (`raw-structure-constants-diverge-from-genuine-ones`) was
renamed to `structure-constants-now-match-genuine-values-after-gauge-fix` with
inverted assertions, and its docstring records the before/after rather than
deleting the history of the bug.

### Manifest / superproject reflection

Followed the sanctioned `:manifest-workflow :canonical :api-single-entry` path
(`repos.edn`, CLAUDE.md): fetched the superproject's `manifest/west.yml` tip blob
SHA, edited only the `standard-model` entry's `revision:` line, PUT via GitHub API
with that SHA for optimistic locking (no local commit to the superproject's shared
working checkout, which was mid-divergence from other concurrent session activity
at the time — exactly the scenario this workflow exists to avoid touching). Pin
advancement verified server-side first (`gh api .../compare` showed
`ahead_by:1, behind_by:0` from the prior pin, i.e. a pure fast-forward, and that
the new commit is on `standard-model`'s default branch).

## Consequences

**In scope, implemented and merged (PR #1) or in review (PR #2):** exactly the six
items enumerated above, plus the generalized, backward-compatible
`structure-constants`.

**Explicitly out of scope, deferred to a future phase if pursued (unchanged from
what PR #1's own namespace docstring commits to):**

- The position gauge field $h_\mu$, the derived spacetime metric
  $g_{\mu\nu}=h_\mu\cdot h_\nu$, and the recovery of `kotoba.sm.tensor`'s fixed
  Minkowski metric as the $h_\mu\to\mathrm{identity}$ special case.
- Riemann/Ricci curvature scalars built from $R_{\mu\nu}$, the Einstein
  multivector, and the full GTG action principle and field equations.
- Any proof (or even a first numeric check) of equivalence to General Relativity
  in this codebase — GTG↔GR equivalence is cited here as an established literature
  result (Lasenby–Doran–Gull 1998), not re-derived.
- de Sitter / $Cl(1,4)$ extensions, MacDowell–Mansouri-style constructions, or any
  dark-matter/dark-energy/entropy sector. The pre-implementation `Workflow` review
  found concrete, unresolved defects in every candidate that attempted this (see
  Context) — none of that speculative material was carried into either merged/
  reviewed PR.
- Path-integral quantization, renormalization/RGE, scattering amplitudes, lattice
  simulation — unchanged scope boundary from ADR-2607051500.

Do not read this ADR as claiming gravity, let alone a unified field theory, is
implemented here. It records exactly two things: (1) a literature-faithful,
narrowly-scoped port of GTG's rotation-gauge sector that reuses existing generic
gauge machinery without modification, and (2) a real bug in that generic machinery
which the port's own honesty-first test discipline surfaced and a follow-up PR
fixed and proved backward-compatible. Both are useful and correct on their own
terms; neither is "gravity" or "unification."
